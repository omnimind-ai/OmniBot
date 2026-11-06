package com.rk.terminal.runtime

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedTerminalHostScriptTest {
    @Test
    fun hostWrapperPreservesStdioExitStatusAndProcessCleanup() {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val contract = generateSequence(workingDirectory) { it.parentFile }
            .map { it.resolve("scripts/test-terminal-host-stop.py") }
            .firstOrNull(File::isFile)
            ?: error("Embedded terminal host contract test is missing.")

        val process = ProcessBuilder("python3", contract.absolutePath)
            .redirectErrorStream(true)
            .start()
        try {
            assertTrue("Host contract test timed out", process.waitFor(30, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.exitValue())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
