import 'package:flutter/services.dart';

class StorageUsageBreakdownEntry {
  const StorageUsageBreakdownEntry({required this.label, required this.bytes});

  final String label;
  final int bytes;

  factory StorageUsageBreakdownEntry.fromMap(Map<dynamic, dynamic> map) {
    return StorageUsageBreakdownEntry(
      label: (map['label'] ?? '').toString(),
      bytes: StorageUsageCategory._asInt(map['bytes']),
    );
  }
}

class StorageUsageCategory {
  const StorageUsageCategory({
    required this.id,
    required this.name,
    required this.description,
    required this.bytes,
    required this.cleanable,
    required this.riskLevel,
    this.cleanupHint,
    this.breakdown = const [],
    this.order = 0,
  });

  final String id;
  final String name;
  final String description;
  final int bytes;
  final bool cleanable;
  final String riskLevel;
  final String? cleanupHint;
  final List<StorageUsageBreakdownEntry> breakdown;
  final int order;

  factory StorageUsageCategory.fromMap(Map<dynamic, dynamic> map) {
    return StorageUsageCategory(
      id: (map['id'] ?? '').toString(),
      name: (map['name'] ?? '').toString(),
      description: (map['description'] ?? '').toString(),
      bytes: _asInt(map['bytes']),
      cleanable: map['cleanable'] == true,
      riskLevel: (map['riskLevel'] ?? 'info').toString(),
      cleanupHint: map['cleanupHint']?.toString(),
      breakdown: (map['breakdown'] as List<dynamic>? ?? const [])
          .whereType<Map<dynamic, dynamic>>()
          .map(StorageUsageBreakdownEntry.fromMap)
          .toList(),
      order: _asInt(map['order']),
    );
  }

  static int _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '') ?? 0;
  }
}

class StorageUsageTrend {
  const StorageUsageTrend({
    required this.hasPrevious,
    required this.deltaTotalBytes,
    required this.deltaCleanableBytes,
    required this.previousGeneratedAt,
    required this.previousTotalBytes,
    required this.previousCleanableBytes,
  });

  final bool hasPrevious;
  final int deltaTotalBytes;
  final int deltaCleanableBytes;
  final int previousGeneratedAt;
  final int previousTotalBytes;
  final int previousCleanableBytes;

  factory StorageUsageTrend.fromMap(Map<dynamic, dynamic> map) {
    return StorageUsageTrend(
      hasPrevious: map['hasPrevious'] == true,
      deltaTotalBytes: StorageUsageCategory._asInt(map['deltaTotalBytes']),
      deltaCleanableBytes: StorageUsageCategory._asInt(
        map['deltaCleanableBytes'],
      ),
      previousGeneratedAt: StorageUsageCategory._asInt(
        map['previousGeneratedAt'],
      ),
      previousTotalBytes: StorageUsageCategory._asInt(
        map['previousTotalBytes'],
      ),
      previousCleanableBytes: StorageUsageCategory._asInt(
        map['previousCleanableBytes'],
      ),
    );
  }
}

class StorageUsageHistoryPoint {
  const StorageUsageHistoryPoint({
    required this.generatedAt,
    required this.totalBytes,
    required this.cleanableBytes,
  });

  final int generatedAt;
  final int totalBytes;
  final int cleanableBytes;

  factory StorageUsageHistoryPoint.fromMap(Map<dynamic, dynamic> map) {
    return StorageUsageHistoryPoint(
      generatedAt: StorageUsageCategory._asInt(map['generatedAt']),
      totalBytes: StorageUsageCategory._asInt(map['totalBytes']),
      cleanableBytes: StorageUsageCategory._asInt(map['cleanableBytes']),
    );
  }
}

class StorageCleanupStrategyPreset {
  const StorageCleanupStrategyPreset({
    required this.id,
    required this.name,
    required this.description,
    required this.riskLevel,
    required this.olderThanDays,
    required this.targetReleaseBytes,
  });

  final String id;
  final String name;
  final String description;
  final String riskLevel;
  final int olderThanDays;
  final int targetReleaseBytes;

  factory StorageCleanupStrategyPreset.fromMap(Map<dynamic, dynamic> map) {
    return StorageCleanupStrategyPreset(
      id: (map['id'] ?? '').toString(),
      name: (map['name'] ?? '').toString(),
      description: (map['description'] ?? '').toString(),
      riskLevel: (map['riskLevel'] ?? 'info').toString(),
      olderThanDays: StorageUsageCategory._asInt(map['olderThanDays']),
      targetReleaseBytes: StorageUsageCategory._asInt(
        map['targetReleaseBytes'],
      ),
    );
  }
}

class StorageUsageSummary {
  const StorageUsageSummary({
    required this.generatedAt,
    required this.totalBytes,
    required this.appBinaryBytes,
    required this.userDataBytes,
    required this.cacheBytes,
    required this.cleanableBytes,
    required this.categories,
    required this.trend,
    required this.history,
    required this.strategyPresets,
    required this.packageName,
    required this.metricsSource,
    required this.scanTotalBytes,
    required this.systemTotalBytes,
  });

  final int generatedAt;
  final int totalBytes;
  final int appBinaryBytes;
  final int userDataBytes;
  final int cacheBytes;
  final int cleanableBytes;
  final List<StorageUsageCategory> categories;
  final StorageUsageTrend trend;
  final List<StorageUsageHistoryPoint> history;
  final List<StorageCleanupStrategyPreset> strategyPresets;
  final String packageName;
  final String metricsSource;
  final int scanTotalBytes;
  final int systemTotalBytes;

  factory StorageUsageSummary.fromMap(Map<dynamic, dynamic> map) {
    final rawCategories =
        (map['categories'] as List<dynamic>? ?? const [])
            .whereType<Map<dynamic, dynamic>>()
            .map(StorageUsageCategory.fromMap)
            .toList()
          ..sort((a, b) => b.bytes.compareTo(a.bytes));
    final rawHistory = (map['history'] as List<dynamic>? ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(StorageUsageHistoryPoint.fromMap)
        .toList();
    final rawPresets = (map['strategyPresets'] as List<dynamic>? ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(StorageCleanupStrategyPreset.fromMap)
        .toList();
    final trendMap = map['trend'];

    return StorageUsageSummary(
      generatedAt: StorageUsageCategory._asInt(map['generatedAt']),
      totalBytes: StorageUsageCategory._asInt(map['totalBytes']),
      appBinaryBytes: StorageUsageCategory._asInt(map['appBinaryBytes']),
      userDataBytes: StorageUsageCategory._asInt(map['userDataBytes']),
      cacheBytes: StorageUsageCategory._asInt(map['cacheBytes']),
      cleanableBytes: StorageUsageCategory._asInt(map['cleanableBytes']),
      categories: rawCategories,
      trend: trendMap is Map
          ? StorageUsageTrend.fromMap(Map<dynamic, dynamic>.from(trendMap))
          : const StorageUsageTrend(
              hasPrevious: false,
              deltaTotalBytes: 0,
              deltaCleanableBytes: 0,
              previousGeneratedAt: 0,
              previousTotalBytes: 0,
              previousCleanableBytes: 0,
            ),
      history: rawHistory,
      strategyPresets: rawPresets,
      packageName: (map['packageName'] ?? '').toString(),
      metricsSource: (map['metricsSource'] ?? 'filesystem_estimate').toString(),
      scanTotalBytes: StorageUsageCategory._asInt(map['scanTotalBytes']),
      systemTotalBytes: StorageUsageCategory._asInt(map['systemTotalBytes']),
    );
  }
}

class StorageUsageCleanupResult {
  const StorageUsageCleanupResult({
    required this.categoryId,
    required this.success,
    required this.beforeBytes,
    required this.afterBytes,
    required this.releasedBytes,
    required this.failedPaths,
    required this.retryable,
    this.manualActionHint,
    this.summary,
  });

  final String categoryId;
  final bool success;
  final int beforeBytes;
  final int afterBytes;
  final int releasedBytes;
  final List<String> failedPaths;
  final bool retryable;
  final String? manualActionHint;
  final StorageUsageSummary? summary;

  factory StorageUsageCleanupResult.fromMap(Map<dynamic, dynamic> map) {
    final summaryMap = map['summary'];
    return StorageUsageCleanupResult(
      categoryId: (map['categoryId'] ?? '').toString(),
      success: map['success'] == true,
      beforeBytes: StorageUsageCategory._asInt(map['beforeBytes']),
      afterBytes: StorageUsageCategory._asInt(map['afterBytes']),
      releasedBytes: StorageUsageCategory._asInt(map['releasedBytes']),
      failedPaths: (map['failedPaths'] as List<dynamic>? ?? const [])
          .map((item) => item.toString())
          .toList(),
      retryable: map['retryable'] == true,
      manualActionHint: map['manualActionHint']?.toString(),
      summary: summaryMap is Map
          ? StorageUsageSummary.fromMap(Map<dynamic, dynamic>.from(summaryMap))
          : null,
    );
  }
}

class StorageStrategyActionResult {
  const StorageStrategyActionResult({
    required this.categoryId,
    required this.success,
    required this.releasedBytes,
    required this.failedPaths,
    this.manualActionHint,
  });

  final String categoryId;
  final bool success;
  final int releasedBytes;
  final List<String> failedPaths;
  final String? manualActionHint;

  factory StorageStrategyActionResult.fromMap(Map<dynamic, dynamic> map) {
    return StorageStrategyActionResult(
      categoryId: (map['categoryId'] ?? '').toString(),
      success: map['success'] == true,
      releasedBytes: StorageUsageCategory._asInt(map['releasedBytes']),
      failedPaths: (map['failedPaths'] as List<dynamic>? ?? const [])
          .map((item) => item.toString())
          .toList(),
      manualActionHint: map['manualActionHint']?.toString(),
    );
  }
}

class StorageUsageStrategyResult {
  const StorageUsageStrategyResult({
    required this.strategyId,
    required this.strategyName,
    required this.success,
    required this.releasedBytes,
    required this.actionResults,
    this.summary,
  });

  final String strategyId;
  final String strategyName;
  final bool success;
  final int releasedBytes;
  final List<StorageStrategyActionResult> actionResults;
  final StorageUsageSummary? summary;

  factory StorageUsageStrategyResult.fromMap(Map<dynamic, dynamic> map) {
    final summaryMap = map['summary'];
    return StorageUsageStrategyResult(
      strategyId: (map['strategyId'] ?? '').toString(),
      strategyName: (map['strategyName'] ?? '').toString(),
      success: map['success'] == true,
      releasedBytes: StorageUsageCategory._asInt(map['releasedBytes']),
      actionResults: (map['actionResults'] as List<dynamic>? ?? const [])
          .whereType<Map<dynamic, dynamic>>()
          .map(StorageStrategyActionResult.fromMap)
          .toList(),
      summary: summaryMap is Map
          ? StorageUsageSummary.fromMap(Map<dynamic, dynamic>.from(summaryMap))
          : null,
    );
  }
}

class LocalDataDeletionResult {
  const LocalDataDeletionResult({
    required this.status,
    required this.reason,
    required this.requiresRestart,
  });

  final String status;
  final String reason;
  final bool requiresRestart;

  bool get started => status == 'STARTED';

  factory LocalDataDeletionResult.fromMap(Map<dynamic, dynamic> map) {
    const statuses = {'STARTED', 'ABORTED'};
    const reasons = {
      '',
      'ACTIVE_TASKS',
      'SYSTEM_REJECTED',
      'SYSTEM_UNAVAILABLE',
    };
    final status = map['status']?.toString() ?? '';
    final reason = map['reason']?.toString() ?? '';
    if (!statuses.contains(status) || !reasons.contains(reason)) {
      throw const FormatException('Invalid local data deletion result');
    }
    return LocalDataDeletionResult(
      status: status,
      reason: reason,
      requiresRestart: map['requiresRestart'] == true,
    );
  }
}

class StorageUsageService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/StorageUsage',
  );

  static Future<StorageUsageSummary> getStorageUsageSummary() async {
    final result = await _channel.invokeMethod<dynamic>(
      'getStorageUsageSummary',
    );
    if (result is! Map) {
      throw PlatformException(
        code: 'INVALID_STORAGE_USAGE_RESULT',
        message: 'Storage summary result is invalid',
      );
    }
    return StorageUsageSummary.fromMap(Map<dynamic, dynamic>.from(result));
  }

  static Future<StorageUsageCleanupResult> clearCategory(
    String categoryId, {
    int? olderThanDays,
  }) async {
    final result = await _channel
        .invokeMethod<dynamic>('clearStorageUsageCategory', {
          'categoryId': categoryId,
          if (olderThanDays != null && olderThanDays > 0)
            'olderThanDays': olderThanDays,
        });
    if (result is! Map) {
      throw PlatformException(
        code: 'INVALID_STORAGE_CLEAN_RESULT',
        message: 'Storage cleanup result is invalid',
      );
    }
    return StorageUsageCleanupResult.fromMap(
      Map<dynamic, dynamic>.from(result),
    );
  }

  static Future<StorageUsageStrategyResult> applyCleanupStrategy(
    String strategyId, {
    int? olderThanDays,
    int? targetReleaseBytes,
  }) async {
    final result = await _channel
        .invokeMethod<dynamic>('applyStorageCleanupStrategy', {
          'strategyId': strategyId,
          if (olderThanDays != null && olderThanDays > 0)
            'olderThanDays': olderThanDays,
          if (targetReleaseBytes != null && targetReleaseBytes > 0)
            'targetReleaseBytes': targetReleaseBytes,
        });
    if (result is! Map) {
      throw PlatformException(
        code: 'INVALID_STORAGE_STRATEGY_RESULT',
        message: 'Storage strategy result is invalid',
      );
    }
    return StorageUsageStrategyResult.fromMap(
      Map<dynamic, dynamic>.from(result),
    );
  }

  static Future<LocalDataDeletionResult> deleteAllLocalData(
    String confirmation,
  ) async {
    final result = await _channel.invokeMethod<dynamic>('deleteAllLocalData', {
      'confirmation': confirmation,
    });
    if (result is! Map) {
      throw PlatformException(
        code: 'INVALID_LOCAL_DATA_DELETION_RESULT',
        message: 'Local data deletion result is invalid',
      );
    }
    return LocalDataDeletionResult.fromMap(Map<dynamic, dynamic>.from(result));
  }
}
