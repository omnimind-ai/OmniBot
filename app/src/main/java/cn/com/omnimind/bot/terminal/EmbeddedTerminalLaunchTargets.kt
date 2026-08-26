package cn.com.omnimind.bot.terminal

import com.rk.libcommons.ContainerBackends

object EmbeddedTerminalLaunchTargets {
    // pending 命令只服务 UI 终端会话，与 agent 开关无关——签名不收 agentBackend，
    // 避免「传了却被忽略」的误导性参数（评审 Speculative Generality）
    fun pendingInitHostFileName(
        openSetup: Boolean,
        uiBackend: Int
    ): String {
        if (openSetup) {
            return ContainerBackends.initHostFileName(ContainerBackends.PROOT)
        }
        return ContainerBackends.initHostFileName(uiBackend)
    }
}
