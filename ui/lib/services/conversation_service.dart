import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/codex_app_server_service.dart';
import 'package:ui/services/conversation_history_service.dart';

class ConversationService {
  static const MethodChannel _assistCore = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );
  static const String _hiddenCodexConversationIdsKey =
      'hidden_codex_conversation_ids';

  static List<ConversationModel> _normalizeConversations(List<dynamic> raw) {
    final conversations = raw
        .whereType<Map>()
        .map(
          (json) => ConversationModel.fromJson(
            Map<String, dynamic>.from(json.cast<String, dynamic>()),
          ),
        )
        .toList();
    conversations.sort((a, b) {
      final byUpdatedAt = b.updatedAt.compareTo(a.updatedAt);
      if (byUpdatedAt != 0) return byUpdatedAt;
      final aPenalty = a.mode == ConversationMode.subagent ? 1 : 0;
      final bPenalty = b.mode == ConversationMode.subagent ? 1 : 0;
      final byMode = aPenalty.compareTo(bPenalty);
      if (byMode != 0) return byMode;
      return b.createdAt.compareTo(a.createdAt);
    });
    return conversations;
  }

  static Future<List<ConversationModel>> getAllConversations({
    bool includeArchived = false,
    bool archivedOnly = false,
  }) async {
    try {
      final result = await _assistCore.invokeMethod<List<dynamic>>(
        'getConversations',
      );
      if (result == null) return [];
      final conversations = await _filterHiddenCodexConversations(
        _normalizeConversations(result),
      );
      if (archivedOnly) {
        return conversations.where((item) => item.isArchived).toList();
      }
      if (includeArchived) {
        return conversations;
      }
      return conversations.where((item) => !item.isArchived).toList();
    } on PlatformException catch (e) {
      debugPrint('[ConversationService] 获取对话列表失败: ${e.message}');
      return [];
    } catch (e) {
      debugPrint('[ConversationService] 获取对话列表异常: $e');
      return [];
    }
  }

  static Future<List<ConversationModel>> getConversationsByPage({
    required int offset,
    required int limit,
    bool includeArchived = false,
    bool archivedOnly = false,
  }) async {
    final all = await getAllConversations(
      includeArchived: includeArchived,
      archivedOnly: archivedOnly,
    );
    if (all.isEmpty) return [];
    final start = offset < 0 ? 0 : offset;
    if (start >= all.length) return [];
    final end = (start + limit) > all.length ? all.length : (start + limit);
    return all.sublist(start, end);
  }

  static Future<int?> createConversation({
    required String title,
    String? summary,
    ConversationMode mode = ConversationMode.normal,
    int? parentConversationId,
    ConversationMode? parentConversationMode,
    String? scheduledTaskId,
  }) async {
    try {
      final result = await _assistCore
          .invokeMethod<dynamic>('createConversation', {
            'title': title,
            'summary': summary,
            'mode': mode.storageValue,
            if (parentConversationId != null && parentConversationId > 0)
              'parentConversationId': parentConversationId,
            if (parentConversationMode != null)
              'parentConversationMode': parentConversationMode.storageValue,
            if (scheduledTaskId != null && scheduledTaskId.trim().isNotEmpty)
              'scheduledTaskId': scheduledTaskId.trim(),
          });
      if (result is int) return result;
      if (result is String) return int.tryParse(result);
      return null;
    } on PlatformException catch (e) {
      debugPrint('创建对话失败: ${e.message}');
      return null;
    } catch (e) {
      debugPrint('创建对话失败: $e');
      return null;
    }
  }

  static Future<bool> updateConversation(ConversationModel conversation) async {
    try {
      final result = await _assistCore.invokeMethod<dynamic>(
        'updateConversation',
        {'conversation': conversation.toJson()},
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

  static Future<ConversationModel?> getConversationById(
    int conversationId, {
    ConversationMode? mode,
    bool includeArchived = false,
  }) async {
    final conversations = await getAllConversations(
      includeArchived: includeArchived,
    );
    for (final conversation in conversations) {
      if (conversation.id == conversationId &&
          (mode == null || conversation.mode == mode)) {
        return conversation;
      }
    }
    return null;
  }

  static Future<bool> updateConversationPromptTokenThreshold({
    required int conversationId,
    required int promptTokenThreshold,
  }) async {
    try {
      final result = await _assistCore
          .invokeMethod<dynamic>('updateConversationPromptTokenThreshold', {
            'conversationId': conversationId,
            'promptTokenThreshold': promptTokenThreshold,
          });
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('更新对话压缩阈值失败: ${e.message}');
      return false;
    } catch (e) {
      debugPrint('更新对话压缩阈值失败: $e');
      return false;
    }
  }

  static Future<bool> deleteConversation(
    int conversationId, {
    ConversationMode? mode,
  }) async {
    if (mode == ConversationMode.codex) {
      final appServerArchived = await _setCodexThreadArchivedBestEffort(
        conversationId: conversationId,
        archived: true,
      );
      final conversation = await _getConversationById(
        conversationId,
        includeArchived: true,
      );
      var localArchived = conversation == null;
      if (conversation != null) {
        localArchived =
            conversation.isArchived ||
            await updateConversation(conversation.copyWith(isArchived: true));
      }
      if (!appServerArchived && !localArchived) {
        return false;
      }
      await _markCodexConversationHidden(conversationId);
      await ConversationHistoryService.clearConversationThreadReferences(
        conversationId,
        mode: ConversationMode.codex,
      );
      await setCurrentConversationTarget(
        await ConversationHistoryService.getLastVisibleThreadTarget(),
      );
      return true;
    }
    try {
      final result = await _assistCore.invokeMethod<dynamic>(
        'deleteConversation',
        {
          'conversationId': conversationId,
          if (mode != null) 'mode': mode.storageValue,
        },
      );
      final deleted = result == 'SUCCESS';
      if (!deleted) {
        return false;
      }
      await ConversationHistoryService.clearConversationMessages(
        conversationId,
        mode: mode ?? ConversationMode.normal,
      );
      await ConversationHistoryService.clearConversationThreadReferences(
        conversationId,
        mode: mode,
      );
      await setCurrentConversationTarget(
        await ConversationHistoryService.getLastVisibleThreadTarget(),
      );
      return true;
    } on PlatformException catch (e) {
      debugPrint('删除对话失败: ${e.message}');
      return false;
    } catch (e) {
      debugPrint('删除对话失败: $e');
      return false;
    }
  }

  static Future<bool> archiveConversation(
    ConversationModel conversation,
  ) async {
    var codexArchived = false;
    if (conversation.mode == ConversationMode.codex) {
      codexArchived = await _setCodexThreadArchivedBestEffort(
        conversationId: conversation.id,
        archived: true,
      );
    }
    final archived = await updateConversation(
      conversation.copyWith(isArchived: true),
    );
    if (!archived && !codexArchived) {
      return false;
    }
    if (conversation.mode == ConversationMode.codex) {
      await ConversationHistoryService.clearConversationThreadReferences(
        conversation.id,
        mode: ConversationMode.codex,
      );
    }
    await setCurrentConversationTarget(
      await ConversationHistoryService.getLastVisibleThreadTarget(),
    );
    return true;
  }

  static Future<bool> unarchiveConversation(
    ConversationModel conversation,
  ) async {
    var codexRestored = false;
    if (conversation.mode == ConversationMode.codex) {
      codexRestored = await _setCodexThreadArchivedBestEffort(
        conversationId: conversation.id,
        archived: false,
      );
    }
    final localRestored = await updateConversation(
      conversation.copyWith(isArchived: false),
    );
    return localRestored || codexRestored;
  }

  static Future<bool> _setCodexThreadArchivedBestEffort({
    required int conversationId,
    required bool archived,
  }) async {
    try {
      if (archived) {
        await CodexAppServerService.archiveThread(
          conversationId: conversationId,
        );
      } else {
        await CodexAppServerService.unarchiveThread(
          conversationId: conversationId,
        );
      }
      return true;
    } catch (e) {
      final action = archived ? '归档' : '恢复';
      debugPrint('Codex thread $action 同步失败，继续使用本地历史状态: $e');
      return false;
    }
  }

  static Future<ConversationModel?> _getConversationById(
    int conversationId, {
    bool includeArchived = false,
  }) async {
    return getConversationById(
      conversationId,
      includeArchived: includeArchived,
    );
  }

  static Future<List<ConversationModel>> _filterHiddenCodexConversations(
    List<ConversationModel> conversations,
  ) async {
    if (conversations.isEmpty) {
      return conversations;
    }
    final hiddenIds = await _getHiddenCodexConversationIds();
    if (hiddenIds.isEmpty) {
      return conversations;
    }
    return conversations
        .where(
          (conversation) =>
              conversation.mode != ConversationMode.codex ||
              !hiddenIds.contains(conversation.id),
        )
        .toList();
  }

  static Future<Set<int>> _getHiddenCodexConversationIds() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return (prefs.getStringList(_hiddenCodexConversationIdsKey) ??
              const <String>[])
          .map(int.tryParse)
          .whereType<int>()
          .toSet();
    } catch (e) {
      debugPrint('[ConversationService] 读取 Codex 隐藏会话失败: $e');
      return const <int>{};
    }
  }

  static Future<void> _markCodexConversationHidden(int conversationId) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final hiddenIds = await _getHiddenCodexConversationIds();
      if (!hiddenIds.add(conversationId)) {
        return;
      }
      final encoded = hiddenIds.map((id) => id.toString()).toList()..sort();
      await prefs.setStringList(_hiddenCodexConversationIdsKey, encoded);
    } catch (e) {
      debugPrint('[ConversationService] 保存 Codex 隐藏会话失败: $e');
    }
  }

  static Future<bool> updateConversationTitle({
    required int conversationId,
    required String newTitle,
    ConversationMode mode = ConversationMode.normal,
  }) async {
    if (mode == ConversationMode.codex) {
      try {
        await CodexAppServerService.setThreadName(
          conversationId: conversationId,
          name: newTitle,
        );
        final localConversation = await getConversationById(
          conversationId,
          mode: mode,
          includeArchived: true,
        );
        if (localConversation != null) {
          final localUpdated = await updateConversation(
            localConversation.copyWith(title: newTitle),
          );
          if (!localUpdated) {
            debugPrint('同步 Codex 本地对话标题失败');
          }
        }
        return true;
      } catch (e) {
        debugPrint('更新 Codex 对话标题失败: $e');
        return false;
      }
    }
    try {
      final result = await _assistCore
          .invokeMethod<dynamic>('updateConversationTitle', {
            'conversationId': conversationId,
            'newTitle': newTitle,
            'mode': mode.storageValue,
          });
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('更新对话标题失败: ${e.message}');
      return false;
    } catch (e) {
      debugPrint('更新对话标题失败: $e');
      return false;
    }
  }

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
    ConversationMode mode = ConversationMode.normal,
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
      mode: target?.mode ?? ConversationMode.normal,
    );
  }

  static Future<ConversationModel?> getLatestConversation({
    ConversationMode? mode,
    bool includeArchived = false,
  }) async {
    final conversations = await getAllConversations(
      includeArchived: includeArchived,
    );
    for (final conversation in conversations) {
      if (mode == null || conversation.mode == mode) {
        return conversation;
      }
    }
    return null;
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
    );
  }
}
