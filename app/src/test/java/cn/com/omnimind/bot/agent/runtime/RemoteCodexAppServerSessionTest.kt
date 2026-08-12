package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.Collections
import org.junit.Assume.assumeFalse

class RemoteCodexAppServerSessionTest {

    private fun requirePosixShell() {
        assumeFalse(
            "POSIX shell integration runs in Linux CI",
            System.getProperty("os.name").orEmpty().startsWith("Windows")
        )
    }

    @Test
    fun startSendsInitializeAndInitializedThenEmitsConnected() = runBlocking {
        requirePosixShell()
        val harness = RemoteCodexSessionHarness(
            """
            read first
            printf '%s\n' "${'$'}first" >> "${'$'}OMNI_TEST_LOG"
            printf '{"id":1,"result":{"server":"ok"}}\n'
            read second
            printf '%s\n' "${'$'}second" >> "${'$'}OMNI_TEST_LOG"
            sleep 5
            """.trimIndent()
        )
        try {
            harness.session.start(clientVersion = "1.2.3")
            waitUntil { harness.logFile.readLinesOrEmpty().size >= 2 }

            val lines = harness.logFile.readLines()
            assertTrue(lines[0].contains("\"method\":\"initialize\""))
            assertTrue(lines[0].contains("\"experimentalApi\":true"))
            assertTrue(lines[0].contains("\"version\":\"1.2.3\""))
            assertTrue(lines[1].contains("\"method\":\"initialized\""))
            assertTrue(harness.eventsSnapshot().any { it["method"] == "codex/connected" })
        } finally {
            harness.close()
        }
    }

    @Test
    fun sendRequestCorrelatesResponseAndRoutesNotifications() = runBlocking {
        requirePosixShell()
        val harness = RemoteCodexSessionHarness(
            """
            read init
            printf '{"id":1,"result":{}}\n'
            read initialized
            read request
            printf '{"method":"turn/started","params":{"threadId":"thread-1","turnId":"turn-1"}}\n'
            printf '{"id":2,"result":{"ok":true,"threadId":"thread-1"}}\n'
            sleep 5
            """.trimIndent()
        )
        try {
            harness.session.start(clientVersion = "1.2.3")
            val response = harness.session.sendRequest(
                method = "thread/list",
                params = mapOf("limit" to 10)
            )

            val result = response["result"] as? Map<*, *>
            assertEquals(true, result?.get("ok"))
            waitUntil { harness.eventsSnapshot().any { it["method"] == "turn/started" } }
        } finally {
            harness.close()
        }
    }

    @Test
    fun configReadRequestSendsObjectParams() = runBlocking {
        requirePosixShell()
        val harness = RemoteCodexSessionHarness(
            """
            read init
            printf '{"id":1,"result":{}}\n'
            read initialized
            read request
            printf '%s\n' "${'$'}request" >> "${'$'}OMNI_TEST_LOG"
            printf '{"id":2,"result":{"config":{"model":"custom-codex"}}}\n'
            sleep 5
            """.trimIndent()
        )
        try {
            harness.session.start(clientVersion = "1.2.3")
            harness.session.sendRequest(
                method = "config/read",
                params = emptyMap<String, Any?>()
            )
            waitUntil { harness.logFile.readLinesOrEmpty().isNotEmpty() }

            val request = harness.logFile.readText()
            assertTrue(request.contains("\"method\":\"config/read\""))
            assertTrue(request.contains("\"params\":{}"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun stdoutParseErrorIsForwardedAsEvent() = runBlocking {
        requirePosixShell()
        val harness = RemoteCodexSessionHarness(
            """
            read init
            printf '{"id":1,"result":{}}\n'
            read initialized
            printf 'not-json\n'
            sleep 5
            """.trimIndent()
        )
        try {
            harness.session.start(clientVersion = "1.2.3")
            waitUntil {
                harness.eventsSnapshot().any { it["method"] == "codex/parseError" }
            }
            val parseError = harness.eventsSnapshot().first { it["method"] == "codex/parseError" }
            val params = parseError["params"] as? Map<*, *>
            assertEquals("not-json", params?.get("raw"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun processExitCompletesPendingStateAndBlocksFutureRequests() = runBlocking {
        requirePosixShell()
        val harness = RemoteCodexSessionHarness(
            """
            read init
            printf '{"id":1,"result":{}}\n'
            read initialized
            exit 7
            """.trimIndent()
        )
        try {
            harness.session.start(clientVersion = "1.2.3")
            waitUntil {
                harness.eventsSnapshot().any { it["method"] == "codex/disconnected" }
            }
            assertTrue(!harness.session.isRunning)

            val error = runCatching {
                harness.session.sendRequest(method = "thread/list")
            }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error is IllegalStateException)
        } finally {
            harness.close()
        }
    }

    private class RemoteCodexSessionHarness(scriptBody: String) {
        private val tempDir: File = Files.createTempDirectory("codex-session-test").toFile()
        val logFile: File = File(tempDir, "stdin.log")
        private val scriptFile: File = File(tempDir, "fake-app-server.sh")
        private val scopeJob = SupervisorJob()
        private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
        val events: MutableList<Map<String, Any?>> = Collections.synchronizedList(mutableListOf())

        val session = RemoteCodexAppServerSession(
            scope = scope,
            onServerMessage = { message -> events.add(message) },
            connectionFactory = {
                scriptFile.writeText("#!/bin/sh\nset -eu\n$scriptBody\n")
                val process = ProcessBuilder("/bin/sh", scriptFile.absolutePath)
                    .apply {
                        environment()["OMNI_TEST_LOG"] = logFile.absolutePath
                    }
                    .start()
                ScriptedProcessConnection(scope, process)
            }
        )

        suspend fun close() {
            session.disconnect()
            scopeJob.cancelAndJoin()
            tempDir.deleteRecursively()
        }

        fun eventsSnapshot(): List<Map<String, Any?>> {
            return synchronized(events) {
                events.toList()
            }
        }
    }

    private class ScriptedProcessConnection(
        private val scope: CoroutineScope,
        private val process: Process
    ) : RemoteCodexAppServerConnection {
        private var stdoutJob: Job? = null
        private var stderrJob: Job? = null
        private var waitJob: Job? = null

        override val isRunning: Boolean
            get() = process.isAlive

        override suspend fun start(
            onStdoutLine: suspend (String) -> Unit,
            onStderrLine: suspend (String) -> Unit,
            onExit: suspend (Int?) -> Unit
        ) {
            stdoutJob = scope.launch(Dispatchers.IO) {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) onStdoutLine(line)
                    }
                }
            }
            stderrJob = scope.launch(Dispatchers.IO) {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) onStderrLine(line)
                    }
                }
            }
            waitJob = scope.launch(Dispatchers.IO) {
                val exitCode = runCatching { process.waitFor() }.getOrNull()
                onExit(exitCode)
            }
        }

        override suspend fun writeLine(line: String) {
            OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8).apply {
                write(line)
                flush()
            }
        }

        override suspend fun close() {
            runCatching { process.outputStream.close() }
            if (process.isAlive) process.destroy()
            stdoutJob?.cancelAndJoin()
            stderrJob?.cancelAndJoin()
            waitJob?.cancelAndJoin()
        }
    }
}

private suspend fun waitUntil(predicate: () -> Boolean) {
    withTimeout(5_000L) {
        while (!predicate()) {
            delay(10L)
        }
    }
}

private fun File.readLinesOrEmpty(): List<String> {
    return if (exists()) readLines() else emptyList()
}
