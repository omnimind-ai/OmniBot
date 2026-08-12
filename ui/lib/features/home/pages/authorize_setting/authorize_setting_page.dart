import 'package:flutter/material.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/cache_util.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

/// 应用权限授权页面
class AuthorizeSettingPage extends StatefulWidget {
  const AuthorizeSettingPage({super.key});

  @override
  State<AuthorizeSettingPage> createState() => _AuthorizeSettingPageState();
}

class _AuthorizeSettingPageState extends State<AuthorizeSettingPage>
    with WidgetsBindingObserver {
  bool notificationEnabled = true; // 接收消息通知

  // 权限状态
  bool _backgroundRunning = false;
  bool _overlayPermission = false;
  bool _installedAppsPermission = false;
  bool _publicStoragePermission = false;
  AppEditionCapabilitySnapshot _capabilities =
      AppEditionCapabilitySnapshot.unavailable;
  ShizukuStatusSnapshot _shizukuStatus = ShizukuStatusSnapshot.fallback();

  Color get _pageBackground => context.omniPalette.pageBackground;
  Color get _titleColor => context.omniPalette.textPrimary;
  Color get _subtitleColor => context.omniPalette.textSecondary;
  Color get _tertiaryTextColor => context.omniPalette.textTertiary;
  Color get _accentColor => context.omniPalette.accentPrimary;
  Color get _switchInactiveColor => context.omniPalette.borderStrong;
  bool get _installedAppsAvailable => _capabilities.installedAppsQuery;
  bool get _publicStorageAvailable => _capabilities.publicStorageAccess;
  int get _corePermissionCount => _installedAppsAvailable ? 3 : 2;
  int get _readyCorePermissionCount => <bool>[
    _backgroundRunning,
    _overlayPermission,
    if (_installedAppsAvailable) _installedAppsPermission,
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
    try {
      final notification = await CacheUtil.getBool("notification_enabled");
      if (!mounted) return;
      setState(() {
        notificationEnabled = notification;
      });
    } catch (_) {
      // Keep the safe default when the preference store is unavailable.
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
            icon: Icons.notifications_none_rounded,
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
          zh: _installedAppsAvailable
              ? '这些授权用于悬浮显示、后台运行和识别已安装应用。'
              : '这些授权用于悬浮显示和后台运行。',
          en: _installedAppsAvailable
              ? 'These permissions support overlays, background operation, and installed-app discovery.'
              : 'These permissions support overlays and background operation.',
        ),
        items: [
          _AuthorizeSettingItem(
            icon: Icons.battery_saver_outlined,
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
            icon: Icons.picture_in_picture_alt_outlined,
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
          if (_installedAppsAvailable)
            _AuthorizeSettingItem(
              icon: Icons.apps_outlined,
              title: context.trLegacy('应用列表读取'),
              subtitle: _localeText(
                zh: '用于识别设备已安装应用，并提供应用上下文。',
                en: 'Used to identify installed apps and provide app context.',
              ),
              trailing: _buildPermissionTrailing(
                label: context.trLegacy(
                  _installedAppsPermission ? '已开启' : '去开启',
                ),
                color: _installedAppsPermission
                    ? _tertiaryTextColor
                    : _accentColor,
              ),
              onTap: () {
                openInstalledAppsSettings();
              },
            ),
        ],
      ),
      _AuthorizeSettingSection(
        label: _localeText(zh: '扩展能力', en: 'Advanced Access'),
        subtitle: _localeText(
          zh: '按需开启，可获得更完整的系统级能力与兼容性支持。',
          en: 'Enable when needed for broader system-level capabilities and compatibility.',
        ),
        items: [
          if (_publicStorageAvailable)
            _AuthorizeSettingItem(
              icon: Icons.folder_open_rounded,
              title: _localeText(zh: '所有文件访问权限', en: 'All files access'),
              subtitle: _localeText(
                zh: '允许小万访问设备公共存储中的文件与文件夹，用于文件读取、整理和下载等操作。',
                en: 'Allow Omnibot to read and manage files in shared device storage for file tasks and downloads.',
              ),
              trailing: _buildPermissionTrailing(
                label: context.trLegacy(
                  _publicStoragePermission ? '已开启' : '去开启',
                ),
                color: _publicStoragePermission
                    ? _tertiaryTextColor
                    : _accentColor,
              ),
              onTap: openPublicStorageSettings,
            ),
          _AuthorizeSettingItem(
            icon: Icons.adb_rounded,
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
    ];
  }

  Future<void> _checkPermissions() async {
    try {
      final capabilities = await refreshAppEditionCapabilitySnapshot();
      final backgroundRunning = await isBackgroundRunAllowed();
      final overlayPermission =
          await spePermission.invokeMethod('isOverlayPermission') ?? false;
      final installedAppsPermission = capabilities.installedAppsQuery
          ? await spePermission.invokeMethod(
                  'isInstalledAppsPermissionGranted',
                ) ??
                false
          : false;
      final publicStoragePermission = capabilities.publicStorageAccess
          ? await isPublicStorageAccessGranted()
          : false;
      final shizukuStatus = await getShizukuStatus();

      if (mounted) {
        setState(() {
          _capabilities = capabilities;
          _backgroundRunning = backgroundRunning;
          _overlayPermission = overlayPermission;
          _installedAppsPermission = installedAppsPermission;
          _publicStoragePermission = publicStoragePermission;
          _shizukuStatus = shizukuStatus;
        });
      }
    } catch (_) {
      // Permission probes fail closed and retain the last visible state.
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
      body: SafeArea(
        top: false,
        bottom: false,
        child: ListView.separated(
          padding: edgeToEdgeScrollPadding(
            context,
            const EdgeInsets.fromLTRB(18, 12, 18, 28),
          ),
          itemCount: sections.length + 1,
          separatorBuilder: (_, __) => const SizedBox(height: 24),
          itemBuilder: (context, index) {
            if (index == 0) {
              return _buildOverview();
            }
            return _buildSettingsSection(sections[index - 1]);
          },
        ),
      ),
    );
  }

  Widget _buildOverview() {
    final palette = context.omniPalette;
    final progress = _corePermissionProgress.clamp(0.0, 1.0);

    return Column(
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
              fontSize: 20,
              fontWeight: FontWeight.w600,
              color: _titleColor,
              height: 1.4,
              fontFamily: 'PingFang SC',
            ),
          ),
        ),
        const SizedBox(height: 6),
        Text(
          _localeText(
            zh: '建议优先完成与任务执行直接相关的授权，缺失时可能影响悬浮交互和后台任务。',
            en: 'Enable task-critical access first. Missing permissions can interrupt floating assist and background tasks.',
          ),
          style: TextStyle(
            fontSize: 12,
            height: 1.6,
            color: _subtitleColor,
            fontFamily: 'PingFang SC',
          ),
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
              color: _allCorePermissionsEnabled ? _accentColor : _subtitleColor,
              height: 1.5,
              fontFamily: 'PingFang SC',
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildSettingsSection(_AuthorizeSettingSection section) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SettingsSectionTitle(
          label: section.label,
          subtitle: section.subtitle,
          bottomPadding: 8,
        ),
        Column(
          children: List.generate(section.items.length, (index) {
            final isLast = index == section.items.length - 1;
            return Column(
              children: [
                _buildSettingTile(section.items[index], isLast: isLast),
                if (!isLast)
                  Padding(
                    padding: const EdgeInsets.only(left: 30),
                    child: Divider(
                      height: 1,
                      thickness: 1,
                      color: palette.borderSubtle.withValues(
                        alpha: context.isDarkTheme ? 0.5 : 0.78,
                      ),
                    ),
                  ),
              ],
            );
          }),
        ),
      ],
    );
  }

  Widget _buildSettingTile(_AuthorizeSettingItem item, {required bool isLast}) {
    final palette = context.omniPalette;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: item.onTap,
        borderRadius: BorderRadius.circular(14),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: EdgeInsets.fromLTRB(4, 14, 2, isLast ? 14 : 13),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              _buildLeadingIcon(item),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      item.title,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                        color: _titleColor,
                        height: 1.5,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                    if (item.subtitle != null) ...[
                      const SizedBox(height: 2),
                      Text(
                        item.subtitle!,
                        style: TextStyle(
                          fontSize: 11,
                          height: 1.55,
                          color: _subtitleColor,
                          fontFamily: 'PingFang SC',
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              if (item.trailing != null)
                item.trailing!
              else if (item.onTap != null)
                Padding(
                  padding: const EdgeInsets.only(left: 12),
                  child: Icon(
                    Icons.chevron_right_rounded,
                    size: 18,
                    color: _tertiaryTextColor,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildLeadingIcon(_AuthorizeSettingItem item) {
    return SizedBox(
      width: 18,
      height: 18,
      child: Icon(item.icon, size: 18, color: _titleColor),
    );
  }

  Widget _buildPermissionTrailing({
    required String label,
    required Color color,
  }) {
    return Padding(
      padding: const EdgeInsets.only(left: 12),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            label,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w500,
              color: color,
              fontFamily: 'PingFang SC',
            ),
          ),
          const SizedBox(width: 4),
          Icon(
            Icons.chevron_right_rounded,
            size: 18,
            color: _tertiaryTextColor,
          ),
        ],
      ),
    );
  }

  Widget _buildSwitchTrailing({
    required bool value,
    required ValueChanged<bool> onToggle,
  }) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => onToggle(!value),
      child: Padding(
        padding: const EdgeInsets.only(left: 12),
        child: AbsorbPointer(
          child: FlutterSwitch(
            width: 32,
            height: 18.67,
            toggleSize: 11.3,
            padding: 3,
            activeColor: _accentColor,
            inactiveColor: _switchInactiveColor,
            borderRadius: 28.75,
            value: value,
            onToggle: onToggle,
          ),
        ),
      ),
    );
  }
}

class _AuthorizeSettingSection {
  final String label;
  final String? subtitle;
  final List<_AuthorizeSettingItem> items;

  const _AuthorizeSettingSection({
    required this.label,
    this.subtitle,
    required this.items,
  });
}

class _AuthorizeSettingItem {
  final IconData icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;

  const _AuthorizeSettingItem({
    required this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
  });
}
