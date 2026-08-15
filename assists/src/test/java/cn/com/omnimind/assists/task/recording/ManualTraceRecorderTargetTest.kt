package cn.com.omnimind.assists.task.recording

import cn.com.omnimind.assists.ManualInputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualTraceRecorderTargetTest {
    private val clicked = ManualInputTarget("clicked", 20f, 20f)

    @Test
    fun `clicked input target is offered`() {
        assertEquals(clicked, selectManualInputTargetAfterClick(clicked))
    }

    @Test
    fun `click outside input does not reuse stale focus after popup is closed`() {
        assertNull(selectManualInputTargetAfterClick(null))
    }
}
