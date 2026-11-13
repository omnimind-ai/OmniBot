package com.rk.terminal.runtime

import com.rk.settings.AlpinePackageMirror
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineRepositoryManagerTest {
    @Test
    fun candidateBaseUrlsPrioritizeSelectedMirrorWithoutDuplicates() {
        assertEquals(
            listOf(
                "https://mirrors.tuna.tsinghua.edu.cn/alpine",
                "https://mirrors.aliyun.com/alpine",
                "https://dl-cdn.alpinelinux.org/alpine"
            ),
            AlpineRepositoryManager.candidateBaseUrls(AlpinePackageMirror.TSINGHUA)
        )
        assertEquals(
            listOf(
                "https://dl-cdn.alpinelinux.org/alpine",
                "https://mirrors.aliyun.com/alpine",
                "https://mirrors.tuna.tsinghua.edu.cn/alpine"
            ),
            AlpineRepositoryManager.candidateBaseUrls(AlpinePackageMirror.OFFICIAL)
        )
    }

    @Test
    fun updateWithFallbackSwitchesMirrorWhenPrimaryReturnsForbidden() {
        val tempRoot = Files.createTempDirectory("alpine-repo-fallback-test").toFile()
        try {
            val binDir = tempRoot.resolve("bin").apply { mkdirs() }
            val etcApk = tempRoot.resolve("etc/apk").apply { mkdirs() }
            val releaseFile = tempRoot.resolve("etc/alpine-release").apply {
                writeText("3.21.0\n")
            }
            val attemptsFile = tempRoot.resolve("apk-attempts.log")

            binDir.resolve("apk").apply {
                writeText(
                    """
                    #!/bin/sh
                    repo_url=${'$'}(head -n 1 "${etcApk.absolutePath}/repositories")
                    printf '%s|%s\n' "${'$'}*" "${'$'}repo_url" >> "${attemptsFile.absolutePath}"
                    case "${'$'}repo_url" in
                      *mirrors.aliyun.com*) exit 0 ;;
                      *) exit 4 ;;
                    esac
                    """.trimIndent()
                )
                setExecutable(true)
            }

            val script = AlpineRepositoryManager
                .buildRepositoryUpdateWithFallbackCommand(AlpinePackageMirror.TSINGHUA)
                .replace("/etc/alpine-release", releaseFile.absolutePath)
                .replace("/etc/apk", etcApk.absolutePath)

            val process = ProcessBuilder("/bin/sh", "-c", script)
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "${binDir.absolutePath}:${System.getenv("PATH")}"
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            assertEquals(output, 0, exitCode)
            assertEquals(
                """
                https://mirrors.aliyun.com/alpine/v3.21/main
                https://mirrors.aliyun.com/alpine/v3.21/community
                """.trimIndent() + "\n",
                etcApk.resolve("repositories").readText()
            )
            assertEquals(
                listOf(
                    "update|https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.21/main",
                    "update|https://mirrors.aliyun.com/alpine/v3.21/main"
                ),
                attemptsFile.readLines()
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
