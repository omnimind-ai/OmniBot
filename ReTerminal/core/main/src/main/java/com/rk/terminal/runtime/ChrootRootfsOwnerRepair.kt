package com.rk.terminal.runtime

import androidx.annotation.VisibleForTesting
import com.rk.libcommons.child
import com.rk.libcommons.terminalHomeDir
import com.rk.libcommons.terminalRootfsDir
import com.rk.terminal.runtime.TerminalDistribution
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * chroot 模式下宿主侧对 rootfs 关键目录的属主修复。
 * 背景：宿主侧 App 进程要访问 rootfs 的多个位置——terminalHomeDir 作交互会话 cwd（termux.c
 * chdir），rootfs/tmp 要 chmod 1777（init-host-chroot.sh 宿主段）。chroot 容器内 root 用户
 * 写过这些目录后属主漂移成 root，App（uid=appUid）无权限 → chdir/chmod 失败。termux.c 对
 * chdir 失败仅 perror 不退出（非致命），chmod 失败仅告警，但都打噪音；这里在会话启动前把
 * 这些目录修回 app 属主。su 不可用/超时只告警不阻断，与非致命语义一致。
 */
object ChrootRootfsOwnerRepair {
    private const val SU_TIMEOUT_MS = 8000L
    private const val OWNER_UNKNOWN = -1L

    /** 宿主侧需要可访问的 rootfs 目录清单：交互会话 cwd + tmp（宿主段 chmod 1777） */
    @VisibleForTesting
    internal fun hostAccessPaths(): List<File> {
        val mode = TerminalDistribution.selected().workingMode
        return listOf(
            terminalHomeDir(mode),
            terminalRootfsDir(mode).child("tmp")
        )
    }

    /**
     * 纯逻辑：属主是否需要修复。
     * owner 未知（目录不存在 / stat 失败）或已等于 app uid 都不修；其余情况（漂移到 root 等）需要。
     */
    @VisibleForTesting
    internal fun needsRepair(owner: Long, appUid: Int): Boolean =
        owner != OWNER_UNKNOWN && owner != appUid.toLong() && appUid >= 0

    /** stat 行为注入点：单测传纯函数，运行时用 Android Os.stat */
    @VisibleForTesting
    internal var statOwner: (path: String) -> Long = { path ->
        runCatching { android.system.Os.stat(path).st_uid.toLong() }.getOrDefault(OWNER_UNKNOWN)
    }

    /** 判定 rootfs/root 当前属主（uid）。读目录 stat 不要求对目录有 search 权限，App 能拿到。 */
    @VisibleForTesting
    internal fun currentOwner(path: File): Long {
        if (!path.exists()) return OWNER_UNKNOWN
        return statOwner(path.path)
    }

    /** 会话启动前调用：属主非 app 的宿主访问目录经 su 修回 app 属主。任何失败都不抛异常。 */
    fun ensureAccessible() {
        val appUid = runCatching { android.os.Process.myUid() }.getOrDefault(-1)
        if (appUid < 0) return
        hostAccessPaths().forEach { path ->
            val owner = currentOwner(path)
            if (needsRepair(owner, appUid)) {
                repairOwner(path, owner, appUid)
            }
        }
    }

    @VisibleForTesting
    internal fun repairOwner(path: File, owner: Long, appUid: Int) {
        val command = "chown $appUid:$appUid ${shellQuote(path.path)}"
        val process = runCatching {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        }.getOrNull()
        if (process == null) {
            android.util.Log.w(TAG, "chroot rootfs owner repair: su 不可用，跳过 chown ${path.path}")
            return
        }
        val finished = runCatching {
            process.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) {
            process.destroyForcibly()
            android.util.Log.w(TAG, "chroot rootfs owner repair: su 超时，跳过 chown ${path.path}")
            return
        }
        if (process.exitValue() != 0) {
            android.util.Log.w(TAG, "chroot rootfs owner repair: chown 失败 exit=${process.exitValue()} ${path.path}")
        } else {
            android.util.Log.i(TAG, "chroot rootfs owner repair: ${path.name} 属主 $owner -> $appUid 修复完成")
        }
    }

    @VisibleForTesting
    internal fun shellQuote(path: String): String = "'${path.replace("'", "'\\''")}'"

    private const val TAG = "ChrootRootfsOwnerRepair"
}
