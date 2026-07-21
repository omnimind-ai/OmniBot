import 'package:flutter/services.dart';

class AlpineFileEntry {
  const AlpineFileEntry({
    required this.path,
    required this.name,
    required this.isDirectory,
    required this.isFile,
    required this.isLink,
    required this.size,
    required this.modifiedAt,
    required this.mode,
    required this.readable,
    required this.writable,
    required this.linkTarget,
    required this.pathToken,
    required this.nameToken,
    required this.hasValidUtf8Path,
  });

  final String path;
  final String name;
  final bool isDirectory;
  final bool isFile;
  final bool isLink;
  final int size;
  final int modifiedAt;
  final String mode;
  final bool readable;
  final bool writable;
  final String linkTarget;
  final String pathToken;
  final String nameToken;
  final bool hasValidUtf8Path;

  String get entryId => hasValidUtf8Path ? path : pathToken;

  factory AlpineFileEntry.fromMap(Map<dynamic, dynamic> map) {
    return AlpineFileEntry(
      path: (map['path'] ?? '').toString(),
      name: (map['name'] ?? '').toString(),
      isDirectory: map['isDirectory'] == true,
      isFile: map['isFile'] == true,
      isLink: map['isLink'] == true,
      size: (map['size'] as num?)?.toInt() ?? 0,
      modifiedAt: (map['modifiedAt'] as num?)?.toInt() ?? 0,
      mode: (map['mode'] ?? '').toString(),
      readable: map['readable'] == true,
      writable: map['writable'] == true,
      linkTarget: (map['linkTarget'] ?? '').toString(),
      pathToken: (map['pathToken'] ?? map['path'] ?? '').toString(),
      nameToken: (map['nameToken'] ?? '').toString(),
      hasValidUtf8Path: map['hasValidUtf8Path'] != false,
    );
  }
}

class AlpineFileContent {
  const AlpineFileContent({
    required this.path,
    required this.content,
    required this.size,
    required this.truncated,
    required this.editable,
    required this.binary,
    required this.isLink,
  });

  final String path;
  final String content;
  final int size;
  final bool truncated;
  final bool editable;
  final bool binary;
  final bool isLink;

  factory AlpineFileContent.fromMap(Map<dynamic, dynamic> map) {
    return AlpineFileContent(
      path: (map['path'] ?? '').toString(),
      content: (map['content'] ?? '').toString(),
      size: (map['size'] as num?)?.toInt() ?? 0,
      truncated: map['truncated'] == true,
      editable: map['editable'] == true,
      binary: map['binary'] == true,
      isLink: map['isLink'] == true,
    );
  }
}

class AlpineFileSystemService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/AlpineFileSystem',
  );

  static Future<List<AlpineFileEntry>> list(String path) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'list',
      <String, dynamic>{'path': path},
    );
    final entries = result?['entries'];
    if (entries is! List) return const <AlpineFileEntry>[];
    final parsed = entries
        .whereType<Map>()
        .map(AlpineFileEntry.fromMap)
        .toList(growable: false);
    parsed.sort((a, b) {
      if (a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
      return a.name.toLowerCase().compareTo(b.name.toLowerCase());
    });
    return parsed;
  }

  static Future<AlpineFileContent> read(
    String path, {
    int maxBytes = 1024 * 1024,
  }) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'read',
      <String, dynamic>{'path': path, 'maxBytes': maxBytes},
    );
    return AlpineFileContent.fromMap(result ?? const <dynamic, dynamic>{});
  }

  static Future<void> write(String path, String content) async {
    await _channel.invokeMethod<Object?>('write', <String, dynamic>{
      'path': path,
      'content': content,
    });
  }

  static Future<void> createDirectory(String path) async {
    await _channel.invokeMethod<Object?>('createDirectory', <String, dynamic>{
      'path': path,
    });
  }

  static Future<void> createFile(String path) async {
    await _channel.invokeMethod<Object?>('createFile', <String, dynamic>{
      'path': path,
    });
  }

  static Future<void> move(String sourcePath, String targetPath) async {
    await _channel.invokeMethod<Object?>('move', <String, dynamic>{
      'sourcePath': sourcePath,
      'targetPath': targetPath,
    });
  }

  static Future<void> delete(String path) async {
    await _channel.invokeMethod<Object?>('delete', <String, dynamic>{
      'path': path,
    });
  }

  static String normalizePath(String value) {
    if (!value.startsWith('/')) {
      throw const FormatException('An absolute Alpine path is required.');
    }
    if (value.contains('\u0000') ||
        value.contains('\n') ||
        value.contains('\r')) {
      throw const FormatException('Invalid Alpine path.');
    }
    final parts = <String>[];
    for (final segment in value.split('/')) {
      if (segment.isEmpty || segment == '.') continue;
      if (segment == '..') {
        if (parts.isNotEmpty) parts.removeLast();
      } else {
        parts.add(segment);
      }
    }
    return parts.isEmpty ? '/' : '/${parts.join('/')}';
  }

  static String joinPath(String parent, String name) {
    final normalizedParent = normalizePath(parent);
    if (!isValidEntryName(name)) {
      throw const FormatException('Invalid Alpine file name.');
    }
    return normalizedParent == '/' ? '/$name' : '$normalizedParent/$name';
  }

  static bool isValidEntryName(String value) {
    return value.isNotEmpty &&
        value != '.' &&
        value != '..' &&
        !value.contains('/') &&
        !value.contains('\u0000') &&
        !value.contains('\n') &&
        !value.contains('\r');
  }
}
