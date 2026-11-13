package com.rk.terminal.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.rk.libcommons.localBinDir
import com.rk.libcommons.localLibDir
import com.rk.settings.Settings
import com.rk.terminal.App
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
    private const val KEY_AUTO_NO_SECCOMP = "auto_proot_no_seccomp"
    private const val KEY_PROBED_FINGERPRINT = "probed_build_fingerprint"
    private const val PROBE_TIMEOUT_SECONDS = 5L

    @Volatile
    private var autoNoSeccompCached: Boolean? = null

    fun isSeccompCompatEnabled(context: Context): Boolean {
        val manualSetting = runCatching { Settings.seccomp }.getOrDefault(false)
        if (manualSetting) {
            return true
        }
        return isAutoFallbackEnabled(context)
    }

    fun isAutoFallbackEnabled(context: Context): Boolean {
        autoNoSeccompCached?.let { return it }
        val stored = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_NO_SECCOMP, false)
        }.getOrDefault(false)
        autoNoSeccompCached = stored
        return stored
    }

    fun enableAutoFallback(context: Context) {
        autoNoSeccompCached = true
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_NO_SECCOMP, true)
                .putString(KEY_PROBED_FINGERPRINT, Build.FINGERPRINT.orEmpty())
                .apply()
        }
        runCatching {
            Settings.seccomp = true
        }
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

    internal fun probeAndConfigureIfNeeded(
        context: Context,
        distribution: TerminalDistribution.Spec
    ) {
        if (isSeccompCompatEnabled(context)) {
            return
        }
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val prefs = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        if (fingerprint.isNotEmpty() && prefs.getString(KEY_PROBED_FINGERPRINT, null) == fingerprint) {
            return
        }

        val linker = File("/system/bin/linker64").takeIf { it.canExecute() }
            ?: File("/system/bin/linker").takeIf { it.canExecute() }
            ?: return
        val parent = context.filesDir.parentFile ?: return
        val rootfs = File(File(parent, "local"), distribution.rootfsDirectoryName)
        val prootBin = runCatching { File(localBinDir(), "proot") }.getOrNull() ?: return
        if (!prootBin.isFile || !rootfs.isDirectory) {
            return
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
                ":"
            )
        )

        val result = runProbeProcess(context, command, probeTmpDir) ?: return
        if (result.first == 0) {
            if (fingerprint.isNotEmpty()) {
                prefs.edit().putString(KEY_PROBED_FINGERPRINT, fingerprint).apply()
            }
            return
        }

        if (isProotSeccompExecveFailure(result.second)) {
            Log.w(
                TAG,
                "PRoot seccomp filter failed during preflight probe; enabling PROOT_NO_SECCOMP=1 fallback."
            )
            enableAutoFallback(context)
        }
    }

    private fun runProbeProcess(
        context: Context,
        command: List<String>,
        probeTmpDir: File
    ): Pair<Int, String>? {
        val process = runCatching {
            ProcessBuilder(command).apply {
                redirectErrorStream(true)
                val env = environment()
                env.remove("PROOT_NO_SECCOMP")
                env.remove("SECCOMP")
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
