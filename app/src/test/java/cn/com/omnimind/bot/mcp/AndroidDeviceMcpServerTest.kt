package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AndroidDeviceMcpServerTest {
    @Test
    fun `public MCP surface exposes official user-level device tools`() {
        assertEquals(
            linkedSetOf(
                "run_gui",
                "run_function",
                "list_functions",
                "register_function",
                "context_apps_query",
                "file_transfer",
            ),
            AndroidDeviceMcpServer.publicToolNames,
        )
        assertFalse(AndroidDeviceMcpServer.publicToolNames.any { it.startsWith("device_") })
    }

    @Test
    fun `missing default plugin is installed before tool call`() = runBlocking {
        var enabled = false
        var installCount = 0
        var enableCount = 0

        AndroidDeviceMcpServer.ensureDefaultPluginEnabled(
            isEnabled = { enabled },
            inspect = { null },
            install = {
                installCount += 1
                enabled = true
            },
            enable = { enableCount += 1 },
        )

        assertTrue(enabled)
        assertEquals(1, installCount)
        assertEquals(0, enableCount)
    }

    @Test
    fun `disabled installed default plugin is formally enabled`() = runBlocking {
        var enabled = false
        var installCount = 0
        var enableCount = 0

        AndroidDeviceMcpServer.ensureDefaultPluginEnabled(
            isEnabled = { enabled },
            inspect = {
                AndroidDeviceMcpServer.DefaultPluginStatus(
                    installed = true,
                    enabled = false,
                )
            },
            install = { installCount += 1 },
            enable = {
                enableCount += 1
                enabled = true
            },
        )

        assertTrue(enabled)
        assertEquals(0, installCount)
        assertEquals(1, enableCount)
    }

    @Test
    fun `ready runtime skips plugin restoration`() = runBlocking {
        var inspectionCount = 0

        AndroidDeviceMcpServer.ensureDefaultPluginEnabled(
            isEnabled = { true },
            inspect = {
                inspectionCount += 1
                null
            },
            install = { error("install must not run") },
            enable = { error("enable must not run") },
        )

        assertEquals(0, inspectionCount)
    }
}
