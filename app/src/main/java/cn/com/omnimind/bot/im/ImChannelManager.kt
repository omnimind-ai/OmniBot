package cn.com.omnimind.bot.im

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.webchat.RealtimeEvent
import cn.com.omnimind.bot.webchat.RealtimeHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object ImChannelManager {
    private const val TAG = "[ImChannelManager]"
    private const val TYPING_REFRESH_MS = 2_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingRuns = ConcurrentHashMap<String, PendingImRun>()
    // 每个 IM 任务下，按 agent 文本流的 entryId（每一轮一个）维护已发送进度。
    // 每条 text_snapshot 事件携带的是该轮累积文本，这里用它做"边写边推"：
    // 用句末标点切片，把新出现的完整句子立刻推给 IM，剩下未完句子等下一帧。
    private val agentTextStreams =
        ConcurrentHashMap<String, ImAgentTaskStreamState>()
    private val typingJobs = ConcurrentHashMap<String, Job>()
    private val telegramConnector = TelegramImConnector()
    private val discordConnector = DiscordImConnector()
    private val wechatConnector = OpenILinkWechatConnector { token, baseUrl, botId ->
        appContext?.let { context ->
            ImChannelStore(context).saveWechatCredentials(token, baseUrl)
            ImChannelForegroundService.ensureState(context)
        }
        if (!botId.isNullOrBlank()) {
            OmniLog.d(TAG, "OpeniLink QR login connected: $botId")
        }
    }
    private val connectors: Map<ImChannelType, ImConnector> = mapOf(
        ImChannelType.TELEGRAM to telegramConnector,
        ImChannelType.WECHAT to wechatConnector,
        ImChannelType.DISCORD to discordConnector
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var store: ImChannelStore? = null

    @Volatile
    private var processor: ImCommandProcessor? = null

    private var realtimeJob: Job? = null

    fun restoreIfEnabled(context: Context) {
        val settings = ImChannelStore(context).loadSettings()
        if (settings.anyEnabled()) {
            ImChannelForegroundService.ensureState(context)
        }
    }

    fun start(context: Context) {
        scope.launch {
            reload(context)
        }
    }

    fun stop() {
        scope.launch {
            connectors.values.forEach { connector ->
                runCatching { connector.stop() }
            }
            pendingRuns.clear()
            agentTextStreams.clear()
            typingJobs.values.forEach { it.cancel() }
            typingJobs.clear()
            realtimeJob?.cancel()
            realtimeJob = null
        }
    }

    suspend fun currentState(context: Context): Map<String, Any?> {
        ensureInitialized(context)
        return buildState()
    }

    suspend fun reload(context: Context): Map<String, Any?> {
        ensureInitialized(context)
        val settings = requireStore().loadSettings()
        connectors.values.forEach { connector ->
            runCatching {
                connector.start(settings, ::handleInboundMessage)
            }.onFailure { error ->
                OmniLog.e(TAG, "connector ${connector.channel.id} start failed: ${error.message}")
            }
        }
        return buildState()
    }

    suspend fun saveTelegram(context: Context, config: TelegramImConfig): Map<String, Any?> {
        ensureInitialized(context)
        requireStore().saveTelegram(config)
        val state = reload(context)
        ImChannelForegroundService.ensureState(context)
        return state
    }

    suspend fun saveWechat(context: Context, config: WechatImConfig): Map<String, Any?> {
        ensureInitialized(context)
        requireStore().saveWechat(config)
        val state = reload(context)
        ImChannelForegroundService.ensureState(context)
        return state
    }

    suspend fun setChannelEnabled(
        context: Context,
        channel: ImChannelType,
        enabled: Boolean
    ): Map<String, Any?> {
        ensureInitialized(context)
        requireStore().setChannelEnabled(channel, enabled)
        val state = reload(context)
        ImChannelForegroundService.ensureState(context)
        return state
    }

    suspend fun requestWechatQr(context: Context): Map<String, Any?> {
        ensureInitialized(context)
        val result = wechatConnector.requestQr()
        return result + mapOf("state" to buildState())
    }

    suspend fun clearPeerSessions(context: Context): Map<String, Any?> {
        ensureInitialized(context)
        requireStore().clearPeerSessions()
        return buildState()
    }

    private fun ensureInitialized(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) {
                    val applicationContext = context.applicationContext
                    appContext = applicationContext
                    store = ImChannelStore(applicationContext)
                    processor = ImCommandProcessor(
                        applicationContext,
                        requireStore(),
                        ::buildStatusText,
                        ::activatePendingRun
                    )
                    ensureRealtimeCollection()
                }
            }
        } else {
            ensureRealtimeCollection()
        }
    }

    private fun ensureRealtimeCollection() {
        if (realtimeJob?.isActive == true) return
        realtimeJob = scope.launch {
            RealtimeHub.stream().collect { event ->
                handleRealtimeEvent(event)
            }
        }
    }

    private suspend fun handleInboundMessage(inbound: ImInboundMessage) {
        val activeProcessor = processor ?: return
        val warmupTypingJob = if (inbound.text.trimStart().startsWith("/")) {
            null
        } else {
            startTransientTypingIndicator(inbound.channel, inbound.peerId)
        }
        try {
            val result = runCatching { activeProcessor.handle(inbound) }
                .getOrElse { error ->
                    ImProcessorResult(
                        replies = listOf("处理 IM 消息失败：${error.message ?: error.javaClass.simpleName}")
                    )
                }
            result.pendingRun?.let(::activatePendingRun)
            result.finishedTaskId?.let {
                pendingRuns.remove(it)
                agentTextStreams.remove(it)
                stopTypingIndicator(it)
            }
            result.replies.forEach { reply ->
                sendChunked(inbound.channel, inbound.peerId, reply)
            }
        } finally {
            warmupTypingJob?.cancel()
        }
    }

    private suspend fun handleRealtimeEvent(event: RealtimeEvent) {
        if (event.event != "agent_stream_event") return
        val taskId = event.data["taskId"]?.toString()?.takeIf { it.isNotBlank() } ?: return
        val pending = pendingRuns[taskId] ?: return
        val kind = event.data["kind"]?.toString().orEmpty()
        val isFinal = event.data["isFinal"] == true
        when (kind) {
            "text_snapshot" -> {
                // 每条 text_snapshot 都是该轮的累积文本，不论中间轮还是最终轮，
                // 全部按"完整句子"边界切片下发。不再用 isFinal 终止 pending run
                // —— 多轮任务里每一轮都会有 isFinal=true，等真正的 completed 事件。
                val entryId = event.data["entryId"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: return
                val text = event.data["text"]?.toString().orEmpty()
                streamAgentText(pending, taskId, entryId, text, isFinal)
            }

            "clarify_required" -> {
                // 询问补充信息前，先把这一轮没来得及发出的尾巴吐完。
                flushAgentTextStreams(pending, taskId)
                stopTypingIndicator(taskId)
                val question = sequenceOf(
                    event.data["question"]?.toString(),
                    event.data["text"]?.toString()
                ).firstOrNull { !it.isNullOrBlank() } ?: "需要补充信息，请直接回复。"
                requireStore().markAwaitingInput(taskId, awaitingInput = true)
                sendChunked(
                    pending.channel,
                    pending.peerId,
                    "$question\n\n请直接回复补充信息，或发送 /cancel 取消。"
                )
            }

            "permission_required" -> {
                flushAgentTextStreams(pending, taskId)
                val text = sequenceOf(
                    event.data["text"]?.toString(),
                    event.data["error"]?.toString()
                ).firstOrNull { !it.isNullOrBlank() } ?: "执行前需要回到 App 开启相关权限。"
                sendChunked(pending.channel, pending.peerId, text)
                agentTextStreams.remove(taskId)
                finishPendingRun(taskId)
            }

            "error" -> {
                flushAgentTextStreams(pending, taskId)
                val text = sequenceOf(
                    event.data["error"]?.toString(),
                    event.data["text"]?.toString()
                ).firstOrNull { !it.isNullOrBlank() } ?: "任务执行失败。"
                sendChunked(pending.channel, pending.peerId, "任务失败：$text")
                agentTextStreams.remove(taskId)
                finishPendingRun(taskId)
            }

            "completed" -> {
                flushAgentTextStreams(pending, taskId)
                val hasOutput = agentTextStreams[taskId]?.hasSentAny == true
                if (!hasOutput) {
                    sendChunked(
                        pending.channel,
                        pending.peerId,
                        "任务已完成，但没有产生文本输出。"
                    )
                }
                agentTextStreams.remove(taskId)
                finishPendingRun(taskId)
            }
        }
    }

    /**
     * 把每一轮 assistant 文本（一个 entryId / content 字段）作为一条独立 IM
     * 消息按时序下发。
     *
     * 关键事实：AgentOrchestrator 在流式过程中只会用 isFinal=false 推 token，
     * 真正的 isFinal=true 只在两个地方触发：
     *  1) "无 tool_call 终止"分支 —— 最后一轮的兜底；
     *  2) onComplete —— 用最后一轮的 entryId 再发一次完整 finalText。
     * 所以中间轮**永远不会**自然收到 isFinal=true。如果只靠 isFinal 判定，
     * 会出现"最后一轮先到、中间轮在 completed 时才一并 flush"导致 IM 端
     * 看到的顺序倒置（最终答案先于过程内容）。
     *
     * 解决方法：用 entryId 的切换作为前一轮 finalize 的信号 —— 当一条
     * text_snapshot 上的 entryId 不再是当前 active entryId 时，意味着 agent
     * 已经进入下一轮，把上一轮缓存到的最新文本立刻作为一条 bubble 发掉。
     */
    private suspend fun streamAgentText(
        pending: PendingImRun,
        taskId: String,
        entryId: String,
        rawText: String,
        isFinal: Boolean
    ) {
        val taskState = agentTextStreams.getOrPut(taskId) { ImAgentTaskStreamState() }

        val prevActiveEntryId = taskState.activeEntryId
        if (prevActiveEntryId != null && prevActiveEntryId != entryId) {
            // 新一轮开始：上一轮的累积文本已定型，立刻 finalize 发出。
            finalizeAgentTextEntry(pending, taskState, prevActiveEntryId)
        }
        taskState.activeEntryId = entryId

        val entryState = taskState.byEntry.getOrPut(entryId) { ImAgentEntryStreamState() }
        if (!entryState.done) {
            val sanitized = rawText.trim()
            // 防御：上游偶发的回退快照（中途出现比之前更短的文本），保留较长版本。
            if (sanitized.length >= entryState.latestText.length) {
                entryState.latestText = sanitized
            }
        }

        if (isFinal) {
            // 最后一轮（或 onComplete 路径）的明确 finalize 信号。
            finalizeAgentTextEntry(pending, taskState, entryId)
        }
    }

    private suspend fun finalizeAgentTextEntry(
        pending: PendingImRun,
        taskState: ImAgentTaskStreamState,
        entryId: String
    ) {
        val entryState = taskState.byEntry[entryId] ?: return
        if (entryState.done) return
        entryState.done = true
        val text = entryState.latestText
        if (text.isNotEmpty()) {
            sendChunked(pending.channel, pending.peerId, text)
            taskState.hasSentAny = true
        }
    }

    /**
     * 在 completed / error / permission_required / clarify_required 之前调用，
     * 把当前任务下还没 finalize 的所有 entry 按出现顺序补发完。正常时序下，
     * 只剩最后一轮还没被 entryId 切换信号推走 —— 它会在这里被发掉。
     */
    private suspend fun flushAgentTextStreams(pending: PendingImRun, taskId: String) {
        val taskState = agentTextStreams[taskId] ?: return
        // MutableMap 在我们这个调用路径下保留插入顺序（Kotlin 默认 LinkedHashMap）。
        for (entryId in taskState.byEntry.keys.toList()) {
            finalizeAgentTextEntry(pending, taskState, entryId)
        }
    }

    private fun finishPendingRun(taskId: String) {
        pendingRuns.remove(taskId)
        stopTypingIndicator(taskId)
        requireStore().clearActiveTask(taskId)
    }

    private fun activatePendingRun(pending: PendingImRun) {
        pendingRuns[pending.taskId] = pending
        if (typingJobs[pending.taskId]?.isActive != true) {
            startTypingIndicator(pending)
        }
    }

    private fun startTransientTypingIndicator(
        channel: ImChannelType,
        peerId: String
    ): Job? {
        val connector = connectors[channel] ?: return null
        return scope.launch {
            while (true) {
                sendTypingSafely(connector, channel, peerId)
                delay(TYPING_REFRESH_MS)
            }
        }
    }

    private fun startTypingIndicator(pending: PendingImRun) {
        stopTypingIndicator(pending.taskId)
        val connector = connectors[pending.channel] ?: return
        typingJobs[pending.taskId] = scope.launch {
            while (pendingRuns.containsKey(pending.taskId)) {
                sendTypingSafely(connector, pending.channel, pending.peerId)
                delay(TYPING_REFRESH_MS)
            }
        }
    }

    private fun stopTypingIndicator(taskId: String) {
        typingJobs.remove(taskId)?.cancel()
    }

    private suspend fun sendTypingSafely(
        connector: ImConnector,
        channel: ImChannelType,
        peerId: String
    ) {
        runCatching {
            connector.sendTyping(peerId)
        }.onFailure { error ->
            OmniLog.w(TAG, "send ${channel.id} typing failed: ${error.message}")
        }
    }

    private data class ImAgentEntryStreamState(
        var latestText: String = "",
        var done: Boolean = false
    )

    private data class ImAgentTaskStreamState(
        val byEntry: MutableMap<String, ImAgentEntryStreamState> = mutableMapOf(),
        var activeEntryId: String? = null,
        var hasSentAny: Boolean = false
    )

    private suspend fun sendChunked(
        channel: ImChannelType,
        peerId: String,
        text: String
    ) {
        val connector = connectors[channel] ?: return
        val chunkSize = requireStore().loadSettings().chunkSizeFor(channel)
        val chunks = splitForIm(text, chunkSize)
        chunks.forEachIndexed { index, chunk ->
            runCatching {
                connector.sendText(peerId, chunk)
            }.onFailure { error ->
                OmniLog.e(TAG, "send ${channel.id} chunk failed: ${error.message}")
            }
            if (index < chunks.lastIndex) {
                delay(250)
            }
        }
    }

    private fun splitForIm(text: String, maxChars: Int): List<String> {
        val normalized = text.ifBlank { " " }
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < normalized.length) {
            var end = (start + maxChars).coerceAtMost(normalized.length)
            if (end < normalized.length && Character.isHighSurrogate(normalized[end - 1])) {
                end -= 1
            }
            if (end <= start) {
                end = (start + maxChars).coerceAtMost(normalized.length)
            }
            chunks += normalized.substring(start, end)
            start = end
        }
        return chunks.ifEmpty { listOf(" ") }
    }

    private fun buildState(): Map<String, Any?> {
        val activeStore = requireStore()
        val settings = activeStore.loadSettings()
        val connectorStatuses = connectors.values.map { it.currentStatus() }
        return linkedMapOf(
            "settings" to settings.toMap(),
            "status" to linkedMapOf(
                "running" to connectorStatuses.any { it.running },
                "pendingRunCount" to pendingRuns.size,
                "sessionCount" to activeStore.listSessions().size,
                "connectors" to connectorStatuses.map { it.toMap() }
            ),
            "sessions" to activeStore.listSessions().map { it.toMap() }
        )
    }

    private fun buildStatusText(
        inbound: ImInboundMessage,
        session: ImPeerSession?
    ): String {
        val activeStore = requireStore()
        val connectorText = connectors.values.joinToString("\n") { connector ->
            val state = connector.currentStatus()
            val connected = if (state.connected) "connected" else "disconnected"
            val running = if (state.running) "running" else "stopped"
            val error = state.lastError.takeIf { it.isNotBlank() }?.let { " error=$it" }.orEmpty()
            "${state.channel.title}: $running/$connected$error"
        }
        val current = session?.let {
            "session: mode=${imModeLabel(it.mode)} conversationId=${it.conversationId}" +
                it.activeTaskId?.let { taskId -> " activeTask=$taskId" }.orEmpty()
        } ?: "session: none"
        return """
            IM 状态
            peer: ${inbound.channel.id}/${inbound.peerId}
            $current
            pendingRuns: ${pendingRuns.size}
            savedSessions: ${activeStore.listSessions().size}
            $connectorText
        """.trimIndent()
    }

    private fun requireStore(): ImChannelStore {
        return store ?: throw IllegalStateException("IM channel store not initialized")
    }
}
