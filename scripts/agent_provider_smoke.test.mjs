import assert from "node:assert/strict";
import test from "node:test";

import {
  buildChatCompletionRequest,
  extractModelIds,
  providerModelsUrl,
  runProviderSmoke,
} from "./agent_provider_smoke.mjs";

test("providerModelsUrl normalizes OpenAI-compatible base URLs", () => {
  assert.equal(
    providerModelsUrl("https://provider.example"),
    "https://provider.example/v1/models",
  );
  assert.equal(
    providerModelsUrl("https://provider.example/v1/"),
    "https://provider.example/v1/models",
  );
});

test("extractModelIds reads OpenAI-compatible model catalogs", () => {
  assert.deepEqual(
    extractModelIds({
      data: [{ id: "glm-5.1" }, { id: "deepseek-chat" }, { id: "" }],
    }),
    ["glm-5.1", "deepseek-chat"],
  );
});

test("chat completion request is short and does not leak credentials", () => {
  const request = buildChatCompletionRequest("glm-5.1", "reply with OK");
  assert.deepEqual(request, {
    model: "glm-5.1",
    messages: [{ role: "user", content: "reply with OK" }],
    stream: false,
    max_tokens: 8,
  });
  assert.equal(JSON.stringify(request).includes("Bearer"), false);
});

test("provider smoke verifies models and a short completion", async () => {
  const calls = [];
  const result = await runProviderSmoke({
    baseUrl: "https://provider.example/v1",
    apiKey: "test-token",
    model: "glm-5.1",
    fetchImpl: async (url, init) => {
      calls.push({ url, init });
      if (url.endsWith("/models")) {
        return new Response(JSON.stringify({ data: [{ id: "glm-5.1" }] }), {
          status: 200,
          headers: { "content-type": "application/json" },
        });
      }
      return new Response(JSON.stringify({
        choices: [{ message: { content: "OK" } }],
      }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    },
  });

  assert.equal(result.modelAvailable, true);
  assert.equal(result.completionSucceeded, true);
  assert.equal(calls.length, 2);
  assert.equal(calls[1].init.headers.Authorization, "Bearer test-token");
  assert.equal(calls[1].init.headers["content-type"], "application/json");
});

test("provider smoke accepts a valid reasoning-only assistant message", async () => {
  const result = await runProviderSmoke({
    baseUrl: "https://provider.example/v1",
    apiKey: "test-token",
    model: "glm-5.1",
    fetchImpl: async (url) => new Response(
      url.endsWith("/models")
        ? JSON.stringify({ data: [{ id: "glm-5.1" }] })
        : JSON.stringify({
            choices: [{
              finish_reason: "length",
              message: { role: "assistant", content: null },
            }],
          }),
      { status: 200 },
    ),
  });

  assert.equal(result.completionSucceeded, true);
});
