package cn.com.omnimind.bot.agent.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of `ui/test/services/agent_diff_parser_test.dart` (non-widget tests). */
class AgentDiffParserTest {
    // Dart `'''\n...'''` drops the leading newline and keeps the trailing one.
    private val diffText = "diff --git a/lib/main.dart b/lib/main.dart\n" +
        "--- a/lib/main.dart\n" +
        "+++ b/lib/main.dart\n" +
        "@@ -1,2 +1,2 @@\n" +
        "-old line\n" +
        "+new line\n" +
        " same line\n"

    private val hunkOnlyDiff = "@@ -1,2 +1,2 @@\n" +
        "-old line\n" +
        "+new line\n" +
        " same line\n"

    @Test
    fun `parseAgentDiffText groups file hunks and counts changes`() {
        val summary = parseAgentDiffText(diffText)

        assertEquals(1, summary.files.size)
        assertEquals(1, summary.additions)
        assertEquals(1, summary.deletions)
        assertEquals("lib/main.dart", summary.primaryPath)
        assertTrue(summary.files.single().lines.any { it.kind == AgentDiffLineKind.add })
        assertTrue(summary.files.single().lines.any { it.kind == AgentDiffLineKind.remove })
        assertEquals("1 file · +1 -1", summarizeAgentDiff(summary))
    }

    @Test
    fun `extractAgentDiffText finds nested diff payloads`() {
        val extracted = extractAgentDiffText(
            jsonMapOf("result" to jsonMapOf("patch" to diffText)),
        )

        assertNotNull(extracted)
        assertTrue(extracted!!.contains("diff --git"))
    }

    @Test
    fun `extractAgentDiffText normalizes hunk-only change payloads`() {
        val extracted = extractAgentDiffText(
            jsonMapOf(
                "changes" to jsonMapOf(
                    "path" to "/repo/lib/main.dart",
                    "kind" to jsonMapOf("type" to "update"),
                    "diff" to hunkOnlyDiff,
                ),
            ),
        )

        assertNotNull(extracted)
        assertTrue(extracted!!.contains("diff --git"))
        assertTrue(extracted.contains("/repo/lib/main.dart"))

        val summary = parseAgentDiffText(extracted)
        assertEquals("/repo/lib/main.dart", summary.primaryPath)
        assertEquals(1, summary.additions)
        assertEquals(1, summary.deletions)
    }

    /**
     * The Dart test builds the payload via `AgentToolEventData.fromMap` and
     * spreads `event.raw` plus the normalized json fields. The parser only
     * reaches the diff through the raw `changes` JSON string, so the payload
     * is reproduced directly here.
     */
    @Test
    fun `extractAgentDiffText reads hunk-only changes from raw tool events`() {
        val changes = DartJson.encode(
            jsonMapOf(
                "path" to "/repo/ui/test/services/agent_diff_parser_test.dart",
                "kind" to jsonMapOf("type" to "update", "move_path" to null),
                "diff" to hunkOnlyDiff,
            ),
        )
        val extracted = extractAgentDiffText(
            jsonMapOf(
                "toolName" to "codex.file",
                "toolType" to "builtin",
                "type" to "fileChange",
                "changes" to changes,
                "status" to "completed",
                "argsJson" to "",
                "rawResultJson" to "",
                "resultPreviewJson" to "",
            ),
        )

        assertNotNull(extracted)
        val summary = parseAgentDiffText(extracted!!)
        assertEquals(
            "/repo/ui/test/services/agent_diff_parser_test.dart",
            summary.primaryPath,
        )
        assertEquals(1, summary.additions)
        assertEquals(1, summary.deletions)
    }

    // Extra cases beyond the Dart suite.

    @Test
    fun `buildAgentUnifiedDiffFromStrings diffs old and new strings`() {
        val extracted = extractAgentDiffText(
            jsonMapOf("oldString" to "a\nb\nc", "newString" to "a\nB\nc", "filePath" to "x.txt"),
        )
        assertEquals(
            "--- a/x.txt\n+++ b/x.txt\n@@ -1,3 +1,3 @@\n a\n-b\n+B\n c",
            extracted,
        )
        val summary = parseAgentDiffText(extracted!!)
        assertEquals("x.txt", summary.primaryPath)
        val removed = summary.files.single().lines.first { it.kind == AgentDiffLineKind.remove }
        assertEquals(2, removed.oldLineNumber)
        assertTrue(summary.hasChanges)
    }

    @Test
    fun `hunk-only add change uses dev null old header and new file flag`() {
        val diff = buildAgentUnifiedDiffFromPatch(
            diffText = "@@ -0,0 +1 @@\n+hi",
            newPath = "new.txt",
            changeKind = "Added",
        )
        assertEquals(
            "diff --git a/new.txt b/new.txt\n--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1 @@\n+hi",
            diff,
        )
        val file = parseAgentDiffText(diff).files.single()
        assertTrue(file.isNewFile)
        assertFalse(file.isDeletedFile)
        assertEquals(1, file.lines.last().newLineNumber)
    }

    @Test
    fun `extractAgentDiffPath and stat formatting`() {
        assertEquals(
            "src/a.kt",
            extractAgentDiffPath(jsonMapOf("args" to DartJson.encode(jsonMapOf("file_path" to "src/a.kt")))),
        )
        assertEquals("README.md", extractAgentDiffPath(listOf("not a path", "README.md")))
        assertNull(extractAgentDiffPath("hello world"))
        assertEquals("+1.5k -2.0m", formatAgentDiffStat(additions = 1500, deletions = 2_000_000))
        assertEquals("+1.3k -999", formatAgentDiffStat(additions = 1250, deletions = 999))
        assertEquals("", summarizeAgentDiff(parseAgentDiffText("  \n")))
        assertFalse(looksLikeAgentDiff("--- a\n+++ b"))
    }
}
