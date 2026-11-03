package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ManagedAgentUpdatesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private fun profile(id: String = "test", spec: String = "@vendor/cli@latest") = AcpAgentProfile(
        id, id, command = "cli", builtIn = true,
        officialRuntime = AcpOfficialRuntime("cli", managedAdapterPackage = spec),
    )

    @Test
    fun `inventory survives health changes and restart without crossing distributions`() {
        val context = acpProfileStoreTestContext(temporaryFolder.root)
        val store = AcpAgentProfileStore(context)
        val ubuntu = ManagedAgentVersion("1.0.0", "2.0.0", "2.0.0", 42L)
        val alpine = ManagedAgentVersion("2.0.0", "2.0.0", "2.0.0", 43L)
        store.savePackageVersion("ubuntu", "test", ubuntu)
        store.savePackageVersion("alpine", "test", alpine)
        store.saveHealth("test", AcpAgentHealth(status = AcpAgentHealth.STATUS_ONLINE))
        val reopened = AcpAgentProfileStore(context)
        assertEquals(ubuntu, reopened.packageVersion("ubuntu", "test"))
        assertEquals(alpine, reopened.packageVersion("alpine", "test"))
        assertNull(reopened.packageVersion("ubuntu", "another"))
    }

    @Test
    fun `versions compare numerically and respect prerelease and build precedence`() {
        assertTrue(compareAgentVersions("1.9.9", "1.10.0")!! < 0)
        assertTrue(compareAgentVersions("1.0.0-rc.2", "1.0.0-rc.10")!! < 0)
        assertTrue(compareAgentVersions("1.0.0-rc.10", "1.0.0")!! < 0)
        assertTrue(compareAgentVersions("1.0.0-2", "1.0.0-beta")!! < 0)
        assertEquals(0, compareAgentVersions("v2.0.32+local", "2.0.32+release"))
        assertNull(compareAgentVersions("unknown", "2.0.32"))
        assertEquals("2.0.32", parseAgentVersion("opencode v2.0.32\n"))
        assertEquals("0.160.0", parseAgentVersion("codex-cli 0.160.0"))
    }

    @Test
    fun `probe distinguishes missing commands from failed or unrecognizable versions`() {
        val result = parseManagedAgentVersionProbe("banner\n__OMNI_AGENT_VERSIONS__" +
            """{"old":"1.18.29","v2":"opencode v2.0.32","missing":null,"broken":"startup error"}""")
        assertEquals("1.18.29", result["old"])
        assertEquals("2.0.32", result["v2"])
        assertTrue(result.containsKey("missing"))
        assertNull(result["missing"])
        assertFalse(result.containsKey("broken"))
    }

    @Test
    fun `Pi inventory reads the SDK version from current and legacy launchers`() {
        for (output in listOf("1.4.0", "omnibot-pi-acp 0.1.0 (Pi 1.4.0)")) {
            val result = parseManagedAgentVersionProbe("__OMNI_AGENT_VERSIONS__" +
                """{"pi-acp":"$output"}""")
            assertEquals("1.4.0", result["pi-acp"])
            assertFalse(ManagedAgentVersion(result["pi-acp"], targetVersion = "1.4.0").updateAvailable)
        }
        assertEquals("1.3.0", parseAgentVersion("omnibot-pi-acp 0.1.0 (Pi 1.3.0)"))
        assertEquals("1.5.0-rc.1", parseAgentVersion("omnibot-pi-acp 0.2.0 (Pi 1.5.0-rc.1)"))
        assertNull(parseAgentVersion("omnibot-pi-acp 0.1.0 (Pi unknown)"))
        assertNull(parseAgentVersion("omnibot-pi-acp 0.1.0"))
    }

    @Test
    fun `successful local refresh clears a local probe error but preserves a registry error`() = runBlocking {
        val cache = mutableMapOf(
            "local" to ManagedAgentVersion(targetVersion = "1.4.0", error = "local_probe_failed"),
            "remote" to ManagedAgentVersion(error = "registry_unavailable"),
        )
        val updater = ManagedAgentUpdates(
            probe = { _, _ -> mapOf("local" to "1.4.0", "remote" to "1.4.0") },
            read = { _, id -> cache[id] }, save = { _, id, info -> cache[id] = info },
            latest = { error("Local refresh must not query the registry") },
        )
        updater.check(listOf(profile("local", "pi@1.4.0"), profile("remote")), "ubuntu", remote = false)
        assertEquals("1.4.0", cache.getValue("local").installedVersion)
        assertNull(cache.getValue("local").error)
        assertFalse(cache.getValue("local").updateAvailable)
        assertEquals("registry_unavailable", cache.getValue("remote").error)
        assertFalse(cache.getValue("remote").updateAvailable)
    }

    @Test
    fun `checks honor catalog pins and do not downgrade or install missing agents`() = runBlocking {
        val cache = mutableMapOf<Pair<String, String>, ManagedAgentVersion>()
        val queried = mutableListOf<String>()
        val updater = ManagedAgentUpdates(
            probe = { _, _ -> mapOf("old" to "1.18.29", "ahead" to "3.0.0-beta.1", "missing" to null,
                "pinned" to "0.1.5-rc.1", "old-pin" to "0.1.4") },
            read = { distro, id -> cache[distro to id] },
            save = { distro, id, info -> cache[distro to id] = info },
            latest = { queried += it; "2.0.32" }, now = { 42L },
        )
        updater.check(listOf(profile("old"), profile("ahead"), profile("missing"),
            profile("pinned", "@vendor/pinned@0.1.5-rc.1"), profile("old-pin", "@vendor/old@0.1.5-rc.1")), "ubuntu")
        assertTrue(cache.getValue("ubuntu" to "old").updateAvailable)
        assertFalse(cache.getValue("ubuntu" to "ahead").updateAvailable)
        assertFalse(cache.getValue("ubuntu" to "missing").updateAvailable)
        assertFalse(cache.getValue("ubuntu" to "pinned").updateAvailable)
        assertEquals("0.1.5-rc.1", cache.getValue("ubuntu" to "pinned").targetVersion)
        assertTrue(cache.getValue("ubuntu" to "old-pin").updateAvailable)
        assertEquals(42L, cache.getValue("ubuntu" to "old").checkedAt)
        assertTrue(queried.contains("@vendor/pinned"))
        assertFalse(cache.containsKey("alpine" to "old"))
    }

    @Test
    fun `one failed registry check does not erase installed versions or claim latest`() = runBlocking {
        val cache = mutableMapOf<String, ManagedAgentVersion>()
        val updater = ManagedAgentUpdates(
            probe = { _, _ -> mapOf("ok" to "1.0.0", "offline" to "1.0.0") },
            read = { _, id -> cache[id] }, save = { _, id, info -> cache[id] = info },
            latest = { if (it == "offline") error("network") else "2.0.0" },
        )
        updater.check(listOf(profile("ok", "ok@latest"), profile("offline", "offline@latest")), "ubuntu")
        assertTrue(cache.getValue("ok").updateAvailable)
        assertEquals("1.0.0", cache.getValue("offline").installedVersion)
        assertEquals("registry_unavailable", cache.getValue("offline").error)
        assertNull(cache.getValue("offline").latestVersion)
        assertFalse(cache.getValue("offline").updateAvailable)
    }

    @Test
    fun `post install refresh reads actual disk version without assuming installation succeeded`() = runBlocking {
        var installed = "1.0.0"
        var info: ManagedAgentVersion? = null
        var registryCalls = 0
        val updater = ManagedAgentUpdates(
            probe = { _, _ -> mapOf("test" to installed) },
            read = { _, _ -> info }, save = { _, _, value -> info = value },
            latest = { registryCalls++; "2.0.0" },
        )
        updater.check(listOf(profile()), "ubuntu")
        updater.check(listOf(profile()), "ubuntu", remote = false)
        assertTrue(info!!.updateAvailable)
        installed = "2.0.0"
        updater.check(listOf(profile()), "ubuntu", remote = false)
        val updated = requireNotNull(info)
        assertFalse(updated.updateAvailable)
        assertEquals("2.0.0", updated.installedVersion)
        assertEquals(1, registryCalls)
    }
}
