package cn.com.omnimind.bot.agent.projection

/*
 * Port of `ui/lib/services/agent_event_reducer.dart` lines 2983-4954:
 * AgentEventReducer instance methods from `_completeItem` to the end of the
 * class body (item completion, raw response items, standalone processes,
 * turn completion/failure, thinking/tool card finalization, identity and
 * request helpers). Keep mechanically comparable with the Dart source.
 */

/** Dart `(value ?? '').toString()`. */
private fun lifecycleDartString(value: Any?): String = dartToString(value ?: "") ?: ""

private val toolCardSuffixes = listOf(
    "command",
    "file",
    "plan",
    "search",
    "workspace",
    "browser",
    "image",
    "tool",
)

internal fun AgentEventReducer.completeItem(
    runtime: ChatConversationRuntimeState,
    taskId: String,
    itemId: String?,
    params: Map<String, Any?>,
) {
    val item: Map<String, Any?> = copyStringMap(params["item"]) ?: params
    val itemType = canonicalAgentItemType(acpString(item["type"]))
    val permissionCard = permissionCardFromAcpItem(item)
    if (permissionCard != null) {
        val completedItemId = itemId ?: acpString(item["id"]) ?: taskId
        val existingCardId = findToolCardIdForCallId(
            runtime,
            completedItemId,
            taskId = taskId,
            sessionId = acpFirstString(
                item["sessionId"],
                item["session_id"],
                params["sessionId"],
                params["session_id"],
            ),
        )
        val cardId = existingCardId ?: taskScopedCardId(
            runtime,
            taskId = taskId,
            baseCardId = "$completedItemId-agent-permission",
        )
        upsertPermissionCard(
            runtime,
            cardId = cardId,
            taskId = taskId,
            permission = permissionCard,
            streamMeta = streamMeta(
                runtime,
                parentTaskId = taskId,
                entryId = cardId,
                kind = "permission_required",
                isFinal = true,
            ),
        )
        return
    }
    val text = acpExtractText(item["text"])
        ?: acpExtractText(item["message"])
        ?: acpExtractText(item["content"])
        ?: ""
    if (itemType == "agentMessage") {
        val messageId = acpString(item["entryId"]) ?: "${itemId ?: taskId}-agent-message"
        val existingText = assistantTextForEntry(runtime, messageId)
        if (text.isNotEmpty() && existingText.isEmpty()) {
            appendAssistantText(
                runtime,
                parentTaskId = taskId,
                entryId = messageId,
                delta = text,
                isFinal = true,
            )
        } else if (text.isNotEmpty() && text != existingText) {
            if (text.startsWith(existingText)) {
                appendAssistantText(
                    runtime,
                    parentTaskId = taskId,
                    entryId = messageId,
                    delta = text.substring(existingText.length),
                    isFinal = true,
                )
            } else {
                appendAssistantText(
                    runtime,
                    parentTaskId = taskId,
                    entryId = messageId,
                    delta = text,
                    isFinal = true,
                    replace = true,
                )
            }
        }
        markAssistantEntryFinal(runtime, taskId, messageId)
        runtime.currentAiMessages.remove("${itemId ?: taskId}-agent-message")
        runtime.agentReplayDeltaOffsets.remove(messageId)
    }
    if (itemType == "reasoning") {
        // Keep the thinking card streaming until the entire turn ends.
        // The owning prompt response finalizes thinking after all item updates.
        val cardId = acpString(item["entryId"]) ?: "${itemId ?: taskId}-agent-thinking"
        markThinkingItemCompleted(runtime, taskId, cardId)
        runtime.agentReplayDeltaOffsets.remove(cardId)
    }
    if (isAgentToolItemType(itemType)) {
        val completedItemId = itemId ?: acpString(item["id"]) ?: taskId
        val existingCardId = findToolCardIdForCallId(
            runtime,
            completedItemId,
            taskId = taskId,
            sessionId = acpFirstString(
                item["sessionId"],
                item["session_id"],
                params["sessionId"],
                params["session_id"],
            ),
        )
        val existingMessage = if (existingCardId == null) {
            null
        } else {
            runtime.messages.firstOrNull { it.id == existingCardId }
        }
        val existing = if (existingCardId == null) null else toolCardData(runtime, existingCardId)
        val itemWithIdentity: JsonMap = LinkedHashMap(item).apply {
            if (params["sessionId"] != null) put("sessionId", params["sessionId"])
            if (params["session_id"] != null) put("session_id", params["session_id"])
            if (params["turnId"] != null) put("turnId", params["turnId"])
            if (params["turn_id"] != null) put("turn_id", params["turn_id"])
        }
        val mergedItem = mergeAgentToolUpdate(existing, itemWithIdentity)
        val mergedItemType = canonicalAgentItemType(acpString(mergedItem["type"]) ?: itemType)
        val toolInfo = normalizeAgentToolCall(
            mergedItem,
            itemType = mergedItemType,
            fallbackToolType = lifecycleDartString(existing?.get("toolType")),
            fallbackTitle = dartToString(existing?.get("toolTitle") ?: existing?.get("displayName")),
            fallbackStatus = "success",
        )
        val suffix = agentToolCardSuffix(toolInfo.toolType, itemType = mergedItemType)
        val cardId = existingCardId ?: taskScopedCardId(
            runtime,
            taskId = taskId,
            baseCardId = toolCardBaseId(
                raw = mergedItem,
                fallback = "$completedItemId-agent-$suffix",
                suffix = suffix,
            ),
        )
        upsertToolCard(
            runtime,
            cardId = cardId,
            taskId = taskId,
            toolType = toolInfo.toolType,
            title = toolInfo.toolTitle,
            status = toolInfo.status,
            summary = toolInfo.summary,
            progress = toolInfo.progress,
            terminalOutput = toolInfo.terminalOutput,
            raw = mergedItem,
            streamMeta = streamMeta(
                runtime,
                parentTaskId = taskId,
                entryId = cardId,
                kind = if (isActiveAgentToolStatus(toolInfo.status)) "tool_progress" else "tool_completed",
                isFinal = !isActiveAgentToolStatus(toolInfo.status),
                existingMessage = existingMessage,
            ),
            touchTurn = false,
        )
        runtime.agentReplayDeltaOffsets.remove(cardId)
        return
    }
    val completedItemId = itemId ?: taskId
    for (suffix in toolCardSuffixes) {
        markToolCardComplete(runtime, "$completedItemId-agent-$suffix")
    }
}

internal fun AgentEventReducer.completeRawResponseItem(
    runtime: ChatConversationRuntimeState,
    taskId: String,
    params: Map<String, Any?>,
) {
    val item: Map<String, Any?> = copyStringMap(params["item"]) ?: params
    val itemType = acpString(item["type"]) ?: ""
    if (isAgentToolOutputItemType(itemType)) {
        completeRawResponseOutputItem(runtime, taskId, params, item, itemType)
        return
    }
    if (!isAgentToolItemType(itemType)) {
        return
    }
    val rawItemId = rawResponseItemId(params, item, taskId)
    val toolInfo = normalizeAgentToolCall(
        item,
        itemType = itemType,
        fallbackStatus = "success",
    )
    val suffix = agentToolCardSuffix(toolInfo.toolType, itemType = itemType)
    val cardId = taskScopedCardId(
        runtime,
        taskId = taskId,
        baseCardId = "$rawItemId-agent-$suffix",
    )
    upsertToolCard(
        runtime,
        cardId = cardId,
        taskId = taskId,
        toolType = toolInfo.toolType,
        title = toolInfo.toolTitle,
        status = toolInfo.status,
        summary = toolInfo.summary,
        progress = toolInfo.progress,
        terminalOutput = toolInfo.terminalOutput,
        raw = item,
        streamMeta = streamMeta(
            runtime,
            parentTaskId = taskId,
            entryId = cardId,
            kind = if (isActiveAgentToolStatus(toolInfo.status)) "tool_progress" else "tool_completed",
            isFinal = !isActiveAgentToolStatus(toolInfo.status),
        ),
        touchTurn = false,
    )
    runtime.agentReplayDeltaOffsets.remove(cardId)
}

internal fun AgentEventReducer.completeRawResponseOutputItem(
    runtime: ChatConversationRuntimeState,
    taskId: String,
    params: Map<String, Any?>,
    item: Map<String, Any?>,
    itemType: String,
) {
    val callId = acpFirstString(
        item["callId"],
        item["call_id"],
        params["callId"],
        params["call_id"],
    )
    val existingCardId = if (callId == null) {
        null
    } else {
        findToolCardIdForCallId(
            runtime,
            callId,
            taskId = taskId,
            sessionId = acpFirstString(
                item["sessionId"],
                item["session_id"],
                params["sessionId"],
                params["session_id"],
            ),
        )
    }
    val existingMessage = if (existingCardId == null) {
        null
    } else {
        runtime.messages.firstOrNull { it.id == existingCardId }
    }
    val existing = if (existingCardId == null) null else toolCardData(runtime, existingCardId)
    val fallbackToolType = if (lifecycleDartString(existing?.get("toolType")).trim().isNotEmpty()) {
        lifecycleDartString(existing!!["toolType"])
    } else if (itemType == "tool_search_output") {
        "search"
    } else {
        "tool"
    }
    val fallbackTitle = lifecycleDartString(
        existing?.get("toolTitle") ?: existing?.get("displayName"),
    ).trim()
    val toolInfo = normalizeAgentToolCall(
        item,
        itemType = itemType,
        fallbackToolType = fallbackToolType,
        fallbackTitle = if (fallbackTitle.isEmpty()) null else fallbackTitle,
        fallbackStatus = "success",
    )
    val rawItemId = rawResponseItemId(params, item, taskId)
    val rawWithIdentity: JsonMap = LinkedHashMap(item).apply {
        if (callId != null) put("toolCallId", callId)
        if (params["sessionId"] != null) put("sessionId", params["sessionId"])
        if (params["session_id"] != null) put("session_id", params["session_id"])
    }
    val suffix = agentToolCardSuffix(toolInfo.toolType, itemType = itemType)
    val cardId = existingCardId ?: taskScopedCardId(
        runtime,
        taskId = taskId,
        baseCardId = toolCardBaseId(
            raw = rawWithIdentity,
            fallback = "$rawItemId-agent-$suffix",
            suffix = suffix,
        ),
    )
    val outputText = extractAgentRawOutputText(item).trimEnd()
    val existingTerminalOutput = lifecycleDartString(existing?.get("terminalOutput"))
    val terminalOutput = if (toolInfo.toolType == "terminal") {
        acpTrimTerminalOutput(
            listOf(existingTerminalOutput.trimEnd(), outputText)
                .filter { it.isNotEmpty() }
                .joinToString("\n"),
        )
    } else {
        existingTerminalOutput
    }
    val summary = if (outputText.isNotEmpty()) {
        compactTitle(outputText, maxLength = 96)
    } else {
        toolInfo.summary
    }
    upsertToolCard(
        runtime,
        cardId = cardId,
        taskId = taskId,
        toolType = toolInfo.toolType,
        title = toolInfo.toolTitle,
        status = toolInfo.status,
        summary = summary,
        progress = summary,
        terminalOutput = terminalOutput,
        raw = rawWithIdentity,
        streamMeta = streamMeta(
            runtime,
            parentTaskId = taskId,
            entryId = cardId,
            kind = "tool_completed",
            isFinal = true,
            existingMessage = existingMessage,
        ),
        touchTurn = false,
    )
    runtime.agentReplayDeltaOffsets.remove(cardId)
}

internal fun AgentEventReducer.rawResponseItemId(
    params: Map<String, Any?>,
    item: Map<String, Any?>,
    taskId: String,
): String {
    return acpFirstString(
        params["itemId"],
        params["item_id"],
        item["id"],
        item["callId"],
        item["call_id"],
        params["callId"],
        params["call_id"],
    ) ?: "$taskId-${stableAgentItemKey(item)}"
}

internal fun AgentEventReducer.completeStandaloneProcess(
    runtime: ChatConversationRuntimeState,
    taskId: String,
    params: Map<String, Any?>,
    method: String,
) {
    val standaloneId = standaloneProcessId(params, method = method)
    val cardId = taskScopedCardId(
        runtime,
        taskId = taskId,
        baseCardId = "$standaloneId-agent-command",
    )
    val existing = toolCardData(runtime, cardId)
    val existingOutput = lifecycleDartString(existing?.get("terminalOutput"))
    val stdout = streamOutputBlock(params["stdout"], stream = "stdout")
    val stderr = streamOutputBlock(params["stderr"], stream = "stderr")
    val output = acpTrimTerminalOutput(existingOutput + stdout + stderr)
    val exitCode = asInt(params["exitCode"] ?: params["exit_code"])
    val status = if (exitCode == null || exitCode == 0) "success" else "error"
    val title = dartToString(existing?.get("toolTitle") ?: existing?.get("displayName"))
        ?: standaloneCommandTitle(params, fallback = standaloneId)
    val summary = if (exitCode == null) "Command completed" else "Command exited with code $exitCode"
    upsertToolCard(
        runtime,
        cardId = cardId,
        taskId = taskId,
        toolType = "terminal",
        title = title,
        status = status,
        summary = summary,
        progress = summary,
        terminalOutput = output,
        raw = LinkedHashMap(params).apply {
            put("type", if (method == "process/exited") "processExecution" else "commandExec")
        },
        streamMeta = streamMeta(
            runtime,
            parentTaskId = taskId,
            entryId = cardId,
            kind = "tool_completed",
            isFinal = true,
        ),
        touchTurn = false,
    )
    runtime.agentReplayDeltaOffsets.remove(cardId)
}

internal fun AgentEventReducer.completeTurn(
    runtime: ChatConversationRuntimeState,
    taskId: String,
    acpTurnId: String? = null,
    // Only the owning prompt response supplies cancellation; an empty
    // assistant message or a session notification cannot imply it.
    appendCancelIfEmpty: Boolean = false,
    cancelled: Boolean = false,
    promptStopReason: String? = null,
) {
    val wasActive = runtime.activeAgentTurnIds.contains(taskId)
    // The UI primes a local render task before ACP has emitted its official
    // turn id. An adapter may answer with only terminal lifecycle data; the
    // terminal event then still belongs to the only locally active turn.
    val pendingLocalTaskId = if (runtime.isAiResponding &&
        runtime.activeAcpTurnId == null &&
        runtime.currentDispatchTurnId != null &&
        runtime.currentDispatchTurnId != taskId
    ) {
        runtime.currentDispatchTurnId
    } else {
        null
    }
    val ownerTaskId = pendingLocalTaskId ?: taskId
    val ownerWasActive = runtime.activeAgentTurnIds.contains(ownerTaskId)
    val protocolTurnMatchesCurrent = acpTurnId == null ||
        runtime.activeAcpTurnId == null ||
        runtime.activeAcpTurnId == acpTurnId
    val isCurrentTurn = protocolTurnMatchesCurrent &&
        (runtime.activeRunId == ownerTaskId ||
            runtime.currentDispatchTurnId == ownerTaskId ||
            runtime.lastAgentTurnId == ownerTaskId ||
            runtime.activeAcpTurnId == acpTurnId ||
            runtime.activeAcpTurnId == ownerTaskId)
    // A terminal notification for turn N can arrive after turn N+1 has
    // already started. Finalize only N's cards/messages in that case; never
    // clear the shared runtime flags or text cache owned by N+1.
    if (!isCurrentTurn && runtime.currentDispatchTurnId != null) {
        markAssistantMessagesFinalForTask(runtime, taskId)
        clearAcpRetryPresentationForTask(runtime, taskId)
        finalizeThinkingCardsForTask(runtime, taskId)
        runtime.currentThinkingMessages.remove(taskId)
        if (wasActive) {
            runtime.completedAgentTurnIds.add(taskId)
        }
        return
    }
    val isManualCancel = appendCancelIfEmpty &&
        ownerTaskId == runtime.currentDispatchTurnId &&
        !hasVisibleAssistantTextForTask(runtime, ownerTaskId) &&
        !hasCompletedAgentOutputForTask(runtime, ownerTaskId)
    if (isManualCancel) {
        appendAssistantText(
            runtime,
            parentTaskId = ownerTaskId,
            entryId = "$ownerTaskId-cancelled",
            delta = "任务已取消",
            isFinal = true,
            replace = true,
        )
        cancelThinkingCardsForTask(runtime, ownerTaskId)
    }
    // Display metadata belongs to the prompt completion owner. Persist it
    // with the last reply.
    val startedAt = runtime.agentEntryStartTimes.remove("prompt:$ownerTaskId")
    // Messages are stored newest first. The first matching reply is the
    // final prose, while the last may be folded into the earlier process.
    val replyIndex = runtime.messages.indexOfFirst { message ->
        message.type == 1 &&
            message.user == 2 &&
            message.streamMeta?.get("parentTaskId") == ownerTaskId
    }
    if (startedAt != null && replyIndex >= 0) {
        val endedAt = System.currentTimeMillis()
        val reply = runtime.messages[replyIndex]
        runtime.messages[replyIndex] = reply.copyWith(
            turnUsage = LinkedHashMap<String, Any?>().apply {
                reply.turnUsage?.let { putAll(it) }
                put("endedAt", endedAt)
                if (endedAt >= startedAt) put("durationMs", endedAt - startedAt)
            },
        )
    }
    // Persist the owning PromptResponse with its existing items. A finished
    // transport is not proof of successful completion, especially after cancel.
    if (promptStopReason != null) {
        var index = 0
        while (index < runtime.messages.size) {
            val message = runtime.messages[index]
            if (message.user != 1 &&
                message.streamMeta?.get("parentTaskId") == ownerTaskId
            ) {
                val card = message.cardData
                // Once the owning prompt ends, its unanswered requests can no
                // longer be acted on. Preserve answers already committed.
                val settleRequest = card?.get("type") == "agent_request" &&
                    !isTerminalRequestStatus(dartToString(card["status"]))
                runtime.messages[index] = message.copyWith(
                    content = if (settleRequest) {
                        LinkedHashMap<String, Any?>().apply {
                            message.content?.let { putAll(it) }
                            put(
                                "cardData",
                                LinkedHashMap<String, Any?>().apply {
                                    card?.let { putAll(it) }
                                    put("status", "cancelled")
                                },
                            )
                        }
                    } else {
                        message.content
                    },
                    isLoading = if (settleRequest) false else message.isLoading,
                    streamMeta = LinkedHashMap<String, Any?>().apply {
                        message.streamMeta?.let { putAll(it) }
                        put("stopReason", promptStopReason)
                    },
                )
            }
            index++
        }
    }
    runtime.isAiResponding = false
    runtime.isExecutingTask = false
    runtime.isCheckingExecutableTask = false
    runtime.currentDispatchTurnId = null
    val completedOfficialTurn = acpTurnId ?: runtime.acpTurnIdForRun(ownerTaskId)
    if (completedOfficialTurn != null && completedOfficialTurn.isNotEmpty()) {
        runtime.rememberCompletedAcpTurn(completedOfficialTurn)
    }
    runtime.activeAcpTurnId = null
    if (runtime.activeRunId == ownerTaskId) {
        runtime.activeRunId = null
    }
    runtime.lastAgentTurnId = null
    runtime.currentAiMessages.clear()
    runtime.currentThinkingMessages.remove(ownerTaskId)
    runtime.pendingAgentTextTaskId = null
    runtime.activeToolCardId = null
    runtime.deepThinkingContent = ""
    runtime.isDeepThinking = false
    runtime.activeThinkingCardId = null
    runtime.currentThinkingStage = if (cancelled) ThinkingStage.CANCELLED else ThinkingStage.COMPLETE
    markAssistantMessagesFinalForTask(runtime, ownerTaskId)
    clearAcpRetryPresentationForTask(runtime, ownerTaskId)
    if (!isManualCancel) {
        finalizeThinkingCardsForTask(runtime, ownerTaskId)
    }
    if (wasActive || ownerWasActive) {
        runtime.completedAgentTurnIds.add(taskId)
        if (completedOfficialTurn != null && completedOfficialTurn.isNotEmpty()) {
            // Keep the protocol id in the legacy fence as well; the canonical
            // fence is completedAcpTurnIds above.
            runtime.completedAgentTurnIds.add(completedOfficialTurn)
        }
        if (ownerTaskId != taskId) {
            runtime.completedAgentTurnIds.add(ownerTaskId)
        }
    }
}

private val acpRetryPresentationKeys = listOf(
    "agentRetrying",
    "agentRetryStatusText",
    "agentRetryCount",
    "agentMaxRetries",
    "agentRetryDelayMs",
    "agentRetryReason",
    "agentRetryable",
)

internal fun AgentEventReducer.clearAcpRetryPresentationForTask(
    runtime: ChatConversationRuntimeState,
    taskId: String,
) {
    var index = 0
    while (index < runtime.messages.size) {
        val message = runtime.messages[index]
        if (message.type != 1 ||
            message.user != 2 ||
            lifecycleDartString(message.streamMeta?.get("parentTaskId")) != taskId
        ) {
            index++
            continue
        }
        val content: JsonMap = LinkedHashMap(message.content ?: emptyMap())
        var changed = false
        for (key in acpRetryPresentationKeys) {
            changed = content.remove(key) != null || changed
        }
        if (changed) {
            runtime.messages[index] = message.copyWith(content = content)
        }
        index++
    }
}

internal fun AgentEventReducer.recordTurnFailure(
    runtime: ChatConversationRuntimeState,
    taskId: String,
    detail: String,
    params: Map<String, Any?>,
) {
    val cardId = "$taskId-agent-status"
    upsertToolCard(
        runtime,
        cardId = cardId,
        taskId = taskId,
        toolType = "status",
        title = "本轮执行失败",
        status = "error",
        summary = detail,
        progress = detail,
        raw = params,
        streamMeta = streamMeta(
            runtime,
            parentTaskId = taskId,
            entryId = cardId,
            kind = "error",
            isFinal = true,
        ),
        touchTurn = false,
    )
}

internal fun AgentEventReducer.hasVisibleAssistantTextForTask(
    runtime: ChatConversationRuntimeState,
    taskId: String,
): Boolean {
    for (message in runtime.messages) {
        if (message.type != 1 || message.user != 2) {
            continue
        }
        if (lifecycleDartString(message.streamMeta?.get("parentTaskId")) != taskId) {
            continue
        }
        if (message.streamMeta?.get("isFinal") == true) {
            return true
        }
        if ((message.text ?: "").trim().isNotEmpty()) {
            return true
        }
    }
    return false
}

internal fun AgentEventReducer.hasCompletedAgentOutputForTask(
    runtime: ChatConversationRuntimeState,
    taskId: String,
): Boolean {
    for (message in runtime.messages) {
        val cardData = message.cardData ?: continue
        val cardTaskId = acpString(cardData["taskID"])
            ?: acpString(cardData["taskId"])
            ?: acpString(message.streamMeta?.get("parentTaskId"))
        if (cardTaskId != taskId) {
            continue
        }
        if (cardData["reasoningItemCompleted"] == true) {
            return true
        }
        val status = acpString(cardData["status"])?.lowercase()
        if (status == "success" ||
            status == "completed" ||
            status == "complete"
        ) {
            return true
        }
    }
    return false
}

internal fun AgentEventReducer.markToolCardComplete(
    runtime: ChatConversationRuntimeState,
    cardId: String,
) {
    val index = runtime.messages.indexOfFirst { it.id == cardId }
    if (index == -1) return
    val existing = runtime.messages[index]
    val cardData: JsonMap = LinkedHashMap(existing.cardData ?: emptyMap())
    val currentStatus = acpString(cardData["status"])?.lowercase()
    if (currentStatus == "error" ||
        currentStatus == "timeout" ||
        currentStatus == "interrupted" ||
        currentStatus == "cancelled" ||
        currentStatus == "canceled"
    ) {
        return
    }
    cardData["status"] = "success"
    val parentTaskId = acpString(cardData["taskId"])
        ?: acpString(existing.streamMeta?.get("parentTaskId"))
    runtime.messages[index] = existing.copyWith(
        content = jsonMapOf("cardData" to cardData, "id" to cardId),
        streamMeta = if (parentTaskId == null) {
            existing.streamMeta
        } else {
            streamMeta(
                runtime,
                parentTaskId = parentTaskId,
                entryId = cardId,
                kind = "tool_completed",
                isFinal = true,
                existingMessage = existing,
            )
        },
    )
}

internal fun AgentEventReducer.assistantTextForEntry(
    runtime: ChatConversationRuntimeState,
    messageId: String,
): String {
    val runtimeText = runtime.currentAiMessages[messageId]
    if (runtimeText != null) {
        return runtimeText
    }
    val index = runtime.messages.indexOfFirst { it.id == messageId }
    return if (index == -1) "" else runtime.messages[index].text ?: ""
}

internal fun AgentEventReducer.markAssistantEntryFinal(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    messageId: String,
) {
    val index = runtime.messages.indexOfFirst { it.id == messageId }
    if (index == -1) return
    val existing = runtime.messages[index]
    runtime.messages[index] = existing.copyWith(
        isLoading = false,
        isError = false,
        streamMeta = streamMeta(
            runtime,
            parentTaskId = parentTaskId,
            entryId = messageId,
            kind = "text_snapshot",
            isFinal = true,
            existingMessage = existing,
        ),
    )
}

internal fun AgentEventReducer.markAssistantMessagesFinalForTask(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
) {
    var index = 0
    while (index < runtime.messages.size) {
        val message = runtime.messages[index]
        if (message.type != 1 || message.user != 2) {
            index += 1
            continue
        }
        if (acpString(message.streamMeta?.get("parentTaskId")) != parentTaskId) {
            index += 1
            continue
        }
        runtime.messages[index] = message.copyWith(
            isLoading = false,
            isError = false,
            streamMeta = streamMeta(
                runtime,
                parentTaskId = parentTaskId,
                entryId = message.id,
                kind = "text_snapshot",
                isFinal = true,
                existingMessage = message,
            ),
        )
        index += 1
    }
}

internal fun AgentEventReducer.finalizeThinkingCard(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    cardId: String,
) {
    val index = runtime.messages.indexOfFirst { it.id == cardId }
    if (index == -1) return
    val existing = runtime.messages[index]
    val existingCardData = existing.cardData
    if (existingCardData?.get("type") != "deep_thinking") return
    val cardData: JsonMap = LinkedHashMap(existingCardData)
    val startTime = asLong(cardData["startTime"])
        ?: startTimeForEntry(runtime, cardId, existingMessage = existing)
    cardData["isLoading"] = false
    cardData["stage"] = ThinkingStage.COMPLETE
    cardData["taskID"] = parentTaskId
    cardData["runId"] = parentTaskId
    cardData["cardId"] = cardId
    cardData["startTime"] = startTime
    if (cardData["endTime"] == null) cardData["endTime"] = System.currentTimeMillis()
    cardData["isCollapsible"] = true
    cardData["thinkingContent"] = lifecycleDartString(cardData["thinkingContent"])
    runtime.messages[index] = existing.copyWith(
        content = jsonMapOf("cardData" to cardData, "id" to cardId),
        streamMeta = streamMeta(
            runtime,
            parentTaskId = parentTaskId,
            entryId = cardId,
            kind = "thinking_snapshot",
            isFinal = true,
            existingMessage = existing,
        ),
    )
}

internal fun AgentEventReducer.markThinkingItemCompleted(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    cardId: String,
) {
    val resolvedCardId = thinkingCardIdForTask(
        runtime,
        parentTaskId = parentTaskId,
        requestedCardId = cardId,
    )
    val index = runtime.messages.indexOfFirst { it.id == resolvedCardId }
    if (index == -1) return
    val existing = runtime.messages[index]
    val existingCardData = existing.cardData
    if (existingCardData?.get("type") != "deep_thinking") return
    val cardData: JsonMap = LinkedHashMap(existingCardData)
    cardData["reasoningItemCompleted"] = true
    runtime.messages[index] = existing.copyWith(
        content = jsonMapOf("cardData" to cardData, "id" to resolvedCardId),
        streamMeta = streamMeta(
            runtime,
            parentTaskId = parentTaskId,
            entryId = resolvedCardId,
            kind = "thinking_snapshot",
            existingMessage = existing,
        ),
    )
}

internal fun AgentEventReducer.cancelThinkingCard(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    cardId: String,
) {
    val index = runtime.messages.indexOfFirst { it.id == cardId }
    if (index == -1) return
    val existing = runtime.messages[index]
    val existingCardData = existing.cardData
    if (existingCardData?.get("type") != "deep_thinking") return
    val cardData: JsonMap = LinkedHashMap(existingCardData)
    val startTime = asLong(cardData["startTime"])
        ?: startTimeForEntry(runtime, cardId, existingMessage = existing)
    cardData["isLoading"] = false
    cardData["stage"] = ThinkingStage.CANCELLED
    cardData["taskID"] = parentTaskId
    cardData["runId"] = parentTaskId
    cardData["cardId"] = cardId
    cardData["startTime"] = startTime
    if (cardData["endTime"] == null) cardData["endTime"] = System.currentTimeMillis()
    cardData["isCollapsible"] = false
    cardData["thinkingContent"] = lifecycleDartString(cardData["thinkingContent"])
    runtime.messages[index] = existing.copyWith(
        content = jsonMapOf("cardData" to cardData, "id" to cardId),
        streamMeta = streamMeta(
            runtime,
            parentTaskId = parentTaskId,
            entryId = cardId,
            kind = "thinking_snapshot",
            isFinal = true,
            existingMessage = existing,
        ),
    )
}

private fun thinkingCardTaskMatches(message: ChatMessage, parentTaskId: String): Boolean {
    val cardData = message.cardData
    if (cardData?.get("type") != "deep_thinking") {
        return false
    }
    val cardTaskId = acpString(cardData["taskID"])
        ?: acpString(message.streamMeta?.get("parentTaskId"))
    return cardTaskId == parentTaskId
}

internal fun AgentEventReducer.finalizeThinkingCardsForTask(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
) {
    val cardIds = runtime.messages
        .filter { thinkingCardTaskMatches(it, parentTaskId) }
        .map { it.id }
    for (cardId in cardIds) {
        finalizeThinkingCard(runtime, parentTaskId, cardId)
    }
}

internal fun AgentEventReducer.finalizeActiveThinkingCardForTask(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
) {
    val cardId = runtime.activeThinkingCardId ?: return
    val index = runtime.messages.indexOfFirst { it.id == cardId }
    if (index == -1) {
        runtime.activeThinkingCardId = null
        return
    }
    val message = runtime.messages[index]
    val cardTaskId = acpString(message.cardData?.get("taskID"))
        ?: acpString(message.streamMeta?.get("parentTaskId"))
    if (cardTaskId != parentTaskId) {
        return
    }
    finalizeThinkingCard(runtime, parentTaskId, cardId)
    runtime.activeThinkingCardId = null
    runtime.currentThinkingMessages.remove(parentTaskId)
    runtime.deepThinkingContent = ""
    runtime.isDeepThinking = false
}

internal fun AgentEventReducer.cancelThinkingCardsForTask(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
) {
    val cardIds = runtime.messages
        .filter { thinkingCardTaskMatches(it, parentTaskId) }
        .map { it.id }
    for (cardId in cardIds) {
        cancelThinkingCard(runtime, parentTaskId, cardId)
    }
}

internal fun AgentEventReducer.finalizeOtherLoadingThinkingCardsForTask(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    activeCardId: String,
) {
    val cardIds = runtime.messages
        .filter { message ->
            if (message.id == activeCardId) {
                return@filter false
            }
            val cardData = message.cardData
            if (cardData?.get("type") != "deep_thinking" ||
                cardData["isLoading"] != true
            ) {
                return@filter false
            }
            val cardTaskId = acpString(cardData["taskID"])
                ?: acpString(message.streamMeta?.get("parentTaskId"))
            cardTaskId == parentTaskId
        }
        .map { it.id }
    for (cardId in cardIds) {
        finalizeThinkingCard(runtime, parentTaskId, cardId)
    }
}

internal fun AgentEventReducer.activeThinkingSegmentIndex(runtime: ChatConversationRuntimeState): String? {
    val activeCardId = runtime.activeThinkingCardId ?: return null
    val index = runtime.messages.indexOfFirst { it.id == activeCardId }
    if (index == -1) return null
    return acpString(runtime.messages[index].cardData?.get("reasoningSegmentIndex"))
}

internal fun AgentEventReducer.thinkingCardIdForTask(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    requestedCardId: String,
): String {
    val activeCardId = runtime.activeThinkingCardId
    if (activeCardId != null) {
        val activeIndex = runtime.messages.indexOfFirst { it.id == activeCardId }
        if (activeIndex != -1 &&
            thinkingCardBelongsToTask(runtime.messages[activeIndex], parentTaskId)
        ) {
            return activeCardId
        }
    }

    val requestedIdExists = runtime.messages.any { it.id == requestedCardId }
    if (!requestedIdExists) {
        return requestedCardId
    }

    // Some ACP adapters omit reasoning messageId. The turn-scoped fallback
    // then repeats after every tool boundary, so allocate a deterministic
    // segment suffix instead of reopening the completed card.
    var segmentIndex = 2
    while (runtime.messages.any { it.id == "$requestedCardId-segment-$segmentIndex" }) {
        segmentIndex += 1
    }
    return "$requestedCardId-segment-$segmentIndex"
}

internal fun AgentEventReducer.thinkingCardBelongsToTask(
    message: ChatMessage,
    parentTaskId: String,
): Boolean {
    if (message.cardData?.get("type") != "deep_thinking") {
        return false
    }
    val cardTaskId = acpString(message.cardData?.get("taskID"))
        ?: acpString(message.streamMeta?.get("parentTaskId"))
    return cardTaskId == parentTaskId
}

/** Returns a copy of the tool summary card data (ChatMessage is immutable). */
internal fun AgentEventReducer.toolCardData(
    runtime: ChatConversationRuntimeState,
    cardId: String,
): JsonMap? {
    val index = runtime.messages.indexOfFirst { it.id == cardId }
    if (index == -1) {
        return null
    }
    val cardData = runtime.messages[index].cardData
    if (cardData?.get("type") != "agent_tool_summary") {
        return null
    }
    return LinkedHashMap(cardData)
}

internal fun AgentEventReducer.mergeAgentToolUpdate(
    existingCardData: Map<String, Any?>?,
    incoming: Map<String, Any?>,
): JsonMap {
    val existingRaw = decodeJsonValue(lifecycleDartString(existingCardData?.get("rawResultJson")))
    val existingMap = copyStringMap(existingRaw)
    if (existingMap == null || existingMap.isEmpty()) {
        return LinkedHashMap(incoming)
    }
    val merged: JsonMap = LinkedHashMap(existingMap)
    val existingStatus = acpString(existingCardData?.get("status"))
        ?: normalizeAgentToolStatus(existingMap, fallbackStatus = "running")
    val incomingStatus = normalizeAgentToolStatus(incoming, fallbackStatus = "running")
    val keepsTerminalState = isTerminalAgentToolStatus(existingStatus) &&
        !isTerminalAgentToolStatus(incomingStatus)
    for ((key, value) in incoming) {
        // ACP tool_call_update is a sparse patch; absent fields arrive as
        // explicit nulls, so a shallow spread would erase earlier facts. A
        // tool lifecycle is monotonic in the UI: terminal cards keep their
        // terminal state while still accepting newly supplied facts.
        val preservesSpecificType = key == "type" &&
            value == "tool" &&
            existingMap["type"] != null &&
            existingMap["type"] != "tool"
        if (value != null &&
            !preservesSpecificType &&
            (!keepsTerminalState || (key != "status" && key != "state"))
        ) {
            merged[key] = value
        }
    }
    return merged
}

private val terminalAgentToolStatuses = setOf("success", "error", "timeout", "interrupted")
private val activeAgentToolStatuses = setOf("running", "pending", "progress")

internal fun AgentEventReducer.isTerminalAgentToolStatus(status: String): Boolean =
    terminalAgentToolStatuses.contains(status.trim().lowercase())

internal fun AgentEventReducer.isActiveAgentToolStatus(status: String): Boolean =
    activeAgentToolStatuses.contains(status.trim().lowercase())

internal fun AgentEventReducer.findToolCardIdForCallId(
    runtime: ChatConversationRuntimeState,
    callId: String,
    taskId: String,
    sessionId: String? = null,
): String? {
    val normalizedCallId = callId.trim()
    if (normalizedCallId.isEmpty()) {
        return null
    }
    val normalizedTaskId = taskId.trim()
    val normalizedSessionId = sessionId?.trim() ?: ""
    // ACP defines toolCallId as unique within a session. Prefer the explicit
    // identity fields before inspecting legacy JSON payloads. The task check
    // also protects the UI when a provider reuses an id in a later turn.
    for (message in runtime.messages) {
        val cardData = message.cardData
        if (cardData == null ||
            (cardData["type"] != "agent_tool_summary" &&
                cardData["type"] != AGENT_REQUEST_CARD_TYPE) ||
            !cardBelongsToTask(cardData, normalizedTaskId)
        ) {
            continue
        }
        val cardToolCallId = acpString(cardData["toolCallId"])?.trim()
        val cardSessionId = acpString(cardData["sessionId"])?.trim() ?: ""
        if (cardToolCallId == normalizedCallId &&
            (normalizedSessionId.isEmpty() ||
                cardSessionId.isEmpty() ||
                cardSessionId == normalizedSessionId)
        ) {
            return message.id
        }
        val terminalSessionId = acpString(cardData["terminalSessionId"])
        if (terminalSessionId == normalizedCallId &&
            (normalizedSessionId.isEmpty() ||
                cardSessionId.isEmpty() ||
                cardSessionId == normalizedSessionId)
        ) {
            return message.id
        }
    }
    for (suffix in toolCardSuffixes) {
        val baseCardId = "$normalizedCallId-agent-$suffix"
        val candidateIds = ArrayList<String>().apply {
            add(baseCardId)
            if (normalizedTaskId.isNotEmpty()) add("$normalizedTaskId-$baseCardId")
        }
        for (cardId in candidateIds) {
            val cardData = toolCardData(runtime, cardId)
            if (cardData != null && cardBelongsToTask(cardData, normalizedTaskId)) {
                return cardId
            }
        }
    }
    for (message in runtime.messages) {
        val cardData = message.cardData
        if (cardData?.get("type") != "agent_tool_summary") {
            continue
        }
        if (cardBelongsToTask(cardData, normalizedTaskId) &&
            toolCardContainsCallId(cardData, normalizedCallId)
        ) {
            return message.id
        }
    }
    return null
}

/**
 * Legacy events may not carry a sessionId. Keep their compact ids when
 * possible, but allocate a task-scoped id on collision. Official ACP cards
 * use the session-scoped identity generated by [toolCardBaseId].
 */
internal fun AgentEventReducer.taskScopedCardId(
    runtime: ChatConversationRuntimeState,
    taskId: String,
    baseCardId: String,
): String {
    val existing = toolCardData(runtime, baseCardId)
    if (existing == null || cardBelongsToTask(existing, taskId)) {
        return baseCardId
    }
    return "${taskId.trim()}-$baseCardId"
}

internal fun AgentEventReducer.toolCardBaseId(
    raw: Map<String, Any?>,
    fallback: String,
    suffix: String,
): String {
    val identity = AgentToolIdentity.fromMaps(raw = raw)
    return identity.cardId(suffix = suffix, fallback = fallback)
}

internal fun AgentEventReducer.cardBelongsToTask(cardData: Map<String, Any?>, taskId: String): Boolean {
    val normalizedTaskId = taskId.trim()
    if (normalizedTaskId.isEmpty()) {
        return false
    }
    val cardTaskId = acpFirstString(cardData["taskId"], cardData["taskID"])
    return cardTaskId?.trim() == normalizedTaskId
}

internal fun AgentEventReducer.toolCardContainsCallId(cardData: Map<String, Any?>, callId: String): Boolean {
    for (key in listOf("rawResultJson", "resultPreviewJson", "argsJson")) {
        val text = lifecycleDartString(cardData[key]).trim()
        if (text.isEmpty()) {
            continue
        }
        val decoded = decodeJsonValue(text)
        if (valueContainsCallId(decoded, callId)) {
            return true
        }
    }
    return false
}

internal fun AgentEventReducer.valueContainsCallId(value: Any?, callId: String): Boolean {
    if (value == null) {
        return false
    }
    if (value is String || value is Number || value is Boolean) {
        return value.toString() == callId
    }
    val map = copyStringMap(value)
    if (map != null) {
        if (acpFirstString(map["callId"], map["call_id"], map["id"]) == callId) {
            return true
        }
        return map.values.any { nested -> valueContainsCallId(nested, callId) }
    }
    if (value is List<*>) {
        return value.any { nested -> valueContainsCallId(nested, callId) }
    }
    return false
}

internal fun AgentEventReducer.decodeJsonValue(text: String): Any? {
    return try {
        DartJson.decode(text)
    } catch (_: Exception) {
        null
    }
}

internal fun AgentEventReducer.stableAgentItemKey(item: Map<String, Any?>): String {
    val stablePayload: JsonMap = jsonMapOf(
        "type" to item["type"],
        "name" to item["name"],
        "namespace" to item["namespace"],
        "arguments" to item["arguments"],
        "action" to item["action"],
        "execution" to item["execution"],
        "query" to item["query"],
        "output" to item["output"],
        "status" to item["status"],
    )
    return "raw-${stableTextHash(acpSafeJson(stablePayload))}"
}

internal fun AgentEventReducer.stableTextHash(value: String): String {
    var hash = 0x811c9dc5L
    for (codeUnit in value) {
        hash = hash xor codeUnit.code.toLong()
        hash = (hash * 0x01000193L) and 0xffffffffL
    }
    return hash.toString(16).padStart(8, '0')
}

internal fun AgentEventReducer.streamMeta(
    runtime: ChatConversationRuntimeState,
    parentTaskId: String,
    entryId: String,
    kind: String,
    isFinal: Boolean = false,
    existingMessage: ChatMessage? = null,
): JsonMap {
    val seq = sequenceForEntry(runtime, entryId, existingMessage = existingMessage)
    return ensureAgentStreamMessageMeta(
        existingMessage?.streamMeta,
        seq = seq,
        roundIndex = seq,
        kind = kind,
        runId = parentTaskId,
        sessionId = runtime.activeAcpSessionId,
        turnId = runtime.acpTurnIdForRun(parentTaskId),
        cardId = entryId,
        parentTaskId = parentTaskId,
        entryId = entryId,
        isFinal = isFinal,
    ) ?: linkedMapOf()
}

internal fun AgentEventReducer.sequenceForEntry(
    runtime: ChatConversationRuntimeState,
    entryId: String,
    existingMessage: ChatMessage? = null,
): Int {
    val key = entryId.trim()
    val cached = runtime.agentEntrySequences[key]
    if (cached != null) {
        return cached
    }
    val existingSeq = asInt(existingMessage?.streamMeta?.get("seq"))
    if (existingSeq != null && existingSeq > 0) {
        runtime.agentEntrySequences[key] = existingSeq
        if (runtime.agentNextEntrySequence < existingSeq) {
            runtime.agentNextEntrySequence = existingSeq
        }
        return existingSeq
    }
    runtime.agentNextEntrySequence += 1
    runtime.agentEntrySequences[key] = runtime.agentNextEntrySequence
    return runtime.agentNextEntrySequence
}

internal fun AgentEventReducer.startTimeForEntry(
    runtime: ChatConversationRuntimeState,
    entryId: String,
    existingMessage: ChatMessage? = null,
): Long {
    val key = entryId.trim()
    val cached = runtime.agentEntryStartTimes[key]
    if (cached != null) {
        return cached
    }
    val existingStart = asLong(existingMessage?.cardData?.get("startTime"))
        ?: existingMessage?.createAtMillis
    val startTime = existingStart ?: System.currentTimeMillis()
    runtime.agentEntryStartTimes[key] = startTime
    return startTime
}

internal fun AgentEventReducer.removeAgentDebugStatusCards(runtime: ChatConversationRuntimeState): Boolean {
    val before = runtime.messages.size
    runtime.messages.removeWhere { message ->
        val cardData = message.cardData ?: return@removeWhere false
        val toolName = acpString(cardData["toolName"])
        val title = acpString(cardData["toolTitle"]) ?: acpString(cardData["displayName"])
        canonicalAgentToolName(toolName) == "agent.status" &&
            (title == "codex/stderr" || title == "codex/parseError")
    }
    return runtime.messages.size != before
}

internal fun AgentEventReducer.standaloneProcessIdentity(params: Map<String, Any?>): String? {
    return acpFirstString(
        params["processId"],
        params["process_id"],
        params["processHandle"],
        params["process_handle"],
    )
}

private val nonAlphanumericRun = Regex("[^a-zA-Z0-9]+")

internal fun AgentEventReducer.standaloneProcessId(
    params: Map<String, Any?>,
    method: String,
): String {
    return acpFirstString(
        params["processId"],
        params["process_id"],
        params["processHandle"],
        params["process_handle"],
        params["id"],
    ) ?: method.replace(nonAlphanumericRun, "-")
}

internal fun AgentEventReducer.standaloneCommandTitle(
    params: Map<String, Any?>,
    fallback: String,
): String {
    val command = acpCommandTextFromValue(params["command"])
        ?: acpCommandTextFromValue(toolArguments(params)["command"])
        ?: acpCommandTextFromValue(copyStringMap(params["action"])?.get("command"))
        ?: acpFirstString(params["processId"], params["processHandle"])
    if (command == null || command.trim().isEmpty()) {
        return compactTitle(fallback, maxLength = 48)
    }
    return compactTitle(command, maxLength = 48)
}

internal fun AgentEventReducer.standaloneProcessOutputDelta(params: Map<String, Any?>): String {
    val decoded = acpDecodeBase64Output(params["deltaBase64"])
        ?: acpDecodeBase64Output(params["delta_base64"])
        ?: acpExtractText(params["delta"])
        ?: acpExtractText(params["output"])
        ?: acpExtractText(params["text"])
        ?: ""
    val stream = acpString(params["stream"])?.lowercase()
    if (decoded.isEmpty() || stream == null || stream == "stdout") {
        return decoded
    }
    return streamOutputBlock(decoded, stream = stream)
}

internal fun AgentEventReducer.streamOutputBlock(value: Any?, stream: String): String {
    val text = acpExtractText(value) ?: ""
    if (text.isEmpty()) {
        return ""
    }
    val normalizedStream = stream.lowercase()
    if (normalizedStream == "stdout") {
        return text
    }
    val needsLeadingNewline = if (text.startsWith("\n")) "" else "\n"
    val needsTrailingNewline = if (text.endsWith("\n")) "" else "\n"
    return "$needsLeadingNewline[$normalizedStream]\n$text$needsTrailingNewline"
}

internal fun AgentEventReducer.extractAgentRawOutputText(item: Map<String, Any?>): String {
    val output = item["output"]
    val text = acpExtractText(output)
        ?: acpExtractText(item["tools"])
        ?: acpExtractText(item["result"])
        ?: acpExtractText(item["content"])
        ?: ""
    if (text.trim().isNotEmpty()) {
        return text
    }
    if (output != null) {
        return acpSafeJson(output)
    }
    return ""
}

internal fun AgentEventReducer.approvalTitle(method: String, params: Map<String, Any?>): String {
    if (method.contains("commandExecution")) {
        return commandTitle(params)
    }
    if (method.contains("fileChange")) {
        return fileChangeTitle(params, fallback = "Agent file approval")
    }
    val toolCall = approvalToolCall(params)
    val providedTitle = acpFirstString(
        toolCall?.get("title"),
        toolCall?.get("name"),
        params["title"],
    )
    if (providedTitle != null && !isGenericApprovalLabel(providedTitle)) {
        return compactTitle(providedTitle, maxLength = 64)
    }
    val command = approvalCommand(params)
    if (command != null) {
        return compactTitle(command, maxLength = 48)
    }
    if (approvalPath(params) != null) {
        return "Modify file"
    }
    return when (approvalKind(params)) {
        "execute", "command", "command_execution", "commandexecution" -> "Run command"
        "edit", "file_change", "filechange", "delete" -> "Modify files"
        "read" -> "Read project files"
        "search" -> "Search project"
        "mcp", "mcp_tool" -> "Use integration"
        else -> "Continue agent action"
    }
}

internal fun AgentEventReducer.approvalDetail(params: Map<String, Any?>): String {
    val toolCall = approvalToolCall(params)
    val input = approvalInput(params)
    val parts = ArrayList<String>()
    val reason = acpFirstString(
        params["reason"],
        params["description"],
        params["message"],
        params["prompt"],
        toolCall?.get("reason"),
        toolCall?.get("description"),
        toolCall?.get("detail"),
        acpExtractText(toolCall?.get("content")),
        input["detail"],
    )
    if (reason != null && !isGenericApprovalLabel(reason)) {
        parts.add(reason)
    }

    val command = approvalCommand(params)
    if (command != null && !containsApprovalDetail(parts, command)) {
        parts.add("Command: $command")
    }
    val path = approvalPath(params)
    if (path != null && !containsApprovalDetail(parts, path)) {
        parts.add("File: $path")
    }
    val toolName = acpFirstString(
        toolCall?.get("toolName"),
        toolCall?.get("tool_name"),
        toolCall?.get("name"),
        params["toolName"],
        params["tool_name"],
        input["toolName"],
        input["tool_name"],
        input["name"],
    )
    if (toolName != null && !isGenericApprovalLabel(toolName)) {
        parts.add("Tool: $toolName")
    }
    if (parts.isEmpty()) {
        return "The agent is requesting permission to continue."
    }
    return parts.joinToString("\n")
}

internal fun AgentEventReducer.approvalToolCall(params: Map<String, Any?>): JsonMap? {
    val request = copyStringMap(params["request"])
    return copyStringMap(params["toolCall"])
        ?: copyStringMap(params["tool_call"])
        ?: copyStringMap(request?.get("toolCall"))
        ?: copyStringMap(request?.get("tool_call"))
}

internal fun AgentEventReducer.approvalInput(params: Map<String, Any?>): JsonMap {
    val toolCall = approvalToolCall(params)
    for (value in listOf(
        toolCall?.get("rawInput"),
        toolCall?.get("raw_input"),
        toolCall?.get("input"),
        params["rawInput"],
        params["raw_input"],
        params["input"],
    )) {
        val map = copyStringMap(value)
        if (map != null) return map
        val text = acpString(value) ?: continue
        try {
            val decoded = DartJson.decode(text)
            val decodedMap = copyStringMap(decoded)
            if (decodedMap != null) return decodedMap
        } catch (_: Exception) {
            // Some Harnesses send a plain command string; it is handled by
            // [approvalCommand] instead of being rendered as protocol JSON.
        }
    }
    return linkedMapOf()
}

internal fun AgentEventReducer.approvalCommand(params: Map<String, Any?>): String? {
    val toolCall = approvalToolCall(params)
    val input = approvalInput(params)
    return acpFirstString(
        approvalCommandValue(params["command"]),
        approvalCommandValue(params["cmd"]),
        approvalCommandValue(toolCall?.get("command")),
        approvalCommandValue(toolCall?.get("cmd")),
        approvalCommandValue(toolCall?.get("rawInput")),
        approvalCommandValue(toolCall?.get("raw_input")),
        approvalCommandValue(params["rawInput"]),
        approvalCommandValue(params["raw_input"]),
        approvalCommandValue(input["command"]),
        approvalCommandValue(input["cmd"]),
        approvalCommandValue(toolArguments(params)["command"]),
        approvalCommandValue(toolArguments(params)["cmd"]),
    )
}

internal fun AgentEventReducer.approvalCommandValue(value: Any?): String? {
    val map = copyStringMap(value)
    if (map != null) {
        return acpFirstString(
            map["command"],
            map["cmd"],
            map["commandLine"],
            map["command_line"],
        )
    }
    return acpCommandTextFromValue(value)
}

internal fun AgentEventReducer.approvalPath(params: Map<String, Any?>): String? {
    val toolCall = approvalToolCall(params)
    val input = approvalInput(params)
    return acpFirstString(
        params["path"],
        params["filePath"],
        params["file_path"],
        params["filename"],
        params["fileName"],
        toolCall?.get("path"),
        toolCall?.get("filePath"),
        toolCall?.get("file_path"),
        toolCall?.get("filename"),
        toolCall?.get("fileName"),
        input["path"],
        input["filePath"],
        input["file_path"],
        input["filename"],
        input["fileName"],
    )
}

internal fun AgentEventReducer.approvalKind(params: Map<String, Any?>): String? {
    val toolCall = approvalToolCall(params)
    return acpFirstString(
        toolCall?.get("kind"),
        params["kind"],
        params["type"],
    )?.lowercase()
}

internal fun AgentEventReducer.containsApprovalDetail(parts: List<String>, value: String): Boolean {
    val normalized = value.trim().lowercase()
    return normalized.isNotEmpty() &&
        parts.any { part -> part.lowercase().contains(normalized) }
}

internal fun AgentEventReducer.isGenericApprovalLabel(value: String): Boolean {
    val normalized = value.trim().lowercase().replace("_", " ")
    return normalized.isEmpty() ||
        normalized == "agent approval" ||
        normalized == "permission required" ||
        normalized == "request permission" ||
        normalized == "approval requested" ||
        normalized == "tool call" ||
        normalized == "to call"
}

internal fun AgentEventReducer.commandTitle(params: Map<String, Any?>): String {
    val command = acpCommandTextFromValue(params["command"])
        ?: acpCommandTextFromValue(toolArguments(params)["command"])
        ?: acpCommandTextFromValue(copyStringMap(params["item"])?.get("command"))
        ?: acpCommandTextFromValue(copyStringMap(params["action"])?.get("command"))
        ?: acpCommandTextFromValue(
            copyStringMap(copyStringMap(params["item"])?.get("action"))?.get("command"),
        )
        ?: acpExtractText(params["cmd"])
    if (command == null || command.trim().isEmpty()) {
        return "Agent command"
    }
    return compactTitle(command, maxLength = 48)
}

internal fun AgentEventReducer.fileChangeTitle(
    params: Map<String, Any?>,
    fallback: String = "Agent file change",
): String {
    val path = resolveFilePath(params) ?: return fallback
    val name = lastPathSegment(path) ?: path
    return compactTitle("Edit $name", maxLength = 42)
}

internal fun AgentEventReducer.resolveFilePath(params: Map<String, Any?>): String? {
    val args = toolArguments(params)
    return acpFirstString(
        params["path"],
        params["filePath"],
        params["file_path"],
        params["filename"],
        params["fileName"],
        args["path"],
        args["filePath"],
        args["file_path"],
        args["filename"],
        args["fileName"],
        firstPathFromList(params["files"]),
        firstPathFromList(params["changes"]),
        firstPathFromList(args["files"]),
        firstPathFromList(args["changes"]),
        copyStringMap(params["item"])?.get("path"),
        copyStringMap(params["item"])?.get("filePath"),
        copyStringMap(params["item"])?.get("file_path"),
    ) ?: extractAgentDiffPath(params)
}

internal fun AgentEventReducer.resolveFileDiffText(
    existingCardData: Map<String, Any?>,
    raw: Map<String, Any?>,
    terminalOutput: String,
    progress: String,
    summary: String,
): String {
    val fromExisting = lifecycleDartString(existingCardData["diffText"])
    val fromCurrent = extractAgentDiffText(
        raw,
        outputText = terminalOutput,
        progress = progress,
        summary = summary,
    )
    if (fromCurrent != null && fromCurrent.trim().isNotEmpty()) {
        return fromCurrent
    }
    return if (fromExisting.trim().isEmpty()) "" else fromExisting
}

internal fun AgentEventReducer.toolArguments(params: Map<String, Any?>): JsonMap {
    for (key in listOf("arguments", "args", "input")) {
        val map = copyStringMap(params[key])
        if (map != null) {
            return map
        }
        val text = acpString(params[key])
        if (text == null || text.isEmpty()) {
            continue
        }
        try {
            val decoded = DartJson.decode(text)
            if (decoded is Map<*, *>) {
                return copyStringMap(decoded)!!
            }
        } catch (_: Exception) {
            continue
        }
    }
    val item = copyStringMap(params["item"])
    // Dart compares by identity; the copied item is never `params`.
    if (item != null && item !== params) {
        return toolArguments(item)
    }
    return linkedMapOf()
}

internal fun AgentEventReducer.firstPathFromList(value: Any?): String? {
    if (value !is List<*>) {
        return null
    }
    for (item in value) {
        if (item is String && item.trim().isNotEmpty()) {
            return item.trim()
        }
        val map = copyStringMap(item)
        val path = acpFirstString(
            map?.get("path"),
            map?.get("filePath"),
            map?.get("file_path"),
            map?.get("filename"),
            map?.get("fileName"),
        )
        if (path != null) {
            return path
        }
    }
    return null
}

private val trailingPathSeparators = Regex("[/\\\\]+\\z")
private val pathSeparators = Regex("[/\\\\]+")

internal fun AgentEventReducer.lastPathSegment(path: String): String? {
    val normalized = path.trim().replace(trailingPathSeparators, "")
    if (normalized.isEmpty()) {
        return null
    }
    val parts = normalized
        .split(pathSeparators)
        .filter { it.isNotEmpty() }
    return if (parts.isEmpty()) normalized else parts.last()
}

/** Dart (ECMAScript) `\s` is Unicode whitespace; Java `\s` is ASCII only. */
private val whitespaceRun = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

internal fun AgentEventReducer.compactTitle(value: String, maxLength: Int): String {
    val normalized = value
        .trim()
        .split("\n")
        .first()
        .trim()
        .replace(whitespaceRun, " ")
    if (normalized.length <= maxLength) {
        return normalized
    }
    return "${normalized.substring(0, maxLength)}..."
}

internal fun AgentEventReducer.isGenericAgentInputTitle(value: String?): Boolean {
    val normalized = value?.trim()?.lowercase() ?: ""
    return normalized.isEmpty() ||
        (normalized.contains("agent") &&
            (normalized.contains("input") || normalized.contains("question"))) ||
        (normalized.contains("需要") && normalized.contains("输入"))
}

internal fun AgentEventReducer.firstQuestion(params: Map<String, Any?>): AgentQuestion {
    val questions = params["questions"]
    val schemaQuestion = elicitationSchemaQuestion(params)
    if (questions is List<*> && questions.isNotEmpty()) {
        val first = copyStringMap(questions.first())
        if (first != null) {
            val id = acpString(first["id"]) ?: acpString(first["questionId"]) ?: "answer"
            val requestedTitle = acpString(first["label"])
                ?: acpString(first["title"])
                ?: acpString(first["question"])
                ?: "Agent needs input"
            val requestedDetail = acpString(first["description"])
                ?: acpString(first["placeholder"])
                ?: requestedTitle
            val title = if (isGenericAgentInputTitle(requestedTitle) && schemaQuestion != null) {
                schemaQuestion.title
            } else {
                requestedTitle
            }
            val detail = if (isGenericAgentInputTitle(requestedTitle) && schemaQuestion != null) {
                schemaQuestion.detail
            } else {
                requestedDetail
            }
            return AgentQuestion(id = id, title = title, detail = detail)
        }
    }
    val id = acpString(params["questionId"]) ?: acpString(params["id"]) ?: "answer"
    val requestedTitle = acpString(params["question"])
        ?: acpString(params["title"])
        ?: "Agent needs input"
    val title = if (isGenericAgentInputTitle(requestedTitle) && schemaQuestion != null) {
        schemaQuestion.title
    } else {
        requestedTitle
    }
    val detail = if (isGenericAgentInputTitle(requestedTitle) && schemaQuestion != null) {
        schemaQuestion.detail
    } else {
        acpString(params["description"]) ?: title
    }
    return AgentQuestion(id = id, title = title, detail = detail)
}

internal fun AgentEventReducer.elicitationSchemaQuestion(params: Map<String, Any?>): AgentQuestion? {
    val schema = schemaMap(params)
    val properties = copyStringMap(schema?.get("properties"))
    if (properties == null || properties.isEmpty()) {
        return null
    }
    val firstEntry = properties.entries.first()
    val field = copyStringMap(firstEntry.value) ?: return null
    val title = acpFirstString(
        field["title"],
        field["label"],
        firstEntry.key,
    )
    val detail = acpFirstString(field["description"], field["placeholder"])
    val rawChoices = field["oneOf"] ?: field["enum"]
    val choices: List<String> = if (rawChoices is List<*>) {
        rawChoices.mapNotNull { value ->
            acpFirstString(
                copyStringMap(value)?.get("title"),
                copyStringMap(value)?.get("label"),
                copyStringMap(value)?.get("const"),
                value,
            )
        }
    } else {
        emptyList()
    }
    if (title == null && detail == null) {
        return null
    }
    val detailParts = ArrayList<String>().apply {
        if (detail != null) add(detail)
        if (choices.isNotEmpty()) add("可选：${choices.joinToString("、")}")
    }
    val joinedDetail = detailParts.joinToString("\n")
    return AgentQuestion(
        id = firstEntry.key,
        title = title ?: "Agent needs input",
        detail = if (joinedDetail.trim().isEmpty()) title ?: "Agent needs input" else joinedDetail,
    )
}

internal fun AgentEventReducer.schemaMap(params: Map<String, Any?>): JsonMap? {
    for (key in listOf(
        "requestedSchema",
        "requested_schema",
        "schema",
        "inputSchema",
        "input_schema",
    )) {
        val value = params[key]
        val map = copyStringMap(value) ?: decodeJsonMap(value)
        if (map != null) return map
    }
    for (key in listOf("request", "elicitation", "params")) {
        val nested = copyStringMap(params[key]) ?: decodeJsonMap(params[key]) ?: continue
        val schema = schemaMap(nested)
        if (schema != null) return schema
    }
    return if (params["properties"] is Map<*, *>) LinkedHashMap(params) else null
}

internal fun AgentEventReducer.decodeJsonMap(value: Any?): JsonMap? {
    if (value !is String) return null
    return try {
        val decoded = DartJson.decode(value)
        copyStringMap(decoded)
    } catch (_: Exception) {
        null
    }
}
