package cn.com.omnimind.bot.agent

internal fun resolveToolExecutionStatus(result: ToolExecutionResult): String {
    return when (result) {
        is ToolExecutionResult.ChatMessage -> AgentConversationHistoryRepository.STATUS_INTERRUPTED

        // ACP represents a tool waiting for approval/input as pending. The
        // local history uses the existing running presentation state; no
        // second business lifecycle is persisted.
        is ToolExecutionResult.Clarify -> AgentConversationHistoryRepository.STATUS_RUNNING

        is ToolExecutionResult.PermissionRequired -> AgentConversationHistoryRepository.STATUS_INTERRUPTED

        is ToolExecutionResult.TerminalResult -> when {
            result.timedOut -> AgentConversationHistoryRepository.STATUS_TIMEOUT
            result.success -> AgentConversationHistoryRepository.STATUS_SUCCESS
            else -> AgentConversationHistoryRepository.STATUS_ERROR
        }

        is ToolExecutionResult.Interrupted -> AgentConversationHistoryRepository.STATUS_INTERRUPTED

        is ToolExecutionResult.ScheduleResult -> if (result.success) {
            AgentConversationHistoryRepository.STATUS_SUCCESS
        } else {
            AgentConversationHistoryRepository.STATUS_ERROR
        }

        is ToolExecutionResult.McpResult -> if (result.success) {
            AgentConversationHistoryRepository.STATUS_SUCCESS
        } else {
            AgentConversationHistoryRepository.STATUS_ERROR
        }

        is ToolExecutionResult.MemoryResult -> if (result.success) {
            AgentConversationHistoryRepository.STATUS_SUCCESS
        } else {
            AgentConversationHistoryRepository.STATUS_ERROR
        }

        is ToolExecutionResult.ContextResult -> if (result.success) {
            AgentConversationHistoryRepository.STATUS_SUCCESS
        } else {
            AgentConversationHistoryRepository.STATUS_ERROR
        }

        is ToolExecutionResult.Error -> AgentConversationHistoryRepository.STATUS_ERROR
    }
}
