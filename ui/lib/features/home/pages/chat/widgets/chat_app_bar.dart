part of 'chat_widgets.dart';

/// A presentation command. Its callback continues through the existing owner.
class ChatAppBarAction {
  const ChatAppBarAction({
    required this.label,
    required this.icon,
    required this.onSelected,
  });
  final String label;
  final IconData icon;
  final FutureOr<void> Function(BuildContext anchorContext) onSelected;
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

/// 聊天页面 AppBar
class ChatAppBar extends StatelessWidget {
  static const _navigationButtonSize = 40.0;

  final VoidCallback onMenuTap;
  final String? title;
  final List<ChatAppBarAction> actions;
  final Key? tutorialActionsAnchorKey;
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
  final bool showTerminalActions;
  final String? activeToolType;
  final List<ChatAcpAgentModeOption> acpAgentModes;
  final String? activeAcpAgentId;
  final bool showAppUpdateIndicator;
  final VoidCallback? onAppUpdateTap;
  final String? appUpdateTooltip;
  final bool translucent;
  final AppBackgroundVisualProfile visualProfile;

  /// Keeps the spacious header in medium-width windows without side panes.
  final bool usePadLayout;
  final bool showMenuButton;
  final bool showSurfaceSwitcher;
  final bool isPureChatSelected;
  final bool showWorkspacePaneButton;
  final VoidCallback? onWorkspacePaneTap;
  final VoidCallback? onConversationPaneTap;
  final bool conversationPaneVisible;
  final bool workspacePaneVisible;
  final Key? tutorialMenuAnchorKey;
  final Key? tutorialModeAnchorKey;

  const ChatAppBar({
    super.key,
    required this.onMenuTap,
    this.title,
    this.actions = const [],
    this.tutorialActionsAnchorKey,
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
    this.showTerminalActions = true,
    this.activeToolType,
    this.acpAgentModes = const <ChatAcpAgentModeOption>[],
    this.activeAcpAgentId,
    this.showAppUpdateIndicator = false,
    this.onAppUpdateTap,
    this.appUpdateTooltip,
    this.translucent = false,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
    this.usePadLayout = false,
    this.showMenuButton = true,
    this.showSurfaceSwitcher = true,
    this.isPureChatSelected = false,
    this.showWorkspacePaneButton = false,
    this.onWorkspacePaneTap,
    this.onConversationPaneTap,
    this.conversationPaneVisible = false,
    this.workspacePaneVisible = false,
    this.tutorialMenuAnchorKey,
    this.tutorialModeAnchorKey,
  });

  String _modeLabel(bool english) {
    if (isPureChatSelected) return english ? 'Pure chat' : '纯聊天';
    for (final agent in acpAgentModes) {
      if (agent.id == activeAcpAgentId) return agent.name;
    }
    final id = activeAcpAgentId?.trim();
    if (id == 'na-cloud') return 'Na';
    if (id == 'openclaw') return 'OpenClaw';
    if (id != null &&
        id.isNotEmpty &&
        AgentBrandIcon.normalizeAgentId(id) != 'xiaowan-acp') {
      return id;
    }
    return english ? 'OmniBot Agent' : '小万 Agent';
  }

  Future<void> _openActionsMenu(BuildContext anchorContext) async {
    final english = Localizations.localeOf(anchorContext).languageCode == 'en';
    final items = <ChatAppBarAction>[
      ...actions,
      ChatAppBarAction(
        label: !showSurfaceSwitcher && onWorkspacePaneTap != null
            ? _workspacePaneLabel(english)
            : activeMode == ChatSurfaceMode.workspace
            ? (english ? 'Return to conversation' : '返回对话')
            : (english ? 'Workspace files' : '工作区文件'),
        icon: LucideIcons.folder,
        onSelected: (_) {
          if (!showSurfaceSwitcher && onWorkspacePaneTap != null) {
            onWorkspacePaneTap!();
          } else {
            onModeChanged(
              activeMode == ChatSurfaceMode.workspace
                  ? ChatSurfaceMode.normal
                  : ChatSurfaceMode.workspace,
            );
          }
        },
      ),
      if (showTerminalActions)
        ChatAppBarAction(
          label: english ? 'Terminal' : '终端',
          icon: LucideIcons.terminal,
          onSelected: (_) => onTerminalTap(),
        ),
      if (showTerminalActions)
        ChatAppBarAction(
          label: english ? 'Environment variables' : '环境变量',
          icon: LucideIcons.slidersHorizontal,
          onSelected: onTerminalEnvironmentTap,
        ),
      if (isBrowserEnabled)
        ChatAppBarAction(
          label: english ? 'Browser' : '浏览器',
          icon: LucideIcons.globe,
          onSelected: (_) => onBrowserTap(),
        ),
      if (onPrimaryModeTap != null)
        ChatAppBarAction(
          label: english ? 'Agent sessions' : 'Agent 会话',
          icon: LucideIcons.messagesSquare,
          onSelected: (_) => onPrimaryModeTap!(),
        ),
      if (showAppUpdateIndicator && onAppUpdateTap != null)
        ChatAppBarAction(
          label: appUpdateTooltip ?? (english ? 'Check for updates' : '检查更新'),
          icon: LucideIcons.download,
          onSelected: (_) => onAppUpdateTap!(),
        ),
    ];
    final tapTarget = glassPopupAnchorFromContext(anchorContext);
    if (tapTarget == null) return;
    // Pad menus capture their shared capsule. Phone menus retain the visible
    // circle inside the padded touch target.
    final anchor = usePadLayout || onConversationPaneTap != null
        ? tapTarget
        : Rect.fromCenter(
            center: tapTarget.center,
            width: _navigationButtonSize,
            height: _navigationButtonSize,
          );
    final selected = await showOmniMenu<int>(
      context: anchorContext,
      sourceContext: anchorContext,
      anchor: anchor,
      width: 260,
      requestFocus: usePadLayout || onConversationPaneTap != null,
      items: [
        for (var index = 0; index < items.length; index++)
          OmniPopupMenuItem<int>(
            value: index,
            child: Row(
              children: [
                Icon(items[index].icon, size: 20),
                const SizedBox(width: OmniMenuStyle.iconGap),
                Expanded(child: Text(items[index].label)),
              ],
            ),
          ),
      ],
    );
    if (selected != null && anchorContext.mounted) {
      await items[selected].onSelected(anchorContext);
    }
  }

  String _conversationPaneLabel(bool english) => conversationPaneVisible
      ? (english ? 'Hide conversations' : '收起会话侧栏')
      : (english ? 'Show conversations' : '展开会话侧栏');

  String _workspacePaneLabel(bool english) => workspacePaneVisible
      ? (english ? 'Hide workspace' : '收起工作区')
      : (english ? 'Show workspace' : '展开工作区');

  Future<void> _openLayoutMenu(BuildContext anchorContext) async {
    final english = Localizations.localeOf(anchorContext).languageCode == 'en';
    final tapTarget = glassPopupAnchorFromContext(anchorContext);
    if (tapTarget == null) return;
    final selected = await showOmniMenu<int>(
      context: anchorContext,
      sourceContext: anchorContext,
      anchor: tapTarget,
      requestFocus: true,
      items: [
        if (onConversationPaneTap != null)
          OmniPopupMenuItem<int>(
            key: const ValueKey('chat-app-bar-layout-conversations'),
            value: 0,
            selected: conversationPaneVisible,
            child: _layoutMenuLabel(
              icon: LucideIcons.panelLeft,
              label: _conversationPaneLabel(english),
              shortcut: 'Ctrl+B',
            ),
          ),
        if (showWorkspacePaneButton && onWorkspacePaneTap != null)
          OmniPopupMenuItem<int>(
            key: const ValueKey('chat-app-bar-layout-workspace'),
            value: 1,
            selected: workspacePaneVisible,
            child: _layoutMenuLabel(
              icon: LucideIcons.panelRight,
              label: _workspacePaneLabel(english),
              shortcut: 'Ctrl+E',
            ),
          ),
      ],
    );
    if (!anchorContext.mounted) return;
    if (selected == 0) onConversationPaneTap?.call();
    if (selected == 1) onWorkspacePaneTap?.call();
  }

  Widget _layoutMenuLabel({
    required IconData icon,
    required String label,
    required String shortcut,
  }) => Row(
    children: [
      Icon(icon, size: OmniMenuStyle.iconSize),
      const SizedBox(width: OmniMenuStyle.iconGap),
      Expanded(child: Text(label)),
      const SizedBox(width: 12),
      Builder(
        builder: (context) => Text(
          shortcut,
          style: TextStyle(
            fontSize: 11,
            color: context.omniPalette.textTertiary,
          ),
        ),
      ),
    ],
  );

  Widget _buildPadHeader(BuildContext context) {
    final palette = context.omniPalette;
    final english = Localizations.localeOf(context).languageCode == 'en';
    final foreground = translucent
        ? visualProfile.appBarIconColor
        : palette.textPrimary;
    final titleText = activeMode == ChatSurfaceMode.workspace
        ? (english ? 'Workspace' : '工作区')
        : ((title?.trim().isNotEmpty ?? false)
              ? title!.trim()
              : (english ? 'New conversation' : '新对话'));
    return TextFieldTapRegion(
      child: ColoredBox(
        key: const ValueKey('chat-app-bar-background'),
        // The Pad pane owns its surface. A second page-background fill here
        // otherwise makes the header look like a separate strip inside it.
        color: Colors.transparent,
        child: LayoutBuilder(
          builder: (context, constraints) {
            // This is the chat column's width, not the physical Pad width.
            // Keep the title readable when the two neighbouring panes open.
            final directPaneControls = constraints.maxWidth >= 600;
            final hasConversationPane = onConversationPaneTap != null;
            final hasWorkspacePane =
                showWorkspacePaneButton && onWorkspacePaneTap != null;
            final hasPaneControls = hasConversationPane || hasWorkspacePane;
            return ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 80),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 12,
                ),
                child: Row(
                  children: [
                    if (showMenuButton ||
                        (directPaneControls && hasConversationPane)) ...[
                      FlClashToolbarButtonGroup(
                        key: const ValueKey('chat-app-bar-leading-group'),
                        buttonSize: 48,
                        iconSize: OmniCircleButton.defaultIconSize,
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (showMenuButton)
                              KeyedSubtree(
                                key: tutorialMenuAnchorKey,
                                child: IconButton(
                                  key: const ValueKey(
                                    'chat-app-bar-menu-button',
                                  ),
                                  icon: const Icon(LucideIcons.chevronLeft),
                                  tooltip: english
                                      ? 'Back to conversations'
                                      : '返回会话列表',
                                  onPressed: onMenuTap,
                                ),
                              ),
                            if (directPaneControls && hasConversationPane)
                              _buildPadPaneButton(context, conversation: true),
                          ],
                        ),
                      ),
                      const SizedBox(width: 12),
                    ],
                    Expanded(
                      child: KeyedSubtree(
                        key: tutorialModeAnchorKey,
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Tooltip(
                              message: titleText,
                              excludeFromSemantics: true,
                              child: Semantics(
                                header: true,
                                child: Text(
                                  titleText,
                                  key: const ValueKey('chat-app-bar-title'),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontSize: 20,
                                    height: 1.25,
                                    fontWeight: FontWeight.w600,
                                    color: foreground,
                                  ),
                                ),
                              ),
                            ),
                            const SizedBox(height: 4),
                            Row(
                              children: [
                                ExcludeSemantics(
                                  child: SizedBox.square(
                                    key: const ValueKey(
                                      'chat-app-bar-mode-icon',
                                    ),
                                    dimension: 14,
                                    child: isPureChatSelected
                                        ? Icon(
                                            LucideIcons.messageCircle,
                                            size: 14,
                                            color: foreground,
                                          )
                                        : AgentBrandIcon(
                                            agentId:
                                                activeAcpAgentId
                                                        ?.trim()
                                                        .isNotEmpty ==
                                                    true
                                                ? activeAcpAgentId!
                                                : 'xiaowan-acp',
                                            size: 14,
                                            fallbackColor: foreground,
                                            tint: translucent
                                                ? foreground
                                                : null,
                                          ),
                                  ),
                                ),
                                const SizedBox(width: 6),
                                Expanded(
                                  child: Text(
                                    _modeLabel(english),
                                    key: const ValueKey(
                                      'chat-app-bar-mode-label',
                                    ),
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      fontSize: 13,
                                      height: 1.2,
                                      color: translucent
                                          ? foreground.withValues(alpha: .78)
                                          : palette.textSecondary,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    FlClashToolbarButtonGroup(
                      key: const ValueKey('chat-app-bar-trailing-group'),
                      buttonSize: 48,
                      iconSize: OmniCircleButton.defaultIconSize,
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (directPaneControls && hasWorkspacePane)
                            _buildPadPaneButton(context, conversation: false)
                          else if (!directPaneControls && hasPaneControls)
                            Builder(
                              builder: (anchorContext) => IconButton(
                                key: const ValueKey(
                                  'chat-app-bar-layout-button',
                                ),
                                icon: const Icon(LucideIcons.panelsTopLeft),
                                tooltip: english ? 'Panel layout' : '面板布局',
                                style: IconButton.styleFrom(
                                  foregroundColor:
                                      conversationPaneVisible ||
                                          workspacePaneVisible
                                      ? palette.accentPrimary
                                      : palette.textPrimary,
                                ),
                                onPressed: () => _openLayoutMenu(anchorContext),
                              ),
                            ),
                          KeyedSubtree(
                            key: tutorialActionsAnchorKey,
                            child: Builder(
                              builder: (anchorContext) => IconButton(
                                key: const ValueKey('chat-app-bar-more-button'),
                                icon: const Icon(LucideIcons.ellipsis),
                                tooltip: english
                                    ? 'Conversation options'
                                    : '对话选项',
                                onPressed: () =>
                                    _openActionsMenu(anchorContext),
                              ),
                            ),
                          ),
                        ],
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

  Widget _buildPadPaneButton(
    BuildContext context, {
    required bool conversation,
  }) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final visible = conversation
        ? conversationPaneVisible
        : workspacePaneVisible;
    final label = conversation
        ? _conversationPaneLabel(english)
        : _workspacePaneLabel(english);
    final shortcut = conversation ? 'Ctrl+B' : 'Ctrl+E';
    return MergeSemantics(
      child: Semantics(
        toggled: visible,
        child: IconButton(
          key: ValueKey(
            conversation
                ? 'chat-app-bar-sidebar-button'
                : 'chat-app-bar-workspace-button',
          ),
          icon: Icon(
            conversation ? LucideIcons.panelLeft : LucideIcons.panelRight,
          ),
          tooltip: '$label ($shortcut)',
          style: IconButton.styleFrom(
            foregroundColor: visible
                ? context.omniPalette.accentPrimary
                : context.omniPalette.textPrimary,
          ),
          onPressed: conversation ? onConversationPaneTap : onWorkspacePaneTap,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (usePadLayout || onConversationPaneTap != null) {
      return _buildPadHeader(context);
    }
    final palette = context.omniPalette;
    final english = Localizations.localeOf(context).languageCode == 'en';
    final foreground = translucent
        ? visualProfile.appBarIconColor
        : palette.textPrimary;
    return TextFieldTapRegion(
      child: ColoredBox(
        key: const ValueKey('chat-app-bar-background'),
        color: translucent ? Colors.transparent : palette.pageBackground,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 4, 14, 6),
          child: Row(
            children: [
              if (showMenuButton) ...[
                KeyedSubtree(
                  key: tutorialMenuAnchorKey,
                  child: OmniCircleButton(
                    key: const ValueKey('chat-app-bar-menu-button'),
                    icon: LucideIcons.chevronLeft,
                    size: _navigationButtonSize,
                    tapTargetSize: MaterialTapTargetSize.padded,
                    tooltip: english ? 'Back to conversations' : '返回会话列表',
                    foregroundColor: palette.textPrimary,
                    onPressed: onMenuTap,
                  ),
                ),
                const SizedBox(width: 8),
              ],
              if (onConversationPaneTap != null) ...[
                Semantics(
                  toggled: conversationPaneVisible,
                  child: OmniCircleButton(
                    key: const ValueKey('chat-app-bar-sidebar-button'),
                    icon: LucideIcons.panelLeft,
                    size: _navigationButtonSize,
                    tapTargetSize: MaterialTapTargetSize.padded,
                    tooltip: conversationPaneVisible
                        ? (english
                              ? 'Hide conversations (Ctrl+B)'
                              : '收起会话侧栏 (Ctrl+B)')
                        : (english
                              ? 'Show conversations (Ctrl+B)'
                              : '展开会话侧栏 (Ctrl+B)'),
                    foregroundColor: conversationPaneVisible
                        ? palette.accentPrimary
                        : palette.textPrimary,
                    onPressed: onConversationPaneTap,
                  ),
                ),
                const SizedBox(width: 4),
              ],
              Expanded(
                child: KeyedSubtree(
                  key: tutorialModeAnchorKey,
                  child: Row(
                    children: [
                      Semantics(
                        key: const ValueKey('chat-app-bar-mode-icon'),
                        label: _modeLabel(english),
                        image: true,
                        child: ExcludeSemantics(
                          child: isPureChatSelected
                              ? Icon(
                                  LucideIcons.messageCircle,
                                  size: 20,
                                  color: foreground,
                                )
                              : AgentBrandIcon(
                                  agentId:
                                      activeAcpAgentId?.trim().isNotEmpty ==
                                          true
                                      ? activeAcpAgentId!
                                      : 'xiaowan-acp',
                                  size: 20,
                                  fallbackColor: foreground,
                                  tint: translucent ? foreground : null,
                                ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          activeMode == ChatSurfaceMode.workspace
                              ? (english ? 'Workspace' : '工作区')
                              : ((title?.trim().isNotEmpty ?? false)
                                    ? title!
                                    : (english ? 'New conversation' : '新对话')),
                          key: const ValueKey('chat-app-bar-title'),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 17,
                            height: 1.3,
                            fontWeight: FontWeight.w600,
                            color: foreground,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(width: 8),
              if (showWorkspacePaneButton && onWorkspacePaneTap != null) ...[
                Semantics(
                  toggled: workspacePaneVisible,
                  child: OmniCircleButton(
                    key: const ValueKey('chat-app-bar-workspace-button'),
                    icon: LucideIcons.panelRight,
                    size: _navigationButtonSize,
                    tapTargetSize: MaterialTapTargetSize.padded,
                    tooltip: workspacePaneVisible
                        ? (english
                              ? 'Hide workspace (Ctrl+E)'
                              : '收起工作区 (Ctrl+E)')
                        : (english
                              ? 'Show workspace (Ctrl+E)'
                              : '展开工作区 (Ctrl+E)'),
                    foregroundColor: workspacePaneVisible
                        ? palette.accentPrimary
                        : palette.textPrimary,
                    onPressed: onWorkspacePaneTap,
                  ),
                ),
                const SizedBox(width: 4),
              ],
              KeyedSubtree(
                key: tutorialActionsAnchorKey,
                child: OmniCircleButton.popup(
                  key: const ValueKey('chat-app-bar-more-button'),
                  icon: LucideIcons.ellipsis,
                  size: _navigationButtonSize,
                  tapTargetSize: MaterialTapTargetSize.padded,
                  tooltip: english ? 'Conversation options' : '对话选项',
                  foregroundColor: palette.textPrimary,
                  onOpen: _openActionsMenu,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
