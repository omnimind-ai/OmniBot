import 'package:flutter/services.dart';

class WorkspaceMemoryEmbeddingConfig {
  final bool enabled;
  final bool configured;
  final String sceneId;
  final String? providerProfileId;
  final String? providerProfileName;
  final String? modelId;
  final String? apiBase;
  final bool hasApiKey;
  final bool usesPlatform;

  const WorkspaceMemoryEmbeddingConfig({
    required this.enabled,
    required this.configured,
    required this.sceneId,
    this.providerProfileId,
    this.providerProfileName,
    this.modelId,
    this.apiBase,
    this.hasApiKey = false,
    this.usesPlatform = false,
  });

  factory WorkspaceMemoryEmbeddingConfig.fromMap(Map<dynamic, dynamic> raw) {
    return WorkspaceMemoryEmbeddingConfig(
      enabled: raw['enabled'] != false,
      configured: raw['configured'] == true,
      sceneId: (raw['sceneId'] ?? '').toString(),
      providerProfileId: raw['providerProfileId']?.toString(),
      providerProfileName: raw['providerProfileName']?.toString(),
      modelId: raw['modelId']?.toString(),
      apiBase: raw['apiBase']?.toString(),
      hasApiKey: raw['hasApiKey'] == true,
      usesPlatform: raw['usesPlatform'] == true,
    );
  }
}

class WorkspaceMemoryRollupStatus {
  final bool enabled;
  final int? lastRunAtMillis;
  final String? lastRunSummary;
  final int? nextRunAtMillis;

  const WorkspaceMemoryRollupStatus({
    required this.enabled,
    this.lastRunAtMillis,
    this.lastRunSummary,
    this.nextRunAtMillis,
  });

  factory WorkspaceMemoryRollupStatus.fromMap(Map<dynamic, dynamic> raw) {
    int? parseInt(dynamic value) {
      if (value is int) return value;
      if (value is String) return int.tryParse(value);
      return null;
    }

    return WorkspaceMemoryRollupStatus(
      enabled: raw['enabled'] != false,
      lastRunAtMillis: parseInt(raw['lastRunAtMillis']),
      lastRunSummary: raw['lastRunSummary']?.toString(),
      nextRunAtMillis: parseInt(raw['nextRunAtMillis']),
    );
  }
}

class WorkspaceShortMemoryItem {
  final String id;
  final String date;
  final String time;
  final String content;
  final int timestampMillis;

  const WorkspaceShortMemoryItem({
    required this.id,
    required this.date,
    required this.time,
    required this.content,
    required this.timestampMillis,
  });

  factory WorkspaceShortMemoryItem.fromMap(Map<dynamic, dynamic> raw) {
    int parseInt(dynamic value) {
      if (value is int) return value;
      if (value is String) return int.tryParse(value) ?? 0;
      return 0;
    }

    return WorkspaceShortMemoryItem(
      id: (raw['id'] ?? '').toString(),
      date: (raw['date'] ?? '').toString(),
      time: (raw['time'] ?? '').toString(),
      content: (raw['content'] ?? '').toString(),
      timestampMillis: parseInt(raw['timestampMillis']),
    );
  }
}

class WorkspaceMemoryService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  static Future<String> getAgentSoulSetting() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'getAgentSoulSetting',
    );
    return (result?['content'] ?? '').toString();
  }

  static Future<String> getChatPromptSetting() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'getChatPromptSetting',
    );
    return (result?['content'] ?? '').toString();
  }

  static Future<String> saveAgentSoulSetting(String content) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'saveAgentSoulSetting',
      {'content': content},
    );
    return (result?['content'] ?? '').toString();
  }

  static Future<String> saveChatPromptSetting(String content) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'saveChatPromptSetting',
      {'content': content},
    );
    return (result?['content'] ?? '').toString();
  }

  static Future<String> getLongMemory() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'getWorkspaceLongMemory',
    );
    return (result?['content'] ?? '').toString();
  }

  static Future<String> saveLongMemory(String content) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'saveWorkspaceLongMemory',
      {'content': content},
    );
    return (result?['content'] ?? '').toString();
  }

  static Future<List<WorkspaceShortMemoryItem>> getShortMemories({
    int? days,
    int? limit,
  }) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'getWorkspaceShortMemories',
      {if (days != null) 'days': days, if (limit != null) 'limit': limit},
    );
    final rawItems = (result?['items'] as List?) ?? const [];
    return rawItems
        .whereType<Map>()
        .map((item) => WorkspaceShortMemoryItem.fromMap(item))
        .toList();
  }

  static Future<WorkspaceMemoryEmbeddingConfig> getEmbeddingConfig() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'getWorkspaceMemoryEmbeddingConfig',
    );
    return WorkspaceMemoryEmbeddingConfig.fromMap(result ?? const {});
  }

  static Future<WorkspaceMemoryEmbeddingConfig> saveEmbeddingConfig({
    required bool enabled,
    String? providerProfileId,
    String? modelId,
  }) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'saveWorkspaceMemoryEmbeddingConfig',
      {
        'enabled': enabled,
        if (providerProfileId != null) 'providerProfileId': providerProfileId,
        if (modelId != null) 'modelId': modelId,
      },
    );
    return WorkspaceMemoryEmbeddingConfig.fromMap(result ?? const {});
  }

  static Future<WorkspaceMemoryRollupStatus> getRollupStatus() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'getWorkspaceMemoryRollupStatus',
    );
    return WorkspaceMemoryRollupStatus.fromMap(result ?? const {});
  }

  static Future<WorkspaceMemoryRollupStatus> saveRollupEnabled(
    bool enabled,
  ) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'saveWorkspaceMemoryRollupEnabled',
      {'enabled': enabled},
    );
    return WorkspaceMemoryRollupStatus.fromMap(result ?? const {});
  }

  static Future<Map<String, dynamic>?> runRollupNow() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'runWorkspaceMemoryRollupNow',
    );
    if (result == null) return null;
    return result.map((k, v) => MapEntry(k.toString(), v));
  }
}
