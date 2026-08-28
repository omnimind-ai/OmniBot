part of 'chat_widgets.dart';

const String _kChatAppBarUpdateSparklesAsset =
    'assets/home/chat/update_sparkles.svg';
const String _kChatAppBarAgentIconAsset = 'assets/home/avatar.svg';
const String _kChatAppBarSurfaceAgentIconAsset = 'assets/home/chat/agent.svg';
const String _kChatAppBarModeMenuClosedIconAsset =
    'assets/home/chat/mode_menu_closed.svg';
const String _kChatAppBarModeMenuOpenIconAsset =
    'assets/home/chat/mode_menu_open.svg';
const String _kChatAppBarPureChatIconAsset = 'assets/home/chat/pure_chat.svg';
const String _kChatAppBarWorkspaceIconAsset =
    'assets/home/workspace_folder_icon.svg';

const List<Color> _kDarkChatAccentGradient = <Color>[
  Color(0xFFAA9774),
  Color(0xFF8FA38A),
];

const double _kChatAppBarMenuButtonSize = 50;
const double _kChatAppBarAccessoryButtonSize = 40;
const double _kChatAppBarAccessoryGap = 12;
const double _kChatAppBarIslandMaxWidth = 176;
const double _kChatAppBarRightActionSlotWidth = 50;
const Duration _kChatAppBarModeMenuOpenDuration = Duration(milliseconds: 260);
const Duration _kChatAppBarModeMenuCloseDuration = Duration(milliseconds: 180);
const double _kChatAppBarModeMenuOmniAiIconSize = 23;
const Offset _kChatAppBarModeMenuOmniAiIconOffset = Offset(1, 0);
const double _kChatAppBarModeMenuPureChatIconSize = 20;

double _chatAppBarModeMenuAgentIconSize(String agentId) {
  // 品牌 SVG 的有效绘制范围并不一致：Codex 几乎铺满 24px viewBox，
  // Claude Code 横向较宽，OpenCode 则有较多留白。这里按轮廓做光学尺寸
  // 校正，让它们在 40px 菜单行内看起来接近同一大小。
  return switch (agentId.trim()) {
    'xiaowan-acp' => 23,
    'codex-acp' || 'codex-remote' => 19,
    'claude-code-acp' => 21,
    'opencode-acp' => 22,
    _ => 20,
  };
}

enum ChatSurfaceMode { workspace, normal, openclaw }

class ChatAcpAgentModeOption {
  const ChatAcpAgentModeOption({
    required this.id,
    required this.name,
    this.enabled = true,
    this.installed = true,
    this.status = 'online',
  });

  final String id;
  final String name;
  final bool enabled;
  final bool installed;
  final String status;

  bool get isAvailable =>
      enabled && installed && status.trim().toLowerCase() != 'missing';
}

const List<ChatSurfaceMode> kVisibleChatSurfaceModes = <ChatSurfaceMode>[
  ChatSurfaceMode.normal,
  ChatSurfaceMode.workspace,
];

/// 聊天页面 AppBar
class ChatAppBar extends StatelessWidget {
  final VoidCallback onMenuTap;
  final VoidCallback? onPetTap;
  final bool isPetOpening;
  final bool isPetShowing;
  final VoidCallback? onOmniAiTap;
  final VoidCallback? onPureChatToggleTap;
  final VoidCallback? onAgentTap;
  final ValueChanged<String>? onAcpAgentTap;
  final VoidCallback? onPrimaryModeTap;
  final ChatSurfaceMode activeMode;
  final ValueChanged<ChatSurfaceMode> onModeChanged;
  final ChatIslandDisplayLayer displayLayer;
  final ValueChanged<ChatIslandDisplayLayer> onDisplayLayerChanged;
  final ValueChanged<BuildContext> onTerminalEnvironmentTap;
  final VoidCallback onTerminalTap;
  final VoidCallback onBrowserTap;
  final bool hasTerminalEnvironment;
  final bool isBrowserEnabled;
  final String? activeToolType;
  final bool isAgentReady;
  final bool isAgentConnected;
  final bool isAgentLoading;
  final bool isAgentSelected;
  final bool isOmniAiSelected;
  final List<ChatAcpAgentModeOption> acpAgentModes;
  final String? activeAcpAgentId;
  final bool showAppUpdateIndicator;
  final VoidCallback? onAppUpdateTap;
  final String? appUpdateTooltip;
  final bool translucent;
  final AppBackgroundVisualProfile visualProfile;
  final bool showMenuButton;
  final bool showSurfaceSwitcher;
  final bool showPureChatToggle;
  final bool isPureChatSelected;
  final bool isPureChatToggleLocked;
  final bool showWorkspacePaneButton;
  final VoidCallback? onWorkspacePaneTap;
  final Key? tutorialMenuAnchorKey;
  final Key? tutorialPetAnchorKey;
  final Key? tutorialIslandAnchorKey;
  final Key? tutorialModeAnchorKey;

  const ChatAppBar({
    super.key,
    required this.onMenuTap,
    this.onPetTap,
    this.isPetOpening = false,
    this.isPetShowing = false,
    this.onOmniAiTap,
    this.onPureChatToggleTap,
    this.onAgentTap,
    this.onAcpAgentTap,
    this.onPrimaryModeTap,
    required this.activeMode,
    required this.onModeChanged,
    this.displayLayer = ChatIslandDisplayLayer.mode,
    required this.onDisplayLayerChanged,
    required this.onTerminalEnvironmentTap,
    required this.onTerminalTap,
    required this.onBrowserTap,
    this.hasTerminalEnvironment = false,
    this.isBrowserEnabled = false,
    this.activeToolType,
    this.isAgentReady = false,
    this.isAgentConnected = false,
    this.isAgentLoading = false,
    this.isAgentSelected = false,
    this.isOmniAiSelected = true,
    this.acpAgentModes = const <ChatAcpAgentModeOption>[],
    this.activeAcpAgentId,
    this.showAppUpdateIndicator = false,
    this.onAppUpdateTap,
    this.appUpdateTooltip,
    this.translucent = false,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
    this.showMenuButton = true,
    this.showSurfaceSwitcher = true,
    this.showPureChatToggle = false,
    this.isPureChatSelected = false,
    this.isPureChatToggleLocked = true,
    this.showWorkspacePaneButton = false,
    this.onWorkspacePaneTap,
    this.tutorialMenuAnchorKey,
    this.tutorialPetAnchorKey,
    this.tutorialIslandAnchorKey,
    this.tutorialModeAnchorKey,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final iconTint = translucent
        ? visualProfile.appBarIconColor
        : context.isDarkTheme
        ? palette.textPrimary
        : Colors.grey[800]!;
    // The center island is the surface switch (normal chat vs workspace), not
    // the Harness selector. Keep it on a stable mode glyph so the selected
    // Harness is not shown twice beside the right-hand Harness menu. The
    // selected brand remains visible in the right-hand selector and in each
    // ACP run header.
    final primaryModeIconAsset = isPureChatSelected
        ? _kChatAppBarPureChatIconAsset
        : _kChatAppBarSurfaceAgentIconAsset;
    final primaryModeAgentId = null;
    const updateTint = Color(0xFFD4A017);
    final showWorkspaceButton =
        showWorkspacePaneButton && onWorkspacePaneTap != null;
    final showUpdateShortcutButton =
        showAppUpdateIndicator && onAppUpdateTap != null;
    final showPetButton = onPetTap != null;
    final appBarBackgroundColor = showSurfaceSwitcher
        ? palette.pageBackground
        : palette.surfacePrimary;
    return ColoredBox(
      key: const ValueKey('chat-app-bar-background'),
      color: translucent ? Colors.transparent : appBarBackgroundColor,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
        child: SizedBox(
          height: 50,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final leftActionRowWidth = showPetButton
                  ? _kChatAppBarAccessoryButtonSize
                  : 0.0;
              final leftReservedSpace =
                  (showMenuButton ? _kChatAppBarMenuButtonSize : 0) +
                  leftActionRowWidth +
                  (showPetButton
                      ? _kChatAppBarAccessoryGap * 2
                      : _kChatAppBarAccessoryGap);
              final rightActionCount =
                  (showUpdateShortcutButton ? 1 : 0) +
                  (showWorkspaceButton ? 1 : 0) +
                  1;
              final rightReservedSpace =
                  rightActionCount * _kChatAppBarRightActionSlotWidth +
                  _kChatAppBarAccessoryGap;
              final symmetricReservedSpace = math.max(
                leftReservedSpace,
                rightReservedSpace,
              );
              final islandWidth = math
                  .min(
                    _kChatAppBarIslandMaxWidth,
                    math.max(
                      0,
                      constraints.maxWidth - symmetricReservedSpace * 2,
                    ),
                  )
                  .toDouble();
              final islandCenterX = constraints.maxWidth / 2;
              final islandLeft = islandCenterX - islandWidth / 2;
              final accessoryLeftEdge = showMenuButton
                  ? _kChatAppBarMenuButtonSize + _kChatAppBarAccessoryGap
                  : _kChatAppBarAccessoryGap;
              final accessoryRightEdge = islandLeft - _kChatAppBarAccessoryGap;
              final accessoryAvailableWidth = math
                  .max(0, accessoryRightEdge - accessoryLeftEdge)
                  .toDouble();
              final accessoryMaxLeft = math.max(
                accessoryLeftEdge,
                accessoryRightEdge - leftActionRowWidth,
              );
              final accessoryRowLeft =
                  (accessoryLeftEdge +
                          ((accessoryAvailableWidth - leftActionRowWidth) / 2)
                              .clamp(0, double.infinity))
                      .clamp(accessoryLeftEdge, accessoryMaxLeft)
                      .toDouble();
              return Stack(
                alignment: Alignment.center,
                children: [
                  if (showMenuButton)
                    Positioned(
                      left: 0,
                      top: 0,
                      bottom: 0,
                      width: _kChatAppBarMenuButtonSize,
                      child: KeyedSubtree(
                        key: tutorialMenuAnchorKey,
                        child: Center(
                          child: GestureDetector(
                            key: const ValueKey('chat-app-bar-menu-button'),
                            onTap: onMenuTap,
                            child: Container(
                              color: Colors.transparent,
                              padding: const EdgeInsets.all(15),
                              child: SvgPicture.asset(
                                'assets/home/drawer_icon.svg',
                                width: 20,
                                height: 20,
                                colorFilter: ColorFilter.mode(
                                  iconTint,
                                  BlendMode.srcIn,
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  if (showPetButton)
                    Positioned(
                      left: accessoryRowLeft,
                      top: 0,
                      bottom: 0,
                      width: leftActionRowWidth,
                      child: KeyedSubtree(
                        key: tutorialPetAnchorKey,
                        child: _ChatAppBarPetButton(
                          isLoading: isPetOpening,
                          isShowing: isPetShowing,
                          iconTint: iconTint,
                          selectedColor: palette.accentPrimary,
                          onTap: onPetTap!,
                        ),
                      ),
                    ),
                  Center(
                    child: KeyedSubtree(
                      key: tutorialIslandAnchorKey,
                      child: SizedBox(
                        key: const ValueKey('chat-app-bar-island'),
                        width: islandWidth,
                        child: _ChatIslandSwitcher(
                          activeMode: activeMode,
                          onModeChanged: onModeChanged,
                          displayLayer: displayLayer,
                          onDisplayLayerChanged: onDisplayLayerChanged,
                          onTerminalEnvironmentTap: onTerminalEnvironmentTap,
                          onTerminalTap: onTerminalTap,
                          onBrowserTap: onBrowserTap,
                          hasTerminalEnvironment: hasTerminalEnvironment,
                          isBrowserEnabled: isBrowserEnabled,
                          activeToolType: activeToolType,
                          translucent: translucent,
                          visualProfile: visualProfile,
                          showSurfaceLayer: showSurfaceSwitcher,
                          primaryModeIconAsset: primaryModeIconAsset,
                          primaryModeAgentId: primaryModeAgentId,
                          onPrimaryModeTap: onPrimaryModeTap,
                        ),
                      ),
                    ),
                  ),
                  Align(
                    alignment: Alignment.centerRight,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (showUpdateShortcutButton)
                          GestureDetector(
                            key: const ValueKey('chat-app-update-button'),
                            onTap: onAppUpdateTap,
                            child: Tooltip(
                              message:
                                  appUpdateTooltip ??
                                  (LegacyTextLocalizer.isEnglish
                                      ? 'Check for updates'
                                      : '检查更新'),
                              child: Container(
                                color: Colors.transparent,
                                padding: const EdgeInsets.all(15),
                                child: SvgPicture.asset(
                                  _kChatAppBarUpdateSparklesAsset,
                                  width: 18,
                                  height: 18,
                                  colorFilter: const ColorFilter.mode(
                                    updateTint,
                                    BlendMode.srcIn,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        if (showWorkspaceButton)
                          SizedBox(
                            width: _kChatAppBarRightActionSlotWidth,
                            height: _kChatAppBarRightActionSlotWidth,
                            child: Center(
                              child: _ChatAppBarWorkspaceButton(
                                iconTint: iconTint,
                                onTap: onWorkspacePaneTap!,
                              ),
                            ),
                          ),
                        SizedBox(
                          width: _kChatAppBarRightActionSlotWidth,
                          height: _kChatAppBarRightActionSlotWidth,
                          child: Center(
                            child: KeyedSubtree(
                              key: tutorialModeAnchorKey,
                              child: _ChatAppBarModeShortcutButton(
                                key: const ValueKey(
                                  'chat-app-bar-pure-chat-button',
                                ),
                                iconTint: iconTint,
                                isAgentLoading: isAgentLoading,
                                isAgentSelected: isAgentSelected,
                                isOmniAiSelected: isOmniAiSelected,
                                acpAgentModes: acpAgentModes,
                                activeAcpAgentId: activeAcpAgentId,
                                isPureChatSelected: isPureChatSelected,
                                isPureChatToggleLocked: isPureChatToggleLocked,
                                onOmniAiTap: onOmniAiTap,
                                onAgentTap: onAgentTap,
                                onAcpAgentTap: onAcpAgentTap,
                                onPureChatToggleTap: onPureChatToggleTap,
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

enum _ChatAppBarModeShortcutKind { omniAi, xiaowanAcp, acpAgent, pureChat }

class _ChatAppBarModeShortcutAction {
  const _ChatAppBarModeShortcutAction._(this.kind, [this.agentId]);

  static const omniAi = _ChatAppBarModeShortcutAction._(
    _ChatAppBarModeShortcutKind.omniAi,
  );
  static const xiaowanAcp = _ChatAppBarModeShortcutAction._(
    _ChatAppBarModeShortcutKind.xiaowanAcp,
  );
  static const pureChat = _ChatAppBarModeShortcutAction._(
    _ChatAppBarModeShortcutKind.pureChat,
  );

  factory _ChatAppBarModeShortcutAction.acpAgent(String agentId) =>
      _ChatAppBarModeShortcutAction._(
        _ChatAppBarModeShortcutKind.acpAgent,
        agentId,
      );

  final _ChatAppBarModeShortcutKind kind;
  final String? agentId;
}

class _ChatAppBarPetButton extends StatelessWidget {
  const _ChatAppBarPetButton({
    required this.isLoading,
    required this.isShowing,
    required this.iconTint,
    required this.selectedColor,
    required this.onTap,
  });

  final bool isLoading;
  final bool isShowing;
  final Color iconTint;
  final Color selectedColor;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final tooltip = LegacyTextLocalizer.localize(isShowing ? '收起宠物' : '唤起宠物');
    final effectiveColor = isShowing ? selectedColor : iconTint;
    return Tooltip(
      message: tooltip,
      child: Semantics(
        label: tooltip,
        button: true,
        child: GestureDetector(
          key: const ValueKey('chat-app-bar-pet-button'),
          onTap: isLoading ? null : onTap,
          behavior: HitTestBehavior.opaque,
          child: SizedBox(
            width: _kChatAppBarAccessoryButtonSize,
            height: _kChatAppBarAccessoryButtonSize,
            child: Center(
              child: isLoading
                  ? SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        valueColor: AlwaysStoppedAnimation<Color>(
                          effectiveColor,
                        ),
                      ),
                    )
                  : Icon(LucideIcons.pawPrint, size: 20, color: effectiveColor),
            ),
          ),
        ),
      ),
    );
  }
}

class _ChatAppBarWorkspaceButton extends StatelessWidget {
  const _ChatAppBarWorkspaceButton({
    required this.iconTint,
    required this.onTap,
  });

  final Color iconTint;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: LegacyTextLocalizer.isEnglish ? 'Show workspace' : '显示工作区',
      child: GestureDetector(
        key: const ValueKey('chat-app-bar-workspace-pane-button'),
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: SizedBox(
          width: _kChatAppBarAccessoryButtonSize,
          height: _kChatAppBarAccessoryButtonSize,
          child: Center(
            child: SvgPicture.asset(
              _kChatAppBarWorkspaceIconAsset,
              width: 20,
              height: 20,
              colorFilter: ColorFilter.mode(iconTint, BlendMode.srcIn),
            ),
          ),
        ),
      ),
    );
  }
}

class _ChatAppBarModeShortcutButton extends StatefulWidget {
  const _ChatAppBarModeShortcutButton({
    super.key,
    required this.iconTint,
    required this.isAgentLoading,
    required this.isAgentSelected,
    required this.isOmniAiSelected,
    required this.acpAgentModes,
    required this.activeAcpAgentId,
    required this.isPureChatSelected,
    required this.isPureChatToggleLocked,
    required this.onOmniAiTap,
    required this.onAgentTap,
    required this.onAcpAgentTap,
    required this.onPureChatToggleTap,
  });

  final Color iconTint;
  final bool isAgentLoading;
  final bool isAgentSelected;
  final bool isOmniAiSelected;
  final List<ChatAcpAgentModeOption> acpAgentModes;
  final String? activeAcpAgentId;
  final bool isPureChatSelected;
  final bool isPureChatToggleLocked;
  final VoidCallback? onOmniAiTap;
  final VoidCallback? onAgentTap;
  final ValueChanged<String>? onAcpAgentTap;
  final VoidCallback? onPureChatToggleTap;

  @override
  State<_ChatAppBarModeShortcutButton> createState() =>
      _ChatAppBarModeShortcutButtonState();
}

class _ChatAppBarModeShortcutButtonState
    extends State<_ChatAppBarModeShortcutButton> {
  bool _isOpen = false;
  late final ValueNotifier<bool> _isAgentLoadingNotifier;

  @override
  void initState() {
    super.initState();
    _isAgentLoadingNotifier = ValueNotifier<bool>(widget.isAgentLoading);
  }

  @override
  void didUpdateWidget(covariant _ChatAppBarModeShortcutButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.isAgentLoading == widget.isAgentLoading) {
      return;
    }
    final isAgentLoading = widget.isAgentLoading;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _isAgentLoadingNotifier.value = isAgentLoading;
      }
    });
  }

  @override
  void dispose() {
    _isAgentLoadingNotifier.dispose();
    super.dispose();
  }

  Future<void> _openMenu() async {
    if (_isOpen) {
      return;
    }
    final anchor = glassPopupAnchorFromContext(context);
    if (anchor == null) {
      return;
    }
    setState(() => _isOpen = true);
    final palette = context.omniPalette;
    final selectedColor = palette.accentPrimary;
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    final canSelectPureChat =
        widget.isAgentSelected ||
        (!widget.isPureChatToggleLocked && widget.onPureChatToggleTap != null);
    // The mode menu is a runtime switcher, not the installation screen.
    final acpAgentModes = widget.acpAgentModes
        .where((agent) => agent.isAvailable)
        .toList(growable: false);
    ChatAcpAgentModeOption? xiaowan;
    for (final agent in acpAgentModes) {
      if (agent.id == 'xiaowan-acp') {
        xiaowan = agent;
        break;
      }
    }
    final otherAgents = acpAgentModes
        .where((agent) => agent.id != 'xiaowan-acp')
        .toList(growable: false);
    final popupAnchor = Rect.fromLTWH(anchor.left, anchor.top, anchor.width, 0);

    final action = await showGlassPopup<_ChatAppBarModeShortcutAction>(
      context: context,
      // 从原按钮的顶边开始绘制完整胶囊，让顶部图标行与下面三项共用同一个
      // surface、裁剪边界和动画时间线，不再把动画拆成上下两段。
      anchor: popupAnchor,
      verticalGap: 0,
      transitionDuration: _kChatAppBarModeMenuOpenDuration,
      reverseTransitionDuration: _kChatAppBarModeMenuCloseDuration,
      unfoldAlignment: Alignment.topCenter,
      child: ValueListenableBuilder<bool>(
        valueListenable: _isAgentLoadingNotifier,
        builder: (context, isAgentLoading, _) {
          final xiaowanItem = xiaowan == null
              ? _ChatAppBarModeShortcutMenuItemData(
                  action: _ChatAppBarModeShortcutAction.omniAi,
                  iconAsset: _kChatAppBarAgentIconAsset,
                  tooltip: isEnglish ? 'OmniAi' : '小万',
                  selected:
                      widget.isOmniAiSelected &&
                      (widget.activeAcpAgentId?.trim().isEmpty ?? true),
                  // Xiaowan is the built-in Agent and is always a valid
                  // selection. The parent keeps the legacy callback as a
                  // compatibility fallback when ACP is not supplied.
                  enabled: true,
                  iconSize: _kChatAppBarModeMenuOmniAiIconSize,
                  iconOffset: _kChatAppBarModeMenuOmniAiIconOffset,
                )
              : _ChatAppBarModeShortcutMenuItemData(
                  action: _ChatAppBarModeShortcutAction.xiaowanAcp,
                  iconAsset: _kChatAppBarAgentIconAsset,
                  tooltip: xiaowan.name,
                  selected:
                      xiaowan.id == widget.activeAcpAgentId ||
                      (widget.isOmniAiSelected &&
                          (widget.activeAcpAgentId?.trim().isEmpty ?? true)),
                  // Do not make the built-in Agent depend on asynchronous
                  // callback/catalog refresh state. Selection is handled by
                  // the common action-return path below.
                  enabled: true,
                  iconSize: _chatAppBarModeMenuAgentIconSize(xiaowan.id),
                );
          return _ChatAppBarModeShortcutMenuContent(
            width: _kChatAppBarAccessoryButtonSize,
            closeTooltip: isEnglish ? 'Close mode menu' : '收起模式菜单',
            headerIcon: _buildOpenIcon(selectedColor),
            items: [
              xiaowanItem,
              for (final agent in otherAgents)
                _ChatAppBarModeShortcutMenuItemData(
                  action: _ChatAppBarModeShortcutAction.acpAgent(agent.id),
                  agentId: agent.id,
                  tooltip: agent.name,
                  selected: agent.id == widget.activeAcpAgentId,
                  // Keep every installed Harness tappable while another
                  // Harness is handshaking. The native ACP selector cancels
                  // the superseded connect attempt; disabling rows here made
                  // an unrelated slow Harness (notably DeepSeek) freeze the
                  // whole switcher.
                  enabled:
                      widget.onAcpAgentTap != null ||
                      widget.onAgentTap != null,
                  iconSize: _chatAppBarModeMenuAgentIconSize(agent.id),
                ),
              _ChatAppBarModeShortcutMenuItemData(
                action: _ChatAppBarModeShortcutAction.pureChat,
                iconAsset: _kChatAppBarPureChatIconAsset,
                tooltip: isEnglish ? 'Pure chat' : '纯聊天模式',
                selected: widget.isPureChatSelected,
                enabled: canSelectPureChat,
                iconSize: _kChatAppBarModeMenuPureChatIconSize,
              ),
            ],
            selectedColor: selectedColor,
            // popup 有自己的不透明 surface，不能复用为聊天壁纸适配的 AppBar
            // iconTint；后者在浅色主题 + 深色壁纸时可能接近白色。
            iconTint: palette.textSecondary,
            disabledTint: palette.textTertiary,
          );
        },
      ),
    );
    if (mounted) {
      setState(() => _isOpen = false);
    }
    switch (action?.kind) {
      case _ChatAppBarModeShortcutKind.omniAi:
        // The single Xiaowan shortcut is backed by the official ACP profile
        // whenever the ACP callback is available. Keep the old callback only
        // for callers that do not provide the ACP surface yet.
        if (widget.onAcpAgentTap != null) {
          widget.onAcpAgentTap!('xiaowan-acp');
        } else {
          widget.onOmniAiTap?.call();
        }
        break;
      case _ChatAppBarModeShortcutKind.xiaowanAcp:
        if (widget.onAcpAgentTap != null) {
          widget.onAcpAgentTap!('xiaowan-acp');
        } else {
          widget.onOmniAiTap?.call();
        }
        break;
      case _ChatAppBarModeShortcutKind.acpAgent:
        final agentId = action?.agentId?.trim() ?? '';
        if (agentId.isNotEmpty && widget.onAcpAgentTap != null) {
          widget.onAcpAgentTap!(agentId);
        } else {
          widget.onAgentTap?.call();
        }
        break;
      case _ChatAppBarModeShortcutKind.pureChat:
        widget.onPureChatToggleTap?.call();
        break;
      case null:
        break;
    }
  }

  String _closedIconAsset() {
    if (widget.isPureChatSelected) {
      return _kChatAppBarPureChatIconAsset;
    }
    if (widget.isOmniAiSelected) {
      return _kChatAppBarAgentIconAsset;
    }
    return _kChatAppBarModeMenuClosedIconAsset;
  }

  Widget _buildClosedIcon(Color color) {
    final activeAgentId = widget.activeAcpAgentId?.trim() ?? '';
    // The selector is an identity control, not a progress indicator. Always
    // keep a stable brand/fallback icon visible; DeepSeek's longer native ACP
    // startup must never turn every Harness tap into a global spinner.
    if (!widget.isPureChatSelected && activeAgentId.isNotEmpty) {
      return AgentBrandIcon(
        key: ValueKey('chat-app-bar-active-agent-icon-$activeAgentId'),
        agentId: activeAgentId,
        size: 22,
        tint: color,
      );
    }
    // Keep the closed surface icons at the same visual size as the ACP brand
    // icon. A 20dp fallback made the selected Xiaowan/pure-chat avatar jump
    // when switching Harnesses.
    const iconSize = 22.0;
    return SvgPicture.asset(
      _closedIconAsset(),
      width: iconSize,
      height: iconSize,
      colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
    );
  }

  Widget _buildOpenIcon(Color color) {
    return SvgPicture.asset(
      _kChatAppBarModeMenuOpenIconAsset,
      width: 20,
      height: 20,
      colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final selectedColor = palette.accentPrimary;
    final hasSelectedMode =
        widget.isOmniAiSelected ||
        widget.isAgentSelected ||
        widget.isPureChatSelected ||
        (widget.activeAcpAgentId?.trim().isNotEmpty ?? false);
    final effectiveIconColor = _isOpen || hasSelectedMode
        ? selectedColor
        : widget.iconTint;
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    final icon = Center(
      child: _isOpen
          ? _buildOpenIcon(effectiveIconColor)
          : _buildClosedIcon(effectiveIconColor),
    );
    return Tooltip(
      message: _isOpen
          ? (isEnglish ? 'Close mode menu' : '收起模式菜单')
          : (isEnglish ? 'Switch chat mode' : '切换聊天模式'),
      child: GestureDetector(
        onTap: _openMenu,
        behavior: HitTestBehavior.opaque,
        child: SizedBox(
          width: _kChatAppBarAccessoryButtonSize,
          height: _kChatAppBarAccessoryButtonSize,
          // 保留原按钮图标作为 overlay 下方的静止底稿；完整胶囊从它的顶边
          // 覆盖展开，可避免 popup 首帧的 clip/fade 让顶部图标短暂消失。
          child: icon,
        ),
      ),
    );
  }
}

class _ChatAppBarModeShortcutMenuItemData {
  const _ChatAppBarModeShortcutMenuItemData({
    required this.action,
    this.iconAsset,
    this.agentId,
    required this.tooltip,
    required this.selected,
    required this.enabled,
    this.iconSize = 20,
    this.iconOffset = Offset.zero,
  });

  final _ChatAppBarModeShortcutAction action;
  final String? iconAsset;
  final String? agentId;
  final String tooltip;
  final bool selected;
  final bool enabled;
  final double iconSize;
  final Offset iconOffset;
}

class _ChatAppBarModeShortcutMenuContent extends StatelessWidget {
  const _ChatAppBarModeShortcutMenuContent({
    required this.width,
    required this.closeTooltip,
    required this.headerIcon,
    required this.items,
    required this.selectedColor,
    required this.iconTint,
    required this.disabledTint,
  });

  final double width;
  final String closeTooltip;
  final Widget headerIcon;
  final List<_ChatAppBarModeShortcutMenuItemData> items;
  final Color selectedColor;
  final Color iconTint;
  final Color disabledTint;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final mediaQuery = MediaQuery.of(context);
    final maxHeight =
        mediaQuery.size.height -
        mediaQuery.padding.vertical -
        mediaQuery.viewInsets.vertical -
        16;
    final panel = OmniGlassPanel(
      key: const ValueKey('chat-app-bar-mode-menu-capsule'),
      borderRadius: BorderRadius.circular(width / 2),
      // 40px 宽、20px 顶部圆角时，top: 0 的 1px 高光会被圆弧裁成
      // 顶部中央的一颗亮点；此处关闭局部高光，保留完整边框与阴影。
      showTopHighlight: false,
      surfaceColor: palette.surfaceElevated,
      child: Material(
        color: Colors.transparent,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Tooltip(
              message: closeTooltip,
              child: InkWell(
                key: const ValueKey('chat-app-bar-mode-menu-close'),
                onTap: () => Navigator.of(context).pop(),
                borderRadius: BorderRadius.vertical(
                  top: Radius.circular(width / 2),
                ),
                child: SizedBox(
                  height: _kChatAppBarAccessoryButtonSize,
                  child: Center(child: headerIcon),
                ),
              ),
            ),
            const SizedBox(height: 6),
            Flexible(
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    for (final item in items)
                      _ChatAppBarModeShortcutMenuRow(
                        key: ValueKey(
                          'chat-app-bar-mode-menu-${_chatModeShortcutActionSlug(item.action)}',
                        ),
                        item: item,
                        selectedColor: selectedColor,
                        iconTint: iconTint,
                        disabledTint: disabledTint,
                      ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 6),
          ],
        ),
      ),
    );
    return SizedBox(
      width: width,
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: maxHeight.clamp(120.0, 720.0).toDouble(),
        ),
        child: panel,
      ),
    );
  }
}

String _chatModeShortcutActionSlug(_ChatAppBarModeShortcutAction action) {
  return switch (action.kind) {
    _ChatAppBarModeShortcutKind.omniAi => 'omni-ai',
    _ChatAppBarModeShortcutKind.xiaowanAcp => 'xiaowan-acp',
    _ChatAppBarModeShortcutKind.acpAgent => 'acp-${action.agentId ?? 'agent'}',
    _ChatAppBarModeShortcutKind.pureChat => 'pure-chat',
  };
}

class _ChatAppBarModeShortcutMenuRow extends StatelessWidget {
  const _ChatAppBarModeShortcutMenuRow({
    super.key,
    required this.item,
    required this.selectedColor,
    required this.iconTint,
    required this.disabledTint,
  });

  final _ChatAppBarModeShortcutMenuItemData item;
  final Color selectedColor;
  final Color iconTint;
  final Color disabledTint;

  @override
  Widget build(BuildContext context) {
    final color = !item.enabled
        ? disabledTint
        : (item.selected ? selectedColor : iconTint);
    return Tooltip(
      message: item.tooltip,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: item.enabled
            ? () {
                Navigator.of(context).pop(item.action);
              }
            : null,
        child: SizedBox(
          height: 40,
          child: Center(
            child: Transform.translate(
              offset: item.iconOffset,
              child: item.agentId?.trim().isNotEmpty == true
                  ? AgentBrandIcon(
                      agentId: item.agentId!,
                      size: item.iconSize,
                      tint: color,
                    )
                  : SvgPicture.asset(
                      item.iconAsset!,
                      width: item.iconSize,
                      height: item.iconSize,
                      colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
                    ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ChatIslandSwitcher extends StatefulWidget {
  const _ChatIslandSwitcher({
    required this.activeMode,
    required this.onModeChanged,
    required this.displayLayer,
    required this.onDisplayLayerChanged,
    required this.onTerminalEnvironmentTap,
    required this.onTerminalTap,
    required this.onBrowserTap,
    required this.hasTerminalEnvironment,
    required this.isBrowserEnabled,
    this.activeToolType,
    this.translucent = false,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
    this.showSurfaceLayer = true,
    required this.primaryModeIconAsset,
    this.primaryModeAgentId,
    this.onPrimaryModeTap,
  });

  final ChatSurfaceMode activeMode;
  final ValueChanged<ChatSurfaceMode> onModeChanged;
  final ChatIslandDisplayLayer displayLayer;
  final ValueChanged<ChatIslandDisplayLayer> onDisplayLayerChanged;
  final ValueChanged<BuildContext> onTerminalEnvironmentTap;
  final VoidCallback onTerminalTap;
  final VoidCallback onBrowserTap;
  final bool hasTerminalEnvironment;
  final bool isBrowserEnabled;
  final String? activeToolType;
  final bool translucent;
  final AppBackgroundVisualProfile visualProfile;
  final bool showSurfaceLayer;
  final String? primaryModeIconAsset;
  final String? primaryModeAgentId;
  final VoidCallback? onPrimaryModeTap;

  @override
  State<_ChatIslandSwitcher> createState() => _ChatIslandSwitcherState();
}

class _ChatIslandSwitcherState extends State<_ChatIslandSwitcher> {
  static const String _terminalIconAsset = 'assets/home/chat/terminal.svg';
  static const String _browserIconAsset = 'assets/home/chat/browser.svg';
  static const String _environmentIconAsset =
      'assets/home/chat/environment.svg';
  static const Duration _switchDuration = Duration(milliseconds: 460);
  static const double _verticalSwitchThreshold = 10;
  static const double _verticalVelocityThreshold = 240;
  static const double _switcherHeight = 32;
  static const double _offstageLayerGap = 2;

  double _verticalDragDelta = 0;

  List<ChatIslandDisplayLayer> get _visibleLayers =>
      const <ChatIslandDisplayLayer>[
        ChatIslandDisplayLayer.tools,
        ChatIslandDisplayLayer.mode,
      ];

  ChatIslandDisplayLayer get _effectiveDisplayLayer =>
      _visibleLayers.contains(widget.displayLayer)
      ? widget.displayLayer
      : _visibleLayers.last;

  int _layerOrder(ChatIslandDisplayLayer layer) =>
      _visibleLayers.indexOf(layer);

  void _handleVerticalDragUpdate(DragUpdateDetails details) {
    if (widget.activeMode != ChatSurfaceMode.normal ||
        _visibleLayers.length < 2) {
      return;
    }
    _verticalDragDelta += details.delta.dy;
  }

  void _handleVerticalDragEnd(DragEndDetails details) {
    final velocity = details.primaryVelocity ?? 0;
    final shouldToggle =
        _verticalDragDelta.abs() > _verticalSwitchThreshold ||
        velocity.abs() > _verticalVelocityThreshold;
    if (!shouldToggle) {
      _verticalDragDelta = 0;
      return;
    }
    final intent = _verticalDragDelta + velocity * 0.015;
    _verticalDragDelta = 0;

    if (widget.activeMode != ChatSurfaceMode.normal) {
      return;
    }
    final targetLayer = intent > 0
        ? ChatIslandDisplayLayer.tools
        : ChatIslandDisplayLayer.mode;
    if (_visibleLayers.contains(targetLayer) &&
        _effectiveDisplayLayer != targetLayer) {
      widget.onDisplayLayerChanged(targetLayer);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final islandBaseColor = widget.translucent
        ? palette.surfacePrimary
        : context.isDarkTheme
        ? palette.surfaceSecondary
        : palette.surfacePrimary;
    final toolLayerWidget = _ChatToolSlider(
      environmentIconAsset: _environmentIconAsset,
      terminalIconAsset: _terminalIconAsset,
      browserIconAsset: _browserIconAsset,
      activeToolType: widget.activeToolType,
      hasTerminalEnvironment: widget.hasTerminalEnvironment,
      onTerminalEnvironmentTap: (anchorContext) {
        widget.onTerminalEnvironmentTap(anchorContext);
      },
      isBrowserEnabled: widget.isBrowserEnabled,
      onTerminalTap: () {
        widget.onTerminalTap();
      },
      onBrowserTap: () {
        widget.onBrowserTap();
      },
      visualProfile: widget.visualProfile,
    );
    final modeLayerWidget = widget.showSurfaceLayer
        ? ChatModeSlider(
            activeMode: widget.activeMode,
            onChanged: widget.onModeChanged,
            visualProfile: widget.visualProfile,
            primaryIconAsset: widget.primaryModeIconAsset,
            primaryAgentId: widget.primaryModeAgentId,
            onPrimaryModeTap: widget.onPrimaryModeTap,
          )
        : _ChatSingleModePill(
            iconAsset: widget.primaryModeIconAsset,
            agentId: widget.primaryModeAgentId,
            onTap: widget.onPrimaryModeTap,
          );
    final currentOrder = _layerOrder(_effectiveDisplayLayer);

    double topFor(ChatIslandDisplayLayer layer) {
      final delta = _layerOrder(layer) - currentOrder;
      if (delta == 0) return 0;
      final direction = delta > 0 ? 1 : -1;
      return delta * _switcherHeight + direction * _offstageLayerGap;
    }

    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundSurfaceColor(
          translucent: widget.translucent,
          baseColor: islandBaseColor,
          opacity: 0.78,
        ),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(
          color: widget.translucent
              ? widget.visualProfile.islandBorderColor
              : palette.borderSubtle.withValues(alpha: 0.72),
        ),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(999),
        child: SizedBox(
          height: _switcherHeight,
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onVerticalDragUpdate: _handleVerticalDragUpdate,
            onVerticalDragEnd: _handleVerticalDragEnd,
            onVerticalDragCancel: () {
              _verticalDragDelta = 0;
            },
            child: Stack(
              fit: StackFit.expand,
              clipBehavior: Clip.hardEdge,
              children: [
                AnimatedPositioned(
                  duration: _switchDuration,
                  curve: Curves.easeInOutCubicEmphasized,
                  left: 0,
                  right: 0,
                  height: _switcherHeight,
                  top: topFor(ChatIslandDisplayLayer.mode),
                  child: ClipRect(child: modeLayerWidget),
                ),
                AnimatedPositioned(
                  duration: _switchDuration,
                  curve: Curves.easeInOutCubicEmphasized,
                  left: 0,
                  right: 0,
                  height: _switcherHeight,
                  top: topFor(ChatIslandDisplayLayer.tools),
                  child: ClipRect(child: toolLayerWidget),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ChatToolSlider extends StatelessWidget {
  final String environmentIconAsset;
  final String terminalIconAsset;
  final String browserIconAsset;
  final String? activeToolType;
  final bool hasTerminalEnvironment;
  final ValueChanged<BuildContext> onTerminalEnvironmentTap;
  final bool isBrowserEnabled;
  final VoidCallback onTerminalTap;
  final VoidCallback onBrowserTap;
  final AppBackgroundVisualProfile visualProfile;

  const _ChatToolSlider({
    required this.environmentIconAsset,
    required this.terminalIconAsset,
    required this.browserIconAsset,
    this.activeToolType,
    required this.hasTerminalEnvironment,
    required this.onTerminalEnvironmentTap,
    this.isBrowserEnabled = false,
    required this.onTerminalTap,
    required this.onBrowserTap,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
  });

  bool get _isBrowserActive => activeToolType?.trim() == 'browser';
  bool get _isTerminalActive => !_isBrowserActive;

  Alignment get _activeAlignment =>
      _isBrowserActive ? Alignment.centerRight : Alignment.center;

  @override
  Widget build(BuildContext context) {
    final activeGradient = context.isDarkTheme
        ? _kDarkChatAccentGradient
        : const <Color>[Color(0xFF2DA5F0), Color(0xFF1930D9)];
    return SizedBox(
      height: 32,
      child: Container(
        height: 32,
        padding: const EdgeInsets.all(2),
        decoration: BoxDecoration(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Stack(
          children: [
            AnimatedAlign(
              duration: const Duration(milliseconds: 280),
              curve: Curves.easeOutCubic,
              alignment: _activeAlignment,
              child: FractionallySizedBox(
                widthFactor: 1 / 3,
                child: Container(
                  margin: const EdgeInsets.symmetric(horizontal: 1),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: activeGradient,
                    ),
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
              ),
            ),
            Row(
              children: [
                Expanded(child: _buildEnvironmentButton(context)),
                Expanded(
                  child: _buildToolSegment(
                    context: context,
                    key: const ValueKey('chat-island-terminal-button'),
                    isSelected: _isTerminalActive,
                    isEnabled: true,
                    tooltip: LegacyTextLocalizer.isEnglish
                        ? 'Open terminal'
                        : '打开终端',
                    onTap: onTerminalTap,
                    child: SvgPicture.asset(
                      terminalIconAsset,
                      width: 16,
                      height: 16,
                    ),
                  ),
                ),
                Expanded(
                  child: _buildToolSegment(
                    context: context,
                    key: const ValueKey('chat-island-browser-button'),
                    isSelected: _isBrowserActive,
                    isEnabled: isBrowserEnabled,
                    tooltip: isBrowserEnabled
                        ? (LegacyTextLocalizer.isEnglish
                              ? 'Open browser for current session'
                              : '打开当前会话浏览器')
                        : (LegacyTextLocalizer.isEnglish
                              ? 'No browser session available'
                              : '当前会话还没有可用的浏览器会话'),
                    onTap: onBrowserTap,
                    child: SvgPicture.asset(
                      browserIconAsset,
                      width: 16,
                      height: 16,
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

  Widget _buildEnvironmentButton(BuildContext context) {
    final inactiveColor = context.isDarkTheme
        ? context.omniPalette.textSecondary
        : visualProfile.secondaryTextColor;
    return Builder(
      builder: (anchorContext) {
        return Tooltip(
          message: LegacyTextLocalizer.isEnglish
              ? 'Manage terminal environment variables'
              : '管理终端环境变量',
          child: InkWell(
            key: const ValueKey('chat-island-terminal-env-button'),
            onTap: () => onTerminalEnvironmentTap(anchorContext),
            borderRadius: BorderRadius.circular(999),
            child: SizedBox.expand(
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 220),
                curve: Curves.easeOutCubic,
                margin: const EdgeInsets.symmetric(horizontal: 1),
                decoration: BoxDecoration(
                  color: Colors.transparent,
                  borderRadius: BorderRadius.circular(999),
                ),
                alignment: Alignment.center,
                child: ColorFiltered(
                  colorFilter: ColorFilter.mode(inactiveColor, BlendMode.srcIn),
                  child: SvgPicture.asset(
                    environmentIconAsset,
                    width: 15,
                    height: 15,
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildToolSegment({
    required BuildContext context,
    required Key key,
    required bool isSelected,
    required bool isEnabled,
    required String tooltip,
    required VoidCallback onTap,
    required Widget child,
  }) {
    final inactiveColor = context.isDarkTheme
        ? context.omniPalette.textSecondary
        : visualProfile.secondaryTextColor;
    final color = !isEnabled
        ? inactiveColor.withValues(alpha: 0.72)
        : isSelected
        ? Theme.of(context).colorScheme.onPrimary
        : inactiveColor;
    return Tooltip(
      message: tooltip,
      child: InkWell(
        key: key,
        onTap: isEnabled ? onTap : null,
        borderRadius: BorderRadius.circular(999),
        child: Center(
          child: AnimatedScale(
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            scale: isSelected ? 1 : 0.95,
            child: ColorFiltered(
              colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
              child: child,
            ),
          ),
        ),
      ),
    );
  }
}

class _ChatSingleModePill extends StatelessWidget {
  const _ChatSingleModePill({
    required this.iconAsset,
    this.agentId,
    this.onTap,
  });

  final String? iconAsset;
  final String? agentId;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final activeGradient = context.isDarkTheme
        ? _kDarkChatAccentGradient
        : const <Color>[Color(0xFF2DA5F0), Color(0xFF1930D9)];
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Container(
        height: 32,
        padding: const EdgeInsets.all(2),
        decoration: BoxDecoration(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(999),
        ),
        child: DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: activeGradient,
            ),
            borderRadius: BorderRadius.circular(999),
          ),
          child: Center(
            child: agentId?.trim().isNotEmpty == true
                ? AgentBrandIcon(
                    key: const ValueKey('chat-island-single-mode-icon'),
                    agentId: agentId!,
                    size: 16,
                    tint: Theme.of(context).colorScheme.onPrimary,
                  )
                : SvgPicture.asset(
                    iconAsset!,
                    key: const ValueKey('chat-island-single-mode-icon'),
                    width: 16,
                    height: 16,
                    colorFilter: ColorFilter.mode(
                      Theme.of(context).colorScheme.onPrimary,
                      BlendMode.srcIn,
                    ),
                  ),
          ),
        ),
      ),
    );
  }
}
