package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.plugin.NativePluginRepository
import cn.com.omnimind.bot.plugin.OmniPluginState
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.PluginDetailActions
import cn.com.omnimind.nativeui.settings.PluginDetailItem
import cn.com.omnimind.nativeui.settings.PluginDetailState
import cn.com.omnimind.nativeui.settings.PluginPresentationAction
import cn.com.omnimind.nativeui.settings.PluginReadyGuide
import cn.com.omnimind.nativeui.settings.PluginUsageItem
import cn.com.omnimind.nativeui.settings.PluginVlmStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Plugin detail page. `OmniPluginHost` keeps install/update downloads and
 * enable state; this ViewModel triggers operations and projects results. The
 * readiness banner reads the shared GUI-scene VLM projection.
 */
internal class NativePluginDetailViewModel(
    context: Context,
    private val pluginId: String,
) : ViewModel() {
    private val repository = NativePluginRepository(context)
    private val mutableState = MutableStateFlow(PluginDetailState())
    val state = mutableState.asStateFlow()

    val actions = PluginDetailActions(
        retry = { load(force = true) },
        install = { runStateAction(R.string.omni_plugin_installed_msg, R.string.omni_plugin_install_failed) { repository.install(pluginId) } },
        update = { runStateAction(R.string.omni_plugin_updated_msg, R.string.omni_plugin_update_failed) { repository.update(pluginId) } },
        setEnabled = { enabled ->
            runStateAction(
                if (enabled) R.string.omni_plugin_enabled_msg else R.string.omni_plugin_disabled_msg,
                R.string.omni_plugin_toggle_failed,
            ) { repository.setEnabled(pluginId, enabled) }
        },
        showUninstallConfirm = { show ->
            if (!state.value.busy) mutableState.update { it.copy(confirmUninstall = show) }
        },
        uninstallConfirmed = ::uninstall,
        openAction = ::openAction,
        consumePluginRoute = { mutableState.update { it.copy(pendingPluginRoute = null) } },
        dismissNotice = { mutableState.update { it.copy(notice = null, noticeArg = null) } },
    )

    fun load(force: Boolean = false) {
        val current = state.value
        if (current.loading || current.busy) return
        if (current.loaded && !force) return
        mutableState.update { it.copy(loading = true, notice = null) }
        viewModelScope.launch { reload() }
    }

    /** Entry lifecycle resume: re-read state after a compatibility page visit. */
    fun resume() {
        if (state.value.busy || state.value.loading) return
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        try {
            val plugin = withContext(Dispatchers.IO) { repository.get(pluginId) }
            if (plugin == null) {
                mutableState.update {
                    it.copy(loading = false, loaded = true, notFound = true, plugin = null)
                }
                return
            }
            val vlm = if (usesVlmReadiness(plugin)) {
                withContext(Dispatchers.IO) { repository.vlmReadiness() }.let { status ->
                    PluginVlmStatus(status.debugBuild, status.providerConfigured, status.providerName, status.model)
                }
            } else {
                null
            }
            mutableState.update {
                it.copy(loading = false, loaded = true, notFound = false, plugin = toItem(plugin, vlm))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OmniLog.e("NativePluginDetail", "Plugin load failed", error)
            mutableState.update {
                it.copy(loading = false, loaded = true, notFound = it.plugin == null,
                    notice = if (it.plugin == null) null else R.string.omni_plugin_load_failed)
            }
        }
    }

    private fun runStateAction(
        successNotice: Int,
        failureNotice: Int,
        operation: suspend () -> OmniPluginState,
    ) {
        val plugin = state.value.plugin ?: return
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { operation() }
                mutableState.update {
                    it.copy(busy = false, plugin = toItem(updated, it.plugin?.vlm))
                }
                notice(successNotice, plugin.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativePluginDetail", "Plugin state action failed", error)
                mutableState.update { it.copy(busy = false) }
                notice(failureNotice)
            }
        }
    }

    private fun uninstall() {
        val plugin = state.value.plugin ?: return
        if (state.value.busy) return
        mutableState.update { it.copy(confirmUninstall = false, busy = true, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.uninstall(pluginId) }
                val updated = withContext(Dispatchers.IO) { repository.get(pluginId) }
                mutableState.update {
                    it.copy(busy = false, plugin = updated?.let { next -> toItem(next, it.plugin?.vlm) })
                }
                notice(R.string.omni_plugin_uninstalled_msg, plugin.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativePluginDetail", "Plugin uninstall failed", error)
                mutableState.update { it.copy(busy = false) }
                notice(R.string.omni_plugin_uninstall_failed)
            }
        }
    }

    /** Plugin-declared routes open the existing Flutter compatibility host. */
    private fun openAction(action: PluginPresentationAction) {
        if (action.route.isBlank() || !action.route.startsWith("/")) return
        mutableState.update { it.copy(pendingPluginRoute = action.route) }
    }

    private fun notice(resource: Int, arg: String? = null) {
        mutableState.update { it.copy(notice = resource, noticeArg = arg) }
    }

    private fun usesVlmReadiness(plugin: OmniPluginState): Boolean =
        (plugin.descriptor.presentation["readiness"] as? JsonPrimitive)?.content == "vlm_provider"

    private fun toItem(state: OmniPluginState, vlm: PluginVlmStatus?): PluginDetailItem {
        val descriptor = state.descriptor
        val presentation = descriptor.presentation
        val capabilityLabels = presentation["capabilityLabels"] as? JsonObject
        return PluginDetailItem(
            id = descriptor.id,
            name = descriptor.name,
            version = descriptor.version,
            interfaceVersion = descriptor.interfaceVersion,
            description = repository.localized(presentation["description"])
                .ifEmpty { descriptor.description.trim() },
            publisher = descriptor.publisher,
            kind = descriptor.kind.wireName,
            downloadSizeBytes = descriptor.downloadSizeBytes,
            capabilities = descriptor.capabilities.map { capability ->
                repository.localized(capabilityLabels?.get(capability)).ifEmpty { capability }
            },
            required = descriptor.required,
            installed = state.installed,
            enabled = state.enabled,
            compatible = state.compatible,
            errorMessage = state.errorMessage?.trim()?.takeIf(String::isNotEmpty),
            usage = (presentation["usage"] as? JsonArray).orEmpty().mapNotNull { raw ->
                val item = raw as? JsonObject ?: return@mapNotNull null
                val title = repository.localized(item["title"])
                if (title.isEmpty()) return@mapNotNull null
                PluginUsageItem(
                    icon = (item["icon"] as? JsonPrimitive)?.content.orEmpty(),
                    title = title,
                    description = repository.localized(item["description"]),
                )
            },
            readyGuide = (presentation["ready"] as? JsonObject)?.let { ready ->
                val title = repository.localized(ready["title"]).ifEmpty { descriptor.name }
                val steps = (ready["steps"] as? JsonArray).orEmpty()
                    .map { repository.localized(it) }
                    .filter(String::isNotEmpty)
                val guideActions = (ready["actions"] as? JsonArray).orEmpty()
                    .mapNotNull(::parseAction)
                if (steps.isEmpty() && guideActions.isEmpty()) null else PluginReadyGuide(
                    key = (ready["key"] as? JsonPrimitive)?.content?.trim()
                        ?.takeIf(String::isNotEmpty) ?: "plugin-ready-guide-${descriptor.id}",
                    title = title,
                    message = repository.localized(ready["message"]),
                    steps = steps,
                    actions = guideActions,
                )
            },
            installedAction = parseAction(presentation["installedAction"]),
            usesVlmReadiness = usesVlmReadiness(state),
            vlm = vlm,
        )
    }

    private fun parseAction(raw: JsonElement?): PluginPresentationAction? {
        val action = raw as? JsonObject ?: return null
        val route = (action["route"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (route.isEmpty()) return null
        val label = repository.localized(action["label"])
        return PluginPresentationAction(
            icon = (action["icon"] as? JsonPrimitive)?.content.orEmpty(),
            label = label,
            route = route,
            requiresReadiness = (action["requiresReadiness"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    class Factory(context: Context, private val pluginId: String) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativePluginDetailViewModel(appContext, pluginId) as T
    }
}
