package cn.com.omnimind.bot.agent.runtime

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class NaItemProjectionTest {
    // A peer dropping a reused/read connection must recover, but a lost
    // submission response must never replay the admitted logical prompt.
    private fun droppingPeer(): Pair<ServerSocket, AtomicInteger> {
        val server = ServerSocket(0)
        val calls = AtomicInteger()
        Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val count = calls.incrementAndGet()
                        if (count > 1) {
                            val reader = socket.getInputStream().bufferedReader()
                            while (reader.readLine()?.isNotEmpty() == true) { }
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
                        }
                    }
                } catch (_: Exception) { }
            }
        }.apply { isDaemon = true; start() }
        return server to calls
    }
    @Test fun droppedReadConnectionRecoversWithoutAnotherLifecycle() = runBlocking {
        val (server, calls) = droppingPeer()
        try {
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            val response = naHttpResponse(client, Request.Builder().url("http://127.0.0.1:${server.localPort}").build()) { it.body!!.string() }
            assertEquals("OK", response)
            assertEquals(2, calls.get())
        } finally { server.close() }
    }
    @Test fun lostSubmissionResponseNeverReplaysPost() = runBlocking {
        val (server, calls) = droppingPeer()
        try {
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            try {
                naHttpResponse(client, Request.Builder().url("http://127.0.0.1:${server.localPort}").post("prompt".toRequestBody()).build()) { it.body!!.string() }
                fail("Lost POST response must surface an error")
            } catch (_: java.io.IOException) { }
            assertEquals(1, calls.get())
        } finally { server.close() }
    }
    @Test fun recoveredSnapshotUsesLivePromptAndItemIdentities() {
        val snapshot = naThreadSnapshot("na:instance:conversation", mapOf("conversation" to mapOf("title" to "Na"),
            "runs" to listOf(mapOf("id" to "server-run", "clientMessageId" to "host-prompt", "clientUserMessageId" to "original-user", "input" to "hello",
                "status" to "completed", "items" to listOf(mapOf("id" to "item_1", "type" to "agent_message", "text" to "answer"))))))
        val turn = (snapshot["turns"] as List<*>).single() as Map<*, *>
        assertEquals("host-prompt", turn["id"])
        assertEquals("original-user", ((turn["items"] as List<*>)[0] as Map<*, *>)["hostMessageId"])
        assertEquals("host-prompt-item_1", ((turn["items"] as List<*>)[1] as Map<*, *>)["id"])
        assertEquals(NA_AGENT_ID, snapshot["agentId"])
    }
    @Test fun repeatedSnapshotsEmitOnlyNewTextWithWhitespace() {
        val projection = NaItemProjection()
        val item = mapOf("id" to "message_1", "type" to "agent_message", "text" to "Hello ")
        assertEquals("Hello ", (projection.updates(item).single()["content"] as Map<*, *>)["text"])
        assertTrue(projection.updates(item).isEmpty())
        assertEquals("world\n", (projection.updates(item + ("text" to "Hello world\n")).single()["content"] as Map<*, *>)["text"])
    }
    @Test fun completedToolCreatesAndCompletesOneIdentity() {
        val projection = NaItemProjection()
        val item = mapOf("id" to "tool_1", "type" to "command_execution", "command" to "pwd", "status" to "completed", "aggregated_output" to "/workspace")
        val updates = projection.updates(item)
        assertEquals(listOf("tool_call", "tool_call_update"), updates.map { it["sessionUpdate"] })
        assertEquals(listOf("in_progress", "completed"), updates.map { it["status"] })
        assertTrue(updates.all { it["toolCallId"] == "tool_1" })
        assertTrue(projection.updates(item).isEmpty())
    }
    @Test fun reasoningAndMessagesRemainDifferentItems() {
        val projection = NaItemProjection()
        assertEquals("agent_thought_chunk", projection.updates(mapOf("id" to "r", "type" to "reasoning", "text" to "Thinking")).single()["sessionUpdate"])
        assertEquals("agent_message_chunk", projection.updates(mapOf("id" to "m", "type" to "agent_message", "text" to "Answer")).single()["sessionUpdate"])
    }
    @Test(expected = IllegalStateException::class) fun rewrittenVisibleTextFailsInsteadOfAppendingDuplicate() {
        val projection = NaItemProjection()
        projection.updates(mapOf("id" to "m", "type" to "agent_message", "text" to "first"))
        projection.updates(mapOf("id" to "m", "type" to "agent_message", "text" to "replaced"))
    }
}
