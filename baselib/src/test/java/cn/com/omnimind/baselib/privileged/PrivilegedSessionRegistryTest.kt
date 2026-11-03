package cn.com.omnimind.baselib.privileged

import org.junit.Assert.*
import org.junit.Test

class PrivilegedSessionRegistryTest {
    private val root = PrivilegedStatus(PrivilegedProvider.ROOT, PrivilegedBackend.ROOT, 1)
    private val shizuku = PrivilegedStatus(PrivilegedProvider.SHIZUKU, PrivilegedBackend.ADB, 2)

    @Test fun rootIsPreferredOnlyWhileExplicitlyEnabledAndGranted() {
        assertEquals(PrivilegedProvider.ROOT, PrivilegedStatus.selectBackend(
            RootAccessSnapshot(true, RootAccessStatus.GRANTED, 10), shizuku).provider)
        assertEquals(shizuku, PrivilegedStatus.selectBackend(RootAccessSnapshot(), shizuku))
        assertEquals(shizuku, PrivilegedStatus.selectBackend(RootAccessSnapshot(true, RootAccessStatus.DENIED), shizuku))
        assertEquals(PrivilegedProvider.NONE, PrivilegedStatus.selectBackend(RootAccessSnapshot(), PrivilegedStatus()).provider)
    }

    @Test fun neitherAnotherConversationNorAnotherAcpSessionCanReuseShell() {
        val registry = PrivilegedSessionRegistry()
        registry.register("shell", PrivilegedSessionRegistry.Binding("conversation_1", "acp_1", root))
        assertEquals(root, registry.selection("shell", "conversation_1", "acp_1"))
        assertNull(registry.selection("shell", "conversation_2", "acp_1"))
        assertNull(registry.selection("shell", "conversation_1", "acp_2"))
        assertNull(registry.selection("shell", "conversation_1", null))
    }

    @Test fun backendAndGenerationStayPinnedAfterPreferencesChange() {
        val registry = PrivilegedSessionRegistry()
        val binding = PrivilegedSessionRegistry.Binding("conversation", "acp", shizuku)
        registry.register("shell", binding)
        PrivilegedStatus.selectBackend(RootAccessSnapshot(true, RootAccessStatus.GRANTED), shizuku)
        assertEquals(shizuku, registry.selection("shell", "conversation", "acp"))
        assertFalse(registry.owns("shell", binding.copy(status = root)))
        assertFalse(registry.owns("shell", binding.copy(status = shizuku.copy(generation = 3))))
    }

    @Test fun revokingRootDoesNotDeleteShizukuSessionsAndCloseTargetsOneAcpSession() {
        val registry = PrivilegedSessionRegistry()
        registry.register("root-shell", PrivilegedSessionRegistry.Binding("conversation_1", "acp_1", root))
        registry.register("adb-shell", PrivilegedSessionRegistry.Binding("conversation_1", "acp_1", shizuku))
        registry.register("other-shell", PrivilegedSessionRegistry.Binding("conversation_2", "acp_2", shizuku))
        registry.removeProvider(PrivilegedProvider.ROOT)
        assertNull(registry.selection("root-shell", "conversation_1", "acp_1"))
        assertEquals(setOf("adb-shell"), registry.forSession("acp_1").keys)
        assertEquals(shizuku, registry.selection("other-shell", "conversation_2", "acp_2"))
    }
}
