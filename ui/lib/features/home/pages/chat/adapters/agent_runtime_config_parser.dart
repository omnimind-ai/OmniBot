part of '../chat_page.dart';

List<String> _extractAgentOptionIds(
  Map<String, dynamic> response,
  List<String> listKeys,
) {
  final rawItems = _collectAgentListItems(response, listKeys);
  final seen = <String>{};
  final result = <String>[];
  for (final item in rawItems) {
    final id = _remoteCodexOptionId(item);
    if (id == null || !seen.add(id)) {
      continue;
    }
    result.add(id);
  }
  return result;
}

List<String> _mergeAgentOptionIds({
  String? current,
  String? preferred,
  required List<String> options,
}) {
  final seen = <String>{};
  final result = <String>[];
  final verifiedOptions = options
      .map((option) => option.trim())
      .where((option) => option.isNotEmpty)
      .toList(growable: false);
  String? verifiedMatch(String? value) {
    final text = value?.trim() ?? '';
    if (text.isEmpty) {
      return null;
    }
    return verifiedOptions.firstWhere(
      (option) => option == text || option.toLowerCase() == text.toLowerCase(),
      orElse: () => '',
    );
  }
  void add(String? value) {
    final verified = verifiedMatch(value);
    if (verified == null || verified.isEmpty || !seen.add(verified)) {
      return;
    }
    result.add(verified);
  }

  add(current);
  add(preferred);
  for (final option in verifiedOptions) {
    add(option);
  }
  return result;
}

List<dynamic> _collectAgentListItems(
  Map<String, dynamic> response,
  List<String> listKeys, {
  bool allowUnkeyedFallback = true,
}) {
  final normalizedKeys = listKeys.map(_normalizeAgentResponseKey).toSet();
  final rawItems = <dynamic>[];

  void visitMap(Map<dynamic, dynamic> map) {
    for (final entry in map.entries) {
      final key = _normalizeAgentResponseKey(entry.key.toString());
      final value = entry.value;
      if (value is List) {
        if (normalizedKeys.contains(key)) {
          rawItems.addAll(value);
        }
        for (final item in value) {
          final nested = _asAgentMap(item);
          if (nested != null) {
            visitMap(nested);
          }
        }
      } else {
        final nested = _asAgentMap(value);
        if (nested != null) {
          visitMap(nested);
        }
      }
    }
  }

  visitMap(response);
  if (allowUnkeyedFallback && rawItems.isEmpty) {
    for (final value in response.values) {
      if (value is List) {
        rawItems.addAll(value);
      }
    }
  }
  return rawItems;
}

String _normalizeAgentResponseKey(String key) {
  return key.toLowerCase().replaceAll(RegExp(r'[_-]'), '');
}

String? _extractAgentPreferredOptionId(Map<String, dynamic> response) {
  for (final key in const <String>[
    'currentModel',
    'currentModelId',
    'selectedModel',
    'selectedModelId',
    'activeModel',
    'activeModelId',
    'defaultModel',
    'defaultModelId',
    'model',
    'modelId',
  ]) {
    final id = _remoteCodexOptionId(response[key]);
    if (id != null) {
      return id;
    }
  }
  for (final key in const <String>[
    'current',
    'selected',
    'active',
    'default',
  ]) {
    final value = response[key];
    if (value is Map) {
      final id = _remoteCodexOptionId(value);
      if (id != null) {
        return id;
      }
    }
  }
  return null;
}

String? _extractAgentDefaultModelId(Map<String, dynamic> response) {
  for (final item in _collectAgentListItems(
    response,
    _kAgentModelListResponseKeys,
    allowUnkeyedFallback: false,
  )) {
    final map = _asAgentMap(item);
    if (map == null) {
      continue;
    }
    final isDefault = map['isDefault'] == true || map['default'] == true;
    if (!isDefault) {
      continue;
    }
    final id = _remoteCodexOptionId(map);
    if (id != null) {
      return id;
    }
  }
  return null;
}

String? _extractAgentModelDefaultReasoningEffort(
  Map<String, dynamic> response,
  String? modelId,
) {
  final normalizedModelId = modelId?.trim();
  for (final item in _collectAgentListItems(
    response,
    _kAgentModelListResponseKeys,
    allowUnkeyedFallback: false,
  )) {
    final map = _asAgentMap(item);
    if (map == null) {
      continue;
    }
    if (normalizedModelId != null &&
        normalizedModelId.isNotEmpty &&
        !_agentModelItemMatches(map, normalizedModelId)) {
      continue;
    }
    final effort = _normalizeAgentReasoningEffort(
      map['defaultReasoningEffort'] ??
          map['default_reasoning_effort'] ??
          map['defaultReasoningLevel'] ??
          map['default_reasoning_level'] ??
          map['reasoningEffort'] ??
          map['reasoning_effort'],
    );
    if (effort != null) {
      return effort;
    }
  }
  return null;
}

bool _agentModelItemMatches(
  Map<String, dynamic> item,
  String normalizedModelId,
) {
  for (final key in const <String>[
    'id',
    'model',
    'modelId',
    'model_id',
    'slug',
    'value',
    'name',
  ]) {
    final text = item[key]?.toString().trim();
    if (text == normalizedModelId) {
      return true;
    }
  }
  return false;
}

String? _extractAgentConfigModelId(Map<String, dynamic> response) {
  final direct = _remoteCodexOptionId(response['model'] ?? response['modelId']);
  if (direct != null) {
    return direct;
  }
  for (final key in const <String>[
    'config',
    'effectiveConfig',
    'effective',
    'settings',
    'data',
    'result',
  ]) {
    final value = response[key];
    if (value is Map) {
      final id = _remoteCodexOptionId(value['model'] ?? value['modelId']);
      if (id != null) {
        return id;
      }
      final nested = _extractAgentConfigModelId(
        value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue)),
      );
      if (nested != null) {
        return nested;
      }
    }
  }
  return null;
}

String? _extractAgentConfigReasoningEffort(Map<String, dynamic> response) {
  final direct = _normalizeAgentReasoningEffort(
    response['model_reasoning_effort'] ??
        response['reasoning_effort'] ??
        response['reasoningEffort'] ??
        response['effort'],
  );
  if (direct != null) {
    return direct;
  }
  for (final key in const <String>[
    'config',
    'effectiveConfig',
    'effective',
    'settings',
    'modelSettings',
    'model_settings',
    'data',
    'result',
  ]) {
    final value = response[key];
    if (value is Map) {
      final nested = _extractAgentConfigReasoningEffort(
        value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue)),
      );
      if (nested != null) {
        return nested;
      }
    }
  }
  return null;
}

AgentPermissionMode? _extractAgentConfigPermissionMode(
  Map<String, dynamic> response,
) {
  final raw = response['mode'] ?? response['permissionMode'];
  final normalized = _remoteCodexOptionId(raw)?.toLowerCase();
  switch (normalized) {
    case 'read-only':
    case 'readonly':
      return AgentPermissionMode.readOnly;
    case 'agent':
    case 'workspace-write':
    case 'workspacewrite':
      return AgentPermissionMode.defaultMode;
    case 'agent-full-access':
    case 'danger-full-access':
    case 'dangerfullaccess':
      return AgentPermissionMode.fullAccess;
  }
  for (final key in const <String>[
    'config',
    'effectiveConfig',
    'effective',
    'settings',
    'data',
    'result',
  ]) {
    final value = response[key];
    if (value is Map) {
      final nested = _extractAgentConfigPermissionMode(
        value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue)),
      );
      if (nested != null) return nested;
    }
  }
  return null;
}

List<String> _mergeAgentReasoningEffortOptions({
  String? current,
  required List<String> options,
}) {
  final seen = <String>{};
  final result = <String>[];
  void add(String? value) {
    final normalized = _normalizeAgentReasoningEffort(value);
    if (normalized == null || !seen.add(normalized)) {
      return;
    }
    result.add(normalized);
  }

  add(current);
  for (final option in options) {
    add(option);
  }
  return result;
}

String? _normalizeAgentReasoningEffort(dynamic value) {
  final text = value?.toString().trim().toLowerCase() ?? '';
  if (text.isEmpty) {
    return null;
  }
  return switch (text) {
    'no' || 'none' || 'off' => 'none',
    'min' || 'minimal' || 'minimum' => 'minimal',
    'med' || 'medium' => 'medium',
    'extra_high' ||
    'extra-high' ||
    'very_high' ||
    'very-high' ||
    'x-high' ||
    'x high' ||
    'xhigh' => 'xhigh',
    'low' || 'high' => text,
    _ => text,
  };
}

String? _remoteCodexOptionId(dynamic item) {
  if (item is String) {
    final text = item.trim();
    return text.isEmpty ? null : text;
  }
  if (item is Map) {
    for (final key in const <String>[
      'id',
      'modelId',
      'model_id',
      'slug',
      'value',
      'model',
      'name',
      'displayName',
      'display_name',
      'mode',
    ]) {
      final text = item[key]?.toString().trim() ?? '';
      if (text.isNotEmpty) {
        return text;
      }
    }
    return null;
  }
  if (item is Iterable) {
    return null;
  }
  final text = item?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

String _resolveAgentPlanMode(List<String> modes) {
  for (final mode in modes) {
    if (mode.toLowerCase() == 'plan') {
      return mode;
    }
  }
  for (final mode in modes) {
    if (_isAgentPlanMode(mode)) {
      return mode;
    }
  }
  return 'plan';
}

bool _isAgentPlanMode(String? mode) {
  final normalized = mode?.trim().toLowerCase() ?? '';
  return normalized == 'plan' || normalized.contains('plan');
}

class _AgentRunSettingsSnapshot {
  const _AgentRunSettingsSnapshot({
    this.modelId,
    this.reasoningEffort,
    this.permissionMode,
  });

  final String? modelId;
  final String? reasoningEffort;
  final AgentPermissionMode? permissionMode;
}

extension _AgentPermissionModePayload on AgentPermissionMode {
  String get approvalPolicy {
    return switch (this) {
      AgentPermissionMode.fullAccess => 'never',
      AgentPermissionMode.readOnly ||
      AgentPermissionMode.defaultMode ||
      AgentPermissionMode.autoReview => 'on-request',
    };
  }

  String get approvalsReviewer {
    return switch (this) {
      AgentPermissionMode.autoReview => 'auto_review',
      AgentPermissionMode.readOnly ||
      AgentPermissionMode.defaultMode ||
      AgentPermissionMode.fullAccess => 'user',
    };
  }

  Map<String, dynamic>? get sandboxPolicy {
    return switch (this) {
      AgentPermissionMode.readOnly => const <String, dynamic>{
        'type': 'readOnly',
      },
      AgentPermissionMode.fullAccess => const <String, dynamic>{
        'type': 'dangerFullAccess',
      },
      AgentPermissionMode.defaultMode || AgentPermissionMode.autoReview => null,
    };
  }
}
