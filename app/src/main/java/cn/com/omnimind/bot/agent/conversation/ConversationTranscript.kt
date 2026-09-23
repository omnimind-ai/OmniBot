package cn.com.omnimind.bot.agent

import com.google.gson.GsonBuilder

/** Export formats consume the native history directly; Flutter never hydrates an entire chat to copy it. */
internal object ConversationTranscript {
    private val json = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

    fun clipboard(messages: List<Map<String, Any?>>): String = messages.asReversed().mapNotNull { message ->
        if ((message["type"] as? Number)?.toInt() != 1) return@mapNotNull null
        val text = (message["content"] as? Map<*, *>)?.get("text")?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return@mapNotNull null
        val role = if ((message["user"] as? Number)?.toInt() == 1) "用户" else "助手"
        "$role：\n$text"
    }.joinToString("\n\n")

    fun export(conversationId: Long, mode: String, messages: List<Map<String, Any?>>): String =
        json.toJson(linkedMapOf("conversationId" to conversationId, "mode" to mode, "messages" to messages))
}
