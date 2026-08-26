package com.rk.libcommons

import android.os.Build

/**
 * 终端运行时的 ABI 解析：把设备 ABI 归一化到内嵌 rootfs 支持的集合。
 * 为什么单独成对象：EmbeddedRuntimeInstaller（下载/校验）与
 * EmbeddedTerminalRuntime（设备放行）两条链路必须用同一套 ABI 映射，
 * 否则「放行的 ABI」和「下载的 rootfs ABI」会漂移（真机 arm64 下载 arm64、
 * 模拟器 x86_64 下载 x86_64，缺一就端到端跑不通）。
 */
object RuntimeAbi {
    const val ARM64 = "arm64-v8a"
    const val X86_64 = "x86_64"

    /** 支持的 ABI 集合；未知值一律回落 arm64-v8a（历史默认，真机主战场） */
    val supportedAbis: Set<String> = setOf(ARM64, X86_64)

    /** 从 Build.SUPPORTED_ABIS 提取当前设备的运行时 ABI（优先已支持项） */
    fun currentAbi(): String = resolveAbi(deviceAbis())

    /** 设备 ABI 列表；JVM 单测里 android.jar 是 stub（字段为 null），兜底空表走 resolveAbi 的历史默认 */
    private fun deviceAbis(): List<String> =
        runCatching { Build.SUPPORTED_ABIS?.toList() }.getOrNull().orEmpty()

    /** 纯函数：从候选 ABI 列表解析当前 ABI；供测试注入，避免依赖 Build */
    @JvmStatic
    fun resolveAbi(supportedAbisOfDevice: List<String>): String {
        val firstSupported = supportedAbisOfDevice.firstOrNull { it in supportedAbis }
        return normalize(firstSupported ?: ARM64)
    }

    /** 归一化：unknown → arm64-v8a（与 ContainerBackends.normalize 回落语义一致） */
    fun normalize(abi: String?): String =
        if (abi == X86_64) X86_64 else ARM64

    /** 是否为受支持的 ABI（精确匹配，不做未知回落——回落只用于取值，不用于放行判断） */
    fun isSupported(abi: String?): Boolean = abi != null && abi in supportedAbis

    /** 候选列表里是否至少有其一受支持（EmbeddedTerminalRuntime 设备放行复用，防两条链路漂移） */
    fun anySupported(abis: List<String>): Boolean = abis.any(::isSupported)

    /** 当前设备是否受支持；JVM stub 下 ABI 列表为空 → false（放行判定的安全侧） */
    fun currentDeviceSupported(): Boolean = anySupported(deviceAbis())

    /** Alpine 内置 asset 文件名（构建时按 ABI 打包进 APK，见 build.gradle.kts） */
    fun alpineAssetFileNames(abi: String): List<String> = when (normalize(abi)) {
        X86_64 -> listOf("alpine-x86_64.tar", "alpine-x86_64.tar.gz")
        else -> listOf("alpine.tar", "alpine.tar.gz")
    }
}
