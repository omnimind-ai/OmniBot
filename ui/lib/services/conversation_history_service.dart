import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/agent_message_kinds.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/omnibot_resource_service.dart';

/// 对话历史持久化服务
class ConversationHistoryService {
  static const MethodChannel _assistCore = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );
  static const String _legacyConversationIdKey = 'current_conversation_id';
  static const String _conversationIdKeyPrefix = 'current_conversation_id_';
  static const String _conversationTargetKeyPrefix =
      'current_conversation_target_';
  static const String _lastVisibleThreadTargetKey =
      'last_visible_conversation_target';
  static const String _conversationMessagesKey = 'conversation_messages_';
  static const String conversationMessagesKeyPrefix = _conversationMessagesKey;
  static final Map<String, Future<void>> _conversationMessageWriteQueues =
      <String, Future<void>>{};

  static ConversationMode _canonicalConversationMode(ConversationMode mode) {
    return mode == ConversationMode.normal ? ConversationMode.agent : mode;
  }

  static String _conversationIdKeyForMode(ConversationMode mode) {
    return '$_conversationIdKeyPrefix${mode.canonicalStorageValue}';
  }

  static String _conversationTargetKeyForMode(ConversationMode mode) {
    return '$_conversationTargetKeyPrefix${mode.canonicalStorageValue}';
  }

  /// 保存当前对话ID
  static Future<void> saveCurrentConversationId(
    int? conversationId, {
    ConversationMode mode = ConversationMode.agent,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    final modeKey = _conversationIdKeyForMode(mode);
    if (conversationId == null) {
      await prefs.remove(modeKey);
      if (mode == ConversationMode.normal) {
        await prefs.remove(_legacyConversationIdKey);
      }
    } else {
      await prefs.setInt(modeKey, conversationId);
    }
  }

  /// 获取当前对话ID
  static Future<int?> getCurrentConversationId({
    ConversationMode mode = ConversationMode.agent,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    final id =
        prefs.getInt(_conversationIdKeyForMode(mode)) ??
        (mode == ConversationMode.normal
            ? prefs.getInt(_legacyConversationIdKey)
            : null);
    return id == 0 ? null : id;
  }

  static Future<ConversationThreadTarget?> getCurrentConversationTarget({
    required ConversationMode mode,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_conversationTargetKeyForMode(mode));
    if (raw != null && raw.trim().isNotEmpty) {
      try {
        final target = ConversationThreadTarget.fromEncodedJson(raw);
        return target.copyWith(
          mode: _canonicalConversationMode(mode),
          fromNativeRoute: false,
          clearRequestKey: true,
        );
      } catch (e) {
        debugPrint('解析当前线程目标失败: $e');
      }
    }
    final conversationId = await getCurrentConversationId(mode: mode);
    if (conversationId == null) {
      return null;
    }
    return ConversationThreadTarget.existing(
      conversationId: conversationId,
      mode: _canonicalConversationMode(mode),
    );
  }

  static Future<void> saveCurrentConversationTarget(
    ConversationThreadTarget? target, {
    required ConversationMode mode,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    final key = _conversationTargetKeyForMode(mode);
    if (target == null) {
      await prefs.remove(key);
      await saveCurrentConversationId(null, mode: mode);
      return;
    }

    final sanitized = target.copyWith(
      mode: _canonicalConversationMode(mode),
      fromNativeRoute: false,
      clearRequestKey: true,
    );
    await prefs.setString(key, sanitized.toEncodedJson());
    await saveCurrentConversationId(sanitized.conversationId, mode: mode);
  }

  static Future<void> saveLastVisibleThreadTarget(
    ConversationThreadTarget? target,
  ) async {
    final prefs = await SharedPreferences.getInstance();
    if (target == null) {
      await prefs.remove(_lastVisibleThreadTargetKey);
      return;
    }
    final sanitized = target.copyWith(
      mode: target.mode,
      fromNativeRoute: false,
      clearRequestKey: true,
    );
    await prefs.setString(
      _lastVisibleThreadTargetKey,
      sanitized.toEncodedJson(),
    );
  }

  static Future<ConversationThreadTarget?> getLastVisibleThreadTarget() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_lastVisibleThreadTargetKey);
    if (raw == null || raw.trim().isEmpty) {
      for (final mode in ConversationMode.values) {
        final target = await getCurrentConversationTarget(mode: mode);
        if (target == null) {
          continue;
        }
        return target;
      }
      return null;
    }
    try {
      final target = ConversationThreadTarget.fromEncodedJson(raw);
      return target;
    } catch (e) {
      debugPrint('解析上次可见线程失败: $e');
      return null;
    }
  }

  static Future<void> clearConversationThreadReferences(
    int conversationId, {
    ConversationMode? mode,
  }) async {
    final modes = mode == null
        ? ConversationMode.values
        : <ConversationMode>[mode];
    for (final entryMode in modes) {
      final currentTarget = await getCurrentConversationTarget(mode: entryMode);
      if (currentTarget?.conversationId == conversationId) {
        await saveCurrentConversationTarget(null, mode: entryMode);
      }
    }

    final lastVisible = await getLastVisibleThreadTarget();
    if (lastVisible != null &&
        lastVisible.conversationId == conversationId &&
        (mode == null ||
            lastVisible.mode.canonicalStorageValue ==
                mode.canonicalStorageValue)) {
      await saveLastVisibleThreadTarget(null);
    }
  }

  /// 重新加载本地存储（用于多引擎/跨隔离同步）
  static Future<void> reloadLocalCache() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.reload();
    } catch (e) {
      debugPrint('刷新本地缓存失败: $e');
    }
  }

  static String conversationMessagesKey(
    int conversationId, {
    ConversationMode mode = ConversationMode.agent,
  }) {
    return '$_conversationMessagesKey${mode.canonicalStorageValue}_$conversationId';
  }

  /// Exports the durable conversation snapshot through the app's existing
  /// share boundary. Native Room remains the source of truth; this is only a
  /// user-visible copy and never becomes a second history protocol.
  static Future<bool> exportConversation(
    int conversationId, {
    ConversationMode mode = ConversationMode.agent,
  }) async {
    final messages = await getConversationMessages(conversationId, mode: mode);
    final payload = const JsonEncoder.withIndent('  ').convert({
      'conversationId': conversationId,
      'mode': mode.canonicalStorageValue,
      'messages': messages.map((message) => message.toJson()).toList(),
    });
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
    final messages = await getConversationMessages(conversationId, mode: mode);
    final text = buildConversationClipboardText(messages);
    if (text.isEmpty) {
      return false;
    }
    return AssistsMessageService.copyToClipboard(text);
  }

  static String buildConversationClipboardText(
    List<ChatMessageModel> messages,
  ) {
    final sections = <String>[];
    for (final message in messages.reversed) {
      if (message.type != 1) {
        continue;
      }
      final text = (message.text ?? '').trim();
      if (text.isEmpty) {
        continue;
      }
      final role = message.user == 1 ? '用户' : '助手';
      sections.add('$role：\n$text');
    }
    return sections.join('\n\n');
  }

  static String _legacyConversationMessagesKey(int conversationId) {
    return '$_conversationMessagesKey$conversationId';
  }

  static List<String> _legacyConversationMessageKeys(
    int conversationId, {
    required ConversationMode mode,
  }) {
    final keys = <String>[conversationMessagesKey(conversationId, mode: mode)];
    if (mode == ConversationMode.normal || mode == ConversationMode.agent) {
      keys.add(
        '$_conversationMessagesKey${ConversationMode.normal.storageValue}_$conversationId',
      );
      keys.add(_legacyConversationMessagesKey(conversationId));
      // Read both the canonical generic Agent key and the old Codex-named
      // key. Codex is a Harness, not the conversation domain mode.
      keys.add(
        '$_conversationMessagesKey${ConversationMode.agent.name}_$conversationId',
      );
      keys.add('${_conversationMessagesKey}codex_$conversationId');
      // Older Xiaowan builds wrote these snapshots before the conversation
      // domain switched from `normal` to canonical `agent`.
      keys.add(
        '$_conversationMessagesKey${ConversationMode.normal.storageValue}_$conversationId',
      );
      keys.add(_legacyConversationMessagesKey(conversationId));
    }
    return keys;
  }

  static ConversationMessageStorageKey? tryParseConversationMessagesKey(
    String key,
  ) {
    if (!key.startsWith(conversationMessagesKeyPrefix)) {
      return null;
    }
    final suffix = key.substring(conversationMessagesKeyPrefix.length);
    final lastUnderscoreIndex = suffix.lastIndexOf('_');
    if (lastUnderscoreIndex < 0) {
      final conversationId = int.tryParse(suffix);
      if (conversationId == null) {
        return null;
      }
      return ConversationMessageStorageKey(
        conversationId: conversationId,
        mode: ConversationMode.normal,
      );
    }

    final modeStorageValue = suffix.substring(0, lastUnderscoreIndex);
    final conversationId = int.tryParse(
      suffix.substring(lastUnderscoreIndex + 1),
    );
    if (modeStorageValue.isEmpty || conversationId == null) {
      return null;
    }
    return ConversationMessageStorageKey(
      conversationId: conversationId,
      mode: ConversationMode.fromStorageValue(modeStorageValue),
    );
  }

  /// 保存对话消息列表。
  ///
  /// Native replacement is a destructive delete-and-rebuild operation. Keep
  /// writes for one logical conversation ordered so an older stream snapshot
  /// cannot finish after a newer one and roll the thread back.
  static Future<void> saveConversationMessages(
    int conversationId,
    List<ChatMessageModel> messages, {
    ConversationMode mode = ConversationMode.agent,
  }) {
    final key = '${mode.canonicalStorageValue}:$conversationId';
    final snapshot = List<ChatMessageModel>.from(messages);
    final previous =
        _conversationMessageWriteQueues[key] ?? Future<void>.value();
    final next = _runConversationMessageWrite(
      previous,
      () => _saveConversationMessages(conversationId, snapshot, mode: mode),
    );
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
  }) async {
    final jsonList = messages.map((m) => m.toJson()).toList();
    final stored = await _replaceNativeConversationMessages(
      conversationId,
      jsonList,
      mode: mode,
    );
    if (stored) {
      await _clearLegacyConversationMessages(conversationId, mode: mode);
      return;
    }

    await _writeLegacyConversationMessages(
      conversationId,
      jsonList,
      mode: mode,
    );
  }

  static Future<bool> _replaceNativeConversationMessages(
    int conversationId,
    List<Map<String, dynamic>> jsonList, {
    required ConversationMode mode,
  }) async {
    try {
      await _assistCore.invokeMethod('replaceConversationMessages', {
        'conversationId': conversationId,
        'mode': mode.canonicalStorageValue,
        'messages': jsonList,
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

  /// Compatibility reader for every supported history generation.
  ///
  /// Native ACP history is authoritative when complete. If it is unavailable
  /// or empty, this reader checks the old local snapshot keys, normalizes old
  /// Agent/tool payloads, merges both sources by stable message identity, and
  /// only then performs a forward migration. A stale `messageCount == 0` must
  /// never erase a non-empty legacy snapshot: an explicit clear removes both
  /// native and legacy stores, so an existing legacy snapshot is recoverable
  /// history rather than proof of an intentional clear.
  static Future<List<ChatMessageModel>> readConversationHistory(
    int conversationId, {
    ConversationMode mode = ConversationMode.agent,
    int? expectedMessageCount,
  }) async {
    try {
      final result = await _assistCore.invokeMethod<List<dynamic>>(
        'getConversationMessages',
        {'conversationId': conversationId, 'mode': mode.canonicalStorageValue},
      );
      final nativeMessages = _decodeMessageList(result, mode: mode);
      return _resolveNativeAndLegacyMessages(
        conversationId,
        mode: mode,
        nativeMessages: nativeMessages,
        expectedMessageCount: expectedMessageCount,
      );
    } on PlatformException catch (e) {
      debugPrint('获取对话历史失败: ${e.message}');
    } catch (e) {
      debugPrint('解析对话历史失败: $e');
    }

    return _restoreLegacyConversationMessages(
      conversationId,
      mode: mode,
      expectedMessageCount: expectedMessageCount,
    );
  }

  /// 分页获取对话消息列表
  static Future<({List<ChatMessageModel> messages, bool hasMore})>
  getConversationMessagesPaged(
    int conversationId, {
    ConversationMode mode = ConversationMode.agent,
    int limit = 20,
    int offset = 0,
    int? expectedMessageCount,
  }) async {
    try {
      final result = await _assistCore
          .invokeMethod<Map<dynamic, dynamic>>('getConversationMessagesPaged', {
            'conversationId': conversationId,
            'mode': mode.canonicalStorageValue,
            'limit': limit,
            'offset': offset,
          });
      if (result == null) {
        return _legacyPagedConversationMessages(
          conversationId,
          mode: mode,
          limit: limit,
          offset: offset,
          expectedMessageCount: expectedMessageCount,
        );
      }
      final messagesList = result['messages'] as List<dynamic>? ?? [];
      final hasMore = result['hasMore'] as bool? ?? false;
      final messages = _decodeMessageList(messagesList, mode: mode);
      if (!hasMore && offset == 0) {
        final recoveredMessages = await _resolveNativeAndLegacyMessages(
          conversationId,
          mode: mode,
          nativeMessages: messages,
          expectedMessageCount: expectedMessageCount,
        );
        final pageSize = limit <= 0 ? recoveredMessages.length : limit;
        return (
          messages: recoveredMessages.take(pageSize).toList(),
          hasMore: recoveredMessages.length > pageSize,
        );
      }
      return (messages: messages, hasMore: hasMore);
    } on PlatformException catch (e) {
      debugPrint('分页获取对话历史失败: ${e.message}');
    } catch (e) {
      debugPrint('分页解析对话历史失败: $e');
    }

    return _legacyPagedConversationMessages(
      conversationId,
      mode: mode,
      limit: limit,
      offset: offset,
      expectedMessageCount: expectedMessageCount,
    );
  }

  static Future<({List<ChatMessageModel> messages, bool hasMore})>
  _legacyPagedConversationMessages(
    int conversationId, {
    required ConversationMode mode,
    required int limit,
    required int offset,
    int? expectedMessageCount,
  }) async {
    // An older native runtime may not implement the paged method but can
    // still provide the complete canonical snapshot. Read that existing
    // history boundary instead of retaining a separate legacy-only cursor:
    // migration is then safe before later pages are requested.
    final persistedMessages = await readConversationHistory(
      conversationId,
      mode: mode,
      expectedMessageCount: expectedMessageCount,
    );
    final start = offset.clamp(0, persistedMessages.length).toInt();
    final end = limit <= 0
        ? persistedMessages.length
        : (start + limit).clamp(0, persistedMessages.length).toInt();
    return (
      messages: persistedMessages.sublist(start, end),
      hasMore: end < persistedMessages.length,
    );
  }

  static List<ChatMessageModel> _decodeMessageList(
    dynamic raw, {
    required ConversationMode mode,
  }) {
    if (raw is! List) return <ChatMessageModel>[];
    return raw
        .whereType<Map>()
        .map((json) {
          final message = ChatMessageModel.fromJson(
            Map<String, dynamic>.from(json.cast<String, dynamic>()),
          );
          return mode == ConversationMode.agent ||
                  mode == ConversationMode.normal
              ? canonicalizeAgentHistoryMessage(message)
              : message;
        })
        .where(_shouldRetainRestoredMessage)
        .toList();
  }

  static Future<List<ChatMessageModel>> _restoreLegacyConversationMessages(
    int conversationId, {
    required ConversationMode mode,
    int? expectedMessageCount,
  }) async {
    return _resolveNativeAndLegacyMessages(
      conversationId,
      mode: mode,
      nativeMessages: const <ChatMessageModel>[],
      expectedMessageCount: expectedMessageCount,
    );
  }

  static Future<List<ChatMessageModel>> _resolveNativeAndLegacyMessages(
    int conversationId, {
    required ConversationMode mode,
    required List<ChatMessageModel> nativeMessages,
    int? expectedMessageCount,
  }) async {
    final legacyMessages = await _readLegacyConversationMessages(
      conversationId,
      mode: mode,
    );
    if (legacyMessages.isEmpty) {
      return nativeMessages;
    }
    final recoveredMessages = nativeMessages.isEmpty
        ? legacyMessages
        : _mergeMessageSnapshots(
            nativeMessages: nativeMessages,
            legacyMessages: legacyMessages,
          );
    if (recoveredMessages.length <= nativeMessages.length) {
      await _clearLegacyConversationMessages(conversationId, mode: mode);
      return nativeMessages;
    }

    final jsonList = recoveredMessages
        .map((message) => message.toJson())
        .toList();
    final migrated = await _replaceNativeConversationMessages(
      conversationId,
      jsonList,
      mode: mode,
    );
    if (migrated) {
      await _clearLegacyConversationMessages(conversationId, mode: mode);
    }
    return recoveredMessages;
  }

  static List<ChatMessageModel> _mergeMessageSnapshots({
    required List<ChatMessageModel> nativeMessages,
    required List<ChatMessageModel> legacyMessages,
  }) {
    final seen = <String>{};
    final indexedMessages = <({ChatMessageModel message, int order})>[];

    void appendIfNew(ChatMessageModel message, int order) {
      final key = _messageIdentityKey(message);
      if (!seen.add(key)) {
        return;
      }
      indexedMessages.add((message: message, order: order));
    }

    for (var index = 0; index < nativeMessages.length; index += 1) {
      appendIfNew(nativeMessages[index], index);
    }
    for (var index = 0; index < legacyMessages.length; index += 1) {
      appendIfNew(legacyMessages[index], nativeMessages.length + index);
    }

    indexedMessages.sort((left, right) {
      final byCreatedAt = right.message.createAt.compareTo(
        left.message.createAt,
      );
      if (byCreatedAt != 0) return byCreatedAt;
      return left.order.compareTo(right.order);
    });
    return indexedMessages.map((item) => item.message).toList();
  }

  static String _messageIdentityKey(ChatMessageModel message) {
    final id = message.id.trim();
    if (id.isNotEmpty) {
      return 'id:$id';
    }
    final contentId = message.contentId?.trim() ?? '';
    if (contentId.isNotEmpty) {
      return 'content:$contentId';
    }
    final dbId = message.dbId;
    if (dbId != null) {
      return 'db:$dbId';
    }
    return [
      'fallback',
      message.type,
      message.user,
      message.createAt.millisecondsSinceEpoch,
      jsonEncode(message.content ?? const <String, dynamic>{}),
    ].join(':');
  }

  static Future<List<ChatMessageModel>> _readLegacyConversationMessages(
    int conversationId, {
    required ConversationMode mode,
  }) async {
    final prefs = await _optionalSharedPreferences(operation: '读取旧版对话历史');
    if (prefs == null) {
      return <ChatMessageModel>[];
    }
    final snapshots = <List<ChatMessageModel>>[];
    for (final key in _legacyConversationMessageKeys(
      conversationId,
      mode: mode,
    )) {
      final raw = prefs.getString(key);
      if (raw == null || raw.trim().isEmpty) {
        continue;
      }
      try {
        final decoded = jsonDecode(raw);
        final messages = _decodeMessageList(decoded, mode: mode);
        if (messages.isNotEmpty) {
          snapshots.add(messages);
        }
      } catch (e) {
        debugPrint('解析旧版对话历史失败 key=$key: $e');
      }
    }
    if (snapshots.isEmpty) {
      return <ChatMessageModel>[];
    }
    if (snapshots.length == 1) {
      return snapshots.single;
    }
    // A conversation can have been written to more than one legacy bucket
    // during the normal -> agent migration. Read all buckets and merge by
    // stable message identity instead of stopping at the first non-empty key.
    return _mergeMessageSnapshots(
      nativeMessages: const <ChatMessageModel>[],
      legacyMessages: snapshots.expand((snapshot) => snapshot).toList(),
    );
  }

  static Future<void> _writeLegacyConversationMessages(
    int conversationId,
    List<Map<String, dynamic>> jsonList, {
    required ConversationMode mode,
  }) async {
    final prefs = await _optionalSharedPreferences(operation: '写入旧版对话历史兜底');
    if (prefs == null) {
      return;
    }
    await prefs.setString(
      conversationMessagesKey(conversationId, mode: mode),
      jsonEncode(jsonList),
    );
  }

  static Future<void> _clearLegacyConversationMessages(
    int conversationId, {
    required ConversationMode mode,
  }) async {
    final prefs = await _optionalSharedPreferences(operation: '清理旧版对话历史');
    if (prefs == null) {
      return;
    }
    for (final key in _legacyConversationMessageKeys(
      conversationId,
      mode: mode,
    )) {
      await prefs.remove(key);
    }
  }

  static Future<SharedPreferences?> _optionalSharedPreferences({
    required String operation,
  }) async {
    try {
      return await SharedPreferences.getInstance();
    } on MissingPluginException {
      return null;
    } catch (e) {
      debugPrint('$operation 跳过：$e');
      return null;
    }
  }

  static bool _shouldRetainRestoredMessage(ChatMessageModel message) {
    if (message.type != 1 || message.user != 2) {
      return true;
    }
    final text = message.text?.trim() ?? '';
    if (text.isNotEmpty ||
        message.isError ||
        message.isLoading ||
        message.isSummarizing) {
      return true;
    }
    final attachments = message.content?['attachments'];
    return attachments is List && attachments.isNotEmpty;
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
  }) async {
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
    await _clearLegacyConversationMessages(conversationId, mode: mode);
  }
}

class ConversationMessageStorageKey {
  const ConversationMessageStorageKey({
    required this.conversationId,
    required this.mode,
  });

  final int conversationId;
  final ConversationMode mode;

  String get threadKey => '${mode.canonicalStorageValue}:$conversationId';
}
