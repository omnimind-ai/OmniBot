import 'dart:convert';

import 'package:ui/features/memory/models/mem0_memory_item.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/workspace_memory_service.dart';

class Mem0MemoryService {
  static Future<Mem0MemorySnapshot> getMemories({
    bool forceRefresh = false,
    int? limit,
  }) async {
    try {
      final content = await WorkspaceMemoryService.getLongMemory();
      final parsedItems = _parseItems(content);
      // The memory screen is a view over the persisted workspace file.  Do
      // not silently hide older entries; a caller that truly needs a page may
      // request one explicitly.
      final items = limit == null
          ? parsedItems
          : parsedItems.take(limit).toList();
      return Mem0MemorySnapshot(
        configured: true,
        items: items,
        relations: const [],
        fetchedAt: DateTime.now(),
        fromCache: false,
        isStale: false,
        infoMessage: items.isEmpty
            ? (LegacyTextLocalizer.isEnglish
                  ? 'Workspace long-term memory is empty'
                  : 'workspace 长期记忆为空')
            : null,
      );
    } catch (e) {
      return Mem0MemorySnapshot(
        configured: true,
        errorMessage: e.toString().replaceFirst('Exception: ', ''),
      );
    }
  }

  static Future<void> createMemory({
    required String memory,
    List<String> categories = const [],
    Map<String, dynamic> metadata = const {},
  }) async {
    final trimmed = memory.trim();
    if (trimmed.isEmpty) {
      throw Exception(
        LegacyTextLocalizer.isEnglish
            ? 'Memory content cannot be empty'
            : '记忆内容不能为空',
      );
    }
    final content = await WorkspaceMemoryService.getLongMemory();
    final lines = content.split('\n');
    lines.add('- $trimmed');
    await WorkspaceMemoryService.saveLongMemory(lines.join('\n'));
  }

  static Future<void> updateMemory({
    required String memoryId,
    required String memory,
    List<String> categories = const [],
    Map<String, dynamic> metadata = const {},
  }) async {
    final trimmed = memory.trim();
    if (trimmed.isEmpty) {
      throw Exception(
        LegacyTextLocalizer.isEnglish
            ? 'Memory content cannot be empty'
            : '记忆内容不能为空',
      );
    }
    final content = await WorkspaceMemoryService.getLongMemory();
    final updated = _replaceById(content, memoryId, trimmed);
    if (updated == null) {
      throw Exception(
        LegacyTextLocalizer.isEnglish ? 'Memory not found' : '未找到对应记忆',
      );
    }
    await WorkspaceMemoryService.saveLongMemory(updated);
  }

  static Future<void> deleteMemory({required String memoryId}) async {
    final content = await WorkspaceMemoryService.getLongMemory();
    final deleted = _deleteById(content, memoryId);
    if (deleted == null) {
      throw Exception(
        LegacyTextLocalizer.isEnglish ? 'Memory not found' : '未找到对应记忆',
      );
    }
    await WorkspaceMemoryService.saveLongMemory(deleted);
  }

  static List<Mem0MemoryItem> _parseItems(String content) {
    final lines = content
        .split('\n')
        .map((line) => line.trim())
        .where((line) => line.startsWith('- '))
        .map((line) => line.substring(2).trim())
        .where((line) => line.isNotEmpty)
        .toList();
    final now = DateTime.now();
    return lines.asMap().entries.map((entry) {
      final index = entry.key;
      final memory = entry.value;
      final id = _memoryId(index, memory);
      return Mem0MemoryItem(
        id: id,
        memory: memory,
        score: null,
        createdAt: now,
        updatedAt: now,
        topLevelCategories: const [],
        metadata: const {},
        userId: 'workspace',
        agentId: 'omnibot-workspace-memory',
      );
    }).toList();
  }

  static String _memoryId(int index, String memory) {
    final raw = base64Url.encode(utf8.encode('$index|$memory'));
    return raw.replaceAll('=', '');
  }

  static String? _replaceById(
    String content,
    String memoryId,
    String nextMemory,
  ) {
    final lines = content.split('\n');
    final bulletIndexes = <int>[];
    final bulletValues = <String>[];
    for (var i = 0; i < lines.length; i++) {
      final trimmed = lines[i].trim();
      if (!trimmed.startsWith('- ')) continue;
      final memory = trimmed.substring(2).trim();
      if (memory.isEmpty) continue;
      bulletIndexes.add(i);
      bulletValues.add(memory);
    }
    for (var i = 0; i < bulletValues.length; i++) {
      final id = _memoryId(i, bulletValues[i]);
      if (id == memoryId) {
        lines[bulletIndexes[i]] = '- $nextMemory';
        return lines.join('\n');
      }
    }
    return null;
  }

  static String? _deleteById(String content, String memoryId) {
    final lines = content.split('\n');
    final bulletIndexes = <int>[];
    final bulletValues = <String>[];
    for (var i = 0; i < lines.length; i++) {
      final trimmed = lines[i].trim();
      if (!trimmed.startsWith('- ')) continue;
      final memory = trimmed.substring(2).trim();
      if (memory.isEmpty) continue;
      bulletIndexes.add(i);
      bulletValues.add(memory);
    }
    for (var i = 0; i < bulletValues.length; i++) {
      final id = _memoryId(i, bulletValues[i]);
      if (id == memoryId) {
        lines.removeAt(bulletIndexes[i]);
        return lines.join('\n');
      }
    }
    return null;
  }
}
