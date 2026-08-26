package com.rk.libcommons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAbiTest {
    @Test
    fun resolvesArm64Device() {
        assertEquals(RuntimeAbi.ARM64, RuntimeAbi.resolveAbi(listOf("arm64-v8a", "armeabi-v7a")))
    }

    @Test
    fun resolvesX86_64Device() {
        assertEquals(RuntimeAbi.X86_64, RuntimeAbi.resolveAbi(listOf("x86_64", "x86")))
    }

    @Test
    fun fallsBackToArm64ForUnknownAbi() {
        assertEquals(RuntimeAbi.ARM64, RuntimeAbi.resolveAbi(listOf("mips")))
        assertEquals(RuntimeAbi.ARM64, RuntimeAbi.resolveAbi(emptyList()))
    }

    @Test
    fun normalizesUnknownToArm64() {
        assertEquals(RuntimeAbi.ARM64, RuntimeAbi.normalize("x86"))
        assertEquals(RuntimeAbi.ARM64, RuntimeAbi.normalize(null))
        assertEquals(RuntimeAbi.X86_64, RuntimeAbi.normalize("x86_64"))
    }

    @Test
    fun supportsArm64AndX86_64() {
        assertTrue(RuntimeAbi.isSupported("arm64-v8a"))
        assertTrue(RuntimeAbi.isSupported("x86_64"))
        assertFalse(RuntimeAbi.isSupported("armeabi-v7a"))
    }

    @Test
    fun alpineAssetNamesFollowAbi() {
        assertEquals(listOf("alpine.tar", "alpine.tar.gz"), RuntimeAbi.alpineAssetFileNames("arm64-v8a"))
        assertEquals(listOf("alpine-x86_64.tar", "alpine-x86_64.tar.gz"), RuntimeAbi.alpineAssetFileNames("x86_64"))
    }

    @Test
    fun currentAbiToleratesMissingBuildFields() {
        // JVM 单测里 android.jar 是 stub，Build.SUPPORTED_ABIS 为 null；
        // currentAbi() 不得 NPE，应回落历史默认 arm64-v8a（EmbeddedRuntimeInstaller
        // 的默认参数在单测中经此路径，曾因 NPE 打红 5 个 installer 测试）
        assertEquals(RuntimeAbi.ARM64, RuntimeAbi.currentAbi())
    }

    @Test
    fun anySupportedRequiresExactMatch() {
        assertTrue(RuntimeAbi.anySupported(listOf("armeabi-v7a", "x86_64")))
        assertTrue(RuntimeAbi.anySupported(listOf("arm64-v8a")))
        assertFalse(RuntimeAbi.anySupported(listOf("armeabi-v7a", "x86")))
        assertFalse(RuntimeAbi.anySupported(emptyList()))
    }

    @Test
    fun currentDeviceSupportedToleratesMissingBuildFields() {
        // JVM stub 下 Build.SUPPORTED_ABIS 为 null：不得 NPE，视为不支持（放行判定的安全侧）
        assertFalse(RuntimeAbi.currentDeviceSupported())
    }
}
