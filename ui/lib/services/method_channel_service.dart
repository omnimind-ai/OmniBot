import 'package:flutter/services.dart';
import 'package:ui/core/router/go_router_manager.dart';

/// Method Channel服务，用于原生与Flutter之间的路由通信
class MethodChannelService {
  static const MethodChannel _channel = MethodChannel('ui_router_channel');
  
  /// 初始化Method Channel服务
  static void initialize() {
    _channel.setMethodCallHandler(_handleMethodCall);
  }
  
  /// 处理来自原生的方法调用
  static Future<dynamic> _handleMethodCall(MethodCall call) async {
    try {
      switch (call.method) {
        case 'openLegacyPage':
          final route = (call.arguments as Map)['route'] as String;
          await GoRouterManager.openLegacyPage(route);
          return null;
        case 'setInitialRouteAndNavigate':
          return _handleSetInitialRouteAndNavigate(call.arguments);
        case 'go':
          return _handleGo(call.arguments);
        case 'clearAndNavigateTo':
          return _handleClearAndNavigateTo(call.arguments);
        case 'push':
          return _handlePush(call.arguments);
        case 'pop':
          return _handlePop(call.arguments);
        case 'canPop':
          return _handleCanPop();
        case 'resetToHomeAndPush':
          return _handleResetToHomeAndPush(call.arguments);
        default:
          throw PlatformException(
            code: 'UNIMPLEMENTED',
            message: 'Method ${call.method} not implemented',
          );
      }
    } catch (e) {
      throw PlatformException(
        code: 'ERROR',
        message: 'Error handling method call: $e',
      );
    }
  }

  static RouteOptions _parseOptions(dynamic arguments) {
    if (arguments['options'] == null) {
      return const RouteOptions();
    }
    final optionsMap = Map<String, dynamic>.from(arguments['options'] as Map);
    return RouteOptions.fromMap(optionsMap);
  }

  static Map<String, dynamic> _handleSetInitialRouteAndNavigate(dynamic arguments) {
    if (arguments is! Map) {
      throw ArgumentError('Arguments must be a Map');
    }

    final route = arguments['route'] as String?;
    if (route == null) {
      throw ArgumentError('Route parameter is required');
    }
    final options = _parseOptions(arguments);

    GoRouterManager.setInitialRoute(route);
    GoRouterManager.go(route, options: options);

    return {
      'success': true,
      'message': 'Initial route set and navigated successfully',
    };
  }

  static Map<String, dynamic> _handleGo(dynamic arguments) {
    if (arguments is! Map) {
      throw ArgumentError('Arguments must be a Map');
    }

    final route = arguments['route'] as String?;
    if (route == null) {
      throw ArgumentError('Route parameter is required');
    }

    final extra = arguments['extra'];
    final queryParams = arguments['queryParams'] as Map<String, dynamic>?;
    final options = _parseOptions(arguments);

    GoRouterManager.go(route, extra: extra, queryParams: queryParams, options: options);

    return {
      'success': true,
      'message': 'Go navigation completed successfully',
    };
  }

  static Map<String, dynamic> _handleClearAndNavigateTo(dynamic arguments) {
    if (arguments is! Map) {
      throw ArgumentError('Arguments must be a Map');
    }

    final route = arguments['route'] as String?;
    if (route == null) {
      throw ArgumentError('Route parameter is required');
    }

    final extra = arguments['extra'];
    final queryParams = arguments['queryParams'] as Map<String, dynamic>?;
    final options = _parseOptions(arguments);

    GoRouterManager.clearAndNavigateTo(route, extra: extra, queryParams: queryParams, options: options);

    return {
      'success': true,
      'message': 'Navigation completed successfully',
    };
  }

  static Map<String, dynamic> _handlePush(dynamic arguments) {
    if (arguments is! Map) {
      throw ArgumentError('Arguments must be a Map');
    }
    final route = arguments['route'] as String?;
    if (route == null) {
      throw ArgumentError('Route parameter is required');
    }
    final extra = arguments['extra'];
    final queryParams = arguments['queryParams'] as Map<String, dynamic>?;
    final options = _parseOptions(arguments);

    GoRouterManager.push(route, extra: extra, queryParams: queryParams, options: options);

    return {
      'success': true,
      'message': 'Push navigation completed successfully',
    };
  }
  
  /// 处理返回上一页的方法调用
  static Map<String, dynamic> _handlePop(dynamic arguments) {
    final result = arguments;
    final canPop = GoRouterManager.canPop();
    
    if (canPop) {
      GoRouterManager.pop(result);
      return {
        'success': true,
        'message': 'Pop navigation completed successfully',
      };
    } else {
      return {
        'success': false,
        'message': 'Cannot pop - no routes to pop',
      };
    }
  }
  
  /// 处理检查是否可以返回的方法调用
  static Map<String, dynamic> _handleCanPop() {
    final canPop = GoRouterManager.canPop();
    return {
      'success': true,
      'canPop': canPop,
    };
  }

  static Map<String, dynamic> _handleResetToHomeAndPush(dynamic arguments) {
    if (arguments is! Map) {
      throw ArgumentError('Arguments must be a Map');
    }
    final route = arguments['route'] as String?;
    if (route == null) {
      throw ArgumentError('Route parameter is required');
    }
    final extra = arguments['extra'];
    final queryParams = arguments['queryParams'] as Map<String, dynamic>?;
    final options = _parseOptions(arguments);

    GoRouterManager.resetToHomeAndPush(route, extra: extra, queryParams: queryParams, options: options);

    return {
      'success': true,
      'message': 'Reset to home and push navigation initiated',
    };
  }
}
