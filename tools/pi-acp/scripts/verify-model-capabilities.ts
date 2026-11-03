// Consume native PiConfigAdapter output through the real ACP and Pi SDK stack.
// Usage: node --import tsx scripts/verify-model-capabilities.ts [fixture-directory]
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { client, methods, type SessionConfigOption } from "@agentclientprotocol/sdk/unstable";
import { readLaunchConfig } from "../src/config.js";
import { createPiAgent } from "../src/server.js";

interface Fixture {
  name: string;
  document: any;
  modelId: string;
  expectedLevels: string[];
  expectedDefault: string;
  expectedWire: Record<string, Record<string, unknown>>;
  expectedAbsent?: Record<string, string[]>;
}

function assertSubset(actual: unknown, expected: unknown, path: string): void {
  if (typeof expected !== "object" || expected === null || Array.isArray(expected)) {
    assert.deepEqual(actual, expected, path);
    return;
  }
  assert.ok(typeof actual === "object" && actual !== null, `${path} must be an object`);
  for (const [key, value] of Object.entries(expected)) {
    assert.ok(Object.hasOwn(actual, key), `${path}.${key} is missing`);
    assertSubset((actual as Record<string, unknown>)[key], value, `${path}.${key}`);
  }
}

function assertAbsent(actual: unknown, path: string): void {
  let value = actual;
  for (const key of path.split(".")) {
    if (typeof value !== "object" || value === null || !Object.hasOwn(value, key)) return;
    value = (value as Record<string, unknown>)[key];
  }
  assert.fail(`Request must omit ${path}`);
}

function responseStream(api: string, modelId: string, sequence: number): Response {
  const id = `fixture-${sequence}`;
  const output = "Capability fixture completed.";
  const records: string[] = [];
  let eventSequence = 0;
  const send = (type: string, fields: Record<string, unknown> = {}) => {
    records.push(`event: ${type}\ndata: ${JSON.stringify({ type, sequence_number: eventSequence++, ...fields })}\n\n`);
  };
  if (api === "openai-completions") {
    for (const [delta, finish] of [[{ role: "assistant", content: output }, null], [{}, "stop"]]) {
      records.push(`data: ${JSON.stringify({ id, object: "chat.completion.chunk", model: modelId, created: 1,
        choices: [{ index: 0, delta, finish_reason: finish }],
      })}\n\n`);
    }
    records.push(`data: ${JSON.stringify({ id, choices: [], usage: { prompt_tokens: 32, completion_tokens: 8, total_tokens: 40 } })}\n\n`);
    records.push("data: [DONE]\n\n");
  } else if (api === "openai-responses") {
    const message = { id: `${id}-message`, type: "message", role: "assistant", status: "in_progress", content: [] };
    const itemFields = { item_id: message.id, output_index: 0, content_index: 0 };
    send("response.created", { response: { id, status: "in_progress", output: [] } });
    send("response.output_item.added", { output_index: 0, item: message });
    send("response.content_part.added", { ...itemFields, part: { type: "output_text", text: "", annotations: [] } });
    send("response.output_text.delta", { ...itemFields, delta: output });
    const part = { type: "output_text", text: output, annotations: [] };
    send("response.output_text.done", { ...itemFields, text: output });
    send("response.content_part.done", { ...itemFields, part });
    const item = { ...message, status: "completed", content: [part] };
    send("response.output_item.done", { output_index: 0, item });
    send("response.completed", { response: { id, status: "completed", output: [item],
      usage: { input_tokens: 32, output_tokens: 8, total_tokens: 40,
        input_tokens_details: { cached_tokens: 0 }, output_tokens_details: { reasoning_tokens: 0 } },
    } });
  } else if (api === "anthropic-messages") {
    send("message_start", { message: { id, type: "message", role: "assistant", content: [], model: modelId,
      stop_reason: null, usage: { input_tokens: 32, output_tokens: 0 } } });
    send("content_block_start", { index: 0, content_block: { type: "text", text: "" } });
    send("content_block_delta", { index: 0, delta: { type: "text_delta", text: output } });
    send("content_block_stop", { index: 0 });
    send("message_delta", { delta: { stop_reason: "end_turn" }, usage: { output_tokens: 8 } });
    send("message_stop");
  } else throw new Error(`Unsupported fixture API: ${api}`);
  return new Response(records.join(""), { headers: { "content-type": "text/event-stream" } });
}

function thoughtOption(options: SessionConfigOption[] | null | undefined) {
  const option = options?.find((candidate) => candidate.category === "thought_level");
  assert.ok(option?.type === "select" && Array.isArray(option.options), "ACP must expose a thought-level selector");
  return option;
}

async function verify(fixture: Fixture): Promise<number> {
  const root = mkdtempSync(join(tmpdir(), "pi-native-capabilities-"));
  const previousFetch = globalThis.fetch;
  let runtime: ReturnType<typeof createPiAgent> | undefined;
  let connection: ReturnType<ReturnType<typeof client>["connect"]> | undefined;
  const requests: any[] = [];
  const attempts: string[] = [];
  try {
    const provider = fixture.document.providers.omnibot;
    const suffix = { "openai-completions": "chat/completions", "openai-responses": "responses", "anthropic-messages": "v1/messages" }[provider.api as string];
    assert.ok(suffix, `${fixture.name}: fixture must use an implemented API`);
    const expectedUrl = new URL(`${provider.baseUrl.replace(/\/+$/, "")}/${suffix}`);
    // Preserve the real endpoint for SDK compatibility detection. No request is
    // forwarded: every fetch is intercepted, including unexpected requests.
    globalThis.fetch = async (input, init) => {
      const request = new Request(input, init);
      const url = new URL(request.url);
      attempts.push(request.url);
      assert.equal(url.origin, expectedUrl.origin, "Unexpected network destination");
      assert.equal(url.pathname, expectedUrl.pathname, "Unexpected API route");
      assert.equal(request.method, "POST");
      const body = await request.json() as Record<string, unknown>;
      assert.equal(body.model, fixture.modelId);
      requests.push(body);
      return responseStream(provider.api, fixture.modelId, requests.length);
    };
    const path = join(root, "models.json");
    writeFileSync(path, JSON.stringify(fixture.document));
    const metadata = fixture.document._meta?.omnibot;
    const config = readLaunchConfig({
      PI_CODING_AGENT_DIR: root, OMNIBOT_PI_MODELS_PATH: path,
      OMNIBOT_PI_MODEL: fixture.modelId, OMNIBOT_PI_API_KEY: "capability-fixture-key", OMNIBOT_PI_HEADERS: "{}",
      ...(metadata ? {
        OMNIBOT_PI_PROVIDER_ID: metadata.providerProfileId,
        OMNIBOT_PI_PROVIDER_REVISION: String(metadata.providerRevision),
        OMNIBOT_PI_PROVIDER_API: provider.api, OMNIBOT_PI_PROVIDER_BASE_URL: provider.baseUrl,
      } : {}),
    });
    runtime = createPiAgent(config);
    const app = client({ name: "native-capability-fixture" })
      .onNotification(methods.client.session.update, () => {})
      .onRequest(methods.client.session.requestPermission, () => {
        throw new Error("Capability fixtures must not execute tools.");
      });
    connection = app.connect(runtime.app);
    const remote = connection.agent;
    await remote.request(methods.agent.initialize, { protocolVersion: 1, clientCapabilities: {} });
    const session = await remote.request(methods.agent.session.new, { cwd: root, mcpServers: [] });
    const thought = thoughtOption(session.configOptions);
    assert.deepEqual(thought.options.map((option: any) => option.value), fixture.expectedLevels, `${fixture.name}: ACP levels`);
    assert.equal(thought.currentValue, fixture.expectedDefault, `${fixture.name}: ACP default`);

    async function promptAndCheck(level: string, label: string) {
      const count = requests.length;
      const result = await remote.request(methods.agent.session.prompt, {
        sessionId: session.sessionId, prompt: [{ type: "text", text: `Verify ${label}. Return the fixture answer.` }],
      });
      assert.equal(result.stopReason, "end_turn", `${fixture.name}: ${label} completion`);
      assert.equal(requests.length, count + 1, `${fixture.name}: ${label} must issue exactly one Provider request`);
      assert.ok(Object.hasOwn(fixture.expectedWire, level), `${fixture.name}: missing expectedWire.${level}`);
      assertSubset(requests.at(-1), fixture.expectedWire[level], `${fixture.name}.${label}`);
      for (const path of fixture.expectedAbsent?.[level] ?? []) assertAbsent(requests.at(-1), path);
    }

    await promptAndCheck(fixture.expectedDefault, "initial default");
    for (const level of fixture.expectedLevels) {
      const updated = await remote.request(methods.agent.session.setConfigOption, {
        sessionId: session.sessionId, configId: thought.id, value: level,
      });
      assert.equal(thoughtOption(updated.configOptions).currentValue, level, `${fixture.name}: selected ${level}`);
      await promptAndCheck(level, level);
    }
    assert.equal(attempts.length, requests.length, "Every network attempt must be intercepted and validated");
    process.stdout.write(`${JSON.stringify({ name: fixture.name, api: provider.api, modelId: fixture.modelId,
      levels: fixture.expectedLevels, default: fixture.expectedDefault, requests: requests.length, network: "intercepted" })}\n`);
    return requests.length;
  } finally {
    try { await runtime?.close(); }
    finally {
      connection?.close();
      globalThis.fetch = previousFetch;
      rmSync(root, { recursive: true, force: true });
    }
  }
}

const directory = resolve(process.argv[2] ?? fileURLToPath(new URL("../../../app/build/reports/pi-model-capabilities/", import.meta.url)));
const files = readdirSync(directory).filter((name) => name.endsWith(".json")).sort();
assert.ok(files.length > 0, `No native capability fixtures in ${directory}; run the native PiConfigAdapter fixture test first.`);
let requests = 0;
for (const file of files) requests += await verify(JSON.parse(readFileSync(join(directory, file), "utf8")) as Fixture);
process.stdout.write(`${JSON.stringify({ fixtures: files.length, requests, externalRequests: 0, result: "passed" })}\n`);
