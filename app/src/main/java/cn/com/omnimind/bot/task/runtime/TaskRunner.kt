package cn.com.omnimind.bot.task.runtime

import kotlinx.coroutines.CoroutineScope

internal interface TaskRunner {
    val kind: String

    fun start(
        task: TaskRuntimeStore.StoredTask,
        parentScope: CoroutineScope,
    ): Boolean
}
