import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { catalogPath, generateCatalog, reasoningEntry } from "../scripts/generate-reasoning-catalog.mjs";

test("native reasoning catalog matches the pinned dependency without network discovery", () => {
  assert.equal(readFileSync(catalogPath, "utf8"), `${JSON.stringify(generateCatalog(), null, 2)}\n`);
});

test("reasoning metadata never turns generic SDK fallback levels into a provider declaration", () => {
  assert.deepEqual(reasoningEntry({ reasoning: true }), { reasoning: true });
  assert.deepEqual(reasoningEntry({ reasoning: true, thinkingLevelMap: { off: null } }), {
    reasoning: true, reasoningCanDisable: false,
  });
  assert.deepEqual(reasoningEntry({ reasoning: true, thinkingLevelMap: {
    off: "none", minimal: "low", low: "low", high: "high", xhigh: null, max: "provider-custom-value",
  } }), {
    reasoning: true, supportedReasoningLevels: ["none", "low", "high"], reasoningCanDisable: true,
  });
});

test("adaptive thinking is copied only when the model compatibility explicitly declares it", () => {
  for (const supported of [true, false]) {
    assert.deepEqual(reasoningEntry({ reasoning: true, compat: { forceAdaptiveThinking: supported } }), {
      reasoning: true, reasoningSupportsAdaptive: supported,
    });
  }
  assert.deepEqual(reasoningEntry({ reasoning: true, compat: { forceAdaptiveThinking: "true" } }), { reasoning: true });
});

test("provider reasoning wire controls preserve explicit false and do not infer a format", () => {
  assert.deepEqual(reasoningEntry({ reasoning: true, compat: {
    thinkingFormat: "deepseek", supportsReasoningEffort: false, requiresReasoningContentOnAssistantMessages: true,
  } }), {
    reasoning: true, reasoningWireFormat: "deepseek", reasoningEffortSupported: false, reasoningContentRequired: true,
  });
  assert.deepEqual(reasoningEntry({ reasoning: true, compat: {
    thinkingFormat: "qwen", supportsReasoningEffort: true,
  } }), {
    reasoning: true, reasoningWireFormat: "qwen", reasoningEffortSupported: true,
  });
  assert.deepEqual(reasoningEntry({ reasoning: true, compat: {
    thinkingFormat: true, supportsReasoningEffort: "false", requiresReasoningContentOnAssistantMessages: "true",
  } }), { reasoning: true });
  assert.deepEqual(reasoningEntry({ reasoning: true, compat: {
    requiresReasoningContentOnAssistantMessages: false,
  } }), { reasoning: true, reasoningContentRequired: false });
});

test("explicit disabled wire values remain distinct from an undeclared toggle", () => {
  for (const off of ["none", "off"]) {
    assert.deepEqual(reasoningEntry({ reasoning: true, thinkingLevelMap: { off, high: "high" } }), {
      reasoning: true, supportedReasoningLevels: [off, "high"], reasoningCanDisable: true,
    });
  }
  assert.deepEqual(reasoningEntry({ reasoning: true, thinkingLevelMap: { high: "high" } }), {
    reasoning: true, supportedReasoningLevels: ["high"],
  });
});

test("reviewed endpoint aliases copy reasoning only for the exact approved model IDs", () => {
  const catalog = generateCatalog();
  const completions = catalog.routes.find((route) => route.api === "openai-completions" && route.baseUrl === "https://api.openai.com/v1");
  const responses = catalog.routes.find((route) => route.api === "openai-responses" && route.baseUrl === "https://api.openai.com/v1");
  assert.equal(Object.keys(completions!.models).length, 31);
  assert.deepEqual(completions!.models["gpt-5.4"], responses!.models["gpt-5.4"]);
  assert.deepEqual(completions!.models["gpt-6.1-sol"], responses!.models["gpt-6.1-sol"]);
  for (const id of ["gpt-5.4-pro", "gpt-5.3-codex", "gpt-5.4-custom"]) assert.equal(completions!.models[id], undefined);
  assert.ok(responses!.models["gpt-5.4-pro"]);
  assert.equal(completions!.models["gpt-6.1-sol"].toolCall, undefined);
});
