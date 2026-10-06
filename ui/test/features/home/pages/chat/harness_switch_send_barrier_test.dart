import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';

void main() {
  test('queued sends keep waiting when another switch starts before resumption', () async {
    for (var round = 0; round < 20; round++) {
      final barrier = HarnessSwitchSendBarrier();
      final first = barrier.begin();
      final released = <bool>[];
      final sends = List.generate(2, (_) => barrier.waitUntilIdle().then(released.add));
      barrier.finish(first);
      final second = barrier.begin();
      await Future<void>.delayed(Duration.zero);
      expect(released, isEmpty, reason: 'round $round released during a new switch');
      barrier.finish(second);
      await Future.wait(sends);
      expect(released, [true, true]);
    }
  });

  test('a failed switch cannot revive queued sends when a new switch succeeds', () async {
    final barrier = HarnessSwitchSendBarrier();
    final first = barrier.begin();
    final waiting = barrier.waitUntilIdle();
    barrier.finish(first, succeeded: false);
    final second = barrier.begin();
    barrier.finish(second);
    expect(await waiting, isFalse);
    expect(await barrier.waitUntilIdle(), isTrue);
  });

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

  group('ChatSubmitGate (5d-0c)', () {
    test('a retry queued behind a Harness switch runs after it settles', () async {
      final barrier = HarnessSwitchSendBarrier();
      final gate = ChatSubmitGate(barrier);
      final generation = barrier.begin();
      var sent = false;
      final retry = gate.run(
        currentTarget: () => 1,
        requireSameTarget: true,
        submit: () async => sent = true,
      );
      await Future<void>.delayed(Duration.zero);
      expect(sent, isFalse);
      barrier.finish(generation);
      expect(await retry, isTrue);
      expect(sent, isTrue);
    });

    test('a retry is dropped when the switch moved the page to another target', () async {
      final barrier = HarnessSwitchSendBarrier();
      final gate = ChatSubmitGate(barrier);
      var target = 1;
      final generation = barrier.begin();
      var sent = false;
      final retry = gate.run(
        currentTarget: () => target,
        requireSameTarget: true,
        submit: () async => sent = true,
      );
      target = 2;
      barrier.finish(generation);
      expect(await retry, isFalse);
      expect(sent, isFalse);
    });

    test('a failed switch refuses a queued retry', () async {
      final barrier = HarnessSwitchSendBarrier();
      final gate = ChatSubmitGate(barrier);
      final generation = barrier.begin();
      final retry = gate.run(currentTarget: () => 1, submit: () async {});
      barrier.finish(generation, succeeded: false);
      expect(await retry, isFalse);
    });

    test('a retry and a composer send on one target submit once', () async {
      final gate = ChatSubmitGate(HarnessSwitchSendBarrier());
      final release = Completer<void>();
      var sends = 0;
      Future<void> submit() async {
        sends += 1;
        await release.future;
      }

      final first = gate.run(currentTarget: () => 1, submit: submit);
      final second = gate.run(
        currentTarget: () => 1,
        requireSameTarget: true,
        submit: submit,
      );
      expect(await second, isFalse);
      expect(gate.isInFlight(1), isTrue);
      release.complete();
      expect(await first, isTrue);
      expect(sends, 1);
      expect(gate.isInFlight(1), isFalse);
    });

    test('another target is not blocked by an in-flight submit', () async {
      final gate = ChatSubmitGate(HarnessSwitchSendBarrier());
      final release = Completer<void>();
      var target = 1;
      final first = gate.run(
        currentTarget: () => target,
        submit: () => release.future,
      );
      await Future<void>.delayed(Duration.zero);
      target = 2;
      expect(await gate.run(currentTarget: () => target, submit: () async {}), isTrue);
      release.complete();
      await first;
    });

    test('the bootstrap is awaited before submit, and a move during it drops a retry', () async {
      final gate = ChatSubmitGate(HarnessSwitchSendBarrier());
      final bootstrap = Completer<void>();
      var target = 1;
      var sent = false;
      final retry = gate.run(
        currentTarget: () => target,
        requireSameTarget: true,
        bootstrap: () => bootstrap.future,
        submit: () async => sent = true,
      );
      await Future<void>.delayed(Duration.zero);
      target = 2;
      bootstrap.complete();
      expect(await retry, isFalse);
      expect(sent, isFalse);
    });
  });
}
