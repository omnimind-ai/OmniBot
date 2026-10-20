import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/jackery_settings/pages/jackery_ces_model_provider_page.dart';
import 'package:ui/features/jackery_settings/pages/jackery_ces_settings_page.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
  });
  Future<void> pumpSettings(
    WidgetTester tester, {
    Future<void> Function()? reset,
  }) => tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.jackeryCesTheme,
      home: JackeryCesSettingsPage(resetDemo: reset, modelConfigured: false),
    ),
  );

  testWidgets('audience settings exposes only reset and model providers', (
    tester,
  ) async {
    await pumpSettings(tester, reset: () async {});
    expect(find.text('Reset demo'), findsOneWidget);
    expect(find.text('Model providers'), findsOneWidget);
    expect(find.byType(ListTile), findsOneWidget);
    expect(find.byType(Switch), findsNothing);
    expect(find.byType(TextField), findsNothing);
    for (final label in [
      'Storage used',
      'Appearance',
      'MCP tools',
      'Account',
      'Sign in',
      'About',
      'Agent mode',
      'Permissions',
      'Terminal',
      'Workspace memory',
    ]) {
      expect(find.text(label), findsNothing);
    }

    await tester.tap(find.byKey(const ValueKey('jackery-model-providers')));
    await tester.pumpAndSettle();
    expect(find.byType(JackeryCesModelProviderPage), findsOneWidget);
    expect(find.text('AI unavailable'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
    expect(find.textContaining('Configure connection'), findsNothing);
  });

  testWidgets('reset stays busy until cleanup completes and prevents repeats', (
    tester,
  ) async {
    final completion = Completer<void>();
    var calls = 0;
    await pumpSettings(
      tester,
      reset: () {
        calls += 1;
        return completion.future;
      },
    );

    await tester.tap(find.byKey(const ValueKey('jackery-reset-demo')));
    await tester.pump();
    expect(calls, 1);
    expect(find.text('Resetting demo…'), findsOneWidget);
    expect(find.byKey(const ValueKey('jackery-reset-feedback')), findsNothing);
    expect(
      tester
          .widget<FilledButton>(
            find.byKey(const ValueKey('jackery-reset-demo')),
          )
          .onPressed,
      isNull,
    );
    expect(
      tester
          .widget<ListTile>(
            find.byKey(const ValueKey('jackery-model-providers')),
          )
          .onTap,
      isNull,
    );
    expect(tester.widget<PopScope>(find.byType(PopScope).first).canPop, false);

    await tester.tap(find.byKey(const ValueKey('jackery-reset-demo')));
    expect(calls, 1);
    completion.complete();
    await tester.pumpAndSettle();
    expect(
      find.text('Demo reset. You can start a new conversation.'),
      findsOneWidget,
    );
    expect(tester.widget<PopScope>(find.byType(PopScope).first).canPop, true);
  });

  testWidgets('failed cleanup does not report a successful reset', (
    tester,
  ) async {
    await pumpSettings(
      tester,
      reset: () async {
        throw StateError('cleanup failed');
      },
    );
    await tester.tap(find.byKey(const ValueKey('jackery-reset-demo')));
    await tester.pumpAndSettle();
    expect(
      find.text('Demo reset. You can start a new conversation.'),
      findsNothing,
    );
    expect(
      find.text(
        'Reset could not finish. Please ask the exhibition staff to retry.',
      ),
      findsOneWidget,
    );
    expect(find.text('Reset demo'), findsOneWidget);
  });

  testWidgets('model configuration does not claim live device readiness', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.jackeryCesTheme,
        home: const JackeryCesModelProviderPage(modelConfigured: true),
      ),
    );
    expect(find.text('Jackery Power AI · On-device'), findsOneWidget);
    expect(find.text('Configuration ready'), findsOneWidget);
    expect(find.text('Connected'), findsNothing);
    expect(find.text('AI ready'), findsNothing);
    expect(find.byType(TextField), findsNothing);
    expect(find.byType(Switch), findsNothing);
  });

  testWidgets('settings fits a narrow display with enlarged text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.jackeryCesTheme,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: const TextScaler.linear(1.5)),
          child: child!,
        ),
        home: JackeryCesSettingsPage(resetDemo: () async {}),
      ),
    );
    expect(tester.takeException(), isNull);
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('jackery-model-providers')),
      180,
      scrollable: find.byType(Scrollable),
    );
    expect(tester.takeException(), isNull);
  });
}
