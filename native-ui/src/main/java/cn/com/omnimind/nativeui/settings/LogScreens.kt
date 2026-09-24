package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RequestLogItem(
    val id: String, val createdAt: Long, val label: String, val model: String,
    val protocol: String, val url: String, val method: String, val stream: Boolean,
    val statusCode: Int?, val success: Boolean, val request: String,
    val response: String, val error: String?,
)

data class RuntimeLogItem(
    val id: String, val createdAt: Long, val level: String, val tag: String,
    val message: String, val stackTrace: String?, val isCrash: Boolean,
)

data class LogPageState<T>(
    val loaded: Boolean = false, val busy: Boolean = false, val error: Boolean = false,
    val entries: List<T> = emptyList(),
)

@Composable
fun RequestLogsScreen(state: LogPageState<RequestLogItem>, refresh: () -> Unit,
    copy: (String) -> Unit, onBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    Scaffold(containerColor = palette.page,
        topBar = { OmniTopBar(stringResource(R.string.omni_request_logs), onBack) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp)) {
            item {
                LogHeader(stringResource(R.string.omni_request_logs), refresh, state.busy)
                LogOverview(state.entries.size.toString(), state.entries.count { it.success }.toString(),
                    state.entries.count { !it.success }.toString(), R.string.omni_log_success, R.string.omni_log_failed)
                if (state.entries.isNotEmpty()) Text(
                    stringResource(R.string.omni_log_latest_time, formatLogTime(state.entries.first().createdAt)),
                    color = palette.secondaryText, fontSize = 11.sp)
                Spacer(Modifier.height(20.dp))
                PreferenceSectionHeader(stringResource(R.string.omni_log_recent))
            }
            if (state.error || state.loaded && state.entries.isEmpty()) {
                item { EmptyLogs(state.error, refresh) }
            }
            items(state.entries, key = { it.id }) { log ->
                var expanded by rememberSaveable(log.id) { mutableStateOf(false) }
                BasicComponent(onClick = { expanded = !expanded },
                    insideMargin = PaddingValues(horizontal = 4.dp, vertical = 13.dp)) {
                    Text(log.label.ifBlank { log.model.ifBlank { log.protocol } }, color = palette.text,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("${formatLogTime(log.createdAt)}  ·  ${log.statusCode ?: if (log.success) "OK" else "ERROR"}",
                        color = palette.secondaryText, fontSize = 11.sp)
                    Text("${log.method} ${log.url}", color = palette.tertiaryText, fontSize = 11.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                    if (expanded) {
                        LogDetail(stringResource(R.string.omni_log_model), log.model)
                        LogDetail(stringResource(R.string.omni_log_protocol), log.protocol)
                        LogDetail(stringResource(R.string.omni_log_stream), log.stream.toString())
                        log.error?.takeIf { it.isNotBlank() }?.let { LogDetail(stringResource(R.string.omni_log_error), it) }
                        JsonDetail(stringResource(R.string.omni_log_request), log.request, copy)
                        JsonDetail(stringResource(R.string.omni_log_response), log.response, copy)
                    }
                }
                PreferenceDivider(withIcon = false)
            }
        }
    }
}

@Composable
fun RuntimeLogsScreen(state: LogPageState<RuntimeLogItem>, refresh: () -> Unit,
    clear: () -> Unit, export: () -> Unit, copy: (String) -> Unit, onBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Scaffold(containerColor = palette.page,
        topBar = { OmniTopBar(stringResource(R.string.omni_runtime_logs), onBack) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp)) {
            item {
                LogHeader(stringResource(R.string.omni_runtime_logs), refresh, state.busy)
                LogOverview(state.entries.size.toString(), state.entries.count { it.isCrash }.toString(),
                    state.entries.firstOrNull()?.let { formatLogTime(it.createdAt).substring(5, 10) } ?: "-",
                    R.string.omni_log_crashes, R.string.omni_log_latest)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(stringResource(R.string.omni_log_export), export,
                        enabled = state.entries.isNotEmpty() && !state.busy)
                    TextButton(stringResource(R.string.omni_log_clear), { confirmClear = true },
                        enabled = state.entries.isNotEmpty() && !state.busy)
                }
                Spacer(Modifier.height(10.dp))
                PreferenceSectionHeader(stringResource(R.string.omni_log_recent))
            }
            if (state.error || state.loaded && state.entries.isEmpty()) {
                item { EmptyLogs(state.error, refresh) }
            }
            items(state.entries, key = { it.id }) { log ->
                var expanded by rememberSaveable(log.id) { mutableStateOf(false) }
                BasicComponent(onClick = { expanded = !expanded },
                    insideMargin = PaddingValues(horizontal = 4.dp, vertical = 13.dp)) {
                    Text("${log.level}${if (log.isCrash) " · CRASH" else ""}  ${log.tag}",
                        color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(formatLogTime(log.createdAt), color = palette.tertiaryText, fontSize = 11.sp)
                    Text(log.message, color = palette.secondaryText, fontSize = 12.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                    if (expanded) log.stackTrace?.takeIf { it.isNotBlank() }?.let {
                        JsonDetail(stringResource(R.string.omni_log_stack), it, copy)
                    }
                }
                PreferenceDivider(withIcon = false)
            }
        }
        OverlayDialog(show = confirmClear, title = stringResource(R.string.omni_log_clear_confirm),
            backgroundColor = palette.page, onDismissRequest = { confirmClear = false }) {
            Text(stringResource(R.string.omni_log_clear_warning), color = palette.secondaryText, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(stringResource(R.string.omni_log_cancel), { confirmClear = false })
                TextButton(stringResource(R.string.omni_log_clear), { confirmClear = false; clear() })
            }
        }
    }
}

@Composable
private fun LogHeader(title: String, refresh: () -> Unit, busy: Boolean) {
    val palette = LocalOmniPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        OmniIconButton(R.drawable.omni_refresh_cw, stringResource(R.string.omni_log_refresh), refresh)
    }
    if (busy) Text(stringResource(R.string.omni_log_loading), color = palette.secondaryText, fontSize = 12.sp)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun LogOverview(total: String, second: String, third: String, secondLabel: Int, thirdLabel: Int) {
    val palette = LocalOmniPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(total, second, third).forEachIndexed { index, value ->
            Column {
                Text(value, color = palette.text, fontSize = if (index == 2 && value.length > 10) 12.sp else 20.sp,
                    fontWeight = FontWeight.Bold)
                Text(stringResource(when (index) {
                    0 -> R.string.omni_log_total
                    1 -> secondLabel
                    else -> thirdLabel
                }), color = palette.secondaryText, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun EmptyLogs(error: Boolean, refresh: () -> Unit) {
    val palette = LocalOmniPalette.current
    Text(stringResource(if (error) R.string.omni_log_read_failed else R.string.omni_log_empty),
        color = palette.secondaryText, fontSize = 13.sp)
    if (error) TextButton(stringResource(R.string.omni_log_retry), refresh)
}

@Composable
private fun LogDetail(label: String, value: String) {
    val palette = LocalOmniPalette.current
    Spacer(Modifier.height(8.dp))
    Text("$label: $value", color = palette.secondaryText, fontSize = 11.sp)
}

@Composable
private fun JsonDetail(label: String, value: String, copy: (String) -> Unit) {
    val palette = LocalOmniPalette.current
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = palette.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        TextButton(stringResource(R.string.omni_log_copy), { copy(value) }, enabled = value.isNotBlank())
    }
    Text(value.ifBlank { "<empty>" }, color = palette.text, fontSize = 11.sp, lineHeight = 16.sp)
}

private fun formatLogTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
