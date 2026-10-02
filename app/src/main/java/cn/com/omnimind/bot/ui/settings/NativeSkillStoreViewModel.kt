package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.SkillIndexService
import cn.com.omnimind.bot.agent.SkillIndexEntry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.SkillItem
import cn.com.omnimind.nativeui.settings.SkillStoreActions
import cn.com.omnimind.nativeui.settings.SkillStoreState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Skill store page. `SkillIndexService` (registry file + workspace skill
 * directories) stays the only storage/sync owner, including the official
 * repository git sync; this ViewModel only triggers operations and projects
 * results. Sorting matches the owner and the Flutter page: installed first,
 * then built-in/official/other, then name.
 */
internal class NativeSkillStoreViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(SkillStoreState())
    val state = mutableState.asStateFlow()

    val actions = SkillStoreActions(
        refresh = { load(force = true) },
        setQuery = { query -> mutableState.update { it.copy(query = query) } },
        toggle = ::toggle,
        installBuiltin = ::installBuiltin,
        confirmDelete = { id ->
            if (state.value.busyIds.isEmpty()) mutableState.update { it.copy(deletingId = id) }
        },
        deleteConfirmed = ::deleteConfirmed,
        syncOfficial = ::syncOfficial,
        dismissNotice = { mutableState.update { it.copy(notice = null, noticeArg = null) } },
    )

    /** The channel constructs the service the same way; no second store. */
    private fun service() = SkillIndexService(appContext, AgentWorkspaceManager(appContext))

    fun load(force: Boolean = false) {
        val current = state.value
        if (current.loading || current.busyIds.isNotEmpty() || current.syncing) return
        if (current.loaded && !force) return
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val skills = withContext(Dispatchers.IO) { service().listSkillsForManagement() }
                mutableState.update {
                    it.copy(loaded = true, loading = false, skills = skills.map(::toItem))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeSkillStore", "Skill list load failed", error)
                mutableState.update {
                    it.copy(loading = false, notice = R.string.omni_skill_load_failed)
                }
            }
        }
    }

    /** Entry lifecycle resume: pick up changes made through the Flutter page. */
    fun resume() {
        if (state.value.busyIds.isNotEmpty() || state.value.syncing || state.value.loading) return
        load(force = true)
    }

    private fun toggle(skillId: String, enabled: Boolean) {
        val skill = state.value.skills.firstOrNull { it.id == skillId } ?: return
        operate(skillId) {
            val updated = service().setSkillEnabled(skillId, enabled)
            mutableState.update { state ->
                state.copy(skills = state.skills.map { if (it.id == skillId) toItem(updated) else it })
            }
            notice(
                if (enabled) R.string.omni_skill_enabled_msg else R.string.omni_skill_disabled_msg,
                skill.name,
            )
        }
    }

    private fun installBuiltin(skillId: String) {
        val skill = state.value.skills.firstOrNull { it.id == skillId } ?: return
        if (!skill.isBuiltin || skill.installed) return
        operate(skillId, failure = R.string.omni_skill_install_failed) {
            val installed = service().installBuiltinSkill(skillId)
            mutableState.update { state ->
                state.copy(skills = state.skills
                    .map { if (it.id == skillId) toItem(installed) else it }
                    .sortedWith(skillComparator))
            }
            notice(R.string.omni_skill_installed_msg, skill.name)
        }
    }

    private fun deleteConfirmed() {
        val skillId = state.value.deletingId ?: return
        mutableState.update { it.copy(deletingId = null) }
        operate(skillId, failure = R.string.omni_skill_delete_failed) {
            val deleted = service().deleteSkill(skillId)
            if (!deleted) throw IllegalStateException("Skill delete rejected")
            val skills = service().listSkillsForManagement()
            mutableState.update { it.copy(skills = skills.map(::toItem)) }
            notice(R.string.omni_skill_deleted)
        }
    }

    private fun syncOfficial() {
        if (state.value.syncing || state.value.busyIds.isNotEmpty() || state.value.loading) return
        mutableState.update { it.copy(syncing = true, notice = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { service().syncOfficialSkillsRepository() }
                mutableState.update {
                    it.copy(skills = result.skills.map(::toItem).sortedWith(skillComparator))
                }
                notice(R.string.omni_skill_sync_success, result.skillCount.toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeSkillStore", "Official skills sync failed", error)
                notice(R.string.omni_skill_sync_failed)
            } finally {
                mutableState.update { it.copy(syncing = false) }
            }
        }
    }

    private fun operate(skillId: String, failure: Int = R.string.omni_skill_toggle_failed,
        operation: suspend () -> Unit) {
        if (skillId in state.value.busyIds || state.value.syncing) return
        mutableState.update { it.copy(busyIds = it.busyIds + skillId, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeSkillStore", "Skill operation failed", error)
                notice(failure)
            } finally {
                mutableState.update { it.copy(busyIds = it.busyIds - skillId) }
            }
        }
    }

    private fun notice(resource: Int, arg: String? = null) {
        mutableState.update { it.copy(notice = resource, noticeArg = arg) }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativeSkillStoreViewModel(appContext) as T
    }

    private companion object {
        val skillComparator = compareByDescending<SkillItem> { it.installed }
            .thenBy { if (it.isBuiltin) 0 else if (it.isOfficial) 1 else 2 }
            .thenBy { it.name.lowercase() }
    }
}

private fun toItem(entry: SkillIndexEntry) = SkillItem(
    id = entry.id,
    name = entry.name,
    description = entry.description,
    shellSkillFilePath = entry.shellSkillFilePath,
    source = entry.source,
    installed = entry.installed,
    enabled = entry.enabled,
)
