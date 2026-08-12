import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/scene_model_config_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');

  test('custom curl command is discarded from native payload', () {
    final config = SceneVoiceConfig.fromMap({
      'ttsMode': SceneVoiceConfig.ttsModeCustomCurl,
      'customCurlCommand':
          "curl -H 'Authorization: Bearer secret' https://tts.example.com",
      'hasCustomCurlCommand': true,
    });

    expect(config.customCurlCommand, isEmpty);
    expect(config.hasCustomCurlCommand, isTrue);
  });

  test('save preserves command unless replace or clear is explicit', () async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{
        'ttsMode': SceneVoiceConfig.ttsModeCustomCurl,
        'customCurlCommand': 'must-not-enter-dart',
        'hasCustomCurlCommand': true,
      };
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));

    await SceneModelConfigService.saveSceneVoiceConfig(
      const SceneVoiceConfig(ttsMode: SceneVoiceConfig.ttsModeCustomCurl),
    );
    final preserved = Map<dynamic, dynamic>.from(calls.single.arguments as Map);
    expect(preserved.containsKey('customCurlCommand'), isFalse);
    expect(preserved.containsKey('replaceCustomCurlCommand'), isFalse);
    expect(preserved.containsKey('clearCustomCurlCommand'), isFalse);

    calls.clear();
    await SceneModelConfigService.saveSceneVoiceConfig(
      const SceneVoiceConfig(ttsMode: SceneVoiceConfig.ttsModeCustomCurl),
      replacementCustomCurlCommand: 'curl https://tts.example.com',
    );
    final replaced = Map<dynamic, dynamic>.from(calls.single.arguments as Map);
    expect(replaced['replaceCustomCurlCommand'], isTrue);
    expect(replaced['customCurlCommand'], 'curl https://tts.example.com');

    calls.clear();
    await SceneModelConfigService.saveSceneVoiceConfig(
      const SceneVoiceConfig(),
      clearCustomCurlCommand: true,
    );
    final cleared = Map<dynamic, dynamic>.from(calls.single.arguments as Map);
    expect(cleared['clearCustomCurlCommand'], isTrue);
    expect(cleared.containsKey('customCurlCommand'), isFalse);
  });
}
