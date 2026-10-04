package cn.com.omnimind.bot.agent.projection

/*
 * Port of `agent_event_reducer.dart` lines 1498–2982: AgentEventReducer
 * instance methods from `_applyAcpPresentation` through
 * `_deduplicateReplayDelta` (presentation/usage/performance metrics,
 * assistant text, media/artifacts, thinking cards, tool output, tool cards,
 * permission and request cards). Ported statement by statement; keep in sync
 * with the Dart owner.
 */

internal fun AgentEventReducer.applyAcpPresentation(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    entryId: String,
    presentation: Map<String, Any?>?,
) {
    if (presentation == null || presentation.isEmpty()) {
        return
    }
    val usage = copyStringMap(presentation["usage"])
    if (usage != null) {
        applyAcpUsage(runtime, usage)
        applyAcpPerformanceMetrics(
            runtime,
            parentTaskId = parentTaskId,
            entryId = entryId,
            usage = usage,
        )
    }
    val retry = copyStringMap(presentation["retry"])
    if (retry != null) {
        // Retry is optional adapter presentation metadata. It can annotate
        // output from an already-admitted prompt, but it must not resurrect or
        // create an ACP turn; official session updates and PromptResponse own
        // that lifecycle.
        if (hasAgentMessage(runtime, entryId)) {
            upsertAcpRetryPresentation(runtime, entryId = entryId, retry = retry)
        } else {
            bufferAcpAssistantPresentation(
                runtime,
                parentTaskId = parentTaskId,
                entryId = entryId,
                key = "retry",
                value = retry,
            )
        }
    }
    val recovery = copyStringMap(presentation["recovery"])
    if (recovery != null) {
        if (hasAgentMessage(runtime, entryId)) {
            upsertAcpRecoveryPresentation(runtime, entryId = entryId, recovery = recovery)
        } else {
            bufferAcpAssistantPresentation(
                runtime,
                parentTaskId = parentTaskId,
                entryId = entryId,
                key = "recovery",
                value = recovery,
            )
        }
    }
    val clarification = copyStringMap(presentation["clarification"])
    if (clarification != null) {
        if (hasAgentMessage(runtime, entryId)) {
            applyAcpClarificationPresentation(
                runtime,
                entryId = entryId,
                clarification = clarification,
            )
        } else {
            bufferAcpAssistantPresentation(
                runtime,
                parentTaskId = parentTaskId,
                entryId = entryId,
                key = "clarification",
                value = clarification,
            )
        }
    }
}

/**
 * Dart `ConversationModel.copyWith(latestPromptTokens:, promptTokenThreshold:,
 * latestPromptTokensUpdatedAt:)` on the conversation JSON map.
 */
internal fun AgentEventReducer.applyAcpUsage(
    runtime: ChatConversationRuntimeState,
    usage: Map<String, Any?>,
) {
    val conversation = runtime.conversation
    val latestPromptTokens = asInt(usage["latestPromptTokens"] ?: usage["promptTokens"])
    val promptTokenThreshold = asInt(usage["promptTokenThreshold"])
    if (conversation == null ||
        (latestPromptTokens == null && promptTokenThreshold == null)
    ) {
        return
    }
    runtime.conversation = LinkedHashMap(conversation).apply {
        if (latestPromptTokens != null) put("latestPromptTokens", latestPromptTokens)
        if (promptTokenThreshold != null) put("promptTokenThreshold", promptTokenThreshold)
        put("latestPromptTokensUpdatedAt", System.currentTimeMillis())
    }
}

internal fun AgentEventReducer.applyAcpPerformanceMetrics(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    entryId: String,
    usage: Map<String, Any?>,
) {
    val prefill = asDouble(usage["prefillTokensPerSecond"])
    val decode = asDouble(usage["decodeTokensPerSecond"])
    val turnUsage = copyStringMap(usage["turnUsage"])
    if (prefill == null && decode == null && turnUsage == null) {
        return
    }
    val index = runtime.messages.indexOfFirst { message -> message.id == entryId }
    if (index == -1) {
        val key = pendingAcpPerformanceKey(parentTaskId = parentTaskId, entryId = entryId)
        val next: JsonMap = LinkedHashMap()
        runtime.pendingAcpPerformanceMetrics[key]?.let { next.putAll(it) }
        if (prefill != null) next["prefillTokensPerSecond"] = prefill
        if (decode != null) next["decodeTokensPerSecond"] = decode
        if (turnUsage != null) next["turnUsage"] = turnUsage
        runtime.pendingAcpPerformanceMetrics[key] = next
        return
    }
    writeAcpPerformanceMetrics(
        runtime,
        index = index,
        prefill = prefill,
        decode = decode,
        turnUsage = turnUsage,
    )
}

internal fun AgentEventReducer.flushPendingAcpPerformanceMetrics(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    entryId: String,
) {
    val key = pendingAcpPerformanceKey(parentTaskId = parentTaskId, entryId = entryId)
    val pending = runtime.pendingAcpPerformanceMetrics.remove(key) ?: return
    val index = runtime.messages.indexOfFirst { message -> message.id == entryId }
    if (index == -1) {
        runtime.pendingAcpPerformanceMetrics[key] = pending
        return
    }
    writeAcpPerformanceMetrics(
        runtime,
        index = index,
        prefill = asDouble(pending["prefillTokensPerSecond"]),
        decode = asDouble(pending["decodeTokensPerSecond"]),
        turnUsage = copyStringMap(pending["turnUsage"]),
    )
}

@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.pendingAcpPerformanceKey(
    parentTaskId: String,
    entryId: String,
): String = "$parentTaskId\u0000$entryId"

@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.pendingAcpReasoningDataKey(
    parentTaskId: String,
    entryId: String,
): String = "$parentTaskId\u0000$entryId"

@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.hasAgentMessage(
    runtime: ChatConversationRuntimeState,
    entryId: String,
): Boolean = runtime.messages.any { message ->
    message.id == entryId && message.type == 1 && message.user == 2
}

internal fun AgentEventReducer.bufferAcpAssistantPresentation(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    entryId: String,
    key: String,
    value: Map<String, Any?>,
) {
    val pendingKey = pendingAcpAssistantPresentationKey(parentTaskId = parentTaskId, entryId = entryId)
    val pending = runtime.pendingAcpAssistantPresentation[pendingKey] ?: LinkedHashMap()
    pending[key] = value
    runtime.pendingAcpAssistantPresentation[pendingKey] = pending
}

internal fun AgentEventReducer.flushPendingAcpAssistantPresentation(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    entryId: String,
) {
    val pendingKey = pendingAcpAssistantPresentationKey(parentTaskId = parentTaskId, entryId = entryId)
    val pending = runtime.pendingAcpAssistantPresentation.remove(pendingKey)
    if (pending == null || pending.isEmpty()) return
    applyAcpPresentation(
        runtime,
        parentTaskId = parentTaskId,
        entryId = entryId,
        presentation = pending,
    )
}

@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.pendingAcpAssistantPresentationKey(
    parentTaskId: String,
    entryId: String,
): String = "$parentTaskId\u0000$entryId"

@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.writeAcpPerformanceMetrics(
    runtime: ChatConversationRuntimeState,
    index: Int,
    prefill: Double?,
    decode: Double?,
    turnUsage: Map<String, Any?>?,
) {
    val existing = runtime.messages[index]
    val content: JsonMap = LinkedHashMap(existing.content ?: emptyMap())
    if (prefill != null) content["prefillTokensPerSecond"] = prefill
    if (decode != null) content["decodeTokensPerSecond"] = decode
    runtime.messages[index] = existing.copyWith(
        content = content,
        turnUsage = if (turnUsage == null) {
            existing.turnUsage
        } else {
            LinkedHashMap<String, Any?>().apply {
                existing.turnUsage?.let { putAll(it) }
                putAll(turnUsage)
            }
        },
    )
}

internal fun AgentEventReducer.upsertAcpRetryPresentation(
    runtime: ChatConversationRuntimeState,
    entryId: String,
    retry: Map<String, Any?>,
) {
    val index = runtime.messages.indexOfFirst { message -> message.id == entryId }
    if (index == -1) return
    val existing = runtime.messages[index]
    val content: JsonMap = LinkedHashMap(existing.content ?: emptyMap())
    content["text"] = dartToString(content["text"] ?: "")
    content["id"] = entryId
    content["agentRetrying"] = true
    content["agentRetryStatusText"] = acpExtractText(retry["message"]) ?: "正在重试…"
    if (asInt(retry["count"]) != null) content["agentRetryCount"] = asInt(retry["count"])
    if (asInt(retry["maxRetries"]) != null) content["agentMaxRetries"] = asInt(retry["maxRetries"])
    if (asInt(retry["delayMs"]) != null) content["agentRetryDelayMs"] = asInt(retry["delayMs"])
    if (acpExtractText(retry["reason"]) != null) content["agentRetryReason"] = acpExtractText(retry["reason"])
    content["agentRetryable"] = true
    // Dart builds a ChatMessageModel here only to read its streamMeta; the
    // start-time lookup is kept for its caching side effect.
    val retryStreamMeta = this.streamMeta(
        runtime,
        parentTaskId = runtime.activeRunId ?: entryId,
        entryId = entryId,
        kind = "retrying",
        existingMessage = existing,
    )
    startTimeForEntry(runtime, entryId, existingMessage = existing)
    runtime.messages[index] = existing.copyWith(
        content = content,
        isError = false,
        streamMeta = retryStreamMeta,
    )
}

@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.upsertAcpRecoveryPresentation(
    runtime: ChatConversationRuntimeState,
    entryId: String,
    recovery: Map<String, Any?>,
) {
    val index = runtime.messages.indexOfFirst { message -> message.id == entryId }
    if (index == -1) {
        return
    }
    val existing = runtime.messages[index]
    val content: JsonMap = LinkedHashMap(existing.content ?: emptyMap())
    val error = acpExtractText(recovery["error"])
    if (error != null && error.isNotEmpty()) {
        content["agentErrorText"] = error
    }
    content["agentRetryable"] = recovery["retryable"] == true
    val persistAsError = recovery["persistAsError"]
    runtime.messages[index] = existing.copyWith(
        content = content,
        isError = if (persistAsError is Boolean) {
            persistAsError
        } else {
            error != null && error.isNotEmpty()
        },
    )
}

@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.applyAcpClarificationPresentation(
    runtime: ChatConversationRuntimeState,
    entryId: String,
    clarification: Map<String, Any?>,
) {
    val index = runtime.messages.indexOfFirst { message -> message.id == entryId }
    if (index == -1) {
        return
    }
    val existing = runtime.messages[index]
    val content: JsonMap = LinkedHashMap(existing.content ?: emptyMap())
    val question = acpExtractText(clarification["question"])
    val missingFields = acpStringList(
        clarification["missingFields"] ?: clarification["missing_fields"],
    )
    content["agentClarificationRequired"] = true
    if (question != null && question.isNotEmpty()) {
        content["agentClarificationQuestion"] = question
    }
    if (missingFields.isNotEmpty()) {
        content["agentClarificationMissingFields"] = missingFields
    }
    runtime.messages[index] = existing.copyWith(content = content)
}

@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.touchActiveTurn(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
) {
    runtime.completedAgentTurnIds.remove(parentTaskId)
    runtime.isAiResponding = true
    if (runtime.activeRunId == null) runtime.activeRunId = parentTaskId
    // currentDispatchTurnId is now only a compatibility alias for activeRunId
    // and therefore must not be overwritten with the official ACP turn id.
    runtime.lastAgentTurnId = runtime.activeRunId
    runtime.currentThinkingStage = ThinkingStage.THINKING
}

internal fun AgentEventReducer.appendAssistantText(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    entryId: String,
    delta: String,
    isFinal: Boolean,
    replace: Boolean = false,
) {
    val messageId = entryId
    val index = runtime.messages.indexOfFirst { message -> message.id == messageId }
    val cachedText = runtime.currentAiMessages[messageId]
    val previous = cachedText ?: (if (index == -1) "" else runtime.messages[index].text ?: "")
    val effectiveDelta = if (replace) {
        delta
    } else {
        deduplicateReplayDelta(
            runtime,
            entryId = messageId,
            existingText = previous,
            delta = delta,
            hasLiveCache = cachedText != null,
        )
    }
    if (effectiveDelta == null) {
        return
    }
    touchActiveTurn(runtime, parentTaskId)
    val next = if (replace) effectiveDelta else previous + effectiveDelta
    runtime.agentReplayDeltaOffsets.remove(messageId)
    runtime.currentAiMessages[messageId] = next
    if (next.isEmpty() && index == -1) {
        return
    }
    val existing = if (index == -1) null else runtime.messages[index]
    val textStreamMeta = this.streamMeta(
        runtime,
        parentTaskId = parentTaskId,
        entryId = messageId,
        kind = "text_snapshot",
        isFinal = isFinal,
        existingMessage = existing,
    )
    val content: JsonMap = jsonMapOf("text" to next, "id" to messageId)
    if (index == -1) {
        runtime.messages.add(
            0,
            ChatMessage(
                id = messageId,
                type = 1,
                user = 2,
                content = content,
                streamMeta = textStreamMeta,
                createAtMillis = startTimeForEntry(runtime, messageId, existingMessage = existing),
            ),
        )
    } else {
        runtime.messages[index] = runtime.messages[index].copyWith(
            content = content,
            isLoading = false,
            isError = false,
            streamMeta = textStreamMeta,
        )
    }
    flushPendingAcpPerformanceMetrics(runtime, parentTaskId = parentTaskId, entryId = messageId)
    flushPendingAcpAssistantPresentation(runtime, parentTaskId = parentTaskId, entryId = messageId)
}

internal fun AgentEventReducer.upsertAcpAssistantMedia(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    entryId: String,
    media: List<Map<String, Any?>>,
) {
    for (index in media.indices) {
        val item = media[index]
        val mediaType = acpString(item["mediaType"])?.lowercase()
        val audioDataUrl = acpString(item["audioDataUrl"])
        val audioUrl = acpString(item["audioUrl"])
        val imageDataUrl = acpString(item["imageDataUrl"])
        val imageUrl = acpString(item["imageUrl"])
        if (mediaType == "audio" &&
            (audioDataUrl?.trim()?.isNotEmpty() == true || audioUrl?.trim()?.isNotEmpty() == true)
        ) {
            val cardId = "$entryId-agent-audio-$index"
            upsertToolCard(
                runtime,
                cardId = cardId,
                taskId = parentTaskId,
                toolType = "audio",
                title = acpString(item["title"]) ?: "音频",
                status = "success",
                summary = acpString(item["title"]) ?: "音频",
                progress = "",
                raw = jsonMapOf(
                    "type" to "acp_audio",
                    "toolType" to "audio",
                    "toolName" to "assistant_media",
                    "title" to (acpString(item["title"]) ?: "音频"),
                ).apply {
                    if (audioDataUrl != null) put("audioDataUrl", audioDataUrl)
                    if (audioUrl != null) put("audioUrl", audioUrl)
                    if (item["mimeType"] != null) put("mimeType", item["mimeType"])
                },
                streamMeta = this.streamMeta(
                    runtime,
                    parentTaskId = parentTaskId,
                    entryId = cardId,
                    kind = "assistant_media",
                    isFinal = true,
                ),
            )
            continue
        }
        val location = imageDataUrl ?: imageUrl
        if (location == null || location.trim().isEmpty()) continue
        val cardId = "$entryId-agent-image-$index"
        upsertToolCard(
            runtime,
            cardId = cardId,
            taskId = parentTaskId,
            toolType = "image",
            title = acpString(item["title"]) ?: "图片",
            status = "success",
            summary = acpString(item["title"]) ?: "图片",
            progress = "",
            raw = jsonMapOf(
                "type" to "image",
                "toolType" to "image",
                "toolName" to "assistant_media",
                "title" to (acpString(item["title"]) ?: "图片"),
            ).apply {
                if (imageDataUrl != null) put("imageDataUrl", imageDataUrl)
                if (imageUrl != null) put("imageUrl", imageUrl)
                if (item["mimeType"] != null) put("mimeType", item["mimeType"])
            },
            streamMeta = this.streamMeta(
                runtime,
                parentTaskId = parentTaskId,
                entryId = cardId,
                kind = "assistant_media",
                isFinal = true,
            ),
        )
    }
}

internal fun AgentEventReducer.upsertAcpAssistantArtifacts(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    entryId: String,
    artifacts: List<Map<String, Any?>>,
) {
    if (artifacts.isEmpty()) return
    upsertArtifactCards(
        runtime,
        taskId = parentTaskId,
        parentCardId = entryId,
        artifacts = artifacts,
    )
}

internal fun AgentEventReducer.appendThinking(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    cardId: String,
    delta: String,
    reasoningCardData: Map<String, Any?> = emptyMap(),
) {
    // Merge chunks only while the same continuous reasoning segment is
    // active. Tool and output boundaries finalize that segment, so later
    // reasoning in the same ACP turn starts a separate timeline card.
    val resolvedCardId = thinkingCardIdForTask(
        runtime,
        parentTaskId = parentTaskId,
        requestedCardId = cardId,
    )
    val index = runtime.messages.indexOfFirst { message -> message.id == resolvedCardId }
    val existingContent = if (index == -1) {
        ""
    } else {
        dartToString(runtime.messages[index].cardData?.get("thinkingContent") ?: "") ?: ""
    }
    val cachedThinking = if (runtime.activeThinkingCardId == resolvedCardId) {
        runtime.currentThinkingMessages[parentTaskId]
    } else {
        null
    }
    val baseContent = cachedThinking ?: existingContent
    val effectiveDelta = deduplicateReplayDelta(
        runtime,
        entryId = resolvedCardId,
        existingText = baseContent,
        delta = delta,
        hasLiveCache = cachedThinking != null,
    ) ?: return
    touchActiveTurn(runtime, parentTaskId)
    runtime.isDeepThinking = true
    runtime.currentThinkingStage = ThinkingStage.THINKING
    runtime.activeThinkingCardId = resolvedCardId
    val nextContent = baseContent + effectiveDelta
    runtime.agentReplayDeltaOffsets.remove(resolvedCardId)
    runtime.currentThinkingMessages[parentTaskId] = nextContent
    runtime.deepThinkingContent = nextContent
    upsertThinkingCard(
        runtime,
        taskId = parentTaskId,
        cardId = resolvedCardId,
        thinkingContent = nextContent,
        isLoading = true,
        stage = ThinkingStage.THINKING,
        reasoningCardData = reasoningCardData,
        streamMeta = this.streamMeta(
            runtime,
            parentTaskId = parentTaskId,
            entryId = resolvedCardId,
            kind = "thinking_snapshot",
            existingMessage = if (index == -1) null else runtime.messages[index],
        ),
    )
}

internal fun AgentEventReducer.upsertThinkingCard(
    runtime: ChatConversationRuntimeState,
    taskId: String,
    cardId: String,
    thinkingContent: String,
    isLoading: Boolean,
    stage: Int,
    reasoningCardData: Map<String, Any?> = emptyMap(),
    streamMeta: Map<String, Any?>,
) {
    if (isLoading) {
        finalizeOtherLoadingThinkingCardsForTask(
            runtime,
            parentTaskId = taskId,
            activeCardId = cardId,
        )
        runtime.activeThinkingCardId = cardId
    }
    val index = runtime.messages.indexOfFirst { message -> message.id == cardId }
    val existing = if (index == -1) null else runtime.messages[index]
    val existingCardData: Map<String, Any?> = existing?.cardData ?: emptyMap()
    val startTime: Long = asLong(existingCardData["startTime"])
        ?: startTimeForEntry(runtime, cardId, existingMessage = existing)
    val endTime: Any? = if (isLoading) {
        existingCardData["endTime"]
    } else {
        existingCardData["endTime"] ?: System.currentTimeMillis()
    }
    val cardData: JsonMap = jsonMapOf(
        "type" to "deep_thinking",
        "isLoading" to isLoading,
        "thinkingContent" to if (thinkingContent.isNotEmpty()) {
            thinkingContent
        } else {
            dartToString(existingCardData["thinkingContent"] ?: "")
        },
        "stage" to stage,
        "taskID" to taskId,
        "runId" to taskId,
        "cardId" to cardId,
        "startTime" to startTime,
        "endTime" to endTime,
        "isCollapsible" to !isLoading,
    )
    cardData.putAll(preservedAcpReasoningCardData(existingCardData))
    cardData.putAll(reasoningCardData)
    val message = ChatMessage(
        id = cardId,
        type = 2,
        user = 3,
        content = jsonMapOf("cardData" to cardData, "id" to cardId),
        streamMeta = streamMeta,
        createAtMillis = startTime,
    )
    if (index == -1) {
        runtime.messages.add(0, message)
    } else {
        runtime.messages[index] = existing!!.copyWith(
            content = jsonMapOf("cardData" to cardData, "id" to cardId),
            streamMeta = streamMeta,
        )
    }
}

internal fun AgentEventReducer.appendToolOutput(
    runtime: ChatConversationRuntimeState,
    cardId: String,
    taskId: String,
    toolType: String,
    title: String,
    outputDelta: String,
    raw: Map<String, Any?>,
    streamMeta: Map<String, Any?>,
) {
    val index = runtime.messages.indexOfFirst { message -> message.id == cardId }
    val existingCardData: Map<String, Any?> = if (index == -1) {
        emptyMap()
    } else {
        runtime.messages[index].cardData ?: emptyMap()
    }
    val existingOutput = dartToString(existingCardData["terminalOutput"] ?: "") ?: ""
    val output = acpTrimTerminalOutput(existingOutput + outputDelta)
    upsertToolCard(
        runtime,
        cardId = cardId,
        taskId = taskId,
        toolType = toolType,
        title = title,
        status = "running",
        summary = if (outputDelta.isNotEmpty()) outputDelta.trim() else title,
        progress = outputDelta,
        terminalOutput = output,
        raw = raw,
        streamMeta = streamMeta,
    )
}

internal fun AgentEventReducer.appendAcpToolContent(
    runtime: ChatConversationRuntimeState,
    taskId: String,
    toolCallId: String?,
    content: Any?,
    raw: Map<String, Any?>,
) {
    val callId = toolCallId?.trim()
    if (callId == null || callId.isEmpty()) {
        return
    }
    val existingCardId = findToolCardIdForCallId(runtime, callId, taskId = taskId)
    val existing: Map<String, Any?>? = if (existingCardId == null) null else toolCardData(runtime, existingCardId)
    val existingContent = acpContentItems(existing?.get("contentItems"))
    val incomingContent = acpContentItems(content)
    if (incomingContent.isEmpty() && acpExtractStreamingText(content) == null) {
        return
    }
    val mergedContent = ArrayList<Map<String, Any?>>().apply {
        addAll(existingContent)
        addAll(incomingContent)
    }
    val presentation = acpStandardToolPresentation(mergedContent)
    val textDelta = acpExtractStreamingText(content) ?: ""
    val existingStatus = dartToString(existing?.get("status") ?: "running") ?: "running"
    val cardId = existingCardId ?: taskScopedCardId(
        runtime,
        taskId = taskId,
        baseCardId = "$callId-agent-tool",
    )
    val existingRaw = copyStringMap(
        decodeJsonValue(dartToString(existing?.get("rawResultJson") ?: "") ?: ""),
    )
    val rawCard: JsonMap = LinkedHashMap<String, Any?>().apply {
        existingRaw?.let { putAll(it) }
        putAll(raw)
        put("toolCallId", callId)
        put("contentItems", mergedContent)
        put("content", mergedContent)
        putAll(presentation)
    }
    upsertToolCard(
        runtime,
        cardId = cardId,
        taskId = taskId,
        toolType = dartToString(presentation["toolType"] ?: existing?.get("toolType") ?: "tool") ?: "tool",
        title = dartToString(existing?.get("toolTitle") ?: existing?.get("displayName") ?: "工具") ?: "工具",
        status = existingStatus,
        summary = textDelta,
        progress = textDelta,
        terminalOutput = dartToString(existing?.get("terminalOutput")) ?: "",
        raw = rawCard,
        streamMeta = this.streamMeta(
            runtime,
            parentTaskId = taskId,
            entryId = cardId,
            kind = "tool_content_delta",
            existingMessage = if (existing == null) {
                null
            } else {
                runtime.messages.first { message -> message.id == cardId }
            },
        ),
    )
}

@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.upsertPermissionCard(
    runtime: ChatConversationRuntimeState,
    cardId: String,
    taskId: String,
    permission: Map<String, Any?>,
    streamMeta: Map<String, Any?>,
) {
    val index = runtime.messages.indexOfFirst { message -> message.id == cardId }
    val existing = if (index == -1) null else runtime.messages[index]
    val requiredPermissionIds = cardsResolveExecutionPermissionIds(
        permission["requiredPermissionIds"] as Iterable<*>?,
    )
    val missing: List<String> = (permission["missing"] as Iterable<*>?)
        ?.map { value -> value.toString() }
        ?.filter { value -> value.trim().isNotEmpty() }
        ?: emptyList()
    val cardData: JsonMap = jsonMapOf(
        "type" to "permission_section",
        "taskId" to taskId,
        "runId" to taskId,
        "cardId" to cardId,
        "requiredPermissionIds" to requiredPermissionIds,
        "missing" to missing,
        // This flag is intentionally live-only. Permission cards restored from
        // history do not carry it, so reopening a conversation cannot launch a
        // settings page unexpectedly.
        "autoOpenAuthorization" to true,
        "permissionSource" to "acp_tool_result",
    ).apply {
        if (existing?.cardData?.get("requestId") != null) put("requestId", existing.cardData!!["requestId"])
        if (existing?.cardData?.get("toolCallId") != null) put("toolCallId", existing.cardData!!["toolCallId"])
        if (existing?.cardData?.get("sessionId") != null) put("sessionId", existing.cardData!!["sessionId"])
    }
    val message = ChatMessage.cardMessage(cardData, id = cardId, streamMeta = streamMeta)
    if (index == -1) {
        runtime.messages.add(0, message)
    } else {
        runtime.messages[index] = existing!!.copyWith(
            content = jsonMapOf("cardData" to cardData, "id" to cardId),
            streamMeta = streamMeta,
        )
    }
    runtime.isAiResponding = true
    runtime.lastAgentToolType = "permission"
}

internal fun AgentEventReducer.upsertToolCard(
    runtime: ChatConversationRuntimeState,
    cardId: String,
    taskId: String,
    toolType: String,
    title: String,
    status: String,
    summary: String,
    progress: String,
    raw: Map<String, Any?>,
    streamMeta: Map<String, Any?>,
    touchTurn: Boolean = true,
    terminalOutput: String = "",
) {
    if (touchTurn) {
        touchActiveTurn(runtime, taskId)
    }
    val index = runtime.messages.indexOfFirst { message -> message.id == cardId }
    val existing = if (index == -1) null else runtime.messages[index]
    val existingCardData: Map<String, Any?> = existing?.cardData ?: emptyMap()
    val identity = AgentToolIdentity.fromMaps(raw = raw, existing = existingCardData)
    val toolInfo = normalizeAgentToolCall(
        raw,
        fallbackToolType = toolType,
        fallbackTitle = title,
        fallbackStatus = status,
    )
    val effectiveToolType = if (toolInfo.toolType.isNotEmpty()) toolInfo.toolType else toolType
    val effectiveTitle = if (toolInfo.toolTitle.isNotEmpty()) toolInfo.toolTitle else title
    val normalizedSummary = when {
        summary.isNotEmpty() -> summary
        toolInfo.summary.isNotEmpty() -> toolInfo.summary
        else -> ""
    }
    val normalizedProgress = when {
        progress.isNotEmpty() -> progress
        toolInfo.progress.isNotEmpty() -> toolInfo.progress
        else -> ""
    }
    val effectiveTerminalOutput = when {
        terminalOutput.isNotEmpty() -> terminalOutput
        toolInfo.terminalOutput.isNotEmpty() -> toolInfo.terminalOutput
        else -> dartToString(existingCardData["terminalOutput"] ?: "") ?: ""
    }
    val diffText = if (effectiveToolType == "file") {
        resolveFileDiffText(
            existingCardData = existingCardData,
            raw = raw,
            terminalOutput = effectiveTerminalOutput,
            progress = normalizedProgress,
            summary = normalizedSummary,
        )
    } else {
        ""
    }
    val diffSummary = if (diffText.isEmpty()) null else parseAgentDiffText(diffText)
    val diffPreview = if (diffSummary == null) "" else summarizeAgentDiff(diffSummary)
    val effectiveSummary = when {
        effectiveToolType == "file" && diffPreview.isNotEmpty() -> diffPreview
        normalizedSummary.isNotEmpty() -> normalizedSummary
        else -> dartToString(existingCardData["summary"] ?: "") ?: ""
    }
    val effectiveProgress = when {
        effectiveToolType == "file" && diffPreview.isNotEmpty() -> diffPreview
        normalizedProgress.isNotEmpty() -> normalizedProgress
        else -> dartToString(existingCardData["progress"] ?: "") ?: ""
    }
    val resolvedFilePath = if (effectiveToolType == "file") {
        resolveFilePath(raw)
            ?: (if (diffSummary?.primaryPath?.trim()?.isNotEmpty() == true) diffSummary.primaryPath else null)
            ?: dartToString(existingCardData["filePath"] ?: "") ?: ""
    } else {
        ""
    }
    val artifacts = asMapList(raw["artifacts"])
    val actions = asMapList(raw["actions"])
    val planEntries = asMapList(raw["planEntries"] ?: raw["entries"])
    val contentItems = acpContentItems(raw["contentItems"] ?: raw["content"])
    val subagentEvents = mergeAcpSubagentEvents(
        existingCardData["subagentEvents"],
        raw["subagentEvents"] ?: raw["subagentEvent"],
    )
    val cardData: JsonMap = linkedMapOf()
    cardData["type"] = "agent_tool_summary"
    cardData["uiStyle"] = AGENT_TOOL_UI_STYLE
    cardData["taskId"] = taskId
    cardData["runId"] = taskId
    cardData["toolName"] = toolInfo.toolName
    cardData["displayName"] = toolInfo.displayName
    cardData["toolTitle"] = effectiveTitle
    // Keep the ACP card's historical `title` alias for status/error cards
    // and older consumers. New cards should prefer `toolTitle`, but a
    // transport failure such as `turn/failed` must remain discoverable by
    // both shapes during replay.
    cardData["title"] = effectiveTitle
    cardData["cardId"] = cardId
    cardData["toolType"] = effectiveToolType
    if (identity.sessionId != null) cardData["sessionId"] = identity.sessionId
    if (identity.turnId != null) cardData["turnId"] = identity.turnId
    if (identity.toolCallId != null) cardData["toolCallId"] = identity.toolCallId
    if (identity.rawProviderToolCallId != null) cardData["rawProviderToolCallId"] = identity.rawProviderToolCallId
    if (identity.toolKey != null) cardData["toolKey"] = identity.toolKey
    if (toolInfo.serverName != null) cardData["serverName"] = toolInfo.serverName
    cardData["status"] = status
    cardData["summary"] = effectiveSummary
    cardData["progress"] = effectiveProgress
    cardData["argsJson"] = if (toolInfo.argsJson.isNotEmpty()) {
        toolInfo.argsJson
    } else {
        dartToString(existingCardData["argsJson"] ?: acpSafeJson(raw))
    }
    cardData["resultPreviewJson"] = if (toolInfo.resultPreviewJson.isNotEmpty()) {
        toolInfo.resultPreviewJson
    } else {
        dartToString(existingCardData["resultPreviewJson"] ?: "")
    }
    cardData["rawResultJson"] = if (toolInfo.rawResultJson.isNotEmpty()) toolInfo.rawResultJson else acpSafeJson(raw)
    cardData["terminalOutput"] = effectiveTerminalOutput
    cardData["terminalOutputDelta"] = normalizedProgress
    cardData["contentItems"] = if (contentItems.isNotEmpty()) {
        contentItems
    } else {
        acpContentItems(existingCardData["contentItems"])
    }
    cardData["subagentEvents"] = subagentEvents
    if (raw["terminalSessionId"] != null) cardData["terminalSessionId"] = raw["terminalSessionId"]
    if (raw["terminalStreamState"] != null) cardData["terminalStreamState"] = raw["terminalStreamState"]
    if (raw["workspaceId"] != null) cardData["workspaceId"] = raw["workspaceId"]
    if (raw["planId"] != null) cardData["planId"] = raw["planId"]
    cardData["planEntries"] = if (planEntries.isNotEmpty()) {
        planEntries
    } else {
        existingCardData["planEntries"] ?: emptyList<Map<String, Any?>>()
    }
    if (raw["imageDataUrl"] != null) cardData["imageDataUrl"] = raw["imageDataUrl"]
    if (raw["dataUrl"] != null) cardData["dataUrl"] = raw["dataUrl"]
    if (raw["imageUrl"] != null) cardData["imageUrl"] = raw["imageUrl"]
    if (raw["audioDataUrl"] != null) cardData["audioDataUrl"] = raw["audioDataUrl"]
    if (raw["audioUrl"] != null) cardData["audioUrl"] = raw["audioUrl"]
    if (raw["mimeType"] != null) cardData["mimeType"] = raw["mimeType"]
    if (raw["taskId"] != null) cardData["sourceTaskId"] = raw["taskId"]
    // Dart map literal: a repeated key keeps its first position.
    if (raw["runId"] != null) cardData["runId"] = raw["runId"]
    if (raw["run_id"] != null) cardData["run_id"] = raw["run_id"]
    cardData["artifacts"] = if (artifacts.isNotEmpty()) {
        artifacts
    } else {
        existingCardData["artifacts"] ?: emptyList<Map<String, Any?>>()
    }
    cardData["actions"] = if (actions.isNotEmpty()) {
        actions
    } else {
        existingCardData["actions"] ?: emptyList<Map<String, Any?>>()
    }
    cardData["showArtifactAction"] = artifacts.isNotEmpty() || existingCardData["showArtifactAction"] == true
    cardData["showTerminalOutput"] =
        (effectiveTerminalOutput.isNotEmpty() && diffText.isEmpty()) || effectiveToolType == "terminal"
    cardData["showRawResult"] = true
    cardData["showScheduleAction"] = effectiveToolType == "schedule"
    cardData["showAlarmAction"] = effectiveToolType == "alarm"
    // Keep the old card-level detail fields available to every ACP-backed
    // Harness. They are intentionally copied as data, not interpreted here.
    for (key in listOf(
        "message",
        "question",
        "missingFields",
        "missing_fields",
        "missing",
        "previewJson",
        "outputTruncated",
        "originalChars",
        "headTail",
        "fullOutputArtifact",
        "subagentStatusText",
    )) {
        if (raw[key] != null) {
            cardData[key] = raw[key]
        } else if (existingCardData[key] != null) {
            cardData[key] = existingCardData[key]
        }
    }
    if (effectiveToolType == "file") {
        cardData["diffText"] = diffText
        cardData["showDiff"] = diffText.isNotEmpty()
        cardData["filePath"] = resolvedFilePath
        cardData["changedFiles"] = diffSummary?.changedFileCount ?: 0
        cardData["additions"] = diffSummary?.additions ?: 0
        cardData["deletions"] = diffSummary?.deletions ?: 0
    }
    val startTime = startTimeForEntry(runtime, cardId, existingMessage = existing)
    val message = ChatMessage(
        id = cardId,
        type = 2,
        user = 3,
        content = jsonMapOf("cardData" to cardData, "id" to cardId),
        streamMeta = streamMeta,
        createAtMillis = startTime,
    )
    if (index == -1) {
        runtime.messages.add(0, message)
    } else {
        runtime.messages[index] = existing!!.copyWith(
            content = jsonMapOf("cardData" to cardData, "id" to cardId),
            streamMeta = streamMeta,
        )
    }
    upsertArtifactCards(
        runtime,
        taskId = taskId,
        parentCardId = cardId,
        artifacts = artifacts,
    )
    runtime.lastAgentToolType = effectiveToolType
    if (status == "running" || status == "pending" || status == "progress") {
        runtime.activeToolCardId = cardId
    } else if (runtime.activeToolCardId == cardId) {
        runtime.activeToolCardId = findRunningToolCardId(
            runtime,
            taskId = taskId,
            excludingCardId = cardId,
        )
    }
    if (effectiveToolType == "terminal" || effectiveToolType == "browser") {
        runtime.chatIslandDisplayLayer = ChatIslandDisplayLayer.TOOLS
    }
    if (effectiveToolType == "browser" && toolInfo.status == "success") {
        val workspaceId = (dartToString(cardData["workspaceId"] ?: "") ?: "").trim()
        if (workspaceId.isNotEmpty()) {
            runtime.browserSessionSnapshot =
                cardsTryParseBrowserToolJson(
                    rawJson = cardData["rawResultJson"].toString(),
                    workspaceId = workspaceId,
                ) ?: cardsTryParseBrowserToolJson(
                    rawJson = cardData["resultPreviewJson"].toString(),
                    workspaceId = workspaceId,
                )
        }
    }
}

/**
 * ACP tool updates are sparse and a subagent progress event normally
 * arrives one-at-a-time in `rawInput`. Do not overwrite the parent card's
 * previous events with the latest singleton; retain the complete child
 * timeline while keeping repeated/replayed updates idempotent.
 */
@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.mergeAcpSubagentEvents(
    existingRaw: Any?,
    incomingRaw: Any?,
): MutableList<JsonMap> {
    val merged = ArrayList<JsonMap>()
    val positions = LinkedHashMap<String, Int>()

    fun normalize(value: Any?): List<JsonMap> {
        if (value is List<*>) {
            return value.filterIsInstance<Map<*, *>>().map { copyStringMap(it)!! }
        } else if (value is Map<*, *>) {
            return listOf(copyStringMap(value)!!)
        }
        return emptyList()
    }

    fun add(value: Any?) {
        for (event in normalize(value)) {
            val identity = (dartToString(event["id"] ?: "") ?: "").trim()
            val key = if (identity.isNotEmpty()) {
                "id:$identity"
            } else {
                listOf(
                    event["subagentId"] ?: event["subagent_id"] ?: "",
                    event["taskIndex"] ?: event["task_index"] ?: "",
                    event["kind"] ?: "",
                    event["seq"] ?: event["sequence"] ?: "",
                    event["summary"] ?: event["message"] ?: event["text"] ?: "",
                ).joinToString("|") { part -> dartToString(part) ?: "" }
            }
            // ACP adapters may replay the same child event id with a newer
            // status/summary. Replace that snapshot in place instead of
            // dropping it, while still preventing duplicate delivery from
            // growing the timeline indefinitely.
            val previousIndex = positions[key]
            if (previousIndex == null) {
                positions[key] = merged.size
                merged.add(event)
            } else {
                merged[previousIndex] = event
            }
        }
    }

    add(existingRaw)
    add(incomingRaw)

    // Streaming thinking/message updates are cumulative snapshots. Keep the
    // newest snapshot per child, but never collapse lifecycle events such as
    // started/completed/failed, which are needed to show each subtask.
    val latestStreaming = LinkedHashMap<String, JsonMap>()
    val retained = ArrayList<JsonMap>()
    for (event in merged) {
        val kind = (dartToString(event["kind"] ?: "") ?: "").trim().lowercase()
        if (kind != "thinking" && kind != "message") {
            retained.add(event)
            continue
        }
        val child = (dartToString(event["subagentId"] ?: event["subagent_id"] ?: "") ?: "").trim()
        val task = (dartToString(event["taskIndex"] ?: event["task_index"] ?: "") ?: "").trim()
        val group = "${if (child.isNotEmpty()) child else "task:$task"}|$kind"
        val previous = latestStreaming[group]
        val previousSeq = asInt(previous?.get("seq") ?: previous?.get("sequence"))
        val currentSeq = asInt(event["seq"] ?: event["sequence"])
        if (previous == null || (currentSeq ?: -1) >= (previousSeq ?: -1)) {
            latestStreaming[group] = event
        }
    }
    retained.addAll(latestStreaming.values)
    // Note: Kotlin's sort is stable; Dart's List.sort is stable only for
    // short lists (insertion sort up to 32 elements).
    retained.sortWith { left, right ->
        val leftSeq = asInt(left["seq"] ?: left["sequence"]) ?: 0
        val rightSeq = asInt(right["seq"] ?: right["sequence"]) ?: 0
        if (leftSeq != rightSeq) return@sortWith leftSeq.compareTo(rightSeq)
        val leftCreated = asLong(left["createdAt"] ?: left["created_at"]) ?: 0L
        val rightCreated = asLong(right["createdAt"] ?: right["created_at"]) ?: 0L
        leftCreated.compareTo(rightCreated)
    }
    return retained
}

@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.findRunningToolCardId(
    runtime: ChatConversationRuntimeState,
    taskId: String,
    excludingCardId: String,
): String? {
    // Messages are newest-first. Selecting the newest still-running card
    // keeps the shared stop action useful when a Harness runs tools in
    // parallel and one of them completes before the others.
    for (message in runtime.messages) {
        if (message.id == excludingCardId || message.type != 2) continue
        val card = message.cardData
        if (card == null || card["type"] != "agent_tool_summary") continue
        val cardTaskId = acpFirstString(card["taskId"], card["runId"], card["parentTaskId"])
        if (cardTaskId != taskId) continue
        val cardStatus = acpString(card["status"])?.lowercase()
        if (cardStatus == "running" ||
            cardStatus == "pending" ||
            cardStatus == "progress" ||
            cardStatus == "in_progress"
        ) {
            return message.id
        }
    }
    return null
}

internal fun AgentEventReducer.upsertArtifactCards(
    runtime: ChatConversationRuntimeState,
    taskId: String,
    parentCardId: String,
    artifacts: List<Map<String, Any?>>,
) {
    for (index in artifacts.indices) {
        val artifact = artifacts[index]
        val rawArtifactId = acpString(artifact["id"])?.trim()
        val artifactId = if (rawArtifactId == null || rawArtifactId.isEmpty()) index.toString() else rawArtifactId
        val cardId = "$parentCardId-artifact-$artifactId"
        val existingIndex = runtime.messages.indexOfFirst { message -> message.id == cardId }
        val existing = if (existingIndex == -1) null else runtime.messages[existingIndex]
        val startTime = startTimeForEntry(runtime, cardId, existingMessage = existing)
        val cardData: JsonMap = jsonMapOf(
            "type" to "artifact_card",
            "artifact" to artifact,
            "taskId" to taskId,
            "runId" to taskId,
            "cardId" to cardId,
        )
        val message = ChatMessage(
            id = cardId,
            type = 2,
            user = 3,
            content = jsonMapOf("cardData" to cardData, "id" to cardId),
            streamMeta = this.streamMeta(
                runtime,
                parentTaskId = taskId,
                entryId = cardId,
                kind = "artifact",
                isFinal = true,
                existingMessage = existing,
            ),
            createAtMillis = startTime,
        )
        if (existingIndex == -1) {
            runtime.messages.add(0, message)
        } else {
            runtime.messages[existingIndex] = existing!!.copyWith(
                content = jsonMapOf("cardData" to cardData, "id" to cardId),
                streamMeta = message.streamMeta,
            )
        }
    }
}

internal fun AgentEventReducer.upsertAgentRequestCard(
    runtime: ChatConversationRuntimeState,
    cardId: String,
    taskId: String,
    requestId: Any?,
    requestKind: String,
    title: String,
    detail: String,
    params: Map<String, Any?>,
    streamMeta: Map<String, Any?>,
    agentId: String? = null,
    agentName: String? = null,
    sessionId: String? = null,
    toolCallId: String? = null,
    questionId: String? = null,
    structuredElicitation: Boolean = false,
) {
    touchActiveTurn(runtime, taskId)
    val index = runtime.messages.indexOfFirst { message -> message.id == cardId }
    val existing = if (index == -1) null else runtime.messages[index]
    val startTime = startTimeForEntry(runtime, cardId, existingMessage = existing)
    val existingRequestId = acpString(existing?.cardData?.get("requestId"))?.trim()
    val nextRequestId = acpString(requestId)?.trim()
    val shouldPreserveExistingStatus =
        nextRequestId != null && nextRequestId.isNotEmpty() && existingRequestId == nextRequestId
    val existingCardData: Map<String, Any?> = existing?.cardData ?: emptyMap()
    val requestAgentId = acpFirstString(
        agentId,
        params["agentId"],
        params["agent_id"],
        existingCardData["agentId"],
        existingCardData["agent_id"],
    )
    val requestAgentName = acpFirstString(
        agentName,
        params["agentName"],
        params["agent_name"],
        existingCardData["agentName"],
        existingCardData["agent_name"],
    )
    val requestSessionId = acpFirstString(
        sessionId,
        params["sessionId"],
        params["session_id"],
        existingCardData["sessionId"],
        existingCardData["session_id"],
    )
    val status = resolveRequestStatus(
        requestKind = requestKind,
        params = params,
        existingStatus = if (shouldPreserveExistingStatus) existingCardData["status"] else null,
    )
    val cardData: JsonMap = linkedMapOf()
    cardData["type"] = AGENT_REQUEST_CARD_TYPE
    cardData["taskId"] = taskId
    cardData["runId"] = taskId
    cardData["requestId"] = requestId
    if (requestId == null) cardData["interactionUnavailable"] = true
    if (requestAgentId != null) cardData["agentId"] = requestAgentId
    if (requestAgentName != null) cardData["agentName"] = requestAgentName
    if (requestSessionId != null) cardData["sessionId"] = requestSessionId
    if (toolCallId != null && toolCallId.trim().isNotEmpty()) cardData["toolCallId"] = toolCallId.trim()
    cardData["requestKind"] = requestKind
    cardData["title"] = title
    cardData["detail"] = detail
    cardData["questionId"] = questionId
    if (structuredElicitation) cardData["structuredElicitation"] = true
    cardData["rawParamsJson"] = acpSafeJson(params)
    cardData["status"] = status
    cardData["conversationId"] = runtime.conversationId
    cardData["cardId"] = cardId
    cardData["startTime"] = startTime
    val message = ChatMessage(
        id = cardId,
        type = 2,
        user = 3,
        content = jsonMapOf("cardData" to cardData, "id" to cardId),
        streamMeta = streamMeta,
        createAtMillis = startTime,
    )
    if (index == -1) {
        runtime.messages.add(0, message)
    } else {
        runtime.messages[index] = runtime.messages[index].copyWith(
            content = jsonMapOf("cardData" to cardData, "id" to cardId),
            streamMeta = streamMeta,
        )
    }
    runtime.isAiResponding = true
}

internal fun AgentEventReducer.resolveRequestStatus(
    requestKind: String,
    params: Map<String, Any?>,
    existingStatus: Any?,
): String {
    val existing = normalizeRequestStatus(existingStatus, requestKind = requestKind)
    if (isTerminalRequestStatus(existing)) {
        return existing!!
    }
    val explicit = normalizeRequestStatus(
        acpFirstString(
            params["status"],
            params["state"],
            params["requestStatus"],
            params["request_status"],
            copyStringMap(params["request"])?.get("status"),
            copyStringMap(params["request"])?.get("state"),
        ),
        requestKind = requestKind,
    )
    if (explicit != null && explicit != "pending") {
        return explicit
    }
    val response = params["response"]
        ?: params["answer"]
        ?: params["answers"]
        ?: params["result"]
        ?: params["decision"]
    if (response != null) {
        if (requestKind == "approval") {
            val decision = acpFirstString(
                response,
                copyStringMap(response)?.get("decision"),
                copyStringMap(response)?.get("status"),
                copyStringMap(response)?.get("state"),
            )?.lowercase()
            if (decision == "accept" ||
                decision == "accepted" ||
                decision == "approve" ||
                decision == "approved" ||
                decision == "yes"
            ) {
                return "accepted"
            }
            if (decision == "decline" ||
                decision == "declined" ||
                decision == "reject" ||
                decision == "rejected" ||
                decision == "no"
            ) {
                return "declined"
            }
        }
        return if (requestKind == "approval") "accepted" else "submitted"
    }
    return explicit ?: existing ?: "pending"
}

@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.normalizeRequestStatus(
    value: Any?,
    requestKind: String,
): String? {
    val normalized = acpString(value)?.trim()?.lowercase()
    if (normalized == null || normalized.isEmpty()) {
        return null
    }
    return when (normalized) {
        "accept", "accepted", "approve", "approved" -> "accepted"
        "decline", "declined", "reject", "rejected" -> "declined"
        "submit", "submitted", "answered", "complete", "completed" ->
            if (requestKind == "approval") "accepted" else "submitted"
        "fail", "failed", "error" -> "failed"
        "pending", "running", "requested", "open" -> "pending"
        else -> normalized
    }
}

@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.isTerminalRequestStatus(status: String?): Boolean =
    status == "submitted" ||
        status == "accepted" ||
        status == "declined" ||
        status == "cancelled" ||
        status == "interrupted" ||
        status == "failed"

@Suppress("UnusedReceiverParameter")
internal fun AgentEventReducer.deduplicateReplayDelta(
    runtime: ChatConversationRuntimeState,
    entryId: String,
    existingText: String,
    delta: String,
    hasLiveCache: Boolean,
): String? {
    if (delta.isEmpty() || existingText.isEmpty()) {
        runtime.agentReplayDeltaOffsets.remove(entryId)
        return delta
    }
    if (hasLiveCache) {
        // Official ACP emits committed assistant message blocks rather than
        // token deltas. A reconnect/retry can deliver the same committed block
        // again, or a provider can send a cumulative block for the same
        // messageId. Keep the live stream idempotent.
        if (delta == existingText) {
            return null
        }
        if (delta.startsWith(existingText)) {
            return delta.substring(existingText.length)
        }
        runtime.agentReplayDeltaOffsets.remove(entryId)
        return delta
    }
    val previousOffset = runtime.agentReplayDeltaOffsets[entryId] ?: 0
    val safeOffset = previousOffset.coerceIn(0, existingText.length)
    val remaining = existingText.substring(safeOffset)
    if (!remaining.startsWith(delta)) {
        runtime.agentReplayDeltaOffsets[entryId] = existingText.length
        return delta
    }
    val nextOffset = safeOffset + delta.length
    if (nextOffset >= existingText.length) {
        runtime.agentReplayDeltaOffsets.remove(entryId)
    } else {
        runtime.agentReplayDeltaOffsets[entryId] = nextOffset
    }
    return null
}

// ---------------------------------------------------------------------------
// File-private ports of Dart helpers that live outside the reducer library.
// ---------------------------------------------------------------------------

private val cardsExecutionPermissionIds = setOf(
    "accessibility",
    "overlay",
    "installed_apps",
    "shizuku",
    "workspace_storage",
    "public_storage",
)

private val cardsExecutionPermissionAliases = mapOf(
    "无障碍权限" to "accessibility",
    "Android GUI 无障碍权限" to "accessibility",
    "Accessibility" to "accessibility",
    "Accessibility Permission" to "accessibility",
    "悬浮窗权限" to "overlay",
    "Overlay" to "overlay",
    "应用列表读取权限" to "installed_apps",
    "Installed Apps Access" to "installed_apps",
    "Shizuku 权限" to "shizuku",
    "Shizuku Permission" to "shizuku",
    "内置 workspace" to "workspace_storage",
    "Built-in workspace" to "workspace_storage",
    "公共文件访问" to "public_storage",
    "Public Storage Access" to "public_storage",
)

/** Dart `resolveExecutionPermissionIds` (authorize_page_args.dart). */
private fun cardsResolveExecutionPermissionIds(rawValues: Iterable<*>?): List<String> {
    if (rawValues == null) return emptyList()
    return rawValues
        .map { item -> item.toString().trim() }
        .map { item -> cardsExecutionPermissionAliases[item] ?: item }
        .filter { it in cardsExecutionPermissionIds }
        .toCollection(LinkedHashSet())
        .toList()
}

/**
 * Dart `ChatBrowserSessionSnapshot.tryParseBrowserToolJson(...)?.toMap()`
 * (chat_page_models.dart). The runtime carries the snapshot as its map form.
 */
private fun cardsTryParseBrowserToolJson(rawJson: String, workspaceId: String): JsonMap? {
    val text = rawJson.trim()
    if (text.isEmpty()) {
        return null
    }
    return try {
        val decoded = DartJson.decode(text)
        if (decoded !is Map<*, *>) return null
        val root = copyStringMap(decoded)!!
        val payload = cardsFindBrowserToolPayload(root) ?: return null
        cardsBrowserSnapshotFromToolPayload(payload, workspaceId)
    } catch (_: Exception) {
        null
    }
}

private fun cardsFindBrowserToolPayload(root: Map<String, Any?>, depth: Int = 0): Map<String, Any?>? {
    if (depth > 4) {
        return null
    }
    val browserFields = setOf(
        "activeTabId",
        "tabId",
        "currentUrl",
        "finalUrl",
        "url",
        "pageTitle",
        "riskChallengeDetected",
    )
    if (root.keys.any { it in browserFields }) {
        return root
    }
    for (key in listOf("result", "rawResult", "rawOutput", "output", "data", "payload")) {
        val value = root[key]
        var child: Map<String, Any?>? = null
        if (value is Map<*, *>) {
            child = copyStringMap(value)
        } else if (value is String && value.trimStart().startsWith("{")) {
            try {
                val decoded = DartJson.decode(value)
                if (decoded is Map<*, *>) {
                    child = copyStringMap(decoded)
                }
            } catch (_: Exception) {
                // The next candidate can still contain the structured browser result.
            }
        }
        if (child == null) {
            continue
        }
        val resolved = cardsFindBrowserToolPayload(child, depth = depth + 1)
        if (resolved != null) {
            return resolved
        }
    }
    return null
}

private fun cardsBrowserString(value: Any?): String = dartToString(value ?: "") ?: ""

private fun cardsBrowserInt(value: Any?): Int? {
    if (value is Number) return value.toInt()
    return cardsBrowserString(value).toIntOrNull()
}

private fun cardsBrowserBool(value: Any?, fallback: Boolean = false): Boolean {
    if (value is Boolean) return value
    val text = cardsBrowserString(value).trim().lowercase()
    if (text == "true" || text == "1") return true
    if (text == "false" || text == "0") return false
    return fallback
}

/** `ChatBrowserSessionSnapshot.fromBrowserToolPayload(...).toMap()`. */
private fun cardsBrowserSnapshotFromToolPayload(payload: Map<String, Any?>, workspaceId: String): JsonMap {
    val activeTabId = cardsBrowserInt(payload["activeTabId"] ?: payload["tabId"])
    val currentUrl = cardsBrowserString(payload["currentUrl"] ?: payload["finalUrl"] ?: payload["url"])
    val title = cardsBrowserString(payload["pageTitle"] ?: payload["title"])
    return jsonMapOf(
        "available" to true,
        "workspaceId" to workspaceId,
        "activeTabId" to activeTabId,
        "currentUrl" to currentUrl,
        "title" to title,
        "userAgentProfile" to dartToString(payload["userAgentProfile"]),
        "isBookmarked" to false,
        "canGoBack" to false,
        "canGoForward" to false,
        "isLoading" to false,
        "hasSslError" to false,
        "isDesktopMode" to false,
        "riskChallengeDetected" to cardsBrowserBool(payload["riskChallengeDetected"]),
        "riskChallengeKind" to dartToString(payload["riskChallengeKind"]),
        "recommendedNextAction" to dartToString(payload["recommendedNextAction"]),
        "throttleDelayMs" to cardsBrowserInt(payload["throttleDelayMs"]),
        "activeDownloadCount" to 0,
        "tabs" to emptyList<Any?>(),
        "bookmarks" to emptyList<Any?>(),
        "history" to emptyList<Any?>(),
        "sessionHistory" to emptyList<Any?>(),
        "downloads" to emptyList<Any?>(),
        "downloadSummary" to jsonMapOf(
            "activeCount" to 0,
            "failedCount" to 0,
            "overallProgress" to null,
            "latestCompletedFileName" to null,
        ),
        "externalOpenPrompt" to null,
        "pendingDialog" to null,
        "permissionPrompt" to null,
        "userscriptSummary" to jsonMapOf(
            "installedScripts" to emptyList<Any?>(),
            "currentPageMenuCommands" to emptyList<Any?>(),
            "pendingInstall" to null,
        ),
    )
}
