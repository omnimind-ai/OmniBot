import 'package:flutter/services.dart';

class TokenUsageRecord {
  final int id;
  final int conversationId;
  final String model;
  final int promptTokens;
  final int completionTokens;
  final int reasoningTokens;
  final int textTokens;
  final int cachedTokens;
  final int createdAt;

  TokenUsageRecord({
    required this.id,
    required this.conversationId,
    required this.model,
    required this.promptTokens,
    required this.completionTokens,
    required this.reasoningTokens,
    required this.textTokens,
    required this.cachedTokens,
    required this.createdAt,
  });

  /// reasoning_tokens + text_tokens；若服务商未返回明细则回退到 completionTokens
  int get totalTokens {
    final detailed = reasoningTokens + textTokens;
    return detailed > 0 ? detailed : completionTokens;
  }

  /// Model id used by UI charts. Provider prefixes such as "openai/gpt-4o"
  /// are removed so the legend focuses on the actual model id.
  String get modelId => TokenUsageService.normalizeModelId(model);

  factory TokenUsageRecord.fromJson(Map<String, dynamic> json) {
    return TokenUsageRecord(
      id: (json['id'] as num?)?.toInt() ?? 0,
      conversationId: (json['conversationId'] as num?)?.toInt() ?? 0,
      model: json['model'] as String? ?? '',
      promptTokens: (json['promptTokens'] as num?)?.toInt() ?? 0,
      completionTokens: (json['completionTokens'] as num?)?.toInt() ?? 0,
      reasoningTokens: (json['reasoningTokens'] as num?)?.toInt() ?? 0,
      textTokens: (json['textTokens'] as num?)?.toInt() ?? 0,
      cachedTokens: (json['cachedTokens'] as num?)?.toInt() ?? 0,
      createdAt: (json['createdAt'] as num?)?.toInt() ?? 0,
    );
  }
}

class TokenUsageService {
  static const MethodChannel _assistCore = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  static String normalizeModelId(String rawModel) {
    var value = rawModel.trim();
    if (value.isEmpty) return 'unknown';

    value = value.replaceAll(RegExp(r'\s+'), ' ');

    final prefixSplit = value
        .split(RegExp(r'\s*(?:\||::)\s*'))
        .where((part) => part.trim().isNotEmpty)
        .toList();
    if (prefixSplit.length > 1) {
      value = prefixSplit.last.trim();
    }

    final pathSplit = value
        .split(RegExp(r'[/\\]'))
        .where((part) => part.trim().isNotEmpty)
        .toList();
    if (pathSplit.length > 1) {
      value = pathSplit.last.trim();
    } else {
      final colonIndex = value.indexOf(':');
      if (colonIndex > 0 && colonIndex < value.length - 1) {
        value = value.substring(colonIndex + 1).trim();
      }
    }

    return value.isEmpty ? 'unknown' : value;
  }

  static Future<List<TokenUsageRecord>> getRecordsSince(int sinceMs) async {
    try {
      final result = await _assistCore.invokeMethod<List<dynamic>>(
        'getTokenUsageRecords',
        {'since': sinceMs},
      );
      if (result == null) return [];
      return result
          .whereType<Map>()
          .map(
            (item) =>
                TokenUsageRecord.fromJson(Map<String, dynamic>.from(item)),
          )
          .toList();
    } on PlatformException catch (_) {
      return [];
    }
  }
}
