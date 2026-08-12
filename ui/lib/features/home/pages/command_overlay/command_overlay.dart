import 'dart:async';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/image_prewarm_cache_service.dart';
import 'package:ui/services/screen_dialog_service.dart';
import 'package:ui/services/openclaw_credential_service.dart';
import 'package:ui/services/data_destination_confirmation.dart';
import 'package:ui/features/home/pages/common/openclaw_connection_checker.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/openclaw_identity_reset_dialog.dart';

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
      final configuration = await OpenClawCredentialService.initializeAndLoad();
      if (!mounted) return;
      setState(() {
        _openClawEnabled = configuration.enabled;
        _openClawBaseUrl = configuration.baseUrl;
        _openClawToken = '';
        _openClawUserId = configuration.userId;
      });
      await _ensureOpenClawUserId();
    } catch (e) {}
  }

  Future<void> _ensureOpenClawUserId() async {
    if (_openClawUserId.isNotEmpty) return;
    final generated = DateTime.now().microsecondsSinceEpoch.toString();
    if (!mounted) return;
    setState(() => _openClawUserId = generated);
  }

  Future<void> _setOpenClawEnabled(bool enabled) async {
    if (enabled && _openClawBaseUrl.trim().isEmpty) {
      AppToast.show(LegacyTextLocalizer.localize('请先使用 /openclaw 配置 OpenClaw'));
      _showOpenClawCommandPanel(expand: true);
      return;
    }
    if (!enabled) {
      if (!mounted) return;
      final disabled = await OpenClawCredentialService.disable();
      if (!disabled.success || !mounted) return;
      setState(() {
        _openClawEnabled = false;
      });
      return;
    }
    final plan = await OpenClawCredentialService.prepareDestination(
      _openClawBaseUrl,
    );
    if (!mounted) return;
    final outcome =
        await confirmDataDestinationAndRun<OpenClawConfigurationMutationResult>(
          context: context,
          rawEndpoint: plan.baseUrl,
          capability: 'OpenClaw Gateway',
          operation: LegacyTextLocalizer.isEnglish ? 'Enable' : '启用',
          dataTypes: [
            LegacyTextLocalizer.isEnglish
                ? 'Future prompts, conversation history, attachments, and device pairing metadata'
                : '启用后发送的提示词、对话历史、附件和设备配对元数据',
          ],
          action: () => OpenClawCredentialService.saveConfirmed(
            plan: plan,
            baseUrl: plan.baseUrl,
            userId: _openClawUserId,
            enable: true,
          ),
        );
    if (!outcome.confirmed || outcome.value?.success != true || !mounted)
      return;
    setState(() {
      _openClawEnabled = outcome.value!.configuration?.enabled == true;
    });
  }

  /// 检查 OpenClaw 服务连接状态
  Future<void> _checkOpenClawConnection() async {
    await OpenClawConnectionChecker.checkAndToast(context, _openClawBaseUrl);
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
      final saved = await _applyOpenClawConfig(
        baseUrl: _openClawBaseUrlController.text.trim(),
        token: _openClawTokenController.text.trim(),
        userId: _openClawUserIdController.text.trim(),
        enable: _openClawEnabled,
      );
      if (saved) _checkOpenClawConnection();
    }
    _hideSlashCommandPanel();
  }

  Future<bool> _applyOpenClawConfig({
    required String baseUrl,
    required String token,
    String? userId,
    bool enable = true,
  }) async {
    final plan = await OpenClawCredentialService.prepareDestination(baseUrl);
    if (!mounted) return false;
    final effectiveUserId = userId?.trim().isNotEmpty == true
        ? userId!.trim()
        : _openClawUserId.trim();
    final outcome =
        await confirmDataDestinationAndRun<OpenClawConfigurationMutationResult>(
          context: context,
          rawEndpoint: plan.baseUrl,
          capability: 'OpenClaw Gateway',
          operation: LegacyTextLocalizer.isEnglish
              ? 'Save or enable configuration'
              : '保存或启用配置',
          dataTypes: [
            LegacyTextLocalizer.isEnglish
                ? 'Gateway credential, when configured'
                : 'Gateway 凭据（如已配置）',
            LegacyTextLocalizer.isEnglish
                ? 'Future prompts, conversation history, attachments, and device pairing metadata'
                : '启用后发送的提示词、对话历史、附件和设备配对元数据',
          ],
          action: () async {
            return OpenClawCredentialService.saveConfirmed(
              plan: plan,
              baseUrl: plan.baseUrl,
              userId: effectiveUserId,
              enable: enable,
              replacementToken: token,
            );
          },
        );
    final mutation = outcome.value;
    if (!outcome.confirmed || mutation?.success != true || !mounted)
      return false;
    final configuration = mutation!.configuration;
    if (configuration == null) return false;
    setState(() {
      _openClawBaseUrl = configuration.baseUrl;
      _openClawToken = '';
      _openClawTokenController.clear();
      _openClawUserId = configuration.userId;
      _openClawEnabled = configuration.enabled;
    });
    return true;
  }

  Future<bool> _tryHandleSlashCommand(String messageText) async {
    final trimmed = messageText.trim();
    if (!trimmed.startsWith('/')) return false;

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

    final saved = await _applyOpenClawConfig(
      baseUrl: baseUrl.trim(),
      token: token.trim(),
      userId: userId?.trim(),
      enable: true,
    );
    if (!saved) {
      AppToast.show('未确认接收方，OpenClaw 未启用');
      return true;
    }
    _messageController.clear();
    _inputFocusNode.unfocus();
    _hideSlashCommandPanel();
    AppToast.show('OpenClaw 已配置并启用');
    return true;
  }

  Future<void> _resetOpenClawDeviceIdentity() async {
    final result = await showOpenClawIdentityResetFlow(
      context: context,
      onLocalDisabled: () {
        if (mounted) setState(() => _openClawEnabled = false);
      },
    );
    if (!mounted || result == null) return;
    AppToast.show(
      result.success
          ? (LegacyTextLocalizer.isEnglish
                ? 'Device identity reset. Restart or reconnect OpenClaw.'
                : '设备身份已重置，请重启或重新连接 OpenClaw。')
          : (LegacyTextLocalizer.isEnglish
                ? 'Reset was not verified; OpenClaw remains disabled.'
                : '无法验证重置结果；OpenClaw 保持停用。'),
    );
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
          final path = file.path;
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
                          obscureText: true,
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
                        const SizedBox(height: 10),
                        Align(
                          alignment: Alignment.centerRight,
                          child: OutlinedButton.icon(
                            key: const Key(
                              'overlay-openclaw-reset-device-identity-button',
                            ),
                            onPressed: _resetOpenClawDeviceIdentity,
                            icon: const Icon(
                              Icons.phonelink_erase_outlined,
                              size: 17,
                            ),
                            label: Text(
                              LegacyTextLocalizer.isEnglish
                                  ? 'Reset device identity'
                                  : '重置设备身份',
                            ),
                          ),
                        ),
                      ],
                    )
                  : InkWell(
                      onTap: () {
                        _showOpenClawCommandPanel(expand: true);
                      },
                      borderRadius: BorderRadius.circular(10),
                      child: Row(
                        children: [
                          Icon(Icons.link, size: 16, color: panelAccentColor),
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
