import assert from "node:assert/strict";
import test from "node:test";

import {
  modelsDevKeys,
  syncModelsDevCatalog,
  syncModelsDevCatalogToBackend,
  loadConfig,
  validateModelsDevCatalog,
} from "./sync_models_dev_catalog.mjs";

const CATALOG = JSON.stringify({
  openai: {
    id: "openai",
    models: {
      "gpt-test": { id: "gpt-test", name: "GPT Test" },
    },
  },
});

class MemoryR2Client {
  constructor() {
    this.objects = new Map();
    this.puts = [];
    this.deletes = [];
    this.clock = 0;
  }

  async exists(key) {
    return this.objects.has(key);
  }

  async getText(key) {
    return this.objects.get(key)?.value || null;
  }

  async putText(key, value, options = {}) {
    this.clock += 1;
    this.objects.set(key, {
      key,
      value,
      options,
      uploaded: new Date(this.clock * 1_000).toISOString(),
    });
    this.puts.push(key);
  }

  async list(prefix) {
    return [...this.objects.values()].filter((object) =>
      object.key.startsWith(prefix)
    );
  }

  async delete(key) {
    this.objects.delete(key);
    this.deletes.push(key);
  }
}

function config(overrides = {}) {
  return {
    upstreamUrl: "https://models.dev/api.json",
    r2Prefix: "metadata/models-dev",
    minBytes: 1,
    maxBytes: 100_000,
    minProviders: 1,
    minModels: 1,
    maxDropRatio: 0.35,
    timeoutMs: 5_000,
    snapshotRetention: 5,
    requiredProviders: ["openai"],
    force: false,
    workflowRunUrl: "https://github.com/example/repo/actions/runs/1",
    ...overrides,
  };
}

function upstreamResponse(body = CATALOG, {
  status = 200,
  etag = '"upstream-v1"',
} = {}) {
  return new Response(body, {
    status,
    headers: {
      "content-type": "application/json",
      etag,
    },
  });
}

test("initial sync validates and publishes snapshot before current", async () => {
  const r2 = new MemoryR2Client();
  const result = await syncModelsDevCatalog({
    config: config(),
    r2,
    fetchImpl: async () => upstreamResponse(),
    now: () => 1_700_000_000_000,
  });
  const keys = modelsDevKeys("metadata/models-dev");

  assert.equal(result.changed, true);
  assert.equal(result.providerCount, 1);
  assert.equal(result.modelCount, 1);
  assert.ok(result.sha256);
  assert.deepEqual(r2.puts.slice(0, 3), [
    keys.snapshot(result.sha256),
    keys.current,
    keys.status,
  ]);
  assert.equal(
    r2.objects.get(keys.current).options.metadata.sha256,
    result.sha256,
  );
});

test("conditional sync handles 304 without replacing current", async () => {
  const r2 = new MemoryR2Client();
  await syncModelsDevCatalog({
    config: config(),
    r2,
    fetchImpl: async () => upstreamResponse(),
    now: () => 1_700_000_000_000,
  });
  const keys = modelsDevKeys("metadata/models-dev");
  const currentBefore = r2.objects.get(keys.current);
  let conditionalEtag = "";

  const result = await syncModelsDevCatalog({
    config: config(),
    r2,
    fetchImpl: async (_url, init) => {
      conditionalEtag = init.headers["if-none-match"];
      return new Response(null, {
        status: 304,
        headers: { etag: '"upstream-v1"' },
      });
    },
    now: () => 1_700_000_100_000,
  });

  assert.equal(conditionalEtag, '"upstream-v1"');
  assert.equal(result.changed, false);
  assert.equal(result.notModified, true);
  assert.equal(r2.objects.get(keys.current), currentBefore);
});

test("invalid update records failure and preserves last good current", async () => {
  const r2 = new MemoryR2Client();
  await syncModelsDevCatalog({
    config: config(),
    r2,
    fetchImpl: async () => upstreamResponse(),
    now: () => 1_700_000_000_000,
  });
  const keys = modelsDevKeys("metadata/models-dev");
  const currentBefore = r2.objects.get(keys.current);

  await assert.rejects(
    syncModelsDevCatalog({
      config: config(),
      r2,
      fetchImpl: async () => upstreamResponse("{}"),
      now: () => 1_700_000_100_000,
    }),
    /provider count/,
  );

  assert.equal(r2.objects.get(keys.current), currentBefore);
  const status = JSON.parse(r2.objects.get(keys.status).value);
  assert.equal(status.consecutiveFailures, 1);
  assert.match(status.lastError, /provider count/);
});

test("validation blocks suspicious model drops unless forced", () => {
  assert.throws(
    () => validateModelsDevCatalog(CATALOG, {
      config: config(),
      previousStatus: { providerCount: 1, modelCount: 10 },
    }),
    /model count dropped/,
  );

  const result = validateModelsDevCatalog(CATALOG, {
    config: config(),
    previousStatus: { providerCount: 1, modelCount: 10 },
    force: true,
  });
  assert.equal(result.modelCount, 1);
});

test("snapshot cleanup keeps the configured newest snapshots and current", async () => {
  const r2 = new MemoryR2Client();
  const keys = modelsDevKeys("metadata/models-dev");
  for (const sha of ["oldest", "middle", "current"]) {
    await r2.putText(keys.snapshot(sha), sha);
  }
  await r2.putText(keys.current, CATALOG);
  await r2.putText(keys.status, JSON.stringify({
    upstreamUrl: "https://models.dev/api.json",
    upstreamEtag: '"old"',
    sha256: "different",
    providerCount: 1,
    modelCount: 1,
  }));

  await syncModelsDevCatalog({
    config: config({ snapshotRetention: 2 }),
    r2,
    fetchImpl: async () => upstreamResponse(CATALOG, { etag: '"new"' }),
    now: () => 1_700_000_000_000,
  });

  assert.equal(r2.objects.has(keys.snapshot("oldest")), false);
  assert.equal(r2.objects.has(keys.snapshot("middle")), false);
});


function backendConfig(overrides = {}) {
  return config({
    backendUrl: "https://backend.example",
    backendToken: "test-admin-token",
    ...overrides,
  });
}

function apiJson(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { "content-type": "application/json" },
  });
}

test("website publishing validates catalog and sends auth only to admin API", async () => {
  const calls = [];
  const result = await syncModelsDevCatalogToBackend({
    config: backendConfig(),
    fetchImpl: async (url, init) => {
      calls.push({ url, init });
      if (url === "https://models.dev/api.json") {
        assert.equal(init.headers.authorization, undefined);
        return upstreamResponse();
      }
      assert.equal(init.headers.authorization, "Bearer test-admin-token");
      if (init.method === "POST") return apiJson({ ok: true });
      return apiJson({ current: null, refresh: null });
    },
    now: () => 1_700_000_000_000,
  });
  assert.equal(result.changed, true);
  const post = calls.find(({ init }) => init.method === "POST");
  assert.equal(post.url, "https://backend.example/api/admin/models-dev");
  assert.equal(post.init.body, CATALOG);
  assert.equal(post.init.headers["x-models-dev-provider-count"], "1");
  assert.equal(post.init.headers["x-models-dev-model-count"], "1");
  assert.equal(post.init.headers["x-models-dev-sha256"], result.sha256);
});

test("website conditional sync writes refresh status without replacing catalog", async () => {
  let status;
  const result = await syncModelsDevCatalogToBackend({
    config: backendConfig(),
    fetchImpl: async (url, init) => {
      if (url === "https://models.dev/api.json") {
        assert.equal(init.headers["if-none-match"], '"upstream-v1"');
        return new Response(null, { status: 304 });
      }
      assert.notEqual(init.method, "POST");
      if (init.method === "PUT") {
        assert.equal(url, "https://backend.example/api/admin/models-dev/status");
        status = JSON.parse(init.body);
        return apiJson({ ok: true });
      }
      return apiJson({
        current: { sha256: "last-good", providerCount: 1, modelCount: 1, upstreamEtag: '"upstream-v1"' },
        refresh: { upstreamUrl: "https://models.dev/api.json", lastSuccessfulAt: 100 },
      });
    },
    now: () => 1_700_000_000_000,
  });
  assert.equal(result.notModified, true);
  assert.equal(status.changed, false);
  assert.equal(status.sha256, "last-good");
  assert.equal(status.lastSuccessfulAt, 1_700_000_000_000);
});

test("website sync rejects invalid catalog and records failure preserving good state", async () => {
  let status;
  await assert.rejects(syncModelsDevCatalogToBackend({
    config: backendConfig(),
    fetchImpl: async (url, init) => {
      if (url === "https://models.dev/api.json") return upstreamResponse("{}");
      assert.notEqual(init.method, "POST");
      if (init.method === "PUT") {
        status = JSON.parse(init.body);
        return apiJson({ ok: true });
      }
      return apiJson({ current: { sha256: "last-good", providerCount: 1, modelCount: 1 }, refresh: {} });
    },
  }), /provider|model/i);
  assert.equal(status.sha256, "last-good");
  assert.equal(status.consecutiveFailures, 1);
  assert.ok(status.lastError);
});

test("default catalog configuration selects website despite legacy R2 variables", () => {
  const value = loadConfig({
    APP_UPDATE_WORKER_TOKEN: "test-admin-token",
    APP_UPDATE_WORKER_URL: "https://legacy-worker.example",
    CLOUDFLARE_ACCOUNT_ID: "legacy-account",
    CLOUDFLARE_R2_BUCKET_NAME: "legacy-bucket",
  });
  assert.equal(value.storage, "backend");
  assert.equal(value.backendUrl, "https://omnibot.omnimind.com.cn");
  assert.equal(value.bucket, "");
});
