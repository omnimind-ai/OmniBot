package cn.com.omnimind.baselib.privileged

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal data class PrivilegedProcessResult(
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val errorCode: String? = null,
) {
    val success: Boolean get() = exitCode == 0 && !timedOut && errorCode == null
}

/** 进程归执行后端所有；取消和关闭权限都能撤销正在执行的命令。 */
internal class PrivilegedProcessRunner(
    private val root: Boolean = false,
    private val leaseDirectory: File? = null,
    private val ownerPid: Int = 0,
    private val admission: () -> Boolean = { true },
    private val accessDenied: () -> Unit = {},
    private val startProcess: (List<String>) -> Process = { ProcessBuilder(it).start() },
) {
    private val lock = Any()
    private val active = ConcurrentHashMap<String, ManagedProcess>()

    internal class ManagedProcess(
        val process: Process,
        val grantMarker: String?,
        private val lease: File?,
        private val released: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            // Root 子进程组的监督进程会观察租约撤销，不依赖 su 客户端转发信号。
            lease?.delete()
            try {
                if (lease != null && process.isAlive) process.waitFor(800, TimeUnit.MILLISECONDS)
                if (process.isAlive) process.destroy()
                if (process.isAlive && !process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
            } finally {
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                released()
            }
        }
    }

    fun launch(requestId: String, command: List<String>, requestAdmission: () -> Boolean = { true }): ManagedProcess = synchronized(lock) {
        check(admission() && requestAdmission()) { "privileged_access_changed" }
        check(!active.containsKey(requestId)) { "request_already_running" }
        val marker = if (root) "__OMNI_ROOT_${UUID.randomUUID()}__" else null
        val lease = if (root) {
            val directory = requireNotNull(leaseDirectory)
            check(directory.isDirectory || directory.mkdirs()) { "root_storage_unavailable" }
            File.createTempFile("process-", ".lease", directory)
        } else null
        try {
            val argv = if (root) listOf("su", "-c", rootSupervisor(command, marker!!, lease!!)) else command
            val process = startProcess(argv)
            lateinit var managed: ManagedProcess
            managed = ManagedProcess(process, marker, lease) { active.remove(requestId, managed) }
            active[requestId] = managed
            managed
        } catch (error: Exception) {
            lease?.delete()
            throw error
        }
    }

    fun execute(requestId: String, command: List<String>, timeoutSeconds: Long, requestAdmission: () -> Boolean = { true }): PrivilegedProcessResult {
        val managed = try {
            launch(requestId, command, requestAdmission)
        } catch (error: IllegalStateException) {
            if (error.message != "privileged_access_changed") throw error
            return PrivilegedProcessResult(errorCode = "privileged_access_changed")
        } catch (error: java.io.IOException) {
            return PrivilegedProcessResult(errorCode = if (root) "root_unavailable" else "process_unavailable")
        }
        managed.use {
            val process = managed.process
            val out = BoundedBytes(OUTPUT_BYTES)
            val err = BoundedBytes(OUTPUT_BYTES)
            val stdoutReader = drain(process.inputStream, out)
            val stderrReader = drain(process.errorStream, err)
            process.outputStream.close()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            val exitCode = if (finished) process.exitValue() else null
            if (!finished) managed.close()
            stdoutReader.join(1000)
            stderrReader.join(1000)
            val stderr = err.text()
            val marker = managed.grantMarker
            val rootConfirmed = marker == null || stderr.contains("$marker\n")
            if (finished && !rootConfirmed) accessDenied()
            return PrivilegedProcessResult(
                exitCode = exitCode,
                stdout = out.text(),
                stderr = if (marker == null) stderr else stderr.replace("$marker\n", ""),
                timedOut = !finished,
                truncated = out.truncated || err.truncated,
                errorCode = when {
                    finished && !rootConfirmed -> "root_denied"
                    stdoutReader.isAlive || stderrReader.isAlive -> "output_incomplete"
                    else -> null
                },
            )
        }
    }

    fun cancel(requestId: String) { active[requestId]?.close() }

    fun cancelAll() {
        val processes = synchronized(lock) { active.values.toList() }
        processes.forEach { it.close() }
    }

    private fun drain(input: InputStream, buffer: BoundedBytes): Thread = thread(isDaemon = true) {
        try {
            input.use {
                val bytes = ByteArray(4096)
                while (true) {
                    val count = it.read(bytes)
                    if (count < 0) break
                    buffer.append(bytes, count)
                }
            }
        } catch (_: java.io.IOException) {
            // 关闭权限或取消时，所有者会关闭管道。
        }
    }

    private fun rootSupervisor(command: List<String>, marker: String, lease: File): String {
        val worker = "exec " + command.joinToString(" ", transform = ::shellQuote)
        return """
            [ "${'$'}(id -u)" = 0 ] || exit 77
            printf '%s\n' ${shellQuote(marker)} >&2
            /system/bin/setsid /system/bin/sh -c ${shellQuote(worker)} <&0 &
            omni_child=${'$'}!
            (
                while [ -f ${shellQuote(lease.absolutePath)} ] && kill -0 $ownerPid 2>/dev/null; do
                    sleep 0.2
                done
                kill -TERM -- -${'$'}omni_child 2>/dev/null
                sleep 0.2
                kill -KILL -- -${'$'}omni_child 2>/dev/null
            ) </dev/null >/dev/null 2>&1 &
            omni_watch=${'$'}!
            omni_cleanup() {
                if kill -0 -- -${'$'}omni_child 2>/dev/null; then
                    kill -TERM -- -${'$'}omni_child 2>/dev/null
                    sleep 0.2
                    kill -KILL -- -${'$'}omni_child 2>/dev/null
                fi
                kill ${'$'}omni_watch 2>/dev/null
                wait ${'$'}omni_watch 2>/dev/null
            }
            trap 'omni_cleanup; exit 143' HUP INT TERM
            wait ${'$'}omni_child
            omni_exit=${'$'}?
            omni_cleanup
            exit ${'$'}omni_exit
        """.trimIndent()
    }

    internal class BoundedBytes(private val limit: Int) {
        private val bytes = ByteArrayOutputStream()
        var truncated: Boolean = false
            private set

        @Synchronized fun append(value: ByteArray, count: Int) {
            val remaining = (limit - bytes.size()).coerceAtLeast(0)
            bytes.write(value, 0, minOf(count, remaining))
            if (count > remaining) truncated = true
        }

        @Synchronized fun text(): String = bytes.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val OUTPUT_BYTES = 64 * 1024
        fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
    }
}
