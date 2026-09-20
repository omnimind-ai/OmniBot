package cn.com.omnimind.bot.localmodel

import org.junit.Assert.*
import org.junit.Test

class LocalInferenceBackendTest {
    @Test fun `unsupported device only starts CPU`() {
        val attempts = mutableListOf<String>()
        val selected = LocalInferenceBackend.select(false, LocalInferenceBackend.HTP,
            { attempts += it; true }, { fail("unexpected reset") }, { "" }, {})
        assertEquals(listOf(LocalInferenceBackend.CPU), attempts)
        assertEquals(LocalInferenceBackend.CPU, selected)
    }

    @Test fun `HTP failure is cleaned up before CPU and successful CPU survives next startup`() {
        val events = mutableListOf<String>()
        var saved: String? = null
        LocalInferenceBackend.select(true, saved,
            { events += it; it == LocalInferenceBackend.CPU }, { events += "reset" },
            { "unsupported accelerator" }, { saved = it })
        assertEquals(listOf(LocalInferenceBackend.HTP, "reset", LocalInferenceBackend.CPU), events)
        events.clear()
        LocalInferenceBackend.select(true, saved, { events += it; true }, {}, { "" }, {})
        assertEquals(listOf(LocalInferenceBackend.CPU), events)
    }

    @Test fun `successful HTP needs no CPU fallback`() {
        var saved: String? = null
        val selected = LocalInferenceBackend.select(true, null, { true },
            { fail("unexpected reset") }, { "" }, { saved = it })
        assertEquals(LocalInferenceBackend.HTP, selected)
        assertEquals(selected, saved)
    }

    @Test fun `both failures remain failure and are not persisted`() {
        var resets = 0
        try {
            LocalInferenceBackend.select(true, null, { false }, { resets++ },
                { "load rejected" }, { fail("must not save failure") })
            fail("must fail startup")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("load rejected"))
            assertEquals(2, resets)
        }
    }

    @Test fun `unexpected SDK exception is surfaced without replay`() {
        val attempts = mutableListOf<String>()
        try {
            LocalInferenceBackend.select(true, null,
                { attempts += it; throw IllegalArgumentException("SDK contract failure") }, {},
                { "" }, { fail("must not persist") })
            fail("must surface exception")
        } catch (e: IllegalArgumentException) {
            assertEquals(listOf(LocalInferenceBackend.HTP), attempts)
        }
    }

    @Test fun `LiteRT uses GPU on non Qualcomm and never sends model to llama`() {
        val attempts = mutableListOf<String>()
        val selected = LocalInferenceBackend.select(false, LocalInferenceBackend.CPU,
            { attempts += it; true }, {}, { "" }, {}, liteRt = true)
        assertEquals(listOf(LocalInferenceBackend.LITERT_GPU), attempts)
        assertEquals(LocalInferenceBackend.LITERT_GPU, selected)
    }

    @Test fun `LiteRT GPU rejection falls back to same model CPU and remembers it`() {
        val events = mutableListOf<String>()
        var saved: String? = null
        LocalInferenceBackend.select(true, null,
            { events += it; it == LocalInferenceBackend.LITERT_CPU }, { events += "reset" },
            { "GPU unavailable" }, { saved = it }, liteRt = true)
        assertEquals(listOf(LocalInferenceBackend.LITERT_GPU, "reset", LocalInferenceBackend.LITERT_CPU), events)
        events.clear()
        LocalInferenceBackend.select(false, saved, { events += it; true }, {}, { "" }, {}, liteRt = true)
        assertEquals(listOf(LocalInferenceBackend.LITERT_CPU), events)
    }

    @Test fun `LiteRT double failure is terminal and cannot cache success`() {
        val attempts = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            LocalInferenceBackend.select(false, null, { attempts += it; false }, {},
                { "model unsupported" }, { fail("must not save") }, liteRt = true)
        }
        assertEquals(listOf(LocalInferenceBackend.LITERT_GPU, LocalInferenceBackend.LITERT_CPU), attempts)
    }
}
