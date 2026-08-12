import 'dart:async';
import 'dart:convert';

import 'package:ui/models/scheduled_task.dart';
import 'package:ui/services/storage_service.dart';

/// 定时任务存储服务
class ScheduledTaskStorageService {
  static const String _scheduledTasksKey = 'scheduled_tasks';
  static final StreamController<List<ScheduledTask>>
  _scheduledTasksChangedController =
      StreamController<List<ScheduledTask>>.broadcast();

  static Stream<List<ScheduledTask>> get scheduledTasksChangedStream =>
      _scheduledTasksChangedController.stream;

  /// 保存所有定时任务
  static Future<bool> saveScheduledTasks(List<ScheduledTask> tasks) async {
    try {
      final jsonList = tasks.map((task) => jsonEncode(task.toJson())).toList();
      final saved = await StorageService.setStringList(
        _scheduledTasksKey,
        jsonList,
      );
      if (saved) {
        _notifyScheduledTasksChanged(tasks);
      }
      return saved;
    } catch (e) {
      return false;
    }
  }

  /// 加载所有定时任务
  static Future<List<ScheduledTask>> loadScheduledTasks() async {
    return loadScheduledTasksSync();
  }

  /// 同步读取已缓存的定时任务，用于页面首帧恢复。
  static List<ScheduledTask> loadScheduledTasksSync() {
    try {
      final jsonList = StorageService.getStringList(_scheduledTasksKey);

      if (jsonList == null || jsonList.isEmpty) {
        return [];
      }

      return jsonList
          .map((jsonStr) {
            final json = jsonDecode(jsonStr) as Map<String, dynamic>;
            return ScheduledTask.fromJson(json);
          })
          .where(
            (task) =>
                task.targetKind == 'subagent' &&
                (task.subagentPrompt?.trim().isNotEmpty ?? false),
          )
          .toList();
    } catch (e) {
      return [];
    }
  }

  /// 添加定时任务
  static Future<bool> addScheduledTask(ScheduledTask task) async {
    try {
      final tasks = await loadScheduledTasks();

      // 检查是否已存在相同的任务
      final existingIndex = tasks.indexWhere((t) => t.id == task.id);
      if (existingIndex != -1) {
        tasks[existingIndex] = task;
      } else {
        tasks.add(task);
      }

      return await saveScheduledTasks(tasks);
    } catch (e) {
      return false;
    }
  }

  /// 更新定时任务
  static Future<bool> updateScheduledTask(ScheduledTask task) async {
    try {
      final tasks = await loadScheduledTasks();
      final index = tasks.indexWhere((t) => t.id == task.id);

      if (index == -1) {
        return false;
      }

      tasks[index] = task;
      return await saveScheduledTasks(tasks);
    } catch (e) {
      return false;
    }
  }

  /// 删除定时任务
  static Future<bool> deleteScheduledTask(String taskId) async {
    try {
      final tasks = await loadScheduledTasks();
      tasks.removeWhere((task) => task.id == taskId);
      return await saveScheduledTasks(tasks);
    } catch (e) {
      return false;
    }
  }

  /// 根据ID获取定时任务
  static Future<ScheduledTask?> getScheduledTaskById(String taskId) async {
    try {
      final tasks = await loadScheduledTasks();
      return tasks.firstWhere(
        (task) => task.id == taskId,
        orElse: () => throw Exception('Task not found'),
      );
    } catch (e) {
      return null;
    }
  }

  /// 获取启用的定时任务
  static Future<List<ScheduledTask>> getEnabledScheduledTasks() async {
    final tasks = await loadScheduledTasks();
    return tasks.where((task) => task.isEnabled).toList();
  }

  /// 清空所有定时任务
  static Future<bool> clearScheduledTasks() async {
    try {
      final cleared = await StorageService.remove(_scheduledTasksKey);
      if (cleared) {
        _notifyScheduledTasksChanged(const <ScheduledTask>[]);
      }
      return cleared;
    } catch (e) {
      return false;
    }
  }

  static void _notifyScheduledTasksChanged(List<ScheduledTask> tasks) {
    _scheduledTasksChangedController.add(
      List<ScheduledTask>.unmodifiable(tasks),
    );
  }
}
