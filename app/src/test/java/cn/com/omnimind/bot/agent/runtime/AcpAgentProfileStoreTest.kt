package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AcpAgentProfileStoreTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun `legacy global choice is removed without selecting a replacement or changing owners`() {
        val context = acpProfileStoreTestContext(directory.root)
        val preferences = context.getSharedPreferences("acp_agent_profiles", Context.MODE_PRIVATE)
        preferences.edit()
            .putString("selected_profile_id", "opencode-acp")
            .putString("session_bindings", Gson().toJson(mapOf("old-session" to "opencode-acp")))
            .putString("conversation_bindings", Gson().toJson(mapOf("42" to "xiaowan-acp")))
            .commit()

        repeat(2) {
            val store = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
            assertFalse(preferences.contains("selected_profile_id"))
            val catalog = store.catalogPayload()
            assertFalse(catalog.containsKey("selectedAgentId"))
            assertTrue((catalog["agents"] as List<*>).all { !(it as Map<*, *>).containsKey("selected") })
            assertEquals("opencode-acp", store.agentIdForSession("old-session"))
            assertEquals("xiaowan-acp", store.agentIdForConversation(42))
            assertEquals("opencode-acp", store.requireProfile("opencode-acp").id)
            assertThrows(IllegalArgumentException::class.java) { store.requireProfile("") }
            assertThrows(IllegalArgumentException::class.java) { store.requireProfile("unknown-agent") }
            store.save(store.requireProfile("opencode-acp").copy(enabled = false))
            assertFalse(preferences.contains("selected_profile_id"))
        }
    }

    @Test
    fun `deleting a directory entry preserves historical ownership instead of choosing another Agent`() {
        val context = acpProfileStoreTestContext(directory.root)
        val store = AcpAgentProfileStore(context)
        val profile = store.save(AcpAgentProfile("custom-agent", "Custom", command = "custom-acp"))
        store.bindConversation(42, profile.id)
        store.bindSession("old-session", profile.id)
        store.saveSessionConfiguration("old-session", mapOf("model" to "saved-model"))

        store.delete(profile.id)

        assertThrows(IllegalArgumentException::class.java) { store.requireProfile(profile.id) }
        assertEquals(profile.id, store.agentIdForConversation(42))
        assertEquals(profile.id, store.agentIdForSession("old-session"))
        assertEquals(mapOf("model" to "saved-model"), store.sessionConfiguration("old-session"))
        assertFalse(context.getSharedPreferences("acp_agent_profiles", Context.MODE_PRIVATE)
            .contains("selected_profile_id"))
    }

    @Test
    fun `a session keeps its owner until the official lifecycle removes its binding`() {
        val store = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
        store.bindSession("shared-id", AcpAgentProfileStore.XIAOWAN_AGENT_ID)
        store.bindConversation(42, AcpAgentProfileStore.XIAOWAN_AGENT_ID)
        store.saveSessionConfiguration("shared-id", mapOf("model" to "original-model"))

        store.bindSession("shared-id", AcpAgentProfileStore.XIAOWAN_AGENT_ID)
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.bindSession("shared-id", AcpAgentProfileStore.CODEX_AGENT_ID)
        }
        assertTrue(error.message.orEmpty().startsWith("agent_owner_conflict:"))
        assertEquals(AcpAgentProfileStore.XIAOWAN_AGENT_ID, store.agentIdForSession("shared-id"))
        assertEquals(AcpAgentProfileStore.XIAOWAN_AGENT_ID, store.agentIdForConversation(42))
        assertEquals(mapOf("model" to "original-model"), store.sessionConfiguration("shared-id"))

        store.unbindSession("shared-id")
        store.bindSession("shared-id", AcpAgentProfileStore.CODEX_AGENT_ID)
        assertEquals(AcpAgentProfileStore.CODEX_AGENT_ID, store.agentIdForSession("shared-id"))
        assertTrue(store.sessionConfiguration("shared-id").isEmpty())
    }

    @Test
    fun `retirement removes launch settings but preserves immutable history owners after reopening`() {
        val context = acpProfileStoreTestContext(directory.root)
        val retiredId = "deepseek-harness-acp"
        val retired = AcpAgentProfile(id = retiredId, name = "DeepSeek Harness", command = "dsh-acp")
        val custom = AcpAgentProfile(id = "user-agent", name = "User Agent", command = "my-acp")
        val preferences = context.getSharedPreferences("acp_agent_profiles", Context.MODE_PRIVATE)
        preferences.edit()
            .putString("profiles", Gson().toJson(listOf(retired, custom)))
            .putString("selected_profile_id", retiredId)
            .putString("session_bindings", Gson().toJson(mapOf("old-session" to retiredId)))
            .putString("conversation_bindings", Gson().toJson(mapOf("42" to retiredId)))
            .putString("health", Gson().toJson(mapOf(retiredId to AcpAgentHealth(installed = true))))
            .putString("package_version:alpine:$retiredId", "{\"installedVersion\":\"0.1.1-rc.2\"}")
            .putString("session_config:old-session", "{\"model\":\"original-model\"}")
            .commit()

        repeat(2) {
            val store = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
            assertFalse(store.list().any { it.id == retiredId })
            assertTrue(store.list().any { it.id == custom.id })
            assertFalse(preferences.contains("selected_profile_id"))
            assertEquals(retiredId, store.agentIdForSession("old-session"))
            assertEquals(retiredId, store.agentIdForConversation(42))
            store.bindConversation(42, AcpAgentProfileStore.CODEX_AGENT_ID)
            assertEquals(retiredId, store.agentIdForConversation(42))
            assertThrows(IllegalStateException::class.java) {
                store.repairConversationBinding(42, AcpAgentProfileStore.CODEX_AGENT_ID)
            }
            assertEquals(mapOf("model" to "original-model"), store.sessionConfiguration("old-session"))
            assertEquals(null, store.packageVersion("alpine", retiredId))
            assertThrows(IllegalStateException::class.java) { store.requireProfile(retiredId) }
            assertThrows(IllegalStateException::class.java) { store.save(retired) }
            val rejection = assertThrows(IllegalStateException::class.java) {
                store.requireSupportedAgent(null, store.agentIdForSession("old-session"), custom.id)
            }
            assertTrue(rejection.message.orEmpty().startsWith("agent_retired:"))
            store.requireSupportedAgent(custom.id, AcpAgentProfileStore.CODEX_AGENT_ID)
        }
        val stored = preferences.getString("profiles", "").orEmpty()
        assertFalse(stored.contains(retiredId))
        assertFalse(preferences.getString("health", "").orEmpty().contains(retiredId))
    }

    @Test
    fun `fresh and reset Na remain available before the first cloud check`() {
        val store = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
        val profile = store.list().single { it.id == NA_AGENT_ID }
        repeat(2) {
            val payload = profile.toPayload(health = store.health(NA_AGENT_ID))
            assertEquals(true, payload["enabled"])
            assertEquals(NaCloudEnvironment.developmentAvailable, payload["configurationAvailable"])
            assertEquals(true, payload["installed"])
            assertEquals(AcpAgentHealth.STATUS_UNCHECKED, payload["status"])
            store.clearHealth(NA_AGENT_ID)
        }
    }

    @Test
    fun `legacy missing Na is repaired durably without changing external agents`() {
        val context = acpProfileStoreTestContext(directory.root)
        val missing = AcpAgentHealth(
            status = AcpAgentHealth.STATUS_MISSING,
            installed = false,
            error = "Agent command not found: na-cloud",
            checkedAt = 123L,
            latencyMs = 10L,
        )
        context.getSharedPreferences("acp_agent_profiles", Context.MODE_PRIVATE).edit()
            .putString("health", Gson().toJson(mapOf(
                NA_AGENT_ID to missing,
                AcpAgentProfileStore.CODEX_AGENT_ID to missing.copy(error = "codex not found"),
            )))
            .commit()

        val store = AcpAgentProfileStore(context)
        val payload = store.list().single { it.id == NA_AGENT_ID }
            .toPayload(health = store.health(NA_AGENT_ID))
        assertEquals(true, payload["installed"])
        assertEquals(AcpAgentHealth.STATUS_UNCHECKED, payload["status"])
        assertEquals(null, payload["lastCheckError"])
        assertEquals(null, payload["lastCheckAt"])
        assertEquals(null, payload["lastCheckLatencyMs"])

        val reopenedContext = acpProfileStoreTestContext(directory.root)
        val persisted = JsonParser.parseString(reopenedContext
            .getSharedPreferences("acp_agent_profiles", Context.MODE_PRIVATE)
            .getString("health", null)).asJsonObject.getAsJsonObject(NA_AGENT_ID)
        assertEquals(AcpAgentHealth.STATUS_UNCHECKED, persisted["status"].asString)
        assertTrue(persisted["installed"].asBoolean)
        assertEquals(store.health(NA_AGENT_ID), AcpAgentProfileStore(reopenedContext).health(NA_AGENT_ID))
        assertEquals(missing.copy(error = "codex not found"),
            store.health(AcpAgentProfileStore.CODEX_AGENT_ID))
    }

    @Test
    fun `Na cannot acquire another missing result through health writes or direct payloads`() {
        val store = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
        val missing = AcpAgentHealth(
            status = AcpAgentHealth.STATUS_MISSING,
            installed = false,
            error = "command not found",
        )
        store.saveHealth(NA_AGENT_ID, missing)
        assertEquals(AcpAgentHealth.STATUS_UNCHECKED, store.health(NA_AGENT_ID).status)
        assertEquals(true, store.health(NA_AGENT_ID).installed)
        assertEquals(null, store.health(NA_AGENT_ID).error)
        val naPayload = store.list().single { it.id == NA_AGENT_ID }.toPayload(health = missing)
        assertEquals(AcpAgentHealth.STATUS_UNCHECKED, naPayload["status"])
        assertEquals(true, naPayload["installed"])
        val external = store.list().single { it.id == AcpAgentProfileStore.CODEX_AGENT_ID }
        assertEquals(true, external.toPayload()["configurationAvailable"])
        store.saveHealth(external.id, missing)
        assertEquals(missing, store.health(external.id))
        assertEquals(AcpAgentHealth.STATUS_MISSING, external.toPayload(health = missing)["status"])
        assertEquals(false, external.toPayload(health = missing)["installed"])
    }

    @Test
    fun `Na preserves actual cloud health and errors instead of claiming it is online`() {
        val store = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
        for (status in listOf(AcpAgentHealth.STATUS_OFFLINE, AcpAgentHealth.STATUS_ONLINE)) {
            val health = AcpAgentHealth(
                status = status,
                installed = false,
                error = if (status == AcpAgentHealth.STATUS_OFFLINE) "Cloud account needs login" else null,
                latencyMs = 87L,
                checkedAt = 456L,
                capabilities = mapOf("cloudModelManaged" to true),
            )
            store.saveHealth(NA_AGENT_ID, health)
            val reopened = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
            assertEquals(health.copy(installed = true), reopened.health(NA_AGENT_ID))
        }
    }

    @Test
    fun `session model survives reopening stays isolated and is removed on delete`() {
        val first = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
        val values = mapOf("providerProfileId" to "provider-a", "model" to "model-b")
        first.saveSessionConfiguration("session-a", values)
        val reopened = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
        assertEquals(values, reopened.sessionConfiguration("session-a"))
        assertEquals(emptyMap<String, String>(), reopened.sessionConfiguration("session-b"))
        reopened.unbindSession("session-a")
        assertEquals(emptyMap<String, String>(),
            AcpAgentProfileStore(acpProfileStoreTestContext(directory.root)).sessionConfiguration("session-a"))
    }

    @Test
    fun `session catalog reads provider cache without accepting obsolete credentials`() {
        val context = acpProfileStoreTestContext(directory.root)
        val profile = cn.com.omnimind.baselib.llm.ModelProviderProfile(
            id = "provider-a", name = "Provider A", baseUrl = "https://example.com/v1", revision = 2,
        )
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
            .putString("flutter.cached_provider_models_with_base_v2",
                """{"provider-a":{"apiBase":"https://example.com/v1","profileRevision":2,"models":[{"id":"model-b","displayName":"Model B"}]}}""")
            .commit()
        val store = cn.com.omnimind.baselib.llm.ModelProviderConfigStore
        assertEquals(listOf("model-b"), store.cachedModels(context, profile).map { it.id })
        assertTrue(store.cachedModels(context, profile.copy(revision = 3)).isEmpty())
        assertTrue(store.cachedModels(context, profile.copy(baseUrl = "https://other.example/v1")).isEmpty())
    }

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
    fun `known legacy identity migrates bindings and removes the old global selection`() {
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
            assertFalse(context.getSharedPreferences("acp_agent_profiles", Context.MODE_PRIVATE)
                .contains("selected_profile_id"))
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
            store.bindSession("session-1", profile.id)
            store.bindConversation(1001, profile.id)
        }

        repeat(2) {
            val reopened = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
            assertEquals(profile, reopened.requireProfile(profile.id))
            assertEquals(profile, reopened.list().single { it.id == profile.id })
            assertEquals(profile.id, reopened.agentIdForSession("session-1"))
            assertEquals(profile.id, reopened.agentIdForConversation(1001))
        }
    }
}
