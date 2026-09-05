package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AcpAgentProfileStoreTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun `custom command containing xiaowan survives saving and reopening`() =
        assertCustomProfileSurvives(name = "My Agent", command = "/workspace/xiaowan-next/acp")

    @Test
    fun `custom display name does not become a built-in identity`() =
        assertCustomProfileSurvives(name = "小万", command = "my-acp")

    @Test
    fun `a custom profile can explicitly launch the built-in command`() =
        assertCustomProfileSurvives(name = "My configuration", command = "omnibot-xiaowan-acp")

    @Test
    fun `reading an existing custom adapter cannot rewrite its saved ownership`() =
        assertCustomProfileSurvives(
            name = "小万 Bot", command = "/workspace/xiaowan-next/acp", preexisting = true,
        )

    @Test
    fun `known legacy identity still migrates its selection and conversation bindings`() {
        val context = acpProfileStoreTestContext(directory.root)
        val legacy = AcpAgentProfile(
            id = "legacy-xiaowan-bot", name = "小万 Bot", command = "legacy-xiaowan",
        )
        context.getSharedPreferences("acp_agent_profiles", Context.MODE_PRIVATE).edit()
            .putString("profiles", Gson().toJson(listOf(legacy)))
            .putString("selected_profile_id", legacy.id)
            .putString("session_bindings", Gson().toJson(mapOf("session-1" to legacy.id)))
            .putString("conversation_bindings", Gson().toJson(mapOf("1001" to legacy.id)))
            .apply()

        repeat(2) {
            val store = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
            assertEquals("xiaowan-acp", store.selected().id)
            assertFalse(store.list().any { it.id == legacy.id })
            assertEquals("xiaowan-acp", store.agentIdForSession("session-1"))
            assertEquals("xiaowan-acp", store.agentIdForConversation(1001))
        }
    }

    private fun assertCustomProfileSurvives(
        name: String,
        command: String,
        preexisting: Boolean = false,
    ) {
        val profile = AcpAgentProfile(
            id = "user-adapter", name = name, command = command,
            arguments = listOf("--config", "/workspace/配置 file.json"),
            environment = mapOf("CUSTOM_OPTION" to "中文 = 'quotes'", "EMPTY_OPTION" to ""),
        )
        val context = acpProfileStoreTestContext(directory.root)
        if (preexisting) {
            context.getSharedPreferences("acp_agent_profiles", Context.MODE_PRIVATE).edit()
                .putString("profiles", Gson().toJson(listOf(profile)))
                .putString("selected_profile_id", profile.id)
                .putString("session_bindings", Gson().toJson(mapOf("session-1" to profile.id)))
                .putString("conversation_bindings", Gson().toJson(mapOf("1001" to profile.id)))
                .apply()
        } else {
            val store = AcpAgentProfileStore(context)
            assertEquals(profile, store.save(profile))
            store.select(profile.id)
            store.bindSession("session-1", profile.id)
            store.bindConversation(1001, profile.id)
        }

        repeat(2) {
            val reopened = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
            assertEquals(profile, reopened.selected())
            assertEquals(profile, reopened.list().single { it.id == profile.id })
            assertEquals(profile.id, reopened.agentIdForSession("session-1"))
            assertEquals(profile.id, reopened.agentIdForConversation(1001))
        }
    }
}
