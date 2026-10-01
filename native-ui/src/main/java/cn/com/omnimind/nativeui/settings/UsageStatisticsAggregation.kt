package cn.com.omnimind.nativeui.settings

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Token usage row projection; the owner is `TokenUsageRecordDao`. */
data class UsageTokenSample(
    val createdAt: Long,
    val model: String,
    val completionTokens: Int,
    val reasoningTokens: Int,
    val textTokens: Int,
    val cachedTokens: Int,
)

/**
 * Pure port of `ActivityDashboardCard`'s aggregation (16 weeks, Monday-aligned
 * grid). No I/O: callers load rows from the existing stores and run this off
 * the main thread.
 */
object UsageStatisticsAggregation {
    const val WEEKS_TO_SHOW = 16

    /** First day the owner rows must be read from (Monday on or before the window start). */
    fun windowStart(today: LocalDate): LocalDate = alignToMonday(today.minusDays(WEEKS_TO_SHOW * 7L - 1))

    fun aggregate(
        conversationCreatedAt: List<Long>,
        tokenSamples: List<UsageTokenSample>,
        today: LocalDate,
        zone: ZoneId,
    ): UsageStatisticsData {
        val rangeStart = today.minusDays(WEEKS_TO_SHOW * 7L - 1)
        val activity = HashMap<LocalDate, Int>()
        var totalInRange = 0
        conversationCreatedAt.forEach { millis ->
            val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            if (date.isBefore(rangeStart) || date.isAfter(today)) return@forEach
            activity[date] = (activity[date] ?: 0) + 1
            totalInRange++
        }
        var streak = 0
        var check = today
        while ((activity[check] ?: 0) > 0) {
            streak++
            check = check.minusDays(1)
        }

        val gridStart = alignToMonday(rangeStart)
        val totalWeeks = ((ChronoUnit.DAYS.between(gridStart, today) + 1 + 6) / 7).toInt()
        val heatmap = List(totalWeeks) { week ->
            UsageWeek(List(7) { dayOfWeek ->
                val date = gridStart.plusDays(week * 7L + dayOfWeek)
                UsageDay(date.monthValue, date.dayOfMonth, activity[date] ?: 0, date.isAfter(today))
            })
        }
        val monthLabels = buildList {
            var lastMonth = -1
            for (week in 0 until totalWeeks) {
                val month = gridStart.plusDays(week * 7L).monthValue
                if (month != lastMonth) add(UsageMonthLabel(week, month))
                lastMonth = month
            }
        }

        val weekModels = List(totalWeeks) { LinkedHashMap<String, Long>() }
        val weekCached = LongArray(totalWeeks)
        val modelTotals = HashMap<String, Long>()
        var totalCached = 0L
        tokenSamples.forEach { sample ->
            val date = Instant.ofEpochMilli(sample.createdAt).atZone(zone).toLocalDate()
            val days = ChronoUnit.DAYS.between(gridStart, date)
            if (days < 0) return@forEach
            val weekIndex = (days / 7).toInt()
            if (weekIndex >= totalWeeks) return@forEach
            val detailed = sample.reasoningTokens.toLong() + sample.textTokens
            val tokens = if (detailed > 0) detailed else sample.completionTokens.toLong()
            val modelId = normalizeModelId(sample.model)
            weekCached[weekIndex] += sample.cachedTokens
            totalCached += sample.cachedTokens
            if (tokens > 0) {
                weekModels[weekIndex][modelId] = (weekModels[weekIndex][modelId] ?: 0) + tokens
                modelTotals[modelId] = (modelTotals[modelId] ?: 0) + tokens
            }
        }
        val modelOrder = modelTotals.keys.sortedWith(
            compareByDescending<String> { modelTotals[it] ?: 0 }.thenBy { it },
        )
        val totalTokens = modelTotals.values.sum()
        val tokenWeeks = List(totalWeeks) { index ->
            val start = gridStart.plusDays(index * 7L)
            val end = start.plusDays(6)
            val models = weekModels[index]
            val ordered = modelOrder.filter { (models[it] ?: 0) > 0 } +
                models.keys.filterNot(modelOrder::contains)
            UsageTokenWeek(
                startMonth = start.monthValue,
                startDay = start.dayOfMonth,
                endMonth = end.monthValue,
                endDay = end.dayOfMonth,
                totalTokens = models.values.sum(),
                cachedTokens = weekCached[index],
                segments = ordered.map { UsageSegment(it, models[it] ?: 0) },
            )
        }
        return UsageStatisticsData(
            totalConversations = totalInRange,
            streak = streak,
            heatmap = heatmap,
            monthLabels = monthLabels,
            tokenWeeks = tokenWeeks,
            models = modelOrder.map { UsageModelShare(it, modelTotals[it] ?: 0, percentOf(modelTotals[it] ?: 0, totalTokens)) },
            totalTokens = totalTokens,
            totalCached = totalCached,
        )
    }

    /** Same rules as Flutter `TokenUsageService.normalizeModelId`. */
    fun normalizeModelId(rawModel: String): String {
        var value = rawModel.trim()
        if (value.isEmpty()) return "unknown"
        value = value.replace(Regex("\\s+"), " ")
        val prefixSplit = value.split(Regex("\\s*(?:\\||::)\\s*")).filter { it.isNotBlank() }
        if (prefixSplit.size > 1) value = prefixSplit.last().trim()
        val pathSplit = value.split(Regex("[/\\\\]")).filter { it.isNotBlank() }
        if (pathSplit.size > 1) {
            value = pathSplit.last().trim()
        } else {
            val colon = value.indexOf(':')
            if (colon > 0 && colon < value.length - 1) value = value.substring(colon + 1).trim()
        }
        return value.ifEmpty { "unknown" }
    }

    /** `x.xM` / `x.xK` / plain, like Flutter `_formatTokenCount`. */
    fun formatTokenCount(count: Long): String = when {
        count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }

    /** Heatmap intensity 0–4, same buckets as Flutter. */
    fun intensity(count: Int): Int = when {
        count <= 0 -> 0
        count == 1 -> 1
        count <= 3 -> 2
        count <= 6 -> 3
        else -> 4
    }

    /** Stable palette slot for a model id (Dart `codeUnits` fold). */
    fun modelColorIndex(modelId: String, size: Int = 10): Int {
        var hash = 0
        modelId.forEach { hash = (hash * 31 + it.code) and 0x7fffffff }
        return hash % size
    }

    private fun percentOf(part: Long, total: Long): Int =
        if (total == 0L) 0 else Math.round(part * 100.0 / total).toInt()

    private fun alignToMonday(date: LocalDate): LocalDate {
        var value = date
        while (value.dayOfWeek != DayOfWeek.MONDAY) value = value.minusDays(1)
        return value
    }
}
