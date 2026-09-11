import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/state/chat_page_mode_state.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/chat_input_area.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';

void main() {
  test('consumption preserves later picks and same-path replacement across repeated sends', () {
    final state = ChatPageModeState();
    final other = ChatPageModeState();
    const replacement = ChatInputAttachment(id: 'new', name: 'a.txt', path: '/a.txt');
    other.pendingAttachments.add(replacement);
    for (var round = 0; round < 20; round++) {
      final submitted = ChatInputAttachment(id: 'old-$round', name: 'a.txt', path: '/a.txt');
      state.pendingAttachments.add(submitted);
      final references = [submitted.toMap()];
      state.pendingAttachments.add(replacement);
      state.consumeMessageAttachments(references);
      expect(state.pendingAttachments.map((a) => a.id), ['new']);
      expect(references.single['path'], '/a.txt');
      state.consumeMessageAttachments(references);
      expect(state.pendingAttachments.map((a) => a.id), ['new']);
      expect(other.pendingAttachments, [replacement]);
      state.pendingAttachments.clear();
    }
  });

  test('empty or historical references do not consume current composer attachments', () {
    final state = ChatPageModeState();
    const attachment = ChatInputAttachment(id: 'picked', name: 'a.png', path: '/a.png');
    state.pendingAttachments.add(attachment);
    state.consumeMessageAttachments([]);
    state.consumeMessageAttachments([{'path': '/a.png'}, {'id': 'another', 'path': '/a.png'}]);
    expect(state.pendingAttachments, [attachment]);
  });

  test('starts with the established chat mode defaults', () {
    final state = ChatPageModeState();

    expect(state.messages, isEmpty);
    expect(state.pendingAttachments, isEmpty);
    expect(state.chatIslandDisplayLayer, ChatIslandDisplayLayer.mode);
    expect(state.isInputAreaVisible, isTrue);
    expect(state.isExecutingTask, isFalse);
    expect(state.currentThinkingStage, 1);
    expect(state.currentConversationId, isNull);
    expect(state.runtimeChromeSignature, isEmpty);
  });

  test('resetConversation clears conversation data but keeps view choices', () {
    final state = ChatPageModeState()
      ..messages.add(ChatMessageModel.assistantMessage('hello', id: 'm1'))
      ..pendingAttachments.add(
        const ChatInputAttachment(id: 'a1', name: 'a.txt', path: '/a.txt'),
      )
      ..currentAiMessages['task'] = 'streaming'
      ..draftMessage = 'draft'
      ..chatIslandDisplayLayer = ChatIslandDisplayLayer.tools
      ..lastAgentToolType = 'browser'
      ..runtimeChromeSignature = 'active'
      ..runtimeMessageMutationRevision = 4
      ..isInputAreaVisible = false
      ..isExecutingTask = true
      ..editingUserMessageId = 'm1'
      ..inputAreaHeight = 80
      ..isAiResponding = true
      ..isContextCompressing = true
      ..isCheckingExecutableTask = true
      ..deepThinkingContent = 'thinking'
      ..isDeepThinking = true
      ..currentDispatchTurnId = 'task'
      ..currentThinkingStage = 2
      ..currentConversationId = 42
      ..currentConversation = ConversationModel(
        id: 42,
        title: 'Conversation',
        status: 0,
        messageCount: 1,
        createdAt: 1,
        updatedAt: 1,
      )
      ..expandedAgentRunTaskIds.add('expanded-task')
      ..expandedAgentRunTaskOrder.add('expanded-task')
      ..toolActivityExpanded = true;

    state.resetConversation();

    expect(state.messages, isEmpty);
    expect(state.pendingAttachments, isEmpty);
    expect(state.currentAiMessages, isEmpty);
    expect(state.draftMessage, isEmpty);
    expect(state.chatIslandDisplayLayer, ChatIslandDisplayLayer.mode);
    expect(state.lastAgentToolType, isNull);
    expect(state.runtimeChromeSignature, isEmpty);
    expect(state.runtimeMessageMutationRevision, 0);
    expect(state.isInputAreaVisible, isTrue);
    expect(state.isExecutingTask, isFalse);
    expect(state.editingUserMessageId, isNull);
    expect(state.inputAreaHeight, 0);
    expect(state.isAiResponding, isFalse);
    expect(state.isContextCompressing, isFalse);
    expect(state.isCheckingExecutableTask, isFalse);
    expect(state.deepThinkingContent, isEmpty);
    expect(state.isDeepThinking, isFalse);
    expect(state.currentDispatchTurnId, isNull);
    expect(state.currentThinkingStage, 1);
    expect(state.currentConversationId, isNull);
    expect(state.currentConversation, isNull);

    expect(state.expandedAgentRunTaskIds, {'expanded-task'});
    expect(state.expandedAgentRunTaskOrder, ['expanded-task']);
    expect(state.toolActivityExpanded, isTrue);
  });
}
