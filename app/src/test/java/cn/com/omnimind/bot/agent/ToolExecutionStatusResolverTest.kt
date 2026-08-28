package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolExecutionStatusResolverTest {
    @Test
    fun `terminal timeout resolves to timeout status`() {
        val result = ToolExecutionResult.TerminalResult(
            toolName = "terminal_execute",
            summaryText = "终端命令等待超时",
            previewJson = """{"timedOut":true}""",
            rawResultJson = """{"timedOut":true}""",
            success = false,
            timedOut = true,
            terminalOutput = "partial output",
            terminalStreamState = "running"
        )

        assertEquals(
            AgentConversationHistoryRepository.STATUS_TIMEOUT,
            resolveToolExecutionStatus(result)
        )
    }

    @Test
    fun `clarification stays in the standard pending lifecycle`() {
        assertEquals(
            AgentConversationHistoryRepository.STATUS_RUNNING,
            resolveToolExecutionStatus(
                ToolExecutionResult.Clarify(
                    question = "确认执行高权限命令？",
                    missingFields = listOf("arguments.confirmed")
                )
            )
        )
    }

    @Test
    fun `permission required resolves to interrupted status`() {
        assertEquals(
            AgentConversationHistoryRepository.STATUS_INTERRUPTED,
            resolveToolExecutionStatus(
                ToolExecutionResult.PermissionRequired(listOf("悬浮窗权限"))
            )
        )
    }

    @Test
    fun `manual interruption resolves to interrupted status`() {
        assertEquals(
            AgentConversationHistoryRepository.STATUS_INTERRUPTED,
            resolveToolExecutionStatus(
                ToolExecutionResult.Interrupted(
                    toolName = "terminal_execute",
                    summaryText = "工具调用已被用户手动停止"
                )
            )
        )
    }
}
