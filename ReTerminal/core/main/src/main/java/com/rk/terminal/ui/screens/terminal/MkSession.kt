package com.rk.terminal.ui.screens.terminal

import android.content.Context
import android.os.Environment
import android.util.Log
import com.rk.libcommons.OmnibotTerminalEnvironment
import com.rk.libcommons.ShellArgv
import com.rk.libcommons.ShellAssetWriter
import com.rk.libcommons.TerminalCommand
import com.rk.libcommons.application
import com.rk.libcommons.child
import com.rk.libcommons.localBinDir
import com.rk.libcommons.localDir
import com.rk.libcommons.localLibDir
import com.rk.libcommons.terminalHomeDir
import com.rk.settings.Settings
import com.rk.terminal.App
import com.rk.terminal.App.Companion.getTempDir
import com.rk.terminal.BuildConfig
import com.rk.terminal.runtime.AlpineRepositoryManager
import com.rk.terminal.runtime.ProotSeccompCompatibility
import com.rk.terminal.runtime.TerminalDistribution
import com.rk.terminal.runtime.UbuntuRepositoryManager
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

object MkSession {
    private const val TAG = "MkSession"

    fun createSession(
        context: Context,
        sessionClient: TerminalSessionClient,
        session_id: String,
        workingMode: Int,
        extraEnv: Map<String, String> = emptyMap(),
        launchCommand: TerminalCommand? = null
    ): TerminalSession {
        with(context) {
            val hostWorkspaceDir = File(applicationInfo.dataDir, "workspace").also { directory ->
                if (!directory.exists()) {
                    directory.mkdirs()
                }
            }
            val envVariables = mapOf(
                "ANDROID_ART_ROOT" to System.getenv("ANDROID_ART_ROOT"),
                "ANDROID_DATA" to System.getenv("ANDROID_DATA"),
                "ANDROID_I18N_ROOT" to System.getenv("ANDROID_I18N_ROOT"),
                "ANDROID_ROOT" to System.getenv("ANDROID_ROOT"),
                "ANDROID_RUNTIME_ROOT" to System.getenv("ANDROID_RUNTIME_ROOT"),
                "ANDROID_TZDATA_ROOT" to System.getenv("ANDROID_TZDATA_ROOT"),
                "BOOTCLASSPATH" to System.getenv("BOOTCLASSPATH"),
                "DEX2OATBOOTCLASSPATH" to System.getenv("DEX2OATBOOTCLASSPATH"),
                "EXTERNAL_STORAGE" to System.getenv("EXTERNAL_STORAGE")
            )

            val distribution = TerminalDistribution.fromWorkingMode(workingMode)
            val workingDir = launchCommand?.workingDir ?: terminalHomeDir(workingMode).path

            val initFile = ShellAssetWriter.writeTerminalShellAssets(this)
            val env = mutableListOf(
                "PATH=${System.getenv("PATH")}:/sbin:${localBinDir().absolutePath}",
                "HOME=/sdcard",
                "PUBLIC_HOME=${getExternalFilesDir(null)?.absolutePath}",
                "COLORTERM=truecolor",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "BIN=${localBinDir()}",
                "DEBUG=${BuildConfig.DEBUG}",
                "PREFIX=${filesDir.parentFile!!.path}",
                "LD_LIBRARY_PATH=${localLibDir().absolutePath}",
                "LINKER=${if(File("/system/bin/linker64").exists()){"/system/bin/linker64"}else{"/system/bin/linker"}}",
                "NATIVE_LIB_DIR=${applicationInfo.nativeLibraryDir}",
                "PKG=${packageName}",
                "RISH_APPLICATION_ID=${packageName}",
                "PKG_PATH=${applicationInfo.sourceDir}",
                "OMNIBOT_HOST_WORKSPACE=${hostWorkspaceDir.absolutePath}",
                "OMNIBOT_TERMINAL_DISTRIBUTION=${distribution.id}",
                "OMNIBOT_ALPINE_APK_REPOSITORY_BASE=${AlpineRepositoryManager.selectedBaseUrl()}",
                "OMNIBOT_UBUNTU_APT_REPOSITORY_BASE=${UbuntuRepositoryManager.selectedBaseUrl()}",
                "PROOT_TMP_DIR=${getTempDir().child(session_id).also { if (it.exists().not()){it.mkdirs()} }}",
                "TMPDIR=${getTempDir().absolutePath}"
            )

            if (File(applicationInfo.nativeLibraryDir).child("libproot-loader32.so").exists()){
                env.add("PROOT_LOADER32=${applicationInfo.nativeLibraryDir}/libproot-loader32.so")
                env.add("PROOT_LOADER_32=${applicationInfo.nativeLibraryDir}/libproot-loader32.so")
            }

            if (File(applicationInfo.nativeLibraryDir).child("libproot-loader.so").exists()){
                env.add("PROOT_LOADER=${applicationInfo.nativeLibraryDir}/libproot-loader.so")
            }

            OmnibotTerminalEnvironment.buildTerminalEnvironment(applicationContext)
                .forEach { (key, value) ->
                    val normalizedKey = key.trim()
                    if (normalizedKey.isNotEmpty()) {
                        env.removeAll { item -> item.substringBefore('=') == normalizedKey }
                        env.add("$normalizedKey=$value")
                    }
                }



            env.addAll(envVariables.map { "${it.key}=${it.value}" })

            localDir().child("stat").apply {
                if (exists().not()){
                    writeText(stat)
                }
            }

            localDir().child("vmstat").apply {
                if (exists().not()){
                    writeText(vmstat)
                }
            }

            launchCommand?.env?.let {
                env.addAll(it)
            }

            if (extraEnv.isNotEmpty()) {
                val overriddenKeys = extraEnv.keys.map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                if (overriddenKeys.isNotEmpty()) {
                    env.removeAll { item ->
                        val separatorIndex = item.indexOf('=')
                        if (separatorIndex <= 0) {
                            return@removeAll false
                        }
                        item.substring(0, separatorIndex) in overriddenKeys
                    }
                }
                extraEnv.forEach { (key, value) ->
                    val normalizedKey = key.trim()
                    if (normalizedKey.isEmpty()) {
                        return@forEach
                    }
                    env.add("$normalizedKey=$value")
                }
            }

            ProotSeccompCompatibility.applyEnvironmentList(
                env,
                ProotSeccompCompatibility.isSeccompCompatEnabled(this)
            )

            val args: Array<String>

            val shell = if (launchCommand == null) {
                args = if (TerminalDistribution.isLinuxWorkingMode(workingMode)){
                    ShellArgv.buildShellScriptArgv(initFile.absolutePath)
                }else{
                    ShellArgv.buildInteractiveShellArgv()
                }
                ShellArgv.SYSTEM_SH
            } else{
                args = launchCommand.args
                launchCommand.shell
            }

            Log.d(TAG, "Launching session ${ShellArgv.formatExecSpec(shell, args, workingDir)}")

            return TerminalSession(
                shell,
                workingDir,
                args,
                env.toTypedArray(),
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                sessionClient,
            )
        }

    }
}
