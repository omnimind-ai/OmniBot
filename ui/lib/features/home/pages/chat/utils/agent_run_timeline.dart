import 'dart:convert';

import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_message_kinds.dart';

class AgentRunTimelineEntry {
  const AgentRunTimelineEntry.message(this.message) : group = null;

  const AgentRunTimelineEntry.group(this.group) : message = null;

  final ChatMessageModel? message;
  final AgentRunTimelineGroup? group;

  bool get isMessage => message != null;

  bool get isUserMessage => message?.user == 1;

  String get key => message?.id ?? 'agent-run-${group!.taskId}';
}

/// Whether an agent turn is still producing output.
///
/// This is derived at render time from the set of in-flight task ids, never
/// from a persisted flag. A turn that is not in that set has ended — whether it
/// ended cleanly, was cancelled, or died with the process. Gating the run
/// header on a persisted `isFinal` boolean instead used to mean that one lost
/// bit removed the agent avatar, the "processed" label, and the fold all at
/// once.
enum AgentRunStatus { running, finished }

/// One chronological slice of a turn: either a message that stays in the
/// conversation, or a contiguous run of process cards that folds as a unit.
///
/// A turn renders in the order the agent produced it. `runtime.messages` is
/// kept newest-first by every insert site, so list position is that order.
/// `streamMeta.seq` is not: a snapshot card — the built-in assistant's text
/// and thinking cards — rewrites its sequence on every delta, so whichever
/// card in a round streamed longest ends up holding the round's highest
/// sequence. Ordering by it puts a round's thinking card after the tools it
/// preceded.
class AgentRunTimelineSegment {
  const AgentRunTimelineSegment._(this.messages, this.isProcess);

  AgentRunTimelineSegment.visible(ChatMessageModel message)
    : this._(<ChatMessageModel>[message], false);

  AgentRunTimelineSegment.process(List<ChatMessageModel> messages)
    : this._(messages, true);

  /// Oldest first.
  final List<ChatMessageModel> messages;

  /// Whether this slice folds away with the run header.
  final bool isProcess;

  ChatMessageModel get message => messages.first;
}

class AgentRunTimelineGroup {
  const AgentRunTimelineGroup({
    required this.taskId,
    required this.status,
    required this.agentId,
    required this.startedAt,
    this.finishedAt,
    required this.segmentsOldestFirst,
  });

  final String taskId;

  /// Canonical UI run identity. [taskId] remains as a source-compatible
  /// compatibility name for expansion state and older callers.
  String get runId => taskId;
  final AgentRunStatus status;

  /// Resolved once, when the group is built, so the live and restored render
  /// paths cannot disagree about which agent produced the turn.
  final String agentId;

  /// Run boundaries, carried on the group so the header does not have to
  /// re-derive elapsed time by scanning message timestamps.
  final DateTime startedAt;
  final DateTime? finishedAt;

  /// The turn, in arrival order.
  final List<AgentRunTimelineSegment> segmentsOldestFirst;

  bool get isRunning => status == AgentRunStatus.running;

  bool get isEmpty => segmentsOldestFirst.isEmpty;

  bool get hasProcessMessages =>
      segmentsOldestFirst.any((segment) => segment.isProcess);

  List<ChatMessageModel> get allMessagesOldestFirst => <ChatMessageModel>[
    for (final segment in segmentsOldestFirst) ...segment.messages,
  ];

  List<ChatMessageModel> get visibleMessagesOldestFirst => <ChatMessageModel>[
    for (final segment in segmentsOldestFirst)
      if (!segment.isProcess) ...segment.messages,
  ];

  List<ChatMessageModel> get processMessagesOldestFirst => <ChatMessageModel>[
    for (final segment in segmentsOldestFirst)
      if (segment.isProcess) ...segment.messages,
  ];

  List<ChatMessageModel> get visibleMessagesNewestFirst =>
      visibleMessagesOldestFirst.reversed.toList(growable: false);

  List<ChatMessageModel> get processMessagesNewestFirst =>
      processMessagesOldestFirst.reversed.toList(growable: false);

  int get thinkingCount => processMessagesOldestFirst
      .where((message) => _cardType(message) == 'deep_thinking')
      .length;

  int get toolCount => processMessagesOldestFirst
      .where((message) => _cardType(message) == 'agent_tool_summary')
      .length;

  /// Swaps in the newest instance of each message without re-deriving the
  /// turn's shape, so a content-only stream update cannot reorder it.
  AgentRunTimelineGroup withRefreshedMessages(
    Map<String, ChatMessageModel> latestById,
  ) {
    List<ChatMessageModel> refresh(List<ChatMessageModel> source) => source
        .map((message) => latestById[message.id] ?? message)
        .toList(growable: false);

    return AgentRunTimelineGroup(
      taskId: taskId,
      status: status,
      agentId: agentId,
      startedAt: startedAt,
      finishedAt: finishedAt,
      segmentsOldestFirst: segmentsOldestFirst
          .map(
            (segment) => segment.isProcess
                ? AgentRunTimelineSegment.process(refresh(segment.messages))
                : AgentRunTimelineSegment.visible(
                    _projectAgentRequestMessage(
                      latestById[segment.message.id] ?? segment.message,
                    ),
                  ),
          )
          .toList(growable: false),
    );
  }
}

List<AgentRunTimelineEntry> buildAgentRunTimelineEntries(
  List<ChatMessageModel> messages, {
  Set<String> activeTaskIds = const <String>{},
  String? conversationAgentId,
}) {
  if (messages.isEmpty) {
    return const <AgentRunTimelineEntry>[];
  }

  // ACP requests are transport interactions, not large forms. Keep the
  // original card in runtime state so the host can answer it, but project it
  // to the shared compact request card for the timeline. User Input still
  // uses the one bottom composer; no nested field is created here.
  final renderMessages = messages
      .map(_projectAgentRequestMessage)
      .toList(growable: false);

  final normalizedActiveTaskIds = activeTaskIds
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toSet();
  final emittedTaskIds = <String>{};
  final entries = <AgentRunTimelineEntry>[];

  for (final message in renderMessages) {
    // Artifact metadata is already carried by the corresponding tool card and
    // powers the activity strip above the composer. Rendering the reducer's
    // standalone compatibility card as well puts a large duplicate file card
    // between the user's prompt and the Agent response.
    if (_cardType(message) == 'artifact_card') {
      continue;
    }
    final taskId = agentRunId(message);
    if (taskId == null) {
      entries.add(AgentRunTimelineEntry.message(message));
      continue;
    }
    if (emittedTaskIds.contains(taskId)) {
      if (!_isAgentRunCandidateMessage(message)) {
        entries.add(AgentRunTimelineEntry.message(message));
      }
      continue;
    }

    final group = _buildTimelineGroup(
      renderMessages,
      taskId: taskId,
      isActive: normalizedActiveTaskIds.contains(taskId),
      conversationAgentId: conversationAgentId,
    );
    if (group == null) {
      entries.add(AgentRunTimelineEntry.message(message));
      continue;
    }

    entries.add(AgentRunTimelineEntry.group(group));
    emittedTaskIds.add(taskId);
  }

  // A turn that has been dispatched but has not streamed anything yet owns no
  // messages, so the loop above never reaches it. Surface exactly ONE header
  // for that state — "an agent is working and has produced nothing" is a single
  // condition, not one per in-flight id. Emitting one per id is what used to
  // stack up a column of avatars and "processing" rows.
  final hasRunningGroup = entries.any(
    (entry) => entry.group?.isRunning ?? false,
  );
  if (!hasRunningGroup) {
    final pendingTaskId = normalizedActiveTaskIds
        .where((taskId) => !emittedTaskIds.contains(taskId))
        .lastOrNull;
    if (pendingTaskId != null) {
      entries.insert(
        0,
        AgentRunTimelineEntry.group(
          AgentRunTimelineGroup(
            taskId: pendingTaskId,
            status: AgentRunStatus.running,
            agentId: resolveAgentRunAgentId(
              turnMessages: const <ChatMessageModel>[],
              conversationAgentId: conversationAgentId,
            ),
            startedAt: _pendingRunStartedAt(renderMessages, pendingTaskId),
            segmentsOldestFirst: const <AgentRunTimelineSegment>[],
          ),
        ),
      );
    }
  }

  return _stabilizeLegacyTurnEntriesNewestFirst(entries);
}

ChatMessageModel _projectAgentRequestMessage(ChatMessageModel message) {
  if (!isAgentRequestMessage(message)) {
    return message;
  }
  final card = message.cardData ?? const <String, dynamic>{};
  final kind = (card['requestKind'] ?? '').toString().trim();
  final rawParams = _decodeRequestParams(card['rawParamsJson']);
  final schema = _requestSchema(rawParams);
  final firstField = _firstSchemaField(schema);
  final storedTitle = (card['title'] ?? '').toString().trim();
  final title = _looksGenericInputTitle(storedTitle) && firstField != null
      ? _stringValue(firstField['title']) ?? storedTitle
      : storedTitle;
  final storedDetail = (card['detail'] ?? '').toString().trim();
  final detail = _requestDisplayDetail(
    storedDetail: storedDetail,
    schemaField: firstField,
    title: title,
  );
  final text = detail.isEmpty || detail == title
      ? title
      : (title.isEmpty ? detail : '$title\n$detail');
  final streamMeta = <String, dynamic>{...?message.streamMeta};
  final taskId = (card['runId'] ?? card['taskId'] ?? card['taskID'])
      ?.toString()
      .trim();
  if ((streamMeta['runId']?.toString().trim() ?? '').isEmpty &&
      taskId != null &&
      taskId.isNotEmpty) {
    streamMeta['runId'] = taskId;
  }
  final displayTitle = title.isEmpty
      ? (kind == 'approval' ? 'Permission requested' : 'Agent question')
      : title;
  final displayDetail = detail.isEmpty && text != displayTitle ? text : detail;
  final projectedCard = <String, dynamic>{
    ...card,
    'type': kAgentRequestCardType,
    'simplePresentation': true,
    'title': displayTitle,
    'detail': displayDetail,
  };
  final content = <String, dynamic>{
    'cardData': projectedCard,
    'id': message.contentId ?? message.id,
    if (card['agentId'] != null) 'agentId': card['agentId'],
    if (card['agentName'] != null) 'agentName': card['agentName'],
  };
  return message.copyWith(
    type: 2,
    user: 3,
    content: content,
    streamMeta: streamMeta.isEmpty ? message.streamMeta : streamMeta,
  );
}

Map<String, dynamic>? _decodeRequestParams(dynamic raw) {
  if (raw is Map) {
    return Map<String, dynamic>.from(raw.cast<String, dynamic>());
  }
  final text = raw?.toString().trim() ?? '';
  if (text.isEmpty) return null;
  try {
    final decoded = jsonDecode(text);
    if (decoded is Map) {
      return Map<String, dynamic>.from(decoded.cast<String, dynamic>());
    }
  } catch (_) {
    // A malformed producer payload should still render its title, never the
    // raw exception or a second form.
  }
  return null;
}

Map<String, dynamic>? _requestSchema(Map<String, dynamic>? params) {
  if (params == null) return null;
  for (final key in const <String>[
    'requestedSchema',
    'requested_schema',
    'schema',
    'inputSchema',
    'input_schema',
  ]) {
    final value = _decodeRequestParams(params[key]);
    if (value != null) return value;
  }
  for (final key in const <String>['request', 'elicitation', 'params']) {
    final nested = _decodeRequestParams(params[key]);
    final schema = _requestSchema(nested);
    if (schema != null) return schema;
  }
  return params['properties'] is Map ? params : null;
}

Map<String, dynamic>? _firstSchemaField(Map<String, dynamic>? schema) {
  final properties = schema?['properties'];
  if (properties is! Map || properties.isEmpty) return null;
  final value = properties.values.first;
  return value is Map
      ? Map<String, dynamic>.from(value.cast<String, dynamic>())
      : null;
}

String? _stringValue(dynamic value) {
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

bool _looksGenericInputTitle(String value) {
  final normalized = value.toLowerCase();
  return value.isEmpty ||
      (normalized.contains('agent') &&
          (normalized.contains('input') || normalized.contains('question'))) ||
      (value.contains('需要') && value.contains('输入'));
}

String _requestDisplayDetail({
  required String storedDetail,
  required Map<String, dynamic>? schemaField,
  required String title,
}) {
  final description = _stringValue(schemaField?['description']);
  final choices = _schemaChoices(schemaField);
  final looksLikeJson =
      storedDetail.startsWith('{') ||
      storedDetail.startsWith('[') ||
      storedDetail.length > 600;
  if (looksLikeJson && description != null && description != title) {
    return _appendSchemaChoices(description, choices);
  }
  if (looksLikeJson && description == null) {
    return _appendSchemaChoices('', choices);
  }
  return _appendSchemaChoices(
    storedDetail == title ? '' : storedDetail,
    choices,
  );
}

List<String> _schemaChoices(Map<String, dynamic>? field) {
  final values = field?['oneOf'] ?? field?['enum'];
  if (values is! List) return const <String>[];
  return values
      .map((value) {
        if (value is Map) {
          return _stringValue(
            value['title'] ?? value['label'] ?? value['const'],
          );
        }
        return _stringValue(value);
      })
      .whereType<String>()
      .toList(growable: false);
}

String _appendSchemaChoices(String detail, List<String> choices) {
  if (choices.isEmpty) return detail;
  final optionLine = '可选：${choices.join('、')}';
  return detail.isEmpty ? optionLine : '$detail\n$optionLine';
}

/// Keeps the top-level timeline newest-first even if an asynchronously restored
/// Xiaowan snapshot briefly arrives oldest-first.
///
/// Built-in turns have a shared millisecond prefix (`<timestamp>-user` and
/// `<timestamp>-ai`). That gives us an exact turn boundary without guessing
/// from nearby timestamps. Only entries with that legacy identity participate,
/// so opaque ACP ids retain their reducer-defined arrival order.
List<AgentRunTimelineEntry> _stabilizeLegacyTurnEntriesNewestFirst(
  List<AgentRunTimelineEntry> entries,
) {
  final legacySlots = <int>[];
  final legacyEntries =
      <({AgentRunTimelineEntry entry, int anchor, int order})>[];
  for (var index = 0; index < entries.length; index += 1) {
    final entry = entries[index];
    final anchor = _legacyTurnAnchor(entry);
    if (anchor == null) {
      continue;
    }
    legacySlots.add(index);
    legacyEntries.add((entry: entry, anchor: anchor, order: index));
  }
  if (legacyEntries.length < 2) {
    return entries;
  }

  legacyEntries.sort((left, right) {
    final anchorCompare = right.anchor.compareTo(left.anchor);
    if (anchorCompare != 0) {
      return anchorCompare;
    }
    // `ChatMessageList` reverses this newest-first projection for display, so
    // the run must precede its user prompt here to render prompt -> response.
    final turnRankCompare = _legacyTurnNewestFirstRank(
      left.entry,
    ).compareTo(_legacyTurnNewestFirstRank(right.entry));
    if (turnRankCompare != 0) {
      return turnRankCompare;
    }
    return left.order.compareTo(right.order);
  });

  final normalized = List<AgentRunTimelineEntry>.from(entries);
  for (var index = 0; index < legacySlots.length; index += 1) {
    normalized[legacySlots[index]] = legacyEntries[index].entry;
  }
  return normalized;
}

int? _legacyTurnAnchor(AgentRunTimelineEntry entry) {
  final groupTaskId = entry.group?.taskId;
  if (groupTaskId != null) {
    return _legacyTurnAnchorFromId(groupTaskId);
  }
  final message = entry.message;
  if (message == null) {
    return null;
  }
  return _legacyTurnAnchorFromId(message.id) ??
      _legacyTurnAnchorFromId(message.contentId);
}

int _legacyTurnNewestFirstRank(AgentRunTimelineEntry entry) {
  if (entry.group != null) {
    return 0;
  }
  return entry.isUserMessage ? 1 : 0;
}

int? _legacyTurnAnchorFromId(String? raw) {
  final id = raw?.trim() ?? '';
  if (id.isEmpty) {
    return null;
  }
  final match = _legacyTurnId.firstMatch(id);
  return match == null ? null : int.tryParse(match.group(1)!);
}

final RegExp _legacyTurnId = RegExp(
  r'^(\d{13})-(?:user|ai(?:-.+)?|assistant(?:-.+)?|clarify(?:-.+)?|permission(?:-.+)?|thinking(?:-.+)?|text(?:-.+)?|tool(?:-.+)?)$',
);

/// When a dispatched-but-silent turn started.
///
/// Prefers the user message minted alongside the dispatch id (`<x>-user` for a
/// `<x>-ai` task), then the newest user message, so the elapsed counter starts
/// from when the user actually sent the prompt.
DateTime _pendingRunStartedAt(List<ChatMessageModel> messages, String taskId) {
  if (taskId.endsWith('-ai')) {
    final expectedUserId = '${taskId.substring(0, taskId.length - 3)}-user';
    for (final message in messages) {
      if (message.id == expectedUserId) {
        return message.createAt;
      }
    }
  }
  for (final message in messages) {
    if (message.user == 1) {
      return message.createAt;
    }
  }
  return DateTime.now();
}

DateTime? _boundaryTimestamp(
  List<ChatMessageModel> messages, {
  required bool earliest,
}) {
  DateTime? boundary;
  for (final message in messages) {
    final createAt = message.createAt;
    if (createAt.millisecondsSinceEpoch <= 0) {
      continue;
    }
    if (boundary == null ||
        (earliest ? createAt.isBefore(boundary) : createAt.isAfter(boundary))) {
      boundary = createAt;
    }
  }
  return boundary;
}

/// A provider tool id can be reused on a later turn. If an old persisted tool
/// snapshot survived with that id, its timestamp is not a reliable boundary
/// for the current run. Reasoning/text entries are turn-owned anchors, so use
/// them whenever available; a tool-only run still falls back to its tool
/// timestamp.
DateTime? _runBoundaryTimestamp(
  List<ChatMessageModel> messages, {
  required bool earliest,
}) {
  final contentMessages = messages
      .where((message) => _cardType(message) != 'agent_tool_summary')
      .toList(growable: false);
  return _boundaryTimestamp(contentMessages, earliest: earliest) ??
      _boundaryTimestamp(messages, earliest: earliest);
}

String? agentRunId(ChatMessageModel message) {
  final normalized = message.runId?.trim() ?? '';
  if (normalized.isNotEmpty) {
    return normalized;
  }
  if (message.user == 1) {
    return null;
  }
  return _agentTaskIdFromEntryId(message.id) ??
      _agentTaskIdFromEntryId(message.contentId);
}

/// Compatibility alias for old callers and persisted-data adapters.
String? agentRunParentTaskId(ChatMessageModel message) => agentRunId(message);

String agentRunKind(ChatMessageModel message) {
  return (message.streamMeta?['kind'] ?? '').toString().trim().toLowerCase();
}

AgentRunTimelineGroup? _buildTimelineGroup(
  List<ChatMessageModel> messages, {
  required String taskId,
  required bool isActive,
  String? conversationAgentId,
}) {
  final taskMessages = _stabilizeTaskMessagesNewestFirst(
    messages
        .where((message) => agentRunId(message) == taskId)
        .where(_isAgentRunCandidateMessage)
        .toList(growable: false),
  );
  if (taskMessages.isEmpty) {
    return null;
  }

  // Every agent turn is a group, however small. The old "needs at least two
  // messages" and "needs process messages" gates meant a plain question and
  // answer never grouped, and therefore never showed an agent avatar once it
  // came back from the database.
  final segments = _buildSegments(taskMessages);
  if (!segments.any((segment) => !segment.isProcess) && !isActive) {
    return null;
  }

  return AgentRunTimelineGroup(
    taskId: taskId,
    status: isActive ? AgentRunStatus.running : AgentRunStatus.finished,
    agentId: resolveAgentRunAgentId(
      turnMessages: taskMessages,
      conversationAgentId: conversationAgentId,
    ),
    startedAt:
        _runBoundaryTimestamp(taskMessages, earliest: true) ?? DateTime.now(),
    finishedAt: isActive
        ? null
        : _runBoundaryTimestamp(taskMessages, earliest: false),
    segmentsOldestFirst: segments,
  );
}

/// `entrySeq` is allocated once when a streamed entry is created, unlike
/// `seq`, which advances on every snapshot update. If every entry in a run has
/// a unique stable sequence, use it to recover newest-first order after a
/// snapshot replacement. Mixed/legacy ACP runs keep their list order so prose
/// interleaving remains untouched.
List<ChatMessageModel> _stabilizeTaskMessagesNewestFirst(
  List<ChatMessageModel> messages,
) {
  if (messages.length < 2) {
    return messages;
  }
  final indexed = <({ChatMessageModel message, int entrySeq, int order})>[];
  final seenSequences = <int>{};
  var hasStableEntrySequences = true;
  for (var index = 0; index < messages.length; index += 1) {
    final message = messages[index];
    final entrySeq = _wholeIntFromDynamic(message.streamMeta?['entrySeq']);
    if (entrySeq == null || !seenSequences.add(entrySeq)) {
      hasStableEntrySequences = false;
      break;
    }
    indexed.add((message: message, entrySeq: entrySeq, order: index));
  }
  // Native history deliberately keeps only the compact display stream meta.
  // Older persisted ACP snapshots therefore have no entrySeq, even though
  // their terminal frames still carry a unique seq.  Restore those snapshots
  // in newest-first seq order; otherwise a run reopens as tool -> thinking ->
  // answer and the thinking card ends up below the viewport.  Live snapshots
  // can reuse seq while an entry is being updated, so they stay on the
  // reducer-owned list order unless every item is a final frame.
  final finalSequences = <int>[];
  var allMessagesAreFinal = true;
  for (final message in messages) {
    final sequence = _wholeIntFromDynamic(message.streamMeta?['seq']);
    final isFinal = message.streamMeta?['isFinal'] == true;
    if (sequence == null || !isFinal) {
      allMessagesAreFinal = false;
      break;
    }
    finalSequences.add(sequence);
  }
  if (allMessagesAreFinal &&
      finalSequences.length == messages.length &&
      finalSequences.toSet().length == messages.length) {
    final ordered = <({ChatMessageModel message, int sequence, int order})>[];
    for (var index = 0; index < messages.length; index += 1) {
      ordered.add((
        message: messages[index],
        sequence: finalSequences[index],
        order: index,
      ));
    }
    ordered.sort((left, right) {
      final sequenceCompare = right.sequence.compareTo(left.sequence);
      if (sequenceCompare != 0) {
        return sequenceCompare;
      }
      return left.order.compareTo(right.order);
    });
    return ordered.map((item) => item.message).toList(growable: false);
  }
  if (!hasStableEntrySequences) {
    return _stabilizePartiallySequencedLegacyTaskNewestFirst(messages);
  }
  indexed.sort((left, right) {
    final sequenceCompare = right.entrySeq.compareTo(left.entrySeq);
    if (sequenceCompare != 0) {
      return sequenceCompare;
    }
    return left.order.compareTo(right.order);
  });
  return indexed.map((item) => item.message).toList(growable: false);
}

/// Older Xiaowan snapshots can contain a partially sequenced run when a
/// streaming batch flush preserved the text but lost its stream metadata.
/// Timestamps are allocated when each entry is first created, so for this
/// exact built-in task-id shape they remain a stable arrival-order fallback.
/// Opaque ACP task ids deliberately keep their reducer-owned list order.
List<ChatMessageModel> _stabilizePartiallySequencedLegacyTaskNewestFirst(
  List<ChatMessageModel> messages,
) {
  final taskIds = messages.map(agentRunId).whereType<String>().toSet();
  if (taskIds.length != 1 || !_legacyAgentTaskId.hasMatch(taskIds.single)) {
    return messages;
  }
  final indexed = <({ChatMessageModel message, int createdAt, int order})>[];
  for (var index = 0; index < messages.length; index += 1) {
    final message = messages[index];
    final createdAt = message.createAt.millisecondsSinceEpoch;
    if (createdAt <= 0) {
      return messages;
    }
    indexed.add((message: message, createdAt: createdAt, order: index));
  }
  indexed.sort((left, right) {
    final createdAtCompare = right.createdAt.compareTo(left.createdAt);
    if (createdAtCompare != 0) {
      return createdAtCompare;
    }
    return left.order.compareTo(right.order);
  });
  return indexed.map((item) => item.message).toList(growable: false);
}

final RegExp _legacyAgentTaskId = RegExp(r'^\d{13}-ai$');

int? _wholeIntFromDynamic(dynamic value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    final asDouble = value.toDouble();
    if (asDouble.isFinite && asDouble == asDouble.truncateToDouble()) {
      return value.toInt();
    }
  }
  if (value is String) {
    return int.tryParse(value.trim());
  }
  return null;
}

/// Slices a turn into what stays in the conversation and what folds away.
///
/// The fold holds the run's process — what the agent thought and which tools
/// it ran. Its prose and any card waiting on the user stay put, so folding a
/// finished run never hides part of the answer. An agent that narrates between
/// tool calls (every ACP agent does; the built-in assistant does too) produces
/// several prose messages per turn, and treating all but the last one as
/// process was what made a completed answer read as a fragment.
///
/// Each contiguous run of process cards becomes one segment, which is what
/// keeps two tool batches separated by prose from merging into a single card.
List<AgentRunTimelineSegment> _buildSegments(
  List<ChatMessageModel> taskMessagesNewestFirst,
) {
  final segments = <AgentRunTimelineSegment>[];
  var pendingProcess = <ChatMessageModel>[];

  void flushProcess() {
    if (pendingProcess.isEmpty) {
      return;
    }
    segments.add(AgentRunTimelineSegment.process(pendingProcess));
    pendingProcess = <ChatMessageModel>[];
  }

  for (final message in taskMessagesNewestFirst.reversed) {
    if (_isProcessMessage(message)) {
      pendingProcess.add(message);
      continue;
    }
    flushProcess();
    segments.add(AgentRunTimelineSegment.visible(message));
  }
  flushProcess();
  return List<AgentRunTimelineSegment>.unmodifiable(segments);
}

bool _isProcessMessage(ChatMessageModel message) {
  final type = _cardType(message);
  // A plan is a live ACP snapshot, not disposable tool activity. Keeping it
  // visible outside the fold lets later plan_update events replace the same
  // card in place and leaves the final plan readable after the run closes.
  return type == 'deep_thinking' ||
      (type == 'agent_tool_summary' && !isAgentPlanMessage(message));
}

/// The one rule for "which agent produced this turn".
///
/// Per-message identity first, then the conversation's bound agent, then a
/// neutral icon. The live and restored paths used to answer this differently —
/// the live header could fall back to page state while the restored header
/// could not — which is why a reloaded turn showed the built-in assistant
/// avatar instead of the agent's brand.
String resolveAgentRunAgentId({
  required Iterable<ChatMessageModel> turnMessages,
  String? conversationAgentId,
}) {
  for (final message in turnMessages) {
    final agentId = message.agentId?.trim() ?? '';
    if (agentId.isNotEmpty) {
      return agentId;
    }
  }
  final fallback = conversationAgentId?.trim() ?? '';
  return fallback.isNotEmpty ? fallback : kGenericAgentId;
}

const String kGenericAgentId = 'generic-agent';

bool _isAgentRunCandidateMessage(ChatMessageModel message) {
  if (message.user == 1) {
    return false;
  }
  if (message.type == 1) {
    return message.user == 2;
  }
  if (message.type != 2) {
    return false;
  }
  final type = _cardType(message);
  return type == 'deep_thinking' ||
      type == 'agent_tool_summary' ||
      type == 'permission_section' ||
      isAgentRequestCardType(type);
}

String _cardType(ChatMessageModel message) {
  return (message.cardData?['type'] ?? '').toString().trim();
}

String? _agentTaskIdFromEntryId(String? raw) {
  final id = raw?.trim() ?? '';
  if (id.isEmpty) {
    return null;
  }
  // Legacy Xiaowan final replies used the run id itself as the message id.
  // Keep that id intact so the reply rejoins its thinking/tool entries and is
  // rendered through the same review header as every other Harness run.
  if (_legacyAgentTaskId.hasMatch(id)) {
    return id;
  }
  const suffixes = <String>[
    '-assistant',
    '-clarify',
    '-permission',
    '-error',
    '-thinking',
    '-text',
  ];
  for (final suffix in suffixes) {
    if (id.endsWith(suffix)) {
      return id.substring(0, id.length - suffix.length);
    }
  }
  const markers = <String>['-thinking-', '-text-', '-tool-', '-permission-'];
  for (final marker in markers) {
    final index = id.indexOf(marker);
    if (index > 0) {
      return id.substring(0, index);
    }
  }
  return null;
}
