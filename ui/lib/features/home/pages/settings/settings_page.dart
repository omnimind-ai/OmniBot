import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/mcp_server_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/workspace_memory_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/omni_root_toolbar.dart';
import 'package:ui/widgets/navigation/open_container.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';
import 'package:ui/widgets/settings_ui.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, this.embedded = false, this.active = true});

  final bool embedded;
  final bool active;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> with FlClashPageLoadMixin {
  bool _mcpEnabled = false;
  bool _mcpBusy = false;
  McpServerInfo? _mcpInfo;
  bool _workspaceMemoryConfigured = false;

  @override
  void initState() {
    super.initState();
    // 同步读取缓存初始值，首帧即渲染真实状态，避免一帧加载占位符闪烁。
    _mcpEnabled =
        StorageService.getBool(StorageService.kMcpLocalServiceEnabledKey) ??
        false;
    _workspaceMemoryConfigured =
        StorageService.getBool(StorageService.kWorkspaceMemoryConfiguredKey) ??
        false;
    _loadMcpServerState();
    _loadWorkspaceMemoryState();
  }

  @override
  void didUpdateWidget(SettingsPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.active && !oldWidget.active) {
      _loadMcpServerState();
      _loadWorkspaceMemoryState();
    }
  }

  Future<void> _loadMcpServerState() =>
      runPageLoad(#mcpState, _readMcpServerState, revalidate: true);

  Future<void> _readMcpServerState() async {
    try {
      final info = await McpServerService.getState();
      if (!await waitForPageReady() || !pageLoadIsCurrent(#mcpState)) return;
      final enabled = info?.enabled == true;
      setState(() {
        _mcpInfo = info;
        _mcpEnabled = enabled;
      });
      // 回写缓存，供下次首帧同步渲染。
      await StorageService.setBool(
        StorageService.kMcpLocalServiceEnabledKey,
        enabled,
      );
    } catch (e) {
      debugPrint('Load MCP state failed: $e');
    }
  }

  Future<void> _loadWorkspaceMemoryState() => runPageLoad(
    #workspaceMemoryState,
    _readWorkspaceMemoryState,
    revalidate: true,
  );

  Future<void> _readWorkspaceMemoryState() async {
    try {
      final results = await Future.wait([
        WorkspaceMemoryService.getEmbeddingConfig(),
        WorkspaceMemoryService.getRollupStatus(),
      ]);
      if (!await waitForPageReady() ||
          !pageLoadIsCurrent(#workspaceMemoryState)) {
        return;
      }
      final config = results[0] as WorkspaceMemoryEmbeddingConfig;
      setState(() {
        _workspaceMemoryConfigured = config.configured;
      });
      // 回写缓存，供下次首帧同步渲染。
      unawaited(
        StorageService.setBool(
          StorageService.kWorkspaceMemoryConfiguredKey,
          config.configured,
        ),
      );
    } catch (e) {
      debugPrint('Load workspace memory state failed: $e');
    }
  }

  Future<void> _toggleMcpServer(bool enable) async {
    if (_mcpBusy) return;
    final previousEnabled = _mcpEnabled;
    setState(() {
      _mcpBusy = true;
      _mcpEnabled = enable;
    });
    try {
      final info = await McpServerService.setEnabled(enable);
      if (!mounted) return;
      final enabled = info?.enabled == true;
      setState(() {
        _mcpInfo = info;
        _mcpEnabled = enabled;
      });
      // 同步更新缓存，保证下次进入设置页首帧即正确。
      unawaited(
        StorageService.setBool(
          StorageService.kMcpLocalServiceEnabledKey,
          enabled,
        ),
      );
      if (enable) {
        final endpoint = info?.endpoint ?? '';
        if (info?.isLoopbackOnly == true) {
          showToast(
            Localizations.localeOf(context).languageCode == 'en'
                ? 'Service is available on this device only. Connect to a local network to share it.'
                : '服务当前仅在此设备可用，连接局域网后可供电脑访问。',
          );
        } else if (endpoint.isNotEmpty) {
          showToast(
            context.l10n.settingsMcpEnabledToast(endpoint),
            type: ToastType.success,
          );
        }
      } else {
        showToast(context.l10n.settingsMcpDisabledToast);
      }
    } on PlatformException catch (e) {
      if (!mounted) return;
      showToast(
        e.message ?? context.l10n.settingsMcpToggleFailed,
        type: ToastType.error,
      );
      setState(() {
        _mcpEnabled = previousEnabled;
      });
    } catch (e) {
      if (!mounted) return;
      showToast(context.l10n.settingsMcpToggleFailed, type: ToastType.error);
      setState(() {
        _mcpEnabled = previousEnabled;
      });
    } finally {
      if (mounted) {
        setState(() {
          _mcpBusy = false;
        });
      }
    }
  }

  void _showMcpInfo() {
    final info = _mcpInfo;
    if (info == null || info.endpoint.isEmpty) return;
    final l10n = context.l10n;
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';

    showSettingsDetailSheet<void>(
      context: context,
      builder: (sheetContext) {
        final sheetPalette = sheetContext.omniPalette;
        final labelStyle = TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w500,
          color: sheetPalette.textTertiary,
        );
        final valueStyle = TextStyle(
          fontSize: 13,
          color: sheetPalette.textPrimary,
          height: 1.4,
        );
        final actionStyle = settingsDetailSheetActionStyle(sheetContext);

        Widget buildInfoBox(String label, String value) {
          return Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: sheetPalette.surfaceSecondary,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: labelStyle),
                const SizedBox(height: 4),
                SelectableText(value, style: valueStyle),
              ],
            ),
          );
        }

        return SettingsDetailSheet(
          key: const ValueKey('local-service-sheet'),
          title: l10n.settingsMcpLocalService,
          body: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (info.isLoopbackOnly) ...[
                Text(
                  isEnglish
                      ? 'This address works only on this device. To use the WebUI or MCP from a computer, connect to a local network and enable local network access.'
                      : '此地址仅能在当前设备访问。电脑访问 WebUI 或 MCP 前，请连接局域网并开启局域网访问。',
                  style: valueStyle,
                ),
                const SizedBox(height: 12),
              ],
              buildInfoBox(l10n.settingsMcpAddress, info.endpoint),
              const SizedBox(height: 8),
              buildInfoBox(
                l10n.settingsMcpToken,
                info.token.isEmpty ? l10n.settingsNotGenerated : info.token,
              ),
            ],
          ),
          actionsKey: const ValueKey('local-service-actions'),
          actions: [
            if (info.isLoopbackOnly)
              TextButton(
                key: const ValueKey('local-service-enable-lan'),
                style: actionStyle,
                onPressed: () {
                  Navigator.of(sheetContext).pop();
                  _toggleMcpServer(true);
                },
                child: Text(
                  isEnglish ? 'Enable local network access' : '开启局域网访问',
                ),
              ),
            TextButton(
              style: actionStyle,
              onPressed: () {
                Clipboard.setData(ClipboardData(text: info.endpoint));
                Navigator.of(sheetContext).pop();
                showToast(l10n.settingsCopiedAddress);
              },
              child: Text(l10n.settingsCopyAddress),
            ),
            TextButton(
              style: actionStyle,
              onPressed: () {
                Clipboard.setData(ClipboardData(text: info.token));
                Navigator.of(sheetContext).pop();
                showToast(l10n.settingsCopiedToken);
              },
              child: Text(l10n.settingsCopyToken),
            ),
            TextButton(
              style: actionStyle,
              onPressed: () async {
                Navigator.of(sheetContext).pop();
                try {
                  final refreshed = await McpServerService.refreshToken();
                  if (!mounted) return;
                  setState(() {
                    _mcpInfo = refreshed ?? _mcpInfo;
                  });
                  showToast(l10n.settingsTokenRefreshed);
                } catch (_) {
                  showToast(
                    l10n.settingsTokenRefreshFailed,
                    type: ToastType.error,
                  );
                }
              },
              child: Text(l10n.settingsRefreshToken),
            ),
          ],
          footer: Text(
            info.isLoopbackOnly
                ? (isEnglish
                      ? 'The local service remains available to agents on this device.'
                      : '本机 Agent 仍可使用本地服务。')
                : l10n.settingsMcpSecurityNotice,
            style: TextStyle(fontSize: 12, color: sheetPalette.textSecondary),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final workspaceMemorySubtitle = _workspaceMemoryConfigured
        ? context.l10n.settingsWorkspaceMemoryEnabled
        : context.l10n.settingsWorkspaceMemoryLexical;
    final sections = _buildSections(workspaceMemorySubtitle);

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: OmniRootToolbar(
        title: context.l10n.settingsTitle,
        primary: true,
        showBackButton: !widget.embedded,
      ),
      body: OmniContentWidth(
        child: SafeArea(
          top: false,
          bottom: false,
          child: ListView.separated(
            padding: edgeToEdgeScrollPadding(
              context,
              const EdgeInsets.fromLTRB(22, 10, 22, 32),
            ),
            itemCount: sections.length,
            separatorBuilder: (_, _) => const SizedBox(height: 26),
            itemBuilder: (context, index) {
              return _buildSettingsSection(sections[index]);
            },
          ),
        ),
      ),
    );
  }

  List<_SettingSection> _buildSections(String workspaceMemorySubtitle) {
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    return [
      _SettingSection(
        label: isEnglish ? 'Account' : '账号',
        isAccount: true,
        items: [
          _SettingItem(
            icon: LucideIcons.userRoundCog,
            title: isEnglish ? 'Account & AI service' : '账号与 AI 服务',
            subtitle: isEnglish
                ? 'Sign in, view platform quota'
                : '注册登录、查看平台额度',
            route: '/my/account',
          ),
        ],
      ),
      _SettingSection(
        label: context.l10n.settingsSectionModelMemory,
        items: [
          _SettingItem(
            icon: LucideIcons.box,
            title: context.l10n.settingsModelProviderTitle,
            subtitle: context.l10n.settingsModelProviderSubtitle,
            route: '/home/model_provider_setting',
          ),
          _SettingItem(
            icon: LucideIcons.fileBox,
            title: context.l10n.settingsSceneModelTitle,
            subtitle: context.l10n.settingsSceneModelSubtitle,
            route: '/home/scene_model_setting',
          ),
          _SettingItem(
            icon: LucideIcons.database,
            title: context.l10n.settingsWorkspaceMemoryTitle,
            subtitle: workspaceMemorySubtitle,
            trailingText: _workspaceMemoryConfigured
                ? (isEnglish ? 'Configured' : '已配置')
                : null,
            route: '/home/workspace_memory_setting',
            onReturned: _loadWorkspaceMemoryState,
          ),
        ],
      ),
      _SettingSection(
        label: context.l10n.settingsSectionServiceEnvironment,
        items: [
          _SettingItem(
            icon: LucideIcons.wrench,
            title: isEnglish ? 'Tools' : '工具',
            subtitle: isEnglish
                ? 'Explore tools available to agents'
                : '查看 Agent 可调用的工具',
            route: '/home/tools',
          ),
          _SettingItem(
            icon: LucideIcons.squareTerminal,
            title: context.l10n.settingsAlpineTitle,
            subtitle: context.l10n.settingsAlpineSubtitle,
            route: '/home/termux_setting',
          ),
          _SettingItem(
            icon: LucideIcons.monitorSmartphone,
            title: context.l10n.settingsLocalServiceTitle,
            subtitle: _mcpInfo?.isLoopbackOnly == true
                ? (isEnglish
                      ? 'Available on this device only; tap to enable local network access'
                      : '当前仅本机可用，点击开启局域网访问')
                : context.l10n.settingsLocalServiceSubtitle,
            trailing: OmniSettingsSwitch(
              value: _mcpEnabled,
              enabled: !_mcpBusy,
              onToggle: (val) async {
                await _toggleMcpServer(val);
              },
            ),
            onTap: _mcpEnabled && !_mcpBusy ? _showMcpInfo : null,
          ),
          _SettingItem(
            icon: LucideIcons.hammer,
            title: context.l10n.settingsMcpToolsTitle,
            subtitle: context.l10n.settingsMcpToolsSubtitle,
            route: '/home/mcp_tools',
          ),
        ],
      ),
      _SettingSection(
        label: context.l10n.settingsSectionExperienceAppearance,
        items: [
          _SettingItem(
            icon: LucideIcons.palette,
            title: context.l10n.settingsAppearanceTitle,
            subtitle: context.l10n.settingsAppearanceSubtitle,
            route: '/home/background_setting',
          ),
          _SettingItem(
            icon: LucideIcons.settings2,
            title: context.trLegacy('杂项'),
            subtitle: context.trLegacy('首页、后台隐藏、闹钟、振动与打开方式'),
            route: '/home/experience_misc_setting',
          ),
        ],
      ),
      _SettingSection(
        label: context.l10n.settingsSectionPermissionInfo,
        items: [
          _SettingItem(
            icon: LucideIcons.shieldCheck,
            title: context.l10n.authorizePageTitle,
            subtitle: context.trLegacy('查看并配置悬浮窗、后台运行、Shizuku 等权限'),
            route: '/home/authorize_setting',
          ),
          _SettingItem(
            icon: LucideIcons.hardDrive,
            title: context.l10n.storageUsageTitle,
            subtitle: context.l10n.storageUsageSubtitle,
            route: '/home/storage_usage',
          ),
          _SettingItem(
            icon: LucideIcons.info,
            title: context.l10n.settingsAboutTitle,
            route: '/my/about',
          ),
        ],
      ),
    ];
  }

  Widget _buildSettingsSection(_SettingSection section) {
    return OmniSettingsGroup(
      title: section.isAccount ? null : section.label,
      translateTitle: false,
      children: [
        for (final item in section.items) _buildSettingsItem(item, section),
      ],
    );
  }

  Widget _buildSettingsItem(_SettingItem item, _SettingSection section) {
    Widget buildTile(VoidCallback? onTap) => Semantics(
      hint: item.subtitle,
      child: OmniSettingsTile(
        icon: item.icon,
        title: item.title,
        trailingText: item.trailingText,
        trailing: item.trailing,
        onTap: onTap,
        translateText: false,
        padding: EdgeInsets.symmetric(
          horizontal: 18,
          vertical: section.isAccount ? 22 : 16,
        ),
      ),
    );
    final route = item.route;
    if (route == null) return buildTile(item.onTap);
    // Carry the row surface and group corners into the destination; the
    // parent OmniSettingsGroup stays behind. Keep the container defaults:
    // the expanding rectangle has page color, with the shaped row inside it.
    final index = section.items.indexOf(item);
    final rowShape = RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(
        top: index == 0 ? const Radius.circular(22) : Radius.zero,
        bottom: index == section.items.length - 1
            ? const Radius.circular(22)
            : Radius.zero,
      ),
    );
    return OmniOpenContainer(
      key: ValueKey('settings-open-$route'),
      closedBuilder: (rowContext, open) => Material(
        color: rowContext.omniPalette.surfacePrimary,
        shape: rowShape,
        clipBehavior: Clip.antiAlias,
        child: buildTile(open),
      ),
      onOpen: (source) => GoRouterManager.pushForResult<Object?>(
        route,
        extra: {omniContainerExtraKey: source},
      ),
      onClosed: (_) => item.onReturned?.call(),
    );
  }
}

class _SettingSection {
  final String label;
  final List<_SettingItem> items;
  final bool isAccount;

  const _SettingSection({
    required this.label,
    required this.items,
    this.isAccount = false,
  });
}

class _SettingItem {
  final IconData? icon;
  final String title;
  final String? subtitle;
  final String? trailingText;
  final Widget? trailing;
  final VoidCallback? onTap;
  final String? route;
  final VoidCallback? onReturned;

  const _SettingItem({
    this.icon,
    required this.title,
    this.subtitle,
    this.trailingText,
    this.trailing,
    this.onTap,
    this.route,
    this.onReturned,
  });
}
