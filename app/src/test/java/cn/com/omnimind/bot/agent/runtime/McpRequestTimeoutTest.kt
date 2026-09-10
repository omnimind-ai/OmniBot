package cn.com.omnimind.bot.agent.runtime

import java.util.concurrent.TimeoutException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class McpRequestTimeoutTest {
    @Test fun `request timeout is a transport failure and caller can continue`() = runBlocking {
        repeat(3) {
            try {
                withMcpRequestTimeout(10, "tools/call") { awaitCancellation() }
                fail("Expected timeout")
            } catch (error: TimeoutException) {
                assertTrue(error.message.orEmpty().contains("tools/call"))
            }
            assertTrue(currentCoroutineContext().isActive)
            assertEquals("next response", withMcpRequestTimeout(1000, "tools/call") { "next response" })
        }
    }

    @Test fun `outer task cancellation stays cancellation`() = runBlocking {
        val result = withTimeoutOrNull(10) {
            withMcpRequestTimeout(1000, "tools/call") { awaitCancellation() }
        }
        assertNull(result)
    }

    @Test fun `explicit cancellation is not converted to request timeout`() = runBlocking {
        try {
            withMcpRequestTimeout(1000, "tools/call") { throw CancellationException("user stopped") }
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("user stopped", error.message)
        }
    }
}
