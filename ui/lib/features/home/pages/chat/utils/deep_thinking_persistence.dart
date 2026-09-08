Map<String, dynamic> buildPersistentDeepThinkingCardData(
  Map<String, dynamic> cardData,
) {
  final result = Map<String, dynamic>.from(cardData);
  final thinking = (result['thinkingContent'] ?? '').toString();
  result['thinkingContentTruncated'] = false;
  result['thinkingOriginalLength'] = thinking.length;
  result['thinkingTruncateMode'] = 'none';
  return result;
}
