package cn.com.omnimind.nativeui.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.settings.UsageStatisticsAggregation.formatTokenCount
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import cn.com.omnimind.nativeui.theme.OmniPalette
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.PlainTooltip
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState
import cn.com.omnimind.nativeui.components.OmniTabRow
import cn.com.omnimind.nativeui.components.OmniPage

private val CellGap = 3.dp
private val DayLabelWidth = 20.dp
private val HeatLight = listOf(0xFFEBF0F5, 0xFFBFDBF7, 0xFF7BBCE6, 0xFF3B8FD4, 0xFF1A56A8).map(::Color)
private val HeatDark = listOf(0xFF242728, 0xFF1A3A5C, 0xFF1B5E94, 0xFF2178BD, 0xFF3B9FE8).map(::Color)
private val ModelLight = listOf(0xFF2C7FEB, 0xFF16A085, 0xFF8B5CF6, 0xFFF97316, 0xFFE11D48,
    0xFF0891B2, 0xFF65A30D, 0xFFDB2777, 0xFF475569, 0xFFB45309).map(::Color)
private val ModelDark = listOf(0xFF7BBCE6, 0xFF5DD6B3, 0xFFC4B5FD, 0xFFFBBF24, 0xFFFB7185,
    0xFF67E8F9, 0xFFA3E635, 0xFFF0ABFC, 0xFFCBD5E1, 0xFFFDBA74).map(::Color)

/** 轨迹 page: conversation heatmap and weekly token bars, same data rules as `ActivityDashboardCard`. */
@Composable
fun UsageStatisticsScreen(state: UsageStatisticsState, actions: UsageStatisticsActions, onBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    OmniPage(stringResource(R.string.omni_history), onBack) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                Crossfade(state.loaded, animationSpec = tween(600), label = "usage-load") { loaded ->
                    if (loaded) UsageContent(state, actions, palette) else UsageSkeleton(palette)
                }
            }
        }
    }
}

@Composable
private fun UsageSkeleton(palette: OmniPalette) {
    val color = if (palette.dark) palette.secondarySurface else Color(0xFFE8EFF8)
    Column {
        Box(Modifier.size(200.dp, 16.dp).background(color, RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(28.dp).background(color, RoundedCornerShape(14.dp)))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(90.dp).background(color, RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.size(140.dp, 12.dp).background(color, RoundedCornerShape(4.dp)))
    }
}

@Composable
private fun UsageContent(state: UsageStatisticsState, actions: UsageStatisticsActions, palette: OmniPalette) {
    val data = state.data
    Column {
        StatsRow(data, palette)
        Spacer(Modifier.height(12.dp))
        val tabs = UsageStatisticsTab.entries
        OmniTabRow(tabs.map { stringResource(if (it == UsageStatisticsTab.Conversations) R.string.omni_usage_tab_chat else R.string.omni_usage_tab_token) },
            tabs.indexOf(state.tab), { index -> actions.setTab(tabs[index]) })
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val weeks = data.heatmap.size.coerceAtLeast(1)
            val cell = ((maxWidth - DayLabelWidth - CellGap * (weeks - 1)) / weeks).coerceIn(4.dp, 14.dp)
            Crossfade(state.tab, animationSpec = tween(300), label = "usage-tab") { tab ->
                when (tab) {
                    UsageStatisticsTab.Conversations -> Heatmap(data, cell, palette)
                    UsageStatisticsTab.Tokens -> TokenView(data, cell, maxWidth, palette)
                }
            }
        }
    }
}

@Composable
private fun StatsRow(data: UsageStatisticsData, palette: OmniPalette) {
    val accentBlue = if (palette.dark) Color(0xFF7BBCE6) else Color(0xFF2C7FEB)
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatPill(R.drawable.omni_message_circle, data.totalConversations.toString(),
            stringResource(R.string.omni_usage_conversations), accentBlue, palette)
        Spacer(Modifier.width(8.dp))
        StatPill(R.drawable.omni_flame, data.streak.toString(), stringResource(R.string.omni_usage_streak),
            if (data.streak >= 3) Color(0xFFF59E0B) else accentBlue, palette)
        Spacer(Modifier.width(8.dp))
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatPill(R.drawable.omni_zap, formatTokenCount(data.totalTokens),
                stringResource(R.string.omni_usage_tokens), accentBlue, palette)
            data.models.firstOrNull()?.let { top ->
                StatPill(R.drawable.omni_network, data.models.size.toString(),
                    stringResource(R.string.omni_usage_models), modelColor(top.modelId, palette), palette)
            }
            if (data.totalCached > 0) {
                StatPill(R.drawable.omni_refresh_ccw, formatTokenCount(data.totalCached),
                    stringResource(R.string.omni_usage_cached),
                    if (palette.dark) Color(0xFFB8860B) else Color(0xFFD4A017), palette)
            }
        }
    }
}

@Composable
private fun StatPill(icon: Int, value: String, label: String, tint: Color, palette: OmniPalette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OmniIcon(icon, size = 14.dp, tint = tint)
        Spacer(Modifier.width(3.dp))
        Text(value, color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(2.dp))
        Text(label, color = palette.tertiaryText, fontSize = 11.sp)
    }
}

@Composable
private fun Heatmap(data: UsageStatisticsData, cell: Dp, palette: OmniPalette) {
    val labelColor = if (palette.dark) Color(0xFF9A9488) else Color(0xFF98A5BB)
    val months = stringArrayResource(R.array.omni_usage_months)
    val weekdays = stringArrayResource(R.array.omni_usage_weekdays)
    val colors = if (palette.dark) HeatDark else HeatLight
    val weeks = data.heatmap.size.coerceAtLeast(1)
    Column {
        BoxWithConstraints(Modifier.padding(start = DayLabelWidth, bottom = 4.dp).fillMaxWidth().height(14.dp)) {
            val weekWidth = maxWidth / weeks
            data.monthLabels.forEach { label ->
                Text(months[label.month - 1], Modifier.offset(x = weekWidth * label.weekIndex),
                    color = labelColor, fontSize = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
        Row {
            Column(Modifier.width(DayLabelWidth)) {
                for (day in 0 until 7) {
                    Box(Modifier.height(if (day < 6) cell + CellGap else cell), contentAlignment = Alignment.CenterStart) {
                        if (day % 2 == 0) Text(weekdays[day / 2], color = labelColor, fontSize = 9.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CellGap)) {
                data.heatmap.forEach { week ->
                    Column(verticalArrangement = Arrangement.spacedBy(CellGap)) {
                        week.days.forEach { day ->
                            if (day.future) {
                                Spacer(Modifier.size(cell))
                            } else {
                                val text = if (day.count > 0) {
                                    stringResource(R.string.omni_usage_day_tooltip, day.count, day.month, day.day)
                                } else {
                                    stringResource(R.string.omni_usage_day_empty_tooltip, day.month, day.day)
                                }
                                TapTooltip(text, palette) { modifier ->
                                    Box(modifier.size(cell).background(colors[UsageStatisticsAggregation.intensity(day.count)],
                                        RoundedCornerShape(2.5.dp)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenView(data: UsageStatisticsData, cell: Dp, width: Dp, palette: OmniPalette) {
    if (data.totalTokens == 0L) {
        Box(Modifier.fillMaxWidth().height(70.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.omni_usage_token_empty), color = palette.tertiaryText, fontSize = 11.sp)
        }
        return
    }
    Column {
        LazyRow(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            items(data.models, key = { it.modelId }) { model ->
                val color = modelColor(model.modelId, palette)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(5.dp))
                    Text(model.modelId, color = palette.secondaryText, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Text("${model.percent}%", color = if (palette.dark) color else lerp(color, palette.text, .18f),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        StackedBars(data, cell, width, palette)
    }
}

@Composable
private fun StackedBars(data: UsageStatisticsData, cell: Dp, width: Dp, palette: OmniPalette) {
    val weeks = data.tokenWeeks
    if (weeks.isEmpty()) return
    val areaHeight = cell * 7 + CellGap * 6
    val barWidth = ((width - CellGap * (weeks.size - 1)) / weeks.size).coerceIn(4.dp, 14.dp)
    val maxWeek = weeks.maxOf { it.totalTokens }
    val cachedLabel = stringResource(R.string.omni_usage_cached)
    val emptyLabel = stringResource(R.string.omni_usage_no_usage)
    Row(Modifier.height(areaHeight), horizontalArrangement = Arrangement.spacedBy(CellGap),
        verticalAlignment = Alignment.Bottom) {
        weeks.forEach { week ->
            val text = if (week.totalTokens > 0) {
                buildList {
                    add(stringResource(R.string.omni_usage_week_tooltip, week.startMonth, week.startDay,
                        week.endMonth, week.endDay, formatTokenCount(week.totalTokens)))
                    week.segments.take(6).forEach { add("${it.modelId} ${formatTokenCount(it.tokens)}") }
                    if (week.segments.size > 6) add("+${week.segments.size - 6}")
                    if (week.cachedTokens > 0) add("$cachedLabel ${formatTokenCount(week.cachedTokens)}")
                }.joinToString("\n")
            } else {
                emptyLabel
            }
            TapTooltip(text, palette) { modifier ->
                Column(modifier.size(barWidth, areaHeight), verticalArrangement = Arrangement.Bottom) {
                    if (week.totalTokens == 0L) {
                        Box(Modifier.size(barWidth, 2.dp).background(palette.elevatedSurface, RoundedCornerShape(1.dp)))
                    } else {
                        val total = areaHeight * (week.totalTokens.toFloat() / maxWeek)
                        val minHeight = if (total >= 1.4.dp * week.segments.size) 1.4.dp else 0.6.dp
                        week.segments.forEachIndexed { index, segment ->
                            val top = if (index == 0) 2.5.dp else 0.dp
                            val bottom = if (index == week.segments.lastIndex) 1.5.dp else 0.dp
                            Box(Modifier.size(barWidth, (total * (segment.tokens.toFloat() / week.totalTokens))
                                .coerceIn(minHeight, areaHeight))
                                .background(modelColor(segment.modelId, palette),
                                    RoundedCornerShape(top, top, bottom, bottom)))
                        }
                    }
                }
            }
        }
    }
}

/** Tap shows the Miuix tooltip, matching Flutter's `TooltipTriggerMode.tap`. */
@Composable
private fun TapTooltip(text: String, palette: OmniPalette, content: @Composable (Modifier) -> Unit) {
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val container = if (palette.dark) Color(0xFF2D3032) else Color(0xFF353E53)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip(maxWidth = 280.dp, containerColor = container, contentColor = Color.White) {
            Text(text, color = Color.White, fontSize = 11.sp)
        } },
        state = tooltip,
    ) {
        content(Modifier.semantics { contentDescription = text }
            .clickable(interactionSource = null, indication = null, role = Role.Button) {
                scope.launch { tooltip.show() }
            })
    }
}

private fun modelColor(modelId: String, palette: OmniPalette): Color =
    (if (palette.dark) ModelDark else ModelLight)[UsageStatisticsAggregation.modelColorIndex(modelId)]
