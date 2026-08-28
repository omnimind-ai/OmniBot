part of 'chat_page.dart';

mixin _ChatPageBrowserMixin on _ChatPageStateBase {
  static const double _kBrowserOverlayMinWidth = 280;
  static const double _kBrowserOverlayMinHeight = 240;
  static const double _kBrowserOverlayMaxWidthFactor = 0.92;
  static const double _kBrowserOverlayMaxHeightFactor = 0.78;
  static const double _kBrowserOverlayHorizontalMargin = 16;
  static const double _kBrowserOverlayTopMargin = 64;
  static const double _kBrowserOverlayBottomMargin = 16;
  static const double _kDrawerSwipeThreshold = 36;

  bool _maybeOpenDrawerFromPageSwipe() {
    if (!mounted ||
        // 锚点面板展开时整页处于"点击空白收起"的模态态，此时的滑动不应
        // 被当成呼出抽屉的手势（否则从左往右轻滑一小段就会误开 home_drawer）。
        _messageAnchorExpanded ||
        _activeSurfaceMode != ChatSurfaceMode.normal ||
        _isHdPadLandscapeForMediaQuery(MediaQuery.of(context))) {
      return false;
    }
    final horizontalDelta = _pageHorizontalDragDelta;
    if (horizontalDelta < _kDrawerSwipeThreshold) {
      return false;
    }
    if (horizontalDelta.abs() <= _pageVerticalDragDelta.abs() * 1.2) {
      return false;
    }
    final scaffoldState = _scaffoldKey.currentState;
    if (scaffoldState == null || scaffoldState.isDrawerOpen) {
      return false;
    }
    _dismissChatInputFocus();
    scaffoldState.openDrawer();
    return true;
  }

  @override
  void _handlePagePointerDown(PointerDownEvent event) {
    if (_activeSurfaceMode != ChatSurfaceMode.normal) {
      _pageGesturePointerId = null;
      _pageHorizontalDragDelta = 0;
      _pageVerticalDragDelta = 0;
      return;
    }
    // 锚点面板展开时不追踪整页滑动，滑动只用于操作锚点/收起面板，不呼出抽屉。
    if (_messageAnchorExpanded) {
      _pageGesturePointerId = null;
      _pageHorizontalDragDelta = 0;
      _pageVerticalDragDelta = 0;
      return;
    }
    if (_isBrowserOverlayVisible &&
        _isPointerInside(_browserOverlayKey, event.position)) {
      _pageGesturePointerId = null;
      _pageHorizontalDragDelta = 0;
      _pageVerticalDragDelta = 0;
      return;
    }
    if (_isPointerInside(_inputAreaKey, event.position)) {
      _pageGesturePointerId = null;
      _pageHorizontalDragDelta = 0;
      _pageVerticalDragDelta = 0;
      return;
    }
    if ((_showModelMentionPanel ||
            _showSlashCommandPanel ||
            _openClawPanelExpanded) &&
        (_isPointerInside(_openClawPanelKey, event.position) ||
            _isPointerInside(_slashCommandStripKey, event.position))) {
      _pageGesturePointerId = null;
      _pageHorizontalDragDelta = 0;
      _pageVerticalDragDelta = 0;
      return;
    }
    _pageGesturePointerId = event.pointer;
    _pageHorizontalDragDelta = 0;
    _pageVerticalDragDelta = 0;
  }

  @override
  void _handlePagePointerMove(PointerMoveEvent event) {
    if (event.pointer != _pageGesturePointerId ||
        _activeSurfaceMode != ChatSurfaceMode.normal) {
      return;
    }
    if (ToolCardDetailGestureGate.containsPointer(event.pointer) ||
        ChatDrawerGestureGate.containsPointer(event.pointer)) {
      _pageGesturePointerId = null;
      _pageHorizontalDragDelta = 0;
      _pageVerticalDragDelta = 0;
      return;
    }
    _pageHorizontalDragDelta += event.delta.dx;
    _pageVerticalDragDelta += event.delta.dy;
  }

  @override
  void _handlePagePointerUp(PointerUpEvent event) {
    if (event.pointer != _pageGesturePointerId) {
      return;
    }
    if (ToolCardDetailGestureGate.containsPointer(event.pointer) ||
        ChatDrawerGestureGate.containsPointer(event.pointer)) {
      _pageGesturePointerId = null;
      _pageHorizontalDragDelta = 0;
      _pageVerticalDragDelta = 0;
      return;
    }
    _maybeOpenDrawerFromPageSwipe();
    _pageGesturePointerId = null;
    _pageHorizontalDragDelta = 0;
    _pageVerticalDragDelta = 0;
  }

  @override
  void _handlePagePointerCancel(PointerCancelEvent event) {
    if (event.pointer != _pageGesturePointerId) {
      return;
    }
    _pageGesturePointerId = null;
    _pageHorizontalDragDelta = 0;
    _pageVerticalDragDelta = 0;
  }

  @override
  String _browserSnapshotSignature(ChatBrowserSessionSnapshot? snapshot) {
    if (snapshot == null) {
      return '';
    }
    return [
      snapshot.available ? '1' : '0',
      snapshot.workspaceId,
      snapshot.activeTabId?.toString() ?? '',
      snapshot.currentUrl,
      snapshot.title,
      snapshot.userAgentProfile ?? '',
      snapshot.isBookmarked ? '1' : '0',
      snapshot.canGoBack ? '1' : '0',
      snapshot.canGoForward ? '1' : '0',
      snapshot.isLoading ? '1' : '0',
      snapshot.hasSslError ? '1' : '0',
      snapshot.isDesktopMode ? '1' : '0',
      snapshot.riskChallengeDetected ? '1' : '0',
      snapshot.riskChallengeKind ?? '',
      snapshot.recommendedNextAction ?? '',
      snapshot.throttleDelayMs?.toString() ?? '',
      snapshot.activeDownloadCount.toString(),
      snapshot.tabs.length.toString(),
      snapshot.downloads.length.toString(),
      snapshot.userscriptSummary.installedScripts.length.toString(),
      snapshot.externalOpenPrompt?.requestId ?? '',
      snapshot.pendingDialog?.requestId ?? '',
      snapshot.permissionPrompt?.requestId ?? '',
    ].join('|');
  }

  @override
  void _scheduleBrowserSessionRefreshIfNeeded() {
    if (_activeSurfaceMode != ChatSurfaceMode.normal) {
      return;
    }
    final browserMode = _activeConversationMode;
    final snapshot = _runtimeForMode(browserMode)?.browserSessionSnapshot;
    if (snapshot == null ||
        !snapshot.matchesWorkspace(_expectedBrowserWorkspaceId)) {
      return;
    }
    final signature = _browserSnapshotSignature(snapshot);
    if (signature.isEmpty ||
        signature == _lastObservedBrowserSnapshotSignature) {
      return;
    }
    _lastObservedBrowserSnapshotSignature = signature;
    unawaited(_refreshLiveBrowserSessionSnapshot(syncRuntime: true));
  }

  @override
  Future<void> _refreshLiveBrowserSessionSnapshot({
    bool syncRuntime = false,
  }) async {
    final browserMode = _activeConversationMode;
    final expectedWorkspaceId = chatConversationWorkspaceId(
      _modeState(browserMode).currentConversationId,
    );
    final snapshot = await AgentBrowserSessionService.getSnapshot();
    if (!mounted) {
      return;
    }
    final resolved = snapshot?.matchesWorkspace(expectedWorkspaceId) == true
        ? snapshot
        : null;
    final previousSignature = _browserSnapshotSignature(
      _liveBrowserSessionSnapshot,
    );
    final nextSignature = _browserSnapshotSignature(resolved);

    if (syncRuntime) {
      final runtime = _runtimeForMode(browserMode);
      if (runtime != null) {
        runtime.browserSessionSnapshot = resolved;
      } else {
        _modeState(browserMode).browserSessionSnapshot = resolved;
      }
    }

    // The platform snapshot request is asynchronous. A Harness/conversation
    // switch may complete while it is in flight; keep the old runtime updated
    // but never paint that old browser session over the newly visible chat.
    if (browserMode != _activeConversationMode) {
      return;
    }

    final shouldHideOverlay = resolved == null || !resolved.available;
    if (!mounted) {
      return;
    }
    setState(() {
      _liveBrowserSessionSnapshot = resolved;
      if (previousSignature != nextSignature) {
        _browserOverlayViewSeed += 1;
      }
      if (shouldHideOverlay) {
        _isBrowserOverlayVisible = false;
        _isBrowserOverlayInitialized = false;
      }
    });
  }

  @override
  void _handleBrowserSessionSnapshotChanged(Map<String, dynamic> raw) {
    final snapshot = ChatBrowserSessionSnapshot.fromMap(raw);
    final browserMode = _browserModeForWorkspace(snapshot.workspaceId);
    if (browserMode == null) {
      return;
    }
    final expectedWorkspaceId = chatConversationWorkspaceId(
      _modeState(browserMode).currentConversationId,
    );
    final resolved =
        snapshot.matchesWorkspace(expectedWorkspaceId) && snapshot.available
        ? snapshot
        : null;
    final runtime = _runtimeForMode(browserMode);
    if (runtime != null) {
      runtime.browserSessionSnapshot = resolved;
    } else {
      _modeState(browserMode).browserSessionSnapshot = resolved;
    }
    if (browserMode != _activeConversationMode) {
      return;
    }
    if (!mounted) {
      _liveBrowserSessionSnapshot = resolved;
      return;
    }
    final previousSignature = _browserSnapshotSignature(
      _liveBrowserSessionSnapshot,
    );
    final nextSignature = _browserSnapshotSignature(resolved);
    final shouldHideOverlay = resolved == null || !resolved.available;
    setState(() {
      _liveBrowserSessionSnapshot = resolved;
      if (previousSignature != nextSignature) {
        _browserOverlayViewSeed += 1;
      }
      if (shouldHideOverlay) {
        _isBrowserOverlayVisible = false;
        _isBrowserOverlayInitialized = false;
      }
    });
  }

  @override
  void _setChatIslandDisplayLayerForMode(
    ChatPageMode mode,
    ChatIslandDisplayLayer layer,
  ) {
    final resolvedLayer = mode == ChatPageMode.openclaw
        ? ChatIslandDisplayLayer.mode
        : layer;
    _modeState(mode).chatIslandDisplayLayer = resolvedLayer;
    final runtime = _runtimeForMode(mode);
    if (runtime != null) {
      runtime.chatIslandDisplayLayer = resolvedLayer;
    }
  }

  @override
  void _handleChatIslandDisplayLayerChanged(ChatIslandDisplayLayer layer) {
    if (_activeSurfaceMode != ChatSurfaceMode.normal) {
      return;
    }
    setState(() {
      _setChatIslandDisplayLayerForMode(_activeConversationMode, layer);
      if (layer != ChatIslandDisplayLayer.tools) {
        _isBrowserOverlayVisible = false;
      }
    });
  }

  @override
  Future<void> _handleTerminalToolTap() async {
    if (_activeSurfaceMode != ChatSurfaceMode.normal) {
      return;
    }
    final browserMode = _activeConversationMode;
    setState(() {
      _setChatIslandDisplayLayerForMode(
        browserMode,
        ChatIslandDisplayLayer.tools,
      );
    });
    try {
      await openNativeTerminal();
    } catch (error) {
      if (!mounted) {
        return;
      }
      showToast('打开终端失败: $error', type: ToastType.error);
    }
  }

  @override
  Future<void> _handleBrowserToolTap() async {
    if (_activeSurfaceMode != ChatSurfaceMode.normal) {
      return;
    }
    final browserMode = _activeConversationMode;
    if (!Platform.isAndroid) {
      showToast('当前平台暂不支持浏览器工具视图', type: ToastType.warning);
      return;
    }
    await _refreshLiveBrowserSessionSnapshot(syncRuntime: true);
    if (!mounted || browserMode != _activeConversationMode) {
      return;
    }
    final snapshot = _resolvedBrowserSessionSnapshot;
    if (snapshot == null || !snapshot.available) {
      showToast('当前会话还没有可用的浏览器会话', type: ToastType.warning);
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _setChatIslandDisplayLayerForMode(
        browserMode,
        ChatIslandDisplayLayer.tools,
      );
      _isBrowserOverlayVisible = true;
      _browserOverlayViewSeed += 1;
    });
  }

  @override
  void _hideBrowserOverlay() {
    if (!_isBrowserOverlayVisible && !_isBrowserOverlayInitialized) {
      return;
    }
    setState(() {
      _isBrowserOverlayVisible = false;
      _isBrowserOverlayInitialized = false;
    });
  }

  Size _overlayMaxSize(BoxConstraints constraints) {
    final maxWidth = (constraints.maxWidth * _kBrowserOverlayMaxWidthFactor)
        .clamp(_kBrowserOverlayMinWidth, constraints.maxWidth)
        .toDouble();
    final maxHeight = (constraints.maxHeight * _kBrowserOverlayMaxHeightFactor)
        .clamp(_kBrowserOverlayMinHeight, constraints.maxHeight)
        .toDouble();
    return Size(maxWidth, maxHeight);
  }

  Size _clampBrowserOverlaySize(Size size, BoxConstraints constraints) {
    final maxSize = _overlayMaxSize(constraints);
    return Size(
      size.width.clamp(_kBrowserOverlayMinWidth, maxSize.width).toDouble(),
      size.height.clamp(_kBrowserOverlayMinHeight, maxSize.height).toDouble(),
    );
  }

  Offset _clampBrowserOverlayOffset(
    Offset offset,
    Size size,
    BoxConstraints constraints,
  ) {
    final maxLeft =
        (constraints.maxWidth - size.width - _kBrowserOverlayHorizontalMargin)
            .clamp(_kBrowserOverlayHorizontalMargin, double.infinity)
            .toDouble();
    final maxTop =
        (constraints.maxHeight - size.height - _kBrowserOverlayBottomMargin)
            .clamp(_kBrowserOverlayTopMargin, double.infinity)
            .toDouble();
    return Offset(
      offset.dx.clamp(_kBrowserOverlayHorizontalMargin, maxLeft).toDouble(),
      offset.dy.clamp(_kBrowserOverlayTopMargin, maxTop).toDouble(),
    );
  }

  @override
  void _ensureBrowserOverlayGeometry(BoxConstraints constraints) {
    final targetSize = _clampBrowserOverlaySize(
      _browserOverlaySize,
      constraints,
    );
    final defaultOffset = Offset(
      (constraints.maxWidth -
              targetSize.width -
              _kBrowserOverlayHorizontalMargin)
          .clamp(_kBrowserOverlayHorizontalMargin, double.infinity)
          .toDouble(),
      _kBrowserOverlayTopMargin,
    );
    _browserOverlaySize = targetSize;
    _browserOverlayOffset = _clampBrowserOverlayOffset(
      _isBrowserOverlayInitialized ? _browserOverlayOffset : defaultOffset,
      targetSize,
      constraints,
    );
    _isBrowserOverlayInitialized = true;
  }

  @override
  void _moveBrowserOverlay(Offset delta, BoxConstraints constraints) {
    setState(() {
      _ensureBrowserOverlayGeometry(constraints);
      _browserOverlayOffset = _clampBrowserOverlayOffset(
        _browserOverlayOffset + delta,
        _browserOverlaySize,
        constraints,
      );
    });
  }

  @override
  void _resizeBrowserOverlayFromLeft(Offset delta, BoxConstraints constraints) {
    setState(() {
      _ensureBrowserOverlayGeometry(constraints);
      final currentRight = _browserOverlayOffset.dx + _browserOverlaySize.width;
      final maxSize = _overlayMaxSize(constraints);
      final nextHeight = _clampBrowserOverlaySize(
        Size(_browserOverlaySize.width, _browserOverlaySize.height + delta.dy),
        constraints,
      ).height;
      var nextLeft = (_browserOverlayOffset.dx + delta.dx).clamp(
        _kBrowserOverlayHorizontalMargin,
        currentRight - _kBrowserOverlayMinWidth,
      );
      var nextWidth = currentRight - nextLeft;
      if (nextWidth > maxSize.width) {
        nextWidth = maxSize.width;
        nextLeft = currentRight - nextWidth;
      }
      _browserOverlaySize = Size(nextWidth.toDouble(), nextHeight);
      _browserOverlayOffset = _clampBrowserOverlayOffset(
        Offset(nextLeft.toDouble(), _browserOverlayOffset.dy),
        _browserOverlaySize,
        constraints,
      );
    });
  }

  @override
  void _resizeBrowserOverlayFromRight(
    Offset delta,
    BoxConstraints constraints,
  ) {
    setState(() {
      _ensureBrowserOverlayGeometry(constraints);
      final resized = _clampBrowserOverlaySize(
        Size(
          _browserOverlaySize.width + delta.dx,
          _browserOverlaySize.height + delta.dy,
        ),
        constraints,
      );
      _browserOverlaySize = resized;
      _browserOverlayOffset = _clampBrowserOverlayOffset(
        _browserOverlayOffset,
        resized,
        constraints,
      );
    });
  }

  @override
  Rect _browserOverlayBounds(BoxConstraints constraints) {
    final size = _clampBrowserOverlaySize(_browserOverlaySize, constraints);
    final offset = _clampBrowserOverlayOffset(
      _isBrowserOverlayInitialized
          ? _browserOverlayOffset
          : Offset(
              (constraints.maxWidth -
                      size.width -
                      _kBrowserOverlayHorizontalMargin)
                  .clamp(_kBrowserOverlayHorizontalMargin, double.infinity)
                  .toDouble(),
              _kBrowserOverlayTopMargin,
            ),
      size,
      constraints,
    );
    return Rect.fromLTWH(offset.dx, offset.dy, size.width, size.height);
  }

  @override
  Widget _buildBrowserOverlay(BoxConstraints constraints) {
    if (!_isBrowserOverlayVisible || _normalSurfaceVisibility <= 0.001) {
      return const SizedBox.shrink();
    }
    final snapshot = _resolvedBrowserSessionSnapshot;
    if (snapshot == null || !snapshot.available) {
      return const SizedBox.shrink();
    }
    if (!_isBrowserOverlayInitialized) {
      _ensureBrowserOverlayGeometry(constraints);
    }
    final bounds = _browserOverlayBounds(constraints);
    return Positioned(
      left: bounds.left,
      top: bounds.top,
      width: bounds.width,
      height: bounds.height,
      child: IgnorePointer(
        ignoring: _normalSurfaceVisibility < 0.999,
        child: Opacity(
          opacity: Curves.easeOutCubic.transform(_normalSurfaceVisibility),
          child: Transform.translate(
            offset: Offset(-_surfacePageProgress * constraints.maxWidth, 0),
            child: KeyedSubtree(
              key: ValueKey(
                'agent-browser-overlay-${snapshot.workspaceId}-$_browserOverlayViewSeed',
              ),
              child: ChatBrowserOverlay(
                key: _browserOverlayKey,
                snapshot: snapshot,
                onSnapshotChanged: (next) {
                  if (!mounted) {
                    return;
                  }
                  final browserMode = next == null
                      ? _activeConversationMode
                      : _browserModeForWorkspace(next.workspaceId);
                  if (browserMode == null) {
                    return;
                  }
                  final expectedWorkspaceId = chatConversationWorkspaceId(
                    _modeState(browserMode).currentConversationId,
                  );
                  final resolved =
                      next?.matchesWorkspace(expectedWorkspaceId) == true
                      ? next
                      : null;
                  final runtime = _runtimeForMode(browserMode);
                  if (runtime != null) {
                    runtime.browserSessionSnapshot = resolved;
                  } else {
                    _modeState(browserMode).browserSessionSnapshot = resolved;
                  }
                  if (browserMode != _activeConversationMode) {
                    return;
                  }
                  setState(() {
                    _liveBrowserSessionSnapshot = resolved;
                    if (resolved == null || !resolved.available) {
                      _isBrowserOverlayVisible = false;
                      _isBrowserOverlayInitialized = false;
                    }
                  });
                },
                onClose: _hideBrowserOverlay,
                onDragDelta: (delta) => _moveBrowserOverlay(delta, constraints),
                onResizeLeftDelta: (delta) =>
                    _resizeBrowserOverlayFromLeft(delta, constraints),
                onResizeRightDelta: (delta) =>
                    _resizeBrowserOverlayFromRight(delta, constraints),
              ),
            ),
          ),
        ),
      ),
    );
  }

  ChatPageMode? _browserModeForWorkspace(String workspaceId) {
    for (final mode in const <ChatPageMode>[
      ChatPageMode.normal,
      ChatPageMode.agent,
    ]) {
      if (workspaceId ==
          chatConversationWorkspaceId(_modeState(mode).currentConversationId)) {
        return mode;
      }
    }
    return null;
  }
}
