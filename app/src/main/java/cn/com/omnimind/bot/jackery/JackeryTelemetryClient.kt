package cn.com.omnimind.bot.jackery

import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal class JackeryTelemetryClient {
    fun read(statusUrl: String): JackeryDeviceStatus {
        if (statusUrl.isBlank()) return JackeryDeviceStatus(unavailableReason = "not_configured")
        val connection = runCatching {
            val url = URL(statusUrl)
            require(url.protocol in setOf("http", "https") && url.userInfo == null && url.ref == null)
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3_000
                readTimeout = 3_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
            }
        }.getOrElse { return JackeryDeviceStatus(unavailableReason = "invalid_endpoint") }
        return try {
            if (connection.responseCode != 200) return JackeryDeviceStatus(unavailableReason = "connection_failed")
            // Bounded response: the status contract is a small JSON object.
            val body = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 65_536)
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            val values = Gson().fromJson(body, Map::class.java)
                ?: return JackeryDeviceStatus(unavailableReason = "invalid_response")
            JackeryDeviceStatus.fromTelemetry(values, System.currentTimeMillis())
        } catch (_: Exception) {
            JackeryDeviceStatus(unavailableReason = "connection_failed")
        } finally {
            connection.disconnect()
        }
    }
}
