package cn.com.omnimind.bot.agent.runtime

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

internal class RemoteCodexAppServerSession(
    private val scope: CoroutineScope,
    private val onServerMessage: suspend (Map<String, Any?>) -> Unit,
    private val connectionFactory: () -> RemoteCodexAppServerConnection,
    private val restoreSessions: suspend (RemoteCodexAppServerSession) -> Unit = {},
    private val reconnectDelays: List<Long> = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L),
) {
    private val gson = Gson()
    /** Identity of this app-server transport instance, not an ACP session id. */
    internal val connectionToken: String = UUID.randomUUID().toString()
    private val writeMutex = Mutex()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Map<String, Any?>>>()
    private val nextId = AtomicLong(1L)

    @Volatile
    private var initializeResult: Map<String, Any?> = emptyMap()

    val protocolVersion: Int
        get() = (initializeResult["protocolVersion"] as? Number)?.toInt() ?: 1

    @Volatile
    private var connection: RemoteCodexAppServerConnection? = null

    @Volatile
    private var initialized = false

    private var clientVersion = ""
    @Volatile private var connectionWanted = false
    @Volatile private var recovery: Deferred<Unit>? = null
    val isRecovering: Boolean get() = recovery?.isActive == true

    suspend fun awaitRecovery() { recovery?.await() }

    val isRunning: Boolean
        get() = initialized && connection?.isRunning == true

    suspend fun start(clientVersion: String) {
        this.clientVersion = clientVersion
        connectionWanted = true
        if (isRecovering) { awaitRecovery(); return }
        openConnection(clientVersion)
        publishConnected()
    }

    private suspend fun openConnection(clientVersion: String) {
        if (isRunning) {
            return
        }
        val startedConnection = createConnection()
        connection = startedConnection
        initialized = false
        try {
            startedConnection.start(
                onStdoutLine = { line ->
                    if (connection === startedConnection) handleStdoutLine(line)
                },
                onStderrLine = { _ ->
                    // Diagnostics are not ACP session events.
                },
                onExit = { exitCode ->
                    handleConnectionExit(startedConnection, exitCode)
                }
            )

            withTimeout(INITIALIZE_TIMEOUT_MS) {
                val response = sendRequest(
                    method = "initialize",
                    params = buildInitializeParams(clientVersion),
                    timeoutMs = INITIALIZE_TIMEOUT_MS
                )
                initializeResult = (response["result"] as? Map<*, *>).orEmpty()
                    .entries
                    .associate { (key, value) -> key.toString() to value }
            }
            sendNotification("initialized", null)
            check(connection === startedConnection && startedConnection.isRunning) {
                "Remote ACP agent disconnected during initialize."
            }
            initialized = true
        } catch (error: Throwable) {
            withContext(NonCancellable) { closeConnection() }
            if (error is TimeoutCancellationException) {
                throw IllegalStateException(
                    "Remote ACP agent did not respond to initialize.",
                    error
                )
            }
            throw error
        }
    }

    suspend fun sendRequest(
        method: String,
        params: Any? = null,
        timeoutMs: Long = REQUEST_TIMEOUT_MS
    ): Map<String, Any?> {
        val currentConnection = connection
        check(currentConnection?.isRunning == true) { "Remote ACP agent is not connected." }
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<Map<String, Any?>>()
        pending[id] = deferred
        val message = linkedMapOf<String, Any?>(
            "id" to id,
            "method" to method,
            "params" to remoteAcpRequestParams(method, params, protocolVersion)
        )
        var writeAttempted = false
        try {
            return withTimeout(timeoutMs) {
                writeJsonLine(message, currentConnection) { writeAttempted = true }
                deferred.await()
            }
        } catch (error: Throwable) {
            pending.remove(id)
            if (writeAttempted && error is CancellationException) {
                cancelInFlightRequest(currentConnection, id)
            }
            throw error
        }
    }

    private suspend fun cancelInFlightRequest(
        requestConnection: RemoteCodexAppServerConnection,
        requestId: Long,
    ) {
        withContext(NonCancellable) {
            if (connection !== requestConnection || !requestConnection.isRunning) return@withContext
            runCatching {
                // Cancellation is best effort, and must not hang behind a blocked write.
                withTimeout(CANCEL_NOTIFICATION_TIMEOUT_MS) {
                    writeJsonLine(mapOf(
                        "method" to "$/cancel_request",
                        "params" to mapOf("requestId" to requestId),
                    ), requestConnection)
                }
            }
        }
    }

    suspend fun sendNotification(method: String, params: Any? = null) {
        val message = if (params == null) {
            linkedMapOf<String, Any?>("method" to method)
        } else {
            linkedMapOf<String, Any?>("method" to method, "params" to params)
        }
        writeJsonLine(message)
    }

    suspend fun sendResponse(requestId: Any, result: Any?) {
        val message = linkedMapOf<String, Any?>(
            "id" to requestId,
            "result" to result
        )
        writeJsonLine(message)
    }

    suspend fun disconnect() {
        connectionWanted = false
        recovery?.cancel()
        recovery = null
        closeConnection()
    }

    private suspend fun closeConnection() {
        val currentConnection = connection
        connection = null
        initialized = false
        initializeResult = emptyMap()
        pending.forEach { (_, deferred) ->
            deferred.completeExceptionally(IllegalStateException("Remote ACP agent disconnected."))
        }
        pending.clear()
        currentConnection?.close()
    }

    fun initializePayload(): Map<String, Any?> = initializeResult

    private suspend fun handleConnectionExit(
        exitedConnection: RemoteCodexAppServerConnection,
        exitCode: Int?
    ) {
        if (connection !== exitedConnection) {
            return
        }
        val canRestore = initialized && protocolVersion == 2 && connectionWanted
        connection = null
        initialized = false
        initializeResult = emptyMap()
        pending.forEach { (_, deferred) ->
            deferred.completeExceptionally(
                IllegalStateException("Remote ACP agent exited.")
            )
        }
        pending.clear()
        if (isRecovering) return
        if (canRestore) {
            // Reconnect only the transport. The session owner restores ACP
            // subscriptions/history; no pending request or prompt is replayed.
            val job = scope.async(start = CoroutineStart.LAZY) {
                onServerMessage(mapOf(
                    "method" to "codex/disconnected",
                    "_remoteConnectionToken" to connectionToken,
                    "params" to mapOf("recovering" to true),
                ))
                for (backoff in reconnectDelays) {
                    delay(backoff)
                    if (!connectionWanted) return@async
                    try {
                        openConnection(clientVersion)
                        check(protocolVersion == 2) { "ACP version changed during recovery" }
                        restoreSessions(this@RemoteCodexAppServerSession)
                        publishConnected()
                        return@async
                    } catch (error: Exception) {
                        if (error is CancellationException && error !is TimeoutCancellationException) throw error
                        closeConnection()
                    }
                }
                publishDisconnected(exitCode)
            }
            recovery = job
            job.start()
            return
        }
        publishDisconnected(exitCode)
    }

    private suspend fun publishConnected() {
        onServerMessage(mapOf("method" to "codex/connected",
            "params" to mapOf("clientVersion" to clientVersion)))
    }

    private suspend fun publishDisconnected(exitCode: Int?) {
        onServerMessage(
            mapOf(
                "method" to "codex/disconnected",
                "_remoteConnectionToken" to connectionToken,
                "params" to mapOf("exitCode" to exitCode),
            )
        )
    }

    private suspend fun handleStdoutLine(line: String) {
        val message = try {
            val element = JsonParser.parseString(line)
            jsonElementToMethodValue(element) as? Map<String, Any?>
                ?: throw IllegalArgumentException("JSONL root is not an object")
        } catch (error: Throwable) {
            onServerMessage(
                mapOf(
                    "method" to "codex/parseError",
                    "params" to mapOf(
                        "error" to (error.message ?: error.javaClass.simpleName),
                        "raw" to line
                    )
                )
            )
            return
        }

        val responseId = (message["id"] as? Number)?.toLong()
        val hasResultOrError = message.containsKey("result") || message.containsKey("error")
        if (responseId != null && hasResultOrError) {
            pending.remove(responseId)?.complete(message)
            return
        }
        onServerMessage(normalizeRemoteAcpNotification(message, protocolVersion))
    }

    private suspend fun writeJsonLine(
        message: Map<String, Any?>,
        expectedConnection: RemoteCodexAppServerConnection? = connection,
        beforeWrite: () -> Unit = {},
    ) {
        val line = gson.toJson(toJsonElement(message + ("jsonrpc" to "2.0"))) + "\n"
        writeMutex.withLock {
            check(expectedConnection != null && connection === expectedConnection && expectedConnection.isRunning) {
                "Remote ACP connection changed before the request could be sent."
            }
            beforeWrite()
            expectedConnection.writeLine(line)
        }
    }

    private fun createConnection(): RemoteCodexAppServerConnection {
        return connectionFactory()
    }

    private fun buildInitializeParams(clientVersion: String): Map<String, Any?> {
        return mapOf(
            "protocolVersion" to 2,
            "info" to mapOf("name" to "omnibot_android", "version" to clientVersion),
            "capabilities" to emptyMap<String, Any?>(),
            "clientInfo" to mapOf(
                "name" to "omnibot_android",
                "title" to "Omnibot",
                "version" to clientVersion
            ),
            "clientCapabilities" to mapOf(
                "experimentalApi" to true,
                "fs" to mapOf(
                    "readTextFile" to true,
                    "writeTextFile" to true
                ),
                "terminal" to mapOf(
                    "create" to true
                )
            )
        )
    }

    private fun toJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull.INSTANCE
            is JsonElement -> value
            is Map<*, *> -> JsonObject().apply {
                value.forEach { (key, nestedValue) ->
                    if (key != null) {
                        add(key.toString(), toJsonElement(nestedValue))
                    }
                }
            }
            is Iterable<*> -> JsonArray().apply {
                value.forEach { add(toJsonElement(it)) }
            }
            is Array<*> -> JsonArray().apply {
                value.forEach { add(toJsonElement(it)) }
            }
            else -> gson.toJsonTree(value)
        }
    }

    private fun jsonElementToMethodValue(element: JsonElement): Any? {
        return when {
            element.isJsonNull -> null
            element.isJsonObject -> element.asJsonObject.entrySet().associate { (key, value) ->
                key to jsonElementToMethodValue(value)
            }
            element.isJsonArray -> element.asJsonArray.map(::jsonElementToMethodValue)
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isNumber -> {
                        val asString = primitive.asString
                        asString.toLongOrNull() ?: primitive.asDouble
                    }
                    else -> primitive.asString
                }
            }
            else -> null
        }
    }

    companion object {
        const val DEFAULT_WORKSPACE_ID = "default"
        private const val INITIALIZE_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 300_000L
        private const val CANCEL_NOTIFICATION_TIMEOUT_MS = 1_000L
    }
}
