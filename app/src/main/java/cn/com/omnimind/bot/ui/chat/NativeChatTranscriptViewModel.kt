package cn.com.omnimind.bot.ui.chat

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
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
import cn.com.omnimind.nativeui.chat.ChatHarnessOption
import cn.com.omnimind.nativeui.chat.ChatPageBarState
import cn.com.omnimind.nativeui.chat.ChatGreetingState
import cn.com.omnimind.nativeui.chat.ChatQuickPrompt
import cn.com.omnimind.nativeui.chat.InjectedDraft
import cn.com.omnimind.nativeui.chat.UserMessageAction
import cn.com.omnimind.nativeui.chat.userMessageActions
import cn.com.omnimind.nativeui.chat.retriedRoundRemovalCount
import cn.com.omnimind.bot.preferences.UiPreferencesStore
import cn.com.omnimind.bot.ui.nativehome.resolveNativeHomeLocale
import cn.com.omnimind.nativeui.chat.AcpConfigPanelState
import cn.com.omnimind.nativeui.chat.parseAcpConfigOptions
import cn.com.omnimind.bot.agent.NativeAgentsRepository
import cn.com.omnimind.nativeui.chat.ChatAdvertisedCommand
import cn.com.omnimind.nativeui.chat.ChatSlashSubmit
import cn.com.omnimind.nativeui.chat.resolveSubmit
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import cn.com.omnimind.bot.agent.runtime.CodexRemoteBridgeConfigStore
import cn.com.omnimind.bot.agent.projection.ConversationModes
import org.json.JSONObject
import cn.com.omnimind.nativeui.chat.ChatTranscriptActions
import cn.com.omnimind.nativeui.chat.ChatPageBarActions
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
    initialConversationId: Long?,
    private val mode: String,
    title: String,
    private val savedState: SavedStateHandle,
    /** Text a new page starts with (a Home quick prompt or Home's draft); never sent by itself. */
    private val initialDraft: String? = null,
) : ViewModel() {
    /**
     * Null until the first send of a new conversation (5e-1) creates it;
     * fixed afterwards and kept in [savedState], so the page reopens that
     * conversation after process death (5e-2). Main thread only.
     */
    private var conversationIdOrNull: Long? = initialConversationId ?: savedState.get<Long>(KEY_CONVERSATION_ID)
    private val conversationId: Long get() = checkNotNull(conversationIdOrNull)
    private var creatingConversation = false
    private val appContext = context.applicationContext
    private val conversations by lazy { ConversationDomainService(appContext) }
    private val mutableState = MutableStateFlow(ChatTranscriptState(title = title))
    val state = mutableState.asStateFlow()

    private val coordinator: ChatConversationRuntimeCoordinator
    private val listener = ChatConversationRuntimeCoordinator.Listener { snapshots, _ ->
        val id = conversationIdOrNull ?: return@Listener
        snapshots.filter { it.conversationId.toLong() == id }
            .maxByOrNull { it.revision }
            ?.let(::applySnapshot)
    }
    private var liveRevision = 0L
    private var historyOffset = 0
    private var historyLoading = false
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
    private val models = NativeChatModelCatalog(appContext)
    private var modelCatalog = NativeChatModelCatalog.Catalog(null, emptyList(), null)
    private var applyingSetting = false
    private val agents = NativeAgentsRepository(appContext)
    private val mutableBar = MutableStateFlow(ChatPageBarState())
    val bar = mutableBar.asStateFlow()
    /** One-shot request for the route to open a new conversation on the chosen Harness. */
    private val mutableOpenNew = MutableStateFlow(0L)
    val openNewConversation = mutableOpenNew.asStateFlow()

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
        if (conversationIdOrNull == null) {
            viewModelScope.launch { resolveNewConversationTarget() }
            mutableState.update { it.copy(loading = false, greeting = readGreeting()) }
            // Adopted once: SavedStateHandle remembers it across recreation.
            if (savedState.get<Boolean>(KEY_DRAFT_ADOPTED) != true) {
                initialDraft?.trim()?.takeIf { it.isNotEmpty() }?.let(::fillComposer)
                savedState[KEY_DRAFT_ADOPTED] = true
            }
            return
        }
        mutableState.update { it.copy(greeting = readGreeting()) }
        val live = coordinator.allSnapshots()
            .filter { it.conversationId.toLong() == conversationId && it.messages.isNotEmpty() }
            .maxByOrNull { it.revision }
        viewModelScope.launch { resolveComposerTarget(live?.mode) }
        if (live != null) {
            applySnapshot(live)
            return
        }
        viewModelScope.launch {
            val agentId = runCatching {
                withContext(Dispatchers.IO) { conversations.getConversationPayload(conversationId)?.get("agentId")?.toString() }
            }.getOrNull()
            mutableState.update { it.copy(conversationAgentId = agentId?.ifBlank { null }) }
            loadHistoryPage()
        }
    }

    /**
     * Stored history, newest first, one page at a time (Dart
     * `loadMoreMessages`). The offset advances by what was received, so a
     * short page cannot leave a gap. A live runtime holds the complete
     * history (it is seeded with every stored message), so it never pages.
     */
    fun loadOlderMessages() {
        val state = mutableState.value
        if (state.isLive || !state.hasMoreHistory || historyLoading || conversationIdOrNull == null) return
        viewModelScope.launch { loadHistoryPage() }
    }

    private suspend fun loadHistoryPage() {
        historyLoading = true
        mutableState.update { it.copy(loadingMore = historyOffset > 0) }
        val page = runCatching {
            withContext(Dispatchers.IO) {
                val result = conversations.listConversationMessagesPaged(conversationId, mode, HISTORY_PAGE, historyOffset)
                @Suppress("UNCHECKED_CAST")
                val rows = (result["messages"] as? List<Map<String, Any?>>).orEmpty()
                rows.mapNotNull { row -> runCatching { ChatMessage.fromJson(row).toUi(cards) }.getOrNull() } to
                    (result["hasMore"] == true)
            }
        }.onFailure { Log.w(TAG, "读取对话历史失败: ${it.message}") }.getOrNull()
        historyLoading = false
        // A live snapshot that arrived while history loaded wins.
        if (liveRevision > 0L) return
        val (rows, hasMore) = page ?: (emptyList<ChatMessageUi>() to false)
        historyOffset += rows.size
        mutableState.update {
            val known = it.messages.mapTo(HashSet()) { message -> message.id }
            it.copy(
                messages = it.messages + rows.filter { row -> row.id !in known },
                hasMoreHistory = hasMore && rows.isNotEmpty(),
                isLive = false,
                loading = false,
                loadingMore = false,
            )
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
                hasMoreHistory = false,
                loadingMore = false,
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
        refreshSlash(snapshot)
        if (mutableBar.value.config?.readOnly != snapshot.isAiResponding) {
            updateConfig { it.copy(readOnly = snapshot.isAiResponding) }
        }
    }

    /** Commands, plan mode and the config lock follow the live snapshot. */
    private fun refreshSlash(snapshot: ChatRuntimeSnapshot? = liveSnapshot()) {
        val target = target ?: return
        val commands = snapshot?.availableAcpCommands.orEmpty().mapNotNull { command ->
            val name = command["name"]?.toString()?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            ChatAdvertisedCommand(name, command["description"]?.toString().orEmpty())
        }
        val collaboration = snapshot?.acpConfigOptions.orEmpty()
            .firstOrNull { (it["id"] ?: it["configId"])?.toString() == "collaboration_mode" }
        val planMode = collaboration?.let(::optionValues)?.firstOrNull { it.trim().equals("plan", ignoreCase = true) }
        mutableComposer.update {
            it.copy(
                slash = it.slash.copy(
                    agent = target.showsPermission,
                    advertisedCommands = if (target.showsPermission) commands else emptyList(),
                    models = modelCatalog.models,
                    selectedModel = modelCatalog.selected,
                    planMode = planMode,
                    planActive = planMode != null &&
                        collaboration?.get("currentValue")?.toString()?.equals(planMode, ignoreCase = true) == true,
                    // Any running turn: the dispatch model is shared by every conversation.
                    configLocked = coordinator.allSnapshots().any { s -> s.isAiResponding } || applyingSetting,
                ),
            )
        }
    }

    private fun liveSnapshot(): ChatRuntimeSnapshot? {
        val id = conversationIdOrNull ?: return null
        return liveMode?.let { coordinator.snapshotFor(id.toInt(), it) }
    }

    /** Values of a select option, flattening grouped options (Dart `_loadAgentCollaborationModes`). */
    private fun optionValues(option: Map<String, Any?>): List<String> =
        (option["options"] as? List<*>).orEmpty().flatMap { entry ->
            val map = entry as? Map<*, *> ?: return@flatMap emptyList()
            val nested = map["options"] as? List<*>
            if (nested != null) nested.mapNotNull { (it as? Map<*, *>)?.get("value")?.toString() }
            else listOfNotNull(map["value"]?.toString())
        }.filter { it.isNotEmpty() }

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
        applyComposerTarget(resolved, payload)
    }

    /**
     * A new conversation is an Agent conversation on the selected Harness
     * (Dart `agentIdForNewConversation`). A remote Codex selection keeps its
     * Flutter flow, so the composer shows the "open in chat" hint.
     */
    private suspend fun resolveNewConversationTarget() {
        val harness = withContext(Dispatchers.IO) {
            runCatching {
                if (CodexRemoteBridgeConfigStore(appContext).read().enabled) null
                else AcpAgentProfileStore(appContext).selected().id
            }.getOrNull()
        } ?: return
        val resolved = NativeChatComposerTarget.resolve(ConversationModes.AGENT, harness, liveRuntimeMode = null) ?: return
        applyComposerTarget(resolved, payload = null)
    }

    private suspend fun applyComposerTarget(resolved: NativeChatComposerTarget, payload: Map<String, Any?>?) {
        target = resolved
        viewModelScope.launch { loadHarnessChoices(resolved) }
        if (resolved.showsPermission) {
            val stored = preferences.turnSettings(conversationIdOrNull?.toInt(), modelSource(resolved)).permission
            permission = stored.forLocalHarness()
        }
        if (resolved.showsPermission) {
            modelCatalog = withContext(Dispatchers.IO) { runCatching { models.load() }.getOrDefault(modelCatalog) }
        }
        val effort = if (resolved.showsPermission) null else conversationIdOrNull?.let {
            pureChatReasoningEffort(flutterPreferences().getString(EFFORTS_KEY, null), it)
        }
        mutableComposer.update {
            it.copy(
                slash = it.slash.copy(agent = resolved.showsPermission, selectedEffort = effort),
                available = true,
                permission = if (resolved.showsPermission) permission.toComposer() else null,
                permissionChoices = if (resolved.showsPermission) LOCAL_PERMISSION_CHOICES else emptyList(),
            ).let { state -> payload?.let { state.withContextUsage(it) } ?: state }
        }
        refreshSlash()
    }

    private fun flutterPreferences() =
        appContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

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
     * Routes a submitted draft through the slash rules (5d-1c) and returns
     * the next draft: "" clears it, other text replaces it, null keeps it.
     */
    fun submit(draft: String): String? {
        if (target == null) return null
        val slash = mutableComposer.value.slash
        // Attachments always travel with a prompt, never with a command.
        // An edit resends the latest user message (Dart `_saveAndResendEditedUserMessage`).
        mutableComposer.value.editingMessageId?.let { editing -> return if (resendEdited(editing, draft.trim())) "" else null }
        val intent = if (draft.isEmpty()) ChatSlashSubmit.Send("") else slash.resolveSubmit(draft, AGENT_INIT_PROMPT)
        return when (intent) {
            is ChatSlashSubmit.Send ->
                if (send(intent.text, display = intent.display, collaborationMode = intent.collaborationMode)) "" else null
            is ChatSlashSubmit.FillText -> intent.text
            is ChatSlashSubmit.SelectModel -> { selectModel(intent.modelId); "" }
            ChatSlashSubmit.TogglePlan -> { setPlanMode(!slash.planActive, then = null); "" }
            is ChatSlashSubmit.StartPlan -> { setPlanMode(true, then = intent.prompt); "" }
            is ChatSlashSubmit.SetEffort -> { setEffort(intent.effort); "" }
            is ChatSlashSubmit.Notice -> { toast(noticeText(intent.reason)); null }
        }
    }

    /**
     * Sends one submission. Returns false when it was not admitted (the
     * composer then keeps the draft). Settings are frozen here, before any
     * await, like the Flutter page's dispatch.
     */
    private fun send(
        text: String,
        display: String? = null,
        collaborationMode: String? = null,
        /** A retry keeps its user row; its id and attachments are reused (Dart `retainedUserMessageId`). */
        retained: ChatMessage? = null,
        /** Attachments of a retried or edited message; the composer's pending ones otherwise. */
        attachmentOverride: List<Map<String, Any?>>? = null,
        /** Removes the previous round once the runtime holds the full history (retry and edit). */
        beforeLaunch: (suspend (conversationId: Int, mode: String) -> Boolean)? = null,
    ): Boolean {
        val target = target ?: return false
        val attachments = if (attachmentOverride == null) mutableComposer.value.attachments else emptyList()
        val attachmentMaps = attachmentOverride ?: attachments.map { it.toMap() }
        if (text.isEmpty() && attachmentMaps.isEmpty()) return false
        if (sending || mutableComposer.value.isProcessing) return false
        sending = true
        // Frozen before the first await, like the Flutter page's dispatch.
        val now = System.currentTimeMillis()
        val ids = retained?.let { ChatTurnIds.forRetry(it.id, now) } ?: ChatTurnIds.forSubmission(now)
        val userText = display ?: text
        val userContent = linkedMapOf<String, Any?>("text" to userText, "id" to ids.userMessageId)
        if (attachmentMaps.isNotEmpty()) userContent["attachments"] = attachmentMaps
        // A retained row is already in the runtime; the launcher must not insert a second one.
        val userRow = if (retained != null) null else ChatMessage(id = ids.userMessageId, type = 1, user = 1, content = userContent)
        val frozenPermission = if (target.showsPermission) permission else AgentPermissionMode.FullAccess
        val frozenModel = modelCatalog.selected
        val terminalEnvironment = OmnibotTerminalEnvironment.loadUserVariables(appContext).ifEmpty { null }
        mutableComposer.update { it.copy(attachments = emptyList(), isProcessing = true) }
        viewModelScope.launch {
            fun restore(message: String) {
                sending = false
                mutableComposer.update { it.copy(isProcessing = false, attachments = attachments) }
                toast(message)
            }
            // A new conversation is created by its first send (5e-1).
            val conversationId = conversationIdOrNull ?: createConversation(userText, target)
                ?: return@launch restore(
                    if (AppLocaleManager.isEnglish()) "Couldn't create the conversation. Try again." else "无法创建对话，请重试",
                )
            val seeded = runCatching { seedRuntime(target.runtimeMode) }
                .onFailure { Log.w(TAG, "加载完整历史失败: ${it.message}") }.isSuccess
            if (!seeded) {
                return@launch restore(
                    if (AppLocaleManager.isEnglish()) "Couldn't load this conversation. Try again." else "无法加载对话，请重试",
                )
            }
            val runtimeConversationId = conversationId.toInt()
            if (beforeLaunch != null && !beforeLaunch(runtimeConversationId, target.runtimeMode)) {
                return@launch restore(string(cn.com.omnimind.nativeui.R.string.omni_slash_failed))
            }
            val settings = if (target.showsPermission) preferences.turnSettings(runtimeConversationId, modelSource(target)) else null
            val overrides = flutterPreferences()
            val request = ChatTurnRequest(
                taskId = ids.taskId,
                conversationId = runtimeConversationId,
                mode = target.runtimeMode,
                text = text,
                attachments = attachmentMaps,
                userMessage = userRow,
                agentId = target.agentId,
                permission = frozenPermission,
                // Every local Harness runs on the shared dispatch binding (Dart
                // `_usesSharedProviderModel`); its model is the bound one, never a
                // stored per-Harness id that may have left the catalog.
                model = if (target.showsPermission) {
                    frozenModel
                } else {
                    pureChatModelOverride(overrides.getString(OVERRIDES_KEY, null), conversationId)
                },
                effort = if (target.showsPermission) {
                    settings?.reasoningEffort
                } else {
                    pureChatReasoningEffort(overrides.getString(EFFORTS_KEY, null), conversationId)
                },
                collaborationMode = collaborationMode ?: settings?.collaborationMode,
                conversationMode = target.conversationMode,
                terminalEnvironment = terminalEnvironment,
                conversation = conversationPayload,
                clearThinkingOnFailure = !target.showsPermission,
            )
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
     * Creates the conversation for a first send (Dart
     * `persistConversationSnapshot` with no id: title from the first user
     * text, the Agent's Harness bound to it). The drawer picks it up from
     * the database flow. The permission choice is stored for the new id, as
     * the Flutter page does after its first send.
     */
    private suspend fun createConversation(firstText: String, target: NativeChatComposerTarget): Long? {
        if (creatingConversation) return null
        creatingConversation = true
        try {
            val title = newConversationTitle(firstText)
            val payload = runCatching {
                withContext(Dispatchers.IO) {
                    conversations.createConversation(title = title, mode = target.conversationMode, agentId = target.agentId)
                }
            }.onFailure { Log.w(TAG, "创建对话失败: ${it.message}") }.getOrNull() ?: return null
            val id = (payload["id"] as? Number)?.toLong() ?: return null
            conversationIdOrNull = id
            savedState[KEY_CONVERSATION_ID] = id
            conversationPayload = payload
            mutableState.update { it.copy(title = payload["title"]?.toString()?.ifBlank { null } ?: title) }
            if (target.showsPermission) {
                preferences.write(AgentCommandPreferences.Kind.PermissionMode, permission.preferenceValue, id.toInt(), modelSource(target))
            }
            return id
        } finally {
            creatingConversation = false
        }
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

    /**
     * `/model <id>`: rebinds the dispatch model (Dart `_selectAgentModel`).
     * Refused while any turn runs: a Provider change reconnects the shared
     * ACP runtime, and Dart then called `disconnect`, which cancels every
     * running turn in every conversation (5d-1c fix).
     */
    private fun selectModel(modelId: String) {
        if (applyingSetting) return
        applyingSetting = true
        refreshSlash()
        viewModelScope.launch {
            val chosen = runCatching {
                modelCatalog = withContext(Dispatchers.IO) { models.load() }
                if (coordinator.allSnapshots().any { it.isAiResponding }) error("busy")
                val selected = withContext(Dispatchers.IO) { models.select(modelCatalog, modelId) }
                // An existing session keeps the model in its own configuration;
                // move it explicitly instead of reconnecting every Harness.
                if (selected != null) setSessionConfig("model", selected)
                selected
            }.onFailure { Log.w(TAG, "切换模型失败: ${it.message}") }
            applyingSetting = false
            when {
                chosen.exceptionOrNull()?.message == "busy" -> toast(noticeText(ChatSlashSubmit.Reason.Busy))
                chosen.isFailure -> toast(string(cn.com.omnimind.nativeui.R.string.omni_slash_failed))
                chosen.getOrNull() == null -> toast(string(cn.com.omnimind.nativeui.R.string.omni_slash_model_unavailable))
                else -> {
                    modelCatalog = modelCatalog.copy(selected = chosen.getOrNull())
                    toast(string(cn.com.omnimind.nativeui.R.string.omni_slash_model_set, chosen.getOrNull()!!))
                }
            }
            refreshSlash()
        }
    }

    /**
     * `/plan` (Dart `_activateAgentPlanMode` / `_deactivateAgentPlanMode`):
     * the advertised `collaboration_mode` value through
     * `session/set_config_option`, stored on the Flutter key. A prompt after
     * `/plan` is sent once plan mode is on.
     */
    private fun setPlanMode(enable: Boolean, then: String?) {
        val target = target ?: return
        // Plan mode is a session option; a new conversation has no session yet.
        if (conversationIdOrNull == null) return
        val slash = mutableComposer.value.slash
        val value = if (enable) slash.planMode else defaultCollaborationMode()
        if (value == null || applyingSetting) return
        if (enable && slash.planActive && then != null) {
            send(then, display = "/plan $then", collaborationMode = value)
            return
        }
        applyingSetting = true
        refreshSlash()
        viewModelScope.launch {
            val applied = runCatching { check(setSessionConfig("collaboration_mode", value)) { "no session" } }
                .onFailure { Log.w(TAG, "切换 Plan 模式失败: ${it.message}") }.isSuccess
            applyingSetting = false
            if (applied) {
                val source = modelSource(target)
                if (enable) {
                    preferences.write(AgentCommandPreferences.Kind.CollaborationMode, value, conversationId.toInt(), source)
                } else {
                    preferences.clear(AgentCommandPreferences.Kind.CollaborationMode, conversationId.toInt(), source)
                }
                mutableComposer.update { it.copy(slash = it.slash.copy(planActive = enable)) }
                if (then != null) send(then, display = "/plan $then", collaborationMode = value)
            } else {
                toast(string(cn.com.omnimind.nativeui.R.string.omni_slash_failed))
            }
            refreshSlash()
        }
    }

    /**
     * `session/set_config_option` on this conversation's bound session. False
     * when it has none yet: the stored preference applies at `session/new`
     * (Dart `_setAgentConfigOption`).
     */
    private suspend fun setSessionConfig(configId: String, value: String): Boolean {
        val target = target ?: return false
        val sessionId = withContext(Dispatchers.IO) {
            DatabaseHelper.getAgentSessionBindingByConversationId(conversationId)?.threadId
        } ?: return false
        AgentRuntimeManager.getInstance(appContext).handleMethod(
            "session/set_config_option",
            linkedMapOf(
                "sessionId" to sessionId,
                "conversationId" to conversationId.toInt(),
                "agentId" to target.agentId,
                "configId" to configId,
                "value" to value,
            ),
        )
        return true
    }

    private fun defaultCollaborationMode(): String? = liveSnapshot()?.acpConfigOptions.orEmpty()
        .firstOrNull { (it["id"] ?: it["configId"])?.toString() == "collaboration_mode" }
        ?.let(::optionValues)?.firstOrNull { it.equals("default", ignoreCase = true) }

    /** `/effort` for pure chat: the conversation's entry in the Flutter effort map. */
    private fun setEffort(effort: String) {
        if (conversationIdOrNull == null) return
        val preferences = flutterPreferences()
        val map = runCatching { JSONObject(preferences.getString(EFFORTS_KEY, null) ?: "{}") }.getOrElse { JSONObject() }
        map.put(conversationId.toString(), effort)
        preferences.edit().putString(EFFORTS_KEY, map.toString()).apply()
        mutableComposer.update { it.copy(slash = it.slash.copy(selectedEffort = effort)) }
        toast(string(cn.com.omnimind.nativeui.R.string.omni_slash_effort_set, effort))
    }

    private fun noticeText(reason: ChatSlashSubmit.Reason): String = string(
        when (reason) {
            ChatSlashSubmit.Reason.Unsupported -> cn.com.omnimind.nativeui.R.string.omni_slash_unsupported
            ChatSlashSubmit.Reason.ReviewUnavailable -> cn.com.omnimind.nativeui.R.string.omni_slash_review_unavailable
            ChatSlashSubmit.Reason.PlanUnavailable -> cn.com.omnimind.nativeui.R.string.omni_slash_plan_unavailable
            ChatSlashSubmit.Reason.InvalidEffort -> cn.com.omnimind.nativeui.R.string.omni_slash_invalid_effort
            ChatSlashSubmit.Reason.OpenInChat -> cn.com.omnimind.nativeui.R.string.omni_slash_open_in_chat
            ChatSlashSubmit.Reason.Busy -> cn.com.omnimind.nativeui.R.string.omni_slash_busy
        },
    )

    private fun string(id: Int, vararg args: Any): String = appContext.getString(id, *args)

    // ---------------------------------------------------------------------
    // Empty-page greeting (batch 5e-4)
    // ---------------------------------------------------------------------

    /**
     * The greeting the Flutter page shows on an empty conversation (the Home
     * greeting setting and its quick prompts, localized like native Home);
     * null when the user turned it off.
     */
    private fun readGreeting(): ChatGreetingState? {
        val saved = runCatching { UiPreferencesStore.get(appContext).read() }.getOrNull() ?: return null
        if (!saved.home.greetingEnabled) return null
        val english = resolveNativeHomeLocale(saved.language).language == "en"
        return ChatGreetingState(
            agentName = mutableBar.value.harness?.name.orEmpty(),
            quickPrompts = saved.home.prompts.map { prompt ->
                ChatQuickPrompt(
                    prompt.id,
                    if (english) prompt.titleEn ?: prompt.title else prompt.title,
                    if (english) prompt.promptEn ?: prompt.prompt else prompt.prompt,
                )
            },
            pinnedPromptIds = saved.home.pinnedIds,
        )
    }

    /** Fills the composer (Dart `_applyHomeQuickPrompt`): no turn is admitted. */
    fun fillComposer(text: String) {
        val value = text.trim().ifEmpty { return }
        mutableComposer.update { it.copy(injectedDraft = InjectedDraft(System.nanoTime(), value)) }
    }

    // ---------------------------------------------------------------------
    // App bar (batch 5e-3)
    // ---------------------------------------------------------------------

    /** Enabled Harnesses for the switcher; remote Codex keeps its Flutter flow. */
    private suspend fun loadHarnessChoices(target: NativeChatComposerTarget) {
        val agentId = target.agentId ?: return
        val catalog = runCatching { withContext(Dispatchers.IO) { agents.listAgents(refresh = false) } }
            .onFailure { Log.w(TAG, "读取 Agent 列表失败: ${it.message}") }.getOrNull()
        val choices = catalog?.agents.orEmpty().filter { it.enabled }
            .map { ChatHarnessOption(it.id, it.name.ifBlank { it.id }) }
        val current = choices.firstOrNull { it.id == agentId } ?: ChatHarnessOption(agentId, agentId)
        mutableBar.update {
            it.copy(
                harness = current,
                harnessChoices = choices.ifEmpty { listOf(current) },
                config = it.config ?: AcpConfigPanelState(),
            )
        }
        mutableState.update { state -> state.copy(greeting = state.greeting?.copy(agentName = current.name)) }
    }

    // ---------------------------------------------------------------------
    // ACP config panel (batch 5e-3, Flutter `_buildAcpConfigButton`)
    // ---------------------------------------------------------------------

    private fun updateConfig(change: (AcpConfigPanelState) -> AcpConfigPanelState) =
        mutableBar.update { bar -> bar.copy(config = change(bar.config ?: AcpConfigPanelState())) }

    /**
     * Opens the panel and reads the session's declared options
     * (`session/load` without history). Settings belong to the conversation's
     * session, so a new page asks for a first send instead of creating an
     * empty conversation just to read them.
     */
    fun openConfig() {
        if (conversationIdOrNull == null) {
            toast(string(cn.com.omnimind.nativeui.R.string.omni_acp_config_needs_conversation))
            return
        }
        updateConfig { it.copy(visible = true, error = null) }
        loadConfig(refresh = false)
    }

    fun dismissConfig() = updateConfig { it.copy(visible = false) }

    fun refreshConfig() = loadConfig(refresh = true)

    private fun loadConfig(refresh: Boolean) {
        val target = target ?: return
        val conversationId = conversationIdOrNull ?: return
        updateConfig { it.copy(loading = true, readOnly = thisTurnRunning()) }
        viewModelScope.launch {
            val result = runCatching {
                AgentRuntimeManager.getInstance(appContext).handleMethod(
                    "session/load",
                    linkedMapOf<String, Any?>(
                        "conversationId" to conversationId.toInt(),
                        "agentId" to target.agentId,
                        "conversationMode" to target.runtimeMode,
                        "includeHistory" to false,
                    ).apply { if (refresh) put("refreshConfig", true) },
                ) as? Map<*, *>
            }.onFailure { Log.w(TAG, "读取 ACP 参数失败: ${it.message}") }
            val options = result.getOrNull()?.get("configOptions")
            updateConfig {
                it.copy(
                    loading = false,
                    options = if (result.isSuccess) parseAcpConfigOptions(options, AppLocaleManager.isEnglish()) else it.options,
                    error = result.exceptionOrNull()?.let { error -> error.message ?: string(cn.com.omnimind.nativeui.R.string.omni_slash_failed) },
                )
            }
        }
    }

    /** `session/set_config_option`; the complete response replaces the list (dependent options included). */
    fun setConfig(configId: String, value: Any) {
        val target = target ?: return
        val conversationId = conversationIdOrNull ?: return
        val config = mutableBar.value.config ?: return
        if (config.saving || config.loading) return
        // Only this conversation's session changes; the runtime refuses it while that session runs.
        if (thisTurnRunning()) {
            updateConfig { it.copy(readOnly = true) }
            toast(noticeText(ChatSlashSubmit.Reason.Busy))
            return
        }
        updateConfig { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val sessionId = withContext(Dispatchers.IO) {
                    DatabaseHelper.getAgentSessionBindingByConversationId(conversationId)?.threadId
                } ?: error(string(cn.com.omnimind.nativeui.R.string.omni_acp_config_needs_conversation))
                AgentRuntimeManager.getInstance(appContext).handleMethod(
                    "session/set_config_option",
                    linkedMapOf(
                        "sessionId" to sessionId,
                        "conversationId" to conversationId.toInt(),
                        "agentId" to target.agentId,
                        "configId" to configId,
                        "value" to value,
                    ),
                ) as? Map<*, *>
            }.onFailure { Log.w(TAG, "写入 ACP 参数失败: ${it.message}") }
            updateConfig {
                it.copy(
                    saving = false,
                    options = result.getOrNull()?.get("configOptions")
                        ?.let { options -> parseAcpConfigOptions(options, AppLocaleManager.isEnglish()) } ?: it.options,
                    error = result.exceptionOrNull()?.let { error -> error.message ?: string(cn.com.omnimind.nativeui.R.string.omni_slash_failed) },
                )
            }
        }
    }

    /** A shared setting (Harness, dispatch model) must wait for every turn. */
    private fun anyTurnRunning(): Boolean = coordinator.allSnapshots().any { it.isAiResponding } || sending

    private fun thisTurnRunning(): Boolean = liveSnapshot()?.isAiResponding == true || sending

    /**
     * Chooses a Harness (Flutter `_handleAcpAgentModeShortcutTap`). A
     * conversation keeps the Harness it was created with, so on a page that
     * already has one the choice opens a new conversation there
     * (`buildHarnessSwitchTarget`); on a new page it only changes which
     * Harness the first send uses. Refused while any turn runs: the switch
     * barrier on the Flutter page exists for the same reason.
     */
    fun selectHarness(agentId: String) {
        val target = target ?: return
        if (mutableBar.value.switching) return
        val plan = planHarnessSwitch(
            currentAgentId = target.agentId,
            requestedAgentId = agentId,
            hasConversation = conversationIdOrNull != null,
            anyTurnRunning = anyTurnRunning(),
        )
        when (plan) {
            HarnessSwitchPlan.Ignore -> return
            HarnessSwitchPlan.Busy -> return toast(noticeText(ChatSlashSubmit.Reason.Busy))
            HarnessSwitchPlan.ReplaceTarget, HarnessSwitchPlan.OpenNewConversation -> Unit
        }
        mutableBar.update { it.copy(switching = true) }
        viewModelScope.launch {
            val selected = runCatching { withContext(Dispatchers.IO) { agents.selectAgent(agentId) } }
                .onFailure { Log.w(TAG, "切换 Agent 失败: ${it.message}") }.isSuccess
            mutableBar.update { it.copy(switching = false) }
            if (!selected) {
                toast(string(cn.com.omnimind.nativeui.R.string.omni_slash_failed))
                return@launch
            }
            if (plan == HarnessSwitchPlan.OpenNewConversation) {
                // The route opens a new page; this one keeps its conversation.
                mutableOpenNew.value = System.currentTimeMillis()
                return@launch
            }
            val next = NativeChatComposerTarget.resolve(ConversationModes.AGENT, agentId, liveRuntimeMode = null) ?: return@launch
            applyComposerTarget(next, payload = null)
        }
    }

    fun consumeOpenNewConversation() {
        mutableOpenNew.value = 0L
    }

    // ---------------------------------------------------------------------
    // User message actions (batch 5e-5)
    // ---------------------------------------------------------------------

    fun userMessageActions(messageId: String): List<UserMessageAction> = userMessageActions(
        messages = mutableState.value.messages,
        messageId = messageId,
        isProcessing = mutableComposer.value.isProcessing,
        canSend = target != null && mutableComposer.value.available,
    )

    fun onUserMessageAction(messageId: String, action: UserMessageAction) {
        val message = mutableState.value.messages.firstOrNull { it.id == messageId && it.user == 1 } ?: return
        when (action) {
            UserMessageAction.Copy -> copyText(message.text.orEmpty())
            UserMessageAction.Edit -> startEditing(message)
            UserMessageAction.Retry -> retry(message)
        }
    }

    private fun copyText(text: String) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
        // Android 13+ shows its own confirmation.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            toast(string(cn.com.omnimind.nativeui.R.string.omni_message_copied))
        }
    }

    /** Dart `_startEditingLatestUserMessage`: the composer takes the text; send resends. */
    private fun startEditing(message: ChatMessageUi) {
        mutableComposer.update {
            it.copy(
                editingMessageId = message.id,
                attachments = emptyList(),
                injectedDraft = InjectedDraft(System.nanoTime(), message.text.orEmpty()),
            )
        }
    }

    fun cancelEdit() = mutableComposer.update { it.copy(editingMessageId = null) }

    /**
     * Dart `_saveAndResendEditedUserMessage`: the round from the edited
     * message on is removed (history included) and the edited text is sent
     * as a fresh message with the original attachments.
     */
    private fun resendEdited(messageId: String, text: String): Boolean {
        val message = mutableState.value.messages.firstOrNull { it.id == messageId && it.user == 1 }
        val attachments = message?.attachmentMaps().orEmpty()
        if (message == null || UserMessageAction.Edit !in userMessageActions(messageId)) {
            cancelEdit()
            toast(noticeText(ChatSlashSubmit.Reason.Busy))
            return false
        }
        if (text.isEmpty() && attachments.isEmpty()) return false
        val sent = send(text, attachmentOverride = attachments, beforeLaunch = { id, mode ->
            removeRound(id, mode, messageId, keepUserMessage = false)
        })
        if (sent) cancelEdit()
        return sent
    }

    /**
     * Dart `_retryUserMessage`: the reply after the latest user message is
     * removed and the same submission runs again as a new run, keeping its
     * user row and attachments.
     */
    private fun retry(message: ChatMessageUi) {
        if (UserMessageAction.Retry !in userMessageActions(message.id)) return
        val retained = ChatMessage(id = message.id, type = 1, user = 1, content = message.content)
        send(
            message.text.orEmpty(),
            retained = retained,
            attachmentOverride = message.attachmentMaps(),
            beforeLaunch = { id, mode -> removeRound(id, mode, message.id, keepUserMessage = true) },
        )
    }

    /**
     * Removes the retried round from the runtime and the stored history
     * (Dart `_clearRetriedMessageRound`: `persistConversationMessageSnapshot`
     * with history removal). The runtime is idle here: a retry is refused
     * while a turn runs.
     */
    private suspend fun removeRound(conversationId: Int, mode: String, userMessageId: String, keepUserMessage: Boolean): Boolean {
        val snapshot = coordinator.snapshotFor(conversationId, mode) ?: return false
        if (snapshot.isAiResponding) return false
        val count = retriedRoundRemovalCount(snapshot.messages.map { it.id }, userMessageId, keepUserMessage)
        if (count <= 0) return false
        val remaining = snapshot.messages.drop(count)
        return runCatching {
            coordinator.persistConversationMessageSnapshot(
                conversationId, mode, remaining, conversation = conversationPayload, allowHistoryRemoval = true,
            ).await()
        }.onFailure { Log.w(TAG, "清除重试轮次失败: ${it.message}") }.isSuccess
    }

    private fun ChatMessageUi.attachmentMaps(): List<Map<String, Any?>> =
        (content?.get("attachments") as? List<*>).orEmpty().mapNotNull { item ->
            (item as? Map<*, *>)?.entries?.associate { (key, value) -> key.toString() to value }
        }

    /** Cancels the running turn; its PromptResponse ends it through the reducer. */
    fun cancel() {
        if (conversationIdOrNull == null) return
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
            conversationIdOrNull?.toInt(),
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
        if (conversationIdOrNull == null) return
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
        if (conversationIdOrNull == null) return
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

    /** The route is visible again (re-entered from the drawer or after a configuration change). */
    fun attach() {
        surfaceOpen = true
    }

    /**
     * The user left the route. A turn whose launch has not reached
     * `session/prompt` stops; one already prompting keeps running and ends
     * through its PromptResponse. The ViewModel is activity-scoped, so this
     * is called by the route, not [onCleared] (which runs only when the
     * activity finishes).
     */
    fun detach() {
        surfaceOpen = false
    }

    override fun onCleared() {
        surfaceOpen = false
        coordinator.removeListener(listener)
    }

    class Factory(
        context: Context,
        /** Null opens a new conversation (5e-1). */
        private val conversationId: Long?,
        private val mode: String,
        private val title: String,
        private val draft: String? = null,
    ) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            NativeChatTranscriptViewModel(appContext, conversationId, mode, title, extras.createSavedStateHandle(), draft) as T
    }

    private companion object {
        const val TAG = "NativeChatTranscript"
        const val HISTORY_PAGE = 50
        const val KEY_CONVERSATION_ID = "conversationId"
        const val KEY_DRAFT_ADOPTED = "draftAdopted"
        const val OVERRIDES_KEY = "flutter.conversation_model_overrides_v1"
        const val EFFORTS_KEY = "flutter.conversation_reasoning_efforts_v1"

        /** Dart `_kAgentInitPrompt`, sent for `/init` (shown as `/init`). */
        const val AGENT_INIT_PROMPT = """Please analyze this repository and create or update an AGENTS.md file that acts as a contributor guide for future coding agents.

Include concise, repository-specific guidance for:
- project structure and where important code lives
- build, test, lint, and development commands
- coding conventions and architectural patterns visible in the repo
- testing expectations and any important setup notes

Keep the file practical and avoid generic advice. If AGENTS.md already exists, preserve useful existing guidance and update it with what you learn from the current repository.
"""
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
    onNewConversation: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val bar by viewModel.bar.collectAsStateWithLifecycle()
    val openNew by viewModel.openNewConversation.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(openNew) {
        if (openNew != 0L) {
            viewModel.consumeOpenNewConversation()
            onNewConversation()
        }
    }
    val barActions = remember(viewModel, onNewConversation) {
        ChatPageBarActions(
            onSelectHarness = viewModel::selectHarness,
            onNewConversation = onNewConversation,
            onOpenConfig = viewModel::openConfig,
            onDismissConfig = viewModel::dismissConfig,
            onRefreshConfig = viewModel::refreshConfig,
            onSetConfig = viewModel::setConfig,
        )
    }
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(viewModel) {
        viewModel.attach()
        // A rotation recomposes the same route; only leaving it closes the fence.
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.detach() }
    }
    // Any document; the runtime copies it into the workspace at send time.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addAttachments(uris)
    }
    val composerActions = remember(viewModel) {
        ChatComposerActions(
            onSend = viewModel::submit,
            onCancel = viewModel::cancel,
            onPickAttachment = { picker.launch(arrayOf("*/*")) },
            onRemoveAttachment = viewModel::removeAttachment,
            onSelectPermission = viewModel::selectPermission,
            onCancelEdit = viewModel::cancelEdit,
        )
    }
    val actions = remember(viewModel, onOpenLink, onToolAction) {
        ChatTranscriptActions(
            onOpenLink = onOpenLink,
            onToolAction = onToolAction,
            onRespondToApproval = viewModel::respondToApproval,
            onStopTool = viewModel::stopActiveTool,
            onLoadOlder = viewModel::loadOlderMessages,
            onQuickPrompt = viewModel::fillComposer,
            userMessageActions = viewModel::userMessageActions,
            onUserMessageAction = viewModel::onUserMessageAction,
        )
    }
    ChatTranscriptScreen(state, onBack, actions, composer, composerActions, bar, barActions)
}
