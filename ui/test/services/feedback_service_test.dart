import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/feedback_service.dart';

class RecordingAdapter implements HttpClientAdapter {
  int status = 201;
  String response = '{"ok":true,"id":"feedback-123"}';
  final requests = <RequestOptions>[];
  final bodies = <String>[];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? stream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    bodies.add(
      stream == null
          ? ''
          : utf8.decode(
              await stream.fold<List<int>>(
                [],
                (bytes, chunk) => bytes..addAll(chunk),
              ),
            ),
    );
    return ResponseBody.fromString(
      response,
      status,
      headers: {
        Headers.contentTypeHeader: ['application/json'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

FeedbackAttachment attachment(String name, {int size = 3}) =>
    FeedbackAttachment(
      name: name,
      size: size,
      identifier: name,
      readBytes: () => Stream.value([65, 66, 67]),
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/account');
  late FeedbackService service;
  late RecordingAdapter adapter;

  setUp(() {
    adapter = RecordingAdapter();
    service = FeedbackService(client: Dio()..httpClientAdapter = adapter);
  });
  tearDown(() {
    service.close();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  Future<String> submit({
    String title = ' Problem ',
    String description = ' Details ',
    String contact = ' wechat:sample ',
    String email = ' user@example.com ',
    List<FeedbackAttachment> files = const [],
  }) => service.submit(
    title: title,
    description: description,
    contact: contact,
    accountEmail: email,
    languageCode: 'en',
    attachments: files,
    versionInfo: {
      'versionName': '0.6.3.3',
      'androidId': 'DO-NOT-SEND',
      'accountId': 'DO-NOT-SEND',
    },
  );

  test(
    'posts native multipart feedback with email separate from edited contact',
    () async {
      expect(await submit(files: [attachment('log.txt')]), 'feedback-123');
      final request = adapter.requests.single;
      expect(request.method, 'POST');
      expect(request.uri.toString(), FeedbackService.apiUrl);
      expect(
        request.headers[Headers.contentTypeHeader],
        startsWith('multipart/form-data; boundary='),
      );
      expect(
        request.headers.keys.any((key) => key.toLowerCase() == 'authorization'),
        isFalse,
      );
      final form = request.data as FormData;
      expect(Map.fromEntries(form.fields), {
        'title': 'Problem',
        'description': 'Details',
        'contact': 'wechat:sample',
        'accountEmail': 'user@example.com',
        'source': 'android',
        'locale': 'en',
        'versionName': '0.6.3.3',
        'deviceInfo': '{"platform":"android"}',
      });
      expect(
        adapter.bodies.single,
        contains('name="attachments"; filename="log.txt"'),
      );
      expect(adapter.bodies.single, contains('ABC'));
      expect(adapter.bodies.single, isNot(contains('DO-NOT-SEND')));
    },
  );

  test('signed-out users can submit without contact or email', () async {
    await submit(contact: '', email: '');
    expect(
      Map.fromEntries((adapter.requests.single.data as FormData).fields),
      isNot(contains('accountEmail')),
    );
  });

  test(
    'rejects missing or excessive fields before issuing a request',
    () async {
      for (final action in [
        () => submit(title: '  '),
        () => submit(description: '  '),
        () => submit(title: 'x' * 201),
        () => submit(description: 'x' * 10001),
        () => submit(contact: 'x' * 301),
      ]) {
        await expectLater(
          action(),
          throwsA(
            isA<FeedbackException>().having(
              (error) => error.reason,
              'reason',
              FeedbackFailure.invalidFields,
            ),
          ),
        );
      }
      expect(adapter.requests, isEmpty);
    },
  );

  test(
    'rejects attachment count and byte limits before reading files',
    () async {
      for (final entry in <(List<FeedbackAttachment>, FeedbackFailure)>[
        (List.generate(6, (i) => attachment('$i')), FeedbackFailure.fileCount),
        (
          [attachment('big', size: FeedbackService.maxFileSize + 1)],
          FeedbackFailure.fileSize,
        ),
        (
          [
            attachment('1', size: FeedbackService.maxFileSize),
            attachment('2', size: FeedbackService.maxFileSize),
            attachment('3', size: 6 * 1024 * 1024),
          ],
          FeedbackFailure.totalSize,
        ),
      ]) {
        await expectLater(
          submit(files: entry.$1),
          throwsA(
            isA<FeedbackException>().having(
              (error) => error.reason,
              'reason',
              entry.$2,
            ),
          ),
        );
      }
      expect(adapter.requests, isEmpty);
    },
  );

  for (final entry in <(int, FeedbackFailure)>[
    (400, FeedbackFailure.invalidFields),
    (413, FeedbackFailure.totalSize),
    (429, FeedbackFailure.rateLimited),
    (500, FeedbackFailure.network),
  ]) {
    test(
      'surfaces backend status ${entry.$1} as a recoverable form error',
      () async {
        adapter.status = entry.$1;
        await expectLater(
          submit(),
          throwsA(
            isA<FeedbackException>().having(
              (error) => error.reason,
              'reason',
              entry.$2,
            ),
          ),
        );
      },
    );
  }

  test('does not show success for a malformed backend response', () async {
    adapter.response = '{"ok":true}';
    await expectLater(
      submit(),
      throwsA(
        isA<FeedbackException>().having(
          (error) => error.reason,
          'reason',
          FeedbackFailure.network,
        ),
      ),
    );
  });

  test('reads only email from the account channel', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'getFeedbackEmail');
          return ' user@example.com ';
        });
    expect(await FeedbackService.loadAccountEmail(), 'user@example.com');
  });

  test('account lookup failure keeps anonymous feedback available', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          throw PlatformException(code: 'account_not_authenticated');
        });
    expect(await FeedbackService.loadAccountEmail(), '');
    await submit(email: '', contact: '');
    expect(adapter.requests, hasLength(1));
  });
}
