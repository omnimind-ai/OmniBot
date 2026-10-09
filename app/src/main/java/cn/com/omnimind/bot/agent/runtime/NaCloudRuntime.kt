package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.net.URLConnection
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal const val NA_AGENT_ID = "na-cloud"

/** HTTP transport for the existing Agent session boundary. Na owns execution,
 * model configuration and approvals; the App keeps its existing Conversation
 * bindings, reducer, prompt completion and history UI. No ACP server is used.
 */
internal class NaCloudRuntime(
    context: Context,
    private val scope: CoroutineScope,
    private val bindings: AgentSessionBindingRepository,
    private val profiles: AcpAgentProfileStore,
    private val emit: (Map<String, Any?>) -> Unit,
) {
    private val preferences = context.getSharedPreferences("na_cloud_connection", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private var token = "" // Account access token only; never the model-provider API key.
    private val mutex = Mutex()
    private val prompts = ConcurrentHashMap<String, Deferred<Map<String, Any?>>>()
    private val activeRuns = ConcurrentHashMap<String, String>()
    private val cancelPending = ConcurrentHashMap.newKeySet<String>()
    private var cachedStatus: Map<String, Any?> = emptyMap()
    private val baseUrl get() = preferences.getString("baseUrl", "http://127.0.0.1:8787")!!
    private val prefix get() = "na:" + MessageDigest.getInstance("SHA-256")
        .digest(baseUrl.toByteArray()).take(8).joinToString("") { "%02x".format(it) } + ":"

    fun config(): Map<String, Any?> = mapOf(
        "kind" to "na-cloud", "baseUrl" to baseUrl, "apiKey" to token,
        "cloudModelManaged" to true,
    )

    fun configure(args: Map<String, Any?>): Map<String, Any?> {
        check(prompts.values.none { !it.isCompleted }) { "请等待当前 Na 任务结束后修改连接。" }
        val address = args["baseUrl"]?.toString()?.trim()?.trimEnd('/') ?: baseUrl
        val url = address.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "请输入有效的 HTTP / HTTPS 服务地址。"
        }
        preferences.edit().putString("baseUrl", address).apply()
        args["apiKey"]?.let { token = it.toString().trim() }
        cachedStatus = emptyMap()
        return config()
    }

    suspend fun status(): Map<String, Any?> {
        val error = runCatching { request("GET", "/api/status") }
            .onSuccess { cachedStatus = it }.exceptionOrNull()
        val connected = error == null && cachedStatus["connected"] == true
        return mapOf(
            "connected" to connected, "ready" to connected, "runtime" to "na",
            "remoteEnabled" to false, "protocol" to "http", "activeAgentId" to NA_AGENT_ID,
            "activeAgentName" to "Na", "version" to cachedStatus["version"],
            "error" to (error?.message ?: cachedStatus["executionBlocked"]),
            "capabilities" to mapOf("cloudModelManaged" to true),
        )
    }

    suspend fun test(): Map<String, Any?> {
        val status = status()
        val online = status["connected"] == true
        profiles.saveHealth(NA_AGENT_ID, AcpAgentHealth(
            status = if (online) AcpAgentHealth.STATUS_ONLINE else AcpAgentHealth.STATUS_OFFLINE,
            installed = true, error = status["error"]?.toString(), checkedAt = System.currentTimeMillis(),
        ))
        return status + mapOf("ok" to online, "status" to if (online) "online" else "offline")
    }

    suspend fun owns(method: String, args: Map<String, Any?>, remoteEnabled: Boolean): Boolean {
        // Configuration/catalog requests have an explicit profile owner.
        if (method.startsWith("config/remote/fs/")) return args["agentId"] == NA_AGENT_ID
        if (method.startsWith("agent/")) return args["agentId"] == NA_AGENT_ID &&
            method in setOf("agent/test", "agent/prepare", "agent/config/read", "agent/config/write")
        if (args["conversationMode"] != null && args["conversationMode"] != "agent") return false
        val conversation = (args["conversationId"] as? Number)?.toLong()
        val bound = conversation?.let { profiles.agentIdForConversation(it) }
        if (bound != null) return bound == NA_AGENT_ID
        val session = (args["sessionId"] ?: args["threadId"])?.toString()
        if (!session.isNullOrBlank()) return session.startsWith("na:")
        val explicit = args["agentId"]?.toString()
        return (explicit == NA_AGENT_ID || (explicit == null && !remoteEnabled && profiles.selected().id == NA_AGENT_ID)) &&
            (method.startsWith("session/") || method in setOf("initialize", "model/list", "collaborationMode/list", "config/read"))
    }

    suspend fun handle(method: String, args: Map<String, Any?>): Any? = when (method) {
        "agent/config/read" -> config()
        "agent/config/write" -> configure(args)
        "agent/test", "agent/prepare" -> test()
        "initialize" -> status() + mapOf("agentInfo" to mapOf("name" to "Na", "version" to "0.1.0"))
        "model/list" -> mapOf("models" to emptyList<Any>())
        "collaborationMode/list" -> mapOf("collaborationModes" to emptyList<Any>())
        "config/read" -> config()
        "config/remote/fs/list" -> listFiles(args)
        "config/remote/fs/read" -> fileContent(args, write = false)
        "config/remote/fs/write" -> fileContent(args, write = true)
        "session/new" -> mutex.withLock { load(args, create = true) }
        "session/load", "session/resume", "session/read" -> mutex.withLock { load(args, create = false) }
        "session/list" -> list()
        "session/prompt" -> prompt(args)
        "session/cancel" -> cancel(args)
        "session/close" -> mapOf("ok" to true) // Closing a view never deletes server history.
        else -> error("Na 云端不支持此操作：$method")
    }

    private suspend fun bind(id: String, conversationId: Long?, title: String?): Long {
        val local = bindings.ensureBinding(id, conversationId, cwd = "na-cloud", title = title)
        profiles.bindSession(id, NA_AGENT_ID)
        profiles.bindConversation(local, NA_AGENT_ID)
        return local
    }

    private suspend fun resolveSession(args: Map<String, Any?>): String? {
        val local = (args["conversationId"] as? Number)?.toLong()
        val binding = local?.let { bindings.getBindingByConversationId(it) }
        val explicit = (args["sessionId"] ?: args["threadId"])?.toString()?.takeIf { it.isNotBlank() }
        // The persisted conversation wins over a stale view's session hint.
        val id = binding?.threadId ?: explicit ?: return null
        require(id.startsWith(prefix)) { "此会话属于另一 Na 实例，请恢复原连接或创建新会话。" }
        return id
    }

    private suspend fun load(args: Map<String, Any?>, create: Boolean): Map<String, Any?> {
        profiles.select(NA_AGENT_ID)
        val existing = resolveSession(args)
        val response = if (existing == null) {
            require(create || args["conversationId"] != null) { "Na session is required" }
            request("POST", "/api/conversations", emptyMap())
        } else request("GET", "/api/conversations/${existing.removePrefix(prefix)}")
        val snapshot = response.map("conversation")
        val sessionId = existing ?: prefix + snapshot["id"]
        val local = bind(sessionId, (args["conversationId"] as? Number)?.toLong(), snapshot["title"]?.toString())
        return mapOf("sessionId" to sessionId, "threadId" to sessionId, "conversationId" to local,
            "agentId" to NA_AGENT_ID, "agentRuntime" to "na", "configOptions" to emptyList<Any>(),
            "thread" to naThreadSnapshot(sessionId, response))
    }

    private suspend fun list(): Map<String, Any?> {
        val entries = request("GET", "/api/conversations")["conversations"] as? List<*> ?: emptyList<Any>()
        val sessions = entries.mapNotNull { raw ->
            val c = raw as? Map<*, *> ?: return@mapNotNull null
            val id = prefix + c["id"]
            val local = bind(id, null, c["title"]?.toString())
            mapOf("id" to id, "sessionId" to id, "threadId" to id, "conversationId" to local,
                "agentId" to NA_AGENT_ID, "agentRuntime" to "na", "title" to c["title"],
                "preview" to c["title"], "cwd" to "na-cloud")
        }
        return mapOf("sessions" to sessions, "threads" to sessions, "data" to sessions)
    }

    private suspend fun prompt(args: Map<String, Any?>): Map<String, Any?> {
        require((args["attachments"] as? List<*>)?.isNotEmpty() != true) {
            "Na 暂不支持从 App 上传附件，请先在云端工作区准备文件。"
        }
        val id = resolveSession(args) ?: error("Na session is required")
        val reservation = args["requestId"]?.toString()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val key = "$id/$reservation"
        val execution = mutex.withLock {
            prompts[key] ?: scope.async {
                val local = bind(id, (args["conversationId"] as? Number)?.toLong(), null)
                // One write per logical submission; GET reconnection never replays it.
                val accepted = request("POST", "/api/conversations/${id.removePrefix(prefix)}/messages",
                    mapOf("text" to args["text"], "clientMessageId" to reservation) +
                        (args["userMessageId"]?.toString()?.takeIf { it.isNotBlank() }?.let { mapOf("clientUserMessageId" to it) } ?: emptyMap()))
                val runId = accepted["runId"].toString()
                activeRuns[id] = runId
                val projector = NaItemProjection()
                try {
                    if (cancelPending.remove(id)) request("POST", "/api/runs/$runId/cancel", emptyMap())
                    withTimeout(31 * 60 * 1000L) {
                        while (true) {
                            val snapshot = request("GET", "/api/runs/$runId")
                            val run = snapshot.map("run").ifEmpty { snapshot }
                            (run["items"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { raw ->
                                projector.updates(raw.entries.associate { it.key.toString() to it.value }).forEach { update ->
                                    emit(mapOf("method" to "session/update", "conversationId" to local,
                                        "agentId" to NA_AGENT_ID, "sessionId" to id, "turnId" to reservation,
                                        "params" to mapOf("sessionId" to id, "turnId" to reservation, "update" to update)))
                                }
                            }
                            val state = run["status"]?.toString()
                            if (state in setOf("completed", "cancelled", "failed")) {
                                return@withTimeout mapOf("sessionId" to id, "threadId" to id,
                                    "conversationId" to local, "turnId" to reservation,
                                    "stopReason" to when(state) { "completed" -> "end_turn"; "cancelled" -> "cancelled"; else -> "error" },
                                    "error" to run["error"])
                            }
                            delay(600)
                        }
                        @Suppress("UNREACHABLE_CODE") emptyMap<String, Any?>()
                    }
                } finally { activeRuns.remove(id, runId); cancelPending.remove(id) }
            }.also { prompts[key] = it }
        }
        try { return execution.await() } finally {
            // Retain completed reservations for concurrent duplicate callers, bounded.
            if (prompts.size > 128) prompts.entries.filter { it.value.isCompleted }.take(64)
                .forEach { prompts.remove(it.key, it.value) }
        }
    }

    private suspend fun cancel(args: Map<String, Any?>): Map<String, Any?> {
        val id = resolveSession(args) ?: return mapOf("ok" to true)
        val run = activeRuns[id]
        if (run != null) request("POST", "/api/runs/$run/cancel", emptyMap())
        else if (prompts.entries.any { it.key.startsWith("$id/") && !it.value.isCompleted }) cancelPending.add(id)
        return mapOf("ok" to true, "sessionId" to id)
    }

    private fun relativePath(args: Map<String, Any?>): String {
        val path = args["path"]?.toString() ?: "/workspace"
        require(path == "/workspace" || path.startsWith("/workspace/")) { "Path is outside Na workspace" }
        val relative = path.removePrefix("/workspace").trim('/')
        require(relative.split('/').none { it == ".." || it == "." }) { "Invalid Na path" }
        return relative
    }

    private suspend fun listFiles(args: Map<String, Any?>): Map<String, Any?> {
        val relative = relativePath(args)
        val path = "/workspace" + if(relative.isEmpty()) "" else "/$relative"
        val all = request("GET", "/api/files")["files"] as? List<*> ?: emptyList<Any>()
        val entries = linkedMapOf<String, Map<String, Any?>>()
        all.filterIsInstance<Map<*, *>>().forEach { file ->
            val full = file["path"]?.toString() ?: return@forEach
            val head = if(relative.isEmpty()) "" else "$relative/"
            if (!full.startsWith(head)) return@forEach
            val tail = full.removePrefix(head)
            if (tail.isEmpty()) return@forEach
            val name = tail.substringBefore('/')
            entries[name] = mapOf("name" to name, "path" to "$path/$name",
                "type" to if('/' in tail) "directory" else "file", "size" to file["size"], "hidden" to name.startsWith('.'))
        }
        return mapOf("ok" to true, "path" to path, "cwd" to "/workspace",
            "parent" to if(relative.isEmpty()) null else path.substringBeforeLast('/'),
            "entries" to entries.values.sortedBy { it["name"].toString() })
    }

    private suspend fun fileContent(args: Map<String, Any?>, write: Boolean): Map<String, Any?> = withContext(Dispatchers.IO) {
        val relative = relativePath(args)
        require(relative.isNotEmpty()) { "File path is required" }
        val url = (baseUrl.trimEnd('/') + "/api/files/content").toHttpUrl().newBuilder().addQueryParameter("path", relative).build()
        val builder = Request.Builder().url(url)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        if (write) {
            val content = args["content"]?.toString().orEmpty().toByteArray(Charsets.UTF_8)
            require(content.size <= 10 * 1024 * 1024) { "Na file exceeds 10 MiB" }
            builder.put(content.toRequestBody("application/octet-stream".toMediaType()))
        }
        naHttpResponse(client, builder.build()) { response ->
            check(response.isSuccessful) { "Na file HTTP ${response.code}" }
            if (write) return@naHttpResponse mapOf("ok" to true, "saved" to true)
            val bytes = ByteArrayOutputStream()
            response.body!!.byteStream().use { stream ->
                val chunk = ByteArray(8192)
                while (true) {
                    val size = stream.read(chunk)
                    if (size < 0) break
                    check(bytes.size() + size <= 20 * 1024 * 1024) { "Na file exceeds 20 MiB" }
                    bytes.write(chunk, 0, size)
                }
            }
            val data = bytes.toByteArray()
            val extension = relative.substringAfterLast('.', "").lowercase()
            val textLike = extension in setOf("md", "txt", "json", "yaml", "yml", "csv", "ts", "js", "py", "html", "css", "sh", "xml", "toml", "log")
            val mime = URLConnection.guessContentTypeFromName(relative) ?: if(textLike) "text/plain" else "application/octet-stream"
            mapOf("ok" to true, "path" to "/workspace/$relative", "name" to relative.substringAfterLast('/'),
                "size" to data.size, "mimeType" to mime,
                "previewKind" to if(textLike) "text" else if(mime.startsWith("image/")) "image" else "file",
                "encoding" to if(textLike) "utf8" else "base64", "content" to if(textLike) data.toString(Charsets.UTF_8) else null,
                "dataBase64" to Base64.getEncoder().encodeToString(data), "truncated" to false)
        }
    }

    private suspend fun request(method: String, path: String, body: Map<String, Any?>? = null): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(baseUrl.trimEnd('/') + path)
                .header("Accept", "application/json")
            if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
            builder.method(method, if (method == "GET") null else gson.toJson(body ?: emptyMap<String, Any?>())
                .toRequestBody("application/json".toMediaType()))
            naHttpResponse(client, builder.build()) { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code == 401) error("Na 访问令牌无效，请在设置 → Agent 模式 → Na 中更新。")
                val value: Map<String, Any?> = runCatching {
                    gson.fromJson<Map<String, Any?>>(raw, object : TypeToken<Map<String, Any?>>() {}.type)
                }.getOrNull() ?: emptyMap()
                check(response.isSuccessful) { value["error"]?.toString() ?: "Na HTTP ${response.code}" }
                value
            }
        }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.map(key: String): Map<String, Any?> = this[key] as? Map<String, Any?> ?: emptyMap()

/** Snapshot normalization only. Business state/projection remains in the one
 * shared AgentEventReducer. Item ids and suffix offsets make repeated reads
 * idempotent and preserve whitespace across message/reasoning chunks.
 */
internal class NaItemProjection {
    private val textById = mutableMapOf<String, String>()
    private val toolsById = mutableMapOf<String, Map<String, Any?>>()
    fun updates(item: Map<String, Any?>): List<Map<String, Any?>> {
        val id = item["id"]?.toString() ?: return emptyList()
        val type = item["type"]?.toString()
        if (type == "agent_message" || type == "reasoning") {
            val text = item["text"]?.toString().orEmpty()
            val previous = textById[id].orEmpty()
            if (text == previous) return emptyList()
            check(text.startsWith(previous)) { "Na text snapshot changed after visible output" }
            textById[id] = text
            return listOf(mapOf("sessionUpdate" to if (type == "agent_message") "agent_message_chunk" else "agent_thought_chunk",
                "messageId" to id, "content" to mapOf("type" to "text", "text" to text.substring(previous.length))))
        }
        if (toolsById[id] == item) return emptyList()
        val first = !toolsById.containsKey(id)
        toolsById[id] = item.toMap()
        val status = when (item["status"]) { "completed" -> "completed"; "failed" -> "failed"; else -> "in_progress" }
        val kind = when(type) { "command_execution" -> "execute"; "file_change" -> "edit"; "web_search" -> "search"; else -> "other" }
        val content = item["aggregated_output"] ?: item["result"] ?: item["output"] ?: item["text"]
        val update = mapOf("sessionUpdate" to if(first) "tool_call" else "tool_call_update",
            "toolCallId" to id, "title" to (item["command"] ?: item["tool"] ?: type ?: "Tool"),
            "kind" to kind, "status" to status, "rawInput" to item,
            "content" to if(content == null) emptyList<Any>() else listOf(mapOf("type" to "content", "content" to mapOf("type" to "text", "text" to content.toString()))))
        return if (first && status in setOf("completed", "failed")) listOf(update + ("status" to "in_progress"), update + ("sessionUpdate" to "tool_call_update")) else listOf(update)
    }
}

/** Na ids are scoped exactly like live session/update projection. The App's
 * existing snapshot mapper and coordinator merge these with committed history.
 */
internal fun naThreadSnapshot(sessionId: String, response: Map<String, Any?>): Map<String, Any?> {
    val conversation = response["conversation"] as? Map<*, *> ?: emptyMap<Any, Any>()
    val turns = (response["runs"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.map { run ->
        val turnId = (run["clientMessageId"] ?: run["id"]).toString()
        val items = mutableListOf<Map<String, Any?>>(mapOf("id" to "$turnId-user", "type" to "userMessage",
            "content" to listOf(mapOf("type" to "text", "text" to run["input"])), "createdAt" to run["createdAt"],
            "hostMessageId" to run["clientUserMessageId"]))
        (run["items"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { item ->
            items += item.entries.associate { it.key.toString() to it.value } + ("id" to "$turnId-${item["id"]}")
        }
        mapOf("id" to turnId, "status" to run["status"], "startedAt" to run["createdAt"], "items" to items)
    } ?: emptyList()
    return mapOf("id" to sessionId, "agentId" to NA_AGENT_ID, "agentName" to "Na", "title" to conversation["title"], "turns" to turns)
}

/** One transport owner for safe reads. Writes, including a lost prompt POST
 * response, are never retried. A read snapshot cannot admit another turn. */
internal suspend fun <T> naHttpResponse(client: OkHttpClient, request: Request, consume: (Response) -> T): T {
    var failures = 0
    while (true) {
        try {
            return withContext(Dispatchers.IO) { client.newCall(request).execute().use(consume) }
        } catch (error: IOException) {
            if (request.method != "GET" || ++failures >= 3) throw error
            delay(1000L * failures)
        }
    }
}
