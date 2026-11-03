import assert from "node:assert/strict";
import test from "node:test";
import { isRetiredAgentConversation } from "../src/conversationVisibility.ts";

test("retirement follows the server catalog without rewriting historical ownership", () => {
  const conversation = { id: 7, mode: "codex", agentId: "deepseek-harness-acp" };
  assert.equal(isRetiredAgentConversation(conversation, []), false);
  assert.equal(isRetiredAgentConversation(conversation, [conversation.agentId]), true);
  assert.equal(conversation.agentId, "deepseek-harness-acp");
  assert.equal(isRetiredAgentConversation({ ...conversation, mode: "agent" }, [conversation.agentId]), true);
  assert.equal(isRetiredAgentConversation({ id: 8, agentId: "codex-acp" }, [conversation.agentId]), false);
  assert.equal(isRetiredAgentConversation(null, [conversation.agentId]), false);
  assert.equal(isRetiredAgentConversation({ ...conversation, mode: "chat_only" }, [conversation.agentId]), false);
});
