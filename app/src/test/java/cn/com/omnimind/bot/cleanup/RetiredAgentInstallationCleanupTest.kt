package cn.com.omnimind.bot.cleanup

import android.content.Context
import cn.com.omnimind.bot.agent.runtime.ManagedAgentUninstallReport
import cn.com.omnimind.bot.agent.runtime.acpProfileStoreTestContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RetiredAgentInstallationCleanupTest {
    @get:Rule val directory = TemporaryFolder()

    private fun installation(distribution: String): File =
        File(directory.root, "local/$distribution/root/.npm-global/bin/dsh-acp-android").apply {
            parentFile.mkdirs()
            writeText("legacy launcher")
        }

    @Test
    fun `cleanup visits both existing installations and never installs a missing environment`() = runBlocking {
        val context = acpProfileStoreTestContext(directory.root)
        installation("alpine")
        installation("ubuntu")
        val called = mutableListOf<String>()
        val first = RetiredAgentInstallationCleanup.cleanup(
            context, installed = { it.id == "ubuntu" },
        ) { distribution ->
            called += distribution.id
            ManagedAgentUninstallReport(true, false, false)
        }
        assertFalse(first)
        assertEquals(listOf("ubuntu"), called)
        assertFalse(context.getSharedPreferences("retired_agent_installations", Context.MODE_PRIVATE)
            .getBoolean("deepseek_v1_complete", false))
    }

    @Test
    fun `partial removal stays incomplete and retained history never schedules removal`() = runBlocking {
        val context = acpProfileStoreTestContext(directory.root)
        val launcher = installation("alpine")
        val history = File(directory.root, "local/alpine/root/.dsh/omnibot-acp/sessions/session.json").apply {
            parentFile.mkdirs()
            writeText("keep")
        }
        assertFalse(RetiredAgentInstallationCleanup.cleanup(context, installed = { true }) {
            ManagedAgentUninstallReport(false, false, true, listOf("external launcher"))
        })
        assertTrue(RetiredAgentInstallationCleanup.cleanup(context, installed = { true }) {
            launcher.delete()
            ManagedAgentUninstallReport(true, false, false)
        })
        assertTrue(RetiredAgentInstallationCleanup.cleanup(context, installed = { error("no runtime needed") }) {
            error("retained history must not trigger another removal")
        })
        assertEquals("keep", history.readText())
        assertTrue(context.getSharedPreferences("retired_agent_installations", Context.MODE_PRIVATE)
            .getBoolean("deepseek_v1_complete", false))
    }
}
