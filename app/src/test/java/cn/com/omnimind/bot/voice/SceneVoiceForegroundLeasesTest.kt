package cn.com.omnimind.bot.voice

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SceneVoiceForegroundLeasesTest {
    @Test
    fun `playback waits for promotion and multiple managers share the foreground service`() = runBlocking {
        var visible = true
        val starts = mutableListOf<Long>()
        var stops = 0
        val leases = SceneVoiceForegroundLeases({ visible }, starts::add, { stops++ })
        val first = leases.acquire("engine-a") { _, _ -> }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { first.awaitReady() }
        assertFalse(waiting.isCompleted)
        visible = false
        val second = leases.acquire("engine-b") { _, _ -> }
        assertEquals(1, starts.size)
        assertFalse(second.isReady)

        assertTrue(leases.onForeground(starts.single()))
        waiting.await()
        assertTrue(first.isReady)
        assertTrue(second.isReady)
        leases.release(first)
        assertEquals(0, stops)
        assertTrue(second.isReady)
        leases.release(second)
        assertEquals(1, stops)
        assertFalse(leases.hasOwners())
    }

    @Test
    fun `a background first request is rejected without starting a service`() {
        var starts = 0
        val leases = SceneVoiceForegroundLeases({ false }, { starts++ }, {})
        assertThrows(IllegalStateException::class.java) {
            leases.acquire(Any()) { _, _ -> }
        }
        assertEquals(0, starts)
        assertFalse(leases.hasOwners())
    }

    @Test
    fun `late destruction and release cannot terminate a replacement queue`() {
        val starts = mutableListOf<Long>()
        var stops = 0
        val leases = SceneVoiceForegroundLeases({ true }, starts::add, { stops++ })
        val owner = Any()
        val first = leases.acquire(owner) { _, _ -> fail("Old callback must not run") }
        leases.onForeground(starts.last())
        leases.release(first)
        val second = leases.acquire(owner) { _, _ -> fail("Replacement must remain active") }
        assertEquals(2, starts.size)
        leases.onForeground(starts.last())

        leases.stopAll("Old service destroyed", expectedGeneration = starts.first())
        leases.release(first)
        assertTrue(second.isReady)
        assertEquals(1, stops)
        leases.release(second)
    }

    @Test
    fun `notification stop stops every manager and a new queue survives an old callback`() {
        val starts = mutableListOf<Long>()
        var stops = 0
        val leases = SceneVoiceForegroundLeases({ true }, starts::add, { stops++ })
        val stopped = mutableListOf<String>()
        lateinit var replacement: SceneVoiceForegroundLeases.Lease
        val first = leases.acquire("a") { _, reason ->
            assertNull(reason)
            stopped += "a"
            replacement = leases.acquire("replacement") { _, _ -> }
        }
        val second = leases.acquire("b") { _, _ -> stopped += "b" }
        leases.onForeground(starts.first())
        leases.stopAll()

        assertEquals(listOf("a", "b"), stopped)
        assertFalse(first.active)
        assertFalse(second.active)
        assertTrue(replacement.active)
        assertEquals(0, stops)
        assertEquals(2, starts.size)
        leases.onForeground(starts.last())
        assertTrue(replacement.isReady)
        leases.release(replacement)
    }

    @Test
    fun `foreground promotion failure reports the failure before releasing waiters`() {
        val starts = mutableListOf<Long>()
        var reported: String? = null
        val leases = SceneVoiceForegroundLeases({ true }, starts::add, {})
        val lease = leases.acquire(Any()) { _, reason -> reported = reason }
        leases.stopAll("Foreground start denied", starts.single())
        assertEquals("Foreground start denied", reported)
        assertFalse(lease.active)
        assertTrue(lease.ready.isCancelled)
        assertFalse(leases.hasOwners())
    }

    @Test
    fun `rejected service launch does not leak a lease`() {
        val leases = SceneVoiceForegroundLeases({ true }, { throw SecurityException("Denied") }, {})
        assertThrows(SecurityException::class.java) {
            leases.acquire(Any()) { _, _ -> }
        }
        assertFalse(leases.hasOwners())
    }
}
