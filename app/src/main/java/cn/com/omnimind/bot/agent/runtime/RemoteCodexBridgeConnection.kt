package cn.com.omnimind.bot.agent.runtime

import android.util.Base64
import android.util.Log
import cn.com.omnimind.bot.agent.readAgentAttachmentBytes
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val REMOTE_BRIDGE_TAG = "RemoteCodexBridge"

/**
 * OkHttp invokes WebSocket callbacks in arrival order, but ACP callbacks are
 * suspending. Keep the transport order after that boundary as well: launching
 * every callback independently would let a later response overtake an earlier
 * session/update or terminal frame.
 */
internal class RemoteCodexInboundEventQueue(
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit = {}
) {
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val terminal = AtomicBoolean(false)
    private val drainJob = scope.launch {
        for (event in events) {
            try {
                event.invoke()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onFailure(error)
            }
        }
    }

    fun offer(event: suspend () -> Unit): Boolean {
        if (terminal.get()) return false
        return events.trySend(event).isSuccess
    }

    /**
     * A transport terminal event is a lifecycle boundary, not another FIFO
     * payload. Cancel queued/in-flight delivery first, then dispatch the
     * terminal callback independently so a stuck session/update cannot strand
     * every active turn in a loading state.
     */
    fun offerTerminal(event: suspend () -> Unit): Boolean {
        if (!terminal.compareAndSet(false, true)) return false
        events.close()
        drainJob.cancel()
        scope.launch {
            try {
                event.invoke()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onFailure(error)
            }
        }
        return true
    }

    suspend fun close() {
        terminal.set(true)
        events.close()
        drainJob.join()
    }
}

internal class RemoteCodexBridgeConnection(
    private val config: CodexRemoteBridgeConfig,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = sharedClient
) : RemoteCodexAppServerConnection {
    private val gson = Gson()
    private val started = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private val inboundEvents = RemoteCodexInboundEventQueue(scope) { error ->
        Log.w(REMOTE_BRIDGE_TAG, "Inbound bridge event failed: ${error.message}")
    }
    private val terminalQueued = AtomicBoolean(false)

    @Volatile
    private var webSocket: WebSocket? = null

    override val isRunning: Boolean
        get() = webSocket != null && !closed.get()

    override suspend fun start(
        onStdoutLine: suspend (String) -> Unit,
        onStderrLine: suspend (String) -> Unit,
        onExit: suspend (Int?) -> Unit
    ) {
        check(config.isConfigured) { "Remote Codex bridge URL and cwd are required." }
        val request = Request.Builder()
            .url(normalizeCodexBridgeWebSocketUrl(config.bridgeUrl))
            .applyBridgeAuth(config.authToken)
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@RemoteCodexBridgeConnection.webSocket = webSocket
                val sent = webSocket.send(
                    gson.toJson(
                        mapOf(
                            "type" to "hello",
                            "protocol" to "acp",
                            "client" to "omnibot_android",
                            "token" to config.authToken,
                            "cwd" to config.cwd.trim()
                        )
                    )
                )
                if (!sent && !started.isCompleted) {
                    val error = IllegalStateException("Codex bridge hello send failed.")
                    closed.set(true)
                    started.completeExceptionally(error)
                    if (terminalQueued.compareAndSet(false, true)) {
                        enqueueTerminal {
                            onStderrLine(error.message.orEmpty())
                            onExit(null)
                        }
                    }
                    webSocket.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val terminalFrame = bridgeMessageType(text) == "exit"
                // OkHttp may deliver a callback that raced with failure or a
                // clean close. Once this transport has a terminal boundary,
                // no later frame may enter the ACP session again.
                if (closed.get() || terminalQueued.get()) {
                    return
                }
                if (terminalFrame &&
                    !terminalQueued.compareAndSet(false, true)
                ) {
                    return
                }
                if (terminalFrame) {
                    enqueueTerminal {
                        handleBridgeMessage(
                            raw = text,
                            onStdoutLine = onStdoutLine,
                            onStderrLine = onStderrLine,
                            onExit = onExit
                        )
                    }
                    return
                }
                enqueueInbound {
                    // Frames can already be queued when an earlier callback
                    // fails. Re-check at execution time so those stale
                    // frames cannot run after the terminal boundary.
                    if (!terminalFrame && (closed.get() || terminalQueued.get())) {
                        return@enqueueInbound
                    }
                    try {
                        handleBridgeMessage(
                            raw = text,
                            onStdoutLine = onStdoutLine,
                            onStderrLine = onStderrLine,
                            onExit = onExit
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        // The queue cannot recover a failed ACP callback by
                        // dropping that event: if it was the terminal update,
                        // Flutter would keep the turn in "thinking" forever.
                        // Fail the transport through the same lifecycle path
                        // used by WebSocket onFailure/onClosed instead.
                        handleInboundEventFailure(
                            error = error,
                            onStderrLine = onStderrLine,
                            onExit = onExit
                        )
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.set(true)
                if (!started.isCompleted) {
                    started.completeExceptionally(t)
                }
                if (terminalQueued.compareAndSet(false, true)) {
                    enqueueTerminal {
                        onStderrLine(t.message ?: t.javaClass.simpleName)
                        onExit(null)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.set(true)
                if (terminalQueued.compareAndSet(false, true)) {
                    enqueueTerminal {
                        onExit(code)
                    }
                }
            }
        }
        client.newWebSocket(request, listener)
        try {
            withTimeout(START_TIMEOUT_MS) {
                started.await()
            }
        } catch (error: Throwable) {
            // A bridge that never completes hello must not leave a live
            // WebSocket and a drain worker behind. Both would otherwise keep
            // delivering late lifecycle events into a failed ACP session.
            closed.set(true)
            webSocket?.cancel()
            webSocket = null
            inboundEvents.close()
            throw error
        }
    }

    override suspend fun writeLine(line: String) {
        val current = webSocket ?: throw IllegalStateException("Codex bridge is not connected.")
        val payload = mapOf(
            "type" to "stdin",
            "line" to line.trimEnd('\n', '\r')
        )
        val sent = withContext(Dispatchers.IO) {
            current.send(gson.toJson(payload))
        }
        if (!sent) {
            throw IllegalStateException("Codex bridge send failed.")
        }
    }

    override suspend fun close() {
        closed.set(true)
        terminalQueued.set(true)
        val current = webSocket
        webSocket = null
        runCatching { current?.close(1000, "client closed") }
        inboundEvents.close()
    }

    private suspend fun handleBridgeMessage(
        raw: String,
        onStdoutLine: suspend (String) -> Unit,
        onStderrLine: suspend (String) -> Unit,
        onExit: suspend (Int?) -> Unit
    ) {
        val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull()
        val obj = parsed?.takeIf { it.isJsonObject }?.asJsonObject
        val type = obj?.get("type")?.asStringOrNull()
        when (type) {
            "hello" -> handleHello(obj)
            "stdout" -> obj.stringValue("line")?.let { line ->
                onStdoutLine(line)
            }
            "stderr" -> obj.stringValue("line")?.let { line ->
                onStderrLine(line)
            }
            "exit" -> {
                closed.set(true)
                val exitCode = obj.get("exitCode")?.asIntOrNull()
                onExit(exitCode)
            }
            "error" -> {
                val message = obj.stringValue("message") ?: "Codex bridge error."
                if (!started.isCompleted) {
                    started.completeExceptionally(IllegalStateException(message))
                }
                onStderrLine(message)
            }
            else -> {
                // Some bridge implementations forward raw ACP JSON instead of an envelope.
                onStdoutLine(raw)
            }
        }
    }

    private fun enqueueInbound(event: suspend () -> Unit) {
        if (!inboundEvents.offer(event)) {
            Log.w(REMOTE_BRIDGE_TAG, "Dropping inbound bridge event after queue close")
        }
    }

    private fun enqueueTerminal(event: suspend () -> Unit) {
        if (!inboundEvents.offerTerminal(event)) {
            Log.w(REMOTE_BRIDGE_TAG, "Dropping duplicate inbound bridge terminal event")
        }
    }

    private suspend fun handleInboundEventFailure(
        error: Throwable,
        onStderrLine: suspend (String) -> Unit,
        onExit: suspend (Int?) -> Unit,
    ) {
        closed.set(true)
        webSocket?.cancel()
        val detail = error.message?.takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName
        onStderrLine("Inbound ACP event failed: $detail")
        if (terminalQueued.compareAndSet(false, true)) {
            onExit(null)
        }
    }

    private fun bridgeMessageType(raw: String): String? {
        return runCatching {
            JsonParser.parseString(raw)
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("type")
                ?.asStringOrNull()
        }.getOrNull()
    }

    private fun handleHello(obj: JsonObject) {
        val ok = obj.get("ok")?.asBooleanOrNull() ?: true
        if (ok) {
            if (!started.isCompleted) {
                started.complete(Unit)
            }
            return
        }
        val message = obj.stringValue("message") ?: "Codex bridge rejected the connection."
        if (!started.isCompleted) {
            started.completeExceptionally(IllegalStateException(message))
        }
    }

    private companion object {
        private const val START_TIMEOUT_MS = 15_000L
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }
}

internal data class CodexRemoteBridgeProbe(
    val ready: Boolean,
    val version: String?,
    val error: String?,
    val cwd: String?,
    val details: Map<String, Any?> = emptyMap()
)

internal suspend fun listCodexRemoteBridgeDirectory(
    config: CodexRemoteBridgeConfig,
    path: String?,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
): Map<String, Any?> {
    if (config.bridgeUrl.trim().isEmpty()) {
        return linkedMapOf(
            "ok" to false,
            "error" to "Remote Codex bridge URL is required.",
            "path" to path.orEmpty()
        )
    }
    return withContext(Dispatchers.IO) {
        runCatching {
            val urlBuilder = normalizeCodexBridgeFsListUrl(config.bridgeUrl)
                .toHttpUrl()
                .newBuilder()
            val targetPath = path?.trim()?.takeIf { it.isNotEmpty() }
                ?: config.cwd.trim().takeIf { it.isNotEmpty() }
            if (targetPath != null) {
                urlBuilder.addQueryParameter("path", targetPath)
            }
            val request = Request.Builder()
                .url(urlBuilder.build())
                .applyBridgeAuth(config.authToken)
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JsonParser.parseString(body) }.getOrNull()
                val parsed = json?.toKotlinValue() as? Map<*, *>
                val payload = parsed
                    ?.entries
                    ?.associate { (key, value) -> key.toString() to value }
                    ?.toMutableMap()
                    ?: linkedMapOf<String, Any?>()
                if (!response.isSuccessful) {
                    payload["ok"] = false
                    payload.putIfAbsent(
                        "error",
                        "Bridge directory list failed: HTTP ${response.code}"
                    )
                }
                payload
            }
        }.getOrElse { error ->
            Log.w(REMOTE_BRIDGE_TAG, "Bridge directory list failed: ${error.message}")
            linkedMapOf(
                "ok" to false,
                "error" to (error.message ?: error.javaClass.simpleName),
                "path" to path.orEmpty()
            )
        }
    }
}

internal suspend fun readCodexRemoteBridgeFile(
    config: CodexRemoteBridgeConfig,
    path: String?,
    client: OkHttpClient = shortCallClient
): Map<String, Any?> {
    if (config.bridgeUrl.trim().isEmpty()) {
        return linkedMapOf(
            "ok" to false,
            "error" to "Remote Codex bridge URL is required.",
            "path" to path.orEmpty()
        )
    }
    val targetPath = path?.trim().orEmpty()
    if (targetPath.isEmpty()) {
        return linkedMapOf(
            "ok" to false,
            "error" to "Remote file path is required.",
            "path" to targetPath
        )
    }
    return requestRemoteBridgeJson(
        config = config,
        client = client,
        url = normalizeCodexBridgeFsReadUrl(config.bridgeUrl)
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("path", targetPath)
            .build()
            .toString(),
        method = "GET",
        body = null,
        fallbackErrorPrefix = "Bridge file read failed"
    )
}

internal suspend fun writeCodexRemoteBridgeFile(
    config: CodexRemoteBridgeConfig,
    path: String?,
    content: String?,
    client: OkHttpClient = shortCallClient
): Map<String, Any?> {
    if (config.bridgeUrl.trim().isEmpty()) {
        return linkedMapOf(
            "ok" to false,
            "error" to "Remote Codex bridge URL is required."
        )
    }
    return requestRemoteBridgeJsonPost(
        config = config,
        client = client,
        url = normalizeCodexBridgeFsWriteUrl(config.bridgeUrl),
        payload = linkedMapOf(
            "path" to path.orEmpty(),
            "content" to content.orEmpty()
        ),
        fallbackErrorPrefix = "Bridge file write failed"
    )
}

internal suspend fun uploadCodexRemoteBridgeAttachment(
    config: CodexRemoteBridgeConfig,
    source: File,
    name: String,
    client: OkHttpClient = shortCallClient
): Map<String, Any?> {
    if (config.bridgeUrl.trim().isEmpty()) {
        return linkedMapOf(
            "ok" to false,
            "error" to "Remote Codex bridge URL is required."
        )
    }
    if (!source.exists() || !source.isFile) {
        return linkedMapOf(
            "ok" to false,
            "error" to "Attachment file is not readable: ${source.absolutePath}"
        )
    }
    val dataBase64 = withContext(Dispatchers.IO) {
        // Keep the remote bridge on the same bounded attachment contract as
        // local ACP. Do not read an arbitrary picker path into memory.
        Base64.encodeToString(readAgentAttachmentBytes(source), Base64.NO_WRAP)
    }
    return requestRemoteBridgeJsonPost(
        config = config,
        client = client,
        url = normalizeCodexBridgeFsUploadUrl(config.bridgeUrl),
        payload = linkedMapOf(
            "name" to name.trim().ifEmpty { source.name },
            "dataBase64" to dataBase64
        ),
        fallbackErrorPrefix = "Bridge attachment upload failed"
    )
}

internal suspend fun deleteCodexRemoteBridgePath(
    config: CodexRemoteBridgeConfig,
    path: String?,
    recursive: Boolean,
    client: OkHttpClient = shortCallClient
): Map<String, Any?> {
    if (config.bridgeUrl.trim().isEmpty()) {
        return linkedMapOf(
            "ok" to false,
            "error" to "Remote Codex bridge URL is required."
        )
    }
    return requestRemoteBridgeJsonPost(
        config = config,
        client = client,
        url = normalizeCodexBridgeFsDeleteUrl(config.bridgeUrl),
        payload = linkedMapOf(
            "path" to path.orEmpty(),
            "recursive" to recursive
        ),
        fallbackErrorPrefix = "Bridge path delete failed"
    )
}

internal suspend fun moveCodexRemoteBridgePath(
    config: CodexRemoteBridgeConfig,
    path: String?,
    destinationPath: String?,
    client: OkHttpClient = shortCallClient
): Map<String, Any?> {
    if (config.bridgeUrl.trim().isEmpty()) {
        return linkedMapOf(
            "ok" to false,
            "error" to "Remote Codex bridge URL is required."
        )
    }
    return requestRemoteBridgeJsonPost(
        config = config,
        client = client,
        url = normalizeCodexBridgeFsMoveUrl(config.bridgeUrl),
        payload = linkedMapOf(
            "path" to path.orEmpty(),
            "destinationPath" to destinationPath.orEmpty()
        ),
        fallbackErrorPrefix = "Bridge path move failed"
    )
}

private suspend fun requestRemoteBridgeJsonPost(
    config: CodexRemoteBridgeConfig,
    client: OkHttpClient,
    url: String,
    payload: Map<String, Any?>,
    fallbackErrorPrefix: String
): Map<String, Any?> {
    if (config.bridgeUrl.trim().isEmpty()) {
        return linkedMapOf(
            "ok" to false,
            "error" to "Remote Codex bridge URL is required."
        )
    }
    return requestRemoteBridgeJson(
        config = config,
        client = client,
        url = url,
        method = "POST",
        body = Gson().toJson(payload),
        fallbackErrorPrefix = fallbackErrorPrefix
    )
}

private suspend fun requestRemoteBridgeJson(
    config: CodexRemoteBridgeConfig,
    client: OkHttpClient,
    url: String,
    method: String,
    body: String?,
    fallbackErrorPrefix: String
): Map<String, Any?> {
    return withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .applyBridgeAuth(config.authToken)
            if (method == "POST") {
                builder.post(
                    (body ?: "{}").toRequestBody(BRIDGE_JSON_MEDIA_TYPE)
                )
            }
            val request = builder.build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val json = runCatching { JsonParser.parseString(responseBody) }.getOrNull()
                val parsed = json?.toKotlinValue() as? Map<*, *>
                val payload = parsed
                    ?.entries
                    ?.associate { (key, value) -> key.toString() to value }
                    ?.toMutableMap()
                    ?: linkedMapOf<String, Any?>()
                if (!response.isSuccessful) {
                    payload["ok"] = false
                    payload.putIfAbsent("error", "$fallbackErrorPrefix: HTTP ${response.code}")
                }
                payload
            }
        }.getOrElse { error ->
            Log.w(REMOTE_BRIDGE_TAG, "$fallbackErrorPrefix: ${error.message}")
            linkedMapOf(
                "ok" to false,
                "error" to (error.message ?: error.javaClass.simpleName)
            )
        }
    }
}

internal suspend fun probeCodexRemoteBridge(
    config: CodexRemoteBridgeConfig,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
): CodexRemoteBridgeProbe {
    if (!config.isConfigured) {
        return CodexRemoteBridgeProbe(
            ready = false,
            version = null,
            error = "Remote Codex bridge URL and cwd are required.",
            cwd = config.cwd.trim().ifBlank { null }
        )
    }
    return withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(normalizeCodexBridgeHealthUrl(config.bridgeUrl))
                .applyBridgeAuth(config.authToken)
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                if (!response.isSuccessful) {
                    return@withContext CodexRemoteBridgeProbe(
                        ready = false,
                        version = null,
                        error = json?.stringValue("error")
                            ?: "Bridge health check failed: HTTP ${response.code}",
                        cwd = json?.stringValue("cwd"),
                        details = json?.toKotlinMap().orEmpty()
                    )
                }
                CodexRemoteBridgeProbe(
                    ready = json?.get("ok")?.asBooleanOrNull() ?: true,
                    version = json?.stringValue("codexVersion") ?: json?.stringValue("version"),
                    error = json?.stringValue("error"),
                    cwd = json?.stringValue("cwd"),
                    details = json?.toKotlinMap().orEmpty()
                )
            }
        }.getOrElse { error ->
            Log.w(REMOTE_BRIDGE_TAG, "Bridge health check failed: ${error.message}")
            CodexRemoteBridgeProbe(
                ready = false,
                version = null,
                error = error.message ?: error.javaClass.simpleName,
                cwd = null
            )
        }
    }
}

private val shortCallClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .build()

private val BRIDGE_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private fun Request.Builder.applyBridgeAuth(token: String): Request.Builder {
    val normalized = token.trim()
    if (normalized.isNotEmpty()) {
        header("Authorization", "Bearer $normalized")
        header("X-Omnibot-Bridge-Token", normalized)
    }
    return this
}

private fun JsonObject.stringValue(key: String): String? {
    return get(key)?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

private fun JsonElement.asStringOrNull(): String? {
    return runCatching {
        if (isJsonNull) null else asString
    }.getOrNull()
}

private fun JsonElement.asBooleanOrNull(): Boolean? {
    return runCatching {
        if (isJsonNull) null else asBoolean
    }.getOrNull()
}

private fun JsonElement.asIntOrNull(): Int? {
    return runCatching {
        if (isJsonNull) null else asInt
    }.getOrNull()
}

private fun JsonElement.toKotlinValue(): Any? {
    if (isJsonNull) {
        return null
    }
    if (isJsonObject) {
        return asJsonObject.entrySet().associate { (key, value) ->
            key to value.toKotlinValue()
        }
    }
    if (isJsonArray) {
        return asJsonArray.map { it.toKotlinValue() }
    }
    if (isJsonPrimitive) {
        val primitive = asJsonPrimitive
        if (primitive.isBoolean) {
            return primitive.asBoolean
        }
        if (primitive.isNumber) {
            val text = primitive.asString
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: text
        }
        return primitive.asString
    }
    return null
}

private fun JsonObject.toKotlinMap(): Map<String, Any?> {
    return entrySet().associate { (key, value) -> key to value.toKotlinValue() }
}
