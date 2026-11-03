package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntry
import cn.com.omnimind.baselib.database.AgentConversationEntrySlice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentHistoryDisplayPagerTest {
    private fun row(id: Long, payload: String = "{}") = AgentConversationEntrySlice(
        AgentConversationEntry(
            id = id, conversationId = 1, conversationMode = "agent", entryId = "item-$id",
            entryType = "assistant_message", status = "success", summary = "", payloadJson = payload,
            createdAt = id, updatedAt = id,
        ),
        summaryBytes = 0,
        payloadBytes = payload.toByteArray().size.toLong(),
    )

    @Test fun `byte limited pages advance by consumed rows and never skip an item`() = runBlocking {
        val rows = (1L..8).map { row(it, "中".repeat(1000)) }
        val loaded = mutableListOf<String>()
        var offset = 0
        do {
            val page = AgentHistoryDisplayPager.load(
                limit = 50, offset = offset, byteBudget = 11_000,
                read = { count, start -> rows.drop(start).take(count) },
                project = { AgentHistoryDisplayEntry(it.entry) },
            )
            assertEquals(2, page.entries.size)
            assertEquals(offset + 2, page.nextOffset)
            loaded += page.entries.map { it.entry.entryId }
            offset = page.nextOffset
        } while (page.hasMore)
        assertEquals(rows.map { it.entry.entryId }, loaded)
    }

    @Test fun `count lookahead does not read a potentially giant payload`() = runBlocking {
        val rows = (1L..51).map { row(it) }
        val hydrated = mutableListOf<Long>()
        val page = AgentHistoryDisplayPager.load(
            limit = 50, offset = 0,
            read = { count, start -> rows.drop(start).take(count) },
            project = {
                check(it.entry.id != 51L) { "Lookahead must not hydrate the next item" }
                hydrated += it.entry.id
                AgentHistoryDisplayEntry(it.entry)
            },
        )
        assertEquals(50, hydrated.size)
        assertEquals(50, page.nextOffset)
        assertTrue(page.hasMore)
    }

    @Test fun `bounded first item always progresses even above the page budget`() = runBlocking {
        val rows = listOf(row(1, "x".repeat(6000)), row(2))
        val first = AgentHistoryDisplayPager.load(50, 0,
            read = { count, start -> rows.drop(start).take(count) },
            project = { AgentHistoryDisplayEntry(it.entry) }, byteBudget = 4096)
        assertEquals(listOf("item-1"), first.entries.map { it.entry.entryId })
        assertTrue(first.hasMore)
        val last = AgentHistoryDisplayPager.load(50, first.nextOffset,
            read = { count, start -> rows.drop(start).take(count) },
            project = { AgentHistoryDisplayEntry(it.entry) }, byteBudget = 4096)
        assertEquals(listOf("item-2"), last.entries.map { it.entry.entryId })
        assertEquals(2, last.nextOffset)
        assertFalse(last.hasMore)
    }

    @Test fun `exact final page and empty page report exhaustion`() = runBlocking {
        val rows = (1L..16).map { row(it) }
        for (offset in listOf(0, 16)) {
            val page = AgentHistoryDisplayPager.load(16, offset,
                read = { count, start -> rows.drop(start).take(count) },
                project = { AgentHistoryDisplayEntry(it.entry) })
            assertFalse(page.hasMore)
            assertEquals(16, page.nextOffset)
        }
    }

    @Test fun `caller cannot request an unbounded retained page`() = runBlocking {
        var largestQuery = 0
        val page = AgentHistoryDisplayPager.load(Int.MAX_VALUE, 0,
            read = { count, start ->
                largestQuery = maxOf(largestQuery, count)
                (start until start + count).map { row(it.toLong()) }
            }, project = { AgentHistoryDisplayEntry(it.entry) })
        assertEquals(AgentHistoryDisplayPager.MAX_PAGE_SIZE, page.entries.size)
        assertTrue(page.hasMore)
        assertTrue(largestQuery <= 16)
    }
}
