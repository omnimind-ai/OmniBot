import assert from "node:assert/strict";
import test from "node:test";
import worker from "./worker.js";

const POLICY_KEY = "metadata/config/cloud-service-policy.json";
const RELEASE_PREFIX = "metadata/releases/";
class Bucket {
  objects = new Map();
  reads = [];
  lists = [];
  pageSize = 1_000;
  async put(key, value, options = {}) {
    this.objects.set(key, { key, customMetadata: options.customMetadata || {}, text: async () => value });
  }
  async get(key) { this.reads.push(key); return this.objects.get(key) || null; }
  async head(key) { return this.objects.get(key) || null; }
  async delete(key) { this.objects.delete(key); }
  async list(options) {
    this.lists.push(options);
    const entries = [...this.objects.values()].filter(o => o.key.startsWith(options.prefix));
    const start = Number(options.cursor || 0);
    const end = start + this.pageSize;
    return {
      objects: entries.slice(start, end).map(o => ({
        key: o.key,
        ...(options.include?.includes("customMetadata") ? { customMetadata: o.customMetadata } : {}),
      })),
      truncated: end < entries.length,
      cursor: end < entries.length ? String(end) : undefined,
    };
  }
  get releaseReads() { return this.reads.filter(key => key !== POLICY_KEY); }
}
async function seed(bucket, version, { legacy = false, prefix = RELEASE_PREFIX, key, ...fields } = {}) {
  const name = `OpenOmniBot-v${version}-standard.apk`;
  const release = {
    tag: `v${version}`, version, track: "stable", draft: false,
    publishedAt: 1_790_000_000_000,
    assets: [{ name, githubDownloadUrl: `https://github.example/${name}` }],
    ...fields,
  };
  const objectKey = key || `${prefix}v${version}.json`;
  await bucket.put(objectKey, JSON.stringify(release), {
    customMetadata: legacy ? {} : {
      version, track: release.track, publishedat: String(release.publishedAt),
    },
  });
  return objectKey;
}
async function check(bucket, query = "", extraEnv = {}) {
  return worker.fetch(new Request(`https://updates.example/updates?currentVersion=0.6.3${query}`), {
    APP_UPDATE_BUCKET: bucket, ...extraEnv,
  });
}
const turn = () => new Promise(resolve => setImmediate(resolve));

test("100 indexed historical releases need one listing and only one release body", async () => {
  const bucket = new Bucket();
  for (let i = 0; i < 100; i++) await seed(bucket, `0.6.${i}`);
  const response = await check(bucket);
  const payload = await response.json();
  assert.equal(response.status, 200);
  assert.equal(payload.latestVersion, "0.6.99");
  assert.equal(payload.hasUpdate, true);
  assert.equal(payload.releaseCheckStatus, "ok");
  assert.equal(bucket.lists.length, 1);
  assert.deepEqual(bucket.lists[0].include, ["customMetadata"]);
  assert.deepEqual(bucket.releaseReads, [`${RELEASE_PREFIX}v0.6.99.json`]);
  assert.equal(payload.cloudServicePolicy.accessAllowed, true);
});

test("pagination, numeric versions, drafts and beta opt-in preserve release selection", async () => {
  const bucket = new Bucket();
  bucket.pageSize = 2;
  await seed(bucket, "0.9.0");
  await seed(bucket, "0.10.0");
  await seed(bucket, "0.11.0", { draft: true });
  await seed(bucket, "0.12.0.1", { track: "beta" });
  await seed(bucket, "0.99.0", { track: "unsupported" });
  let payload = await (await check(bucket, "&source=github")).json();
  assert.equal(payload.latestVersion, "0.10.0");
  assert.match(payload.apkDownloadUrl, /^https:\/\/github\.example\//);
  assert.equal(bucket.lists.length, 3);
  assert.deepEqual(bucket.releaseReads, [
    `${RELEASE_PREFIX}v0.11.0.json`, `${RELEASE_PREFIX}v0.10.0.json`,
  ]);
  payload = await (await check(bucket, "&includeBeta=true")).json();
  assert.equal(payload.latestVersion, "0.12.0.1");
  assert.match(payload.apkDownloadUrl, /^https:\/\/updates\.example\/downloads\//);
});

test("publication timestamps break equal-version ties and custom prefixes work", async () => {
  const bucket = new Bucket();
  const prefix = "custom/releases/";
  await seed(bucket, "0.7.0", { prefix, key: `${prefix}older.json` });
  const latest = await seed(bucket, "0.7.0", {
    prefix, key: `${prefix}newer.json`, publishedAt: 1_790_000_000_001, releaseNotes: "newer",
  });
  const payload = await (await check(bucket, "", { R2_METADATA_PREFIX: "custom/releases" })).json();
  assert.equal(payload.releaseNotes, "newer");
  assert.deepEqual(bucket.releaseReads, [latest]);
});

test("legacy metadata uses bounded parallel reads and remains compatible", async () => {
  const bucket = new Bucket();
  for (let i = 0; i < 14; i++) await seed(bucket, `0.7.${i}`, { legacy: true });
  let active = 0;
  let maximum = 0;
  const originalGet = bucket.get.bind(bucket);
  bucket.get = async key => {
    if (key === POLICY_KEY) return originalGet(key);
    active++;
    maximum = Math.max(maximum, active);
    await turn();
    active--;
    return originalGet(key);
  };
  const payload = await (await check(bucket)).json();
  assert.equal(payload.latestVersion, "0.7.13");
  assert.equal(bucket.releaseReads.length, 14);
  assert.equal(maximum, 6);
});

test("missing, corrupt and draft newest bodies do not hide a published release", async () => {
  const bucket = new Bucket();
  await seed(bucket, "0.7.0");
  const corrupt = await seed(bucket, "0.8.0");
  bucket.objects.get(corrupt).text = async () => "not-json";
  const missing = await seed(bucket, "0.9.0");
  const originalGet = bucket.get.bind(bucket);
  bucket.get = key => key === missing ? null : originalGet(key);
  assert.equal((await (await check(bucket)).json()).latestVersion, "0.7.0");
});

test("changed body ordering is rechecked instead of trusting stale index version", async () => {
  const bucket = new Bucket();
  await seed(bucket, "0.8.0");
  const changed = await seed(bucket, "0.7.0");
  bucket.objects.get(changed).customMetadata.version = "0.9.0";
  assert.equal((await (await check(bucket)).json()).latestVersion, "0.8.0");
});

for (const minimumVersion of ["", "0.7.0"]) {
  test(`release storage failure preserves the real policy (minimum=${minimumVersion || "disabled"})`, async () => {
    const bucket = new Bucket();
    await bucket.put(POLICY_KEY, JSON.stringify({ minimumVersion }));
    bucket.list = async () => { throw new Error("simulated storage failure"); };
    const response = await check(bucket);
    const payload = await response.json();
    assert.equal(response.status, 200);
    assert.equal(payload.releaseCheckStatus, "unavailable");
    assert.equal(payload.hasUpdate, false);
    assert.equal(payload.cloudServicePolicy.enabled, Boolean(minimumVersion));
    assert.equal(payload.cloudServicePolicy.accessAllowed, !minimumVersion);
  });
}

test("8-second release deadline returns policy and stops scheduling legacy reads", async t => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const bucket = new Bucket();
  for (let i = 0; i < 15; i++) await seed(bucket, `0.7.${i}`, { legacy: true });
  const pendingReads = [];
  const originalGet = bucket.get.bind(bucket);
  bucket.get = async key => {
    if (key === POLICY_KEY) return originalGet(key);
    const object = await originalGet(key);
    await new Promise(resolve => pendingReads.push(resolve));
    return object;
  };
  let finished = false;
  const pending = check(bucket).then(response => { finished = true; return response; });
  await turn();
  assert.equal(pendingReads.length, 6);
  t.mock.timers.tick(7_999);
  await turn();
  assert.equal(finished, false);
  t.mock.timers.tick(1);
  const payload = await (await pending).json();
  assert.equal(payload.cloudServicePolicy.accessAllowed, true);
  assert.equal(payload.releaseCheckStatus, "unavailable");
  for (const resolve of pendingReads) resolve();
  await turn();
  assert.equal(bucket.releaseReads.length, 6);
});

test("policy storage or validation failure never becomes an allowed policy", async () => {
  for (const mode of ["throw", "invalid"]) {
    const bucket = new Bucket();
    if (mode === "throw") {
      bucket.get = async () => { throw new Error("simulated policy storage failure"); };
    } else {
      await bucket.put(POLICY_KEY, JSON.stringify({ minimumVersion: "invalid" }));
    }
    const response = await check(bucket);
    assert.equal(response.status, 500);
    assert.equal((await response.json()).cloudServicePolicy, undefined);
  }
});

test("publishing, editing and deleting releases are immediately visible without a cache rebuild", async () => {
  const bucket = new Bucket();
  await seed(bucket, "0.6.4");
  const env = { APP_UPDATE_BUCKET: bucket, ADMIN_TOKEN: "test-token" };
  const headers = { authorization: "Bearer test-token", "content-type": "application/json" };
  const publish = await worker.fetch(new Request("https://updates.example/admin/releases", {
    method: "POST", headers, body: JSON.stringify({ tag: "v0.7.0" }),
  }), env);
  assert.equal(publish.status, 200);
  assert.equal((await (await check(bucket)).json()).latestVersion, "0.7.0");
  const patch = await worker.fetch(new Request("https://updates.example/admin/releases/v0.7.0", {
    method: "PATCH", headers, body: JSON.stringify({ releaseNotes: "corrected" }),
  }), env);
  assert.equal(patch.status, 200);
  assert.equal((await (await check(bucket)).json()).releaseNotes, "corrected");
  const remove = await worker.fetch(new Request("https://updates.example/admin/releases/v0.7.0", {
    method: "DELETE", headers,
  }), env);
  assert.equal(remove.status, 200);
  assert.equal((await (await check(bucket)).json()).latestVersion, "0.6.4");
});
