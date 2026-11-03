import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  const chatRoot = 'lib/features/home/pages/chat';

  test(
    'chat keeps tool traces in messages without a duplicate composer overlay',
    () {
      final ui = File('$chatRoot/chat_page_ui.dart').readAsStringSync();
      final page = File('$chatRoot/chat_page.dart').readAsStringSync();
      final outsideTap = File('$chatRoot/chat_page_openclaw.dart')
          .readAsStringSync();
      final mode = File('$chatRoot/state/chat_page_mode_state.dart')
          .readAsStringSync();
      expect(ui, isNot(contains('ChatToolActivityStrip(')));
      expect(ui, isNot(contains('resolveAgentToolActivitySnapshot(')));
      expect(ui, contains('ChatCommandActivityStrip('));
      expect(ui, contains('_slashCommandPanelOccupiedHeight'));
      expect(ui, contains('ChatMessageList('));
      expect(ui, contains('onCancelTask: _onCancelTask'));
      for (final source in [ui, page, outsideTap, mode]) {
        expect(source, isNot(contains('toolActivityOccupiedHeight')));
        expect(source, isNot(contains('toolActivityExpanded')));
        expect(source, isNot(contains('_toolActivityStripKey')));
      }
    },
  );

  test('init shares submit admission and preserves attachment references', () {
    final source = File('$chatRoot/chat_page_agent.dart').readAsStringSync();
    final card = source
        .split("if (command == '/init') {")
        .last
        .split('return;')
        .first;
    expect(card, contains("_sendMessage(text: '/init')"));
    expect(card, isNot(contains('_executeAgentInitCommand')));
    final submit = source
        .split('case AgentSlashSubmitKind.startInit:')
        .last
        .split('return true;')
        .first;
    expect(
      submit,
      contains('_executeAgentInitCommand(attachments: attachments)'),
    );
    final init = source
        .split('Future<void> _executeAgentInitCommand(')
        .last
        .split('Future<void> _startAgentReviewCommand')
        .first;
    expect(init, contains('attachments: attachments'));
    expect(init, contains('_startAgentTurnCommand('));
  });

  test('attachments are consumed at message admission, never before command routing', () {
    final flow = File('$chatRoot/chat_page_conversation_flow.dart')
        .readAsStringSync();
    final send = flow
        .split('Future<void> _sendMessage(')
        .last
        .split('Future<void> _startManualRecordingCommand')
        .first;
    expect(send, isNot(contains('_pendingAttachments.clear()')));
    expect(send, isNot(contains('consumeMessageAttachments')));
    final dispatch = flow
        .split('Future<void> _dispatchUserMessage(')
        .last
        .split('void _syncUserMessageLinkPreviews')
        .first;
    expect(
      dispatch.indexOf('consumeMessageAttachments'),
      greaterThan(dispatch.indexOf('messageIds = addUserMessage')),
    );
    final agent = File('$chatRoot/chat_page_agent.dart').readAsStringSync();
    final turn = agent
        .split('Future<void> _startAgentTurnCommand(')
        .last
        .split('String? _readAgentPreference')
        .first;
    expect(
      turn.indexOf('consumeMessageAttachments'),
      greaterThan(turn.indexOf('final messageIds = addUserMessage')),
    );
  });

  test('model refresh and user budgets have separate write owners', () {
    final models = File('$chatRoot/chat_page_model_context.dart')
        .readAsStringSync();
    final actions = File('$chatRoot/chat_page_user_message_actions.dart')
        .readAsStringSync();
    expect(models, isNot(contains('updateConversationPromptTokenThreshold')));
    expect(models, isNot(contains('getManualModelContextThreshold')));
    expect(actions, contains('updateConversationPromptTokenThreshold'));
    expect(actions, isNot(contains('setManualModelContextThreshold')));
  });

  test(
    'history avatar shares conversation identity instead of connected process',
    () {
      final page = File('$chatRoot/chat_page.dart').readAsStringSync();
      expect(
        page,
        contains('String? get _appBarActiveAcpAgentId => _activeAcpAgentId;'),
      );
      final committed = page
          .split('String? get _committedAcpAgentId {')
          .last
          .split('String get _activeAcpAgentDisplayName')
          .first;
      expect(
        committed.indexOf('target?.agentId'),
        lessThan(committed.indexOf(': _conversationBoundAcpAgentId')),
      );
      expect(
        committed,
        isNot(contains('_agentRuntimeStatus.activeAgentId')),
      );
    },
  );

  test(
    'conversation identity has no obsolete in-chat Harness switch state',
    () {
      final page = File('$chatRoot/chat_page.dart').readAsStringSync();
      expect(page, isNot(contains('_optimisticAcpAgentId')));
      expect(page, isNot(contains('_isAcpAgentSwitching')));
      expect(page, isNot(contains('_agentRuntimeStatusEpoch')));
      expect(page, isNot(contains('_agentCatalogEpoch')));
      expect(
        page,
        contains('String? get _activeAcpAgentId => _committedAcpAgentId;'),
      );
      final committed = page
          .split('String? get _committedAcpAgentId {')
          .last
          .split('String get _activeAcpAgentDisplayName')
          .first;
      expect(committed, isNot(contains('_optimisticAcpAgentId')));
      expect(
        committed,
        contains('final target = _resolvedThreadTarget ?? widget.threadTarget'),
      );
      final target = page
          .split('ConversationThreadTarget get _threadTargetForMode {')
          .last
          .split('ConversationThreadTarget? get _visibleThreadTarget')
          .first;
      expect(target, contains('_committedAcpAgentId'));
      expect(target, isNot(contains('_activeAcpAgentId')));
      final switching = File('$chatRoot/chat_page_agent.dart')
          .readAsStringSync();
      expect(switching, isNot(contains('_handleAcpAgentModeShortcutTap')));
      expect(switching, isNot(contains('_handleAgentTap')));
      expect(switching, isNot(contains('Future<bool> _selectAgent(')));
      expect(switching, isNot(contains('_selectRemoteCodexRuntime(')));
      final service = File('lib/services/agent_runtime_service.dart')
          .readAsStringSync();
      expect(service, isNot(contains('static Future<AcpAgentCatalog> selectAgent(')));
      expect(service, isNot(contains("_invokeMap('connect'")));
      expect(service, isNot(contains('selectedAgentId')));
    },
  );

  test(
    'Home creates an explicit target consumed by the shared ACP session path',
    () {
      final home = File('lib/features/home/widgets/home_drawer_actions.dart')
          .readAsStringSync();
      final create = home
          .split('void _openNewConversation() {')
          .last
          .split('Future<void> _triggerDeleteHaptic()')
          .first;
      expect(create, contains('ConversationThreadTarget.newConversation('));
      expect(create, contains("_homeFilter.agentId ?? 'xiaowan-acp'"));
      final open = home
          .split('void _openThreadTarget(ConversationThreadTarget target) {')
          .last
          .split('bool _closeDrawerForNavigation()')
          .first;
      expect(open, contains("'/home/chat'"));
      expect(open, contains('extra: target'));

      final lifecycle = File('$chatRoot/chat_page_lifecycle.dart')
          .readAsStringSync();
      final bootstrap = lifecycle
          .split('Future<void> _bootstrapConversationThread() async {')
          .last
          .split('Future<void> _reloadConversationForCurrentTarget()')
          .first;
      expect(
        bootstrap,
        matches(
          RegExp(r'_resolveConversationThreadTarget\(\s*widget.threadTarget,?\s*\)'),
        ),
      );
      expect(bootstrap, contains('_applyConversationThreadTarget('));
      final apply = lifecycle
          .split('Future<void> _applyConversationThreadTarget(')
          .last
          .split('Future<void> _sendInitialMessageIfNeeded(')
          .first;
      expect(apply, contains('_resolvedThreadTarget = effectiveTarget'));
      expect(apply, contains('initializeConversation(lifecycleToken:'));

      final agent = File('$chatRoot/chat_page_agent.dart').readAsStringSync();
      final send = agent
          .split('Future<void> _sendAgentMessage(')
          .last
          .split('Future<void> _interruptAgentTurn(')
          .first;
      expect(send, contains('final dispatchTarget = _resolvedThreadTarget'));
      expect(send, contains('dispatchTarget?.agentId ?? _activeAcpAgentId'));
      expect(send, contains('await _prepareAcpSessionForTurn('));
      expect(send, contains('AgentRuntimeService.promptSession('));
      expect(send, contains('agentId: dispatchAgentId'));
    },
  );

  test('Harness switching preserves composer and captures queued input before waiting', () {
    final lifecycle = File('$chatRoot/chat_page_lifecycle.dart')
        .readAsStringSync();
    final apply = lifecycle
        .split('Future<void> _applyConversationThreadTarget(')
        .last
        .split('Future<void> _sendInitialMessageIfNeeded(')
        .first;
    expect(
      apply,
      contains('effectiveTarget.isNewConversation && !preserveComposer'),
    );
    expect(
      apply.indexOf('_resetLocalConversationState(targetMode);'),
      lessThan(apply.indexOf('draftMessage = composerValue.text')),
    );
    final flow = File('$chatRoot/chat_page_conversation_flow.dart')
        .readAsStringSync();
    final send = flow
        .split('Future<void> _sendMessage(')
        .last
        .split('Future<void> _startManualRecordingCommand')
        .first;
    final wait = send.indexOf('await _harnessSwitchSendBarrier.waitUntilIdle()');
    expect(wait, greaterThanOrEqualTo(0));
    expect(
      send,
      contains('final submittedText = text ?? _messageController.text;'),
    );
    for (final capture in [
      'final submittedText =',
      'final submittedAttachments =',
    ]) {
      expect(send.indexOf(capture), inInclusiveRange(0, wait - 1));
    }
    expect(send, contains('if (!mounted || !switched) return;'));
    expect(send, contains('final messageText = submittedText.trim();'));
    expect(send, contains('final inputAttachments = submittedAttachments;'));
  });

  test('only the prompt response completes the shared reducer', () {
    final reducer = File('lib/services/agent_event_reducer.dart')
        .readAsStringSync();
    final response = reducer
        .split('AgentReduceResult reducePromptResponse(')
        .last
        .split('AgentReduceResult reduce(')
        .first;
    expect(response, contains('_completeTurn('));
    // One call plus the method declaration; notifications cannot end a request.
    expect(
      RegExp(
        r'^\s*(?:void )?_completeTurn\(',
        multiLine: true,
      ).allMatches(reducer),
      hasLength(2),
    );
    final coordinator = File(
      '$chatRoot/services/chat_conversation_runtime_coordinator.dart',
    ).readAsStringSync();
    expect(coordinator, isNot(contains('_isTerminalAcpBindingEvent')));
    final page = File('$chatRoot/chat_page.dart').readAsStringSync();
    final errors = page
        .split('void handleAgentError(')
        .last
        .split('void interruptActiveToolCard(')
        .first;
    expect(errors, contains('applyAcpPromptResponse('));
    expect(errors, isNot(contains('unregisterTask(')));
    expect(errors, isNot(contains('messages.insert(')));
  });

  test(
    'remote notifications and snapshots do not decide prompt termination',
    () {
      final native = File(
        '../app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeManager.kt',
      ).readAsStringSync();
      final notification = native
          .split('val protocolEventType = if (method == "codex/event")')
          .last
          .split('val eventAgentId =')
          .first;
      expect(notification, isNot(contains('clearActiveTurn(')));
      expect(native, isNot(contains('syncActiveTurnSnapshot(')));
    },
  );

  test(
    'normal chat delegates failures without inserting a second error message',
    () {
      final source = File('$chatRoot/chat_page_conversation_flow.dart')
          .readAsStringSync();
      final send = source
          .split('Future<void> _sendPureChatMessage(')
          .last
          .split('Future<bool> _handleExecutableTaskFlow(')
          .first;
      final handler = send.substring(send.lastIndexOf('} catch (error) {'));
      expect(handler, contains('_runtimeCoordinator.applyAcpPromptResponse('));
      expect(handler, isNot(contains('_messages.insert(')));
      expect(handler, isNot(contains('ChatMessageModel(')));
      expect(handler, isNot(contains('isAiResponding')));
    },
  );

  test('chat page keeps per-mode values in ChatPageModeState', () {
    final source = File('$chatRoot/chat_page.dart').readAsStringSync();

    // Unbound ACP configuration sessions are resources awaiting a first
    // prompt, not a second store for per-mode presentation state.
    final modeMaps = RegExp(
      r'\bMap<ChatPageMode,\s*[^;=]+>\s+(_\w+)\s*=',
    ).allMatches(source).map((match) => match.group(1));
    expect(modeMaps, ['_unboundConfigurationSessions']);
    expect(source, contains('List<ChatPageModeState> _modeStates'));
  });

  test(
    'runtime and widget facades declare their focused implementation parts',
    () {
      final runtimeSource = File(
        '$chatRoot/services/chat_conversation_runtime_coordinator.dart',
      ).readAsStringSync();
      final widgetSource = File('$chatRoot/widgets/chat_widgets.dart')
          .readAsStringSync();

      for (final part in const <String>[
        'chat_runtime_agent_event_support.dart',
        'chat_runtime_message_support.dart',
        'chat_runtime_streaming_support.dart',
        'chat_runtime_thinking_support.dart',
      ]) {
        expect(runtimeSource, contains("part '$part';"));
      }
      expect(
        runtimeSource,
        isNot(contains("part 'chat_runtime_tool_support.dart';")),
      );
      for (final part in const <String>[
        'chat_app_bar.dart',
        'chat_input_wrapper.dart',
        'chat_message_list.dart',
      ]) {
        expect(widgetSource, contains("part '$part';"));
      }
    },
  );

  test(
    'the shared coordinator solely consumes and projects native ACP events',
    () {
      const coordinatorPath =
          '$chatRoot/services/chat_conversation_runtime_coordinator.dart';
      final subscription = RegExp(
        r'\bAgentRuntimeService\s*\.\s*events\s*\.\s*listen\s*\(',
      );
      final owners = <String>[];
      for (final file in Directory(
        'lib',
      ).listSync(recursive: true).whereType<File>()) {
        if (!file.path.endsWith('.dart')) continue;
        final source = file.readAsStringSync();
        for (final _ in subscription.allMatches(source)) {
          owners.add(file.path);
        }
      }
      expect(owners, <String>[coordinatorPath]);
      final coordinator = File(coordinatorPath).readAsStringSync();
      expect(coordinator, contains('if (_initialized) return;'));
      final projection = File(
        '$chatRoot/services/chat_runtime_agent_event_support.dart',
      ).readAsStringSync();
      final reductionIndex = projection.indexOf('_applyAgentEvent(');
      final publicationIndex = projection.indexOf(
        '_latestAgentEventProjection = ChatAgentEventProjection(',
      );
      expect(reductionIndex, greaterThanOrEqualTo(0));
      expect(publicationIndex, greaterThan(reductionIndex));
      expect(
        projection.indexOf('_notifyRuntimeListeners();'),
        greaterThan(publicationIndex),
      );

      final page =
          File('$chatRoot/chat_page.dart').readAsStringSync() +
          File('$chatRoot/chat_page_lifecycle.dart').readAsStringSync() +
          File('$chatRoot/chat_page_agent.dart').readAsStringSync();
      final sheet = File(
        'lib/features/home/pages/command_overlay/chat_bot_sheet.dart',
      ).readAsStringSync();
      for (final source in <String>[page, sheet]) {
        expect(
          source,
          contains('_runtimeCoordinator.latestAgentEventProjection'),
        );
        expect(
          source,
          contains(
            '_runtimeCoordinator.addListener(_handleRuntimeCoordinatorChanged)',
          ),
        );
        expect(
          source,
          contains(
            '_runtimeCoordinator.removeListener(_handleRuntimeCoordinatorChanged)',
          ),
        );
        expect(source, isNot(contains('_runtimeCoordinator.applyAgentEvent(')));
      }
    },
  );

  test('Agent Flutter runtime exposes only the ACP lifecycle entry points', () {
    final reducerSource = File('lib/services/agent_event_reducer.dart')
        .readAsStringSync();
    final coordinatorSource = File(
      '$chatRoot/services/chat_conversation_runtime_coordinator.dart',
    ).readAsStringSync();
    final runtimeServiceSource = File('lib/services/agent_runtime_service.dart')
        .readAsStringSync();

    expect(reducerSource, isNot(contains('completePrompt(')));
    expect(coordinatorSource, isNot(contains('completePrompt(')));
    expect(coordinatorSource, isNot(contains('agent_stream_handler')));
    expect(coordinatorSource, isNot(contains('agent_stream_reducer')));
    for (final legacyMethod in const <String>[
      'startThread(',
      'resumeThread(',
      'readThread(',
      'listThreads(',
      'listLoadedThreads(',
      'archiveThread(',
      'unarchiveThread(',
      'setThreadName(',
      'startTurn(',
    ]) {
      expect(runtimeServiceSource, isNot(contains(legacyMethod)));
    }
  });

  test(
    'remote ACP snapshot sync is wired to every visible session admission',
    () {
      final source =
          File('$chatRoot/chat_page_agent.dart').readAsStringSync() +
          File('$chatRoot/chat_page_remote_codex.dart').readAsStringSync();

      final prepareStart = source.indexOf(
        'Future<void> _prepareRemoteCodexSessionTarget(',
      );
      final prepareEnd = source.indexOf('\n  @override', prepareStart);
      expect(prepareStart, greaterThanOrEqualTo(0));
      expect(prepareEnd, greaterThan(prepareStart));
      final prepareBody = source.substring(prepareStart, prepareEnd);
      expect(prepareBody, contains('_startRemoteCodexSessionSync('));

      final activationStart = source.indexOf(
        'int _activateRemoteCodexRuntimeForThread(',
      );
      final activationEnd = source.indexOf(
        '\n  bool _shouldPromoteRemoteCodexEventToVisibleThread(',
        activationStart,
      );
      expect(activationStart, greaterThanOrEqualTo(0));
      expect(activationEnd, greaterThan(activationStart));
      final activationBody = source.substring(activationStart, activationEnd);
      expect(activationBody, contains('_startRemoteCodexSessionSync('));
    },
  );

  test(
    'remote session restore is fenced by the conversation target generation',
    () {
      final source = File('$chatRoot/chat_page_agent.dart').readAsStringSync();
      final flowStart = source.indexOf(
        'Future<void> _prepareRemoteCodexSessionTarget(',
      );
      final flowEnd = source.indexOf('\n  @override', flowStart);
      expect(flowStart, greaterThanOrEqualTo(0));
      expect(flowEnd, greaterThan(flowStart));
      final flowBody = source.substring(flowStart, flowEnd);
      expect(
        flowBody,
        contains('final targetRequestId = _conversationTargetRequestId;'),
      );
      expect(
        flowBody,
        contains('_isConversationTargetRequestCurrent(targetRequestId)'),
      );
    },
  );

  test('command overlay admits ACP turns through the shared coordinator', () {
    final source = File(
      'lib/features/home/pages/command_overlay/chat_bot_sheet.dart',
    ).readAsStringSync();
    final flowStart = source.indexOf(
      'Future<bool> _tryAgentFlow(String aiMessageId, String userMessageId)',
    );
    final flowEnd = source.indexOf('\n  bool _canContinueAcpTurn(', flowStart);
    expect(flowStart, greaterThanOrEqualTo(0));
    expect(flowEnd, greaterThan(flowStart));
    final flowBody = source.substring(flowStart, flowEnd);
    expect(flowBody, contains('_runtimeCoordinator.beginAcpTurn('));
    expect(
      flowBody.indexOf('_runtimeCoordinator.beginAcpTurn('),
      lessThan(flowBody.indexOf('await AgentRuntimeService.newSession(')),
    );
    expect(flowBody, isNot(contains('_runtimeCoordinator.registerTask(')));
  });

  test('new ACP entry points have one coordinator admission boundary', () {
    final agentSource = File(
      'lib/features/home/pages/chat/chat_page_agent.dart',
    ).readAsStringSync();
    final conversationSource = File(
      'lib/features/home/pages/chat/chat_page_conversation_flow.dart',
    ).readAsStringSync();

    // registerTask is the coordinator's internal binding primitive. New UI
    // entry points must call beginAcpTurn, which performs that binding and
    // activation atomically; keeping both calls at a call site creates two
    // admission boundaries for one logical ACP turn.
    expect(agentSource, isNot(contains('_runtimeCoordinator.registerTask(')));
    expect(
      conversationSource,
      isNot(contains('_runtimeCoordinator.registerTask(')),
    );
  });

  test('command overlay reserves an ACP session before starting a prompt', () {
    final source = File(
      'lib/features/home/pages/command_overlay/chat_bot_sheet.dart',
    ).readAsStringSync();
    final flowStart = source.indexOf(
      'Future<bool> _tryAgentFlow(String aiMessageId, String userMessageId)',
    );
    final flowEnd = source.indexOf('\n  bool _canContinueAcpTurn(', flowStart);
    expect(flowStart, greaterThanOrEqualTo(0));
    expect(flowEnd, greaterThan(flowStart));
    final flowBody = source.substring(flowStart, flowEnd);
    final newSessionIndex = flowBody.indexOf('AgentRuntimeService.newSession(');
    final bindSessionIndex = flowBody.indexOf(
      '_runtimeCoordinator.bindAcpSession(',
    );
    final promptIndex = flowBody.indexOf('AgentRuntimeService.promptSession(');
    expect(newSessionIndex, greaterThanOrEqualTo(0));
    expect(bindSessionIndex, greaterThan(newSessionIndex));
    expect(promptIndex, greaterThan(bindSessionIndex));
    expect(flowBody.substring(promptIndex), isNot(contains('sessionId: null')));
  });

  test('main chat prompts reserve and bind ACP sessions before prompt', () {
    final flowSource = File('$chatRoot/chat_page_conversation_flow.dart')
        .readAsStringSync();
    final agentSource = File('$chatRoot/chat_page_agent.dart')
        .readAsStringSync();

    expect(flowSource, contains('_prepareAcpSessionForTurn('));
    expect(flowSource, contains('AgentRuntimeService.promptSession('));
    expect(flowSource, contains('sessionId: acpSessionId'));
    expect(agentSource, contains('_prepareAcpSessionForTurn('));
    expect(agentSource, contains('sessionId: acpSessionId'));
    expect(flowSource, isNot(contains('sessionId: dispatchSessionId')));
    expect(agentSource, isNot(contains('sessionId: dispatchSessionId')));
  });

  test('Agent send reconciles a user item by id, never by repeated text', () {
    final source = File('$chatRoot/chat_page_agent.dart').readAsStringSync();
    final sendStart = source.indexOf('Future<void> _sendAgentMessage(');
    final sendEnd = source.indexOf(
      '// The preflight admission already owns this logical turn',
      sendStart,
    );
    expect(sendStart, greaterThanOrEqualTo(0));
    expect(sendEnd, greaterThan(sendStart));
    final sendBody = source.substring(sendStart, sendEnd);

    expect(sendBody, contains('required String userMessageId'));
    expect(sendBody, contains('final expectedUserId = userMessageId.trim();'));
    expect(sendBody, contains('message.id == expectedUserId'));
    expect(sendBody, isNot(contains('message.text == messageText')));
  });

  test(
    'granting a device permission never fabricates or replays a user turn',
    () {
      final flowSource = File('$chatRoot/chat_page_conversation_flow.dart')
          .readAsStringSync();
      final pageSource = File('$chatRoot/chat_page.dart').readAsStringSync();

      final authorizeStart = flowSource.indexOf(
        'Future<void> _requestAuthorizeForExecution(',
      );
      expect(authorizeStart, greaterThanOrEqualTo(0));
      final authorizeBody = flowSource.substring(authorizeStart);

      // Authorization is an environment change, not a user send. It must
      // retain the failed turn and wait for the user to explicitly retry it.
      expect(authorizeBody, isNot(contains('_tryAgentFlow(')));
      expect(authorizeBody, isNot(contains('_handleExecutableTaskFlow(')));
      expect(flowSource, isNot(contains('_retryLatestInstructionAfterAuth')));
      expect(flowSource, isNot(contains('_removeFailedAttemptMessages')));
      expect(
        pageSource,
        isNot(contains('_isRetryingLatestInstructionAfterAuth')),
      );
    },
  );

  test(
    'editing or retrying waits for the active ACP turn to reach its terminal',
    () {
      final actionSource = File('$chatRoot/chat_page_user_message_actions.dart')
          .readAsStringSync();
      final flowSource = File('$chatRoot/chat_page_conversation_flow.dart')
          .readAsStringSync();

      // `session/cancel` only notifies the Agent; it cannot be used as a
      // local terminal transition that clears history and admits another turn.
      expect(
        actionSource,
        contains('return !_isAiResponding && _isLatestUserMessage(message);'),
      );
      final clearStart = actionSource.indexOf(
        'Future<bool> _clearRetriedMessageRound(',
      );
      final clearEnd = actionSource.indexOf(
        '\n  Future<void> _copyUserMessageText',
        clearStart,
      );
      expect(clearStart, greaterThanOrEqualTo(0));
      expect(clearEnd, greaterThan(clearStart));
      final clearBody = actionSource.substring(clearStart, clearEnd);
      expect(clearBody, contains('if (_isAiResponding)'));
      expect(clearBody, contains('return false;'));
      expect(clearBody, isNot(contains('_onCancelTask()')));

      final retryStart = flowSource.indexOf(
        'Future<void> _retryUserMessageText(',
      );
      final retryEnd = flowSource.indexOf(
        '\n  Future<void> _dispatchUserMessage(',
        retryStart,
      );
      expect(retryStart, greaterThanOrEqualTo(0));
      expect(retryEnd, greaterThan(retryStart));
      final retryBody = flowSource.substring(retryStart, retryEnd);
      expect(retryBody, contains('if (_isAiResponding) return;'));
      expect(retryBody, isNot(contains('_onCancelTask()')));
    },
  );

  test('command overlay cancellation is owned by its ACP close lifecycle', () {
    final source = File(
      'lib/features/home/pages/command_overlay/chat_bot_sheet.dart',
    ).readAsStringSync();
    final closeStart = source.indexOf('void _onDialogClose()');
    final closeEnd = source.indexOf(
      '\n  @override\n  void didChangeMetrics',
      closeStart,
    );
    expect(closeStart, greaterThanOrEqualTo(0));
    expect(closeEnd, greaterThan(closeStart));
    final closeBody = source.substring(closeStart, closeEnd);
    expect(closeBody, contains('_closeAcpLifecycle('));
    expect(closeBody, contains('markComplete: !hasLiveTurn'));

    final disposeStart = source.indexOf('void dispose()');
    final disposeEnd = source.indexOf(
      '\n  void _onFocusChange()',
      disposeStart,
    );
    expect(disposeStart, greaterThanOrEqualTo(0));
    expect(disposeEnd, greaterThan(disposeStart));
    final disposeBody = source.substring(disposeStart, disposeEnd);
    expect(disposeBody, contains('_closeAcpLifecycle('));
    expect(disposeBody, contains('setOnBeforeCloseChatBotDialog(null)'));
  });
}
