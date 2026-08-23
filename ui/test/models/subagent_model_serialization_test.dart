import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/scheduled_task.dart';

void main() {
  test('ConversationMode supports chat_only serialization', () {
    expect(ConversationMode.chatOnly.storageValue, 'chat_only');
    expect(
      ConversationMode.fromStorageValue('chat_only'),
      ConversationMode.chatOnly,
    );
    expect(
      ConversationMode.fromStorageValue('CHAT_ONLY'),
      ConversationMode.chatOnly,
    );
  });

  test('ConversationMode supports subagent serialization', () {
    expect(ConversationMode.subagent.storageValue, 'subagent');
    expect(
      ConversationMode.fromStorageValue('subagent'),
      ConversationMode.subagent,
    );
    expect(
      ConversationMode.fromStorageValue('SUBAGENT'),
      ConversationMode.subagent,
    );
  });

  test('ConversationMode reads legacy Agent mode aliases', () {
    expect(ConversationMode.agent.storageValue, 'agent');
    expect(ConversationMode.fromStorageValue('agent'), ConversationMode.agent);
    expect(ConversationMode.fromStorageValue('codex'), ConversationMode.agent);
    expect(ConversationMode.fromStorageValue('acp'), ConversationMode.agent);
  });

  test('missing or unknown conversation mode defaults to agent', () {
    expect(ConversationMode.fromStorageValue(null), ConversationMode.agent);
    expect(ConversationMode.fromStorageValue(''), ConversationMode.agent);
    expect(
      ConversationMode.fromStorageValue('future_mode'),
      ConversationMode.agent,
    );
    expect(
      ConversationMode.fromStorageValue('normal'),
      ConversationMode.agent,
    );
  });

  test('ScheduledTask keeps subagent fields through json', () {
    final now = DateTime.now().millisecondsSinceEpoch;
    final task = ScheduledTask(
      id: 'task-subagent-1',
      title: '新闻整理',
      targetKind: 'subagent',
      subagentConversationId: '12345',
      parentConversationId: '7',
      parentConversationMode: ConversationMode.normal.storageValue,
      subagentPrompt: '每晚整理一下今天的新闻',
      notificationEnabled: true,
      type: ScheduledTaskType.fixedTime,
      fixedTime: '18:00',
      repeatDaily: true,
      isEnabled: true,
      createdAt: now,
      nextExecutionTime: now + 3600 * 1000,
    );

    final restored = ScheduledTask.fromJson(task.toJson());

    expect(restored.targetKind, 'subagent');
    expect(restored.subagentConversationId, '12345');
    expect(restored.parentConversationId, '7');
    expect(
      restored.parentConversationMode,
      ConversationMode.normal.storageValue,
    );
    expect(restored.subagentPrompt, '每晚整理一下今天的新闻');
    expect(restored.notificationEnabled, true);
  });
}
