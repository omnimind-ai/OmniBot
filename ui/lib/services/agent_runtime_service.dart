import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
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

bool isAgentCancellationSuccessful(Map<String, dynamic> response) {
  return response['ok'] == true ||
      response['cancelled'] == true ||
      response['status'] == 'cancelled';
}

/// Converts an ACP boundary error into short, actionable UI text. Native
/// errors carry a stable `failureKind` in PlatformException.details; use that
/// instead of exposing adapter stack traces or waiting for a raw error string
/// to render in the chat.
const _agentUserErrors = <String, ({String zh, String en})>{
  'agentIdentityRequired': (
    zh: '此对话没有可用的助手归属。历史记录仍可查看，请新建对话。',
    en: 'This conversation has no assistant owner. You can view its history or start a new conversation.',
  ),
  'agentOwnerConflict': (
    zh: '助手与此对话的归属不一致，请返回原对话或新建对话。',
    en: 'The assistant does not match this conversation. Return to the original conversation or start a new one.',
  ),
  'harnessUpgradeRequired': (
    zh: '当前助手运行环境需要升级，请在助手设置中升级或重新安装。',
    en: 'This assistant runtime needs an upgrade. Update or reinstall it in Agent settings.',
  ),
  'agentRetired': (
    zh: '此助手已停止支持。历史记录仍可查看，请新建其他助手的对话。',
    en: 'This assistant is no longer supported. You can view its history or start a new conversation with another assistant.',
  ),
  'modelRequired': (
    zh: '请先在模型设置中选择一个可用模型，再启动助手。',
    en: 'Choose an available model in model settings, then start the assistant.',
  ),
  'credentials': (
    zh: '模型连接验证失败，请在模型设置中检查接口地址和密钥。',
    en: 'Could not verify the model connection. Check its address and key in model settings.',
  ),
  'modelUnavailable': (
    zh: '当前模型不可用，请在模型设置中重新选择。',
    en: 'This model is unavailable. Choose another in model settings.',
  ),
  'certificate': (
    zh: '连接验证失败，请检查网络是否需要登录，并确认设备时间正确。',
    en: 'Could not verify the secure connection. Check whether your network requires sign-in and whether the device time is correct.',
  ),
  'interrupted': (
    zh: '回复连接已中断，请检查网络后重试。未完成的工具调用不会执行。',
    en: 'The reply was interrupted. Check your connection and try again. Incomplete tool calls will not run.',
  ),
  'timeout': (
    zh: '等待回复超时，请检查网络后重试；若仍失败，请更换模型。',
    en: 'The reply timed out. Check your connection and try again, or choose another model.',
  ),
  'incompleteTool': (
    zh: '模型返回了不完整的工具调用，请重试；若仍失败，请更换模型。',
    en: 'The model returned an incomplete tool call. Try again, or choose another model.',
  ),
  'installBusy': (
    zh: '已有助手正在安装，请等待完成后再试。',
    en: 'Another assistant is being installed. Try again when it finishes.',
  ),
  'packageBusy': (
    zh: '助手正在安装或卸载，请等待完成后再试。',
    en: 'An assistant is being installed or uninstalled. Try again when it finishes.',
  ),
  'uninstallFailed': (
    zh: '卸载未完成，请重试。',
    en: 'Uninstall did not finish. Try again.',
  ),
  'uninstallExternalInstallation': (
    zh: '检测到其他安装或无法安全移除的文件，请在终端中检查该助手的安装位置。',
    en: 'Another installation or files that could not be safely removed remain. Check the assistant\'s installation in the terminal.',
  ),
  'uninstallInvalidRequest': (
    zh: '卸载请求无效，请刷新助手列表后重试。',
    en: 'The uninstall request is invalid. Refresh the assistant list and try again.',
  ),
  'uninstallDistributionChanged': (
    zh: '终端环境已切换，请刷新助手列表后重试。',
    en: 'The terminal environment changed. Refresh the assistant list and try again.',
  ),
  'uninstallTerminalUnavailable': (
    zh: '终端环境不可用，请恢复终端环境后重试卸载。',
    en: 'The terminal environment is unavailable. Restore it before trying to uninstall again.',
  ),
  'incompatible': (
    zh: '当前模型连接不适用于这个助手，请更换模型连接或助手。',
    en: 'This model connection is incompatible with the assistant. Choose another connection or assistant.',
  ),
  'unsupportedTools': (
    zh: '当前模型连接不支持这个助手的工具，请更换模型连接或助手。',
    en: 'This model connection does not support the assistant’s tools. Choose another connection or assistant.',
  ),
  'notInstalled': (
    zh: '助手尚未安装完成，请在助手设置中重新安装。',
    en: 'The assistant is not fully installed. Reinstall it in assistant settings.',
  ),
  'quota': (
    zh: '模型服务商额度不足，请检查账户余额或配额后再试。',
    en: 'The model provider quota is exhausted. Check your balance or quota before retrying.',
  ),
  'requestLimited': (
    zh: '模型服务商限制了本次请求，请检查额度和请求频率后再试。',
    en: 'The model provider limited this request. Check your quota and request rate before retrying.',
  ),
  'rateLimit': (
    zh: '模型服务商限制了请求频率，请稍后再试。',
    en: 'The model provider is rate limiting requests. Try again later.',
  ),
  'serviceUnavailable': (
    zh: '模型服务商暂时不可用，请稍后再试或更换模型连接。',
    en: 'The model provider is temporarily unavailable. Try later or choose another connection.',
  ),
  'requestRejected': (
    zh: '模型服务商拒绝了本次请求，请检查模型及请求配置。',
    en: 'The model provider rejected this request. Check the model and request configuration.',
  ),
  'naConnectionFailed': (
    zh: '无法连接 Na 服务，请检查网络后重试。',
    en: 'Could not connect to Na. Check your connection and try again.',
  ),
  'naRequestTimeout': (
    zh: '连接 Na 服务超时，请稍后重试。',
    en: 'The connection to Na timed out. Try again shortly.',
  ),
  'naCertificate': (
    zh: 'Na 安全连接验证失败，请检查网络和设备时间。',
    en: 'Could not verify the secure connection to Na. Check your network and device time.',
  ),
  'naAuthentication': (
    zh: 'Na 连接验证已失效，请重新登录或检查连接设置。',
    en: 'The Na connection is no longer authenticated. Sign in again or check the connection settings.',
  ),
  'naPermission': (
    zh: '当前账号没有此 Na 操作的权限，请检查账号授权。',
    en: 'Your account does not have permission for this Na action. Check its authorization.',
  ),
  'naConnectionChanged': (
    zh: 'Na 连接已变化，请重新打开此对话。',
    en: 'The Na connection changed. Reopen this conversation.',
  ),
  'naOwnerConflict': (
    zh: '此 Na 对话属于其他账号或连接，请切回原账号或新建对话。',
    en: 'This Na conversation belongs to another account or connection. Switch back or start a new conversation.',
  ),
  'naServiceUnavailable': (
    zh: 'Na 服务暂时不可用，请稍后重试。',
    en: 'Na is temporarily unavailable. Try again later.',
  ),
  'naRateLimited': (
    zh: 'Na 请求过于频繁，请稍后重试。',
    en: 'Na is rate limiting requests. Try again later.',
  ),
  'naRequestRejected': (
    zh: 'Na 未接受此请求，请重新打开对话后重试。',
    en: 'Na did not accept this request. Reopen the conversation and try again.',
  ),
  'naResponseInvalid': (
    zh: 'Na 返回了无法识别的响应，请稍后重试。',
    en: 'Na returned an invalid response. Try again later.',
  ),
  'naAdmissionUnconfirmed': (
    zh: '尚未确认 Na 是否已受理，请重新打开对话查看状态。',
    en: 'It is not yet confirmed whether Na accepted this request. Reopen the conversation to check its status.',
  ),
  'unknown': (
    zh: '助手暂时无法完成操作，请重试。',
    en: 'The assistant could not complete this action. Please try again.',
  ),
};

/// Presents actionable errors without exposing transport payloads or credentials.
/// The owning runtime still determines failure/retry; this only formats it.
String formatAgentRuntimeErrorForUser(
  Object? error, {
  bool english = false,
  String? fallback,
}) {
  String? failureKind;
  final rawMessage = error is PlatformException
      ? error.message
      : error?.toString();
  if (error is PlatformException && error.details is Map) {
    failureKind = (error.details as Map)['failureKind']?.toString().trim();
  }
  final raw = rawMessage?.toLowerCase() ?? '';
  String text(String key) {
    final message = _agentUserErrors[key]!;
    return english ? message.en : message.zh;
  }

  if (failureKind == 'agent_identity_required' ||
      raw.contains('agent_identity_required:'))
    return text('agentIdentityRequired');
  if (failureKind == 'agent_owner_conflict' ||
      raw.contains('agent_owner_conflict:'))
    return text('agentOwnerConflict');
  if (failureKind == 'harness_upgrade_required' ||
      raw.contains('runtime needs an upgrade')) {
    return text('harnessUpgradeRequired');
  }
  if (failureKind == 'agent_retired' || raw.contains('agent_retired:')) {
    return text('agentRetired');
  }

  // Na connection failures belong to the cloud service, not the model Provider.
  final naError = switch (failureKind) {
    'na_connection_failed' => 'naConnectionFailed',
    'na_request_timeout' => 'naRequestTimeout',
    'na_tls_certificate_failure' => 'naCertificate',
    'na_authentication_required' => 'naAuthentication',
    'na_permission_denied' => 'naPermission',
    'na_connection_changed' => 'naConnectionChanged',
    'na_owner_conflict' => 'naOwnerConflict',
    'na_service_unavailable' => 'naServiceUnavailable',
    'na_rate_limited' => 'naRateLimited',
    'na_request_rejected' => 'naRequestRejected',
    'na_response_invalid' => 'naResponseInvalid',
    'na_admission_unconfirmed' => 'naAdmissionUnconfirmed',
    _ => null,
  };
  if (naError != null) return text(naError);

  // Package maintenance errors belong to the host, not a model request. Use
  // their structured kind before inspecting diagnostics such as "timeout".
  final packageError = switch (failureKind) {
    'agent_uninstall_failed' => 'uninstallFailed',
    'agent_uninstall_external_installation' => 'uninstallExternalInstallation',
    'agent_uninstall_invalid_request' => 'uninstallInvalidRequest',
    'agent_uninstall_distribution_changed' => 'uninstallDistributionChanged',
    'agent_uninstall_terminal_unavailable' => 'uninstallTerminalUnavailable',
    'agent_package_operation_in_progress' => 'packageBusy',
    _ => null,
  };
  if (packageError != null) {
    return text(packageError);
  }
  if (raw.contains('agent package operation is already running')) {
    return text('packageBusy');
  }

  // Errors may already have been formatted before durable projection.
  // Preserve only these exact app-owned messages, never arbitrary payloads.
  for (final message in _agentUserErrors.values) {
    if (rawMessage == message.zh || rawMessage == message.en) {
      return english ? message.en : message.zh;
    }
  }
  // ACP error messages can arrive without PlatformException.details. Match
  // our transport's HTTP envelope, not arbitrary numbers in tool output.
  final httpMatch = RegExp(
    r'(?:chat completion stream|responses|anthropic) request failed\((\d{3})\)',
  ).firstMatch(raw);
  final httpStatus = int.tryParse(httpMatch?.group(1) ?? '');
  if (failureKind == 'provider_service_unavailable')
    return text('serviceUnavailable');
  if (failureKind == 'provider_request_rejected')
    return text('requestRejected');
  if (failureKind == 'provider_quota_exceeded') return text('quota');
  if (failureKind == 'provider_rate_limited') return text('rateLimit');
  if (failureKind == 'provider_request_limited') return text('requestLimited');
  if (httpStatus == 401) return text('credentials');
  if (httpStatus == 429) {
    if (raw.contains('insufficient_quota') ||
        raw.contains('quota_exceeded') ||
        raw.contains('quota exhausted') ||
        raw.contains('quota_exhausted') ||
        raw.contains('额度不足') ||
        raw.contains('余额不足'))
      return text('quota');
    if (raw.contains('rate_limit_exceeded') ||
        raw.contains('rate_limit_error')) {
      return text('rateLimit');
    }
    return text('requestLimited');
  }
  if (httpStatus != null && httpStatus >= 500) {
    return text('serviceUnavailable');
  }
  if (httpStatus == 400 || httpStatus == 403 || httpStatus == 422) {
    return text('requestRejected');
  }
  if (failureKind == 'provider_not_bound' ||
      (raw.contains('dispatch model') &&
          (raw.contains('not configured') ||
              (raw.contains('no usable') && !raw.contains('credentials'))))) {
    return text('modelRequired');
  }
  if (failureKind == 'provider_unavailable' ||
      failureKind == 'provider_authentication_failed' ||
      raw.contains('no usable credentials') ||
      raw.contains('authentication') ||
      raw.contains('unauthorized') ||
      raw.contains('invalid api key')) {
    return text('credentials');
  }
  if (failureKind == 'provider_model_unavailable') {
    return text('modelUnavailable');
  }
  if (failureKind == 'provider_tls_certificate_failure' ||
      raw.contains('self_signed_cert') ||
      raw.contains('certificate_verify_failed') ||
      raw.contains('certificate verify failed')) {
    return text('certificate');
  }
  if (failureKind == 'provider_stream_interrupted' ||
      raw.contains('stream disconnected') ||
      raw.contains('connection reset') ||
      raw.contains('error sending request for url')) {
    return text('interrupted');
  }
  if (failureKind == 'provider_stream_idle_timeout' ||
      failureKind == 'provider_request_timeout' ||
      raw.contains('timed out') ||
      raw.contains('timeout')) {
    return text('timeout');
  }
  if (failureKind == 'provider_tool_call_incomplete' ||
      raw.contains('missing function.name') ||
      raw.contains('missing function name')) {
    return text('incompleteTool');
  }
  if (failureKind == 'harness_preparation_in_progress' ||
      raw.contains('harness preparation is already running') ||
      raw.contains('harness preparation in progress')) {
    return text('installBusy');
  }
  if (raw.contains('requires an anthropic-compatible') ||
      raw.contains('requires an openai responses-compatible') ||
      raw.contains('does not support the openai responses')) {
    return text('incompatible');
  }
  if (raw.contains('unknown variant namespace') && raw.contains('tools')) {
    return text('unsupportedTools');
  }
  if (raw.contains('not installed') ||
      raw.contains('executable not found') ||
      raw.contains('command not found')) {
    return text('notInstalled');
  }
  // Unknown server errors are untrusted; never render the raw payload.
  return fallback ?? text('unknown');
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

class AgentPackageVersion {
  const AgentPackageVersion({
    this.installedVersion,
    this.latestVersion,
    this.targetVersion,
    this.checkedAt,
    this.error,
    this.pinned = false,
    this.updateAvailable = false,
  });

  final String? installedVersion;
  final String? latestVersion;
  final String? targetVersion;
  final int? checkedAt;
  final String? error;
  final bool pinned;
  final bool updateAvailable;

  factory AgentPackageVersion.fromMap(Map<dynamic, dynamic> map) =>
      AgentPackageVersion(
        installedVersion: _stringOrNull(map['installedVersion']),
        latestVersion: _stringOrNull(map['latestVersion']),
        targetVersion: _stringOrNull(map['targetVersion']),
        checkedAt: _intOrNull(map['checkedAt']),
        error: _stringOrNull(map['error']),
        pinned: map['pinned'] == true,
        updateAvailable: map['updateAvailable'] == true,
      );
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
    this.installed,
    this.status = 'unchecked',
    this.lastCheckError,
    this.lastCheckLatencyMs,
    this.lastCheckAt,
    this.capabilities = const <String, dynamic>{},
    this.discoveryCommand,
    this.managedAdapter = false,
    this.uninstallable = false,
    this.packageVersion,
    bool? configurationAvailable,
  }) : configurationAvailable = configurationAvailable ?? id != 'na-cloud';

  final String id;
  final String name;
  final String command;
  final String description;
  final List<String> arguments;
  final Map<String, String> environment;
  final bool enabled;
  final bool builtIn;
  final String source;
  final bool? installed;
  final String status;
  final String? lastCheckError;
  final int? lastCheckLatencyMs;
  final int? lastCheckAt;
  final Map<String, dynamic> capabilities;
  final String? discoveryCommand;
  final bool managedAdapter;
  final bool uninstallable;
  final AgentPackageVersion? packageVersion;

  /// Native build policy owns whether this Agent exposes a configuration page.
  /// Na is account managed unless the native develop flavor opts in.
  final bool configurationAvailable;

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
      installed: _boolOrNull(map['installed']),
      status: _stringOrNull(map['status']) ?? 'unchecked',
      lastCheckError: _stringOrNull(map['lastCheckError']),
      lastCheckLatencyMs: _intOrNull(map['lastCheckLatencyMs']),
      lastCheckAt: _intOrNull(map['lastCheckAt']),
      capabilities:
          _normalizeMap(map['capabilities']) ?? const <String, dynamic>{},
      discoveryCommand: _stringOrNull(map['discoveryCommand']),
      managedAdapter: map['managedAdapter'] == true,
      uninstallable: map['uninstallable'] == true,
      packageVersion: map['packageVersion'] is Map
          ? AgentPackageVersion.fromMap(map['packageVersion'] as Map)
          : null,
      configurationAvailable: map['configurationAvailable'] is bool
          ? map['configurationAvailable'] as bool
          : null,
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
    required this.agents,
    this.distributionId,
    this.retiredAgentIds = const <String>{},
  });

  final List<AcpAgentProfile> agents;
  final String? distributionId;
  final Set<String> retiredAgentIds;

  bool isRetired(String? agentId) => retiredAgentIds.contains(agentId?.trim());

  factory AcpAgentCatalog.fromMap(Map<dynamic, dynamic>? map) {
    final source = map ?? const <dynamic, dynamic>{};
    final rawAgents = source['agents'];
    final retiredAgentIds = (source['retiredAgentIds'] as List? ?? const [])
        .whereType<String>()
        .map((id) => id.trim())
        .where((id) => id.isNotEmpty)
        .toSet();
    final agents = <AcpAgentProfile>[];
    final seenIdentities = <String>{};
    if (rawAgents is List) {
      for (final rawAgent in rawAgents.whereType<Map>()) {
        final agent = AcpAgentProfile.fromMap(rawAgent);
        if (agent.id.isEmpty || retiredAgentIds.contains(agent.id)) continue;
        if (seenIdentities.add(_agentCatalogIdentity(agent))) {
          agents.add(agent);
        }
      }
    }
    return AcpAgentCatalog(
      agents: agents,
      retiredAgentIds: retiredAgentIds,
      distributionId: _stringOrNull(source['distributionId']),
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
  });

  final bool remoteEnabled;
  final String remoteBridgeUrl;
  final String remoteBridgeToken;
  final String remoteCwd;
  final bool remoteConfigured;

  factory CodexRemoteBridgeConfig.fromMap(Map<dynamic, dynamic>? map) {
    final source = map ?? const <dynamic, dynamic>{};
    return CodexRemoteBridgeConfig(
      remoteEnabled: source['remoteEnabled'] == true,
      remoteBridgeUrl: _stringOrNull(source['remoteBridgeUrl']) ?? '',
      remoteBridgeToken: _stringOrNull(source['remoteBridgeToken']) ?? '',
      remoteCwd: _stringOrNull(source['remoteCwd']) ?? '',
      remoteConfigured: source['remoteConfigured'] == true,
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
    this.workspaceOwner,
    this.error,
    this.entries = const <CodexRemoteDirectoryEntry>[],
  });

  final bool ok;
  final String path;
  final String? parent;
  final String? cwd;
  final String? home;
  final String? workspaceOwner;
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
      workspaceOwner: _stringOrNull(source['workspaceOwner']),
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

enum AgentPackageOperation { install, uninstall }

typedef _AgentPackageTask = ({
  AgentPackageOperation operation,
  String? distributionId,
  bool force,
  Future<Map<String, dynamic>> future,
});

/// Response attributed to one captured Na account and service endpoint.
class NaPersonalResponse {
  const NaPersonalResponse({required this.owner, required this.data});
  final String owner;
  final Map<String, dynamic> data;

  factory NaPersonalResponse.fromMap(Map<String, dynamic> value) {
    final owner = value['owner'];
    if (owner is! String || owner.isEmpty) {
      throw StateError('Na 返回了无效的连接归属。');
    }
    final raw = value['data'];
    if (raw is! Map) throw StateError('Na 返回了无效的资源。');
    return NaPersonalResponse(
      owner: owner,
      data: Map<String, dynamic>.from(raw),
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
  static final Map<String, _AgentPackageTask> _agentPackageTasks = {};
  static Future<void>? _packageOperationQueue;
  static final Set<String> _queuedAgentIds = <String>{};
  static final StreamController<Set<String>> _agentPreparationController =
      StreamController<Set<String>>.broadcast();
  static final StreamController<Map<String, AgentPackageOperation>>
  _agentPackageOperationController =
      StreamController<Map<String, AgentPackageOperation>>.broadcast();
  static StreamSubscription<dynamic>? _nativeEventSubscription;

  static Stream<Map<String, dynamic>> get events {
    _ensureEventSubscription();
    return _eventController.stream;
  }

  /// Package operations belong to this service so leaving a settings page
  /// cannot interrupt them or start the same operation a second time.
  static Map<String, AgentPackageOperation> get agentPackageOperations =>
      Map<String, AgentPackageOperation>.unmodifiable(
        _agentPackageTasks.map((id, task) => MapEntry(id, task.operation)),
      );

  static Stream<Map<String, AgentPackageOperation>>
  get agentPackageOperationChanges => _agentPackageOperationController.stream;

  /// Compatibility projection for callers interested only in installation.
  static Set<String> get preparingAgentIds => Set<String>.unmodifiable(
    _agentPackageTasks.entries
        .where(
          (entry) => entry.value.operation == AgentPackageOperation.install,
        )
        .map((entry) => entry.key),
  );

  static Set<String> get queuedAgentIds =>
      Set<String>.unmodifiable(_queuedAgentIds);

  static Stream<Set<String>> get agentPreparationChanges =>
      _agentPreparationController.stream;

  static String _requiredAgentId(String agentId) {
    final normalized = agentId.trim();
    if (normalized.isEmpty)
      throw ArgumentError('agent_identity_required: agentId');
    return normalized;
  }

  /// Bounded Na resource bridge. Only status may discover a fresh owner;
  /// native validates every operation against that captured connection.
  static Future<NaPersonalResponse> requestNaPersonal({
    required String operation,
    String? owner,
    String? id,
    Map<String, dynamic> payload = const {},
  }) async {
    if (operation.trim().isEmpty ||
        (operation != 'status' && (owner == null || owner.isEmpty))) {
      throw ArgumentError(
        'Na resource operation requires its connection owner',
      );
    }
    final result = await _invokeMap('config/na/personal', {
      'agentId': 'na-cloud',
      'operation': operation,
      if (owner != null) 'owner': owner,
      if (id != null) 'id': id,
      'payload': payload,
    });
    final response = NaPersonalResponse.fromMap(result);
    if (owner != null && response.owner != owner) {
      throw StateError('Na 连接已更改，请重新载入连接。');
    }
    return response;
  }

  /// Pure IANA calendar conversion; never connects or starts an Agent turn.
  static Future<Map<String, dynamic>> requestNaLocalTime({
    String? localTime,
    String? at,
    required String timezone,
  }) => _invokeMap('config/na/local-time', {
    'agentId': 'na-cloud',
    if (localTime != null) 'localTime': localTime,
    if (at != null) 'at': at,
    'timezone': timezone,
  });

  /// Local device delivery preferences; private connector/project keys never
  /// enter this API. Native captures the same Na account/endpoint owner.
  static Future<NaPersonalResponse> requestNaNotifications({
    String? owner,
    bool? enabled,
  }) async {
    if (enabled != null && (owner == null || owner.isEmpty)) {
      throw ArgumentError('Na notification changes require their connection owner');
    }
    final response = NaPersonalResponse.fromMap(await _invokeMap('config/na/notifications', {
      'agentId': 'na-cloud',
      'operation': enabled == null ? 'read' : 'write',
      if (owner != null) 'owner': owner,
      if (enabled != null) 'enabled': enabled,
    }));
    if (owner != null && response.owner != owner) {
      throw StateError('Na 连接已更改，请重新载入通知设置。');
    }
    return response;
  }

  static Future<AgentRuntimeStatus> status({required String agentId}) async {
    final result = await _invokeMap('status', {
      'agentId': _requiredAgentId(agentId),
    });
    return AgentRuntimeStatus.fromMap(result);
  }

  static Future<AgentRuntimeStatus> disconnect({
    required String agentId,
  }) async {
    final result = await _invokeMap('disconnect', {
      'agentId': _requiredAgentId(agentId),
    });
    return AgentRuntimeStatus.fromMap(result);
  }

  /// Initializes the explicitly owned ACP transport without creating a session.
  static Future<Map<String, dynamic>> initialize({required String agentId}) {
    return _invokeMap('initialize', {'agentId': _requiredAgentId(agentId)});
  }

  static Future<AcpAgentCatalog> listAgents() async {
    return AcpAgentCatalog.fromMap(await _invokeMap('agent/list'));
  }

  static Future<AcpAgentCatalog> refreshAgents() async {
    return AcpAgentCatalog.fromMap(await _invokeMap('agent/refresh'));
  }

  static Future<AcpAgentCatalog> checkAgentUpdates() async {
    return AcpAgentCatalog.fromMap(await _invokeMap('agent/checkUpdates'));
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

  static Future<Map<String, dynamic>> prepareAgent(
    String agentId, {
    bool force = false,
    String? distributionId,
  }) {
    return _invokeMap('agent/prepare', {
      'agentId': agentId.trim(),
      if (force) 'force': true,
      if (distributionId != null) 'distributionId': distributionId,
    });
  }

  static Future<Map<String, dynamic>> prepareAgentInBackground(
    String agentId, {
    bool force = false,
    String? distributionId,
  }) => _queueAgentPackageOperation(
    agentId,
    operation: AgentPackageOperation.install,
    force: force,
    distributionId: distributionId,
  );

  static Future<Map<String, dynamic>> uninstallAgent(
    String agentId, {
    required String distributionId,
  }) {
    final normalizedId = agentId.trim();
    final normalizedDistribution = distributionId.trim();
    if (normalizedId.isEmpty || normalizedDistribution.isEmpty) {
      return Future.error(
        ArgumentError('Uninstall requires an agent and terminal distribution.'),
      );
    }
    return _invokeMap('agent/uninstall', {
      'agentId': normalizedId,
      'distributionId': normalizedDistribution,
    });
  }

  static Future<Map<String, dynamic>> uninstallAgentInBackground(
    String agentId, {
    required String distributionId,
  }) => _queueAgentPackageOperation(
    agentId,
    operation: AgentPackageOperation.uninstall,
    distributionId: distributionId,
  );

  static Future<Map<String, dynamic>> _queueAgentPackageOperation(
    String agentId, {
    required AgentPackageOperation operation,
    required String? distributionId,
    bool force = false,
  }) {
    final normalizedId = agentId.trim();
    final normalizedDistribution = distributionId?.trim();
    if (normalizedId.isEmpty || normalizedDistribution == '') {
      return Future.error(
        ArgumentError('Package operations require non-empty identities.'),
      );
    }
    final existing = _agentPackageTasks[normalizedId];
    if (existing != null) {
      if (existing.operation == operation &&
          existing.distributionId == normalizedDistribution &&
          existing.force == force) {
        return existing.future;
      }
      return Future.error(
        StateError(
          'Agent package operation is already running for $normalizedId.',
        ),
      );
    }

    _queuedAgentIds.add(normalizedId);
    // Installs and uninstalls share the same npm prefix and must not overlap.
    Future<Map<String, dynamic>> run() {
      _queuedAgentIds.remove(normalizedId);
      _emitAgentPackageOperationState();
      return switch (operation) {
        AgentPackageOperation.install => prepareAgent(
          normalizedId,
          force: force,
          distributionId: normalizedDistribution,
        ),
        AgentPackageOperation.uninstall => uninstallAgent(
          normalizedId,
          distributionId: normalizedDistribution!,
        ),
      };
    }

    final task =
        _packageOperationQueue?.then((_) => run()) ?? Future.microtask(run);
    _packageOperationQueue = task.then<void>(
      (_) {},
      onError: (Object _, StackTrace _) {},
    );
    _agentPackageTasks[normalizedId] = (
      operation: operation,
      distributionId: normalizedDistribution,
      force: force,
      future: task,
    );
    _emitAgentPackageOperationState();
    unawaited(
      task.then<void>(
        (_) => _finishAgentPackageOperation(normalizedId, task),
        onError: (Object _, StackTrace _) {
          _finishAgentPackageOperation(normalizedId, task);
        },
      ),
    );
    return task;
  }

  static void _finishAgentPackageOperation(
    String agentId,
    Future<Map<String, dynamic>> task,
  ) {
    if (!identical(_agentPackageTasks[agentId]?.future, task)) return;
    _agentPackageTasks.remove(agentId);
    _queuedAgentIds.remove(agentId);
    if (_agentPackageTasks.isEmpty) _packageOperationQueue = null;
    _emitAgentPackageOperationState();
  }

  static void _emitAgentPackageOperationState() {
    _agentPackageOperationController.add(agentPackageOperations);
    _agentPreparationController.add(preparingAgentIds);
  }

  static Future<Map<String, dynamic>> readAgentConfig(String agentId) {
    return _invokeMap('agent/config/read', {'agentId': agentId.trim()});
  }

  static Future<Map<String, dynamic>> writeAgentConfig(
    String agentId, {
    String? connectionMode,
    String? baseUrl,
    String? model,
    String? apiKey,
    String? content,
    int? expectedRevision,
  }) {
    return _invokeMap('agent/config/write', {
      'agentId': agentId.trim(),
      if (connectionMode != null) 'connectionMode': connectionMode,
      if (baseUrl != null) 'baseUrl': baseUrl,
      if (model != null) 'model': model,
      if (apiKey != null) 'apiKey': apiKey,
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
    String? agentId,
    String? cwd,
    String? model,
    String? effort,
    String? collaborationMode,
    String? conversationMode,
    List<String> additionalDirectories = const <String>[],
  }) {
    return _invokeMap('session/new', {
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
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
    String? agentId,
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
      agentId: agentId,
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
    String? historyCursor,
  }) {
    return _invokeMap('session/load', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
      'includeHistory': includeHistory,
      if (historyCursor != null) 'historyCursor': historyCursor,
      if (refreshConfig) 'refreshConfig': true,
      if (conversationMode != null && conversationMode.trim().isNotEmpty)
        'conversationMode': conversationMode.trim(),
    });
  }

  static Future<void> setNaHistorySyncEnabled(bool enabled) async {
    await _invokeMap('config/na/historySync', {
      'agentId': 'na-cloud',
      'enabled': enabled,
    });
  }

  static Future<Map<String, dynamic>> listSessions({
    required String agentId,
    int? limit,
    String? cursor,
    String? cwd,
    List<String> additionalDirectories = const <String>[],
  }) {
    return _invokeMap('session/list', {
      'agentId': _requiredAgentId(agentId),
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
    String? agentId,
    String? cwd,
    String? conversationMode,
    List<String> additionalDirectories = const <String>[],
  }) {
    return _invokeMap('session/fork', {
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null) 'agentId': _requiredAgentId(agentId),
      if (cwd != null && cwd.trim().isNotEmpty) 'cwd': cwd.trim(),
      if (conversationMode != null && conversationMode.trim().isNotEmpty)
        'conversationMode': conversationMode.trim(),
      if (additionalDirectories.isNotEmpty)
        'additionalDirectories': additionalDirectories,
    });
  }

  static Future<Map<String, dynamic>> listLoadedSessions({
    required String agentId,
  }) {
    return _invokeMap('session/list', {'agentId': _requiredAgentId(agentId)});
  }

  /// Close releases the live ACP Session without archiving or deleting the
  /// user's conversation history. Persistence remains owned by the host DB.
  static Future<Map<String, dynamic>> closeSession({
    String? sessionId,
    int? conversationId,
    String? agentId,
  }) {
    return _invokeMap('session/close', {
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null && agentId.trim().isNotEmpty)
        'agentId': agentId.trim(),
    });
  }

  /// Deletes the ACP-side session while retaining OmniBot's local
  /// conversation and messages. The shared runtime returns historyPreserved.
  static Future<Map<String, dynamic>> deleteSession({
    String? sessionId,
    int? conversationId,
    String? agentId,
  }) {
    return _invokeMap('session/delete', {
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null) 'agentId': _requiredAgentId(agentId),
    });
  }

  static Future<Map<String, dynamic>> setSessionMode({
    String? sessionId,
    int? conversationId,
    String? agentId,
    required String modeId,
  }) {
    return _invokeMap('session/set_mode', {
      if (sessionId != null && sessionId.trim().isNotEmpty)
        'sessionId': sessionId.trim(),
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null) 'agentId': _requiredAgentId(agentId),
      'modeId': modeId.trim(),
    });
  }

  static Future<Map<String, dynamic>> authenticateAgent({
    required String agentId,
    required String methodId,
    Map<String, dynamic>? meta,
  }) {
    return _invokeMap('authenticate', {
      'agentId': _requiredAgentId(agentId),
      'methodId': methodId.trim(),
      if (meta != null) '_meta': meta,
    });
  }

  static Future<Map<String, dynamic>> logoutAgent({
    required String agentId,
    Map<String, dynamic>? meta,
  }) {
    return _invokeMap('logout', {
      'agentId': _requiredAgentId(agentId),
      if (meta != null) '_meta': meta,
    });
  }

  static Future<Map<String, dynamic>> listAgentProviders({
    required String agentId,
    Map<String, dynamic>? meta,
  }) {
    return _invokeMap('providers/list', {
      'agentId': _requiredAgentId(agentId),
      if (meta != null) '_meta': meta,
    });
  }

  static Future<Map<String, dynamic>> setAgentProvider(
    Map<String, dynamic> params, {
    required String agentId,
  }) {
    return _invokeMap('providers/set', {
      ...params,
      'agentId': _requiredAgentId(agentId),
    });
  }

  static Future<Map<String, dynamic>> disableAgentProvider(
    Map<String, dynamic> params, {
    required String agentId,
  }) {
    return _invokeMap('providers/disable', {
      ...params,
      'agentId': _requiredAgentId(agentId),
    });
  }

  /// Calls an ACP implementation extension without introducing a second
  /// Harness transport. ACP reserves the underscore namespace for extension
  /// methods; unknown core-looking method names are rejected at the host
  /// boundary so a typo cannot silently become a legacy RPC.
  static Future<Map<String, dynamic>> callAcpExtension({
    required String method,
    required String agentId,
    Map<String, dynamic> params = const <String, dynamic>{},
  }) {
    return _invokeMap(_validateAcpExtensionMethod(method), {
      'agentId': _requiredAgentId(agentId),
      'params': params,
    });
  }

  /// Sends a client-to-agent ACP extension notification. Notifications do
  /// not produce an Agent response, but using this shared bridge keeps the
  /// extension on the same transport as every other ACP operation.
  static Future<Map<String, dynamic>> notifyAcpExtension({
    required String method,
    required String agentId,
    Object? params,
  }) {
    return _invokeMap('notifyAcpExtension', {
      'agentId': _requiredAgentId(agentId),
      'method': _validateAcpExtensionMethod(method),
      'params': params,
    });
  }

  /// Preserves non-object extension results such as arrays and scalar values.
  static Future<dynamic> callAcpExtensionValue({
    required String method,
    required String agentId,
    Object? params,
  }) {
    return _invokeValue(_validateAcpExtensionMethod(method), {
      'agentId': _requiredAgentId(agentId),
      'params': params,
    });
  }

  static Future<Map<String, dynamic>> archiveSession({
    String? sessionId,
    int? conversationId,
    String? agentId,
  }) {
    return _invokeMap('session/archive', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null) 'agentId': _requiredAgentId(agentId),
    });
  }

  static Future<Map<String, dynamic>> unarchiveSession({
    String? sessionId,
    int? conversationId,
    String? agentId,
  }) {
    return _invokeMap('session/unarchive', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null) 'agentId': _requiredAgentId(agentId),
    });
  }

  static Future<Map<String, dynamic>> setSessionName({
    String? sessionId,
    int? conversationId,
    String? agentId,
    required String name,
  }) {
    return _invokeMap('session/name/set', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null) 'agentId': _requiredAgentId(agentId),
      'name': name,
    });
  }

  static Future<Map<String, dynamic>> promptSession({
    String? sessionId,
    int? conversationId,
    String? requestId,
    String? userMessageId,
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
      if (userMessageId != null && userMessageId.trim().isNotEmpty)
        'userMessageId': userMessageId.trim(),
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
    }).then((response) {
      final failureKind = response['failureKind'];
      if (failureKind is String && response['error'] is String) {
        return <String, dynamic>{
          ...response,
          'error': formatAgentRuntimeErrorForUser(
            PlatformException(
              code: 'AGENT_RUNTIME_CALL_FAILED',
              message: response['error'] as String,
              details: <String, dynamic>{'failureKind': failureKind},
            ),
          ),
        };
      }
      return response;
    });
  }

  static Future<Map<String, dynamic>> cancelPrompt({
    String? sessionId,
    int? conversationId,
    String? agentId,
    String? promptId,
    String? runId,
  }) {
    return _invokeMap('session/cancel', {
      if (sessionId != null) 'sessionId': sessionId,
      if (conversationId != null) 'conversationId': conversationId,
      if (agentId != null) 'agentId': _requiredAgentId(agentId),
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
    String? agentId,
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
      if (agentId != null) 'agentId': _requiredAgentId(agentId),
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

  static Future<Map<String, dynamic>> listModels({required String agentId}) {
    return _invokeMap('model/list', {'agentId': _requiredAgentId(agentId)});
  }

  static Future<Map<String, dynamic>> listCollaborationModes({
    required String agentId,
  }) {
    return _invokeMap('collaborationMode/list', {
      'agentId': _requiredAgentId(agentId),
    });
  }

  static Future<Map<String, dynamic>> readConfig({required String agentId}) {
    return _invokeMap('config/read', {'agentId': _requiredAgentId(agentId)});
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
    String? agentId,
    String? workspaceOwner,
    String remoteBridgeUrl = '',
    String remoteBridgeToken = '',
    String remoteCwd = '',
    String? path,
  }) async {
    final result = await _invokeMap('config/remote/fs/list', {
      if (agentId != null) 'agentId': agentId,
      if (workspaceOwner != null) 'workspaceOwner': workspaceOwner,
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
    String? agentId,
    String? sessionId,
    String? workspaceOwner,
    String remoteBridgeUrl = '',
    String remoteBridgeToken = '',
    String remoteCwd = '',
    required String path,
  }) async {
    final result = await _invokeMap('config/remote/fs/read', {
      if (agentId != null) 'agentId': agentId,
      if (sessionId != null) 'sessionId': sessionId,
      if (workspaceOwner != null) 'workspaceOwner': workspaceOwner,
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
    String? agentId,
    String? sessionId,
    String? workspaceOwner,
    String remoteBridgeUrl = '',
    String remoteBridgeToken = '',
    String remoteCwd = '',
    required String path,
    required String content,
  }) {
    return _invokeMap('config/remote/fs/write', {
      if (agentId != null) 'agentId': agentId,
      if (sessionId != null) 'sessionId': sessionId,
      if (workspaceOwner != null) 'workspaceOwner': workspaceOwner,
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
    String? agentId,
    String? workspaceOwner,
    String remoteBridgeUrl = '',
    String remoteBridgeToken = '',
    String remoteCwd = '',
    required String path,
    bool recursive = false,
  }) {
    return _invokeMap('config/remote/fs/delete', {
      if (agentId != null) 'agentId': agentId,
      if (workspaceOwner != null) 'workspaceOwner': workspaceOwner,
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
    String? agentId,
    String? workspaceOwner,
    String remoteBridgeUrl = '',
    String remoteBridgeToken = '',
    String remoteCwd = '',
    required String path,
    required String destinationPath,
  }) {
    return _invokeMap('config/remote/fs/move', {
      if (agentId != null) 'agentId': agentId,
      if (workspaceOwner != null) 'workspaceOwner': workspaceOwner,
      if (remoteBridgeUrl.trim().isNotEmpty)
        'remoteBridgeUrl': remoteBridgeUrl.trim(),
      if (remoteBridgeToken.trim().isNotEmpty)
        'remoteBridgeToken': remoteBridgeToken.trim(),
      if (remoteCwd.trim().isNotEmpty) 'remoteCwd': remoteCwd.trim(),
      'path': path.trim(),
      'destinationPath': destinationPath.trim(),
    });
  }

  static Future<Map<String, dynamic>> readAccount({required String agentId}) {
    return _invokeMap('account/read', {'agentId': _requiredAgentId(agentId)});
  }

  static Future<Map<String, dynamic>> startLogin({
    required String agentId,
    CodexLoginType type = CodexLoginType.chatgpt,
  }) {
    return _invokeMap('account/login/start', {
      'agentId': _requiredAgentId(agentId),
      'type': type.payloadValue,
    });
  }

  static Future<Map<String, dynamic>> cancelLogin({
    required String agentId,
    String? loginId,
  }) {
    return _invokeMap('account/login/cancel', {
      'agentId': _requiredAgentId(agentId),
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
