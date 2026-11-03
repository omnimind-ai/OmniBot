package cn.com.omnimind.baselib.privileged

import cn.com.omnimind.baselib.util.OmniLog
import java.io.BufferedWriter
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class PrivilegedShellSessionManager(private val runner: PrivilegedProcessRunner) {

    private enum class StreamKind {
        STDOUT,
        STDERR,
    }

    private data class SessionCommandResult(
        val success: Boolean,
        val code: String,
        val message: String,
        val stdout: String = "",
        val stderr: String = "",
        val transcript: String = "",
        val exitCode: Int? = null,
        val timedOut: Boolean = false,
        val commandRunning: Boolean = false,
        val workingDirectory: String? = null,
    )

    private class RollingTextBuffer {
        private val builder = StringBuilder()
        private var truncated = false

        fun appendLine(line: String) {
            appendText("$line\n")
        }

        fun appendText(text: String) {
            if (text.isEmpty()) {
                return
            }
            builder.append(text)
            if (builder.length > MAX_TRANSCRIPT_CHARS) {
                builder.delete(0, builder.length - MAX_TRANSCRIPT_CHARS)
                truncated = true
            }
        }

        fun snapshot(maxChars: Int? = null): String {
            val raw = builder.toString()
            if (maxChars == null || raw.length <= maxChars) {
                return (if (truncated) "...[earlier output truncated]\n" else "") + raw.trimEnd()
            }
            val tail = raw.takeLast(maxChars).trimEnd()
            return if (tail.isEmpty()) {
                "...[earlier output truncated]"
            } else {
                "...[earlier output truncated]\n$tail"
            }
        }
    }

    private class CommandState(
        val token: String,
        val stdoutMarker: String,
        val stderrMarker: String,
        val recordTranscript: Boolean,
    ) {
        val stdoutBuffer = RollingTextBuffer()
        val stderrBuffer = RollingTextBuffer()
        var stdoutDone: Boolean = false
        var stderrDone: Boolean = false
        var completed: Boolean = false
        var exitCode: Int? = null
        var workingDirectory: String? = null
        var failureMessage: String? = null
    }

    private inner class ShellSession(
        val sessionId: String,
        val backend: PrivilegedBackend,
        private val managed: PrivilegedProcessRunner.ManagedProcess,
    ) {
        private val process get() = managed.process
        private val lock = Object()
        private val writer: BufferedWriter = process.outputStream.bufferedWriter()
        private val transcriptBuffer = RollingTextBuffer()

        @Volatile
        private var currentCommand: CommandState? = null

        @Volatile
        private var closed = false

        init {
            startReader(process.inputStream, StreamKind.STDOUT)
            startReader(process.errorStream, StreamKind.STDERR)
            startExitWatcher()
        }

        fun execute(
            command: String?,
            workingDirectory: String?,
            environment: Map<String, String>,
            timeoutSeconds: Int,
            recordTranscript: Boolean,
            admission: () -> Boolean = { true },
        ): SessionCommandResult {
            val normalizedCommand = command?.trim().orEmpty().ifEmpty { ":" }
            val token = UUID.randomUUID().toString()
            val commandState = CommandState(
                token = token,
                stdoutMarker = stdoutMarker(token),
                stderrMarker = stderrMarker(token),
                recordTranscript = recordTranscript
            )
            val scriptWithMarkers = buildCommandScript(
                token = token,
                command = normalizedCommand,
                workingDirectory = workingDirectory,
                environment = environment
            )
            synchronized(lock) {
                if (closed || !process.isAlive) {
                    return SessionCommandResult(
                        success = false,
                        code = "session_not_found",
                        message = "The privileged shell session is no longer alive."
                    )
                }
                val active = currentCommand
                if (active != null && !active.completed) {
                    return SessionCommandResult(
                        success = false,
                        code = "session_busy",
                        message = "A command is still running in the privileged shell session.",
                        transcript = transcriptBuffer.snapshot(),
                        commandRunning = true
                    )
                }
                check(admission()) { "privileged_access_changed" }
                currentCommand = commandState
                try {
                    writer.write(scriptWithMarkers)
                    writer.flush()
                } catch (error: Exception) {
                    currentCommand = null
                    closed = true
                    return SessionCommandResult(
                        success = false,
                        code = "session_write_failed",
                        message = error.message ?: "Failed to write to the privileged shell session."
                    )
                }

                val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
                while (!commandState.completed && !closed) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) {
                        break
                    }
                    lock.wait(remaining)
                }

                if (commandState.completed) {
                    return SessionCommandResult(
                        success = commandState.exitCode == 0,
                        code = if (commandState.exitCode == 0) "ok" else "command_failed",
                        message = if (commandState.exitCode == 0) {
                            "Privileged shell session command executed successfully."
                        } else {
                            "Privileged shell session command failed."
                        },
                        stdout = commandState.stdoutBuffer.snapshot(),
                        stderr = commandState.stderrBuffer.snapshot(),
                        transcript = transcriptBuffer.snapshot(),
                        exitCode = commandState.exitCode,
                        workingDirectory = commandState.workingDirectory
                    )
                }

                if (closed) {
                    return SessionCommandResult(
                        success = false,
                        code = "session_not_found",
                        message = commandState.failureMessage
                            ?: "The privileged shell session closed unexpectedly.",
                        stdout = commandState.stdoutBuffer.snapshot(),
                        stderr = commandState.stderrBuffer.snapshot(),
                        transcript = transcriptBuffer.snapshot()
                    )
                }

                close()
                return SessionCommandResult(
                    success = false,
                    code = "timeout",
                    message = "The privileged shell session command timed out.",
                    stdout = commandState.stdoutBuffer.snapshot(),
                    stderr = commandState.stderrBuffer.snapshot(),
                    transcript = transcriptBuffer.snapshot(),
                    timedOut = true,
                    commandRunning = false
                )
            }
        }

        fun readTranscript(maxChars: Int?): String {
            synchronized(lock) {
                return transcriptBuffer.snapshot(maxChars)
            }
        }

        fun isAlive(): Boolean = !closed && process.isAlive

        fun isBusy(): Boolean {
            val active = currentCommand
            return active != null && !active.completed
        }

        fun close(): Boolean {
            managed.close()
            synchronized(lock) {
                val wasOpen = !closed
                closed = true
                currentCommand?.apply {
                    failureMessage = "The privileged shell session was stopped."
                    completed = true
                }
                runCatching { writer.close() }
                lock.notifyAll()
                return wasOpen
            }
        }

        private fun startReader(inputStream: InputStream, streamKind: StreamKind) {
            thread(start = true, isDaemon = true, name = "privileged-shell-$streamKind-$sessionId") {
                runCatching {
                    inputStream.reader(Charsets.UTF_8).use { reader ->
                        val chunk = CharArray(4096)
                        val line = StringBuilder()
                        while (true) {
                            val count = reader.read(chunk)
                            if (count < 0) break
                            for (index in 0 until count) {
                                val character = chunk[index]
                                if (character == '\n') {
                                    handleLine(streamKind, line.toString())
                                    line.setLength(0)
                                } else {
                                    line.append(character)
                                    if (line.length >= 8192) {
                                        handleLine(streamKind, line.toString(), terminated = false)
                                        line.setLength(0)
                                    }
                                }
                            }
                        }
                        if (line.isNotEmpty()) handleLine(streamKind, line.toString(), terminated = false)
                    }
                }.onFailure {
                    OmniLog.w(TAG, "Privileged shell reader stopped: $sessionId/$streamKind", it)
                }
                handleProcessClosed("The privileged shell session stream closed unexpectedly.")
            }
        }

        private fun startExitWatcher() {
            thread(start = true, isDaemon = true, name = "privileged-shell-exit-$sessionId") {
                val exitCode = runCatching { process.waitFor() }.getOrNull()
                handleProcessClosed(
                    message = if (exitCode == null) {
                        "The privileged shell session exited."
                    } else {
                        "The privileged shell session exited with code $exitCode."
                    }
                )
            }
        }

        private fun handleLine(streamKind: StreamKind, rawLine: String, terminated: Boolean = true) {
            if (rawLine == managed.grantMarker) return
            val text = rawLine + if (terminated) "\n" else ""
            synchronized(lock) {
                val active = currentCommand
                if (active != null) {
                    if (streamKind == StreamKind.STDOUT && rawLine.startsWith(active.stdoutMarker)) {
                        parseStdoutMarker(active, rawLine)
                        if (active.stdoutDone && active.stderrDone) {
                            active.completed = true
                            currentCommand = null
                            lock.notifyAll()
                        }
                        return
                    }
                    if (streamKind == StreamKind.STDERR && rawLine == active.stderrMarker) {
                        active.stderrDone = true
                        if (active.stdoutDone && active.stderrDone) {
                            active.completed = true
                            currentCommand = null
                            lock.notifyAll()
                        }
                        return
                    }
                    when (streamKind) {
                        StreamKind.STDOUT -> active.stdoutBuffer.appendText(text)
                        StreamKind.STDERR -> active.stderrBuffer.appendText(text)
                    }
                    if (active.recordTranscript) {
                        appendTranscript(streamKind, text)
                    }
                    return
                }
                appendTranscript(streamKind, text)
            }
        }

        private fun parseStdoutMarker(state: CommandState, rawLine: String) {
            val parts = rawLine.split(UNIT_SEPARATOR, limit = 3)
            state.stdoutDone = true
            state.exitCode = parts.getOrNull(1)?.toIntOrNull()
            state.workingDirectory = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
        }

        private fun appendTranscript(streamKind: StreamKind, rawLine: String) {
            val normalized = when (streamKind) {
                StreamKind.STDOUT -> rawLine
                StreamKind.STDERR -> "[stderr] $rawLine"
            }
            transcriptBuffer.appendText(normalized)
        }

        private fun handleProcessClosed(message: String) {
            synchronized(lock) {
                if (closed) {
                    return
                }
                closed = true
                currentCommand?.apply {
                    failureMessage = message
                    completed = true
                }
                currentCommand = null
                lock.notifyAll()
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, ShellSession>()

    fun startSession(
        request: PrivilegedRequest,
        backend: PrivilegedBackend,
        admission: () -> Boolean = { true },
    ): PrivilegedResult {
        val requestedSessionId = request.sessionId?.trim().takeUnless { it.isNullOrEmpty() }
        val sessionId = requestedSessionId ?: UUID.randomUUID().toString()
        if (sessions.containsKey(sessionId)) {
            return sessionFailure(
                request = request,
                backend = backend,
                code = "session_exists",
                message = "A privileged shell session with the same id already exists."
            )
        }
        val session = runCatching {
            ShellSession(
                sessionId = sessionId,
                backend = backend,
                managed = runner.launch("session:$sessionId", listOf(SHELL_PATH), admission)
            )
        }.getOrElse { error ->
            return sessionFailure(
                request = request,
                backend = backend,
                code = when {
                    error.message == "privileged_access_changed" -> "privileged_access_changed"
                    backend == PrivilegedBackend.ROOT && error is java.io.IOException -> "root_unavailable"
                    else -> "session_start_failed"
                },
                message = error.message ?: "Failed to start the privileged shell session."
            )
        }
        if (sessions.putIfAbsent(sessionId, session) != null) {
            session.close()
            return sessionFailure(request, backend, "session_exists", "A session with this id already exists.")
        }

        try {
            val identity = session.execute("id -u", null, emptyMap(), 10, false)
            val expectedUid = if (backend == PrivilegedBackend.ROOT) "0" else "2000"
            if (!identity.success || identity.stdout.trim() != expectedUid) {
                sessions.remove(sessionId, session)
                session.close()
                return sessionFailure(request, backend,
                    if (identity.timedOut) "timeout" else if (backend == PrivilegedBackend.ROOT) "root_denied" else "permission_denied",
                    "Could not verify the privileged shell identity.")
            }
            if (!request.workingDirectory.isNullOrBlank() || request.environment.isNotEmpty()) {
                val bootstrap = session.execute(":", request.workingDirectory, request.environment, 10, false)
                if (!bootstrap.success) {
                    sessions.remove(sessionId, session)
                    session.close()
                    return sessionFailure(request, backend, bootstrap.code, bootstrap.message)
                }
            }
        } catch (error: Exception) {
            sessions.remove(sessionId, session)
            session.close()
            throw error
        }

        return PrivilegedResult(
            requestId = request.requestId,
            action = request.action,
            success = true,
            code = "ok",
            message = "Privileged shell session started successfully.",
            backend = backend,
            availableActions = PrivilegedActionPolicy.visibleAgentActions(backend),
            sessionId = sessionId,
            workingDirectory = request.workingDirectory,
            environment = request.environment,
            data = mapOf(
                "created" to "true",
                "alive" to "true",
                "running" to "false"
            )
        )
    }

    fun execSession(
        request: PrivilegedRequest,
        backend: PrivilegedBackend,
        admission: () -> Boolean = { true },
    ): PrivilegedResult {
        val sessionId = request.sessionId?.trim().orEmpty()
        if (sessionId.isEmpty()) {
            return sessionFailure(
                request = request,
                backend = backend,
                code = "invalid_arguments",
                message = "sessionId is required."
            )
        }
        val session = sessions[sessionId]
            ?: return sessionFailure(
                request = request,
                backend = backend,
                code = "session_not_found",
                message = "The privileged shell session does not exist.",
                sessionId = sessionId
            )
        val timeoutSeconds = request.timeoutSeconds?.coerceIn(5, 600) ?: DEFAULT_SESSION_EXEC_TIMEOUT_SECONDS
        val result = try { session.execute(
            command = request.command,
            workingDirectory = request.workingDirectory,
            environment = request.environment,
            timeoutSeconds = timeoutSeconds,
            recordTranscript = true,
            admission = admission,
        ) } catch (error: InterruptedException) {
            sessions.remove(sessionId, session)
            session.close()
            throw error
        }
        if (!session.isAlive()) {
            sessions.remove(sessionId)
        }
        return PrivilegedResult(
            requestId = request.requestId,
            action = request.action,
            success = result.success,
            code = result.code,
            message = result.message,
            backend = backend,
            output = combineOutput(result.stdout, result.stderr),
            stdout = result.stdout,
            stderr = result.stderr,
            transcript = result.transcript,
            exitCode = result.exitCode,
            availableActions = PrivilegedActionPolicy.visibleAgentActions(backend),
            command = request.command,
            timeoutSeconds = timeoutSeconds,
            workingDirectory = result.workingDirectory ?: request.workingDirectory,
            environment = request.environment,
            sessionId = sessionId,
            data = mapOf(
                "alive" to session.isAlive().toString(),
                "running" to result.commandRunning.toString()
            )
        )
    }

    fun readSession(
        request: PrivilegedRequest,
        backend: PrivilegedBackend,
    ): PrivilegedResult {
        val sessionId = request.sessionId?.trim().orEmpty()
        if (sessionId.isEmpty()) {
            return sessionFailure(
                request = request,
                backend = backend,
                code = "invalid_arguments",
                message = "sessionId is required."
            )
        }
        val session = sessions[sessionId]
            ?: return sessionFailure(
                request = request,
                backend = backend,
                code = "session_not_found",
                message = "The privileged shell session does not exist.",
                sessionId = sessionId
            )
        val maxChars = request.arguments["maxChars"]?.toIntOrNull()?.takeIf { it > 0 }
        val transcript = session.readTranscript(maxChars)
        if (!session.isAlive()) {
            sessions.remove(sessionId)
        }
        return PrivilegedResult(
            requestId = request.requestId,
            action = request.action,
            success = true,
            code = "ok",
            message = "Privileged shell session output read successfully.",
            backend = backend,
            output = transcript,
            transcript = transcript,
            availableActions = PrivilegedActionPolicy.visibleAgentActions(backend),
            sessionId = sessionId,
            data = mapOf(
                "alive" to session.isAlive().toString(),
                "running" to session.isBusy().toString()
            )
        )
    }

    fun stopSession(
        request: PrivilegedRequest,
        backend: PrivilegedBackend,
    ): PrivilegedResult {
        val sessionId = request.sessionId?.trim().orEmpty()
        if (sessionId.isEmpty()) {
            return sessionFailure(
                request = request,
                backend = backend,
                code = "invalid_arguments",
                message = "sessionId is required."
            )
        }
        val session = sessions.remove(sessionId)
            ?: return sessionFailure(
                request = request,
                backend = backend,
                code = "session_not_found",
                message = "The privileged shell session does not exist.",
                sessionId = sessionId
            )
        session.close()
        return PrivilegedResult(
            requestId = request.requestId,
            action = request.action,
            success = true,
            code = "ok",
            message = "Privileged shell session stopped successfully.",
            backend = backend,
            availableActions = PrivilegedActionPolicy.visibleAgentActions(backend),
            sessionId = sessionId,
            data = mapOf(
                "stopped" to "true",
                "alive" to "false",
                "running" to "false"
            )
        )
    }

    fun shutdown() {
        sessions.keys.toList().forEach { sessionId ->
            sessions.remove(sessionId)?.close()
        }
    }

    companion object {
        private const val MAX_TRANSCRIPT_CHARS = 64 * 1024
        private fun buildCommandScript(
            token: String,
            command: String,
            workingDirectory: String?,
            environment: Map<String, String>,
        ): String {
            val lines = mutableListOf<String>()
            workingDirectory?.trim()?.takeIf { it.isNotEmpty() }?.let { directory ->
                lines += "cd ${shellQuote(directory)} || exit"
            }
            environment.forEach { (key, value) ->
                require(ENV_KEY_PATTERN.matches(key)) { "Invalid environment variable name: $key" }
                lines += "export $key=${shellQuote(value)}"
            }
            lines += command
            lines += "__omnibot_exit_code=\$?"
            lines += "__omnibot_pwd=\$(pwd 2>/dev/null || printf '')"
            lines += "printf '\\n%s\\037%s\\037%s\\n' ${shellQuote(stdoutMarker(token))} \"\$__omnibot_exit_code\" \"\$__omnibot_pwd\""
            lines += "printf '\\n%s\\n' ${shellQuote(stderrMarker(token))} 1>&2"
            return lines.joinToString(separator = "\n", postfix = "\n")
        }

        private fun stdoutMarker(token: String): String = "__OMNIBOT_DONE__$token"

        private fun stderrMarker(token: String): String = "__OMNIBOT_DONE_ERR__$token"

        private fun combineOutput(stdout: String, stderr: String): String {
            return when {
                stdout.isNotBlank() && stderr.isNotBlank() -> "$stdout\n[stderr]\n$stderr"
                stdout.isNotBlank() -> stdout
                else -> stderr
            }
        }

        private fun sessionFailure(
            request: PrivilegedRequest,
            backend: PrivilegedBackend,
            code: String,
            message: String,
            stdout: String = "",
            stderr: String = "",
            transcript: String = "",
            exitCode: Int? = null,
            sessionId: String? = request.sessionId,
        ): PrivilegedResult {
            return PrivilegedResult(
                requestId = request.requestId,
                action = request.action,
                success = false,
                code = code,
                message = message,
                backend = backend,
                output = combineOutput(stdout, stderr),
                stdout = stdout,
                stderr = stderr,
                transcript = transcript,
                exitCode = exitCode,
                availableActions = PrivilegedActionPolicy.visibleAgentActions(backend),
                command = request.command,
                timeoutSeconds = request.timeoutSeconds,
                workingDirectory = request.workingDirectory,
                environment = request.environment,
                sessionId = sessionId
            )
        }

        private fun shellQuote(value: String): String {
            return "'${value.replace("'", "'\"'\"'")}'"
        }

        private const val TAG = "PrivilegedShellSessionMgr"
        private const val SHELL_PATH = "/system/bin/sh"
        private const val DEFAULT_SESSION_EXEC_TIMEOUT_SECONDS = 120
        private const val UNIT_SEPARATOR = '\u001F'
        private val ENV_KEY_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}
