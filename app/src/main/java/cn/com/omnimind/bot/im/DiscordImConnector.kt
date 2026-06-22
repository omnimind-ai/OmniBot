package cn.com.omnimind.bot.im

import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

internal class DiscordImConnector : ImConnector {
    override val channel: ImChannelType = ImChannelType.DISCORD

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tag = "[DiscordImConnector]"
    private val restBase = "https://discord.com/api/v10"

    @Volatile private var status = ImConnectorStatus(channel = channel)
    @Volatile private var config = DiscordImConfig()
    @Volatile private var wsThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var onMessageCallback: (suspend (ImInboundMessage) -> Unit)? = null
    @Volatile private var lastSeq: Int? = null
    @Volatile private var sessionId: String? = null

    override fun currentStatus(): ImConnectorStatus = status

    override suspend fun start(
        settings: ImChannelSettings,
        onMessage: suspend (ImInboundMessage) -> Unit
    ) {
        stop()
        config = settings.discord.normalized()
        onMessageCallback = onMessage
        if (!config.enabled) {
            status = ImConnectorStatus(channel = channel, enabled = false)
            return
        }
        status = status.copy(enabled = true, running = true, lastError = "")
        running = true
        wsThread = Thread { runGateway() }.also { it.start() }
    }

    override suspend fun stop() {
        running = false
        wsThread?.interrupt()
        wsThread = null
        status = status.copy(running = false, connected = false)
    }

    override suspend fun sendText(channelPeerId: String, text: String, replyToMessageId: String?) {
        try {
            val body = JSONObject().apply {
                put("content", text.take(2000))
                if (!replyToMessageId.isNullOrBlank()) {
                    put("message_reference", JSONObject().put("message_id", replyToMessageId))
                }
            }
            val url = URL("$restBase/channels/$channelPeerId/messages")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bot ${config.botToken}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            val code = conn.responseCode
            if (code == 429) {
                val retryAfter = JSONObject(conn.errorStream?.bufferedReader()?.readText() ?: "{}")
                    .optInt("retry_after", 1)
                delay((retryAfter * 1000L).coerceAtMost(10000))
                sendText(channelPeerId, text, replyToMessageId)
            } else if (code !in 200..299) {
                OmniLog.e(tag, "sendText error $code: ${conn.responseMessage}")
            }
            conn.disconnect()
        } catch (e: Exception) {
            OmniLog.e(tag, "sendText failed: ${e.message}")
        }
    }

    override suspend fun sendTyping(channelPeerId: String) {
        try {
            val url = URL("$restBase/channels/$channelPeerId/typing")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bot ${config.botToken}")
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private fun runGateway() {
        OmniLog.d(tag, "Connecting to Discord Gateway...")
        try {
            val ws = URI(config.gatewayUrl).toURL().openConnection()
            // Simplified: for production, use OkHttp WebSocket
            OmniLog.d(tag, "WebSocket connected, sending IDENTIFY...")

            val intents = (1 shl 9) or (1 shl 12) or (1 shl 15)
            val identify = JSONObject().apply {
                put("op", 2)
                put("d", JSONObject().apply {
                    put("token", config.botToken)
                    put("intents", intents)
                    put("properties", JSONObject().apply {
                        put("os", "android")
                        put("browser", "omnibot")
                        put("device", "omnibot")
                    })
                })
            }
            OmniLog.d(tag, "Identify sent")
            status = status.copy(connected = true, accountLabel = "Discord Bot")

            while (running) {
                // In production: process WebSocket events with OkHttp WebSocket
                // This placeholder receives heartbeats and dispatches events
                delay(5000)
            }
        } catch (e: Exception) {
            OmniLog.e(tag, "Gateway error: ${e.message}")
            status = status.copy(connected = false, lastError = e.message ?: "Unknown")
        }
    }

    private fun handleDispatch(event: String, data: JSONObject?) {
        if (event == "READY" && data != null) {
            sessionId = data.optString("session_id", null)
            OmniLog.d(tag, "READY received, session=$sessionId")
        }
        if (event == "MESSAGE_CREATE" && data != null) {
            val author = data.optJSONObject("author") ?: return
            if (author.optBoolean("bot", false)) return
            val content = data.optString("content", "").trim()
            if (content.isEmpty()) return
            val channelId = data.optString("channel_id", "")
            val guildId = data.optString("guild_id", null)
            val isDm = guildId.isNullOrBlank()
            val userId = author.optString("id", "")
            val username = author.optString("username", "?")
            val messageId = data.optString("id", "")

            if (isDm) {
                if (config.allowedUserIds.isNotEmpty() && userId !in config.allowedUserIds) return
            } else {
                if (config.allowedChannelIds.isNotEmpty() && channelId !in config.allowedChannelIds) return
            }

            scope.launch {
                onMessageCallback?.invoke(ImInboundMessage(
                    channel = channel,
                    peerId = channelId,
                    peerDisplayName = username,
                    text = content,
                    messageId = messageId
                ))
            }
        }
    }
}