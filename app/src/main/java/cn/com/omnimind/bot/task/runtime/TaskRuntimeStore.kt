package cn.com.omnimind.bot.task.runtime

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Small durable queue for tasks that are owned by [TaskRuntime].
 *
 * This is deliberately only the launch envelope, not the agent transcript.
 * Conversation history remains the source of truth for model/tool progress;
 * the envelope lets the service recreate the runner after a process restart.
 */
internal object TaskRuntimeStore {
    private const val TAG = "TaskRuntimeStore"
    private const val PREFS = "task_runtime_store_v1"
    private const val RECORDS_KEY = "records"
    private val lock = Any()
    private val gson = Gson()
    private val recordsType = object : TypeToken<Map<String, StoredTask>>() {}.type

    internal enum class Status {
        QUEUED,
        RUNNING,
    }

    internal data class StoredTask(
        val taskId: String,
        val kind: String,
        val payload: Map<String, Any?>,
        val status: Status = Status.QUEUED,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    fun putAgent(context: Context, taskId: String, payload: Map<String, Any?>): Boolean {
        val normalized = taskId.trim()
        if (normalized.isEmpty()) return false
        return update(context) { records ->
            records + (normalized to StoredTask(
                taskId = normalized,
                kind = "agent",
                payload = payload,
                status = Status.QUEUED,
            ))
        }
    }

    fun markRunning(context: Context, taskId: String): Boolean {
        val normalized = taskId.trim()
        return update(context) { records ->
            val record = records[normalized] ?: return@update records
            records + (normalized to record.copy(
                status = Status.RUNNING,
                updatedAt = System.currentTimeMillis(),
            ))
        }
    }

    fun remove(context: Context, taskId: String): Boolean {
        val normalized = taskId.trim()
        return update(context) { records -> records - normalized }
    }

    fun listPending(context: Context): List<StoredTask> = synchronized(lock) {
        read(context).values
            .filter { it.kind == "agent" }
            .sortedBy { it.updatedAt }
    }

    private fun update(
        context: Context,
        transform: (Map<String, StoredTask>) -> Map<String, StoredTask>,
    ): Boolean = synchronized(lock) {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = transform(read(preferences))
        preferences.edit().putString(RECORDS_KEY, gson.toJson(next)).commit()
    }

    private fun read(context: Context): Map<String, StoredTask> {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return read(preferences)
    }

    private fun read(preferences: android.content.SharedPreferences): Map<String, StoredTask> {
        val json = preferences.getString(RECORDS_KEY, null) ?: return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, StoredTask>>(json, recordsType) ?: emptyMap()
        }.onFailure {
            OmniLog.e(TAG, "Unable to decode task runtime records: ${it.message}")
        }.getOrDefault(emptyMap())
    }
}
