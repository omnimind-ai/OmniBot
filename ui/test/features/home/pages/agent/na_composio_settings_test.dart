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
    Future<Map<String, dynamic>?> Function(Map<String, dynamic>) save, {
    Map<String, dynamic> settings = original,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ListView(
            children: [
              NaComposioSettings(settings: settings, busy: false, onSave: save),
            ],
          ),
        ),
      ),
    );
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
    'official underscore and digit toolkit identifiers save without rewriting',
    (tester) async {
      final writes = <Map<String, dynamic>>[];
      const slugs = [
        '_1password',
        '_21risk',
        '_2chat',
        '1-custom-app',
        'team-app',
      ];
      await pump(tester, (value) async {
        writes.add(value);
        return {...original, 'toolkits': slugs, 'revision': 8};
      }, settings: {...original, 'toolkits': slugs});
      await save(tester);
      expect(writes.single['toolkits'], slugs);
    },
  );

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
      expect(find.text('获取密钥'), findsOneWidget);
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
    'phone shows project key and nearby save before seven advanced application rows',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      const toolkits = [
        'gmail',
        'googlecalendar',
        'googledrive',
        'googledocs',
        'googlesheets',
        'googleslides',
        'googletasks',
      ];
      final settings = {
        ...original,
        'toolkits': toolkits,
        'authConfigs': {'gmail': 'ac_existing'},
      };
      final calls = <Map<String, dynamic>>[];
      await pump(tester, (value) async {
        calls.add(value);
        return {...settings, 'revision': 8};
      }, settings: settings);
      final key = find.byKey(const Key('na-composio-api-key'));
      final button = find.byKey(const Key('na-composio-settings-save'));
      expect(find.text('连接服务'), findsOneWidget);
      expect(key.hitTestable(), findsOneWidget);
      expect(button.hitTestable(), findsOneWidget);
      expect(
        find.widgetWithText(TextField, '应用标识').hitTestable(),
        findsNothing,
      );
      expect(
        tester.getTopLeft(button).dy - tester.getBottomLeft(key).dy,
        lessThan(90),
      );
      await tester.enterText(key, 'fixture-replacement-project-key');
      await tester.tap(button);
      await tester.pumpAndSettle();
      expect(calls.single, {
        'expectedRevision': 7,
        'enabled': true,
        'toolkits': toolkits,
        'authConfigs': {'gmail': 'ac_existing'},
        'apiKey': 'fixture-replacement-project-key',
      });
      expect(tester.widget<TextField>(key).controller!.text, isEmpty);
      await tester.tap(find.text('应用与授权配置'));
      await tester.pumpAndSettle();
      expect(find.widgetWithText(TextField, '应用标识'), findsNWidgets(7));
      expect(tester.takeException(), isNull);
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
      expect(find.textContaining('ck_ 是消费者密钥'), findsOneWidget);
    },
  );
}
