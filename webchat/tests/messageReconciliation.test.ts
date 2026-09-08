import assert from "node:assert/strict";
import test from "node:test";
import { reconcileCodexMessages } from "../src/messageReconciliation.ts";
import type { ChatMessage } from "../src/types.ts";

function message(overrides: Partial<ChatMessage>): ChatMessage {
  return {
    type: 1,
    user: 2,
    content: { text: "" },
    ...overrides,
  };
}

test("a stale Codex snapshot cannot remove the visible user bubble", () => {
  const user = message({
    id: "run-1-user",
    user: 1,
    content: { id: "run-1-user", text: "请检查项目" },
    createAt: 1000,
  });
  const assistant = message({
    id: "item-1-codex-agent",
    content: { id: "item-1-codex-agent", text: "好的" },
    createAt: 1001,
  });

  assert.deepEqual(
    reconcileCodexMessages([user], [assistant]).map((item) => item.id),
    ["run-1-user", "item-1-codex-agent"],
  );
});

test("native and Flutter copies of the same Codex answer collapse to one", () => {
  const bridged = message({
    id: "run-1-codex-assistant",
    content: { text: "已完成分析" },
    createAt: 2000,
    streamMeta: { parentTaskId: "run-1" },
  });
  const canonical = message({
    id: "item-1-codex-agent",
    content: { text: "已完成分析" },
    createAt: 2001,
    streamMeta: { parentTaskId: "turn-1", isFinal: true },
  });

  const result = reconcileCodexMessages([bridged], [canonical]);
  assert.equal(result.length, 1);
  assert.equal(result[0].id, "item-1-codex-agent");
});

test("duplicate canonical events in one turn collapse without merging later phases", () => {
  const first = message({
    id: "protocol-1-codex-agent",
    content: { text: "正在处理" },
    createAt: 2500,
    streamMeta: { parentTaskId: "turn-1" },
  });
  const duplicate = message({
    id: "item-1-codex-agent",
    content: { text: "正在处理" },
    createAt: 2501,
    streamMeta: { parentTaskId: "turn-1" },
  });
  const laterPhase = message({
    id: "item-2-codex-agent",
    content: { text: "正在处理" },
    createAt: 5001,
    streamMeta: { parentTaskId: "turn-1" },
  });

  const result = reconcileCodexMessages([first], [duplicate, laterPhase]);
  assert.equal(result.length, 2);
});

test("tool and assistant entries retain monotonic turn order", () => {
  const user = message({
    id: "run-2-user",
    user: 1,
    content: { text: "运行测试" },
    createAt: 3000,
  });
  const tool: ChatMessage = {
    id: "tool-1-codex-command",
    type: 2,
    user: 3,
    content: {
      cardData: {
        type: "agent_tool_summary",
        taskId: "turn-2",
        status: "success",
      },
    },
    createAt: 3001,
    streamMeta: { parentTaskId: "turn-2", seq: 1 },
  };
  const assistant = message({
    id: "item-2-codex-agent",
    content: { text: "测试通过" },
    createAt: 3002,
    streamMeta: { parentTaskId: "turn-2", seq: 2 },
  });

  assert.deepEqual(
    reconcileCodexMessages([user, tool], [tool, assistant]).map((item) => item.id),
    ["run-2-user", "tool-1-codex-command", "item-2-codex-agent"],
  );
});

test("a two-turn conversation keeps each user request and reply distinct", () => {
  const firstUser = message({
    id: "run-10-user",
    user: 1,
    content: { text: "先检查项目" },
    createAt: 10_000,
  });
  const firstReply = message({
    id: "item-10-codex-agent",
    content: { text: "项目检查完成" },
    createAt: 10_001,
    streamMeta: { parentTaskId: "turn-10", isFinal: true },
  });
  const secondUser = message({
    id: "run-11-user",
    user: 1,
    content: { text: "再解释一下结果" },
    createAt: 20_000,
  });
  const secondReplyChunk = message({
    id: "item-11-codex-agent",
    content: { text: "结果表示" },
    createAt: 20_001,
    streamMeta: { parentTaskId: "turn-11", seq: 1 },
  });
  const secondReplyFinal = message({
    id: "item-11-codex-agent",
    content: { text: "结果表示项目状态正常" },
    createAt: 20_002,
    streamMeta: { parentTaskId: "turn-11", seq: 2, isFinal: true },
  });

  const afterFirstTurn = reconcileCodexMessages(
    [firstUser, firstReply],
    [],
  );
  const afterSecondInput = reconcileCodexMessages(afterFirstTurn, [secondUser]);
  const afterStreaming = reconcileCodexMessages(afterSecondInput, [secondReplyChunk]);
  const result = reconcileCodexMessages(afterStreaming, [secondReplyFinal]);

  assert.deepEqual(
    result.map((item) => item.id),
    [
      "run-10-user",
      "item-10-codex-agent",
      "run-11-user",
      "item-11-codex-agent",
    ],
  );
  assert.deepEqual(
    result.map((item) => item.content.text),
    ["先检查项目", "项目检查完成", "再解释一下结果", "结果表示项目状态正常"],
  );
});
