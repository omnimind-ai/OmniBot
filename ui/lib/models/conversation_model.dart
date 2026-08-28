import 'package:ui/l10n/legacy_text_localizer.dart';

enum ConversationMode {
  normal('normal'),
  chatOnly('chat_only'),
  openclaw('openclaw'),
  subagent('subagent'),
  agent('agent');

  const ConversationMode(this.storageValue);

  final String storageValue;

  String get canonicalStorageValue => storageValue;

  static ConversationMode fromStorageValue(String? value) {
    final normalized = value?.trim().toLowerCase() ?? '';
    switch (normalized) {
      // `codex` is the legacy database value from an earlier build. The
      // domain mode is generic Agent; Codex is only one possible Harness.
      case 'agent':
      case 'codex':
      case 'acp':
      case 'coding':
      case 'normal':
        return ConversationMode.agent;
      case 'chat':
      case 'chatonly':
      case 'chat-only':
        return ConversationMode.chatOnly;
    }
    for (final mode in ConversationMode.values) {
      if (mode.storageValue == normalized) {
        return mode;
      }
    }
    // Missing/unknown mode is a new-data boundary. `normal` is handled above
    // as a legacy alias, so no new conversation can re-enter that split path.
    return ConversationMode.agent;
  }

  String get displayLabel => switch (this) {
    ConversationMode.normal => LegacyTextLocalizer.localize('小万'),
    ConversationMode.chatOnly => LegacyTextLocalizer.localize('纯聊天'),
    ConversationMode.openclaw => 'OpenClaw',
    ConversationMode.subagent => 'SubAgent',
    ConversationMode.agent => 'Agent',
  };
}

class ConversationModel {
  final int id;
  final ConversationMode mode;

  /// Agent 模式会话绑定的工作目录。
  /// 其余模式恒为 null。
  final String? agentCwd;

  /// Agent 模式会话绑定的 ACP Agent。
  /// 用于在切换会话时立即恢复正确的品牌和运行时。
  final String? agentId;
  final bool isArchived;
  final bool isPinned;
  final int? parentConversationId;
  final ConversationMode? parentConversationMode;
  final String? scheduledTaskId;
  final String title;
  final String? summary;
  final String? contextSummary;
  final int? contextSummaryCutoffEntryDbId;
  final int contextSummaryUpdatedAt;
  final int status; // 0: 进行中, 1: 已完成
  final String? lastMessage;
  final int messageCount;
  final int latestPromptTokens;
  final int promptTokenThreshold;
  final int latestPromptTokensUpdatedAt;
  final int createdAt;
  final int updatedAt;

  ConversationModel({
    required this.id,
    this.mode = ConversationMode.agent,
    this.agentCwd,
    this.agentId,
    this.isArchived = false,
    this.isPinned = false,
    this.parentConversationId,
    this.parentConversationMode,
    this.scheduledTaskId,
    required this.title,
    this.summary,
    this.contextSummary,
    this.contextSummaryCutoffEntryDbId,
    this.contextSummaryUpdatedAt = 0,
    required this.status,
    this.lastMessage,
    required this.messageCount,
    this.latestPromptTokens = 0,
    this.promptTokenThreshold = 128000,
    this.latestPromptTokensUpdatedAt = 0,
    required this.createdAt,
    required this.updatedAt,
  });

  factory ConversationModel.fromJson(Map<String, dynamic> json) {
    return ConversationModel(
      id: (json['id'] as num?)?.toInt() ?? 0,
      mode: ConversationMode.fromStorageValue(json['mode'] as String?),
      agentCwd:
          ((json['agentCwd'] ?? json['codexCwd']) as String?)
                  ?.trim()
                  .isNotEmpty ==
              true
          ? ((json['agentCwd'] ?? json['codexCwd']) as String).trim()
          : null,
      agentId: json['agentId']?.toString().trim().isNotEmpty == true
          ? json['agentId'].toString().trim()
          : null,
      isArchived: json['isArchived'] as bool? ?? false,
      isPinned: json['isPinned'] as bool? ?? false,
      parentConversationId: (json['parentConversationId'] as num?)?.toInt(),
      parentConversationMode: json['parentConversationMode'] == null
          ? null
          : ConversationMode.fromStorageValue(
              json['parentConversationMode'] as String?,
            ),
      scheduledTaskId: json['scheduledTaskId'] as String?,
      title: (json['title'] ?? '').toString(),
      summary: json['summary'] as String?,
      contextSummary: json['contextSummary'] as String?,
      contextSummaryCutoffEntryDbId:
          (json['contextSummaryCutoffEntryDbId'] as num?)?.toInt(),
      contextSummaryUpdatedAt:
          (json['contextSummaryUpdatedAt'] as num?)?.toInt() ?? 0,
      status: (json['status'] as num?)?.toInt() ?? 0,
      lastMessage: json['lastMessage'] as String?,
      messageCount: (json['messageCount'] as num?)?.toInt() ?? 0,
      latestPromptTokens: (json['latestPromptTokens'] as num?)?.toInt() ?? 0,
      promptTokenThreshold:
          (json['promptTokenThreshold'] as num?)?.toInt() ?? 128000,
      latestPromptTokensUpdatedAt:
          (json['latestPromptTokensUpdatedAt'] as num?)?.toInt() ?? 0,
      createdAt: (json['createdAt'] as num?)?.toInt() ?? 0,
      updatedAt: (json['updatedAt'] as num?)?.toInt() ?? 0,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'mode': mode.storageValue,
      'agentCwd': agentCwd,
      'agentId': agentId,
      'isArchived': isArchived,
      'isPinned': isPinned,
      'parentConversationId': parentConversationId,
      'parentConversationMode': parentConversationMode?.storageValue,
      'scheduledTaskId': scheduledTaskId,
      'title': title,
      'summary': summary,
      'contextSummary': contextSummary,
      'contextSummaryCutoffEntryDbId': contextSummaryCutoffEntryDbId,
      'contextSummaryUpdatedAt': contextSummaryUpdatedAt,
      'status': status,
      'lastMessage': lastMessage,
      'messageCount': messageCount,
      'latestPromptTokens': latestPromptTokens,
      'promptTokenThreshold': promptTokenThreshold,
      'latestPromptTokensUpdatedAt': latestPromptTokensUpdatedAt,
      'createdAt': createdAt,
      'updatedAt': updatedAt,
    };
  }

  ConversationModel copyWith({
    int? id,
    ConversationMode? mode,
    String? agentCwd,
    String? agentId,
    bool? isArchived,
    bool? isPinned,
    int? parentConversationId,
    ConversationMode? parentConversationMode,
    String? scheduledTaskId,
    String? title,
    String? summary,
    String? contextSummary,
    int? contextSummaryCutoffEntryDbId,
    int? contextSummaryUpdatedAt,
    int? status,
    String? lastMessage,
    int? messageCount,
    int? latestPromptTokens,
    int? promptTokenThreshold,
    int? latestPromptTokensUpdatedAt,
    int? createdAt,
    int? updatedAt,
  }) {
    return ConversationModel(
      id: id ?? this.id,
      mode: mode ?? this.mode,
      agentCwd: agentCwd ?? this.agentCwd,
      agentId: agentId ?? this.agentId,
      isArchived: isArchived ?? this.isArchived,
      isPinned: isPinned ?? this.isPinned,
      parentConversationId: parentConversationId ?? this.parentConversationId,
      parentConversationMode:
          parentConversationMode ?? this.parentConversationMode,
      scheduledTaskId: scheduledTaskId ?? this.scheduledTaskId,
      title: title ?? this.title,
      summary: summary ?? this.summary,
      contextSummary: contextSummary ?? this.contextSummary,
      contextSummaryCutoffEntryDbId:
          contextSummaryCutoffEntryDbId ?? this.contextSummaryCutoffEntryDbId,
      contextSummaryUpdatedAt:
          contextSummaryUpdatedAt ?? this.contextSummaryUpdatedAt,
      status: status ?? this.status,
      lastMessage: lastMessage ?? this.lastMessage,
      messageCount: messageCount ?? this.messageCount,
      latestPromptTokens: latestPromptTokens ?? this.latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold ?? this.promptTokenThreshold,
      latestPromptTokensUpdatedAt:
          latestPromptTokensUpdatedAt ?? this.latestPromptTokensUpdatedAt,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  // 获取格式化的时间显示（今天/昨天/日期）
  String get timeDisplay {
    final now = DateTime.now();
    updatedDate;
    final today = DateTime(now.year, now.month, now.day);
    final updatedDay = DateTime(
      updatedDate.year,
      updatedDate.month,
      updatedDate.day,
    );

    final difference = today.difference(updatedDay).inDays;

    if (difference == 0) {
      return LegacyTextLocalizer.localize('今天');
    } else if (difference == 1) {
      return LegacyTextLocalizer.localize('昨天');
    } else if (difference < 7) {
      // 显示星期几
      final weekdays = LegacyTextLocalizer.isEnglish
          ? ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
          : ['一', '二', '三', '四', '五', '六', '日'];
      return LegacyTextLocalizer.isEnglish
          ? weekdays[updatedDate.weekday - 1]
          : '周${weekdays[updatedDate.weekday - 1]}';
    } else {
      // 显示月-日
      return '${updatedDate.month}-${updatedDate.day}';
    }
  }

  DateTime get updatedDate => DateTime.fromMillisecondsSinceEpoch(updatedAt);

  /// Agent 会话所属项目名：工作目录的最后一段路径（如 /root/blog → blog）。
  String? get agentProjectName {
    final normalized = (agentCwd ?? '').trim().replaceAll(RegExp(r'/+$'), '');
    if (normalized.isEmpty) {
      return (agentCwd ?? '').trim() == '/' ? '/' : null;
    }
    final segments = normalized
        .split('/')
        .where((segment) => segment.isNotEmpty)
        .toList(growable: false);
    return segments.isEmpty ? null : segments.last;
  }

  bool get isActive => status == 0;

  /// Whether the conversation has reached its durable terminal state.
  ///
  /// This is persisted conversation metadata, not a replacement for the
  /// shared runtime's live-work signal. The sidebar combines both sources:
  /// runtime state wins for "running", while this flag supplies the stable
  /// completed marker after the run has been folded into history.
  bool get isCompleted => status == 1;

  bool get isScheduledChild =>
      parentConversationId != null && parentConversationId! > 0;

  double? get contextUsageRatio {
    if (promptTokenThreshold <= 0) return null;
    if (latestPromptTokensUpdatedAt <= 0 && latestPromptTokens <= 0) {
      return null;
    }
    return latestPromptTokens / promptTokenThreshold;
  }

  String get threadKey => '${mode.canonicalStorageValue}:$id';
}
