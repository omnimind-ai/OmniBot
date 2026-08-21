package cn.com.omnimind.bot.omniflow

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

fun interface OmniFlowPythonHostCall {
    suspend fun call(method: String, payload: Map<String, Any?>): Map<String, Any?>
}

class OmniFlowPythonClient(
    private val processStarter: suspend (command: String, environment: Map<String, String>) -> Process,
    private val bridgeCommand: String,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class BridgeSession(
        val process: Process,
        val writer: BufferedWriter,
        val stdout: Channel<String>,
        val stdoutJob: Job,
        val stderrJob: Job,
        val stderr: StderrTail,
    )

    private class StderrTail {
        private val value = StringBuilder()

        @Synchronized
        fun append(line: String) {
            if (value.isNotEmpty()) value.append('\n')
            value.append(line)
            if (value.length > STDERR_TAIL_CHARS) {
                value.delete(0, value.length - STDERR_TAIL_CHARS)
            }
        }

        @Synchronized
        fun text(): String = value.toString().trim()
    }

    private val callMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: BridgeSession? = null
    private var closed = false

    suspend fun initialize(): Map<String, Any?> = callMutex.withLock {
        check(!closed) { "omniflow_python_client_closed" }
        val activeSession = ensureSession()
        try {
            val requestId = requestIdFactory()
            writeRequest(
                activeSession.writer,
                requestId,
                "initialize",
                mapOf(
                    "protocolVersion" to PROTOCOL_VERSION,
                    "capabilities" to emptyMap<String, Any?>(),
                    "clientInfo" to mapOf(
                        "name" to "openomnibot-android",
                        "version" to "1",
                    ),
                ),
            )
            val result = withTimeout(INITIALIZE_TIMEOUT_MS) {
                readResponse(activeSession, requestId, hostCall = null)
            }
            writeNotification(activeSession.writer, "notifications/initialized")
            result
        } catch (error: Throwable) {
            clearSession(activeSession)
            throw error
        }
    }

    suspend fun call(
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
        hostCall: OmniFlowPythonHostCall? = null,
        timeoutMs: Long = defaultTimeoutMs(operation, payload),
    ): Map<String, Any?> = callMutex.withLock {
        check(!closed) { "omniflow_python_client_closed" }
        val activeSession = ensureSession()
        try {
            val requestId = requestIdFactory()
            writeRequest(activeSession.writer, requestId, operation, payload)
            withTimeout(timeoutMs) {
                readResponse(activeSession, requestId, hostCall)
            }
        } catch (error: Throwable) {
            if (
                error is CancellationException ||
                error !is OmniFlowPythonException ||
                error.type == "process_exit"
            ) {
                clearSession(activeSession)
            }
            throw error
        }
    }

    suspend fun close() = callMutex.withLock {
        if (closed) return@withLock
        closed = true
        val activeSession = session ?: return@withLock
        clearSession(activeSession)
    }

    private suspend fun ensureSession(): BridgeSession {
        session?.takeIf { it.process.isAlive }?.let { return it }
        session?.let { clearSession(it) }
        val process = processStarter(
            bridgeCommand,
            mapOf("PYTHONUNBUFFERED" to "1", "OMNIBOT_HEADLESS" to "1"),
        )
        val stdout = Channel<String>(Channel.UNLIMITED)
        val stderr = StderrTail()
        val activeSession = BridgeSession(
            process = process,
            writer = OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8).buffered(),
            stdout = stdout,
            stdoutJob = ioScope.launch {
                try {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            if (stdout.trySend(line).isFailure) break
                        }
                    }
                } catch (_: IOException) {
                } finally {
                    stdout.close()
                }
            },
            stderrJob = ioScope.launch {
                try {
                    process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEach(stderr::append)
                    }
                } catch (_: IOException) {
                }
            },
            stderr = stderr,
        )
        session = activeSession
        return activeSession
    }

    private suspend fun readResponse(
        session: BridgeSession,
        requestId: String,
        hostCall: OmniFlowPythonHostCall?,
    ): Map<String, Any?> {
        while (true) {
            val line = session.stdout.receiveCatching().getOrNull()
                ?: throw bridgeExited(session)
            if (line.isBlank()) continue
            val message = jsonMap(line)
            val method = message["method"]?.toString().orEmpty()
            if (
                message["jsonrpc"] == JSON_RPC_VERSION &&
                method.startsWith(HOST_METHOD_PREFIX) &&
                message.containsKey("id")
            ) {
                writeHostResponse(
                    writer = session.writer,
                    message = message,
                    hostCall = hostCall,
                )
                continue
            }
            if (
                message["jsonrpc"] != JSON_RPC_VERSION ||
                message["id"]?.toString() != requestId ||
                (!message.containsKey("result") && !message.containsKey("error"))
            ) {
                continue
            }
            if (message.containsKey("error")) {
                val error = mapValue(message["error"])
                val data = mapValue(error["data"])
                throw OmniFlowPythonException(
                    code = error["message"]?.toString().orEmpty().ifBlank { "python_call_failed" },
                    type = data["type"]?.toString().orEmpty(),
                )
            }
            return mapValue(message["result"])
        }
    }

    private fun writeRequest(
        writer: BufferedWriter,
        requestId: String,
        operation: String,
        payload: Map<String, Any?>,
    ) {
        writer.write(
            gson.toJson(
                linkedMapOf(
                    "jsonrpc" to JSON_RPC_VERSION,
                    "id" to requestId,
                    "method" to operation,
                    "params" to payload,
                ),
            ),
        )
        writer.newLine()
        writer.flush()
    }

    private fun writeNotification(writer: BufferedWriter, method: String) {
        writer.write(
            gson.toJson(
                linkedMapOf(
                    "jsonrpc" to JSON_RPC_VERSION,
                    "method" to method,
                ),
            ),
        )
        writer.newLine()
        writer.flush()
    }

    private fun bridgeExited(session: BridgeSession): OmniFlowPythonException {
        val exitCode = runCatching { session.process.exitValue() }.getOrNull()
        return OmniFlowPythonException(
            code = session.stderr.text().ifBlank {
                exitCode?.let { "python_bridge_exited_$it" } ?: "python_bridge_output_closed"
            },
            type = "process_exit",
        )
    }

    private fun clearSession(activeSession: BridgeSession) {
        if (session === activeSession) session = null
        if (activeSession.process.isAlive) {
            runCatching { activeSession.process.destroyForcibly() }
        }
        activeSession.stdout.close()
        activeSession.stdoutJob.cancel()
        activeSession.stderrJob.cancel()
        ioScope.launch {
            runCatching {
                activeSession.process.waitFor(PROCESS_EXIT_GRACE_MS, TimeUnit.MILLISECONDS)
            }
            runCatching { activeSession.writer.close() }
        }
    }

    private suspend fun writeHostResponse(
        writer: BufferedWriter,
        message: Map<String, Any?>,
        hostCall: OmniFlowPythonHostCall?,
    ) {
        val callId = message["id"] ?: error("host_call_id_required")
        val method = message["method"]?.toString().orEmpty().removePrefix(HOST_METHOD_PREFIX)
        val response = runCatching {
            requireNotNull(hostCall) { "host_call_not_configured" }
                .call(method, mapValue(message["params"]))
        }.fold(
            onSuccess = { result ->
                linkedMapOf<String, Any?>(
                    "jsonrpc" to JSON_RPC_VERSION,
                    "id" to callId,
                    "result" to result,
                )
            },
            onFailure = { error ->
                linkedMapOf<String, Any?>(
                    "jsonrpc" to JSON_RPC_VERSION,
                    "id" to callId,
                    "error" to linkedMapOf(
                        "code" to -32603,
                        "message" to error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                        "data" to mapOf("type" to error.javaClass.name),
                    ),
                )
            },
        )
        writer.write(gson.toJson(response))
        writer.newLine()
        writer.flush()
    }

    private fun jsonMap(value: String): Map<String, Any?> =
        @Suppress("UNCHECKED_CAST")
        (gson.fromJson<Map<String, Any?>>(value, MAP_TYPE) ?: emptyMap())

    private fun mapValue(value: Any?): Map<String, Any?> =
        @Suppress("UNCHECKED_CAST")
        ((value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            ?: emptyMap())

    companion object {
        const val PROTOCOL_VERSION = "2025-11-25"
        private const val JSON_RPC_VERSION = "2.0"
        private const val HOST_METHOD_PREFIX = "omniflow/"
        private const val PROCESS_EXIT_GRACE_MS = 1_000L
        private const val STDERR_TAIL_CHARS = 8_192
        internal const val INITIALIZE_TIMEOUT_MS = 120_000L
        private const val DEFAULT_CALL_TIMEOUT_MS = 30_000L
        private const val SEMANTIC_COMPILE_TIMEOUT_MS = 210_000L
        private const val RUN_TIMEOUT_MS = 10 * 60_000L
    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        // RunLog v1 requires explicit nullable fields such as `seed`. The
        // Python bridge must receive those fields instead of silently dropping
        // them during JSON-RPC serialization.
        .serializeNulls()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()
        private val MAP_TYPE = object : TypeToken<Map<String, Any?>>() {}.type

        fun bridgeCommand(
            shellPythonSourcePath: String,
            shellSitePackagesPath: String,
            shellOmniTransferRoot: String,
            shellOmniTransferCheckpointPath: String,
            shellDeveloperOverridePath: String? = null,
        ): String {
            listOf(
                shellPythonSourcePath,
                shellSitePackagesPath,
                shellOmniTransferRoot,
                shellOmniTransferCheckpointPath,
            ).forEach { path ->
                require(path.matches(Regex("/[A-Za-z0-9_./-]+"))) {
                    "omniflow_runtime_path_invalid"
                }
            }
            shellDeveloperOverridePath?.let { path ->
                require(path.matches(Regex("/[A-Za-z0-9_./-]+"))) {
                    "omniflow_override_path_invalid"
                }
            }
            val pythonPath = listOfNotNull(
                shellDeveloperOverridePath,
                shellPythonSourcePath,
                shellSitePackagesPath,
                "$shellOmniTransferRoot/src",
            ).joinToString(":")
            return """
            export PYTHONPATH='$pythonPath'
            export OMNITRANSFER_ROOT='$shellOmniTransferRoot'
            export OMNITRANSFER_MATCHER_CHECKPOINT='$shellOmniTransferCheckpointPath'
            python_bin="${'$'}(command -v python3 || true)"
            if [ -z "${'$'}python_bin" ]; then echo 'omniflow_python_not_installed' >&2; exit 127; fi
            exec "${'$'}python_bin" -u -m omniflow.bridge --store /workspace/.omnibot/omniflow/omniflow.json --catalog default
            """.trimIndent()
        }

        fun defaultTimeoutMs(
            operation: String,
            payload: Map<String, Any?> = emptyMap(),
        ): Long = when {
            operation == "tools/call" && payload["name"] == "run_gui" -> RUN_TIMEOUT_MS
            operation == "tools/call" -> SEMANTIC_COMPILE_TIMEOUT_MS
            else -> DEFAULT_CALL_TIMEOUT_MS
        }
    }
}

class OmniFlowPythonException(
    val code: String,
    val type: String,
) : IllegalStateException(code)
