import { test } from "node:test";
import assert from "node:assert/strict";
import { setTimeout as delay } from "node:timers/promises";
import { methods } from "@agentclientprotocol/sdk/unstable";
import { harness, httpFixture, stream } from "./fixtures.js";

test("agent_end during native compaction recovery does not complete the ACP prompt", { timeout: 20000 }, async () => {
  let requests = 0;
  const provider = await httpFixture((_body, response) => {
    requests++;
    if (requests === 2) {
      response.writeHead(400, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ error: { message: "maximum context length exceeded", code: "context_length_exceeded" } }));
      return;
    }
    stream(response, { text: requests === 1 ? "History item. ".repeat(16000)
      : requests === 3 ? "Summary of prior work." : "Recovered after compaction" });
  });
  const host = await harness(provider.url);
  try {
    const session = await host.newSession();
    await host.prompt(session.sessionId, "Build initial context");
    const result = await host.prompt(session.sessionId, "Continue after context overflow");
    assert.equal(result.stopReason, "end_turn");
    assert.equal(requests, 4); // Initial run, overflow, Pi summary, Pi continuation.
    assert.ok(host.updates.some(({ update }) => update.sessionUpdate === "agent_message_chunk" &&
      update.content.type === "text" && update.content.text === "Recovered after compaction"));
  } finally { await host.close(); await provider.close(); }
});

test("model selection and image/resource attachments reach the selected Provider", { timeout: 15000 }, async () => {
  const provider = await httpFixture((_body, response) => stream(response, { text: "image received" }));
  const host = await harness(provider.url);
  try {
    const session = await host.newSession();
    await host.remote.request(methods.agent.session.setConfigOption, { sessionId: session.sessionId, configId: "model", value: "fixture-two" });
    await assert.rejects(host.remote.request(methods.agent.session.setConfigOption, { sessionId: session.sessionId, configId: "model", value: "not-configured" }));
    const result = await host.remote.request(methods.agent.session.prompt, { sessionId: session.sessionId, prompt: [
      { type: "text", text: "Describe this" },
      { type: "image", mimeType: "image/png", data: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==" },
      { type: "resource", resource: { uri: "file:///context.txt", text: "embedded-context", mimeType: "text/plain" } },
      { type: "resource_link", uri: "file:///workspace/report.pdf", name: "report.pdf" },
    ] });
    assert.equal(result.stopReason, "end_turn");
    const request = provider.requests.at(-1).body;
    assert.equal(request.model, "fixture-two");
    const userContent = JSON.stringify(request.messages.filter((message: any) => message.role === "user"));
    assert.match(userContent, /image_url/);
    assert.match(userContent, /embedded-context/);
    assert.match(userContent, /report.pdf/);
    await host.remote.request(methods.agent.session.close, { sessionId: session.sessionId });
    const loaded = await host.remote.request(methods.agent.session.load, { sessionId: session.sessionId, cwd: host.root, mcpServers: [] });
    assert.equal(loaded.configOptions?.find((option) => option.id === "model")?.currentValue, "fixture-two");
  } finally { await host.close(); await provider.close(); }
});

test("a broken output stream preserves its visible text and returns one terminal error", { timeout: 15000 }, async () => {
  const provider = await httpFixture(async (_body, response) => {
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    response.write(`data: ${JSON.stringify({ id: "broken", choices: [{ index: 0, delta: { role: "assistant", content: "visible-prefix" }, finish_reason: null }] })}\n\n`);
    await delay(30);
    response.destroy();
  });
  const host = await harness(provider.url);
  try {
    const session = await host.newSession();
    await assert.rejects(host.prompt(session.sessionId, "fail after output"));
    assert.equal(provider.requests.length, 1);
    const text = host.updates.filter(({ update }) => update.sessionUpdate === "agent_message_chunk");
    assert.match(JSON.stringify(text), /visible-prefix/);
    assert.equal(text.length, 1);
    const before = host.updates.length;
    await delay(50);
    assert.equal(host.updates.length, before);
  } finally { await host.close(); await provider.close(); }
});

test("cancel during output preserves the session and does not replay the prompt", { timeout: 15000 }, async () => {
  const visible = Promise.withResolvers<void>();
  const provider = await httpFixture((body, response) => {
    if (JSON.stringify(body.messages.at(-1)).includes("next")) { stream(response, { text: "next answer" }); return; }
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    response.write(`data: ${JSON.stringify({ id: "long", choices: [{ index: 0, delta: { role: "assistant", content: "partial" }, finish_reason: null }] })}\n\n`);
  });
  const host = await harness(provider.url, { onUpdate: ({ update }) => {
    if (update.sessionUpdate === "agent_message_chunk") visible.resolve();
  } });
  try {
    const session = await host.newSession();
    const prompt = host.prompt(session.sessionId, "keep streaming");
    await visible.promise;
    await host.remote.notify(methods.agent.session.cancel, { sessionId: session.sessionId });
    assert.equal((await prompt).stopReason, "cancelled");
    assert.equal(provider.requests.length, 1);
    assert.equal((await host.prompt(session.sessionId, "next")).stopReason, "end_turn");
    assert.equal(provider.requests.length, 2);
  } finally { await host.close(); await provider.close(); }
});

test("two simultaneous ACP sessions never exchange updates or model context", { timeout: 15000 }, async () => {
  const provider = await httpFixture(async (body, response) => {
    const text = JSON.stringify(body.messages.at(-1));
    await delay(text.includes("slow") ? 60 : 10);
    stream(response, { text: text.includes("slow") ? "slow-response" : "fast-response" });
  });
  const host = await harness(provider.url);
  try {
    const a = await host.newSession();
    const b = await host.newSession();
    await Promise.all([host.prompt(a.sessionId, "slow"), host.prompt(b.sessionId, "fast")]);
    const forSession = (id: string) => JSON.stringify(host.updates.filter(({ sessionId }) => sessionId === id));
    assert.match(forSession(a.sessionId), /slow-response/);
    assert.doesNotMatch(forSession(a.sessionId), /fast-response/);
    assert.match(forSession(b.sessionId), /fast-response/);
    assert.doesNotMatch(forSession(b.sessionId), /slow-response/);
  } finally { await host.close(); await provider.close(); }
});

test("Anthropic messages use the configured endpoint and credentials", { timeout: 15000 }, async () => {
  const provider = await httpFixture((_body, response) => {
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    const send = (type: string, fields = {}) => response.write(`event: ${type}\ndata: ${JSON.stringify({ type, ...fields })}\n\n`);
    send("message_start", { message: { id: "anthropic-test", type: "message", role: "assistant", content: [], model: "fixture", stop_reason: null, usage: { input_tokens: 32, output_tokens: 0 } } });
    send("content_block_start", { index: 0, content_block: { type: "text", text: "" } });
    send("content_block_delta", { index: 0, delta: { type: "text_delta", text: "Anthropic answer" } });
    send("content_block_stop", { index: 0 });
    send("message_delta", { delta: { stop_reason: "end_turn" }, usage: { output_tokens: 8 } });
    send("message_stop");
    response.end();
  });
  const host = await harness(provider.url, { configure: (config) => {
    config.provider.api = "anthropic-messages";
    config.provider.baseUrl = provider.url;
    config.provider.models!.forEach((model) => { model.reasoning = false; });
  } });
  try {
    const session = await host.newSession();
    assert.equal((await host.prompt(session.sessionId, "anthropic")).stopReason, "end_turn");
    assert.equal(provider.requests[0].url.split("?")[0], "/v1/messages");
    assert.equal(provider.requests[0].headers["x-api-key"], "test-key");
    assert.match(JSON.stringify(host.updates), /Anthropic answer/);
  } finally { await host.close(); await provider.close(); }
});

test("Responses Provider returns standard text and usage through ACP", { timeout: 15000 }, async () => {
  const provider = await httpFixture((_body, response) => {
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    let sequence = 0;
    const send = (type: string, fields = {}) => response.write(`event: ${type}\ndata: ${JSON.stringify({ type, sequence_number: sequence++, ...fields })}\n\n`);
    const message = { id: "message", type: "message", role: "assistant", status: "in_progress", content: [] };
    send("response.created", { response: { id: "response", status: "in_progress", output: [] } });
    send("response.output_item.added", { output_index: 0, item: message });
    send("response.content_part.added", { item_id: "message", output_index: 0, content_index: 0, part: { type: "output_text", text: "", annotations: [] } });
    send("response.output_text.delta", { item_id: "message", output_index: 0, content_index: 0, delta: "Responses answer" });
    const part = { type: "output_text", text: "Responses answer", annotations: [] };
    send("response.output_text.done", { item_id: "message", output_index: 0, content_index: 0, text: part.text });
    send("response.content_part.done", { item_id: "message", output_index: 0, content_index: 0, part });
    const item = { ...message, status: "completed", content: [part] };
    send("response.output_item.done", { output_index: 0, item });
    send("response.completed", { response: { id: "response", status: "completed", output: [item], usage: { input_tokens: 32, output_tokens: 8, total_tokens: 40, input_tokens_details: { cached_tokens: 0 }, output_tokens_details: { reasoning_tokens: 0 } } } });
    response.end();
  });
  const host = await harness(provider.url, { configure: (config) => {
    config.provider.api = "openai-responses";
    config.provider.models!.forEach((model) => { model.reasoning = false; });
  } });
  try {
    const session = await host.newSession();
    const result = await host.prompt(session.sessionId, "responses");
    assert.equal(result.stopReason, "end_turn");
    assert.equal(provider.requests[0].url, "/v1/responses");
    assert.equal(provider.requests[0].headers.authorization, "Bearer test-key");
    assert.equal(result.usage?.outputTokens, 8);
    assert.match(JSON.stringify(host.updates), /Responses answer/);
  } finally { await host.close(); await provider.close(); }
});
