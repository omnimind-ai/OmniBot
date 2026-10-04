package cn.com.omnimind.bot.webchat

import cn.com.omnimind.assists.controller.http.HttpController

/** Generates the short conversation title summary from user turns. */
object ConversationSummaryGenerator {
    /** Throws when the model fails or returns an empty summary. */
    suspend fun generate(conversationHistory: String): String {
        // 构建提示词，要求生成10字左右的摘要
        val prompt = """
            你是一个聊天总结助手，请根据以下用户发送的对话内容，生成一个简洁的摘要标题，要求：
            1. 摘要标题长度控制在10个字左右
            2. 摘要标题应该体现对话的主要内容
            3. 不要包含特殊字符和表情符号
            4. 不要包含任何的人称用词

            对话内容：
            $conversationHistory

            请直接返回摘要标题，不要包含其他内容。
        """.trimIndent()

        // 调用 LLM 生成摘要
        val llmResult = HttpController.postLLMRequest("scene.compactor.context.chat", prompt)
        return llmResult.message
            .trim()
            .take(10)
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Conversation summary is empty")
    }
}
