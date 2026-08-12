import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/my/pages/account/account_page.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/account');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('shows a clear message when account server is not configured', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getSessionState') {
            return <String, Object?>{'configured': false, 'signedIn': false};
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();

    expect(find.text('账号服务尚未配置'), findsOneWidget);
    expect(find.textContaining('OMNIBOT_BASE_URL'), findsOneWidget);
    expect(find.byIcon(LucideIcons.cloudOff), findsOneWidget);
  });

  testWidgets('shows login form for a configured signed-out user', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getSessionState') {
            return <String, Object?>{'configured': true, 'signedIn': false};
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();

    expect(find.text('登录小万账号'), findsOneWidget);
    expect(find.text('邮箱'), findsOneWidget);
    expect(find.text('密码'), findsOneWidget);
    expect(find.text('登录'), findsWidgets);
    expect(find.byIcon(LucideIcons.mail), findsOneWidget);
    expect(find.byIcon(LucideIcons.lockKeyhole), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('account-login-submit')))
          .style,
      isNull,
    );

    expect(
      tester
          .getSize(find.byKey(const Key('account-auth-mode-selector')))
          .height,
      40,
    );
    final thumb = tester.widget<Container>(
      find.byKey(const Key('account-auth-mode-thumb')),
    );
    final thumbDecoration = thumb.decoration! as BoxDecoration;
    expect(thumbDecoration.borderRadius, BorderRadius.circular(999));
    expect((thumbDecoration.gradient! as LinearGradient).colors, const <Color>[
      Color(0xFF2DA5F0),
      Color(0xFF1930D9),
    ]);
    expect(
      tester
          .widget<AnimatedAlign>(
            find.byKey(const Key('account-auth-mode-thumb-align')),
          )
          .alignment,
      Alignment.centerLeft,
    );

    final pageView = find.byKey(const Key('account-auth-page-view'));
    expect(
      tester.getSize(find.byKey(const Key('account-auth-content-gap'))).height,
      12,
    );
    expect(
      tester.getTopLeft(find.byKey(const Key('account-login-email'))).dy -
          tester
              .getBottomLeft(
                find.byKey(const Key('account-auth-mode-selector')),
              )
              .dy,
      20,
    );

    Future<void> expectFocusedEmailLabelFullyVisible(Key fieldKey) async {
      final emailField = find.byKey(fieldKey);
      await tester.tap(emailField);
      await tester.pumpAndSettle();

      final emailLabel = find.descendant(
        of: emailField,
        matching: find.text('邮箱'),
      );
      final pageViewport = tester.getRect(pageView);
      final labelRect = tester.getRect(emailLabel);
      expect(labelRect.top, greaterThan(pageViewport.top));
      expect(labelRect.bottom, lessThan(pageViewport.bottom));
    }

    await expectFocusedEmailLabelFullyVisible(const Key('account-login-email'));

    await tester.fling(pageView, const Offset(-600, 0), 1200);
    await tester.pumpAndSettle();

    expect(find.text('创建小万账号'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(
            find.byKey(const Key('account-register-submit')),
          )
          .style,
      isNull,
    );
    expect(
      tester
          .widget<AnimatedAlign>(
            find.byKey(const Key('account-auth-mode-thumb-align')),
          )
          .alignment,
      Alignment.centerRight,
    );

    await expectFocusedEmailLabelFullyVisible(
      const Key('account-register-email'),
    );

    final registerPassword = find.byKey(const Key('account-register-password'));
    final registerPasswordDecorator = tester.widget<InputDecorator>(
      find.descendant(
        of: registerPassword,
        matching: find.byType(InputDecorator),
      ),
    );
    expect(registerPasswordDecorator.decoration.helperText, isNull);
    expect(registerPasswordDecorator.decoration.hintText, isNull);
    expect(find.text('至少 15 个字符'), findsNothing);

    await tester.tap(registerPassword);
    await tester.pump();

    expect(
      tester
          .widget<InputDecorator>(
            find.descendant(
              of: registerPassword,
              matching: find.byType(InputDecorator),
            ),
          )
          .decoration
          .hintText,
      '至少 15 个字符',
    );
    expect(find.text('至少 15 个字符'), findsOneWidget);

    await tester.fling(pageView, const Offset(600, 0), 1200);
    await tester.pumpAndSettle();

    expect(find.text('登录小万账号'), findsOneWidget);
    expect(
      tester
          .widget<AnimatedAlign>(
            find.byKey(const Key('account-auth-mode-thumb-align')),
          )
          .alignment,
      Alignment.centerLeft,
    );

    await tester.tap(find.byKey(const Key('account-auth-mode-register')));
    await tester.pumpAndSettle();

    expect(find.text('创建小万账号'), findsOneWidget);
  });

  testWidgets('shows email quota and platform mode after login', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(375, 812);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getSessionState') {
            return <String, Object?>{'configured': true, 'signedIn': true};
          }
          if (call.method == 'getOverview') {
            return <String, Object?>{
              'user': <String, Object?>{
                'id': 'user-1',
                'email': 'learner@example.com',
                'role': 'user',
                'status': 'active',
              },
              'settings': <String, Object?>{
                'mode': 'platform',
                'keyStorage': 'device',
                'platformAvailable': true,
                'platform': <String, Object?>{
                  'platformEnabled': true,
                  'balanceQuota': 1000,
                  'weeklyLimitQuota': 5000,
                  'weeklyUsedQuota': 1200,
                  'unit': 'new_api_quota',
                },
              },
            };
          }
          if (call.method == 'updateAiMode') {
            return <String, Object?>{
              'mode': 'byok',
              'keyStorage': 'device',
              'platformAvailable': true,
              'platform': <String, Object?>{
                'platformEnabled': true,
                'balanceQuota': 1000,
                'unit': 'new_api_quota',
              },
            };
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();

    expect(find.text('learner@example.com'), findsOneWidget);
    expect(find.text('1000'), findsOneWidget);
    expect(find.text('使用平台额度'), findsOneWidget);
    expect(find.text('本周剩余额度'), findsOneWidget);
    expect(find.text('文字、识图、图片、语音共用，每周一自动恢复'), findsOneWidget);
    expect(find.text('本周已用/预占 1200 / 5000'), findsOneWidget);
    expect(find.text('Key 只保存在当前设备，不会上传账号服务器。'), findsNothing);
    expect(find.text('由小万平台统一提供模型服务，不显示内部 API 端。'), findsNothing);
    expect(find.byIcon(LucideIcons.userRound), findsOneWidget);
    expect(find.byIcon(LucideIcons.coins), findsOneWidget);
    expect(find.byIcon(LucideIcons.circleCheck), findsOneWidget);
    expect(find.byType(Divider), findsOneWidget);
    _expectModeIconsVerticallyCentered(
      tester,
      optionKey: 'account-ai-mode-platform',
      leadingIcon: LucideIcons.cloud,
      trailingIcon: LucideIcons.circleCheck,
    );
    _expectModeIconsVerticallyCentered(
      tester,
      optionKey: 'account-ai-mode-byok',
      leadingIcon: LucideIcons.keyRound,
      trailingIcon: LucideIcons.circle,
    );
    expect(tester.takeException(), isNull);

    await tester.tap(find.text('使用自己的 API Key'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));

    expect(find.text('AI 来源已更新'), findsOneWidget);
    expect(find.byType(SnackBar), findsNothing);
    expect(find.text('配置我的 API Key'), findsOneWidget);
    expect(find.byType(Divider), findsOneWidget);

    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('disables platform mode while platform AI is unavailable', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getSessionState') {
            return <String, Object?>{'configured': true, 'signedIn': true};
          }
          if (call.method == 'getOverview') {
            return <String, Object?>{
              'user': <String, Object?>{
                'id': 'user-1',
                'email': 'learner@example.com',
                'role': 'user',
                'status': 'active',
              },
              'settings': <String, Object?>{
                'mode': 'byok',
                'keyStorage': 'device',
                'platformAvailable': false,
                'platformUnavailableReason': '平台 AI 服务暂未开放',
                'platform': <String, Object?>{
                  'platformEnabled': true,
                  'balanceQuota': 1000,
                  'unit': 'new_api_quota',
                },
              },
            };
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();

    expect(find.text('平台 AI 服务暂未开放'), findsWidgets);
    expect(find.text('1000'), findsNothing);
    expect(find.text('配置我的 API Key'), findsOneWidget);
  });

  testWidgets('resets a forgotten password with a reset-purpose email code', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          switch (call.method) {
            case 'getSessionState':
              return <String, Object?>{'configured': true, 'signedIn': false};
            case 'requestPasswordResetCode':
              return <String, Object?>{
                'requestId': 'reset-request-1',
                'expiresInSeconds': 600,
              };
            case 'resetPassword':
              return null;
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('forgot-password')));
    await tester.pumpAndSettle();

    expect(find.text('重置密码'), findsOneWidget);
    await tester.enterText(
      find.byKey(const ValueKey('auth-email-field')),
      'learner@example.com',
    );
    await tester.tap(find.text('发送'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(
      calls.where((call) => call.method == 'requestPasswordResetCode'),
      hasLength(1),
    );
    expect(
      calls.where((call) => call.method == 'requestRegistrationCode'),
      isEmpty,
    );

    const newPassword = 'NewPass26!';
    await tester.enterText(
      find.byKey(const ValueKey('auth-password-field')),
      newPassword,
    );
    await tester.enterText(
      find.byKey(const ValueKey('auth-confirm-password-field')),
      newPassword,
    );
    await tester.enterText(
      find.byKey(const ValueKey('auth-verification-code-field')),
      '123456',
    );
    await tester.tap(find.byKey(const ValueKey('submit-auth')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    final resetCall = calls.singleWhere(
      (call) => call.method == 'resetPassword',
    );
    expect(resetCall.arguments, <String, Object?>{
      'email': 'learner@example.com',
      'newPassword': newPassword,
      'verificationRequestId': 'reset-request-1',
      'verificationCode': '123456',
    });
    expect(find.text('登录小万账号'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('shows recent platform usage without exposing credentials', (
    tester,
  ) async {
    _setPhoneViewport(tester);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'getSessionState':
              return <String, Object?>{'configured': true, 'signedIn': true};
            case 'getOverview':
              return _signedInOverview();
            case 'listPlatformUsage':
              expect(call.arguments, <String, Object?>{'limit': 20});
              return <Map<String, Object?>>[
                <String, Object?>{
                  'model': 'qwen-official',
                  'promptTokens': 12,
                  'completionTokens': 8,
                  'totalTokens': 20,
                  'quotaUsed': 17,
                  'createdAt': '2026-08-12T08:30:00Z',
                },
              ];
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();
    await _scrollTo(tester, const ValueKey('account-usage-action'));
    await tester.tap(find.byKey(const ValueKey('account-usage-action')));
    await tester.pumpAndSettle();

    expect(find.text('qwen-official'), findsOneWidget);
    expect(find.text('消耗 17'), findsOneWidget);
    expect(find.textContaining('输入 12'), findsOneWidget);
  });

  testWidgets('revokes one session and then all remaining other sessions', (
    tester,
  ) async {
    _setPhoneViewport(tester);
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          switch (call.method) {
            case 'getSessionState':
              return <String, Object?>{'configured': true, 'signedIn': true};
            case 'getOverview':
              return _signedInOverview();
            case 'listSessions':
              return <Map<String, Object?>>[
                _sessionPayload('current', current: true, minute: 30),
                _sessionPayload('other-1', current: false, minute: 20),
                _sessionPayload('other-2', current: false, minute: 10),
              ];
            case 'revokeSession':
              return null;
            case 'revokeOtherSessions':
              return <String, Object?>{'revoked': 1};
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();
    await _scrollTo(tester, const ValueKey('account-sessions-action'));
    await tester.tap(find.byKey(const ValueKey('account-sessions-action')));
    await tester.pumpAndSettle();

    expect(find.text('当前设备'), findsOneWidget);
    expect(find.text('其他登录设备 1'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('revoke-session-other-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('confirm-revoke-session')));
    await tester.pumpAndSettle();

    final revokeCall = calls.singleWhere(
      (call) => call.method == 'revokeSession',
    );
    expect(revokeCall.arguments, <String, Object?>{'sessionId': 'other-1'});
    expect(find.text('已退出该设备'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('account-session-current')),
      findsOneWidget,
    );

    await tester.tap(find.byKey(const ValueKey('revoke-other-sessions')));
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey('confirm-revoke-other-sessions')),
    );
    await tester.pumpAndSettle();

    expect(
      calls.where((call) => call.method == 'revokeOtherSessions'),
      hasLength(1),
    );
    expect(find.text('已退出 1 个其他设备'), findsOneWidget);
    expect(find.textContaining('其他登录设备'), findsNothing);
  });

  testWidgets('changes password with a local busy state and stable errors', (
    tester,
  ) async {
    _setPhoneViewport(tester);
    var attempts = 0;
    MethodCall? successfulCall;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'getSessionState':
              return <String, Object?>{'configured': true, 'signedIn': true};
            case 'getOverview':
              return _signedInOverview();
            case 'changePassword':
              attempts += 1;
              if (attempts == 1) {
                throw PlatformException(
                  code: 'current_password_invalid',
                  message: 'server detail must not be shown',
                );
              }
              successfulCall = call;
              return null;
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();
    await _scrollTo(tester, const ValueKey('change-password-action'));
    await tester.tap(find.byKey(const ValueKey('change-password-action')));
    await tester.pumpAndSettle();

    const newPassword = 'Changed26!';
    await tester.enterText(
      find.byKey(const ValueKey('current-password-field')),
      'wrong current password',
    );
    await tester.enterText(
      find.byKey(const ValueKey('new-password-field')),
      newPassword,
    );
    await tester.enterText(
      find.byKey(const ValueKey('confirm-new-password-field')),
      newPassword,
    );
    await tester.tap(find.byKey(const ValueKey('confirm-change-password')));
    await tester.pumpAndSettle();

    expect(find.text('当前密码不正确'), findsOneWidget);
    expect(find.textContaining('server detail'), findsNothing);
    await tester.enterText(
      find.byKey(const ValueKey('current-password-field')),
      'correct current password',
    );
    await tester.tap(find.byKey(const ValueKey('confirm-change-password')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(successfulCall?.arguments, <String, Object?>{
      'currentPassword': 'correct current password',
      'newPassword': newPassword,
    });
    expect(find.byKey(const ValueKey('current-password-field')), findsNothing);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('requires two confirmations before deleting the account', (
    tester,
  ) async {
    _setPhoneViewport(tester);
    final deleteCalls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'getSessionState':
              return <String, Object?>{'configured': true, 'signedIn': true};
            case 'getOverview':
              return _signedInOverview();
            case 'deleteAccount':
              deleteCalls.add(call);
              return null;
          }
          return null;
        });

    await tester.pumpWidget(_testApp());
    await tester.pumpAndSettle();
    await _scrollTo(tester, const ValueKey('delete-account-action'));
    await tester.tap(find.byKey(const ValueKey('delete-account-action')));
    await tester.pumpAndSettle();

    expect(find.text('永久删除账号？'), findsOneWidget);
    expect(find.textContaining('本机聊天和文件不会自动清理'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('continue-delete-account')));
    await tester.pumpAndSettle();

    expect(find.text('最后确认'), findsOneWidget);
    await tester.enterText(
      find.byKey(const ValueKey('delete-account-email-field')),
      'wrong@example.com',
    );
    await tester.enterText(
      find.byKey(const ValueKey('delete-account-password-field')),
      'current password',
    );
    await tester.tap(find.byKey(const ValueKey('confirm-delete-account')));
    await tester.pump();
    expect(deleteCalls, isEmpty);
    expect(find.text('请输入当前账号的完整邮箱'), findsOneWidget);

    await tester.enterText(
      find.byKey(const ValueKey('delete-account-email-field')),
      'learner@example.com',
    );
    await tester.tap(find.byKey(const ValueKey('confirm-delete-account')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(deleteCalls, hasLength(1));
    expect(deleteCalls.single.arguments, <String, Object?>{
      'currentPassword': 'current password',
    });
    expect(find.text('登录小万账号'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });
}

void _expectModeIconsVerticallyCentered(
  WidgetTester tester, {
  required String optionKey,
  required IconData leadingIcon,
  required IconData trailingIcon,
}) {
  final optionCenterY = tester.getCenter(find.byKey(ValueKey(optionKey))).dy;
  expect(
    tester.getCenter(find.byIcon(leadingIcon)).dy,
    closeTo(optionCenterY, 0.01),
  );
  expect(
    tester.getCenter(find.byIcon(trailingIcon)).dy,
    closeTo(optionCenterY, 0.01),
  );
}

Widget _testApp() {
  return MaterialApp(
    navigatorKey: GoRouterManager.rootNavigatorKey,
    theme: AppTheme.lightTheme,
    locale: const Locale('zh'),
    supportedLocales: const <Locale>[Locale('zh')],
    localizationsDelegates: <LocalizationsDelegate<dynamic>>[
      GlobalMaterialLocalizations.delegate,
      GlobalWidgetsLocalizations.delegate,
      GlobalCupertinoLocalizations.delegate,
    ],
    home: AccountPage(),
  );
}

void _setPhoneViewport(WidgetTester tester) {
  tester.view.physicalSize = const Size(390, 844);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Future<void> _scrollTo(WidgetTester tester, Key key) async {
  await tester.scrollUntilVisible(
    find.byKey(key),
    260,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.pumpAndSettle();
}

Map<String, Object?> _signedInOverview() {
  return <String, Object?>{
    'user': <String, Object?>{
      'id': 'user-1',
      'email': 'learner@example.com',
      'role': 'user',
      'status': 'active',
    },
    'settings': <String, Object?>{
      'mode': 'platform',
      'keyStorage': 'device',
      'platformAvailable': true,
      'officialProviderReady': true,
      'platform': <String, Object?>{
        'platformEnabled': true,
        'balanceQuota': 1000,
        'unit': 'new_api_quota',
      },
    },
  };
}

Map<String, Object?> _sessionPayload(
  String id, {
  required bool current,
  required int minute,
}) {
  final timestamp = '2026-08-12T08:${minute.toString().padLeft(2, '0')}:00Z';
  return <String, Object?>{
    'id': id,
    'expiresAt': '2026-09-12T08:00:00Z',
    'createdAt': timestamp,
    'lastUsedAt': timestamp,
    'current': current,
  };
}
