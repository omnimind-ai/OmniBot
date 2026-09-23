import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/omnibot_resource_service.dart';

/// 对话历史持久化服务
class ConversationHistoryService {
  // Acknowledged immutable display objects only, weakly held. No JSON hashing of history.
  // A fresh process submits its visible page once. Failed writes are not acknowledged.
  static final Map<String, Map<String, WeakReference<ChatMessageModel>>>
  _acknowledgedWrites = {};

  @visibleForTesting
  static void resetWriteAcknowledgements() => _acknowledgedWrites.clear();

  static const MethodChannel _assistCore = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );
  static final Map<String, Future<void>> _conversationMessageWriteQueues = {};

  static Future<T?> _selection<T>(
    String operation, [
    Map<String, dynamic> arguments = const {},
  ]) => _assistCore.invokeMethod<T>('conversationSelection', {
    'operation': operation,
    ...arguments,
  });

  static Future<void> saveCurrentConversationId(
    int? conversationId, {
    ConversationMode mode = ConversationMode.agent,
  }) => _selection<void>('saveId', {
    'conversationId': conversationId,
    'mode': mode.storageValue,
  });

  static Future<int?> getCurrentConversationId({
    ConversationMode mode = ConversationMode.agent,
  }) => _selection<int>('getId', {'mode': mode.storageValue});

  static Future<ConversationThreadTarget?> getCurrentConversationTarget({
    required ConversationMode mode,
  }) async {
    final row = await _selection<Map>('getTarget', {'mode': mode.storageValue});
    return row == null
        ? null
        : ConversationThreadTarget.fromJson(Map<String, dynamic>.from(row));
  }

  static Future<void> saveCurrentConversationTarget(
    ConversationThreadTarget? target, {
    required ConversationMode mode,
  }) => _selection<void>('saveTarget', {
    'target': target?.toJson(),
    'mode': mode.storageValue,
  });

  static Future<void> saveLastVisibleThreadTarget(
    ConversationThreadTarget? target,
  ) => _selection<void>('saveLast', {'target': target?.toJson()});

  static Future<ConversationThreadTarget?> getLastVisibleThreadTarget() async {
    final row = await _selection<Map>('getLast');
    return row == null
        ? null
        : ConversationThreadTarget.fromJson(Map<String, dynamic>.from(row));
  }

  static Future<void> clearConversationThreadReferences(
    int conversationId, {
    ConversationMode? mode,
  }) => _selection<void>('clearReferences', {
    'conversationId': conversationId,
    if (mode != null) 'mode': mode.storageValue,
  });

  // Selection reads now go directly to the host shared by all engines.
  static Future<void> reloadLocalCache() async {}

  /// Exports the durable conversation snapshot through the app's existing
  /// share boundary. Native Room remains the source of truth; this is only a
  /// user-visible copy and never becomes a second history protocol.
  static Future<bool> exportConversation(
    int conversationId, {
    ConversationMode mode = ConversationMode.agent,
  }) async {
    final payload = await _assistCore.invokeMethod<String>(
      'getConversationTranscript',
      {
        'conversationId': conversationId,
        'mode': mode.storageValue,
        'format': 'json',
      },
    );
    if (payload == null) return false;
    return OmnibotResourceService.shareText(payload);
  }

  /// Copies the user-visible dialogue in chronological order.
  ///
  /// Thinking/tool/system cards intentionally stay out of the clipboard
  /// representation. They remain available in the exported JSON snapshot,
  /// while Copy conversation produces the readable transcript users expect.
  static Future<bool> copyConversation(
    int conversationId, {
    ConversationMode mode = ConversationMode.agent,
  }) async {
    final text = await _assistCore.invokeMethod<String>(
      'getConversationTranscript',
      {
        'conversationId': conversationId,
        'mode': mode.storageValue,
        'format': 'text',
      },
    );
    if (text == null || text.isEmpty) return false;
    return AssistsMessageService.copyToClipboard(text);
  }

  /// 保存对话消息列表。
  ///
  /// Runtime snapshots merge by message identity. Only explicit user history
  /// edits may remove missing entries; writes remain ordered per conversation.
  static Future<void> saveConversationMessages(
    int conversationId,
    List<ChatMessageModel> messages, {
    ConversationMode mode = ConversationMode.agent,
    bool allowHistoryRemoval = false,
  }) {
    final key = '${mode.canonicalStorageValue}:$conversationId';
    final snapshot = List<ChatMessageModel>.from(messages);
    return _enqueueConversationMessageWrite(
      key,
      () => _saveConversationMessages(
        conversationId,
        snapshot,
        mode: mode,
        allowHistoryRemoval: allowHistoryRemoval,
      ),
    );
  }

  static Future<void> _enqueueConversationMessageWrite(
    String key,
    Future<void> Function() write,
  ) {
    final previous =
        _conversationMessageWriteQueues[key] ?? Future<void>.value();
    final next = _runConversationMessageWrite(previous, write);
    _conversationMessageWriteQueues[key] = next;
    return next.whenComplete(() {
      if (identical(_conversationMessageWriteQueues[key], next)) {
        _conversationMessageWriteQueues.remove(key);
      }
    });
  }

  static Future<void> _runConversationMessageWrite(
    Future<void> previous,
    Future<void> Function() write,
  ) async {
    try {
      await previous;
    } catch (_) {
      // A failed snapshot must not permanently block later snapshots for the
      // same conversation.
    }
    await write();
  }

  static Future<void> _saveConversationMessages(
    int conversationId,
    List<ChatMessageModel> messages, {
    required ConversationMode mode,
    bool allowHistoryRemoval = false,
  }) async {
    final key = '${mode.canonicalStorageValue}:$conversationId';
    final previous =
        _acknowledgedWrites[key] ??
        const <String, WeakReference<ChatMessageModel>>{};
    final next = <String, WeakReference<ChatMessageModel>>{};
    final changed = <Map<String, dynamic>>[];
    for (final message in messages) {
      next[message.id] = WeakReference(message);
      if (allowHistoryRemoval ||
          !identical(previous[message.id]?.target, message)) {
        changed.add(message.toJson());
      }
    }
    if (changed.isEmpty && !allowHistoryRemoval) return;
    final stored = await _replaceNativeConversationMessages(
      conversationId,
      changed,
      mode: mode,
      allowHistoryRemoval: allowHistoryRemoval,
    );
    if (stored) {
      _acknowledgedWrites[key] = allowHistoryRemoval
          ? next
          : {...previous, ...next};
      return;
    }

    // Legacy storage is an import source, not a competing destination for
    // failed live writes. Surface failure so admission/flush retains its owner.
    throw StateError('Native conversation persistence failed');
  }

  static Future<bool> _replaceNativeConversationMessages(
    int conversationId,
    List<Map<String, dynamic>> jsonList, {
    required ConversationMode mode,
    bool allowHistoryRemoval = false,
  }) async {
    try {
      await _assistCore.invokeMethod('replaceConversationMessages', {
        'conversationId': conversationId,
        'mode': mode.canonicalStorageValue,
        'messages': jsonList,
        'allowHistoryRemoval': allowHistoryRemoval,
      });
      return true;
    } on PlatformException catch (e) {
      debugPrint('保存对话历史失败: ${e.message}');
      return false;
    } catch (e) {
      debugPrint('保存对话历史异常: $e');
      return false;
    }
  }

  /// 获取对话消息列表
  static Future<List<ChatMessageModel>> getConversationMessages(
    int conversationId, {
    ConversationMode mode = ConversationMode.agent,
    int? expectedMessageCount,
  }) => readConversationHistory(
    conversationId,
    mode: mode,
    expectedMessageCount: expectedMessageCount,
  );

  /// Kotlin owns legacy import, reconciliation and Room paging on Dispatchers.IO.
  /// Only the requested display payload crosses the platform channel.
  static Future<List<ChatMessageModel>> readConversationHistory(
    int conversationId, {
    ConversationMode mode = ConversationMode.agent,
    int? expectedMessageCount,
  }) async {
    final result = await _assistCore.invokeMethod<List<dynamic>>(
      'getConversationMessages',
      {'conversationId': conversationId, 'mode': mode.canonicalStorageValue},
    );
    return _decodeMessageList(result, mode: mode);
  }

  static Future<
    ({List<ChatMessageModel> messages, bool hasMore, int nextOffset})
  >
  getConversationMessagesPaged(
    int conversationId, {
    ConversationMode mode = ConversationMode.agent,
    int limit = 20,
    int offset = 0,
    int? expectedMessageCount,
  }) async {
    final result = await _assistCore.invokeMethod<Map<dynamic, dynamic>>(
      'getConversationMessagesPaged',
      {
        'conversationId': conversationId,
        'mode': mode.canonicalStorageValue,
        'limit': limit,
        'offset': offset,
      },
    );
    if (result == null) {
      throw StateError('Native conversation paging returned no result');
    }
    final rows = result['messages'] as List<dynamic>? ?? const [];
    return (
      messages: _decodeMessageList(rows, mode: mode),
      hasMore: result['hasMore'] as bool? ?? false,
      // Advance by consumed storage rows, including invisible legacy entries.
      nextOffset:
          (result['nextOffset'] as num?)?.toInt() ?? offset + rows.length,
    );
  }

  static List<ChatMessageModel> _decodeMessageList(
    dynamic raw, {
    required ConversationMode mode,
  }) {
    if (raw is! List) return <ChatMessageModel>[];
    return raw.whereType<Map>().map((json) {
      final message = ChatMessageModel.fromJson(
        Map<String, dynamic>.from(json.cast<String, dynamic>()),
      );
      return message;
    }).toList();
  }

  static Future<void> upsertConversationUiCard(
    int conversationId, {
    required String entryId,
    required Map<String, dynamic> cardData,
    int? createdAtMillis,
    ConversationMode mode = ConversationMode.agent,
  }) async {
    final normalizedEntryId = entryId.trim();
    if (normalizedEntryId.isEmpty) return;
    try {
      await _assistCore.invokeMethod('upsertConversationUiCard', {
        'conversationId': conversationId,
        'mode': mode.canonicalStorageValue,
        'entryId': normalizedEntryId,
        'cardData': cardData,
        'createdAt': createdAtMillis,
      });
    } on PlatformException catch (e) {
      debugPrint('保存 UI 卡片失败: ${e.message}');
    } catch (e) {
      debugPrint('保存 UI 卡片异常: $e');
    }
  }

  /// 清除对话消息
  static Future<void> clearConversationMessages(
    int conversationId, {
    ConversationMode mode = ConversationMode.agent,
  }) {
    final key = '${mode.canonicalStorageValue}:$conversationId';
    return _enqueueConversationMessageWrite(key, () async {
      _acknowledgedWrites.remove(
        '${mode.canonicalStorageValue}:$conversationId',
      );
      try {
        await _assistCore.invokeMethod('clearConversationMessages', {
          'conversationId': conversationId,
          'mode': mode.canonicalStorageValue,
        });
      } on PlatformException catch (e) {
        debugPrint('清理对话历史失败: ${e.message}');
      } catch (e) {
        debugPrint('清理对话历史异常: $e');
      }
    });
  }
}
