import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/home/pages/chat/chat_page.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/services/agent_event_reducer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('Na snapshot carries each server run status without adding local ACP work', () {
    for (final agentId in ['na-cloud', 'codex-acp']) {
      final messages = remoteCodexMessagesFromThreadResponseForTesting({
        'agentId': agentId,
        'turns': [
          for (final status in ['running', 'completed', 'failed', 'cancelled'])
            {'id': status, 'status': status, 'items': [
              {'id': '$status-reply', 'type': 'agentMessage', 'text': status},
            ]},
        ],
      });
      for (final message in messages) {
        expect(message.streamMeta?['naRunStatus'], agentId == 'na-cloud' ? message.text : null);
      }
    }
  });
  setUpAll(() async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      const MethodChannel('cn.com.omnimind.bot/AssistCoreEvent'),
      (call) async =>
          call.method == 'getSceneModelBindings' ? [] : <String, dynamic>{},
    );
    for (final name in [
      'AgentRuntime',
      'AgentRuntimeEvents',
      'VoicePlaybackEvents',
      'VoicePlayback',
    ]) {
      messenger.setMockMethodCallHandler(
        MethodChannel('cn.com.omnimind.bot/$name'),
        (_) async => <String, dynamic>{},
      );
    }
    ChatConversationRuntimeCoordinator.instance.ensureInitialized();
    await Future<void>.delayed(Duration.zero);
  });

  for (final conflicting in [false, true]) {
    test(
      'Na migrates only unique explicit legacy user aliases: conflict=$conflicting',
      () {
        final previous = ChatMessageModel.userMessage('原消息', id: 'legacy-user');
        final snapshot = remoteCodexMessagesFromThreadResponseForTesting({
          'agentId': 'na-cloud',
          'turns': [
            for (final turn in conflicting ? ['first', 'second'] : ['first'])
              {
                'id': turn,
                'stopReason': 'end_turn',
                'items': [
                  {
                    'id': '$turn-user',
                    'type': 'userMessage',
                    'content': '原消息',
                    'legacyHostMessageId': 'legacy-user',
                  },
                ],
              },
          ],
        });
        final merged = mergeRemoteCodexSnapshotMessagesForTesting(
          snapshotMessages: snapshot,
          existingMessages: [previous],
          activeTaskId: null,
          isAiResponding: false,
          partialHistory: true,
        );
        expect(
          merged.where((row) => row.id == previous.id),
          conflicting ? hasLength(1) : isEmpty,
        );
        expect(merged, hasLength(conflicting ? 3 : 1));
      },
    );
  }
  test(
    'partial Na snapshots retain older runs and repeated user text by identity',
    () {
      final old = remoteCodexMessagesFromThreadResponseForTesting({
        'agentId': 'na-cloud',
        'turns': [
          {
            'id': 'old',
            'stopReason': 'end_turn',
            'items': [
              {'id': 'old-user', 'type': 'userMessage', 'content': '再试一次'},
              {'id': 'old-answer', 'type': 'agentMessage', 'text': '旧回复'},
            ],
          },
        ],
      });
      final recent = remoteCodexMessagesFromThreadResponseForTesting({
        'agentId': 'na-cloud',
        'turns': [
          {
            'id': 'new',
            'stopReason': 'end_turn',
            'items': [
              {'id': 'new-user', 'type': 'userMessage', 'content': '再试一次'},
              {'id': 'new-answer', 'type': 'agentMessage', 'text': '新回复'},
            ],
          },
        ],
      });
      var merged = old;
      for (var attempt = 0; attempt < 3; attempt++) {
        merged = mergeRemoteCodexSnapshotMessagesForTesting(
          snapshotMessages: recent,
          existingMessages: merged,
          activeTaskId: null,
          isAiResponding: false,
          partialHistory: true,
        );
      }
      expect(merged.map((row) => row.id).toSet(), {
        ...old.map((row) => row.id),
        ...recent.map((row) => row.id),
      });
      expect(merged.where((row) => row.text == '再试一次'), hasLength(2));
    },
  );

  test('Na history updates another phone while preserving this phone queued prompt', () {
    final coordinator = ChatConversationRuntimeCoordinator.instance;
    coordinator.resetForTest();
    addTearDown(coordinator.resetForTest);
    final runtime = coordinator.ensureRuntime(
      conversationId: 42,
      mode: kChatRuntimeModeAgent,
      conversation: ConversationModel(
        id: 42,
        title: 'Na',
        status: 0,
        agentId: 'na-cloud',
        messageCount: 0,
        createdAt: 1,
        updatedAt: 1,
      ),
    );
    coordinator.beginAcpTurn(
      taskId: 'my-queued-turn',
      conversationId: 42,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.bindAcpSession(
      taskId: 'my-queued-turn',
      conversationId: 42,
      mode: kChatRuntimeModeAgent,
      sessionId: 'na:instance:session',
    );
    final local = ChatMessageModel(
      id: 'own-reply',
      type: 1,
      user: 2,
      content: {'text': '本机输出'},
      streamMeta: {'parentTaskId': 'my-queued-turn'},
    );
    runtime.messages.add(local);
    for (final text in ['另一端输出中', '另一端输出增加了']) {
      final remote = remoteCodexMessagesFromThreadResponseForTesting(
        {
          'agentId': 'na-cloud',
          'turns': [
            {
              'id': 'other-turn',
              'status': 'running',
              'items': [
                {'id': 'other-reply', 'type': 'agentMessage', 'text': text},
                {'id': 'other-thinking', 'type': 'reasoning', 'text': '远端思考'},
              ],
            },
          ],
        },
        active: true,
        activeTurnId: 'other-turn',
      );
      final merged = mergeRemoteCodexSnapshotMessagesForTesting(
        snapshotMessages: remote,
        existingMessages: runtime.messages,
        activeTaskId: runtime.activeRunId,
        isAiResponding: true,
        partialHistory: true,
      );
      coordinator.mergeNaHistoryMessages(conversationId: 42, messages: merged);
      expect(runtime.messages.where((row) => row.text == text), hasLength(1));
      expect(
        runtime.messages.firstWhere((row) => row.id == local.id),
        same(local),
      );
      expect(
        runtime.messages
            .singleWhere((row) => row.cardData?['type'] == 'deep_thinking')
            .cardData?['isLoading'],
        true,
      );
      expect(runtime.activeRunId, 'my-queued-turn');
      expect(runtime.activeAcpSessionId, 'na:instance:session');
      expect(runtime.hasInFlightTask, true);
    }
  });
  for (final reason in [null, 'end_turn', 'cancelled']) {
    test(
      'Na terminal history reconciles transport failure, outcome=$reason',
      () {
        final runtime = ChatConversationRuntimeState(
          conversationId: 42,
          mode: kChatRuntimeModeAgent,
        );
        addTearDown(runtime.dispose);
        runtime.currentDispatchTurnId = 'prompt';
        runtime.activeAcpTurnId = 'prompt';
        runtime.activeAcpSessionId = 'na:session';
        const AgentEventReducer().reducePromptResponse(
          runtime: runtime,
          sessionId: 'na:session',
          turnId: 'prompt',
          stopReason: 'error',
          error: 'Network unavailable',
        );
        expect(runtime.messages.any(isAgentTurnFailureMessage), true);
        final snapshot = remoteCodexMessagesFromThreadResponseForTesting({
          'id': 'na:session',
          'agentId': 'na-cloud',
          'turns': [
            {
              'id': 'prompt',
              'status': reason == null ? 'running' : 'completed',
              'stopReason': reason,
              'items': [
                {
                  'id': 'prompt-reply',
                  'type': 'agentMessage',
                  'text': 'remote result',
                },
              ],
            },
          ],
        });
        final merged = mergeRemoteCodexSnapshotMessagesForTesting(
          snapshotMessages: snapshot,
          existingMessages: runtime.messages,
          activeTaskId: null,
          isAiResponding: false,
        );
        expect(merged.any(isAgentTurnFailureMessage), reason == null);
        expect(merged.where((m) => m.text == 'remote result'), hasLength(1));
        if (reason != null) {
          expect(merged.single.streamMeta?['stopReason'], reason);
        }
      },
    );
  }
  test(
    'Na reload preserves local attachment cards on the same user message',
    () {
      final local = ChatMessageModel(
        id: 'user-attachment',
        type: 1,
        user: 1,
        content: {
          'text': 'Read the file',
          'attachments': [
            {
              'id': 'file',
              'name': 'report.txt',
              'path': 'content://files/1',
              'size': 17,
            },
          ],
        },
      );
      final snapshot = ChatMessageModel(
        id: local.id,
        type: 1,
        user: 1,
        content: {
          'text': 'Read the file\n\n[附件] attachment-uuid-report.txt',
          'attachments': [],
        },
        streamMeta: {'sessionId': 'na:session', 'turnId': 'turn'},
      );
      final merged = mergeRemoteCodexSnapshotMessagesForTesting(
        snapshotMessages: [snapshot],
        existingMessages: [local],
        activeTaskId: null,
        isAiResponding: false,
      );
      expect(merged, hasLength(1));
      expect(merged.single.text, local.text);
      expect(
        merged.single.content!['attachments'],
        local.content!['attachments'],
      );
      expect(merged.single.sessionId, 'na:session');
      final remote = snapshot.copyWith(
        content: {
          'text': 'remote',
          'attachments': [
            {'name': 'remote.txt'},
          ],
        },
      );
      expect(
        mergeRemoteCodexSnapshotMessagesForTesting(
          snapshotMessages: [remote],
          existingMessages: [local],
          activeTaskId: null,
          isAiResponding: false,
        ).single.content!['attachments'],
        remote.content!['attachments'],
      );
    },
  );

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
