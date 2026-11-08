package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.AgentPromptInbox
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class XiaowanSteeringTest {
    private class Fixture {
        @Suppress("UNCHECKED_CAST")
        val type = Class.forName("cn.com.omnimind.bot.agent.runtime.XiaowanAgentSession") as Class<AgentSession>
        val session = mock(type, CALLS_REAL_METHODS)
        val job = Job()
        val updates = mutableListOf<Pair<String, SessionUpdate>>()
        val inbox = AgentPromptInbox("active-turn") { entry ->
            updates += "session-a" to xiaowanSteeringUserUpdate(entry)
        }
        val closed = AtomicBoolean(false)

        init {
            set("closed", closed)
            set("activePromptJob", job)
            set("activePromptInbox", inbox)
            val idField = type.getDeclaredField("sessionId").apply { isAccessible = true }
            idField.set(session, if (idField.type == String::class.java) "session-a" else SessionId("session-a"))
        }

        fun set(name: String, value: Any) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }

        fun steer(expected: String = "active-turn", requestId: String = "input-1"): JsonObject {
            val params = buildJsonObject {
                put("sessionId", "session-a")
                put("prompt", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "Use the corrected constraint") })
                })
                put("_meta", buildJsonObject {
                    put("requestId", requestId)
                    put("expectedTurnId", expected)
                    put("steering", buildJsonObject { put("idleBehavior", "promptRequired") })
                })
            }
            return type.getDeclaredMethod("steer", JsonObject::class.java).apply { isAccessible = true }
                .invoke(session, params) as JsonObject
        }
    }

    @Test fun `steering projects exactly one user item without cancelling the prompt`() {
        val fixture = Fixture()
        try {
            assertEquals("injected", fixture.steer()["outcome"]?.jsonPrimitive?.content)
            assertEquals("injected", fixture.steer()["outcome"]?.jsonPrimitive?.content)
            assertTrue(fixture.job.isActive)
            val (id, update) = fixture.updates.single()
            assertEquals("session-a", id)
            val user = update as SessionUpdate.UserMessageChunk
            assertEquals("input-1-user", user.messageId?.value)
            assertEquals("true", (user._meta as JsonObject)["steering"]?.jsonPrimitive?.content)
            assertEquals("Use the corrected constraint", fixture.inbox.drain().single().text)
        } finally { fixture.job.cancel() }
    }

    @Test fun `stale turn and sealed prompt leave input with the caller`() {
        val fixture = Fixture()
        try {
            assertEquals("promptRequired", fixture.steer(expected = "old-turn")["outcome"]?.jsonPrimitive?.content)
            fixture.inbox.drainOrSeal()
            assertEquals("promptRequired", fixture.steer()["outcome"]?.jsonPrimitive?.content)
            assertTrue(fixture.updates.isEmpty())
            assertTrue(fixture.job.isActive)
        } finally { fixture.job.cancel() }
    }

    @Test fun `cancellation closes admission and preserves already echoed history`() = runBlocking {
        val fixture = Fixture()
        assertEquals("injected", fixture.steer()["outcome"]?.jsonPrimitive?.content)
        fixture.session.cancel()
        assertEquals("promptRequired", fixture.steer(requestId = "after-stop")["outcome"]?.jsonPrimitive?.content)
        assertEquals(1, fixture.updates.size)
        assertEquals(1, fixture.inbox.acceptedEntries().size)
        assertTrue(fixture.inbox.drain().isEmpty())
        assertFalse(fixture.job.isActive)
    }
}
