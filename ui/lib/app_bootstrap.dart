import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/l10n/app_locale_controller.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/features/home/state/predictive_back_controller.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/services/scheduled_task_scheduler_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme_controller.dart';
import 'package:ui/theme/app_theme_mode.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/embedded_terminal_init_overlay.dart';
import 'package:ui/widgets/startup_account_prompt.dart';

import 'core/router/go_router_manager.dart';
import 'services/event_bus.dart';

Future<void> bootstrapMain(List<String> args) async {
  String? initialRoute;

  // Handle parameters passed from the native layer here.
  if (args.isNotEmpty) {
    debugPrint('Received args from native: $args');

    // Look for an initial route parameter.
    for (var arg in args) {
      if (arg.startsWith('--route=')) {
        initialRoute = arg.substring(8); // Extract the route path.
      }
    }
  } else {
    debugPrint('No args received from native');
  }

  // Set the initial route.
  if (initialRoute != null) {
    GoRouterManager.setInitialRoute(initialRoute);
  }
  WidgetsFlutterBinding.ensureInitialized();
  WidgetsBinding.instance.deferFirstFrame();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);

  final container = ProviderContainer();
  await StorageService.init();
  await AppBackgroundService.load();
  await ScheduledTaskSchedulerService.initialize();
  await OmnibotResourceService.ensureWorkspacePathsLoaded();
  SystemChrome.setSystemUIOverlayStyle(
    AppTheme.overlayStyleForBrightness(
      _resolveStartupBrightness(StorageService.getThemeMode()),
    ),
  );

  runApp(
    UncontrolledProviderScope(
      container: container,
      child: MyApp(args: args),
    ),
  );
  WidgetsBinding.instance.allowFirstFrame();
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
      debugPrint('⚠️ [FlutterStartup] initializeApp error: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final buildStart = DateTime.now();
    debugPrint('🎨 [FlutterStartup] MyApp build start');

    final widgetBuildStart = DateTime.now();
    final themeMode = ref.watch(appThemeModeProvider).materialThemeMode;
    final resolvedLocale = ref.watch(appResolvedLocaleProvider);
    final predictiveBackEnabled = ref.watch(predictiveBackEnabledProvider);
    // Predictive-back behavior applies to Android page transitions only.
    final pageTransitionsTheme = PageTransitionsTheme(
      builders: {
        TargetPlatform.android: predictiveBackEnabled
            ? const PredictiveBackPageTransitionsBuilder()
            : const FadeForwardsPageTransitionsBuilder(),
      },
    );
    final lightTheme = AppTheme.lightTheme.copyWith(
      pageTransitionsTheme: pageTransitionsTheme,
    );
    final darkTheme = AppTheme.darkTheme.copyWith(
      pageTransitionsTheme: pageTransitionsTheme,
    );
    LegacyTextLocalizer.setResolvedLocale(resolvedLocale.locale);
    final widget = MaterialApp.router(
      debugShowCheckedModeBanner: false,
      onGenerateTitle: (context) =>
          AppLocalizations.of(context)?.appName ?? 'Omnibot',
      theme: lightTheme,
      darkTheme: darkTheme,
      themeMode: themeMode,
      themeAnimationCurve: Curves.easeInOutCubic,
      themeAnimationDuration: const Duration(milliseconds: 220),
      routerConfig: _router,
      locale: resolvedLocale.locale,
      builder: (context, child) {
        final theme = Theme.of(context);
        final brightness = theme.brightness;
        // Explicitly paint the scaffold background so theme transitions do not
        // expose the native window background for a frame.
        return AnnotatedRegion<SystemUiOverlayStyle>(
          value: AppTheme.overlayStyleForBrightness(brightness),
          child: ColoredBox(
            color: theme.scaffoldBackgroundColor,
            child: Stack(
              fit: StackFit.expand,
              children: [
                StartupAccountPrompt(
                  routeListenable: _router.routeInformationProvider,
                  child: child ?? const SizedBox.shrink(),
                ),
                const EmbeddedTerminalInitToastListener(),
              ],
            ),
          ),
        );
      },
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    );

    debugPrint(
      "⏱️ [FlutterStartup] Widget tree build cost: ${DateTime.now().difference(widgetBuildStart).inMilliseconds}ms",
    );
    debugPrint(
      "✅ [FlutterStartup] MyApp build total cost: ${DateTime.now().difference(buildStart).inMilliseconds}ms",
    );

    return widget;
  }
}
