import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Readable, Writable } from "node:stream";
import { fileURLToPath } from "node:url";
import { client, methods, ndJsonStream, type SessionNotification } from "@agentclientprotocol/sdk/unstable";
import { configFor, httpFixture, stream } from "./fixtures.js";
import { VERSION } from "../src/config.js";

test("the packaged stdio entrypoint speaks ACP v1 and exits on stdin close", { timeout: 15000 }, async () => {
  const provider = await httpFixture((_body, response) => stream(response, { text: "wire \u2028 and \u2029 preserved" }));
  const root = mkdtempSync(join(tmpdir(), "pi-stdio-"));
  const config = configFor(root, provider.url);
  mkdirSync(config.agentDir, { recursive: true });
  const modelsPath = join(config.agentDir, "models.json");
  writeFileSync(modelsPath, JSON.stringify({ providers: { omnibot: config.provider } }));
  const child = spawn(process.execPath, [fileURLToPath(new URL("../dist/index.js", import.meta.url))], {
    stdio: ["pipe", "pipe", "pipe"],
    env: {
      ...process.env, PI_CODING_AGENT_DIR: config.agentDir, OMNIBOT_PI_MODELS_PATH: modelsPath,
      OMNIBOT_PI_MODEL: config.modelId, OMNIBOT_PI_API_KEY: config.apiKey, OMNIBOT_PI_HEADERS: "{}",
    },
  });
  const exited = once(child, "exit");
  let stderr = "";
  child.stderr.on("data", (data) => { stderr += data; });
  const updates: SessionNotification[] = [];
  const connection = client()
    .onNotification(methods.client.session.update, ({ params }) => { updates.push(params); })
    .onRequest(methods.client.session.requestPermission, () => ({ outcome: { outcome: "selected", optionId: "allow_once" } }))
    .connect(ndJsonStream(Writable.toWeb(child.stdin), Readable.toWeb(child.stdout)));
  try {
    const initialized = await connection.agent.request(methods.agent.initialize, { protocolVersion: 1, clientCapabilities: {} });
    assert.equal(initialized.agentInfo?.name, "omnibot-pi-acp");
    assert.equal(initialized.agentInfo?.version, VERSION);
    const session = await connection.agent.request(methods.agent.session.new, { cwd: root, mcpServers: [] });
    const result = await connection.agent.request(methods.agent.session.prompt, { sessionId: session.sessionId, prompt: [{ type: "text", text: "wire test" }] });
    assert.equal(result.stopReason, "end_turn");
    assert.ok(updates.some(({ update }) => update.sessionUpdate === "agent_message_chunk" && update.content.type === "text" && update.content.text.includes("\u2028")));
    child.stdin.end();
    const [code] = await exited;
    assert.equal(code, 0, stderr);
  } finally {
    connection.close();
    if (child.exitCode == null) child.kill("SIGTERM");
    await provider.close();
    rmSync(root, { recursive: true, force: true });
  }
});
