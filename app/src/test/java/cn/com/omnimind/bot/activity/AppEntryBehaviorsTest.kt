package cn.com.omnimind.bot.activity

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AppEntryBehaviorsTest {
    /**
     * Fix (5e-7e): native Home never applied the responsive orientation, so a
     * phone rotated it to landscape while the Flutter entry stayed portrait.
     */
    @Test fun phonesStayPortraitAndTabletsRotate() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppEntryBehaviors.orientationFor(411))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, AppEntryBehaviors.orientationFor(599))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, AppEntryBehaviors.orientationFor(600))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, AppEntryBehaviors.orientationFor(800))
    }
}
