package cn.com.omnimind.bot.task.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import cn.com.omnimind.baselib.util.OmniLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Stable entry point for long-running task lifecycle.
 *
 * The first migration keeps the existing Agent runner intact and only moves
 * its process-lifetime signal to a foreground service. Future runners (for
 * example DSH) must enter through this same boundary instead of coupling the
 * task to an Activity or Flutter engine.
 */
object TaskRuntime {
    private const val TAG = "TaskRuntime"
    private val activeTaskIds = ConcurrentHashMap.newKeySet<String>()

    fun enqueueAgent(
        context: Context,
        taskId: String,
        payload: Map<String, Any?>,
    ): Boolean {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) return false
        if (!TaskRuntimeStore.putAgent(context, normalizedTaskId, payload)) {
            OmniLog.e(TAG, "Unable to persist agent task taskId=$normalizedTaskId")
            return false
        }
        if (start(context, normalizedTaskId)) return true
        TaskRuntimeStore.remove(context, normalizedTaskId)
        return false
    }

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

    fun finish(context: Context, taskId: String): Boolean {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) {
            OmniLog.w(TAG, "Ignoring task runtime finish with empty task id")
            return false
        }
        activeTaskIds.remove(normalizedTaskId)
        TaskRuntimeStore.remove(context, normalizedTaskId)
        if (activeTaskIds.isNotEmpty() || TaskRuntimeStore.listPending(context).isNotEmpty()) return true

        return runCatching {
            context.applicationContext.stopService(
                Intent(context.applicationContext, TaskRuntimeService::class.java),
            )
            true
        }.onFailure { error ->
            OmniLog.w(
                TAG,
                "Unable to stop task runtime taskId=$normalizedTaskId: ${error.message}",
            )
        }.getOrDefault(false)
    }

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
            // Android may reject a foreground-service start when a task is
            // restored from a background-only entry point. The Agent runner
            // remains responsible for reporting the task failure; this
            // boundary must never crash the existing execution path.
            OmniLog.w(
                TAG,
                "Unable to start task runtime foreground service: ${error.message}",
            )
        }.getOrDefault(false)
    }
}
