package cn.com.omnimind.bot.task.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import cn.com.omnimind.baselib.util.OmniLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Stable entry point for long-running task lifecycle.
 *
 * ACP owns the Agent turn lifecycle. This object only keeps the Android
 * foreground-service lease alive while a turn is running.
 */
object TaskRuntime {
    private const val TAG = "TaskRuntime"
    private val activeTaskIds = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun start(context: Context, taskId: String): Boolean {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) {
            OmniLog.w(TAG, "Ignoring task runtime start with empty task id")
            return false
        }
        val newlyActive = activeTaskIds.add(normalizedTaskId)
        return sendStartCommand(context).also { started ->
            if (!started && newlyActive) activeTaskIds.remove(normalizedTaskId)
        }
    }

    @Synchronized
    fun finish(context: Context, taskId: String): Boolean {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) {
            OmniLog.w(TAG, "Ignoring task runtime finish with empty task id")
            return false
        }
        if (!activeTaskIds.remove(normalizedTaskId)) return true
        if (activeTaskIds.isNotEmpty()) return true

        // Do not stop a service that Android has not yet promoted. A short/failed
        // turn can finish before onCreate; stopService then crashes the whole app.
        // Deliver reconciliation to the same service after its foreground handshake.
        return runCatching {
            context.applicationContext.startService(
                Intent(context.applicationContext, TaskRuntimeService::class.java).apply {
                    action = TaskRuntimeService.ACTION_RECONCILE
                },
            )
            true
        }.onFailure { error ->
            OmniLog.w(TAG, "Unable to reconcile task runtime: ${error.message}")
        }.getOrDefault(false)
    }

    internal fun hasActiveTasks(): Boolean = activeTaskIds.isNotEmpty()

    private fun sendStartCommand(context: Context): Boolean {
        val intent = Intent(context.applicationContext, TaskRuntimeService::class.java).apply {
            action = TaskRuntimeService.ACTION_START
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
            true
        }.onFailure { error ->
            // Android may reject a foreground-service start when a turn is
            // restored from a background-only entry point. ACP remains
            // responsible for reporting the turn failure.
            OmniLog.w(
                TAG,
                "Unable to start task runtime foreground service: ${error.message}",
            )
        }.getOrDefault(false)
    }
}
