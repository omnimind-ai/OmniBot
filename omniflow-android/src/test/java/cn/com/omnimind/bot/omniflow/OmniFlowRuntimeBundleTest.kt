package cn.com.omnimind.bot.omniflow

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class OmniFlowRuntimeBundleTest {
    private fun manifest(entry: String = "bin/start.sh", version: Int = 1) = """
        {"interfaceVersion":$version,"version":"next","protocol":"2025-11-25",
         "entrypoint":"$entry","prepareEntrypoint":"bin/prepare.sh","sourceRoot":"engine/src",
         "tools":[{"name":"new_tool","description":"A new package tool",
         "inputSchema":{"type":"object"},"interactive":true,"agentVisible":true}]}
    """.trimIndent()

    @Test fun `package can relocate every private directory and declare a new tool`() {
        val parsed = parseOmniFlowRuntimeManifest(manifest().byteInputStream())
        assertEquals("bin/start.sh", parsed.entrypoint)
        assertEquals("engine/src", parsed.sourceRoot)
        assertEquals("new_tool", parsed.tools.single().name)
        assertTrue(parsed.tools.single().interactive)
    }

    @Test fun `reject incompatible host interface and escaping entrypoints`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseOmniFlowRuntimeManifest(manifest(version = 2).byteInputStream())
        }
        listOf("../escape", "/absolute", "bin/../../escape", "bin/start;bad").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                parseOmniFlowRuntimeManifest(manifest(entry = it).byteInputStream())
            }
        }
    }

    @Test fun `optional visibility defaults to published and missing paths fail validation`() {
        val minimal = manifest().replace(",\"interactive\":true,\"agentVisible\":true", "")
        val tool = parseOmniFlowRuntimeManifest(minimal.byteInputStream()).tools.single()
        assertTrue(tool.agentVisible)
        assertFalse(tool.interactive)
        assertThrows(IllegalArgumentException::class.java) {
            parseOmniFlowRuntimeManifest(manifest().replace("\"bin/start.sh\"", "null").byteInputStream())
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseOmniFlowRuntimeManifest("null".byteInputStream())
        }
    }

    @Test fun `runtime path cannot escape through a symbolic link`() {
        val root = Files.createTempDirectory("runtime-root").toFile()
        val outside = Files.createTempDirectory("runtime-outside").toFile()
        try {
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { runtimeFile(root, "escape/start.sh") }
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun `entrypoint quoting preserves paths containing shell syntax`() {
        val command = PreparedOmniFlowRuntime(
            parseOmniFlowRuntimeManifest(manifest().byteInputStream()), File("."),
            "/workspace/user's folder/\$(touch bad)", "test",
        ).command("bin/start.sh")
        val p = ProcessBuilder("sh", "-c", "printf '%s' " + shellQuote(command)).start()
        assertEquals(command, p.inputStream.bufferedReader().readText())
        assertEquals(0, p.waitFor())
    }
}
