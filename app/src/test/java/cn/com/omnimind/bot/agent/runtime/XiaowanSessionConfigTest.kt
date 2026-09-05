@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
package cn.com.omnimind.bot.agent.runtime

import com.agentclientprotocol.model.*
import org.junit.Assert.*
import org.junit.Test

class XiaowanSessionConfigTest {
    private fun config() = XiaowanSessionConfig(
        listOf(ModelInfo(ModelId("model-a"), "Model A"), ModelInfo(ModelId("model-b"), "Model B")),
        "model-a",
    )

    @Test fun `reasoning is visible but provider defaults remain the initial request`() {
        assertEquals("default", config().effort)
        assertNull(config().requestEffort)
    }

    @Test fun `official configuration uses declared values and session isolation`() {
        val a = config()
        val b = config()
        a.set("model", SessionConfigOptionValue.StringValue("model-b"))
        assertEquals("model-b", a.model)
        assertEquals("model-a", b.model)
        assertEquals("model-b", (a.options.first() as SessionConfigOption.Select).currentValue.value)
    }


    @Test fun `fresh catalog replaces old choices without losing session settings`() {
        val config = config()
        config.set("reasoning_effort", SessionConfigOptionValue.StringValue("high"))
        config.replaceModels(listOf(ModelInfo(ModelId("new-model"), "New model")))
        assertEquals("high", config.requestEffort)
        assertEquals("model-a", config.model)
        config.set("model", SessionConfigOptionValue.StringValue("new-model"))
        assertNull(config.requestEffort)
        assertTrue(runCatching { config.set("model", SessionConfigOptionValue.StringValue("model-b")) }.isFailure)
    }

    @Test fun `unknown IDs types and unsupported values never mutate settings`() {
        val config = config()
        for ((id, value) in listOf(
            "reasoning_effort" to SessionConfigOptionValue.StringValue("ultra"),
            "reasoning_effort" to SessionConfigOptionValue.BoolValue(true),
            "temperature" to SessionConfigOptionValue.StringValue("1"),
            "model" to SessionConfigOptionValue.StringValue("unknown"),
        )) {
            assertTrue(runCatching { config.set(id, value) }.isFailure)
        }
        assertEquals("model-a", config.model)
    }
}
