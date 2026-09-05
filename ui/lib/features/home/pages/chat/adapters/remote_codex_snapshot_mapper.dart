part of '../chat_page.dart';

String _remoteCodexThreadContentSignature(Map<String, dynamic> response) {
  final thread = _asAgentMap(response['thread']) ?? response;
  final turns = _remoteCodexTurnsFromThreadResponse(response);
  final buffer = StringBuffer()
    ..write(_asAgentString(thread['id'] ?? response['threadId']) ?? '')
    ..write('|');
  if (turns == null) {
    buffer
      ..write(
        _remoteCodexTimeValueMs(thread['updatedAt'] ?? thread['updated_at']) ??
            '',
      )
      ..write('|')
      ..write(_asAgentString(thread['preview'] ?? response['preview']) ?? '');
    return buffer.toString();
  }
  for (var turnIndex = 0; turnIndex < turns.length; turnIndex += 1) {
    final turn = _asAgentMap(turns[turnIndex]);
    if (turn == null) {
      continue;
    }
    buffer
      ..write(_remoteCodexTurnIdAt(turns, turnIndex) ?? '')
      ..write(':')
      ..write(_remoteCodexStatusText(turn['status'] ?? turn['state']) ?? '')
      ..write(':')
      ..write(
        _remoteCodexTimeValueMs(turn['startedAt'] ?? turn['started_at']) ?? '',
      )
      ..write(':')
      ..write(
        _remoteCodexTimeValueMs(turn['completedAt'] ?? turn['completed_at']) ??
            '',
      )
      ..write('|');
    final rawItems = _remoteCodexHistoricalItemsFromTurn(turn);
    for (var itemIndex = 0; itemIndex < rawItems.length; itemIndex += 1) {
      final item = rawItems[itemIndex];
      buffer
        ..write(_asAgentString(item['id']) ?? '$turnIndex-$itemIndex')
        ..write(',')
        ..write(_asAgentString(item['type']) ?? '')
        ..write(',')
        ..write(_remoteCodexStatusText(item['status'] ?? item['state']) ?? '')
        ..write(',')
        ..write(
          _remoteCodexExtractText(
            item['summary'] ??
                item['text'] ??
                item['message'] ??
                item['content'] ??
                item['output'] ??
                item['command'] ??
                item['cmd'] ??
                item['path'],
          ).hashCode,
        )
        ..write(';');
    }
  }
  return buffer.toString();
}

String _remoteCodexSnapshotSignature({
  required String threadId,
  required List<ChatMessageModel> messages,
  required ConversationModel conversation,
  required bool isAiResponding,
  required String? activeTaskId,
}) {
  final buffer = StringBuffer()
    ..write(threadId)
    ..write('|')
    ..write(conversation.updatedAt)
    ..write('|')
    ..write(isAiResponding ? '1' : '0')
    ..write('|')
    ..write(activeTaskId ?? '')
    ..write('|')
    ..write(messages.length);
  for (final message in messages) {
    final attachments = message.content?['attachments'];
    buffer
      ..write('|')
      ..write(message.id)
      ..write(':')
      ..write(message.text?.hashCode ?? message.cardData?.hashCode ?? 0)
      ..write(':')
      ..write(attachments == null ? 0 : _safeAgentJson(attachments).hashCode);
  }
  return buffer.toString();
}

List<ChatMessageModel> _mergeRemoteCodexSnapshotMessages({
  required List<ChatMessageModel> snapshotMessages,
  required List<ChatMessageModel> existingMessages,
  required String? activeTaskId,
  required bool isAiResponding,
}) {
  if (existingMessages.isEmpty) {
    return snapshotMessages
        .map(canonicalizeAgentHistoryMessage)
        .toList(growable: false);
  }
  final snapshotById = <String, ChatMessageModel>{
    for (final message in snapshotMessages) message.id: message,
  };
  final existingById = <String, ChatMessageModel>{
    for (final message in existingMessages) message.id: message,
  };
  final userMessageIdsToPreserve = _remoteRuntimeUserMessageIdsToPreserve(
    existingMessages: existingMessages,
    snapshotMessageIds: snapshotById.keys.toSet(),
    snapshotUserTextCounts: _remoteUserMessageTextCounts(snapshotMessages),
  );
  final snapshotTaskIds = _remoteSnapshotTaskIds(snapshotMessages);
  final mergedById = <String, ChatMessageModel>{};
  for (final snapshot in snapshotMessages) {
    final existing = existingById[snapshot.id];
    mergedById[snapshot.id] = canonicalizeAgentHistoryMessage(
      existing != null &&
              _shouldPreferExistingRemoteMessage(
                existing: existing,
                snapshot: snapshot,
                activeTaskId: activeTaskId,
                isAiResponding: isAiResponding,
              )
          ? existing
          : snapshot,
    );
  }
  for (final existing in existingMessages) {
    if (snapshotById.containsKey(existing.id)) {
      continue;
    }
    if (existing.type == 1 && existing.user == 1) {
      if (userMessageIdsToPreserve.contains(existing.id)) {
        mergedById[existing.id] = canonicalizeAgentHistoryMessage(existing);
      }
      continue;
    }
    if (!_shouldPreserveRemoteRuntimeMessage(
      existing,
      activeTaskId: activeTaskId,
      isAiResponding: isAiResponding,
      snapshotTaskIds: snapshotTaskIds,
    )) {
      continue;
    }
    mergedById[existing.id] = canonicalizeAgentHistoryMessage(existing);
  }
  final merged = mergedById.values.toList(growable: false)
    ..sort((a, b) => b.createAt.compareTo(a.createAt));
  return _normalizeAgentLoadingThinkingCards(
    merged,
    activeTaskId: activeTaskId,
    isAiResponding: isAiResponding,
  );
}

List<ChatMessageModel> _normalizeAgentLoadingThinkingCards(
  List<ChatMessageModel> messages, {
  required String? activeTaskId,
  required bool isAiResponding,
}) {
  final activeTask = activeTaskId?.trim() ?? '';
  final keptLoadingTaskIds = <String>{};
  final normalized = <ChatMessageModel>[];
  for (final message in messages) {
    final cardData = message.cardData;
    if (cardData?['type'] != 'deep_thinking') {
      normalized.add(message);
      continue;
    }
    final taskId = _messageTaskId(message);
    final normalizedTaskId = taskId?.trim() ?? '';
    final isLoading = cardData?['isLoading'] == true;
    final keepLoading =
        isLoading &&
        isAiResponding &&
        activeTask.isNotEmpty &&
        normalizedTaskId == activeTask &&
        !keptLoadingTaskIds.contains(normalizedTaskId);
    if (keepLoading) {
      keptLoadingTaskIds.add(normalizedTaskId);
      normalized.add(message);
      continue;
    }
    final shouldFinalize =
        isLoading ||
        cardData?['stage'] == ThinkingStage.thinking.value ||
        cardData?['isCollapsible'] == false;
    normalized.add(
      shouldFinalize
          ? _completeAgentThinkingSnapshotMessage(message, taskId: taskId)
          : message,
    );
  }
  return normalized;
}

ChatMessageModel _completeAgentThinkingSnapshotMessage(
  ChatMessageModel message, {
  required String? taskId,
}) {
  final cardData = Map<String, dynamic>.from(
    message.cardData ?? const <String, dynamic>{},
  );
  final resolvedTaskId =
      taskId ??
      _asAgentString(cardData['taskID']) ??
      _asAgentString(message.streamMeta?['parentTaskId']);
  final startTime =
      _asAgentInt(cardData['startTime']) ??
      message.createAt.millisecondsSinceEpoch;
  cardData['isLoading'] = false;
  cardData['stage'] = ThinkingStage.complete.value;
  if (resolvedTaskId != null) {
    cardData['taskID'] = resolvedTaskId;
  }
  cardData['cardId'] = _asAgentString(cardData['cardId']) ?? message.id;
  cardData['startTime'] = startTime;
  cardData['endTime'] ??= DateTime.now().millisecondsSinceEpoch;
  cardData['isCollapsible'] = true;
  cardData['thinkingContent'] = (cardData['thinkingContent'] ?? '').toString();
  return message.copyWith(
    content: {'cardData': cardData, 'id': message.id},
    streamMeta: ensureAgentStreamMessageMeta(
      message.streamMeta,
      kind: 'thinking_snapshot',
      parentTaskId: resolvedTaskId,
      entryId: message.id,
      isFinal: true,
    ),
  );
}

bool _shouldPreferExistingRemoteMessage({
  required ChatMessageModel existing,
  required ChatMessageModel snapshot,
  required String? activeTaskId,
  required bool isAiResponding,
}) {
  if (!isAiResponding) {
    return false;
  }
  if (!_messageBelongsToTask(existing, activeTaskId)) {
    return false;
  }
  if (_isInFlightAgentMessage(existing)) {
    return true;
  }
  final existingText = existing.text ?? '';
  final snapshotText = snapshot.text ?? '';
  return existingText.length > snapshotText.length &&
      existingText.startsWith(snapshotText);
}

bool _shouldPreserveRemoteRuntimeMessage(
  ChatMessageModel message, {
  required String? activeTaskId,
  required bool isAiResponding,
  required Set<String> snapshotTaskIds,
}) {
  if (_isAgentRequestMessage(message)) {
    if (isAiResponding) {
      return true;
    }
    final taskId = _messageTaskId(message);
    return taskId != null && snapshotTaskIds.contains(taskId);
  }
  final isAgentTool = isAcpAgentToolSummaryMessage(message);
  if (isAiResponding &&
      activeTaskId != null &&
      _messageBelongsToTask(message, activeTaskId)) {
    return isAgentTool || _isInFlightAgentMessage(message);
  }
  if (isAgentTool) {
    final taskId = _messageTaskId(message);
    return taskId != null && snapshotTaskIds.contains(taskId);
  }
  return false;
}

Set<String> _remoteSnapshotTaskIds(List<ChatMessageModel> messages) {
  final ids = <String>{};
  for (final message in messages) {
    final taskId = _messageTaskId(message);
    if (taskId != null) {
      ids.add(taskId);
    }
  }
  return ids;
}

Map<String, int> _remoteUserMessageTextCounts(List<ChatMessageModel> messages) {
  final counts = <String, int>{};
  for (final message in messages) {
    if (message.type != 1 || message.user != 1) {
      continue;
    }
    final text = message.text?.trim();
    if (text == null || text.isEmpty) {
      continue;
    }
    counts[text] = (counts[text] ?? 0) + 1;
  }
  return counts;
}

Set<String> _remoteRuntimeUserMessageIdsToPreserve({
  required List<ChatMessageModel> existingMessages,
  required Set<String> snapshotMessageIds,
  required Map<String, int> snapshotUserTextCounts,
}) {
  final existingByText = <String, List<ChatMessageModel>>{};
  for (final message in existingMessages) {
    if (snapshotMessageIds.contains(message.id) ||
        message.type != 1 ||
        message.user != 1) {
      continue;
    }
    final text = message.text?.trim();
    if (text == null || text.isEmpty) {
      continue;
    }
    (existingByText[text] ??= <ChatMessageModel>[]).add(message);
  }
  final preserveIds = <String>{};
  existingByText.forEach((text, messages) {
    messages.sort((a, b) => b.createAt.compareTo(a.createAt));
    final preserveCount = messages.length - (snapshotUserTextCounts[text] ?? 0);
    if (preserveCount <= 0) {
      return;
    }
    for (
      var index = 0;
      index < preserveCount && index < messages.length;
      index += 1
    ) {
      preserveIds.add(messages[index].id);
    }
  });
  return preserveIds;
}

bool _messageBelongsToTask(ChatMessageModel message, String? taskId) {
  final normalizedTaskId = taskId?.trim() ?? '';
  if (normalizedTaskId.isEmpty) {
    return false;
  }
  return _messageTaskId(message) == normalizedTaskId;
}

String? _messageTaskId(ChatMessageModel message) {
  final cardData = message.cardData;
  final parentTaskId =
      _asAgentString(message.streamMeta?['parentTaskId']) ??
      _asAgentString(cardData?['taskId']) ??
      _asAgentString(cardData?['taskID']);
  return parentTaskId;
}

bool _isAgentRequestMessage(ChatMessageModel message) {
  final cardData = message.cardData;
  return isAgentRequestCardType(cardData?['type']);
}

bool _isPendingAgentRequestMessage(ChatMessageModel message) {
  if (!_isAgentRequestMessage(message)) return false;
  final cardData = message.cardData;
  final status = _asAgentString(cardData?['status'])?.toLowerCase();
  return status == null ||
      status == 'pending' ||
      status == 'running' ||
      status == 'requested' ||
      status == 'open' ||
      status == 'progress';
}

bool _isInFlightAgentMessage(ChatMessageModel message) {
  final streamFinal = message.streamMeta?['isFinal'];
  if (streamFinal == false) {
    return true;
  }
  final cardData = message.cardData;
  if (cardData == null) {
    return message.isLoading;
  }
  if (cardData['type'] == 'deep_thinking' && cardData['isLoading'] == true) {
    return true;
  }
  final status = _asAgentString(cardData['status'])?.toLowerCase();
  return status == 'running' || status == 'pending' || status == 'progress';
}

@visibleForTesting
List<ChatMessageModel> mergeRemoteCodexSnapshotMessagesForTesting({
  required List<ChatMessageModel> snapshotMessages,
  required List<ChatMessageModel> existingMessages,
  required String? activeTaskId,
  required bool isAiResponding,
}) {
  return _mergeRemoteCodexSnapshotMessages(
    snapshotMessages: snapshotMessages,
    existingMessages: existingMessages,
    activeTaskId: activeTaskId,
    isAiResponding: isAiResponding,
  );
}

List<ChatMessageModel> _remoteCodexMessagesFromThreadResponse(
  Map<String, dynamic> response, {
  bool active = false,
  String? activeTurnId,
}) {
  final thread = _asAgentMap(response['thread']) ?? response;
  final agentId =
      _asAgentString(thread['agentId'] ?? response['agentId']) ?? 'codex-acp';
  final agentName = _asAgentString(
    thread['agentName'] ?? response['agentName'],
  );
  final rawTurns = thread['turns'] ?? response['turns'];
  if (rawTurns is! List) {
    return const <ChatMessageModel>[];
  }
  final chronological = <ChatMessageModel>[];
  final effectiveActiveTurnId =
      activeTurnId ??
      (active ? _remoteCodexLatestTurnIdFromThreadResponse(response) : null);
  var seq = 0;
  for (var turnIndex = 0; turnIndex < rawTurns.length; turnIndex += 1) {
    final turn = _asAgentMap(rawTurns[turnIndex]);
    if (turn == null) {
      continue;
    }
    final turnId =
        _remoteCodexTurnIdAt(rawTurns, turnIndex) ?? 'turn-$turnIndex';
    final isActiveTurn =
        active &&
        ((effectiveActiveTurnId != null && turnId == effectiveActiveTurnId) ||
            (effectiveActiveTurnId == null &&
                turnIndex == rawTurns.length - 1));
    final turnStartedAt =
        _remoteCodexTimeValueMs(turn['startedAt'] ?? turn['started_at']) ??
        DateTime.now().millisecondsSinceEpoch;
    final rawItems = _remoteCodexHistoricalItemsFromTurn(turn);
    if (rawItems.isEmpty) {
      continue;
    }
    for (var itemIndex = 0; itemIndex < rawItems.length; itemIndex += 1) {
      final item = rawItems[itemIndex];
      final itemType = canonicalAgentItemType(_asAgentString(item['type']));
      final itemId =
          _asAgentString(item['id']) ??
          _asAgentString(item['callId']) ??
          _asAgentString(item['call_id']) ??
          '$turnId-${_remoteCodexStableItemKey(item)}';
      final createdAt = DateTime.fromMillisecondsSinceEpoch(
        (_remoteCodexTimeValueMs(
                  item['createdAt'] ??
                      item['created_at'] ??
                      item['startedAt'] ??
                      item['started_at'],
                ) ??
                turnStartedAt) +
            itemIndex,
      );
      if (itemType == 'userMessage') {
        final userContent = _remoteCodexExtractUserMessageContent(
          item['content'] ??
              item['text'] ??
              item['message'] ??
              item['input'] ??
              item['text_elements'] ??
              item['parts'],
        );
        if (userContent.text.trim().isEmpty &&
            userContent.attachments.isEmpty) {
          continue;
        }
        final content = <String, dynamic>{
          'text': userContent.text,
          'id': '$itemId-agent-user',
        };
        if (userContent.attachments.isNotEmpty) {
          content['attachments'] = userContent.attachments;
        }
        chronological.add(
          ChatMessageModel(
            id: '$itemId-agent-user',
            type: 1,
            user: 1,
            content: content,
            createAt: createdAt,
          ),
        );
        continue;
      }
      if (itemType == 'agentMessage') {
        final text = _remoteCodexExtractText(
          item['text'] ?? item['message'] ?? item['content'],
        );
        if (text.trim().isEmpty) {
          continue;
        }
        seq += 1;
        final messageId = '$itemId-agent-message';
        final isFinal = !isActiveTurn;
        chronological.add(
          ChatMessageModel(
            id: messageId,
            type: 1,
            user: 2,
            content: {'text': text, 'id': messageId},
            createAt: createdAt,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: 'text_snapshot',
              parentTaskId: turnId,
              entryId: messageId,
              isFinal: isFinal,
            ),
          ),
        );
        continue;
      }
      if (itemType == 'reasoning') {
        final text = _remoteCodexExtractText(
          item['summary'] ?? item['text'] ?? item['content'],
        );
        if (text.trim().isEmpty && !isActiveTurn) {
          continue;
        }
        seq += 1;
        final cardId = '$itemId-agent-thinking';
        // Reasoning items only collapse once the entire turn ends. While the
        // turn is active, all reasoning cards stay in "正在思考" + expanded —
        // even if a per-item status flips to "completed" mid-turn.
        final isLoading = isActiveTurn;
        final stage = isLoading
            ? ThinkingStage.thinking.value
            : ThinkingStage.complete.value;
        chronological.add(
          ChatMessageModel.cardMessage(
            {
              'type': 'deep_thinking',
              'isLoading': isLoading,
              'thinkingContent': text,
              'stage': stage,
              'taskID': turnId,
              'cardId': cardId,
              'startTime': createdAt.millisecondsSinceEpoch,
              'endTime': isLoading ? null : createdAt.millisecondsSinceEpoch,
              'isCollapsible': !isLoading,
            },
            id: cardId,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: 'thinking_snapshot',
              parentTaskId: turnId,
              entryId: cardId,
              isFinal: !isLoading,
            ),
          ).copyWith(createAt: createdAt),
        );
        continue;
      }
      if (_remoteCodexHistoricalRequestItemTypes.contains(itemType)) {
        seq += 1;
        final requestKind = itemType == 'requestApproval'
            ? 'approval'
            : 'user_input';
        final question = _remoteCodexHistoricalFirstQuestion(item);
        final cardSuffix = requestKind == 'approval'
            ? 'approval'
            : 'user-input';
        final cardId = '$itemId-agent-$cardSuffix';
        final title = requestKind == 'approval'
            ? _remoteCodexHistoricalApprovalTitle(item)
            : question.title;
        final detail = requestKind == 'approval'
            ? _remoteCodexHistoricalApprovalDetail(item)
            : question.detail;
        final status = _remoteCodexHistoricalRequestStatus(
          item,
          requestKind: requestKind,
        );
        chronological.add(
          ChatMessageModel.cardMessage(
            <String, dynamic>{
              'type': kAgentRequestCardType,
              'taskId': turnId,
              'requestId':
                  _asAgentString(item['requestId']) ??
                  _asAgentString(item['request_id']) ??
                  _asAgentString(item['id']) ??
                  itemId,
              'requestKind': requestKind,
              'title': title,
              'detail': detail,
              if (requestKind == 'user_input') 'questionId': question.id,
              'rawParamsJson': _safeAgentJson(item),
              'status': status,
              'cardId': cardId,
              'startTime': createdAt.millisecondsSinceEpoch,
            },
            id: cardId,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: requestKind == 'approval'
                  ? 'permission_required'
                  : 'clarify_required',
              parentTaskId: turnId,
              entryId: cardId,
              isFinal: status != 'pending',
            ),
          ).copyWith(createAt: createdAt),
        );
        continue;
      }
      if (_remoteCodexHistoricalToolOutputItemTypes.contains(itemType)) {
        final outputText = _remoteCodexRawOutputText(item).trimRight();
        final callId =
            _asAgentString(item['callId']) ?? _asAgentString(item['call_id']);
        final existingIndex = callId == null
            ? -1
            : _remoteCodexFindToolMessageIndexForCallId(chronological, callId);
        if (existingIndex != -1) {
          final existing = chronological[existingIndex];
          final existingCardData = Map<String, dynamic>.from(
            existing.cardData ?? const <String, dynamic>{},
          );
          final existingToolType = (existingCardData['toolType'] ?? '')
              .toString();
          final terminalOutput = existingToolType == 'terminal'
              ? [
                  (existingCardData['terminalOutput'] ?? '')
                      .toString()
                      .trimRight(),
                  outputText,
                ].where((part) => part.isNotEmpty).join('\n')
              : (existingCardData['terminalOutput'] ?? '').toString();
          final summary = outputText.isNotEmpty
              ? _truncateAgentText(outputText, 96)
              : (existingCardData['summary'] ?? '').toString();
          existingCardData.addAll(<String, dynamic>{
            'status': 'success',
            'summary': summary,
            'progress': summary,
            'resultPreviewJson': _safeAgentJson(item['output'] ?? item),
            'rawResultJson': _safeAgentJson(item),
            'terminalOutput': terminalOutput,
            'terminalOutputDelta': '',
            'showTerminalOutput':
                terminalOutput.isNotEmpty || existingToolType == 'terminal',
          });
          final existingSeq = _asAgentInt(existing.streamMeta?['seq']) ?? seq;
          chronological[existingIndex] = existing.copyWith(
            content: {'cardData': existingCardData, 'id': existing.id},
            streamMeta: ensureAgentStreamMessageMeta(
              existing.streamMeta,
              seq: existingSeq,
              roundIndex: existingSeq,
              kind: 'tool_completed',
              parentTaskId: turnId,
              entryId: existing.id,
              isFinal: true,
            ),
          );
          continue;
        }
        seq += 1;
        final outputItemId = itemId.startsWith('$turnId-item-')
            ? '$turnId-${_remoteCodexStableItemKey(item)}'
            : itemId;
        final toolInfo = normalizeAgentToolCall(
          item,
          itemType: itemType,
          fallbackToolType: itemType == 'tool_search_output'
              ? 'search'
              : 'tool',
          fallbackStatus: 'success',
        );
        final toolKind = agentToolCardSuffix(
          toolInfo.toolType,
          itemType: itemType,
        );
        final cardId = '$outputItemId-agent-$toolKind';
        final summary = outputText.isNotEmpty
            ? _truncateAgentText(outputText, 96)
            : toolInfo.summary;
        chronological.add(
          ChatMessageModel.cardMessage(
            <String, dynamic>{
              'type': 'agent_tool_summary',
              'uiStyle': kAgentToolUiStyle,
              'taskId': turnId,
              'toolName': toolInfo.toolName,
              'displayName': toolInfo.displayName,
              'toolTitle': toolInfo.toolTitle,
              'cardId': cardId,
              'toolType': toolInfo.toolType,
              if (toolInfo.serverName != null)
                'serverName': toolInfo.serverName,
              'status': 'success',
              'summary': summary,
              'progress': summary,
              'argsJson': toolInfo.argsJson,
              'resultPreviewJson': toolInfo.resultPreviewJson,
              'rawResultJson': toolInfo.rawResultJson,
              'terminalOutput': toolInfo.toolType == 'terminal'
                  ? outputText
                  : '',
              'terminalOutputDelta': '',
              'showTerminalOutput': toolInfo.toolType == 'terminal',
              'showRawResult': true,
            },
            id: cardId,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: 'tool_completed',
              parentTaskId: turnId,
              entryId: cardId,
              isFinal: true,
            ),
          ).copyWith(createAt: createdAt),
        );
        continue;
      }
      if (_remoteCodexHistoricalToolItemTypes.contains(itemType)) {
        seq += 1;
        final toolInfo = normalizeAgentToolCall(
          item,
          itemType: itemType,
          fallbackStatus: 'success',
        );
        final toolKind = agentToolCardSuffix(
          toolInfo.toolType,
          itemType: itemType,
        );
        final cardId = '$itemId-agent-$toolKind';
        final itemActivity = _remoteCodexActivityFromValue(
          item['status'] ?? item['state'],
        );
        final isRunning = isActiveTurn && itemActivity?.active != false;
        final normalizedStatus = toolInfo.status == 'running' && !isRunning
            ? 'success'
            : toolInfo.status;
        final status = isRunning ? 'running' : normalizedStatus;
        final toolTitle = toolInfo.toolTitle;
        final summary = _remoteCodexExtractText(
          item['summary'] ??
              item['status'] ??
              item['output'] ??
              item['text'] ??
              item['content'],
        );
        final rawJson = toolInfo.rawResultJson.isNotEmpty
            ? toolInfo.rawResultJson
            : _safeAgentJson(item);
        final terminalOutput = toolInfo.terminalOutput.isNotEmpty
            ? toolInfo.terminalOutput
            : _remoteCodexExtractText(item['output']);
        final diffText = toolInfo.toolType == 'file'
            ? extractAgentDiffText(
                    item,
                    outputText: terminalOutput,
                    progress: summary,
                    summary: summary,
                  ) ??
                  ''
            : '';
        final diffSummary = diffText.isEmpty
            ? null
            : parseAgentDiffText(diffText);
        final diffPreview = diffSummary == null
            ? ''
            : summarizeAgentDiff(diffSummary);
        final effectiveSummary = toolKind == 'file' && diffPreview.isNotEmpty
            ? diffPreview
            : summary.isNotEmpty
            ? summary
            : toolInfo.summary;
        final effectiveProgress = toolKind == 'file' && diffPreview.isNotEmpty
            ? diffPreview
            : toolInfo.progress.isNotEmpty
            ? toolInfo.progress
            : summary;
        final filePath = toolInfo.toolType == 'file'
            ? extractAgentDiffPath(item) ??
                  (diffSummary?.primaryPath.trim().isNotEmpty == true
                      ? diffSummary!.primaryPath
                      : null)
            : null;
        final cardData = <String, dynamic>{
          'type': 'agent_tool_summary',
          'uiStyle': kAgentToolUiStyle,
          'taskId': turnId,
          'toolName': toolInfo.toolName,
          'displayName': toolInfo.displayName,
          'toolTitle': toolTitle,
          'cardId': cardId,
          'toolType': toolInfo.toolType,
          if (toolInfo.serverName != null) 'serverName': toolInfo.serverName,
          'status': status,
          'summary': effectiveSummary,
          'progress': effectiveProgress,
          'argsJson': toolInfo.argsJson,
          'resultPreviewJson': toolInfo.resultPreviewJson,
          'rawResultJson': rawJson,
          'terminalOutput': terminalOutput,
          'terminalOutputDelta': '',
          'showTerminalOutput': toolInfo.toolType == 'terminal',
          'showRawResult': true,
        };
        if (toolInfo.toolType == 'file') {
          cardData.addAll(<String, dynamic>{
            'diffText': diffText,
            'showDiff': diffText.isNotEmpty,
            'filePath': filePath ?? '',
            'changedFiles': diffSummary?.changedFileCount ?? 0,
            'additions': diffSummary?.additions ?? 0,
            'deletions': diffSummary?.deletions ?? 0,
          });
        }
        chronological.add(
          ChatMessageModel.cardMessage(
            cardData,
            id: cardId,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: isRunning ? 'tool_progress' : 'tool_completed',
              parentTaskId: turnId,
              entryId: cardId,
              isFinal: !isRunning,
            ),
          ).copyWith(createAt: createdAt),
        );
      }
    }
  }
  final messages = chronological.reversed.toList(growable: false);
  return _normalizeAgentLoadingThinkingCards(
        messages,
        activeTaskId: effectiveActiveTurnId,
        isAiResponding: active,
      )
      .map(
        (message) => _withAcpAgentIdentity(
          message,
          agentId: agentId,
          agentName: agentName,
        ),
      )
      .toList(growable: false);
}

ChatMessageModel _withAcpAgentIdentity(
  ChatMessageModel message, {
  required String agentId,
  String? agentName,
}) {
  if (message.user == 1 || message.agentId != null) {
    return message;
  }
  final content = Map<String, dynamic>.from(
    message.content ?? const <String, dynamic>{},
  );
  content['agentId'] = agentId;
  if (agentName != null) {
    content['agentName'] = agentName;
  }
  final cardData = message.cardData;
  if (cardData != null) {
    content['cardData'] = <String, dynamic>{
      ...cardData,
      'agentId': agentId,
      if (agentName != null) 'agentName': agentName,
    };
  }
  return message.copyWith(content: content);
}

@visibleForTesting
List<ChatMessageModel> remoteCodexMessagesFromThreadResponseForTesting(
  Map<String, dynamic> response, {
  bool active = false,
  String? activeTurnId,
}) {
  return _remoteCodexMessagesFromThreadResponse(
    response,
    active: active,
    activeTurnId: activeTurnId,
  );
}

@visibleForTesting
String? remoteCodexActiveTurnIdFromThreadResponseForTesting(
  Map<String, dynamic> response,
) {
  return _remoteCodexActiveTurnIdFromThreadResponse(response);
}

Map<String, dynamic>? _asAgentMap(dynamic value) {
  if (value is! Map) {
    return null;
  }
  return value.map((key, nestedValue) {
    return MapEntry(key.toString(), nestedValue);
  });
}
