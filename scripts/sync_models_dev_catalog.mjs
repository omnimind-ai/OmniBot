#!/usr/bin/env node

import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const DEFAULT_UPSTREAM_URL = "https://models.dev/api.json";
const DEFAULT_R2_PREFIX = "metadata/models-dev";
const DEFAULT_REQUIRED_PROVIDERS = [
  "openai",
  "anthropic",
  "google",
  "openrouter",
  "deepseek",
  "alibaba",
];

class AwsCliR2Client {
  constructor({
    bucket,
    endpoint,
    command = "aws",
    processEnv = process.env,
  }) {
    this.bucket = bucket;
    this.endpoint = endpoint;
    this.command = command;
    this.processEnv = {
      ...processEnv,
      AWS_DEFAULT_REGION: processEnv.AWS_DEFAULT_REGION || "auto",
      AWS_REGION: processEnv.AWS_REGION || "auto",
    };
  }

  async exists(key) {
    try {
      await this.#run([
        "head-object",
        "--bucket",
        this.bucket,
        "--key",
        key,
      ]);
      return true;
    } catch (error) {
      if (isMissingObjectError(error)) return false;
      throw error;
    }
  }

  async getText(key) {
    if (!(await this.exists(key))) return null;
    return await this.#withTempFile(async (filePath) => {
      await this.#run([
        "get-object",
        "--bucket",
        this.bucket,
        "--key",
        key,
      ], { outfile: filePath });
      return await readFile(filePath, "utf8");
    });
  }

  async putText(key, value, {
    contentType = "application/octet-stream",
    cacheControl = "no-store",
    metadata = {},
  } = {}) {
    await this.#withTempFile(async (filePath) => {
      await writeFile(filePath, value);
      const args = [
        "put-object",
        "--bucket",
        this.bucket,
        "--key",
        key,
        "--body",
        filePath,
        "--content-type",
        contentType,
        "--cache-control",
        cacheControl,
      ];
      if (Object.keys(metadata).length > 0) {
        args.push("--metadata", JSON.stringify(stringMetadata(metadata)));
      }
      await this.#run(args);
    });
  }

  async list(prefix) {
    const objects = [];
    let continuationToken = "";
    do {
      const args = [
        "list-objects-v2",
        "--bucket",
        this.bucket,
        "--prefix",
        prefix,
        "--no-paginate",
      ];
      if (continuationToken) {
        args.push("--continuation-token", continuationToken);
      }
      const result = await this.#run(args);
      const payload = result.stdout.trim()
        ? JSON.parse(result.stdout)
        : {};
      for (const object of payload.Contents || []) {
        objects.push({
          key: String(object.Key || ""),
          uploaded: object.LastModified || "",
          size: Number(object.Size || 0),
        });
      }
      continuationToken = payload.IsTruncated
        ? String(payload.NextContinuationToken || "")
        : "";
      if (payload.IsTruncated && !continuationToken) {
        throw new Error("R2 listing was truncated without a continuation token");
      }
    } while (continuationToken);
    return objects;
  }

  async delete(key) {
    await this.#run([
      "delete-object",
      "--bucket",
      this.bucket,
      "--key",
      key,
    ]);
  }

  async #run(args, { outfile = "" } = {}) {
    try {
      const commandArgs = [
        "s3api",
        ...args,
        "--endpoint-url",
        this.endpoint,
        "--region",
        "auto",
        "--output",
        "json",
      ];
      if (outfile) commandArgs.push(outfile);
      return await execFileAsync(
        this.command,
        commandArgs,
        {
          env: this.processEnv,
          maxBuffer: 16 * 1024 * 1024,
        },
      );
    } catch (error) {
      const stderr = String(error.stderr || "").trim();
      const detail = stderr || error.message || String(error);
      const wrapped = new Error(`AWS CLI R2 operation failed: ${detail}`);
      wrapped.cause = error;
      wrapped.stderr = stderr;
      throw wrapped;
    }
  }

  async #withTempFile(callback) {
    const directory = await mkdtemp(path.join(os.tmpdir(), "models-dev-r2-"));
    const filePath = path.join(directory, "object");
    try {
      return await callback(filePath);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  }
}

async function syncModelsDevCatalog({
  config,
  r2,
  fetchImpl = fetch,
  now = () => Date.now(),
  logger = console,
}) {
  const keys = modelsDevKeys(config.r2Prefix);
  const checkedAt = now();
  const [currentExists, previousStatusText] = await Promise.all([
    r2.exists(keys.current),
    r2.getText(keys.status),
  ]);
  const previousStatus = parsePreviousStatus(previousStatusText);

  if (currentExists && !previousStatus && !config.force) {
    throw new Error(
      "The R2 mirror has current.json but no valid status.json; run the workflow manually with force enabled after reviewing the current object",
    );
  }

  try {
    const upstream = await fetchModelsDevCatalog({
      config,
      currentExists,
      previousStatus,
      fetchImpl,
    });

    if (upstream.notModified) {
      if (!currentExists || !previousStatus) {
        throw new Error("models.dev returned 304 before the R2 mirror was initialized");
      }
      const status = {
        ...previousStatus,
        schemaVersion: 1,
        publisher: "github-actions",
        workflowRunUrl: config.workflowRunUrl,
        lastCheckedAt: checkedAt,
        lastSuccessfulAt: checkedAt,
        changed: false,
        consecutiveFailures: 0,
        lastError: "",
        upstreamUrl: config.upstreamUrl,
        upstreamEtag: upstream.etag || previousStatus.upstreamEtag || "",
      };
      await writeStatus(r2, keys.status, status);
      return syncResult(status, { notModified: true });
    }

    const validation = validateModelsDevCatalog(upstream.payload, {
      config,
      previousStatus,
      force: config.force,
    });
    const sha256 = createHash("sha256")
      .update(upstream.payload, "utf8")
      .digest("hex");
    const changed = !currentExists || sha256 !== String(previousStatus?.sha256 || "");
    const publishedAt = now();
    const metadata = {
      sha256,
      upstreametag: upstream.etag,
      fetchedat: publishedAt,
      providercount: validation.providerCount,
      modelcount: validation.modelCount,
      size: validation.size,
      sourceurl: config.upstreamUrl,
    };

    if (changed) {
      await r2.putText(keys.snapshot(sha256), upstream.payload, {
        contentType: "application/json; charset=utf-8",
        cacheControl: "public, max-age=31536000, immutable",
        metadata,
      });
      await r2.putText(keys.current, upstream.payload, {
        contentType: "application/json; charset=utf-8",
        cacheControl: "public, max-age=3600",
        metadata,
      });
    }

    const status = {
      schemaVersion: 1,
      publisher: "github-actions",
      workflowRunUrl: config.workflowRunUrl,
      lastCheckedAt: checkedAt,
      lastSuccessfulAt: publishedAt,
      lastChangedAt: changed
        ? publishedAt
        : positiveInteger(previousStatus?.lastChangedAt),
      changed,
      consecutiveFailures: 0,
      lastError: "",
      upstreamUrl: config.upstreamUrl,
      upstreamEtag: upstream.etag,
      sha256,
      providerCount: validation.providerCount,
      modelCount: validation.modelCount,
      size: validation.size,
    };
    await writeStatus(r2, keys.status, status);

    if (changed) {
      try {
        await cleanupSnapshots(r2, keys, {
          keep: config.snapshotRetention,
          currentSha256: sha256,
        });
      } catch (error) {
        logger.warn(`Snapshot cleanup failed: ${error.message || error}`);
      }
    }

    return syncResult(status, { notModified: false });
  } catch (error) {
    const failureStatus = {
      ...(previousStatus || {}),
      schemaVersion: 1,
      publisher: "github-actions",
      workflowRunUrl: config.workflowRunUrl,
      lastCheckedAt: checkedAt,
      changed: false,
      consecutiveFailures: positiveInteger(previousStatus?.consecutiveFailures) + 1,
      lastError: error.message || String(error),
      upstreamUrl: config.upstreamUrl,
    };
    try {
      await writeStatus(r2, keys.status, failureStatus);
    } catch (statusError) {
      logger.error(`Could not record sync failure in R2: ${statusError.message || statusError}`);
    }
    throw error;
  }
}

async function fetchModelsDevCatalog({
  config,
  currentExists,
  previousStatus,
  fetchImpl,
}) {
  const canUseEtag = currentExists &&
    previousStatus?.upstreamUrl === config.upstreamUrl &&
    String(previousStatus?.upstreamEtag || "");
  let response = await fetchWithTimeout(
    config.upstreamUrl,
    upstreamRequestInit(canUseEtag || ""),
    config.timeoutMs,
    fetchImpl,
  );

  if (response.status === 304 && !currentExists) {
    response = await fetchWithTimeout(
      config.upstreamUrl,
      upstreamRequestInit(""),
      config.timeoutMs,
      fetchImpl,
    );
  }
  if (response.status === 304) {
    return {
      notModified: true,
      etag: response.headers.get("etag") || String(canUseEtag || ""),
    };
  }
  if (!response.ok) {
    throw new Error(
      `models.dev fetch failed (${response.status}): ${response.body.slice(0, 300)}`,
    );
  }

  const contentType = String(response.headers.get("content-type") || "").toLowerCase();
  if (!contentType.includes("json")) {
    throw new Error(
      `models.dev returned unexpected content type: ${contentType || "missing"}`,
    );
  }
  return {
    notModified: false,
    etag: String(response.headers.get("etag") || ""),
    payload: response.body,
  };
}

async function fetchWithTimeout(url, init, timeoutMs, fetchImpl) {
  const controller = new AbortController();
  const timeout = setTimeout(
    () => controller.abort(new Error("models.dev fetch timed out")),
    timeoutMs,
  );
  try {
    const response = await fetchImpl(url, {
      ...init,
      signal: controller.signal,
    });
    return {
      status: response.status,
      ok: response.ok,
      headers: response.headers,
      body: response.status === 304 ? "" : await response.text(),
    };
  } finally {
    clearTimeout(timeout);
  }
}

function upstreamRequestInit(etag) {
  const headers = {
    accept: "application/json",
    "cache-control": "no-cache",
    "user-agent": "OpenOmniBot-model-catalog-sync/1.0",
  };
  if (etag) headers["if-none-match"] = etag;
  return {
    method: "GET",
    headers,
    redirect: "follow",
  };
}

function validateModelsDevCatalog(payload, {
  config,
  previousStatus = null,
  force = false,
}) {
  const size = Buffer.byteLength(payload, "utf8");
  if (size < config.minBytes || size > config.maxBytes) {
    throw new Error(
      `models.dev payload size ${size} is outside ${config.minBytes}-${config.maxBytes} bytes`,
    );
  }

  let decoded;
  try {
    decoded = JSON.parse(payload);
  } catch {
    throw new Error("models.dev returned invalid JSON");
  }
  if (!decoded || typeof decoded !== "object" || Array.isArray(decoded)) {
    throw new Error("models.dev catalog root must be an object");
  }

  let providerCount = 0;
  let modelCount = 0;
  for (const provider of Object.values(decoded)) {
    if (!provider || typeof provider !== "object" || Array.isArray(provider)) continue;
    if (!provider.models || typeof provider.models !== "object" || Array.isArray(provider.models)) continue;
    providerCount += 1;
    modelCount += Object.keys(provider.models).length;
  }

  if (providerCount < config.minProviders) {
    throw new Error(
      `models.dev provider count ${providerCount} is below ${config.minProviders}`,
    );
  }
  if (modelCount < config.minModels) {
    throw new Error(
      `models.dev model count ${modelCount} is below ${config.minModels}`,
    );
  }
  for (const providerId of config.requiredProviders) {
    const provider = decoded[providerId];
    if (!provider || typeof provider !== "object" || !provider.models) {
      throw new Error(`models.dev catalog is missing required provider: ${providerId}`);
    }
  }

  const previousProviderCount = positiveInteger(previousStatus?.providerCount);
  const previousModelCount = positiveInteger(previousStatus?.modelCount);
  if (!force && previousProviderCount > 0) {
    const minimum = Math.floor(previousProviderCount * (1 - config.maxDropRatio));
    if (providerCount < minimum) {
      throw new Error(
        `models.dev provider count dropped from ${previousProviderCount} to ${providerCount}; run the workflow manually with force enabled after review`,
      );
    }
  }
  if (!force && previousModelCount > 0) {
    const minimum = Math.floor(previousModelCount * (1 - config.maxDropRatio));
    if (modelCount < minimum) {
      throw new Error(
        `models.dev model count dropped from ${previousModelCount} to ${modelCount}; run the workflow manually with force enabled after review`,
      );
    }
  }

  return { providerCount, modelCount, size };
}

async function cleanupSnapshots(r2, keys, { keep, currentSha256 }) {
  const snapshots = await r2.list(keys.snapshotsPrefix);
  snapshots.sort((left, right) =>
    new Date(right.uploaded || 0).getTime() -
    new Date(left.uploaded || 0).getTime()
  );
  const retained = new Set([
    keys.snapshot(currentSha256),
    ...snapshots.slice(0, keep).map((object) => object.key),
  ]);
  for (const object of snapshots) {
    if (!retained.has(object.key)) {
      await r2.delete(object.key);
    }
  }
}

async function writeStatus(r2, key, status) {
  await r2.putText(key, `${JSON.stringify(status, null, 2)}\n`, {
    contentType: "application/json; charset=utf-8",
    cacheControl: "no-store",
  });
}

function parsePreviousStatus(text) {
  if (!text) return null;
  try {
    const value = JSON.parse(text);
    return value && typeof value === "object" && !Array.isArray(value)
      ? value
      : null;
  } catch {
    return null;
  }
}

function modelsDevKeys(prefix) {
  const normalized = String(prefix || DEFAULT_R2_PREFIX)
    .replace(/^\/+|\/+$/g, "");
  return {
    current: `${normalized}/current.json`,
    status: `${normalized}/status.json`,
    snapshotsPrefix: `${normalized}/snapshots/`,
    snapshot: (sha256) => `${normalized}/snapshots/${sha256}.json`,
  };
}

async function syncModelsDevCatalogToBackend({
  config,
  fetchImpl = fetch,
  now = () => Date.now(),
  logger = console,
}) {
  const base = config.backendUrl.replace(/\/+$/g, "");
  const endpoint = `${base}/api/admin/models-dev`;
  const authorization = { authorization: `Bearer ${config.backendToken}` };
  const adminRequest = async (url, init = {}) => {
    const response = await fetchImpl(url, {
      ...init,
      headers: { ...authorization, ...init.headers },
      signal: AbortSignal.timeout(config.timeoutMs),
    });
    if (!response.ok) {
      throw new Error(`Website catalog API returned HTTP ${response.status}`);
    }
    return response;
  };
  const catalog = await (await adminRequest(endpoint)).json();
  const currentExists = Boolean(catalog.current);
  const previousStatus = currentExists || catalog.refresh
    ? { ...(catalog.refresh || {}), ...(catalog.current || {}) }
    : null;
  const checkedAt = now();
  const writeRefreshStatus = async (status) => {
    await adminRequest(`${endpoint}/status`, {
      method: "PUT",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(status),
    });
  };
  try {
    const upstream = await fetchModelsDevCatalog({
      config, currentExists, previousStatus, fetchImpl,
    });
    if (upstream.notModified) {
      if (!currentExists || !previousStatus) {
        throw new Error("models.dev returned 304 before the website mirror was initialized");
      }
      const status = {
        ...previousStatus,
        lastCheckedAt: checkedAt,
        lastSuccessfulAt: checkedAt,
        changed: false,
        consecutiveFailures: 0,
        lastError: "",
        upstreamUrl: config.upstreamUrl,
        upstreamEtag: upstream.etag || previousStatus.upstreamEtag || "",
      };
      await writeRefreshStatus(status);
      return syncResult(status, { notModified: true });
    }
    const validation = validateModelsDevCatalog(upstream.payload, {
      config, previousStatus, force: config.force,
    });
    const sha256 = createHash("sha256").update(upstream.payload, "utf8").digest("hex");
    const changed = !currentExists || sha256 !== String(previousStatus?.sha256 || "");
    const publishedAt = now();
    await adminRequest(endpoint, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-models-dev-sha256": sha256,
        "x-models-dev-provider-count": String(validation.providerCount),
        "x-models-dev-model-count": String(validation.modelCount),
        "x-models-dev-fetched-at": String(publishedAt),
        "x-models-dev-upstream-etag": upstream.etag || "",
        "x-models-dev-upstream-url": config.upstreamUrl,
        "x-models-dev-force": String(config.force),
      },
      body: upstream.payload,
    });
    return syncResult({
      ...validation,
      lastCheckedAt: checkedAt,
      lastSuccessfulAt: publishedAt,
      sha256,
      upstreamEtag: upstream.etag,
      changed,
    }, { notModified: false });
  } catch (error) {
    try {
      await writeRefreshStatus({
        ...(previousStatus || {}),
        lastCheckedAt: checkedAt,
        changed: false,
        consecutiveFailures: positiveInteger(previousStatus?.consecutiveFailures) + 1,
        lastError: error.message || String(error),
        upstreamUrl: config.upstreamUrl,
      });
    } catch (statusError) {
      logger.error(`Could not record sync failure on website: ${statusError.message || statusError}`);
    }
    throw error;
  }
}

function loadConfig(env = process.env) {
  const storage = String(env.MODELS_DEV_STORAGE || "backend").trim();
  if (!["backend", "r2"].includes(storage)) throw new Error("MODELS_DEV_STORAGE must be backend or r2");
  let bucket = "", endpoint = "", backendUrl = "", backendToken = "";
  if (storage === "r2") {
    const accountId = String(env.CLOUDFLARE_ACCOUNT_ID || "").trim();
    bucket = requiredValue(env.CLOUDFLARE_R2_BUCKET_NAME, "CLOUDFLARE_R2_BUCKET_NAME");
    endpoint = String(env.CLOUDFLARE_R2_ENDPOINT || "").trim() ||
      `https://${requiredValue(accountId, "CLOUDFLARE_ACCOUNT_ID")}.r2.cloudflarestorage.com`;
    httpsUrl(endpoint, "CLOUDFLARE_R2_ENDPOINT");
    requiredValue(env.AWS_ACCESS_KEY_ID, "AWS_ACCESS_KEY_ID");
    requiredValue(env.AWS_SECRET_ACCESS_KEY, "AWS_SECRET_ACCESS_KEY");
  } else {
    backendUrl = httpsUrl(
      String(env.OMNIBOT_BACKEND_URL || "https://omnibot.omnimind.com.cn"),
      "OMNIBOT_BACKEND_URL",
    ).replace(/\/+$/g, "");
    backendToken = requiredValue(
      env.OMNIBOT_BACKEND_TOKEN || env.APP_UPDATE_WORKER_TOKEN,
      "OMNIBOT_BACKEND_TOKEN or APP_UPDATE_WORKER_TOKEN",
    );
  }
  const upstreamUrl = httpsUrl(
    String(env.MODELS_DEV_UPSTREAM_URL || DEFAULT_UPSTREAM_URL),
    "MODELS_DEV_UPSTREAM_URL",
  );
  return {
    storage,
    backendUrl,
    backendToken,
    bucket,
    endpoint,
    upstreamUrl,
    r2Prefix: String(env.MODELS_DEV_R2_PREFIX || DEFAULT_R2_PREFIX)
      .replace(/^\/+|\/+$/g, ""),
    minBytes: integerValue(env.MODELS_DEV_MIN_BYTES, 1, 50_000_000, 100_000),
    maxBytes: integerValue(env.MODELS_DEV_MAX_BYTES, 1, 50_000_000, 10_000_000),
    minProviders: integerValue(env.MODELS_DEV_MIN_PROVIDERS, 1, 10_000, 50),
    minModels: integerValue(env.MODELS_DEV_MIN_MODELS, 1, 1_000_000, 1_000),
    maxDropRatio: ratioValue(env.MODELS_DEV_MAX_DROP_RATIO, 0.35),
    timeoutMs: integerValue(env.MODELS_DEV_REFRESH_TIMEOUT_MS, 1_000, 300_000, 60_000),
    snapshotRetention: integerValue(env.MODELS_DEV_SNAPSHOT_RETENTION, 1, 100, 5),
    requiredProviders: String(env.MODELS_DEV_REQUIRED_PROVIDERS || "")
      .split(",")
      .map((value) => value.trim().toLowerCase())
      .filter(Boolean)
      .concat(
        String(env.MODELS_DEV_REQUIRED_PROVIDERS || "").trim()
          ? []
          : DEFAULT_REQUIRED_PROVIDERS,
      ),
    force: parseBoolean(env.MODELS_DEV_FORCE),
    workflowRunUrl: workflowRunUrl(env),
  };
}

function workflowRunUrl(env) {
  const server = String(env.GITHUB_SERVER_URL || "").replace(/\/+$/g, "");
  const repository = String(env.GITHUB_REPOSITORY || "");
  const runId = String(env.GITHUB_RUN_ID || "");
  return server && repository && runId
    ? `${server}/${repository}/actions/runs/${runId}`
    : "";
}

function syncResult(status, { notModified }) {
  return {
    changed: Boolean(status.changed),
    notModified,
    checkedAt: positiveInteger(status.lastCheckedAt),
    successfulAt: positiveInteger(status.lastSuccessfulAt),
    sha256: String(status.sha256 || ""),
    upstreamEtag: String(status.upstreamEtag || ""),
    providerCount: positiveInteger(status.providerCount),
    modelCount: positiveInteger(status.modelCount),
    size: positiveInteger(status.size),
  };
}

function stringMetadata(metadata) {
  return Object.fromEntries(
    Object.entries(metadata)
      .filter(([, value]) => value !== null && value !== undefined && String(value) !== "")
      .map(([key, value]) => [key.toLowerCase(), String(value)]),
  );
}

function isMissingObjectError(error) {
  const detail = `${error.stderr || ""}\n${error.message || ""}`;
  return /\b(404|NoSuchKey|Not Found)\b/i.test(detail);
}

function positiveInteger(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? Math.trunc(number) : 0;
}

function integerValue(value, min, max, fallback) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

function ratioValue(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 && parsed < 1
    ? parsed
    : fallback;
}

function parseBoolean(value) {
  return ["1", "true", "yes", "on"].includes(
    String(value || "").trim().toLowerCase(),
  );
}

function requiredValue(value, name) {
  const normalized = String(value || "").trim();
  if (!normalized) throw new Error(`${name} is required`);
  return normalized;
}

function httpsUrl(value, name) {
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`${name} must be a valid URL`);
  }
  if (parsed.protocol !== "https:") {
    throw new Error(`${name} must use HTTPS`);
  }
  return parsed.toString();
}

async function main() {
  const config = loadConfig();
  const result = config.storage === "r2"
    ? await syncModelsDevCatalog({
      config,
      r2: new AwsCliR2Client({ bucket: config.bucket, endpoint: config.endpoint }),
    })
    : await syncModelsDevCatalogToBackend({ config });
  console.log(JSON.stringify(result, null, 2));
}

const isDirectExecution = process.argv[1] &&
  pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url;
if (isDirectExecution) {
  main().catch((error) => {
    console.error(error.stack || error.message || String(error));
    process.exitCode = 1;
  });
}

export {
  AwsCliR2Client,
  cleanupSnapshots,
  fetchModelsDevCatalog,
  loadConfig,
  modelsDevKeys,
  syncModelsDevCatalog,
  syncModelsDevCatalogToBackend,
  validateModelsDevCatalog,
};
