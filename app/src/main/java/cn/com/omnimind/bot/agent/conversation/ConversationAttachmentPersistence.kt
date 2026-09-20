package cn.com.omnimind.bot.agent

/** Preserve durable attachment identities when a UI snapshot still has picker paths. */
internal fun persistConversationAttachments(
    incoming: List<Map<String, Any?>>,
    existing: List<Map<String, Any?>>,
    materialize: (List<Map<String, Any?>>) -> List<Map<String, Any?>>,
): List<Map<String, Any?>> {
    val byId = existing.mapNotNull { attachment ->
        attachment["id"]?.toString()?.takeIf { it.isNotBlank() }?.let { it to attachment }
    }.toMap()
    return incoming.map { attachment ->
        val prior = attachment["id"]?.toString()?.let(byId::get)
        val durable = prior?.takeIf {
            !it["workspacePath"]?.toString().isNullOrBlank() &&
                !it["path"]?.toString().isNullOrBlank()
        }
        if (durable != null) {
            // The attachment id, not its name or array position, owns its bytes.
            LinkedHashMap(attachment).apply {
                for (key in listOf("path", "workspacePath", "promptPath", "mimeType", "size")) {
                    durable[key]?.let { put(key, it) }
                }
            }
        } else {
            materialize(listOf(attachment)).single()
        }
    }
}
