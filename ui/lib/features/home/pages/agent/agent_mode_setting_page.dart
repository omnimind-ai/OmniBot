import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/agent_web_action_presenter.dart';
import 'package:ui/services/agent_web_status_service.dart';
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
import 'package:ui/widgets/settings_ui.dart';

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
  bool _checkingNaConnection = false;
  bool _checkNaAfterLoad = false;
  String? _error;
  String? _busyAgentId;
  String? _busyPluginActionKey;
  List<OmniPluginActionItem> _pluginActions = const <OmniPluginActionItem>[];
  Map<String, AgentWebStatus> _webActionStatuses =
      const <String, AgentWebStatus>{};
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
    // Render the cached catalog immediately, then check Na through its existing
    // health endpoint. Local terminal/proot probes remain an explicit action.
    unawaited(_load(checkNaConnection: true));
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
      unawaited(_refreshWebActionStatuses());
    } catch (_) {
      // Agent configuration remains available if the optional action catalog
      // cannot be read during app startup.
    }
  }

  /// Probes the managed Web processes linked from the visible plugin actions.
  /// Each probe failure degrades to [AgentWebStatus.unknown], which renders
  /// the tile without a status badge.
  Future<void> _refreshWebActionStatuses() async {
    final actions = _pluginActions
        .where(AgentWebStatusService.supportsLifecycle)
        .toList(growable: false);
    if (actions.isEmpty) return;
    final statuses = await AgentWebStatusService.queryAll(actions);
    if (!mounted) return;
    setState(() => _webActionStatuses = statuses);
  }

  Future<void> _load({
    bool refresh = false,
    bool checkNaConnection = false,
  }) async {
    // Preserve the entry check if another catalog request supersedes this one.
    if (checkNaConnection) _checkNaAfterLoad = true;
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
      if (_checkNaAfterLoad) {
        _checkNaAfterLoad = false;
        unawaited(_checkNaConnection());
      }
    } catch (error) {
      if (!mounted || requestId != _catalogRequestId) return;
      setState(() {
        _loading = false;
        _refreshing = false;
        // The native ACP catalog is the only source of truth. Keep an
        // already loaded catalog on transient errors, but never invent a
        // second list in Dart.
        _error = formatAgentRuntimeErrorForUser(error, english: _english);
      });
    }
  }

  Future<void> _checkNaConnection() async {
    if (_checkingNaConnection ||
        !(_catalog?.agents.any(
              (agent) => agent.id == 'na-cloud' && agent.enabled,
            ) ??
            false)) {
      return;
    }
    setState(() => _checkingNaConnection = true);
    try {
      await AgentRuntimeService.testAgent('na-cloud', readOnly: true);
      if (mounted) await _load(); // Native Na health owns the persisted result.
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = formatAgentRuntimeErrorForUser(error, english: _english);
      });
    } finally {
      if (mounted) setState(() => _checkingNaConnection = false);
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
        (agent.id == 'na-cloud' && _checkingNaConnection) ||
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
          ? await AgentRuntimeService.prepareAgentInBackground(
              agent.id,
              force: true,
            )
          : await AgentRuntimeService.testAgent(agent.id);
      if (!mounted) return;
      await _load();
      if (!mounted) return;
      final ok = result['ok'] == true;
      final installed =
          result['agent'] is Map &&
          (result['agent'] as Map)['installed'] == true;
      final initializationFailed = prepare && !ok && installed;
      final title = prepare
          ? (ok
                ? _text('助手安装成功', 'Assistant installed')
                : initializationFailed
                ? _text(
                    '安装已完成，但助手未能启动',
                    'Installed, but the assistant could not start',
                  )
                : _text('助手安装失败', 'Assistant installation failed'))
          : (ok
                ? _text('助手检查通过', 'Assistant is ready')
                : _text('助手暂时无法启动', 'Assistant could not start'));
      await _showActionResult(
        agent,
        title,
        ok
            ? _text('助手已准备好，可以开始对话。', 'The assistant is ready to chat.')
            : _actionError(
                result['error'],
                installation: prepare && !installed,
              ),
      );
    } catch (error) {
      if (!mounted) return;
      await _showActionResult(
        agent,
        prepare
            ? _text('安装未完成', 'Installation did not finish')
            : _text('助手暂时无法启动', 'Assistant could not start'),
        _actionError(error, installation: prepare),
      );
    } finally {
      if (!prepare && mounted) setState(() => _busyAgentId = null);
    }
  }

  Future<void> _showActionResult(
    AcpAgentProfile agent,
    String title,
    String message,
  ) async {
    await showSettingsDetailSheet<void>(
      context: context,
      builder: (sheetContext) => SettingsDetailSheet(
        key: ValueKey('agent-check-result-${agent.id}'),
        title: title,
        body: Semantics(
          container: true,
          liveRegion: true,
          label: title,
          child: SelectableText(message),
        ),
      ),
    );
  }

  String _actionError(Object? error, {bool installation = false}) {
    return formatAgentRuntimeErrorForUser(
      error,
      english: _english,
      fallback: installation
          ? _text(
              '安装未完成，请检查网络后重新安装。',
              'Installation did not finish. Check your connection and try again.',
            )
          : _text(
              '助手未能启动，请重试；若仍失败，可重新安装。',
              'The assistant could not start. Try again, or reinstall if the problem continues.',
            ),
    );
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
      showToast(
        formatAgentRuntimeErrorForUser(error, english: _english),
        type: ToastType.error,
      );
    }
  }

  Future<void> _openAgentConfig(AcpAgentProfile agent) async {
    if (!agent.configurationAvailable) return;
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
      await _refreshWebActionStatuses();
    } finally {
      if (mounted && _busyPluginActionKey == key) {
        setState(() => _busyPluginActionKey = null);
      }
    }
  }

  Future<void> _stopPluginAction(OmniPluginActionItem action) async {
    final key = _pluginActionKey(action);
    if (_busyPluginActionKey != null) return;
    setState(() => _busyPluginActionKey = key);
    try {
      await AgentWebActionPresenter.stop(action, english: _english);
      await _refreshWebActionStatuses();
    } finally {
      if (mounted && _busyPluginActionKey == key) {
        setState(() => _busyPluginActionKey = null);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final card = palette.surfacePrimary;
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
            onPressed: _refreshing || _checkingNaConnection
                ? null
                : () => _load(refresh: true, checkNaConnection: true),
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
                        onPressed: () => _load(checkNaConnection: true),
                        child: Text(_text('重试', 'Retry')),
                      ),
                    ],
                  ),
                ),
              )
            : ListView(
                padding: edgeToEdgeScrollPadding(
                  context,
                  const EdgeInsets.fromLTRB(22, 12, 22, 32),
                ),
                children: [
                  SettingsSectionTitle(
                    label: _text('托管 Agent', 'Managed Agents'),
                    subtitle: _text(
                      '选择助手并配置模型。安装完成后，可返回聊天页使用；启动失败时可修改配置或重新安装。',
                      'Choose an assistant and configure its model. Once installed, return to chat to use it. If it cannot start, update its configuration or reinstall it.',
                    ),
                  ),
                  _buildSharedModelSummary(card),
                  const SizedBox(height: 10),
                  _buildSearchField(card),
                  const SizedBox(height: 10),
                  OmniSegmentedSlider<_AgentFilter>(
                    value: _filter,
                    height: 36,
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
                    const SizedBox(height: 18),
                    OmniSettingsGroup(
                      title: _text('预置 Agent', 'Built-in Agents'),
                      translateTitle: false,
                      dividerIndent: 46,
                      children: [
                        for (final agent in managed) _buildAgentTile(agent),
                      ],
                    ),
                  ],
                  if (custom.isNotEmpty) ...[
                    const SizedBox(height: 22),
                    OmniSettingsGroup(
                      title: _text('自定义 Agent', 'Custom Agents'),
                      translateTitle: false,
                      dividerIndent: 46,
                      children: [
                        for (final agent in custom) _buildAgentTile(agent),
                      ],
                    ),
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
                    const SizedBox(height: 22),
                    OmniSettingsGroup(
                      title: _text('本地 Web 界面', 'Local Web interfaces'),
                      translateTitle: false,
                      dividerIndent: 46,
                      children: [
                        for (final action in _pluginActions)
                          _buildPluginActionTile(action),
                      ],
                    ),
                  ],
                  // 远程 PC Bridge：全局共享配置入口（仅配置远程 ACP 连接）。
                  const SizedBox(height: 22),
                  OmniSettingsGroup(
                    title: _text('远程运行', 'Remote runtime'),
                    translateTitle: false,
                    dividerIndent: 46,
                    children: [
                      _FlatTile(
                        leading: Icon(
                          LucideIcons.monitorSmartphone,
                          size: 18,
                          color: palette.textPrimary.withValues(alpha: 0.78),
                        ),
                        title: _text('远程 PC Bridge', 'Remote PC Bridge'),
                        statusColor: _remoteBridgeEnabled
                            ? const Color(0xFF2EAF67)
                            : palette.textTertiary,
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
    return OmniSettingsCard(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      color: card,
      child: Row(
        children: [
          Icon(
            LucideIcons.bot,
            size: 18,
            color: palette.textPrimary.withValues(alpha: 0.78),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              '${_text('默认模型：', 'Default model: ')}$label',
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
    final isDark = context.isDarkTheme;
    return TextField(
      style: TextStyle(color: palette.textPrimary, fontSize: 13.5),
      cursorColor: palette.accentPrimary,
      decoration: InputDecoration(
        hintText: _text('搜索 Agent', 'Search Agents'),
        hintStyle: TextStyle(color: palette.textTertiary, fontSize: 13),
        prefixIcon: Padding(
          padding: const EdgeInsets.only(left: 14, right: 8),
          child: Icon(
            LucideIcons.search,
            size: 17,
            color: palette.textTertiary,
          ),
        ),
        prefixIconConstraints: const BoxConstraints(),
        filled: true,
        fillColor: card,
        isDense: true,
        contentPadding: const EdgeInsets.symmetric(vertical: 11),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(
            color: palette.borderSubtle.withValues(alpha: isDark ? 0.65 : 0.85),
            width: 0.75,
          ),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(
            color: palette.accentPrimary.withValues(alpha: 0.65),
            width: 1.1,
          ),
        ),
      ),
      onChanged: (value) => setState(() => _query = value),
    );
  }

  Widget _buildAgentTile(AcpAgentProfile agent) {
    final palette = context.omniPalette;
    final status = _statusPresentation(
      agent.status,
      _english,
      palette.textTertiary,
    );
    final statusColor = agent.enabled ? status.color : palette.textTertiary;
    final hasError =
        (agent.lastCheckError ?? '').isNotEmpty && agent.status != 'online';
    final canTest =
        agent.enabled && (agent.status != 'missing' || agent.managedAdapter);
    final preparing = _preparingAgentIds.contains(agent.id);
    final checkingConnection = agent.id == 'na-cloud' && _checkingNaConnection;
    final busy = agent.id == _busyAgentId || preparing || checkingConnection;
    final testLabel = agent.managedAdapter
        ? agent.installed == true
              ? _text('重新安装', 'Reinstall')
              : _text('安装', 'Install')
        : _text('重新检测', 'Check again');
    final action = agent.managedAdapter ? _prepare : _test;
    final capabilitySubtitle = _capabilitySubtitle(agent);
    return _FlatTile(
      tileKey: Key('agent-config-${agent.id}'),
      leading: AgentBrandIcon(
        agentId: agent.id,
        size: 18,
        fallbackColor: palette.accentPrimary,
      ),
      title: agent.name,
      statusColor: preparing
          ? const Color(0xFFE3A52B)
          : checkingConnection
          ? palette.textTertiary
          : statusColor,
      statusLabel: preparing
          ? _text('后台安装中', 'Installing in background')
          : !agent.enabled
          ? _text('已停用', 'Disabled')
          : checkingConnection
          ? _text('检测中', 'Checking connection')
          : status.label,
      subtitle:
          capabilitySubtitle ??
          (agent.managedAdapter || agent.id == 'xiaowan-acp'
              ? null
              : agent.description.isNotEmpty
              ? agent.description
              : ([agent.command, ...agent.arguments]).join(' ')),
      subtitleMonospace:
          capabilitySubtitle == null && agent.description.isEmpty,
      errorText: hasError && !preparing && !checkingConnection
          ? _actionError(
              agent.lastCheckError,
              installation: agent.installed == false,
            )
          : null,
      actionLabel: canTest ? testLabel : null,
      actionKey: Key('agent-check-${agent.id}'),
      onAction: canTest ? () => action(agent) : null,
      navigationLabel: agent.configurationAvailable
          ? _text('配置', 'Configure')
          : null,
      navigationKey: Key('agent-navigation-${agent.id}'),
      busy: busy,
      onTap: !agent.configurationAvailable || preparing
          ? null
          : () => _openAgentConfig(agent),
    );
  }

  Widget _buildPluginActionTile(OmniPluginActionItem action) {
    final palette = context.omniPalette;
    final key = _pluginActionKey(action);
    final busy = _busyPluginActionKey == key;
    final disabled = _busyPluginActionKey != null;
    final status = _webActionStatuses[key] ?? AgentWebStatus.unknown;
    final active = status.active;
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
    final statusColor = switch (status) {
      AgentWebStatus.running => const Color(0xFF2EAF67),
      AgentWebStatus.starting => const Color(0xFFE3A52B),
      _ => null,
    };
    final statusLabel = switch (status) {
      AgentWebStatus.running => _text('运行中', 'Running'),
      AgentWebStatus.starting => _text('启动中', 'Starting'),
      _ => null,
    };
    return _FlatTile(
      tileKey: Key('plugin-action-$key'),
      leading: Icon(LucideIcons.globe2, size: 18, color: palette.accentPrimary),
      title: label,
      statusColor: statusColor,
      statusLabel: statusLabel,
      subtitle: description,
      actionLabel: active ? _text('停止', 'Stop') : _text('打开', 'Open'),
      actionColor: active ? const Color(0xFFE05252) : null,
      actionKey: Key(
        active ? 'plugin-action-stop-$key' : 'plugin-action-button-$key',
      ),
      onAction: disabled
          ? null
          : () => active
                ? _stopPluginAction(action)
                : _invokePluginAction(action),
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
          ? 'Plugins: create and install through the assistant'
          : authoring
          ? 'Plugins: create through the assistant'
          : 'Plugins: install through the assistant';
    }
    return authoring && install
        ? '插件：可通过助手创建和安装'
        : authoring
        ? '插件：可通过助手创建'
        : '插件：可通过助手安装';
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
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      _text('启用 Agent', 'Enable Agent'),
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w500,
                        color: context.omniPalette.textPrimary,
                      ),
                    ),
                  ),
                  OmniSettingsSwitch(
                    value: _enabled,
                    onToggle: (value) => setState(() => _enabled = value),
                  ),
                ],
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
    this.actionColor,
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
  final Color? actionColor;
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
        splashColor: palette.accentPrimary.withValues(alpha: 0.06),
        highlightColor: palette.accentPrimary.withValues(alpha: 0.03),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13.5),
          child: Row(
            children: [
              SizedBox(width: 20, height: 20, child: Center(child: leading)),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w500,
                        color: palette.textPrimary,
                        height: 1.38,
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
                              color: actionColor ?? palette.accentPrimary,
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
                    else if (!busy && actionLabel == null && onTap != null)
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

({String label, Color color}) _statusPresentation(
  String status,
  bool english,
  Color inactiveColor,
) {
  return switch (status) {
    'online' => (
      label: english ? 'Available' : '可用',
      color: const Color(0xFF2EAF67),
    ),
    'missing' => (
      label: english ? 'Not installed' : '未安装',
      color: inactiveColor,
    ),
    'offline' => (
      label: english ? 'Could not start' : '启动失败',
      color: const Color(0xFFE05252),
    ),
    _ => (label: english ? 'Unchecked' : '未检测', color: const Color(0xFFE3A52B)),
  };
}
