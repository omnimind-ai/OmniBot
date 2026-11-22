package com.rk.terminal.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.rk.libcommons.localBinDir
import com.rk.libcommons.localLibDir
import com.rk.terminal.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages PRoot's seccomp syscall-filter compatibility across Android kernels.
 *
 * Termux PRoot enables `SECCOMP_MODE_FILTER` (`SECCOMP_RET_TRACE`) unless
 * `PROOT_NO_SECCOMP` is present in the environment; it does not read `SECCOMP`.
 * On certain OEM/Android 16 kernels (such as Samsung Galaxy A07 SM-A075F),
 * `SECCOMP_RET_TRACE` causes the initial tracee `execve()` to fail immediately
 * with `ENOSYS` ("Function not implemented") or `EPERM` ("Operation not permitted").
 */
object ProotSeccompCompatibility {
    private const val TAG = "ProotSeccompCompat"
    private const val PREFS_NAME = "embedded_terminal_proot_compat"
    private const val KEY_MODE = "proot_seccomp_mode"
    private const val PROBE_TIMEOUT_SECONDS = 5L
    private val probeMutex = Mutex()

    enum class Mode { AUTOMATIC, DEFAULT, COMPATIBILITY }

    fun getMode(context: Context): Mode {
        val stored = prefs(context).getString(KEY_MODE, null)
        // The old switch could be set by a failed probe or carried over from
        // a release where it had no effect. Re-evaluate those devices in Auto.
        return Mode.entries.firstOrNull { it.name == stored } ?: Mode.AUTOMATIC
    }

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun isSeccompCompatEnabled(context: Context, distribution: TerminalDistribution.Spec): Boolean =
        when (getMode(context)) {
            Mode.COMPATIBILITY -> true
            Mode.DEFAULT -> false
            Mode.AUTOMATIC -> readProbeMode(context, distribution) == Mode.COMPATIBILITY
        }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun probeKey(distribution: TerminalDistribution.Spec) = "validated_seccomp_${distribution.id}"

    private fun readProbeMode(context: Context, distribution: TerminalDistribution.Spec): Mode? {
        val result = prefs(context).getString(probeKey(distribution), null) ?: return null
        if (result.substringAfter(':') != Build.FINGERPRINT.orEmpty()) return null
        return Mode.entries.firstOrNull { it.name == result.substringBefore(':') }
    }

    @VisibleForTesting
    fun applyEnvironment(
        env: MutableMap<String, String>,
        seccompCompatEnabled: Boolean
    ) {
        val explicitNoSeccomp = env["PROOT_NO_SECCOMP"]?.trim()?.takeIf { it.isNotEmpty() }
        env.remove("SECCOMP")
        if (seccompCompatEnabled) {
            env["SECCOMP"] = "1"
            env["PROOT_NO_SECCOMP"] = "1"
        } else if (explicitNoSeccomp != null) {
            env["PROOT_NO_SECCOMP"] = explicitNoSeccomp
        } else {
            env.remove("PROOT_NO_SECCOMP")
        }
    }

    @VisibleForTesting
    fun applyEnvironmentList(
        env: MutableList<String>,
        seccompCompatEnabled: Boolean
    ) {
        var explicitNoSeccomp: String? = null
        val iterator = env.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val sep = entry.indexOf('=')
            val key = if (sep >= 0) entry.substring(0, sep) else entry
            if (key == "PROOT_NO_SECCOMP") {
                val rawVal = if (sep >= 0) entry.substring(sep + 1).trim() else ""
                if (rawVal.isNotEmpty()) {
                    explicitNoSeccomp = rawVal
                }
                iterator.remove()
            } else if (key == "SECCOMP") {
                iterator.remove()
            }
        }
        if (seccompCompatEnabled) {
            env.add("SECCOMP=1")
            env.add("PROOT_NO_SECCOMP=1")
        } else if (explicitNoSeccomp != null) {
            env.add("PROOT_NO_SECCOMP=$explicitNoSeccomp")
        }
    }

    @VisibleForTesting
    fun isProotSeccompExecveFailure(output: String): Boolean {
        if (output.isBlank()) return false
        val lower = output.lowercase()
        if ("proot_no_seccomp" in lower) {
            return true
        }
        if ("proot error:" !in lower || "execve(" !in lower) {
            return false
        }
        return "function not implemented" in lower ||
            "operation not permitted" in lower
    }

    suspend fun probeAndConfigureIfNeeded(
        context: Context,
        distribution: TerminalDistribution.Spec
    ) {
        if (getMode(context) != Mode.AUTOMATIC || readProbeMode(context, distribution) != null) return
        withContext(Dispatchers.IO) {
            probeMutex.withLock {
                if (getMode(context) != Mode.AUTOMATIC || readProbeMode(context, distribution) != null) {
                    return@withLock
                }
                val mode = probe(context, distribution) ?: return@withLock
                prefs(context).edit()
                    .putString(probeKey(distribution), "${mode.name}:${Build.FINGERPRINT.orEmpty()}")
                    .apply()
            }
        }
    }

    private fun probe(context: Context, distribution: TerminalDistribution.Spec): Mode? {
        val linker = File("/system/bin/linker64").takeIf { it.canExecute() }
            ?: File("/system/bin/linker").takeIf { it.canExecute() }
            ?: return null
        val parent = context.filesDir.parentFile ?: return null
        val rootfs = File(File(parent, "local"), distribution.rootfsDirectoryName)
        val prootBin = runCatching { File(localBinDir(), "proot") }.getOrNull() ?: return null
        if (!prootBin.isFile || !rootfs.isDirectory) {
            return null
        }

        val probeTmpDir = runCatching {
            App.getTempDir().resolve("proot-seccomp-probe").apply { mkdirs() }
        }.getOrElse {
            File(context.cacheDir, "proot-seccomp-probe").apply { mkdirs() }
        }

        val command = mutableListOf(
            linker.absolutePath,
            prootBin.absolutePath,
            "--kill-on-exit",
            "-w",
            "/"
        )
        for (hostMount in listOf("/apex", "/system", "/dev", "/proc")) {
            if (File(hostMount).exists()) {
                command.add("-b")
                command.add(hostMount)
            }
        }
        command.addAll(
            listOf(
                "-r",
                rootfs.absolutePath,
                "-0",
                "/bin/sh",
                "-c",
                """cd /root && /bin/pwd >/dev/null && dir=${'$'}(mktemp -d /tmp/omni-proot.XXXXXX) && rmdir "${'$'}dir""""
            )
        )

        return selectAutomaticMode { compatibility ->
            runProbeProcess(context, command, probeTmpDir, compatibility)
        }.also { mode ->
            if (mode == Mode.COMPATIBILITY) {
                Log.i(TAG, "PRoot compatibility probe passed for ${distribution.id} with seccomp acceleration disabled.")
            }
        }
    }

    internal fun selectAutomaticMode(runProbe: (compatibility: Boolean) -> Pair<Int, String>?): Mode? {
        val result = runProbe(false) ?: return null
        if (result.first == 0) return Mode.DEFAULT
        if (!isProotSeccompExecveFailure(result.second)) return null
        return if (runProbe(true)?.first == 0) Mode.COMPATIBILITY else null
    }

    private fun runProbeProcess(
        context: Context,
        command: List<String>,
        probeTmpDir: File,
        compatibility: Boolean
    ): Pair<Int, String>? {
        val process = runCatching {
            ProcessBuilder(command).apply {
                redirectErrorStream(true)
                val env = environment()
                env.remove("PROOT_NO_SECCOMP")
                env.remove("SECCOMP")
                if (compatibility) env["PROOT_NO_SECCOMP"] = "1"
                env["PATH"] = "/usr/bin:/bin"
                runCatching { localLibDir().absolutePath }.getOrNull()?.let { libPath ->
                    env["LD_LIBRARY_PATH"] = libPath
                }
                env["PROOT_TMP_DIR"] = probeTmpDir.absolutePath
                env["TMPDIR"] = probeTmpDir.absolutePath
                val nativeLibDir = context.applicationInfo?.nativeLibraryDir
                if (!nativeLibDir.isNullOrBlank()) {
                    if (File(nativeLibDir, "libproot-loader.so").exists()) {
                        env["PROOT_LOADER"] = "$nativeLibDir/libproot-loader.so"
                    }
                    if (File(nativeLibDir, "libproot-loader32.so").exists()) {
                        env["PROOT_LOADER32"] = "$nativeLibDir/libproot-loader32.so"
                        env["PROOT_LOADER_32"] = "$nativeLibDir/libproot-loader32.so"
                    }
                }
            }.start()
        }.getOrNull() ?: return null

        return try {
            runCatching { process.outputStream.close() }
            val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                null
            } else {
                val output = runCatching {
                    process.inputStream.bufferedReader().use { it.readText() }
                }.getOrDefault("")
                process.exitValue() to output
            }
        } catch (_: Exception) {
            runCatching { process.destroyForcibly() }
            null
        }
    }
}
