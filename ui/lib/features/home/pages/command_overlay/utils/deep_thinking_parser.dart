/// deep_thinking 增量解析结果
class DeepThinkingResult {
  final String? taskDescription;
  final List<String>? subTasks;
  final String? preparation;
  final bool hasTaskDescription;
  final bool hasSubTasks;
  final bool hasPreparation;
  final bool isDeepThinkingComplete;

  DeepThinkingResult({
    this.taskDescription,
    this.subTasks,
    this.preparation,
    this.hasTaskDescription = false,
    this.hasSubTasks = false,
    this.hasPreparation = false,
    this.isDeepThinkingComplete = false,
  });

  /// 格式化为展示文本
  /// 按照要求：sub_tasks前多换一行，每个item换行，preparation前多换一行
  String toDisplayText() {
    final buffer = StringBuffer();

    if (hasTaskDescription && taskDescription != null && taskDescription!.isNotEmpty) {
      buffer.write(taskDescription);
    }

    if (hasSubTasks && subTasks != null && subTasks!.isNotEmpty) {
      // sub_tasks前多换一行
      if (buffer.isNotEmpty) {
        buffer.write('\n\n');
      }
      // 每个sub_task item都换行
      for (int i = 0; i < subTasks!.length; i++) {
        buffer.write('任务${i + 1}: ${subTasks![i]}');
        if (i < subTasks!.length - 1) {
          buffer.write('\n');
        }
      }
    }

    if (hasPreparation && preparation != null && preparation!.isNotEmpty) {
      // preparation前多换一行
      if (buffer.isNotEmpty) {
        buffer.write('\n\n');
      }
      buffer.write(preparation);
    }

    return buffer.toString();
  }

  bool get hasAnyContent =>
      hasTaskDescription || hasSubTasks || hasPreparation;
}

/// deep_thinking 增量解析器
class DeepThinkingParser {
  /// 去除markdown代码块标记
  /// 流式返回可能被 ```json 和 ``` 包裹
  static String _stripMarkdownCodeBlock(String content) {
    String result = content;

    // 去掉开头的 ```json 或 ```
    final startPattern = RegExp(r'^```(?:json)?\s*\n?');
    result = result.replaceFirst(startPattern, '');

    // 去掉结尾的 ```
    final endPattern = RegExp(r'\n?```\s*$');
    result = result.replaceFirst(endPattern, '');

    return result;
  }

  /// 提取字段值，支持未闭合字符串的增量提取（逐字流式展示）
  ///
  /// 查找模式：`"fieldName": "value"`
  /// - 如果字符串已闭合（有结束引号），提取完整值
  /// - 如果字符串未闭合（流式返回中），提取到当前 buffer 末尾
  static String? _extractFieldValue(String buffer, String fieldName) {
    // 查找字段名的位置
    final fieldPattern = '"$fieldName"';
    final fieldIndex = buffer.indexOf(fieldPattern);
    if (fieldIndex == -1) {
      return null;
    }

    // 从字段名后查找冒号和开始引号
    final afterField = buffer.substring(fieldIndex + fieldPattern.length);
    final colonIndex = afterField.indexOf(':');
    if (colonIndex == -1) {
      return null;
    }

    final afterColon = afterField.substring(colonIndex + 1).trimLeft();
    if (!afterColon.startsWith('"')) {
      return null;
    }

    // 从开始引号后开始提取内容
    final contentStart = afterColon.substring(1);

    // 查找结束引号（需要处理转义的引号 \"）
    int pos = 0;
    final chars = contentStart.split('');
    bool escaped = false;
    int? endQuotePos;

    while (pos < chars.length) {
      if (escaped) {
        escaped = false;
      } else if (chars[pos] == '\\') {
        escaped = true;
      } else if (chars[pos] == '"') {
        endQuotePos = pos;
        break;
      }
      pos++;
    }

    // 如果找到了结束引号，提取完整值
    if (endQuotePos != null) {
      final value = contentStart.substring(0, endQuotePos);
      return _unescapeString(value);
    }

    // 如果没有找到结束引号（流式返回中），提取到当前末尾
    // 需要注意：如果末尾是不完整的转义序列（如单个 \），要去掉
    String partialValue = contentStart;

    // 去掉末尾可能的不完整转义字符
    if (partialValue.endsWith('\\')) {
      partialValue = partialValue.substring(0, partialValue.length - 1);
    }

    return _unescapeString(partialValue);
  }

  /// 反转义字符串中的特殊字符
  static String _unescapeString(String value) {
    return value
        .replaceAll(r'\"', '"')
        .replaceAll(r'\\', '\\')
        .replaceAll(r'\n', '\n')
        .replaceAll(r'\r', '\r')
        .replaceAll(r'\t', '\t');
  }

  /// 提取 sub_tasks 数组，支持未闭合数组和未闭合字符串的增量提取
  ///
  /// 查找模式：`"sub_tasks": ["task1", "task2", ...]`
  /// - 支持数组未闭合（没有 `]`）
  /// - 支持最后一个字符串未闭合（流式返回中）
  static List<String>? _extractSubTasks(String buffer) {
    // 查找 "sub_tasks" 字段
    final subTasksIndex = buffer.indexOf('"sub_tasks"');
    if (subTasksIndex == -1) {
      return null;
    }

    // 查找数组开始位置 [
    final arrayStart = buffer.indexOf('[', subTasksIndex);
    if (arrayStart == -1) {
      return null;
    }

    // 从 [ 后面开始提取
    String afterBracket = buffer.substring(arrayStart + 1);

    // 如果已经出现 ]，只取到第一个 ] 为止
    final closingBracketPos = afterBracket.indexOf(']');
    if (closingBracketPos != -1) {
      afterBracket = afterBracket.substring(0, closingBracketPos);
    }

    // 逐个提取数组项（支持未闭合字符串）
    final items = <String>[];
    int pos = 0;

    while (pos < afterBracket.length) {
      // 跳过空白和逗号
      while (pos < afterBracket.length &&
             (afterBracket[pos] == ' ' ||
              afterBracket[pos] == '\n' ||
              afterBracket[pos] == '\r' ||
              afterBracket[pos] == '\t' ||
              afterBracket[pos] == ',')) {
        pos++;
      }

      if (pos >= afterBracket.length) break;

      // 如果遇到引号，提取字符串
      if (afterBracket[pos] == '"') {
        pos++; // 跳过开始引号
        final stringStart = pos;
        bool escaped = false;
        bool foundEnd = false;

        // 查找结束引号
        while (pos < afterBracket.length) {
          if (escaped) {
            escaped = false;
          } else if (afterBracket[pos] == '\\') {
            escaped = true;
          } else if (afterBracket[pos] == '"') {
            // 找到结束引号
            final value = afterBracket.substring(stringStart, pos);
            items.add(_unescapeString(value));
            pos++; // 跳过结束引号
            foundEnd = true;
            break;
          }
          pos++;
        }

        // 如果没找到结束引号（流式返回中），提取到当前位置
        if (!foundEnd && pos > stringStart) {
          String partialValue = afterBracket.substring(stringStart, pos);
          // 去掉末尾可能的不完整转义字符
          if (partialValue.endsWith('\\')) {
            partialValue = partialValue.substring(0, partialValue.length - 1);
          }
          if (partialValue.isNotEmpty) {
            items.add(_unescapeString(partialValue));
          }
          break; // 未闭合，停止解析
        }
      } else {
        // 遇到非引号字符，跳过
        pos++;
      }
    }

    return items.isEmpty ? null : items;
  }

  /// 从buffer中增量解析deep_thinking内容
  /// 不依赖JSON.parse，通过字符串扫描+正则增量解析
  /// 支持逐字流式展示：即使字符串未闭合也能提取已返回的部分
  static DeepThinkingResult extractDeepThinking(String buffer) {
    // 先去掉markdown代码块标记
    final cleanBuffer = _stripMarkdownCodeBlock(buffer);
    String? taskDescription;
    List<String>? subTasks;
    String? preparation;
    bool hasTaskDescription = false;
    bool hasSubTasks = false;
    bool hasPreparation = false;

    // 1. task_description: "xxx" 支持未闭合字符串的增量提取
    try {
      taskDescription = _extractFieldValue(cleanBuffer, 'task_description');
      if (taskDescription != null && taskDescription.isNotEmpty) {
        hasTaskDescription = true;
      }
    } catch (e) {
    }

    // 2. preparation: "xxx" 支持未闭合字符串的增量提取
    try {
      preparation = _extractFieldValue(cleanBuffer, 'preparation');
      if (preparation != null && preparation.isNotEmpty) {
        hasPreparation = true;
      }
    } catch (e) {
    }

    // 3. sub_tasks 增量解析，支持未闭合数组和未闭合字符串
    try {
      subTasks = _extractSubTasks(cleanBuffer);
      if (subTasks != null && subTasks.isNotEmpty) {
        hasSubTasks = true;
      }
    } catch (e) {
    }

    // 4. 检测 deep_thinking 是否已完整返回
    // 当出现 deep_thinking 之后的字段时，说明 deep_thinking 已完整
    final isDeepThinkingComplete = _checkDeepThinkingComplete(cleanBuffer);

    return DeepThinkingResult(
      taskDescription: taskDescription,
      subTasks: subTasks,
      preparation: preparation,
      hasTaskDescription: hasTaskDescription,
      hasSubTasks: hasSubTasks,
      hasPreparation: hasPreparation,
      isDeepThinkingComplete: isDeepThinkingComplete,
    );
  }

  /// 检测 deep_thinking 字段是否已完整返回
  /// 通过检查是否出现了 deep_thinking 之后的字段来判断
  static bool _checkDeepThinkingComplete(String buffer) {
    // deep_thinking 之后的字段有: summary, task_title, memory_actions
    // 只要出现其中任意一个，说明 deep_thinking 已完整
    final afterDeepThinkingFields = [
      '"summary"',
      '"task_title"',
      '"memory_actions"',
    ];

    for (final field in afterDeepThinkingFields) {
      if (buffer.contains(field)) {
        return true;
      }
    }
    return false;
  }

  /// 检查buffer是否是完整的JSON
  static ({bool isComplete, String? error}) checkJsonComplete(String buffer) {
    final trimmed = buffer.trim();
    if (trimmed.isEmpty) {
      return (isComplete: false, error: '内容为空');
    }

    // 尝试只取最外层第一个 { 到最后一个 } 之间的内容
    String candidate = trimmed;
    final firstBrace = trimmed.indexOf('{');
    final lastBrace = trimmed.lastIndexOf('}');
    if (firstBrace != -1 && lastBrace > firstBrace) {
      candidate = trimmed.substring(firstBrace, lastBrace + 1);
    }

    // 简单检查括号是否匹配
    int braceCount = 0;
    int bracketCount = 0;
    for (int i = 0; i < candidate.length; i++) {
      final char = candidate[i];
      if (char == '{') braceCount++;
      if (char == '}') braceCount--;
      if (char == '[') bracketCount++;
      if (char == ']') bracketCount--;
    }

    if (braceCount != 0 || bracketCount != 0) {
      return (isComplete: false, error: '括号不匹配');
    }

    return (isComplete: true, error: null);
  }
}

