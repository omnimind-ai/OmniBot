import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/voice_playback_coordinator.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );
  const voiceChannel = MethodChannel('cn.com.omnimind.bot/VoicePlayback');

  late List<Map<String, dynamic>> sceneBindings;
  late Map<String, dynamic> sceneVoiceConfig;
  late List<MethodCall> voiceCalls;

  setUp(() async {
    sceneBindings = <Map<String, dynamic>>[];
    sceneVoiceConfig = <String, dynamic>{
      'autoPlay': false,
      'voiceId': 'default_zh',
      'stylePreset': '默认',
      'customStyle': '',
    };
    voiceCalls = <MethodCall>[];
    await VoicePlaybackCoordinator.instance.debugResetForTest();

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'getSceneModelBindings':
              return sceneBindings;
            case 'getSceneVoiceConfig':
              return sceneVoiceConfig;
            default:
              return null;
          }
        });

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(voiceChannel, (call) async {
          voiceCalls.add(call);
          return true;
        });
  });

  tearDown(() async {
    await VoicePlaybackCoordinator.instance.debugResetForTest();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(voiceChannel, null);
  });

  test('auto play queues sealed segments incrementally', () async {
    sceneBindings = <Map<String, dynamic>>[
      <String, dynamic>{
        'sceneId': 'scene.voice',
        'providerProfileId': 'provider-1',
        'modelId': 'mimo-v2-tts',
      },
    ];
    sceneVoiceConfig = <String, dynamic>{
      'autoPlay': true,
      'voiceId': 'default_zh',
      'stylePreset': '默认',
      'customStyle': '',
    };

    await VoicePlaybackCoordinator.instance.ensureInitialized();
    await VoicePlaybackCoordinator.instance.onAssistantMessageUpdated(
      messageId: 'message-1',
      text: '第一句。第二句',
      isFinal: false,
    );
    await Future<void>.delayed(Duration.zero);

    expect(voiceCalls, hasLength(1));
    expect(voiceCalls.first.method, 'speakText');
    expect(voiceCalls.first.arguments['text'], '第一句。');
    expect(voiceCalls.first.arguments['enqueue'], false);

    await VoicePlaybackCoordinator.instance.onAssistantMessageCompleted(
      messageId: 'message-1',
      text: '第一句。第二句',
    );
    await Future<void>.delayed(Duration.zero);

    expect(voiceCalls, hasLength(2));
    expect(voiceCalls.last.arguments['text'], '第二句');
    expect(voiceCalls.last.arguments['enqueue'], true);
  });

  test('registered speech provider uses the main chat playback path', () async {
    sceneVoiceConfig = {
      'autoPlay': true,
      'ttsMode': 'provider',
      'speechSynthesisProviderId': 'test-speech-provider',
      'speechProviders': [
        {
          'id': 'test-speech-provider',
          'label': 'Test speech',
          'ready': true,
          'synthesis': true,
          'voices': [],
        },
      ],
    };
    final coordinator = VoicePlaybackCoordinator.instance;
    await coordinator.ensureInitialized();
    expect(coordinator.isVoiceSceneBound, isTrue);
    expect(
      coordinator.shouldShowVoiceButton(user: 2, type: 1, text: 'Reply'),
      isFalse,
    );
    expect(
      coordinator.shouldShowVoiceButton(
        user: 2,
        type: 1,
        text: 'Reply',
        allowSpeechProvider: true,
      ),
      isTrue,
    );

    var visible = true;
    final first = coordinator.onAssistantMessageUpdated(
      messageId: 'visible',
      text: '第一句。',
      isFinal: false,
      providerSpeechVisible: () => visible,
    );
    final second = coordinator.onAssistantMessageUpdated(
      messageId: 'visible',
      text: '第一句。第二句',
      isFinal: true,
      providerSpeechVisible: () => visible,
    );
    await Future.wait([first, second]);
    expect(voiceCalls.map((c) => c.arguments['text']), ['第一句。', '第二句']);
    expect(voiceCalls.map((c) => c.arguments['enqueue']), [false, true]);

    visible = false;
    await coordinator.onAssistantMessageCompleted(
      messageId: 'hidden',
      text: '不播放。',
      providerSpeechVisible: () => visible,
    );
    expect(voiceCalls, hasLength(2));

    final mic = Object();
    await coordinator.beginSpeechInput(mic);
    await coordinator.onAssistantMessageUpdated(
      messageId: 'during-recording',
      text: '录音中。',
      isFinal: false,
      providerSpeechVisible: () => true,
    );
    coordinator.endSpeechInput(mic);
    await coordinator.onAssistantMessageCompleted(
      messageId: 'during-recording',
      text: '录音中。结束。',
      providerSpeechVisible: () => true,
    );
    expect(voiceCalls.map((c) => c.method), [
      'speakText',
      'speakText',
      'stopPlayback',
    ]);
  });
}
