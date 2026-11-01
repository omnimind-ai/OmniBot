package cn.com.omnimind.baselib.permission

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkEndpointTest {
    @Test
    fun localAddressRangesRequirePermission() {
        listOf(
            "10.0.0.2", "172.16.0.2", "172.31.255.254", "192.168.1.2",
            "169.254.1.2", "100.64.0.2", "100.127.255.254", "0.0.0.0",
            "224.0.0.251", "255.255.255.255", "fc00::1", "fd12::2", "fe80::1",
        ).forEach { host ->
            assertTrue(host, isLocalNetworkAddress(InetAddress.getByName(host)))
        }
    }

    @Test
    fun publicAndLoopbackAddressesRemainAvailable() {
        listOf(
            "8.8.8.8", "172.15.1.2", "172.32.1.2", "100.63.255.254",
            "100.128.0.1", "127.0.0.1", "127.0.0.2", "::1", "2001:4860::8888",
        ).forEach { host ->
            assertFalse(host, isLocalNetworkAddress(InetAddress.getByName(host)))
        }
        assertFalse(isLocalNetworkEndpoint("http://localhost:8899/mcp") {
            error("Loopback should not require DNS")
        })
        assertFalse(isLocalNetworkEndpoint("http://agent.localhost:8899/mcp") {
            error("Loopback should not require DNS")
        })
    }

    @Test
    fun localNamesAndPrivateDnsResultsAreRecognized() {
        assertTrue(isLocalNetworkEndpoint("ws://computer.LOCAL.:8765") {
            error("mDNS itself needs permission")
        })
        assertTrue(isLocalNetworkEndpoint("https://models.example.com/v1") {
            arrayOf(InetAddress.getByName("192.168.1.5"))
        })
        assertTrue(isLocalNetworkEndpoint("http://[fd12::2]:8899/mcp"))
        assertFalse(isLocalNetworkEndpoint("http://[::1]:8899/mcp"))
    }

    @Test
    fun invalidEndpointsAndDnsFailuresDoNotBecomePermissionErrors() {
        assertFalse(isLocalNetworkEndpoint("not a URL"))
        assertFalse(isLocalNetworkEndpoint("https://unavailable.example.com") {
            throw UnknownHostException()
        })
        assertFalse(isLocalNetworkEndpoint("https://models.example.com/v1") {
            arrayOf(InetAddress.getByName("8.8.8.8"))
        })
    }
}
