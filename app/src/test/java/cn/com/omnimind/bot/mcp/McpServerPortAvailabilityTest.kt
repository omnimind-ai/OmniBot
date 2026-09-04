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
    fun `MCP authority validation permits loopback and LAN but rejects rebinding origins`() {
        val allowed = listOf("127.0.0.1", "192.168.1.20", "localhost", "[::1]")

        assertTrue(
            McpServerManager.isAllowedMcpRequestAuthority(
                host = "192.168.1.20:8899",
                origin = "http://192.168.1.20:8899",
                allowedHosts = allowed,
            )
        )
        assertTrue(
            McpServerManager.isAllowedMcpRequestAuthority(
                host = "127.0.0.1",
                origin = null,
                allowedHosts = allowed,
            )
        )
        assertTrue(
            McpServerManager.isAllowedMcpRequestAuthority(
                host = "[::1]:8899",
                origin = "http://[::1]:8899",
                allowedHosts = allowed,
            )
        )
        assertFalse(
            McpServerManager.isAllowedMcpRequestAuthority(
                host = "127.0.0.1",
                origin = "https://attacker.example",
                allowedHosts = allowed,
            )
        )
        assertFalse(
            McpServerManager.isAllowedMcpRequestAuthority(
                host = "attacker.example",
                origin = null,
                allowedHosts = allowed,
            )
        )
        assertFalse(
            McpServerManager.isAllowedMcpRequestAuthority(
                host = "attacker.example@127.0.0.1",
                origin = null,
                allowedHosts = allowed,
            )
        )
    }

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
