package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntrySlice

internal data class AgentHistoryDisplayPage(
    val entries: List<AgentHistoryDisplayEntry>,
    val hasMore: Boolean,
    val nextOffset: Int,
)

/** Paging bounds retained display data, as well as each database query. */
internal object AgentHistoryDisplayPager {
    const val MAX_PAGE_SIZE = 100
    const val PAGE_BYTES = 1024 * 1024
    private const val QUERY_SIZE = 16

    suspend fun load(
        limit: Int,
        offset: Int,
        read: suspend (limit: Int, offset: Int) -> List<AgentConversationEntrySlice>,
        project: suspend (AgentConversationEntrySlice) -> AgentHistoryDisplayEntry,
        byteBudget: Int = PAGE_BYTES,
    ): AgentHistoryDisplayPage {
        require(byteBudget > 0)
        val pageSize = limit.coerceIn(1, MAX_PAGE_SIZE)
        val start = offset.coerceAtLeast(0)
        val entries = mutableListOf<AgentHistoryDisplayEntry>()
        var bytes = 0L
        fun page(hasMore: Boolean) = AgentHistoryDisplayPage(entries, hasMore, start + entries.size)
        while (true) {
            // The extra row proves hasMore without hydrating an invisible item.
            val batchSize = minOf(QUERY_SIZE, pageSize - entries.size + 1)
            val slices = read(batchSize, start + entries.size)
            for (slice in slices) {
                if (entries.size == pageSize) return page(true)
                val projected = project(slice)
                val size = projected.entry.payloadJson.toByteArray(Charsets.UTF_8).size.toLong() +
                    projected.entry.summary.toByteArray(Charsets.UTF_8).size + 2048
                // A single item is independently bounded by its projection. Always
                // consume it to ensure a large record cannot stall the cursor.
                if (entries.isNotEmpty() && bytes + size > byteBudget) return page(true)
                entries += projected
                bytes += size
            }
            if (slices.size < batchSize) return page(false)
        }
    }
}
