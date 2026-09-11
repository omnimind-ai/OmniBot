package cn.com.omnimind.bot.agent.runtime

import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.TimeUnit

internal fun closeMcpProcess(process: Process?, reader: BufferedReader?, writer: BufferedWriter?) {
    // BufferedReader.close takes the same lock as readLine. End the producer
    // first so a quiet server cannot hold that lock throughout session close.
    if (process != null) {
        runCatching { process.destroy() }
        val exited = runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!exited) runCatching { process.destroyForcibly() }
    }
    runCatching { writer?.close() }
    runCatching { reader?.close() }
}
