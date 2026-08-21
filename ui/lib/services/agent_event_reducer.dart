import 'dart:convert';

import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_stream_meta.dart';
import 'package:ui/services/agent_diff_parser.dart';
import 'package:ui/services/agent_message_kinds.dart';
import 'package:ui/services/agent_tool_call_parser.dart';

class AgentReduceResult {
  const AgentReduceResult({
    required this.handled,
    this.method,
    this.threadId,
    this.turnId,
    this.requestId,
    this.collaborationMode,
  });

  final bool handled;
  final String? method;
  final String? threadId;
  final String? turnId;
  final Object? requestId;
  final String? collaborationMode;
}

class AgentEventReducer {
  const AgentEventReducer();

  AgentReduceResult reduce({
    required ChatConversationRuntimeState runtime,
    required Map<String, dynamic> event,
  }) {
    final message = _asStringMap(event['message']) ?? event;
    final method = _resolveAgentEventMethod(event: event, message: message);
    if (method.isEmpty) {
      return const AgentReduceResult(handled: false);
    }

    final params = _eventParams(event: event, message: message, method: method);

    // ACP agents speak the official session/update notification. The reducer
    // projects that protocol object into UI state without introducing a
    // second host-owned event protocol.
    if (method == 'session/update') {
      final update = _asStringMap(params['update']);
      final sessionUpdate = _string(update?['sessionUpdate']);
      final scopedUpdate =
          sessionUpdate != null &&
          sessionUpdate != 'current_mode_update' &&
          sessionUpdate != 'config_option_update';
      final updateTurnId = _firstString([
        event['turnId'],
        event['turn_id'],
        params['turnId'],
        params['turn_id'],
      ]);
      if (scopedUpdate && updateTurnId == null) {
        // ACP updates are streamed inside a prompt turn. Never manufacture a
        // local owner from sessionId or messageId: that reattaches late data
        // to the next prompt and recreates the duplicate-conversation bug.
        return AgentReduceResult(handled: true, method: method);
      }
      // A turn-scoped ACP update without a turn id is not attributable. Do
      // not guess from itemId or threadId: doing so is how late tool output
      // gets attached to the next prompt.
      final projected = _projectAcpSessionUpdate(event: event, params: params);
      if (projected == null) {
        return AgentReduceResult(handled: true, method: method);
      }
      return reduce(runtime: runtime, event: projected);
    }
    final threadId = _firstString([
      event['threadId'],
      params['threadId'],
      params['thread_id'],
      _asStringMap(params['thread'])?['id'],
    ]);
    final turnId = _firstString([
      event['turnId'],
      params['turnId'],
      params['turn_id'],
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
    final parentTaskId =
        _firstString([
          turnId,
          if (method.startsWith('item/')) runtime.activeAcpTurnId,
          if (method.startsWith('item/')) runtime.currentDispatchTurnId,
          itemId,
          threadId,
        ]) ??
        'agent-${runtime.conversationId}';

    if (turnId != null &&
        method != 'turn/started' &&
        runtime.completedAgentTurnIds.contains(turnId)) {
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'turn/started') {
      runtime.activeAcpTurnId = turnId ?? parentTaskId;
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

    if (method == 'turn/completed' || method == 'thread/closed') {
      _completeTurn(runtime, parentTaskId);
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (method == 'thread/started' || method == 'thread/status/changed') {
      final status = _statusType([
        params['status'],
        params['state'],
        _asStringMap(params['thread'])?['status'],
        _asStringMap(params['thread'])?['state'],
      ]);
      if (_statusIsActive(status)) {
        _touchActiveTurn(runtime, parentTaskId);
      } else if (method == 'thread/status/changed' &&
          _statusIsInactive(status)) {
        final taskId =
            turnId ??
            runtime.currentDispatchTurnId ??
            runtime.lastAgentTurnId ??
            parentTaskId;
        final statusDetail = _turnFailureDetail(params);
        final statusIsFailure =
            status == 'failed' || status == 'systemerror' || status == 'error';
        if (statusIsFailure && statusDetail != null) {
          _recordTurnFailure(
            runtime,
            taskId: taskId,
            detail: statusDetail,
            params: params,
          );
        }
        _completeTurn(
          runtime,
          taskId,
          appendCancelIfEmpty: !statusIsFailure && _statusIsCancelled(status),
        );
      }
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
        final thinkingEntryId = '$startedItemId-agent-thinking';
        final text =
            _extractText(item['text']) ??
            _extractText(item['summary']) ??
            _extractText(item['content']) ??
            '';
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
        final existingCardId = _findToolCardIdForCallId(runtime, startedItemId);
        final existingMessage = existingCardId == null
            ? null
            : runtime.messages.cast<ChatMessageModel?>().firstWhere(
                (message) => message?.id == existingCardId,
                orElse: () => null,
              );
        final existing = existingCardId == null
            ? null
            : _toolCardData(runtime, existingCardId);
        final mergedItem = _mergeAgentToolUpdate(existing, item);
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
            '$startedItemId-agent-${agentToolCardSuffix(toolInfo.toolType, itemType: toolInfo.itemType)}';
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
        final cardId = '$startedItemId-agent-approval';
        _upsertAgentRequestCard(
          runtime,
          cardId: cardId,
          taskId: parentTaskId,
          requestId: params['requestId'] ?? message['id'],
          requestKind: 'approval',
          title: _approvalTitle(itemType, item),
          detail: _approvalDetail(item),
          params: item,
          streamMeta: _streamMeta(
            runtime,
            parentTaskId: parentTaskId,
            entryId: cardId,
            kind: 'permission_required',
          ),
        );
      } else if (itemType.contains('requestUserInput')) {
        final question = _firstQuestion(item);
        final cardId = '$startedItemId-agent-user-input';
        _upsertAgentRequestCard(
          runtime,
          cardId: cardId,
          taskId: parentTaskId,
          requestId: params['requestId'] ?? message['id'] ?? item['id'],
          requestKind: 'user_input',
          title: question.title,
          detail: question.detail,
          questionId: question.id,
          params: item,
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
        requestId: params['requestId'] ?? message['id'],
      );
    }

    if (method == 'item/agentMessage/delta') {
      final delta =
          _extractText(params['delta']) ??
          _extractText(params['text']) ??
          _extractText(params['message']) ??
          '';
      if (delta.isNotEmpty) {
        _finalizeActiveThinkingCardForTask(runtime, parentTaskId);
        final entryId =
            _string(params['entryId']) ??
            '${itemId ?? parentTaskId}-agent-message';
        _appendAssistantText(
          runtime,
          parentTaskId: parentTaskId,
          entryId: entryId,
          delta: delta,
          isFinal: false,
        );
      }
      return AgentReduceResult(
        handled: true,
        method: method,
        threadId: threadId,
        turnId: turnId,
      );
    }

    if (_isReasoningMethod(method)) {
      final text =
          _extractText(params['delta']) ??
          _extractText(params['text']) ??
          _extractText(params['summary']) ??
          _extractText(params['part']) ??
          '';
      if (text.isNotEmpty) {
        final entryId =
            _string(params['entryId']) ??
            '${itemId ?? parentTaskId}-agent-thinking';
        _appendThinking(
          runtime,
          parentTaskId: parentTaskId,
          cardId: entryId,
          delta: text,
        );
      }
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
      final cardId = '${itemId ?? parentTaskId}-agent-plan';
      _upsertToolCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        toolType: 'plan',
        title: 'Agent plan',
        status: 'running',
        summary: text,
        progress: text,
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

    if (method == 'item/commandExecution/outputDelta' ||
        method == 'item/commandExecution/terminalInteraction') {
      final delta =
          _extractText(params['delta']) ??
          _extractText(params['output']) ??
          _extractText(params['text']) ??
          '';
      final callId = itemId ?? parentTaskId;
      final existingCardId = _findToolCardIdForCallId(runtime, callId);
      final existing = existingCardId == null
          ? null
          : _toolCardData(runtime, existingCardId);
      final cardId = existingCardId ?? '$callId-agent-command';
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
      final standaloneId = _standaloneProcessId(params, method: method);
      final cardId = '$standaloneId-agent-command';
      _appendToolOutput(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
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

    if (method == 'process/exited' || method == 'command/exec/completed') {
      _completeStandaloneProcess(runtime, parentTaskId, params, method);
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
      final cardId = '${itemId ?? parentTaskId}-agent-file';
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
      final requestId = message['id'];
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

    if (method == 'item/tool/requestUserInput') {
      final requestId = message['id'];
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
      final cardId = '${itemId ?? parentTaskId}-agent-tool';
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
      final cardId =
          '$dynamicItemId-agent-${agentToolCardSuffix(toolInfo.toolType, itemType: toolInfo.itemType)}';
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

    if (method == 'turn/failed') {
      _recordTurnFailure(
        runtime,
        taskId: parentTaskId,
        detail: _turnFailureDetail(params, fallbackToPayload: true)!,
        params: params,
      );
      final completionTaskId =
          turnId ??
          runtime.currentDispatchTurnId ??
          runtime.lastAgentTurnId ??
          parentTaskId;
      _completeTurn(runtime, completionTaskId, appendCancelIfEmpty: false);
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

    if (method == 'error') {
      final detail =
          _extractText(params['message']) ??
          _extractText(params['error']) ??
          _safeJson(params);
      final cardId = '$parentTaskId-agent-status';
      _upsertToolCard(
        runtime,
        cardId: cardId,
        taskId: parentTaskId,
        toolType: 'status',
        title: method,
        status: 'error',
        summary: detail,
        progress: detail,
        raw: params,
        streamMeta: _streamMeta(
          runtime,
          parentTaskId: parentTaskId,
          entryId: cardId,
          kind: 'error',
          isFinal: true,
        ),
        touchTurn: false,
      );
      // ACP emits the top-level `error` notification when a turn fails
      // terminally (network, rate-limit, server error). When
      // willRetry=false the server will NOT follow up with turn/completed,
      // so we must finalize the turn ourselves — otherwise runtime stays
      // isAiResponding=true forever.
      final willRetry = params['willRetry'] == true;
      if (!willRetry) {
        final completionTaskId =
            turnId ??
            runtime.currentDispatchTurnId ??
            runtime.lastAgentTurnId ??
            parentTaskId;
        _completeTurn(runtime, completionTaskId, appendCancelIfEmpty: false);
      }
      return AgentReduceResult(
        handled: true,
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

  void _touchActiveTurn(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
  ) {
    runtime.completedAgentTurnIds.remove(parentTaskId);
    runtime.isAiResponding = true;
    if (runtime.activeAcpTurnId == null && parentTaskId.trim().isNotEmpty) {
      // The first official session/update can be the admission boundary for
      // adapters that do not expose a separate turn/started notification.
      runtime.activeAcpTurnId = parentTaskId;
    }
    runtime.currentDispatchTurnId = parentTaskId;
    runtime.lastAgentTurnId = parentTaskId;
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
      return;
    }
    runtime.messages[index] = runtime.messages[index].copyWith(
      content: content,
      isLoading: false,
      isError: false,
      streamMeta: streamMeta,
    );
  }

  void _appendThinking(
    ChatConversationRuntimeState runtime, {
    required String parentTaskId,
    required String cardId,
    required String delta,
  }) {
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
      'cardId': cardId,
      'startTime': startTime,
      'endTime': endTime,
      'isCollapsible': !isLoading,
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
    final cardData = <String, dynamic>{
      'type': 'agent_tool_summary',
      'uiStyle': kAgentToolUiStyle,
      'taskId': taskId,
      'toolName': toolInfo.toolName,
      'displayName': toolInfo.displayName,
      'toolTitle': effectiveTitle,
      'cardId': cardId,
      'toolType': effectiveToolType,
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
      'showTerminalOutput':
          (effectiveTerminalOutput.isNotEmpty && diffText.isEmpty) ||
          effectiveToolType == 'terminal',
      'showRawResult': true,
    };
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
    runtime.lastAgentToolType = effectiveToolType;
    if (effectiveToolType == 'terminal' || effectiveToolType == 'browser') {
      runtime.chatIslandDisplayLayer = ChatIslandDisplayLayer.tools;
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
    String? questionId,
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
      'requestId': requestId,
      'requestKind': requestKind,
      'title': title,
      'detail': detail,
      'questionId': questionId,
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
      // _completeTurn() will call _finalizeThinkingCardsForTask() once
      // turn/completed (or thread/closed/inactive) arrives.
      final cardId =
          _string(item['entryId']) ?? '${itemId ?? taskId}-agent-thinking';
      _markThinkingItemCompleted(runtime, taskId, cardId);
      runtime.agentReplayDeltaOffsets.remove(cardId);
    }
    if (isAgentToolItemType(itemType)) {
      final completedItemId = itemId ?? _string(item['id']) ?? taskId;
      final existingCardId = _findToolCardIdForCallId(runtime, completedItemId);
      final existingMessage = existingCardId == null
          ? null
          : runtime.messages.cast<ChatMessageModel?>().firstWhere(
              (message) => message?.id == existingCardId,
              orElse: () => null,
            );
      final existing = existingCardId == null
          ? null
          : _toolCardData(runtime, existingCardId);
      final mergedItem = _mergeAgentToolUpdate(existing, item);
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
      final cardId = existingCardId ?? '$completedItemId-agent-$suffix';
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
          kind: toolInfo.status == 'running'
              ? 'tool_progress'
              : 'tool_completed',
          isFinal: toolInfo.status != 'running',
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
    final cardId = '$rawItemId-agent-$suffix';
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
        kind: toolInfo.status == 'running' ? 'tool_progress' : 'tool_completed',
        isFinal: toolInfo.status != 'running',
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
        : _findToolCardIdForCallId(runtime, callId);
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
    final suffix = agentToolCardSuffix(toolInfo.toolType, itemType: itemType);
    final cardId = existingCardId ?? '$rawItemId-agent-$suffix';
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
      raw: item,
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
    final cardId = '$standaloneId-agent-command';
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
    // A normal ACP turn/completed is successful even when the turn only
    // produced reasoning or tool activity. Cancellation is represented by an
    // explicit cancelled thread status, not by an empty assistant message.
    bool appendCancelIfEmpty = false,
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
    final isCurrentTurn =
        runtime.currentDispatchTurnId == ownerTaskId ||
        runtime.lastAgentTurnId == ownerTaskId ||
        runtime.activeAcpTurnId == ownerTaskId;
    // A terminal notification for turn N can arrive after turn N+1 has
    // already started. Finalize only N's cards/messages in that case; never
    // clear the shared runtime flags or text cache owned by N+1.
    if (!isCurrentTurn && runtime.currentDispatchTurnId != null) {
      _markAssistantMessagesFinalForTask(runtime, taskId);
      _finalizeThinkingCardsForTask(runtime, taskId);
      _markToolCardsCompleteForTask(runtime, taskId);
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
    runtime.isAiResponding = false;
    runtime.isExecutingTask = false;
    runtime.isCheckingExecutableTask = false;
    runtime.currentDispatchTurnId = null;
    if (runtime.activeAcpTurnId == taskId) {
      runtime.activeAcpTurnId = null;
    }
    runtime.lastAgentTurnId = null;
    runtime.currentAiMessages.clear();
    runtime.currentThinkingMessages.remove(ownerTaskId);
    runtime.pendingAgentTextTaskId = null;
    runtime.activeToolCardId = null;
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    runtime.activeThinkingCardId = null;
    runtime.currentThinkingStage = ThinkingStage.complete.value;
    _markAssistantMessagesFinalForTask(runtime, ownerTaskId);
    if (!isManualCancel) {
      _finalizeThinkingCardsForTask(runtime, ownerTaskId);
    }
    _markToolCardsCompleteForTask(runtime, ownerTaskId);
    if (wasActive || ownerWasActive) {
      runtime.completedAgentTurnIds.add(taskId);
      if (ownerTaskId != taskId) {
        runtime.completedAgentTurnIds.add(ownerTaskId);
      }
      if (runtime.completedAgentTurnIds.length > 128) {
        runtime.completedAgentTurnIds.remove(
          runtime.completedAgentTurnIds.first,
        );
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
      title: 'turn/failed',
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

  String? _turnFailureDetail(
    Map<String, dynamic> params, {
    bool fallbackToPayload = false,
  }) {
    final detail =
        _extractText(_asStringMap(params['error'])?['message']) ??
        _extractText(params['message']) ??
        _extractText(params['reason']) ??
        _extractText(params['error']);
    if (detail != null && detail.trim().isNotEmpty) {
      return detail.trim();
    }
    return fallbackToPayload ? _safeJson(params) : null;
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

  void _markToolCardsCompleteForTask(
    ChatConversationRuntimeState runtime,
    String parentTaskId,
  ) {
    final cardIds = runtime.messages
        .where((message) {
          final cardData = message.cardData;
          if (cardData?['type'] != 'agent_tool_summary') {
            return false;
          }
          final cardTaskId =
              _string(cardData?['taskId']) ??
              _string(message.streamMeta?['parentTaskId']);
          if (cardTaskId != parentTaskId) {
            return false;
          }
          final status = _string(cardData?['status'])?.toLowerCase();
          return status == null ||
              status == 'running' ||
              status == 'pending' ||
              status == 'progress';
        })
        .map((message) => message.id)
        .toList(growable: false);
    for (final cardId in cardIds) {
      _markToolCardComplete(runtime, cardId);
    }
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
    for (final entry in incoming.entries) {
      // ACP tool_call_update is a sparse patch. LocalAcpRuntime keeps absent
      // fields as explicit nulls while projecting it to a Map, so a shallow
      // spread would erase kind/title/input/content from the initial call.
      if (entry.value != null) {
        merged[entry.key] = entry.value;
      }
    }
    return merged;
  }

  String? _findToolCardIdForCallId(
    ChatConversationRuntimeState runtime,
    String callId,
  ) {
    final normalizedCallId = callId.trim();
    if (normalizedCallId.isEmpty) {
      return null;
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
      final cardId = '$normalizedCallId-agent-$suffix';
      if (_toolCardData(runtime, cardId) != null) {
        return cardId;
      }
    }
    for (final message in runtime.messages) {
      final cardData = message.cardData;
      if (cardData?['type'] != 'agent_tool_summary') {
        continue;
      }
      if (_toolCardContainsCallId(cardData!, normalizedCallId)) {
        return message.id;
      }
    }
    return null;
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
    return 'Agent approval';
  }

  String _approvalDetail(Map<String, dynamic> params) {
    return _extractText(params['reason']) ??
        _extractText(params['description']) ??
        _extractText(params['command']) ??
        _safeJson(params);
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

  _AgentQuestion _firstQuestion(Map<String, dynamic> params) {
    final questions = params['questions'];
    if (questions is List && questions.isNotEmpty) {
      final first = _asStringMap(questions.first);
      if (first != null) {
        final id =
            _string(first['id']) ?? _string(first['questionId']) ?? 'answer';
        final title =
            _string(first['label']) ??
            _string(first['title']) ??
            _string(first['question']) ??
            'Agent needs input';
        final detail =
            _string(first['description']) ??
            _string(first['placeholder']) ??
            title;
        return _AgentQuestion(id: id, title: title, detail: detail);
      }
    }
    final id =
        _string(params['questionId']) ?? _string(params['id']) ?? 'answer';
    final title =
        _string(params['question']) ??
        _string(params['title']) ??
        'Agent needs input';
    final detail = _string(params['description']) ?? title;
    return _AgentQuestion(id: id, title: title, detail: detail);
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

Map<String, dynamic>? _projectAcpSessionUpdate({
  required Map<String, dynamic> event,
  required Map<String, dynamic> params,
}) {
  final update = _asStringMap(params['update']);
  if (update == null) return null;
  final sessionId = _firstString([
    params['sessionId'],
    params['session_id'],
    event['threadId'],
  ]);
  final turnId = _firstString([
    event['turnId'],
    event['turn_id'],
    params['turnId'],
    params['turn_id'],
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

  final scopedMessageId = turnScopedEntryId(update['messageId']) ?? turnId;
  final scopedEntryId = turnScopedEntryId(update['entryId']);

  Map<String, dynamic> projectedParams(Map<String, dynamic> values) {
    return <String, dynamic>{
      ...values,
      if (sessionId != null) 'threadId': sessionId,
      if (turnId != null) 'turnId': turnId,
    };
  }

  switch (sessionUpdate) {
    case 'agent_message_chunk':
      return <String, dynamic>{
        'method': 'item/agentMessage/delta',
        'params': projectedParams(<String, dynamic>{
          // DSH may omit messageId, and when present it is only session
          // scoped. Both forms need a turn-scoped host entry id.
          'itemId': scopedMessageId,
          if (scopedEntryId != null) 'entryId': scopedEntryId,
          'delta': _extractText(update['content']) ?? '',
        }),
      };
    case 'agent_thought_chunk':
      return <String, dynamic>{
        'method': 'item/reasoning/delta',
        'params': projectedParams(<String, dynamic>{
          'itemId': scopedMessageId,
          if (scopedEntryId != null) 'entryId': scopedEntryId,
          'delta': _extractText(update['content']) ?? '',
        }),
      };
    case 'tool_call':
      return <String, dynamic>{
        'method': 'item/started',
        'params': projectedParams(<String, dynamic>{
          'item': _projectAcpToolCall(update),
        }),
      };
    case 'tool_call_update':
      final item = _projectAcpToolCall(update);
      final status = _string(item['status'])?.toLowerCase();
      return <String, dynamic>{
        'method':
            status == 'completed' || status == 'failed' || status == 'cancelled'
            ? 'item/completed'
            : 'item/updated',
        'params': projectedParams(<String, dynamic>{'item': item}),
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

bool _isTerminalAgentEventMethod(String method) {
  return method == 'turn/completed' ||
      method == 'turn/failed' ||
      method == 'thread/closed' ||
      method == 'error';
}

Map<String, dynamic> _projectAcpToolCall(Map<String, dynamic> update) {
  return <String, dynamic>{
    'id': update['toolCallId'],
    'type': _acpToolUiType(_string(update['kind'])),
    'title': update['title'],
    'status': update['status'],
    'content': update['content'],
    'locations': update['locations'],
    'rawInput': update['rawInput'],
    'rawOutput': update['rawOutput'],
  };
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

bool _isReasoningMethod(String method) {
  return method == 'item/reasoning/delta' ||
      method == 'item/reasoning/summaryPartAdded' ||
      method == 'item/reasoning/summaryTextDelta' ||
      method == 'item/reasoning/textDelta';
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

String? _firstString(Iterable<dynamic> values) {
  for (final value in values) {
    final text = _extractText(value)?.trim();
    if (text != null && text.isNotEmpty) {
      return text;
    }
  }
  return null;
}

String? _statusType(Iterable<dynamic> values) {
  for (final value in values) {
    final text = _statusText(value);
    if (text != null && text.isNotEmpty) {
      return _normalizeStatus(text);
    }
  }
  return null;
}

String? _statusText(dynamic value) {
  if (value == null) return null;
  if (value is String || value is num || value is bool) {
    return value.toString();
  }
  final map = _asStringMap(value);
  if (map != null) {
    return _firstString([
      map['type'],
      map['status'],
      map['state'],
      map['value'],
      map['name'],
    ]);
  }
  return null;
}

String _normalizeStatus(String status) =>
    status.trim().toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), '');

bool _statusIsActive(String? status) {
  return status == 'running' ||
      status == 'active' ||
      status == 'busy' ||
      status == 'inprogress' ||
      status == 'inflight' ||
      status == 'executing';
}

bool _statusIsInactive(String? status) {
  return status == 'idle' ||
      status == 'closed' ||
      status == 'completed' ||
      status == 'complete' ||
      status == 'notloaded' ||
      status == 'systemerror' ||
      status == 'failed' ||
      status == 'cancelled' ||
      status == 'canceled' ||
      status == 'interrupted';
}

bool _statusIsCancelled(String? status) {
  return status == 'cancelled' ||
      status == 'canceled' ||
      status == 'interrupted';
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
  const maxChars = 64 * 1024;
  const maxLines = 600;
  var text = value;
  if (text.length > maxChars) {
    text = text.substring(text.length - maxChars);
  }
  final lines = text.split('\n');
  if (lines.length > maxLines) {
    text = lines.sublist(lines.length - maxLines).join('\n');
  }
  return text;
}
