import { mkdirSync, statSync } from "node:fs";
import { resolve } from "node:path";
import {
  agent, methods, PROTOCOL_VERSION, RequestError,
  type AgentContext, type McpServer, type SessionInfo,
} from "@agentclientprotocol/sdk/unstable";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import { type LaunchConfig, VERSION } from "./config.js";
import { PiAcpSession } from "./session.js";

export function createPiAgent(config: LaunchConfig) {
  const sessions = new Map<string, PiAcpSession>();
  const loading = new Set<string>();
  const bindings = new Map<string, string>();
  let initialized = false;
  let stopping = false;
  mkdirSync(config.sessionDir, { recursive: true, mode: 0o700 });

  function ready() {
    if (!initialized) throw RequestError.invalidRequest(undefined, "Initialize the ACP connection first.");
    if (stopping) throw RequestError.invalidRequest(undefined, "Agent is shutting down.");
  }

  function get(id: string): PiAcpSession {
    ready();
    if (loading.has(id)) throw RequestError.invalidRequest(undefined, "Session is loading.");
    const session = sessions.get(id);
    if (!session) throw RequestError.resourceNotFound(id);
    return session;
  }

  function checkCwd(cwd: string): string {
    const absolute = resolve(cwd);
    if (absolute !== cwd || !statSync(absolute, { throwIfNoEntry: false })?.isDirectory()) {
      throw RequestError.invalidParams(undefined, "Session cwd must be an existing absolute directory.");
    }
    return absolute;
  }

  async function open(manager: SessionManager, client: AgentContext, servers: McpServer[], launchConfig = config) {
    const session = await PiAcpSession.create(manager, launchConfig, client, servers);
    if (stopping) {
      await session.close();
      throw RequestError.invalidRequest(undefined, "Agent is shutting down.");
    }
    sessions.set(session.id, session);
    bindings.set(session.id, JSON.stringify(servers));
    return session;
  }

  async function load(id: string, cwd: string, servers: McpServer[], client: AgentContext) {
    ready();
    checkCwd(cwd);
    if (loading.has(id)) throw RequestError.invalidRequest(undefined, "Session is loading.");
    loading.add(id);
    try {
      const existing = sessions.get(id);
      if (existing?.busy) throw RequestError.invalidRequest(undefined, "Cannot load a session with an active prompt.");
      if (existing && existing.cwd !== cwd) throw RequestError.invalidParams(undefined, "Session belongs to a different cwd.");
      if (existing) await existing.refreshCatalog();
      if (existing && bindings.get(id) === JSON.stringify(servers)) return existing;
      const path = existing ? undefined : SessionManager.findById(cwd, id, config.sessionDir);
      if (!existing && !path) throw RequestError.resourceNotFound(id);
      const manager = existing?.manager || SessionManager.open(path!, config.sessionDir);
      if (manager.getSessionId() !== id || manager.getCwd() !== cwd) {
        throw RequestError.invalidParams(undefined, "Session identity does not match the requested workspace.");
      }
      if (existing) await existing.close();
      sessions.delete(id);
      bindings.delete(id);
      return await open(manager, client, servers, existing?.launchConfig ?? config);
    } finally {
      loading.delete(id);
    }
  }

  const app = agent({ name: "omnibot-pi-acp" })
    .onRequest(methods.agent.initialize, ({ params }) => {
      if (params.protocolVersion !== PROTOCOL_VERSION) throw RequestError.unsupportedProtocolVersion(params.protocolVersion, [PROTOCOL_VERSION]);
      initialized = true;
      return {
        protocolVersion: PROTOCOL_VERSION,
        agentInfo: { name: "omnibot-pi-acp", title: "Pi", version: VERSION },
        authMethods: [],
        agentCapabilities: {
          loadSession: true,
          promptCapabilities: { image: true, embeddedContext: true },
          mcpCapabilities: { http: true, sse: false },
          sessionCapabilities: { list: {}, close: {}, resume: {} },
        },
      };
    })
    .onRequest(methods.agent.session.new, async ({ params, client }) => {
      ready();
      const cwd = checkCwd(params.cwd);
      const session = await open(SessionManager.create(cwd, config.sessionDir), client, params.mcpServers);
      return { sessionId: session.id, modes: session.modes(), configOptions: session.configOptions() };
    })
    .onRequest(methods.agent.session.load, async ({ params, client }) => {
      const session = await load(params.sessionId, params.cwd, params.mcpServers, client);
      await session.replay();
      return { modes: session.modes(), configOptions: session.configOptions() };
    })
    .onRequest(methods.agent.session.resume, async ({ params, client }) => {
      const session = await load(params.sessionId, params.cwd, params.mcpServers || [], client);
      return { modes: session.modes(), configOptions: session.configOptions() };
    })
    .onRequest(methods.agent.session.list, async ({ params }) => {
      ready();
      // Pi's own session files are authoritative; active empty sessions are
      // included without manufacturing a separate persistence database.
      const saved = await SessionManager.listAll(config.sessionDir);
      const entries = new Map<string, SessionInfo>(saved.map((info) => [info.id, {
        sessionId: info.id, cwd: info.cwd, title: info.name || info.firstMessage,
        updatedAt: info.modified.toISOString(),
      }]));
      for (const session of sessions.values()) {
        if (!entries.has(session.id)) entries.set(session.id, {
          sessionId: session.id, cwd: session.cwd,
          title: session.manager.getSessionName(), updatedAt: session.createdAt,
        });
      }
      const filtered = [...entries.values()].filter((entry) => !params.cwd || entry.cwd === params.cwd)
        .sort((a, b) => (b.updatedAt || "").localeCompare(a.updatedAt || "") || a.sessionId.localeCompare(b.sessionId));
      const start = params.cursor ? filtered.findIndex((entry) => entry.sessionId === params.cursor) + 1 : 0;
      if (params.cursor && start === 0) throw RequestError.invalidParams(undefined, "Unknown session list cursor.");
      const page = filtered.slice(start, start + 100);
      return { sessions: page, ...(start + page.length < filtered.length ? { nextCursor: page.at(-1)!.sessionId } : {}) };
    })
    .onRequest(methods.agent.session.prompt, ({ params, signal }) => get(params.sessionId).prompt(params.prompt, signal))
    .onNotification(methods.agent.session.cancel, async ({ params }) => { await get(params.sessionId).cancel(); })
    .onRequest(methods.agent.session.close, async ({ params }) => {
      const session = get(params.sessionId);
      await session.close();
      sessions.delete(session.id);
      bindings.delete(session.id);
      return {};
    })
    .onRequest(methods.agent.session.setConfigOption, async ({ params }) => ({
      configOptions: await get(params.sessionId).setConfig(params.configId, params.value),
    }))
    .onRequest(methods.agent.session.setMode, async ({ params }) => {
      await get(params.sessionId).setMode(params.modeId);
      return {};
    });

  return {
    app,
    async close() {
      stopping = true;
      await Promise.allSettled([...sessions.values()].map((session) => session.close()));
      sessions.clear();
      bindings.clear();
    },
  };
}
