package cn.com.omnimind.bot.util

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.activity.MainActivity
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

object TaskRuntimeSettings {
    private const val TAG = "[TaskRuntimeSettings]"
    private const val PREFS_NAME = "OmnibotSettings"
    private const val FLUTTER_PREFS_NAME = "FlutterSharedPreferences"
    private const val KEY_PREVENT_SLEEP = "prevent_screen_sleep_during_tasks"
    private const val KEY_NOTIFY = "task_completion_notification_enabled"
    private const val FLUTTER_KEY_PREVENT_SLEEP = "flutter.$KEY_PREVENT_SLEEP"
    private const val FLUTTER_KEY_NOTIFY = "flutter.$KEY_NOTIFY"
    private const val CHANNEL_ID = "task_completion_heads_up_v3"
    private const val OVERLAY_ALERT_CHANNEL_ID = "task_completion_overlay_alert_v1"
    private const val KEY_ACTIVE_NOTIFICATION_ENTRIES = "task_completion_active_notification_entries"
    private const val KEY_NOTIFICATION_COUNTER = "task_completion_notification_counter"
    private const val EXTRA_NOTIFICATION_ID = "task_completion_notification_id"
    private const val EXTRA_NOTIFICATION_CONVERSATION_ID = "task_completion_notification_conversation_id"
    private const val EXTRA_NOTIFICATION_CONVERSATION_MODE = "task_completion_notification_conversation_mode"
    private val activeTaskCount = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    @Volatile
    private var isAppInForeground = false

    @Volatile
    private var isChatPageVisible = false

    @Volatile
    private var currentVisibleConversationId: Long? = null

    @Volatile
    private var currentVisibleConversationMode: String = "agent"

    private data class ActiveNotificationEntry(
        val id: Int,
        val conversationId: Long?,
        val conversationMode: String
    ) {
        fun encode(): String {
            val conversationPart = conversationId?.toString() ?: ""
            return listOf(id.toString(), conversationPart, conversationMode).joinToString("|")
        }

        companion object {
            fun decode(raw: String): ActiveNotificationEntry? {
                val parts = raw.split("|")
                if (parts.size < 3) return null
                val notificationId = parts[0].toIntOrNull() ?: return null
                val conversationId = parts[1].takeIf { it.isNotBlank() }?.toLongOrNull()
                val conversationMode = parts[2].ifBlank { "agent" }
                return ActiveNotificationEntry(
                    id = notificationId,
                    conversationId = conversationId,
                    conversationMode = conversationMode
                )
            }
        }
    }

    fun isPreventSleepEnabled(context: Context): Boolean =
        readBoolean(context, KEY_PREVENT_SLEEP, FLUTTER_KEY_PREVENT_SLEEP, true)

    fun setPreventSleepEnabled(context: Context, enabled: Boolean): Boolean =
        writeBoolean(context, KEY_PREVENT_SLEEP, enabled).also {
            if (enabled && activeTaskCount.get() > 0) {
                acquireWakeLock(context.applicationContext)
                setKeepScreenOnFlag(true)
            } else if (!enabled) {
                releaseWakeLock()
                setKeepScreenOnFlag(false)
            }
        }

    fun isTaskCompletionNotificationEnabled(context: Context): Boolean =
        readBoolean(context, KEY_NOTIFY, FLUTTER_KEY_NOTIFY, true)

    fun setTaskCompletionNotificationEnabled(context: Context, enabled: Boolean): Boolean =
        writeBoolean(context, KEY_NOTIFY, enabled)

    fun onTaskStarted(context: Context) {
        if (activeTaskCount.incrementAndGet() == 1 && isPreventSleepEnabled(context)) {
            acquireWakeLock(context.applicationContext)
            setKeepScreenOnFlag(true)
        }
    }

    fun onTaskFinished(context: Context) {
        val remaining = activeTaskCount.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0) {
            activeTaskCount.set(0)
            releaseWakeLock()
            setKeepScreenOnFlag(false)
        }
    }

    fun attachActivity(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        if (activeTaskCount.get() > 0 && isPreventSleepEnabled(activity)) {
            setKeepScreenOnFlag(true)
        }
    }

    fun detachActivity(activity: Activity) {
        if (currentActivityRef?.get() === activity) {
            setKeepScreenOnFlag(false)
            currentActivityRef = null
        }
    }

    fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        isAppInForeground = true
    }

    fun onActivityPaused(activity: Activity) {
        if (currentActivityRef?.get() === activity) {
            isAppInForeground = false
        }
    }

    fun setVisibleConversation(
        context: Context,
        conversationId: Long?,
        conversationMode: String?,
        visible: Boolean = conversationId?.let { it > 0 } == true
    ) {
        isChatPageVisible = visible
        currentVisibleConversationId = if (visible) conversationId?.takeIf { it > 0 } else null
        currentVisibleConversationMode = conversationMode?.trim()?.ifEmpty { "agent" } ?: "agent"
        if (isChatPageVisible) {
            clearOverlayHint()
            clearTaskCompletionNotificationsForVisibleChat(
                context = context,
                conversationId = currentVisibleConversationId,
                conversationMode = currentVisibleConversationMode
            )
        }
    }

    fun consumeTaskCompletionNotificationIntent(context: Context, intent: Intent?) {
        if (intent == null) return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, Int.MIN_VALUE)
        if (notificationId != Int.MIN_VALUE) {
            cancelTaskCompletionNotification(context, notificationId)
        }
        val conversationId = intent.extras?.let { extras ->
            when (val raw = extras.get(EXTRA_NOTIFICATION_CONVERSATION_ID)) {
                is Long -> raw
                is Int -> raw.toLong()
                is String -> raw.toLongOrNull()
                else -> null
            }
        }
        val conversationMode = intent.getStringExtra(EXTRA_NOTIFICATION_CONVERSATION_MODE)
        if (conversationId != null && conversationId > 0) {
            clearTaskCompletionNotificationsForConversation(
                context = context,
                conversationId = conversationId,
                conversationMode = conversationMode
            )
            clearOverlayHint()
        }
    }

    fun notifyTaskFinished(
        context: Context,
        title: String,
        message: String,
        conversationId: Long? = null,
        conversationMode: String? = null
    ) {
        if (!isTaskCompletionNotificationEnabled(context)) return
        if (shouldSuppressNotificationForVisibleConversation(conversationId, conversationMode)) {
            clearOverlayHint()
            clearTaskCompletionNotificationsForVisibleChat(
                context = context,
                conversationId = conversationId,
                conversationMode = conversationMode
            )
            return
        }
        val normalizedMode = normalizeConversationMode(conversationMode)
        val petHintShown = showOverlayHint(
            context = context,
            message = compactOverlayCompletionText(title, message),
            conversationId = conversationId,
            conversationMode = normalizedMode
        )
        if (!canPostNotification(context)) return
        ensureChannel(context, quiet = petHintShown)
        val route = TaskCompletionNavigator.buildChatRoute(conversationId, conversationMode)
        val notificationId = nextNotificationId(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra("route", route)
            putExtra("needClear", false)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_NOTIFICATION_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_NOTIFICATION_CONVERSATION_MODE, normalizedMode)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            flags
        )
        val body = message.ifBlank { "Task completed. Tap to view details." }
        val notification = NotificationCompat.Builder(
            context,
            if (petHintShown) OVERLAY_ALERT_CHANNEL_ID else CHANNEL_ID
        )
            .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: R.mipmap.ic_launcher)
            .setContentTitle(title.ifBlank { "Omnibot task completed" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(
                if (petHintShown) {
                    NotificationCompat.VISIBILITY_SECRET
                } else {
                    NotificationCompat.VISIBILITY_PUBLIC
                }
            )
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .apply {
                if (petHintShown) {
                    setDefaults(NotificationCompat.DEFAULT_ALL)
                    priority = NotificationCompat.PRIORITY_DEFAULT
                    setVibrate(longArrayOf(0, 250, 120, 250))
                    setLocalOnly(true)
                } else {
                    setDefaults(NotificationCompat.DEFAULT_ALL)
                    priority = NotificationCompat.PRIORITY_HIGH
                    setVibrate(longArrayOf(0, 250, 120, 250))
                }
            }
            .build()
        NotificationManagerCompat.from(context).notify(
            notificationId,
            notification
        )
        registerActiveNotification(
            context,
            ActiveNotificationEntry(
                id = notificationId,
                conversationId = conversationId?.takeIf { it > 0 },
                conversationMode = normalizedMode
            )
        )
    }

    private fun readBoolean(
        context: Context,
        nativeKey: String,
        flutterKey: String,
        defaultValue: Boolean
    ): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(nativeKey)) return prefs.getBoolean(nativeKey, defaultValue)
            context.getSharedPreferences(FLUTTER_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(flutterKey, defaultValue)
        } catch (e: Exception) {
            OmniLog.e(TAG, "read setting failed: ${e.message}")
            defaultValue
        }
    }

    private fun writeBoolean(context: Context, key: String, enabled: Boolean): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(key, enabled)
                .commit()
        } catch (e: Exception) {
            OmniLog.e(TAG, "write setting failed: ${e.message}")
            false
        }
    }

    private fun acquireWakeLock(context: Context) {
        runCatching {
            val current = wakeLock
            if (current?.isHeld == true) return
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                // Keep only the CPU available for non-GUI Agent work. The
                // Activity's FLAG_KEEP_SCREEN_ON remains the independent UI
                // policy, and a user-initiated screen-off is never reversed.
                PowerManager.PARTIAL_WAKE_LOCK,
                "Omnibot:TaskRuntime"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure {
            OmniLog.e(TAG, "acquire wake lock failed: ${it.message}")
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }.onFailure {
            OmniLog.e(TAG, "release wake lock failed: ${it.message}")
        }
    }

    private fun setKeepScreenOnFlag(enabled: Boolean) {
        mainHandler.post {
            val activity = currentActivityRef?.get() ?: return@post
            runCatching {
                if (enabled) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }.onFailure {
                OmniLog.e(TAG, "set keep screen on flag failed: ${it.message}")
            }
        }
    }

    private fun canPostNotification(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context, quiet: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (quiet) {
            manager.createNotificationChannel(
                NotificationChannel(
                    OVERLAY_ALERT_CHANNEL_ID,
                    "Task completion overlay alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Sound and vibration alerts when the floating Omnibot bubble shows task completion"
                    enableLights(false)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 120, 250)
                    val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(soundUri, attributes)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                    setShowBadge(true)
                }
            )
            return
        }

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Task completion reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders after OmniAi, Agent, and chat tasks finish"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 120, 250)
                setSound(soundUri, attributes)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
        )
    }

    private fun shouldSuppressNotificationForVisibleConversation(
        conversationId: Long?,
        conversationMode: String?
    ): Boolean {
        if (!isAppInForeground || !isChatPageVisible) return false
        val targetId = conversationId?.takeIf { it > 0 } ?: return true
        val visibleId = currentVisibleConversationId
        if (visibleId == null) {
            return normalizeConversationMode(currentVisibleConversationMode) ==
                normalizeConversationMode(conversationMode)
        }
        return sameConversationTarget(
            leftConversationId = visibleId,
            leftConversationMode = currentVisibleConversationMode,
            rightConversationId = targetId,
            rightConversationMode = conversationMode
        )
    }

    private fun normalizeConversationMode(mode: String?): String {
        return when (mode?.trim()?.lowercase()) {
            null, "", "normal", "codex", "acp", "coding" -> "agent"
            "chat", "chatonly", "chat-only" -> "chat_only"
            else -> mode.trim().lowercase()
        }
    }

    private fun sameConversationTarget(
        leftConversationId: Long?,
        leftConversationMode: String?,
        rightConversationId: Long?,
        rightConversationMode: String?
    ): Boolean {
        return leftConversationId != null &&
            rightConversationId != null &&
            leftConversationId == rightConversationId &&
            normalizeConversationMode(leftConversationMode) == normalizeConversationMode(rightConversationMode)
    }

    private fun compactOverlayCompletionText(title: String, message: String): String {
        val cleanedMessage = sanitizeCompletionText(message)
        val cleanedTitle = sanitizeCompletionText(title)
        val text = when {
            cleanedMessage.isNotBlank() &&
                !isGenericCompletionMessage(cleanedMessage) -> cleanedMessage
            cleanedTitle.isNotBlank() && cleanedMessage.isNotBlank() -> "$cleanedTitle：$cleanedMessage"
            cleanedTitle.isNotBlank() -> cleanedTitle
            cleanedMessage.isNotBlank() -> cleanedMessage
            else -> "任务已完成"
        }
        return text.limitForOverlay()
    }

    private fun sanitizeCompletionText(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private fun isGenericCompletionMessage(value: String): Boolean {
        val normalized = value.trim()
        return normalized.equals("任务完成", ignoreCase = true) ||
            normalized.equals("任务已完成", ignoreCase = true) ||
            normalized.equals("任务已完成，点击查看详情", ignoreCase = true) ||
            normalized.equals("任务已完成，点击查看详情。", ignoreCase = true) ||
            normalized.equals("Task completed", ignoreCase = true) ||
            normalized.equals("Task completed. Tap to view details.", ignoreCase = true) ||
            normalized.equals("Tap to view details.", ignoreCase = true)
    }

    private fun String.limitForOverlay(maxLength: Int = 48): String {
        if (length <= maxLength) return this
        return take(maxLength - 1).trimEnd() + "…"
    }
    private fun playTaskCompletionAlertSound(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: return
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ringtone.play()
        }.onFailure {
            OmniLog.w(TAG, "play task completion alert sound failed: ${it.message}")
        }
    }

    private fun nextNotificationId(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getInt(KEY_NOTIFICATION_COUNTER, 4000)
        val next = if (current >= Int.MAX_VALUE - 1) 4000 else current + 1
        prefs.edit().putInt(KEY_NOTIFICATION_COUNTER, next).apply()
        return next
    }

    private fun registerActiveNotification(context: Context, entry: ActiveNotificationEntry) {
        val entries = readActiveNotificationEntries(context)
            .filterNot { it.id == entry.id }
            .toMutableSet()
        entries.add(entry)
        persistActiveNotificationEntries(context, entries)
    }

    private fun cancelTaskCompletionNotification(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
        val remainingEntries = readActiveNotificationEntries(context)
            .filterNot { it.id == notificationId }
            .toSet()
        persistActiveNotificationEntries(context, remainingEntries)
    }

    private fun clearTaskCompletionNotificationsForConversation(
        context: Context,
        conversationId: Long?,
        conversationMode: String?
    ) {
        val targetId = conversationId?.takeIf { it > 0 } ?: return
        val normalizedMode = normalizeConversationMode(conversationMode)
        val allEntries = readActiveNotificationEntries(context)
        if (allEntries.isEmpty()) return
        val remainingEntries = mutableSetOf<ActiveNotificationEntry>()
        allEntries.forEach { entry ->
            if (sameConversationTarget(
                    leftConversationId = entry.conversationId,
                    leftConversationMode = entry.conversationMode,
                    rightConversationId = targetId,
                    rightConversationMode = normalizedMode
                )
            ) {
                NotificationManagerCompat.from(context).cancel(entry.id)
            } else {
                remainingEntries.add(entry)
            }
        }
        persistActiveNotificationEntries(context, remainingEntries)
    }

    private fun clearTaskCompletionNotificationsForVisibleChat(
        context: Context,
        conversationId: Long?,
        conversationMode: String?
    ) {
        val targetId = conversationId?.takeIf { it > 0 }
        if (targetId == null) {
            clearAllTaskCompletionNotifications(context)
            return
        }
        val normalizedMode = normalizeConversationMode(conversationMode)
        val allEntries = readActiveNotificationEntries(context)
        if (allEntries.isEmpty()) return
        val remainingEntries = mutableSetOf<ActiveNotificationEntry>()
        allEntries.forEach { entry ->
            val shouldCancel = entry.conversationId == null ||
                sameConversationTarget(
                    leftConversationId = entry.conversationId,
                    leftConversationMode = entry.conversationMode,
                    rightConversationId = targetId,
                    rightConversationMode = normalizedMode
                )
            if (shouldCancel) {
                NotificationManagerCompat.from(context).cancel(entry.id)
            } else {
                remainingEntries.add(entry)
            }
        }
        persistActiveNotificationEntries(context, remainingEntries)
    }

    private fun clearAllTaskCompletionNotifications(context: Context) {
        val allEntries = readActiveNotificationEntries(context)
        if (allEntries.isEmpty()) return
        allEntries.forEach { entry ->
            NotificationManagerCompat.from(context).cancel(entry.id)
        }
        persistActiveNotificationEntries(context, emptySet())
    }

    private fun readActiveNotificationEntries(context: Context): Set<ActiveNotificationEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawEntries = prefs.getStringSet(KEY_ACTIVE_NOTIFICATION_ENTRIES, emptySet()) ?: emptySet()
        return rawEntries.mapNotNullTo(linkedSetOf()) { raw ->
            ActiveNotificationEntry.decode(raw)
        }
    }

    private fun persistActiveNotificationEntries(
        context: Context,
        entries: Set<ActiveNotificationEntry>
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(
                KEY_ACTIVE_NOTIFICATION_ENTRIES,
                entries.mapTo(linkedSetOf()) { it.encode() }
            )
            .apply()
    }

    private fun showOverlayHint(
        context: Context,
        message: String,
        conversationId: Long?,
        conversationMode: String?
    ): Boolean {
        val appContext = context.applicationContext
        return runCatching {
            DraggableBallInstance.showTaskCompletionHint(
                message.ifBlank { "Task completed" }
            ) {
                TaskCompletionNavigator.navigateBackToChat(
                    appContext,
                    conversationId,
                    conversationMode
                )
            }
        }.getOrElse {
            OmniLog.w(TAG, "show overlay completion hint failed: ${it.message}")
            false
        }
    }

    private fun clearOverlayHint() {
        runCatching {
            DraggableBallInstance.clearTaskCompletionHint()
        }.onFailure {
            OmniLog.w(TAG, "clear overlay completion hint failed: ${it.message}")
        }
    }
}
