import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/na_routine_editor.dart';

Map<String, dynamic> routine({dynamic schedule, int? interval}) => {
  'id': 'r1',
  'title': 'Follow mail',
  'prompt': 'Notify when meaningful changes appear',
  'enabled': true,
  'nextRunAt': '2026-11-05T01:00:00.123Z',
  'intervalMinutes': interval,
  if (schedule != null) 'schedule': schedule,
};
NaRoutineDraft fresh({Map<String, dynamic>? original}) =>
    NaRoutineDraft(original: original, timezone: 'Asia/Shanghai')
      ..acceptInitialLocalTime('2026-11-05T09:00')
      ..title = 'Follow mail'
      ..prompt = 'Notify when meaningful changes appear';

Future<Map<String, dynamic>> forbidden({
  String? localTime,
  String? at,
  required String timezone,
}) async => throw StateError(
  'Unchanged or event timing must not convert a new instant',
);

void main() {
  test(
    'wording only preserves every original schedule and precise due timestamp',
    () async {
      for (final schedule in [
        {'time': '09:00', 'timezone': 'Asia/Shanghai'},
        {'type': 'once', 'at': '2026-11-05T01:00:00.123Z'},
        {
          'type': 'interval',
          'everyMinutes': 30,
          'anchorAt': '2026-01-01T00:00:00Z',
          'endsAt': '2027-01-01T00:00:00Z',
          'timezone': 'Asia/Shanghai',
          'weekdays': [1, 3],
          'window': {'start': '09:00', 'end': '18:00'},
        },
      ]) {
        final draft = fresh(original: routine(schedule: schedule))
          ..title = 'Better title';
        expect(await draft.payload(forbidden), {
          'title': 'Better title',
          'prompt': 'Notify when meaningful changes appear',
        });
      }
      final draft = fresh(original: routine(interval: 1440));
      expect((await draft.payload(forbidden)).keys, ['title', 'prompt']);
    },
  );

  test(
    'daily weekly monthly and cron are profile-zone calendar schedules',
    () async {
      final draft = fresh()
        ..time = '09:15'
        ..weekday = 5
        ..monthDay = '31';
      for (final pair in {
        'daily': '15 9 * * *',
        'weekly': '15 9 * * 5',
        'monthly': '15 9 31 * *',
        'cron': '0 8 * * 1-5',
      }.entries) {
        draft.mode = pair.key;
        draft.cron = '0 8 * * 1-5';
        expect((await draft.payload(forbidden))['schedule'], {
          'type': 'cron',
          'expression': pair.value,
          'timezone': 'Asia/Shanghai',
        });
      }
    },
  );

  test('event switch emits no schedule or legacy time fields', () async {
    final draft = fresh(original: routine(interval: 1440))..mode = 'event';
    expect(await draft.payload(forbidden), {
      'title': 'Follow mail',
      'prompt': 'Notify when meaningful changes appear',
      'trigger': 'event',
    });
  });

  test('once conversion forwards service timezone and explicit offset bypasses ambiguity', () async {
    final draft = fresh();
    int calls = 0;
    Future<Map<String, dynamic>> convert({
      String? localTime,
      String? at,
      required String timezone,
    }) async {
      calls++;
      expect(timezone, 'Asia/Shanghai');
      expect(localTime, '2026-11-05T09:00');
      return {'at': '2026-11-05T01:00:00Z'};
    }

    expect((await draft.payload(convert))['schedule'], {
      'type': 'once',
      'at': '2026-11-05T01:00:00Z',
    });
    draft.explicitAt = '2026-02-30T09:00:00+08:00';
    await expectLater(draft.payload(forbidden), throwsFormatException);
    draft.explicitAt = '2026-11-01T01:30:00-05:00';
    expect((await draft.payload(forbidden))['schedule'], {
      'type': 'once',
      'at': '2026-11-01T06:30:00.000Z',
    });
    expect(calls, 1);
  });

  test('interval advanced controls validate window and changing back preserves plan', () async {
    final draft = fresh()
      ..mode = 'interval'
      ..interval = '20'
      ..weekdays = [3, 1]
      ..windowStart = '18:00'
      ..windowEnd = '09:00';
    await expectLater(draft.payload(forbidden), throwsFormatException);
    draft.windowEnd = '19:00';
    expect((await draft.payload(forbidden))['schedule'], {
      'type': 'interval',
      'everyMinutes': 20,
      'timezone': 'Asia/Shanghai',
      'weekdays': [1, 3],
      'window': {'start': '18:00', 'end': '19:00'},
    });
    final original = fresh(
      original: routine(
        schedule: {
          'type': 'cron',
          'expression': '0 9 * * *',
          'timezone': 'Asia/Shanghai',
        },
      ),
    );
    original.time = '10:00';
    original.time = '09:00';
    expect((await original.payload(forbidden)).keys, ['title', 'prompt']);
  });

  test(
    'checks are preferences only with default cap and no implicit permission',
    () async {
      final draft = fresh()
        ..mode = 'event'
        ..checks = ['mail', 'runs'];
      expect(await draft.payload(forbidden), {
        'title': 'Follow mail',
        'prompt': 'Notify when meaningful changes appear',
        'trigger': 'event',
        'continuity': true,
        'deliver': 'chat',
        'maxRunsPerDay': 24,
        'checks': ['mail', 'runs'],
      });
      draft.maxRunsPerDay = '0';
      await expectLater(draft.payload(forbidden), throwsFormatException);
    },
  );

  test('status separates enabled plans from approval, unavailable checks and daily cap', () {
    expect(
      naRoutineStatus({
        ...routine(),
        'waitingReview': {'runId': 'run'},
      }),
      '等待你审核应用操作',
    );
    expect(
      naRoutineStatus({...routine(), 'heldReason': 'daily_cap'}),
      '已达每日运行上限',
    );
    expect(
      naRoutineStatus({...routine(), 'lastCheckFailureCause': 'grant_missing'}),
      contains('不代表没有新内容'),
    );
    expect(naRoutineStatus({...routine(), 'trigger': 'event'}), '等待应用事件');
    expect(naRoutineStatus(routine()), '计划已启用');
  });

  Future<void> mount(
    WidgetTester tester,
    NaRoutineDraft draft,
    NaLocalTimeResolver resolve,
    ValueChanged<Map<String, dynamic>?> done,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () async {
                done(
                  await showDialog<Map<String, dynamic>>(
                    context: context,
                    builder: (_) => NaRoutineEditor(
                      draft: draft,
                      resolveLocalTime: resolve,
                    ),
                  ),
                );
              },
              child: const Text('Open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
  }

  testWidgets(
    'cancel returns no payload and never converts or mutates anything',
    (tester) async {
      Map<String, dynamic>? result;
      bool closed = false;
      await mount(tester, fresh(), forbidden, (value) {
        result = value;
        closed = true;
      });
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(closed, true);
      expect(result, null);
    },
  );

  testWidgets(
    'event selection exposes the binding instruction and saves no time fields',
    (tester) async {
      Map<String, dynamic>? result;
      await mount(tester, fresh(), forbidden, (value) => result = value);
      await tester.tap(find.text('只运行一次'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('应用事件').last);
      await tester.pumpAndSettle();
      expect(find.textContaining('应用事件中绑定此职责'), findsOneWidget);
      await tester.tap(find.text('保存职责'));
      await tester.pumpAndSettle();
      expect(result?['trigger'], 'event');
      expect(result?.containsKey('schedule'), false);
    },
  );

  testWidgets(
    'late initial conversion cannot open an editor for a replacement owner',
    (tester) async {
      final gate = Completer<Map<String, dynamic>>();
      bool current = true, returned = false;
      Map<String, dynamic>? result;
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () async {
                  result = await showNaRoutineEditor(
                    context,
                    timezone: 'Asia/Shanghai',
                    isCurrent: () => current,
                    resolveLocalTime: ({
                      String? localTime,
                      String? at,
                      required String timezone,
                    }) => gate.future,
                  );
                  returned = true;
                },
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pump();
      current = false;
      gate.complete({'localTime': '2026-11-05T09:00'});
      await tester.pumpAndSettle();
      expect(returned, true);
      expect(result, null);
      expect(find.byType(NaRoutineEditor), findsNothing);
    },
  );

  testWidgets('rapid saves convert once and retain form on DST error', (
    tester,
  ) async {
    final gate = Completer<Map<String, dynamic>>();
    int calls = 0;
    Future<Map<String, dynamic>> resolve({
      String? localTime,
      String? at,
      required String timezone,
    }) {
      calls++;
      return gate.future;
    }

    await mount(
      tester,
      fresh(),
      resolve,
      (_) => fail('Invalid wall time must stay editable'),
    );
    await tester.tap(find.text('保存职责'));
    await tester.tap(find.text('保存职责'));
    await tester.pump();
    expect(calls, 1);
    gate.completeError(
      PlatformException(code: 'invalid_time', message: '该时间因夏令时出现两次'),
    );
    await tester.pumpAndSettle();
    expect(find.text('该时间因夏令时出现两次'), findsOneWidget);
    expect(find.text('保存职责'), findsOneWidget);
  });
}
