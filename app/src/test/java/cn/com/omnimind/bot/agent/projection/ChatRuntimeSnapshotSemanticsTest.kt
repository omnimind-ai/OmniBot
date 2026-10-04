package cn.com.omnimind.bot.agent.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `replaceConversationSnapshot` semantics formerly tested in Dart
 * `chat_page_models_test.dart`, plus the Flutter-adapter guards added when
 * the snapshot started crossing the channel.
 */
class ChatRuntimeSnapshotSemanticsTest {
    private lateinit var fixture: ChatRuntimeTestFixture
    private val coordinator get() = fixture.coordinator

    @Before
    fun setUp() {
        fixture = ChatRuntimeTestFixture()
    }

    @Test
    fun `when preserveLiveStreamingState=true the snapshot keeps reducer push state intact`() {
        val conversationId = 0xC0DE
        coordinator.ensureEphemeralRuntime(conversationId, CHAT_RUNTIME_MODE_AGENT)
        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        runtime.isAiResponding = true
        runtime.currentDispatchTurnId = "turn-1"
        runtime.lastAgentTurnId = "turn-1"
        runtime.currentAiMessages["msg-1-codex-agent"] = "streaming text"
        runtime.currentThinkingMessages["turn-1"] = "thinking text"
        runtime.currentThinkingStage = ThinkingStage.THINKING
        runtime.isDeepThinking = true

        coordinator.replaceConversationSnapshot(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = emptyList(),
            isAiResponding = false,
            currentDispatchTurnId = null,
            currentThinkingStage = ThinkingStage.COMPLETE,
            preserveLiveStreamingState = true,
        )

        assertTrue(runtime.isAiResponding)
        assertEquals("turn-1", runtime.currentDispatchTurnId)
        assertEquals("turn-1", runtime.lastAgentTurnId)
        assertEquals("streaming text", runtime.currentAiMessages["msg-1-codex-agent"])
        assertEquals("thinking text", runtime.currentThinkingMessages["turn-1"])
        assertEquals(ThinkingStage.THINKING, runtime.currentThinkingStage)
        assertTrue(runtime.isDeepThinking)
        assertTrue("turn-1" in runtime.activeAgentTurnIds)
    }

    @Test
    fun `when preserveLiveStreamingState=false the snapshot fully overwrites runtime state`() {
        val conversationId = 0xBEEF
        coordinator.ensureEphemeralRuntime(conversationId, CHAT_RUNTIME_MODE_AGENT)
        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        runtime.isAiResponding = true
        runtime.currentDispatchTurnId = "stale-turn"
        runtime.currentAiMessages["old"] = "old text"

        coordinator.replaceConversationSnapshot(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = emptyList(),
            isAiResponding = false,
            currentDispatchTurnId = null,
        )

        assertFalse(runtime.isAiResponding)
        assertNull(runtime.currentDispatchTurnId)
        assertTrue(runtime.currentAiMessages.isEmpty())
        assertTrue(runtime.activeAgentTurnIds.isEmpty())
    }

    @Test
    fun `snapshot keeps one latest row for each message id`() {
        coordinator.replaceConversationSnapshot(
            conversationId = 0xD56,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = listOf(
                ChatMessage.assistantMessage("old", id = "same-id"),
                ChatMessage.assistantMessage("other", id = "other-id"),
                ChatMessage.assistantMessage("latest", id = "same-id"),
            ),
        )
        val messages = coordinator.debugRuntimeStateFor(0xD56, CHAT_RUNTIME_MODE_AGENT)!!.messages
        assertEquals(listOf("same-id", "other-id"), messages.map { it.id })
        assertEquals("latest", messages.first().text)
    }

    @Test
    fun `a snapshot built from an older revision cannot roll back a newer runtime`() {
        val conversationId = 0xD57
        coordinator.ensureRuntime(conversationId, CHAT_RUNTIME_MODE_AGENT)
        coordinator.publishDirtySnapshots()
        val pageSawRevision = coordinator.revisionFor(conversationId, CHAT_RUNTIME_MODE_AGENT)
        // An event projected after the page captured its snapshot.
        coordinator.beginAcpTurn("turn-new", conversationId, CHAT_RUNTIME_MODE_AGENT)
        assertTrue(coordinator.revisionFor(conversationId, CHAT_RUNTIME_MODE_AGENT) > pageSawRevision)

        coordinator.replaceConversationSnapshot(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = listOf(ChatMessage.userMessage("history", id = "h1")),
            isAiResponding = false,
            basedOnRevision = pageSawRevision,
            keepTextCaches = true,
        )

        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        assertTrue(runtime.isAiResponding)
        assertEquals("turn-new", runtime.activeRunId)
        assertTrue(runtime.messages.any { it.id == "h1" })
    }

    @Test
    fun `a UI snapshot keeps runtime-owned text caches`() {
        val conversationId = 0xD58
        coordinator.ensureRuntime(conversationId, CHAT_RUNTIME_MODE_AGENT)
        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        runtime.currentAiMessages["entry"] = "cached"
        coordinator.replaceConversationSnapshot(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = emptyList(),
            keepTextCaches = true,
        )
        assertEquals("cached", runtime.currentAiMessages["entry"])
    }

    @Test
    fun `snapshots expose bound task ids for the UI task guard`() {
        val conversationId = 0xD59
        val published = ArrayList<ChatRuntimeSnapshot>()
        coordinator.addListener { snapshots, _ -> published += snapshots }
        coordinator.beginAcpTurn("task-1", conversationId, CHAT_RUNTIME_MODE_AGENT)
        assertEquals(setOf("task-1"), published.last().boundTaskIds)
        coordinator.unregisterTask("task-1", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.publishDirtySnapshots()
        assertTrue(published.last().boundTaskIds.isEmpty())
    }

    @Test
    fun `revisions are ordered across snapshots and removals`() {
        val removedRevisions = ArrayList<Long>()
        val snapshotRevisions = ArrayList<Long>()
        coordinator.addListener { snapshots, removed ->
            snapshots.forEach { snapshotRevisions += it.revision }
            removedRevisions += removed.values
        }
        coordinator.beginAcpTurn("t", 0xD60, CHAT_RUNTIME_MODE_AGENT)
        coordinator.discardConversationRuntime(0xD60, CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn("t2", 0xD60, CHAT_RUNTIME_MODE_AGENT)
        assertTrue(removedRevisions.single() > snapshotRevisions.first())
        assertTrue(snapshotRevisions.last() > removedRevisions.single())
    }
}
