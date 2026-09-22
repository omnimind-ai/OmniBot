import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/storage_service.dart';

class ConversationService {
  static const Duration recentConversationWindow = Duration(days: 7);
  static const MethodChannel _assistCore = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );
  static final StreamController<bool> _sidebarPolicyChangedController =
      StreamController<bool>.broadcast();

  static Stream<bool> get sidebarPolicyChangedStream =>
      _sidebarPolicyChangedController.stream;

  static bool isRecentConversationsOnlyEnabled() =>
      StorageService.isRecentConversationsOnlyEnabled();

  static Future<bool> setRecentConversationsOnlyEnabled(bool enabled) async {
    final saved = await StorageService.setBool(
      StorageService.kRecentConversationsOnlyEnabledKey,
      enabled,
    );
    if (saved) {
      _sidebarPolicyChangedController.add(enabled);
    }
    return saved;
  }

  static int recentConversationCutoff({DateTime? now}) {
    return (now ?? DateTime.now())
        .subtract(recentConversationWindow)
        .millisecondsSinceEpoch;
  }

  static List<ConversationModel> _normalizeConversations(List<dynamic> raw) {
    final conversations = raw
        .whereType<Map>()
        .map(
          (json) => ConversationModel.fromJson(
            Map<String, dynamic>.from(json.cast<String, dynamic>()),
          ),
        )
        .toList();
    return conversations;
  }

  static Future<List<ConversationModel>> getAllConversations({
    bool includeArchived = false,
    bool archivedOnly = false,
    int? archiveBefore,
  }) async {
    try {
      final result = await _assistCore.invokeMethod<List<dynamic>>(
        'getConversations',
        <String, dynamic>{
          'includeArchived': includeArchived,
          'archivedOnly': archivedOnly,
          if (archiveBefore != null) 'archiveBefore': archiveBefore,
        },
      );
      if (result == null) return [];
      return _normalizeConversations(result);
    } on PlatformException catch (e) {
      debugPrint('[ConversationService] 获取对话列表失败: ${e.message}');
      return [];
    } catch (e) {
      debugPrint('[ConversationService] 获取对话列表异常: $e');
      return [];
    }
  }

  static Future<List<ConversationModel>> getSidebarConversations({
    DateTime? now,
  }) {
    final recentOnly = isRecentConversationsOnlyEnabled();
    return getAllConversations(
      includeArchived: !recentOnly,
      archiveBefore: recentOnly ? recentConversationCutoff(now: now) : null,
    );
  }

  static List<ConversationModel> filterSidebarSnapshot(
    List<ConversationModel> conversations, {
    DateTime? now,
  }) {
    if (!isRecentConversationsOnlyEnabled()) {
      return List<ConversationModel>.from(conversations);
    }
    final cutoff = recentConversationCutoff(now: now);
    return conversations
        .where(
          (conversation) =>
              !conversation.isArchived && conversation.updatedAt >= cutoff,
        )
        .toList(growable: false);
  }

  static Future<List<ConversationModel>> getConversationsByPage({
    required int offset,
    required int limit,
    bool includeArchived = false,
    bool archivedOnly = false,
    ConversationMode? mode,
  }) async {
    try {
      final result = await _assistCore.invokeListMethod<dynamic>(
        'getConversationsByPage',
        {
          'offset': offset,
          'limit': limit,
          'includeArchived': includeArchived,
          'archivedOnly': archivedOnly,
          if (mode != null) 'mode': mode.storageValue,
        },
      );
      return _normalizeConversations(result ?? const []);
    } catch (error) {
      debugPrint('获取会话页失败: $error');
      return [];
    }
  }

  static Future<int?> createConversation({
    required String title,
    String? summary,
    ConversationMode mode = ConversationMode.agent,
    String? agentId,
    int? parentConversationId,
    ConversationMode? parentConversationMode,
    String? scheduledTaskId,
    bool rethrowOnError = false,
  }) async {
    try {
      final result = await _assistCore.invokeMethod<dynamic>(
        'createConversation',
        {
          'title': title,
          'summary': summary,
          'mode': mode.storageValue,
          if (agentId != null && agentId.trim().isNotEmpty)
            'agentId': agentId.trim(),
          if (parentConversationId != null && parentConversationId > 0)
            'parentConversationId': parentConversationId,
          if (parentConversationMode != null)
            'parentConversationMode': parentConversationMode.storageValue,
          if (scheduledTaskId != null && scheduledTaskId.trim().isNotEmpty)
            'scheduledTaskId': scheduledTaskId.trim(),
        },
      );
      if (result is int) return result;
      if (result is String) return int.tryParse(result);
      return null;
    } on PlatformException catch (e) {
      debugPrint('创建对话失败: ${e.message}');
      if (rethrowOnError) rethrow;
      return null;
    } catch (e) {
      debugPrint('创建对话失败: $e');
      if (rethrowOnError) rethrow;
      return null;
    }
  }

  static Future<bool> updateConversation(
    ConversationModel conversation, {
    bool preserveLatestMetadata = false,
  }) async {
    try {
      final result = await _assistCore.invokeMethod<dynamic>(
        'updateConversation',
        {
          'conversation': conversation.toJson(),
          'preserveLatestMetadata': preserveLatestMetadata,
        },
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('更新对话失败: ${e.message}');
      return false;
    } catch (e) {
      debugPrint('更新对话失败: $e');
      return false;
    }
  }

  static Future<bool> updateConversationPromptTokenThreshold({
    required int conversationId,
    required int promptTokenThreshold,
  }) async {
    try {
      final result = await _assistCore.invokeMethod<dynamic>(
        'updateConversationPromptTokenThreshold',
        {
          'conversationId': conversationId,
          'promptTokenThreshold': promptTokenThreshold,
        },
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('更新对话压缩阈值失败: ${e.message}');
      return false;
    } catch (e) {
      debugPrint('更新对话压缩阈值失败: $e');
      return false;
    }
  }

  static Future<bool> _manage(
    String action,
    int conversationId, {
    ConversationMode? mode,
    String? title,
  }) async {
    try {
      return await _assistCore.invokeMethod<bool>('manageConversation', {
            'action': action,
            'conversationId': conversationId,
            if (mode != null) 'mode': mode.storageValue,
            if (title != null) 'title': title,
          }) ??
          false;
    } on PlatformException catch (error) {
      debugPrint('会话操作失败: ${error.message}');
      return false;
    }
  }

  static Future<bool> deleteConversation(
    int conversationId, {
    ConversationMode? mode,
  }) => _manage('delete', conversationId, mode: mode);

  static Future<bool> archiveConversation(ConversationModel conversation) =>
      _manage('archive', conversation.id, mode: conversation.mode);

  static Future<bool> unarchiveConversation(ConversationModel conversation) =>
      _manage('unarchive', conversation.id, mode: conversation.mode);

  static Future<bool> updateConversationTitle({
    required int conversationId,
    required String newTitle,
    ConversationMode mode = ConversationMode.agent,
  }) => _manage('rename', conversationId, mode: mode, title: newTitle);

  static Future<String?> generateConversationSummary({
    required String conversationHistory,
  }) async {
    try {
      final result = await _assistCore.invokeMethod(
        'generateConversationSummary',
        {'conversationHistory': conversationHistory},
      );
      return result as String?;
    } on PlatformException catch (e) {
      debugPrint('生成对话摘要失败: ${e.message}');
      return null;
    } catch (e) {
      debugPrint('生成对话摘要失败: $e');
      return null;
    }
  }

  static Future<bool> completeConversation(
    int conversationId, {
    ConversationMode? mode,
  }) async {
    try {
      final result = await _assistCore.invokeMethod<dynamic>(
        'completeConversation',
        {
          'conversationId': conversationId,
          if (mode != null) 'mode': mode.storageValue,
        },
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('完成对话失败: ${e.message}');
      return false;
    } catch (e) {
      debugPrint('完成对话失败: $e');
      return false;
    }
  }

  static Future<bool> setCurrentConversationId(
    int? conversationId, {
    ConversationMode mode = ConversationMode.agent,
  }) async {
    try {
      final result = await _assistCore.invokeMethod<dynamic>(
        'setCurrentConversationId',
        {'conversationId': conversationId ?? 0, 'mode': mode.storageValue},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('设置当前对话ID失败: ${e.message}');
      return false;
    } catch (e) {
      debugPrint('设置当前对话ID失败: $e');
      return false;
    }
  }

  static Future<bool> setCurrentConversationTarget(
    ConversationThreadTarget? target,
  ) async {
    return setCurrentConversationId(
      target?.conversationId,
      mode: target?.mode ?? ConversationMode.agent,
    );
  }

  static Future<ConversationModel?> getLatestConversation({
    ConversationMode? mode,
    bool includeArchived = false,
  }) async {
    final conversations = await getConversationsByPage(
      offset: 0,
      limit: 1,
      includeArchived: includeArchived,
      mode: mode,
    );
    return conversations.firstOrNull;
  }

  static Future<ConversationThreadTarget?> getLatestConversationTarget({
    ConversationMode? mode,
    bool includeArchived = false,
  }) async {
    final conversation = await getLatestConversation(
      mode: mode,
      includeArchived: includeArchived,
    );
    if (conversation == null) {
      return null;
    }
    return ConversationThreadTarget.existing(
      conversationId: conversation.id,
      mode: conversation.mode,
      agentId: conversation.agentId,
    );
  }
}
