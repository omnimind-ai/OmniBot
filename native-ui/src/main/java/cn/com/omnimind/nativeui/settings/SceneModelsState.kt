package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap

data class SceneModelRow(
    val id: String,
    val description: String,
    val defaultModel: String,
    val providerId: String? = null,
    val providerName: String? = null,
    val modelId: String? = null,
) {
    val capability: String get() = if (id == "scene.memory.embedding") "embedding" else "text"
}

data class SceneProviderGroup(
    val id: String,
    val name: String,
    val configured: Boolean,
    val modelsByCapability: Map<String, List<String>> = emptyMap(),
    val loadingCapabilities: Set<String> = emptySet(),
    val failedCapabilities: Set<String> = emptySet(),
)

data class SceneModelsState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val scenes: List<SceneModelRow> = emptyList(),
    val providers: List<SceneProviderGroup> = emptyList(),
    val savingSceneId: String? = null,
    val voiceBusy: Boolean = false,
    val autoPlay: Boolean = false,
    val voiceAvailable: Boolean = false,
    val voiceId: String = "default_zh",
    val stylePreset: String = "",
    val avatar: ImageBitmap? = null,
    @StringRes val notice: Int? = null,
)

data class SceneModelsActions(
    val refresh: () -> Unit,
    val selectModel: (sceneId: String, providerId: String, modelId: String) -> Unit,
    val restoreDefault: (sceneId: String) -> Unit,
    val setAutoPlay: (Boolean) -> Unit,
    val dismissNotice: () -> Unit,
)
