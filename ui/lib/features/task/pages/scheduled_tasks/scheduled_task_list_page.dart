import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/features/task/pages/scheduled_tasks/widgets/schedule_task_sheet.dart';
import 'package:ui/models/scheduled_task.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/scheduled_task_scheduler_service.dart';
import 'package:ui/services/scheduled_task_storage_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

/// 定时任务列表页面
class ScheduledTaskListPage extends StatefulWidget {
  const ScheduledTaskListPage({super.key, this.initialTab});

  final String? initialTab;

  @override
  State<ScheduledTaskListPage> createState() => _ScheduledTaskListPageState();
}

class _ScheduledTaskListPageState extends State<ScheduledTaskListPage> {
  static const int _scheduleTab = 0;
  static const int _alarmTab = 1;

  late final PageController _pageController;
  int _currentTab = _scheduleTab;
  double _tabSwitcherDragDelta = 0;

  List<ScheduledTask> _scheduledTasks = [];
  List<_AgentExactAlarmItem> _exactAlarms = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _currentTab = _resolveInitialTab(widget.initialTab);
    _pageController = PageController(initialPage: _currentTab);
    _loadPageData();
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  int _resolveInitialTab(String? rawTab) {
    if ((rawTab ?? '').toLowerCase() == 'alarm') {
      return _alarmTab;
    }
    return _scheduleTab;
  }

  Future<void> _loadPageData() async {
    setState(() {
      _isLoading = true;
    });

    try {
      final results = await Future.wait<dynamic>([
        _fetchScheduledTasks(),
        _fetchExactAlarms(),
      ]);

      if (!mounted) return;
      setState(() {
        _scheduledTasks = results[0] as List<ScheduledTask>;
        _exactAlarms = results[1] as List<_AgentExactAlarmItem>;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<List<ScheduledTask>> _fetchScheduledTasks() async {
    final tasks = await ScheduledTaskStorageService.loadScheduledTasks();
    tasks.sort((a, b) {
      final aTime = a.nextExecutionTime ?? 0;
      final bTime = b.nextExecutionTime ?? 0;
      return aTime.compareTo(bTime);
    });
    return tasks;
  }

  Future<void> _reloadScheduledTasksOnly() async {
    try {
      final tasks = await _fetchScheduledTasks();
      if (!mounted) return;
      setState(() {
        _scheduledTasks = tasks;
      });
    } catch (e) {
    }
  }

  Future<List<_AgentExactAlarmItem>> _fetchExactAlarms() async {
    final rawItems = await AssistsMessageService.listAgentExactAlarms();
    final items = rawItems
        .map(_AgentExactAlarmItem.fromMap)
        .where((item) => item.triggerAtMillis > 0)
        .toList();
    items.sort((a, b) => a.triggerAtMillis.compareTo(b.triggerAtMillis));
    return items;
  }

  Future<void> _reloadExactAlarmsOnly() async {
    try {
      final items = await _fetchExactAlarms();
      if (!mounted) return;
      setState(() {
        _exactAlarms = items;
      });
    } catch (e) {
    }
  }

  void _switchTab(int tabIndex) {
    if (_currentTab == tabIndex) return;
    setState(() {
      _currentTab = tabIndex;
    });
    _pageController.animateToPage(
      tabIndex,
      duration: const Duration(milliseconds: 240),
      curve: Curves.easeOutCubic,
    );
  }

  void _handleTabSwitcherDragEnd({double velocity = 0}) {
    final shouldSwitchRight = (_tabSwitcherDragDelta + velocity * 0.015) > 0;
    final shouldSwitch =
        _tabSwitcherDragDelta.abs() > 14 || velocity.abs() > 250;
    if (shouldSwitch) {
      _switchTab(shouldSwitchRight ? _alarmTab : _scheduleTab);
    }
    _tabSwitcherDragDelta = 0;
  }

  Future<void> _deleteScheduledTask(ScheduledTask task) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) {
        final palette = context.omniPalette;
        return AlertDialog(
          backgroundColor: context.isDarkTheme ? palette.surfacePrimary : null,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(18),
            side: context.isDarkTheme
                ? BorderSide(color: palette.borderSubtle)
                : BorderSide.none,
          ),
          title: Text(
            LegacyTextLocalizer.isEnglish ? 'Delete scheduled task' : '删除定时任务',
            style: TextStyle(color: palette.textPrimary),
          ),
          content: Text(
            LegacyTextLocalizer.isEnglish
                ? 'Are you sure you want to delete the scheduled task "${task.title}"?'
                : '确定要删除"${task.title}"的定时任务吗？',
            style: TextStyle(color: palette.textSecondary),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(
                LegacyTextLocalizer.localize('取消'),
                style: TextStyle(color: palette.textSecondary),
              ),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context, true),
              style: TextButton.styleFrom(foregroundColor: AppColors.alertRed),
              child: Text(LegacyTextLocalizer.isEnglish ? 'Delete' : '删除'),
            ),
          ],
        );
      },
    );

    if (confirmed == true) {
      ScheduledTaskSchedulerService.cancelTask(task.id);
      await ScheduledTaskStorageService.deleteScheduledTask(task.id);
      await _reloadScheduledTasksOnly();

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              LegacyTextLocalizer.isEnglish
                  ? 'Scheduled task deleted'
                  : '定时任务已删除',
            ),
            behavior: SnackBarBehavior.floating,
            backgroundColor: context.isDarkTheme
                ? context.omniPalette.surfaceElevated
                : AppColors.text.withValues(alpha: 0.8),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(8),
              side: context.isDarkTheme
                  ? BorderSide(color: context.omniPalette.borderSubtle)
                  : BorderSide.none,
            ),
            margin: const EdgeInsets.all(16),
            duration: const Duration(seconds: 2),
          ),
        );
      }
    }
  }

  Future<void> _deleteExactAlarm(_AgentExactAlarmItem alarm) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) {
        final palette = context.omniPalette;
        return AlertDialog(
          backgroundColor: context.isDarkTheme ? palette.surfacePrimary : null,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(18),
            side: context.isDarkTheme
                ? BorderSide(color: palette.borderSubtle)
                : BorderSide.none,
          ),
          title: Text(
            LegacyTextLocalizer.isEnglish ? 'Delete alarm' : '删除闹钟',
            style: TextStyle(color: palette.textPrimary),
          ),
          content: Text(
            LegacyTextLocalizer.isEnglish
                ? 'Are you sure you want to delete "${alarm.title}"?'
                : '确定要删除"${alarm.title}"吗？',
            style: TextStyle(color: palette.textSecondary),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(
                LegacyTextLocalizer.localize('取消'),
                style: TextStyle(color: palette.textSecondary),
              ),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context, true),
              style: TextButton.styleFrom(foregroundColor: AppColors.alertRed),
              child: Text(LegacyTextLocalizer.isEnglish ? 'Delete' : '删除'),
            ),
          ],
        );
      },
    );

    if (confirmed != true) return;

    final deleted = await AssistsMessageService.deleteAgentExactAlarm(
      alarm.alarmId,
    );
    if (!mounted) return;

    if (deleted) {
      await _reloadExactAlarmsOnly();
      showToast(
        LegacyTextLocalizer.isEnglish ? 'Alarm deleted' : '闹钟已删除',
        type: ToastType.success,
      );
      return;
    }

    showToast(
      LegacyTextLocalizer.isEnglish
          ? 'Failed to delete alarm, please try again later'
          : '删除闹钟失败，请稍后重试',
      type: ToastType.error,
    );
  }

  void _showSuccessOverlay(ScheduledTask task) {
    final rootContext = context;
    final overlay = Overlay.of(context);
    late OverlayEntry overlayEntry;

    overlayEntry = OverlayEntry(
      builder: (context) => Positioned(
        top: MediaQuery.of(context).padding.top + 16,
        left: 16,
        right: 16,
        child: Material(
          color: Colors.transparent,
          child: TweenAnimationBuilder<double>(
            tween: Tween(begin: 0.0, end: 1.0),
            duration: const Duration(milliseconds: 300),
            curve: Curves.easeOut,
            builder: (context, value, child) {
              return Opacity(
                opacity: value,
                child: Transform.translate(
                  offset: Offset(0, -20 * (1 - value)),
                  child: child,
                ),
              );
            },
            child: Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: rootContext.isDarkTheme
                    ? rootContext.omniPalette.surfacePrimary
                    : Colors.white,
                borderRadius: BorderRadius.circular(16),
                border: rootContext.isDarkTheme
                    ? Border.all(color: rootContext.omniPalette.borderSubtle)
                    : null,
                boxShadow: rootContext.isDarkTheme
                    ? null
                    : [
                        BoxShadow(
                          color: Colors.black.withValues(alpha: 0.1),
                          blurRadius: 10,
                          offset: const Offset(0, 4),
                        ),
                      ],
              ),
              child: Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: rootContext.isDarkTheme
                          ? Color.lerp(
                              rootContext.omniPalette.surfaceSecondary,
                              rootContext.omniPalette.accentPrimary,
                              0.14,
                            )!
                          : AppColors.primaryBlue.withValues(alpha: 0.1),
                      shape: BoxShape.circle,
                    ),
                    child: Icon(
                      Icons.alarm_on,
                      color: rootContext.isDarkTheme
                          ? rootContext.omniPalette.accentPrimary
                          : AppColors.primaryBlue,
                      size: 20,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          LegacyTextLocalizer.isEnglish
                              ? 'Scheduled task updated'
                              : '定时任务已更新',
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            color: rootContext.isDarkTheme
                                ? rootContext.omniPalette.textPrimary
                                : AppColors.text,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          LegacyTextLocalizer.isEnglish
                              ? 'Will execute at ${task.getDisplayTimeText()}'
                              : '将在 ${task.getDisplayTimeText()} 执行',
                          style: TextStyle(
                            fontSize: 12,
                            color: rootContext.isDarkTheme
                                ? rootContext.omniPalette.textSecondary
                                : AppColors.text70,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );

    overlay.insert(overlayEntry);

    Future.delayed(const Duration(seconds: 3), () {
      if (overlayEntry.mounted) {
        overlayEntry.remove();
      }
    });
  }

  Future<void> _editScheduledTask(ScheduledTask task) async {
    final result = await ScheduleTaskSheet.show(
      context: context,
      existingTask: task,
    );

    if (result != null) {
      await ScheduledTaskStorageService.updateScheduledTask(result);
      ScheduledTaskSchedulerService.scheduleTask(result);
      await _reloadScheduledTasksOnly();

      if (mounted) {
        _showSuccessOverlay(result);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: LegacyTextLocalizer.isEnglish ? 'Scheduled' : '定时',
        primary: true,
      ),
      body: Column(
        children: [
          const SizedBox(height: 8),
          _buildTabSwitcher(),
          const SizedBox(height: 12),
          Expanded(
            child: _isLoading
                ? const Center(child: CircularProgressIndicator())
                : PageView(
                    controller: _pageController,
                    onPageChanged: (index) {
                      if (!mounted) return;
                      setState(() {
                        _currentTab = index;
                      });
                    },
                    children: [_buildSchedulePage(), _buildAlarmPage()],
                  ),
          ),
        ],
      ),
    );
  }

  Widget _buildTabSwitcher() {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Builder(
        builder: (sliderContext) => GestureDetector(
          behavior: HitTestBehavior.opaque,
          onHorizontalDragUpdate: (details) {
            _tabSwitcherDragDelta += details.delta.dx;
          },
          onHorizontalDragEnd: (details) {
            _handleTabSwitcherDragEnd(velocity: details.primaryVelocity ?? 0);
          },
          onTapUp: (details) {
            final box = sliderContext.findRenderObject() as RenderBox?;
            if (box == null || !box.hasSize) return;
            final local = box.globalToLocal(details.globalPosition);
            _switchTab(
              local.dx >= box.size.width / 2 ? _alarmTab : _scheduleTab,
            );
          },
          child: Container(
            height: 40,
            padding: const EdgeInsets.all(3),
            decoration: BoxDecoration(
              color: context.isDarkTheme ? palette.segmentTrack : Colors.white,
              borderRadius: BorderRadius.circular(999),
              border: Border.all(
                color: context.isDarkTheme
                    ? palette.borderSubtle
                    : AppColors.text10,
              ),
            ),
            child: Stack(
              children: [
                AnimatedAlign(
                  duration: const Duration(milliseconds: 280),
                  curve: Curves.easeOutCubic,
                  alignment: _currentTab == _scheduleTab
                      ? Alignment.centerLeft
                      : Alignment.centerRight,
                  child: FractionallySizedBox(
                    widthFactor: 0.5,
                    child: Container(
                      margin: const EdgeInsets.symmetric(horizontal: 1),
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(999),
                        gradient: context.isDarkTheme
                            ? LinearGradient(
                                begin: Alignment.topLeft,
                                end: Alignment.bottomRight,
                                colors: [
                                  Color.lerp(
                                    palette.surfaceElevated,
                                    palette.accentPrimary,
                                    0.18,
                                  )!,
                                  Color.lerp(
                                    palette.surfaceSecondary,
                                    palette.accentPrimary,
                                    0.30,
                                  )!,
                                ],
                              )
                            : const LinearGradient(
                                begin: Alignment.topLeft,
                                end: Alignment.bottomRight,
                                colors: [Color(0xFF2DA5F0), Color(0xFF1930D9)],
                              ),
                        boxShadow: context.isDarkTheme
                            ? null
                            : const [
                                BoxShadow(
                                  color: Color(0x1F1930D9),
                                  blurRadius: 10,
                                  offset: Offset(0, 4),
                                ),
                              ],
                        border: context.isDarkTheme
                            ? Border.all(color: palette.borderSubtle)
                            : null,
                      ),
                    ),
                  ),
                ),
                Row(
                  children: [
                    _buildTabButton(
                      label: LegacyTextLocalizer.isEnglish
                          ? 'Scheduled Tasks'
                          : '定时任务',
                      tabIndex: _scheduleTab,
                    ),
                    _buildTabButton(
                      label: LegacyTextLocalizer.isEnglish ? 'Alarms' : '闹钟列表',
                      tabIndex: _alarmTab,
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildTabButton({required String label, required int tabIndex}) {
    final selected = _currentTab == tabIndex;
    final palette = context.omniPalette;

    return Expanded(
      child: Center(
        child: AnimatedScale(
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
          scale: selected ? 1 : 0.97,
          child: AnimatedDefaultTextStyle(
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            style: TextStyle(
              color: selected
                  ? (context.isDarkTheme ? palette.textPrimary : Colors.white)
                  : (context.isDarkTheme
                        ? palette.textSecondary
                        : AppColors.text70),
              fontSize: 14,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
            ),
            child: Text(label),
          ),
        ),
      ),
    );
  }

  Widget _buildSchedulePage() {
    if (_scheduledTasks.isEmpty) {
      return _buildScheduledEmptyState();
    }
    return _buildTaskList();
  }

  Widget _buildAlarmPage() {
    if (_exactAlarms.isEmpty) {
      return _buildAlarmEmptyState();
    }

    return ListView.builder(
      padding: edgeToEdgeScrollPadding(context, const EdgeInsets.all(16)),
      itemCount: _exactAlarms.length,
      itemBuilder: (context, index) {
        final alarm = _exactAlarms[index];
        return Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: _buildAlarmItem(alarm),
        );
      },
    );
  }

  Widget _buildScheduledEmptyState() {
    final palette = context.omniPalette;
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          SvgPicture.asset(
            'assets/common/schedule_icon.svg',
            width: 64,
            height: 64,
            colorFilter: ColorFilter.mode(
              context.isDarkTheme ? palette.borderStrong : AppColors.text50,
              BlendMode.srcIn,
            ),
          ),
          const SizedBox(height: 16),
          Text(
            LegacyTextLocalizer.isEnglish ? 'No scheduled tasks' : '暂无定时任务',
            style: TextStyle(
              fontSize: 16,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : AppColors.text70,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            LegacyTextLocalizer.isEnglish
                ? 'Tap the alarm icon in task records to add'
                : '在任务记录中点击闹钟图标添加',
            style: TextStyle(
              fontSize: 14,
              color: context.isDarkTheme
                  ? palette.textTertiary
                  : AppColors.text50,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAlarmEmptyState() {
    final palette = context.omniPalette;
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            Icons.alarm_outlined,
            size: 64,
            color: context.isDarkTheme
                ? palette.borderStrong
                : AppColors.text50,
          ),
          const SizedBox(height: 16),
          Text(
            LegacyTextLocalizer.isEnglish ? 'No in-app alarms' : '暂无应用内闹钟',
            style: TextStyle(
              fontSize: 16,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : AppColors.text70,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            LegacyTextLocalizer.isEnglish
                ? 'Alarms created via Agent will appear here'
                : '通过统一 Agent 创建 exact_alarm 后会显示在这里',
            style: TextStyle(
              fontSize: 14,
              color: context.isDarkTheme
                  ? palette.textTertiary
                  : AppColors.text50,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTaskList() {
    return ListView.builder(
      padding: edgeToEdgeScrollPadding(context, const EdgeInsets.all(16)),
      itemCount: _scheduledTasks.length,
      itemBuilder: (context, index) {
        final task = _scheduledTasks[index];
        return Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: _buildTaskItem(task),
        );
      },
    );
  }

  Widget _buildAlarmItem(_AgentExactAlarmItem alarm) {
    final palette = context.omniPalette;
    final triggerAt = DateTime.fromMillisecondsSinceEpoch(
      alarm.triggerAtMillis,
    );
    final timeText = _formatDateTime(triggerAt);

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: context.isDarkTheme ? palette.surfacePrimary : Colors.white,
        borderRadius: BorderRadius.circular(8),
        border: context.isDarkTheme
            ? Border.all(color: palette.borderSubtle)
            : null,
        boxShadow: context.isDarkTheme ? null : [AppColors.boxShadow],
      ),
      child: Row(
        children: [
          Container(
            width: 28,
            height: 28,
            decoration: BoxDecoration(
              color: context.isDarkTheme
                  ? Color.lerp(
                      palette.surfaceSecondary,
                      palette.accentPrimary,
                      0.14,
                    )!
                  : AppColors.primaryBlue.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(8),
            ),
            alignment: Alignment.center,
            child: Icon(
              Icons.alarm,
              size: 16,
              color: context.isDarkTheme
                  ? palette.accentPrimary
                  : AppColors.primaryBlue,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  alarm.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                    color: context.isDarkTheme
                        ? palette.textPrimary
                        : AppColors.text,
                  ),
                ),
                if (alarm.message.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text(
                    alarm.message,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12,
                      color: context.isDarkTheme
                          ? palette.textSecondary
                          : AppColors.text70,
                      height: 1.35,
                    ),
                  ),
                ],
                const SizedBox(height: 6),
                Text(
                  '$timeText  ·  ${alarm.timezone}',
                  style: TextStyle(
                    fontSize: 11,
                    color: context.isDarkTheme
                        ? palette.textTertiary
                        : AppColors.text50,
                  ),
                ),
              ],
            ),
          ),
          GestureDetector(
            onTap: () => _deleteExactAlarm(alarm),
            behavior: HitTestBehavior.opaque,
            child: Container(
              width: 32,
              height: 32,
              alignment: Alignment.center,
              child: Icon(
                Icons.close,
                size: 16,
                color: context.isDarkTheme
                    ? palette.textSecondary
                    : AppColors.text70,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTaskItem(ScheduledTask task) {
    final palette = context.omniPalette;
    final isExpired = _isTaskExpired(task);
    final titleColor = context.isDarkTheme
        ? (isExpired ? palette.textTertiary : palette.textPrimary)
        : (isExpired ? AppColors.text50 : AppColors.text);
    final secondaryTextColor = context.isDarkTheme
        ? (isExpired ? palette.textTertiary : palette.textSecondary)
        : (isExpired ? AppColors.text50 : AppColors.text70);
    final accentColor = context.isDarkTheme
        ? (isExpired ? palette.textTertiary : palette.accentPrimary)
        : (isExpired ? AppColors.text50 : AppColors.primaryBlue);

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? (isExpired ? palette.surfaceSecondary : palette.surfacePrimary)
            : (isExpired ? const Color(0xFFF8FAFC) : Colors.white),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: context.isDarkTheme
              ? palette.borderSubtle
              : (isExpired ? AppColors.text10 : Colors.transparent),
        ),
        boxShadow: context.isDarkTheme || isExpired
            ? const []
            : [AppColors.boxShadow],
      ),
      child: Stack(
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(children: [_buildTaskIcon(isExpired: isExpired)]),
                    const SizedBox(height: 12),
                    Text(
                      task.title,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                        color: titleColor,
                        height: 1.57,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 6,
                            vertical: 2,
                          ),
                          decoration: BoxDecoration(
                            color:
                                (context.isDarkTheme
                                        ? palette.surfaceElevated
                                        : Colors.teal)
                                    .withValues(alpha: 0.12),
                            borderRadius: BorderRadius.circular(4),
                            border: context.isDarkTheme
                                ? Border.all(color: palette.borderSubtle)
                                : null,
                          ),
                          child: Text(
                            'SubAgent',
                            style: TextStyle(
                              fontSize: 10,
                              color: context.isDarkTheme
                                  ? palette.textSecondary
                                  : Colors.teal,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                        if (!task.notificationEnabled) ...[
                          const SizedBox(width: 6),
                          Text(
                            Localizations.localeOf(context).languageCode == 'en'
                                ? 'Notifications off'
                                : '通知关闭',
                            style: TextStyle(
                              fontSize: 10,
                              color: secondaryTextColor,
                            ),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Icon(
                          task.type == ScheduledTaskType.fixedTime
                              ? Icons.access_time
                              : Icons.timer,
                          size: 14,
                          color: accentColor,
                        ),
                        const SizedBox(width: 4),
                        GestureDetector(
                          onTap: () => _editScheduledTask(task),
                          child: Container(
                            padding: const EdgeInsets.only(bottom: 1),
                            decoration: BoxDecoration(
                              border: Border(
                                bottom: BorderSide(
                                  color: accentColor,
                                  width: 1.0,
                                ),
                              ),
                            ),
                            child: Text(
                              task.getDisplayTimeText(),
                              style: TextStyle(
                                fontSize: 12,
                                color: accentColor,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                          ),
                        ),
                        if (task.repeatDaily) ...[
                          const SizedBox(width: 8),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color:
                                  (isExpired
                                          ? (context.isDarkTheme
                                                ? palette.surfaceElevated
                                                : AppColors.text50)
                                          : (context.isDarkTheme
                                                ? Color.lerp(
                                                    palette.surfaceSecondary,
                                                    palette.accentPrimary,
                                                    0.14,
                                                  )!
                                                : AppColors.primaryBlue))
                                      .withValues(alpha: 0.1),
                              borderRadius: BorderRadius.circular(4),
                              border: context.isDarkTheme
                                  ? Border.all(color: palette.borderSubtle)
                                  : null,
                            ),
                            child: Text(
                              LegacyTextLocalizer.localize('每日'),
                              style: TextStyle(
                                fontSize: 10,
                                color: accentColor,
                              ),
                            ),
                          ),
                        ],
                        const Spacer(),
                        Text(
                          task.getNextExecutionTimeText(),
                          style: TextStyle(
                            fontSize: 10,
                            color: secondaryTextColor,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
          Positioned(
            top: 0,
            right: 0,
            child: GestureDetector(
              onTap: () => _deleteScheduledTask(task),
              behavior: HitTestBehavior.opaque,
              child: Container(
                width: 32,
                height: 32,
                alignment: Alignment.center,
                child: Icon(Icons.close, size: 16, color: secondaryTextColor),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTaskIcon({required bool isExpired}) {
    final palette = context.omniPalette;
    return Container(
      width: 20,
      height: 20,
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? (isExpired ? palette.surfaceElevated : palette.surfaceSecondary)
            : (isExpired ? Colors.grey[200] : Colors.grey[300]),
        borderRadius: BorderRadius.circular(4),
        border: context.isDarkTheme
            ? Border.all(color: palette.borderSubtle)
            : null,
      ),
      child: Icon(
        Icons.smart_toy_outlined,
        size: 14,
        color: context.isDarkTheme
            ? (isExpired ? palette.textTertiary : palette.textSecondary)
            : (isExpired ? Colors.grey[400] : Colors.grey),
      ),
    );
  }

  bool _isTaskExpired(ScheduledTask task) {
    final nextExecutionTime = task.nextExecutionTime;
    if (nextExecutionTime == null) {
      return false;
    }
    return DateTime.fromMillisecondsSinceEpoch(
      nextExecutionTime,
    ).isBefore(DateTime.now());
  }

  String _formatDateTime(DateTime dateTime) {
    final month = dateTime.month.toString().padLeft(2, '0');
    final day = dateTime.day.toString().padLeft(2, '0');
    final hour = dateTime.hour.toString().padLeft(2, '0');
    final minute = dateTime.minute.toString().padLeft(2, '0');
    return '$month-$day $hour:$minute';
  }
}

class _AgentExactAlarmItem {
  const _AgentExactAlarmItem({
    required this.alarmId,
    required this.title,
    required this.message,
    required this.triggerAtMillis,
    required this.timezone,
  });

  final String alarmId;
  final String title;
  final String message;
  final int triggerAtMillis;
  final String timezone;

  factory _AgentExactAlarmItem.fromMap(Map<String, dynamic> map) {
    final rawMillis = map['triggerAtMillis'];
    return _AgentExactAlarmItem(
      alarmId: (map['alarmId'] ?? '').toString(),
      title: (map['title'] ?? '').toString(),
      message: (map['message'] ?? '').toString(),
      triggerAtMillis: rawMillis is int
          ? rawMillis
          : int.tryParse(rawMillis?.toString() ?? '') ?? 0,
      timezone: (map['timezone'] ?? '').toString(),
    );
  }
}
