part of 'chat_conversation_runtime_coordinator.dart';

/// Native/IM user-message notifications are part of the shared runtime
/// ingress. They are not an Agent stream protocol and intentionally do not
/// manufacture a Flutter-side event type.
extension _ChatRuntimeExternalMessageSupport
    on ChatConversationRuntimeCoordinator {
  void _handleExternalUserMessageAppended(Map<String, dynamic> data) {
    final conversationId = _asPositiveInt(data['conversationId']);
    if (conversationId == null) return;
    final runtimeMode = _runtimeModeFromConversationMode(
      (data['mode'] ?? data['conversationMode'] ?? '').toString(),
    );
    final runtime = _runtimes[
      _runtimeKey(conversationId: conversationId, mode: runtimeMode)
    ];
    if (runtime == null) return;

    final entryId = (data['entryId'] ?? '').toString().trim();
    if (entryId.isEmpty) return;
    final text = (data['text'] ?? '').toString();
    final createdAt = DateTime.fromMillisecondsSinceEpoch(
      _asPositiveInt(data['createdAt']) ??
          DateTime.now().millisecondsSinceEpoch,
    );
    if (_hasEquivalentAgentUserMessage(
      runtime.messages,
      entryId: entryId,
      text: text,
      createdAt: createdAt,
    )) {
      return;
    }

    final rawAttachments = data['attachments'];
    final attachments = rawAttachments is List
        ? rawAttachments
              .whereType<Map>()
              .map((item) => Map<String, dynamic>.from(item.cast()))
              .toList()
        : const <Map<String, dynamic>>[];
    final message = ChatMessageModel(
      id: entryId,
      type: 1,
      user: 1,
      content: <String, dynamic>{
        'id': entryId,
        'text': text,
        if (attachments.isNotEmpty) 'attachments': attachments,
      },
      createAt: createdAt,
    );
    runtime.messages.insert(
      _findInsertIndexByCreatedAt(runtime.messages, createdAt),
      message,
    );
    _notifyRuntimeListeners();
  }

  List<ChatMessageModel> _dedupeEquivalentAgentUserMessages(
    Iterable<ChatMessageModel> messages,
  ) {
    final source = List<ChatMessageModel>.from(messages);
    final preferredByCanonicalId = <String, ChatMessageModel>{};
    for (final message in source) {
      if (message.user != 1) continue;
      final canonicalId = _canonicalAgentUserMessageId(message.id);
      if (canonicalId == null) continue;
      final existing = preferredByCanonicalId[canonicalId];
      if (existing == null || _preferAgentUserMessage(message, existing)) {
        preferredByCanonicalId[canonicalId] = message;
      }
    }
    if (preferredByCanonicalId.isEmpty) return source;
    return source
        .where((message) {
          if (message.user != 1) return true;
          final canonicalId = _canonicalAgentUserMessageId(message.id);
          if (canonicalId == null) return true;
          return identical(preferredByCanonicalId[canonicalId], message);
        })
        .toList(growable: false);
  }

  bool _hasEquivalentAgentUserMessage(
    Iterable<ChatMessageModel> messages, {
    required String entryId,
    String? text,
    DateTime? createdAt,
  }) {
    final canonicalId = _canonicalAgentUserMessageId(entryId);
    if (canonicalId == null) {
      return messages.any((message) => message.id == entryId);
    }
    final normalizedText = text?.trim();
    for (final message in messages) {
      if (message.user != 1) continue;
      if (message.id == entryId) return true;
      if (_canonicalAgentUserMessageId(message.id) != canonicalId) continue;
      if (normalizedText != null && normalizedText.isNotEmpty) {
        final existingText = (message.text ?? '').trim();
        if (existingText.isNotEmpty && existingText != normalizedText) continue;
      }
      if (createdAt != null &&
          message.createAt.difference(createdAt).inMilliseconds.abs() > 1000) {
        continue;
      }
      return true;
    }
    return false;
  }

  bool _preferAgentUserMessage(
    ChatMessageModel candidate,
    ChatMessageModel existing,
  ) {
    final candidateIsLocal = !candidate.id.endsWith('-ai-user');
    final existingIsLocal = !existing.id.endsWith('-ai-user');
    if (candidateIsLocal != existingIsLocal) return candidateIsLocal;
    return candidate.createAt.isAfter(existing.createAt);
  }

  String? _canonicalAgentUserMessageId(String rawId) {
    final id = rawId.trim();
    if (id.isEmpty) return null;
    if (id.endsWith('-ai-user')) {
      return '${id.substring(0, id.length - '-ai-user'.length)}-user';
    }
    if (id.endsWith('-user')) return id;
    return null;
  }

  int _findInsertIndexByCreatedAt(
    List<ChatMessageModel> messages,
    DateTime createdAt,
  ) {
    for (var index = 0; index < messages.length; index += 1) {
      if (!messages[index].createAt.isAfter(createdAt)) return index;
    }
    return messages.length;
  }

  int? _asPositiveInt(dynamic raw) {
    final value = switch (raw) {
      int value => value,
      num value => value.toInt(),
      String value => int.tryParse(value.trim()),
      _ => null,
    };
    return value != null && value > 0 ? value : null;
  }

  String _runtimeModeFromConversationMode(String rawMode) {
    return switch (ConversationMode.fromStorageValue(rawMode)) {
      ConversationMode.openclaw => kChatRuntimeModeOpenClaw,
      ConversationMode.agent => kChatRuntimeModeAgent,
      _ => kChatRuntimeModeNormal,
    };
  }
}
