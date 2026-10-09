package cn.com.omnimind.bot.ui.workspace

import cn.com.omnimind.nativeui.workspace.WorkspaceSelection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceFileRepositoryTest {
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var host: File
    private lateinit var repository: WorkspaceFileRepository

    @Before fun setUp() {
        base = Files.createTempDirectory("ws-repo").toFile().canonicalFile
        root = File(base, "workspace").apply { mkdirs() }
        host = File(base, "host").apply { mkdirs() }
        File(host, "keep.txt").writeText("host file")
        File(root, "docs").mkdirs()
        File(root, "docs/note.md").writeText("# note")
        File(root, "Zeta.txt").writeText("z")
        File(root, "alpha.txt").writeText("a")
        Files.createSymbolicLink(File(root, "mounted").toPath(), host.toPath())
        repository = WorkspaceFileRepository(root.path)
    }

    @After fun tearDown() {
        base.deleteRecursively()
    }

    @Test fun listsFoldersFirstThenCaseInsensitiveAndMarksMounts() {
        val listing = repository.list(root.path)
        assertTrue(listing.exists)
        assertEquals(listOf("docs", "mounted", "alpha.txt", "Zeta.txt"), listing.entries.map { it.name })
        val mount = listing.entries.first { it.name == "mounted" }
        assertTrue(mount.directory)
        assertTrue(mount.mount)
        assertFalse(listing.entries.first { it.name == "docs" }.mount)
    }

    @Test fun aMissingFolderListsAsNotFound() {
        assertFalse(repository.list(File(root, "gone").path).exists)
    }

    /** Fix (5e-8a): deleting a mount must remove only the link, never the host folder it points to. */
    @Test fun deletingAMountUnmountsWithoutTouchingTheHostFiles() {
        val link = File(root, "mounted").path
        assertTrue(repository.isMountRoot(link))
        repository.unmount(link)
        assertFalse(repository.exists(link))
        assertTrue(File(host, "keep.txt").exists())
    }

    @Test fun bulkDeleteOfASelectedMountUnmountsIt() {
        val selection = WorkspaceSelection().select(File(root, "mounted").path)
        val result = repository.deleteSelected(selection, root.path)
        assertEquals(1, result.deleted)
        assertFalse(repository.exists(File(root, "mounted").path))
        assertTrue(File(host, "keep.txt").exists())
    }

    @Test fun aNestedLinkIsDeletedAsALink() {
        val outside = File(base, "outside").apply { mkdirs() }
        File(outside, "precious.txt").writeText("keep")
        Files.createSymbolicLink(File(root, "docs/link").toPath(), outside.toPath())
        repository.delete(File(root, "docs").path)
        assertFalse(File(root, "docs").exists())
        assertTrue(File(outside, "precious.txt").exists())
    }

    @Test fun bulkDeleteKeepsExcludedChildrenAndTheirFolder() {
        val docs = File(root, "docs").path
        File(root, "docs/drop.txt").writeText("x")
        val selection = WorkspaceSelection().select(docs).deselect("$docs/note.md", root.path)
        val result = repository.deleteSelected(selection, root.path)
        assertEquals(1, result.deleted)
        assertTrue(File(root, "docs/note.md").exists())
        assertFalse(File(root, "docs/drop.txt").exists())
    }

    @Test fun bulkDeleteRemovesAFullySelectedFolder() {
        val result = repository.deleteSelected(WorkspaceSelection().select(File(root, "docs").path), root.path)
        assertEquals(1, result.deleted)
        assertFalse(File(root, "docs").exists())
    }

    @Test fun renameRefusesTakenNamesAndReportsMissingSources() {
        val alpha = File(root, "alpha.txt").path
        assertEquals(WorkspaceFileRepository.RenameResult.Taken, repository.rename(alpha, "Zeta.txt"))
        assertEquals(WorkspaceFileRepository.RenameResult.Unchanged, repository.rename(alpha, "alpha.txt"))
        assertEquals(WorkspaceFileRepository.RenameResult.Renamed, repository.rename(alpha, "beta.txt"))
        assertTrue(File(root, "beta.txt").exists())
        assertEquals(WorkspaceFileRepository.RenameResult.Missing, repository.rename(alpha, "gamma.txt"))
    }

    @Test fun movesIntoAFolderAndDetectsNameClashes() {
        val alpha = File(root, "alpha.txt").path
        val docs = File(root, "docs").path
        assertFalse(repository.destinationTaken(alpha, docs))
        repository.move(alpha, docs)
        assertTrue(File(root, "docs/alpha.txt").exists())
        File(root, "alpha.txt").writeText("again")
        assertTrue(repository.destinationTaken(File(root, "alpha.txt").path, docs))
    }

    /** Fix (5e-8a): the preview wrote in place; a save now replaces the file atomically. */
    @Test fun writeReplacesContentAndLeavesNoTempFile() {
        val note = File(root, "docs/note.md")
        repository.writeText(note.path, "updated ✓")
        assertEquals("updated ✓", note.readText())
        assertEquals(listOf("note.md"), File(root, "docs").list()!!.toList())
    }

    @Test fun writingThroughALinkKeepsTheLink() {
        val target = File(base, "target.txt").apply { writeText("old") }
        val link = File(root, "docs/linked.txt")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        repository.writeText(link.path, "new")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("new", target.readText())
    }

    /** Fix (5e-8a): the preview read the whole file and failed on invalid UTF-8. */
    @Test fun readTextTruncatesLargeFilesAndReplacesMalformedBytes() {
        val big = File(root, "big.log").apply { writeText("a".repeat(5_000)) }
        val head = repository.readText(big.path, 1_000)
        assertTrue(head.truncated)
        assertEquals(1_000, head.text.length)
        val bad = File(root, "bad.txt").apply { writeBytes(byteArrayOf(0x61, 0xFF.toByte(), 0x62)) }
        val decoded = repository.readText(bad.path, 1_000)
        assertFalse(decoded.truncated)
        assertEquals("a�b", decoded.text)
    }
}

class WorkspaceResourcePathsTest {
    private val paths = WorkspaceResourcePaths("/data/app/workspace", "/data/app/workspace/.omnibot")

    @Test fun omnibotUrisResolveLikeTheResourceService() {
        assertEquals("/data/app/workspace/docs/a.md", paths.resolveUriToPath("omnibot://workspace/docs/a.md"))
        assertEquals("/data/app/workspace/.omnibot/attachments/x.png", paths.resolveUriToPath("omnibot://attachments/x.png"))
        assertEquals("/storage/emulated/0/a.txt", paths.resolveUriToPath("omnibot://public/emulated/0/a.txt"))
        assertEquals("/data/app/workspace/docs", paths.resolveUriToPath("omnibot:///workspace/docs"))
        assertEquals("/data/app/workspace/my file.md", paths.resolveUriToPath("omnibot://workspace/my%20file.md"))
        assertNull(paths.resolveUriToPath("omnibot://unknown/a"))
        assertNull(paths.resolveUriToPath("https://example.com"))
    }

    @Test fun parentSegmentsCannotEscapeTheRoot() {
        assertEquals("/data/app/workspace/etc", paths.resolveUriToPath("omnibot://workspace/../etc"))
    }

    @Test fun shellAndAndroidPathsRoundTrip() {
        assertEquals("/workspace/docs", paths.shellPathForAndroidPath("/data/app/workspace/docs"))
        assertEquals("/workspace", paths.shellPathForAndroidPath("/data/app/workspace"))
        assertEquals("/data/app/workspace/.omnibot/skills", paths.androidPathForShellPath("/workspace/.omnibot/skills"))
        assertEquals("/data/app/workspace/docs", paths.androidPathForShellPath("/workspace/docs"))
        assertEquals("/sdcard/Download", paths.androidPathForShellPath("/sdcard/Download"))
        assertNull(paths.shellPathForAndroidPath("/data/other"))
    }
}
