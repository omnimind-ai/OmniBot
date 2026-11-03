package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NaRunStreamTest {
    private fun item(text: String) = mapOf("id" to "reply", "type" to "agent_message", "text" to text)
    private fun snapshot(text: String, cursor: Long, status: String = "running") = mapOf(
        "eventCursor" to cursor, "run" to mapOf("id" to "run", "status" to status, "items" to listOf(item(text))))
    private fun event(id: Long, text: String, runId: String = "run") = mapOf(
        "id" to id, "runId" to runId, "type" to "item.updated", "data" to mapOf("item" to item(text)))
    private fun done(id: Long, text: String, status: String = "completed") = mapOf(
        "id" to id, "runId" to "run", "type" to "run.$status",
        "data" to mapOf("run" to mapOf("id" to "run", "status" to status, "items" to listOf(item(text)))))

    @Test fun readsUtf8MultilineFramesAndIgnoresHeartbeats() {
        val source = Buffer().writeUtf8(": connected\r\n\r\nevent: ignored\ndata: {}\n\n" +
            "id: 7\nevent: na\ndata: {\"id\":7,\n" + "data: \"text\":\"你好🌍\"}\n\n")
        val seen = mutableListOf<Map<String, Any?>>()
        readNaEvents(source) { seen += it; false }
        assertEquals(1, seen.size)
        assertEquals("你好🌍", seen.single()["text"])
    }

    @Test fun truncatedFrameNeverProjectsPartialJson() {
        var count = 0
        assertThrows(IOException::class.java) {
            readNaEvents(Buffer().writeUtf8("event: na\ndata: {\"id\":1")) { count++; true }
        }
        assertEquals(0, count)
    }

    @Test fun liveStreamProjectsOnlyItsOwnRunWithoutPollingOrDuplicateReplay() = runBlocking {
        var reads = 0
        val projection = NaItemProjection("turn")
        val text = StringBuilder()
        val result = awaitNaRun("run", snapshot = { reads++; snapshot("a", 10) },
            subscribe = { cursor, receive ->
                assertEquals(10L, cursor)
                assertTrue(receive(event(10, "old")))
                assertTrue(receive(event(11, "unrelated", "other-run")))
                assertTrue(receive(event(12, "ab")))
                assertTrue(receive(event(12, "ab")))
                assertFalse(receive(done(13, "abc")))
            }, checkOwner = {}, onItem = { value ->
                projection.updates(value).forEach { text.append((it["content"] as Map<*, *>)["text"]) }
            })
        assertEquals(1, reads)
        assertEquals("abc", text.toString())
        assertEquals("completed", result["status"])
    }

    @Test fun disconnectRecoversSnapshotWithoutResubmittingAndPreservesIdentity() = runBlocking {
        var reads = 0
        var connections = 0
        val projection = NaItemProjection("same-turn")
        val text = StringBuilder()
        val result = awaitNaRun("run", snapshot = {
            reads++; if (reads == 1) snapshot("a", 1) else snapshot("abc", 4)
        }, subscribe = { cursor, receive ->
            connections++
            if (connections == 1) { receive(event(2, "ab")); throw IOException("lost connection") }
            assertEquals(4L, cursor)
            assertFalse(receive(done(5, "abcd", "cancelled")))
        }, checkOwner = {}, onItem = { value ->
            projection.updates(value).forEach { text.append((it["content"] as Map<*, *>)["text"]) }
        })
        assertEquals(2, reads)
        assertEquals("abcd", text.toString())
        assertEquals("cancelled", result["status"])
    }

    @Test fun persistentDisconnectIsBounded() = runBlocking {
        var subscriptions = 0
        try {
            awaitNaRun("run", snapshot = { snapshot("", 1) },
                subscribe = { _, _ -> subscriptions++; throw IOException("offline") },
                checkOwner = {}, onItem = {})
            fail("must surface the disconnection")
        } catch (_: IOException) { }
        assertEquals(3, subscriptions)
    }

    @Test fun changedAccountRejectsLateEventsBeforeProjection() = runBlocking {
        var currentOwner = true
        val seen = mutableListOf<String>()
        try {
            awaitNaRun("run", snapshot = { snapshot("a", 1) }, subscribe = { _, receive ->
                currentOwner = false
                receive(event(2, "for old account"))
            }, checkOwner = { if (!currentOwner) throw CancellationException("account changed") },
                onItem = { seen += it["text"].toString() })
            fail("must cancel")
        } catch (_: CancellationException) { }
        assertEquals(listOf("a"), seen)
    }
}
