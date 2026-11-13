package com.rk.terminal.runtime

import com.rk.libcommons.child
import com.rk.libcommons.localDir
import com.rk.settings.AlpinePackageMirror
import com.rk.settings.Settings
import java.io.File

object AlpineRepositoryManager {
    data class ApplyResult(
        val applied: Boolean,
        val repositoriesFile: File?
    )

    private const val DEFAULT_BRANCH = "v3.21"
    private const val OFFICIAL_BASE_URL = "https://dl-cdn.alpinelinux.org/alpine"
    private const val TSINGHUA_BASE_URL = "https://mirrors.tuna.tsinghua.edu.cn/alpine"
    private const val ALIYUN_BASE_URL = "https://mirrors.aliyun.com/alpine"

    fun selectedBaseUrl(): String {
        return baseUrlFor(Settings.alpine_package_mirror)
    }

    fun baseUrlFor(source: Int): String {
        return when (source) {
            AlpinePackageMirror.TSINGHUA -> TSINGHUA_BASE_URL
            else -> OFFICIAL_BASE_URL
        }
    }

    fun candidateBaseUrls(source: Int = Settings.alpine_package_mirror): List<String> {
        return listOf(
            baseUrlFor(source),
            ALIYUN_BASE_URL,
            OFFICIAL_BASE_URL,
            TSINGHUA_BASE_URL
        ).distinct()
    }

    fun buildSelectedRepositorySetupCommand(): String {
        return buildRepositorySetupCommand(Settings.alpine_package_mirror)
    }

    fun buildRepositorySetupCommand(source: Int): String {
        val quotedBaseUrl = shellSingleQuote(baseUrlFor(source))
        val quotedDefaultBranch = shellSingleQuote(DEFAULT_BRANCH)
        return "( set -e; branch=\"\$(if [ -r /etc/alpine-release ]; then cut -d. -f1,2 /etc/alpine-release | sed 's/^/v/'; else printf '%s' $quotedDefaultBranch; fi)\"; base=$quotedBaseUrl; mkdir -p /etc/apk; printf '%s/%s/main\\n%s/%s/community\\n' \"\$base\" \"\$branch\" \"\$base\" \"\$branch\" > /etc/apk/repositories )"
    }

    fun buildSelectedRepositoryUpdateWithFallbackCommand(): String {
        return buildRepositoryUpdateWithFallbackCommand(Settings.alpine_package_mirror)
    }

    fun buildRepositoryUpdateWithFallbackCommand(source: Int): String {
        val candidates = candidateBaseUrls(source).joinToString(" ") { shellSingleQuote(it) }
        val quotedDefaultBranch = shellSingleQuote(DEFAULT_BRANCH)
        return """
            (
              branch="${'$'}(if [ -r /etc/alpine-release ]; then cut -d. -f1,2 /etc/alpine-release | sed 's/^/v/'; else printf '%s' $quotedDefaultBranch; fi)"
              mkdir -p /etc/apk
              for base in $candidates; do
                printf '%s/%s/main\n%s/%s/community\n' "${'$'}base" "${'$'}branch" "${'$'}base" "${'$'}branch" > /etc/apk/repositories
                if apk update; then
                  exit 0
                fi
                printf '[!] Alpine mirror %s failed; trying next mirror.\n' "${'$'}base" >&2
              done
              for base in $candidates; do
                printf '%s/%s/main\n%s/%s/community\n' "${'$'}base" "${'$'}branch" "${'$'}base" "${'$'}branch" > /etc/apk/repositories
                if apk --no-check-certificate update; then
                  exit 0
                fi
              done
              exit 1
            )
        """.trimIndent()
    }

    fun applySelectedRepositoryToInstalledRootfs(): ApplyResult {
        return applyRepositoryToInstalledRootfs(Settings.alpine_package_mirror)
    }

    fun applyRepositoryToInstalledRootfs(source: Int): ApplyResult {
        val rootfs = installedRootfsDir()
        if (!isRootfsExtracted(rootfs)) {
            return ApplyResult(applied = false, repositoriesFile = null)
        }

        val repositoriesFile = rootfs.child("etc").child("apk").child("repositories")
        repositoriesFile.parentFile?.mkdirs()
        repositoriesFile.writeText(buildRepositoriesFileContent(source, detectInstalledBranch(rootfs)))
        return ApplyResult(applied = true, repositoriesFile = repositoriesFile)
    }

    private fun buildRepositoriesFileContent(source: Int, branch: String): String {
        val baseUrl = baseUrlFor(source)
        return buildString {
            append(baseUrl)
            append('/')
            append(branch)
            appendLine("/main")
            append(baseUrl)
            append('/')
            append(branch)
            appendLine("/community")
        }
    }

    private fun detectInstalledBranch(rootfs: File): String {
        val release = rootfs.child("etc").child("alpine-release")
        val majorMinor = release.takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.split('.')
            ?.take(2)
            ?.joinToString(".")
            ?.takeIf { it.isNotBlank() }
        return majorMinor?.let { "v$it" } ?: DEFAULT_BRANCH
    }

    private fun installedRootfsDir(): File {
        return localDir().child("alpine")
    }

    private fun isRootfsExtracted(rootfs: File): Boolean {
        return rootfs.child("etc").child("alpine-release").isFile ||
            rootfs.child("bin").isDirectory
    }

    private fun shellSingleQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }
}
