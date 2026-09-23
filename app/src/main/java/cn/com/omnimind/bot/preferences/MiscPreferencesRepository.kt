package cn.com.omnimind.bot.preferences

import android.content.Context
import android.content.SharedPreferences
import cn.com.omnimind.bot.util.TaskRuntimeSettings
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Adapts existing owners and keys; this repository introduces no new persisted state. */
internal class MiscPreferencesRepository private constructor(context: Context) {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    val snapshots = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in FLUTTER_KEYS) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { read() }.flowOn(Dispatchers.IO)

    fun read(): MiscPreferencesSnapshot = MiscPreferencesSnapshot(
        startup = preferences.getString(STARTUP, "resume_last")?.takeIf { it in STARTUP_VALUES } ?: "resume_last",
        recentOnly = preferences.getBoolean(RECENT_ONLY, false),
        hideFromRecents = RecentTasksVisibility.read(application),
        vibration = MMKV.defaultMMKV().decodeBool("app_vibrate", true),
        independentSend = preferences.getBoolean(INDEPENDENT_SEND, true),
        predictiveBack = preferences.getBoolean(PREDICTIVE_BACK, true),
        preventSleep = TaskRuntimeSettings.isPreventSleepEnabled(application),
        completionNotification = TaskRuntimeSettings.isTaskCompletionNotificationEnabled(application),
        habitualHand = preferences.getString(HABITUAL_HAND, "right")?.takeIf { it in HAND_VALUES } ?: "right",
    )

    suspend fun update(operation: MiscPreferenceKey, value: Any?): MiscPreferencesSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (operation) {
                MiscPreferenceKey.Startup -> saveString(STARTUP, requireChoice(value, STARTUP_VALUES))
                MiscPreferenceKey.RecentOnly -> saveBoolean(RECENT_ONLY, requireBoolean(value))
                MiscPreferenceKey.HideFromRecents -> RecentTasksVisibility.set(application, requireBoolean(value))
                MiscPreferenceKey.Vibration -> check(MMKV.defaultMMKV().encode("app_vibrate", requireBoolean(value)))
                MiscPreferenceKey.IndependentSend -> saveBoolean(INDEPENDENT_SEND, requireBoolean(value))
                MiscPreferenceKey.PredictiveBack -> saveBoolean(PREDICTIVE_BACK, requireBoolean(value))
                MiscPreferenceKey.PreventSleep -> check(TaskRuntimeSettings.setPreventSleepEnabled(application, requireBoolean(value)))
                MiscPreferenceKey.CompletionNotification -> check(TaskRuntimeSettings.setTaskCompletionNotificationEnabled(application, requireBoolean(value)))
                MiscPreferenceKey.HabitualHand -> saveString(HABITUAL_HAND, requireChoice(value, HAND_VALUES))
            }
            read()
        }
    }

    private fun saveBoolean(key: String, value: Boolean) {
        check(preferences.edit().putBoolean(key, value).commit()) { "Unable to save $key" }
    }

    private fun saveString(key: String, value: String) {
        check(preferences.edit().putString(key, value).commit()) { "Unable to save $key" }
    }

    private fun requireBoolean(value: Any?): Boolean = value as? Boolean
        ?: throw IllegalArgumentException("Boolean preference value required")

    private fun requireChoice(value: Any?, allowed: Set<String>): String = (value as? String)
        ?.takeIf { it in allowed } ?: throw IllegalArgumentException("Unsupported preference value")

    companion object {
        private const val STARTUP = "flutter.chat_startup_behavior"
        private const val RECENT_ONLY = "flutter.recent_conversations_only_enabled"
        private const val HIDE_FROM_RECENTS = "flutter.hide_from_recents"
        private const val INDEPENDENT_SEND = "flutter.use_independent_chat_send_button"
        private const val PREDICTIVE_BACK = "flutter.predictive_back_enabled"
        private const val HABITUAL_HAND = "flutter.habitual_hand"
        private const val PREVENT_SLEEP = "flutter.prevent_screen_sleep_during_tasks"
        private const val COMPLETION_NOTIFICATION = "flutter.task_completion_notification_enabled"
        private val FLUTTER_KEYS = setOf(STARTUP, RECENT_ONLY, HIDE_FROM_RECENTS, INDEPENDENT_SEND,
            PREDICTIVE_BACK, HABITUAL_HAND, PREVENT_SLEEP, COMPLETION_NOTIFICATION)
        private val STARTUP_VALUES = setOf("resume_last", "new_conversation")
        private val HAND_VALUES = setOf("left", "right")
        @Volatile private var instance: MiscPreferencesRepository? = null
        fun get(context: Context): MiscPreferencesRepository = instance ?: synchronized(this) {
            instance ?: MiscPreferencesRepository(context).also { instance = it }
        }
    }
}

internal data class MiscPreferencesSnapshot(
    val startup: String,
    val recentOnly: Boolean,
    val hideFromRecents: Boolean,
    val vibration: Boolean,
    val independentSend: Boolean,
    val predictiveBack: Boolean,
    val preventSleep: Boolean,
    val completionNotification: Boolean,
    val habitualHand: String,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "startup" to startup, "recentOnly" to recentOnly, "hideFromRecents" to hideFromRecents,
        "vibration" to vibration, "independentSend" to independentSend,
        "predictiveBack" to predictiveBack, "preventSleep" to preventSleep,
        "completionNotification" to completionNotification, "habitualHand" to habitualHand,
    )
}

/** Typed native operations; only the channel maps their stable wire names. */
internal enum class MiscPreferenceKey(val wireName: String) {
    Startup("startup"), RecentOnly("recentOnly"), HideFromRecents("hideFromRecents"),
    Vibration("vibration"), IndependentSend("independentSend"), PredictiveBack("predictiveBack"),
    PreventSleep("preventSleep"), CompletionNotification("completionNotification"), HabitualHand("habitualHand");

    companion object {
        fun fromWire(value: String?): MiscPreferenceKey = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unknown miscellaneous preference")
    }
}
