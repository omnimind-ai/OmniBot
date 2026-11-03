import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';

void main() {
  const resolver = HdPadPaneLayoutResolver();

  test(
    'uses window width for portrait, landscape and multi-window layouts',
    () {
      expect(isChatWorkspaceViewport(const Size(600, 1024)), isFalse);
      expect(isChatWorkspaceViewport(const Size(719, 1366)), isFalse);
      expect(isChatWorkspaceViewport(const Size(720, 1024)), isTrue);
      expect(isChatWorkspaceViewport(const Size(768, 1024)), isTrue);
      expect(isChatWorkspaceViewport(const Size(1024, 1366)), isTrue);
      expect(isChatWorkspaceViewport(const Size(932, 430)), isTrue);
      // Showing an IME or reducing height never changes the column choice.
      expect(isChatWorkspaceViewport(const Size(960, 300)), isTrue);
    },
  );

  test('portrait and small landscape keep one sidebar and a readable chat', () {
    for (final width in [720.0, 768.0, 960.0, 1024.0]) {
      final layout = resolver.resolve(width - 16);
      expect(layout.leftWidth, greaterThan(0));
      expect(layout.rightWidth, 0);
      expect(layout.centerWidth, greaterThanOrEqualTo(420));
      expect(
        layout.leftWidth +
            layout.centerWidth +
            HdPadPaneLayoutResolver.dividerHitWidth,
        width - 16,
      );
    }
  });

  test('workspace replaces the left sidebar when only two panes fit', () {
    final layout = resolver.resolve(752, collapseLeftPane: true);
    expect(layout.leftWidth, 0);
    expect(layout.rightWidth, greaterThanOrEqualTo(240));
    expect(layout.centerWidth, greaterThanOrEqualTo(420));
    expect(
      layout.rightWidth +
          layout.centerWidth +
          HdPadPaneLayoutResolver.dividerHitWidth,
      752,
    );
  });

  test('narrow and invalid constraints never overflow or return negatives', () {
    for (final width in [0.0, 300.0, 600.0, 650.0, 900.0, 1366.0]) {
      final layout = resolver.resolve(width);
      final dividers =
          (layout.leftWidth > 0 ? HdPadPaneLayoutResolver.dividerHitWidth : 0) +
          (layout.rightWidth > 0 ? HdPadPaneLayoutResolver.dividerHitWidth : 0);
      expect(
        layout.leftWidth + layout.centerWidth + layout.rightWidth + dividers,
        closeTo(width, .001),
      );
      expect(layout.centerWidth, greaterThanOrEqualTo(0));
    }
    expect(resolver.resolve(double.infinity).centerWidth, 0);
    expect(resolver.resolve(double.nan).centerWidth, 0);
  });

  test('uses defaults within supported width', () {
    final layout = resolver.resolve(1200);

    expect(layout.leftWidth, HdPadPaneLayoutResolver.defaultLeftWidth);
    expect(layout.rightWidth, HdPadPaneLayoutResolver.defaultRightWidth);
    expect(
      layout.centerWidth,
      1200 -
          HdPadPaneLayoutResolver.dividerHitWidth * 2 -
          HdPadPaneLayoutResolver.defaultLeftWidth -
          HdPadPaneLayoutResolver.defaultRightWidth,
    );
  });

  test('clamps oversized preferences to preserve minimum center width', () {
    final layout = resolver.resolve(
      960,
      preferredLeftWidth: 360,
      preferredRightWidth: 420,
    );

    expect(
      layout.leftWidth,
      greaterThanOrEqualTo(HdPadPaneLayoutResolver.minLeftWidth),
    );
    expect(layout.rightWidth, 0);
    expect(
      layout.centerWidth,
      greaterThanOrEqualTo(HdPadPaneLayoutResolver.minCenterWidth),
    );
  });

  test('clamps saved widths to pane-specific bounds', () {
    final layout = resolver.resolve(
      1400,
      preferredLeftWidth: 120,
      preferredRightWidth: 1000,
    );

    expect(layout.leftWidth, HdPadPaneLayoutResolver.minLeftWidth);
    expect(
      layout.rightWidth,
      lessThanOrEqualTo(HdPadPaneLayoutResolver.maxRightWidth),
    );
    expect(
      layout.centerWidth,
      greaterThanOrEqualTo(HdPadPaneLayoutResolver.minCenterWidth),
    );
  });

  test('supports collapsing the left pane while keeping the right pane', () {
    final layout = resolver.resolve(
      1200,
      preferredLeftWidth: 320,
      preferredRightWidth: 300,
      collapseLeftPane: true,
    );

    expect(layout.leftWidth, 0);
    expect(layout.rightWidth, HdPadPaneLayoutResolver.defaultRightWidth);
    expect(
      layout.centerWidth,
      1200 -
          HdPadPaneLayoutResolver.dividerHitWidth -
          HdPadPaneLayoutResolver.defaultRightWidth,
    );
  });

  test('supports collapsing the right pane while keeping the left pane', () {
    final layout = resolver.resolve(
      1200,
      preferredLeftWidth: 260,
      preferredRightWidth: 360,
      collapseRightPane: true,
    );

    expect(layout.leftWidth, HdPadPaneLayoutResolver.defaultLeftWidth);
    expect(layout.rightWidth, 0);
    expect(
      layout.centerWidth,
      1200 -
          HdPadPaneLayoutResolver.dividerHitWidth -
          HdPadPaneLayoutResolver.defaultLeftWidth,
    );
  });

  test('supports collapsing both side panes', () {
    final layout = resolver.resolve(
      1200,
      collapseLeftPane: true,
      collapseRightPane: true,
    );

    expect(layout.leftWidth, 0);
    expect(layout.rightWidth, 0);
    expect(layout.centerWidth, 1200);
  });

  test('resolves overlay anchor from current keyboard spacing', () {
    final geometry = resolveChatPaneOverlayAnchorGeometry(
      viewportSize: const Size(420, 900),
      bottomSpacing: 260,
      anchorHeight: 96,
    );

    expect(geometry.bottom, 260);
    expect(geometry.rect.left, 24);
    expect(geometry.rect.width, 372);
    expect(geometry.rect.top, 640);
    expect(geometry.rect.height, 96);
  });

  test('adds composer clearance only when soft keyboard is visible', () {
    expect(
      resolveChatComposerKeyboardSpacer(
        shouldLiftComposerForKeyboard: true,
        bottomInset: 320,
      ),
      320 + kChatKeyboardComposerClearance,
    );
    expect(
      resolveChatComposerKeyboardSpacer(
        shouldLiftComposerForKeyboard: false,
        bottomInset: 320,
      ),
      0,
    );
    expect(
      resolveChatComposerKeyboardSpacer(
        shouldLiftComposerForKeyboard: true,
        bottomInset: 0,
      ),
      0,
    );
  });

  test('ramps composer clearance out as the soft keyboard finishes hiding', () {
    expect(
      resolveChatComposerKeyboardSpacer(
        shouldLiftComposerForKeyboard: true,
        bottomInset: 4,
      ),
      8,
    );
  });

  test('keeps the lifted composer bottom padding clamped to the edge '
      'inset', () {
    expect(
      resolveChatComposerInputBottomPadding(
        shouldLiftComposerForKeyboard: true,
        bottomInset: 320,
        viewPaddingBottom: 24,
        safeAreaBottomPadding: 0,
      ),
      0,
    );
    expect(
      resolveChatComposerInputBottomPadding(
        shouldLiftComposerForKeyboard: true,
        bottomInset: 30,
        viewPaddingBottom: 24,
        safeAreaBottomPadding: 0,
      ),
      18,
    );
    expect(
      resolveChatComposerInputBottomPadding(
        shouldLiftComposerForKeyboard: true,
        bottomInset: 0,
        viewPaddingBottom: 24,
        safeAreaBottomPadding: 24,
      ),
      kChatComposerEdgeInset,
    );
  });

  test('compensates pending SafeArea padding while the keyboard hides '
      'without focus', () {
    // Mid-hide: SafeArea bottom padding is still consumed by the keyboard
    // inset, so the unlifted composer padding makes up the difference.
    expect(
      resolveChatComposerInputBottomPadding(
        shouldLiftComposerForKeyboard: false,
        bottomInset: 300,
        viewPaddingBottom: 24,
        safeAreaBottomPadding: 0,
      ),
      kChatComposerEdgeInset + 24,
    );
    // Fully hidden: SafeArea padding restored, no compensation left.
    expect(
      resolveChatComposerInputBottomPadding(
        shouldLiftComposerForKeyboard: false,
        bottomInset: 0,
        viewPaddingBottom: 24,
        safeAreaBottomPadding: 24,
      ),
      kChatComposerEdgeInset,
    );
  });

  test('unlifted composer keeps a constant screen-anchored rest position '
      'through the whole keyboard hide animation', () {
    const viewPaddingBottom = 24.0;
    for (final bottomInset in const [300.0, 24.0, 12.0, 3.0, 0.5, 0.0]) {
      final safeAreaBottomPadding = bottomInset >= viewPaddingBottom
          ? 0.0
          : viewPaddingBottom - bottomInset;
      final padding = resolveChatComposerInputBottomPadding(
        shouldLiftComposerForKeyboard: false,
        bottomInset: bottomInset,
        viewPaddingBottom: viewPaddingBottom,
        safeAreaBottomPadding: safeAreaBottomPadding,
      );
      expect(
        safeAreaBottomPadding + padding,
        viewPaddingBottom + kChatComposerEdgeInset,
        reason: 'bottomInset $bottomInset should not move the settled composer',
      );
    }
  });

  test('SafeArea compensation is a no-op without bottom view padding', () {
    expect(
      resolveChatComposerInputBottomPadding(
        shouldLiftComposerForKeyboard: false,
        bottomInset: 200,
        viewPaddingBottom: 0,
        safeAreaBottomPadding: 0,
      ),
      kChatComposerEdgeInset,
    );
  });

  test('clamps overlay anchor when keyboard spacing exceeds viewport', () {
    final geometry = resolveChatPaneOverlayAnchorGeometry(
      viewportSize: const Size(420, 300),
      bottomSpacing: 480,
      anchorHeight: 0,
    );

    expect(geometry.bottom, 300);
    expect(geometry.rect.top, 0);
    expect(geometry.rect.height, 0);
  });
}
