// Adapted from FlClash lib/ui/context_menu.dart and lib/ui/popup.dart at
// 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7. The independent no-source popup
// keeps OmnibotApp's existing menu placement. See docs/third-party/flclash-NOTICE.md.
// SPDX-License-Identifier: GPL-3.0-only

import 'dart:math' as math;
import 'dart:ui' show SemanticsRole;

import 'package:flutter/material.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/flclash/popup.dart';
import 'package:ui/widgets/omni_menu.dart';

/// FlClash's context-menu clock: the independent pane shrinks away in 200ms,
/// rather than returning into a captured button over 500ms.
class ConversationContextMenuRoute<T> extends RawDialogRoute<T> {
  ConversationContextMenuRoute({
    required super.pageBuilder,
    required super.barrierLabel,
    this.reduceMotion = false,
  }) : super(
         barrierDismissible: true,
         barrierColor: null,
         transitionDuration: reduceMotion
             ? Duration.zero
             : FlClashPopupMotion.openingDuration,
         transitionBuilder: (_, animation, _, child) => AbsorbPointer(
           absorbing: animation.status == AnimationStatus.reverse,
           child: child,
         ),
       );

  final bool reduceMotion;

  @override
  Duration get reverseTransitionDuration =>
      reduceMotion ? Duration.zero : FlClashPopupMotion.shrinkingDuration;

  @override
  bool didPop(T? result) {
    // Even an interrupted opening gets the full, continuous closing clock.
    controller!.reverseDuration =
        reverseTransitionDuration * (1 / math.max(controller!.value, 1e-3));
    return super.didPop(result);
  }
}

/// FlClash's independent action pane, with an optional lifted conversation row.
/// The focus layer never becomes the popup's source: menu placement and its
/// independent opening/closing motion stay separate from the row animation.
class ConversationContextMenu extends StatelessWidget {
  const ConversationContextMenu({
    super.key,
    required this.anchor,
    required this.origin,
    required this.actions,
    required this.animation,
    required this.onDismiss,
    this.preview,
  });

  final Rect anchor;
  final Offset origin;
  final List<Widget> actions;
  final Animation<double> animation;
  final VoidCallback onDismiss;
  final Widget? preview;

  void _dismiss(BuildContext context) {
    if (animation.status == AnimationStatus.reverse ||
        ModalRoute.of(context)?.isCurrent == false) {
      return;
    }
    onDismiss();
  }

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    final menu = FlClashPopupTransition(
      anchor: origin & Size.zero,
      animation: animation,
      sourceColor: Colors.transparent,
      child: IntrinsicWidth(
        child: Material(
          type: MaterialType.transparency,
          child: Semantics(
            role: SemanticsRole.menu,
            scopesRoute: true,
            namesRoute: true,
            explicitChildNodes: true,
            label: MaterialLocalizations.of(context).popupMenuLabel,
            child: OmniMenuSurface(
              key: const ValueKey('conversation-context-actions'),
              child: SingleChildScrollView(
                key: const ValueKey('conversation-context-scroll'),
                hitTestBehavior: HitTestBehavior.deferToChild,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: actions,
                ),
              ),
            ),
          ),
        ),
      ),
    );
    if (preview != null) {
      return _ConversationFocusLayer(
        anchor: anchor,
        animation: animation,
        actionCount: actions.length,
        preview: preview!,
        menu: menu,
        onDismiss: () => _dismiss(context),
      );
    }
    return Stack(
      children: [
        Positioned.fill(
          child: GestureDetector(
            key: const ValueKey('conversation-context-dismiss'),
            behavior: HitTestBehavior.opaque,
            onTap: () => _dismiss(context),
            child: Semantics(
              label: MaterialLocalizations.of(context).modalBarrierDismissLabel,
              button: true,
              child: const ColoredBox(color: Colors.transparent),
            ),
          ),
        ),
        CustomSingleChildLayout(
          delegate: _ConversationMenuLayout(
            anchor: anchor,
            actionCount: actions.length,
            media: media,
          ),
          child: menu,
        ),
      ],
    );
  }
}

class _ConversationFocusLayer extends StatelessWidget {
  const _ConversationFocusLayer({
    required this.anchor,
    required this.animation,
    required this.actionCount,
    required this.preview,
    required this.menu,
    required this.onDismiss,
  });

  final Rect anchor;
  final Animation<double> animation;
  final int actionCount;
  final Widget preview;
  final Widget menu;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    final palette = context.omniPalette;
    final glass = LiquidGlassScope.of(context);
    final dark = context.isDarkTheme;
    const radius = BorderRadius.all(Radius.circular(18));
    final shadows = [
      BoxShadow(
        color: Colors.black.withValues(alpha: dark ? 0.20 : 0.06),
        blurRadius: 18,
        offset: const Offset(0, 6),
      ),
    ];
    final content = LiquidGlassSurfaceScope(
      enabled: true,
      child: ClipRRect(
        borderRadius: radius,
        child: Material(type: MaterialType.transparency, child: preview),
      ),
    );
    final surface = GestureDetector(
      key: const ValueKey('conversation-context-preview'),
      behavior: HitTestBehavior.opaque,
      onTap: () {},
      child: IgnorePointer(
        child: glass
            ? LiquidGlass(
                style: LiquidGlassStyle.menu(Theme.of(context).colorScheme),
                borderRadius: radius,
                shadows: shadows,
                child: content,
              )
            : DecoratedBox(
                decoration: BoxDecoration(
                  color: palette.surfacePrimary,
                  borderRadius: radius,
                  boxShadow: shadows,
                ),
                child: content,
              ),
      ),
    );
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = math.max(
          0.0,
          // The lifted row keeps its own layout; only the independent action
          // menu has a compact width. Narrow windows may still constrain it.
          math.min(anchor.width, constraints.maxWidth - 32),
        );
        return AnimatedBuilder(
          animation: animation,
          child: menu,
          builder: (context, menu) {
            final progress = media.disableAnimations ? 1.0 : animation.value;
            final lift = const Interval(
              0,
              0.72,
              curve: Curves.easeOutCubic,
            ).transform(progress);
            final opacity = const Interval(
              0,
              0.25,
              curve: Curves.easeOutCubic,
            ).transform(progress);
            final scrim = palette.overlayScrim.withValues(
              alpha: (dark ? 0.24 : 0.12) * Curves.easeOut.transform(progress),
            );
            final dismiss = GestureDetector(
              key: const ValueKey('conversation-context-dismiss'),
              behavior: HitTestBehavior.opaque,
              onTap: onDismiss,
              child: Semantics(
                label: MaterialLocalizations.of(context)
                    .modalBarrierDismissLabel,
                button: true,
                child: ColoredBox(color: glass ? Colors.transparent : scrim),
              ),
            );
            return Stack(
              children: [
                Positioned.fill(
                  child: glass
                      ? LiquidGlassVeil(
                          color: AlwaysStoppedAnimation(scrim),
                          child: dismiss,
                        )
                      : dismiss,
                ),
                CustomMultiChildLayout(
                  delegate: _ConversationFocusLayout(
                    anchor: anchor,
                    actionCount: actionCount,
                    media: media,
                    lift: lift,
                    width: width,
                  ),
                  children: [
                    LayoutId(
                      id: 'preview',
                      child: Transform.scale(
                        scale: 0.97 + 0.03 * lift,
                        alignment: Alignment.topLeft,
                        child: LiquidGlassFade(
                          opacity: AlwaysStoppedAnimation(opacity),
                          child: FittedBox(
                            fit: BoxFit.scaleDown,
                            alignment: Alignment.topLeft,
                            child: SizedBox(width: width, child: surface),
                          ),
                        ),
                      ),
                    ),
                    LayoutId(id: 'menu', child: menu!),
                  ],
                ),
              ],
            );
          },
        );
      },
    );
  }
}

class _ConversationFocusLayout extends MultiChildLayoutDelegate {
  _ConversationFocusLayout({
    required this.anchor,
    required this.actionCount,
    required this.media,
    required this.lift,
    required this.width,
  });

  final Rect anchor;
  final int actionCount;
  final MediaQueryData media;
  final double lift;
  final double width;

  @override
  void performLayout(Size size) {
    final menuLayout = _ConversationMenuLayout(
      anchor: anchor,
      actionCount: actionCount,
      media: media,
    );
    final available = math.max(
      0.0,
      size.height - menuLayout._topInset - menuLayout._bottomInset,
    );
    final gap = math.min(14.0, available * 0.05);
    final previewSize = layoutChild(
      'preview',
      BoxConstraints.tightFor(width: width)
          .copyWith(maxHeight: available * 0.4),
    );
    final menuConstraints = menuLayout.getConstraintsForChild(
      BoxConstraints.loose(size),
    );
    final menuSize = layoutChild(
      'menu',
      menuConstraints.copyWith(
        maxHeight: math.min(
          menuConstraints.maxHeight,
          math.max(0.0, available - previewSize.height - gap),
        ),
      ),
    );
    final originalMenuPosition = menuLayout.getPositionForChild(size, menuSize);
    final menuPosition = Offset(
      originalMenuPosition.dx,
      math.max(
        originalMenuPosition.dy,
        menuLayout._topInset + previewSize.height + gap,
      ),
    );
    positionChild('menu', menuPosition);
    final destination = Offset(
      menuPosition.dx.clamp(
        16.0,
        math.max(16.0, size.width - 16 - previewSize.width),
      ),
      menuPosition.dy - gap - previewSize.height,
    );
    positionChild('preview', Offset.lerp(anchor.topLeft, destination, lift)!);
  }

  @override
  bool shouldRelayout(_ConversationFocusLayout oldDelegate) =>
      anchor != oldDelegate.anchor ||
      actionCount != oldDelegate.actionCount ||
      media != oldDelegate.media ||
      lift != oldDelegate.lift ||
      width != oldDelegate.width;
}

/// Retains the former action panel's x/y without mounting an invisible preview.
/// Only insufficient space (keyboard/large text) may move it to stay reachable.
class _ConversationMenuLayout extends SingleChildLayoutDelegate {
  const _ConversationMenuLayout({
    required this.anchor,
    required this.actionCount,
    required this.media,
  });

  final Rect anchor;
  final int actionCount;
  final MediaQueryData media;

  double get _topInset => media.padding.top + 12;
  double get _bottomInset =>
      math.max(media.padding.bottom, media.viewInsets.bottom) + 12;

  @override
  BoxConstraints getConstraintsForChild(BoxConstraints constraints) {
    final maxWidth = math.max(
      0.0,
      math.min(280.0, math.min(anchor.width, constraints.maxWidth - 48)),
    );
    return BoxConstraints(
      minWidth: math.min(160, maxWidth),
      maxWidth: maxWidth,
      maxHeight: math.max(
        0,
        math.min(
          constraints.maxHeight - _topInset - _bottomInset,
          16 + 7.5 * (24 + media.textScaler.scale(20)),
        ),
      ),
    );
  }

  @override
  Offset getPositionForChild(Size size, Size childSize) {
    final oldAlignmentWidth = math.max(
      0.0,
      math.min(anchor.width, math.min(360, size.width - 48)),
    );
    final sourceHeight =
        24.0 +
        math.max(
          50.0,
          media.textScaler.scale(16) * 1.25 +
              4 +
              media.textScaler.scale(14) * 1.3,
        );
    final reservedAbove = sourceHeight + 14;
    final oldRowHeight = math.max(48, 24 + media.textScaler.scale(16) * 1.25);
    final oldMenuHeight = 20 + actionCount * oldRowHeight;
    final available = math.max(0.0, size.height - _topInset - _bottomInset);
    final oldViewportHeight = math.min(
      reservedAbove + oldMenuHeight,
      available,
    );
    final oldGroupTop = (anchor.top - 8).clamp(
      _topInset,
      math.max(_topInset, size.height - _bottomInset - oldViewportHeight),
    );
    return Offset(
      anchor.left.clamp(
        24.0,
        math.max(24, size.width - 24 - oldAlignmentWidth),
      ),
      (oldGroupTop + reservedAbove).clamp(
        _topInset,
        math.max(_topInset, size.height - _bottomInset - childSize.height),
      ),
    );
  }

  @override
  bool shouldRelayout(_ConversationMenuLayout oldDelegate) =>
      anchor != oldDelegate.anchor ||
      actionCount != oldDelegate.actionCount ||
      media != oldDelegate.media;
}
