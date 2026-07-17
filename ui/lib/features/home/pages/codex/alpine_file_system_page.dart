import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/services/alpine_file_system_service.dart';
import 'package:ui/theme/theme_context.dart';

class AlpineFileSystemPage extends StatefulWidget {
  const AlpineFileSystemPage({super.key, this.initialPath = '/'});

  final String initialPath;

  @override
  State<AlpineFileSystemPage> createState() => _AlpineFileSystemPageState();
}

class _AlpineFileSystemPageState extends State<AlpineFileSystemPage> {
  late String _path;
  List<AlpineFileEntry> _entries = const <AlpineFileEntry>[];
  bool _loading = true;
  String? _error;

  bool get _isEnglish => Localizations.localeOf(context).languageCode == 'en';
  String _text({required String zh, required String en}) =>
      _isEnglish ? en : zh;

  @override
  void initState() {
    super.initState();
    _path = _normalizePath(widget.initialPath);
    unawaited(_load());
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final entries = await AlpineFileSystemService.list(_path);
      if (!mounted) return;
      setState(() {
        _entries = entries;
        _loading = false;
      });
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = error.message ?? error.code;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = error.toString();
      });
    }
  }

  void _openDirectory(String path) {
    setState(() => _path = _normalizePath(path));
    unawaited(_load());
  }

  void _openParent() {
    if (_path == '/') return;
    final index = _path.lastIndexOf('/');
    _openDirectory(index <= 0 ? '/' : _path.substring(0, index));
  }

  Future<void> _jumpToPath() async {
    final controller = TextEditingController(text: _path);
    final selected = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(_text(zh: '打开路径', en: 'Open path')),
        content: TextField(
          key: const Key('alpine-fs-path-field'),
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(hintText: '/root/.codex'),
          onSubmitted: (value) => Navigator.of(context).pop(value),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(_text(zh: '取消', en: 'Cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text),
            child: Text(_text(zh: '打开', en: 'Open')),
          ),
        ],
      ),
    );
    controller.dispose();
    if (selected == null || selected.trim().isEmpty || !mounted) return;
    _openDirectory(selected);
  }

  Future<void> _createEntry({required bool directory}) async {
    final name = await _promptName(
      title: directory
          ? _text(zh: '新建文件夹', en: 'New folder')
          : _text(zh: '新建文件', en: 'New file'),
    );
    if (name == null) return;
    final target = _joinPath(_path, name);
    await _runMutation(() async {
      if (directory) {
        await AlpineFileSystemService.createDirectory(target);
      } else {
        await AlpineFileSystemService.createFile(target);
      }
    });
  }

  Future<void> _rename(AlpineFileEntry entry) async {
    final name = await _promptName(
      title: _text(zh: '重命名', en: 'Rename'),
      initialValue: entry.name,
    );
    if (name == null || name == entry.name) return;
    await _runMutation(
      () => AlpineFileSystemService.move(entry.path, _joinPath(_path, name)),
    );
  }

  Future<void> _delete(AlpineFileEntry entry) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(_text(zh: '删除项目', en: 'Delete item')),
        content: Text(
          _text(
            zh: '确定删除 ${entry.path}？目录会递归删除。',
            en: 'Delete ${entry.path}? Directories are removed recursively.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(_text(zh: '取消', en: 'Cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(_text(zh: '删除', en: 'Delete')),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await _runMutation(() => AlpineFileSystemService.delete(entry.path));
  }

  Future<String?> _promptName({
    required String title,
    String initialValue = '',
  }) async {
    final controller = TextEditingController(text: initialValue);
    final value = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: InputDecoration(
            labelText: _text(zh: '名称', en: 'Name'),
          ),
          onSubmitted: (value) => Navigator.of(context).pop(value),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: Text(_text(zh: '取消', en: 'Cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text),
            child: Text(_text(zh: '确定', en: 'OK')),
          ),
        ],
      ),
    );
    controller.dispose();
    final normalized = value?.trim();
    if (normalized == null ||
        normalized.isEmpty ||
        normalized == '.' ||
        normalized == '..' ||
        normalized.contains('/')) {
      return null;
    }
    return normalized;
  }

  Future<void> _runMutation(Future<void> Function() action) async {
    try {
      await action();
      if (!mounted) return;
      await _load();
    } on PlatformException catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(error.message ?? error.code)));
    }
  }

  Future<void> _openFile(AlpineFileEntry entry) async {
    await Navigator.of(context).push<void>(
      MaterialPageRoute<void>(
        builder: (_) => _AlpineTextFilePage(entry: entry),
      ),
    );
    if (mounted) unawaited(_load());
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return PopScope(
      canPop: _path == '/',
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _openParent();
      },
      child: Scaffold(
        backgroundColor: palette.pageBackground,
        appBar: AppBar(
          title: Text(_text(zh: 'Alpine 文件系统', en: 'Alpine filesystem')),
          actions: [
            IconButton(
              key: const Key('alpine-fs-jump-button'),
              tooltip: _text(zh: '打开路径', en: 'Open path'),
              onPressed: _jumpToPath,
              icon: const Icon(Icons.route_rounded),
            ),
            IconButton(
              tooltip: _text(zh: '刷新', en: 'Refresh'),
              onPressed: _load,
              icon: const Icon(Icons.refresh_rounded),
            ),
          ],
        ),
        body: Column(
          children: [
            _buildBreadcrumbs(),
            Expanded(child: _buildBody()),
          ],
        ),
        floatingActionButton: PopupMenuButton<String>(
          tooltip: _text(zh: '新建', en: 'Create'),
          onSelected: (value) =>
              unawaited(_createEntry(directory: value == 'directory')),
          itemBuilder: (context) => [
            PopupMenuItem(
              value: 'file',
              child: ListTile(
                leading: const Icon(Icons.note_add_outlined),
                title: Text(_text(zh: '新建文件', en: 'New file')),
              ),
            ),
            PopupMenuItem(
              value: 'directory',
              child: ListTile(
                leading: const Icon(Icons.create_new_folder_outlined),
                title: Text(_text(zh: '新建文件夹', en: 'New folder')),
              ),
            ),
          ],
          child: const FloatingActionButton(
            onPressed: null,
            child: Icon(Icons.add_rounded),
          ),
        ),
      ),
    );
  }

  Widget _buildBreadcrumbs() {
    final segments = _path == '/'
        ? const <String>[]
        : _path.substring(1).split('/');
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(
          children: [
            ActionChip(
              label: const Text('/'),
              onPressed: _path == '/' ? null : () => _openDirectory('/'),
            ),
            for (var index = 0; index < segments.length; index++) ...[
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 3),
                child: Icon(Icons.chevron_right_rounded, size: 18),
              ),
              ActionChip(
                label: Text(segments[index]),
                onPressed: index == segments.length - 1
                    ? null
                    : () => _openDirectory(
                        '/${segments.take(index + 1).join('/')}',
                      ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_error!, textAlign: TextAlign.center),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: _load,
                child: Text(_text(zh: '重试', en: 'Retry')),
              ),
            ],
          ),
        ),
      );
    }
    if (_entries.isEmpty) {
      return Center(
        child: Text(_text(zh: '目录为空', en: 'Empty directory')),
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.builder(
        itemCount: _entries.length,
        itemBuilder: (context, index) {
          final entry = _entries[index];
          return ListTile(
            key: ValueKey('alpine-fs-entry-${entry.path}'),
            leading: Icon(
              entry.isDirectory
                  ? Icons.folder_rounded
                  : entry.isLink
                  ? Icons.link_rounded
                  : Icons.insert_drive_file_outlined,
            ),
            title: Text(
              entry.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            subtitle: Text(
              _entrySubtitle(entry),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            onTap: entry.isDirectory
                ? () => _openDirectory(entry.path)
                : entry.readable
                ? () => unawaited(_openFile(entry))
                : null,
            trailing: PopupMenuButton<String>(
              onSelected: (value) {
                if (value == 'copy') {
                  Clipboard.setData(ClipboardData(text: entry.path));
                } else if (value == 'rename') {
                  unawaited(_rename(entry));
                } else if (value == 'delete') {
                  unawaited(_delete(entry));
                }
              },
              itemBuilder: (context) => [
                PopupMenuItem(
                  value: 'copy',
                  child: Text(_text(zh: '复制路径', en: 'Copy path')),
                ),
                PopupMenuItem(
                  value: 'rename',
                  child: Text(_text(zh: '重命名', en: 'Rename')),
                ),
                PopupMenuItem(
                  value: 'delete',
                  child: Text(_text(zh: '删除', en: 'Delete')),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  String _entrySubtitle(AlpineFileEntry entry) {
    final parts = <String>[
      if (entry.mode.isNotEmpty) entry.mode,
      if (!entry.isDirectory) _formatBytes(entry.size),
      if (entry.isLink && entry.linkTarget.isNotEmpty) '→ ${entry.linkTarget}',
    ];
    return parts.join(' · ');
  }

  static String _normalizePath(String value) {
    final parts = <String>[];
    for (final segment in value.trim().replaceAll('\\', '/').split('/')) {
      if (segment.isEmpty || segment == '.') continue;
      if (segment == '..') {
        if (parts.isNotEmpty) parts.removeLast();
      } else {
        parts.add(segment);
      }
    }
    return parts.isEmpty ? '/' : '/${parts.join('/')}';
  }

  static String _joinPath(String parent, String name) =>
      parent == '/' ? '/$name' : '$parent/$name';

  static String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}

class _AlpineTextFilePage extends StatefulWidget {
  const _AlpineTextFilePage({required this.entry});

  final AlpineFileEntry entry;

  @override
  State<_AlpineTextFilePage> createState() => _AlpineTextFilePageState();
}

class _AlpineTextFilePageState extends State<_AlpineTextFilePage> {
  final TextEditingController _controller = TextEditingController();
  bool _loading = true;
  bool _saving = false;
  bool _truncated = false;
  String? _error;

  bool get _isEnglish => Localizations.localeOf(context).languageCode == 'en';
  String _text({required String zh, required String en}) =>
      _isEnglish ? en : zh;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final value = await AlpineFileSystemService.read(widget.entry.path);
      if (!mounted) return;
      _controller.text = value.content;
      setState(() {
        _loading = false;
        _truncated = value.truncated;
      });
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = error.message ?? error.code;
      });
    }
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      await AlpineFileSystemService.write(widget.entry.path, _controller.text);
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_text(zh: '已保存', en: 'Saved')),
        ),
      );
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(error.message ?? error.code)));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.entry.name),
        actions: [
          IconButton(
            tooltip: _text(zh: '复制路径', en: 'Copy path'),
            onPressed: () =>
                Clipboard.setData(ClipboardData(text: widget.entry.path)),
            icon: const Icon(Icons.content_copy_rounded),
          ),
          IconButton(
            key: const Key('alpine-fs-save-button'),
            tooltip: _text(zh: '保存', en: 'Save'),
            onPressed: _loading || _saving || _truncated ? null : _save,
            icon: _saving
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.save_rounded),
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
          ? Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text(_error!),
              ),
            )
          : Column(
              children: [
                if (_truncated)
                  MaterialBanner(
                    content: Text(
                      _text(
                        zh: '文件超过 1 MB，当前只显示前 1 MB。',
                        en: 'The file is larger than 1 MB. Only the first 1 MB is shown.',
                      ),
                    ),
                    actions: const <Widget>[SizedBox.shrink()],
                  ),
                Expanded(
                  child: TextField(
                    key: const Key('alpine-fs-editor'),
                    controller: _controller,
                    readOnly: _truncated || !widget.entry.writable,
                    expands: true,
                    maxLines: null,
                    minLines: null,
                    textAlignVertical: TextAlignVertical.top,
                    style: const TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 13,
                      height: 1.4,
                    ),
                    decoration: const InputDecoration(
                      border: InputBorder.none,
                      contentPadding: EdgeInsets.all(14),
                    ),
                  ),
                ),
              ],
            ),
    );
  }
}
