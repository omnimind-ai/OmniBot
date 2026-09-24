package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.bot.storage.StorageUsageRepository
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.StorageBreakdown
import cn.com.omnimind.nativeui.settings.StorageCategory
import cn.com.omnimind.nativeui.settings.StorageHistoryPoint
import cn.com.omnimind.nativeui.settings.StorageStrategy
import cn.com.omnimind.nativeui.settings.StorageSummary
import cn.com.omnimind.nativeui.settings.StorageUsageActions
import cn.com.omnimind.nativeui.settings.StorageUsageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Adapts the existing Flutter-compatible storage contract to native presentation state. */
internal class NativeStorageUsageViewModel(private val context: Context,
    private val repository: StorageUsageRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(StorageUsageState())
    val state = mutableState.asStateFlow()
    val actions = StorageUsageActions(::refresh, ::clearCategory, ::runStrategy,
        { mutableState.value = mutableState.value.copy(notice = null) })

    fun refresh() {
        if (state.value.busy) return
        mutableState.value = state.value.copy(busy = true, error = false)
        viewModelScope.launch {
            try {
                mutableState.value = StorageUsageState(loaded = true,
                    summary = repository.summary().toStorageSummary())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.value = state.value.copy(busy = false, loaded = true, error = true)
            }
        }
    }

    private fun clearCategory(id: String, olderThanDays: Int?) = perform {
        repository.clear(id, olderThanDays)
    }

    private fun runStrategy(id: String) = perform { repository.applyStrategy(id, null, 0L) }

    private fun perform(operation: suspend () -> Map<String, Any?>) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(busy = true, notice = null)
        viewModelScope.launch {
            try {
                val result = operation()
                val summary = result["summary"].asMap()?.toStorageSummary() ?: repository.summary().toStorageSummary()
                val released = (result["releasedBytes"] as? Number)?.toLong() ?: 0L
                val failures = (result["failedPaths"] as? List<*>)?.size ?: 0
                val actionFailures = (result["actionResults"] as? List<*>)?.count { action ->
                    action.asMap()?.get("success") != true
                } ?: 0
                val success = result["success"] == true && failures == 0 && actionFailures == 0
                val hint = (result["manualActionHint"] as? String)?.takeIf { it.isNotBlank() }
                val actionHints = (result["actionResults"] as? List<*>)?.mapNotNull { action ->
                    val item = action.asMap() ?: return@mapNotNull null
                    if (item["success"] == true) null else (item["manualActionHint"] as? String)
                        ?.takeIf { it.isNotBlank() }
                }?.distinct().orEmpty()
                val notice = buildString {
                    append(context.getString(R.string.omni_storage_released_bytes, released))
                    if (!success) append("\n${context.getString(R.string.omni_storage_partial)}")
                    if (hint != null) append("\n$hint")
                    actionHints.forEach { append("\n$it") }
                }
                mutableState.value = StorageUsageState(loaded = true, summary = summary, notice = notice)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.value = state.value.copy(busy = false,
                    notice = context.getString(R.string.omni_storage_failed))
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativeStorageUsageViewModel(appContext, StorageUsageRepository.get(appContext)) as T
    }
}

private fun Any?.asMap(): Map<*, *>? = this as? Map<*, *>
private fun Map<*, *>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
private fun Map<*, *>.string(key: String): String = this[key] as? String ?: ""
private fun Map<*, *>.maps(key: String): List<Map<*, *>> =
    (this[key] as? List<*>)?.mapNotNull { it.asMap() } ?: emptyList()

private fun Map<*, *>.toStorageSummary(): StorageSummary {
    val trend = this["trend"].asMap().orEmpty()
    return StorageSummary(
        generatedAt = long("generatedAt"), totalBytes = long("totalBytes"), binaryBytes = long("appBinaryBytes"),
        userDataBytes = long("userDataBytes"), cacheBytes = long("cacheBytes"),
        cleanableBytes = long("cleanableBytes"), metricsSource = string("metricsSource"),
        packageName = string("packageName"), scanTotalBytes = long("scanTotalBytes"),
        systemTotalBytes = long("systemTotalBytes"),
        hasPrevious = trend["hasPrevious"] == true,
        deltaTotalBytes = trend.long("deltaTotalBytes"),
        deltaCleanableBytes = trend.long("deltaCleanableBytes"),
        history = maps("history").map { StorageHistoryPoint(it.long("generatedAt"),
            it.long("totalBytes"), it.long("cleanableBytes")) },
        strategies = maps("strategyPresets").map { StorageStrategy(it.string("id"),
            it.string("name"), it.string("description"), it.string("riskLevel"),
            (it["olderThanDays"] as? Number)?.toInt(), it.long("targetReleaseBytes")) },
        categories = maps("categories").map { StorageCategory(it.string("id"),
            it.string("name"), it.string("description"), it.long("bytes"),
            it["cleanable"] == true, it.string("riskLevel"), it["cleanupHint"] as? String,
            it.maps("breakdown").map { part -> StorageBreakdown(part.string("label"), part.long("bytes")) }) },
    )
}
