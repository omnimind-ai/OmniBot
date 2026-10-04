package cn.com.omnimind.bot.agent.projection

/**
 * Port of Dart `AgentReduceResult` (ui/lib/services/agent_event_reducer.dart).
 */
data class AgentReduceResult(
    val handled: Boolean,
    val method: String? = null,
    val threadId: String? = null,
    val turnId: String? = null,
    val requestId: Any? = null,
    val collaborationMode: String? = null,
    val compatibilityWarning: String? = null,
    /**
     * Whether the event was allowed to mutate the currently active local turn.
     *
     * A stale event can still be [handled] so the shared reducer consumes it
     * without noisy logging, while being unrelated to the turn the page may
     * cancel or display. Keep that distinction explicit at the reducer seam.
     */
    val affectsActiveTurn: Boolean = true,
) {
    /** Dart `copyWith({bool? affectsActiveTurn})`: null keeps the value. */
    fun copyWith(affectsActiveTurn: Boolean? = null): AgentReduceResult = AgentReduceResult(
        handled = handled,
        method = method,
        threadId = threadId,
        turnId = turnId,
        requestId = requestId,
        collaborationMode = collaborationMode,
        compatibilityWarning = compatibilityWarning,
        affectsActiveTurn = affectsActiveTurn ?: this.affectsActiveTurn,
    )
}

/**
 * Stateless ACP event reducer; port of Dart `AgentEventReducer`. Private Dart
 * instance methods are `internal` extension functions spread over the
 * `AgentEventReducer*.kt` files.
 */
class AgentEventReducer {

    /**
     * Projects the terminal result of the official ACP `session/prompt`
     * request. PromptResponse is a request result rather than a
     * `session/update` notification, so it must enter this same reducer
     * instead of being converted into a private `turn/...` event.
     */
    fun reducePromptResponse(
        runtime: ChatConversationRuntimeState,
        sessionId: String?,
        turnId: String? = null,
        stopReason: String? = null,
        error: String? = null,
    ): AgentReduceResult {
        val normalizedSessionId = sessionId?.trim()
        val normalizedTurnId = if (turnId?.trim()?.isNotEmpty() == true) {
            turnId.trim()
        } else {
            runtime.activeAcpTurnId?.trim()
        }
        val reason = stopReason?.trim()?.lowercase() ?: ""
        val isCancelled = reason == "cancelled" || reason == "canceled"
        val isFailure = error?.trim()?.isNotEmpty() == true ||
            reason == "error" ||
            reason == "failed" ||
            reason == "failure" ||
            reason == "timeout"
        val taskId = runtime.resolveAcpEventRunId(
            sessionId = normalizedSessionId,
            turnId = normalizedTurnId,
            fallback = runtime.activeRunId
                ?: runtime.currentDispatchTurnId
                ?: runtime.lastAgentTurnId,
        )
            ?: runtime.currentDispatchTurnId
            ?: runtime.lastAgentTurnId
            ?: normalizedTurnId
            ?: "agent-${runtime.conversationId}"
        if (isFailure) {
            val detail = if (error?.trim()?.isNotEmpty() == true) {
                error.trim()
            } else if (stopReason?.trim()?.isNotEmpty() == true) {
                stopReason.trim()
            } else {
                "ACP session/prompt failed."
            }
            recordTurnFailure(
                runtime,
                taskId = taskId,
                detail = AgentUserErrorText.format(detail),
                params = jsonMapOf().apply {
                    if (normalizedSessionId != null && normalizedSessionId.isNotEmpty()) {
                        put("sessionId", normalizedSessionId)
                    }
                    if (normalizedTurnId != null && normalizedTurnId.isNotEmpty()) {
                        put("turnId", normalizedTurnId)
                    }
                    if (stopReason != null) put("stopReason", stopReason)
                    put("error", detail)
                },
            )
        }
        completeTurn(
            runtime,
            taskId,
            acpTurnId = normalizedTurnId,
            appendCancelIfEmpty = isCancelled,
            cancelled = isCancelled,
            promptStopReason = if (reason.isEmpty()) null else reason,
        )
        return AgentReduceResult(
            handled = true,
            method = "session/prompt",
            threadId = normalizedSessionId,
            turnId = normalizedTurnId,
        )
    }

    fun reduce(
        runtime: ChatConversationRuntimeState,
        event: Map<String, Any?>,
    ): AgentReduceResult {
        // Old Harness payloads may still arrive through a host that has migrated
        // to the ACP EventChannel. Convert them at this one boundary; do not
        // reintroduce the removed private stream or a second reducer.
        val normalizedEvent: Map<String, Any?> = acpLegacyEventAdapter.normalize(event)
        val hostEventId = acpFirstString(normalizedEvent["eventId"], normalizedEvent["hostEventId"])
        if (hostEventId != null && runtime.hasProcessedAcpEventId(hostEventId)) {
            return AgentReduceResult(
                handled = true,
                method = resolveAgentEventMethod(event = normalizedEvent, message = normalizedEvent),
                threadId = acpEventSessionId(normalizedEvent),
                turnId = acpEventTurnId(normalizedEvent),
            )
        }

        // Do not mark an event processed until the complete projection returns.
        // If a listener fails halfway through reduction, the runtime can replay
        // the event instead of either duplicating a partial side effect or losing
        // it behind an eagerly consumed id.
        val result = reduceNormalized(runtime = runtime, event = normalizedEvent)
        if (hostEventId != null) {
            runtime.rememberProcessedAcpEventId(hostEventId)
        }
        return result
    }
}

private const val TURN_ID_MISSING_WARNING = "ACP 事件缺少 turnId，已隔离本轮事件。请更新 Harness 后重试。"

internal fun AgentEventReducer.reduceNormalized(
    runtime: ChatConversationRuntimeState,
    event: Map<String, Any?>,
): AgentReduceResult {
    val message: Map<String, Any?> = copyStringMap(event["message"]) ?: event
    val method = resolveAgentEventMethod(event = event, message = message)
    if (method.isEmpty()) {
        return AgentReduceResult(handled = false)
    }

    val params: JsonMap = eventParams(event = event, message = message, method = method)
    // An actionable request card must carry its owner at creation time. The
    // coordinator still annotates older messages, but response routing cannot
    // depend on that later pass when local ACP processes run in parallel or a
    // conversation is switched while an event is being delivered.
    val eventAgentId = acpFirstString(
        event["agentId"],
        event["agent_id"],
        message["agentId"],
        message["agent_id"],
        params["agentId"],
        params["agent_id"],
    )
    val eventAgentName = acpFirstString(
        event["agentName"],
        event["agent_name"],
        message["agentName"],
        message["agent_name"],
        params["agentName"],
        params["agent_name"],
    )

    // ACP implementation extensions are valid Agent->Client traffic, not
    // unknown failures. Keep their original namespace and payload in the
    // shared runtime so an adapter/card can opt in later. Requests are also
    // retained with their id so respondToServerRequest can answer them.
    if (method.startsWith("_")) {
        rememberAcpExtensionUpdate(
            runtime,
            jsonMapOf().apply {
                put("method", method)
                if (event["id"] != null) put("id", event["id"])
                put("params", if (message.containsKey("params")) message["params"] else params)
                if (event["acpExtensionRequest"] == true) put("request", true)
                if (event["acpExtensionNotification"] == true) put("notification", true)
            },
        )
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = acpFirstString(
                event["threadId"],
                event["sessionId"],
                params["threadId"],
                params["sessionId"],
            ),
            turnId = acpFirstString(event["turnId"], params["turnId"]),
            requestId = event["id"],
        )
    }

    // ACP agents speak the official session/update notification. The reducer
    // projects that protocol object into UI state without introducing a
    // second host-owned event protocol.
    if (method == "session/update") {
        val update = copyStringMap(params["update"])
        val sessionUpdate = acpString(update?.get("sessionUpdate"))
        if (update != null) {
            rememberAcpExtensionMetadata(runtime, update)
        }
        val hasRawAcpUpdate = update?.containsKey("rawUpdate") == true
        val renderableRawAcpUpdate = update != null && isRenderableAcpRawUpdate(update)
        val projected = projectAcpSessionUpdate(
            event = event,
            params = renderableAcpParams(params),
        )
        val scopedUpdate = projected != null &&
            (
                (
                    sessionUpdate != "current_mode_update" &&
                        sessionUpdate != "config_option_update" &&
                        // ACP usage is session-level state; it can arrive after
                        // a streamed turn completed, without a turn id.
                        sessionUpdate != "usage_update" &&
                        // Session metadata is not owned by a prompt turn.
                        sessionUpdate != "session_info_update" &&
                        sessionUpdate != "available_commands_update" &&
                        // User messages can be replayed by session/load without
                        // belonging to a prompt turn.
                        sessionUpdate != "user_message_chunk" &&
                        // UnknownSessionUpdate is forwarded with rawUpdate; its
                        // scope is provider-defined, keep it for extension retention.
                        !hasRawAcpUpdate
                    ) ||
                    renderableRawAcpUpdate
                )
        val updateTurnId = acpFirstString(
            event["turnId"],
            event["turn_id"],
            event["taskId"],
            event["task_id"],
            event["runId"],
            event["run_id"],
            message["turnId"],
            message["turn_id"],
            message["taskId"],
            message["task_id"],
            message["runId"],
            message["run_id"],
            params["turnId"],
            params["turn_id"],
            params["taskId"],
            params["task_id"],
            params["runId"],
            params["run_id"],
            update?.get("turnId"),
            update?.get("turn_id"),
            update?.get("taskId"),
            update?.get("task_id"),
        )
        val hasHostTurnReservation = updateTurnId == null && canUseHostTurnReservation(runtime, event)
        if (scopedUpdate && updateTurnId == null && !hasHostTurnReservation) {
            // ACP updates are streamed inside a prompt turn. Never manufacture a
            // local owner from sessionId or messageId: that reattaches late data
            // to the next prompt and recreates the duplicate-conversation bug.
            val shouldWarnUser = runtime.rememberAcpCompatibilityDiagnostic(
                reason = "turn_id_missing",
                method = method,
                sessionId = acpFirstString(
                    event["sessionId"],
                    event["session_id"],
                    params["sessionId"],
                    params["session_id"],
                ),
                messageId = acpFirstString(
                    update?.get("messageId"),
                    update?.get("message_id"),
                    update?.get("entryId"),
                    update?.get("entry_id"),
                ),
            )
            return AgentReduceResult(
                handled = true,
                method = method,
                compatibilityWarning = if (shouldWarnUser) TURN_ID_MISSING_WARNING else null,
            )
        }
        if (sessionUpdate == "usage_update" && update != null) {
            applyAcpUsage(runtime, acpStandardUsage(update))
            return AgentReduceResult(
                handled = true,
                method = method,
                threadId = acpFirstString(
                    event["sessionId"],
                    event["session_id"],
                    params["sessionId"],
                    params["session_id"],
                ),
                turnId = updateTurnId,
            )
        }
        if (sessionUpdate == "available_commands_update" && update != null) {
            runtime.availableAcpCommands = acpAvailableCommands(update["availableCommands"])
            return AgentReduceResult(
                handled = true,
                method = method,
                threadId = acpFirstString(
                    event["sessionId"],
                    event["session_id"],
                    params["sessionId"],
                    params["session_id"],
                ),
                turnId = updateTurnId,
            )
        }
        if (sessionUpdate == "config_option_update" && update != null) {
            runtime.acpConfigOptions = acpConfigOptions(update["configOptions"])
            return AgentReduceResult(
                handled = true,
                method = method,
                threadId = acpFirstString(
                    event["sessionId"],
                    event["session_id"],
                    params["sessionId"],
                    params["session_id"],
                ),
                turnId = updateTurnId,
            )
        }
        if (sessionUpdate == "current_mode_update" && update != null) {
            runtime.currentAcpModeId = acpString(update["currentModeId"])
        }
        if (sessionUpdate == "session_info_update" && update != null) {
            runtime.acpSessionInfo = LinkedHashMap(update).apply { remove("sessionUpdate") }
        }
        if (hasRawAcpUpdate && update != null && !renderableRawAcpUpdate) {
            rememberAcpExtensionUpdate(runtime, update)
            return AgentReduceResult(
                handled = true,
                method = method,
                threadId = acpFirstString(
                    event["sessionId"],
                    event["session_id"],
                    params["sessionId"],
                    params["session_id"],
                ),
                turnId = updateTurnId,
            )
        }
        // A turn-scoped ACP update without a turn id is not attributable. Do
        // not guess from itemId or threadId: doing so is how late tool output
        // gets attached to the next prompt.
        if (projected == null) {
            return AgentReduceResult(handled = true, method = method)
        }
        return reduce(runtime = runtime, event = projected)
    }
    val threadId = acpFirstString(
        event["threadId"],
        event["thread_id"],
        event["sessionId"],
        event["session_id"],
        params["threadId"],
        params["thread_id"],
        params["sessionId"],
        params["session_id"],
        copyStringMap(params["thread"])?.get("id"),
    )
    val sessionId = acpFirstString(
        event["sessionId"],
        event["session_id"],
        params["sessionId"],
        params["session_id"],
    )
    val turnId = acpFirstString(
        event["turnId"],
        event["turn_id"],
        event["taskId"],
        event["task_id"],
        event["runId"],
        event["run_id"],
        message["turnId"],
        message["turn_id"],
        message["taskId"],
        message["task_id"],
        message["runId"],
        message["run_id"],
        params["turnId"],
        params["turn_id"],
        params["taskId"],
        params["task_id"],
        params["runId"],
        params["run_id"],
        copyStringMap(params["turn"])?.get("id"),
    )
    val itemId = acpFirstString(
        params["itemId"],
        params["item_id"],
        params["callId"],
        params["call_id"],
        copyStringMap(params["item"])?.get("id"),
        copyStringMap(params["item"])?.get("callId"),
        copyStringMap(params["item"])?.get("call_id"),
        params["processId"],
        params["processHandle"],
        params["id"],
    )

    val hasHostTurnReservation = turnId == null && canUseHostTurnReservation(runtime, event)
    if (requiresAcpTurnIdentity(method, params) && turnId == null && !hasHostTurnReservation) {
        val shouldWarnUser = runtime.rememberAcpCompatibilityDiagnostic(
            reason = "turn_id_missing",
            method = method,
            sessionId = sessionId,
            itemId = itemId,
            messageId = acpEventMessageId(event),
            legacy = acpLegacyEventAdapter.isLegacy(event),
        )
        // An item or terminal event without a turn cannot be safely assigned to
        // the active run. Guessing here lets a delayed old Harness event mutate
        // a newer turn, so quarantine it instead.
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            compatibilityWarning = if (shouldWarnUser) TURN_ID_MISSING_WARNING else null,
        )
    }

    // One conversation has one active turn. A delayed update from an older
    // turn must never call touchActiveTurn and replace the current owner of
    // the shared streaming state. Terminal events are still allowed through
    // so that the old turn's cards can be finalized independently.
    val admittedAcpTurnId = runtime.activeAcpTurnId?.trim()
    val currentTurnId = admittedAcpTurnId ?: runtime.currentDispatchTurnId?.trim()
    // Before ACP admits a prompt, currentDispatchTurnId is only the local
    // request/render key. ACP adapters are not required to emit a synthetic
    // `turn/started` (OpenCode begins with `session/update` carrying the
    // official turn id). With exactly one local request placeholder and no
    // admitted official turn, the first turn-scoped non-terminal event is the
    // admission boundary.
    val isTurnAdmission = method == "turn/started" ||
        (
            turnId != null &&
                acpEventAllowsImplicitTurnAdmission(event) &&
                admittedAcpTurnId == null &&
                runtime.isAiResponding &&
                runtime.currentDispatchTurnId != null &&
                runtime.currentDispatchTurnId != turnId &&
                !isTerminalAgentEventMethod(method)
            )
    if (isTurnAdmission && method != "turn/started") {
        runtime.activeAcpTurnId = turnId
    }
    if (turnId != null &&
        currentTurnId != null &&
        currentTurnId.isNotEmpty() &&
        currentTurnId != turnId &&
        !isTurnAdmission &&
        !isTerminalAgentEventMethod(method)
    ) {
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }
    // Resolve protocol identity exactly once. All projected messages use the
    // stable local run id for grouping; the official ACP turn remains in
    // streamMeta/cardData for protocol correlation.
    val parentTaskId = runtime.resolveAcpEventRunId(
        sessionId = sessionId,
        turnId = turnId,
        fallback = acpFirstString(
            runtime.activeRunId,
            runtime.currentDispatchTurnId,
            turnId,
            itemId,
            threadId,
        ),
    ) ?: "agent-${runtime.conversationId}"

    val finalTurnUsagePresentation = method == "item/agentMessage/delta" &&
        (acpExtractText(params["delta"]) ?: "").isEmpty() &&
        copyStringMap(
            copyStringMap(
                copyStringMap(params["acpPresentation"])?.get("usage"),
            )?.get("turnUsage"),
        ) != null
    if (turnId != null &&
        method != "turn/started" &&
        runtime.completedAgentTurnIds.contains(turnId) &&
        !finalTurnUsagePresentation
    ) {
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "turn/started") {
        // A missing wire turn id is a compatibility shape, not permission to
        // promote the local render/run id into ACP identity space.
        if (turnId != null) {
            runtime.activeAcpTurnId = turnId
        }
        touchActiveTurn(runtime, parentTaskId)
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "thread/settings/updated") {
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
            collaborationMode = acpCollaborationModeFromThreadSettings(params),
        )
    }

    if (method == "thread/name/updated") {
        val name = acpFirstString(params["name"], params["title"])?.trim()
        val conversation = runtime.conversation
        // Do not overwrite a useful local title with an empty or malformed ACP
        // notification. The coordinator persists this runtime conversation
        // after a handled event.
        if (name != null && name.isNotEmpty() && conversation != null) {
            // Dart `conversation.copyWith(title: name)` on the toJson shape.
            runtime.conversation = LinkedHashMap(conversation).apply { put("title", name) }
        }
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    // ACP v2 can stream tool content independently from the tool lifecycle.
    // Keep that stream on the same card identity.
    if (method == "item/tool/contentDelta") {
        appendAcpToolContent(
            runtime,
            taskId = parentTaskId,
            toolCallId = acpFirstString(
                params["toolCallId"],
                params["tool_call_id"],
                params["callId"],
                params["call_id"],
                params["itemId"],
                params["item_id"],
                params["terminalId"],
                params["terminal_id"],
            ),
            content = params["content"],
            raw = params,
        )
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "item/started" || method == "item/updated") {
        val item: JsonMap = copyStringMap(params["item"]) ?: params
        val itemType = canonicalAgentItemType(acpString(item["type"]))
        val startedItemId = acpFirstString(
            item["id"],
            item["callId"],
            item["call_id"],
            params["itemId"],
            params["callId"],
            params["call_id"],
            params["id"],
        ) ?: parentTaskId
        touchActiveTurn(runtime, parentTaskId)
        if (itemType == "reasoning") {
            val text = acpExtractText(item["text"])
                ?: acpExtractText(item["summary"])
                ?: acpExtractText(item["content"])
                ?: ""
            // item/started is only a lifecycle hint; rendering it created a
            // blank card. The first non-empty reasoning update creates the card
            // with the same stable item identity.
            if (text.isNotEmpty()) {
                val thinkingEntryId = thinkingCardIdForTask(
                    runtime,
                    parentTaskId = parentTaskId,
                    requestedCardId = "$startedItemId-agent-thinking",
                )
                upsertThinkingCard(
                    runtime,
                    taskId = parentTaskId,
                    cardId = thinkingEntryId,
                    thinkingContent = text,
                    isLoading = true,
                    stage = ThinkingStage.THINKING,
                    streamMeta = streamMeta(
                        runtime,
                        parentTaskId = parentTaskId,
                        entryId = thinkingEntryId,
                        kind = "thinking_snapshot",
                    ),
                )
            }
        } else if (itemType == "agentMessage") {
            val text = acpExtractText(item["text"]) ?: ""
            if (text.isNotEmpty()) {
                finalizeActiveThinkingCardForTask(runtime, parentTaskId)
                appendAssistantText(
                    runtime,
                    parentTaskId = parentTaskId,
                    entryId = "$startedItemId-agent-message",
                    delta = text,
                    isFinal = false,
                )
            }
        } else if (isAgentToolItemType(itemType)) {
            finalizeActiveThinkingCardForTask(runtime, parentTaskId)
            val existingCardId = findToolCardIdForCallId(
                runtime,
                startedItemId,
                taskId = parentTaskId,
                sessionId = acpFirstString(
                    item["sessionId"],
                    item["session_id"],
                    sessionId,
                ),
            )
            val existingMessage = if (existingCardId == null) {
                null
            } else {
                runtime.messages.firstOrNull { it.id == existingCardId }
            }
            val existing = if (existingCardId == null) null else toolCardData(runtime, existingCardId)
            val itemWithIdentity: JsonMap = LinkedHashMap(item).apply {
                if (sessionId != null) put("sessionId", sessionId)
                if (turnId != null) put("turnId", turnId)
            }
            val mergedItem = mergeAgentToolUpdate(existing, itemWithIdentity)
            val toolInfo = normalizeAgentToolCall(
                mergedItem,
                itemType = canonicalAgentItemType(acpString(mergedItem["type"]) ?: itemType),
                fallbackToolType = dartToString(existing?.get("toolType") ?: ""),
                fallbackTitle = dartToString(existing?.get("toolTitle") ?: existing?.get("displayName")),
                fallbackStatus = "running",
            )
            val cardId = existingCardId ?: taskScopedCardId(
                runtime,
                taskId = parentTaskId,
                baseCardId = toolCardBaseId(
                    raw = mergedItem,
                    fallback = "$startedItemId-agent-" +
                        agentToolCardSuffix(toolInfo.toolType, itemType = toolInfo.itemType),
                    suffix = agentToolCardSuffix(toolInfo.toolType, itemType = toolInfo.itemType),
                ),
            )
            upsertToolCard(
                runtime,
                cardId = cardId,
                taskId = parentTaskId,
                toolType = toolInfo.toolType,
                title = toolInfo.toolTitle,
                status = toolInfo.status,
                summary = toolInfo.summary,
                progress = toolInfo.progress,
                terminalOutput = toolInfo.terminalOutput,
                raw = mergedItem,
                streamMeta = streamMeta(
                    runtime,
                    parentTaskId = parentTaskId,
                    entryId = cardId,
                    kind = if (method == "item/updated") "tool_progress" else "tool_started",
                    existingMessage = existingMessage,
                ),
            )
        } else if (itemType.contains("requestApproval")) {
            val requestId = acpRequestId(params = params, message = message, item = item)
            val cardId = "$startedItemId-agent-approval"
            upsertAgentRequestCard(
                runtime,
                cardId = cardId,
                taskId = parentTaskId,
                requestId = requestId,
                requestKind = "approval",
                title = approvalTitle(itemType, item),
                detail = approvalDetail(item),
                params = item,
                agentId = eventAgentId,
                agentName = eventAgentName,
                sessionId = sessionId,
                toolCallId = startedItemId,
                streamMeta = streamMeta(
                    runtime,
                    parentTaskId = parentTaskId,
                    entryId = cardId,
                    kind = "permission_required",
                ),
            )
        } else if (itemType.contains("requestUserInput")) {
            val requestId = acpRequestId(params = params, message = message, item = item)
            val question = firstQuestion(item)
            val cardId = "$startedItemId-agent-user-input"
            upsertAgentRequestCard(
                runtime,
                cardId = cardId,
                taskId = parentTaskId,
                requestId = requestId,
                requestKind = "user_input",
                title = question.title,
                detail = question.detail,
                questionId = question.id,
                params = item,
                agentId = eventAgentId,
                agentName = eventAgentName,
                sessionId = sessionId,
                toolCallId = startedItemId,
                streamMeta = streamMeta(
                    runtime,
                    parentTaskId = parentTaskId,
                    entryId = cardId,
                    kind = "clarify_required",
                ),
            )
        }
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
            requestId = acpRequestId(params = params, message = message),
        )
    }

    if (method == "item/userMessage/delta") {
        val isReplay = params["replay"] == true
        val delta = acpExtractText(params["delta"])
            ?: acpExtractText(params["text"])
            ?: acpExtractText(params["message"])
            ?: ""
        val entryId = acpString(params["entryId"]) ?: "${itemId ?: parentTaskId}-user-message"
        if (delta.isNotEmpty()) {
            val text = (runtime.currentAcpUserMessages[entryId] ?: "") + delta
            runtime.currentAcpUserMessages[entryId] = text
            val messageId = "$entryId-agent-user"

            // ChatPage inserts the user bubble before opening ACP. Live ACP user
            // echoes must converge on that bubble, not create a second one. The
            // local dispatch id is the only reliable bridge; never deduplicate
            // historical prompts by text alone.
            if (!isReplay) {
                val dispatchIds = LinkedHashSet<String>().apply {
                    if (runtime.currentDispatchTurnId?.trim()?.isNotEmpty() == true) {
                        add(runtime.currentDispatchTurnId!!.trim())
                    }
                    if (runtime.activeRunId?.trim()?.isNotEmpty() == true) {
                        add(runtime.activeRunId!!.trim())
                    }
                }
                val expectedHostUserIds = dispatchIds
                    .filter { it.endsWith("-ai") }
                    .map { "${it.substring(0, it.length - 3)}-user" }
                    .toCollection(LinkedHashSet())
                val hostIndex = runtime.messages.indexOfFirst {
                    it.user == 1 && expectedHostUserIds.contains(it.id)
                }
                if (hostIndex >= 0) {
                    // A provider may have emitted one echo before the host
                    // snapshot was installed. Remove only the generated fallback
                    // for this event; unrelated user history must remain.
                    val fallbackIndex = runtime.messages.indexOfFirst {
                        it.id == messageId && it.user == 1
                    }
                    if (fallbackIndex >= 0 && fallbackIndex != hostIndex) {
                        runtime.messages.removeAt(fallbackIndex)
                    }
                    return AgentReduceResult(
                        handled = true,
                        method = method,
                        threadId = threadId,
                        turnId = turnId,
                    )
                }
            }
            val existingIndex = runtime.messages.indexOfFirst { it.id == messageId }
            val userMessage = ChatMessage(
                id = messageId,
                type = 1,
                user = 1,
                content = jsonMapOf("id" to messageId, "text" to text),
                createAtMillis = if (existingIndex >= 0) {
                    runtime.messages[existingIndex].createAtMillis
                } else {
                    System.currentTimeMillis()
                },
            )
            if (existingIndex >= 0) {
                runtime.messages[existingIndex] = userMessage
            } else {
                runtime.messages.add(userMessage)
            }
        }
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "item/agentMessage/delta") {
        val delta = acpExtractText(params["delta"])
            ?: acpExtractText(params["text"])
            ?: acpExtractText(params["message"])
            ?: ""
        val entryId = acpString(params["entryId"]) ?: "${itemId ?: parentTaskId}-agent-message"
        if (delta.isNotEmpty()) {
            finalizeActiveThinkingCardForTask(runtime, parentTaskId)
            appendAssistantText(
                runtime,
                parentTaskId = parentTaskId,
                entryId = entryId,
                delta = delta,
                isFinal = false,
            )
        }
        applyAcpPresentation(
            runtime,
            parentTaskId = parentTaskId,
            entryId = entryId,
            presentation = copyStringMap(params["acpPresentation"]),
        )
        upsertAcpAssistantMedia(
            runtime,
            parentTaskId = parentTaskId,
            entryId = entryId,
            media = asMapList(params["acpAssistantMedia"]),
        )
        upsertAcpAssistantArtifacts(
            runtime,
            parentTaskId = parentTaskId,
            entryId = entryId,
            artifacts = asMapList(params["acpAssistantArtifacts"]),
        )
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (isReasoningMethod(method)) {
        val presentation = copyStringMap(params["acpPresentation"])
        val entryId = acpString(params["entryId"]) ?: "${itemId ?: parentTaskId}-agent-thinking"
        val text = acpExtractText(params["delta"])
            ?: acpExtractText(params["text"])
            ?: acpExtractText(params["summary"])
            ?: acpExtractText(params["part"])
            ?: ""
        val segmentIndex = acpReasoningSegmentIndex(presentation)
        val reasoningCardData: JsonMap = LinkedHashMap<String, Any?>(acpReasoningCardData(presentation)).apply {
            if (segmentIndex != null) put("reasoningSegmentIndex", segmentIndex)
        }
        val reasoningDataKey = pendingAcpReasoningDataKey(
            parentTaskId = parentTaskId,
            entryId = entryId,
        )
        if (text.isNotEmpty()) {
            val pendingReasoningCardData: Map<String, Any?> =
                runtime.pendingAcpReasoningCardData.remove(reasoningDataKey) ?: emptyMap()
            applyAcpPresentation(
                runtime,
                parentTaskId = parentTaskId,
                entryId = entryId,
                presentation = presentation,
            )
            // A retry is a new provider generation inside the same logical turn.
            // The active-card fallback must not merge the new generation back
            // into the failed card. Segment metadata is optional; only use it
            // when the adapter explicitly supplied it.
            if (segmentIndex != null &&
                runtime.activeThinkingCardId != null &&
                activeThinkingSegmentIndex(runtime) != segmentIndex
            ) {
                finalizeActiveThinkingCardForTask(runtime, parentTaskId)
            }
            appendThinking(
                runtime,
                parentTaskId = parentTaskId,
                cardId = entryId,
                delta = text,
                reasoningCardData = LinkedHashMap<String, Any?>(pendingReasoningCardData).apply {
                    putAll(reasoningCardData)
                },
            )
        } else {
            if (reasoningCardData.isNotEmpty()) {
                runtime.pendingAcpReasoningCardData[reasoningDataKey] = LinkedHashMap<String, Any?>().apply {
                    runtime.pendingAcpReasoningCardData[reasoningDataKey]?.let { putAll(it) }
                    putAll(reasoningCardData)
                }
            }
            applyAcpPresentation(
                runtime,
                parentTaskId = parentTaskId,
                entryId = entryId,
                presentation = presentation,
            )
        }
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "turn/plan/removed") {
        val planId = acpFirstString(params["planId"], params["id"], itemId)
        runtime.messages.removeWhere { message ->
            val cardData = message.cardData
            if (cardData?.get("toolType") != "plan" || !cardBelongsToTask(cardData!!, parentTaskId)) {
                return@removeWhere false
            }
            val cardPlanId = acpString(cardData["planId"])
            planId == null || cardPlanId == null || cardPlanId == planId
        }
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "item/plan/delta" || method == "turn/plan/updated") {
        val text = acpExtractText(params["delta"])
            ?: acpExtractText(params["plan"])
            ?: acpExtractText(params["text"])
            ?: ""
        val cardId = taskScopedCardId(
            runtime,
            taskId = parentTaskId,
            baseCardId = "${itemId ?: parentTaskId}-agent-plan",
        )
        upsertToolCard(
            runtime,
            cardId = cardId,
            taskId = parentTaskId,
            toolType = "plan",
            title = "Agent plan",
            status = "running",
            summary = text,
            progress = text,
            raw = LinkedHashMap<String, Any?>(params).apply {
                if (acpFirstString(params["planId"], itemId) != null) {
                    put("planId", acpFirstString(params["planId"], itemId))
                }
            },
            streamMeta = streamMeta(
                runtime,
                parentTaskId = parentTaskId,
                entryId = cardId,
                kind = "tool_progress",
            ),
        )
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "item/commandExecution/outputDelta" ||
        method == "item/commandExecution/terminalInteraction"
    ) {
        val delta = acpExtractText(params["delta"])
            ?: acpExtractText(params["output"])
            ?: acpExtractText(params["text"])
            ?: ""
        val callId = itemId ?: parentTaskId
        val existingCardId = findToolCardIdForCallId(
            runtime,
            callId,
            taskId = parentTaskId,
        )
        val existing = if (existingCardId == null) null else toolCardData(runtime, existingCardId)
        val cardId = existingCardId ?: taskScopedCardId(
            runtime,
            taskId = parentTaskId,
            baseCardId = "$callId-agent-command",
        )
        val toolType = dartToString(existing?.get("toolType") ?: "")!!.trim()
        val title = dartToString(existing?.get("toolTitle") ?: existing?.get("displayName"))
            ?: commandTitle(params)
        val outputTaskId = acpFirstString(existing?.get("taskId"), parentTaskId) ?: parentTaskId
        appendToolOutput(
            runtime,
            cardId = cardId,
            taskId = outputTaskId,
            toolType = if (toolType.isEmpty()) "terminal" else toolType,
            title = title,
            outputDelta = delta,
            raw = params,
            streamMeta = streamMeta(
                runtime,
                parentTaskId = outputTaskId,
                entryId = cardId,
                kind = "tool_progress",
            ),
        )
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "command/exec/outputDelta" || method == "process/outputDelta") {
        val delta = standaloneProcessOutputDelta(params)
        val processIdentity = standaloneProcessIdentity(params)
        val standaloneId = processIdentity ?: standaloneProcessId(params, method = method)
        val processTaskId = if (processIdentity == null) {
            parentTaskId
        } else {
            runtime.standaloneProcessOwner(processIdentity, parentTaskId)
        }
        val cardId = taskScopedCardId(
            runtime,
            taskId = processTaskId,
            baseCardId = "$standaloneId-agent-command",
        )
        appendToolOutput(
            runtime,
            cardId = cardId,
            taskId = processTaskId,
            toolType = "terminal",
            title = standaloneCommandTitle(params, fallback = standaloneId),
            outputDelta = delta,
            raw = LinkedHashMap<String, Any?>(params).apply {
                put("type", if (method == "command/exec/outputDelta") "commandExec" else "processExecution")
            },
            streamMeta = streamMeta(
                runtime,
                parentTaskId = processTaskId,
                entryId = cardId,
                kind = "tool_progress",
            ),
        )
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "process/exited" || method == "command/exec/completed") {
        val processIdentity = standaloneProcessIdentity(params)
        val processTaskId = if (processIdentity == null) {
            parentTaskId
        } else {
            runtime.standaloneProcessOwner(processIdentity, parentTaskId)
        }
        completeStandaloneProcess(runtime, processTaskId, params, method)
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "item/fileChange/outputDelta" ||
        method == "item/fileChange/patchUpdated" ||
        method == "turn/diff/updated"
    ) {
        val delta = acpExtractText(params["delta"])
            ?: acpExtractText(params["output"])
            ?: acpExtractText(params["text"])
            ?: ""
        val cardId = taskScopedCardId(
            runtime,
            taskId = parentTaskId,
            baseCardId = "${itemId ?: parentTaskId}-agent-file",
        )
        appendToolOutput(
            runtime,
            cardId = cardId,
            taskId = parentTaskId,
            toolType = "file",
            title = fileChangeTitle(params),
            outputDelta = delta,
            raw = params,
            streamMeta = streamMeta(
                runtime,
                parentTaskId = parentTaskId,
                entryId = cardId,
                kind = "tool_progress",
            ),
        )
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "session/request_permission" || method.endsWith("requestApproval")) {
        val requestId = acpRequestId(params = params, message = message)
        val cardId = "${requestId ?: itemId ?: parentTaskId}-agent-approval"
        upsertAgentRequestCard(
            runtime,
            cardId = cardId,
            taskId = parentTaskId,
            requestId = requestId,
            requestKind = "approval",
            title = approvalTitle(method, params),
            detail = approvalDetail(params),
            params = params,
            agentId = eventAgentId,
            agentName = eventAgentName,
            sessionId = sessionId,
            toolCallId = acpFirstString(
                params["toolCallId"],
                params["tool_call_id"],
                params["itemId"],
                params["item_id"],
                itemId,
            ),
            streamMeta = streamMeta(
                runtime,
                parentTaskId = parentTaskId,
                entryId = cardId,
                kind = "permission_required",
            ),
        )
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
            requestId = requestId,
        )
    }

    if (method == "elicitation/create") {
        // ACP structured user input is represented by the existing request
        // Card. The request id stays the JSON-RPC id so the shared response
        // route can answer the original Agent request.
        val requestId = acpRequestId(params = params, message = message)
        val schemaQuestion = elicitationSchemaQuestion(params)
        val requestedTitle = acpFirstString(
            params["title"],
            params["message"],
            params["question"],
        )
        val title = if (isGenericAgentInputTitle(requestedTitle) && schemaQuestion != null) {
            schemaQuestion.title
        } else {
            requestedTitle ?: schemaQuestion?.title ?: "Agent needs input"
        }
        val url = acpFirstString(params["url"], params["uri"])
        val requestedDescription = acpFirstString(
            params["description"],
            params["detail"],
        )
        val description = if (isGenericAgentInputTitle(requestedDescription) && schemaQuestion != null) {
            schemaQuestion.detail
        } else {
            requestedDescription ?: schemaQuestion?.detail
        }
        val detail = ArrayList<String>().apply {
            if (description != null) add(description)
            if (url != null) add(url)
            if (description == null && url == null) add(schemaQuestion?.detail ?: title)
        }.joinToString("\n")
        val cardId = "${requestId ?: itemId ?: parentTaskId}-agent-elicitation"
        upsertAgentRequestCard(
            runtime,
            cardId = cardId,
            taskId = parentTaskId,
            requestId = requestId,
            requestKind = "user_input",
            title = title,
            detail = detail,
            params = params,
            agentId = eventAgentId,
            agentName = eventAgentName,
            sessionId = sessionId,
            structuredElicitation = true,
            streamMeta = streamMeta(
                runtime,
                parentTaskId = parentTaskId,
                entryId = cardId,
                kind = "clarify_required",
            ),
        )
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
            requestId = requestId,
        )
    }

    if (method == "item/tool/requestUserInput") {
        val requestId = acpRequestId(params = params, message = message)
        val question = firstQuestion(params)
        val cardId = "${requestId ?: itemId ?: parentTaskId}-agent-user-input"
        upsertAgentRequestCard(
            runtime,
            cardId = cardId,
            taskId = parentTaskId,
            requestId = requestId,
            requestKind = "user_input",
            title = question.title,
            detail = question.detail,
            questionId = question.id,
            params = params,
            agentId = eventAgentId,
            agentName = eventAgentName,
            sessionId = sessionId,
            toolCallId = acpFirstString(
                params["toolCallId"],
                params["tool_call_id"],
                params["itemId"],
                params["item_id"],
                itemId,
            ),
            streamMeta = streamMeta(
                runtime,
                parentTaskId = parentTaskId,
                entryId = cardId,
                kind = "clarify_required",
            ),
        )
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
            requestId = requestId,
        )
    }

    if (method == "item/mcpToolCall/progress") {
        val progress = acpExtractText(params["message"])
            ?: acpExtractText(params["progress"])
            ?: ""
        val cardId = taskScopedCardId(
            runtime,
            taskId = parentTaskId,
            baseCardId = "${itemId ?: parentTaskId}-agent-tool",
        )
        val existing = toolCardData(runtime, cardId)
        upsertToolCard(
            runtime,
            cardId = cardId,
            taskId = parentTaskId,
            toolType = dartToString(existing?.get("toolType") ?: "mcp")!!,
            title = dartToString(
                existing?.get("toolTitle") ?: existing?.get("displayName") ?: "Agent tool",
            )!!,
            status = "running",
            summary = progress,
            progress = progress,
            raw = params,
            streamMeta = streamMeta(
                runtime,
                parentTaskId = parentTaskId,
                entryId = cardId,
                kind = "tool_progress",
            ),
        )
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "item/tool/call") {
        val toolInfo = normalizeAgentToolCall(
            LinkedHashMap<String, Any?>(params).apply { put("type", "dynamicToolCall") },
            itemType = "dynamicToolCall",
            fallbackStatus = "running",
        )
        val dynamicItemId = acpFirstString(params["callId"], params["itemId"], itemId) ?: parentTaskId
        val cardId = taskScopedCardId(
            runtime,
            taskId = parentTaskId,
            baseCardId = "$dynamicItemId-agent-" +
                agentToolCardSuffix(toolInfo.toolType, itemType = toolInfo.itemType),
        )
        upsertToolCard(
            runtime,
            cardId = cardId,
            taskId = parentTaskId,
            toolType = toolInfo.toolType,
            title = toolInfo.toolTitle,
            status = toolInfo.status,
            summary = toolInfo.summary,
            progress = toolInfo.progress,
            raw = LinkedHashMap<String, Any?>(params).apply { put("type", "dynamicToolCall") },
            streamMeta = streamMeta(
                runtime,
                parentTaskId = parentTaskId,
                entryId = cardId,
                kind = "tool_started",
            ),
        )
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
            requestId = message["id"],
        )
    }

    if (method == "rawResponseItem/completed") {
        completeRawResponseItem(runtime, parentTaskId, params)
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "item/completed") {
        completeItem(runtime, parentTaskId, itemId, params)
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "account/updated" ||
        method == "account/login/completed" ||
        method == "account/rateLimits/updated" ||
        method == "account/read"
    ) {
        val cardId = "$parentTaskId-agent-account"
        upsertToolCard(
            runtime,
            cardId = cardId,
            taskId = parentTaskId,
            toolType = "account",
            title = method,
            status = "success",
            summary = acpAccountSummary(params),
            progress = acpAccountSummary(params),
            raw = params,
            streamMeta = streamMeta(
                runtime,
                parentTaskId = parentTaskId,
                entryId = cardId,
                kind = "tool_completed",
                isFinal = true,
            ),
            touchTurn = false,
        )
        return AgentReduceResult(
            handled = true,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    if (method == "codex/stderr" || method == "codex/parseError") {
        val removedStaleCard = removeAgentDebugStatusCards(runtime)
        return AgentReduceResult(
            handled = removedStaleCard,
            method = method,
            threadId = threadId,
            turnId = turnId,
        )
    }

    return AgentReduceResult(
        handled = false,
        method = method,
        threadId = threadId,
        turnId = turnId,
    )
}
