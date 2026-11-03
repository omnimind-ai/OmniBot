import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/mixins/conversation_manager.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() async {
    messenger.setMockMethodCallHandler(channel, null);
    ChatConversationRuntimeCoordinator.instance.resetForTest();
  });

  testWidgets('empty snapshots never reserve a durable conversation', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    var creates = 0;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'createConversation') {
        creates++;
        return 901;
      }
      return 'SUCCESS';
    });
    final key = GlobalKey<_ConversationManagerHarnessState>();
    await tester.pumpWidget(
      MaterialApp(home: _ConversationManagerHarness(key)),
    );
    await key.currentState!.persistConversationSnapshot();
    expect(creates, 0);
    await key.currentState!.persistConversationSnapshot(
      markComplete: true,
      generateSummary: true,
    );
    expect(key.currentState!.currentConversationId, isNull);
    expect(key.currentState!.messages, isEmpty);
    expect(creates, 0);
    key.currentState!.messages.add(ChatMessageModel.userMessage('First input'));
    await key.currentState!.persistConversationSnapshot();
    expect(key.currentState!.currentConversationId, 901);
    expect(creates, 1);
  });

  testWidgets('offloaded thinking previews are never persisted as full cards', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final storedIds = <String>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'upsertConversationUiCard') {
        storedIds.add((call.arguments as Map)['entryId'] as String);
        return true;
      }
      return 'SUCCESS';
    });
    final key = GlobalKey<_ConversationManagerHarnessState>();
    await tester.pumpWidget(
      MaterialApp(home: _ConversationManagerHarness(key)),
    );
    final state = key.currentState!..currentConversationId = 1;
    state.messages.addAll([
      ChatMessageModel(
        id: 'preview',
        type: 2,
        user: 3,
        content: {
          'historyPayloadOffloaded': true,
          'cardData': {'type': 'deep_thinking', 'thinkingContent': 'preview'},
        },
      ),
      ChatMessageModel(
        id: 'complete',
        type: 2,
        user: 3,
        content: {
          'cardData': {'type': 'deep_thinking', 'thinkingContent': 'complete'},
        },
      ),
    ]);
    await state.persistConversationSnapshot();
    expect(storedIds, ['complete']);
  });

  testWidgets(
    'forced history refresh must reach the runtime owner before mutating its list',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final page = Completer<Map<String, dynamic>>();
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'getConversations')
          return [_conversationJson(id: 1, title: 'active')];
        if (call.method == 'getConversationMessagesPaged') return page.future;
        return 'SUCCESS';
      });
      final key = GlobalKey<_ConversationManagerHarnessState>();
      await tester.pumpWidget(
        MaterialApp(home: _ConversationManagerHarness(key)),
      );
      final state = key.currentState!..sharedRuntimeList = true;
      state.seedInMemoryConversation(1, [
        ChatMessageModel.assistantMessage('partial', id: 'reply'),
      ]);
      final loading = state.loadConversation(1, preferInMemory: false);
      await tester.pump();
      // ACP advances while a card-triggered database refresh is in flight.
      final completed = ChatMessageModel.assistantMessage(
        'complete response',
        id: 'reply',
      );
      state.seedInMemoryConversation(1, [completed]);
      page.complete({
        'messages': [_assistantMessageJson(id: 'reply', text: 'partial')],
        'hasMore': false,
      });
      await loading;
      expect(state.runtimeBeforeLoadCallback, [completed]);
      expect(state.loadedSnapshots.single.single.text, 'partial');
      expect(state.messages, [completed]);
    },
  );

  testWidgets('stale loadConversation result does not overwrite new thread', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final messagePageCompleter = Completer<Map<dynamic, dynamic>?>();

    messenger.setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'getConversations':
          return <Map<String, dynamic>>[
            _conversationJson(id: 1, title: 'old thread'),
          ];
        case 'getConversationMessagesPaged':
          return messagePageCompleter.future;
        default:
          return 'SUCCESS';
      }
    });

    final key = GlobalKey<_ConversationManagerHarnessState>();
    await tester.pumpWidget(
      MaterialApp(home: _ConversationManagerHarness(key)),
    );

    unawaited(key.currentState!.loadConversation(1));
    await tester.pump();

    expect(key.currentState!.currentConversationId, 1);

    key.currentState!.simulateThreadSwitchToBlank();

    messagePageCompleter.complete(<String, dynamic>{
      'messages': <Map<String, dynamic>>[
        _assistantMessageJson(id: 'assistant-1', text: 'persisted reply'),
      ],
      'hasMore': false,
    });
    await tester.pump();
    await tester.pump();

    expect(key.currentState!.currentConversationId, isNull);
    expect(key.currentState!.currentConversation, isNull);
    expect(key.currentState!.messages, isEmpty);
    expect(key.currentState!.loadedConversationCount, 0);
    expect(key.currentState!.historyPages, isEmpty);
  });

  testWidgets(
    'stale persistConversationSnapshot does not restore cleared thread state',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final createConversationCompleter = Completer<int?>();

      messenger.setMockMethodCallHandler(channel, (call) async {
        switch (call.method) {
          case 'createConversation':
            return createConversationCompleter.future;
          case 'updateConversation':
            return 'SUCCESS';
          default:
            return 'SUCCESS';
        }
      });

      final key = GlobalKey<_ConversationManagerHarnessState>();
      await tester.pumpWidget(
        MaterialApp(home: _ConversationManagerHarness(key)),
      );

      key.currentState!.seedDraftMessages(<ChatMessageModel>[
        ChatMessageModel.userMessage('hello'),
      ]);

      unawaited(key.currentState!.persistConversationSnapshot());
      await tester.pump();

      key.currentState!.simulateThreadSwitchToBlank();
      createConversationCompleter.complete(101);
      await tester.pump();
      await tester.pump();

      expect(key.currentState!.currentConversationId, isNull);
      expect(key.currentState!.currentConversation, isNull);
      expect(key.currentState!.persistedConversationIds, isEmpty);
    },
  );

  testWidgets(
    'loadConversation forwards the in-memory runtime snapshot after refresh',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final key = GlobalKey<_ConversationManagerHarnessState>();
      await tester.pumpWidget(
        MaterialApp(home: _ConversationManagerHarness(key)),
      );

      final persistedMessage = ChatMessageModel.assistantMessage(
        'reply retained in the runtime',
      );
      key.currentState!.seedInMemoryConversation(1, <ChatMessageModel>[
        persistedMessage,
      ]);

      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'getConversations') {
          return <Map<String, dynamic>>[
            _conversationJson(id: 1, title: 'existing thread'),
          ];
        }
        return 'SUCCESS';
      });

      await key.currentState!.loadConversation(1);

      expect(key.currentState!.loadedSnapshots, hasLength(1));
      expect(key.currentState!.loadedSnapshots.single, [persistedMessage]);
    },
  );

  testWidgets(
    'metadata refresh cannot reinstall a pre-completion runtime snapshot',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final metadata = Completer<List<Map<String, dynamic>>>();
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'getConversations') return metadata.future;
        throw StateError(
          'Populated runtime must not reload history: ${call.method}',
        );
      });
      final key = GlobalKey<_ConversationManagerHarnessState>();
      await tester.pumpWidget(
        MaterialApp(home: _ConversationManagerHarness(key)),
      );
      final user = ChatMessageModel.userMessage('continue', id: 'current-user');
      final answer = ChatMessageModel.assistantMessage(
        'finished',
        id: 'current-answer',
      );
      key.currentState!.seedInMemoryConversation(1, [user]);
      final loading = key.currentState!.loadConversation(1);
      await tester.pump();
      // The same ACP turn completes while the metadata request is in flight.
      key.currentState!.seedInMemoryConversation(1, [user, answer]);
      metadata.complete([_conversationJson(id: 1, title: 'existing thread')]);
      await loading;
      expect(key.currentState!.loadedSnapshots.single, [user, answer]);
    },
  );

  testWidgets(
    'history read cannot overwrite a runtime admitted while awaiting the page',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final page = Completer<Map<String, dynamic>>();
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'getConversations')
          return [_conversationJson(id: 1, title: 'existing thread')];
        if (call.method == 'getConversationMessagesPaged') return page.future;
        return 'SUCCESS';
      });
      final key = GlobalKey<_ConversationManagerHarnessState>();
      await tester.pumpWidget(
        MaterialApp(home: _ConversationManagerHarness(key)),
      );
      final loading = key.currentState!.loadConversation(1);
      await tester.pump();
      final user = ChatMessageModel.userMessage(
        'current request',
        id: 'current-user',
      );
      final answer = ChatMessageModel.assistantMessage(
        'completed',
        id: 'current-answer',
      );
      key.currentState!.seedInMemoryConversation(1, [user, answer]);
      page.complete({'messages': <Map<String, dynamic>>[], 'hasMore': false});
      await loading;
      expect(key.currentState!.loadedSnapshots.single, [user, answer]);
      expect(key.currentState!.messages, [user, answer]);
    },
  );

  testWidgets(
    'a conversation with more than one visible page remains fully reachable',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final allMessages = List<Map<String, dynamic>>.generate(
        51,
        (index) => _assistantMessageJson(
          id: 'assistant-${index + 1}',
          text: 'persisted reply ${index + 1}',
        ),
      );
      final pageOffsets = <int>[];

      messenger.setMockMethodCallHandler(channel, (call) async {
        switch (call.method) {
          case 'getConversations':
            return <Map<String, dynamic>>[
              _conversationJson(id: 1, title: 'long thread'),
            ];
          case 'getConversationMessagesPaged':
            final args = Map<dynamic, dynamic>.from(call.arguments as Map);
            final offset = (args['offset'] as num).toInt();
            final limit = (args['limit'] as num).toInt();
            pageOffsets.add(offset);
            final end = (offset + limit).clamp(0, allMessages.length).toInt();
            return <String, dynamic>{
              'messages': allMessages.sublist(offset, end),
              'hasMore': end < allMessages.length,
            };
          default:
            return 'SUCCESS';
        }
      });

      final key = GlobalKey<_ConversationManagerHarnessState>();
      await tester.pumpWidget(
        MaterialApp(home: _ConversationManagerHarness(key)),
      );

      await key.currentState!.loadConversation(1);
      expect(key.currentState!.messages, hasLength(50));
      expect(key.currentState!.hasMoreMessages, isTrue);

      await key.currentState!.loadMoreMessages();
      expect(pageOffsets, [0, 50]);
      expect(key.currentState!.messages, hasLength(51));
      expect(key.currentState!.messages.last.text, 'persisted reply 51');
      expect(key.currentState!.hasMoreMessages, isFalse);
    },
  );

  testWidgets('returning to a cached conversation restores its native cursor', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final requestedOffsets = <(int, int)>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'getConversations') {
        return [
          _conversationJson(id: 1, title: 'first'),
          _conversationJson(id: 2, title: 'second'),
        ];
      }
      if (call.method == 'getConversationMessagesPaged') {
        final args = call.arguments as Map;
        final id = args['conversationId'] as int;
        final offset = args['offset'] as int;
        requestedOffsets.add((id, offset));
        return {
          'messages': [
            _assistantMessageJson(id: '$id-$offset', text: 'history'),
          ],
          'hasMore': offset == 0,
          // Hidden storage rows and byte budgets make this differ from the
          // number of visible messages. Only the native cursor can advance it.
          'nextOffset': offset + 7,
        };
      }
      return 'SUCCESS';
    });
    final key = GlobalKey<_ConversationManagerHarnessState>();
    await tester.pumpWidget(
      MaterialApp(home: _ConversationManagerHarness(key)),
    );
    final state = key.currentState!;
    await state.loadConversation(1);
    state.seedInMemoryConversation(1, state.messages);
    await state.loadConversation(2);
    state.seedInMemoryConversation(2, state.messages);
    await state.loadConversation(1);
    expect(requestedOffsets, [(1, 0), (2, 0)]);
    expect(state.hasMoreMessages, isTrue);
    expect(state.messageOffset, 7);
    await state.loadMoreMessages();
    expect(requestedOffsets, [(1, 0), (2, 0), (1, 7)]);
    expect(state.messages.map((message) => message.id), ['1-0', '1-7']);
    expect(state.hasMoreMessages, isFalse);
    expect(state.messageOffset, 14);
  });

  testWidgets('runtime without a storage cursor can load older history', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final offsets = <int>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'getConversations') {
        return [_conversationJson(id: 1, title: 'live thread')];
      }
      if (call.method == 'getConversationMessagesPaged') {
        offsets.add((call.arguments as Map)['offset'] as int);
        return {
          'messages': [
            _assistantMessageJson(id: 'live', text: 'stale database copy'),
            _assistantMessageJson(id: 'older', text: 'older reply'),
          ],
          'hasMore': false,
          'nextOffset': 2,
        };
      }
      return 'SUCCESS';
    });
    final key = GlobalKey<_ConversationManagerHarnessState>();
    await tester.pumpWidget(
      MaterialApp(home: _ConversationManagerHarness(key)),
    );
    final state = key.currentState!;
    state.seedInMemoryConversation(1, [
      ChatMessageModel.assistantMessage('latest live reply', id: 'live'),
    ]);
    await state.loadConversation(1);
    expect(offsets, isEmpty);
    expect(state.hasMoreMessages, isTrue);
    expect(state.messageOffset, 0);
    await state.loadMoreMessages();
    expect(offsets, [0]);
    expect(state.messages.map((message) => message.id), ['live', 'older']);
    expect(state.messages.first.text, 'latest live reply');
    expect(state.hasMoreMessages, isFalse);
  });

  testWidgets(
    'late older page cannot replace the selected conversation cursor',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final oldPage = Completer<Map<String, dynamic>>();
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'getConversations') {
          return [
            _conversationJson(id: 1, title: 'old'),
            _conversationJson(id: 2, title: 'selected'),
          ];
        }
        if (call.method == 'getConversationMessagesPaged') {
          final args = call.arguments as Map;
          final id = args['conversationId'] as int;
          if (id == 1 && args['offset'] == 3) return oldPage.future;
          return {
            'messages': [_assistantMessageJson(id: '$id-first', text: 'reply')],
            'hasMore': id == 1,
            'nextOffset': id == 1 ? 3 : 1,
          };
        }
        return 'SUCCESS';
      });
      final key = GlobalKey<_ConversationManagerHarnessState>();
      await tester.pumpWidget(
        MaterialApp(home: _ConversationManagerHarness(key)),
      );
      final state = key.currentState!;
      await state.loadConversation(1);
      final loadingOlder = state.loadMoreMessages();
      await tester.pump();
      state.simulateThreadSwitchToBlank();
      await state.loadConversation(2);
      oldPage.complete({
        'messages': [_assistantMessageJson(id: 'old-late', text: 'old reply')],
        'hasMore': false,
        'nextOffset': 8,
      });
      await loadingOlder;
      expect(state.messages.single.id, '2-first');
      expect(state.hasMoreMessages, isFalse);
      expect(state.messageOffset, 1);
      expect(state.isLoadingMore, isFalse);
      expect(state.historyPages[1], (hasMore: true, nextOffset: 3));
      expect(state.historyPages[2], (hasMore: false, nextOffset: 1));
    },
  );

  testWidgets(
    'metadata recovery reads one page without writing partial totals',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final methods = <String>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        methods.add(call.method);
        switch (call.method) {
          case 'getConversations':
            return <Map<String, dynamic>>[];
          case 'getConversationMessagesPaged':
            expect((call.arguments as Map)['limit'], 50);
            expect((call.arguments as Map)['offset'], 0);
            return {
              'messages': [
                _assistantMessageJson(id: 'restored', text: 'reply'),
              ],
              'hasMore': true,
              'nextOffset': 3,
            };
          case 'getConversationMessages':
          case 'updateConversation':
            fail(
              'A partial recovery must not read all history or write its count',
            );
          default:
            return 'SUCCESS';
        }
      });
      final key = GlobalKey<_ConversationManagerHarnessState>();
      await tester.pumpWidget(
        MaterialApp(home: _ConversationManagerHarness(key)),
      );
      final state = key.currentState!..currentConversationId = 1;
      await state.checkConversationExists();
      expect(
        methods.where((method) => method == 'getConversationMessagesPaged'),
        hasLength(1),
      );
      expect(state.currentConversationId, 1);
      expect(state.messages.single.id, 'restored');
      expect(state.hasMoreMessages, isTrue);
      expect(state.messageOffset, 3);
      expect(state.historyPages[1], (hasMore: true, nextOffset: 3));
    },
  );

  testWidgets(
    'a late first page keeps live items and exposes earlier history',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final page = Completer<Map<String, dynamic>>();
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'getConversations') {
          return [_conversationJson(id: 1, title: 'thread')];
        }
        if (call.method == 'getConversationMessagesPaged') return page.future;
        return 'SUCCESS';
      });
      final key = GlobalKey<_ConversationManagerHarnessState>();
      await tester.pumpWidget(
        MaterialApp(home: _ConversationManagerHarness(key)),
      );
      final state = key.currentState!;
      final loading = state.loadConversation(1);
      await tester.pump();
      final live = ChatMessageModel.assistantMessage(
        'finished live',
        id: 'live',
      );
      state.seedInMemoryConversation(1, [live]);
      page.complete({
        'messages': [
          _assistantMessageJson(id: 'live', text: 'partial'),
          _assistantMessageJson(id: 'older', text: 'historical reply'),
        ],
        'hasMore': true,
        'nextOffset': 6,
      });
      await loading;
      expect(state.messages.map((message) => message.id), ['live', 'older']);
      expect(state.messages.first, same(live));
      expect(state.messageOffset, 6);
      expect(state.historyPages[1], (hasMore: true, nextOffset: 6));
    },
  );

  testWidgets(
    'a short history page advances by received messages without skipping a gap',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final allMessages = List<Map<String, dynamic>>.generate(
        68,
        (index) => _assistantMessageJson(
          id: 'assistant-${index + 1}',
          text: 'persisted reply ${index + 1}',
        ),
      );
      final pageOffsets = <int>[];

      messenger.setMockMethodCallHandler(channel, (call) async {
        switch (call.method) {
          case 'getConversations':
            return <Map<String, dynamic>>[
              _conversationJson(id: 1, title: 'short page thread'),
            ];
          case 'getConversationMessagesPaged':
            final args = Map<dynamic, dynamic>.from(call.arguments as Map);
            final offset = (args['offset'] as num).toInt();
            pageOffsets.add(offset);
            final end = (offset + 17).clamp(0, allMessages.length).toInt();
            return <String, dynamic>{
              'messages': allMessages.sublist(offset, end),
              'hasMore': end < allMessages.length,
            };
          default:
            return 'SUCCESS';
        }
      });

      final key = GlobalKey<_ConversationManagerHarnessState>();
      await tester.pumpWidget(
        MaterialApp(home: _ConversationManagerHarness(key)),
      );

      await key.currentState!.loadConversation(1);
      while (key.currentState!.hasMoreMessages) {
        await key.currentState!.loadMoreMessages();
      }

      expect(pageOffsets, [0, 17, 34, 51]);
      expect(key.currentState!.messages, hasLength(allMessages.length));
      expect(
        key.currentState!.messages.map((message) => message.text),
        allMessages
            .map((message) => (message['content'] as Map)['text'])
            .cast<String>(),
      );
    },
  );

  testWidgets(
    'deleting a loaded round rescans without skipping the original 51st row',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final coordinator = ChatConversationRuntimeCoordinator.instance;
      coordinator.resetForTest();
      final stored = List.generate(
        51,
        (index) =>
            _assistantMessageJson(id: 'row-$index', text: 'message $index'),
      );
      final offsets = <int>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'getConversations') {
          return [_conversationJson(id: 1, title: 'history')];
        }
        if (call.method == 'getConversationMessagesPaged') {
          final args = call.arguments as Map;
          final offset = args['offset'] as int;
          offsets.add(offset);
          final end = (offset + (args['limit'] as int)).clamp(0, stored.length);
          return {
            'messages': stored.sublist(offset, end),
            'hasMore': end < stored.length,
            'nextOffset': end,
          };
        }
        if (call.method == 'replaceConversationMessages') {
          final removed = ((call.arguments as Map)['removedMessageIds'] as List)
              .cast<String>()
              .toSet();
          stored.removeWhere((message) => removed.contains(message['id']));
        }
        return 'SUCCESS';
      });
      final key = GlobalKey<_ConversationManagerHarnessState>();
      await tester.pumpWidget(
        MaterialApp(home: _ConversationManagerHarness(key)),
      );
      final state = key.currentState!..runtimeCoordinator = coordinator;
      await state.loadConversation(1);
      expect(state.messages, hasLength(50));
      final removed = state.messages
          .take(5)
          .map((message) => message.id)
          .toList();
      await coordinator.persistConversationMessageSnapshot(
        conversationId: 1,
        mode: kChatRuntimeModeNormal,
        messages: state.messages.skip(5).toList(),
        allowHistoryRemoval: true,
        removedMessageIds: removed,
      );
      final runtime = coordinator.runtimeFor(
        conversationId: 1,
        mode: kChatRuntimeModeNormal,
      )!;
      expect(runtime.historyPage, (hasMore: true, nextOffset: 0));
      expect(runtime.historyPageRevision, 1);
      // Even a stale page-local flag must not override the owning runtime cursor.
      state.hasMoreMessages = false;
      await state.loadMoreMessages();
      expect(offsets, [0, 0]);
      expect(state.messages, hasLength(46));
      expect(state.messages.map((message) => message.id), contains('row-50'));
      expect(
        state.messages.any((message) => removed.contains(message.id)),
        isFalse,
      );
      expect(runtime.historyPage, (hasMore: false, nextOffset: 46));
    },
  );

  for (final refresh in [false, true]) {
    testWidgets(
      'pre-edit offset-zero ${refresh ? 'refresh' : 'older page'} cannot revive deleted items',
      (tester) async {
        SharedPreferences.setMockInitialValues(<String, Object>{});
        final coordinator = ChatConversationRuntimeCoordinator.instance;
        coordinator.resetForTest();
        final oldPage = Completer<Map<String, dynamic>>();
        var reads = 0;
        final offsets = <int>[];
        messenger.setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getConversations') {
            return [_conversationJson(id: 1, title: 'history')];
          }
          if (call.method == 'getConversationMessagesPaged') {
            offsets.add((call.arguments as Map)['offset'] as int);
            reads += 1;
            if (reads == 2) return oldPage.future;
            return {
              'messages': [
                _assistantMessageJson(id: 'remove-me', text: 'old'),
                _assistantMessageJson(id: 'keep', text: 'kept'),
              ],
              'hasMore': true,
              'nextOffset': 2,
            };
          }
          return 'SUCCESS';
        });
        final key = GlobalKey<_ConversationManagerHarnessState>();
        await tester.pumpWidget(
          MaterialApp(home: _ConversationManagerHarness(key)),
        );
        final state = key.currentState!..runtimeCoordinator = coordinator;
        await state.loadConversation(1);
        final runtime = coordinator.runtimeFor(
          conversationId: 1,
          mode: kChatRuntimeModeNormal,
        )!;
        runtime.historyPage = (hasMore: true, nextOffset: 0);
        final loading = refresh
            ? state.loadConversation(1, preferInMemory: false)
            : state.loadMoreMessages();
        await tester.pump();
        expect(offsets, [0, 0]);
        await coordinator.persistConversationMessageSnapshot(
          conversationId: 1,
          mode: kChatRuntimeModeNormal,
          messages: runtime.messages
              .where((message) => message.id != 'remove-me')
              .toList(),
          allowHistoryRemoval: true,
          removedMessageIds: ['remove-me'],
        );
        oldPage.complete({
          'messages': [_assistantMessageJson(id: 'remove-me', text: 'stale')],
          'hasMore': false,
          'nextOffset': 100,
        });
        await loading;
        expect(runtime.messages.map((message) => message.id), ['keep']);
        expect(runtime.historyPage, (hasMore: true, nextOffset: 0));
        expect(runtime.historyPageRevision, 1);
        expect(state.isLoadingMore, isFalse);
      },
    );
  }
}

class _ConversationManagerHarness extends StatefulWidget {
  const _ConversationManagerHarness(this.stateKey) : super(key: stateKey);

  final GlobalKey<_ConversationManagerHarnessState> stateKey;

  @override
  State<_ConversationManagerHarness> createState() =>
      _ConversationManagerHarnessState();
}

class _ConversationManagerHarnessState
    extends State<_ConversationManagerHarness>
    with ConversationManager<_ConversationManagerHarness> {
  final List<ChatMessageModel> _messages = <ChatMessageModel>[];
  int? _currentConversationId;
  ConversationModel? _currentConversation;
  bool _hasMoreMessages = false;
  bool _isLoadingMore = false;
  int _messageOffset = 0;
  int _lifecycleToken = 0;
  int loadedConversationCount = 0;
  bool sharedRuntimeList = false;
  List<ChatMessageModel>? runtimeBeforeLoadCallback;
  final List<int> persistedConversationIds = <int>[];
  final Map<int, List<ChatMessageModel>> _inMemorySnapshots =
      <int, List<ChatMessageModel>>{};
  final historyPages = <int, ({bool hasMore, int nextOffset})>{};
  ChatConversationRuntimeCoordinator? runtimeCoordinator;
  final List<List<ChatMessageModel>> loadedSnapshots =
      <List<ChatMessageModel>>[];

  @override
  List<ChatMessageModel> get messages =>
      runtimeCoordinator
          ?.runtimeFor(
            conversationId: _currentConversationId ?? -1,
            mode: kChatRuntimeModeNormal,
          )
          ?.messages ??
      (sharedRuntimeList
          ? (_inMemorySnapshots[_currentConversationId] ?? _messages)
          : _messages);

  @override
  int? get currentConversationId => _currentConversationId;

  @override
  set currentConversationId(int? value) => _currentConversationId = value;

  @override
  ConversationModel? get currentConversation => _currentConversation;

  @override
  set currentConversation(ConversationModel? value) =>
      _currentConversation = value;

  @override
  ConversationThreadTarget? get routeThreadTarget => null;

  @override
  ConversationMode get activeConversationModeValue => ConversationMode.normal;

  @override
  bool get hasMoreMessages => _hasMoreMessages;

  @override
  set hasMoreMessages(bool value) => _hasMoreMessages = value;

  @override
  bool get isLoadingMore => _isLoadingMore;

  @override
  set isLoadingMore(bool value) => _isLoadingMore = value;

  @override
  int get messageOffset => _messageOffset;

  @override
  set messageOffset(int value) => _messageOffset = value;

  @override
  int captureConversationLifecycleToken() => _lifecycleToken;

  @override
  bool isConversationLifecycleTokenCurrent(int token) =>
      token == _lifecycleToken;

  @override
  void invalidateConversationLifecycle() {
    _lifecycleToken += 1;
  }

  @override
  List<ChatMessageModel>? getInMemoryMessagesForConversation(
    int conversationId,
    ConversationMode mode,
  ) =>
      runtimeCoordinator
          ?.runtimeFor(
            conversationId: conversationId,
            mode: kChatRuntimeModeNormal,
          )
          ?.messages ??
      _inMemorySnapshots[conversationId];

  @override
  ConversationModel? getInMemoryConversationForConversation(
    int conversationId,
    ConversationMode mode,
  ) {
    return null;
  }

  @override
  ({bool hasMore, int nextOffset})? getInMemoryHistoryPageForConversation(
    int conversationId,
    ConversationMode mode,
  ) =>
      runtimeCoordinator
          ?.runtimeFor(
            conversationId: conversationId,
            mode: kChatRuntimeModeNormal,
          )
          ?.historyPage ??
      historyPages[conversationId];

  @override
  int getHistoryPageRevisionForConversation(
    int conversationId,
    ConversationMode mode,
  ) =>
      runtimeCoordinator
          ?.runtimeFor(
            conversationId: conversationId,
            mode: kChatRuntimeModeNormal,
          )
          ?.historyPageRevision ??
      0;

  @override
  void onConversationHistoryPageLoaded(
    ConversationMode mode,
    int conversationId, {
    required bool hasMore,
    required int nextOffset,
  }) {
    historyPages[conversationId] = (hasMore: hasMore, nextOffset: nextOffset);
    final runtime = runtimeCoordinator?.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    runtime?.historyPage = (hasMore: hasMore, nextOffset: nextOffset);
  }

  @override
  void onConversationLoaded(
    ConversationMode mode,
    int conversationId,
    ConversationModel? conversation,
    List<ChatMessageModel> messages,
  ) {
    runtimeBeforeLoadCallback = List<ChatMessageModel>.from(this.messages);
    loadedConversationCount += 1;
    loadedSnapshots.add(messages);
    if (runtimeCoordinator != null) {
      runtimeCoordinator!.replaceConversationSnapshot(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
        messages: messages,
        conversation: conversation,
      );
      return;
    }
    // Model the page callback: the runtime owner decides whether to install
    // the snapshot. A live/shared projection keeps its newer items.
    if (!sharedRuntimeList) {
      _messages
        ..clear()
        ..addAll(messages);
    }
  }

  @override
  void onConversationPersisted(
    ConversationMode mode,
    int conversationId,
    ConversationModel conversation,
    List<ChatMessageModel> messages,
  ) {
    persistedConversationIds.add(conversationId);
  }

  void simulateThreadSwitchToBlank() {
    invalidateConversationLifecycle();
    setState(() {
      _messages.clear();
      _currentConversationId = null;
      _currentConversation = null;
      _hasMoreMessages = false;
      _messageOffset = 0;
      _isLoadingMore = false;
    });
  }

  void seedDraftMessages(List<ChatMessageModel> values) {
    setState(() {
      _messages
        ..clear()
        ..addAll(values);
      _currentConversationId = null;
      _currentConversation = null;
    });
  }

  void seedInMemoryConversation(
    int conversationId,
    List<ChatMessageModel> values,
  ) {
    _inMemorySnapshots[conversationId] = List<ChatMessageModel>.from(values);
  }

  @override
  Widget build(BuildContext context) => const SizedBox.shrink();
}

Map<String, dynamic> _conversationJson({
  required int id,
  required String title,
}) {
  return <String, dynamic>{
    'id': id,
    'title': title,
    'mode': 'normal',
    'status': 0,
    'messageCount': 1,
    'createdAt': 1,
    'updatedAt': 2,
  };
}

Map<String, dynamic> _assistantMessageJson({
  required String id,
  required String text,
}) {
  return <String, dynamic>{
    'id': id,
    'type': 1,
    'user': 2,
    'content': <String, dynamic>{'id': id, 'text': text},
    'createAt': DateTime.fromMillisecondsSinceEpoch(1).toIso8601String(),
  };
}
