package com.rk.libcommons

/**
 * 容器后端解析：把设置值映射为启动脚本 asset 名与落盘文件名。
 * 为什么单独成对象：MkSession（终端 UI）与 TerminalManager（Agent 工具）
 * 两条链路必须用同一套映射，避免各处硬编码字符串漂移。
 */
object ContainerBackends {
    const val PROOT = 0
    const val CHROOT = 1

    const val INIT_HOST_ASSET = "init-host.sh"
    const val INIT_HOST_CHROOT_ASSET = "init-host-chroot.sh"
    const val INIT_HOST_CHROOT_ROOT_ASSET = "init-host-chroot-root.sh"

    /** 落盘文件名（写入 files 目录 local/bin 下，去掉 .sh 后缀与现有 init-host 一致） */
    const val INIT_HOST_CHROOT_ROOT_FILE = "init-host-chroot-root"

    /** 未知值一律回落 proot，保证无 root 设备永远有可用后端 */
    fun normalize(backend: Int): Int = if (backend == CHROOT) CHROOT else PROOT

    fun isChroot(backend: Int): Boolean = normalize(backend) == CHROOT

    fun initHostAsset(backend: Int): String =
        if (isChroot(backend)) INIT_HOST_CHROOT_ASSET else INIT_HOST_ASSET

    fun initHostFileName(backend: Int): String =
        if (isChroot(backend)) "init-host-chroot" else "init-host"

    /**
     * 会话后端解析：显式 override 优先（Agent 无头会话必须传 agent 开关值），
     * 否则跟随终端 UI 性能开关（交互会话）。UI 开 chroot 不得静默提权 Agent 会话
     * （开发者审查 P1#1）。
     */
    fun forSession(backendOverride: Int?, uiBackend: Int): Int =
        normalize(backendOverride ?: uiBackend)
}
