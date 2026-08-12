import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/speech_transcription_service.dart';

void main() {
  test('parses a native speech transcription result', () {
    final result = SpeechTranscriptionResult.fromMap(<dynamic, dynamic>{
      'text': '  hello OmniBot  ',
      'model': 'official-stt',
      'route': 'platform',
      'durationMs': 1234.8,
    });

    expect(result.text, 'hello OmniBot');
    expect(result.model, 'official-stt');
    expect(result.route, 'platform');
    expect(result.durationMs, 1234);
  });

  test('parses completed and error native events without inventing text', () {
    final completed = SpeechTranscriptionEvent.fromMap(<dynamic, dynamic>{
      'state': 'completed',
      'text': 'transcript',
      'model': 'official-stt',
      'route': 'platform',
      'durationMs': '900',
    });
    final error = SpeechTranscriptionEvent.fromMap(<dynamic, dynamic>{
      'state': 'error',
      'code': 'STT_QUOTA_EXCEEDED',
      'message': '平台额度不足',
    });

    expect(completed.result?.text, 'transcript');
    expect(completed.result?.durationMs, 900);
    expect(error.result, isNull);
    expect(error.code, 'STT_QUOTA_EXCEEDED');
    expect(error.message, '平台额度不足');
  });
}
