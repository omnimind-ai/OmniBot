package com.rk.terminal.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChrootRootfsOwnerRepairTest {
    @Before
    fun setup() {
        ChrootRootfsOwnerRepair.statOwner = { -1L }
    }

    @After
    fun teardown() {
        ChrootRootfsOwnerRepair.statOwner = { -1L }
    }

    @Test
    fun ownerAlreadyAppUidDoesNotNeedRepair() {
        assertFalse(ChrootRootfsOwnerRepair.needsRepair(10482L, 10482))
    }

    @Test
    fun ownerDriftedToRootNeedsRepair() {
        assertTrue(ChrootRootfsOwnerRepair.needsRepair(0L, 10482))
    }

    @Test
    fun unknownOwnerDoesNotNeedRepair() {
        assertFalse(ChrootRootfsOwnerRepair.needsRepair(-1L, 10482))
    }

    @Test
    fun invalidAppUidNeverRepairs() {
        assertFalse(ChrootRootfsOwnerRepair.needsRepair(0L, -1))
    }

    @Test
    fun quoteWithSingleQuoteEscapes() {
        assertEquals("'a/'\\''b'", ChrootRootfsOwnerRepair.shellQuote("a/'b"))
    }

    @Test
    fun quotePlainPathUntouched() {
        assertEquals("'/data/root'", ChrootRootfsOwnerRepair.shellQuote("/data/root"))
    }
}
