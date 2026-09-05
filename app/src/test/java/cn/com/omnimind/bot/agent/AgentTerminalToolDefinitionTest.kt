package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTerminalToolDefinitionTest {
    @Test
    fun progressTextUsesSelectedDistributionInsteadOfGenericTerminalLabel() {
        assertTrue(
            AgentTerminalDistributionText.makeDistributionExplicit(
                "正在调用内嵌终端环境执行命令",
                TerminalDistribution.ubuntu,
                english = false
            ).contains("内嵌 Ubuntu 环境")
        )
        assertTrue(
            AgentTerminalDistributionText.makeDistributionExplicit(
                "Terminal command failed",
                TerminalDistribution.alpine,
                english = true
            ).contains("Alpine command failed")
        )
    }

    @Test
    fun ubuntuDefinitionsExposeOnlyUbuntuAndMatchingDistroId() {
        val text = terminalDefinitionsText(TerminalDistribution.ubuntu)

        assertTrue(text.contains("Ubuntu"))
        assertDistroParameter(TerminalDistribution.ubuntu, "ubuntu", "Ubuntu")
        assertFalse(text.contains("Alpine"))
        assertFalse(text.contains("OMNIBOT_TERMINAL_DISTRIBUTION"))
        assertFalse(text.contains("terminal environment"))
    }

    @Test
    fun alpineDefinitionsExposeOnlyAlpineAndMatchingDistroId() {
        val text = terminalDefinitionsText(TerminalDistribution.alpine)

        assertTrue(text.contains("Alpine"))
        assertDistroParameter(TerminalDistribution.alpine, "alpine", "Alpine")
        assertFalse(text.contains("Ubuntu"))
        assertFalse(text.contains("OMNIBOT_TERMINAL_DISTRIBUTION"))
        assertFalse(text.contains("terminal environment"))
    }

    private fun assertDistroParameter(distribution: TerminalDistribution.Spec, id: String, label: String) {
        val execute = AgentToolDefinitions.staticTools(PromptLocale.EN_US, distribution)
            .map { it["function"] as JsonObject }
            .single { it["name"]?.jsonPrimitive?.content == "terminal_execute" }
        val properties = (execute["parameters"] as JsonObject)["properties"] as JsonObject
        val distro = properties["prootDistro"] as JsonObject
        val description = distro["description"]!!.jsonPrimitive.content
        assertTrue(description, description.contains(id))
        assertTrue(description, description.contains(label))
        assertFalse(execute.containsKey("postToolRule"))
    }

    private fun terminalDefinitionsText(distribution: TerminalDistribution.Spec): String {
        return AgentToolDefinitions.staticTools(PromptLocale.EN_US, distribution)
            .filter { definition ->
                val function = definition["function"] as? JsonObject
                function?.get("name")?.jsonPrimitive?.content?.startsWith("terminal_") == true
            }
            .joinToString("\n")
    }
}
