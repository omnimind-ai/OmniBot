package cn.com.omnimind.bot.ui.chat

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.bot.agent.projection.ChatConversationRuntimeCoordinator
import cn.com.omnimind.bot.agent.projection.ChatMessage
import cn.com.omnimind.bot.agent.projection.ChatRuntimeHost
import cn.com.omnimind.bot.agent.projection.ChatRuntimeSnapshot
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.nativeui.chat.AgentToolCardUi
import cn.com.omnimind.nativeui.chat.AgentToolActionUi
import cn.com.omnimind.nativeui.chat.ChatMessageUi
import cn.com.omnimind.nativeui.chat.ChatTranscriptScreen
import cn.com.omnimind.nativeui.chat.ChatTranscriptState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read-only native transcript (batch 5c preview). Mirrors the live runtime
 * snapshot of [conversationId] when the native coordinator holds one, and
 * falls back to stored history otherwise. It never writes to the runtime:
 * the Flutter chat page stays the only interactive surface until 5e.
 */
internal class NativeChatTranscriptViewModel(
    context: Context,
    private val conversationId: Long,
    private val mode: String,
    title: String,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val conversations by lazy { ConversationDomainService(appContext) }
    private val mutableState = MutableStateFlow(ChatTranscriptState(title = title))
    val state = mutableState.asStateFlow()

    private val coordinator: ChatConversationRuntimeCoordinator
    private val listener = ChatConversationRuntimeCoordinator.Listener { snapshots, _ ->
        snapshots.filter { it.conversationId.toLong() == conversationId }
            .maxByOrNull { it.revision }
            ?.let(::applySnapshot)
    }
    private var liveRevision = 0L
    private val toolCards = ToolCardCache()
    private var loaded = false

    init {
        ChatRuntimeHost.initialize(appContext)
        coordinator = ChatRuntimeHost.coordinator
        coordinator.addListener(listener)
    }

    fun load() {
        if (loaded) return
        loaded = true
        val live = coordinator.allSnapshots()
            .filter { it.conversationId.toLong() == conversationId && it.messages.isNotEmpty() }
            .maxByOrNull { it.revision }
        if (live != null) {
            applySnapshot(live)
            return
        }
        viewModelScope.launch {
            val history = runCatching {
                withContext(Dispatchers.IO) {
                    val page = conversations.listConversationMessagesPaged(conversationId, mode, HISTORY_LIMIT, 0)
                    val agentId = conversations.getConversationPayload(conversationId)?.get("agentId")?.toString()
                    @Suppress("UNCHECKED_CAST")
                    val rows = (page["messages"] as? List<Map<String, Any?>>).orEmpty()
                    rows.mapNotNull { row -> runCatching { ChatMessage.fromJson(row).toUi(toolCards) }.getOrNull() } to agentId
                }
            }.onFailure { Log.w(TAG, "读取对话历史失败: ${it.message}") }.getOrNull()
            // A live snapshot that arrived while history loaded wins.
            if (liveRevision > 0L) return@launch
            mutableState.update {
                it.copy(
                    messages = history?.first.orEmpty(),
                    conversationAgentId = history?.second?.ifBlank { null },
                    isLive = false,
                    loading = false,
                )
            }
        }
    }

    private fun applySnapshot(snapshot: ChatRuntimeSnapshot) {
        if (snapshot.revision <= liveRevision) return
        liveRevision = snapshot.revision
        mutableState.update {
            it.copy(
                messages = snapshot.messages.map { message -> message.toUi(toolCards) },
                activeTaskIds = snapshot.activeAgentTurnIds,
                conversationAgentId = snapshot.conversation?.get("agentId")?.toString()?.ifBlank { null },
                isLive = true,
                loading = false,
            )
        }
    }

    override fun onCleared() {
        coordinator.removeListener(listener)
    }

    class Factory(
        context: Context,
        private val conversationId: Long,
        private val mode: String,
        private val title: String,
    ) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativeChatTranscriptViewModel(appContext, conversationId, mode, title) as T
    }

    private companion object {
        const val TAG = "NativeChatTranscript"
        const val HISTORY_LIMIT = 200
    }
}

/**
 * Presented tool cards by message id. Snapshots reuse unchanged message
 * content maps, so a card is only re-derived when its content changed.
 */
internal class ToolCardCache {
    private val entries = HashMap<String, Pair<Map<String, Any?>, AgentToolCardUi>>()

    @Synchronized
    fun present(message: ChatMessage): AgentToolCardUi? {
        val content = message.content ?: return null
        val cardData = message.cardData ?: return null
        if (message.type != 2 || cardData["type"]?.toString() != "agent_tool_summary") return null
        entries[message.id]?.let { (cachedContent, card) -> if (cachedContent === content) return card }
        val english = runCatching { AppLocaleManager.isEnglish() }.getOrDefault(false)
        val card = runCatching { presentAgentToolCard(cardData, english) }
            .onFailure { Log.w("NativeChatTranscript", "工具卡片投影失败: ${it.message}") }
            .getOrNull() ?: return null
        entries[message.id] = content to card
        return card
    }
}

internal fun ChatMessage.toUi(toolCards: ToolCardCache? = null) = ChatMessageUi(
    id = id,
    type = type,
    user = user,
    content = content,
    isLoading = isLoading,
    isError = isError,
    isSummarizing = isSummarizing,
    streamMeta = streamMeta,
    turnUsage = turnUsage,
    reasoningContent = reasoningContent,
    createAtMillis = createAtMillis,
    toolCard = toolCards?.present(this),
)

@Composable
internal fun NativeChatTranscriptRoute(
    viewModel: NativeChatTranscriptViewModel,
    onOpenLink: (String) -> Unit,
    onToolAction: (AgentToolActionUi) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    ChatTranscriptScreen(state, onBack, onOpenLink, onToolAction)
}
