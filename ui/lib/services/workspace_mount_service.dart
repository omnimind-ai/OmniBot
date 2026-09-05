import 'dart:io';

import 'package:ui/services/omnibot_resource_service.dart';

class WorkspaceMountEntry {
  final String alias;
  final String linkPath;
  final String sourcePath;
  final String shellPath;
  final bool sourceExists;
  final bool sourceIsDirectory;

  const WorkspaceMountEntry({
    required this.alias,
    required this.linkPath,
    required this.sourcePath,
    required this.shellPath,
    required this.sourceExists,
    required this.sourceIsDirectory,
  });

  bool get isBroken => !sourceExists || !sourceIsDirectory;
}

class WorkspaceMountService {
  static Future<List<WorkspaceMountEntry>> listMountedDirectories() async {
    await OmnibotResourceService.ensureWorkspacePathsLoaded();
    return listMountedDirectoriesSync(
      rootPath: OmnibotResourceService.rootPath,
    );
  }

  static List<WorkspaceMountEntry> listMountedDirectoriesSync({
    required String rootPath,
  }) {
    final rootDirectory = Directory(_normalizePath(rootPath));
    if (!rootDirectory.existsSync()) {
      return const <WorkspaceMountEntry>[];
    }
    final entries = <WorkspaceMountEntry>[];
    for (final entity in rootDirectory.listSync(followLinks: false)) {
      final mountEntry = describeMountEntrySync(
        entity.path,
        rootPath: rootDirectory.path,
      );
      if (mountEntry != null) {
        entries.add(mountEntry);
      }
    }
    entries.sort(
      (a, b) => a.alias.toLowerCase().compareTo(b.alias.toLowerCase()),
    );
    return entries;
  }

  static WorkspaceMountEntry? describeMountEntrySync(
    String entryPath, {
    required String rootPath,
  }) {
    final normalizedRoot = _normalizePath(rootPath);
    final normalizedEntryPath = _normalizePath(entryPath);
    final parentPath = _normalizePath(File(normalizedEntryPath).parent.path);
    if (parentPath != normalizedRoot) {
      return null;
    }
    if (FileSystemEntity.typeSync(normalizedEntryPath, followLinks: false) !=
        FileSystemEntityType.link) {
      return null;
    }
    final rawTarget = _readLinkTargetSync(normalizedEntryPath);
    if (rawTarget == null || rawTarget.trim().isEmpty) {
      return null;
    }
    final resolvedTarget = _resolveLinkTargetPath(
      linkPath: normalizedEntryPath,
      rawTarget: rawTarget,
    );
    final sourceType = FileSystemEntity.typeSync(resolvedTarget);
    return WorkspaceMountEntry(
      alias: _basename(normalizedEntryPath),
      linkPath: normalizedEntryPath,
      sourcePath: resolvedTarget,
      shellPath: '/workspace/${_basename(normalizedEntryPath)}',
      sourceExists: sourceType != FileSystemEntityType.notFound,
      sourceIsDirectory: sourceType == FileSystemEntityType.directory,
    );
  }

  static Future<WorkspaceMountEntry?> describeMountEntry(
    String entryPath, {
    required String rootPath,
  }) async {
    final normalizedRoot = _normalizePath(rootPath);
    final normalizedEntryPath = _normalizePath(entryPath);
    final parentPath = _normalizePath(File(normalizedEntryPath).parent.path);
    if (parentPath != normalizedRoot) {
      return null;
    }
    if (await FileSystemEntity.type(normalizedEntryPath, followLinks: false) !=
        FileSystemEntityType.link) {
      return null;
    }
    final String rawTarget;
    try {
      rawTarget = await Link(normalizedEntryPath).target();
    } on FileSystemException {
      return null;
    }
    if (rawTarget.trim().isEmpty) {
      return null;
    }
    final resolvedTarget = _resolveLinkTargetPath(
      linkPath: normalizedEntryPath,
      rawTarget: rawTarget,
    );
    final sourceType = await FileSystemEntity.type(resolvedTarget);
    return WorkspaceMountEntry(
      alias: _basename(normalizedEntryPath),
      linkPath: normalizedEntryPath,
      sourcePath: resolvedTarget,
      shellPath: '/workspace/${_basename(normalizedEntryPath)}',
      sourceExists: sourceType != FileSystemEntityType.notFound,
      sourceIsDirectory: sourceType == FileSystemEntityType.directory,
    );
  }

  static Future<WorkspaceMountEntry> mountDirectory({
    required String sourcePath,
    required String alias,
  }) async {
    await OmnibotResourceService.ensureWorkspacePathsLoaded();
    return mountDirectorySync(
      sourcePath: sourcePath,
      alias: alias,
      rootPath: OmnibotResourceService.rootPath,
    );
  }

  static WorkspaceMountEntry mountDirectorySync({
    required String sourcePath,
    required String alias,
    required String rootPath,
  }) {
    final normalizedRoot = _normalizePath(rootPath);
    final normalizedAlias = alias.trim();
    final validationError = validateAlias(normalizedAlias);
    if (validationError != null) {
      throw ArgumentError(validationError);
    }

    final sourceDirectory = Directory(_normalizePath(sourcePath));
    if (!sourceDirectory.existsSync()) {
      throw ArgumentError('目录不存在：${sourceDirectory.path}');
    }
    if (FileSystemEntity.typeSync(sourceDirectory.path) !=
        FileSystemEntityType.directory) {
      throw ArgumentError('仅支持挂载文件夹：${sourceDirectory.path}');
    }

    final canonicalRoot = _resolveExistingDirectoryPath(normalizedRoot);
    final canonicalSource = _resolveExistingDirectoryPath(sourceDirectory.path);
    if (canonicalSource == canonicalRoot ||
        canonicalSource.startsWith('$canonicalRoot/')) {
      throw ArgumentError('不能把 workspace 目录自身再次挂载到 /workspace。');
    }
    if (canonicalRoot.startsWith('$canonicalSource/')) {
      throw ArgumentError('不能把 workspace 的父级目录直接挂载进 /workspace。');
    }

    final linkPath = _normalizePath('$canonicalRoot/$normalizedAlias');
    final existingType = FileSystemEntity.typeSync(
      linkPath,
      followLinks: false,
    );
    if (existingType != FileSystemEntityType.notFound) {
      final existingMount = describeMountEntrySync(
        linkPath,
        rootPath: canonicalRoot,
      );
      if (existingMount != null &&
          existingMount.sourcePath == canonicalSource) {
        return existingMount;
      }
      throw ArgumentError('/workspace/$normalizedAlias 已存在，请更换挂载名称。');
    }

    final link = Link(linkPath);
    link.createSync(canonicalSource, recursive: true);
    return describeMountEntrySync(linkPath, rootPath: canonicalRoot) ??
        WorkspaceMountEntry(
          alias: normalizedAlias,
          linkPath: linkPath,
          sourcePath: canonicalSource,
          shellPath: '/workspace/$normalizedAlias',
          sourceExists: true,
          sourceIsDirectory: true,
        );
  }

  static Future<void> unmountDirectory(WorkspaceMountEntry entry) async {
    await OmnibotResourceService.ensureWorkspacePathsLoaded();
    unmountDirectorySync(entry.linkPath);
  }

  static void unmountDirectorySync(String linkPath) {
    final normalizedLinkPath = _normalizePath(linkPath);
    if (FileSystemEntity.typeSync(normalizedLinkPath, followLinks: false) !=
        FileSystemEntityType.link) {
      throw ArgumentError('挂载入口不存在：$normalizedLinkPath');
    }
    Link(normalizedLinkPath).deleteSync();
  }

  static String suggestedAlias(String sourcePath) {
    final name = _basename(_normalizePath(sourcePath));
    if (name.isEmpty) {
      return 'mount';
    }
    return name;
  }

  static String suggestUniqueAlias(
    String sourcePath, {
    required String rootPath,
  }) {
    final baseAlias = suggestedAlias(sourcePath);
    var candidate = baseAlias;
    var index = 2;
    while (FileSystemEntity.typeSync(
          '${_normalizePath(rootPath)}/$candidate',
          followLinks: false,
        ) !=
        FileSystemEntityType.notFound) {
      candidate = '$baseAlias-$index';
      index += 1;
    }
    return candidate;
  }

  static String? validateAlias(String alias) {
    final trimmed = alias.trim();
    if (trimmed.isEmpty) {
      return '挂载名称不能为空';
    }
    if (trimmed == '.' || trimmed == '..') {
      return '挂载名称不能为 . 或 ..';
    }
    if (trimmed.contains('/')) {
      return '挂载名称不能包含 /';
    }
    if (trimmed.contains('\\')) {
      return '挂载名称不能包含 \\';
    }
    if (trimmed.contains('\u0000')) {
      return '挂载名称包含非法字符';
    }
    if (trimmed == '.omnibot' || trimmed.startsWith('.omnibot/')) {
      return '挂载名称不能与内部目录冲突';
    }
    return null;
  }

  static String? _readLinkTargetSync(String linkPath) {
    try {
      return Link(linkPath).targetSync();
    } catch (_) {
      return null;
    }
  }

  static String _resolveLinkTargetPath({
    required String linkPath,
    required String rawTarget,
  }) {
    final trimmedTarget = rawTarget.trim();
    if (trimmedTarget.startsWith('/')) {
      return _normalizePath(trimmedTarget);
    }
    final parentPath = _normalizePath(File(linkPath).parent.path);
    return _normalizePath('$parentPath/$trimmedTarget');
  }

  static String _resolveExistingDirectoryPath(String path) {
    return Directory(path).resolveSymbolicLinksSync();
  }

  static String _normalizePath(String path) {
    final trimmed = path.trim();
    if (trimmed.isEmpty) {
      return trimmed;
    }
    if (trimmed.length > 1 && trimmed.endsWith('/')) {
      return trimmed.substring(0, trimmed.length - 1);
    }
    return trimmed;
  }

  static String _basename(String path) {
    final normalizedPath = _normalizePath(path);
    final lastSlashIndex = normalizedPath.lastIndexOf('/');
    if (lastSlashIndex < 0) {
      return normalizedPath;
    }
    return normalizedPath.substring(lastSlashIndex + 1);
  }
}
