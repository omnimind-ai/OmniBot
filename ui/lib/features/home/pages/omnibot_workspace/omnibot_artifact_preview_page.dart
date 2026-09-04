import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:ui/services/chat_detail_sheet_preferences.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/image_preview_overlay.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';
import 'package:ui/widgets/omnibot_resource_widgets.dart';

enum _ArtifactPreviewAction {
  openInBrowser,
  saveFile,
  openWithSystem,
  shareFile,
}

bool shouldOpenOmnibotArtifactInFullPage(String previewKind) {
  return previewKind == 'html';
}

class OmnibotArtifactPreviewPage extends StatefulWidget {
  final String path;
  final String? uri;
  final String title;
  final String previewKind;
  final String mimeType;
  final String? shellPath;
  final bool exists;
  final bool startInEditMode;
  final bool showPathBar;
  final bool appBarPrimary;
  final bool showLeading;
  final bool glassSurface;
  final VoidCallback? onClose;
  final ValueChanged<bool>? onEditingChanged;

  const OmnibotArtifactPreviewPage({
    super.key,
    required this.path,
    required this.title,
    required this.previewKind,
    required this.mimeType,
    this.shellPath,
    this.uri,
    this.exists = true,
    this.startInEditMode = false,
    this.showPathBar = true,
    this.appBarPrimary = true,
    this.showLeading = true,
    this.glassSurface = false,
    this.onClose,
    this.onEditingChanged,
  });

  @override
  State<OmnibotArtifactPreviewPage> createState() =>
      _OmnibotArtifactPreviewPageState();
}

class _OmnibotArtifactPreviewPageState
    extends State<OmnibotArtifactPreviewPage> {
  final TextEditingController _editorController = TextEditingController();

  String? _textContent;
  String? _error;
  bool _loadingText = false;
  bool _isEditing = false;
  bool _isSaving = false;
  bool _isDirty = false;
  bool _allowPop = false;

  bool get _isTextLike =>
      widget.previewKind == 'text' || widget.previewKind == 'code';

  bool get _canEdit => widget.exists && _isTextLike;

  bool get _preferMonospace =>
      widget.previewKind == 'code' ||
      widget.mimeType == 'application/json' ||
      widget.mimeType == 'application/xml' ||
      widget.mimeType == 'application/yaml';

  @override
  void initState() {
    super.initState();
    _isEditing = widget.startInEditMode && _canEdit;
    _editorController.addListener(_handleEditorChanged);
    _loadIfNeeded();
    if (_isEditing) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        widget.onEditingChanged?.call(true);
      });
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
    if (!_isEditing) {
      return;
    }
    final nextDirty = _editorController.text != (_textContent ?? '');
    if (nextDirty == _isDirty || !mounted) {
      return;
    }
    setState(() => _isDirty = nextDirty);
  }

  Future<void> _loadIfNeeded({bool showLoading = true}) async {
    if (!widget.exists || !_isTextLike) return;
    if (showLoading && mounted) {
      setState(() => _loadingText = true);
    }
    try {
      final text = await File(widget.path).readAsString();
      if (!mounted) return;
      final keepDraft = _isEditing && _isDirty;
      if (!keepDraft) {
        _editorController.value = TextEditingValue(
          text: text,
          selection: TextSelection.collapsed(offset: text.length),
        );
      }
      setState(() {
        _textContent = text;
        _error = null;
        _loadingText = false;
        if (!keepDraft) {
          _isDirty = false;
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '读取失败：$e';
        _loadingText = false;
      });
    }
  }

  Future<void> _handleEditPressed() async {
    if (!_canEdit) return;
    if (_textContent == null && !_loadingText) {
      await _loadIfNeeded();
    }
    if (!mounted) return;
    setState(() {
      _isEditing = true;
      _isDirty = false;
      _editorController.value = TextEditingValue(
        text: _textContent ?? '',
        selection: TextSelection.collapsed(offset: (_textContent ?? '').length),
      );
    });
    widget.onEditingChanged?.call(true);
  }

  Future<void> _handleCancelEditing() async {
    if (!_isEditing) return;
    if (_isDirty) {
      final confirmed = await AppDialog.confirm(
        context,
        title: Localizations.localeOf(context).languageCode == 'en'
            ? 'Discard changes'
            : '放弃修改',
        content: Localizations.localeOf(context).languageCode == 'en'
            ? 'There are unsaved changes. Discard them?'
            : '当前有未保存修改，确认放弃吗？',
        cancelText: Localizations.localeOf(context).languageCode == 'en'
            ? 'Keep editing'
            : '继续编辑',
        confirmText: Localizations.localeOf(context).languageCode == 'en'
            ? 'Discard'
            : '放弃',
      );
      if (confirmed != true || !mounted) {
        return;
      }
    }
    setState(() {
      _isEditing = false;
      _isDirty = false;
      _editorController.value = TextEditingValue(
        text: _textContent ?? '',
        selection: TextSelection.collapsed(offset: (_textContent ?? '').length),
      );
    });
    widget.onEditingChanged?.call(false);
  }

  Future<void> _handleSaveText() async {
    if (!_canEdit || _isSaving) return;
    setState(() => _isSaving = true);
    try {
      final savedText = _editorController.text;
      await File(widget.path).writeAsString(savedText);
      if (!mounted) return;
      setState(() {
        _textContent = savedText;
        _isDirty = false;
        _error = null;
      });
      await Future<void>.delayed(const Duration(milliseconds: 250));
      await _loadIfNeeded(showLoading: false);
      if (!mounted) return;
      showToast(
        Localizations.localeOf(context).languageCode == 'en'
            ? 'File saved'
            : '文件已保存',
        type: ToastType.success,
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        Localizations.localeOf(context).languageCode == 'en'
            ? 'Save failed: $error'
            : '保存失败：$error',
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() => _isSaving = false);
      }
    }
  }

  Future<void> _handleOpenWithSystem() async {
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    try {
      final opened = await OmnibotResourceService.openWithSystem(
        sourcePath: widget.path,
        mimeType: widget.mimeType,
      );
      if (!mounted) return;
      if (!opened) {
        showToast(
          isEnglish
              ? 'Open with system failed. Please try again later.'
              : '系统打开失败，请稍后重试',
          type: ToastType.error,
        );
      }
    } catch (error) {
      if (!mounted) return;
      showToast(
        isEnglish ? 'Open with system failed: $error' : '系统打开失败：$error',
        type: ToastType.error,
      );
    }
  }

  Future<void> _handleOpenInBrowser() async {
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    try {
      final opened = await OmnibotResourceService.openInBrowser(
        sourcePath: widget.path,
        mimeType: widget.mimeType,
      );
      if (!mounted) return;
      if (!opened) {
        showToast(
          isEnglish ? 'Browser opening failed' : '浏览器打开失败',
          type: ToastType.error,
        );
      }
    } catch (error) {
      if (!mounted) return;
      showToast(
        isEnglish ? 'Browser opening failed: $error' : '浏览器打开失败：$error',
        type: ToastType.error,
      );
    }
  }

  Future<void> _handleSaveFile() async {
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    try {
      final savedUri = await OmnibotResourceService.saveToLocal(
        sourcePath: widget.path,
        fileName: widget.title,
        mimeType: widget.mimeType,
      );
      if (!mounted) return;
      showToast(
        savedUri == null
            ? (isEnglish ? 'Save failed' : '保存失败')
            : (isEnglish ? 'Saved to device' : '已保存到设备'),
        type: savedUri == null ? ToastType.error : ToastType.success,
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        isEnglish ? 'Save failed: $error' : '保存失败：$error',
        type: ToastType.error,
      );
    }
  }

  Future<void> _handleShareFile() async {
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    try {
      final shared = await OmnibotResourceService.shareFile(
        sourcePath: widget.path,
        fileName: widget.title,
        mimeType: widget.mimeType,
      );
      if (!mounted) return;
      if (!shared) {
        showToast(
          isEnglish ? 'Share failed. Please try again later.' : '分享失败，请稍后重试',
          type: ToastType.error,
        );
      }
    } catch (error) {
      if (!mounted) return;
      showToast(
        isEnglish ? 'Share failed: $error' : '分享失败：$error',
        type: ToastType.error,
      );
    }
  }

  void _handleToolbarAction(_ArtifactPreviewAction action) {
    switch (action) {
      case _ArtifactPreviewAction.openInBrowser:
        unawaited(_handleOpenInBrowser());
        break;
      case _ArtifactPreviewAction.saveFile:
        unawaited(_handleSaveFile());
        break;
      case _ArtifactPreviewAction.openWithSystem:
        unawaited(_handleOpenWithSystem());
        break;
      case _ArtifactPreviewAction.shareFile:
        unawaited(_handleShareFile());
        break;
    }
  }

  Future<void> _handleBackNavigation(bool didPop) async {
    if (didPop || !_isEditing || !_isDirty) {
      return;
    }
    final confirmed = await AppDialog.confirm(
      context,
      title: Localizations.localeOf(context).languageCode == 'en'
          ? 'Exit editing'
          : '退出编辑',
      content: Localizations.localeOf(context).languageCode == 'en'
          ? 'There are unsaved changes. Exit editing?'
          : '当前有未保存修改，确认退出吗？',
      cancelText: Localizations.localeOf(context).languageCode == 'en'
          ? 'Keep editing'
          : '继续编辑',
      confirmText: Localizations.localeOf(context).languageCode == 'en'
          ? 'Exit'
          : '退出',
    );
    if (confirmed != true || !mounted) {
      return;
    }
    setState(() {
      _allowPop = true;
      _isDirty = false;
    });
    Navigator.of(context).maybePop();
  }

  OmnibotResourceMetadata _currentMetadata() {
    return OmnibotResourceService.describePath(
      widget.path,
      uri: widget.uri,
      shellPath: widget.shellPath,
      title: widget.title,
      previewKind: widget.previewKind,
      mimeType: widget.mimeType,
    );
  }

  Widget _buildInlineResourcePreview(BuildContext context) {
    final metadata = _currentMetadata();
    final maxWidth = MediaQuery.sizeOf(context).width - 32;
    final preview = OmnibotInlineResourceEmbed(
      metadata: metadata,
      maxWidth: maxWidth,
      preferredHeight: switch (metadata.previewKind) {
        'pdf' => (MediaQuery.sizeOf(context).height - 220).clamp(320.0, 960.0),
        'html' => (MediaQuery.sizeOf(context).height - 220).clamp(280.0, 960.0),
        _ => null,
      },
    );
    if (metadata.previewKind == 'pdf' || metadata.previewKind == 'html') {
      return Padding(
        padding: const EdgeInsets.all(16),
        child: Center(child: preview),
      );
    }
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: preview,
      ),
    );
  }

  Widget _buildHtmlPreview() {
    final metadata = _currentMetadata();
    return LayoutBuilder(
      builder: (context, constraints) => OmnibotInlineResourceEmbed(
        key: const ValueKey('artifact-preview-html-view'),
        metadata: metadata,
        plainStyle: true,
        maxWidth: constraints.maxWidth,
        preferredHeight: constraints.maxHeight,
      ),
    );
  }

  Widget _buildEditor() {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              key: const ValueKey('artifact-preview-editor-field'),
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
                color: palette.textPrimary,
              ),
              decoration: InputDecoration(
                filled: true,
                fillColor: palette.surfacePrimary,
                hintText: Localizations.localeOf(context).languageCode == 'en'
                    ? 'Enter file content'
                    : '输入文件内容',
                alignLabelWithHint: true,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide(color: palette.borderSubtle),
                ),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide(color: palette.borderSubtle),
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide(color: palette.accentPrimary),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildBody() {
    if (!widget.exists) {
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
      if (_loadingText && _textContent == null) {
        return const Center(child: CircularProgressIndicator());
      }
      return _buildEditor();
    }

    switch (widget.previewKind) {
      case 'image':
        return OmnibotInteractiveImageView(
          key: const ValueKey('artifact-preview-image-view'),
          source: FileImageSource(widget.path),
          enableFileShareOnLongPress: true,
          previewBoundsKey: const ValueKey('artifact-preview-image-bounds'),
        );
      case 'audio':
      case 'video':
      case 'pdf':
        return _buildInlineResourcePreview(context);
      case 'html':
        return _buildHtmlPreview();
      case 'office_word':
      case 'office_sheet':
      case 'office_slide':
        return _buildInlineResourcePreview(context);
      case 'text':
      case 'code':
        if (_loadingText && _textContent == null) {
          return const Center(child: CircularProgressIndicator());
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
        if (widget.mimeType == 'text/markdown') {
          return SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: OmnibotMarkdownBody(
              data: _textContent!,
              baseStyle: const TextStyle(fontSize: 14, height: 1.5),
              selectable: true,
            ),
          );
        }
        return SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: SelectableText(
            _textContent!,
            style: TextStyle(
              fontFamily: _preferMonospace ? 'monospace' : null,
              fontSize: 14,
              height: 1.5,
            ),
          ),
        );
      default:
        return Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Icon(Icons.insert_drive_file_outlined, size: 56),
                const SizedBox(height: 12),
                Text(widget.title, textAlign: TextAlign.center),
                const SizedBox(height: 8),
                Text(
                  widget.mimeType,
                  style: TextStyle(color: context.omniPalette.textSecondary),
                ),
                const SizedBox(height: 16),
                FilledButton.icon(
                  onPressed: _handleOpenWithSystem,
                  icon: const Icon(Icons.open_in_new_outlined),
                  label: Text(
                    Localizations.localeOf(context).languageCode == 'en'
                        ? 'Open with system'
                        : '系统打开',
                  ),
                ),
              ],
            ),
          ),
        );
    }
  }

  List<Widget> _buildActions() {
    final actions = <Widget>[];
    if (_canEdit) {
      if (_isEditing) {
        actions.add(
          IconButton(
            tooltip: '取消编辑',
            onPressed: _handleCancelEditing,
            icon: const Icon(Icons.close_rounded),
          ),
        );
        actions.add(
          IconButton(
            tooltip: '保存文件',
            onPressed: _isSaving ? null : _handleSaveText,
            icon: _isSaving
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.save_outlined),
          ),
        );
      } else {
        actions.add(
          IconButton(
            tooltip: '编辑文件',
            onPressed: _handleEditPressed,
            icon: const Icon(Icons.edit_outlined),
          ),
        );
      }
    }
    if (widget.exists) {
      actions.add(
        PopupMenuButton<_ArtifactPreviewAction>(
          key: const ValueKey('artifact-preview-more-actions'),
          tooltip: Localizations.localeOf(context).languageCode == 'en'
              ? 'More actions'
              : '更多操作',
          splashRadius: 18,
          onSelected: _handleToolbarAction,
          itemBuilder: (context) => [
            if (widget.previewKind == 'html')
              PopupMenuItem<_ArtifactPreviewAction>(
                value: _ArtifactPreviewAction.openInBrowser,
                child: Text(
                  Localizations.localeOf(context).languageCode == 'en'
                      ? 'Open in browser'
                      : '在浏览器打开',
                ),
              ),
            PopupMenuItem<_ArtifactPreviewAction>(
              value: _ArtifactPreviewAction.saveFile,
              child: Text(
                Localizations.localeOf(context).languageCode == 'en'
                    ? 'Save to device'
                    : '保存到设备',
              ),
            ),
            PopupMenuItem<_ArtifactPreviewAction>(
              value: _ArtifactPreviewAction.openWithSystem,
              child: Text(
                Localizations.localeOf(context).languageCode == 'en'
                    ? 'Open with system'
                    : '系统打开',
              ),
            ),
            PopupMenuItem<_ArtifactPreviewAction>(
              value: _ArtifactPreviewAction.shareFile,
              child: Text(
                Localizations.localeOf(context).languageCode == 'en'
                    ? 'Share file'
                    : '分享文件',
              ),
            ),
          ],
          icon: const Icon(Icons.more_horiz_rounded),
        ),
      );
    }
    return actions;
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return PopScope(
      canPop: _allowPop || !(_isEditing && _isDirty),
      onPopInvokedWithResult: (didPop, _) => _handleBackNavigation(didPop),
      child: Scaffold(
        resizeToAvoidBottomInset: !widget.glassSurface,
        backgroundColor: widget.glassSurface
            ? Colors.transparent
            : palette.pageBackground,
        appBar: CommonAppBar(
          title: widget.title,
          primary: widget.appBarPrimary,
          backgroundColor: widget.glassSurface ? Colors.transparent : null,
          showLeading: widget.showLeading,
          onBackPressed: widget.onClose,
          actions: _buildActions(),
        ),
        body: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (widget.showPathBar)
              Container(
                key: const ValueKey('artifact-preview-path-bar'),
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                color: palette.surfaceSecondary,
                child: Text(
                  widget.path,
                  style: TextStyle(fontSize: 12, color: palette.textSecondary),
                ),
              ),
            Expanded(child: _buildBody()),
          ],
        ),
      ),
    );
  }
}

Future<void> showOmnibotArtifactPreviewSheet(
  BuildContext context,
  OmnibotResourceMetadata metadata,
) async {
  await OmnibotResourceService.ensureWorkspacePathsLoaded();
  final uriMetadata = metadata.uri == null
      ? null
      : OmnibotResourceService.resolveUri(metadata.uri!);
  final sourceMetadata = uriMetadata ?? metadata;
  final resolvedMetadata = OmnibotResourceService.describePath(
    sourceMetadata.path,
    uri: sourceMetadata.uri,
    shellPath: sourceMetadata.shellPath,
    title: sourceMetadata.title,
    previewKind: sourceMetadata.previewKind,
    mimeType: sourceMetadata.mimeType,
  );
  if (!await OmnibotResourceService.ensureResourceAccess(
    path: resolvedMetadata.path,
    uri: resolvedMetadata.uri,
  )) {
    return;
  }
  if (resolvedMetadata.isDirectory) {
    await OmnibotResourceService.openWorkspace(
      absolutePath: resolvedMetadata.path,
      shellPath: resolvedMetadata.shellPath,
      uri: resolvedMetadata.uri,
    );
    return;
  }
  if (shouldOpenOmnibotArtifactInFullPage(resolvedMetadata.previewKind)) {
    await OmnibotResourceService.openFilePath(
      resolvedMetadata.path,
      uri: resolvedMetadata.uri,
      title: resolvedMetadata.title,
      previewKind: resolvedMetadata.previewKind,
      mimeType: resolvedMetadata.mimeType,
      shellPath: resolvedMetadata.shellPath,
    );
    return;
  }
  if (!context.mounted) {
    return;
  }

  await showModalBottomSheet<void>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    isDismissible: true,
    enableDrag: false,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black.withValues(alpha: 0.28),
    builder: (sheetContext) {
      return _OmnibotArtifactPreviewSheetFrame(metadata: resolvedMetadata);
    },
  );
}

class _OmnibotArtifactPreviewSheetFrame extends StatefulWidget {
  const _OmnibotArtifactPreviewSheetFrame({required this.metadata});

  final OmnibotResourceMetadata metadata;

  @override
  State<_OmnibotArtifactPreviewSheetFrame> createState() =>
      _OmnibotArtifactPreviewSheetFrameState();
}

class _OmnibotArtifactPreviewSheetFrameState
    extends State<_OmnibotArtifactPreviewSheetFrame> {
  static const double _minHeightFactor = 0.36;
  static const double _editingMinHeightFactor = 0.58;
  static const double _keyboardEditingMinHeightFactor = 0.82;
  static const double _maxHeightFactor = 0.94;

  double? _heightFactor;
  bool _isEditing = false;

  double _initialHeightFactor(double viewportHeight) {
    return viewportHeight < 720 ? 0.72 : 0.62;
  }

  void _handleDragUpdate(DragUpdateDetails details, double availableHeight) {
    if (availableHeight <= 0) {
      return;
    }
    final mediaQuery = MediaQuery.of(context);
    final minHeightFactor = _effectiveMinHeightFactor(
      keyboardVisible: mediaQuery.viewInsets.bottom > 0,
    );
    final delta = details.primaryDelta ?? details.delta.dy;
    setState(() {
      final current =
          (_heightFactor ??
                  ChatDetailSheetPreferences.resolveHeightFactor(
                    fallback: _initialHeightFactor(
                      MediaQuery.sizeOf(context).height,
                    ),
                    min: _minHeightFactor,
                    max: _maxHeightFactor,
                  ))
              .clamp(minHeightFactor, _maxHeightFactor);
      _heightFactor = (current - delta / availableHeight).clamp(
        minHeightFactor,
        _maxHeightFactor,
      );
    });
  }

  double _effectiveMinHeightFactor({required bool keyboardVisible}) {
    if (!_isEditing) {
      return _minHeightFactor;
    }
    return keyboardVisible
        ? _keyboardEditingMinHeightFactor
        : _editingMinHeightFactor;
  }

  double _resolveHeightFactor(MediaQueryData mediaQuery) {
    final minHeightFactor = _effectiveMinHeightFactor(
      keyboardVisible: mediaQuery.viewInsets.bottom > 0,
    );
    return (_heightFactor ??
            ChatDetailSheetPreferences.resolveHeightFactor(
              fallback: _initialHeightFactor(MediaQuery.sizeOf(context).height),
              min: _minHeightFactor,
              max: _maxHeightFactor,
            ))
        .clamp(minHeightFactor, _maxHeightFactor)
        .toDouble();
  }

  void _handleEditingChanged(bool isEditing) {
    if (_isEditing == isEditing) {
      return;
    }
    setState(() {
      _isEditing = isEditing;
    });
  }

  void _persistHeightFactor() {
    if (_isEditing) {
      return;
    }
    final heightFactor = _heightFactor;
    if (heightFactor == null) {
      return;
    }
    unawaited(
      ChatDetailSheetPreferences.saveHeightFactor(
        heightFactor,
        min: _minHeightFactor,
        max: _maxHeightFactor,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final mediaQuery = MediaQuery.of(context);
    final availableHeight = math.max(
      320.0,
      mediaQuery.size.height -
          mediaQuery.padding.top -
          mediaQuery.viewInsets.bottom,
    );
    final heightFactor = _resolveHeightFactor(mediaQuery);
    final sheetHeight = availableHeight * heightFactor;
    const borderRadius = BorderRadius.vertical(top: Radius.circular(24));
    return SafeArea(
      top: false,
      child: AnimatedPadding(
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOutCubic,
        padding: EdgeInsets.only(bottom: mediaQuery.viewInsets.bottom),
        child: TweenAnimationBuilder<double>(
          tween: Tween<double>(end: sheetHeight),
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOutCubic,
          builder: (context, animatedHeight, _) {
            return OmniGlassPanel(
              height: animatedHeight,
              width: double.infinity,
              borderRadius: borderRadius,
              child: Material(
                color: Colors.transparent,
                child: Column(
                  children: [
                    GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onVerticalDragUpdate: (details) =>
                          _handleDragUpdate(details, availableHeight),
                      onVerticalDragEnd: (_) => _persistHeightFactor(),
                      child: SizedBox(
                        height: 22,
                        width: double.infinity,
                        child: Center(
                          child: Container(
                            width: 42,
                            height: 4,
                            decoration: BoxDecoration(
                              color: palette.textSecondary.withValues(
                                alpha: 0.34,
                              ),
                              borderRadius: BorderRadius.circular(999),
                            ),
                          ),
                        ),
                      ),
                    ),
                    Expanded(
                      child: OmnibotArtifactPreviewPage(
                        path: widget.metadata.path,
                        uri: widget.metadata.uri,
                        title: widget.metadata.title,
                        previewKind: widget.metadata.previewKind,
                        mimeType: widget.metadata.mimeType,
                        shellPath: widget.metadata.shellPath,
                        exists: widget.metadata.exists,
                        showPathBar: false,
                        appBarPrimary: false,
                        showLeading: false,
                        glassSurface: true,
                        onEditingChanged: _handleEditingChanged,
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
