import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/usage_statistics_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final today = DateTime(2026, 10, 21);
  final start = today.subtract(const Duration(days: 27));
  final initialRevision = AssistsMessageService.conversationVisibilityRevision;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    AssistsMessageService.conversationVisibilityRevision = initialRevision;
  });

  test('activity reads only the aggregate host projection', () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'getConversationActivitySummary');
      expect(call.arguments, {
        'startDateMs': start.millisecondsSinceEpoch,
        'todayMs': today.millisecondsSinceEpoch,
      });
      return {
        'activityMap': {'2026-10-21': 2},
        'totalConversations': 2,
        'currentStreak': 1,
      };
    });
    final result = await UsageStatisticsService.getConversationActivity(
      startDate: start,
      today: today,
    );
    expect(result.activityMap, {'2026-10-21': 2});
    expect(result.totalConversations, 2);
    expect(result.currentStreak, 1);
  });

  test(
    'queued activity reply selects local summary after an account change',
    () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        AssistsMessageService.conversationVisibilityRevision++;
        return {
          'activityMap': {'2026-10-21': 5},
          'totalConversations': 5,
          'currentStreak': 1,
          'localSummary': {
            'activityMap': {'2026-10-21': 2},
            'totalConversations': 2,
            'currentStreak': 1,
          },
        };
      });
      final result = await UsageStatisticsService.getConversationActivity(
        startDate: start,
        today: today,
      );
      expect(result.activityMap, {'2026-10-21': 2});
      expect(result.totalConversations, 2);
    },
  );

  test(
    'token DTO preserves supplied elapsed-time week starts and model order',
    () async {
      final next = start.add(const Duration(days: 7));
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'getTokenUsageSummary');
        expect(call.arguments, {
          'alignedStartMs': start.millisecondsSinceEpoch,
          'weekStartsMs': [
            start.millisecondsSinceEpoch,
            next.millisecondsSinceEpoch,
          ],
        });
        return {
          'weeklyData': [
            {
              'weekStartMs': start.millisecondsSinceEpoch,
              'modelTokens': {'gpt-4o': 300},
              'cachedTokens': 400,
            },
            {
              'weekStartMs': next.millisecondsSinceEpoch,
              'modelTokens': <String, int>{},
              'cachedTokens': 0,
            },
          ],
          'totalCached': 400,
          'modelTotals': {'gpt-4o': 300},
          'modelOrder': ['gpt-4o'],
        };
      });
      final result = await UsageStatisticsService.getTokenUsage(
        alignedStart: start,
        weekStarts: [start, next],
      );
      expect(result.weeklyData.map((week) => week.weekStart), [start, next]);
      expect(result.weeklyData.first.totalTokens, 300);
      expect(result.weeklyData.last.totalTokens, 0);
      expect(result.totalCached, 400);
      expect(result.modelOrder, ['gpt-4o']);
    },
  );

  test(
    'a failed token query propagates without affecting conversation reads',
    () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'getTokenUsageSummary') {
          throw PlatformException(code: 'READ_FAILED');
        }
        return {
          'activityMap': {'2026-10-21': 1},
          'totalConversations': 1,
          'currentStreak': 1,
        };
      });
      await expectLater(
        UsageStatisticsService.getTokenUsage(
          alignedStart: start,
          weekStarts: [start],
        ),
        throwsA(isA<PlatformException>()),
      );
      final activity = await UsageStatisticsService.getConversationActivity(
        startDate: start,
        today: today,
      );
      expect(activity.totalConversations, 1);
    },
  );

  test(
    'failed conversation queries propagate instead of returning zero',
    () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        throw PlatformException(code: 'READ_FAILED');
      });
      await expectLater(
        UsageStatisticsService.getConversationActivity(
          startDate: start,
          today: today,
        ),
        throwsA(isA<PlatformException>()),
      );
    },
  );

  test('missing host summaries are not treated as empty statistics', () async {
    messenger.setMockMethodCallHandler(channel, (call) async => null);
    await expectLater(
      UsageStatisticsService.getConversationActivity(
        startDate: start,
        today: today,
      ),
      throwsStateError,
    );
    await expectLater(
      UsageStatisticsService.getTokenUsage(
        alignedStart: start,
        weekStarts: [start],
      ),
      throwsStateError,
    );
  });
}
