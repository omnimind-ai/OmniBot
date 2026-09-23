import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/models/chat_message_model.dart';

class _CountedMessage extends ChatMessageModel {
  _CountedMessage(int index)
    : super(
        id: 'reply-$index',
        type: 1,
        user: 2,
        content: {'text': 'answer $index'},
        streamMeta: {'runId': 'turn-$index'},
        createAt: DateTime.fromMillisecondsSinceEpoch(index + 1),
      );

  static int identityReads = 0;

  @override
  String? get runId {
    identityReads++;
    return super.runId;
  }
}

void main() {
  test('ten thousand turns keep order with linear identity lookup work', () {
    final messages = List.generate(
      10000,
      _CountedMessage.new,
    ).reversed.toList();
    _CountedMessage.identityReads = 0;
    final timeline = buildAgentRunTimelineEntries(messages);
    expect(timeline.length, 10000);
    expect(timeline.first.group!.taskId, 'turn-9999');
    expect(timeline.last.group!.taskId, 'turn-0');
    expect(
      timeline.every((entry) => entry.group!.status == AgentRunStatus.finished),
      isTrue,
    );
    // Deterministic operation bound, independent of host speed or debug mode.
    expect(_CountedMessage.identityReads, lessThan(10000 * 12));
  });
}
