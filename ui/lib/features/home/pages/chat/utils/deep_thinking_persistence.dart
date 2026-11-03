Map<String, dynamic> buildPersistentDeepThinkingCardData(
  Map<String, dynamic> cardData,
) {
  final result = Map<String, dynamic>.from(cardData);
  // A display preview must never masquerade as the complete canonical trace.
  if (result['historyPayloadOffloaded'] == true) return result;
  final thinking = (result['thinkingContent'] ?? '').toString();
  result['thinkingContentTruncated'] = false;
  result['thinkingOriginalLength'] = thinking.length;
  result['thinkingTruncateMode'] = 'none';
  return result;
}
