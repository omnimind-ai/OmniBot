import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/conversation_history_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late Map<String, List<Map<String, dynamic>>> nativeMessages;

  String threadKey(int conversationId, ConversationMode mode) {
    return '${mode.storageValue}:$conversationId';
  }

  List<Map<String, dynamic>> normalizeMessageList(dynamic raw) {
    return ((raw as List?) ?? const <dynamic>[])
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .toList();
  }

  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    nativeMessages = <String, List<Map<String, dynamic>>>{};
    messenger.setMockMethodCallHandler(channel, (call) async {
      final args = Map<String, dynamic>.from(
        (call.arguments as Map?) ?? const {},
      );
      final conversationId = (args['conversationId'] as num?)?.toInt() ?? 0;
      final mode = ConversationMode.fromStorageValue(args['mode'] as String?);
      final key = threadKey(conversationId, mode);
      switch (call.method) {
        case 'replaceConversationMessages':
          nativeMessages[key] = normalizeMessageList(args['messages']);
          return 'SUCCESS';
        case 'getConversationMessages':
          return nativeMessages[key] ?? <Map<String, dynamic>>[];
        case 'getConversationMessagesPaged':
          final allMessages = nativeMessages[key] ?? <Map<String, dynamic>>[];
          final limit = (args['limit'] as num?)?.toInt() ?? 20;
          final offset = (args['offset'] as num?)?.toInt() ?? 0;
          final start = offset.clamp(0, allMessages.length).toInt();
          final end = (start + limit).clamp(0, allMessages.length).toInt();
          return <String, dynamic>{
            'messages': allMessages.sublist(start, end),
            'hasMore': end < allMessages.length,
          };
        case 'clearConversationMessages':
          nativeMessages.remove(key);
          return 'SUCCESS';
        default:
          return null;
      }
    });
  });

  tearDown(() async {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('stores current conversation ids independently per mode', () async {
    await ConversationHistoryService.saveCurrentConversationId(
      11,
      mode: ConversationMode.normal,
    );
    await ConversationHistoryService.saveCurrentConversationId(
      22,
      mode: ConversationMode.openclaw,
    );

    expect(
      await ConversationHistoryService.getCurrentConversationId(
        mode: ConversationMode.normal,
      ),
      11,
    );
    expect(
      await ConversationHistoryService.getCurrentConversationId(
        mode: ConversationMode.openclaw,
      ),
      22,
    );
  });

  test('stores blank current thread targets independently per mode', () async {
    const normalTarget = ConversationThreadTarget.newConversation(
      mode: ConversationMode.normal,
    );
    const chatOnlyTarget = ConversationThreadTarget.newConversation(
      mode: ConversationMode.chatOnly,
    );
    const openClawTarget = ConversationThreadTarget.existing(
      conversationId: 22,
      mode: ConversationMode.openclaw,
    );

    await ConversationHistoryService.saveCurrentConversationTarget(
      normalTarget,
      mode: ConversationMode.normal,
    );
    await ConversationHistoryService.saveCurrentConversationTarget(
      chatOnlyTarget,
      mode: ConversationMode.chatOnly,
    );
    await ConversationHistoryService.saveCurrentConversationTarget(
      openClawTarget,
      mode: ConversationMode.openclaw,
    );

    expect(
      await ConversationHistoryService.getCurrentConversationTarget(
        mode: ConversationMode.normal,
      ),
      normalTarget,
    );
    expect(
      await ConversationHistoryService.getCurrentConversationTarget(
        mode: ConversationMode.chatOnly,
      ),
      chatOnlyTarget,
    );
    expect(
      await ConversationHistoryService.getCurrentConversationTarget(
        mode: ConversationMode.openclaw,
      ),
      openClawTarget,
    );
    expect(
      await ConversationHistoryService.getCurrentConversationId(
        mode: ConversationMode.normal,
      ),
      isNull,
    );
  });

  test('round-trips chat_only storage keys through parser', () {
    final parsed = ConversationHistoryService.tryParseConversationMessagesKey(
      ConversationHistoryService.conversationMessagesKey(
        9,
        mode: ConversationMode.chatOnly,
      ),
    );

    expect(parsed, isNotNull);
    expect(parsed!.conversationId, 9);
    expect(parsed.mode, ConversationMode.chatOnly);
    expect(parsed.threadKey, 'chat_only:9');
  });

  test('round-trips last visible thread target with mode metadata', () async {
    const target = ConversationThreadTarget.existing(
      conversationId: 42,
      mode: ConversationMode.openclaw,
    );

    await ConversationHistoryService.saveLastVisibleThreadTarget(target);
    final restored =
        await ConversationHistoryService.getLastVisibleThreadTarget();

    expect(restored, target);
  });

  test('round-trips remote codex active thread target metadata', () {
    const target = ConversationThreadTarget.codexSession(
      threadId: 'thread-active',
      runtime: 'remote',
      codexThreadActive: true,
      requestKey: 'request-1',
    );

    final restored = ConversationThreadTarget.fromEncodedJson(
      target.toEncodedJson(),
    );

    expect(restored, target);
    expect(restored.codexThreadActive, isTrue);
  });

  test('round-trips local codex conversation target thread metadata', () async {
    const target = ConversationThreadTarget.existing(
      conversationId: 42,
      mode: ConversationMode.codex,
      codexThreadId: '019f12d6-16a0-7f01-9537-275ff25b9f79',
      codexRuntime: 'local',
    );

    await ConversationHistoryService.saveCurrentConversationTarget(
      target,
      mode: ConversationMode.codex,
    );
    await ConversationHistoryService.saveLastVisibleThreadTarget(target);

    expect(
      await ConversationHistoryService.getCurrentConversationTarget(
        mode: ConversationMode.codex,
      ),
      target,
    );
    expect(
      await ConversationHistoryService.getLastVisibleThreadTarget(),
      target,
    );
  });

  test(
    'falls back to current thread target when last visible is absent',
    () async {
      const target = ConversationThreadTarget.newConversation(
        mode: ConversationMode.normal,
      );

      await ConversationHistoryService.saveCurrentConversationTarget(
        target,
        mode: ConversationMode.normal,
      );

      expect(
        await ConversationHistoryService.getLastVisibleThreadTarget(),
        target,
      );
    },
  );

  test(
    'stores conversation messages independently per mode through native',
    () async {
      await ConversationHistoryService.saveConversationMessages(
        1,
        <ChatMessageModel>[ChatMessageModel.userMessage('normal thread')],
        mode: ConversationMode.normal,
      );
      await ConversationHistoryService.saveConversationMessages(
        2,
        <ChatMessageModel>[ChatMessageModel.userMessage('openclaw thread')],
        mode: ConversationMode.openclaw,
      );

      final normalMessages =
          await ConversationHistoryService.getConversationMessages(
            1,
            mode: ConversationMode.normal,
          );
      final openClawMessages =
          await ConversationHistoryService.getConversationMessages(
            2,
            mode: ConversationMode.openclaw,
          );

      expect(normalMessages.single.text, 'normal thread');
      expect(openClawMessages.single.text, 'openclaw thread');
    },
  );

  test(
    'migrates mode-scoped legacy messages when native storage is empty',
    () async {
      final prefs = await SharedPreferences.getInstance();
      final legacyMessages = <ChatMessageModel>[
        ChatMessageModel.userMessage('legacy normal thread'),
      ];
      await prefs.setString(
        ConversationHistoryService.conversationMessagesKey(
          3,
          mode: ConversationMode.normal,
        ),
        jsonEncode(legacyMessages.map((message) => message.toJson()).toList()),
      );

      final restored = await ConversationHistoryService.getConversationMessages(
        3,
        mode: ConversationMode.normal,
      );

      expect(restored.single.text, 'legacy normal thread');
      expect(nativeMessages['normal:3']?.single['id'], restored.single.id);
      expect(
        prefs.getString(
          ConversationHistoryService.conversationMessagesKey(
            3,
            mode: ConversationMode.normal,
          ),
        ),
        isNull,
      );
    },
  );

  test('migrates pre-mode legacy normal messages', () async {
    final prefs = await SharedPreferences.getInstance();
    final legacyMessages = <ChatMessageModel>[
      ChatMessageModel.userMessage('legacy before modes'),
    ];
    await prefs.setString(
      'conversation_messages_4',
      jsonEncode(legacyMessages.map((message) => message.toJson()).toList()),
    );

    final restored = await ConversationHistoryService.getConversationMessages(
      4,
      mode: ConversationMode.normal,
    );

    expect(restored.single.text, 'legacy before modes');
    expect(nativeMessages['normal:4'], hasLength(1));
    expect(prefs.getString('conversation_messages_4'), isNull);
  });

  test('paged load restores first page from legacy storage', () async {
    final prefs = await SharedPreferences.getInstance();
    final legacyMessages = <ChatMessageModel>[
      ChatMessageModel.userMessage('newest'),
      ChatMessageModel.userMessage('middle'),
      ChatMessageModel.userMessage('oldest'),
    ];
    await prefs.setString(
      ConversationHistoryService.conversationMessagesKey(
        5,
        mode: ConversationMode.chatOnly,
      ),
      jsonEncode(legacyMessages.map((message) => message.toJson()).toList()),
    );

    final restored =
        await ConversationHistoryService.getConversationMessagesPaged(
          5,
          mode: ConversationMode.chatOnly,
          limit: 2,
          offset: 0,
        );

    expect(restored.messages.map((message) => message.text), [
      'newest',
      'middle',
    ]);
    expect(restored.hasMore, isTrue);
    expect(nativeMessages['chat_only:5'], hasLength(3));
  });

  test('merges partial native history with richer legacy snapshot', () async {
    final prefs = await SharedPreferences.getInstance();
    nativeMessages['normal:6'] = <Map<String, dynamic>>[
      ChatMessageModel.userMessage(
        'new native message',
        id: 'native-new',
      ).toJson(),
    ];
    final legacyMessages = <ChatMessageModel>[
      ChatMessageModel.userMessage('legacy newest', id: 'legacy-newest'),
      ChatMessageModel.userMessage('legacy oldest', id: 'legacy-oldest'),
    ];
    await prefs.setString(
      ConversationHistoryService.conversationMessagesKey(
        6,
        mode: ConversationMode.normal,
      ),
      jsonEncode(legacyMessages.map((message) => message.toJson()).toList()),
    );

    final restored = await ConversationHistoryService.getConversationMessages(
      6,
      mode: ConversationMode.normal,
    );

    expect(
      restored.map((message) => message.text),
      contains('new native message'),
    );
    expect(restored.map((message) => message.text), contains('legacy newest'));
    expect(restored.map((message) => message.text), contains('legacy oldest'));
    expect(nativeMessages['normal:6'], hasLength(3));
    expect(
      prefs.getString(
        ConversationHistoryService.conversationMessagesKey(
          6,
          mode: ConversationMode.normal,
        ),
      ),
      isNull,
    );
  });

  test('does not revive legacy messages when metadata expects none', () async {
    final prefs = await SharedPreferences.getInstance();
    final legacyMessages = <ChatMessageModel>[
      ChatMessageModel.userMessage('stale cleared message'),
    ];
    await prefs.setString(
      ConversationHistoryService.conversationMessagesKey(
        8,
        mode: ConversationMode.normal,
      ),
      jsonEncode(legacyMessages.map((message) => message.toJson()).toList()),
    );

    final restored = await ConversationHistoryService.getConversationMessages(
      8,
      mode: ConversationMode.normal,
      expectedMessageCount: 0,
    );

    expect(restored, isEmpty);
    expect(nativeMessages['normal:8'], isNull);
    expect(
      prefs.getString(
        ConversationHistoryService.conversationMessagesKey(
          8,
          mode: ConversationMode.normal,
        ),
      ),
      isNull,
    );
  });

  test('clears conversation messages through native', () async {
    await ConversationHistoryService.saveConversationMessages(
      7,
      <ChatMessageModel>[ChatMessageModel.userMessage('to be cleared')],
      mode: ConversationMode.subagent,
    );
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      ConversationHistoryService.conversationMessagesKey(
        7,
        mode: ConversationMode.subagent,
      ),
      '[]',
    );

    await ConversationHistoryService.clearConversationMessages(
      7,
      mode: ConversationMode.subagent,
    );

    final messages = await ConversationHistoryService.getConversationMessages(
      7,
      mode: ConversationMode.subagent,
    );
    expect(messages, isEmpty);
    expect(
      prefs.getString(
        ConversationHistoryService.conversationMessagesKey(
          7,
          mode: ConversationMode.subagent,
        ),
      ),
      isNull,
    );
  });
}
