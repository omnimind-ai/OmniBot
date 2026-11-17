import 'dart:collection';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/chat_message_model.dart';

void main() {
  test(
    'history preview identity and full-record attachment survive roundtrip',
    () {
      final message = ChatMessageModel.fromJson({
        'id': 'history-preview',
        'type': 2,
        'user': 3,
        'content': {
          'id': 'history-preview',
          'historyPayloadOffloaded': true,
          'cardData': {
            'type': 'deep_thinking',
            'historyPayloadOffloaded': true,
            'thinkingContent': 'preview',
          },
          'attachments': [
            {'name': 'original.txt', 'path': '/workspace/original.txt'},
            {
              'name': '完整消息记录',
              'path': '/workspace/full-history.json',
              'historyPayloadOffloaded': true,
            },
          ],
        },
        'streamMeta': {'sessionId': 'session-1', 'turnId': 'turn-1'},
      });

      final restored = ChatMessageModel.fromJson(message.toJson());
      expect(restored.isHistoryPayloadOffloaded, isTrue);
      expect(restored.id, message.id);
      expect(restored.sessionId, 'session-1');
      expect(restored.turnId, 'turn-1');
      expect(
        restored.historyPayloadAttachments.single['path'],
        '/workspace/full-history.json',
      );
      expect(
        ChatMessageModel.userMessage('complete').isHistoryPayloadOffloaded,
        isFalse,
      );
    },
  );

  test('ChatMessageModel normalizes integer-like doubles recursively', () {
    final message = ChatMessageModel.fromJson({
      'id': 'history-message',
      'type': 2.0,
      'user': 3.0,
      'content': {
        'id': 99.0,
        'dbId': 123.0,
        'cardData': {
          'type': 'deep_thinking',
          'stage': 4.0,
          'startTime': 1774600557281.0,
          'nested': {'count': 2.0},
          'items': [
            1.0,
            {'delay': 5.0},
            1.5,
          ],
        },
      },
      'createAt': 1774600557281.0,
    });

    expect(message.type, 2);
    expect(message.user, 3);
    expect(message.contentId, '99');
    expect(message.dbId, 123);
    expect(message.cardData?['stage'], 4);
    expect(message.cardData?['startTime'], 1774600557281);

    final nested = message.cardData?['nested'] as Map<String, dynamic>;
    expect(nested['count'], 2);

    final items = message.cardData?['items'] as List<dynamic>;
    expect(items[0], 1);
    expect((items[1] as Map<String, dynamic>)['delay'], 5);
    expect(items[2], 1.5);
    expect(message.createAt.millisecondsSinceEpoch, 1774600557281);
  });

  test('ChatMessageModel parses legacy createAt strings', () {
    final message = ChatMessageModel.fromJson({
      'id': 'legacy-message',
      'type': 1,
      'user': 2,
      'content': {'text': 'hello'},
      'createAt': '1774600557281',
    });

    expect(message.text, 'hello');
    expect(message.createAt.millisecondsSinceEpoch, 1774600557281);
  });

  test('ChatMessageModel preserves assistant replies that are raw JSON', () {
    final message = ChatMessageModel.fromJson({
      'id': 'assistant-json',
      'type': 1,
      'user': 2,
      'content': {'text': '{"foo":1,"bar":{"baz":true}}'},
      'createAt': '1774600557281',
    });

    expect(message.text, '{"foo":1,"bar":{"baz":true}}');
  });

  test('ChatMessageModel preserves inline JSON inside assistant replies', () {
    final message = ChatMessageModel.fromJson({
      'id': 'assistant-inline-json',
      'type': 1,
      'user': 2,
      'content': {'text': '这里是示例 payload: {"foo":1,"bar":2}'},
      'createAt': '1774600557281',
    });

    expect(message.text, '这里是示例 payload: {"foo":1,"bar":2}');
  });

  test(
    'message display data is recursively immutable and detached from inputs',
    () {
      final nested = <String, dynamic>{
        'text': 'original',
        'attachments': <dynamic>[
          {'name': 'one'},
        ],
      };
      final message = ChatMessageModel(
        id: '1',
        type: 1,
        user: 1,
        content: nested,
      );
      nested['text'] = 'changed externally';
      expect(message.text, 'original');
      expect(
        () => message.content!['text'] = 'mutated',
        throwsUnsupportedError,
      );
      expect(
        () => (message.content!['attachments'] as List).add({}),
        throwsUnsupportedError,
      );
      expect(
        () => message.content!['attachments'][0]['name'] = 'two',
        throwsUnsupportedError,
      );
      expect(message.copyWith(content: {'text': 'edit'}).text, 'edit');
    },
  );

  test('ChatMessageModel preserves turn usage payload', () {
    final message = ChatMessageModel.fromJson({
      'id': 'assistant-turn-usage',
      'type': 1,
      'user': 2,
      'content': {'text': 'done'},
      'turnUsage': {
        'ctx': 20000.0,
        'in': 10000.0,
        'out': 87.0,
        'cache': 10000.0,
      },
      'createAt': '1774600557281',
    });

    expect(message.turnUsage?['ctx'], 20000);
    expect(message.turnUsage?['in'], 10000);
    expect(message.turnUsage?['out'], 87);
    expect(message.turnUsage?['cache'], 10000);
    expect(message.toJson()['turnUsage'], <String, dynamic>{
      'ctx': 20000,
      'in': 10000,
      'out': 87,
      'cache': 10000,
    });
  });

  test(
    'nested source edits cannot change content, stream metadata or usage',
    () {
      final event = <String, dynamic>{
        'summary': 'original',
        'details': <String, dynamic>{
          'paths': <dynamic>['original.txt'],
        },
      };
      final events = <dynamic>[event];
      final metadata = <String, dynamic>{
        'tags': <dynamic>['original'],
      };
      final usage = <String, dynamic>{
        'provider': <String, dynamic>{'input': 10},
      };
      final message = ChatMessageModel(
        id: 'snapshot',
        type: 2,
        user: 3,
        content: {
          'cardData': {'subagentEvents': events},
        },
        streamMeta: metadata,
        turnUsage: usage,
      );

      event['summary'] = 'changed';
      (event['details']['paths'] as List).add('changed.txt');
      events.clear();
      (metadata['tags'] as List).clear();
      usage['provider']['input'] = 999;

      final storedEvent = (message.cardData!['subagentEvents'] as List).single;
      expect(storedEvent['summary'], 'original');
      expect(storedEvent['details']['paths'], ['original.txt']);
      expect(message.streamMeta!['tags'], ['original']);
      expect(message.turnUsage!['provider']['input'], 10);
      expect(
        () => storedEvent['details']['paths'].add('mutated.txt'),
        throwsUnsupportedError,
      );
    },
  );

  for (final useView in [false, true]) {
    test('external unmodifiable shells are detached (view=$useView)', () {
      final child = <String, dynamic>{
        'paths': <dynamic>['original.txt'],
      };
      final backingList = <dynamic>[child];
      final outer = <String, dynamic>{
        'items': useView
            ? UnmodifiableListView<dynamic>(backingList)
            : List<dynamic>.unmodifiable(backingList),
      };
      final message = ChatMessageModel(
        id: 'external-shell',
        type: 1,
        user: 2,
        content: useView
            ? UnmodifiableMapView<String, dynamic>(outer)
            : Map<String, dynamic>.unmodifiable(outer),
      );

      child['paths'].add('changed.txt');
      backingList.clear();
      outer.clear();

      final stored = message.content!['items'] as List;
      expect(stored, hasLength(1));
      expect(stored.single['paths'], ['original.txt']);
      expect(() => stored.clear(), throwsUnsupportedError);
      expect(() => stored.single['paths'].clear(), throwsUnsupportedError);
    });
  }

  test('flag-only copies share already immutable snapshot collections', () {
    final message = ChatMessageModel(
      id: 'large-snapshot',
      type: 2,
      user: 3,
      content: {
        'cardData': {
          'subagentEvents': List.generate(1000, (index) => {'seq': index}),
        },
      },
      streamMeta: {
        'sessionId': 'session-1',
        'nested': {'turnId': 'turn-1'},
      },
      turnUsage: {
        'provider': {'input': 10},
      },
    );
    final copy = message.copyWith(isLoading: true, isFirst: true);

    expect(copy.isLoading, isTrue);
    expect(copy.isFirst, isTrue);
    expect(message.isLoading, isFalse);
    expect(copy.content, same(message.content));
    expect(copy.cardData, same(message.cardData));
    expect(copy.streamMeta, same(message.streamMeta));
    expect(copy.turnUsage, same(message.turnUsage));
    expect(() => copy.content!['extra'] = true, throwsUnsupportedError);
    expect(
      () => (copy.cardData!['subagentEvents'] as List).clear(),
      throwsUnsupportedError,
    );
  });

  test(
    'card updates share old immutable nodes and detach new incoming data',
    () {
      final original = ChatMessageModel.cardMessage({
        'status': 'running',
        'subagentEvents': List.generate(
          1000,
          (index) => {
            'seq': index,
            'details': {
              'paths': ['file-$index.txt'],
            },
          },
        ),
      }, id: 'subagent');
      final oldEvents = original.cardData!['subagentEvents'] as List;
      final statusUpdate = original.copyWith(
        content: {
          ...original.content!,
          'cardData': {...original.cardData!, 'status': 'success'},
        },
      );
      expect(statusUpdate.cardData!['subagentEvents'], same(oldEvents));
      expect(original.cardData!['status'], 'running');
      expect(statusUpdate.cardData!['status'], 'success');

      final incoming = <String, dynamic>{
        'seq': 1000,
        'details': <String, dynamic>{
          'paths': <dynamic>['incoming.txt'],
        },
      };
      final next = original.copyWith(
        content: {
          ...original.content!,
          'cardData': {
            ...original.cardData!,
            'subagentEvents': [...oldEvents, incoming],
          },
        },
      );
      incoming['details']['paths'].add('changed.txt');
      incoming['seq'] = -1;

      final newEvents = next.cardData!['subagentEvents'] as List;
      expect(oldEvents, hasLength(1000));
      expect(newEvents, hasLength(1001));
      for (var index = 0; index < oldEvents.length; index++) {
        expect(newEvents[index], same(oldEvents[index]));
      }
      expect(newEvents.last['seq'], 1000);
      expect(newEvents.last['details']['paths'], ['incoming.txt']);
      expect(
        () => newEvents.first['details']['paths'].clear(),
        throwsUnsupportedError,
      );
      expect(
        () => newEvents.last['details']['paths'].clear(),
        throwsUnsupportedError,
      );
    },
  );

  test('shared snapshots preserve JSON and platform codec roundtrips', () {
    final message = ChatMessageModel(
      id: 'codec-snapshot',
      type: 2,
      user: 3,
      content: {
        'text': 'result 😀',
        'cardData': {
          'status': 'running',
          'subagentEvents': [
            {
              'seq': 1,
              'details': {
                'paths': ['one.txt'],
              },
            },
          ],
        },
      },
      streamMeta: {'sessionId': 'session-1', 'turnId': 'turn-1'},
      turnUsage: {'input': 10, 'output': 2},
      createAt: DateTime.utc(2026, 11, 17),
    ).copyWith(isFirst: true);
    final json = message.toJson();
    final fromJson = ChatMessageModel.fromJson(jsonDecode(jsonEncode(json)));
    const codec = StandardMessageCodec();
    final decoded = codec.decodeMessage(codec.encodeMessage(json)) as Map;
    final fromPlatform = ChatMessageModel.fromJson(
      Map<String, dynamic>.from(decoded),
    );

    expect(fromJson.toJson(), json);
    expect(fromPlatform.toJson(), json);
    expect(fromPlatform.sessionId, message.sessionId);
    expect(fromPlatform.turnId, message.turnId);
    expect(fromPlatform.cardData!['subagentEvents'], hasLength(1));
    decoded['content']['cardData']['subagentEvents'][0]['seq'] = 2;
    expect(message.cardData!['subagentEvents'][0]['seq'], 1);
    expect(fromPlatform.cardData!['subagentEvents'][0]['seq'], 1);
    expect(
      () => fromPlatform.cardData!['subagentEvents'][0]['seq'] = 3,
      throwsUnsupportedError,
    );
  });
}
