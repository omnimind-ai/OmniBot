import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/acp_config_button.dart';

void main() {
  final effort = <String, dynamic>{
    'id': 'vendor_thought',
    'name': 'Vendor thought',
    'category': 'thought_level',
    'type': 'select',
    'currentValue': 'low',
    'options': [
      {'value': 'low', 'name': 'Low'},
      {'value': 'high', 'name': 'High'},
    ],
  };
  final toggle = <String, dynamic>{
    'id': 'vendor_experimental',
    'name': 'Experimental setting',
    'type': 'boolean',
    'currentValue': true,
  };
  test(
    'friendly labels preserve unknown names and original option identities',
    () {
      expect(acpConfigLabel(effort), '思考强度');
      expect(acpConfigLabel(toggle), 'Experimental setting');
      expect(effort['id'], 'vendor_thought');
      expect(acpConfigLabel(effort, english: true), 'Vendor thought');
    },
  );
  testWidgets(
    'all official options render and false is sent with original IDs',
    (tester) async {
      final writes = <List<Object>>[];
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AcpConfigPanel(
              load: () async => {
                'sessionId': 'session-a',
                'configOptions': [effort, toggle],
              },
              write: (session, id, value) async {
                writes.add([session, id, value]);
                return {
                  'configOptions': [
                    effort,
                    {...toggle, 'currentValue': false},
                  ],
                };
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Vendor thought'), findsOneWidget);
      expect(find.text('Experimental setting'), findsOneWidget);
      await tester.tap(find.byType(SwitchListTile));
      await tester.pumpAndSettle();
      expect(writes, [
        ['session-a', 'vendor_experimental', false],
      ]);
      expect(
        tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value,
        false,
      );
    },
  );
  testWidgets(
    'shared model card searches and saves the official session choice',
    (tester) async {
      final writes = <List<Object>>[];
      final model = <String, dynamic>{
        'id': 'model',
        'name': 'Model',
        'category': 'model',
        'type': 'select',
        'currentValue': 'model-0',
        'options': List.generate(
          8,
          (i) => {'value': 'model-$i', 'name': 'Model $i'},
        ),
      };
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AcpConfigPanel(
              load: () async => {
                'sessionId': 's-model',
                'configOptions': [model, effort],
              },
              write: (session, id, value) async {
                writes.add([session, id, value]);
                return {
                  'configOptions': [
                    {...model, 'currentValue': value},
                    effort,
                  ],
                };
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('acp-config-model')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'model-7');
      await tester.pumpAndSettle();
      expect(find.text('Model 0'), findsNothing);
      await tester.tap(
        find.byKey(const ValueKey('acp-config-model-value-model-7')),
      );
      await tester.pumpAndSettle();
      expect(writes, [
        ['s-model', 'model', 'model-7'],
      ]);
      expect(find.text('Model 7'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('acp-config-vendor_thought')),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'opening model choices refreshes every time and running users can inspect effort',
    (tester) async {
      var refreshes = 0;
      final model = <String, dynamic>{
        'id': 'model',
        'name': 'Model',
        'category': 'model',
        'type': 'select',
        'currentValue': 'old',
        'options': [
          {'value': 'old', 'name': 'Old'},
        ],
      };
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AcpConfigPanel(
              load: () async => {
                'sessionId': 's',
                'configOptions': [model, effort],
              },
              refresh: () async {
                refreshes++;
                return {
                  'sessionId': 's',
                  'configOptions': [
                    {
                      ...model,
                      'options': [
                        {'value': 'fresh', 'name': 'Fresh'},
                      ],
                    },
                    effort,
                  ],
                };
              },
              write: (_, _, _) async => throw StateError('must not write'),
              readOnly: true,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      for (var i = 0; i < 2; i++) {
        await tester.tap(find.byKey(const ValueKey('acp-config-model')));
        await tester.pumpAndSettle();
        expect(find.text('Fresh'), findsOneWidget);
        await tester.tap(find.byKey(const ValueKey('acp-config-back')));
        await tester.pumpAndSettle();
      }
      expect(refreshes, 2);
      await tester.tap(find.byKey(const ValueKey('acp-config-vendor_thought')));
      await tester.pumpAndSettle();
      expect(find.text('High'), findsOneWidget);
    },
  );

  testWidgets('select rejection restores the official value', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AcpConfigPanel(
            load: () async => {
              'sessionId': 'session-a',
              'configOptions': [effort],
            },
            write: (_, id, value) async {
              expect(id, 'vendor_thought');
              expect(value, 'high');
              throw StateError('Rejected');
            },
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('acp-config-vendor_thought')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('High').last);
    await tester.pumpAndSettle();
    expect(find.text('Low'), findsOneWidget);
    expect(find.textContaining('Rejected'), findsOneWidget);
  });

  testWidgets('failed writes retain server value and do not claim success', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AcpConfigPanel(
            load: () async => {
              'sessionId': 'session-a',
              'configOptions': [toggle],
            },
            write: (_, _, _) async =>
                throw StateError('Provider rejected this option'),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byType(SwitchListTile));
    await tester.pumpAndSettle();
    expect(
      tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value,
      true,
    );
    expect(find.textContaining('Provider rejected'), findsOneWidget);
  });
  testWidgets('active turns expose options without allowing mutation', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AcpConfigPanel(
            readOnly: true,
            load: () async => {
              'sessionId': 'session-a',
              'configOptions': [toggle],
            },
            write: (_, _, _) async => throw StateError('Must not write'),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      tester.widget<SwitchListTile>(find.byType(SwitchListTile)).onChanged,
      isNull,
    );
  });
}
