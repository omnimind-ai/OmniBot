package com.rk.libcommons

import android.content.Context
import java.io.File

object ShellAssetWriter {
    fun writeExecutableShellAsset(context: Context, assetName: String, target: File) {
        writeShellAsset(context, assetName, target)
        target.setExecutable(true, false)
    }

    fun writeShellAsset(context: Context, assetName: String, target: File) {
        target.parentFile?.mkdirs()
        val content = context.assets.open(assetName).bufferedReader().use { reader ->
            reader.readText()
        }.normalizeShellLineEndings()
        if (!target.exists() || target.readText().normalizeShellLineEndings() != content) {
            // 用「写临时文件 + rename」而非直接 writeText：chroot 后端 root 段写的
            // init-host-chroot-root 属主是 root:root，App 进程对 755 文件无写权限，
            // 直接 writeText 会抛 IOException → 终端环境未就绪。rename 只需父目录
            // 写权限（local/bin 属主 app_uid，App 有），与文件属主无关，可覆盖 root:root。
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            tmp.setReadable(true, false)
            tmp.setExecutable(target.exists() && target.canExecute(), false)
            if (!tmp.renameTo(target)) {
                // rename 失败（跨设备/权限）回退直接写
                target.writeText(content)
            }
        }
        target.setReadable(true, false)
    }

    private fun String.normalizeShellLineEndings(): String {
        return replace("\r\n", "\n").replace('\r', '\n')
    }
}
