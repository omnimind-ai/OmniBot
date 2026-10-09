import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/image_preview_overlay.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';
import 'package:ui/widgets/omnibot_resource_widgets.dart';

class CodexRemoteFilePreviewPage extends StatefulWidget {
  const CodexRemoteFilePreviewPage({
    super.key,
    required this.path,
    this.title,
    this.agentId,
    this.remoteBridgeUrl = '',
    this.remoteBridgeToken = '',
    this.remoteCwd = '',
    this.startInEditMode = false,
  });

  final String path;
  final String? agentId;
  final String? title;
  final String remoteBridgeUrl;
  final String remoteBridgeToken;
  final String remoteCwd;
  final bool startInEditMode;

  @override
  State<CodexRemoteFilePreviewPage> createState() =>
      _CodexRemoteFilePreviewPageState();
}

class _CodexRemoteFilePreviewPageState
    extends State<CodexRemoteFilePreviewPage> {
  final TextEditingController _editorController = TextEditingController();
  CodexRemoteFilePayload? _payload;
  String? _tempPath;
  String? _error;
  bool _loading = true;
  bool _isEditing = false;
  bool _isSaving = false;
  bool _isDirty = false;

  bool get _isEnglish => Localizations.localeOf(context).languageCode == 'en';

  bool get _isTextLike => _payload?.isTextLike == true;

  bool get _canEdit => _payload?.ok == true && _isTextLike;

  bool get _preferMonospace {
    final payload = _payload;
    return payload?.previewKind == 'code' ||
        payload?.mimeType == 'application/json' ||
        payload?.mimeType == 'application/xml' ||
        payload?.mimeType == 'application/yaml';
  }

  @override
  void initState() {
    super.initState();
    _isEditing = widget.startInEditMode;
    _editorController.addListener(_handleEditorChanged);
    unawaited(_loadFile());
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
    final nextDirty = _editorController.text != (_payload?.content ?? '');
    if (nextDirty == _isDirty || !mounted) return;
    setState(() {
      _isDirty = nextDirty;
    });
  }

  Future<void> _loadFile({bool showLoading = true}) async {
    if (showLoading && mounted) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }
    try {
      final payload = await AgentRuntimeService.readRemoteFile(
        agentId: widget.agentId,
        remoteBridgeUrl: widget.remoteBridgeUrl,
        remoteBridgeToken: widget.remoteBridgeToken,
        remoteCwd: widget.remoteCwd,
        path: widget.path,
      );
      String? tempPath;
      if (payload.ok && !payload.isTextLike && payload.bytes != null) {
        tempPath = await _writeTempFile(payload);
      }
      if (!mounted) return;
      final keepDraft = _isEditing && _isDirty;
      if (!keepDraft && payload.isTextLike) {
        final text = payload.content ?? '';
        _editorController.value = TextEditingValue(
          text: text,
          selection: TextSelection.collapsed(offset: text.length),
        );
      }
      setState(() {
        _payload = payload;
        _tempPath = tempPath;
        _loading = false;
        _error = payload.ok
            ? null
            : (payload.error ??
                  (_isEnglish ? 'Failed to load file' : '加载文件失败'));
        if (!keepDraft) {
          _isDirty = false;
          if (_isEditing && !payload.isTextLike) {
            _isEditing = false;
          }
        }
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = error.toString();
      });
    }
  }

  Future<String> _writeTempFile(CodexRemoteFilePayload payload) async {
    final bytes = payload.bytes ?? Uint8List(0);
    final tempDir = await getTemporaryDirectory();
    final safeName = _safeFileName(
      payload.name.isEmpty ? 'remote-file' : payload.name,
    );
    final file = File(
      '${tempDir.path}/omnibot_remote_codex_${DateTime.now().microsecondsSinceEpoch}_$safeName',
    );
    await file.writeAsBytes(bytes, flush: true);
    return file.path;
  }

  String _safeFileName(String name) {
    return name.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_');
  }

  Future<void> _handleEditPressed() async {
    if (!_canEdit) {
      showToast(
        _isEnglish ? 'This remote file is not editable' : '此远程文件不可编辑',
        type: ToastType.warning,
      );
      return;
    }
    setState(() {
      _isEditing = true;
      _isDirty = false;
      final text = _payload?.content ?? '';
      _editorController.value = TextEditingValue(
        text: text,
        selection: TextSelection.collapsed(offset: text.length),
      );
    });
  }

  Future<void> _handleCancelEditing() async {
    if (!_isEditing) return;
    if (_isDirty) {
      final confirmed = await AppDialog.confirm(
        context,
        title: _isEnglish ? 'Discard changes' : '放弃修改',
        content: _isEnglish
            ? 'There are unsaved changes. Discard them?'
            : '当前有未保存修改，确认放弃吗？',
        cancelText: _isEnglish ? 'Keep editing' : '继续编辑',
        confirmText: _isEnglish ? 'Discard' : '放弃',
      );
      if (confirmed != true || !mounted) return;
    }
    setState(() {
      _isEditing = false;
      _isDirty = false;
      final text = _payload?.content ?? '';
      _editorController.value = TextEditingValue(
        text: text,
        selection: TextSelection.collapsed(offset: text.length),
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
      final response = await AgentRuntimeService.writeRemoteFile(
        agentId: widget.agentId,
        remoteBridgeUrl: widget.remoteBridgeUrl,
        remoteBridgeToken: widget.remoteBridgeToken,
        remoteCwd: widget.remoteCwd,
        path: widget.path,
        content: savedText,
      );
      if (response['ok'] != true) {
        throw StateError(response['error']?.toString() ?? 'write failed');
      }
      await _loadFile(showLoading: false);
      if (!mounted) return;
      setState(() {
        _isDirty = false;
        _isEditing = false;
      });
      showToast(
        _isEnglish ? 'Remote file saved' : '远程文件已保存',
        type: ToastType.success,
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        _isEnglish ? 'Save failed: $error' : '保存失败：$error',
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _isSaving = false;
        });
      }
    }
  }

  Widget _buildEditor() {
    final palette = context.omniPalette;
    return Column(
      children: [
        Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          color: palette.surfaceSecondary,
          child: Text(
            _isDirty
                ? (_isEnglish ? 'Editing with unsaved changes' : '编辑中，存在未保存修改')
                : (_isEnglish
                      ? 'Editing remote file. Save writes back to the remote workspace.'
                      : '正在编辑远程文件，保存后会写回远程工作区。'),
            style: TextStyle(fontSize: 12, color: palette.textSecondary),
          ),
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
                fillColor: palette.surfacePrimary,
                hintText: _isEnglish ? 'Enter file content' : '输入文件内容',
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

  Widget _buildTextPreview(CodexRemoteFilePayload payload) {
    final text = payload.content ?? '';
    if (payload.mimeType == 'text/markdown') {
      return SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 88),
        child: OmnibotMarkdownBody(
          data: text,
          baseStyle: const TextStyle(fontSize: 14, height: 1.5),
          selectable: true,
        ),
      );
    }
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 88),
      child: SelectableText(
        text,
        style: TextStyle(
          fontFamily: _preferMonospace ? 'monospace' : null,
          fontSize: 14,
          height: 1.5,
          color: context.omniPalette.textPrimary,
        ),
      ),
    );
  }

  Widget _buildImagePreview(CodexRemoteFilePayload payload) {
    final bytes = payload.bytes;
    if (bytes == null) return _buildUnsupportedPreview(payload);
    final heroTag = 'remote_codex_image_${payload.path}';
    return Center(
      child: GestureDetector(
        onTap: () => ImagePreviewOverlay.show(
          context,
          source: MemoryImageSource(bytes),
          heroTag: heroTag,
        ),
        child: Hero(
          tag: heroTag,
          child: Image.memory(bytes, fit: BoxFit.contain),
        ),
      ),
    );
  }

  Widget _buildInlineResourcePreview(CodexRemoteFilePayload payload) {
    final tempPath = _tempPath;
    if (tempPath == null) return _buildUnsupportedPreview(payload);
    final metadata = OmnibotResourceService.describePath(
      tempPath,
      title: payload.name,
      shellPath: payload.path,
      previewKind: payload.previewKind,
      mimeType: payload.mimeType,
    );
    return LayoutBuilder(
      builder: (context, constraints) {
        final preview = OmnibotInlineResourceEmbed(
          metadata: metadata,
          maxWidth: (constraints.maxWidth - 24).clamp(
            0.0,
            constraints.maxWidth,
          ),
          preferredHeight: switch (payload.previewKind) {
            'pdf' => (constraints.maxHeight - 24).clamp(240.0, 1200.0),
            'html' => (constraints.maxHeight - 24).clamp(280.0, 1200.0),
            _ => null,
          },
        );
        return SingleChildScrollView(
          padding: const EdgeInsets.all(12),
          child: Center(child: preview),
        );
      },
    );
  }

  Widget _buildUnsupportedPreview(CodexRemoteFilePayload payload) {
    final palette = context.omniPalette;
    final sizeText = payload.size == null ? '' : '${payload.size} bytes';
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.insert_drive_file_outlined, size: 56),
            const SizedBox(height: 12),
            Text(
              payload.name.isEmpty ? widget.path : payload.name,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 15,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              [
                payload.mimeType,
                if (sizeText.isNotEmpty) sizeText,
                if (payload.truncated)
                  _isEnglish ? 'too large to preview' : '文件过大，无法预览',
              ].join(' · '),
              textAlign: TextAlign.center,
              style: TextStyle(color: palette.textSecondary, fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator.adaptive());
    }
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.error_outline_rounded, size: 32),
              const SizedBox(height: 10),
              Text(_error!, textAlign: TextAlign.center),
              const SizedBox(height: 12),
              TextButton.icon(
                onPressed: () => unawaited(_loadFile()),
                icon: const Icon(Icons.refresh_rounded, size: 17),
                label: Text(_isEnglish ? 'Retry' : '重试'),
              ),
            ],
          ),
        ),
      );
    }
    final payload = _payload;
    if (payload == null) {
      return Center(child: Text(_isEnglish ? 'No content' : '暂无内容'));
    }
    if (_isEditing) {
      return _buildEditor();
    }
    switch (payload.previewKind) {
      case 'text':
      case 'code':
        return _buildTextPreview(payload);
      case 'image':
        return _buildImagePreview(payload);
      case 'audio':
      case 'video':
      case 'pdf':
      case 'html':
      case 'office_word':
      case 'office_sheet':
      case 'office_slide':
        return _buildInlineResourcePreview(payload);
      default:
        return _buildUnsupportedPreview(payload);
    }
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
                onPressed: _isSaving ? null : _handleCancelEditing,
                icon: const Icon(Icons.close_rounded),
                label: Text(_isEnglish ? 'Cancel' : '取消'),
              ),
            if (_isEditing) const SizedBox(width: 10),
            FilledButton.icon(
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
                    ? (_isEnglish ? 'Save' : '保存')
                    : (_isEnglish ? 'Edit' : '编辑'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final title = widget.title ?? widget.path.split('/').last;
    return PopScope(
      canPop: !_isEditing || !_isDirty,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        unawaited(_handleCancelEditing());
      },
      child: Scaffold(
        backgroundColor: context.omniPalette.pageBackground,
        appBar: CommonAppBar(
          title: title,
          primary: true,
          actions: [
            IconButton(
              tooltip: _isEnglish ? 'Reload' : '刷新',
              onPressed: _loading ? null : () => unawaited(_loadFile()),
              icon: const Icon(Icons.refresh_rounded),
            ),
          ],
        ),
        body: Stack(
          children: [
            Positioned.fill(child: _buildBody()),
            Positioned.fill(child: _buildActionButtons()),
          ],
        ),
      ),
    );
  }
}
