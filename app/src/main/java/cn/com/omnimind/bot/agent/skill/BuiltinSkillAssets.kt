package cn.com.omnimind.bot.agent

import java.io.File

/** Bundled code is replaceable; skill-local learning data belongs to the user. */
internal fun refreshBuiltinSkillAssets(targetDir: File, copyAssets: () -> Unit) {
    targetDir.listFiles().orEmpty().filter { it.name != "data" }.forEach {
        check(it.deleteRecursively()) { "Cannot refresh builtin skill asset: ${it.name}" }
    }
    copyAssets()
}
