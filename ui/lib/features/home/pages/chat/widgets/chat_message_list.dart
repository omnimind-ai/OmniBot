part of 'chat_widgets.dart';

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
  final Set<String> activeAgentTurnIds;
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
  final String? emptyGreetingAgentName;
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
    this.activeAgentTurnIds = const <String>{},
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
    this.emptyGreetingAgentName,
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
  String? _timelineCacheAgentId;
  final Map<String, GlobalKey> _entryRowKeys = <String, GlobalKey>{};
  int _navigatorJumpSerial = 0;
  bool _navigatorJumpUserInterrupted = false;
  static const String _kListEntryKeyPrefix = 'chat-timeline-entry:';

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
      // Content-only rows rebuild through their item notifier, so
      // didUpdateWidget never gets a chance to restore latest-edge following.
      // This matters when an async link preview grows after the usage footer
      // was already visible: a list that is exactly back at the latest edge
      // must reattach before the new layout pushes that footer out of view.
      if (!_autoStickToLatest &&
          !_isAutoStickTemporarilySuppressed &&
          _isNearLatest(null, _manualLatestAttachTolerance)) {
        _autoStickToLatest = true;
      }
      _scheduleStickToLatest();
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
    final wasExpanded = nextExpandedTaskIds.contains(normalizedTaskId);
    if (wasExpanded) {
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
    if (!wasExpanded) {
      // Expanding a run increases its height below the header. When the run
      // is near the latest edge, the newly revealed reasoning can otherwise
      // land underneath the composer and look like it was not restored at
      // all. Scroll the whole run into view after the expansion animation has
      // laid out, without changing the user's position when they are reading
      // an older part of the conversation.
      final wasNearLatest = _isNearLatest(null);
      if (wasNearLatest) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted) return;
          unawaited(_animateToEntryKey('agent-run-$normalizedTaskId'));
        });
      }
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

  ValueKey<String> _listKeyForEntry(String entryKey) {
    return ValueKey<String>('$_kListEntryKeyPrefix$entryKey');
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
        activeTaskIds: widget.activeAgentTurnIds,
        conversationAgentId: widget.activeAcpAgentId,
      );
    }
    final cached = _timelineEntriesCache;
    if (cached != null &&
        identical(_timelineCacheSource, observable) &&
        _timelineCacheStructureRevision == observable.structureRevision &&
        setEquals(_timelineCacheActiveTaskIds, widget.activeAgentTurnIds) &&
        _timelineCacheAgentId == widget.activeAcpAgentId) {
      return cached;
    }
    final entries = buildAgentRunTimelineEntries(
      List<ChatMessageModel>.from(observable),
      activeTaskIds: widget.activeAgentTurnIds,
      conversationAgentId: widget.activeAcpAgentId,
    );
    _timelineEntriesCache = entries;
    _timelineCacheSource = observable;
    _timelineCacheStructureRevision = observable.structureRevision;
    _timelineCacheActiveTaskIds = Set<String>.from(widget.activeAgentTurnIds);
    _timelineCacheAgentId = widget.activeAcpAgentId;
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
                  agentName: widget.emptyGreetingAgentName,
                  agentWorkspaceName: widget.emptyGreetingAgentWorkspaceName,
                  onAgentWorkspaceTap: widget.onEmptyGreetingAgentWorkspaceTap,
                ),
              ),
            )
          : const SizedBox.expand();
      if (pageBackgroundColor == null) {
        return Padding(
          padding: EdgeInsets.only(bottom: reservedBottomInset),
          child: content,
        );
      }
      return ColoredBox(
        color: pageBackgroundColor,
        child: Padding(
          padding: EdgeInsets.only(bottom: reservedBottomInset),
          child: content,
        ),
      );
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
      findChildIndexCallback: (key) {
        if (key is! ValueKey<String> ||
            !key.value.startsWith(_kListEntryKeyPrefix)) {
          return null;
        }
        final entryKey = key.value.substring(_kListEntryKeyPrefix.length);
        final dataIndex = timelineEntries.indexWhere(
          (entry) => entry.key == entryKey,
        );
        return dataIndex == -1 ? null : timelineEntries.length - 1 - dataIndex;
      },
      itemBuilder: (context, index) {
        final dataIndex = timelineEntries.length - 1 - index;
        final entry = timelineEntries[dataIndex];
        final isOldestEntry = dataIndex == timelineEntries.length - 1;
        final needTopPadding = isOldestEntry && !entry.isUserMessage;
        // The list child uses a local ValueKey so sliver reordering does not
        // reparent a GlobalKey during a live ACP shape change. The nested
        // GlobalKey is only an already-laid-out scroll anchor; keeping those
        // identities separate avoids the framework's child == _child race.
        return KeyedSubtree(
          key: _listKeyForEntry(entry.key),
          child: KeyedSubtree(
            key: _rowKeyForEntry(entry.key),
            child: _buildTimelineListRow(
              messageSource: messageSource,
              entry: entry,
              latestUserMessageId: latestUserMessageId,
              padding: EdgeInsets.only(top: needTopPadding ? 24.0 : 0.0),
            ),
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
