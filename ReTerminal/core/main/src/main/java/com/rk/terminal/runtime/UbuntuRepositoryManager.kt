package com.rk.terminal.runtime

import com.rk.libcommons.child
import com.rk.libcommons.localDir
import com.rk.settings.Settings
import com.rk.settings.UbuntuPackageMirror
import java.io.File

object UbuntuRepositoryManager {
    data class ApplyResult(
        val applied: Boolean,
        val sourcesFile: File?
    )

    private const val NODE_MAJOR = 22
    private const val NODE_SOURCE_BASE_URL = "https://deb.nodesource.com"
    private const val DEFAULT_CODENAME = "noble"
    private const val OFFICIAL_BASE_URL = "http://ports.ubuntu.com/ubuntu-ports"
    private const val TSINGHUA_BASE_URL = "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
    private const val ALIYUN_BASE_URL = "http://mirrors.aliyun.com/ubuntu-ports"

    fun selectedBaseUrl(): String {
        return baseUrlFor(Settings.ubuntu_package_mirror)
    }

    fun baseUrlFor(source: Int): String {
        return when (source) {
            UbuntuPackageMirror.TSINGHUA -> TSINGHUA_BASE_URL
            else -> OFFICIAL_BASE_URL
        }
    }

    fun candidateBaseUrls(source: Int = Settings.ubuntu_package_mirror): List<String> {
        return listOf(
            baseUrlFor(source),
            ALIYUN_BASE_URL,
            OFFICIAL_BASE_URL,
            TSINGHUA_BASE_URL
        ).distinct()
    }

    fun buildSelectedRepositorySetupCommand(): String {
        return buildRepositorySetupCommand(Settings.ubuntu_package_mirror)
    }

    fun buildRepositorySetupCommand(source: Int): String {
        val mainBaseUrl = shellSingleQuote(baseUrlFor(source))
        return "/bin/sh \"${'$'}BIN/ubuntu-repositories\" $mainBaseUrl"
    }

    fun buildSelectedRepositoryUpdateWithFallbackCommand(): String {
        return buildRepositoryUpdateWithFallbackCommand(Settings.ubuntu_package_mirror)
    }

    fun buildRepositoryUpdateWithFallbackCommand(source: Int): String {
        val candidates = candidateBaseUrls(source).joinToString(" ") { shellSingleQuote(it) }
        val official = shellSingleQuote(OFFICIAL_BASE_URL)
        return """
            (
              for base in $candidates; do
                if /bin/sh "${'$'}BIN/ubuntu-repositories" "${'$'}base" && apt-get update; then
                  exit 0
                fi
                if [ "${'$'}base" != $official ]; then
                  if /bin/sh "${'$'}BIN/ubuntu-repositories" "${'$'}base" "${'$'}base" && apt-get update; then
                    exit 0
                  fi
                fi
                printf '[!] Ubuntu mirror %s failed; trying next mirror.\n' "${'$'}base" >&2
              done
              exit 1
            )
        """.trimIndent()
    }

    fun applySelectedRepositoryToInstalledRootfs(): ApplyResult {
        return applyRepositoryToInstalledRootfs(Settings.ubuntu_package_mirror)
    }

    fun applyRepositoryToInstalledRootfs(source: Int): ApplyResult {
        val rootfs = localDir().child("ubuntu")
        if (!isRootfsExtracted(rootfs)) {
            return ApplyResult(applied = false, sourcesFile = null)
        }

        val sourcesFile = rootfs.child("etc").child("apt").child("sources.list.d").child("ubuntu.sources")
        sourcesFile.parentFile?.mkdirs()
        sourcesFile.writeText(
            buildSourcesFileContent(
                source = source,
                codename = detectInstalledCodename(rootfs)
            )
        )
        return ApplyResult(applied = true, sourcesFile = sourcesFile)
    }

    internal fun buildSourcesFileContent(source: Int, codename: String): String {
        val normalizedCodename = codename.trim().ifBlank { DEFAULT_CODENAME }
        return buildString {
            appendLine("Types: deb")
            appendLine("URIs: ${baseUrlFor(source)}")
            appendLine("Suites: $normalizedCodename $normalizedCodename-updates $normalizedCodename-backports")
            appendLine("Components: main universe restricted multiverse")
            appendLine("Architectures: arm64")
            appendLine("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg")
            appendLine()
            appendLine("Types: deb")
            appendLine("URIs: $OFFICIAL_BASE_URL")
            appendLine("Suites: $normalizedCodename-security")
            appendLine("Components: main universe restricted multiverse")
            appendLine("Architectures: arm64")
            appendLine("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg")
        }
    }

    private fun detectInstalledCodename(rootfs: File): String {
        val osRelease = rootfs.child("etc").child("os-release")
        return osRelease.takeIf { it.isFile }
            ?.useLines { lines ->
                lines.firstOrNull { it.startsWith("VERSION_CODENAME=") }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.trim('"', '\'')
            }
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_CODENAME
    }

    private fun isRootfsExtracted(rootfs: File): Boolean {
        return rootfs.child("etc").child("os-release").isFile ||
            rootfs.child("usr").child("bin").isDirectory
    }

    fun buildNodeRepositorySetupCommand(): String {
        return """
            ( set -e;
              apt-get update;
              DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl gnupg;
              mkdir -p /usr/share/keyrings /etc/apt/sources.list.d;
              key_file="/tmp/nodesource-repo-key.${'$'}${'$'}";
              rm -f "${'$'}key_file";
              curl -fsSL "$NODE_SOURCE_BASE_URL/gpgkey/nodesource-repo.gpg.key" -o "${'$'}key_file";
              gpg --batch --yes --dearmor --output /usr/share/keyrings/nodesource.gpg "${'$'}key_file";
              rm -f "${'$'}key_file";
              chmod 644 /usr/share/keyrings/nodesource.gpg;
              arch="${'$'}(dpkg --print-architecture)";
              case "${'$'}arch" in arm64|amd64) ;; *) echo "Unsupported NodeSource architecture: ${'$'}arch" >&2; exit 1 ;; esac;
              printf 'Types: deb\nURIs: $NODE_SOURCE_BASE_URL/node_${NODE_MAJOR}.x\nSuites: nodistro\nComponents: main\nArchitectures: %s\nSigned-By: /usr/share/keyrings/nodesource.gpg\n' "${'$'}arch" > /etc/apt/sources.list.d/nodesource.sources;
              printf 'Package: nodejs\nPin: origin deb.nodesource.com\nPin-Priority: 600\n' > /etc/apt/preferences.d/nodejs
            )
        """.trimIndent()
    }

    private fun shellSingleQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }
}
