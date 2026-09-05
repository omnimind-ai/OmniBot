import 'package:flutter/material.dart';
import 'package:ui/models/conversation_model.dart';
import '../../../../../models/chat_message_model.dart';
import '../../../../../services/storage_service.dart';

/// 聊天上下文存储的key
const String kChatContextStorageKey = 'chat_context_for_summary';

/// 聊天调度支持 Mixin
/// 负责处理可执行任务、发送消息等功能
mixin ChatDispatchSupport<T extends StatefulWidget> on State<T> {
  // ===================== 抽象属性/方法（需要在主类中实现）=====================

  List<ChatMessageModel> get messages;
  ConversationModel? get currentConversation;
  TextEditingController get messageController;
  FocusNode get inputFocusNode;
  bool get isAiResponding;
  set isAiResponding(bool value);
  bool get isInputAreaVisible;
  set isInputAreaVisible(bool value);
  bool get isExecutingTask;
  set isExecutingTask(bool value);
  bool get isCheckingExecutableTask;
  set isCheckingExecutableTask(bool value);

  String? get currentDispatchTurnId;
  set currentDispatchTurnId(String? value);
  int get currentThinkingStage;
  set currentThinkingStage(int value);
  bool get isDeepThinking;
  set isDeepThinking(bool value);
  String get deepThinkingContent;
  set deepThinkingContent(String value);

  void createThinkingCard(String taskID);
  void updateThinkingCard(String taskID);
  void handleValidationError(String taskID, String debugMessage);
  void resetDispatchState();
  Future<void> persistConversationSnapshot({
    bool generateSummary,
    bool markComplete,
    bool rethrowOnFailure = false,
    bool allowEmpty = false,
  });

  // ===================== 上下文保存 =====================

  /// 保存当前聊天上下文到本地存储
  Future<void> saveChatContext() async {
    try {
      final List<Map<String, dynamic>> contextList = messages
          .where((msg) => !msg.isLoading)
          .map((msg) => msg.toJson())
          .toList();
      await StorageService.setJson(kChatContextStorageKey, contextList);
    } catch (e) {
      debugPrint('保存聊天上下文失败: $e');
    }
  }

  /// 任务执行前的处理
  Future<void> handleBeforeTaskExecute() async {
    await saveChatContext();
    await persistConversationSnapshot();
  }

  /// 获取最新的用户输入
  String latestUserUtterance() {
    for (final message in messages) {
      if (message.user == 1) {
        final text = _buildMessageTextForModel(message);
        if (text.isNotEmpty) {
          return text;
        }
      }
    }
    return '';
  }

  String _buildMessageTextForModel(ChatMessageModel message) {
    final text = message.content?['text'] as String? ?? '';
    final attachments = _extractAttachmentList(message);
    if (attachments.isEmpty) return text;

    final pathHint = _buildAttachmentPathHint(attachments);
    if (pathHint.isNotEmpty) {
      if (text.trim().isEmpty) return pathHint;
      return '$text\n$pathHint';
    }

    final names = attachments
        .where((attachment) => !_isImageAttachment(attachment))
        .map(_resolveAttachmentName)
        .where((name) => name.trim().isNotEmpty)
        .map((name) => name.trim())
        .toList();
    if (names.isEmpty) return text;

    final attachmentHint = '已附加附件：${names.join('、')}';
    if (text.trim().isEmpty) return attachmentHint;
    return '$text\n$attachmentHint';
  }

  List<Map<String, dynamic>> _extractAttachmentList(ChatMessageModel message) {
    final raw = message.content?['attachments'];
    if (raw is! List) return const [];
    return raw
        .whereType<Map>()
        .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
        .toList();
  }

  bool _shouldSendAttachmentToModel(Map<String, dynamic> attachment) {
    final raw = attachment['sendToModel'];
    if (raw is bool) return raw;
    if (raw is String) return raw.toLowerCase() != 'false';
    return true;
  }

  String _buildAttachmentPathHint(List<Map<String, dynamic>> attachments) {
    final lines = attachments
        .map((attachment) {
          final promptPath = _resolveAttachmentPromptPath(attachment);
          if (promptPath.isEmpty) return '';
          final name = _resolveAttachmentName(attachment);
          return name.isEmpty ? '- $promptPath' : '- $name: $promptPath';
        })
        .where((line) => line.isNotEmpty)
        .toList();
    if (lines.isEmpty) return '';
    return '已添加到 workspace，可通过以下路径读取：\n${lines.join('\n')}';
  }

  String _resolveAttachmentPromptPath(Map<String, dynamic> attachment) {
    final promptPath = (attachment['promptPath'] as String? ?? '').trim();
    if (promptPath.isNotEmpty) return promptPath;
    final workspacePath = (attachment['workspacePath'] as String? ?? '').trim();
    if (workspacePath.isNotEmpty) return workspacePath;
    if (_shouldSendAttachmentToModel(attachment)) return '';
    return (attachment['path'] as String? ?? '').trim();
  }

  bool _isImageAttachment(Map<String, dynamic> attachment) {
    final mimeType = (attachment['mimeType'] as String? ?? '')
        .trim()
        .toLowerCase();
    if (mimeType.startsWith('image/')) return true;
    final explicitFlag = attachment['isImage'];
    if (explicitFlag is bool && explicitFlag) return true;
    final path = (attachment['path'] as String? ?? '').toLowerCase();
    final url = (attachment['url'] as String? ?? '').toLowerCase();
    return _pathLooksLikeImage(path) || _pathLooksLikeImage(url);
  }

  bool _pathLooksLikeImage(String value) {
    if (value.isEmpty) return false;
    final pure = value.split('?').first;
    return pure.endsWith('.png') ||
        pure.endsWith('.jpg') ||
        pure.endsWith('.jpeg') ||
        pure.endsWith('.webp') ||
        pure.endsWith('.gif') ||
        pure.endsWith('.bmp') ||
        pure.endsWith('.heic') ||
        pure.endsWith('.heif');
  }

  String _resolveAttachmentName(Map<String, dynamic> attachment) {
    final name = (attachment['name'] as String? ?? '').trim();
    if (name.isNotEmpty) return name;
    final fileName = (attachment['fileName'] as String? ?? '').trim();
    if (fileName.isNotEmpty) return fileName;
    final path = (attachment['path'] as String? ?? '').trim();
    if (path.isEmpty) return '';
    final normalized = path.replaceAll('\\', '/');
    return normalized.split('/').last;
  }

  /// 添加用户消息
  ({String userMessageId, String aiMessageId, int userCreatedAtMillis})
  addUserMessage(
    String text, {
    List<Map<String, dynamic>> attachments = const [],
  }) {
    final createdAt = DateTime.now();
    final timestamp = createdAt.millisecondsSinceEpoch.toString();
    final userMessageId = '$timestamp-user';
    final aiMessageId = '$timestamp-ai';

    setState(() {
      final content = <String, dynamic>{'text': text, 'id': userMessageId};
      if (attachments.isNotEmpty) {
        content['attachments'] = attachments;
      }
      messages.insert(
        0,
        ChatMessageModel(
          id: userMessageId,
          type: 1,
          user: 1,
          content: content,
          createAt: createdAt,
        ),
      );
      messageController.clear();
      isAiResponding = true;
    });

    return (
      userMessageId: userMessageId,
      aiMessageId: aiMessageId,
      userCreatedAtMillis: createdAt.millisecondsSinceEpoch,
    );
  }
}
