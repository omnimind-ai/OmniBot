import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/home_greeting_settings_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/agent_brand_icon.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/omni_glass.dart';
import '../../../../../models/chat_message_model.dart';
import '../../../../../services/app_background_service.dart';
import '../../../../../widgets/app_background_widgets.dart';
import '../chat_page_models.dart';
import '../utils/agent_run_timeline.dart';
import 'package:ui/services/agent_message_kinds.dart';
import '../../command_overlay/widgets/message_bubble.dart';
import '../../command_overlay/widgets/chat_input_area.dart';
import 'agent_run_group_message.dart';
import 'chat_empty_greeting.dart';

const String _kChatAppBarUpdateSparklesAsset =
    'assets/home/chat/update_sparkles.svg';
const String _kChatAppBarAgentIconAsset = 'assets/home/avatar.svg';
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
    'codex-acp' || 'codex-remote' => 19,
    'claude-code-acp' => 21,
    'opencode-acp' => 22,
    _ => 20,
  };
}

enum ChatSurfaceMode { workspace, normal, openclaw }

class ChatAcpAgentModeOption {
  const ChatAcpAgentModeOption({required this.id, required this.name});

  final String id;
  final String name;
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
    final primaryModeIconAsset = isAgentSelected
        ? null
        : isPureChatSelected
        ? _kChatAppBarPureChatIconAsset
        : _kChatAppBarAgentIconAsset;
    final primaryModeAgentId = isAgentSelected
        ? (activeAcpAgentId?.trim().isNotEmpty == true
              ? activeAcpAgentId!.trim()
              : 'generic-agent')
        : null;
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

enum _ChatAppBarModeShortcutKind { omniAi, acpAgent, pureChat }

class _ChatAppBarModeShortcutAction {
  const _ChatAppBarModeShortcutAction._(this.kind, [this.agentId]);

  static const omniAi = _ChatAppBarModeShortcutAction._(
    _ChatAppBarModeShortcutKind.omniAi,
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
    final acpAgentModes = widget.acpAgentModes.isNotEmpty
        ? widget.acpAgentModes
        : widget.onAgentTap != null
        ? const <ChatAcpAgentModeOption>[
            ChatAcpAgentModeOption(id: 'generic-agent', name: 'Agent'),
          ]
        : const <ChatAcpAgentModeOption>[];
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
        builder: (context, isAgentLoading, _) =>
            _ChatAppBarModeShortcutMenuContent(
              width: _kChatAppBarAccessoryButtonSize,
              closeTooltip: isEnglish ? 'Close mode menu' : '收起模式菜单',
              headerIcon: _buildOpenIcon(selectedColor),
              items: [
                _ChatAppBarModeShortcutMenuItemData(
                  action: _ChatAppBarModeShortcutAction.omniAi,
                  iconAsset: _kChatAppBarAgentIconAsset,
                  tooltip: isEnglish ? 'OmniAi' : '小万',
                  selected: widget.isOmniAiSelected,
                  enabled: widget.onOmniAiTap != null,
                  iconSize: _kChatAppBarModeMenuOmniAiIconSize,
                  iconOffset: _kChatAppBarModeMenuOmniAiIconOffset,
                ),
                for (final agent in acpAgentModes)
                  _ChatAppBarModeShortcutMenuItemData(
                    action: _ChatAppBarModeShortcutAction.acpAgent(agent.id),
                    agentId: agent.id,
                    tooltip: agent.name,
                    selected:
                        widget.isAgentSelected &&
                        agent.id == widget.activeAcpAgentId,
                    enabled:
                        !isAgentLoading &&
                        (widget.onAcpAgentTap != null ||
                            widget.onAgentTap != null),
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
            ),
      ),
    );
    if (mounted) {
      setState(() => _isOpen = false);
    }
    switch (action?.kind) {
      case _ChatAppBarModeShortcutKind.omniAi:
        widget.onOmniAiTap?.call();
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
    if (widget.isAgentLoading && !widget.isAgentSelected) {
      return SizedBox(
        width: 18,
        height: 18,
        child: CircularProgressIndicator(
          strokeWidth: 2,
          valueColor: AlwaysStoppedAnimation<Color>(color),
        ),
      );
    }
    if (widget.isAgentSelected) {
      return AgentBrandIcon(
        key: ValueKey(
          'chat-app-bar-active-agent-icon-${widget.activeAcpAgentId ?? 'generic-agent'}',
        ),
        agentId: widget.activeAcpAgentId ?? 'generic-agent',
        size: 22,
        tint: color,
      );
    }
    const iconSize = 20.0;
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
        widget.isPureChatSelected;
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
    return SizedBox(
      width: width,
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: maxHeight.clamp(120.0, 720.0).toDouble(),
        ),
        child: OmniGlassPanel(
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
        ),
      ),
    );
  }
}

String _chatModeShortcutActionSlug(_ChatAppBarModeShortcutAction action) {
  return switch (action.kind) {
    _ChatAppBarModeShortcutKind.omniAi => 'omni-ai',
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
      child: InkWell(
        onTap: item.enabled
            ? () => Navigator.of(context).pop(item.action)
            : null,
        borderRadius: BorderRadius.circular(12),
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

class ChatModeSlider extends StatefulWidget {
  final ChatSurfaceMode activeMode;
  final ValueChanged<ChatSurfaceMode> onChanged;
  final AppBackgroundVisualProfile visualProfile;
  final String? primaryIconAsset;
  final String? primaryAgentId;
  final VoidCallback? onPrimaryModeTap;

  const ChatModeSlider({
    super.key,
    required this.activeMode,
    required this.onChanged,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
    this.primaryIconAsset = _kChatAppBarAgentIconAsset,
    this.primaryAgentId,
    this.onPrimaryModeTap,
  });

  @override
  State<ChatModeSlider> createState() => _ChatModeSliderState();
}

class _ChatModeSliderState extends State<ChatModeSlider> {
  static const String _workspaceIconAsset = 'assets/home/chat/workspace.svg';

  double _dragDelta = 0;

  int get _activeVisibleModeIndex {
    final index = kVisibleChatSurfaceModes.indexOf(widget.activeMode);
    if (index >= 0) {
      return index;
    }
    return 0;
  }

  void _handleDragEnd({double velocity = 0}) {
    final intent = _dragDelta + velocity * 0.015;
    final shouldSwitch = _dragDelta.abs() > 14 || velocity.abs() > 250;
    if (shouldSwitch) {
      final currentIndex = _activeVisibleModeIndex;
      final delta = intent > 0 ? 1 : -1;
      final targetIndex = (currentIndex + delta).clamp(
        0,
        kVisibleChatSurfaceModes.length - 1,
      );
      widget.onChanged(kVisibleChatSurfaceModes[targetIndex]);
    }
    _dragDelta = 0;
  }

  @override
  Widget build(BuildContext context) {
    final activeGradient = context.isDarkTheme
        ? _kDarkChatAccentGradient
        : const <Color>[Color(0xFF2DA5F0), Color(0xFF1930D9)];
    final alignment = _activeVisibleModeIndex == 0
        ? Alignment.centerLeft
        : Alignment.centerRight;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onHorizontalDragUpdate: (details) {
        _dragDelta += details.delta.dx;
      },
      onHorizontalDragEnd: (details) {
        _handleDragEnd(velocity: details.primaryVelocity ?? 0);
      },
      onTapUp: (details) {
        final box = context.findRenderObject() as RenderBox?;
        if (box == null || !box.hasSize) return;
        final local = box.globalToLocal(details.globalPosition);
        final segmentWidth = box.size.width / kVisibleChatSurfaceModes.length;
        final targetIndex = (local.dx / segmentWidth).floor().clamp(
          0,
          kVisibleChatSurfaceModes.length - 1,
        );
        if (targetIndex == 0 &&
            targetIndex == _activeVisibleModeIndex &&
            widget.onPrimaryModeTap != null) {
          widget.onPrimaryModeTap?.call();
          return;
        }
        widget.onChanged(kVisibleChatSurfaceModes[targetIndex]);
      },
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
              alignment: alignment,
              child: FractionallySizedBox(
                widthFactor: 1 / kVisibleChatSurfaceModes.length,
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
                Expanded(
                  child: _buildModeIcon(
                    isSelected: widget.activeMode == ChatSurfaceMode.normal,
                    child: widget.primaryAgentId?.trim().isNotEmpty == true
                        ? AgentBrandIcon(
                            key: const ValueKey(
                              'chat-mode-slider-primary-icon',
                            ),
                            agentId: widget.primaryAgentId!,
                            size: 16,
                          )
                        : SvgPicture.asset(
                            widget.primaryIconAsset!,
                            key: const ValueKey(
                              'chat-mode-slider-primary-icon',
                            ),
                            width: 16,
                            height: 16,
                          ),
                  ),
                ),
                Expanded(
                  child: _buildModeIcon(
                    isSelected: widget.activeMode == ChatSurfaceMode.workspace,
                    child: SvgPicture.asset(
                      _workspaceIconAsset,
                      key: const ValueKey('chat-mode-slider-workspace-icon'),
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

  Widget _buildModeIcon({required bool isSelected, required Widget child}) {
    final inactiveColor = context.isDarkTheme
        ? context.omniPalette.textSecondary
        : widget.visualProfile.secondaryTextColor;
    final color = isSelected
        ? Theme.of(context).colorScheme.onPrimary
        : inactiveColor;
    return Center(
      child: AnimatedScale(
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
        scale: isSelected ? 1 : 0.95,
        child: ColorFiltered(
          colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
          child: child,
        ),
      ),
    );
  }
}

/// 消息列表
/// 供消息锚点导航等外部入口按 timeline entry 平滑跳转消息列表。
/// 由 [_ChatMessageListState] 在生命周期内挂载/卸载自身。
class ChatMessageListNavigator {
  _ChatMessageListState? _state;

  bool get isAttached => _state != null;

  void _attach(_ChatMessageListState state) {
    _state = state;
  }

  void _detach(_ChatMessageListState state) {
    if (identical(_state, state)) {
      _state = null;
    }
  }

  /// 平滑滚动到 [AgentRunTimelineEntry.key] 对应的行。
  /// 返回是否成功定位（列表未挂载或条目不存在时为 false）。
  Future<bool> animateToEntry(String entryKey) {
    final state = _state;
    if (state == null) {
      return Future<bool>.value(false);
    }
    return state._animateToEntryKey(entryKey);
  }
}

class ChatMessageList extends StatefulWidget {
  final List<ChatMessageModel> messages;
  final ScrollController scrollController;
  final ChatMessageListNavigator? navigator;
  final Future<void> Function() onBeforeTaskExecute;
  final void Function(String taskId)? onCancelTask;
  final ValueChanged<ChatMessageModel>? onRetryAgentMessage;
  final ValueChanged<ChatMessageModel>? onContinueAgentMessage;
  final void Function(List<String> requiredPermissionIds)? onRequestAuthorize;
  final double bottomOverlayInset;
  final void Function(ChatMessageModel message, LongPressStartDetails details)?
  onUserMessageLongPressStart;
  final ValueChanged<ChatMessageModel>? onLatestUserMessageEditTap;
  final Future<void> Function()? onLoadMore;
  final bool hasMore;
  final Set<String> activeAgentTaskIds;
  final bool useAcpPresentation;
  final String? activeAcpAgentId;
  final Set<String>? expandedAgentRunTaskIds;
  final ValueChanged<Set<String>>? onExpandedAgentRunTaskIdsChanged;
  final AppBackgroundVisualProfile visualProfile;
  final AppBackgroundConfig appearanceConfig;
  final bool showEmptyGreeting;
  final bool liftEmptyGreeting;
  final List<HomeQuickPrompt> emptyGreetingQuickPrompts;
  final List<String> emptyGreetingPinnedQuickPromptIds;
  final ValueChanged<HomeQuickPrompt>? onQuickPromptSelected;
  final String? emptyGreetingAgentWorkspaceName;
  final VoidCallback? onEmptyGreetingAgentWorkspaceTap;
  final ValueChanged<bool>? onInternalInputFocusChanged;

  const ChatMessageList({
    super.key,
    required this.messages,
    required this.scrollController,
    this.navigator,
    required this.onBeforeTaskExecute,
    this.onCancelTask,
    this.onRetryAgentMessage,
    this.onContinueAgentMessage,
    this.onRequestAuthorize,
    this.bottomOverlayInset = 0,
    this.onUserMessageLongPressStart,
    this.onLatestUserMessageEditTap,
    this.onLoadMore,
    this.hasMore = false,
    this.activeAgentTaskIds = const <String>{},
    this.useAcpPresentation = false,
    this.activeAcpAgentId,
    this.expandedAgentRunTaskIds,
    this.onExpandedAgentRunTaskIdsChanged,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
    this.appearanceConfig = AppBackgroundConfig.defaults,
    this.showEmptyGreeting = true,
    this.liftEmptyGreeting = false,
    this.emptyGreetingQuickPrompts = const <HomeQuickPrompt>[],
    this.emptyGreetingPinnedQuickPromptIds = const <String>[],
    this.onQuickPromptSelected,
    this.emptyGreetingAgentWorkspaceName,
    this.onEmptyGreetingAgentWorkspaceTap,
    this.onInternalInputFocusChanged,
  });

  @override
  State<ChatMessageList> createState() => _ChatMessageListState();
}

class _ChatLatestEdgeScrollPhysics extends ClampingScrollPhysics {
  const _ChatLatestEdgeScrollPhysics({
    required this.shouldStickToLatest,
    super.parent,
  });

  final ValueGetter<bool> shouldStickToLatest;

  @override
  _ChatLatestEdgeScrollPhysics applyTo(ScrollPhysics? ancestor) {
    return _ChatLatestEdgeScrollPhysics(
      shouldStickToLatest: shouldStickToLatest,
      parent: buildParent(ancestor),
    );
  }

  @override
  double adjustPositionForNewDimensions({
    required ScrollMetrics oldPosition,
    required ScrollMetrics newPosition,
    required bool isScrolling,
    required double velocity,
  }) {
    if (!shouldStickToLatest() || isScrolling || velocity != 0.0) {
      return super.adjustPositionForNewDimensions(
        oldPosition: oldPosition,
        newPosition: newPosition,
        isScrolling: isScrolling,
        velocity: velocity,
      );
    }
    return switch (newPosition.axisDirection) {
      AxisDirection.down || AxisDirection.right => newPosition.maxScrollExtent,
      AxisDirection.up || AxisDirection.left => newPosition.minScrollExtent,
    };
  }
}

class _ChatMessageListState extends State<ChatMessageList> {
  static const Duration _kAgentRunToggleAutoStickSuppression = Duration(
    milliseconds: 420,
  );
  bool _stickToBottomScheduled = false;
  bool _autoStickToLatest = true;
  bool _outerScrollWasUserDriven = false;
  bool _isAutoLoadingHistory = false;
  final Set<String> _localExpandedAgentRunTaskIds = <String>{};
  static const double _latestEdgeTolerance = 48.0;
  static const double _manualLatestAttachTolerance = 2.0;
  static const double _historyLoadTriggerExtent = 180.0;
  ObservableChatMessageList? _observableMessages;
  DateTime? _autoStickSuppressedUntil;
  List<AgentRunTimelineEntry>? _timelineEntriesCache;
  ObservableChatMessageList? _timelineCacheSource;
  int _timelineCacheStructureRevision = -1;
  Set<String>? _timelineCacheActiveTaskIds;
  final Map<String, GlobalKey> _entryRowKeys = <String, GlobalKey>{};
  int _navigatorJumpSerial = 0;
  bool _navigatorJumpUserInterrupted = false;

  Set<String> get _expandedAgentRunTaskIds =>
      widget.expandedAgentRunTaskIds ?? _localExpandedAgentRunTaskIds;

  bool get _isAutoStickTemporarilySuppressed {
    final suppressedUntil = _autoStickSuppressedUntil;
    if (suppressedUntil == null) {
      return false;
    }
    if (DateTime.now().isBefore(suppressedUntil)) {
      return true;
    }
    _autoStickSuppressedUntil = null;
    return false;
  }

  @override
  void initState() {
    super.initState();
    widget.navigator?._attach(this);
    _bindObservableMessages(widget.messages);
    _scheduleStickToBottom();
  }

  @override
  void didUpdateWidget(covariant ChatMessageList oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.navigator, widget.navigator)) {
      oldWidget.navigator?._detach(this);
      widget.navigator?._attach(this);
    }
    _bindObservableMessages(widget.messages);
    final scrollControllerChanged =
        oldWidget.scrollController != widget.scrollController;
    if (scrollControllerChanged) {
      _navigatorJumpSerial++;
      _autoStickToLatest = true;
      _outerScrollWasUserDriven = false;
      _autoStickSuppressedUntil = null;
      _scheduleStickToLatest();
      return;
    }
    if (_autoStickToLatest) {
      return;
    }
    if (_isAutoStickTemporarilySuppressed) {
      return;
    }
    if (_isNearLatest(null, _manualLatestAttachTolerance)) {
      _autoStickToLatest = true;
    }
  }

  @override
  void dispose() {
    _navigatorJumpSerial++;
    widget.navigator?._detach(this);
    _observableMessages?.removeListener(_handleObservableMessagesChanged);
    super.dispose();
  }

  List<ScrollPosition> _attachedPositions() {
    if (!widget.scrollController.hasClients) {
      return const <ScrollPosition>[];
    }
    return widget.scrollController.positions.toList(growable: false);
  }

  bool _isNearLatest([
    ScrollMetrics? metrics,
    double tolerance = _latestEdgeTolerance,
  ]) {
    final resolvedMetrics = metrics;
    if (resolvedMetrics != null) {
      return _distanceToLatest(resolvedMetrics) <= tolerance;
    }
    final positions = _attachedPositions();
    if (positions.isEmpty) {
      return true;
    }
    return positions.every(
      (position) => _distanceToLatest(position) <= tolerance,
    );
  }

  double _latestOffset(ScrollMetrics metrics) {
    return switch (metrics.axisDirection) {
      AxisDirection.down || AxisDirection.right => metrics.maxScrollExtent,
      AxisDirection.up || AxisDirection.left => metrics.minScrollExtent,
    };
  }

  double _distanceToLatest(ScrollMetrics metrics) {
    return (metrics.pixels - _latestOffset(metrics)).abs();
  }

  void _scheduleStickToBottom() => _scheduleStickToLatest();

  void _scheduleStickToLatest() {
    if (!_autoStickToLatest || _isAutoStickTemporarilySuppressed) {
      return;
    }
    if (_stickToBottomScheduled) {
      return;
    }
    _stickToBottomScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _stickToBottomScheduled = false;
      if (!mounted) {
        return;
      }
      final positions = _attachedPositions();
      if (positions.isEmpty) {
        _scheduleStickToLatest();
        return;
      }
      if (!_autoStickToLatest) {
        return;
      }
      for (final position in positions) {
        final target = _latestOffset(position);
        if ((target - position.pixels).abs() < 0.5) {
          continue;
        }
        position.jumpTo(target);
      }
    });
  }

  void _bindObservableMessages(List<ChatMessageModel> messages) {
    final nextObservable = messages is ObservableChatMessageList
        ? messages
        : null;
    if (identical(_observableMessages, nextObservable)) {
      return;
    }
    _observableMessages?.removeListener(_handleObservableMessagesChanged);
    _observableMessages = nextObservable;
    _observableMessages?.addListener(_handleObservableMessagesChanged);
  }

  void _handleObservableMessagesChanged() {
    if (!mounted) {
      return;
    }
    _collapseCancelledAgentRuns();
    // 流式追加文本这类 content 级变更由每行的 ValueListenableBuilder 精确
    // 刷新，这里不再整表 setState（否则每个 chunk 都会重跑时间线分组和
    // 全部可见行的 itemBuilder）。结构变化仍走完整重建。
    if (_observableMessages?.lastMutationKind ==
        ChatMessageListMutationKind.content) {
      return;
    }
    setState(() {});
  }

  void _collapseCancelledAgentRuns() {
    final collapsedTaskIds = _cancelledAgentRunTaskIds(
      _expandedAgentRunTaskIds,
    );
    if (collapsedTaskIds.isEmpty) {
      return;
    }
    final nextExpandedTaskIds = Set<String>.from(_expandedAgentRunTaskIds)
      ..removeAll(collapsedTaskIds);
    if (widget.expandedAgentRunTaskIds != null) {
      widget.onExpandedAgentRunTaskIdsChanged?.call(nextExpandedTaskIds);
      return;
    }
    _localExpandedAgentRunTaskIds
      ..clear()
      ..addAll(nextExpandedTaskIds);
    widget.onExpandedAgentRunTaskIdsChanged?.call(nextExpandedTaskIds);
  }

  Set<String> _cancelledAgentRunTaskIds(Set<String> expandedTaskIds) {
    if (expandedTaskIds.isEmpty) {
      return const <String>{};
    }
    final normalizedExpandedTaskIds = expandedTaskIds
        .map((item) => item.trim())
        .where((item) => item.isNotEmpty)
        .toSet();
    if (normalizedExpandedTaskIds.isEmpty) {
      return const <String>{};
    }
    final collapsedTaskIds = <String>{};
    final messages = _observableMessages ?? widget.messages;
    for (final message in messages) {
      final taskId = agentRunParentTaskId(message);
      if (taskId == null || !normalizedExpandedTaskIds.contains(taskId)) {
        continue;
      }
      if (_isCancelledAgentRunMessage(message)) {
        collapsedTaskIds.add(taskId);
      }
    }
    return collapsedTaskIds;
  }

  bool _isCancelledAgentRunMessage(ChatMessageModel message) {
    return message.type == 1 &&
        message.user == 2 &&
        isCancelledTaskText(message);
  }

  void _handleParentScrollHandoff() {
    _autoStickToLatest = false;
    _outerScrollWasUserDriven = false;
  }

  void _suspendAutoStickForAgentRunToggle() {
    _autoStickToLatest = false;
    _outerScrollWasUserDriven = false;
    _autoStickSuppressedUntil = DateTime.now().add(
      _kAgentRunToggleAutoStickSuppression,
    );
  }

  void _toggleAgentRunGroup(String taskId) {
    final normalizedTaskId = taskId.trim();
    if (normalizedTaskId.isEmpty) {
      return;
    }
    _suspendAutoStickForAgentRunToggle();
    final nextExpandedTaskIds = Set<String>.from(_expandedAgentRunTaskIds);
    if (nextExpandedTaskIds.contains(normalizedTaskId)) {
      nextExpandedTaskIds.remove(normalizedTaskId);
    } else {
      nextExpandedTaskIds.add(normalizedTaskId);
    }
    if (widget.expandedAgentRunTaskIds != null) {
      widget.onExpandedAgentRunTaskIdsChanged?.call(nextExpandedTaskIds);
    } else {
      setState(() {
        _localExpandedAgentRunTaskIds
          ..clear()
          ..addAll(nextExpandedTaskIds);
      });
      widget.onExpandedAgentRunTaskIdsChanged?.call(nextExpandedTaskIds);
    }
  }

  double _distanceToOldest(ScrollMetrics metrics) {
    return (metrics.pixels - metrics.minScrollExtent).abs();
  }

  ScrollPosition? _closestAttachedPosition({
    required double pixels,
    required double minScrollExtent,
    required double maxScrollExtent,
  }) {
    final positions = _attachedPositions();
    if (positions.isEmpty) {
      return null;
    }
    ScrollPosition? bestMatch;
    var bestScore = double.infinity;
    for (final position in positions) {
      final score =
          (position.pixels - pixels).abs() +
          (position.minScrollExtent - minScrollExtent).abs() +
          (position.maxScrollExtent - maxScrollExtent).abs();
      if (score < bestScore) {
        bestScore = score;
        bestMatch = position;
      }
    }
    return bestMatch;
  }

  void _maybeLoadOlderMessages(ScrollMetrics metrics) {
    if (_isAutoLoadingHistory || !widget.hasMore || widget.onLoadMore == null) {
      return;
    }
    if (_distanceToOldest(metrics) > _historyLoadTriggerExtent) {
      return;
    }
    _isAutoLoadingHistory = true;
    unawaited(
      _loadOlderMessagesAndPreserveViewport(
        anchorPixels: metrics.pixels,
        anchorMinScrollExtent: metrics.minScrollExtent,
        anchorMaxScrollExtent: metrics.maxScrollExtent,
      ),
    );
  }

  Future<void> _loadOlderMessagesAndPreserveViewport({
    required double anchorPixels,
    required double anchorMinScrollExtent,
    required double anchorMaxScrollExtent,
  }) async {
    try {
      await widget.onLoadMore!.call();
      if (!mounted) return;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) {
          _isAutoLoadingHistory = false;
          return;
        }
        final position = _closestAttachedPosition(
          pixels: anchorPixels,
          minScrollExtent: anchorMinScrollExtent,
          maxScrollExtent: anchorMaxScrollExtent,
        );
        if (position == null) {
          _isAutoLoadingHistory = false;
          return;
        }
        final extentDelta = position.maxScrollExtent - anchorMaxScrollExtent;
        if (extentDelta.abs() >= 0.5) {
          final targetOffset = (anchorPixels + extentDelta).clamp(
            position.minScrollExtent,
            position.maxScrollExtent,
          );
          if ((position.pixels - targetOffset).abs() >= 0.5) {
            position.jumpTo(targetOffset);
          }
        }
        _isAutoLoadingHistory = false;
      });
    } catch (_) {
      _isAutoLoadingHistory = false;
      rethrow;
    }
  }

  bool _handleListScrollNotification(ScrollNotification notification) {
    if (notification.depth != 0 || notification.metrics.axis != Axis.vertical) {
      return false;
    }

    final shouldCheckAutoLoad =
        notification is ScrollUpdateNotification ||
        notification is OverscrollNotification ||
        notification is ScrollEndNotification;
    if (shouldCheckAutoLoad) {
      _maybeLoadOlderMessages(notification.metrics);
    }

    final isUserDrivenUpdate =
        (notification is ScrollUpdateNotification &&
            notification.dragDetails != null) ||
        (notification is OverscrollNotification &&
            notification.dragDetails != null);
    if (isUserDrivenUpdate) {
      _outerScrollWasUserDriven = true;
      _navigatorJumpUserInterrupted = true;
      if (_distanceToLatest(notification.metrics) >
          _manualLatestAttachTolerance) {
        _autoStickToLatest = false;
      }
      return false;
    }
    if (notification is ScrollEndNotification) {
      if (_outerScrollWasUserDriven &&
          _isNearLatest(notification.metrics, _manualLatestAttachTolerance)) {
        _autoStickToLatest = true;
      }
      _outerScrollWasUserDriven = false;
    }
    return false;
  }

  GlobalKey _rowKeyForEntry(String entryKey) {
    return _entryRowKeys.putIfAbsent(entryKey, GlobalKey.new);
  }

  void _pruneEntryRowKeys(List<AgentRunTimelineEntry> entries) {
    // 只在 key 数量明显超过当前条目时清理，避免每帧做 set 差集。
    if (_entryRowKeys.length <= entries.length + 32) {
      return;
    }
    final liveKeys = entries.map((entry) => entry.key).toSet();
    _entryRowKeys.removeWhere((key, _) => !liveKeys.contains(key));
  }

  static const Cubic _kJumpSettleCurve = Cubic(0.25, 1, 0.5, 1);
  static const double _kJumpAlignment = 0.04;

  /// 平滑跳转到指定 timeline entry：
  /// 目标行已布局时直接 ensureVisible；否则按平均行高估算目标偏移，
  /// 长距离先瞬移到目标一屏之外再平滑落位（保持总动画时长恒定），
  /// 短距离用短程动画逼近并循环修正，直到目标行被布局。
  Future<bool> _animateToEntryKey(String entryKey) async {
    final scrollController = widget.scrollController;
    if (!scrollController.hasClients) {
      return false;
    }
    final jumpSerial = ++_navigatorJumpSerial;
    _navigatorJumpUserInterrupted = false;
    // 关闭自动吸底，避免流式更新在跳转后把视口拽回底部。
    _autoStickToLatest = false;
    _outerScrollWasUserDriven = false;

    bool cancelled() =>
        !mounted ||
        _navigatorJumpUserInterrupted ||
        jumpSerial != _navigatorJumpSerial ||
        !widget.scrollController.hasClients;

    Future<bool> settleOn(BuildContext targetContext) async {
      await Scrollable.ensureVisible(
        targetContext,
        alignment: _kJumpAlignment,
        duration: const Duration(milliseconds: 340),
        curve: _kJumpSettleCurve,
      );
      // 落点校验：ensureVisible 中途被打断（历史分页的视口保持 jumpTo、
      // 流式内容撑高行等）会停在半路，补一次短修正。
      if (!cancelled()) {
        await WidgetsBinding.instance.endOfFrame;
        final verifyContext = _entryRowKeys[entryKey]?.currentContext;
        if (!cancelled() && verifyContext != null && verifyContext.mounted) {
          await Scrollable.ensureVisible(
            verifyContext,
            alignment: _kJumpAlignment,
            duration: const Duration(milliseconds: 150),
            curve: Curves.easeOutCubic,
          );
        }
      }
      // 跳到最新消息附近时恢复自动吸底，让后续流式输出继续跟随。
      if (jumpSerial == _navigatorJumpSerial &&
          !_navigatorJumpUserInterrupted &&
          _isNearLatest(null, _latestEdgeTolerance)) {
        _autoStickToLatest = true;
      }
      return true;
    }

    for (var attempt = 0; attempt < 10; attempt++) {
      if (cancelled()) {
        return false;
      }
      final targetContext = _entryRowKeys[entryKey]?.currentContext;
      if (targetContext != null && targetContext.mounted) {
        return settleOn(targetContext);
      }
      final entries = _resolveTimelineEntries(
        _observableMessages ?? widget.messages,
      );
      final dataIndex = entries.indexWhere((entry) => entry.key == entryKey);
      if (dataIndex == -1 || entries.isEmpty) {
        return false;
      }
      final position = widget.scrollController.positions.first;
      final visualIndex = entries.length - 1 - dataIndex;
      final totalExtent =
          (position.maxScrollExtent - position.minScrollExtent) +
          position.viewportDimension;
      final averageExtent = totalExtent / entries.length;
      final estimatedTarget =
          (position.minScrollExtent +
                  averageExtent * visualIndex -
                  position.viewportDimension * _kJumpAlignment)
              .clamp(position.minScrollExtent, position.maxScrollExtent)
              .toDouble();
      final distance = (estimatedTarget - position.pixels).abs();
      if (distance > position.viewportDimension * 1.4) {
        // 长距离：瞬移到估算点一屏之外的近端，让最终动画始终只滚一屏左右。
        final approachOffset =
            (estimatedTarget +
                    (position.pixels > estimatedTarget
                        ? position.viewportDimension
                        : -position.viewportDimension))
                .clamp(position.minScrollExtent, position.maxScrollExtent)
                .toDouble();
        position.jumpTo(approachOffset);
      } else {
        // 近距离但目标行还没建出来：短程动画逼近估算点后重试。
        await position.animateTo(
          estimatedTarget,
          duration: Duration(
            milliseconds: math.max(120, math.min(260, distance ~/ 4)),
          ),
          curve: attempt == 0 ? Curves.easeOutCubic : Curves.linear,
        );
      }
      await WidgetsBinding.instance.endOfFrame;
    }
    if (cancelled()) {
      return false;
    }
    final finalContext = _entryRowKeys[entryKey]?.currentContext;
    if (finalContext != null && finalContext.mounted) {
      return settleOn(finalContext);
    }
    return false;
  }

  ValueListenable<ChatMessageModel>? _messageListenableFor(
    ObservableChatMessageList messages,
    String messageId,
  ) {
    final index = messages.indexWhere((message) => message.id == messageId);
    if (index == -1) {
      return null;
    }
    return messages.listenableAt(index);
  }

  List<Listenable> _groupMessageListenablesFor(
    ObservableChatMessageList messages,
    AgentRunTimelineGroup group,
  ) {
    final seenIds = <String>{};
    final listenables = <Listenable>[];
    for (final message in group.allMessagesOldestFirst) {
      if (!seenIds.add(message.id)) {
        continue;
      }
      final listenable = _messageListenableFor(messages, message.id);
      if (listenable != null) {
        listenables.add(listenable);
      }
    }
    return listenables;
  }

  /// 时间线分组按 structureRevision 缓存：content 级流式更新与父层
  /// （键盘高度、翻页过渡等）触发的重建直接复用上次分组结果，避免每帧
  /// 对全部消息重跑 O(n·g) 的分组扫描。
  List<AgentRunTimelineEntry> _resolveTimelineEntries(
    List<ChatMessageModel> messageSource,
  ) {
    final observable = messageSource is ObservableChatMessageList
        ? messageSource
        : null;
    if (observable == null) {
      return buildAgentRunTimelineEntries(
        List<ChatMessageModel>.from(messageSource),
        activeTaskIds: widget.activeAgentTaskIds,
        conversationAgentId: widget.activeAcpAgentId,
      );
    }
    final cached = _timelineEntriesCache;
    if (cached != null &&
        identical(_timelineCacheSource, observable) &&
        _timelineCacheStructureRevision == observable.structureRevision &&
        setEquals(_timelineCacheActiveTaskIds, widget.activeAgentTaskIds)) {
      return cached;
    }
    final entries = buildAgentRunTimelineEntries(
      List<ChatMessageModel>.from(observable),
      activeTaskIds: widget.activeAgentTaskIds,
      conversationAgentId: widget.activeAcpAgentId,
    );
    _timelineEntriesCache = entries;
    _timelineCacheSource = observable;
    _timelineCacheStructureRevision = observable.structureRevision;
    _timelineCacheActiveTaskIds = Set<String>.from(widget.activeAgentTaskIds);
    return entries;
  }

  AgentRunTimelineGroup _refreshTimelineGroup(
    ObservableChatMessageList messages,
    AgentRunTimelineGroup group,
  ) {
    return group.withRefreshedMessages(<String, ChatMessageModel>{
      for (final message in messages) message.id: message,
    });
  }

  Widget _buildTimelineListRow({
    required List<ChatMessageModel> messageSource,
    required AgentRunTimelineEntry entry,
    required String? latestUserMessageId,
    required EdgeInsets padding,
  }) {
    final rowKey = ValueKey('chat-message-list-item-${entry.key}');

    Widget buildRow(AgentRunTimelineEntry rowEntry, {Key? key}) {
      return _ChatTimelineListRow(
        key: key,
        entry: rowEntry,
        latestUserMessageId: latestUserMessageId,
        onLatestUserMessageEditTap: widget.onLatestUserMessageEditTap,
        padding: padding,
        onBeforeTaskExecute: widget.onBeforeTaskExecute,
        onCancelTask: widget.onCancelTask,
        onRetryAgentMessage: widget.onRetryAgentMessage,
        onContinueAgentMessage: widget.onContinueAgentMessage,
        parentScrollController: widget.scrollController,
        onParentScrollHandoff: _handleParentScrollHandoff,
        onRequestAuthorize: widget.onRequestAuthorize,
        onUserMessageLongPressStart: widget.onUserMessageLongPressStart,
        onStreamingTextLayoutChanged: null,
        onToggleAgentRunGroup: _toggleAgentRunGroup,
        expandedAgentRunTaskIds: _expandedAgentRunTaskIds,
        useAcpPresentation: widget.useAcpPresentation,
        visualProfile: widget.visualProfile,
        appearanceConfig: widget.appearanceConfig,
      );
    }

    final observableMessages = messageSource is ObservableChatMessageList
        ? messageSource
        : null;
    if (observableMessages == null) {
      return buildRow(entry, key: rowKey);
    }

    final message = entry.message;
    if (message != null) {
      final listenable = _messageListenableFor(observableMessages, message.id);
      if (listenable == null) {
        return buildRow(entry, key: rowKey);
      }
      return ValueListenableBuilder<ChatMessageModel>(
        key: rowKey,
        valueListenable: listenable,
        builder: (context, latestMessage, _) {
          return buildRow(AgentRunTimelineEntry.message(latestMessage));
        },
      );
    }

    final group = entry.group;
    if (group == null) {
      return buildRow(entry, key: rowKey);
    }
    final listenables = _groupMessageListenablesFor(observableMessages, group);
    if (listenables.isEmpty) {
      return buildRow(entry, key: rowKey);
    }
    return AnimatedBuilder(
      key: rowKey,
      animation: Listenable.merge(listenables),
      builder: (context, _) {
        return buildRow(
          AgentRunTimelineEntry.group(
            _refreshTimelineGroup(observableMessages, group),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final reservedBottomInset = widget.bottomOverlayInset
        .clamp(0.0, double.infinity)
        .toDouble();
    final pageBackgroundColor =
        !widget.appearanceConfig.isActive && context.isDarkTheme
        ? context.omniPalette.pageBackground
        : null;

    final Widget content;
    if (widget.messages.isEmpty) {
      final usePaletteText =
          !widget.appearanceConfig.isActive &&
          widget.appearanceConfig.chatTextColorMode !=
              AppBackgroundTextColorMode.custom;
      content = widget.showEmptyGreeting
          ? GestureDetector(
              onVerticalDragUpdate: (_) {},
              behavior: HitTestBehavior.opaque,
              child: AnimatedAlign(
                duration: const Duration(milliseconds: 280),
                curve: Curves.easeInOutCubic,
                alignment: widget.liftEmptyGreeting
                    ? const Alignment(-1, -1)
                    : const Alignment(0, -0.18),
                child: ChatEmptyGreeting(
                  primaryTextColor: usePaletteText
                      ? context.omniPalette.textPrimary
                      : widget.visualProfile.primaryTextColor,
                  secondaryTextColor: usePaletteText
                      ? context.omniPalette.textSecondary
                      : widget.visualProfile.secondaryTextColor,
                  accentColor: context.omniPalette.accentPrimary,
                  quickPrompts: widget.emptyGreetingQuickPrompts,
                  pinnedQuickPromptIds:
                      widget.emptyGreetingPinnedQuickPromptIds,
                  onQuickPromptSelected: widget.onQuickPromptSelected,
                  agentWorkspaceName: widget.emptyGreetingAgentWorkspaceName,
                  onAgentWorkspaceTap: widget.onEmptyGreetingAgentWorkspaceTap,
                ),
              ),
            )
          : const SizedBox.expand();
      final paddedContent = Padding(
        key: const ValueKey('chat-empty-bottom-overlay-padding'),
        padding: EdgeInsets.only(bottom: reservedBottomInset),
        child: content,
      );
      if (pageBackgroundColor == null) {
        return paddedContent;
      }
      return ColoredBox(color: pageBackgroundColor, child: paddedContent);
    }

    String? latestUserMessageId;
    final messageSource = _observableMessages ?? widget.messages;
    final timelineEntries = _resolveTimelineEntries(messageSource);
    _pruneEntryRowKeys(timelineEntries);
    for (final item in messageSource) {
      if (item.user == 1) {
        latestUserMessageId = item.id;
        break;
      }
    }
    Widget listView = ListView.builder(
      controller: widget.scrollController,
      reverse: false,
      physics: _ChatLatestEdgeScrollPhysics(
        shouldStickToLatest: () =>
            _autoStickToLatest && !_isAutoStickTemporarilySuppressed,
      ),
      clipBehavior: Clip.hardEdge,
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 0),
      itemCount: timelineEntries.length,
      itemBuilder: (context, index) {
        final dataIndex = timelineEntries.length - 1 - index;
        final entry = timelineEntries[dataIndex];
        final isOldestEntry = dataIndex == timelineEntries.length - 1;
        final needTopPadding = isOldestEntry && !entry.isUserMessage;
        // GlobalKey 供锚点跳转定位已布局的行；行内部仍用 ValueKey 维持
        // 原有的复用语义。
        return KeyedSubtree(
          key: _rowKeyForEntry(entry.key),
          child: _buildTimelineListRow(
            messageSource: messageSource,
            entry: entry,
            latestUserMessageId: latestUserMessageId,
            padding: EdgeInsets.only(top: needTopPadding ? 24.0 : 0.0),
          ),
        );
      },
    );
    content = ClipRect(
      child: Align(
        alignment: Alignment.topCenter,
        child: NotificationListener<ScrollNotification>(
          onNotification: _handleListScrollNotification,
          child: listView,
        ),
      ),
    );

    // Plain Padding (no implicit tween): reservedBottomInset is already
    // driven per-frame by the live keyboard inset via the parent's
    // ComposerKeyboardMetricsTracker, and the composer above this list rides
    // the keyboard with a plain Padding. Wrapping this in AnimatedPadding
    // makes the chat content tween toward a target that's already moving,
    // so the content visibly trails the composer by ~half the tween length.
    final focusedContent = Focus(
      canRequestFocus: false,
      skipTraversal: true,
      onFocusChange: widget.onInternalInputFocusChanged,
      child: content,
    );
    final paddedContent = Padding(
      padding: EdgeInsets.only(bottom: reservedBottomInset),
      child: focusedContent,
    );

    if (pageBackgroundColor == null) {
      return paddedContent;
    }
    return ColoredBox(color: pageBackgroundColor, child: paddedContent);
  }
}

class _ChatTimelineListRow extends StatelessWidget {
  const _ChatTimelineListRow({
    super.key,
    required this.entry,
    required this.padding,
    required this.onBeforeTaskExecute,
    this.latestUserMessageId,
    this.onLatestUserMessageEditTap,
    this.onCancelTask,
    this.onRetryAgentMessage,
    this.onContinueAgentMessage,
    this.parentScrollController,
    this.onParentScrollHandoff,
    this.onRequestAuthorize,
    this.onUserMessageLongPressStart,
    this.onStreamingTextLayoutChanged,
    required this.onToggleAgentRunGroup,
    required this.expandedAgentRunTaskIds,
    required this.useAcpPresentation,
    required this.visualProfile,
    required this.appearanceConfig,
  });

  final AgentRunTimelineEntry entry;
  final EdgeInsets padding;
  final Future<void> Function() onBeforeTaskExecute;
  final String? latestUserMessageId;
  final ValueChanged<ChatMessageModel>? onLatestUserMessageEditTap;
  final void Function(String taskId)? onCancelTask;
  final ValueChanged<ChatMessageModel>? onRetryAgentMessage;
  final ValueChanged<ChatMessageModel>? onContinueAgentMessage;
  final ScrollController? parentScrollController;
  final VoidCallback? onParentScrollHandoff;
  final void Function(List<String> requiredPermissionIds)? onRequestAuthorize;
  final void Function(ChatMessageModel message, LongPressStartDetails details)?
  onUserMessageLongPressStart;
  final VoidCallback? onStreamingTextLayoutChanged;
  final void Function(String taskId) onToggleAgentRunGroup;
  final Set<String> expandedAgentRunTaskIds;
  final bool useAcpPresentation;
  final AppBackgroundVisualProfile visualProfile;
  final AppBackgroundConfig appearanceConfig;

  @override
  Widget build(BuildContext context) {
    if (entry.message != null) {
      return _buildBubble(entry.message!);
    }
    final group = entry.group!;
    return Padding(
      padding: padding,
      child: AgentRunGroupMessage(
        group: group,
        useAcpPresentation: useAcpPresentation,
        expanded: expandedAgentRunTaskIds.contains(group.taskId),
        onToggleExpanded: () => onToggleAgentRunGroup(group.taskId),
        onBeforeTaskExecute: onBeforeTaskExecute,
        onCancelTask: onCancelTask,
        onRetryAgentMessage: onRetryAgentMessage,
        onContinueAgentMessage: onContinueAgentMessage,
        parentScrollController: parentScrollController,
        onParentScrollHandoff: onParentScrollHandoff,
        onRequestAuthorize: onRequestAuthorize,
        onStreamingTextLayoutChanged: onStreamingTextLayoutChanged,
        visualProfile: visualProfile,
        appearanceConfig: appearanceConfig,
      ),
    );
  }

  Widget _buildBubble(ChatMessageModel currentMessage) {
    final isLatestUserMessage =
        currentMessage.user == 1 && currentMessage.id == latestUserMessageId;
    final bubble = MessageBubble(
      message: currentMessage,
      key: ValueKey(
        currentMessage.dbId ?? currentMessage.contentId ?? currentMessage.id,
      ),
      onBeforeTaskExecute: onBeforeTaskExecute,
      onCancelTask: onCancelTask,
      onRetryAgentMessage: () => onRetryAgentMessage?.call(currentMessage),
      onContinueAgentMessage: () =>
          onContinueAgentMessage?.call(currentMessage),
      enableThinkingCollapse: true,
      useAgentToolPresentation: useAcpPresentation,
      showThinkingAvatarOverride: null,
      parentScrollController: parentScrollController,
      onParentScrollHandoff: onParentScrollHandoff,
      onRequestAuthorize: onRequestAuthorize,
      onUserMessageLongPressStart: onUserMessageLongPressStart,
      onUserMessageEditTap:
          isLatestUserMessage && onLatestUserMessageEditTap != null
          ? () => onLatestUserMessageEditTap?.call(currentMessage)
          : null,
      onStreamingTextLayoutChanged: onStreamingTextLayoutChanged,
      visualProfile: visualProfile,
      appearanceConfig: appearanceConfig,
    );
    // The agent avatar and the processing/processed label belong to the run
    // header, which AgentRunGroupMessage renders once per turn. A loose bubble
    // is just a bubble — that is what makes duplicate avatars impossible.
    return Padding(padding: padding, child: bubble);
  }
}

/// 聊天输入区域包装器
class ChatInputWrapper extends StatelessWidget {
  final GlobalKey<ChatInputAreaState> inputAreaKey;
  final TextEditingController controller;
  final FocusNode focusNode;
  final VoidCallback? onRequestFocus;
  final bool isProcessing;
  final Future<void> Function({String? text}) onSendMessage;
  final VoidCallback onCancelTask;
  final void Function(bool) onPopupVisibilityChanged;
  final FutureOr<void> Function()? onTerminalTap;
  final bool? openClawEnabled;
  final ValueChanged<bool>? onToggleOpenClaw;
  final VoidCallback? onLongPressOpenClaw;
  final bool useLargeComposerStyle;
  final bool useAttachmentPickerForPlus;
  final Future<void> Function()? onPickAttachment;
  final List<ChatInputAttachment> attachments;
  final bool hasExternalSendPayload;
  final ValueChanged<String>? onRemoveAttachment;
  final VoidCallback? onTriggerSlashCommand;
  final Widget? topBanner;
  final String? selectedModelOverrideId;
  final VoidCallback? onClearSelectedModelOverride;
  final double? contextUsageRatio;
  final String? contextUsageTooltipMessage;
  final VoidCallback? onLongPressContextUsageRing;
  final ValueChanged<double>? onInputHeightChanged;
  final ChatModelPickerSettings? modelPickerSettings;
  final AgentRunSettings? agentRunSettings;
  final AgentRunSettingsChanged? onAgentRunSettingsChanged;
  final FutureOr<void> Function()? onAgentRunSettingsOpened;
  final AgentPermissionMode? agentPermissionMode;
  final List<AgentPermissionMode> agentPermissionModes;
  final ValueChanged<AgentPermissionMode>? onAgentPermissionModeChanged;
  final bool useIndependentSendButton;
  final bool translucent;

  const ChatInputWrapper({
    super.key,
    required this.inputAreaKey,
    required this.controller,
    required this.focusNode,
    this.onRequestFocus,
    required this.isProcessing,
    required this.onSendMessage,
    required this.onCancelTask,
    required this.onPopupVisibilityChanged,
    this.onTerminalTap,
    this.openClawEnabled,
    this.onToggleOpenClaw,
    this.onLongPressOpenClaw,
    this.useLargeComposerStyle = false,
    this.useAttachmentPickerForPlus = false,
    this.onPickAttachment,
    this.attachments = const [],
    this.hasExternalSendPayload = false,
    this.onRemoveAttachment,
    this.onTriggerSlashCommand,
    this.topBanner,
    this.selectedModelOverrideId,
    this.onClearSelectedModelOverride,
    this.contextUsageRatio,
    this.contextUsageTooltipMessage,
    this.onLongPressContextUsageRing,
    this.onInputHeightChanged,
    this.modelPickerSettings,
    this.agentRunSettings,
    this.onAgentRunSettingsChanged,
    this.onAgentRunSettingsOpened,
    this.agentPermissionMode,
    this.agentPermissionModes = AgentPermissionMode.values,
    this.onAgentPermissionModeChanged,
    this.useIndependentSendButton = true,
    this.translucent = false,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(24, 8, 24, 0),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (topBanner != null) ...[topBanner!, const SizedBox(height: 8)],
          ChatInputArea(
            key: inputAreaKey,
            controller: controller,
            focusNode: focusNode,
            onRequestFocus: onRequestFocus,
            isProcessing: isProcessing,
            onSendMessage: onSendMessage,
            onCancelTask: onCancelTask,
            onPopupVisibilityChanged: onPopupVisibilityChanged,
            onTerminalTap: onTerminalTap,
            openClawEnabled: openClawEnabled,
            onToggleOpenClaw: onToggleOpenClaw,
            onLongPressOpenClaw: onLongPressOpenClaw,
            useFrostedGlass: translucent,
            useLargeComposerStyle: useLargeComposerStyle,
            useAttachmentPickerForPlus: useAttachmentPickerForPlus,
            onPickAttachment: onPickAttachment,
            attachments: attachments,
            hasExternalSendPayload: hasExternalSendPayload,
            onRemoveAttachment: onRemoveAttachment,
            onTriggerSlashCommand: onTriggerSlashCommand,
            selectedModelOverrideId: selectedModelOverrideId,
            onClearSelectedModelOverride: onClearSelectedModelOverride,
            contextUsageRatio: contextUsageRatio,
            contextUsageTooltipMessage: contextUsageTooltipMessage,
            onLongPressContextUsageRing: onLongPressContextUsageRing,
            modelPickerSettings: modelPickerSettings,
            agentRunSettings: agentRunSettings,
            onAgentRunSettingsChanged: onAgentRunSettingsChanged,
            onAgentRunSettingsOpened: onAgentRunSettingsOpened,
            agentPermissionMode: agentPermissionMode,
            agentPermissionModes: agentPermissionModes,
            onAgentPermissionModeChanged: onAgentPermissionModeChanged,
            onInputHeightChanged: onInputHeightChanged,
            useIndependentSendButton: useIndependentSendButton,
          ),
        ],
      ),
    );
  }
}
