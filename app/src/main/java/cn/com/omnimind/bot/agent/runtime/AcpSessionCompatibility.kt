package cn.com.omnimind.bot.agent.runtime

import java.nio.charset.StandardCharsets
import java.util.Base64

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
            sequenceOf("threadId", "thread_id", "session_id")
                .asSequence()
                .mapNotNull { result.stringValue(it) }
                .firstOrNull()
                ?.let {
                    result["sessionId"] = it
                }
        }
        if (result.stringValue("promptId").isNullOrBlank()) {
            sequenceOf("turnId", "turn_id", "taskId", "task_id", "runId", "run_id")
                .asSequence()
                .mapNotNull { result.stringValue(it) }
                .firstOrNull()
                ?.let {
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

internal data class AcpPage<T>(
    val items: List<T>,
    val nextCursor: String?,
)

/**
 * Paginates the app bridge's compatibility response without inventing a
 * second session store. The official ACP SDK paginates the raw Agent request;
 * this helper covers the Flutter/native facade, which materializes that
 * sequence into a Map response. The cursor carries a snapshot fingerprint so
 * a changing list cannot silently skip or duplicate sessions between pages.
 */
internal fun <T> paginateAcpItems(
    items: List<T>,
    limit: Int?,
    cursor: String?,
    identity: (T) -> String,
): AcpPage<T> {
    require(limit == null || limit > 0) { "ACP session list limit must be positive." }
    val fingerprint = items.joinToString("\u001f", transform = identity)
        .toByteArray(StandardCharsets.UTF_8)
        .let { bytes ->
            bytes.fold(17L) { hash, byte ->
                (hash * 31L + byte.toInt()) and 0x7fff_ffff_ffff_ffffL
            }.toString(16)
        }
    val offset = if (cursor.isNullOrBlank()) {
        0
    } else {
        val decoded = runCatching {
            String(
                Base64.getUrlDecoder().decode(cursor),
                StandardCharsets.UTF_8,
            )
        }.getOrNull()
        val parts = decoded?.split('|')
        require(parts?.size == 3 && parts[0] == "acp-list-v1") {
            "Invalid ACP session list cursor."
        }
        require(parts[1] == fingerprint) {
            "ACP session list changed; restart pagination from the first page."
        }
        parts[2].toIntOrNull()?.also {
            require(it in 0..items.size) { "Invalid ACP session list cursor." }
        } ?: throw IllegalArgumentException("Invalid ACP session list cursor.")
    }
    val page = items.drop(offset).let { remaining ->
        limit?.let(remaining::take) ?: remaining
    }
    val nextOffset = offset + page.size
    val nextCursor = if (limit != null && nextOffset < items.size) {
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            "acp-list-v1|$fingerprint|$nextOffset".toByteArray(StandardCharsets.UTF_8),
        )
    } else {
        null
    }
    return AcpPage(page, nextCursor)
}
