import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { client, methods, type RequestPermissionResponse, type SessionNotification } from "@agentclientprotocol/sdk/unstable";
import { createPiAgent } from "../src/server.js";
import type { LaunchConfig } from "../src/config.js";

export async function httpFixture(handler: (body: any, response: ServerResponse, request: IncomingMessage) => void | Promise<void>) {
  const requests: any[] = [];
  const server = createServer(async (request, response) => {
    try {
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString() || "{}");
      requests.push({ body, headers: request.headers, url: request.url });
      await handler(body, response, request);
    } catch (error) {
      if (!response.destroyed) { response.statusCode = 500; response.end(String(error)); }
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("No server port");
  return {
    url: `http://127.0.0.1:${address.port}`,
    requests,
    async close() {
      const done = new Promise<void>((resolve) => server.close(() => resolve()));
      server.closeAllConnections();
      await done;
    },
  };
}

export function stream(response: ServerResponse, options: { text?: string; thinking?: string; tool?: { id: string; name: string; arguments: unknown } } = {}) {
  response.writeHead(200, { "Content-Type": "text/event-stream" });
  const chunk = (delta: unknown, finish_reason: string | null = null) => response.write(`data: ${JSON.stringify({
    id: "completion", object: "chat.completion.chunk", model: "fixture", created: 1,
    choices: [{ index: 0, delta, finish_reason }],
  })}\n\n`);
  chunk({ role: "assistant" });
  if (options.thinking) chunk({ reasoning_content: options.thinking });
  if (options.text) chunk({ content: options.text });
  if (options.tool) chunk({ tool_calls: [{ index: 0, id: options.tool.id, type: "function", function: { name: options.tool.name, arguments: JSON.stringify(options.tool.arguments) } }] });
  chunk({}, options.tool ? "tool_calls" : "stop");
  response.write(`data: ${JSON.stringify({ id: "completion", choices: [], usage: { prompt_tokens: 32, completion_tokens: 8, total_tokens: 40 } })}\n\n`);
  response.end("data: [DONE]\n\n");
}

export function configFor(root: string, baseUrl: string): LaunchConfig {
  return {
    agentDir: join(root, "pi"), sessionDir: join(root, "pi", "sessions"), modelId: "fixture", apiKey: "test-key",
    provider: {
      baseUrl: `${baseUrl}/v1`, api: "openai-completions", headers: { "X-Fixture": "pi-acp" },
      models: ["fixture", "fixture-two"].map((id) => ({
        id, name: id, reasoning: true, input: ["text", "image"],
        contextWindow: 128000, maxTokens: 2048,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
        compat: { thinkingFormat: "deepseek" },
      })),
    },
    skillPaths: [],
  };
}

export async function harness(
  baseUrl: string,
  options: {
    root?: string;
    permission?: (signal: AbortSignal) => Promise<RequestPermissionResponse>;
    onUpdate?: (update: SessionNotification) => void;
    configure?: (config: LaunchConfig) => void;
  } = {},
) {
  const root = options.root || mkdtempSync(join(tmpdir(), "omnibot-pi-test-"));
  const config = configFor(root, baseUrl);
  options.configure?.(config);
  const runtime = createPiAgent(config);
  const updates: SessionNotification[] = [];
  let permissions = 0;
  const app = client({ name: "pi-test-host" })
    .onNotification(methods.client.session.update, ({ params }) => { updates.push(params); options.onUpdate?.(params); })
    .onRequest(methods.client.session.requestPermission, async ({ signal }) => {
      permissions++;
      return options.permission ? options.permission(signal) : { outcome: { outcome: "selected", optionId: "allow_once" } };
    });
  const connection = app.connect(runtime.app);
  const remote = connection.agent;
  const initialize = await remote.request(methods.agent.initialize, { protocolVersion: 1, clientCapabilities: {} });
  return {
    root, remote, updates, initialize, permissions: () => permissions,
    async newSession(mcpServers: any[] = []) {
      return remote.request(methods.agent.session.new, { cwd: root, mcpServers });
    },
    prompt(sessionId: string, text: string) {
      return remote.request(methods.agent.session.prompt, { sessionId, prompt: [{ type: "text", text }] });
    },
    async close(remove = true) {
      await runtime.close();
      connection.close();
      if (remove) rmSync(root, { recursive: true, force: true });
    },
  };
}
