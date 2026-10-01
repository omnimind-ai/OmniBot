package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.nativeui.settings.UsageStatisticsActions
import cn.com.omnimind.nativeui.settings.UsageStatisticsAggregation
import cn.com.omnimind.nativeui.settings.UsageStatisticsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * 轨迹 page. Reads the existing conversation and token-usage owners and
 * aggregates on IO; it never writes history or usage records.
 */
internal class NativeUsageStatisticsViewModel(context: Context) : ViewModel() {
    private val repository = NativeUsageStatisticsRepository(context.applicationContext)
    private val mutableState = MutableStateFlow(UsageStatisticsState())
    private var loadJob: Job? = null
    val state = mutableState.asStateFlow()

    val actions = UsageStatisticsActions(
        setTab = { tab -> mutableState.update { it.copy(tab = tab) } },
    )

    /** Entry lifecycle resume: counts can change while the page is in the back stack. */
    fun resume() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        try {
            val data = withContext(Dispatchers.IO) {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val since = UsageStatisticsAggregation.windowStart(today).atStartOfDay(zone).toInstant().toEpochMilli()
                UsageStatisticsAggregation.aggregate(
                    conversationCreatedAt = repository.conversationCreatedAt(),
                    tokenSamples = repository.tokenSamplesSince(since),
                    today = today,
                    zone = zone,
                )
            }
            mutableState.update { it.copy(loaded = true, data = data) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OmniLog.e("NativeUsageStatistics", "Usage statistics load failed", error)
            // Flutter shows the empty dashboard on failure; keep the previous snapshot if any.
            mutableState.update { it.copy(loaded = true) }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativeUsageStatisticsViewModel(appContext) as T
    }
}
