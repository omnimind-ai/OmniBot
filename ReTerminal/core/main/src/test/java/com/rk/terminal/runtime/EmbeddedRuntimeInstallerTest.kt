package com.rk.terminal.runtime

import com.rk.libcommons.RuntimeAbi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class EmbeddedRuntimeInstallerTest {
    @Test
    fun rejectsNonEmptyPartialRootfs() = withTemporaryRootfs { rootfs ->
        rootfs.resolve("usr/bin").mkdirs()

        assertFalse(
            EmbeddedRuntimeInstaller.isRootfsInstalled(rootfs, TerminalDistribution.alpine)
        )
    }

    @Test
    fun rejectsMarkerWithoutMinimumRootfsLayout() = withTemporaryRootfs { rootfs ->
        touch(rootfs, EmbeddedRuntimeInstaller.ROOTFS_READY_MARKER_NAME)

        assertFalse(
            EmbeddedRuntimeInstaller.isRootfsInstalled(rootfs, TerminalDistribution.alpine)
        )
    }

    @Test
    fun acceptsMarkedRootfsWithMinimumLayout() = withTemporaryRootfs { rootfs ->
        touch(rootfs, "bin/sh")
        touch(rootfs, "etc/os-release")
        touch(rootfs, EmbeddedRuntimeInstaller.ROOTFS_READY_MARKER_NAME)

        assertTrue(
            EmbeddedRuntimeInstaller.isRootfsInstalled(rootfs, TerminalDistribution.alpine)
        )
    }

    @Test
    fun acceptsCompleteLegacyAlpineRootfs() = withTemporaryRootfs { rootfs ->
        listOf(
            "bin/sh",
            "etc/os-release",
            "usr/bin/env",
            "sbin/apk",
            "lib/apk/db/installed",
            "etc/alpine-release"
        ).forEach { touch(rootfs, it) }

        assertTrue(
            EmbeddedRuntimeInstaller.isRootfsInstalled(rootfs, TerminalDistribution.alpine)
        )
    }

    @Test
    fun acceptsCompleteLegacyUbuntuRootfs() = withTemporaryRootfs { rootfs ->
        listOf(
            "bin/sh",
            "etc/os-release",
            "usr/bin/env",
            "usr/bin/apt-get",
            "var/lib/dpkg/status"
        ).forEach { touch(rootfs, it) }

        assertTrue(
            EmbeddedRuntimeInstaller.isRootfsInstalled(rootfs, TerminalDistribution.ubuntu)
        )
    }

    @Test
    fun usesPinnedOfficialUbuntuRuntimeByDefault() {
        val entry = EmbeddedRuntimeInstaller.officialUbuntuRuntime()

        assertEquals("ubuntu", entry.id)
        assertEquals("24.04.4", entry.version)
        assertEquals(29_870_567L, entry.compressedSize)
        assertEquals(106_649_600L, entry.expandedSize)
        assertEquals(
            "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2",
            entry.sha256
        )
        assertEquals("cdimage.ubuntu.com", entry.downloadUrl.host)
        assertEquals("https", entry.downloadUrl.scheme)
    }

    @Test
    fun usesPinnedOfficialX86_64UbuntuRuntime() {
        val entry = EmbeddedRuntimeInstaller.officialUbuntuRuntime(abi = "x86_64")

        assertEquals("ubuntu", entry.id)
        assertEquals("24.04.4", entry.version)
        assertEquals("x86_64", entry.abi)
        assertEquals("ubuntu-base-24.04.4-base-amd64.tar.gz", entry.fileName)
        assertEquals(29_989_394L, entry.compressedSize)
        assertEquals(
            "c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58",
            entry.sha256
        )
        assertEquals("cdimage.ubuntu.com", entry.downloadUrl.host)
        assertEquals("https", entry.downloadUrl.scheme)
    }

    private fun manifest(
        id: String = "ubuntu",
        abi: String = "arm64-v8a",
        url: String = "https://updates.example/terminal-runtimes/downloads/ubuntu/24.04.4/ubuntu.tar.gz",
        compressedSize: Long = 2_000_000,
        expandedSize: Long = 8_000_000,
        sha256: String = "a".repeat(64)
    ): String = """
        {
          "schemaVersion": 1,
          "runtimes": [{
            "id": "$id",
            "version": "24.04.4",
            "abi": "$abi",
            "fileName": "ubuntu-base-24.04.4-base-arm64.tar.gz",
            "compressedSize": $compressedSize,
            "expandedSize": $expandedSize,
            "sha256": "$sha256",
            "downloadUrl": "$url"
          }]
        }
    """.trimIndent()

    @Test
    fun parsesPinnedArm64HttpsRuntime() {
        val entry = EmbeddedRuntimeInstaller.parseManifest(manifest(), "ubuntu")

        assertEquals("ubuntu", entry.id)
        assertEquals("24.04.4", entry.version)
        assertEquals("arm64-v8a", entry.abi)
        assertEquals(2_000_000, entry.compressedSize)
        assertEquals("https", entry.downloadUrl.scheme)
    }

    @Test
    fun parsesPinnedX86_64Runtime() {
        val entry = EmbeddedRuntimeInstaller.parseManifest(
            manifest(
                abi = "x86_64",
                url = "https://updates.example/terminal-runtimes/downloads/ubuntu/24.04.4/ubuntu-x86_64.tar.gz"
            ),
            "ubuntu",
            abi = "x86_64"
        )

        assertEquals("ubuntu", entry.id)
        assertEquals("24.04.4", entry.version)
        assertEquals("x86_64", entry.abi)
        assertEquals(2_000_000, entry.compressedSize)
        assertEquals("https", entry.downloadUrl.scheme)
    }

    @Test
    fun rejectsNonHttpsDownload() {
        assertThrows(IOException::class.java) {
            EmbeddedRuntimeInstaller.parseManifest(
                manifest(url = "http://updates.example/ubuntu.tar.gz"),
                "ubuntu"
            )
        }
    }

    @Test
    fun rejectsUnknownAbiAndDistribution() {
        assertThrows(IOException::class.java) {
            EmbeddedRuntimeInstaller.parseManifest(manifest(abi = "armeabi-v7a"), "ubuntu")
        }
        assertThrows(IOException::class.java) {
            EmbeddedRuntimeInstaller.parseManifest(manifest(id = "debian"), "debian")
        }
    }

    @Test
    fun rejectsInvalidSizesAndDigest() {
        assertThrows(IOException::class.java) {
            EmbeddedRuntimeInstaller.parseManifest(
                manifest(compressedSize = 8_000_000, expandedSize = 2_000_000),
                "ubuntu"
            )
        }
        assertThrows(IOException::class.java) {
            EmbeddedRuntimeInstaller.parseManifest(manifest(sha256 = "not-a-sha"), "ubuntu")
        }
    }

    @Test
    fun readsManifestWithinOneMiBLimit() = runBlocking {
        val payload = "{\"schemaVersion\":1,\"runtimes\":[]}".toByteArray()

        val actual = EmbeddedRuntimeInstaller.readBoundedManifest(
            input = ByteArrayInputStream(payload),
            declaredSize = -1L
        )

        assertTrue(payload.contentEquals(actual))
    }

    @Test
    fun rejectsChunkedManifestAsSoonAsItExceedsOneMiB() {
        val oversized = ByteArray(1024 * 1024 + 1)

        assertThrows(IOException::class.java) {
            runBlocking {
                EmbeddedRuntimeInstaller.readBoundedManifest(
                    input = ByteArrayInputStream(oversized),
                    declaredSize = -1L
                )
            }
        }
    }

    @Test
    fun cancellationInterruptsStalledHttpCall() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.NO_RESPONSE)
        )
        server.start()
        try {
            val call = OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
                .newCall(Request.Builder().url(server.url("/ubuntu.tar.gz")).build())
            val download = async(Dispatchers.IO) {
                EmbeddedRuntimeInstaller.executeCancellableCall(call) { response ->
                    response.body!!.byteStream().read()
                }
            }

            assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
            download.cancel()
            withTimeout(2_000L) { download.cancelAndJoin() }

            assertTrue(call.isCanceled())
        } finally {
            server.shutdown()
        }
    }

    private fun withTemporaryRootfs(block: (File) -> Unit) {
        val rootfs = Files.createTempDirectory("omnibot-rootfs-test").toFile()
        try {
            block(rootfs)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    private fun touch(rootfs: File, relativePath: String) {
        rootfs.resolve(relativePath).apply {
            parentFile?.mkdirs()
            writeText("test")
        }
    }
}
