import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/chat_message_model.dart';

void main() {
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
}
