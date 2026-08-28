package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AndroidDeviceMcpServerTest {
    @Test
    fun `public MCP surface exposes Android device tools only`() {
        assertTrue(
            AndroidDeviceMcpServer.publicToolNames.containsAll(
                setOf(
                    "run_gui",
                    "run_function",
                    "list_functions",
                    "register_function",
                    "context_apps_query",
                    "file_transfer",
                    "schedule_task_create",
                    "schedule_task_list",
                    "schedule_task_update",
                    "schedule_task_delete",
                    "alarm_reminder_create",
                    "alarm_reminder_list",
                    "alarm_reminder_delete",
                ),
            ),
        )
        assertFalse(AndroidDeviceMcpServer.publicToolNames.any { it in setOf(
            "context_time_now",
            "browser_use",
            "file_read",
            "file_write",
            "file_edit",
            "file_list",
            "file_search",
            "file_stat",
            "file_move",
            "skills_list",
            "skills_read",
            "calendar_list",
            "calendar_event_create",
            "calendar_event_list",
            "calendar_event_update",
            "calendar_event_delete",
            "music_playback_control",
            "memory_search",
            "memory_write_daily",
            "memory_upsert_longterm",
            "memory_rollup_day",
            "memory_load",
            "subagent_dispatch",
        ) })
        assertFalse(AndroidDeviceMcpServer.publicToolNames.any { it.startsWith("device_") })
        assertTrue(AndroidDeviceMcpServer.MCP_INSTRUCTIONS.contains("Android device"))
        assertFalse(AndroidDeviceMcpServer.MCP_INSTRUCTIONS.contains("browser/internet"))
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
