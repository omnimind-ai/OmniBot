import 'package:flutter/services.dart';
import 'package:ui/services/assists_core_service.dart';

class ConversationActivitySummary {
  const ConversationActivitySummary({
    this.activityMap = const {},
    this.totalConversations = 0,
    this.currentStreak = 0,
  });

  final Map<String, int> activityMap;
  final int totalConversations;
  final int currentStreak;

  factory ConversationActivitySummary.fromMap(Map<dynamic, dynamic> map) =>
      ConversationActivitySummary(
        activityMap: _intMap(map['activityMap']),
        totalConversations: (map['totalConversations'] as num?)?.toInt() ?? 0,
        currentStreak: (map['currentStreak'] as num?)?.toInt() ?? 0,
      );
}

class WeeklyTokenData {
  const WeeklyTokenData({
    required this.weekStart,
    this.modelTokens = const {},
    this.cachedTokens = 0,
  });

  final DateTime weekStart;
  final Map<String, int> modelTokens;
  final int cachedTokens;
  int get totalTokens =>
      modelTokens.values.fold(0, (sum, tokens) => sum + tokens);

  factory WeeklyTokenData.fromMap(Map<dynamic, dynamic> map) => WeeklyTokenData(
    weekStart: DateTime.fromMillisecondsSinceEpoch(
      (map['weekStartMs'] as num).toInt(),
    ),
    modelTokens: _intMap(map['modelTokens']),
    cachedTokens: (map['cachedTokens'] as num?)?.toInt() ?? 0,
  );
}

class TokenUsageSummary {
  const TokenUsageSummary({
    this.weeklyData = const [],
    this.totalCached = 0,
    this.modelTotals = const {},
    this.modelOrder = const [],
  });

  final List<WeeklyTokenData> weeklyData;
  final int totalCached;
  final Map<String, int> modelTotals;
  final List<String> modelOrder;

  factory TokenUsageSummary.fromMap(Map<dynamic, dynamic> map) =>
      TokenUsageSummary(
        weeklyData: (map['weeklyData'] as List? ?? const [])
            .whereType<Map>()
            .map(WeeklyTokenData.fromMap)
            .toList(growable: false),
        totalCached: (map['totalCached'] as num?)?.toInt() ?? 0,
        modelTotals: _intMap(map['modelTotals']),
        modelOrder: (map['modelOrder'] as List? ?? const []).cast<String>(),
      );
}

/// Chart display DTOs only; the host owns history reads and aggregation.
class UsageStatisticsService {
  static const _channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');

  static Future<ConversationActivitySummary> getConversationActivity({
    required DateTime startDate,
    required DateTime today,
  }) async {
    final visibility = AssistsMessageService.conversationVisibilityRevision;
    final result = await _channel.invokeMapMethod<dynamic, dynamic>(
      'getConversationActivitySummary',
      {
        'startDateMs': startDate.millisecondsSinceEpoch,
        'todayMs': today.millisecondsSinceEpoch,
      },
    );
    if (result == null) {
      throw StateError('Conversation activity summary is unavailable');
    }
    final summary =
        visibility == AssistsMessageService.conversationVisibilityRevision
        ? result
        : result['localSummary'];
    return summary is Map
        ? ConversationActivitySummary.fromMap(summary)
        : const ConversationActivitySummary();
  }

  static Future<TokenUsageSummary> getTokenUsage({
    required DateTime alignedStart,
    required List<DateTime> weekStarts,
  }) async {
    final result = await _channel.invokeMapMethod<dynamic, dynamic>(
      'getTokenUsageSummary',
      {
        'alignedStartMs': alignedStart.millisecondsSinceEpoch,
        'weekStartsMs': weekStarts
            .map((date) => date.millisecondsSinceEpoch)
            .toList(growable: false),
      },
    );
    if (result == null) {
      throw StateError('Token usage summary is unavailable');
    }
    return TokenUsageSummary.fromMap(result);
  }
}

Map<String, int> _intMap(dynamic value) => value is Map
    ? value.map(
        (key, number) => MapEntry(key.toString(), (number as num).toInt()),
      )
    : const {};
