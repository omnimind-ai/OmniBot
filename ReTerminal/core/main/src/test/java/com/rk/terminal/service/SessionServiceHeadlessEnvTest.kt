package com.rk.terminal.service

import com.rk.libcommons.ContainerBackends
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionServiceHeadlessEnvTest {
    @Test
    fun headlessEnvCarriesHeadlessFlagAndExecutorKey() {
        // 开发者审查 P1#2：无头会话必须带 OMNIBOT_HEADLESS=1（root 段才写 pid 文件），
        // 且 OMNIBOT_EXECUTOR_KEY=sessionId（停止会话时 killpg 按此定位进程组，不再共享 chroot-default.pid）
        val env = SessionService.headlessBaseEnv("session_abc123")
        assertEquals("1", env["OMNIBOT_HEADLESS"])
        assertEquals("session_abc123", env["OMNIBOT_EXECUTOR_KEY"])
    }

    @Test
    fun headlessEnvKeepsQuietPagerDefaults() {
        val env = SessionService.headlessBaseEnv("session_x")
        assertEquals("/root", env["HOME"])
        assertEquals("cat", env["PAGER"])
        assertEquals("cat", env["GIT_PAGER"])
    }

    @Test
    fun backendChangedDetectsBackendSwitch() {
        // 后端切换后必须终止旧会话重建，否则 Agent 会复用切换前的 proot 会话（真机复现）
        assertTrue(SessionService.backendChanged(ContainerBackends.PROOT, ContainerBackends.CHROOT))
        assertTrue(SessionService.backendChanged(ContainerBackends.CHROOT, ContainerBackends.PROOT))
        assertFalse(SessionService.backendChanged(ContainerBackends.CHROOT, ContainerBackends.CHROOT))
        // 无记录（历史会话）按不一致处理：保守重建
        assertTrue(SessionService.backendChanged(null, ContainerBackends.CHROOT))
    }
}
