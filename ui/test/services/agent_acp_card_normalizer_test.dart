import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/agent_acp_card_normalizer.dart';

void main() {
  test('turns markdown ACP plans into structured mutable entries', () {
    final card = AgentAcpCardNormalizer.normalize({
      'type': 'plan',
      'entries': const <Map<String, dynamic>>[],
      'plan': '# Plan\n\n- [x] Inspect workspace\n- [ ] Suggest improvement',
    });

    expect(card['type'], 'agent_tool_summary');
    expect(card['toolType'], 'plan');
    expect(card['planEntries'], [
      {'content': 'Inspect workspace', 'status': 'completed'},
      {'content': 'Suggest improvement', 'status': 'pending'},
    ]);
  });

  test('prefers structured ACP entries when both forms are present', () {
    final card = AgentAcpCardNormalizer.normalize({
      'type': 'plan',
      'entries': [
        {'content': 'Structured task', 'status': 'in_progress'},
      ],
      'plan': '- [ ] Markdown fallback',
    });

    expect(card['planEntries'], [
      {'content': 'Structured task', 'status': 'in_progress'},
    ]);
  });

  test('reads entries nested in an ACP plan object', () {
    final card = AgentAcpCardNormalizer.normalize({
      'type': 'plan',
      'plan': {
        'type': 'entries',
        'entries': [
          {'content': 'Nested task', 'status': 'pending'},
        ],
      },
    });

    expect(card['planEntries'], [
      {'content': 'Nested task', 'status': 'pending'},
    ]);
  });

  test('normalizes reducer-created plan cards with markdown summaries', () {
    final card = AgentAcpCardNormalizer.normalize({
      'type': 'agent_tool_summary',
      'toolType': 'plan',
      'summary': '- [ ] Inspect the workspace\n- [x] Report the result',
      'planEntries': const <Map<String, dynamic>>[],
    });

    expect(card['planEntries'], [
      {'content': 'Inspect the workspace', 'status': 'pending'},
      {'content': 'Report the result', 'status': 'completed'},
    ]);
  });

  test('uses the ACP lifecycle status instead of raw tool output', () {
    final card = AgentAcpCardNormalizer.normalize({
      'type': 'tool_call_update',
      'status': 'pending',
      'success': false,
      'rawOutput': {'success': false, 'question': '请确认执行高权限操作'},
    });

    expect(card['status'], 'pending');
  });
}
