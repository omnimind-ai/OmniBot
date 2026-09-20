package cn.com.omnimind.bot.plugin.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SandboxPluginAppLaunchSpecTest {
    @Test
    fun `plugin gets a stable standalone shortcut identity`() {
        val pluginId = "local.project.fitness-beast"

        assertEquals(
            "vibe-app:local.project.fitness-beast",
            SandboxPluginAppLaunchSpec.shortcutId(pluginId),
        )
        assertEquals(
            "omnibot://plugin-app/local.project.fitness-beast",
            SandboxPluginAppLaunchSpec.uri(pluginId),
        )
        assertEquals(
            pluginId,
            SandboxPluginAppLaunchSpec.pluginId(SandboxPluginAppLaunchSpec.uri(pluginId)),
        )
        assertEquals(
            SandboxPluginAppLaunchSpec.shortcutId(pluginId),
            SandboxPluginAppLaunchSpec.shortcutId(pluginId),
        )
    }

    @Test
    fun `different plugins cannot collide and foreign links are ignored`() {
        assertNotEquals(
            SandboxPluginAppLaunchSpec.shortcutId("local.project.fitness-beast"),
            SandboxPluginAppLaunchSpec.shortcutId("local.project.tiny-garden"),
        )
        assertNull(SandboxPluginAppLaunchSpec.pluginId("https://example.com/app"))
        assertNull(SandboxPluginAppLaunchSpec.pluginId("omnibot://plugin-app/not-a-plugin"))
    }
}
