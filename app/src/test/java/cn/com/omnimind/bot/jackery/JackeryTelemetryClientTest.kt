package cn.com.omnimind.bot.jackery

import org.junit.Assert.*
import org.junit.Test

class JackeryTelemetryClientTest {
    @Test fun absentInterfaceHasNoHardwareValues() {
        val status = JackeryTelemetryClient().read("")
        assertFalse(status.connected)
        assertNull(status.batteryPercent)
        assertNull(status.aiModelStatus)
        assertEquals("not_configured", status.unavailableReason)
    }

    @Test fun malformedOrUnsupportedEndpointCannotProduceStatusOrOpenAnotherProtocol() {
        listOf("not a URL", "file:///private/device.json", "http://user:password@device/status", "https://device/status#cache")
            .forEach { address ->
                val status = JackeryTelemetryClient().read(address)
                assertFalse(status.connected)
                assertNull(status.batteryPercent)
                assertNull(status.power)
                assertEquals("invalid_endpoint", status.unavailableReason)
            }
    }
}
