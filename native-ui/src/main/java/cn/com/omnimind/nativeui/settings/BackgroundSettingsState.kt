package cn.com.omnimind.nativeui.settings

import androidx.compose.ui.graphics.ImageBitmap

enum class BackgroundSource(val stored: String) { None("none"), Local("local"), Remote("remote") }
enum class BackgroundTextColorMode(val stored: String) { Auto("auto"), Custom("custom") }
enum class BackgroundPreviewKind { Chat, Workspace }
enum class BackgroundNotice { SaveFailed, ImportFailed, ChangedElsewhere }

/** Values and defaults mirror Flutter's AppBackgroundConfig JSON contract. */
data class BackgroundConfig(
    val enabled: Boolean = false,
    val sourceType: BackgroundSource = BackgroundSource.None,
    val localImagePath: String = "",
    val remoteImageUrl: String = "",
    val blurSigma: Float = 8f,
    val frostOpacity: Float = .18f,
    val brightness: Float = 1f,
    val focalX: Float = 0f,
    val focalY: Float = 0f,
    val imageScale: Float = 1f,
    val chatTextSize: Float = 14f,
    val chatTextColorMode: BackgroundTextColorMode = BackgroundTextColorMode.Auto,
    val chatTextHexColor: String = "",
) {
    val hasResolvedImage: Boolean get() = when (sourceType) {
        BackgroundSource.None -> false
        BackgroundSource.Local -> localImagePath.isNotBlank()
        BackgroundSource.Remote -> remoteImageUrl.isNotBlank()
    }
    val isActive: Boolean get() = enabled && hasResolvedImage
    val imageKey: String get() = when (sourceType) {
        BackgroundSource.None -> ""
        BackgroundSource.Local -> "local:${localImagePath.trim()}"
        BackgroundSource.Remote -> "remote:${remoteImageUrl.trim()}"
    }

    fun toMap(): Map<String, Any> = mapOf(
        "enabled" to enabled, "sourceType" to sourceType.stored,
        "localImagePath" to localImagePath, "remoteImageUrl" to remoteImageUrl,
        "blurSigma" to blurSigma.toDouble(), "frostOpacity" to frostOpacity.toDouble(),
        "brightness" to brightness.toDouble(), "focalX" to focalX.toDouble(), "focalY" to focalY.toDouble(),
        "imageScale" to imageScale.toDouble(), "chatTextSize" to chatTextSize.toDouble(),
        "chatTextColorMode" to chatTextColorMode.stored, "chatTextHexColor" to chatTextHexColor,
    )

    companion object {
        fun fromMap(raw: Map<String, *>): BackgroundConfig {
            fun number(name: String, default: Float) = (raw[name] as? Number)?.toFloat() ?: default
            return BackgroundConfig(
                enabled = raw["enabled"] == true,
                sourceType = BackgroundSource.entries.firstOrNull { it.stored == raw["sourceType"] }
                    ?: BackgroundSource.None,
                localImagePath = raw["localImagePath"] as? String ?: "",
                remoteImageUrl = raw["remoteImageUrl"] as? String ?: "",
                blurSigma = number("blurSigma", 8f), frostOpacity = number("frostOpacity", .18f),
                brightness = number("brightness", 1f), focalX = number("focalX", 0f),
                focalY = number("focalY", 0f), imageScale = number("imageScale", 1f),
                chatTextSize = number("chatTextSize", 14f),
                chatTextColorMode = BackgroundTextColorMode.entries.firstOrNull {
                    it.stored == raw["chatTextColorMode"]
                } ?: BackgroundTextColorMode.Auto,
                chatTextHexColor = raw["chatTextHexColor"] as? String ?: "",
            )
        }
    }
}

data class BackgroundSettingsState(
    val loaded: Boolean = false,
    val config: BackgroundConfig = BackgroundConfig(),
    val committed: BackgroundConfig = BackgroundConfig(),
    val previewImage: ImageBitmap? = null,
    val sampledLuminance: Float = .72f,
    val imageLoading: Boolean = false,
    val imageLoadFailed: Boolean = false,
    val saving: Boolean = false,
    val importing: Boolean = false,
    val notice: BackgroundNotice? = null,
)

data class BackgroundSettingsActions(
    val setEnabled: (Boolean) -> Unit,
    val setSource: (BackgroundSource) -> Unit,
    val setRemoteUrl: (String) -> Unit,
    val setBlur: (Float) -> Unit,
    val setFrost: (Float) -> Unit,
    val setBrightness: (Float) -> Unit,
    val setChatTextSize: (Float) -> Unit,
    val setTextColor: (mode: BackgroundTextColorMode, hex: String) -> Unit,
    val setViewport: (focalX: Float, focalY: Float, scale: Float) -> Unit,
    val importImage: (uri: android.net.Uri) -> Unit,
    val flush: () -> Unit,
    val clearNotice: () -> Unit,
)
