package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.account.AccountApiException
import cn.com.omnimind.baselib.account.AccountRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Immutable connection attribution for one host operation/prompt. Account
 * refresh never changes its owner, and cannot reroute an old task after login. */
internal class NaCloudConnection(
    val baseUrl: String,
    val account: AccountRepository? = null,
    val userId: String? = null,
    val generation: Long = 0,
    private val developmentToken: String = "",
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NaCloudConnection> {
        fun officialBaseUrl(raw: String, debug: Boolean): String {
            val url = raw.trim().trimEnd('/').toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") {
                "Cloud 地址必须是不带路径或凭证的服务入口。"
            }
            require(url.scheme == "https" || (debug && url.scheme == "http" && url.host in setOf("127.0.0.1", "::1"))) {
                "Cloud 连接必须使用 HTTPS。"
            }
            return url.toString().trimEnd('/')
        }

        suspend fun managed(raw: String, debug: Boolean, account: AccountRepository): NaCloudConnection {
            val base = officialBaseUrl(raw, debug)
            val generation = account.sessionGeneration.value
            val userId = account.withCloudAccessToken(expectedGeneration = generation) { user, _ -> user.id }
            return NaCloudConnection(base, account, userId, generation)
        }
    }

    val managed get() = account != null
    val apiBaseUrl get() = baseUrl + if (managed) "/v1/cloud/na" else ""
    val prefix = "na:" + MessageDigest.getInstance("SHA-256")
        .digest((baseUrl + (userId?.let { "\n$it" } ?: "")).toByteArray())
        .take(8).joinToString("") { "%02x".format(it) } + ":"

    suspend fun <T> execute(client: OkHttpClient, request: Request, consume: (Response) -> T): T {
        // Reject redirects at the client, and credentials to any unrelated URL.
        val target = request.url
        val expected = baseUrl.toHttpUrl()
        require(target.scheme == expected.scheme && target.host == expected.host && target.port == expected.port)
        if (managed) require(target.encodedPath.startsWith("/v1/cloud/"))
        suspend fun send(token: String): T {
            currentCoroutineContext().ensureActive()
            val authenticated = request.newBuilder().apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }.build()
            return naHttpResponse(client, authenticated) { response ->
                if (managed && response.code == 401) {
                    throw AccountApiException(401, "authentication_required", "登录已失效，请重新连接。")
                }
                consume(response)
            }.also { currentCoroutineContext().ensureActive() }
        }
        return if (account == null) send(developmentToken) else account.withCloudAccessToken(
            expectedGeneration = generation, expectedUserId = userId,
            retryUnauthorized = request.method == "GET",
        ) { _, token -> send(token) }
    }

    suspend fun prepareWorkspace(client: OkHttpClient) {
        if (!managed) return
        suspend fun workspace(method: String): Map<String, Any?> {
            val path = "/v1/cloud/workspace" + if (method == "POST") "/claim" else ""
            val request = Request.Builder().url(baseUrl + path).apply {
                if (method == "POST") post("{}".toRequestBody("application/json".toMediaType()))
            }.build()
            return execute(client, request) { response ->
                val body = response.body.string()
                val value = runCatching { Gson().fromJson<Map<String, Any?>>(body, object : TypeToken<Map<String, Any?>>() {}.type) }.getOrNull() ?: emptyMap()
                check(response.isSuccessful) { naCloudPublicError(response.code, value["error"]) }
                value
            }
        }
        var state = workspace("GET")
        if (state["workspace"] == null) state = workspace("POST")
        check(state["executionAvailable"] == true) { "云端空间尚未准备好，请稍后重试。" }
    }
}

internal fun naCloudPublicError(status: Int, raw: Any?): String {
    val code = (raw as? Map<*, *>)?.get("code")?.toString()
    return when (code) {
        "cloud_entitlement_required" -> "当前账号尚未开通 Na 云端使用权限。"
        "cloud_entitlement_expired" -> "当前账号的 Na 云端使用权限已到期。"
        "cloud_capacity_exhausted" -> "Na 云端名额已满，请联系管理员。"
        "cloud_runtime_unavailable", "cloud_workspace_required" -> "云端空间尚未准备好，请稍后重试。"
        "account_authorization_unavailable" -> "账号服务暂时不可用，请稍后重试。"
        else -> if (status == 401) "登录已失效，请重新登录。" else "Na 云端请求失败（HTTP $status）。"
    }
}
