import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/scene_model_config_service.dart';
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
  int _configRevision = 0;
  bool _loading = true;
  bool _saving = false;
  bool _obscureApiKey = true;
  bool _enabled = true;
  bool _changed = false;
  String? _reasoningEffort;
  String? _permissionMode;
  bool _sharedModelLoading = true;
  bool _sharedModelSaving = false;
  List<ModelProviderProfileSummary> _providerProfiles = const [];
  Map<String, List<ProviderModelOption>> _providerModels = {};
  SceneModelBindingEntry? _sharedModelBinding;
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
      if (agent.builtIn) {
        unawaited(_loadSharedModelSelection());
      }
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
    _configRevision = switch (payload['revision']) {
      int value => value,
      num value => value.toInt(),
      String value => int.tryParse(value) ?? 0,
      _ => 0,
    };
    _setText(_baseUrlController, payload['baseUrl']?.toString() ?? '');
    _setText(_modelController, payload['model']?.toString() ?? '');
    _setText(_apiKeyController, payload['apiKey']?.toString() ?? '');
    _setText(_contentController, payload['content']?.toString() ?? '');
    _configPath =
        payload['configPath']?.toString() ?? payload['path']?.toString() ?? '';
    _authPath = payload['authPath']?.toString() ?? '';
    _reasoningEffort = payload['reasoningEffort']?.toString().trim();
    _permissionMode = payload['permissionMode']?.toString().trim();
    if (_reasoningEffort?.isEmpty == true) _reasoningEffort = null;
    if (_permissionMode?.isEmpty == true) _permissionMode = null;
  }

  Future<void> _loadSharedModelSelection() async {
    try {
      final profilesPayload = await ModelProviderConfigService.listProfiles();
      final bindings = await SceneModelConfigService.getSceneModelBindings();
      final models = <String, List<ProviderModelOption>>{};
      for (final profile in profilesPayload.profiles) {
        // This page reads the persisted Provider document. It must not turn
        // merely opening Agent settings into a serialized /models sweep.
        models[profile.id] = profile.configured
            ? await ModelProviderConfigService.getStoredModelOptionsForProfile(
                profile.id,
                profile: profile,
                enrichMetadata: false,
              )
            : const <ProviderModelOption>[];
      }
      final persistedBinding = bindings
          .where((item) => item.sceneId == 'scene.dispatch.model')
          .firstOrNull;
      final persistedModels = persistedBinding == null
          ? const <ProviderModelOption>[]
          : models[persistedBinding.providerProfileId] ??
                const <ProviderModelOption>[];
      final binding =
          persistedBinding != null &&
              (persistedModels.isEmpty ||
                  persistedModels.any(
                    (item) =>
                        item.id.trim().toLowerCase() ==
                        persistedBinding.modelId.trim().toLowerCase(),
                  ))
          ? persistedBinding
          : null;
      if (!mounted) return;
      setState(() {
        _providerProfiles = profilesPayload.profiles;
        _providerModels = models;
        _sharedModelBinding = binding;
        _sharedModelLoading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _sharedModelLoading = false;
      });
      debugPrint('Load shared Agent Provider selection failed: $error');
    }
  }

  Future<void> _selectSharedModel() async {
    if (_sharedModelSaving || _sharedModelLoading) return;
    final selection = await showModalBottomSheet<_SharedModelSelection>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) {
        final expanded = <String>{
          if (_sharedModelBinding != null)
            _sharedModelBinding!.providerProfileId,
        };
        if (expanded.isEmpty && _providerProfiles.isNotEmpty) {
          expanded.add(_providerProfiles.first.id);
        }
        return StatefulBuilder(
          builder: (context, setSheetState) {
            return SafeArea(
              child: SizedBox(
                height: MediaQuery.sizeOf(context).height * 0.7,
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(12, 12, 12, 24),
                  children: [
                    ListTile(
                      title: Text(
                        _text(
                          '选择 Agent Provider / 模型',
                          'Select Agent Provider / model',
                        ),
                      ),
                      subtitle: Text(
                        _text(
                          '所有 ACP 默认继承这里的选择。',
                          'All ACP Agents inherit this selection by default.',
                        ),
                      ),
                    ),
                    for (final profile in _providerProfiles)
                      ExpansionTile(
                        initiallyExpanded: expanded.contains(profile.id),
                        onExpansionChanged: (value) {
                          setSheetState(() {
                            if (value) {
                              expanded.add(profile.id);
                            } else {
                              expanded.remove(profile.id);
                            }
                          });
                        },
                        title: Text(profile.name),
                        subtitle: Text(
                          profile.configured
                              ? _text(
                                  '选择该 Provider 的模型',
                                  'Choose a model from this Provider',
                                )
                              : _text('未配置', 'Not configured'),
                        ),
                        children: [
                          for (final model
                              in (_providerModels[profile.id] ?? const []))
                            ListTile(
                              title: Text(model.id),
                              trailing:
                                  _sharedModelBinding?.providerProfileId ==
                                          profile.id &&
                                      _sharedModelBinding?.modelId == model.id
                                  ? const Icon(LucideIcons.check)
                                  : null,
                              onTap: () => Navigator.of(sheetContext).pop(
                                _SharedModelSelection(
                                  providerProfileId: profile.id,
                                  modelId: model.id,
                                ),
                              ),
                            ),
                          if ((_providerModels[profile.id] ?? const []).isEmpty)
                            ListTile(
                              title: Text(
                                _text(
                                  '没有可用模型，请先检查 Provider 配置。',
                                  'No models available. Check this Provider first.',
                                ),
                              ),
                            ),
                        ],
                      ),
                    if (_providerProfiles.isEmpty)
                      ListTile(
                        title: Text(
                          _text('没有可用 Provider。', 'No Provider is available.'),
                        ),
                      ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
    if (selection == null) return;
    await _saveSharedModel(selection);
  }

  Future<void> _saveSharedModel(_SharedModelSelection selection) async {
    setState(() {
      _sharedModelSaving = true;
      _error = null;
    });
    try {
      final bindings = await SceneModelConfigService.saveSceneModelBinding(
        sceneId: 'scene.dispatch.model',
        providerProfileId: selection.providerProfileId,
        modelId: selection.modelId,
      );
      if (!mounted) return;
      setState(() {
        _sharedModelBinding = bindings
            .where((item) => item.sceneId == 'scene.dispatch.model')
            .firstOrNull;
        _sharedModelSaving = false;
        _changed = true;
      });
      try {
        await AgentRuntimeService.disconnect();
      } catch (_) {
        // The next ACP request still prepares the selected shared mapping.
      }
      showToast(
        _text('Agent Provider / 模型已更新。', 'Agent Provider / model updated.'),
        type: ToastType.success,
      );
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _sharedModelSaving = false;
        _error = error.toString();
      });
      showToast(error.toString(), type: ToastType.error);
    }
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
          final payload = await AgentRuntimeService.writeAgentConfig(
            _agent!.id,
            expectedRevision: _configRevision > 0 ? _configRevision : null,
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
            expectedRevision: _configRevision > 0 ? _configRevision : null,
          );
          if (!mounted) return;
          _syncPayload(payload);
          break;
        case 'jsonc':
          final payload = await AgentRuntimeService.writeAgentConfig(
            _agent!.id,
            content: _contentController.text,
            expectedRevision: _configRevision > 0 ? _configRevision : null,
          );
          if (!mounted) return;
          _syncPayload(payload);
          break;
        case 'deepseek-harness':
          final payload = await AgentRuntimeService.writeAgentConfig(
            _agent!.id,
            reasoningEffort: _reasoningEffort,
            permissionMode: _permissionMode,
            expectedRevision: _configRevision > 0 ? _configRevision : null,
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
        '默认直接复用统一 Provider 和模型；这里仅保留官方 DSH 配置入口。安装官方 Harness 后，检测只检查当前运行状态。',
        'The shared Provider and model are used by default. This page only keeps the official DSH configuration entry. After installation, Check only verifies the current runtime state.',
      ),
      'profile' => _text(
        '保存不会中断当前对话；启动命令、参数与环境变量在下次启动 Agent 进程时生效。Provider 和模型由该 Agent 自身的配置管理。',
        'Saving does not interrupt the current conversation. Command, arguments, and environment changes apply when the Agent process next starts. Provider and model settings are managed by the Agent itself.',
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
        _buildSharedProviderModelSelector(),
        const SizedBox(height: 12),
        Text(
          _text(
            'Base URL 和 API Key 自动来自 Provider 配置。官方配置文件：$_configPath；认证文件：$_authPath',
            'Base URL and API key come from the selected Provider. Official config: $_configPath; auth: $_authPath',
          ),
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ],
    );
  }

  Widget _buildDeepSeekHarnessEditor() {
    return Column(
      children: [
        _buildSharedProviderModelSelector(),
        const SizedBox(height: 14),
        Align(
          alignment: Alignment.centerLeft,
          child: Text(
            _text('官方配置文件：$_configPath', 'Official config: $_configPath'),
            style: Theme.of(context).textTheme.bodySmall,
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
        const SizedBox(height: 14),
        DropdownButtonFormField<String>(
          key: ValueKey('deepseek-harness-permission-$_permissionMode'),
          initialValue: _permissionMode,
          decoration: InputDecoration(
            labelText: _text('权限模式', 'Permission mode'),
          ),
          items: [
            DropdownMenuItem(
              value: 'read-only',
              child: Text(_text('只读', 'Read-only')),
            ),
            DropdownMenuItem(
              value: 'workspace-write',
              child: Text(_text('工作区可写', 'Workspace write')),
            ),
            DropdownMenuItem(
              value: 'danger-full-access',
              child: Text(_text('完全访问', 'Full access')),
            ),
          ],
          onChanged: (value) {
            if (value != null) setState(() => _permissionMode = value);
          },
        ),
      ],
    );
  }

  Widget _buildSharedProviderModelSelector() {
    final binding = _sharedModelBinding;
    final profile = binding == null
        ? null
        : _providerProfiles
              .where((item) => item.id == binding.providerProfileId)
              .firstOrNull;
    final label = binding == null
        ? _text('请选择 Provider / 模型', 'Select Provider / model')
        : '${profile?.name ?? binding.providerProfileId} / ${binding.modelId}';
    return InkWell(
      key: const Key('agent-shared-provider-model-selector'),
      onTap: _sharedModelSaving || _sharedModelLoading
          ? null
          : _selectSharedModel,
      borderRadius: BorderRadius.circular(10),
      child: InputDecorator(
        decoration: InputDecoration(
          labelText: _text(
            '统一 Agent Provider / 模型',
            'Shared Agent Provider / model',
          ),
          suffixIcon: _sharedModelSaving
              ? const Padding(
                  padding: EdgeInsets.all(12),
                  child: SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                )
              : const Icon(LucideIcons.chevronDown),
        ),
        child: Text(
          _sharedModelLoading
              ? _text('正在加载 Provider…', 'Loading Providers…')
              : label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ),
    );
  }

  Widget _buildRawFileEditor() {
    return Column(
      children: [
        _buildSharedProviderModelSelector(),
        const SizedBox(height: 14),
        TextField(
          key: const Key('agent-raw-config-content'),
          controller: _contentController,
          minLines: 16,
          maxLines: 28,
          keyboardType: TextInputType.multiline,
          style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
          decoration: InputDecoration(
            labelText: _text('高级配置：$_configPath', 'Advanced: $_configPath'),
            alignLabelWithHint: true,
            hintText: '{\n}\n',
          ),
        ),
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerLeft,
          child: Text(
            _text(
              '仅在需要 Adapter 专属参数时编辑。Provider 凭据不在这里填写。',
              'Edit this only for Adapter-specific options. Provider credentials are not entered here.',
            ),
            style: Theme.of(context).textTheme.bodySmall,
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

class _SharedModelSelection {
  const _SharedModelSelection({
    required this.providerProfileId,
    required this.modelId,
  });

  final String providerProfileId;
  final String modelId;
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
