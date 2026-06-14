package cn.com.omnimind.bot.codex

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rk.libcommons.OmnibotTerminalEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class CodexAppServerSession(
    private val context: Context? = null,
    private val scope: CoroutineScope,
    private val onServerMessage: suspend (Map<String, Any?>) -> Unit,
    private val connectionFactory: (() -> CodexAppServerConnection)? = null,
    private val processStarter: suspend (String, Map<String, String>) -> Process = { command, extraEnvironment ->
        defaultLocalProcessStarter(context, command, extraEnvironment)
    }
) {
    private val gson = Gson()
    private val writeMutex = Mutex()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Map<String, Any?>>>()
    private val nextId = AtomicLong(1L)

    @Volatile
    private var connection: CodexAppServerConnection? = null

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
                onServerMessage(
                    mapOf(
                        "method" to "codex/stderr",
                        "params" to mapOf("message" to line)
                    )
                )
            },
            onExit = { exitCode ->
                handleConnectionExit(startedConnection, exitCode)
            }
        )

        try {
            withTimeout(INITIALIZE_TIMEOUT_MS) {
                sendRequest(
                    method = "initialize",
                    params = buildInitializeParams(clientVersion),
                    timeoutMs = INITIALIZE_TIMEOUT_MS
                )
            }
            sendNotification("initialized", null)
            onServerMessage(
                mapOf(
                    "method" to "codex/connected",
                    "params" to mapOf("workspaceId" to DEFAULT_WORKSPACE_ID)
                )
            )
        } catch (error: Throwable) {
            disconnect()
            if (error is TimeoutCancellationException) {
                throw IllegalStateException(
                    "Codex app-server did not respond to initialize.",
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
        check(currentConnection?.isRunning == true) { "Codex app-server is not connected." }
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
            throw error
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
            deferred.completeExceptionally(IllegalStateException("Codex app-server disconnected."))
        }
        pending.clear()
        currentConnection?.close()
    }

    private suspend fun handleConnectionExit(
        exitedConnection: CodexAppServerConnection,
        exitCode: Int?
    ) {
        if (connection !== exitedConnection) {
            return
        }
        connection = null
        pending.forEach { (_, deferred) ->
            deferred.completeExceptionally(
                IllegalStateException("Codex app-server exited.")
            )
        }
        pending.clear()
        onServerMessage(
            mapOf(
                "method" to "codex/disconnected",
                "params" to mapOf("exitCode" to exitCode)
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
            ?: throw IllegalStateException("Codex app-server stdin is closed.")
        writeMutex.withLock {
            currentConnection.writeLine(line)
        }
    }

    private fun createConnection(): CodexAppServerConnection {
        return connectionFactory?.invoke()
            ?: LocalCodexAppServerConnection(
                context = context,
                scope = scope,
                command = buildStartCommand(),
                environment = buildStartEnvironment(),
                processStarter = processStarter
            )
    }

    private fun buildStartEnvironment(): Map<String, String> {
        return linkedMapOf<String, String>().apply {
            if (context != null) {
                putAll(OmnibotTerminalEnvironment.buildTerminalEnvironment(context))
            }
            put("OMNIBOT_HEADLESS", "1")
            put("CODEX_HOME", CodexAppServerDefaults.CODEX_HOME)
            put("OMNIBOT_SESSION_CWD", CodexAppServerDefaults.FALLBACK_CWD)
        }
    }

    private fun buildStartCommand(): String {
        return """
            if [ -n "${'$'}{OMNIBOT_USER_ENV_FILE:-}" ] && [ -f "${'$'}OMNIBOT_USER_ENV_FILE" ]; then
              . "${'$'}OMNIBOT_USER_ENV_FILE"
            fi
            export CODEX_HOME=${CodexAppServerDefaults.CODEX_HOME}
            export PATH="/root/.npm-global/bin:${'$'}PATH"
            mkdir -p "${'$'}CODEX_HOME"
            if [ -d ${CodexAppServerDefaults.DEFAULT_WORKSPACE_CWD} ]; then
              cd ${CodexAppServerDefaults.DEFAULT_WORKSPACE_CWD}
            else
              cd ${CodexAppServerDefaults.FALLBACK_CWD}
            fi
            exec codex app-server
        """.trimIndent()
    }

    private fun buildInitializeParams(clientVersion: String): Map<String, Any?> {
        return mapOf(
            "clientInfo" to mapOf(
                "name" to "omnibot_android",
                "title" to "Omnibot",
                "version" to clientVersion
            ),
            "capabilities" to mapOf(
                "experimentalApi" to true
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
