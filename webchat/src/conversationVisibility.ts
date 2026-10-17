import type { Conversation } from "./types";

/** Device account changes invalidate only Na display caches. The server remains
 * the owner of authorization, and the existing ACP runtime owns execution. */
export function isNaConversation(conversation: Conversation | null | undefined): boolean {
  return conversation?.agentId === "na-cloud";
}

export function withoutNaHistory(conversations: Conversation[]): Conversation[] {
  return conversations.filter((conversation) => !isNaConversation(conversation));
}
