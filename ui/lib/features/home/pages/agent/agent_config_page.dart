import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

class AgentConfigPage extends StatefulWidget {
  const AgentConfigPage({super.key, required this.agentId});

  final String agentId;

  @override
  State<AgentConfigPage> createState() => _AgentConfigPageState();
}

class _AgentConfigPageState extends State<AgentConfigPage> {
  late final TextEditingController _baseUrlController;
  late final TextEditingController _modelController;
  late final TextEditingController _apiKeyController;
  late final TextEditingController _contentController;
  late final TextEditingController _commandController;
  late final TextEditingController _argumentsController;
  late final TextEditingController _environmentController;

  AcpAgentProfile? _agent;
  String _kind = '';
  String _configFormat = '';
  String _configPath = '';
  String _authPath = '';
  bool _loading = true;
  bool _saving = false;
  bool _obscureApiKey = true;
  bool _hasStoredApiKey = false;
  bool _hasConfig = false;
  int _configByteCount = 0;
  bool _enabled = true;
  bool _changed = false;
  String? _error;

  bool get _english =>
      Localizations.localeOf(context).languageCode.toLowerCase() == 'en';

  String _text(String zh, String en) => _english ? en : zh;

  @override
  void initState() {
    super.initState();
    _baseUrlController = TextEditingController();
    _modelController = TextEditingController();
    _apiKeyController = TextEditingController();
    _contentController = TextEditingController();
    _commandController = TextEditingController();
    _argumentsController = TextEditingController();
    _environmentController = TextEditingController();
    unawaited(_load());
  }

  @override
  void dispose() {
    _baseUrlController.dispose();
    _modelController.dispose();
    _apiKeyController.dispose();
    _contentController.dispose();
    _commandController.dispose();
    _argumentsController.dispose();
    _environmentController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final catalog = await AgentRuntimeService.listAgents();
      final agent = catalog.agents
          .where((candidate) => candidate.id == widget.agentId)
          .firstOrNull;
      if (agent == null) {
        throw StateError('Unknown ACP Agent: ${widget.agentId}');
      }
      if (!mounted) return;
      _syncAgent(agent);
      setState(() {
        _agent = agent;
        _kind = agent.builtIn
            ? _fallbackBuiltInConfigKind(agent.id)
            : 'profile';
      });
      Map<String, dynamic> payload = const {};
      if (agent.builtIn) {
        payload = await AgentRuntimeService.readAgentConfig(agent.id);
      }
      if (!mounted) return;
      _syncPayload(payload);
      setState(() {
        _agent = agent;
        _kind = agent.builtIn ? (payload['kind']?.toString() ?? '') : 'profile';
        _loading = false;
        _error = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = error.toString();
      });
    }
  }

  void _syncAgent(AcpAgentProfile agent) {
    _setText(_commandController, agent.command);
    _setText(_argumentsController, agent.arguments.join('\n'));
    _setText(
      _environmentController,
      agent.environment.entries
          .map((entry) => '${entry.key}=${entry.value}')
          .join('\n'),
    );
    _enabled = agent.enabled;
  }

  String _fallbackBuiltInConfigKind(String agentId) {
    return switch (agentId) {
      'codex-acp' => 'codex',
      'claude-code-acp' || 'opencode-acp' => 'replace-only',
      _ => '',
    };
  }

  void _syncPayload(Map<String, dynamic> payload) {
    _setText(_baseUrlController, payload['baseUrl']?.toString() ?? '');
    _setText(_modelController, payload['model']?.toString() ?? '');
    // All built-in Agent secrets/config files are replace-only. Even if a
    // future native regression adds a value, this page never restores it.
    _setText(_apiKeyController, '');
    _setText(_contentController, '');
    _hasStoredApiKey = payload['hasApiKey'] == true;
    _hasConfig = payload['hasConfig'] == true;
    _configByteCount = switch (payload['byteCount']) {
      final int value when value >= 0 => value,
      final num value when value >= 0 => value.toInt(),
      _ => 0,
    };
    _configFormat = payload['format']?.toString() ?? '';
    _configPath =
        payload['configPath']?.toString() ??
        payload['displayPath']?.toString() ??
        '';
    _authPath = payload['authPath']?.toString() ?? '';
  }

  void _setText(TextEditingController controller, String value) {
    controller.value = TextEditingValue(
      text: value,
      selection: TextSelection.collapsed(offset: value.length),
    );
  }

  Future<void> _save() async {
    if (_saving || _agent == null) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      switch (_kind) {
        case 'codex':
          final baseUrl = _baseUrlController.text.trim();
          final model = _modelController.text.trim();
          final apiKey = _apiKeyController.text.trim();
          if (baseUrl.isEmpty ||
              model.isEmpty ||
              (apiKey.isEmpty && !_hasStoredApiKey)) {
            throw ArgumentError(
              _text(
                'Base URL、模型 ID 和 API Key 均不能为空。',
                'Base URL, model ID, and API Key are required.',
              ),
            );
          }
          final payload = await AgentRuntimeService.writeAgentConfig(
            _agent!.id,
            baseUrl: baseUrl,
            model: model,
            apiKey: apiKey.isEmpty ? null : apiKey,
          );
          if (!mounted) return;
          _syncPayload(payload);
          break;
        case 'replace-only':
          final content = _contentController.text;
          if (content.trim().isEmpty) {
            throw ArgumentError(
              _text(
                '替换内容不能为空；未写入任何文件。',
                'Replacement content cannot be empty; no file was changed.',
              ),
            );
          }
          if (_configFormat == 'json') {
            final decoded = jsonDecode(content);
            if (decoded is! Map) {
              throw const FormatException(
                'settings.json must contain a JSON object.',
              );
            }
          }
          final payload = await AgentRuntimeService.writeAgentConfig(
            _agent!.id,
            content: content,
          );
          if (!mounted) return;
          _syncPayload(payload);
          break;
        case 'profile':
          final command = _commandController.text.trim();
          if (command.isEmpty) {
            throw ArgumentError(
              _text('启动命令不能为空。', 'Launch command is required.'),
            );
          }
          final catalog = await AgentRuntimeService.saveAgent(
            AcpAgentProfile(
              id: _agent!.id,
              name: _agent!.name,
              description: _agent!.description,
              command: command,
              arguments: _nonEmptyLines(_argumentsController.text),
              environment: _parseEnvironment(_environmentController.text),
              enabled: _enabled,
              builtIn: false,
              source: _agent!.source,
            ),
          );
          if (!mounted) return;
          final saved = catalog.agents
              .where((candidate) => candidate.id == _agent!.id)
              .firstOrNull;
          if (saved != null) {
            _agent = saved;
            _syncAgent(saved);
          }
          break;
        default:
          throw UnsupportedError(
            _text(
              '该 Agent 没有可编辑的本地配置。',
              'This Agent has no editable local configuration.',
            ),
          );
      }
      if (!mounted) return;
      setState(() => _changed = true);
      showToast(
        _text('配置已保存。', 'Configuration saved.'),
        type: ToastType.success,
      );
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error.toString());
      showToast(error.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _clearBuiltInConfig() async {
    final agent = _agent;
    if (agent == null || !agent.builtIn || _saving) return;
    final continued = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(
          _text('清除本机 Agent 配置？', 'Clear local Agent configuration?'),
        ),
        content: Text(
          _text(
            '这会删除 ${agent.name} 的本机配置，并退出当前本地 Agent 会话。服务端账号和云端数据不会受影响。',
            'This deletes the local ${agent.name} configuration and exits the current local Agent session. Your server account and cloud data are not affected.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(_text('取消', 'Cancel')),
          ),
          FilledButton(
            key: const Key('agent-config-clear-continue'),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(_text('继续', 'Continue')),
          ),
        ],
      ),
    );
    if (continued != true || !mounted) return;
    final clearTarget = _configPath.isEmpty
        ? _text('该 Agent 的固定本机配置文件', 'this Agent’s fixed local files')
        : '$_configPath${_authPath.isEmpty ? '' : _text(' 和 $_authPath', ' and $_authPath')}';
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(_text('最后确认', 'Final confirmation')),
        content: Text(
          _text(
            '确认永久清除 $clearTarget。已有秘密不会显示，清除后需要重新配置才能启动此本地 Agent。',
            'Permanently clear $clearTarget. Existing secrets are never shown. You must configure this local Agent again before it can start.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(_text('返回', 'Back')),
          ),
          FilledButton(
            key: const Key('agent-config-clear-confirm'),
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(context).colorScheme.error,
            ),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(_text('确认清除', 'Clear now')),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final payload = await AgentRuntimeService.clearAgentConfig(agent.id);
      if (!mounted) return;
      _syncPayload(payload);
      setState(() => _changed = true);
      showToast(
        _text('本机 Agent 配置已清除。', 'Local Agent configuration cleared.'),
        type: ToastType.success,
      );
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error.toString());
      showToast(error.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _deleteCustomAgent() async {
    final agent = _agent;
    if (agent == null || agent.builtIn || _saving) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(_text('删除 Agent？', 'Delete Agent?')),
        content: Text(
          _text(
            '将删除“${agent.name}”的配置，不会卸载对应命令。',
            'This removes “${agent.name}” without uninstalling its command.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(_text('取消', 'Cancel')),
          ),
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(_text('删除', 'Delete')),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _saving = true);
    try {
      await AgentRuntimeService.deleteAgent(agent.id);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = error.toString();
      });
      showToast(error.toString(), type: ToastType.error);
    }
  }

  void _close() {
    Navigator.of(context).pop(_changed);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final background = context.isDarkTheme
        ? palette.pageBackground
        : AppColors.background;
    final card = context.isDarkTheme ? palette.surfacePrimary : Colors.white;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _close();
      },
      child: Scaffold(
        backgroundColor: background,
        appBar: CommonAppBar(
          title: _agent?.name ?? _text('Agent 配置', 'Agent configuration'),
          primary: true,
          onBackPressed: _close,
          actions: [
            if (_agent?.builtIn == false)
              IconButton(
                tooltip: _text('删除 Agent', 'Delete Agent'),
                onPressed: _saving ? null : _deleteCustomAgent,
                icon: const Icon(Icons.delete_outline_rounded),
              ),
          ],
        ),
        body: SafeArea(
          top: false,
          bottom: false,
          child: _loading
              ? const Center(child: CircularProgressIndicator())
              : _error != null && _agent == null
              ? _ErrorState(error: _error!, onRetry: _load)
              : ListView(
                  padding: edgeToEdgeScrollPadding(
                    context,
                    const EdgeInsets.fromLTRB(18, 12, 18, 28),
                  ),
                  children: [
                    SettingsSectionTitle(
                      label: _pageTitle,
                      subtitle: _pageSubtitle,
                    ),
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: card,
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(color: palette.borderSubtle),
                      ),
                      child: _buildEditor(),
                    ),
                    if (_error != null) ...[
                      const SizedBox(height: 12),
                      Text(
                        _error!,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                          fontSize: 12,
                        ),
                      ),
                    ],
                    if (_kind.isNotEmpty) ...[
                      const SizedBox(height: 18),
                      FilledButton.icon(
                        key: const Key('agent-config-save'),
                        onPressed: _saving ? null : _save,
                        icon: _saving
                            ? const SizedBox(
                                width: 16,
                                height: 16,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Icon(Icons.save_outlined),
                        label: Text(_saveButtonLabel),
                      ),
                      if (_agent?.builtIn == true &&
                          (_kind == 'codex' || _kind == 'replace-only')) ...[
                        const SizedBox(height: 10),
                        OutlinedButton.icon(
                          key: const Key('agent-config-clear'),
                          onPressed: _saving ? null : _clearBuiltInConfig,
                          icon: const Icon(Icons.delete_outline_rounded),
                          label: Text(
                            _text('清除本机配置', 'Clear local configuration'),
                          ),
                        ),
                      ],
                    ],
                  ],
                ),
        ),
      ),
    );
  }

  String get _pageTitle {
    return switch (_kind) {
      'codex' => _text('Codex API 配置', 'Codex API configuration'),
      'replace-only' => _text(
        '${_agent?.name ?? 'Agent'} 本机配置',
        '${_agent?.name ?? 'Agent'} local configuration',
      ),
      'profile' => _text('ACP 启动配置', 'ACP launch configuration'),
      _ => _text('Agent 配置', 'Agent configuration'),
    };
  }

  String get _saveButtonLabel {
    return switch (_kind) {
      'replace-only' => _text('替换整份配置', 'Replace entire configuration'),
      _ => _text('保存配置', 'Save configuration'),
    };
  }

  String get _pageSubtitle {
    return switch (_kind) {
      'codex' => _text(
        'API Key 保存在本机 Agent 运行环境的权限受限文件中，可能是明文；这里只显示密钥是否存在，已有 API Key 永不显示。留空会保留，输入新值才替换。保存或清除会退出当前本地 Agent 会话。',
        'The API Key is stored in a permission-restricted local Agent runtime file and may be plaintext. Only its presence is shown; the existing value is never displayed. Blank keeps it and a new value replaces it. Saving or clearing exits the current local Agent session.',
      ),
      'replace-only' => _text(
        '$_configPath 是本机 Agent 运行环境文件，其中秘密可能是明文。现有配置和秘密不会显示；下方输入始终为空，提交会替换整份文件，空输入不会覆盖。替换或清除会退出当前本地 Agent 会话，服务端和云端不受影响。',
        '$_configPath is a local Agent runtime file whose secrets may be plaintext. Existing configuration and secrets are never shown; the field below always starts empty, submitting replaces the entire file, and empty input never overwrites it. Replacing or clearing exits the current local Agent session; server and cloud data are unaffected.',
      ),
      'profile' => _text(
        'API 和模型由该 Agent 自身配置；这里仅管理 ACP 启动命令、参数与环境。',
        'The Agent owns its API and model configuration. This page only manages ACP launch settings.',
      ),
      _ => '',
    };
  }

  Widget _buildEditor() {
    return switch (_kind) {
      'codex' => _buildCodexEditor(),
      'replace-only' => _buildReplaceOnlyFileEditor(),
      'profile' => _buildProfileEditor(),
      _ => Text(_text('没有可编辑的配置。', 'No editable configuration.')),
    };
  }

  Widget _buildCodexEditor() {
    return Column(
      children: [
        TextField(
          key: const Key('codex-agent-base-url'),
          controller: _baseUrlController,
          keyboardType: TextInputType.url,
          decoration: const InputDecoration(
            labelText: 'Base URL',
            hintText: 'https://api.example.com/v1',
          ),
        ),
        const SizedBox(height: 14),
        TextField(
          key: const Key('codex-agent-model'),
          controller: _modelController,
          decoration: InputDecoration(
            labelText: _text('模型 ID', 'Model ID'),
            hintText: 'gpt-5.5',
          ),
        ),
        const SizedBox(height: 14),
        TextField(
          key: const Key('codex-agent-api-key'),
          controller: _apiKeyController,
          obscureText: _obscureApiKey,
          enableSuggestions: false,
          autocorrect: false,
          decoration: InputDecoration(
            labelText: 'API Key',
            hintText: _hasStoredApiKey
                ? _text(
                    '已存在；留空表示保留，输入新值表示替换',
                    'Already configured; blank keeps it, a new value replaces it',
                  )
                : null,
            suffixIcon: IconButton(
              tooltip: _obscureApiKey
                  ? _text('显示 API Key', 'Show API Key')
                  : _text('隐藏 API Key', 'Hide API Key'),
              onPressed: () => setState(() => _obscureApiKey = !_obscureApiKey),
              icon: Icon(
                _obscureApiKey
                    ? Icons.visibility_outlined
                    : Icons.visibility_off_outlined,
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildReplaceOnlyFileEditor() {
    final status = _hasConfig
        ? _text(
            '已配置 · $_configByteCount 字节',
            'Configured · $_configByteCount bytes',
          )
        : _text('尚未配置', 'Not configured');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          status,
          key: const Key('agent-config-status'),
          style: Theme.of(context).textTheme.titleSmall,
        ),
        const SizedBox(height: 4),
        SelectableText(
          _configPath,
          key: const Key('agent-config-display-path'),
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 12),
        Text(
          _text(
            '本机文件可能含明文秘密。替换整份文件；现有内容和秘密不会显示。',
            'The local file may contain plaintext secrets. Replace the entire file; existing content and secrets are never shown.',
          ),
          key: const Key('agent-config-replace-warning'),
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
            fontSize: 12,
          ),
        ),
        const SizedBox(height: 10),
        TextField(
          key: const Key('agent-raw-config-content'),
          controller: _contentController,
          minLines: 16,
          maxLines: 28,
          keyboardType: TextInputType.multiline,
          style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
          decoration: InputDecoration(
            labelText: _text('新的完整配置', 'New complete configuration'),
            alignLabelWithHint: true,
            hintText: _configFormat == 'jsonc'
                ? _text('// OpenCode 支持 JSONC', '// OpenCode supports JSONC')
                : '{\n}\n',
          ),
        ),
      ],
    );
  }

  Widget _buildProfileEditor() {
    return Column(
      children: [
        TextField(
          controller: _commandController,
          decoration: InputDecoration(
            labelText: _text('启动命令或路径', 'Command or path'),
          ),
        ),
        const SizedBox(height: 14),
        TextField(
          controller: _argumentsController,
          minLines: 3,
          maxLines: 6,
          decoration: InputDecoration(
            labelText: _text('启动参数（每行一个）', 'Arguments (one per line)'),
          ),
        ),
        const SizedBox(height: 14),
        TextField(
          controller: _environmentController,
          minLines: 5,
          maxLines: 10,
          decoration: InputDecoration(
            labelText: _text('环境变量', 'Environment variables'),
            hintText: 'KEY=VALUE',
          ),
        ),
        SwitchListTile.adaptive(
          contentPadding: EdgeInsets.zero,
          title: Text(_text('启用 Agent', 'Enable Agent')),
          value: _enabled,
          onChanged: (value) => setState(() => _enabled = value),
        ),
      ],
    );
  }
}

class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.error, required this.onRetry});

  final String error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final english =
        Localizations.localeOf(context).languageCode.toLowerCase() == 'en';
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(error, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: onRetry,
              child: Text(english ? 'Retry' : '重试'),
            ),
          ],
        ),
      ),
    );
  }
}

List<String> _nonEmptyLines(String source) {
  return source
      .split('\n')
      .map((value) => value.trim())
      .where((value) => value.isNotEmpty)
      .toList(growable: false);
}

Map<String, String> _parseEnvironment(String source) {
  final environment = <String, String>{};
  for (final line in source.split('\n')) {
    final separator = line.indexOf('=');
    if (separator <= 0) continue;
    final key = line.substring(0, separator).trim();
    if (key.isEmpty) continue;
    environment[key] = line.substring(separator + 1);
  }
  return environment;
}
