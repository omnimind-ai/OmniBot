package cn.com.omnimind.bot.agent.runtime

import java.time.Instant

internal data class NaDeliveredNotification(
    val id: String,
    val title: String,
    val body: String,
    val conversationId: String?,
)

internal data class NaNotificationBatch(
    val notifications: List<NaDeliveredNotification>,
    val readIds: List<String>,
    val nextCursor: Long,
    val hasMore: Boolean,
    val clearAll: Boolean,
)

internal fun naNotificationIdentifier(value: Any?): String {
    require(value is String && value.matches(Regex("[A-Za-z0-9_-]{1,200}"))) {
        "Na 提醒标识无效。"
    }
    return value
}

internal fun naNotificationCursor(value: Any?): Long {
    require(value is Number && value.toDouble().isFinite() &&
        value.toDouble() >= 0 && value.toDouble() <= 9007199254740991.0 &&
        value.toDouble() == value.toLong().toDouble()) { "Na 提醒游标无效。" }
    return value.toLong()
}

internal fun parseNaNotificationBatch(value: Map<String, Any?>, after: Long): NaNotificationBatch {
    val cursor = naNotificationCursor(value["nextCursor"])
    require(cursor >= after) { "Na 提醒游标不能回退。" }
    val raw = value["notifications"] as? List<*> ?: error("Na 提醒列表无效。")
    val reads = value["readIds"] as? List<*> ?: emptyList<Any>()
    require(raw.size <= 100 && reads.size <= 100) { "Na 提醒分页过大。" }
    val notifications = raw.map { item ->
        val entry = item as? Map<*, *> ?: error("Na 提醒内容无效。")
        val title = entry["title"] as? String ?: error("Na 提醒标题无效。")
        val body = entry["body"] as? String ?: error("Na 提醒正文无效。")
        require(title.isNotBlank() && title.length <= 160 && body.isNotBlank() && body.length <= 8000)
        require(entry["readAt"] == null && entry["deliveredAt"] is String) { "Na 提醒尚未投递或已读。" }
        Instant.parse(entry["deliveredAt"] as String)
        NaDeliveredNotification(naNotificationIdentifier(entry["id"]), title, body,
            entry["conversationId"]?.let(::naNotificationIdentifier))
    }
    require(notifications.map { it.id }.distinct().size == notifications.size) { "Na 提醒分页有重复记录。" }
    require(value["hasMore"] is Boolean && (value["clearAll"] == null || value["clearAll"] is Boolean))
    require(value["hasMore"] != true || cursor > after) { "Na 提醒分页未推进。" }
    return NaNotificationBatch(notifications, reads.map(::naNotificationIdentifier).distinct(), cursor,
        value["hasMore"] == true, value["clearAll"] == true)
}

/** Receipt processing is independent of model/session execution. Stable OS ids
 * make replay after a crash replace the same notification, not a new message. */
internal suspend fun consumeNaNotificationBatch(
    batch: NaNotificationBatch,
    postedIds: Set<String>,
    canPost: () -> Boolean,
    assertCurrent: () -> Unit,
    cancelAll: () -> Unit,
    cancelOne: (String) -> Unit,
    post: suspend (NaDeliveredNotification) -> Unit,
    recordPosted: (String) -> Unit,
    advance: (Long) -> Unit,
): Boolean {
    assertCurrent()
    if (!canPost()) return false // A denied permission must not consume delivery.
    if (batch.clearAll) cancelAll()
    batch.readIds.forEach { assertCurrent(); cancelOne(it) }
    for (notification in batch.notifications) {
        assertCurrent()
        if (notification.id in postedIds) continue
        if (!canPost()) return false
        post(notification)
        assertCurrent()
        recordPosted(notification.id)
    }
    assertCurrent()
    advance(batch.nextCursor)
    return true
}
