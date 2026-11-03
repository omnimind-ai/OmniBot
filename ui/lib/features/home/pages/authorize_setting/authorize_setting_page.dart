import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/flclash/popup.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/authorize/accessibility_permission_prompt.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/personal_context_service.dart';
import 'package:ui/features/home/pages/authorize_setting/personal_context_labels.dart';
import 'package:ui/features/home/pages/authorize_setting/root_access_setting.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/utils/cache_util.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_ui.dart';

/// 应用权限授权页面
class AuthorizeSettingPage extends StatefulWidget {
  const AuthorizeSettingPage({super.key});

  @override
  State<AuthorizeSettingPage> createState() => _AuthorizeSettingPageState();
}

class _AuthorizeSettingPageState extends State<AuthorizeSettingPage>
    with WidgetsBindingObserver, FlClashPageLoadMixin {
  bool notificationEnabled = true; // 接收消息通知

  // 权限状态
  bool _backgroundRunning = false;
  bool _accessibilityReady = false;
  bool _overlayPermission = false;
  bool _installedAppsPermission = false;
  bool _publicStoragePermission = false;
  bool _localNetworkPermissionRequired = false;
  bool _localNetworkPermissionGranted = false;
  ShizukuStatusSnapshot _shizukuStatus = ShizukuStatusSnapshot.fallback();
  Map<String, PersonalContextCapability> _personalContext = {};
  bool _personalContextLoaded = false;
  bool _personalContextFailed = false;

  Color get _pageBackground => context.omniPalette.pageBackground;
  Color get _titleColor => context.omniPalette.textPrimary;
  Color get _subtitleColor => context.omniPalette.textSecondary;
  Color get _tertiaryTextColor => context.omniPalette.textTertiary;
  Color get _accentColor => context.omniPalette.accentPrimary;
  int get _corePermissionCount => 4;
  int get _readyCorePermissionCount => <bool>[
    _backgroundRunning,
    _accessibilityReady,
    _overlayPermission,
    _installedAppsPermission,
  ].where((value) => value).length;
  bool get _allCorePermissionsEnabled =>
      _readyCorePermissionCount == _corePermissionCount;
  double get _corePermissionProgress =>
      _readyCorePermissionCount / _corePermissionCount;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadSettings();
    _checkPermissions();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    if (state == AppLifecycleState.resumed) {
      _checkPermissions();
    }
  }

  Future<void> _loadSettings() async {
    if (!await waitForPageReady() || !mounted) return;
    try {
      final notification = await CacheUtil.getBool("notification_enabled");
      if (!await waitForPageReady() || !mounted) return;
      setState(() {
        notificationEnabled = notification;
      });
    } catch (e) {
      debugPrint('Error loading settings: $e');
    }
  }

  Future<void> _toggleNotification(bool value) async {
    await CacheUtil.cacheBool("notification_enabled", value);
    if (!mounted) return;
    setState(() {
      notificationEnabled = value;
    });
  }

  String _localeText({required String zh, required String en}) {
    final languageCode = Localizations.localeOf(context).languageCode;
    return languageCode == 'en' ? en : zh;
  }

  List<_AuthorizeSettingSection> _buildSections() {
    return [
      _AuthorizeSettingSection(
        label: _localeText(zh: '通知与提醒', en: 'Notifications'),
        subtitle: _localeText(
          zh: '控制任务进度与消息提醒的接收方式。',
          en: 'Control how task progress updates and reminders are delivered.',
        ),
        items: [
          _AuthorizeSettingItem(
            icon: LucideIcons.bell,
            title: context.l10n.authorizeReceiveNotifications,
            subtitle: context.l10n.authorizeNotificationsDesc,
            trailing: _buildSwitchTrailing(
              value: notificationEnabled,
              onToggle: _toggleNotification,
            ),
            onTap: () {
              _toggleNotification(!notificationEnabled);
            },
          ),
        ],
      ),
      _AuthorizeSettingSection(
        label: _localeText(zh: '核心权限', en: 'Core Permissions'),
        subtitle: _localeText(
          zh: '这些授权用于操作界面、悬浮显示、后台运行和识别已安装应用。',
          en: 'These permissions support GUI control, overlays, background operation, and installed-app discovery.',
        ),
        items: [
          _AuthorizeSettingItem(
            icon: LucideIcons.accessibility,
            title: _localeText(zh: '无障碍权限', en: 'Accessibility Permission'),
            subtitle: _localeText(
              zh: '用于读取页面并执行点击、滑动和输入；可在系统设置的“无障碍”中开启。',
              en: 'Observe the screen and perform taps, swipes, and text input. Enable it in system Accessibility settings.',
            ),
            trailing: _buildPermissionTrailing(
              label: context.trLegacy(_accessibilityReady ? '已开启' : '去开启'),
              color: _accessibilityReady ? _tertiaryTextColor : _accentColor,
            ),
            onTap: () async {
              await showAccessibilityPermissionPrompt(context);
              if (mounted) await _checkPermissions();
            },
          ),
          _AuthorizeSettingItem(
            icon: LucideIcons.batteryCharging,
            title: context.trLegacy('后台运行权限'),
            subtitle: _localeText(
              zh: '减少系统回收，让消息、定时任务和本机服务在后台稳定继续。',
              en: 'Reduce system cleanup so messages, scheduled tasks, and local services can continue reliably.',
            ),
            trailing: _buildPermissionTrailing(
              label: context.trLegacy(_backgroundRunning ? '已开启' : '去开启'),
              color: _backgroundRunning ? _tertiaryTextColor : _accentColor,
            ),
            onTap: () {
              spePermission.invokeMethod('openBatteryOptimizationSettings');
            },
          ),
          _AuthorizeSettingItem(
            icon: LucideIcons.pictureInPicture2,
            title: context.trLegacy('悬浮窗权限'),
            subtitle: _localeText(
              zh: '允许小万在其他应用上方显示宠物、半屏聊天和任务提醒。',
              en: 'Allow Omnibot to show the pet, half-screen chat, and task reminders above other apps.',
            ),
            trailing: _buildPermissionTrailing(
              label: context.trLegacy(_overlayPermission ? '已开启' : '去开启'),
              color: _overlayPermission ? _tertiaryTextColor : _accentColor,
            ),
            onTap: () {
              spePermission.invokeMethod('openOverlaySettings');
            },
          ),
          _AuthorizeSettingItem(
            icon: LucideIcons.layoutGrid,
            title: context.trLegacy('应用列表读取'),
            subtitle: _localeText(
              zh: '用于识别设备已安装应用，并提供应用上下文。',
              en: 'Used to identify installed apps and provide app context.',
            ),
            trailing: _buildPermissionTrailing(
              label: context.trLegacy(_installedAppsPermission ? '已开启' : '去开启'),
              color: _installedAppsPermission
                  ? _tertiaryTextColor
                  : _accentColor,
            ),
            onTap: () {
              spePermission.invokeMethod('openInstalledAppsSettings');
            },
          ),
        ],
      ),
      _AuthorizeSettingSection(
        label: _localeText(zh: '扩展能力', en: 'Advanced Access'),
        footer: const RootAccessSetting(),
        subtitle: _localeText(
          zh: '按需开启，可获得更完整的系统级能力与兼容性支持。',
          en: 'Enable when needed for broader system-level capabilities and compatibility.',
        ),
        items: [
          if (_localNetworkPermissionRequired)
            _AuthorizeSettingItem(
              icon: LucideIcons.wifi,
              title: _localeText(zh: '局域网访问', en: 'Local network'),
              subtitle: _localeText(
                zh: '连接同一网络中的电脑、模型和 MCP 服务，或让电脑访问小万。',
                en: 'Connect to computers, model providers and MCP services on your network, or access Omnibot from a computer.',
              ),
              trailing: _buildPermissionTrailing(
                label: context.trLegacy(
                  _localNetworkPermissionGranted ? '已开启' : '去开启',
                ),
                color: _localNetworkPermissionGranted
                    ? _tertiaryTextColor
                    : _accentColor,
              ),
              onTap: _requestLocalNetworkAccess,
            ),
          _AuthorizeSettingItem(
            icon: LucideIcons.folderOpen,
            title: _localeText(zh: '所有文件访问权限', en: 'All files access'),
            subtitle: _localeText(
              zh: '允许小万访问设备公共存储中的文件与文件夹，用于文件读取、整理和下载等操作。',
              en: 'Allow Omnibot to read and manage files in shared device storage for file tasks and downloads.',
            ),
            trailing: _buildPermissionTrailing(
              label: context.trLegacy(_publicStoragePermission ? '已开启' : '去开启'),
              color: _publicStoragePermission
                  ? _tertiaryTextColor
                  : _accentColor,
            ),
            onTap: () {
              openPublicStorageSettings();
            },
          ),
          _AuthorizeSettingItem(
            icon: LucideIcons.usb,
            title: context.trLegacy('Shizuku 权限'),
            subtitle: _shizukuStatus.localizedGuide,
            trailing: _buildPermissionTrailing(
              label: _shizukuStatus.localizedStatusLabel,
              color: _shizukuStatus.isGranted
                  ? _tertiaryTextColor
                  : _accentColor,
            ),
            onTap: () async {
              await ensureShizukuPermission(context);
              if (mounted) {
                await _checkPermissions();
              }
            },
          ),
        ],
      ),
      _buildPersonalContextSection(),
    ];
  }

  _AuthorizeSettingSection _buildPersonalContextSection() {
    return _AuthorizeSettingSection(
      label: _localeText(zh: '个人上下文', en: 'Personal context'),
      subtitle: _localeText(
        zh: '逐项选择您愿意提供的信息。AI 按需查询，查询结果可能提供给您配置的模型；不会自动整理为长期记忆。',
        en: 'Choose what to share. AI queries data when needed and may send results to your configured model. Data is not automatically saved as long-term memory.',
      ),
      items: [
        for (final item in personalContextLabels)
          _AuthorizeSettingItem(
            icon: item.icon,
            title: _localeText(zh: item.zh, en: item.en),
            subtitle: _localeText(zh: item.zhDetail, en: item.enDetail),
            trailing: _buildPermissionTrailing(
              label: _personalContextStatus(item.id),
              color:
                  _personalContext[item.id]?.status ==
                      PersonalContextAccessStatus.granted
                  ? _tertiaryTextColor
                  : _accentColor,
            ),
            onTap: item.id == 'documents'
                ? null
                : () => _openPersonalContext(item.id),
            onMenuTap: item.id == 'documents'
                ? (anchorContext) => _openPersonalContext(
                    item.id,
                    anchorContext: anchorContext,
                  )
                : null,
          ),
        _AuthorizeSettingItem(
          icon: LucideIcons.trash2,
          title: _localeText(zh: '清空通知历史', en: 'Clear notification history'),
          subtitle: _localeText(
            zh: '删除本地通知缓存，不删除系统通知或聊天记录。',
            en: 'Delete the local notification cache. System notifications and conversations are preserved.',
          ),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => _personalContextAction(
            PersonalContextService.clearNotifications,
            _localeText(zh: '通知历史已清空', en: 'Notification history cleared'),
          ),
        ),
      ],
    );
  }

  String _personalContextStatus(String id) {
    if (!_personalContextLoaded) return _localeText(zh: '检查中', en: 'Checking');
    if (_personalContextFailed) return _localeText(zh: '点击重试', en: 'Retry');
    final status =
        _personalContext[id]?.status ?? PersonalContextAccessStatus.unavailable;
    return switch (status) {
      PersonalContextAccessStatus.granted => _localeText(
        zh: id == 'environment' ? '可用' : '已开启',
        en: id == 'environment' ? 'Available' : 'Enabled',
      ),
      PersonalContextAccessStatus.partial => _localeText(
        zh: id == 'location' ? '大致位置' : '部分授权',
        en: id == 'location' ? 'Approximate' : 'Partial access',
      ),
      PersonalContextAccessStatus.denied => _localeText(
        zh: id == 'documents' ? '去选择' : '去开启',
        en: id == 'documents' ? 'Select' : 'Enable',
      ),
      PersonalContextAccessStatus.restricted => _localeText(
        zh: '系统受限',
        en: 'Restricted',
      ),
      PersonalContextAccessStatus.disconnected => _localeText(
        zh: '服务未连接',
        en: 'Disconnected',
      ),
      PersonalContextAccessStatus.unavailable => _localeText(
        zh: '不可用',
        en: 'Unavailable',
      ),
    };
  }

  Future<void> _refreshPersonalContext() async {
    if (!await waitForPageReady() || !mounted) return;
    try {
      final capabilities = await PersonalContextService.capabilities();
      if (await waitForPageReady() && mounted) {
        setState(() {
          _personalContext = capabilities;
          _personalContextLoaded = true;
          _personalContextFailed = false;
        });
      }
    } catch (_) {
      if (await waitForPageReady() && mounted) {
        setState(() {
          _personalContextLoaded = true;
          _personalContextFailed = true;
        });
      }
    }
  }

  Future<void> _personalContextAction(
    Future<void> Function() action, [
    String? message,
  ]) async {
    try {
      await action();
      await _refreshPersonalContext();
      if (mounted && message != null) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(message)));
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              _localeText(
                zh: '暂时无法完成，请检查系统设置后重试。',
                en: 'Unable to complete this action. Check system settings and try again.',
              ),
            ),
          ),
        );
      }
    }
  }

  Future<void> _openPersonalContext(
    String id, {
    BuildContext? anchorContext,
  }) async {
    if (_personalContextFailed || !_personalContextLoaded) {
      await _refreshPersonalContext();
      return;
    }
    if (id == 'environment' ||
        _personalContext[id]?.status ==
            PersonalContextAccessStatus.unavailable) {
      return;
    }
    if (id == 'background_location' &&
        ![
          PersonalContextAccessStatus.granted,
          PersonalContextAccessStatus.partial,
        ].contains(_personalContext['location']?.status)) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            _localeText(
              zh: '请先开启上方的位置信息。',
              en: 'Enable location access above first.',
            ),
          ),
        ),
      );
      return;
    }
    if (id == 'documents') {
      if (anchorContext == null) return;
      final anchor = glassPopupAnchorFromContext(anchorContext);
      if (anchor == null) return;
      final action = await showOmniMenu<String>(
        context: context,
        sourceContext: anchorContext,
        anchor: anchor,
        width: 300,
        items: [
          OmniPopupMenuItem(
            value: 'files',
            child: Text(_localeText(zh: '选择文件', en: 'Select files')),
          ),
          OmniPopupMenuItem(
            value: 'directory',
            child: Text(_localeText(zh: '选择文件夹', en: 'Select a folder')),
          ),
          OmniPopupMenuItem(
            value: 'revoke',
            child: Text(
              _localeText(
                zh: '取消已选文件与文件夹的授权',
                en: 'Remove selected file and folder access',
              ),
            ),
          ),
        ],
      );
      if (action == null || !mounted) return;
      if (action == 'revoke') {
        await _personalContextAction(PersonalContextService.revokeDocuments);
        return;
      }
      await _personalContextAction(
        () => PersonalContextService.request(
          id,
          directory: action == 'directory',
        ),
      );
      return;
    }
    await _personalContextAction(() => PersonalContextService.request(id));
  }

  Future<void> _checkPermissions() async {
    if (!await waitForPageReady() || !mounted) return;
    await _refreshPersonalContext();
    if (!await waitForPageReady() || !mounted) return;
    try {
      final backgroundRunning = await isBackgroundRunAllowed();
      final accessibilityReady =
          await spePermission.invokeMethod<bool>(
            'isAndroidGuiAccessibilityReady',
          ) ??
          false;
      final overlayPermission =
          await spePermission.invokeMethod('isOverlayPermission') ?? false;
      final installedAppsPermission =
          await spePermission.invokeMethod(
            'isInstalledAppsPermissionGranted',
          ) ??
          false;
      final publicStoragePermission = await isPublicStorageAccessGranted();
      final localNetworkStatus = await spePermission
          .invokeMapMethod<String, dynamic>('getLocalNetworkPermissionStatus');
      final shizukuStatus = await getShizukuStatus();

      if (await waitForPageReady() && mounted) {
        setState(() {
          _backgroundRunning = backgroundRunning;
          _accessibilityReady = accessibilityReady;
          _overlayPermission = overlayPermission;
          _installedAppsPermission = installedAppsPermission;
          _publicStoragePermission = publicStoragePermission;
          _localNetworkPermissionRequired =
              localNetworkStatus?['required'] == true;
          _localNetworkPermissionGranted =
              localNetworkStatus?['granted'] == true;
          _shizukuStatus = shizukuStatus;
        });
      }
    } catch (e) {
      debugPrint('Error checking permissions: $e');
    }
  }

  Future<void> _requestLocalNetworkAccess() async {
    try {
      if (_localNetworkPermissionGranted) {
        await spePermission.invokeMethod('openAppDetailsSettings');
        return;
      }
      final granted = await spePermission.invokeMethod<bool>(
        'requestLocalNetworkPermission',
      );
      if (!mounted) return;
      if (granted != true) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              _localeText(
                zh: '局域网访问未授权，可在系统应用权限中允许「附近的设备」。',
                en: 'Local network access was denied. Allow Nearby devices in system app permissions.',
              ),
            ),
            action: SnackBarAction(
              label: _localeText(zh: '打开设置', en: 'Open settings'),
              onPressed: () =>
                  spePermission.invokeMethod('openAppDetailsSettings'),
            ),
          ),
        );
      }
      await _checkPermissions();
    } catch (error) {
      if (mounted) {
        showToast(
          _localeText(
            zh: '无法申请局域网权限，请在系统应用权限中开启。',
            en: 'Could not request local network access. Enable it in system app permissions.',
          ),
          type: ToastType.warning,
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final sections = _buildSections();

    return Scaffold(
      backgroundColor: _pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.authorizePageTitle,
        primary: true,
      ),
      body: OmniContentWidth(
        child: SafeArea(
          top: false,
          bottom: false,
          child: ListView.separated(
            padding: edgeToEdgeScrollPadding(
              context,
              const EdgeInsets.fromLTRB(22, 12, 22, 32),
            ),
            itemCount: sections.length + 1,
            separatorBuilder: (_, __) => const SizedBox(height: 22),
            itemBuilder: (context, index) {
              if (index == 0) {
                return _buildOverview();
              }
              return _buildSettingsSection(sections[index - 1]);
            },
          ),
        ),
      ),
    );
  }

  Widget _buildOverview() {
    final palette = context.omniPalette;
    final progress = _corePermissionProgress.clamp(0.0, 1.0);

    return OmniSettingsCard(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 220),
            child: Text(
              _localeText(
                zh: '$_readyCorePermissionCount / $_corePermissionCount 项核心授权已就绪',
                en: '$_readyCorePermissionCount of $_corePermissionCount core permissions ready',
              ),
              key: ValueKey<int>(_readyCorePermissionCount),
              style: TextStyle(
                fontSize: 17,
                fontWeight: FontWeight.w600,
                color: _titleColor,
                height: 1.35,
              ),
            ),
          ),
          const SizedBox(height: 6),
          Text(
            _localeText(
              zh: '建议优先完成与任务执行直接相关的授权，缺失时可能影响悬浮交互和后台任务。',
              en: 'Enable task-critical access first. Missing permissions can interrupt floating assist and background tasks.',
            ),
            style: TextStyle(fontSize: 12, height: 1.5, color: _subtitleColor),
          ),
          const SizedBox(height: 14),
          LayoutBuilder(
            builder: (context, constraints) {
              return Container(
                height: 6,
                decoration: BoxDecoration(
                  color: palette.borderSubtle.withValues(
                    alpha: context.isDarkTheme ? 0.9 : 0.72,
                  ),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 240),
                    curve: Curves.easeOutCubic,
                    width: constraints.maxWidth * progress,
                    decoration: BoxDecoration(
                      color: palette.accentPrimary,
                      borderRadius: BorderRadius.circular(999),
                    ),
                  ),
                ),
              );
            },
          ),
          const SizedBox(height: 10),
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 180),
            child: Text(
              _allCorePermissionsEnabled
                  ? _localeText(
                      zh: '核心权限已齐备，可以继续按需配置扩展能力。',
                      en: 'Core permissions are ready. You can configure advanced access if needed.',
                    )
                  : _localeText(
                      zh: '建议先补齐核心权限，再执行需要悬浮交互或后台运行的任务。',
                      en: 'Finish the core permissions first before running tasks that need floating interaction or background execution.',
                    ),
              key: ValueKey<bool>(_allCorePermissionsEnabled),
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w500,
                color: _allCorePermissionsEnabled
                    ? _accentColor
                    : _subtitleColor,
                height: 1.45,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSettingsSection(_AuthorizeSettingSection section) {
    return OmniSettingsGroup(
      title: section.label,
      subtitle: section.subtitle,
      translateTitle: false,
      children: [
        for (final item in section.items)
          if (item.onMenuTap != null)
            FlClashPopupSource(
              glass: false,
              color: Colors.transparent,
              shape: const RoundedRectangleBorder(),
              builder: (anchorContext) => OmniSettingsTile(
                icon: item.icon,
                title: item.title,
                subtitle: item.subtitle,
                trailing: item.trailing,
                onTap: () => item.onMenuTap!(anchorContext),
                translateText: false,
              ),
            )
          else
            OmniSettingsTile(
              icon: item.icon,
              title: item.title,
              subtitle: item.subtitle,
              trailing: item.trailing,
              onTap: item.onTap,
              translateText: false,
            ),
        if (section.footer != null) section.footer!,
      ],
    );
  }

  Widget _buildPermissionTrailing({
    required String label,
    required Color color,
  }) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          label,
          style: TextStyle(
            fontSize: 12.5,
            fontWeight: FontWeight.w500,
            color: color,
          ),
        ),
        const SizedBox(width: 4),
        Icon(LucideIcons.chevronRight, size: 16, color: _tertiaryTextColor),
      ],
    );
  }

  Widget _buildSwitchTrailing({
    required bool value,
    required ValueChanged<bool> onToggle,
  }) {
    return OmniSettingsSwitch(value: value, onToggle: onToggle);
  }
}

class _AuthorizeSettingSection {
  final String label;
  final String? subtitle;
  final List<_AuthorizeSettingItem> items;
  final Widget? footer;

  const _AuthorizeSettingSection({
    required this.label,
    this.subtitle,
    required this.items,
    this.footer,
  });
}

class _AuthorizeSettingItem {
  final IconData icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;
  final ValueChanged<BuildContext>? onMenuTap;

  const _AuthorizeSettingItem({
    required this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
    this.onMenuTap,
  });
}
