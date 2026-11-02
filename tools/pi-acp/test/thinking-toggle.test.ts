import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { methods, type NewSessionResponse } from "@agentclientprotocol/sdk/unstable";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import type { LaunchConfig } from "../src/config.js";
import { harness, httpFixture, stream } from "./fixtures.js";

const thought = (result: any) => result.configOptions.find((option: any) => option.id === "thought_level");
const levels = (result: any) => thought(result).options.map((option: any) => option.value);

function configureToggle(config: LaunchConfig, format = "deepseek", mandatory = false) {
  config.modelThinkingDefaults = { fixture: "high" };
  for (const model of config.provider.models! as any[]) {
    model.reasoning = true;
    model.compat = { thinkingFormat: format, supportsReasoningEffort: false };
    if (mandatory) model.thinkingLevelMap = { off: null };
  }
}

function savedThinking(root: string, sessionId: string): string[] {
  const path = SessionManager.findById(root, sessionId, join(root, "pi", "sessions"));
  assert.ok(path);
  return readFileSync(path, "utf8").trim().split("\n").map((line) => JSON.parse(line))
    .filter((entry) => entry.type === "thinking_level_change").map((entry) => entry.thinkingLevel);
}

test("explicit switch-only formats expose off/on and send only their actual thinking switch", { timeout: 15000 }, async () => {
  for (const format of ["deepseek", "qwen", "zai", "together", "qwen-chat-template"]) {
    const provider = await httpFixture((_body, response) => stream(response, { text: "answer" }));
    const host = await harness(provider.url, { configure: (config) => configureToggle(config, format) });
    try {
      const session = await host.newSession() as NewSessionResponse;
      assert.deepEqual(levels(session), ["off", "on"], format);
      assert.equal(thought(session).currentValue, "on");
      await assert.rejects(host.remote.request(methods.agent.session.setConfigOption, {
        sessionId: session.sessionId, configId: "thought_level", value: "high",
      }), /Unsupported thinking level/);
      for (const value of ["on", "off", "on"]) {
        const updated = await host.remote.request(methods.agent.session.setConfigOption, {
          sessionId: session.sessionId, configId: "thought_level", value,
        });
        assert.equal(thought(updated).currentValue, value);
        await host.prompt(session.sessionId, `${format}: ${value}`);
        const body = provider.requests.at(-1).body;
        assert.equal(body.reasoning_effort, undefined, `${format} must not invent effort`);
        if (format === "qwen") assert.equal(body.enable_thinking, value === "on");
        else if (format === "qwen-chat-template") {
          assert.deepEqual(body.chat_template_kwargs, { enable_thinking: value === "on", preserve_thinking: true });
        } else if (format === "together") assert.deepEqual(body.reasoning, { enabled: value === "on" });
        else assert.deepEqual(body.thinking, value === "off" ? { type: "disabled" }
          : format === "zai" ? { type: "enabled", clear_thinking: false } : { type: "enabled" });
      }
      assert.equal(savedThinking(host.root, session.sessionId).at(-1), "high");
      assert.ok(savedThinking(host.root, session.sessionId).every((level) => level !== "on"));
    } finally { await host.close(); await provider.close(); }
  }
});

test("unknown effort support, token budgets and custom level-dependent formats keep SDK levels", { timeout: 15000 }, async () => {
  const cases = [
    { name: "effort unknown", compat: { thinkingFormat: "deepseek" } },
    { name: "effort supported", compat: { thinkingFormat: "deepseek", supportsReasoningEffort: true } },
    { name: "budget field", compat: { thinkingFormat: "deepseek", supportsReasoningEffort: false, thinkingTokenBudgetField: "thinking_budget" } },
    { name: "legacy budget flag", compat: { thinkingFormat: "qwen", supportsReasoningEffort: false, supportsThinkingTokenBudget: true } },
    { name: "custom template", compat: { thinkingFormat: "chat-template", supportsReasoningEffort: false } },
    { name: "string-valued thinking", compat: { thinkingFormat: "string-thinking", supportsReasoningEffort: false } },
    { name: "no switch format", compat: { thinkingFormat: "openai", supportsReasoningEffort: false } },
    { name: "level sampling", compat: { thinkingFormat: "deepseek", supportsReasoningEffort: false }, samplingParamsByThinkingLevel: { high: { temperature: 0.5 } } },
    { name: "different API", compat: { thinkingFormat: "deepseek", supportsReasoningEffort: false }, api: "openai-responses" },
  ];
  for (const entry of cases) {
    const host = await harness("http://127.0.0.1:1", { configure(config) {
      config.provider.api = entry.api ?? config.provider.api;
      for (const model of config.provider.models! as any[]) {
        model.compat = entry.compat;
        model.samplingParamsByThinkingLevel = entry.samplingParamsByThinkingLevel;
      }
    } });
    try {
      const session = await host.newSession() as NewSessionResponse;
      assert.deepEqual(levels(session), ["off", "minimal", "low", "medium", "high"], entry.name);
      await assert.rejects(host.remote.request(methods.agent.session.setConfigOption, {
        sessionId: session.sessionId, configId: "thought_level", value: "on",
      }), /Unsupported thinking level/);
    } finally { await host.close(); }
  }
});

test("mandatory thinking exposes only on and restores the SDK's persisted level", { timeout: 15000 }, async () => {
  const provider = await httpFixture((_body, response) => stream(response, { text: "answer" }));
  const configure = (config: LaunchConfig) => configureToggle(config, "deepseek", true);
  const host = await harness(provider.url, { configure });
  let restarted: Awaited<ReturnType<typeof harness>> | undefined;
  try {
    const session = await host.newSession() as NewSessionResponse;
    assert.deepEqual(levels(session), ["on"]);
    assert.equal(thought(session).currentValue, "on");
    await assert.rejects(host.remote.request(methods.agent.session.setConfigOption, {
      sessionId: session.sessionId, configId: "thought_level", value: "off",
    }), /Unsupported thinking level/);
    await host.remote.request(methods.agent.session.setConfigOption, {
      sessionId: session.sessionId, configId: "thought_level", value: "on",
    });
    await host.prompt(session.sessionId, "mandatory thinking");
    assert.deepEqual(provider.requests.at(-1).body.thinking, { type: "enabled" });
    assert.equal(provider.requests.at(-1).body.reasoning_effort, undefined);
    assert.deepEqual(savedThinking(host.root, session.sessionId), ["high"]);
    await host.close(false);
    restarted = await harness(provider.url, { root: host.root, configure });
    const restored = await restarted.remote.request(methods.agent.session.load, {
      sessionId: session.sessionId, cwd: restarted.root, mcpServers: [],
    });
    assert.deepEqual(levels(restored), ["on"]);
    assert.equal(thought(restored).currentValue, "on");
    assert.deepEqual(savedThinking(restarted.root, session.sessionId), ["high"]);
  } finally {
    if (restarted) await restarted.close(); else await host.close();
    await provider.close();
  }
});

test("off restores without a second toggle state and model changes recalculate the advertised control", { timeout: 15000 }, async () => {
  const provider = await httpFixture((_body, response) => stream(response, { text: "answer" }));
  const configure = (config: LaunchConfig) => {
    configureToggle(config);
    (config.provider.models![1] as any).compat = { thinkingFormat: "deepseek", supportsReasoningEffort: true };
  };
  const host = await harness(provider.url, { configure });
  let restarted: Awaited<ReturnType<typeof harness>> | undefined;
  try {
    const session = await host.newSession() as NewSessionResponse;
    const regular = await host.remote.request(methods.agent.session.setConfigOption, {
      sessionId: session.sessionId, configId: "model", value: "fixture-two",
    });
    assert.deepEqual(levels(regular), ["off", "minimal", "low", "medium", "high"]);
    await host.remote.request(methods.agent.session.setConfigOption, {
      sessionId: session.sessionId, configId: "thought_level", value: "low",
    });
    const toggle = await host.remote.request(methods.agent.session.setConfigOption, {
      sessionId: session.sessionId, configId: "model", value: "fixture",
    });
    assert.deepEqual(levels(toggle), ["off", "on"]);
    assert.equal(thought(toggle).currentValue, "on");
    await host.remote.request(methods.agent.session.setConfigOption, {
      sessionId: session.sessionId, configId: "thought_level", value: "on",
    });
    await host.prompt(session.sessionId, "keep the current SDK level");
    assert.equal(savedThinking(host.root, session.sessionId).at(-1), "low");
    await host.remote.request(methods.agent.session.setConfigOption, {
      sessionId: session.sessionId, configId: "thought_level", value: "off",
    });
    await host.close(false);
    restarted = await harness(provider.url, { root: host.root, configure });
    const restored = await restarted.remote.request(methods.agent.session.load, {
      sessionId: session.sessionId, cwd: restarted.root, mcpServers: [],
    });
    assert.equal(thought(restored).currentValue, "off");
    await restarted.prompt(session.sessionId, "still disabled");
    assert.deepEqual(provider.requests.at(-1).body.thinking, { type: "disabled" });
    assert.equal(savedThinking(restarted.root, session.sessionId).at(-1), "off");
  } finally {
    if (restarted) await restarted.close(); else await host.close();
    await provider.close();
  }
});
