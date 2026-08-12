import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/widgets/openclaw_identity_reset_dialog.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/openclaw_credential');

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('reset requires both confirmations and exact local phrase', (
    tester,
  ) async {
    final methods = <String>[];
    var disabledCallbacks = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          methods.add(call.method);
          if (call.method == 'hasExistingIdentity') return false;
          if (call.method == 'resetDeviceIdentity') {
            return <String, Object>{'success': true, 'status': 'success'};
          }
          return null;
        });
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () => showOpenClawIdentityResetFlow(
              context: context,
              onLocalDisabled: () => disabledCallbacks++,
            ),
            child: const Text('reset'),
          ),
        ),
      ),
    );

    await tester.tap(find.text('reset'));
    await tester.pumpAndSettle();
    expect(methods, ['hasExistingIdentity']);
    expect(find.textContaining('does not create an identity'), findsOneWidget);
    await tester.tap(find.byKey(const Key('openclaw-reset-continue')));
    await tester.pumpAndSettle();

    var confirm = tester.widget<FilledButton>(
      find.byKey(const Key('openclaw-reset-confirm')),
    );
    expect(confirm.onPressed, isNull);
    await tester.enterText(
      find.byKey(const Key('openclaw-reset-phrase-field')),
      'wrong phrase',
    );
    await tester.pump();
    confirm = tester.widget<FilledButton>(
      find.byKey(const Key('openclaw-reset-confirm')),
    );
    expect(confirm.onPressed, isNull);
    await tester.enterText(
      find.byKey(const Key('openclaw-reset-phrase-field')),
      kOpenClawResetPhraseEn,
    );
    await tester.pump();
    await tester.tap(find.byKey(const Key('openclaw-reset-confirm')));
    await tester.pumpAndSettle();

    expect(methods, ['hasExistingIdentity', 'resetDeviceIdentity']);
    expect(disabledCallbacks, 1);
  });

  testWidgets('canceling confirmation performs no reset and does not disable', (
    tester,
  ) async {
    final methods = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          methods.add(call.method);
          return true;
        });
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () => showOpenClawIdentityResetFlow(context: context),
            child: const Text('reset'),
          ),
        ),
      ),
    );

    await tester.tap(find.text('reset'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('openclaw-reset-cancel-first')));
    await tester.pumpAndSettle();

    expect(methods, ['hasExistingIdentity']);
  });

  testWidgets('native failure is returned without re-enabling OpenClaw', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'hasExistingIdentity') return true;
          return <String, Object>{
            'success': false,
            'status': 'session_stop_failed',
          };
        });
    Object? flowResult;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              flowResult = await showOpenClawIdentityResetFlow(
                context: context,
              );
            },
            child: const Text('reset'),
          ),
        ),
      ),
    );

    await tester.tap(find.text('reset'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('openclaw-reset-continue')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('openclaw-reset-phrase-field')),
      kOpenClawResetPhraseEn,
    );
    await tester.pump();
    await tester.tap(find.byKey(const Key('openclaw-reset-confirm')));
    await tester.pumpAndSettle();

    expect(flowResult, isNotNull);
  });
}
