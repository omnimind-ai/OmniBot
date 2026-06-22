package cn.com.omnimind.bot.im

enum class ImChannelType(val id: String, val title: String) {
    TELEGRAM("telegram", "Telegram"),
    WECHAT("wechat", "WeChat"),
    DISCORD("discord", "Discord");

    companion object {
        fun fromId(id: String?): ImChannelType? {
            return entries.firstOrNull { it.id == id?.trim()?.lowercase() }
        }
    }
}

data class TelegramImConfig(
    val enabled: Boolean = false,
    val botToken: String = "",
    val apiBaseUrl: String = "https://api.telegram.org",
    val allowedChatIds: Set<String> = emptySet(),
    val chunkSize: Int = 3900,
    val dropPendingUpdates: Boolean = false
) {
    fun normalized(): TelegramImConfig {
        return copy(
            botToken = botToken.trim(),
            apiBaseUrl = apiBaseUrl.trim().ifEmpty { "https://api.telegram.org" },
            allowedChatIds = allowedChatIds.map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet(),
            chunkSize = chunkSize.coerceIn(500, 3900)
        )
    }
    fun toMap(): Map<String, Any?> {
        val config = normalized()
        return linkedMapOf(
            "enabled" to config.enabled,
            "botToken" to config.botToken,
            "apiBaseUrl" to config.apiBaseUrl,
            "allowedChatIds" to config.allowedChatIds.joinToString("\n"),
            "chunkSize" to config.chunkSize,
            "dropPendingUpdates" to config.dropPendingUpdates
        )
    }
}

data class WechatImConfig(
    val enabled: Boolean = false,
    val token: String = "",
    val baseUrl: String = "https://ilinkai.weixin.qq.com",
    val botType: String = "3",
    val version: String = "1.0.0",
    val chunkSize: Int = 3000
) {
    fun normalized(): WechatImConfig {
        return copy(
            token = token.trim(),
            baseUrl = baseUrl.trim().ifEmpty { "https://ilinkai.weixin.qq.com" },
            botType = botType.trim().ifEmpty { "3" },
            version = version.trim().ifEmpty { "1.0.0" },
            chunkSize = chunkSize.coerceIn(500, 8000)
        )
    }
    fun toMap(): Map<String, Any?> {
        val config = normalized()
        return linkedMapOf(
            "enabled" to config.enabled,
            "token" to config.token,
            "baseUrl" to config.baseUrl,
            "botType" to config.botType,
            "version" to config.version,
            "chunkSize" to config.chunkSize
        )
    }
}

data class DiscordImConfig(
    val enabled: Boolean = false,
    val botToken: String = "",
    val allowedUserIds: Set<String> = emptySet(),
    val allowedChannelIds: Set<String> = emptySet(),
    val gatewayUrl: String = "wss://gateway.discord.gg/?v=10&encoding=json",
    val chunkSize: Int = 3900
) {
    fun normalized(): DiscordImConfig {
        return copy(
            botToken = botToken.trim(),
            gatewayUrl = gatewayUrl.trim().ifEmpty { "wss://gateway.discord.gg/?v=10&encoding=json" },
            allowedUserIds = allowedUserIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            allowedChannelIds = allowedChannelIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            chunkSize = chunkSize.coerceIn(500, 3900)
        )
    }
    fun toMap(): Map<String, Any?> {
        val config = normalized()
        return linkedMapOf(
            "enabled" to config.enabled,
            "botToken" to config.botToken,
            "allowedUserIds" to config.allowedUserIds.joinToString("\n"),
            "allowedChannelIds" to config.allowedChannelIds.joinToString("\n"),
            "gatewayUrl" to config.gatewayUrl,
            "chunkSize" to config.chunkSize
        )
    }
}

data class ImChannelSettings(
    val telegram: TelegramImConfig = TelegramImConfig(),
    val wechat: WechatImConfig = WechatImConfig(),
    val discord: DiscordImConfig = DiscordImConfig()
) {
    fun anyEnabled(): Boolean = telegram.enabled || wechat.enabled || discord.enabled

    fun chunkSizeFor(channel: ImChannelType): Int {
        return when (channel) {
            ImChannelType.TELEGRAM -> telegram.normalized().chunkSize
            ImChannelType.WECHAT -> wechat.normalized().chunkSize
            ImChannelType.DISCORD -> discord.normalized().chunkSize
        }
    }

    fun toMap(): Map<String, Any?> {
        return linkedMapOf(
            "telegram" to telegram.toMap(),
            "wechat" to wechat.toMap(),
            "discord" to discord.toMap()
        )
    }
}

data class ImConnectorStatus(
    val channel: ImChannelType,
    val enabled: Boolean = false,
    val running: Boolean = false,
    val connected: Boolean = false,
    val accountLabel: String = "",
    val lastError: String = "",
    val sdkAvailable: Boolean? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> {
        return linkedMapOf(
            "channel" to channel.id,
            "enabled" to enabled,
            "running" to running,
            "connected" to connected,
            "accountLabel" to accountLabel,
            "lastError" to lastError,
            "sdkAvailable" to sdkAvailable,
            "updatedAt" to updatedAt
        )
    }
}

data class ImPeerSession(
    val channel: ImChannelType,
    val peerId: String,
    val displayName: String = "",
    val conversationId: Long = 0L,
    val mode: String = "normal",
    val activeTaskId: String? = null,
    val awaitingInput: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val key: String get() = "${channel.id}:$peerId"
    fun toMap(): Map<String, Any?> {
        return linkedMapOf(
            "channel" to channel.id,
            "peerId" to peerId,
            "displayName" to displayName,
            "conversationId" to conversationId,
            "mode" to mode,
            "activeTaskId" to activeTaskId,
            "awaitingInput" to awaitingInput,
            "updatedAt" to updatedAt
        )
    }
}

data class ImInboundMessage(
    val channel: ImChannelType,
    val peerId: String,
    val peerDisplayName: String = "",
    val text: String,
    val messageId: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class PendingImRun(
    val taskId: String,
    val channel: ImChannelType,
    val peerId: String,
    val conversationId: Long,
    val mode: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class ImProcessorResult(
    val replies: List<String> = emptyList(),
    val pendingRun: PendingImRun? = null,
    val finishedTaskId: String? = null
)

internal fun normalizeImConversationMode(rawMode: String?): String? {
    return when (rawMode?.trim()?.lowercase()) {
        null, "", "agent", "normal" -> "normal"
        "chat", "chat_only", "chat-only" -> "chat_only"
        "codex" -> "codex"
        else -> null
    }
}

internal fun imModeLabel(mode: String): String {
    return when (mode) {
        "chat_only" -> "chat"
        "codex" -> "codex"
        else -> "agent"
    }
}