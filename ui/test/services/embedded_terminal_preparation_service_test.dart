import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:ui/services/embedded_terminal_preparation_service.dart';
import 'package:ui/services/special_permission.dart';

EmbeddedTerminalInitSnapshot snapshot(Map<String, Object?> values) =>
    EmbeddedTerminalInitSnapshot.fromMap(values);

EmbeddedTerminalInitProgress progress() => EmbeddedTerminalInitProgress.fromMap(
  {'kind': 'status', 'message': 'ready'},
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    'default retry asks native owner to repeat the failed request',
    () async {
      const channel = MethodChannel(
        'cn.com.omnimind.bot/SpecialPermissionEvent',
      );
      final calls = <String>[];
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        return {'success': true};
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      final service = EmbeddedTerminalPreparationService(
        loadSnapshot: () async =>
            snapshot({'completed': true, 'success': true}),
        progressStream: const Stream.empty(),
      );
      addTearDown(service.dispose);

      await service.retry();

      expect(calls, ['retryEmbeddedTerminalInit']);
      expect(service.ready, isTrue);
    },
  );

  test(
    'observation mirrors native idle/running/completed without installing',
    () async {
      var native = snapshot({});
      var preparations = 0;
      final events = StreamController<EmbeddedTerminalInitProgress>.broadcast();
      final service = EmbeddedTerminalPreparationService(
        loadSnapshot: () async => native,
        progressStream: events.stream,
        prepare: () async {
          preparations++;
          return {};
        },
      );
      addTearDown(service.dispose);
      addTearDown(events.close);
      service.startObserving();
      service.startObserving();
      await service.refresh();
      expect(service.ready, isFalse);
      expect(service.failed, isFalse);
      expect(service.snapshot?.running, isFalse);
      native = snapshot({'running': true});
      events.add(progress());
      await Future<void>.delayed(Duration.zero);
      expect(service.snapshot?.running, isTrue);
      native = snapshot({'completed': true, 'success': true});
      events.add(progress());
      await Future<void>.delayed(Duration.zero);
      expect(service.ready, isTrue);
      expect(preparations, 0);
    },
  );

  test(
    'retry joins one native call and reads its final snapshot without events',
    () async {
      var native = snapshot({'completed': true, 'success': false});
      final gate = Completer<void>();
      var preparations = 0;
      final service = EmbeddedTerminalPreparationService(
        loadSnapshot: () async => native,
        progressStream: const Stream.empty(),
        prepare: () async {
          preparations++;
          native = snapshot({'running': true});
          await gate.future;
          native = snapshot({'completed': true, 'success': true});
          return {'success': true};
        },
      );
      addTearDown(service.dispose);
      service.startObserving();
      await service.refresh();
      expect(service.failed, isTrue);
      final first = service.retry();
      final second = service.retry();
      expect(first, same(second));
      expect(service.retrying, isTrue);
      await service.refresh();
      expect(service.snapshot?.running, isTrue);
      gate.complete();
      await first;
      expect(service.ready, isTrue);
      expect(service.failed, isFalse);
      expect(service.retrying, isFalse);
      expect(preparations, 1);
    },
  );

  test('channel failure retains authoritative native readiness', () async {
    var cannotRead = false;
    final service = EmbeddedTerminalPreparationService(
      loadSnapshot: () async {
        if (cannotRead) throw StateError('channel disconnected');
        return snapshot({'completed': true, 'success': true});
      },
      progressStream: const Stream.empty(),
    );
    addTearDown(service.dispose);
    await service.refresh();
    cannotRead = true;
    await service.refresh();
    expect(service.ready, isTrue);
    expect(service.failed, isFalse);
    expect(service.observationError, isA<StateError>());
  });

  test(
    'a native completion event during a read schedules a fresh read',
    () async {
      final firstRead = Completer<EmbeddedTerminalInitSnapshot>();
      var reads = 0;
      final service = EmbeddedTerminalPreparationService(
        loadSnapshot: () async {
          reads++;
          if (reads == 1) return firstRead.future;
          return snapshot({'completed': true, 'success': true});
        },
        progressStream: const Stream.empty(),
      );
      addTearDown(service.dispose);
      final first = service.refresh();
      final fromEvent = service.refresh();
      firstRead.complete(snapshot({'running': true}));
      await Future.wait([first, fromEvent]);
      expect(reads, 2);
      expect(service.ready, isTrue);
    },
  );

  testWidgets('last UI observer releases pending installation polling', (
    tester,
  ) async {
    var reads = 0;
    final service = EmbeddedTerminalPreparationService(
      loadSnapshot: () async {
        reads++;
        return snapshot({'running': true});
      },
      progressStream: const Stream.empty(),
    );
    addTearDown(service.dispose);
    void listener() {}
    service.addListener(listener);
    service.startObserving();
    await service.refresh();
    final observedReads = reads;
    service.removeListener(listener);
    await tester.pump(const Duration(seconds: 3));
    expect(reads, observedReads);
  });

  test(
    'retry bridge failure stays observable when the native snapshot is idle',
    () async {
      final service = EmbeddedTerminalPreparationService(
        loadSnapshot: () async => snapshot({}),
        progressStream: const Stream.empty(),
        prepare: () async => throw StateError('prepare unavailable'),
      );
      addTearDown(service.dispose);
      await service.retry();
      expect(service.ready, isFalse);
      expect(service.failed, isFalse);
      expect(service.observationError, isA<StateError>());
    },
  );
}
