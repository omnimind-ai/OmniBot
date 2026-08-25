package com.rk.terminal.runtime

import com.rk.libcommons.child
import com.rk.libcommons.localDir
import com.rk.settings.Settings
import com.rk.settings.UbuntuPackageMirror
import java.io.File

object UbuntuRepositoryManager {
    // 无 pending 字段：消费方（设置页 Toast）只看 applied，「保存待应用」语义由文案承担（评审死代码清理）
    data class ApplyResult(
        val applied: Boolean,
        val sourcesFile: File?
    )

    private const val NODE_MAJOR = 22
    private const val NODE_SOURCE_BASE_URL = "https://deb.nodesource.com"
    private const val DEFAULT_CODENAME = "noble"
    private const val OFFICIAL_BASE_URL = "http://ports.ubuntu.com/ubuntu-ports"
    private const val TSINGHUA_BASE_URL = "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
    private const val OFFICIAL_PYPI_BASE_URL = "https://pypi.org/simple"
    private const val TSINGHUA_PYPI_BASE_URL = "https://pypi.tuna.tsinghua.edu.cn/simple"

    fun selectedBaseUrl(): String {
        return baseUrlFor(Settings.ubuntu_package_mirror)
    }

    fun baseUrlFor(source: Int): String {
        return when (source) {
            UbuntuPackageMirror.TSINGHUA -> TSINGHUA_BASE_URL
            else -> OFFICIAL_BASE_URL
        }
    }

    /** pip 源跟随 apt 镜像：弱网直连 PyPI 会读超时（真机 12.3kB/s 教训），选清华则 pip 也走 tuna */
    fun pypiBaseUrlFor(source: Int): String {
        return when (source) {
            UbuntuPackageMirror.TSINGHUA -> TSINGHUA_PYPI_BASE_URL
            else -> OFFICIAL_PYPI_BASE_URL
        }
    }

    /** /etc/pip.conf 内容：无论镜像都拉长 read timeout + 多重试，弱网兜底 */
    internal fun buildPipConfContent(source: Int): String = buildString {
        appendLine("[global]")
        appendLine("index-url = ${pypiBaseUrlFor(source)}")
        appendLine("timeout = 120")
        appendLine("retries = 10")
    }

    fun buildSelectedRepositorySetupCommand(): String {
        return buildRepositorySetupCommand(Settings.ubuntu_package_mirror)
    }

    fun buildRepositorySetupCommand(source: Int): String {
        val mainBaseUrl = shellSingleQuote(baseUrlFor(source))
        val securityBaseUrl = shellSingleQuote(OFFICIAL_BASE_URL)
        // pip.conf 多行内容整体单引号字面量传入，printf 原样落盘
        val pipConf = shellSingleQuote(buildPipConfContent(source))
        return """
            ( set -e;
              . /etc/os-release;
              codename="${'$'}{VERSION_CODENAME:-$DEFAULT_CODENAME}";
              main_base=$mainBaseUrl;
              security_base=$securityBaseUrl;
              mkdir -p /etc/apt/sources.list.d;
              printf 'Types: deb\nURIs: %s\nSuites: %s %s-updates %s-backports\nComponents: main universe restricted multiverse\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n\nTypes: deb\nURIs: %s\nSuites: %s-security\nComponents: main universe restricted multiverse\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n' "${'$'}main_base" "${'$'}codename" "${'$'}codename" "${'$'}codename" "${'$'}security_base" "${'$'}codename" > /etc/apt/sources.list.d/ubuntu.sources;
              printf '%s' $pipConf > /etc/pip.conf
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
        // pip 源同步：设置页切镜像后，已装 rootfs 里的 pip 也跟随（/etc/pip.conf 全局生效）
        rootfs.child("etc").child("pip.conf").writeText(buildPipConfContent(source))
        return ApplyResult(applied = true, sourcesFile = sourcesFile)
    }

    internal fun buildSourcesFileContent(source: Int, codename: String): String {
        val normalizedCodename = codename.trim().ifBlank { DEFAULT_CODENAME }
        return buildString {
            appendLine("Types: deb")
            appendLine("URIs: ${baseUrlFor(source)}")
            appendLine("Suites: $normalizedCodename $normalizedCodename-updates $normalizedCodename-backports")
            appendLine("Components: main universe restricted multiverse")
            appendLine("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg")
            appendLine()
            appendLine("Types: deb")
            appendLine("URIs: $OFFICIAL_BASE_URL")
            appendLine("Suites: $normalizedCodename-security")
            appendLine("Components: main universe restricted multiverse")
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
