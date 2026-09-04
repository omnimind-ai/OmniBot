import 'dart:async';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/image_prewarm_cache_service.dart';
import 'package:ui/services/screen_dialog_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/constants/openclaw/openclaw_keys.dart';
import 'package:ui/features/home/pages/common/openclaw_connection_checker.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_flow_controller.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';

import 'chat_bot_sheet.dart';
import 'widgets/chat_input_area.dart';

class CommandOverlay extends StatefulWidget {
  /// 启动场景参数，目前支持 'summary' 场景
  final String? scene;

  const CommandOverlay({super.key, this.scene});

  @override
  State<CommandOverlay> createState() => _CommandOverlayState();
}

class _CommandOverlayState extends State<CommandOverlay> {
  final TextEditingController _messageController = TextEditingController();
  final FocusNode _inputFocusNode = FocusNode();
  final GlobalKey<ChatInputAreaState> _chatInputAreaKey =
      GlobalKey<ChatInputAreaState>();
  final GlobalKey _inputAreaKey = GlobalKey();
  final List<ChatInputAttachment> _pendingAttachments = <ChatInputAttachment>[];

  bool _isPopupVisible = false;
  double _chatInputAreaHeight = 44;
  bool _openClawEnabled = false;
  String _openClawBaseUrl = '';
  String _openClawToken = '';
  String _openClawUserId = '';
  bool _showSlashCommandPanel = false;
  bool _openClawPanelExpanded = false;
  final TextEditingController _openClawBaseUrlController =
      TextEditingController();
  final TextEditingController _openClawTokenController =
      TextEditingController();
  final TextEditingController _openClawUserIdController =
      TextEditingController();
  final GlobalKey _openClawPanelKey = GlobalKey();

  @override
  void initState() {
    super.initState();
    _inputFocusNode.addListener(_onFocusChange);
    _messageController.addListener(_handleSlashCommandInput);
    _loadOpenClawConfig();

    // 预热 Suggestion 图标到内存缓存
    WidgetsBinding.instance.addPostFrameCallback((_) {
      SuggestionImagePrewarmService.prewarm(context, tag: 'CommandOverlay');
    });

    // 如果是总结场景，自动拉起ChatBotSheet
    if (widget.scene == 'summary') {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _showChatSheetWithScene(ChatBotLaunchScene.summary);
      });
    } else if (widget.scene == 'resume_after_auth') {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _showChatSheetWithScene(ChatBotLaunchScene.resumeAfterAuth);
      });
    }
  }

  Future<void> _loadOpenClawConfig() async {
    try {
      final enabled =
          StorageService.getBool(kOpenClawEnabledKey, defaultValue: false) ??
          false;
      final baseUrl =
          StorageService.getString(kOpenClawBaseUrlKey, defaultValue: '') ?? '';
      final token =
          StorageService.getString(kOpenClawTokenKey, defaultValue: '') ?? '';
      final userId =
          StorageService.getString(kOpenClawUserIdKey, defaultValue: '') ?? '';
      final effectiveEnabled = enabled && baseUrl.trim().isNotEmpty;
      if (enabled && !effectiveEnabled) {
        await StorageService.setBool(kOpenClawEnabledKey, false);
      }
      if (!mounted) return;
      setState(() {
        _openClawEnabled = effectiveEnabled;
        _openClawBaseUrl = baseUrl;
        _openClawToken = token;
        _openClawUserId = userId;
      });
      await _ensureOpenClawUserId();
    } catch (e) {
      debugPrint('加载OpenClaw配置失败: $e');
    }
  }

  Future<void> _ensureOpenClawUserId() async {
    if (_openClawUserId.isNotEmpty) return;
    final existing =
        StorageService.getString(kOpenClawUserIdKey, defaultValue: '') ?? '';
    if (existing.isNotEmpty) {
      if (!mounted) return;
      setState(() => _openClawUserId = existing);
      return;
    }
    final generated = DateTime.now().microsecondsSinceEpoch.toString();
    await StorageService.setString(kOpenClawUserIdKey, generated);
    if (!mounted) return;
    setState(() => _openClawUserId = generated);
  }

  Future<void> _setOpenClawEnabled(bool enabled) async {
    if (enabled && _openClawBaseUrl.trim().isEmpty) {
      AppToast.show(LegacyTextLocalizer.localize('请先使用 /openclaw 配置 OpenClaw'));
      _showOpenClawCommandPanel(expand: true);
      return;
    }
    if (!mounted) return;
    setState(() => _openClawEnabled = enabled);
    await StorageService.setBool(kOpenClawEnabledKey, enabled);
  }

  Future<void> _showOpenClawConfigDialog() async {
    final result = await showDialog<_OpenClawConfigDraft>(
      context: context,
      useRootNavigator: false,
      builder: (_) => _OpenClawConfigDialog(
        initialBaseUrl: _openClawBaseUrl,
        initialToken: _openClawToken,
        initialUserId: _openClawUserId,
      ),
    );
    if (!mounted || result == null) return;
    final baseUrl = result.baseUrl.trim();
    final token = result.token.trim();
    final userId = result.userId.trim();
    await StorageService.setString(kOpenClawBaseUrlKey, baseUrl);
    await StorageService.setString(kOpenClawTokenKey, token);
    if (userId.isNotEmpty) {
      await StorageService.setString(kOpenClawUserIdKey, userId);
    }
    if (!mounted) return;
    setState(() {
      _openClawBaseUrl = baseUrl;
      _openClawToken = token;
      _openClawUserId = userId.isNotEmpty ? userId : _openClawUserId;
    });
    await _ensureOpenClawUserId();

    // 配置保存后检查连接
    _checkOpenClawConnection();
  }

  /// 检查 OpenClaw 服务连接状态
  Future<void> _checkOpenClawConnection() async {
    await OpenClawConnectionChecker.checkAndToast(_openClawBaseUrl);
  }

  Widget _buildOpenClawToggle() {
    final palette = context.omniPalette;
    final labelColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF666666);
    return Row(
      children: [
        Text('OpenClaw', style: TextStyle(fontSize: 12, color: labelColor)),
        const SizedBox(width: 8),
        Switch.adaptive(
          value: _openClawEnabled,
          onChanged: (value) => _setOpenClawEnabled(value),
          materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
        ),
        const Spacer(),
        TextButton.icon(
          onPressed: _showOpenClawConfigDialog,
          icon: Icon(Icons.settings, size: 16, color: labelColor),
          label: Text('配置', style: TextStyle(fontSize: 12, color: labelColor)),
          style: TextButton.styleFrom(
            visualDensity: VisualDensity.compact,
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            foregroundColor: labelColor,
          ),
        ),
      ],
    );
  }

  void _handleSlashCommandInput() {
    final text = _messageController.text.trimLeft();
    final shouldShow = text.startsWith('/');
    if (!mounted) return;
    if (shouldShow != _showSlashCommandPanel) {
      setState(() {
        _showSlashCommandPanel = shouldShow;
        if (!shouldShow) {
          _openClawPanelExpanded = false;
        }
      });
    }
  }

  void _showOpenClawCommandPanel({bool expand = false}) {
    if (!mounted) return;
    setState(() {
      _showSlashCommandPanel = true;
      _openClawPanelExpanded = expand;
      if (expand) {
        _openClawBaseUrlController.text = _openClawBaseUrl;
        _openClawTokenController.text = _openClawToken;
        _openClawUserIdController.text = _openClawUserId;
      }
    });
  }

  void _hideSlashCommandPanel() {
    if (!mounted) return;
    setState(() {
      _showSlashCommandPanel = false;
      _openClawPanelExpanded = false;
    });
  }

  Future<void> _startManualRecordingFromCommandPanel() async {
    _messageController.clear();
    _hideSlashCommandPanel();
    await ManualRecordingFlowController.startStandalone(
      context: context,
      inputFocusNode: _inputFocusNode,
      userMessageText: '/record',
      recordDebugScreenshots: true,
      isMounted: () => mounted,
    );
  }

  bool _isPointerInside(GlobalKey key, Offset position) {
    final context = key.currentContext;
    if (context == null) return false;
    final renderBox = context.findRenderObject() as RenderBox?;
    if (renderBox == null || !renderBox.hasSize) return false;
    final offset = renderBox.localToGlobal(Offset.zero);
    final rect = offset & renderBox.size;
    return rect.contains(position);
  }

  Future<void> _handleOutsideTap(Offset position) async {
    if (!_showSlashCommandPanel && !_openClawPanelExpanded) return;
    if (_isPointerInside(_openClawPanelKey, position) ||
        _isPointerInside(_inputAreaKey, position)) {
      return;
    }
    if (_openClawPanelExpanded) {
      await _applyOpenClawConfig(
        baseUrl: _openClawBaseUrlController.text.trim(),
        token: _openClawTokenController.text.trim(),
        userId: _openClawUserIdController.text.trim(),
        enable: _openClawEnabled,
      );
      _checkOpenClawConnection();
    }
    _hideSlashCommandPanel();
  }

  Future<void> _applyOpenClawConfig({
    required String baseUrl,
    required String token,
    String? userId,
    bool enable = true,
  }) async {
    await StorageService.setString(kOpenClawBaseUrlKey, baseUrl);
    await StorageService.setString(kOpenClawTokenKey, token);
    if (userId != null && userId.isNotEmpty) {
      await StorageService.setString(kOpenClawUserIdKey, userId);
    }
    if (!mounted) return;
    setState(() {
      _openClawBaseUrl = baseUrl;
      _openClawToken = token;
      if (userId != null && userId.isNotEmpty) {
        _openClawUserId = userId;
      }
      _openClawEnabled = enable && baseUrl.trim().isNotEmpty;
    });
    await StorageService.setBool(kOpenClawEnabledKey, _openClawEnabled);
    await _ensureOpenClawUserId();
  }

  Future<bool> _tryHandleSlashCommand(String messageText) async {
    final trimmed = messageText.trim();
    if (!trimmed.startsWith('/')) return false;

    if (trimmed == '/record') {
      await _startManualRecordingFromCommandPanel();
      return true;
    }

    // 只拦截 /openclaw 本地配置命令，其他斜杠命令（如 /model、/help 等）
    // 透传给 OpenClaw 网关或作为普通消息发送
    if (!trimmed.startsWith('/openclaw')) {
      return false;
    }

    final parts = trimmed.split(RegExp(r'\\s+'));
    if (parts.length < 2) {
      AppToast.show('格式: /openclaw <baseurl> --token <token> <userid>');
      return true;
    }

    final baseUrl = parts[1];
    final tokenIndex = parts.indexOf('--token');
    if (tokenIndex == -1) {
      AppToast.show('请在命令中显式包含 --token');
      return true;
    }
    String token = '';
    String? userId;
    if (tokenIndex + 1 < parts.length) {
      token = parts[tokenIndex + 1];
    }
    if (token == '-' || token == 'null') {
      token = '';
    }
    if (tokenIndex + 2 < parts.length) {
      userId = parts[tokenIndex + 2];
    }

    if (baseUrl.trim().isEmpty) {
      AppToast.show('OpenClaw baseurl 不能为空');
      return true;
    }

    await _applyOpenClawConfig(
      baseUrl: baseUrl.trim(),
      token: token.trim(),
      userId: userId?.trim(),
      enable: true,
    );
    _messageController.clear();
    _inputFocusNode.unfocus();
    _hideSlashCommandPanel();
    AppToast.show('OpenClaw 已配置并启用');
    return true;
  }

  @override
  void dispose() {
    _messageController.removeListener(_handleSlashCommandInput);
    _messageController.dispose();
    _inputFocusNode.dispose();
    _openClawBaseUrlController.dispose();
    _openClawTokenController.dispose();
    _openClawUserIdController.dispose();
    super.dispose();
  }

  void _onFocusChange() {}

  void _closePage() {
    _inputFocusNode.unfocus();
    ScreenDialogService.closeChatBotDialog();
  }

  Future<void> _sendMessage() async {
    final text = _messageController.text.trim();
    final hasAttachments = _pendingAttachments.isNotEmpty;
    if (text.isEmpty && !hasAttachments) return;

    final handledSlash = await _tryHandleSlashCommand(text);
    if (handledSlash) return;

    if (!hasAttachments && ManualRecordingFlowController.isCommand(text)) {
      if (!mounted) return;
      _messageController.clear();
      await ManualRecordingFlowController.startStandalone(
        context: context,
        inputFocusNode: _inputFocusNode,
        userMessageText: text,
        recordDebugScreenshots: true,
        isMounted: () => mounted,
      );
      return;
    }

    final attachments = _pendingAttachments
        .map((item) => item.toMap())
        .toList();
    if (attachments.isNotEmpty && mounted) {
      setState(() => _pendingAttachments.clear());
    }
    _inputFocusNode.unfocus();
    _messageController.clear();

    _showChatSheet(initialMessage: text, initialAttachments: attachments);
  }

  void _showChatSheet({
    String? initialMessage,
    List<Map<String, dynamic>> initialAttachments = const [],
  }) {
    _showChatSheetWithScene(
      ChatBotLaunchScene.normal,
      initialMessage: initialMessage,
      initialAttachments: initialAttachments,
    );
  }

  /// 显示ChatBotSheet，支持指定启动场景
  void _showChatSheetWithScene(
    ChatBotLaunchScene launchScene, {
    String? initialMessage,
    List<Map<String, dynamic>> initialAttachments = const [],
  }) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0),
      // 禁用 showModalBottomSheet 的默认拖动关闭行为
      // 防止向下拖动内容时整个 sheet 跟着移动
      enableDrag: false,
      builder: (context) => ChatBotSheet(
        initialMessage: initialMessage,
        initialAttachments: initialAttachments,
        launchScene: launchScene,
        openClawEnabled: _openClawEnabled,
      ),
    ).then((_) {
      ScreenDialogService.closeChatBotDialog();
    });
  }

  void _onCancelTask() {}

  void _onPopupVisibilityChanged(bool visible) {
    setState(() {
      _isPopupVisible = visible;
    });
  }

  void _onInputHeightChanged(double height) {
    if (_chatInputAreaHeight == height) return;
    setState(() {
      _chatInputAreaHeight = height;
    });
  }

  Future<void> _pickAttachments() async {
    var hiddenForPicker = false;
    try {
      hiddenForPicker = await ScreenDialogService.hideForExternalActivity();
      if (hiddenForPicker) {
        await Future<void>.delayed(const Duration(milliseconds: 80));
      }
      final result = await FilePicker.platform.pickFiles(
        allowMultiple: true,
        type: FileType.any,
      );
      if (result == null || result.files.isEmpty || !mounted) return;

      setState(() {
        for (final file in result.files) {
          // Android file_picker can return a readable content URI in
          // `identifier` while `path` is null (cloud/document providers).
          // Keep that identifier so the ACP boundary can materialize it.
          final path = file.path ?? file.identifier;
          if (path == null || path.isEmpty) continue;
          final exists = _pendingAttachments.any((item) => item.path == path);
          if (exists) continue;
          final displayName = file.name.trim().isNotEmpty
              ? file.name.trim()
              : _fileNameFromPath(path);
          final extension = (file.extension ?? '').toLowerCase();
          final mimeType = _mimeTypeFromExtension(path, extension: extension);
          _pendingAttachments.add(
            ChatInputAttachment(
              id: '${path}_${DateTime.now().microsecondsSinceEpoch}',
              name: displayName,
              path: path,
              size: file.size > 0 ? file.size : null,
              mimeType: mimeType,
              isImage: _isImageFilePath(path, mimeType: mimeType),
            ),
          );
        }
      });
    } catch (e) {
      showToast('添加附件失败：$e', type: ToastType.error);
    } finally {
      if (hiddenForPicker) {
        await Future<void>.delayed(const Duration(milliseconds: 120));
        await ScreenDialogService.restoreAfterExternalActivity();
      }
    }
  }

  void _removePendingAttachment(String id) {
    if (!mounted) return;
    setState(() {
      _pendingAttachments.removeWhere((item) => item.id == id);
    });
  }

  String _fileNameFromPath(String path) {
    final normalized = path.replaceAll('\\', '/');
    final segments = normalized.split('/');
    if (segments.isEmpty) return path;
    return segments.last.isEmpty ? path : segments.last;
  }

  bool _isImageFilePath(String path, {String? mimeType}) {
    final normalizedMime = mimeType?.trim().toLowerCase();
    if (normalizedMime != null && normalizedMime.startsWith('image/')) {
      return true;
    }
    final lowerPath = path.toLowerCase();
    return lowerPath.endsWith('.png') ||
        lowerPath.endsWith('.jpg') ||
        lowerPath.endsWith('.jpeg') ||
        lowerPath.endsWith('.webp') ||
        lowerPath.endsWith('.gif') ||
        lowerPath.endsWith('.bmp') ||
        lowerPath.endsWith('.heic') ||
        lowerPath.endsWith('.heif');
  }

  String? _mimeTypeFromExtension(String path, {String extension = ''}) {
    final ext = extension.isNotEmpty
        ? extension
        : _fileNameFromPath(path).split('.').last.toLowerCase();
    switch (ext) {
      case 'png':
        return 'image/png';
      case 'jpg':
      case 'jpeg':
        return 'image/jpeg';
      case 'gif':
        return 'image/gif';
      case 'webp':
        return 'image/webp';
      case 'bmp':
        return 'image/bmp';
      case 'heic':
        return 'image/heic';
      case 'heif':
        return 'image/heif';
      case 'pdf':
        return 'application/pdf';
      case 'txt':
        return 'text/plain';
      case 'md':
        return 'text/markdown';
      default:
        return null;
    }
  }

  Widget _buildSlashCommandPanel() {
    final visible = _showSlashCommandPanel || _openClawPanelExpanded;
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final panelTextColor = isDark
        ? palette.textPrimary
        : const Color(0xFF1F2937);
    final panelSecondaryTextColor = isDark
        ? palette.textSecondary
        : const Color(0xFF6B7280);
    final panelAccentColor = isDark
        ? palette.accentPrimary
        : const Color(0xFF2563EB);
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 180),
      transitionBuilder: (child, animation) {
        final slide = Tween<Offset>(
          begin: const Offset(0, 0.15),
          end: Offset.zero,
        ).animate(animation);
        return ClipRect(
          child: SlideTransition(
            position: slide,
            child: FadeTransition(opacity: animation, child: child),
          ),
        );
      },
      child: !visible
          ? const SizedBox.shrink()
          : Container(
              key: _openClawPanelKey,
              margin: const EdgeInsets.fromLTRB(24, 0, 24, 6),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: isDark ? palette.surfacePrimary : Colors.white,
                borderRadius: BorderRadius.circular(12),
                border: isDark ? Border.all(color: palette.borderSubtle) : null,
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: isDark ? 0.24 : 0.08),
                    blurRadius: 10,
                    offset: const Offset(0, 4),
                  ),
                ],
              ),
              child: _openClawPanelExpanded
                  ? Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'OpenClaw 配置',
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                            color: panelTextColor,
                          ),
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _openClawBaseUrlController,
                          decoration: const InputDecoration(
                            labelText: 'Base URL',
                            hintText: 'http://192.168.1.10:18789',
                            isDense: true,
                          ),
                        ),
                        const SizedBox(height: 6),
                        TextField(
                          controller: _openClawTokenController,
                          decoration: const InputDecoration(
                            labelText: 'Token（可选）',
                            hintText: '为空表示无需 token',
                            isDense: true,
                          ),
                        ),
                        const SizedBox(height: 6),
                        TextField(
                          controller: _openClawUserIdController,
                          decoration: const InputDecoration(
                            labelText: 'User ID（可选）',
                            isDense: true,
                          ),
                        ),
                      ],
                    )
                  : Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        InkWell(
                          onTap: () => unawaited(
                            _startManualRecordingFromCommandPanel(),
                          ),
                          borderRadius: BorderRadius.circular(10),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 4),
                            child: Row(
                              children: [
                                Icon(
                                  Icons.route_rounded,
                                  size: 16,
                                  color: panelAccentColor,
                                ),
                                const SizedBox(width: 8),
                                Expanded(
                                  child: Text(
                                    '/record',
                                    style: TextStyle(
                                      fontSize: 13,
                                      fontWeight: FontWeight.w600,
                                      color: panelTextColor,
                                    ),
                                  ),
                                ),
                                Text(
                                  '手动录制',
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: panelSecondaryTextColor,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                        Divider(height: 12, color: palette.borderSubtle),
                        InkWell(
                          onTap: () {
                            _showOpenClawCommandPanel(expand: true);
                          },
                          borderRadius: BorderRadius.circular(10),
                          child: Row(
                            children: [
                              Icon(
                                Icons.link,
                                size: 16,
                                color: panelAccentColor,
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  'OpenClaw',
                                  style: TextStyle(
                                    fontSize: 13,
                                    fontWeight: FontWeight.w600,
                                    color: panelTextColor,
                                  ),
                                ),
                              ),
                              Text(
                                '配置',
                                style: TextStyle(
                                  fontSize: 12,
                                  color: panelSecondaryTextColor,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
            ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final keyboardHeight = MediaQuery.of(context).viewInsets.bottom;
    final bottomPadding = keyboardHeight + 20;
    const double inputHeaderOffset = 0;

    final showSlashPanel = _showSlashCommandPanel || _openClawPanelExpanded;
    return Scaffold(
      backgroundColor: Colors.transparent,
      resizeToAvoidBottomInset: false,
      body: Listener(
        behavior: HitTestBehavior.translucent,
        onPointerDown: (event) => _handleOutsideTap(event.position),
        child: Stack(
          children: [
            // 蒙层背景 - 点击关闭页面
            Positioned.fill(
              child: GestureDetector(
                onTap: _closePage,
                behavior: HitTestBehavior.opaque,
                child: Container(color: Colors.black.withValues(alpha: 0)),
              ),
            ),
            // 快捷提示气泡 - 随键盘移动
            Positioned(
              left: 24,
              right: 24,
              bottom: bottomPadding + _chatInputAreaHeight + inputHeaderOffset,
              child: IgnorePointer(
                ignoring: showSlashPanel,
                child: AnimatedOpacity(
                  opacity: showSlashPanel ? 0.0 : 1.0,
                  duration: const Duration(milliseconds: 150),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: const [],
                  ),
                ),
              ),
            ),
            Positioned(
              left: 0,
              right: 0,
              bottom: bottomPadding + _chatInputAreaHeight + inputHeaderOffset,
              child: _buildSlashCommandPanel(),
            ),
            // 底部输入框区域
            Positioned(
              left: 0,
              right: 0,
              bottom: bottomPadding,
              child: Container(
                key: _inputAreaKey,
                padding: const EdgeInsets.symmetric(horizontal: 24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    ChatInputArea(
                      key: _chatInputAreaKey,
                      controller: _messageController,
                      focusNode: _inputFocusNode,
                      isProcessing: false,
                      onSendMessage: _sendMessage,
                      onCancelTask: _onCancelTask,
                      onPopupVisibilityChanged: _onPopupVisibilityChanged,
                      onInputHeightChanged: _onInputHeightChanged,
                      openClawEnabled: _openClawEnabled,
                      onToggleOpenClaw: _setOpenClawEnabled,
                      onLongPressOpenClaw: () =>
                          _showOpenClawCommandPanel(expand: true),
                      useLargeComposerStyle: true,
                      useFrostedGlass: true, // command_overlay 使用毛玻璃效果
                      useAttachmentPickerForPlus: true,
                      onPickAttachment: _pickAttachments,
                      attachments: _pendingAttachments,
                      onRemoveAttachment: _removePendingAttachment,
                    ),
                  ],
                ),
              ),
            ),
            if (_isPopupVisible)
              Positioned(
                right: 24,
                bottom: bottomPadding + 52 + inputHeaderOffset,
                child:
                    _chatInputAreaKey.currentState?.buildPopupMenu() ??
                    const SizedBox.shrink(),
              ),
          ],
        ),
      ),
    );
  }
}

class _OpenClawConfigDraft {
  const _OpenClawConfigDraft({
    required this.baseUrl,
    required this.token,
    required this.userId,
  });

  final String baseUrl;
  final String token;
  final String userId;
}

class _OpenClawConfigDialog extends StatefulWidget {
  const _OpenClawConfigDialog({
    required this.initialBaseUrl,
    required this.initialToken,
    required this.initialUserId,
  });

  final String initialBaseUrl;
  final String initialToken;
  final String initialUserId;

  @override
  State<_OpenClawConfigDialog> createState() => _OpenClawConfigDialogState();
}

class _OpenClawConfigDialogState extends State<_OpenClawConfigDialog> {
  late final TextEditingController _baseUrlController;
  late final TextEditingController _tokenController;
  late final TextEditingController _userIdController;
  final FocusNode _baseUrlFocusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    _baseUrlController = TextEditingController(text: widget.initialBaseUrl);
    _tokenController = TextEditingController(text: widget.initialToken);
    _userIdController = TextEditingController(text: widget.initialUserId);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _baseUrlFocusNode.requestFocus();
      }
    });
  }

  @override
  void dispose() {
    _baseUrlFocusNode.dispose();
    _baseUrlController.dispose();
    _tokenController.dispose();
    _userIdController.dispose();
    super.dispose();
  }

  void _close([_OpenClawConfigDraft? value]) {
    FocusScope.of(context).unfocus();
    Navigator.of(context).pop(value);
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        _close();
      },
      child: AlertDialog(
        title: const Text('OpenClaw 配置'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _baseUrlController,
              focusNode: _baseUrlFocusNode,
              decoration: const InputDecoration(
                labelText: 'Base URL',
                hintText: 'http://192.168.1.10:18789',
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _tokenController,
              decoration: const InputDecoration(labelText: 'Token（可选）'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _userIdController,
              decoration: const InputDecoration(labelText: 'User ID（可选）'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => _close(), child: const Text('取消')),
          ElevatedButton(
            onPressed: () => _close(
              _OpenClawConfigDraft(
                baseUrl: _baseUrlController.text,
                token: _tokenController.text,
                userId: _userIdController.text,
              ),
            ),
            child: const Text('保存'),
          ),
        ],
      ),
    );
  }
}
