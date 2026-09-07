package com.rk.libcommons

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalResolverTest {
    @Test fun activeNetworkResolversReplaceFixedPublicDns() {
        val addresses = listOf("192.0.2.10", "192.0.2.10", "192.0.2.11", "192.0.2.12", "192.0.2.13")
            .map(InetAddress::getByName)
        assertEquals("nameserver 192.0.2.10\nnameserver 192.0.2.11\nnameserver 192.0.2.12\n",
            OmnibotTerminalEnvironment.buildResolverConfig(addresses))
    }
    @Test fun absentNetworkDoesNotInventDns() {
        assertEquals("", OmnibotTerminalEnvironment.buildResolverConfig(emptyList()))
    }
    @Test fun networkChangeAndIpv6ProduceFreshConfiguration() {
        val first = OmnibotTerminalEnvironment.buildResolverConfig(listOf(InetAddress.getByName("192.0.2.10")))
        val address = InetAddress.getByName("2001:db8::53")
        val second = OmnibotTerminalEnvironment.buildResolverConfig(listOf(address))
        assertEquals("nameserver ${address.hostAddress}\n", second)
        org.junit.Assert.assertNotEquals(first, second)
    }
}
