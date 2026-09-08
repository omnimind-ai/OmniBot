package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarListLimitTest {

    @Test
    fun `omitted calendar limit leaves the result unbounded`() {
        assertNull(resolveCalendarListLimit(null))
    }

    @Test
    fun `an explicit calendar limit is honored without an application ceiling`() {
        assertEquals(201, resolveCalendarListLimit(201))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nonpositive calendar limit is invalid input`() {
        resolveCalendarListLimit(0)
    }
}
