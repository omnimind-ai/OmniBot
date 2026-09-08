import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/omni_plugin_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/PluginPlatform');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('lists declarative plugin actions', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'listActions');
          return <Map<String, dynamic>>[
            <String, dynamic>{
              'id': 'open_kimi_web',
              'pluginId': 'com.omnimind.agent-web',
              'displayName': 'Kimi Code Web',
              'description': 'Open Kimi Code Web',
              'presentation': <String, dynamic>{
                'placement': 'agent_settings',
                'placements': <String>[
                  'agent_settings',
                  'home_drawer_quick_launch',
                ],
                'packageId': 'kimi',
                'quickLaunchOrder': 1,
                'label': <String, String>{
                  'zh': 'Kimi Code Web',
                  'en': 'Kimi Code Web',
                },
              },
            },
          ];
        });

    final actions = await OmniPluginService.listActions();

    expect(actions, hasLength(1));
    expect(actions.single.id, 'open_kimi_web');
    expect(actions.single.pluginId, 'com.omnimind.agent-web');
    expect(actions.single.presentation['packageId'], 'kimi');
    expect(actions.single.supportsPlacement('agent_settings'), isTrue);
    expect(
      actions.single.supportsPlacement('home_drawer_quick_launch'),
      isTrue,
    );
    expect(actions.single.quickLaunchOrder, 1);
    expect(
      actions.single.localizedPresentationValue(
        'label',
        english: false,
        fallback: '',
      ),
      'Kimi Code Web',
    );
  });

  test('invokes one scoped plugin action', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'invokeAction');
          expect(call.arguments, <String, Object?>{
            'pluginId': 'com.omnimind.agent-web',
            'actionId': 'open_kimi_web',
            'arguments': <String, dynamic>{'reasoning_effort': 'high'},
          });
          return <String, dynamic>{
            'success': true,
            'code': 'OPENED',
            'running': true,
          };
        });

    final result = await OmniPluginService.invokeAction(
      'com.omnimind.agent-web',
      'open_kimi_web',
      <String, dynamic>{'reasoning_effort': 'high'},
    );

    expect(result['code'], 'OPENED');
    expect(result.containsKey('url'), isFalse);
  });
}
