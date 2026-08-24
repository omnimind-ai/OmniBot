import 'dart:convert';
import 'package:flutter/cupertino.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/features/home/pages/chat/chat_page.dart';
import 'go_router_config.dart';
import 'package:flutter/foundation.dart';
import 'logging_observer.dart';
import 'package:ui/services/method_channel_service.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/widgets/predictive_back_gesture_wrapper.dart';

class RouteOptions {
  final bool noAnim;

  const RouteOptions({this.noAnim = false});

  Map<String, dynamic> toMap() {
    return {'noAnim': noAnim};
  }

  factory RouteOptions.fromMap(Map<String, dynamic>? map) {
    if (map == null) return const RouteOptions();
    return RouteOptions(noAnim: map['noAnim'] == true);
  }
}

/// GoRouter路由管理器
class GoRouterManager {
  static const String homeRoute = '/home/chat';
  static final GlobalKey<NavigatorState> _rootNavigatorKey =
      GlobalKey<NavigatorState>();
  static final GlobalKey<NavigatorState> _shellNavigatorKey =
      GlobalKey<NavigatorState>();
  static String? _initialRoute;
  static bool _isSubEngine = false;

  /// 全局 RouteObserver，用于监听页面生命周期
  static final RouteObserver<ModalRoute<void>> routeObserver =
      RouteObserver<ModalRoute<void>>();

  static void setInitialRoute(String? route) {
    print('[GoRouterManager] setInitialRoute: $route');
    _initialRoute = route;
  }

  static String? getInitialRoute() => _initialRoute;

  static void setSubEngine(bool isSubEngine) {
    _isSubEngine = isSubEngine;
  }

  static bool get isSubEngine => _isSubEngine;

  static GlobalKey<NavigatorState> get rootNavigatorKey => _rootNavigatorKey;

  static GlobalKey<NavigatorState> get shellNavigatorKey => _shellNavigatorKey;

  static Object? _wrapExtra(Object? extra, RouteOptions options) {
    if (extra is Map) {
      return {
        ...Map<String, dynamic>.from(extra),
        '_routeOptions': options.toMap(),
      };
    }
    // extra 不是 Map，直接返回原值，不做包装
    return extra;
  }

  static ({RouteOptions options, Object? data}) _parseExtra(Object? extra) {
    if (extra is! Map) {
      return (options: const RouteOptions(), data: extra);
    }

    final map = Map<String, dynamic>.from(extra);
    final optionsMap = map.remove('_routeOptions') as Map<String, dynamic>?;
    final options = RouteOptions.fromMap(optionsMap);

    return (options: options, data: map.isEmpty ? null : map);
  }

  static Page<dynamic> _buildPage({
    required LocalKey key,
    required Widget child,
    required RouteOptions options,
    String? name,
  }) {
    if (options.noAnim) {
      return CustomTransitionPage(
        key: key,
        child: child,
        name: name,
        transitionDuration: Duration.zero,
        reverseTransitionDuration: Duration.zero,
        transitionsBuilder: (context, animation, secondaryAnimation, child) {
          return child;
        },
      );
    } else {
      return CustomTransitionPage(
        key: key,
        child: child,
        name: name,
        transitionDuration: const Duration(milliseconds: 250),
        reverseTransitionDuration: const Duration(milliseconds: 250),
        transitionsBuilder: (context, animation, secondaryAnimation, child) {
          // 开关开启时统一为全宽滑动手势转场，关闭时回退为原有 Fade 转场。
          return PredictiveBackGestureWrapper(
            animation: animation,
            secondaryAnimation: secondaryAnimation,
            transitionBuilder:
                (context, animation, secondaryAnimation, child) =>
                    FadeTransition(opacity: animation, child: child),
            child: child,
          );
        },
      );
    }
  }

  /// Overlay-style stack transition:
  /// the incoming page stays above the previous page and covers it while
  /// sliding in, and slides out above it on pop.
  static Page<dynamic> buildActivitySlidePage({
    required LocalKey key,
    required Widget child,
    String? name,
  }) {
    const duration = Duration(milliseconds: 300);

    return CustomTransitionPage(
      key: key,
      child: child,
      name: name,
      maintainState: true,
      transitionDuration: duration,
      reverseTransitionDuration: duration,
      transitionsBuilder: (context, animation, secondaryAnimation, child) {
        return PredictiveBackGestureWrapper(
          animation: animation,
          secondaryAnimation: secondaryAnimation,
          transitionBuilder: (context, animation, secondaryAnimation, child) =>
              CupertinoPageTransition(
                primaryRouteAnimation: animation,
                secondaryRouteAnimation: secondaryAnimation,
                linearTransition: false,
                child: child,
              ),
          child: child,
        );
      },
    );
  }

  static GoRoute _wrapRoute(GoRoute route) {
    return GoRoute(
      path: route.path,
      name: route.name,
      redirect: route.redirect,
      routes: route.routes
          .map((r) => r is GoRoute ? _wrapRoute(r) : r)
          .toList(),
      pageBuilder: (context, state) {
        final parsed = _parseExtra(state.extra);

        Widget child;
        if (route.builder != null) {
          child = route.builder!(context, state);
        } else if (route.pageBuilder != null) {
          return route.pageBuilder!(context, state);
        } else {
          child = const SizedBox.shrink();
        }

        return _buildPage(
          key: state.pageKey,
          child: child,
          options: parsed.options,
          name: route.name,
        );
      },
    );
  }

  static Object? getRealExtra(Object? extra) {
    final parsed = _parseExtra(extra);
    return parsed.data;
  }

  static GoRouter createRouter(WidgetRef ref) {
    MethodChannelService.initialize();

    final wrappedRoutes = AppRouterConfig.getAllRoutes()
        .map(_wrapRoute)
        .toList();

    print('initialLocation: $_initialRoute');

    // Determine effective initial location with onboarding guard
    final welcomeCompleted =
        StorageService.getBool(
          StorageKeys.welcomeCompleted,
          defaultValue: false,
        ) ??
        false;
    final requestedInitial = _initialRoute ?? homeRoute;
    final effectiveInitial =
        (!welcomeCompleted &&
            !requestedInitial.startsWith('/welcome') &&
            !_isSubEngine)
        ? '/welcome/choice'
        : requestedInitial;

    return GoRouter(
      navigatorKey: _rootNavigatorKey,
      initialLocation: effectiveInitial,
      redirect: _isSubEngine
          ? null
          : (context, state) {
              final completed =
                  StorageService.getBool(
                    StorageKeys.welcomeCompleted,
                    defaultValue: false,
                  ) ??
                  false;
              final location = state.matchedLocation;
              final isWelcomeRoute = location.startsWith('/welcome');

              // Not completed onboarding -> redirect non-welcome routes
              if (!completed && !isWelcomeRoute) {
                return '/welcome/choice';
              }
              // Already completed -> redirect welcome routes to home
              if (completed && isWelcomeRoute) {
                return homeRoute;
              }
              return null;
            },
      observers: [routeObserver, if (kDebugMode) LoggingRouterObserver()],
      routes: [
        GoRoute(
          path: '/',
          pageBuilder: (context, state) {
            final parsed = _parseExtra(state.extra);
            final child = _isSubEngine
                ? const SizedBox.shrink()
                : const ChatPage();
            return _buildPage(
              key: state.pageKey,
              child: child,
              options: parsed.options,
              name: '/', // 添加根路径名称
            );
          },
        ),
        ...wrappedRoutes,
      ],
    );
  }

  /// 将 queryParams 转换为 query string 并拼接到路由上
  static String _buildRouteWithQueryParams(
    String route,
    Map<String, dynamic>? queryParams,
  ) {
    if (queryParams == null || queryParams.isEmpty) {
      return route;
    }

    final uri = Uri.parse(route);
    final Map<String, String> queryStringMap = {};

    // 遍历 queryParams，将复杂对象转换为 JSON 字符串
    queryParams.forEach((key, value) {
      if (value is Map || value is List) {
        queryStringMap[key] = jsonEncode(value);
      } else {
        queryStringMap[key] = value.toString();
      }
    });

    // 构建新的 URI，保留原有的 query parameters（如果有）
    final newUri = uri.replace(
      queryParameters: {...uri.queryParameters, ...queryStringMap},
    );

    return newUri.toString();
  }

  static void go(
    String route, {
    Object? extra,
    Map<String, dynamic>? queryParams,
    RouteOptions? options,
  }) {
    print(
      '[GoRouterManager] go: $route, extra: $extra, queryParams: $queryParams, options: $options',
    );
    print('[GoRouterManager] go 调用堆栈: ${StackTrace.current}');
    final context = _rootNavigatorKey.currentContext;
    if (context != null) {
      final finalRoute = _normalizeHomeRoute(
        _buildRouteWithQueryParams(route, queryParams),
      );
      final wrappedExtra = _wrapExtra(extra, options ?? const RouteOptions());
      GoRouter.of(context).go(finalRoute, extra: wrappedExtra);
    }
  }

  static void clearAndNavigateTo(
    String route, {
    Object? extra,
    Map<String, dynamic>? queryParams,
    RouteOptions? options,
  }) {
    print(
      '[GoRouterManager] clearAndNavigateTo: $route, extra: $extra, queryParams: $queryParams, options: $options',
    );
    print('[GoRouterManager] clearAndNavigateTo 调用堆栈: ${StackTrace.current}');
    final context = _rootNavigatorKey.currentContext;
    if (context != null) {
      final finalRoute = _normalizeHomeRoute(
        _buildRouteWithQueryParams(route, queryParams),
      );
      final wrappedExtra = _wrapExtra(extra, options ?? const RouteOptions());
      GoRouter.of(context).go(finalRoute, extra: wrappedExtra);
    }
  }

  static void push(
    String route, {
    Object? extra,
    Map<String, dynamic>? queryParams,
    RouteOptions? options,
  }) {
    final context = _rootNavigatorKey.currentContext;
    print(
      '[GoRouterManager] push route: $route, extra: $extra, queryParams: $queryParams, options: $options',
    );
    print('[GoRouterManager] push context: $context');
    if (context != null) {
      final finalRoute = _normalizeHomeRoute(
        _buildRouteWithQueryParams(route, queryParams),
      );
      final wrappedExtra = _wrapExtra(extra, options ?? const RouteOptions());
      if (_isHomeChatRoute(finalRoute)) {
        GoRouter.of(context).go(finalRoute, extra: wrappedExtra);
        return;
      }
      GoRouter.of(context).push(finalRoute, extra: wrappedExtra);
    }
  }

  /// push 并等待返回结果，支持 .then() 回调
  static Future<T?> pushForResult<T>(
    String route, {
    Object? extra,
    Map<String, dynamic>? queryParams,
    RouteOptions? options,
  }) async {
    final context = _rootNavigatorKey.currentContext;
    print(
      '[GoRouterManager] push route(for result): $route, extra: $extra, queryParams: $queryParams, options: $options',
    );
    print('[GoRouterManager] push context: $context');
    if (context != null) {
      final finalRoute = _normalizeHomeRoute(
        _buildRouteWithQueryParams(route, queryParams),
      );
      final wrappedExtra = _wrapExtra(extra, options ?? const RouteOptions());
      return GoRouter.of(context).push<T>(finalRoute, extra: wrappedExtra);
    }
    return null;
  }

  static void pop([Object? result]) {
    print('[GoRouterManager] pop: $result');
    final context = _rootNavigatorKey.currentContext;
    if (context != null && context.canPop()) {
      context.pop(result);
    }
  }

  /// 替换当前路由（先 pop 再 push），避免路由栈堆积
  static void pushReplacement(
    String route, {
    Object? extra,
    Map<String, dynamic>? queryParams,
    RouteOptions? options,
  }) {
    final context = _rootNavigatorKey.currentContext;
    print(
      '[GoRouterManager] pushReplacement route: $route, extra: $extra, queryParams: $queryParams, options: $options',
    );
    if (context != null) {
      final finalRoute = _normalizeHomeRoute(
        _buildRouteWithQueryParams(route, queryParams),
      );
      final wrappedExtra = _wrapExtra(extra, options ?? const RouteOptions());
      GoRouter.of(context).pushReplacement(finalRoute, extra: wrappedExtra);
    }
  }

  static bool canPop() {
    final context = _rootNavigatorKey.currentContext;
    final canPop = context?.canPop() ?? false;
    print('[GoRouterManager] canPop: $canPop');
    return canPop;
  }

  static String _normalizeHomeRoute(String route) {
    final uri = Uri.parse(route);
    if (uri.path == '/home/home') {
      return uri.replace(path: homeRoute).toString();
    }
    return route;
  }

  static bool _isHomeChatRoute(String route) {
    final normalized = _normalizeHomeRoute(route);
    final uri = Uri.parse(normalized);
    return uri.path == homeRoute;
  }

  /// 重置回到首页并跳转到指定路由
  /// 如果目标路由就是首页，则只执行 clearAndNavigateTo，不再 push
  static void resetToHomeAndPush(
    String route, {
    Object? extra,
    Map<String, dynamic>? queryParams,
    RouteOptions? options,
  }) {
    final context = _rootNavigatorKey.currentContext;
    print(
      '[GoRouterManager] resetToHomeAndPush route: $route, extra: $extra, queryParams: $queryParams, options: $options',
    );
    if (context == null) return;

    final finalRoute = _normalizeHomeRoute(
      _buildRouteWithQueryParams(route, queryParams),
    );

    // 如果目标是聊天主页（包含 query 参数场景），直接替换到目标路由，不再 push
    if (_isHomeChatRoute(finalRoute)) {
      print(
        '[GoRouterManager] resetToHomeAndPush: target is home, only clearAndNavigateTo',
      );
      clearAndNavigateTo(finalRoute, extra: extra, options: options);
      return;
    }

    // 1. 先回到首页
    clearAndNavigateTo(homeRoute);

    // 2. 在下一帧推入新页面
    WidgetsBinding.instance.addPostFrameCallback((_) {
      // 重新获取 context，因为页面可能已经变动
      final currentContext = _rootNavigatorKey.currentContext;
      if (currentContext != null) {
        final wrappedExtra = _wrapExtra(extra, options ?? const RouteOptions());
        GoRouter.of(currentContext).push(finalRoute, extra: wrappedExtra);
      }
    });
  }
}
