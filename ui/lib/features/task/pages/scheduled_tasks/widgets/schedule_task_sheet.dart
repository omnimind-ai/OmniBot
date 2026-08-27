import 'package:flutter/material.dart';
import 'package:flutter/cupertino.dart';
import 'package:ui/models/scheduled_task.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/model_provider_config_service.dart';

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
    return showModalBottomSheet<ScheduledTask>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
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

  String? _selectedModelId;
  List<ProviderModelOption> _availableModels = [];
  bool _isLoadingModels = true;

  @override
  void initState() {
    super.initState();

    final task = widget.existingTask;
    _selectedTabIndex = task.type == ScheduledTaskType.fixedTime ? 0 : 1;
    _repeatDaily = task.repeatDaily;
    _selectedModelId = task.subagentModelId;

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
    _loadModels();
  }

  Future<void> _loadModels() async {
    try {
      final groups = await ModelProviderConfigService.loadChatModelGroups();
      final models = groups.expand((g) => g.models).toList();
      if (mounted) {
        setState(() {
          _availableModels = models;
          // default to first model if current isn't in list and list isn't empty
          if (_selectedModelId == null || !models.any((m) => m.id == _selectedModelId)) {
            _selectedModelId = models.isNotEmpty ? models.first.id : null;
          }
          _isLoadingModels = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isLoadingModels = false;
        });
      }
    }
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // 拖动指示器
            Container(
              margin: const EdgeInsets.only(top: 12),
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: palette.borderStrong,
                borderRadius: BorderRadius.circular(2),
              ),
            ),

            // 标题
            Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      LegacyTextLocalizer.isEnglish
                          ? 'Set scheduled task'
                          : '设置定时任务',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                        color: palette.textPrimary,
                      ),
                    ),
                  ),
                  GestureDetector(
                    onTap: () => Navigator.pop(context),
                    child: Icon(
                      Icons.close,
                      size: 24,
                      color: palette.textSecondary,
                    ),
                  ),
                ],
              ),
            ),

            // 任务标题显示
            Container(
              margin: const EdgeInsets.symmetric(horizontal: 16),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: palette.surfaceSecondary,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                children: [
                  Icon(Icons.task_alt, size: 20, color: palette.accentPrimary),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      widget.existingTask.title,
                      style: TextStyle(
                        fontSize: 14,
                        color: palette.textPrimary,
                      ),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 16),

            // 标签页切换
            _buildTabSelector(),

            const SizedBox(height: 16),
            
            // 模型选择器 (仅针对 SubAgent)
            if (widget.existingTask.targetKind == 'subagent')
              _buildModelSelector(),

            if (widget.existingTask.targetKind == 'subagent')
              const SizedBox(height: 16),

            // 内容区域
            SizedBox(
              height: 200,
              child: PageView(
                controller: _pageController,
                onPageChanged: (index) {
                  setState(() {
                    _selectedTabIndex = index;
                  });
                },
                children: [_buildFixedTimeTab(), _buildCountdownTab()],
              ),
            ),

            // 每日重复开关
            if (_selectedTabIndex == 0)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Row(
                  children: [
                    Text(
                      LegacyTextLocalizer.isEnglish ? 'Repeat daily' : '每日重复执行',
                      style: TextStyle(
                        fontSize: 14,
                        color: palette.textPrimary,
                      ),
                    ),
                    const Spacer(),
                    CupertinoSwitch(
                      value: _repeatDaily,
                      activeTrackColor: palette.accentPrimary,
                      onChanged: (value) {
                        setState(() {
                          _repeatDaily = value;
                        });
                      },
                    ),
                  ],
                ),
              ),

            const SizedBox(height: 24),

            // 确认按钮
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: SizedBox(
                width: double.infinity,
                height: 48,
                child: ElevatedButton(
                  onPressed: _onConfirm,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: palette.accentPrimary,
                    foregroundColor: Theme.of(context).colorScheme.onPrimary,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(24),
                    ),
                    elevation: 0,
                  ),
                  child: Text(
                    LegacyTextLocalizer.localize('确认'),
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
                  ),
                ),
              ),
            ),

            const SizedBox(height: 16),
          ],
        ),
      ),
    );
  }

  /// 构建模型选择器
  Widget _buildModelSelector() {
    if (_isLoadingModels) {
      return const Padding(
        padding: EdgeInsets.symmetric(horizontal: 16),
        child: SizedBox(height: 48, child: Center(child: CupertinoActivityIndicator())),
      );
    }
    if (_availableModels.isEmpty) {
      return const SizedBox.shrink();
    }
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Container(
        height: 48,
        decoration: BoxDecoration(
          color: palette.surfaceSecondary,
          borderRadius: BorderRadius.circular(8),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Row(
          children: [
            Icon(Icons.smart_toy_outlined, size: 20, color: palette.textSecondary),
            const SizedBox(width: 8),
            Text(
              LegacyTextLocalizer.isEnglish ? 'Model' : '模型',
              style: TextStyle(color: palette.textPrimary, fontSize: 14),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: DropdownButtonHideUnderline(
                child: DropdownButton<String>(
                  value: _availableModels.any((m) => m.id == _selectedModelId)
                      ? _selectedModelId
                      : (_availableModels.isNotEmpty ? _availableModels.first.id : null),
                  isExpanded: true,
                  icon: Icon(Icons.arrow_drop_down, color: palette.textSecondary),
                  dropdownColor: palette.surfacePrimary,
                  items: _availableModels.map((model) {
                    return DropdownMenuItem<String>(
                      value: model.id,
                      child: Text(
                        model.displayName,
                        style: TextStyle(color: palette.textPrimary, fontSize: 14),
                        overflow: TextOverflow.ellipsis,
                      ),
                    );
                  }).toList(),
                  onChanged: (String? newValue) {
                    setState(() {
                      _selectedModelId = newValue;
                    });
                  },
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 构建标签页选择器
  Widget _buildTabSelector() {
    final palette = context.omniPalette;
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: palette.segmentTrack,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          Expanded(
            child: _buildTabButton(
              title: LegacyTextLocalizer.isEnglish ? 'Fixed time' : '固定时间',
              isSelected: _selectedTabIndex == 0,
              onTap: () {
                setState(() {
                  _selectedTabIndex = 0;
                });
                _pageController.animateToPage(
                  0,
                  duration: const Duration(milliseconds: 300),
                  curve: Curves.easeInOut,
                );
              },
            ),
          ),
          Expanded(
            child: _buildTabButton(
              title: LegacyTextLocalizer.isEnglish ? 'Countdown' : '倒计时',
              isSelected: _selectedTabIndex == 1,
              onTap: () {
                setState(() {
                  _selectedTabIndex = 1;
                });
                _pageController.animateToPage(
                  1,
                  duration: const Duration(milliseconds: 300),
                  curve: Curves.easeInOut,
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  /// 构建单个标签按钮
  Widget _buildTabButton({
    required String title,
    required bool isSelected,
    required VoidCallback onTap,
  }) {
    final palette = context.omniPalette;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 10),
        decoration: BoxDecoration(
          color: isSelected ? palette.segmentThumb : Colors.transparent,
          borderRadius: BorderRadius.circular(6),
          boxShadow: isSelected
              ? [
                  BoxShadow(
                    color: palette.shadowColor,
                    blurRadius: 4,
                    offset: const Offset(0, 2),
                  ),
                ]
              : null,
        ),
        child: Center(
          child: Text(
            title,
            style: TextStyle(
              fontSize: 14,
              fontWeight: isSelected ? FontWeight.w500 : FontWeight.normal,
              color: isSelected ? palette.accentPrimary : palette.textSecondary,
            ),
          ),
        ),
      ),
    );
  }

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
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          _buildCountdownButton(
            icon: Icons.remove,
            onTap: () {
              if (_countdownMinutes > 5) {
                setState(() {
                  _countdownMinutes -= 5;
                });
              }
            },
          ),
          const SizedBox(width: 24),
          GestureDetector(
            onTap: _showCountdownInputDialog,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
              decoration: BoxDecoration(
                color: palette.surfaceSecondary,
                border: Border.all(color: palette.borderStrong),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    _formatCountdown(_countdownMinutes),
                    style: TextStyle(
                      fontSize: 36,
                      fontWeight: FontWeight.w300,
                      color: palette.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    LegacyTextLocalizer.isEnglish ? 'Execute after' : '后执行',
                    style: TextStyle(
                      fontSize: 14,
                      color: palette.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(width: 24),
          _buildCountdownButton(
            icon: Icons.add,
            onTap: () {
              if (_countdownMinutes < 1440) {
                // 最多24小时
                setState(() {
                  _countdownMinutes += 5;
                });
              }
            },
          ),
        ],
      ),
    );
  }

  /// 显示倒计时输入对话框
  Future<void> _showCountdownInputDialog() async {
    final minutes = await showDialog<int>(
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

  /// 构建加减按钮
  Widget _buildCountdownButton({
    required IconData icon,
    required VoidCallback onTap,
  }) {
    final palette = context.omniPalette;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 48,
        height: 48,
        decoration: BoxDecoration(
          color: palette.accentPrimary.withValues(alpha: 0.12),
          shape: BoxShape.circle,
        ),
        child: Icon(icon, size: 24, color: palette.accentPrimary),
      ),
    );
  }

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
      subagentModelId: _selectedModelId,
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
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        _close();
      },
      child: AlertDialog(
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
