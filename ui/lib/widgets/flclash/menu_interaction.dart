// FlClash CommonPopupMenu row tracking and ElasticPress integration, adapted
// from 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7. See flclash-NOTICE.md.
// SPDX-License-Identifier: GPL-3.0-only
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

import 'elastic_press.dart';

/// Carries a button's opening pointer into its menu, without owning selection,
/// routes, focus or dismissal. Long-press context menus start a fresh pointer.
class FlClashMenuController {
  _FlClashMenuInteractionState? _state;

  void press(Offset position) => _state?._startPress(position);
  void drag(Offset position) => _state?._slideCarried(position);
  void release(Offset position) => _state?._endPress(position);
  void cancel() => _state?._cancelPress();
}

/// A single paint-only elastic panel and row-selection gesture owner. Only
/// [FlClashMenuItemRegion] participates; editors and arbitrary content do not.
class FlClashMenuInteraction extends StatefulWidget {
  const FlClashMenuInteraction({
    super.key,
    required this.child,
    this.controller,
    this.enabled = true,
  });

  final Widget child;
  final FlClashMenuController? controller;

  /// The opening platter cannot swell while it clips the menu. Row tracking
  /// remains available for a button's carried press, as in the upstream menu.
  final bool enabled;

  static bool hasAncestor(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<_MenuScope>() != null;

  /// The popup anchor uses its parent's layout coordinates. Elasticity adds
  /// only proxy boxes; keep the original layout offset through those proxies.
  static Offset layoutOffsetOf(RenderBox box) {
    for (var node = box.parent; node != null; node = node.parent) {
      if (node is _RenderMenuBounds) {
        final data = node.parentData;
        return (data is BoxParentData ? data.offset : Offset.zero) +
            MatrixUtils.transformPoint(box.getTransformTo(node), Offset.zero);
      }
    }
    final data = box.parentData;
    return data is BoxParentData ? data.offset : Offset.zero;
  }

  @override
  State<FlClashMenuInteraction> createState() => _FlClashMenuInteractionState();
}

class _FlClashMenuInteractionState extends State<FlClashMenuInteraction> {
  final _menuKey = GlobalKey();
  Object? _pressed;
  bool _carrying = false;
  bool _selecting = false;

  FlClashElasticPressHandle? get _elastic {
    final context = _menuKey.currentContext;
    return context == null ? null : FlClashElasticPress.maybeOf(context);
  }

  @override
  void initState() {
    super.initState();
    widget.controller?._state = this;
  }

  @override
  void didUpdateWidget(covariant FlClashMenuInteraction oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      if (identical(oldWidget.controller?._state, this)) {
        oldWidget.controller?._state = null;
      }
      widget.controller?._state = this;
    }
  }

  @override
  void dispose() {
    if (identical(widget.controller?._state, this)) {
      widget.controller?._state = null;
    }
    super.dispose();
  }

  _MenuHit _hitAt(Offset position) {
    final box = _menuKey.currentContext?.findRenderObject();
    if (box is! RenderBox || !box.hasSize || box.size.isEmpty) {
      return const _MenuHit();
    }
    final painted = FlClashElasticPress.paintBoundsOf(box);
    final local = box.globalToLocal(position);
    final result = BoxHitTestResult();
    box.hitTest(
      result,
      position: Offset(
        (local.dx - painted.left) * box.size.width / painted.width,
        (local.dy - painted.top) * box.size.height / painted.height,
      ),
    );
    var panel = false;
    var tapOwners = 0;
    for (final entry in result.path) {
      final target = entry.target;
      if (target is RenderEditable) return const _MenuHit();
      if (target is RenderSemanticsAnnotations) {
        final semantics = target.properties;
        if (semantics.onTap != null || semantics.onLongPress != null) {
          tapOwners++;
        }
        if (semantics.button == true ||
            semantics.textField == true ||
            semantics.slider == true ||
            semantics.toggled != null ||
            semantics.checked != null) {
          return const _MenuHit();
        }
      }
      if (target is RenderSemanticsGestureHandler &&
          (target.onTap != null || target.onLongPress != null)) {
        tapOwners++;
      }
      if (target case RenderMetaData(metaData: _MenuInteractionBlocker())) {
        return const _MenuHit();
      }
      if (target case RenderMetaData(metaData: final _MenuRow row)) {
        // The row's own InkWell is one tap owner. Any deeper independent
        // control keeps its gesture even if the caller omitted an Ignore.
        return tapOwners > 1 ? const _MenuHit() : _MenuHit(row: row);
      }
      if (target case RenderMetaData(metaData: _MenuPanel())) panel = true;
    }
    return _MenuHit(panel: panel && tapOwners == 0);
  }

  _MenuRow? _rowAt(Offset position) => _hitAt(position).row;

  bool _startPress(Offset position) {
    final hit = _hitAt(position);
    if (hit.row == null && !hit.panel) return false;
    _selecting = hit.row != null;
    _hold(hit.row);
    if (widget.enabled) _elastic?.press(position);
    return true;
  }

  void _slidePress(Offset position) {
    if (widget.enabled) _elastic?.drag(position);
    if (!_selecting) return;
    final row = _rowAt(position);
    final identity = row?.onTap == null ? null : row!.identity;
    if (identity == _pressed) return;
    if (identity != null) HapticFeedback.selectionClick();
    _hold(row);
  }

  void _hold(_MenuRow? row) {
    final identity = row?.onTap == null ? null : row!.identity;
    if (identity != _pressed) setState(() => _pressed = identity);
  }

  void _endPress(Offset position) {
    final onTap = _selecting ? _rowAt(position)?.onTap : null;
    _cancelPress();
    onTap?.call();
  }

  void _cancelPress() {
    _carrying = false;
    _selecting = false;
    _elastic?.release();
    if (mounted && _pressed != null) setState(() => _pressed = null);
  }

  void _slideCarried(Offset position) {
    _selecting = true;
    if (!_carrying && widget.enabled) {
      _carrying = true;
      _elastic?.press(position);
    }
    _slidePress(position);
  }

  @override
  Widget build(BuildContext context) => _MenuBounds(
    child: FlClashElasticPress(
      enabled: widget.enabled,
      listenToPointers: false,
      pullPastEdges: true,
      child: RawGestureDetector(
        key: _menuKey,
        behavior: HitTestBehavior.opaque,
        gestures: {
          _RowPressRecognizer:
              GestureRecognizerFactoryWithHandlers<_RowPressRecognizer>(
                () => _RowPressRecognizer(debugOwner: this),
                (recognizer) => recognizer
                  ..onStart = _startPress
                  ..onUpdate = _slidePress
                  ..onEnd = _endPress
                  ..onCancel = _cancelPress,
              ),
        },
        child: _MenuScope(pressed: _pressed, child: widget.child),
      ),
    ),
  );
}

class _MenuBounds extends SingleChildRenderObjectWidget {
  const _MenuBounds({required super.child});
  @override
  RenderObject createRenderObject(BuildContext context) => _RenderMenuBounds();
}

class _RenderMenuBounds extends RenderProxyBox {}

/// Identifies real menu chrome. Its blank padding/title can be pulled without
/// creating a selection gesture or activating a tooltip/editor card.
class FlClashMenuPanelRegion extends StatelessWidget {
  const FlClashMenuPanelRegion({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => MetaData(
    behavior: HitTestBehavior.opaque,
    metaData: const _MenuPanel(),
    child: child,
  );
}

class _MenuPanel {
  const _MenuPanel();
}

class _MenuHit {
  const _MenuHit({this.row, this.panel = false});
  final _MenuRow? row;
  final bool panel;
}

/// Marks a row without taking over its keyboard action or business callback.
class FlClashMenuItemRegion extends StatefulWidget {
  const FlClashMenuItemRegion({
    super.key,
    required this.onTap,
    required this.builder,
  });
  final VoidCallback? onTap;
  final Widget Function(BuildContext context, bool pressed) builder;
  @override
  State<FlClashMenuItemRegion> createState() => _FlClashMenuItemRegionState();
}

class _FlClashMenuItemRegionState extends State<FlClashMenuItemRegion> {
  final _identity = Object();
  @override
  Widget build(BuildContext context) => MetaData(
    behavior: HitTestBehavior.opaque,
    metaData: _MenuRow(_identity, widget.onTap),
    child: widget.builder(
      context,
      context.dependOnInheritedWidgetOfExactType<_MenuScope>()?.pressed ==
          _identity,
    ),
  );
}

/// Embedded controls such as a row's switch keep their independent gesture.
class FlClashMenuInteractionIgnore extends StatelessWidget {
  const FlClashMenuInteractionIgnore({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => MetaData(
    behavior: HitTestBehavior.opaque,
    metaData: const _MenuInteractionBlocker(),
    child: child,
  );
}

class _MenuInteractionBlocker {
  const _MenuInteractionBlocker();
}

class _MenuRow {
  const _MenuRow(this.identity, this.onTap);
  final Object identity;
  final VoidCallback? onTap;
}

class _MenuScope extends InheritedWidget {
  const _MenuScope({required this.pressed, required super.child});
  final Object? pressed;
  @override
  bool updateShouldNotify(_MenuScope oldWidget) => pressed != oldWidget.pressed;
}

/// Follows a press on a row across the rows and picks the one it is let go
/// on, as iOS menus do. It claims the pointer only then, so a menu that
/// scrolls takes a drag first, and a row's own tap never fires on a press.
class _RowPressRecognizer extends OneSequenceGestureRecognizer {
  _RowPressRecognizer({super.debugOwner});

  bool Function(Offset position)? onStart;
  ValueSetter<Offset>? onUpdate;
  ValueSetter<Offset>? onEnd;
  VoidCallback? onCancel;

  int? _pointer;
  Offset? _release;
  bool _accepted = false;

  @override
  bool isPointerAllowed(PointerDownEvent event) =>
      _pointer == null &&
      event.buttons == kPrimaryButton &&
      super.isPointerAllowed(event);

  @override
  void addAllowedPointer(PointerDownEvent event) {
    if (!(onStart?.call(event.position) ?? false)) {
      return;
    }
    _pointer = event.pointer;
    _release = null;
    _accepted = false;
    startTrackingPointer(event.pointer, event.transform);
  }

  @override
  void handleEvent(PointerEvent event) {
    if (event is PointerMoveEvent) {
      onUpdate?.call(event.position);
    } else if (event is PointerUpEvent) {
      _release = event.position;
      stopTrackingPointer(event.pointer);
      if (_accepted) {
        _finish();
      } else {
        resolve(GestureDisposition.accepted);
      }
    } else if (event is PointerCancelEvent) {
      resolve(GestureDisposition.rejected);
      // An already accepted arena no longer calls rejectGesture. Clear our
      // tracking explicitly as well, so cancellation cannot strand the press.
      if (event.pointer == _pointer) rejectGesture(event.pointer);
    }
  }

  @override
  void acceptGesture(int pointer) {
    _accepted = true;
    if (pointer == _pointer && _release != null) {
      _finish();
    }
  }

  @override
  void rejectGesture(int pointer) {
    stopTrackingPointer(pointer);
    if (pointer != _pointer) {
      return;
    }
    _pointer = null;
    _release = null;
    onCancel?.call();
  }

  void _finish() {
    final release = _release!;
    _pointer = null;
    _release = null;
    onEnd?.call(release);
  }

  @override
  void didStopTrackingLastPointer(int pointer) {}

  @override
  String get debugDescription => 'menu row press';
}
