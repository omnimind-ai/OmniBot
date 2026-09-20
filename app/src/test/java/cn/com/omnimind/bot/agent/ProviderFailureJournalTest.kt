package cn.com.omnimind.bot.agent

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlinx.coroutines.CancellationException

class ProviderFailureJournalTest {
    @Test fun `provider diagnostics survive asset refresh and never retain response secrets`() {
        val root = Files.createTempDirectory("provider-diagnostic").toFile()
        try {
            val error = AgentStreamRequestException(400,
                "GLM fallback timeout Bearer private-secret user-private-prompt", "private-response")
            ProviderFailureJournal.record(root, error)
            val skill = root.resolve("self-improving-agent")
            val file = skill.resolve("data/provider-diagnostic.json")
            val saved = file.readText()
            assertTrue(saved.contains("provider_request_rejected"))
            assertTrue(saved.contains("needs_verification"))
            assertFalse(saved.contains("private"))
            refreshBuiltinSkillAssets(skill) { skill.resolve("SKILL.md").writeText("updated") }
            assertEquals(saved, file.readText())
            ProviderFailureJournal.record(root, CancellationException("cancelled"))
            assertEquals(saved, file.readText())
            ProviderFailureJournal.record(root, AgentStreamRequestException(401, "secret", null))
            assertTrue(file.readText().contains("provider_authentication_failed"))
            assertFalse(file.readText().contains("secret"))
        } finally { root.deleteRecursively() }
    }
    @Test fun `diagnostic history is bounded and survives repeated builtin refresh`() {
        val root = Files.createTempDirectory("provider-history").toFile()
        try {
            repeat(25) { index ->
                ProviderFailureJournal.record(root,
                    AgentStreamRequestException(if (index % 2 == 0) 401 else 503, "secret", null))
            }
            val skill = root.resolve("self-improving-agent")
            val history = skill.resolve("data/provider-diagnostics")
            assertEquals(20, history.listFiles()!!.size)
            val before = history.listFiles()!!.associate { it.name to it.readText() }
            assertTrue(before.values.none { it.contains("secret") })
            repeat(2) {
                refreshBuiltinSkillAssets(skill) { skill.resolve("SKILL.md").writeText("new") }
            }
            assertEquals(before, history.listFiles()!!.associate { it.name to it.readText() })
            ProviderFailureJournal.record(root, CancellationException("cancelled"))
            assertEquals(20, history.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
}
