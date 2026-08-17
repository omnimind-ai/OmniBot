import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/agent_runtime_service.dart';
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
  String _configPath = '';
  String _authPath = '';
  bool _loading = true;
  bool _saving = false;
  bool _obscureApiKey = true;
  bool _enabled = true;
  bool _changed = false;
  String _reasoningEffort = 'max';
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
      Map<String, dynamic> payload = const {};
      if (agent.builtIn) {
        payload = await AgentRuntimeService.readAgentConfig(agent.id);
      }
      if (!mounted) return;
      _syncAgent(agent);
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

  void _syncPayload(Map<String, dynamic> payload) {
    _setText(_baseUrlController, payload['baseUrl']?.toString() ?? '');
    _setText(_modelController, payload['model']?.toString() ?? '');
    _setText(_apiKeyController, payload['apiKey']?.toString() ?? '');
    _setText(_contentController, payload['content']?.toString() ?? '');
    _configPath =
        payload['configPath']?.toString() ?? payload['path']?.toString() ?? '';
    _authPath = payload['authPath']?.toString() ?? '';
    _reasoningEffort = switch (payload['reasoningEffort']?.toString()) {
      'off' => 'off',
      'high' => 'high',
      _ => 'max',
    };
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
          if (baseUrl.isEmpty || model.isEmpty || apiKey.isEmpty) {
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
            apiKey: apiKey,
          );
          if (!mounted) return;
          _syncPayload(payload);
          break;
        case 'json':
          final content = _contentController.text;
          final decoded = jsonDecode(content);
          if (decoded is! Map) {
            throw const FormatException(
              'settings.json must contain a JSON object.',
            );
          }
          final payload = await AgentRuntimeService.writeAgentConfig(
            _agent!.id,
            content: content,
          );
          if (!mounted) return;
          _syncPayload(payload);
          break;
        case 'jsonc':
          final payload = await AgentRuntimeService.writeAgentConfig(
            _agent!.id,
            content: _contentController.text,
          );
          if (!mounted) return;
          _syncPayload(payload);
          break;
        case 'deepseek-harness':
          final baseUrl = _baseUrlController.text.trim();
          final model = _modelController.text.trim();
          final apiKey = _apiKeyController.text.trim();
          if (baseUrl.isEmpty || model.isEmpty || apiKey.isEmpty) {
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
            apiKey: apiKey,
            reasoningEffort: _reasoningEffort,
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
    final card = context.isDarkTheme ? palette.surfacePrimary : Colors.white;
    return PopScope(
      // Built-in Agent configuration does not mutate the catalog entry, so it
      // does not need to intercept system back just to return `_changed`.
      // Keeping the route poppable is also required for Android predictive
      // back: PredictiveBackGestureWrapper only starts when
      // ModalRoute.popGestureEnabled is true.
      canPop: _agent?.builtIn == true,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _close();
      },
      child: Scaffold(
        backgroundColor: palette.pageBackground,
        appBar: CommonAppBar(
          title: _agent?.name ?? _text('Agent 配置', 'Agent configuration'),
          primary: true,
          onBackPressed: _close,
          actions: [
            if (_agent?.builtIn == false)
              IconButton(
                tooltip: _text('删除 Agent', 'Delete Agent'),
                onPressed: _saving ? null : _deleteCustomAgent,
                icon: const Icon(LucideIcons.trash2),
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
                            : const Icon(LucideIcons.save),
                        label: Text(_text('保存配置', 'Save configuration')),
                      ),
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
      'json' => _text('Claude Code 配置', 'Claude Code configuration'),
      'jsonc' => _text('OpenCode 配置', 'OpenCode configuration'),
      'deepseek-harness' => _text(
        'DeepSeek Harness 配置',
        'DeepSeek Harness configuration',
      ),
      'profile' => _text('ACP 启动配置', 'ACP launch configuration'),
      _ => _text('Agent 配置', 'Agent configuration'),
    };
  }

  String get _pageSubtitle {
    return switch (_kind) {
      'codex' => _text(
        '默认直接复用统一 Provider；这里仅查看或覆盖官方 Codex 文件，保存后下一次启动 ACP 时生效。',
        'The shared Provider is used by default. This page only views or overrides the official Codex files; changes apply on the next ACP start.',
      ),
      'json' => _text(
        '直接编辑 $_configPath。这里显示的就是配置文件当前内容。',
        'Edit $_configPath directly. This is the current file content.',
      ),
      'jsonc' => _text(
        '直接编辑 $_configPath；OpenCode 支持 JSON 和 JSONC。',
        'Edit $_configPath directly. OpenCode supports JSON and JSONC.',
      ),
      'deepseek-harness' => _text(
        '默认直接复用统一 Provider 和模型；这里仅保留官方 DSH 配置入口。首次检测会准备官方 dsh ACP 运行组件。',
        'The shared Provider and model are used by default. This page only keeps the official DSH configuration entry. The first check prepares the official dsh ACP runtime.',
      ),
      'profile' => _text(
        '自定义 Agent 只管理 ACP 启动命令、参数与环境；Provider 和模型仍由统一 Agent 配置提供。',
        'Custom Agents only manage the ACP launch command, arguments, and environment; the shared Agent Provider supplies credentials and model.',
      ),
      _ => '',
    };
  }

  Widget _buildEditor() {
    return switch (_kind) {
      'codex' => _buildCodexEditor(),
      'json' || 'jsonc' => _buildRawFileEditor(),
      'deepseek-harness' => _buildDeepSeekHarnessEditor(),
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
            suffixIcon: IconButton(
              tooltip: _obscureApiKey
                  ? _text('显示 API Key', 'Show API Key')
                  : _text('隐藏 API Key', 'Hide API Key'),
              onPressed: () => setState(() => _obscureApiKey = !_obscureApiKey),
              icon: Icon(_obscureApiKey ? LucideIcons.eye : LucideIcons.eyeOff),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildDeepSeekHarnessEditor() {
    return Column(
      children: [
        TextField(
          key: const Key('deepseek-harness-base-url'),
          controller: _baseUrlController,
          keyboardType: TextInputType.url,
          decoration: const InputDecoration(
            labelText: 'Base URL',
            hintText: 'https://api.deepseek.com',
          ),
        ),
        const SizedBox(height: 14),
        TextField(
          key: const Key('deepseek-harness-model'),
          controller: _modelController,
          decoration: InputDecoration(
            labelText: _text('模型 ID', 'Model ID'),
            hintText: 'deepseek-v4-pro',
          ),
        ),
        const SizedBox(height: 14),
        TextField(
          key: const Key('deepseek-harness-api-key'),
          controller: _apiKeyController,
          obscureText: _obscureApiKey,
          enableSuggestions: false,
          autocorrect: false,
          decoration: InputDecoration(
            labelText: 'DeepSeek API Key',
            suffixIcon: IconButton(
              tooltip: _obscureApiKey
                  ? _text('显示 API Key', 'Show API Key')
                  : _text('隐藏 API Key', 'Hide API Key'),
              onPressed: () => setState(() => _obscureApiKey = !_obscureApiKey),
              icon: Icon(_obscureApiKey ? LucideIcons.eye : LucideIcons.eyeOff),
            ),
          ),
        ),
        const SizedBox(height: 14),
        DropdownButtonFormField<String>(
          key: ValueKey('deepseek-harness-reasoning-$_reasoningEffort'),
          initialValue: _reasoningEffort,
          decoration: InputDecoration(
            labelText: _text('推理强度', 'Reasoning effort'),
          ),
          items: const [
            DropdownMenuItem(value: 'off', child: Text('Off')),
            DropdownMenuItem(value: 'high', child: Text('High')),
            DropdownMenuItem(value: 'max', child: Text('Max')),
          ],
          onChanged: (value) {
            if (value != null) setState(() => _reasoningEffort = value);
          },
        ),
      ],
    );
  }

  Widget _buildRawFileEditor() {
    return TextField(
      key: const Key('agent-raw-config-content'),
      controller: _contentController,
      minLines: 16,
      maxLines: 28,
      keyboardType: TextInputType.multiline,
      style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
      decoration: InputDecoration(
        labelText: _configPath,
        alignLabelWithHint: true,
        hintText: '{\n}\n',
      ),
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
