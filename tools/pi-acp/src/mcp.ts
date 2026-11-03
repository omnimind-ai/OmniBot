import { createHash } from "node:crypto";
import { RequestError, type McpServer } from "@agentclientprotocol/sdk/unstable";
import type { LoadedMcpConfig, McpServerEntry } from "@earendil-works/pi-coding-agent";
import { piLiteral } from "./config.js";

export function sessionMcpConfig(servers: McpServer[], cwd: string): LoadedMcpConfig {
  const names = servers.map((server) => server.name);
  if (new Set(names).size !== names.length) throw RequestError.invalidParams(undefined, "MCP server names must be unique.");
  const normalize = (name: string) => name.replace(/[^a-zA-Z0-9_-]/g, "_") || "server";
  const counts = new Map<string, number>();
  for (const name of names) {
    const key = normalize(name).replaceAll("-", "_");
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  const entries: McpServerEntry[] = servers.map((server) => {
    // Host display names need not be Pi identifiers. Keep the transformation
    // deterministic and disambiguate Pi's hyphen/underscore namespace folding.
    const base = normalize(server.name);
    const needsSuffix = base !== server.name || counts.get(base.replaceAll("-", "_"))! > 1;
    // Hash the original name rather than its position: reconnecting with a
    // reordered server list must not route a saved tool name to another server.
    const name = needsSuffix ? `${base}_${createHash("sha256").update(server.name).digest("hex").slice(0, 12)}` : base;
    const common = { exposure: "direct" as const, enabled: true };
    const entry = { name, source: "ACP session", scope: "extension" as const };
    if ("type" in server && server.type === "sse") {
      throw RequestError.invalidParams(undefined, "Pi supports Streamable HTTP MCP, not legacy SSE.");
    }
    if ("type" in server && server.type === "http") {
      return { ...entry, config: {
        ...common, type: "http", url: server.url,
        headers: Object.fromEntries(server.headers.map(({ name, value }) => [name, piLiteral(value)])),
      } };
    }
    if (!("command" in server)) throw RequestError.invalidParams(undefined, "Invalid MCP server.");
    return { ...entry, config: {
      ...common, type: "stdio", command: server.command, args: server.args, cwd,
      env: Object.fromEntries(server.env.map(({ name, value }) => [name, piLiteral(value)])),
    } };
  });
  const namespaces = entries.map((entry) => entry.name.replaceAll("-", "_"));
  if (new Set(namespaces).size !== namespaces.length) {
    throw RequestError.invalidParams(undefined, "MCP server names collide after normalization; rename one server.");
  }
  return { servers: entries, errors: [], autoEnableCodemode: false };
}
