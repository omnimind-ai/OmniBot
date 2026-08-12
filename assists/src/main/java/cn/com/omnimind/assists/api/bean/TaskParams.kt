package cn.com.omnimind.assists.api.bean

import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener

sealed class TaskParams {
    data class OpenClawConfig(
        val baseUrl: String,
        val userId: String? = null,
        val sessionKey: String? = null,
        val generation: Long = -1L,
        val canonicalOrigin: String = "",
    )
    data class ChatModelOverride(
        val providerProfileId: String,
        val modelId: String,
        val apiBase: String,
        val apiKey: String,
        val customHeaders: Map<String, String> = emptyMap(),
        val protocolType: String = "openai_compatible",
        val wireApi: String = "chat_completions",
        val contextLimit: Int? = null
    )
    data class ChatTaskParams(
        val taskId: String,
        val content: List<Map<String, Any>>,
        val onMessagePush: OnMessagePushListener,
        val provider: String? = null,
        val openClawConfig: OpenClawConfig? = null,
        val modelOverride: ChatModelOverride? = null,
        val reasoningEffort: String? = null,
        val promptCacheKey: String? = null
    ) : TaskParams()
}
