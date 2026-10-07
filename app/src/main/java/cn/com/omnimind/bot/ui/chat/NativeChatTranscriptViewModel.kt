package cn.com.omnimind.bot.ui.chat

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import android.net.Uri
import android.provider.OpenableColumns
import cn.com.omnimind.bot.agent.projection.AgentCommandPreferences
import cn.com.omnimind.bot.agent.projection.AgentPermissionMode
import cn.com.omnimind.bot.agent.projection.ChatConversationRuntimeCoordinator
import cn.com.omnimind.bot.agent.projection.ChatTurnIds
import cn.com.omnimind.bot.agent.projection.ChatTurnOutcome
import cn.com.omnimind.bot.agent.projection.ChatTurnRequest
import cn.com.omnimind.bot.agent.projection.agentModelSourceKey
import cn.com.omnimind.bot.agent.projection.ChatMessage
import cn.com.omnimind.bot.agent.projection.ChatRuntimeHost
import cn.com.omnimind.bot.agent.projection.ChatRuntimeSnapshot
import cn.com.omnimind.bot.agent.projection.isAgentRequestCardType
import cn.com.omnimind.bot.ui.settings.loadAgentAvatarPreview
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.nativeui.chat.AgentRequestCardUi
import cn.com.omnimind.nativeui.chat.AgentToolCardUi
import cn.com.omnimind.nativeui.chat.DeepThinkingCardUi
import cn.com.omnimind.nativeui.chat.AgentToolActionUi
import cn.com.omnimind.nativeui.chat.ChatMessageUi
import cn.com.omnimind.nativeui.chat.ChatComposerActions
import cn.com.omnimind.nativeui.chat.ChatComposerAttachment
import cn.com.omnimind.nativeui.chat.ChatComposerPermission
import cn.com.omnimind.nativeui.chat.ChatComposerState
import cn.com.omnimind.nativeui.chat.ChatTranscriptActions
import cn.com.omnimind.nativeui.chat.contextUsageRing
import com.rk.libcommons.OmnibotTerminalEnvironment
import cn.com.omnimind.nativeui.chat.ChatTranscriptScreen
import cn.com.omnimind.nativeui.chat.ChatTranscriptState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native chat page for one conversation (batch 5c transcript, 5d-1b
 * composer). Mirrors the live runtime snapshot of [conversationId] when the
 * native coordinator holds one, and falls back to stored history otherwise.
 *
 * Writes go through the native entries only: approvals and stop through the
 * 5b [ChatRuntimeHost.dispatcher], prompts through the 5d-0 launcher
 * ([ChatRuntimeHost.launchTurnDetached]) with settings read natively
 * ([AgentCommandPreferences]). Sending seeds the runtime with the complete
 * stored history first, so the admission snapshot can never replace the
 * conversation with the preview's partial page.
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
    private var liveMode: String? = null
    private val cards = ChatCardCache()
    private var loaded = false
    private val preferences = AgentCommandPreferences.forContext(appContext)
    private val mutableComposer = MutableStateFlow(ChatComposerState())
    val composer = mutableComposer.asStateFlow()
    private var target: NativeChatComposerTarget? = null
    private var conversationPayload: Map<String, Any?>? = null
    private var permission: AgentPermissionMode = AgentPermissionMode.FullAccess
    /** This page's navigation fence: bumped when it closes, so a queued turn never prompts. */
    @Volatile private var surfaceOpen = true
    private var sending = false

    init {
        ChatRuntimeHost.initialize(appContext)
        coordinator = ChatRuntimeHost.coordinator
        coordinator.addListener(listener)
    }

    fun load() {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val avatar = withContext(Dispatchers.IO) { loadAgentAvatarPreview(appContext) }
            mutableState.update { it.copy(agentAvatar = avatar) }
        }
        val live = coordinator.allSnapshots()
            .filter { it.conversationId.toLong() == conversationId && it.messages.isNotEmpty() }
            .maxByOrNull { it.revision }
        viewModelScope.launch { resolveComposerTarget(live?.mode) }
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
                    rows.mapNotNull { row -> runCatching { ChatMessage.fromJson(row).toUi(cards) }.getOrNull() } to agentId
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
        liveMode = snapshot.mode
        mutableState.update {
            val stopping = it.stoppingToolMessageId?.takeIf { id ->
                snapshot.messages.firstOrNull { message -> message.id == id }?.cardData?.get("status") == "running"
            }
            it.copy(
                stoppingToolMessageId = stopping,
                messages = snapshot.messages.map { message -> message.toUi(cards) },
                activeTaskIds = snapshot.activeAgentTurnIds,
                conversationAgentId = snapshot.conversation?.get("agentId")?.toString()?.ifBlank { null },
                isLive = true,
                loading = false,
            )
        }
        snapshot.conversation?.let { conversationPayload = it }
        mutableComposer.update {
            it.copy(
                // A send still seeding its runtime publishes an idle snapshot first.
                isProcessing = snapshot.isAiResponding || sending,
                cancelling = it.cancelling && snapshot.isAiResponding,
            ).withContextUsage(snapshot.conversation)
        }
    }

    private suspend fun resolveComposerTarget(liveMode: String?) {
        val payload = runCatching {
            withContext(Dispatchers.IO) { conversations.getConversationPayload(conversationId) }
        }.getOrNull() ?: return
        conversationPayload = payload
        val resolved = NativeChatComposerTarget.resolve(
            storedMode = payload["mode"]?.toString(),
            agentId = payload["agentId"]?.toString(),
            liveRuntimeMode = liveMode,
        ) ?: return
        target = resolved
        if (resolved.showsPermission) {
            val stored = preferences.turnSettings(conversationId.toInt(), modelSource(resolved)).permission
            permission = stored.forLocalHarness()
        }
        mutableComposer.update {
            it.copy(
                available = true,
                permission = if (resolved.showsPermission) permission.toComposer() else null,
                permissionChoices = if (resolved.showsPermission) LOCAL_PERMISSION_CHOICES else emptyList(),
            ).withContextUsage(payload)
        }
    }

    private fun ChatComposerState.withContextUsage(conversation: Map<String, Any?>?): ChatComposerState {
        conversation ?: return this
        fun long(key: String) = (conversation[key] as? Number)?.toLong() ?: conversation[key]?.toString()?.toLongOrNull()
        val used = long("latestPromptTokens") ?: 0L
        val threshold = long("promptTokenThreshold") ?: 128_000L
        val ring = contextUsageRing(used, threshold, long("latestPromptTokensUpdatedAt") ?: 0L)
        return copy(contextUsage = ring, contextUsageLabel = ring?.let { "$used / $threshold tokens" })
    }

    private fun modelSource(target: NativeChatComposerTarget): String =
        agentModelSourceKey(runtime = "local", remoteEnabled = false, activeAgentId = target.agentId)

    // ---------------------------------------------------------------------
    // Composer (batch 5d-1b)
    // ---------------------------------------------------------------------

    /**
     * Sends one submission. Returns false when it was not admitted (the
     * composer then keeps the draft). Settings are frozen here, before any
     * await, like the Flutter page's dispatch.
     */
    fun send(text: String): Boolean {
        val target = target ?: return false
        val attachments = mutableComposer.value.attachments
        if (text.isEmpty() && attachments.isEmpty()) return false
        if (sending || mutableComposer.value.isProcessing) return false
        sending = true
        val ids = ChatTurnIds.forSubmission(System.currentTimeMillis())
        val attachmentMaps = attachments.map { it.toMap() }
        val userContent = linkedMapOf<String, Any?>("text" to text, "id" to ids.userMessageId)
        if (attachmentMaps.isNotEmpty()) userContent["attachments"] = attachmentMaps
        val userRow = ChatMessage(id = ids.userMessageId, type = 1, user = 1, content = userContent)
        val runtimeConversationId = conversationId.toInt()
        val settings = if (target.showsPermission) {
            preferences.turnSettings(runtimeConversationId, modelSource(target))
        } else {
            null
        }
        val overrides = appContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val request = ChatTurnRequest(
            taskId = ids.taskId,
            conversationId = runtimeConversationId,
            mode = target.runtimeMode,
            text = text,
            attachments = attachmentMaps,
            userMessage = userRow,
            agentId = target.agentId,
            permission = if (target.showsPermission) permission else AgentPermissionMode.FullAccess,
            model = settings?.model
                ?: pureChatModelOverride(overrides.getString("flutter.conversation_model_overrides_v1", null), conversationId)
                    .takeIf { !target.showsPermission },
            effort = settings?.reasoningEffort
                ?: pureChatReasoningEffort(overrides.getString("flutter.conversation_reasoning_efforts_v1", null), conversationId)
                    .takeIf { !target.showsPermission },
            collaborationMode = settings?.collaborationMode,
            conversationMode = target.conversationMode,
            terminalEnvironment = OmnibotTerminalEnvironment.loadUserVariables(appContext).ifEmpty { null },
            conversation = conversationPayload,
            clearThinkingOnFailure = !target.showsPermission,
        )
        mutableComposer.update { it.copy(attachments = emptyList(), isProcessing = true) }
        viewModelScope.launch {
            val seeded = runCatching { seedRuntime(target.runtimeMode) }
                .onFailure { Log.w(TAG, "加载完整历史失败: ${it.message}") }.isSuccess
            if (!seeded) {
                sending = false
                mutableComposer.update { it.copy(isProcessing = false, attachments = attachments) }
                toast(if (AppLocaleManager.isEnglish()) "Couldn't load this conversation. Try again." else "无法加载对话，请重试")
                return@launch
            }
            liveMode = target.runtimeMode
            ChatRuntimeHost.launchTurnDetached(request, isTargetCurrent = { surfaceOpen }) { outcome ->
                sending = false
                if (outcome.status == ChatTurnOutcome.Status.Rejected) {
                    mutableComposer.update { it.copy(isProcessing = false) }
                }
            }
        }
        return true
    }

    /**
     * Admission persists the runtime's messages as the conversation, so a
     * runtime created from the preview's partial page would drop older
     * history. Seed it with every stored message (Dart `onConversationLoaded`
     * reads the whole history the same way) unless a live runtime exists.
     */
    private suspend fun seedRuntime(mode: String) {
        val runtimeConversationId = conversationId.toInt()
        if (coordinator.snapshotFor(runtimeConversationId, mode) != null) return
        val messages = withContext(Dispatchers.IO) {
            val storageMode = target?.conversationMode ?: mode
            conversations.listConversationMessages(conversationId, storageMode).mapNotNull { row ->
                runCatching { ChatMessage.fromJson(row) }.getOrNull()
            }
        }
        if (coordinator.snapshotFor(runtimeConversationId, mode) != null) return
        coordinator.ensureRuntime(
            runtimeConversationId,
            mode,
            initialMessages = messages,
            conversation = conversationPayload,
            initialChatIslandDisplayLayer = null,
        )
        coordinator.publishDirtySnapshots()
    }

    /** Cancels the running turn; its PromptResponse ends it through the reducer. */
    fun cancel() {
        val mode = liveMode ?: return
        val snapshot = coordinator.snapshotFor(conversationId.toInt(), mode) ?: return
        if (!snapshot.isAiResponding || mutableComposer.value.cancelling) return
        val args = linkedMapOf<String, Any?>("conversationId" to conversationId.toInt())
        snapshot.activeAcpSessionId?.let { args["sessionId"] = it }
        snapshot.activeAcpTurnId?.let { args["promptId"] = it }
        mutableComposer.update { it.copy(cancelling = true) }
        viewModelScope.launch {
            val cancelled = runCatching { ChatRuntimeHost.dispatcher.cancelTurn(args) }
                .onFailure { Log.w(TAG, "取消回合失败: ${it.message}") }.isSuccess
            if (!cancelled) {
                mutableComposer.update { it.copy(cancelling = false) }
                toast(if (AppLocaleManager.isEnglish()) "Couldn't stop the reply. Try again." else "停止回复失败，请重试")
            }
        }
    }

    /** Stores the choice on the Flutter keys, so both composers stay in sync. */
    fun selectPermission(choice: ChatComposerPermission) {
        val target = target ?: return
        permission = choice.toAgent()
        preferences.write(
            AgentCommandPreferences.Kind.PermissionMode,
            permission.preferenceValue,
            conversationId.toInt(),
            modelSource(target),
        )
        mutableComposer.update { it.copy(permission = choice) }
    }

    /** Adds picked documents; a path already attached is skipped (Dart `_pickAttachments`). */
    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { uris.mapNotNull(::describeAttachment) }
            mutableComposer.update { state ->
                val known = state.attachments.map { it.path }.toMutableSet()
                state.copy(attachments = state.attachments + picked.filter { known.add(it.path) })
            }
        }
    }

    fun removeAttachment(id: String) {
        mutableComposer.update { state -> state.copy(attachments = state.attachments.filterNot { it.id == id }) }
    }

    private fun describeAttachment(uri: Uri): ChatComposerAttachment? = runCatching {
        val resolver = appContext.contentResolver
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
                size = if (cursor.isNull(1)) null else cursor.getLong(1)
            }
        }
        val mimeType = resolver.getType(uri)
        val path = uri.toString()
        ChatComposerAttachment(
            id = "${path}_${System.nanoTime() / 1000}",
            name = name?.trim()?.ifEmpty { null } ?: uri.lastPathSegment ?: "attachment",
            path = path,
            size = size,
            mimeType = mimeType,
            isImage = mimeType?.startsWith("image/") == true,
        )
    }.onFailure { Log.w(TAG, "读取附件失败: ${it.message}") }.getOrNull()

    private fun toast(text: String) = Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()

    /**
     * Answers a pending approval (Flutter `AgentRequestNotice._respond`). The
     * request belongs to the live ACP session, so history rows are never
     * answered. After the runtime acknowledges, the card's status is written
     * through the coordinator, which republishes to the Flutter mirror and
     * persists the message like the composer's user-input answer.
     */
    fun respondToApproval(messageId: String, accepted: Boolean) {
        val mode = liveMode ?: return
        val runtimeConversationId = conversationId.toInt()
        val message = coordinator.snapshotFor(runtimeConversationId, mode)?.messages
            ?.firstOrNull { it.id == messageId } ?: return
        val cardData = message.cardData ?: return
        val requestId = cardData["requestId"] ?: return
        if (messageId in state.value.respondingRequestIds) return
        mutableState.update { it.copy(respondingRequestIds = it.respondingRequestIds + messageId) }
        viewModelScope.launch {
            val acknowledged = runCatching {
                val args = linkedMapOf<String, Any?>("requestId" to requestId)
                cardData["agentId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { args["agentId"] = it }
                requestConversationId(cardData["conversationId"])?.let { args["conversationId"] = it }
                cardData["sessionId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { args["sessionId"] = it }
                args["response"] = linkedMapOf("decision" to if (accepted) "accept" else "decline")
                val result = ChatRuntimeHost.dispatcher.respondToServerRequest(args) as? Map<*, *>
                check(result?.get("ok") == true) { "ACP server request was not acknowledged" }
            }.onFailure { Log.w(TAG, "审批回复失败: ${it.message}") }.isSuccess
            if (acknowledged) {
                markRequestAnswered(runtimeConversationId, mode, messageId, if (accepted) "accepted" else "declined")
            } else {
                Toast.makeText(
                    appContext,
                    if (AppLocaleManager.isEnglish()) "Reply was not sent. Try again." else "回复未送达，可以重试",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            mutableState.update { it.copy(respondingRequestIds = it.respondingRequestIds - messageId) }
        }
    }

    /** Re-reads the message: the reducer may have replaced it while the reply was in flight. */
    private fun markRequestAnswered(conversationId: Int, mode: String, messageId: String, status: String) {
        val current = coordinator.snapshotFor(conversationId, mode)?.messages
            ?.firstOrNull { it.id == messageId } ?: return
        val cardData = LinkedHashMap(current.cardData ?: return)
        // A terminal status the reducer applied meanwhile (cancelled, expired) wins.
        if (requestCardStatus(cardData) != "pending") return
        cardData["status"] = status
        cardData["submittedAnswers"] = emptyList<String>()
        val content = LinkedHashMap(current.content ?: emptyMap()).apply {
            put("cardData", cardData)
            put("id", messageId)
        }
        if (coordinator.replaceRuntimeMessage(conversationId, mode, messageId, current.copy(content = content))) {
            coordinator.publishDirtySnapshots()
            coordinator.schedulePersistRuntimeConversation(conversationId, mode, persistMessages = true)
        }
    }

    /**
     * Stops the live tool from the activity strip (Flutter
     * `_handleToolActivityStopRequested`): ACP has no per-tool cancel, so the
     * active turn is cancelled through the 5b `session/cancel` entry. The
     * PromptResponse that follows ends the turn through the reducer.
     */
    fun stopActiveTool(messageId: String) {
        val mode = liveMode ?: return
        if (state.value.stoppingToolMessageId != null) return
        val runtimeConversationId = conversationId.toInt()
        val snapshot = coordinator.snapshotFor(runtimeConversationId, mode) ?: return
        val runId = snapshot.messages.firstOrNull { it.id == messageId || it.cardData?.get("cardId")?.toString()?.trim() == messageId }
            ?.cardData?.let { (it["runId"] ?: it["run_id"])?.toString()?.trim() }
            ?.takeIf { it.isNotEmpty() }
        val args = linkedMapOf<String, Any?>("conversationId" to runtimeConversationId)
        // Normal and Agent chats both keep the live ACP session and prompt on the runtime.
        snapshot.activeAcpSessionId?.let { args["sessionId"] = it }
        snapshot.activeAcpTurnId?.let { args["promptId"] = it }
        runId?.let { args["runId"] = it }
        mutableState.update { it.copy(stoppingToolMessageId = messageId) }
        viewModelScope.launch {
            val stopped = runCatching {
                val response = ChatRuntimeHost.dispatcher.cancelTurn(args) as? Map<*, *>
                response?.get("ok") == true || response?.get("cancelled") == true || response?.get("status") == "cancelled"
            }.onFailure { Log.w(TAG, "停止工具失败: ${it.message}") }.getOrDefault(false)
            // On success the button stays disabled until the card stops running.
            if (!stopped) {
                Toast.makeText(
                    appContext,
                    if (AppLocaleManager.isEnglish()) "Couldn't stop the tool call. Try again later." else "停止工具调用失败，请稍后重试",
                    Toast.LENGTH_SHORT,
                ).show()
                mutableState.update { it.copy(stoppingToolMessageId = null) }
            }
        }
    }

    private fun requestConversationId(value: Any?): Int? =
        (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull()

    override fun onCleared() {
        // A turn whose launch has not reached session/prompt stops here; one
        // already prompting keeps running and ends through its PromptResponse.
        surfaceOpen = false
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
 * Presented cards by message id. Snapshots reuse unchanged message content
 * maps, so a card is only re-derived when its content changed.
 */
internal class ChatCardCache {
    internal data class Cards(
        val tool: AgentToolCardUi? = null,
        val request: AgentRequestCardUi? = null,
        val thinking: DeepThinkingCardUi? = null,
    )

    private val entries = HashMap<String, Pair<Map<String, Any?>, Cards>>()

    @Synchronized
    fun present(message: ChatMessage): Cards? {
        val content = message.content ?: return null
        val cardData = message.cardData ?: return null
        if (message.type != 2) return null
        entries[message.id]?.let { (cachedContent, cards) -> if (cachedContent === content) return cards }
        val english = runCatching { AppLocaleManager.isEnglish() }.getOrDefault(false)
        val type = cardData["type"]?.toString()
        val cards = runCatching {
            when {
                type == "agent_tool_summary" -> Cards(tool = presentAgentToolCard(cardData, english))
                isAgentRequestCardType(type) -> Cards(request = presentAgentRequestCard(cardData))
                type == "deep_thinking" -> Cards(thinking = presentDeepThinkingCard(cardData, english))
                else -> null
            }
        }.onFailure { Log.w("NativeChatTranscript", "卡片投影失败($type): ${it.message}") }
            .getOrNull() ?: return null
        entries[message.id] = content to cards
        return cards
    }
}

internal fun ChatMessage.toUi(cards: ChatCardCache? = null): ChatMessageUi {
    val presented = cards?.present(this)
    return ChatMessageUi(
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
        toolCard = presented?.tool,
        requestCard = presented?.request,
        thinkingCard = presented?.thinking,
    )
}

@Composable
internal fun NativeChatTranscriptRoute(
    viewModel: NativeChatTranscriptViewModel,
    onOpenLink: (String) -> Unit,
    onToolAction: (AgentToolActionUi) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    // Any document; the runtime copies it into the workspace at send time.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addAttachments(uris)
    }
    val composerActions = remember(viewModel) {
        ChatComposerActions(
            onSend = viewModel::send,
            onCancel = viewModel::cancel,
            onPickAttachment = { picker.launch(arrayOf("*/*")) },
            onRemoveAttachment = viewModel::removeAttachment,
            onSelectPermission = viewModel::selectPermission,
        )
    }
    val actions = remember(viewModel, onOpenLink, onToolAction) {
        ChatTranscriptActions(
            onOpenLink = onOpenLink,
            onToolAction = onToolAction,
            onRespondToApproval = viewModel::respondToApproval,
            onStopTool = viewModel::stopActiveTool,
        )
    }
    ChatTranscriptScreen(state, onBack, actions, composer, composerActions)
}
