import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/na_cloud_service.dart';

/// Optional cross-repository acceptance against a Na server with a fixture
/// runner. Unit/widget suites remain independent of a running cloud service.
void main() {
  final url = Platform.environment['NA_CONTRACT_URL'];
  final token = Platform.environment['NA_CONTRACT_TOKEN'];
  test(
    'Flutter client speaks the live Na REST and SSE contract',
    () async {
      final service = NaCloudService(
        NaCloudConfig(baseUrl: url!, token: token!),
      );
      final events = <NaCloudEvent>[];
      final subscription = service.events().listen(events.add);
      try {
        expect(await service.status(), isA<Map<String, dynamic>>());
        final conversation = await service.createConversation();
        final runId = await service.send(
          conversation.id,
          'contract-work',
          clientMessageId: 'app-contract-one',
        );
        expect(
          await service.send(
            conversation.id,
            'contract-work',
            clientMessageId: 'app-contract-one',
          ),
          runId,
        );
        NaCloudRun run = await service.run(runId);
        for (var i = 0; i < 100 && run.active; i++) {
          await Future<void>.delayed(const Duration(milliseconds: 20));
          run = await service.run(runId);
        }
        expect(run.status, 'completed');
        final snapshot = await service.conversation(conversation.id);
        expect(
          snapshot.messages.where((message) => message.role == 'user').length,
          1,
        );
        expect(
          snapshot.messages
              .where((message) => message.role == 'assistant')
              .single
              .text,
          'Contract complete',
        );
        expect(snapshot.activeRun, isNull);
        expect(
          (await service.conversations()).any(
            (item) => item.id == conversation.id,
          ),
          true,
        );
        for (
          var i = 0;
          i < 100 && !events.any((event) => event.type == 'run.completed');
          i++
        ) {
          await Future<void>.delayed(const Duration(milliseconds: 20));
        }
        expect(
          events.any(
            (event) => event.type == 'run.completed' && event.runId == runId,
          ),
          true,
        );
        final stopId = await service.send(
          conversation.id,
          'contract-cancel',
          clientMessageId: 'app-contract-stop',
        );
        final working = await service.conversation(conversation.id);
        expect(working.activeRun!.id, stopId);
        await service.cancel(stopId);
        expect((await service.run(stopId)).status, 'cancelled');
      } finally {
        service.close();
        await subscription.cancel();
      }
    },
    skip: url == null || token == null
        ? 'Set NA_CONTRACT_URL and NA_CONTRACT_TOKEN for a fixture Na server'
        : false,
  );
}
