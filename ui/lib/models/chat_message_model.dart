import 'dart:convert';

import 'package:ui/models/chat_link_preview.dart';

/// AI聊天消息模型
///
/// 支持不同类型的消息：用户消息、AI回复消息，以及卡片类型消息

/// 聊天消息模型
class ChatMessageModel {
  /// 消息唯一标识
  final String id;

  /// 消息类型：1=普通消息, 2=卡片消息
  final int type;

  /// 发送方：1=用户, 2=AI, 3=系统（卡片消息）
  final int user;

  /// 内容数据（包含text、cardData、id等）
  final Map<String, dynamic>? content;

  /// 是否为加载中状态
  final bool isLoading;

  /// 是否为第一个切片（用于流式消息持久化）
  final bool isFirst;

  /// 是否为错误消息
  final bool isError;

  /// 是否为总结中状态
  final bool isSummarizing;

  /// 原生流式排序元数据
  final Map<String, dynamic>? streamMeta;
  final Map<String, dynamic>? turnUsage;
  final String? reasoningContent;

  /// 创建时间
  final DateTime createAt;

  ChatMessageModel({
    required this.id,
    required this.type,
    required this.user,
    this.content,
    this.isLoading = false,
    this.isFirst = false,
    this.isError = false,
    this.isSummarizing = false,
    this.streamMeta,
    this.turnUsage,
    this.reasoningContent,
    DateTime? createAt,
  }) : createAt = createAt ?? DateTime.now();

  /// 获取文本内容
  String? get text {
    final value = content?['text'];
    return value == null ? null : value.toString();
  }

  /// 获取卡片数据
  Map<String, dynamic>? get cardData {
    final value = content?['cardData'];
    if (value is Map<String, dynamic>) {
      return value;
    }
    if (value is Map) {
      return _normalizeMap(value);
    }
    return null;
  }

  /// 获取内容ID（用于卡片）
  String? get contentId {
    final value = content?['id'];
    return value == null ? null : value.toString();
  }

  /// 生成此消息的 ACP Agent。旧消息可能为空，渲染层使用通用 Agent 外观。
  String? get agentId => _normalizeOptionalString(
    content?['agentId'] ?? cardData?['agentId'] ?? streamMeta?['agentId'],
  );

  String? get agentName => _normalizeOptionalString(
    content?['agentName'] ?? cardData?['agentName'] ?? streamMeta?['agentName'],
  );

  /// Canonical projected ACP identity. The legacy card/stream field names are
  /// read only as fallbacks so old conversations remain renderable.
  String? get runId => _normalizeOptionalString(
    streamMeta?['runId'] ??
        streamMeta?['parentTaskId'] ??
        cardData?['runId'] ??
        cardData?['taskID'] ??
        cardData?['taskId'],
  );

  String? get sessionId => _normalizeOptionalString(
    streamMeta?['sessionId'] ?? cardData?['sessionId'],
  );

  String? get turnId =>
      _normalizeOptionalString(streamMeta?['turnId'] ?? cardData?['turnId']);

  String? get itemId =>
      _normalizeOptionalString(streamMeta?['itemId'] ?? cardData?['itemId']);

  String? get toolCallId => _normalizeOptionalString(
    streamMeta?['toolCallId'] ?? cardData?['toolCallId'],
  );

  String? get cardId => _normalizeOptionalString(
    streamMeta?['cardId'] ?? cardData?['cardId'] ?? contentId,
  );

  /// 获取数据库ID（用于本地存储和渲染key）
  int? get dbId => _asNullableInt(content?['dbId']);

  List<ChatLinkPreview> get linkPreviews {
    final raw = content?['linkPreviews'];
    if (raw is! List) {
      return const <ChatLinkPreview>[];
    }
    return raw
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .map(ChatLinkPreview.fromJson)
        .toList();
  }

  /// 从JSON创建
  factory ChatMessageModel.fromJson(Map<String, dynamic> json) {
    final normalizedContent = _normalizeDynamic(json['content']);
    final normalizedType = _asNullableInt(json['type']) ?? 1;
    final normalizedUser = _asNullableInt(json['user']) ?? 1;
    final contentMap = normalizedContent is Map<String, dynamic>
        ? _normalizeAssistantTextContent(
            normalizedContent,
            type: normalizedType,
            user: normalizedUser,
          )
        : null;
    return ChatMessageModel(
      id: json['id']?.toString() ?? '',
      type: normalizedType,
      user: normalizedUser,
      content: contentMap,
      isLoading: json['isLoading'] as bool? ?? false,
      isFirst: json['isFirst'] as bool? ?? false,
      isError: json['isError'] as bool? ?? false,
      isSummarizing: json['isSummarizing'] as bool? ?? false,
      streamMeta: _normalizeDynamic(json['streamMeta']) is Map<String, dynamic>
          ? _normalizeDynamic(json['streamMeta']) as Map<String, dynamic>
          : null,
      turnUsage: _normalizeDynamic(json['turnUsage']) is Map<String, dynamic>
          ? _normalizeDynamic(json['turnUsage']) as Map<String, dynamic>
          : null,
      reasoningContent: _normalizeOptionalString(
        json['reasoning_content'] ?? json['reasoningContent'],
      ),
      createAt: _parseCreateAt(json['createAt']),
    );
  }

  /// 转换为JSON
  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'type': type,
      'user': user,
      'content': content,
      'isLoading': isLoading,
      'isFirst': isFirst,
      'isError': isError,
      'isSummarizing': isSummarizing,
      if (streamMeta != null) 'streamMeta': streamMeta,
      if (turnUsage != null) 'turnUsage': turnUsage,
      if (reasoningContent != null) 'reasoning_content': reasoningContent,
      'createAt': createAt.toIso8601String(),
    };
  }

  /// 创建用户发送的消息
  factory ChatMessageModel.userMessage(String text, {String? id}) {
    final messageId = id ?? DateTime.now().millisecondsSinceEpoch.toString();
    return ChatMessageModel(
      id: messageId,
      type: 1, // 普通消息
      user: 1, // 用户
      content: {'text': text, 'id': messageId},
    );
  }

  /// 创建AI回复的消息
  factory ChatMessageModel.assistantMessage(
    String text, {
    String? id,
    bool isLoading = false,
    String? reasoningContent,
  }) {
    final messageId = id ?? DateTime.now().millisecondsSinceEpoch.toString();
    return ChatMessageModel(
      id: messageId,
      type: 1, // 普通消息
      user: 2, // AI
      content: {'text': text, 'id': messageId},
      isLoading: isLoading,
      reasoningContent: _normalizeOptionalString(reasoningContent),
    );
  }

  /// 创建卡片消息
  factory ChatMessageModel.cardMessage(
    Map<String, dynamic> cardData, {
    String? id,
    Map<String, dynamic>? streamMeta,
  }) {
    final messageId = id ?? DateTime.now().millisecondsSinceEpoch.toString();
    return ChatMessageModel(
      id: messageId,
      type: 2, // 卡片消息
      user: 3, // 系统
      content: {'cardData': cardData, 'id': messageId},
      streamMeta: streamMeta,
    );
  }

  /// 复制消息并更新字段（用于流式更新）
  ChatMessageModel copyWith({
    String? id,
    int? type,
    int? user,
    Map<String, dynamic>? content,
    bool? isLoading,
    bool? isFirst,
    bool? isError,
    bool? isSummarizing,
    Map<String, dynamic>? streamMeta,
    Map<String, dynamic>? turnUsage,
    String? reasoningContent,
    DateTime? createAt,
  }) {
    return ChatMessageModel(
      id: id ?? this.id,
      type: type ?? this.type,
      user: user ?? this.user,
      content: content ?? this.content,
      isLoading: isLoading ?? this.isLoading,
      isFirst: isFirst ?? this.isFirst,
      isError: isError ?? this.isError,
      isSummarizing: isSummarizing ?? this.isSummarizing,
      streamMeta: streamMeta ?? this.streamMeta,
      turnUsage: turnUsage ?? this.turnUsage,
      reasoningContent: reasoningContent ?? this.reasoningContent,
      createAt: createAt ?? this.createAt,
    );
  }

  static int? _asNullableInt(dynamic raw) {
    if (raw is int) return raw;
    if (raw is num) {
      final asDouble = raw.toDouble();
      if (asDouble.isFinite && asDouble == asDouble.truncateToDouble()) {
        return raw.toInt();
      }
      return null;
    }
    if (raw is String) {
      final trimmed = raw.trim();
      final parsedInt = int.tryParse(trimmed);
      if (parsedInt != null) {
        return parsedInt;
      }
      final parsedDouble = double.tryParse(trimmed);
      if (parsedDouble != null &&
          parsedDouble.isFinite &&
          parsedDouble == parsedDouble.truncateToDouble()) {
        return parsedDouble.toInt();
      }
    }
    return null;
  }

  static String? _normalizeOptionalString(dynamic raw) {
    final value = raw?.toString().trim() ?? '';
    return value.isEmpty ? null : value;
  }

  static DateTime _parseCreateAt(dynamic raw) {
    if (raw is DateTime) {
      return raw;
    }
    if (raw is num) {
      return DateTime.fromMillisecondsSinceEpoch(raw.toInt());
    }
    if (raw is String) {
      final trimmed = raw.trim();
      if (trimmed.isEmpty) {
        return DateTime.now();
      }
      final parsedDateTime = DateTime.tryParse(trimmed);
      if (parsedDateTime != null) {
        return parsedDateTime;
      }
      final millis = int.tryParse(trimmed);
      if (millis != null) {
        return DateTime.fromMillisecondsSinceEpoch(millis);
      }
    }
    return DateTime.now();
  }

  static Map<String, dynamic> _normalizeAssistantTextContent(
    Map<String, dynamic> content, {
    required int type,
    required int user,
  }) {
    if (type != 1 || user != 2) {
      return content;
    }
    final rawText = content['text']?.toString() ?? '';
    final trimmed = rawText.trimLeft();
    if (trimmed.isEmpty || !trimmed.startsWith('{')) {
      return content;
    }
    final sanitized = _sanitizePersistedAssistantText(rawText);
    if (sanitized == rawText) {
      return content;
    }
    return <String, dynamic>{...content, 'text': sanitized};
  }

  static String _sanitizePersistedAssistantText(String raw) {
    final firstContentIndex = raw.indexOf(RegExp(r'\S'));
    if (firstContentIndex < 0 || raw[firstContentIndex] != '{') {
      return raw;
    }

    final extractedBuffer = StringBuffer();
    var cursor = firstContentIndex;
    var strippedTransportFrames = false;

    while (cursor < raw.length) {
      final nextNonWhitespace = _skipWhitespace(raw, cursor);
      if (nextNonWhitespace >= raw.length || raw[nextNonWhitespace] != '{') {
        cursor = nextNonWhitespace;
        break;
      }

      final jsonEnd = _findBalancedJsonObjectEnd(raw, nextNonWhitespace);
      if (jsonEnd == null) {
        cursor = nextNonWhitespace;
        break;
      }

      final extracted = _tryExtractTransportAssistantText(
        raw.substring(nextNonWhitespace, jsonEnd + 1),
      );
      if (extracted == null) {
        cursor = nextNonWhitespace;
        break;
      }

      strippedTransportFrames = true;
      if (extracted.isNotEmpty) {
        extractedBuffer.write(extracted);
      }
      cursor = jsonEnd + 1;
    }

    if (!strippedTransportFrames) {
      return raw;
    }

    final sanitized =
        '${raw.substring(0, firstContentIndex)}${extractedBuffer.toString()}${raw.substring(cursor)}'
            .trim();
    if (sanitized.isEmpty) {
      return '';
    }
    return sanitized;
  }

  static int _skipWhitespace(String raw, int start) {
    var index = start;
    while (index < raw.length && raw[index].trim().isEmpty) {
      index += 1;
    }
    return index;
  }

  static int? _findBalancedJsonObjectEnd(String raw, int start) {
    var depth = 0;
    var inString = false;
    var escaped = false;
    for (var index = start; index < raw.length; index++) {
      final char = raw[index];
      if (escaped) {
        escaped = false;
        continue;
      }
      if (inString && char == '\\') {
        escaped = true;
        continue;
      }
      if (char == '"') {
        inString = !inString;
        continue;
      }
      if (inString) {
        continue;
      }
      if (char == '{') {
        depth += 1;
      } else if (char == '}') {
        depth -= 1;
        if (depth == 0) {
          return index;
        }
      }
    }
    return null;
  }

  static String? _tryExtractTransportAssistantText(String raw) {
    final normalized = raw.trim();
    if (normalized.isEmpty || !normalized.startsWith('{')) {
      return null;
    }

    try {
      final decoded = jsonDecode(normalized);
      if (decoded is Map) {
        final choices = decoded['choices'];
        final choiceText = _tryExtractChoicesTransportText(choices);
        if (choiceText != null) {
          return choiceText;
        }
        final output = decoded['output'];
        final outputText = _tryExtractOutputTransportText(output);
        if (outputText != null) {
          return outputText;
        }
      }
    } catch (_) {}

    return null;
  }

  static String? _tryExtractChoicesTransportText(dynamic rawChoices) {
    if (rawChoices is! List) {
      return null;
    }
    if (rawChoices.isEmpty) {
      return '';
    }

    final firstChoice = rawChoices.first;
    if (firstChoice is! Map) {
      return null;
    }

    final delta = firstChoice['delta'];
    if (delta is Map) {
      return _extractTextPayload(delta['content']);
    }

    final message = firstChoice['message'];
    if (message is Map) {
      return _extractTextPayload(message['content']);
    }

    final choiceText = _extractTextPayload(
      firstChoice['text'] ?? firstChoice['content'],
    );
    if (choiceText.isNotEmpty) {
      return choiceText;
    }

    if (firstChoice.containsKey('finish_reason') ||
        firstChoice.containsKey('delta') ||
        firstChoice.containsKey('message')) {
      return '';
    }

    return null;
  }

  static String? _tryExtractOutputTransportText(dynamic rawOutput) {
    if (rawOutput is! List) {
      return null;
    }
    final hasTransportShape = rawOutput.any((item) {
      if (item is! Map) {
        return false;
      }
      final type = item['type']?.toString().trim().toLowerCase();
      return item.containsKey('content') ||
          item.containsKey('text') ||
          type == 'message' ||
          type == 'output_text' ||
          type == 'reasoning' ||
          type == 'reasoning_text';
    });
    if (!hasTransportShape) {
      return null;
    }
    return rawOutput.map(_extractTextPayload).join();
  }

  static String _extractTextPayload(dynamic raw) {
    if (raw == null) return '';
    if (raw is String) return raw;
    if (raw is List) {
      return raw.map(_extractTextPayload).join();
    }
    if (raw is Map) {
      final type = raw['type']?.toString().trim().toLowerCase();
      if (type == 'text' || type == 'output_text') {
        return _extractTextPayload(raw['text']);
      }
      if (raw.containsKey('text')) {
        return _extractTextPayload(raw['text']);
      }
      if (raw.containsKey('content')) {
        return _extractTextPayload(raw['content']);
      }
    }
    return '';
  }

  static Map<String, dynamic> _normalizeMap(Map<dynamic, dynamic> source) {
    return source.map(
      (key, value) => MapEntry(key.toString(), _normalizeDynamic(value)),
    );
  }

  static dynamic _normalizeDynamic(dynamic value) {
    if (value is Map<String, dynamic>) {
      return value.map(
        (key, nestedValue) => MapEntry(key, _normalizeDynamic(nestedValue)),
      );
    }
    if (value is Map) {
      return _normalizeMap(value);
    }
    if (value is List) {
      return value.map(_normalizeDynamic).toList();
    }
    if (value is double && value.isFinite) {
      final integral = value.toInt();
      if (value == integral.toDouble()) {
        return integral;
      }
    }
    return value;
  }
}
