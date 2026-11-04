package cn.com.omnimind.bot.agent.runtime

import org.junit.Assert.*
import org.junit.Test

class NaLocalTimeTest {
    @Test fun convertsProfileZoneInsteadOfDeviceZone() {
        assertEquals("2026-11-05T01:30:00Z", NaLocalTime.convert(mapOf("localTime" to "2026-11-05T09:30", "timezone" to "Asia/Shanghai"))["at"])
        assertEquals("2026-11-04T20:30", NaLocalTime.convert(mapOf("at" to "2026-11-05T01:30:00Z", "timezone" to "America/New_York"))["localTime"])
    }
    @Test fun rejectsNonexistentAndAmbiguousDstWallTimes() {
        for (local in listOf("2026-03-08T02:30", "2026-11-01T01:30")) {
            try { NaLocalTime.convert(mapOf("localTime" to local, "timezone" to "America/New_York")); fail("must not silently shift or choose an offset") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun rejectsInvalidCalendarAndZoneInput() {
        for (args in listOf(mapOf("localTime" to "2026-02-30T09:30", "timezone" to "UTC"), mapOf("localTime" to "2026-11-05T09:30", "timezone" to "Invalid/Zone"))) {
            try { NaLocalTime.convert(args); fail("invalid input") } catch (_: java.time.DateTimeException) { }
        }
    }
}
