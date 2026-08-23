package cn.com.omnimind.bot.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAcpRuntimeTest {
    @Test
    fun `legacy conversation without binding creates session on load`() {
        assertTrue(
            shouldCreateSessionForConversationLoad(
                explicitSessionId = null,
                explicitThreadId = null,
                conversationId = 42L,
                hasConversationBinding = false
            )
        )
    }

    @Test
    fun `bound conversation still resolves its existing session`() {
        assertFalse(
            shouldCreateSessionForConversationLoad(
                explicitSessionId = null,
                explicitThreadId = null,
                conversationId = 42L,
                hasConversationBinding = true
            )
        )
    }

    @Test
    fun `explicit session is never replaced by a new session`() {
        assertFalse(
            shouldCreateSessionForConversationLoad(
                explicitSessionId = "session-1",
                explicitThreadId = null,
                conversationId = 42L,
                hasConversationBinding = false
            )
        )
    }
}
