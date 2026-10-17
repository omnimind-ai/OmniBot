package cn.com.omnimind.bot.agent.runtime

import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProcessCleanupTest {
    @Test fun `closing a quiet server unblocks its pending stdout read`() {
        val process = ProcessBuilder("sh", "-c", "exec sleep 30").start()
        val enteredRead = CountDownLatch(1)
        val closingStarted = CountDownLatch(1)
        val input = object : FilterInputStream(process.inputStream) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                enteredRead.countDown()
                return super.read(bytes, offset, length)
            }
        }
        val reader = input.bufferedReader()
        val writer = process.outputStream.bufferedWriter()
        val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "mcp-cleanup-regression").apply { isDaemon = true }
        }
        try {
            val reading = executor.submit<String?> {
                try {
                    reader.readLine()
                } catch (error: IOException) {
                    // destroy() closes stdout too. If it wins the race with the
                    // underlying read, the JDK reports a closed stream instead of EOF.
                    if (closingStarted.count != 0L || error.message != "Stream closed") throw error
                    null
                }
            }
            assertTrue("stdout read started", enteredRead.await(2, TimeUnit.SECONDS))
            assertTrue("quiet server is running before close", process.isAlive)
            assertFalse("stdout read remains pending before close", reading.isDone)
            val closing = executor.submit {
                closingStarted.countDown()
                closeMcpProcess(process, reader, writer)
            }
            closing.get(2, TimeUnit.SECONDS)
            assertTrue("server terminated", process.waitFor(2, TimeUnit.SECONDS))
            assertNull("quiet stdout ended", reading.get(2, TimeUnit.SECONDS))
            closeMcpProcess(process, reader, writer)
        } finally {
            process.destroyForcibly()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
}
