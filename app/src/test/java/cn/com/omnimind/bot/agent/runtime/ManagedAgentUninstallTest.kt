package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedAgentUninstallTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun profile(id: String = "pi-acp") = AcpAgentProfile(
        id = id,
        name = "Pi",
        command = "omnibot-pi-acp",
        builtIn = true,
        officialRuntime = AcpOfficialRuntime(
            discoveryCommand = "omnibot-pi-acp",
            managedAdapterPackage = "@earendil-works/pi-coding-agent@1.4.0",
            managedUninstallStrategy = ManagedAgentUninstallStrategy.PI,
        ),
    )

    private fun removed() = ManagedAgentUninstallReport(
        ok = true, managedFilesRemain = false, commandPresent = false,
    )

    private fun uninstaller(
        gate: ManagedAcpPreparationGate = ManagedAcpPreparationGate(),
        profiles: () -> List<AcpAgentProfile> = { listOf(profile()) },
        currentDistribution: () -> String = { "ubuntu" },
        disconnect: suspend (AcpAgentProfile) -> Unit = {},
        execute: suspend (AcpAgentProfile, String, Boolean) -> ManagedAgentUninstallReport = { _, _, _ -> removed() },
        refresh: suspend (AcpAgentProfile, String, ManagedAgentUninstallReport?) -> Unit = { _, _, _ -> },
    ) = ManagedAgentUninstaller(gate, profiles, currentDistribution, disconnect, execute, refresh)

    @Test
    fun `unknown custom and unmanaged profiles cannot uninstall the selected agent`() = runBlocking {
        val official = profile()
        val custom = profile("custom").copy(builtIn = false)
        val unsupported = profile("unsupported").copy(
            officialRuntime = official.officialRuntime!!.copy(managedUninstallStrategy = null),
        )
        val actions = mutableListOf<String>()
        val subject = uninstaller(
            profiles = { listOf(official, custom, unsupported) },
            disconnect = { actions += "disconnect" },
            execute = { _, _, _ -> actions += "execute"; removed() },
            refresh = { _, _, _ -> actions += "refresh" },
        )
        for (id in listOf("", "unknown", custom.id, unsupported.id)) {
            val failure = runCatching { subject.uninstall(id, "ubuntu") }.exceptionOrNull()
            assertTrue(id, failure is ManagedAgentUninstallException)
            assertEquals("agent_uninstall_invalid_request", (failure as ManagedAgentUninstallException).failureKind)
        }
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `a missing or changed distribution fails before disconnect or filesystem execution`() = runBlocking {
        val actions = mutableListOf<String>()
        for (requested in listOf("", " ", "alpine")) {
            val subject = uninstaller(
                disconnect = { actions += "disconnect" },
                execute = { _, _, _ -> actions += "execute"; removed() },
                refresh = { _, _, _ -> actions += "refresh" },
            )
            val failure = runCatching { subject.uninstall("pi-acp", requested) }.exceptionOrNull()
            assertEquals("agent_uninstall_distribution_changed", (failure as ManagedAgentUninstallException).failureKind)
        }
        var reads = 0
        val gate = ManagedAcpPreparationGate()
        val changedAtGate = uninstaller(
            gate = gate,
            currentDistribution = { if (reads++ == 0) "ubuntu" else "alpine" },
            disconnect = { actions += "disconnect" },
            execute = { _, _, _ -> actions += "execute"; removed() },
        )
        val failure = runCatching { changedAtGate.uninstall("pi-acp", "ubuntu") }.exceptionOrNull()
        assertEquals("agent_uninstall_distribution_changed", (failure as ManagedAgentUninstallException).failureKind)
        assertFalse(gate.isBusy)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `installation excludes uninstallation and releasing the same gate permits retry`() = runBlocking {
        val gate = ManagedAcpPreparationGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val install = async {
            gate.run("codex-acp") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var executions = 0
        val subject = uninstaller(gate = gate, execute = { _, _, _ -> executions++; removed() })
        try {
            val failure = runCatching { subject.uninstall("pi-acp", "ubuntu") }.exceptionOrNull()
            assertTrue(failure is ManagedAcpPreparationInProgressException)
            assertEquals("codex-acp", gate.currentOwnerAgentId)
            assertEquals(0, executions)
        } finally {
            release.complete(Unit)
            install.await()
        }
        assertTrue(subject.uninstall("pi-acp", "ubuntu").ok)
        assertEquals(1, executions)
        assertFalse(gate.isBusy)
    }

    @Test
    fun `partial failure is refreshed from its report and is not reported as success`() = runBlocking {
        val report = ManagedAgentUninstallReport(
            ok = false, managedFilesRemain = true, commandPresent = true,
            error = "Could not remove the package directory",
        )
        val events = mutableListOf<String>()
        val subject = uninstaller(
            disconnect = { events += "disconnect:${it.id}" },
            execute = { _, distribution, probe -> events += "execute:$distribution:$probe"; report },
            refresh = { _, distribution, actual ->
                events += "refresh:$distribution"
                assertSame(report, actual)
                val health = managedAgentHealthAfterUninstall(actual)
                assertEquals(AcpAgentHealth.STATUS_OFFLINE, health.status)
                assertEquals(true, health.installed)
                assertNull(health.preparationRevision)
                val version = managedAgentVersionAfterUninstall(ManagedAgentVersion("1.4.0"), actual)
                assertNull(version.installedVersion)
                assertEquals("local_probe_failed", version.error)
            },
        )
        val outcome = subject.uninstall("pi-acp", "ubuntu")
        assertFalse(outcome.ok)
        assertSame(report, outcome.report)
        assertEquals(report.error, outcome.error)
        assertEquals("agent_uninstall_failed", outcome.failureKind)
        assertEquals(listOf("disconnect:pi-acp", "execute:ubuntu:false", "refresh:ubuntu"), events)
    }

    @Test
    fun `command exception triggers only a read only probe and missing files cannot erase the error`() = runBlocking {
        val probes = mutableListOf<Boolean>()
        val refreshed = mutableListOf<ManagedAgentUninstallReport?>()
        val report = removed()
        val subject = uninstaller(
            execute = { _, distribution, probe ->
                assertEquals("ubuntu", distribution)
                probes += probe
                if (!probe) throw ManagedAgentUninstallException("command_timeout", "Uninstall command timed out")
                report
            },
            refresh = { _, _, actual -> refreshed += actual },
        )
        val outcome = subject.uninstall("pi-acp", "ubuntu")
        assertEquals(listOf(false, true), probes)
        assertEquals(listOf(report), refreshed)
        assertFalse(outcome.ok)
        assertTrue(outcome.report!!.removed)
        assertEquals("Uninstall command timed out", outcome.error)
        assertEquals("command_timeout", outcome.failureKind)
    }

    @Test
    fun `unreadable final inventory remains unknown and preserves the original command failure`() = runBlocking {
        val probes = mutableListOf<Boolean>()
        var refreshed = false
        val subject = uninstaller(
            execute = { _, _, probe ->
                probes += probe
                error(if (probe) "Inventory unavailable" else "Command failed")
            },
            refresh = { _, _, report ->
                refreshed = true
                assertNull(report)
                val health = managedAgentHealthAfterUninstall(report, checkedAt = 42L)
                assertEquals(AcpAgentHealth.STATUS_UNCHECKED, health.status)
                assertNull(health.installed)
                assertNull(health.preparationRevision)
                assertEquals(42L, health.checkedAt)
                val version = managedAgentVersionAfterUninstall(ManagedAgentVersion("1.4.0"), report)
                assertNull(version.installedVersion)
                assertEquals("local_probe_failed", version.error)
            },
        )
        val outcome = subject.uninstall("pi-acp", "ubuntu")
        assertFalse(outcome.ok)
        assertNull(outcome.report)
        assertEquals("Command failed", outcome.error)
        assertEquals(listOf(false, true), probes)
        assertTrue(refreshed)
    }

    @Test
    fun `distribution change during operation refreshes the original environment and reports the change`() = runBlocking {
        var distribution = "ubuntu"
        val refreshedDistributions = mutableListOf<String>()
        val subject = uninstaller(
            currentDistribution = { distribution },
            execute = { _, requested, probe ->
                assertEquals("ubuntu", requested)
                assertFalse(probe)
                distribution = "alpine"
                removed()
            },
            refresh = { _, requested, report ->
                refreshedDistributions += requested
                assertTrue(report!!.removed)
            },
        )
        val outcome = subject.uninstall("pi-acp", "ubuntu")
        assertFalse(outcome.ok)
        assertEquals("agent_uninstall_distribution_changed", outcome.failureKind)
        assertEquals(listOf("ubuntu"), refreshedDistributions)
    }

    @Test
    fun `distribution change after disconnect performs a probe but never removes files`() = runBlocking {
        var distribution = "ubuntu"
        val probes = mutableListOf<Boolean>()
        val subject = uninstaller(
            currentDistribution = { distribution },
            disconnect = { distribution = "alpine" },
            execute = { _, requested, probe ->
                assertEquals("ubuntu", requested)
                probes += probe
                removed()
            },
            refresh = { _, requested, _ -> assertEquals("ubuntu", requested) },
        )
        val outcome = subject.uninstall("pi-acp", "ubuntu")
        assertFalse(outcome.ok)
        assertEquals("agent_uninstall_distribution_changed", outcome.failureKind)
        assertEquals(listOf(true), probes)
    }

    @Test
    fun `refresh failure prevents success and releases the installation gate for a retry`() = runBlocking {
        val gate = ManagedAcpPreparationGate()
        var refreshFails = true
        val subject = uninstaller(
            gate = gate,
            refresh = { _, _, _ -> if (refreshFails) error("Inventory persistence failed") },
        )
        val first = subject.uninstall("pi-acp", "ubuntu")
        assertFalse(first.ok)
        assertEquals("Inventory persistence failed", first.error)
        assertFalse(gate.isBusy)
        refreshFails = false
        assertTrue(subject.uninstall("pi-acp", "ubuntu").ok)
    }

    @Test
    fun `cancellation still probes and refreshes in a non cancellable finalizer`() = runBlocking {
        val gate = ManagedAcpPreparationGate()
        val entered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val subject = uninstaller(
            gate = gate,
            execute = { _, distribution, probe ->
                assertEquals("ubuntu", distribution)
                events += "execute:$probe"
                if (!probe) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                assertTrue(currentCoroutineContext().isActive)
                removed()
            },
            refresh = { _, distribution, report ->
                assertTrue(currentCoroutineContext().isActive)
                assertEquals("ubuntu", distribution)
                assertTrue(report!!.removed)
                events += "refresh"
            },
        )
        val operation = launch { subject.uninstall("pi-acp", "ubuntu") }
        entered.await()
        operation.cancelAndJoin()
        assertTrue(operation.isCancelled)
        assertEquals(listOf("execute:false", "execute:true", "refresh"), events)
        assertFalse(gate.isBusy)
        assertNull(gate.currentOwnerAgentId)
    }

    @Test
    fun `parser requires one typed final report and accepts diagnostic lines around it`() {
        val marker = "__OMNI_AGENT_UNINSTALL__"
        val valid = """{"ok":true,"managedFilesRemain":false,"commandPresent":false,"externalCommands":[],"error":null}"""
        assertEquals(removed(), parseManagedAgentUninstallReport("diagnostic\n$marker$valid\nfinished"))
        val invalid = listOf(
            "no marker", "$marker$valid\n$marker$valid", "${marker}null", "${marker}{",
            marker + valid.replace("\"ok\":true", "\"ok\":\"true\""),
            marker + valid.replace("\"managedFilesRemain\":false", "\"managedFilesRemain\":0"),
            marker + valid.replace("\"commandPresent\":false", "\"commandPresent\":null"),
            marker + valid.replace("\"ok\":true,", ""),
            marker + valid.replace("\"externalCommands\":[]", "\"externalCommands\":null"),
            marker + valid.replace("\"externalCommands\":[]", "\"externalCommands\":[42]"),
            marker + valid.replace("\"externalCommands\":[]", "\"externalCommands\":[true]"),
            marker + valid.replace("\"error\":null", "\"error\":42"),
            marker + valid.replace("\"error\":null", "\"error\":true"),
        )
        invalid.forEach { output ->
            assertTrue(output, runCatching { parseManagedAgentUninstallReport(output) }.isFailure)
        }
    }

    @Test
    fun `inconsistent success flags or external commands can never produce a successful outcome`() = runBlocking {
        val reports = listOf(
            removed().copy(ok = false),
            removed().copy(managedFilesRemain = true),
            removed().copy(commandPresent = true),
            removed().copy(externalCommands = listOf("/custom/bin/pi")),
            removed().copy(error = "Uninstall reported an error"),
        )
        for (report in reports) {
            val outcome = uninstaller(execute = { _, _, _ -> report }).uninstall("pi-acp", "ubuntu")
            assertFalse(report.toString(), outcome.ok)
            assertNotNull(outcome.error)
            assertEquals(
                if (report.externalCommands.isNotEmpty()) "agent_uninstall_external_installation" else "agent_uninstall_failed",
                outcome.failureKind,
            )
        }
    }

    @Test
    fun `only the five catalog managed agents are uninstallable and customized launchers opt out`() = runBlocking {
        val store = AcpAgentProfileStore(acpProfileStoreTestContext(temporaryFolder.root))
        val expected = setOf("claude-code-acp", "codex-acp", "opencode-acp", "pi-acp", "kimi-code-acp")
        assertEquals(expected, store.list().filter { it.toPayload()["uninstallable"] == true }.map { it.id }.toSet())
        assertEquals(false, AcpAgentProfile("custom", "Custom", command = "custom").toPayload()["uninstallable"])
        assertEquals(false, profile().copy(officialRuntime = AcpOfficialRuntime("pi")).toPayload()["uninstallable"])
        val original = store.list().first { it.id == "pi-acp" }
        val customized = store.save(original.copy(command = "/custom/bin/pi-acp"))
        assertEquals(false, customized.toPayload()["uninstallable"])
        val failure = runCatching {
            uninstaller(profiles = store::list).uninstall(original.id, "ubuntu")
        }.exceptionOrNull()
        assertEquals("agent_uninstall_invalid_request", (failure as ManagedAgentUninstallException).failureKind)
        assertEquals(false, AcpAgentProfileStore(acpProfileStoreTestContext(temporaryFolder.root))
            .list().first { it.id == original.id }.toPayload()["uninstallable"])
    }

    @Test
    fun `uninstall clears local inventory and preparation but preserves configuration bindings and other distributions`() = runBlocking {
        val context = acpProfileStoreTestContext(temporaryFolder.root)
        val store = AcpAgentProfileStore(context)
        val original = store.list().first { it.id == "pi-acp" }
        val configured = store.save(original.copy(environment = mapOf("CUSTOM_SETTING" to "keep")))
        store.bindSession("session-to-keep", configured.id)
        store.bindConversation(19L, configured.id)
        store.saveSessionConfiguration("session-to-keep", mapOf("model" to "model-to-keep"))
        val ubuntu = ManagedAgentVersion("1.4.0", "1.5.0", "1.4.0", 123L, pinned = true)
        val alpine = ManagedAgentVersion("1.3.0", "1.5.0", "1.4.0", 456L, pinned = true)
        store.savePackageVersion("ubuntu", configured.id, ubuntu)
        store.savePackageVersion("alpine", configured.id, alpine)
        store.saveHealth(configured.id, AcpAgentHealth(
            status = AcpAgentHealth.STATUS_ONLINE, installed = true, preparationRevision = "old-revision",
        ))
        val subject = uninstaller(
            profiles = store::list,
            refresh = { agent, distribution, report ->
                store.savePackageVersion(distribution, agent.id,
                    managedAgentVersionAfterUninstall(store.packageVersion(distribution, agent.id), report))
                store.saveHealth(agent.id, managedAgentHealthAfterUninstall(report, checkedAt = 789L))
            },
        )
        assertTrue(subject.uninstall(configured.id, "ubuntu").ok)
        val reopened = AcpAgentProfileStore(acpProfileStoreTestContext(temporaryFolder.root))
        assertEquals(ubuntu.copy(installedVersion = null, error = null), reopened.packageVersion("ubuntu", configured.id))
        assertEquals(alpine, reopened.packageVersion("alpine", configured.id))
        assertEquals(AcpAgentHealth.STATUS_MISSING, reopened.health(configured.id).status)
        assertEquals(false, reopened.health(configured.id).installed)
        assertNull(reopened.health(configured.id).preparationRevision)
        assertEquals(789L, reopened.health(configured.id).checkedAt)
        assertEquals(configured, reopened.requireProfile(configured.id))
        assertEquals(configured.environment, reopened.list().first { it.id == configured.id }.environment)
        assertEquals(configured.id, reopened.agentIdForSession("session-to-keep"))
        assertEquals(configured.id, reopened.agentIdForConversation(19L))
        assertEquals(mapOf("model" to "model-to-keep"), reopened.sessionConfiguration("session-to-keep"))
        assertFalse(reopened.packageVersion("ubuntu", configured.id)!!.updateAvailable)
    }
}
