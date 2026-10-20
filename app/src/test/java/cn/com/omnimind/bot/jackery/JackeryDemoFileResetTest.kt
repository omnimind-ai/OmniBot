package cn.com.omnimind.bot.jackery

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class JackeryDemoFileResetTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun deletingWorkspaceLinkNeverDeletesItsExternalTarget() {
        val external = folder.newFolder("external-device-assets")
        val asset = File(external, "device.png").apply { writeText("approved product image") }
        val workspace = folder.newFolder("workspace")
        Files.createSymbolicLink(File(workspace, "public").toPath(), external.toPath())
        val memory = File(workspace, ".omnibot/memory/nested").apply { mkdirs() }
        File(memory, "MEMORY.md").writeText("Exhibition visitor memory")
        Files.createSymbolicLink(File(memory, "approved-art").toPath(), external.toPath())
        JackeryDemoReset.deleteOwnedTree(File(workspace, ".omnibot/memory"))
        assertTrue(asset.exists())
        File(workspace, "demo-output.txt").writeText("demo user file")
        JackeryDemoReset.deleteOwnedTree(workspace)
        assertFalse(workspace.exists())
        assertTrue(asset.exists())
        assertEquals("approved product image", asset.readText())
    }
}
