package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class OmniInferChatRequestAdapterTest {
    private val request = ChatCompletionRequest(model = "Qwen3.5-0.8B-Q4_0.gguf", messages = listOf(
        ChatCompletionMessage("system", JsonPrimitive("rules")),
        ChatCompletionMessage("system", JsonPrimitive("environment")),
        ChatCompletionMessage("user", JsonPrimitive("task")),
        ChatCompletionMessage("assistant", JsonPrimitive("result")),
        ChatCompletionMessage("user", JsonPrimitive("follow-up")),
    ))
    private fun prepare(input: ChatCompletionRequest = request, base: String = "http://127.0.0.1:9099/v1",
                        wire: String = OpenAiWireApi.CHAT_COMPLETIONS) =
        OmniInferChatRequestAdapter.prepare(input, base, input.model, wire)

    @Test fun `followup items retain exact identity and order`() {
        val prepared = prepare()
        assertEquals(4, prepared.messages.size)
        request.messages.drop(2).forEachIndexed { index, message ->
            assertSame(message, prepared.messages[index + 1])
        }
        assertSame(prepared, prepare(prepared))
        assertEquals(5, request.messages.size)
    }
    @Test fun `other endpoints and Responses keep their existing requests`() {
        assertSame(request, prepare(base = "https://api.example.com/v1"))
        assertSame(request, prepare(base = "http://127.0.0.1:9000/v1"))
        assertSame(request, prepare(wire = OpenAiWireApi.RESPONSES))
    }
    @Test fun `late system instructions fail before native code rather than reordering history`() {
        val late = request.copy(messages = request.messages + ChatCompletionMessage("system", JsonPrimitive("late")))
        assertThrows(IllegalArgumentException::class.java) { prepare(late) }
    }
}
