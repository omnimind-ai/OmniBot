import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/omni_glass.dart';
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
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:ui/widgets/omni_root_toolbar.dart';
import 'package:ui/widgets/omni_segmented_slider.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';
import 'package:ui/widgets/settings_ui.dart';

enum _AgentFilter { all, available, unavailable }

class AgentModeSettingPage extends StatefulWidget {
  const AgentModeSettingPage({
    super.key,
    this.embedded = false,
    this.active = true,
  });

  /// A root tab keeps its own title and actions, without a route back button.
  final bool embedded;

  /// Kept-alive tabs refresh cached catalog/configuration on becoming visible.
  final bool active;

  @override
  State<AgentModeSettingPage> createState() => _AgentModeSettingPageState();
}

class _AgentModeSettingPageState extends State<AgentModeSettingPage>
    with FlClashPageLoadMixin {
  AcpAgentCatalog? _catalog;
  _AgentFilter _filter = _AgentFilter.all;
  String _query = '';
  final _searchController = TextEditingController();
  final _searchFocusNode = FocusNode();
  bool _searchVisible = false;
  bool _loading = true;
  bool _refreshing = false;
  bool _checkingUpdates = false;
  bool _upgradingAll = false;
  String? _error;
  String? _busyAgentId;
  String? _busyPluginActionKey;
  List<OmniPluginActionItem> _pluginActions = const <OmniPluginActionItem>[];
  Map<String, AgentWebStatus> _webActionStatuses =
      const <String, AgentWebStatus>{};
  int _catalogRequestId = 0;
  late Map<String, AgentPackageOperation> _packageOperations;
  StreamSubscription<Map<String, AgentPackageOperation>>?
  _packageOperationSubscription;
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
    _packageOperations = AgentRuntimeService.agentPackageOperations;
    _packageOperationSubscription = AgentRuntimeService
        .agentPackageOperationChanges
        .listen((operations) {
          if (!mounted) return;
          final completed = _packageOperations.keys.any(
            (id) => !operations.containsKey(id),
          );
          // Package operation ownership stays immediate; only its repaint waits.
          _packageOperations = operations;
          unawaited(publishPageState(() {}));
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
  void didUpdateWidget(covariant AgentModeSettingPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.active) _searchFocusNode.unfocus();
    if (widget.active && !oldWidget.active) {
      unawaited(_load());
      unawaited(_loadPluginActions());
      unawaited(_loadSharedModel());
      unawaited(_loadRemoteBridge());
    }
  }

  @override
  void dispose() {
    unawaited(_packageOperationSubscription?.cancel());
    _searchController.dispose();
    _searchFocusNode.dispose();
    super.dispose();
  }

  void _openSearch() => setState(() => _searchVisible = true);

  void _closeSearch() {
    _searchFocusNode.unfocus();
    _searchController.clear();
    setState(() {
      _searchVisible = false;
      _query = '';
    });
  }

  Future<void> _loadSharedModel() =>
      runPageLoad(#sharedModel, _readSharedModel, revalidate: true);

  Future<void> _readSharedModel() async {
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
      if (!await waitForPageReady() || !pageLoadIsCurrent(#sharedModel)) return;
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

  Future<void> _loadPluginActions() =>
      runPageLoad(#pluginActions, _readPluginActions, revalidate: true);

  Future<void> _readPluginActions() async {
    try {
      final actions = await OmniPluginService.listActions();
      if (!await waitForPageReady() || !pageLoadIsCurrent(#pluginActions)) {
        return;
      }
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
  Future<void> _refreshWebActionStatuses() =>
      runPageLoad(#webActionStatuses, _readWebActionStatuses, revalidate: true);

  Future<void> _readWebActionStatuses() async {
    final actions = _pluginActions
        .where(AgentWebStatusService.supportsLifecycle)
        .toList(growable: false);
    if (actions.isEmpty) return;
    final statuses = await AgentWebStatusService.queryAll(actions);
    if (!await waitForPageReady() || !pageLoadIsCurrent(#webActionStatuses)) {
      return;
    }
    setState(() => _webActionStatuses = statuses);
  }

  Future<void> _load({bool refresh = false}) => refresh
      ? _readCatalog(refresh: true)
      : runPageLoad(#catalog, _readCatalog, revalidate: true);

  Future<void> _readCatalog({bool refresh = false}) async {
    if (!await waitForPageReady()) return;
    final requestId = ++_catalogRequestId;
    if (refresh) {
      setState(() => _refreshing = true);
    }
    try {
      final catalog = refresh
          ? await AgentRuntimeService.refreshAgents()
          : await AgentRuntimeService.listAgents();
      if (!await waitForPageReady() ||
          requestId != _catalogRequestId ||
          !pageLoadIsCurrent(#catalog)) {
        return;
      }
      setState(() {
        _catalog = catalog;
        _loading = false;
        _refreshing = false;
        _error = null;
      });
    } catch (error) {
      if (!await waitForPageReady() ||
          requestId != _catalogRequestId ||
          !pageLoadIsCurrent(#catalog)) {
        return;
      }
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

  Future<void> _loadRemoteBridge() =>
      runPageLoad(#remoteBridge, _readRemoteBridge, revalidate: true);

  Future<void> _readRemoteBridge() async {
    try {
      final config = await AgentRuntimeService.readRemoteBridgeConfig();
      if (!await waitForPageReady() || !pageLoadIsCurrent(#remoteBridge)) {
        return;
      }
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

  Future<void> _checkUpdates() async {
    if (_checkingUpdates || _packageOperations.isNotEmpty) return;
    setState(() => _checkingUpdates = true);
    try {
      final catalog = await AgentRuntimeService.checkAgentUpdates();
      if (!mounted) return;
      setState(() {
        _catalog = catalog;
        _error = null;
      });
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            _text('检查更新失败，请稍后重试。', 'Update check failed. Try again later.'),
          ),
        ),
      );
    } finally {
      if (mounted) setState(() => _checkingUpdates = false);
    }
  }

  List<AcpAgentProfile> get _updatableAgents =>
      (_catalog?.agents ?? const <AcpAgentProfile>[])
          .where(
            (agent) =>
                agent.enabled &&
                agent.managedAdapter &&
                agent.installed != false &&
                agent.packageVersion?.updateAvailable == true,
          )
          .toList(growable: false);

  Future<void> _upgradeAll() async {
    final agents = _updatableAgents;
    if (agents.isEmpty || _packageOperations.isNotEmpty || _upgradingAll) {
      return;
    }
    final english = _english;
    final distributionId = _catalog?.distributionId;
    String text(String zh, String en) => english ? en : zh;
    String failure(Object? error) => formatAgentRuntimeErrorForUser(
      error,
      english: english,
      fallback: text(
        '升级未完成，请检查网络后重试。',
        'Update did not finish. Check your connection and try again.',
      ),
    );
    setState(() => _upgradingAll = true);
    // Schedule the complete batch before awaiting it; the service owns its
    // lifetime and serial execution even if this settings page is closed.
    final results = await Future.wait(
      agents.map((agent) async {
        try {
          final result = await AgentRuntimeService.prepareAgentInBackground(
            agent.id,
            force: true,
            distributionId: distributionId,
          );
          final version = _resultVersion(result);
          final updated =
              version?.installedVersion != null &&
              version?.targetVersion != null &&
              version?.error == null &&
              version?.updateAvailable == false;
          final ready = result['ok'] == true;
          final label = updated
              ? ready
                    ? text('已升级', 'Updated')
                    : text('已升级，启动未完成', 'Updated; startup incomplete')
              : text('升级未完成', 'Update incomplete');
          return '${agent.name}：$label${updated ? ' ${version!.installedVersion}' : ''}'
              '${updated && ready ? '' : '\n${failure(result['error'])}'}';
        } catch (error) {
          return '${agent.name}：${text('升级失败', 'Update failed')}\n${failure(error)}';
        }
      }),
    );
    if (!mounted) return;
    setState(() => _upgradingAll = false);
    await _load();
    if (!mounted) return;
    await _showActionResult(
      agents.first,
      _text('升级结果', 'Update results'),
      results.join('\n\n'),
    );
  }

  AgentPackageVersion? _resultVersion(Map<String, dynamic> result) {
    final agent = result['agent'];
    return agent is Map && agent['packageVersion'] is Map
        ? AgentPackageVersion.fromMap(agent['packageVersion'] as Map)
        : null;
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
        _packageOperations.containsKey(agent.id) ||
        !agent.enabled) {
      return;
    }
    await _runAgentAction(agent, prepare: false);
  }

  void _prepare(AcpAgentProfile agent) {
    if (_packageOperations.containsKey(agent.id) || !agent.enabled) return;
    unawaited(
      _runAgentAction(
        agent,
        prepare: true,
        upgrade: agent.packageVersion?.updateAvailable == true,
      ),
    );
  }

  Future<void> _uninstall(AcpAgentProfile agent) async {
    if (!agent.uninstallable ||
        _packageOperations.containsKey(agent.id) ||
        _busyAgentId == agent.id) {
      return;
    }
    final distributionId = _catalog?.distributionId;
    if (distributionId == null || distributionId.isEmpty) {
      await _showActionResult(
        agent,
        _text('无法卸载', 'Could not uninstall'),
        _text('请刷新 Agent 列表后重试。', 'Refresh the Agent list and try again.'),
      );
      return;
    }
    final confirmed = await showSettingsDetailSheet<bool>(
      context: context,
      builder: (sheetContext) => SettingsDetailSheet(
        key: ValueKey('agent-uninstall-confirm-${agent.id}'),
        title: _text('卸载 ${agent.name}？', 'Uninstall ${agent.name}?'),
        body: Text(
          _text(
            '卸载当前终端环境中的程序，保留配置、登录信息和历史记录。',
            'Uninstall the program from the current terminal environment. Keep configuration, sign-in information, and history.',
          ),
        ),
        actions: [
          TextButton(
            key: Key('agent-uninstall-cancel-${agent.id}'),
            style: settingsDetailSheetActionStyle(sheetContext),
            onPressed: () => Navigator.of(sheetContext).pop(false),
            child: Text(_text('取消', 'Cancel')),
          ),
          TextButton(
            key: Key('agent-uninstall-confirm-action-${agent.id}'),
            style: settingsDetailSheetActionStyle(
              sheetContext,
              foregroundColor: Theme.of(sheetContext).colorScheme.error,
            ),
            onPressed: () => Navigator.of(sheetContext).pop(true),
            child: Text(_text('卸载', 'Uninstall')),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    await _runUninstall(agent, distributionId);
  }

  Future<void> _runUninstall(
    AcpAgentProfile agent,
    String distributionId,
  ) async {
    // The service owns the queued operation after this page is disposed.
    // A retry stays attached to the originally confirmed distribution.
    while (mounted) {
      if (_packageOperations.containsKey(agent.id)) return;
      var ok = false;
      Object? failure;
      try {
        final result = await AgentRuntimeService.uninstallAgentInBackground(
          agent.id,
          distributionId: distributionId,
        );
        ok = result['ok'] == true;
        failure = ok
            ? null
            : PlatformException(
                code: 'agent_uninstall_failed',
                message: result['error']?.toString(),
                details: result,
              );
      } catch (error) {
        failure = error;
      }
      if (!mounted) return;
      await _load();
      if (!mounted) return;
      final retry = await showSettingsDetailSheet<bool>(
        context: context,
        builder: (sheetContext) => SettingsDetailSheet(
          key: ValueKey('agent-uninstall-result-${agent.id}'),
          title: ok
              ? _text('助手已卸载', 'Assistant uninstalled')
              : _text('卸载未完成', 'Uninstall did not finish'),
          body: SelectableText(
            ok
                ? _text(
                    '${agent.name} 已卸载。配置、登录信息和历史记录已保留。',
                    '${agent.name} was uninstalled. Configuration, sign-in information, and history were kept.',
                  )
                : formatAgentRuntimeErrorForUser(
                    failure,
                    english: _english,
                    fallback: _text(
                      '卸载未完成，请重试。',
                      'Uninstall did not finish. Try again.',
                    ),
                  ),
          ),
          actions: [
            TextButton(
              key: Key('agent-uninstall-result-close-${agent.id}'),
              style: settingsDetailSheetActionStyle(sheetContext),
              onPressed: () => Navigator.of(sheetContext).pop(false),
              child: Text(_text('关闭', 'Close')),
            ),
            if (!ok)
              TextButton(
                key: Key('agent-uninstall-retry-${agent.id}'),
                style: settingsDetailSheetActionStyle(sheetContext),
                onPressed: () => Navigator.of(sheetContext).pop(true),
                child: Text(_text('重试', 'Retry')),
              ),
          ],
        ),
      );
      if (retry != true || !mounted) return;
    }
  }

  Future<void> _runAgentAction(
    AcpAgentProfile agent, {
    required bool prepare,
    bool upgrade = false,
  }) async {
    if (!prepare) setState(() => _busyAgentId = agent.id);
    try {
      final result = prepare
          ? await AgentRuntimeService.prepareAgentInBackground(
              agent.id,
              force: true,
              distributionId: _catalog?.distributionId,
            )
          : await AgentRuntimeService.testAgent(agent.id);
      if (!mounted) return;
      await _load();
      if (!mounted) return;
      final version = _resultVersion(result);
      final verifiedUpgrade =
          version?.installedVersion != null &&
          version?.targetVersion != null &&
          version?.error == null &&
          version?.updateAvailable == false;
      final ok = result['ok'] == true && (!upgrade || verifiedUpgrade);
      final installed =
          result['agent'] is Map &&
          (result['agent'] as Map)['installed'] == true;
      final initializationFailed = prepare && !ok && installed;
      final title = upgrade
          ? (ok
                ? _text('助手升级成功', 'Assistant updated')
                : verifiedUpgrade
                ? _text(
                    '版本已升级，助手未能启动',
                    'Updated, but the assistant could not start',
                  )
                : _text('升级未完成', 'Update incomplete'))
          : prepare
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
        upgrade && ok
            ? _text(
                '已安装 ${version!.installedVersion}。已运行的助手将在下次启动时使用新版本。',
                'Installed ${version.installedVersion}. Running assistants will use it on their next launch.',
              )
            : upgrade && result['ok'] == true && !verifiedUpgrade
            ? _text(
                '尚未检测到目标版本，请检查网络后重试。',
                'The target version was not detected. Check your connection and try again.',
              )
            : ok
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
        upgrade
            ? _text('升级未完成', 'Update incomplete')
            : prepare
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
    final result = await showOmniGlassCardSheet<AcpAgentProfile>(
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
    if (_packageOperations.containsKey(agent.id)) return;
    if (agent.id == 'na-cloud') {
      await GoRouterManager.pushForResult<Object?>('/home/na/personal');
      if (mounted) await _load();
      return;
    }
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
    final agents = _visibleAgents;
    final managed = agents.where((agent) => agent.builtIn).toList()
      ..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    final custom = agents.where((agent) => !agent.builtIn).toList()
      ..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    return PopScope(
      canPop: !widget.active || !_searchVisible,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && widget.active && _searchVisible) _closeSearch();
      },
      child: Scaffold(
        backgroundColor: palette.pageBackground,
        appBar: OmniRootToolbar(
          title: 'Agents',
          primary: true,
          showBackButton: !widget.embedded,
          search: OmniRootToolbarSearch(
            searching: _searchVisible,
            onSearch: _openSearch,
            onClose: _closeSearch,
            controller: _searchController,
            focusNode: _searchFocusNode,
            onChanged: (value) => setState(() => _query = value),
            hintText: _text('搜索 Agent', 'Search Agents'),
            searchTooltip: _text('搜索 Agent', 'Search Agents'),
            searchButtonKey: const Key('agent-search-button'),
            closeButtonKey: const Key('agent-close-search'),
            fieldKey: const Key('agent-contact-search'),
          ),
          actions: [
            IconButton(
              tooltip: _text('添加自定义 ACP Agent', 'Add custom ACP Agent'),
              onPressed: _addCustomAgent,
              icon: const Icon(LucideIcons.plus),
            ),
          ],
        ),
        body: OmniContentWidth(
          child: SafeArea(
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
                : _buildList(managed: managed, custom: custom),
          ),
        ),
      ),
    );
  }

  Widget _buildList({
    required List<AcpAgentProfile> managed,
    required List<AcpAgentProfile> custom,
  }) {
    final sections = <Widget>[
      OmniSegmentedSlider<_AgentFilter>(
        value: _filter,
        height: 38 * MediaQuery.textScalerOf(context).scale(12.5) / 12.5,
        keyPrefix: 'agent-filter',
        options: [
          OmniSegmentedOption(
            value: _AgentFilter.all,
            label: '${_text('全部', 'All')} ${_countFor(_AgentFilter.all)}',
          ),
          OmniSegmentedOption(
            value: _AgentFilter.available,
            label:
                '${_text('可用', 'Available')} ${_countFor(_AgentFilter.available)}',
          ),
          OmniSegmentedOption(
            value: _AgentFilter.unavailable,
            label:
                '${_text('不可用', 'Unavailable')} ${_countFor(_AgentFilter.unavailable)}',
          ),
        ],
        onChanged: (value) => setState(() => _filter = value),
      ),
      if (managed.isNotEmpty)
        _buildContactSection(
          title: _text('预置 Agent', 'Built-in Agents'),
          agents: managed,
        ),
      if (custom.isNotEmpty)
        _buildContactSection(
          title: _text('自定义 Agent', 'Custom Agents'),
          agents: custom,
        ),
      if (managed.isEmpty && custom.isEmpty)
        OmniSettingsCard(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 32),
          child: Column(
            children: [
              Icon(
                LucideIcons.searchX,
                size: 24,
                color: context.omniPalette.textTertiary,
              ),
              const SizedBox(height: 12),
              Text(
                _text('没有匹配的 Agent', 'No matching Agents'),
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 15,
                  color: context.omniPalette.textSecondary,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                _text('换个关键词或筛选条件试试', 'Try a different keyword or filter'),
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 12,
                  height: 1.45,
                  color: context.omniPalette.textTertiary,
                ),
              ),
            ],
          ),
        ),
      if (!_searchVisible) ...[
        _buildGeneralSettings(),
        if (_pluginActions.isNotEmpty)
          OmniSettingsGroup(
            title: 'Agent WebUI',
            translateTitle: false,
            children: [
              for (final action in _pluginActions)
                _buildPluginActionTile(action),
            ],
          ),
        _buildMaintenanceSettings(),
      ],
    ];
    return ListView.separated(
      key: const PageStorageKey('agent-settings-list'),
      padding: edgeToEdgeScrollPadding(
        context,
        const EdgeInsets.fromLTRB(22, 10, 22, 32),
      ),
      itemCount: sections.length,
      separatorBuilder: (_, _) => const SizedBox(height: 26),
      itemBuilder: (_, index) => sections[index],
    );
  }

  Widget _buildContactSection({
    required String title,
    required List<AcpAgentProfile> agents,
  }) => OmniSettingsGroup(
    title: title,
    translateTitle: false,
    dividerIndent: 76,
    children: [for (final agent in agents) _buildAgentTile(agent)],
  );

  Widget _buildGeneralSettings() => OmniSettingsGroup(
    title: _text('通用配置', 'General'),
    translateTitle: false,
    children: [
      OmniSettingsTile(
        tileKey: const Key('agent-shared-model'),
        icon: LucideIcons.bot,
        title: _text('默认模型', 'Default model'),
        subtitle: _sharedModelLabel.isEmpty
            ? _text('尚未配置统一模型', 'No shared model configured')
            : _sharedModelLabel,
        translateText: false,
        onTap: () async {
          await GoRouterManager.pushForResult<Object?>(
            '/home/scene_model_setting',
          );
          if (mounted) await _loadSharedModel();
        },
      ),
      OmniSettingsTile(
        tileKey: const Key('agent-remote-bridge'),
        icon: LucideIcons.monitorSmartphone,
        title: _text('远程 PC Bridge', 'Remote PC Bridge'),
        subtitle: _remoteBridgeEnabled
            ? _text('已启用', 'Enabled')
            : _text('未启用', 'Not enabled'),
        translateText: false,
        onTap: () async {
          await GoRouterManager.pushForResult<Object?>(
            '/home/remote_codex_setting',
          );
          if (mounted) await _loadRemoteBridge();
        },
      ),
    ],
  );

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
    final operation = _packageOperations[agent.id];
    final changingPackage = operation != null;
    final queued = AgentRuntimeService.queuedAgentIds.contains(agent.id);
    final busy = agent.id == _busyAgentId || changingPackage;
    final canUninstall =
        agent.builtIn &&
        agent.managedAdapter &&
        agent.uninstallable &&
        (agent.installed == true ||
            agent.packageVersion?.installedVersion != null);
    final testLabel = agent.managedAdapter
        ? agent.packageVersion?.updateAvailable == true
              ? _text('升级', 'Update')
              : agent.installed == true ||
                    agent.packageVersion?.installedVersion != null
              ? _text('重新安装', 'Reinstall')
              : _text('安装', 'Install')
        : _text('重新检测', 'Check again');
    final action = agent.managedAdapter ? _prepare : _test;
    final needsAttention =
        agent.status != 'online' ||
        agent.packageVersion?.updateAvailable == true ||
        agent.packageVersion?.error != null;
    return _AgentContactTile(
      tileKey: Key('agent-config-${agent.id}'),
      leading: AgentBrandIcon(
        agentId: agent.id,
        size: 32,
        fallbackColor: palette.accentPrimary,
      ),
      title: agent.name,
      statusColor: changingPackage ? const Color(0xFFE3A52B) : statusColor,
      statusLabel: changingPackage
          ? operation == AgentPackageOperation.uninstall
                ? queued
                      ? _text('等待卸载', 'Queued for uninstall')
                      : _text('卸载中', 'Uninstalling')
                : queued
                ? _text('等待安装 / 升级', 'Queued')
                : _text('后台安装 / 升级中', 'Installing / updating')
          : !agent.enabled
          ? _text('已停用', 'Disabled')
          : status.label,
      subtitle: !agent.builtIn && agent.description.isNotEmpty
          ? agent.description
          : null,
      detailText:
          agent.packageVersion?.updateAvailable == true ||
              agent.packageVersion?.error != null
          ? _versionSummary(agent)
          : null,
      detailIsError: agent.packageVersion?.error != null,
      errorText: hasError && !changingPackage
          ? _actionError(
              agent.lastCheckError,
              installation: agent.installed == false,
            )
          : null,
      actionLabel: canTest ? testLabel : null,
      actionKey: Key('agent-check-${agent.id}'),
      quietAction: !needsAttention,
      onAction: canTest ? () => action(agent) : null,
      secondaryActionLabel: canUninstall ? _text('卸载', 'Uninstall') : null,
      secondaryActionKey: Key('agent-uninstall-${agent.id}'),
      onSecondaryAction: canUninstall ? () => _uninstall(agent) : null,
      navigationLabel: agent.id == 'na-cloud' || agent.configurationAvailable
          ? _text('配置', 'Configure')
          : null,
      navigationKey: Key('agent-navigation-${agent.id}'),
      busy: busy,
      onTap: (agent.id != 'na-cloud' && !agent.configurationAvailable) || busy
          ? null
          : () => _openAgentConfig(agent),
    );
  }

  String? _versionSummary(AcpAgentProfile agent) {
    if (!agent.managedAdapter ||
        (agent.status == 'missing' && agent.installed == false)) {
      return null;
    }
    final version = agent.packageVersion;
    if (version == null) return _text('版本未检测', 'Version not checked');
    final current = version.installedVersion;
    final prefix = current == null ? '' : '$current · ';
    if (version.error != null) {
      return '$prefix${version.error == 'local_probe_failed' ? _text('无法读取本地版本', 'Could not read installed version') : _text('检查更新失败，点击重试', 'Update check failed; try again')}';
    }
    if (version.updateAvailable) {
      return '$prefix${_text('有新版本', 'Update available')} ${version.targetVersion}';
    }
    if (version.pinned) {
      final upstream = version.latestVersion;
      return '$prefix${_text('版本由应用管理', 'Version managed by the app')}'
          '${upstream != null && upstream != current ? '\n${_text('上游版本', 'Upstream version')} $upstream' : ''}';
    }
    if (current == null) {
      return version.latestVersion == null
          ? null
          : '${_text('最新版本', 'Latest')} ${version.latestVersion}';
    }
    return version.checkedAt == null
        ? current
        : '$current · ${_text('无可用更新', 'No updates')}';
  }

  Widget _buildMaintenanceSettings() {
    final times =
        (_catalog?.agents ?? const <AcpAgentProfile>[])
            .map((agent) => agent.packageVersion?.checkedAt)
            .whereType<int>()
            .toList()
          ..sort();
    final checked = times.isEmpty
        ? null
        : DateTime.fromMillisecondsSinceEpoch(times.last);
    final timeLabel = checked == null
        ? _text('尚未检查更新', 'Updates not checked')
        : '${_text('上次检查', 'Last checked')} ${MaterialLocalizations.of(context).formatShortDate(checked)} '
              '${TimeOfDay.fromDateTime(checked).format(context)}';
    return OmniSettingsGroup(
      title: _text('维护', 'Maintenance'),
      translateTitle: false,
      children: [
        OmniSettingsTile(
          tileKey: const Key('agent-refresh'),
          icon: LucideIcons.refreshCw,
          title: _text('刷新检测', 'Refresh detection'),
          translateText: false,
          showChevron: false,
          trailing: _refreshing ? const _AgentBusyIndicator() : null,
          onTap:
              _refreshing || _checkingUpdates || _packageOperations.isNotEmpty
              ? null
              : () => _load(refresh: true),
        ),
        OmniSettingsTile(
          tileKey: const Key('agent-check-updates'),
          icon: LucideIcons.download,
          title: _checkingUpdates
              ? _text('正在检查', 'Checking')
              : _text('检查更新', 'Check updates'),
          subtitle: timeLabel,
          translateText: false,
          showChevron: false,
          trailing: _checkingUpdates ? const _AgentBusyIndicator() : null,
          onTap:
              _checkingUpdates || _refreshing || _packageOperations.isNotEmpty
              ? null
              : _checkUpdates,
        ),
        if (_updatableAgents.isNotEmpty || _upgradingAll)
          OmniSettingsTile(
            tileKey: const Key('agent-upgrade-all'),
            icon: LucideIcons.arrowUpCircle,
            title:
                '${_text('全部升级', 'Update all')} (${_updatableAgents.length})',
            translateText: false,
            showChevron: false,
            trailing: _upgradingAll ? const _AgentBusyIndicator() : null,
            onTap:
                _checkingUpdates ||
                    _packageOperations.isNotEmpty ||
                    _upgradingAll
                ? null
                : _upgradeAll,
          ),
        if (_catalog?.agents.any(_hasAgentDetails) == true)
          OmniSettingsTile(
            tileKey: const Key('agent-version-details'),
            icon: LucideIcons.info,
            title: _text('版本与能力', 'Versions & capabilities'),
            translateText: false,
            onTap: _showVersionDetails,
          ),
      ],
    );
  }

  bool _hasAgentDetails(AcpAgentProfile agent) =>
      agent.managedAdapter || _capabilitySubtitle(agent) != null;

  void _showVersionDetails() {
    final agents = (_catalog?.agents ?? const <AcpAgentProfile>[])
        .where(_hasAgentDetails)
        .toList();
    showSettingsDetailSheet<void>(
      context: context,
      builder: (context) => SettingsDetailSheet(
        title: _text('版本与能力', 'Versions & capabilities'),
        body: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (var index = 0; index < agents.length; index++) ...[
              OmniSettingsTile(
                title: agents[index].name,
                subtitle:
                    _versionSummary(agents[index]) ??
                    _capabilitySubtitle(agents[index]),
                bottomNote: _versionSummary(agents[index]) == null
                    ? null
                    : _capabilitySubtitle(agents[index]),
                showChevron: false,
                translateText: false,
                padding: const EdgeInsets.symmetric(vertical: 12),
              ),
              if (index < agents.length - 1)
                Divider(
                  height: 1,
                  thickness: 0.65,
                  color: context.omniPalette.borderSubtle,
                ),
            ],
          ],
        ),
      ),
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
    final statusLabel = switch (status) {
      AgentWebStatus.running => _text('运行中', 'Running'),
      AgentWebStatus.starting => _text('启动中', 'Starting'),
      _ => null,
    };
    return OmniSettingsTile(
      tileKey: Key('plugin-action-$key'),
      icon: LucideIcons.globe2,
      title: label,
      subtitle: description,
      bottomNote: statusLabel,
      translateText: false,
      trailing: busy
          ? const _AgentBusyIndicator()
          : TextButton(
              key: Key(
                active
                    ? 'plugin-action-stop-$key'
                    : 'plugin-action-button-$key',
              ),
              onPressed: disabled
                  ? null
                  : () => active
                        ? _stopPluginAction(action)
                        : _invokePluginAction(action),
              style: TextButton.styleFrom(
                foregroundColor: active
                    ? Theme.of(context).colorScheme.error
                    : palette.accentPrimary,
                minimumSize: const Size(40, 40),
                padding: const EdgeInsets.symmetric(horizontal: 4),
                textStyle: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w400,
                ),
              ),
              child: Text(active ? _text('停止', 'Stop') : _text('打开', 'Open')),
            ),
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
    return OmniGlassAlertDialog(
      title: Text(_text('添加自定义 ACP Agent', 'Add custom ACP Agent')),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          padding: const EdgeInsets.only(top: 8),
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

/// The contact identity follows the settings group's spacing and typography.
/// Healthy rows keep maintenance quiet; attention states retain explicit actions.
class _AgentContactTile extends StatelessWidget {
  const _AgentContactTile({
    required this.leading,
    required this.title,
    required this.onTap,
    this.tileKey,
    this.statusColor,
    this.statusLabel,
    this.subtitle,
    this.errorText,
    this.detailText,
    this.detailIsError = false,
    this.actionLabel,
    this.actionKey,
    this.onAction,
    this.quietAction = false,
    this.secondaryActionLabel,
    this.secondaryActionKey,
    this.onSecondaryAction,
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
  final String? errorText;
  final String? detailText;
  final bool detailIsError;
  final String? actionLabel;
  final Key? actionKey;
  final VoidCallback? onAction;
  final bool quietAction;
  final String? secondaryActionLabel;
  final Key? secondaryActionKey;
  final VoidCallback? onSecondaryAction;
  final String? navigationLabel;
  final Key? navigationKey;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final showAction = actionLabel != null && onAction != null;
    final showSecondaryAction = secondaryActionLabel != null && !busy;
    final details = <Widget>[
      if ((subtitle ?? '').isNotEmpty)
        Text(
          subtitle!,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 12,
            height: 1.45,
            color: palette.textSecondary,
          ),
        ),
      if ((detailText ?? '').isNotEmpty)
        Text(
          detailText!,
          style: TextStyle(
            fontSize: 12,
            height: 1.45,
            color: detailIsError
                ? Theme.of(context).colorScheme.error
                : palette.textSecondary,
          ),
        ),
      if ((errorText ?? '').isNotEmpty)
        Text(
          errorText!,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 12,
            height: 1.45,
            color: Theme.of(context).colorScheme.error,
          ),
        ),
    ];
    final maintenanceActions = <Widget>[
      if (!quietAction && showAction && !busy)
        _actionButton(
          key: actionKey,
          label: actionLabel!,
          color: palette.accentPrimary,
          onPressed: onAction,
        ),
      if (showSecondaryAction)
        _actionButton(
          key: secondaryActionKey,
          label: secondaryActionLabel!,
          color: Theme.of(context).colorScheme.error,
          onPressed: onSecondaryAction,
        ),
    ];
    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: tileKey,
        onTap: onTap,
        child: LayoutBuilder(
          builder: (context, constraints) {
            // Use the row's available space, including when this page is in a
            // split window. Larger text keeps maintenance on its own line.
            final inlineActions =
                constraints.maxWidth >= OmniLayoutBreakpoints.medium &&
                MediaQuery.textScalerOf(context).scale(15) <= 19.5;
            return Padding(
              padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      SizedBox(
                        key: ValueKey('agent-avatar-$title'),
                        width: 44,
                        height: 44,
                        child: Center(child: leading),
                      ),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              title,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.w400,
                                color: palette.textPrimary,
                                height: 1.38,
                              ),
                            ),
                            if (statusLabel != null) ...[
                              const SizedBox(height: 4),
                              Row(
                                children: [
                                  Container(
                                    width: 5,
                                    height: 5,
                                    decoration: BoxDecoration(
                                      color: statusColor,
                                      shape: BoxShape.circle,
                                    ),
                                  ),
                                  const SizedBox(width: 6),
                                  Expanded(
                                    child: Text(
                                      statusLabel!,
                                      style: TextStyle(
                                        fontSize: 12,
                                        height: 1.45,
                                        color: palette.textSecondary,
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                            ],
                            if (inlineActions && details.isNotEmpty) ...[
                              const SizedBox(height: 6),
                              ...details,
                            ],
                          ],
                        ),
                      ),
                      if (inlineActions && maintenanceActions.isNotEmpty) ...[
                        const SizedBox(width: 16),
                        Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            for (
                              var index = 0;
                              index < maintenanceActions.length;
                              index++
                            ) ...[
                              if (index > 0) const SizedBox(width: 8),
                              maintenanceActions[index],
                            ],
                          ],
                        ),
                      ],
                      if (busy) ...[
                        const SizedBox(width: 10),
                        const SizedBox.square(
                          dimension: 48,
                          child: Center(child: _AgentBusyIndicator()),
                        ),
                      ] else if (quietAction && showAction)
                        OmniCircleButton(
                          key: actionKey,
                          tooltip: actionLabel!,
                          onPressed: onAction,
                          icon: LucideIcons.rotateCw,
                          size: 40,
                          tapTargetSize: MaterialTapTargetSize.padded,
                          foregroundColor: palette.textTertiary,
                        ),
                      if (navigationLabel != null)
                        IconButton(
                          key: navigationKey,
                          onPressed: onTap,
                          tooltip: navigationLabel,
                          icon: const Icon(LucideIcons.chevronRight, size: 18),
                          style: IconButton.styleFrom(
                            foregroundColor: palette.textTertiary,
                            minimumSize: const Size.square(48),
                            visualDensity: VisualDensity.standard,
                            padding: EdgeInsets.zero,
                            tapTargetSize: MaterialTapTargetSize.padded,
                          ),
                        ),
                    ],
                  ),
                  if (!inlineActions &&
                      (details.isNotEmpty || maintenanceActions.isNotEmpty))
                    Padding(
                      padding: const EdgeInsets.only(left: 58, top: 6),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          ...details,
                          if (maintenanceActions.isNotEmpty)
                            Wrap(
                              spacing: 12,
                              crossAxisAlignment: WrapCrossAlignment.center,
                              children: maintenanceActions,
                            ),
                        ],
                      ),
                    ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _actionButton({
    required String label,
    required Key? key,
    required Color color,
    required VoidCallback? onPressed,
  }) => TextButton(
    key: key,
    onPressed: onPressed,
    style: TextButton.styleFrom(
      foregroundColor: color,
      minimumSize: const Size(48, 48),
      visualDensity: VisualDensity.standard,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
      textStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w400),
    ),
    child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis),
  );
}

class _AgentBusyIndicator extends StatelessWidget {
  const _AgentBusyIndicator();

  @override
  Widget build(BuildContext context) => const SizedBox(
    width: 18,
    height: 18,
    child: CircularProgressIndicator(strokeWidth: 2),
  );
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
