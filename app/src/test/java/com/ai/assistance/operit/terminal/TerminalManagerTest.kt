package com.ai.assistance.operit.terminal

import android.content.Context
import com.rk.terminal.runtime.EmbeddedRuntimeInstaller
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TerminalManagerTest {
    @Test
    fun `preparing Ubuntu does not reuse Alpine readiness`() = runBlocking {
        val directory = Files.createTempDirectory("terminal-distribution-test").toFile()
        try {
            val files = directory.resolve("files").apply { mkdirs() }
            listOf("proot", "libtalloc.so.2").forEach {
                files.resolve(it).writeText("fixture")
            }
            val context = mock(Context::class.java)
            `when`(context.filesDir).thenReturn(files)

            // An unextracted archive in filesDir must not be treated as a ready rootfs.
            files.resolve("alpine.tar.gz").writeText("fixture")
            assertFalse(EmbeddedRuntimeInstaller.isDistributionReady(context, TerminalDistribution.alpine))

            val alpineRootfs = directory.resolve("local/alpine")
            listOf(
                "bin/sh",
                "etc/os-release",
                EmbeddedRuntimeInstaller.ROOTFS_READY_MARKER_NAME
            ).forEach { rel ->
                alpineRootfs.resolve(rel).apply {
                    parentFile?.mkdirs()
                    writeText("fixture")
                }
            }

            assertTrue(EmbeddedRuntimeInstaller.isDistributionReady(context, TerminalDistribution.alpine))
            assertFalse(EmbeddedRuntimeInstaller.isDistributionReady(context, TerminalDistribution.ubuntu))

            val manager = TerminalManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }.newInstance(context)
            val progress = mutableListOf<EmbeddedRuntimeInstaller.RuntimeInstallProgress>()
            try {
                manager.initializeEnvironment(TerminalDistribution.ubuntu) {
                    progress += it
                    // Admission is the regression boundary; stop before asset or network I/O.
                    throw CancellationException("Stop after installer admission")
                }
            } catch (_: CancellationException) {
                // initializeEnvironment must propagate cancellation from the installer.
            }
            assertEquals(listOf("ubuntu"), progress.map { it.distribution })
            assertEquals(listOf("checking"), progress.map { it.phase })

            files.resolve("ubuntu.tar.gz").writeText("fixture")
            assertFalse(EmbeddedRuntimeInstaller.isDistributionReady(context, TerminalDistribution.ubuntu))

            val ubuntuRootfs = directory.resolve("local/ubuntu")
            listOf(
                "bin/sh",
                "etc/os-release",
                EmbeddedRuntimeInstaller.ROOTFS_READY_MARKER_NAME
            ).forEach { rel ->
                ubuntuRootfs.resolve(rel).apply {
                    parentFile?.mkdirs()
                    writeText("fixture")
                }
            }
            assertTrue(EmbeddedRuntimeInstaller.isDistributionReady(context, TerminalDistribution.ubuntu))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cancellation terminates a blocking hidden process`() = runBlocking {
        val process = BlockingProcess()
        val execution = async(Dispatchers.IO) {
            withCancellableHiddenExecProcess(process) {
                process.waitFor(30, TimeUnit.SECONDS)
            }
        }

        assertTrue(process.waitStarted.await(2, TimeUnit.SECONDS))
        execution.cancel()
        withTimeout(2_000L) { execution.cancelAndJoin() }

        assertTrue(process.destroyCalled.get())
        assertFalse(process.isAlive)
    }

    @Test
    fun `reader close interruption is treated as expected termination`() {
        assertTrue(
            isExpectedHiddenExecReaderTermination(
                InterruptedIOException("read interrupted by close() on another thread")
            )
        )
    }

    @Test
    fun `wrapped closed stream io exception is treated as expected termination`() {
        assertTrue(
            isExpectedHiddenExecReaderTermination(
                IllegalStateException("wrapper", IOException("stream closed"))
            )
        )
    }

    @Test
    fun `ordinary io exception is not treated as expected termination`() {
        assertFalse(
            isExpectedHiddenExecReaderTermination(
                IOException("permission denied")
            )
        )
    }

    private class BlockingProcess : Process() {
        val waitStarted = CountDownLatch(1)
        val destroyCalled = AtomicBoolean(false)
        private val exited = CountDownLatch(1)
        private val output = ByteArrayOutputStream()
        private val input = ByteArrayInputStream(ByteArray(0))
        private val error = ByteArrayInputStream(ByteArray(0))

        override fun getOutputStream(): OutputStream = output

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = error

        override fun waitFor(): Int {
            waitStarted.countDown()
            exited.await()
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitStarted.countDown()
            return exited.await(timeout, unit)
        }

        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException("Process is still running")
            return 0
        }

        override fun destroy() {
            destroyCalled.set(true)
            exited.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = exited.count > 0L
    }
}
