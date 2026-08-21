package cn.com.omnimind.bot.ui.channel

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCornerRadiiPxTest {
    @Test
    fun `converts physical radii to logical pixels`() {
        val result = ScreenCornerRadiiPx(
            topLeft = 120,
            topRight = 108,
            bottomLeft = 96,
            bottomRight = 84,
        ).toLogicalPixels(density = 3f)

        assertEquals(40.0, result["topLeft"]!!, 0.001)
        assertEquals(36.0, result["topRight"]!!, 0.001)
        assertEquals(32.0, result["bottomLeft"]!!, 0.001)
        assertEquals(28.0, result["bottomRight"]!!, 0.001)
    }

    @Test
    fun `invalid values are normalized`() {
        val result = ScreenCornerRadiiPx(-1, -2, -3, -4)
            .toLogicalPixels(density = 0f)

        assertEquals(ScreenCornerRadiiPx.Zero.toLogicalPixels(1f), result)
    }
}
