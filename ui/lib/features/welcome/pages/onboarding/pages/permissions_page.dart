import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/theme_context.dart';

import '../onboarding_l10n.dart';
import '../onboarding_permission_controller.dart';
import '../widgets/onboarding_page_scaffold.dart';

/// Step 4: grant the app permissions that power floating reminders,
/// background execution, and app detection.
class OnboardingPermissionsPage extends StatelessWidget {
  const OnboardingPermissionsPage({
    super.key,
    required this.controller,
    required this.scrollController,
    required this.onRequestShizuku,
  });

  final OnboardingPermissionController controller;
  final ScrollController scrollController;
  final FutureOr<void> Function() onRequestShizuku;

  @override
  Widget build(BuildContext context) {
    return OnboardingPageScaffold(
      icon: LucideIcons.shieldCheck,
      title: onbTr(context, '开启应用权限', 'Enable app permissions'),
      description: onbTr(
        context,
        controller.installedAppsAvailable
            ? '这些授权用于悬浮提醒、后台运行和识别应用，之后可随时在“设置-应用权限授权”中调整。'
            : '这些授权用于悬浮提醒和后台运行，之后可随时在“设置-应用权限授权”中调整。',
        controller.installedAppsAvailable
            ? 'These permissions power floating reminders, background execution, and app detection. Adjust them anytime in settings.'
            : 'These permissions power floating reminders and background execution. Adjust them anytime in settings.',
      ),
      scrollController: scrollController,
      children: [
        _CoreProgressOverview(controller: controller),
        const SizedBox(height: 26),
        _SectionLabel(label: onbTr(context, '核心权限', 'Core permissions')),
        const SizedBox(height: 6),
        _PermissionRow(
          tapKey: const ValueKey('tutorial-permission-battery'),
          icon: LucideIcons.batteryCharging,
          title: onbTr(context, '后台运行权限', 'Background running'),
          subtitle: onbTr(
            context,
            '减少系统回收，让消息、定时任务和本机服务在后台稳定继续。',
            'Reduce system cleanup so messages, scheduled tasks, and local services can continue reliably.',
          ),
          granted: controller.backgroundRunning,
          actionLabel: onbTr(context, '去开启', 'Enable'),
          onTap: controller.openBatterySettings,
        ),
        _PermissionRow(
          tapKey: const ValueKey('tutorial-permission-overlay'),
          icon: LucideIcons.pictureInPicture2,
          title: onbTr(context, '悬浮窗权限', 'Floating window'),
          subtitle: onbTr(
            context,
            '允许小万在其他应用上方显示宠物、半屏聊天和任务提醒。',
            'Allow Omnibot to show the pet, half-screen chat, and task reminders above other apps.',
          ),
          granted: controller.overlay,
          actionLabel: onbTr(context, '去开启', 'Enable'),
          onTap: controller.openOverlaySettings,
        ),
        if (controller.installedAppsAvailable)
          _PermissionRow(
            tapKey: const ValueKey('tutorial-permission-apps'),
            icon: LucideIcons.layoutGrid,
            title: onbTr(context, '应用列表读取', 'Installed apps access'),
            subtitle: onbTr(
              context,
              '用于识别设备已安装应用，并提供应用上下文。',
              'Used to identify installed apps and provide app context.',
            ),
            granted: controller.installedApps,
            actionLabel: onbTr(context, '去开启', 'Enable'),
            onTap: controller.openInstalledAppsSettings,
          ),
        const SizedBox(height: 26),
        _SectionLabel(label: onbTr(context, '扩展能力', 'Advanced access')),
        const SizedBox(height: 6),
        if (controller.publicStorageAvailable)
          _PermissionRow(
            tapKey: const ValueKey('tutorial-permission-storage'),
            icon: LucideIcons.folderOpen,
            title: onbTr(context, '所有文件访问权限', 'All files access'),
            subtitle: onbTr(
              context,
              '允许小万访问设备公共存储中的文件与文件夹，用于文件读取、整理和下载等操作。',
              'Allow Omnibot to read and manage files in shared device storage for file tasks and downloads.',
            ),
            granted: controller.publicStorage,
            actionLabel: onbTr(context, '去开启', 'Enable'),
            onTap: controller.openStorageSettings,
          ),
        _PermissionRow(
          tapKey: const ValueKey('tutorial-permission-shizuku'),
          icon: LucideIcons.shieldPlus,
          title: onbTr(context, 'Shizuku 权限', 'Shizuku access'),
          subtitle: controller.shizukuStatus.localizedGuide,
          granted: controller.shizukuStatus.isGranted,
          actionLabel: controller.shizukuStatus.localizedStatusLabel,
          onTap: onRequestShizuku,
        ),
        const SizedBox(height: 26),
        _SectionLabel(label: onbTr(context, '通知与提醒', 'Notifications')),
        const SizedBox(height: 6),
        _NotificationRow(controller: controller),
      ],
    );
  }
}

class _SectionLabel extends StatelessWidget {
  const _SectionLabel({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Text(
      label,
      style: Theme.of(context).textTheme.labelMedium?.copyWith(
        color: context.omniPalette.textTertiary,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.4,
      ),
    );
  }
}

/// Slim readiness meter for the three core permissions.
class _CoreProgressOverview extends StatelessWidget {
  const _CoreProgressOverview({required this.controller});

  final OnboardingPermissionController controller;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final reduceMotion =
        MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    final ready = controller.coreReadyCount;
    final total = controller.coreTotal;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        AnimatedSwitcher(
          duration: reduceMotion
              ? Duration.zero
              : const Duration(milliseconds: 220),
          child: Text(
            onbTr(
              context,
              '$ready / $total 项核心授权已就绪',
              '$ready of $total core permissions ready',
            ),
            key: ValueKey<int>(ready),
            style: Theme.of(context).textTheme.titleMedium?.copyWith(
              color: palette.textPrimary,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
        const SizedBox(height: 10),
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
                  duration: reduceMotion
                      ? Duration.zero
                      : const Duration(milliseconds: 240),
                  curve: Curves.easeOutCubic,
                  width: constraints.maxWidth * controller.coreProgress,
                  decoration: BoxDecoration(
                    color: palette.accentPrimary,
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
              ),
            );
          },
        ),
      ],
    );
  }
}

/// Flat permission row: leading icon, title/subtitle, and an animated
/// granted-check or an action label with a chevron.
class _PermissionRow extends StatelessWidget {
  const _PermissionRow({
    required this.tapKey,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.granted,
    required this.actionLabel,
    required this.onTap,
  });

  final Key tapKey;
  final IconData icon;
  final String title;
  final String subtitle;
  final bool granted;
  final String actionLabel;
  final FutureOr<void> Function() onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: tapKey,
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: palette.accentPrimary.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(11),
                ),
                alignment: Alignment.center,
                child: Icon(icon, size: 18, color: palette.accentPrimary),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        color: palette.textPrimary,
                        fontWeight: FontWeight.w600,
                        height: 1.4,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      subtitle,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: palette.textSecondary,
                        height: 1.5,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 10),
              AnimatedSwitcher(
                duration: const Duration(milliseconds: 180),
                transitionBuilder: (child, animation) => ScaleTransition(
                  scale: animation,
                  child: FadeTransition(opacity: animation, child: child),
                ),
                child: granted
                    ? Container(
                        key: const ValueKey('granted'),
                        width: 24,
                        height: 24,
                        decoration: BoxDecoration(
                          color: palette.accentPrimary,
                          shape: BoxShape.circle,
                        ),
                        alignment: Alignment.center,
                        child: Icon(
                          LucideIcons.check,
                          size: 14,
                          color: Theme.of(context).colorScheme.onPrimary,
                        ),
                      )
                    : Row(
                        key: const ValueKey('pending'),
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            actionLabel,
                            style: Theme.of(context).textTheme.labelMedium
                                ?.copyWith(
                                  color: palette.accentPrimary,
                                  fontWeight: FontWeight.w600,
                                ),
                          ),
                          const SizedBox(width: 2),
                          Icon(
                            LucideIcons.chevronRight,
                            size: 16,
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

/// Notification preference row with the same switch used in settings.
class _NotificationRow extends StatelessWidget {
  const _NotificationRow({required this.controller});

  final OnboardingPermissionController controller;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: const ValueKey('tutorial-permission-notification'),
        onTap: () =>
            controller.setNotificationEnabled(!controller.notificationEnabled),
        borderRadius: BorderRadius.circular(14),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: palette.accentPrimary.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(11),
                ),
                alignment: Alignment.center,
                child: Icon(
                  LucideIcons.bell,
                  size: 18,
                  color: palette.accentPrimary,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      onbTr(context, '接收消息通知', 'Receive notifications'),
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        color: palette.textPrimary,
                        fontWeight: FontWeight.w600,
                        height: 1.4,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      onbTr(
                        context,
                        '任务进度与结果会通过通知提醒你。',
                        'Get notified about task progress and results.',
                      ),
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: palette.textSecondary,
                        height: 1.5,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 10),
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => controller.setNotificationEnabled(
                  !controller.notificationEnabled,
                ),
                child: AbsorbPointer(
                  child: FlutterSwitch(
                    width: 32,
                    height: 18.67,
                    toggleSize: 11.3,
                    padding: 3,
                    activeColor: palette.accentPrimary,
                    inactiveColor: palette.borderStrong,
                    borderRadius: 28.75,
                    value: controller.notificationEnabled,
                    onToggle: controller.setNotificationEnabled,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
