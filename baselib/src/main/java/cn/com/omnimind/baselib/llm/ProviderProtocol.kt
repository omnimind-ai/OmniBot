package cn.com.omnimind.baselib.llm

/** Model wire protocols; independent of the ACP session lifecycle. */
object ProviderProtocol {
    const val OPENAI = "openai_compatible"
    const val ANTHROPIC = "anthropic"
    const val GEMINI = "gemini_interactions"

    fun normalize(value: String?): String = when (value?.trim()?.lowercase()) {
        DeepSeekProvider.PROTOCOL_TYPE -> DeepSeekProvider.PROTOCOL_TYPE
        ANTHROPIC -> ANTHROPIC
        GEMINI -> GEMINI
        else -> OPENAI
    }
}
