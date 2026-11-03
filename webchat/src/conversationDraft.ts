import type { Conversation, ConversationMode } from "./types";

export const DRAFT_CONVERSATION_ID = 0;

const AGENT_MODE_ALIASES = new Set(["agent", "codex", "acp", "coding"]);

export function isAgentConversationMode(mode: string | undefined): boolean {
  return AGENT_MODE_ALIASES.has(mode?.trim().toLowerCase() ?? "");
}

/** The UI's historical codex mode represents any explicitly owned ACP Agent. */
export function normalizeConversationMode(mode: string | undefined): ConversationMode {
  const normalized = mode?.trim().toLowerCase();
  if (normalized === "normal" || normalized === "chat_only") return normalized;
  return "codex";
}

export function canRunConversation(conversation: Conversation): boolean {
  return conversation.mode === "normal" || conversation.mode === "chat_only"
    || isAgentConversationMode(conversation.mode);
}


export function createConversationDraft(
  mode: ConversationMode,
  updatedAt = Date.now(),
  agentId?: string,
): Conversation {
  const owner = mode === "normal" || mode === "chat_only"
    ? "xiaowan-acp"
    : agentId?.trim();
  if (!owner) throw new Error("agent_identity_required: new conversation");
  return {
    id: DRAFT_CONVERSATION_ID,
    title: "新对话",
    mode,
    agentId: owner,
    messageCount: 0,
    updatedAt,
  };
}

export function isPersistedConversation(
  conversation: Conversation | null | undefined,
): boolean {
  return Number(conversation?.id ?? 0) > 0;
}

/** Existing Agent history must carry its own owner. Product chat modes are explicit Xiaowan modes. */
export function conversationAgentId(conversation: Conversation | null | undefined): string | undefined {
  if (!conversation) return undefined;
  if (conversation.mode === "normal" || conversation.mode === "chat_only") return "xiaowan-acp";
  return conversation.agentId?.trim() || undefined;
}
