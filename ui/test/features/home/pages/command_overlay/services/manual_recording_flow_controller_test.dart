import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_flow_controller.dart';

void main() {
  test('recognizes manual recording commands before agent dispatch', () {
    expect(ManualRecordingFlowController.isCommand('/record'), isTrue);
    expect(ManualRecordingFlowController.isCommand('手动录制'), isTrue);
    expect(
      ManualRecordingFlowController.isCommand(' manual recording '),
      isTrue,
    );
    expect(ManualRecordingFlowController.isCommand('帮我录制一个流程'), isFalse);
  });

  test('main chat routes manual recording before model configuration', () {
    final source = File(
      'lib/features/home/pages/chat/chat_page_conversation_flow.dart',
    ).readAsStringSync();
    final manualRoute = source.indexOf(
      'ManualRecordingFlowController.isCommand(messageText)',
    );
    final modelCheck = source.indexOf(
      '_ensureNormalChatModelConfigurationForSend()',
      manualRoute,
    );

    expect(manualRoute, greaterThanOrEqualTo(0));
    expect(modelCheck, greaterThan(manualRoute));
  });

  test('manual recording is exposed through the command panel', () {
    final commandPanelSource = File(
      'lib/features/home/pages/chat/chat_page_ui.dart',
    ).readAsStringSync();
    final composerSource = File(
      'lib/features/home/pages/command_overlay/widgets/chat_input_area_composer.dart',
    ).readAsStringSync();

    expect(commandPanelSource, contains("'cardId': 'slash-command-record'"));
    expect(commandPanelSource, contains("case '/record':"));
    expect(composerSource, isNot(contains('_buildManualRecordingButton')));
  });

  testWidgets('keeps recording completion inline when conversion fails', (
    tester,
  ) async {
    late BuildContext context;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (builderContext) {
            context = builderContext;
            return const SizedBox();
          },
        ),
      ),
    );

    final focusNode = FocusNode();
    addTearDown(focusNode.dispose);
    Map<String, dynamic>? insertedResult;

    final started = await ManualRecordingFlowController.start(
      context: context,
      inputFocusNode: focusNode,
      userMessageText: '手动录制',
      recordDebugScreenshots: false,
      isMounted: () => true,
      addUserMessage: (_) => const ManualRecordingFlowMessageIds(
        userMessageId: '',
        aiMessageId: '',
      ),
      beforeNativeRecording: () async {},
      afterNativeRecording: () async {},
      insertResultMessage: (_, result) => insertedResult = result,
      ensureAuthorized: (_) async => true,
      startNativeRecording:
          ({
            required name,
            required description,
            required enableDebugScreenshots,
          }) async => {
            'success': true,
            'run_log': <String, dynamic>{
              'schema_version': 'omniflow.canonical_run_log.v1',
              'run_id': 'manual-run-1',
              'goal': 'manual recording',
              'status': 'succeeded',
              'success': true,
              'steps': <dynamic>[],
            },
            'function_error': <String, dynamic>{
              'code': 'RUN_LOG_NO_REPLAYABLE_STEPS',
              'message': 'RunLog has no replayable steps',
            },
          },
    );

    expect(started, isTrue);
    expect((insertedResult?['run_log'] as Map?)?['run_id'], 'manual-run-1');
  });
}
