import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/state/habitual_hand_controller.dart';
import 'package:ui/features/home/state/predictive_back_controller.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/models/chat_startup_behavior.dart';
import 'package:ui/models/habitual_hand.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/hide_from_recents_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/cache_util.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class ExperienceMiscSettingPage extends ConsumerStatefulWidget {
  const ExperienceMiscSettingPage({super.key});

  @override
  ConsumerState<ExperienceMiscSettingPage> createState() =>
      _ExperienceMiscSettingPageState();
}

class _ExperienceMiscSettingPageState
    extends ConsumerState<ExperienceMiscSettingPage> {
  bool _hideFromRecentsEnabled = false;
  bool _vibrationEnabled = true;
  bool _preventScreenSleepDuringTasksEnabled = true;
  bool _taskCompletionNotificationEnabled = true;
  bool _useIndependentChatSendButton = true;
  bool _recentConversationsOnlyEnabled = false;
  ChatStartupBehavior _chatStartupBehavior = ChatStartupBehavior.resumeLast;

  @override
  void initState() {
    super.initState();
    _preventScreenSleepDuringTasksEnabled =
        StorageService.getBool(
          StorageService.kPreventScreenSleepDuringTasksKey,
          defaultValue: true,
        ) ??
        true;

    _taskCompletionNotificationEnabled =
        StorageService.getBool(
          StorageService.kTaskCompletionNotificationEnabledKey,
          defaultValue: true,
        ) ??
        true;

    _useIndependentChatSendButton =
        StorageService.isIndependentChatSendButtonEnabled();
    _recentConversationsOnlyEnabled =
        ConversationService.isRecentConversationsOnlyEnabled();
    _chatStartupBehavior = StorageService.getChatStartupBehavior();
    _loadHideFromRecentsState();
    _loadVibrationState();
    _loadRuntimeTaskState();
  }

  Future<void> _loadHideFromRecentsState() async {
    try {
      final enabled =
          StorageService.getBool('hide_from_recents', defaultValue: false) ??
          false;
      if (!mounted) return;
      setState(() {
        _hideFromRecentsEnabled = enabled;
      });
    } catch (e) {
      debugPrint('Error loading hide from recents state: $e');
    }
  }

  Future<void> _loadVibrationState() async {
    try {
      final enabled = await CacheUtil.getBool(
        'app_vibrate',
        defaultValue: true,
      );
      if (!mounted) return;
      setState(() {
        _vibrationEnabled = enabled;
      });
    } catch (e) {
      debugPrint('Error loading vibration state: $e');
    }
  }

  Future<void> _loadRuntimeTaskState() async {
    try {
      final preventSleep =
          await StorageService.isPreventScreenSleepDuringTasksEnabled();
      final notification =
          await StorageService.isTaskCompletionNotificationEnabled();
      if (!mounted) return;
      setState(() {
        _preventScreenSleepDuringTasksEnabled = preventSleep;
        _taskCompletionNotificationEnabled = notification;
      });
      await AssistsMessageService.setPreventScreenSleepDuringTasksEnabled(
        preventSleep,
      );
      await AssistsMessageService.setTaskCompletionNotificationEnabled(
        notification,
      );
    } catch (e) {
      debugPrint('Error loading runtime task settings: $e');
    }
  }

  Future<void> _onVibrationChanged(bool value) async {
    await CacheUtil.cacheBool('app_vibrate', value);
    if (!mounted) return;
    setState(() {
      _vibrationEnabled = value;
    });
  }

  Future<void> _onHideFromRecentsChanged(bool value) async {
    setState(() {
      _hideFromRecentsEnabled = value;
    });

    final success = await HideFromRecentsService.setExcludeFromRecents(value);
    if (!success) {
      if (!mounted) return;
      setState(() {
        _hideFromRecentsEnabled = !value;
      });
      showToast(context.l10n.settingsHideRecentsFailed, type: ToastType.error);
    }
  }

  Future<void> _onPreventScreenSleepDuringTasksChanged(bool value) async {
    try {
      await StorageService.setPreventScreenSleepDuringTasksEnabled(value);
      final synced =
          await AssistsMessageService.setPreventScreenSleepDuringTasksEnabled(
            value,
          );
      if (!synced) {
        throw Exception('native_sync_failed');
      }
      if (!mounted) return;
      setState(() {
        _preventScreenSleepDuringTasksEnabled = value;
      });
    } catch (e) {
      if (!mounted) return;
      showToast(context.l10n.settingsSaveFailed, type: ToastType.error);
    }
  }

  Future<void> _onTaskCompletionNotificationChanged(bool value) async {
    try {
      if (value) {
        final granted = await ensureNotificationPermission();
        if (!granted) {
          if (!mounted) return;
          showToast(context.trLegacy('需要开启通知权限'), type: ToastType.error);
          return;
        }
      }
      await StorageService.setTaskCompletionNotificationEnabled(value);
      final synced =
          await AssistsMessageService.setTaskCompletionNotificationEnabled(
            value,
          );
      if (!synced) {
        throw Exception('native_sync_failed');
      }
      if (!mounted) return;
      setState(() {
        _taskCompletionNotificationEnabled = value;
      });
    } catch (e) {
      if (!mounted) return;
      showToast(context.l10n.settingsSaveFailed, type: ToastType.error);
    }
  }

  Future<void> _onIndependentChatSendButtonChanged(bool value) async {
    final saved = await StorageService.setIndependentChatSendButtonEnabled(
      value,
    );
    if (!mounted) return;
    if (!saved) {
      showToast(context.l10n.settingsSaveFailed, type: ToastType.error);
      return;
    }
    setState(() {
      _useIndependentChatSendButton = value;
    });
  }

  Future<void> _onRecentConversationsOnlyChanged(bool value) async {
    final saved = await ConversationService.setRecentConversationsOnlyEnabled(
      value,
    );
    if (!mounted) return;
    if (!saved) {
      showToast(context.l10n.settingsSaveFailed, type: ToastType.error);
      return;
    }
    setState(() {
      _recentConversationsOnlyEnabled = value;
    });
  }

  Future<void> _onChatStartupBehaviorChanged(ChatStartupBehavior? value) async {
    if (value == null) {
      return;
    }
    final saved = await StorageService.setChatStartupBehavior(value);
    if (!mounted) return;
    if (!saved) {
      showToast(context.l10n.settingsSaveFailed, type: ToastType.error);
      return;
    }
    setState(() {
      _chatStartupBehavior = value;
    });
  }

  Future<void> _onHabitualHandChanged(HabitualHand? value) async {
    if (value == null) {
      return;
    }
    final saved = await ref
        .read(habitualHandProvider.notifier)
        .setHabitualHand(value);
    if (!saved && mounted) {
      showToast(context.l10n.settingsSaveFailed, type: ToastType.error);
    }
  }

  Future<void> _onPredictiveBackChanged(bool value) async {
    final saved = await ref
        .read(predictiveBackEnabledProvider.notifier)
        .setEnabled(value);
    if (!saved && mounted) {
      showToast(context.l10n.settingsSaveFailed, type: ToastType.error);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final habitualHand = ref.watch(habitualHandProvider);
    final predictiveBackEnabled = ref.watch(predictiveBackEnabledProvider);
    final sections = [
      _SettingSection(
        label: context.trLegacy('杂项'),
        items: [
          _SettingItem(
            icon: LucideIcons.alarmClock,
            title: context.l10n.settingsAlarmTitle,
            subtitle: context.l10n.settingsAlarmSubtitle,
            onTap: () {
              GoRouterManager.push('/home/alarm_setting');
            },
          ),
          _SettingItem(
            icon: LucideIcons.house,
            title: context.trLegacy('首页设置'),
            subtitle: context.trLegacy('管理聊天首页问候语和快捷指令'),
            onTap: () {
              GoRouterManager.push('/home/home_setting');
            },
          ),
          _SettingItem(
            icon: LucideIcons.power,
            title: context.trLegacy('启动时'),
            subtitle: context.trLegacy('选择应用启动后打开的对话'),
            trailing: _buildStartupBehaviorDropdown(_chatStartupBehavior),
          ),
          _SettingItem(
            icon: Icons.auto_delete_outlined,
            title: context.l10n.settingsRecentConversationsOnlyTitle,
            subtitle: context.l10n.settingsRecentConversationsOnlySubtitle,
            trailing: _buildSwitchTrailing(
              value: _recentConversationsOnlyEnabled,
              onToggle: _onRecentConversationsOnlyChanged,
            ),
          ),
          _SettingItem(
            icon: LucideIcons.eyeOff,
            title: context.l10n.settingsHideRecentsTitle,
            subtitle: context.l10n.settingsHideRecentsSubtitle,
            trailing: _buildSwitchTrailing(
              value: _hideFromRecentsEnabled,
              onToggle: _onHideFromRecentsChanged,
            ),
          ),
          _SettingItem(
            icon: LucideIcons.vibrate,
            title: context.l10n.settingsVibrationTitle,
            subtitle: context.l10n.settingsVibrationSubtitle,
            trailing: _buildSwitchTrailing(
              value: _vibrationEnabled,
              onToggle: _onVibrationChanged,
            ),
          ),
          _SettingItem(
            icon: LucideIcons.cornerDownLeft,
            title: context.l10n.settingsIndependentSendButtonTitle,
            subtitle: context.l10n.settingsIndependentSendButtonSubtitle,
            trailing: _buildSwitchTrailing(
              value: _useIndependentChatSendButton,
              onToggle: _onIndependentChatSendButtonChanged,
            ),
          ),
          // 所有系统版本可见:低版本系统无预测返回事件,开关关闭时行为与旧版一致
          _SettingItem(
            icon: Icons.swipe_left_rounded,
            title: context.l10n.settingsPredictiveBackTitle,
            subtitle: context.l10n.settingsPredictiveBackSubtitle,
            trailing: _buildSwitchTrailing(
              value: predictiveBackEnabled,
              onToggle: _onPredictiveBackChanged,
            ),
          ),
          _SettingItem(
            icon: LucideIcons.smartphone,
            title: context.trLegacy('防止任务运行时屏幕休眠'),
            subtitle: context.trLegacy('任务运行期间保持屏幕常亮，适用于小万（OmniAi）、Agent 和纯聊天'),
            trailing: _buildSwitchTrailing(
              value: _preventScreenSleepDuringTasksEnabled,
              onToggle: _onPreventScreenSleepDuringTasksChanged,
            ),
          ),
          _SettingItem(
            icon: LucideIcons.bellRing,
            title: context.trLegacy('任务完成通知'),
            subtitle: context.trLegacy('小万（OmniAi）、Agent 和纯聊天完成后推送提醒'),
            trailing: _buildSwitchTrailing(
              value: _taskCompletionNotificationEnabled,
              onToggle: _onTaskCompletionNotificationChanged,
            ),
          ),
          _SettingItem(
            icon: LucideIcons.folderUp,
            title: context.trLegacy('使用小万打开'),
            subtitle: context.trLegacy('分别设置图片和文件的打开方式'),
            onTap: () {
              GoRouterManager.push('/home/open_with_omnibot_setting');
            },
          ),
          _SettingItem(
            icon: LucideIcons.hand,
            title: context.l10n.settingsHabitualHandTitle,
            subtitle: context.l10n.settingsHabitualHandSubtitle,
            trailing: _buildHabitualHandDropdown(habitualHand),
          ),
          _SettingItem(
            icon: LucideIcons.graduationCap,
            title: context.trLegacy('快速开始'),
            subtitle: context.trLegacy('重新查看基础配置与可选插件说明'),
            onTap: () {
              GoRouterManager.push('/home/first_use_tutorial/setup');
            },
          ),
        ],
      ),
    ];

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(title: context.trLegacy('杂项'), primary: true),
      body: SafeArea(
        top: false,
        bottom: false,
        child: ListView.separated(
          padding: edgeToEdgeScrollPadding(
            context,
            const EdgeInsets.fromLTRB(18, 10, 18, 28),
          ),
          itemCount: sections.length,
          separatorBuilder: (_, __) => const SizedBox(height: 24),
          itemBuilder: (context, index) {
            return _buildSettingsSection(sections[index]);
          },
        ),
      ),
    );
  }

  Widget _buildSettingsSection(_SettingSection section) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 0, 4, 10),
          child: Row(
            children: [
              Text(
                context.trLegacy(section.label),
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 0,
                  color: palette.textTertiary,
                  fontFamily: 'PingFang SC',
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Container(
                  height: 1,
                  color: palette.borderSubtle.withValues(
                    alpha: context.isDarkTheme ? 0.56 : 0.8,
                  ),
                ),
              ),
            ],
          ),
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

  Widget _buildSettingTile(_SettingItem item, {required bool isLast}) {
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
                      context.trLegacy(item.title),
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                        color: palette.textPrimary,
                        height: 1.5,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                    if (item.subtitle != null) ...[
                      const SizedBox(height: 2),
                      Text(
                        context.trLegacy(item.subtitle!),
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 11,
                          fontFamily: 'PingFang SC',
                          fontWeight: FontWeight.w400,
                          height: 1.55,
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
                    LucideIcons.chevronRight,
                    size: 18,
                    color: palette.textTertiary,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildLeadingIcon(_SettingItem item) {
    final palette = context.omniPalette;
    final iconColor = palette.textPrimary;
    return SizedBox(
      width: 18,
      height: 18,
      child: item.icon != null
          ? Icon(item.icon, size: 18, color: iconColor)
          : const SizedBox.shrink(),
    );
  }

  Widget _buildHabitualHandDropdown(HabitualHand value) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(left: 12),
      child: SizedBox(
        width: 82,
        child: DropdownButtonHideUnderline(
          child: DropdownButton<HabitualHand>(
            value: value,
            isDense: true,
            isExpanded: true,
            borderRadius: BorderRadius.circular(10),
            dropdownColor: palette.surfacePrimary,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 12,
              fontWeight: FontWeight.w500,
            ),
            icon: Icon(
              LucideIcons.chevronDown,
              size: 18,
              color: palette.textTertiary,
            ),
            items: [
              DropdownMenuItem(
                value: HabitualHand.left,
                child: _buildDropdownText(
                  context.l10n.settingsHabitualHandLeft,
                ),
              ),
              DropdownMenuItem(
                value: HabitualHand.right,
                child: _buildDropdownText(
                  context.l10n.settingsHabitualHandRight,
                ),
              ),
            ],
            onChanged: _onHabitualHandChanged,
          ),
        ),
      ),
    );
  }

  Widget _buildDropdownText(String text) {
    return Text(text, maxLines: 1, overflow: TextOverflow.ellipsis);
  }

  Widget _buildStartupBehaviorDropdown(ChatStartupBehavior value) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(left: 12),
      child: SizedBox(
        width: 132,
        child: DropdownButtonHideUnderline(
          child: DropdownButton<ChatStartupBehavior>(
            value: value,
            isDense: true,
            isExpanded: true,
            borderRadius: BorderRadius.circular(10),
            dropdownColor: palette.surfacePrimary,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 12,
              fontWeight: FontWeight.w500,
            ),
            icon: Icon(
              LucideIcons.chevronDown,
              size: 18,
              color: palette.textTertiary,
            ),
            items: ChatStartupBehavior.values
                .map(
                  (behavior) => DropdownMenuItem(
                    value: behavior,
                    child: _buildDropdownText(
                      context.trLegacy(behavior.legacyLabel),
                    ),
                  ),
                )
                .toList(),
            onChanged: _onChatStartupBehaviorChanged,
          ),
        ),
      ),
    );
  }

  Widget _buildSwitchTrailing({
    required bool value,
    required ValueChanged<bool> onToggle,
  }) {
    final palette = context.omniPalette;
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
            activeColor: palette.accentPrimary,
            inactiveColor: palette.borderStrong,
            borderRadius: 28.75,
            value: value,
            onToggle: onToggle,
          ),
        ),
      ),
    );
  }
}

class _SettingSection {
  final String label;
  final List<_SettingItem> items;

  const _SettingSection({required this.label, required this.items});
}

class _SettingItem {
  final IconData? icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;

  const _SettingItem({
    this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
  });
}
