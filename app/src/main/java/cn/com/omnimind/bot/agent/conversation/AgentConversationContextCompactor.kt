package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
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

    suspend fun compactIfNeeded(
        conversationId: Long?,
        conversationMode: String,
        promptTokens: Int?,
        messages: List<ChatCompletionMessage>,
        contextTokens: Int? = null,
        promptTokenThresholdOverride: Int? = null,
        callback: AgentCallback? = null
    ): List<ChatCompletionMessage>

}

open class AgentConversationContextCompactor(
    private val historyRepository: AgentConversationHistoryRepository,
    private val modelScene: String = DEFAULT_AGENT_MODEL_SCENE,
    private val modelOverride: AgentModelOverride? = null,
    private val reasoningEffort: String? = null,
    private val promptCacheKey: String? = null,
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

    open override suspend fun compactIfNeeded(
        conversationId: Long?,
        conversationMode: String,
        promptTokens: Int?,
        messages: List<ChatCompletionMessage>,
        contextTokens: Int?,
        promptTokenThresholdOverride: Int?,
        callback: AgentCallback?
    ): List<ChatCompletionMessage> {
        if (conversationId == null || conversationId <= 0L) {
            return messages
        }
        val normalizedPromptTokens = promptTokens ?: return messages
        val promptTokenThreshold = promptTokenThresholdOverride?.coerceAtLeast(1)
            ?: resolvePromptTokenThreshold(conversationId)
        val autoCompactionTrigger = resolveAutoCompactionTrigger(promptTokenThreshold)
        val normalizedContextTokens = contextTokens
            ?.coerceAtLeast(normalizedPromptTokens)
            ?: normalizedPromptTokens
        historyRepository.updatePromptTokenUsage(
            conversationId = conversationId,
            promptTokens = normalizedPromptTokens,
            threshold = promptTokenThreshold
        )
        if (normalizedContextTokens <= autoCompactionTrigger) {
            return messages
        }
        val candidate = historyRepository.getContextCompactionCandidate(
            conversationId = conversationId,
            conversationMode = conversationMode
        ) ?: return messages
        val runtimeWindow = AgentConversationHistorySupport.buildRuntimeCompactionWindow(messages)
            ?: return messages

        OmniLog.i(
            TAG,
            "conversation=$conversationId auto_compaction context=$normalizedContextTokens " +
                "trigger=$autoCompactionTrigger capacity=$promptTokenThreshold"
        )
        return compactRuntimeWindow(
            conversationId = conversationId,
            candidate = candidate,
            runtimeWindow = runtimeWindow,
            originalMessages = messages,
            latestPromptTokens = normalizedPromptTokens,
            promptTokenThreshold = promptTokenThreshold,
            callback = callback
        ) ?: messages
    }

    private suspend fun compactRuntimeWindow(
        conversationId: Long,
        candidate: AgentConversationHistoryRepository.ContextCompactionCandidate,
        runtimeWindow: AgentConversationHistorySupport.RuntimeCompactionWindow,
        originalMessages: List<ChatCompletionMessage>,
        latestPromptTokens: Int,
        promptTokenThreshold: Int,
        callback: AgentCallback?
    ): List<ChatCompletionMessage>? {

        callback?.onContextCompactionStateChanged(
            isCompacting = true,
            latestPromptTokens = latestPromptTokens,
            promptTokenThreshold = promptTokenThreshold
        )
        try {
            return runCatching {
                val outcome = compactAndPersist(
                    conversationId = conversationId,
                    existingSummary = runtimeWindow.existingSummary
                        ?: candidate.conversation.contextSummary,
                    messagesToCompact = runtimeWindow.messagesToCompact,
                    cutoffEntryDbId = candidate.cutoffEntryDbId
                )
                val summary = outcome.summary.orEmpty()
                if (!outcome.compacted || summary.isBlank()) {
                    OmniLog.w(TAG, "conversation=$conversationId compaction returned blank summary")
                    null
                } else {
                    AgentConversationHistorySupport.rebuildMessagesWithCompactedSummary(
                        messages = originalMessages,
                        summary = summary
                    )
                }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                OmniLog.w(
                    TAG,
                    "conversation=$conversationId compaction failed: ${error.message}"
                )
                null
            }
        } finally {
            callback?.onContextCompactionStateChanged(
                isCompacting = false,
                latestPromptTokens = latestPromptTokens,
                promptTokenThreshold = promptTokenThreshold
            )
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
            cutoffEntryDbId = candidate.cutoffEntryDbId
        )
    }

    private suspend fun compactAndPersist(
        conversationId: Long,
        existingSummary: String?,
        messagesToCompact: List<ChatCompletionMessage>,
        cutoffEntryDbId: Long
    ): CompactionOutcome {
        if (messagesToCompact.isEmpty()) {
            return CompactionOutcome(
                compacted = false,
                reason = "no_prompt_messages"
            )
        }
        val requestMessages = buildCompactionRequestMessages(
            existingSummary = existingSummary,
            messagesToCompact = messagesToCompact
        )
        val summary = requestCompactedSummary(requestMessages)
        if (summary.isBlank()) {
            return CompactionOutcome(
                compacted = false,
                reason = "blank_summary"
            )
        }
        historyRepository.updateContextSummary(
            conversationId = conversationId,
            summary = summary,
            cutoffEntryDbId = cutoffEntryDbId
        )
        return CompactionOutcome(
            compacted = true,
            summary = summary,
            cutoffEntryDbId = cutoffEntryDbId
        )
    }

    protected open suspend fun requestCompactedSummary(
        messages: List<Map<String, Any>>
    ): String = withContext(Dispatchers.IO) {
        val completed = AtomicBoolean(false)
        val result = CompletableDeferred<String>()
        val accumulator = AgentLlmStreamAccumulator(json)
        var eventSource: EventSource? = null

        fun completeStream(source: EventSource? = null) {
            if (!completed.compareAndSet(false, true)) return
            runCatching {
                accumulator.buildTurn().message.contentText().trim()
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
                promptCacheKey = promptCacheKey
            )
            result.await()
        } finally {
            eventSource?.cancel()
        }
    }

}
