package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.baselib.account.OmniAccount
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
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
    private val appContext = context.applicationContext
    private val historyAccess = NaConversationAccess(appContext)
    private val preferences = context.getSharedPreferences("na_cloud_connection", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private var token = "" // Debug standalone-instance token; managed credentials stay in AccountRepository.
    private val mutex = Mutex()
    private val prompts = ConcurrentHashMap<String, Deferred<Map<String, Any?>>>()
    private val promptOwners = ConcurrentHashMap<String, NaCloudConnection>()
    private val activeRuns = ConcurrentHashMap<String, String>()
    private val cancelPending = ConcurrentHashMap.newKeySet<String>()
    private var cachedStatus: Map<String, Any?> = emptyMap()
    private val development get() = NaCloudEnvironment.developmentAvailable && preferences.getBoolean("development", false)
    private val baseUrl get() = if (development) preferences.getString("baseUrl", "http://127.0.0.1:8787")!! else BuildConfig.CLOUD_URL

    init {
        if (OmniAccount.isConfigured()) scope.launch {
            val account = OmniAccount.repository()
            var previous = account.sessionGeneration.value
            account.sessionGeneration.collect { current ->
                if (current != previous && !development) {
                    promptOwners.entries.filter { it.value.managed && it.value.generation != current }.forEach {
                        prompts.remove(it.key)?.cancel()
                        promptOwners.remove(it.key)
                    }
                    cachedStatus = emptyMap()
                }
                previous = current
            }
        }
    }

    private suspend fun connection() = currentCoroutineContext()[NaCloudConnection]
        ?: error("Na connection attribution is missing")

    private suspend fun <T> withConnection(operation: suspend () -> T): T {
        val connection = if (development) NaCloudConnection(baseUrl, developmentToken = token)
            else NaCloudConnection.managed(BuildConfig.CLOUD_URL, NaCloudEnvironment.developmentAvailable, OmniAccount.repository())
        return withContext(connection) { connection.prepareWorkspace(client); operation() }
    }

    fun config(): Map<String, Any?> = mapOf(
        "kind" to "na-cloud", "baseUrl" to baseUrl, "apiKey" to if (development) token else "",
        "connectionMode" to if (development) "development" else "managed",
        "developmentAvailable" to NaCloudEnvironment.developmentAvailable,
        "signedIn" to (OmniAccount.isConfigured() && runCatching { OmniAccount.repository().isSignedIn() }.getOrDefault(false)),
        "cloudModelManaged" to true,
    )

    fun configure(args: Map<String, Any?>): Map<String, Any?> {
        check(prompts.values.none { !it.isCompleted }) { "请等待当前 Na 任务结束后修改连接。" }
        val mode = args["connectionMode"]?.toString() ?: if (development) "development" else "managed"
        require(mode == "managed" || (mode == "development" && NaCloudEnvironment.developmentAvailable)) { "不支持此连接方式。" }
        if (mode == "managed") {
            preferences.edit().putBoolean("development", false).apply()
            token = ""
            cachedStatus = emptyMap()
            cn.com.omnimind.bot.webchat.FlutterChatSyncBridge.dispatchNaHistoryOwnerChanged(appContext)
            return config()
        }
        val address = args["baseUrl"]?.toString()?.trim()?.trimEnd('/') ?: baseUrl
        val url = address.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "请输入有效的 HTTP / HTTPS 服务地址。"
        }
        preferences.edit().putString("baseUrl", address).apply()
        preferences.edit().putBoolean("development", true).apply()
        args["apiKey"]?.let { token = it.toString().trim() }
        cachedStatus = emptyMap()
        cn.com.omnimind.bot.webchat.FlutterChatSyncBridge.dispatchNaHistoryOwnerChanged(appContext)
        return config()
    }

    suspend fun status(): Map<String, Any?> {
        val error = runCatching { withConnection { request("GET", "/api/status") } }
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

    suspend fun handle(method: String, args: Map<String, Any?>): Any? = when (method) {
        "agent/config/read" -> config()
        "agent/config/write" -> configure(args)
        "agent/test", "agent/prepare" -> test()
        "initialize" -> status() + mapOf("agentInfo" to mapOf("name" to "Na", "version" to "0.1.0"))
        "model/list" -> mapOf("models" to emptyList<Any>())
        "collaborationMode/list" -> mapOf("collaborationModes" to emptyList<Any>())
        "config/read" -> config()
        "session/close" -> mapOf("ok" to true)
        else -> withConnection { handleConnected(method, args) }
    }

    private suspend fun handleConnected(method: String, args: Map<String, Any?>): Any? {
        if (method.startsWith("config/remote/fs/")) {
            if (args["sessionId"] != null) resolveSession(args)
            requireNaWorkspaceOwner(connection().prefix, args["workspaceOwner"],
                required = method != "config/remote/fs/list" && args["sessionId"] == null)
        }
        return when (method) {
            "config/remote/fs/list" -> listFiles(args)
            "config/remote/fs/read" -> fileContent(args, write = false)
            "config/remote/fs/write" -> fileContent(args, write = true)
            "config/remote/fs/move" -> request("POST", "/api/files/rename", mapOf("path" to relativePath(args),
                "destination" to relativePath(mapOf("path" to args["destinationPath"]))))
            "config/remote/fs/delete" -> request("DELETE", "/api/files/content?path=" +
                java.net.URLEncoder.encode(relativePath(args), "UTF-8"))
            "session/new" -> mutex.withLock { load(args, create = true) }
            "session/load", "session/resume", "session/read" -> mutex.withLock { load(args, create = false) }
            "session/list" -> list()
            "session/prompt" -> prompt(args)
            "session/cancel" -> cancel(args)
            "session/close" -> mapOf("ok" to true) // Closing a view never deletes server history.
            else -> error("Na 云端不支持此操作：$method")
        }
    }

    private suspend fun bind(id: String, conversationId: Long?, title: String?): Long {
        val connection = connection()
        connection.account?.requireSessionGeneration(connection.generation)
        if (conversationId != null) {
            val existingOwner = profiles.naOwnerForConversation(conversationId)
            check(existingOwner == null || existingOwner == connection.prefix) { "此会话属于另一个账号。" }
        }
        val local = bindings.ensureBinding(id, NA_AGENT_ID, conversationId, cwd = "na-cloud", title = title)
        profiles.bindSession(id, NA_AGENT_ID)
        profiles.bindConversation(local, NA_AGENT_ID)
        profiles.bindNaConversationOwner(local, connection.prefix)
        connection.account?.requireSessionGeneration(connection.generation)
        return local
    }

    private suspend fun resolveSession(args: Map<String, Any?>): String? {
        val local = (args["conversationId"] as? Number)?.toLong()
        if (local != null) {
            require(historyAccess.canRead(local, connection().prefix)) {
                "此云端会话缺少当前账号的可验证归属，请切回原账号或新建会话。"
            }
        }
        local?.let { profiles.naOwnerForConversation(it) }?.let { owner ->
            require(owner == connection().prefix) { "此云端会话属于另一个账号。" }
        }
        val binding = local?.let { bindings.getBindingByConversationId(it) }
        val explicit = (args["sessionId"] ?: args["threadId"])?.toString()?.takeIf { it.isNotBlank() }
        // The persisted conversation wins over a stale view's session hint.
        val id = binding?.threadId ?: explicit ?: return null
        val prefix = connection().prefix
        require(id.startsWith(prefix)) { "此云端会话属于另一个账号或连接，请切回原账号或新建会话。" }
        return id
    }

    private suspend fun load(args: Map<String, Any?>, create: Boolean): Map<String, Any?> {
        val prefix = connection().prefix
        val existing = resolveSession(args)
        val response = if (existing == null) {
            require(create || args["conversationId"] != null) { "Na session is required" }
            request("POST", "/api/conversations", emptyMap())
        } else request("GET", "/api/conversations/${existing.removePrefix(prefix)}")
        val snapshot = response.map("conversation")
        val sessionId = existing ?: prefix + snapshot["id"]
        profiles.bindSession(sessionId, NA_AGENT_ID)
        val requestedConversationId = (args["conversationId"] as? Number)?.toLong()
        val local = if (requestedConversationId != null || bindings.getBindingByThreadId(sessionId) != null) {
            bind(sessionId, requestedConversationId, snapshot["title"]?.toString())
        } else null
        return mapOf("sessionId" to sessionId, "threadId" to sessionId, "conversationId" to local,
            "agentId" to NA_AGENT_ID, "agentRuntime" to "na", "configOptions" to emptyList<Any>(),
            "thread" to naThreadSnapshot(sessionId, response))
    }

    private suspend fun list(): Map<String, Any?> {
        val prefix = connection().prefix
        val entries = request("GET", "/api/conversations")["conversations"] as? List<*> ?: emptyList<Any>()
        val sessions = entries.mapNotNull { raw ->
            val c = raw as? Map<*, *> ?: return@mapNotNull null
            val id = prefix + c["id"]
            if (profiles.agentIdForSession(id) != null && bindings.getBindingByThreadId(id) == null) {
                return@mapNotNull null
            }
            val local = bind(id, null, c["title"]?.toString())
            mapOf("id" to id, "sessionId" to id, "threadId" to id, "conversationId" to local,
                "agentId" to NA_AGENT_ID, "agentRuntime" to "na", "title" to c["title"],
                "preview" to c["title"], "cwd" to "na-cloud")
        }
        return mapOf("sessions" to sessions, "threads" to sessions, "data" to sessions)
    }

    private suspend fun prompt(args: Map<String, Any?>): Map<String, Any?> {
        val input = AcpPromptInputCompatibilityAdapter.normalize(args)
        val attachments = (input["attachments"] as? List<*>)?.map { raw ->
            require(raw is Map<*, *>) { "附件信息无效，请重新选择。" }
            raw.entries.associate { it.key.toString() to it.value }
        }.orEmpty()
        val id = resolveSession(args) ?: error("Na session is required")
        val reservation = args["requestId"]?.toString()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val connection = connection()
        val prefix = connection.prefix
        val key = "$id/$reservation"
        val execution = mutex.withLock {
            prompts[key] ?: scope.async(connection) {
                val local = bind(id, (args["conversationId"] as? Number)?.toLong(), null)
                val promptContext = currentCoroutineContext()
                val checkActive: () -> Unit = {
                    promptContext.ensureActive()
                    connection.account?.requireSessionGeneration(connection.generation)
                    if (cancelPending.contains(id)) throw NaAttachmentCancelled()
                }
                var admittedRunId: String? = null
                try {
                    val uploadOwner = mapOf("conversationId" to id.removePrefix(prefix), "clientMessageId" to reservation)
                    val attachmentIds = mutableListOf<String>()
                    if (attachments.isNotEmpty()) {
                        val capabilities = request("GET", "/api/status")["capabilities"] as? List<*>
                        check(capabilities?.contains("attachments") == true) { "云端附件服务尚未更新，请稍后再试。" }
                    }
                    // One admission after all uploads; neither PUT nor POST is replayed.
                    val accepted = naSubmitWithAttachments(
                        input["text"]?.toString().orEmpty(), attachments, key, appContext.cacheDir,
                        ::openAttachment,
                        { path, file, active ->
                            val attachment = attachments[attachmentIds.size]
                            attachmentIds += path.removePrefix("attachment-").take(36)
                            val mime = attachment["mimeType"]?.toString()?.takeIf { it.matches(Regex("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+")) }
                                ?: "application/octet-stream"
                            naUploadAttachment(connection, client, path, file, active, uploadOwner + ("mimeType" to mime))
                        },
                        checkActive,
                        includeReferences = false,
                        cleanup = { request("DELETE", "/api/files/uploads", uploadOwner) },
                    ) { text ->
                        request("POST", "/api/conversations/${id.removePrefix(prefix)}/messages",
                            mapOf("text" to text, "clientMessageId" to reservation, "attachmentIds" to attachmentIds) +
                                (args["userMessageId"]?.toString()?.takeIf { it.isNotBlank() }?.let { mapOf("clientUserMessageId" to it) } ?: emptyMap()))
                    }
                    val runId = accepted["runId"].toString()
                    admittedRunId = runId
                    activeRuns[id] = runId
                    val projector = NaItemProjection(reservation)
                    if (cancelPending.remove(id)) request("POST", "/api/runs/$runId/cancel", emptyMap())
                    withTimeout(31 * 60 * 1000L) {
                        val run = awaitNaRun(runId,
                            snapshot = { request("GET", "/api/runs/$runId") },
                            subscribe = { cursor, receive ->
                                val request = Request.Builder().url(connection.apiBaseUrl + "/api/events?after=$cursor")
                                    .header("Accept", "text/event-stream").build()
                                connection.execute(client, request, retryReads = false) { response ->
                                    if (response.code == 409) throw IOException("Na event history compacted")
                                    check(response.isSuccessful) { naCloudPublicError(response.code, null) }
                                    check(response.header("Content-Type").orEmpty().startsWith("text/event-stream")) {
                                        "Na returned an invalid event stream"
                                    }
                                    readNaEvents(response.body.source(), receive)
                                }
                            },
                            checkOwner = {
                                promptContext.ensureActive()
                                connection.account?.requireSessionGeneration(connection.generation)
                            },
                            onItem = { item ->
                                projector.updates(item).forEach { update ->
                                    promptContext.ensureActive()
                                    connection.account?.requireSessionGeneration(connection.generation)
                                    emit(mapOf("method" to "session/update", "conversationId" to local,
                                        "agentId" to NA_AGENT_ID, "sessionId" to id, "turnId" to reservation,
                                        "params" to mapOf("sessionId" to id, "turnId" to reservation, "update" to update)))
                                }
                            })
                        mapOf("sessionId" to id, "threadId" to id,
                            "conversationId" to local, "turnId" to reservation,
                            "stopReason" to when(run["status"]) { "completed" -> "end_turn"; "cancelled" -> "cancelled"; else -> "error" },
                            "error" to run["error"])
                    }
                } catch (_: NaAttachmentCancelled) {
                    mapOf("sessionId" to id, "threadId" to id, "conversationId" to local,
                        "turnId" to reservation, "stopReason" to "cancelled")
                } finally { admittedRunId?.let { activeRuns.remove(id, it) }; cancelPending.remove(id) }
            }.also { promptOwners[key] = connection; prompts[key] = it }
        }
        try { return execution.await() } finally {
            // Retain completed reservations for concurrent duplicate callers, bounded.
            if (prompts.size > 128) prompts.entries.filter { it.value.isCompleted }.take(64)
                .forEach { prompts.remove(it.key, it.value); promptOwners.remove(it.key) }
        }
    }

    private fun openAttachment(attachment: Map<String, Any?>): InputStream {
        val dataUrl = attachment["dataUrl"]?.toString().orEmpty()
        if (dataUrl.isNotEmpty()) {
            val separator = dataUrl.indexOf(',')
            require(separator in 1..256 && dataUrl.substring(0, separator).endsWith(";base64")) { "附件编码无效。" }
            require(dataUrl.length - separator - 1 <= ((NA_ATTACHMENT_MAX_BYTES + 2) / 3 * 4)) { "附件不能超过 10 MiB，请重新选择。" }
            return ByteArrayInputStream(Base64.getDecoder().decode(dataUrl.substring(separator + 1)))
        }
        val path = attachment["path"]?.toString().orEmpty()
        require(path.isNotBlank()) { "附件不可读，请重新选择。" }
        val uri = android.net.Uri.parse(path)
        return when (uri.scheme?.lowercase()) {
            "content" -> appContext.contentResolver.openInputStream(uri)
                ?: error("附件读取权限已失效，请重新选择。")
            "file", null -> {
                val file = File(if (uri.scheme == null) path else uri.path.orEmpty())
                require(file.isAbsolute && file.isFile) { "附件不可读，请重新选择。" }
                require(file.length() <= NA_ATTACHMENT_MAX_BYTES) { "附件不能超过 10 MiB，请重新选择。" }
                file.inputStream()
            }
            else -> error("请从手机选择文件后上传附件。")
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
        return mapOf("ok" to true, "path" to path, "cwd" to "/workspace", "workspaceOwner" to connection().prefix,
            "parent" to if(relative.isEmpty()) null else path.substringBeforeLast('/'),
            "entries" to entries.values.sortedBy { it["name"].toString() })
    }

    private suspend fun fileContent(args: Map<String, Any?>, write: Boolean): Map<String, Any?> = withContext(Dispatchers.IO) {
        val relative = relativePath(args)
        require(relative.isNotEmpty()) { "File path is required" }
        val connection = connection()
        val url = (connection.apiBaseUrl + "/api/files/content").toHttpUrl().newBuilder().addQueryParameter("path", relative).build()
        val builder = Request.Builder().url(url)
        if (write) {
            val content = args["content"]?.toString().orEmpty().toByteArray(Charsets.UTF_8)
            require(content.size <= 10 * 1024 * 1024) { "Na file exceeds 10 MiB" }
            builder.put(content.toRequestBody("application/octet-stream".toMediaType()))
        }
        connection.execute(client, builder.build()) consume@{ response ->
            check(response.isSuccessful) { "Na file HTTP ${response.code}" }
            if (write) return@consume mapOf("ok" to true, "saved" to true)
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
            val connection = connection()
            val builder = Request.Builder().url(connection.apiBaseUrl + path)
                .header("Accept", "application/json")
            builder.method(method, if (method == "GET") null else gson.toJson(body ?: emptyMap<String, Any?>())
                .toRequestBody("application/json".toMediaType()))
            connection.execute(client, builder.build()) { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code == 401) error("Na 访问令牌无效，请在设置 → Agent 模式 → Na 中更新。")
                val value: Map<String, Any?> = runCatching {
                    gson.fromJson<Map<String, Any?>>(raw, object : TypeToken<Map<String, Any?>>() {}.type)
                }.getOrNull() ?: emptyMap()
                check(response.isSuccessful) { naCloudPublicError(response.code, value["error"]) }
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
internal class NaItemProjection(private val turnId: String) {
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
            // ACP tool ids are session-unique; Na runner ids are only run-unique.
            // Unlike messageId, the shared reducer does not add a turn prefix.
            "toolCallId" to "$turnId-$id", "title" to (item["command"] ?: item["tool"] ?: type ?: "Tool"),
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
            "content" to listOf(mapOf("type" to "text", "text" to (run["userText"] ?: run["input"]))), "createdAt" to run["createdAt"],
            "attachments" to naHistoryAttachments(sessionId, run["attachments"]),
            "hostMessageId" to run["clientUserMessageId"]))
        (run["items"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { item ->
            val itemId = "$turnId-${item["id"]}"
            val identity = if (item["type"] in setOf("agent_message", "reasoning")) emptyMap()
                else mapOf("sessionId" to sessionId, "turnId" to turnId, "toolCallId" to itemId)
            items += item.entries.associate { it.key.toString() to it.value } + ("id" to itemId) + identity
        }
        mapOf("id" to turnId, "status" to run["status"], "startedAt" to run["createdAt"], "items" to items,
            "stopReason" to when (run["status"]) {
                "completed" -> "end_turn"
                "cancelled" -> "cancelled"
                "failed" -> "error"
                else -> null
            })
    } ?: emptyList()
    return mapOf("id" to sessionId, "agentId" to NA_AGENT_ID, "agentName" to "Na", "title" to conversation["title"], "turns" to turns)
}

internal fun requireNaWorkspaceOwner(expected: String, supplied: Any?, required: Boolean) {
    require((supplied == null && !required) || supplied == expected) {
        "云端账号或连接已变化，请重新打开文件窗口。"
    }
}

internal fun naHistoryAttachments(sessionId: String, value: Any?): List<Map<String, Any?>> =
    (value as? List<*>)?.filterIsInstance<Map<*, *>>()?.mapNotNull { row ->
        val path = row["path"] as? String ?: return@mapNotNull null
        if (path.startsWith('/') || path.contains('\\') || path.split('/').any { it.isEmpty() || it.startsWith('.') }) return@mapNotNull null
        val size = (row["size"] as? Number)?.toLong() ?: return@mapNotNull null
        if (size < 0 || size > NA_ATTACHMENT_MAX_BYTES) return@mapNotNull null
        mapOf("id" to row["id"], "name" to row["name"], "size" to size, "mimeType" to row["mimeType"],
            "path" to "/workspace/$path", "source" to "na-cloud", "sessionId" to sessionId,
            "isImage" to false, "deleted" to (row["deleted"] == true), "sendToModel" to false)
    }.orEmpty()

/** One transport owner for safe reads. Writes, including a lost prompt POST
 * response, are never retried. A read snapshot cannot admit another turn. */
internal suspend fun <T> naHttpResponse(client: OkHttpClient, request: Request, retryReads: Boolean = true, consume: (Response) -> T): T {
    var failures = 0
    while (true) {
        try {
            return suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        continuation.resumeWithException(error)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try { continuation.resume(response.use(consume)) }
                        catch (error: Exception) { continuation.resumeWithException(error) }
                    }
                })
            }
        } catch (error: IOException) {
            if (!retryReads || request.method != "GET" || ++failures >= 3) throw error
            delay(1000L * failures)
        }
    }
}
