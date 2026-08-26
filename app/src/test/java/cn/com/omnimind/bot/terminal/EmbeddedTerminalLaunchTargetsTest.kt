package cn.com.omnimind.bot.terminal

import com.rk.libcommons.ContainerBackends
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedTerminalLaunchTargetsTest {

    @Test
    fun uiSessionUsesUiBackendScript() {
        // pending 命令只服务 UI 终端会话：agent 开关与此无关，签名里不该出现（开发者审查死代码清理）
        assertEquals(
            "init-host-chroot",
            EmbeddedTerminalLaunchTargets.pendingInitHostFileName(
                openSetup = false,
                uiBackend = ContainerBackends.CHROOT
            )
        )
    }

    @Test
    fun setupSessionAlwaysUsesOfficialProot() {
        assertEquals(
            "init-host",
            EmbeddedTerminalLaunchTargets.pendingInitHostFileName(
                openSetup = true,
                uiBackend = ContainerBackends.CHROOT
            )
        )
    }
}
