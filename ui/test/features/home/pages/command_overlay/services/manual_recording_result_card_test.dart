import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_result_card.dart';

void main() {
  test('manual recording result uses GUI completion card payload', () {
    final message = buildManualRecordingResultCard(
      messageId: 'manual-1',
      summary: '录制完成',
      result: {
        'success': true,
        'run_log': {
          'run_id': 'run-1',
          'steps': [
            {
              'action': {
                'tool': 'swipe',
                'args': {'direction': 'left'},
              },
            },
            {
              'action': {
                'tool': 'press_key',
                'args': {'key': 'back'},
              },
            },
          ],
        },
        'function': {'function_id': 'recorded_demo'},
      },
    );

    expect(message.type, 2);
    final cardData = message.content!['cardData'] as Map<String, dynamic>;
    final payload =
        jsonDecode(cardData['resultPreviewJson'] as String)
            as Map<String, dynamic>;
    expect(payload['context_type'], 'manual_recording_result');
    expect(payload['run_id'], 'run-1');
    expect(payload['action_count'], 2);
    expect(payload['auto_registered'], isTrue);
    expect(payload['registered_function_id'], 'recorded_demo');
    expect(payload.containsKey('actions'), isFalse);
  });
}
