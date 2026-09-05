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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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
    data class Input(
        val callback: AgentCallback,
        val initialMessages: List<ChatCompletionMessage>,
        val executionEnv: AgentExecutionEnvironment,
        val conversationId: Long? = null,
        val promptCacheKey: String? = null
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
        // Keep this as an explicit loop instead of the inline `mapTo` call.
        // This code runs inside the ACP request coroutine and can be resumed
        // while a counterpart sends $/cancelRequest.  The generated inline
        // collection bridge is needlessly fragile on Android/R8 in that
        // cancellation path; the mutable set is also clearer about the
        // de-duplication contract used by tool-choice recovery.
        val executedTools = mutableListOf<ToolExecutionResult>()
        var outputKind = AgentOutputKind.NONE
        var hasUserFacingOutput = false
        var lastAssistantContent = ""
        var accumulatedAssistantContent = ""
        var lastFinishReason: String? = null
        var latestPromptTokens: Int? = null
        var lastTurnUsage: TurnUsage? = null
        var lastPrefillTokensPerSecond: Double? = null
        var lastDecodeTokensPerSecond: Double? = null
        var completedModelRounds = 0
        var terminated = false

        try {
            roundLoop@ while (true) {
                completedModelRounds += 1
                val round = completedModelRounds
                val assistantContentPrefix = accumulatedAssistantContent
                callback.onThinkingStart()
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
                val turn = streamTurn(
                    callback = callback,
                    request = ChatCompletionRequest(
                        messages = requestMessages,
                        model = model,
                        maxCompletionTokens = null,
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
                        // These optional controls belong to the active
                        // Harness/Provider. The shared loop supplies the
                        // tool surface and preserves all tool results,
                        // without overriding the provider's own defaults.
                        toolChoice = null,
                        // The configured Harness/Provider owns whether
                        // independent tool calls may run in parallel.
                        // Omitting this optional OpenAI-compatible field
                        // keeps the shared ACP loop free of a local policy.
                        parallelToolCalls = null
                    ),
                    assistantContentPrefix = assistantContentPrefix
                )
                val turnUsage = resolveTurnUsage(turn)
                lastTurnUsage = turnUsage
                lastFinishReason = turn.finishReason
                latestPromptTokens = turnUsage.promptTokens
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
                        promptTokenThreshold = null
                    )
                }

                if (toolCalls.isEmpty()) {
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
                    val descriptor = toolRegistry.runtimeDescriptor(toolCall.function.name)
                    descriptorMap[toolCall.id] = descriptor
                    val parsedArgs: JsonObject = try {
                        parseToolArguments(toolCall.function.arguments)
                    } catch (error: Exception) {
                        val result = ToolExecutionResult.Error(
                            toolCall.function.name,
                            error.message ?: "Invalid tool arguments JSON"
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
                            result = result
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
                            result = result
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

                // Phase B — execute the calls in the model's declared order.
                // The runtime does not add a second tool scheduler: a tool
                // result is committed before the next model-selected call so
                // identity, cancellation, and user-visible activity stay in
                // one straightforward ACP prompt loop.
                if (!advanceToNextRound && validatedCalls.isNotEmpty()) {
                    logInfo(
                        tag,
                        "round=$round model_tool_calls=${validatedCalls.size}"
                    )

                    for (call in validatedCalls) {
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
                        executedTools.add(result)
                        appendToolResultMessage(
                            memory = memory,
                            env = input.executionEnv,
                            callback = callback,
                            assistantMessage = assistantMessageForMemory,
                            toolCall = call,
                            descriptor = desc,
                            result = result
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
                        if (!terminated && isUserStoppedVlmTask(call.function.name, result)) {
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
                        if (terminated) {
                            break
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
            // A retry is always an explicit new user send.  Do not infer a
            // different UI action from provider-specific status codes or
            // error strings: that turns transport heuristics into a private
            // lifecycle policy and prevents the user from retrying after
            // fixing credentials, quota, or a transient service issue.
            val message = terminalFailureMessage(e)
            callback.onError(message, retryable = true)
            return AgentResult.Error(message, e)
        } finally {
            if (ownsToolRouter) {
                runCatching { toolRouter.dispose() }
            }
        }

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
                promptTokenThreshold = null,
                completionTokens = lastTurnUsage?.completionTokens,
                cachedTokens = lastTurnUsage?.cachedTokens,
                cacheCreationTokens = lastTurnUsage?.cacheCreationTokens,
                totalTokens = lastTurnUsage?.totalTokens
            ),
            executedTools = executedTools,
            outputKind = outputKind.value,
            hasUserVisibleOutput = hasUserFacingOutput,
            latestPromptTokens = latestPromptTokens,
            promptTokenThreshold = null,
            completionTokens = lastTurnUsage?.completionTokens,
            cachedTokens = lastTurnUsage?.cachedTokens,
            cacheCreationTokens = lastTurnUsage?.cacheCreationTokens,
            totalTokens = lastTurnUsage?.totalTokens
        )
        callback.onComplete(finalResult)
        return finalResult
    }

    private suspend fun streamTurn(
        callback: AgentCallback,
        request: ChatCompletionRequest,
        assistantContentPrefix: String
    ): ChatCompletionTurn {
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
            onToolCallInput = { call ->
                callback.onToolCallInput(call, toolRegistry.runtimeDescriptor(call.function.name).toolType)
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

    private suspend fun appendToolResultMessage(
        memory: AgentChatMemory,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        assistantMessage: ChatCompletionMessage,
        toolCall: AssistantToolCall,
        descriptor: AgentToolRegistry.RuntimeToolDescriptor,
        result: ToolExecutionResult,
    ) {
        val textContent = eventAdapter.toolResultContent(
            descriptor = descriptor,
            result = result,
            extras = emptyMap()
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
            // The provider requires a matching tool result before it can
            // continue this prompt, but this call never executed.  Keep the
            // protocol-only result out of the user-visible tool timeline so
            // chat does not claim an operation started or failed when it was
            // deliberately skipped.
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

    private fun isStopFinishReason(reason: String?): Boolean {
        return reason?.trim()?.lowercase() == "stop"
    }

    private fun terminalFailureMessage(error: Throwable): String {
        AgentRuntimeErrorSupport.userFacingMessage(error)?.let { return it }
        if (error is AgentStreamRequestException) {
            return formatTurnFailureReason(error.statusCode, error.reason)
        }
        return AgentRuntimeErrorSupport.safeDiagnosticMessage(error)
    }

    private fun formatTurnFailureReason(statusCode: Int?, reason: String): String {
        val normalizedReason = reason.trim().ifEmpty {
            t("请求失败，请稍后重试。", "Request failed. Please try again later.")
        }
        return statusCode?.let { "HTTP $it: $normalizedReason" } ?: normalizedReason
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
