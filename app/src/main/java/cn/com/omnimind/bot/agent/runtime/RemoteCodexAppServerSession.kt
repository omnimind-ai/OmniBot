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
    private val connectionFactory: () -> RemoteCodexAppServerConnection
) {
    private val gson = Gson()
    /** Identity of this app-server transport instance, not an ACP session id. */
    internal val connectionToken: String = UUID.randomUUID().toString()
    private val writeMutex = Mutex()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Map<String, Any?>>>()
    private val nextId = AtomicLong(1L)

    @Volatile
    private var initializeResult: Map<String, Any?> = emptyMap()

    @Volatile
    private var connection: RemoteCodexAppServerConnection? = null

    val isRunning: Boolean
        get() = connection?.isRunning == true

    suspend fun start(clientVersion: String) {
        if (isRunning) {
            return
        }
        val startedConnection = createConnection()
        connection = startedConnection
        startedConnection.start(
            onStdoutLine = ::handleStdoutLine,
            onStderrLine = { line ->
                // The bridge stderr is diagnostic output, not an Agent event.
                // Keep it out of the ACP session stream.
            },
            onExit = { exitCode ->
                handleConnectionExit(startedConnection, exitCode)
            }
        )

        try {
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
            onServerMessage(
                mapOf(
                    "method" to "codex/connected",
                    "params" to mapOf("clientVersion" to clientVersion),
                )
            )
        } catch (error: Throwable) {
            disconnect()
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
            "params" to params
        )
        try {
            writeJsonLine(message)
            return withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (error: Throwable) {
            pending.remove(id)
            if (error is TimeoutCancellationException || error is CancellationException) {
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
                sendNotification(
                    "$/cancel_request",
                    mapOf("requestId" to requestId),
                )
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
        val currentConnection = connection
        connection = null
        pending.forEach { (_, deferred) ->
            deferred.completeExceptionally(IllegalStateException("Remote ACP agent disconnected."))
        }
        pending.clear()
        initializeResult = emptyMap()
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
        connection = null
        pending.forEach { (_, deferred) ->
            deferred.completeExceptionally(
                IllegalStateException("Remote ACP agent exited.")
            )
        }
        pending.clear()
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
        onServerMessage(message)
    }

    private suspend fun writeJsonLine(message: Map<String, Any?>) {
        val line = gson.toJson(toJsonElement(message)) + "\n"
        val currentConnection = connection
            ?: throw IllegalStateException("Remote ACP agent stdin is closed.")
        writeMutex.withLock {
            currentConnection.writeLine(line)
        }
    }

    private fun createConnection(): RemoteCodexAppServerConnection {
        return connectionFactory()
    }

    private fun buildInitializeParams(clientVersion: String): Map<String, Any?> {
        return mapOf(
            "protocolVersion" to 1,
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
    }
}
