import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/scene_voice_text_processing.dart';

void main() {
  test('sanitizeForSpeech removes markdown links urls and fenced code', () {
    final sanitized = SceneVoiceTextProcessing.sanitizeForSpeech(
      '''
这是一个[链接标题](https://example.com)
```dart
print("hello");
```
访问 https://openai.com 看看
''',
    );

    expect(sanitized, contains('这是一个链接标题'));
    expect(sanitized, isNot(contains('https://example.com')));
    expect(sanitized, isNot(contains('print("hello")')));
    expect(sanitized, isNot(contains('https://openai.com')));
  });

  test('extractSealedSegments splits on punctuation and flushes tail on final', () {
    final partial = SceneVoiceTextProcessing.extractSealedSegments(
      fullText: '第一句。第二句',
      fromIndex: 0,
      isFinal: false,
    );
    expect(partial.segments, <String>['第一句。']);
    expect(partial.nextIndex, '第一句。'.length);

    final completed = SceneVoiceTextProcessing.extractSealedSegments(
      fullText: '第一句。第二句',
      fromIndex: partial.nextIndex,
      isFinal: true,
    );
    expect(completed.segments, <String>['第二句']);
    expect(completed.nextIndex, '第一句。第二句'.length);
  });

  test('extractSealedSegments does not split inside fenced code blocks', () {
    final result = SceneVoiceTextProcessing.extractSealedSegments(
      fullText: '前言```code();\nnext();```结尾。',
      fromIndex: 0,
      isFinal: true,
    );

    expect(result.segments, <String>['前言 结尾。']);
  });
}
