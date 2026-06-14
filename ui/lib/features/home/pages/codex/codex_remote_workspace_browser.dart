import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/home/pages/codex/codex_remote_file_preview_page.dart';
import 'package:ui/services/codex_app_server_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/app_background_widgets.dart';

class _RemoteWorkspaceBreadcrumbSegment {
  const _RemoteWorkspaceBreadcrumbSegment({
    required this.label,
    required this.path,
    required this.isCurrent,
  });

  final String label;
  final String path;
  final bool isCurrent;
}

enum _RemoteWorkspaceEntryAction {
  sendToCurrentConversation,
  open,
  edit,
  rename,
  delete,
  copyPath,
}

class CodexRemoteWorkspaceBrowser extends StatefulWidget {
  const CodexRemoteWorkspaceBrowser({
    super.key,
    required this.workspacePath,
    this.remoteBridgeUrl = '',
    this.remoteBridgeToken = '',
    this.enableSystemBackHandler = true,
    this.translucentSurfaces = false,
    this.onCanGoUpChanged,
    this.showBreadcrumbHeader = true,
    this.showHeaderTitle = false,
    this.onSendFileToCurrentConversation,
  });

  final String workspacePath;
  final String remoteBridgeUrl;
  final String remoteBridgeToken;
  final bool enableSystemBackHandler;
  final bool translucentSurfaces;
  final ValueChanged<bool>? onCanGoUpChanged;
  final bool showBreadcrumbHeader;
  final bool showHeaderTitle;
  final FutureOr<void> Function(CodexRemoteDirectoryEntry entry)?
      onSendFileToCurrentConversation;

  @override
  State<CodexRemoteWorkspaceBrowser> createState() =>
      CodexRemoteWorkspaceBrowserState();
}

class CodexRemoteWorkspaceBrowserState
    extends State<CodexRemoteWorkspaceBrowser> {
  static const double _itemHeight = 48;
  static const double _itemCornerRadius = 10;

  CodexRemoteDirectoryList? _listing;
  List<CodexRemoteDirectoryEntry> _entries = const [];
  String _rootPath = '';
  String _currentPath = '';
  bool _isLoading = true;
  bool _isReloading = false;
  String? _error;
  int _requestSerial = 0;

  bool get _isEnglish => Localizations.localeOf(context).languageCode == 'en';

  bool get canGoUp {
    final root = _normalizePath(_rootPath);
    final current = _normalizePath(_currentPath);
    return root.isNotEmpty && current.isNotEmpty && current != root;
  }

  Color _surfaceColor({double opacity = 0.8}) {
    return backgroundSurfaceColor(
      translucent: widget.translucentSurfaces,
      baseColor: context.omniPalette.surfacePrimary,
      opacity: opacity,
    );
  }

  @override
  void initState() {
    super.initState();
    _rootPath = _normalizePath(widget.workspacePath.trim());
    _currentPath = _rootPath;
    _notifyCanGoUpChanged();
    unawaited(_loadDirectory(_currentPath));
  }

  @override
  void didUpdateWidget(covariant CodexRemoteWorkspaceBrowser oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.workspacePath == widget.workspacePath &&
        oldWidget.remoteBridgeUrl == widget.remoteBridgeUrl &&
        oldWidget.remoteBridgeToken == widget.remoteBridgeToken) {
      return;
    }
    _rootPath = _normalizePath(widget.workspacePath.trim());
    _currentPath = _rootPath;
    _entries = const [];
    _listing = null;
    _error = null;
    _notifyCanGoUpChanged();
    unawaited(_loadDirectory(_currentPath));
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

  Future<void> _loadDirectory(String path, {bool silent = false}) async {
    if (!mounted) return;
    final requestId = ++_requestSerial;
    setState(() {
      if (silent) {
        _isReloading = true;
      } else {
        _isLoading = true;
      }
      _error = null;
    });
    try {
      final listing = await CodexAppServerService.listRemoteDirectories(
        remoteBridgeUrl: widget.remoteBridgeUrl,
        remoteBridgeToken: widget.remoteBridgeToken,
        remoteCwd: _rootPath,
        path: path.trim().isEmpty ? null : path,
      );
      if (!mounted || requestId != _requestSerial) return;
      final resolvedPath = _normalizePath(
        listing.path.isNotEmpty ? listing.path : path,
      );
      final requestedPath = _normalizePath(path);
      final resolvedRoot = _rootPath.isEmpty
          ? _normalizePath(
              (listing.cwd?.trim().isNotEmpty == true
                      ? listing.cwd
                      : resolvedPath) ??
                  resolvedPath,
            )
          : (requestedPath.isEmpty || requestedPath == _rootPath)
          ? (resolvedPath.isNotEmpty ? resolvedPath : _rootPath)
          : _rootPath;
      setState(() {
        _listing = listing;
        _rootPath = resolvedRoot;
        _currentPath = resolvedPath.isNotEmpty ? resolvedPath : resolvedRoot;
        _entries = listing.ok ? listing.entries : const [];
        _isLoading = false;
        _isReloading = false;
        _error = listing.ok
            ? null
            : (listing.error ??
                  (_isEnglish
                      ? 'Failed to read remote workspace'
                      : '读取远程工作区失败'));
      });
      _notifyCanGoUpChanged();
    } catch (error) {
      if (!mounted || requestId != _requestSerial) return;
      setState(() {
        _isLoading = false;
        _isReloading = false;
        _error = error.toString();
      });
      _notifyCanGoUpChanged();
    }
  }

  Future<void> _reload() {
    return _loadDirectory(_currentPath, silent: true);
  }

  void openParentDirectory() {
    if (!canGoUp) return;
    final parent = _parentPath(_currentPath);
    if (parent == null || !_isInsideWorkspace(parent)) return;
    unawaited(_loadDirectory(parent));
  }

  void _openDirectory(CodexRemoteDirectoryEntry entry) {
    if (!entry.isDirectory) return;
    final path = _normalizePath(entry.path);
    if (!_isInsideWorkspace(path)) return;
    unawaited(_loadDirectory(path));
  }

  Future<void> _copyRemotePath(CodexRemoteDirectoryEntry entry) async {
    await Clipboard.setData(ClipboardData(text: entry.path));
    if (!mounted) return;
    showToast(
      _isEnglish ? 'Remote path copied' : '已复制远程路径',
      type: ToastType.success,
    );
  }

  Future<void> _openFileEntry(
    CodexRemoteDirectoryEntry entry, {
    bool startInEditMode = false,
  }) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => CodexRemoteFilePreviewPage(
          path: entry.path,
          title: entry.name,
          remoteBridgeUrl: widget.remoteBridgeUrl,
          remoteBridgeToken: widget.remoteBridgeToken,
          remoteCwd: _rootPath,
          startInEditMode: startInEditMode,
        ),
      ),
    );
    if (mounted) {
      unawaited(_reload());
    }
  }

  Future<void> _showEntryActionSheet(CodexRemoteDirectoryEntry entry) async {
    final palette = context.omniPalette;
    final isDirectory = entry.isDirectory;
    final canSendToConversation =
        !isDirectory && widget.onSendFileToCurrentConversation != null;
    final action = await showModalBottomSheet<_RemoteWorkspaceEntryAction>(
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
                  entry.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                    fontFamily: 'PingFang SC',
                  ),
                ),
                const SizedBox(height: 12),
                if (canSendToConversation) ...[
                  _buildActionTile(
                    context: sheetContext,
                    icon: Icons.add_comment_outlined,
                    label: _isEnglish ? 'Send to chat' : '发送至对话',
                    action:
                        _RemoteWorkspaceEntryAction.sendToCurrentConversation,
                  ),
                  const SizedBox(height: 8),
                ],
                if (!isDirectory) ...[
                  _buildActionTile(
                    context: sheetContext,
                    icon: Icons.visibility_outlined,
                    label: _isEnglish ? 'Open' : '打开',
                    action: _RemoteWorkspaceEntryAction.open,
                  ),
                  const SizedBox(height: 8),
                  _buildActionTile(
                    context: sheetContext,
                    icon: Icons.edit_outlined,
                    label: _isEnglish ? 'Edit' : '编辑',
                    action: _RemoteWorkspaceEntryAction.edit,
                  ),
                  const SizedBox(height: 8),
                ],
                _buildActionTile(
                  context: sheetContext,
                  icon: Icons.drive_file_rename_outline_rounded,
                  label: _isEnglish ? 'Rename' : '重命名',
                  action: _RemoteWorkspaceEntryAction.rename,
                ),
                const SizedBox(height: 8),
                _buildActionTile(
                  context: sheetContext,
                  icon: Icons.copy_rounded,
                  label: _isEnglish ? 'Copy path' : '复制路径',
                  action: _RemoteWorkspaceEntryAction.copyPath,
                ),
                const SizedBox(height: 8),
                _buildActionTile(
                  context: sheetContext,
                  icon: Icons.delete_outline_rounded,
                  label: _isEnglish ? 'Delete' : '删除',
                  action: _RemoteWorkspaceEntryAction.delete,
                  destructive: true,
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
                    _isEnglish ? 'Cancel' : '取消',
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
    switch (action) {
      case _RemoteWorkspaceEntryAction.sendToCurrentConversation:
        await _sendEntryToCurrentConversation(entry);
      case _RemoteWorkspaceEntryAction.open:
        await _openFileEntry(entry);
      case _RemoteWorkspaceEntryAction.edit:
        await _openFileEntry(entry, startInEditMode: true);
      case _RemoteWorkspaceEntryAction.rename:
        await _promptRenameEntry(entry);
      case _RemoteWorkspaceEntryAction.delete:
        await _confirmAndDeleteEntry(entry);
      case _RemoteWorkspaceEntryAction.copyPath:
        await _copyRemotePath(entry);
    }
  }

  Future<void> _sendEntryToCurrentConversation(
    CodexRemoteDirectoryEntry entry,
  ) async {
    final callback = widget.onSendFileToCurrentConversation;
    if (callback == null) return;
    if (entry.isDirectory) {
      showToast(
        _isEnglish
            ? 'Only files can be sent to the current chat'
            : '只能发送文件到当前对话',
        type: ToastType.warning,
      );
      return;
    }
    try {
      await callback(entry);
    } catch (error) {
      showToast(
        _isEnglish ? 'Failed to send file: $error' : '发送文件失败：$error',
        type: ToastType.error,
      );
    }
  }

  Color _secondarySurfaceColor({double opacity = 0.64}) {
    final palette = context.omniPalette;
    return widget.translucentSurfaces
        ? palette.surfaceSecondary.withValues(alpha: opacity)
        : palette.surfaceSecondary;
  }

  Widget _buildActionTile({
    required BuildContext context,
    required IconData icon,
    required String label,
    required _RemoteWorkspaceEntryAction action,
    bool destructive = false,
  }) {
    final palette = this.context.omniPalette;
    final color = destructive ? const Color(0xFFE53935) : palette.textPrimary;
    return ListTile(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      tileColor: _secondarySurfaceColor(),
      leading: Icon(icon, color: color),
      title: Text(
        label,
        style: TextStyle(
          color: color,
          fontWeight: FontWeight.w600,
          fontFamily: 'PingFang SC',
        ),
      ),
      onTap: () => Navigator.of(context).pop(action),
    );
  }

  Future<void> _promptRenameEntry(CodexRemoteDirectoryEntry entry) async {
    final oldName = entry.name;
    final nextName = (await AppDialog.input(
      context,
      title: entry.isDirectory
          ? (_isEnglish ? 'Rename folder' : '重命名文件夹')
          : (_isEnglish ? 'Rename file' : '重命名文件'),
      hintText: _isEnglish ? 'New name' : '请输入新名称',
      initialValue: oldName,
      confirmText: _isEnglish ? 'Save' : '保存',
      cancelText: _isEnglish ? 'Cancel' : '取消',
    ))?.trim();
    if (nextName == null) return;
    final validationError = _validateEntryName(nextName);
    if (validationError != null) {
      showToast(validationError, type: ToastType.warning);
      return;
    }
    if (nextName == oldName) {
      showToast(_isEnglish ? 'Name unchanged' : '名称未发生变化');
      return;
    }
    final parentPath = _parentPath(entry.path);
    if (parentPath == null) {
      showToast(
        _isEnglish ? 'Rename failed: invalid path' : '重命名失败：路径无效',
        type: ToastType.error,
      );
      return;
    }
    final destinationPath = '$parentPath/$nextName';
    try {
      final result = await CodexAppServerService.moveRemotePath(
        remoteBridgeUrl: widget.remoteBridgeUrl,
        remoteBridgeToken: widget.remoteBridgeToken,
        remoteCwd: _rootPath,
        path: entry.path,
        destinationPath: destinationPath,
      );
      if (result['ok'] != true) {
        throw StateError(result['error']?.toString() ?? 'rename failed');
      }
      showToast(_isEnglish ? 'Renamed' : '重命名成功', type: ToastType.success);
      await _reload();
    } catch (error) {
      if (!mounted) return;
      showToast(
        _isEnglish ? 'Rename failed: $error' : '重命名失败：$error',
        type: ToastType.error,
      );
    }
  }

  Future<void> _confirmAndDeleteEntry(CodexRemoteDirectoryEntry entry) async {
    final confirmed = await AppDialog.confirm(
      context,
      title: entry.isDirectory
          ? (_isEnglish ? 'Delete folder' : '删除文件夹')
          : (_isEnglish ? 'Delete file' : '删除文件'),
      content: _isEnglish
          ? 'Delete "${entry.name}" from the remote PC? This cannot be undone.'
          : '确认从远程 PC 删除“${entry.name}”？删除后不可恢复。',
      cancelText: _isEnglish ? 'Cancel' : '取消',
      confirmText: _isEnglish ? 'Delete' : '删除',
      confirmButtonColor: const Color(0xFFE53935),
    );
    if (confirmed != true) return;
    try {
      final result = await CodexAppServerService.deleteRemotePath(
        remoteBridgeUrl: widget.remoteBridgeUrl,
        remoteBridgeToken: widget.remoteBridgeToken,
        remoteCwd: _rootPath,
        path: entry.path,
        recursive: entry.isDirectory,
      );
      if (result['ok'] != true) {
        throw StateError(result['error']?.toString() ?? 'delete failed');
      }
      showToast(
        entry.isDirectory
            ? (_isEnglish ? 'Folder deleted' : '文件夹已删除')
            : (_isEnglish ? 'File deleted' : '文件已删除'),
        type: ToastType.success,
      );
      await _reload();
    } catch (error) {
      if (!mounted) return;
      showToast(
        _isEnglish ? 'Delete failed: $error' : '删除失败：$error',
        type: ToastType.error,
      );
    }
  }

  String? _validateEntryName(String name) {
    if (name.trim().isEmpty) return _isEnglish ? 'Name is required' : '名称不能为空';
    if (name == '.' || name == '..') {
      return _isEnglish ? 'Name cannot be . or ..' : '名称不能为 . 或 ..';
    }
    if (name.contains('/')) {
      return _isEnglish ? 'Name cannot contain /' : '名称不能包含 /';
    }
    if (name.contains('\\')) {
      return _isEnglish ? 'Name cannot contain \\' : '名称不能包含 "\\"';
    }
    if (name.contains('\u0000')) {
      return _isEnglish ? 'Name contains invalid characters' : '名称包含非法字符';
    }
    return null;
  }

  String get _rootBreadcrumbLabel {
    final root = _normalizePath(_rootPath);
    if (root.isEmpty) {
      return _isEnglish ? 'Remote workspace' : '远程工作区';
    }
    return _entryNameFromPath(root).isEmpty ? root : _entryNameFromPath(root);
  }

  List<_RemoteWorkspaceBreadcrumbSegment> get _breadcrumbs {
    final root = _normalizePath(_rootPath);
    final current = _normalizePath(_currentPath);
    if (root.isEmpty || current.isEmpty) {
      return const <_RemoteWorkspaceBreadcrumbSegment>[];
    }
    if (!_isInsideWorkspace(current)) {
      return <_RemoteWorkspaceBreadcrumbSegment>[
        _RemoteWorkspaceBreadcrumbSegment(
          label: current,
          path: current,
          isCurrent: true,
        ),
      ];
    }

    final segments = <_RemoteWorkspaceBreadcrumbSegment>[
      _RemoteWorkspaceBreadcrumbSegment(
        label: _rootBreadcrumbLabel,
        path: root,
        isCurrent: current == root,
      ),
    ];
    if (current == root) return segments;

    final relative = current.substring(root.length);
    final parts = relative
        .split(RegExp(r'[/\\]+'))
        .where((segment) => segment.trim().isNotEmpty)
        .toList(growable: false);
    var runningPath = root;
    for (final part in parts) {
      runningPath = '$runningPath/$part';
      segments.add(
        _RemoteWorkspaceBreadcrumbSegment(
          label: part,
          path: runningPath,
          isCurrent: runningPath == current,
        ),
      );
    }
    return segments;
  }

  Widget _buildBreadcrumbHeader() {
    final palette = context.omniPalette;
    final breadcrumbs = _breadcrumbs;
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (widget.showHeaderTitle) ...[
            Text(
              _isEnglish ? 'Remote Workspace' : '远程工作区',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w700,
                color: palette.textPrimary,
              ),
            ),
            const SizedBox(height: 6),
          ],
          Row(
            children: [
              Expanded(
                child: breadcrumbs.isEmpty
                    ? Text(
                        _isEnglish
                            ? 'Loading remote workspace...'
                            : '加载远程工作区中...',
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
                            _buildBreadcrumbChip(breadcrumbs[index]),
                          ],
                        ],
                      ),
              ),
              const SizedBox(width: 8),
              IconButton(
                tooltip: _isEnglish ? 'Reload' : '刷新',
                onPressed: _isLoading || _isReloading
                    ? null
                    : () => unawaited(_reload()),
                icon: _isReloading
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Icon(
                        Icons.refresh_rounded,
                        size: 20,
                        color: palette.textSecondary,
                      ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildBreadcrumbChip(_RemoteWorkspaceBreadcrumbSegment segment) {
    final palette = context.omniPalette;
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: segment.isCurrent
            ? null
            : () => unawaited(_loadDirectory(segment.path)),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 180),
            child: Text(
              segment.label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 12,
                fontWeight: segment.isCurrent
                    ? FontWeight.w600
                    : FontWeight.w500,
                color: segment.isCurrent
                    ? palette.textPrimary
                    : palette.textSecondary,
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildEntryNode({
    required CodexRemoteDirectoryEntry entry,
    required BorderRadius borderRadius,
  }) {
    final palette = context.omniPalette;
    final isDirectory = entry.isDirectory;
    final typeLabel = switch (entry.type) {
      'directory' => _isEnglish ? 'Folder' : '文件夹',
      'symlink' => _isEnglish ? 'Symlink' : '符号链接',
      'file' => _isEnglish ? 'File' : '文件',
      _ => _isEnglish ? 'Item' : '项目',
    };
    return DecoratedBox(
      decoration: BoxDecoration(
        color: _surfaceColor(),
        borderRadius: borderRadius,
      ),
      child: Material(
        color: Colors.transparent,
        borderRadius: borderRadius,
        child: InkWell(
          borderRadius: borderRadius,
          onTap: isDirectory
              ? () => _openDirectory(entry)
              : () => unawaited(_openFileEntry(entry)),
          onLongPress: () => unawaited(_showEntryActionSheet(entry)),
          child: SizedBox(
            height: _itemHeight,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Row(
                children: [
                  Icon(
                    isDirectory
                        ? Icons.folder_outlined
                        : entry.type == 'symlink'
                        ? Icons.link_rounded
                        : Icons.insert_drive_file_outlined,
                    color: isDirectory
                        ? palette.accentPrimary
                        : palette.textPrimary,
                    size: 21,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          entry.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                            color: palette.textPrimary,
                            fontFamily: 'PingFang SC',
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          typeLabel,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 11,
                            color: palette.textTertiary,
                            fontFamily: 'PingFang SC',
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  Icon(
                    isDirectory
                        ? Icons.chevron_right_rounded
                        : Icons.copy_rounded,
                    color: palette.textSecondary,
                    size: 18,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildStatusList({
    required String message,
    IconData? icon,
    VoidCallback? onRetry,
  }) {
    final palette = context.omniPalette;
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      children: [
        SizedBox(
          height: 280,
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (icon != null) ...[
                    Icon(icon, size: 28, color: palette.textSecondary),
                    const SizedBox(height: 10),
                  ],
                  Text(
                    message,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 14,
                    ),
                  ),
                  if (onRetry != null) ...[
                    const SizedBox(height: 12),
                    TextButton.icon(
                      onPressed: onRetry,
                      icon: const Icon(Icons.refresh_rounded, size: 17),
                      label: Text(_isEnglish ? 'Retry' : '重试'),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final exists = _error == null;
    final itemCount = _entries.length;
    final body = RefreshIndicator(
      onRefresh: _reload,
      child: _isLoading && _listing == null
          ? _buildStatusList(
              message: _isEnglish
                  ? 'Loading remote workspace...'
                  : '加载远程工作区中...',
            )
          : !exists
          ? _buildStatusList(
              message: _error!,
              icon: Icons.error_outline_rounded,
              onRetry: () => unawaited(_reload()),
            )
          : itemCount == 0
          ? _buildStatusList(
              message: _isEnglish
                  ? 'Current remote directory is empty'
                  : '当前远程目录为空',
              icon: Icons.folder_open_outlined,
            )
          : ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
              itemCount: itemCount,
              itemBuilder: (context, index) {
                final isFirst = index == 0;
                final isLast = index == itemCount - 1;
                final borderRadius = BorderRadius.vertical(
                  top: isFirst
                      ? const Radius.circular(_itemCornerRadius)
                      : Radius.zero,
                  bottom: isLast
                      ? const Radius.circular(_itemCornerRadius)
                      : Radius.zero,
                );
                return _buildEntryNode(
                  entry: _entries[index],
                  borderRadius: borderRadius,
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

  String _normalizePath(String path) {
    var normalized = path.trim();
    while (normalized.length > 1 &&
        (normalized.endsWith('/') || normalized.endsWith('\\'))) {
      normalized = normalized.substring(0, normalized.length - 1);
    }
    return normalized;
  }

  bool _isInsideWorkspace(String path) {
    final normalizedPath = _normalizePath(path);
    final normalizedRoot = _normalizePath(_rootPath);
    if (normalizedRoot.isEmpty || normalizedPath.isEmpty) {
      return false;
    }
    return normalizedPath == normalizedRoot ||
        normalizedPath.startsWith('$normalizedRoot/') ||
        normalizedPath.startsWith('$normalizedRoot\\');
  }

  String _entryNameFromPath(String path) {
    final normalizedPath = _normalizePath(path);
    final parts = normalizedPath
        .split(RegExp(r'[/\\]+'))
        .where((part) => part.trim().isNotEmpty)
        .toList(growable: false);
    return parts.isEmpty ? normalizedPath : parts.last;
  }

  String? _parentPath(String path) {
    final normalizedPath = _normalizePath(path);
    final slashIndex = normalizedPath.lastIndexOf(RegExp(r'[/\\]'));
    if (slashIndex <= 0) return null;
    return normalizedPath.substring(0, slashIndex);
  }
}
