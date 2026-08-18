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
    fun `missing default plugin explains how to enable phone control`() = runBlocking {
        var message = ""
        try {
            AndroidDeviceMcpServer.requireDefaultPluginEnabled(
                isEnabled = { false },
                inspect = { null },
            )
        } catch (error: IllegalStateException) {
            message = error.message.orEmpty()
        }

        assertTrue(message.contains("插件市场"))
        assertTrue(message.contains("安装并启用"))
    }

    @Test
    fun `disabled installed default plugin explains how to enable it`() = runBlocking {
        var message = ""
        try {
            AndroidDeviceMcpServer.requireDefaultPluginEnabled(
                isEnabled = { false },
                inspect = {
                    AndroidDeviceMcpServer.DefaultPluginStatus(
                        installed = true,
                        enabled = false,
                    )
                },
            )
        } catch (error: IllegalStateException) {
            message = error.message.orEmpty()
        }

        assertTrue(message.contains("启用插件"))
        assertTrue(message.contains("无障碍服务"))
    }

    @Test
    fun `ready runtime skips plugin inspection`() = runBlocking {
        var inspectionCount = 0

        AndroidDeviceMcpServer.requireDefaultPluginEnabled(
            isEnabled = { true },
            inspect = {
                inspectionCount += 1
                null
            },
        )

        assertEquals(0, inspectionCount)
    }
}
