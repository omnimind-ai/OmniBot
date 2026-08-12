package cn.com.omnimind.bot.activity

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityOrientationPolicyTest {
    @Test
    fun phonesRemainPortraitLocked() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            MainActivityOrientationPolicy.requestedOrientation(599),
        )
    }

    @Test
    fun largeScreensAllowResponsiveOrientation() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            MainActivityOrientationPolicy.requestedOrientation(600),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            MainActivityOrientationPolicy.requestedOrientation(720),
        )
    }
}
