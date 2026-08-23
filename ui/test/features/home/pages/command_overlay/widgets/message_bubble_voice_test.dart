import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/message_bubble.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/scene_model_config_service.dart';
import 'package:ui/services/voice_playback_channel_service.dart';
import 'package:ui/services/voice_playback_coordinator.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<void> pumpBubble(
    WidgetTester tester, {
    required ChatMessageModel message,
    VoidCallback? onContinueAgentMessage,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: MessageBubble(
            message: message,
            onContinueAgentMessage: onContinueAgentMessage,
          ),
        ),
      ),
    );
    await tester.pump();
  }

  setUp(() async {
    await VoicePlaybackCoordinator.instance.debugResetForTest();
  });

  tearDown(() async {
    await VoicePlaybackCoordinator.instance.debugResetForTest();
  });

  testWidgets('shows voice button only when voice scene is bound', (
    tester,
  ) async {
    final message = ChatMessageModel.assistantMessage('你好，世界', id: 'voice-msg');

    VoicePlaybackCoordinator.instance.debugSetAvailabilityForTest(
      isBound: false,
    );
    await pumpBubble(tester, message: message);
    expect(find.byTooltip('播放语音'), findsNothing);

    VoicePlaybackCoordinator.instance.debugSetAvailabilityForTest(
      isBound: true,
      config: const SceneVoiceConfig(),
    );
    await pumpBubble(tester, message: message);
    expect(find.byTooltip('播放语音'), findsOneWidget);
  });

  testWidgets('shows pause tooltip when message is playing', (tester) async {
    final message = ChatMessageModel.assistantMessage('你好，世界', id: 'voice-msg');

    VoicePlaybackCoordinator.instance.debugSetAvailabilityForTest(
      isBound: true,
      config: const SceneVoiceConfig(),
    );
    VoicePlaybackCoordinator.instance.debugSetMessageStateForTest(
      'voice-msg',
      const VoiceMessagePlaybackState(
        status: VoicePlaybackStatus.playing,
        canReplay: true,
      ),
    );

    await pumpBubble(tester, message: message);
    expect(find.byTooltip('暂停语音'), findsOneWidget);
  });

  testWidgets('user bubble shows compact quote-style link preview', (
    tester,
  ) async {
    final message = ChatMessageModel(
      id: 'user-bubble',
      type: 1,
      user: 1,
      content: {
        'text': '帮我看一下这个链接',
        'id': 'user-bubble',
        'linkPreviews': [
          {
            'url': 'https://example.com/article',
            'domain': 'example.com',
            'siteName': 'Example',
            'title': '链接预览标题',
            'description': '链接预览描述',
            'status': 'ready',
          },
        ],
      },
    );

    await pumpBubble(tester, message: message);

    final bubbleFinder = find.byKey(
      const ValueKey('user-message-bubble-user-bubble'),
    );
    final bubble = tester.widget<Container>(bubbleFinder);
    final decoration = bubble.decoration! as ShapeDecoration;
    final shape = decoration.shape as RoundedRectangleBorder;

    expect(
      bubble.padding,
      const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
    );
    expect(shape.borderRadius, BorderRadius.circular(4));
    final previewQuote = tester.widget<Container>(
      find.byKey(const ValueKey('link-preview-quote-0')),
    );
    final previewDecoration = previewQuote.decoration! as BoxDecoration;
    final previewTitle = tester.widget<Text>(find.text('链接预览标题'));

    expect(previewDecoration.color, isNull);
    expect(previewDecoration.border, isA<Border>());
    expect((previewDecoration.border! as Border).left.width, 3);
    expect(previewTitle.style?.fontSize, 12);
    expect(find.text('链接预览标题'), findsOneWidget);
    expect(find.byKey(const ValueKey('link-preview-card-0')), findsOneWidget);
  });

  testWidgets('assistant bubble also uses compact quote-style link preview', (
    tester,
  ) async {
    final message = ChatMessageModel(
      id: 'assistant-bubble',
      type: 1,
      user: 2,
      content: {
        'text': '你可以看这个链接',
        'id': 'assistant-bubble',
        'linkPreviews': [
          {
            'url': 'https://example.com/article',
            'domain': 'example.com',
            'siteName': 'Example',
            'title': 'AI 链接预览标题',
            'description': 'AI 链接预览描述',
            'status': 'ready',
          },
        ],
      },
    );

    await pumpBubble(tester, message: message);

    final previewQuote = tester.widget<Container>(
      find.byKey(const ValueKey('link-preview-quote-0')),
    );
    final previewDecoration = previewQuote.decoration! as BoxDecoration;
    final previewTitle = tester.widget<Text>(find.text('AI 链接预览标题'));

    expect(previewDecoration.color, isNull);
    expect(previewDecoration.border, isA<Border>());
    expect((previewDecoration.border! as Border).left.width, 3);
    expect(previewTitle.style?.fontSize, 12);
    expect(find.byKey(const ValueKey('link-preview-card-0')), findsOneWidget);
  });

  testWidgets('shows per-turn usage pill and continue action separately', (
    tester,
  ) async {
    var continueTapped = 0;
    final message = ChatMessageModel(
      id: 'assistant-continue',
      type: 1,
      user: 2,
      content: {
        'text': 'partial reply',
        'id': 'assistant-continue',
        'agentContinueable': true,
        'agentErrorText': 'network interrupted',
      },
      turnUsage: {'ctx': 20000, 'in': 10000, 'out': 87, 'cache': 10000},
    );

    await pumpBubble(
      tester,
      message: message,
      onContinueAgentMessage: () {
        continueTapped += 1;
      },
    );

    // The existing pill presents input/output/cache with icons, so those
    // values are separate text nodes rather than one legacy sentence.
    expect(find.text('ctx:20k'), findsOneWidget);
    expect(find.text('10k'), findsNWidgets(2));
    expect(find.text('87'), findsOneWidget);
    final continueAction = find.byIcon(Icons.play_arrow_rounded);
    expect(continueAction, findsOneWidget);

    await tester.tap(continueAction);
    await tester.pump();

    expect(continueTapped, 1);
  });
}
