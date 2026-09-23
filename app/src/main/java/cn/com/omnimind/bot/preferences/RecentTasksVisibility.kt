package cn.com.omnimind.bot.preferences

import android.app.ActivityManager
import android.content.Context

/** Applies the existing `flutter.hide_from_recents` preference to every app task. */
internal object RecentTasksVisibility {
    private const val KEY = "flutter.hide_from_recents"

    fun read(context: Context): Boolean = preferences(context).getBoolean(KEY, false)

    fun applySaved(context: Context) = apply(context, read(context))

    fun set(context: Context, hidden: Boolean) {
        val previous = read(context)
        try {
            apply(context, hidden)
        } catch (error: Exception) {
            runCatching { apply(context, previous) }
            throw error
        }
        if (!preferences(context).edit().putBoolean(KEY, hidden).commit()) {
            runCatching { apply(context, previous) }
            error("Unable to save recent-task visibility")
        }
    }

    private fun apply(context: Context, hidden: Boolean) {
        val manager = checkNotNull(context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager) {
            "ActivityManager unavailable"
        }
        manager.appTasks.forEach { it.setExcludeFromRecents(hidden) }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
}
