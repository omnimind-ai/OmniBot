package cn.com.omnimind.bot.agent.runtime

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Client-owned settings/resources, never another Agent execution lifecycle.
 * Operation names map to fixed service routes; caller input cannot select a URL. */
internal data class NaPersonalRoute(
    val operation: String,
    val method: String,
    val path: String,
    val payload: Map<String, Any?>? = null,
)

internal fun naPersonalRoute(operation: String, id: Any?, payload: Map<String, Any?>): NaPersonalRoute {
    val routes = mapOf(
        "status" to ("GET" to "/api/status"),
        "profile.read" to ("GET" to "/api/profile"),
        "profile.update" to ("PATCH" to "/api/profile"),
        "onboarding.start" to ("POST" to "/api/onboarding/start"),
        "onboarding.complete" to ("POST" to "/api/onboarding/complete"),
        "composio.read" to ("GET" to "/api/integrations/composio"),
        "composio.settings.read" to ("GET" to "/api/integrations/composio/settings"),
        "composio.settings.update" to ("PUT" to "/api/integrations/composio/settings"),
        "composio.authorize" to ("POST" to "/api/integrations/composio/authorize"),
        "composio.disconnect" to ("DELETE" to "/api/integrations/composio"),
        "connectors.read" to ("GET" to "/api/connectors"),
        "connectors.search" to ("POST" to "/api/connectors/search"),
        "connectors.describe" to ("POST" to "/api/connectors/describe"),
        "connectors.execute" to ("POST" to "/api/connectors/execute"),
        "connectors.result" to ("GET" to "/api/connectors/actions/{id}"),
        "memories.list" to ("GET" to "/api/memories"),
        "memories.search" to ("POST" to "/api/memories/search"),
        "memories.save" to ("POST" to "/api/memories"),
        "memories.update" to ("PUT" to "/api/memories/{id}"),
        "memories.delete" to ("DELETE" to "/api/memories/{id}"),
        "routines.list" to ("GET" to "/api/routines"),
        "routines.create" to ("POST" to "/api/routines"),
        "routines.update" to ("PATCH" to "/api/routines/{id}"),
        "routines.delete" to ("DELETE" to "/api/routines/{id}"),
        "routines.run" to ("POST" to "/api/routines/{id}/run"),
        "scenarios.list" to ("GET" to "/api/scenarios"),
        "scenarios.activate" to ("POST" to "/api/scenarios/{id}/activate"),
        "notifications.list" to ("GET" to "/api/notifications"),
        "notifications.read" to ("PATCH" to "/api/notifications/{id}"),
        "notifications.readAll" to ("POST" to "/api/notifications/read-all"),
        "actions.list" to ("GET" to "/api/actions"),
        "actions.approve" to ("POST" to "/api/actions/{id}/approve"),
        "actions.reject" to ("POST" to "/api/actions/{id}/reject"),
    )
    val (method, template) = routes[operation] ?: throw IllegalArgumentException("Na 不支持此个人助理操作。")
    val path = if (template.contains("{id}")) {
        require(id is String && id.matches(Regex("[A-Za-z0-9_-]{1,200}"))) { "Na 资源标识无效。" }
        template.replace("{id}", id)
    } else {
        require(id == null) { "此 Na 操作不接受资源标识。" }
        template
    }
    require(method != "GET" || payload.isEmpty()) { "Na 读取操作不接受参数正文。" }
    if (operation in setOf("actions.approve", "actions.reject", "routines.run", "notifications.readAll", "onboarding.start")) {
        require(payload.isEmpty()) { "此 Na 操作不接受附加参数。" }
    }
    return NaPersonalRoute(operation, method, path, if (method == "GET") null else payload)
}

internal fun naPersonalOwner(prefix: String, accountGeneration: Long, configurationRevision: Long): String =
    "na-personal:" + MessageDigest.getInstance("SHA-256")
        .digest("$prefix:$accountGeneration:$configurationRevision".toByteArray())
        .joinToString("") { "%02x".format(it) }

internal fun requireNaPersonalOwner(expected: String, supplied: Any?, initialStatus: Boolean) {
    require((initialStatus && supplied == null) || supplied == expected) {
        "Na 账号或连接已变化，请重新打开个人助理。"
    }
}

/** The captured connection holds credentials, not this response or the Flutter
 * resource model. Only safe GETs use the existing bounded transport retry. */
internal suspend fun naPersonalHttpRequest(
    connection: NaCloudConnection,
    client: OkHttpClient,
    route: NaPersonalRoute,
    assertCurrent: () -> Unit = {},
): Map<String, Any?> = withContext(Dispatchers.IO) {
    val gson = GsonBuilder().serializeNulls().create()
    val builder = Request.Builder().url(connection.apiBaseUrl + route.path).header("Accept", "application/json")
    val encoded = route.payload?.let { gson.toJson(it).toByteArray(Charsets.UTF_8) }
    require(encoded == null || encoded.size <= 1024 * 1024) { "Na 请求正文超过 1 MiB。" }
    builder.method(route.method, encoded?.toRequestBody("application/json".toMediaType()))
    assertCurrent()
    connection.execute(client, builder.build()) { response ->
        val bytes = ByteArrayOutputStream()
        response.body.byteStream().use { stream ->
            val chunk = ByteArray(8192)
            while (true) {
                val length = stream.read(chunk)
                if (length < 0) break
                check(bytes.size() + length <= 2 * 1024 * 1024) { "Na 响应超过 2 MiB，请减少查询范围后重试。" }
                bytes.write(chunk, 0, length)
            }
        }
        assertCurrent()
        val value = runCatching {
            gson.fromJson<Map<String, Any?>>(bytes.toByteArray().toString(Charsets.UTF_8), object : TypeToken<Map<String, Any?>>() {}.type)
        }.getOrNull()
        if (response.code == 401) error("Na 访问授权已失效，请重新连接。")
        check(response.isSuccessful) {
            val public = naPersonalPublicError(response.code, value?.get("error"), connection.managed)
            val submittedKey = route.payload?.get("apiKey") as? String
            if (submittedKey.isNullOrEmpty()) public else public.replace(submittedKey, "[凭据已隐藏]")
        }
        check(value != null) { "Na 返回了无效的资源数据。" }
        if (route.operation.endsWith(".authorize")) validateNaPersonalAuthorizationUrl(route.operation, value["url"])
        if (route.operation.startsWith("composio.settings.")) naPublicComposioSettings(value) else value
    }.also { assertCurrent() }
}

internal fun validateNaPersonalAuthorizationUrl(operation: String, raw: Any?) {
    require(raw is String) { "Na 未返回有效的授权地址。" }
    val url = raw.toHttpUrl()
    val allowed = when (operation) {
        "composio.authorize" -> setOf("connect.composio.dev", "app.composio.dev")
        else -> emptySet()
    }
    require(url.scheme == "https" && url.host in allowed && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()) {
        "Na 返回的授权地址不受信任。"
    }
    if (operation == "composio.authorize") require(
        url.encodedPath.matches(Regex("/link/[A-Za-z0-9_-]+/?")) && url.query == null && url.fragment == null,
    ) { "Na 返回的连接授权地址无效。" }
}

/** Standalone Na deliberately returns public validation messages. Keep those
 * actionable without surfacing credential-looking values or access URLs. */
internal fun naPersonalPublicError(status: Int, raw: Any?, managed: Boolean): String {
    if (managed || raw !is String || raw.isBlank()) return naCloudPublicError(status, raw)
    return raw.take(2000)
        .replace(Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE), "[地址已隐藏]")
        .replace(Regex("bearer\\s+[^\\s,;]+", RegexOption.IGNORE_CASE), "[凭据已隐藏]")
        .replace(Regex("(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password)\\s*[:=]\\s*[^\\s,;]+", RegexOption.IGNORE_CASE), "[凭据已隐藏]")
        .replace(Regex("\\b(?:sk|ak)[_-][A-Za-z0-9_-]{8,}"), "[凭据已隐藏]")
        .replace(Regex("[\\p{Cntrl}]+"), " ").take(500)
}


/** Write-only project credentials never cross back into Flutter, including if
 * an older/custom service accidentally includes one in its settings payload. */
internal fun naPublicComposioSettings(value: Map<String, Any?>): Map<String, Any?> {
    val raw = value["settings"] as? Map<*, *> ?: error("Na 返回了无效的连接服务设置。")
    val publicFields = setOf("enabled", "apiKeyConfigured", "configured", "toolkits", "authConfigs", "revision")
    return mapOf("settings" to raw.entries.filter { it.key in publicFields }.associate { it.key.toString() to it.value })
}
