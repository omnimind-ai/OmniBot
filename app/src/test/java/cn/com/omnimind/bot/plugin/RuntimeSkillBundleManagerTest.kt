package cn.com.omnimind.bot.plugin.runtime

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimeSkillBundleManagerTest {
    @org.junit.Test fun `absent remote metadata cannot make an old packaged installation current`() {
        org.junit.Assert.assertFalse(matchesRuntimeArchiveMarker(null, null))
        org.junit.Assert.assertFalse(matchesRuntimeArchiveMarker("old", null))
        org.junit.Assert.assertFalse(matchesRuntimeArchiveMarker("old", "new"))
        org.junit.Assert.assertTrue(matchesRuntimeArchiveMarker("new\n", "new"))
    }

    @Test
    fun `component archive requires a pinned sha256`() {
        val error = expectFailure {
            runtimeSpec(
                componentArchiveUrl = "https://github.com/example/runtime.zip",
            ).validated()
        }

        assertTrue(error.message.orEmpty().contains("URL and SHA-256"))
    }

    @Test
    fun `component version must use semantic versioning`() {
        val error = expectFailure {
            runtimeSpec(componentVersion = "latest").validated()
        }

        assertTrue(error.message.orEmpty().contains("component version"))
    }

    @Test
    fun `component archive and checksum validate together`() {
        val spec = runtimeSpec(
            componentArchiveUrl = "https://github.com/example/runtime.zip",
            componentArchiveSha256 = "a".repeat(64),
        ).validated()

        assertEquals("2.1.6", spec.componentVersion)
        assertEquals("a".repeat(64), spec.componentArchiveSha256)
    }

    @Test
    fun `packaged release archive uses the same pinned checksum`() {
        val spec = RuntimeSkillSpec(
            componentId = COMPONENT_ID,
            componentVersion = COMPONENT_VERSION,
            id = SKILL_ID,
            packagedArchivePath = "runtime-components/component.zip",
            componentArchiveUrl = "https://github.com/example/component.zip",
            componentArchiveSha256 = "a".repeat(64),
        ).validated()

        assertEquals("runtime-components/component.zip", spec.packagedArchivePath)
        assertEquals("a".repeat(64), spec.componentArchiveSha256)
    }

    @Test
    fun `packaged release archive requires a pinned checksum`() {
        val error = expectFailure {
            RuntimeSkillSpec(
                componentId = COMPONENT_ID,
                componentVersion = COMPONENT_VERSION,
                id = SKILL_ID,
                packagedArchivePath = "runtime-components/component.zip",
            ).validated()
        }

        assertTrue(error.message.orEmpty().contains("packaged archive SHA-256"))
    }

    @Test
    fun `verified component archive extracts bundled runtime`() {
        val root = createTempDirectory("omniflow-component-").toFile()
        try {
            val archive = File(root, "component.zip")
            writeArchive(archive, validComponentEntries())
            val target = File(root, "unpacked")

            unpackVerifiedComponentArchive(
                archive = archive,
                target = target,
                expectedSha256 = sha256(archive),
                componentId = COMPONENT_ID,
                componentVersion = COMPONENT_VERSION,
                runtimeSkillId = SKILL_ID,
            )

            assertTrue(File(target, "SKILL.md").isFile)
            assertFalse(File(target, "pyproject.toml").exists())
            assertFalse(File(target, "uv.lock").exists())
            assertFalse(File(target, "runtime.prebuilt.zip").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `component archive checksum mismatch fails before extraction`() {
        val root = createTempDirectory("omniflow-component-").toFile()
        try {
            val archive = File(root, "component.zip")
            writeArchive(archive, validComponentEntries())
            val target = File(root, "unpacked")

            val error = expectFailure {
                unpackVerifiedComponentArchive(
                    archive = archive,
                    target = target,
                    expectedSha256 = "0".repeat(64),
                    componentId = COMPONENT_ID,
                    componentVersion = COMPONENT_VERSION,
                    runtimeSkillId = SKILL_ID,
                )
            }

            assertTrue(error.message.orEmpty().contains("checksum_mismatch"))
            assertFalse(target.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `component archive rejects entries escaping install directory`() {
        val root = createTempDirectory("omniflow-component-").toFile()
        try {
            val archive = File(root, "component.zip")
            writeArchive(
                archive,
                validComponentEntries() + ("../escaped" to "bad"),
            )

            val error = expectFailure {
                unpackVerifiedComponentArchive(
                    archive = archive,
                    target = File(root, "unpacked"),
                    expectedSha256 = sha256(archive),
                    componentId = COMPONENT_ID,
                    componentVersion = COMPONENT_VERSION,
                    runtimeSkillId = SKILL_ID,
                )
            }

            assertTrue(error.message.orEmpty().contains("unsafe_entry"))
            assertFalse(File(root, "escaped").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `component manifest accepts bundled site packages`() {
        val root = createTempDirectory("omniflow-component-").toFile()
        try {
            validComponentEntries().forEach { (path, contents) ->
                File(root, path).apply {
                    parentFile?.mkdirs()
                    writeText(contents)
                }
            }

            val install = readRuntimeComponentInstall(
                root = root,
                expectedComponentId = COMPONENT_ID,
                expectedComponentVersion = COMPONENT_VERSION,
                expectedSkillId = SKILL_ID,
            )

            assertEquals("bundled", install.manager)
            assertEquals("vendor/site-packages", install.sitePackagesPath)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runtimeSpec(
        componentVersion: String = COMPONENT_VERSION,
        componentArchiveUrl: String? = null,
        componentArchiveSha256: String? = null,
    ) = RuntimeSkillSpec(
        componentId = COMPONENT_ID,
        componentVersion = componentVersion,
        id = SKILL_ID,
        packagedAssetPath = if (componentArchiveUrl == null) "omni-vlm-lite/runtime-skill" else null,
        componentArchiveUrl = componentArchiveUrl,
        componentArchiveSha256 = componentArchiveSha256,
    )

    private fun validComponentEntries(): Map<String, String> = mapOf(
        "component.json" to """
            {
              "schemaVersion": 1,
              "id": "$COMPONENT_ID",
              "version": "$COMPONENT_VERSION",
              "skill": {"id": "$SKILL_ID"},
              "install": {
                "manager": "bundled",
                "sitePackages": "vendor/site-packages"
              }
            }
        """.trimIndent(),
        "SKILL.md" to "---\nname: $SKILL_ID\n---\n",
        "vendor/site-packages/.keep" to "",
    )

    private fun writeArchive(archive: File, entries: Map<String, String>) {
        ZipOutputStream(archive.outputStream().buffered()).use { output ->
            entries.forEach { (name, contents) ->
                output.putNextEntry(ZipEntry(name))
                output.write(contents.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun expectFailure(block: () -> Unit): Throwable = try {
        block()
        fail("Expected failure")
        error("unreachable")
    } catch (error: Throwable) {
        error
    }

    private companion object {
        const val COMPONENT_ID = "com.omnimind.omni-vlm-lite"
        const val COMPONENT_VERSION = "2.1.6"
        const val SKILL_ID = "omniflow-gui-runtime"
    }
}
