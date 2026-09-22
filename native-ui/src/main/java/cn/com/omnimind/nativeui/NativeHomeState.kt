package cn.com.omnimind.nativeui

import androidx.compose.runtime.Immutable

/** Presentation data only. Conversation/ACP lifecycle remains owned by the host runtime. */
@Immutable
data class NativeHomeState(
    val conversations: List<ConversationSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val theme: ThemePreference = ThemePreference.System,
    val predictiveBack: Boolean = true,
    val localServiceEnabled: Boolean = false,
    val localServiceBusy: Boolean = false,
    val workspaceMemoryConfigured: Boolean = false,
    val greetingEnabled: Boolean = true,
    val quickPrompts: List<QuickPrompt> = emptyList(),
)

@Immutable
data class ConversationSummary(
    val id: Long,
    val title: String,
    val preview: String,
    val mode: String,
    val updatedAt: Long,
    val pinned: Boolean,
    val parentId: Long? = null,
)

@Immutable
data class QuickPrompt(val id: String, val title: String, val prompt: String)

enum class ThemePreference { System, Light, Dark }

/** Typed hand-off to pages whose existing implementation still owns their behavior. */
sealed interface LegacyDestination {
    data class Conversation(val id: Long, val mode: String) : LegacyDestination
    data class NewConversation(val draft: String = "") : LegacyDestination

    enum class Page : LegacyDestination {
        Account, ModelProviders, SceneModels, WorkspaceMemory, Agents, Terminal,
        LocalService, McpTools, Appearance, Miscellaneous, Permissions, Storage, About,
        Archive, Memory, Plugins, Skills, ExecutionHistory, ScheduledTasks, Workspace,
    }
}

/** Stable ordering independent of database iteration order. No writes to conversation history. */
fun visibleConversations(
    conversations: List<ConversationSummary>,
    query: String,
): List<ConversationSummary> {
    val search = query.trim()
    return conversations.asSequence()
        .filter { search.isNotEmpty() || it.parentId == null }
        .filter {
            search.isEmpty() || it.title.contains(search, ignoreCase = true) ||
                it.preview.contains(search, ignoreCase = true)
        }
        .sortedWith(compareByDescending<ConversationSummary> { it.pinned }
            .thenByDescending { it.updatedAt }.thenByDescending { it.id })
        .toList()
}
