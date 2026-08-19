import 'dart:async';
import 'package:flutter/material.dart';
import 'package:ui/models/scheduled_task.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/scheduled_task_storage_service.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/conversation_service.dart';

/// 定时任务调度服务
/// 负责管理定时任务的执行调度和倒计时提醒
class ScheduledTaskSchedulerService {
  static const bool _useNativeAlarmScheduler = true;
  static final Map<String, Timer> _scheduledTimers = {};
  static final Map<String, Timer> _reminderTimers = {};

  /// 倒计时提醒的回调
  static Function(ScheduledTask task, int countdown)? onCountdownReminder;

  /// 任务即将执行的回调（5秒倒计时）
  static Function(ScheduledTask task)? onTaskAboutToExecute;

  /// 用户取消任务的回调
  static VoidCallback? onTaskCancelled;

  /// 用户选择立即执行的回调
  static VoidCallback? onTaskExecuteNow;

  /// 当前正在倒计时的任务
  static ScheduledTask? _currentCountdownTask;

  /// 是否正在显示倒计时提醒
  static bool _isShowingReminder = false;

  /// 初始化服务，加载所有定时任务并设置定时器
  static Future<void> initialize() async {
    try {
      // 设置原生层回调
      _setupNativeCallbacks();

      final tasks =
          await ScheduledTaskStorageService.getEnabledScheduledTasks();
      if (_useNativeAlarmScheduler) {
        await AssistsMessageService.syncWorkspaceScheduledTasks(
          tasks.map((task) => task.toJson()).toList(),
        );
        print(
          'ScheduledTaskSchedulerService: Native scheduler synced ${tasks.length} tasks',
        );
        return;
      }
      for (final task in tasks) {
        scheduleTask(task);
      }
      print(
        'ScheduledTaskSchedulerService: Initialized with ${tasks.length} tasks',
      );
    } catch (e) {
      print('ScheduledTaskSchedulerService: Initialize error - $e');
    }
  }

  /// 设置原生层回调
  static void _setupNativeCallbacks() {
    // 用户取消定时任务
    AssistsMessageService.setOnScheduledTaskCancelledCallBack((taskId) {
      print(
        'ScheduledTaskSchedulerService: Task cancelled from native - $taskId',
      );
      _handleNativeCancel(taskId);
    });

    // 用户选择立即执行
    AssistsMessageService.setOnScheduledTaskExecuteNowCallBack((taskId) {
      print(
        'ScheduledTaskSchedulerService: Task execute now from native - $taskId',
      );
      _handleNativeExecuteNow(taskId);
    });
  }

  /// 处理原生层取消任务
  static Future<void> _handleNativeCancel(String taskId) async {
    if (_currentCountdownTask?.id == taskId) {
      cancelTask(taskId);

      // 如果不是重复任务，删除
      if (!_currentCountdownTask!.repeatDaily) {
        await ScheduledTaskStorageService.deleteScheduledTask(taskId);
      } else {
        // 重复任务，重新调度到下一次
        final updatedTask = _currentCountdownTask!.copyWith(
          nextExecutionTime: _currentCountdownTask!
              .calculateNextExecutionTime(),
        );
        await ScheduledTaskStorageService.updateScheduledTask(updatedTask);
        scheduleTask(updatedTask);
      }

      _currentCountdownTask = null;
      _isShowingReminder = false;
      onTaskCancelled?.call();
    }
  }

  /// 处理原生层立即执行
  static Future<void> _handleNativeExecuteNow(String taskId) async {
    if (_currentCountdownTask?.id == taskId) {
      final task = _currentCountdownTask!;
      cancelTask(taskId);
      _currentCountdownTask = null;
      _isShowingReminder = false;

      await _executeScheduledTask(task);
      onTaskExecuteNow?.call();
    }
  }

  /// 调度一个定时任务
  static void scheduleTask(ScheduledTask task) {
    if (_useNativeAlarmScheduler) {
      _scheduledTimers[task.id]?.cancel();
      _scheduledTimers.remove(task.id);
      _reminderTimers[task.id]?.cancel();
      _reminderTimers.remove(task.id);
      if (!task.isEnabled) {
        unawaited(AssistsMessageService.deleteWorkspaceScheduledTask(task.id));
        return;
      }
      unawaited(
        AssistsMessageService.upsertWorkspaceScheduledTask(task.toJson()),
      );
      return;
    }

    // 先取消已有的定时器
    cancelTask(task.id);

    if (!task.isEnabled) return;

    final nextExecutionTime =
        task.nextExecutionTime ?? task.calculateNextExecutionTime();
    final now = DateTime.now().millisecondsSinceEpoch;
    final delay = nextExecutionTime - now;

    if (delay <= 0) {
      // 如果时间已过，检查是否需要重复
      if (task.repeatDaily) {
        // 重新计算下一次执行时间
        final updatedTask = task.copyWith(
          nextExecutionTime: task.calculateNextExecutionTime(),
        );
        ScheduledTaskStorageService.updateScheduledTask(updatedTask);
        scheduleTask(updatedTask);
      }
      return;
    }

    // 设置提醒定时器（提前5秒提醒）
    final reminderDelay = delay - 5000;
    if (reminderDelay > 0) {
      _reminderTimers[task.id] = Timer(
        Duration(milliseconds: reminderDelay),
        () => _showCountdownReminder(task),
      );
    } else if (delay > 0) {
      // 如果不足5秒，立即显示提醒
      _showCountdownReminder(task);
    }

    // 设置执行定时器
    _scheduledTimers[task.id] = Timer(
      Duration(milliseconds: delay),
      () => _executeScheduledTask(task),
    );

    print(
      'ScheduledTaskSchedulerService: Scheduled task "${task.title}" for ${DateTime.fromMillisecondsSinceEpoch(nextExecutionTime)}',
    );
  }

  /// 显示倒计时提醒
  static Future<void> _showCountdownReminder(ScheduledTask task) async {
    _currentCountdownTask = task;
    _isShowingReminder = true;

    // 调用原生层显示悬浮提醒
    await AssistsMessageService.showScheduledTaskReminder(
      taskId: task.id,
      taskName: task.title,
      countdownSeconds: 5,
    );

    onTaskAboutToExecute?.call(task);
  }

  /// 执行定时任务
  static Future<void> _executeScheduledTask(ScheduledTask task) async {
    print('ScheduledTaskSchedulerService: Executing task "${task.title}"');

    _isShowingReminder = false;
    _currentCountdownTask = null;

    // 隐藏提醒
    await AssistsMessageService.hideScheduledTaskReminder();

    try {
      final prompt = task.subagentPrompt?.trim() ?? '';
      if (prompt.isEmpty) {
        throw Exception('SubAgent task missing prompt');
      }
      final parentConversationId = int.tryParse(
        task.parentConversationId ?? task.subagentConversationId ?? '',
      );
      final rawParentConversationMode =
          task.parentConversationMode?.trim() ?? '';
      final parentConversationMode = rawParentConversationMode.isEmpty
          ? null
          : ConversationMode.fromStorageValue(rawParentConversationMode);
      final conversationId = await ConversationService.createConversation(
        title: task.title,
        mode: ConversationMode.subagent,
        parentConversationId: parentConversationId,
        parentConversationMode: parentConversationId == null
            ? null
            : parentConversationMode,
        scheduledTaskId: task.id,
      );
      final normalizedConversationId = conversationId;
      if (normalizedConversationId == null) {
        throw Exception('SubAgent conversation create failed');
      }
      final session = await AgentRuntimeService.newSession(
        conversationId: normalizedConversationId,
        conversationMode: ConversationMode.subagent.storageValue,
      );
      final sessionId = (session['sessionId'] ?? session['threadId'])
          ?.toString()
          .trim();
      if (sessionId == null || sessionId.isEmpty) {
        throw Exception('ACP scheduled session creation failed');
      }
      await AgentRuntimeService.promptSession(
        sessionId: sessionId,
        conversationId: normalizedConversationId,
        requestId:
            'scheduled-${task.id}-${DateTime.now().microsecondsSinceEpoch}',
        text: prompt,
        conversationMode: ConversationMode.subagent.storageValue,
      );

      // 如果是重复任务，重新调度
      if (task.repeatDaily) {
        final updatedTask = task.copyWith(
          nextExecutionTime: task.calculateNextExecutionTime(),
        );
        await ScheduledTaskStorageService.updateScheduledTask(updatedTask);
        scheduleTask(updatedTask);
      } else {
        // 非重复任务，删除
        await ScheduledTaskStorageService.deleteScheduledTask(task.id);
      }
    } catch (e) {
      print('ScheduledTaskSchedulerService: Execute task error - $e');
    }
  }

  /// 取消定时任务
  static void cancelTask(String taskId) {
    if (_useNativeAlarmScheduler) {
      unawaited(AssistsMessageService.deleteWorkspaceScheduledTask(taskId));
    }
    _scheduledTimers[taskId]?.cancel();
    _scheduledTimers.remove(taskId);

    _reminderTimers[taskId]?.cancel();
    _reminderTimers.remove(taskId);

    print('ScheduledTaskSchedulerService: Cancelled task $taskId');
  }

  /// 取消所有定时任务
  static void cancelAllTasks() {
    if (_useNativeAlarmScheduler) {
      unawaited(AssistsMessageService.syncWorkspaceScheduledTasks(const []));
    }
    for (final timer in _scheduledTimers.values) {
      timer.cancel();
    }
    _scheduledTimers.clear();

    for (final timer in _reminderTimers.values) {
      timer.cancel();
    }
    _reminderTimers.clear();
  }

  /// 用户取消当前倒计时任务
  static Future<void> cancelCurrentCountdownTask() async {
    if (_currentCountdownTask != null) {
      cancelTask(_currentCountdownTask!.id);

      // 隐藏提醒
      await AssistsMessageService.hideScheduledTaskReminder();

      // 如果不是重复任务，删除
      if (!_currentCountdownTask!.repeatDaily) {
        await ScheduledTaskStorageService.deleteScheduledTask(
          _currentCountdownTask!.id,
        );
      } else {
        // 重复任务，重新调度到下一次
        final updatedTask = _currentCountdownTask!.copyWith(
          nextExecutionTime: _currentCountdownTask!
              .calculateNextExecutionTime(),
        );
        await ScheduledTaskStorageService.updateScheduledTask(updatedTask);
        scheduleTask(updatedTask);
      }

      _currentCountdownTask = null;
      _isShowingReminder = false;
      onTaskCancelled?.call();
    }
  }

  /// 用户选择立即执行当前倒计时任务
  static Future<void> executeCurrentCountdownTaskNow() async {
    if (_currentCountdownTask != null) {
      final task = _currentCountdownTask!;
      cancelTask(task.id);
      _currentCountdownTask = null;
      _isShowingReminder = false;

      await _executeScheduledTask(task);
      onTaskExecuteNow?.call();
    }
  }

  /// 获取当前正在倒计时的任务
  static ScheduledTask? get currentCountdownTask => _currentCountdownTask;

  /// 是否正在显示提醒
  static bool get isShowingReminder => _isShowingReminder;
}
