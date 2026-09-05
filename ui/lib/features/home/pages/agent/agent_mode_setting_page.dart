import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/agent_web_action_presenter.dart';
import 'package:ui/services/omni_plugin_service.dart';
import 'package:ui/services/scene_model_config_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/agent_brand_icon.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/omni_segmented_slider.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';
import 'package:ui/widgets/settings_section_title.dart';

enum _AgentFilter { all, available, unavailable }

class AgentModeSettingPage extends StatefulWidget {
  const AgentModeSettingPage({super.key});

  @override
  State<AgentModeSettingPage> createState() => _AgentModeSettingPageState();
}

class _AgentModeSettingPageState extends State<AgentModeSettingPage> {
  AcpAgentCatalog? _catalog;
  _AgentFilter _filter = _AgentFilter.all;
  String _query = '';
  bool _loading = true;
  bool _refreshing = false;
  String? _error;
  String? _busyAgentId;
  String? _busyPluginActionKey;
  List<OmniPluginActionItem> _pluginActions = const <OmniPluginActionItem>[];
  int _catalogRequestId = 0;
  late Set<String> _preparingAgentIds;
  StreamSubscription<Set<String>>? _preparationSubscription;
  String _sharedModelLabel = '';
  // 远程 PC Bridge 状态：先用缓存同步渲染，后台再刷新，避免一帧加载闪烁。
  bool _remoteBridgeEnabled =
      StorageService.getBool(StorageService.kRemoteBridgeEnabledKey) ?? false;

  bool get _english =>
      Localizations.localeOf(context).languageCode.toLowerCase() == 'en';

  String _text(String zh, String en) => _english ? en : zh;

  @override
  void initState() {
    super.initState();
    _preparingAgentIds = AgentRuntimeService.preparingAgentIds;
    _preparationSubscription = AgentRuntimeService.agentPreparationChanges
        .listen((agentIds) {
          if (!mounted) return;
          final completed = _preparingAgentIds.difference(agentIds).isNotEmpty;
          setState(() => _preparingAgentIds = agentIds);
          if (completed) unawaited(_load());
        });
    // The native agent/list endpoint returns cached health. Keep route entry
    // immediate; users can request the full terminal/proot probe with the
    // refresh action without blocking this page.
    unawaited(_load());
    unawaited(_loadPluginActions());
    unawaited(_loadSharedModel());
    unawaited(_loadRemoteBridge());
  }

  @override
  void dispose() {
    unawaited(_preparationSubscription?.cancel());
    super.dispose();
  }

  Future<void> _loadSharedModel() async {
    try {
      final catalog = await SceneModelConfigService.getSceneCatalog();
      final agentScene = catalog.firstWhere(
        (item) => item.sceneId == 'scene.dispatch.model',
        orElse: () => const SceneCatalogItem(
          sceneId: '',
          description: '',
          defaultModel: '',
          effectiveModel: '',
          effectiveProviderProfileId: '',
          effectiveProviderProfileName: '',
          boundProviderProfileId: '',
          boundProviderProfileName: '',
          transport: '',
          configSource: '',
          overrideApplied: false,
          overrideModel: '',
          providerConfigured: false,
          bindingExists: false,
          bindingProfileMissing: false,
        ),
      );
      final provider = agentScene.effectiveProviderProfileName.trim();
      final model = agentScene.effectiveModel.trim();
      if (!mounted) return;
      setState(() {
        _sharedModelLabel = [
          provider,
          model,
        ].where((value) => value.isNotEmpty).join(' / ');
      });
    } catch (_) {
      // The Agent catalog remains usable when scene binding is unavailable.
    }
  }

  Future<void> _loadPluginActions() async {
    try {
      final actions = await OmniPluginService.listActions();
      if (!mounted) return;
      setState(() {
        _pluginActions = actions
            .where((action) => action.supportsPlacement('agent_settings'))
            .toList(growable: false);
      });
    } catch (_) {
      // Agent configuration remains available if the optional action catalog
      // cannot be read during app startup.
    }
  }

  Future<void> _load({bool refresh = false}) async {
    final requestId = ++_catalogRequestId;
    if (refresh) {
      setState(() => _refreshing = true);
    }
    try {
      final catalog = refresh
          ? await AgentRuntimeService.refreshAgents()
          : await AgentRuntimeService.listAgents();
      if (!mounted || requestId != _catalogRequestId) return;
      setState(() {
        _catalog = catalog;
        _loading = false;
        _refreshing = false;
        _error = null;
      });
    } catch (error) {
      if (!mounted || requestId != _catalogRequestId) return;
      setState(() {
        _loading = false;
        _refreshing = false;
        // The native ACP catalog is the only source of truth. Keep an
        // already loaded catalog on transient errors, but never invent a
        // second list in Dart.
        _error = error.toString();
      });
    }
  }

  Future<void> _loadRemoteBridge() async {
    try {
      final config = await AgentRuntimeService.readRemoteBridgeConfig();
      if (!mounted) return;
      final enabled = config.remoteEnabled;
      setState(() => _remoteBridgeEnabled = enabled);
      await StorageService.setBool(
        StorageService.kRemoteBridgeEnabledKey,
        enabled,
      );
    } catch (error) {
      debugPrint('Load remote bridge failed: $error');
    }
  }

  List<AcpAgentProfile> get _visibleAgents {
    final normalizedQuery = _query.trim().toLowerCase();
    return (_catalog?.agents ?? const <AcpAgentProfile>[])
        .where((agent) {
          final matchesQuery =
              normalizedQuery.isEmpty ||
              [
                agent.name,
                agent.description,
                agent.command,
              ].join(' ').toLowerCase().contains(normalizedQuery);
          if (!matchesQuery) return false;
          return switch (_filter) {
            _AgentFilter.all => true,
            _AgentFilter.available => agent.status == 'online',
            _AgentFilter.unavailable => agent.status != 'online',
          };
        })
        .toList(growable: false);
  }

  int _countFor(_AgentFilter filter) {
    final agents = _catalog?.agents ?? const <AcpAgentProfile>[];
    return switch (filter) {
      _AgentFilter.all => agents.length,
      _AgentFilter.available =>
        agents.where((agent) => agent.status == 'online').length,
      _AgentFilter.unavailable =>
        agents.where((agent) => agent.status != 'online').length,
    };
  }

  Future<void> _test(AcpAgentProfile agent) async {
    if (_busyAgentId == agent.id ||
        _preparingAgentIds.contains(agent.id) ||
        !agent.enabled) {
      return;
    }
    await _runAgentAction(agent, prepare: false);
  }

  void _prepare(AcpAgentProfile agent) {
    if (_preparingAgentIds.contains(agent.id) || !agent.enabled) return;
    unawaited(_runAgentAction(agent, prepare: true));
  }

  Future<void> _runAgentAction(
    AcpAgentProfile agent, {
    required bool prepare,
  }) async {
    if (!prepare) setState(() => _busyAgentId = agent.id);
    try {
      final result = prepare
          ? await AgentRuntimeService.prepareAgentInBackground(agent.id)
          : await AgentRuntimeService.testAgent(agent.id);
      if (!mounted) return;
      await _load();
      if (!mounted) return;
      final ok = result['ok'] == true;
      final installed =
          result['agent'] is Map &&
          (result['agent'] as Map)['installed'] == true;
      final providerConfigurationPending = prepare && !ok && installed;
      final title = prepare
          ? (ok
                ? _text('Harness 安装成功', 'Harness installation succeeded')
                : providerConfigurationPending
                ? _text(
                    'Harness 已准备，等待 Dispatch Model 配置',
                    'Harness is ready; Dispatch Model configuration is pending',
                  )
                : _text('Harness 安装失败', 'Harness installation failed'))
          : (ok
                ? _text('Agent 检测成功', 'Agent check succeeded')
                : _text('Agent 检测失败', 'Agent check failed'));
      await showSettingsDetailSheet<void>(
        context: context,
        builder: (sheetContext) => SettingsDetailSheet(
          key: ValueKey('agent-check-result-${agent.id}'),
          title: title,
          body: Semantics(
            container: true,
            liveRegion: true,
            label: title,
            child: SelectableText(
              ok
                  ? _formatCapabilities(result['capabilities'])
                  : (result['error']?.toString() ??
                        _text('未知错误', 'Unknown error')),
            ),
          ),
        ),
      );
    } catch (error) {
      if (!mounted) return;
      showToast(error.toString(), type: ToastType.error);
    } finally {
      if (!prepare && mounted) setState(() => _busyAgentId = null);
    }
  }

  String _formatCapabilities(dynamic value, {String indent = ''}) {
    if (value is Map) {
      return value.entries
          .map((entry) {
            final nested = entry.value;
            if (nested is Map || nested is List) {
              return '$indent${entry.key}:\n'
                  '${_formatCapabilities(nested, indent: '$indent  ')}';
            }
            return '$indent${entry.key}: $nested';
          })
          .join('\n');
    }
    if (value is List) {
      return value
          .map(
            (item) =>
                '$indent- '
                '${_formatCapabilities(item, indent: '$indent  ').trim()}',
          )
          .join('\n');
    }
    return '$indent$value';
  }

  Future<void> _addCustomAgent() async {
    final result = await showDialog<AcpAgentProfile>(
      context: context,
      builder: (dialogContext) => _AddCustomAgentDialog(english: _english),
    );
    if (result == null) return;
    try {
      final catalog = await AgentRuntimeService.saveAgent(result);
      if (!mounted) return;
      setState(() {
        _catalog = catalog;
        _error = null;
      });
    } catch (error) {
      if (!mounted) return;
      showToast(error.toString(), type: ToastType.error);
    }
  }

  Future<void> _openAgentConfig(AcpAgentProfile agent) async {
    final changed = await GoRouterManager.pushForResult<bool>(
      '/home/agent_config/${Uri.encodeComponent(agent.id)}',
    );
    if (changed == true && mounted) {
      await _load();
    }
  }

  String _pluginActionKey(OmniPluginActionItem action) =>
      '${action.pluginId}/${action.id}';

  Future<void> _invokePluginAction(OmniPluginActionItem action) async {
    final key = _pluginActionKey(action);
    if (_busyPluginActionKey != null) return;
    setState(() => _busyPluginActionKey = key);
    try {
      await AgentWebActionPresenter.invoke(action, english: _english);
    } finally {
      if (mounted && _busyPluginActionKey == key) {
        setState(() => _busyPluginActionKey = null);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final dark = context.isDarkTheme;
    final card = dark ? palette.surfacePrimary : Colors.white;
    final agents = _visibleAgents;
    final managed = agents.where((agent) => agent.builtIn).toList();
    final custom = agents.where((agent) => !agent.builtIn).toList();
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: _text('Agent 模式', 'Agent mode'),
        primary: true,
        actions: [
          IconButton(
            tooltip: _text('刷新检测', 'Refresh detection'),
            onPressed: _refreshing ? null : () => _load(refresh: true),
            icon: _refreshing
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(LucideIcons.refreshCw),
          ),
          IconButton(
            tooltip: _text('添加自定义 ACP Agent', 'Add custom ACP Agent'),
            onPressed: _addCustomAgent,
            icon: const Icon(LucideIcons.plus),
          ),
        ],
      ),
      body: SafeArea(
        top: false,
        bottom: false,
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _error != null && (_catalog?.agents.isEmpty ?? true)
            ? Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(_error!, textAlign: TextAlign.center),
                      const SizedBox(height: 12),
                      FilledButton(
                        onPressed: _load,
                        child: Text(_text('重试', 'Retry')),
                      ),
                    ],
                  ),
                ),
              )
            : ListView(
                padding: edgeToEdgeScrollPadding(
                  context,
                  const EdgeInsets.fromLTRB(18, 12, 18, 28),
                ),
                children: [
                  SettingsSectionTitle(
                    label: _text('托管 Agent', 'Managed Agents'),
                    subtitle: _text(
                      '预置 Agent 默认使用统一 Provider 和模型；自定义 Agent 使用自己的启动环境与配置。状态来自命令检测与 ACP initialize。',
                      'Built-in Agents use the shared Provider and model by default. Custom Agents use their own launch environment and configuration. Status comes from command detection and ACP initialize.',
                    ),
                  ),
                  _buildSharedModelSummary(card),
                  const SizedBox(height: 12),
                  _buildSearchField(card),
                  const SizedBox(height: 12),
                  OmniSegmentedSlider<_AgentFilter>(
                    value: _filter,
                    keyPrefix: 'agent-filter',
                    options: [
                      OmniSegmentedOption(
                        value: _AgentFilter.all,
                        label:
                            '${_text('全部', 'All')} '
                            '${_countFor(_AgentFilter.all)}',
                      ),
                      OmniSegmentedOption(
                        value: _AgentFilter.available,
                        label:
                            '${_text('可用', 'Available')} '
                            '${_countFor(_AgentFilter.available)}',
                      ),
                      OmniSegmentedOption(
                        value: _AgentFilter.unavailable,
                        label:
                            '${_text('不可用', 'Unavailable')} '
                            '${_countFor(_AgentFilter.unavailable)}',
                      ),
                    ],
                    onChanged: (value) => setState(() => _filter = value),
                  ),
                  if (managed.isNotEmpty) ...[
                    const SizedBox(height: 20),
                    _buildSectionLabel(_text('预置 Agent', 'Built-in Agents')),
                    for (var i = 0; i < managed.length; i++) ...[
                      _buildAgentTile(managed[i]),
                      if (i < managed.length - 1) _buildRowDivider(),
                    ],
                  ],
                  if (custom.isNotEmpty) ...[
                    const SizedBox(height: 24),
                    _buildSectionLabel(_text('自定义 Agent', 'Custom Agents')),
                    for (var i = 0; i < custom.length; i++) ...[
                      _buildAgentTile(custom[i]),
                      if (i < custom.length - 1) _buildRowDivider(),
                    ],
                  ],
                  if (agents.isEmpty) ...[
                    const SizedBox(height: 48),
                    Column(
                      children: [
                        Icon(
                          LucideIcons.searchX,
                          size: 26,
                          color: palette.textTertiary,
                        ),
                        const SizedBox(height: 12),
                        Text(
                          _text('没有匹配的 Agent', 'No matching Agents'),
                          style: TextStyle(
                            color: palette.textSecondary,
                            fontSize: 13,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          _text(
                            '换个关键词或筛选条件试试',
                            'Try a different keyword or filter',
                          ),
                          style: TextStyle(
                            color: palette.textTertiary,
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                  ],
                  if (_pluginActions.isNotEmpty) ...[
                    const SizedBox(height: 24),
                    _buildSectionLabel(
                      _text('本地 Web 界面', 'Local Web interfaces'),
                    ),
                    for (var i = 0; i < _pluginActions.length; i++) ...[
                      _buildPluginActionTile(_pluginActions[i]),
                      if (i < _pluginActions.length - 1) _buildRowDivider(),
                    ],
                  ],
                  // 远程 PC Bridge：全局共享配置入口（仅配置远程 ACP 连接）。
                  const SizedBox(height: 24),
                  _buildSectionLabel(_text('远程运行', 'Remote runtime')),
                  _FlatTile(
                    leading: Icon(
                      LucideIcons.monitorSmartphone,
                      size: 18,
                      color: palette.accentPrimary,
                    ),
                    title: _text('远程 PC Bridge', 'Remote PC Bridge'),
                    statusColor: _remoteBridgeEnabled
                        ? const Color(0xFF2EAF67)
                        : const Color(0xFF98A2B3),
                    statusLabel: _remoteBridgeEnabled
                        ? _text('已启用', 'Enabled')
                        : _text('未启用', 'Not enabled'),
                    subtitle: _remoteBridgeEnabled
                        ? _text(
                            'Agent 聊天使用远程 ACP',
                            'Agent chat runs on the remote ACP runtime',
                          )
                        : _text(
                            '配置远程 ACP 连接',
                            'Configure a remote ACP connection',
                          ),
                    onTap: () {
                      GoRouterManager.push('/home/remote_codex_setting');
                    },
                  ),
                ],
              ),
      ),
    );
  }

  Widget _buildSharedModelSummary(Color card) {
    final palette = context.omniPalette;
    final label = _sharedModelLabel.isEmpty
        ? _text('尚未配置统一模型', 'No shared model configured')
        : _sharedModelLabel;
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      decoration: BoxDecoration(
        color: card,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          Icon(LucideIcons.bot, size: 18, color: palette.accentPrimary),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              '${_text('统一 Provider / 模型：', 'Shared Provider / model: ')}$label',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 13,
                height: 1.4,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSearchField(Color card) {
    final palette = context.omniPalette;
    return TextField(
      style: TextStyle(color: palette.textPrimary, fontSize: 14),
      cursorColor: palette.accentPrimary,
      decoration: InputDecoration(
        hintText: _text('搜索 Agent', 'Search Agents'),
        hintStyle: TextStyle(color: palette.textTertiary, fontSize: 13.5),
        prefixIcon: Padding(
          padding: const EdgeInsets.only(left: 14, right: 8),
          child: Icon(
            LucideIcons.search,
            size: 18,
            color: palette.textTertiary,
          ),
        ),
        prefixIconConstraints: const BoxConstraints(),
        filled: true,
        fillColor: card,
        isDense: true,
        contentPadding: const EdgeInsets.symmetric(vertical: 13),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: palette.borderSubtle),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(
            color: palette.accentPrimary.withValues(alpha: 0.6),
          ),
        ),
      ),
      onChanged: (value) => setState(() => _query = value),
    );
  }

  /// 与设置主页一致的分组小标题。
  Widget _buildSectionLabel(String label) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 0, 4, 10),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.6,
          color: context.omniPalette.textTertiary,
          fontFamily: 'PingFang SC',
        ),
      ),
    );
  }

  /// 与设置主页一致的行间隔细分隔线（左侧缩进对齐标题文字）。
  Widget _buildRowDivider() {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(left: 30),
      child: Divider(
        height: 1,
        thickness: 1,
        color: palette.borderSubtle.withValues(
          alpha: context.isDarkTheme ? 0.5 : 0.78,
        ),
      ),
    );
  }

  Widget _buildAgentTile(AcpAgentProfile agent) {
    final palette = context.omniPalette;
    final status = _statusPresentation(agent.status, _english);
    final statusColor = agent.enabled ? status.color : const Color(0xFF98A2B3);
    final hasError =
        (agent.lastCheckError ?? '').isNotEmpty && agent.status != 'online';
    final canTest =
        agent.enabled && (agent.status != 'missing' || agent.managedAdapter);
    final preparing = _preparingAgentIds.contains(agent.id);
    final busy = agent.id == _busyAgentId || preparing;
    final needsManagedPreparation =
        agent.managedAdapter &&
        (agent.status == 'unchecked' || agent.status == 'missing') &&
        (agent.lastCheckError?.contains('will be prepared') == true ||
            agent.lastCheckError?.contains('未初始化') == true ||
            agent.status == 'missing');
    final isDeepSeekHarness = agent.id == 'deepseek-harness-acp';
    final testLabel = needsManagedPreparation && isDeepSeekHarness
        ? _text('安装官方 Harness', 'Install official Harness')
        : needsManagedPreparation
        ? _text('准备并初始化', 'Prepare & initialize')
        : agent.status == 'unchecked'
        ? _text('检测', 'Check')
        : _text('重新检测', 'Check again');
    final installEntry = agent.managedAdapter && agent.status != 'online';
    final action = needsManagedPreparation ? _prepare : _test;
    final capabilitySubtitle = _capabilitySubtitle(agent);
    return _FlatTile(
      tileKey: Key('agent-config-${agent.id}'),
      leading: AgentBrandIcon(
        agentId: agent.id,
        size: 18,
        fallbackColor: palette.accentPrimary,
      ),
      title: agent.name,
      statusColor: preparing ? const Color(0xFFE3A52B) : statusColor,
      statusLabel: preparing
          ? _text('后台安装中', 'Installing in background')
          : !agent.enabled
          ? _text('已停用', 'Disabled')
          : status.label,
      subtitle:
          capabilitySubtitle ??
          (agent.description.isNotEmpty
              ? agent.description
              : ([agent.command, ...agent.arguments]).join(' ')),
      subtitleMonospace:
          capabilitySubtitle == null && agent.description.isEmpty,
      errorText: hasError && !preparing ? agent.lastCheckError : null,
      actionLabel: canTest ? testLabel : null,
      actionKey: Key('agent-check-${agent.id}'),
      onAction: canTest ? () => action(agent) : null,
      navigationLabel: installEntry
          ? _text('安装', 'Install')
          : _text('配置', 'Configure'),
      navigationKey: Key('agent-navigation-${agent.id}'),
      busy: busy,
      onTap: preparing
          ? () {}
          : installEntry
          ? () => _prepare(agent)
          : () => _openAgentConfig(agent),
    );
  }

  Widget _buildPluginActionTile(OmniPluginActionItem action) {
    final palette = context.omniPalette;
    final key = _pluginActionKey(action);
    final busy = _busyPluginActionKey == key;
    final disabled = _busyPluginActionKey != null;
    final label = action.localizedPresentationValue(
      'label',
      english: _english,
      fallback: action.displayName,
    );
    final description = action.localizedPresentationValue(
      'description',
      english: _english,
      fallback: action.description,
    );
    return _FlatTile(
      tileKey: Key('plugin-action-$key'),
      leading: Icon(LucideIcons.globe2, size: 18, color: palette.accentPrimary),
      title: label,
      subtitle: description,
      actionLabel: _text('打开', 'Open'),
      actionKey: Key('plugin-action-button-$key'),
      onAction: disabled ? null : () => _invokePluginAction(action),
      busy: busy,
      onTap: disabled ? null : () => _invokePluginAction(action),
    );
  }

  /// Surface the common plugin workflow without exposing a raw capability
  /// dump. The source remains the generic ACP profile capabilities map; this
  /// page does not branch the runtime by vendor.
  String? _capabilitySubtitle(AcpAgentProfile agent) {
    final plugin = agent.capabilities['plugin'];
    if (plugin is! Map || plugin['supported'] != true) return null;
    final authoring = plugin['authoring'] == true;
    final install = plugin['installViaHarness'] == true;
    if (!authoring && !install) return null;
    if (_english) {
      return authoring && install
          ? 'Plugins: create and install through the Harness'
          : authoring
          ? 'Plugins: create through the Harness'
          : 'Plugins: install through the Harness';
    }
    return authoring && install
        ? '插件：可创建，并由 Harness 安装'
        : authoring
        ? '插件：可通过 Harness 创建'
        : '插件：可通过 Harness 安装';
  }
}

class _AddCustomAgentDialog extends StatefulWidget {
  const _AddCustomAgentDialog({required this.english});

  final bool english;

  @override
  State<_AddCustomAgentDialog> createState() => _AddCustomAgentDialogState();
}

class _AddCustomAgentDialogState extends State<_AddCustomAgentDialog> {
  String _name = '';
  String _command = '';
  String _arguments = '';
  String _environment = '';
  bool _enabled = true;

  String _text(String zh, String en) => widget.english ? en : zh;

  void _save() {
    final name = _name.trim();
    final command = _command.trim();
    if (name.isEmpty) {
      showToast(
        _text('名称不能为空', 'Agent name is required'),
        type: ToastType.warning,
      );
      return;
    }
    if (command.isEmpty) {
      showToast(
        _text('启动命令不能为空', 'Agent command is required'),
        type: ToastType.warning,
      );
      return;
    }
    Navigator.of(context).pop(
      AcpAgentProfile(
        id: '',
        name: name,
        command: command,
        arguments: _nonEmptyLines(_arguments),
        environment: _parseEnvironment(_environment),
        enabled: _enabled,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_text('添加自定义 ACP Agent', 'Add custom ACP Agent')),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                onChanged: (value) => _name = value,
                decoration: InputDecoration(
                  labelText: _text('名称', 'Name'),
                  hintText: 'My ACP Agent',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                onChanged: (value) => _command = value,
                decoration: InputDecoration(
                  labelText: _text('启动命令或路径', 'Command or path'),
                  hintText: '/usr/local/bin/agent',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                onChanged: (value) => _arguments = value,
                minLines: 2,
                maxLines: 4,
                decoration: InputDecoration(
                  labelText: _text('启动参数（每行一个）', 'Arguments (one per line)'),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                onChanged: (value) => _environment = value,
                minLines: 3,
                maxLines: 6,
                decoration: InputDecoration(
                  labelText: _text('启动环境变量', 'Launch environment'),
                  hintText: 'KEY=VALUE',
                  helperText: _text(
                    '变量直接传给 Agent，由 Agent 自身决定如何使用。',
                    'Variables are passed directly to the Agent.',
                  ),
                ),
              ),
              SwitchListTile.adaptive(
                contentPadding: EdgeInsets.zero,
                title: Text(_text('启用 Agent', 'Enable Agent')),
                value: _enabled,
                onChanged: (value) => setState(() => _enabled = value),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(_text('取消', 'Cancel')),
        ),
        FilledButton(onPressed: _save, child: Text(_text('保存', 'Save'))),
      ],
    );
  }
}

/// 扁平设置行：与设置主页（settings_page）一致的行式排版，
/// 行首 18px 图标 + 标题 + 状态/副标题，整行可点击。
class _FlatTile extends StatelessWidget {
  const _FlatTile({
    required this.leading,
    required this.title,
    required this.onTap,
    this.tileKey,
    this.statusColor,
    this.statusLabel,
    this.subtitle,
    this.subtitleMonospace = false,
    this.errorText,
    this.actionLabel,
    this.actionKey,
    this.onAction,
    this.navigationLabel,
    this.navigationKey,
    this.busy = false,
  });

  final Widget leading;
  final String title;
  final VoidCallback? onTap;
  final Key? tileKey;
  final Color? statusColor;
  final String? statusLabel;
  final String? subtitle;
  final bool subtitleMonospace;
  final String? errorText;
  final String? actionLabel;
  final Key? actionKey;
  final VoidCallback? onAction;
  final String? navigationLabel;
  final Key? navigationKey;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final hasSubtitle = (subtitle ?? '').isNotEmpty;
    final hasStatus = statusLabel != null && statusColor != null;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: tileKey,
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(4, 13, 2, 13),
          child: Row(
            children: [
              SizedBox(width: 18, height: 18, child: Center(child: leading)),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                        color: palette.textPrimary,
                        height: 1.5,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                    if (hasStatus || hasSubtitle) ...[
                      const SizedBox(height: 2),
                      Row(
                        children: [
                          if (hasStatus) ...[
                            Container(
                              width: 6,
                              height: 6,
                              decoration: BoxDecoration(
                                color: statusColor,
                                shape: BoxShape.circle,
                              ),
                            ),
                            const SizedBox(width: 5),
                            Text(
                              statusLabel!,
                              style: TextStyle(
                                fontSize: 11,
                                fontWeight: FontWeight.w500,
                                color: statusColor,
                                height: 1.55,
                                fontFamily: 'PingFang SC',
                              ),
                            ),
                          ],
                          if (hasSubtitle) ...[
                            if (hasStatus)
                              Text(
                                '  ·  ',
                                style: TextStyle(
                                  fontSize: 11,
                                  color: palette.textTertiary,
                                  height: 1.55,
                                ),
                              ),
                            Flexible(
                              child: Text(
                                subtitle!,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  fontSize: 11,
                                  color: palette.textSecondary,
                                  height: 1.55,
                                  fontFamily: subtitleMonospace
                                      ? 'monospace'
                                      : 'PingFang SC',
                                ),
                              ),
                            ),
                          ],
                        ],
                      ),
                    ],
                    if ((errorText ?? '').isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        errorText!,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 11,
                          color: Theme.of(context).colorScheme.error,
                          height: 1.55,
                          fontFamily: 'PingFang SC',
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.only(left: 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (busy)
                      const Padding(
                        padding: EdgeInsets.symmetric(vertical: 3),
                        child: SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                      )
                    else if (actionLabel != null && onAction != null)
                      TextButton(
                        key: actionKey,
                        onPressed: onAction,
                        style: TextButton.styleFrom(
                          minimumSize: Size.zero,
                          padding: const EdgeInsets.symmetric(
                            horizontal: 4,
                            vertical: 3,
                          ),
                          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                          visualDensity: VisualDensity.compact,
                        ),
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(maxWidth: 150),
                          child: Text(
                            actionLabel!,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                              color: palette.accentPrimary,
                              fontFamily: 'PingFang SC',
                            ),
                          ),
                        ),
                      ),
                    if ((busy || actionLabel != null) &&
                        navigationLabel != null)
                      const SizedBox(height: 3),
                    if (navigationLabel != null)
                      InkWell(
                        key: navigationKey,
                        onTap: onTap,
                        borderRadius: BorderRadius.circular(8),
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(4, 3, 0, 3),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                navigationLabel!,
                                style: TextStyle(
                                  fontSize: 11,
                                  fontWeight: FontWeight.w500,
                                  color: palette.textSecondary,
                                  fontFamily: 'PingFang SC',
                                ),
                              ),
                              const SizedBox(width: 3),
                              Icon(
                                LucideIcons.chevronRight,
                                size: 16,
                                color: palette.textTertiary,
                              ),
                            ],
                          ),
                        ),
                      )
                    else if (!busy && actionLabel == null)
                      Icon(
                        LucideIcons.chevronRight,
                        size: 18,
                        color: palette.textTertiary,
                      ),
                  ],
                ),
              ),
            ],
          ),
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

({String label, Color color}) _statusPresentation(String status, bool english) {
  return switch (status) {
    'online' => (
      label: english ? 'Available' : '可用',
      color: const Color(0xFF2EAF67),
    ),
    'missing' => (
      label: english ? 'Not installed' : '未安装',
      color: const Color(0xFF98A2B3),
    ),
    'offline' => (
      label: english ? 'Initialization failed' : '初始化失败',
      color: const Color(0xFFE05252),
    ),
    _ => (label: english ? 'Unchecked' : '未检测', color: const Color(0xFFE3A52B)),
  };
}
