import 'dart:convert';

/// 安全解析 JSON：
/// - 能解析就返回解析结果（Map/List/num/bool/null/字符串…）
/// - 不能解析就返回 `fallback`（若未提供则返回原始字符串）
/// - 不抛异常
dynamic safeJsonDecode(
  String? input, {
  dynamic fallback,
  void Function(Object error)? onError,
}) {
  if (input == null) return fallback;

  final s = _stripUtf8Bom(input).trim();
  if (s.isEmpty) return fallback;

  // 轻量级形态判断：不是典型 JSON 起始就直接兜底，省一次 try
  if (!_looksLikeJson(s)) {
    return fallback ?? input;
  }

  try {
    return jsonDecode(s);
  } on FormatException catch (e) {
    onError?.call(e);
    return fallback ?? input;
  } catch (e) {
    onError?.call(e);
    return fallback ?? input;
  }
}

/// 安全解析 JSON V2：
/// - 在 safeJsonDecode 的基础上，增加了修复字符串内未转义换行符的功能
/// - 如果初次解析失败，会尝试修复字符串值内部的换行符（\n, \r, \t）后再次解析
/// - 能解析就返回解析结果（Map/List/num/bool/null/字符串…）
/// - 不能解析就返回 `fallback`（若未提供则返回原始字符串）
/// - 不抛异常
dynamic safeJsonDecodeV2(
  String? input, {
  dynamic fallback,
  void Function(Object error)? onError,
}) {
  if (input == null) return fallback;

  final s = _stripUtf8Bom(input).trim();
  if (s.isEmpty) return fallback;

  // 轻量级形态判断：不是典型 JSON 起始就直接兜底，省一次 try
  if (!_looksLikeJson(s)) {
    return fallback ?? input;
  }

  // 先尝试正常解析
  try {
    return jsonDecode(s);
  } on FormatException catch (e) {
    // 初次解析失败，尝试修复换行符
    onError?.call(e);

    try {
      // 方法：在字符串值内部（双引号之间）的真实换行符需要转义
      // 使用状态机方式处理，逐字符扫描
      final buffer = StringBuffer();
      bool inString = false;
      bool escaped = false;

      for (int i = 0; i < s.length; i++) {
        final char = s[i];

        if (escaped) {
          // 前一个是转义符，当前字符直接添加
          buffer.write(char);
          escaped = false;
          continue;
        }

        if (char == '\\') {
          // 遇到转义符
          buffer.write(char);
          escaped = true;
          continue;
        }

        if (char == '"') {
          // 切换字符串状态
          inString = !inString;
          buffer.write(char);
          continue;
        }

        if (inString) {
          // 在字符串内部，需要转义特殊字符
          if (char == '\n') {
            buffer.write('\\n');
          } else if (char == '\r') {
            buffer.write('\\r');
          } else if (char == '\t') {
            buffer.write('\\t');
          } else {
            buffer.write(char);
          }
        } else {
          // 在字符串外部，直接添加
          buffer.write(char);
        }
      }

      final fixedJson = buffer.toString();

      // 尝试解析修复后的 JSON
      try {
        return jsonDecode(fixedJson);
      } catch (e2) {
        // 修复后仍解析失败
        onError?.call(e2);
        return fallback ?? input;
      }
    } catch (e2) {
      // 修复过程出错
      onError?.call(e2);
      return fallback ?? input;
    }
  } catch (e) {
    onError?.call(e);
    return fallback ?? input;
  }
}

/// 如果你明确期望是对象（Map），给一个 Map 兜底
Map<String, dynamic> safeDecodeMap(
  String? input, {
  Map<String, dynamic> fallback = const {},
  void Function(Object error)? onError,
}) {
  final v = safeJsonDecode(input, fallback: fallback, onError: onError);
  return v is Map<String, dynamic> ? v : fallback;
}

String extractChatTaskText(String? input, {bool fallbackToRawText = true}) {
  final rawInput = input ?? '';
  final normalized = rawInput.trim();
  if (normalized.isEmpty || normalized == '[DONE]') {
    return '';
  }

  final decoded = safeJsonDecode(normalized, fallback: rawInput);
  return _extractChatTaskTextPayload(
    decoded,
    fallbackRawText: fallbackToRawText ? rawInput : '',
  );
}

String extractChatTaskThinking(
  String? input, {
  bool fallbackToRawText = false,
}) {
  final rawInput = input ?? '';
  final normalized = rawInput.trim();
  if (normalized.isEmpty || normalized == '[DONE]') {
    return '';
  }

  final decoded = safeJsonDecode(normalized, fallback: rawInput);
  return _extractChatTaskThinkingPayload(
    decoded,
    fallbackRawText: fallbackToRawText ? rawInput : '',
  );
}

double? extractChatTaskPrefillTokensPerSecond(String? input) {
  return _extractChatTaskNumericMetric(
    input,
    const <List<String>>[
      <String>['usage', 'prefill_tokens_per_second'],
      <String>['usage', 'performance', 'prefill_tokens_per_second'],
      <String>['prefill_tokens_per_second'],
      <String>['performance', 'prefill_tokens_per_second'],
    ],
  );
}

double? extractChatTaskDecodeTokensPerSecond(String? input) {
  return _extractChatTaskNumericMetric(
    input,
    const <List<String>>[
      <String>['usage', 'decode_tokens_per_second'],
      <String>['usage', 'performance', 'decode_tokens_per_second'],
      <String>['decode_tokens_per_second'],
      <String>['performance', 'decode_tokens_per_second'],
    ],
  );
}

String _extractChatTaskTextPayload(dynamic raw, {String fallbackRawText = ''}) {
  if (raw == null) {
    return '';
  }
  if (raw is String) {
    return raw;
  }
  if (raw is List) {
    return raw
        .map((item) => _extractChatTaskTextPayload(item, fallbackRawText: ''))
        .join();
  }
  if (raw is! Map) {
    return fallbackRawText;
  }

  final payload = raw.map((key, value) => MapEntry(key.toString(), value));

  final directText = _extractTextPayload(payload['text']);
  if (directText.isNotEmpty) {
    return directText;
  }

  final outputText = _extractTextPayload(payload['output_text']);
  if (outputText.isNotEmpty) {
    return outputText;
  }

  final contentText = _extractTextPayload(payload['content']);
  if (contentText.isNotEmpty) {
    return contentText;
  }

  final messageText = _extractChatTaskTextPayload(
    payload['message'],
    fallbackRawText: '',
  );
  if (messageText.isNotEmpty) {
    return messageText;
  }

  final choices = payload['choices'];
  if (choices is List && choices.isNotEmpty) {
    final firstChoice = choices.first;
    if (firstChoice is Map) {
      final choicePayload = firstChoice.map(
        (key, value) => MapEntry(key.toString(), value),
      );
      final deltaText = _extractChatTaskTextPayload(
        choicePayload['delta'],
        fallbackRawText: '',
      );
      if (deltaText.isNotEmpty) {
        return deltaText;
      }
      final choiceMessageText = _extractChatTaskTextPayload(
        choicePayload['message'],
        fallbackRawText: '',
      );
      if (choiceMessageText.isNotEmpty) {
        return choiceMessageText;
      }
      final choiceText = _extractTextPayload(
        choicePayload['text'] ?? choicePayload['content'],
      );
      if (choiceText.isNotEmpty) {
        return choiceText;
      }
    }
  }

  final output = payload['output'];
  if (output is List && output.isNotEmpty) {
    final outputTextFromList = output
        .map((item) => _extractChatTaskTextPayload(item, fallbackRawText: ''))
        .where((item) => item.isNotEmpty)
        .join();
    if (outputTextFromList.isNotEmpty) {
      return outputTextFromList;
    }
  }

  return fallbackRawText;
}

double? _extractChatTaskNumericMetric(
  String? input,
  List<List<String>> candidatePaths,
) {
  final rawInput = input ?? '';
  final normalized = rawInput.trim();
  if (normalized.isEmpty || normalized == '[DONE]') {
    return null;
  }

  final decoded = safeJsonDecode(normalized, fallback: null);
  return _extractChatTaskNumericMetricPayload(decoded, candidatePaths);
}

double? _extractChatTaskNumericMetricPayload(
  dynamic raw,
  List<List<String>> candidatePaths,
) {
  if (raw == null) {
    return null;
  }
  if (raw is List) {
    for (final item in raw) {
      final value = _extractChatTaskNumericMetricPayload(item, candidatePaths);
      if (value != null) {
        return value;
      }
    }
    return null;
  }
  if (raw is! Map) {
    return null;
  }

  final payload = raw.map((key, value) => MapEntry(key.toString(), value));
  for (final path in candidatePaths) {
    final candidate = deepGet(payload, path.join('.'));
    final value = _asNullableDouble(candidate);
    if (value != null) {
      return value;
    }
  }
  return null;
}

String _extractChatTaskThinkingPayload(
  dynamic raw, {
  String fallbackRawText = '',
}) {
  if (raw == null) {
    return '';
  }
  if (raw is String) {
    return '';
  }
  if (raw is List) {
    return raw
        .map(
          (item) => _extractChatTaskThinkingPayload(item, fallbackRawText: ''),
        )
        .join();
  }
  if (raw is! Map) {
    return fallbackRawText;
  }

  final payload = raw.map((key, value) => MapEntry(key.toString(), value));

  final directReasoning = _extractReasoningPayload(
    payload['reasoning_content'] ?? payload['reasoning'] ?? payload['thinking'],
  );
  if (directReasoning.isNotEmpty) {
    return directReasoning;
  }

  final messageReasoning = _extractChatTaskThinkingPayload(
    payload['message'],
    fallbackRawText: '',
  );
  if (messageReasoning.isNotEmpty) {
    return messageReasoning;
  }

  final choices = payload['choices'];
  if (choices is List && choices.isNotEmpty) {
    final firstChoice = choices.first;
    if (firstChoice is Map) {
      final choicePayload = firstChoice.map(
        (key, value) => MapEntry(key.toString(), value),
      );
      final deltaReasoning = _extractChatTaskThinkingPayload(
        choicePayload['delta'],
        fallbackRawText: '',
      );
      if (deltaReasoning.isNotEmpty) {
        return deltaReasoning;
      }
      final choiceMessageReasoning = _extractChatTaskThinkingPayload(
        choicePayload['message'],
        fallbackRawText: '',
      );
      if (choiceMessageReasoning.isNotEmpty) {
        return choiceMessageReasoning;
      }
    }
  }

  final output = payload['output'];
  if (output is List && output.isNotEmpty) {
    final outputReasoning = output
        .map(
          (item) => _extractChatTaskThinkingPayload(item, fallbackRawText: ''),
        )
        .where((item) => item.isNotEmpty)
        .join();
    if (outputReasoning.isNotEmpty) {
      return outputReasoning;
    }
  }

  return fallbackRawText;
}

String _extractTextPayload(dynamic raw) {
  if (raw == null) {
    return '';
  }
  if (raw is String) {
    return raw;
  }
  if (raw is List) {
    return raw.map(_extractTextPayload).join();
  }
  if (raw is! Map) {
    return '';
  }

  final payload = raw.map((key, value) => MapEntry(key.toString(), value));
  final type = payload['type']?.toString().trim().toLowerCase();
  if (type == 'text' || type == 'output_text') {
    final text = _extractTextPayload(payload['text']);
    if (text.isNotEmpty) {
      return text;
    }
  }

  final directText = payload['text'];
  if (directText != null && directText is! Map && directText is! List) {
    final text = directText.toString();
    if (text.isNotEmpty) {
      return text;
    }
  }

  final nestedText = _extractTextPayload(payload['text']);
  if (nestedText.isNotEmpty) {
    return nestedText;
  }

  final content = _extractTextPayload(payload['content']);
  if (content.isNotEmpty) {
    return content;
  }

  return '';
}

String _extractReasoningPayload(dynamic raw) {
  if (raw == null) {
    return '';
  }
  if (raw is String) {
    return raw;
  }
  if (raw is List) {
    return raw.map(_extractReasoningPayload).join();
  }
  if (raw is! Map) {
    return '';
  }

  final payload = raw.map((key, value) => MapEntry(key.toString(), value));
  final type = payload['type']?.toString().trim().toLowerCase();
  if (type == 'reasoning' || type == 'reasoning_text' || type == 'thinking') {
    final text = _extractTextPayload(
      payload['text'] ??
          payload['content'] ??
          payload['reasoning_content'] ??
          payload['reasoning'] ??
          payload['thinking'],
    );
    if (text.isNotEmpty) {
      return text;
    }
  }

  return _extractTextPayload(
    payload['text'] ??
        payload['content'] ??
        payload['reasoning_content'] ??
        payload['reasoning'] ??
        payload['thinking'],
  );
}

double? _asNullableDouble(dynamic raw) {
  if (raw == null) {
    return null;
  }
  if (raw is num) {
    return raw.toDouble();
  }
  return double.tryParse(raw.toString());
}

/// 如果你明确期望是数组（List），给一个 List 兜底
List<dynamic> safeDecodeList(
  String? input, {
  List<dynamic> fallback = const [],
  void Function(Object error)? onError,
}) {
  final v = safeJsonDecode(input, fallback: fallback, onError: onError);
  return v is List ? v : fallback;
}

/// 安全编码 JSON：
/// - 能编码就返回 JSON 字符串
/// - 不能编码就返回 `fallback`（若未提供则返回对象的 toString()）
/// - 不抛异常
String safeJsonEncode(
  dynamic input, {
  String? fallback,
  void Function(Object error)? onError,
}) {
  if (input == null) return fallback ?? 'null';

  try {
    return jsonEncode(input);
  } on TypeError catch (e) {
    onError?.call(e);
    return fallback ?? input.toString();
  } catch (e) {
    onError?.call(e);
    return fallback ?? input.toString();
  }
}

/// --- helpers ---

String _stripUtf8Bom(String s) {
  // 去除 UTF-8 BOM（某些日志/文件会带）
  const bom = '\uFEFF';
  return s.startsWith(bom) ? s.substring(1) : s;
}

bool _looksLikeJson(String s) {
  // 典型 JSON 五种类型的起始：{ [ " 数字/负号 t/f/null
  final ch = s.codeUnitAt(0);
  const dash = 45;
  final isDigit = ch >= 48 && ch <= 57; // 0-9
  return ch == 123 /*{*/ ||
      ch == 91 /*[*/ ||
      ch == 34 /*"*/ ||
      ch == dash ||
      isDigit ||
      s.startsWith('true') ||
      s.startsWith('false') ||
      s.startsWith('null');
}

/// 安全取值，支持点分路径（如 "a.b.c"）
/// - obj: 可以是 Map 或 List
/// - path: 点分字符串路径，比如 "a.b.0.c"
/// - fallback: 兜底值（默认 null）
dynamic deepGet(dynamic obj, String path, {dynamic fallback}) {
  if (obj == null || path.isEmpty) return fallback;

  final keys = path.split('.');
  dynamic current = obj;

  for (final key in keys) {
    if (current == null) return fallback;

    if (current is Map && current.containsKey(key)) {
      current = current[key];
    } else if (current is List) {
      final index = int.tryParse(key);
      if (index != null && index >= 0 && index < current.length) {
        current = current[index];
      } else {
        return fallback;
      }
    } else {
      return fallback;
    }
  }

  return current ?? fallback;
}
