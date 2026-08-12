package cn.com.omnimind.assists

import android.content.Context
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.api.interfaces.TaskChangeListener
import cn.com.omnimind.assists.task.ChatTask
import cn.com.omnimind.assists.openclaw.OpenClawDeviceIdentity
import cn.com.omnimind.assists.openclaw.OpenClawIdentityResetResult
import cn.com.omnimind.assists.openclaw.OpenClawIdentityResetStatus
import cn.com.omnimind.assists.openclaw.OpenClawTokenStore
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationStore
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationMutationResult
import cn.com.omnimind.assists.openclaw.OpenClawConfigurationStatus
import cn.com.omnimind.assists.openclaw.OpenClawCredentialMutation
import cn.com.omnimind.baselib.util.OmniLog

class TaskManager(
    val context: Context,
    val taskChangeListener: TaskChangeListener
) {

    private val TAG = "[Assists] TaskManager"
    private val chatTasks: LinkedHashMap<String, ChatTask> = linkedMapOf()
    private var openClawIdentityResetInProgress = false

    @Synchronized
    fun createAndStartTask(params: TaskParams) {
        if (
            params is TaskParams.ChatTaskParams &&
            params.provider?.trim()?.equals("openclaw", ignoreCase = true) == true &&
            (
                openClawIdentityResetInProgress ||
                    params.openClawConfig == null ||
                    !OpenClawConfigurationStore.isAuthorized(params.openClawConfig)
                )
        ) {
            OmniLog.w(TAG, "OpenClaw task rejected by native authorization gate")
            return
        }
        when (params) {
            is TaskParams.ChatTaskParams -> createChatTaskAndStart(params)
        }
    }

    private fun createChatTaskAndStart(params: TaskParams.ChatTaskParams) {
        cleanupFinishedChatTasks()
        if (chatTasks[params.taskId]?.isRunning == true) {
            OmniLog.w(
                TAG, "ChatTask is not worked! taskId=${params.taskId} already running"
            )
            return
        }
        val chatTask = ChatTask(taskChangeListener,this)
        chatTasks[params.taskId] = chatTask
        chatTask.start(
            params.taskId,
            params.content,
            params.onMessagePush,
            params.provider,
            params.openClawConfig,
            params.modelOverride,
            params.reasoningEffort,
            params.promptCacheKey
        )
    }

    @Synchronized
    fun cancelChatTask(taskId: String? = null) {
        cleanupFinishedChatTasks()
        val targetChatTask = if (taskId.isNullOrBlank()) {
            chatTasks.values.lastOrNull { it.isRunning }
        } else {
            chatTasks[taskId]
        }
        if (targetChatTask?.isRunning == true) {
            targetChatTask.finishTask()
        }
    }

    @Synchronized
    fun unregisterChatTask(taskId: String) {
        chatTasks.remove(taskId)
    }

    @Synchronized
    fun resetOpenClawDeviceIdentity(): OpenClawIdentityResetResult {
        if (openClawIdentityResetInProgress) {
            return OpenClawIdentityResetResult(false, OpenClawIdentityResetStatus.BUSY)
        }
        openClawIdentityResetInProgress = true
        return try {
            val disabled = OpenClawConfigurationStore.disableAndAdvance()
            if (!disabled.success) {
                return OpenClawIdentityResetResult(
                    false,
                    OpenClawIdentityResetStatus.CONFIGURATION_DISABLE_FAILED,
                )
            }
            val stopped = stopAllOpenClawSessions()
            val openClawEntries = chatTasks.entries.filter { it.value.isOpenClawTask() }
            if (!stopped || openClawEntries.any { it.value.isRunning }) {
                OpenClawIdentityResetResult(
                    false,
                    OpenClawIdentityResetStatus.SESSION_STOP_FAILED,
                )
            } else {
                openClawEntries.forEach { chatTasks.remove(it.key) }
                val identityDeleted = OpenClawDeviceIdentity.resetVerified()
                val deviceAuthDeleted = OpenClawTokenStore.resetDevicePairingVerified()
                when {
                    !identityDeleted -> OpenClawIdentityResetResult(
                        false,
                        OpenClawIdentityResetStatus.IDENTITY_DELETE_FAILED,
                    )
                    !deviceAuthDeleted -> OpenClawIdentityResetResult(
                        false,
                        OpenClawIdentityResetStatus.DEVICE_AUTH_DELETE_FAILED,
                    )
                    else -> OpenClawIdentityResetResult(
                        true,
                        OpenClawIdentityResetStatus.SUCCESS,
                    )
                }
            }
        } finally {
            openClawIdentityResetInProgress = false
        }
    }

    /** Disables first, advances generation, and then severs every active OpenClaw session. */
    @Synchronized
    fun disableOpenClaw(): OpenClawConfigurationMutationResult {
        val disabled = OpenClawConfigurationStore.disableAndAdvance()
        if (!disabled.success) return disabled
        return if (stopAllOpenClawSessions()) {
            disabled
        } else {
            OpenClawConfigurationMutationResult(
                success = false,
                status = OpenClawConfigurationStatus.ROLLBACK_FAILED,
                snapshot = disabled.snapshot,
            )
        }
    }

    /**
     * Reconfiguration is serialized against task creation. Old sessions are stopped before the
     * generation CAS, so a changed endpoint or credential cannot coexist with a stale socket.
     */
    @Synchronized
    fun saveOpenClawConfiguration(
        requestId: String,
        expectedGeneration: Long,
        confirmedOrigin: String,
        rawBaseUrl: String,
        userId: String,
        credentialMutation: OpenClawCredentialMutation,
        replacementToken: String?,
        enable: Boolean,
    ): OpenClawConfigurationMutationResult {
        if (!stopAllOpenClawSessions()) {
            return OpenClawConfigurationMutationResult(
                success = false,
                status = OpenClawConfigurationStatus.ROLLBACK_FAILED,
                snapshot = OpenClawConfigurationStore.snapshot(),
            )
        }
        return OpenClawConfigurationStore.saveConfirmed(
            requestId = requestId,
            expectedGeneration = expectedGeneration,
            confirmedOrigin = confirmedOrigin,
            rawBaseUrl = rawBaseUrl,
            userId = userId,
            credentialMutation = credentialMutation,
            replacementToken = replacementToken,
            enable = enable,
        )
    }

    private fun stopAllOpenClawSessions(): Boolean {
        cleanupFinishedChatTasks()
        val openClawEntries = chatTasks.entries.filter { it.value.isOpenClawTask() }
        val stopped = openClawEntries
            .map { it.value.stopOpenClawSessionForIdentityReset() }
            .all { it }
        if (stopped) {
            openClawEntries.forEach { chatTasks.remove(it.key) }
        }
        return stopped && openClawEntries.none { it.value.isRunning }
    }

    private fun cleanupFinishedChatTasks() {
        val iterator = chatTasks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.value.isRunning) {
                iterator.remove()
            }
        }
    }
}
