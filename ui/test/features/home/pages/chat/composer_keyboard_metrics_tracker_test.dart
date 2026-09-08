import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/utils/composer_keyboard_metrics_tracker.dart';

/// One raw metrics sample as reported by the platform for a frame.
class _Sample {
  const _Sample(this.inset, this.viewPadding, this.padding, this.lift);

  final double inset;
  final double viewPadding;
  final double padding;
  final bool lift;
}

/// Frame-by-frame keyboard hide captured on a OnePlus PJD110 (gesture nav,
/// viewPadding.bottom = 16) while the composer kept focus (back-key
/// dismissal). padding.bottom == max(0, viewPadding - inset).
const List<_Sample> _focusRetainedHideTrace = [
  _Sample(304.00, 16, 0.00, true),
  _Sample(239.33, 16, 0.00, true),
  _Sample(150.67, 16, 0.00, true),
  _Sample(87.67, 16, 0.00, true),
  _Sample(58.67, 16, 0.00, true),
  _Sample(33.33, 16, 0.00, true),
  _Sample(20.33, 16, 0.00, true),
  _Sample(14.33, 16, 1.67, true),
  _Sample(7.00, 16, 9.00, true),
  _Sample(4.00, 16, 12.00, true),
  _Sample(2.00, 16, 14.00, true),
  _Sample(0.33, 16, 15.67, true),
  _Sample(0.00, 16, 16.00, true),
];

/// Same device, send-path dismissal: focus is cleared while the keyboard is
/// still at full height, then the keyboard animates away.
const List<_Sample> _unfocusedHideTrace = [
  _Sample(301.00, 16, 0.00, false),
  _Sample(298.33, 16, 0.00, false),
  _Sample(174.00, 16, 0.00, false),
  _Sample(113.67, 16, 0.00, false),
  _Sample(75.67, 16, 0.00, false),
  _Sample(50.67, 16, 0.00, false),
  _Sample(28.00, 16, 0.00, false),
  _Sample(17.00, 16, 0.00, false),
  _Sample(9.33, 16, 6.67, false),
  _Sample(2.00, 16, 14.00, false),
  _Sample(0.00, 16, 16.00, false),
];

/// Same device, keyboard open after tapping the focused composer.
const List<_Sample> _openTrace = [
  _Sample(0.00, 16, 16.00, true),
  _Sample(2.33, 16, 13.67, true),
  _Sample(126.67, 16, 0.00, true),
  _Sample(171.00, 16, 0.00, true),
  _Sample(225.00, 16, 0.00, true),
  _Sample(268.00, 16, 0.00, true),
  _Sample(289.33, 16, 0.00, true),
  _Sample(298.67, 16, 0.00, true),
  _Sample(301.00, 16, 0.00, true),
];

double _legacyInputBottomPadding(_Sample s) =>
    resolveChatComposerInputBottomPadding(
      shouldLiftComposerForKeyboard: s.lift,
      bottomInset: s.inset,
      viewPaddingBottom: s.viewPadding,
      safeAreaBottomPadding: s.padding,
    );

double _legacySpacer(_Sample s) => resolveChatComposerKeyboardSpacer(
  shouldLiftComposerForKeyboard: s.lift,
  bottomInset: s.inset,
);

/// The composer's distance from the physical screen bottom for a frame:
/// the SafeArea contribution plus the in-tree padding.
double _screenBottomDistance(_Sample s, ComposerKeyboardMetrics m) =>
    s.padding + m.inputBottomPadding + m.keyboardSpacer;

void main() {
  ComposerKeyboardMetricsTracker primedTracker() {
    final tracker = ComposerKeyboardMetricsTracker();
    // Prime the rest latch with one settled frame, as happens in production
    // builds long before any keyboard motion.
    tracker.update(
      shouldLiftComposerForKeyboard: false,
      bottomInset: 0,
      viewPaddingBottom: 16,
      safeAreaBottomPadding: 16,
    );
    return tracker;
  }

  ComposerKeyboardMetricsTracker trackerAtOpenKeyboard() {
    final tracker = primedTracker();
    for (final sample in _openTrace) {
      tracker.update(
        shouldLiftComposerForKeyboard: sample.lift,
        bottomInset: sample.inset,
        viewPaddingBottom: sample.viewPadding,
        safeAreaBottomPadding: sample.padding,
      );
    }
    return tracker;
  }

  test('rides the keyboard down with clearance and lands continuously at '
      'rest on a real focus-retained hide trace', () {
    final tracker = trackerAtOpenKeyboard();
    for (final sample in _focusRetainedHideTrace) {
      final metrics = tracker.update(
        shouldLiftComposerForKeyboard: sample.lift,
        bottomInset: sample.inset,
        viewPaddingBottom: sample.viewPadding,
        safeAreaBottomPadding: sample.padding,
      );
      // One closed form for the whole hide: keyboard top + clearance until
      // that reaches the rest position, then rest — no shelf above rest and
      // no late final step (the perceived settle bounce).
      final expectedTotal = math.max(
        16 + kChatComposerEdgeInset,
        sample.inset + kChatKeyboardComposerClearance,
      );
      expect(
        _screenBottomDistance(sample, metrics),
        closeTo(expectedTotal, 1e-9),
        reason: 'trajectory diverged at inset ${sample.inset}',
      );
    }
  });

  test('hide trajectory has no shelf: strictly falling until rest, then '
      'frozen', () {
    final tracker = trackerAtOpenKeyboard();
    const restTotal = 16 + kChatComposerEdgeInset;
    var reachedRest = false;
    double? previous;
    for (final sample in _focusRetainedHideTrace) {
      final metrics = tracker.update(
        shouldLiftComposerForKeyboard: sample.lift,
        bottomInset: sample.inset,
        viewPaddingBottom: sample.viewPadding,
        safeAreaBottomPadding: sample.padding,
      );
      final distance = _screenBottomDistance(sample, metrics);
      if (reachedRest) {
        expect(
          distance,
          closeTo(restTotal, 1e-9),
          reason: 'composer moved after landing at inset ${sample.inset}',
        );
      } else if (previous != null) {
        expect(
          distance,
          lessThan(previous),
          reason: 'composer stalled above rest at inset ${sample.inset}',
        );
      }
      if ((distance - restTotal).abs() < 1e-9) {
        reachedRest = true;
      }
      previous = distance;
    }
    expect(reachedRest, isTrue);
  });

  test('reopening mid-hide releases the landing cap and restores the legacy '
      'lift trajectory', () {
    final tracker = trackerAtOpenKeyboard();
    // Close partially...
    const partialHide = [
      _Sample(304.00, 16, 0.00, true),
      _Sample(150.67, 16, 0.00, true),
      _Sample(58.67, 16, 0.00, true),
    ];
    for (final sample in partialHide) {
      tracker.update(
        shouldLiftComposerForKeyboard: sample.lift,
        bottomInset: sample.inset,
        viewPaddingBottom: sample.viewPadding,
        safeAreaBottomPadding: sample.padding,
      );
    }
    // ...then the keyboard comes back up: legacy formulas again.
    const reopen = [
      _Sample(120.00, 16, 0.00, true),
      _Sample(250.00, 16, 0.00, true),
    ];
    for (final sample in reopen) {
      final metrics = tracker.update(
        shouldLiftComposerForKeyboard: sample.lift,
        bottomInset: sample.inset,
        viewPaddingBottom: sample.viewPadding,
        safeAreaBottomPadding: sample.padding,
      );
      expect(
        metrics.inputBottomPadding,
        closeTo(_legacyInputBottomPadding(sample), 1e-9),
      );
      expect(metrics.keyboardSpacer, closeTo(_legacySpacer(sample), 1e-9));
    }
  });

  test('keyboard open trace only defers the first sub-perceptual frame', () {
    final tracker = primedTracker();
    for (var i = 0; i < _openTrace.length; i++) {
      final sample = _openTrace[i];
      final metrics = tracker.update(
        shouldLiftComposerForKeyboard: sample.lift,
        bottomInset: sample.inset,
        viewPaddingBottom: sample.viewPadding,
        safeAreaBottomPadding: sample.padding,
      );
      if (i == 1) {
        // First rising frame (2.33dp) is deferred: composer stays at rest.
        expect(_screenBottomDistance(sample, metrics), closeTo(40, 1e-9));
        continue;
      }
      expect(
        metrics.inputBottomPadding,
        closeTo(_legacyInputBottomPadding(sample), 1e-9),
        reason: 'inputBottomPadding diverged at inset ${sample.inset}',
      );
      expect(
        metrics.keyboardSpacer,
        closeTo(_legacySpacer(sample), 1e-9),
        reason: 'keyboardSpacer diverged at inset ${sample.inset}',
      );
    }
  });

  test('composer screen position is monotone non-increasing and settles at '
      'rest on the focus-retained hide trace', () {
    final tracker = trackerAtOpenKeyboard();
    double? previous;
    late double last;
    for (final sample in _focusRetainedHideTrace) {
      final metrics = tracker.update(
        shouldLiftComposerForKeyboard: sample.lift,
        bottomInset: sample.inset,
        viewPaddingBottom: sample.viewPadding,
        safeAreaBottomPadding: sample.padding,
      );
      final distance = _screenBottomDistance(sample, metrics);
      if (previous != null) {
        expect(
          distance,
          lessThanOrEqualTo(previous + 1e-9),
          reason: 'composer rose mid-hide at inset ${sample.inset}',
        );
      }
      previous = distance;
      last = distance;
    }
    expect(last, closeTo(16 + kChatComposerEdgeInset, 1e-9));
  });

  test('composer holds its rest position for the entire unfocused hide', () {
    final tracker = trackerAtOpenKeyboard();
    for (final sample in _unfocusedHideTrace) {
      final metrics = tracker.update(
        shouldLiftComposerForKeyboard: sample.lift,
        bottomInset: sample.inset,
        viewPaddingBottom: sample.viewPadding,
        safeAreaBottomPadding: sample.padding,
      );
      expect(
        _screenBottomDistance(sample, metrics),
        closeTo(16 + kChatComposerEdgeInset, 1e-9),
        reason: 'composer moved at inset ${sample.inset}',
      );
    }
  });

  test('swallows a single-frame inset blip after settling', () {
    final tracker = trackerAtOpenKeyboard();
    // Settled with focus retained (back-key dismissal already finished).
    for (final sample in _focusRetainedHideTrace) {
      tracker.update(
        shouldLiftComposerForKeyboard: sample.lift,
        bottomInset: sample.inset,
        viewPaddingBottom: sample.viewPadding,
        safeAreaBottomPadding: sample.padding,
      );
    }
    // Late engine blip: inset pops to 8dp for one frame, then returns to 0.
    final blip = tracker.update(
      shouldLiftComposerForKeyboard: true,
      bottomInset: 8,
      viewPaddingBottom: 16,
      safeAreaBottomPadding: 8,
    );
    expect(
      8 + blip.inputBottomPadding + blip.keyboardSpacer,
      closeTo(16 + kChatComposerEdgeInset, 1e-9),
      reason: 'blip frame must keep the composer at rest',
    );
    final settled = tracker.update(
      shouldLiftComposerForKeyboard: true,
      bottomInset: 0,
      viewPaddingBottom: 16,
      safeAreaBottomPadding: 16,
    );
    expect(
      16 + settled.inputBottomPadding + settled.keyboardSpacer,
      closeTo(16 + kChatComposerEdgeInset, 1e-9),
    );
  });

  test('rest latch keeps the composer anchored when viewPadding collapses '
      'mid-hide (degraded OEM signal)', () {
    final tracker = primedTracker();
    // Keyboard fully open, healthy signal.
    tracker.update(
      shouldLiftComposerForKeyboard: true,
      bottomInset: 300,
      viewPaddingBottom: 16,
      safeAreaBottomPadding: 0,
    );
    // Hide animation during which the platform wrongly reports
    // viewPadding.bottom = 0 (observed on ColorOS nav-mode transitions).
    const degraded = [
      _Sample(150.0, 0, 0, true),
      _Sample(50.0, 0, 0, true),
      _Sample(20.0, 0, 0, true),
      _Sample(5.0, 0, 0, true),
      _Sample(0.0, 0, 0, true),
    ];
    double? previous;
    for (final sample in degraded) {
      final metrics = tracker.update(
        shouldLiftComposerForKeyboard: sample.lift,
        bottomInset: sample.inset,
        viewPaddingBottom: sample.viewPadding,
        safeAreaBottomPadding: sample.padding,
      );
      final distance = _screenBottomDistance(sample, metrics);
      expect(
        distance,
        greaterThanOrEqualTo(16 + kChatComposerEdgeInset - 1e-9),
        reason: 'composer dipped below rest at inset ${sample.inset}',
      );
      if (previous != null) {
        expect(distance, lessThanOrEqualTo(previous + 1e-9));
      }
      previous = distance;
    }
    // When the real padding is finally restored at the end of the
    // animation, the composer must not move.
    final restored = tracker.update(
      shouldLiftComposerForKeyboard: true,
      bottomInset: 0,
      viewPaddingBottom: 16,
      safeAreaBottomPadding: 16,
    );
    expect(
      16 + restored.inputBottomPadding + restored.keyboardSpacer,
      closeTo(16 + kChatComposerEdgeInset, 1e-9),
      reason: 'late padding restoration must not pop the composer',
    );
  });

  test('repeated identical samples return cached metrics without advancing '
      'the debounce state', () {
    final tracker = primedTracker();
    final first = tracker.update(
      shouldLiftComposerForKeyboard: true,
      bottomInset: 300,
      viewPaddingBottom: 16,
      safeAreaBottomPadding: 0,
    );
    final second = tracker.update(
      shouldLiftComposerForKeyboard: true,
      bottomInset: 300,
      viewPaddingBottom: 16,
      safeAreaBottomPadding: 0,
    );
    expect(identical(first, second), isTrue);
  });

  test('a keyboard opening without intermediate frames cannot hide composer', () {
    final tracker = primedTracker();
    // IME metrics may jump directly to the final height. No later different
    // sample is guaranteed, even when unrelated page rebuilds occur.
    for (var frame = 0; frame < 3; frame++) {
      const sample = _Sample(300, 16, 0, true);
      final metrics = tracker.update(
        shouldLiftComposerForKeyboard: sample.lift,
        bottomInset: sample.inset,
        viewPaddingBottom: sample.viewPadding,
        safeAreaBottomPadding: sample.padding,
      );
      expect(
        _screenBottomDistance(sample, metrics),
        greaterThanOrEqualTo(300),
        reason:
            'Composer must clear a fully open keyboard, including frame $frame',
      );
    }
  });

  test('latch refreshes at rest so a legitimately smaller nav inset is '
      'adopted', () {
    final tracker = primedTracker();
    // Device switches to a smaller bottom inset (e.g. nav mode change while
    // the keyboard is closed) — the new rest must win, not the old latch.
    final metrics = tracker.update(
      shouldLiftComposerForKeyboard: false,
      bottomInset: 0,
      viewPaddingBottom: 0,
      safeAreaBottomPadding: 0,
    );
    expect(
      0 + metrics.inputBottomPadding + metrics.keyboardSpacer,
      closeTo(kChatComposerEdgeInset, 1e-9),
    );
  });
}
