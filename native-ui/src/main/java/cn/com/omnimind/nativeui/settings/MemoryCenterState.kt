package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

enum class MemoryTab { Local, Cloud }

/** Presentation row for a short-memory entry; the workspace files stay the owner. */
@Immutable
data class ShortMemoryItem(
    val id: String,
    val title: String,
    val description: String?,
    val timeLabel: String,
)

/** Presentation row for a MEMORY.md bullet line; id is `base64url(index|memory)`. */
@Immutable
data class LongMemoryItem(
    val id: String,
    val memory: String,
    val timeLabel: String,
)

@Immutable
data class MemoryCenterState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val tab: MemoryTab = MemoryTab.Local,
    val shortMemories: List<ShortMemoryItem> = emptyList(),
    val longMemories: List<LongMemoryItem> = emptyList(),
    val longLoading: Boolean = false,
    val longFailed: Boolean = false,
    val longErrorDetail: String? = null,
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val confirmDeleteSelection: Boolean = false,
    val detailItemId: String? = null,
    val editorOpen: Boolean = false,
    val editorItemId: String? = null,
    val confirmDeleteLongId: String? = null,
    val mutating: Boolean = false,
    @StringRes val notice: Int? = null,
    val noticeArg: String? = null,
)

data class MemoryCenterActions(
    val setTab: (MemoryTab) -> Unit,
    val refreshLong: () -> Unit,
    val enterSelection: (String) -> Unit,
    val toggleSelection: (String) -> Unit,
    val exitSelection: () -> Unit,
    val toggleSelectAll: () -> Unit,
    val showDeleteSelection: (Boolean) -> Unit,
    val deleteSelectionConfirmed: () -> Unit,
    val openDetail: (String) -> Unit,
    val closeDetail: () -> Unit,
    val openEditor: (String?) -> Unit,
    val closeEditor: () -> Unit,
    val saveEditor: (String) -> Unit,
    val showDeleteLong: (String?) -> Unit,
    val deleteLongConfirmed: () -> Unit,
    val dismissNotice: () -> Unit,
)
