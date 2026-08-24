import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_stream_reducer.dart';
import 'package:ui/services/agent_stream_meta.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/agent_diff_parser.dart';
import 'package:ui/services/agent_tool_call_parser.dart';

enum ThinkingStage {
  thinking(1),
  toolCall(2),
  executing(3),
  complete(4),
  cancelled(5);

  final int value;
  const ThinkingStage(this.value);

  static ThinkingStage fromValue(int value) {
    return ThinkingStage.values.firstWhere(
      (e) => e.value == value,
      orElse: () => ThinkingStage.thinking,
    );
  }
}

mixin AgentStreamHandler<T extends StatefulWidget> on State<T> {
  static const int _maxTerminalOutputChars = 64 * 1024;
  static const int _maxTerminalOutputLines = 600;
  String? _lastAgentTaskId;
  String? _activeToolCardId;
  String? _activeThinkingCardId;
  String? _pendingAgentTextTaskId;
  final AgentStreamReducer _agentStreamReducer = const AgentStreamReducer();
  AgentStreamTaskState? _agentStreamState;

  String? get currentDispatchTaskId;

  String get deepThinkingContent;
  set deepThinkingContent(String value);

  bool get isDeepThinking;
  set isDeepThinking(bool value);

  int get currentThinkingStage;
  set currentThinkingStage(int value);

  List<ChatMessageModel> get messages;

  bool get isAiResponding;
  set isAiResponding(bool value);

  void createThinkingCard(String taskID);

  void updateThinkingCard(String taskID);

  void createThinkingCardForAgent(
    String taskID, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
  }) {
    createThinkingCard(taskID);
  }

  void updateThinkingCardForAgent(
    String taskID, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
    bool lockCompleted = true,
  }) {
    updateThinkingCard(taskID);
  }

  void resetDispatchState();

  void fallbackToChat(String taskID);

  Future<void> persistAgentConversation();

  // Agent 文本消息更新后交给具体页面决定是否补充额外结构化内容。
  void onAgentTextMessageUpdated(String messageId, {bool isFinal = true}) {}

  /// Called when an Agent tool reports that Android automation permissions are
  /// missing. Full-screen chat overrides this to open the scoped authorization
  /// flow and retry the original request after the user grants access. Compact
  /// surfaces may keep the existing permission card behavior by leaving the
  /// hook as a no-op.
  Future<void> onAgentPermissionRequired(List<String> permissionIds) async {}

  @protected
  Set<String> activeAgentStreamTaskIds() {
    final ids = <String>{};
    final currentTaskId = currentDispatchTaskId?.trim() ?? '';
    if (currentTaskId.isNotEmpty) {
      ids.add(currentTaskId);
    }
    final lastTaskId = _lastAgentTaskId?.trim() ?? '';
    if (isAiResponding && lastTaskId.isNotEmpty) {
      ids.add(lastTaskId);
    }
    final pendingTaskId = _pendingAgentTextTaskId?.trim() ?? '';
    if (pendingTaskId.isNotEmpty) {
      ids.add(pendingTaskId);
    }
    return ids;
  }

  void handleAgentStreamEvent(AgentStreamEvent event) {
    final reduceResult = _agentStreamReducer.reduce(_agentStreamState, event);
    if (!reduceResult.accepted) {
      return;
    }
    final thinkingCardToFinalize = _resolveThinkingCardToFinalize(
      reduceResult,
      event,
    );
    _agentStreamState = reduceResult.nextState;
    _lastAgentTaskId = event.taskId;
    _activeThinkingCardId = reduceResult.nextState.activeThinkingEntryId;
    currentThinkingStage = reduceResult.nextState.thinkingStage;
    isDeepThinking = reduceResult.nextState.isDeepThinking;
    _pendingAgentTextTaskId =
        event.kind == AgentStreamEventKind.textSnapshot && !event.isFinal
        ? event.taskId
        : null;

    switch (event.kind) {
      case AgentStreamEventKind.thinkingStarted:
      case AgentStreamEventKind.thinkingSnapshot:
        _applyAgentThinkingStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.retrying:
        _applyAgentRetryingStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.textSnapshot:
        _applyAgentTextStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.toolStarted:
      case AgentStreamEventKind.toolProgress:
      case AgentStreamEventKind.toolCompleted:
        _applyAgentToolStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.clarifyRequired:
        _applyAgentClarifyStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.completed:
        _applyAgentCompletedStreamEvent(
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.error:
        _applyAgentErrorStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.permissionRequired:
        _applyAgentPermissionStreamEvent(
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
    }
  }

  void _applyAgentThinkingStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final cardId = (event.entryId ?? '').trim();
    if (cardId.isEmpty) return;
    if (event.thinking.isNotEmpty) {
      deepThinkingContent = event.thinking;
    }
    setState(() {
      _finalizeThinkingCardInMessages(event.taskId, completedThinkingCardId);
      final exists = messages.any((msg) => msg.id == cardId);
      if (exists) {
        updateThinkingCardForAgent(
          event.taskId,
          cardId: cardId,
          thinkingContent: event.thinking.isNotEmpty ? event.thinking : null,
          isLoading: true,
          stage: event.stage <= 0 ? ThinkingStage.thinking.value : event.stage,
          streamMeta: _streamMetaFromEvent(event),
          lockCompleted: false,
        );
      } else {
        createThinkingCardForAgent(
          event.taskId,
          cardId: cardId,
          thinkingContent: event.thinking,
          isLoading: true,
          stage: event.stage <= 0 ? ThinkingStage.thinking.value : event.stage,
          streamMeta: _streamMetaFromEvent(event),
        );
      }
      isAiResponding = true;
    });
    _persistAgentConversationSafely();
  }

  void _applyAgentTextStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final messageId = (event.entryId ?? '').trim();
    final text = event.text.trim();
    if (messageId.isEmpty || text.isEmpty) return;
    final reasoningContent =
        _normalizeReasoningContent(event.thinking) ??
        _normalizeReasoningContent(deepThinkingContent);
    final streamMeta = ensureAgentStreamMessageMeta(
      _streamMetaFromEvent(event),
      entryId: messageId,
      isFinal: event.isFinal,
    );

    // 纯文本增长（消息已存在、非最终帧、无思考卡需要收敛、页面状态不变）
    // 直接原位更新：ObservableChatMessageList 会通知对应行精确刷新，
    // 不再让每个 chunk 触发整页 setState/rebuild。
    final existingIndex = messages.indexWhere((msg) => msg.id == messageId);
    final isPureTextGrowth =
        !event.isFinal &&
        existingIndex != -1 &&
        (completedThinkingCardId ?? '').trim().isEmpty &&
        isAiResponding &&
        messages is ObservableChatMessageList;
    if (isPureTextGrowth) {
      final existing = messages[existingIndex];
      final content = Map<String, dynamic>.from(existing.content ?? {});
      content['text'] = text;
      _clearAgentRetryPresentation(content);
      messages[existingIndex] = existing.copyWith(
        content: content,
        streamMeta: streamMeta,
        reasoningContent: reasoningContent ?? existing.reasoningContent,
      );
    } else {
      setState(() {
        _finalizeThinkingCardInMessages(event.taskId, completedThinkingCardId);
        final index = messages.indexWhere((msg) => msg.id == messageId);
        if (index == -1) {
          final content = <String, dynamic>{
            'text': text,
            'id': messageId,
            if (event.isFinal && event.prefillTokensPerSecond != null)
              'prefillTokensPerSecond': event.prefillTokensPerSecond,
            if (event.isFinal && event.decodeTokensPerSecond != null)
              'decodeTokensPerSecond': event.decodeTokensPerSecond,
          };
          _clearAgentRetryPresentation(content);
          messages.insert(
            0,
            ChatMessageModel(
              id: messageId,
              type: 1,
              user: 2,
              content: content,
              streamMeta: streamMeta,
              reasoningContent: reasoningContent,
            ),
          );
        } else {
          final existing = messages[index];
          final content = Map<String, dynamic>.from(existing.content ?? {});
          content['text'] = text;
          if (event.isFinal && event.prefillTokensPerSecond != null) {
            content['prefillTokensPerSecond'] = event.prefillTokensPerSecond;
          }
          if (event.isFinal && event.decodeTokensPerSecond != null) {
            content['decodeTokensPerSecond'] = event.decodeTokensPerSecond;
          }
          _clearAgentRetryPresentation(content);
          messages[index] = existing.copyWith(
            content: content,
            streamMeta: streamMeta,
            reasoningContent: reasoningContent ?? existing.reasoningContent,
          );
        }
        isAiResponding = true;
      });
    }
    onAgentTextMessageUpdated(messageId, isFinal: event.isFinal);
    _persistAgentConversationSafely();
  }

  void _applyAgentRetryingStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final entryId = (event.entryId ?? '').trim();
    final retryText = _buildAgentRetryingText(event);
    final streamMeta = ensureAgentStreamMessageMeta(
      _streamMetaFromEvent(event),
      entryId: entryId.isEmpty ? null : entryId,
      isFinal: false,
    );

    setState(() {
      final isThinkingCardTarget =
          entryId.isNotEmpty &&
          messages.any((msg) => msg.id == entryId && msg.type == 2);
      if (!isThinkingCardTarget) {
        _finalizeThinkingCardInMessages(event.taskId, completedThinkingCardId);
      }
      if (isThinkingCardTarget) {
        updateThinkingCardForAgent(
          event.taskId,
          cardId: entryId,
          thinkingContent: retryText,
          isLoading: true,
          stage: ThinkingStage.thinking.value,
          streamMeta: streamMeta,
          lockCompleted: false,
        );
      } else {
        final messageId = entryId.isNotEmpty
            ? entryId
            : _nextAgentTextMessageId(event.taskId);
        final index = messages.indexWhere((msg) => msg.id == messageId);
        if (index == -1) {
          final content = <String, dynamic>{'text': '', 'id': messageId};
          _applyAgentRetryPresentation(content, event, retryText);
          messages.insert(
            0,
            ChatMessageModel(
              id: messageId,
              type: 1,
              user: 2,
              content: content,
              streamMeta: streamMeta,
            ),
          );
        } else {
          final existing = messages[index];
          final content = Map<String, dynamic>.from(existing.content ?? {});
          content['id'] = messageId;
          _applyAgentRetryPresentation(content, event, retryText);
          messages[index] = existing.copyWith(
            content: content,
            streamMeta: streamMeta ?? existing.streamMeta,
            isError: false,
          );
        }
      }
      isAiResponding = true;
    });
    _persistAgentConversationSafely();
  }

  void _applyAgentToolStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final taskId = event.taskId;
    final cardId = (event.entryId ?? '').trim();
    if (cardId.isEmpty) return;

    final toolEvent = AgentToolEventData.fromMap(event.raw);
    _activeToolCardId = event.kind == AgentStreamEventKind.toolCompleted
        ? null
        : cardId;
    setState(() {
      isAiResponding = true;
      _finalizeThinkingCardInMessages(taskId, completedThinkingCardId);
      _upsertToolCard(
        taskId: taskId,
        cardId: cardId,
        event: toolEvent,
        status: event.kind == AgentStreamEventKind.toolCompleted
            ? _resolveToolStatus(toolEvent)
            : 'running',
        summary: toolEvent.summary.isNotEmpty
            ? toolEvent.summary
            : (LegacyTextLocalizer.isEnglish ? 'Calling tool' : '正在调用工具'),
        progress: toolEvent.progress,
        resultPreviewJson: toolEvent.resultPreviewJson,
        rawResultJson: toolEvent.rawResultJson,
        reasoningContent: event.thinking,
        streamMeta: _streamMetaFromEvent(event),
      );
    });
    _persistAgentConversationSafely();
  }

  void _applyAgentClarifyStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final text = event.question.trim().isNotEmpty
        ? event.question.trim()
        : event.text.trim();
    final messageId = (event.entryId ?? '').trim();
    final reasoningContent =
        _normalizeReasoningContent(event.thinking) ??
        _normalizeReasoningContent(deepThinkingContent);
    final streamMeta = ensureAgentStreamMessageMeta(
      _streamMetaFromEvent(event),
      entryId: messageId,
      isFinal: true,
    );
    setState(() {
      currentThinkingStage = ThinkingStage.complete.value;
      isDeepThinking = false;
      _finalizeThinkingCardInMessages(event.taskId, completedThinkingCardId);
      if (messageId.isNotEmpty && text.isNotEmpty) {
        final index = messages.indexWhere((msg) => msg.id == messageId);
        if (index == -1) {
          messages.insert(
            0,
            ChatMessageModel(
              id: messageId,
              type: 1,
              user: 2,
              content: {'text': text, 'id': messageId},
              streamMeta: streamMeta,
              reasoningContent: reasoningContent,
            ),
          );
        } else {
          messages[index] = messages[index].copyWith(
            content: {'text': text, 'id': messageId},
            streamMeta: streamMeta,
            reasoningContent:
                reasoningContent ?? messages[index].reasoningContent,
          );
        }
      }
      isAiResponding = false;
    });
    clearAgentStreamSessionState();
    resetDispatchState();
    _persistAgentConversationSafely();
  }

  void _applyAgentCompletedStreamEvent({String? completedThinkingCardId}) {
    setState(() {
      currentThinkingStage = ThinkingStage.complete.value;
      isDeepThinking = false;
      _finalizeThinkingCardInMessages(
        _lastAgentTaskId ?? '',
        completedThinkingCardId,
      );
      isAiResponding = false;
    });
    clearAgentStreamSessionState();
    resetDispatchState();
    _persistAgentConversationSafely();
  }

  void _applyAgentErrorStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final entryId = (event.entryId ?? '').trim();
    final shouldMarkError = event.raw['persistAsError'] == true;
    final errorText = (event.raw['errorText'] ?? event.errorMessage)
        .toString()
        .trim();
    setState(() {
      currentThinkingStage = ThinkingStage.complete.value;
      isDeepThinking = false;
      _finalizeThinkingCardInMessages(event.taskId, completedThinkingCardId);
      if (entryId.isNotEmpty) {
        final index = messages.indexWhere((msg) => msg.id == entryId);
        if (index != -1) {
          final existing = messages[index];
          final content = Map<String, dynamic>.from(existing.content ?? {});
          _applyAgentErrorPresentation(content, event, errorText);
          messages[index] = existing.copyWith(
            content: content,
            isError: shouldMarkError,
            streamMeta: ensureAgentStreamMessageMeta(
              _streamMetaFromEvent(event),
              entryId: entryId,
              isFinal: true,
            ),
            turnUsage: event.turnUsage ?? existing.turnUsage,
          );
        }
      }
      isAiResponding = false;
    });
    clearAgentStreamSessionState();
    resetDispatchState();
    _persistAgentConversationSafely();
  }

  void _applyAgentPermissionStreamEvent(
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final taskId = event.taskId;
    final messageId = (event.entryId ?? '').trim();
    final text = event.text.trim();
    final reasoningContent =
        _normalizeReasoningContent(event.thinking) ??
        _normalizeReasoningContent(deepThinkingContent);
    final permissionCardId =
        (event.raw['permissionCardId'] ?? '$taskId-permission').toString();
    final executionPermissionIds = _resolveExecutionPermissionIds(
      event.missingPermissions,
    );
    final replyStreamMeta = ensureAgentStreamMessageMeta(
      _streamMetaFromEvent(event),
      entryId: messageId,
      isFinal: true,
    );
    final cardStreamMeta = ensureAgentStreamMessageMeta(
      _streamMetaFromEvent(event),
      entryId: permissionCardId,
      isFinal: true,
    );
    setState(() {
      currentThinkingStage = ThinkingStage.complete.value;
      isDeepThinking = false;
      _finalizeThinkingCardInMessages(taskId, completedThinkingCardId);
      if (messageId.isNotEmpty && text.isNotEmpty) {
        final index = messages.indexWhere((msg) => msg.id == messageId);
        if (index == -1) {
          messages.insert(
            0,
            ChatMessageModel(
              id: messageId,
              type: 1,
              user: 2,
              content: {'text': text, 'id': messageId},
              streamMeta: replyStreamMeta,
              reasoningContent: reasoningContent,
            ),
          );
        } else {
          messages[index] = messages[index].copyWith(
            content: {'text': text, 'id': messageId},
            streamMeta: replyStreamMeta,
            reasoningContent:
                reasoningContent ?? messages[index].reasoningContent,
          );
        }
      }
      if (executionPermissionIds.isNotEmpty) {
        final cardIndex = messages.indexWhere(
          (msg) => msg.id == permissionCardId,
        );
        final card = ChatMessageModel(
          id: permissionCardId,
          type: 2,
          user: 3,
          content: {
            'cardData': {
              'type': 'permission_section',
              'requiredPermissionIds': executionPermissionIds,
            },
            'id': permissionCardId,
          },
          streamMeta: cardStreamMeta,
        );
        if (cardIndex == -1) {
          messages.insert(0, card);
        } else {
          messages[cardIndex] = messages[cardIndex].copyWith(
            content: {
              'cardData': {
                'type': 'permission_section',
                'requiredPermissionIds': executionPermissionIds,
              },
              'id': permissionCardId,
            },
            streamMeta: cardStreamMeta,
          );
        }
      }
      isAiResponding = false;
    });
    clearAgentStreamSessionState();
    resetDispatchState();
    _persistAgentConversationSafely();
    if (executionPermissionIds.isNotEmpty && mounted) {
      unawaited(onAgentPermissionRequired(executionPermissionIds));
    }
  }

  Map<String, dynamic> _streamMetaFromEvent(AgentStreamEvent event) {
    return buildAgentStreamMetaFromEvent(event);
  }

  String _buildAgentRetryingText(AgentStreamEvent event) {
    if (event.text.trim().isNotEmpty) {
      return event.text.trim();
    }
    final retryCount = event.retryCount <= 0 ? 1 : event.retryCount;
    final maxRetries = event.maxRetries <= 0 ? 3 : event.maxRetries;
    return LegacyTextLocalizer.isEnglish
        ? 'Connection interrupted. Retrying $retryCount/$maxRetries...'
        : '连接中断，正在重试 $retryCount/$maxRetries…';
  }

  void _applyAgentRetryPresentation(
    Map<String, dynamic> content,
    AgentStreamEvent event,
    String retryText,
  ) {
    content['agentTaskId'] = event.taskId;
    content['agentRetrying'] = true;
    content['agentRetryStatusText'] = retryText;
    content['agentRetryCount'] = event.retryCount;
    content['agentMaxRetries'] = event.maxRetries;
    content['agentRetryDelayMs'] = event.retryDelayMs;
    content['agentRetryReason'] = event.retryReason;
    content['agentContinuing'] = false;
    content.remove('agentContinueStatusText');
    content.remove('agentContinueable');
    content.remove('agentContinueResumeMode');
    content.remove('agentErrorText');
    content.remove('agentRetryable');
  }

  void _applyAgentErrorPresentation(
    Map<String, dynamic> content,
    AgentStreamEvent event,
    String errorText,
  ) {
    content['agentTaskId'] = event.taskId;
    content['agentRetrying'] = false;
    content['agentRetryStatusText'] = '';
    content['agentRetryCount'] = event.retryCount;
    content['agentMaxRetries'] = event.maxRetries;
    content['agentRetryDelayMs'] = 0;
    content['agentRetryReason'] = event.retryReason;
    content['agentContinuing'] = false;
    content['agentContinueStatusText'] = '';
    content['agentRetryable'] = event.retryable;
    content['agentContinueable'] = event.continueable;
    content['agentContinueResumeMode'] = event.continueResumeMode;
    content['agentErrorText'] = errorText;
  }

  void _clearAgentRetryPresentation(Map<String, dynamic> content) {
    content.remove('agentRetrying');
    content.remove('agentRetryStatusText');
    content.remove('agentRetryCount');
    content.remove('agentMaxRetries');
    content.remove('agentRetryDelayMs');
    content.remove('agentRetryReason');
    content.remove('agentRetryable');
    content.remove('agentContinuing');
    content.remove('agentContinueStatusText');
    content.remove('agentContinueable');
    content.remove('agentContinueResumeMode');
    content.remove('agentErrorText');
  }

  void handleAgentError(String error) {
    final taskId = currentDispatchTaskId ?? _lastAgentTaskId;
    if (taskId == null) return;

    debugPrint('Agent error: $error');

    currentThinkingStage = ThinkingStage.complete.value;
    isDeepThinking = false;
    final thinkingCardId = _resolveThinkingCardId(taskId);
    if (thinkingCardId != null) {
      updateThinkingCardForAgent(
        taskId,
        cardId: thinkingCardId,
        isLoading: false,
        stage: ThinkingStage.complete.value,
      );
    }

    final textId =
        _resolvePendingAgentTextMessageId(taskId) ??
        _nextAgentTextMessageId(taskId);
    final index = messages.indexWhere((msg) => msg.id == textId);
    final existingText = index == -1
        ? ''
        : (messages[index].content?['text'] as String? ?? '');
    final preservedText = existingText.trim();
    final fallbackMessage = error.trim().isEmpty
        ? (LegacyTextLocalizer.isEnglish
              ? "I can't generate a reply right now. Please try again."
              : '暂时无法生成回复，请重试。')
        : (LegacyTextLocalizer.isEnglish
              ? "I can't generate a reply right now. Please try again. ${error.trim()}"
              : '暂时无法生成回复，请重试。${error.trim()}');
    setState(() {
      if (index == -1) {
        messages.insert(
          0,
          ChatMessageModel(
            id: textId,
            type: 1,
            user: 2,
            content: {
              'text': preservedText.isNotEmpty
                  ? preservedText
                  : fallbackMessage,
              'id': textId,
            },
            isError: preservedText.isEmpty,
          ),
        );
      } else {
        final existing = messages[index];
        messages[index] = existing.copyWith(
          content: {
            'text': preservedText.isNotEmpty ? preservedText : fallbackMessage,
            'id': textId,
          },
          isError: preservedText.isEmpty,
        );
      }
      isAiResponding = false;
    });
    _pendingAgentTextTaskId = null;

    clearAgentStreamSessionState();
    resetDispatchState();
    _persistAgentConversationSafely();
  }

  List<String> _resolveExecutionPermissionIds(List<String> missing) {
    return resolveExecutionPermissionIds(missing);
  }

  String _baseThinkingCardId(String taskId) => '$taskId-thinking';
  String _agentTextBaseId(String taskId) => '$taskId-text';

  String? _resolveThinkingCardToFinalize(
    AgentStreamReduceResult reduceResult,
    AgentStreamEvent event,
  ) {
    switch (event.kind) {
      case AgentStreamEventKind.thinkingStarted:
      case AgentStreamEventKind.thinkingSnapshot:
        return reduceResult.isNewThinkingEntry
            ? reduceResult.previousThinkingEntryId
            : null;
      case AgentStreamEventKind.textSnapshot:
      case AgentStreamEventKind.retrying:
      case AgentStreamEventKind.toolStarted:
      case AgentStreamEventKind.toolProgress:
      case AgentStreamEventKind.toolCompleted:
      case AgentStreamEventKind.completed:
      case AgentStreamEventKind.error:
      case AgentStreamEventKind.permissionRequired:
      case AgentStreamEventKind.clarifyRequired:
        return reduceResult.previousThinkingEntryId;
    }
  }

  void _finalizeThinkingCardInMessages(String taskId, String? cardId) {
    final resolvedCardId = (cardId ?? '').trim();
    if (taskId.trim().isEmpty || resolvedCardId.isEmpty) {
      return;
    }
    final index = messages.indexWhere((msg) => msg.id == resolvedCardId);
    if (index == -1) {
      return;
    }

    final existing = messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? const {});
    final cardData = Map<String, dynamic>.from(content['cardData'] ?? const {});
    final currentStageRaw = cardData['stage'];
    final currentStage = currentStageRaw is num
        ? currentStageRaw.toInt()
        : int.tryParse(currentStageRaw?.toString() ?? '');
    final isLoading = cardData['isLoading'] == true;
    if (!isLoading && currentStage == ThinkingStage.complete.value) {
      return;
    }

    cardData['thinkingContent'] =
        cardData['thinkingContent'] ?? deepThinkingContent;
    cardData['isLoading'] = false;
    cardData['stage'] = ThinkingStage.complete.value;
    cardData['taskID'] = taskId;
    cardData['cardId'] = resolvedCardId;
    cardData['endTime'] ??= DateTime.now().millisecondsSinceEpoch;
    content['cardData'] = cardData;
    messages[index] = existing.copyWith(content: content);
  }

  String? _resolveThinkingCardId(String taskId) {
    if (_activeThinkingCardId != null) {
      return _activeThinkingCardId;
    }
    final baseId = _baseThinkingCardId(taskId);
    final exists = messages.any((msg) => msg.id == baseId);
    return exists ? baseId : null;
  }

  String? _resolvePendingAgentTextMessageId(String taskId) {
    if (_pendingAgentTextTaskId != taskId) return null;
    for (final message in messages) {
      if (_isAgentTextMessageForTask(message, taskId)) {
        return message.id;
      }
    }
    return null;
  }

  String _nextAgentTextMessageId(String taskId) {
    final baseId = _agentTextBaseId(taskId);
    var maxSequence = 0;
    for (final message in messages) {
      final sequence = _agentTextMessageSequence(message.id, taskId);
      if (sequence > maxSequence) {
        maxSequence = sequence;
      }
    }
    if (maxSequence == 0) {
      return baseId;
    }
    return '$baseId-${maxSequence + 1}';
  }

  bool _isAgentTextMessageForTask(ChatMessageModel message, String taskId) {
    if (message.type != 1 || message.user != 2) {
      return false;
    }
    return _agentTextMessageSequence(message.id, taskId) > 0;
  }

  int _agentTextMessageSequence(String messageId, String taskId) {
    final baseId = _agentTextBaseId(taskId);
    if (messageId == baseId) {
      return 1;
    }
    if (!messageId.startsWith('$baseId-')) {
      return 0;
    }
    return int.tryParse(messageId.substring(baseId.length + 1)) ?? 0;
  }

  void clearAgentStreamSessionState() {
    _lastAgentTaskId = null;
    _pendingAgentTextTaskId = null;
    _activeToolCardId = null;
    _agentStreamState = null;
    _activeThinkingCardId = null;
  }

  void interruptActiveToolCard({String? summary}) {
    final cardId = _activeToolCardId;
    if (cardId == null) return;

    setState(() {
      final index = messages.indexWhere((msg) => msg.id == cardId);
      if (index == -1) {
        return;
      }

      final existingCardData = Map<String, dynamic>.from(
        messages[index].cardData ?? const {},
      );
      existingCardData['status'] = 'interrupted';
      existingCardData['success'] = false;
      if (summary != null && summary.trim().isNotEmpty) {
        existingCardData['summary'] = summary.trim();
      }

      messages[index] = messages[index].copyWith(
        content: {'cardData': existingCardData, 'id': cardId},
      );
    });

    _activeToolCardId = null;
  }

  void _persistAgentConversationSafely() {
    Future<void>.microtask(() async {
      try {
        await persistAgentConversation();
      } catch (e) {
        debugPrint('persistAgentConversation failed: $e');
      }
    });
  }

  void _upsertToolCard({
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
    setState(() {
      final index = messages.indexWhere((msg) => msg.id == cardId);
      final existingCardData = index == -1
          ? const <String, dynamic>{}
          : Map<String, dynamic>.from(messages[index].cardData ?? const {});
      final existingTerminalOutput = (existingCardData['terminalOutput'] ?? '')
          .toString();
      final isFileChangeTool = _isAgentFileChangeTool(event, existingCardData);
      final effectiveToolType = isFileChangeTool ? 'file' : event.toolType;
      final terminalOutput = effectiveToolType == 'terminal'
          ? _resolveTerminalOutput(
              existing: existingTerminalOutput,
              event: event,
            )
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
      final diffSummary = diffText.isEmpty
          ? null
          : parseAgentDiffText(diffText);
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
      final cardData = <String, dynamic>{
        'type': 'agent_tool_summary',
        'uiStyle': event.uiStyle.isNotEmpty
            ? event.uiStyle
            : (existingCardData['uiStyle'] ?? '').toString(),
        'taskId': taskId,
        'cardId': cardId,
        'toolName': event.toolName,
        'displayName': event.displayName,
        'toolTitle': event.toolTitle.isNotEmpty
            ? event.toolTitle
            : (existingCardData['toolTitle'] ?? '').toString(),
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
        'artifacts': event.artifacts.isNotEmpty
            ? event.artifacts
            : (existingCardData['artifacts'] ?? const []),
        'actions': event.actions.isNotEmpty
            ? event.actions
            : (existingCardData['actions'] ?? const []),
        'success': event.success,
        'showTerminalOutput': effectiveToolType == 'terminal',
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
        messages.insert(
          0,
          ChatMessageModel.cardMessage(
            cardData,
            id: cardId,
            streamMeta: ensureAgentStreamMessageMeta(
              streamMeta,
              entryId: cardId,
            ),
          ),
        );
      } else {
        messages[index] = messages[index].copyWith(
          content: {'cardData': cardData, 'id': cardId},
          streamMeta: ensureAgentStreamMessageMeta(
            streamMeta ?? messages[index].streamMeta,
            entryId: cardId,
          ),
        );
      }
    });
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
    final existing = (existingCardData['diffText'] ?? '').toString().trim();
    return existing;
  }

  String _resolveToolStatus(AgentToolEventData event) {
    final normalized = event.status.trim().toLowerCase();
    if (normalized.isNotEmpty) {
      return normalized;
    }
    return event.success ? 'success' : 'error';
  }

  String _trimTerminalOutput(String value) {
    if (value.isEmpty) return value;

    var candidate = value;
    if (candidate.length > _maxTerminalOutputChars) {
      candidate = candidate.substring(
        candidate.length - _maxTerminalOutputChars,
      );
    }

    final lines = candidate.split('\n');
    if (lines.length > _maxTerminalOutputLines) {
      candidate = lines
          .sublist(lines.length - _maxTerminalOutputLines)
          .join('\n');
    }

    final wasTrimmed =
        candidate.length < value.length ||
        lines.length > _maxTerminalOutputLines;
    if (!wasTrimmed) {
      return candidate;
    }

    // Kept in step with the coordinator's copy of this helper, which is
    // localized. This one was not, so an English-locale user saw a Chinese
    // truncation notice in the command overlay's terminal output.
    final notice = LegacyTextLocalizer.isEnglish
        ? '[Only the most recent terminal output is shown]\n'
        : '[只显示最近的部分终端输出]\n';
    final body = candidate.startsWith(notice)
        ? candidate.substring(notice.length)
        : candidate;
    final remaining = _maxTerminalOutputChars - notice.length;
    return '$notice${body.substring(body.length > remaining ? body.length - remaining : 0)}';
  }

  String? _normalizeReasoningContent(String? value) {
    final normalized = value?.trim() ?? '';
    return normalized.isEmpty ? null : normalized;
  }
}
