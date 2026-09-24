package cn.com.omnimind.bot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.llm.AiRequestLogStore
import cn.com.omnimind.baselib.util.RuntimeLogStore
import cn.com.omnimind.nativeui.settings.LogPageState
import cn.com.omnimind.nativeui.settings.RequestLogItem
import cn.com.omnimind.nativeui.settings.RuntimeLogItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Presentation snapshots only; the existing baselib stores retain all log ownership. */
internal class NativeLogsViewModel : ViewModel() {
    private val requestMutable = MutableStateFlow(LogPageState<RequestLogItem>())
    private val runtimeMutable = MutableStateFlow(LogPageState<RuntimeLogItem>())
    val requests = requestMutable.asStateFlow()
    val runtime = runtimeMutable.asStateFlow()

    fun refreshRequests() {
        if (requestMutable.value.busy) return
        requestMutable.value = requestMutable.value.copy(busy = true, error = false)
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { AiRequestLogStore.listRecent(10).map { log ->
                    RequestLogItem(log.id, log.createdAt, log.label, log.model, log.protocolType,
                        log.url, log.method, log.stream, log.statusCode, log.success,
                        AiRequestLogStore.prettyJsonOrRaw(log.requestJson),
                        AiRequestLogStore.prettyJsonOrRaw(log.responseJson), log.errorMessage)
                } }
                requestMutable.value = LogPageState(loaded = true, entries = items)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { requestMutable.value = requestMutable.value.copy(loaded = true, busy = false, error = true) }
        }
    }

    fun refreshRuntime() {
        if (runtimeMutable.value.busy) return
        runtimeMutable.value = runtimeMutable.value.copy(busy = true, error = false)
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { RuntimeLogStore.listRecent(200).map { log ->
                    RuntimeLogItem(log.id, log.createdAt, log.level, log.tag, log.message,
                        log.stackTrace, log.isCrash)
                } }
                runtimeMutable.value = LogPageState(loaded = true, entries = items)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { runtimeMutable.value = runtimeMutable.value.copy(loaded = true, busy = false, error = true) }
        }
    }

    fun clearRuntime() {
        if (runtimeMutable.value.busy) return
        runtimeMutable.value = runtimeMutable.value.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { RuntimeLogStore.clear() }
                runtimeMutable.value = LogPageState(loaded = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { runtimeMutable.value = runtimeMutable.value.copy(busy = false, error = true) }
        }
    }

    fun runtimeExportText(): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return runtimeMutable.value.entries.joinToString("\n\n---\n\n") { log ->
            buildString {
                appendLine("Time: ${format.format(Date(log.createdAt))}")
                appendLine("Level: ${log.level}${if (log.isCrash) " (CRASH)" else ""}")
                appendLine("Tag: ${log.tag.ifBlank { "-" }}")
                append("Message: ${log.message.ifBlank { "-" }}")
                log.stackTrace?.takeIf { it.isNotBlank() }?.let { append("\nStackTrace:\n$it") }
            }
        }
    }
}
