package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLlmStreamAccumulatorTest {
    @Test
    fun `partial parallel inputs retain provider ids and exact content without invented cards`() {
        val accumulator = AgentLlmStreamAccumulator(json = Json)
        accumulator.consume("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"file_write","arguments":"{"}},{"index":1,"function":{"name":"file_write","arguments":"{"}},{"index":2,"id":"unnamed","function":{"arguments":"{"}}]}}]}""")
        assertEquals(listOf("a"), accumulator.currentToolCalls().map { it.id })
        accumulator.consume("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"content\":\"<html>"}},{"index":1,"id":"b","function":{"arguments":"\"path\":"}}]}}]}""")
        assertEquals(listOf("a", "b"), accumulator.currentToolCalls().map { it.id })
        assertEquals(listOf("{\"content\":\"<html>", "{\"path\":"), accumulator.currentToolCalls().map { it.function.arguments })
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `ignores empty tool call placeholder after valid streamed call`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}},{"index":1,"id":"","type":"","function":{"name":"","arguments":""}}]},"finish_reason":"tool_calls"}]}"""
        )

        val turn = accumulator.buildTurn()

        val toolCalls = requireNotNull(turn.message.toolCalls)
        assertEquals(1, toolCalls.size)
        assertEquals("call_1", toolCalls.single().id)
        assertEquals("get_time", toolCalls.single().function.name)
    }

    @Test
    fun `ignores identity only tool call placeholder after valid streamed call`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}},{"index":1,"id":"call_placeholder","type":"function","function":{"arguments":""}}]},"finish_reason":"tool_calls"}]}"""
        )

        val toolCalls = requireNotNull(accumulator.buildTurn().message.toolCalls)

        assertEquals(1, toolCalls.size)
        assertEquals("call_1", toolCalls.single().id)
        assertEquals("get_time", toolCalls.single().function.name)
    }

    @Test
    fun `rejects tool call with identity or arguments but no function name`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_bad","type":"function","function":{"arguments":"{\"timezone\":\"UTC\"}"}}]},"finish_reason":"tool_calls"}]}"""
        )

        val error = runCatching { accumulator.buildTurn() }.exceptionOrNull()

        requireNotNull(error)
        assertEquals("tool_call[0] missing function.name", error.message)
    }

    @Test
    fun `does not discard a nameless tool call that contains arguments`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}},{"index":1,"id":"call_bad","type":"function","function":{"arguments":"{\"timezone\":\"UTC\"}"}}]},"finish_reason":"tool_calls"}]}"""
        )

        val error = runCatching { accumulator.buildTurn() }.exceptionOrNull()

        requireNotNull(error)
        assertEquals("tool_call[1] missing function.name", error.message)
    }

    @Test
    fun `keeps all valid calls while dropping a trailing identity placeholder`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}},{"index":1,"id":"call_2","type":"function","function":{"name":"get_weather","arguments":"{}"}},{"index":2,"id":"call_placeholder","type":"function","function":{"arguments":""}}]},"finish_reason":"tool_calls"}]}"""
        )

        val toolCalls = requireNotNull(accumulator.buildTurn().message.toolCalls)

        assertEquals(listOf("get_time", "get_weather"), toolCalls.map { it.function.name })
    }

    @Test
    fun `reconciles OmniMind arguments streamed under the next tool index`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"","name":"vlm_task"},"id":"call_vlm","index":0,"type":"function"}]}}]}"""
        )
        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{\"goal\":\"打开蓝牙\",\"tool_title\":\"打开蓝牙\"}"},"index":1}]},"finish_reason":"tool_calls"}]}"""
        )

        val toolCall = requireNotNull(accumulator.buildTurn().message.toolCalls).single()

        assertEquals("call_vlm", toolCall.id)
        assertEquals("vlm_task", toolCall.function.name)
        assertEquals(
            """{"goal":"打开蓝牙","tool_title":"打开蓝牙"}""",
            toolCall.function.arguments,
        )
    }

    @Test
    fun `reconciles multiple OmniMind calls sharing the arguments index`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"","name":"file_list"},"id":"call_list","index":0,"type":"function"}]}}]}"""
        )
        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{\"path\":\"/workspace\"}"},"index":1}]}}]}"""
        )
        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"","name":"skills_read"},"id":"call_skill","index":2,"type":"function"}]}}]}"""
        )
        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{\"skillId\":\"vibe-project-builder\"}"},"index":1}]}}]}"""
        )
        accumulator.consume("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")

        val toolCalls = requireNotNull(accumulator.buildTurn().message.toolCalls)

        assertEquals(listOf("file_list", "skills_read"), toolCalls.map { it.function.name })
        assertEquals("""{"path":"/workspace"}""", toolCalls[0].function.arguments)
        assertEquals(
            """{"skillId":"vibe-project-builder"}""",
            toolCalls[1].function.arguments,
        )
    }

    @Test
    fun `blank continuation preserves existing streamed tool call identity and name`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}}]}}]}"""
        )
        accumulator.consume(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"","type":"","function":{"name":"","arguments":""}}]},"finish_reason":"tool_calls"}]}"""
        )

        val toolCall = requireNotNull(accumulator.buildTurn().message.toolCalls).single()

        assertEquals("call_1", toolCall.id)
        assertEquals("get_time", toolCall.function.name)
    }

    @Test
    fun `reads tokens per second from usage performance payload`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume("""{"choices":[{"delta":{"content":"已完成。"}}]}""")
        accumulator.consume(
            """
            {"id":"chatcmpl-test","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":15,"completion_tokens":100,"total_tokens":115,"performance":{"prefill_tokens_per_second":36.6,"decode_tokens_per_second":12.4}}}
            """.trimIndent()
        )

        val turn = accumulator.buildTurn()

        assertNotNull(turn.usage)
        assertEquals(36.6, turn.usage?.prefillTokensPerSecond ?: 0.0, 0.0)
        assertEquals(12.4, turn.usage?.decodeTokensPerSecond ?: 0.0, 0.0)
    }

    @Test
    fun `does not finalize content when provider closes without a terminal marker`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"content":"网关已返回完整答案"}}]}"""
        )

        assertFalse(accumulator.canFinalizeOnClosed())
    }

    @Test
    fun `can retain reasoning content on assistant message for deepseek tool rounds`() {
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            includeReasoningInAssistantMessage = true
        )

        accumulator.consume(
            """{"choices":[{"delta":{"reasoning_content":"需要查工具","content":"","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        )

        val turn = accumulator.buildTurn()

        assertEquals("需要查工具", turn.reasoning)
        assertEquals("需要查工具", turn.message.reasoningContent)
    }

    @Test
    fun `tool rounds retain reasoning content even without full deepseek adapter mode`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"reasoning_content":"继续调用工具前要回传思考","content":"","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        )

        val turn = accumulator.buildTurn()

        assertEquals("继续调用工具前要回传思考", turn.reasoning)
        assertEquals("继续调用工具前要回传思考", turn.message.reasoningContent)
    }

    @Test
    fun `does not append the same provider reasoning aliases twice`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"reasoning_content":"先分析","reasoning":"先分析","thinking":"先分析"}}]}"""
        )
        accumulator.consume(
            """{"choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}"""
        )

        assertEquals("先分析", accumulator.buildTurn().reasoning)
    }

    @Test
    fun `treats cumulative provider reasoning snapshots as one stream`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"reasoning_content":"先分析"}}]}"""
        )
        accumulator.consume(
            """{"choices":[{"delta":{"reasoning_content":"先分析，再调用工具"}}]}"""
        )
        accumulator.consume(
            """{"choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}"""
        )

        assertEquals("先分析，再调用工具", accumulator.buildTurn().reasoning)
    }

    @Test
    fun `keeps top level reasoning when choices also contain visible content`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"content":"答案"}}],"reasoning":"先分析"}"""
        )
        accumulator.consume(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        )

        val turn = accumulator.buildTurn()

        assertEquals("答案", turn.message.contentText())
        assertEquals("先分析", turn.reasoning)
    }

    @Test
    fun `surfaces top level provider error instead of empty assistant turn`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"error":{"code":"upstream_unavailable","message":"Upstream service is unavailable and returned no output.","param":null,"type":"service_unavailable_error"},"status_code":503}"""
        )
        accumulator.consume(
            """{"id":"","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":0,"total_tokens":10}}"""
        )
        accumulator.consume("[DONE]")

        val error = runCatching { accumulator.buildTurn() }.exceptionOrNull()

        requireNotNull(error)
        assertTrue(error.message.orEmpty().contains("provider stream returned error"))
        assertTrue(error.message.orEmpty().contains("status=503"))
        assertTrue(error.message.orEmpty().contains("upstream_unavailable"))
    }

    @Test
    fun `preserves surrogate pair split across chunks`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume("""{"choices":[{"delta":{"content":"前缀\uD83D"}}]}""")
        accumulator.consume("""{"choices":[{"delta":{"content":"\uDE00后缀"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("前缀😀后缀", turn.message.contentText())
    }

    @Test
    fun `drops dangling surrogate from final content`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume("""{"choices":[{"delta":{"content":"前缀\uD83D后缀"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("前缀后缀", turn.message.contentText())
    }

    @Test
    fun `does not append choice text when delta content already supplied payload`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume(
            """{"choices":[{"delta":{"content":"The build finished."},"text":"The build finished."}]}"""
        )

        val turn = accumulator.buildTurn()

        assertEquals("The build finished.", turn.message.contentText())
    }

    @Test
    fun `keeps completion style choice text when no chat payload exists`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume("""{"choices":[{"text":"The build finished."}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("The build finished.", turn.message.contentText())
    }

    @Test
    fun `streams ordinary content immediately without provider-specific buffering`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume("""{"choices":[{"delta":{"content":"The user is asking for a build fix."}}]}""")

        assertEquals("The user is asking for a build fix.", accumulator.currentContent())
        assertEquals("The user is asking for a build fix.", accumulator.buildTurn().message.contentText())
    }

    @Test
    fun `only complete inline think tags classify stream content as reasoning`() {
        val accumulator = AgentLlmStreamAccumulator(json = json)

        accumulator.consume("""{"choices":[{"delta":{"content":"<think>inner reasoning</think>final answer"}}]}""")

        val turn = accumulator.buildTurn()

        assertEquals("inner reasoning", turn.reasoning)
        assertEquals("final answer", turn.message.contentText())
    }
}
