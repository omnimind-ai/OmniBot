package cn.com.omnimind.bot.agent.runtime

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.com.omnimind.baselib.util.AppSecretStore
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.notifications.NaMessageService
import cn.com.omnimind.bot.notifications.NaNotificationOpenActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

internal data class NaNotificationState(
    val enabled: Boolean = false,
    val owner: String = "",
    val lease: String = "",
    val development: Boolean = false,
    val baseUrl: String = "",
    val cursor: Long = 0,
    val postedIds: List<String> = emptyList(),
    val lastSyncAt: String? = null,
    val error: String? = null,
)

/** The user opts in to saving the standalone-instance token for background
 * reception. Project/model keys are never received or stored here. */
internal class NaNotificationStore(context: Context) {
    private val preferences = context.getSharedPreferences("na_notification_delivery", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun read(): NaNotificationState = runCatching {
        val raw = preferences.getString("state", null) ?: return@runCatching NaNotificationState()
        val value = gson.fromJson<Map<String, Any?>>(raw, object : TypeToken<Map<String, Any?>>() {}.type)
        if (value["enabled"] != true) return@runCatching NaNotificationState()
        val owner = value["owner"] as? String ?: error("Missing owner")
        val lease = value["lease"] as? String ?: error("Missing lease")
        require(owner.matches(Regex("na:[0-9a-f]{16}:")) && lease.matches(Regex("[0-9a-f-]{36}")))
        NaNotificationState(true, owner, lease, value["development"] == true,
            value["baseUrl"] as? String ?: "", naNotificationCursor(value["cursor"]),
            (value["postedIds"] as? List<*>)?.map(::naNotificationIdentifier)?.takeLast(1000).orEmpty(),
            value["lastSyncAt"] as? String, value["error"] as? String)
    }.getOrDefault(NaNotificationState())

    fun write(value: NaNotificationState) {
        check(preferences.edit().putString("state", gson.toJson(value)).commit()) { "无法保存 Na 提醒接收进度。" }
    }

    fun requireCurrent(expected: NaNotificationState) {
        val current = read()
        check(current.enabled && current.owner == expected.owner && current.lease == expected.lease) {
            "Na 提醒连接已改变。"
        }
    }
}

internal object NaNotificationDelivery {
    const val CHANNEL = "na_personal_messages"
    private const val SECRET = "na.notifications.development_token"
    private const val NOTIFICATION_ID = 2048122
    val syncMutex = Mutex()
    private val stateLock = Any()

    fun canPost(context: Context): Boolean = runCatching {
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }.getOrDefault(false)

    fun restoreDevelopmentToken(context: Context, development: Boolean, baseUrl: String): String {
        val state = NaNotificationStore(context).read()
        if (!development || !state.enabled || !state.development || state.baseUrl != baseUrl ||
            state.owner != NaCloudConnection(baseUrl).prefix) return ""
        val read = AppSecretStore.readWithStatus(SECRET)
        return if (read.succeeded) read.value.orEmpty() else ""
    }

    fun enable(context: Context, connection: NaCloudConnection, developmentToken: String, cursor: Long,
        assertCurrent: () -> Unit): NaNotificationState = synchronized(stateLock) {
        assertCurrent()
        check(canPost(context)) { "请先允许 Omnibot 发送通知。" }
        val store = NaNotificationStore(context)
        val current = store.read()
        if (current.enabled && current.owner == connection.prefix) return current
        if (!connection.managed) {
            check(developmentToken.isNotBlank()) { "请先配置 Na 开发服务令牌。" }
            check(AppSecretStore.write(SECRET, developmentToken)) { "无法安全保存开发连接，请检查系统凭据存储。" }
        }
        val state = NaNotificationState(true, connection.prefix, UUID.randomUUID().toString(),
            !connection.managed, connection.baseUrl, cursor)
        try { assertCurrent(); store.write(state) } catch (error: Exception) { AppSecretStore.delete(SECRET); throw error }
        state
    }

    fun disable(context: Context) = synchronized(stateLock) {
        val store = NaNotificationStore(context)
        val old = store.read()
        if (!old.enabled) return@synchronized
        // Fence asynchronous requests and pending intents before stopping IO.
        store.write(NaNotificationState())
        AppSecretStore.delete(SECRET)
        cancelOwner(context, old.owner)
        NaMessageService.stopReception(context)
    }

    fun ownerMatches(context: Context, state: NaNotificationState): Boolean =
        state.enabled && NaConversationAccess.currentOwnerPrefix(context) == state.owner

    fun statePayload(context: Context): Map<String, Any?> {
        val state = NaNotificationStore(context).read()
        return mapOf("enabled" to state.enabled, "permissionGranted" to canPost(context),
            "connectionActive" to (state.enabled && NaMessageService.running),
            "lastSyncAt" to state.lastSyncAt, "error" to state.error,
            "developmentCredentialSaved" to (state.enabled && state.development),
            "backgroundMode" to "foreground_connection_with_periodic_recovery")
    }

    fun update(context: Context, expected: NaNotificationState, change: (NaNotificationState) -> NaNotificationState) = synchronized(stateLock) {
        val store = NaNotificationStore(context)
        store.requireCurrent(expected)
        store.write(change(store.read()))
    }

    fun cancelOwner(context: Context, owner: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications.filter { it.tag?.startsWith("na-message:$owner") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun cancel(context: Context, owner: String, id: String) {
        NotificationManagerCompat.from(context).cancel("na-message:$owner$id", NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Na 主动消息", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Na 的主动提醒、关注事项与职责更新"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
    }

    suspend fun consume(context: Context, expected: NaNotificationState, batch: NaNotificationBatch,
        assertConnection: () -> Unit, bindConversation: suspend (NaDeliveredNotification) -> Long?): Boolean {
        val store = NaNotificationStore(context)
        fun guard() { assertConnection(); store.requireCurrent(expected) }
        return consumeNaNotificationBatch(batch, store.read().postedIds.toSet(), { canPost(context) }, ::guard,
            { synchronized(stateLock) { guard(); cancelOwner(context, expected.owner) } },
            { id -> synchronized(stateLock) { guard(); cancel(context, expected.owner, id) } }, { notification ->
                val localId = bindConversation(notification)
                guard()
                ensureChannel(context)
                val intent = Intent(context, NaNotificationOpenActivity::class.java).apply {
                    data = Uri.Builder().scheme("omnibot-internal").authority("na-message")
                        .appendPath(expected.lease).appendPath(notification.id).build()
                    putExtra("naOwner", expected.owner)
                    putExtra("naLease", expected.lease)
                    putExtra("naNotificationId", notification.id)
                    localId?.let { putExtra("conversationId", it) }
                }
                val pending = PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val message = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle(notification.title)
                    .setContentText(notification.body).setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
                    .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .build()
                synchronized(stateLock) {
                    guard()
                    check(canPost(context)) { "Na 消息通知权限已改变。" }
                    NotificationManagerCompat.from(context).notify("na-message:${expected.owner}${notification.id}", NOTIFICATION_ID, message)
                }
            }, { id -> update(context, expected) { it.copy(postedIds = (it.postedIds + id).distinct().takeLast(1000)) } },
            { cursor -> update(context, expected) { it.copy(cursor = cursor, lastSyncAt = Instant.now().toString(), error = null) } })
    }
}

internal suspend fun naNotificationFeedRequest(connection: NaCloudConnection, client: OkHttpClient,
    after: Long, waitMs: Int, assertCurrent: () -> Unit): NaNotificationBatch {
    require(after >= 0 && waitMs in 0..25000)
    val request = Request.Builder().url(connection.apiBaseUrl +
        "/api/notifications/feed?after=$after&limit=100&waitMs=$waitMs")
        .header("Accept", "application/json").tag(NaHttpRequestGuard::class.java, NaHttpRequestGuard(assertCurrent)).build()
    return connection.execute(client, request) { response ->
        val output = ByteArrayOutputStream()
        response.body.byteStream().use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                check(output.size() + count <= 2 * 1024 * 1024) { "Na 提醒响应过大。" }
                output.write(bytes, 0, count)
            }
        }
        check(response.isSuccessful) { "Na 提醒服务暂时不可用（HTTP ${response.code}）。" }
        val value = Gson().fromJson<Map<String, Any?>>(output.toString("UTF-8"), object : TypeToken<Map<String, Any?>>() {}.type)
        assertCurrent()
        parseNaNotificationBatch(value, after)
    }
}
