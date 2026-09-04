package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AnthropicMessageProtocolState
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionProtocolState
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.tool.AgentToolConcurrencyPolicy
import cn.com.omnimind.bot.agent.tool.handlers.ToolSearchHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

private val CONTEXT_OVERFLOW_PATTERNS = listOf(
    Regex("prompt is too long", RegexOption.IGNORE_CASE),
    Regex("request_too_large", RegexOption.IGNORE_CASE),
    Regex("input is too long for requested model", RegexOption.IGNORE_CASE),
    Regex("exceeds (?:the )?context window", RegexOption.IGNORE_CASE),
    Regex("exceeds (?:the )?(?:model'?s )?maximum context length", RegexOption.IGNORE_CASE),
    Regex("input token count.*exceeds the maximum", RegexOption.IGNORE_CASE),
    Regex("maximum prompt length is \\d+", RegexOption.IGNORE_CASE),
    Regex("reduce the length of the messages", RegexOption.IGNORE_CASE),
    Regex("maximum context length is \\d+ tokens", RegexOption.IGNORE_CASE),
    Regex("exceeds (?:the )?maximum allowed input length", RegexOption.IGNORE_CASE),
    Regex("exceeds the available context size", RegexOption.IGNORE_CASE),
    Regex("greater than the context length", RegexOption.IGNORE_CASE),
    Regex("context window exceeds limit", RegexOption.IGNORE_CASE),
    Regex("exceeded model token limit", RegexOption.IGNORE_CASE),
    Regex("too large for model with \\d+ maximum context length", RegexOption.IGNORE_CASE),
    Regex("model_context_window_exceeded", RegexOption.IGNORE_CASE),
    Regex("prompt too long; exceeded (?:max )?context length", RegexOption.IGNORE_CASE),
    Regex("range of input length should be", RegexOption.IGNORE_CASE),
    Regex("context[_ ]length[_ ]exceeded", RegexOption.IGNORE_CASE),
    Regex("too many tokens", RegexOption.IGNORE_CASE),
    Regex("token limit exceeded", RegexOption.IGNORE_CASE)
)

private val NON_CONTEXT_OVERFLOW_PATTERNS = listOf(
    Regex("rate limit", RegexOption.IGNORE_CASE),
    Regex("too many requests", RegexOption.IGNORE_CASE),
    Regex("throttl", RegexOption.IGNORE_CASE)
)

internal fun isContextOverflowTurnFailure(error: Throwable): Boolean {
    if (error !is AgentStreamRequestException) return false
    val failureText = buildString {
        append(error.reason)
        error.responseBody?.takeIf { it.isNotBlank() }?.let { body ->
            append(' ')
            append(body)
        }
    }
    if (NON_CONTEXT_OVERFLOW_PATTERNS.any { it.containsMatchIn(failureText) }) {
        return false
    }
    return CONTEXT_OVERFLOW_PATTERNS.any { it.containsMatchIn(failureText) }
}

internal fun isLengthStopAtContextCapacity(
    finishReason: String?,
    promptTokens: Int?,
    completionTokens: Int?,
    contextCapacityTokens: Int?
): Boolean {
    val normalizedReason = finishReason?.trim()?.lowercase().orEmpty()
    val isLengthStop = normalizedReason == "length" ||
        normalizedReason == "max_tokens" ||
        normalizedReason == "max_completion_tokens"
    if (!isLengthStop || completionTokens != 0) return false
    val prompt = promptTokens?.takeIf { it >= 0 } ?: return false
    val capacity = contextCapacityTokens?.takeIf { it > 0 } ?: return false
    return prompt.toLong() * 100L >= capacity.toLong() * 99L
}

class AgentOrchestrator(
    private val llmClient: AgentLlmClient,
    private val toolRegistry: AgentToolCatalog,
    private val toolRouter: AgentToolExecutor,
    private val eventAdapter: AgentEventAdapter,
    private val model: String,
    private val toolImageContinuationPolicy: AgentToolImageContinuationPolicy =
        AgentToolImageContinuationPolicy.DEFAULT,
    /**
     * A child orchestrator may borrow a parent's router. Only the owner may
     * release the handlers and their process/session resources.
     */
    private val ownsToolRouter: Boolean = true
) {
    private data class RetryDecision(
        val retryable: Boolean,
        val reason: String
    )

    private class TerminalTurnRequestFailure(
        val errorMessage: String,
        cause: Throwable
    ) : RuntimeException(errorMessage, cause)

    private class ContextOverflowTurnFailure(
        val errorMessage: String,
        cause: Throwable
    ) : RuntimeException(errorMessage, cause)

    private data class SkillCompletionPolicy(
        val skillId: String,
        val startTools: Set<String>,
        val completionTools: List<String>
    )

    private data class PendingSkillCompletion(
        val policy: SkillCompletionPolicy,
        val nextTool: String
    )

    data class Input(
        val callback: AgentCallback,
        val initialMessages: List<ChatCompletionMessage>,
        val executionEnv: AgentExecutionEnvironment,
        val conversationId: Long? = null,
        val promptCacheKey: String? = null,
        val contextCompactor: AgentContextCompactionController? = null,
        val maxModelRounds: Int? = null,
        val maxCompletionTokens: Int? = null
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val tag = "AgentOrchestrator"
    private data class TurnUsage(
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val totalTokens: Int? = null,
        val cachedTokens: Int? = null,
        val cacheCreationTokens: Int? = null
    )

    private fun t(zh: String, en: String): String {
        return if (AppLocaleManager.isEnglish()) en else zh
    }

    private fun logPromptCacheFingerprints(
        messages: List<ChatCompletionMessage>,
        tools: List<ChatCompletionTool>
    ) {
        val latestIndex = messages.lastIndex
        fun encodedMessage(index: Int): String {
            return messages.getOrNull(index)?.let { json.encodeToString(it) }.orEmpty()
        }
        val history = messages
            .drop(2)
            .dropLast(if (messages.size > 2) 1 else 0)
            .joinToString(separator = "\u001e") { json.encodeToString(it) }
        logInfo(
            tag,
            "cache_prefix_fingerprint " +
                "system=${shortFingerprint(encodedMessage(0))} " +
                "time=${shortFingerprint(encodedMessage(1))} " +
                "tools=${shortFingerprint(json.encodeToString(tools))} " +
                "history=${shortFingerprint(history)} " +
                "latest=${shortFingerprint(encodedMessage(latestIndex))} " +
                "messages=${messages.size} tools_count=${tools.size}"
        )
    }

    private fun shortFingerprint(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(12)
    }

    private fun contextStateFingerprint(messages: List<ChatCompletionMessage>): String {
        return shortFingerprint(json.encodeToString(messages))
    }

    private fun resolveTurnUsage(turn: ChatCompletionTurn): TurnUsage {
        val usage = turn.usage
        val promptTokens = usage?.promptTokens
        val completionTokens = usage?.completionTokens
        val totalTokens = usage?.totalTokens
        val cachedTokens = usage?.promptTokensDetails
            ?.let { detail ->
                (detail as? kotlinx.serialization.json.JsonObject)
                    ?.get("cached_tokens")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toIntOrNull()
            }
        val cacheCreationTokens = usage?.promptTokensDetails
            ?.let { detail ->
                (detail as? kotlinx.serialization.json.JsonObject)
                    ?.get("cache_creation_tokens")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toIntOrNull()
            }
        return TurnUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens,
            cacheCreationTokens = cacheCreationTokens
        )
    }

    suspend fun run(input: Input): AgentResult {
        val callback = input.callback
        val memory: AgentChatMemory = MutableListChatMemory(input.initialMessages)
        val completionPolicies = resolveSkillCompletionPolicies(input.executionEnv.resolvedSkills)
        // Keep this as an explicit loop instead of the inline `mapTo` call.
        // This code runs inside the ACP request coroutine and can be resumed
        // while a counterpart sends $/cancelRequest.  The generated inline
        // collection bridge is needlessly fragile on Android/R8 in that
        // cancellation path; the mutable set is also clearer about the
        // de-duplication contract used by tool-choice recovery.
        val availableToolNames = linkedSetOf<String>()
        for (tool in toolRegistry.toolsForModel) {
            availableToolNames += tool.function.name
        }
        val completionProgress = mutableMapOf<String, Int>()
        val completionRecoveryRounds = mutableMapOf<String, Int>()
        val executedTools = mutableListOf<ToolExecutionResult>()
        var outputKind = AgentOutputKind.NONE
        var hasUserFacingOutput = false
        var lastAssistantContent = ""
        var accumulatedAssistantContent = ""
        var lastFinishReason: String? = null
        var latestPromptTokens: Int? = null
        var latestPromptTokenThreshold: Int? = null
        var lastTurnUsage: TurnUsage? = null
        var lastPrefillTokensPerSecond: Double? = null
        var lastDecodeTokensPerSecond: Double? = null
        var completedModelRounds = 0
        var lengthContinuationRounds = 0
        var contextOverflowRecoveryRounds = 0
        val contextCompactionStates = mutableSetOf(
            contextStateFingerprint(input.initialMessages)
        )
        var terminated = false
        var terminalError: AgentResult.Error? = null

        suspend fun compactContextForOverflow(): Boolean {
            val settings = input.executionEnv.runtimeSettings
            val maxRecoveryRounds = settings.maxContextOverflowRecoveryRounds
            if (maxRecoveryRounds != null &&
                contextOverflowRecoveryRounds >= maxRecoveryRounds
            ) {
                return false
            }
            val compacted = input.contextCompactor?.compactForOverflow(
                conversationId = input.conversationId,
                conversationMode = input.executionEnv.conversationMode,
                latestPromptTokens = latestPromptTokens,
                messages = memory.snapshot(),
                promptTokenThresholdOverride = latestPromptTokenThreshold,
                callback = callback
            ) ?: return false
            if (!contextCompactionStates.add(contextStateFingerprint(compacted))) {
                logInfo(tag, "context_overflow compacted=false reason=no_progress")
                return false
            }
            memory.replaceAll(compacted)
            contextOverflowRecoveryRounds += 1
            completedModelRounds = (completedModelRounds - 1).coerceAtLeast(0)
            logInfo(
                tag,
                "context_overflow compacted=true " +
                    "retry=$contextOverflowRecoveryRounds/${maxRecoveryRounds ?: "unlimited"}"
            )
            return true
        }

        try {
            roundLoop@ while (true) {
                val settings = input.executionEnv.runtimeSettings
                val maxModelRounds = (input.maxModelRounds ?: settings.maxModelRounds)
                    ?.takeIf { it > 0 }
                if (maxModelRounds != null && completedModelRounds >= maxModelRounds) {
                    val message = t(
                        "Agent 已达到 $maxModelRounds 轮模型调用上限。",
                        "Agent reached the $maxModelRounds-round model-call limit."
                    )
                    callback.onError(message, false)
                    terminalError = AgentResult.Error(message)
                    terminated = true
                    break@roundLoop
                }
                completedModelRounds += 1
                val round = completedModelRounds
                val assistantContentPrefix = accumulatedAssistantContent
                callback.onThinkingStart()
                val roundStartsAfterToolResult = memory.lastRole() == "tool"
                val toolChoiceForRound = if (roundStartsAfterToolResult) {
                    null
                } else {
                    JsonPrimitive("auto")
                }
                logInfo(
                    tag,
                    "round=$round request_tools=${toolRegistry.toolsForModel.size}"
                )
                val requestMessages = memory.snapshot()
                logPromptCacheFingerprints(
                    messages = requestMessages,
                    tools = toolRegistry.toolsForModel
                )
                // ACP/Xiaowan uses the shared vocabulary where `none` is the
                // normal no-thinking value.  Treat all no-thinking aliases as
                // an explicit wire-level disable; checking only `no` leaves
                // GLM-style providers free to enable their default reasoning
                // path, which can delay the first token for a simple greeting.
                val normalizedReasoningEffort =
                    input.executionEnv.reasoningEffort?.trim()?.lowercase()
                val disableThinking = normalizedReasoningEffort in setOf(
                    "no",
                    "none",
                    "off",
                    "disabled",
                )
                val turn = try {
                    streamTurnWithTransportPolicy(
                        callback = callback,
                        request = ChatCompletionRequest(
                            messages = requestMessages,
                            model = model,
                            maxCompletionTokens = input.maxCompletionTokens
                                ?: settings.maxCompletionTokens,
                            stream = true,
                            streamOptions = ChatCompletionStreamOptions(includeUsage = true),
                            enableThinking = if (disableThinking) false else null,
                            reasoningEffort = if (disableThinking) null else input.executionEnv.reasoningEffort,
                            thinking = if (disableThinking) {
                                cn.com.omnimind.baselib.llm.ChatCompletionThinking(type = "disabled")
                            } else {
                                null
                            },
                            promptCacheKey = input.promptCacheKey,
                            tools = toolRegistry.toolsForModel,
                            toolChoice = toolChoiceForRound,
                            parallelToolCalls = true
                        ),
                        assistantContentPrefix = assistantContentPrefix
                    )
                } catch (e: ContextOverflowTurnFailure) {
                    if (compactContextForOverflow()) {
                        continue@roundLoop
                    }
                    callback.onError(e.errorMessage, true)
                    terminalError = AgentResult.Error(e.errorMessage, e)
                    terminated = true
                    break@roundLoop
                } catch (e: TerminalTurnRequestFailure) {
                    callback.onError(e.errorMessage, true)
                    terminalError = AgentResult.Error(e.errorMessage, e)
                    terminated = true
                    break@roundLoop
                }
                val turnUsage = resolveTurnUsage(turn)
                lastTurnUsage = turnUsage
                lastFinishReason = turn.finishReason
                latestPromptTokens = turnUsage.promptTokens
                latestPromptTokenThreshold =
                    input.contextCompactor?.resolvePromptTokenThreshold(input.conversationId)
                if (
                    isLengthStopAtContextCapacity(
                        finishReason = lastFinishReason,
                        promptTokens = turnUsage.promptTokens,
                        completionTokens = turnUsage.completionTokens,
                        contextCapacityTokens = latestPromptTokenThreshold
                    ) &&
                    (settings.maxContextOverflowRecoveryRounds == null ||
                        contextOverflowRecoveryRounds < settings.maxContextOverflowRecoveryRounds)
                ) {
                    if (compactContextForOverflow()) {
                        continue@roundLoop
                    }
                }
                contextOverflowRecoveryRounds = 0
                contextCompactionStates.clear()
                contextCompactionStates += contextStateFingerprint(memory.snapshot())
                lastPrefillTokensPerSecond =
                    turn.usage?.prefillTokensPerSecond ?: lastPrefillTokensPerSecond
                lastDecodeTokensPerSecond =
                    turn.usage?.decodeTokensPerSecond ?: lastDecodeTokensPerSecond
                val rawAssistantContent = turn.message.contentText().trim()
                lastAssistantContent = combineContinuationContent(
                    prefix = accumulatedAssistantContent,
                    content = rawAssistantContent
                )
                val toolCalls = turn.message.toolCalls.orEmpty()
                logInfo(
                    tag,
                    "round=$round parsed_tool_calls=${toolCalls.size} finish_reason=${lastFinishReason.orEmpty()} assistant_content_len=${lastAssistantContent.length}"
                )

                val assistantMessageForMemory = ChatCompletionMessage(
                    role = "assistant",
                    content = normalizeAssistantContentForNextRound(
                        content = turn.message.content,
                        toolCalls = toolCalls
                    ),
                    toolCalls = toolCalls.ifEmpty { null },
                    reasoningContent = turn.message.reasoningContent
                        ?.takeIf { it.isNotBlank() },
                    protocolState = turn.message.protocolState
                )
                memory.add(assistantMessageForMemory)
                latestPromptTokens?.let { promptTokens ->
                    callback.onPromptTokenUsageChanged(
                        latestPromptTokens = promptTokens,
                        promptTokenThreshold = latestPromptTokenThreshold
                    )
                }
                input.contextCompactor?.let { compactor ->
                    val compacted = compactor.compactIfNeeded(
                        conversationId = input.conversationId,
                        conversationMode = input.executionEnv.conversationMode,
                        promptTokens = latestPromptTokens,
                        messages = memory.snapshot(),
                        contextTokens = AgentConversationContextCompactor
                            .resolveReportedContextTokens(
                                promptTokens = turnUsage.promptTokens,
                                completionTokens = turnUsage.completionTokens,
                                totalTokens = turnUsage.totalTokens
                            ),
                        promptTokenThresholdOverride = latestPromptTokenThreshold,
                        callback = callback
                    )
                    memory.replaceAll(compacted)
                }

                // A provider can finish with `length` after streaming a syntactically
                // valid but incomplete tool-call argument object. Never execute those
                // calls: feed one result per call back to the model so it can re-issue
                // the complete operation in the next round.
                if (toolCalls.isNotEmpty() && isLengthFinishReason(lastFinishReason)) {
                    val reason = buildTruncatedToolCallMessage()
                    toolCalls.forEach { toolCall ->
                        // Even a rejected/truncated call must follow ACP's
                        // start -> terminal-update lifecycle. The bridge
                        // deliberately drops terminal updates for unknown
                        // ids, so do not emit a completion without its start.
                        callback.onToolCallStart(
                            toolCall.id,
                            toolCall.function.name,
                            JsonObject(emptyMap()),
                            toolRegistry.runtimeDescriptor(toolCall.function.name).toolType,
                        )
                        val result = ToolExecutionResult.Error(
                            toolName = toolCall.function.name,
                            message = reason
                        )
                        executedTools.add(result)
                        callback.onToolCallComplete(
                            toolCall.id,
                            toolCall.function.name,
                            result
                        )
                        appendToolResultMessage(
                            memory = memory,
                            env = input.executionEnv,
                            callback = callback,
                            assistantMessage = assistantMessageForMemory,
                            toolCall = toolCall,
                            descriptor = toolRegistry.runtimeDescriptor(toolCall.function.name),
                            result = result
                        )
                    }
                    accumulatedAssistantContent = ""
                    lengthContinuationRounds = 0
                    logInfo(
                        tag,
                        "round=$round rejected_truncated_tool_calls=${toolCalls.size} " +
                            "finish_reason=${lastFinishReason.orEmpty()}"
                    )
                    continue@roundLoop
                }

                if (toolCalls.isEmpty()) {
                    if (
                                isLengthFinishReason(lastFinishReason) &&
                        rawAssistantContent.isNotBlank() &&
                        (settings.maxLengthContinuationRounds == null ||
                            lengthContinuationRounds < settings.maxLengthContinuationRounds)
                    ) {
                        lengthContinuationRounds += 1
                        accumulatedAssistantContent = lastAssistantContent
                        memory.add(buildLengthContinuationMessage())
                        logInfo(
                            tag,
                            "round=$round finish_reason=${lastFinishReason.orEmpty()} auto_continue=$lengthContinuationRounds/${settings.maxLengthContinuationRounds ?: "unlimited"} accumulated_content_len=${accumulatedAssistantContent.length}"
                        )
                        continue@roundLoop
                    }
                    val pendingCompletion = pendingSkillCompletion(
                        policies = completionPolicies,
                        completionProgress = completionProgress
                    )
                    if (pendingCompletion != null && isStopFinishReason(lastFinishReason)) {
                        val unavailableTools = pendingCompletion.policy.completionTools
                            .filterNot(availableToolNames::contains)
                        if (unavailableTools.isNotEmpty()) {
                            val message = t(
                                "Skill ${pendingCompletion.policy.skillId} 无法完成：缺少工具 ${unavailableTools.joinToString(", ")}。",
                                "Skill ${pendingCompletion.policy.skillId} cannot complete because these tools are unavailable: ${unavailableTools.joinToString(", ")}."
                            )
                            callback.onError(message, false)
                            terminalError = AgentResult.Error(message)
                            terminated = true
                            break@roundLoop
                        }
                        val recoveryCount = completionRecoveryRounds
                            .getOrDefault(pendingCompletion.policy.skillId, 0)
                        val maxRecoveryRounds = settings.maxMissingToolCallRecoveryRounds
                        if (maxRecoveryRounds == null || recoveryCount < maxRecoveryRounds) {
                            completionRecoveryRounds[pendingCompletion.policy.skillId] = recoveryCount + 1
                            memory.add(buildSkillCompletionRecoveryMessage(pendingCompletion))
                            logInfo(
                                tag,
                                "round=$round skill_completion_pending " +
                                    "skill=${pendingCompletion.policy.skillId} " +
                                    "next_tool=${pendingCompletion.nextTool} " +
                                    "recovery=${recoveryCount + 1}/$maxRecoveryRounds"
                            )
                            continue@roundLoop
                        }
                        val message = t(
                            "项目文件已生成，但 ${pendingCompletion.nextTool} 尚未成功，不能标记为已完成。",
                            "Project files were generated, but ${pendingCompletion.nextTool} has not succeeded, so the task cannot be marked complete."
                        )
                        callback.onError(message, false)
                        terminalError = AgentResult.Error(message)
                        terminated = true
                        break@roundLoop
                    }
                    val fallbackMessage = lastAssistantContent.ifBlank {
                        "我已完成思考，但暂时无法生成回复，请重试。"
                    }
                    callback.onChatMessage(
                        fallbackMessage,
                        true,
                        lastPrefillTokensPerSecond,
                        lastDecodeTokensPerSecond
                    )
                    executedTools.add(ToolExecutionResult.ChatMessage(fallbackMessage))
                    outputKind = AgentOutputKind.CHAT_MESSAGE
                    hasUserFacingOutput = true
                    terminated = true
                    break
                }
                accumulatedAssistantContent = ""
                lengthContinuationRounds = 0

                var advanceToNextRound = false
                var pendingToolCallBackfillReason: String? = null
                val descriptorMap = mutableMapOf<String, AgentToolRegistry.RuntimeToolDescriptor>()
                val parsedArgsMap = mutableMapOf<String, JsonObject>()
                val validatedCalls = mutableListOf<AssistantToolCall>()
                val writtenToolCallIds = linkedSetOf<String>()

                // Phase A — parse + validate all tool arguments synchronously.
                // Any parse/validation failure aborts the current turn's tool execution
                // (matching pre-refactor semantics: write the error tool message,
                // skip remaining calls, and advance to the next LLM round).
                parsePhase@ for (toolCall in toolCalls) {
                    if (
                        toolRegistry.usesProgressiveDiscovery &&
                        toolRegistry.toolsForModel.none { it.function.name == toolCall.function.name }
                    ) {
                        val result = ToolExecutionResult.Error(
                            toolCall.function.name,
                            t(
                                "工具 ${toolCall.function.name} 尚未加载，请先调用 tools_search 查找并加载它。",
                                "Tool ${toolCall.function.name} is not loaded yet. Call tools_search first to discover it."
                            )
                        )
                        val descriptor = toolRegistry.runtimeDescriptor(toolCall.function.name)
                        descriptorMap[toolCall.id] = descriptor
                        executedTools.add(result)
                        callback.onToolCallStart(
                            toolCall.id,
                            toolCall.function.name,
                            JsonObject(emptyMap()),
                            descriptor.toolType,
                        )
                        callback.onToolCallComplete(
                            toolCall.id,
                            toolCall.function.name,
                            result
                        )
                        appendToolResultMessage(
                            memory = memory,
                            env = input.executionEnv,
                            callback = callback,
                            assistantMessage = assistantMessageForMemory,
                            toolCall = toolCall,
                            descriptor = descriptor,
                            result = result
                        )
                        writtenToolCallIds += toolCall.id
                        hasUserFacingOutput =
                            hasUserFacingOutput || eventAdapter.hasUserVisibleOutput(result)
                        advanceToNextRound = true
                        pendingToolCallBackfillReason = t(
                            "工具尚未加载，当前 assistant 消息中的剩余 tool_call 未执行。",
                            "The tool was not loaded, so the remaining tool calls in this assistant message were not executed."
                        )
                        break@parsePhase
                    }
                    val descriptor = toolRegistry.runtimeDescriptor(toolCall.function.name)
                    descriptorMap[toolCall.id] = descriptor
                    val parsedArgs: JsonObject = try {
                        parseToolArguments(toolCall.function.arguments)
                    } catch (error: Exception) {
                        val result = ToolExecutionResult.Error(
                            toolCall.function.name,
                            error.message ?: "Invalid tool arguments JSON"
                        )
                        val failureLearning = buildFailureLearningPayload(
                            env = input.executionEnv,
                            toolCall = toolCall,
                            descriptor = descriptor,
                            argumentsJson = null,
                            result = result,
                            failureStage = "argument_parse"
                        )
                        executedTools.add(result)
                        callback.onToolCallStart(
                            toolCall.id,
                            toolCall.function.name,
                            JsonObject(emptyMap()),
                            descriptor.toolType,
                        )
                        callback.onToolCallComplete(
                            toolCall.id,
                            toolCall.function.name,
                            result
                        )
                        appendToolResultMessage(
                            memory = memory,
                            env = input.executionEnv,
                            callback = callback,
                            assistantMessage = assistantMessageForMemory,
                            toolCall = toolCall,
                            descriptor = descriptor,
                            result = result,
                            failureLearning = failureLearning
                        )
                        writtenToolCallIds += toolCall.id
                        hasUserFacingOutput =
                            hasUserFacingOutput || eventAdapter.hasUserVisibleOutput(result)
                        advanceToNextRound = true
                        pendingToolCallBackfillReason = t(
                            "工具参数 JSON 解析失败，当前 assistant 消息中的剩余 tool_call 未执行。",
                            "Tool arguments JSON parsing failed, so the remaining tool calls in this assistant message were not executed."
                        )
                        break@parsePhase
                    }
                    val validationError = runCatching {
                        toolRegistry.validateArguments(toolCall.function.name, parsedArgs)
                    }.exceptionOrNull()
                    if (validationError != null) {
                        val result = ToolExecutionResult.Error(
                            toolCall.function.name,
                            validationError.message ?: "Tool arguments validation failed"
                        )
                        val failureLearning = buildFailureLearningPayload(
                            env = input.executionEnv,
                            toolCall = toolCall,
                            descriptor = descriptor,
                            argumentsJson = parsedArgs.toString(),
                            result = result,
                            failureStage = "argument_validation"
                        )
                        executedTools.add(result)
                        callback.onToolCallStart(
                            toolCall.id,
                            toolCall.function.name,
                            parsedArgs,
                            descriptor.toolType,
                        )
                        callback.onToolCallComplete(
                            toolCall.id,
                            toolCall.function.name,
                            result
                        )
                        appendToolResultMessage(
                            memory = memory,
                            env = input.executionEnv,
                            callback = callback,
                            assistantMessage = assistantMessageForMemory,
                            toolCall = toolCall,
                            descriptor = descriptor,
                            result = result,
                            failureLearning = failureLearning
                        )
                        writtenToolCallIds += toolCall.id
                        hasUserFacingOutput =
                            hasUserFacingOutput || eventAdapter.hasUserVisibleOutput(result)
                        advanceToNextRound = true
                        pendingToolCallBackfillReason = t(
                            "工具参数校验失败，当前 assistant 消息中的剩余 tool_call 未执行。",
                            "Tool argument validation failed, so the remaining tool calls in this assistant message were not executed."
                        )
                        break@parsePhase
                    }
                    parsedArgsMap[toolCall.id] = parsedArgs
                    validatedCalls.add(toolCall)
                }

                // Phase B — partition validated calls into batches and execute.
                if (!advanceToNextRound && validatedCalls.isNotEmpty()) {
                    val batches = AgentToolConcurrencyPolicy.partitionToolCalls(
                        validatedCalls,
                        parsedArgsMap
                    )
                    logInfo(
                        tag,
                        "round=$round batches=${batches.size} " +
                            batches.joinToString(separator = ",") { batch ->
                                "${if (batch.parallel) "P" else "S"}${batch.calls.size}"
                            }
                    )

                    batchLoop@ for (batch in batches) {
                        val batchResults: List<Pair<AssistantToolCall, ToolExecutionResult>>
                        if (batch.parallel && batch.calls.size > 1) {
                            // Parallel batch: launch async per call. callback.onToolCallStart /
                            // onToolCallComplete fire from inside each async (lets UI update each
                            // card independently). State mutation + memory append happen serially
                            // below to preserve ToolCall ↔ ToolMessage pairing order.
                            batchResults = coroutineScope {
                                batch.calls.map { call ->
                                    async {
                                        val desc = descriptorMap.getValue(call.id)
                                        val args = parsedArgsMap.getValue(call.id)
                                        val result = executeSingleTool(
                                            env = input.executionEnv,
                                            callback = callback,
                                            toolCall = call,
                                            descriptor = desc,
                                            parsedArgs = args
                                        )
                                        callback.onToolCallComplete(
                                            call.id,
                                            call.function.name,
                                            result
                                        )
                                        call to result
                                    }
                                }.awaitAll()
                            }
                        } else {
                            // Serial batch (single call or barrier).
                            val singles = mutableListOf<Pair<AssistantToolCall, ToolExecutionResult>>()
                            for (call in batch.calls) {
                                val desc = descriptorMap.getValue(call.id)
                                val args = parsedArgsMap.getValue(call.id)
                                val result = executeSingleTool(
                                    env = input.executionEnv,
                                    callback = callback,
                                    toolCall = call,
                                    descriptor = desc,
                                    parsedArgs = args
                                )
                                callback.onToolCallComplete(
                                    call.id,
                                    call.function.name,
                                    result
                                )
                                singles.add(call to result)
                            }
                            batchResults = singles
                        }

                        var breakBatchLoopAfterPost = false
                        // Phase C — serial post-process: write results back to memory in
                        // original call order, accumulate UI state, honor stop conditions.
                        for ((call, result) in batchResults) {
                            val desc = descriptorMap.getValue(call.id)
                            val args = parsedArgsMap.getValue(call.id)
                            executedTools.add(result)
                            activateDiscoveredTools(call.function.name, result)
                            recordSkillCompletionToolAttempt(
                                policies = completionPolicies,
                                completionProgress = completionProgress,
                                toolName = call.function.name,
                                successful = isSuccessfulToolResult(result)
                            )
                            val failureLearning = buildFailureLearningPayload(
                                env = input.executionEnv,
                                toolCall = call,
                                descriptor = desc,
                                argumentsJson = args.toString(),
                                result = result,
                                failureStage = "execution"
                            )
                            if (failureLearning == null) {
                                resolveFailureLearningAfterSuccess(
                                    env = input.executionEnv,
                                    toolCall = call,
                                    argumentsJson = args.toString(),
                                    result = result
                                )
                            }
                            appendToolResultMessage(
                                memory = memory,
                                env = input.executionEnv,
                                callback = callback,
                                assistantMessage = assistantMessageForMemory,
                                toolCall = call,
                                descriptor = desc,
                                result = result,
                                failureLearning = failureLearning
                            )
                            writtenToolCallIds += call.id

                            if (!terminated && !advanceToNextRound && eventAdapter.hasUserVisibleOutput(result)) {
                                hasUserFacingOutput = true
                            }
                            if (!terminated && !advanceToNextRound) {
                                val mappedKind = eventAdapter.mapOutputKind(result)
                                if (mappedKind != AgentOutputKind.NONE) {
                                    outputKind = mappedKind
                                }
                            }
                            if (
                                !terminated &&
                                isUserStoppedVlmTask(call.function.name, result)
                            ) {
                                terminated = true
                                pendingToolCallBackfillReason = t(
                                    "GUI 任务已被用户停止，当前 assistant 消息中的剩余 tool_call 未继续处理。",
                                    "The GUI task was stopped by the user, so the remaining tool calls in this assistant message were not processed."
                                )
                            }
                            if (!terminated && eventAdapter.isConversationStoppingResult(result)) {
                                terminated = true
                                pendingToolCallBackfillReason = t(
                                    "工具 ${call.function.name} 的结果已结束当前对话，当前 assistant 消息中的剩余 tool_call 未继续处理。",
                                    "The result of tool ${call.function.name} ended the conversation, so the remaining tool calls in this assistant message were not processed."
                                )
                            }
                            if (
                                !terminated &&
                                !advanceToNextRound &&
                                AgentToolConcurrencyPolicy.isTurnBoundary(call.function.name)
                            ) {
                                advanceToNextRound = true
                                breakBatchLoopAfterPost = true
                                pendingToolCallBackfillReason = t(
                                    "独占工具 ${call.function.name} 已占用本轮，当前 assistant 消息中的剩余 tool_call 未执行。",
                                    "Exclusive tool ${call.function.name} occupied this turn, so the remaining tool calls in this assistant message were not executed."
                                )
                            }
                            if (terminated) {
                                breakBatchLoopAfterPost = true
                            }
                        }
                        if (breakBatchLoopAfterPost) {
                            break@batchLoop
                        }
                    }
                }

                pendingToolCallBackfillReason?.let { reason ->
                    appendSyntheticToolResultMessages(
                        memory = memory,
                        env = input.executionEnv,
                        callback = callback,
                        assistantMessage = assistantMessageForMemory,
                        toolCalls = toolCalls,
                        descriptorMap = descriptorMap,
                        writtenToolCallIds = writtenToolCallIds,
                        round = round,
                        reason = reason
                    )
                }

                if (terminated) {
                    break
                }
                if (advanceToNextRound) {
                    continue@roundLoop
                }
            }
        } catch (e: CancellationException) {
            // Cancellation is a normal ACP prompt outcome. Convert it at the
            // Agent boundary so Android does not rethrow a pending coroutine
            // exception from the protocol dispatcher; the ACP adapter maps
            // this result to PromptResponse(CANCELLED).
            return AgentResult.Error("Agent execution cancelled", e)
        } catch (e: Exception) {
            // Keep the same retry policy for failures that escape the
            // streaming-specific branches.  In particular, transient tool
            // router/transport exceptions must expose the manual Retry action
            // instead of becoming an unrecoverable generic error.  The
            // classifier still rejects provider limits and malformed tool
            // calls, so a retry cannot blindly repeat a side-effecting tool.
            val decision = classifyRetryableTurnFailure(e)
            val message = decision.reason.ifBlank {
                t(
                    "Agent 执行失败，请重试。",
                    "Agent execution failed. Please retry."
                )
            }
            callback.onError(message, decision.retryable)
            return AgentResult.Error(message, e)
        } finally {
            if (ownsToolRouter) {
                runCatching { toolRouter.dispose() }
            }
        }

        terminalError?.let { return it }

        if (!hasUserFacingOutput) {
            val fallbackMessage = lastAssistantContent.ifBlank {
                t(
                    "我已完成思考，但暂时无法生成回复，请重试。",
                    "I finished reasoning, but I couldn't produce a reply just now. Please try again."
                )
            }
            callback.onChatMessage(
                fallbackMessage,
                true,
                lastPrefillTokensPerSecond,
                lastDecodeTokensPerSecond
            )
            executedTools.add(ToolExecutionResult.ChatMessage(fallbackMessage))
            outputKind = AgentOutputKind.CHAT_MESSAGE
            hasUserFacingOutput = true
        }

        val finalResult = AgentResult.Success(
            response = AgentFinalResponse(
                content = lastAssistantContent,
                finishReason = lastFinishReason,
                latestPromptTokens = latestPromptTokens,
                promptTokenThreshold = latestPromptTokenThreshold,
                completionTokens = lastTurnUsage?.completionTokens,
                cachedTokens = lastTurnUsage?.cachedTokens,
                cacheCreationTokens = lastTurnUsage?.cacheCreationTokens,
                totalTokens = lastTurnUsage?.totalTokens
            ),
            executedTools = executedTools,
            outputKind = outputKind.value,
            hasUserVisibleOutput = hasUserFacingOutput,
            latestPromptTokens = latestPromptTokens,
            promptTokenThreshold = latestPromptTokenThreshold,
            completionTokens = lastTurnUsage?.completionTokens,
            cachedTokens = lastTurnUsage?.cachedTokens,
            cacheCreationTokens = lastTurnUsage?.cacheCreationTokens,
            totalTokens = lastTurnUsage?.totalTokens
        )
        callback.onComplete(finalResult)
        return finalResult
    }

    private suspend fun streamTurnWithTransportPolicy(
        callback: AgentCallback,
        request: ChatCompletionRequest,
        assistantContentPrefix: String
    ): ChatCompletionTurn {
        try {
            // AgentLlmClient is the sole owner of transport retries. Retrying
            // this method would replay the complete logical round, including
            // streamed reasoning and tool intent, and can produce duplicate
            // thoughts/cards. A failed turn is surfaced to the UI instead.
            return llmClient.streamTurn(
                request = request,
                onReasoningUpdate = { reasoning ->
                    if (reasoning.isNotBlank()) {
                        callback.onThinkingUpdate(normalizeThinkingText(reasoning))
                    }
                },
                onContentUpdate = { content ->
                    if (content.isNotBlank()) {
                        callback.onChatMessage(
                            combineContinuationContent(
                                prefix = assistantContentPrefix,
                                content = content
                            ),
                            false
                        )
                    }
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isContextOverflowTurnFailure(e)) {
                val requestError = e as AgentStreamRequestException
                throw ContextOverflowTurnFailure(
                    errorMessage = formatTurnFailureReason(
                        requestError.statusCode,
                        requestError.reason
                    ),
                    cause = e
                )
            }
            val decision = classifyRetryableTurnFailure(e)
            throw TerminalTurnRequestFailure(
                errorMessage = decision.reason,
                cause = e
            )
        }
    }

    private suspend fun executeSingleTool(
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolCall: AssistantToolCall,
        descriptor: AgentToolRegistry.RuntimeToolDescriptor,
        parsedArgs: JsonObject
    ): ToolExecutionResult {
        val toolHandle = env.runControl.beginToolExecution(
            toolName = toolCall.function.name,
            toolCallId = toolCall.id
        )
        callback.onToolCallStart(
            toolCall.id,
            toolCall.function.name,
            parsedArgs,
            descriptor.toolType,
        )
        return try {
            coroutineScope {
                val deferred = async {
                    toolRouter.execute(
                        toolCall = toolCall,
                        args = parsedArgs,
                        runtimeDescriptor = descriptor,
                        env = env,
                        callback = callback,
                        toolHandle = toolHandle
                    )
                }
                toolHandle.bindExecutionJob(deferred)
                deferred.await()
            }
        } catch (error: CancellationException) {
            if (toolHandle.isManualStopRequested()) {
                buildInterruptedToolResult(
                    toolName = toolCall.function.name,
                    toolHandle = toolHandle
                )
            } else {
                throw error
            }
        } finally {
            toolHandle.complete()
        }
    }

    private fun activateDiscoveredTools(
        toolName: String,
        result: ToolExecutionResult,
    ) {
        if (toolName != ToolSearchHandler.NAME) return
        val rawResult = (result as? ToolExecutionResult.ContextResult)?.rawResultJson
            ?: return
        val names = runCatching {
            val payload = json.parseToJsonElement(rawResult) as? JsonObject
            val tools = payload?.get("tools") as? JsonArray
            tools.orEmpty().mapNotNull { item ->
                (item as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            }.toSet()
        }.getOrDefault(emptySet())
        if (names.isNotEmpty()) {
            toolRegistry.exposeToolNames(names)
            logInfo(
                tag,
                "tool_discovery activated=${names.size} visible=${toolRegistry.toolsForModel.size}"
            )
        }
    }

    private suspend fun appendToolResultMessage(
        memory: AgentChatMemory,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        assistantMessage: ChatCompletionMessage,
        toolCall: AssistantToolCall,
        descriptor: AgentToolRegistry.RuntimeToolDescriptor,
        result: ToolExecutionResult,
        failureLearning: FailureLearningHookPayload? = null
    ) {
        val rawTextContent = eventAdapter.toolResultContent(
            descriptor = descriptor,
            result = result,
            extras = failureLearning?.toPayload() ?: emptyMap()
        )
        val maxToolResultChars = env.runtimeSettings.maxToolResultChars
        val offloadArtifact = if (
            maxToolResultChars != null && rawTextContent.length > maxToolResultChars
        ) {
            runCatching {
                env.workspaceManager.writeOffload(
                    agentRunId = env.agentRunId,
                    extension = "json",
                    content = rawTextContent
                )
            }.getOrNull()
        } else {
            null
        }
        val textContent = eventAdapter.compactToolResultContent(
            rawContent = rawTextContent,
            offloadArtifact = offloadArtifact,
            maxChars = maxToolResultChars,
        )
        val imageDataUrl = (result as? ToolExecutionResult.ContextResult)
            ?.imageDataUrl
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { toolImageContinuationPolicy.supportsToolImageContinuation }
        if (
            result is ToolExecutionResult.ContextResult &&
            !result.imageDataUrl.isNullOrBlank() &&
            imageDataUrl == null
        ) {
            logInfo(
                tag,
                "skip_tool_image_continuation tool=${toolCall.function.name} route=${toolImageContinuationPolicy.routeLabel}"
            )
        }

        val skippedUnsupportedBrowserImage =
            result is ToolExecutionResult.ContextResult &&
                !result.imageDataUrl.isNullOrBlank() &&
                imageDataUrl == null &&
                toolCall.function.name.equals("browser_use", ignoreCase = true)
        val modelTextContent = if (skippedUnsupportedBrowserImage) {
            buildString {
                append(textContent)
                append("\n\n")
                append(
                    t(
                        zh = "当前模型不支持图片输入，本次截图未发送图片。请继续使用 browser_use 的 get_text 或 get_readable 读取当前页面文字；复用结果中的 tab_id，不要再次请求 read_image。",
                        en = "The current model does not support image input, so the screenshot was not sent. Continue with browser_use get_text or get_readable on the same tab_id; do not request read_image again."
                    )
                )
            }
        } else {
            textContent
        }

        val content: JsonElement = if (imageDataUrl != null) {
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", modelTextContent)
                })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", imageDataUrl)
                    })
                })
            }
        } else {
            JsonPrimitive(modelTextContent)
        }

        val toolResultMessage = ChatCompletionMessage(
            role = "tool",
            toolCallId = toolCall.id,
            content = content,
            protocolState = ChatCompletionProtocolState(
                anthropic = AnthropicMessageProtocolState(
                    toolResultIsError = !isSuccessfulToolResult(result)
                )
            )
        )
        memory.add(toolResultMessage)
        callback.onToolReplayReady(
            toolCallId = toolCall.id,
            assistantMessage = assistantMessage,
            toolResultMessage = toolResultMessage
        )
    }

    private suspend fun appendSyntheticToolResultMessages(
        memory: AgentChatMemory,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        assistantMessage: ChatCompletionMessage,
        toolCalls: List<AssistantToolCall>,
        descriptorMap: MutableMap<String, AgentToolRegistry.RuntimeToolDescriptor>,
        writtenToolCallIds: MutableSet<String>,
        round: Int,
        reason: String
    ) {
        val syntheticIds = mutableListOf<String>()
        val actualIds = writtenToolCallIds.toList()
        for (toolCall in toolCalls) {
            if (toolCall.id in writtenToolCallIds) {
                continue
            }
            val descriptor = descriptorMap.getOrPut(toolCall.id) {
                toolRegistry.runtimeDescriptor(toolCall.function.name)
            }
            val syntheticResult = ToolExecutionResult.Error(
                toolName = toolCall.function.name,
                message = buildSyntheticToolSkipMessage(reason)
            )
            val displayArguments = runCatching {
                parseToolArguments(toolCall.function.arguments)
            }.getOrDefault(JsonObject(emptyMap()))
            callback.onToolCallStart(
                toolCall.id,
                toolCall.function.name,
                displayArguments,
                descriptor.toolType,
            )
            callback.onToolCallComplete(
                toolCall.id,
                toolCall.function.name,
                syntheticResult
            )
            appendToolResultMessage(
                memory = memory,
                env = env,
                callback = callback,
                assistantMessage = assistantMessage,
                toolCall = toolCall,
                descriptor = descriptor,
                result = syntheticResult
            )
            writtenToolCallIds += toolCall.id
            syntheticIds += toolCall.id
        }
        if (syntheticIds.isNotEmpty()) {
            logInfo(
                tag,
                "round=$round tool_calls=${toolCalls.size} actual_tool_call_ids=${actualIds.joinToString(",")} " +
                    "synthetic_tool_call_ids=${syntheticIds.joinToString(",")} reason=$reason"
            )
        }
    }

    private fun buildSyntheticToolSkipMessage(reason: String): String {
        return t(
            "本轮未执行该工具。原因：$reason 如仍需要此工具，请由模型在下一轮重新发起。",
            "This tool was not executed in this turn. Reason: $reason If it is still needed, the model should call it again in the next turn."
        )
    }

    private fun isUserStoppedVlmTask(
        toolName: String,
        result: ToolExecutionResult
    ): Boolean =
        toolName == "vlm_task" &&
            result is ToolExecutionResult.Interrupted &&
            result.interruptedBy.equals("user", ignoreCase = true)

    private fun buildInterruptedToolResult(
        toolName: String,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult.Interrupted {
        val snapshot = toolHandle.latestProgressSnapshot()
        val interruptedSummary = t(
            "工具调用已被用户手动停止",
            "Tool call was stopped manually by the user."
        )
        val rawPayload = linkedMapOf<String, Any?>(
            "toolName" to toolName,
            "status" to "interrupted",
            "summary" to interruptedSummary,
            "interruptedBy" to "user",
            "interruptionReason" to "manual_stop"
        ).apply {
            if (snapshot.summary.isNotBlank()) {
                put("lastProgress", snapshot.summary)
            }
            snapshot.extras.forEach { (key, value) ->
                put(key, value)
            }
        }
        val encodedPayload = json.encodeToString(mapToJsonElement(rawPayload))
        return ToolExecutionResult.Interrupted(
            toolName = toolName,
            summaryText = interruptedSummary,
            previewJson = encodedPayload,
            rawResultJson = encodedPayload,
            terminalOutput = snapshot.extras["terminalOutput"]?.toString().orEmpty().ifBlank {
                snapshot.extras["terminalOutputDelta"]?.toString().orEmpty()
            },
            terminalSessionId = snapshot.extras["terminalSessionId"]?.toString(),
            terminalStreamState = snapshot.extras["terminalStreamState"]?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: "interrupted"
        )
    }

    private fun mapToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> kotlinx.serialization.json.JsonNull
            is JsonElement -> value
            is Map<*, *> -> JsonObject(
                value.entries.associate { (key, item) ->
                    key.toString() to mapToJsonElement(item)
                }
            )
            is List<*> -> JsonArray(value.map { mapToJsonElement(it) })
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun buildFailureLearningPayload(
        env: AgentExecutionEnvironment,
        toolCall: AssistantToolCall,
        descriptor: AgentToolRegistry.RuntimeToolDescriptor,
        argumentsJson: String?,
        result: ToolExecutionResult,
        failureStage: String
    ): FailureLearningHookPayload? {
        if (!SelfImprovingSkillFailureHook.shouldHandle(result)) {
            return null
        }
        val skill = env.failureLearningSkill ?: return null
        val payload = SelfImprovingSkillFailureHook.capture(
            skillsRoot = env.workspaceManager.skillsRoot(),
            skill = skill,
            userMessage = env.userMessage,
            toolName = toolCall.function.name,
            toolType = descriptor.toolType,
            argumentsJson = argumentsJson,
            result = result,
            agentRunId = env.agentRunId,
            failureStage = failureStage
        ) ?: return null
        return payload.copy(
            logShellPath = env.workspaceManager.shellPathForAndroid(payload.logFile)
        )
    }

    private fun resolveFailureLearningAfterSuccess(
        env: AgentExecutionEnvironment,
        toolCall: AssistantToolCall,
        argumentsJson: String?,
        result: ToolExecutionResult
    ) {
        if (env.failureLearningSkill == null) return
        val resolution = SelfImprovingSkillFailureHook.resolveAfterSuccess(
            skillsRoot = env.workspaceManager.skillsRoot(),
            agentRunId = env.agentRunId,
            toolName = toolCall.function.name,
            argumentsJson = argumentsJson,
            result = result
        ) ?: return
        if (resolution.shouldPromoteDaily) {
            runCatching {
                env.workspaceMemoryService.appendDailyMemoryIfNovel(resolution.lesson)
            }
        }
        logInfo(
            tag,
            "self_improving_resolved entry=${resolution.entryId} " +
                "tool=${toolCall.function.name} promoted_daily=${resolution.shouldPromoteDaily}"
        )
    }

    private fun normalizeAssistantContentForNextRound(
        content: JsonElement?,
        toolCalls: List<AssistantToolCall>
    ): JsonElement? {
        if (toolCalls.isEmpty()) {
            return content
        }
        return when (content) {
            null -> JsonPrimitive("")
            is JsonPrimitive -> {
                if (content.isString && content.content.isBlank()) {
                    JsonPrimitive("")
                } else {
                    content
                }
            }

            else -> content
        }
    }

    private fun parseToolArguments(argumentsJson: String): JsonObject {
        val normalized = argumentsJson.trim()
        if (normalized.isEmpty()) return JsonObject(emptyMap())
        val parsed = json.decodeFromString<JsonElement>(normalized)
        return parsed as? JsonObject
            ?: throw IllegalArgumentException("tool arguments must be a JSON object")
    }

    private fun normalizeThinkingText(text: String): String {
        val normalized = if ('\r' in text) {
            text.replace("\r\n", "\n").replace('\r', '\n')
        } else {
            text
        }
        return normalized.trim()
    }

    private fun isLengthFinishReason(reason: String?): Boolean {
        val normalized = reason?.trim()?.lowercase().orEmpty()
        return normalized == "length" ||
            normalized == "max_tokens" ||
            normalized == "max_completion_tokens"
    }

    private fun buildTruncatedToolCallMessage(): String {
        return t(
            "本工具调用未执行：模型输出达到长度上限，参数可能被截断。请在下一轮重新发起并提供完整参数。",
            "This tool call was not executed because the model output reached its length limit and the arguments may be truncated. Re-issue it in the next round with complete arguments."
        )
    }

    private fun isStopFinishReason(reason: String?): Boolean {
        return reason?.trim()?.lowercase() == "stop"
    }

    private fun classifyRetryableTurnFailure(error: Throwable): RetryDecision {
        // The LLM client already owns the stream-idle deadline. Retrying this
        // exact request from the orchestrator only multiplies a dead-provider
        // wait (45s x 3 previously) and leaves ACP/UI stuck on "thinking".
        // Surface the failure so the user can retry explicitly.
        if (AgentRuntimeErrorSupport.failureKind(error) ==
            AgentRuntimeErrorSupport.PROVIDER_STREAM_IDLE_TIMEOUT
        ) {
            return RetryDecision(
                retryable = false,
                reason = AgentRuntimeErrorSupport.userFacingMessage(error)
                    ?: error.message.orEmpty()
            )
        }
        if (error is AgentStreamRequestException) {
            val statusCode = error.statusCode
            val providerFailureText = buildString {
                append(error.reason)
                error.responseBody?.takeIf { it.isNotBlank() }?.let {
                    append(' ')
                    append(it)
                }
            }
            if (looksLikeNonRetryableProviderLimitFailure(providerFailureText)) {
                return RetryDecision(
                    retryable = false,
                    reason = formatTurnFailureReason(statusCode, error.reason)
                )
            }
            val retryableStatus = statusCode == 408 ||
                statusCode == 429 ||
                statusCode == 500 ||
                statusCode == 502 ||
                statusCode == 503 ||
                statusCode == 504 ||
                statusCode == 524 ||
                statusCode == 529
            val retryableReason = looksLikeTransientTransportFailure(error.reason)
            if (retryableStatus || retryableReason) {
                return RetryDecision(
                    retryable = true,
                    reason = formatTurnFailureReason(statusCode, error.reason)
                )
            }
            return RetryDecision(
                retryable = false,
                reason = error.reason
            )
        }

        val message = error.message?.trim().orEmpty()
        AgentRuntimeErrorSupport.userFacingMessage(error)?.let { userFacingMessage ->
            return RetryDecision(
                retryable = false,
                reason = userFacingMessage
            )
        }
        if (looksLikeTransientTransportFailure(message)) {
            return RetryDecision(
                retryable = true,
                reason = formatTurnFailureReason(null, message)
            )
        }
        return RetryDecision(
            retryable = false,
            reason = message.ifEmpty { error::class.java.simpleName }
        )
    }

    private fun looksLikeNonRetryableProviderLimitFailure(message: String): Boolean {
        if (message.isBlank()) return false
        val normalized = message.lowercase()
        return normalized.contains("insufficient_quota") ||
            normalized.contains("quota exceeded") ||
            normalized.contains("out of budget") ||
            normalized.contains("billing") ||
            normalized.contains("monthly usage limit") ||
            normalized.contains("weekly usage limit") ||
            normalized.contains("freeusagelimiterror") ||
            normalized.contains("gousagelimiterror") ||
            normalized.contains("available balance")
    }

    private fun looksLikeTransientTransportFailure(message: String): Boolean {
        if (message.isBlank()) return false
        val normalized = message.lowercase()
        return normalized.contains("idle timeout") ||
            normalized.contains("closed before completion signal") ||
            normalized.contains("unknown stream failure") ||
            normalized.contains("timeout") ||
            normalized.contains("timed out") ||
            normalized.contains("connection reset") ||
            normalized.contains("unexpected end of stream") ||
            normalized.contains("software caused connection abort") ||
            normalized.contains("broken pipe") ||
            normalized.contains("connection refused") ||
            normalized.contains("connection aborted") ||
            normalized.contains("eofexception") ||
            normalized.contains("protocol_error") ||
            normalized.contains("stream was reset") ||
            normalized.contains("network") ||
            normalized.contains("temporarily unavailable")
    }

    private fun formatTurnFailureReason(statusCode: Int?, reason: String): String {
        val normalizedReason = reason.trim().ifEmpty {
            t("请求失败，请稍后重试。", "Request failed. Please try again later.")
        }
        return statusCode?.let { "HTTP $it: $normalizedReason" } ?: normalizedReason
    }

    private fun resolveSkillCompletionPolicies(
        skills: List<ResolvedSkillContext>
    ): List<SkillCompletionPolicy> {
        return skills.mapNotNull { skill ->
            val completionTools = parseToolNameList(skill.frontmatter["completion-tools"])
            if (completionTools.isEmpty()) {
                return@mapNotNull null
            }
            SkillCompletionPolicy(
                skillId = skill.skillId,
                startTools = parseToolNameList(skill.frontmatter["completion-start-tools"]).toSet(),
                completionTools = completionTools
            )
        }
    }

    private fun parseToolNameList(raw: String?): List<String> {
        return raw.orEmpty()
            .split(Regex("[,\\s]+"))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
    }

    private fun pendingSkillCompletion(
        policies: List<SkillCompletionPolicy>,
        completionProgress: Map<String, Int>
    ): PendingSkillCompletion? {
        return policies.firstNotNullOfOrNull { policy ->
            val completedCount = completionProgress[policy.skillId]
                ?: return@firstNotNullOfOrNull null
            policy.completionTools.getOrNull(completedCount)?.let { nextTool ->
                PendingSkillCompletion(policy = policy, nextTool = nextTool)
            }
        }
    }

    private fun recordSkillCompletionToolAttempt(
        policies: List<SkillCompletionPolicy>,
        completionProgress: MutableMap<String, Int>,
        toolName: String,
        successful: Boolean
    ) {
        policies.forEach { policy ->
            if (
                policy.skillId !in completionProgress &&
                (policy.startTools.isEmpty() || toolName in policy.startTools)
            ) {
                completionProgress[policy.skillId] = 0
            }
            val completedCount = completionProgress[policy.skillId] ?: return@forEach
            if (successful && policy.completionTools.getOrNull(completedCount) == toolName) {
                completionProgress[policy.skillId] = completedCount + 1
            }
        }
    }

    private fun isSuccessfulToolResult(result: ToolExecutionResult): Boolean {
        return when (result) {
            is ToolExecutionResult.ChatMessage,
            is ToolExecutionResult.Clarify -> true
            is ToolExecutionResult.Error,
            is ToolExecutionResult.PermissionRequired,
            is ToolExecutionResult.Interrupted -> false
            is ToolExecutionResult.ScheduleResult -> result.success
            is ToolExecutionResult.McpResult -> result.success
            is ToolExecutionResult.MemoryResult -> result.success
            is ToolExecutionResult.TerminalResult -> result.success && !result.timedOut
            is ToolExecutionResult.ContextResult -> result.success
        }
    }

    private fun buildLengthContinuationMessage(): ChatCompletionMessage {
        return ChatCompletionMessage(
            role = "user",
            content = JsonPrimitive(
                "上一条 assistant 回复因为达到输出长度上限被截断。请从中断处继续完成原任务，不要重复已经输出的内容，不要重新开头，不要解释本提示。"
            )
        )
    }

    private fun buildSkillCompletionRecoveryMessage(
        pending: PendingSkillCompletion
    ): ChatCompletionMessage {
        return ChatCompletionMessage(
            role = "user",
            content = JsonPrimitive(
                t(
                    "当前已加载的 Skill ${pending.policy.skillId} 声明了完成契约。你已经开始执行该流程，但 ${pending.nextTool} 尚未成功。不要声称任务已完成；请继续修复必要文件并调用 ${pending.nextTool}。只有完成工具序列 ${pending.policy.completionTools.joinToString(" -> ")} 全部成功后，才能给出最终完成回复。",
                    "The loaded skill ${pending.policy.skillId} declares a completion contract. You started that workflow, but ${pending.nextTool} has not succeeded. Do not claim completion; continue fixing the required files and call ${pending.nextTool}. Give a final completion response only after the full tool sequence ${pending.policy.completionTools.joinToString(" -> ")} succeeds."
                )
            )
        )
    }

    private fun combineContinuationContent(prefix: String, content: String): String {
        val normalizedPrefix = AgentTextSanitizer.sanitizeUtf16(prefix).trim()
        val normalizedContent = AgentTextSanitizer.sanitizeUtf16(content).trim()
        if (normalizedPrefix.isEmpty()) return normalizedContent
        if (normalizedContent.isEmpty()) return normalizedPrefix
        if (normalizedContent.startsWith(normalizedPrefix)) return normalizedContent
        if (normalizedPrefix.startsWith(normalizedContent)) return normalizedPrefix

        val maxOverlap = minOf(
            normalizedPrefix.length,
            normalizedContent.length,
            2048
        )
        for (overlap in maxOverlap downTo 1) {
            val prefixStart = normalizedPrefix.length - overlap
            if (
                normalizedPrefix.regionMatches(
                    thisOffset = prefixStart,
                    other = normalizedContent,
                    otherOffset = 0,
                    length = overlap,
                    ignoreCase = false
                )
            ) {
                return normalizedPrefix + normalizedContent.substring(overlap)
            }
        }
        return normalizedPrefix + normalizedContent
    }

    private fun logInfo(tag: String, message: String) {
        runCatching { OmniLog.i(tag, message) }
    }
}
