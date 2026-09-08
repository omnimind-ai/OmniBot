import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/deep_thinking_persistence.dart';

void main() {
  test('persists a long deep-thinking trace verbatim', () {
    final thinking = List<String>.filled(5000, '推理🙂\n').join();
    final cardData = <String, dynamic>{
      'type': 'deep_thinking',
      'thinkingContent': thinking,
    };

    final persisted = buildPersistentDeepThinkingCardData(cardData);

    expect(persisted['thinkingContent'], thinking);
    expect(persisted['thinkingContentTruncated'], isFalse);
    expect(persisted['thinkingOriginalLength'], thinking.length);
    expect(persisted['thinkingTruncateMode'], 'none');
    expect(cardData, isNot(contains('thinkingContentTruncated')));
  });

  test('keeps existing card fields while recording full thinking metadata', () {
    final cardData = <String, dynamic>{
      'type': 'deep_thinking',
      'thinkingContent': 'first step\nsecond step',
      'customField': <String>['a', 'b'],
    };

    final persisted = buildPersistentDeepThinkingCardData(cardData);

    expect(persisted['customField'], cardData['customField']);
    expect(persisted['thinkingContent'], 'first step\nsecond step');
    expect(persisted['thinkingContentTruncated'], isFalse);
    expect(persisted['thinkingOriginalLength'], 22);
    expect(persisted['thinkingTruncateMode'], 'none');
  });
}
