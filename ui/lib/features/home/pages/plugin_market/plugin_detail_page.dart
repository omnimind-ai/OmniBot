import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/models/omni_plugin_item.dart';
import 'package:ui/services/omni_plugin_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class PluginDetailPage extends StatefulWidget {
  const PluginDetailPage({
    super.key,
    required this.pluginId,
    this.initialPlugin,
  });

  final String pluginId;
  final OmniPluginItem? initialPlugin;

  @override
  State<PluginDetailPage> createState() => _PluginDetailPageState();
}

class _PluginDetailPageState extends State<PluginDetailPage> {
  OmniPluginItem? _plugin;
  OmniVlmReadiness _vlmReadiness = const OmniVlmReadiness();
  bool _loading = true;
  bool _busy = false;
  bool _changed = false;

  @override
  void initState() {
    super.initState();
    _plugin = widget.initialPlugin;
    _loading = _plugin == null;
    unawaited(_loadPlugin(showLoading: _plugin == null));
    if (_usesVlmReadiness(_plugin)) {
      unawaited(_loadVlmReadiness());
    }
  }

  Future<void> _loadVlmReadiness() async {
    try {
      final readiness = await OmniPluginService.getVlmReadiness();
      if (mounted) setState(() => _vlmReadiness = readiness);
    } catch (_) {}
  }

  Future<void> _loadPlugin({bool showLoading = true}) async {
    if (showLoading && mounted) setState(() => _loading = true);
    try {
      final plugin = await OmniPluginService.getPlugin(widget.pluginId);
      if (!mounted) return;
      setState(() {
        _plugin = plugin;
        _loading = false;
      });
      if (_usesVlmReadiness(plugin)) {
        unawaited(_loadVlmReadiness());
      }
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
      showToast(context.l10n.pluginLoadFailed, type: ToastType.error);
    }
  }

  Future<void> _install() async {
    final plugin = _plugin;
    if (plugin == null) return;
    await _runStateAction(
      () => OmniPluginService.install(plugin.id),
      successMessage: context.l10n.pluginInstalledMsg(plugin.name),
      failureMessage: context.l10n.pluginInstallFailed,
    );
  }

  Future<void> _toggle(bool enabled) async {
    final plugin = _plugin;
    if (plugin == null) return;
    await _runStateAction(
      () => OmniPluginService.setEnabled(plugin.id, enabled),
      successMessage: enabled
          ? context.l10n.pluginEnabledMsg(plugin.name)
          : context.l10n.pluginDisabledMsg(plugin.name),
      failureMessage: context.l10n.pluginToggleFailed,
    );
  }

  Future<void> _update() async {
    final plugin = _plugin;
    if (plugin == null) return;
    await _runStateAction(
      () => OmniPluginService.update(plugin.id),
      successMessage: context.l10n.pluginUpdatedMsg(plugin.name),
      failureMessage: context.l10n.pluginUpdateFailed,
    );
  }

  Future<void> _pinToHome() async {
    final plugin = _plugin;
    if (plugin == null || _busy) return;
    setState(() => _busy = true);
    try {
      final result = await OmniPluginService.pinToHome(plugin.id);
      if (!mounted) return;
      final status = result['status']?.toString() ?? '';
      final message = switch (status) {
        'requested' => _text(
          '已发起添加；若桌面未出现，请允许小万“创建桌面快捷方式”后重试',
          'Request sent. If it does not appear, allow XiaoWan to create home-screen shortcuts and retry',
        ),
        'updated' => _text('桌面入口已更新', 'Home-screen entry updated'),
        'unsupported' => _text(
          '当前桌面不支持快捷入口',
          'This launcher does not support pinned shortcuts',
        ),
        'unavailable' => _text(
          '这个插件还没有可打开的 Dashboard',
          'This plugin does not provide an openable dashboard yet',
        ),
        _ => _text('未能添加桌面入口', 'Unable to add home-screen entry'),
      };
      showToast(
        message,
        type: status == 'requested' || status == 'updated'
            ? ToastType.success
            : ToastType.error,
      );
    } catch (_) {
      if (mounted) {
        showToast(
          _text('未能添加桌面入口', 'Unable to add home-screen entry'),
          type: ToastType.error,
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _openPresentationAction(Map<String, dynamic> action) {
    final route = action['route']?.toString().trim() ?? '';
    if (route.isEmpty) return;
    if (action['navigation'] == 'go') {
      context.go(route);
    } else {
      context.push(route);
    }
  }

  Future<void> _runStateAction(
    Future<OmniPluginItem> Function() action, {
    required String successMessage,
    required String failureMessage,
  }) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final updated = await action();
      if (!mounted) return;
      setState(() {
        _plugin = updated;
        _changed = true;
      });
      showToast(successMessage, type: ToastType.success);
    } catch (_) {
      if (mounted) showToast(failureMessage, type: ToastType.error);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _uninstall() async {
    final plugin = _plugin;
    if (plugin == null || _busy) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(context.l10n.pluginUninstallTitle),
        content: Text(context.l10n.pluginUninstallConfirmMsg(plugin.name)),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(context.l10n.pluginCancel),
          ),
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            style: TextButton.styleFrom(foregroundColor: AppColors.alertRed),
            child: Text(context.l10n.pluginUninstall),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _busy = true);
    try {
      await OmniPluginService.uninstall(plugin.id);
      final updated = await OmniPluginService.getPlugin(plugin.id);
      if (!mounted) return;
      setState(() {
        _plugin = updated;
        _changed = true;
      });
      showToast(
        context.l10n.pluginUninstalledMsg(plugin.name),
        type: ToastType.success,
      );
    } catch (_) {
      if (mounted) {
        showToast(context.l10n.pluginUninstallFailed, type: ToastType.error);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return PopScope(
      // The route already uses PredictiveBackGestureWrapper. It must remain
      // poppable so Android can hand gesture progress to that transition.
      canPop: true,
      child: Scaffold(
        backgroundColor: context.isDarkTheme
            ? palette.pageBackground
            : AppColors.background,
        appBar: CommonAppBar(
          title: context.l10n.pluginDetailTitle,
          primary: true,
          onBackPressed: () => context.pop(_changed),
        ),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : _plugin == null
            ? _buildUnavailable()
            : _buildDetails(_plugin!),
        bottomNavigationBar: _plugin == null
            ? null
            : _buildBottomActions(_plugin!),
      ),
    );
  }

  Widget _buildUnavailable() {
    final palette = context.omniPalette;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.extension_off_outlined,
            size: 48,
            color: palette.textTertiary,
          ),
          const SizedBox(height: 12),
          Text(
            context.l10n.pluginMarketEmpty,
            style: TextStyle(color: palette.textPrimary, fontSize: 16),
          ),
          const SizedBox(height: 8),
          TextButton(
            onPressed: () => _loadPlugin(),
            child: Text(context.l10n.pluginRetry),
          ),
        ],
      ),
    );
  }

  Widget _buildDetails(OmniPluginItem plugin) {
    final palette = context.omniPalette;
    final capabilities = plugin.capabilities;
    final usageItems = _usageItems(plugin);
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 56,
              height: 56,
              decoration: BoxDecoration(
                color: palette.accentPrimary.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(15),
              ),
              child: Icon(
                Icons.extension_rounded,
                size: 29,
                color: palette.accentPrimary,
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    plugin.name,
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: 20,
                      fontWeight: FontWeight.w600,
                      height: 1.3,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${plugin.publisher} · v${plugin.version}',
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                    ),
                  ),
                  const SizedBox(height: 7),
                  Text(
                    _statusLabel(plugin),
                    style: TextStyle(
                      color: plugin.enabled
                          ? palette.accentPrimary
                          : palette.textTertiary,
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 20),
        Text(
          _pluginDescription(plugin),
          style: TextStyle(
            color: palette.textSecondary,
            fontSize: 14,
            height: 1.7,
          ),
        ),
        const SizedBox(height: 26),
        Divider(color: palette.borderSubtle, height: 1),
        const SizedBox(height: 26),
        _buildReadmeHeading(_text('核心功能', 'Core capabilities')),
        const SizedBox(height: 12),
        if (capabilities.isEmpty)
          Text(
            context.l10n.pluginNoCapabilities,
            style: TextStyle(color: palette.textTertiary, fontSize: 13),
          )
        else
          ...capabilities.map(
            (capability) =>
                _buildCapabilityRow(_capabilityLabel(plugin, capability)),
          ),
        if (usageItems.isNotEmpty) ...[
          const SizedBox(height: 28),
          _buildReadmeHeading(_text('工作方式', 'How it works')),
          const SizedBox(height: 10),
          ...usageItems.map(
            (item) => _buildUsageRow(
              _presentationIcon(item['icon']),
              _localized(item['title']),
              _localized(item['description']),
            ),
          ),
          if (plugin.installed && plugin.enabled) ...[
            const SizedBox(height: 24),
            _buildReadmeHeading(_text('开始使用', 'Get started')),
            const SizedBox(height: 12),
            _buildReadyGuide(plugin),
          ],
        ],
        if (plugin.installed && plugin.enabled) ...[
          const SizedBox(height: 20),
          OutlinedButton.icon(
            key: const ValueKey('plugin-add-to-home-screen'),
            onPressed: _busy ? null : _pinToHome,
            icon: const Icon(Icons.add_to_home_screen_rounded),
            label: Text(_text('添加到桌面', 'Add to Home Screen')),
          ),
        ],
        const SizedBox(height: 24),
        Divider(color: palette.borderSubtle, height: 1),
        Theme(
          data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
          child: ExpansionTile(
            key: const ValueKey('plugin-technical-information'),
            tilePadding: EdgeInsets.zero,
            childrenPadding: EdgeInsets.zero,
            title: Text(
              context.l10n.pluginInformationTitle,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 14,
                fontWeight: FontWeight.w600,
              ),
            ),
            children: [
              _buildInfoRow(
                context.l10n.pluginPublisherLabel,
                plugin.publisher,
              ),
              _buildInfoRow(context.l10n.pluginVersionLabel, plugin.version),
              _buildInfoRow(
                context.l10n.pluginTypeLabel,
                _kindLabel(plugin.kind),
              ),
              if (plugin.downloadSizeBytes > 0)
                _buildInfoRow(
                  context.l10n.pluginDownloadSizeLabel,
                  _formatBytes(plugin.downloadSizeBytes),
                ),
              _buildInfoRow(
                context.l10n.pluginInterfaceVersionLabel,
                plugin.interfaceVersion.toString(),
              ),
            ],
          ),
        ),
        if (!plugin.compatible ||
            plugin.errorMessage?.trim().isNotEmpty == true)
          Padding(
            padding: const EdgeInsets.only(top: 18),
            child: Text(
              !plugin.compatible
                  ? context.l10n.pluginIncompatible
                  : plugin.errorMessage!.trim(),
              style: const TextStyle(
                color: AppColors.alertRed,
                fontSize: 12,
                height: 1.5,
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildReadmeHeading(String label) {
    final palette = context.omniPalette;
    return Text(
      label,
      style: TextStyle(
        color: palette.textPrimary,
        fontSize: 18,
        fontWeight: FontWeight.w700,
        height: 1.35,
      ),
    );
  }

  Widget _buildCapabilityRow(String label) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Icon(
              Icons.check_circle_rounded,
              size: 18,
              color: palette.accentPrimary,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              label,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 14,
                fontWeight: FontWeight.w500,
                height: 1.45,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInfoRow(String label, String value) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: TextStyle(color: palette.textSecondary, fontSize: 13),
            ),
          ),
          const SizedBox(width: 20),
          Flexible(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: TextStyle(color: palette.textPrimary, fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildUsageRow(IconData icon, String title, String description) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Icon(icon, size: 18, color: palette.accentPrimary),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  description,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 13,
                    height: 1.55,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildReadyGuide(OmniPluginItem plugin) {
    final palette = context.omniPalette;
    final ready = _map(plugin.presentation['ready']);
    if (ready.isEmpty) return const SizedBox.shrink();
    final usesVlmReadiness = _usesVlmReadiness(plugin);
    final providerReady = !usesVlmReadiness || _vlmReadiness.providerConfigured;
    final providerLabel = _vlmReadiness.providerName.trim().isEmpty
        ? _text('在线 VLM Provider', 'Online VLM provider')
        : _vlmReadiness.providerName.trim();
    final modelSuffix = _vlmReadiness.model.trim().isEmpty
        ? ''
        : ' · ${_vlmReadiness.model.trim()}';
    final providerMessage = !usesVlmReadiness
        ? _localized(ready['message'])
        : providerReady
        ? _vlmReadiness.debugBuild
              ? _text(
                  'Debug APK 已预置 $providerLabel$modelSuffix，无需再配置模型。',
                  'The debug APK includes $providerLabel$modelSuffix; no model setup is required.',
                )
              : _text(
                  '$providerLabel$modelSuffix 已就绪。',
                  '$providerLabel$modelSuffix is ready.',
                )
        : _text(
            '插件能力已安装；在线执行前请先在模型场景中配置 GUI Agent Provider。',
            'Plugin capabilities are installed. Configure a GUI Agent provider before online execution.',
          );

    return Container(
      key: ValueKey(
        ready['key']?.toString().trim().isNotEmpty == true
            ? ready['key'].toString().trim()
            : 'plugin-ready-guide-${plugin.id}',
      ),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: palette.accentPrimary.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: palette.accentPrimary.withValues(alpha: 0.2)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                providerReady
                    ? Icons.check_circle_rounded
                    : Icons.info_outline_rounded,
                size: 20,
                color: palette.accentPrimary,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  _localized(ready['title'], fallback: plugin.name),
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          if (providerMessage.isNotEmpty) ...[
            Text(
              providerMessage,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 12.5,
                height: 1.5,
              ),
            ),
            const SizedBox(height: 10),
          ],
          ..._list(ready['steps']).indexed.map(
            (entry) => _buildGuideStep('${entry.$1 + 1}', _localized(entry.$2)),
          ),
          if (_mapList(ready['actions']).isNotEmpty) ...[
            const SizedBox(height: 12),
            Row(
              children: _mapList(ready['actions']).indexed
                  .expand((entry) {
                    final index = entry.$1;
                    final action = entry.$2;
                    final enabled =
                        action['requiresReadiness'] != true || providerReady;
                    final button = index == 0
                        ? FilledButton.tonalIcon(
                            onPressed: enabled
                                ? () => _openPresentationAction(action)
                                : null,
                            icon: Icon(
                              _presentationIcon(action['icon']),
                              size: 18,
                            ),
                            label: Text(_localized(action['label'])),
                          )
                        : OutlinedButton.icon(
                            onPressed: enabled
                                ? () => _openPresentationAction(action)
                                : null,
                            icon: Icon(
                              _presentationIcon(action['icon']),
                              size: 18,
                            ),
                            label: Text(_localized(action['label'])),
                          );
                    return <Widget>[
                      if (index > 0) const SizedBox(width: 8),
                      Expanded(child: button),
                    ];
                  })
                  .toList(growable: false),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildGuideStep(String index, String text) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(top: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 20,
            child: Text(
              index,
              style: TextStyle(
                color: palette.accentPrimary,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          Expanded(
            child: Text(
              text,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 12.5,
                height: 1.45,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBottomActions(OmniPluginItem plugin) {
    final palette = context.omniPalette;
    final dashboardAction = _map(plugin.presentation['dashboard']);
    final installedAction = dashboardAction.isNotEmpty
        ? dashboardAction
        : _map(plugin.presentation['installedAction']);
    return Material(
      color: context.isDarkTheme ? palette.surfacePrimary : Colors.white,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 14),
          child: plugin.installed
              ? Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (!plugin.required)
                      Row(
                        children: [
                          Expanded(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  context.l10n.pluginEnableTitle,
                                  style: TextStyle(
                                    color: palette.textPrimary,
                                    fontSize: 14,
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  context.l10n.pluginEnableDescription,
                                  style: TextStyle(
                                    color: palette.textTertiary,
                                    fontSize: 11,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          Switch.adaptive(
                            value: plugin.enabled,
                            onChanged: _busy || !plugin.compatible
                                ? null
                                : _toggle,
                          ),
                        ],
                      ),
                    if (!plugin.required) const SizedBox(height: 8),
                    Row(
                      children: [
                        if (installedAction.isNotEmpty) ...[
                          Expanded(
                            child: FilledButton.tonalIcon(
                              onPressed: _busy || !plugin.enabled
                                  ? null
                                  : () => _openPresentationAction(
                                      installedAction,
                                    ),
                              icon: Icon(
                                _presentationIcon(installedAction['icon']),
                              ),
                              label: Text(_localized(installedAction['label'])),
                            ),
                          ),
                          const SizedBox(width: 8),
                        ],
                        TextButton(
                          onPressed: _busy ? null : _update,
                          child: Text(context.l10n.pluginUpdate),
                        ),
                        if (!plugin.required) ...[
                          const SizedBox(width: 4),
                          TextButton(
                            onPressed: _busy ? null : _uninstall,
                            style: TextButton.styleFrom(
                              foregroundColor: AppColors.alertRed,
                            ),
                            child: Text(context.l10n.pluginUninstall),
                          ),
                        ],
                      ],
                    ),
                  ],
                )
              : plugin.required
              ? const SizedBox.shrink()
              : SizedBox(
                  width: double.infinity,
                  height: 46,
                  child: FilledButton(
                    onPressed: _busy || !plugin.compatible ? null : _install,
                    child: _busy
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : Text(context.l10n.pluginInstall),
                  ),
                ),
        ),
      ),
    );
  }

  String _statusLabel(OmniPluginItem plugin) {
    if (!plugin.compatible) return context.l10n.pluginIncompatible;
    if (!plugin.installed) return context.l10n.pluginStatusNotInstalled;
    if (plugin.enabled) return context.l10n.pluginStatusEnabled;
    return context.l10n.pluginStatusInstalled;
  }

  String _kindLabel(String kind) {
    return switch (kind) {
      'bundled_module' => context.l10n.pluginKindBundledModule,
      'companion_app' => context.l10n.pluginKindCompanionApp,
      _ => context.l10n.pluginKindRuntimeBundle,
    };
  }

  String _formatBytes(int bytes) {
    if (bytes <= 0) return '—';
    const megabyte = 1024 * 1024;
    if (bytes >= megabyte) {
      return '${(bytes / megabyte).toStringAsFixed(bytes >= 10 * megabyte ? 0 : 1)} MB';
    }
    return '${(bytes / 1024).toStringAsFixed(0)} KB';
  }

  String _capabilityLabel(OmniPluginItem plugin, String capability) {
    final labels = _map(plugin.presentation['capabilityLabels']);
    return _localized(labels[capability], fallback: capability);
  }

  String _pluginDescription(OmniPluginItem plugin) {
    final presented = _localized(plugin.presentation['description']);
    if (presented.isNotEmpty) return presented;
    return plugin.description.trim().isEmpty
        ? context.l10n.pluginNoDescription
        : plugin.description;
  }

  bool _usesVlmReadiness(OmniPluginItem? plugin) =>
      plugin?.presentation['readiness'] == 'vlm_provider';

  List<Map<String, dynamic>> _usageItems(OmniPluginItem plugin) =>
      _mapList(plugin.presentation['usage']);

  Map<String, dynamic> _map(Object? value) => value is Map
      ? Map<String, dynamic>.from(value)
      : const <String, dynamic>{};

  List<Object?> _list(Object? value) =>
      value is List ? List<Object?>.from(value) : const <Object?>[];

  List<Map<String, dynamic>> _mapList(Object? value) => _list(value)
      .whereType<Map>()
      .map((item) => Map<String, dynamic>.from(item))
      .toList(growable: false);

  String _localized(Object? value, {String fallback = ''}) {
    if (value is String) return value.trim().isEmpty ? fallback : value.trim();
    final localized = _map(value);
    final languageCode = Localizations.localeOf(context).languageCode;
    final text = (localized[languageCode] ?? localized['en'] ?? localized['zh'])
        ?.toString()
        .trim();
    return text?.isNotEmpty == true ? text! : fallback;
  }

  IconData _presentationIcon(Object? value) {
    return switch (value?.toString()) {
      'power' => Icons.power_settings_new_rounded,
      'touch' => Icons.touch_app_rounded,
      'layers' => Icons.layers_outlined,
      'chat' => Icons.chat_bubble_outline_rounded,
      'route' => Icons.route_rounded,
      'dashboard' => Icons.dashboard_outlined,
      _ => Icons.extension_rounded,
    };
  }

  String _text(String zh, String en) =>
      Localizations.localeOf(context).languageCode == 'en' ? en : zh;
}
