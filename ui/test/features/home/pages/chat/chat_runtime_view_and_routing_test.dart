import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';

/// The Flutter side of the chat runtime is a read-only mirror of native
/// snapshots plus a command forwarder. These tests drive it with recorded
/// native traffic; the projection itself is tested natively.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/ChatRuntime');
  final coordinator = ChatConversationRuntimeCoordinator.instance;
  late List<MethodCall> calls;
  late Map<String, dynamic> Function(MethodCall call) respond;

  Map<String, dynamic> message(String id, String text) => <String, dynamic>{
    'id': id,
    'type': 1,
    'user': 2,
    'content': <String, dynamic>{'text': text, 'id': id},
    'createAt': 1700000000000,
  };

  Map<String, dynamic> snapshot({
    required int conversationId,
    required int revision,
    required List<String> ids,
    List<Map<String, dynamic>> changed = const <Map<String, dynamic>>[],
    String mode = kChatRuntimeModeAgent,
    bool isAiResponding = false,
    String? activeRunId,
    List<String> boundTaskIds = const <String>[],
  }) => <String, dynamic>{
    'conversationId': conversationId,
    'mode': mode,
    'revision': revision,
    'messageIds': ids,
    'changedMessages': changed,
    'currentAiMessageKeys': <String>[],
    'currentThinkingMessageKeys': <String>[],
    'isAiResponding': isAiResponding,
    'activeRunId': activeRunId,
    'boundTaskIds': boundTaskIds,
    'isInputAreaVisible': true,
    'currentThinkingStage': 1,
    'chatIslandDisplayLayer': 'mode',
  };

  void deliver(
    List<Map<String, dynamic>> snapshots, [
    Map<String, int> removed = const <String, int>{},
  ]) {
    coordinator.debugHandleNativeEvent(<String, dynamic>{
      'type': 'sync',
      'snapshots': snapshots,
      'removed': removed,
    });
  }

  List<MethodCall> callsTo(String method) =>
      calls.where((call) => call.method == method).toList();

  setUp(() {
    coordinator.resetForTest();
    calls = <MethodCall>[];
    respond = (_) => <String, dynamic>{'result': null, 'sync': <dynamic>[]};
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return respond(call);
        });
  });

  tearDown(() {
    coordinator.resetForTest();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  group('snapshot mirror', () {
    test('renders native snapshots through a cached read-only view', () {
      deliver([
        snapshot(
          conversationId: 1,
          revision: 1,
          ids: ['b', 'a'],
          changed: [message('a', 'A'), message('b', 'B')],
          isAiResponding: true,
          activeRunId: 'run-1',
        ),
      ]);
      final view = coordinator.runtimeFor(
        conversationId: 1,
        mode: kChatRuntimeModeAgent,
      )!;
      expect(view.messages.map((m) => m.id), ['b', 'a']);
      expect(view.isAiResponding, isTrue);
      expect(view.hasInFlightTask, isTrue);
      expect(view.activeRunIdentity?.runId, 'run-1');
      expect(
        identical(
          view,
          coordinator.runtimeFor(
            conversationId: 1,
            mode: kChatRuntimeModeAgent,
          ),
        ),
        isTrue,
      );
      expect(
        () => view.messages.add(ChatMessageModel.userMessage('x')),
        throwsUnsupportedError,
      );
      expect(coordinator.activeAgentConversationIds, {1});
    });

    test('a streamed chunk updates one row and keeps the others', () {
      deliver([
        snapshot(
          conversationId: 2,
          revision: 1,
          ids: ['b', 'a'],
          changed: [message('a', 'A'), message('b', 'B')],
        ),
      ]);
      final view = coordinator.runtimeFor(
        conversationId: 2,
        mode: kChatRuntimeModeAgent,
      )!;
      final rowA = view.messages.listenableAt(1);
      final instanceA = view.messages[1];
      final structureBefore = view.messages.structureRevision;
      deliver([
        snapshot(
          conversationId: 2,
          revision: 2,
          ids: ['b', 'a'],
          changed: [message('b', 'B more')],
        ),
      ]);
      expect(view.messages[0].text, 'B more');
      expect(identical(view.messages[1], instanceA), isTrue);
      expect(identical(view.messages.listenableAt(1), rowA), isTrue);
      expect(view.messages.structureRevision, structureBefore);
      expect(
        view.messages.lastMutationKind,
        ChatMessageListMutationKind.content,
      );
    });

    test('late batches never roll a runtime back', () {
      deliver([
        snapshot(
          conversationId: 3,
          revision: 5,
          ids: ['a'],
          changed: [message('a', 'new')],
        ),
      ]);
      deliver([
        snapshot(
          conversationId: 3,
          revision: 4,
          ids: ['a'],
          changed: [message('a', 'old')],
        ),
      ]);
      expect(
        coordinator
            .runtimeFor(conversationId: 3, mode: kChatRuntimeModeAgent)!
            .messages
            .single
            .text,
        'new',
      );
      deliver(const [], {'agent:3': 6});
      expect(
        coordinator.runtimeFor(conversationId: 3, mode: kChatRuntimeModeAgent),
        isNull,
      );
      deliver([
        snapshot(
          conversationId: 3,
          revision: 5,
          ids: ['a'],
          changed: [message('a', 'resurrected')],
        ),
      ]);
      expect(
        coordinator.runtimeFor(conversationId: 3, mode: kChatRuntimeModeAgent),
        isNull,
      );
    });

    test('an unknown message id requests a full resync', () async {
      deliver([
        snapshot(conversationId: 4, revision: 1, ids: ['missing']),
      ]);
      await Future<void>.delayed(Duration.zero);
      expect(callsTo('resync').single.arguments, {
        'conversationId': 4,
        'mode': kChatRuntimeModeAgent,
      });
    });

    test('isTaskActive reads the bound task ids of the snapshot', () {
      deliver([
        snapshot(
          conversationId: 5,
          revision: 1,
          ids: const [],
          activeRunId: 'task-1',
          boundTaskIds: ['task-1'],
        ),
      ]);
      expect(
        coordinator.isTaskActive(
          taskId: 'task-1',
          conversationId: 5,
          mode: kChatRuntimeModeAgent,
        ),
        isTrue,
      );
      expect(
        coordinator.isTaskActive(
          taskId: 'task-2',
          conversationId: 5,
          mode: kChatRuntimeModeAgent,
        ),
        isFalse,
      );
    });
  });

  group('commands', () {
    test(
      'page writes apply to the mirror at once and are forwarded',
      () async {
        final view = coordinator.ensureRuntime(
          conversationId: 6,
          mode: kChatRuntimeModeNormal,
        );
        coordinator.insertRuntimeMessage(
          conversationId: 6,
          mode: kChatRuntimeModeNormal,
          message: ChatMessageModel.userMessage('hi', id: 'u1'),
        );
        coordinator.updateRuntimePresentation(
          conversationId: 6,
          mode: kChatRuntimeModeNormal,
          isAiResponding: true,
          chatIslandDisplayLayer: ChatIslandDisplayLayer.tools,
        );
        expect(view.messages.single.id, 'u1');
        expect(view.isAiResponding, isTrue);
        expect(view.chatIslandDisplayLayer, ChatIslandDisplayLayer.tools);
        await Future<void>.delayed(Duration.zero);
        expect(calls.map((call) => call.method), [
          'ensureRuntime',
          'insertRuntimeMessage',
          'updateRuntimePresentation',
        ]);
        expect(callsTo('updateRuntimePresentation').single.arguments, {
          'conversationId': 6,
          'mode': kChatRuntimeModeNormal,
          'isAiResponding': true,
          'chatIslandDisplayLayer': 'tools',
        });
        final inserted =
            callsTo('insertRuntimeMessage').single.arguments['message'] as Map;
        expect(inserted['id'], 'u1');
        expect(inserted['createAt'], isA<int>());
      },
    );

    test(
      'awaited commands return after their own snapshot is applied',
      () async {
        respond = (call) => <String, dynamic>{
          'result': <String, dynamic>{'handled': true, 'turnId': 'turn-9'},
          'sync': <dynamic>[
            <String, dynamic>{
              'type': 'sync',
              'snapshots': [
                snapshot(
                  conversationId: 7,
                  revision: 3,
                  ids: ['answer'],
                  changed: [message('answer', 'done')],
                ),
              ],
              'removed': <String, int>{},
            },
          ],
        };
        final result = await coordinator.applyAcpPromptResponse(
          taskId: 'task',
          conversationId: 7,
          sessionId: 's',
          stopReason: 'end_turn',
        );
        expect(result.handled, isTrue);
        expect(result.turnId, 'turn-9');
        expect(
          coordinator
              .runtimeFor(conversationId: 7, mode: kChatRuntimeModeAgent)!
              .messages
              .single
              .text,
          'done',
        );
      },
    );

    test('a page snapshot carries the revision it was built from', () async {
      deliver([snapshot(conversationId: 8, revision: 12, ids: const [])]);
      coordinator.replaceConversationSnapshot(
        conversationId: 8,
        mode: kChatRuntimeModeAgent,
        messages: const <ChatMessageModel>[],
      );
      await Future<void>.delayed(Duration.zero);
      final args = callsTo('replaceConversationSnapshot').single.arguments;
      expect(args['basedOnRevision'], 12);
      expect((args as Map).containsKey('currentAiMessages'), isFalse);
    });

    test('remote thread runtimes use the native runtime id', () async {
      final id = coordinator.ensureRemoteThreadRuntime('thread-abc');
      expect(id, remoteAgentRuntimeIdForThread('thread-abc'));
      expect(
        coordinator.isEphemeralRuntime(
          conversationId: id,
          mode: kChatRuntimeModeAgent,
        ),
        isTrue,
      );
      await Future<void>.delayed(Duration.zero);
      expect(callsTo('ensureRemoteThreadRuntime').single.arguments, {
        'threadId': 'thread-abc',
      });
    });
  });

  group('event route', () {
    test('publishes routing facts only when they change', () async {
      var visibleConversation = 10;
      final host = coordinator.attachEventHost(
        context: () => ChatRuntimeRoutingContext.page(
          activeMode: kChatRuntimeModeAgent,
          conversationIdsByMode: {kChatRuntimeModeAgent: visibleConversation},
        ),
        onOutcome: (_) {},
      );
      coordinator.beginAcpTurn(
        taskId: 't',
        conversationId: 10,
        mode: kChatRuntimeModeAgent,
      );
      visibleConversation = 11;
      coordinator.beginAcpTurn(
        taskId: 't2',
        conversationId: 11,
        mode: kChatRuntimeModeAgent,
      );
      await Future<void>.delayed(Duration.zero);
      final published = callsTo('setRoutingContexts');
      expect(published, hasLength(2));
      final last = (published.last.arguments['contexts'] as List).single as Map;
      expect(last['conversationIdsByMode'], {kChatRuntimeModeAgent: 11});
      expect(last['dispatchScoped'], isFalse);

      host.detach();
      await Future<void>.delayed(Duration.zero);
      expect(callsTo('setRoutingContexts').last.arguments['contexts'], isEmpty);
    });

    test('delivers each native outcome to every surface', () {
      final pageOutcomes = <ChatRuntimeEventOutcome>[];
      final sheetOutcomes = <ChatRuntimeEventOutcome>[];
      coordinator.attachEventHost(
        context: () =>
            const ChatRuntimeRoutingContext.page(activeMode: 'agent'),
        onOutcome: pageOutcomes.add,
      );
      coordinator.attachEventHost(
        context: () => null,
        onOutcome: sheetOutcomes.add,
      );
      coordinator.debugHandleNativeEvent(<String, dynamic>{
        'type': 'outcome',
        'event': <String, dynamic>{'method': 'session/update'},
        'conversationId': 12,
        'mode': kChatRuntimeModeAgent,
        'promotedRemoteThreadId': 'thread-1',
        'result': <String, dynamic>{
          'handled': true,
          'turnId': 'turn-1',
          'affectsActiveTurn': false,
          'compatibilityWarning': 'warn',
        },
      });
      expect(pageOutcomes.single.conversationId, 12);
      expect(pageOutcomes.single.promotedRemoteThreadId, 'thread-1');
      expect(pageOutcomes.single.result.handled, isTrue);
      expect(pageOutcomes.single.result.affectsActiveTurn, isFalse);
      expect(pageOutcomes.single.result.compatibilityWarning, 'warn');
      expect(sheetOutcomes, hasLength(1));
    });
  });
}
