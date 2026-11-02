import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { getBuiltinModels } from "@earendil-works/pi-ai/providers/all";

const packageRoot = fileURLToPath(new URL("..", import.meta.url));
const repo = resolve(packageRoot, "../..");
const packageName = "@earendil-works/pi-ai";
const version = "1.4.0";
const enabledLevels = ["minimal", "low", "medium", "high", "xhigh", "max"];
const supportedApis = new Set(["anthropic-messages", "openai-completions", "openai-responses", "openai-codex-responses"]);
const providers = [
  "anthropic", "deepseek", "minimax", "minimax-cn", "moonshotai", "moonshotai-cn", "openai", "openai-codex",
  "qwen-token-plan", "qwen-token-plan-cn", "qwen-token-plan-individual", "xai", "xiaomi", "zai", "zai-coding-cn",
];
// Exact endpoint aliases reviewed in docs/third-party/pi-model-catalog-NOTICE.md.
const chatCompletionsAliases = [
  "gpt-4", "gpt-4-turbo", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano",
  "gpt-4o", "gpt-4o-2024-05-13", "gpt-4o-2024-08-06", "gpt-4o-2024-11-20", "gpt-4o-mini",
  "gpt-5", "gpt-5-chat-latest", "gpt-5-mini", "gpt-5-nano", "gpt-5.1", "gpt-5.2",
  "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano", "gpt-5.5", "gpt-5.6-luna", "gpt-5.6-sol",
  "gpt-5.6-terra", "gpt-6-astra", "gpt-6-luna", "gpt-6-sol", "gpt-6.1-sol",
  "o1", "o3", "o3-mini", "o4-mini",
];

/** Copy declarations only; SDK fallback levels are not model metadata. */
export function reasoningEntry(model) {
  const entry = { reasoning: model.reasoning };
  if (typeof model.compat?.forceAdaptiveThinking === "boolean") {
    entry.reasoningSupportsAdaptive = model.compat.forceAdaptiveThinking;
  }
  if (typeof model.compat?.thinkingFormat === "string" && model.compat.thinkingFormat.trim()) {
    entry.reasoningWireFormat = model.compat.thinkingFormat;
  }
  if (typeof model.compat?.supportsReasoningEffort === "boolean") {
    entry.reasoningEffortSupported = model.compat.supportsReasoningEffort;
  }
  if (typeof model.compat?.requiresReasoningContentOnAssistantMessages === "boolean") {
    entry.reasoningContentRequired = model.compat.requiresReasoningContentOnAssistantMessages;
  }
  if (model.reasoning !== true) return entry;
  const map = model.thinkingLevelMap ?? {};
  const declared = new Set(Object.values(map).filter((value) => typeof value === "string"));
  const offWireValue = map.off === "none" || map.off === "off" ? map.off : undefined;
  const levels = [
    ...(offWireValue ? [offWireValue] : []),
    ...enabledLevels.filter((value) => declared.has(value)),
  ];
  if (levels.length) entry.supportedReasoningLevels = levels;
  if (Object.hasOwn(map, "off")) {
    if (map.off === null) entry.reasoningCanDisable = false;
    else if (map.off === "none" || map.off === "off") entry.reasoningCanDisable = true;
  }
  return entry;
}

export function generateCatalog() {
  const installed = JSON.parse(readFileSync(new URL("../package.json", import.meta.resolve(packageName)), "utf8"));
  const pinned = JSON.parse(readFileSync(resolve(packageRoot, "package.json"), "utf8"));
  if (installed.version !== version || pinned.dependencies[packageName] !== version) {
    throw new Error(`Review the model catalog source before changing ${packageName}@${version}.`);
  }
  const routes = new Map();
  for (const provider of providers) {
    for (const model of getBuiltinModels(provider)) {
      if (!supportedApis.has(model.api)) continue;
      if (typeof model.reasoning !== "boolean") continue;
      const key = `${model.api}\n${model.baseUrl}`;
      const route = routes.get(key) ?? {
        api: model.api, baseUrl: model.baseUrl,
        ...(provider === "openai-codex" ? { sourceType: "chatgpt_oauth" } : {}),
        models: {},
      };
      const entry = reasoningEntry(model);
      const previous = route.models[model.id];
      if (previous && JSON.stringify(previous) !== JSON.stringify(entry)) {
        throw new Error(`Conflicting reasoning declarations for ${key}: ${model.id}`);
      }
      route.models[model.id] = entry;
      routes.set(key, route);
    }
  }
  const apiBase = "https://api.openai.com/v1";
  const responseModels = routes.get(`openai-responses\n${apiBase}`)?.models;
  routes.set(`openai-completions\n${apiBase}`, {
    api: "openai-completions", baseUrl: apiBase,
    models: Object.fromEntries(chatCompletionsAliases.map((id) => {
      const entry = responseModels?.[id];
      if (!entry) throw new Error(`Reviewed endpoint alias is missing its exact source model: ${id}`);
      return [id, entry];
    })),
  });
  return {
    schemaVersion: 1,
    source: { package: packageName, version, providers },
    routes: [...routes.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([, route]) => ({
      ...route, models: Object.fromEntries(Object.entries(route.models).sort(([a], [b]) => a.localeCompare(b))),
    })),
  };
}

export const catalogPath = resolve(repo, "baselib/src/main/assets/provider_reasoning_catalog.json");

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const content = `${JSON.stringify(generateCatalog(), null, 2)}\n`;
  if (process.argv.includes("--check")) {
    if (readFileSync(catalogPath, "utf8") !== content) {
      throw new Error("Provider reasoning catalog is stale. Run node tools/pi-acp/scripts/generate-reasoning-catalog.mjs.");
    }
  } else {
    mkdirSync(dirname(catalogPath), { recursive: true });
    writeFileSync(catalogPath, content);
  }
  console.log(`Provider reasoning catalog: ${Buffer.byteLength(content)} bytes`);
}
