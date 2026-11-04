package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NaNotificationPolicyTest {
    private fun notification(id: String = "n_one") = mapOf<String, Any?>("id" to id,
        "title" to "Na reminder", "body" to "A meaningful new update", "conversationId" to "cloud_one",
        "deliveredAt" to "2026-11-04T01:00:00Z", "readAt" to null)
    private fun payload(notifications: List<Any> = listOf(notification())) = mapOf<String, Any?>(
        "notifications" to notifications, "readIds" to listOf("n_read"), "nextCursor" to 12,
        "hasMore" to false, "clearAll" to false)

    @Test fun feedOnlyAcceptsDeliveredUnreadBoundedEntriesAndMonotonicSafeCursors() {
        val batch = parseNaNotificationBatch(payload(), 10)
        assertEquals("cloud_one", batch.notifications.single().conversationId)
        assertEquals(12L, batch.nextCursor)
        for (bad in listOf(notification() + ("readAt" to "2026-11-04T01:02:00Z"),
            notification() + ("deliveredAt" to null), notification() + ("deliveredAt" to "yesterday"),
            notification() + ("id" to "../other"), notification() + ("conversationId" to "other?token=x"),
            notification() + ("body" to "x".repeat(8001)))) {
            assertTrue(runCatching { parseNaNotificationBatch(payload(listOf(bad)), 10) }.isFailure)
        }
        for (cursor in listOf(-1, 11, 12.5, 9007199254740992.0, "13")) {
            assertTrue(runCatching { parseNaNotificationBatch(payload() + ("nextCursor" to cursor), 12) }.isFailure)
        }
        assertTrue(runCatching { parseNaNotificationBatch(payload(List(101) { notification("n_$it") }), 10) }.isFailure)
        assertTrue(runCatching { parseNaNotificationBatch(payload(listOf(notification(), notification())), 10) }.isFailure)
    }

    @Test fun permissionDenialCannotAcknowledgeOrPostAnyDelivery() = runBlocking {
        val events = mutableListOf<String>()
        val result = consumeNaNotificationBatch(parseNaNotificationBatch(payload(), 10), emptySet(), { false }, {},
            { events += "clear" }, { events += "cancel" }, { events += "post" },
            { events += "receipt" }, { events += "advance" })
        assertFalse(result)
        assertTrue(events.isEmpty())
    }

    @Test fun ownerChangeAfterPostCannotCommitAnOldResponseToNewAccount() = runBlocking {
        var current = true
        var posts = 0
        var receipts = 0
        var advances = 0
        assertTrue(runCatching {
            consumeNaNotificationBatch(parseNaNotificationBatch(payload(), 10), emptySet(), { true },
                { check(current) }, {}, {}, { posts++; current = false }, { receipts++ }, { advances++ })
        }.isFailure)
        assertEquals(1, posts)
        assertEquals(0, receipts)
        assertEquals(0, advances)
    }

    @Test fun durableReceiptPreventsRepostingWhenProcessStopsBeforeCursorCommit() = runBlocking {
        val receipts = mutableSetOf<String>()
        var posts = 0
        var cursor = 10L
        val batch = parseNaNotificationBatch(payload(), cursor)
        assertTrue(runCatching {
            consumeNaNotificationBatch(batch, receipts.toSet(), { true }, {}, {}, {}, { posts++ },
                { receipts += it }, { error("simulated process stop before final cursor write") })
        }.isFailure)
        assertEquals(setOf("n_one"), receipts)
        consumeNaNotificationBatch(batch, receipts.toSet(), { true }, {}, {}, {}, { posts++ },
            { receipts += it }, { cursor = it })
        assertEquals(1, posts)
        assertEquals(12L, cursor)
    }

    @Test fun failedPostLeavesNoReceiptOrCursorAndCanBeDeliveredLater() = runBlocking {
        var receipt = false
        var advanced = false
        assertTrue(runCatching {
            consumeNaNotificationBatch(parseNaNotificationBatch(payload(), 10), emptySet(), { true }, {}, {}, {},
                { throw SecurityException("permission changed") }, { receipt = true }, { advanced = true })
        }.isFailure)
        assertFalse(receipt)
        assertFalse(advanced)
    }

    @Test fun readCancellationPrecedesNewPostsAndProgressIsCommittedLast() = runBlocking {
        val events = mutableListOf<String>()
        val batch = parseNaNotificationBatch(payload() + ("clearAll" to true), 10)
        consumeNaNotificationBatch(batch, emptySet(), { true }, {}, { events += "clear" },
            { events += "read:$it" }, { events += "post:${it.id}" }, { events += "receipt:$it" }, { events += "cursor:$it" })
        assertEquals(listOf("clear", "read:n_read", "post:n_one", "receipt:n_one", "cursor:12"), events)
    }
}
