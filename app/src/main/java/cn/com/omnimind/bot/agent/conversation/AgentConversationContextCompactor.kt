package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.contentText
import kotlinx.serialization.encodeToString
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import java.util.concurrent.atomic.AtomicBoolean

interface AgentContextCompactionController {
    suspend fun resolvePromptTokenThreshold(conversationId: Long?): Int

    suspend fun resolveOutputTokenBudget(conversationId: Long?): Int? = null

    suspend fun compactIfNeeded(
        conversationId: Long?,
        conversationMode: String,
        promptTokens: Int?,
        messages: List<ChatCompletionMessage>,
        contextTokens: Int? = null,
        promptTokenThresholdOverride: Int? = null,
        callback: AgentCallback? = null,
        requestOverheadTokens: Int = 0,
        force: Boolean = false
    ): List<ChatCompletionMessage>

}

open class AgentConversationContextCompactor(
    private val historyRepository: AgentConversationHistoryRepository,
    private val modelScene: String = DEFAULT_AGENT_MODEL_SCENE,
    private val modelOverride: AgentModelOverride? = null,
    private val reasoningEffort: String? = null,
    private val promptCacheKey: String? = null,
    private val offloadToolOutput: ((String) -> String)? = null,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
) : AgentContextCompactionController {
    data class CompactionOutcome(
        val compacted: Boolean,
        val summary: String? = null,
        val cutoffEntryDbId: Long? = null,
        val reason: String? = null
    )

    companion object {
        const val DEFAULT_PROMPT_TOKEN_THRESHOLD = 128_000
        private const val MAX_AUTO_COMPACTION_RESERVE_TOKENS = 16_384
        private const val MIN_AUTO_COMPACTION_RESERVE_TOKENS = 2_048
        private const val AUTO_COMPACTION_RESERVE_DIVISOR = 8
        const val DEFAULT_AGENT_MODEL_SCENE = "scene.dispatch.model"
        private const val TAG = "AgentConversationContextCompactor"
        private val EPHEMERAL_CACHE_CONTROL = mapOf("type" to "ephemeral")
        private const val COMPACTION_REQUEST_PROMPT = """
You are a context compaction engine. Your summary will REPLACE the original messages in the conversation context window — the agent will rely on it to continue working. Write the summary in the same language the user used in the conversation.

MUST PRESERVE (never omit or shorten):
- All file paths, directory names, URLs, UUIDs, and identifiers — copy verbatim
- Commands executed and their outcomes (success/failure/output)
- Active tasks: what was requested, what's done, what's still pending
- Key decisions made and their rationale
- Errors encountered and how they were resolved
- Important constraints, rules, or user preferences mentioned
- Any tool calls and their results that affect current state

Use this EXACT checkpoint structure:

## Goal
[The user's intended outcome. Preserve multiple goals when necessary.]

## Constraints & Preferences
- [User requirements, safety boundaries, environment constraints, and preferences]
- [Or "(none)" if none were stated]

## Progress
### Done
- [x] [Completed and verified work]

### In Progress
- [ ] [Work that has started but is not complete]

### Blocked
- [Current blockers, or "(none)"]

## Key Decisions
- **[Decision]**: [Rationale and important rejected alternatives]

## Next Steps
1. [Ordered continuation steps]

## Critical Context
- [Exact paths, commands, errors, tool outcomes, identifiers, and facts needed to continue]
- [Or "(none)" if not applicable]

PRIORITIZE recent context over older history — the agent needs to know what it was doing most recently, not just what was discussed early on.

Do NOT continue the conversation or answer questions inside it. Do NOT translate or alter code snippets, file paths, identifiers, or error messages. Be concise but never lose information the agent needs to continue.
"""
        private const val FINAL_USER_PROMPT =
            "Generate the replacement context summary now."

        internal fun completedSummary(accumulator: AgentLlmStreamAccumulator): String {
            check(accumulator.canFinalizeOnClosed()) { "上下文摘要连接在完成前关闭，未提交检查点。" }
            val turn = accumulator.buildTurn()
            check(turn.finishReason !in setOf("length", "max_tokens", "max_output_tokens")) {
                "上下文摘要因输出限制被截断，未提交检查点。"
            }
            return turn.message.contentText().trim()
        }

        internal fun buildCompactionRequestMessages(
            existingSummary: String?,
            messagesToCompact: List<ChatCompletionMessage>
        ): List<Map<String, Any>> {
            val requestMessages = mutableListOf<Map<String, Any>>()
            requestMessages += mapOf(
                "role" to "system",
                "content" to buildTextContentBlocks(
                    text = COMPACTION_REQUEST_PROMPT.trim(),
                    cacheControl = EPHEMERAL_CACHE_CONTROL
                )
            )
            existingSummary?.trim()?.takeIf { it.isNotEmpty() }?.let { summary ->
                requestMessages += toTransportMessage(
                    AgentConversationHistorySupport.buildContextSummaryAssistantMessage(summary)
                )
            }
            requestMessages += messagesToCompact.map(::toTransportMessage)
            requestMessages += mapOf(
                "role" to "user",
                "content" to FINAL_USER_PROMPT
            )
            return requestMessages
        }

        internal fun resolveAutoCompactionTrigger(contextCapacityTokens: Int): Int {
            val capacity = contextCapacityTokens.coerceAtLeast(1)
            if (capacity == 1) return 1
            val adaptiveReserve = (capacity / AUTO_COMPACTION_RESERVE_DIVISOR)
                .coerceAtLeast(MIN_AUTO_COMPACTION_RESERVE_TOKENS)
                .coerceAtMost(MAX_AUTO_COMPACTION_RESERVE_TOKENS)
            val reserve = adaptiveReserve.coerceAtMost((capacity / 2).coerceAtLeast(1))
            return (capacity - reserve).coerceAtLeast(1)
        }

        internal fun resolveEffectiveContextCapacity(
            storedThreshold: Int?,
            modelContextLimit: Int?
        ): Int {
            val stored = storedThreshold?.takeIf { it > 0 }
            val modelLimit = modelContextLimit?.takeIf { it > 0 }
            return when {
                stored != null && modelLimit != null -> minOf(stored, modelLimit)
                stored != null -> stored
                modelLimit != null -> modelLimit
                else -> DEFAULT_PROMPT_TOKEN_THRESHOLD
            }
        }

        internal fun resolveReportedContextTokens(
            promptTokens: Int?,
            completionTokens: Int?,
            totalTokens: Int?
        ): Int? {
            val reportedTotal = totalTokens?.takeIf { it >= 0 }
            val promptAndCompletion = promptTokens?.takeIf { it >= 0 }?.let { prompt ->
                val completion = completionTokens?.coerceAtLeast(0) ?: 0
                (prompt.toLong() + completion.toLong())
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            }
            return listOfNotNull(reportedTotal, promptAndCompletion).maxOrNull()
        }

        private fun buildTextContentBlocks(
            text: String,
            cacheControl: Map<String, String>? = null
        ): List<Map<String, Any>> {
            val block = linkedMapOf<String, Any>(
                "type" to "text",
                "text" to text
            )
            if (cacheControl != null) {
                block["cache_control"] = cacheControl
            }
            return listOf(block)
        }

        private fun toTransportMessage(message: ChatCompletionMessage): Map<String, Any> {
            val payload = linkedMapOf<String, Any>(
                "role" to message.role
            )
            val content = message.content?.let(::jsonElementToTransportValue)
            if (content != null) {
                payload["content"] = content
            }
            message.toolCalls?.takeIf { it.isNotEmpty() }?.let { toolCalls ->
                payload["tool_calls"] = toolCalls.map(::toolCallToTransportMap)
            }
            message.reasoningContent?.takeIf { it.isNotBlank() }?.let { reasoning ->
                payload["reasoning_content"] = reasoning
            }
            message.toolCallId?.takeIf { it.isNotBlank() }?.let { toolCallId ->
                payload["tool_call_id"] = toolCallId
            }
            message.name?.takeIf { it.isNotBlank() }?.let { name ->
                payload["name"] = name
            }
            return payload
        }

        private fun toolCallToTransportMap(toolCall: AssistantToolCall): Map<String, Any> {
            return linkedMapOf(
                "id" to toolCall.id,
                "type" to toolCall.type,
                "function" to linkedMapOf(
                    "name" to toolCall.function.name,
                    "arguments" to toolCall.function.arguments
                )
            )
        }

        private fun jsonElementToTransportValue(element: JsonElement): Any? {
            return when (element) {
                is JsonPrimitive -> {
                    element.contentOrNull
                        ?: element.booleanOrNull
                        ?: element.toString()
                }

                is JsonArray -> element.mapNotNull(::jsonElementToTransportValue)
                is JsonObject -> element.entries.associate { (key, value) ->
                    key to (jsonElementToTransportValue(value) ?: "")
                }
            }
        }
    }

    private fun resolveModelContextThreshold(): Int? {
        return modelOverride?.contextLimit
            ?.coerceAtLeast(1)
    }

    open override suspend fun resolvePromptTokenThreshold(conversationId: Long?): Int {
        val modelContextLimit = resolveModelContextThreshold()
        if (conversationId == null || conversationId <= 0L) {
            return resolveEffectiveContextCapacity(
                storedThreshold = null,
                modelContextLimit = modelContextLimit
            )
        }
        val conversation = historyRepository.getConversation(conversationId)
        return resolveEffectiveContextCapacity(
            storedThreshold = conversation?.promptTokenThreshold,
            modelContextLimit = modelContextLimit
        )
    }

    override suspend fun resolveOutputTokenBudget(conversationId: Long?): Int {
        val capacity = resolvePromptTokenThreshold(conversationId)
        return (capacity - resolveAutoCompactionTrigger(capacity)).coerceAtLeast(1)
    }

    open override suspend fun compactIfNeeded(
        conversationId: Long?,
        conversationMode: String,
        promptTokens: Int?,
        messages: List<ChatCompletionMessage>,
        contextTokens: Int?,
        promptTokenThresholdOverride: Int?,
        callback: AgentCallback?,
        requestOverheadTokens: Int,
        force: Boolean
    ): List<ChatCompletionMessage> {
        // A caller's preferred budget must never enlarge the model's capacity.
        val capacity = resolveEffectiveContextCapacity(
            storedThreshold = promptTokenThresholdOverride ?: resolvePromptTokenThreshold(conversationId),
            modelContextLimit = resolveModelContextThreshold(),
        )
        val trigger = resolveAutoCompactionTrigger(capacity)
        val estimated = contextTokens ?: AgentContextBudget.estimate(messages, toolTokens = requestOverheadTokens)
        if (conversationId != null && conversationId > 0 && promptTokens != null) {
            historyRepository.updatePromptTokenUsage(conversationId, promptTokens)
        }
        val messageBudget = trigger - requestOverheadTokens
        check(messageBudget > 0) { "工具定义已超过上下文预算，请减少启用的工具。" }
        if (!force && estimated <= trigger) return messages

        val checkpointRevision = conversationId?.takeIf { it > 0 }?.let {
            historyRepository.getConversation(it)?.contextSummaryUpdatedAt
        } ?: 0L
        callback?.onContextCompactionStateChanged(true, promptTokens, capacity)
        try {
            // Gemini CLI chatCompressionService: budget recent tool outputs, keep
            // complete offloaded text retrievable. Original Conversation rows are untouched.
            val bounded = boundToolOutputs(messages, minOf(50_000, messageBudget / 3).coerceAtLeast(1))
            if (bounded != messages && AgentContextBudget.estimate(bounded) <= messageBudget) return bounded
            val keepRecent = minOf(20_000, messageBudget / 2).coerceAtLeast(1)
            val cut = AgentContextBudget.cutPoint(bounded, keepRecent)
                ?: error("上下文超过预算，当前输入没有可安全压缩的已完成片段。请减小本次输入。")
            val prefix = bounded.take(cut).filter { it.role != "system" &&
                !AgentConversationHistorySupport.isContextSummaryMessage(it) }
            check(prefix.isNotEmpty()) { "上下文没有可压缩内容；未发送超限请求。" }
            val previousSummary = bounded.firstOrNull(AgentConversationHistorySupport::isContextSummaryMessage)
                ?.let(AgentConversationHistorySupport::extractContextSummaryText)
            val summary = summarizeWithinBudget(previousSummary, prefix, capacity)
            val rebuilt = AgentContextBudget.rebuild(bounded, cut, summary)
            check(AgentContextBudget.estimate(rebuilt) <= messageBudget) {
                "压缩后上下文仍超过预算；原始历史已保留，未发送超限请求。"
            }
            if (conversationId != null && conversationId > 0) {
                // Resolve the journal boundary only by canonical message identity.
                // The repository awaits that completed group when ACP projection
                // is still committing; a timeout must not claim a durable summary.
                val latestUser = messages.indexOfLast { it.role == "user" }
                val cutoff = if (cut == latestUser) {
                    historyRepository.getContextCompactionCandidate(conversationId, conversationMode)?.cutoffEntryDbId
                } else {
                    historyRepository.findCompactionToolCutoff(conversationId, conversationMode, messages.take(cut))
                }
                if (cutoff != null) historyRepository.updateContextSummary(conversationId, summary, cutoff, checkpointRevision)
            }
            return rebuilt
        } finally {
            callback?.onContextCompactionStateChanged(false, promptTokens, capacity)
        }
    }

    private suspend fun boundToolOutputs(messages: List<ChatCompletionMessage>, budget: Int): List<ChatCompletionMessage> = withContext(Dispatchers.IO) {
        var tokens = 0L
        var currentTool = true
        messages.asReversed().map { message ->
            if (message.role != "tool") return@map message
            val preserveMetadata = currentTool
            currentTool = false
            val size = AgentContextBudget.messageTokens(message)
            if (tokens + size <= budget || offloadToolOutput == null) {
                tokens += size
                return@map message
            }
            val text = message.contentText()
            val path = offloadToolOutput.invoke(text) // An I/O failure must not discard the original result.
            // This output has exhausted the shared recent-output budget. Keeping
            // a per-result excerpt here accumulates without a bound after restore.
            val reference = "Earlier tool output saved in full to $path. Read it with file_read if needed."
            // Keep only the newest result's small control fields, within the same
            // shared budget. Older results do not accumulate per-result previews.
            val metadata = if (preserveMetadata) toolOutputMetadata(text) else null
            val candidate = metadata?.let { "$reference\nCurrent result metadata (body omitted): $it" }
            val notice = candidate?.takeIf {
                tokens + AgentContextBudget.textTokens(it) <= budget
            } ?: reference
            val content = if (message.content is JsonArray) {
                JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(notice)))) +
                    (message.content as JsonArray).filter { (it as? JsonObject)?.get("type") != JsonPrimitive("text") })
            } else JsonPrimitive(notice)
            message.copy(content = content).also { tokens += AgentContextBudget.messageTokens(it) }
        }.asReversed()
    }

    private suspend fun summarizeWithinBudget(existingSummary: String?, messages: List<ChatCompletionMessage>, capacity: Int): String {
        // The user's compaction trigger limits the continuing conversation, not
        // the summarizer's model. A low trigger must not reject history that the
        // configured summary model can still read safely.
        val summaryCapacity = resolveModelContextThreshold() ?: capacity
        val bounded = boundToolOutputs(messages, minOf(50_000, summaryCapacity / 3).coerceAtLeast(1))
        val request = buildCompactionRequestMessages(existingSummary, bounded)
        // Include summary instructions and prior checkpoint in the request budget.
        // Do not issue another known oversized request when the summary input cannot fit.
        val inputTokens = AgentContextBudget.estimate(bounded).toLong() +
            AgentContextBudget.textTokens(COMPACTION_REQUEST_PROMPT) +
            AgentContextBudget.textTokens(existingSummary.orEmpty()) +
            AgentContextBudget.textTokens(FINAL_USER_PROMPT) + 32
        check(inputTokens < resolveAutoCompactionTrigger(summaryCapacity)) {
            "待压缩内容仍超过摘要请求预算；原始历史已保留，请减小输入或使用更大上下文模型。"
        }
        return requestCompactedSummary(request, ((capacity - resolveAutoCompactionTrigger(capacity)) * 0.8).toInt().coerceAtLeast(1)).trim().also {
            check(it.isNotEmpty()) { "上下文压缩未返回有效摘要；未发送原始超限上下文。" }
        }
    }

    open suspend fun compactConversationContext(
        conversationId: Long,
        conversationMode: String
    ): CompactionOutcome {
        val candidate = historyRepository.getContextCompactionCandidate(
            conversationId = conversationId,
            conversationMode = conversationMode
        ) ?: return CompactionOutcome(
            compacted = false,
            reason = "no_candidate"
        )
        val messagesToCompact = AgentConversationHistorySupport.buildPromptRelevantMessages(
            candidate.entriesToCompact
        )
        if (messagesToCompact.isEmpty()) {
            return CompactionOutcome(
                compacted = false,
                reason = "no_prompt_messages"
            )
        }
        return compactAndPersist(
            conversationId = conversationId,
            existingSummary = candidate.conversation.contextSummary,
            messagesToCompact = messagesToCompact,
            cutoffEntryDbId = candidate.cutoffEntryDbId,
            expectedRevision = candidate.conversation.contextSummaryUpdatedAt
        )
    }

    private suspend fun compactAndPersist(
        conversationId: Long,
        existingSummary: String?,
        messagesToCompact: List<ChatCompletionMessage>,
        cutoffEntryDbId: Long,
        expectedRevision: Long
    ): CompactionOutcome {
        if (messagesToCompact.isEmpty()) {
            return CompactionOutcome(
                compacted = false,
                reason = "no_prompt_messages"
            )
        }
        val capacity = resolvePromptTokenThreshold(conversationId)
        val summary = summarizeWithinBudget(existingSummary, messagesToCompact, capacity)
        check(AgentContextBudget.textTokens(summary) < resolveAutoCompactionTrigger(capacity)) {
            "摘要仍超过上下文预算；原始历史已保留。"
        }
        if (summary.isBlank()) {
            return CompactionOutcome(
                compacted = false,
                reason = "blank_summary"
            )
        }
        historyRepository.updateContextSummary(
            conversationId = conversationId,
            summary = summary,
            cutoffEntryDbId = cutoffEntryDbId,
            expectedRevision = expectedRevision
        )
        return CompactionOutcome(
            compacted = true,
            summary = summary,
            cutoffEntryDbId = cutoffEntryDbId
        )
    }

    protected open suspend fun requestCompactedSummary(
        messages: List<Map<String, Any>>,
        maxOutputTokens: Int
    ): String = withContext(Dispatchers.IO) {
        val completed = AtomicBoolean(false)
        val result = CompletableDeferred<String>()
        val accumulator = AgentLlmStreamAccumulator(json)
        var eventSource: EventSource? = null

        fun completeStream(source: EventSource? = null) {
            if (!completed.compareAndSet(false, true)) return
            runCatching {
                completedSummary(accumulator)
            }.onSuccess { summary ->
                result.complete(summary)
            }.onFailure { error ->
                result.completeExceptionally(error)
            }
            source?.cancel()
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (completed.get()) return
                runCatching {
                    val done = accumulator.consume(data)
                    if (done) {
                        completeStream(eventSource)
                    }
                }.onFailure { error ->
                    if (completed.compareAndSet(false, true)) {
                        result.completeExceptionally(error)
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                completeStream(eventSource)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (!completed.compareAndSet(false, true)) return
                val reason = buildString {
                    append(t?.message?.trim().orEmpty())
                    val responseBody = runCatching { response?.body?.string() }.getOrNull()
                        ?.trim()
                        .orEmpty()
                    if (responseBody.isNotEmpty()) {
                        if (isNotEmpty()) append(" ")
                        append(responseBody.take(500))
                    }
                }.trim().ifEmpty { "unknown compaction stream failure" }
                result.completeExceptionally(IllegalStateException(reason))
            }
        }

        try {
            eventSource = HttpController.postLLMStreamRequestWithContextAsFlow(
                model = modelScene,
                messages = messages,
                event = listener,
                enableThinking = false,
                explicitApiBase = modelOverride?.apiBase,
                explicitApiKey = modelOverride?.apiKey,
                explicitCustomHeaders = modelOverride?.customHeaders,
                explicitModel = modelOverride?.modelId,
                explicitProtocolType = modelOverride?.protocolType,
                explicitWireApi = modelOverride?.wireApi,
                reasoningEffort = reasoningEffort,
                promptCacheKey = promptCacheKey,
                maxCompletionTokens = maxOutputTokens,
            )
            result.await()
        } finally {
            eventSource?.cancel()
        }
    }

}
