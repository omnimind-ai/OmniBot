import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_segmented_slider.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:flutter/cupertino.dart';
import 'package:ui/models/scheduled_task.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/widgets/settings_ui.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

/// 定时任务配置底部弹窗
class ScheduleTaskSheet extends StatefulWidget {
  /// 现有的定时任务（用于编辑）
  final ScheduledTask existingTask;

  const ScheduleTaskSheet({super.key, required this.existingTask});

  @override
  State<ScheduleTaskSheet> createState() => _ScheduleTaskSheetState();

  /// 显示定时任务配置弹窗
  static Future<ScheduledTask?> show({
    required BuildContext context,
    required ScheduledTask existingTask,
  }) {
    return showSettingsDetailSheet<ScheduledTask>(
      context: context,
      isScrollControlled: true,
      builder: (context) => ScheduleTaskSheet(existingTask: existingTask),
    );
  }
}

class _ScheduleTaskSheetState extends State<ScheduleTaskSheet> {
  /// 当前选择的标签页索引 (0: 固定时间, 1: 倒计时)
  int _selectedTabIndex = 0;

  /// 是否每日重复
  bool _repeatDaily = false;

  /// 固定时间
  TimeOfDay _selectedTime = TimeOfDay.now();

  /// 倒计时分钟数
  int _countdownMinutes = 30;

  /// PageController for tab switching
  late PageController _pageController;

  @override
  void initState() {
    super.initState();

    final task = widget.existingTask;
    _selectedTabIndex = task.type == ScheduledTaskType.fixedTime ? 0 : 1;
    _repeatDaily = task.repeatDaily;

    if (task.type == ScheduledTaskType.fixedTime && task.fixedTime != null) {
      final parts = task.fixedTime!.split(':');
      if (parts.length == 2) {
        _selectedTime = TimeOfDay(
          hour: int.tryParse(parts[0]) ?? 0,
          minute: int.tryParse(parts[1]) ?? 0,
        );
      }
    } else if (task.type == ScheduledTaskType.countdown) {
      _countdownMinutes = task.countdownMinutes ?? 30;
    }

    _pageController = PageController(initialPage: _selectedTabIndex);
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final textScaler = MediaQuery.textScalerOf(context);
    final pickerHeight =
        (textScaler.scale(36) * 1.35 + textScaler.scale(15) * 1.35 + 40)
            .clamp(200.0, double.infinity)
            .toDouble();
    return SettingsDetailSheet(
      title: LegacyTextLocalizer.isEnglish ? 'Set scheduled task' : '设置定时任务',
      headerAction: OmniCircleButton(
        key: const ValueKey('schedule-task-close'),
        icon: LucideIcons.x,
        size: 40,
        tapTargetSize: MaterialTapTargetSize.padded,
        tooltip: MaterialLocalizations.of(context).closeButtonTooltip,
        onPressed: () => Navigator.pop(context),
      ),
      body: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: palette.surfaceSecondary,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                Icon(LucideIcons.clock, size: 20, color: palette.accentPrimary),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    widget.existingTask.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 15, color: palette.textPrimary),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),
          _buildTabSelector(),
          const SizedBox(height: 12),
          SizedBox(
            height: pickerHeight,
            child: PageView(
              controller: _pageController,
              onPageChanged: (index) {
                setState(() => _selectedTabIndex = index);
              },
              children: [_buildFixedTimeTab(), _buildCountdownTab()],
            ),
          ),
          if (_selectedTabIndex == 0)
            Row(
              children: [
                Expanded(
                  child: Text(
                    LegacyTextLocalizer.isEnglish ? 'Repeat daily' : '每日重复执行',
                    style: TextStyle(fontSize: 15, color: palette.textPrimary),
                  ),
                ),
                const SizedBox(width: 12),
                OmniSettingsSwitch(
                  value: _repeatDaily,
                  onToggle: (value) => setState(() => _repeatDaily = value),
                ),
              ],
            ),
        ],
      ),
      footer: SizedBox(
        width: double.infinity,
        child: ElevatedButton(
          key: const ValueKey('schedule-task-confirm'),
          onPressed: _onConfirm,
          style: ElevatedButton.styleFrom(
            minimumSize: const Size(0, 48),
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            backgroundColor: palette.accentPrimary,
            foregroundColor: Theme.of(context).colorScheme.onPrimary,
            shape: const StadiumBorder(),
            elevation: 0,
          ),
          child: Text(
            LegacyTextLocalizer.localize('确认'),
            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
          ),
        ),
      ),
    );
  }

  Widget _buildTabSelector() => OmniSegmentedSlider<int>(
    keyPrefix: 'schedule-time-mode',
    value: _selectedTabIndex,
    options: [
      OmniSegmentedOption(
        value: 0,
        label: LegacyTextLocalizer.isEnglish ? 'Fixed time' : '固定时间',
      ),
      OmniSegmentedOption(
        value: 1,
        label: LegacyTextLocalizer.isEnglish ? 'Countdown' : '倒计时',
      ),
    ],
    onChanged: (index) {
      if (_selectedTabIndex == index) return;
      setState(() => _selectedTabIndex = index);
      _pageController.animateToPage(
        index,
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeInOut,
      );
    },
  );

  /// 构建固定时间选择
  Widget _buildFixedTimeTab() {
    final palette = context.omniPalette;
    return SizedBox(
      height: 200,
      child: CupertinoTheme(
        data: CupertinoThemeData(
          brightness: context.isDarkTheme ? Brightness.dark : Brightness.light,
          primaryColor: palette.accentPrimary,
          textTheme: CupertinoTextThemeData(
            dateTimePickerTextStyle: TextStyle(
              fontSize: 26, // 增大字体
              color: palette.textPrimary,
            ),
          ),
        ),
        child: CupertinoDatePicker(
          backgroundColor: palette.surfacePrimary,
          mode: CupertinoDatePickerMode.time,
          initialDateTime: DateTime(
            DateTime.now().year,
            DateTime.now().month,
            DateTime.now().day,
            _selectedTime.hour,
            _selectedTime.minute,
          ),
          onDateTimeChanged: (DateTime newDateTime) {
            setState(() {
              _selectedTime = TimeOfDay.fromDateTime(newDateTime);
            });
          },
          use24hFormat: true,
          itemExtent: 40, // 增加行高
        ),
      ),
    );
  }

  /// 构建倒计时选择
  Widget _buildCountdownTab() {
    final palette = context.omniPalette;
    return Center(
      child: Row(
        children: [
          _buildCountdownButton(
            icon: LucideIcons.minus,
            tooltip: LegacyTextLocalizer.isEnglish
                ? 'Subtract 5 minutes'
                : '减少 5 分钟',
            onTap: _countdownMinutes > 5
                ? () => setState(() => _countdownMinutes -= 5)
                : null,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: TextButton(
              key: const ValueKey('schedule-countdown-input'),
              onPressed: _showCountdownInputDialog,
              style: TextButton.styleFrom(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 16,
                ),
                backgroundColor: palette.surfaceSecondary,
                foregroundColor: palette.textPrimary,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(16),
                ),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(
                      _formatCountdown(_countdownMinutes),
                      style: TextStyle(
                        fontSize: 36,
                        fontWeight: FontWeight.w300,
                        color: palette.textPrimary,
                      ),
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    LegacyTextLocalizer.isEnglish ? 'Execute after' : '后执行',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 15,
                      color: palette.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(width: 12),
          _buildCountdownButton(
            icon: LucideIcons.plus,
            tooltip: LegacyTextLocalizer.isEnglish
                ? 'Add 5 minutes'
                : '增加 5 分钟',
            onTap: _countdownMinutes < 1440
                ? () => setState(() => _countdownMinutes += 5)
                : null,
          ),
        ],
      ),
    );
  }

  /// 显示倒计时输入对话框
  Future<void> _showCountdownInputDialog() async {
    final minutes = await showOmniGlassCardSheet<int>(
      context: context,
      useRootNavigator: false,
      builder: (_) => _CountdownInputDialog(initialMinutes: _countdownMinutes),
    );

    if (!mounted || minutes == null) return;
    setState(() {
      _countdownMinutes = minutes;
    });
  }

  /// 格式化倒计时显示
  String _formatCountdown(int minutes) {
    final isEnglish = LegacyTextLocalizer.isEnglish;
    if (minutes >= 60) {
      final hours = minutes ~/ 60;
      final mins = minutes % 60;
      if (mins > 0) {
        return '${hours}h ${mins}m';
      }
      return isEnglish ? '${hours}h' : '$hours小时';
    }
    return isEnglish ? '${minutes}m' : '$minutes分钟';
  }

  /// Uses the shared glass control for touch, focus and keyboard activation.
  Widget _buildCountdownButton({
    required IconData icon,
    required String tooltip,
    required VoidCallback? onTap,
  }) => OmniCircleButton(
    icon: icon,
    tooltip: tooltip,
    size: 40,
    tapTargetSize: MaterialTapTargetSize.padded,
    foregroundColor: context.omniPalette.accentPrimary,
    onPressed: onTap,
  );

  /// 确认创建定时任务
  void _onConfirm() {
    final task = ScheduledTask(
      id: widget.existingTask.id,
      title: widget.existingTask.title,
      targetKind: 'subagent',
      subagentConversationId: widget.existingTask.subagentConversationId,
      parentConversationId: widget.existingTask.parentConversationId,
      parentConversationMode: widget.existingTask.parentConversationMode,
      subagentPrompt: widget.existingTask.subagentPrompt,
      notificationEnabled: widget.existingTask.notificationEnabled,
      type: _selectedTabIndex == 0
          ? ScheduledTaskType.fixedTime
          : ScheduledTaskType.countdown,
      fixedTime: _selectedTabIndex == 0
          ? '${_selectedTime.hour.toString().padLeft(2, '0')}:${_selectedTime.minute.toString().padLeft(2, '0')}'
          : null,
      countdownMinutes: _selectedTabIndex == 1 ? _countdownMinutes : null,
      repeatDaily: _repeatDaily,
      isEnabled: true,
      createdAt: widget.existingTask.createdAt,
    );

    // 计算下次执行时间
    final taskWithNextTime = task.copyWith(
      nextExecutionTime: task.calculateNextExecutionTime(),
    );

    Navigator.pop(context, taskWithNextTime);
  }
}

class _CountdownInputDialog extends StatefulWidget {
  const _CountdownInputDialog({required this.initialMinutes});

  final int initialMinutes;

  @override
  State<_CountdownInputDialog> createState() => _CountdownInputDialogState();
}

class _CountdownInputDialogState extends State<_CountdownInputDialog> {
  late final TextEditingController _controller;
  final FocusNode _focusNode = FocusNode();
  String? _errorText;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.initialMinutes.toString());
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _focusNode.requestFocus();
      }
    });
  }

  @override
  void dispose() {
    _focusNode.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _close([int? value]) {
    _focusNode.unfocus();
    Navigator.of(context).pop(value);
  }

  void _submit() {
    final minutes = int.tryParse(_controller.text.trim());
    if (minutes == null || minutes <= 0 || minutes > 1440) {
      setState(() {
        _errorText = LegacyTextLocalizer.isEnglish
            ? 'Enter minutes between 1 and 1440'
            : '请输入 1-1440 之间的分钟数';
      });
      return;
    }
    _close(minutes);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final inputBorder = OutlineInputBorder(
      borderSide: BorderSide(color: palette.borderStrong),
    );
    final focusedInputBorder = OutlineInputBorder(
      borderSide: BorderSide(color: palette.accentPrimary),
    );
    return PopScope(
      canPop: true,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) _focusNode.unfocus();
      },
      child: OmniGlassAlertDialog(
        backgroundColor: palette.surfacePrimary,
        surfaceTintColor: Colors.transparent,
        title: Text(
          LegacyTextLocalizer.isEnglish ? 'Set countdown' : '设置倒计时',
          style: TextStyle(color: palette.textPrimary),
        ),
        content: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Expanded(
              child: TextField(
                controller: _controller,
                focusNode: _focusNode,
                keyboardType: TextInputType.number,
                style: TextStyle(color: palette.textPrimary),
                decoration: InputDecoration(
                  suffixText: LegacyTextLocalizer.isEnglish ? 'min' : '分钟',
                  suffixStyle: TextStyle(color: palette.textSecondary),
                  border: inputBorder,
                  enabledBorder: inputBorder,
                  focusedBorder: focusedInputBorder,
                  errorBorder: inputBorder.copyWith(
                    borderSide: const BorderSide(color: Colors.redAccent),
                  ),
                  focusedErrorBorder: focusedInputBorder.copyWith(
                    borderSide: const BorderSide(color: Colors.redAccent),
                  ),
                  errorText: _errorText,
                ),
                onChanged: (_) {
                  if (_errorText != null) {
                    setState(() {
                      _errorText = null;
                    });
                  }
                },
                onSubmitted: (_) => _submit(),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => _close(),
            child: Text(LegacyTextLocalizer.isEnglish ? 'Cancel' : '取消'),
          ),
          TextButton(
            onPressed: _submit,
            child: Text(LegacyTextLocalizer.isEnglish ? 'OK' : '确定'),
          ),
        ],
      ),
    );
  }
}
