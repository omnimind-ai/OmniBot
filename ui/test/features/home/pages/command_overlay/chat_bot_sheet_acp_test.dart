import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/features/home/pages/command_overlay/chat_bot_sheet.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/chat_input_area.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/message_bubble.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const runtimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const eventsChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntimeEvents');
  const assistChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const speechChannel = MethodChannel('cn.com.omnimind.bot/SpeechRecognition');
  const screenChannel = MethodChannel('cn.com.omnimind.bot/ScreenDialogEvent');
  const voiceChannel = MethodChannel('cn.com.omnimind.bot/VoicePlayback');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final coordinator = ChatConversationRuntimeCoordinator.instance;

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    coordinator.resetForTest();
    messenger.setMockMethodCallHandler(eventsChannel, (_) async => null);
    messenger.setMockMethodCallHandler(speechChannel, (_) async => true);
    messenger.setMockMethodCallHandler(screenChannel, (_) async => null);
    messenger.setMockMethodCallHandler(voiceChannel, (_) async => true);
    messenger.setMockMethodCallHandler(assistChannel, (call) async {
      switch (call.method) {
        case 'createConversation':
          return 1001;
        case 'getConversations':
        case 'getSceneCatalog':
        case 'getSceneModelBindings':
          return <Map<String, dynamic>>[];
        case 'getSceneVoiceConfig':
          return <String, dynamic>{'autoPlay': false};
        default:
          return null;
      }
    });
  });

  tearDown(() {
    coordinator.resetForTest();
    for (final channel in <MethodChannel>[
      runtimeChannel,
      eventsChannel,
      assistChannel,
      speechChannel,
      screenChannel,
      voiceChannel,
    ]) {
      messenger.setMockMethodCallHandler(channel, null);
    }
  });

  void registerEarlyStopTests() {
    for (final blockedAt in <String>[
      'status',
      'status/disconnected',
      'connect',
      'session/new',
    ]) {
      testWidgets(
        'stopping during $blockedAt does not start a prompt or leak a session',
        (tester) async {
          final pending = Completer<Map<String, dynamic>>();
          final requests = <MethodCall>[];
          final waitingForStatus = blockedAt.startsWith('status');
          final blockedMethod = waitingForStatus ? 'status' : blockedAt;
          final lateResponse = blockedAt != 'session/new'
              ? <String, dynamic>{
                  'connected': blockedAt != 'status/disconnected',
                  'activeAgentId': 'test-agent',
                }
              : <String, dynamic>{'sessionId': 'late-session'};
          messenger.setMockMethodCallHandler(runtimeChannel, (call) async {
            requests.add(call);
            switch (call.method) {
              case 'status':
                final count = requests
                    .where((call) => call.method == 'status')
                    .length;
                if (waitingForStatus && count == 2) return pending.future;
                return <String, dynamic>{
                  'connected': blockedAt != 'connect' || pending.isCompleted,
                  'activeAgentId': 'test-agent',
                };
              case 'connect':
                return pending.future;
              case 'session/new':
                if (blockedAt == 'session/new' && !pending.isCompleted)
                  return pending.future;
                return <String, dynamic>{'sessionId': 'next-session'};
              case 'session/prompt':
                return <String, dynamic>{
                  'sessionId': 'next-session',
                  'stopReason': 'end_turn',
                };
              case 'session/cancel':
                return <String, dynamic>{'ok': true, 'cancelled': false};
              case 'session/close':
                return <String, dynamic>{'closed': true};
              default:
                return null;
            }
          });
          tester.view.physicalSize = const Size(1080, 2200);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          try {
            await tester.pumpWidget(
              MaterialApp(
                theme: AppTheme.lightTheme,
                localizationsDelegates: AppLocalizations.localizationsDelegates,
                supportedLocales: AppLocalizations.supportedLocales,
                locale: const Locale('zh'),
                home: const Scaffold(
                  body: ChatBotSheet(initialMessage: '先整理这份资料'),
                ),
              ),
            );
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 100));
            expect(
              requests.where((call) => call.method == blockedMethod),
              hasLength(waitingForStatus ? 2 : 1),
            );
            await tester.tap(
              find.byKey(const ValueKey('chat-input-send-or-stop-button')),
            );
            await tester.pump();
            expect(
              requests.where((call) => call.method == 'session/prompt'),
              isEmpty,
            );
            pending.complete(lateResponse);
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 100));
            expect(
              requests.where((call) => call.method == 'session/prompt'),
              isEmpty,
            );
            expect(
              requests.where((call) => call.method == 'connect'),
              hasLength(blockedAt == 'connect' ? 1 : 0),
            );
            expect(
              requests.where((call) => call.method == 'session/new'),
              hasLength(blockedAt == 'session/new' ? 1 : 0),
            );
            final closes = requests
                .where((call) => call.method == 'session/close')
                .toList();
            expect(closes, hasLength(blockedAt == 'session/new' ? 1 : 0));
            if (closes.isNotEmpty)
              expect(
                (closes.single.arguments as Map)['sessionId'],
                'late-session',
              );
            final runtime = coordinator.runtimeFor(
              conversationId: 1001,
              mode: 'command_overlay',
            )!;
            expect(runtime.isAiResponding, isFalse);
            expect(find.textContaining('启动失败'), findsNothing);
            expect(
              tester
                  .widgetList<MessageBubble>(find.byType(MessageBubble))
                  .where((bubble) => bubble.message.isError),
              isEmpty,
            );
            expect(
              tester
                  .widget<ChatInputArea>(find.byType(ChatInputArea))
                  .isProcessing,
              isFalse,
            );
            expect(
              runtime.messages.where((message) => message.text == '先整理这份资料'),
              hasLength(1),
            );
            expect(
              jsonEncode(
                runtime.messages.map((message) => message.content).toList(),
              ),
              isNot(contains('启动失败')),
            );

            await tester.enterText(
              find
                  .descendant(
                    of: find.byType(ChatInputArea),
                    matching: find.byType(TextField),
                  )
                  .first,
              '现在处理另一份资料',
            );
            await tester.pump();
            await tester.tap(
              find.byKey(const ValueKey('chat-input-send-or-stop-button')),
            );
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 100));
            expect(
              requests.where((call) => call.method == 'session/prompt'),
              hasLength(1),
            );
            expect(
              runtime.messages.where((message) => message.text == '现在处理另一份资料'),
              hasLength(1),
            );
            expect(
              tester
                  .widget<ChatInputArea>(find.byType(ChatInputArea))
                  .isProcessing,
              isFalse,
            );
            expect(tester.takeException(), isNull);
          } finally {
            if (!pending.isCompleted) pending.complete(lateResponse);
            await tester.pump();
            await tester.pumpWidget(const SizedBox.shrink());
            await tester.pump();
          }
        },
      );
    }
  }

  testWidgets(
    'overlay prompt results preserve output and release the composer across stop and failure paths',
    (tester) async {
      Future<void> emitUpdate(Map<String, dynamic> update) async {
        await messenger.handlePlatformMessage(
          eventsChannel.name,
          const StandardMethodCodec().encodeSuccessEnvelope(<String, dynamic>{
            'conversationId': 1001,
            'sessionId': 'overlay-session',
            'turnId': 'overlay-turn',
            'agentId': 'test-agent',
            'allowImplicitTurnAdmission': true,
            'message': <String, dynamic>{
              'method': 'session/update',
              'params': <String, dynamic>{
                'sessionId': 'overlay-session',
                'update': update,
              },
            },
          }),
          (_) {},
        );
        await tester.pump();
      }

      // Keep one app/event-channel lifetime while the user closes and reopens the
      // sheet. Only the conversation projection resets between scenarios.
      const scenarios = [
        (stop: false, closeBeforePrompt: false, result: 'end_turn'),
        (stop: false, closeBeforePrompt: false, result: 'error'),
        (stop: true, closeBeforePrompt: false, result: 'cancelled'),
        (stop: true, closeBeforePrompt: true, result: 'cancelled'),
        (stop: true, closeBeforePrompt: true, result: 'end_turn'),
        (stop: true, closeBeforePrompt: true, result: 'error'),
      ];
      for (final scenario in scenarios) {
        coordinator.resetForTest();
        final prompt = Completer<Map<String, dynamic>>();
        final followup = Completer<Map<String, dynamic>>();
        final close = Completer<Map<String, dynamic>>();
        final requests = <MethodCall>[];
        messenger.setMockMethodCallHandler(runtimeChannel, (call) async {
          requests.add(call);
          switch (call.method) {
            case 'status':
              return <String, dynamic>{
                'connected': true,
                'activeAgentId': 'test-agent',
              };
            case 'session/new':
              return <String, dynamic>{
                'sessionId':
                    requests
                            .where((call) => call.method == 'session/new')
                            .length ==
                        1
                    ? 'overlay-session'
                    : 'followup-session',
              };
            case 'session/prompt':
              return requests
                          .where((call) => call.method == 'session/prompt')
                          .length ==
                      1
                  ? prompt.future
                  : followup.future;
            case 'session/cancel':
              return <String, dynamic>{'ok': true, 'cancelled': true};
            case 'session/close':
              return close.future;
            default:
              return null;
          }
        });
        tester.view.physicalSize = const Size(1080, 2200);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        try {
          await tester.pumpWidget(
            MaterialApp(
              theme: AppTheme.lightTheme,
              localizationsDelegates: AppLocalizations.localizationsDelegates,
              supportedLocales: AppLocalizations.supportedLocales,
              locale: const Locale('zh'),
              home: const Scaffold(
                body: ChatBotSheet(initialMessage: '整理这份资料'),
              ),
            ),
          );
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 100));
          expect(
            requests.where((call) => call.method == 'session/prompt'),
            hasLength(1),
          );
          final runtime = coordinator.runtimeFor(
            conversationId: 1001,
            mode: 'command_overlay',
          )!;
          expect(runtime.isAiResponding, isTrue);
          await emitUpdate(<String, dynamic>{
            'sessionUpdate': 'tool_call',
            'toolCallId': 'read-document',
            'title': '读取资料',
            'kind': 'read',
            'status': 'in_progress',
          });

          if (scenario.stop) {
            await tester.tap(
              find.byKey(const ValueKey('chat-input-send-or-stop-button')),
            );
            await tester.pump();
            expect(
              requests.where((call) => call.method == 'session/cancel'),
              hasLength(1),
            );
            expect(
              requests.where((call) => call.method == 'session/close'),
              hasLength(1),
            );
            expect(prompt.isCompleted, isFalse);
            expect(
              runtime.isAiResponding,
              isTrue,
              reason: 'A cancellation acknowledgement is not PromptResponse.',
            );
            if (scenario.closeBeforePrompt) {
              close.complete(<String, dynamic>{'closed': true});
              await tester.pump();
              expect(runtime.isAiResponding, isTrue);
            }
          }

          await emitUpdate(<String, dynamic>{
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'last-output',
            'content': <String, dynamic>{'type': 'text', 'text': '已整理好的结果'},
          });
          await emitUpdate(<String, dynamic>{
            'sessionUpdate': 'tool_call_update',
            'toolCallId': 'read-document',
            'status': 'completed',
            'rawOutput': <String, dynamic>{'text': '工具已经读取的完整结果'},
          });
          expect(
            runtime.messages.any((message) => message.text == '已整理好的结果'),
            isTrue,
          );
          if (scenario.result == 'error') {
            prompt.completeError(
              PlatformException(
                code: 'connection_lost',
                message: 'ACP transport disconnected',
              ),
            );
          } else {
            prompt.complete(<String, dynamic>{
              'sessionId': 'overlay-session',
              'stopReason': scenario.result,
            });
          }
          if (!close.isCompleted)
            close.complete(<String, dynamic>{'closed': true});
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 100));
          expect(runtime.isAiResponding, isFalse);
          expect(
            tester
                .widget<ChatInputArea>(find.byType(ChatInputArea))
                .isProcessing,
            isFalse,
          );
          expect(
            runtime.messages.any((message) => message.text == '整理这份资料'),
            isTrue,
          );
          expect(
            runtime.messages.any((message) => message.text == '已整理好的结果'),
            isTrue,
          );
          final contents = jsonEncode(
            runtime.messages.map((message) => message.content).toList(),
          );
          expect(contents, contains('工具已经读取的完整结果'));
          if (scenario.result == 'error') {
            expect(contents, contains('ACP transport disconnected'));
          }
          expect(
            runtime.messages.where(
              (message) => message.cardData?['toolCallId'] == 'read-document',
            ),
            hasLength(1),
          );
          expect(
            runtime.messages
                .singleWhere(
                  (message) =>
                      message.cardData?['toolCallId'] == 'read-document',
                )
                .cardData?['status'],
            'success',
          );
          expect(
            requests.where((call) => call.method == 'session/prompt'),
            hasLength(1),
          );
          expect(
            requests.where((call) => call.method == 'session/cancel'),
            hasLength(scenario.stop ? 1 : 0),
          );
          // The next user send is a new prompt, not an automatic replay of
          // the cancelled/failed request. Exercise the actual composer again.
          await tester.enterText(
            find
                .descendant(
                  of: find.byType(ChatInputArea),
                  matching: find.byType(TextField),
                )
                .first,
            '继续整理第二份资料',
          );
          await tester.pump();
          await tester.tap(
            find.byKey(const ValueKey('chat-input-send-or-stop-button')),
          );
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 100));
          final prompts = requests
              .where((call) => call.method == 'session/prompt')
              .toList();
          expect(prompts, hasLength(2));
          final firstArgs = prompts.first.arguments as Map;
          final secondArgs = prompts.last.arguments as Map;
          expect(secondArgs['requestId'], isNot(firstArgs['requestId']));
          expect(secondArgs['sessionId'], 'followup-session');
          expect(runtime.isAiResponding, isTrue);
          followup.complete(<String, dynamic>{
            'sessionId': 'followup-session',
            'stopReason': 'end_turn',
          });
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 100));
          expect(
            tester
                .widget<ChatInputArea>(find.byType(ChatInputArea))
                .isProcessing,
            isFalse,
          );
          expect(
            runtime.messages.where((message) => message.text == '继续整理第二份资料'),
            hasLength(1),
          );
          expect(
            runtime.messages.where(
              (message) => message.cardData?['toolCallId'] == 'read-document',
            ),
            hasLength(1),
          );
          expect(tester.takeException(), isNull);
        } finally {
          if (!prompt.isCompleted)
            prompt.complete(<String, dynamic>{'stopReason': 'cancelled'});
          if (!followup.isCompleted)
            followup.complete(<String, dynamic>{'stopReason': 'cancelled'});
          if (!close.isCompleted)
            close.complete(<String, dynamic>{'closed': true});
          await tester.pump();
          await tester.pumpWidget(const SizedBox.shrink());
          await tester.pump();
        }
      }
    },
  );
  // Keep the event-emitting scenarios in one native EventChannel lifetime.
  registerEarlyStopTests();
}
