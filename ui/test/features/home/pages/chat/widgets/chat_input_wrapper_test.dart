import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/chat_input_area.dart';

void main() {
  testWidgets(
    'unavailable Agent preserves the draft and exposes no send action',
    (tester) async {
      final controller = TextEditingController(text: 'Saved draft');
      final focusNode = FocusNode();
      addTearDown(controller.dispose);
      addTearDown(focusNode.dispose);
      var sends = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ChatInputWrapper(
              inputAreaKey: GlobalKey<ChatInputAreaState>(),
              controller: controller,
              focusNode: focusNode,
              isProcessing: false,
              unavailableMessage:
                  'History remains available. Start another conversation.',
              onSendMessage: ({String? text}) async => sends++,
              onCancelTask: () {},
              onPopupVisibilityChanged: (_) {},
            ),
          ),
        ),
      );

      expect(find.byKey(const Key('chat-agent-unavailable')), findsOneWidget);
      expect(find.byType(ChatInputArea), findsNothing);
      expect(find.byType(TextField), findsNothing);
      expect(controller.text, 'Saved draft');
      expect(sends, 0);
    },
  );
}
