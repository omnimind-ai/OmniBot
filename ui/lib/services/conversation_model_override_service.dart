import 'dart:convert';

import 'package:ui/services/storage_service.dart';

class ConversationModelOverride {
  final int conversationId;
  final String providerProfileId;
  final String modelId;

  const ConversationModelOverride({
    required this.conversationId,
    required this.providerProfileId,
    required this.modelId,
  });

  factory ConversationModelOverride.fromMap(Map<String, dynamic> map) {
    final rawConversationId = map['conversationId'];
    final conversationId = rawConversationId is int
        ? rawConversationId
        : int.tryParse(rawConversationId?.toString() ?? '') ?? 0;
    return ConversationModelOverride(
      conversationId: conversationId,
      providerProfileId: (map['providerProfileId'] ?? '').toString(),
      modelId: (map['modelId'] ?? '').toString(),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'conversationId': conversationId,
      'providerProfileId': providerProfileId,
      'modelId': modelId,
    };
  }
}

class ConversationModelOverrideService {
  static const String _kConversationModelOverridesKey =
      'conversation_model_overrides_v1';

  static Future<ConversationModelOverride?> getOverride(
    int conversationId,
  ) async {
    final map = _readOverrideMap();
    final raw = map[conversationId.toString()];
    if (raw is! Map<String, dynamic>) {
      return null;
    }
    final override = ConversationModelOverride.fromMap(raw);
    if (override.providerProfileId.isEmpty || override.modelId.isEmpty) {
      return null;
    }
    return override;
  }

  static Future<void> saveOverride(ConversationModelOverride value) async {
    final map = _readOverrideMap();
    map[value.conversationId.toString()] = value.toMap();
    await StorageService.setString(
      _kConversationModelOverridesKey,
      jsonEncode(map),
    );
  }

  static Future<void> clearOverride(int conversationId) async {
    final map = _readOverrideMap();
    map.remove(conversationId.toString());
    await StorageService.setString(
      _kConversationModelOverridesKey,
      jsonEncode(map),
    );
  }

  static Map<String, dynamic> _readOverrideMap() {
    final raw = StorageService.getString(
      _kConversationModelOverridesKey,
      defaultValue: '',
    );
    if (raw == null || raw.trim().isEmpty) {
      return <String, dynamic>{};
    }
    try {
      final decoded = jsonDecode(raw);
      if (decoded is Map<String, dynamic>) {
        return Map<String, dynamic>.from(decoded);
      }
    } catch (_) {
      // ignore broken cache
    }
    return <String, dynamic>{};
  }
}

class ConversationReasoningEffortService {
  static const String _kConversationReasoningEffortsKey =
      'conversation_reasoning_efforts_v1';
  static const Set<String> _kSupportedEfforts = {
    'none',
    'medium',
    'low',
    'high',
    'xhigh',
    'max',
  };

  static Future<String?> getEffort(int conversationId) async {
    final map = _readEffortMap();
    final raw = map[conversationId.toString()];
    return _normalizeEffort(raw?.toString());
  }

  static Future<void> saveEffort(int conversationId, String effort) async {
    final normalized = _normalizeEffort(effort);
    if (normalized == null) {
      await clearEffort(conversationId);
      return;
    }
    final map = _readEffortMap();
    map[conversationId.toString()] = normalized;
    await StorageService.setString(
      _kConversationReasoningEffortsKey,
      jsonEncode(map),
    );
  }

  static Future<void> clearEffort(int conversationId) async {
    final map = _readEffortMap();
    map.remove(conversationId.toString());
    await StorageService.setString(
      _kConversationReasoningEffortsKey,
      jsonEncode(map),
    );
  }

  static String? normalizeEffort(String? raw) => _normalizeEffort(raw);

  static Map<String, dynamic> _readEffortMap() {
    final raw = StorageService.getString(
      _kConversationReasoningEffortsKey,
      defaultValue: '',
    );
    if (raw == null || raw.trim().isEmpty) {
      return <String, dynamic>{};
    }
    try {
      final decoded = jsonDecode(raw);
      if (decoded is Map<String, dynamic>) {
        return Map<String, dynamic>.from(decoded);
      }
    } catch (_) {
      // ignore broken cache
    }
    return <String, dynamic>{};
  }

  static String? _normalizeEffort(String? raw) {
    final normalized = raw?.trim().toLowerCase();
    if (normalized == null || normalized.isEmpty) {
      return null;
    }
    // `no` was the historical storage value. Read it as the canonical
    // `none`; all new writes use the same value as the native Agent path.
    final canonical = switch (normalized) {
      'no' || 'off' || 'disabled' => 'none',
      'med' => 'medium',
      _ => normalized,
    };
    return _kSupportedEfforts.contains(canonical) ? canonical : null;
  }
}
