package cn.com.omnimind.bot.plugin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmPluginBoundaryTest {
    @Test
    fun `build profiles keep opt in OmniFlow lifecycle in plugin host`() {
        val provider = projectSource(
            "app/src/main/java/cn/com/omnimind/bot/plugin/official/OmniVlmLiteProvider.kt",
        )
        val catalog = projectSource("plugins/catalog.v1.json")
        val vlmHandler = projectSource(
            "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/VlmToolHandler.kt",
        )
        val host = projectSource(
            "app/src/main/java/cn/com/omnimind/bot/plugin/OmniPluginHost.kt",
        )
        val appBuild = projectSource("app/build.gradle.kts")
        val mcpServerManager = projectSource(
            "app/src/main/java/cn/com/omnimind/bot/mcp/McpServerManager.kt",
        )
        val terminalBuild = projectSource("ReTerminal/core/main/build.gradle.kts")

        assertTrue(provider.contains("RuntimeBundleAdapter"))
        assertTrue(provider.contains("runtimeProvider.install(appContext, platform)"))
        assertTrue(provider.contains("OmniFlow.shutdown()"))
        assertTrue(provider.contains("RuntimeBundlePrepareMode.UPDATE"))
        assertFalse(provider.contains("OmniFlow.prepareAndStart(appContext)"))
        assertFalse(provider.contains("RuntimeBundlePrepareMode.INSTALL -> Unit"))
        assertTrue(provider.contains("OmniFlowPluginRuntime.enable(appContext)"))
        assertTrue(provider.contains("MCP is started lazily"))
        assertFalse(provider.contains("McpServerManager.setEnabled(appContext, true)"))
        assertFalse(provider.contains("finally"))
        assertFalse(provider.contains("vlm_task"))
        assertFalse(provider.contains("VlmToolHandler"))
        assertTrue(vlmHandler.contains("OmniVlmPlugin.execute"))
        assertFalse(vlmHandler.contains("OmniFlowRuntimeProvider"))
        assertTrue(catalog.contains("\"name\": \"OmniFlow\""))
        assertFalse(catalog.contains("\"name\": \"Android GUI\""))
        assertFalse(host.contains("DEFAULT_INSTALL_GUI_PLUGIN"))
        assertFalse(host.contains("DEFAULT_INSTALL_ALL_PLUGINS"))
        assertTrue(host.contains("defaultEnabledPluginIds = setOf(OmniVlmLiteProvider.ID)"))
        assertTrue(catalog.contains("\"required\": true"))
        assertTrue(catalog.contains("\"installByDefault\": true"))
        assertTrue(appBuild.contains("prop(\"OMNIBOT_PROFILE\").ifBlank { \"main\" }"))
        assertTrue(appBuild.contains("omnibotProfile == \"investor\""))
        assertTrue(
            appBuild.contains(
                "exclude(\"omni-vlm-lite/**\", \"vibe-project/**\", \"omnilink-agent/**\")",
            ),
        )
        assertFalse(appBuild.contains("DEFAULT_INSTALL_GUI_PLUGIN"))
        assertFalse(appBuild.contains("DEFAULT_INSTALL_ALL_PLUGINS"))
        assertTrue(appBuild.contains("ALLOW_PACKAGED_PLUGIN_FALLBACK\", \"true\""))
        assertTrue(appBuild.contains("omniFlowPackagedArchivePath"))
        assertTrue(appBuild.contains("File(omniFlowPackagedArchivePath).name"))
        assertFalse(Regex("omniflow-gui-runtime-\\d+\\.\\d+\\.\\d+\\.zip").containsMatchIn(appBuild))
        assertTrue(appBuild.contains("into(\"runtime-components\")"))
        assertTrue(appBuild.contains("omnibotProfile in profiles"))
        assertTrue(mcpServerManager.indexOf("json(McpJson)") < mcpServerManager.indexOf("gson()"))
        assertTrue(terminalBuild.contains("TERMINAL_RUNTIME_MANIFEST_URL"))
        assertTrue(terminalBuild.contains("alpineMiniRootfsUrl"))
        assertTrue(terminalBuild.contains("alpine.tar.gz"))
        assertTrue(terminalBuild.contains("root.deleteRecursively()"))
        assertFalse(
            Regex(
                "\\\"id\\\": \\\"com\\.omnimind\\.omni-vlm-lite\\\"[\\s\\S]*?" +
                    "\\\"profiles\\\": \\[\\\"investor\\\"\\]",
            ).containsMatchIn(catalog.substringBefore("com.omnimind.vibe-project-builder")),
        )
    }

    private fun projectSource(path: String): String {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Could not locate project root")
        }
        return current.resolve(path).readText()
    }
}
