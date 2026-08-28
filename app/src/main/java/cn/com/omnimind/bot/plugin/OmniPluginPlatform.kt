package cn.com.omnimind.bot.plugin

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OmniPluginPlatform(
    private val providerSource: () -> List<OmniPluginProvider>,
    private val stateStore: OmniPluginStateStore,
    private val reservedToolNames: Set<String>,
    private val defaultEnabledPluginIds: Set<String> = emptySet()
) {
    private data class ActivePlugin(
        val plugin: OmniPlugin,
        val contribution: OmniPluginContribution
    )

    private val mutex = Mutex()
    private val storedStates = linkedMapOf<String, OmniPluginStoredState>()
    private val activePlugins = linkedMapOf<String, ActivePlugin>()
    private val errors = linkedMapOf<String, String>()
    private var initialized = false

    suspend fun list(): List<OmniPluginState> = mutex.withLock {
        ensureInitialized()
        providers().map(::stateFor)
    }

    suspend fun install(pluginId: String): OmniPluginState = mutex.withLock {
        ensureInitialized()
        val provider = requireProvider(pluginId)
        requireCompatible(provider.descriptor)
        val existing = storedStates[pluginId]
        if (existing != null && !existing.installPending) {
            return@withLock stateFor(provider)
        }

        try {
            provider.install()
        } catch (error: Throwable) {
            runCatching { provider.uninstall() }
            throw error
        }
        val active = try {
            activate(provider)
        } catch (error: Throwable) {
            runCatching { provider.uninstall() }
            throw error
        }
        val nextState = OmniPluginStoredState(
            pluginId = pluginId,
            enabled = true,
            installPending = false,
        )
        try {
            persist(
                storedStates.values.filterNot { it.pluginId == pluginId } + nextState,
            )
        } catch (error: Throwable) {
            runCatching { active.plugin.onDisable() }
            runCatching { provider.uninstall() }
            throw error
        }
        activePlugins[pluginId] = active
        storedStates[pluginId] = nextState
        errors.remove(pluginId)
        stateFor(provider)
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean): OmniPluginState = mutex.withLock {
        ensureInitialized()
        val provider = requireProvider(pluginId)
        requireCompatible(provider.descriptor)
        require(enabled || !provider.descriptor.required) {
            "Required plugin $pluginId cannot be disabled"
        }
        val current = storedStates[pluginId]
            ?: throw IllegalArgumentException("Plugin $pluginId is not installed")
        if (current.enabled == enabled && !(enabled && current.installPending)) {
            return@withLock stateFor(provider)
        }

        if (enabled) {
            if (current.installPending) {
                try {
                    provider.install()
                } catch (error: Throwable) {
                    runCatching { provider.uninstall() }
                    errors[pluginId] = error.message ?: error.javaClass.simpleName
                    throw error
                }
            }
            val active = activate(provider)
            val nextState = current.copy(enabled = true, installPending = false)
            try {
                persist(storedStates.values.map { if (it.pluginId == pluginId) nextState else it })
            } catch (error: Throwable) {
                runCatching { active.plugin.onDisable() }
                throw error
            }
            activePlugins[pluginId] = active
            storedStates[pluginId] = nextState
            errors.remove(pluginId)
        } else {
            val active = activePlugins[pluginId]
            active?.plugin?.onDisable()
            val nextState = current.copy(enabled = false)
            try {
                persist(storedStates.values.map { if (it.pluginId == pluginId) nextState else it })
            } catch (error: Throwable) {
                runCatching { active?.plugin?.onEnable() }
                throw error
            }
            activePlugins.remove(pluginId)
            storedStates[pluginId] = nextState
            errors.remove(pluginId)
        }
        stateFor(provider)
    }

    suspend fun update(pluginId: String): OmniPluginState = mutex.withLock {
        ensureInitialized()
        val provider = requireProvider(pluginId)
        requireCompatible(provider.descriptor)
        val current = storedStates[pluginId]
            ?: throw IllegalArgumentException("Plugin $pluginId is not installed")
        val previousActive = activePlugins[pluginId]

        if (current.enabled) {
            previousActive?.plugin?.onDisable()
        }
        try {
            provider.update()
        } catch (error: Throwable) {
            if (current.enabled) runCatching { previousActive?.plugin?.onEnable() }
            errors[pluginId] = error.message ?: error.javaClass.simpleName
            throw error
        }

        val nextActive = if (current.enabled) {
            try {
                activate(provider)
            } catch (error: Throwable) {
                runCatching { previousActive?.plugin?.onEnable() }
                errors[pluginId] = error.message ?: error.javaClass.simpleName
                throw error
            }
        } else {
            null
        }
        if (nextActive != null) {
            activePlugins[pluginId] = nextActive
        }
        errors.remove(pluginId)
        stateFor(provider)
    }

    suspend fun uninstall(pluginId: String) = mutex.withLock {
        ensureInitialized()
        val provider = requireProvider(pluginId)
        require(!provider.descriptor.required) {
            "Required plugin $pluginId cannot be uninstalled"
        }
        val current = storedStates[pluginId] ?: return@withLock
        val active = activePlugins[pluginId]
        active?.plugin?.onDisable()
        val nextStates = storedStates.values.filterNot { it.pluginId == pluginId }
        try {
            persist(nextStates)
        } catch (error: Throwable) {
            if (current.enabled) runCatching { active?.plugin?.onEnable() }
            throw error
        }
        activePlugins.remove(pluginId)
        storedStates.remove(pluginId)
        errors.remove(pluginId)
        provider.uninstall()
    }

    suspend fun openSession(): OmniPluginSession = mutex.withLock {
        ensureInitialized()
        val requiredPluginIds = providers().asSequence()
            .filter { it.descriptor.required }
            .mapTo(linkedSetOf()) { it.descriptor.id }
        val definitions = mutableListOf<OmniPluginToolDefinition>()
        val handlers = mutableListOf<ToolHandler>()
        val failedPluginIds = mutableListOf<String>()

        activePlugins.forEach { (pluginId, active) ->
            val pluginHandlers = mutableListOf<ToolHandler>()
            try {
                active.contribution.toolGroups.forEach { group ->
                    val handler = group.handlerFactory()
                    validateHandler(group, handler)
                    pluginHandlers += handler
                }
                definitions += active.contribution.toolGroups.flatMap { group ->
                    group.definitions.map { definition ->
                        definition.copy(ownerPluginId = pluginId)
                    }
                }
                handlers += pluginHandlers
            } catch (error: Throwable) {
                pluginHandlers.asReversed().forEach { handler ->
                    runCatching { handler.dispose() }
                }
                errors[pluginId] = error.message ?: error.javaClass.simpleName
                failedPluginIds += pluginId
            }
        }

        failedPluginIds.forEach { pluginId ->
            val active = activePlugins.remove(pluginId) ?: return@forEach
            runCatching { active.plugin.onDisable() }
            storedStates[pluginId]?.let { state ->
                storedStates[pluginId] = state.copy(
                    enabled = pluginId in requiredPluginIds,
                )
            }
        }
        if (failedPluginIds.isNotEmpty()) {
            runCatching { persist(storedStates.values) }
        }
        OmniPluginSession(toolDefinitions = definitions, toolHandlers = handlers)
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        val providerMap = providers().associateBy { it.descriptor.id }
        val requiredPluginIds = providerMap.values.asSequence()
            .filter { it.descriptor.required }
            .mapTo(linkedSetOf()) { it.descriptor.id }
        val defaults = defaultEnabledPluginIds.map { pluginId ->
            OmniPluginStoredState(
                pluginId = pluginId,
                enabled = true,
                installPending = true,
            )
        }
        val storedBeforeDefaults = runCatching { stateStore.read() }
            .getOrDefault(emptyList())
        val restored = runCatching { stateStore.readWithDefaults(defaults) }
            .getOrDefault(storedBeforeDefaults)
        val storedPluginIds = storedBeforeDefaults.mapTo(mutableSetOf()) { it.pluginId }
        val newlySeededDefaultIds = restored.asSequence()
            .map { it.pluginId }
            .filter { it in defaultEnabledPluginIds && it !in storedPluginIds }
            .toSet()
        val defaultInstalledPluginIds = providerMap.values.asSequence()
            .filter { it.descriptor.installByDefault }
            .mapTo(linkedSetOf()) { it.descriptor.id }
        val reconciled = restored.associateByTo(linkedMapOf()) { it.pluginId }
        defaultInstalledPluginIds.forEach { pluginId ->
            if (pluginId !in reconciled) {
                reconciled[pluginId] = OmniPluginStoredState(
                    pluginId = pluginId,
                    enabled = false,
                    installPending = true,
                )
            }
        }
        requiredPluginIds.forEach { pluginId ->
            val current = reconciled[pluginId]
            reconciled[pluginId] = current?.copy(enabled = true)
                ?: OmniPluginStoredState(
                    pluginId = pluginId,
                    enabled = true,
                    installPending = true,
                )
        }
        reconciled.values.forEach { state -> storedStates[state.pluginId] = state }
        initialized = true

        reconciled.values.filter { it.enabled || it.installPending }.forEach { state ->
            val provider = providerMap[state.pluginId]
            if (provider == null) {
                storedStates[state.pluginId] = state.copy(enabled = false)
                return@forEach
            }
            runCatching {
                requireCompatible(provider.descriptor)
                val requiresInstall = state.installPending ||
                    state.pluginId in newlySeededDefaultIds
                if (requiresInstall) {
                    provider.install()
                }
                if (state.enabled) {
                    activePlugins[state.pluginId] = activate(provider)
                }
                if (requiresInstall) {
                    storedStates[state.pluginId] = state.copy(installPending = false)
                }
            }.onFailure { error ->
                OmniLog.e(
                    "[OmniPluginPlatform]",
                    "provider_init_failed plugin=${state.pluginId} " +
                        "error=${error.message ?: error.javaClass.simpleName}",
                    error,
                )
                val requiresInstall = state.installPending ||
                    state.pluginId in newlySeededDefaultIds
                if (requiresInstall) {
                    runCatching { provider.uninstall() }
                }
                errors[state.pluginId] = error.message ?: error.javaClass.simpleName
                if (requiresInstall) {
                    storedStates[state.pluginId] = state.copy(
                        enabled = state.enabled || state.pluginId in requiredPluginIds,
                        installPending = true,
                    )
                } else {
                    storedStates[state.pluginId] = state.copy(
                        enabled = state.pluginId in requiredPluginIds,
                    )
                }
            }
        }
        if (storedStates != restored.associateBy { it.pluginId }) {
            runCatching { persist(storedStates.values) }
        }
    }

    private suspend fun activate(provider: OmniPluginProvider): ActivePlugin {
        val plugin = provider.create()
        val contribution = plugin.contribution()
        validateContribution(provider.descriptor.id, contribution)
        contribution.toolGroups.forEach { group ->
            val probe = group.handlerFactory()
            try {
                validateHandler(group, probe)
            } finally {
                runCatching { probe.dispose() }
            }
        }
        try {
            plugin.onEnable()
        } catch (error: Throwable) {
            runCatching { plugin.onDisable() }
            throw error
        }
        return ActivePlugin(plugin = plugin, contribution = contribution)
    }

    private fun validateContribution(
        pluginId: String,
        contribution: OmniPluginContribution
    ) {
        val names = contribution.toolGroups.flatMap { group -> group.definitions.map { it.name } }
        require(names.size == names.toSet().size) {
            "Plugin $pluginId declares duplicate tool names"
        }
        names.forEach { name ->
            require(TOOL_NAME.matches(name)) {
                "Plugin $pluginId declares invalid tool name: $name"
            }
            require(name !in reservedToolNames) {
                "Plugin $pluginId conflicts with reserved tool: $name"
            }
            val owner = activePlugins.entries.firstOrNull { (_, active) ->
                active.contribution.toolGroups.any { group ->
                    group.definitions.any { it.name == name }
                }
            }?.key
            require(owner == null || owner == pluginId) {
                "Plugin $pluginId tool $name conflicts with plugin $owner"
            }
        }
        contribution.toolGroups.forEach { group ->
            require(group.definitions.isNotEmpty()) {
                "Plugin $pluginId contains an empty tool group"
            }
        }
    }

    private fun validateHandler(group: OmniPluginToolGroup, handler: ToolHandler) {
        val expected = group.definitions.mapTo(linkedSetOf()) { it.name }
        require(handler.toolNames == expected) {
            "Plugin handler tools ${handler.toolNames} do not match definitions $expected"
        }
    }

    private fun stateFor(provider: OmniPluginProvider): OmniPluginState {
        val descriptor = provider.descriptor
        val stored = storedStates[descriptor.id]
        val compatible = descriptor.interfaceVersion == OmniPluginContract.CURRENT_INTERFACE_VERSION
        return OmniPluginState(
            descriptor = descriptor,
            installed = stored != null && !stored.installPending,
            enabled = stored?.enabled == true && activePlugins.containsKey(descriptor.id),
            compatible = compatible,
            errorMessage = errors[descriptor.id] ?: if (!compatible) {
                "Requires plugin interface ${descriptor.interfaceVersion}; host supports ${OmniPluginContract.CURRENT_INTERFACE_VERSION}"
            } else {
                null
            }
        )
    }

    private fun providers(): List<OmniPluginProvider> {
        val providers = providerSource().sortedBy { it.descriptor.id }
        val duplicateId = providers.groupBy { it.descriptor.id }
            .entries.firstOrNull { it.value.size > 1 }?.key
        require(duplicateId == null) { "Duplicate plugin provider id: $duplicateId" }
        providers.forEach { validateDescriptor(it.descriptor) }
        return providers
    }

    private fun requireProvider(pluginId: String): OmniPluginProvider {
        return providers().firstOrNull { it.descriptor.id == pluginId }
            ?: throw IllegalArgumentException("Unknown plugin: $pluginId")
    }

    private fun requireCompatible(descriptor: OmniPluginDescriptor) {
        require(descriptor.interfaceVersion == OmniPluginContract.CURRENT_INTERFACE_VERSION) {
            "Plugin ${descriptor.id} requires interface ${descriptor.interfaceVersion}; host supports ${OmniPluginContract.CURRENT_INTERFACE_VERSION}"
        }
    }

    private fun validateDescriptor(descriptor: OmniPluginDescriptor) {
        require(PLUGIN_ID.matches(descriptor.id)) { "Invalid plugin id: ${descriptor.id}" }
        require(descriptor.name.isNotBlank()) { "Plugin ${descriptor.id} has no name" }
        require(descriptor.version.isNotBlank()) { "Plugin ${descriptor.id} has no version" }
        require(descriptor.publisher.isNotBlank()) { "Plugin ${descriptor.id} has no publisher" }
        require(descriptor.downloadSizeBytes >= 0) {
            "Plugin ${descriptor.id} has a negative download size"
        }
    }

    private fun persist(states: Collection<OmniPluginStoredState>) {
        stateStore.write(states.sortedBy { it.pluginId })
    }

    private companion object {
        val PLUGIN_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$")
        val TOOL_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    }
}
