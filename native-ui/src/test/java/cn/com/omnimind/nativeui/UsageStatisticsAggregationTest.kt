package cn.com.omnimind.nativeui

import cn.com.omnimind.nativeui.settings.UsageStatisticsAggregation
import cn.com.omnimind.nativeui.settings.UsageTokenSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class UsageStatisticsAggregationTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 30) // Wednesday

    private fun at(date: LocalDate, hour: Int = 12) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun sample(date: LocalDate, model: String, completion: Int = 0, reasoning: Int = 0, text: Int = 0, cached: Int = 0) =
        UsageTokenSample(at(date), model, completion, reasoning, text, cached)

    @Test fun heatmapIsMondayAlignedAndCountsOnlyTheSixteenWeekWindow() {
        val windowStart = today.minusDays(16 * 7L - 1)
        val data = UsageStatisticsAggregation.aggregate(
            listOf(at(today), at(today), at(today.minusDays(1)), at(windowStart), at(windowStart.minusDays(1)), at(today.plusDays(1))),
            emptyList(), today, zone,
        )
        assertEquals(4, data.totalConversations)
        assertEquals(2, data.streak)
        assertEquals(17, data.heatmap.size)
        assertEquals(UsageStatisticsAggregation.windowStart(today).dayOfMonth, data.heatmap.first().days.first().day)
        val lastWeek = data.heatmap.last().days
        assertEquals(2, lastWeek[2].count)
        assertTrue(lastWeek[3].future && lastWeek[6].future)
        assertEquals(0, data.heatmap.first().days.first().count)
    }

    @Test fun tokensPreferDetailedCountsAndOrderModelsByTotal() {
        val data = UsageStatisticsAggregation.aggregate(
            emptyList(),
            listOf(
                sample(today, "openai/gpt-4o", completion = 500, reasoning = 100, text = 200, cached = 40),
                sample(today, "provider | deepseek-chat", completion = 900),
                sample(today.minusDays(7), "gpt-4o", completion = 200),
                sample(today, "empty", completion = 0),
            ),
            today, zone,
        )
        assertEquals(listOf("deepseek-chat", "gpt-4o"), data.models.map { it.modelId })
        assertEquals(1400L, data.totalTokens)
        assertEquals(40L, data.totalCached)
        assertEquals(64, data.models.first().percent)
        val week = data.tokenWeeks.last()
        assertEquals(1200L, week.totalTokens)
        assertEquals(listOf("deepseek-chat", "gpt-4o"), week.segments.map { it.modelId })
        assertEquals(200L, data.tokenWeeks[data.tokenWeeks.size - 2].totalTokens)
    }

    @Test fun modelIdsAndCountsMatchFlutterFormatting() {
        assertEquals("unknown", UsageStatisticsAggregation.normalizeModelId("  "))
        assertEquals("claude-sonnet", UsageStatisticsAggregation.normalizeModelId("anthropic::claude-sonnet"))
        assertEquals("qwen3", UsageStatisticsAggregation.normalizeModelId("ollama:qwen3"))
        assertEquals("gpt-4o", UsageStatisticsAggregation.normalizeModelId("azure/openai/gpt-4o"))
        assertEquals("999", UsageStatisticsAggregation.formatTokenCount(999))
        assertEquals("1.5K", UsageStatisticsAggregation.formatTokenCount(1500))
        assertEquals("2.0M", UsageStatisticsAggregation.formatTokenCount(2_000_000))
        assertEquals(listOf(0, 1, 2, 2, 3, 4), listOf(0, 1, 2, 3, 6, 7).map(UsageStatisticsAggregation::intensity))
        assertEquals((("ab".fold(0) { h, c -> (h * 31 + c.code) and 0x7fffffff }) % 10), UsageStatisticsAggregation.modelColorIndex("ab"))
    }
}
