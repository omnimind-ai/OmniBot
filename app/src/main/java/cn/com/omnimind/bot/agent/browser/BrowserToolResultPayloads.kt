package cn.com.omnimind.bot.agent.browser

/**
 * Model-visible payloads for browser tools.
 *
 * Browser artifacts are useful as user-downloadable copies, but must not be
 * used as a lossy substitute for the result returned to the current ACP
 * prompt. The model needs the same complete facts that the browser produced.
 */
internal object BrowserToolResultPayloads {
    fun text(
        text: String,
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> = LinkedHashMap<String, Any?>(extra).apply {
        put("text", text)
        put("textLength", text.length)
    }

    fun collection(
        selectorUsed: String?,
        items: List<String>
    ): Map<String, Any?> = linkedMapOf(
        "selectorUsed" to selectorUsed,
        "itemCount" to items.size,
        "items" to items
    )
}
