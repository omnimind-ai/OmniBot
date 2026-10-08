package cn.com.omnimind.nativeui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dart `_canEditUserMessage` / `_canRetryUserMessage` / `retriedMessageRoundRemovalCount` (5e-5). */
class UserMessageActionsTest {
    private fun user(id: String, text: String? = "问题", attachments: Boolean = false) = ChatMessageUi(
        id = id, type = 1, user = 1,
        content = buildMap {
            text?.let { put("text", it) }
            if (attachments) put("attachments", listOf(mapOf("name" to "a.png")))
        },
    )
    private fun ai(id: String) = ChatMessageUi(id = id, type = 1, user = 2, content = mapOf("text" to "回答"))

    // Newest first.
    private val messages = listOf(ai("3-ai"), user("3-user"), ai("1-ai"), user("1-user"))

    @Test
    fun `the latest user message offers edit, copy and retry`() {
        assertEquals(
            listOf(UserMessageAction.Edit, UserMessageAction.Copy, UserMessageAction.Retry),
            userMessageActions(messages, "3-user", isProcessing = false, canSend = true),
        )
    }

    @Test
    fun `older messages and running replies only copy`() {
        assertEquals(listOf(UserMessageAction.Copy), userMessageActions(messages, "1-user", isProcessing = false, canSend = true))
        assertEquals(listOf(UserMessageAction.Copy), userMessageActions(messages, "3-user", isProcessing = true, canSend = true))
        // Stored history the page cannot send to.
        assertEquals(listOf(UserMessageAction.Copy), userMessageActions(messages, "3-user", isProcessing = false, canSend = false))
    }

    @Test
    fun `attachment-only messages retry but cannot be edited or copied`() {
        val withFile = listOf(user("5-user", text = null, attachments = true))
        assertEquals(listOf(UserMessageAction.Retry), userMessageActions(withFile, "5-user", isProcessing = false, canSend = true))
        assertEquals(emptyList<UserMessageAction>(), userMessageActions(listOf(user("6-user", text = " ")), "6-user", false, true))
    }

    @Test
    fun `a retry removes the newer rows and keeps or drops the user row`() {
        val ids = messages.map { it.id }
        assertEquals(1, retriedRoundRemovalCount(ids, "3-user", keepUserMessage = true))
        assertEquals(2, retriedRoundRemovalCount(ids, "3-user", keepUserMessage = false))
        assertEquals(0, retriedRoundRemovalCount(ids, "missing", keepUserMessage = false))
    }
}
