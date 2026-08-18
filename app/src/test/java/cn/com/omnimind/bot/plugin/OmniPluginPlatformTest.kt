package cn.com.omnimind.bot.plugin

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OmniPluginPlatformTest {

    @Test
    fun `empty platform preserves an empty plugin session`() = runBlocking {
        val platform = platform()

        assertTrue(platform.list().isEmpty())
        platform.openSession().useSuspending { session ->
            assertTrue(session.toolDefinitions.isEmpty())
            assertTrue(session.toolHandlers.isEmpty())
        }
    }

    @Test
    fun `install atomically enables plugin tools`() = runBlocking {
        val provider = RecordingProvider("com.omnimind.test", "test_action")
        val platform = platform(provider)

        val installed = platform.install(provider.descriptor.id)
        assertTrue(installed.installed)
        assertTrue(installed.enabled)
        assertEquals(1, provider.installCount)
        assertEquals(1, provider.enableCount)

        platform.openSession().useSuspending { session ->
            assertEquals(listOf("test_action"), session.toolDefinitions.map { it.name })
            assertEquals(setOf("test_action"), session.toolHandlers.single().toolNames)
        }
        assertEquals(2, provider.handlerDisposeCount)

        val disabled = platform.setEnabled(provider.descriptor.id, false)
        assertFalse(disabled.enabled)
        assertEquals(1, provider.disableCount)
        platform.openSession().useSuspending { session ->
            assertTrue(session.toolDefinitions.isEmpty())
        }
    }

    @Test
    fun `update refreshes an installed plugin and preserves enabled state`() = runBlocking {
        val provider = RecordingProvider("com.omnimind.updated", "updated_action")
        val platform = platform(provider)
        platform.install(provider.descriptor.id)

        val updated = platform.update(provider.descriptor.id)

        assertTrue(updated.installed)
        assertTrue(updated.enabled)
        assertEquals(1, provider.updateCount)
        assertEquals(2, provider.enableCount)
        assertEquals(1, provider.disableCount)
        platform.openSession().useSuspending { session ->
            assertEquals(listOf("updated_action"), session.toolDefinitions.map { it.name })
        }
    }

    @Test
    fun `tool conflict rejects enable without replacing active plugin`() = runBlocking {
        val first = RecordingProvider("com.omnimind.first", "shared_action")
        val second = RecordingProvider("com.omnimind.second", "shared_action")
        val platform = platform(first, second)
        platform.install(first.descriptor.id)

        assertFailsWithMessage("shared_action") {
            platform.install(second.descriptor.id)
        }

        val states = platform.list().associateBy { it.descriptor.id }
        assertTrue(states.getValue(first.descriptor.id).enabled)
        assertFalse(states.getValue(second.descriptor.id).enabled)
        platform.openSession().useSuspending { session ->
            assertEquals(listOf("shared_action"), session.toolDefinitions.map { it.name })
        }
    }

    @Test
    fun `built in tool name is reserved`() = runBlocking {
        val provider = RecordingProvider("com.omnimind.conflict", "file_read")
        val platform = platform(provider, reservedToolNames = setOf("file_read"))

        assertFailsWithMessage("file_read") {
            platform.install(provider.descriptor.id)
        }

        assertFalse(platform.list().single().installed)
    }

    @Test
    fun `failed install rolls back provider resources`() = runBlocking {
        val provider = RecordingProvider(
            pluginId = "com.omnimind.install-failure",
            toolName = "failed_action",
            installFailure = IllegalStateException("runtime download failed"),
        )
        val platform = platform(provider)

        try {
            platform.install(provider.descriptor.id)
            fail("Expected install failure")
        } catch (error: IllegalStateException) {
            assertEquals("runtime download failed", error.message)
        }

        assertEquals(1, provider.installCount)
        assertEquals(1, provider.uninstallCount)
        assertFalse(platform.list().single().installed)
    }

    @Test
    fun `unsupported interface stays visible but cannot install`() = runBlocking {
        val provider = RecordingProvider(
            pluginId = "com.omnimind.future",
            toolName = "future_action",
            interfaceVersion = OmniPluginContract.CURRENT_INTERFACE_VERSION + 1
        )
        val platform = platform(provider)

        val state = platform.list().single()
        assertFalse(state.compatible)
        assertFalse(state.installed)
        assertFailsWithMessage("interface") {
            platform.install(provider.descriptor.id)
        }
    }

    @Test
    fun `enabled persisted plugin restores without reinstall`() = runBlocking {
        val provider = RecordingProvider("com.omnimind.persisted", "persisted_action")
        val store = RecordingStore(
            listOf(OmniPluginStoredState(provider.descriptor.id, enabled = true))
        )
        val platform = platform(provider, store = store)

        platform.openSession().useSuspending { session ->
            assertEquals(listOf("persisted_action"), session.toolDefinitions.map { it.name })
        }

        assertEquals(0, provider.installCount)
        assertEquals(1, provider.enableCount)
        assertTrue(platform.list().single().enabled)
    }

    @Test
    fun `default plugin installs backend before enable and explicit uninstall is preserved`() =
        runBlocking {
            val provider = RecordingProvider("com.omnimind.default", "default_action")
            val store = OneShotDefaultStore()
            val firstPlatform = platform(
                provider,
                store = store,
                defaultEnabledPluginIds = setOf(provider.descriptor.id)
            )

            assertTrue(firstPlatform.list().single().enabled)
            assertEquals(1, provider.installCount)
            assertEquals(1, provider.enableCount)
            assertEquals(listOf("install", "enable"), provider.lifecycleEvents)

            firstPlatform.uninstall(provider.descriptor.id)
            assertFalse(firstPlatform.list().single().installed)

            val restoredPlatform = platform(
                provider,
                store = store,
                defaultEnabledPluginIds = setOf(provider.descriptor.id)
            )
            assertFalse(restoredPlatform.list().single().installed)
        }

    @Test
    fun `failed default backend install is not persisted as installed`() = runBlocking {
        val provider = RecordingProvider(
            pluginId = "com.omnimind.default-failure",
            toolName = "default_failure_action",
            installFailure = IllegalStateException("backend preparation failed"),
        )
        val platform = platform(
            provider,
            store = OneShotDefaultStore(),
            defaultEnabledPluginIds = setOf(provider.descriptor.id),
        )

        val state = platform.list().single()

        assertFalse(state.installed)
        assertFalse(state.enabled)
        assertEquals("backend preparation failed", state.errorMessage)
        assertEquals(1, provider.installCount)
        assertEquals(1, provider.uninstallCount)
        assertEquals(0, provider.enableCount)
    }

    @Test
    fun `failed default backend install retries after the next app startup`() = runBlocking {
        val provider = RecordingProvider(
            pluginId = "com.omnimind.default-retry",
            toolName = "default_retry_action",
            installFailure = IllegalStateException("network unavailable"),
        )
        val store = OneShotDefaultStore()
        val defaultIds = setOf(provider.descriptor.id)

        val firstPlatform = platform(
            provider,
            store = store,
            defaultEnabledPluginIds = defaultIds,
        )
        assertFalse(firstPlatform.list().single().installed)
        assertEquals(1, provider.installCount)

        provider.recoverInstall()
        val restartedPlatform = platform(
            provider,
            store = store,
            defaultEnabledPluginIds = defaultIds,
        )

        assertTrue(restartedPlatform.list().single().installed)
        assertTrue(restartedPlatform.list().single().enabled)
        assertEquals(2, provider.installCount)
        assertEquals(1, provider.enableCount)
    }

    @Test
    fun `required plugin is reconciled after defaults were already seeded`() = runBlocking {
        val provider = RecordingProvider(
            pluginId = "com.omnimind.required",
            toolName = "required_action",
            required = true,
        )
        val store = OneShotDefaultStore().apply {
            readWithDefaults(emptyList())
        }

        val platform = platform(provider, store = store)
        val state = platform.list().single()

        assertTrue(state.installed)
        assertTrue(state.enabled)
        assertEquals(listOf("install", "enable"), provider.lifecycleEvents)
    }

    @Test
    fun `install by default prepares runtime without loading operation tools`() = runBlocking {
        val provider = RecordingProvider(
            pluginId = "com.omnimind.install-default",
            toolName = "install_default_action",
            installByDefault = true,
        )
        val platform = platform(provider)

        val state = platform.list().single()

        assertTrue(state.installed)
        assertFalse(state.enabled)
        assertEquals(1, provider.installCount)
        assertEquals(0, provider.enableCount)
        platform.openSession().useSuspending { session ->
            assertTrue(session.toolDefinitions.isEmpty())
            assertTrue(session.toolHandlers.isEmpty())
        }
    }

    @Test
    fun `required plugin repairs a persisted disabled state`() = runBlocking {
        val provider = RecordingProvider(
            pluginId = "com.omnimind.required-disabled",
            toolName = "required_disabled_action",
            required = true,
        )
        val store = RecordingStore(
            listOf(OmniPluginStoredState(provider.descriptor.id, enabled = false))
        )

        val state = platform(provider, store = store).list().single()

        assertTrue(state.installed)
        assertTrue(state.enabled)
        assertEquals(0, provider.installCount)
        assertEquals(1, provider.enableCount)
    }

    @Test
    fun `required plugin install failure retries after restart`() = runBlocking {
        val provider = RecordingProvider(
            pluginId = "com.omnimind.required-retry",
            toolName = "required_retry_action",
            required = true,
            installFailure = IllegalStateException("runtime unavailable"),
        )
        val store = RecordingStore()

        val failed = platform(provider, store = store).list().single()
        assertFalse(failed.installed)
        assertFalse(failed.enabled)
        assertEquals(1, provider.installCount)

        provider.recoverInstall()
        val recovered = platform(provider, store = store).list().single()

        assertTrue(recovered.installed)
        assertTrue(recovered.enabled)
        assertEquals(2, provider.installCount)
        assertEquals(1, provider.enableCount)
    }

    @Test
    fun `required plugin cannot be disabled or uninstalled`() = runBlocking {
        val provider = RecordingProvider(
            pluginId = "com.omnimind.required-locked",
            toolName = "required_locked_action",
            required = true,
        )
        val platform = platform(provider)
        assertTrue(platform.list().single().enabled)

        assertFailsWithMessage("cannot be disabled") {
            platform.setEnabled(provider.descriptor.id, false)
        }
        assertFailsWithMessage("cannot be uninstalled") {
            platform.uninstall(provider.descriptor.id)
        }

        assertTrue(platform.list().single().installed)
        assertTrue(platform.list().single().enabled)
        assertEquals(0, provider.disableCount)
        assertEquals(0, provider.uninstallCount)
    }

    private fun platform(
        vararg providers: OmniPluginProvider,
        store: OmniPluginStateStore = RecordingStore(),
        reservedToolNames: Set<String> = emptySet(),
        defaultEnabledPluginIds: Set<String> = emptySet()
    ): OmniPluginPlatform {
        return OmniPluginPlatform(
            providerSource = { providers.toList() },
            stateStore = store,
            reservedToolNames = reservedToolNames,
            defaultEnabledPluginIds = defaultEnabledPluginIds
        )
    }

    private suspend fun <T : AutoCloseable> T.useSuspending(block: suspend (T) -> Unit) {
        try {
            block(this)
        } finally {
            if (this is OmniPluginSession) {
                closeSuspending()
            } else {
                close()
            }
        }
    }

    private suspend fun assertFailsWithMessage(
        messageFragment: String,
        block: suspend () -> Unit
    ) {
        try {
            block()
            fail("Expected failure containing $messageFragment")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains(messageFragment, ignoreCase = true))
        }
    }

    private class RecordingStore(
        initial: List<OmniPluginStoredState> = emptyList()
    ) : OmniPluginStateStore {
        private var states = initial

        override fun read(): List<OmniPluginStoredState> = states

        override fun write(states: List<OmniPluginStoredState>) {
            this.states = states
        }
    }

    private class OneShotDefaultStore : OmniPluginStateStore {
        private var states = emptyList<OmniPluginStoredState>()
        private var defaultsSeeded = false

        override fun read(): List<OmniPluginStoredState> = states

        override fun readWithDefaults(
            defaults: List<OmniPluginStoredState>
        ): List<OmniPluginStoredState> {
            if (!defaultsSeeded) {
                states = defaults
                defaultsSeeded = true
            }
            return states
        }

        override fun write(states: List<OmniPluginStoredState>) {
            this.states = states
        }
    }

    private class RecordingProvider(
        pluginId: String,
        private val toolName: String,
        interfaceVersion: Int = OmniPluginContract.CURRENT_INTERFACE_VERSION,
        required: Boolean = false,
        installByDefault: Boolean = false,
        private var installFailure: Throwable? = null,
    ) : OmniPluginProvider {
        var installCount = 0
        var updateCount = 0
        var uninstallCount = 0
        var enableCount = 0
        var disableCount = 0
        var handlerDisposeCount = 0
        val lifecycleEvents = mutableListOf<String>()

        fun recoverInstall() {
            installFailure = null
        }

        override val descriptor = OmniPluginDescriptor(
            id = pluginId,
            name = pluginId.substringAfterLast('.'),
            version = "1.0.0",
            interfaceVersion = interfaceVersion,
            description = "test plugin",
            publisher = "OmniMind",
            required = required,
            installByDefault = installByDefault,
        )

        override suspend fun install() {
            installCount += 1
            lifecycleEvents += "install"
            installFailure?.let { throw it }
        }

        override suspend fun uninstall() {
            uninstallCount += 1
            lifecycleEvents += "uninstall"
        }

        override suspend fun update() {
            updateCount += 1
            lifecycleEvents += "update"
        }

        override fun create(): OmniPlugin {
            return object : OmniPlugin {
                override suspend fun onEnable() {
                    enableCount += 1
                    lifecycleEvents += "enable"
                }

                override suspend fun onDisable() {
                    disableCount += 1
                    lifecycleEvents += "disable"
                }

                override fun contribution(): OmniPluginContribution {
                    return OmniPluginContribution(
                        toolGroups = listOf(
                            OmniPluginToolGroup(
                                definitions = listOf(
                                    OmniPluginToolDefinition(
                                        name = toolName,
                                        displayName = toolName,
                                        description = "test tool",
                                        parameters = buildJsonObject {
                                            put("type", "object")
                                            put("properties", JsonObject(emptyMap()))
                                        }
                                    )
                                ),
                                handlerFactory = {
                                    RecordingHandler(setOf(toolName)) {
                                        handlerDisposeCount += 1
                                    }
                                }
                            )
                        )
                    )
                }
            }
        }
    }

    private class RecordingHandler(
        override val toolNames: Set<String>,
        private val onDispose: () -> Unit
    ) : ToolHandler {
        override suspend fun execute(
            toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
            args: JsonObject,
            runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
            env: AgentExecutionEnvironment,
            callback: AgentCallback,
            toolHandle: AgentToolExecutionHandle
        ): ToolExecutionResult {
            error("not used")
        }

        override suspend fun dispose() {
            onDispose()
        }
    }
}
