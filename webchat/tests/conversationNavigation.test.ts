import assert from "node:assert/strict";
import test from "node:test";
import {
  appendConversationNavigation,
  type ConversationNavigationHistory,
} from "../src/conversationNavigation.ts";

test("conversation navigation retains every visited conversation in a tab", () => {
  let history: ConversationNavigationHistory = { keys: [], index: -1 };

  for (let index = 0; index < 80; index += 1) {
    history = appendConversationNavigation(history, `conversation-${index}`);
  }

  assert.equal(history.keys.length, 80);
  assert.equal(history.keys[0], "conversation-0");
  assert.equal(history.keys.at(-1), "conversation-79");
  assert.equal(history.index, 79);
});

test("a navigation branch removes only forward visits", () => {
  let history: ConversationNavigationHistory = { keys: [], index: -1 };
  for (const key of ["first", "second", "third"]) {
    history = appendConversationNavigation(history, key);
  }

  history = appendConversationNavigation({ ...history, index: 0 }, "replacement");

  assert.deepEqual(history, {
    keys: ["first", "replacement"],
    index: 1,
  });
});
