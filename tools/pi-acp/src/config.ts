import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join, resolve } from "node:path";
import { InMemoryCredentialStore } from "@earendil-works/pi-ai";
import type { ThinkingLevel } from "@earendil-works/pi-agent-core";
import { ModelRuntime } from "@earendil-works/pi-coding-agent";

export const VERSION = "0.1.0";
export const PI_VERSION = "1.4.0";
export const PROVIDER_ID = "omnibot";

export interface LaunchConfig {
  agentDir: string;
  sessionDir: string;
  modelId: string;
  apiKey: string;
  provider: Parameters<ModelRuntime["registerProvider"]>[1];
  skillPaths: string[];
  configPath?: string;
  capabilityIdentity?: { providerProfileId: string; providerRevision: number };
  modelThinkingDefaults?: Record<string, ThinkingLevel>;
}

const THINKING_LEVELS = new Set<string>(["off", "minimal", "low", "medium", "high", "xhigh", "max"]);

function capabilityIdentity(document: any): LaunchConfig["capabilityIdentity"] {
  const metadata = document?._meta?.omnibot;
  if (typeof metadata?.providerProfileId !== "string" || !metadata.providerProfileId.trim() ||
      !Number.isSafeInteger(metadata.providerRevision) || metadata.providerRevision < 0) return undefined;
  return { providerProfileId: metadata.providerProfileId, providerRevision: metadata.providerRevision };
}

function modelThinkingDefaults(document: any): Record<string, ThinkingLevel> {
  const defaults = document?._meta?.omnibot?.modelThinkingDefaults ?? {};
  if (typeof defaults !== "object" || defaults === null || Array.isArray(defaults) ||
      Object.values(defaults).some((value) => typeof value !== "string" || !THINKING_LEVELS.has(value))) {
    throw new Error("Invalid shared Provider thinking defaults.");
  }
  return { ...defaults };
}

function catalogProvider(document: any): LaunchConfig["provider"] {
  const provider = document?.providers?.[PROVIDER_ID];
  if (!provider || !Array.isArray(provider.models) || !provider.models.length) {
    throw new Error("Pi requires the shared Provider model catalog.");
  }
  if (!["openai-completions", "openai-responses", "anthropic-messages"].includes(provider.api)) {
    throw new Error("Unsupported Pi Provider protocol.");
  }
  const endpoint = new URL(provider.baseUrl);
  if (!["http:", "https:"].includes(endpoint.protocol)) throw new Error("Invalid Provider URL.");
  const ids = new Set<string>();
  const models = provider.models.map((model: any) => {
    if (!model || typeof model.id !== "string" || !model.id.trim() || ids.has(model.id) ||
        (model.type !== undefined && model.type !== "chat") ||
        (model.name !== undefined && typeof model.name !== "string") ||
        (model.reasoning !== undefined && typeof model.reasoning !== "boolean") ||
        [model.contextWindow, model.maxTokens].some((value) =>
          value !== undefined && (typeof value !== "number" || !Number.isFinite(value) || value <= 0)) ||
        (model.input !== undefined && (!Array.isArray(model.input) ||
          model.input.some((value: unknown) => value !== "text" && value !== "image")))) {
      throw new Error("Invalid shared Provider model definition.");
    }
    ids.add(model.id);
    if ((model.api !== undefined && model.api !== provider.api) ||
        (model.baseUrl !== undefined && model.baseUrl !== provider.baseUrl)) {
      throw new Error("Shared Provider models cannot change the configured route.");
    }
    if (model.thinkingLevelMap !== undefined &&
        (typeof model.thinkingLevelMap !== "object" || model.thinkingLevelMap === null ||
         Array.isArray(model.thinkingLevelMap) || Object.entries(model.thinkingLevelMap).some(([level, value]) =>
           !THINKING_LEVELS.has(level) || (value !== null && typeof value !== "string")))) {
      throw new Error("Invalid shared Provider thinking levels.");
    }
    // Model headers cannot replace the host's process-scoped credentials either.
    const { headers: _headers, ...definition } = model;
    return definition;
  });
  return { api: provider.api, baseUrl: provider.baseUrl, models };
}

function verifyLaunchIdentity(document: any, provider: LaunchConfig["provider"], env: NodeJS.ProcessEnv): void {
  const expected = [env.OMNIBOT_PI_PROVIDER_ID, env.OMNIBOT_PI_PROVIDER_REVISION,
    env.OMNIBOT_PI_PROVIDER_API, env.OMNIBOT_PI_PROVIDER_BASE_URL];
  if (expected.every((value) => value === undefined)) return;
  if (expected.some((value) => !value?.trim()) || !/^\d+$/.test(expected[1]!) ||
      !Number.isSafeInteger(Number(expected[1]))) {
    throw new Error("Incomplete shared Provider launch identity.");
  }
  const actual = capabilityIdentity(document);
  if (!actual || actual.providerProfileId !== expected[0] || actual.providerRevision !== Number(expected[1]) ||
      provider.api !== expected[2] || provider.baseUrl !== expected[3]) {
    throw new Error("Shared Provider configuration changed before Pi started.");
  }
}

/** Only catalog metadata may change while this process retains its Provider grant. */
export function refreshedLaunchConfig(config: LaunchConfig, modelId = config.modelId): LaunchConfig {
  if (!config.configPath || !config.capabilityIdentity) return config;
  const document = JSON.parse(readFileSync(config.configPath, "utf8"));
  const identity = capabilityIdentity(document);
  if (!identity || identity.providerProfileId !== config.capabilityIdentity.providerProfileId ||
      identity.providerRevision !== config.capabilityIdentity.providerRevision) return config;
  // The shared file can belong to another route/conversation by the time a load arrives.
  const route = document?.providers?.[PROVIDER_ID];
  if ((typeof route?.api === "string" && route.api !== config.provider.api) ||
      (typeof route?.baseUrl === "string" && route.baseUrl !== config.provider.baseUrl)) return config;
  const provider = catalogProvider(document);
  if (!provider.models!.some((model) => model.id === modelId)) {
    throw new Error(`Model ${modelId} is unavailable in the refreshed shared Provider catalog.`);
  }
  const defaults = modelThinkingDefaults(document);
  if (JSON.stringify(provider.models) === JSON.stringify(config.provider.models) &&
      JSON.stringify(defaults) === JSON.stringify(config.modelThinkingDefaults ?? {})) return config;
  return {
    ...config,
    provider: { ...config.provider, models: provider.models },
    modelThinkingDefaults: defaults,
  };
}

// Pi configuration values support interpolation and !commands. ACP and host
// configuration already contain resolved values, so preserve them as literals.
export function piLiteral(value: string): string {
  const escaped = value.replaceAll("$", "$$$$");
  return escaped.startsWith("!") ? `$${escaped}` : escaped;
}

export function readLaunchConfig(env = process.env): LaunchConfig {
  const agentDir = resolve(env.PI_CODING_AGENT_DIR || join(homedir(), ".pi", "omnibot-acp"));
  const configPath = resolve(env.OMNIBOT_PI_MODELS_PATH || join(agentDir, "models.json"));
  const document = JSON.parse(readFileSync(configPath, "utf8"));
  const provider = catalogProvider(document);
  verifyLaunchIdentity(document, provider, env);
  const modelId = env.OMNIBOT_PI_MODEL?.trim();
  const apiKey = env.OMNIBOT_PI_API_KEY;
  if (!modelId || !provider.models!.some((model) => model.id === modelId)) {
    throw new Error("Select a model from the shared Provider before starting Pi.");
  }
  if (!apiKey) throw new Error("Pi requires shared Provider credentials.");
  // Snapshot configuration at process launch. Other conversations/processes may
  // subsequently write their own shared-Provider configuration to the file.
  const headers = JSON.parse(env.OMNIBOT_PI_HEADERS || "{}");
  if (Object.values(headers).some((value) => typeof value !== "string")) {
    throw new Error("Provider headers must contain string values.");
  }
  return {
    agentDir,
    sessionDir: join(agentDir, "sessions"),
    modelId,
    apiKey,
    provider: {
      ...provider,
      apiKey: undefined,
      headers: Object.fromEntries(Object.entries(headers).map(([name, value]) => [name, piLiteral(value as string)])),
    },
    skillPaths: (env.OMNIBOT_PI_SKILL_PATHS || "").split(":").filter(Boolean),
    configPath,
    capabilityIdentity: capabilityIdentity(document),
    modelThinkingDefaults: modelThinkingDefaults(document),
  };
}

export async function createModelRuntime(config: LaunchConfig): Promise<ModelRuntime> {
  const runtime = await ModelRuntime.create({
    credentials: new InMemoryCredentialStore(),
    modelsPath: null,
    allowModelNetwork: false,
  });
  registerModelCatalog(runtime, config);
  await runtime.setRuntimeApiKey(PROVIDER_ID, config.apiKey);
  return runtime;
}

export function registerModelCatalog(runtime: ModelRuntime, config: LaunchConfig): void {
  runtime.registerProvider(PROVIDER_ID, {
    ...config.provider,
    // The SDK registration API, unlike models.json loading, expects the
    // complete price shape even when the host has no pricing metadata.
    models: config.provider.models?.map((model) => ({
      ...model, cost: model.cost ?? { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    })),
  });
}
