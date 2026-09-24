package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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

data class StorageBreakdown(val label: String, val bytes: Long)
data class StorageCategory(val id: String, val name: String, val description: String,
    val bytes: Long, val cleanable: Boolean, val risk: String, val hint: String?,
    val breakdown: List<StorageBreakdown>)
data class StorageStrategy(val id: String, val name: String, val description: String,
    val risk: String, val olderThanDays: Int?, val targetReleaseBytes: Long)
data class StorageHistoryPoint(val time: Long, val totalBytes: Long, val cleanableBytes: Long)
data class StorageSummary(val generatedAt: Long, val totalBytes: Long, val binaryBytes: Long, val userDataBytes: Long,
    val cacheBytes: Long, val cleanableBytes: Long, val metricsSource: String, val packageName: String,
    val scanTotalBytes: Long, val systemTotalBytes: Long,
    val hasPrevious: Boolean, val deltaTotalBytes: Long, val deltaCleanableBytes: Long,
    val history: List<StorageHistoryPoint>, val strategies: List<StorageStrategy>,
    val categories: List<StorageCategory>)
data class StorageUsageState(val busy: Boolean = false, val loaded: Boolean = false,
    val summary: StorageSummary? = null, val error: Boolean = false, val notice: String? = null)
data class StorageUsageActions(val refresh: () -> Unit,
    val clearCategory: (String, Int?) -> Unit, val runStrategy: (String) -> Unit,
    val dismissNotice: () -> Unit)

@Composable
fun StorageUsageScreen(state: StorageUsageState, actions: StorageUsageActions, onBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    var selectedCategory by remember { mutableStateOf<StorageCategory?>(null) }
    var selectedStrategy by remember { mutableStateOf<StorageStrategy?>(null) }
    var retention by rememberSaveable { mutableIntStateOf(0) }
    val summary = state.summary
    Scaffold(containerColor = palette.page,
        topBar = { OmniTopBar(stringResource(R.string.omni_storage_usage_title), onBack) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 32.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.omni_storage_overview), color = palette.text,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    OmniIconButton(R.drawable.omni_refresh_cw, stringResource(R.string.omni_storage_analyze), actions.refresh)
                }
                if (state.busy) Text(stringResource(R.string.omni_storage_working), color = palette.secondaryText, fontSize = 12.sp)
                if (state.error) {
                    Text(stringResource(R.string.omni_storage_failed), color = palette.secondaryText)
                    TextButton(stringResource(R.string.omni_log_retry), actions.refresh)
                }
                if (summary != null) {
                    Spacer(Modifier.height(14.dp))
                    StorageMetric(stringResource(R.string.omni_storage_total), summary.totalBytes)
                    StorageMetric(stringResource(R.string.omni_storage_binary), summary.binaryBytes)
                    StorageMetric(stringResource(R.string.omni_storage_data), summary.userDataBytes)
                    StorageMetric(stringResource(R.string.omni_storage_cache), summary.cacheBytes)
                    StorageMetric(stringResource(R.string.omni_storage_cleanable), summary.cleanableBytes)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.omni_storage_analyzed_at,
                        formatStorageTime(summary.generatedAt)),
                        color = palette.secondaryText, fontSize = 11.sp)
                    Text(stringResource(if (summary.metricsSource == "system_storage_stats")
                        R.string.omni_storage_system_stats else R.string.omni_storage_estimate),
                        color = palette.tertiaryText, fontSize = 11.sp)
                    if (summary.packageName.isNotBlank()) Text(summary.packageName,
                        color = palette.tertiaryText, fontSize = 11.sp)
                    if (summary.scanTotalBytes > 0 && summary.systemTotalBytes > 0 &&
                        summary.scanTotalBytes != summary.systemTotalBytes) {
                        Text(stringResource(R.string.omni_storage_stats_delta,
                            signedBytes(summary.systemTotalBytes - summary.scanTotalBytes)),
                            color = palette.tertiaryText, fontSize = 11.sp)
                    }
                    Text(if (summary.hasPrevious) stringResource(R.string.omni_storage_trend,
                        signedBytes(summary.deltaTotalBytes), signedBytes(summary.deltaCleanableBytes))
                        else stringResource(R.string.omni_storage_first_analysis),
                        color = palette.secondaryText, fontSize = 12.sp)
                    if (summary.history.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.omni_storage_history), color = palette.secondaryText, fontSize = 12.sp)
                        summary.history.takeLast(4).forEach { point ->
                            Text("${formatStorageTime(point.time)}  ${formatBytes(point.totalBytes)}",
                                color = palette.tertiaryText, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    PreferenceSectionHeader(stringResource(R.string.omni_storage_suggestions),
                        stringResource(R.string.omni_storage_suggestions_note))
                }
            }
            if (summary != null) {
                items(summary.strategies, key = { "strategy_${it.id}" }) { strategy ->
                    BasicComponent(onClick = { selectedStrategy = strategy }, enabled = !state.busy,
                        insideMargin = PaddingValues(horizontal = 4.dp, vertical = 13.dp),
                        endActions = { TextButton(stringResource(R.string.omni_storage_run), { selectedStrategy = strategy }, enabled = !state.busy) }) {
                        Text(strategy.name, color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(strategy.description, color = palette.secondaryText, fontSize = 11.sp)
                        Text(strategy.risk, color = palette.tertiaryText, fontSize = 10.sp)
                    }
                    PreferenceDivider(withIcon = false)
                }
                item {
                    Spacer(Modifier.height(24.dp))
                    PreferenceSectionHeader(stringResource(R.string.omni_storage_categories),
                        stringResource(R.string.omni_storage_categories_note))
                    StorageDistribution(summary)
                    Spacer(Modifier.height(10.dp))
                }
                items(summary.categories, key = { "category_${it.id}" }) { category ->
                    BasicComponent(insideMargin = PaddingValues(horizontal = 4.dp, vertical = 13.dp),
                        endActions = { if (category.cleanable) TextButton(
                            stringResource(R.string.omni_storage_clean),
                            { retention = 0; selectedCategory = category }, enabled = !state.busy) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(category.name, color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(formatBytes(category.bytes), color = palette.text, fontSize = 13.sp)
                        }
                        Text(category.description, color = palette.secondaryText, fontSize = 11.sp)
                        Text("${if (summary.totalBytes > 0) "%.1f".format(category.bytes * 100.0 / summary.totalBytes) else "0.0"}%",
                            color = palette.tertiaryText, fontSize = 10.sp)
                        Text(if (category.cleanable) category.risk else stringResource(R.string.omni_storage_not_cleanable),
                            color = palette.tertiaryText, fontSize = 10.sp)
                        category.breakdown.forEach { item ->
                            Text("${item.label} · ${formatBytes(item.bytes)}", color = palette.tertiaryText, fontSize = 10.sp)
                        }
                    }
                    PreferenceDivider(withIcon = false)
                }
            }
        }
        val category = selectedCategory
        OverlayDialog(show = category != null, title = category?.name.orEmpty(),
            backgroundColor = palette.page, onDismissRequest = { selectedCategory = null }) {
            if (category != null) {
                Text(stringResource(R.string.omni_storage_confirm), color = palette.text, fontSize = 13.sp)
                Text(category.description, color = palette.secondaryText, fontSize = 12.sp)
                category.hint?.takeIf { it.isNotBlank() }?.let { Text(it,
                    color = palette.secondaryText, fontSize = 12.sp)
                }
                Text(stringResource(R.string.omni_storage_risk, category.risk), color = palette.secondaryText, fontSize = 11.sp)
                if (category.risk != "dangerous") {
                    Row {
                        listOf(0, 7, 30).forEach { days ->
                            TextButton(stringResource(when (days) {
                                7 -> R.string.omni_storage_7_days
                                30 -> R.string.omni_storage_30_days
                                else -> R.string.omni_storage_all
                            }), { retention = days })
                        }
                    }
                    Text(stringResource(R.string.omni_storage_scope, if (retention == 0)
                        stringResource(R.string.omni_storage_all) else "$retention"),
                        color = palette.tertiaryText, fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(stringResource(R.string.omni_log_cancel), { selectedCategory = null })
                    TextButton(stringResource(R.string.omni_storage_confirm_clean), {
                        selectedCategory = null
                        actions.clearCategory(category.id, retention.takeIf { it > 0 && category.risk != "dangerous" })
                    })
                }
            }
        }
        val strategy = selectedStrategy
        OverlayDialog(show = strategy != null, title = strategy?.name.orEmpty(),
            backgroundColor = palette.page, onDismissRequest = { selectedStrategy = null }) {
            if (strategy != null) {
                Text(strategy.description, color = palette.secondaryText, fontSize = 12.sp)
                Text(stringResource(R.string.omni_storage_risk, strategy.risk), color = palette.secondaryText, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(stringResource(R.string.omni_log_cancel), { selectedStrategy = null })
                    TextButton(stringResource(R.string.omni_storage_run), {
                        selectedStrategy = null; actions.runStrategy(strategy.id)
                    })
                }
            }
        }
        OverlayDialog(show = state.notice != null, title = stringResource(R.string.omni_storage_result),
            backgroundColor = palette.page, onDismissRequest = actions.dismissNotice) {
            Text(state.notice.orEmpty(), color = palette.secondaryText, fontSize = 12.sp)
            TextButton(stringResource(R.string.omni_log_cancel), actions.dismissNotice)
        }
    }
}

private val chartColors = listOf(Color(0xFF2C7FEB), Color(0xFF67A5F1), Color(0xFF98AD90),
    Color(0xFFF2AD57), Color(0xFFE77E81), Color(0xFF9279CE), Color(0xFF8296A9))

@Composable
private fun StorageDistribution(summary: StorageSummary) {
    val palette = LocalOmniPalette.current
    val visible = summary.categories.filter { it.bytes > 0 }.sortedByDescending { it.bytes }
    if (visible.isEmpty() || summary.totalBytes <= 0) return
    val segments = if (visible.size <= 7) visible.map { it.name to it.bytes }
        else visible.take(6).map { it.name to it.bytes } +
            (stringResource(R.string.omni_storage_other) to visible.drop(6).sumOf { it.bytes })
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 380.dp
        val chart: @Composable () -> Unit = {
            Box(Modifier.size(if (compact) 156.dp else 172.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = if (compact) 18.dp.toPx() else 20.dp.toPx()
                    val topLeft = Offset(stroke / 2f, stroke / 2f)
                    val diameter = minOf(size.width, size.height) - stroke
                    val arcSize = Size(diameter, diameter)
                    drawArc(palette.segmentTrack, 0f, 360f, false,
                        topLeft = topLeft, size = arcSize, style = Stroke(width = stroke))
                    var start = -90f
                    segments.forEachIndexed { index, (_, bytes) ->
                        val sweep = (bytes.toDouble() / summary.totalBytes * 360).toFloat().coerceAtLeast(0f)
                        drawArc(chartColors[index % chartColors.size], start, sweep, false,
                            topLeft = topLeft, size = arcSize, style = Stroke(width = stroke))
                        start += sweep
                    }
                }
                Text(formatBytes(summary.totalBytes), modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                    color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        val legend: @Composable () -> Unit = {
            Column {
                Text(stringResource(R.string.omni_storage_distribution), color = palette.text,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                segments.take(5).forEachIndexed { index, (label, bytes) ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.weight(1f)) {
                            Box(Modifier.padding(top = 4.dp).size(8.dp).background(chartColors[index % chartColors.size], RoundedCornerShape(4.dp)))
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = palette.text, fontSize = 12.sp, maxLines = 1)
                        }
                        Text("%.1f%%".format(bytes * 100.0 / summary.totalBytes),
                            color = palette.secondaryText, fontSize = 11.sp)
                    }
                }
            }
        }
        if (compact) {
            Column {
                Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { chart() }
                Spacer(Modifier.height(12.dp))
                legend()
            }
        } else {
            Row {
                Box(Modifier.weight(4f), contentAlignment = androidx.compose.ui.Alignment.Center) { chart() }
                Spacer(Modifier.width(20.dp))
                Box(Modifier.weight(5f)) { legend() }
            }
        }
    }
}

@Composable
private fun StorageMetric(label: String, bytes: Long) {
    val palette = LocalOmniPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = palette.secondaryText, fontSize = 12.sp)
        Text(formatBytes(bytes), color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun signedBytes(bytes: Long): String = when {
    bytes > 0 -> "+${formatBytes(bytes)}"
    bytes < 0 -> "-${formatBytes(-bytes)}"
    else -> "0 B"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return if (unit == 0) "${bytes} B" else "%.1f %s".format(value, units[unit])
}

private fun formatStorageTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
