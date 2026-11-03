import 'dart:async';
import 'dart:math' as math;

import 'package:ui/widgets/flclash/popup.dart';
import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/agent_brand_icon.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/omni_menu.dart';

import 'chat_widgets.dart' show ChatAcpAgentModeOption;

const String kNewConversationPureChatModeId = 'chat';

/// Chooses the presentation target of an unsent conversation.
///
/// The page owns visibility, target changes, and draft preservation. This
/// widget only returns the chosen ID and never changes an ACP session.
class NewConversationModeSelector extends StatefulWidget {
  static const double height = 48;

  const NewConversationModeSelector({
    super.key,
    required this.selectedId,
    required this.agentModes,
    required this.onSelected,
    this.enabled = true,
    this.onPopupVisibilityChanged,
  });

  final String selectedId;
  final List<ChatAcpAgentModeOption> agentModes;
  final ValueChanged<String> onSelected;
  final bool enabled;

  /// Reports balanced open/close notifications, including owner disposal.
  /// An owner that calls setState from this callback must check mounted.
  final ValueChanged<bool>? onPopupVisibilityChanged;

  @override
  State<NewConversationModeSelector> createState() =>
      _NewConversationModeSelectorState();
}

class _NewConversationModeSelectorState
    extends State<NewConversationModeSelector> {
  OverlayGlassPopupHandle<String>? _popup;
  int _presentationRevision = 0;

  @override
  void didUpdateWidget(covariant NewConversationModeSelector oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.selectedId != oldWidget.selectedId ||
        widget.enabled != oldWidget.enabled ||
        !_sameModes(oldWidget.agentModes, widget.agentModes)) {
      _presentationRevision++;
      _dismissAfterFrame();
    }
  }

  bool _sameModes(
    List<ChatAcpAgentModeOption> before,
    List<ChatAcpAgentModeOption> after,
  ) {
    if (before.length != after.length) return false;
    for (var index = 0; index < before.length; index++) {
      final left = before[index];
      final right = after[index];
      if (left.id != right.id ||
          left.name != right.name ||
          left.isAvailable != right.isAvailable) {
        return false;
      }
    }
    return true;
  }

  void _dismissAfterFrame() {
    final popup = _popup;
    if (popup == null) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(popup.dismiss());
    });
  }

  @override
  void dispose() {
    _presentationRevision++;
    _dismissAfterFrame();
    super.dispose();
  }

  bool _isAvailable(String id) =>
      id == kNewConversationPureChatModeId ||
      widget.agentModes.any((mode) => mode.id == id && mode.isAvailable);

  Future<void> _open(BuildContext anchorContext) async {
    if (!widget.enabled || _popup != null) return;
    final anchor = glassPopupAnchorFromContext(anchorContext);
    if (anchor == null) return;
    final revision = _presentationRevision;
    final onVisibilityChanged = widget.onPopupVisibilityChanged;
    final english = Localizations.localeOf(context).languageCode == 'en';
    final selectedId = widget.selectedId;
    final ids = <String>{};
    final choices = [
      for (final mode in widget.agentModes)
        if (mode.isAvailable &&
            mode.id != kNewConversationPureChatModeId &&
            ids.add(mode.id))
          (mode.id, mode.name.trim().isEmpty ? mode.id : mode.name),
      (kNewConversationPureChatModeId, english ? 'Pure chat' : '纯聊天'),
    ];
    final handle = showOverlayGlassPopup<String>(
      context: context,
      sourceContext: anchorContext,
      anchor: anchor,
      preferBelow: false,
      builder: (handle) => TextFieldTapRegion(
        child: OmniMenuSurface(
          key: const ValueKey('new-conversation-mode-menu'),
          width: 280,
          child: LayoutBuilder(
            builder: (context, constraints) => ConstrainedBox(
              constraints: BoxConstraints(
                maxHeight: math.min(360, constraints.maxHeight),
              ),
              child: ListView(
                key: const ValueKey('new-conversation-mode-options'),
                padding: EdgeInsets.zero,
                shrinkWrap: true,
                children: [
                  for (final (id, label) in choices)
                    OmniMenuItem(
                      key: ValueKey('new-conversation-mode-option-$id'),
                      selected: selectedId == id,
                      onTap: () => unawaited(handle.dismiss(id)),
                      leading: _modeIcon(id),
                      label: label,
                      trailing: selectedId == id
                          ? const Icon(LucideIcons.check)
                          : null,
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    _popup = handle;
    onVisibilityChanged?.call(true);
    try {
      final selected = await handle.future;
      await handle.closed;
      if (!mounted ||
          revision != _presentationRevision ||
          !widget.enabled ||
          selected == null ||
          selected == widget.selectedId ||
          !_isAvailable(selected)) {
        return;
      }
      widget.onSelected(selected);
    } finally {
      if (_popup == handle) _popup = null;
      // The owner can remove this widget while building its sent-message UI.
      // Release its auxiliary-popup hold after that frame, not during build.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        onVisibilityChanged?.call(false);
      });
    }
  }

  static Widget _modeIcon(String id) => ExcludeSemantics(
    child: id == kNewConversationPureChatModeId
        ? const Icon(LucideIcons.messageCircle, size: 20)
        : AgentBrandIcon(agentId: id, size: 20),
  );

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final english = Localizations.localeOf(context).languageCode == 'en';
    final selected = widget.agentModes
        .where((mode) => mode.id == widget.selectedId)
        .firstOrNull;
    final label = widget.selectedId == kNewConversationPureChatModeId
        ? (english ? 'Pure chat' : '纯聊天')
        : selected?.name.trim().isNotEmpty == true
        ? selected!.name.trim()
        : widget.selectedId;
    final color = widget.enabled ? palette.textSecondary : palette.textTertiary;
    return TextFieldTapRegion(
      child: FlClashPopupSource(
        glass: false,
        color: Colors.transparent,
        shape: const RoundedRectangleBorder(
          borderRadius: OmniMenuStyle.rowRadius,
        ),
        builder: (anchorContext) => Tooltip(
          message: english ? 'Switch Agent mode' : '切换 Agent 模式',
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              key: const ValueKey('new-conversation-mode-selector'),
              onTap: widget.enabled ? () => _open(anchorContext) : null,
              borderRadius: OmniMenuStyle.rowRadius,
              child: SizedBox(
                height: NewConversationModeSelector.height,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _modeIcon(widget.selectedId),
                      const SizedBox(width: 8),
                      Flexible(
                        child: Text(
                          label,
                          key: const ValueKey('new-conversation-mode-name'),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 16,
                            height: 1.25,
                            color: color,
                          ),
                        ),
                      ),
                      const SizedBox(width: 6),
                      Icon(LucideIcons.chevronsUpDown, size: 14, color: color),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
