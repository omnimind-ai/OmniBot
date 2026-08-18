package cn.com.omnimind.bot.agent.runtime

/**
 * The only compatibility boundary for the old conversation vocabulary.
 *
 * ACP-facing application code speaks in terms of sessions and prompts. Older
 * app versions and the remote Codex app-server still send the previous names,
 * so they are accepted here and translated once. Keeping this logic in one
 * place prevents legacy fields from leaking into new UI or agent code.
 */
internal object AcpSessionCompatibility {
    fun canonicalize(method: String, args: Map<String, Any?>): Map<String, Any?> {
        if (!method.startsWith("session/") && method != "config/set" && method != "review/start") {
            return args
        }
        val result = LinkedHashMap(args)
        if (result.stringValue("sessionId").isNullOrBlank()) {
            result.stringValue("threadId")?.takeIf { it.isNotBlank() }?.let {
                result["sessionId"] = it
            }
        }
        if (result.stringValue("promptId").isNullOrBlank()) {
            result.stringValue("turnId")?.takeIf { it.isNotBlank() }?.let {
                result["promptId"] = it
            }
        }
        return result
    }

    /** Adds old keys only to compatibility responses consumed by old clients. */
    fun withLegacyIds(payload: Map<String, Any?>): Map<String, Any?> {
        val result = LinkedHashMap(payload)
        result.stringValue("sessionId")?.takeIf { it.isNotBlank() }?.let {
            result.putIfAbsent("threadId", it)
        }
        result.stringValue("promptId")?.takeIf { it.isNotBlank() }?.let {
            result.putIfAbsent("turnId", it)
        }
        return result
    }

    private fun Map<String, Any?>.stringValue(key: String): String? =
        this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
