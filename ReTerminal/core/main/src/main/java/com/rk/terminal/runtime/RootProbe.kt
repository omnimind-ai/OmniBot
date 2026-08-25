package com.rk.terminal.runtime

import androidx.annotation.VisibleForTesting
import com.rk.libcommons.ContainerBackends
import com.rk.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Root（su）可用性探测：设置页切到 chroot 前、Agent 链路每次启动进程时调用。
 * 为什么要超时强制销毁：su 未授权时 KernelSU 可能弹窗等待用户操作，
 * 探测进程会一直挂起，必须兜底杀掉避免协程泄漏。
 */
object RootProbe {
    private const val TIMEOUT_MS = 5000L
    private const val CACHE_HIT_MS = 30_000L
    private const val CACHE_MISS_MS = 2_000L

    // 缓存值与过期时间同生同灭：打包成 Snapshot 用 AtomicReference 保证成对可见（评审 Data Clump）
    private data class CacheSnapshot(val value: Boolean, val untilMs: Long)

    private val cache = AtomicReference(CacheSnapshot(false, 0L))

    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    suspend fun isSuAvailable(): Boolean = withContext(Dispatchers.IO) {
        val now = nowMs()
        val snapshot = cache.get()
        if (now < snapshot.untilMs) return@withContext snapshot.value
        val result = runCatching { probeSu() }.getOrDefault(false)
        remember(result, now)
        result
    }

    fun invalidateCache() {
        cache.set(CacheSnapshot(false, 0L))
    }

    @VisibleForTesting
    internal fun seedCache(result: Boolean, now: Long) {
        remember(result, now)
    }

    @VisibleForTesting
    internal fun parseProbeOutput(output: String): Boolean {
        val uidOk = Regex("""^Uid:\s+0\s+0\s+0\s+0""", RegexOption.MULTILINE).containsMatchIn(output)
        // KernelSU 输出是 16 位零填充十六进制，不能要求首字符非 0
        val capValue = Regex("""^CapEff:\s+([0-9a-fA-F]+)""", RegexOption.MULTILINE)
            .find(output)
            ?.groupValues
            ?.get(1)
        val capOk = capValue?.any { it != '0' } == true
        return uidOk && capOk
    }

    private fun remember(result: Boolean, now: Long) {
        cache.set(CacheSnapshot(result, now + if (result) CACHE_HIT_MS else CACHE_MISS_MS))
    }

    /**
     * Agent 链路后端解析：开启 chroot 时每次使用都重检 root（带 30s/2s 缓存），
     * 授权被撤销则回退 proot 并改写持久化设置，避免设置页显示与实际运行不一致
     * （开发者审查 P2#6：「每次启动重新检查、失败则回退」含持久化设置）。
     */
    suspend fun resolveAgentBackend(): Int {
        val requested = Settings.agent_container_backend
        if (!ContainerBackends.isChroot(requested)) return ContainerBackends.PROOT
        if (isSuAvailable()) return ContainerBackends.CHROOT
        Settings.agent_container_backend = ContainerBackends.PROOT
        return ContainerBackends.PROOT
    }

    /** 同步上下文专用（调用方已在 IO 线程）：ensureShellScripts 等非 suspend 入口 */
    fun resolveAgentBackendBlocking(): Int = runBlocking { resolveAgentBackend() }

    private fun probeSu(): Boolean {
        val command = "id; grep -E '^Uid:|^CapEff:' /proc/self/status"
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return false
        }
        if (process.exitValue() != 0) return false
        val output = runCatching { process.inputStream.bufferedReader().readText() }.getOrNull()
            ?: return false
        return parseProbeOutput(output)
    }
}
