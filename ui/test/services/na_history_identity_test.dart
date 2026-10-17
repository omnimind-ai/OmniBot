import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/services/agent_event_reducer.dart';

void main() {
  test('Na history merges live tools once across repeated runner item ids', () {
    const session = 'na:instance:conversation';
    final runtime = ChatConversationRuntimeState(
      conversationId: 42,
      mode: kChatRuntimeModeAgent,
    );
    addTearDown(runtime.dispose);
    const reducer = AgentEventReducer();
    final turns = <Map<String, dynamic>>[];
    for (final turn in ['first-prompt', 'second-prompt']) {
      final callId = '$turn-file';
      runtime.currentDispatchTurnId = turn;
      runtime.activeAcpTurnId = turn;
      for (final status in ['in_progress', 'completed']) {
        reducer.reduce(
          runtime: runtime,
          event: {
            'message': {
              'method': 'session/update',
              'turnId': turn,
              'params': {
                'sessionId': session,
                'update': {
                  'sessionUpdate': status == 'completed'
                      ? 'tool_call_update'
                      : 'tool_call',
                  'toolCallId': callId,
                  'title': 'fixture write phone-result.md',
                  'kind': 'execute',
                  'status': status,
                },
              },
            },
          },
        );
      }
      turns.add({
        'id': turn,
        'status': 'completed',
        'items': [
          {
            'id': callId,
            'type': 'command_execution',
            'command': 'fixture write phone-result.md',
            'status': 'completed',
            'sessionId': session,
            'turnId': turn,
            'toolCallId': callId,
          },
        ],
      });
    }
    final snapshot = remoteCodexMessagesFromThreadResponseForTesting({
      'id': session,
      'agentId': 'na',
      'turns': turns,
    });
    expect(runtime.messages, hasLength(2));
    expect(
      snapshot.map((m) => m.id).toSet(),
      runtime.messages.map((m) => m.id).toSet(),
    );
    final merged = mergeRemoteCodexSnapshotMessagesForTesting(
      snapshotMessages: snapshot,
      existingMessages: runtime.messages,
      activeTaskId: null,
      isAiResponding: false,
    );
    expect(merged, hasLength(2));
    expect(merged.map((m) => m.cardData!['toolCallId']).toSet(), {
      'first-prompt-file',
      'second-prompt-file',
    });
  });
}
