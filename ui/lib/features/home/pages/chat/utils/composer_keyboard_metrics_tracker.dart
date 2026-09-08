import 'dart:math' as math;

import 'package:ui/features/home/pages/chat/chat_page_models.dart';

/// Resolved composer metrics for one frame.
class ComposerKeyboardMetrics {
  const ComposerKeyboardMetrics({
    required this.inputBottomPadding,
    required this.keyboardSpacer,
  });

  final double inputBottomPadding;
  final double keyboardSpacer;
}

/// Resolves the chat composer's bottom padding and keyboard spacer from raw
/// per-frame window metrics, keeping the composer's absolute screen position
/// monotone while the soft keyboard hides and perfectly still once settled.
///
/// Raw metrics are not trustworthy frame-by-frame on every device:
/// - `viewPadding.bottom` can momentarily report 0 or a stale value while
///   the IME animates or the navigation mode changes (observed on ColorOS),
///   which makes any formula based on the live value settle the composer too
///   low and visibly pop it up when the hide animation ends.
/// - `viewInsets.bottom` can emit a short non-zero blip right after the hide
///   animation has settled at 0, which re-engages the keyboard spacer for a
///   frame and bounces the composer.
///
/// Guards 1-3 are provably inert on healthy signals; guard 4 reshapes only
/// the tail of the hide trajectory:
/// 1. Rest latch: `viewPadding.bottom` is latched while the keyboard is
///    fully closed and the latched value anchors the rest position during
///    keyboard motion.
/// 2. Rest floor: the composer's total distance from the physical screen
///    bottom (SafeArea padding + bottom padding + spacer) never drops below
///    the latched rest position. The healthy lifted trajectory always stays
///    at or above it, so the floor only engages on degraded signals and on
///    unfocused dismissals (where it pins the composer exactly at rest for
///    the whole hide animation).
/// 3. Settle debounce: only residual insets within the resting navigation
///    inset may be deferred. A full keyboard height must apply immediately:
///    the platform need not emit intermediate animation frames.
/// 4. Continuous landing: while the keyboard is closing, the total is
///    capped at `max(rest, inset + clearance)`. The raw formulas would hold
///    the composer clearance-height above rest for the last stretch of the
///    hide (a flat shelf) and only drop the final ~10dp in the very last
///    frames — which reads as a small bottom bounce after an apparent
///    landing. With the cap the composer rides the keyboard down in one
///    continuous motion, lands exactly at rest slightly before the keyboard
///    finishes, and never moves again. The opening trajectory is untouched.
class ComposerKeyboardMetricsTracker {
  ComposerKeyboardMetricsTracker({
    this.edgeInset = kChatComposerEdgeInset,
    this.keyboardClearance = kChatKeyboardComposerClearance,
    this.settleInsetThreshold = 0.5,
    this.motionEpsilon = 1.0,
  });

  final double edgeInset;
  final double keyboardClearance;
  final double settleInsetThreshold;
  final double motionEpsilon;

  double? _restViewPadding;
  bool _settled = true;
  bool _liftDeferredOnce = false;
  bool _closing = false;
  double? _lastEngagedInset;

  double? _lastInset;
  double? _lastViewPadding;
  double? _lastSafeAreaPadding;
  bool? _lastShouldLift;
  ComposerKeyboardMetrics _lastMetrics = const ComposerKeyboardMetrics(
    inputBottomPadding: kChatComposerEdgeInset,
    keyboardSpacer: 0,
  );

  /// Latched rest-time bottom view padding, exposed for diagnostics/tests.
  double? get restViewPadding => _restViewPadding;

  ComposerKeyboardMetrics update({
    required bool shouldLiftComposerForKeyboard,
    required double bottomInset,
    required double viewPaddingBottom,
    required double safeAreaBottomPadding,
  }) {
    final inset = bottomInset.isFinite ? math.max(0.0, bottomInset) : 0.0;
    final viewPadding = viewPaddingBottom.isFinite
        ? math.max(0.0, viewPaddingBottom)
        : 0.0;
    final safeAreaPadding = safeAreaBottomPadding.isFinite
        ? math.max(0.0, safeAreaBottomPadding)
        : 0.0;

    // Rebuilds happen for many reasons besides metrics changes; only advance
    // the settle/debounce state machine when the raw samples change.
    final inputsUnchanged =
        _lastInset == inset &&
        _lastViewPadding == viewPadding &&
        _lastSafeAreaPadding == safeAreaPadding &&
        _lastShouldLift == shouldLiftComposerForKeyboard;
    if (inputsUnchanged) {
      return _lastMetrics;
    }
    _lastInset = inset;
    _lastViewPadding = viewPadding;
    _lastSafeAreaPadding = safeAreaPadding;
    _lastShouldLift = shouldLiftComposerForKeyboard;

    // Guard 1: latch the rest anchor while the keyboard is fully closed.
    // The settle frame itself (motion -> rest transition) may still carry a
    // degraded viewPadding (observed as 0 on ColorOS), so it may only raise
    // or hold the latch; a later rest -> rest sample is trusted to lower it
    // (legitimate navigation-mode changes happen with the keyboard closed).
    if (inset <= settleInsetThreshold) {
      final previous = _restViewPadding;
      _restViewPadding = (previous == null || _settled)
          ? viewPadding
          : math.max(previous, viewPadding);
    }
    final restAnchor = math.max(viewPadding, _restViewPadding ?? viewPadding);

    // Guard 3: defer re-engaging the lift by one frame after settling.
    var effectiveInset = inset;
    var deferred = false;
    if (inset <= settleInsetThreshold) {
      // Settle dead zone: sub-threshold residues are treated as fully
      // closed so the composer pins exactly at rest.
      effectiveInset = 0.0;
      _settled = true;
      _liftDeferredOnce = false;
      _closing = false;
      _lastEngagedInset = null;
    } else if (_settled) {
      if (!_liftDeferredOnce && inset <= restAnchor) {
        _liftDeferredOnce = true;
        effectiveInset = 0.0;
        deferred = true;
      } else {
        _settled = false;
        _liftDeferredOnce = false;
      }
    }

    // Guard 4 phase detection: the keyboard is closing once the engaged
    // inset shrinks; a clear rise (reopen) releases the cap again.
    if (!deferred && inset > settleInsetThreshold) {
      final lastEngaged = _lastEngagedInset;
      if (lastEngaged != null) {
        if (inset < lastEngaged - motionEpsilon) {
          _closing = true;
        } else if (inset > lastEngaged + motionEpsilon) {
          _closing = false;
        }
      }
      _lastEngagedInset = inset;
    }

    var inputBottomPadding = resolveChatComposerInputBottomPadding(
      shouldLiftComposerForKeyboard: shouldLiftComposerForKeyboard,
      bottomInset: effectiveInset,
      viewPaddingBottom: viewPadding,
      safeAreaBottomPadding: safeAreaPadding,
      edgeInset: edgeInset,
    );
    var keyboardSpacer = resolveChatComposerKeyboardSpacer(
      shouldLiftComposerForKeyboard: shouldLiftComposerForKeyboard,
      bottomInset: effectiveInset,
    );

    // Guard 2: never let the composer rest below its settled position.
    final restTotal = restAnchor + edgeInset;
    var total = safeAreaPadding + inputBottomPadding + keyboardSpacer;
    if (total < restTotal) {
      inputBottomPadding += restTotal - total;
      total = restTotal;
    }

    // Guard 4: while closing, ride the keyboard down in one continuous
    // motion instead of shelving clearance-height above rest and dropping
    // the final stretch in the last frames.
    if (_closing && shouldLiftComposerForKeyboard) {
      final capped = math.max(restTotal, effectiveInset + keyboardClearance);
      var reduction = total - capped;
      if (reduction > 0) {
        final fromBottomPadding = math.min(reduction, inputBottomPadding);
        inputBottomPadding -= fromBottomPadding;
        reduction -= fromBottomPadding;
        if (reduction > 0) {
          keyboardSpacer = math.max(0.0, keyboardSpacer - reduction);
        }
      }
    }

    _lastMetrics = ComposerKeyboardMetrics(
      inputBottomPadding: inputBottomPadding,
      keyboardSpacer: keyboardSpacer,
    );
    return _lastMetrics;
  }
}
