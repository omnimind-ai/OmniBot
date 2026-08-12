package cn.com.omnimind.assists

import android.content.Context
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.task.TaskChangeImpl
import cn.com.omnimind.assists.openclaw.OpenClawIdentityResetResult
import cn.com.omnimind.assists.openclaw.OpenClawIdentityResetStatus
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationMutationResult
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationStatus
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationStore
import cn.com.omnimind.assists.openclaw.OpenClawCredentialMutation

class StateMachine {
    private var isInitialized = false
    private var taskManager: TaskManager? = null

    fun isInitialized(): Boolean {
        return isInitialized
    }

    fun init(context: Context) {
        taskManager = TaskManager(context, TaskChangeImpl())
        isInitialized = true
    }

    fun startTask(params: TaskParams) {
        taskManager?.createAndStartTask(params)
    }

    fun cancelChatTask(taskId: String? = null) {
        taskManager?.cancelChatTask(taskId)
    }

    fun resetOpenClawDeviceIdentity(): OpenClawIdentityResetResult =
        taskManager?.resetOpenClawDeviceIdentity()
            ?: OpenClawIdentityResetResult(
                false,
                OpenClawIdentityResetStatus.CORE_UNAVAILABLE,
            )

    fun disableOpenClaw(): OpenClawConfigurationMutationResult =
        taskManager?.disableOpenClaw()
            ?: OpenClawConfigurationMutationResult(
                false,
                OpenClawConfigurationStatus.STORAGE_UNAVAILABLE,
                OpenClawConfigurationStore.snapshot(),
            )

    fun saveOpenClawConfiguration(
        requestId: String,
        expectedGeneration: Long,
        confirmedOrigin: String,
        rawBaseUrl: String,
        userId: String,
        credentialMutation: OpenClawCredentialMutation,
        replacementToken: String?,
        enable: Boolean,
    ): OpenClawConfigurationMutationResult = taskManager?.saveOpenClawConfiguration(
        requestId,
        expectedGeneration,
        confirmedOrigin,
        rawBaseUrl,
        userId,
        credentialMutation,
        replacementToken,
        enable,
    ) ?: OpenClawConfigurationMutationResult(
        false,
        OpenClawConfigurationStatus.STORAGE_UNAVAILABLE,
        OpenClawConfigurationStore.snapshot(),
    )
}
