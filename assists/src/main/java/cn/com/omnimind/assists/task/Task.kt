package cn.com.omnimind.assists.task

import cn.com.omnimind.assists.TaskManager
import cn.com.omnimind.assists.api.enums.TaskFinishType
import cn.com.omnimind.assists.api.enums.TaskType
import cn.com.omnimind.assists.api.interfaces.TaskChangeListener
import cn.com.omnimind.assists.api.interfaces.TaskLifeListener
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 任务基类
 */
abstract class Task(open val taskChangeListener: TaskChangeListener,open val taskManager: TaskManager) : TaskLifeListener {
    private val TAG = "[Task]"

    open var id: String = ""
    @Volatile
    var isRunning: Boolean = false
    open var taskScope = CoroutineScope( Dispatchers.IO)
    open val cancelScope = CoroutineScope( Dispatchers.IO)
    abstract fun getTaskType(): TaskType


    open fun finishTask(block: suspend CoroutineScope.() -> Unit) {
        cancelScope.launch {
            block.invoke(this)
            onTaskStop(TaskFinishType.CANCEL,"")
            onTaskDestroy()
        }

    }

    open lateinit var taskJob: Job

    fun start(block: suspend CoroutineScope.() -> Unit) {
        taskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        taskJob = taskScope.launch {
            onTaskCreated()
            onTaskStarted()
            block.invoke(this)
        }


    }

    override suspend fun onTaskCreated() {
        OmniLog.i(TAG, " task ready to create...")
    }

    init {
        id = java.util.UUID.randomUUID().toString() + System.currentTimeMillis()
    }


    override suspend fun onTaskStarted() {
        taskChangeListener.onTaskStart(getTaskType(),taskManager)
        OmniLog.i(TAG, " task started...")
        isRunning = true

    }

    override suspend fun onTaskStop(finishType: TaskFinishType,message:String) {
        taskChangeListener.onTaskStop(getTaskType(),finishType, message,taskManager)

        OmniLog.i(TAG, " task ready to stop...")
    }

    override suspend fun onTaskDestroy() {
        OmniLog.i(TAG, " task ready to destroy...")
        isRunning = false
        id = ""
    }

    // 新增任务取消的回调方法
    open suspend fun onTaskCancelled() {
        OmniLog.i(TAG, " task was cancelled...")
        isRunning = false
    }

}
