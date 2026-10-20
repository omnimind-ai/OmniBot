package cn.com.omnimind.bot.jackery

/** A projection of vendor telemetry. No Android phone sensors are consulted. */
internal data class JackeryDeviceStatus(
    val connected: Boolean = false,
    val batteryPercent: Int? = null,
    val power: String? = null,
    val connection: String? = null,
    val aiModelStatus: String? = null,
    val observedAtEpochMs: Long? = null,
    val unavailableReason: String? = null,
) {
    fun toPayload(): Map<String, Any?> = mapOf(
        "connected" to connected,
        "batteryPercent" to batteryPercent,
        "power" to power,
        "connection" to connection,
        "aiModelStatus" to aiModelStatus,
        "observedAtEpochMs" to observedAtEpochMs,
        "unavailableReason" to unavailableReason,
    )

    companion object {
        const val MAX_AGE_MS = 15_000L

        fun fromTelemetry(values: Map<*, *>, nowEpochMs: Long): JackeryDeviceStatus {
            fun number(name: String): Double? = (values[name] as? Number)?.toDouble()?.takeIf { it.isFinite() }
            if (number("schemaVersion") != 1.0) return JackeryDeviceStatus(unavailableReason = "invalid_response")
            val observed = number("observedAtEpochMs")?.takeIf { it % 1.0 == 0.0 }?.toLong()
                ?: return JackeryDeviceStatus(unavailableReason = "timestamp_unavailable")
            if (observed <= 0L || observed > nowEpochMs + 5_000L || nowEpochMs - observed > MAX_AGE_MS) {
                return JackeryDeviceStatus(unavailableReason = "stale")
            }
            if (values["connected"] != true) return JackeryDeviceStatus(unavailableReason = "not_connected")
            val battery = number("batteryPercent")?.takeIf { it in 0.0..100.0 && it % 1.0 == 0.0 }?.toInt()
            fun enum(name: String, allowed: Set<String>): String? =
                (values[name] as? String)?.lowercase()?.takeIf { it in allowed }
            return JackeryDeviceStatus(
                connected = true,
                batteryPercent = battery,
                power = enum("power", setOf("battery", "external", "charging", "off")),
                connection = enum("connection", setOf("wifi", "usb", "ethernet", "bluetooth")),
                aiModelStatus = enum("aiModelStatus", setOf("ready", "loading", "unavailable", "offline")),
                observedAtEpochMs = observed,
            )
        }
    }
}
