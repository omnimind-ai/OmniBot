import ADMIN_HTML from "./admin-ui.js";

const DEFAULT_GITHUB_REPO = "omnimind-ai/OpenOmniBot";
const DEFAULT_EDITIONS = ["standard"];
const DEFAULT_R2_RELEASES_PREFIX = "releases";
const DEFAULT_R2_METADATA_PREFIX = "metadata/releases";
const DEFAULT_ANALYTICS_DATASET = "omnibot_app_updates";
const DEFAULT_MODELS_DEV_R2_PREFIX = "metadata/models-dev";
const MODELS_DEV_PUBLIC_PATH = "/catalog/models-dev/api.json";
const ADMIN_MODELS_DEV_PATH = "/admin/models-dev";
const ADMIN_CLOUD_SERVICE_POLICY_PATH = "/admin/cloud-service-policy";
const COMMUNITY_WECHAT_QR_PUBLIC_PATH = "/community/wechat-qr";
const ADMIN_COMMUNITY_WECHAT_QR_PATH = "/admin/community/wechat-qr";
const CLOUD_SERVICE_POLICY_OBJECT_KEY = "metadata/config/cloud-service-policy.json";
const COMMUNITY_WECHAT_QR_OBJECT_KEY = "metadata/community/wechat-qr";
const DOWNLOAD_ROUTE_PREFIX = "/downloads/";
const ADMIN_RELEASE_ROUTE_PREFIX = "/admin/releases/";
const ADMIN_ANALYTICS_ROUTE_PREFIX = "/admin/analytics/";
const VLM_RESPONSES_PATH = "/v1/responses";
const VLM_CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
const MAX_VLM_REQUEST_BYTES = 8 * 1024 * 1024;
const MAX_COMMUNITY_QR_BYTES = 5 * 1024 * 1024;
const ANALYTICS_RETENTION_DAYS = 90;
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "access-control-allow-origin": "*",
};

const worker = {
  async fetch(request, env) {
    const url = new URL(request.url);
    const pathname = normalizePath(url.pathname);

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          ...JSON_HEADERS,
          "access-control-allow-methods": "GET,HEAD,POST,PUT,PATCH,DELETE,OPTIONS",
          "access-control-allow-headers": "authorization,content-type,if-none-match,x-content-sha256,x-content-size,x-update-token",
        },
      });
    }

    try {
      if ((request.method === "GET" || request.method === "HEAD") && pathname.startsWith(DOWNLOAD_ROUTE_PREFIX)) {
        return await handleDownloadAsset(request, url, env);
      }

      if (request.method === "GET" && pathname === "/") {
        return json({
          ok: true,
          service: "omnibot-app-update-worker",
          storage: "r2",
          routes: [
            "/updates",
            VLM_RESPONSES_PATH,
            VLM_CHAT_COMPLETIONS_PATH,
            MODELS_DEV_PUBLIC_PATH,
            COMMUNITY_WECHAT_QR_PUBLIC_PATH,
            "/downloads/:tag/:asset",
            "/admin",
            "/admin/api/session",
            ADMIN_MODELS_DEV_PATH,
            ADMIN_CLOUD_SERVICE_POLICY_PATH,
            ADMIN_COMMUNITY_WECHAT_QR_PATH,
            "/admin/releases",
            "/admin/releases/:tag",
            "/admin/releases/:tag/assets/:asset",
            "/admin/analytics/:metric",
          ],
        });
      }

      if (request.method === "GET" && pathname === "/updates") {
        return await handleUpdateCheck(request, url, env);
      }

      if (
        request.method === "POST" &&
        (pathname === VLM_RESPONSES_PATH || pathname === VLM_CHAT_COMPLETIONS_PATH)
      ) {
        return await handleOfficialVlmProxy(request, pathname, env);
      }

      if (
        (request.method === "GET" || request.method === "HEAD") &&
        pathname === MODELS_DEV_PUBLIC_PATH
      ) {
        return await handleModelsDevCatalog(request, env);
      }

      if (
        (request.method === "GET" || request.method === "HEAD") &&
        pathname === COMMUNITY_WECHAT_QR_PUBLIC_PATH
      ) {
        return await handleCommunityWechatQr(request, env);
      }

      if (request.method === "GET" && pathname === "/admin") {
        return adminPage();
      }

      if (pathname === "/admin/api/session" && request.method === "GET") {
        requireAdmin(request, env);
        return json({ ok: true, authorized: true, analyticsConfigured: isAnalyticsQueryConfigured(env) });
      }

      if (pathname === ADMIN_MODELS_DEV_PATH && request.method === "GET") {
        requireAdmin(request, env);
        return await handleModelsDevStatus(env);
      }

      if (
        pathname === ADMIN_CLOUD_SERVICE_POLICY_PATH &&
        (request.method === "GET" || request.method === "PUT")
      ) {
        requireAdmin(request, env);
        return request.method === "GET"
          ? await handleGetCloudServicePolicy(env)
          : await handlePutCloudServicePolicy(request, env);
      }

      if (
        pathname === ADMIN_COMMUNITY_WECHAT_QR_PATH &&
        (request.method === "GET" || request.method === "PUT")
      ) {
        requireAdmin(request, env);
        return request.method === "GET"
          ? await handleGetCommunityWechatQr(request, env)
          : await handlePutCommunityWechatQr(request, env);
      }

      if (request.method === "GET" && pathname.startsWith(ADMIN_ANALYTICS_ROUTE_PREFIX)) {
        requireAdmin(request, env);
        return await handleAnalyticsQuery(pathname, url, env);
      }

      if (pathname === "/admin/releases" && request.method === "GET") {
        requireAdmin(request, env);
        return await handleListReleases(env);
      }

      if (pathname === "/admin/releases" && request.method === "POST") {
        requireAdmin(request, env);
        return await handleUpsertRelease(request, env);
      }

      if (
        (request.method === "POST" || request.method === "PUT" || request.method === "DELETE") &&
        pathname.startsWith(ADMIN_RELEASE_ROUTE_PREFIX) &&
        pathname.includes("/assets/")
      ) {
        requireAdmin(request, env);
        return await handleAssetMutation(request, url, env);
      }

      if (
        pathname.startsWith(ADMIN_RELEASE_ROUTE_PREFIX) &&
        !pathname.includes("/assets/") &&
        request.method === "GET"
      ) {
        requireAdmin(request, env);
        return await handleGetRelease(decodeURIComponent(pathname.slice(ADMIN_RELEASE_ROUTE_PREFIX.length)), env);
      }

      if (
        pathname.startsWith(ADMIN_RELEASE_ROUTE_PREFIX) &&
        !pathname.includes("/assets/") &&
        request.method === "PATCH"
      ) {
        requireAdmin(request, env);
        return await handlePatchRelease(request, decodeURIComponent(pathname.slice(ADMIN_RELEASE_ROUTE_PREFIX.length)), env);
      }

      if (pathname === "/admin/releases" && request.method === "DELETE") {
        requireAdmin(request, env);
        return await handleDeleteRelease(url.searchParams.get("tag"), env);
      }

      if (pathname.startsWith(ADMIN_RELEASE_ROUTE_PREFIX) && request.method === "DELETE") {
        requireAdmin(request, env);
        return await handleDeleteRelease(decodeURIComponent(pathname.slice(ADMIN_RELEASE_ROUTE_PREFIX.length)), env);
      }

      return json({ ok: false, error: "Not found" }, 404);
    } catch (error) {
      const status = Number.isInteger(error.status) ? error.status : 500;
      return json({ ok: false, error: error.message || "Internal error" }, status);
    }
  },
};

export default worker;

function adminPage() {
  return new Response(ADMIN_HTML, {
    status: 200,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-cache",
      "referrer-policy": "no-referrer",
      "x-frame-options": "DENY",
    },
  });
}

async function handleCommunityWechatQr(request, env) {
  const bucket = requireBucket(env);
  const object = request.method === "HEAD"
    ? await bucket.head(COMMUNITY_WECHAT_QR_OBJECT_KEY)
    : await bucket.get(COMMUNITY_WECHAT_QR_OBJECT_KEY);
  if (!object) {
    return json({ ok: false, error: "WeChat group QR code has not been uploaded" }, 404);
  }

  const etag = quoteEtag(object.etag);
  const headers = communityWechatQrHeaders(object, etag);
  if (etag && requestEtagMatches(request.headers.get("if-none-match"), etag)) {
    return new Response(null, { status: 304, headers });
  }
  return new Response(request.method === "HEAD" ? null : object.body, {
    status: 200,
    headers,
  });
}

async function handleGetCommunityWechatQr(request, env) {
  const object = await requireBucket(env).head(COMMUNITY_WECHAT_QR_OBJECT_KEY);
  return json({
    ok: true,
    image: communityWechatQrStatus(object, new URL(request.url)),
  });
}

async function handlePutCommunityWechatQr(request, env) {
  const declaredSize = normalizeSize(request.headers.get("content-length"));
  if (declaredSize > MAX_COMMUNITY_QR_BYTES) {
    throw httpError(413, "QR image must be 5 MB or smaller");
  }

  const bytes = new Uint8Array(await request.arrayBuffer());
  if (!bytes.byteLength) {
    throw httpError(400, "QR image body is required");
  }
  if (bytes.byteLength > MAX_COMMUNITY_QR_BYTES) {
    throw httpError(413, "QR image must be 5 MB or smaller");
  }

  const contentType = detectCommunityQrContentType(bytes);
  if (!contentType) {
    throw httpError(415, "QR image must be a JPEG, PNG, or WebP file");
  }
  const requestedContentType = stringValue(request.headers.get("content-type"))
    .split(";", 1)[0]
    .trim()
    .toLowerCase();
  if (
    requestedContentType &&
    requestedContentType !== "application/octet-stream" &&
    requestedContentType !== contentType
  ) {
    throw httpError(415, `Image bytes are ${contentType}, not ${requestedContentType}`);
  }

  const bucket = requireBucket(env);
  const uploadedAt = Date.now();
  const uploaded = await bucket.put(COMMUNITY_WECHAT_QR_OBJECT_KEY, bytes, {
    httpMetadata: {
      contentType,
      cacheControl: "public, no-cache, max-age=0, must-revalidate",
    },
    customMetadata: {
      uploadedAt: String(uploadedAt),
    },
  });
  const object = uploaded || await bucket.head(COMMUNITY_WECHAT_QR_OBJECT_KEY);
  return json({
    ok: true,
    image: communityWechatQrStatus(object, new URL(request.url), {
      contentType,
      size: bytes.byteLength,
      uploadedAt,
    }),
  });
}

function communityWechatQrStatus(object, url, fallback = {}) {
  if (!object) {
    return {
      configured: false,
      publicUrl: `${url.origin}${COMMUNITY_WECHAT_QR_PUBLIC_PATH}`,
    };
  }
  const metadata = object.customMetadata || {};
  return {
    configured: true,
    publicUrl: `${url.origin}${COMMUNITY_WECHAT_QR_PUBLIC_PATH}`,
    contentType: stringValue(object.httpMetadata?.contentType) || fallback.contentType || "image/jpeg",
    size: normalizeSize(object.size) || fallback.size || 0,
    etag: quoteEtag(object.etag),
    uploadedAt: normalizeSize(metadata.uploadedAt ?? metadata.uploadedat) || fallback.uploadedAt || 0,
  };
}

function communityWechatQrHeaders(object, etag) {
  const headers = new Headers({
    "content-type": stringValue(object.httpMetadata?.contentType) || "image/jpeg",
    "cache-control": "public, no-cache, max-age=0, must-revalidate",
    "access-control-allow-origin": "*",
    "access-control-expose-headers": "etag",
    "cross-origin-resource-policy": "cross-origin",
    "x-content-type-options": "nosniff",
  });
  if (etag) headers.set("etag", etag);
  return headers;
}

function detectCommunityQrContentType(bytes) {
  if (
    bytes.length >= 3 &&
    bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff
  ) return "image/jpeg";
  if (
    bytes.length >= 8 &&
    bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47 &&
    bytes[4] === 0x0d && bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a
  ) return "image/png";
  if (
    bytes.length >= 12 &&
    bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x46 &&
    bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50
  ) return "image/webp";
  return "";
}

async function handleModelsDevCatalog(request, env) {
  const bucket = requireBucket(env);
  const key = modelsDevCurrentObjectKey(env);
  const object = request.method === "HEAD"
    ? await bucket.head(key)
    : await bucket.get(key);

  if (!object) {
    return json({
      ok: false,
      error: "Models.dev catalog mirror has not been initialized",
    }, 503);
  }

  const metadata = object.customMetadata || {};
  const sha256 = modelsDevMetadataValue(metadata, "sha256");
  const etag = sha256 ? `"${sha256}"` : quoteEtag(object.etag);
  const headers = modelsDevResponseHeaders(metadata, etag);

  if (etag && requestEtagMatches(request.headers.get("if-none-match"), etag)) {
    return new Response(null, { status: 304, headers });
  }

  return new Response(request.method === "HEAD" ? null : object.body, {
    status: 200,
    headers,
  });
}

async function handleModelsDevStatus(env) {
  const bucket = requireBucket(env);
  const [current, refresh] = await Promise.all([
    bucket.head(modelsDevCurrentObjectKey(env)),
    readModelsDevRefreshStatus(bucket, env),
  ]);
  const metadata = current?.customMetadata || {};

  return json({
    ok: true,
    configured: true,
    publicPath: MODELS_DEV_PUBLIC_PATH,
    upstreamUrl: stringValue(refresh?.upstreamUrl),
    current: current ? {
      key: current.key || modelsDevCurrentObjectKey(env),
      size: current.size ||
        normalizeSize(modelsDevMetadataValue(metadata, "size")) ||
        normalizeSize(refresh?.size),
      upstreamEtag: modelsDevMetadataValue(metadata, "upstreamEtag") ||
        stringValue(refresh?.upstreamEtag),
      sha256: modelsDevMetadataValue(metadata, "sha256") ||
        stringValue(refresh?.sha256),
      fetchedAt: normalizeSize(modelsDevMetadataValue(metadata, "fetchedAt")) ||
        normalizeSize(refresh?.lastSuccessfulAt),
      providerCount:
        normalizeSize(modelsDevMetadataValue(metadata, "providerCount")) ||
        normalizeSize(refresh?.providerCount),
      modelCount:
        normalizeSize(modelsDevMetadataValue(metadata, "modelCount")) ||
        normalizeSize(refresh?.modelCount),
    } : null,
    refresh,
  });
}

function modelsDevR2Prefix(env) {
  return stringValue(env.MODELS_DEV_R2_PREFIX).replace(/^\/+|\/+$/g, "") ||
    DEFAULT_MODELS_DEV_R2_PREFIX;
}

function modelsDevCurrentObjectKey(env) {
  return `${modelsDevR2Prefix(env)}/current.json`;
}

function modelsDevStatusObjectKey(env) {
  return `${modelsDevR2Prefix(env)}/status.json`;
}

async function readModelsDevRefreshStatus(bucket, env) {
  const object = await bucket.get(modelsDevStatusObjectKey(env));
  if (!object) return null;
  try {
    return JSON.parse(await object.text());
  } catch {
    return null;
  }
}

function modelsDevResponseHeaders(metadata, etag) {
  const headers = new Headers({
    "content-type": "application/json; charset=utf-8",
    "cache-control": "public, max-age=3600, stale-while-revalidate=86400, stale-if-error=604800",
    "access-control-allow-origin": "*",
    "access-control-expose-headers": "etag,x-models-dev-fetched-at,x-models-dev-provider-count,x-models-dev-model-count",
    "x-content-type-options": "nosniff",
  });
  if (etag) headers.set("etag", etag);
  const fetchedAt = modelsDevMetadataValue(metadata, "fetchedAt");
  const providerCount = modelsDevMetadataValue(metadata, "providerCount");
  const modelCount = modelsDevMetadataValue(metadata, "modelCount");
  if (fetchedAt) headers.set("x-models-dev-fetched-at", fetchedAt);
  if (providerCount) headers.set("x-models-dev-provider-count", providerCount);
  if (modelCount) headers.set("x-models-dev-model-count", modelCount);
  return headers;
}

function modelsDevMetadataValue(metadata, key) {
  return stringValue(metadata?.[key] ?? metadata?.[key.toLowerCase()]);
}

function requestEtagMatches(rawHeader, etag) {
  if (!rawHeader || !etag) return false;
  const normalizedTarget = normalizeEtag(etag);
  return rawHeader.split(",").some((candidate) => {
    const value = candidate.trim();
    return value === "*" || normalizeEtag(value) === normalizedTarget;
  });
}

function normalizeEtag(raw) {
  return stringValue(raw).replace(/^W\//i, "");
}

function quoteEtag(raw) {
  const value = stringValue(raw).replace(/^W\//i, "").replace(/^"|"$/g, "");
  return value ? `"${value}"` : "";
}

async function handleUpdateCheck(request, url, env) {
  const currentVersion = normalizeVersion(
    url.searchParams.get("currentVersion") ||
      url.searchParams.get("current_version") ||
      url.searchParams.get("version") ||
      "",
  );
  const includeBeta = parseBoolean(url.searchParams.get("includeBeta") || url.searchParams.get("include_beta"));
  const edition = normalizeEdition(url.searchParams.get("edition"));
  const source = normalizeSource(url.searchParams.get("source") || env.DEFAULT_SOURCE || "worker");
  const checkedAt = Date.now();
  const bucket = requireBucket(env);
  const [releases, cloudServicePolicyConfig] = await Promise.all([
    loadReleases(bucket, env),
    readCloudServicePolicyConfig(bucket),
  ]);
  const cloudServicePolicy = buildCloudServicePolicy(currentVersion, cloudServicePolicyConfig);
  const selected = selectLatestRelease(releases, includeBeta);
  const asset = selected ? selectPreferredApkAsset(selected.assets, edition) : null;
  const latestVersion = selected ? selected.version : currentVersion;
  const hasUpdate = Boolean(selected && asset) && compareVersions(latestVersion, currentVersion) > 0;

  recordAnalytics(env, {
    type: "check",
    appVersion: currentVersion,
    latestVersion: selected ? selected.version : "",
    edition,
    deviceBrand: url.searchParams.get("deviceBrand") || url.searchParams.get("device_brand"),
    deviceModel: url.searchParams.get("deviceModel") || url.searchParams.get("device_model"),
    osVersion: url.searchParams.get("osVersion") || url.searchParams.get("os_version"),
    sdkInt: url.searchParams.get("sdkInt") || url.searchParams.get("sdk_int"),
    installId: url.searchParams.get("installId") || url.searchParams.get("install_id"),
    country: request.cf?.country,
    track: selected ? selected.track : "",
    source,
    hasUpdate,
  });

  if (!selected) {
    return json(emptyUpdateResponse({
      currentVersion,
      checkedAt,
      edition,
      source,
      cloudServicePolicy,
      officialVlmOperation: officialVlmOperationConfig(env, url),
    }));
  }

  return json({
    ok: true,
    currentVersion,
    latestVersion,
    hasUpdate,
    checkedAt,
    publishedAt: selected.publishedAt || 0,
    tag: selected.tag,
    track: selected.track,
    releaseUrl: selected.releaseUrl || "",
    releaseNotes: selected.releaseNotes || "",
    apkName: asset?.name || "",
    apkDownloadUrl: asset ? assetDownloadUrl(asset, source, url, selected.tag) : "",
    edition,
    source,
    officialVlmOperation: officialVlmOperationConfig(env, url),
    cloudServicePolicy,
    assets: (selected.assets || []).map((releaseAsset) => publicAsset(releaseAsset, url, selected.tag)),
  });
}

async function handleOfficialVlmProxy(request, pathname, env) {
  const requestedWireApi = pathname === VLM_RESPONSES_PATH
    ? "responses"
    : "chat_completions";
  const config = officialVlmUpstreamConfig(env, requestedWireApi);
  if (!config.enabled) {
    if (officialVlmUpstreamConfig(env).enabled) {
      throw httpError(404, "Official VLM route is not enabled");
    }
    throw httpError(503, "Official VLM service is not configured");
  }
  if (config.wireApi !== requestedWireApi) {
    throw httpError(404, "Official VLM route is not enabled");
  }

  const contentType = stringValue(request.headers.get("content-type")).toLowerCase();
  if (!contentType.startsWith("application/json")) {
    throw httpError(415, "Content-Type must be application/json");
  }
  const declaredSize = Number(request.headers.get("content-length"));
  if (Number.isFinite(declaredSize) && declaredSize > MAX_VLM_REQUEST_BYTES) {
    throw httpError(413, "VLM request body is too large");
  }

  const rawBody = await request.text();
  if (new TextEncoder().encode(rawBody).byteLength > MAX_VLM_REQUEST_BYTES) {
    throw httpError(413, "VLM request body is too large");
  }
  let payload;
  try {
    payload = JSON.parse(rawBody);
  } catch {
    throw httpError(400, "Invalid JSON body");
  }
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    throw httpError(400, "VLM request body must be a JSON object");
  }
  payload.model = config.model;

  const upstreamUrl = buildOpenAiUpstreamUrl(config.apiBase, config.wireApi);
  const clientApiKey = requestedWireApi === "responses"
    ? bearerToken(request.headers.get("authorization"))
    : "";
  const upstreamHeaders = new Headers({
    "authorization": `Bearer ${clientApiKey || config.apiKey}`,
    "content-type": "application/json",
    "accept": payload.stream === true ? "text/event-stream" : "application/json",
  });
  let upstreamResponse;
  try {
    upstreamResponse = await fetch(upstreamUrl, {
      method: "POST",
      headers: upstreamHeaders,
      body: JSON.stringify(payload),
    });
  } catch {
    throw httpError(502, "Official VLM upstream is unavailable");
  }

  const responseHeaders = new Headers({
    "access-control-allow-origin": "*",
    "cache-control": "no-store",
  });
  for (const name of ["content-type", "retry-after", "x-request-id"]) {
    const value = upstreamResponse.headers.get(name);
    if (value) responseHeaders.set(name, value);
  }
  return new Response(upstreamResponse.body, {
    status: upstreamResponse.status,
    statusText: upstreamResponse.statusText,
    headers: responseHeaders,
  });
}

function bearerToken(value) {
  const normalized = stringValue(value);
  const match = /^Bearer\s+(.+)$/i.exec(normalized);
  return match ? match[1].trim() : "";
}

function buildCloudServicePolicy(currentVersion, config) {
  const configuredMinimum = stringValue(config?.minimumVersion);
  if (!configuredMinimum) {
    return {
      schemaVersion: 1,
      enabled: false,
      minimumVersion: "",
      accessAllowed: true,
      message: "",
    };
  }

  const minimumVersion = normalizeVersion(configuredMinimum);
  const validMinimum = numericParts(minimumVersion) !== null;
  const validCurrent = numericParts(currentVersion) !== null;
  const accessAllowed = validMinimum && validCurrent &&
    compareVersions(currentVersion, minimumVersion) >= 0;
  const customMessage = stringValue(config?.message);
  const message = accessAllowed
    ? ""
    : customMessage || (
      validMinimum
        ? `当前版本过旧，请升级至 v${minimumVersion} 或更高版本后使用账号与官方云服务`
        : "云服务最低版本策略配置错误，请联系管理员"
    );

  return {
    schemaVersion: 1,
    enabled: true,
    minimumVersion,
    accessAllowed,
    message,
  };
}

async function handleGetCloudServicePolicy(env) {
  const policy = await readCloudServicePolicyConfig(requireBucket(env));
  return json({ ok: true, policy });
}

async function handlePutCloudServicePolicy(request, env) {
  const body = await readJson(request);
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw httpError(400, "JSON object body is required");
  }
  if (!Object.prototype.hasOwnProperty.call(body, "minimumVersion")) {
    throw httpError(400, "minimumVersion is required; use an empty string to disable the gate");
  }
  if (typeof body.minimumVersion !== "string") {
    throw httpError(400, "minimumVersion must be a string");
  }
  if (body.message !== undefined && typeof body.message !== "string") {
    throw httpError(400, "message must be a string");
  }

  const minimumVersion = normalizeVersion(body.minimumVersion);
  if (minimumVersion && numericParts(minimumVersion) === null) {
    throw httpError(400, "minimumVersion must contain only dot-separated numeric segments");
  }
  const message = stringValue(body.message);
  if (message.length > 500) {
    throw httpError(400, "message must be 500 characters or fewer");
  }

  const policy = {
    schemaVersion: 1,
    enabled: Boolean(minimumVersion),
    minimumVersion,
    message,
    updatedAt: Date.now(),
  };
  await requireBucket(env).put(
    CLOUD_SERVICE_POLICY_OBJECT_KEY,
    JSON.stringify(policy),
    cloudServicePolicyStorageOptions(policy),
  );
  return json({ ok: true, policy });
}

async function readCloudServicePolicyConfig(bucket) {
  const object = await bucket.get(CLOUD_SERVICE_POLICY_OBJECT_KEY);
  if (!object) {
    return defaultCloudServicePolicyConfig();
  }

  try {
    const stored = JSON.parse(await object.text());
    const minimumVersion = normalizeVersion(stored?.minimumVersion);
    const message = stringValue(stored?.message);
    if ((minimumVersion && numericParts(minimumVersion) === null) || message.length > 500) {
      throw new Error("invalid fields");
    }
    return {
      schemaVersion: 1,
      enabled: Boolean(minimumVersion),
      minimumVersion,
      message,
      updatedAt: normalizeTimestamp(stored?.updatedAt),
    };
  } catch {
    throw httpError(500, "Stored cloud-service policy is invalid");
  }
}

function defaultCloudServicePolicyConfig() {
  return {
    schemaVersion: 1,
    enabled: false,
    minimumVersion: "",
    message: "",
    updatedAt: 0,
  };
}

function cloudServicePolicyStorageOptions(policy) {
  return {
    httpMetadata: {
      contentType: "application/json; charset=utf-8",
    },
    customMetadata: omitEmpty({
      minimumVersion: policy.minimumVersion,
      updatedAt: String(policy.updatedAt),
    }),
  };
}

async function handleListReleases(env) {
  const releases = await loadReleases(requireBucket(env), env, { includeDrafts: true });
  return json({ ok: true, releases });
}

async function handleGetRelease(rawTag, env) {
  const bucket = requireBucket(env);
  const tag = normalizeTag(rawTag);
  if (!tag) {
    throw httpError(400, "tag is required");
  }
  const release = await readReleaseMetadata(bucket, releaseObjectKey(tag, env));
  if (!release) {
    throw httpError(404, "Release not found");
  }
  return json({ ok: true, release });
}

async function handleUpsertRelease(request, env) {
  const bucket = requireBucket(env);
  const body = await readJson(request);
  const tag = normalizeTag(body.tag || body.tagName || body.tag_name);
  if (!tag) {
    throw httpError(400, "tag is required");
  }
  const existing = await readReleaseMetadata(bucket, releaseObjectKey(tag, env));
  const release = normalizeRelease(body, env, existing);
  await bucket.put(releaseObjectKey(release.tag, env), JSON.stringify(release), releaseMetadataOptions(release));
  return json({ ok: true, release });
}

async function handlePatchRelease(request, rawTag, env) {
  const bucket = requireBucket(env);
  const tag = normalizeTag(rawTag);
  if (!tag) {
    throw httpError(400, "tag is required");
  }
  const existing = await readReleaseMetadata(bucket, releaseObjectKey(tag, env));
  if (!existing) {
    throw httpError(404, "Release not found");
  }

  const body = await readJson(request);
  const merged = {
    tag,
    version: body.version !== undefined ? body.version : existing.version,
    track: body.track !== undefined ? body.track : existing.track,
    draft: body.draft !== undefined ? body.draft : existing.draft,
    prerelease: body.prerelease !== undefined ? body.prerelease : existing.prerelease,
    publishedAt: body.publishedAt !== undefined ? body.publishedAt : existing.publishedAt,
    releaseUrl: body.releaseUrl !== undefined ? body.releaseUrl : existing.releaseUrl,
    releaseNotes: body.releaseNotes !== undefined ? body.releaseNotes : existing.releaseNotes,
    assets: Array.isArray(body.assets) ? body.assets : existing.assets,
  };

  const release = normalizeRelease(merged, env);
  await bucket.put(releaseObjectKey(release.tag, env), JSON.stringify(release), releaseMetadataOptions(release));
  return json({ ok: true, release });
}

async function handleAssetMutation(request, url, env) {
  const action = stringValue(url.searchParams.get("action"));
  if (!action && request.method === "PUT") {
    return await handleUploadAsset(request, url, env);
  }
  if (!action && request.method === "DELETE") {
    return await handleDeleteAsset(url, env);
  }
  if (action === "mpu-create" && request.method === "POST") {
    return await handleCreateMultipartUpload(request, url, env);
  }
  if (action === "mpu-uploadpart" && request.method === "PUT") {
    return await handleUploadMultipartPart(request, url, env);
  }
  if (action === "mpu-complete" && request.method === "POST") {
    return await handleCompleteMultipartUpload(request, url, env);
  }
  if (action === "mpu-abort" && request.method === "DELETE") {
    return await handleAbortMultipartUpload(url, env);
  }
  throw httpError(400, "unsupported asset upload action");
}

async function handleUploadAsset(request, url, env) {
  const bucket = requireBucket(env);
  const parsed = requireAdminAssetPath(url);
  const { tag, name } = parsed;
  validateStoredAssetName(name);
  if (!request.body) {
    throw httpError(400, "asset body is required");
  }

  const contentType = request.headers.get("content-type") || contentTypeForAssetName(name);
  const sha256 = stringValue(request.headers.get("x-content-sha256"));
  const size = normalizeSize(request.headers.get("content-length"));
  const key = assetObjectKey(tag, name, env);
  const uploadedAt = Date.now();

  const uploaded = await bucket.put(key, request.body, assetUploadOptions({
    tag,
    name,
    contentType,
    sha256,
    size,
    uploadedAt,
  }));

  const workerDownloadUrl = publicDownloadUrl(url, tag, name);
  return json({
    ok: true,
    asset: {
      name,
      r2ObjectKey: key,
      workerDownloadUrl,
      downloadUrl: workerDownloadUrl,
      sha256,
      size,
      etag: uploaded?.etag || "",
      uploadedAt,
    },
  });
}

async function handleDeleteAsset(url, env) {
  const bucket = requireBucket(env);
  const { tag, name } = requireAdminAssetPath(url);
  validateStoredAssetName(name);

  const key = assetObjectKey(tag, name, env);
  const existing = await bucket.head(key);
  if (existing) {
    await bucket.delete(key);
  }

  const releaseKey = releaseObjectKey(tag, env);
  const release = await readReleaseMetadata(bucket, releaseKey);
  if (release && Array.isArray(release.assets)) {
    const remaining = release.assets.filter((asset) => stringValue(asset?.name) !== name);
    if (remaining.length !== release.assets.length) {
      release.assets = remaining;
      release.updatedAt = Date.now();
      await bucket.put(releaseKey, JSON.stringify(release), releaseMetadataOptions(release));
    }
  }

  return json({ ok: true, tag, name, deleted: Boolean(existing) });
}

async function handleCreateMultipartUpload(request, url, env) {
  const bucket = requireBucket(env);
  const { tag, name } = requireAdminAssetPath(url);
  validateStoredAssetName(name);

  const key = assetObjectKey(tag, name, env);
  const sha256 = stringValue(request.headers.get("x-content-sha256"));
  const size = normalizeSize(request.headers.get("x-content-size") || request.headers.get("content-length"));
  const uploadedAt = Date.now();
  const multipartUpload = await bucket.createMultipartUpload(key, assetUploadOptions({
    tag,
    name,
    contentType: request.headers.get("content-type") || contentTypeForAssetName(name),
    sha256,
    size,
    uploadedAt,
  }));

  return json({
    ok: true,
    upload: {
      key: multipartUpload.key,
      uploadId: multipartUpload.uploadId,
      uploadedAt,
    },
  });
}

async function handleUploadMultipartPart(request, url, env) {
  const bucket = requireBucket(env);
  const { tag, name } = requireAdminAssetPath(url);
  validateStoredAssetName(name);
  if (!request.body) {
    throw httpError(400, "part body is required");
  }

  const uploadId = stringValue(url.searchParams.get("uploadId"));
  const partNumber = Number(url.searchParams.get("partNumber"));
  if (!uploadId || !Number.isInteger(partNumber) || partNumber < 1 || partNumber > 10000) {
    throw httpError(400, "valid uploadId and partNumber are required");
  }

  const multipartUpload = bucket.resumeMultipartUpload(assetObjectKey(tag, name, env), uploadId);
  try {
    const uploadedPart = await multipartUpload.uploadPart(partNumber, request.body);
    return json({ ok: true, part: uploadedPart });
  } catch (error) {
    throw httpError(400, error.message || "multipart part upload failed");
  }
}

async function handleCompleteMultipartUpload(request, url, env) {
  const bucket = requireBucket(env);
  const { tag, name } = requireAdminAssetPath(url);
  validateStoredAssetName(name);

  const uploadId = stringValue(url.searchParams.get("uploadId"));
  if (!uploadId) {
    throw httpError(400, "uploadId is required");
  }

  const body = await readJson(request);
  const parts = normalizeUploadedParts(body.parts);
  const multipartUpload = bucket.resumeMultipartUpload(assetObjectKey(tag, name, env), uploadId);
  let object;
  try {
    object = await multipartUpload.complete(parts);
  } catch (error) {
    throw httpError(400, error.message || "multipart upload complete failed");
  }

  const workerDownloadUrl = publicDownloadUrl(url, tag, name);
  return json({
    ok: true,
    asset: {
      name,
      r2ObjectKey: object.key,
      workerDownloadUrl,
      downloadUrl: workerDownloadUrl,
      sha256: stringValue(body.sha256),
      size: normalizeSize(body.size),
      etag: object.httpEtag || object.etag || "",
      uploadedAt: normalizeTimestamp(body.uploadedAt || Date.now()),
    },
  });
}

async function handleAbortMultipartUpload(url, env) {
  const bucket = requireBucket(env);
  const { tag, name } = requireAdminAssetPath(url);
  validateStoredAssetName(name);

  const uploadId = stringValue(url.searchParams.get("uploadId"));
  if (!uploadId) {
    throw httpError(400, "uploadId is required");
  }

  const multipartUpload = bucket.resumeMultipartUpload(assetObjectKey(tag, name, env), uploadId);
  try {
    await multipartUpload.abort();
  } catch (error) {
    throw httpError(400, error.message || "multipart upload abort failed");
  }
  return json({ ok: true, aborted: true });
}

async function handleDownloadAsset(request, url, env) {
  const bucket = requireBucket(env);
  const parsed = parseDownloadPath(normalizePath(url.pathname));
  if (!parsed) {
    throw httpError(404, "Download route not found");
  }

  const { tag, name } = parsed;
  validateStoredAssetName(name);
  const object = await bucket.get(assetObjectKey(tag, name, env));
  if (!object) {
    throw httpError(404, "Asset not found");
  }

  if (request.method === "GET" && isApkAssetName(name)) {
    recordAnalytics(env, {
      type: "download",
      latestVersion: tag,
      edition: name,
      country: request.cf?.country,
    });
  }

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  const etag = object.httpEtag || object.etag || "";
  if (etag) {
    headers.set("etag", etag);
  }
  headers.set("cache-control", isApkAssetName(name) ? "public, max-age=300" : "public, max-age=60");
  headers.set("content-disposition", `attachment; filename="${headerFileName(name)}"`);
  headers.set("access-control-allow-origin", "*");

  return new Response(request.method === "HEAD" ? null : object.body, {
    status: 200,
    headers,
  });
}

async function handleDeleteRelease(rawTag, env) {
  const bucket = requireBucket(env);
  const tag = normalizeTag(rawTag);
  if (!tag) {
    throw httpError(400, "tag is required");
  }

  const key = releaseObjectKey(tag, env);
  const existing = await bucket.head(key);
  const deleted = Boolean(existing);
  if (deleted) {
    await bucket.delete(key);
  }

  return json({ ok: true, tag, deleted });
}

// --- Analytics Engine ---
//
// Data point schema (one dataset, event type doubles as the sampling index):
//   index1  event type: "check" | "download"
//   blob1   event type (again, so it is queryable as a column)
//   blob2   app currentVersion ("check") / "" ("download")
//   blob3   latest offered version ("check") / release tag ("download")
//   blob4   edition ("check") / asset file name ("download")
//   blob5   device brand
//   blob6   device model
//   blob7   Android OS version
//   blob8   Android SDK int
//   blob9   anonymous install id
//   blob10  request country (from Cloudflare)
//   blob11  release track offered
//   blob12  download source preference
//   double1 hasUpdate (1/0)

function recordAnalytics(env, event) {
  const dataset = env.UPDATE_ANALYTICS;
  if (!dataset || typeof dataset.writeDataPoint !== "function") {
    return;
  }
  try {
    dataset.writeDataPoint({
      indexes: [blobValue(event.type)],
      blobs: [
        blobValue(event.type),
        blobValue(event.appVersion),
        blobValue(event.latestVersion),
        blobValue(event.edition),
        blobValue(event.deviceBrand),
        blobValue(event.deviceModel),
        blobValue(event.osVersion),
        blobValue(event.sdkInt),
        blobValue(event.installId),
        blobValue(event.country),
        blobValue(event.track),
        blobValue(event.source),
      ],
      doubles: [event.hasUpdate ? 1 : 0],
    });
  } catch {
    // Analytics must never break the update/download path.
  }
}

function blobValue(raw) {
  return stringValue(raw).slice(0, 200);
}

function isAnalyticsQueryConfigured(env) {
  return Boolean(stringValue(env.CF_ACCOUNT_ID) && stringValue(env.CF_ANALYTICS_API_TOKEN || env.CF_API_TOKEN));
}

async function handleAnalyticsQuery(pathname, url, env) {
  const metric = pathname.slice(ADMIN_ANALYTICS_ROUTE_PREFIX.length);
  const days = clampInt(url.searchParams.get("days"), 1, ANALYTICS_RETENTION_DAYS, 30);
  const limit = clampInt(url.searchParams.get("limit"), 1, 100, 10);
  const table = analyticsTableName(env);
  const since = `timestamp > now() - INTERVAL '${days}' DAY`;

  let sql;
  switch (metric) {
    case "summary":
      sql = `SELECT blob1 AS event,
               sum(_sample_interval) AS total,
               count(DISTINCT blob9) AS uniqueDevices
             FROM ${table}
             WHERE ${since}
             GROUP BY event
             FORMAT JSON`;
      break;
    case "daily":
      sql = `SELECT toStartOfInterval(timestamp, INTERVAL '1' DAY) AS day,
               blob1 AS event,
               sum(_sample_interval) AS total,
               count(DISTINCT blob9) AS uniqueDevices
             FROM ${table}
             WHERE ${since}
             GROUP BY day, event
             ORDER BY day ASC
             FORMAT JSON`;
      break;
    case "devices":
      sql = `SELECT blob5 AS brand,
               blob6 AS model,
               sum(_sample_interval) AS total,
               count(DISTINCT blob9) AS uniqueDevices
             FROM ${table}
             WHERE ${since} AND blob1 = 'check' AND blob6 <> ''
             GROUP BY brand, model
             ORDER BY uniqueDevices DESC, total DESC
             LIMIT ${limit}
             FORMAT JSON`;
      break;
    case "versions":
      sql = `SELECT blob2 AS version,
               sum(_sample_interval) AS total,
               count(DISTINCT blob9) AS uniqueDevices
             FROM ${table}
             WHERE ${since} AND blob1 = 'check' AND blob2 <> ''
             GROUP BY version
             ORDER BY uniqueDevices DESC, total DESC
             LIMIT ${limit}
             FORMAT JSON`;
      break;
    case "os":
      sql = `SELECT blob7 AS osVersion,
               sum(_sample_interval) AS total,
               count(DISTINCT blob9) AS uniqueDevices
             FROM ${table}
             WHERE ${since} AND blob1 = 'check' AND blob7 <> ''
             GROUP BY osVersion
             ORDER BY uniqueDevices DESC, total DESC
             LIMIT ${limit}
             FORMAT JSON`;
      break;
    case "downloads":
      sql = `SELECT blob3 AS tag,
               blob4 AS asset,
               sum(_sample_interval) AS total
             FROM ${table}
             WHERE ${since} AND blob1 = 'download'
             GROUP BY tag, asset
             ORDER BY total DESC
             LIMIT ${limit}
             FORMAT JSON`;
      break;
    default:
      throw httpError(404, `unknown analytics metric: ${metric}`);
  }

  const result = await queryAnalytics(env, sql);
  const rows = (result.data || []).map(coerceNumericFields);
  return json({ ok: true, metric, days, rows });
}

async function queryAnalytics(env, sql) {
  const accountId = stringValue(env.CF_ACCOUNT_ID);
  const apiToken = stringValue(env.CF_ANALYTICS_API_TOKEN || env.CF_API_TOKEN);
  if (!accountId || !apiToken) {
    throw httpError(501, "analytics query is not configured: set CF_ACCOUNT_ID and the CF_ANALYTICS_API_TOKEN secret");
  }

  const response = await fetch(`https://api.cloudflare.com/client/v4/accounts/${accountId}/analytics_engine/sql`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${apiToken}`,
      "content-type": "text/plain; charset=utf-8",
    },
    body: sql,
  });

  const text = await response.text();
  if (!response.ok) {
    throw httpError(502, `analytics query failed (${response.status}): ${text.slice(0, 300)}`);
  }
  try {
    return JSON.parse(text);
  } catch {
    throw httpError(502, "analytics query returned invalid JSON");
  }
}

function analyticsTableName(env) {
  const dataset = stringValue(env.ANALYTICS_DATASET) || DEFAULT_ANALYTICS_DATASET;
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(dataset)) {
    throw httpError(500, "ANALYTICS_DATASET must be a plain identifier");
  }
  return dataset;
}

function coerceNumericFields(row) {
  const result = {};
  for (const [key, value] of Object.entries(row || {})) {
    if (typeof value === "string" && /^\d+(\.\d+)?$/.test(value)) {
      const numeric = Number(value);
      result[key] = Number.isSafeInteger(numeric) || !Number.isInteger(numeric) ? numeric : value;
    } else {
      result[key] = value;
    }
  }
  return result;
}

function clampInt(raw, min, max, fallback) {
  const value = Number(raw);
  if (!Number.isInteger(value)) return fallback;
  return Math.min(max, Math.max(min, value));
}

async function loadReleases(bucket, env, { includeDrafts = false } = {}) {
  const releases = [];
  let cursor;
  const prefix = `${normalizeMetadataPrefix(env.R2_METADATA_PREFIX)}/`;

  do {
    const page = await bucket.list({ prefix, cursor });
    for (const object of page.objects || []) {
      const release = await readReleaseMetadata(bucket, object.key);
      if (release) {
        releases.push(release);
      }
    }
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);

  return releases
    .filter((release) => includeDrafts || (!release.draft && release.track !== "unsupported"))
    .sort((left, right) => {
      const versionOrder = compareVersions(right.version, left.version);
      if (versionOrder !== 0) return versionOrder;
      return (right.publishedAt || 0) - (left.publishedAt || 0);
    });
}

function requireBucket(env) {
  if (!env.APP_UPDATE_BUCKET) {
    throw httpError(500, "APP_UPDATE_BUCKET R2 bucket binding is missing");
  }
  return env.APP_UPDATE_BUCKET;
}

function requireAdmin(request, env) {
  const expected = env.ADMIN_TOKEN || env.APP_UPDATE_WORKER_TOKEN;
  if (!expected) {
    throw httpError(500, "ADMIN_TOKEN is not configured");
  }

  const auth = request.headers.get("authorization") || "";
  const bearerToken = auth.replace(/^Bearer\s+/i, "").trim();
  const headerToken = (request.headers.get("x-update-token") || "").trim();
  if (!timingSafeEquals(bearerToken, expected) && !timingSafeEquals(headerToken, expected)) {
    throw httpError(401, "Unauthorized");
  }
}

function timingSafeEquals(candidate, expected) {
  const encoder = new TextEncoder();
  const candidateBytes = encoder.encode(candidate);
  const expectedBytes = encoder.encode(expected);
  if (candidateBytes.byteLength !== expectedBytes.byteLength) {
    return false;
  }
  if (typeof crypto?.subtle?.timingSafeEqual === "function") {
    return crypto.subtle.timingSafeEqual(candidateBytes, expectedBytes);
  }
  let mismatch = 0;
  for (let index = 0; index < candidateBytes.byteLength; index += 1) {
    mismatch |= candidateBytes[index] ^ expectedBytes[index];
  }
  return mismatch === 0;
}

function normalizeRelease(input, env, existing = null) {
  if (!input || typeof input !== "object") {
    throw httpError(400, "JSON object body is required");
  }

  const tag = normalizeTag(input.tag || input.tagName || input.tag_name);
  if (!tag) {
    throw httpError(400, "tag is required");
  }

  const version = normalizeVersion(input.version || input.latestVersion || tag);
  const track = normalizeTrack(input.track) || classifyReleaseTrack(version, input.prerelease);
  const publishedAt = normalizeTimestamp(input.publishedAt || input.published_at || Date.now());
  const assets = normalizeAssets(input.assets, tag, env, existing);

  // A manually curated changelog must survive automated republishes (CI posts
  // the release without notes), so empty incoming notes fall back to the
  // stored ones. PATCH bypasses this by pre-merging fields before the call.
  const releaseNotes = stringValue(input.releaseNotes || input.notes || input.body) ||
    stringValue(existing?.releaseNotes);
  const releaseUrl = stringValue(input.releaseUrl || input.htmlUrl || input.html_url || input.url) ||
    stringValue(existing?.releaseUrl);

  return {
    tag,
    version,
    track,
    draft: Boolean(input.draft),
    prerelease: Boolean(input.prerelease),
    publishedAt,
    releaseUrl,
    releaseNotes,
    assets,
    updatedAt: Date.now(),
  };
}

function normalizeAssets(rawAssets, tag, env, existing = null) {
  if (Array.isArray(rawAssets)) {
    return rawAssets.map((asset) => normalizeAsset(asset, tag, env)).filter(Boolean);
  }

  if (existing && Array.isArray(existing.assets)) {
    return existing.assets.map((asset) => normalizeAsset(asset, tag, env)).filter(Boolean);
  }

  return DEFAULT_EDITIONS.map((edition) => buildDefaultAsset(tag, edition, env));
}

function normalizeAsset(asset, tag, env) {
  if (!asset || typeof asset !== "object") return null;
  const name = stringValue(asset.name || asset.fileName || asset.filename);
  if (!name.toLowerCase().endsWith(".apk")) return null;
  const r2ObjectKey = stringValue(asset.r2ObjectKey || asset.r2_object_key || asset.key) ||
    assetObjectKey(tag, name, env);
  return {
    name,
    r2ObjectKey,
    downloadUrl: stringValue(asset.downloadUrl || asset.browser_download_url),
    workerDownloadUrl: stringValue(asset.workerDownloadUrl || asset.worker_download_url),
    r2DownloadUrl: stringValue(asset.r2DownloadUrl || asset.r2_download_url),
    githubDownloadUrl: stringValue(asset.githubDownloadUrl || asset.github_download_url || asset.browser_download_url),
    cnbDownloadUrl: stringValue(asset.cnbDownloadUrl || asset.cnb_download_url),
    sha256: stringValue(asset.sha256 || asset.sha256sum || asset.checksum),
    size: normalizeSize(asset.size || asset.contentLength || asset.content_length),
  };
}

function buildDefaultAsset(tag, edition, env) {
  const name = `OpenOmniBot-${tag}-${edition}.apk`;
  const githubRepo = env.GITHUB_REPO || DEFAULT_GITHUB_REPO;
  return {
    name,
    r2ObjectKey: assetObjectKey(tag, name, env),
    githubDownloadUrl: `https://github.com/${githubRepo}/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(name)}`,
  };
}

function selectLatestRelease(releases, includeBeta) {
  return releases
    .filter((release) => release.track === "stable" || (includeBeta && release.track === "beta"))
    .reduce((selected, release) => {
      if (!selected) return release;
      const versionOrder = compareVersions(release.version, selected.version);
      if (versionOrder > 0) return release;
      if (versionOrder === 0 && (release.publishedAt || 0) > (selected.publishedAt || 0)) {
        return release;
      }
      return selected;
    }, null);
}

function selectPreferredApkAsset(assets, edition) {
  const apkAssets = (assets || []).filter((asset) => asset.name.toLowerCase().endsWith(".apk"));
  const editionAsset = apkAssets.find((asset) => isEditionApkAsset(asset.name, edition));
  if (editionAsset) return editionAsset;
  if (apkAssets.some((asset) => isKnownEditionApkAsset(asset.name))) return null;
  return apkAssets.find((asset) => /^OpenOmniBot-v/i.test(asset.name)) || apkAssets[0] || null;
}

function assetDownloadUrl(asset, source, url, tag) {
  const workerDownloadUrl = assetWorkerDownloadUrl(asset, url, tag);
  if (source === "github") {
    return asset.githubDownloadUrl || asset.downloadUrl || workerDownloadUrl || "";
  }
  return workerDownloadUrl || asset.downloadUrl || asset.githubDownloadUrl || asset.cnbDownloadUrl || "";
}

function publicAsset(asset, url, tag) {
  const workerDownloadUrl = assetWorkerDownloadUrl(asset, url, tag);
  return {
    name: asset.name,
    downloadUrl: workerDownloadUrl || asset.downloadUrl || "",
    workerDownloadUrl,
    r2DownloadUrl: asset.r2DownloadUrl || "",
    r2ObjectKey: asset.r2ObjectKey || "",
    githubDownloadUrl: asset.githubDownloadUrl || "",
    cnbDownloadUrl: asset.cnbDownloadUrl || "",
    sha256: asset.sha256 || "",
    size: normalizeSize(asset.size),
  };
}

function assetWorkerDownloadUrl(asset, url, tag) {
  return asset.workerDownloadUrl || asset.r2DownloadUrl || (asset.name ? publicDownloadUrl(url, tag, asset.name) : "");
}

function requireAdminAssetPath(url) {
  const parsed = parseAdminAssetPath(normalizePath(url.pathname));
  if (!parsed) {
    throw httpError(404, "Upload route not found");
  }
  return parsed;
}

function parseDownloadPath(pathname) {
  const rest = pathname.slice(DOWNLOAD_ROUTE_PREFIX.length);
  const separator = rest.indexOf("/");
  if (separator <= 0 || separator >= rest.length - 1) return null;
  return {
    tag: normalizeTag(decodePathSegment(rest.slice(0, separator))),
    name: decodePathSegment(rest.slice(separator + 1)),
  };
}

function parseAdminAssetPath(pathname) {
  if (!pathname.startsWith(ADMIN_RELEASE_ROUTE_PREFIX)) return null;
  const rest = pathname.slice(ADMIN_RELEASE_ROUTE_PREFIX.length);
  const marker = "/assets/";
  const markerIndex = rest.indexOf(marker);
  if (markerIndex <= 0 || markerIndex >= rest.length - marker.length) return null;
  return {
    tag: normalizeTag(decodePathSegment(rest.slice(0, markerIndex))),
    name: decodePathSegment(rest.slice(markerIndex + marker.length)),
  };
}

function publicDownloadUrl(url, tag, name) {
  return `${url.origin}${DOWNLOAD_ROUTE_PREFIX}${encodePathSegment(tag)}/${encodePathSegment(name)}`;
}

function assetObjectKey(tag, name, env) {
  const prefix = normalizeR2Prefix(env.R2_RELEASES_PREFIX || DEFAULT_R2_RELEASES_PREFIX);
  return `${prefix}/${encodePathSegment(tag)}/${encodePathSegment(name)}`;
}

function normalizeR2Prefix(raw) {
  return stringValue(raw).replace(/^\/+|\/+$/g, "") || DEFAULT_R2_RELEASES_PREFIX;
}

function releaseObjectKey(tag, env) {
  return `${normalizeMetadataPrefix(env.R2_METADATA_PREFIX)}/${encodePathSegment(tag)}.json`;
}

function normalizeMetadataPrefix(raw) {
  return stringValue(raw).replace(/^\/+|\/+$/g, "") || DEFAULT_R2_METADATA_PREFIX;
}

function releaseMetadataOptions(release) {
  return {
    httpMetadata: {
      contentType: "application/json; charset=utf-8",
    },
    customMetadata: omitEmpty({
      tag: release.tag,
      version: release.version,
      track: release.track,
      publishedAt: release.publishedAt ? String(release.publishedAt) : "",
    }),
  };
}

async function readReleaseMetadata(bucket, key) {
  const object = await bucket.get(key);
  if (!object) return null;
  try {
    return JSON.parse(await object.text());
  } catch {
    return null;
  }
}

function validateStoredAssetName(name) {
  const value = stringValue(name);
  if (!value || value.includes("/") || value.includes("\\") || value === "." || value === "..") {
    throw httpError(400, "invalid asset name");
  }
  if (!isApkAssetName(value) && !value.toLowerCase().endsWith(".apk.sha256")) {
    throw httpError(400, "only APK assets and APK SHA-256 files are supported");
  }
}

function isApkAssetName(name) {
  return stringValue(name).toLowerCase().endsWith(".apk");
}

function contentTypeForAssetName(name) {
  return isApkAssetName(name) ? "application/vnd.android.package-archive" : "text/plain; charset=utf-8";
}

function headerFileName(name) {
  return stringValue(name).replace(/["\r\n]/g, "_");
}

function assetUploadOptions({ tag, name, contentType, sha256, size, uploadedAt }) {
  return {
    httpMetadata: {
      contentType: contentType || contentTypeForAssetName(name),
      contentDisposition: `attachment; filename="${headerFileName(name)}"`,
    },
    customMetadata: omitEmpty({
      tag,
      name,
      sha256,
      size: size ? String(size) : "",
      uploadedAt: String(uploadedAt || Date.now()),
    }),
  };
}

function normalizeUploadedParts(rawParts) {
  if (!Array.isArray(rawParts) || rawParts.length === 0) {
    throw httpError(400, "parts are required");
  }
  return rawParts
    .map((part) => {
      const partNumber = Number(part?.partNumber);
      const etag = stringValue(part?.etag);
      if (!Number.isInteger(partNumber) || partNumber < 1 || !etag) {
        throw httpError(400, "each part needs partNumber and etag");
      }
      return { partNumber, etag };
    })
    .sort((left, right) => left.partNumber - right.partNumber);
}

function normalizePath(pathname) {
  if (!pathname || pathname === "/") return "/";
  return pathname.replace(/\/+$/, "");
}

function normalizeTag(raw) {
  return stringValue(raw).replace(/^refs\/tags\//, "").trim();
}

function normalizeVersion(raw) {
  return stringValue(raw)
    .replace(/^refs\/tags\//, "")
    .replace(/^[vV]/, "")
    .split("+")[0]
    .trim();
}

function normalizeTrack(raw) {
  const value = stringValue(raw).toLowerCase();
  if (value === "stable") return "stable";
  if (value === "beta" || value === "prerelease" || value === "pre-release") return "beta";
  return "";
}

function classifyReleaseTrack(version, prerelease) {
  if (prerelease) return "beta";
  const parts = normalizeVersion(version).split(".");
  if (parts.length === 3 && parts.every(isDigits)) return "stable";
  if (parts.length === 4 && parts.every(isDigits)) return "beta";
  return "unsupported";
}

function compareVersions(leftRaw, rightRaw) {
  const left = normalizeVersion(leftRaw);
  const right = normalizeVersion(rightRaw);
  if (left === right) return 0;

  const leftParts = numericParts(left);
  const rightParts = numericParts(right);
  if (leftParts && rightParts) {
    const length = Math.max(leftParts.length, rightParts.length);
    for (let index = 0; index < length; index += 1) {
      const leftValue = leftParts[index] || 0;
      const rightValue = rightParts[index] || 0;
      if (leftValue !== rightValue) {
        return leftValue > rightValue ? 1 : -1;
      }
    }
    return 0;
  }

  return left.localeCompare(right);
}

function numericParts(version) {
  if (!version) return null;
  const parts = version.split(".");
  if (!parts.every(isDigits)) return null;
  return parts.map((part) => Number(part));
}

function isDigits(value) {
  return /^\d+$/.test(value);
}

function isEditionApkAsset(name, edition) {
  return name.toLowerCase().endsWith(`-${edition}.apk`);
}

function isKnownEditionApkAsset(name) {
  return /^openomnibot-.+-[a-z0-9_]+\.apk$/i.test(name);
}

function normalizeEdition(raw) {
  return "standard";
}

function normalizeSource(raw) {
  return stringValue(raw).toLowerCase() === "github" ? "github" : "worker";
}

function parseBoolean(raw) {
  const value = stringValue(raw).toLowerCase();
  return value === "1" || value === "true" || value === "yes";
}

function normalizeTimestamp(raw) {
  if (typeof raw === "number" && Number.isFinite(raw)) {
    return raw < 10_000_000_000 ? Math.trunc(raw * 1000) : Math.trunc(raw);
  }
  const value = stringValue(raw);
  if (!value) return 0;
  if (/^\d+$/.test(value)) {
    const numeric = Number(value);
    return numeric < 10_000_000_000 ? numeric * 1000 : numeric;
  }
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? 0 : parsed;
}

function emptyUpdateResponse({
  currentVersion,
  checkedAt,
  edition,
  source,
  cloudServicePolicy,
  officialVlmOperation,
}) {
  return {
    ok: true,
    currentVersion,
    latestVersion: currentVersion,
    hasUpdate: false,
    checkedAt,
    publishedAt: 0,
    tag: "",
    track: "",
    releaseUrl: "",
    releaseNotes: "",
    apkName: "",
    apkDownloadUrl: "",
    edition,
    source,
    officialVlmOperation,
    cloudServicePolicy,
    assets: [],
  };
}

function officialVlmUpstreamConfig(env, requestedWireApi = "") {
  const lunaConfig = {
    apiBase: stringValue(env.CHATGPT_LUNA_VLM_API_BASE),
    apiKey: stringValue(env.CHATGPT_LUNA_VLM_API_KEY),
    model: stringValue(env.CHATGPT_LUNA_VLM_MODEL),
    wireApi: "responses",
    enabledValue: env.CHATGPT_LUNA_VLM_ENABLED,
  };
  const legacyConfig = {
    apiBase: stringValue(env.OFFICIAL_VLM_OPERATION_API_BASE),
    apiKey: stringValue(env.OFFICIAL_VLM_OPERATION_API_KEY),
    model: stringValue(env.OFFICIAL_VLM_OPERATION_MODEL),
    wireApi: "chat_completions",
    enabledValue: env.OFFICIAL_VLM_OPERATION_ENABLED,
  };
  const selected = requestedWireApi === "responses"
    ? lunaConfig
    : requestedWireApi === "chat_completions"
      ? legacyConfig
      : isOfficialVlmConfigEnabled(legacyConfig)
        ? legacyConfig
        : lunaConfig;
  const { apiBase, apiKey, model, wireApi, enabledValue } = selected;
  const configured = Boolean(apiBase && apiKey && model);
  const enabled = enabledValue === undefined
    ? configured
    : parseBoolean(enabledValue) && configured;
  return { enabled, apiBase, apiKey, model, wireApi };
}

function isOfficialVlmConfigEnabled(config) {
  const configured = Boolean(config.apiBase && config.apiKey && config.model);
  return config.enabledValue === undefined
    ? configured
    : parseBoolean(config.enabledValue) && configured;
}

function officialVlmOperationConfig(env, requestUrl) {
  const upstream = officialVlmUpstreamConfig(env);
  return {
    enabled: upstream.enabled,
    apiBase: requestUrl.origin,
    model: upstream.model,
    wireApi: upstream.wireApi,
  };
}

function buildOpenAiUpstreamUrl(apiBase, wireApi) {
  let url;
  try {
    url = new URL(apiBase);
  } catch {
    throw httpError(500, "Official VLM upstream URL is invalid");
  }
  const suffix = wireApi === "responses" ? "/responses" : "/chat/completions";
  const pathname = url.pathname.replace(/\/+$/, "");
  if (!pathname.endsWith(suffix)) {
    url.pathname = /\/v\d+(?:\.\d+)?$/i.test(pathname)
      ? `${pathname}${suffix}`
      : `${pathname}/v1${suffix}`;
  }
  return url.toString();
}

function stringValue(value) {
  if (value === null || value === undefined) return "";
  return String(value).trim();
}

function normalizeSize(raw) {
  const size = Number(raw);
  return Number.isFinite(size) && size > 0 ? Math.trunc(size) : 0;
}

function omitEmpty(input) {
  return Object.fromEntries(Object.entries(input).filter(([, value]) => stringValue(value) !== ""));
}

function encodePathSegment(raw) {
  return encodeURIComponent(stringValue(raw));
}

function decodePathSegment(raw) {
  try {
    return decodeURIComponent(raw);
  } catch {
    throw httpError(400, "invalid encoded path segment");
  }
}

async function readJson(request) {
  try {
    return await request.json();
  } catch {
    throw httpError(400, "Invalid JSON body");
  }
}

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: JSON_HEADERS,
  });
}

export {
  handleModelsDevCatalog,
};
