import assert from "node:assert/strict";
import test from "node:test";

import worker from "./worker.js";

const CATALOG = JSON.stringify({
  openai: {
    id: "openai",
    name: "OpenAI",
    models: {
      "gpt-test": {
        id: "gpt-test",
        name: "GPT Test",
      },
    },
  },
});

class MemoryR2Object {
  constructor(key, value, options = {}) {
    this.key = key;
    this.value = value instanceof Uint8Array ? value : new TextEncoder().encode(value);
    this.size = this.value.byteLength;
    this.etag = options.etag || `r2-${this.size}`;
    this.uploaded = new Date();
    this.httpMetadata = options.httpMetadata || {};
    this.customMetadata = options.customMetadata || {};
  }

  get body() {
    return new Response(this.value).body;
  }

  async text() {
    return new TextDecoder().decode(this.value);
  }
}

class MemoryR2Bucket {
  constructor() {
    this.objects = new Map();
  }

  async head(key) {
    return this.objects.get(key) || null;
  }

  async get(key) {
    return this.objects.get(key) || null;
  }

  async put(key, value, options = {}) {
    let bytes;
    if (typeof value === "string") {
      bytes = new TextEncoder().encode(value);
    } else if (value instanceof Uint8Array) {
      bytes = value;
    } else if (value instanceof ArrayBuffer) {
      bytes = new Uint8Array(value);
    } else {
      bytes = new Uint8Array(await new Response(value).arrayBuffer());
    }
    const object = new MemoryR2Object(key, bytes, options);
    this.objects.set(key, object);
    return object;
  }

  async list({ prefix = "" } = {}) {
    const objects = [...this.objects.values()]
      .filter((object) => object.key.startsWith(prefix));
    return { objects, truncated: false };
  }
}

function testEnv(bucket) {
  return {
    APP_UPDATE_BUCKET: bucket,
    ADMIN_TOKEN: "test-token",
  };
}

async function seedMirror(bucket, {
  lowercaseMetadata = true,
  withMetadata = true,
} = {}) {
  const status = {
    schemaVersion: 1,
    publisher: "github-actions",
    lastCheckedAt: 1_700_000_000_000,
    lastSuccessfulAt: 1_700_000_000_000,
    changed: true,
    consecutiveFailures: 0,
    lastError: "",
    upstreamUrl: "https://models.dev/api.json",
    upstreamEtag: '"upstream-v1"',
    sha256: "catalog-sha256",
    providerCount: 1,
    modelCount: 1,
    size: new TextEncoder().encode(CATALOG).byteLength,
  };
  const metadata = lowercaseMetadata
    ? {
      sha256: status.sha256,
      upstreametag: status.upstreamEtag,
      fetchedat: String(status.lastSuccessfulAt),
      providercount: String(status.providerCount),
      modelcount: String(status.modelCount),
      size: String(status.size),
    }
    : {
      sha256: status.sha256,
      upstreamEtag: status.upstreamEtag,
      fetchedAt: String(status.lastSuccessfulAt),
      providerCount: String(status.providerCount),
      modelCount: String(status.modelCount),
      size: String(status.size),
    };

  await bucket.put("metadata/models-dev/current.json", CATALOG, {
    etag: "r2-catalog-etag",
    customMetadata: withMetadata ? metadata : {},
  });
  await bucket.put(
    "metadata/models-dev/status.json",
    JSON.stringify(status),
  );
  return status;
}

test("serves CI-published R2 catalog with SHA conditional GET", async () => {
  const bucket = new MemoryR2Bucket();
  const env = testEnv(bucket);
  const status = await seedMirror(bucket);

  const response = await worker.fetch(
    new Request("https://updates.example/catalog/models-dev/api.json"),
    env,
  );
  assert.equal(response.status, 200);
  assert.equal(await response.text(), CATALOG);
  assert.equal(response.headers.get("etag"), `"${status.sha256}"`);
  assert.equal(response.headers.get("x-models-dev-provider-count"), "1");
  assert.equal(response.headers.get("access-control-allow-origin"), "*");

  const notModified = await worker.fetch(
    new Request("https://updates.example/catalog/models-dev/api.json", {
      headers: { "if-none-match": `W/"${status.sha256}"` },
    }),
    env,
  );
  assert.equal(notModified.status, 304);
  assert.equal(await notModified.text(), "");
});

test("update checks default to a disabled policy and ignore legacy environment values", async () => {
  const bucket = new MemoryR2Bucket();
  const env = {
    ...testEnv(bucket),
    MIN_CLOUD_SERVICE_VERSION: "99.0",
    CLOUD_SERVICE_UPDATE_MESSAGE: "legacy environment value",
  };
  const response = await worker.fetch(
    new Request("https://updates.example/updates?currentVersion=0.5.6.1"),
    env,
  );

  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.deepEqual(payload.cloudServicePolicy, {
    schemaVersion: 1,
    enabled: false,
    minimumVersion: "",
    accessAllowed: true,
    message: "",
  });
});

test("admin console exposes cloud-service policy controls", async () => {
  const response = await worker.fetch(
    new Request("https://updates.example/admin"),
    testEnv(new MemoryR2Bucket()),
  );
  const html = await response.text();

  assert.equal(response.status, 200);
  assert.match(html, /id="nav-cloud-policy"/);
  assert.match(html, /id="cloud-minimum-version"/);
  assert.match(html, /id="cloud-policy-message"/);
  assert.match(html, /\/admin\/cloud-service-policy/);
  const script = html.split("<script>")[1]?.split("</script>")[0] || "";
  assert.doesNotThrow(() => new Function(script));
});

test("admin console exposes community QR replacement controls", async () => {
  const response = await worker.fetch(
    new Request("https://updates.example/admin"),
    testEnv(new MemoryR2Bucket()),
  );
  const html = await response.text();

  assert.equal(response.status, 200);
  assert.match(html, /id="nav-community-qr"/);
  assert.match(html, /id="community-qr-input"/);
  assert.match(html, /id="community-qr-upload"/);
  assert.match(html, /\/admin\/community\/wechat-qr/);
  const script = html.split("<script>")[1]?.split("</script>")[0] || "";
  assert.doesNotThrow(() => new Function(script));
});

test("authenticated QR upload replaces the image behind one public URL", async () => {
  const bucket = new MemoryR2Bucket();
  const env = testEnv(bucket);
  const adminUrl = "https://updates.example/admin/community/wechat-qr";
  const publicUrl = "https://updates.example/community/wechat-qr";

  const missing = await worker.fetch(new Request(publicUrl), env);
  assert.equal(missing.status, 404);
  const unauthorized = await worker.fetch(new Request(adminUrl), env);
  assert.equal(unauthorized.status, 401);

  const invalid = await worker.fetch(
    new Request(adminUrl, {
      method: "PUT",
      headers: {
        authorization: "Bearer test-token",
        "content-type": "image/png",
      },
      body: new Uint8Array([1, 2, 3]),
    }),
    env,
  );
  assert.equal(invalid.status, 415);

  const png = new Uint8Array([
    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4,
  ]);
  const uploaded = await worker.fetch(
    new Request(adminUrl, {
      method: "PUT",
      headers: {
        authorization: "Bearer test-token",
        "content-type": "image/png",
      },
      body: png,
    }),
    env,
  );
  assert.equal(uploaded.status, 200);
  const uploadedPayload = await uploaded.json();
  assert.equal(uploadedPayload.image.configured, true);
  assert.equal(uploadedPayload.image.publicUrl, publicUrl);
  assert.equal(uploadedPayload.image.contentType, "image/png");
  assert.equal(uploadedPayload.image.size, png.byteLength);

  const publicImage = await worker.fetch(new Request(publicUrl), env);
  assert.equal(publicImage.status, 200);
  assert.equal(publicImage.headers.get("content-type"), "image/png");
  assert.match(publicImage.headers.get("cache-control"), /no-cache/);
  assert.deepEqual(new Uint8Array(await publicImage.arrayBuffer()), png);
  const etag = publicImage.headers.get("etag");
  assert.ok(etag);

  const notModified = await worker.fetch(
    new Request(publicUrl, { headers: { "if-none-match": `W/${etag}` } }),
    env,
  );
  assert.equal(notModified.status, 304);

  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 5, 6, 7]);
  const replaced = await worker.fetch(
    new Request(adminUrl, {
      method: "PUT",
      headers: {
        authorization: "Bearer test-token",
        "content-type": "image/jpeg",
      },
      body: jpeg,
    }),
    env,
  );
  assert.equal(replaced.status, 200);
  assert.equal((await replaced.json()).image.publicUrl, publicUrl);

  const replacement = await worker.fetch(new Request(publicUrl), env);
  assert.equal(replacement.headers.get("content-type"), "image/jpeg");
  assert.deepEqual(new Uint8Array(await replacement.arrayBuffer()), jpeg);
});

test("admin UI policy is authenticated, stored in R2, and applied to update checks", async () => {
  const bucket = new MemoryR2Bucket();
  const env = testEnv(bucket);

  const unauthorized = await worker.fetch(
    new Request("https://updates.example/admin/cloud-service-policy"),
    env,
  );
  assert.equal(unauthorized.status, 401);

  const savedResponse = await worker.fetch(
    new Request("https://updates.example/admin/cloud-service-policy", {
      method: "PUT",
      headers: {
        authorization: "Bearer test-token",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        minimumVersion: "v0.5.7",
        message: "Upgrade before using cloud services",
      }),
    }),
    env,
  );
  assert.equal(savedResponse.status, 200);
  const saved = await savedResponse.json();
  assert.equal(saved.policy.enabled, true);
  assert.equal(saved.policy.minimumVersion, "0.5.7");
  assert.equal(saved.policy.message, "Upgrade before using cloud services");

  const storedObject = await bucket.get("metadata/config/cloud-service-policy.json");
  assert.ok(storedObject);

  const readResponse = await worker.fetch(
    new Request("https://updates.example/admin/cloud-service-policy", {
      headers: { authorization: "Bearer test-token" },
    }),
    env,
  );
  const read = await readResponse.json();
  assert.equal(read.policy.minimumVersion, "0.5.7");

  const blockedResponse = await worker.fetch(
    new Request("https://updates.example/updates?currentVersion=0.5.6.15"),
    env,
  );
  const blocked = await blockedResponse.json();
  assert.equal(blocked.cloudServicePolicy.enabled, true);
  assert.equal(blocked.cloudServicePolicy.minimumVersion, "0.5.7");
  assert.equal(blocked.cloudServicePolicy.accessAllowed, false);
  assert.equal(
    blocked.cloudServicePolicy.message,
    "Upgrade before using cloud services",
  );

  const allowedResponse = await worker.fetch(
    new Request("https://updates.example/updates?currentVersion=0.5.7"),
    env,
  );
  const allowed = await allowedResponse.json();
  assert.equal(allowed.cloudServicePolicy.accessAllowed, true);
  assert.equal(allowed.cloudServicePolicy.message, "");
});

test("admin UI can disable the cloud-service floor and rejects invalid versions", async () => {
  const bucket = new MemoryR2Bucket();
  const env = testEnv(bucket);
  const headers = {
    authorization: "Bearer test-token",
    "content-type": "application/json",
  };

  const invalidResponse = await worker.fetch(
    new Request("https://updates.example/admin/cloud-service-policy", {
      method: "PUT",
      headers,
      body: JSON.stringify({ minimumVersion: "0.5.beta", message: "" }),
    }),
    env,
  );
  assert.equal(invalidResponse.status, 400);

  await worker.fetch(
    new Request("https://updates.example/admin/cloud-service-policy", {
      method: "PUT",
      headers,
      body: JSON.stringify({ minimumVersion: "0.5.7", message: "blocked" }),
    }),
    env,
  );
  const disabledResponse = await worker.fetch(
    new Request("https://updates.example/admin/cloud-service-policy", {
      method: "PUT",
      headers,
      body: JSON.stringify({ minimumVersion: "", message: "" }),
    }),
    env,
  );
  const disabled = await disabledResponse.json();
  assert.equal(disabled.policy.enabled, false);
  assert.equal(disabled.policy.minimumVersion, "");

  const updateResponse = await worker.fetch(
    new Request("https://updates.example/updates?currentVersion=0.5.6.15"),
    env,
  );
  const update = await updateResponse.json();
  assert.equal(update.cloudServicePolicy.enabled, false);
  assert.equal(update.cloudServicePolicy.accessAllowed, true);
});

test("falls back to the R2 object ETag when custom metadata is unavailable", async () => {
  const bucket = new MemoryR2Bucket();
  const env = testEnv(bucket);
  await seedMirror(bucket, { withMetadata: false });

  const response = await worker.fetch(
    new Request("https://updates.example/catalog/models-dev/api.json"),
    env,
  );
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("etag"), '"r2-catalog-etag"');
});

test("returns 503 before GitHub Actions publishes the first catalog", async () => {
  const response = await worker.fetch(
    new Request("https://updates.example/catalog/models-dev/api.json"),
    testEnv(new MemoryR2Bucket()),
  );
  assert.equal(response.status, 503);
});

test("update checks prefer Gelab while keeping both upstream keys private", async () => {
  const bucket = new MemoryR2Bucket();
  const env = {
    ...testEnv(bucket),
    CHATGPT_LUNA_VLM_API_BASE: "https://chatgpt.example/codex",
    CHATGPT_LUNA_VLM_API_KEY: "server-managed-key",
    CHATGPT_LUNA_VLM_MODEL: "gpt-5.6-sol",
    OFFICIAL_VLM_OPERATION_API_BASE: "https://gelab.example/v1",
    OFFICIAL_VLM_OPERATION_API_KEY: "gelab-server-key",
    OFFICIAL_VLM_OPERATION_MODEL: "qwen-vl",
  };

  const response = await worker.fetch(
    new Request("https://updates.example/updates?currentVersion=0.5.6.14&edition=standard"),
    env,
  );
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.deepEqual(payload.officialVlmOperation, {
    enabled: true,
    apiBase: "https://updates.example",
    model: "qwen-vl",
    wireApi: "chat_completions",
  });
  assert.equal(JSON.stringify(payload).includes("server-managed-key"), false);
  assert.equal(JSON.stringify(payload).includes("gelab-server-key"), false);
});

test("update checks retain the legacy official VLM configuration fallback", async () => {
  const bucket = new MemoryR2Bucket();
  const env = {
    ...testEnv(bucket),
    OFFICIAL_VLM_OPERATION_API_BASE: "https://gelab.example/v1",
    OFFICIAL_VLM_OPERATION_API_KEY: "legacy-server-key",
    OFFICIAL_VLM_OPERATION_MODEL: "qwen-vl",
  };

  const response = await worker.fetch(
    new Request("https://worker.example/updates?currentVersion=0.0.0"),
    env,
  );
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.deepEqual(payload.officialVlmOperation, {
    enabled: true,
    apiBase: "https://worker.example",
    model: "qwen-vl",
    wireApi: "chat_completions",
  });
  assert.equal(JSON.stringify(payload).includes("legacy-server-key"), false);
});

test("Luna VLM proxy forwards the debug OmniMind key", async () => {
  const env = {
    ...testEnv(new MemoryR2Bucket()),
    CHATGPT_LUNA_VLM_API_BASE: "https://chatgpt.example/codex",
    CHATGPT_LUNA_VLM_API_KEY: "server-managed-key",
    CHATGPT_LUNA_VLM_MODEL: "gpt-5.6-sol",
  };
  const originalFetch = globalThis.fetch;
  let capturedRequest;
  globalThis.fetch = async (url, init) => {
    capturedRequest = new Request(url, init);
    return new Response("data: {\"type\":\"response.completed\"}\n\n", {
      status: 200,
      headers: {
        "content-type": "text/event-stream",
        "x-request-id": "upstream-request",
      },
    });
  };

  try {
    const response = await worker.fetch(
      new Request("https://updates.example/v1/responses", {
        method: "POST",
        headers: {
          "authorization": "Bearer client-supplied-key",
          "content-type": "application/json",
        },
        body: JSON.stringify({ model: "client-selected-model", stream: true, input: "hello" }),
      }),
      env,
    );

    assert.equal(response.status, 200);
    assert.equal(response.headers.get("content-type"), "text/event-stream");
    assert.equal(response.headers.get("x-request-id"), "upstream-request");
    assert.equal(capturedRequest.url, "https://chatgpt.example/codex/v1/responses");
    assert.equal(capturedRequest.headers.get("authorization"), "Bearer client-supplied-key");
    const upstreamPayload = await capturedRequest.json();
    assert.equal(upstreamPayload.model, "gpt-5.6-sol");
    assert.equal(upstreamPayload.input, "hello");
    assert.equal(await response.text(), "data: {\"type\":\"response.completed\"}\n\n");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("VLM proxy exposes Gelab and Luna on their own wire routes", async () => {
  const env = {
    ...testEnv(new MemoryR2Bucket()),
    CHATGPT_LUNA_VLM_API_BASE: "https://chatgpt.example/codex",
    CHATGPT_LUNA_VLM_API_KEY: "luna-server-key",
    CHATGPT_LUNA_VLM_MODEL: "gpt-5.6-sol",
    OFFICIAL_VLM_OPERATION_API_BASE: "https://gelab.example/v1",
    OFFICIAL_VLM_OPERATION_API_KEY: "gelab-server-key",
    OFFICIAL_VLM_OPERATION_MODEL: "qwen-vl",
  };
  const originalFetch = globalThis.fetch;
  const capturedRequests = [];
  globalThis.fetch = async (url, init) => {
    capturedRequests.push(new Request(url, init));
    return new Response("{}", {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  };

  try {
    const gelabResponse = await worker.fetch(
      new Request("https://updates.example/v1/chat/completions", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ model: "client-model", messages: [] }),
      }),
      env,
    );
    const lunaResponse = await worker.fetch(
      new Request("https://updates.example/v1/responses", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ model: "client-model", input: "hello" }),
      }),
      env,
    );

    assert.equal(gelabResponse.status, 200);
    assert.equal(lunaResponse.status, 200);
    assert.equal(capturedRequests[0].url, "https://gelab.example/v1/chat/completions");
    assert.equal(capturedRequests[0].headers.get("authorization"), "Bearer gelab-server-key");
    assert.equal((await capturedRequests[0].json()).model, "qwen-vl");
    assert.equal(capturedRequests[1].url, "https://chatgpt.example/codex/v1/responses");
    assert.equal(capturedRequests[1].headers.get("authorization"), "Bearer luna-server-key");
    assert.equal((await capturedRequests[1].json()).model, "gpt-5.6-sol");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("VLM proxy rejects unsupported content and disabled wire routes", async () => {
  const env = {
    ...testEnv(new MemoryR2Bucket()),
    CHATGPT_LUNA_VLM_API_BASE: "https://chatgpt.example/v1",
    CHATGPT_LUNA_VLM_API_KEY: "server-managed-key",
    CHATGPT_LUNA_VLM_MODEL: "gpt-5.6-sol",
  };
  const wrongContentType = await worker.fetch(
    new Request("https://updates.example/v1/responses", {
      method: "POST",
      headers: { "content-type": "text/plain" },
      body: "{}",
    }),
    env,
  );
  assert.equal(wrongContentType.status, 415);

  const disabledWireRoute = await worker.fetch(
    new Request("https://updates.example/v1/chat/completions", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "{}",
    }),
    env,
  );
  assert.equal(disabledWireRoute.status, 404);
});

test("admin status is authenticated and combines R2 metadata with CI status", async () => {
  const bucket = new MemoryR2Bucket();
  const env = testEnv(bucket);
  await seedMirror(bucket, { withMetadata: false });

  const unauthorized = await worker.fetch(
    new Request("https://updates.example/admin/models-dev"),
    env,
  );
  assert.equal(unauthorized.status, 401);

  const response = await worker.fetch(
    new Request("https://updates.example/admin/models-dev", {
      headers: { authorization: "Bearer test-token" },
    }),
    env,
  );
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.equal(payload.upstreamUrl, "https://models.dev/api.json");
  assert.equal(payload.current.sha256, "catalog-sha256");
  assert.equal(payload.current.providerCount, 1);
  assert.equal(payload.current.modelCount, 1);
  assert.equal(payload.refresh.publisher, "github-actions");
});

test("Worker no longer exposes an in-process models.dev refresh route", async () => {
  const bucket = new MemoryR2Bucket();
  const response = await worker.fetch(
    new Request("https://updates.example/admin/models-dev/refresh", {
      method: "POST",
      headers: { authorization: "Bearer test-token" },
    }),
    testEnv(bucket),
  );
  assert.equal(response.status, 404);
});
