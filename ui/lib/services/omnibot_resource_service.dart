import 'dart:io';

import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher_string.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/services/special_permission.dart';

class OmnibotResourceMetadata {
  final String? uri;
  final String path;
  final String shellPath;
  final String title;
  final String previewKind;
  final String mimeType;
  final bool exists;
  final bool isDirectory;
  final String embedKind;
  final bool inlineRenderable;

  const OmnibotResourceMetadata({
    required this.uri,
    required this.path,
    required this.shellPath,
    required this.title,
    required this.previewKind,
    required this.mimeType,
    required this.exists,
    required this.isDirectory,
    required this.embedKind,
    required this.inlineRenderable,
  });
}

class OmnibotWorkspacePaths {
  final String rootPath;
  final String shellRootPath;
  final String internalRootPath;

  const OmnibotWorkspacePaths({
    required this.rootPath,
    required this.shellRootPath,
    required this.internalRootPath,
  });

  factory OmnibotWorkspacePaths.fromMap(Map<dynamic, dynamic> map) {
    final rootPath =
        (map['rootPath'] as String?)?.trim() ??
        '/data/user/0/cn.com.omnimind.bot/workspace';
    final shellRootPath =
        (map['shellRootPath'] as String?)?.trim() ?? '/workspace';
    final internalRootPath =
        (map['internalRootPath'] as String?)?.trim() ?? '$rootPath/.omnibot';
    return OmnibotWorkspacePaths(
      rootPath: rootPath,
      shellRootPath: shellRootPath,
      internalRootPath: internalRootPath,
    );
  }
}

class OmnibotResourceService {
  static const MethodChannel _fileChannel = MethodChannel(
    'cn.com.omnimind.bot/file_save',
  );
  static const List<String> _publicStoragePathPrefixes = <String>[
    '/storage',
    '/sdcard',
  ];
  static const OmnibotWorkspacePaths _defaultWorkspacePaths =
      OmnibotWorkspacePaths(
        rootPath: '/data/user/0/cn.com.omnimind.bot/workspace',
        shellRootPath: '/workspace',
        internalRootPath: '/data/user/0/cn.com.omnimind.bot/workspace/.omnibot',
      );

  static OmnibotWorkspacePaths _workspacePaths = _defaultWorkspacePaths;
  static Future<OmnibotWorkspacePaths>? _workspacePathsFuture;

  static String get rootPath => _workspacePaths.rootPath;
  static String get shellRootPath => _workspacePaths.shellRootPath;
  static String get internalRootPath => _workspacePaths.internalRootPath;

  static Future<OmnibotWorkspacePaths> ensureWorkspacePathsLoaded({
    bool forceRefresh = false,
  }) {
    if (forceRefresh) {
      _workspacePathsFuture = null;
    }
    final existing = _workspacePathsFuture;
    if (existing != null) {
      return existing;
    }
    final future = _loadWorkspacePaths();
    _workspacePathsFuture = future;
    return future;
  }

  static Future<OmnibotWorkspacePaths> _loadWorkspacePaths() async {
    try {
      final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
        'getWorkspacePathSnapshot',
      );
      if (result != null) {
        _workspacePaths = OmnibotWorkspacePaths.fromMap(result);
      } else {
        _workspacePathsFuture = null;
      }
    } catch (_) {
      _workspacePathsFuture = null;
    }
    return _workspacePaths;
  }

  static void debugSetWorkspacePaths(OmnibotWorkspacePaths paths) {
    _workspacePaths = paths;
    _workspacePathsFuture = Future<OmnibotWorkspacePaths>.value(paths);
  }

  static void debugResetWorkspacePaths() {
    _workspacePaths = _defaultWorkspacePaths;
    _workspacePathsFuture = null;
  }

  static Future<bool> handleLinkTap(String href) async {
    if (href.startsWith('omnibot://')) {
      await openUri(href);
      return true;
    }
    if (href.startsWith('http://') || href.startsWith('https://')) {
      await launchUrlString(href, mode: LaunchMode.externalApplication);
      return true;
    }
    return false;
  }

  static Future<void> openUri(String uri) async {
    await ensureWorkspacePathsLoaded();
    final metadata = resolveUri(uri);
    if (metadata == null) return;
    if (!await _ensureResourceAccess(path: metadata.path, uri: uri)) {
      return;
    }
    if (metadata.isDirectory) {
      openWorkspace(
        absolutePath: metadata.path,
        shellPath: metadata.shellPath,
        uri: uri,
      );
      return;
    }
    if (uri.startsWith('omnibot://workspace/') && metadata.exists == false) {
      openWorkspace(absolutePath: metadata.path, shellPath: metadata.shellPath);
      return;
    }
    await openFilePath(
      metadata.path,
      uri: uri,
      title: metadata.title,
      previewKind: metadata.previewKind,
      mimeType: metadata.mimeType,
      shellPath: metadata.shellPath,
    );
  }

  static Future<void> openFilePath(
    String path, {
    String? uri,
    String? title,
    String? previewKind,
    String? mimeType,
    String? shellPath,
    bool startInEditMode = false,
  }) async {
    await ensureWorkspacePathsLoaded();
    if (!await _ensureResourceAccess(path: path, uri: uri)) {
      return;
    }
    final metadata = describePath(
      path,
      uri: uri,
      shellPath: shellPath,
      title: title,
      previewKind: previewKind,
      mimeType: mimeType,
    );

    GoRouterManager.push(
      '/home/omnibot_artifact_preview',
      extra: <String, dynamic>{
        'path': path,
        'uri': uri,
        'title': metadata.title,
        'previewKind': metadata.previewKind,
        'mimeType': metadata.mimeType,
        'shellPath': metadata.shellPath,
        'exists': metadata.exists,
        'startInEditMode': startInEditMode,
      },
    );
  }

  static Future<void> openWorkspace({
    String? workspaceId,
    String? absolutePath,
    String? shellPath,
    String? uri,
  }) async {
    await ensureWorkspacePathsLoaded();
    final path = absolutePath ?? resolveUriToPath(uri ?? '') ?? rootPath;
    final resolvedShellPath =
        shellPath ?? resolveUriToShellPath(uri ?? '') ?? shellRootPath;
    if (!await _ensureResourceAccess(path: path, uri: uri)) {
      return;
    }
    final effectiveWorkspaceId = path == rootPath ? null : workspaceId;
    GoRouterManager.push(
      '/home/omnibot_workspace',
      extra: <String, dynamic>{
        'workspacePath': path,
        'workspaceId': effectiveWorkspaceId,
        'workspaceShellPath': resolvedShellPath,
      },
    );
  }

  static Future<String?> saveToLocal({
    required String sourcePath,
    required String fileName,
    required String mimeType,
  }) async {
    await ensureWorkspacePathsLoaded();
    if (!await _ensureResourceAccess(path: sourcePath)) {
      return null;
    }
    return _fileChannel.invokeMethod<String>('saveFileWithSystemDialog', {
      'sourcePath': sourcePath,
      'fileName': fileName,
      'mimeType': mimeType,
    });
  }

  static Future<bool> openWithSystem({
    required String sourcePath,
    required String mimeType,
  }) async {
    await ensureWorkspacePathsLoaded();
    if (!await _ensureResourceAccess(path: sourcePath)) {
      return false;
    }
    final result = await _fileChannel.invokeMethod<dynamic>('openFile', {
      'sourcePath': sourcePath,
      'mimeType': mimeType,
    });
    return result == true;
  }

  /// Opens a local artifact in the device's default web browser.
  ///
  /// The native side stages the file behind a temporary, read-only
  /// FileProvider URI and grants the browser access to it. Passing the raw
  /// app-private path to a browser would work only inside the app process and
  /// would fail for workspace files.
  static Future<bool> openInBrowser({
    required String sourcePath,
    required String mimeType,
  }) async {
    await ensureWorkspacePathsLoaded();
    if (!await _ensureResourceAccess(path: sourcePath)) {
      return false;
    }
    final result = await _fileChannel.invokeMethod<dynamic>('openInBrowser', {
      'sourcePath': sourcePath,
      'mimeType': mimeType,
    });
    return result == true;
  }

  static Future<bool> shareFile({
    required String sourcePath,
    required String fileName,
    required String mimeType,
  }) async {
    await ensureWorkspacePathsLoaded();
    if (!await _ensureResourceAccess(path: sourcePath)) {
      return false;
    }
    final result = await _fileChannel.invokeMethod<dynamic>('shareFile', {
      'sourcePath': sourcePath,
      'fileName': fileName,
      'mimeType': mimeType,
    });
    return result == true;
  }

  static Future<bool> shareText(String text) async {
    if (text.trim().isEmpty) {
      return false;
    }
    final result = await _fileChannel.invokeMethod<dynamic>('shareText', {
      'text': text,
    });
    return result == true;
  }

  static Future<bool> ensureResourceAccess({String? path, String? uri}) {
    return _ensureResourceAccess(path: path, uri: uri);
  }

  static Future<bool> _ensureResourceAccess({String? path, String? uri}) async {
    final requiredPermissionId = _requiredPermissionIdForPathOrUri(
      path: path,
      uri: uri,
    );
    if (requiredPermissionId == null) {
      return true;
    }

    final granted = switch (requiredPermissionId) {
      kPublicStoragePermissionId => await isPublicStorageAccessGranted(),
      _ => await isWorkspaceStorageAccessGranted(),
    };
    if (granted) {
      return true;
    }
    final result = await GoRouterManager.pushForResult<bool>(
      '/home/authorize',
      extra: AuthorizePageArgs(
        requiredPermissionIds: <String>[requiredPermissionId],
      ),
    );
    if (result == true) {
      return switch (requiredPermissionId) {
        kPublicStoragePermissionId => await isPublicStorageAccessGranted(),
        _ => await isWorkspaceStorageAccessGranted(),
      };
    }
    return false;
  }

  static String? _requiredPermissionIdForPathOrUri({
    String? path,
    String? uri,
  }) {
    final resolvedPath = path?.trim().isNotEmpty == true
        ? path!.trim()
        : resolveUriToPath(uri ?? '');
    if (resolvedPath == null || resolvedPath.isEmpty) {
      return null;
    }
    if (_isPublicAndroidPath(resolvedPath)) {
      return kPublicStoragePermissionId;
    }
    return kWorkspaceStoragePermissionId;
  }

  static OmnibotResourceMetadata? resolveUri(String uri) {
    final path = resolveUriToPath(uri);
    if (path == null) return null;
    return describePath(path, uri: uri, shellPath: resolveUriToShellPath(uri));
  }

  static OmnibotResourceMetadata describePath(
    String path, {
    String? uri,
    String? shellPath,
    String? title,
    String? previewKind,
    String? mimeType,
  }) {
    final resolvedShellPath =
        shellPath ?? shellPathForAndroidPath(path) ?? path;
    final isDirectory = _safeIsDirectory(path);
    final resolvedPreviewKind = isDirectory
        ? 'directory'
        : (previewKind ?? _guessPreviewKind(path));
    final resolvedMimeType = isDirectory
        ? 'inode/directory'
        : (mimeType ?? _guessMimeType(path));
    final derivedTitle = path.split(Platform.pathSeparator).last;
    final resolvedTitle =
        title ?? (derivedTitle.isEmpty ? 'workspace' : derivedTitle);
    final resolvedEmbedKind = switch (resolvedPreviewKind) {
      'image' => 'image',
      'audio' => 'audio',
      'video' => 'video',
      'pdf' => 'pdf',
      'html' => 'html',
      'office_word' || 'office_sheet' || 'office_slide' => 'office',
      _ => 'link',
    };
    return OmnibotResourceMetadata(
      uri: uri,
      path: path,
      shellPath: resolvedShellPath,
      title: resolvedTitle,
      previewKind: resolvedPreviewKind,
      mimeType: resolvedMimeType,
      exists: _safeExists(path),
      isDirectory: isDirectory,
      embedKind: resolvedEmbedKind,
      inlineRenderable: resolvedEmbedKind != 'link',
    );
  }

  static bool isOfficePreviewKind(String previewKind) {
    return previewKind == 'office_word' ||
        previewKind == 'office_sheet' ||
        previewKind == 'office_slide';
  }

  static String? resolveUriToPath(String uri) {
    if (!uri.startsWith('omnibot://')) return null;
    final parsed = Uri.tryParse(uri);
    final authority = parsed?.host;
    if (authority == null) return null;
    if (authority.isEmpty) {
      return _resolveAbsoluteOmnibotPath(parsed);
    }
    final segments = parsed?.pathSegments ?? const <String>[];
    final base = switch (authority) {
      'attachments' => '$internalRootPath/attachments',
      'workspace' => rootPath,
      'public' => '/storage',
      'storage' => '/storage',
      'sdcard' => '/sdcard',
      'shared' => '$internalRootPath/shared',
      'offloads' => '$internalRootPath/offloads',
      'browser' => '$internalRootPath/browser',
      'skills' => '$internalRootPath/skills',
      'memory' => '$internalRootPath/memory',
      _ => null,
    };
    if (base == null) return null;
    final normalizedSegments = segments
        .where((segment) => segment.isNotEmpty && segment != '..')
        .toList();
    if (normalizedSegments.isEmpty) return base;
    return '$base/${normalizedSegments.join('/')}';
  }

  static String? resolveUriToShellPath(String uri) {
    if (!uri.startsWith('omnibot://')) return null;
    final parsed = Uri.tryParse(uri);
    final authority = parsed?.host;
    if (authority == null) return null;
    if (authority.isEmpty) {
      return _resolveAbsoluteOmnibotShellPath(parsed);
    }
    final suffix = (parsed?.pathSegments ?? const <String>[])
        .where((segment) => segment.isNotEmpty && segment != '..')
        .join('/');
    final base = switch (authority) {
      'workspace' => shellRootPath,
      'public' => '/storage',
      'storage' => '/storage',
      'sdcard' => '/sdcard',
      _ => '$shellRootPath/.omnibot/$authority',
    };
    return suffix.isEmpty ? base : '$base/$suffix';
  }

  static String? shellPathForAndroidPath(String path) {
    if (_isPublicAndroidPath(path)) {
      return path;
    }
    if (path == rootPath) {
      return shellRootPath;
    }
    if (path.startsWith('$rootPath/')) {
      final relative = path.substring(rootPath.length + 1);
      return '$shellRootPath/$relative';
    }
    if (path == internalRootPath) {
      return '$shellRootPath/.omnibot';
    }
    if (path.startsWith('$internalRootPath/')) {
      final relative = path.substring(internalRootPath.length + 1);
      return '$shellRootPath/.omnibot/$relative';
    }
    return null;
  }

  static String? androidPathForShellPath(
    String shellPath, {
    OmnibotWorkspacePaths? workspacePaths,
  }) {
    final paths = workspacePaths ?? _workspacePaths;
    final normalized = shellPath.trim();
    if (normalized.isEmpty) {
      return null;
    }
    if (_isPublicShellPath(normalized)) {
      return normalized;
    }
    final internalShellRoot = '${paths.shellRootPath}/.omnibot';
    if (normalized == internalShellRoot) {
      return paths.internalRootPath;
    }
    if (normalized.startsWith('$internalShellRoot/')) {
      final relative = normalized.substring(internalShellRoot.length + 1);
      return '${paths.internalRootPath}/$relative';
    }
    if (normalized == paths.shellRootPath) {
      return paths.rootPath;
    }
    if (normalized.startsWith('${paths.shellRootPath}/')) {
      final relative = normalized.substring(paths.shellRootPath.length + 1);
      return '${paths.rootPath}/$relative';
    }
    return null;
  }

  static bool _safeExists(String path) {
    try {
      return FileSystemEntity.typeSync(path) != FileSystemEntityType.notFound;
    } catch (_) {
      return false;
    }
  }

  static bool _safeIsDirectory(String path) {
    try {
      return FileSystemEntity.typeSync(path) == FileSystemEntityType.directory;
    } catch (_) {
      return false;
    }
  }

  static String _guessPreviewKind(String path) {
    final lower = path.toLowerCase();
    if (_matchesAny(lower, const ['.png', '.jpg', '.jpeg', '.gif', '.webp'])) {
      return 'image';
    }
    if (_matchesAny(lower, const ['.docx', '.docm'])) {
      return 'office_word';
    }
    if (_matchesAny(lower, const ['.xlsx', '.xlsm'])) {
      return 'office_sheet';
    }
    if (_matchesAny(lower, const ['.pptx', '.pptm'])) {
      return 'office_slide';
    }
    if (_matchesAny(lower, const ['.html', '.htm'])) {
      return 'html';
    }
    if (lower.endsWith('.pdf')) {
      return 'pdf';
    }
    if (_matchesAny(lower, const ['.mp3', '.m4a', '.wav'])) {
      return 'audio';
    }
    if (_matchesAny(lower, const ['.mp4', '.mov'])) {
      return 'video';
    }
    if (_matchesAny(lower, const [
      '.json',
      '.jsonl',
      '.yaml',
      '.yml',
      '.xml',
    ])) {
      return 'code';
    }
    if (_matchesAny(lower, const [
      '.md',
      '.txt',
      '.log',
      '.kt',
      '.java',
      '.py',
      '.js',
      '.ts',
      '.css',
      '.sh',
      '.csv',
    ])) {
      return 'text';
    }
    return 'file';
  }

  static String _guessMimeType(String path) {
    final lower = path.toLowerCase();
    if (lower.endsWith('.md')) return 'text/markdown';
    if (lower.endsWith('.docx')) {
      return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
    }
    if (lower.endsWith('.docm')) {
      return 'application/vnd.ms-word.document.macroEnabled.12';
    }
    if (lower.endsWith('.xlsx')) {
      return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
    }
    if (lower.endsWith('.xlsm')) {
      return 'application/vnd.ms-excel.sheet.macroEnabled.12';
    }
    if (lower.endsWith('.pptx')) {
      return 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
    }
    if (lower.endsWith('.pptm')) {
      return 'application/vnd.ms-powerpoint.presentation.macroEnabled.12';
    }
    if (lower.endsWith('.json')) return 'application/json';
    if (lower.endsWith('.jsonl')) return 'application/x-ndjson';
    if (lower.endsWith('.yaml') || lower.endsWith('.yml')) {
      return 'application/yaml';
    }
    if (lower.endsWith('.xml')) return 'application/xml';
    if (lower.endsWith('.csv')) return 'text/csv';
    if (lower.endsWith('.html') || lower.endsWith('.htm')) return 'text/html';
    if (lower.endsWith('.pdf')) return 'application/pdf';
    if (lower.endsWith('.png')) return 'image/png';
    if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
    if (lower.endsWith('.gif')) return 'image/gif';
    if (lower.endsWith('.webp')) return 'image/webp';
    if (lower.endsWith('.mp3')) return 'audio/mpeg';
    if (lower.endsWith('.m4a')) return 'audio/mp4';
    if (lower.endsWith('.wav')) return 'audio/wav';
    if (lower.endsWith('.mp4')) return 'video/mp4';
    if (lower.endsWith('.mov')) return 'video/quicktime';
    if (_guessPreviewKind(path) == 'text' ||
        _guessPreviewKind(path) == 'code') {
      return 'text/plain';
    }
    return 'application/octet-stream';
  }

  static bool _matchesAny(String value, List<String> suffixes) {
    return suffixes.any(value.endsWith);
  }

  static String? _resolveAbsoluteOmnibotPath(Uri? parsed) {
    final absolutePath = _absoluteOmnibotPath(parsed);
    if (absolutePath == null) return null;
    final workspaceMapped = androidPathForShellPath(absolutePath);
    if (workspaceMapped != null) {
      return workspaceMapped;
    }
    if (_isPublicAndroidPath(absolutePath)) {
      return absolutePath;
    }
    return null;
  }

  static String? _resolveAbsoluteOmnibotShellPath(Uri? parsed) {
    final absolutePath = _absoluteOmnibotPath(parsed);
    if (absolutePath == null) return null;
    final workspaceMapped = androidPathForShellPath(absolutePath);
    if (workspaceMapped != null) {
      return absolutePath;
    }
    if (_isPublicShellPath(absolutePath)) {
      return absolutePath;
    }
    return null;
  }

  static String? _absoluteOmnibotPath(Uri? parsed) {
    final path = parsed?.path.trim() ?? '';
    if (path.isEmpty) {
      return null;
    }
    return path.startsWith('/') ? path : '/$path';
  }

  static bool _isPublicAndroidPath(String path) {
    final normalized = path.trim();
    return _publicStoragePathPrefixes.any(
      (prefix) => normalized == prefix || normalized.startsWith('$prefix/'),
    );
  }

  static bool _isPublicShellPath(String shellPath) {
    final normalized = shellPath.trim();
    return _publicStoragePathPrefixes.any(
      (prefix) => normalized == prefix || normalized.startsWith('$prefix/'),
    );
  }
}
