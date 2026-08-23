part of 'chat_conversation_runtime_coordinator.dart';

extension _ChatRuntimeToolSupport on ChatConversationRuntimeCoordinator {
  void _upsertToolCard({
    required ChatConversationRuntimeState runtime,
    required String taskId,
    required String cardId,
    required AgentToolEventData event,
    required String status,
    required String summary,
    required String progress,
    required String resultPreviewJson,
    required String rawResultJson,
    String? reasoningContent,
    Map<String, dynamic>? streamMeta,
  }) {
    final index = runtime.messages.indexWhere((msg) => msg.id == cardId);
    final existingCardData = index == -1
        ? const <String, dynamic>{}
        : Map<String, dynamic>.from(
            runtime.messages[index].cardData ?? const {},
          );
    final existingTerminalOutput = (existingCardData['terminalOutput'] ?? '')
        .toString();
    final isFileChangeTool = _isAgentFileChangeTool(event, existingCardData);
    final effectiveToolType = isFileChangeTool ? 'file' : event.toolType;
    final terminalOutput = effectiveToolType == 'terminal'
        ? _resolveTerminalOutput(existing: existingTerminalOutput, event: event)
        : '';
    final diffSource = _agentToolDiffSource(
      event,
      resultPreviewJson: resultPreviewJson,
      rawResultJson: rawResultJson,
      summary: summary,
      progress: progress,
    );
    final diffText = isFileChangeTool
        ? _resolveAgentFileDiffText(
            existingCardData: existingCardData,
            source: diffSource,
            outputText: terminalOutput,
            progress: progress,
            summary: summary,
          )
        : '';
    final diffSummary = diffText.isEmpty ? null : parseAgentDiffText(diffText);
    final diffPreview = diffSummary == null
        ? ''
        : summarizeAgentDiff(diffSummary);
    final effectiveSummary = isFileChangeTool && diffPreview.isNotEmpty
        ? diffPreview
        : summary.isNotEmpty
        ? summary
        : (existingCardData['summary'] ?? '').toString();
    final effectiveProgress = isFileChangeTool && diffPreview.isNotEmpty
        ? diffPreview
        : progress.isNotEmpty
        ? progress
        : (existingCardData['progress'] ?? '').toString();
    final filePath = isFileChangeTool
        ? extractAgentDiffPath(diffSource) ??
              (diffSummary?.primaryPath.trim().isNotEmpty == true
                  ? diffSummary!.primaryPath
                  : null) ??
              (existingCardData['filePath'] ?? '').toString()
        : '';
    String? runIdFromRawInput(Object? rawInput) {
      if (rawInput is Map) {
        final value = rawInput['run_id'] ?? rawInput['runId'];
        final normalized = value?.toString().trim() ?? '';
        return normalized.isEmpty ? null : normalized;
      }
      if (rawInput is String && rawInput.trim().isNotEmpty) {
        try {
          return runIdFromRawInput(jsonDecode(rawInput));
        } catch (_) {
          return null;
        }
      }
      return null;
    }

    final existingRunIdValue =
        existingCardData['runId'] ?? existingCardData['run_id'];
    final existingRunId = existingRunIdValue == null
        ? null
        : existingRunIdValue.toString().trim();
    final runId = runIdFromRawInput(event.raw['rawInput']) ??
        runIdFromRawInput(event.raw['raw_input']) ??
        (existingRunId?.isNotEmpty == true ? existingRunId : null);
    final cardData = <String, dynamic>{
      'type': 'agent_tool_summary',
      'uiStyle': event.uiStyle.isNotEmpty
          ? event.uiStyle
          : (existingCardData['uiStyle'] ?? '').toString(),
      'taskId': taskId,
      if (runId != null && runId.isNotEmpty) 'runId': runId,
      'toolName': event.toolName,
      'displayName': event.displayName,
      'toolTitle': event.toolTitle.isNotEmpty
          ? event.toolTitle
          : (existingCardData['toolTitle'] ?? '').toString(),
      'cardId': event.cardId.isNotEmpty
          ? event.cardId
          : (existingCardData['cardId'] ?? cardId).toString(),
      'toolType': effectiveToolType,
      'serverName': event.serverName,
      'status': status,
      'reasoning_content':
          _normalizeReasoningContent(reasoningContent) ??
          (existingCardData['reasoning_content'] ?? '').toString(),
      'summary': effectiveSummary,
      'progress': effectiveProgress,
      'subagentStatusText': event.subagentStatusText.isNotEmpty
          ? event.subagentStatusText
          : (existingCardData['subagentStatusText'] ?? '').toString(),
      'subagentEvents': _mergeSubagentEvents(
        existingCardData['subagentEvents'],
        event.subagentEvents,
      ),
      'argsJson': event.argsJson.isNotEmpty
          ? event.argsJson
          : (existingCardData['argsJson'] ?? '').toString(),
      'resultPreviewJson': resultPreviewJson.isNotEmpty
          ? resultPreviewJson
          : (existingCardData['resultPreviewJson'] ?? '').toString(),
      'rawResultJson': rawResultJson.isNotEmpty
          ? rawResultJson
          : (existingCardData['rawResultJson'] ?? '').toString(),
      'terminalOutput': terminalOutput,
      'terminalOutputDelta': event.terminalOutputDelta,
      'terminalSessionId':
          event.terminalSessionId ?? existingCardData['terminalSessionId'],
      'terminalStreamState': event.terminalStreamState.isNotEmpty
          ? event.terminalStreamState
          : (existingCardData['terminalStreamState'] ?? '').toString(),
      'workspaceId': event.workspaceId ?? existingCardData['workspaceId'],
      'interruptedBy': event.interruptedBy ?? existingCardData['interruptedBy'],
      'interruptionReason':
          event.interruptionReason ?? existingCardData['interruptionReason'],
      'artifacts': event.artifacts.isNotEmpty
          ? event.artifacts
          : (existingCardData['artifacts'] ?? const []),
      'actions': event.actions.isNotEmpty
          ? event.actions
          : (existingCardData['actions'] ?? const []),
      'success': event.success,
      'showTerminalOutput': effectiveToolType == 'terminal',
      'showRawResult': event.rawResultJson.isNotEmpty,
      'showArtifactAction': event.artifacts.isNotEmpty,
      'showScheduleAction': effectiveToolType == 'schedule',
      'showAlarmAction': effectiveToolType == 'alarm',
    };
    if (isFileChangeTool) {
      cardData.addAll(<String, dynamic>{
        'diffText': diffText,
        'showDiff': diffText.isNotEmpty,
        'filePath': filePath,
        'changedFiles': diffSummary?.changedFileCount ?? 0,
        'additions': diffSummary?.additions ?? 0,
        'deletions': diffSummary?.deletions ?? 0,
      });
    }

    if (index == -1) {
      runtime.messages.insert(
        0,
        ChatMessageModel.cardMessage(
          cardData,
          id: cardId,
          streamMeta: ensureAgentStreamMessageMeta(streamMeta, entryId: cardId),
        ),
      );
    } else {
      runtime.messages[index] = runtime.messages[index].copyWith(
        content: {'cardData': cardData, 'id': cardId},
        streamMeta: ensureAgentStreamMessageMeta(
          streamMeta ?? runtime.messages[index].streamMeta,
          entryId: cardId,
        ),
      );
    }
  }

  List<Map<String, dynamic>> _mergeSubagentEvents(
    dynamic existingRaw,
    List<Map<String, dynamic>> incomingEvents,
  ) {
    final merged = <Map<String, dynamic>>[];
    final seen = <String>{};

    void addEvent(Map<dynamic, dynamic> rawEvent) {
      final event = rawEvent.map<String, dynamic>(
        (key, value) => MapEntry(key.toString(), value),
      );
      final key = _subagentEventIdentity(event);
      if (!seen.add(key)) {
        return;
      }
      merged.add(event);
    }

    if (existingRaw is List) {
      for (final item in existingRaw.whereType<Map>()) {
        addEvent(item);
      }
    }
    for (final event in incomingEvents) {
      addEvent(event);
    }
    // 折叠流式累积事件: 同一个 subagent (按 subagentId 或 taskIndex 分组)
    // 的同种流式 kind (thinking / message) 只保留 seq 最大的一条。
    // Kotlin 端为了实现"实时滚动"效果会每 ~32 字符 emit 一次 thinking 事件,
    // 每条 seq 都不同,id-based 去重保留不了它们。展开后会显示"每行字数递增"。
    final folded = _foldStreamingSubagentEvents(merged);
    folded.sort((left, right) {
      final leftSeq = _asInt(left['seq']);
      final rightSeq = _asInt(right['seq']);
      if (leftSeq != null && rightSeq != null && leftSeq != rightSeq) {
        return leftSeq.compareTo(rightSeq);
      }
      final leftCreatedAt = _asInt(left['createdAt']) ?? 0;
      final rightCreatedAt = _asInt(right['createdAt']) ?? 0;
      if (leftCreatedAt != rightCreatedAt) {
        return leftCreatedAt.compareTo(rightCreatedAt);
      }
      return _subagentEventIdentity(
        left,
      ).compareTo(_subagentEventIdentity(right));
    });
    return folded;
  }

  static const Set<String> _streamingSubagentKinds = <String>{
    'thinking',
    'message',
  };

  List<Map<String, dynamic>> _foldStreamingSubagentEvents(
    List<Map<String, dynamic>> events,
  ) {
    final latestByGroup = <String, Map<String, dynamic>>{};
    final result = <Map<String, dynamic>>[];
    for (final event in events) {
      final kind = (event['kind'] ?? '').toString();
      if (!_streamingSubagentKinds.contains(kind)) {
        result.add(event);
        continue;
      }
      final groupKey = _subagentStreamingGroupKey(event, kind);
      final existing = latestByGroup[groupKey];
      if (existing == null) {
        latestByGroup[groupKey] = event;
        continue;
      }
      final existingSeq = _asInt(existing['seq']) ?? -1;
      final newSeq = _asInt(event['seq']) ?? -1;
      if (newSeq >= existingSeq) {
        latestByGroup[groupKey] = event;
      }
    }
    result.addAll(latestByGroup.values);
    return result;
  }

  String _subagentStreamingGroupKey(Map<String, dynamic> event, String kind) {
    final subagentId = (event['subagentId'] ?? '').toString().trim();
    if (subagentId.isNotEmpty) {
      return 'sub:$subagentId|$kind';
    }
    final taskIndex = event['taskIndex'];
    if (taskIndex != null) {
      return 'task:$taskIndex|$kind';
    }
    return 'global|$kind';
  }

  String _subagentEventIdentity(Map<String, dynamic> event) {
    final id = (event['id'] ?? '').toString().trim();
    if (id.isNotEmpty) {
      return id;
    }
    return [
      event['seq'],
      event['kind'],
      event['taskIndex'],
      event['summary'],
      event['toolName'],
    ].map((item) => item?.toString() ?? '').join('|');
  }

  int? _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse((value ?? '').toString());
  }

  String _resolveTerminalOutput({
    required String existing,
    required AgentToolEventData event,
  }) {
    if (event.terminalOutput.isNotEmpty) {
      return _trimTerminalOutput(event.terminalOutput);
    }
    if (event.terminalOutputDelta.isNotEmpty) {
      return _trimTerminalOutput(existing + event.terminalOutputDelta);
    }
    return existing;
  }

  bool _isAgentFileChangeTool(
    AgentToolEventData event,
    Map<String, dynamic> existingCardData,
  ) {
    final toolType = event.toolType.trim();
    if (toolType == 'file' ||
        (existingCardData['toolType'] ?? '').toString().trim() == 'file') {
      return true;
    }
    if (canonicalAgentToolName(event.toolName) == 'agent.file') {
      return true;
    }
    if (_valueHasFileChangeType(event.raw)) {
      return true;
    }
    return _jsonHasFileChangeType(event.argsJson) ||
        _jsonHasFileChangeType(event.rawResultJson) ||
        _jsonHasFileChangeType(event.resultPreviewJson);
  }

  bool _jsonHasFileChangeType(String raw) {
    final trimmed = raw.trim();
    if (trimmed.isEmpty) {
      return false;
    }
    try {
      final decoded = jsonDecode(trimmed);
      return _valueHasFileChangeType(decoded);
    } catch (_) {
      return false;
    }
  }

  bool _valueHasFileChangeType(dynamic value) {
    if (value is Map) {
      final map = value.map((key, nested) => MapEntry(key.toString(), nested));
      final type = (map['type'] ?? '').toString();
      if (type == 'fileChange') {
        return true;
      }
      return map.values.any(_valueHasFileChangeType);
    }
    if (value is Iterable) {
      return value.any(_valueHasFileChangeType);
    }
    return false;
  }

  Map<String, dynamic> _agentToolDiffSource(
    AgentToolEventData event, {
    required String resultPreviewJson,
    required String rawResultJson,
    required String summary,
    required String progress,
  }) {
    return <String, dynamic>{
      ...event.raw,
      'toolName': event.toolName,
      'toolType': event.toolType,
      'argsJson': event.argsJson,
      'resultPreviewJson': resultPreviewJson,
      'rawResultJson': rawResultJson,
      'summary': summary,
      'progress': progress,
    };
  }

  String _resolveAgentFileDiffText({
    required Map<String, dynamic> existingCardData,
    required Map<String, dynamic> source,
    required String outputText,
    required String progress,
    required String summary,
  }) {
    final current = extractAgentDiffText(
      source,
      outputText: outputText,
      progress: progress,
      summary: summary,
    );
    if (current != null && current.trim().isNotEmpty) {
      return current;
    }
    return (existingCardData['diffText'] ?? '').toString().trim();
  }

  String _resolveToolStatus(AgentToolEventData event) {
    final normalized = event.status.trim().toLowerCase();
    if (normalized.isNotEmpty) {
      return normalized;
    }
    return event.success ? 'success' : 'error';
  }
}
