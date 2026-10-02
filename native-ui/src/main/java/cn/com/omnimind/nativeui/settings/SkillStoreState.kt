package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/** Presentation row; the registry/workspace store stays with SkillIndexService. */
@Immutable
data class SkillItem(
    val id: String,
    val name: String,
    val description: String,
    val shellSkillFilePath: String,
    val source: String,
    val installed: Boolean,
    val enabled: Boolean,
) {
    val isBuiltin: Boolean get() = source == "builtin"
    val isOfficial: Boolean get() = source == "official"
}

@Immutable
data class SkillStoreState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val skills: List<SkillItem> = emptyList(),
    val query: String = "",
    val busyIds: Set<String> = emptySet(),
    val syncing: Boolean = false,
    val deletingId: String? = null,
    @StringRes val notice: Int? = null,
    val noticeArg: String? = null,
)

data class SkillStoreActions(
    val refresh: () -> Unit,
    val setQuery: (String) -> Unit,
    val toggle: (String, Boolean) -> Unit,
    val installBuiltin: (String) -> Unit,
    val confirmDelete: (String?) -> Unit,
    val deleteConfirmed: () -> Unit,
    val syncOfficial: () -> Unit,
    val dismissNotice: () -> Unit,
)
