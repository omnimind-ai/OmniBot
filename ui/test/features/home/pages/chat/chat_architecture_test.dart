import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  const chatRoot = 'lib/features/home/pages/chat';

  test('only the prompt response completes the shared reducer', () {
    final reducer = File(
      'lib/services/agent_event_reducer.dart',
    ).readAsStringSync();
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

  test('remote notifications and snapshots do not decide prompt termination', () {
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
  });

  test(
    'normal chat delegates failures without inserting a second error message',
    () {
      final source = File(
        '$chatRoot/chat_page_conversation_flow.dart',
      ).readAsStringSync();
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

    expect(source, isNot(contains('Map<ChatPageMode,')));
    expect(source, contains('List<ChatPageModeState> _modeStates'));
  });

  test(
    'runtime and widget facades declare their focused implementation parts',
    () {
      final runtimeSource = File(
        '$chatRoot/services/chat_conversation_runtime_coordinator.dart',
      ).readAsStringSync();
      final widgetSource = File(
        '$chatRoot/widgets/chat_widgets.dart',
      ).readAsStringSync();

      for (final part in const <String>[
        'chat_runtime_message_support.dart',
        'chat_runtime_streaming_support.dart',
        'chat_runtime_thinking_support.dart',
        'chat_runtime_tool_support.dart',
      ]) {
        expect(runtimeSource, contains("part '$part';"));
      }
      for (final part in const <String>[
        'chat_app_bar.dart',
        'chat_input_wrapper.dart',
        'chat_message_list.dart',
        'chat_mode_slider.dart',
      ]) {
        expect(widgetSource, contains("part '$part';"));
      }
    },
  );

  test('Agent Flutter runtime exposes only the ACP lifecycle entry points', () {
    final reducerSource = File(
      'lib/services/agent_event_reducer.dart',
    ).readAsStringSync();
    final coordinatorSource = File(
      '$chatRoot/services/chat_conversation_runtime_coordinator.dart',
    ).readAsStringSync();
    final runtimeServiceSource = File(
      'lib/services/agent_runtime_service.dart',
    ).readAsStringSync();

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
    final flowEnd = source.indexOf(
      '\n  void _handleIncomingAcpRuntimeEvent(',
      flowStart,
    );
    expect(flowStart, greaterThanOrEqualTo(0));
    expect(flowEnd, greaterThan(flowStart));
    final flowBody = source.substring(flowStart, flowEnd);
    expect(flowBody, contains('_runtimeCoordinator.beginAcpTurn('));
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
    final flowEnd = source.indexOf(
      '\n  void _handleIncomingAcpRuntimeEvent(',
      flowStart,
    );
    expect(flowStart, greaterThanOrEqualTo(0));
    expect(flowEnd, greaterThan(flowStart));
    final flowBody = source.substring(flowStart, flowEnd);
    final newSessionIndex = flowBody.indexOf('AgentRuntimeService.newSession(');
    final promptIndex = flowBody.indexOf('AgentRuntimeService.promptSession(');
    expect(newSessionIndex, greaterThanOrEqualTo(0));
    expect(promptIndex, greaterThan(newSessionIndex));
    expect(flowBody.substring(promptIndex), isNot(contains('sessionId: null')));
  });

  test('main chat prompts reserve and bind ACP sessions before prompt', () {
    final flowSource = File(
      '$chatRoot/chat_page_conversation_flow.dart',
    ).readAsStringSync();
    final agentSource = File(
      '$chatRoot/chat_page_agent.dart',
    ).readAsStringSync();

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
      final flowSource = File(
        '$chatRoot/chat_page_conversation_flow.dart',
      ).readAsStringSync();
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
      final actionSource = File(
        '$chatRoot/chat_page_user_message_actions.dart',
      ).readAsStringSync();
      final flowSource = File(
        '$chatRoot/chat_page_conversation_flow.dart',
      ).readAsStringSync();

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
