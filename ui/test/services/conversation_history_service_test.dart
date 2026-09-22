import 'dart:async';

import 'package:flutter/foundation.dart';
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
    ConversationHistoryService.resetWriteAcknowledgements();
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
          final incoming = normalizeMessageList(args['messages']);
          if (args['allowHistoryRemoval'] == true) {
            nativeMessages[key] = incoming;
          } else {
            final byId = {
              for (final m in nativeMessages[key] ?? <Map<String, dynamic>>[])
                m['id']: m,
            };
            for (final m in incoming) {
              byId[m['id']] = m;
            }
            nativeMessages[key] = byId.values.toList();
          }
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

  test('repeated snapshots send only changed messages and explicit removal stays explicit', () async {
    final writes = <Map<String, dynamic>>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'replaceConversationMessages')
        writes.add(Map<String, dynamic>.from(call.arguments));
      return 'SUCCESS';
    });
    final old = ChatMessageModel(
      id: 'old',
      type: 1,
      user: 1,
      content: {'text': 'x' * 65536},
    );
    final reply = ChatMessageModel(
      id: 'reply',
      type: 1,
      user: 2,
      content: {'text': 'first'},
    );
    await ConversationHistoryService.saveConversationMessages(99112, [
      old,
      reply,
    ]);
    await ConversationHistoryService.saveConversationMessages(99112, [
      old,
      reply.copyWith(content: {'text': 'second'}),
    ]);
    expect((writes.last['messages'] as List).map((m) => m['id']), ['reply']);
    expect(writes.last['allowHistoryRemoval'], false);
    await ConversationHistoryService.saveConversationMessages(
      99112,
      [],
      allowHistoryRemoval: true,
    );
    expect(writes.last['messages'], isEmpty);
    expect(writes.last['allowHistoryRemoval'], true);
  });

  test('failed native writes are not acknowledged and clear invalidates acknowledgements', () async {
    var attempts = 0;
    final writes = <List<dynamic>>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'replaceConversationMessages') {
        attempts++;
        writes.add(List<dynamic>.from(call.arguments['messages']));
        if (attempts == 1)
          throw PlatformException(code: 'fixture-write-failed');
      }
      return 'SUCCESS';
    });
    final message = ChatMessageModel.userMessage('retain this')
        .copyWith(id: 'retained');
    await expectLater(
      ConversationHistoryService.saveConversationMessages(99113, [message]),
      throwsStateError,
    );
    final prefs = await SharedPreferences.getInstance();
    expect(prefs.getKeys().where((key) => key.contains('99113')), isEmpty);
    await ConversationHistoryService.saveConversationMessages(99113, [message]);
    expect(attempts, 2);
    expect(writes.every((rows) => rows.length == 1), true);
    await ConversationHistoryService.saveConversationMessages(99113, [message]);
    expect(attempts, 2);
    await ConversationHistoryService.clearConversationMessages(99113);
    await ConversationHistoryService.saveConversationMessages(99113, [message]);
    expect(attempts, 3);
  });

  test(
    'clear shares the write queue so an earlier write cannot resurrect history',
    () async {
      final entered = Completer<void>();
      final release = Completer<void>();
      final operations = <String>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'replaceConversationMessages') {
          entered.complete();
          await release.future;
          operations.add('write');
        }
        if (call.method == 'clearConversationMessages') operations.add('clear');
        return 'SUCCESS';
      });
      final write = ConversationHistoryService.saveConversationMessages(99114, [
        ChatMessageModel.userMessage('old'),
      ]);
      await entered.future;
      final clear = ConversationHistoryService.clearConversationMessages(99114);
      await Future<void>.delayed(Duration.zero);
      expect(operations, isEmpty);
      release.complete();
      await Future.wait([write, clear]);
      expect(operations, ['write', 'clear']);
    },
  );

  test('ten thousand unchanged display rows are never serialized again on streaming updates', () async {
    final page = List<ChatMessageModel>.generate(
      10000,
      (index) => _CountedMessage('$index', 'x' * 2048),
    );
    var submitted = 0;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'replaceConversationMessages')
        submitted += (call.arguments['messages'] as List).length;
      return 'SUCCESS';
    });
    _CountedMessage.serializations = 0;
    await ConversationHistoryService.saveConversationMessages(99199, page);
    expect(_CountedMessage.serializations, 10000);
    final timer = Stopwatch()..start();
    for (var chunk = 0; chunk < 20; chunk++) {
      page[0] = _CountedMessage('0', 'chunk $chunk');
      await ConversationHistoryService.saveConversationMessages(99199, page);
    }
    timer.stop();
    expect(_CountedMessage.serializations, 10020);
    expect(submitted, 10020);
    // Report timing without a machine-dependent threshold. The regression gate
    // is the amount of data serialized and submitted, independent of CPU speed.
    debugPrint(
      '10000 rows, 20 updates: ${timer.elapsedMilliseconds} ms; 20 changed rows serialized',
    );
  });

  test(
    'thread selection delegates to native storage and decodes its identity',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return {
          'conversationId': 42,
          'mode': 'agent',
          'agentId': 'claude-code-acp',
          'agentSessionId': 'session-42',
          'agentRuntime': 'local',
        };
      });
      final target =
          await ConversationHistoryService.getCurrentConversationTarget(
            mode: ConversationMode.normal,
          );
      expect(calls.single.method, 'conversationSelection');
      expect(calls.single.arguments, {
        'operation': 'getTarget',
        'mode': 'normal',
      });
      expect(target?.conversationId, 42);
      expect(target?.agentSessionId, 'session-42');
      expect(target?.agentId, 'claude-code-acp');
    },
  );

  test('round-trips remote agent session metadata', () {
    const target = ConversationThreadTarget.agentSession(
      sessionId: 'thread-active',
      runtime: 'remote',
      agentSessionActive: true,
      requestKey: 'request-1',
    );

    final restored = ConversationThreadTarget.fromEncodedJson(
      target.toEncodedJson(),
    );

    expect(restored, target);
    expect(restored.agentSessionActive, isTrue);
  });

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

  test('serializes conversation snapshot writes per thread', () async {
    final firstWriteStarted = Completer<void>();
    final releaseFirstWrite = Completer<void>();
    var replaceCallCount = 0;
    final persistedSnapshots = <List<Map<String, dynamic>>>[];

    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method != 'replaceConversationMessages') {
        return 'SUCCESS';
      }
      final arguments = Map<String, dynamic>.from(
        (call.arguments as Map).cast<String, dynamic>(),
      );
      persistedSnapshots.add(normalizeMessageList(arguments['messages']));
      replaceCallCount += 1;
      if (replaceCallCount == 1) {
        firstWriteStarted.complete();
        await releaseFirstWrite.future;
      }
      return 'SUCCESS';
    });

    final firstWrite = ConversationHistoryService.saveConversationMessages(
      7,
      <ChatMessageModel>[ChatMessageModel.userMessage('first')],
    );
    await firstWriteStarted.future;
    final secondWrite = ConversationHistoryService.saveConversationMessages(
      7,
      <ChatMessageModel>[ChatMessageModel.userMessage('latest')],
    );

    await Future<void>.delayed(Duration.zero);
    expect(replaceCallCount, 1);

    releaseFirstWrite.complete();
    await Future.wait(<Future<void>>[firstWrite, secondWrite]);

    expect(replaceCallCount, 2);
    expect(persistedSnapshots.last.single['content']['text'], 'latest');
  });

  test('decodes canonical Agent tool metadata supplied by Kotlin', () async {
    nativeMessages['agent:12'] = <Map<String, dynamic>>[
      ChatMessageModel.cardMessage(<String, dynamic>{
        'type': 'agent_tool_summary',
        'uiStyle': 'agent_tool',
        'agentId': 'claude-code-acp',
        'agentName': 'Claude Code',
        'toolName': 'agent.tool',
        'toolTitle': 'Read settings.json',
        'status': 'success',
      }, id: 'tool-12').toJson(),
    ];

    final restored = await ConversationHistoryService.getConversationMessages(
      12,
      mode: ConversationMode.agent,
    );

    expect(restored.single.cardData?['uiStyle'], 'agent_tool');
    expect(restored.single.cardData?['toolName'], 'agent.tool');
    expect(restored.single.agentId, 'claude-code-acp');
    expect(restored.single.agentName, 'Claude Code');
  });

  // Legacy bucket recovery now runs in Kotlin; its persistence and crash
  // behavior is covered by LegacyConversationHistoryTest.
  test('paged history only requests the current native page', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {
        'messages': [ChatMessageModel.userMessage('page', id: 'page').toJson()],
        'hasMore': true,
        'nextOffset': 1250,
      };
    });
    final page = await ConversationHistoryService.getConversationMessagesPaged(
      5,
      limit: 50,
      offset: 1200,
      expectedMessageCount: 20000,
    );
    expect(calls.map((call) => call.method), ['getConversationMessagesPaged']);
    expect(calls.single.arguments['offset'], 1200);
    expect(calls.single.arguments['limit'], 50);
    expect(page.messages.single.text, 'page');
    expect(page.hasMore, isTrue);
    expect(page.nextOffset, 1250);
  });

  test(
    'native cursor advances past filtered legacy assistant placeholders',
    () async {
      messenger.setMockMethodCallHandler(
        channel,
        (call) async => {
          'messages': [
            ChatMessageModel.userMessage('visible', id: 'visible').toJson(),
          ],
          'hasMore': true,
          'nextOffset': 52,
        },
      );
      final page =
          await ConversationHistoryService.getConversationMessagesPaged(
            5,
            offset: 50,
          );
      expect(page.messages.map((message) => message.id), ['visible']);
      expect(page.nextOffset, 52);
      expect(page.hasMore, isTrue);
    },
  );

  test('history failure propagates without replacing the visible conversation with empty history', () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      throw PlatformException(code: 'STORAGE_UNAVAILABLE');
    });
    await expectLater(
      ConversationHistoryService.getConversationMessages(5),
      throwsA(isA<PlatformException>()),
    );
    await expectLater(
      ConversationHistoryService.getConversationMessagesPaged(5),
      throwsA(isA<PlatformException>()),
    );
  });

  test(
    'Flutter does not parse or retire native-owned legacy snapshots',
    () async {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(
        'conversation_messages_normal_5',
        'native-owned legacy JSON',
      );
      nativeMessages['agent:5'] = [
        ChatMessageModel.userMessage('native result').toJson(),
      ];
      final messages = await ConversationHistoryService.getConversationMessages(
        5,
        expectedMessageCount: 0,
      );
      expect(messages.single.text, 'native result');
      expect(
        prefs.getString('conversation_messages_normal_5'),
        'native-owned legacy JSON',
      );
    },
  );

  test('clears conversation messages through native', () async {
    await ConversationHistoryService.saveConversationMessages(
      7,
      <ChatMessageModel>[ChatMessageModel.userMessage('to be cleared')],
      mode: ConversationMode.subagent,
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
  });
}

class _CountedMessage extends ChatMessageModel {
  _CountedMessage(String id, String text)
    : super(id: id, type: 1, user: 2, content: {'text': text});
  static int serializations = 0;
  @override
  Map<String, dynamic> toJson() {
    serializations++;
    return super.toJson();
  }
}
