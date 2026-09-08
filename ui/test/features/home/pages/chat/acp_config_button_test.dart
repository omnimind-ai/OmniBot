import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/acp_config_button.dart';
import 'package:ui/widgets/conversation_model_selector.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';

void main() {
  for (final readOnly in [false, true]) {
    testWidgets(
      'shared model selection uses configured connections (readOnly=$readOnly)',
      (tester) async {
        var opened = 0;
        var refreshed = 0;
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: AcpConfigPanel(
                readOnly: readOnly,
                load: () async => {
                  'sessionId': 'existing-session',
                  'configOptions': [
                    {
                      'id': 'model',
                      'category': 'model',
                      'name': 'Model',
                      'type': 'select',
                      'currentValue': 'old-model',
                      'options': [
                        {'value': 'old-model', 'name': 'Old model'},
                      ],
                    },
                  ],
                },
                refresh: () async {
                  refreshed++;
                  throw StateError('stale catalog');
                },
                configureModel: () async {
                  opened++;
                },
                write: (_, _, _) async =>
                    throw StateError('must use connection selection'),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('acp-config-model')));
        await tester.pumpAndSettle();
        expect(opened, readOnly ? 0 : 1);
        expect(refreshed, 0);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'incompatible setup offers model selection before a session exists',
    (tester) async {
      var opened = 0;
      var writes = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AcpConfigPanel(
              load: () async => throw StateError(
                'Claude Code requires an Anthropic-compatible Provider endpoint',
              ),
              configureModel: () async {
                opened++;
              },
              write: (session, id, value) async {
                writes++;
                return {};
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Choose model'), findsOneWidget);
      await tester.tap(find.text('Choose model'));
      await tester.pumpAndSettle();
      expect(opened, 1);
      expect(writes, 0);
      expect(
        find.textContaining('Anthropic-compatible Provider endpoint'),
        findsNothing,
      );
    },
  );

  testWidgets('model options retain the original searchable icon card', (
    tester,
  ) async {
    final writes = <List<Object>>[];
    final model = <String, dynamic>{
      'id': 'vendor-model',
      'name': 'Model',
      'category': 'model',
      'type': 'select',
      'currentValue': 'gpt-4.1',
      'options': [
        {'value': 'gpt-4.1', 'name': 'GPT 4.1'},
        {'value': 'claude-sonnet-4', 'name': 'Claude Sonnet 4'},
      ],
    };
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AcpConfigPanel(
            load: () async => {
              'sessionId': 'original-card',
              'configOptions': [model],
            },
            write: (session, id, value) async {
              writes.add([session, id, value]);
              return {
                'configOptions': [
                  {...model, 'currentValue': value},
                ],
              };
            },
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('acp-config-vendor-model')));
    await tester.pumpAndSettle();
    final card = tester.widget<ConversationModelSelectorContent>(
      find.byType(ConversationModelSelectorContent),
    );
    expect(card.showVendorIcons, isTrue);
    expect(card.showSearchField, isTrue);
    expect(card.selectedValue, 'gpt-4.1');
    expect(find.byType(ProviderVendorIcon), findsNWidgets(2));
    expect(find.byType(TextField), findsOneWidget);
    await tester.enterText(find.byType(TextField), 'claude');
    await tester.pumpAndSettle();
    expect(find.text('GPT 4.1'), findsNothing);
    expect(find.text('Claude Sonnet 4'), findsOneWidget);
    await tester.tap(find.text('Claude Sonnet 4'));
    await tester.pumpAndSettle();
    expect(writes, [
      ['original-card', 'vendor-model', 'claude-sonnet-4'],
    ]);
    expect(
      find.byKey(const ValueKey('acp-reasoning-model-default')),
      findsOneWidget,
    );
    expect(find.text('Model default'), findsOneWidget);
  });

  for (final fails in [false, true]) {
    testWidgets('late load after leaving panel is ignored (failure=$fails)', (
      tester,
    ) async {
      final pending = Completer<Map<String, dynamic>>();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AcpConfigPanel(
              load: () => pending.future,
              write: (_, _, _) async => throw StateError('must not write'),
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.pumpWidget(
        const MaterialApp(home: Text('Other conversation')),
      );
      if (fails) {
        pending.completeError(StateError('Old provider failed'));
      } else {
        pending.complete({'sessionId': 'old', 'configOptions': <Map>[]});
      }
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('Other conversation'), findsOneWidget);
      expect(find.textContaining('Old provider failed'), findsNothing);
    });
  }

  testWidgets(
    'slow model refresh cannot race a setting write after going back',
    (tester) async {
      final refresh = Completer<Map<String, dynamic>>();
      final response = <String, dynamic>{
        'sessionId': 'session-slow',
        'configOptions': [
          {
            'id': 'vendor-model',
            'name': 'Model',
            'category': 'model',
            'type': 'select',
            'currentValue': 'old',
            'options': <Map>[],
          },
          {
            'id': 'vendor-toggle',
            'name': 'Toggle',
            'type': 'boolean',
            'currentValue': false,
          },
        ],
      };
      var writes = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AcpConfigPanel(
              load: () async => response,
              refresh: () => refresh.future,
              write: (_, _, _) async {
                writes++;
                return response;
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('acp-config-vendor-model')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('acp-config-back')));
      await tester.pump();
      expect(
        tester.widget<SwitchListTile>(find.byType(SwitchListTile)).onChanged,
        isNull,
      );
      await tester.tap(find.byType(SwitchListTile));
      await tester.pump();
      expect(writes, 0);
      refresh.complete(response);
      await tester.pumpAndSettle();
      expect(
        tester.widget<SwitchListTile>(find.byType(SwitchListTile)).onChanged,
        isNotNull,
      );
    },
  );

  testWidgets(
    'initial load failure allows explicit reload without auto retry',
    (tester) async {
      var loads = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AcpConfigPanel(
              load: () async {
                if (++loads == 1) throw StateError('Provider unavailable');
                return {'sessionId': 'recovered', 'configOptions': <Map>[]};
              },
              write: (_, _, _) async => {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(loads, 1);
      expect(find.text('助手暂时无法完成操作，请重试。'), findsOneWidget);
      await tester.tap(find.text('Reload'));
      await tester.pumpAndSettle();
      expect(loads, 2);
      expect(find.text('助手暂时无法完成操作，请重试。'), findsNothing);
      expect(
        find.text('This Agent exposes no configurable options'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'first model click refreshes an empty catalog and displays the response',
    (tester) async {
      var refreshes = 0;
      final model = {
        'id': 'model',
        'name': 'Model',
        'category': 'model',
        'type': 'select',
        'currentValue': 'old',
        'options': <Map>[],
      };
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AcpConfigPanel(
              load: () async => {
                'sessionId': 's',
                'configOptions': [model],
              },
              refresh: () async {
                refreshes++;
                return {
                  'sessionId': 's',
                  'configOptions': [
                    {
                      ...model,
                      'options': [
                        {'value': 'fresh', 'name': 'Fresh provider model'},
                      ],
                    },
                  ],
                };
              },
              write: (_, _, _) async => {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('acp-config-model')));
      await tester.pumpAndSettle();
      expect(refreshes, 1);
      expect(find.text('Fresh provider model'), findsOneWidget);
    },
  );
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
    expect(find.text('助手暂时无法完成操作，请重试。'), findsOneWidget);
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
    expect(find.text('助手暂时无法完成操作，请重试。'), findsOneWidget);
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
