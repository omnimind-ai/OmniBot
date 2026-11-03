package cn.com.omnimind.baselib.database

/** One deduplicated user-message timestamp; dashboard reads never load text. */
data class ConversationActivityRow(
    val id: Long,
    val mode: String,
    val createdAt: Long,
    val threadId: String?,
)

data class TokenUsageBucket(
    val model: String,
    val weekIndex: Int,
    val tokens: Long,
    val cachedTokens: Long,
)
