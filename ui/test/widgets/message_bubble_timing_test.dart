import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/message_bubble.dart';

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
}
