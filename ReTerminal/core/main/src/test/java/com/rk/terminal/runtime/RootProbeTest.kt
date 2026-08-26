package com.rk.terminal.runtime

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RootProbeTest {
    @Before
    fun reset() {
        RootProbe.invalidateCache()
        RootProbe.nowMs = { System.currentTimeMillis() }
    }

    @After
    fun restore() {
        RootProbe.invalidateCache()
        RootProbe.nowMs = { System.currentTimeMillis() }
    }

    @Test
    fun parseAcceptsFullRootAndCapabilities() {
        val output = """
            uid=0(root) gid=0(root)
            Uid:	0	0	0	0
            CapEff:	000001ffffffffff
        """.trimIndent()
        assertTrue(RootProbe.parseProbeOutput(output))
    }

    @Test
    fun parseRejectsMissingCapability() {
        val output = """
            Uid:	0	0	0	0
            CapEff:	0000000000000000
        """.trimIndent()
        assertFalse(RootProbe.parseProbeOutput(output))
    }

    @Test
    fun cacheHitDoesNotNeedSu() = runBlocking {
        RootProbe.nowMs = { 10_000L }
        RootProbe.seedCache(true, 10_000L)
        RootProbe.nowMs = { 20_000L }
        assertTrue(RootProbe.isSuAvailable())
    }

    @Test
    fun expiredCacheFallsThroughToFailedProbe() = runBlocking {
        RootProbe.nowMs = { 10_000L }
        RootProbe.seedCache(true, 10_000L)
        RootProbe.nowMs = { 50_000L }
        assertFalse(RootProbe.isSuAvailable())
    }
}
