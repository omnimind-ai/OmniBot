package cn.com.omnimind.bot.agent.workspace.memory

import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import java.security.MessageDigest
import java.util.Locale

data class LongTermMemoryEntry(
    val slug: String,
    val title: String,
    val body: String,
    val updatedAt: Long,
    val tokens: Int
)

/**
 * In-memory index over the existing `.omnibot/memory/MEMORY.md` file.
 *
 * The physical layout is unchanged: each line `- <text>` in `MEMORY.md`
 * is one long-term memory entry. Slug = first 8 chars of SHA-1(text)
 * prefixed by the first ~6 chars of the title — stable across rebuilds
 * as long as the text doesn't change.
 *
 * This avoids a disk migration while still giving the agent slug-addressable
 * access via `memory_load`.
 */
class LongTermMemoryIndex(
    private val workspaceManager: AgentWorkspaceManager
) {
    fun list(limit: Int? = null): List<LongTermMemoryEntry> {
        val file = runCatching { workspaceManager.longTermMemoryMarkdownFile() }
            .getOrNull() ?: return emptyList()
        if (!file.exists()) return emptyList()
        val text = file.readText()
        val updatedAt = file.lastModified()
        val entries = mutableListOf<LongTermMemoryEntry>()
        for (raw in text.lineSequence()) {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("- ")) continue
            val body = trimmed.removePrefix("- ").trim()
            if (body.isEmpty()) continue
            val slug = makeSlug(body)
            val title = body.take(80)
            entries.add(
                LongTermMemoryEntry(
                    slug = slug,
                    title = title,
                    body = body,
                    updatedAt = updatedAt,
                    tokens = estimateTokens(body)
                )
            )
            if (limit != null && entries.size >= limit) break
        }
        return entries
    }

    fun get(slug: String): LongTermMemoryEntry? {
        if (slug.isBlank()) return null
        return list().firstOrNull { it.slug == slug }
    }

    companion object {
        internal fun makeSlug(text: String): String {
            val normalized = text.trim()
            val digest = MessageDigest.getInstance("SHA-1")
                .digest(normalized.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }.take(8)
            val titlePart = normalized
                .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
                .trim('-')
                .lowercase(Locale.ROOT)
                .take(12)
                .ifBlank { "entry" }
            return "$titlePart-$hex"
        }

        private fun estimateTokens(text: String): Int {
            // Rough approximation: ~4 chars per token for mixed text.
            return (text.length + 3) / 4
        }
    }
}
