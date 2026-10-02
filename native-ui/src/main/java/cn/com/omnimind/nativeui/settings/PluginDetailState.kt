package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

@Immutable
data class PluginPresentationAction(
    val icon: String,
    val label: String,
    val route: String,
    val requiresReadiness: Boolean = false,
)

@Immutable
data class PluginUsageItem(val icon: String, val title: String, val description: String)

@Immutable
data class PluginReadyGuide(
    val key: String,
    val title: String,
    /** Resolved text; an empty value means the VLM status banner decides. */
    val message: String,
    val steps: List<String>,
    val actions: List<PluginPresentationAction>,
)

@Immutable
data class PluginVlmStatus(
    val debugBuild: Boolean,
    val providerConfigured: Boolean,
    val providerName: String,
    val model: String,
)

/** Presentation snapshot; the platform store stays with OmniPluginHost. */
@Immutable
data class PluginDetailItem(
    val id: String,
    val name: String,
    val version: String,
    val interfaceVersion: Int,
    val description: String,
    val publisher: String,
    val kind: String,
    val downloadSizeBytes: Long,
    val capabilities: List<String>,
    val required: Boolean,
    val installed: Boolean,
    val enabled: Boolean,
    val compatible: Boolean,
    val errorMessage: String?,
    val usage: List<PluginUsageItem>,
    val readyGuide: PluginReadyGuide?,
    val installedAction: PluginPresentationAction?,
    val usesVlmReadiness: Boolean,
    val vlm: PluginVlmStatus?,
)

@Immutable
data class PluginDetailState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val notFound: Boolean = false,
    val busy: Boolean = false,
    val plugin: PluginDetailItem? = null,
    val confirmUninstall: Boolean = false,
    val pendingPluginRoute: String? = null,
    @StringRes val notice: Int? = null,
    val noticeArg: String? = null,
)

data class PluginDetailActions(
    val retry: () -> Unit,
    val install: () -> Unit,
    val update: () -> Unit,
    val setEnabled: (Boolean) -> Unit,
    val showUninstallConfirm: (Boolean) -> Unit,
    val uninstallConfirmed: () -> Unit,
    val openAction: (PluginPresentationAction) -> Unit,
    val consumePluginRoute: () -> Unit,
    val dismissNotice: () -> Unit,
)
