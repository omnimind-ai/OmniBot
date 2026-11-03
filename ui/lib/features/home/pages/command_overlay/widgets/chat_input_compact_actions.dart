part of 'chat_input_area.dart';

enum _CompactComposerAction { images, files, permissions }

extension _CompactComposerActions on _ChatInputAreaStateBase {
  Rect? _compactActionsAnchor(BuildContext buttonContext) {
    final surfaceContext = _compactComposerSurfaceKey.currentContext;
    final surface = surfaceContext == null
        ? null
        : glassPopupAnchorFromContext(surfaceContext);
    if (surface == null) return glassPopupAnchorFromContext(buttonContext);

    // The button is inset inside the composer. Both menus grow from the
    // composer's outside corner, so their left/bottom edges share its outline.
    final diameter = OmniMenuStyle.panelRadius.bottomLeft.x * 2;
    return Rect.fromLTWH(
      surface.left,
      surface.bottom - diameter,
      diameter,
      diameter,
    );
  }

  Widget _buildCompactAddButton() {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final canOpen =
        widget.onPickImages != null ||
        widget.onPickAttachment != null ||
        _shouldShowAgentPermissionSelector;
    return FlClashPopupSource(
      glass: false,
      color: Colors.transparent,
      shape: const CircleBorder(),
      builder: (anchor) => IconButton(
        key: const ValueKey('chat-input-add-or-cancel-edit-button'),
        padding: EdgeInsets.zero,
        tooltip: widget.isEditingUserMessage
            ? (english ? 'Exit editing' : '退出编辑')
            : (english ? 'Attachments and permissions' : '附件与权限'),
        icon: AnimatedRotation(
          key: const ValueKey('chat-input-add-or-cancel-edit-icon'),
          turns: widget.isEditingUserMessage ? 0.125 : 0,
          duration: _compactAnimationDuration,
          curve: Curves.easeOutCubic,
          child: Icon(
            LucideIcons.plus,
            size: 22,
            color: context.omniPalette.textPrimary,
          ),
        ),
        onPressed: widget.isEditingUserMessage
            ? widget.onCancelUserMessageEditing
            : canOpen
            ? () => _openCompactActions(anchor)
            : null,
      ),
    );
  }

  Future<void> _openCompactActions(BuildContext anchorContext) async {
    if (_compactActionsMenu != null ||
        !_composerStateMachine.beginPopupOpening(
          ChatComposerPopup.compactActions,
        )) {
      return;
    }
    final anchor = _compactActionsAnchor(anchorContext);
    if (anchor == null) {
      _composerStateMachine.popupClosed(ChatComposerPopup.compactActions);
      return;
    }
    final english = Localizations.localeOf(context).languageCode == 'en';
    final handle = showOverlayGlassPopup<_CompactComposerAction>(
      context: context,
      sourceContext: anchorContext,
      anchor: anchor,
      placementAnchor: anchor,
      preferBelow: false,
      builder: (handle) => TextFieldTapRegion(
        child: OmniMenuSurface(
          width: 224,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (widget.onPickImages != null)
                _CompactComposerMenuRow(
                  action: _CompactComposerAction.images,
                  icon: LucideIcons.image,
                  title: english ? 'Photos' : '图片',
                  onTap: () => handle.dismiss(_CompactComposerAction.images),
                ),
              if (widget.onPickAttachment != null)
                _CompactComposerMenuRow(
                  action: _CompactComposerAction.files,
                  icon: LucideIcons.paperclip,
                  title: english ? 'Files' : '文件',
                  onTap: () => handle.dismiss(_CompactComposerAction.files),
                ),
              if (_shouldShowAgentPermissionSelector)
                _CompactComposerMenuRow(
                  action: _CompactComposerAction.permissions,
                  icon: LucideIcons.shield,
                  title: english ? 'Permissions' : '权限模式',
                  subtitle: _agentPermissionLabel(widget.agentPermissionMode!),
                  onTap: () =>
                      handle.dismiss(_CompactComposerAction.permissions),
                ),
            ],
          ),
        ),
      ),
    );
    _compactActionsMenu = handle;
    _composerStateMachine.popupOpened(ChatComposerPopup.compactActions);
    try {
      final action = await handle.future;
      await handle.closed;
      if (!mounted || !anchorContext.mounted) return;
      // Hold the layout through the native picker or the existing permission
      // popup. A handoff must not briefly collapse the focused text editor.
      switch (action) {
        case _CompactComposerAction.images:
          await widget.onPickImages?.call();
        case _CompactComposerAction.files:
          await widget.onPickAttachment?.call();
        case _CompactComposerAction.permissions:
          await _openAgentPermissionMenu(
            anchorContext,
            preferBelow: false,
            anchorOverride: _compactActionsAnchor(anchorContext),
          );
        case null:
          break;
      }
    } finally {
      if (_compactActionsMenu == handle) {
        _compactActionsMenu = null;
        if (mounted) {
          _composerStateMachine.popupClosed(ChatComposerPopup.compactActions);
        }
      }
    }
  }
}

class _CompactComposerMenuRow extends StatelessWidget {
  const _CompactComposerMenuRow({
    required this.action,
    required this.icon,
    required this.title,
    required this.onTap,
    this.subtitle,
  });

  final _CompactComposerAction action;
  final IconData icon;
  final String title;
  final String? subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return OmniMenuItem(
      key: ValueKey('chat-input-add-${action.name}'),
      onTap: onTap,
      label: title,
      subtitle: subtitle,
      leading: Icon(icon),
      trailing: action == _CompactComposerAction.permissions
          ? const Icon(LucideIcons.chevronRight)
          : null,
    );
  }
}
