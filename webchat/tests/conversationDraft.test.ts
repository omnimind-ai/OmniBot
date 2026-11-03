import assert from "node:assert/strict";
import test from "node:test";
import {
  createConversationDraft,
  conversationAgentId,
  normalizeConversationMode,
  canRunConversation,
  isPersistedConversation,
} from "../src/conversationDraft.ts";

test("choosing a new conversation mode creates only a local draft", () => {
  const draft = createConversationDraft("codex", 1234, "claude-code-acp");

  assert.equal(draft.id, 0);
  assert.equal(draft.mode, "codex");
  assert.equal(draft.agentId, "claude-code-acp");
  assert.equal(draft.updatedAt, 1234);
  assert.equal(isPersistedConversation(draft), false);
});

test("only a positive server id is considered persisted", () => {
  assert.equal(isPersistedConversation({ id: 17, mode: "normal" }), true);
  assert.equal(isPersistedConversation(null), false);
});


test("only new product chat targets receive the explicit Xiaowan owner", () => {
  assert.equal(createConversationDraft("normal").agentId, "xiaowan-acp");
  assert.equal(createConversationDraft("chat_only").agentId, "xiaowan-acp");
  assert.throws(() => createConversationDraft("codex"), /agent_identity_required/);
  assert.equal(conversationAgentId({ id: 17, mode: "codex" }), undefined);
  assert.equal(conversationAgentId({ id: 18, mode: "chat_only" }), "xiaowan-acp");
  assert.equal(conversationAgentId({ id: 19, mode: "codex", agentId: "na-cloud" }), "na-cloud");
});


test("native Agent payloads preserve their explicit owner and never normalize to ordinary chat", () => {
  const native = { id: 27, mode: "agent", agentId: "na-cloud" };
  assert.equal(normalizeConversationMode(native.mode), "codex");
  assert.equal(conversationAgentId(native), "na-cloud");
  assert.equal(canRunConversation(native), true);
  assert.equal(conversationAgentId({ id: 28, mode: "agent" }), undefined);
  assert.equal(normalizeConversationMode("unknown"), "codex");
  assert.equal(canRunConversation({ id: 29, mode: "subagent", agentId: "xiaowan-acp" }), false);
  assert.equal(canRunConversation({ id: 30 }), false);
});
