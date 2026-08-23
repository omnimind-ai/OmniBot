package cn.com.omnimind.baselib.llm

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponsesFunctionNameCodecTest {
    @Test
    fun `plan keeps valid names and collision safely encodes ACP names`() {
        val firstPlan = OpenAiResponsesFunctionNameCodec.planFor(
            requestWithTools("agent.status"),
        )
        val candidateCollision = firstPlan.encode("agent.status")
        val request = requestWithTools(
            "get_weather",
            candidateCollision,
            "agent.status",
            "agent/status",
            "协作设备.状态",
            "mcp." + "very-long-namespace-".repeat(5) + "read",
        )

        val plan = OpenAiResponsesFunctionNameCodec.planFor(request)
        val encodedNames = plan.encodeRequest(request).tools.map { it.function.name }

        assertEquals("get_weather", encodedNames[0])
        assertEquals(candidateCollision, encodedNames[1])
        assertNotEquals(candidateCollision, encodedNames[2])
        assertEquals(encodedNames.size, encodedNames.distinct().size)
        encodedNames.forEach { wireName ->
            assertTrue(wireName.matches(Regex("^[a-zA-Z0-9_-]+$")))
            assertTrue(wireName.length <= 64)
        }
        request.tools.forEachIndexed { index, tool ->
            assertEquals(tool.function.name, plan.restore(encodedNames[index]))
        }
    }

    private fun requestWithTools(vararg names: String) = ChatCompletionRequest(
        model = "gpt-5.6-sol",
        messages = emptyList(),
        tools = names.map { name ->
            ChatCompletionTool(
                function = ChatCompletionFunction(
                    name = name,
                    parameters = JsonObject(emptyMap()),
                ),
            )
        },
    )
}
