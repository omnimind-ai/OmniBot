package cn.com.omnimind.nativeui.workspace

import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.opensNativePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceBrowserModelsTest {
    private val root = "/data/user/0/app/workspace"

    @Test fun breadcrumbsWalkFromTheShellRootToTheTarget() {
        val crumbs = workspaceBreadcrumbs(root, "/workspace", "$root/docs/notes", targetIsFile = false)
        assertEquals(listOf("/workspace", "docs", "notes"), crumbs.map { it.label })
        assertEquals(listOf(false, false, true), crumbs.map { it.current })
        assertEquals("$root/docs", crumbs[1].path)
    }

    @Test fun breadcrumbsAtTheRootAreOneCurrentChip() {
        val crumbs = workspaceBreadcrumbs(root, "/workspace", "$root/", targetIsFile = false)
        assertEquals(listOf(WorkspaceBreadcrumb("/workspace", root, current = true)), crumbs)
    }

    @Test fun breadcrumbsOutsideTheRootAreASingleChip() {
        val crumbs = workspaceBreadcrumbs(root, "/workspace", "/storage/emulated/0", targetIsFile = false)
        assertEquals(1, crumbs.size)
        assertTrue(crumbs.single().current)
    }

    @Test fun selectingAFolderSelectsItsChildrenUntilOneIsExcluded() {
        val scope = root
        var selection = WorkspaceSelection().select("$root/docs")
        assertTrue(selection.isSelected("$root/docs/a.md", scope))
        selection = selection.deselect("$root/docs/a.md", scope)
        assertFalse(selection.isSelected("$root/docs/a.md", scope))
        assertTrue(selection.isSelected("$root/docs/b.md", scope))
        assertEquals(setOf("$root/docs/a.md"), selection.excluded)
        // Reselecting the child drops the exclusion instead of stacking directives.
        selection = selection.select("$root/docs/a.md")
        assertTrue(selection.isSelected("$root/docs/a.md", scope))
        assertTrue(selection.excluded.isEmpty())
    }

    @Test fun deselectingADirectlySelectedEntryLeavesNoDirective() {
        val selection = WorkspaceSelection().select("$root/a.txt").deselect("$root/a.txt", root)
        assertTrue(selection.isEmpty)
        assertTrue(selection.excluded.isEmpty())
    }

    @Test fun topLevelSelectionDropsPathsCoveredByASelectedFolder() {
        val selection = WorkspaceSelection(selected = setOf("$root/docs/a.md", "$root/docs", "$root/other"))
        assertEquals(listOf("$root/docs", "$root/other"), selection.topLevelSelected())
        // A sibling sharing a name prefix is not covered.
        assertEquals(2, WorkspaceSelection(setOf("$root/doc", "$root/docs")).topLevelSelected().size)
    }

    @Test fun entryNamesFollowTheDartRules() {
        assertEquals(EntryNameError.Empty, validateEntryName("  "))
        assertEquals(EntryNameError.Dot, validateEntryName(".."))
        assertEquals(EntryNameError.Slash, validateEntryName("a/b"))
        assertEquals(EntryNameError.Backslash, validateEntryName("a\\b"))
        assertEquals(EntryNameError.Illegal, validateEntryName("a\u0000"))
        assertNull(validateEntryName("report.md"))
    }

    @Test fun movesAreRefusedLikeTheDragAndDrop() {
        fun error(source: String, target: String, directory: Boolean = false, mount: Boolean = false, taken: Boolean = false) =
            workspaceMoveError(source, target, root, directory, mount, taken)
        assertEquals(MoveError.MountRoot, error("$root/host", "$root/docs", mount = true))
        assertEquals(MoveError.OutsideWorkspace, error("$root/a.txt", "/storage/emulated/0"))
        assertEquals(MoveError.IntoSelf, error("$root/docs", "$root/docs", directory = true))
        assertEquals(MoveError.AlreadyThere, error("$root/docs/a.txt", "$root/docs"))
        assertEquals(MoveError.IntoDescendant, error("$root/docs", "$root/docs/sub", directory = true))
        assertEquals(MoveError.NameTaken, error("$root/a.txt", "$root/docs", taken = true))
        assertNull(error("$root/a.txt", "$root/docs"))
    }

    @Test fun fileKindsAndMimeTypesMatchTheResourceService() {
        assertEquals(WorkspaceFileKind.Text, workspaceFileKind("/w/README.MD"))
        assertEquals("text/markdown", workspaceMimeType("/w/README.md"))
        assertEquals(WorkspaceFileKind.Code, workspaceFileKind("/w/a.json"))
        assertEquals(WorkspaceFileKind.Image, workspaceFileKind("/w/a.webp"))
        assertEquals(WorkspaceFileKind.Office, workspaceFileKind("/w/a.xlsx"))
        assertEquals(WorkspaceFileKind.Other, workspaceFileKind("/w/archive.zip"))
        assertEquals("text/plain", workspaceMimeType("/w/script.py"))
        assertEquals("application/octet-stream", workspaceMimeType("/w/archive.zip"))
        assertTrue(WorkspaceFileKind.Code.editable)
        assertFalse(WorkspaceFileKind.Html.editable)
    }

    @Test fun expandedFoldersFlattenWithDepthAndAnEmptyPlaceholder() {
        val docs = WorkspaceEntryUi("$root/docs", "docs", directory = true)
        val empty = WorkspaceEntryUi("$root/docs/empty", "empty", directory = true)
        val note = WorkspaceEntryUi("$root/docs/note.md", "note.md", directory = false)
        val rows = flattenWorkspaceRows(
            listOf(docs),
            children = mapOf(docs.path to listOf(empty, note), empty.path to emptyList()),
            expanded = setOf(docs.path, empty.path),
        )
        assertEquals(listOf(docs.path, empty.path, "${empty.path}/<empty>", note.path), rows.map { it.key })
        assertEquals(listOf(0, 1, 2, 1), rows.map { it.depth })
    }

    @Test fun foldersAtTheExpansionLimitAreNotExpandable() {
        val deep = WorkspaceEntryUi("$root/a/b/c", "c", directory = true)
        val row = flattenWorkspaceRows(listOf(deep), emptyMap(), setOf(deep.path), depth = WORKSPACE_INLINE_EXPANSION_DEPTH)
            .single() as WorkspaceRow.Entry
        assertFalse(row.expandable)
        assertFalse(row.expanded)
    }

    /**
     * Regression (5e-8a): the host handed every destination but ModelProviders
     * to Flutter, so a terminal focus or a shared draft opened the native page
     * and the Flutter page at once.
     */
    @Test fun destinationsNativeHomeOpensAreNotHandedToFlutter() {
        assertTrue(LegacyDestination.TerminalPackage("node").opensNativePage)
        assertTrue(LegacyDestination.SharedDraft("k").opensNativePage)
        assertTrue(LegacyDestination.Workspace().opensNativePage)
        assertTrue(LegacyDestination.WorkspaceFile("/w/a.md").opensNativePage)
        assertTrue(LegacyDestination.Page.ModelProviders.opensNativePage)
        assertFalse(LegacyDestination.Page.Account.opensNativePage)
        assertFalse(LegacyDestination.Conversation(1, "agent").opensNativePage)
    }
}
