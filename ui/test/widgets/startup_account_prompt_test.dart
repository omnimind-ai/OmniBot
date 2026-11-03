import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/services/account_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/startup_account_prompt.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const accountChannel = MethodChannel('cn.com.omnimind.bot/account');

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    await StorageService.setBool(StorageKeys.welcomeCompleted, true);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(accountChannel, (call) async {
          if (call.method == 'getSessionState') {
            return <String, Object?>{'configured': true, 'signedIn': false};
          }
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(accountChannel, null);
  });

  testWidgets(
    'checks version policy before showing the signed-out account card',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      tester.view.padding = const FakeViewPadding(bottom: 24);
      tester.view.viewPadding = const FakeViewPadding(bottom: 24);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPadding);
      addTearDown(tester.view.resetViewPadding);
      final navigatorKey = GlobalKey<NavigatorState>();
      final calls = <String>[];

      await tester.pumpWidget(
        MaterialApp(
          navigatorKey: navigatorKey,
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          supportedLocales: const <Locale>[Locale('zh')],
          localizationsDelegates: const <LocalizationsDelegate<dynamic>>[
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          home: StartupAccountPrompt(
            navigatorKey: navigatorKey,
            refreshVersionPolicy: () async => calls.add('version'),
            loadSession: () async {
              calls.add('account');
              return const AccountSessionState(
                configured: true,
                signedIn: false,
              );
            },
            child: const Scaffold(body: Text('home')),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(calls, <String>['version', 'account']);
      expect(
        find.byKey(const ValueKey('startup-account-card')),
        findsOneWidget,
      );
      expect(find.byType(Dialog), findsNothing);
      final panelRect = tester.getRect(find.byType(OmniGlassPanel));
      expect(panelRect.bottom, closeTo(844, 1));
      final header = tester.widget<Image>(
        find.byKey(const ValueKey('startup-account-header-image')),
      );
      expect(header.fit, BoxFit.cover);
      final headerRect = tester.getRect(
        find.byKey(const ValueKey('startup-account-card-header')),
      );
      expect(headerRect.top, closeTo(panelRect.top, 1));
      expect(headerRect.height, lessThanOrEqualTo(120));
      final closeRect = tester.getRect(
        find.byKey(const ValueKey('startup-account-close')),
      );
      expect(closeRect.width, greaterThanOrEqualTo(48));
      expect(closeRect.height, greaterThanOrEqualTo(48));
      expect(closeRect.top - panelRect.top, inInclusiveRange(0, 8));
      expect(
        (header.image as AssetImage).assetName,
        'assets/my/atmosphere-light-mineral-02.webp',
      );
      expect(
        find.byKey(const ValueKey('account-auth-only-surface')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('account-auth-mode-selector')),
        findsOneWidget,
      );
      expect(find.text('登录小万账号'), findsNothing);
      expect(find.text('账号用于同步登录状态和平台额度；登录后官方 AI 会作为可选渠道提供。'), findsNothing);
      expect(
        tester
            .getSize(find.byKey(const ValueKey('startup-account-card-content')))
            .height,
        lessThan(500),
      );
      final submitRect = tester.getRect(
        find.byKey(const ValueKey('submit-auth')),
      );
      expect(panelRect.bottom - 24 - submitRect.bottom, closeTo(16, 1));
      expect(find.text('小万通灵，云启大千'), findsOneWidget);
      final sloganRect = tester.getRect(
        find.byKey(const ValueKey('startup-account-slogan')),
      );
      expect(headerRect.contains(sloganRect.bottomLeft), isTrue);
      expect(headerRect.contains(sloganRect.bottomRight), isTrue);
      final sloganAnimation = tester.widget<TweenAnimationBuilder<double>>(
        find.byKey(const ValueKey('startup-account-slogan-animation')),
      );
      expect(sloganAnimation.duration, const Duration(milliseconds: 420));

      expect(find.text('不再提醒'), findsNothing);
      expect(find.byTooltip('关闭并不再提醒'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('startup-account-close')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('startup-account-card')), findsNothing);
      expect(
        StorageService.getBool(
          StorageKeys.startupAccountPromptDismissed,
          defaultValue: false,
        ),
        isTrue,
      );
    },
  );

  testWidgets('keeps a failed account load compact and retries into the form', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    var accountLoads = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(accountChannel, (call) async {
          if (call.method != 'getSessionState') return null;
          accountLoads += 1;
          if (accountLoads == 2) {
            throw PlatformException(code: 'UNAVAILABLE');
          }
          return <String, Object?>{'configured': true, 'signedIn': false};
        });
    final navigatorKey = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        theme: AppTheme.lightTheme,
        home: StartupAccountPrompt(
          navigatorKey: navigatorKey,
          refreshVersionPolicy: () async {},
          child: const Scaffold(body: Text('home')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(accountLoads, 2);
    expect(find.byKey(const ValueKey('account-login-email')), findsNothing);
    final retry = find.widgetWithText(FilledButton, 'Retry');
    expect(retry.hitTestable(), findsOneWidget);
    expect(tester.getSize(find.byType(OmniGlassPanel)).height, lessThan(400));
    expect(
      tester.getRect(find.byType(OmniGlassPanel)).bottom -
          tester.getRect(retry).bottom,
      lessThanOrEqualTo(32),
    );

    await tester.tap(retry);
    await tester.pumpAndSettle();

    expect(accountLoads, 3);
    expect(find.byKey(const ValueKey('account-login-email')), findsOneWidget);
    expect(
      find.byKey(const ValueKey('submit-auth')).hitTestable(),
      findsOneWidget,
    );
    expect(find.widgetWithText(FilledButton, 'Retry'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('does not show login while secure storage is unavailable', (
    tester,
  ) async {
    final navigatorKey = GlobalKey<NavigatorState>();

    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        home: StartupAccountPrompt(
          navigatorKey: navigatorKey,
          refreshVersionPolicy: () async {},
          loadSession: () async => const AccountSessionState(
            configured: true,
            signedIn: false,
            credentialStorageAvailable: false,
          ),
          child: const Scaffold(body: Text('home')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('startup-account-card')), findsNothing);
  });

  testWidgets('dismissing outside the card also disables future prompts', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final navigatorKey = GlobalKey<NavigatorState>();

    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        home: StartupAccountPrompt(
          navigatorKey: navigatorKey,
          refreshVersionPolicy: () async {},
          loadSession: () async =>
              const AccountSessionState(configured: true, signedIn: false),
          child: const Scaffold(body: Text('home')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('startup-account-card')), findsOneWidget);
    await tester.tapAt(const Offset(4, 4));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('startup-account-card')), findsNothing);
    expect(
      StorageService.getBool(
        StorageKeys.startupAccountPromptDismissed,
        defaultValue: false,
      ),
      isTrue,
    );
  });

  testWidgets('uses the dark satin header in dark mode', (tester) async {
    final navigatorKey = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        themeMode: ThemeMode.dark,
        home: StartupAccountPrompt(
          navigatorKey: navigatorKey,
          refreshVersionPolicy: () async {},
          loadSession: () async =>
              const AccountSessionState(configured: true, signedIn: false),
          child: const Scaffold(body: Text('home')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final header = tester.widget<Image>(
      find.byKey(const ValueKey('startup-account-header-image')),
    );
    expect(
      (header.image as AssetImage).assetName,
      'assets/my/atmosphere-dark-satin-02.webp',
    );
  });

  testWidgets(
    'resizes for registration and preserves input across tap and swipe',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final navigatorKey = GlobalKey<NavigatorState>();
      await tester.pumpWidget(
        MaterialApp(
          navigatorKey: navigatorKey,
          theme: AppTheme.lightTheme,
          home: StartupAccountPrompt(
            navigatorKey: navigatorKey,
            refreshVersionPolicy: () async {},
            loadSession: () async =>
                const AccountSessionState(configured: true, signedIn: false),
            child: const Scaffold(body: Text('home')),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final loginHeight = tester.getSize(find.byType(OmniGlassPanel)).height;
      final loginEmail = find.byKey(const ValueKey('account-login-email'));
      final loginPassword = find.byKey(
        const ValueKey('account-login-password'),
      );
      await tester.enterText(loginEmail, 'person@example.com');
      await tester.enterText(loginPassword, 'login-password');
      await tester.tap(
        find.byKey(const ValueKey('account-auth-mode-register')),
      );
      await tester.pumpAndSettle();

      expect(
        tester.getSize(find.byType(OmniGlassPanel)).height,
        greaterThan(loginHeight + 60),
      );
      final registerEmail = find.byKey(
        const ValueKey('account-register-email'),
      );
      final registerPassword = find.byKey(
        const ValueKey('account-register-password'),
      );
      expect(
        tester.widget<TextFormField>(registerEmail).controller!.text,
        'person@example.com',
      );
      await tester.enterText(registerPassword, 'register-password');
      await tester.drag(registerEmail, const Offset(180, 0));
      await tester.pumpAndSettle();

      expect(
        tester.getSize(find.byType(OmniGlassPanel)).height,
        closeTo(loginHeight, 1),
      );
      expect(
        tester.widget<TextFormField>(loginEmail).controller!.text,
        'person@example.com',
      );
      expect(
        tester.widget<TextFormField>(loginPassword).controller!.text,
        'login-password',
      );
      await tester.drag(loginEmail, const Offset(-180, 0));
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextFormField>(registerPassword).controller!.text,
        'register-password',
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('compact auth card supports a small screen with large text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final navigatorKey = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        theme: AppTheme.lightTheme,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: const TextScaler.linear(1.4)),
          child: child!,
        ),
        home: StartupAccountPrompt(
          navigatorKey: navigatorKey,
          refreshVersionPolicy: () async {},
          loadSession: () async =>
              const AccountSessionState(configured: true, signedIn: false),
          child: const Scaffold(body: Text('home')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      tester
          .getSize(find.byKey(const ValueKey('startup-account-card-content')))
          .height,
      lessThanOrEqualTo(568),
    );
    await tester.tap(find.byKey(const ValueKey('account-auth-mode-register')));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('account-register-email')),
      findsOneWidget,
    );
    final submit = find.byKey(const ValueKey('account-register-submit'));
    await tester.ensureVisible(submit);
    await tester.pumpAndSettle();
    expect(submit.hitTestable(), findsOneWidget);
    final panelRect = tester.getRect(find.byType(OmniGlassPanel));
    final submitRect = tester.getRect(submit);
    expect(submitRect.top, greaterThan(panelRect.top));
    expect(submitRect.bottom, lessThanOrEqualTo(panelRect.bottom));
    expect(tester.takeException(), isNull);
  });

  testWidgets('account form remains usable above the keyboard', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    tester.view.viewInsets = const FakeViewPadding(bottom: 320);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    final navigatorKey = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        theme: AppTheme.lightTheme,
        home: StartupAccountPrompt(
          navigatorKey: navigatorKey,
          refreshVersionPolicy: () async {},
          loadSession: () async =>
              const AccountSessionState(configured: true, signedIn: false),
          child: const Scaffold(body: Text('home')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(tester.getRect(find.byType(OmniGlassPanel)).bottom, closeTo(524, 1));
    await tester.tap(find.byKey(const ValueKey('account-auth-mode-register')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(
      find.byKey(const ValueKey('account-register-email')),
    );
    await tester.enterText(
      find.byKey(const ValueKey('account-register-email')),
      'person@example.com',
    );
    final submit = find.byKey(const ValueKey('account-register-submit'));
    await tester.ensureVisible(submit);
    await tester.pumpAndSettle();
    expect(submit.hitTestable(), findsOneWidget);
    expect(tester.getRect(submit).bottom, lessThanOrEqualTo(524));
    expect(tester.takeException(), isNull);
    await tester.tap(find.byKey(const ValueKey('startup-account-close')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('startup-account-card')), findsNothing);
  });

  testWidgets('does not prompt an already signed-in user', (tester) async {
    final navigatorKey = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        home: StartupAccountPrompt(
          navigatorKey: navigatorKey,
          refreshVersionPolicy: () async {},
          loadSession: () async =>
              const AccountSessionState(configured: true, signedIn: true),
          child: const Scaffold(body: Text('home')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('startup-account-card')), findsNothing);
  });

  testWidgets('prompts after onboarding completes in the same app launch', (
    tester,
  ) async {
    await StorageService.setBool(StorageKeys.welcomeCompleted, false);
    final navigatorKey = GlobalKey<NavigatorState>();
    final routeChanges = ChangeNotifier();
    addTearDown(routeChanges.dispose);
    var versionChecks = 0;
    var accountChecks = 0;
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        home: StartupAccountPrompt(
          navigatorKey: navigatorKey,
          routeListenable: routeChanges,
          refreshVersionPolicy: () async {
            versionChecks += 1;
          },
          loadSession: () async {
            accountChecks += 1;
            return const AccountSessionState(configured: true, signedIn: false);
          },
          child: const Scaffold(body: Text('onboarding')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(versionChecks, 0);
    expect(accountChecks, 0);
    expect(find.byKey(const ValueKey('startup-account-card')), findsNothing);
    expect(find.text('onboarding'), findsOneWidget);

    await StorageService.setBool(StorageKeys.welcomeCompleted, true);
    routeChanges.notifyListeners();
    await tester.pump();
    await tester.pumpAndSettle();

    expect(versionChecks, 1);
    expect(accountChecks, 1);
    expect(find.byKey(const ValueKey('startup-account-card')), findsOneWidget);
  });

  testWidgets('does not prompt again after dismissal is stored', (
    tester,
  ) async {
    await StorageService.setBool(
      StorageKeys.startupAccountPromptDismissed,
      true,
    );
    final navigatorKey = GlobalKey<NavigatorState>();
    var accountChecks = 0;
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        home: StartupAccountPrompt(
          navigatorKey: navigatorKey,
          refreshVersionPolicy: () async {},
          loadSession: () async {
            accountChecks += 1;
            return const AccountSessionState(configured: true, signedIn: false);
          },
          child: const Scaffold(body: Text('home')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(accountChecks, 0);
    expect(find.byKey(const ValueKey('startup-account-card')), findsNothing);
  });
}
