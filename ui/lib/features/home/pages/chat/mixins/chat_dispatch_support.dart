import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:ui/models/conversation_model.dart';
import '../../../../../models/chat_message_model.dart';
import '../../../../../services/storage_service.dart';
import '../../command_overlay/services/chat_service.dart';

/// 聊天上下文存储的key
const String kChatContextStorageKey = 'chat_context_for_summary';
const String kCompactedContextSummaryPrefix =
    '<context-summary> The following is a summary of the earlier conversation that was compacted to save context space.';
const int _kMaxInlineImageBytes = 20 * 1024 * 1024;

/// 聊天调度支持 Mixin
/// 负责处理可执行任务、发送消息等功能
mixin ChatDispatchSupport<T extends StatefulWidget> on State<T> {
  final Map<String, String> _imageDataUrlCache = <String, String>{};

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

  // ===================== 对话历史构建 =====================

  /// 构建对话历史
  List<Map<String, dynamic>> buildConversationHistory() {
    final List<Map<String, dynamic>> history = [];
    final recentMessages = ChatService.getRecentMessages(
      messages,
      maxCount: 10,
    );

    for (final message in recentMessages) {
      if (message.user == 1) {
        final content = _buildMessageContentForModel(message);
        if (content is String && content.isNotEmpty) {
          history.insert(0, {'role': 'user', 'content': content});
        } else if (content is List && content.isNotEmpty) {
          history.insert(0, {'role': 'user', 'content': content});
        }
      } else if (message.user == 2) {
        final text = message.content?['text'] as String? ?? '';
        if (text.isNotEmpty) {
          history.insert(0, {'role': 'assistant', 'content': text});
        }
      }
    }
    final contextSummary = (currentConversation?.contextSummary ?? '').trim();
    if (contextSummary.isNotEmpty &&
        !history.any((message) {
          final content = message['content'];
          return content is String &&
              content.startsWith(kCompactedContextSummaryPrefix);
        })) {
      history.insert(0, {
        'role': 'user',
        'content': '$kCompactedContextSummaryPrefix\n$contextSummary',
      });
    }
    return history;
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

  dynamic _buildMessageContentForModel(ChatMessageModel message) {
    final attachments = _extractAttachmentList(message);
    final imageAttachments = attachments
        .where(
          (item) =>
              _isImageAttachment(item) && _shouldSendAttachmentToModel(item),
        )
        .toList();

    if (imageAttachments.isEmpty) {
      return _buildMessageTextForModel(message);
    }

    final blocks = <Map<String, dynamic>>[];
    final normalizedText = _buildMessageTextForModel(message).trim();
    if (normalizedText.isNotEmpty) {
      blocks.add({'type': 'text', 'text': normalizedText});
    }

    for (final attachment in imageAttachments) {
      final url = _resolveImageAttachmentUrl(attachment);
      if (url.isEmpty) continue;
      blocks.add({
        'type': 'image_url',
        'image_url': {'url': url},
      });
    }

    if (blocks.isEmpty) {
      return _buildMessageTextForModel(message);
    }
    return blocks;
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

  String _resolveImageAttachmentUrl(Map<String, dynamic> attachment) {
    final dataUrl = (attachment['dataUrl'] as String? ?? '').trim();
    if (dataUrl.startsWith('data:')) return dataUrl;

    final url = (attachment['url'] as String? ?? '').trim();
    if (url.startsWith('http://') ||
        url.startsWith('https://') ||
        url.startsWith('data:')) {
      return url;
    }

    final path = (attachment['path'] as String? ?? '').trim();
    if (path.isEmpty) return '';
    final cached = _imageDataUrlCache[path];
    if (cached != null && cached.isNotEmpty) return cached;

    final file = File(path);
    if (!file.existsSync()) return '';
    try {
      // This is retained only for the legacy history builder. The active ACP
      // path keeps the resource reference and lets Native materialize it.
      // Bound the compatibility path as well so one old caller cannot turn a
      // large image into an unbounded Dart memory allocation.
      if (file.lengthSync() > _kMaxInlineImageBytes) return '';
      final bytes = file.readAsBytesSync();
      if (bytes.isEmpty) return '';
      final mimeType = (attachment['mimeType'] as String? ?? '')
          .trim()
          .toLowerCase();
      final safeMimeType = mimeType.startsWith('image/')
          ? mimeType
          : _guessImageMimeType(path);
      final encoded = base64Encode(bytes);
      final normalized = 'data:$safeMimeType;base64,$encoded';
      _imageDataUrlCache[path] = normalized;
      return normalized;
    } catch (_) {
      return '';
    }
  }

  String _guessImageMimeType(String path) {
    final lower = path.toLowerCase();
    if (lower.endsWith('.png')) return 'image/png';
    if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) {
      return 'image/jpeg';
    }
    if (lower.endsWith('.gif')) return 'image/gif';
    if (lower.endsWith('.webp')) return 'image/webp';
    if (lower.endsWith('.bmp')) return 'image/bmp';
    if (lower.endsWith('.heic')) return 'image/heic';
    if (lower.endsWith('.heif')) return 'image/heif';
    return 'image/png';
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
