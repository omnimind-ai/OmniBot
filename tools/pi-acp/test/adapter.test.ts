import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { methods, type McpServer } from "@agentclientprotocol/sdk/unstable";
import { harness, httpFixture, stream } from "./fixtures.js";
import { piLiteral } from "../src/config.js";
import { sessionMcpConfig } from "../src/mcp.js";

test("ACP initialize, two turns, isolated conversations and exact history replay after restart", { timeout: 20000 }, async () => {
  const provider = await httpFixture((_body, response) => stream(response, { text: "Answer \u2028 with unicode", thinking: "Reasoning" }));
  const host = await harness(provider.url);
  let resumed: Awaited<ReturnType<typeof harness>> | undefined;
  try {
    assert.equal(host.initialize.protocolVersion, 1);
    assert.equal(host.initialize.agentCapabilities?.mcpCapabilities?.http, true);
    assert.equal(host.initialize.agentCapabilities?.mcpCapabilities?.sse, false);
    const a = await host.newSession();
    const b = await host.newSession();
    assert.notEqual(a.sessionId, b.sessionId);
    assert.equal((await host.prompt(a.sessionId, "first-a")).stopReason, "end_turn");
    await host.prompt(b.sessionId, "first-b");
    const outcome = await host.prompt(a.sessionId, "second-a");
    assert.equal(outcome.stopReason, "end_turn");
    assert.equal(outcome.usage?.outputTokens, 8);
    const last = provider.requests.at(-1).body;
    assert.match(JSON.stringify(last.messages), /first-a/);
    assert.doesNotMatch(JSON.stringify(last.messages), /first-b/);
    assert.equal(provider.requests[0].headers.authorization, "Bearer test-key");
    assert.equal(provider.requests[0].headers["x-fixture"], "pi-acp");
    const live = host.updates.filter((event) => event.sessionId === a.sessionId && ["user_message_chunk", "agent_message_chunk", "agent_thought_chunk"].includes(event.update.sessionUpdate));
    assert.ok(live.length >= 6);
    assert.ok(live.every((event) => "messageId" in event.update && event.update.messageId));
    const listed = await host.remote.request(methods.agent.session.list, { cwd: host.root });
    assert.equal(listed.sessions.length, 2);
    await host.close(false);
    resumed = await harness(provider.url, { root: host.root });
    await resumed.remote.request(methods.agent.session.load, { sessionId: a.sessionId, cwd: host.root, mcpServers: [] });
    const replay = resumed.updates.filter((event) => ["user_message_chunk", "agent_message_chunk", "agent_thought_chunk"].includes(event.update.sessionUpdate));
    assert.deepEqual(replay, live);
    await resumed.prompt(a.sessionId, "third-a");
    assert.match(JSON.stringify(provider.requests.at(-1).body.messages), /second-a/);
    await resumed.remote.request(methods.agent.session.close, { sessionId: a.sessionId });
    await assert.rejects(resumed.prompt(a.sessionId, "closed"));
    await resumed.remote.request(methods.agent.session.load, { sessionId: a.sessionId, cwd: host.root, mcpServers: [] });
    await resumed.prompt(a.sessionId, "after-close");
  } finally {
    if (resumed) await resumed.close(); else await host.close();
    await provider.close();
  }
});

test("Pi native file and bash tools use the ACP permission owner", { timeout: 20000 }, async () => {
  const provider = await httpFixture((body, response) => {
    if (body.messages.at(-1).role === "tool") stream(response, { text: "Tool completed" });
    else {
      const prompt = JSON.stringify(body.messages.at(-1));
      const tool = prompt.includes("read file") ? { name: "read", arguments: { path: "result.txt" } }
        : prompt.includes("run bash") ? { name: "bash", arguments: { command: "printf 'pi-shell-output'" } }
        : { name: "write", arguments: { path: "result.txt", content: "written by Pi" } };
      stream(response, { tool: { id: `call-${body.messages.length}`, ...tool } });
    }
  });
  const host = await harness(provider.url);
  try {
    const session = await host.newSession();
    await host.prompt(session.sessionId, "write the file");
    assert.ok(existsSync(join(host.root, "result.txt")));
    assert.equal(host.permissions(), 1);
    const tools = host.updates.map((event) => event.update).filter((update) => update.sessionUpdate === "tool_call" || update.sessionUpdate === "tool_call_update");
    assert.equal(tools[0].status, "in_progress");
    assert.equal(tools.at(-1)!.status, "completed");
    assert.ok(tools.every((tool) => tool.toolCallId === tools[0].toolCallId));
    await host.prompt(session.sessionId, "read file");
    assert.match(JSON.stringify(host.updates), /written by Pi/);
    await host.prompt(session.sessionId, "run bash");
    assert.match(JSON.stringify(host.updates), /pi-shell-output/);
    assert.equal(host.permissions(), 3);
  } finally { await host.close(); await provider.close(); }

  const deniedProvider = await httpFixture((body, response) => {
    stream(response, body.messages.at(-1).role === "tool" ? { text: "Denied" } : { tool: { id: "denied", name: "write", arguments: { path: "denied.txt", content: "must not write" } } });
  });
  const denied = await harness(deniedProvider.url, { permission: async () => ({ outcome: { outcome: "selected", optionId: "reject_once" } }) });
  try {
    const session = await denied.newSession();
    await denied.prompt(session.sessionId, "try writing");
    assert.equal(existsSync(join(denied.root, "denied.txt")), false);
    assert.ok(denied.updates.some(({ update }) => update.sessionUpdate === "tool_call_update" && update.status === "failed"));
  } finally { await denied.close(); await deniedProvider.close(); }
});

test("cancel unblocks an unanswered permission and admits the next prompt", { timeout: 15000 }, async () => {
  const permissionStarted = Promise.withResolvers<void>();
  const permissionResponse = Promise.withResolvers<any>();
  const provider = await httpFixture((body, response) => {
    const text = JSON.stringify(body.messages.at(-1));
    stream(response, text.includes("next prompt") ? { text: "Recovered" } : { tool: { id: "waiting", name: "bash", arguments: { command: "echo should-not-execute" } } });
  });
  const host = await harness(provider.url, { permission: () => { permissionStarted.resolve(); return permissionResponse.promise; } });
  try {
    const session = await host.newSession();
    const prompt = host.prompt(session.sessionId, "wait for permission");
    await permissionStarted.promise;
    await assert.rejects(host.prompt(session.sessionId, "duplicate"), /already owns/);
    await host.remote.notify(methods.agent.session.cancel, { sessionId: session.sessionId });
    const result = await prompt;
    assert.equal(result.stopReason, "cancelled");
    permissionResponse.resolve({ outcome: { outcome: "cancelled" } });
    assert.equal((await host.prompt(session.sessionId, "next prompt")).stopReason, "end_turn");
  } finally {
    permissionResponse.resolve({ outcome: { outcome: "cancelled" } });
    await host.close(); await provider.close();
  }
});

test("Provider failures return ACP errors without retrying the logical turn", { timeout: 15000 }, async () => {
  const provider = await httpFixture((_body, response) => {
    response.writeHead(503, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ error: { message: "fixture-unavailable" } }));
  });
  const host = await harness(provider.url);
  try {
    const session = await host.newSession();
    await assert.rejects(host.prompt(session.sessionId, "fail"), /fixture-unavailable|503/);
    assert.equal(provider.requests.length, 1);
    assert.ok(host.updates.some(({ update }) => update.sessionUpdate === "user_message_chunk"));
    const saved = await host.remote.request(methods.agent.session.list, {});
    assert.equal(saved.sessions.length, 1);
  } finally { await host.close(); await provider.close(); }
});

test("native MCP connects with session headers, exposes a direct tool and returns its content", { timeout: 20000 }, async () => {
  const mcp = await httpFixture((body, response) => {
    if (!body.id) { response.writeHead(202); response.end(); return; }
    let result: unknown;
    if (body.method === "initialize") result = { protocolVersion: body.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: "phone", version: "1" } };
    else if (body.method === "tools/list") result = { tools: [{ name: "ping", description: "Ping the phone", inputSchema: { type: "object", properties: {} } }] };
    else if (body.method === "tools/call") result = { content: [{ type: "text", text: "phone-result" }] };
    else result = {};
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ jsonrpc: "2.0", id: body.id, result }));
  });
  const provider = await httpFixture((body, response) => {
    if (body.messages.at(-1).role === "tool") stream(response, { text: "Phone completed" });
    else stream(response, { tool: { id: "phone-call", name: "mcp__phone__ping", arguments: {} } });
  });
  const host = await harness(provider.url);
  try {
    const session = await host.newSession([{ type: "http", name: "phone", url: `${mcp.url}/mcp`, headers: [{ name: "Authorization", value: "Bearer session-token" }] }]);
    await host.prompt(session.sessionId, "use the phone");
    assert.ok(provider.requests[0].body.tools.some((tool: any) => tool.function?.name === "mcp__phone__ping"));
    assert.ok(mcp.requests.some(({ body }) => body.method === "tools/call"));
    assert.ok(mcp.requests.every(({ headers }) => headers.authorization === "Bearer session-token"));
    assert.match(JSON.stringify(host.updates), /phone-result/);
    assert.equal(host.permissions(), 1);
  } finally { await host.close(); await provider.close(); await mcp.close(); }
});

test("MCP values are literal, names are isolated and SSE is rejected", () => {
  assert.equal(piLiteral("Bearer $TOKEN"), "Bearer $$TOKEN");
  assert.equal(piLiteral("!command"), "$!command");
  const servers: McpServer[] = [
    { type: "http", name: "a-b", url: "http://localhost/mcp", headers: [{ name: "X", value: "${TOKEN}" }] },
    { type: "http", name: "a_b", url: "http://localhost/mcp", headers: [] },
  ];
  const config = sessionMcpConfig(servers, "/workspace");
  assert.notEqual(config.servers[0].name.replaceAll("-", "_"), config.servers[1].name.replaceAll("-", "_"));
  assert.deepEqual(sessionMcpConfig(servers.toReversed(), "/workspace").servers.map((server) => server.name),
    config.servers.map((server) => server.name).toReversed());
  assert.equal((config.servers[0].config as any).headers.X, "$${TOKEN}");
  assert.throws(() => sessionMcpConfig([{ type: "sse", name: "old", url: "http://localhost/sse", headers: [] }], "/workspace"), /SSE/);
});
