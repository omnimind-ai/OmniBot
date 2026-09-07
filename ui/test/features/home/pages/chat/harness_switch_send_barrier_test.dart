import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';

void main() {
  test('idle sends are admitted', () async {
    expect(await HarnessSwitchSendBarrier().waitUntilIdle(), isTrue);
  });

  test(
    'submit waits until successful selection and target application',
    () async {
      final barrier = HarnessSwitchSendBarrier();
      final generation = barrier.begin();
      var delivered = false;
      final send = barrier.waitUntilIdle().then(
        (success) => delivered = success,
      );
      await Future<void>.delayed(Duration.zero);
      expect(delivered, isFalse);
      barrier.finish(generation, succeeded: true);
      await send;
      expect(delivered, isTrue);
    },
  );

  test(
    'failed switch refuses every queued submit, next explicit send works',
    () async {
      final barrier = HarnessSwitchSendBarrier();
      final generation = barrier.begin();
      final first = barrier.waitUntilIdle();
      final second = barrier.waitUntilIdle();
      barrier.finish(generation, succeeded: false);
      expect(await first, isFalse);
      expect(await second, isFalse);
      expect(await barrier.waitUntilIdle(), isTrue);
    },
  );

  test('stale completion cannot release a submit for a newer switch', () async {
    final barrier = HarnessSwitchSendBarrier();
    final first = barrier.begin();
    final second = barrier.begin();
    var released = false;
    final send = barrier.waitUntilIdle().then((_) => released = true);
    barrier.finish(first, succeeded: true);
    await Future<void>.delayed(Duration.zero);
    expect(released, isFalse);
    barrier.finish(second, succeeded: false);
    await send;
    expect(released, isTrue);
  });

  test(
    'native selections remain serialized and skip stale queued work',
    () async {
      final barrier = HarnessSwitchSendBarrier();
      final release = Completer<void>();
      final calls = <int>[];
      final first = barrier.begin();
      final running = barrier.runIfCurrent(first, () async {
        calls.add(first);
        await release.future;
      });
      await Future<void>.delayed(Duration.zero);
      final second = barrier.begin();
      final stale = barrier.runIfCurrent(second, () async => calls.add(second));
      final third = barrier.begin();
      final latest = barrier.runIfCurrent(third, () async => calls.add(third));
      release.complete();
      await running;
      await stale;
      await latest;
      expect(calls, [first, third]);
      barrier.finish(third);
    },
  );
}
