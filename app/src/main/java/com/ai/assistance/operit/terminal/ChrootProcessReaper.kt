package com.ai.assistance.operit.terminal

import java.io.File

/**
 * chroot 进程组回收的纯逻辑：从 pid 文件读 pgid，并在文件刚创建、内容尚未写入时重试。
 * 为什么独立成对象：kill 路径需要单测空文件/延迟写入，不能绑 Android ProcessBuilder。
 */
object ChrootProcessReaper {
    fun sanitizeExecutorKey(key: String): String =
        key.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    fun readPgid(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it > 0 }

    fun readPgidFromFile(
        file: File,
        attempts: Int = 5,
        delayMs: Long = 50L,
        sleeper: (Long) -> Unit = { Thread.sleep(it) }
    ): Int? {
        repeat(attempts) { index ->
            val pgid = runCatching { readPgid(file.readText()) }.getOrNull()
            if (pgid != null) return pgid
            if (index < attempts - 1) sleeper(delayMs)
        }
        return null
    }
}
