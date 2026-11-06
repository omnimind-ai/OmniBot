package com.rk.terminal.runtime

import com.rk.settings.UbuntuPackageMirror
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuRepositoryManagerTest {
    @Test
    fun guestSetupMatchesImmediateMirrorApplication() {
        listOf(UbuntuPackageMirror.OFFICIAL, UbuntuPackageMirror.TSINGHUA).forEach { mirror ->
            withGuestFixture { fixture ->
                val result = fixture.configure(mirror)

                assertEquals(result.output, 0, result.exitCode)
                assertEquals(
                    UbuntuRepositoryManager.buildSourcesFileContent(mirror, "noble"),
                    fixture.sources.readText()
                )
            }
        }
    }

    @Test
    fun foreignArchitecturesDoNotLeakIntoManagedPortsSources() = withGuestFixture { fixture ->
        val foreignArchitectures = fixture.root.resolve("var/lib/dpkg/arch").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("arm64\namd64\n")
        }
        val customSources = fixture.root.resolve("etc/apt/sources.list.d/custom.sources").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("Types: deb\nURIs: https://example.test/ubuntu\nArchitectures: amd64\n")
        }
        val originalCustomSources = customSources.readText()

        val result = fixture.configure(UbuntuPackageMirror.TSINGHUA)

        assertEquals(result.output, 0, result.exitCode)
        assertEquals(
            listOf("Architectures: arm64", "Architectures: arm64"),
            fixture.sources.readLines().filter { it.startsWith("Architectures:") }
        )
        assertFalse(fixture.sources.readText().contains("amd64"))
        assertEquals("arm64\namd64\n", foreignArchitectures.readText())
        assertEquals(originalCustomSources, customSources.readText())
        assertEquals("--print-architecture\n", fixture.dpkgCalls.readText())
    }

    @Test
    fun unsupportedNativeArchitectureLeavesExistingSourcesUntouched() = withGuestFixture { fixture ->
        requireNotNull(fixture.sources.parentFile).mkdirs()
        fixture.sources.writeText("existing sources\n")

        val result = fixture.configure(UbuntuPackageMirror.OFFICIAL, nativeArchitecture = "amd64")

        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("amd64"))
        assertTrue(result.output.contains("arm64"))
        assertEquals("existing sources\n", fixture.sources.readText())
    }

    @Test
    fun failedArchitectureProbeDoesNotCreateSources() = withGuestFixture { fixture ->
        val result = fixture.configure(UbuntuPackageMirror.OFFICIAL, nativeArchitecture = "probe-error")

        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("无法读取 Ubuntu 系统架构"))
        assertFalse(fixture.sources.exists())
    }

    private data class ShellResult(val exitCode: Int, val output: String)

    private class GuestFixture(val root: File) {
        val sources = root.resolve("etc/apt/sources.list.d/ubuntu.sources")
        val dpkgCalls = root.resolve("dpkg-calls")
        private val bin = root.resolve("runtime scripts").apply { mkdirs() }

        init {
            root.resolve("etc/os-release").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("ID=ubuntu\nVERSION_CODENAME=noble\n")
            }
            // Run the production script against fixture paths without requiring
            // a Linux guest or write access to the test machine's /etc.
            bin.resolve("ubuntu-repositories").writeText(
                repositoryAsset().readText().replace("/etc/", "${root.path}/etc/")
            )
            bin.resolve("dpkg").apply {
                writeText(
                    """
                    #!/bin/sh
                    printf '%s\n' "${'$'}*" >> "${'$'}TEST_DPKG_CALLS"
                    [ "${'$'}TEST_NATIVE_ARCH" != probe-error ] || exit 2
                    case "${'$'}1" in
                        --print-architecture) printf '%s\n' "${'$'}TEST_NATIVE_ARCH" ;;
                        --print-foreign-architectures) printf '%s\n' amd64 ;;
                        *) exit 2 ;;
                    esac
                    """.trimIndent()
                )
                setExecutable(true)
            }
        }

        fun configure(mirror: Int, nativeArchitecture: String = "arm64"): ShellResult {
            val process = ProcessBuilder(
                "/bin/sh", "-c", UbuntuRepositoryManager.buildRepositorySetupCommand(mirror)
            ).apply {
                redirectErrorStream(true)
                environment()["BIN"] = bin.path
                environment()["PATH"] = "${bin.path}:${System.getenv("PATH")}"
                environment()["TEST_NATIVE_ARCH"] = nativeArchitecture
                environment()["TEST_DPKG_CALLS"] = dpkgCalls.path
            }.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return ShellResult(process.waitFor(), output)
        }
    }

    private companion object {
        fun repositoryAsset(): File {
            val cwd = File(requireNotNull(System.getProperty("user.dir")))
            return listOf(
                cwd.resolve("src/main/assets/ubuntu-repositories.sh"),
                cwd.resolve("ReTerminal/core/main/src/main/assets/ubuntu-repositories.sh")
            ).firstOrNull(File::isFile) ?: error("Ubuntu repository setup asset is missing.")
        }

        fun withGuestFixture(test: (GuestFixture) -> Unit) {
            val root = Files.createTempDirectory("ubuntu-repositories-test").toFile()
            try {
                test(GuestFixture(root))
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
