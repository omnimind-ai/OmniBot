import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/mixins/conversation_manager.dart';
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
  });

  testWidgets(
    'explicit draft reservation creates an identity without a fake message',
    (tester) async {
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
      await key.currentState!.persistConversationSnapshot(allowEmpty: true);
      expect(key.currentState!.currentConversationId, 901);
      expect(key.currentState!.messages, isEmpty);
      await key.currentState!.persistConversationSnapshot(allowEmpty: true);
      expect(creates, 1);
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
  final List<int> persistedConversationIds = <int>[];
  final Map<int, List<ChatMessageModel>> _inMemorySnapshots =
      <int, List<ChatMessageModel>>{};
  final List<List<ChatMessageModel>> loadedSnapshots =
      <List<ChatMessageModel>>[];

  @override
  List<ChatMessageModel> get messages => _messages;

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
  ) => _inMemorySnapshots[conversationId];

  @override
  ConversationModel? getInMemoryConversationForConversation(
    int conversationId,
    ConversationMode mode,
  ) {
    return null;
  }

  @override
  void onConversationLoaded(
    ConversationMode mode,
    int conversationId,
    ConversationModel? conversation,
    List<ChatMessageModel> messages,
  ) {
    loadedConversationCount += 1;
    loadedSnapshots.add(messages);
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
