import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/l10n/app_locale_controller.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/services/scheduled_task_scheduler_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme_controller.dart';
import 'package:ui/theme/app_theme_mode.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/liquid_glass_controller.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/startup_account_prompt.dart';
import 'package:ui/widgets/omnibot_error_widget.dart';

import 'core/router/go_router_manager.dart';
import 'services/event_bus.dart';

Future<void> bootstrapMain(List<String> args) async {
  final startupWatch = Stopwatch()..start();
  String? initialRoute;

  // 可以在这里处理从原生传递过来的参数
  if (args.isNotEmpty) {
    // 处理参数的逻辑
    debugPrint('Received args from native: $args');

    // 检查是否有路由参数
    for (var arg in args) {
      if (arg.startsWith('--route=')) {
        initialRoute = arg.substring(8); // 提取路由路径
      }
    }
  } else {
    debugPrint('No args received from native');
  }

  // 设置初始路由
  if (initialRoute != null) {
    GoRouterManager.setInitialRoute(initialRoute);
  }
  WidgetsFlutterBinding.ensureInitialized();
  installOmnibotErrorWidget();
  final prerequisites = initializeMainStartupPrerequisites();
  final container = ProviderContainer();
  await prerequisites;
  try {
    SystemChrome.setSystemUIOverlayStyle(
      AppTheme.overlayStyleForBrightness(
        _resolveStartupBrightness(StorageService.getThemeMode()),
      ),
    );
  } catch (error, stackTrace) {
    debugPrint('[FlutterStartup] system UI style setup failed: $error');
    debugPrint('$stackTrace');
  }

  runApp(
    UncontrolledProviderScope(
      container: container,
      child: MyApp(args: args),
    ),
  );
  unawaited(
    WidgetsBinding.instance.waitUntilFirstFrameRasterized.then((_) async {
      debugPrint(
        '[FlutterStartup] Bootstrap to first rasterized frame: '
        '${startupWatch.elapsedMilliseconds}ms',
      );
      // Submit the usable shell before optional services or legacy cache I/O
      // compete with startup. Post-frame runs before rasterization completes.
      await Future<void>(_initializeDeferredStartupServices);
    }),
  );
}

/// Starts independent platform setup together, settling both before the saved
/// theme is read or the app is mounted. Each step remains fail-open so a
/// transient plugin failure cannot leave the engine behind a blank window.
Future<void> initializeMainStartupPrerequisites({
  Future<void> Function()? configureSystemUi,
  Future<void> Function()? initializeStorage,
}) async {
  await Future.wait<void>([
    _runStartupStep(
      'system UI mode setup',
      configureSystemUi ??
          () => SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge),
    ),
    _runStartupStep(
      'StorageService.init',
      initializeStorage ?? StorageService.init,
    ),
  ]);
}

Future<void> _initializeDeferredStartupServices() async {
  await Future.wait(<Future<void>>[
    _runStartupStep(
      'ModelsDevCatalogService.migrateLegacyCache',
      ModelsDevCatalogService.migrateLegacyCache,
    ),
    _runStartupStep('AppBackgroundService.load', AppBackgroundService.load),
    _runStartupStep(
      'ScheduledTaskSchedulerService.initialize',
      ScheduledTaskSchedulerService.initialize,
    ),
    _runStartupStep(
      'OmnibotResourceService.ensureWorkspacePathsLoaded',
      () async {
        await OmnibotResourceService.ensureWorkspacePathsLoaded();
      },
    ),
  ]);
}

Future<void> _runStartupStep(
  String name,
  Future<void> Function() operation,
) async {
  try {
    await operation();
  } catch (error, stackTrace) {
    debugPrint('[FlutterStartup] $name failed: $error');
    debugPrint('$stackTrace');
  }
}

Brightness _resolveStartupBrightness(AppThemeMode mode) {
  return switch (mode) {
    AppThemeMode.light => Brightness.light,
    AppThemeMode.dark => Brightness.dark,
    AppThemeMode.system =>
      WidgetsBinding.instance.platformDispatcher.platformBrightness,
  };
}

class MyApp extends ConsumerStatefulWidget {
  final List<String> args;
  const MyApp({super.key, this.args = const []});

  @override
  ConsumerState<MyApp> createState() => _MyAppState();
}

class _MyAppState extends ConsumerState<MyApp> {
  late final GoRouter _router;

  @override
  void initState() {
    super.initState();

    final initStart = DateTime.now();
    debugPrint('🎨 [FlutterStartup] MyApp initState start');
    _router = GoRouterManager.createRouter(ref);
    _initializeApp();
    debugPrint(
      "⏱️  [FlutterStartup] MyApp initState cost: ${DateTime.now().difference(initStart).inMilliseconds}ms",
    );
  }

  Future<void> _initializeApp() async {
    final appInitStart = DateTime.now();
    try {
      ref.read(eventListenerProvider);
      debugPrint(
        "⏱️  [FlutterStartup] eventListenerProvider init cost: ${DateTime.now().difference(appInitStart).inMilliseconds}ms",
      );
    } catch (e) {
      debugPrint('⚠️  [FlutterStartup] initializeApp error: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final buildStart = DateTime.now();
    debugPrint('🎨 [FlutterStartup] MyApp build start');

    final widgetBuildStart = DateTime.now();
    final themeMode = ref.watch(appThemeModeProvider).materialThemeMode;
    final resolvedLocale = ref.watch(appResolvedLocaleProvider);
    final glass = ref.watch(liquidGlassSettingsProvider);
    LegacyTextLocalizer.setResolvedLocale(resolvedLocale.locale);
    final widget = MaterialApp.router(
      debugShowCheckedModeBanner: false,
      onGenerateTitle: (context) =>
          AppLocalizations.of(context)?.appName ?? 'Omnibot',
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: themeMode,
      themeAnimationCurve: Curves.easeInOutCubic,
      themeAnimationDuration: const Duration(milliseconds: 220),
      routerConfig: _router,
      locale: resolvedLocale.locale,
      builder: (context, child) {
        final theme = Theme.of(context);
        final brightness = theme.brightness;
        // scaffoldBackgroundColor 由父级 AnimatedTheme 在主题切换时逐帧 lerp,
        // 这里用 ColoredBox 把它显式画出来作为整屏兜底色:
        // - 堵住主题切换瞬间 Flutter 子树短暂透明露出原生 windowBackground 的可能
        // - 让背景过渡显式参与 themeAnimationDuration(220ms)的平滑插值
        return AnnotatedRegion<SystemUiOverlayStyle>(
          value: AppTheme.overlayStyleForBrightness(brightness),
          child: ColoredBox(
            color: theme.scaffoldBackgroundColor,
            child: LiquidGlassScope(
              enabled: glass.enabled,
              quality: glass.quality,
              child: LiquidGlassHost(
                enabled: glass.enabled,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    StartupAccountPrompt(
                      routeListenable: _router.routeInformationProvider,
                      child: child ?? const SizedBox.shrink(),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    );

    debugPrint(
      "⏱️  [FlutterStartup] Widget tree build cost: ${DateTime.now().difference(widgetBuildStart).inMilliseconds}ms",
    );
    debugPrint(
      "✅ [FlutterStartup] MyApp build total cost: ${DateTime.now().difference(buildStart).inMilliseconds}ms",
    );

    return widget;
  }
}
