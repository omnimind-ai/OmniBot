// Runs the real Pi SDK in the installed Android PRoot against local fixtures.
// Usage: node --import tsx scripts/verify-device.mjs emulator-N alpine|ubuntu
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { once } from "node:events";
import { Readable, Writable } from "node:stream";
import { client, methods, ndJsonStream } from "@agentclientprotocol/sdk/unstable";
import { configFor, httpFixture, stream } from "../test/fixtures.ts";
import { deviceProcess } from "./device-process.mjs";

const [serial, distribution] = process.argv.slice(2);
assert(/^emulator-\d+$/.test(serial || "") && ["alpine", "ubuntu"].includes(distribution));
const adb = (...args) => execFileSync(process.env.ADB || "adb", ["-s", serial, ...args], { encoding: "utf8", timeout: 30000 });
const quote = value => `'${value.replaceAll("'", "'\\''")}'`;
const home = `/tmp/omnibot-pi-acceptance-${randomUUID()}`;
const ports = [];
const processes = new Set();
const deadline = setTimeout(() => {
  for (const child of processes) child.kill();
  console.error("Android Pi verification exceeded its observation deadline.");
  process.exit(1);
}, 120000);

async function shell(command, input = "") {
  const child = deviceProcess(serial, distribution, command);
  processes.add(child);
  let output = "";
  child.stdout.on("data", data => { output += data; });
  let error = "";
  child.stderr.on("data", data => { error += data; });
  const exited = once(child, "exit");
  child.stdin.end(input);
  const [code] = await exited;
  processes.delete(child);
  assert.equal(code, 0, error);
  return output;
}

const provider = await httpFixture((body, response) => {
  if (body.messages.at(-1).role === "tool") { stream(response, { text: "device-tool-completed" }); return; }
  const text = JSON.stringify(body.messages.findLast(message => message.role === "user"));
  if (text.includes("cancel-fixture")) {
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    response.write(`data: ${JSON.stringify({ id: "cancel", choices: [{ index: 0, delta: { role: "assistant", content: "device-partial" }, finish_reason: null }] })}\n\n`);
    return;
  }
  const tool = text.includes("write-fixture") ? { name: "write", arguments: { path: "proof.txt", content: "device-file-proof" } }
    : text.includes("read-fixture") ? { name: "read", arguments: { path: "proof.txt" } }
    : text.includes("long-shell-fixture") ? { name: "bash", arguments: { command: "sleep 30 & pi_test_sleep_pid=$!; printf '%s' \"$pi_test_sleep_pid\" > sleep.pid; printf 'device-shell-running\\n'; wait \"$pi_test_sleep_pid\"" } }
    : text.includes("shell-fixture") ? { name: "bash", arguments: { command: "printf 'device-shell-proof'" } }
    : text.includes("mcp-fixture") ? { name: "mcp__fixture__ping", arguments: {} } : undefined;
  stream(response, tool ? { tool: { id: randomUUID(), ...tool } } : { text: "device-answer" });
});
const mcp = await httpFixture((body, response) => {
  if (body.id === undefined) { response.writeHead(202); response.end(); return; }
  const result = body.method === "initialize" ? { protocolVersion: body.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: "fixture", version: "1" } }
    : body.method === "tools/list" ? { tools: [{ name: "ping", description: "Device MCP fixture", inputSchema: { type: "object", properties: {} } }] }
    : body.method === "tools/call" ? { content: [{ type: "text", text: "device-mcp-proof" }] } : {};
  response.writeHead(200, { "Content-Type": "application/json" });
  response.end(JSON.stringify({ jsonrpc: "2.0", id: body.id, result }));
});
const updates = [];
let partial = Promise.withResolvers();
const shellStarted = Promise.withResolvers();
async function start() {
  const child = deviceProcess(serial, distribution,
    `export PI_CODING_AGENT_DIR=${quote(home)} OMNIBOT_PI_MODELS_PATH=${quote(`${home}/models.json`)} OMNIBOT_PI_MODEL=fixture OMNIBOT_PI_API_KEY=test-key OMNIBOT_PI_HEADERS='{}'; exec omnibot-pi-acp`);
  processes.add(child);
  let diagnostics = "";
  child.stderr.on("data", data => { diagnostics += data; });
  const exited = once(child, "exit");
  const connection = client()
    .onNotification(methods.client.session.update, ({ params }) => {
      updates.push(params);
      if (params.update.sessionUpdate === "agent_message_chunk" && params.update.content.text === "device-partial") partial.resolve();
      if (params.update.sessionUpdate === "tool_call_update" && (JSON.stringify(params.update.content) || "").includes("device-shell-running")) shellStarted.resolve();
    })
    .onRequest(methods.client.session.requestPermission, () => ({ outcome: { outcome: "selected", optionId: "allow_once" } }))
    .connect(ndJsonStream(Writable.toWeb(child.stdin), Readable.toWeb(child.stdout)));
  const initialized = await connection.agent.request(methods.agent.initialize, { protocolVersion: 1, clientCapabilities: {} });
  assert.equal(initialized.protocolVersion, 1);
  return {
    remote: connection.agent,
    async close() {
      child.stdin.end();
      const [code] = await exited;
      processes.delete(child);
      connection.close();
      assert.equal(code, 0, diagnostics);
    },
  };
}

let running;
try {
  for (const fixture of [provider, mcp]) {
    const port = new URL(fixture.url).port;
    adb("reverse", `tcp:${port}`, `tcp:${port}`);
    ports.push(port);
  }
  const config = configFor(home, provider.url);
  await shell(`mkdir -p ${quote(`${home}/work`)}; cat > ${quote(`${home}/models.json`)}`,
    JSON.stringify({ providers: { omnibot: config.provider } }));
  const servers = [{ type: "http", name: "fixture", url: `${mcp.url}/mcp`, headers: [{ name: "Authorization", value: "Bearer device-token" }] }];
  running = await start();
  const session = await running.remote.request(methods.agent.session.new, { cwd: `${home}/work`, mcpServers: servers });
  const prompt = text => running.remote.request(methods.agent.session.prompt, { sessionId: session.sessionId, prompt: [{ type: "text", text }] });
  for (const task of ["write-fixture", "read-fixture", "shell-fixture", "mcp-fixture"]) {
    assert.equal((await prompt(task)).stopReason, "end_turn");
  }
  for (const proof of ["device-file-proof", "device-shell-proof", "device-mcp-proof"]) {
    assert.ok(updates.some(({ update }) => update.sessionUpdate === "tool_call_update" && update.status === "completed" && JSON.stringify(update.content).includes(proof)), proof);
  }
  assert.ok(mcp.requests.filter(({ body }) => body.method === "tools/call").every(({ headers }) => headers.authorization === "Bearer device-token"));
  const ids = updates.flatMap(({ update }) => update.messageId ? [update.messageId] : []);
  await running.close();
  running = await start();
  const offset = updates.length;
  await running.remote.request(methods.agent.session.load, { sessionId: session.sessionId, cwd: `${home}/work`, mcpServers: servers });
  const replayIds = updates.slice(offset).flatMap(({ update }) => update.messageId ? [update.messageId] : []);
  assert.deepEqual(replayIds, ids);
  const cancelled = prompt("cancel-fixture");
  await partial.promise;
  await running.remote.notify(methods.agent.session.cancel, { sessionId: session.sessionId });
  assert.equal((await cancelled).stopReason, "cancelled");
  assert.equal((await prompt("after cancellation")).stopReason, "end_turn");
  const longShell = prompt("long-shell-fixture");
  await shellStarted.promise;
  const cancelStarted = Date.now();
  await running.remote.notify(methods.agent.session.cancel, { sessionId: session.sessionId });
  assert.equal((await longShell).stopReason, "cancelled");
  assert.ok(Date.now() - cancelStarted < 10000, "Bash cancellation did not stop promptly");
  await shell(`! kill -0 "$(cat ${quote(`${home}/work/sleep.pid`)})" 2>/dev/null`);
  await running.remote.request(methods.agent.session.close, { sessionId: session.sessionId });
  await running.close();
  running = undefined;
  console.log(JSON.stringify({ serial, distribution, pi: "1.4.0", protocol: 1, provider: "local fixture", fileReadWrite: true, bash: true, mcp: true, restoredIdentity: true, cancellation: true, shellChildStopped: true, closedCleanly: true }));
} finally {
  clearTimeout(deadline);
  if (running) await running.close().catch(() => {});
  for (const child of processes) child.kill();
  await shell(`rm -rf ${quote(home)}`).catch(() => {});
  for (const port of ports) adb("reverse", "--remove", `tcp:${port}`);
  await provider.close();
  await mcp.close();
}
