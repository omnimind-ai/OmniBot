package cn.com.omnimind.bot.agent

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import cn.com.omnimind.baselib.permission.PermissionRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

data class CalendarEventCreateRequest(
    val title: String,
    val startAt: String,
    val endAt: String,
    val calendarId: String?,
    val description: String?,
    val location: String?,
    val timezone: String?,
    val allDay: Boolean,
    val reminderMinutes: List<Int>
)

data class CalendarEventListRequest(
    val calendarId: String?,
    val startAt: String?,
    val endAt: String?,
    val query: String?,
    val limit: Int?
)

/** An omitted limit means that the Android calendar provider decides the result size. */
internal fun resolveCalendarListLimit(raw: Int?): Int? {
    require(raw == null || raw > 0) { "limit 必须大于 0" }
    return raw
}

data class CalendarEventUpdateRequest(
    val eventId: String,
    val title: String?,
    val startAt: String?,
    val endAt: String?,
    val description: String?,
    val location: String?,
    val timezone: String?,
    val allDay: Boolean?,
    val reminderMinutes: List<Int>?
)

class AgentCalendarToolService(
    private val context: Context
) {

    private data class EventSnapshot(
        val eventId: Long,
        val calendarId: Long,
        val title: String,
        val description: String,
        val location: String,
        val startMillis: Long,
        val endMillis: Long,
        val timezone: String,
        val allDay: Boolean
    )

    fun hasCalendarPermissions(): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }

    suspend fun requestCalendarPermissions(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            PermissionRequest.requestPermissions(
                context,
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                )
            ) { result ->
                if (!continuation.isCompleted) {
                    val granted = result[Manifest.permission.READ_CALENDAR] == true &&
                        result[Manifest.permission.WRITE_CALENDAR] == true
                    continuation.resume(granted)
                }
            }
        }
    }

    fun listCalendars(
        writableOnly: Boolean,
        visibleOnly: Boolean
    ): List<Map<String, Any?>> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_COLOR
        )

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (writableOnly) {
            selectionParts += "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
            selectionArgs += CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()
        }
        if (visibleOnly) {
            selectionParts += "${CalendarContract.Calendars.VISIBLE} = 1"
        }

        val selection = selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val orderBy = "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"

        val items = mutableListOf<Map<String, Any?>>()
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs.toTypedArray(),
            orderBy
        )

        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val displayName = it.getString(1).orEmpty()
                val accountName = it.getString(2).orEmpty()
                val accountType = it.getString(3).orEmpty()
                val visible = it.getInt(4) == 1
                val accessLevel = it.getInt(5)
                val primary = it.getInt(6) == 1
                val color = it.getInt(7)

                items += mapOf(
                    "calendarId" to id.toString(),
                    "displayName" to displayName,
                    "accountName" to accountName,
                    "accountType" to accountType,
                    "visible" to visible,
                    "accessLevel" to accessLevel,
                    "isPrimary" to primary,
                    "color" to color,
                    "isWritable" to (accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                )
            }
        }

        return items
    }

    fun createEvent(request: CalendarEventCreateRequest): Map<String, Any?> {
        val calendarId = resolveWritableCalendarId(request.calendarId)
        val zone = resolveZone(request.timezone)
        val start = parseDateTime(request.startAt, zone)
        val end = parseDateTime(request.endAt, zone)
        val (normalizedStart, normalizedEnd) = normalizeEventRange(start, end, request.allDay)

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, request.title.trim())
            put(CalendarContract.Events.DTSTART, normalizedStart.toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, normalizedEnd.toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            put(CalendarContract.Events.ALL_DAY, if (request.allDay) 1 else 0)
            if (!request.description.isNullOrBlank()) {
                put(CalendarContract.Events.DESCRIPTION, request.description.trim())
            }
            if (!request.location.isNullOrBlank()) {
                put(CalendarContract.Events.EVENT_LOCATION, request.location.trim())
            }
            put(CalendarContract.Events.HAS_ALARM, if (request.reminderMinutes.isNotEmpty()) 1 else 0)
        }

        val insertedUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw IllegalStateException("创建日程失败")
        val eventId = insertedUri.lastPathSegment?.toLongOrNull()
            ?: throw IllegalStateException("创建日程失败：无法获取 eventId")

        if (request.reminderMinutes.isNotEmpty()) {
            replaceReminders(eventId, request.reminderMinutes)
        }

        return mapOf(
            "success" to true,
            "eventId" to eventId.toString(),
            "calendarId" to calendarId.toString(),
            "title" to request.title.trim(),
            "startAt" to normalizedStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "endAt" to normalizedEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "timezone" to zone.id,
            "allDay" to request.allDay,
            "reminderMinutes" to request.reminderMinutes,
            "summary" to "已创建日程“${request.title.trim()}”"
        )
    }

    fun listEvents(request: CalendarEventListRequest): Map<String, Any?> {
        val limit = resolveCalendarListLimit(request.limit)
        val defaultZone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(defaultZone)

        val rangeStart = request.startAt
            ?.takeIf { it.isNotBlank() }
            ?.let { parseDateTime(it, defaultZone) }
            ?: now.minusDays(30)
        val rangeEnd = request.endAt
            ?.takeIf { it.isNotBlank() }
            ?.let { parseDateTime(it, defaultZone) }
            ?: now.plusDays(180)

        require(rangeEnd.toInstant().toEpochMilli() > rangeStart.toInstant().toEpochMilli()) {
            "查询结束时间必须晚于开始时间"
        }

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, rangeStart.toInstant().toEpochMilli())
        ContentUris.appendId(builder, rangeEnd.toInstant().toEpochMilli())

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_TIMEZONE
        )

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        request.calendarId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val calendarId = it.toLongOrNull()
                ?: throw IllegalArgumentException("calendarId 必须为数字")
            selectionParts += "${CalendarContract.Instances.CALENDAR_ID} = ?"
            selectionArgs += calendarId.toString()
        }

        request.query?.trim()?.takeIf { it.isNotEmpty() }?.let { query ->
            selectionParts += "(${CalendarContract.Instances.TITLE} LIKE ? OR ${CalendarContract.Instances.EVENT_LOCATION} LIKE ?)"
            val pattern = "%$query%"
            selectionArgs += pattern
            selectionArgs += pattern
        }

        val selection = selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val orderBy = "${CalendarContract.Instances.BEGIN} ASC"

        val items = mutableListOf<Map<String, Any?>>()
        val cursor = context.contentResolver.query(
            builder.build(),
            projection,
            selection,
            selectionArgs.toTypedArray(),
            orderBy
        )

        cursor?.use {
            while (it.moveToNext() && (limit == null || items.size < limit)) {
                val eventId = it.getLong(0)
                val calendarId = it.getLong(1)
                val title = it.getString(2).orEmpty()
                val beginMillis = it.getLong(3)
                val endMillis = it.getLong(4)
                val location = it.getString(5).orEmpty()
                val allDay = it.getInt(6) == 1
                val timezone = it.getString(7).orEmpty().ifBlank { defaultZone.id }
                val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(defaultZone)

                items += mapOf(
                    "eventId" to eventId.toString(),
                    "calendarId" to calendarId.toString(),
                    "title" to title,
                    "startAt" to Instant.ofEpochMilli(beginMillis).atZone(zone)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "endAt" to Instant.ofEpochMilli(endMillis).atZone(zone)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "location" to location,
                    "timezone" to zone.id,
                    "allDay" to allDay
                )
            }
        }

        return mapOf(
            "success" to true,
            "count" to items.size,
            "limit" to limit,
            "rangeStart" to rangeStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "rangeEnd" to rangeEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "items" to items
        )
    }

    fun updateEvent(request: CalendarEventUpdateRequest): Map<String, Any?> {
        val eventId = request.eventId.trim().toLongOrNull()
            ?: throw IllegalArgumentException("eventId 必须为数字")
        val current = loadEventSnapshot(eventId)
            ?: throw IllegalArgumentException("未找到对应日程")

        val zone = resolveZone(request.timezone ?: current.timezone)
        val allDay = request.allDay ?: current.allDay
        val currentStart = Instant.ofEpochMilli(current.startMillis).atZone(zone)
        val currentEnd = Instant.ofEpochMilli(current.endMillis).atZone(zone)

        val nextStart = request.startAt?.takeIf { it.isNotBlank() }?.let { parseDateTime(it, zone) }
            ?: currentStart
        val nextEnd = request.endAt?.takeIf { it.isNotBlank() }?.let { parseDateTime(it, zone) }
            ?: currentEnd
        val (normalizedStart, normalizedEnd) = normalizeEventRange(nextStart, nextEnd, allDay)

        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, request.title?.trim()?.ifBlank { current.title } ?: current.title)
            put(
                CalendarContract.Events.DESCRIPTION,
                request.description?.trim() ?: current.description
            )
            put(
                CalendarContract.Events.EVENT_LOCATION,
                request.location?.trim() ?: current.location
            )
            put(CalendarContract.Events.DTSTART, normalizedStart.toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, normalizedEnd.toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            if (request.reminderMinutes != null) {
                put(CalendarContract.Events.HAS_ALARM, if (request.reminderMinutes.isNotEmpty()) 1 else 0)
            }
        }

        val updated = context.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            values,
            null,
            null
        )
        if (updated <= 0) {
            throw IllegalStateException("更新日程失败")
        }

        if (request.reminderMinutes != null) {
            replaceReminders(eventId, request.reminderMinutes)
        }

        return mapOf(
            "success" to true,
            "eventId" to eventId.toString(),
            "title" to (request.title?.trim()?.ifBlank { current.title } ?: current.title),
            "startAt" to normalizedStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "endAt" to normalizedEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "timezone" to zone.id,
            "allDay" to allDay,
            "reminderMinutes" to (request.reminderMinutes ?: loadReminderMinutes(eventId)),
            "summary" to "已更新日程“${request.title?.trim()?.ifBlank { current.title } ?: current.title}”"
        )
    }

    fun deleteEvent(eventIdRaw: String): Map<String, Any?> {
        val eventId = eventIdRaw.trim().toLongOrNull()
            ?: throw IllegalArgumentException("eventId 必须为数字")
        val snapshot = loadEventSnapshot(eventId)
            ?: throw IllegalArgumentException("未找到对应日程")

        val deleted = context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            null,
            null
        )
        if (deleted <= 0) {
            throw IllegalStateException("删除日程失败")
        }

        return mapOf(
            "success" to true,
            "eventId" to eventId.toString(),
            "summary" to "已删除日程“${snapshot.title}”"
        )
    }

    private fun loadEventSnapshot(eventId: Long): EventSnapshot? {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.ALL_DAY
        )

        val cursor = context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            projection,
            null,
            null,
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                return EventSnapshot(
                    eventId = it.getLong(0),
                    calendarId = it.getLong(1),
                    title = it.getString(2).orEmpty(),
                    description = it.getString(3).orEmpty(),
                    location = it.getString(4).orEmpty(),
                    startMillis = it.getLong(5),
                    endMillis = it.getLong(6),
                    timezone = it.getString(7).orEmpty().ifBlank { ZoneId.systemDefault().id },
                    allDay = it.getInt(8) == 1
                )
            }
        }
        return null
    }

    private fun resolveWritableCalendarId(rawCalendarId: String?): Long {
        val fromArg = rawCalendarId?.trim()?.takeIf { it.isNotEmpty() }
        if (fromArg != null) {
            val id = fromArg.toLongOrNull() ?: throw IllegalArgumentException("calendarId 必须为数字")
            if (!isWritableCalendar(id)) {
                throw IllegalArgumentException("指定 calendarId 不可写")
            }
            return id
        }

        val calendars = listCalendars(writableOnly = true, visibleOnly = true)
        val preferred = calendars.firstOrNull { it["isPrimary"] == true } ?: calendars.firstOrNull()
            ?: throw IllegalStateException("未找到可写日历")
        return preferred["calendarId"]?.toString()?.toLongOrNull()
            ?: throw IllegalStateException("未找到可写日历")
    }

    private fun isWritableCalendar(calendarId: Long): Boolean {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )

        val selection = "${CalendarContract.Calendars._ID} = ?"
        val args = arrayOf(calendarId.toString())
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            args,
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val accessLevel = it.getInt(1)
                val visible = it.getInt(2) == 1
                return accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR && visible
            }
        }
        return false
    }

    private fun replaceReminders(eventId: Long, reminderMinutes: List<Int>) {
        context.contentResolver.delete(
            CalendarContract.Reminders.CONTENT_URI,
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString())
        )

        val normalized = reminderMinutes
            .map { it.coerceAtLeast(0) }
            .distinct()
            .sorted()

        normalized.forEach { minutes ->
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
        }
    }

    private fun loadReminderMinutes(eventId: Long): List<Int> {
        val projection = arrayOf(
            CalendarContract.Reminders.MINUTES
        )
        val cursor = context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            projection,
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            "${CalendarContract.Reminders.MINUTES} ASC"
        )

        val result = mutableListOf<Int>()
        cursor?.use {
            while (it.moveToNext()) {
                result += it.getInt(0)
            }
        }
        return result
    }

    private fun normalizeEventRange(
        start: ZonedDateTime,
        end: ZonedDateTime,
        allDay: Boolean
    ): Pair<ZonedDateTime, ZonedDateTime> {
        val normalizedStart: ZonedDateTime
        val normalizedEnd: ZonedDateTime

        if (allDay) {
            normalizedStart = start.toLocalDate().atStartOfDay(start.zone)
            normalizedEnd = end.toLocalDate().plusDays(1).atStartOfDay(start.zone)
        } else {
            normalizedStart = start
            normalizedEnd = end
        }

        require(normalizedEnd.toInstant().toEpochMilli() > normalizedStart.toInstant().toEpochMilli()) {
            "结束时间必须晚于开始时间"
        }

        return normalizedStart to normalizedEnd
    }

    private fun parseDateTime(value: String, zone: ZoneId): ZonedDateTime {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "时间不能为空" }

        runCatching { return ZonedDateTime.parse(normalized) }
        runCatching { return OffsetDateTime.parse(normalized).toZonedDateTime().withZoneSameInstant(zone) }
        runCatching { return Instant.parse(normalized).atZone(zone) }
        runCatching { return LocalDateTime.parse(normalized).atZone(zone) }

        throw IllegalArgumentException("无法解析时间：$normalized")
    }

    private fun resolveZone(rawZone: String?): ZoneId {
        val zone = rawZone?.trim().orEmpty()
        if (zone.isEmpty()) return ZoneId.systemDefault()
        return runCatching { ZoneId.of(zone) }.getOrElse {
            throw IllegalArgumentException("Invalid timezone: $zone")
        }
    }
}
