package cn.com.omnimind.nativeui.settings

import androidx.compose.runtime.Immutable

enum class UsageStatisticsTab { Conversations, Tokens }

/** One heatmap cell; [future] cells render as empty space like the Flutter grid. */
@Immutable
data class UsageDay(val month: Int, val day: Int, val count: Int, val future: Boolean)

/** Monday-first column of seven heatmap cells. */
@Immutable
data class UsageWeek(val days: List<UsageDay>)

@Immutable
data class UsageMonthLabel(val weekIndex: Int, val month: Int)

@Immutable
data class UsageModelShare(val modelId: String, val tokens: Long, val percent: Int)

@Immutable
data class UsageSegment(val modelId: String, val tokens: Long)

/** Weekly stacked bar; [segments] are top-to-bottom in legend order. */
@Immutable
data class UsageTokenWeek(
    val startMonth: Int,
    val startDay: Int,
    val endMonth: Int,
    val endDay: Int,
    val totalTokens: Long,
    val cachedTokens: Long,
    val segments: List<UsageSegment>,
)

/** Aggregated snapshot; counts only, never message content or credentials. */
@Immutable
data class UsageStatisticsData(
    val totalConversations: Int = 0,
    val streak: Int = 0,
    val heatmap: List<UsageWeek> = emptyList(),
    val monthLabels: List<UsageMonthLabel> = emptyList(),
    val tokenWeeks: List<UsageTokenWeek> = emptyList(),
    val models: List<UsageModelShare> = emptyList(),
    val totalTokens: Long = 0,
    val totalCached: Long = 0,
)

@Immutable
data class UsageStatisticsState(
    val loaded: Boolean = false,
    val tab: UsageStatisticsTab = UsageStatisticsTab.Conversations,
    val data: UsageStatisticsData = UsageStatisticsData(),
)

data class UsageStatisticsActions(
    val setTab: (UsageStatisticsTab) -> Unit = {},
)
