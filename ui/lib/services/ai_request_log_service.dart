import 'package:flutter/services.dart';

class AiRequestLogEntry {
  final String id;
  final DateTime createdAt;
  final String label;
  final String model;
  final String protocolType;
  final String url;
  final String method;
  final bool stream;
  final int? statusCode;
  final bool success;
  final String requestJson;
  final String responseJson;
  final String errorMessage;

  const AiRequestLogEntry({
    required this.id,
    required this.createdAt,
    required this.label,
    required this.model,
    required this.protocolType,
    required this.url,
    required this.method,
    required this.stream,
    required this.statusCode,
    required this.success,
    required this.requestJson,
    required this.responseJson,
    required this.errorMessage,
  });

  factory AiRequestLogEntry.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    final createdAtValue = raw['createdAt'];
    final createdAtMillis = createdAtValue is int
        ? createdAtValue
        : int.tryParse(createdAtValue?.toString() ?? '') ?? 0;
    final statusCodeValue = raw['statusCode'];
    final statusCode = statusCodeValue is int
        ? statusCodeValue
        : int.tryParse(statusCodeValue?.toString() ?? '');
    return AiRequestLogEntry(
      id: (raw['id'] ?? '').toString(),
      createdAt: DateTime.fromMillisecondsSinceEpoch(createdAtMillis),
      label: (raw['label'] ?? '').toString(),
      model: (raw['model'] ?? '').toString(),
      protocolType: (raw['protocolType'] ?? '').toString(),
      url: (raw['url'] ?? '').toString(),
      method: (raw['method'] ?? 'POST').toString(),
      stream: raw['stream'] == true,
      statusCode: statusCode,
      success: raw['success'] != false,
      requestJson: (raw['requestJson'] ?? '').toString(),
      responseJson: (raw['responseJson'] ?? '').toString(),
      errorMessage: (raw['errorMessage'] ?? '').toString(),
    );
  }
}

class AiRequestLogService {
  static const MethodChannel _assistCore = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  static Future<List<AiRequestLogEntry>> listRecent({int limit = 10}) async {
    final result = await _assistCore.invokeMethod<List<dynamic>>(
      'listRecentAiRequestLogs',
      {'limit': limit},
    );
    return (result ?? const [])
        .whereType<Map>()
        .map((item) => AiRequestLogEntry.fromMap(item))
        .toList();
  }

  static Future<void> clear() async {
    final cleared = await _assistCore.invokeMethod<bool>('clearAiRequestLogs');
    if (cleared != true) {
      throw PlatformException(
        code: 'CLEAR_AI_REQUEST_LOGS_ERROR',
        message: 'Unable to clear AI request logs.',
      );
    }
  }
}
