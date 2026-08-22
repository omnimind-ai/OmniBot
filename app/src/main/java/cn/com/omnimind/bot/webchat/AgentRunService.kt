package cn.com.omnimind.bot.webchat

import android.content.Context
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class NormalizedAgentRunPayload(
    val userMessage: String,
    val attachments: List<Map<String, Any?>>
)

private data class WebAgentRunContext(
    val conversationId: Long,
    val conversationMode: String
)

private val WEB_CONVERSATION_MODES = setOf(
    "normal", "agent", "codex", "acp", "coding", "chat_only"
)

internal enum class WebConversationRunKind {
    OMNIAI,
    AGENT,
    CHAT_ONLY
}

internal fun resolveWebConversationMode(
    storedMode: String?,
    requestedMode: String?
): String {
    val normalizedStored = storedMode?.trim()?.lowercase().orEmpty()
    if (normalizedStored in WEB_CONVERSATION_MODES) {
        return if (normalizedStored in setOf("normal", "codex", "acp", "coding")) {
            "agent"
        } else {
            normalizedStored
        }
    }
    val normalizedRequested = requestedMode?.trim()?.lowercase().orEmpty()
    return normalizedRequested
        .takeIf(WEB_CONVERSATION_MODES::contains)
        ?.let { if (it in setOf("normal", "codex", "acp", "coding")) "agent" else it }
        ?: "agent"
}

internal fun resolveWebConversationRunKind(mode: String?): WebConversationRunKind {
    return when (mode?.trim()?.lowercase()) {
        "normal", "agent", "codex", "acp", "coding" -> WebConversationRunKind.AGENT
        "chat_only" -> WebConversationRunKind.CHAT_ONLY
        else -> WebConversationRunKind.OMNIAI
    }
}

internal fun resolveWebAgentId(
    storedAgentId: String?,
    requestedAgentId: String?,
    conversationMode: String? = null,
): String? {
    val stored = storedAgentId?.trim()?.takeIf { it.isNotEmpty() }
    val requested = requestedAgentId?.trim()?.takeIf { it.isNotEmpty() }
    if (conversationMode?.trim()?.equals("normal", ignoreCase = true) == true) {
        require(requested == null || requested == AcpAgentProfileStore.XIAOWAN_AGENT_ID) {
            "Xiaowan conversations cannot switch Harness; create a new conversation."
        }
        return AcpAgentProfileStore.XIAOWAN_AGENT_ID
    }
    require(stored == null || requested == null || stored == requested) {
        "The requested Agent does not match this conversation."
    }
    return stored ?: requested
}

internal object AgentRunRequestNormalizer {
    fun normalize(request: Map<String, Any?>): NormalizedAgentRunPayload {
        val explicitUserMessage = request["userMessage"]?.toString().orEmpty()
        val explicitAttachments = normalizeListOfMaps(request["attachments"])
        if (explicitUserMessage.isNotBlank() || explicitAttachments.isNotEmpty()) {
            return NormalizedAgentRunPayload(
                userMessage = explicitUserMessage,
                attachments = explicitAttachments
            )
        }

        val directContent = normalizeContentBlocks(request["content"])
        if (directContent != null) {
            return directContent
        }

        val messages = request["messages"] as? List<*> ?: emptyList<Any?>()
        for (index in messages.indices.reversed()) {
            val message = normalizeMap(messages[index]) ?: continue
            val role = message["role"]?.toString()?.trim()?.lowercase().orEmpty()
            if (role != "user") continue
            val content = message["content"]
            if (content is String) {
                return NormalizedAgentRunPayload(
                    userMessage = content,
                    attachments = emptyList()
                )
            }
            normalizeContentBlocks(content)?.let { return it }
        }

        return NormalizedAgentRunPayload(
            userMessage = "",
            attachments = emptyList()
        )
    }

    private fun normalizeContentBlocks(raw: Any?): NormalizedAgentRunPayload? {
        val blocks = raw as? List<*> ?: return null
        val texts = mutableListOf<String>()
        val attachments = mutableListOf<Map<String, Any?>>()
        blocks.forEachIndexed { index, item ->
            val block = normalizeMap(item) ?: return@forEachIndexed
            val type = inferBlockType(block)
            when (type) {
                "text", "input_text" -> {
                    val text = block["text"]?.toString().orEmpty()
                    if (text.isNotBlank()) {
                        texts += text
                    }
                }

                "image_url", "input_image", "image" -> {
                    val imageUrl = extractImageUrl(block)
                    if (imageUrl.isBlank()) {
                        return@forEachIndexed
                    }
                    val attachment = linkedMapOf<String, Any?>(
                        "isImage" to true
                    )
                    val fileName = block["fileName"]?.toString()?.trim().orEmpty()
                    if (fileName.isNotBlank()) {
                        attachment["fileName"] = fileName
                        attachment["name"] = fileName
                    } else {
                        attachment["fileName"] = "image_$index"
                        attachment["name"] = "image_$index"
                    }
                    val mimeType = extractMimeType(imageUrl, block["mimeType"]?.toString())
                    if (mimeType.isNotBlank()) {
                        attachment["mimeType"] = mimeType
                    }
                    if (imageUrl.startsWith("data:", ignoreCase = true)) {
                        attachment["dataUrl"] = imageUrl
                    } else {
                        attachment["url"] = imageUrl
                    }
                    attachments += attachment
                }

                "file", "attachment", "input_file" -> {
                    val attachment = extractAttachment(block, index)
                    if (attachment != null) {
                        attachments += attachment
                    }
                }
            }
        }
        return NormalizedAgentRunPayload(
            userMessage = texts.joinToString("\n").trim(),
            attachments = attachments
        )
    }

    private fun inferBlockType(block: Map<String, Any?>): String {
        val explicit = block["type"]?.toString()?.trim()?.lowercase().orEmpty()
        if (explicit.isNotEmpty()) {
            return explicit
        }
        if (block.containsKey("image_url") || block.containsKey("imageUrl")) {
            return "image_url"
        }
        if (block.containsKey("text")) {
            return "text"
        }
        if (block.containsKey("file") ||
            block.containsKey("attachment") ||
            block.containsKey("input_file")
        ) {
            return "attachment"
        }
        val mimeType = block["mimeType"]?.toString()?.trim().orEmpty()
        if (mimeType.startsWith("image/", ignoreCase = true) &&
            (block.containsKey("url") || block.containsKey("dataUrl"))
        ) {
            return "image_url"
        }
        return if (block.containsKey("url") ||
            block.containsKey("path") ||
            block.containsKey("filePath") ||
            block.containsKey("promptPath") ||
            block.containsKey("workspacePath") ||
            block.containsKey("fileName") ||
            block.containsKey("name")
        ) {
            "attachment"
        } else {
            ""
        }
    }

    private fun extractImageUrl(block: Map<String, Any?>): String {
        val imageUrlField = block["image_url"]
        val nested = when (imageUrlField) {
            is Map<*, *> -> imageUrlField["url"]?.toString()
            else -> imageUrlField?.toString()
        }
        return sequenceOf(
            nested,
            block["url"]?.toString(),
            block["imageUrl"]?.toString()
        ).map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun extractMimeType(imageUrl: String, explicit: String?): String {
        val normalizedExplicit = explicit?.trim().orEmpty()
        if (normalizedExplicit.isNotBlank()) {
            return normalizedExplicit
        }
        if (imageUrl.startsWith("data:", ignoreCase = true)) {
            return imageUrl
                .substringAfter("data:", "")
                .substringBefore(';')
                .trim()
        }
        return ""
    }

    private fun extractAttachment(
        block: Map<String, Any?>,
        index: Int
    ): Map<String, Any?>? {
        val nested = sequenceOf(
            block["attachment"],
            block["file"],
            block["input_file"]
        ).mapNotNull(::normalizeMap).firstOrNull()

        fun readField(key: String): Any? = nested?.get(key) ?: block[key]

        val attachment = linkedMapOf<String, Any?>()
        val name = readField("name")?.toString()?.trim().orEmpty()
        val fileName = readField("fileName")?.toString()?.trim().orEmpty()
        val resolvedName = fileName.ifEmpty { name }
        if (resolvedName.isNotEmpty()) {
            attachment["name"] = resolvedName
            attachment["fileName"] = resolvedName
        } else {
            attachment["fileName"] = "attachment_$index"
            attachment["name"] = "attachment_$index"
        }

        val mimeType = readField("mimeType")?.toString()?.trim().orEmpty()
        if (mimeType.isNotEmpty()) {
            attachment["mimeType"] = mimeType
        }

        copyIfNotBlank(attachment, "id", readField("id")?.toString())
        copyIfNotBlank(attachment, "path", firstNonBlank(readField("path"), readField("filePath")))
        copyIfNotBlank(attachment, "promptPath", readField("promptPath")?.toString())
        copyIfNotBlank(attachment, "workspacePath", readField("workspacePath")?.toString())
        copyIfNotBlank(attachment, "url", readField("url")?.toString())
        copyIfNotBlank(attachment, "dataUrl", readField("dataUrl")?.toString())

        when (val raw = readField("size") ?: readField("sizeBytes")) {
            is Number -> attachment["size"] = raw.toLong()
            is String -> raw.trim().toLongOrNull()?.let { attachment["size"] = it }
        }

        val explicitImage = when (val raw = readField("isImage")) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> false
        }
        val looksLikeImage = explicitImage ||
            mimeType.startsWith("image/", ignoreCase = true) ||
            attachment["dataUrl"]?.toString()?.startsWith("data:image/", ignoreCase = true) == true ||
            firstNonBlank(attachment["path"], attachment["url"])
                ?.let(::looksLikeImagePath) == true
        attachment["isImage"] = looksLikeImage

        when (val raw = readField("sendToModel")) {
            is Boolean -> if (!raw) attachment["sendToModel"] = false
            is String -> if (raw.equals("false", ignoreCase = true)) {
                attachment["sendToModel"] = false
            }
        }

        return if (
            attachment["path"] != null ||
            attachment["url"] != null ||
            attachment["dataUrl"] != null ||
            attachment["promptPath"] != null ||
            attachment["workspacePath"] != null
        ) {
            attachment
        } else {
            null
        }
    }

    private fun firstNonBlank(vararg values: Any?): String? {
        return values.firstNotNullOfOrNull { value ->
            value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun copyIfNotBlank(
        target: MutableMap<String, Any?>,
        key: String,
        value: String?
    ) {
        val normalized = value?.trim().orEmpty()
        if (normalized.isNotEmpty()) {
            target[key] = normalized
        }
    }

    private fun looksLikeImagePath(value: String): Boolean {
        val normalized = value.trim().lowercase().split('?').firstOrNull().orEmpty()
        return normalized.endsWith(".png") ||
            normalized.endsWith(".jpg") ||
            normalized.endsWith(".jpeg") ||
            normalized.endsWith(".webp") ||
            normalized.endsWith(".gif") ||
            normalized.endsWith(".bmp") ||
            normalized.endsWith(".heic") ||
            normalized.endsWith(".heif")
    }

    internal fun normalizeMap(value: Any?): Map<String, Any?>? {
        return (value as? Map<*, *>)?.entries?.associate { entry ->
            entry.key.toString() to normalizeValue(entry.value)
        }
    }

    internal fun normalizeListOfMaps(value: Any?): List<Map<String, Any?>> {
        return (value as? List<*>)?.mapNotNull { entry ->
            normalizeMap(entry)
        } ?: emptyList()
    }

    private fun normalizeValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> normalizeMap(value)
            is List<*> -> value.map { normalizeValue(it) }
            else -> value
        }
    }
}

class AgentRunService(
    private val context: Context
) {
    private val runContexts = ConcurrentHashMap<String, WebAgentRunContext>()
    private val conversationService by lazy {
        ConversationDomainService(context.applicationContext)
    }
    private val agentRunBridge by lazy {
        WebAgentRunBridge(
            context = context.applicationContext,
            manager = AgentRuntimeManager.getInstance(context)
        )
    }

    fun hasActiveConversationRun(
        conversationId: Long,
        conversationMode: String
    ): Boolean {
        return when (resolveWebConversationRunKind(conversationMode)) {
            WebConversationRunKind.OMNIAI ->
                agentRunBridge.hasActiveRun(conversationId)
            WebConversationRunKind.CHAT_ONLY ->
                agentRunBridge.hasActiveRun(conversationId)
            WebConversationRunKind.AGENT -> agentRunBridge.hasActiveRun(conversationId)
        }
    }

    suspend fun startConversationRun(
        conversationId: Long,
        request: Map<String, Any?>
    ): Map<String, Any?> {
        val taskId = request["taskId"]?.toString()?.trim()?.ifEmpty { null }
            ?: UUID.randomUUID().toString()
        val normalizedPayload = AgentRunRequestNormalizer.normalize(request)
        val storedConversation = conversationService.getConversationPayload(conversationId)
            ?: throw IllegalArgumentException("Conversation not found")
        val conversationMode = resolveWebConversationMode(
            storedMode = storedConversation["mode"]?.toString(),
            requestedMode = request["conversationMode"]?.toString()
        )
        val runKind = resolveWebConversationRunKind(conversationMode)
        val agentId = if (runKind == WebConversationRunKind.AGENT) {
            resolveWebAgentId(
                storedAgentId = storedConversation["agentId"]?.toString(),
                requestedAgentId = request["agentId"]?.toString(),
                conversationMode = conversationMode,
            )
        } else {
            null
        }
        when (runKind) {
            WebConversationRunKind.OMNIAI -> if (agentRunBridge.hasActiveRun(conversationId)) {
                throw IllegalStateException("设备当前已有运行中的 Agent 任务，请稍后重试")
            }
            WebConversationRunKind.CHAT_ONLY -> if (agentRunBridge.hasActiveRun(conversationId)) {
                throw IllegalStateException("设备当前已有运行中的纯聊天任务，请稍后重试")
            }
            WebConversationRunKind.AGENT -> if (agentRunBridge.hasActiveRun(conversationId)) {
                throw IllegalStateException("该 Agent 会话已有运行中的任务")
            }
        }
        val updatedConversation = try {
            conversationService.applyFirstUserMessageTitle(
                conversationId = conversationId,
                firstUserMessage = normalizedPayload.userMessage
            )
        } catch (_: Exception) {
            storedConversation
        }
        val runtimeResult = when (runKind) {
            WebConversationRunKind.OMNIAI -> {
                agentRunBridge.startRun(
                    taskId = taskId,
                    conversationId = conversationId,
                    conversationMode = conversationMode,
                    userMessage = normalizedPayload.userMessage,
                    attachments = normalizedPayload.attachments,
                    cwd = (
                        storedConversation["agentCwd"]
                            ?: storedConversation["codexCwd"]
                        )?.toString(),
                    userMessageCreatedAt = (request["userMessageCreatedAt"] as? Number)?.toLong()
                )
            }
            WebConversationRunKind.CHAT_ONLY -> {
                val modelOverride = AgentRunRequestNormalizer.normalizeMap(
                    request["modelOverride"]
                )
                agentRunBridge.startRun(
                    taskId = taskId,
                    conversationId = conversationId,
                    conversationMode = conversationMode,
                    userMessage = normalizedPayload.userMessage,
                    attachments = normalizedPayload.attachments,
                    cwd = (
                        storedConversation["agentCwd"]
                            ?: storedConversation["codexCwd"]
                    )?.toString(),
                    model = modelOverride?.get("modelId")?.toString(),
                    effort = request["reasoningEffort"]?.toString(),
                    userMessageCreatedAt = (request["userMessageCreatedAt"] as? Number)?.toLong()
                )
            }
            WebConversationRunKind.AGENT -> agentRunBridge.startRun(
                taskId = taskId,
                conversationId = conversationId,
                conversationMode = conversationMode,
                userMessage = normalizedPayload.userMessage,
                attachments = normalizedPayload.attachments,
                cwd = (
                    storedConversation["agentCwd"]
                        ?: storedConversation["codexCwd"]
                    )?.toString(),
                agentId = agentId,
                userMessageCreatedAt = (request["userMessageCreatedAt"] as? Number)?.toLong()
            )
        }
        runContexts[taskId] = WebAgentRunContext(
            conversationId = conversationId,
            conversationMode = conversationMode
        )
        return mapOf(
            "taskId" to taskId,
            "status" to "accepted",
            "conversationMode" to conversationMode,
            "conversation" to updatedConversation
        ) + runtimeResult
    }

    suspend fun cancelTask(taskId: String?): Map<String, Any?> {
        val normalizedTaskId = taskId?.trim().takeUnless { it.isNullOrEmpty() }
        val runContext = normalizedTaskId?.let(runContexts::get)
        when (resolveWebConversationRunKind(runContext?.conversationMode)) {
            WebConversationRunKind.OMNIAI -> {
                if (normalizedTaskId != null) {
                    agentRunBridge.cancelRun(normalizedTaskId)
                }
            }
            WebConversationRunKind.CHAT_ONLY -> {
                if (normalizedTaskId != null) {
                    agentRunBridge.cancelRun(normalizedTaskId)
                }
            }
            WebConversationRunKind.AGENT -> {
                if (normalizedTaskId != null) {
                    agentRunBridge.cancelRun(normalizedTaskId)
                }
            }
        }
        normalizedTaskId?.let(runContexts::remove)
        return mapOf(
            "taskId" to normalizedTaskId,
            "status" to "cancelled"
        )
    }

    suspend fun clarifyTask(taskId: String?, reply: String): Map<String, Any?> {
        val normalizedTaskId = taskId?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalArgumentException("taskId is required")
        val runContext = runContexts.remove(normalizedTaskId)
            ?: throw IllegalStateException("Agent run context is no longer available")
        val accepted = startConversationRun(
            conversationId = runContext.conversationId,
            request = mapOf(
                "userMessage" to reply,
                "conversationMode" to runContext.conversationMode
            )
        )
        return accepted + ("previousTaskId" to normalizedTaskId)
    }

}
