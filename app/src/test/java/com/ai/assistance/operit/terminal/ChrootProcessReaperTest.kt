package com.ai.assistance.operit.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ChrootProcessReaperTest {
    @Test
    fun sanitizeReplacesPathSeparators() {
        assertEquals("sess_a_b", ChrootProcessReaper.sanitizeExecutorKey("sess/a/b"))
    }

    @Test
    fun readPgidRejectsEmptyAndZero() {
        assertNull(ChrootProcessReaper.readPgid(""))
        assertNull(ChrootProcessReaper.readPgid("0"))
        assertNull(ChrootProcessReaper.readPgid("abc"))
        assertEquals(4242, ChrootProcessReaper.readPgid(" 4242\n"))
    }

    @Test
    fun readPgidFromFileRetriesUntilNumberAppears() {
        val dir = createTempDirectory("chroot-reaper").toFile()
        val file = File(dir, "chroot-key.pid")
        file.writeText("")
        var sleeps = 0
        val pgid = ChrootProcessReaper.readPgidFromFile(
            file = file,
            attempts = 4,
            delayMs = 1L,
            sleeper = {
                sleeps += 1
                if (sleeps == 2) file.writeText("918\n")
            }
        )
        assertEquals(918, pgid)
        assertEquals(2, sleeps)
        dir.deleteRecursively()
    }

    @Test
    fun readPgidFromFileGivesUpOnEmptyFile() {
        val dir = createTempDirectory("chroot-reaper-empty").toFile()
        val file = File(dir, "chroot-key.pid")
        file.writeText("")
        val pgid = ChrootProcessReaper.readPgidFromFile(
            file = file,
            attempts = 3,
            delayMs = 1L,
            sleeper = {}
        )
        assertNull(pgid)
        dir.deleteRecursively()
    }
}
