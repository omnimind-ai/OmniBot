import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/workspace_mount_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/app_background_widgets.dart';
import 'package:ui/widgets/image_preview_overlay.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';
import 'package:ui/widgets/omnibot_resource_widgets.dart';

class _WorkspaceBreadcrumbSegment {
  const _WorkspaceBreadcrumbSegment({
    required this.label,
    required this.path,
    required this.isCurrent,
    this.isFile = false,
  });

  final String label;
  final String path;
  final bool isCurrent;
  final bool isFile;
}

enum _WorkspaceEntryAction { edit, rename, delete, unmount }

class _WorkspaceDragPayload {
  const _WorkspaceDragPayload({
    required this.sourcePath,
    required this.isDirectory,
  });

  final String sourcePath;
  final bool isDirectory;
}

class OmnibotWorkspaceBrowser extends StatefulWidget {
  final String workspacePath;
  final String? workspaceShellPath;
  final bool enableSystemBackHandler;
  final bool translucentSurfaces;
  final ValueChanged<bool>? onCanGoUpChanged;
  final bool showBreadcrumbHeader;
  final bool showHeaderTitle;
  final bool enableInlineDirectoryExpansion;
  final bool inlineFilePreview;

  const OmnibotWorkspaceBrowser({
    super.key,
    required this.workspacePath,
    this.workspaceShellPath,
    this.enableSystemBackHandler = true,
    this.translucentSurfaces = false,
    this.onCanGoUpChanged,
    this.showBreadcrumbHeader = false,
    this.showHeaderTitle = true,
    this.enableInlineDirectoryExpansion = true,
    this.inlineFilePreview = false,
  });

  @override
  State<OmnibotWorkspaceBrowser> createState() =>
      OmnibotWorkspaceBrowserState();
}

class OmnibotWorkspaceBrowserState extends State<OmnibotWorkspaceBrowser> {
  static const String _folderIconAsset =
      'assets/home/workspace_folder_icon.svg';
  static const String _folderOpenIconAsset =
      'assets/home/workspace_folder_open_icon.svg';
  static const String _audioIconAsset = 'assets/home/workspace_audio_icon.svg';
  static const String _videoIconAsset = 'assets/home/workspace_video_icon.svg';
  static const String _fileIconAsset = 'assets/home/workspace_file_icon.svg';
  static const int _maxInlineExpansionDepth = 2;
  static const int _maxExpandedItemsBeforeScroll = 8;
  static const double _itemHeight = 40;
  static const double _itemCornerRadius = 10;
  static const double _indentStep = 16;
  static const Set<String> _audioExtensions = <String>{
    '.mp3',
    '.m4a',
    '.wav',
    '.aac',
    '.ogg',
    '.flac',
  };
  static const Set<String> _videoExtensions = <String>{
    '.mp4',
    '.mov',
    '.m4v',
    '.avi',
    '.mkv',
    '.webm',
  };
  static const String _bulkSelectIconSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <rect width="18" height="18" x="3" y="3" rx="2"/>
</svg>
''';
  static const String _bulkSelectCheckedIconSvg = '''
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <rect width="18" height="18" x="3" y="3" rx="2"/>
  <path d="m9 12 2 2 4-4"/>
</svg>
''';

  late final Directory _rootDirectory;
  late Directory _directory;
  List<FileSystemEntity> _entries = const [];
  final Map<String, WorkspaceMountEntry> _mountEntries = {};
  final Set<String> _directoryLikePaths = <String>{};
  bool _directoryExists = true;
  bool _isLoadingDirectory = true;
  String? _directoryLoadError;
  int _directoryLoadGeneration = 0;
  final Set<String> _expandedDirectoryPaths = <String>{};
  final Map<String, List<FileSystemEntity>> _directoryChildrenCache =
      <String, List<FileSystemEntity>>{};
  final Set<String> _selectedEntryPaths = <String>{};
  final Set<String> _excludedEntryPaths = <String>{};
  String? _dragHoverTargetPath;
  OmnibotResourceMetadata? _selectedFileMetadata;
  final GlobalKey<_WorkspaceInlineFilePreviewState> _inlinePreviewKey =
      GlobalKey<_WorkspaceInlineFilePreviewState>();
  bool _isBulkSelectionMode = false;

  bool get _isEnglish => Localizations.localeOf(context).languageCode == 'en';

  Color _surfaceColor({double opacity = 0.8}) {
    return backgroundSurfaceColor(
      translucent: widget.translucentSurfaces,
      baseColor: context.omniPalette.surfacePrimary,
      opacity: opacity,
    );
  }

  Color _secondarySurfaceColor({double opacity = 0.64}) {
    final palette = context.omniPalette;
    return widget.translucentSurfaces
        ? palette.surfaceSecondary.withValues(alpha: opacity)
        : palette.surfaceSecondary;
  }

  @override
  void initState() {
    super.initState();
    _rootDirectory = Directory(widget.workspacePath);
    _directory = _rootDirectory;
    _notifyCanGoUpChanged();
    _refresh();
  }

  void _notifyCanGoUpChanged() {
    final callback = widget.onCanGoUpChanged;
    if (callback == null) return;
    final value = canGoUp;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      callback(value);
    });
  }

  Future<void> _refresh() async {
    final generation = ++_directoryLoadGeneration;
    final directory = _directory;
    final expandedPaths = _expandedDirectoryPaths.toList();
    bool isCurrent() =>
        mounted &&
        generation == _directoryLoadGeneration &&
        directory.path == _directory.path;
    setState(() {
      _isLoadingDirectory = true;
      _directoryLoadError = null;
    });
    try {
      final exists = await directory.exists();
      final root = exists
          ? await _readSortedEntries(directory)
          : (entries: <FileSystemEntity>[], directories: <String>{});
      final nextEntries = root.entries;
      final mounts = <String, WorkspaceMountEntry>{};
      for (final entry in nextEntries.whereType<Link>()) {
        final mount = await WorkspaceMountService.describeMountEntry(
          entry.path,
          rootPath: _rootDirectory.path,
        );
        if (mount != null) mounts[entry.path] = mount;
      }
      final directoryPaths = {...root.directories};
      final nextChildrenCache = <String, List<FileSystemEntity>>{};
      for (final path in expandedPaths) {
        if (!isCurrent()) return;
        if (!_isDescendantOfCurrentDirectory(path)) continue;
        final child = Directory(path);
        if (await child.exists()) {
          final loaded = await _readSortedEntries(child);
          nextChildrenCache[path] = loaded.entries;
          directoryPaths.addAll(loaded.directories);
        }
      }
      if (!isCurrent()) return;
      final previousCanGoUp = canGoUp;
      final selectedPaths = <String>{};
      final excludedPaths = <String>{};
      for (final pair in [
        (_selectedEntryPaths.toList(), selectedPaths),
        (_excludedEntryPaths.toList(), excludedPaths),
      ]) {
        for (final path in pair.$1) {
          if (_isDescendantOfCurrentDirectory(path) &&
              await FileSystemEntity.type(path) !=
                  FileSystemEntityType.notFound) {
            pair.$2.add(path);
          }
        }
      }
      if (!isCurrent()) return;
      final selected = _selectedFileMetadata;
      final selectedExists =
          selected != null && await File(selected.path).exists();
      if (!isCurrent()) return;
      setState(() {
        _directoryExists = exists;
        _isLoadingDirectory = false;
        _entries = nextEntries;
        _mountEntries
          ..clear()
          ..addAll(mounts);
        _directoryLikePaths
          ..clear()
          ..addAll(directoryPaths);
        _expandedDirectoryPaths
          ..clear()
          ..addAll(nextChildrenCache.keys);
        _directoryChildrenCache
          ..clear()
          ..addAll(nextChildrenCache);
        _selectedEntryPaths
          ..clear()
          ..addAll(selectedPaths);
        _excludedEntryPaths
          ..clear()
          ..addAll(excludedPaths);
        if (!selectedExists) _selectedFileMetadata = null;
      });
      if (previousCanGoUp != canGoUp) _notifyCanGoUpChanged();
    } on FileSystemException catch (error) {
      if (!isCurrent()) return;
      setState(() {
        _isLoadingDirectory = false;
        _directoryLoadError = error.message;
      });
    }
  }

  Future<({List<FileSystemEntity> entries, Set<String> directories})>
  _readSortedEntries(Directory directory) async {
    final entries = await directory.list(followLinks: false).toList();
    final directoryPaths = <String>{};
    for (final entry in entries) {
      if (entry is Directory ||
          (entry is Link &&
              await FileSystemEntity.type(entry.path) ==
                  FileSystemEntityType.directory)) {
        directoryPaths.add(entry.path);
      }
    }
    entries.sort((a, b) {
      final typeOrder = (directoryPaths.contains(a.path) ? 0 : 1).compareTo(
        directoryPaths.contains(b.path) ? 0 : 1,
      );
      return typeOrder != 0
          ? typeOrder
          : a.path.toLowerCase().compareTo(b.path.toLowerCase());
    });
    return (entries: entries, directories: directoryPaths);
  }

  bool get _isPreviewingFile =>
      widget.inlineFilePreview && _selectedFileMetadata != null;

  bool get canGoUp =>
      _isPreviewingFile ||
      _isBulkSelectionMode ||
      _directory.path != _rootDirectory.path;

  void openParentDirectory() {
    unawaited(_handleOpenParentDirectory());
  }

  void _openDirectory(Directory directory) {
    setState(() {
      _directory = directory;
      _entries = const [];
      _expandedDirectoryPaths.clear();
      _directoryChildrenCache.clear();
      _selectedEntryPaths.clear();
      _excludedEntryPaths.clear();
      _selectedFileMetadata = null;
      _isBulkSelectionMode = false;
    });
    _notifyCanGoUpChanged();
    _refresh();
  }

  void _openDirectoryPath(String path) {
    final normalized = _normalizePath(path);
    if (!_isInsideWorkspace(normalized)) return;
    if (_directory.path == normalized && !_isPreviewingFile) return;
    _openDirectory(Directory(normalized));
  }

  void _openInlineFilePreview(
    FileSystemEntity entry, {
    String? currentShellPath,
  }) {
    final name = _entryNameFromPath(entry.path);
    final shellPath =
        OmnibotResourceService.shellPathForAndroidPath(entry.path) ??
        (currentShellPath == null ? null : '$currentShellPath/$name');
    setState(() {
      _selectedEntryPaths.clear();
      _excludedEntryPaths.clear();
      _selectedFileMetadata = OmnibotResourceService.describePath(
        entry.path,
        title: name,
        shellPath: shellPath,
      );
      _isBulkSelectionMode = false;
    });
    _notifyCanGoUpChanged();
  }

  Future<bool> _confirmDiscardPreviewChangesIfNeeded() async {
    final previewState = _inlinePreviewKey.currentState;
    if (previewState == null) {
      return true;
    }
    return previewState.confirmDiscardIfNeeded();
  }

  Future<void> _handleOpenParentDirectory() async {
    if (_isPreviewingFile) {
      final shouldClose = await _confirmDiscardPreviewChangesIfNeeded();
      if (!shouldClose || !mounted) return;
      setState(() {
        _selectedFileMetadata = null;
      });
      _notifyCanGoUpChanged();
      return;
    }
    if (_isBulkSelectionMode) {
      setState(() {
        _isBulkSelectionMode = false;
        _selectedEntryPaths.clear();
        _excludedEntryPaths.clear();
      });
      _notifyCanGoUpChanged();
      return;
    }
    if (!canGoUp) return;
    setState(() {
      _directory = _directory.parent;
      _expandedDirectoryPaths.clear();
      _directoryChildrenCache.clear();
      _selectedEntryPaths.clear();
      _excludedEntryPaths.clear();
      _selectedFileMetadata = null;
      _isBulkSelectionMode = false;
    });
    _notifyCanGoUpChanged();
    _refresh();
  }

  Future<void> _handleBreadcrumbTap(_WorkspaceBreadcrumbSegment segment) async {
    if (segment.isCurrent || segment.isFile) return;
    final shouldNavigate = await _confirmDiscardPreviewChangesIfNeeded();
    if (!shouldNavigate || !mounted) return;
    _openDirectoryPath(segment.path);
  }

  String get _rootBreadcrumbLabel {
    final shellRoot = (widget.workspaceShellPath ?? '').trim();
    if (shellRoot.isNotEmpty) {
      return shellRoot;
    }
    return _normalizePath(_rootDirectory.path);
  }

  List<_WorkspaceBreadcrumbSegment> get _workspaceBreadcrumbs {
    final targetMetadata = _selectedFileMetadata;
    final targetPath = _normalizePath(targetMetadata?.path ?? _directory.path);
    final isFile = targetMetadata != null;

    if (!_isInsideWorkspace(targetPath)) {
      return <_WorkspaceBreadcrumbSegment>[
        _WorkspaceBreadcrumbSegment(
          label: targetMetadata?.shellPath ?? targetPath,
          path: targetPath,
          isCurrent: true,
          isFile: isFile,
        ),
      ];
    }

    final segments = <_WorkspaceBreadcrumbSegment>[
      _WorkspaceBreadcrumbSegment(
        label: _rootBreadcrumbLabel,
        path: _normalizePath(_rootDirectory.path),
        isCurrent: targetPath == _normalizePath(_rootDirectory.path) && !isFile,
      ),
    ];

    if (targetPath == _normalizePath(_rootDirectory.path) && !isFile) {
      return segments;
    }

    final relative = targetPath.substring(
      _normalizePath(_rootDirectory.path).length,
    );
    final parts = relative
        .split('/')
        .where((segment) => segment.trim().isNotEmpty)
        .toList(growable: false);
    var runningPath = _normalizePath(_rootDirectory.path);
    for (var index = 0; index < parts.length; index++) {
      final part = parts[index];
      runningPath = '$runningPath/$part';
      final isCurrent = runningPath == targetPath;
      segments.add(
        _WorkspaceBreadcrumbSegment(
          label: part,
          path: runningPath,
          isCurrent: isCurrent,
          isFile: isCurrent && isFile,
        ),
      );
    }
    return segments;
  }

  void _toggleBulkSelectionMode() {
    setState(() {
      _isBulkSelectionMode = !_isBulkSelectionMode;
      _selectedEntryPaths.clear();
      _excludedEntryPaths.clear();
    });
  }

  bool _isPathSelfOrDescendantOf(String path, String ancestorPath) {
    final normalizedPath = _normalizePath(path);
    final normalizedAncestorPath = _normalizePath(ancestorPath);
    return normalizedPath == normalizedAncestorPath ||
        normalizedPath.startsWith('$normalizedAncestorPath/');
  }

  Iterable<String> _selectionTrailForPath(
    String path, {
    bool includeSelf = true,
  }) sync* {
    final normalizedPath = _normalizePath(path);
    final scopeRoot = _normalizePath(_directory.path);
    if (!_isPathSelfOrDescendantOf(normalizedPath, scopeRoot)) {
      return;
    }

    final segments = <String>[];
    var cursor = normalizedPath;
    while (true) {
      segments.add(cursor);
      if (cursor == scopeRoot) {
        break;
      }
      final parentPath = _normalizePath(File(cursor).parent.path);
      if (!_isPathSelfOrDescendantOf(parentPath, scopeRoot)) {
        break;
      }
      cursor = parentPath;
    }

    for (var index = segments.length - 1; index >= 0; index--) {
      final candidate = segments[index];
      if (!includeSelf && candidate == normalizedPath) {
        continue;
      }
      yield candidate;
    }
  }

  bool _isPathEffectivelySelected(String path) {
    var selected = false;
    for (final candidate in _selectionTrailForPath(path)) {
      if (_selectedEntryPaths.contains(candidate)) {
        selected = true;
      }
      if (_excludedEntryPaths.contains(candidate)) {
        selected = false;
      }
    }
    return selected;
  }

  bool _isPathSelectedByAncestor(String path) {
    var selected = false;
    for (final candidate in _selectionTrailForPath(path, includeSelf: false)) {
      if (_selectedEntryPaths.contains(candidate)) {
        selected = true;
      }
      if (_excludedEntryPaths.contains(candidate)) {
        selected = false;
      }
    }
    return selected;
  }

  bool _hasSelectionDirectiveDescendant(Set<String> directives, String path) {
    final normalizedPath = _normalizePath(path);
    return directives.any((candidate) {
      final normalizedCandidate = _normalizePath(candidate);
      return normalizedCandidate != normalizedPath &&
          _isPathSelfOrDescendantOf(normalizedCandidate, normalizedPath);
    });
  }

  void _clearSelectionDirectivesWithin(String path) {
    final normalizedPath = _normalizePath(path);
    _selectedEntryPaths.removeWhere(
      (candidate) => _isPathSelfOrDescendantOf(candidate, normalizedPath),
    );
    _excludedEntryPaths.removeWhere(
      (candidate) => _isPathSelfOrDescendantOf(candidate, normalizedPath),
    );
  }

  bool _isEntrySelected(FileSystemEntity entry) {
    final path = _normalizePath(entry.path);
    if (!_isPathEffectivelySelected(path)) {
      return false;
    }
    if (!_isDirectoryLikeEntry(entry) ||
        !_expandedDirectoryPaths.contains(path)) {
      return true;
    }

    final children =
        _directoryChildrenCache[path] ??
        (Directory(path).existsSync()
            ? _sortedEntriesFor(Directory(path))
            : const <FileSystemEntity>[]);
    for (final child in children) {
      if (!_isEntrySelected(child)) {
        return false;
      }
    }
    return true;
  }

  void _selectEntry(FileSystemEntity entry) {
    final path = _normalizePath(entry.path);
    if (!_isInsideWorkspace(path)) return;
    setState(() {
      _clearSelectionDirectivesWithin(path);
      _selectedEntryPaths.add(path);
    });
  }

  void _deselectEntry(FileSystemEntity entry) {
    final path = _normalizePath(entry.path);
    if (!_isInsideWorkspace(path)) return;
    final selectedByAncestor = _isPathSelectedByAncestor(path);
    setState(() {
      _clearSelectionDirectivesWithin(path);
      if (selectedByAncestor) {
        _excludedEntryPaths.add(path);
      }
    });
  }

  void _toggleEntrySelection(FileSystemEntity entry) {
    if (_isEntrySelected(entry)) {
      _deselectEntry(entry);
      return;
    }
    _selectEntry(entry);
  }

  List<String> _topLevelSelectedPathsForDelete() {
    final targets = _selectedEntryPaths.toList(growable: false)
      ..sort((a, b) => a.length.compareTo(b.length));
    final collapsed = <String>[];
    for (final rawPath in targets) {
      final path = _normalizePath(rawPath);
      final alreadyCovered = collapsed.any(
        (selected) => path == selected || path.startsWith('$selected/'),
      );
      if (alreadyCovered) {
        continue;
      }
      collapsed.add(path);
    }
    return collapsed;
  }

  Widget _buildBreadcrumbHeader() {
    final palette = context.omniPalette;
    final breadcrumbs = _workspaceBreadcrumbs;
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (widget.showHeaderTitle) ...[
            Text(
              Localizations.localeOf(context).languageCode == 'en'
                  ? 'Workspace'
                  : '工作区',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w700,
                color: palette.textPrimary,
              ),
            ),
            const SizedBox(height: 6),
          ],
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              const SizedBox(width: 10),
              _buildBulkSelectionToggleButton(),
              const SizedBox(width: 4),
              Expanded(
                child: breadcrumbs.isEmpty
                    ? Text(
                        _isEnglish ? 'Loading workspace...' : '加载工作区中...',
                        style: TextStyle(
                          fontSize: 12,
                          color: palette.textSecondary,
                        ),
                      )
                    : Wrap(
                        crossAxisAlignment: WrapCrossAlignment.center,
                        spacing: 2,
                        runSpacing: 4,
                        children: [
                          for (
                            var index = 0;
                            index < breadcrumbs.length;
                            index++
                          ) ...[
                            if (index > 0)
                              const Padding(
                                padding: EdgeInsets.symmetric(horizontal: 2),
                                child: Icon(
                                  Icons.chevron_right_rounded,
                                  size: 16,
                                  color: Color(0xFF98A2B3),
                                ),
                              ),
                            _buildWorkspaceBreadcrumbChip(breadcrumbs[index]),
                          ],
                        ],
                      ),
              ),
              if (_selectedEntryPaths.isNotEmpty) ...[
                const SizedBox(width: 8),
                AnimatedSwitcher(
                  duration: const Duration(milliseconds: 160),
                  switchInCurve: Curves.easeOut,
                  switchOutCurve: Curves.easeIn,
                  child: _buildDeleteSelectedButton(),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildBulkSelectionToggleButton() {
    final palette = context.omniPalette;
    final isActive = _isBulkSelectionMode;
    return Tooltip(
      message: _isEnglish
          ? (isActive ? 'Exit multi-select' : 'Multi-select')
          : (isActive ? '退出批量选择' : '批量选择'),
      child: Material(
        color: Colors.transparent,
        child: InkResponse(
          radius: 18,
          highlightShape: BoxShape.circle,
          onTap: _toggleBulkSelectionMode,
          child: SizedBox(
            width: 28,
            height: 28,
            child: Center(
              child: SvgPicture.string(
                isActive ? _bulkSelectCheckedIconSvg : _bulkSelectIconSvg,
                width: 20,
                height: 20,
                colorFilter: ColorFilter.mode(
                  isActive ? const Color(0xFF2563EB) : palette.textSecondary,
                  BlendMode.srcIn,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildDeleteSelectedButton() {
    final count = _selectedEntryPaths.length;
    final label = _isEnglish ? 'Delete ($count)' : '删除 $count 项';
    return TextButton(
      key: ValueKey<String>('workspace-delete-selected-$count'),
      onPressed: () => unawaited(_confirmAndDeleteSelectedEntries()),
      style: TextButton.styleFrom(
        foregroundColor: const Color(0xFFE53935),
        backgroundColor: const Color(0x14E53935),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        shape: const StadiumBorder(),
        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
        visualDensity: VisualDensity.compact,
      ),
      child: Text(
        label,
        style: const TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w700,
          fontFamily: 'PingFang SC',
        ),
      ),
    );
  }

  Widget _buildSelectionLeadingIcon({required bool isSelected}) {
    return SvgPicture.string(
      isSelected ? _bulkSelectCheckedIconSvg : _bulkSelectIconSvg,
      width: 20,
      height: 20,
      colorFilter: ColorFilter.mode(
        isSelected
            ? const Color(0xFF2563EB)
            : context.omniPalette.textSecondary,
        BlendMode.srcIn,
      ),
    );
  }

  Widget _buildWorkspaceBreadcrumbChip(_WorkspaceBreadcrumbSegment segment) {
    final palette = context.omniPalette;
    final labelStyle = TextStyle(
      fontSize: 12,
      fontWeight: segment.isCurrent ? FontWeight.w600 : FontWeight.w500,
      color: segment.isCurrent ? palette.textPrimary : palette.textSecondary,
    );
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: segment.isCurrent
            ? null
            : () => unawaited(_handleBreadcrumbTap(segment)),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 180),
            child: Text(
              segment.label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: labelStyle,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildInlineFilePreview() {
    final metadata = _selectedFileMetadata;
    if (metadata == null) {
      return const SizedBox.shrink();
    }
    return _WorkspaceInlineFilePreview(
      key: _inlinePreviewKey,
      metadata: metadata,
    );
  }

  List<FileSystemEntity> _sortedEntriesFor(Directory directory) {
    final entries = directory.listSync(followLinks: false).toList();
    for (final entry in entries.whereType<Link>()) {
      if (FileSystemEntity.typeSync(entry.path) ==
          FileSystemEntityType.directory) {
        _directoryLikePaths.add(entry.path);
      } else {
        _directoryLikePaths.remove(entry.path);
      }
    }
    return entries..sort((a, b) {
      final aIsDirectoryLike = _isDirectoryLikeEntry(a);
      final bIsDirectoryLike = _isDirectoryLikeEntry(b);
      if (aIsDirectoryLike && !bIsDirectoryLike) return -1;
      if (!aIsDirectoryLike && bIsDirectoryLike) return 1;
      return a.path.toLowerCase().compareTo(b.path.toLowerCase());
    });
  }

  bool _isDirectoryLikeEntry(FileSystemEntity entry) {
    if (entry is Directory) {
      return true;
    }
    if (entry is! Link) {
      return false;
    }
    return _directoryLikePaths.contains(entry.path);
  }

  WorkspaceMountEntry? _workspaceMountEntryFor(FileSystemEntity entry) {
    return _mountEntries[entry.path];
  }

  bool _isWorkspaceMountRootPath(String path) {
    return WorkspaceMountService.describeMountEntrySync(
          path,
          rootPath: _rootDirectory.path,
        ) !=
        null;
  }

  bool _isDescendantOfCurrentDirectory(String path) {
    if (path == _directory.path) return true;
    return path.startsWith('${_directory.path}/');
  }

  void _collapseDirectory(String directoryPath) {
    setState(() {
      _expandedDirectoryPaths.removeWhere(
        (path) => path == directoryPath || path.startsWith('$directoryPath/'),
      );
      _directoryChildrenCache.removeWhere(
        (path, _) =>
            path == directoryPath || path.startsWith('$directoryPath/'),
      );
    });
  }

  void _toggleDirectoryExpansion(Directory directory, {required int depth}) {
    if (depth >= _maxInlineExpansionDepth) {
      _openDirectory(directory);
      return;
    }
    final path = directory.path;
    if (_expandedDirectoryPaths.contains(path)) {
      _collapseDirectory(path);
      return;
    }
    final children = _sortedEntriesFor(directory);
    setState(() {
      _expandedDirectoryPaths.add(path);
      _directoryChildrenCache[path] = children;
    });
  }

  @override
  Widget build(BuildContext context) {
    final exists = _directoryExists;
    final currentShellPath = _currentShellPath();
    final canGoUpDirectory = _directory.path != _rootDirectory.path;
    final showParentEntry = canGoUpDirectory && !widget.showBreadcrumbHeader;
    final itemCount = _entries.length + (showParentEntry ? 1 : 0);

    final body = _isPreviewingFile
        ? _buildInlineFilePreview()
        : RefreshIndicator(
            onRefresh: _refresh,
            child: _isLoadingDirectory && _entries.isEmpty
                ? _buildStatusList(
                    message: _isEnglish ? 'Loading workspace…' : '正在加载工作区…',
                  )
                : _directoryLoadError != null
                ? _buildStatusList(
                    message: _isEnglish
                        ? 'Unable to read directory. Pull to retry.'
                        : '无法读取目录，请下拉重试。',
                  )
                : !exists
                ? _buildStatusList(
                    message:
                        Localizations.localeOf(context).languageCode == 'en'
                        ? 'Workspace not found'
                        : '工作区不存在',
                  )
                : itemCount == 0
                ? _buildStatusList(
                    message:
                        Localizations.localeOf(context).languageCode == 'en'
                        ? 'Current directory is empty'
                        : '当前目录为空',
                  )
                : ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
                    itemCount: itemCount,
                    itemBuilder: (context, index) {
                      final isFirst = index == 0;
                      final isLast = index == itemCount - 1;

                      if (showParentEntry && index == 0) {
                        final borderRadius = _itemRadius(
                          roundTop: true,
                          roundBottom: isLast,
                        );
                        final parentRow = _buildWorkspaceItem(
                          title: '..',
                          leading: Icon(
                            Icons.arrow_upward_rounded,
                            size: 20,
                            color: context.omniPalette.textSecondary,
                          ),
                          borderRadius: borderRadius,
                          onTap: openParentDirectory,
                        );
                        return _buildDirectoryDropTarget(
                          child: parentRow,
                          borderRadius: borderRadius,
                          targetDirectoryPath: _directory.parent.path,
                        );
                      }

                      final entry = _entries[index - (showParentEntry ? 1 : 0)];
                      return _buildEntryNode(
                        entry: entry,
                        depth: 0,
                        currentShellPath: currentShellPath,
                        roundTop: isFirst,
                        roundBottom: isLast,
                      );
                    },
                  ),
          );

    final content = Column(
      children: [
        if (widget.showBreadcrumbHeader) _buildBreadcrumbHeader(),
        Expanded(child: body),
      ],
    );

    if (!widget.enableSystemBackHandler) {
      return content;
    }
    return PopScope(
      canPop: !canGoUp,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        openParentDirectory();
      },
      child: content,
    );
  }

  BorderRadius _itemRadius({
    required bool roundTop,
    required bool roundBottom,
  }) {
    const radius = Radius.circular(_itemCornerRadius);
    return BorderRadius.vertical(
      top: roundTop ? radius : Radius.zero,
      bottom: roundBottom ? radius : Radius.zero,
    );
  }

  Widget _buildEntryNode({
    required FileSystemEntity entry,
    required int depth,
    required String? currentShellPath,
    bool roundTop = true,
    bool roundBottom = true,
  }) {
    final name = entry.path.split('/').last;
    final mountEntry = _workspaceMountEntryFor(entry);
    final isWorkspaceMountEntry = mountEntry != null;
    final isDirectory = _isDirectoryLikeEntry(entry);
    final selectionMode = _isBulkSelectionMode;
    final isSelected = _isEntrySelected(entry);
    final canExpandInline =
        widget.enableInlineDirectoryExpansion &&
        isDirectory &&
        depth < _maxInlineExpansionDepth;
    final isExpanded =
        isDirectory &&
        canExpandInline &&
        _expandedDirectoryPaths.contains(entry.path);
    final expandedChildren = isExpanded
        ? (_directoryChildrenCache[entry.path] ?? const <FileSystemEntity>[])
        : const <FileSystemEntity>[];

    // 展开后本行底部与下方子项(或空文件夹占位)相接,底部圆角交给展开块的最后一行。
    final itemBorderRadius = _itemRadius(
      roundTop: roundTop,
      roundBottom: roundBottom && !isExpanded,
    );

    final trailing = selectionMode
        ? null
        : isDirectory
        ? Icon(
            canExpandInline
                ? (isExpanded
                      ? Icons.expand_less_rounded
                      : Icons.expand_more_rounded)
                : Icons.chevron_right_rounded,
            color: context.omniPalette.textSecondary,
            size: 18,
          )
        : null;

    Widget row = _buildWorkspaceItem(
      title: name,
      depth: depth,
      leading: selectionMode
          ? _buildSelectionLeadingIcon(isSelected: isSelected)
          : _buildDraggableLeadingIcon(
              entry: entry,
              isExpanded: isExpanded,
              draggable: !isWorkspaceMountEntry,
            ),
      borderRadius: itemBorderRadius,
      trailing: trailing,
      onTap: () {
        if (selectionMode) {
          _toggleEntrySelection(entry);
          return;
        }
        if (isDirectory) {
          final directory = Directory(entry.path);
          if (canExpandInline) {
            _toggleDirectoryExpansion(directory, depth: depth);
          } else {
            _openDirectory(directory);
          }
          return;
        }
        if (widget.inlineFilePreview) {
          _openInlineFilePreview(entry, currentShellPath: currentShellPath);
          return;
        }
        _openFileEntry(entry, currentShellPath: currentShellPath);
      },
      onLongPress: selectionMode ? null : () => _showEntryActionSheet(entry),
    );

    if (isDirectory && !selectionMode) {
      row = _buildDirectoryDropTarget(
        child: row,
        borderRadius: itemBorderRadius,
        targetDirectoryPath: entry.path,
      );
    }

    if (!isExpanded) {
      return row;
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        row,
        _buildExpandedChildren(
          entries: expandedChildren,
          depth: depth + 1,
          currentShellPath: currentShellPath,
          roundLastChildBottom: roundBottom,
        ),
      ],
    );
  }

  Widget _buildDraggableLeadingIcon({
    required FileSystemEntity entry,
    required bool isExpanded,
    required bool draggable,
  }) {
    Widget buildIcon({double size = 20}) {
      return SvgPicture.asset(
        _iconAssetForEntry(entry, isExpanded: isExpanded),
        width: size,
        height: size,
        colorFilter: ColorFilter.mode(
          context.omniPalette.textPrimary,
          BlendMode.srcIn,
        ),
      );
    }

    final payload = _WorkspaceDragPayload(
      sourcePath: _normalizePath(entry.path),
      isDirectory: _isDirectoryLikeEntry(entry),
    );

    if (!draggable) {
      return buildIcon();
    }

    return LongPressDraggable<_WorkspaceDragPayload>(
      data: payload,
      feedback: _buildDragFeedback(
        name: _entryNameFromPath(entry.path),
        icon: buildIcon(size: 18),
      ),
      childWhenDragging: Opacity(opacity: 0.35, child: buildIcon()),
      onDragStarted: () => _setDragHoverTarget(null),
      onDragEnd: (_) => _setDragHoverTarget(null),
      onDraggableCanceled: (_, __) => _setDragHoverTarget(null),
      child: buildIcon(),
    );
  }

  Widget _buildDragFeedback({required String name, required Widget icon}) {
    final palette = context.omniPalette;
    return Material(
      color: Colors.transparent,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 220),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: _surfaceColor(opacity: 0.9),
            borderRadius: BorderRadius.circular(8),
            boxShadow: [AppColors.boxShadow],
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                icon,
                const SizedBox(width: 8),
                Flexible(
                  child: Text(
                    name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                      color: palette.textPrimary,
                      fontFamily: 'PingFang SC',
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildDirectoryDropTarget({
    required Widget child,
    required BorderRadius borderRadius,
    required String targetDirectoryPath,
  }) {
    return DragTarget<_WorkspaceDragPayload>(
      onWillAcceptWithDetails: (details) {
        final canMove = _canMovePayloadToDirectory(
          payload: details.data,
          targetDirectoryPath: targetDirectoryPath,
        );
        _setDragHoverTarget(canMove ? targetDirectoryPath : null);
        return canMove;
      },
      onLeave: (_) => _setDragHoverTarget(null),
      onAcceptWithDetails: (details) {
        _setDragHoverTarget(null);
        _handleDropMove(
          payload: details.data,
          targetDirectoryPath: targetDirectoryPath,
        );
      },
      builder: (context, candidateData, rejectedData) {
        final highlighted =
            _dragHoverTargetPath == targetDirectoryPath ||
            candidateData.isNotEmpty;
        return AnimatedContainer(
          duration: const Duration(milliseconds: 120),
          decoration: highlighted
              ? BoxDecoration(
                  color: const Color(0x142C7FEB),
                  borderRadius: borderRadius,
                )
              : null,
          child: child,
        );
      },
    );
  }

  void _setDragHoverTarget(String? path) {
    if (_dragHoverTargetPath == path || !mounted) return;
    setState(() {
      _dragHoverTargetPath = path;
    });
  }

  bool _canMovePayloadToDirectory({
    required _WorkspaceDragPayload payload,
    required String targetDirectoryPath,
  }) {
    final sourcePath = _normalizePath(payload.sourcePath);
    final targetPath = _normalizePath(targetDirectoryPath);

    if (_isWorkspaceMountRootPath(sourcePath)) {
      return false;
    }

    if (!_isInsideWorkspace(sourcePath) || !_isInsideWorkspace(targetPath)) {
      return false;
    }
    if (sourcePath == targetPath) return false;

    final sourceParentPath = _normalizePath(File(sourcePath).parent.path);
    if (sourceParentPath == targetPath) return false;

    if (payload.isDirectory && targetPath.startsWith('$sourcePath/')) {
      return false;
    }

    if (!Directory(targetPath).existsSync()) return false;

    final destinationPath = '$targetPath/${_entryNameFromPath(sourcePath)}';
    return FileSystemEntity.typeSync(destinationPath) ==
        FileSystemEntityType.notFound;
  }

  Future<void> _handleDropMove({
    required _WorkspaceDragPayload payload,
    required String targetDirectoryPath,
  }) async {
    final sourcePath = _normalizePath(payload.sourcePath);
    final targetPath = _normalizePath(targetDirectoryPath);
    final sourceName = _entryNameFromPath(sourcePath);

    if (sourceName.isEmpty) {
      showToast('移动失败：文件名无效', type: ToastType.error);
      return;
    }
    if (!_isInsideWorkspace(sourcePath) || !_isInsideWorkspace(targetPath)) {
      showToast('仅支持在当前 workspace 内移动', type: ToastType.warning);
      return;
    }
    if (sourcePath == targetPath) {
      showToast('不能移动到自身目录', type: ToastType.warning);
      return;
    }

    final sourceParentPath = _normalizePath(File(sourcePath).parent.path);
    if (sourceParentPath == targetPath) {
      showToast('文件已在目标目录中', type: ToastType.info);
      return;
    }

    if (payload.isDirectory && targetPath.startsWith('$sourcePath/')) {
      showToast('不能移动到自身或子目录', type: ToastType.warning);
      return;
    }

    final destinationPath = '$targetPath/$sourceName';
    if (FileSystemEntity.typeSync(destinationPath) !=
        FileSystemEntityType.notFound) {
      showToast('目标目录存在同名项，请先重命名', type: ToastType.warning);
      return;
    }

    final sourceType = FileSystemEntity.typeSync(sourcePath);
    if (sourceType == FileSystemEntityType.notFound) {
      showToast('源文件不存在，请刷新重试', type: ToastType.warning);
      _refresh();
      return;
    }

    try {
      final sourceEntity = sourceType == FileSystemEntityType.directory
          ? Directory(sourcePath)
          : File(sourcePath);
      await sourceEntity.rename(destinationPath);
      showToast(
        sourceType == FileSystemEntityType.directory ? '文件夹已移动' : '文件已移动',
        type: ToastType.success,
      );
      _refresh();
    } catch (error) {
      showToast('移动失败：$error', type: ToastType.error);
    }
  }

  Future<void> _showEntryActionSheet(FileSystemEntity entry) async {
    final palette = context.omniPalette;
    final name = _entryNameFromPath(entry.path);
    final mountEntry = _workspaceMountEntryFor(entry);
    final editable = _canEditEntry(entry);
    final action = await showModalBottomSheet<_WorkspaceEntryAction>(
      context: context,
      backgroundColor: _surfaceColor(opacity: 0.92),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (sheetContext) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 36,
                  height: 4,
                  decoration: BoxDecoration(
                    color: palette.borderStrong,
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                    fontFamily: 'PingFang SC',
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  mountEntry == null
                      ? '长按左侧图标并拖动到目标文件夹可移动位置'
                      : (Localizations.localeOf(context).languageCode == 'en'
                            ? 'This entry is a mounted host directory'
                            : '这是一个挂载进 /workspace 的宿主目录'),
                  style: TextStyle(
                    fontSize: 12,
                    color: palette.textSecondary,
                    fontFamily: 'PingFang SC',
                  ),
                ),
                const SizedBox(height: 12),
                if (mountEntry == null && editable) ...[
                  ListTile(
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                    tileColor: _secondarySurfaceColor(),
                    leading: Icon(
                      Icons.edit_outlined,
                      color: palette.textPrimary,
                    ),
                    title: Text(
                      '编辑',
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontWeight: FontWeight.w600,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                    onTap: () => Navigator.of(
                      sheetContext,
                    ).pop(_WorkspaceEntryAction.edit),
                  ),
                  const SizedBox(height: 8),
                ],
                if (mountEntry == null) ...[
                  ListTile(
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                    tileColor: _secondarySurfaceColor(),
                    leading: Icon(
                      Icons.drive_file_rename_outline_rounded,
                      color: palette.textPrimary,
                    ),
                    title: Text(
                      '重命名',
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontWeight: FontWeight.w600,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                    onTap: () => Navigator.of(
                      sheetContext,
                    ).pop(_WorkspaceEntryAction.rename),
                  ),
                  const SizedBox(height: 8),
                ],
                ListTile(
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                  tileColor: _secondarySurfaceColor(),
                  leading: Icon(
                    mountEntry == null
                        ? Icons.delete_outline_rounded
                        : Icons.link_off_rounded,
                    color: const Color(0xFFE53935),
                  ),
                  title: Text(
                    mountEntry == null ? '删除' : '卸载挂载',
                    style: const TextStyle(
                      color: Color(0xFFE53935),
                      fontWeight: FontWeight.w600,
                      fontFamily: 'PingFang SC',
                    ),
                  ),
                  onTap: () => Navigator.of(sheetContext).pop(
                    mountEntry == null
                        ? _WorkspaceEntryAction.delete
                        : _WorkspaceEntryAction.unmount,
                  ),
                ),
                const SizedBox(height: 8),
                ListTile(
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                  tileColor: _secondarySurfaceColor(),
                  leading: Icon(
                    Icons.close_rounded,
                    color: palette.textPrimary,
                  ),
                  title: Text(
                    '取消',
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontWeight: FontWeight.w500,
                      fontFamily: 'PingFang SC',
                    ),
                  ),
                  onTap: () => Navigator.of(sheetContext).pop(),
                ),
              ],
            ),
          ),
        );
      },
    );

    if (!mounted || action == null) return;
    if (action == _WorkspaceEntryAction.unmount) {
      if (mountEntry != null) {
        await _confirmAndUnmountWorkspaceMount(mountEntry);
      }
      return;
    }
    if (action == _WorkspaceEntryAction.edit) {
      _openFileEntry(entry, startInEditMode: true);
      return;
    }
    if (action == _WorkspaceEntryAction.rename) {
      await _promptRenameEntry(entry);
      return;
    }
    await _confirmAndDeleteEntry(entry);
  }

  bool _canEditEntry(FileSystemEntity entry) {
    if (entry is! File) {
      return false;
    }
    final metadata = OmnibotResourceService.describePath(entry.path);
    return metadata.previewKind == 'text' || metadata.previewKind == 'code';
  }

  void _openFileEntry(
    FileSystemEntity entry, {
    String? currentShellPath,
    bool startInEditMode = false,
  }) {
    final name = _entryNameFromPath(entry.path);
    final shellPath =
        OmnibotResourceService.shellPathForAndroidPath(entry.path) ??
        (currentShellPath == null ? null : '$currentShellPath/$name');
    OmnibotResourceService.openFilePath(
      entry.path,
      title: name,
      shellPath: shellPath,
      startInEditMode: startInEditMode,
    );
  }

  Future<void> _promptRenameEntry(FileSystemEntity entry) async {
    final sourcePath = _normalizePath(entry.path);
    final sourceType = FileSystemEntity.typeSync(sourcePath);
    if (sourceType == FileSystemEntityType.notFound) {
      showToast('目标不存在，请刷新重试', type: ToastType.warning);
      _refresh();
      return;
    }

    final oldName = _entryNameFromPath(sourcePath);
    final isDirectory = sourceType == FileSystemEntityType.directory;
    final nextName = (await AppDialog.input(
      context,
      title: isDirectory ? '重命名文件夹' : '重命名文件',
      hintText: '请输入新名称',
      initialValue: oldName,
      confirmText: '保存',
      cancelText: '取消',
    ))?.trim();

    if (nextName == null) return;

    final validationError = _validateEntryName(nextName);
    if (validationError != null) {
      showToast(validationError, type: ToastType.warning);
      return;
    }
    if (nextName == oldName) {
      showToast('名称未发生变化', type: ToastType.info);
      return;
    }

    final parentPath = _normalizePath(File(sourcePath).parent.path);
    final destinationPath = '$parentPath/$nextName';

    if (!_isInsideWorkspace(destinationPath)) {
      showToast('重命名失败：目标路径无效', type: ToastType.error);
      return;
    }
    if (FileSystemEntity.typeSync(destinationPath) !=
        FileSystemEntityType.notFound) {
      showToast('同名文件或文件夹已存在', type: ToastType.warning);
      return;
    }

    try {
      final sourceEntity = isDirectory
          ? Directory(sourcePath)
          : File(sourcePath);
      await sourceEntity.rename(destinationPath);
      showToast('重命名成功', type: ToastType.success);
      _refresh();
    } catch (error) {
      showToast('重命名失败：$error', type: ToastType.error);
    }
  }

  String? _validateEntryName(String name) {
    if (name.trim().isEmpty) return '名称不能为空';
    if (name == '.' || name == '..') return '名称不能为 . 或 ..';
    if (name.contains('/')) return '名称不能包含 /';
    if (name.contains('\\')) return '名称不能包含 "\\"';
    if (name.contains('\u0000')) return '名称包含非法字符';
    return null;
  }

  Future<void> _confirmAndDeleteEntry(FileSystemEntity entry) async {
    final mountEntry = _workspaceMountEntryFor(entry);
    if (mountEntry != null) {
      await _confirmAndUnmountWorkspaceMount(mountEntry);
      return;
    }
    final path = _normalizePath(entry.path);
    final name = _entryNameFromPath(path);
    final sourceType = FileSystemEntity.typeSync(path);
    if (sourceType == FileSystemEntityType.notFound) {
      showToast('目标不存在，请刷新重试', type: ToastType.warning);
      _refresh();
      return;
    }

    final isDirectory = sourceType == FileSystemEntityType.directory;
    final confirmed = await AppDialog.confirm(
      context,
      title: isDirectory ? '删除文件夹' : '删除文件',
      content: '确认删除“$name”？删除后不可恢复。',
      cancelText: '取消',
      confirmText: '删除',
      confirmButtonColor: const Color(0xFFE53935),
    );
    if (confirmed != true) return;

    try {
      if (isDirectory) {
        await Directory(path).delete(recursive: true);
      } else {
        await File(path).delete();
      }
      showToast(isDirectory ? '文件夹已删除' : '文件已删除', type: ToastType.success);
      _refresh();
    } catch (error) {
      showToast('删除失败：$error', type: ToastType.error);
    }
  }

  Future<void> _confirmAndDeleteSelectedEntries() async {
    final selectedPaths = _topLevelSelectedPathsForDelete();
    if (selectedPaths.isEmpty) {
      showToast(_isEnglish ? 'No items selected' : '还没有选中文件或文件夹');
      return;
    }

    final previewNames = selectedPaths
        .take(3)
        .map(_entryNameFromPath)
        .where((name) => name.isNotEmpty)
        .toList(growable: false);
    final content = selectedPaths.length == 1
        ? (_isEnglish
              ? 'Delete "${previewNames.first}"? This cannot be undone.'
              : '确认删除“${previewNames.first}”？删除后不可恢复。')
        : (_isEnglish
              ? 'Delete ${selectedPaths.length} selected items? This cannot be undone.'
              : '确认删除已选择的 ${selectedPaths.length} 项？删除后不可恢复。');

    final confirmed = await AppDialog.confirm(
      context,
      title: _isEnglish ? 'Delete selected items' : '删除所选项',
      content: content,
      cancelText: _isEnglish ? 'Cancel' : '取消',
      confirmText: _isEnglish ? 'Delete' : '删除',
      confirmButtonColor: const Color(0xFFE53935),
    );
    if (confirmed != true) return;

    var deletedCount = 0;
    final failedPaths = <String>[];
    for (final path in selectedPaths) {
      final sourceType = FileSystemEntity.typeSync(path);
      if (sourceType == FileSystemEntityType.notFound) {
        continue;
      }
      try {
        await _deleteSelectedPathRecursively(path);
        deletedCount += 1;
      } catch (_) {
        failedPaths.add(path);
      }
    }

    if (!mounted) return;

    setState(() {
      _selectedEntryPaths.clear();
      _excludedEntryPaths.clear();
      _isBulkSelectionMode = false;
    });
    _refresh();

    if (failedPaths.isEmpty) {
      showToast(
        _isEnglish
            ? 'Deleted $deletedCount item(s)'
            : '已删除 $deletedCount 项文件或文件夹',
        type: ToastType.success,
      );
      return;
    }

    if (deletedCount > 0) {
      showToast(
        _isEnglish
            ? 'Deleted $deletedCount item(s), ${failedPaths.length} failed'
            : '已删除 $deletedCount 项，${failedPaths.length} 项删除失败',
        type: ToastType.warning,
      );
      return;
    }

    showToast(
      _isEnglish ? 'Failed to delete selected items' : '删除所选项失败',
      type: ToastType.error,
    );
  }

  Future<void> _confirmAndUnmountWorkspaceMount(
    WorkspaceMountEntry mountEntry,
  ) async {
    final confirmed = await AppDialog.confirm(
      context,
      title: '卸载挂载',
      content: '确认卸载 “${mountEntry.shellPath}” 吗？不会删除原始目录和其中的文件。',
      cancelText: '取消',
      confirmText: '卸载',
      confirmButtonColor: const Color(0xFFE53935),
    );
    if (confirmed != true) return;
    try {
      WorkspaceMountService.unmountDirectorySync(mountEntry.linkPath);
      showToast('已卸载 ${mountEntry.shellPath}', type: ToastType.success);
      _refresh();
    } catch (error) {
      showToast('卸载失败：$error', type: ToastType.error);
    }
  }

  Future<void> _deleteSelectedPathRecursively(String path) async {
    final normalizedPath = _normalizePath(path);
    final sourceType = FileSystemEntity.typeSync(normalizedPath);
    if (sourceType == FileSystemEntityType.notFound) {
      return;
    }

    final isSelected = _isPathEffectivelySelected(normalizedPath);
    final hasSelectedDescendants = _hasSelectionDirectiveDescendant(
      _selectedEntryPaths,
      normalizedPath,
    );
    final hasExcludedDescendants = _hasSelectionDirectiveDescendant(
      _excludedEntryPaths,
      normalizedPath,
    );
    final mountEntry = WorkspaceMountService.describeMountEntrySync(
      normalizedPath,
      rootPath: _rootDirectory.path,
    );

    if (sourceType != FileSystemEntityType.directory) {
      if (isSelected) {
        await File(normalizedPath).delete();
      }
      return;
    }

    final directory = Directory(normalizedPath);
    if (mountEntry != null) {
      if (isSelected && !hasExcludedDescendants) {
        WorkspaceMountService.unmountDirectorySync(mountEntry.linkPath);
        return;
      }
      if (!hasSelectedDescendants) {
        return;
      }
    }
    if (isSelected && !hasExcludedDescendants) {
      await directory.delete(recursive: true);
      return;
    }
    if (!isSelected && !hasSelectedDescendants) {
      return;
    }
    if (!directory.existsSync()) {
      return;
    }

    final children = directory.listSync().toList(growable: false);
    for (final child in children) {
      await _deleteSelectedPathRecursively(child.path);
    }

    if (!isSelected || !directory.existsSync()) {
      return;
    }

    if (mountEntry != null) {
      return;
    }

    final hasRemainingEntries = directory.listSync().isNotEmpty;
    if (!hasRemainingEntries) {
      await directory.delete();
    }
  }

  String _normalizePath(String path) {
    if (path.length > 1 && path.endsWith('/')) {
      return path.substring(0, path.length - 1);
    }
    return path;
  }

  bool _isInsideWorkspace(String path) {
    final normalizedPath = _normalizePath(path);
    final normalizedRoot = _normalizePath(_rootDirectory.path);
    return normalizedPath == normalizedRoot ||
        normalizedPath.startsWith('$normalizedRoot/');
  }

  String _entryNameFromPath(String path) {
    final normalizedPath = _normalizePath(path);
    final slashIndex = normalizedPath.lastIndexOf('/');
    if (slashIndex < 0 || slashIndex == normalizedPath.length - 1) {
      return normalizedPath;
    }
    return normalizedPath.substring(slashIndex + 1);
  }

  Widget _buildExpandedChildren({
    required List<FileSystemEntity> entries,
    required int depth,
    required String? currentShellPath,
    required bool roundLastChildBottom,
  }) {
    if (entries.isEmpty) {
      // 整宽占位,与上方父行背景拉通,避免出现缺口;若父级是列表末行则收底部圆角。
      final palette = context.omniPalette;
      return DecoratedBox(
        decoration: BoxDecoration(
          color: _surfaceColor(),
          borderRadius: _itemRadius(
            roundTop: false,
            roundBottom: roundLastChildBottom,
          ),
        ),
        child: SizedBox(
          height: _itemHeight,
          child: Padding(
            padding: EdgeInsets.only(left: 12 + depth * _indentStep, right: 12),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                '空文件夹',
                style: TextStyle(fontSize: 12, color: palette.textSecondary),
              ),
            ),
          ),
        ),
      );
    }

    Widget buildItem(BuildContext context, int index) {
      final isLast = index == entries.length - 1;
      return _buildEntryNode(
        entry: entries[index],
        depth: depth,
        currentShellPath: currentShellPath,
        roundTop: false,
        roundBottom: isLast && roundLastChildBottom,
      );
    }

    if (entries.length > _maxExpandedItemsBeforeScroll) {
      return SizedBox(
        height: _itemHeight * _maxExpandedItemsBeforeScroll,
        child: ListView.builder(
          primary: false,
          physics: const ClampingScrollPhysics(),
          itemCount: entries.length,
          itemBuilder: buildItem,
        ),
      );
    }
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var index = 0; index < entries.length; index++)
          buildItem(context, index),
      ],
    );
  }

  Widget _buildStatusList({required String message}) {
    final palette = context.omniPalette;
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      children: [
        SizedBox(
          height: 280,
          child: Center(
            child: Text(
              message,
              style: TextStyle(color: palette.textSecondary, fontSize: 14),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildWorkspaceItem({
    required String title,
    required Widget leading,
    required BorderRadius borderRadius,
    required VoidCallback onTap,
    int depth = 0,
    VoidCallback? onLongPress,
    Widget? trailing,
  }) {
    final palette = context.omniPalette;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: _surfaceColor(),
        borderRadius: borderRadius,
      ),
      child: Material(
        color: Colors.transparent,
        borderRadius: borderRadius,
        child: InkWell(
          onTap: onTap,
          onLongPress: onLongPress,
          borderRadius: borderRadius,
          child: SizedBox(
            height: _itemHeight,
            child: Padding(
              // 背景整宽拉通,层级只靠内容左缩进体现,展开父子间不再出现背景缺口。
              padding: EdgeInsets.only(
                left: 12 + depth * _indentStep,
                right: 12,
              ),
              child: Row(
                children: [
                  leading,
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                        color: palette.textPrimary,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                  ),
                  if (trailing != null) ...[const SizedBox(width: 8), trailing],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  String _iconAssetForEntry(FileSystemEntity entry, {bool isExpanded = false}) {
    if (_isDirectoryLikeEntry(entry)) {
      return isExpanded ? _folderOpenIconAsset : _folderIconAsset;
    }
    final fileName = entry.path.split('/').last.toLowerCase();
    final dotIndex = fileName.lastIndexOf('.');
    final extension = dotIndex >= 0 ? fileName.substring(dotIndex) : '';
    if (_audioExtensions.contains(extension)) {
      return _audioIconAsset;
    }
    if (_videoExtensions.contains(extension)) {
      return _videoIconAsset;
    }
    return _fileIconAsset;
  }

  String? _currentShellPath() {
    final baseAndroid = widget.workspacePath;
    final baseShell = widget.workspaceShellPath;
    if (baseShell == null || baseShell.isEmpty) return null;
    if (_directory.path == baseAndroid) return baseShell;
    if (_directory.path.startsWith('$baseAndroid/')) {
      final suffix = _directory.path.substring(baseAndroid.length + 1);
      return '$baseShell/$suffix';
    }
    return OmnibotResourceService.shellPathForAndroidPath(_directory.path) ??
        baseShell;
  }
}

class _WorkspaceInlineFilePreview extends StatefulWidget {
  const _WorkspaceInlineFilePreview({super.key, required this.metadata});

  final OmnibotResourceMetadata metadata;

  @override
  State<_WorkspaceInlineFilePreview> createState() =>
      _WorkspaceInlineFilePreviewState();
}

class _WorkspaceInlineFilePreviewState
    extends State<_WorkspaceInlineFilePreview> {
  final TextEditingController _editorController = TextEditingController();
  String? _textContent;
  String? _error;
  bool _loadingText = false;
  bool _isEditing = false;
  bool _isSaving = false;
  bool _isDirty = false;

  bool get _isTextLike =>
      widget.metadata.previewKind == 'text' ||
      widget.metadata.previewKind == 'code';

  bool get _canEdit => widget.metadata.exists && _isTextLike;

  bool get _preferMonospace =>
      widget.metadata.previewKind == 'code' ||
      widget.metadata.mimeType == 'application/json' ||
      widget.metadata.mimeType == 'application/xml' ||
      widget.metadata.mimeType == 'application/yaml';

  @override
  void initState() {
    super.initState();
    _editorController.addListener(_handleEditorChanged);
    _loadIfNeeded();
  }

  @override
  void didUpdateWidget(covariant _WorkspaceInlineFilePreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.metadata.path != widget.metadata.path ||
        oldWidget.metadata.previewKind != widget.metadata.previewKind) {
      _textContent = null;
      _error = null;
      _isEditing = false;
      _isSaving = false;
      _isDirty = false;
      _editorController.clear();
      _loadIfNeeded();
    }
  }

  @override
  void dispose() {
    _editorController
      ..removeListener(_handleEditorChanged)
      ..dispose();
    super.dispose();
  }

  void _handleEditorChanged() {
    if (!_isEditing) return;
    final nextDirty = _editorController.text != (_textContent ?? '');
    if (nextDirty == _isDirty || !mounted) return;
    setState(() {
      _isDirty = nextDirty;
    });
  }

  Future<void> _loadIfNeeded() async {
    if (!_isTextLike || !widget.metadata.exists) return;
    if (mounted) {
      setState(() {
        _loadingText = true;
      });
    }
    try {
      final text = await File(widget.metadata.path).readAsString();
      if (!mounted) return;
      setState(() {
        _textContent = text;
        _error = null;
        _loadingText = false;
        if (_isEditing && !_isDirty) {
          _editorController.value = TextEditingValue(
            text: text,
            selection: TextSelection.collapsed(offset: text.length),
          );
        }
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = '读取失败：$error';
        _loadingText = false;
      });
    }
  }

  Future<bool> confirmDiscardIfNeeded() async {
    if (!_isEditing || !_isDirty) {
      return true;
    }
    final confirmed = await AppDialog.confirm(
      context,
      title: '放弃修改',
      content: '当前有未保存修改，确认离开吗？',
      cancelText: '继续编辑',
      confirmText: '放弃',
    );
    return confirmed == true;
  }

  Future<void> _handleEditPressed() async {
    if (!_canEdit) return;
    setState(() {
      _isEditing = true;
      _isDirty = false;
      _editorController.value = TextEditingValue(
        text: _textContent ?? '',
        selection: TextSelection.collapsed(offset: (_textContent ?? '').length),
      );
    });
    if (_textContent == null && !_loadingText) {
      unawaited(_loadIfNeeded());
    }
  }

  Future<void> _handleCancelEditing() async {
    final confirmed = await confirmDiscardIfNeeded();
    if (!confirmed || !mounted) return;
    setState(() {
      _isEditing = false;
      _isDirty = false;
      _editorController.value = TextEditingValue(
        text: _textContent ?? '',
        selection: TextSelection.collapsed(offset: (_textContent ?? '').length),
      );
    });
  }

  Future<void> _handleSaveText() async {
    if (!_canEdit || _isSaving) return;
    setState(() {
      _isSaving = true;
    });
    try {
      final savedText = _editorController.text;
      File(widget.metadata.path).writeAsStringSync(savedText);
      if (!mounted) return;
      setState(() {
        _textContent = savedText;
        _isDirty = false;
        _error = null;
        _isEditing = false;
      });
      showToast('文件已保存', type: ToastType.success);
    } catch (error) {
      if (!mounted) return;
      showToast('保存失败：$error', type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _isSaving = false;
        });
      }
    }
  }

  Widget _buildInlineResourcePreview(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final preview = OmnibotInlineResourceEmbed(
          metadata: widget.metadata,
          maxWidth: (constraints.maxWidth - 24).clamp(
            0.0,
            constraints.maxWidth,
          ),
          preferredHeight: switch (widget.metadata.previewKind) {
            'pdf' => (constraints.maxHeight - 24).clamp(240.0, 1200.0),
            'html' => (constraints.maxHeight - 24).clamp(280.0, 1200.0),
            _ => null,
          },
        );
        if (widget.metadata.previewKind == 'pdf' ||
            widget.metadata.previewKind == 'html') {
          return Padding(
            padding: const EdgeInsets.all(12),
            child: Center(child: preview),
          );
        }
        return SingleChildScrollView(
          padding: const EdgeInsets.all(12),
          child: Center(child: preview),
        );
      },
    );
  }

  Widget _buildEditor() {
    final statusText = _loadingText && _textContent == null
        ? (Localizations.localeOf(context).languageCode == 'en'
              ? 'Loading original content, you can start editing first'
              : '正在加载原始内容，可先开始编辑')
        : (_isDirty
              ? (Localizations.localeOf(context).languageCode == 'en'
                    ? 'Editing with unsaved changes'
                    : '编辑中，存在未保存修改')
              : (Localizations.localeOf(context).languageCode == 'en'
                    ? 'Editing. Save will write back to workspace immediately'
                    : '编辑中，保存后会立即写回 workspace'));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Builder(
          builder: (context) {
            final palette = context.omniPalette;
            return Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              color: palette.surfaceSecondary,
              child: Text(
                statusText,
                style: TextStyle(fontSize: 12, color: palette.textSecondary),
              ),
            );
          },
        ),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 88),
            child: TextField(
              controller: _editorController,
              expands: true,
              minLines: null,
              maxLines: null,
              keyboardType: TextInputType.multiline,
              textAlignVertical: TextAlignVertical.top,
              style: TextStyle(
                fontFamily: _preferMonospace ? 'monospace' : null,
                fontSize: 14,
                height: 1.5,
              ),
              decoration: InputDecoration(
                filled: true,
                fillColor: context.omniPalette.surfacePrimary,
                hintText: Localizations.localeOf(context).languageCode == 'en'
                    ? 'Enter file content'
                    : '输入文件内容',
                alignLabelWithHint: true,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: const BorderSide(color: Color(0xFF2C7FEB)),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildActionButtons() {
    if (!_canEdit) {
      return const SizedBox.shrink();
    }
    return SafeArea(
      minimum: const EdgeInsets.fromLTRB(16, 16, 16, 16),
      child: Align(
        alignment: Alignment.bottomRight,
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (_isEditing)
              FilledButton.tonalIcon(
                key: const ValueKey('workspace-inline-preview-cancel'),
                onPressed: _isSaving ? null : _handleCancelEditing,
                icon: const Icon(Icons.close_rounded),
                label: Text(
                  Localizations.localeOf(context).languageCode == 'en'
                      ? 'Cancel'
                      : '取消',
                ),
              ),
            if (_isEditing) const SizedBox(width: 10),
            FilledButton.icon(
              key: ValueKey(
                _isEditing
                    ? 'workspace-inline-preview-save'
                    : 'workspace-inline-preview-edit',
              ),
              onPressed: _isEditing
                  ? (_isSaving ? null : _handleSaveText)
                  : _handleEditPressed,
              icon: _isEditing
                  ? (_isSaving
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.save_outlined))
                  : const Icon(Icons.edit_outlined),
              label: Text(
                _isEditing
                    ? (Localizations.localeOf(context).languageCode == 'en'
                          ? 'Save'
                          : '保存')
                    : (Localizations.localeOf(context).languageCode == 'en'
                          ? 'Edit'
                          : '编辑'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBody(BuildContext context) {
    if (!widget.metadata.exists) {
      return Center(
        child: Text(
          Localizations.localeOf(context).languageCode == 'en'
              ? 'File does not exist'
              : '文件不存在',
        ),
      );
    }
    if (_error != null) {
      return Center(child: Text(_error!));
    }
    if (_isEditing) {
      return _buildEditor();
    }
    switch (widget.metadata.previewKind) {
      case 'image':
        return OmnibotInteractiveImageView(
          key: const ValueKey('workspace-inline-image-view'),
          source: FileImageSource(widget.metadata.path),
          enableFileShareOnLongPress: true,
          viewportFraction: 1.0,
        );
      case 'audio':
      case 'video':
      case 'pdf':
      case 'html':
      case 'office_word':
      case 'office_sheet':
      case 'office_slide':
        return _buildInlineResourcePreview(context);
      case 'text':
      case 'code':
        if (_loadingText && _textContent == null) {
          return const Center(child: CircularProgressIndicator.adaptive());
        }
        if (_textContent == null) {
          return Center(
            child: Text(
              Localizations.localeOf(context).languageCode == 'en'
                  ? 'No content'
                  : '暂无内容',
            ),
          );
        }
        if (widget.metadata.mimeType == 'text/markdown') {
          return SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 88),
            child: OmnibotMarkdownBody(
              data: _textContent!,
              baseStyle: const TextStyle(fontSize: 14, height: 1.5),
              selectable: true,
            ),
          );
        }
        return SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 88),
          child: SelectableText(
            _textContent!,
            style: TextStyle(
              fontFamily: _preferMonospace ? 'monospace' : null,
              fontSize: 14,
              height: 1.5,
              color: context.omniPalette.textPrimary,
            ),
          ),
        );
      default:
        return Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.insert_drive_file_outlined, size: 56),
                const SizedBox(height: 12),
                Text(widget.metadata.title, textAlign: TextAlign.center),
                const SizedBox(height: 8),
                Text(
                  widget.metadata.mimeType,
                  style: TextStyle(color: context.omniPalette.textSecondary),
                ),
              ],
            ),
          ),
        );
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Stack(
      children: [
        Positioned.fill(
          child: Material(
            color: Colors.transparent,
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: palette.surfacePrimary.withValues(alpha: 0.76),
              ),
              child: _buildBody(context),
            ),
          ),
        ),
        Positioned.fill(child: _buildActionButtons()),
      ],
    );
  }
}
