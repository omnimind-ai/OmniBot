import 'dart:convert';

import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_stream_meta.dart';
import 'package:ui/services/agent_diff_parser.dart';
import 'package:ui/services/agent_identity.dart';
import 'package:ui/services/agent_message_kinds.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/agent_tool_call_parser.dart';
import 'package:ui/services/acp_extension_registry.dart';

class AgentReduceResult {
  const AgentReduceResult({
    required this.handled,
    this.method,
    this.threadId,
    this.turnId,
    this.requestId,
    this.collaborationMode,
    this.compatibilityWarning,
    this.affectsActiveTurn = true,
  });

  final bool handled;
  final String? method;
  final String? threadId;
  final String? turnId;
  final Object? requestId;
  final String? collaborationMode;
  final String? compatibilityWarning;

  /// Whether the event was allowed to mutate the currently active local turn.
  ///
  /// A stale event can still be [handled] so the shared reducer consumes it
  /// without noisy logging, while being unrelated to the turn the page may
  /// cancel or display. Keep that distinction explicit at the reducer seam.
  final bool affectsActiveTurn;

  AgentReduceResult copyWith({bool? affectsActiveTurn}) {
    return AgentReduceResult(
      handled: handled,
      method: method,
      threadId: threadId,
      turnId: turnId,
      requestId: requestId,
      collaborationMode: collaborationMode,
      compatibilityWarning: compatibilityWarning,
      affectsActiveTurn: affectsActiveTurn ?? this.affectsActiveTurn,
    );
  }
}

class AgentEventReducer {
  const AgentEventReducer();

  /// Projects the terminal result of the official ACP `session/prompt`
  /// request. PromptResponse is a request result rather than a
  /// `session/update` notification, so it must enter this same reducer
  /// instead of being converted into a private `turn/*` event.
  AgentReduceResult reducePromptResponse({
    required ChatConversationRuntimeState runtime,
    required String? sessionId,
    String? turnId,
    String? stopReason,
    String? error,
  }) {
    final normalizedSessionId = sessionId?.trim();
    final normalizedTurnId = turnId?.trim().isNotEmpty == true
        ? turnId!.trim()
        : runtime.activeAcpTurnId?.trim();
    final reason = stopReason?.trim().toLowerCase() ?? '';
    final isCancelled = reason == 'cancelled' || reason == 'canceled';
    final isFailure =
        error?.trim().isNotEmpty == true ||
        reason == 'error' ||
        reason == 'failed' ||
        reason == 'failure' ||
        reason == 'timeout';
    final taskId =
        runtime.resolveAcpEventRunId(
          sessionId: normalizedSessionId,
          turnId: normalizedTurnId,
          fallback:
              runtime.activeRunId ??
              runtime.currentDispatchTurnId ??
              runtime.lastAgentTurnId,
        ) ??
        runtime.currentDispatchTurnId ??
        runtime.lastAgentTurnId ??
        normalizedTurnId ??
        'agent-${runtime.conversationId}';
    if (isFailure) {
      final detail = error?.trim().isNotEmpty == true
          ? error!.trim()
          : (stopReason?.trim().isNotEmpty == true
                ? stopReason!.trim()
                : 'ACP session/prompt failed.');
      _recordTurnFailure(
        runtime,
        taskId: taskId,
        detail: formatAgentRuntimeErrorForUser(detail),
        params: <String, dynamic>{
          if (normalizedSessionId != null && normalizedSessionId.isNotEmpty)
            'sessionId': normalizedSessionId,
          if (normalizedTurnId != null && normalizedTurnId.isNotEmpty)
            'turnId': normalizedTurnId,
          if (stopReason != null) 'stopReason': stopReason,
          'error': detail,
        },
      );
    }
    _completeTurn(
      runtime,
      taskId,
      acpTurnId: normalizedTurnId,
      appendCancelIfEmpty: isCancelled,
      cancelled: isCancelled,
    );
    return AgentReduceResult(
      handled: true,
      method: 'session/prompt',
      threadId: normalizedSessionId,
      turnId: normalizedTurnId,
    );
  }

  AgentReduceResult reduce({
    required ChatConversationRuntimeState runtime,
    required Map<String, dynamic> event,
  }) {
    // Old Harness payloads may still arrive through a host that has migrated
    // to the ACP EventChannel. Convert them at this one boundary; do not
    // reintroduce the removed private stream or a second reducer.
    event = _acpLegacyEventAdapter.normalize(event);
    final hostEventId = _firstString([event['eventId'], event['hostEventId']]);
    if (hostEventId != null && runtime.hasProcessedAcpEventId(hostEventId)) {
      return AgentReduceResult(
        handled: true,
        method: _resolveAgentEventMethod(event: event, message: event),
        threadId: acpEventSessionId(event),
        turnId: acpEventTurnId(event),
      );
    }

    // Do not mark an event processed until the complete projection returns.
    // If a listener fails halfway through reduction, the runtime can replay
    // the event instead of either duplicating a partial side effect or losing
    // it behind an eagerly consumed id.
    final result = _reduceNormalized(runtime: runtime, event: event);
    if (hostEventId != null) {
      runtime.rememberProcessedAcpEventId(hostEventId);
    }
    return result;
  }

  AgentReduceResult _reduceNormalized({
    required ChatConversationRuntimeState runtime,
    required Map<String, dynamic> event,
  }) {
    final message = _asStringMap(event['message']) ?? event;
    final method = _resolveAgentEventMethod(event: event, message: message);
    if (method.isEmpty) {
      return const AgentReduceResult(handled: false);
    }

    final params = _eventParams(event: event, message: message, method: method);
    // An actionable request card must carry its owner at creation time. The
    // coordinator still annotates older messages, but response routing cannot
    // depend on that later pass when local ACP processes run in parallel or a
    // conversation is switched while an event is being delivered.
    final eventAgentId = _firstString([
      event['agentId'],
      event['agent_id'],
      message['agentId'],
      message['agent_id'],
      params['agentId'],
      params['agent_id'],
    ]);
    final eventAgentName = _firstString([
      event['agentName'],
      event['agent_name'],
      message['agentName'],
      message['agent_name'],
      params['agentName'],
      params['agent_name'],
    ]);

    // ACP implementation extensions are valid Agent->Client traffic, not
    // unknown failures. Keep their original namespace and payload in the
    // shared runtime so an adapter/card can opt in later without changing
    // the transport or losing data today. Requests are also retained with
    // their id so the existing respondToServerRequest bridge can answer them.
    if (method.startsWith('_')) {
      _rememberAcpExtensionUpdate(runtime, <String, dynamic>{
        'method': method,
        if (event['id'] != null) 'id': event['id'],
        'params': message.containsKey('params') ? message['params'] : params,
        if (event['acpExtensionRequest'] == true) 'request': true,
        if (event['acpExtensionNotification'] == true) 'notification': true,
      });
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: _firstString([
          event['threadId'],
          event['sessionId'],
          params['threadId'],
          params['sessionId'],
        ]),
        turnId: _firstString([event['turnId'], params['turnId']]),
        requestId: event['id'],
      );
    }

    // ACP agents speak the official session/update notification. The reducer
    // projects that protocol object into UI state without introducing a
    // second host-owned event protocol.
    if (method == 'session/update') {
      final update = _asStringMap(params['update']);
      final sessionUpdate = _string(update?['sessionUpdate']);
      if (update != null) {
        _rememberAcpExtensionMetadata(runtime, update);
      }
      final hasRawAcpUpdate = update?.containsKey('rawUpdate') == true;
      final renderableRawAcpUpdate =
          update != null && _isRenderableAcpRawUpdate(update);
      final projected = _projectAcpSessionUpdate(
        event: event,
        params: _renderableAcpParams(params),
      );
      final scopedUpdate =
          projected != null &&
          (sessionUpdate != 'current_mode_update' &&
                  sessionUpdate != 'config_option_update' &&
                  // ACP usage is session-level state. It can legitimately arrive
                  // after a streamed turn has completed, so it must not be rejected
                  // merely because the notification has no turn id.
                  sessionUpdate != 'usage_update' &&
                  // Session metadata is not owned by a prompt turn. Xiaowan used
                  // this information to keep the conversation title in sync, so it
                  // must reach the host even when it arrives between turns.
                  sessionUpdate != 'session_info_update' &&
                  sessionUpdate != 'available_commands_update' &&
                  // User messages can be replayed by session/load without belonging
                  // to a prompt turn. Live echoes are ignored by the projector.
                  sessionUpdate != 'user_message_chunk' &&
                  // UnknownSessionUpdate is forwarded with rawUpdate. Its scope and
                  // shape are provider-defined (it may be a scalar or list, not only
                  // an object), so do not reject it before the shared extension
                  // retention path has a chance to preserve it.
                  !hasRawAcpUpdate ||
              renderableRawAcpUpdate);
      final updateTurnId = _firstString([
        event['turnId'],
        event['turn_id'],
        event['taskId'],
        event['task_id'],
        event['runId'],
        event['run_id'],
        message['turnId'],
        message['turn_id'],
        message['taskId'],
        message['task_id'],
        message['runId'],
        message['run_id'],
        params['turnId'],
        params['turn_id'],
        params['taskId'],
        params['task_id'],
        params['runId'],
        params['run_id'],
        update?['turnId'],
        update?['turn_id'],
        update?['taskId'],
        update?['task_id'],
      ]);
      final hasHostTurnReservation =
          updateTurnId == null && _canUseHostTurnReservation(runtime, event);
      if (scopedUpdate && updateTurnId == null && !hasHostTurnReservation) {
        // ACP updates are streamed inside a prompt turn. Never manufacture a
        // local owner from sessionId or messageId: that reattaches late data
        // to the next prompt and recreates the duplicate-conversation bug.
        final shouldWarnUser = runtime.rememberAcpCompatibilityDiagnostic(
          reason: 'turn_id_missing',
          method: method,
          sessionId: _firstString([
            event['sessionId'],
            event['session_id'],
            params['sessionId'],
            params['session_id'],
          ]),
          messageId: _firstString([
            update?['messageId'],
            update?['message_id'],
            update?['entryId'],
            update?['entry_id'],
          ]),
        );
        return AgentReduceResult(
          handled: true,
          method: method,
          compatibilityWarning: shouldWarnUser
              ? 'ACP 事件缺少 turnId，已隔离本轮事件。请更新 Harness 后重试。'
              : null,
        );
      }
      if (sessionUpdate == 'usage_update' && update != null) {
        _applyAcpUsage(runtime, _acpStandardUsage(update));
        return AgentReduceResult(
          handled: true,
          method: method,
          threadId: _firstString([
            event['sessionId'],
            event['session_id'],
            params['sessionId'],
            params['session_id'],
          ]),
          turnId: updateTurnId,
        );
      }
      if (sessionUpdate == 'available_commands_update' && update != null) {
        runtime.availableAcpCommands = _acpAvailableCommands(
          update['availableCommands'],
        );
        return AgentReduceResult(
          handled: true,
          method: method,
          threadId: _firstString([
            event['sessionId'],
            event['session_id'],
            params['sessionId'],
            params['session_id'],
          ]),
          turnId: updateTurnId,
        );
      }
      if (sessionUpdate == 'config_option_update' && update != null) {
        runtime.acpConfigOptions = _acpConfigOptions(update['configOptions']);
        return AgentReduceResult(
          handled: true,
          method: method,
          threadId: _firstString([
            event['sessionId'],
            event['session_id'],
            params['sessionId'],
            params['session_id'],
          ]),
          turnId: updateTurnId,
        );
      }
      if (sessionUpdate == 'current_mode_update' && update != null) {
        runtime.currentAcpModeId = _string(update['currentModeId']);
      }
      if (sessionUpdate == 'session_info_update' && update != null) {
        runtime.acpSessionInfo = Map<String, dynamic>.from(update)
          ..remove('sessionUpdate');
      }
      if (hasRawAcpUpdate && update != null && !renderableRawAcpUpdate) {
        _rememberAcpExtensionUpdate(runtime, update);
        return AgentReduceResult(
          handled: true,
          method: method,
          threadId: _firstString([
            event['sessionId'],
            event['session_id'],
            params['sessionId'],
            params['session_id'],
          ]),
          turnId: updateTurnId,
        );
      }
      // A turn-scoped ACP update without a turn id is not attributable. Do
      // not guess from itemId or threadId: doing so is how late tool output
      // gets attached to the next prompt.
      if (projected == null) {
        return AgentReduceResult(handled: true, method: method);
      }
      return reduce(runtime: runtime, event: projected);
    }
    final threadId = _firstString([
      event['threadId'],
      event['thread_id'],
      event['sessionId'],
      event['session_id'],
      params['threadId'],
      params['thread_id'],
      params['sessionId'],
      params['session_id'],
      _asStringMap(params['thread'])?['id'],
    ]);
    final sessionId = _firstString([
      event['sessionId'],
      event['session_id'],
      params['sessionId'],
      params['session_id'],
    ]);
    final turnId = _firstString([
      event['turnId'],
      event['turn_id'],
      event['taskId'],
      event['task_id'],
      event['runId'],
      event['run_id'],
      message['turnId'],
      message['turn_id'],
      message['taskId'],
      message['task_id'],
      message['runId'],
      message['run_id'],
      params['turnId'],
      params['turn_id'],
      params['taskId'],
      params['task_id'],
      params['runId'],
      params['run_id'],
      _asStringMap(params['turn'])?['id'],
    ]);
    final itemId = _firstString([
      params['itemId'],
      params['item_id'],
      params['callId'],
      params['call_id'],
      _asStringMap(params['item'])?['id'],
      _asStringMap(params['item'])?['callId'],
      _asStringMap(params['item'])?['call_id'],
      params['processId'],
      params['processHandle'],
      params['id'],
    ]);

    final hasHostTurnReservation =
        turnId == null && _canUseHostTurnReservation(runtime, event);
    if (_requiresAcpTurnIdentity(method, params) &&
        turnId == null &&
        !hasHostTurnReservation) {
      final shouldWarnUser = runtime.rememberAcpCompatibilityDiagnostic(
        reason: 'turn_id_missing',
        method: method,
        sessionId: sessionId,
        itemId: itemId,
        messageId: acpEventMessageId(event),
        legacy: _acpLegacyEventAdapter.isLegacy(event),
      );
      // An item or terminal event without a turn cannot be safely assigned to
      // the active run. Guessing here lets a delayed old Harness event mutate
      // a newer Xiaowan turn, so quarantine it instead.
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        compatibilityWarning: shouldWarnUser
            ? 'ACP 事件缺少 turnId，已隔离本轮事件。请更新 Harness 后重试。'
            : null,
      );
    }

    // One conversation has one active turn. A delayed update from an older
    // turn must never call _touchActiveTurn and replace the current owner of
    // the shared streaming state. Terminal events are still allowed through
    // so that the old turn's cards can be finalized independently.
    final admittedAcpTurnId = runtime.activeAcpTurnId?.trim();
    final currentTurnId =
        admittedAcpTurnId ?? runtime.currentDispatchTurnId?.trim();
    // Before ACP admits a prompt, currentDispatchTurnId is only the local
    // request/render key. A real `turn/started` is the event that binds the
    // official ACP turn id; rejecting it here leaves every following update
    // looking stale and strands the UI in "processing" forever.
    // ACP adapters are not required to emit a synthetic `turn/started`
    // notification. OpenCode, for example, begins with `session/update` and
    // carries the official turn id on that update. When the UI has exactly
    // one local request placeholder and no official turn has been admitted,
    // the first turn-scoped non-terminal event is therefore the admission
    // boundary. Without this, all OpenCode deltas look stale and the later
    // terminal event cannot clear the local "processing" state.
    final isTurnAdmission =
        method == 'turn/started' ||
        (turnId != null &&
            acpEventAllowsImplicitTurnAdmission(event) &&
            admittedAcpTurnId == null &&
            runtime.isAiResponding &&
            runtime.currentDispatchTurnId != null &&
            runtime.currentDispatchTurnId != turnId &&
            !_isTerminalAgentEventMethod(method));
    if (isTurnAdmission && method != 'turn/started') {
      runtime.activeAcpTurnId = turnId;
    }
    if (turnId != null &&
        currentTurnId != null &&
        currentTurnId.isNotEmpty &&
        currentTurnId != turnId &&
        !isTurnAdmission &&
        !_isTerminalAgentEventMethod(method)) {
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }
    // Resolve protocol identity exactly once. All projected messages use the
    // stable local run id for grouping; the official ACP turn remains in
    // streamMeta/cardData for protocol correlation. This prevents a provider
    // turn id arriving after the prompt from renaming the visible run.
    final parentTaskId =
        runtime.resolveAcpEventRunId(
          sessionId: sessionId,
          turnId: turnId,
          fallback: _firstString([
            runtime.activeRunId,
            runtime.currentDispatchTurnId,
            turnId,
            itemId,
            threadId,
          ]),
        ) ??
        'agent-${runtime.conversationId}';

    final finalTurnUsagePresentation =
        method == 'item/agentMessage/delta' &&
        (_extractText(params['delta']) ?? '').isEmpty &&
        _asStringMap(
              _asStringMap(
                _asStringMap(params['acpPresentation'])?['usage'],
              )?['turnUsage'],
            ) !=
            null;
    if (turnId != null &&
        method != 'turn/started' &&
        runtime.completedAgentTurnIds.contains(turnId) &&
        !finalTurnUsagePresentation) {
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'turn/started') {
      // A missing wire turn id is a compatibility shape, not permission to
      // promote the local render/run id into ACP identity space. Keep the
      // local reservation active and wait for an official id-bearing event.
      if (turnId != null) {
        runtime.activeAcpTurnId = turnId;
      }
      _touchActiveTurn(runtime, parentTaskId);
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'thread/settings/updated') {
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
        collaborationMode: _collaborationModeFromThreadSettings(params),
      );
    }

    if (method == 'thread/name/updated') {
      final name = _firstString([params['name'], params['title']])?.trim();
      final conversation = runtime.conversation;
      // Do not overwrite a useful local title with an empty or malformed ACP
      // notification. The coordinator persists this runtime conversation
      // after a handled event, so this keeps the old title-sync capability on
      // the same ACP projection path as every other card/state update.
      if (name != null && name.isNotEmpty && conversation != null) {
        runtime.conversation = conversation.copyWith(title: name);
      }
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    // ACP v2 can stream tool content independently from the tool lifecycle.
    // Keep that stream on the same card identity instead of turning it into a
    // provider-specific event or dropping it as an unknown extension.
    if (method == 'item/tool/contentDelta') {
      _appendAcpToolContent(
        runtime,
        taskId: parentTaskId,
        toolCallId: _firstString([
          params['toolCallId'],
          params['tool_call_id'],
          params['callId'],
          params['call_id'],
          params['itemId'],
          params['item_id'],
          params['terminalId'],
          params['terminal_id'],
        ]),
        content: params['content'],
        raw: params,
      );
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'item/started' || method == 'item/updated') {
      final item = _asStringMap(params['item']) ?? params;
      final itemType = canonicalAgentItemType(_string(item['type']));
      final startedItemId =
          _firstString([
            item['id'],
            item['callId'],
            item['call_id'],
            params['itemId'],
            params['callId'],
            params['call_id'],
            params['id'],
          ]) ??
          parentTaskId;
      _touchActiveTurn(runtime, parentTaskId);
      if (itemType == 'reasoning') {
        final text =
            _extractText(item['text']) ??
            _extractText(item['summary']) ??
            _extractText(item['content']) ??
            '';
        // item/started is only a lifecycle hint. Many ACP agents announce
        // reasoning before its first delta; rendering that hint created a
        // blank card in the conversation. The first non-empty reasoning
        // update creates the card with the same stable item identity.
        if (text.isNotEmpty) {
          final thinkingEntryId = _thinkingCardIdForTask(
            runtime,
            parentTaskId: parentTaskId,
            requestedCardId: '$startedItemId-agent-thinking',
          );
          _upsertThinkingCard(
            runtime,
            taskId: parentTaskId,
            cardId: thinkingEntryId,
            thinkingContent: text,
            isLoading: true,
            stage: ThinkingStage.thinking.value,
            streamMeta: _streamMeta(
              runtime,
              parentTaskId: parentTaskId,
              entryId: thinkingEntryId,
              kind: 'thinking_snapshot',
            ),
          );
        }
      } else if (itemType == 'agentMessage') {
        final text = _extractText(item['text']) ?? '';
        if (text.isNotEmpty) {
          _finalizeActiveThinkingCardForTask(runtime, parentTaskId);
          _appendAssistantText(
            runtime,
            parentTaskId: parentTaskId,
            entryId: '$startedItemId-agent-message',
            delta: text,
            isFinal: false,
          );
        }
      } else if (isAgentToolItemType(itemType)) {
        _finalizeActiveThinkingCardForTask(runtime, parentTaskId);
        final existingCardId = _findToolCardIdForCallId(
          runtime,
          startedItemId,
          taskId: parentTaskId,
          sessionId: _firstString([
            item['sessionId'],
            item['session_id'],
            sessionId,
          ]),
        );
        final existingMessage = existingCardId == null
            ? null
            : runtime.messages.cast<ChatMessageModel?>().firstWhere(
                (message) => message?.id == existingCardId,
                orElse: () => null,
              );
        final existing = existingCardId == null
            ? null
            : _toolCardData(runtime, existingCardId);
        final itemWithIdentity = <String, dynamic>{
          ...item,
          if (sessionId != null) 'sessionId': sessionId,
          if (turnId != null) 'turnId': turnId,
        };
        final mergedItem = _mergeAgentToolUpdate(existing, itemWithIdentity);
        final toolInfo = normalizeAgentToolCall(
          mergedItem,
          itemType: canonicalAgentItemType(
            _string(mergedItem['type']) ?? itemType,
          ),
          fallbackToolType: (existing?['toolType'] ?? '').toString(),
          fallbackTitle: (existing?['toolTitle'] ?? existing?['displayName'])
              ?.toString(),
          fallbackStatus: 'running',
        );
        final cardId =
            existingCardId ??
            _taskScopedCardId(
              runtime,
              taskId: parentTaskId,
              baseCardId: _toolCardBaseId(
                raw: mergedItem,
                fallback:
                    '$startedItemId-agent-${agentToolCardSuffix(toolInfo.toolType, itemType: toolInfo.itemType)}',
                suffix: agentToolCardSuffix(
                  toolInfo.toolType,
                  itemType: toolInfo.itemType,
                ),
              ),
            );
        _upsertToolCard(
          runtime,
          cardId: cardId,
          taskId: parentTaskId,
          toolType: toolInfo.toolType,
          title: toolInfo.toolTitle,
          status: toolInfo.status,
          summary: toolInfo.summary,
          progress: toolInfo.progress,
          terminalOutput: toolInfo.terminalOutput,
          raw: mergedItem,
          streamMeta: _streamMeta(
            runtime,
            parentTaskId: parentTaskId,
            entryId: cardId,
            kind: method == 'item/updated' ? 'tool_progress' : 'tool_started',
            existingMessage: existingMessage,
          ),
        );
      } else if (itemType.contains('requestApproval')) {
        final requestId = _acpRequestId(
          params: params,
          message: message,
          item: item,
        );
        final cardId = '$startedItemId-agent-approval';
        _upsertAgentRequestCard(
          runtime,
          cardId: cardId,
          taskId: parentTaskId,
          requestId: requestId,
          requestKind: 'approval',
          title: _approvalTitle(itemType, item),
          detail: _approvalDetail(item),
          params: item,
          agentId: eventAgentId,
          agentName: eventAgentName,
          sessionId: sessionId,
          toolCallId: startedItemId,
          streamMeta: _streamMeta(
            runtime,
            parentTaskId: parentTaskId,
            entryId: cardId,
            kind: 'permission_required',
          ),
        );
      } else if (itemType.contains('requestUserInput')) {
        final requestId = _acpRequestId(
          params: params,
          message: message,
          item: item,
        );
        final question = _firstQuestion(item);
        final cardId = '$startedItemId-agent-user-input';
        _upsertAgentRequestCard(
          runtime,
          cardId: cardId,
          taskId: parentTaskId,
          requestId: requestId,
          requestKind: 'user_input',
          title: question.title,
          detail: question.detail,
          questionId: question.id,
          params: item,
          agentId: eventAgentId,
          agentName: eventAgentName,
          sessionId: sessionId,
          toolCallId: startedItemId,
          streamMeta: _streamMeta(
            runtime,
            parentTaskId: parentTaskId,
            entryId: cardId,
            kind: 'clarify_required',
          ),
        );
      }
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
        requestId: _acpRequestId(params: params, message: message),
      );
    }

    if (method == 'item/userMessage/delta') {
      final isReplay = params['replay'] == true;
      final delta =
          _extractText(params['delta']) ??
          _extractText(params['text']) ??
          _extractText(params['message']) ??
          '';
      final entryId =
          _string(params['entryId']) ??
          '${itemId ?? parentTaskId}-user-message';
      if (delta.isNotEmpty) {
        final text = (runtime.currentAcpUserMessages[entryId] ?? '') + delta;
        runtime.currentAcpUserMessages[entryId] = text;
        final messageId = '$entryId-agent-user';

        // ChatPage inserts the user bubble before opening ACP. Live ACP user
        // echoes must converge on that bubble, not create a second one. The
        // local dispatch id is the only reliable bridge between the host
        // message and the official ACP turn; never deduplicate historical
        // prompts by text alone.
        if (!isReplay) {
          final dispatchIds = <String>{
            if (runtime.currentDispatchTurnId?.trim().isNotEmpty == true)
              runtime.currentDispatchTurnId!.trim(),
            if (runtime.activeRunId?.trim().isNotEmpty == true)
              runtime.activeRunId!.trim(),
          };
          final expectedHostUserIds = dispatchIds
              .where((id) => id.endsWith('-ai'))
              .map((id) => '${id.substring(0, id.length - 3)}-user')
              .toSet();
          final hostIndex = runtime.messages.indexWhere(
            (message) =>
                message.user == 1 && expectedHostUserIds.contains(message.id),
          );
          if (hostIndex >= 0) {
            // A provider may have emitted one echo before the host snapshot
            // was installed. Remove only the generated fallback for this
            // event; unrelated user history must remain untouched.
            final fallbackIndex = runtime.messages.indexWhere(
              (message) => message.id == messageId && message.user == 1,
            );
            if (fallbackIndex >= 0 && fallbackIndex != hostIndex) {
              runtime.messages.removeAt(fallbackIndex);
            }
            return AgentReduceResult(
              handled: true,
              method: method,
              threadId: threadId,
              turnId: turnId,
            );
          }
        }
        final existingIndex = runtime.messages.indexWhere(
          (message) => message.id == messageId,
        );
        final message = ChatMessageModel(
          id: messageId,
          type: 1,
          user: 1,
          content: <String, dynamic>{'id': messageId, 'text': text},
          createAt: existingIndex >= 0
              ? runtime.messages[existingIndex].createAt
              : DateTime.now(),
        );
        if (existingIndex >= 0) {
          runtime.messages[existingIndex] = message;
        } else {
          runtime.messages.add(message);
        }
      }
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'item/agentMessage/delta') {
      final delta =
          _extractText(params['delta']) ??
          _extractText(params['text']) ??
          _extractText(params['message']) ??
          '';
      final entryId =
          _string(params['entryId']) ??
          '${itemId ?? parentTaskId}-agent-message';
      if (delta.isNotEmpty) {
        _finalizeActiveThinkingCardForTask(runtime, parentTaskId);
        _appendAssistantText(
          runtime,
          parentTaskId: parentTaskId,
          entryId: entryId,
          delta: delta,
          isFinal: false,
        );
      }
      _applyAcpPresentation(
        runtime,
        parentTaskId: parentTaskId,
        entryId: entryId,
        presentation: _asStringMap(params['acpPresentation']),
      );
      _upsertAcpAssistantMedia(
        runtime,
        parentTaskId: parentTaskId,
        entryId: entryId,
        media: _asMapList(params['acpAssistantMedia']),
      );
      _upsertAcpAssistantArtifacts(
        runtime,
        parentTaskId: parentTaskId,
        entryId: entryId,
        artifacts: _asMapList(params['acpAssistantArtifacts']),
      );
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (_isReasoningMethod(method)) {
      final presentation = _asStringMap(params['acpPresentation']);
      final entryId =
          _string(params['entryId']) ??
          '${itemId ?? parentTaskId}-agent-thinking';
      final text =
          _extractText(params['delta']) ??
          _extractText(params['text']) ??
          _extractText(params['summary']) ??
          _extractText(params['part']) ??
          '';
      final segmentIndex = _acpReasoningSegmentIndex(presentation);
      final reasoningCardData = <String, dynamic>{
        ..._acpReasoningCardData(presentation),
        if (segmentIndex != null) 'reasoningSegmentIndex': segmentIndex,
      };
      final reasoningDataKey = _pendingAcpReasoningDataKey(
        parentTaskId: parentTaskId,
        entryId: entryId,
      );
      if (text.isNotEmpty) {
        final pendingReasoningCardData =
            runtime.pendingAcpReasoningCardData.remove(reasoningDataKey) ??
            const <String, dynamic>{};
        _applyAcpPresentation(
          runtime,
          parentTaskId: parentTaskId,
          entryId: entryId,
          presentation: presentation,
        );
        // A retry is a new provider generation inside the same logical turn.
        // ACP message ids are allowed to change at that boundary, but the
        // active-card fallback must not merge the new generation back into
        // the failed card. Segment metadata is optional for external ACP
        // agents; only use it when the adapter explicitly supplied it.
        if (segmentIndex != null &&
            runtime.activeThinkingCardId != null &&
            _activeThinkingSegmentIndex(runtime) != segmentIndex) {
          _finalizeActiveThinkingCardForTask(runtime, parentTaskId);
        }
        _appendThinking(
          runtime,
          parentTaskId: parentTaskId,
          cardId: entryId,
          delta: text,
          reasoningCardData: <String, dynamic>{
            ...pendingReasoningCardData,
            ...reasoningCardData,
          },
        );
      } else {
        if (reasoningCardData.isNotEmpty) {
          runtime.pendingAcpReasoningCardData[reasoningDataKey] = {
            ...?runtime.pendingAcpReasoningCardData[reasoningDataKey],
            ...reasoningCardData,
          };
        }
        _applyAcpPresentation(
          runtime,
          parentTaskId: parentTaskId,
          entryId: entryId,
          presentation: presentation,
        );
      }
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'turn/plan/removed') {
      final planId = _firstString([params['planId'], params['id'], itemId]);
      runtime.messages.removeWhere((message) {
        final cardData = message.cardData;
        if (cardData?['toolType'] != 'plan' ||
            !_cardBelongsToTask(cardData!, parentTaskId)) {
          return false;
        }
        final cardPlanId = _string(cardData['planId']);
        return planId == null || cardPlanId == null || cardPlanId == planId;
      });
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'item/plan/delta' || method == 'turn/plan/updated') {
      final text =
          _extractText(params['delta']) ??
          _extractText(params['plan']) ??
          _extractText(params['text']) ??
          '';
      final cardId = _taskScopedCardId(
        runtime,
        taskId: parentTaskId,
        baseCardId: '${itemId ?? parentTaskId}-agent-plan',
      );
      _upsertToolCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        toolType: 'plan',
        title: 'Agent plan',
        status: 'running',
        summary: text,
        progress: text,
        raw: <String, dynamic>{
          ...params,
          if (_firstString([params['planId'], itemId]) != null)
            'planId': _firstString([params['planId'], itemId]),
        },
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'tool_progress',
        ),
      );
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'item/commandExecution/outputDelta' ||
        method == 'item/commandExecution/terminalInteraction') {
      final delta =
          _extractText(params['delta']) ??
          _extractText(params['output']) ??
          _extractText(params['text']) ??
          '';
      final callId = itemId ?? parentTaskId;
      final existingCardId = _findToolCardIdForCallId(
        runtime,
        callId,
        taskId: parentTaskId,
      );
      final existing = existingCardId == null
          ? null
          : _toolCardData(runtime, existingCardId);
      final cardId =
          existingCardId ??
          _taskScopedCardId(
            runtime,
            taskId: parentTaskId,
            baseCardId: '$callId-agent-command',
          );
      final toolType = (existing?['toolType'] ?? '').toString().trim();
      final title =
          (existing?['toolTitle'] ?? existing?['displayName'])?.toString() ??
          _commandTitle(params);
      final outputTaskId =
          _firstString([existing?['taskId'], parentTaskId]) ?? parentTaskId;
      _appendToolOutput(
        runtime,
        cardId: cardId,
        taskId: outputTaskId,
        toolType: toolType.isEmpty ? 'terminal' : toolType,
        title: title,
        outputDelta: delta,
        raw: params,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: outputTaskId,
          entryId: cardId,
          kind: 'tool_progress',
        ),
      );
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'command/exec/outputDelta' ||
        method == 'process/outputDelta') {
      final delta = _standaloneProcessOutputDelta(params);
      final processIdentity = _standaloneProcessIdentity(params);
      final standaloneId =
          processIdentity ?? _standaloneProcessId(params, method: method);
      final processTaskId = processIdentity == null
          ? parentTaskId
          : runtime.standaloneProcessOwner(processIdentity, parentTaskId);
      final cardId = _taskScopedCardId(
        runtime,
        taskId: processTaskId,
        baseCardId: '$standaloneId-agent-command',
      );
      _appendToolOutput(
        runtime,
        cardId: cardId,
        taskId: processTaskId,
        toolType: 'terminal',
        title: _standaloneCommandTitle(params, fallback: standaloneId),
        outputDelta: delta,
        raw: <String, dynamic>{
          ...params,
          'type': method == 'command/exec/outputDelta'
              ? 'commandExec'
              : 'processExecution',
        },
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: processTaskId,
          entryId: cardId,
          kind: 'tool_progress',
        ),
      );
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'process/exited' || method == 'command/exec/completed') {
      final processIdentity = _standaloneProcessIdentity(params);
      final processTaskId = processIdentity == null
          ? parentTaskId
          : runtime.standaloneProcessOwner(processIdentity, parentTaskId);
      _completeStandaloneProcess(runtime, processTaskId, params, method);
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'item/fileChange/outputDelta' ||
        method == 'item/fileChange/patchUpdated' ||
        method == 'turn/diff/updated') {
      final delta =
          _extractText(params['delta']) ??
          _extractText(params['output']) ??
          _extractText(params['text']) ??
          '';
      final cardId = _taskScopedCardId(
        runtime,
        taskId: parentTaskId,
        baseCardId: '${itemId ?? parentTaskId}-agent-file',
      );
      _appendToolOutput(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        toolType: 'file',
        title: _fileChangeTitle(params),
        outputDelta: delta,
        raw: params,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'tool_progress',
        ),
      );
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'session/request_permission' ||
        method.endsWith('requestApproval')) {
      final requestId = _acpRequestId(params: params, message: message);
      final cardId = '${requestId ?? itemId ?? parentTaskId}-agent-approval';
      _upsertAgentRequestCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        requestId: requestId,
        requestKind: 'approval',
        title: _approvalTitle(method, params),
        detail: _approvalDetail(params),
        params: params,
        agentId: eventAgentId,
        agentName: eventAgentName,
        sessionId: sessionId,
        toolCallId: _firstString([
          params['toolCallId'],
          params['tool_call_id'],
          params['itemId'],
          params['item_id'],
          itemId,
        ]),
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'permission_required',
        ),
      );
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
        requestId: requestId,
      );
    }

    if (method == 'elicitation/create') {
      // ACP structured user input is represented by the existing request
      // Card. The request id stays the JSON-RPC id so the shared response
      // route can answer the original Agent request without a Harness branch.
      final requestId = _acpRequestId(params: params, message: message);
      final schemaQuestion = _elicitationSchemaQuestion(params);
      final requestedTitle = _firstString([
        params['title'],
        params['message'],
        params['question'],
      ]);
      final title =
          (_isGenericAgentInputTitle(requestedTitle) && schemaQuestion != null)
          ? schemaQuestion.title
          : (requestedTitle ?? schemaQuestion?.title ?? 'Agent needs input');
      final url = _firstString([params['url'], params['uri']]);
      final requestedDescription = _firstString([
        params['description'],
        params['detail'],
      ]);
      final description =
          (_isGenericAgentInputTitle(requestedDescription) &&
              schemaQuestion != null)
          ? schemaQuestion.detail
          : (requestedDescription ?? schemaQuestion?.detail);
      final detail = [
        if (description != null) description,
        if (url != null) url,
        if (description == null && url == null)
          (schemaQuestion?.detail ?? title),
      ].join('\n');
      final cardId = '${requestId ?? itemId ?? parentTaskId}-agent-elicitation';
      _upsertAgentRequestCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        requestId: requestId,
        requestKind: 'user_input',
        title: title,
        detail: detail,
        params: params,
        agentId: eventAgentId,
        agentName: eventAgentName,
        sessionId: sessionId,
        structuredElicitation: true,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'clarify_required',
        ),
      );
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
        requestId: requestId,
      );
    }

    if (method == 'item/tool/requestUserInput') {
      final requestId = _acpRequestId(params: params, message: message);
      final question = _firstQuestion(params);
      final cardId = '${requestId ?? itemId ?? parentTaskId}-agent-user-input';
      _upsertAgentRequestCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        requestId: requestId,
        requestKind: 'user_input',
        title: question.title,
        detail: question.detail,
        questionId: question.id,
        params: params,
        agentId: eventAgentId,
        agentName: eventAgentName,
        sessionId: sessionId,
        toolCallId: _firstString([
          params['toolCallId'],
          params['tool_call_id'],
          params['itemId'],
          params['item_id'],
          itemId,
        ]),
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'clarify_required',
        ),
      );
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
        requestId: requestId,
      );
    }

    if (method == 'item/mcpToolCall/progress') {
      final progress =
          _extractText(params['message']) ??
          _extractText(params['progress']) ??
          '';
      final cardId = _taskScopedCardId(
        runtime,
        taskId: parentTaskId,
        baseCardId: '${itemId ?? parentTaskId}-agent-tool',
      );
      final existing = _toolCardData(runtime, cardId);
      _upsertToolCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        toolType: (existing?['toolType'] ?? 'mcp').toString(),
        title:
            (existing?['toolTitle'] ?? existing?['displayName'] ?? 'Agent tool')
                .toString(),
        status: 'running',
        summary: progress,
        progress: progress,
        raw: params,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'tool_progress',
        ),
      );
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'item/tool/call') {
      final toolInfo = normalizeAgentToolCall(
        <String, dynamic>{...params, 'type': 'dynamicToolCall'},
        itemType: 'dynamicToolCall',
        fallbackStatus: 'running',
      );
      final dynamicItemId =
          _firstString([params['callId'], params['itemId'], itemId]) ??
          parentTaskId;
      final cardId = _taskScopedCardId(
        runtime,
        taskId: parentTaskId,
        baseCardId:
            '$dynamicItemId-agent-${agentToolCardSuffix(toolInfo.toolType, itemType: toolInfo.itemType)}',
      );
      _upsertToolCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        toolType: toolInfo.toolType,
        title: toolInfo.toolTitle,
        status: toolInfo.status,
        summary: toolInfo.summary,
        progress: toolInfo.progress,
        raw: <String, dynamic>{...params, 'type': 'dynamicToolCall'},
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'tool_started',
        ),
      );
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
        requestId: message['id'],
      );
    }

    if (method == 'rawResponseItem/completed') {
      _completeRawResponseItem(runtime, parentTaskId, params);
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'item/completed') {
      _completeItem(runtime, parentTaskId, itemId, params);
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'account/updated' ||
        method == 'account/login/completed' ||
        method == 'account/rateLimits/updated' ||
        method == 'account/read') {
      final cardId = '$parentTaskId-agent-account';
      _upsertToolCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        toolType: 'account',
        title: method,
        status: 'success',
        summary: _accountSummary(params),
        progress: _accountSummary(params),
        raw: params,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'tool_completed',
          isFinal: true,
        ),
        touchTurn: false,
      );
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'codex/stderr' || method == 'codex/parseError') {
      final removedStaleCard = _removeAgentDebugStatusCards(runtime);
      return AgentReduceResult(
        handled: removedStaleCard,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    return AgentReduceResult(
      handled: false,
      method: method,
      threadId: threadId,
      turnId: turnId,
    );
  }

  void _applyAcpPresentation(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String entryId,
    required Map<String, dynamic>? presentation,
  }) {
    if (presentation == null || presentation.isEmpty) {
      return;
    }
    final usage = _asStringMap(presentation['usage']);
    if (usage != null) {
      _applyAcpUsage(runtime, usage);
      _applyAcpPerformanceMetrics(
        runtime,
        parentTaskId: parentTaskId,
        entryId: entryId,
        usage: usage,
      );
    }
    final retry = _asStringMap(presentation['retry']);
    if (retry != null) {
      // Retry is optional adapter presentation metadata.  It can annotate
      // output from an already-admitted prompt, but it must not resurrect or
      // create an ACP turn; official session updates and PromptResponse own
      // that lifecycle.
      if (_hasAgentMessage(runtime, entryId)) {
        _upsertAcpRetryPresentation(runtime, entryId: entryId, retry: retry);
      } else {
        _bufferAcpAssistantPresentation(
          runtime,
          parentTaskId: parentTaskId,
          entryId: entryId,
          key: 'retry',
          value: retry,
        );
      }
    }
    final recovery = _asStringMap(presentation['recovery']);
    if (recovery != null) {
      if (_hasAgentMessage(runtime, entryId)) {
        _upsertAcpRecoveryPresentation(
          runtime,
          entryId: entryId,
          recovery: recovery,
        );
      } else {
        _bufferAcpAssistantPresentation(
          runtime,
          parentTaskId: parentTaskId,
          entryId: entryId,
          key: 'recovery',
          value: recovery,
        );
      }
    }
    final clarification = _asStringMap(presentation['clarification']);
    if (clarification != null) {
      if (_hasAgentMessage(runtime, entryId)) {
        _applyAcpClarificationPresentation(
          runtime,
          entryId: entryId,
          clarification: clarification,
        );
      } else {
        _bufferAcpAssistantPresentation(
          runtime,
          parentTaskId: parentTaskId,
          entryId: entryId,
          key: 'clarification',
          value: clarification,
        );
      }
    }
  }

  void _applyAcpUsage(
    ChatConversationRuntimeState runtime,
    Map<String, dynamic> usage,
  ) {
    final conversation = runtime.conversation;
    final latestPromptTokens = _asInt(
      usage['latestPromptTokens'] ?? usage['promptTokens'],
    );
    final promptTokenThreshold = _asInt(usage['promptTokenThreshold']);
    if (conversation == null ||
        (latestPromptTokens == null && promptTokenThreshold == null)) {
      return;
    }
    runtime.conversation = conversation.copyWith(
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
      latestPromptTokensUpdatedAt: DateTime.now().millisecondsSinceEpoch,
    );
  }

  void _applyAcpPerformanceMetrics(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String entryId,
    required Map<String, dynamic> usage,
  }) {
    final prefill = _asDouble(usage['prefillTokensPerSecond']);
    final decode = _asDouble(usage['decodeTokensPerSecond']);
    final turnUsage = _asStringMap(usage['turnUsage']);
    if (prefill == null && decode == null && turnUsage == null) {
      return;
    }
    final index = runtime.messages.indexWhere(
      (message) => message.id == entryId,
    );
    if (index == -1) {
      final key = _pendingAcpPerformanceKey(
        parentTaskId: parentTaskId,
        entryId: entryId,
      );
      runtime.pendingAcpPerformanceMetrics[key] = <String, dynamic>{
        ...?runtime.pendingAcpPerformanceMetrics[key],
        if (prefill != null) 'prefillTokensPerSecond': prefill,
        if (decode != null) 'decodeTokensPerSecond': decode,
        if (turnUsage != null) 'turnUsage': turnUsage,
      };
      return;
    }
    _writeAcpPerformanceMetrics(
      runtime,
      index: index,
      prefill: prefill,
      decode: decode,
      turnUsage: turnUsage,
    );
  }

  void _flushPendingAcpPerformanceMetrics(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String entryId,
  }) {
    final key = _pendingAcpPerformanceKey(
      parentTaskId: parentTaskId,
      entryId: entryId,
    );
    final pending = runtime.pendingAcpPerformanceMetrics.remove(key);
    if (pending == null) return;
    final index = runtime.messages.indexWhere(
      (message) => message.id == entryId,
    );
    if (index == -1) {
      runtime.pendingAcpPerformanceMetrics[key] = pending;
      return;
    }
    _writeAcpPerformanceMetrics(
      runtime,
      index: index,
      prefill: _asDouble(pending['prefillTokensPerSecond']),
      decode: _asDouble(pending['decodeTokensPerSecond']),
      turnUsage: _asStringMap(pending['turnUsage']),
    );
  }

  String _pendingAcpPerformanceKey({
    required String parentTaskId,
    required String entryId,
  }) => '$parentTaskId\u0000$entryId';

  String _pendingAcpReasoningDataKey({
    required String parentTaskId,
    required String entryId,
  }) => '$parentTaskId\u0000$entryId';

  bool _hasAgentMessage(ChatConversationRuntimeState runtime, String entryId) {
    return runtime.messages.any(
      (message) =>
          message.id == entryId && message.type == 1 && message.user == 2,
    );
  }

  void _bufferAcpAssistantPresentation(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String entryId,
    required String key,
    required Map<String, dynamic> value,
  }) {
    final pendingKey = _pendingAcpAssistantPresentationKey(
      parentTaskId: parentTaskId,
      entryId: entryId,
    );
    final pending =
        runtime.pendingAcpAssistantPresentation[pendingKey] ??
        <String, dynamic>{};
    pending[key] = value;
    runtime.pendingAcpAssistantPresentation[pendingKey] = pending;
  }

  void _flushPendingAcpAssistantPresentation(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String entryId,
  }) {
    final pendingKey = _pendingAcpAssistantPresentationKey(
      parentTaskId: parentTaskId,
      entryId: entryId,
    );
    final pending = runtime.pendingAcpAssistantPresentation.remove(pendingKey);
    if (pending == null || pending.isEmpty) return;
    _applyAcpPresentation(
      runtime,
      parentTaskId: parentTaskId,
      entryId: entryId,
      presentation: pending,
    );
  }

  String _pendingAcpAssistantPresentationKey({
    required String parentTaskId,
    required String entryId,
  }) => '$parentTaskId\u0000$entryId';

  void _writeAcpPerformanceMetrics(
    ChatConversationRuntimeState runtime, {
    required int index,
    required double? prefill,
    required double? decode,
    required Map<String, dynamic>? turnUsage,
  }) {
    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? const {});
    if (prefill != null) content['prefillTokensPerSecond'] = prefill;
    if (decode != null) content['decodeTokensPerSecond'] = decode;
    runtime.messages[index] = existing.copyWith(
      content: content,
      turnUsage: turnUsage == null
          ? existing.turnUsage
          : <String, dynamic>{...?existing.turnUsage, ...turnUsage},
    );
  }

  void _upsertAcpRetryPresentation(
    ChatConversationRuntimeState runtime, {
    required String entryId,
    required Map<String, dynamic> retry,
  }) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == entryId,
    );
    if (index == -1) return;
    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? const {});
    content.addAll(<String, dynamic>{
      'text': (content['text'] ?? '').toString(),
      'id': entryId,
      'agentRetrying': true,
      'agentRetryStatusText': _extractText(retry['message']) ?? '正在重试…',
      if (_asInt(retry['count']) != null)
        'agentRetryCount': _asInt(retry['count']),
      if (_asInt(retry['maxRetries']) != null)
        'agentMaxRetries': _asInt(retry['maxRetries']),
      if (_asInt(retry['delayMs']) != null)
        'agentRetryDelayMs': _asInt(retry['delayMs']),
      if (_extractText(retry['reason']) != null)
        'agentRetryReason': _extractText(retry['reason']),
      'agentRetryable': true,
    });
    final message = ChatMessageModel(
      id: entryId,
      type: 1,
      user: 2,
      content: content,
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: runtime.activeRunId ?? entryId,
        entryId: entryId,
        kind: 'retrying',
        existingMessage: existing,
      ),
      createAt: DateTime.fromMillisecondsSinceEpoch(
        _startTimeForEntry(runtime, entryId, existingMessage: existing),
      ),
    );
    runtime.messages[index] = existing.copyWith(
      content: content,
      isError: false,
      streamMeta: message.streamMeta,
    );
  }

  void _upsertAcpRecoveryPresentation(
    ChatConversationRuntimeState runtime, {
    required String entryId,
    required Map<String, dynamic> recovery,
  }) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == entryId,
    );
    if (index == -1) {
      return;
    }
    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? const {});
    final error = _extractText(recovery['error']);
    if (error != null && error.isNotEmpty) {
      content['agentErrorText'] = error;
    }
    content['agentRetryable'] = recovery['retryable'] == true;
    final persistAsError = recovery['persistAsError'];
    runtime.messages[index] = existing.copyWith(
      content: content,
      isError: persistAsError is bool
          ? persistAsError
          : error != null && error.isNotEmpty,
    );
  }

  void _applyAcpClarificationPresentation(
    ChatConversationRuntimeState runtime, {
    required String entryId,
    required Map<String, dynamic> clarification,
  }) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == entryId,
    );
    if (index == -1) {
      return;
    }
    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? const {});
    final question = _extractText(clarification['question']);
    final missingFields = _acpStringList(
      clarification['missingFields'] ?? clarification['missing_fields'],
    );
    content['agentClarificationRequired'] = true;
    if (question != null && question.isNotEmpty) {
      content['agentClarificationQuestion'] = question;
    }
    if (missingFields.isNotEmpty) {
      content['agentClarificationMissingFields'] = missingFields;
    }
    runtime.messages[index] = existing.copyWith(content: content);
  }

  void _touchActiveTurn(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
  ) {
    runtime.completedAgentTurnIds.remove(parentTaskId);
    runtime.isAiResponding = true;
    runtime.activeRunId ??= parentTaskId;
    // currentDispatchTurnId is now only a compatibility alias for activeRunId
    // and therefore must not be overwritten with the official ACP turn id.
    runtime.lastAgentTurnId = runtime.activeRunId;
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
  }

  void _appendAssistantText(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String entryId,
    required String delta,
    required bool isFinal,
    bool replace = false,
  }) {
    final messageId = entryId;
    final index = runtime.messages.indexWhere(
      (message) => message.id == messageId,
    );
    final cachedText = runtime.currentAiMessages[messageId];
    final previous =
        cachedText ?? (index == -1 ? '' : runtime.messages[index].text ?? '');
    final effectiveDelta = replace
        ? delta
        : _deduplicateReplayDelta(
            runtime,
            entryId: messageId,
            existingText: previous,
            delta: delta,
            hasLiveCache: cachedText != null,
          );
    if (effectiveDelta == null) {
      return;
    }
    _touchActiveTurn(runtime, parentTaskId);
    final next = replace ? effectiveDelta : previous + effectiveDelta;
    runtime.agentReplayDeltaOffsets.remove(messageId);
    runtime.currentAiMessages[messageId] = next;
    if (next.isEmpty && index == -1) {
      return;
    }
    final existing = index == -1 ? null : runtime.messages[index];
    final streamMeta = _streamMeta(
      runtime,
      parentTaskId: parentTaskId,
      entryId: messageId,
      kind: 'text_snapshot',
      isFinal: isFinal,
      existingMessage: existing,
    );
    final content = <String, dynamic>{'text': next, 'id': messageId};
    if (index == -1) {
      runtime.messages.insert(
        0,
        ChatMessageModel(
          id: messageId,
          type: 1,
          user: 2,
          content: content,
          streamMeta: streamMeta,
          createAt: DateTime.fromMillisecondsSinceEpoch(
            _startTimeForEntry(runtime, messageId, existingMessage: existing),
          ),
        ),
      );
    } else {
      runtime.messages[index] = runtime.messages[index].copyWith(
        content: content,
        isLoading: false,
        isError: false,
        streamMeta: streamMeta,
      );
    }
    _flushPendingAcpPerformanceMetrics(
      runtime,
      parentTaskId: parentTaskId,
      entryId: messageId,
    );
    _flushPendingAcpAssistantPresentation(
      runtime,
      parentTaskId: parentTaskId,
      entryId: messageId,
    );
  }

  void _upsertAcpAssistantMedia(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String entryId,
    required List<Map<String, dynamic>> media,
  }) {
    for (var index = 0; index < media.length; index++) {
      final item = media[index];
      final mediaType = _string(item['mediaType'])?.toLowerCase();
      final audioDataUrl = _string(item['audioDataUrl']);
      final audioUrl = _string(item['audioUrl']);
      final imageDataUrl = _string(item['imageDataUrl']);
      final imageUrl = _string(item['imageUrl']);
      if (mediaType == 'audio' &&
          (audioDataUrl?.trim().isNotEmpty == true ||
              audioUrl?.trim().isNotEmpty == true)) {
        final cardId = '$entryId-agent-audio-$index';
        _upsertToolCard(
          runtime,
          cardId: cardId,
          taskId: parentTaskId,
          toolType: 'audio',
          title: _string(item['title']) ?? '音频',
          status: 'success',
          summary: _string(item['title']) ?? '音频',
          progress: '',
          raw: <String, dynamic>{
            'type': 'acp_audio',
            'toolType': 'audio',
            'toolName': 'assistant_media',
            'title': _string(item['title']) ?? '音频',
            if (audioDataUrl != null) 'audioDataUrl': audioDataUrl,
            if (audioUrl != null) 'audioUrl': audioUrl,
            if (item['mimeType'] != null) 'mimeType': item['mimeType'],
          },
          streamMeta: _streamMeta(
            runtime,
            parentTaskId: parentTaskId,
            entryId: cardId,
            kind: 'assistant_media',
            isFinal: true,
          ),
        );
        continue;
      }
      final location = imageDataUrl ?? imageUrl;
      if (location == null || location.trim().isEmpty) continue;
      final cardId = '$entryId-agent-image-$index';
      _upsertToolCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        toolType: 'image',
        title: _string(item['title']) ?? '图片',
        status: 'success',
        summary: _string(item['title']) ?? '图片',
        progress: '',
        raw: <String, dynamic>{
          'type': 'image',
          'toolType': 'image',
          'toolName': 'assistant_media',
          'title': _string(item['title']) ?? '图片',
          if (imageDataUrl != null) 'imageDataUrl': imageDataUrl,
          if (imageUrl != null) 'imageUrl': imageUrl,
          if (item['mimeType'] != null) 'mimeType': item['mimeType'],
        },
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'assistant_media',
          isFinal: true,
        ),
      );
    }
  }

  void _upsertAcpAssistantArtifacts(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String entryId,
    required List<Map<String, dynamic>> artifacts,
  }) {
    if (artifacts.isEmpty) return;
    _upsertArtifactCards(
      runtime,
      taskId: parentTaskId,
      parentCardId: entryId,
      artifacts: artifacts,
    );
  }

  void _appendThinking(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String cardId,
    required String delta,
    Map<String, dynamic> reasoningCardData = const <String, dynamic>{},
  }) {
    // Merge chunks only while the same continuous reasoning segment is
    // active. Tool and output boundaries finalize that segment, so later
    // reasoning in the same ACP turn starts a separate timeline card.
    cardId = _thinkingCardIdForTask(
      runtime,
      parentTaskId: parentTaskId,
      requestedCardId: cardId,
    );
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    final existingContent = index == -1
        ? ''
        : (runtime.messages[index].cardData?['thinkingContent'] ?? '')
              .toString();
    final cachedThinking = runtime.activeThinkingCardId == cardId
        ? runtime.currentThinkingMessages[parentTaskId]
        : null;
    final baseContent = cachedThinking ?? existingContent;
    final effectiveDelta = _deduplicateReplayDelta(
      runtime,
      entryId: cardId,
      existingText: baseContent,
      delta: delta,
      hasLiveCache: cachedThinking != null,
    );
    if (effectiveDelta == null) {
      return;
    }
    _touchActiveTurn(runtime, parentTaskId);
    runtime.isDeepThinking = true;
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
    runtime.activeThinkingCardId = cardId;
    final nextContent = baseContent + effectiveDelta;
    runtime.agentReplayDeltaOffsets.remove(cardId);
    runtime.currentThinkingMessages[parentTaskId] = nextContent;
    runtime.deepThinkingContent = nextContent;
    _upsertThinkingCard(
      runtime,
      taskId: parentTaskId,
      cardId: cardId,
      thinkingContent: nextContent,
      isLoading: true,
      stage: ThinkingStage.thinking.value,
      reasoningCardData: reasoningCardData,
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: parentTaskId,
        entryId: cardId,
        kind: 'thinking_snapshot',
        existingMessage: index == -1 ? null : runtime.messages[index],
      ),
    );
  }

  void _upsertThinkingCard(
    ChatConversationRuntimeState runtime, {
    required String taskId,
    required String cardId,
    required String thinkingContent,
    required bool isLoading,
    required int stage,
    Map<String, dynamic> reasoningCardData = const <String, dynamic>{},
    required Map<String, dynamic> streamMeta,
  }) {
    if (isLoading) {
      _finalizeOtherLoadingThinkingCardsForTask(
        runtime,
        parentTaskId: taskId,
        activeCardId: cardId,
      );
      runtime.activeThinkingCardId = cardId;
    }
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    final existing = index == -1 ? null : runtime.messages[index];
    final existingCardData = existing?.cardData ?? const <String, dynamic>{};
    final startTime =
        _asInt(existingCardData['startTime']) ??
        _startTimeForEntry(runtime, cardId, existingMessage: existing);
    final endTime = isLoading
        ? existingCardData['endTime']
        : (existingCardData['endTime'] ??
              DateTime.now().millisecondsSinceEpoch);
    final cardData = <String, dynamic>{
      'type': 'deep_thinking',
      'isLoading': isLoading,
      'thinkingContent': thinkingContent.isNotEmpty
          ? thinkingContent
          : (existingCardData['thinkingContent'] ?? '').toString(),
      'stage': stage,
      'taskID': taskId,
      'runId': taskId,
      'cardId': cardId,
      'startTime': startTime,
      'endTime': endTime,
      'isCollapsible': !isLoading,
      ..._preservedAcpReasoningCardData(existingCardData),
      ...reasoningCardData,
    };
    final message = ChatMessageModel(
      id: cardId,
      type: 2,
      user: 3,
      content: {'cardData': cardData, 'id': cardId},
      streamMeta: streamMeta,
      createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
    );
    if (index == -1) {
      runtime.messages.insert(0, message);
    } else {
      runtime.messages[index] = existing!.copyWith(
        content: {'cardData': cardData, 'id': cardId},
        streamMeta: streamMeta,
      );
    }
  }

  void _appendToolOutput(
    ChatConversationRuntimeState runtime, {
    required String cardId,
    required String taskId,
    required String toolType,
    required String title,
    required String outputDelta,
    required Map<String, dynamic> raw,
    required Map<String, dynamic> streamMeta,
  }) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    final existingCardData = index == -1
        ? const <String, dynamic>{}
        : runtime.messages[index].cardData ?? const <String, dynamic>{};
    final existingOutput = (existingCardData['terminalOutput'] ?? '')
        .toString();
    final output = _trimTerminalOutput(existingOutput + outputDelta);
    _upsertToolCard(
      runtime,
      cardId: cardId,
      taskId: taskId,
      toolType: toolType,
      title: title,
      status: 'running',
      summary: outputDelta.isNotEmpty ? outputDelta.trim() : title,
      progress: outputDelta,
      terminalOutput: output,
      raw: raw,
      streamMeta: streamMeta,
    );
  }

  void _appendAcpToolContent(
    ChatConversationRuntimeState runtime, {
    required String taskId,
    required String? toolCallId,
    required Object? content,
    required Map<String, dynamic> raw,
  }) {
    final callId = toolCallId?.trim();
    if (callId == null || callId.isEmpty) {
      return;
    }
    final existingCardId = _findToolCardIdForCallId(
      runtime,
      callId,
      taskId: taskId,
    );
    final existing = existingCardId == null
        ? null
        : _toolCardData(runtime, existingCardId);
    final existingContent = _acpContentItems(existing?['contentItems']);
    final incomingContent = _acpContentItems(content);
    if (incomingContent.isEmpty && _extractStreamingText(content) == null) {
      return;
    }
    final mergedContent = <Map<String, dynamic>>[
      ...existingContent,
      ...incomingContent,
    ];
    final presentation = _acpStandardToolPresentation(mergedContent);
    final textDelta = _extractStreamingText(content) ?? '';
    final existingStatus = (existing?['status'] ?? 'running').toString();
    final cardId =
        existingCardId ??
        _taskScopedCardId(
          runtime,
          taskId: taskId,
          baseCardId: '$callId-agent-tool',
        );
    final existingRaw = _asStringMap(
      _decodeJsonValue((existing?['rawResultJson'] ?? '').toString()),
    );
    final rawCard = <String, dynamic>{
      ...?existingRaw,
      ...raw,
      'toolCallId': callId,
      'contentItems': mergedContent,
      'content': mergedContent,
      ...presentation,
    };
    _upsertToolCard(
      runtime,
      cardId: cardId,
      taskId: taskId,
      toolType: (presentation['toolType'] ?? existing?['toolType'] ?? 'tool')
          .toString(),
      title: (existing?['toolTitle'] ?? existing?['displayName'] ?? '工具')
          .toString(),
      status: existingStatus,
      summary: textDelta,
      progress: textDelta,
      terminalOutput: existing?['terminalOutput']?.toString() ?? '',
      raw: rawCard,
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: taskId,
        entryId: cardId,
        kind: 'tool_content_delta',
        existingMessage: existing == null
            ? null
            : runtime.messages.firstWhere((message) => message.id == cardId),
      ),
    );
  }

  void _upsertPermissionCard(
    ChatConversationRuntimeState runtime, {
    required String cardId,
    required String taskId,
    required Map<String, dynamic> permission,
    required Map<String, dynamic> streamMeta,
  }) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    final existing = index == -1 ? null : runtime.messages[index];
    final requiredPermissionIds = resolveExecutionPermissionIds(
      permission['requiredPermissionIds'] as Iterable<dynamic>?,
    );
    final missing =
        (permission['missing'] as Iterable<dynamic>?)
            ?.map((value) => value.toString())
            .where((value) => value.trim().isNotEmpty)
            .toList(growable: false) ??
        const <String>[];
    final cardData = <String, dynamic>{
      'type': 'permission_section',
      'taskId': taskId,
      'runId': taskId,
      'cardId': cardId,
      'requiredPermissionIds': requiredPermissionIds,
      'missing': missing,
      // This flag is intentionally live-only. Permission cards restored from
      // history do not carry it, so reopening a conversation cannot launch a
      // settings page unexpectedly.
      'autoOpenAuthorization': true,
      'permissionSource': 'acp_tool_result',
      if (existing?.cardData?['requestId'] != null)
        'requestId': existing!.cardData!['requestId'],
      if (existing?.cardData?['toolCallId'] != null)
        'toolCallId': existing!.cardData!['toolCallId'],
      if (existing?.cardData?['sessionId'] != null)
        'sessionId': existing!.cardData!['sessionId'],
    };
    final message = ChatMessageModel.cardMessage(
      cardData,
      id: cardId,
      streamMeta: streamMeta,
    );
    if (index == -1) {
      runtime.messages.insert(0, message);
    } else {
      runtime.messages[index] = existing!.copyWith(
        content: {'cardData': cardData, 'id': cardId},
        streamMeta: streamMeta,
      );
    }
    runtime.isAiResponding = true;
    runtime.lastAgentToolType = 'permission';
  }

  void _upsertToolCard(
    ChatConversationRuntimeState runtime, {
    required String cardId,
    required String taskId,
    required String toolType,
    required String title,
    required String status,
    required String summary,
    required String progress,
    required Map<String, dynamic> raw,
    required Map<String, dynamic> streamMeta,
    bool touchTurn = true,
    String terminalOutput = '',
  }) {
    if (touchTurn) {
      _touchActiveTurn(runtime, taskId);
    }
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    final existing = index == -1 ? null : runtime.messages[index];
    final existingCardData = existing?.cardData ?? const <String, dynamic>{};
    final identity = AgentToolIdentity.fromMaps(
      raw: raw,
      existing: existingCardData,
    );
    final toolInfo = normalizeAgentToolCall(
      raw,
      fallbackToolType: toolType,
      fallbackTitle: title,
      fallbackStatus: status,
    );
    final effectiveToolType = toolInfo.toolType.isNotEmpty
        ? toolInfo.toolType
        : toolType;
    final effectiveTitle = toolInfo.toolTitle.isNotEmpty
        ? toolInfo.toolTitle
        : title;
    final normalizedSummary = summary.isNotEmpty
        ? summary
        : toolInfo.summary.isNotEmpty
        ? toolInfo.summary
        : '';
    final normalizedProgress = progress.isNotEmpty
        ? progress
        : toolInfo.progress.isNotEmpty
        ? toolInfo.progress
        : '';
    final effectiveTerminalOutput = terminalOutput.isNotEmpty
        ? terminalOutput
        : toolInfo.terminalOutput.isNotEmpty
        ? toolInfo.terminalOutput
        : (existingCardData['terminalOutput'] ?? '').toString();
    final diffText = effectiveToolType == 'file'
        ? _resolveFileDiffText(
            existingCardData: existingCardData,
            raw: raw,
            terminalOutput: effectiveTerminalOutput,
            progress: normalizedProgress,
            summary: normalizedSummary,
          )
        : '';
    final diffSummary = diffText.isEmpty ? null : parseAgentDiffText(diffText);
    final diffPreview = diffSummary == null
        ? ''
        : summarizeAgentDiff(diffSummary);
    final effectiveSummary =
        effectiveToolType == 'file' && diffPreview.isNotEmpty
        ? diffPreview
        : normalizedSummary.isNotEmpty
        ? normalizedSummary
        : (existingCardData['summary'] ?? '').toString();
    final effectiveProgress =
        effectiveToolType == 'file' && diffPreview.isNotEmpty
        ? diffPreview
        : normalizedProgress.isNotEmpty
        ? normalizedProgress
        : (existingCardData['progress'] ?? '').toString();
    final resolvedFilePath = effectiveToolType == 'file'
        ? _resolveFilePath(raw) ??
              (diffSummary?.primaryPath.trim().isNotEmpty == true
                  ? diffSummary!.primaryPath
                  : null) ??
              (existingCardData['filePath'] ?? '').toString()
        : '';
    final artifacts = _asMapList(raw['artifacts']);
    final actions = _asMapList(raw['actions']);
    final planEntries = _asMapList(raw['planEntries'] ?? raw['entries']);
    final contentItems = _acpContentItems(
      raw['contentItems'] ?? raw['content'],
    );
    final subagentEvents = _mergeAcpSubagentEvents(
      existingCardData['subagentEvents'],
      raw['subagentEvents'] ?? raw['subagentEvent'],
    );
    final cardData = <String, dynamic>{
      'type': 'agent_tool_summary',
      'uiStyle': kAgentToolUiStyle,
      'taskId': taskId,
      'runId': taskId,
      'toolName': toolInfo.toolName,
      'displayName': toolInfo.displayName,
      'toolTitle': effectiveTitle,
      // Keep the ACP card's historical `title` alias for status/error cards
      // and older consumers. New cards should prefer `toolTitle`, but a
      // transport failure such as `turn/failed` must remain discoverable by
      // both shapes during replay.
      'title': effectiveTitle,
      'cardId': cardId,
      'toolType': effectiveToolType,
      if (identity.sessionId != null) 'sessionId': identity.sessionId,
      if (identity.turnId != null) 'turnId': identity.turnId,
      if (identity.toolCallId != null) 'toolCallId': identity.toolCallId,
      if (identity.rawProviderToolCallId != null)
        'rawProviderToolCallId': identity.rawProviderToolCallId,
      if (identity.toolKey != null) 'toolKey': identity.toolKey,
      if (toolInfo.serverName != null) 'serverName': toolInfo.serverName,
      'status': status,
      'summary': effectiveSummary,
      'progress': effectiveProgress,
      'argsJson': toolInfo.argsJson.isNotEmpty
          ? toolInfo.argsJson
          : (existingCardData['argsJson'] ?? _safeJson(raw)).toString(),
      'resultPreviewJson': toolInfo.resultPreviewJson.isNotEmpty
          ? toolInfo.resultPreviewJson
          : (existingCardData['resultPreviewJson'] ?? '').toString(),
      'rawResultJson': toolInfo.rawResultJson.isNotEmpty
          ? toolInfo.rawResultJson
          : _safeJson(raw),
      'terminalOutput': effectiveTerminalOutput,
      'terminalOutputDelta': normalizedProgress,
      'contentItems': contentItems.isNotEmpty
          ? contentItems
          : _acpContentItems(existingCardData['contentItems']),
      'subagentEvents': subagentEvents,
      if (raw['terminalSessionId'] != null)
        'terminalSessionId': raw['terminalSessionId'],
      if (raw['terminalStreamState'] != null)
        'terminalStreamState': raw['terminalStreamState'],
      if (raw['workspaceId'] != null) 'workspaceId': raw['workspaceId'],
      if (raw['planId'] != null) 'planId': raw['planId'],
      'planEntries': planEntries.isNotEmpty
          ? planEntries
          : (existingCardData['planEntries'] ?? const <Map<String, dynamic>>[]),
      if (raw['imageDataUrl'] != null) 'imageDataUrl': raw['imageDataUrl'],
      if (raw['dataUrl'] != null) 'dataUrl': raw['dataUrl'],
      if (raw['imageUrl'] != null) 'imageUrl': raw['imageUrl'],
      if (raw['audioDataUrl'] != null) 'audioDataUrl': raw['audioDataUrl'],
      if (raw['audioUrl'] != null) 'audioUrl': raw['audioUrl'],
      if (raw['mimeType'] != null) 'mimeType': raw['mimeType'],
      if (raw['taskId'] != null) 'sourceTaskId': raw['taskId'],
      if (raw['runId'] != null) 'runId': raw['runId'],
      if (raw['run_id'] != null) 'run_id': raw['run_id'],
      'artifacts': artifacts.isNotEmpty
          ? artifacts
          : (existingCardData['artifacts'] ?? const <Map<String, dynamic>>[]),
      'actions': actions.isNotEmpty
          ? actions
          : (existingCardData['actions'] ?? const <Map<String, dynamic>>[]),
      'showArtifactAction':
          artifacts.isNotEmpty ||
          existingCardData['showArtifactAction'] == true,
      'showTerminalOutput':
          (effectiveTerminalOutput.isNotEmpty && diffText.isEmpty) ||
          effectiveToolType == 'terminal',
      'showRawResult': true,
      'showScheduleAction': effectiveToolType == 'schedule',
      'showAlarmAction': effectiveToolType == 'alarm',
    };
    // Keep the old card-level detail fields available to every ACP-backed
    // Harness. They are intentionally copied as data, not interpreted here;
    // cards that understand truncation, clarification, or SubAgent timelines
    // can render them while unknown extensions remain harmless.
    for (final key in const <String>[
      'message',
      'question',
      'missingFields',
      'missing_fields',
      'missing',
      'previewJson',
      'outputTruncated',
      'originalChars',
      'headTail',
      'fullOutputArtifact',
      'subagentStatusText',
    ]) {
      if (raw[key] != null) {
        cardData[key] = raw[key];
      } else if (existingCardData[key] != null) {
        cardData[key] = existingCardData[key];
      }
    }
    if (effectiveToolType == 'file') {
      cardData.addAll(<String, dynamic>{
        'diffText': diffText,
        'showDiff': diffText.isNotEmpty,
        'filePath': resolvedFilePath,
        'changedFiles': diffSummary?.changedFileCount ?? 0,
        'additions': diffSummary?.additions ?? 0,
        'deletions': diffSummary?.deletions ?? 0,
      });
    }
    final startTime = _startTimeForEntry(
      runtime,
      cardId,
      existingMessage: existing,
    );
    final message = ChatMessageModel(
      id: cardId,
      type: 2,
      user: 3,
      content: {'cardData': cardData, 'id': cardId},
      streamMeta: streamMeta,
      createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
    );
    if (index == -1) {
      runtime.messages.insert(0, message);
    } else {
      runtime.messages[index] = existing!.copyWith(
        content: {'cardData': cardData, 'id': cardId},
        streamMeta: streamMeta,
      );
    }
    _upsertArtifactCards(
      runtime,
      taskId: taskId,
      parentCardId: cardId,
      artifacts: artifacts,
    );
    runtime.lastAgentToolType = effectiveToolType;
    if (status == 'running' || status == 'pending' || status == 'progress') {
      runtime.activeToolCardId = cardId;
    } else if (runtime.activeToolCardId == cardId) {
      runtime.activeToolCardId = _findRunningToolCardId(
        runtime,
        taskId: taskId,
        excludingCardId: cardId,
      );
    }
    if (effectiveToolType == 'terminal' || effectiveToolType == 'browser') {
      runtime.chatIslandDisplayLayer = ChatIslandDisplayLayer.tools;
    }
    if (effectiveToolType == 'browser' && toolInfo.status == 'success') {
      final workspaceId = (cardData['workspaceId'] ?? '').toString().trim();
      if (workspaceId.isNotEmpty) {
        runtime.browserSessionSnapshot =
            ChatBrowserSessionSnapshot.tryParseBrowserToolJson(
              rawJson: cardData['rawResultJson'].toString(),
              workspaceId: workspaceId,
            ) ??
            ChatBrowserSessionSnapshot.tryParseBrowserToolJson(
              rawJson: cardData['resultPreviewJson'].toString(),
              workspaceId: workspaceId,
            );
      }
    }
  }

  /// ACP tool updates are sparse and a subagent progress event normally
  /// arrives one-at-a-time in `rawInput`. Do not overwrite the parent card's
  /// previous events with the latest singleton; retain the complete child
  /// timeline while keeping repeated/replayed updates idempotent.
  List<Map<String, dynamic>> _mergeAcpSubagentEvents(
    dynamic existingRaw,
    dynamic incomingRaw,
  ) {
    final merged = <Map<String, dynamic>>[];
    final positions = <String, int>{};

    Iterable<Map<String, dynamic>> normalize(dynamic value) sync* {
      if (value is List) {
        for (final item in value.whereType<Map>()) {
          yield item.map<String, dynamic>(
            (key, nested) => MapEntry(key.toString(), nested),
          );
        }
      } else if (value is Map) {
        yield value.map<String, dynamic>(
          (key, nested) => MapEntry(key.toString(), nested),
        );
      }
    }

    void add(dynamic value) {
      for (final event in normalize(value)) {
        final identity = (event['id'] ?? '').toString().trim();
        final key = identity.isNotEmpty
            ? 'id:$identity'
            : [
                event['subagentId'] ?? event['subagent_id'] ?? '',
                event['taskIndex'] ?? event['task_index'] ?? '',
                event['kind'] ?? '',
                event['seq'] ?? event['sequence'] ?? '',
                event['summary'] ?? event['message'] ?? event['text'] ?? '',
              ].map((part) => part.toString()).join('|');
        // ACP adapters may replay the same child event id with a newer
        // status/summary. Replace that snapshot in place instead of dropping
        // it, while still preventing duplicate delivery from growing the
        // timeline indefinitely.
        final previousIndex = positions[key];
        if (previousIndex == null) {
          positions[key] = merged.length;
          merged.add(event);
        } else {
          merged[previousIndex] = event;
        }
      }
    }

    add(existingRaw);
    add(incomingRaw);

    // Streaming thinking/message updates are cumulative snapshots. Keep the
    // newest snapshot per child, but never collapse lifecycle events such as
    // started/completed/failed, which are needed to show each subtask.
    final latestStreaming = <String, Map<String, dynamic>>{};
    final retained = <Map<String, dynamic>>[];
    for (final event in merged) {
      final kind = (event['kind'] ?? '').toString().trim().toLowerCase();
      if (kind != 'thinking' && kind != 'message') {
        retained.add(event);
        continue;
      }
      final child = (event['subagentId'] ?? event['subagent_id'] ?? '')
          .toString()
          .trim();
      final task = (event['taskIndex'] ?? event['task_index'] ?? '')
          .toString()
          .trim();
      final group = '${child.isNotEmpty ? child : 'task:$task'}|$kind';
      final previous = latestStreaming[group];
      final previousSeq = _asInt(previous?['seq'] ?? previous?['sequence']);
      final currentSeq = _asInt(event['seq'] ?? event['sequence']);
      if (previous == null || (currentSeq ?? -1) >= (previousSeq ?? -1)) {
        latestStreaming[group] = event;
      }
    }
    retained.addAll(latestStreaming.values);
    retained.sort((left, right) {
      final leftSeq = _asInt(left['seq'] ?? left['sequence']) ?? 0;
      final rightSeq = _asInt(right['seq'] ?? right['sequence']) ?? 0;
      if (leftSeq != rightSeq) return leftSeq.compareTo(rightSeq);
      final leftCreated = _asInt(left['createdAt'] ?? left['created_at']) ?? 0;
      final rightCreated =
          _asInt(right['createdAt'] ?? right['created_at']) ?? 0;
      return leftCreated.compareTo(rightCreated);
    });
    return retained;
  }

  String? _findRunningToolCardId(
    ChatConversationRuntimeState runtime, {
    required String taskId,
    required String excludingCardId,
  }) {
    // Messages are newest-first. Selecting the newest still-running card
    // keeps the shared stop action useful when a Harness runs tools in
    // parallel and one of them completes before the others.
    for (final message in runtime.messages) {
      if (message.id == excludingCardId || message.type != 2) continue;
      final card = message.cardData;
      if (card == null || card['type'] != 'agent_tool_summary') continue;
      final cardTaskId = _firstString([
        card['taskId'],
        card['runId'],
        card['parentTaskId'],
      ]);
      if (cardTaskId != taskId) continue;
      final cardStatus = _string(card['status'])?.toLowerCase();
      if (cardStatus == 'running' ||
          cardStatus == 'pending' ||
          cardStatus == 'progress' ||
          cardStatus == 'in_progress') {
        return message.id;
      }
    }
    return null;
  }

  void _upsertArtifactCards(
    ChatConversationRuntimeState runtime, {
    required String taskId,
    required String parentCardId,
    required List<Map<String, dynamic>> artifacts,
  }) {
    for (var index = 0; index < artifacts.length; index++) {
      final artifact = artifacts[index];
      final rawArtifactId = _string(artifact['id'])?.trim();
      final artifactId = rawArtifactId == null || rawArtifactId.isEmpty
          ? index.toString()
          : rawArtifactId;
      final cardId = '$parentCardId-artifact-$artifactId';
      final existingIndex = runtime.messages.indexWhere(
        (message) => message.id == cardId,
      );
      final existing = existingIndex == -1
          ? null
          : runtime.messages[existingIndex];
      final startTime = _startTimeForEntry(
        runtime,
        cardId,
        existingMessage: existing,
      );
      final cardData = <String, dynamic>{
        'type': 'artifact_card',
        'artifact': artifact,
        'taskId': taskId,
        'runId': taskId,
        'cardId': cardId,
      };
      final message = ChatMessageModel(
        id: cardId,
        type: 2,
        user: 3,
        content: {'cardData': cardData, 'id': cardId},
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: taskId,
          entryId: cardId,
          kind: 'artifact',
          isFinal: true,
          existingMessage: existing,
        ),
        createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
      );
      if (existingIndex == -1) {
        runtime.messages.insert(0, message);
      } else {
        runtime.messages[existingIndex] = existing!.copyWith(
          content: {'cardData': cardData, 'id': cardId},
          streamMeta: message.streamMeta,
        );
      }
    }
  }

  void _upsertAgentRequestCard(
    ChatConversationRuntimeState runtime, {
    required String cardId,
    required String taskId,
    required Object? requestId,
    required String requestKind,
    required String title,
    required String detail,
    required Map<String, dynamic> params,
    required Map<String, dynamic> streamMeta,
    String? agentId,
    String? agentName,
    String? sessionId,
    String? toolCallId,
    String? questionId,
    bool structuredElicitation = false,
  }) {
    _touchActiveTurn(runtime, taskId);
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    final existing = index == -1 ? null : runtime.messages[index];
    final startTime = _startTimeForEntry(
      runtime,
      cardId,
      existingMessage: existing,
    );
    final existingRequestId = _string(existing?.cardData?['requestId'])?.trim();
    final nextRequestId = _string(requestId)?.trim();
    final shouldPreserveExistingStatus =
        nextRequestId != null &&
        nextRequestId.isNotEmpty &&
        existingRequestId == nextRequestId;
    final existingCardData = existing?.cardData ?? const <String, dynamic>{};
    final requestAgentId = _firstString([
      agentId,
      params['agentId'],
      params['agent_id'],
      existingCardData['agentId'],
      existingCardData['agent_id'],
    ]);
    final requestAgentName = _firstString([
      agentName,
      params['agentName'],
      params['agent_name'],
      existingCardData['agentName'],
      existingCardData['agent_name'],
    ]);
    final requestSessionId = _firstString([
      sessionId,
      params['sessionId'],
      params['session_id'],
      existingCardData['sessionId'],
      existingCardData['session_id'],
    ]);
    final status = _resolveRequestStatus(
      requestKind: requestKind,
      params: params,
      existingStatus: shouldPreserveExistingStatus
          ? existingCardData['status']
          : null,
    );
    final cardData = <String, dynamic>{
      'type': kAgentRequestCardType,
      'taskId': taskId,
      'runId': taskId,
      'requestId': requestId,
      if (requestId == null) 'interactionUnavailable': true,
      if (requestAgentId != null) 'agentId': requestAgentId,
      if (requestAgentName != null) 'agentName': requestAgentName,
      if (requestSessionId != null) 'sessionId': requestSessionId,
      if (toolCallId != null && toolCallId.trim().isNotEmpty)
        'toolCallId': toolCallId.trim(),
      'requestKind': requestKind,
      'title': title,
      'detail': detail,
      'questionId': questionId,
      if (structuredElicitation) 'structuredElicitation': true,
      'rawParamsJson': _safeJson(params),
      'status': status,
      'conversationId': runtime.conversationId,
      'cardId': cardId,
      'startTime': startTime,
    };
    final message = ChatMessageModel(
      id: cardId,
      type: 2,
      user: 3,
      content: {'cardData': cardData, 'id': cardId},
      streamMeta: streamMeta,
      createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
    );
    if (index == -1) {
      runtime.messages.insert(0, message);
    } else {
      runtime.messages[index] = runtime.messages[index].copyWith(
        content: {'cardData': cardData, 'id': cardId},
        streamMeta: streamMeta,
      );
    }
    runtime.isAiResponding = true;
  }

  String _resolveRequestStatus({
    required String requestKind,
    required Map<String, dynamic> params,
    required dynamic existingStatus,
  }) {
    final existing = _normalizeRequestStatus(
      existingStatus,
      requestKind: requestKind,
    );
    if (_isTerminalRequestStatus(existing)) {
      return existing!;
    }
    final explicit = _normalizeRequestStatus(
      _firstString([
        params['status'],
        params['state'],
        params['requestStatus'],
        params['request_status'],
        _asStringMap(params['request'])?['status'],
        _asStringMap(params['request'])?['state'],
      ]),
      requestKind: requestKind,
    );
    if (explicit != null && explicit != 'pending') {
      return explicit;
    }
    final response =
        params['response'] ??
        params['answer'] ??
        params['answers'] ??
        params['result'] ??
        params['decision'];
    if (response != null) {
      if (requestKind == 'approval') {
        final decision = _firstString([
          response,
          _asStringMap(response)?['decision'],
          _asStringMap(response)?['status'],
          _asStringMap(response)?['state'],
        ])?.toLowerCase();
        if (decision == 'accept' ||
            decision == 'accepted' ||
            decision == 'approve' ||
            decision == 'approved' ||
            decision == 'yes') {
          return 'accepted';
        }
        if (decision == 'decline' ||
            decision == 'declined' ||
            decision == 'reject' ||
            decision == 'rejected' ||
            decision == 'no') {
          return 'declined';
        }
      }
      return requestKind == 'approval' ? 'accepted' : 'submitted';
    }
    return explicit ?? existing ?? 'pending';
  }

  String? _normalizeRequestStatus(
    dynamic value, {
    required String requestKind,
  }) {
    final normalized = _string(value)?.trim().toLowerCase();
    if (normalized == null || normalized.isEmpty) {
      return null;
    }
    return switch (normalized) {
      'accept' || 'accepted' || 'approve' || 'approved' => 'accepted',
      'decline' || 'declined' || 'reject' || 'rejected' => 'declined',
      'submit' ||
      'submitted' ||
      'answered' ||
      'complete' ||
      'completed' => requestKind == 'approval' ? 'accepted' : 'submitted',
      'fail' || 'failed' || 'error' => 'failed',
      'pending' || 'running' || 'requested' || 'open' => 'pending',
      _ => normalized,
    };
  }

  bool _isTerminalRequestStatus(String? status) {
    return status == 'submitted' ||
        status == 'accepted' ||
        status == 'declined';
  }

  String? _deduplicateReplayDelta(
    ChatConversationRuntimeState runtime, {
    required String entryId,
    required String existingText,
    required String delta,
    required bool hasLiveCache,
  }) {
    if (delta.isEmpty || existingText.isEmpty) {
      runtime.agentReplayDeltaOffsets.remove(entryId);
      return delta;
    }
    if (hasLiveCache) {
      // Official DSH ACP emits committed assistant message blocks rather than
      // token deltas. A reconnect/retry can deliver the same committed block
      // again, or a provider can send a cumulative block for the same
      // messageId. Keep the live stream idempotent without changing the ACP
      // envelope or inventing a second event protocol.
      if (delta == existingText) {
        return null;
      }
      if (delta.startsWith(existingText)) {
        return delta.substring(existingText.length);
      }
      runtime.agentReplayDeltaOffsets.remove(entryId);
      return delta;
    }
    final previousOffset = runtime.agentReplayDeltaOffsets[entryId] ?? 0;
    final safeOffset = previousOffset.clamp(0, existingText.length).toInt();
    final remaining = existingText.substring(safeOffset);
    if (!remaining.startsWith(delta)) {
      runtime.agentReplayDeltaOffsets[entryId] = existingText.length;
      return delta;
    }
    final nextOffset = safeOffset + delta.length;
    if (nextOffset >= existingText.length) {
      runtime.agentReplayDeltaOffsets.remove(entryId);
    } else {
      runtime.agentReplayDeltaOffsets[entryId] = nextOffset;
    }
    return null;
  }

  void _completeItem(
    ChatConversationRuntimeState runtime,
    String taskId,
    String? itemId,
    Map<String, dynamic> params,
  ) {
    final item = _asStringMap(params['item']) ?? params;
    final itemType = canonicalAgentItemType(_string(item['type']));
    final permissionCard = _permissionCardFromAcpItem(item);
    if (permissionCard != null) {
      final completedItemId = itemId ?? _string(item['id']) ?? taskId;
      final existingCardId = _findToolCardIdForCallId(
        runtime,
        completedItemId,
        taskId: taskId,
        sessionId: _firstString([
          item['sessionId'],
          item['session_id'],
          params['sessionId'],
          params['session_id'],
        ]),
      );
      final cardId =
          existingCardId ??
          _taskScopedCardId(
            runtime,
            taskId: taskId,
            baseCardId: '$completedItemId-agent-permission',
          );
      _upsertPermissionCard(
        runtime,
        cardId: cardId,
        taskId: taskId,
        permission: permissionCard,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: taskId,
          entryId: cardId,
          kind: 'permission_required',
          isFinal: true,
        ),
      );
      return;
    }
    final text =
        _extractText(item['text']) ??
        _extractText(item['message']) ??
        _extractText(item['content']) ??
        '';
    if (itemType == 'agentMessage') {
      final messageId =
          _string(item['entryId']) ?? '${itemId ?? taskId}-agent-message';
      final existingText = _assistantTextForEntry(runtime, messageId);
      if (text.isNotEmpty && existingText.isEmpty) {
        _appendAssistantText(
          runtime,
          parentTaskId: taskId,
          entryId: messageId,
          delta: text,
          isFinal: true,
        );
      } else if (text.isNotEmpty && text != existingText) {
        if (text.startsWith(existingText)) {
          _appendAssistantText(
            runtime,
            parentTaskId: taskId,
            entryId: messageId,
            delta: text.substring(existingText.length),
            isFinal: true,
          );
        } else {
          _appendAssistantText(
            runtime,
            parentTaskId: taskId,
            entryId: messageId,
            delta: text,
            isFinal: true,
            replace: true,
          );
        }
      }
      _markAssistantEntryFinal(runtime, taskId, messageId);
      runtime.currentAiMessages.remove('${itemId ?? taskId}-agent-message');
      runtime.agentReplayDeltaOffsets.remove(messageId);
    }
    if (itemType == 'reasoning') {
      // Keep the thinking card streaming until the entire turn ends.
      // The owning prompt response finalizes thinking after all item updates.
      final cardId =
          _string(item['entryId']) ?? '${itemId ?? taskId}-agent-thinking';
      _markThinkingItemCompleted(runtime, taskId, cardId);
      runtime.agentReplayDeltaOffsets.remove(cardId);
    }
    if (isAgentToolItemType(itemType)) {
      final completedItemId = itemId ?? _string(item['id']) ?? taskId;
      final existingCardId = _findToolCardIdForCallId(
        runtime,
        completedItemId,
        taskId: taskId,
        sessionId: _firstString([
          item['sessionId'],
          item['session_id'],
          params['sessionId'],
          params['session_id'],
        ]),
      );
      final existingMessage = existingCardId == null
          ? null
          : runtime.messages.cast<ChatMessageModel?>().firstWhere(
              (message) => message?.id == existingCardId,
              orElse: () => null,
            );
      final existing = existingCardId == null
          ? null
          : _toolCardData(runtime, existingCardId);
      final itemWithIdentity = <String, dynamic>{
        ...item,
        if (params['sessionId'] != null) 'sessionId': params['sessionId'],
        if (params['session_id'] != null) 'session_id': params['session_id'],
        if (params['turnId'] != null) 'turnId': params['turnId'],
        if (params['turn_id'] != null) 'turn_id': params['turn_id'],
      };
      final mergedItem = _mergeAgentToolUpdate(existing, itemWithIdentity);
      final mergedItemType = canonicalAgentItemType(
        _string(mergedItem['type']) ?? itemType,
      );
      final toolInfo = normalizeAgentToolCall(
        mergedItem,
        itemType: mergedItemType,
        fallbackToolType: (existing?['toolType'] ?? '').toString(),
        fallbackTitle: (existing?['toolTitle'] ?? existing?['displayName'])
            ?.toString(),
        fallbackStatus: 'success',
      );
      final suffix = agentToolCardSuffix(
        toolInfo.toolType,
        itemType: mergedItemType,
      );
      final cardId =
          existingCardId ??
          _taskScopedCardId(
            runtime,
            taskId: taskId,
            baseCardId: _toolCardBaseId(
              raw: mergedItem,
              fallback: '$completedItemId-agent-$suffix',
              suffix: suffix,
            ),
          );
      _upsertToolCard(
        runtime,
        cardId: cardId,
        taskId: taskId,
        toolType: toolInfo.toolType,
        title: toolInfo.toolTitle,
        status: toolInfo.status,
        summary: toolInfo.summary,
        progress: toolInfo.progress,
        terminalOutput: toolInfo.terminalOutput,
        raw: mergedItem,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: taskId,
          entryId: cardId,
          kind: _isActiveAgentToolStatus(toolInfo.status)
              ? 'tool_progress'
              : 'tool_completed',
          isFinal: !_isActiveAgentToolStatus(toolInfo.status),
          existingMessage: existingMessage,
        ),
        touchTurn: false,
      );
      runtime.agentReplayDeltaOffsets.remove(cardId);
      return;
    }
    final completedItemId = itemId ?? taskId;
    for (final suffix in const [
      'command',
      'file',
      'plan',
      'search',
      'workspace',
      'browser',
      'image',
      'tool',
    ]) {
      _markToolCardComplete(runtime, '$completedItemId-agent-$suffix');
    }
  }

  void _completeRawResponseItem(
    ChatConversationRuntimeState runtime,
    String taskId,
    Map<String, dynamic> params,
  ) {
    final item = _asStringMap(params['item']) ?? params;
    final itemType = _string(item['type']) ?? '';
    if (isAgentToolOutputItemType(itemType)) {
      _completeRawResponseOutputItem(runtime, taskId, params, item, itemType);
      return;
    }
    if (!isAgentToolItemType(itemType)) {
      return;
    }
    final rawItemId = _rawResponseItemId(params, item, taskId);
    final toolInfo = normalizeAgentToolCall(
      item,
      itemType: itemType,
      fallbackStatus: 'success',
    );
    final suffix = agentToolCardSuffix(toolInfo.toolType, itemType: itemType);
    final cardId = _taskScopedCardId(
      runtime,
      taskId: taskId,
      baseCardId: '$rawItemId-agent-$suffix',
    );
    _upsertToolCard(
      runtime,
      cardId: cardId,
      taskId: taskId,
      toolType: toolInfo.toolType,
      title: toolInfo.toolTitle,
      status: toolInfo.status,
      summary: toolInfo.summary,
      progress: toolInfo.progress,
      terminalOutput: toolInfo.terminalOutput,
      raw: item,
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: taskId,
        entryId: cardId,
        kind: _isActiveAgentToolStatus(toolInfo.status)
            ? 'tool_progress'
            : 'tool_completed',
        isFinal: !_isActiveAgentToolStatus(toolInfo.status),
      ),
      touchTurn: false,
    );
    runtime.agentReplayDeltaOffsets.remove(cardId);
  }

  void _completeRawResponseOutputItem(
    ChatConversationRuntimeState runtime,
    String taskId,
    Map<String, dynamic> params,
    Map<String, dynamic> item,
    String itemType,
  ) {
    final callId = _firstString([
      item['callId'],
      item['call_id'],
      params['callId'],
      params['call_id'],
    ]);
    final existingCardId = callId == null
        ? null
        : _findToolCardIdForCallId(
            runtime,
            callId,
            taskId: taskId,
            sessionId: _firstString([
              item['sessionId'],
              item['session_id'],
              params['sessionId'],
              params['session_id'],
            ]),
          );
    final existingMessage = existingCardId == null
        ? null
        : runtime.messages.cast<ChatMessageModel?>().firstWhere(
            (message) => message?.id == existingCardId,
            orElse: () => null,
          );
    final existing = existingCardId == null
        ? null
        : _toolCardData(runtime, existingCardId);
    final fallbackToolType =
        (existing?['toolType'] ?? '').toString().trim().isNotEmpty
        ? (existing!['toolType'] ?? '').toString()
        : itemType == 'tool_search_output'
        ? 'search'
        : 'tool';
    final fallbackTitle =
        (existing?['toolTitle'] ?? existing?['displayName'] ?? '')
            .toString()
            .trim();
    final toolInfo = normalizeAgentToolCall(
      item,
      itemType: itemType,
      fallbackToolType: fallbackToolType,
      fallbackTitle: fallbackTitle.isEmpty ? null : fallbackTitle,
      fallbackStatus: 'success',
    );
    final rawItemId = _rawResponseItemId(params, item, taskId);
    final rawWithIdentity = <String, dynamic>{
      ...item,
      if (callId != null) 'toolCallId': callId,
      if (params['sessionId'] != null) 'sessionId': params['sessionId'],
      if (params['session_id'] != null) 'session_id': params['session_id'],
    };
    final suffix = agentToolCardSuffix(toolInfo.toolType, itemType: itemType);
    final cardId =
        existingCardId ??
        _taskScopedCardId(
          runtime,
          taskId: taskId,
          baseCardId: _toolCardBaseId(
            raw: rawWithIdentity,
            fallback: '$rawItemId-agent-$suffix',
            suffix: suffix,
          ),
        );
    final outputText = _extractAgentRawOutputText(item).trimRight();
    final existingTerminalOutput = (existing?['terminalOutput'] ?? '')
        .toString();
    final terminalOutput = toolInfo.toolType == 'terminal'
        ? _trimTerminalOutput(
            [
              existingTerminalOutput.trimRight(),
              outputText,
            ].where((part) => part.isNotEmpty).join('\n'),
          )
        : existingTerminalOutput;
    final summary = outputText.isNotEmpty
        ? _compactTitle(outputText, maxLength: 96)
        : toolInfo.summary;
    _upsertToolCard(
      runtime,
      cardId: cardId,
      taskId: taskId,
      toolType: toolInfo.toolType,
      title: toolInfo.toolTitle,
      status: toolInfo.status,
      summary: summary,
      progress: summary,
      terminalOutput: terminalOutput,
      raw: rawWithIdentity,
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: taskId,
        entryId: cardId,
        kind: 'tool_completed',
        isFinal: true,
        existingMessage: existingMessage,
      ),
      touchTurn: false,
    );
    runtime.agentReplayDeltaOffsets.remove(cardId);
  }

  String _rawResponseItemId(
    Map<String, dynamic> params,
    Map<String, dynamic> item,
    String taskId,
  ) {
    return _firstString([
          params['itemId'],
          params['item_id'],
          item['id'],
          item['callId'],
          item['call_id'],
          params['callId'],
          params['call_id'],
        ]) ??
        '$taskId-${_stableAgentItemKey(item)}';
  }

  void _completeStandaloneProcess(
    ChatConversationRuntimeState runtime,
    String taskId,
    Map<String, dynamic> params,
    String method,
  ) {
    final standaloneId = _standaloneProcessId(params, method: method);
    final cardId = _taskScopedCardId(
      runtime,
      taskId: taskId,
      baseCardId: '$standaloneId-agent-command',
    );
    final existing = _toolCardData(runtime, cardId);
    final existingOutput = (existing?['terminalOutput'] ?? '').toString();
    final stdout = _streamOutputBlock(params['stdout'], stream: 'stdout');
    final stderr = _streamOutputBlock(params['stderr'], stream: 'stderr');
    final output = _trimTerminalOutput(existingOutput + stdout + stderr);
    final exitCode = _asInt(params['exitCode'] ?? params['exit_code']);
    final status = exitCode == null || exitCode == 0 ? 'success' : 'error';
    final title =
        (existing?['toolTitle'] ?? existing?['displayName'])?.toString() ??
        _standaloneCommandTitle(params, fallback: standaloneId);
    final summary = exitCode == null
        ? 'Command completed'
        : 'Command exited with code $exitCode';
    _upsertToolCard(
      runtime,
      cardId: cardId,
      taskId: taskId,
      toolType: 'terminal',
      title: title,
      status: status,
      summary: summary,
      progress: summary,
      terminalOutput: output,
      raw: <String, dynamic>{
        ...params,
        'type': method == 'process/exited' ? 'processExecution' : 'commandExec',
      },
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: taskId,
        entryId: cardId,
        kind: 'tool_completed',
        isFinal: true,
      ),
      touchTurn: false,
    );
    runtime.agentReplayDeltaOffsets.remove(cardId);
  }

  void _completeTurn(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? acpTurnId,
    // Only the owning prompt response supplies cancellation; an empty
    // assistant message or a session notification cannot imply it.
    bool appendCancelIfEmpty = false,
    bool cancelled = false,
  }) {
    final wasActive = runtime.activeAgentTurnIds.contains(taskId);
    // The UI primes a local render task before ACP has emitted its official
    // turn id. Most adapters bind that placeholder on their first
    // session/update, but an adapter is allowed to answer with only terminal
    // lifecycle data. In that case the terminal event still belongs to the
    // only locally active turn; treating it as an unrelated stale turn leaves
    // the pre-created thinking card loading forever.
    final pendingLocalTaskId =
        runtime.isAiResponding &&
            runtime.activeAcpTurnId == null &&
            runtime.currentDispatchTurnId != null &&
            runtime.currentDispatchTurnId != taskId
        ? runtime.currentDispatchTurnId
        : null;
    final ownerTaskId = pendingLocalTaskId ?? taskId;
    final ownerWasActive = runtime.activeAgentTurnIds.contains(ownerTaskId);
    final protocolTurnMatchesCurrent =
        acpTurnId == null ||
        runtime.activeAcpTurnId == null ||
        runtime.activeAcpTurnId == acpTurnId;
    final isCurrentTurn =
        protocolTurnMatchesCurrent &&
        (runtime.activeRunId == ownerTaskId ||
            runtime.currentDispatchTurnId == ownerTaskId ||
            runtime.lastAgentTurnId == ownerTaskId ||
            runtime.activeAcpTurnId == acpTurnId ||
            runtime.activeAcpTurnId == ownerTaskId);
    // A terminal notification for turn N can arrive after turn N+1 has
    // already started. Finalize only N's cards/messages in that case; never
    // clear the shared runtime flags or text cache owned by N+1.
    if (!isCurrentTurn && runtime.currentDispatchTurnId != null) {
      _markAssistantMessagesFinalForTask(runtime, taskId);
      _clearAcpRetryPresentationForTask(runtime, taskId);
      _finalizeThinkingCardsForTask(runtime, taskId);
      runtime.currentThinkingMessages.remove(taskId);
      if (wasActive) {
        runtime.completedAgentTurnIds.add(taskId);
      }
      return;
    }
    final isManualCancel =
        appendCancelIfEmpty &&
        ownerTaskId == runtime.currentDispatchTurnId &&
        !_hasVisibleAssistantTextForTask(runtime, ownerTaskId) &&
        !_hasCompletedAgentOutputForTask(runtime, ownerTaskId);
    if (isManualCancel) {
      _appendAssistantText(
        runtime,
        parentTaskId: ownerTaskId,
        entryId: '$ownerTaskId-cancelled',
        delta: '任务已取消',
        isFinal: true,
        replace: true,
      );
      _cancelThinkingCardsForTask(runtime, ownerTaskId);
    }
    // Display metadata belongs to the existing prompt completion owner, not
    // a timer or an item-completed notification. Persist it with the last reply.
    final startedAt = runtime.agentEntryStartTimes.remove(
      'prompt:$ownerTaskId',
    );
    // Messages are stored newest first. The first matching reply is the
    // final prose, while the last may be folded into the earlier process.
    final replyIndex = runtime.messages.indexWhere(
      (message) =>
          message.type == 1 &&
          message.user == 2 &&
          message.streamMeta?['parentTaskId'] == ownerTaskId,
    );
    if (startedAt != null && replyIndex >= 0) {
      final endedAt = DateTime.now().millisecondsSinceEpoch;
      final reply = runtime.messages[replyIndex];
      runtime.messages[replyIndex] = reply.copyWith(
        turnUsage: {
          ...?reply.turnUsage,
          'endedAt': endedAt,
          if (endedAt >= startedAt) 'durationMs': endedAt - startedAt,
        },
      );
    }
    runtime.isAiResponding = false;
    runtime.isExecutingTask = false;
    runtime.isCheckingExecutableTask = false;
    runtime.currentDispatchTurnId = null;
    final completedOfficialTurn =
        acpTurnId ?? runtime.acpTurnIdForRun(ownerTaskId);
    if (completedOfficialTurn != null && completedOfficialTurn.isNotEmpty) {
      runtime.rememberCompletedAcpTurn(completedOfficialTurn);
    }
    runtime.activeAcpTurnId = null;
    if (runtime.activeRunId == ownerTaskId) {
      runtime.activeRunId = null;
    }
    runtime.lastAgentTurnId = null;
    runtime.currentAiMessages.clear();
    runtime.currentThinkingMessages.remove(ownerTaskId);
    runtime.pendingAgentTextTaskId = null;
    runtime.activeToolCardId = null;
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    runtime.activeThinkingCardId = null;
    runtime.currentThinkingStage = cancelled
        ? ThinkingStage.cancelled.value
        : ThinkingStage.complete.value;
    _markAssistantMessagesFinalForTask(runtime, ownerTaskId);
    _clearAcpRetryPresentationForTask(runtime, ownerTaskId);
    if (!isManualCancel) {
      _finalizeThinkingCardsForTask(runtime, ownerTaskId);
    }
    if (wasActive || ownerWasActive) {
      runtime.completedAgentTurnIds.add(taskId);
      if (completedOfficialTurn != null && completedOfficialTurn.isNotEmpty) {
        // Keep the protocol id in the legacy fence as well. Older callers
        // inspect completedAgentTurnIds directly; the canonical fence is
        // completedAcpTurnIds above.
        runtime.completedAgentTurnIds.add(completedOfficialTurn);
      }
      if (ownerTaskId != taskId) {
        runtime.completedAgentTurnIds.add(ownerTaskId);
      }
    }
  }

  void _clearAcpRetryPresentationForTask(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    for (var index = 0; index < runtime.messages.length; index++) {
      final message = runtime.messages[index];
      if (message.type != 1 ||
          message.user != 2 ||
          (message.streamMeta?['parentTaskId'] ?? '').toString() != taskId) {
        continue;
      }
      final content = Map<String, dynamic>.from(message.content ?? const {});
      var changed = false;
      for (final key in const <String>[
        'agentRetrying',
        'agentRetryStatusText',
        'agentRetryCount',
        'agentMaxRetries',
        'agentRetryDelayMs',
        'agentRetryReason',
        'agentRetryable',
      ]) {
        changed = content.remove(key) != null || changed;
      }
      if (changed) {
        runtime.messages[index] = message.copyWith(content: content);
      }
    }
  }

  void _recordTurnFailure(
    ChatConversationRuntimeState runtime, {
    required String taskId,
    required String detail,
    required Map<String, dynamic> params,
  }) {
    final cardId = '$taskId-agent-status';
    _upsertToolCard(
      runtime,
      cardId: cardId,
      taskId: taskId,
      toolType: 'status',
      title: '本轮执行失败',
      status: 'error',
      summary: detail,
      progress: detail,
      raw: params,
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: taskId,
        entryId: cardId,
        kind: 'error',
        isFinal: true,
      ),
      touchTurn: false,
    );
  }

  bool _hasVisibleAssistantTextForTask(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    for (final message in runtime.messages) {
      if (message.type != 1 || message.user != 2) {
        continue;
      }
      if ((message.streamMeta?['parentTaskId'] ?? '').toString() != taskId) {
        continue;
      }
      if (message.streamMeta?['isFinal'] == true) {
        return true;
      }
      if ((message.text ?? '').trim().isNotEmpty) {
        return true;
      }
    }
    return false;
  }

  bool _hasCompletedAgentOutputForTask(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    for (final message in runtime.messages) {
      final cardData = message.cardData;
      if (cardData == null) {
        continue;
      }
      final cardTaskId =
          _string(cardData['taskID']) ??
          _string(cardData['taskId']) ??
          _string(message.streamMeta?['parentTaskId']);
      if (cardTaskId != taskId) {
        continue;
      }
      if (cardData['reasoningItemCompleted'] == true) {
        return true;
      }
      final status = _string(cardData['status'])?.toLowerCase();
      if (status == 'success' ||
          status == 'completed' ||
          status == 'complete') {
        return true;
      }
    }
    return false;
  }

  void _markToolCardComplete(
    ChatConversationRuntimeState runtime,
    String cardId,
  ) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    if (index == -1) return;
    final existing = runtime.messages[index];
    final cardData = Map<String, dynamic>.from(existing.cardData ?? const {});
    final currentStatus = _string(cardData['status'])?.toLowerCase();
    if (currentStatus == 'error' ||
        currentStatus == 'timeout' ||
        currentStatus == 'interrupted' ||
        currentStatus == 'cancelled' ||
        currentStatus == 'canceled') {
      return;
    }
    cardData['status'] = 'success';
    final parentTaskId =
        _string(cardData['taskId']) ??
        _string(existing.streamMeta?['parentTaskId']);
    runtime.messages[index] = existing.copyWith(
      content: {'cardData': cardData, 'id': cardId},
      streamMeta: parentTaskId == null
          ? existing.streamMeta
          : _streamMeta(
              runtime,
              parentTaskId: parentTaskId,
              entryId: cardId,
              kind: 'tool_completed',
              isFinal: true,
              existingMessage: existing,
            ),
    );
  }

  String _assistantTextForEntry(
    ChatConversationRuntimeState runtime,
    String messageId,
  ) {
    final runtimeText = runtime.currentAiMessages[messageId];
    if (runtimeText != null) {
      return runtimeText;
    }
    final index = runtime.messages.indexWhere(
      (message) => message.id == messageId,
    );
    return index == -1 ? '' : runtime.messages[index].text ?? '';
  }

  void _markAssistantEntryFinal(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
    String messageId,
  ) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == messageId,
    );
    if (index == -1) return;
    final existing = runtime.messages[index];
    runtime.messages[index] = existing.copyWith(
      isLoading: false,
      isError: false,
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: parentTaskId,
        entryId: messageId,
        kind: 'text_snapshot',
        isFinal: true,
        existingMessage: existing,
      ),
    );
  }

  void _markAssistantMessagesFinalForTask(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
  ) {
    for (var index = 0; index < runtime.messages.length; index += 1) {
      final message = runtime.messages[index];
      if (message.type != 1 || message.user != 2) {
        continue;
      }
      if (_string(message.streamMeta?['parentTaskId']) != parentTaskId) {
        continue;
      }
      runtime.messages[index] = message.copyWith(
        isLoading: false,
        isError: false,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: message.id,
          kind: 'text_snapshot',
          isFinal: true,
          existingMessage: message,
        ),
      );
    }
  }

  void _finalizeThinkingCard(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
    String cardId,
  ) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    if (index == -1) return;
    final existing = runtime.messages[index];
    final existingCardData = existing.cardData;
    if (existingCardData?['type'] != 'deep_thinking') return;
    final cardData = Map<String, dynamic>.from(existingCardData!);
    final startTime =
        _asInt(cardData['startTime']) ??
        _startTimeForEntry(runtime, cardId, existingMessage: existing);
    cardData['isLoading'] = false;
    cardData['stage'] = ThinkingStage.complete.value;
    cardData['taskID'] = parentTaskId;
    cardData['runId'] = parentTaskId;
    cardData['cardId'] = cardId;
    cardData['startTime'] = startTime;
    cardData['endTime'] ??= DateTime.now().millisecondsSinceEpoch;
    cardData['isCollapsible'] = true;
    cardData['thinkingContent'] = (cardData['thinkingContent'] ?? '')
        .toString();
    runtime.messages[index] = existing.copyWith(
      content: {'cardData': cardData, 'id': cardId},
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: parentTaskId,
        entryId: cardId,
        kind: 'thinking_snapshot',
        isFinal: true,
        existingMessage: existing,
      ),
    );
  }

  void _markThinkingItemCompleted(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
    String cardId,
  ) {
    cardId = _thinkingCardIdForTask(
      runtime,
      parentTaskId: parentTaskId,
      requestedCardId: cardId,
    );
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    if (index == -1) return;
    final existing = runtime.messages[index];
    final existingCardData = existing.cardData;
    if (existingCardData?['type'] != 'deep_thinking') return;
    final cardData = Map<String, dynamic>.from(existingCardData!);
    cardData['reasoningItemCompleted'] = true;
    runtime.messages[index] = existing.copyWith(
      content: {'cardData': cardData, 'id': cardId},
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: parentTaskId,
        entryId: cardId,
        kind: 'thinking_snapshot',
        existingMessage: existing,
      ),
    );
  }

  void _cancelThinkingCard(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
    String cardId,
  ) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    if (index == -1) return;
    final existing = runtime.messages[index];
    final existingCardData = existing.cardData;
    if (existingCardData?['type'] != 'deep_thinking') return;
    final cardData = Map<String, dynamic>.from(existingCardData!);
    final startTime =
        _asInt(cardData['startTime']) ??
        _startTimeForEntry(runtime, cardId, existingMessage: existing);
    cardData['isLoading'] = false;
    cardData['stage'] = ThinkingStage.cancelled.value;
    cardData['taskID'] = parentTaskId;
    cardData['runId'] = parentTaskId;
    cardData['cardId'] = cardId;
    cardData['startTime'] = startTime;
    cardData['endTime'] ??= DateTime.now().millisecondsSinceEpoch;
    cardData['isCollapsible'] = false;
    cardData['thinkingContent'] = (cardData['thinkingContent'] ?? '')
        .toString();
    runtime.messages[index] = existing.copyWith(
      content: {'cardData': cardData, 'id': cardId},
      streamMeta: _streamMeta(
        runtime,
        parentTaskId: parentTaskId,
        entryId: cardId,
        kind: 'thinking_snapshot',
        isFinal: true,
        existingMessage: existing,
      ),
    );
  }

  void _finalizeThinkingCardsForTask(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
  ) {
    final cardIds = runtime.messages
        .where((message) {
          final cardData = message.cardData;
          if (cardData?['type'] != 'deep_thinking') {
            return false;
          }
          final cardTaskId =
              _string(cardData?['taskID']) ??
              _string(message.streamMeta?['parentTaskId']);
          return cardTaskId == parentTaskId;
        })
        .map((message) => message.id)
        .toList(growable: false);
    for (final cardId in cardIds) {
      _finalizeThinkingCard(runtime, parentTaskId, cardId);
    }
  }

  void _finalizeActiveThinkingCardForTask(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
  ) {
    final cardId = runtime.activeThinkingCardId;
    if (cardId == null) {
      return;
    }
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    if (index == -1) {
      runtime.activeThinkingCardId = null;
      return;
    }
    final message = runtime.messages[index];
    final cardTaskId =
        _string(message.cardData?['taskID']) ??
        _string(message.streamMeta?['parentTaskId']);
    if (cardTaskId != parentTaskId) {
      return;
    }
    _finalizeThinkingCard(runtime, parentTaskId, cardId);
    runtime.activeThinkingCardId = null;
    runtime.currentThinkingMessages.remove(parentTaskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
  }

  void _cancelThinkingCardsForTask(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
  ) {
    final cardIds = runtime.messages
        .where((message) {
          final cardData = message.cardData;
          if (cardData?['type'] != 'deep_thinking') {
            return false;
          }
          final cardTaskId =
              _string(cardData?['taskID']) ??
              _string(message.streamMeta?['parentTaskId']);
          return cardTaskId == parentTaskId;
        })
        .map((message) => message.id)
        .toList(growable: false);
    for (final cardId in cardIds) {
      _cancelThinkingCard(runtime, parentTaskId, cardId);
    }
  }

  void _finalizeOtherLoadingThinkingCardsForTask(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String activeCardId,
  }) {
    final cardIds = runtime.messages
        .where((message) {
          if (message.id == activeCardId) {
            return false;
          }
          final cardData = message.cardData;
          if (cardData?['type'] != 'deep_thinking' ||
              cardData?['isLoading'] != true) {
            return false;
          }
          final cardTaskId =
              _string(cardData?['taskID']) ??
              _string(message.streamMeta?['parentTaskId']);
          return cardTaskId == parentTaskId;
        })
        .map((message) => message.id)
        .toList(growable: false);
    for (final cardId in cardIds) {
      _finalizeThinkingCard(runtime, parentTaskId, cardId);
    }
  }

  String? _activeThinkingSegmentIndex(ChatConversationRuntimeState runtime) {
    final activeCardId = runtime.activeThinkingCardId;
    if (activeCardId == null) return null;
    final index = runtime.messages.indexWhere(
      (message) => message.id == activeCardId,
    );
    if (index == -1) return null;
    return _string(runtime.messages[index].cardData?['reasoningSegmentIndex']);
  }

  String _thinkingCardIdForTask(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String requestedCardId,
  }) {
    final activeCardId = runtime.activeThinkingCardId;
    if (activeCardId != null) {
      final activeIndex = runtime.messages.indexWhere(
        (message) => message.id == activeCardId,
      );
      if (activeIndex != -1 &&
          _thinkingCardBelongsToTask(
            runtime.messages[activeIndex],
            parentTaskId,
          )) {
        return activeCardId;
      }
    }

    final requestedIdExists = runtime.messages.any(
      (message) => message.id == requestedCardId,
    );
    if (!requestedIdExists) {
      return requestedCardId;
    }

    // Some ACP adapters omit reasoning messageId. The turn-scoped fallback
    // then repeats after every tool boundary, so allocate a deterministic
    // segment suffix instead of reopening the completed card.
    var segmentIndex = 2;
    while (runtime.messages.any(
      (message) => message.id == '$requestedCardId-segment-$segmentIndex',
    )) {
      segmentIndex += 1;
    }
    return '$requestedCardId-segment-$segmentIndex';
  }

  bool _thinkingCardBelongsToTask(
    ChatMessageModel message,
    String parentTaskId,
  ) {
    if (message.cardData?['type'] != 'deep_thinking') {
      return false;
    }
    final cardTaskId =
        _string(message.cardData?['taskID']) ??
        _string(message.streamMeta?['parentTaskId']);
    return cardTaskId == parentTaskId;
  }

  Map<String, dynamic>? _toolCardData(
    ChatConversationRuntimeState runtime,
    String cardId,
  ) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == cardId,
    );
    if (index == -1) {
      return null;
    }
    final cardData = runtime.messages[index].cardData;
    if (cardData?['type'] != 'agent_tool_summary') {
      return null;
    }
    return cardData;
  }

  Map<String, dynamic> _mergeAgentToolUpdate(
    Map<String, dynamic>? existingCardData,
    Map<String, dynamic> incoming,
  ) {
    final existingRaw = _decodeJsonValue(
      (existingCardData?['rawResultJson'] ?? '').toString(),
    );
    final existingMap = _asStringMap(existingRaw);
    if (existingMap == null || existingMap.isEmpty) {
      return Map<String, dynamic>.from(incoming);
    }
    final merged = Map<String, dynamic>.from(existingMap);
    final existingStatus =
        _string(existingCardData?['status']) ??
        normalizeAgentToolStatus(existingMap, fallbackStatus: 'running');
    final incomingStatus = normalizeAgentToolStatus(
      incoming,
      fallbackStatus: 'running',
    );
    final keepsTerminalState =
        _isTerminalAgentToolStatus(existingStatus) &&
        !_isTerminalAgentToolStatus(incomingStatus);
    for (final entry in incoming.entries) {
      // ACP tool_call_update is a sparse patch. LocalAcpRuntime keeps absent
      // fields as explicit nulls while projecting it to a Map, so a shallow
      // spread would erase kind/title/input/content from the initial call.
      // A reconnect may replay an older running update after the completed
      // update. A tool lifecycle is monotonic in the UI: terminal cards keep
      // their terminal state while still accepting any newly supplied facts.
      final preservesSpecificType =
          entry.key == 'type' &&
          entry.value == 'tool' &&
          existingMap['type'] != null &&
          existingMap['type'] != 'tool';
      if (entry.value != null &&
          !preservesSpecificType &&
          (!keepsTerminalState ||
              (entry.key != 'status' && entry.key != 'state'))) {
        merged[entry.key] = entry.value;
      }
    }
    return merged;
  }

  bool _isTerminalAgentToolStatus(String status) {
    return const <String>{
      'success',
      'error',
      'timeout',
      'interrupted',
    }.contains(status.trim().toLowerCase());
  }

  bool _isActiveAgentToolStatus(String status) {
    return const <String>{
      'running',
      'pending',
      'progress',
    }.contains(status.trim().toLowerCase());
  }

  String? _findToolCardIdForCallId(
    ChatConversationRuntimeState runtime,
    String callId, {
    required String taskId,
    String? sessionId,
  }) {
    final normalizedCallId = callId.trim();
    if (normalizedCallId.isEmpty) {
      return null;
    }
    final normalizedTaskId = taskId.trim();
    final normalizedSessionId = sessionId?.trim() ?? '';
    // ACP defines toolCallId as unique within a session. Prefer the explicit
    // identity fields before inspecting legacy JSON payloads. The task check
    // also protects the UI when a non-conforming provider reuses an id in a
    // later turn.
    for (final message in runtime.messages) {
      final cardData = message.cardData;
      if (cardData == null ||
          (cardData['type'] != 'agent_tool_summary' &&
              cardData['type'] != kAgentRequestCardType) ||
          !_cardBelongsToTask(cardData!, normalizedTaskId)) {
        continue;
      }
      final cardToolCallId = _string(cardData['toolCallId'])?.trim();
      final cardSessionId = _string(cardData['sessionId'])?.trim() ?? '';
      if (cardToolCallId == normalizedCallId &&
          (normalizedSessionId.isEmpty ||
              cardSessionId.isEmpty ||
              cardSessionId == normalizedSessionId)) {
        return message.id;
      }
      final terminalSessionId = _string(cardData['terminalSessionId']);
      if (terminalSessionId == normalizedCallId &&
          (normalizedSessionId.isEmpty ||
              cardSessionId.isEmpty ||
              cardSessionId == normalizedSessionId)) {
        return message.id;
      }
    }
    for (final suffix in const <String>[
      'command',
      'file',
      'plan',
      'search',
      'workspace',
      'browser',
      'image',
      'tool',
    ]) {
      final baseCardId = '$normalizedCallId-agent-$suffix';
      final candidateIds = <String>[
        baseCardId,
        if (normalizedTaskId.isNotEmpty) '$normalizedTaskId-$baseCardId',
      ];
      for (final cardId in candidateIds) {
        final cardData = _toolCardData(runtime, cardId);
        if (cardData != null &&
            _cardBelongsToTask(cardData, normalizedTaskId)) {
          return cardId;
        }
      }
    }
    for (final message in runtime.messages) {
      final cardData = message.cardData;
      if (cardData?['type'] != 'agent_tool_summary') {
        continue;
      }
      if (_cardBelongsToTask(cardData!, normalizedTaskId) &&
          _toolCardContainsCallId(cardData, normalizedCallId)) {
        return message.id;
      }
    }
    return null;
  }

  /// Legacy events may not carry a sessionId. Keep their compact ids when
  /// possible, but allocate a task-scoped id on collision. Official ACP
  /// cards use the session-scoped identity generated by [_toolCardBaseId].
  String _taskScopedCardId(
    ChatConversationRuntimeState runtime, {
    required String taskId,
    required String baseCardId,
  }) {
    final existing = _toolCardData(runtime, baseCardId);
    if (existing == null || _cardBelongsToTask(existing, taskId)) {
      return baseCardId;
    }
    return '${taskId.trim()}-$baseCardId';
  }

  String _toolCardBaseId({
    required Map<String, dynamic> raw,
    required String fallback,
    required String suffix,
  }) {
    final identity = AgentToolIdentity.fromMaps(raw: raw);
    return identity.cardId(suffix: suffix, fallback: fallback);
  }

  bool _cardBelongsToTask(Map<String, dynamic> cardData, String taskId) {
    final normalizedTaskId = taskId.trim();
    if (normalizedTaskId.isEmpty) {
      return false;
    }
    final cardTaskId = _firstString([cardData['taskId'], cardData['taskID']]);
    return cardTaskId?.trim() == normalizedTaskId;
  }

  bool _toolCardContainsCallId(Map<String, dynamic> cardData, String callId) {
    for (final key in const <String>[
      'rawResultJson',
      'resultPreviewJson',
      'argsJson',
    ]) {
      final text = (cardData[key] ?? '').toString().trim();
      if (text.isEmpty) {
        continue;
      }
      final decoded = _decodeJsonValue(text);
      if (_valueContainsCallId(decoded, callId)) {
        return true;
      }
    }
    return false;
  }

  bool _valueContainsCallId(dynamic value, String callId) {
    if (value == null) {
      return false;
    }
    if (value is String || value is num || value is bool) {
      return value.toString() == callId;
    }
    final map = _asStringMap(value);
    if (map != null) {
      if (_firstString([map['callId'], map['call_id'], map['id']]) == callId) {
        return true;
      }
      return map.values.any((nested) => _valueContainsCallId(nested, callId));
    }
    if (value is List) {
      return value.any((nested) => _valueContainsCallId(nested, callId));
    }
    return false;
  }

  dynamic _decodeJsonValue(String text) {
    try {
      return jsonDecode(text);
    } catch (_) {
      return null;
    }
  }

  String _stableAgentItemKey(Map<String, dynamic> item) {
    final stablePayload = <String, dynamic>{
      'type': item['type'],
      'name': item['name'],
      'namespace': item['namespace'],
      'arguments': item['arguments'],
      'action': item['action'],
      'execution': item['execution'],
      'query': item['query'],
      'output': item['output'],
      'status': item['status'],
    };
    return 'raw-${_stableTextHash(_safeJson(stablePayload))}';
  }

  String _stableTextHash(String value) {
    var hash = 0x811c9dc5;
    for (final codeUnit in value.codeUnits) {
      hash ^= codeUnit;
      hash = (hash * 0x01000193) & 0xffffffff;
    }
    return hash.toRadixString(16).padLeft(8, '0');
  }

  Map<String, dynamic> _streamMeta(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String entryId,
    required String kind,
    bool isFinal = false,
    ChatMessageModel? existingMessage,
  }) {
    final seq = _sequenceForEntry(
      runtime,
      entryId,
      existingMessage: existingMessage,
    );
    return ensureAgentStreamMessageMeta(
          existingMessage?.streamMeta,
          seq: seq,
          roundIndex: seq,
          kind: kind,
          runId: parentTaskId,
          sessionId: runtime.activeAcpSessionId,
          turnId: runtime.acpTurnIdForRun(parentTaskId),
          cardId: entryId,
          parentTaskId: parentTaskId,
          entryId: entryId,
          isFinal: isFinal,
        ) ??
        <String, dynamic>{};
  }

  int _sequenceForEntry(
    ChatConversationRuntimeState runtime,
    String entryId, {
    ChatMessageModel? existingMessage,
  }) {
    final key = entryId.trim();
    final cached = runtime.agentEntrySequences[key];
    if (cached != null) {
      return cached;
    }
    final existingSeq = _asInt(existingMessage?.streamMeta?['seq']);
    if (existingSeq != null && existingSeq > 0) {
      runtime.agentEntrySequences[key] = existingSeq;
      if (runtime.agentNextEntrySequence < existingSeq) {
        runtime.agentNextEntrySequence = existingSeq;
      }
      return existingSeq;
    }
    runtime.agentNextEntrySequence += 1;
    runtime.agentEntrySequences[key] = runtime.agentNextEntrySequence;
    return runtime.agentNextEntrySequence;
  }

  int _startTimeForEntry(
    ChatConversationRuntimeState runtime,
    String entryId, {
    ChatMessageModel? existingMessage,
  }) {
    final key = entryId.trim();
    final cached = runtime.agentEntryStartTimes[key];
    if (cached != null) {
      return cached;
    }
    final existingStart =
        _asInt(existingMessage?.cardData?['startTime']) ??
        existingMessage?.createAt.millisecondsSinceEpoch;
    final startTime = existingStart ?? DateTime.now().millisecondsSinceEpoch;
    runtime.agentEntryStartTimes[key] = startTime;
    return startTime;
  }

  bool _removeAgentDebugStatusCards(ChatConversationRuntimeState runtime) {
    final before = runtime.messages.length;
    runtime.messages.removeWhere((message) {
      final cardData = message.cardData;
      if (cardData == null) return false;
      final toolName = _string(cardData['toolName']);
      final title =
          _string(cardData['toolTitle']) ?? _string(cardData['displayName']);
      return canonicalAgentToolName(toolName) == 'agent.status' &&
          (title == 'codex/stderr' || title == 'codex/parseError');
    });
    return runtime.messages.length != before;
  }

  String? _standaloneProcessIdentity(Map<String, dynamic> params) {
    return _firstString([
      params['processId'],
      params['process_id'],
      params['processHandle'],
      params['process_handle'],
    ]);
  }

  String _standaloneProcessId(
    Map<String, dynamic> params, {
    required String method,
  }) {
    return _firstString([
          params['processId'],
          params['process_id'],
          params['processHandle'],
          params['process_handle'],
          params['id'],
        ]) ??
        method.replaceAll(RegExp(r'[^a-zA-Z0-9]+'), '-');
  }

  String _standaloneCommandTitle(
    Map<String, dynamic> params, {
    required String fallback,
  }) {
    final command =
        _commandTextFromValue(params['command']) ??
        _commandTextFromValue(_toolArguments(params)['command']) ??
        _commandTextFromValue(_asStringMap(params['action'])?['command']) ??
        _firstString([params['processId'], params['processHandle']]);
    if (command == null || command.trim().isEmpty) {
      return _compactTitle(fallback, maxLength: 48);
    }
    return _compactTitle(command, maxLength: 48);
  }

  String _standaloneProcessOutputDelta(Map<String, dynamic> params) {
    final decoded =
        _decodeBase64Output(params['deltaBase64']) ??
        _decodeBase64Output(params['delta_base64']) ??
        _extractText(params['delta']) ??
        _extractText(params['output']) ??
        _extractText(params['text']) ??
        '';
    final stream = _string(params['stream'])?.toLowerCase();
    if (decoded.isEmpty || stream == null || stream == 'stdout') {
      return decoded;
    }
    return _streamOutputBlock(decoded, stream: stream);
  }

  String _streamOutputBlock(dynamic value, {required String stream}) {
    final text = _extractText(value) ?? '';
    if (text.isEmpty) {
      return '';
    }
    final normalizedStream = stream.toLowerCase();
    if (normalizedStream == 'stdout') {
      return text;
    }
    final needsLeadingNewline = text.startsWith('\n') ? '' : '\n';
    final needsTrailingNewline = text.endsWith('\n') ? '' : '\n';
    return '$needsLeadingNewline[$normalizedStream]\n$text$needsTrailingNewline';
  }

  String _extractAgentRawOutputText(Map<String, dynamic> item) {
    final output = item['output'];
    final text =
        _extractText(output) ??
        _extractText(item['tools']) ??
        _extractText(item['result']) ??
        _extractText(item['content']) ??
        '';
    if (text.trim().isNotEmpty) {
      return text;
    }
    if (output != null) {
      return _safeJson(output);
    }
    return '';
  }

  String _approvalTitle(String method, Map<String, dynamic> params) {
    if (method.contains('commandExecution')) {
      return _commandTitle(params);
    }
    if (method.contains('fileChange')) {
      return _fileChangeTitle(params, fallback: 'Agent file approval');
    }
    final toolCall = _approvalToolCall(params);
    final providedTitle = _firstString([
      toolCall?['title'],
      toolCall?['name'],
      params['title'],
    ]);
    if (providedTitle != null && !_isGenericApprovalLabel(providedTitle)) {
      return _compactTitle(providedTitle, maxLength: 64);
    }
    final command = _approvalCommand(params);
    if (command != null) {
      return _compactTitle(command, maxLength: 48);
    }
    if (_approvalPath(params) != null) {
      return 'Modify file';
    }
    switch (_approvalKind(params)) {
      case 'execute':
      case 'command':
      case 'command_execution':
      case 'commandexecution':
        return 'Run command';
      case 'edit':
      case 'file_change':
      case 'filechange':
      case 'delete':
        return 'Modify files';
      case 'read':
        return 'Read project files';
      case 'search':
        return 'Search project';
      case 'mcp':
      case 'mcp_tool':
        return 'Use integration';
      default:
        return 'Continue agent action';
    }
  }

  String _approvalDetail(Map<String, dynamic> params) {
    final toolCall = _approvalToolCall(params);
    final input = _approvalInput(params);
    final parts = <String>[];
    final reason = _firstString([
      params['reason'],
      params['description'],
      params['message'],
      params['prompt'],
      toolCall?['reason'],
      toolCall?['description'],
      toolCall?['detail'],
      _extractText(toolCall?['content']),
      input['detail'],
    ]);
    if (reason != null && !_isGenericApprovalLabel(reason)) {
      parts.add(reason);
    }

    final command = _approvalCommand(params);
    if (command != null && !_containsApprovalDetail(parts, command)) {
      parts.add('Command: $command');
    }
    final path = _approvalPath(params);
    if (path != null && !_containsApprovalDetail(parts, path)) {
      parts.add('File: $path');
    }
    final toolName = _firstString([
      toolCall?['toolName'],
      toolCall?['tool_name'],
      toolCall?['name'],
      params['toolName'],
      params['tool_name'],
      input['toolName'],
      input['tool_name'],
      input['name'],
    ]);
    if (toolName != null && !_isGenericApprovalLabel(toolName)) {
      parts.add('Tool: $toolName');
    }
    if (parts.isEmpty) {
      return 'The agent is requesting permission to continue.';
    }
    return parts.join('\n');
  }

  Map<String, dynamic>? _approvalToolCall(Map<String, dynamic> params) {
    final request = _asStringMap(params['request']);
    return _asStringMap(params['toolCall']) ??
        _asStringMap(params['tool_call']) ??
        _asStringMap(request?['toolCall']) ??
        _asStringMap(request?['tool_call']);
  }

  Map<String, dynamic> _approvalInput(Map<String, dynamic> params) {
    final toolCall = _approvalToolCall(params);
    for (final value in <dynamic>[
      toolCall?['rawInput'],
      toolCall?['raw_input'],
      toolCall?['input'],
      params['rawInput'],
      params['raw_input'],
      params['input'],
    ]) {
      final map = _asStringMap(value);
      if (map != null) return map;
      final text = _string(value);
      if (text == null) continue;
      try {
        final decoded = jsonDecode(text);
        final decodedMap = _asStringMap(decoded);
        if (decodedMap != null) return decodedMap;
      } catch (_) {
        // Some Harnesses send a plain command string; it is handled by
        // [_approvalCommand] instead of being rendered as protocol JSON.
      }
    }
    return const <String, dynamic>{};
  }

  String? _approvalCommand(Map<String, dynamic> params) {
    final toolCall = _approvalToolCall(params);
    final input = _approvalInput(params);
    return _firstString([
      _approvalCommandValue(params['command']),
      _approvalCommandValue(params['cmd']),
      _approvalCommandValue(toolCall?['command']),
      _approvalCommandValue(toolCall?['cmd']),
      _approvalCommandValue(toolCall?['rawInput']),
      _approvalCommandValue(toolCall?['raw_input']),
      _approvalCommandValue(params['rawInput']),
      _approvalCommandValue(params['raw_input']),
      _approvalCommandValue(input['command']),
      _approvalCommandValue(input['cmd']),
      _approvalCommandValue(_toolArguments(params)['command']),
      _approvalCommandValue(_toolArguments(params)['cmd']),
    ]);
  }

  String? _approvalCommandValue(dynamic value) {
    final map = _asStringMap(value);
    if (map != null) {
      return _firstString([
        map['command'],
        map['cmd'],
        map['commandLine'],
        map['command_line'],
      ]);
    }
    return _commandTextFromValue(value);
  }

  String? _approvalPath(Map<String, dynamic> params) {
    final toolCall = _approvalToolCall(params);
    final input = _approvalInput(params);
    return _firstString([
      params['path'],
      params['filePath'],
      params['file_path'],
      params['filename'],
      params['fileName'],
      toolCall?['path'],
      toolCall?['filePath'],
      toolCall?['file_path'],
      toolCall?['filename'],
      toolCall?['fileName'],
      input['path'],
      input['filePath'],
      input['file_path'],
      input['filename'],
      input['fileName'],
    ]);
  }

  String? _approvalKind(Map<String, dynamic> params) {
    final toolCall = _approvalToolCall(params);
    return _firstString([
      toolCall?['kind'],
      params['kind'],
      params['type'],
    ])?.toLowerCase();
  }

  bool _containsApprovalDetail(List<String> parts, String value) {
    final normalized = value.trim().toLowerCase();
    return normalized.isNotEmpty &&
        parts.any((part) => part.toLowerCase().contains(normalized));
  }

  bool _isGenericApprovalLabel(String value) {
    final normalized = value.trim().toLowerCase().replaceAll('_', ' ');
    return normalized.isEmpty ||
        normalized == 'agent approval' ||
        normalized == 'permission required' ||
        normalized == 'request permission' ||
        normalized == 'approval requested' ||
        normalized == 'tool call' ||
        normalized == 'to call';
  }

  String _commandTitle(Map<String, dynamic> params) {
    final command =
        _commandTextFromValue(params['command']) ??
        _commandTextFromValue(_toolArguments(params)['command']) ??
        _commandTextFromValue(_asStringMap(params['item'])?['command']) ??
        _commandTextFromValue(_asStringMap(params['action'])?['command']) ??
        _commandTextFromValue(
          _asStringMap(_asStringMap(params['item'])?['action'])?['command'],
        ) ??
        _extractText(params['cmd']);
    if (command == null || command.trim().isEmpty) {
      return 'Agent command';
    }
    return _compactTitle(command, maxLength: 48);
  }

  String _fileChangeTitle(
    Map<String, dynamic> params, {
    String fallback = 'Agent file change',
  }) {
    final path = _resolveFilePath(params);
    if (path == null) {
      return fallback;
    }
    final name = _lastPathSegment(path) ?? path;
    return _compactTitle('Edit $name', maxLength: 42);
  }

  String? _resolveFilePath(Map<String, dynamic> params) {
    final args = _toolArguments(params);
    return _firstString([
          params['path'],
          params['filePath'],
          params['file_path'],
          params['filename'],
          params['fileName'],
          args['path'],
          args['filePath'],
          args['file_path'],
          args['filename'],
          args['fileName'],
          _firstPathFromList(params['files']),
          _firstPathFromList(params['changes']),
          _firstPathFromList(args['files']),
          _firstPathFromList(args['changes']),
          _asStringMap(params['item'])?['path'],
          _asStringMap(params['item'])?['filePath'],
          _asStringMap(params['item'])?['file_path'],
        ]) ??
        extractAgentDiffPath(params);
  }

  String _resolveFileDiffText({
    required Map<String, dynamic> existingCardData,
    required Map<String, dynamic> raw,
    required String terminalOutput,
    required String progress,
    required String summary,
  }) {
    final fromExisting = (existingCardData['diffText'] ?? '').toString();
    final fromCurrent = extractAgentDiffText(
      raw,
      outputText: terminalOutput,
      progress: progress,
      summary: summary,
    );
    if (fromCurrent != null && fromCurrent.trim().isNotEmpty) {
      return fromCurrent;
    }
    return fromExisting.trim().isEmpty ? '' : fromExisting;
  }

  Map<String, dynamic> _toolArguments(Map<String, dynamic> params) {
    for (final key in const <String>['arguments', 'args', 'input']) {
      final map = _asStringMap(params[key]);
      if (map != null) {
        return map;
      }
      final text = _string(params[key]);
      if (text == null || text.isEmpty) {
        continue;
      }
      try {
        final decoded = jsonDecode(text);
        if (decoded is Map) {
          return decoded.map((key, value) => MapEntry(key.toString(), value));
        }
      } catch (_) {
        continue;
      }
    }
    final item = _asStringMap(params['item']);
    if (item != null && item != params) {
      return _toolArguments(item);
    }
    return const <String, dynamic>{};
  }

  String? _firstPathFromList(dynamic value) {
    if (value is! List) {
      return null;
    }
    for (final item in value) {
      if (item is String && item.trim().isNotEmpty) {
        return item.trim();
      }
      final map = _asStringMap(item);
      final path = _firstString([
        map?['path'],
        map?['filePath'],
        map?['file_path'],
        map?['filename'],
        map?['fileName'],
      ]);
      if (path != null) {
        return path;
      }
    }
    return null;
  }

  String? _lastPathSegment(String path) {
    final normalized = path.trim().replaceAll(RegExp(r'[/\\]+$'), '');
    if (normalized.isEmpty) {
      return null;
    }
    final parts = normalized
        .split(RegExp(r'[/\\]+'))
        .where((part) => part.isNotEmpty)
        .toList(growable: false);
    return parts.isEmpty ? normalized : parts.last;
  }

  String _compactTitle(String value, {required int maxLength}) {
    final normalized = value
        .trim()
        .split('\n')
        .first
        .trim()
        .replaceAll(RegExp(r'\s+'), ' ');
    if (normalized.length <= maxLength) {
      return normalized;
    }
    return '${normalized.substring(0, maxLength)}...';
  }

  bool _isGenericAgentInputTitle(String? value) {
    final normalized = value?.trim().toLowerCase() ?? '';
    return normalized.isEmpty ||
        (normalized.contains('agent') &&
            (normalized.contains('input') ||
                normalized.contains('question'))) ||
        (normalized.contains('需要') && normalized.contains('输入'));
  }

  _AgentQuestion _firstQuestion(Map<String, dynamic> params) {
    final questions = params['questions'];
    final schemaQuestion = _elicitationSchemaQuestion(params);
    if (questions is List && questions.isNotEmpty) {
      final first = _asStringMap(questions.first);
      if (first != null) {
        final id =
            _string(first['id']) ?? _string(first['questionId']) ?? 'answer';
        final requestedTitle =
            _string(first['label']) ??
            _string(first['title']) ??
            _string(first['question']) ??
            'Agent needs input';
        final requestedDetail =
            _string(first['description']) ??
            _string(first['placeholder']) ??
            requestedTitle;
        final title =
            _isGenericAgentInputTitle(requestedTitle) && schemaQuestion != null
            ? schemaQuestion.title
            : requestedTitle;
        final detail =
            _isGenericAgentInputTitle(requestedTitle) && schemaQuestion != null
            ? schemaQuestion.detail
            : requestedDetail;
        return _AgentQuestion(id: id, title: title, detail: detail);
      }
    }
    final id =
        _string(params['questionId']) ?? _string(params['id']) ?? 'answer';
    final requestedTitle =
        _string(params['question']) ??
        _string(params['title']) ??
        'Agent needs input';
    final title =
        _isGenericAgentInputTitle(requestedTitle) && schemaQuestion != null
        ? schemaQuestion.title
        : requestedTitle;
    final detail =
        _isGenericAgentInputTitle(requestedTitle) && schemaQuestion != null
        ? schemaQuestion.detail
        : (_string(params['description']) ?? title);
    return _AgentQuestion(id: id, title: title, detail: detail);
  }

  _AgentQuestion? _elicitationSchemaQuestion(Map<String, dynamic> params) {
    final schema = _schemaMap(params);
    final properties = _asStringMap(schema?['properties']);
    if (properties == null || properties.isEmpty) {
      return null;
    }
    final firstEntry = properties.entries.first;
    final field = _asStringMap(firstEntry.value);
    if (field == null) {
      return null;
    }
    final title = _firstString([
      field['title'],
      field['label'],
      firstEntry.key,
    ]);
    final detail = _firstString([field['description'], field['placeholder']]);
    final rawChoices = field['oneOf'] ?? field['enum'];
    final choices = rawChoices is List
        ? rawChoices
              .map(
                (value) => _firstString([
                  _asStringMap(value)?['title'],
                  _asStringMap(value)?['label'],
                  _asStringMap(value)?['const'],
                  value,
                ]),
              )
              .whereType<String>()
              .toList(growable: false)
        : const <String>[];
    if (title == null && detail == null) {
      return null;
    }
    return _AgentQuestion(
      id: firstEntry.key,
      title: title ?? 'Agent needs input',
      detail:
          [
            if (detail != null) detail,
            if (choices.isNotEmpty) '可选：${choices.join('、')}',
          ].join('\n').trim().isEmpty
          ? title ?? 'Agent needs input'
          : [
              if (detail != null) detail,
              if (choices.isNotEmpty) '可选：${choices.join('、')}',
            ].join('\n'),
    );
  }

  Map<String, dynamic>? _schemaMap(Map<String, dynamic> params) {
    for (final key in const <String>[
      'requestedSchema',
      'requested_schema',
      'schema',
      'inputSchema',
      'input_schema',
    ]) {
      final value = params[key];
      final map = _asStringMap(value) ?? _decodeJsonMap(value);
      if (map != null) return map;
    }
    for (final key in const <String>['request', 'elicitation', 'params']) {
      final nested = _asStringMap(params[key]) ?? _decodeJsonMap(params[key]);
      if (nested == null) continue;
      final schema = _schemaMap(nested);
      if (schema != null) return schema;
    }
    return params['properties'] is Map ? params : null;
  }

  Map<String, dynamic>? _decodeJsonMap(dynamic value) {
    if (value is! String) return null;
    try {
      final decoded = jsonDecode(value);
      return _asStringMap(decoded);
    } catch (_) {
      return null;
    }
  }
}

class _AgentQuestion {
  const _AgentQuestion({
    required this.id,
    required this.title,
    required this.detail,
  });

  final String id;
  final String title;
  final String detail;
}

const Set<String> _renderableAcpIncrementTypes = <String>{
  'tool_call_content_chunk',
  'terminal_output_chunk',
  'terminal_update',
};

bool _isRenderableAcpRawUpdate(Map<String, dynamic> update) {
  final sessionUpdate = _string(update['sessionUpdate']);
  if (_renderableAcpIncrementTypes.contains(sessionUpdate)) {
    return true;
  }
  final raw = _asStringMap(update['rawUpdate']);
  final rawType = _string(
    raw?['sessionUpdate'] ?? raw?['type'] ?? raw?['kind'] ?? raw?['updateType'],
  );
  return _renderableAcpIncrementTypes.contains(rawType);
}

Map<String, dynamic> _renderableAcpParams(Map<String, dynamic> params) {
  final update = _asStringMap(params['update']);
  if (update == null || !_isRenderableAcpRawUpdate(update)) {
    return params;
  }
  final raw = _asStringMap(update['rawUpdate']);
  if (raw == null) {
    return params;
  }
  final rawType = _string(
    raw['sessionUpdate'] ?? raw['type'] ?? raw['kind'] ?? raw['updateType'],
  );
  final updateType = _string(update['sessionUpdate']);
  final effectiveType = _renderableAcpIncrementTypes.contains(updateType)
      ? updateType
      : rawType;
  return <String, dynamic>{
    ...params,
    'update': <String, dynamic>{
      ...raw,
      ...update,
      if (effectiveType != null) 'sessionUpdate': effectiveType,
      'rawUpdate': update['rawUpdate'],
    },
  };
}

Map<String, dynamic>? _projectAcpSessionUpdate({
  required Map<String, dynamic> event,
  required Map<String, dynamic> params,
}) {
  final update = _asStringMap(params['update']);
  if (update == null) return null;
  final message = _asStringMap(event['message']);
  final sessionId = _firstString([
    event['sessionId'],
    event['session_id'],
    message?['sessionId'],
    message?['session_id'],
    message?['threadId'],
    message?['thread_id'],
    params['sessionId'],
    params['session_id'],
    event['threadId'],
    event['thread_id'],
    params['threadId'],
    params['thread_id'],
    update['sessionId'],
    update['session_id'],
  ]);
  final turnId = _firstString([
    event['turnId'],
    event['turn_id'],
    message?['turnId'],
    message?['turn_id'],
    message?['taskId'],
    message?['task_id'],
    message?['runId'],
    message?['run_id'],
    params['turnId'],
    params['turn_id'],
    update['turnId'],
    update['turn_id'],
    update['taskId'],
    update['task_id'],
  ]);
  final sessionUpdate = _string(update['sessionUpdate']);
  if (sessionUpdate == null || sessionUpdate.isEmpty) return null;

  // ACP messageId identifies a message within an ACP session, not an entry
  // in the host conversation. DeepSeek Harness currently reuses values such
  // as `1:1` on later prompts, so using it directly makes the reducer append a
  // new answer to the first answer in the timeline. Scope projected entries
  // by the host-owned turn while keeping all chunks from the same turn
  // together.
  String? turnScopedEntryId(Object? rawId) {
    final id = _string(rawId)?.trim();
    if (id == null || id.isEmpty) return null;
    final owner = turnId?.trim();
    if (owner == null || owner.isEmpty) return id;
    return '$owner-$id';
  }

  final messageIdentity = _firstString([
    update['messageId'],
    update['message_id'],
    update['itemId'],
    update['item_id'],
    update['contentId'],
    update['content_id'],
  ]);
  final scopedMessageId = turnScopedEntryId(messageIdentity) ?? turnId;
  final scopedEntryId = turnScopedEntryId(
    update['entryId'] ?? update['entry_id'],
  );
  final presentation = _acpPresentationMeta(update);

  // ACP messageId is only a message identity. Some Harnesses keep one
  // reasoning message open across tool boundaries and expose the actual
  // visible segment in _meta. Preserve that segment identity at the shared
  // projection seam so the timeline remains reasoning -> tool -> reasoning
  // even when the upstream messageId is reused.
  final reasoningSegmentIndex = _acpReasoningSegmentIndex(presentation);
  final scopedReasoningMessageId =
      reasoningSegmentIndex == null || scopedMessageId == null
      ? scopedMessageId
      : '$scopedMessageId-reasoning-$reasoningSegmentIndex';
  final scopedReasoningEntryId =
      reasoningSegmentIndex == null || scopedEntryId == null
      ? scopedEntryId
      : '$scopedEntryId-reasoning-$reasoningSegmentIndex';

  Map<String, dynamic> projectedParams(Map<String, dynamic> values) {
    return <String, dynamic>{
      ...values,
      if (sessionId != null) 'sessionId': sessionId,
      if (sessionId != null) 'threadId': sessionId,
      if (turnId != null) 'turnId': turnId,
      if (acpEventAllowsImplicitTurnAdmission(event))
        'allowImplicitTurnAdmission': true,
    };
  }

  switch (sessionUpdate) {
    case 'agent_message_chunk':
      final presentationMedia = _acpPresentationMedia(presentation);
      final presentationArtifacts = _acpPresentationArtifacts(presentation);
      return <String, dynamic>{
        'method': 'item/agentMessage/delta',
        'params': projectedParams(<String, dynamic>{
          // DSH may omit messageId, and when present it is only session
          // scoped. Both forms need a turn-scoped host entry id.
          'itemId': scopedMessageId,
          if (scopedEntryId != null) 'entryId': scopedEntryId,
          // ACP ContentBlock.Text is a map (`{type: text, text: ...}`). The
          // generic extractor trims map values because it is also used for
          // ids and statuses. Streaming text must instead preserve every
          // leading/trailing space and newline across chunk boundaries;
          // otherwise valid Markdown is glued into malformed headings and
          // code fences.
          'delta': _extractStreamingText(update['content']) ?? '',
          if (_mergeAcpMedia(
            _acpAssistantMedia(update['content']),
            presentationMedia,
          ).isNotEmpty)
            'acpAssistantMedia': _mergeAcpMedia(
              _acpAssistantMedia(update['content']),
              presentationMedia,
            ),
          if (_mergeAcpArtifacts(
            _acpAssistantArtifacts(update['content']),
            presentationArtifacts,
          ).isNotEmpty)
            'acpAssistantArtifacts': _mergeAcpArtifacts(
              _acpAssistantArtifacts(update['content']),
              presentationArtifacts,
            ),
          if (presentation != null) 'acpPresentation': presentation,
        }),
      };
    case 'agent_thought_chunk':
      return <String, dynamic>{
        'method': 'item/reasoning/delta',
        'params': projectedParams(<String, dynamic>{
          'itemId': scopedReasoningMessageId,
          if (scopedReasoningEntryId != null) 'entryId': scopedReasoningEntryId,
          'delta': _acpReasoningText(update, presentation),
          if (presentation != null) 'acpPresentation': presentation,
        }),
      };
    case 'user_message_chunk':
      // ACP session/load replays and live turn echoes share one projection
      // seam. A live echo is safe only with an official turn identity; the
      // reducer then deduplicates it against the host's optimistic message.
      final isReplay =
          update['replay'] == true ||
          params['replay'] == true ||
          event['replay'] == true;
      if (!isReplay && turnId == null) return null;
      return <String, dynamic>{
        'method': 'item/userMessage/delta',
        'params': projectedParams(<String, dynamic>{
          'itemId': scopedMessageId,
          if (scopedEntryId != null) 'entryId': scopedEntryId,
          'delta': _extractStreamingText(update['content']) ?? '',
          'replay': isReplay,
        }),
      };
    case 'tool_call':
      return <String, dynamic>{
        'method': 'item/started',
        'params': projectedParams(<String, dynamic>{
          'item': _projectAcpToolCall(
            update,
            sessionId: sessionId,
            turnId: turnId,
          ),
        }),
      };
    case 'tool_call_update':
      final item = _projectAcpToolCall(
        update,
        sessionId: sessionId,
        turnId: turnId,
      );
      final status = _string(item['status'])?.toLowerCase();
      return <String, dynamic>{
        'method': _isTerminalAcpToolStatus(status)
            ? 'item/completed'
            : 'item/updated',
        'params': projectedParams(<String, dynamic>{'item': item}),
      };
    case 'tool_call_content_chunk':
      return <String, dynamic>{
        'method': 'item/tool/contentDelta',
        'params': projectedParams(<String, dynamic>{
          'toolCallId': _firstString([
            update['toolCallId'],
            update['tool_call_id'],
            update['callId'],
            update['call_id'],
          ]),
          'content': update['content'] ?? update['chunk'] ?? update['data'],
          'rawUpdate': update,
        }),
      };
    case 'terminal_output_chunk':
      return <String, dynamic>{
        'method': 'item/commandExecution/outputDelta',
        'params': projectedParams(<String, dynamic>{
          'itemId': _firstString([
            update['toolCallId'],
            update['tool_call_id'],
            update['callId'],
            update['call_id'],
            update['terminalId'],
            update['terminal_id'],
          ]),
          'terminalId': update['terminalId'] ?? update['terminal_id'],
          'terminalSessionId': update['terminalId'] ?? update['terminal_id'],
          'delta': _acpTerminalOutputDelta(update),
          'rawUpdate': update,
        }),
      };
    case 'terminal_update':
      final terminalDelta = _acpTerminalOutputDelta(update);
      final terminalStatus = _string(
        update['status'] ?? update['state'] ?? update['exitStatus'],
      );
      if (terminalDelta.isNotEmpty) {
        return <String, dynamic>{
          'method': 'item/commandExecution/outputDelta',
          'params': projectedParams(<String, dynamic>{
            'itemId': _firstString([
              update['toolCallId'],
              update['tool_call_id'],
              update['callId'],
              update['call_id'],
              update['terminalId'],
              update['terminal_id'],
            ]),
            'terminalId': update['terminalId'] ?? update['terminal_id'],
            'terminalSessionId': update['terminalId'] ?? update['terminal_id'],
            'delta': terminalDelta,
            'rawUpdate': update,
          }),
        };
      }
      return <String, dynamic>{
        'method': 'item/updated',
        'params': projectedParams(<String, dynamic>{
          'item': <String, dynamic>{
            'id': _firstString([
              update['toolCallId'],
              update['tool_call_id'],
              update['callId'],
              update['call_id'],
              update['terminalId'],
              update['terminal_id'],
            ]),
            'type': 'commandExecution',
            if (terminalStatus != null) 'status': terminalStatus,
            if (update['terminalId'] != null)
              'terminalSessionId': update['terminalId'],
            'rawUpdate': update,
          },
        }),
      };
    case 'plan':
      final entries = (update['entries'] as List?)
          ?.whereType<Map>()
          .map((entry) => Map<String, dynamic>.from(entry))
          .toList();
      return <String, dynamic>{
        'method': 'turn/plan/updated',
        'params': projectedParams(<String, dynamic>{
          'entries': entries ?? const <Map<String, dynamic>>[],
          'plan':
              entries
                  ?.map(
                    (entry) =>
                        '- [${entry['status'] ?? 'pending'}] ${entry['content'] ?? ''}',
                  )
                  .join('\n') ??
              '',
        }),
      };
    case 'plan_update':
      final plan = _asStringMap(update['plan']);
      final planType = _string(plan?['type'])?.toLowerCase();
      final entries = (plan?['entries'] as List?)
          ?.whereType<Map>()
          .map((entry) => Map<String, dynamic>.from(entry))
          .toList();
      final planText = switch (planType) {
        'markdown' => _extractStreamingText(plan?['content']) ?? '',
        'file' => _string(plan?['uri']) ?? '',
        _ =>
          entries
                  ?.map(
                    (entry) =>
                        '- [${entry['status'] ?? 'pending'}] ${entry['content'] ?? ''}',
                  )
                  .join('\n') ??
              '',
      };
      return <String, dynamic>{
        'method': 'turn/plan/updated',
        'params': projectedParams(<String, dynamic>{
          if (_string(plan?['id']) != null) 'itemId': plan!['id'],
          if (_string(plan?['id']) != null) 'planId': plan!['id'],
          'entries': entries ?? const <Map<String, dynamic>>[],
          'plan': planText,
          'planData': plan,
        }),
      };
    case 'plan_removed':
      return <String, dynamic>{
        'method': 'turn/plan/removed',
        'params': projectedParams(<String, dynamic>{
          if (_string(update['id']) != null) 'itemId': update['id'],
          if (_string(update['id']) != null) 'planId': update['id'],
        }),
      };
    case 'current_mode_update':
      return <String, dynamic>{
        'method': 'thread/settings/updated',
        'params': projectedParams(<String, dynamic>{
          'collaborationMode': update['currentModeId'],
        }),
      };
    case 'config_option_update':
      // Configuration metadata is consumed directly by the ACP-facing
      // settings UI. It is not converted into an app-owned event name.
      return null;
    case 'session_info_update':
      return <String, dynamic>{
        'method': 'thread/name/updated',
        'params': projectedParams(<String, dynamic>{'name': update['title']}),
      };
    default:
      // Usage, commands, and future ACP update kinds do not affect the chat
      // cards yet. They remain valid ACP notifications and are safely ignored
      // by this ACP-to-UI projection.
      return null;
  }
}

bool _isTerminalAcpToolStatus(String? status) {
  switch (status?.trim().toLowerCase()) {
    case 'completed':
    case 'complete':
    case 'success':
    case 'succeeded':
    case 'failed':
    case 'error':
    case 'cancelled':
    case 'canceled':
    case 'interrupted':
    case 'aborted':
    case 'timeout':
    case 'timed_out':
      return true;
    default:
      return false;
  }
}

bool _isTerminalAgentEventMethod(String method) {
  return method == 'turn/completed' ||
      method == 'turn/failed' ||
      method == 'thread/closed' ||
      method == 'error';
}

Map<String, dynamic> _projectAcpToolCall(
  Map<String, dynamic> update, {
  String? sessionId,
  String? turnId,
}) {
  final permissionCard = _acpPermissionCard(update['rawOutput']);
  final standardContent = _acpStandardToolContent(update['content']);
  final presentation = _acpPresentationMeta(update);
  final presentationMedia = _acpPresentationMedia(presentation);
  final presentationArtifacts = _acpPresentationArtifacts(presentation);
  final standardMedia = _acpAssistantMedia(standardContent);
  final allMedia = _mergeAcpMedia(standardMedia, presentationMedia);
  final standardPresentation = _acpStandardToolPresentation(standardContent);
  final kind = _string(update['kind']);
  final officialToolType = _acpOfficialToolType(kind);
  // Keep a generic item type on sparse updates so the shared reducer still
  // recognizes the lifecycle event as a tool. _mergeAgentToolUpdate protects
  // a more specific type from being replaced by this fallback.
  final projectedType = _acpToolUiType(kind);
  final structuredOutput = _asStringMap(
    update['rawOutput'] is String
        ? _decodeAcpJsonValue(update['rawOutput'] as String)
        : update['rawOutput'],
  );
  // Xiaowan and a few ACP bridges carry progress metadata in rawInput on
  // `tool_call_update` (the official ACP shape has no dedicated extension
  // field). Promote the shared presentation fields from that envelope too;
  // otherwise subagent progress remains hidden in argsJson and the UI only
  // renders the parent tool without its child tasks.
  final structuredInput = _asStringMap(
    update['rawInput'] is String
        ? _decodeAcpJsonValue(update['rawInput'] as String)
        : update['rawInput'],
  );
  final plainRawOutput =
      update['rawOutput'] is String && structuredOutput == null
      ? (update['rawOutput'] as String).trim()
      : '';
  final structuredArtifacts = _asMapList(structuredOutput?['artifacts']);
  final standardArtifacts = _acpAssistantArtifacts(
    standardContent,
    includeEmbeddedResources: true,
  );
  final artifacts = _mergeAcpArtifacts(structuredArtifacts, standardArtifacts);
  final allArtifacts = _mergeAcpArtifacts(artifacts, presentationArtifacts);
  final firstMedia = allMedia.firstOrNull;
  final standardToolType = _string(standardPresentation['toolType']);
  return <String, dynamic>{
    'id': update['toolCallId'],
    'toolCallId': update['toolCallId'],
    if (sessionId != null) 'sessionId': sessionId,
    if (turnId != null) 'turnId': turnId,
    if (projectedType != null) 'type': projectedType,
    'title': update['title'],
    if (update['title'] != null) 'toolTitle': update['title'],
    'status': update['status'],
    'content': update['content'],
    'locations': update['locations'],
    'rawInput': update['rawInput'],
    'rawOutput': update['rawOutput'],
    if (plainRawOutput.isNotEmpty) ...<String, dynamic>{
      'summary': plainRawOutput,
      'progress': plainRawOutput,
    },
    ..._acpStructuredToolOutput(structuredInput),
    ..._acpStructuredToolOutput(structuredOutput),
    if (allArtifacts.isNotEmpty) 'artifacts': allArtifacts,
    if (standardContent.isNotEmpty) 'contentItems': standardContent,
    if (allMedia.isNotEmpty) 'media': allMedia,
    if (firstMedia?['imageDataUrl'] != null)
      'imageDataUrl': firstMedia!['imageDataUrl'],
    if (firstMedia?['imageUrl'] != null) 'imageUrl': firstMedia!['imageUrl'],
    if (firstMedia?['audioDataUrl'] != null)
      'audioDataUrl': firstMedia!['audioDataUrl'],
    if (firstMedia?['audioUrl'] != null) 'audioUrl': firstMedia!['audioUrl'],
    if (firstMedia?['mimeType'] != null) 'mimeType': firstMedia!['mimeType'],
    // Standard content is a concrete capability signal. It takes precedence
    // over generic adapter envelopes such as `toolType: context`. An
    // official ToolKind is the fallback when the content has no more specific
    // visual capability (for example, a read call with no content yet).
    ...standardPresentation,
    if (standardToolType == null && officialToolType != null)
      'toolType': officialToolType,
    if (permissionCard != null) 'permissionCard': permissionCard,
  };
}

List<Map<String, dynamic>> _acpStandardToolContent(Object? value) {
  return _acpContentItems(value);
}

List<Map<String, dynamic>> _acpContentItems(Object? value) {
  if (value is List) {
    return value
        .whereType<Map>()
        .map((item) => item.map((key, nested) => MapEntry('$key', nested)))
        .toList(growable: false);
  }
  final item = _asStringMap(value);
  return item == null
      ? const <Map<String, dynamic>>[]
      : <Map<String, dynamic>>[item];
}

String _acpTerminalOutputDelta(Map<String, dynamic> update) {
  final encoding = _string(
    update['encoding'] ?? update['dataEncoding'],
  )?.toLowerCase();
  if (encoding == 'base64') {
    return _decodeBase64Output(update['data'] ?? update['output']) ?? '';
  }
  final byteList = _decodeByteListOutput(update['bytes']);
  if (byteList != null) {
    return byteList;
  }
  return _extractStreamingText(
        update['output'] ??
            update['text'] ??
            update['delta'] ??
            update['data'] ??
            update['content'],
      ) ??
      '';
}

/// Converts ACP assistant image/resource blocks into the same image location
/// shape used by the existing shared tool card. The reducer then routes these
/// through the normal `agent_tool_summary` image card instead of introducing a
/// second assistant-media widget.
List<Map<String, dynamic>> _acpAssistantMedia(Object? value) {
  final media = <Map<String, dynamic>>[];

  void visit(Object? candidate) {
    if (candidate is List) {
      for (final item in candidate) {
        visit(item);
      }
      return;
    }
    final block = _asStringMap(candidate);
    if (block == null) return;
    final type = _string(block['type'])?.toLowerCase();
    if (type == 'content') {
      visit(block['content']);
      return;
    }
    if (type == 'resource') {
      visit(block['resource']);
      return;
    }
    // ACP extensions often put the already-normalized media location in
    // `_meta` rather than repeating an official ContentBlock. Accept both
    // forms so adapters can expose generated media without a private card
    // event or a second renderer.
    final normalizedMediaType = _string(block['mediaType'])?.toLowerCase();
    final normalizedImage =
        _string(block['imageDataUrl']) ?? _string(block['imageUrl']);
    final normalizedAudio =
        _string(block['audioDataUrl']) ?? _string(block['audioUrl']);
    if (normalizedImage != null && normalizedImage.trim().isNotEmpty) {
      media.add(<String, dynamic>{
        'mediaType': 'image',
        if (normalizedImage.startsWith('data:'))
          'imageDataUrl': normalizedImage
        else
          'imageUrl': normalizedImage,
        'mimeType': _string(block['mimeType']) ?? 'image/png',
        'title': _string(block['title']) ?? _string(block['name']) ?? '图片',
      });
      return;
    }
    if (normalizedAudio != null && normalizedAudio.trim().isNotEmpty) {
      media.add(<String, dynamic>{
        'mediaType': 'audio',
        if (normalizedAudio.startsWith('data:'))
          'audioDataUrl': normalizedAudio
        else
          'audioUrl': normalizedAudio,
        'mimeType': _string(block['mimeType']) ?? 'audio/mpeg',
        'title': _string(block['title']) ?? _string(block['name']) ?? '音频',
      });
      return;
    }
    final mimeType = _string(block['mimeType']) ?? _string(block['mime_type']);
    final isImage =
        type == 'image' ||
        normalizedMediaType == 'image' ||
        mimeType?.toLowerCase().startsWith('image/') == true;
    final isAudio =
        type == 'audio' ||
        normalizedMediaType == 'audio' ||
        mimeType?.toLowerCase().startsWith('audio/') == true;
    if (!isImage && !isAudio) return;
    final data = _string(block['data']) ?? _string(block['blob']);
    final uri = _string(block['uri']) ?? _string(block['url']);
    final mediaType = isAudio ? 'audio' : 'image';
    final location = data == null || data.isEmpty
        ? uri
        : data.startsWith('data:')
        ? data
        : 'data:${mimeType ?? (isAudio ? 'audio/mpeg' : 'image/png')};base64,$data';
    if (location == null || location.trim().isEmpty) return;
    media.add(<String, dynamic>{
      'mediaType': mediaType,
      if (isImage && location.startsWith('data:')) 'imageDataUrl': location,
      if (isImage && !location.startsWith('data:')) 'imageUrl': location,
      if (isAudio && location.startsWith('data:')) 'audioDataUrl': location,
      if (isAudio && !location.startsWith('data:')) 'audioUrl': location,
      'mimeType': mimeType ?? (isAudio ? 'audio/mpeg' : 'image/png'),
      'title':
          _string(block['title']) ??
          _string(block['name']) ??
          (isAudio ? '音频' : '图片'),
    });
  }

  visit(value);
  return media;
}

/// Projects non-image ACP resources into the existing artifact card.
///
/// This is shared by assistant messages and tool content. It is the bridge
/// from the official ACP content union back to the old Xiaowan artifact route:
/// links keep their URI, while embedded resources retain their text/blob when
/// a host-resolvable URI is present. Image resources remain on the image-card
/// route handled by [_acpAssistantMedia].
List<Map<String, dynamic>> _acpAssistantArtifacts(
  Object? value, {
  bool includeEmbeddedResources = false,
}) {
  final artifacts = <Map<String, dynamic>>[];

  void visit(Object? candidate) {
    if (candidate is List) {
      for (final item in candidate) {
        visit(item);
      }
      return;
    }
    final block = _asStringMap(candidate);
    if (block == null) return;
    final type = _string(block['type'])?.toLowerCase();
    if (type == 'content') {
      visit(block['content']);
      return;
    }
    if (type == 'resource') {
      if (!includeEmbeddedResources) return;
      final resource = _asStringMap(block['resource']);
      if (resource == null) return;
      final resourceMimeType =
          _string(resource['mimeType']) ?? _string(resource['mime_type']);
      if (resourceMimeType?.toLowerCase().startsWith('image/') == true) {
        return;
      }
      final uri = _string(resource['uri'])?.trim();
      // ArtifactCard resolves previews through the workspace resource
      // service. A raw ACP blob without a durable URI cannot safely be routed
      // there yet, so leave it in the preserved ACP content model instead of
      // creating a card that can never open.
      if (uri == null || uri.isEmpty) return;
      final text = _string(resource['text']);
      final blob = _string(resource['blob']);
      final title =
          _string(block['title']) ??
          _string(block['name']) ??
          _string(resource['name']) ??
          uri;
      artifacts.add(<String, dynamic>{
        'id': 'resource-${Uri.encodeComponent(uri)}',
        'title': title,
        if (_string(block['name']) != null) 'fileName': block['name'],
        'uri': uri,
        if (resourceMimeType != null) 'mimeType': resourceMimeType,
        if (resource['size'] != null) 'size': resource['size'],
        if (text != null) 'text': text,
        if (blob != null) 'blob': blob,
        if (resourceMimeType?.toLowerCase().startsWith('text/') == true)
          'previewKind': 'text',
      });
      return;
    }
    final uri = _string(block['uri'])?.trim();
    // `_meta` presentations commonly use a compact `{uri, title, mimeType}`
    // artifact object instead of an ACP `resource_link` content block. It is
    // still the same durable resource and should use the existing artifact
    // card route.
    if (type == null || type == 'artifact' || type == 'file') {
      if (uri == null || uri.isEmpty) return;
    } else if (type != 'resource_link') {
      return;
    }
    if (uri == null || uri.isEmpty) return;
    final mimeType = _string(block['mimeType']) ?? _string(block['mime_type']);
    if (mimeType?.toLowerCase().startsWith('image/') == true) return;
    final title =
        _string(block['title']) ??
        _string(block['name']) ??
        _string(block['description']) ??
        uri;
    artifacts.add(<String, dynamic>{
      'id': 'resource-${Uri.encodeComponent(uri)}',
      'title': title,
      if (_string(block['name']) != null) 'fileName': block['name'],
      'uri': uri,
      if (mimeType != null) 'mimeType': mimeType,
      if (block['size'] != null) 'size': block['size'],
      if (mimeType?.toLowerCase().startsWith('text/') == true)
        'previewKind': 'text',
    });
  }

  visit(value);
  return artifacts;
}

List<Map<String, dynamic>> _acpPresentationMedia(
  Map<String, dynamic>? presentation,
) {
  if (presentation == null) return const <Map<String, dynamic>>[];
  return _acpAssistantMedia(
    presentation['media'] ?? presentation['images'] ?? presentation['audio'],
  );
}

List<Map<String, dynamic>> _acpPresentationArtifacts(
  Map<String, dynamic>? presentation,
) {
  if (presentation == null) return const <Map<String, dynamic>>[];
  return _acpAssistantArtifacts(
    presentation['artifacts'] ??
        presentation['artifact'] ??
        presentation['resources'],
  );
}

List<Map<String, dynamic>> _mergeAcpMedia(
  List<Map<String, dynamic>> first,
  List<Map<String, dynamic>> second,
) {
  final merged = <Map<String, dynamic>>[...first];
  for (final candidate in second) {
    final candidateLocation = _firstString([
      candidate['imageDataUrl'],
      candidate['imageUrl'],
      candidate['audioDataUrl'],
      candidate['audioUrl'],
    ]);
    final duplicate =
        candidateLocation != null &&
        merged.any(
          (existing) =>
              _firstString([
                existing['imageDataUrl'],
                existing['imageUrl'],
                existing['audioDataUrl'],
                existing['audioUrl'],
              ]) ==
              candidateLocation,
        );
    if (!duplicate) merged.add(candidate);
  }
  return merged;
}

List<Map<String, dynamic>> _mergeAcpArtifacts(
  List<Map<String, dynamic>> first,
  List<Map<String, dynamic>> second,
) {
  final merged = <Map<String, dynamic>>[...first];
  for (final candidate in second) {
    final candidateId = _string(candidate['id']);
    final candidateUri = _string(candidate['uri']);
    final duplicate = merged.any((existing) {
      final existingId = _string(existing['id']);
      final existingUri = _string(existing['uri']);
      return (candidateId != null && candidateId == existingId) ||
          (candidateUri != null && candidateUri == existingUri);
    });
    if (!duplicate) merged.add(candidate);
  }
  return merged;
}

/// Derives visual hints from standard ACP tool content once, before all
/// Harnesses enter the existing shared card router.
Map<String, dynamic> _acpStandardToolPresentation(
  List<Map<String, dynamic>> contentItems,
) {
  String? imageDataUrl;
  String? audioDataUrl;
  String? audioUrl;
  String? audioMimeType;
  String? terminalSessionId;
  String? diffPath;
  var hasDiff = false;
  for (final media in _acpAssistantMedia(contentItems)) {
    final mediaType = _string(media['mediaType'])?.toLowerCase();
    if (mediaType == 'image') {
      imageDataUrl ??= _firstString([media['imageDataUrl'], media['imageUrl']]);
    } else if (mediaType == 'audio') {
      audioDataUrl ??= _string(media['audioDataUrl']);
      audioUrl ??= _string(media['audioUrl']);
      audioMimeType ??= _string(media['mimeType']);
    }
  }
  for (final item in contentItems) {
    final itemType = _string(item['type'])?.toLowerCase();
    if (itemType == 'diff') {
      hasDiff = true;
      diffPath ??= _firstString([
        item['path'],
        item['filePath'],
        item['file_path'],
      ]);
      continue;
    }
    if (itemType == 'terminal') {
      terminalSessionId ??= _string(item['terminalId']);
      continue;
    }
    if (itemType != 'content') continue;
    final block = _asStringMap(item['content']);
    final blockType = _string(block?['type'])?.toLowerCase();
    if (blockType == 'audio') {
      final data = _string(block?['data']);
      final mimeType = _string(block?['mimeType']) ?? 'audio/mpeg';
      final uri = _string(block?['uri']) ?? _string(block?['url']);
      if (data != null && data.isNotEmpty) {
        audioDataUrl ??= data.startsWith('data:')
            ? data
            : 'data:$mimeType;base64,$data';
      } else {
        audioUrl ??= uri;
      }
      audioMimeType ??= mimeType;
      continue;
    }
    if (blockType != 'image') continue;
    final data = _string(block?['data']);
    final mimeType = _string(block?['mimeType']) ?? 'image/png';
    imageDataUrl ??= data == null || data.isEmpty
        ? _string(block?['uri'])
        : 'data:$mimeType;base64,$data';
  }
  return <String, dynamic>{
    if (hasDiff) 'toolType': 'file',
    if (diffPath != null) 'filePath': diffPath,
    if (imageDataUrl != null && !hasDiff) 'toolType': 'image',
    if (imageDataUrl != null) 'imageDataUrl': imageDataUrl,
    if (audioDataUrl != null || audioUrl != null) 'toolType': 'audio',
    if (audioDataUrl != null) 'audioDataUrl': audioDataUrl,
    if (audioUrl != null) 'audioUrl': audioUrl,
    if (audioMimeType != null) 'mimeType': audioMimeType,
    if (terminalSessionId != null) 'terminalSessionId': terminalSessionId,
  };
}

Map<String, dynamic>? _acpPresentationMeta(Map<String, dynamic> update) {
  final projection = AcpExtensionRegistry.shared.project(update);
  return projection.presentation.isEmpty ? null : projection.presentation;
}

/// Reads the shared presentation metadata from an ACP session update,
/// regardless of which supported bridge envelope contains it.
Map<String, dynamic>? acpEventPresentation(Map<String, dynamic> event) {
  for (final envelope in _agentEnvelopeMaps(event)) {
    final update = _asStringMap(envelope['update']);
    if (update == null) continue;
    final presentation = _acpPresentationMeta(update);
    if (presentation != null) return presentation;
  }
  return null;
}

/// A Harness may report exact turn usage in a final empty message chunk after
/// `turn/completed`. This is metadata for the completed answer, not a delayed
/// text mutation, so the conversation fence may safely admit this one shape.
bool acpEventCarriesFinalTurnUsage(Map<String, dynamic> event) {
  for (final envelope in _agentEnvelopeMaps(event)) {
    final update = _asStringMap(envelope['update']);
    if (update == null ||
        _string(update['sessionUpdate']) != 'agent_message_chunk') {
      continue;
    }
    final presentation = _acpPresentationMeta(update);
    final usage = _asStringMap(presentation?['usage']);
    if (_asStringMap(usage?['turnUsage']) == null) continue;
    if ((_extractStreamingText(update['content']) ?? '').isEmpty) return true;
  }
  return false;
}

/// ACP's standard usage update is provider-neutral. Translate it once at the
/// shared reducer boundary, rather than making every Harness add a private UI
/// metadata event just to show context consumption.
Map<String, dynamic> _acpStandardUsage(Map<String, dynamic> update) {
  return <String, dynamic>{
    if (update['used'] != null) 'latestPromptTokens': update['used'],
    if (update['size'] != null) 'promptTokenThreshold': update['size'],
  };
}

List<Map<String, dynamic>> _acpAvailableCommands(Object? value) {
  if (value is! List) return const <Map<String, dynamic>>[];
  final commands = <Map<String, dynamic>>[];
  final seen = <String>{};
  for (final candidate in value) {
    final item = _asStringMap(candidate);
    if (item == null) continue;
    final name = _string(item['name'] ?? item['command'])?.trim();
    if (name == null || name.isEmpty) continue;
    final normalized = name.startsWith('/') ? name.substring(1) : name;
    if (normalized.isEmpty || !seen.add(normalized.toLowerCase())) continue;
    commands.add(<String, dynamic>{
      'name': normalized,
      'description': _string(item['description']) ?? '',
    });
  }
  return commands;
}

List<Map<String, dynamic>> _acpConfigOptions(Object? value) {
  if (value is! List) return const <Map<String, dynamic>>[];
  return value
      .map(_asStringMap)
      .whereType<Map<String, dynamic>>()
      .map((option) => Map<String, dynamic>.from(option))
      .where((option) {
        final id = _string(option['id'] ?? option['configId']);
        return id != null && id.isNotEmpty;
      })
      .toList(growable: false);
}

void _rememberAcpExtensionUpdate(
  ChatConversationRuntimeState runtime,
  Map<String, dynamic> update,
) {
  runtime.acpExtensionUpdates.add(Map<String, dynamic>.from(update));
}

/// Retain extension namespaces even when they do not have a Card projector
/// yet. This is the compatibility seam used by future Harness adapters: an
/// extension can be added without changing the transport or the chat page,
/// and the original namespace remains inspectable for replay/debugging.
void _rememberAcpExtensionMetadata(
  ChatConversationRuntimeState runtime,
  Map<String, dynamic> update,
) {
  final projection = AcpExtensionRegistry.shared.project(update);
  if (projection.extensions.isEmpty) return;
  _rememberAcpExtensionUpdate(runtime, <String, dynamic>{
    'sessionUpdate': update['sessionUpdate'],
    'extensions': projection.extensions,
  });
}

Map<String, dynamic> _acpReasoningCardData(Map<String, dynamic>? presentation) {
  final reasoning = _asStringMap(presentation?['reasoning']);
  if (reasoning == null) return const <String, dynamic>{};
  final taskTitle = _string(reasoning['taskTitle'] ?? reasoning['task_title']);
  final reasoningSummary = _extractText(
    reasoning['summary'] ?? reasoning['reasoningSummary'],
  )?.trim();
  final preparation = _string(reasoning['preparation']);
  final subTasks = _acpStringList(
    reasoning['subTasks'] ?? reasoning['sub_tasks'],
  );
  final memoryActions = _acpStringList(
    reasoning['memoryActions'] ?? reasoning['memory_actions'],
  );
  final stage = _acpThinkingStage(reasoning['stage'] ?? reasoning['phase']);
  return <String, dynamic>{
    if (taskTitle != null) 'taskTitle': taskTitle,
    if (subTasks.isNotEmpty) 'subTasks': subTasks,
    if (preparation != null) 'preparation': preparation,
    if (memoryActions.isNotEmpty) 'memoryActions': memoryActions,
    if (reasoningSummary != null && reasoningSummary.isNotEmpty)
      'reasoningSummary': reasoningSummary,
    if (stage != null) 'stage': stage,
  };
}

int? _acpThinkingStage(Object? value) {
  if (value is num) {
    final stage = value.toInt();
    return stage >= 1 && stage <= 5 ? stage : null;
  }
  final normalized = value?.toString().trim().toLowerCase();
  return switch (normalized) {
    'thinking' || 'analysis' || 'analyzing' || 'planning' => 1,
    'tool' || 'tool_call' || 'tool-call' || 'calling_tool' => 2,
    'executing' || 'execution' || 'running' => 3,
    'complete' || 'completed' || 'done' || 'finished' => 4,
    'cancelled' || 'canceled' || 'aborted' => 5,
    _ => null,
  };
}

String? _acpReasoningSegmentIndex(Map<String, dynamic>? presentation) {
  if (presentation == null || presentation.isEmpty) return null;
  final reasoning = _asStringMap(presentation['reasoning']);
  final value =
      reasoning?['segmentIndex'] ??
      reasoning?['segment_index'] ??
      presentation['reasoningSegmentIndex'] ??
      presentation['reasoning_segment_index'] ??
      presentation['segmentIndex'] ??
      presentation['segment_index'];
  return value?.toString().trim().isEmpty == true
      ? null
      : value?.toString().trim();
}

Map<String, dynamic> _preservedAcpReasoningCardData(
  Map<String, dynamic> cardData,
) {
  return <String, dynamic>{
    if (_string(cardData['taskTitle']) != null)
      'taskTitle': cardData['taskTitle'],
    if (cardData['subTasks'] is List) 'subTasks': cardData['subTasks'],
    if (_string(cardData['preparation']) != null)
      'preparation': cardData['preparation'],
    if (cardData['memoryActions'] is List)
      'memoryActions': cardData['memoryActions'],
  };
}

List<String> _acpStringList(Object? value) {
  if (value is List) {
    return value
        .map(_extractText)
        .whereType<String>()
        .map((item) => item.trim())
        .where((item) => item.isNotEmpty)
        .toList(growable: false);
  }
  final text = _extractText(value)?.trim();
  return text == null || text.isEmpty ? const <String>[] : <String>[text];
}

String _acpReasoningText(
  Map<String, dynamic> update,
  Map<String, dynamic>? presentation,
) {
  final fallback = _extractStreamingText(update['content']) ?? '';
  if (fallback.isNotEmpty) {
    return fallback;
  }
  final reasoning = _asStringMap(presentation?['reasoning']);
  if (reasoning == null) {
    return fallback;
  }
  final metadataText = _extractText(
    reasoning['text'] ??
        reasoning['content'] ??
        reasoning['message'] ??
        reasoning['summary'],
  )?.trim();
  final taskDescription = _extractText(
    reasoning['taskDescription'] ?? reasoning['task_description'],
  )?.trim();
  final taskTitle = _extractText(
    reasoning['taskTitle'] ?? reasoning['task_title'],
  )?.trim();
  final preparation = _extractText(reasoning['preparation'])?.trim();
  final subTasks = reasoning['subTasks'] ?? reasoning['sub_tasks'];
  final memoryActions =
      reasoning['memoryActions'] ?? reasoning['memory_actions'];
  final lines = <String>[];
  if (taskTitle != null && taskTitle.isNotEmpty) {
    lines.add(taskTitle);
  }
  if (taskDescription != null && taskDescription.isNotEmpty) {
    lines.add(taskDescription);
  }
  if (subTasks is List) {
    final items = subTasks
        .map(_extractText)
        .whereType<String>()
        .map((item) => item.trim())
        .where((item) => item.isNotEmpty)
        .toList(growable: false);
    if (items.isNotEmpty) {
      lines.add(items.map((item) => '- $item').join('\n'));
    }
  }
  if (preparation != null && preparation.isNotEmpty) {
    lines.add(preparation);
  }
  final memoryItems = _acpStringList(memoryActions);
  if (memoryItems.isNotEmpty) {
    lines.add('记忆：${memoryItems.join('、')}');
  }
  return lines.isEmpty
      ? (metadataText?.isNotEmpty == true ? metadataText! : fallback)
      : lines.join('\n\n');
}

/// ACP reserves [rawOutput] for adapter-specific tool results. Preserve the
/// common result vocabulary at the shared card seam instead of forcing every
/// Harness to duplicate a UI-specific event stream.
Map<String, dynamic> _acpStructuredToolOutput(Map<String, dynamic>? output) {
  if (output == null || output.isEmpty) {
    return const <String, dynamic>{};
  }
  final result =
      output['result'] ??
      output['resultPreview'] ??
      output['preview'] ??
      output['previewJson'];
  final projected = <String, dynamic>{
    // Keep adapter facts intact. The shared tool parser owns the distinction
    // between a transport envelope (such as ContextResult) and a visual
    // capability, so every Harness follows the same card-routing rule.
    if (output['toolType'] != null) 'toolType': output['toolType'],
    for (final key in const <String>[
      'toolName',
      'displayName',
      'serverName',
      'summary',
      'message',
      'question',
      'missingFields',
      'missing_fields',
      'missing',
      'progress',
      'terminalOutput',
      'terminalSessionId',
      'terminalStreamState',
      'workspaceId',
      'interruptedBy',
      'interruptionReason',
      'artifacts',
      'actions',
      'success',
      'exitCode',
      'error',
      'timedOut',
      'timed_out',
      'imageDataUrl',
      'dataUrl',
      'imageUrl',
      'audioDataUrl',
      'audioUrl',
      'mimeType',
      'previewJson',
      'rawResultJson',
      'outputTruncated',
      'originalChars',
      'headTail',
      'fullOutputArtifact',
      'subagentStatusText',
      'subagentEvents',
      'subagentEvent',
      'taskId',
      'runId',
      'run_id',
      'contextType',
    ])
      if (output[key] != null) key: output[key],
    if (result != null) 'result': result,
  };

  // The old Xiaowan adapter put clarification and permission facts both at
  // the result envelope and inside `result`. ACP intentionally does not
  // constrain rawOutput, so make that shape available to the shared card
  // parser without requiring each Harness to duplicate this flattening.
  final resultMap =
      _asStringMap(result) ??
      (result is String ? _asStringMap(_decodeAcpJsonValue(result)) : null);
  if (resultMap != null) {
    // ACP deliberately leaves rawOutput application-defined. Several ACP
    // clients wrap their concrete result in `result` (for example a
    // ContextResult containing a terminal result), while Xiaowan historically
    // placed the same fields at the envelope level. Promote the nested facts
    // once here so every Harness reaches the existing card routes equally.
    final nestedToolType = _string(resultMap['toolType']);
    if ((projected['toolType'] == null ||
            projected['toolType'].toString().trim().toLowerCase() ==
                'context') &&
        nestedToolType != null &&
        nestedToolType.trim().isNotEmpty &&
        nestedToolType.trim().toLowerCase() != 'context') {
      projected['toolType'] = nestedToolType;
    }
    for (final key in const <String>[
      'terminalOutput',
      'terminalSessionId',
      'terminalStreamState',
      'imageDataUrl',
      'dataUrl',
      'imageUrl',
      'audioDataUrl',
      'audioUrl',
      'mimeType',
      'artifacts',
      'actions',
      'workspaceId',
      'success',
      'exitCode',
      'error',
      'timedOut',
      'timed_out',
      'outputTruncated',
      'originalChars',
      'headTail',
      'fullOutputArtifact',
      'previewJson',
      'rawResultJson',
    ]) {
      if (projected[key] == null && resultMap[key] != null) {
        projected[key] = resultMap[key];
      }
    }
    for (final key in const <String>[
      'question',
      'missingFields',
      'missing_fields',
      'missing',
      'message',
      'subagentStatusText',
      'subagentEvents',
      'subagentEvent',
    ]) {
      if (projected[key] == null && resultMap[key] != null) {
        projected[key] = resultMap[key];
      }
    }
  }
  if (projected['previewJson'] == null && result != null) {
    projected['previewJson'] = result;
  }
  return projected;
}

Map<String, dynamic>? _permissionCardFromAcpItem(Map<String, dynamic> item) {
  final explicit = _asStringMap(item['permissionCard']);
  if (explicit != null) return explicit;
  return _acpPermissionCard(item['rawOutput']);
}

Map<String, dynamic>? _acpPermissionCard(Object? rawOutput) {
  final decoded = rawOutput is String
      ? _decodeAcpJsonValue(rawOutput)
      : rawOutput;
  final map = _asStringMap(decoded);
  if (map == null || map['type'] != 'permission_section') return null;
  return map;
}

dynamic _decodeAcpJsonValue(String text) {
  try {
    return jsonDecode(text);
  } catch (_) {
    return text;
  }
}

String _acpToolUiType(String? kind) {
  switch (kind?.toLowerCase()) {
    case 'execute':
      return 'commandExecution';
    case 'edit':
    case 'delete':
    case 'move':
      return 'fileChange';
    case 'search':
    case 'fetch':
      return 'webSearch';
    case 'think':
      return 'plan';
    default:
      return 'tool';
  }
}

/// Official ACP ToolKind is the portable capability signal shared by all
/// Harnesses. Keep the old card routes behind this projection rather than
/// asking each adapter to invent its own `toolType` metadata.
String? _acpOfficialToolType(String? kind) {
  return switch (kind?.trim().toLowerCase()) {
    'read' => 'workspace',
    'edit' || 'delete' || 'move' => 'file',
    'search' => 'search',
    'execute' => 'terminal',
    'fetch' => 'browser',
    'think' => 'plan',
    _ => null,
  };
}

bool _isReasoningMethod(String method) {
  return method == 'item/reasoning/delta' ||
      method == 'item/reasoning/summaryPartAdded' ||
      method == 'item/reasoning/summaryTextDelta' ||
      method == 'item/reasoning/textDelta';
}

bool _requiresAcpTurnIdentity(String method, Map<String, dynamic> params) {
  if (method == 'item/tool/requestUserInput' ||
      method.endsWith('requestApproval') ||
      method == 'item/userMessage/delta' && params['replay'] == true) {
    return false;
  }
  if (method == 'turn/started' ||
      method == 'turn/plan/updated' ||
      method == 'turn/plan/removed' ||
      method == 'turn/diff/updated' ||
      method == 'rawResponseItem/completed' ||
      method.startsWith('item/')) {
    final item = _asStringMap(params['item']);
    final itemType = canonicalAgentItemType(_string(item?['type']));
    if (itemType == 'requestApproval' ||
        itemType == 'requestUserInput' ||
        itemType == 'elicitation') {
      return false;
    }
    return true;
  }
  if (method != 'session/update') {
    return false;
  }
  final update = _asStringMap(params['update']);
  final sessionUpdate = _string(update?['sessionUpdate']);
  return sessionUpdate != null &&
      <String>{
        'agent_message_chunk',
        'agent_thought_chunk',
        'tool_call',
        'tool_call_update',
        'plan',
        'plan_update',
        'plan_removed',
        'terminal_output_chunk',
        'terminal_update',
      }.contains(sessionUpdate);
}

class AcpLegacyEventAdapter {
  const AcpLegacyEventAdapter();

  bool isLegacy(Map<String, dynamic> event) {
    return event['legacyCompatibility'] == true ||
        event.containsKey('taskId') ||
        event.containsKey('task_id') ||
        event.containsKey('streamKind') ||
        event.containsKey('eventKind') ||
        event.containsKey('kind') &&
            _resolveAgentEventMethod(event: event, message: event).isEmpty;
  }

  /// Converts the removed `AgentStreamEvent` data shape into official ACP item
  /// notifications. This is deliberately an in-process import adapter: old
  /// clients must still enter through the ACP runtime/bridge, and no legacy
  /// method channel or stream endpoint is revived.
  Map<String, dynamic> normalize(Map<String, dynamic> source) {
    if (_resolveAgentEventMethod(event: source, message: source).isNotEmpty) {
      return source;
    }
    final kind = _string(
      source['kind'] ?? source['streamKind'] ?? source['eventKind'],
    )?.toLowerCase();
    if (kind == null || kind.isEmpty) {
      return source;
    }

    final taskId = _firstString([
      source['taskId'],
      source['task_id'],
      source['turnId'],
      source['turn_id'],
      source['runId'],
      source['run_id'],
    ]);
    final entryId = _firstString([
      source['entryId'],
      source['entry_id'],
      source['messageId'],
      source['message_id'],
      source['itemId'],
      source['item_id'],
      source['callId'],
      source['call_id'],
    ]);
    final sessionId = _firstString([
      source['sessionId'],
      source['session_id'],
      source['threadId'],
      source['thread_id'],
    ]);
    final requestId =
        source['requestId'] ??
        source['request_id'] ??
        source['requestID'] ??
        source['id'];
    final sequence = _firstString([source['seq'], source['sequence']]);
    final eventId =
        _firstString([source['eventId'], source['hostEventId']]) ??
        (taskId != null && sequence != null
            ? 'legacy:$taskId:$sequence'
            : null);
    final common = <String, dynamic>{
      if (sessionId != null) 'sessionId': sessionId,
      if (taskId != null) 'turnId': taskId,
      if (requestId is String && requestId.trim().isNotEmpty)
        'requestId': requestId.trim(),
      if (requestId is num) 'requestId': requestId,
      if (eventId != null) 'eventId': eventId,
      'legacyCompatibility': true,
    };
    final text = source['text'] ?? source['content'];
    final thinking = source['thinking'] ?? source['reasoning'];
    final toolName = _firstString([
      source['toolName'],
      source['tool_name'],
      source['displayName'],
    ]);
    final item = <String, dynamic>{
      if (entryId != null) 'id': entryId,
      if (entryId != null) 'itemId': entryId,
      if (toolName != null) 'toolName': toolName,
      if (source['toolType'] != null) 'toolType': source['toolType'],
      if (source['status'] != null) 'status': source['status'],
      if (source['summary'] != null) 'summary': source['summary'],
      if (source['error'] != null) 'error': source['error'],
      if (source['rawOutput'] != null) 'rawOutput': source['rawOutput'],
      if (source['result'] != null) 'rawOutput': source['result'],
    };

    Map<String, dynamic> eventWith(String method, Map<String, dynamic> params) {
      return <String, dynamic>{
        ...common,
        'method': method,
        'params': <String, dynamic>{
          ...common,
          ...params,
          '_compatibility': <String, dynamic>{
            'source': 'legacy_agent_stream',
            'kind': kind,
          },
        },
      };
    }

    switch (kind) {
      case 'thinking_started':
      case 'thinking_snapshot':
      case 'thinking':
        return eventWith('item/reasoning/delta', {
          if (entryId != null) 'itemId': entryId,
          'delta': thinking ?? text ?? '',
        });
      case 'text_snapshot':
      case 'assistant_message':
      case 'message':
      case 'text':
        return eventWith('item/agentMessage/delta', {
          if (entryId != null) 'itemId': entryId,
          'delta': text ?? '',
        });
      case 'tool_started':
        return eventWith('item/started', {
          'item': <String, dynamic>{
            ...item,
            'type': source['itemType'] ?? 'dynamicToolCall',
            'status': source['status'] ?? 'in_progress',
          },
        });
      case 'tool_progress':
        return eventWith('item/updated', {
          'item': <String, dynamic>{...item, 'type': 'dynamicToolCall'},
        });
      case 'tool_completed':
        return eventWith('item/completed', {
          'item': <String, dynamic>{
            ...item,
            'type': source['itemType'] ?? 'dynamicToolCall',
            'status': source['status'] ?? 'completed',
          },
        });
      case 'permission_required':
        return eventWith('item/started', {
          'item': <String, dynamic>{
            ...item,
            'id': entryId ?? 'legacy-permission',
            'type': 'requestApproval',
          },
        });
      case 'clarify_required':
        return eventWith('item/started', {
          'item': <String, dynamic>{
            ...item,
            'id': entryId ?? 'legacy-clarification',
            'type': 'requestUserInput',
            'question': source['question'] ?? text ?? '',
            'missingFields':
                source['missingFields'] ??
                source['missing'] ??
                const <dynamic>[],
          },
        });
      case 'retrying':
        return eventWith('item/agentMessage/delta', {
          if (entryId != null) 'itemId': entryId,
          'delta': '',
          'acpPresentation': <String, dynamic>{
            'retry': <String, dynamic>{
              if (source['retryCount'] != null) 'count': source['retryCount'],
              if (source['maxRetries'] != null)
                'maxRetries': source['maxRetries'],
              'message': source['message'] ?? '正在重试…',
              if (source['reason'] != null) 'reason': source['reason'],
            },
          },
        });
      default:
        // Unknown legacy kinds are retained for diagnostics instead of being
        // guessed into a visible card. Future ACP extensions can add a real
        // session/update projection without modifying this adapter.
        return <String, dynamic>{...source, 'legacyCompatibility': true};
    }
  }
}

const _acpLegacyEventAdapter = AcpLegacyEventAdapter();

bool _canUseHostTurnReservation(
  ChatConversationRuntimeState runtime,
  Map<String, dynamic> event,
) {
  // ACP v1 session/update is session-scoped and may have no wire turn id.
  // Only the host's active prompt reservation can attribute that update; an
  // arbitrary provider id or a stale text snapshot is not sufficient.
  return acpEventAllowsImplicitTurnAdmission(event) &&
      runtime.isAiResponding &&
      runtime.activeAcpTurnId == null &&
      runtime.currentDispatchTurnId?.trim().isNotEmpty == true;
}

String _resolveAgentEventMethod({
  required Map<String, dynamic> event,
  required Map<String, dynamic> message,
}) {
  for (final envelope in _agentEnvelopeMaps(message)) {
    final normalized = _normalizeAgentEventMethod(_string(envelope['method']));
    if (normalized.isNotEmpty) {
      return normalized;
    }
  }
  if (!identical(event, message)) {
    for (final envelope in _agentEnvelopeMaps(event)) {
      final normalized = _normalizeAgentEventMethod(
        _string(envelope['method']),
      );
      if (normalized.isNotEmpty) {
        return normalized;
      }
    }
  }
  for (final envelope in _agentEnvelopeMaps(message)) {
    final rawType = _string(envelope['type']);
    if (!_agentTypeLooksLikeEventMethod(rawType)) {
      continue;
    }
    final normalized = _normalizeAgentEventMethod(rawType);
    if (normalized.isNotEmpty) {
      return normalized;
    }
  }
  if (!identical(event, message)) {
    for (final envelope in _agentEnvelopeMaps(event)) {
      final rawType = _string(envelope['type']);
      if (!_agentTypeLooksLikeEventMethod(rawType)) {
        continue;
      }
      final normalized = _normalizeAgentEventMethod(rawType);
      if (normalized.isNotEmpty) {
        return normalized;
      }
    }
  }
  return '';
}

bool _agentTypeLooksLikeEventMethod(String? rawType) {
  final value = rawType?.trim() ?? '';
  if (value.isEmpty) {
    return false;
  }
  final normalized = _normalizeAgentEventMethod(value);
  return normalized.contains('/') ||
      normalized == 'error' ||
      _looksLikeStandaloneAgentItemType(value);
}

String _normalizeAgentEventMethod(String? rawMethod) {
  final value = rawMethod?.trim() ?? '';
  if (value.isEmpty) {
    return '';
  }
  final dotted = const <String, String>{
    'thread.started': 'thread/started',
    'turn.started': 'turn/started',
    'turn.completed': 'turn/completed',
    'turn.failed': 'turn/failed',
    'item.started': 'item/started',
    'item.updated': 'item/updated',
    'item.completed': 'item/completed',
  }[value];
  if (dotted != null) {
    return dotted;
  }
  if (_looksLikeStandaloneAgentItemType(value)) {
    return 'item/completed';
  }
  return value
      .replaceAll('/agent_message/', '/agentMessage/')
      .replaceAll('/command_execution/', '/commandExecution/')
      .replaceAll('/file_change/', '/fileChange/')
      .replaceAll('/mcp_tool_call/', '/mcpToolCall/');
}

Map<String, dynamic> _eventParams({
  required Map<String, dynamic> event,
  required Map<String, dynamic> message,
  required String method,
}) {
  final messageParams = _firstNestedParamsMap(message);
  if (messageParams != null && messageParams.isNotEmpty) {
    return messageParams;
  }
  final eventParams = _firstNestedParamsMap(event);
  if (eventParams != null && eventParams.isNotEmpty) {
    return eventParams;
  }
  if (_isItemLifecycleMethod(method)) {
    final item = _firstNestedItemMap(message) ?? _firstNestedItemMap(event);
    if (item != null) {
      return <String, dynamic>{
        ..._payloadWithoutEnvelope(message),
        'item': item,
      };
    }
    final directItem =
        _standaloneAgentItemPayload(message) ??
        _standaloneAgentItemPayload(event);
    if (directItem != null) {
      return <String, dynamic>{
        ..._topLevelAgentIds(message),
        ..._topLevelAgentIds(event),
        'item': directItem,
      };
    }
  }
  final messagePayload = _payloadWithoutEnvelope(message);
  if (messagePayload.isNotEmpty) {
    return messagePayload;
  }
  return _payloadWithoutEnvelope(event);
}

const List<String> _agentEnvelopeKeys = <String>[
  'message',
  'payload',
  'data',
  'event',
  'notification',
  'result',
];

Iterable<Map<String, dynamic>> _agentEnvelopeMaps(
  Map<String, dynamic> root, {
  int depth = 0,
}) sync* {
  if (depth > 6) {
    return;
  }
  yield root;
  final params = _asStringMap(root['params']);
  if (params != null) {
    yield* _agentEnvelopeMaps(params, depth: depth + 1);
  }
  for (final key in _agentEnvelopeKeys) {
    final nested = _asStringMap(root[key]);
    if (nested == null) {
      continue;
    }
    yield* _agentEnvelopeMaps(nested, depth: depth + 1);
  }
}

Map<String, dynamic>? _firstNestedParamsMap(
  Map<String, dynamic> root, {
  int depth = 0,
}) {
  if (depth > 6) {
    return null;
  }
  final direct = _asStringMap(root['params']);
  if (direct != null) {
    final nested = _firstNestedParamsMap(direct, depth: depth + 1);
    if (nested != null && nested.isNotEmpty) {
      return <String, dynamic>{..._topLevelAgentIds(root), ...nested};
    }
    if (direct.isNotEmpty) {
      return <String, dynamic>{..._topLevelAgentIds(root), ...direct};
    }
  }
  for (final key in _agentEnvelopeKeys) {
    final nested = _asStringMap(root[key]);
    if (nested == null) {
      continue;
    }
    final nestedParams = _firstNestedParamsMap(nested, depth: depth + 1);
    if (nestedParams != null && nestedParams.isNotEmpty) {
      return <String, dynamic>{..._topLevelAgentIds(root), ...nestedParams};
    }
  }
  return null;
}

Map<String, dynamic>? _firstNestedItemMap(
  Map<String, dynamic> root, {
  int depth = 0,
}) {
  if (depth > 6) {
    return null;
  }
  for (final key in const <String>['item', 'rawItem', 'responseItem']) {
    final item = _asStringMap(root[key]);
    if (item != null) {
      return item;
    }
  }
  final params = _asStringMap(root['params']);
  if (params != null) {
    final item = _firstNestedItemMap(params, depth: depth + 1);
    if (item != null) {
      return item;
    }
  }
  for (final key in _agentEnvelopeKeys) {
    final nested = _asStringMap(root[key]);
    if (nested == null) {
      continue;
    }
    final item = _firstNestedItemMap(nested, depth: depth + 1);
    if (item != null) {
      return item;
    }
  }
  return null;
}

bool _isItemLifecycleMethod(String method) {
  return method == 'item/started' ||
      method == 'item/updated' ||
      method == 'item/completed';
}

Map<String, dynamic> _payloadWithoutEnvelope(Map<String, dynamic> value) {
  final payload = <String, dynamic>{};
  for (final entry in value.entries) {
    final key = entry.key;
    if (key == 'method' ||
        key == 'type' ||
        key == 'params' ||
        _agentEnvelopeKeys.contains(key)) {
      continue;
    }
    payload[key] = entry.value;
  }
  return payload;
}

Map<String, dynamic>? _standaloneAgentItemPayload(Map<String, dynamic> value) {
  final type = _string(value['type']);
  if (!_looksLikeStandaloneAgentItemType(type)) {
    return null;
  }
  return value;
}

bool _looksLikeStandaloneAgentItemType(String? itemType) {
  final canonicalItemType = canonicalAgentItemType(itemType);
  return canonicalItemType == 'agentMessage' ||
      canonicalItemType == 'reasoning' ||
      isAgentToolItemType(canonicalItemType);
}

Map<String, dynamic> _topLevelAgentIds(Map<String, dynamic> value) {
  final ids = <String, dynamic>{};
  final meta = _asStringMap(value['_meta']);
  if (meta != null) {
    for (final key in const <String>['threadId', 'thread_id']) {
      if (meta.containsKey(key)) {
        ids[key] = meta[key];
      }
    }
  }
  for (final key in const <String>[
    'threadId',
    'thread_id',
    'turnId',
    'turn_id',
    'itemId',
    'item_id',
  ]) {
    if (value.containsKey(key)) {
      ids[key] = value[key];
    }
  }
  return ids;
}

Map<String, dynamic>? _asStringMap(dynamic value) {
  if (value is! Map) return null;
  return value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue));
}

List<Map<String, dynamic>> _asMapList(dynamic value) {
  if (value is! List) {
    return const <Map<String, dynamic>>[];
  }
  return value
      .whereType<Map>()
      .map(
        (item) => item.map(
          (key, nestedValue) => MapEntry(key.toString(), nestedValue),
        ),
      )
      .toList(growable: false);
}

String? _extractText(dynamic value) {
  if (value == null) return null;
  if (value is String) return value;
  if (value is num || value is bool) return value.toString();
  final map = _asStringMap(value);
  if (map != null) {
    return _firstString([
      map['text'],
      map['content'],
      map['message'],
      map['value'],
      map['delta'],
      map['summary'],
    ]);
  }
  if (value is List) {
    return value.map(_extractText).whereType<String>().join();
  }
  return value.toString();
}

/// Extracts streamed ACP text without normalizing its whitespace.
///
/// Markdown structure can be split at arbitrary token boundaries, including
/// chunks made entirely of spaces or newlines. This helper is intentionally
/// separate from [_extractText]/[_firstString], whose trimming behavior is
/// still required for protocol identifiers, statuses, and display labels.
String? _extractStreamingText(dynamic value) {
  if (value == null) return null;
  if (value is String) return value;
  if (value is num || value is bool) return value.toString();
  final map = _asStringMap(value);
  if (map != null) {
    for (final candidate in <dynamic>[
      map['text'],
      map['content'],
      map['message'],
      map['value'],
      map['delta'],
      map['resource'],
    ]) {
      final text = _extractStreamingText(candidate);
      if (text != null && text.isNotEmpty) {
        return text;
      }
    }
    return null;
  }
  if (value is List) {
    return value.map(_extractStreamingText).whereType<String>().join();
  }
  return value.toString();
}

String? _commandTextFromValue(dynamic value) {
  if (value == null) return null;
  if (value is String) {
    final text = value.trim();
    return text.isEmpty ? null : text;
  }
  if (value is List) {
    final parts = value
        .map(_extractText)
        .whereType<String>()
        .map((part) => part.trim())
        .where((part) => part.isNotEmpty)
        .toList(growable: false);
    return parts.isEmpty ? null : parts.join(' ');
  }
  return _extractText(value);
}

String? _decodeBase64Output(dynamic value) {
  final encoded = _string(value);
  if (encoded == null) {
    return null;
  }
  try {
    return utf8.decode(base64Decode(encoded), allowMalformed: true);
  } catch (_) {
    return null;
  }
}

String? _decodeByteListOutput(dynamic value) {
  if (value is! List) {
    return null;
  }
  final bytes = <int>[];
  for (final item in value) {
    final byte = _asInt(item);
    if (byte == null || byte < 0 || byte > 255) {
      return null;
    }
    bytes.add(byte);
  }
  try {
    return utf8.decode(bytes, allowMalformed: true);
  } catch (_) {
    return null;
  }
}

String? _string(dynamic value) {
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

Object? _acpRequestId({
  required Map<String, dynamic> params,
  required Map<String, dynamic> message,
  Map<String, dynamic>? item,
}) {
  for (final candidate in <dynamic>[
    message['id'],
    params['requestId'],
    params['request_id'],
    item?['requestId'],
    item?['request_id'],
  ]) {
    if (candidate is String) {
      if (candidate.trim().isNotEmpty) return candidate.trim();
    } else if (candidate is num) {
      return candidate;
    }
  }
  return null;
}

String? _firstString(Iterable<dynamic> values) {
  for (final value in values) {
    final text = _extractText(value)?.trim();
    if (text != null && text.isNotEmpty) {
      return text;
    }
  }
  return null;
}

String? _collaborationModeFromThreadSettings(Map<String, dynamic> params) {
  final settings =
      _asStringMap(params['threadSettings']) ??
      _asStringMap(params['thread_settings']) ??
      _asStringMap(params['settings']) ??
      _asStringMap(params['thread']) ??
      params;
  final modeValue =
      settings['collaborationMode'] ??
      settings['collaboration_mode'] ??
      params['collaborationMode'] ??
      params['collaboration_mode'];
  final modeMap = _asStringMap(modeValue);
  final mode = _firstString([modeMap?['mode'], modeMap?['kind'], modeValue]);
  if (mode != null) {
    return mode;
  }
  final nestedSettings =
      _asStringMap(modeMap?['settings']) ?? _asStringMap(settings['settings']);
  return _firstString([nestedSettings?['mode'], nestedSettings?['kind']]);
}

int? _asInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

double? _asDouble(dynamic value) {
  if (value is num) return value.toDouble();
  return double.tryParse(value?.toString() ?? '');
}

String _safeJson(dynamic value) {
  try {
    return const JsonEncoder.withIndent('  ').convert(value);
  } catch (_) {
    return value?.toString() ?? '';
  }
}

String _accountSummary(Map<String, dynamic> params) {
  final account = _asStringMap(params['account']) ?? params;
  final email = _string(account['email']);
  final plan = _string(account['planType']) ?? _string(account['plan_type']);
  final type = _string(account['type']);
  final parts = <String>[
    if (email != null) email,
    if (plan != null) plan,
    if (type != null && type != 'chatgpt') type,
  ];
  return parts.isEmpty ? _safeJson(params) : parts.join(' / ');
}

String _trimTerminalOutput(String value) {
  return value;
}
