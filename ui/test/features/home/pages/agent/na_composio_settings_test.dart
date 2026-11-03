import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/na_composio_settings.dart';

void main() {
  const original = <String, dynamic>{
    'enabled': true,
    'apiKeyConfigured': true,
    'configured': true,
    'revision': 7,
    'toolkits': ['gmail'],
    'authConfigs': <String, String>{},
  };
  Future<void> pump(
    WidgetTester tester,
    Future<Map<String, dynamic>?> Function(Map<String, dynamic>) save,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ListView(
            children: [
              NaComposioSettings(settings: original, busy: false, onSave: save),
            ],
          ),
        ),
      ),
    );
    await tester.tap(find.text('连接服务设置'));
    await tester.pumpAndSettle();
  }

  Future<void> save(WidgetTester tester) async {
    final button = find.byKey(const Key('na-composio-settings-save'));
    await tester.ensureVisible(button);
    await tester.pumpAndSettle();
    await tester.tap(button);
    await tester.pumpAndSettle();
  }

  testWidgets(
    'blank key preserves the server credential and carries optimistic revision',
    (tester) async {
      final calls = <Map<String, dynamic>>[];
      await pump(tester, (value) async {
        calls.add(value);
        return {...original, 'revision': 8};
      });
      final key = tester.widget<TextField>(
        find.byKey(const Key('na-composio-api-key')),
      );
      expect(key.obscureText, isTrue);
      expect(key.controller!.text, isEmpty);
      expect(find.text('获取项目 API Key'), findsOneWidget);
      await save(tester);
      expect(calls.single, {
        'expectedRevision': 7,
        'enabled': true,
        'toolkits': ['gmail'],
        'authConfigs': {},
      });
    },
  );
  testWidgets(
    'only explicit clear removes the server credential and never sends another key',
    (tester) async {
      final calls = <Map<String, dynamic>>[];
      await pump(tester, (value) async {
        calls.add(value);
        return {
          ...original,
          'revision': 8,
          'apiKeyConfigured': false,
          'configured': false,
        };
      });
      await tester.tap(find.text('清除服务端密钥'));
      await tester.pumpAndSettle();
      await save(tester);
      expect(calls.single['clearApiKey'], isTrue);
      expect(calls.single.containsKey('apiKey'), isFalse);
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('na-composio-api-key')))
            .controller!
            .text,
        isEmpty,
      );
    },
  );
  testWidgets(
    'known Connect consumer key shows Platform guidance without a write',
    (tester) async {
      var count = 0;
      await pump(tester, (value) async {
        count++;
        return original;
      });
      await tester.enterText(
        find.byKey(const Key('na-composio-api-key')),
        'ck_fixture_consumer_key',
      );
      await save(tester);
      expect(count, 0);
      expect(find.textContaining('这是 Connect 消费者密钥'), findsOneWidget);
    },
  );
}
