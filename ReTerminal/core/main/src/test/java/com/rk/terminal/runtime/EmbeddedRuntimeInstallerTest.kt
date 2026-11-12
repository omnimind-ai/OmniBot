package com.rk.terminal.runtime

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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

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

    @Test
    fun extractsUncompressedAlpineTarAndOverwritesStaleGuestSymlinks() = withTemporaryRootfs { rootfs ->
        // Simulate a leftover partial symlink that used to trigger host Toybox tar's
        // "'./bin/chattr' /system/bin/busybox not under ..." failure.
        rootfs.resolve("bin").mkdirs()
        Files.createSymbolicLink(rootfs.resolve("bin/chattr").toPath(), Paths.get("/bin/busybox"))
        touch(rootfs, "root/keep-history", "keep-history")
        touch(rootfs, "tmp/keep-temp", "keep-temp")

        val archiveBytes = buildTarArchive(gzip = false) {
            regularFile("./bin/busybox", "busybox-binary", mode = 0b111_101_101)
            symlink("./bin/sh", "/bin/busybox")
            symlink("./bin/chattr", "/bin/busybox")
            symlink("./usr/bin/env", "/bin/busybox")
            regularFile("./sbin/apk", "apk-binary", mode = 0b111_101_101)
            regularFile("./lib/apk/db/installed", "C:Q1\n")
            regularFile("./etc/alpine-release", "3.21.0\n")
            regularFile("./etc/os-release", "ID=alpine\n")
            // Read-only directory mode in archive must still remain cleanable by app UID.
            directory("./var/empty/", mode = 0b101_101_101)
        }

        EmbeddedRuntimeInstaller.extractRootfsArchive(
            rootfs = rootfs,
            distribution = TerminalDistribution.alpine,
            openStream = { ByteArrayInputStream(archiveBytes) }
        )

        assertTrue(EmbeddedRuntimeInstaller.isRootfsInstalled(rootfs, TerminalDistribution.alpine))
        assertTrue(rootfs.resolve(EmbeddedRuntimeInstaller.ROOTFS_READY_MARKER_NAME).isFile)
        assertEquals(
            Paths.get("/bin/busybox"),
            Files.readSymbolicLink(rootfs.resolve("bin/chattr").toPath())
        )
        assertEquals("busybox-binary", rootfs.resolve("bin/busybox").readText())
        assertEquals("keep-history", rootfs.resolve("root/keep-history").readText())
        assertEquals("keep-temp", rootfs.resolve("tmp/keep-temp").readText())
    }

    @Test
    fun extractsGzippedUbuntuRootfsWithUsrmergeAndHardlinksAsRelativeSymlinks() = withTemporaryRootfs { rootfs ->
        val longConfPath = "etc/apt/apt.conf.d/" + "99-omnibot-very-long-configuration-name-".repeat(3) + ".conf"
        val archiveBytes = buildTarArchive(gzip = true) {
            symlink("bin", "usr/bin")
            directory("usr/bin/")
            regularFile("usr/bin/dash", "dash-bin", mode = 0b111_101_101)
            symlink("bin/sh", "dash")
            regularFile("usr/bin/env", "env-bin", mode = 0b111_101_101)
            regularFile("usr/bin/apt-get", "apt-get-bin", mode = 0b111_101_101)
            regularFile("usr/bin/gunzip", "gzip-payload", mode = 0b111_101_101)
            hardlink("usr/bin/uncompress", "usr/bin/gunzip")
            regularFile("usr/bin/perl", "perl-payload", mode = 0b111_101_101)
            hardlink("usr/bin/perl5.38.2", "usr/bin/perl")
            regularFile("etc/os-release", "ID=ubuntu\n")
            regularFile("var/lib/dpkg/status", "Package: base-files\nStatus: install ok installed\n")
            paxRegularFile(longConfPath, "Acquire::Retries \"3\";\n")
        }

        EmbeddedRuntimeInstaller.extractRootfsArchive(
            rootfs = rootfs,
            distribution = TerminalDistribution.ubuntu,
            openStream = { ByteArrayInputStream(archiveBytes) }
        )

        assertTrue(EmbeddedRuntimeInstaller.isRootfsInstalled(rootfs, TerminalDistribution.ubuntu))
        assertTrue(Files.isSymbolicLink(rootfs.resolve("bin").toPath()))
        assertTrue(Files.isSymbolicLink(rootfs.resolve("usr/bin/uncompress").toPath()))
        assertEquals(
            Paths.get("gunzip"),
            Files.readSymbolicLink(rootfs.resolve("usr/bin/uncompress").toPath())
        )
        assertEquals("gzip-payload", rootfs.resolve("usr/bin/uncompress").readText())
        assertEquals("perl-payload", rootfs.resolve("usr/bin/perl5.38.2").readText())
        assertEquals("Acquire::Retries \"3\";\n", rootfs.resolve(longConfPath).readText())
    }

    @Test
    fun rejectsTraversalAndCleansPartialRootfsWhilePreservingRootAndTmp() = withTemporaryRootfs { rootfs ->
        touch(rootfs, "root/user-profile", "user-profile")
        touch(rootfs, "tmp/socket", "socket")

        val traversalArchive = buildTarArchive(gzip = false) {
            regularFile("etc/os-release", "ID=alpine\n")
            symlink("escape", "../../outside")
            regularFile("escape/pwned", "nope")
        }

        assertThrows(IOException::class.java) {
            EmbeddedRuntimeInstaller.extractRootfsArchive(
                rootfs = rootfs,
                distribution = TerminalDistribution.alpine,
                openStream = { ByteArrayInputStream(traversalArchive) }
            )
        }

        assertFalse(EmbeddedRuntimeInstaller.isRootfsInstalled(rootfs, TerminalDistribution.alpine))
        assertFalse(rootfs.resolve(EmbeddedRuntimeInstaller.ROOTFS_READY_MARKER_NAME).exists())
        assertFalse(rootfs.resolve("etc/os-release").exists())
        assertEquals("user-profile", rootfs.resolve("root/user-profile").readText())
        assertEquals("socket", rootfs.resolve("tmp/socket").readText())
    }

    private fun withTemporaryRootfs(block: (File) -> Unit) {
        val rootfs = Files.createTempDirectory("omnibot-rootfs-test").toFile()
        try {
            block(rootfs)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    private fun touch(rootfs: File, relativePath: String, content: String = "test") {
        rootfs.resolve(relativePath).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }

    private class TarFixtureBuilder {
        private val raw = ByteArrayOutputStream()

        fun regularFile(name: String, content: String, mode: Int = 0b110_100_100) {
            val data = content.toByteArray(StandardCharsets.UTF_8)
            writeHeader(name = name, mode = mode, size = data.size.toLong(), typeFlag = '0', linkName = "")
            raw.write(data)
            writePadding(data.size)
        }

        fun paxRegularFile(path: String, content: String, mode: Int = 0b110_100_100) {
            val paxRecord = buildPaxRecord("path", path)
            writeHeader(
                name = "PaxHeader/entry",
                mode = 0b110_100_100,
                size = paxRecord.size.toLong(),
                typeFlag = 'x',
                linkName = ""
            )
            raw.write(paxRecord)
            writePadding(paxRecord.size)

            val data = content.toByteArray(StandardCharsets.UTF_8)
            writeHeader(
                name = path.takeLast(80),
                mode = mode,
                size = data.size.toLong(),
                typeFlag = '0',
                linkName = ""
            )
            raw.write(data)
            writePadding(data.size)
        }

        fun directory(name: String, mode: Int = 0b111_101_101) {
            val normalized = if (name.endsWith("/")) name else "$name/"
            writeHeader(name = normalized, mode = mode, size = 0L, typeFlag = '5', linkName = "")
        }

        fun symlink(name: String, target: String) {
            writeHeader(name = name, mode = 0b111_111_111, size = 0L, typeFlag = '2', linkName = target)
        }

        fun hardlink(name: String, target: String) {
            writeHeader(name = name, mode = 0b111_101_101, size = 0L, typeFlag = '1', linkName = target)
        }

        fun toBytes(gzip: Boolean): ByteArray {
            // End-of-archive marker: two 512-byte zero blocks.
            raw.write(ByteArray(1024))
            val tarBytes = raw.toByteArray()
            if (!gzip) return tarBytes
            val compressed = ByteArrayOutputStream()
            GZIPOutputStream(compressed).use { it.write(tarBytes) }
            return compressed.toByteArray()
        }

        private fun buildPaxRecord(key: String, value: String): ByteArray {
            val body = " $key=$value\n"
            var length = body.toByteArray(StandardCharsets.UTF_8).size + 2
            while (true) {
                val candidate = "$length$body".toByteArray(StandardCharsets.UTF_8)
                if (candidate.size == length) return candidate
                length = candidate.size
            }
        }

        private fun writeHeader(
            name: String,
            mode: Int,
            size: Long,
            typeFlag: Char,
            linkName: String
        ) {
            val block = ByteArray(512)
            writeAscii(block, 0, 100, name)
            writeOctal(block, 100, 8, mode.toLong())
            writeOctal(block, 108, 8, 0L)
            writeOctal(block, 116, 8, 0L)
            writeOctal(block, 124, 12, size)
            writeOctal(block, 136, 12, 1_700_000_000L)
            for (index in 148..155) {
                block[index] = ' '.code.toByte()
            }
            block[156] = typeFlag.code.toByte()
            writeAscii(block, 157, 100, linkName)
            writeAscii(block, 257, 6, "ustar")
            writeAscii(block, 263, 2, "00")
            val checksum = block.sumOf { it.toInt() and 0xFF }.toLong()
            writeOctal(block, 148, 7, checksum)
            block[155] = ' '.code.toByte()
            raw.write(block)
        }

        private fun writeAscii(block: ByteArray, offset: Int, maxLen: Int, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            bytes.copyInto(block, destinationOffset = offset, startIndex = 0, endIndex = minOf(bytes.size, maxLen))
        }

        private fun writeOctal(block: ByteArray, offset: Int, length: Int, value: Long) {
            val digits = value.toString(8).padStart(length - 1, '0')
            writeAscii(block, offset, length - 1, digits)
            block[offset + length - 1] = 0
        }

        private fun writePadding(size: Int) {
            val rem = size % 512
            if (rem != 0) {
                raw.write(ByteArray(512 - rem))
            }
        }
    }

    private fun buildTarArchive(gzip: Boolean, block: TarFixtureBuilder.() -> Unit): ByteArray {
        return TarFixtureBuilder().apply(block).toBytes(gzip)
    }
}
