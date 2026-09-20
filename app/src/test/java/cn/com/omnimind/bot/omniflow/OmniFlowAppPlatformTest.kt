package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.assists.controller.http.SceneChatCompletionResponse
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class OmniFlowAppPlatformTest {
    @Test
    fun `json completion reads standard json content without tool calls`() {
        val response = SceneChatCompletionResponse(
            success = true, code = "200", message = "success",
            parser = ModelSceneRegistry.ResponseParser.TEXT_CONTENT,
            content = """{"functions":[]}""",
        )
        assertEquals(response.content, resolveOmniFlowJsonCompletion(response))
    }

    @Test
    fun `python preparation failure keeps actionable package output`() {
        val message = buildOmniFlowPythonFailureMessage(
            error = "proot warning: can't sanitize binding \"/proc/self/fd/0\": No such file or directory",
            output = "ERROR: unable to select packages: py3-numpy",
            rawOutputPreview = "",
        )

        assertTrue(message.contains("unable to select packages: py3-numpy"))
        assertFalse(message.contains("proot warning"))
    }

    @Test
    fun `json completion reads submit json native tool arguments`() {
        val response = SceneChatCompletionResponse(
            success = true,
            code = "200",
            message = "success",
            parser = ModelSceneRegistry.ResponseParser.TEXT_CONTENT,
            toolCalls = listOf(
                AssistantToolCall(
                    id = "call-1",
                    function = AssistantToolCallFunction(
                        name = "submit_json",
                        arguments = """{"parameters":[]}""",
                    ),
                ),
            ),
        )

        assertEquals(
            """{"parameters":[]}""",
            resolveOmniFlowJsonCompletion(response),
        )
    }
}
