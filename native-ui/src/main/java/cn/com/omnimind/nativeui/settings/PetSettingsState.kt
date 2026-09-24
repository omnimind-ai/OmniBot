package cn.com.omnimind.nativeui.settings

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap

data class PetAppearanceItem(
    val id: String,
    val name: String,
    val description: String,
    val previewPath: String,
    val playbackPath: String,
    val builtIn: Boolean,
    val animationLabel: String,
)

enum class PetNotice { ReadFailed, SelectionFailed, ImportFailed }

data class PetSettingsState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val options: List<PetAppearanceItem> = emptyList(),
    val selectedId: String = "builtin:xiaowan",
    val previewImages: Map<String, ImageBitmap> = emptyMap(),
    val previewRevision: Int = 0,
    val notice: PetNotice? = null,
)

data class PetSettingsActions(
    val refresh: () -> Unit,
    val select: (String) -> Unit,
    val importPackage: (Uri) -> Unit,
    val loadPreview: (String) -> Unit,
    val clearNotice: () -> Unit,
)
