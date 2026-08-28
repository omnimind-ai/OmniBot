import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const DEFAULT_PROMPT = "Reply with exactly OK.";

function normalizedBaseUrl(baseUrl) {
  const value = String(baseUrl || "").trim().replace(/\/+$/, "");
  if (!value) throw new Error("Provider base URL is required");
  return value;
}

function apiRoot(baseUrl) {
  const value = normalizedBaseUrl(baseUrl);
  return /\/v\d+(?:\.\d+)?$/i.test(value) ? value : `${value}/v1`;
}

export function providerModelsUrl(baseUrl) {
  return `${apiRoot(baseUrl)}/models`;
}

function providerChatCompletionsUrl(baseUrl) {
  return `${apiRoot(baseUrl)}/chat/completions`;
}

export function extractModelIds(payload) {
  const models = Array.isArray(payload?.data) ? payload.data : [];
  return models
    .map((model) => (typeof model?.id === "string" ? model.id.trim() : ""))
    .filter(Boolean);
}

export function buildChatCompletionRequest(model, prompt = DEFAULT_PROMPT) {
  return {
    model: String(model).trim(),
    messages: [{ role: "user", content: String(prompt) }],
    stream: false,
    max_tokens: 8,
  };
}

async function readJsonResponse(response, label, apiKey) {
  const body = await response.text();
  if (!response.ok) {
    const redacted = body.replaceAll(apiKey, "[REDACTED]").slice(0, 500);
    throw new Error(`${label} failed with HTTP ${response.status}: ${redacted}`);
  }
  try {
    return JSON.parse(body);
  } catch {
    throw new Error(`${label} returned invalid JSON`);
  }
}

function requestOptions(apiKey, body) {
  const headers = {
    Accept: "application/json",
    Authorization: `Bearer ${apiKey}`,
  };
  if (body !== undefined) {
    headers["content-type"] = "application/json";
  }
  return {
    method: body === undefined ? "GET" : "POST",
    headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  };
}

function completionSucceeded(payload) {
  const choice = payload?.choices?.[0];
  const message = choice?.message;
  if (typeof message?.content === "string" && message.content.trim().length > 0) {
    return true;
  }
  // Reasoning-first models may spend a deliberately tiny smoke budget on
  // reasoning and return content=null while still returning a valid assistant
  // message and finish reason. That is a successful transport/API check.
  return message?.role === "assistant" && typeof choice?.finish_reason === "string";
}

export async function runProviderSmoke({
  baseUrl,
  apiKey,
  model,
  prompt = DEFAULT_PROMPT,
  fetchImpl = fetch,
  signal,
}) {
  const normalizedKey = String(apiKey || "").trim();
  const normalizedModel = String(model || "").trim();
  if (!normalizedKey) throw new Error("Provider API key is required");
  if (!normalizedModel) throw new Error("Provider model is required");

  const modelsResponse = await fetchImpl(
    providerModelsUrl(baseUrl),
    { ...requestOptions(normalizedKey), ...(signal ? { signal } : {}) },
  );
  const modelsPayload = await readJsonResponse(
    modelsResponse,
    "Provider model catalog",
    normalizedKey,
  );
  const modelAvailable = extractModelIds(modelsPayload).includes(normalizedModel);
  if (!modelAvailable) {
    return {
      modelAvailable: false,
      completionSucceeded: false,
      model: normalizedModel,
    };
  }

  const completionResponse = await fetchImpl(
    providerChatCompletionsUrl(baseUrl),
    {
      ...requestOptions(
        normalizedKey,
        buildChatCompletionRequest(normalizedModel, prompt),
      ),
      ...(signal ? { signal } : {}),
    },
  );
  const completionPayload = await readJsonResponse(
    completionResponse,
    "Provider chat completion",
    normalizedKey,
  );
  return {
    modelAvailable: true,
    completionSucceeded: completionSucceeded(completionPayload),
    model: normalizedModel,
  };
}

export async function runProviderSmokeFromEnvironment(env = process.env) {
  const apiKey = String(
    env.OMNIBOT_TEST_API_KEY || env.LLMTHU_API_KEY || env.OPENAI_API_KEY || "",
  ).trim();
  const baseUrl = String(
    env.OMNIBOT_TEST_BASE_URL || env.LLMTHU_API_BASE_URL || "https://llmapi.paratera.com",
  ).trim();
  const model = String(
    env.OMNIBOT_TEST_MODEL || env.LLMTHU_MODEL || "GLM-5.1",
  ).trim();
  const timeoutMs = Number(env.OMNIBOT_TEST_TIMEOUT_MS || 30_000);
  const signal = AbortSignal.timeout(Number.isFinite(timeoutMs) ? timeoutMs : 30_000);
  const result = await runProviderSmoke({
    baseUrl,
    apiKey,
    model,
    signal,
  });
  if (!result.modelAvailable) {
    throw new Error(`Provider does not expose configured model: ${model}`);
  }
  if (!result.completionSucceeded) {
    throw new Error("Provider returned no assistant completion content");
  }
  return result;
}

if (
  process.argv[1] &&
  fileURLToPath(import.meta.url) === resolve(process.argv[1])
) {
  try {
    const result = await runProviderSmokeFromEnvironment();
    console.log(
      `Provider smoke passed: model=${result.model}, catalog=ok, completion=ok`,
    );
  } catch (error) {
    console.error(`Provider smoke failed: ${error.message || error}`);
    process.exitCode = 1;
  }
}
