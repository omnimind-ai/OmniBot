package cn.com.omnimind.bot.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.NativeMemoryCenterRepository
import cn.com.omnimind.bot.agent.WorkspaceShortMemoryEntry
import cn.com.omnimind.bot.ui.nativehome.resolveNativeHomeLocale
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.LongMemoryItem
import cn.com.omnimind.nativeui.settings.MemoryCenterActions
import cn.com.omnimind.nativeui.settings.MemoryCenterState
import cn.com.omnimind.nativeui.settings.MemoryTab
import cn.com.omnimind.nativeui.settings.ShortMemoryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Memory center page. `WorkspaceMemoryService` keeps the workspace files;
 * short-memory deletion keeps its unchanged-snapshot validation. The
 * suggestion banner's LLM flow is disabled upstream (the Flutter page returns
 * before generating), so this page renders the static greeting and never calls
 * `generateMemoryGreeting`.
 */
internal class NativeMemoryCenterViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val repository = NativeMemoryCenterRepository(appContext)
    private val mutableState = MutableStateFlow(MemoryCenterState())
    private var loadGeneration = 0
    private var shortEntriesById: Map<String, WorkspaceShortMemoryEntry> = emptyMap()
    val state = mutableState.asStateFlow()

    val actions = MemoryCenterActions(
        setTab = { tab ->
            if (!state.value.selectionMode) mutableState.update { it.copy(tab = tab) }
        },
        refreshLong = { loadLongTerm(force = true) },
        enterSelection = { id ->
            mutableState.update { it.copy(selectionMode = true, selectedIds = setOf(id)) }
        },
        toggleSelection = { id ->
            mutableState.update {
                it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id)
            }
        },
        exitSelection = {
            mutableState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
        },
        toggleSelectAll = ::toggleSelectAll,
        showDeleteSelection = { show ->
            if (!state.value.mutating) mutableState.update { it.copy(confirmDeleteSelection = show) }
        },
        deleteSelectionConfirmed = ::deleteSelectionConfirmed,
        openDetail = { id -> mutableState.update { it.copy(detailItemId = id) } },
        closeDetail = { mutableState.update { it.copy(detailItemId = null) } },
        openEditor = { id ->
            if (!state.value.mutating) {
                mutableState.update { it.copy(detailItemId = null, editorOpen = true, editorItemId = id) }
            }
        },
        closeEditor = { mutableState.update { it.copy(editorOpen = false, editorItemId = null) } },
        saveEditor = ::saveEditor,
        showDeleteLong = { id ->
            if (!state.value.mutating) {
                mutableState.update { it.copy(detailItemId = null, confirmDeleteLongId = id) }
            }
        },
        deleteLongConfirmed = ::deleteLongConfirmed,
        dismissNotice = { mutableState.update { it.copy(notice = null, noticeArg = null) } },
    )

    fun load() {
        if (state.value.loading) return
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch { reload() }
    }

    /** Silent refresh on entry resume; selection survives, as on the Flutter page. */
    fun resume() {
        if (state.value.mutating) return
        viewModelScope.launch { reload(silent = true) }
    }

    private suspend fun reload(silent: Boolean = false) {
        val generation = ++loadGeneration
        try {
            val (shortEntries, longItems) = withContext(Dispatchers.IO) {
                repository.listShortMemories() to runCatching { repository.listLongTermMemories() }
            }
            if (generation != loadGeneration) return
            shortEntriesById = shortEntries.associateBy { it.id }
            val longFailed = longItems.isFailure
            mutableState.update {
                it.copy(
                    loaded = true,
                    loading = false,
                    shortMemories = shortEntries.map(::toShortItem),
                    longMemories = longItems.getOrDefault(emptyList()).map { item ->
                        LongMemoryItem(item.id, item.memory, text(R.string.omni_memory_time_just_now))
                    },
                    longLoading = false,
                    longFailed = longFailed,
                    longErrorDetail = longItems.exceptionOrNull()?.message?.removePrefix("Exception: "),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OmniLog.e("NativeMemoryCenter", "Memory center load failed", error)
            if (generation != loadGeneration) return
            mutableState.update {
                it.copy(
                    loading = false,
                    notice = if (silent) it.notice else R.string.omni_memory_short_delete_failed,
                )
            }
        }
    }

    private fun loadLongTerm(force: Boolean) {
        if (state.value.mutating) return
        mutableState.update { it.copy(longLoading = true) }
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { repository.listLongTermMemories() }
                mutableState.update {
                    it.copy(
                        longLoading = false,
                        longFailed = false,
                        longErrorDetail = null,
                        longMemories = items.map { item ->
                            LongMemoryItem(item.id, item.memory, text(R.string.omni_memory_time_just_now))
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeMemoryCenter", "Long-term memory load failed", error)
                mutableState.update {
                    it.copy(longLoading = false, longFailed = true,
                        longErrorDetail = error.message?.removePrefix("Exception: "))
                }
            }
        }
    }

    private fun toggleSelectAll() {
        val current = state.value
        val all = current.shortMemories.map { it.id }.toSet()
        mutableState.update {
            it.copy(selectedIds = if (it.selectedIds.size == all.size) emptySet() else all)
        }
    }

    private fun deleteSelectionConfirmed() {
        val current = state.value
        if (current.mutating || current.selectedIds.isEmpty()) return
        val entries = current.selectedIds.mapNotNull { shortEntriesById[it] }
        if (entries.size != current.selectedIds.size) {
            // A stale selection must not delete against a shifted snapshot.
            mutableState.update { it.copy(confirmDeleteSelection = false) }
            viewModelScope.launch { reload(silent = true) }
            mutableState.update { it.copy(notice = R.string.omni_memory_short_delete_failed) }
            return
        }
        mutableState.update { it.copy(confirmDeleteSelection = false, mutating = true, notice = null) }
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { repository.deleteShortMemories(entries) }
                if (count != entries.size) throw IllegalStateException("Incomplete deletion")
                mutableState.update {
                    it.copy(selectionMode = false, selectedIds = emptySet(), notice = R.string.omni_memory_short_deleted)
                }
                reload(silent = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeMemoryCenter", "Short-memory delete failed", error)
                reload(silent = true)
                mutableState.update { it.copy(notice = R.string.omni_memory_short_delete_failed) }
            } finally {
                mutableState.update { it.copy(mutating = false) }
            }
        }
    }

    private fun saveEditor(text: String) {
        val current = state.value
        if (current.mutating) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val editId = current.editorItemId
        mutableState.update { it.copy(editorOpen = false, editorItemId = null, mutating = true, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (editId == null) repository.appendLongTermMemory(trimmed)
                    else repository.updateLongTermMemory(editId, trimmed)
                }
                mutableState.update {
                    it.copy(notice = if (editId == null) R.string.omni_memory_long_added
                        else R.string.omni_memory_save_changes)
                }
                reload(silent = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeMemoryCenter", "Long-term memory save failed", error)
                mutableState.update {
                    it.copy(notice = R.string.omni_memory_long_failed,
                        noticeArg = error.message?.removePrefix("Exception: "))
                }
            } finally {
                mutableState.update { it.copy(mutating = false) }
            }
        }
    }

    private fun deleteLongConfirmed() {
        val memoryId = state.value.confirmDeleteLongId ?: return
        if (state.value.mutating) return
        mutableState.update { it.copy(confirmDeleteLongId = null, mutating = true, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.deleteLongTermMemory(memoryId) }
                mutableState.update { it.copy(notice = R.string.omni_memory_long_deleted) }
                reload(silent = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeMemoryCenter", "Long-term memory delete failed", error)
                mutableState.update {
                    it.copy(notice = R.string.omni_memory_long_failed,
                        noticeArg = error.message?.removePrefix("Exception: "))
                }
            } finally {
                mutableState.update { it.copy(mutating = false) }
            }
        }
    }

    private fun toShortItem(entry: WorkspaceShortMemoryEntry): ShortMemoryItem {
        val text = normalizeShortMemoryText(entry.content)
        val truncated = text.length > 26
        val timestamp = entry.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        return ShortMemoryItem(
            id = entry.id,
            title = if (truncated) text.substring(0, 26) + "..." else text,
            description = if (truncated) text else null,
            timeLabel = shortTimeLabel(timestamp),
        )
    }

    /** Quick-log prefix normalization is Chinese-only, matching the Flutter page. */
    private fun normalizeShortMemoryText(raw: String): String {
        val text = raw.trim()
        val locale = currentLocale()
        if (locale.language == "en") return text
        val quickLogPrefix = Regex("^Quick log[:：]?\\s*")
        if (quickLogPrefix.containsMatchIn(text)) {
            val content = text.replaceFirst(quickLogPrefix, "")
            return if (content.isEmpty()) text(R.string.omni_memory_quick_log_empty)
                else text(R.string.omni_memory_quick_log, content)
        }
        return text
    }

    private fun shortTimeLabel(timestampMillis: Long): String {
        val zone = ZoneId.systemDefault()
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMillis), zone)
        val date = dateTime.toLocalDate()
        val today = LocalDate.now(zone)
        val time = dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        return when (date) {
            today -> text(R.string.omni_memory_time_today, time)
            today.minusDays(1) -> text(R.string.omni_memory_time_yesterday, time)
            else -> dateTime.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
        }
    }

    private fun currentLocale(): java.util.Locale {
        val preferences =
            appContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        return resolveNativeHomeLocale(preferences.getString("flutter.language_option", "system"))
    }

    private fun text(resource: Int, vararg args: Any): String {
        val configuration = Configuration(appContext.resources.configuration)
            .apply { setLocale(currentLocale()) }
        return appContext.createConfigurationContext(configuration).getString(resource, *args)
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativeMemoryCenterViewModel(appContext) as T
    }
}
