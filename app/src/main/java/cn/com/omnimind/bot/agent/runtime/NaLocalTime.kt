package cn.com.omnimind.bot.agent.runtime

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure calendar conversion. No connection, Agent session, or state changes. */
internal object NaLocalTime {
    fun convert(args: Map<String, Any?>): Map<String, String> {
        val zone = ZoneId.of(args["timezone"]?.toString()?.trim().orEmpty())
        val local = args["localTime"]?.toString()?.trim()
        val at = args["at"]?.toString()?.trim()
        require((local.isNullOrEmpty()) != (at.isNullOrEmpty())) { "请选择一种时间转换方式。" }
        val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
        if (!at.isNullOrEmpty()) {
            return mapOf("localTime" to formatter.format(Instant.parse(at).atZone(zone)), "timezone" to zone.id)
        }
        require(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}").matches(local!!)) { "请选择完整的日期和时间。" }
        val wall = LocalDateTime.parse(local, formatter.withResolverStyle(java.time.format.ResolverStyle.STRICT))
        val offsets = zone.rules.getValidOffsets(wall)
        require(offsets.isNotEmpty()) { "该时区因夏令时没有这个时间，请选择其他时间。" }
        require(offsets.size == 1) { "该时间因夏令时出现两次，请在高级设置填写带明确偏移的 ISO 时间。" }
        return mapOf("at" to wall.toInstant(offsets.single()).toString(), "timezone" to zone.id)
    }
}
