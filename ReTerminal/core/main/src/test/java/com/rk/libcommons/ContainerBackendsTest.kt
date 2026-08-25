package com.rk.libcommons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerBackendsTest {

    @Test
    fun `proot is the default and stays proot`() {
        assertEquals(ContainerBackends.PROOT, ContainerBackends.normalize(ContainerBackends.PROOT))
        assertFalse(ContainerBackends.isChroot(ContainerBackends.PROOT))
        assertEquals("init-host.sh", ContainerBackends.initHostAsset(ContainerBackends.PROOT))
        assertEquals("init-host", ContainerBackends.initHostFileName(ContainerBackends.PROOT))
    }

    @Test
    fun `chroot resolves to chroot script names`() {
        assertTrue(ContainerBackends.isChroot(ContainerBackends.CHROOT))
        assertEquals("init-host-chroot.sh", ContainerBackends.initHostAsset(ContainerBackends.CHROOT))
        assertEquals("init-host-chroot", ContainerBackends.initHostFileName(ContainerBackends.CHROOT))
    }

    @Test
    fun `unknown values fall back to proot`() {
        assertEquals(ContainerBackends.PROOT, ContainerBackends.normalize(42))
        assertFalse(ContainerBackends.isChroot(42))
        assertEquals("init-host.sh", ContainerBackends.initHostAsset(42))
        assertEquals("init-host", ContainerBackends.initHostFileName(42))
    }

    @Test
    fun sessionWithoutOverrideUsesUiBackend() {
        // 终端 UI 会话不传 override：跟随终端性能开关（历史行为）
        assertEquals(
            ContainerBackends.CHROOT,
            ContainerBackends.forSession(null, ContainerBackends.CHROOT)
        )
        assertEquals(
            ContainerBackends.PROOT,
            ContainerBackends.forSession(null, ContainerBackends.PROOT)
        )
    }

    @Test
    fun sessionWithAgentOverrideIgnoresUiBackend() {
        // 开发者审查 P1#1：Agent 无头会话必须只看 agent 开关——
        // UI 性能开关开 chroot 不得静默提权 agent 会话，反之亦然
        assertEquals(
            ContainerBackends.PROOT,
            ContainerBackends.forSession(ContainerBackends.PROOT, ContainerBackends.CHROOT)
        )
        assertEquals(
            ContainerBackends.CHROOT,
            ContainerBackends.forSession(ContainerBackends.CHROOT, ContainerBackends.PROOT)
        )
    }

    @Test
    fun unknownBackendFallsBackToProot() {
        assertEquals(ContainerBackends.PROOT, ContainerBackends.forSession(99, 99))
    }
}
