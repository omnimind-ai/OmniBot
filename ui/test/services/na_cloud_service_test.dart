import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/na_cloud_service.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  test('file index and binary download keep bearer auth and encoded workspace paths', () async {
    final requests = <http.Request>[];
    final service = NaCloudService(
      const NaCloudConfig(
        baseUrl: 'https://na.example/tenant',
        token: 'private-file-token',
      ),
      client: MockClient((request) async {
        requests.add(request);
        if (request.url.path.endsWith('/content'))
          return http.Response.bytes([0, 255, 1, 128], 200);
        return http.Response(
          '{"files":[{"path":"reports/a b.md","size":4}]}',
          200,
        );
      }),
    );
    addTearDown(service.close);
    expect((await service.files()).single.path, 'reports/a b.md');
    expect(await service.fileContent('reports/a b.md'), [0, 255, 1, 128]);
    expect(requests.last.url.queryParameters, {'path': 'reports/a b.md'});
    expect(requests.last.url.path, '/tenant/api/files/content');
    expect(
      requests.every(
        (request) =>
            request.headers['Authorization'] == 'Bearer private-file-token',
      ),
      true,
    );
    expect(requests.last.url.toString(), isNot(contains('private-file-token')));
  });

  test(
    'unauthorized file download exposes status without bearer credentials',
    () async {
      final service = NaCloudService(
        const NaCloudConfig(
          baseUrl: 'https://na.example',
          token: 'private-file-token',
        ),
        client: MockClient((_) async => http.Response('unauthorized', 401)),
      );
      addTearDown(service.close);
      await expectLater(
        service.fileContent('report.md'),
        throwsA(
          isA<NaCloudException>()
              .having((error) => error.statusCode, 'status', 401)
              .having(
                (error) => error.toString(),
                'message',
                isNot(contains('private-file-token')),
              ),
        ),
      );
    },
  );

  test('wrapped conversation snapshot restores the latest terminal run after switching history', () async {
    final service = NaCloudService(
      const NaCloudConfig(baseUrl: 'https://na.example'),
      client: MockClient(
        (_) async => http.Response(
          jsonEncode({
            'conversation': {
              'id': 'c1',
              'title': 'Earlier',
              'messages': [],
              'activeRun': null,
            },
            'runs': [
              {'id': 'old-run', 'status': 'completed'},
              {'id': 'latest-run', 'status': 'cancelled'},
            ],
          }),
          200,
        ),
      ),
    );
    addTearDown(service.close);
    final conversation = await service.conversation('c1');
    expect(conversation.activeRun, isNull);
    expect(conversation.latestRun!.id, 'latest-run');
    expect(conversation.latestRun!.status, 'cancelled');
  });

  test(
    'cloud auth and logical send identity go only to the Na endpoint',
    () async {
      final requests = <http.Request>[];
      final service = NaCloudService(
        const NaCloudConfig(
          baseUrl: 'https://na.example/tenant',
          token: 'test-token',
        ),
        client: MockClient((request) async {
          requests.add(request);
          return http.Response(jsonEncode({'runId': 'run-1'}), 202);
        }),
      );
      addTearDown(service.close);
      expect(
        await service.send('c/1', 'Plan my week', clientMessageId: 'same-send'),
        'run-1',
      );
      expect(
        requests.single.url.toString(),
        'https://na.example/tenant/api/conversations/c%2F1/messages',
      );
      expect(requests.single.headers['Authorization'], 'Bearer test-token');
      expect(jsonDecode(requests.single.body), {
        'text': 'Plan my week',
        'clientMessageId': 'same-send',
      });
    },
  );

  test(
    'a failed message admission is never replayed by the transport',
    () async {
      var sends = 0;
      final service = NaCloudService(
        const NaCloudConfig(baseUrl: 'https://na.example'),
        client: MockClient((_) async {
          sends++;
          throw const SocketException('Connection lost after admission');
        }),
      );
      addTearDown(service.close);
      await expectLater(
        service.send('c1', 'Remember this', clientMessageId: 'logical-send'),
        throwsA(isA<SocketException>()),
      );
      expect(sends, 1);
    },
  );

  test(
    'server snapshots restore active cancellation and stable message ids',
    () async {
      final service = NaCloudService(
        const NaCloudConfig(baseUrl: 'https://na.example'),
        client: MockClient(
          (request) async => http.Response(
            jsonEncode({
              'id': 'c1',
              'title': 'Planning',
              'messages': [
                {
                  'id': 'assistant-item-1',
                  'role': 'assistant',
                  'text': 'Draft',
                  'createdAt': 'now',
                },
              ],
              'activeRun': {'id': 'r1', 'status': 'running'},
            }),
            200,
          ),
        ),
      );
      addTearDown(service.close);
      final snapshot = await service.conversation('c1');
      expect(snapshot.messages.single.id, 'assistant-item-1');
      expect(snapshot.activeRun!.id, 'r1');
      expect(snapshot.activeRun!.active, true);
    },
  );

  test(
    '401 gives a useful connection error without exposing a token',
    () async {
      final service = NaCloudService(
        const NaCloudConfig(
          baseUrl: 'https://na.example',
          token: 'private-secret',
        ),
        client: MockClient(
          (_) async => http.Response('{"error":"unauthorized"}', 401),
        ),
      );
      addTearDown(service.close);
      await expectLater(
        service.status(),
        throwsA(
          isA<NaCloudException>()
              .having((error) => error.statusCode, 'status', 401)
              .having(
                (error) => error.message.contains('private-secret'),
                'secret leaked',
                false,
              ),
        ),
      );
    },
  );

  test(
    'SSE parser accepts heartbeat comments, split UTF-8, and multiline JSON',
    () async {
      final bytes = utf8.encode(
        ': heartbeat\r\n\r\nid: 12\r\nevent: message.created\r\ndata: {"id":12,"type":"message.created",\r\ndata: "conversationId":"c1","data":{"text":"你好"}}\r\n\r\n',
      );
      final events = await NaCloudService.decodeEvents(
        Stream.fromIterable(bytes.map((byte) => [byte])),
      ).toList();
      expect(events.single.id, 12);
      expect(events.single.conversationId, 'c1');
      expect(events.single.data['text'], '你好');
    },
  );

  test('real SSE reconnect resumes the durable cursor and ignores replay', () async {
    // This is a plain async service test: no widget-test HttpClient override.
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final afters = <String?>[];
    var writes = 0;
    final handlers = server.listen((request) async {
      if (request.method != 'GET') writes++;
      afters.add(request.uri.queryParameters['after']);
      expect(request.headers.value('authorization'), 'Bearer local-token');
      request.response.headers.contentType = ContentType(
        'text',
        'event-stream',
      );
      final first = afters.length == 1;
      for (final id in first ? [1] : [1, 2]) {
        request.response.write(
          'data: ${jsonEncode({'id': id, 'type': 'run.updated', 'conversationId': 'c1'})}\n\n',
        );
      }
      await request.response.close();
    });
    final service = NaCloudService(
      NaCloudConfig(
        baseUrl: 'http://127.0.0.1:${server.port}',
        token: 'local-token',
      ),
      reconnectDelay: const Duration(milliseconds: 5),
    );
    final received = <int>[];
    final done = Completer<void>();
    final subscription = service.events().listen((event) {
      received.add(event.id);
      if (received.length == 2 && !done.isCompleted) done.complete();
    });
    try {
      await done.future.timeout(const Duration(seconds: 3));
      expect(received, [1, 2]);
      expect(afters.take(2), ['0', '1']);
      expect(writes, 0);
    } finally {
      service.close();
      await subscription.cancel();
      await handlers.cancel();
      await server.close(force: true);
    }
  });

  test(
    'connection config validates origin and never persists bearer credentials',
    () async {
      SharedPreferences.setMockInitialValues({});
      await StorageService.init();
      const config = NaCloudConfig(
        baseUrl: 'https://na.example',
        token: 'private-secret',
      );
      await config.save();
      expect(StorageService.getAllKeys(), {'na_cloud_development_url'});
      expect(NaCloudConfig.load().token, 'private-secret');
      for (final value in [
        'file:///tmp',
        'https://name:password@example.com',
        'https://na.example?token=secret',
        'https://na.example#token',
      ]) {
        expect(
          () => NaCloudConfig(baseUrl: value).validate(),
          throwsA(isA<NaCloudException>()),
        );
      }
    },
  );
}
