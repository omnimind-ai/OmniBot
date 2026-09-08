@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PlanVariant
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapper is the single ACP notification serializer shared by local ACP
 * agents, so its official envelope is a contract worth pinning down.
 */
class AcpSessionUpdateMapperTest {

    @Test
    fun codexPlanVariantsAndRemovalDecodeWithoutLosingIdentity() {
        val decoder = Json { ignoreUnknownKeys = true }
        for (body in listOf(
            """{"sessionUpdate":"plan_update","plan":{"type":"items","planId":"plan-items","entries":[]}}""",
            """{"sessionUpdate":"plan_update","plan":{"type":"file","planId":"plan-file","uri":"file:///workspace/plan.md"}}""",
            """{"sessionUpdate":"plan_removed","planId":"plan-removed"}""",
        )) {
            val line = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":$body}}"""
            val normalized = AcpHarnessAdapters.codex.normalizeStdioLine(line)
            assertEquals(normalized, AcpHarnessAdapters.codex.normalizeStdioLine(normalized))
            val params = (Json.parseToJsonElement(normalized) as JsonObject)["params"] as JsonObject
            val update = decoder.decodeFromJsonElement(SessionUpdate.serializer(), params.getValue("update"))
            val mapped = update.toAcpSessionNotification("s")!!.update
            val id = (mapped["plan"] as? Map<*, *>)?.get("id") ?: mapped["id"]
            assertTrue(id.toString().startsWith("plan-"))
        }
    }

    @Test
    fun codexPlanCompatibilityDoesNotRewriteOtherPayloadsOrCanonicalIds() {
        val lines = listOf(
            "not-json planId",
            """{"method":"session/update","params":{"update":{"sessionUpdate":"tool_call","rawInput":{"planId":"private-tool-field"}}}}""",
            """{"method":"other","params":{"update":{"sessionUpdate":"plan_removed","planId":"p"}}}""",
            """{"method":"session/update","params":{"update":{"sessionUpdate":"plan_removed","planId":"p","id":"canonical"}}}""",
        )
        lines.forEach { assertEquals(it, AcpHarnessAdapters.codex.normalizeStdioLine(it)) }
    }

    @Test
    fun codexPlanWireReachesTypedSessionUpdate() {
        // Exact codex-acp 1.10.0 plan snapshot shape; process through the
        // production compatibility boundary before the installed SDK decoder.
        val line = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"session-plan","update":{"sessionUpdate":"plan_update","plan":{"type":"markdown","planId":"plan-1","content":"# Plan\n1. inspect"}}}}"""
        val normalized = AcpHarnessAdapters.codex.normalizeStdioLine(line)
        val envelope = Json.parseToJsonElement(normalized) as JsonObject
        val params = envelope["params"] as JsonObject
        val decoder = Json { ignoreUnknownKeys = true }
        val update = decoder.decodeFromJsonElement(SessionUpdate.serializer(), params.getValue("update"))
        val plan = update.toAcpSessionNotification("session-plan")?.update?.get("plan") as Map<*, *>
        assertEquals("plan-1", plan["id"])
        assertEquals("# Plan\n1. inspect", plan["content"])
    }

    @Test
    fun promptResponseUsageBecomesSharedTurnUsagePresentation() {
        val update = PromptResponse(
            stopReason = StopReason.END_TURN,
            usage = Usage(
                inputTokens = 40,
                outputTokens = 7,
                totalTokens = 82,
                cachedReadTokens = 30,
                cachedWriteTokens = 5,
            ),
        ).toAcpTurnUsageUpdate(messageId = MessageId("msg_usage"))

        assertEquals("agent_message_chunk", update?.get("sessionUpdate"))
        assertEquals("msg_usage", update?.get("messageId"))
        assertEquals(mapOf("type" to "text", "text" to ""), update?.get("content"))
        assertEquals(
            mapOf(
                "cn.com.omnimind.agent" to mapOf(
                    "usage" to mapOf(
                        "turnUsage" to mapOf(
                            "ctx" to 75L,
                            "in" to 75L,
                            "out" to 7L,
                            "cache" to 30L,
                            "totalInputTokens" to 75L,
                            "uncachedInputTokens" to 40L,
                            "cacheReadTokens" to 30L,
                            "cacheWriteTokens" to 5L,
                            "promptTokens" to 75L,
                            "completionTokens" to 7L,
                            "totalTokens" to 82L,
                        )
                    )
                )
            ),
            update?.get("_meta"),
        )
    }

    @Test
    fun planV2AndRemovalKeepTheirOfficialAcpShapes() {
        val update = SessionUpdate.PlanUpdateV2(
            plan = PlanVariant.Markdown(
                id = "plan-1",
                content = "# Plan\n\n1. inspect",
            ),
            _meta = JsonObject(mapOf("source" to JsonPrimitive("claude"))),
        ).toAcpSessionNotification("thread-1")

        assertEquals("plan_update", update?.update?.get("sessionUpdate"))
        assertEquals(
            mapOf(
                "type" to "markdown",
                "id" to "plan-1",
                "content" to "# Plan\n\n1. inspect",
            ),
            update?.update?.get("plan"),
        )
        assertEquals(mapOf("source" to "claude"), update?.update?.get("_meta"))

        val removed = SessionUpdate.PlanRemoved(
            id = "plan-1",
            _meta = JsonObject(mapOf("reason" to JsonPrimitive("finished"))),
        ).toAcpSessionNotification("thread-1")

        assertEquals("plan_removed", removed?.update?.get("sessionUpdate"))
        assertEquals("plan-1", removed?.update?.get("id"))
        assertEquals(mapOf("reason" to "finished"), removed?.update?.get("_meta"))
    }

    @Test
    fun toolContentKeepsStandardImageBlocksForSharedImageCards() {
        val event = SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("image-1"),
            content = listOf(
                ToolCallContent.Content(
                    ContentBlock.Image(data = "AAAA", mimeType = "image/png")
                )
            ),
        ).toAcpSessionNotification("thread-1")

        assertEquals(
            listOf(
                mapOf(
                    "type" to "content",
                    "content" to mapOf(
                        "type" to "image",
                        "data" to "AAAA",
                        "mimeType" to "image/png",
                    ),
                )
            ),
            event?.update?.get("content"),
        )
    }

    @Test
    fun toolContentKeepsStandardDiffTerminalAndMetadata() {
        val event = SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("tool-content-1"),
            content = listOf(
                ToolCallContent.Diff(
                    path = "lib/main.dart",
                    oldText = "old",
                    newText = "new",
                    _meta = JsonObject(mapOf("source" to JsonPrimitive("acp"))),
                ),
                ToolCallContent.Terminal(
                    terminalId = "shell-1",
                    _meta = JsonObject(mapOf("interactive" to JsonPrimitive(true))),
                ),
            ),
        ).toAcpSessionNotification("thread-1")

        assertEquals(
            listOf(
                mapOf(
                    "type" to "diff",
                    "path" to "lib/main.dart",
                    "oldText" to "old",
                    "newText" to "new",
                    "_meta" to mapOf("source" to "acp"),
                ),
                mapOf(
                    "type" to "terminal",
                    "terminalId" to "shell-1",
                    "_meta" to mapOf("interactive" to true),
                ),
            ),
            event?.update?.get("content"),
        )
    }

    @Test
    fun agentMessageChunkKeepsItsMessageIdAsTheItemId() {
        val event = SessionUpdate.AgentMessageChunk(
            content = ContentBlock.Text("hello"),
            messageId = MessageId("msg_a")
        ).toAcpSessionNotification("thread-1")

        assertEquals("thread-1", event?.sessionId)
        assertEquals("agent_message_chunk", event?.update?.get("sessionUpdate"))
        assertEquals("msg_a", event?.update?.get("messageId"))
        assertEquals(mapOf("type" to "text", "text" to "hello"), event?.update?.get("content"))
    }

    @Test
    fun agentMessageChunkWithoutMessageIdKeepsTheOfficialOptionalFieldAbsent() {
        val event = SessionUpdate.AgentMessageChunk(content = ContentBlock.Text("hello"))
            .toAcpSessionNotification("thread-1")

        assertFalse(event?.update?.containsKey("messageId") == true)
    }

    @Test
    fun agentThoughtChunkUsesTheReasoningDeltaContract() {
        val event = SessionUpdate.AgentThoughtChunk(
            content = ContentBlock.Text("先检查消息顺序"),
            messageId = MessageId("msg_thinking")
        ).toAcpSessionNotification("thread-1")

        assertEquals("agent_thought_chunk", event?.update?.get("sessionUpdate"))
        assertEquals("msg_thinking", event?.update?.get("messageId"))
        assertEquals(
            mapOf("type" to "text", "text" to "先检查消息顺序"),
            event?.update?.get("content")
        )
    }

    @Test
    fun sessionUpdatePreservesNamespacedPresentationMeta() {
        val event = SessionUpdate.AgentThoughtChunk(
            content = ContentBlock.Text("thinking"),
            messageId = MessageId("msg_thinking"),
            _meta = JsonObject(
                mapOf(
                    "cn.com.omnimind.agent" to JsonObject(
                        mapOf("phase" to JsonPrimitive("thinking"))
                    )
                )
            )
        ).toAcpSessionNotification("thread-1")

        assertEquals(
            mapOf(
                "cn.com.omnimind.agent" to mapOf("phase" to "thinking")
            ),
            event?.update?.get("_meta")
        )
    }

    @Test
    fun toolCallUpdateOnlyCompletesOnATerminalStatus() {
        fun statusFor(status: ToolCallStatus?): String? = SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("call-1"),
            status = status
        ).toAcpSessionNotification("thread-1")?.update?.get("status") as String?

        assertEquals("completed", statusFor(ToolCallStatus.COMPLETED))
        assertEquals("failed", statusFor(ToolCallStatus.FAILED))
        assertEquals("in_progress", statusFor(ToolCallStatus.IN_PROGRESS))
        assertEquals("pending", statusFor(ToolCallStatus.PENDING))
        assertNull(statusFor(null))
    }

    @Test
    fun userMessageChunkStaysInTheOfficialSessionUpdateEnvelope() {
        // The ACP transport keeps the official echo. The Conversation reducer
        // owns idempotent merge with the locally committed user message.
        val event = SessionUpdate.UserMessageChunk(content = ContentBlock.Text("hi"))
            .toAcpSessionNotification("thread-1")

        assertEquals("thread-1", event?.sessionId)
        assertEquals("user_message_chunk", event?.update?.get("sessionUpdate"))
        assertEquals(
            mapOf("type" to "text", "text" to "hi"),
            event?.update?.get("content"),
        )
    }

    @Test
    fun sessionInfoUpdateWithoutATitleProducesNothing() {
        assertNull(SessionUpdate.SessionInfoUpdate(title = null).toAcpSessionNotification("thread-1"))
        assertNull(SessionUpdate.SessionInfoUpdate(title = "  ").toAcpSessionNotification("thread-1"))

        val renamed = SessionUpdate.SessionInfoUpdate(title = "Renamed")
            .toAcpSessionNotification("thread-1")
        assertEquals("session_info_update", renamed?.update?.get("sessionUpdate"))
        assertEquals("Renamed", renamed?.update?.get("title"))
    }

    @Test
    fun sessionScopedMetadataIsNotDroppedWhenThereIsNoTitle() {
        val update = SessionUpdate.SessionInfoUpdate(
            title = null,
            _meta = JsonObject(mapOf("source" to JsonPrimitive("agent")))
        ).toAcpSessionNotification("thread-1")

        assertEquals("session_info_update", update?.update?.get("sessionUpdate"))
        assertEquals(mapOf("source" to "agent"), update?.update?.get("_meta"))
    }

    @Test
    fun onlyTimelineUpdatesAreTurnScoped() {
        // A turn-scoped update with no resolvable turn is dropped rather than
        // rendered as its own pseudo turn; session-scoped ones still go through
        // between turns. Getting this wrong is what produced one agent avatar
        // and one "processing" row per streamed item.
        assertTrue(
            SessionUpdate.AgentMessageChunk(content = ContentBlock.Text("x")).isTurnScoped()
        )
        assertTrue(
            SessionUpdate.AgentThoughtChunk(content = ContentBlock.Text("x")).isTurnScoped()
        )
        assertTrue(
            SessionUpdate.ToolCall(toolCallId = ToolCallId("c"), title = "t").isTurnScoped()
        )
        assertTrue(
            SessionUpdate.ToolCallUpdate(toolCallId = ToolCallId("c")).isTurnScoped()
        )
        assertTrue(
            SessionUpdate.UserMessageChunk(content = ContentBlock.Text("x")).isTurnScoped()
        )

        assertFalse(SessionUpdate.SessionInfoUpdate(title = "t").isTurnScoped())
        assertFalse(
            SessionUpdate.AvailableCommandsUpdate(availableCommands = emptyList())
                .isTurnScoped()
        )
    }

    @Test
    fun userMessageChunkIsPreservedForTheSharedConversationReducer() {
        val update = SessionUpdate.UserMessageChunk(
            content = ContentBlock.Text("DSH user query"),
            messageId = MessageId("user-message-1"),
        ).toAcpSessionNotification("session-1")

        assertEquals("session-1", update?.sessionId)
        assertEquals("user_message_chunk", update?.update?.get("sessionUpdate"))
        assertEquals("user-message-1", update?.update?.get("messageId"))
        assertEquals(
            mapOf("type" to "text", "text" to "DSH user query"),
            update?.update?.get("content"),
        )
    }

    @Test
    fun unknownSessionUpdatesKeepTheirOfficialDiscriminatorAndRawJson() {
        val update = SessionUpdate.UnknownSessionUpdate(
            sessionUpdateType = "provider_progress",
            rawJson = JsonObject(
                mapOf(
                    "sessionUpdate" to JsonPrimitive("provider_progress"),
                    "progress" to JsonPrimitive(0.5),
                )
            ),
            _meta = null,
        ).toAcpSessionNotification("thread-extension")

        assertEquals("provider_progress", update?.update?.get("sessionUpdate"))
        assertEquals(
            0.5,
            (update?.update?.get("rawUpdate") as Map<*, *>) ["progress"],
        )
    }
}
