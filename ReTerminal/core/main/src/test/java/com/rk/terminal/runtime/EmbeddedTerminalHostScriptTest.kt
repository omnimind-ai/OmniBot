package com.rk.terminal.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedTerminalHostScriptTest {
    @Test
    fun finalProotProcessKeepsCallerStdio() {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val script = listOf(
            workingDirectory.resolve(
                "ReTerminal/core/main/src/main/assets/init-host.sh"
            ),
            workingDirectory.resolve("src/main/assets/init-host.sh")
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Embedded terminal init-host.sh asset is missing.")

        assertTrue(
            script.contains(
                "exec \"\$LINKER\" \"\$PREFIX/local/bin/proot\" \$ARGS"
            )
        )
        assertFalse(script.contains("run_child \$LINKER \"\$PREFIX/local/bin/proot\""))
    }

    @Test
    fun chrootLauncherForwardsAcpExtraEnv() {
        val script = readAsset("init-host-chroot.sh")
        // 断言代码而非注释：上一版只断言 CODEX_HOME 字符串，删掉透传逻辑后注释仍让测试绿（空转断言）
        assertTrue(
            "launcher must iterate the full environment and export every non-unsafe key",
            script.contains("env | while IFS='=' read -r key val")
        )
        assertTrue(
            "unsafe keys (PATH/LD_*/...) must be filtered before writing into the su launcher",
            script.contains("is_unsafe_env_key \"\$key\" && continue")
        )
    }

    @Test
    fun chrootHostScriptSweepsStaleLaunchersByAge() {
        val script = readAsset("init-host-chroot.sh")
        // 崩溃残留的 launcher 不会被 trap 清掉：只按年龄（mtime > 2 天）回收，
        // 并发会话刚创建的文件 mtime 是新的，不受影响（开发者审查 P2#5 附注）
        assertTrue(
            "stale .chroot-launcher.* files must be reaped by age, not by glob delete",
            script.contains("-name '.chroot-launcher.*' -mtime +1 -delete")
        )
        assertFalse(
            "glob delete would remove a concurrent session's fresh launcher",
            script.contains("rm -f \"\$PREFIX\"/local/bin/.chroot-launcher.*")
        )
    }

    @Test
    fun chrootHostScriptVerifiesRootCapabilities() {
        val script = readAsset("init-host-chroot.sh")
        // 退出码不可信：KernelSU App Profile 可保留 uid 0 但剥光 capabilities（开发者审查 P2#6）
        assertTrue(
            "su probe must verify uid==0 and non-zero CapEff, not just exit code",
            script.contains("su_root_capable") && script.contains("id -u") && script.contains("CapEff")
        )
        assertTrue(
            "the proot fallback condition must use the capability probe",
            script.contains("! su_root_capable")
        )
    }

    @Test
    fun chrootRootWritesPgidBeforeExecInHeadlessBranch() {
        val script = readAsset("init-host-chroot-root.sh")
        // 进程组回收契约（开发者审查 P1#2）：pid 文件按 OMNIBOT_EXECUTOR_KEY 命名（会话间隔离），
        // headless 分支必须先写 pgid 再 exec chroot，App 读到的才是最终 chroot 进程组
        assertTrue(
            "pid file must be namespaced by OMNIBOT_EXECUTOR_KEY",
            script.contains("PID_FILE=\"\$RUN_DIR/chroot-\${EXECUTOR_KEY}.pid\"")
        )
        val pgidWrite = script.indexOf("echo \$\$ > \"\$OMNIBOT_CHROOT_ROOT_PID_FILE\"")
        val headlessExec = script.indexOf("exec chroot \"\$ROOTFS_DIR\"")
        assertTrue("headless branch must write pgid via setsid sh -c", pgidWrite >= 0)
        assertTrue("headless branch must exec chroot after writing pgid", headlessExec > pgidWrite)
        assertTrue(
            "interactive branch must not leave a stale pid file behind",
            script.contains("rm -f \"\$PID_FILE\"")
        )
    }

    @Test
    fun chrootRootBindsDataFromInitNamespace() {
        val script = readAsset("init-host-chroot-root.sh")
        // 真机教训：Android 对 App 挂载命名空间做应用数据隔离，真 root 也只见 3 条、
        // 跨命名空间 bind 被拒；必须先 nsenter 进 init 命名空间再 unshare，
        // 从完整视图派生私有命名空间后正常 bind /data 才能看到全部（575 条）
        assertTrue(
            "must jump into the init mount namespace before unshare to escape app data isolation",
            script.contains("nsenter -t 1 -m -- unshare -m")
        )
        assertTrue(
            "with the init-derived namespace, a plain /data bind exposes the full view",
            script.contains("bind_one /data /data")
        )
    }

    @Test
    fun chrootRootBindsCacheAndMetadataPartitions() {
        val script = readAsset("init-host-chroot-root.sh")
        // 全视图清单补齐：/cache、/metadata 不在默认清单里，容器内看不到（真机实测缺失）；
        // bind_one 对不存在的源会静默跳过，无此分区的设备安全
        assertTrue("/cache must be bound for a complete host view", script.contains("bind_one /cache /cache"))
        assertTrue("/metadata must be bound for a complete host view", script.contains("bind_one /metadata /metadata"))
    }

    @Test
    fun chrootRootKeepsControllingTtyForInteractiveSession() {
        val script = readAsset("init-host-chroot-root.sh")
        assertTrue(
            "interactive (non-headless) chroot must exec without setsid to keep the controlling tty",
            script.contains("setsid") && script.contains("OMNIBOT_HEADLESS")
        )
        assertFalse(
            "setsid must not unconditionally detach the interactive session from its controlling tty",
            script.contains("setsid /system/bin/sh -c")
        )
    }

    private fun readAsset(fileName: String): String {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            workingDirectory.resolve("ReTerminal/core/main/src/main/assets/$fileName"),
            workingDirectory.resolve("src/main/assets/$fileName")
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Embedded terminal $fileName asset is missing.")
    }
}
