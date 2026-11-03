import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { methods, type AgentContext } from "@agentclientprotocol/sdk/unstable";
import { ModelRuntime, SessionManager } from "@earendil-works/pi-coding-agent";
import { readLaunchConfig, type LaunchConfig } from "../src/config.js";
import { PiAcpSession } from "../src/session.js";
import { configFor, harness, httpFixture, stream } from "./fixtures.js";

const thinking = (result: any) => result.configOptions.find((option: any) => option.id === "thought_level");
const levels = (result: any) => thinking(result).options.map((option: any) => option.value);
const deepSeekLevels = { minimal: null, low: "low", medium: null, high: "high", xhigh: null, max: "max" };

function catalogFile(config: LaunchConfig, configure?: (document: any) => void) {
  mkdirSync(config.agentDir, { recursive: true });
  const path = join(config.agentDir, "models.json");
  const document = {
    providers: { omnibot: structuredClone(config.provider) },
    _meta: { omnibot: {
      providerProfileId: "provider-a", providerRevision: 7,
      modelThinkingDefaults: { fixture: "high", "fixture-two": "low" },
    } },
  };
  configure?.(document);
  const save = () => writeFileSync(path, JSON.stringify(document));
  save();
  Object.assign(config, readLaunchConfig({
    PI_CODING_AGENT_DIR: config.agentDir, OMNIBOT_PI_MODELS_PATH: path,
    OMNIBOT_PI_MODEL: config.modelId, OMNIBOT_PI_API_KEY: config.apiKey,
    OMNIBOT_PI_HEADERS: JSON.stringify(config.provider.headers ?? {}),
  }));
  return { document, save, path };
}

test("launch identity rejects a shared file replaced between native configuration and process startup", () => {
  const root = mkdtempSync(join(tmpdir(), "pi-launch-identity-"));
  try {
    const config = configFor(root, "http://127.0.0.1:1");
    const file = catalogFile(config);
    const env = {
      PI_CODING_AGENT_DIR: config.agentDir, OMNIBOT_PI_MODELS_PATH: file.path,
      OMNIBOT_PI_MODEL: config.modelId, OMNIBOT_PI_API_KEY: config.apiKey,
      OMNIBOT_PI_PROVIDER_ID: "provider-a", OMNIBOT_PI_PROVIDER_REVISION: "7",
      OMNIBOT_PI_PROVIDER_API: "openai-completions", OMNIBOT_PI_PROVIDER_BASE_URL: "http://127.0.0.1:1/v1",
    };
    assert.equal(readLaunchConfig(env).capabilityIdentity?.providerProfileId, "provider-a");
    for (const mismatch of [
      { OMNIBOT_PI_PROVIDER_ID: "provider-b" }, { OMNIBOT_PI_PROVIDER_REVISION: "8" },
      { OMNIBOT_PI_PROVIDER_API: "openai-responses" }, { OMNIBOT_PI_PROVIDER_BASE_URL: "https://other.invalid/v1" },
    ]) assert.throws(() => readLaunchConfig({ ...env, ...mismatch }), /configuration changed/);
    assert.throws(() => readLaunchConfig({ ...env, OMNIBOT_PI_PROVIDER_REVISION: undefined }), /Incomplete/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test("session load refreshes its model capabilities through Pi without replacing the conversation", { timeout: 15000 }, async () => {
  const provider = await httpFixture((_body, response) => stream(response, { text: "answer" }));
  let file!: ReturnType<typeof catalogFile>;
  const host = await harness(provider.url, { configure(config) {
    file = catalogFile(config, (document) => {
      for (const model of document.providers.omnibot.models) model.reasoning = false;
    });
  } });
  try {
    const session = await host.newSession();
    assert.deepEqual(levels(session), ["off"]);
    await host.prompt(session.sessionId, "preserve-history");
    for (const model of file.document.providers.omnibot.models!) {
      model.reasoning = true;
      model.thinkingLevelMap = { ...deepSeekLevels };
    }
    file.save();
    const loaded = await host.remote.request(methods.agent.session.load, {
      sessionId: session.sessionId, cwd: host.root, mcpServers: [],
    });
    assert.deepEqual(levels(loaded), ["off", "low", "high", "max"]);
    assert.equal(thinking(loaded).currentValue, "off");
    assert.ok(host.updates.some(({ sessionId, update }) => sessionId === session.sessionId && update.sessionUpdate === "config_option_update"));
    assert.deepEqual((await host.remote.request(methods.agent.session.list, {})).sessions.map((item) => item.sessionId), [session.sessionId]);
    await host.remote.request(methods.agent.session.setConfigOption, {
      sessionId: session.sessionId, configId: "thought_level", value: "max",
    });
    await host.prompt(session.sessionId, "use max");
    assert.equal(provider.requests.at(-1).body.reasoning_effort, "max");
    assert.deepEqual(provider.requests.at(-1).body.thinking, { type: "enabled" });
    assert.match(JSON.stringify(provider.requests.at(-1).body.messages), /preserve-history/);
    await host.remote.request(methods.agent.session.setConfigOption, {
      sessionId: session.sessionId, configId: "thought_level", value: "off",
    });
    await host.prompt(session.sessionId, "disable thinking");
    assert.deepEqual(provider.requests.at(-1).body.thinking, { type: "disabled" });
    assert.equal(thinking(await host.newSession()).currentValue, "high");
  } finally { await host.close(); await provider.close(); }
});

test("refreshing another session cannot change an active prompt's model or tool continuation", { timeout: 15000 }, async () => {
  const started = Promise.withResolvers<void>();
  const release = Promise.withResolvers<void>();
  const provider = await httpFixture(async (body, response) => {
    const last = body.messages.at(-1);
    if (last.role === "user" && JSON.stringify(last).includes("hold-a")) {
      started.resolve();
      await release.promise;
      stream(response, { tool: { id: "a-tool", name: "bash", arguments: { command: "printf done" } } });
    } else stream(response, { text: "finished" });
  });
  let file!: ReturnType<typeof catalogFile>;
  const host = await harness(provider.url, { configure(config) { file = catalogFile(config); } });
  try {
    const a = await host.newSession();
    const b = await host.newSession();
    await host.remote.request(methods.agent.session.setConfigOption, { sessionId: a.sessionId, configId: "thought_level", value: "low" });
    const prompt = host.prompt(a.sessionId, "hold-a");
    await started.promise;
    file.document.providers.omnibot.models![0].thinkingLevelMap = { ...deepSeekLevels, low: "high" };
    file.save();
    await assert.rejects(host.remote.request(methods.agent.session.load, {
      sessionId: a.sessionId, cwd: host.root, mcpServers: [],
    }), /active prompt/);
    await host.remote.request(methods.agent.session.load, { sessionId: b.sessionId, cwd: host.root, mcpServers: [] });
    await host.remote.request(methods.agent.session.setConfigOption, { sessionId: b.sessionId, configId: "thought_level", value: "low" });
    await host.prompt(b.sessionId, "b uses the refreshed mapping");
    assert.equal(provider.requests.at(-1).body.reasoning_effort, "high");
    release.resolve();
    await prompt;
    const requestsA = provider.requests.filter(({ body }) => JSON.stringify(body.messages).includes("hold-a"));
    assert.equal(requestsA.length, 2);
    assert.ok(requestsA.every(({ body }) => body.reasoning_effort === "low"));
  } finally { release.resolve(); await host.close(); await provider.close(); }
});

test("foreign catalogs are ignored and refreshed file headers cannot replace process credentials", { timeout: 15000 }, async () => {
  const provider = await httpFixture((_body, response) => stream(response, { text: "answer" }));
  let file!: ReturnType<typeof catalogFile>;
  const host = await harness(provider.url, { configure(config) { file = catalogFile(config); } });
  try {
    const session = await host.newSession();
    const original = structuredClone(file.document);
    const variants = [
      (document: any) => { document._meta.omnibot.providerProfileId = "provider-b"; },
      (document: any) => { document._meta.omnibot.providerRevision++; },
      (document: any) => { document.providers.omnibot.baseUrl = "https://other.invalid/v1"; },
      (document: any) => { document.providers.omnibot.api = "openai-responses"; },
      (document: any) => { delete document._meta; },
    ];
    for (const change of variants) {
      const foreign = structuredClone(original);
      foreign.providers.omnibot.models![0].reasoning = false;
      change(foreign);
      writeFileSync(file.path, JSON.stringify(foreign));
      const loaded = await host.remote.request(methods.agent.session.load, { sessionId: session.sessionId, cwd: host.root, mcpServers: [] });
      assert.deepEqual(levels(loaded), levels(session));
      assert.equal(thinking(loaded).currentValue, "high");
    }
    file.document.providers.omnibot.models![0].thinkingLevelMap = { ...deepSeekLevels };
    file.document.providers.omnibot.headers = { Authorization: "Bearer file-provider-secret" };
    file.document.providers.omnibot.models![0].headers = { "X-Fixture": "file-model-secret" };
    file.document.providers.omnibot.apiKey = "file-key";
    file.save();
    await host.remote.request(methods.agent.session.load, { sessionId: session.sessionId, cwd: host.root, mcpServers: [] });
    await host.prompt(session.sessionId, "check credentials");
    assert.equal(provider.requests.at(-1).headers.authorization, "Bearer test-key");
    assert.equal(provider.requests.at(-1).headers["x-fixture"], "pi-acp");
  } finally { await host.close(); await provider.close(); }
});

test("invalid same-provider catalogs fail without damaging the existing session snapshot", { timeout: 15000 }, async () => {
  const provider = await httpFixture((_body, response) => stream(response, { text: "answer" }));
  let file!: ReturnType<typeof catalogFile>;
  const host = await harness(provider.url, { configure(config) { file = catalogFile(config); } });
  try {
    const session = await host.newSession();
    const original = structuredClone(file.document);
    const missing = structuredClone(original);
    missing.providers.omnibot.models = missing.providers.omnibot.models!.slice(1);
    for (const [content, error] of [
      [JSON.stringify(missing), /unavailable/],
      ["{", /JSON|Expected/],
      [JSON.stringify({ ...original, providers: { omnibot: { ...original.providers.omnibot, models: null } } }), /model catalog/],
    ] as const) {
      writeFileSync(file.path, content);
      await assert.rejects(host.remote.request(methods.agent.session.load, { sessionId: session.sessionId, cwd: host.root, mcpServers: [] }), error);
      await host.prompt(session.sessionId, "original configuration still works");
      assert.equal(provider.requests.at(-1).body.model, "fixture");
      assert.equal(provider.requests.at(-1).body.reasoning_effort, "high");
    }
    file.save();
    const loaded = await host.remote.request(methods.agent.session.load, { sessionId: session.sessionId, cwd: host.root, mcpServers: [] });
    assert.equal(thinking(loaded).currentValue, "high");
  } finally { await host.close(); await provider.close(); }
});

test("Provider defaults initialize new sessions while saved choices survive close and restart", { timeout: 15000 }, async () => {
  const provider = await httpFixture((_body, response) => stream(response, { text: "answer" }));
  let file!: ReturnType<typeof catalogFile>;
  const configure = (config: LaunchConfig) => { file = catalogFile(config); };
  const host = await harness(provider.url, { configure });
  let restarted: Awaited<ReturnType<typeof harness>> | undefined;
  try {
    const session = await host.newSession();
    assert.equal(thinking(session).currentValue, "high");
    await host.prompt(session.sessionId, "persist the session");
    await host.remote.request(methods.agent.session.setConfigOption, { sessionId: session.sessionId, configId: "thought_level", value: "low" });
    await host.close(false);
    restarted = await harness(provider.url, { root: host.root, configure });
    const restored = await restarted.remote.request(methods.agent.session.load, { sessionId: session.sessionId, cwd: restarted.root, mcpServers: [] });
    assert.equal(thinking(restored).currentValue, "low");
    assert.equal(thinking(await restarted.newSession()).currentValue, "high");
  } finally {
    if (restarted) await restarted.close(); else await host.close();
    await provider.close();
  }
});

test("an empty session's official thinking entry takes precedence over a new Provider default", async () => {
  const root = mkdtempSync(join(tmpdir(), "pi-empty-default-"));
  const config = configFor(root, "http://127.0.0.1:1");
  config.modelThinkingDefaults = { fixture: "high" };
  const manager = SessionManager.inMemory(root);
  manager.appendThinkingLevelChange("low");
  const client = { notify: async () => {} } as unknown as AgentContext;
  const session = await PiAcpSession.create(manager, config, client, []);
  try { assert.equal(thinking({ configOptions: session.configOptions() }).currentValue, "low"); }
  finally { await session.close(); rmSync(root, { recursive: true, force: true }); }
});

test("legacy launch configuration without catalog identity remains a fixed snapshot", async () => {
  const root = mkdtempSync(join(tmpdir(), "pi-legacy-catalog-"));
  const config = configFor(root, "http://127.0.0.1:1");
  const file = catalogFile(config, (document) => { delete document._meta; });
  const client = { notify: async () => {} } as unknown as AgentContext;
  const session = await PiAcpSession.create(SessionManager.inMemory(root), config, client, []);
  try {
    writeFileSync(file.path, "this replacement must not be read");
    await session.refreshCatalog();
    assert.deepEqual(levels({ configOptions: session.configOptions() }), ["off", "minimal", "low", "medium", "high"]);
  } finally { await session.close(); rmSync(root, { recursive: true, force: true }); }
});

test("a registry failure rolls back the catalog before another session operation is admitted", async () => {
  const root = mkdtempSync(join(tmpdir(), "pi-catalog-rollback-"));
  const config = configFor(root, "http://127.0.0.1:1");
  const file = catalogFile(config);
  const client = { notify: async () => {} } as unknown as AgentContext;
  const session = await PiAcpSession.create(SessionManager.inMemory(root), config, client, []);
  const originalRegister = ModelRuntime.prototype.registerProvider;
  try {
    file.document.providers.omnibot.models![0].name = "fail-after-registration";
    file.save();
    ModelRuntime.prototype.registerProvider = function (this: ModelRuntime, id, registration) {
      originalRegister.call(this, id, registration);
      if (registration.models?.some((model) => model.name === "fail-after-registration")) {
        throw new Error("fixture registry registration failed");
      }
    };
    await assert.rejects(session.refreshCatalog(), /registry registration failed/);
    const modelOption = session.configOptions().find((option) => option.id === "model") as any;
    assert.equal(modelOption.options[0].name, "fixture");
    assert.equal(thinking({ configOptions: session.configOptions() }).currentValue, "high");
    ModelRuntime.prototype.registerProvider = originalRegister;
    await session.refreshCatalog();
    const updated = session.configOptions().find((option) => option.id === "model") as any;
    assert.equal(updated.options[0].name, "fail-after-registration");
  } finally {
    ModelRuntime.prototype.registerProvider = originalRegister;
    await session.close();
    rmSync(root, { recursive: true, force: true });
  }
});
