import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/message_bubble.dart';
import 'package:ui/services/voice_playback_channel_service.dart';
import 'package:ui/services/voice_playback_coordinator.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/streaming_text.dart';

void main() {
  for (final withUsage in [true, false]) {
    testWidgets(
      'persisted end metadata renders on narrow screens, usage=$withUsage',
      (tester) async {
        final message = ChatMessageModel.fromJson(
          ChatMessageModel(
            id: 'answer',
            type: 1,
            user: 2,
            content: {'text': '完成'},
            turnUsage: {
              if (withUsage) ...{'ctx': 120, 'in': 80, 'out': 40, 'cache': 0},
              'durationMs': 65400,
              'endedAt': DateTime(2026, 9, 6, 14, 5, 9).millisecondsSinceEpoch,
            },
          ).toJson(),
        );
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: Center(
                child: SizedBox(
                  width: 220,
                  child: MessageBubble(message: message),
                ),
              ),
            ),
          ),
        );
        await tester.pump();
        expect(find.textContaining('1m 5s'), findsOneWidget);
        expect(find.textContaining('14:05:09'), findsOneWidget);
        expect(find.textContaining('ctx:'), findsNothing);
        expect(find.text('80'), withUsage ? findsOneWidget : findsNothing);
        expect(find.text('40'), withUsage ? findsOneWidget : findsNothing);
        expect(find.text('0'), withUsage ? findsOneWidget : findsNothing);
        expect(find.textContaining('用时'), findsNothing);
        expect(find.textContaining('结束于'), findsNothing);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('old history does not invent completion time', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: MessageBubble(
            message: ChatMessageModel(
              id: 'old',
              type: 1,
              user: 2,
              content: {'text': '旧回复'},
              turnUsage: {'ctx': 120},
            ),
          ),
        ),
      ),
    );
    expect(find.text('ctx:120'), findsNothing);
    expect(
      find
          .byType(Tooltip)
          .evaluate()
          .where(
            (element) =>
                (element.widget as Tooltip).message?.contains('2026-') == true,
          ),
      isEmpty,
    );
  });

  testWidgets(
    'assistant voice control renders below prose and offers stop when paused',
    (tester) async {
      const voiceChannel = MethodChannel('cn.com.omnimind.bot/VoicePlayback');
      final voiceCalls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(voiceChannel, (call) async {
            voiceCalls.add(call);
            return true;
          });
      addTearDown(() async {
        await VoicePlaybackCoordinator.instance.debugResetForTest();
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(voiceChannel, null);
      });

      VoicePlaybackCoordinator.instance.debugSetAvailabilityForTest(
        isBound: true,
      );
      VoicePlaybackCoordinator.instance.debugSetMessageStateForTest(
        'voice-msg',
        const VoiceMessagePlaybackState(status: VoicePlaybackStatus.paused),
      );

      final message = ChatMessageModel(
        id: 'voice-msg',
        type: 1,
        user: 2,
        content: {'text': '需要做什么，直接说就行。'},
        turnUsage: {
          'in': 16300,
          'out': 73,
          'cache': 128,
          'durationMs': 2000,
          'endedAt': DateTime(2026, 11, 19, 13, 0, 10).millisecondsSinceEpoch,
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          home: Scaffold(
            body: Padding(
              padding: const EdgeInsets.all(16),
              child: MessageBubble(
                message: message,
                allowSpeechProvider: true,
              ),
            ),
          ),
        ),
      );
      await tester.pump();

      final proseRect = tester.getRect(find.byType(StreamingText));
      final voiceButton = find.byKey(const ValueKey('voice-playback-voice-msg'));
      final stopButton = find.byKey(
        const ValueKey('voice-playback-stop-voice-msg'),
      );
      expect(voiceButton, findsOneWidget);
      expect(stopButton, findsOneWidget);
      final voiceRect = tester.getRect(voiceButton);
      expect(voiceRect.top, greaterThanOrEqualTo(proseRect.bottom));
      expect(voiceRect.left, closeTo(proseRect.left, 2));

      await tester.tap(stopButton);
      await tester.pump();
      expect(
        voiceCalls.any(
          (call) =>
              call.method == 'stopPlayback' &&
              (call.arguments as Map)['messageId'] == 'voice-msg',
        ),
        isTrue,
      );
    },
  );
}
