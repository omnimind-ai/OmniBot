import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/agent_browser_session_service.dart';

class ChatBrowserOverlay extends StatefulWidget {
  const ChatBrowserOverlay({
    super.key,
    required this.snapshot,
    required this.onSnapshotChanged,
    required this.onClose,
    required this.onDragDelta,
    required this.onResizeLeftDelta,
    required this.onResizeRightDelta,
  });

  final ChatBrowserSessionSnapshot snapshot;
  final ValueChanged<ChatBrowserSessionSnapshot?> onSnapshotChanged;
  final VoidCallback onClose;
  final ValueChanged<Offset> onDragDelta;
  final ValueChanged<Offset> onResizeLeftDelta;
  final ValueChanged<Offset> onResizeRightDelta;

  @override
  State<ChatBrowserOverlay> createState() => _ChatBrowserOverlayState();
}

class _ChatBrowserOverlayState extends State<ChatBrowserOverlay> {
  static const double _actionButtonSize = 32;
  static const double _actionIconSize = 17;
  static const double _addressPillHeight = 36;

  late final TextEditingController _addressController;
  late final FocusNode _addressFocusNode;
  late final TextEditingController _promptController;
  String? _lastPromptRequestId;

  bool get _isEnglish => LegacyTextLocalizer.isEnglish;

  String _text(String zh, String en) => _isEnglish ? en : zh;

  @override
  void initState() {
    super.initState();
    _addressController = TextEditingController(
      text: widget.snapshot.currentUrl,
    );
    _addressFocusNode = FocusNode()..addListener(_handleAddressFocusChanged);
    _promptController = TextEditingController(
      text: widget.snapshot.pendingDialog?.defaultValue ?? '',
    );
    _lastPromptRequestId = widget.snapshot.pendingDialog?.requestId;
  }

  @override
  void didUpdateWidget(covariant ChatBrowserOverlay oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!_addressFocusNode.hasFocus &&
        _addressController.text != widget.snapshot.currentUrl) {
      _addressController.text = widget.snapshot.currentUrl;
    }
    final nextPromptId = widget.snapshot.pendingDialog?.requestId;
    if (_lastPromptRequestId != nextPromptId) {
      _lastPromptRequestId = nextPromptId;
      _promptController.text =
          widget.snapshot.pendingDialog?.defaultValue ?? '';
    }
  }

  @override
  void dispose() {
    _addressController.dispose();
    _addressFocusNode.dispose();
    _promptController.dispose();
    super.dispose();
  }

  void _handleAddressFocusChanged() {
    if (_addressFocusNode.hasFocus) {
      _addressController.selection = TextSelection(
        baseOffset: 0,
        extentOffset: _addressController.text.length,
      );
    } else if (_addressController.text != widget.snapshot.currentUrl) {
      _addressController.text = widget.snapshot.currentUrl;
    }
    if (mounted) {
      setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = widget.snapshot;
    return Material(
      elevation: 18,
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(20),
      child: Container(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: const Color(0xFFD8E4F4)),
          boxShadow: const [
            BoxShadow(
              color: Color(0x1F1930D9),
              blurRadius: 24,
              offset: Offset(0, 10),
            ),
          ],
        ),
        clipBehavior: Clip.antiAlias,
        child: Stack(
          children: [
            Column(
              children: [
                _buildChrome(context, snapshot),
                if (snapshot.hasSslError)
                  _buildBanner(
                    icon: Icons.gpp_bad_rounded,
                    message: _text(
                      '此页面存在 SSL 错误，请谨慎操作',
                      'This page has an SSL error',
                    ),
                    color: const Color(0xFFB42318),
                    background: const Color(0xFFFFF1F3),
                  ),
                if (snapshot.riskChallengeDetected)
                  _buildBanner(
                    icon: Icons.front_hand_rounded,
                    message: _riskChallengeMessage(snapshot),
                    color: const Color(0xFF92400E),
                    background: const Color(0xFFFFF7ED),
                  ),
                if (snapshot.externalOpenPrompt != null)
                  _buildExternalPrompt(snapshot.externalOpenPrompt!),
                if (snapshot.permissionPrompt != null)
                  _buildPermissionPrompt(snapshot.permissionPrompt!),
                if (snapshot.pendingDialog != null)
                  _buildDialogPrompt(snapshot.pendingDialog!),
                Expanded(
                  child: ColoredBox(
                    color: const Color(0xFFF4F7FB),
                    child: Platform.isAndroid
                        ? AndroidView(
                            viewType:
                                AgentBrowserSessionService.platformViewType,
                            creationParams: <String, dynamic>{
                              'workspaceId': snapshot.workspaceId,
                            },
                            creationParamsCodec: const StandardMessageCodec(),
                          )
                        : Center(
                            child: Text(
                              context.l10n.browserOverlayUnsupported,
                              style: const TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.w500,
                                color: Color(0xFF617390),
                              ),
                            ),
                          ),
                  ),
                ),
                _buildToolbar(snapshot),
              ],
            ),
            Positioned(
              left: 0,
              bottom: 6,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onPanUpdate: (details) =>
                    widget.onResizeLeftDelta(details.delta),
                child: const SizedBox(width: 32, height: 28),
              ),
            ),
            Positioned(
              right: 0,
              bottom: 6,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onPanUpdate: (details) =>
                    widget.onResizeRightDelta(details.delta),
                child: const SizedBox(width: 32, height: 28),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildChrome(
    BuildContext context,
    ChatBrowserSessionSnapshot snapshot,
  ) {
    final iconColor = snapshot.hasSslError
        ? const Color(0xFFB42318)
        : (snapshot.currentUrl.startsWith('https://')
              ? const Color(0xFF067647)
              : const Color(0xFF617390));
    return Container(
      padding: const EdgeInsets.fromLTRB(8, 8, 8, 8),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          colors: [Color(0xFFF8FBFF), Color(0xFFF3F7FD)],
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
        ),
        border: Border(bottom: BorderSide(color: Color(0xFFE3EBF8))),
      ),
      child: Row(
        children: [
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onPanUpdate: (details) => widget.onDragDelta(details.delta),
            child: Container(
              width: 24,
              height: _addressPillHeight,
              alignment: Alignment.center,
              child: Container(
                width: 14,
                height: 4,
                decoration: BoxDecoration(
                  color: const Color(0xFF9BAECC),
                  borderRadius: BorderRadius.circular(999),
                ),
              ),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(child: _buildAddressPill(context, snapshot, iconColor)),
          const SizedBox(width: 6),
          _compactActionButton(
            onPressed: _handleToggleBookmark,
            tooltip: _text('切换收藏', 'Toggle bookmark'),
            icon: Icon(
              snapshot.isBookmarked
                  ? Icons.star_rounded
                  : Icons.star_outline_rounded,
              size: _actionIconSize,
              color: snapshot.isBookmarked
                  ? const Color(0xFFD97706)
                  : const Color(0xFF617390),
            ),
            backgroundColor: snapshot.isBookmarked
                ? const Color(0xFFFFF7ED)
                : Colors.white,
            borderColor: snapshot.isBookmarked
                ? const Color(0xFFF7D6A6)
                : const Color(0xFFD9E5F4),
          ),
          const SizedBox(width: 6),
          _compactActionButton(
            onPressed: snapshot.isLoading ? _handleStopLoading : _handleReload,
            tooltip: snapshot.isLoading
                ? _text('停止加载', 'Stop loading')
                : _text('刷新页面', 'Reload page'),
            icon: Icon(
              snapshot.isLoading
                  ? Icons.close_rounded
                  : Icons.autorenew_rounded,
              size: _actionIconSize,
              color: const Color(0xFF617390),
            ),
          ),
          const SizedBox(width: 6),
          _compactActionButton(
            onPressed: widget.onClose,
            icon: const Icon(
              Icons.close_rounded,
              size: _actionIconSize,
              color: Color(0xFF9A3E4A),
            ),
            tooltip: context.l10n.browserOverlayClose,
            backgroundColor: const Color(0xFFFFF4F5),
            borderColor: const Color(0xFFF5D4DA),
          ),
        ],
      ),
    );
  }

  Widget _buildAddressPill(
    BuildContext context,
    ChatBrowserSessionSnapshot snapshot,
    Color iconColor,
  ) {
    final isEditing = _addressFocusNode.hasFocus;
    final radius = BorderRadius.circular(999);
    final hintText = snapshot.title.trim().isEmpty
        ? context.l10n.browserOverlayTitle
        : snapshot.title;
    final primaryText = _addressPrimaryText(snapshot, hintText);
    final secondaryText = _addressSecondaryText(snapshot);
    final fieldDecoration = BoxDecoration(
      color: Colors.white,
      borderRadius: radius,
      border: Border.all(
        color: isEditing ? const Color(0xFFB7CAF2) : const Color(0xFFD9E5F4),
      ),
      boxShadow: [
        BoxShadow(
          color: isEditing ? const Color(0x142563EB) : const Color(0x08172B4D),
          blurRadius: isEditing ? 16 : 10,
          offset: const Offset(0, 4),
        ),
      ],
    );

    if (isEditing) {
      return AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        height: _addressPillHeight,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        decoration: fieldDecoration,
        child: Row(
          children: [
            Icon(
              snapshot.hasSslError
                  ? Icons.gpp_bad_rounded
                  : snapshot.currentUrl.startsWith('https://')
                  ? Icons.lock_rounded
                  : Icons.language_rounded,
              size: 15,
              color: iconColor,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                controller: _addressController,
                focusNode: _addressFocusNode,
                textInputAction: TextInputAction.go,
                keyboardType: TextInputType.url,
                autocorrect: false,
                enableSuggestions: false,
                onTapOutside: (_) => _addressFocusNode.unfocus(),
                onSubmitted: _handleAddressSubmitted,
                style: const TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w600,
                  color: Color(0xFF213147),
                ),
                decoration: InputDecoration(
                  isCollapsed: true,
                  border: InputBorder.none,
                  hintText: hintText,
                  hintStyle: const TextStyle(
                    color: Color(0xFF93A4BB),
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
            ),
          ],
        ),
      );
    }

    final tooltipText = snapshot.currentUrl.trim().isEmpty
        ? hintText
        : snapshot.currentUrl.trim();

    return Tooltip(
      message: tooltipText,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: radius,
          onTap: _addressFocusNode.requestFocus,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            height: _addressPillHeight,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            decoration: fieldDecoration,
            child: Row(
              children: [
                Icon(
                  snapshot.hasSslError
                      ? Icons.gpp_bad_rounded
                      : snapshot.currentUrl.startsWith('https://')
                      ? Icons.lock_rounded
                      : Icons.language_rounded,
                  size: 15,
                  color: iconColor,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text.rich(
                    TextSpan(
                      children: [
                        TextSpan(
                          text: primaryText,
                          style: const TextStyle(
                            fontSize: 12.5,
                            fontWeight: FontWeight.w700,
                            color: Color(0xFF213147),
                          ),
                        ),
                        if (secondaryText.isNotEmpty)
                          TextSpan(
                            text: '   $secondaryText',
                            style: const TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.w500,
                              color: Color(0xFF7C8EA7),
                            ),
                          ),
                      ],
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildBanner({
    required IconData icon,
    required String message,
    required Color color,
    required Color background,
  }) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
      color: background,
      child: Row(
        children: [
          Icon(icon, size: 16, color: color),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: color,
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _riskChallengeMessage(ChatBrowserSessionSnapshot snapshot) {
    switch (snapshot.recommendedNextAction) {
      case 'wait_before_retrying_and_reduce_request_rate':
        return _text(
          '页面触发频率限制，已暂停自动操作，请稍后再继续',
          'Rate limit detected. Automation is paused; wait before continuing.',
        );
      case 'stop_automatic_retry_and_use_manual_access':
        return _text(
          '页面拒绝自动访问，已暂停操作，请手动处理后继续',
          'Access was denied. Automation is paused; continue manually.',
        );
      default:
        return _text(
          '检测到验证码或风控验证，已暂停自动操作，请手动处理后继续',
          'Verification challenge detected. Automation is paused; complete it manually to continue.',
        );
    }
  }

  Widget _buildExternalPrompt(BrowserExternalOpenPrompt prompt) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: const BoxDecoration(
        color: Color(0xFFFFFBEB),
        border: Border(bottom: BorderSide(color: Color(0xFFFDE68A))),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _text('是否打开外部链接？', 'Open external link?'),
            style: const TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: Color(0xFF92400E),
            ),
          ),
          const SizedBox(height: 4),
          Text(
            prompt.target,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 12, color: Color(0xFF7C2D12)),
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              _secondaryActionButton(
                label: _text('取消', 'Cancel'),
                onPressed: () => _runCommand(
                  AgentBrowserSessionService.cancelExternalOpen(
                    prompt.requestId,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              _primaryActionButton(
                label: _text('打开', 'Open'),
                onPressed: () => _runCommand(
                  AgentBrowserSessionService.confirmExternalOpen(
                    prompt.requestId,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildPermissionPrompt(BrowserPermissionPrompt prompt) {
    final capabilityLabels = prompt.capabilities
        .map(_permissionCapabilityLabel)
        .toSet()
        .toList(growable: false);
    final capabilityLabel = capabilityLabels.isEmpty
        ? _text('网站功能', 'website capability')
        : capabilityLabels.join(_text('、', ', '));
    final parsedHost = Uri.tryParse(prompt.origin)?.host ?? '';
    final recipient = prompt.recipient.trim().isNotEmpty
        ? prompt.recipient.trim()
        : (parsedHost.isNotEmpty ? parsedHost : _text('当前网站', 'current website'));
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: const BoxDecoration(
        color: Color(0xFFF5F8FF),
        border: Border(bottom: BorderSide(color: Color(0xFFDCE7F5))),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _text('网站请求敏感权限', 'Website requests sensitive access'),
            style: const TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: Color(0xFF1D4ED8),
            ),
          ),
          const SizedBox(height: 4),
          Text(
            _text('接收方：$recipient', 'Recipient: $recipient'),
            style: const TextStyle(fontSize: 12, color: Color(0xFF334155)),
          ),
          const SizedBox(height: 3),
          Text(
            _text(
              '用途：允许该网站使用$capabilityLabel。',
              'Purpose: let this website use $capabilityLabel.',
            ),
            style: const TextStyle(fontSize: 12, color: Color(0xFF334155)),
          ),
          const SizedBox(height: 4),
          Text(
            _text(
              '数据由该网站处理，不是 OmniBot AI。授权仅对本次请求有效，不会保存为“始终允许”。',
              'The website processes this data, not OmniBot AI. Access applies only to this request and is never saved as “always allow”.',
            ),
            style: const TextStyle(fontSize: 11, color: Color(0xFF64748B)),
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              _secondaryActionButton(
                label: _text('拒绝', 'Deny'),
                onPressed: () => _runCommand(
                  AgentBrowserSessionService.denyPermission(prompt.requestId),
                ),
              ),
              const SizedBox(width: 8),
              _primaryActionButton(
                label: _text('仅此次允许', 'Allow once'),
                onPressed: () => _runCommand(
                  AgentBrowserSessionService.grantPermission(prompt.requestId),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  String _permissionCapabilityLabel(String rawCapability) {
    switch (rawCapability.trim().toLowerCase()) {
      case 'location':
      case 'android.permission.access_coarse_location':
      case 'android.permission.access_fine_location':
        return _text('位置', 'location');
      case 'camera':
      case 'android.permission.camera':
      case 'android.webkit.resource.video_capture':
        return _text('相机', 'camera');
      case 'microphone':
      case 'android.permission.record_audio':
      case 'android.webkit.resource.audio_capture':
        return _text('麦克风', 'microphone');
      default:
        return _text('网站功能', 'website capability');
    }
  }

  Widget _buildDialogPrompt(BrowserDialogPrompt prompt) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: const BoxDecoration(
        color: Color(0xFFF8FAFC),
        border: Border(bottom: BorderSide(color: Color(0xFFE2E8F0))),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _dialogTitle(prompt.type),
            style: const TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: Color(0xFF1E293B),
            ),
          ),
          const SizedBox(height: 4),
          Text(
            prompt.message,
            style: const TextStyle(fontSize: 12, color: Color(0xFF334155)),
          ),
          if (prompt.type == 'prompt') ...[
            const SizedBox(height: 8),
            TextField(
              controller: _promptController,
              decoration: InputDecoration(
                isDense: true,
                hintText: _text('输入内容', 'Enter value'),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
            ),
          ],
          const SizedBox(height: 8),
          Row(
            children: [
              _secondaryActionButton(
                label: _text('取消', 'Cancel'),
                onPressed: () => _runCommand(
                  AgentBrowserSessionService.resolveDialog(
                    requestId: prompt.requestId,
                    accept: false,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              _primaryActionButton(
                label: _text('确定', 'OK'),
                onPressed: () => _runCommand(
                  AgentBrowserSessionService.resolveDialog(
                    requestId: prompt.requestId,
                    accept: true,
                    promptValue: prompt.type == 'prompt'
                        ? _promptController.text
                        : null,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildToolbar(ChatBrowserSessionSnapshot snapshot) {
    return Container(
      height: 44,
      padding: const EdgeInsets.fromLTRB(8, 5, 8, 7),
      decoration: const BoxDecoration(
        color: Color(0xFFF7F9FC),
        border: Border(top: BorderSide(color: Color(0xFFE2E8F0))),
      ),
      child: Row(
        children: [
          _toolbarButton(
            icon: Icons.arrow_back_ios_new_rounded,
            enabled: snapshot.canGoBack,
            tooltip: _text('后退', 'Back'),
            onPressed: () => _runCommand(AgentBrowserSessionService.goBack()),
          ),
          const SizedBox(width: 6),
          _toolbarButton(
            icon: Icons.arrow_forward_ios_rounded,
            enabled: snapshot.canGoForward,
            tooltip: _text('前进', 'Forward'),
            onPressed: () =>
                _runCommand(AgentBrowserSessionService.goForward()),
          ),
          const SizedBox(width: 6),
          _toolbarButton(
            icon: Icons.add_rounded,
            tooltip: _text('新标签页', 'New tab'),
            onPressed: () => _runCommand(AgentBrowserSessionService.newTab()),
          ),
          Expanded(
            child: Center(
              child: InkWell(
                borderRadius: BorderRadius.circular(999),
                onTap: _showTabsSheet,
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: const Color(0xFFD9E5F4)),
                    color: Colors.white,
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(
                        Icons.layers_outlined,
                        size: 16,
                        color: Color(0xFF42526B),
                      ),
                      const SizedBox(width: 6),
                      Text(
                        '${widget.snapshot.tabs.length}',
                        style: const TextStyle(
                          fontSize: 11.5,
                          fontWeight: FontWeight.w700,
                          color: Color(0xFF213147),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(width: 6),
          _toolbarButton(
            icon: Icons.more_horiz_rounded,
            tooltip: _text('菜单', 'Menu'),
            onPressed: _showMenuSheet,
          ),
        ],
      ),
    );
  }

  Widget _toolbarButton({
    required IconData icon,
    required VoidCallback onPressed,
    String? tooltip,
    bool enabled = true,
  }) {
    return _compactActionButton(
      onPressed: enabled ? onPressed : null,
      tooltip: tooltip,
      icon: Icon(icon, size: _actionIconSize, color: const Color(0xFF42526B)),
    );
  }

  Widget _compactActionButton({
    required Widget icon,
    VoidCallback? onPressed,
    String? tooltip,
    Color backgroundColor = Colors.white,
    Color borderColor = const Color(0xFFD9E5F4),
  }) {
    final enabled = onPressed != null;
    Widget child = AnimatedOpacity(
      duration: const Duration(milliseconds: 150),
      opacity: enabled ? 1 : 0.48,
      child: Material(
        color: enabled ? backgroundColor : const Color(0xFFF6F8FB),
        borderRadius: BorderRadius.circular(999),
        child: InkWell(
          borderRadius: BorderRadius.circular(999),
          onTap: onPressed,
          child: Ink(
            width: _actionButtonSize,
            height: _actionButtonSize,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(999),
              border: Border.all(
                color: enabled ? borderColor : const Color(0xFFE3EAF4),
              ),
            ),
            child: Center(child: icon),
          ),
        ),
      ),
    );
    if (tooltip != null) {
      child = Tooltip(message: tooltip, child: child);
    }
    return child;
  }

  Widget _secondaryActionButton({
    required String label,
    required VoidCallback onPressed,
  }) {
    return TextButton(onPressed: onPressed, child: Text(label));
  }

  Widget _primaryActionButton({
    required String label,
    VoidCallback? onPressed,
  }) {
    return FilledButton(onPressed: onPressed, child: Text(label));
  }

  String _dialogTitle(String type) {
    switch (type) {
      case 'alert':
        return _text('页面提醒', 'Page alert');
      case 'confirm':
        return _text('页面确认', 'Page confirmation');
      case 'prompt':
        return _text('页面输入', 'Page prompt');
      default:
        return _text('页面对话框', 'Page dialog');
    }
  }

  String _addressPrimaryText(
    ChatBrowserSessionSnapshot snapshot,
    String fallback,
  ) {
    final url = snapshot.currentUrl.trim();
    if (url.isEmpty) {
      return fallback;
    }
    final uri = Uri.tryParse(url);
    if (uri == null) {
      return url;
    }
    final host = uri.host.trim();
    if (host.isNotEmpty) {
      final normalizedHost = host.startsWith('www.') ? host.substring(4) : host;
      return uri.hasPort ? '$normalizedHost:${uri.port}' : normalizedHost;
    }
    if (uri.scheme.trim().isNotEmpty) {
      return '${uri.scheme.toLowerCase()}://';
    }
    return url;
  }

  String _addressSecondaryText(ChatBrowserSessionSnapshot snapshot) {
    final url = snapshot.currentUrl.trim();
    if (url.isEmpty) {
      return '';
    }
    final uri = Uri.tryParse(url);
    if (uri == null) {
      return '';
    }
    final buffer = StringBuffer();
    final path = uri.path.trim();
    if (path.isNotEmpty && path != '/') {
      buffer.write(path);
    }
    if (uri.hasQuery) {
      buffer.write('?${uri.query}');
    }
    if (uri.hasFragment) {
      buffer.write('#${uri.fragment}');
    }
    return buffer.toString();
  }

  Future<void> _handleAddressSubmitted(String value) async {
    final trimmed = value.trim();
    if (trimmed.isEmpty) {
      return;
    }
    await _runCommand(AgentBrowserSessionService.navigate(trimmed));
    _addressFocusNode.unfocus();
  }

  Future<void> _handleToggleBookmark() {
    return _runCommand(AgentBrowserSessionService.toggleBookmark());
  }

  Future<void> _handleReload() {
    return _runCommand(AgentBrowserSessionService.reload());
  }

  Future<void> _handleStopLoading() {
    return _runCommand(AgentBrowserSessionService.stopLoading());
  }

  Future<void> _runCommand(
    Future<ChatBrowserSessionSnapshot?> future, {
    bool showErrors = true,
  }) async {
    try {
      final next = await future;
      if (!mounted) {
        return;
      }
      if (next != null) {
        widget.onSnapshotChanged(next);
      }
    } on PlatformException catch (error) {
      if (!mounted || !showErrors) {
        return;
      }
      _showSnack(error.message ?? error.code);
    } catch (error) {
      if (!mounted || !showErrors) {
        return;
      }
      _showSnack(error.toString());
    }
  }

  Future<void> _showTabsSheet() async {
    final snapshot = widget.snapshot;
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (context) {
        return SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            children: [
              Text(
                _text('标签页', 'Tabs'),
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 12),
              ...snapshot.tabs.map((tab) {
                return ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(
                    tab.isActive
                        ? Icons.radio_button_checked_rounded
                        : Icons.radio_button_unchecked_rounded,
                    size: 18,
                    color: tab.isActive
                        ? const Color(0xFF2563EB)
                        : const Color(0xFF94A3B8),
                  ),
                  title: Text(
                    tab.title.trim().isEmpty ? tab.url : tab.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  subtitle: Text(
                    tab.url,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  trailing: IconButton(
                    onPressed: () async {
                      Navigator.of(context).pop();
                      await _runCommand(
                        AgentBrowserSessionService.closeTab(tab.tabId),
                      );
                    },
                    icon: const Icon(Icons.close_rounded),
                  ),
                  onTap: () async {
                    Navigator.of(context).pop();
                    await _runCommand(
                      AgentBrowserSessionService.selectTab(tab.tabId),
                    );
                  },
                );
              }),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: () async {
                  Navigator.of(context).pop();
                  await _runCommand(AgentBrowserSessionService.newTab());
                },
                icon: const Icon(Icons.add_rounded),
                label: Text(_text('新建标签页', 'New tab')),
              ),
            ],
          ),
        );
      },
    );
  }

  Future<void> _showMenuSheet() async {
    final snapshot = widget.snapshot;
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (context) {
        return SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            children: [
              Text(
                _text('浏览器菜单', 'Browser menu'),
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 12),
              _menuTile(
                icon: Icons.history_rounded,
                label: _text('历史记录', 'History'),
                onTap: () {
                  Navigator.of(context).pop();
                  _showHistorySheet();
                },
              ),
              _menuTile(
                icon: Icons.bookmarks_rounded,
                label: _text('收藏夹', 'Bookmarks'),
                onTap: () {
                  Navigator.of(context).pop();
                  _showBookmarksSheet();
                },
              ),
              _menuTile(
                icon: Icons.download_rounded,
                label: _text('下载', 'Downloads'),
                onTap: () {
                  Navigator.of(context).pop();
                  _showDownloadsSheet();
                },
              ),
              _menuTile(
                icon: Icons.code_rounded,
                label: _text('Userscript', 'Userscript'),
                onTap: () {
                  Navigator.of(context).pop();
                  _showUserscriptSheet();
                },
              ),
              _menuTile(
                icon: snapshot.isDesktopMode
                    ? Icons.smartphone_rounded
                    : Icons.desktop_windows_rounded,
                label: snapshot.isDesktopMode
                    ? _text('切换到移动模式', 'Switch to mobile mode')
                    : _text('切换到桌面模式', 'Switch to desktop mode'),
                onTap: () async {
                  Navigator.of(context).pop();
                  await _runCommand(
                    AgentBrowserSessionService.toggleDesktopMode(),
                  );
                },
              ),
              _menuTile(
                icon: Icons.close_rounded,
                label: _text('关闭当前标签页', 'Close current tab'),
                onTap: () async {
                  Navigator.of(context).pop();
                  final activeTabId = snapshot.activeTabId;
                  if (activeTabId != null) {
                    await _runCommand(
                      AgentBrowserSessionService.closeTab(activeTabId),
                    );
                  }
                },
              ),
              _menuTile(
                icon: Icons.tab_unselected_rounded,
                label: _text('关闭全部标签页', 'Close all tabs'),
                onTap: () async {
                  Navigator.of(context).pop();
                  await _runCommand(
                    AgentBrowserSessionService.closeAllTabs(
                      snapshot.tabs.map((tab) => tab.tabId).toList(),
                    ),
                  );
                },
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _menuTile({
    required IconData icon,
    required String label,
    required VoidCallback onTap,
  }) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Icon(icon, color: const Color(0xFF334155)),
      title: Text(label),
      onTap: onTap,
    );
  }

  Future<void> _showHistorySheet() async {
    final entries = widget.snapshot.history;
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (context) {
        return SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            children: [
              Text(
                _text('历史记录', 'History'),
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 12),
              if (entries.isEmpty)
                _emptyState(_text('暂无历史记录', 'No history yet')),
              ...entries.map((entry) {
                return ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(
                    entry.title.trim().isEmpty ? entry.url : entry.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  subtitle: Text(
                    entry.url,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  onTap: () async {
                    Navigator.of(context).pop();
                    await _runCommand(
                      AgentBrowserSessionService.openHistoryEntry(entry.url),
                    );
                  },
                );
              }),
              if (entries.isNotEmpty) ...[
                const SizedBox(height: 12),
                OutlinedButton(
                  onPressed: () async {
                    Navigator.of(context).pop();
                    await _runCommand(
                      AgentBrowserSessionService.clearHistory(),
                    );
                  },
                  child: Text(_text('清空历史记录', 'Clear history')),
                ),
              ],
            ],
          ),
        );
      },
    );
  }

  Future<void> _showBookmarksSheet() async {
    final entries = widget.snapshot.bookmarks;
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (context) {
        return SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            children: [
              Text(
                _text('收藏夹', 'Bookmarks'),
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 12),
              if (entries.isEmpty)
                _emptyState(_text('暂无收藏', 'No bookmarks yet')),
              ...entries.map((entry) {
                return ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(
                    entry.title.trim().isEmpty ? entry.url : entry.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  subtitle: Text(
                    entry.url,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  trailing: IconButton(
                    onPressed: () async {
                      Navigator.of(context).pop();
                      await _runCommand(
                        AgentBrowserSessionService.removeBookmark(entry.url),
                      );
                    },
                    icon: const Icon(Icons.delete_outline_rounded),
                  ),
                  onTap: () async {
                    Navigator.of(context).pop();
                    await _runCommand(
                      AgentBrowserSessionService.navigate(entry.url),
                    );
                  },
                );
              }),
            ],
          ),
        );
      },
    );
  }

  Future<void> _showDownloadsSheet() async {
    final snapshot = widget.snapshot;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) {
        return SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
            children: [
              Text(
                _text('下载', 'Downloads'),
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 4),
              Text(
                _text(
                  '进行中 ${snapshot.downloadSummary.activeCount} · 失败 ${snapshot.downloadSummary.failedCount}',
                  'Active ${snapshot.downloadSummary.activeCount} · Failed ${snapshot.downloadSummary.failedCount}',
                ),
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 12),
              if (snapshot.downloads.isEmpty)
                _emptyState(_text('暂无下载任务', 'No downloads yet')),
              ...snapshot.downloads.map(
                (item) => _buildDownloadCard(context, item),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildDownloadCard(BuildContext context, BrowserDownloadItem item) {
    final progress = item.progress;
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              item.fileName,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 4),
            Text(
              item.status,
              style: const TextStyle(fontSize: 12, color: Color(0xFF475569)),
            ),
            if (progress != null) ...[
              const SizedBox(height: 8),
              LinearProgressIndicator(value: progress.clamp(0.0, 1.0)),
            ],
            if ((item.errorMessage ?? '').isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(
                item.errorMessage!,
                style: const TextStyle(fontSize: 12, color: Color(0xFFB42318)),
              ),
            ],
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                if (item.canPause)
                  _actionChip(
                    label: _text('暂停', 'Pause'),
                    onPressed: () => _runCommand(
                      AgentBrowserSessionService.pauseDownload(item.id),
                    ),
                  ),
                if (item.canResume)
                  _actionChip(
                    label: _text('继续', 'Resume'),
                    onPressed: () => _runCommand(
                      AgentBrowserSessionService.resumeDownload(item.id),
                    ),
                  ),
                if (item.canCancel)
                  _actionChip(
                    label: _text('取消', 'Cancel'),
                    onPressed: () => _runCommand(
                      AgentBrowserSessionService.cancelDownload(item.id),
                    ),
                  ),
                if (item.canRetry)
                  _actionChip(
                    label: _text('重试', 'Retry'),
                    onPressed: () => _runCommand(
                      AgentBrowserSessionService.retryDownload(item.id),
                    ),
                  ),
                if (item.canOpenFile)
                  _actionChip(
                    label: _text('打开文件', 'Open file'),
                    onPressed: () => _runCommand(
                      AgentBrowserSessionService.openDownloadedFile(item.id),
                    ),
                  ),
                if (item.canOpenLocation)
                  _actionChip(
                    label: _text('打开目录', 'Open folder'),
                    onPressed: () => _runCommand(
                      AgentBrowserSessionService.openDownloadLocation(item.id),
                    ),
                  ),
                _actionChip(
                  label: _text('删除', 'Delete'),
                  onPressed: () => _runCommand(
                    AgentBrowserSessionService.deleteDownload(
                      item.id,
                      deleteFile: item.canDeleteFile,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _actionChip({required String label, required VoidCallback onPressed}) {
    return ActionChip(label: Text(label), onPressed: onPressed);
  }

  Future<void> _showUserscriptSheet() async {
    final snapshot = widget.snapshot;
    final pendingInstall = snapshot.userscriptSummary.pendingInstall;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) {
        return SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
            children: [
              Text(
                _text('Userscript', 'Userscript'),
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 12),
              if (pendingInstall != null)
                _buildPendingUserscriptCard(pendingInstall),
              if (snapshot
                  .userscriptSummary
                  .currentPageMenuCommands
                  .isNotEmpty) ...[
                Text(
                  _text('当前页菜单命令', 'Current page menu'),
                  style: Theme.of(context).textTheme.titleSmall,
                ),
                const SizedBox(height: 8),
                ...snapshot.userscriptSummary.currentPageMenuCommands.map((
                  command,
                ) {
                  return ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.play_circle_outline_rounded),
                    title: Text(command.title),
                    onTap: () => _runCommand(
                      AgentBrowserSessionService.invokeUserscriptMenuCommand(
                        command.commandId,
                      ),
                    ),
                  );
                }),
                const SizedBox(height: 12),
              ],
              Text(
                _text('已安装脚本', 'Installed scripts'),
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 8),
              if (snapshot.userscriptSummary.installedScripts.isEmpty)
                _emptyState(_text('暂无脚本', 'No scripts installed')),
              ...snapshot.userscriptSummary.installedScripts.map(
                _buildUserscriptTile,
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: _handleInstallUserscriptFromUrl,
                icon: const Icon(Icons.link_rounded),
                label: Text(_text('从 URL 安装', 'Install from URL')),
              ),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: () async {
                  await _runCommand(
                    AgentBrowserSessionService.importUserscriptFile(),
                  );
                },
                icon: const Icon(Icons.file_upload_outlined),
                label: Text(_text('导入本地 .user.js', 'Import local .user.js')),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildPendingUserscriptCard(BrowserUserscriptInstallPreview pending) {
    final blocked = pending.blockedGrants;
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      color: const Color(0xFFF8FAFC),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              pending.isUpdate
                  ? _text('待更新脚本', 'Pending script update')
                  : _text('待安装脚本', 'Pending script install'),
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 6),
            Text('${pending.name} · ${pending.version}'),
            if (blocked.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                '${_text('未实现的 grants：', 'Unsupported grants: ')}${blocked.join(', ')}',
                style: const TextStyle(fontSize: 12, color: Color(0xFFB42318)),
              ),
            ],
            const SizedBox(height: 8),
            Row(
              children: [
                _secondaryActionButton(
                  label: _text('取消', 'Cancel'),
                  onPressed: () => _runCommand(
                    AgentBrowserSessionService.cancelUserscriptInstall(),
                  ),
                ),
                const SizedBox(width: 8),
                _primaryActionButton(
                  label: _text('确认安装', 'Confirm'),
                  onPressed: blocked.isNotEmpty
                      ? null
                      : () => _runCommand(
                          AgentBrowserSessionService.confirmUserscriptInstall(),
                        ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildUserscriptTile(BrowserUserscriptItem script) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Switch(
        value: script.enabled,
        onChanged: (value) => _runCommand(
          AgentBrowserSessionService.setUserscriptEnabled(script.id, value),
        ),
      ),
      title: Text(script.name),
      subtitle: Text(
        [
          if (script.version.trim().isNotEmpty) script.version,
          if ((script.sourceUrl ?? '').trim().isNotEmpty) script.sourceUrl!,
        ].join(' · '),
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            onPressed: () => _runCommand(
              AgentBrowserSessionService.checkUserscriptUpdate(script.id),
            ),
            icon: const Icon(Icons.system_update_alt_rounded),
            tooltip: _text('检查更新', 'Check update'),
          ),
          IconButton(
            onPressed: () => _runCommand(
              AgentBrowserSessionService.deleteUserscript(script.id),
            ),
            icon: const Icon(Icons.delete_outline_rounded),
            tooltip: _text('删除', 'Delete'),
          ),
        ],
      ),
    );
  }

  Future<void> _handleInstallUserscriptFromUrl() async {
    final controller = TextEditingController();
    final result = await showDialog<String>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: Text(_text('从 URL 安装脚本', 'Install script from URL')),
          content: TextField(
            controller: controller,
            autofocus: true,
            decoration: InputDecoration(
              hintText: 'https://example.com/script.user.js',
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: Text(_text('取消', 'Cancel')),
            ),
            FilledButton(
              onPressed: () =>
                  Navigator.of(context).pop(controller.text.trim()),
              child: Text(_text('安装', 'Install')),
            ),
          ],
        );
      },
    );
    if (result == null || result.isEmpty) {
      return;
    }
    await _runCommand(
      AgentBrowserSessionService.installUserscriptFromUrl(result),
    );
  }

  Widget _emptyState(String text) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 20),
      child: Center(
        child: Text(
          text,
          style: const TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.w500,
            color: Color(0xFF64748B),
          ),
        ),
      ),
    );
  }

  void _showSnack(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }
}
