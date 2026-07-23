import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/scheduled_task.dart';
import 'package:ui/services/scheduled_task_scheduler_service.dart';
import 'package:ui/services/scheduled_task_storage_service.dart';

class AgentScheduleBridgeService {
  static Future<Map<String, dynamic>> createTask(
    Map<String, dynamic> raw,
  ) async {
    final targetKind = (raw['targetKind'] ?? 'subagent').toString();
    if (targetKind != 'subagent') {
      throw ArgumentError('targetKind 仅支持 subagent');
    }
    final subagentPrompt = _requireSubagentPrompt(raw['subagentPrompt']);

    final type = _parseType((raw['scheduleType'] ?? '').toString());
    final enabled = raw['enabled'] != false;
    final task = ScheduledTask(
      id: (raw['taskId'] ?? DateTime.now().microsecondsSinceEpoch.toString())
          .toString(),
      title: (raw['title'] ?? '').toString(),
      targetKind: targetKind,
      subagentConversationId: raw['subagentConversationId']?.toString(),
      parentConversationId:
          (raw['parentConversationId'] ?? raw['subagentParentConversationId'])
              ?.toString(),
      parentConversationMode:
          (raw['parentConversationMode'] ??
                  raw['subagentParentConversationMode'])
              ?.toString(),
      subagentPrompt: subagentPrompt,
      subagentModelId: raw['subagentModelId']?.toString(),
      notificationEnabled: raw['notificationEnabled'] != false,
      type: type,
      fixedTime: type == ScheduledTaskType.fixedTime
          ? raw['fixedTime']?.toString()
          : null,
      countdownMinutes: type == ScheduledTaskType.countdown
          ? _toInt(raw['countdownMinutes'])
          : null,
      repeatDaily: raw['repeatDaily'] == true,
      isEnabled: enabled,
      createdAt: DateTime.now().millisecondsSinceEpoch,
      nextExecutionTime: null,
    );

    final normalizedTask = task.copyWith(
      nextExecutionTime: task.calculateNextExecutionTime(),
    );
    final saved = await ScheduledTaskStorageService.addScheduledTask(
      normalizedTask,
    );
    if (!saved) {
      throw StateError(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to save scheduled task'
            : '定时任务保存失败',
      );
    }
    if (normalizedTask.isEnabled) {
      ScheduledTaskSchedulerService.scheduleTask(normalizedTask);
    } else {
      ScheduledTaskSchedulerService.cancelTask(normalizedTask.id);
    }
    return {
      'success': true,
      'taskId': normalizedTask.id,
      'summary': LegacyTextLocalizer.isEnglish
          ? 'Created scheduled task “${normalizedTask.title}”'
          : '已创建定时任务”${normalizedTask.title}”',
      'task': _toSummaryMap(normalizedTask),
    };
  }

  static Future<List<Map<String, dynamic>>> listTasks() async {
    final tasks = await ScheduledTaskStorageService.loadScheduledTasks();
    tasks.sort((a, b) {
      final aTime = a.nextExecutionTime ?? 0;
      final bTime = b.nextExecutionTime ?? 0;
      return aTime.compareTo(bTime);
    });
    return tasks.map(_toSummaryMap).toList();
  }

  static Future<Map<String, dynamic>> updateTask(
    Map<String, dynamic> raw,
  ) async {
    final taskId = (raw['taskId'] ?? '').toString();
    final existing = await ScheduledTaskStorageService.getScheduledTaskById(
      taskId,
    );
    if (existing == null) {
      throw StateError(
        LegacyTextLocalizer.isEnglish
            ? 'Scheduled task not found'
            : '未找到对应的定时任务',
      );
    }
    final subagentPrompt = raw.containsKey('subagentPrompt')
        ? _requireSubagentPrompt(raw['subagentPrompt'])
        : _requireSubagentPrompt(existing.subagentPrompt);

    var nextType = existing.type;
    if (raw.containsKey('fixedTime')) {
      nextType = ScheduledTaskType.fixedTime;
    } else if (raw.containsKey('countdownMinutes')) {
      nextType = ScheduledTaskType.countdown;
    }

    final baseUpdated = existing.copyWith(
      title: raw['title']?.toString(),
      targetKind: raw['targetKind']?.toString(),
      subagentConversationId: raw.containsKey('subagentConversationId')
          ? raw['subagentConversationId']?.toString()
          : existing.subagentConversationId,
      parentConversationId: raw.containsKey('parentConversationId')
          ? raw['parentConversationId']?.toString()
          : existing.parentConversationId,
      parentConversationMode: raw.containsKey('parentConversationMode')
          ? raw['parentConversationMode']?.toString()
          : existing.parentConversationMode,
      subagentPrompt: subagentPrompt,
      subagentModelId: raw.containsKey('subagentModelId')
          ? raw['subagentModelId']?.toString()
          : existing.subagentModelId,
      notificationEnabled: raw.containsKey('notificationEnabled')
          ? raw['notificationEnabled'] == true
          : existing.notificationEnabled,
      type: nextType,
      fixedTime: nextType == ScheduledTaskType.fixedTime
          ? (raw.containsKey('fixedTime')
                ? raw['fixedTime']?.toString()
                : existing.fixedTime)
          : null,
      countdownMinutes: nextType == ScheduledTaskType.countdown
          ? (raw.containsKey('countdownMinutes')
                ? _toInt(raw['countdownMinutes'])
                : existing.countdownMinutes)
          : null,
      repeatDaily: raw.containsKey('repeatDaily')
          ? raw['repeatDaily'] == true
          : existing.repeatDaily,
      isEnabled: raw.containsKey('enabled')
          ? raw['enabled'] == true
          : existing.isEnabled,
      nextExecutionTime: null,
    );
    final updated = baseUpdated.copyWith(
      nextExecutionTime: baseUpdated.calculateNextExecutionTime(),
    );

    final saved = await ScheduledTaskStorageService.updateScheduledTask(
      updated,
    );
    if (!saved) {
      throw StateError(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to update scheduled task'
            : '定时任务更新失败',
      );
    }
    if (updated.isEnabled) {
      ScheduledTaskSchedulerService.scheduleTask(updated);
    } else {
      ScheduledTaskSchedulerService.cancelTask(updated.id);
    }
    return {
      'success': true,
      'taskId': updated.id,
      'summary': LegacyTextLocalizer.isEnglish
          ? 'Updated scheduled task “${updated.title}”'
          : '已更新定时任务”${updated.title}”',
      'task': _toSummaryMap(updated),
    };
  }

  static Future<Map<String, dynamic>> deleteTask(
    Map<String, dynamic> raw,
  ) async {
    final taskId = (raw['taskId'] ?? '').toString();
    final existing = await ScheduledTaskStorageService.getScheduledTaskById(
      taskId,
    );
    if (existing == null) {
      throw StateError(
        LegacyTextLocalizer.isEnglish
            ? 'Scheduled task not found'
            : '未找到对应的定时任务',
      );
    }

    ScheduledTaskSchedulerService.cancelTask(taskId);
    final deleted = await ScheduledTaskStorageService.deleteScheduledTask(
      taskId,
    );
    if (!deleted) {
      throw StateError(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to delete scheduled task'
            : '定时任务删除失败',
      );
    }
    return {
      'success': true,
      'taskId': taskId,
      'summary': LegacyTextLocalizer.isEnglish
          ? 'Deleted scheduled task “${existing.title}”'
          : '已删除定时任务”${existing.title}”',
      'task': _toSummaryMap(existing),
    };
  }

  static ScheduledTaskType _parseType(String raw) {
    switch (raw) {
      case 'countdown':
        return ScheduledTaskType.countdown;
      case 'fixed_time':
      default:
        return ScheduledTaskType.fixedTime;
    }
  }

  static int? _toInt(dynamic value) {
    if (value is int) return value;
    if (value is String) return int.tryParse(value);
    return null;
  }

  static String _requireSubagentPrompt(dynamic value) {
    final prompt = value?.toString().trim() ?? '';
    if (prompt.isEmpty) {
      throw ArgumentError('SubAgent 定时任务缺少 subagentPrompt');
    }
    return prompt;
  }

  static Map<String, dynamic> _toSummaryMap(ScheduledTask task) {
    return {
      'taskId': task.id,
      'title': task.title,
      'scheduleType': task.type == ScheduledTaskType.fixedTime
          ? 'fixed_time'
          : 'countdown',
      'fixedTime': task.fixedTime,
      'countdownMinutes': task.countdownMinutes,
      'repeatDaily': task.repeatDaily,
      'enabled': task.isEnabled,
      'nextExecutionTime': task.nextExecutionTime,
      'displayTimeText': task.getDisplayTimeText(),
      'targetKind': task.targetKind,
      'subagentConversationId': task.subagentConversationId,
      'parentConversationId': task.parentConversationId,
      'parentConversationMode': task.parentConversationMode,
      'subagentPrompt': task.subagentPrompt,
      'notificationEnabled': task.notificationEnabled,
    };
  }
}
