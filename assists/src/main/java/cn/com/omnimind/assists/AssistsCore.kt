package cn.com.omnimind.assists

import android.content.Context
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.openclaw.OpenClawIdentityResetResult
import cn.com.omnimind.assists.openclaw.OpenClawIdentityResetStatus
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationMutationResult
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationStatus
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationStore
import cn.com.omnimind.assists.openclaw.OpenClawCredentialMutation

/**
 * Facade for the remaining chat task lifecycle.
 */
object AssistsCore {

    const val TAG = "[Assists]"
    private var stateMachine: StateMachine? = null

    fun initCore(context: Context) {
        stateMachine = StateMachine().also { it.init(context.applicationContext) }
    }

    fun isStateMachineInitialized(): Boolean {
        return stateMachine?.isInitialized() == true
    }

    fun startTask(params: TaskParams) {
        stateMachine?.startTask(params)
    }

    fun cancelChatTask(taskId: String? = null) {
        stateMachine?.cancelChatTask(taskId)
    }

    fun resetOpenClawDeviceIdentity(): OpenClawIdentityResetResult =
        stateMachine?.resetOpenClawDeviceIdentity()
            ?: OpenClawIdentityResetResult(
                false,
                OpenClawIdentityResetStatus.CORE_UNAVAILABLE,
            )

    fun disableOpenClaw(): OpenClawConfigurationMutationResult =
        stateMachine?.disableOpenClaw()
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
    ): OpenClawConfigurationMutationResult = stateMachine?.saveOpenClawConfiguration(
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
