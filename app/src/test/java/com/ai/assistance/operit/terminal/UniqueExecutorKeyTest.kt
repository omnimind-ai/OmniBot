package com.ai.assistance.operit.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UniqueExecutorKeyTest {
    @Test
    fun appendsNonceToBaseKey() {
        assertEquals("embedded-x-42", uniqueExecutorKey("embedded-x", 42L))
    }

    @Test
    fun differentNoncesProduceDifferentKeys() {
        // 并发同命令共享 executor key 会互相清空 pid 文件（真机 14:56 抓到的竞态）；
        // 每次启动必须得到不同 key
        assertNotEquals(uniqueExecutorKey("embedded-1", 1L), uniqueExecutorKey("embedded-1", 2L))
    }

    @Test
    fun keyRemainsPidFileSafeAfterSanitize() {
        val key = uniqueExecutorKey("embedded/1", 7L)
        assertEquals(key.replace('/', '_'), ChrootProcessReaper.sanitizeExecutorKey(key))
    }
}
