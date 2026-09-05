export interface ConversationNavigationHistory {
  keys: string[];
  index: number;
}

/**
 * Appends one visited conversation while preserving the normal back/forward
 * branch semantics. The navigation trail belongs to the current WebChat tab,
 * so it deliberately has no application-defined length cap.
 */
export function appendConversationNavigation(
  history: ConversationNavigationHistory,
  key: string,
): ConversationNavigationHistory {
  if (history.keys[history.index] === key) return history;

  const keys = [...history.keys.slice(0, history.index + 1), key];
  return {
    keys,
    index: keys.length - 1,
  };
}
