package cn.com.omnimind.bot.jackery

import org.junit.Assert.*
import org.junit.Test

class JackeryDeviceStatusTest {
    private val now = 1_800_000_000_000L
    private fun telemetry(vararg changes: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "schemaVersion" to 1, "observedAtEpochMs" to now, "connected" to true,
        "batteryPercent" to 73, "power" to "battery", "connection" to "wifi", "aiModelStatus" to "ready",
    ) + changes

    @Test fun projectsOnlyFreshVendorValues() {
        val status = JackeryDeviceStatus.fromTelemetry(telemetry(), now)
        assertTrue(status.connected)
        assertEquals(73, status.batteryPercent)
        assertEquals("battery", status.power)
        assertEquals("wifi", status.connection)
        assertEquals("ready", status.aiModelStatus)
    }

    @Test fun disconnectAndStalenessDiscardEveryLastKnownValue() {
        listOf(telemetry("connected" to false), telemetry("observedAtEpochMs" to now - 15_001),
            telemetry("observedAtEpochMs" to now + 5_001), telemetry("observedAtEpochMs" to null),
            telemetry("schemaVersion" to 2)).forEach { payload ->
            val status = JackeryDeviceStatus.fromTelemetry(payload, now)
            assertFalse(status.connected)
            assertNull(status.batteryPercent)
            assertNull(status.power)
            assertNull(status.connection)
            assertNull(status.aiModelStatus)
        }
    }

    @Test fun invalidOrMissingFieldsStayUnknownWithoutChangingValidConnection() {
        listOf(null, -1, 101, 42.5, Double.NaN, "80").forEach { battery ->
            val status = JackeryDeviceStatus.fromTelemetry(telemetry("batteryPercent" to battery,
                "power" to "magic", "connection" to null, "aiModelStatus" to "true"), now)
            assertTrue(status.connected)
            assertNull(status.batteryPercent)
            assertNull(status.power)
            assertNull(status.connection)
            assertNull(status.aiModelStatus)
        }
        assertEquals(0, JackeryDeviceStatus.fromTelemetry(telemetry("batteryPercent" to 0), now).batteryPercent)
        assertEquals(100, JackeryDeviceStatus.fromTelemetry(telemetry("batteryPercent" to 100), now).batteryPercent)
    }
}
