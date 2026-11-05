import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/features/home/pages/chat/chat_page.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_spotlight_tour.dart';
import 'package:ui/features/welcome/pages/onboarding/onboarding_choice_page.dart';
import 'package:ui/features/welcome/pages/onboarding/onboarding_definitions.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const terminalChannel = MethodChannel(
    'cn.com.omnimind.bot/SpecialPermissionEvent',
  );
  const assistsChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const accountChannel = MethodChannel('cn.com.omnimind.bot/account');
  late List<String> terminalCalls;
  late bool accountSignedIn;
  late int loginCalls;

  Map<String, dynamic> profilePayload({
    String apiKey = '',
    bool configured = false,
  }) {
    return <String, dynamic>{
      'profiles': <Map<String, dynamic>>[
        <String, dynamic>{
          'id': 'deepseek-official',
          'name': 'DeepSeek',
          'baseUrl': 'https://api.deepseek.com',
          'apiKey': apiKey,
          'sourceType': 'deepseek',
          'readOnly': false,
          'ready': configured,
          'statusText': '',
          'configured': configured,
          'protocolType': 'deepseek',
          'wireApi': 'chat_completions',
        },
      ],
      'editingProfileId': 'deepseek-official',
    };
  }

  Widget buildTestApp() {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      locale: const Locale('zh'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: const OnboardingChoicePage(),
    );
  }

  Future<void> showFinder(WidgetTester tester, Finder finder) async {
    await tester.ensureVisible(finder);
    await tester.pump(const Duration(milliseconds: 50));
    if (tester.getCenter(finder).dy < 180) {
      await tester.drag(
        find.byType(SingleChildScrollView).last,
        const Offset(0, 180),
      );
      await tester.pump(const Duration(milliseconds: 50));
    }
  }

  Future<void> openPermissionsPage(WidgetTester tester) async {
    await tester.pumpAndSettle();
    expect(find.text('开启应用权限'), findsOneWidget);
  }

  Future<void> openProviderPage(WidgetTester tester) async {
    await openPermissionsPage(tester);
    final permissionsNext = find.byKey(
      const ValueKey('tutorial-permissions-next'),
    );
    await showFinder(tester, permissionsNext);
    await tester.tap(permissionsNext);
    await tester.pumpAndSettle();
  }

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    ModelsDevCatalogService.setCatalogForTesting(
      const ModelsDevCatalog(providers: {}),
    );
    terminalCalls = <String>[];
    accountSignedIn = false;
    loginCalls = 0;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(terminalChannel, (call) async {
      terminalCalls.add(call.method);
      switch (call.method) {
        case 'isBackgroundRunAllowed':
        case 'isOverlayPermission':
        case 'isInstalledAppsPermissionGranted':
        case 'isPublicStorageAccessGranted':
          return false;
      }
      return null;
    });
    messenger.setMockMethodCallHandler(assistsChannel, (call) async {
      switch (call.method) {
        case 'listModelProviderProfiles':
          return profilePayload();
        case 'getSceneModelBindings':
          return <Map<String, dynamic>>[];
        case 'saveModelProviderProfile':
          final args = Map<dynamic, dynamic>.from(call.arguments as Map);
          return <String, dynamic>{
            'id': 'deepseek-official',
            'name': args['name'],
            'baseUrl': args['baseUrl'],
            'apiKey': args['apiKey'],
            'sourceType': args['sourceType'],
            'readOnly': false,
            'ready': true,
            'statusText': '',
            'configured': true,
            'protocolType': args['protocolType'],
            'wireApi': args['wireApi'],
          };
        case 'fetchProviderModels':
          return <Map<String, dynamic>>[
            <String, dynamic>{
              'id': 'deepseek-chat',
              'displayName': 'DeepSeek Chat',
            },
            <String, dynamic>{
              'id': 'text-embedding-v1',
              'displayName': 'Text Embedding',
            },
          ];
        case 'saveSceneModelBinding':
          return <Map<String, dynamic>>[];
      }
      return null;
    });
    messenger.setMockMethodCallHandler(accountChannel, (call) async {
      if (call.method == 'login') {
        accountSignedIn = true;
        loginCalls += 1;
        return <String, Object?>{
          'id': 'learner',
          'email': 'learner@example.com',
        };
      }
      if (call.method == 'requestRegistrationCode') {
        return <String, Object?>{
          'requestId': 'test-registration-code',
          'expiresInSeconds': 300,
        };
      }
      if (call.method == 'getOverview') {
        return <String, Object?>{
          'user': <String, Object?>{
            'id': 'learner',
            'email': 'learner@example.com',
            'role': 'user',
            'status': 'active',
          },
          'settings': <String, Object?>{
            'mode': 'platform',
            'platformAvailable': true,
          },
        };
      }
      if (call.method == 'getSessionState') {
        return <String, Object?>{
          'configured': true,
          'signedIn': accountSignedIn,
        };
      }
      return null;
    });
  });

  tearDown(() {
    ModelsDevCatalogService.resetForTesting();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(terminalChannel, null);
    messenger.setMockMethodCallHandler(assistsChannel, null);
    messenger.setMockMethodCallHandler(accountChannel, null);
  });

  testWidgets(
    'tutorial opens permissions without environment choices or setup',
    (tester) async {
      tester.view.physicalSize = const Size(390, 700);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(buildTestApp());
      await tester.pumpAndSettle();

      expect(find.text('开启应用权限'), findsOneWidget);
      expect(find.text('选择本地 Linux 系统'), findsNothing);
      expect(find.text('选择开发环境'), findsNothing);
      expect(find.text('配置 Agent 与连接工具'), findsNothing);
      expect(terminalCalls, isNot(contains('setEmbeddedTerminalDistribution')));
      expect(terminalCalls, isNot(contains('prepareTermuxLiveWrapper')));
      expect(terminalCalls, isNot(contains('cancelEmbeddedTerminalInit')));
      expect(
        tester
            .widget<IconButton>(
              find.byKey(const ValueKey('tutorial-permissions-next')),
            )
            .onPressed,
        isNotNull,
      );

      await tester.tap(find.byKey(const ValueKey('tutorial-permissions-next')));
      await tester.pumpAndSettle();
      expect(find.text('登录与注册'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'tutorial dots and arrows retain navigation after simplification',
    (tester) async {
      tester.view.physicalSize = const Size(390, 700);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(buildTestApp());
      await tester.pumpAndSettle();
      final navigation = find.byKey(
        const ValueKey('tutorial-pagination-navigation'),
      );
      final initialNavigationRect = tester.getRect(navigation);
      final back = find.byKey(const ValueKey('tutorial-bottom-back'));
      expect(tester.widget<IconButton>(back).onPressed, isNull);
      for (var index = 0; index < tutorialNavigationPages.length; index++) {
        expect(
          find.byKey(ValueKey<String>('tutorial-page-dot-$index')),
          findsOneWidget,
        );
      }

      await tester.drag(
        find.byKey(const ValueKey('tutorial-page-swipe-surface')),
        const Offset(-300, 0),
      );
      await tester.pumpAndSettle();
      expect(find.text('登录与注册'), findsOneWidget);
      expect(tester.getRect(navigation), initialNavigationRect);
      await tester.tap(back);
      await tester.pumpAndSettle();
      expect(find.text('开启应用权限'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('tutorial-page-dot-1')));
      await tester.pumpAndSettle();
      expect(find.text('登录与注册'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tutorial-provider-next')));
      await tester.pumpAndSettle();
      expect(find.text('选择模型服务商'), findsOneWidget);
      await tester.drag(
        find.byKey(const ValueKey('tutorial-page-swipe-surface')),
        const Offset(300, 0),
      );
      await tester.pumpAndSettle();
      expect(find.text('登录与注册'), findsOneWidget);
      expect(tester.getRect(navigation), initialNavigationRect);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('tutorial pagination stays usable on a narrow viewport', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(buildTestApp());
    await tester.pumpAndSettle();
    expect(
      find.byKey(
        ValueKey<String>(
          'tutorial-page-dot-${tutorialNavigationPages.length - 1}',
        ),
      ),
      findsOneWidget,
    );
    await tester.drag(
      find.byKey(const ValueKey('tutorial-page-swipe-surface')),
      const Offset(-260, 0),
    );
    await tester.pumpAndSettle();
    expect(find.text('登录与注册'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'account form is inline and custom models open provider selection first',
    (tester) async {
      tester.view.physicalSize = const Size(430, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(buildTestApp());
      await tester.pump(const Duration(milliseconds: 50));
      await openProviderPage(tester);

      expect(find.text('登录与注册'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('tutorial-inline-account-auth')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('standalone-account-auth-page')),
        findsNothing,
      );
      expect(find.text('账号与 AI 服务'), findsNothing);
      expect(find.byKey(const ValueKey('account-login-email')), findsOneWidget);
      expect(find.byKey(const ValueKey('submit-auth')), findsOneWidget);
      expect(find.byType(ProviderVendorIcon), findsNothing);

      await tester.enterText(
        find.byKey(const ValueKey('account-login-email')),
        'learner@example.com',
      );
      await tester.tap(
        find.byKey(const ValueKey('account-auth-mode-register')),
      );
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('auth-confirm-password-field')),
        findsOneWidget,
      );
      expect(
        tester
            .widget<TextFormField>(
              find.byKey(const ValueKey('account-register-email')),
            )
            .controller!
            .text,
        'learner@example.com',
      );
      expect(find.byType(ChatPage), findsNothing);

      // A form swipe switches authentication modes, not tutorial pages.
      await tester.drag(
        find.byKey(const Key('account-auth-page-view')),
        const Offset(300, 0),
      );
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('account-login-email')), findsOneWidget);
      expect(find.text('登录与注册'), findsOneWidget);
      expect(find.text('下一步：了解聊天界面'), findsNothing);
      expect(
        find.byKey(const ValueKey('tutorial-skip-models-visible')),
        findsNothing,
      );
      expect(find.text('可选：配置自己的模型'), findsOneWidget);
      await tester.tap(
        find.byKey(const ValueKey('account-auth-mode-register')),
      );
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const ValueKey('account-register-password')),
        'draft-password',
      );
      await tester.ensureVisible(find.text('发送'));
      await tester.tap(find.text('发送'));
      await tester.pumpAndSettle();
      expect(find.text('重新发送'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('tutorial-provider-next')));
      await tester.pumpAndSettle();
      expect(find.text('选择模型服务商'), findsOneWidget);
      expect(find.byKey(const ValueKey('account-login-email')), findsNothing);
      expect(
        find.byKey(const ValueKey('tutorial-provider-api-key')),
        findsNothing,
      );
      for (final id in <String>[
        'deepseek',
        'moonshot',
        'mimo',
        'openai',
        'anthropic',
        'custom',
      ]) {
        expect(
          find.byKey(ValueKey<String>('tutorial-provider-icon-$id')),
          findsOneWidget,
        );
      }
      expect(find.byType(ProviderVendorIcon), findsNWidgets(5));

      await tester.tap(
        find.byKey(const ValueKey('tutorial-provider-moonshot')),
      );
      await tester.pumpAndSettle();
      expect(find.text('连接 Kimi'), findsOneWidget);
      expect(
        tester
            .widget<TextField>(
              find.byKey(const ValueKey('tutorial-provider-base-url')),
            )
            .controller!
            .text,
        'https://api.moonshot.cn/v1',
      );
      expect(
        find.byKey(const ValueKey('tutorial-provider-api-key')),
        findsOneWidget,
      );

      await tester.tap(find.byKey(const ValueKey('tutorial-bottom-back')));
      await tester.pumpAndSettle();
      expect(find.text('选择模型服务商'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tutorial-bottom-back')));
      await tester.pumpAndSettle();
      expect(find.text('登录与注册'), findsOneWidget);
      expect(
        tester
            .widget<TextFormField>(
              find.byKey(const ValueKey('account-register-email')),
            )
            .controller!
            .text,
        'learner@example.com',
      );
      expect(
        tester
            .widget<TextFormField>(
              find.byKey(const ValueKey('account-register-password')),
            )
            .controller!
            .text,
        'draft-password',
      );
      expect(find.text('重新发送'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('tutorial-bottom-back')));
      await tester.pumpAndSettle();
      expect(find.text('开启应用权限'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('signing in stays in the tutorial and shows the account state', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(430, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(buildTestApp());
    await tester.pump(const Duration(milliseconds: 50));
    await openProviderPage(tester);
    await tester.enterText(
      find.byKey(const ValueKey('account-login-email')),
      'learner@example.com',
    );
    await tester.enterText(
      find.byKey(const ValueKey('account-login-password')),
      'password',
    );
    await tester.tap(find.byKey(const ValueKey('submit-auth')));
    await tester.pumpAndSettle();
    expect(loginCalls, 1);
    expect(find.text('当前账号已登录'), findsOneWidget);
    expect(find.text('learner@example.com'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('tutorial-inline-account-auth')),
      findsOneWidget,
    );
    expect(find.byType(ChatPage), findsNothing);
    expect(find.text('账号与 AI 服务'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'inline registration stays usable with a keyboard on a narrow phone',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetViewInsets);
      await tester.pumpWidget(buildTestApp());
      await tester.pump(const Duration(milliseconds: 50));
      await openProviderPage(tester);
      await tester.tap(
        find.byKey(const ValueKey('account-auth-mode-register')),
      );
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const ValueKey('account-register-email')),
        'learner@example.com',
      );
      tester.view.viewInsets = const FakeViewPadding(bottom: 300);
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('tutorial-inline-account-auth')),
        findsOneWidget,
      );
      expect(find.byType(ChatPage), findsNothing);
      final submit = find.byKey(const ValueKey('account-register-submit'));
      await tester.scrollUntilVisible(
        submit,
        120,
        scrollable: find
            .descendant(
              of: find.byKey(const PageStorageKey('account-register-page')),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      await tester.pumpAndSettle();
      expect(submit.hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('permissions page lists authorization items and continues', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(430, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(buildTestApp());
    await tester.pump(const Duration(milliseconds: 50));

    await openPermissionsPage(tester);

    expect(find.text('开启应用权限'), findsOneWidget);
    expect(find.text('0 / 3 项核心授权已就绪'), findsOneWidget);
    expect(find.text('核心权限'), findsOneWidget);
    expect(find.text('扩展能力'), findsOneWidget);
    expect(find.text('通知与提醒'), findsOneWidget);
    for (final key in <String>[
      'tutorial-permission-battery',
      'tutorial-permission-overlay',
      'tutorial-permission-apps',
      'tutorial-permission-storage',
      'tutorial-permission-shizuku',
      'tutorial-permission-notification',
    ]) {
      final row = find.byKey(ValueKey<String>(key));
      await showFinder(tester, row);
      expect(row, findsOneWidget);
    }
    expect(find.text('去开启'), findsNWidgets(4));

    await tester.tap(
      find.byKey(const ValueKey('tutorial-permission-notification')),
    );
    await tester.pump();

    final permissionsNext = find.byKey(
      const ValueKey('tutorial-permissions-next'),
    );
    expect(tester.widget<IconButton>(permissionsNext).onPressed, isNotNull);
    await showFinder(tester, permissionsNext);
    await tester.tap(permissionsNext);
    await tester.pumpAndSettle();

    expect(find.text('登录与注册'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('provider setup can be skipped without selecting a model', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(430, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(buildTestApp());
    await tester.pump(const Duration(milliseconds: 50));

    await openProviderPage(tester);
    final skipModels = find.byKey(const ValueKey('tutorial-skip-models'));
    await showFinder(tester, skipModels);
    await tester.tap(skipModels);
    for (var frame = 0; frame < 15; frame += 1) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    expect(find.byType(ChatPage), findsOneWidget);
    expect(find.byKey(const ValueKey('chat-spotlight-tour')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('provider models can be assigned before opening the chat guide', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(430, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(buildTestApp());
    await tester.pump(const Duration(milliseconds: 50));

    await openProviderPage(tester);

    final providerNext = find.byKey(const ValueKey('tutorial-provider-next'));
    await showFinder(tester, providerNext);
    await tester.tap(providerNext);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('tutorial-provider-deepseek')));
    await tester.pumpAndSettle();

    await tester.enterText(
      find.byKey(const ValueKey('tutorial-provider-api-key')),
      'sk-test',
    );
    final connect = find.byKey(const ValueKey('tutorial-provider-connect'));
    await showFinder(tester, connect);
    await tester.tap(connect);
    await tester.pumpAndSettle();

    expect(find.text('已准备 2 个模型'), findsOneWidget);
    expect(find.text('确认可用模型'), findsOneWidget);

    final modelsNext = find.byKey(const ValueKey('tutorial-models-next'));
    await showFinder(tester, modelsNext);
    await tester.tap(modelsNext);
    await tester.pumpAndSettle();

    expect(find.text('配置对话与 Agent 模型'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('tutorial-scene-scene.dispatch.model')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('tutorial-scene-scene.memory.embedding')),
      findsNothing,
    );

    final primaryScenesNext = find.byKey(
      const ValueKey('tutorial-primary-scenes-next'),
    );
    await showFinder(tester, primaryScenesNext);
    await tester.tap(primaryScenesNext);
    await tester.pumpAndSettle();

    expect(find.text('配置记忆模型'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('tutorial-scene-scene.memory.embedding')),
      findsOneWidget,
    );

    final saveScenes = find.byKey(const ValueKey('tutorial-save-scenes'));
    final stickyAction = find.byKey(
      const ValueKey('tutorial-sticky-primary-action'),
    );
    final memoryNavigation = find.byKey(
      const ValueKey('tutorial-pagination-navigation'),
    );
    expect(
      find.descendant(of: stickyAction, matching: saveScenes),
      findsOneWidget,
    );
    expect(
      tester.getRect(saveScenes).bottom,
      lessThan(tester.getRect(memoryNavigation).top),
    );
    await showFinder(tester, saveScenes);
    await tester.tap(saveScenes);
    for (var frame = 0; frame < 15; frame += 1) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    expect(find.byType(ChatPage), findsOneWidget);
    expect(find.byKey(const ValueKey('chat-spotlight-tour')), findsOneWidget);
    expect(
      find.byKey(const ValueKey('chat-spotlight-navigation')),
      findsNothing,
    );

    for (var step = 1; step < ChatSpotlightTour.stepCount; step += 1) {
      await tester.tapAt(const Offset(8, 350));
      await tester.pump(const Duration(milliseconds: 260));
      expect(
        find.byKey(ValueKey<String>('chat-spotlight-card-$step')),
        findsOneWidget,
      );
    }
    await tester.tapAt(const Offset(8, 350));
    await tester.pumpAndSettle();

    expect(find.byType(ChatPage), findsNothing);
    expect(
      find.byKey(const ValueKey('tutorial-completion-page')),
      findsOneWidget,
    );
    expect(find.text('教程已完成'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('tutorial-start-exploring')),
      findsOneWidget,
    );
    expect(
      StorageService.getBool(StorageKeys.welcomeCompleted, defaultValue: false),
      isFalse,
    );

    await tester.tap(find.byKey(const ValueKey('tutorial-start-exploring')));
    await tester.pump();

    expect(
      StorageService.getBool(StorageKeys.welcomeCompleted, defaultValue: false),
      isTrue,
    );
    expect(tester.takeException(), isNull);
  });
}
