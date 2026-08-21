import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

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
  const AcpAgentCatalog({required this.selectedAgentId, required this.agents});

  final String selectedAgentId;
  final List<AcpAgentProfile> agents;

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
    );
  }
}

String _agentCatalogIdentity(AcpAgentProfile agent) {
  final normalizedName = agent.name.trim().toLowerCase().replaceAll(
    RegExp(r'[\s_-]+'),
    '',
  );
  if (agent.id == 'xiaowan-acp' ||
      agent.command.toLowerCase() == 'omnibot-xiaowan-acp' ||
      normalizedName == '小万bot' ||
      normalizedName == 'xiaowanbot') {
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
  static StreamSubscription<dynamic>? _nativeEventSubscription;

  static Stream<Map<String, dynamic>> get events {
    _ensureEventSubscription();
    return _eventController.stream;
  }

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
  }) {
    return _invokeMap('agent/config/write', {
      'agentId': agentId.trim(),
      if (baseUrl != null) 'baseUrl': baseUrl,
      if (model != null) 'model': model,
      if (apiKey != null) 'apiKey': apiKey,
      if (reasoningEffort != null) 'reasoningEffort': reasoningEffort,
      if (permissionMode != null) 'permissionMode': permissionMode,
      if (content != null) 'content': content,
    });
  }

  // Canonical ACP application API. New code must use session/prompt names;
  // the methods below keep the previous Dart surface working for old builds.
  static Future<Map<String, dynamic>> newSession({
    int? conversationId,
    String? cwd,
    String? model,
    String? effort,
    String? collaborationMode,
    String? conversationMode,
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
    });
  }

  static Future<Map<String, dynamic>> loadSession({
    String? sessionId,
    int? conversationId,
    String? agentId,
  }) {
    return _invokeMap('session/load', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
    });
  }

  static Future<Map<String, dynamic>> readSession({
    String? sessionId,
    int? conversationId,
    String? agentId,
    bool includeHistory = true,
  }) {
    return _invokeMap('session/load', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
      'includeHistory': includeHistory,
    });
  }

  static Future<Map<String, dynamic>> listSessions({
    int limit = 50,
    String? cursor,
  }) {
    return _invokeMap('session/list', {
      'limit': limit,
      if (cursor != null && cursor.trim().isNotEmpty) 'cursor': cursor.trim(),
    });
  }

  static Future<Map<String, dynamic>> listLoadedSessions() {
    return _invokeMap('session/list');
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
      'text': text,
      if (attachments.isNotEmpty) 'attachments': attachments,
    });
  }

  static Future<Map<String, dynamic>> cancelPrompt({
    String? sessionId,
    int? conversationId,
    String? promptId,
  }) {
    return _invokeMap('session/cancel', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      if (promptId != null) 'promptId': promptId,
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
    return _invokeMap('model/list', {'limit': 100});
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

  static Future<Map<String, dynamic>> steerTurn({
    String? threadId,
    int? conversationId,
    String? turnId,
    required String text,
  }) {
    return _invokeMap('turn/steer', {
      if (threadId != null) 'threadId': threadId,
      if (conversationId != null) 'conversationId': conversationId,
      if (turnId != null) 'turnId': turnId,
      'text': text,
    });
  }

  static Future<Map<String, dynamic>> interruptTurn({
    String? threadId,
    int? conversationId,
    String? turnId,
  }) {
    return _invokeMap('session/cancel', {
      if (threadId != null) 'threadId': threadId,
      if (conversationId != null) 'conversationId': conversationId,
      if (turnId != null) 'turnId': turnId,
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
  }) {
    return _invokeMap('respondToServerRequest', {
      'requestId': requestId,
      'response': {'decision': accepted ? 'accept' : 'decline'},
    });
  }

  static Future<Map<String, dynamic>> respondToUserInput({
    required Object requestId,
    required String questionId,
    required List<String> answers,
  }) {
    return _invokeMap('respondToServerRequest', {
      'requestId': requestId,
      'response': {
        'answers': {
          questionId: {'answers': answers},
        },
      },
    });
  }

  static Future<Map<String, dynamic>> ignoreUserInput({
    required Object requestId,
  }) {
    return _invokeMap('respondToServerRequest', {
      'requestId': requestId,
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
