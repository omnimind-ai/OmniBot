package cn.com.omnimind.bot.activity

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.content.pm.PackageManager.DONT_KILL_APP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class AppIconManagerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    @Test
    fun `only the selected launcher is exposed and widget intents follow it`() {
        assertLauncher(AppIconVariant.SPARKLE)
        for (variant in AppIconVariant.entries.drop(1) + AppIconVariant.SPARKLE) {
            assertEquals(variant, AppIconManager.applyVariant(context, variant))
            assertEquals(variant.storageValue, prefs.getString(AppIconManager.KEY_APP_ICON_VARIANT, null))
            assertLauncher(variant)
        }
    }

    @Test
    fun `the actual launcher takes precedence over a stale preference`() {
        AppIconManager.applyVariant(context, AppIconVariant.DOT)
        prefs.edit().putString(AppIconManager.KEY_APP_ICON_VARIANT, "classic").apply()

        assertLauncher(AppIconVariant.DOT)
    }

    @Test
    @Config(sdk = [29])
    fun `Android 10 enables the new component first and reapplying it is idempotent`() {
        val pm = mock(PackageManager::class.java)
        val host = contextWith(pm)
        val sparkle = component(AppIconVariant.SPARKLE)
        val dot = component(AppIconVariant.DOT)

        AppIconManager.applyVariant(host, AppIconVariant.DOT)

        inOrder(pm).apply {
            verify(pm).setComponentEnabledSetting(dot, COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP)
            verify(pm).setComponentEnabledSetting(sparkle, COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP)
        }
        `when`(pm.getComponentEnabledSetting(dot)).thenReturn(COMPONENT_ENABLED_STATE_ENABLED)
        `when`(pm.getComponentEnabledSetting(sparkle)).thenReturn(COMPONENT_ENABLED_STATE_DISABLED)

        AppIconManager.applyVariant(host, AppIconVariant.DOT)

        verify(pm, times(1)).setComponentEnabledSetting(dot, COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP)
        verify(pm, times(1)).setComponentEnabledSetting(sparkle, COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP)
    }

    @Test
    @Config(sdk = [29])
    fun `Android 10 restores the previous components on a failed switch`() {
        val pm = mock(PackageManager::class.java)
        val host = contextWith(pm)
        val sparkle = component(AppIconVariant.SPARKLE)
        val dot = component(AppIconVariant.DOT)
        doThrow(IllegalArgumentException("Component unavailable")).`when`(pm)
            .setComponentEnabledSetting(sparkle, COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP)

        assertThrows(IllegalArgumentException::class.java) {
            AppIconManager.applyVariant(host, AppIconVariant.DOT)
        }

        inOrder(pm).apply {
            verify(pm).setComponentEnabledSetting(dot, COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP)
            verify(pm).setComponentEnabledSetting(sparkle, COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP)
            verify(pm).setComponentEnabledSetting(dot, COMPONENT_ENABLED_STATE_DEFAULT, DONT_KILL_APP)
        }
        assertNull(prefs.getString(AppIconManager.KEY_APP_ICON_VARIANT, null))
    }

    private fun assertLauncher(variant: AppIconVariant) {
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val launchers = context.packageManager.queryIntentActivities(launcherIntent, 0)
        assertEquals(listOf(variant.launcherActivity.name), launchers.map { it.activityInfo.name })
        assertEquals(variant, AppIconManager.getActiveVariant(context))
        assertEquals(component(variant), AppIconManager.createLauncherIntent(context).component)
    }

    private fun component(variant: AppIconVariant): ComponentName = ComponentName(context, variant.launcherActivity)

    private fun contextWith(pm: PackageManager): Context = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getPackageManager(): PackageManager = pm
    }
}
