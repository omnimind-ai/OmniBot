import 'package:ui/l10n/legacy_text_localizer.dart';

/// 定时任务类型
enum ScheduledTaskType {
  /// 固定时间（如每天8:00）
  fixedTime,

  /// 倒计时（如30分钟后）
  countdown,
}

/// 定时任务模型
class ScheduledTask {
  /// 唯一标识符
  final String id;

  /// 任务标题
  final String title;

  /// 目标类型：subagent
  final String targetKind;

  /// 旧版 subagent 固定线程 conversationId，升级后仅作兼容回填
  final String? subagentConversationId;

  /// subagent 定时任务产生所在的主会话
  final String? parentConversationId;

  /// 主会话模式
  final String? parentConversationMode;

  /// subagent 任务提示词
  final String? subagentPrompt;

  /// 执行完成是否通知
  final bool notificationEnabled;

  /// 定时任务类型
  final ScheduledTaskType type;

  /// 固定时间（仅当type为fixedTime时有效）
  /// 格式: "HH:mm"
  final String? fixedTime;

  /// 倒计时分钟数（仅当type为countdown时有效）
  final int? countdownMinutes;

  /// 是否每日重复执行
  final bool repeatDaily;

  /// 是否启用
  final bool isEnabled;

  /// 创建时间
  final int createdAt;

  /// 下次执行时间（毫秒时间戳）
  final int? nextExecutionTime;

  /// 独立配置的模型ID
  final String? subagentModelId;

  ScheduledTask({
    required this.id,
    required this.title,
    this.targetKind = 'subagent',
    this.subagentConversationId,
    this.parentConversationId,
    this.parentConversationMode,
    this.subagentPrompt,
    this.subagentModelId,
    this.notificationEnabled = true,
    required this.type,
    this.fixedTime,
    this.countdownMinutes,
    this.repeatDaily = false,
    this.isEnabled = true,
    required this.createdAt,
    this.nextExecutionTime,
  });

  /// 从JSON创建
  factory ScheduledTask.fromJson(Map<String, dynamic> json) {
    final targetKindFromJson = json['targetKind'] as String? ?? '';

    return ScheduledTask(
      id: json['id'] as String,
      title: json['title'] as String,
      targetKind: targetKindFromJson,
      subagentConversationId: json['subagentConversationId'] as String?,
      parentConversationId:
          (json['parentConversationId'] ?? json['parentConversationID'])
              ?.toString(),
      parentConversationMode: json['parentConversationMode'] as String?,
      subagentPrompt: json['subagentPrompt'] as String?,
      subagentModelId: json['subagentModelId'] as String?,
      notificationEnabled: json['notificationEnabled'] as bool? ?? true,
      type: ScheduledTaskType.values.firstWhere(
        (e) => e.name == json['type'],
        orElse: () => ScheduledTaskType.fixedTime,
      ),
      fixedTime: json['fixedTime'] as String?,
      countdownMinutes: json['countdownMinutes'] as int?,
      repeatDaily: json['repeatDaily'] as bool? ?? false,
      isEnabled: json['isEnabled'] as bool? ?? true,
      createdAt: json['createdAt'] as int,
      nextExecutionTime: json['nextExecutionTime'] as int?,
    );
  }

  /// 转换为JSON
  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'targetKind': targetKind,
      'subagentConversationId': subagentConversationId,
      'parentConversationId': parentConversationId,
      'parentConversationMode': parentConversationMode,
      'subagentPrompt': subagentPrompt,
      'subagentModelId': subagentModelId,
      'notificationEnabled': notificationEnabled,
      'type': type.name,
      'fixedTime': fixedTime,
      'countdownMinutes': countdownMinutes,
      'repeatDaily': repeatDaily,
      'isEnabled': isEnabled,
      'createdAt': createdAt,
      'nextExecutionTime': nextExecutionTime,
    };
  }

  /// 复制并修改
  ScheduledTask copyWith({
    String? id,
    String? title,
    String? targetKind,
    String? subagentConversationId,
    String? parentConversationId,
    String? parentConversationMode,
    String? subagentPrompt,
    String? subagentModelId,
    bool? notificationEnabled,
    ScheduledTaskType? type,
    String? fixedTime,
    int? countdownMinutes,
    bool? repeatDaily,
    bool? isEnabled,
    int? createdAt,
    int? nextExecutionTime,
  }) {
    return ScheduledTask(
      id: id ?? this.id,
      title: title ?? this.title,
      targetKind: targetKind ?? this.targetKind,
      subagentConversationId:
          subagentConversationId ?? this.subagentConversationId,
      parentConversationId: parentConversationId ?? this.parentConversationId,
      parentConversationMode:
          parentConversationMode ?? this.parentConversationMode,
      subagentPrompt: subagentPrompt ?? this.subagentPrompt,
      subagentModelId: subagentModelId ?? this.subagentModelId,
      notificationEnabled: notificationEnabled ?? this.notificationEnabled,
      type: type ?? this.type,
      fixedTime: fixedTime ?? this.fixedTime,
      countdownMinutes: countdownMinutes ?? this.countdownMinutes,
      repeatDaily: repeatDaily ?? this.repeatDaily,
      isEnabled: isEnabled ?? this.isEnabled,
      createdAt: createdAt ?? this.createdAt,
      nextExecutionTime: nextExecutionTime ?? this.nextExecutionTime,
    );
  }

  /// 计算下次执行时间
  int calculateNextExecutionTime() {
    final now = DateTime.now();

    if (type == ScheduledTaskType.countdown) {
      // 倒计时类型：当前时间 + 倒计时分钟数
      return now
          .add(Duration(minutes: countdownMinutes ?? 0))
          .millisecondsSinceEpoch;
    } else {
      // 固定时间类型
      if (fixedTime == null) return now.millisecondsSinceEpoch;

      final parts = fixedTime!.split(':');
      if (parts.length != 2) return now.millisecondsSinceEpoch;

      final hour = int.tryParse(parts[0]) ?? 0;
      final minute = int.tryParse(parts[1]) ?? 0;

      var scheduledDateTime = DateTime(
        now.year,
        now.month,
        now.day,
        hour,
        minute,
      );

      // 如果今天的时间已经过了，则设置为明天
      if (scheduledDateTime.isBefore(now)) {
        scheduledDateTime = scheduledDateTime.add(const Duration(days: 1));
      }

      return scheduledDateTime.millisecondsSinceEpoch;
    }
  }

  /// 获取显示的时间文本
  String getDisplayTimeText() {
    if (type == ScheduledTaskType.countdown) {
      final minutes = countdownMinutes ?? 0;
      if (minutes >= 60) {
        final hours = minutes ~/ 60;
        final mins = minutes % 60;
        if (mins > 0) {
          return '$hours小时$mins分钟后';
        }
        return '$hours小时后';
      }
      return '$minutes分钟后';
    } else {
      return fixedTime ?? '--:--';
    }
  }

  /// 获取下次执行时间的显示文本
  String getNextExecutionTimeText() {
    final en = LegacyTextLocalizer.isEnglish;
    if (nextExecutionTime == null) return en ? 'Not set' : '未设置';

    final nextTime = DateTime.fromMillisecondsSinceEpoch(nextExecutionTime!);
    final now = DateTime.now();
    final diff = nextTime.difference(now);

    if (diff.isNegative) return en ? 'Expired' : '已过期';

    if (diff.inDays > 0) {
      return en ? '${diff.inDays}d later' : '${diff.inDays}天后';
    } else if (diff.inHours > 0) {
      return en ? '${diff.inHours}h later' : '${diff.inHours}小时后';
    } else if (diff.inMinutes > 0) {
      return en ? '${diff.inMinutes}m later' : '${diff.inMinutes}分钟后';
    } else {
      return en ? 'Starting soon' : '即将执行';
    }
  }

  @override
  String toString() {
    return 'ScheduledTask(id: $id, title: $title, targetKind: $targetKind, type: $type, fixedTime: $fixedTime, countdownMinutes: $countdownMinutes, repeatDaily: $repeatDaily)';
  }
}
