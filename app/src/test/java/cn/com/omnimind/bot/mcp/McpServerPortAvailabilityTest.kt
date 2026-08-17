package cn.com.omnimind.bot.mcp

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.BindException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerPortAvailabilityTest {
    @Test
    fun occupiedPortIsRejectedBeforeStartingKtor() {
        ServerSocket().use { socket ->
            socket.bind(InetSocketAddress("127.0.0.1", 0))
            assertFalse(McpServerManager.isTcpPortAvailable(socket.localPort))
        }
    }

    @Test
    fun releasedPortCanBeUsed() {
        val port = ServerSocket(0).use { it.localPort }
        assertTrue(McpServerManager.isTcpPortAvailable(port))
    }

    @Test
    fun occupiedPreferredPortSwitchesToNextAvailablePort() {
        assertEquals(
            8901,
            McpServerManager.resolveAvailablePort(
                preferredPort = 8899,
                maxAttempts = 3,
                isAvailable = { it == 8901 },
            ),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun failsWhenSearchRangeHasNoAvailablePort() {
        McpServerManager.resolveAvailablePort(
            preferredPort = 8899,
            maxAttempts = 2,
            isAvailable = { false },
        )
    }

    @Test
    fun detectsWrappedAddressInUseWithoutEscalatingAnOptionalServerFailure() {
        val error = IllegalStateException("server start failed", BindException("Address already in use"))

        assertTrue(McpServerManager.hasAddressAlreadyInUse(error))
        assertFalse(McpServerManager.hasAddressAlreadyInUse(IllegalStateException("network unavailable")))
    }
}
