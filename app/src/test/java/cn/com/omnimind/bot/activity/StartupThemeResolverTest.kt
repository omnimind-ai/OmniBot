package cn.com.omnimind.bot.activity

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.util.TypedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class StartupThemeResolverTest {
    @Test
    fun `manifest cold start and saved Flutter theme resolve all six splash assets`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val day = context.withNightMode(Configuration.UI_MODE_NIGHT_NO)
        val night = context.withNightMode(Configuration.UI_MODE_NIGHT_YES)

        for (variant in AppIconVariant.entries) {
            val component = ComponentName(context, variant.launcherActivity)
            val activity = context.packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
            assertTrue(activity.exported)
            assertEquals(variant.defaultEnabledInManifest, activity.enabled)
            assertSplash(day.resources, activity.theme, variant, dark = false)
            assertSplash(night.resources, activity.theme, variant, dark = true)

            AppIconManager.applyVariant(context, variant)
            prefs.edit().putString("flutter.theme_option", "system").apply()
            assertSplash(day.resources, StartupThemeResolver.resolveSplashTheme(day), variant, dark = false)
            assertSplash(night.resources, StartupThemeResolver.resolveSplashTheme(night), variant, dark = true)
            prefs.edit().putString("flutter.theme_option", "light").apply()
            assertSplash(night.resources, StartupThemeResolver.resolveSplashTheme(night), variant, dark = false)
            prefs.edit().putString("flutter.theme_option", "dark").apply()
            assertSplash(day.resources, StartupThemeResolver.resolveSplashTheme(day), variant, dark = true)
        }
    }

    private fun Context.withNightMode(nightMode: Int): Context {
        val config = Configuration(resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        return createConfigurationContext(config)
    }

    private fun assertSplash(resources: Resources, themeId: Int, variant: AppIconVariant, dark: Boolean) {
        val variantSuffix = if (variant == AppIconVariant.SPARKLE) "" else "_${variant.storageValue}"
        val themeSuffix = if (dark) "_dark" else ""
        val theme = resources.newTheme().apply { applyStyle(themeId, true) }
        val value = TypedValue()
        assertTrue(theme.resolveAttribute(android.R.attr.windowBackground, value, true))
        assertEquals("splash_background$variantSuffix$themeSuffix", resources.getResourceEntryName(value.resourceId))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(theme.resolveAttribute(android.R.attr.windowSplashScreenAnimatedIcon, value, true))
            assertEquals("splash_logo${variantSuffix}_animated$themeSuffix", resources.getResourceEntryName(value.resourceId))
        }
    }
}
