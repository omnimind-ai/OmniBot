package cn.com.omnimind.bot.plugin.sandbox

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.contentText
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class XiaowanChatCompletionRequestFactoryTest {
    @Test
    fun `large prompt and system are forwarded without a host character limit`() {
        val system = "system-" + "s".repeat(16 * 1024)
        val prompt = "prompt-" + "p".repeat(64 * 1024)

        val request = XiaowanChatCompletionRequestFactory.create(
            prompt = prompt,
            system = system
        )

        assertEquals(system, request.messages.first().contentText())
        assertEquals(prompt, request.messages.last().contentText())
    }

    @Test
    fun `large multi-turn request is forwarded without a host aggregate limit`() {
        val messages = listOf(
            ChatCompletionMessage(role = "user", content = JsonPrimitive("u".repeat(24 * 1024))),
            ChatCompletionMessage(role = "assistant", content = JsonPrimitive("a".repeat(24 * 1024))),
            ChatCompletionMessage(role = "user", content = JsonPrimitive("follow-up".repeat(8 * 1024)))
        )

        val request = XiaowanChatCompletionRequestFactory.create(messages = messages)

        assertEquals(messages.map { it.contentText() }, request.messages.map { it.contentText() })
    }
}
