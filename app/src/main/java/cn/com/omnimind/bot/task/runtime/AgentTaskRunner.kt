package cn.com.omnimind.bot.task.runtime

import cn.com.omnimind.bot.manager.AssistsCoreManager
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope

internal class AgentTaskRunner(
    private val manager: AssistsCoreManager,
) : TaskRunner {
    override val kind: String = "agent"

    override fun start(
        task: TaskRuntimeStore.StoredTask,
        parentScope: CoroutineScope,
    ): Boolean {
        if (task.kind != kind) return false
        val arguments = task.payload.toMutableMap().apply {
            put("__taskRuntimeOwned", true)
        }
        manager.startAgentTaskFromRuntime(
            call = MethodCall("createAgentTask", arguments),
            parentScope = parentScope,
            result = NoOpResult,
        )
        return true
    }

    private object NoOpResult : MethodChannel.Result {
        override fun success(result: Any?) = Unit
        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) = Unit
        override fun notImplemented() = Unit
    }
}
