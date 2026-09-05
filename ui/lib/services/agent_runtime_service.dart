import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:ui/features/home/pages/command_overlay/utils/error_message_formatter.dart';
import 'package:ui/services/acp_capabilities.dart';

enum CodexLoginType {
  chatgpt('chatgpt'),
  chatgptDeviceCode('chatgptDeviceCode'),
  apiKey('apiKey');

  const CodexLoginType(this.payloadValue);

  final String payloadValue;
}

class AgentRuntimeStatus {
  const AgentRuntimeStatus({
    required this.connected,
    required this.ready,
    this.version,
    this.error,
    this.agentHome,
    this.cwd,
    this.runtime,
    this.remoteEnabled = false,
    this.remoteBridgeUrl,
    this.remoteCwd,
    this.remoteConfigured = false,
    this.remoteTransport,
    this.remoteDesktopAvailable,
    this.remoteActiveConnections,
    this.remoteUptimeMs,
    this.protocol,
    this.protocolVersion,
    this.activeAgentId,
    this.activeAgentName,
    this.capabilities = const <String, dynamic>{},
  });

  final bool connected;
  final bool ready;
  final String? version;
  final String? error;
  final String? agentHome;
  final String? cwd;
  final String? runtime;
  final bool remoteEnabled;
  final String? remoteBridgeUrl;
  final String? remoteCwd;
  final bool remoteConfigured;
  final String? remoteTransport;
  final bool? remoteDesktopAvailable;
  final int? remoteActiveConnections;
  final int? remoteUptimeMs;
  final String? protocol;
  final int? protocolVersion;
  final String? activeAgentId;
  final String? activeAgentName;
  final Map<String, dynamic> capabilities;

  /// Typed, forward-compatible view of the ACP capabilities advertised by
  /// the active Harness. The original map remains available for extensions.
  AcpCapabilities get acpCapabilities => AcpCapabilities.fromMap(capabilities);

  bool get canConnect => ready;

  factory AgentRuntimeStatus.fromMap(Map<dynamic, dynamic>? map) {
    final source = map ?? const <dynamic, dynamic>{};
    return AgentRuntimeStatus(
      connected: source['connected'] == true,
      ready: source['ready'] == true,
      version: _stringOrNull(source['version']),
      error: _stringOrNull(source['error']),
      agentHome: _stringOrNull(source['agentHome'] ?? source['codexHome']),
      cwd: _stringOrNull(source['cwd']),
      runtime: _stringOrNull(source['runtime']),
      remoteEnabled: source['remoteEnabled'] == true,
      remoteBridgeUrl: _stringOrNull(source['remoteBridgeUrl']),
      remoteCwd: _stringOrNull(source['remoteCwd']),
      remoteConfigured: source['remoteConfigured'] == true,
      remoteTransport: _stringOrNull(source['remoteTransport']),
      remoteDesktopAvailable: _boolOrNull(source['remoteDesktopAvailable']),
      remoteActiveConnections: _intOrNull(source['remoteActiveConnections']),
      remoteUptimeMs: _intOrNull(source['remoteUptimeMs']),
      protocol: _stringOrNull(source['protocol']),
      protocolVersion: _intOrNull(source['protocolVersion']),
      activeAgentId: _stringOrNull(source['activeAgentId']),
      activeAgentName: _stringOrNull(source['activeAgentName']),
      capabilities:
          _normalizeMap(source['capabilities']) ?? const <String, dynamic>{},
    );
  }

  static const disconnected = AgentRuntimeStatus(
    connected: false,
    ready: false,
  );
}

String agentModelSourceKey(AgentRuntimeStatus status) {
  if (status.runtime == 'remote' || status.remoteEnabled) {
    return 'remote';
  }
  return 'local-${status.activeAgentId ?? 'agent'}';
}

bool isAgentCancellationSuccessful(Map<String, dynamic> response) {
  return response['ok'] == true ||
      response['cancelled'] == true ||
      response['status'] == 'cancelled';
}

/// Converts an ACP boundary error into short, actionable UI text. Native
/// errors carry a stable `failureKind` in PlatformException.details; use that
/// instead of exposing adapter stack traces or waiting for a raw error string
/// to render in the chat.
String formatAgentRuntimeErrorForUser(Object? error) {
  String? failureKind;
  String? rawMessage;
  if (error is PlatformException) {
    rawMessage = error.message;
    final details = error.details;
    if (details is Map) {
      failureKind = details['failureKind']?.toString().trim();
    }
  } else if (error != null) {
    rawMessage = error.toString();
  }

  switch (failureKind) {
    case 'provider_not_bound':
      return '尚未绑定统一 Agent Provider / 模型，请在 Agent 设置中选择后重试。';
    case 'provider_unavailable':
      return '统一 Agent Provider 不可用或凭据不完整，请检查 Provider 配置。';
    case 'provider_model_unavailable':
      return '服务商拒绝了当前模型。本轮已停止，请重新选择可用模型后重试。';
    case 'provider_tls_certificate_failure':
      return 'Provider HTTPS 证书校验失败，请检查设备时间和证书链。';
    case 'provider_stream_interrupted':
      return '模型响应期间连接中断。本轮已停止，请检查网络后重试；未完成的工具调用不会执行。';
    case 'provider_stream_idle_timeout':
      return 'Provider 长时间没有返回新的流式更新，请检查接口地址、模型和网络后重试。';
    case 'provider_tool_call_incomplete':
      return 'Provider 返回了不完整的工具调用，缺少工具名称。已自动重试；请重试本轮，或检查 Provider 是否完整转发 tool_calls/function.name。';
    case 'harness_preparation_in_progress':
      return '另一个 Harness 正在安装或准备中，当前切换不会等待。请稍后重试，或先切换到已安装完成的 Harness。';
  }

  final normalizedRaw = rawMessage?.toLowerCase() ?? '';
  if (normalizedRaw.contains('unknown variant namespace') &&
      normalizedRaw.contains('tools')) {
    return '当前 Responses Provider 不支持 Codex 的 MCP 工具格式。请重试；'
        '如仍失败，请改用支持 namespace tools 的 Provider。';
  }
  if (normalizedRaw.contains('missing function.name') ||
      normalizedRaw.contains('missing function name')) {
    return 'Provider 返回了不完整的工具调用，缺少工具名称。请重试本轮，或检查 Provider 是否完整转发 tool_calls/function.name。';
  }
  if (normalizedRaw.contains('harness preparation is already running') ||
      normalizedRaw.contains('harness preparation in progress')) {
    return '另一个 Harness 正在安装或准备中，当前切换不会等待。请稍后重试，或先切换到已安装完成的 Harness。';
  }

  return formatErrorMessageForUser(rawMessage, fallback: 'Agent 执行失败，请重试。');
}

/// Extracts only model choices from an ACP model/config response.
///
/// ACP `configOptions` can also contain mode, permission, boolean, and
/// thought-level choices. Those values must never be treated as models.
List<String> extractAcpModelIds(Map<String, dynamic> response) {
  final rawItems = <dynamic>[];
  _collectAcpExplicitLists(response, const <String>{
    'models',
    'modeloptions',
    'availablemodels',
    'modelids',
  }, rawItems);
  _collectAcpCategorizedConfigChoices(
    response,
    categories: const <String>{'model'},
    fallbackIds: const <String>{'model'},
    output: rawItems,
  );
  if (rawItems.isEmpty) {
    _collectAcpRootModelListFallback(response, rawItems);
  }
  return _uniqueAcpChoiceIds(rawItems);
}

/// Returns the Agent-owned ACP config id for thought/reasoning choices.
///
/// Codex currently uses `reasoning_effort`, while other ACP Agents may use a
/// different id. Keep the id next to the values so a live
/// `session/set_config_option` call does not guess a vendor field.
String? extractAcpReasoningEffortConfigId(Map<String, dynamic> response) {
  String? visit(Map<dynamic, dynamic> source) {
    for (final entry in source.entries) {
      final value = entry.value;
      if (value is List) {
        if (_normalizeAcpConfigKey(entry.key.toString()) == 'configoptions') {
          for (final item in value) {
            final option = _normalizeMap(item);
            if (option == null) continue;
            final id = option['id']?.toString().trim() ?? '';
            final category = _normalizeAcpConfigKey(
              option['category']?.toString() ?? '',
            );
            if (id.isNotEmpty &&
                option['options'] is List &&
                (category == 'thoughtlevel' ||
                    id.toLowerCase() == 'reasoning_effort' ||
                    id.toLowerCase() == 'reasoning-effort' ||
                    id.toLowerCase() == 'effort')) {
              return id;
            }
          }
        }
        for (final item in value) {
          final nested = _normalizeMap(item);
          if (nested != null) {
            final found = visit(nested);
            if (found != null) return found;
          }
        }
      } else {
        final nested = _normalizeMap(value);
        if (nested != null) {
          final found = visit(nested);
          if (found != null) return found;
        }
      }
    }
    return null;
  }

  return visit(response);
}

/// Extracts only ACP thought-level/reasoning choices.
List<String> extractAcpReasoningEffortIds(Map<String, dynamic> response) {
  final rawItems = <dynamic>[];
  _collectAcpExplicitLists(response, const <String>{
    'reasoningefforts',
    'efforts',
    'modelreasoningefforts',
    'supportedreasoningefforts',
  }, rawItems);
  _collectAcpCategorizedConfigChoices(
    response,
    categories: const <String>{'thoughtlevel'},
    fallbackIds: const <String>{'thoughtlevel', 'reasoningeffort'},
    output: rawItems,
  );
  return _uniqueAcpChoiceIds(rawItems);
}

void _collectAcpExplicitLists(
  Map<dynamic, dynamic> source,
  Set<String> listKeys,
  List<dynamic> output,
) {
  for (final entry in source.entries) {
    final key = _normalizeAcpConfigKey(entry.key.toString());
    final value = entry.value;
    if (value is List) {
      if (listKeys.contains(key)) {
        output.addAll(value);
      }
      for (final item in value) {
        final nested = _normalizeMap(item);
        if (nested != null) {
          _collectAcpExplicitLists(nested, listKeys, output);
        }
      }
      continue;
    }
    final nested = _normalizeMap(value);
    if (nested != null) {
      _collectAcpExplicitLists(nested, listKeys, output);
    }
  }
}

void _collectAcpCategorizedConfigChoices(
  Map<dynamic, dynamic> source, {
  required Set<String> categories,
  required Set<String> fallbackIds,
  required List<dynamic> output,
}) {
  for (final entry in source.entries) {
    final key = _normalizeAcpConfigKey(entry.key.toString());
    final value = entry.value;
    if (value is List) {
      if (key == 'configoptions') {
        for (final item in value) {
          final option = _normalizeMap(item);
          if (option == null) {
            continue;
          }
          final category = _normalizeAcpConfigKey(
            option['category']?.toString() ?? '',
          );
          final id = _normalizeAcpConfigKey(option['id']?.toString() ?? '');
          final isMatchingOption =
              categories.contains(category) || fallbackIds.contains(id);
          final choices = option['options'];
          if (isMatchingOption && choices is List) {
            output.addAll(choices);
          }
        }
      }
      for (final item in value) {
        final nested = _normalizeMap(item);
        if (nested != null) {
          _collectAcpCategorizedConfigChoices(
            nested,
            categories: categories,
            fallbackIds: fallbackIds,
            output: output,
          );
        }
      }
      continue;
    }
    final nested = _normalizeMap(value);
    if (nested != null) {
      _collectAcpCategorizedConfigChoices(
        nested,
        categories: categories,
        fallbackIds: fallbackIds,
        output: output,
      );
    }
  }
}

void _collectAcpRootModelListFallback(
  Map<dynamic, dynamic> source,
  List<dynamic> output, {
  int depth = 0,
}) {
  if (depth > 3) {
    return;
  }
  for (final entry in source.entries) {
    final key = _normalizeAcpConfigKey(entry.key.toString());
    final value = entry.value;
    if (value is List &&
        const <String>{'items', 'data', 'options'}.contains(key) &&
        !_looksLikeAcpConfigOptionList(value)) {
      output.addAll(value);
      continue;
    }
    if (value is Map &&
        const <String>{'data', 'result', 'response', 'payload'}.contains(key)) {
      _collectAcpRootModelListFallback(value, output, depth: depth + 1);
    }
  }
}

bool _looksLikeAcpConfigOptionList(List<dynamic> values) {
  return values.any((item) {
    final map = _normalizeMap(item);
    if (map == null || map['options'] is! List) {
      return false;
    }
    return map.containsKey('category') ||
        map.containsKey('type') ||
        map.containsKey('option_type') ||
        map.containsKey('currentValue') ||
        map.containsKey('current_value');
  });
}

List<String> _uniqueAcpChoiceIds(List<dynamic> rawItems) {
  final seen = <String>{};
  final result = <String>[];
  for (final item in rawItems) {
    final id = _acpChoiceId(item);
    if (id == null || !seen.add(id)) {
      continue;
    }
    result.add(id);
  }
  return result;
}

String? _acpChoiceId(dynamic item) {
  if (item is String) {
    final value = item.trim();
    return value.isEmpty ? null : value;
  }
  final map = _normalizeMap(item);
  if (map == null) {
    return null;
  }
  for (final key in const <String>[
    'id',
    'modelId',
    'model_id',
    'slug',
    'value',
    'model',
    'name',
  ]) {
    final value = map[key]?.toString().trim() ?? '';
    if (value.isNotEmpty) {
      return value;
    }
  }
  return null;
}

String _normalizeAcpConfigKey(String key) {
  return key.toLowerCase().replaceAll(RegExp(r'[_-]'), '');
}

class AcpAgentProfile {
  const AcpAgentProfile({
    required this.id,
    required this.name,
    required this.command,
    this.description = '',
    this.arguments = const <String>[],
    this.environment = const <String, String>{},
    this.enabled = true,
    this.builtIn = false,
    this.source = 'custom',
    this.selected = false,
    this.installed,
    this.status = 'unchecked',
    this.lastCheckError,
    this.lastCheckLatencyMs,
    this.lastCheckAt,
    this.capabilities = const <String, dynamic>{},
    this.discoveryCommand,
    this.managedAdapter = false,
  });

  final String id;
  final String name;
  final String command;
  final String description;
  final List<String> arguments;
  final Map<String, String> environment;
  final bool enabled;
  final bool builtIn;
  final String source;
  final bool selected;
  final bool? installed;
  final String status;
  final String? lastCheckError;
  final int? lastCheckLatencyMs;
  final int? lastCheckAt;
  final Map<String, dynamic> capabilities;
  final String? discoveryCommand;
  final bool managedAdapter;

  factory AcpAgentProfile.fromMap(Map<dynamic, dynamic> map) {
    final rawArguments = map['arguments'];
    final rawEnvironment = map['environment'];
    return AcpAgentProfile(
      id: _stringOrNull(map['id']) ?? '',
      name: _stringOrNull(map['name']) ?? '',
      command: _stringOrNull(map['command']) ?? '',
      description: _stringOrNull(map['description']) ?? '',
      arguments: rawArguments is List
          ? rawArguments
                .map((value) => value.toString().trim())
                .where((value) => value.isNotEmpty)
                .toList(growable: false)
          : const <String>[],
      environment: rawEnvironment is Map
          ? rawEnvironment.map(
              (key, value) => MapEntry(key.toString(), value.toString()),
            )
          : const <String, String>{},
      enabled: map['enabled'] != false,
      builtIn: map['builtIn'] == true,
      source: _stringOrNull(map['source']) ?? 'custom',
      selected: map['selected'] == true,
      installed: _boolOrNull(map['installed']),
      status: _stringOrNull(map['status']) ?? 'unchecked',
      lastCheckError: _stringOrNull(map['lastCheckError']),
      lastCheckLatencyMs: _intOrNull(map['lastCheckLatencyMs']),
      lastCheckAt: _intOrNull(map['lastCheckAt']),
      capabilities:
          _normalizeMap(map['capabilities']) ?? const <String, dynamic>{},
      discoveryCommand: _stringOrNull(map['discoveryCommand']),
      managedAdapter: map['managedAdapter'] == true,
    );
  }

  Map<String, dynamic> toMap() => {
    'id': id,
    'name': name,
    'description': description,
    'command': command,
    'arguments': arguments,
    'environment': environment,
    'enabled': enabled,
  };
}

class AcpAgentCatalog {
  const AcpAgentCatalog({
    required this.selectedAgentId,
    required this.agents,
    this.runtimeStatus,
  });

  final String selectedAgentId;
  final List<AcpAgentProfile> agents;

  /// A switch response may carry the status snapshot captured immediately
  /// after ACP initialization. Keeping it with the catalog avoids a second
  /// status/connect IPC round-trip on the hot path.
  final AgentRuntimeStatus? runtimeStatus;

  AcpAgentProfile? get selectedAgent {
    for (final agent in agents) {
      if (agent.id == selectedAgentId) return agent;
    }
    return agents.isEmpty ? null : agents.first;
  }

  factory AcpAgentCatalog.fromMap(Map<dynamic, dynamic>? map) {
    final source = map ?? const <dynamic, dynamic>{};
    final rawAgents = source['agents'];
    final agents = <AcpAgentProfile>[];
    final seenIdentities = <String>{};
    if (rawAgents is List) {
      for (final rawAgent in rawAgents.whereType<Map>()) {
        final agent = AcpAgentProfile.fromMap(rawAgent);
        if (agent.id.isEmpty) continue;
        if (seenIdentities.add(_agentCatalogIdentity(agent))) {
          agents.add(agent);
        }
      }
    }
    return AcpAgentCatalog(
      selectedAgentId:
          _stringOrNull(source['selectedAgentId']) ??
          (agents.isEmpty ? '' : agents.first.id),
      agents: agents,
      runtimeStatus:
          source.containsKey('connected') || source.containsKey('ready')
          ? AgentRuntimeStatus.fromMap(source)
          : null,
    );
  }
}

String _agentCatalogIdentity(AcpAgentProfile agent) {
  if (agent.id == 'xiaowan-acp' ||
      agent.id.toLowerCase() == 'legacy-xiaowan-bot' ||
      agent.id.toLowerCase() == 'legacy-xiaowan-command') {
    return 'xiaowan-acp';
  }
  return 'id:${agent.id}';
}

String? selectAgentRequestModel({
  required AgentRuntimeStatus status,
  required String? overrideModel,
  required String? activeModel,
  required bool activeModelSourceMatches,
}) {
  return _stringOrNull(
    overrideModel ?? (activeModelSourceMatches ? activeModel : null),
  );
}

/// Resolves the shared Agent Provider/model without throwing away a persisted
/// binding when the scene catalog is temporarily empty or stale.
///
/// The catalog's effective fields are the preferred projection. The bound
/// fields are the durable source of truth and are intentionally a fallback so
/// switching Harnesses does not require the model catalog request to win a
/// race with the ACP runtime startup.
Map<String, String>? resolveSharedAgentProviderSelection({
  required String? effectiveProviderProfileId,
  required String? effectiveModel,
  required String? boundProviderProfileId,
  required String? boundModel,
}) {
  String? normalized(String? value) {
    final result = value?.trim() ?? '';
    return result.isEmpty ? null : result;
  }

  final effectiveProvider = normalized(effectiveProviderProfileId);
  final effectiveModelId = normalized(effectiveModel);
  if (effectiveProvider != null && effectiveModelId != null) {
    return <String, String>{
      'providerProfileId': effectiveProvider,
      'modelId': effectiveModelId,
    };
  }

  final boundProvider = normalized(boundProviderProfileId);
  final boundModelId = normalized(boundModel);
  if (boundProvider != null && boundModelId != null) {
    return <String, String>{
      'providerProfileId': boundProvider,
      'modelId': boundModelId,
    };
  }
  return null;
}

/// A Provider/model pair is launchable only while its Provider still exists
/// in the latest configured profile snapshot. A non-empty cached selection is
/// not sufficient because settings changes can delete or clear that profile.
bool isSharedAgentProviderSelectionReady({
  required Map<String, String>? selection,
  required Set<String> configuredProviderIds,
}) {
  final providerId = selection?['providerProfileId']?.trim() ?? '';
  final modelId = selection?['modelId']?.trim() ?? '';
  return providerId.isNotEmpty &&
      modelId.isNotEmpty &&
      configuredProviderIds.contains(providerId);
}

bool isCurrentAgentModelLoad({
  required int requestId,
  required int activeRequestId,
  required String requestSource,
  required String currentSource,
}) {
  return requestId == activeRequestId && requestSource == currentSource;
}

class CodexRemoteBridgeConfig {
  const CodexRemoteBridgeConfig({
    this.remoteEnabled = false,
    this.remoteBridgeUrl = '',
    this.remoteBridgeToken = '',
    this.remoteCwd = '',
    this.remoteConfigured = false,
    this.runtime,
  });

  final bool remoteEnabled;
  final String remoteBridgeUrl;
  final String remoteBridgeToken;
  final String remoteCwd;
  final bool remoteConfigured;
  final String? runtime;

  factory CodexRemoteBridgeConfig.fromMap(Map<dynamic, dynamic>? map) {
    final source = map ?? const <dynamic, dynamic>{};
    return CodexRemoteBridgeConfig(
      remoteEnabled: source['remoteEnabled'] == true,
      remoteBridgeUrl: _stringOrNull(source['remoteBridgeUrl']) ?? '',
      remoteBridgeToken: _stringOrNull(source['remoteBridgeToken']) ?? '',
      remoteCwd: _stringOrNull(source['remoteCwd']) ?? '',
      remoteConfigured: source['remoteConfigured'] == true,
      runtime: _stringOrNull(source['runtime']),
    );
  }
}

class CodexRemoteDirectoryEntry {
  const CodexRemoteDirectoryEntry({
    required this.name,
    required this.path,
    required this.type,
    this.hidden = false,
  });

  final String name;
  final String path;
  final String type;
  final bool hidden;

  bool get isDirectory => type == 'directory';

  factory CodexRemoteDirectoryEntry.fromMap(Map<dynamic, dynamic> map) {
    return CodexRemoteDirectoryEntry(
      name: _stringOrNull(map['name']) ?? '',
      path: _stringOrNull(map['path']) ?? '',
      type: _stringOrNull(map['type']) ?? 'other',
      hidden: map['hidden'] == true,
    );
  }
}

class CodexRemoteDirectoryList {
  const CodexRemoteDirectoryList({
    required this.ok,
    required this.path,
    this.parent,
    this.cwd,
    this.home,
    this.error,
    this.entries = const <CodexRemoteDirectoryEntry>[],
  });

  final bool ok;
  final String path;
  final String? parent;
  final String? cwd;
  final String? home;
  final String? error;
  final List<CodexRemoteDirectoryEntry> entries;

  factory CodexRemoteDirectoryList.fromMap(Map<dynamic, dynamic>? map) {
    final source = map ?? const <dynamic, dynamic>{};
    final rawEntries = source['entries'];
    return CodexRemoteDirectoryList(
      ok: source['ok'] == true,
      path: _stringOrNull(source['path']) ?? '',
      parent: _stringOrNull(source['parent']),
      cwd: _stringOrNull(source['cwd']),
      home: _stringOrNull(source['home']),
      error: _stringOrNull(source['error']),
      entries: rawEntries is List
          ? rawEntries
                .whereType<Map>()
                .map(CodexRemoteDirectoryEntry.fromMap)
                .where(
                  (entry) => entry.name.isNotEmpty && entry.path.isNotEmpty,
                )
                .toList(growable: false)
          : const <CodexRemoteDirectoryEntry>[],
    );
  }
}

class CodexRemoteFilePayload {
  const CodexRemoteFilePayload({
    required this.ok,
    required this.path,
    required this.name,
    this.type = 'file',
    this.size,
    this.mtimeMs,
    this.mimeType = 'application/octet-stream',
    this.previewKind = 'file',
    this.encoding,
    this.content,
    this.dataBase64,
    this.truncated = false,
    this.error,
  });

  final bool ok;
  final String path;
  final String name;
  final String type;
  final int? size;
  final double? mtimeMs;
  final String mimeType;
  final String previewKind;
  final String? encoding;
  final String? content;
  final String? dataBase64;
  final bool truncated;
  final String? error;

  bool get isTextLike => previewKind == 'text' || previewKind == 'code';

  Uint8List? get bytes {
    final encoded = dataBase64;
    if (encoded == null || encoded.isEmpty) return null;
    try {
      return base64Decode(encoded);
    } catch (_) {
      return null;
    }
  }

  factory CodexRemoteFilePayload.fromMap(Map<dynamic, dynamic>? map) {
    final source = map ?? const <dynamic, dynamic>{};
    return CodexRemoteFilePayload(
      ok: source['ok'] == true,
      path: _stringOrNull(source['path']) ?? '',
      name: _stringOrNull(source['name']) ?? '',
      type: _stringOrNull(source['type']) ?? 'file',
      size: _intOrNull(source['size']),
      mtimeMs: _doubleOrNull(source['mtimeMs']),
      mimeType: _stringOrNull(source['mimeType']) ?? 'application/octet-stream',
      previewKind: _stringOrNull(source['previewKind']) ?? 'file',
      encoding: _stringOrNull(source['encoding']),
      content: source['content']?.toString(),
      dataBase64: _stringOrNull(source['dataBase64']),
      truncated: source['truncated'] == true,
      error: _stringOrNull(source['error']),
    );
  }
}

class AgentRuntimeService {
  AgentRuntimeService._();

  static const MethodChannel _methodChannel = MethodChannel(
    'cn.com.omnimind.bot/AgentRuntime',
  );
  static const EventChannel _eventChannel = EventChannel(
    'cn.com.omnimind.bot/AgentRuntimeEvents',
  );

  static final StreamController<Map<String, dynamic>> _eventController =
      StreamController<Map<String, dynamic>>.broadcast();
  static final Map<String, Future<Map<String, dynamic>>>
  _agentPreparationTasks = <String, Future<Map<String, dynamic>>>{};
  static final StreamController<Set<String>> _agentPreparationController =
      StreamController<Set<String>>.broadcast();
  static StreamSubscription<dynamic>? _nativeEventSubscription;

  static Stream<Map<String, dynamic>> get events {
    _ensureEventSubscription();
    return _eventController.stream;
  }

  /// Harness preparation can spend minutes downloading npm/native packages.
  /// Keep the operation owned by the service instead of a settings page so it
  /// continues when that page is popped and a rebuilt page can recover the
  /// in-flight state without starting the same installation again.
  static Set<String> get preparingAgentIds =>
      Set<String>.unmodifiable(_agentPreparationTasks.keys);

  static Stream<Set<String>> get agentPreparationChanges =>
      _agentPreparationController.stream;

  static Future<AgentRuntimeStatus> status() async {
    final result = await _invokeMap('status');
    return AgentRuntimeStatus.fromMap(result);
  }

  static Future<AgentRuntimeStatus> connect() async {
    final result = await _invokeMap('connect');
    return AgentRuntimeStatus.fromMap(result);
  }

  static Future<AgentRuntimeStatus> disconnect() async {
    final result = await _invokeMap('disconnect');
    return AgentRuntimeStatus.fromMap(result);
  }

  /// Returns the already negotiated ACP AgentInfo. The native host performs
  /// the wire handshake once per transport; callers must not create a second
  /// session just to initialize a shared ACP client.
  static Future<Map<String, dynamic>> initialize({String? agentId}) {
    return _invokeMap('initialize', {
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
    });
  }

  static Future<AcpAgentCatalog> listAgents() async {
    return AcpAgentCatalog.fromMap(await _invokeMap('agent/list'));
  }

  static Future<AcpAgentCatalog> refreshAgents() async {
    return AcpAgentCatalog.fromMap(await _invokeMap('agent/refresh'));
  }

  static Future<AcpAgentCatalog> selectAgent(String agentId) async {
    return AcpAgentCatalog.fromMap(
      await _invokeMap('agent/select', {'agentId': agentId.trim()}),
    );
  }

  static Future<AcpAgentCatalog> saveAgent(AcpAgentProfile agent) async {
    final response = await _invokeMap('agent/save', {'agent': agent.toMap()});
    return AcpAgentCatalog.fromMap(
      response['catalog'] is Map
          ? response['catalog'] as Map<dynamic, dynamic>
          : response,
    );
  }

  static Future<AcpAgentCatalog> deleteAgent(String agentId) async {
    return AcpAgentCatalog.fromMap(
      await _invokeMap('agent/delete', {'agentId': agentId.trim()}),
    );
  }

  static Future<Map<String, dynamic>> testAgent(String agentId) {
    return _invokeMap('agent/test', {'agentId': agentId.trim()});
  }

  static Future<Map<String, dynamic>> prepareAgent(String agentId) {
    return _invokeMap('agent/prepare', {'agentId': agentId.trim()});
  }

  static Future<Map<String, dynamic>> prepareAgentInBackground(String agentId) {
    final normalizedId = agentId.trim();
    final existing = _agentPreparationTasks[normalizedId];
    if (existing != null) return existing;

    final task = prepareAgent(normalizedId);
    _agentPreparationTasks[normalizedId] = task;
    _emitAgentPreparationState();
    unawaited(
      task.then<void>(
        (_) => _finishAgentPreparation(normalizedId, task),
        onError: (Object _, StackTrace __) {
          _finishAgentPreparation(normalizedId, task);
        },
      ),
    );
    return task;
  }

  static void _finishAgentPreparation(
    String agentId,
    Future<Map<String, dynamic>> task,
  ) {
    if (!identical(_agentPreparationTasks[agentId], task)) return;
    _agentPreparationTasks.remove(agentId);
    _emitAgentPreparationState();
  }

  static void _emitAgentPreparationState() {
    _agentPreparationController.add(preparingAgentIds);
  }

  static Future<Map<String, dynamic>> readAgentConfig(String agentId) {
    return _invokeMap('agent/config/read', {'agentId': agentId.trim()});
  }

  static Future<Map<String, dynamic>> writeAgentConfig(
    String agentId, {
    String? baseUrl,
    String? model,
    String? apiKey,
    String? reasoningEffort,
    String? permissionMode,
    String? content,
    int? expectedRevision,
  }) {
    return _invokeMap('agent/config/write', {
      'agentId': agentId.trim(),
      if (baseUrl != null) 'baseUrl': baseUrl,
      if (model != null) 'model': model,
      if (apiKey != null) 'apiKey': apiKey,
      if (reasoningEffort != null) 'reasoningEffort': reasoningEffort,
      if (permissionMode != null) 'permissionMode': permissionMode,
      if (content != null) 'content': content,
      if (expectedRevision != null) 'expectedRevision': expectedRevision,
    });
  }

  static Future<Map<String, dynamic>> rollbackAgentConfig(
    String agentId, {
    required int targetRevision,
    int? expectedRevision,
  }) {
    return _invokeMap('agent/config/rollback', {
      'agentId': agentId.trim(),
      'targetRevision': targetRevision,
      if (expectedRevision != null) 'expectedRevision': expectedRevision,
    });
  }

  // Canonical ACP application API. New business code must use these
  // session/* methods. Legacy thread/turn requests are accepted only by the
  // native compatibility adapter and are not exposed as a Dart service API.
  static Future<Map<String, dynamic>> newSession({
    int? conversationId,
    String? cwd,
    String? model,
    String? effort,
    String? collaborationMode,
    String? conversationMode,
    List<String> additionalDirectories = const <String>[],
  }) {
    return _invokeMap('session/new', {
      if (conversationId != null) 'conversationId': conversationId,
      if (cwd != null && cwd.trim().isNotEmpty) 'cwd': cwd.trim(),
      if (model != null && model.trim().isNotEmpty) 'model': model.trim(),
      if (effort != null && effort.trim().isNotEmpty) 'effort': effort.trim(),
      if (collaborationMode != null && collaborationMode.trim().isNotEmpty)
        'collaborationMode': collaborationMode.trim(),
      if (conversationMode != null && conversationMode.trim().isNotEmpty)
        'conversationMode': conversationMode.trim(),
      if (additionalDirectories.isNotEmpty)
        'additionalDirectories': additionalDirectories,
    });
  }

  /// Resolves the ACP session identity required by a prompt.
  ///
  /// A missing session is an application-level bootstrap case, not a second
  /// protocol. Keep the bootstrap on the official `session/new` operation and
  /// return only the stable identity that the caller must use for
  /// `session/prompt`. Legacy callers may still call `promptSession` without
  /// an id; new lifecycle code should resolve it here first.
  static Future<String> ensureSession({
    String? sessionId,
    int? conversationId,
    String? cwd,
    String? model,
    String? effort,
    String? collaborationMode,
    String? conversationMode,
    List<String> additionalDirectories = const <String>[],
  }) async {
    final existing = sessionId?.trim() ?? '';
    if (existing.isNotEmpty) return existing;

    final response = await newSession(
      conversationId: conversationId,
      cwd: cwd,
      model: model,
      effort: effort,
      collaborationMode: collaborationMode,
      conversationMode: conversationMode,
      additionalDirectories: additionalDirectories,
    );
    final resolved = (response['sessionId'] ?? response['threadId'])
        ?.toString()
        .trim();
    if (resolved == null || resolved.isEmpty) {
      throw StateError('ACP session/new did not return a session id');
    }
    return resolved;
  }

  static Future<Map<String, dynamic>> loadSession({
    String? sessionId,
    int? conversationId,
    String? agentId,
    String? conversationMode,
    List<String> additionalDirectories = const <String>[],
  }) {
    return _invokeMap('session/load', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
      if (conversationMode != null && conversationMode.trim().isNotEmpty)
        'conversationMode': conversationMode.trim(),
      if (additionalDirectories.isNotEmpty)
        'additionalDirectories': additionalDirectories,
    });
  }

  /// Resumes an ACP session through the shared runtime. The host keeps this
  /// distinct from session/load because ACP agents may implement different
  /// replay/resume semantics; chat callers do not need to know which Harness
  /// owns the session.
  static Future<Map<String, dynamic>> resumeSession({
    String? sessionId,
    int? conversationId,
    String? agentId,
    String? conversationMode,
    List<String> additionalDirectories = const <String>[],
  }) {
    return _invokeMap('session/resume', {
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
      if (conversationMode != null && conversationMode.trim().isNotEmpty)
        'conversationMode': conversationMode.trim(),
      if (additionalDirectories.isNotEmpty)
        'additionalDirectories': additionalDirectories,
    });
  }

  static Future<Map<String, dynamic>> readSession({
    String? sessionId,
    int? conversationId,
    String? agentId,
    bool includeHistory = true,
    bool refreshConfig = false,
    String? conversationMode,
  }) {
    return _invokeMap('session/load', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
      'includeHistory': includeHistory,
      if (refreshConfig) 'refreshConfig': true,
      if (conversationMode != null && conversationMode.trim().isNotEmpty)
        'conversationMode': conversationMode.trim(),
    });
  }

  static Future<Map<String, dynamic>> listSessions({
    int? limit,
    String? cursor,
    String? cwd,
    List<String> additionalDirectories = const <String>[],
  }) {
    return _invokeMap('session/list', {
      if (limit != null) 'limit': limit,
      if (cursor != null && cursor.trim().isNotEmpty) 'cursor': cursor.trim(),
      if (cwd != null && cwd.trim().isNotEmpty) 'cwd': cwd.trim(),
      if (additionalDirectories.isNotEmpty)
        'additionalDirectories': additionalDirectories,
    });
  }

  /// Creates a new ACP session fork and lets the host bind it to a new
  /// conversation. The chat page does not need to know which Harness owns the
  /// source session.
  static Future<Map<String, dynamic>> forkSession({
    String? sessionId,
    int? conversationId,
    String? cwd,
    String? conversationMode,
    List<String> additionalDirectories = const <String>[],
  }) {
    return _invokeMap('session/fork', {
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      if (cwd != null && cwd.trim().isNotEmpty) 'cwd': cwd.trim(),
      if (conversationMode != null && conversationMode.trim().isNotEmpty)
        'conversationMode': conversationMode.trim(),
      if (additionalDirectories.isNotEmpty)
        'additionalDirectories': additionalDirectories,
    });
  }

  static Future<Map<String, dynamic>> listLoadedSessions() {
    return _invokeMap('session/list');
  }

  /// Close releases the live ACP Session without archiving or deleting the
  /// user's conversation history. Persistence remains owned by the host DB.
  static Future<Map<String, dynamic>> closeSession({
    String? sessionId,
    int? conversationId,
  }) {
    return _invokeMap('session/close', {
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
    });
  }

  /// Deletes the ACP-side session while retaining OmniBot's local
  /// conversation and messages. The shared runtime returns historyPreserved.
  static Future<Map<String, dynamic>> deleteSession({
    String? sessionId,
    int? conversationId,
  }) {
    return _invokeMap('session/delete', {
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
    });
  }

  static Future<Map<String, dynamic>> setSessionMode({
    String? sessionId,
    int? conversationId,
    required String modeId,
  }) {
    return _invokeMap('session/set_mode', {
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      'modeId': modeId.trim(),
    });
  }

  static Future<Map<String, dynamic>> authenticateAgent({
    required String methodId,
    Map<String, dynamic>? meta,
  }) {
    return _invokeMap('authenticate', {
      'methodId': methodId.trim(),
      if (meta != null) '_meta': meta,
    });
  }

  static Future<Map<String, dynamic>> logoutAgent({
    Map<String, dynamic>? meta,
  }) {
    return _invokeMap('logout', {if (meta != null) '_meta': meta});
  }

  static Future<Map<String, dynamic>> listAgentProviders({
    Map<String, dynamic>? meta,
  }) {
    return _invokeMap('providers/list', {if (meta != null) '_meta': meta});
  }

  static Future<Map<String, dynamic>> setAgentProvider(
    Map<String, dynamic> params,
  ) {
    return _invokeMap('providers/set', params);
  }

  static Future<Map<String, dynamic>> disableAgentProvider(
    Map<String, dynamic> params,
  ) {
    return _invokeMap('providers/disable', params);
  }

  /// Calls an ACP implementation extension without introducing a second
  /// Harness transport. ACP reserves the underscore namespace for extension
  /// methods; unknown core-looking method names are rejected at the host
  /// boundary so a typo cannot silently become a legacy RPC.
  static Future<Map<String, dynamic>> callAcpExtension({
    required String method,
    Map<String, dynamic> params = const <String, dynamic>{},
  }) {
    return _invokeMap(_validateAcpExtensionMethod(method), params);
  }

  /// Sends a client-to-agent ACP extension notification. Notifications do
  /// not produce an Agent response, but using this shared bridge keeps the
  /// extension on the same transport as every other ACP operation.
  static Future<Map<String, dynamic>> notifyAcpExtension({
    required String method,
    Object? params,
  }) {
    return _invokeMap('notifyAcpExtension', {
      'method': _validateAcpExtensionMethod(method),
      if (params != null) 'params': params,
    });
  }

  /// Preserves non-object extension results such as arrays and scalar values.
  static Future<dynamic> callAcpExtensionValue({
    required String method,
    Object? params,
  }) {
    return _invokeValue(_validateAcpExtensionMethod(method), {
      if (params != null) 'params': params,
    });
  }

  static Future<Map<String, dynamic>> archiveSession({
    String? sessionId,
    int? conversationId,
  }) {
    return _invokeMap('session/archive', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
    });
  }

  static Future<Map<String, dynamic>> unarchiveSession({
    String? sessionId,
    int? conversationId,
  }) {
    return _invokeMap('session/unarchive', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
    });
  }

  static Future<Map<String, dynamic>> setSessionName({
    String? sessionId,
    int? conversationId,
    required String name,
  }) {
    return _invokeMap('session/name/set', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      'name': name,
    });
  }

  static Future<Map<String, dynamic>> promptSession({
    String? sessionId,
    int? conversationId,
    String? requestId,
    String? agentId,
    required String text,
    List<Map<String, dynamic>> attachments = const [],
    String? cwd,
    String? approvalPolicy,
    String? approvalsReviewer,
    Map<String, dynamic>? sandboxPolicy,
    String? model,
    String? effort,
    String? collaborationMode,
    String? conversationMode,
    Map<String, String>? terminalEnvironment,
  }) {
    return _invokeMap('session/prompt', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      if (requestId != null && requestId.trim().isNotEmpty)
        'requestId': requestId.trim(),
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
      if (cwd != null && cwd.trim().isNotEmpty) 'cwd': cwd.trim(),
      if (approvalPolicy != null && approvalPolicy.trim().isNotEmpty)
        'approvalPolicy': approvalPolicy.trim(),
      if (approvalsReviewer != null && approvalsReviewer.trim().isNotEmpty)
        'approvalsReviewer': approvalsReviewer.trim(),
      if (sandboxPolicy != null) 'sandboxPolicy': sandboxPolicy,
      if (model != null && model.trim().isNotEmpty) 'model': model.trim(),
      if (effort != null && effort.trim().isNotEmpty) 'effort': effort.trim(),
      if (collaborationMode != null && collaborationMode.trim().isNotEmpty)
        'collaborationMode': collaborationMode.trim(),
      if (conversationMode != null && conversationMode.trim().isNotEmpty)
        'conversationMode': conversationMode.trim(),
      if (terminalEnvironment != null && terminalEnvironment.isNotEmpty)
        'terminalEnvironment': terminalEnvironment,
      'text': text,
      if (attachments.isNotEmpty) 'attachments': attachments,
    });
  }

  static Future<Map<String, dynamic>> cancelPrompt({
    String? sessionId,
    int? conversationId,
    String? promptId,
    String? runId,
  }) {
    return _invokeMap('session/cancel', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      if (promptId != null) 'promptId': promptId,
      if (runId != null && runId.trim().isNotEmpty) 'runId': runId.trim(),
    });
  }

  /// Cancels one JSON-RPC request. This is deliberately separate from
  /// session/cancel, which cancels the active ACP turn/session lifecycle.
  static Future<Map<String, dynamic>> cancelRequest({
    required Object requestId,
    String? sessionId,
    String? agentId,
  }) {
    return _invokeMap('\$/cancel_request', {
      'requestId': requestId,
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
    });
  }

  static Future<Map<String, dynamic>> reviewSession({
    String? sessionId,
    int? conversationId,
    String? cwd,
    Map<String, dynamic>? target,
    String? approvalPolicy,
    String? approvalsReviewer,
    Map<String, dynamic>? sandboxPolicy,
    String? model,
    String? effort,
    String? collaborationMode,
  }) {
    return _invokeMap('review/start', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      if (cwd != null && cwd.trim().isNotEmpty) 'cwd': cwd.trim(),
      'target': target ?? <String, dynamic>{'type': 'uncommittedChanges'},
      if (approvalPolicy != null && approvalPolicy.trim().isNotEmpty)
        'approvalPolicy': approvalPolicy.trim(),
      if (approvalsReviewer != null && approvalsReviewer.trim().isNotEmpty)
        'approvalsReviewer': approvalsReviewer.trim(),
      if (sandboxPolicy != null) 'sandboxPolicy': sandboxPolicy,
      if (model != null && model.trim().isNotEmpty) 'model': model.trim(),
      if (effort != null && effort.trim().isNotEmpty) 'effort': effort.trim(),
      if (collaborationMode != null && collaborationMode.trim().isNotEmpty)
        'collaborationMode': collaborationMode.trim(),
    });
  }

  @Deprecated('Use reviewSession')
  static Future<Map<String, dynamic>> startReview({
    String? threadId,
    int? conversationId,
    String? cwd,
    Map<String, dynamic>? target,
    String? approvalPolicy,
    String? approvalsReviewer,
    Map<String, dynamic>? sandboxPolicy,
    String? model,
    String? effort,
    String? collaborationMode,
  }) {
    return _invokeMap('review/start', {
      if (threadId != null) 'threadId': threadId,
      if (conversationId != null) 'conversationId': conversationId,
      if (cwd != null && cwd.trim().isNotEmpty) 'cwd': cwd.trim(),
      'target': target ?? <String, dynamic>{'type': 'uncommittedChanges'},
      if (approvalPolicy != null && approvalPolicy.trim().isNotEmpty)
        'approvalPolicy': approvalPolicy.trim(),
      if (approvalsReviewer != null && approvalsReviewer.trim().isNotEmpty)
        'approvalsReviewer': approvalsReviewer.trim(),
      if (sandboxPolicy != null) 'sandboxPolicy': sandboxPolicy,
      if (model != null && model.trim().isNotEmpty) 'model': model.trim(),
      if (effort != null && effort.trim().isNotEmpty) 'effort': effort.trim(),
      if (collaborationMode != null && collaborationMode.trim().isNotEmpty)
        'collaborationMode': collaborationMode.trim(),
    });
  }

  static Future<Map<String, dynamic>> listModels() {
    return _invokeMap('model/list');
  }

  static Future<Map<String, dynamic>> listModelsForStatus(
    AgentRuntimeStatus status,
  ) => listModels();

  static Future<Map<String, dynamic>> listCollaborationModes() {
    return _invokeMap('collaborationMode/list');
  }

  static Future<Map<String, dynamic>> readConfig() {
    return _invokeMap('config/read');
  }

  static Future<Map<String, dynamic>> setSessionConfigOption({
    String? sessionId,
    int? conversationId,
    String? agentId,
    required String configId,
    required dynamic value,
  }) {
    return _invokeMap('session/set_config_option', {
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
      'configId': configId.trim(),
      'value': value,
    });
  }

  @Deprecated('Use setSessionConfigOption')
  static Future<Map<String, dynamic>> setConfigOption({
    String? threadId,
    int? conversationId,
    required String configId,
    required dynamic value,
  }) {
    return _invokeMap('session/set_config_option', {
      if (threadId != null && threadId.trim().isNotEmpty)
        'threadId': threadId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      'configId': configId.trim(),
      'value': value,
    });
  }

  static Future<CodexRemoteBridgeConfig> readRemoteBridgeConfig() async {
    final result = await _invokeMap('config/remote/read');
    return CodexRemoteBridgeConfig.fromMap(result);
  }

  static Future<CodexRemoteBridgeConfig> writeRemoteBridgeConfig({
    bool remoteEnabled = false,
    String remoteBridgeUrl = '',
    String remoteBridgeToken = '',
    String remoteCwd = '',
  }) async {
    final result = await _invokeMap('config/remote/write', {
      'remoteEnabled': remoteEnabled,
      'remoteBridgeUrl': remoteBridgeUrl.trim(),
      'remoteBridgeToken': remoteBridgeToken.trim(),
      'remoteCwd': remoteCwd.trim(),
    });
    return CodexRemoteBridgeConfig.fromMap(result);
  }

  static Future<Map<String, dynamic>> testRemoteConfig({
    required String remoteBridgeUrl,
    required String remoteBridgeToken,
    required String remoteCwd,
  }) {
    return _invokeMap('config/remote/test', {
      'remoteBridgeUrl': remoteBridgeUrl.trim(),
      'remoteBridgeToken': remoteBridgeToken.trim(),
      'remoteCwd': remoteCwd.trim(),
    });
  }

  static Future<CodexRemoteDirectoryList> listRemoteDirectories({
    String remoteBridgeUrl = '',
    String remoteBridgeToken = '',
    String remoteCwd = '',
    String? path,
  }) async {
    final result = await _invokeMap('config/remote/fs/list', {
      if (remoteBridgeUrl.trim().isNotEmpty)
        'remoteBridgeUrl': remoteBridgeUrl.trim(),
      if (remoteBridgeToken.trim().isNotEmpty)
        'remoteBridgeToken': remoteBridgeToken.trim(),
      if (remoteCwd.trim().isNotEmpty) 'remoteCwd': remoteCwd.trim(),
      if (path != null && path.trim().isNotEmpty) 'path': path.trim(),
    });
    return CodexRemoteDirectoryList.fromMap(result);
  }

  static Future<CodexRemoteFilePayload> readRemoteFile({
    String remoteBridgeUrl = '',
    String remoteBridgeToken = '',
    String remoteCwd = '',
    required String path,
  }) async {
    final result = await _invokeMap('config/remote/fs/read', {
      if (remoteBridgeUrl.trim().isNotEmpty)
        'remoteBridgeUrl': remoteBridgeUrl.trim(),
      if (remoteBridgeToken.trim().isNotEmpty)
        'remoteBridgeToken': remoteBridgeToken.trim(),
      if (remoteCwd.trim().isNotEmpty) 'remoteCwd': remoteCwd.trim(),
      'path': path.trim(),
    });
    return CodexRemoteFilePayload.fromMap(result);
  }

  static Future<Map<String, dynamic>> writeRemoteFile({
    String remoteBridgeUrl = '',
    String remoteBridgeToken = '',
    String remoteCwd = '',
    required String path,
    required String content,
  }) {
    return _invokeMap('config/remote/fs/write', {
      if (remoteBridgeUrl.trim().isNotEmpty)
        'remoteBridgeUrl': remoteBridgeUrl.trim(),
      if (remoteBridgeToken.trim().isNotEmpty)
        'remoteBridgeToken': remoteBridgeToken.trim(),
      if (remoteCwd.trim().isNotEmpty) 'remoteCwd': remoteCwd.trim(),
      'path': path.trim(),
      'content': content,
    });
  }

  static Future<Map<String, dynamic>> deleteRemotePath({
    String remoteBridgeUrl = '',
    String remoteBridgeToken = '',
    String remoteCwd = '',
    required String path,
    bool recursive = false,
  }) {
    return _invokeMap('config/remote/fs/delete', {
      if (remoteBridgeUrl.trim().isNotEmpty)
        'remoteBridgeUrl': remoteBridgeUrl.trim(),
      if (remoteBridgeToken.trim().isNotEmpty)
        'remoteBridgeToken': remoteBridgeToken.trim(),
      if (remoteCwd.trim().isNotEmpty) 'remoteCwd': remoteCwd.trim(),
      'path': path.trim(),
      'recursive': recursive,
    });
  }

  static Future<Map<String, dynamic>> moveRemotePath({
    String remoteBridgeUrl = '',
    String remoteBridgeToken = '',
    String remoteCwd = '',
    required String path,
    required String destinationPath,
  }) {
    return _invokeMap('config/remote/fs/move', {
      if (remoteBridgeUrl.trim().isNotEmpty)
        'remoteBridgeUrl': remoteBridgeUrl.trim(),
      if (remoteBridgeToken.trim().isNotEmpty)
        'remoteBridgeToken': remoteBridgeToken.trim(),
      if (remoteCwd.trim().isNotEmpty) 'remoteCwd': remoteCwd.trim(),
      'path': path.trim(),
      'destinationPath': destinationPath.trim(),
    });
  }

  static Future<Map<String, dynamic>> readAccount() {
    return _invokeMap('account/read');
  }

  static Future<Map<String, dynamic>> startLogin({
    CodexLoginType type = CodexLoginType.chatgpt,
  }) {
    return _invokeMap('account/login/start', {'type': type.payloadValue});
  }

  static Future<Map<String, dynamic>> cancelLogin({String? loginId}) {
    return _invokeMap('account/login/cancel', {
      if (loginId != null && loginId.trim().isNotEmpty)
        'loginId': loginId.trim(),
    });
  }

  static Future<Map<String, dynamic>> respondToApproval({
    required Object requestId,
    required bool accepted,
    String? sessionId,
    String? agentId,
    int? conversationId,
  }) {
    return _invokeServerResponse({
      'requestId': requestId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      'response': {'decision': accepted ? 'accept' : 'decline'},
    });
  }

  static Future<Map<String, dynamic>> respondToUserInput({
    required Object requestId,
    required String questionId,
    required List<String> answers,
    String? sessionId,
    String? agentId,
    int? conversationId,
  }) {
    return _invokeServerResponse({
      'requestId': requestId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      'response': {
        'answers': {
          questionId: {'answers': answers},
        },
      },
    });
  }

  /// Answers a standard ACP `elicitation/create` request.  Unlike the
  /// legacy requestUserInput shape, ACP form values live directly under
  /// `response.content` and must retain their primitive JSON types.
  static Future<Map<String, dynamic>> respondToElicitation({
    required Object requestId,
    required Map<String, dynamic> content,
    String? sessionId,
    String? agentId,
    int? conversationId,
  }) {
    return _invokeServerResponse({
      'requestId': requestId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      'response': {'action': 'accept', 'content': content},
    });
  }

  static Future<Map<String, dynamic>> cancelElicitation({
    required Object requestId,
    String? sessionId,
    String? agentId,
    int? conversationId,
  }) {
    return _invokeServerResponse({
      'requestId': requestId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      'response': {'action': 'cancel'},
    });
  }

  static Future<Map<String, dynamic>> ignoreUserInput({
    required Object requestId,
    String? sessionId,
    String? agentId,
    int? conversationId,
  }) {
    return _invokeServerResponse({
      'requestId': requestId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      'response': {'answers': <String, dynamic>{}},
    });
  }

  static void _ensureEventSubscription() {
    if (_nativeEventSubscription != null) return;
    _nativeEventSubscription = _eventChannel.receiveBroadcastStream().listen(
      (event) {
        final normalized = _normalizeMap(event);
        if (normalized != null) {
          _eventController.add(normalized);
        }
      },
      onError: (Object error, StackTrace stackTrace) {
        _eventController.add({
          'method': 'error',
          'message': {
            'method': 'error',
            'params': {'error': error.toString()},
          },
        });
      },
    );
  }

  static Future<Map<String, dynamic>> _invokeMap(
    String method, [
    Map<String, dynamic> args = const <String, dynamic>{},
  ]) async {
    final result = await _methodChannel.invokeMethod<dynamic>(method, args);
    return _normalizeMap(result) ?? <String, dynamic>{};
  }

  static Future<Map<String, dynamic>> _invokeServerResponse(
    Map<String, dynamic> args,
  ) async {
    final result = await _invokeMap('respondToServerRequest', args);
    if (result['ok'] != true) {
      throw StateError('ACP server request was not acknowledged');
    }
    return result;
  }

  static Future<dynamic> _invokeValue(
    String method, [
    Map<String, dynamic> args = const <String, dynamic>{},
  ]) async {
    final result = await _methodChannel.invokeMethod<dynamic>(method, args);
    return _normalizeValue(result);
  }
}

String _validateAcpExtensionMethod(String method) {
  final normalized = method.trim();
  if (!normalized.startsWith('_')) {
    throw ArgumentError.value(
      method,
      'method',
      'ACP extension methods must use the underscore namespace',
    );
  }
  return normalized;
}

Map<String, dynamic>? _normalizeMap(dynamic value) {
  if (value is! Map) return null;
  return value.map((key, nestedValue) {
    return MapEntry(key.toString(), _normalizeValue(nestedValue));
  });
}

dynamic _normalizeValue(dynamic value) {
  if (value is Map) {
    return value.map((key, nestedValue) {
      return MapEntry(key.toString(), _normalizeValue(nestedValue));
    });
  }
  if (value is List) {
    return value.map(_normalizeValue).toList();
  }
  return value;
}

String? _stringOrNull(dynamic value) {
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

int? _intOrNull(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

bool? _boolOrNull(dynamic value) {
  if (value is bool) return value;
  if (value is num) return value.toInt() != 0;
  final normalized = value?.toString().trim().toLowerCase() ?? '';
  return switch (normalized) {
    'true' || '1' || 'yes' => true,
    'false' || '0' || 'no' => false,
    _ => null,
  };
}

double? _doubleOrNull(dynamic value) {
  if (value is double) return value;
  if (value is num) return value.toDouble();
  return double.tryParse(value?.toString() ?? '');
}
