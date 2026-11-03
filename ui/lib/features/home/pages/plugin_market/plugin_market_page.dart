import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/models/omni_plugin_item.dart';
import 'package:ui/services/omni_plugin_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_ui.dart';

class PluginMarketPage extends StatefulWidget {
  const PluginMarketPage({super.key});

  @override
  State<PluginMarketPage> createState() => _PluginMarketPageState();
}

class _PluginMarketPageState extends State<PluginMarketPage>
    with FlClashPageLoadMixin {
  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocusNode = FocusNode();
  List<OmniPluginItem> _plugins = const <OmniPluginItem>[];
  bool _loading = true;

  List<OmniPluginItem> get _visiblePlugins {
    final query = _searchController.text.trim().toLowerCase();
    if (query.isEmpty) return _plugins;
    return _plugins
        .where((plugin) {
          return plugin.name.toLowerCase().contains(query) ||
              plugin.description.toLowerCase().contains(query) ||
              plugin.publisher.toLowerCase().contains(query) ||
              plugin.capabilities.any(
                (capability) => capability.toLowerCase().contains(query),
              );
        })
        .toList(growable: false);
  }

  @override
  void initState() {
    super.initState();
    _searchController.addListener(_handleSearchChanged);
    _loadPlugins();
  }

  @override
  void dispose() {
    _searchController
      ..removeListener(_handleSearchChanged)
      ..dispose();
    _searchFocusNode.dispose();
    super.dispose();
  }

  void _handleSearchChanged() {
    if (mounted) setState(() {});
  }

  Future<void> _loadPlugins() async {
    if (!await waitForPageReady() || !mounted) return;
    if (await waitForPageReady() && mounted) setState(() => _loading = true);
    try {
      final plugins = await OmniPluginService.listPlugins();
      if (!await waitForPageReady() || !mounted) return;
      setState(() {
        _plugins = plugins
            .where((plugin) => !plugin.hidden)
            .toList(growable: false);
        _loading = false;
      });
    } catch (_) {
      if (!await waitForPageReady() || !mounted) return;
      setState(() => _loading = false);
      showToast(context.l10n.pluginLoadFailed, type: ToastType.error);
    }
  }

  Future<void> _openPlugin(OmniPluginItem plugin) async {
    final changed = await context.push<bool>(
      '/home/plugin_market/${Uri.encodeComponent(plugin.id)}',
      extra: plugin,
    );
    // Predictive/system back pops the route without a typed result. Refresh
    // for that path as well so changes made on the detail page stay visible.
    if (changed != false && mounted) await _loadPlugins();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.pluginMarketTitle,
        primary: true,
      ),
      body: OmniContentWidth(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : SafeArea(
                top: false,
                bottom: false,
                child: Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 12, 16, 10),
                      child: OmniSettingsSearchField(
                        controller: _searchController,
                        focusNode: _searchFocusNode,
                        hintText: context.l10n.pluginSearchHint,
                      ),
                    ),
                    Expanded(
                      child: RefreshIndicator(
                        onRefresh: _loadPlugins,
                        child: _buildContent(_visiblePlugins),
                      ),
                    ),
                  ],
                ),
              ),
      ),
    );
  }

  Widget _buildContent(List<OmniPluginItem> visiblePlugins) {
    if (_plugins.isEmpty) {
      return _buildStateList(
        icon: LucideIcons.puzzle,
        title: context.l10n.pluginMarketEmpty,
        description: context.l10n.pluginMarketEmptyDesc,
      );
    }
    if (visiblePlugins.isEmpty) {
      return _buildStateList(
        icon: LucideIcons.searchX,
        title: context.l10n.pluginSearchEmpty,
      );
    }

    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: edgeToEdgeScrollPadding(
        context,
        const EdgeInsets.fromLTRB(16, 6, 16, 28),
      ),
      children: [
        OmniSettingsGroup(
          dividerIndent: 64,
          children: [
            for (final plugin in visiblePlugins) _buildPluginTile(plugin),
          ],
        ),
      ],
    );
  }

  Widget _buildPluginTile(OmniPluginItem plugin) {
    final palette = context.omniPalette;
    final status = _statusLabel(plugin);
    final isEnabled = plugin.installed && plugin.enabled;
    final statusColor = isEnabled
        ? palette.accentPrimary
        : palette.textTertiary;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () => _openPlugin(plugin),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  LucideIcons.puzzle,
                  size: 18,
                  color: palette.textPrimary.withValues(alpha: 0.78),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      plugin.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 15,
                        fontWeight: FontWeight.w500,
                        height: 1.38,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      _pluginDescription(plugin),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textSecondary,
                        fontSize: 12,
                        height: 1.45,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      [
                        plugin.publisher,
                        _kindLabel(plugin.kind),
                        if (plugin.downloadSizeBytes > 0)
                          _formatBytes(plugin.downloadSizeBytes),
                      ].join(' · '),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textTertiary,
                        fontSize: 11,
                        height: 1.4,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (isEnabled) ...[
                    Container(
                      width: 6,
                      height: 6,
                      decoration: BoxDecoration(
                        color: statusColor,
                        shape: BoxShape.circle,
                      ),
                    ),
                    const SizedBox(width: 5),
                  ],
                  Text(
                    status,
                    style: TextStyle(
                      color: statusColor,
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                  const SizedBox(width: 4),
                  Icon(
                    LucideIcons.chevronRight,
                    size: 16,
                    color: palette.textTertiary,
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildStateList({
    required IconData icon,
    required String title,
    String? description,
  }) {
    final palette = context.omniPalette;
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: edgeToEdgeScrollPadding(
        context,
        const EdgeInsets.fromLTRB(18, 24, 18, 24),
      ),
      children: [
        SizedBox(
          height: 180,
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(icon, size: 40, color: palette.textTertiary),
                const SizedBox(height: 12),
                Text(
                  title,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 15,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                if (description != null) ...[
                  const SizedBox(height: 6),
                  Text(
                    description,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ],
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

  String _pluginDescription(OmniPluginItem plugin) {
    final presented = _localized(plugin.presentation['description']);
    if (presented.isNotEmpty) return presented;
    return plugin.description.trim().isEmpty
        ? context.l10n.pluginNoDescription
        : plugin.description;
  }

  String _localized(Object? value) {
    if (value is String) return value.trim();
    if (value is! Map) return '';
    final localized = Map<String, dynamic>.from(value);
    final languageCode = Localizations.localeOf(context).languageCode;
    return (localized[languageCode] ?? localized['en'] ?? localized['zh'])
            ?.toString()
            .trim() ??
        '';
  }

  String _text(String zh, String en) =>
      Localizations.localeOf(context).languageCode == 'en' ? en : zh;
}
