part of 'chat_page.dart';

const int _kDefaultContextTokenThreshold = 128000;
const int _kMinContextTokenThreshold = 10000;
const int _kMaxContextTokenThreshold = 1000000;
const double _kChatMessageBottomSafeSpacing = 12.0;
const double _kSlashCommandDrawerRadius = 18.0;
const double _kSlashCommandDrawerHandleWidth = 36.0;
const double _kSlashCommandDrawerHandleHeight = 4.0;
const List<String> _kAgentReasoningEffortOptions = <String>[
  'no',
  'low',
  'high',
  'xhigh',
  'max',
];

enum _UserMessageQuickAction { copy, edit, retry }

mixin _ChatPageUiMixin on _ChatPageStateBase {
  ChatPaneOverlayAnchorGeometry? _lastStableToolActivityAnchorGeometry;
  static const double _kChatInputWrapperTopPadding = 8.0;
  static const double _kChatInputFallbackHeight = 80.0;
  static const double _kHdPadPaneCollapseWidthRatio = 0.12;
  static const double _kHdPadPaneCollapseMinWidthFactor = 0.72;
  final Set<String> _pendingManualAgentRetryTaskIds = <String>{};
  final Set<String> _pendingManualAgentContinueTaskIds = <String>{};
  bool _isHomeDrawerSearchFocused = false;

  void _handleHomeDrawerSearchFocusChanged(bool hasFocus) {
    if (!mounted || _isHomeDrawerSearchFocused == hasFocus) {
      return;
    }
    final chatInputWasFocused = _inputFocusNode.hasFocus;
    final composerLiftWasLatched = _composerLiftIntentTracker.isLatched;
    if (hasFocus && _inputFocusNode.hasFocus) {
      _inputFocusNode.unfocus();
    }
    _composerLiftIntentTracker.reset();
    // Avoid an unconditional ChatPage rebuild just to record focus ownership.
    // The search field repaints itself locally, while the next focus/IME
    // metrics frame normally reads this flag for composer suppression.
    _isHomeDrawerSearchFocused = hasFocus;
    if (hasFocus && composerLiftWasLatched && !chatInputWasFocused) {
      // A previously lost chat focus may have left the lift latched against a
      // stable IME inset, so no new focus/metrics notification is guaranteed.
      setState(() {});
    }
  }

  void _handleHomeDrawerChanged(bool isOpen) {
    if (!mounted) {
      return;
    }
    if (isOpen) {
      _dismissChatInputFocus();
      _composerLiftIntentTracker.reset();
      _drawerKey.currentState?.reloadConversations();
    } else {
      _isHomeDrawerSearchFocused = false;
      _composerLiftIntentTracker.reset();
      checkAndHandleDeletedConversation();
    }
  }

  ChatPageMode get _primaryChatMessagePageMode =>
      _activeMode == ChatPageMode.agent
      ? ChatPageMode.agent
      : ChatPageMode.normal;

  @override
  void _armComposerLiftIntent() {
    _composerLiftIntentTracker.arm();
  }

  @override
  void _requestComposerFocus({bool showKeyboard = false}) {
    _armComposerLiftIntent();
    _inputFocusNode.requestFocus();
    if (showKeyboard) {
      SystemChannels.textInput.invokeMethod('TextInput.show');
    }
  }

  void _applyHomeQuickPrompt(HomeQuickPrompt prompt) {
    _suppressNextOutsideTapKeyboardHide = true;
    final text = prompt.resolvePrompt(context).trim();
    if (text.isEmpty) {
      return;
    }
    _messageController.value = TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: text.length),
    );
    _modeState(_activeConversationMode).draftMessage = text;
    _handleSlashCommandInput();
    if (!_inputFocusNode.hasFocus) {
      _requestComposerFocus(showKeyboard: true);
    }
  }

  bool _isMessageListInputFocusedForMode(ChatPageMode mode) {
    return _modeState(mode).messageListInputFocused;
  }

  void _handleMessageListInputFocusChanged(ChatPageMode mode, bool hasFocus) {
    if (!mounted) {
      return;
    }
    if (_isMessageListInputFocusedForMode(mode) == hasFocus) {
      return;
    }
    if (hasFocus) {
      _armComposerLiftIntent();
    }
    setState(() {
      _modeState(mode).messageListInputFocused = hasFocus;
    });
  }

  double _resolveNormalSurfaceComposerInset({
    required double inputBottomPadding,
    required double keyboardSpacer,
  }) {
    if (!_isInputAreaVisible) {
      return 0.0;
    }
    final measuredComposerHeight = _inputAreaHeight > 0.5
        ? _inputAreaHeight + _kChatInputWrapperTopPadding
        : _kChatInputFallbackHeight;
    return measuredComposerHeight +
        inputBottomPadding +
        keyboardSpacer +
        _kChatMessageBottomSafeSpacing;
  }

  double _resolveHdPadPaneCollapseThreshold({
    required double availableWidth,
    required double minPaneWidth,
  }) {
    final ratioThreshold = availableWidth * _kHdPadPaneCollapseWidthRatio;
    final minWidthThreshold = minPaneWidth * _kHdPadPaneCollapseMinWidthFactor;
    return math
        .min(minPaneWidth - 1, math.max(ratioThreshold, minWidthThreshold))
        .toDouble();
  }

  void _scheduleSlashCommandPanelInsetSync(bool visible) {
    final mode = _activeMode;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      double nextHeight = 0;
      if (visible) {
        final context = _openClawPanelKey.currentContext;
        final renderBox = context?.findRenderObject() as RenderBox?;
        if (renderBox != null && renderBox.hasSize) {
          nextHeight = renderBox.size.height;
        }
      }
      final currentHeight = _modeState(mode).slashCommandPanelOccupiedHeight;
      if ((currentHeight - nextHeight).abs() < 0.5) {
        return;
      }
      setState(() {
        _modeState(mode).slashCommandPanelOccupiedHeight = nextHeight;
      });
    });
  }

  void _scheduleSlashCommandOccupiedHeightSync(double height) {
    final normalized = height.isFinite ? height : 0.0;
    if ((_slashCommandPanelOccupiedHeight - normalized).abs() < 0.5) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted ||
          (_slashCommandPanelOccupiedHeight - normalized).abs() < 0.5) {
        return;
      }
      setState(() {
        _modeState(_activeMode).slashCommandPanelOccupiedHeight = normalized;
      });
    });
  }

  List<Map<String, dynamic>> _buildSlashCommandCards() {
    final route = _resolveSlashCommandPanelRoute(_messageController.text);
    if (_activeMode == ChatPageMode.agent) {
      return _buildAgentSlashCommandCards(route);
    }
    if (route == _SlashCommandPanelRoute.effort &&
        _supportsReasoningEffortCommand) {
      return <Map<String, dynamic>>[_buildReasoningEffortCommandCard()];
    }

    final commands = <Map<String, dynamic>>[
      <String, dynamic>{
        'cardId': 'slash-command-record',
        'toolName': '/record',
        'toolTitle': '/record',
        'displayName': '/record',
        'toolType': 'command',
        'toolTypeLabel': LegacyTextLocalizer.isEnglish ? 'Recording' : '录制',
        'status': 'running',
        'statusLabel': LegacyTextLocalizer.isEnglish ? 'Action' : '操作',
        'summary': LegacyTextLocalizer.isEnglish
            ? 'Manually record a reusable operation flow'
            : '手动录制可复用的操作流程',
        'progress': LegacyTextLocalizer.isEnglish
            ? 'Start recording from the floating control'
            : '通过悬浮控件开始录制',
      },
    ];
    if (_supportsManualContextCompaction) {
      commands.add(<String, dynamic>{
        'cardId': 'slash-command-compact',
        'toolName': '/compact',
        'toolTitle': '/compact',
        'displayName': '/compact',
        'toolType': 'command',
        'toolTypeLabel': LegacyTextLocalizer.isEnglish ? 'Context' : '上下文',
        'status': 'running',
        'statusLabel': LegacyTextLocalizer.isEnglish ? 'Command' : '命令',
        'summary': LegacyTextLocalizer.isEnglish
            ? 'Manually compress conversation context'
            : '手动压缩当前对话上下文',
        'progress': LegacyTextLocalizer.isEnglish
            ? 'Compress current session history into a replacement summary'
            : '把当前会话历史压缩成 replacement summary',
      });
    }
    if (_supportsReasoningEffortCommand) {
      commands.add(_buildReasoningEffortCommandCard());
    }
    if (_isOpenClawSurface) {
      commands.add(<String, dynamic>{
        'cardId': 'slash-command-openclaw',
        'toolName': '/openclaw',
        'toolTitle': '/openclaw',
        'displayName': '/openclaw',
        'toolType': 'command',
        'toolTypeLabel': LegacyTextLocalizer.isEnglish ? 'Gateway' : '网关',
        'status': 'running',
        'statusLabel': LegacyTextLocalizer.isEnglish ? 'Command' : '命令',
        'summary': LegacyTextLocalizer.isEnglish
            ? 'Manually configure a remote or custom OpenClaw gateway'
            : '手动配置远端或自定义 OpenClaw 网关',
        'progress': LegacyTextLocalizer.isEnglish
            ? 'Enter Base URL, Token, and User ID'
            : '填写 Base URL、Token 与 User ID',
      });
    }
    return commands;
  }

  Map<String, dynamic> _buildReasoningEffortCommandCard() {
    final activeEffort = _activeConversationReasoningEffort;
    final hasSelectedEffort = activeEffort != null;
    return <String, dynamic>{
      'cardId': 'slash-command-effort',
      'toolName': '/effort',
      'toolTitle': '/effort',
      'displayName': '/effort',
      'toolType': 'command',
      'toolTypeLabel': LegacyTextLocalizer.isEnglish ? 'Thinking' : '思考',
      'status': hasSelectedEffort ? 'success' : 'running',
      'statusLabel':
          activeEffort ?? (LegacyTextLocalizer.isEnglish ? 'Default' : '默认'),
      'summary': '',
      'progress': '',
      'controlType': 'effortSlider',
      'effortOptions': _kAgentReasoningEffortOptions,
      if (activeEffort != null) 'selectedEffort': activeEffort,
    };
  }

  List<Map<String, dynamic>> _buildAgentSlashCommandCards(
    _SlashCommandPanelRoute route,
  ) {
    if (route == _SlashCommandPanelRoute.agentModel) {
      return _buildAgentModelCards();
    }
    return _buildAgentRootCommandCards();
  }

  List<Map<String, dynamic>> _buildAgentRootCommandCards() {
    final query = _messageController.text.trimLeft().toLowerCase();
    final planModeEnabled = _isAgentPlanMode(_activeAgentCollaborationMode);
    final commands = <Map<String, dynamic>>[
      _buildAgentCommandCard(
        cardId: 'slash-command-agent-model',
        toolTitle: '/model',
        displayName: '/model',
        toolTypeLabel: LegacyTextLocalizer.isEnglish ? 'Model' : '模型',
        status: _activeAgentModelId == null ? 'running' : 'success',
        statusLabel: _activeAgentModelId == null
            ? (LegacyTextLocalizer.isEnglish ? 'Select' : '选择')
            : (_activeAgentModelId!),
        summary: _activeAgentModelId == null
            ? (LegacyTextLocalizer.isEnglish
                  ? 'Choose a model for $_activeAcpAgentDisplayName'
                  : '选择 $_activeAcpAgentDisplayName 的模型')
            : (LegacyTextLocalizer.isEnglish
                  ? 'Current model: $_activeAgentModelId'
                  : '当前模型：$_activeAgentModelId'),
        progress: _agentModelListError != null
            ? _agentModelListError!
            : _isAgentModelListLoading
            ? (LegacyTextLocalizer.isEnglish ? 'Loading models' : '加载模型中')
            : (_agentModelOptions.isEmpty
                  ? (LegacyTextLocalizer.isEnglish
                        ? 'Tap to load models'
                        : '点击加载模型')
                  : (_agentModelOptions.length == 1
                        ? '1 model'
                        : '${_agentModelOptions.length} models')),
      ),
      _buildAgentCommandCard(
        cardId: 'slash-command-agent-review',
        toolTitle: '/review',
        displayName: '/review',
        toolTypeLabel: LegacyTextLocalizer.isEnglish ? 'Review' : '审查',
        status: 'running',
        statusLabel: LegacyTextLocalizer.isEnglish ? 'Command' : '命令',
        summary: LegacyTextLocalizer.isEnglish
            ? 'Review changes in the current workspace'
            : '审查当前工作区改动',
        progress: LegacyTextLocalizer.isEnglish
            ? 'Runs an Agent review on the active thread'
            : '在当前线程中启动 Agent review',
      ),
      _buildAgentCommandCard(
        cardId: 'slash-command-agent-init',
        toolTitle: '/init',
        displayName: '/init',
        toolTypeLabel: LegacyTextLocalizer.isEnglish ? 'Init' : '初始化',
        status: 'running',
        statusLabel: LegacyTextLocalizer.isEnglish ? 'Command' : '命令',
        summary: LegacyTextLocalizer.isEnglish
            ? 'Generate or update AGENTS.md'
            : '生成或更新 AGENTS.md',
        progress: LegacyTextLocalizer.isEnglish
            ? 'Creates $_activeAcpAgentDisplayName initialization guidance'
            : '生成 $_activeAcpAgentDisplayName 初始化指引',
      ),
      _buildAgentCommandCard(
        cardId: 'slash-command-agent-plan',
        toolTitle: '/plan',
        displayName: '/plan',
        toolTypeLabel: LegacyTextLocalizer.isEnglish ? 'Plan' : '计划',
        status: planModeEnabled ? 'success' : 'running',
        statusLabel: planModeEnabled
            ? (LegacyTextLocalizer.isEnglish ? 'Selected' : '已选')
            : (LegacyTextLocalizer.isEnglish ? 'Off' : '关闭'),
        summary: planModeEnabled
            ? (LegacyTextLocalizer.isEnglish
                  ? 'Plan mode is active'
                  : '当前已启用 Plan 模式')
            : (LegacyTextLocalizer.isEnglish
                  ? 'Plan mode is off'
                  : '当前未启用 Plan 模式'),
        progress: _agentCollaborationModeListError != null
            ? _agentCollaborationModeListError!
            : _isAgentCollaborationModeListLoading
            ? (LegacyTextLocalizer.isEnglish ? 'Loading modes' : '加载模式中')
            : (_agentCollaborationModes.isEmpty
                  ? (LegacyTextLocalizer.isEnglish
                        ? 'Tap to load modes'
                        : '点击加载模式')
                  : (_agentCollaborationModes.length == 1
                        ? '1 mode'
                        : '${_agentCollaborationModes.length} modes')),
        isToggle: true,
        toggleValue: planModeEnabled,
      ),
    ];
    if (query.isEmpty) {
      return commands;
    }
    return commands
        .where((card) {
          final title = (card['toolTitle'] ?? '').toString().toLowerCase();
          return title.startsWith(query);
        })
        .toList(growable: false);
  }

  List<Map<String, dynamic>> _buildAgentModelCards() {
    if (_agentModelOptions.isEmpty &&
        !_isAgentModelListLoading &&
        _agentModelListError == null) {
      unawaited(_loadAgentModelOptionsWhenReady());
    }
    final query = _slashCommandRouteQuery(
      _SlashCommandPanelRoute.agentModel,
    ).toLowerCase();
    final availableModels = _agentModelOptions.isEmpty
        ? <String>[]
        : _agentModelOptions;
    final filteredModels = availableModels
        .where(
          (modelId) => query.isEmpty || modelId.toLowerCase().contains(query),
        )
        .toList(growable: false);
    final selectedModel = _activeAgentModelId;
    final orderedModels = <String>[
      if (selectedModel != null && filteredModels.contains(selectedModel))
        selectedModel,
      ...filteredModels.where((modelId) => modelId != selectedModel),
    ];
    if (orderedModels.isEmpty) {
      final statusLabel = _agentModelListError != null
          ? (LegacyTextLocalizer.isEnglish ? 'Error' : '错误')
          : _isAgentModelListLoading
          ? (LegacyTextLocalizer.isEnglish ? 'Loading' : '加载中')
          : (LegacyTextLocalizer.isEnglish ? 'No models' : '暂无模型');
      return <Map<String, dynamic>>[
        _buildAgentCommandCard(
          cardId: 'slash-command-agent-model-placeholder',
          toolTitle: '/model',
          displayName: '/model',
          toolTypeLabel: LegacyTextLocalizer.isEnglish ? 'Model' : '模型',
          status: _agentModelListError != null ? 'failed' : 'running',
          statusLabel: statusLabel,
          summary:
              _agentModelListError ??
              (_isAgentModelListLoading
                  ? (LegacyTextLocalizer.isEnglish
                        ? 'Loading available models'
                        : '正在加载可用模型')
                  : (LegacyTextLocalizer.isEnglish
                        ? 'Open /model to load $_activeAcpAgentDisplayName models'
                        : '输入 /model 加载 $_activeAcpAgentDisplayName 模型')),
          progress: query.isEmpty ? '/model' : query,
        ),
      ];
    }
    return orderedModels
        .map(
          (modelId) => _buildAgentCommandCard(
            cardId: 'slash-command-agent-model-$modelId',
            toolTitle: modelId,
            displayName: modelId,
            toolTypeLabel: LegacyTextLocalizer.isEnglish ? 'Model' : '模型',
            status: modelId == selectedModel ? 'success' : 'running',
            statusLabel: modelId == selectedModel
                ? (LegacyTextLocalizer.isEnglish ? 'Selected' : '已选')
                : (LegacyTextLocalizer.isEnglish ? 'Available' : '可选'),
            summary: modelId == selectedModel
                ? (LegacyTextLocalizer.isEnglish ? 'Current model' : '当前模型')
                : (LegacyTextLocalizer.isEnglish
                      ? 'Switch to $modelId'
                      : '切换到 $modelId'),
            progress: modelId,
          ),
        )
        .toList(growable: false);
  }

  Map<String, dynamic> _buildAgentCommandCard({
    required String cardId,
    required String toolTitle,
    required String displayName,
    required String toolTypeLabel,
    required String status,
    required String statusLabel,
    required String summary,
    required String progress,
    bool isToggle = false,
    bool toggleValue = false,
  }) {
    return <String, dynamic>{
      'cardId': cardId,
      'toolName': toolTitle,
      'toolTitle': toolTitle,
      'displayName': displayName,
      'toolType': 'command',
      'toolTypeLabel': toolTypeLabel,
      'status': status,
      'statusLabel': statusLabel,
      'summary': summary,
      'progress': progress,
      if (isToggle) ...<String, dynamic>{
        'isToggle': true,
        'toggleValue': toggleValue,
      },
    };
  }

  void _handleSlashCommandCardSelected(Map<String, dynamic> cardData) {
    if (_activeMode == ChatPageMode.agent) {
      unawaited(_handleAgentSlashCommandCardSelected(cardData));
      return;
    }
    final command = (cardData['toolTitle'] ?? cardData['displayName'] ?? '')
        .toString()
        .trim();
    switch (command) {
      case '/record':
        _messageController.clear();
        _hideSlashCommandPanel();
        unawaited(_startManualRecordingCommand('/record'));
        break;
      case '/compact':
        unawaited(_executeManualContextCompactionCommand());
        break;
      case '/effort':
        _requestComposerFocus();
        break;
      case 'no':
      case 'low':
      case 'high':
      case 'xhigh':
      case 'max':
        unawaited(_applyConversationReasoningEffort(command));
        break;
      case '/openclaw':
        _showOpenClawCommandPanel(expand: true);
        break;
      default:
        break;
    }
  }

  Widget _buildSlashCommandDrawerSurface({
    required Widget child,
    bool bodyHasOwnPadding = false,
  }) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final surfaceColor = isDark
        ? palette.surfacePrimary
        : const Color(0xFFF9FCFF);
    final handleColor = isDark
        ? palette.borderStrong.withValues(alpha: 0.9)
        : const Color(0x334E627D);
    return Material(
      color: Colors.transparent,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: surfaceColor,
          borderRadius: const BorderRadius.vertical(
            top: Radius.circular(_kSlashCommandDrawerRadius),
          ),
          border: isDark
              ? Border.all(color: palette.borderSubtle.withValues(alpha: 0.72))
              : Border.all(color: const Color(0x120F2034)),
          boxShadow: [
            BoxShadow(
              color: isDark
                  ? palette.shadowColor.withValues(alpha: 0.34)
                  : const Color(0x18111B2D),
              blurRadius: isDark ? 20 : 16,
              offset: const Offset(0, -2),
            ),
          ],
        ),
        child: SafeArea(
          top: false,
          bottom: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 8, bottom: 6),
                child: Container(
                  width: _kSlashCommandDrawerHandleWidth,
                  height: _kSlashCommandDrawerHandleHeight,
                  decoration: BoxDecoration(
                    color: handleColor,
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
              ),
              bodyHasOwnPadding
                  ? child
                  : Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: child,
                    ),
            ],
          ),
        ),
      ),
    );
  }

  ChatPaneOverlayAnchorGeometry _resolveToolActivityAnchorGeometry({
    required BuildContext layoutContext,
    required BoxConstraints constraints,
    required double inputBottomPadding,
    required double keyboardSpacer,
    required double inputAreaHeight,
  }) {
    final normalizedInputHeight = inputAreaHeight.isFinite
        ? inputAreaHeight
        : 0.0;
    final derivedWidth = math.max(0.0, constraints.maxWidth - 48);
    if (_isSurfacePageScrolling &&
        _lastStableToolActivityAnchorGeometry != null) {
      return _lastStableToolActivityAnchorGeometry!;
    }

    if (_isInputAreaVisible && normalizedInputHeight > 0.5) {
      final geometry = resolveChatPaneOverlayAnchorGeometry(
        viewportSize: constraints.biggest,
        bottomSpacing:
            inputBottomPadding + keyboardSpacer + normalizedInputHeight,
        anchorHeight: normalizedInputHeight,
      );
      _lastStableToolActivityAnchorGeometry = geometry;
      return geometry;
    }

    final liveGeometry = _resolveToolActivityAnchorGeometryFromInputArea(
      layoutContext: layoutContext,
      constraints: constraints,
      derivedWidth: derivedWidth,
    );
    if (liveGeometry != null) {
      _lastStableToolActivityAnchorGeometry = liveGeometry;
      return liveGeometry;
    }

    final fallbackGeometry = resolveChatPaneOverlayAnchorGeometry(
      viewportSize: constraints.biggest,
      bottomSpacing: inputBottomPadding + keyboardSpacer + 84,
      anchorHeight: 0,
    );
    if (!_isInputAreaVisible) {
      return fallbackGeometry;
    }
    final inputContext = _chatInputAreaKey.currentContext;
    final inputBox = inputContext?.findRenderObject();
    final stackBox = layoutContext.findRenderObject();
    if (inputBox is! RenderBox ||
        stackBox is! RenderBox ||
        !inputBox.hasSize ||
        !stackBox.hasSize) {
      return fallbackGeometry;
    }
    final inputOffset = inputBox.localToGlobal(Offset.zero, ancestor: stackBox);
    final rect = inputOffset & inputBox.size;
    final geometry = ChatPaneOverlayAnchorGeometry(
      rect: rect,
      bottom: (constraints.maxHeight - rect.top)
          .clamp(0.0, constraints.maxHeight)
          .toDouble(),
    );
    _lastStableToolActivityAnchorGeometry = geometry;
    return geometry;
  }

  ChatPaneOverlayAnchorGeometry?
  _resolveToolActivityAnchorGeometryFromInputArea({
    required BuildContext layoutContext,
    required BoxConstraints constraints,
    required double derivedWidth,
  }) {
    if (!_isInputAreaVisible) {
      return null;
    }
    final inputContext = _chatInputAreaKey.currentContext;
    final inputBox = inputContext?.findRenderObject();
    final stackBox = layoutContext.findRenderObject();
    if (inputBox is! RenderBox ||
        stackBox is! RenderBox ||
        !inputBox.hasSize ||
        !stackBox.hasSize) {
      return null;
    }
    final inputOffset = inputBox.localToGlobal(Offset.zero, ancestor: stackBox);
    final top = inputOffset.dy.clamp(0.0, constraints.maxHeight).toDouble();
    return ChatPaneOverlayAnchorGeometry(
      rect: Rect.fromLTWH(24, top, derivedWidth, inputBox.size.height),
      bottom: (constraints.maxHeight - top)
          .clamp(0.0, constraints.maxHeight)
          .toDouble(),
    );
  }

  void _scheduleToolActivityInsetSync(double height) {
    final normalized = height.isFinite ? height : 0.0;
    if ((_toolActivityOccupiedHeight - normalized).abs() < 0.5) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || (_toolActivityOccupiedHeight - normalized).abs() < 0.5) {
        return;
      }
      setState(() {
        _modeState(_activeMode).toolActivityOccupiedHeight = normalized;
      });
    });
  }

  void _setToolActivityExpanded(bool expanded) {
    if (_isToolActivityExpanded == expanded) {
      return;
    }
    if (!mounted) {
      _modeState(_activeMode).toolActivityExpanded = expanded;
      return;
    }
    setState(() {
      _modeState(_activeMode).toolActivityExpanded = expanded;
    });
  }

  bool _shouldShowToolActivityStripForMode({
    required ChatPageMode mode,
    required AgentToolActivitySnapshot snapshot,
  }) {
    if (mode != _activeMode ||
        !_isInputAreaVisible ||
        _showSlashCommandPanel ||
        _openClawPanelExpanded) {
      return false;
    }
    return shouldShowAgentToolActivitySnapshot(
      snapshot,
      expandedTaskIds: _expandedAgentRunTaskIdsForMode(mode),
    );
  }

  void _handleInputAreaHeightChanged(double height) {
    final normalized = height.isFinite ? height : 0.0;
    if ((_inputAreaHeight - normalized).abs() < 0.5) {
      return;
    }
    if (!mounted) {
      _modeState(_activeMode).inputAreaHeight = normalized;
      return;
    }
    setState(() {
      _modeState(_activeMode).inputAreaHeight = normalized;
    });
  }

  Widget _buildNormalSurfaceTransition({
    required double viewportWidth,
    required Widget child,
  }) {
    return AnimatedBuilder(
      animation: _modePageController,
      child: child,
      builder: (context, child) {
        final visibility = _normalSurfaceVisibility;
        if (child == null || visibility <= 0.001) {
          return const SizedBox.shrink();
        }
        final horizontalOffset = -_surfacePageProgress * viewportWidth;
        return IgnorePointer(
          ignoring: visibility < 0.999,
          child: Opacity(
            opacity: Curves.easeOutCubic.transform(visibility),
            child: Transform.translate(
              offset: Offset(horizontalOffset, 0),
              // 翻页拖拽期间 opacity/offset 每帧变化；RepaintBoundary 让子树
              // 图层只录制一次，逐帧仅更新透明度与位移，不重绘内容。
              child: RepaintBoundary(child: child),
            ),
          ),
        );
      },
    );
  }

  @override
  Widget _buildSlashCommandPanel() {
    final palette = context.omniPalette;
    final visible = _showModelMentionPanel || _openClawPanelExpanded;
    _scheduleSlashCommandPanelInsetSync(visible);
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 180),
      transitionBuilder: (child, animation) {
        final slide = Tween<Offset>(
          begin: const Offset(0, 0.15),
          end: Offset.zero,
        ).animate(animation);
        return ClipRect(
          child: SlideTransition(
            position: slide,
            child: FadeTransition(opacity: animation, child: child),
          ),
        );
      },
      child: !visible
          ? const SizedBox.shrink()
          : KeyedSubtree(
              key: _openClawPanelKey,
              child: _showModelMentionPanel
                  ? Container(
                      decoration: BoxDecoration(
                        color: context.isDarkTheme
                            ? palette.surfacePrimary
                            : Colors.white,
                        borderRadius: BorderRadius.circular(16),
                        border: context.isDarkTheme
                            ? Border.all(color: palette.borderSubtle)
                            : null,
                        boxShadow: [
                          BoxShadow(
                            color:
                                (context.isDarkTheme
                                        ? palette.shadowColor
                                        : Colors.black)
                                    .withValues(
                                      alpha: context.isDarkTheme ? 0.22 : 0.08,
                                    ),
                            blurRadius: context.isDarkTheme ? 18 : 14,
                            offset: Offset(0, context.isDarkTheme ? 8 : 6),
                          ),
                        ],
                      ),
                      child: _buildModelMentionPanel(),
                    )
                  : _buildSlashCommandDrawerSurface(
                      bodyHasOwnPadding: _openClawPanelExpanded,
                      child: _openClawPanelExpanded
                          ? Padding(
                              padding: const EdgeInsets.fromLTRB(14, 2, 14, 14),
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    LegacyTextLocalizer.isEnglish
                                        ? 'OpenClaw Configuration'
                                        : 'OpenClaw 配置',
                                    style: TextStyle(
                                      fontSize: 13,
                                      fontWeight: FontWeight.w600,
                                      color: palette.textPrimary,
                                    ),
                                  ),
                                  const SizedBox(height: 8),
                                  TextField(
                                    controller: _openClawBaseUrlController,
                                    decoration: const InputDecoration(
                                      labelText: 'Base URL',
                                      hintText: 'http://192.168.1.10:18789',
                                      isDense: true,
                                    ),
                                  ),
                                  const SizedBox(height: 6),
                                  TextField(
                                    controller: _openClawTokenController,
                                    decoration: InputDecoration(
                                      labelText: LegacyTextLocalizer.isEnglish
                                          ? 'Token (optional)'
                                          : 'Token（可选）',
                                      hintText: LegacyTextLocalizer.isEnglish
                                          ? 'Leave empty if no token needed'
                                          : '为空表示无需 token',
                                      isDense: true,
                                    ),
                                  ),
                                  const SizedBox(height: 6),
                                  TextField(
                                    controller: _openClawUserIdController,
                                    decoration: InputDecoration(
                                      labelText: LegacyTextLocalizer.isEnglish
                                          ? 'User ID (optional)'
                                          : 'User ID（可选）',
                                      isDense: true,
                                    ),
                                  ),
                                ],
                              ),
                            )
                          : const SizedBox.shrink(),
                    ),
            ),
    );
  }

  @override
  Widget _buildModeMessagePage(
    ChatPageMode mode,
    AppBackgroundConfig appearanceConfig,
    AppBackgroundVisualProfile visualProfile, {
    double bottomOverlayInset = 0,
  }) {
    final runtime = _runtimeForMode(mode);
    final resolvedMessages = resolveVisibleChatMessages(
      runtimeMessages: runtime?.messages,
      fallbackMessages: _modeState(mode).messages,
      preserveFallbackDuringHandoff: _modeState(mode).isAiResponding,
    );
    // `runtime.activeAgentTaskIds` is the single source of truth for which
    // turns are in flight. Only fall back to the page-level dispatch id when
    // there is no runtime yet to own it.
    final activeAgentTaskIds = <String>{...?runtime?.activeAgentTaskIds};
    if (runtime == null && mode == ChatPageMode.agent) {
      final isAwaitingAgent = _modeState(mode).isAiResponding;
      final pendingDispatchTaskId =
          _modeState(mode).currentDispatchTaskId?.trim() ?? '';
      if (isAwaitingAgent && pendingDispatchTaskId.isNotEmpty) {
        activeAgentTaskIds.add(pendingDispatchTaskId);
      }
    }
    final toolActivitySnapshot = resolveAgentToolActivitySnapshot(
      List<ChatMessageModel>.from(resolvedMessages),
      activeTaskIds: activeAgentTaskIds,
      preferredCompletedTaskId: _latestExpandedAgentRunTaskIdForMode(mode),
    );
    final showToolActivityStrip = _shouldShowToolActivityStripForMode(
      mode: mode,
      snapshot: toolActivitySnapshot,
    );
    final bottomInset = MediaQuery.maybeOf(context)?.viewInsets.bottom ?? 0.0;
    final internalInputKeyboardInset =
        mode == _activeMode && _isMessageListInputFocusedForMode(mode)
        ? bottomInset + _kChatMessageBottomSafeSpacing
        : 0.0;
    final reservedBottomOverlayInset = math.max(
      bottomOverlayInset,
      internalInputKeyboardInset,
    );
    final liftEmptyGreeting =
        mode == _activeMode &&
        _emptyGreetingKeyboardLiftTracker.resolveForBuild(bottomInset);
    final homeGreetingSettings = HomeGreetingSettingsService.notifier.value;
    return ChatMessageList(
      messages: resolvedMessages,
      activeAgentTaskIds: activeAgentTaskIds,
      useAcpPresentation: mode == ChatPageMode.agent,
      activeAcpAgentId: mode == ChatPageMode.agent ? _activeAcpAgentId : null,
      onRetryAgentMessage: _retryFailedAgentTurn,
      onContinueAgentMessage: _continueFailedAgentTurn,
      expandedAgentRunTaskIds: _expandedAgentRunTaskIdsForMode(mode),
      onExpandedAgentRunTaskIdsChanged: (taskIds) {
        _updateExpandedAgentRunTaskIds(mode, taskIds);
      },
      showEmptyGreeting: homeGreetingSettings.greetingEnabled,
      liftEmptyGreeting: liftEmptyGreeting,
      emptyGreetingQuickPrompts: homeGreetingSettings.quickPrompts,
      emptyGreetingPinnedQuickPromptIds:
          homeGreetingSettings.pinnedQuickPromptIds,
      onQuickPromptSelected: _applyHomeQuickPrompt,
      emptyGreetingAgentWorkspaceName: mode == ChatPageMode.agent
          ? _remoteCodexWorkspaceNameForGreeting()
          : null,
      onEmptyGreetingAgentWorkspaceTap: mode == ChatPageMode.agent
          ? () => unawaited(_openRemoteCodexWorkspacePicker())
          : null,
      scrollController: _scrollControllerForMode(mode),
      navigator: _modeState(mode).messageListNavigator,
      bottomOverlayInset:
          reservedBottomOverlayInset +
          (mode == _activeMode ? _slashCommandPanelOccupiedHeight : 0) +
          (showToolActivityStrip ? _toolActivityOccupiedHeight : 0),
      onInternalInputFocusChanged: (hasFocus) {
        _handleMessageListInputFocusChanged(mode, hasFocus);
      },
      onBeforeTaskExecute: handleBeforeTaskExecute,
      onCancelTask: _onCancelTaskFromCard,
      onRequestAuthorize: mode == ChatPageMode.normal
          ? _requestAuthorizeForExecution
          : null,
      onUserMessageLongPressStart: switch (mode) {
        ChatPageMode.normal => _handleUserMessageLongPressStart,
        ChatPageMode.agent =>
          (message, details) => _handleUserMessageLongPressStart(
            message,
            details,
            allowConversationActions: false,
          ),
        ChatPageMode.openclaw => null,
      },
      onLatestUserMessageEditTap: mode == ChatPageMode.normal
          ? _startEditingLatestUserMessage
          : null,
      onLoadMore: loadMoreMessages,
      hasMore: hasMoreMessages,
      visualProfile: visualProfile,
      appearanceConfig: appearanceConfig,
    );
  }

  @override
  Widget _buildWorkspaceSurfacePage() {
    if (_shouldUseRemoteCodexWorkspace()) {
      return _buildRemoteCodexWorkspaceBrowser(
        translucentSurfaces: AppBackgroundService.current.isActive,
        enableSystemBackHandler: true,
      );
    }
    final workspacePathsFuture = _workspacePathsLoadFuture ??=
        OmnibotResourceService.ensureWorkspacePathsLoaded();
    return FutureBuilder<OmnibotWorkspacePaths>(
      future: workspacePathsFuture,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator.adaptive());
        }
        final paths =
            snapshot.data ??
            const OmnibotWorkspacePaths(
              rootPath: '/data/user/0/cn.com.omnimind.bot/workspace',
              shellRootPath: '/workspace',
              internalRootPath:
                  '/data/user/0/cn.com.omnimind.bot/workspace/.omnibot',
            );
        return OmnibotWorkspaceBrowser(
          workspacePath: paths.rootPath,
          workspaceShellPath: paths.shellRootPath,
          translucentSurfaces: AppBackgroundService.current.isActive,
          showBreadcrumbHeader: true,
          showHeaderTitle: false,
          onCanGoUpChanged: (canGoUp) {
            if (_workspaceBrowserCanGoUp == canGoUp || !mounted) return;
            setState(() {
              _workspaceBrowserCanGoUp = canGoUp;
            });
          },
        );
      },
    );
  }

  bool _shouldUseRemoteCodexWorkspace() {
    if (_activeMode != ChatPageMode.agent) {
      return false;
    }
    final runtime = _agentRuntimeStatus.runtime?.trim();
    return runtime == 'remote' || _agentRuntimeStatus.remoteEnabled;
  }

  Widget _buildRemoteCodexWorkspaceBrowser({
    Key? key,
    required bool translucentSurfaces,
    required bool enableSystemBackHandler,
  }) {
    final workspacePath =
        (_agentRuntimeStatus.remoteCwd ?? _agentRuntimeStatus.cwd ?? '').trim();
    final bridgeUrl = (_agentRuntimeStatus.remoteBridgeUrl ?? '').trim();
    if (workspacePath.isEmpty) {
      return _buildRemoteCodexWorkspaceUnavailable();
    }
    return CodexRemoteWorkspaceBrowser(
      key: key,
      workspacePath: workspacePath,
      remoteBridgeUrl: bridgeUrl,
      enableSystemBackHandler: enableSystemBackHandler,
      translucentSurfaces: translucentSurfaces,
      showBreadcrumbHeader: true,
      showHeaderTitle: false,
      onCanGoUpChanged: (canGoUp) {
        if (_workspaceBrowserCanGoUp == canGoUp || !mounted) return;
        setState(() {
          _workspaceBrowserCanGoUp = canGoUp;
        });
      },
    );
  }

  Widget _buildRemoteCodexWorkspaceUnavailable() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_workspaceBrowserCanGoUp) return;
      setState(() {
        _workspaceBrowserCanGoUp = false;
      });
    });
    final palette = context.omniPalette;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.folder_off_outlined,
              size: 34,
              color: palette.textSecondary,
            ),
            const SizedBox(height: 12),
            Text(
              LegacyTextLocalizer.isEnglish
                  ? 'Remote Agent workspace is not configured'
                  : '远程 Agent 工作目录尚未配置',
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 14,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              LegacyTextLocalizer.isEnglish
                  ? 'Open Agent settings and set a remote cwd, or scan the PC Bridge QR code.'
                  : '请在 Agent 配置中设置远程工作目录，或扫描 PC Bridge 二维码。',
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 12,
                height: 1.4,
              ),
            ),
            const SizedBox(height: 14),
            TextButton.icon(
              onPressed: () =>
                  GoRouterManager.push('/home/remote_codex_setting'),
              icon: const Icon(Icons.settings_outlined, size: 18),
              label: Text(LegacyTextLocalizer.localize('Agent 配置')),
            ),
          ],
        ),
      ),
    );
  }

  ChatIslandDisplayLayer _resolveChatPaneDisplayLayer() {
    return _activeSurfaceMode == ChatSurfaceMode.normal
        ? _chatIslandDisplayLayer
        : ChatIslandDisplayLayer.mode;
  }

  Widget _buildPaneSurface({
    required Widget child,
    required bool translucent,
    required AppBackgroundVisualProfile visualProfile,
    bool showBorder = true,
    bool showShadow = true,
  }) {
    final palette = context.omniPalette;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: backgroundSurfaceColor(
          translucent: translucent,
          baseColor: palette.surfacePrimary,
          opacity: translucent ? 0.72 : 1,
        ),
        borderRadius: BorderRadius.circular(24),
        border: showBorder
            ? Border.all(
                color: translucent
                    ? visualProfile.islandBorderColor
                    : const Color(0xFFD9E6FB),
              )
            : null,
        boxShadow: showShadow
            ? const [
                BoxShadow(
                  color: Color(0x121A2433),
                  blurRadius: 28,
                  offset: Offset(0, 12),
                ),
              ]
            : null,
      ),
      child: ClipRRect(borderRadius: BorderRadius.circular(24), child: child),
    );
  }

  Widget _buildChatPaneShell({
    required BuildContext layoutContext,
    required BoxConstraints constraints,
    required AppBackgroundConfig backgroundConfig,
    required AppBackgroundVisualProfile visualProfile,
    required bool backgroundActive,
    required double inputBottomPadding,
    required double keyboardSpacer,
    required double commandPanelBottomOffset,
    required Widget conversationBody,
    required bool hideWorkspaceOverlays,
    required bool showMenuButton,
    required bool showSurfaceSwitcher,
    required VoidCallback onMenuTap,
    VoidCallback? onWorkspacePaneTap,
    bool showWorkspacePaneButton = false,
  }) {
    final toolActivitySnapshot = resolveAgentToolActivitySnapshot(
      List<ChatMessageModel>.from(_messages),
      activeTaskIds: _activeRuntime?.activeAgentTaskIds ?? const <String>{},
      preferredCompletedTaskId: _latestExpandedAgentRunTaskIdForMode(
        _activeMode,
      ),
    );
    final toolActivityMessages = toolActivitySnapshot.messages;
    final toolActivityCards = extractAgentToolCards(toolActivityMessages);
    final isPinnedCompletedToolActivity =
        toolActivityCards.isNotEmpty && !toolActivitySnapshot.isActiveRun;
    final slashCommandCards =
        _showSlashCommandPanel &&
            !_showModelMentionPanel &&
            !_openClawPanelExpanded
        ? _buildSlashCommandCards()
        : const <Map<String, dynamic>>[];
    final showSlashCommandStrip =
        _isInputAreaVisible && slashCommandCards.isNotEmpty;
    final showToolActivityStrip = _shouldShowToolActivityStripForMode(
      mode: _activeMode,
      snapshot: toolActivitySnapshot,
    );
    final toolActivityCanExpand = toolActivityCards.length > 1;
    // The activity strip sits flush above the composer, so its downward drop
    // shadow reads as part of the input surface instead of as separate chrome.
    final suppressToolActivitySurfaceShadow =
        showToolActivityStrip || showSlashCommandStrip;
    final overlayAnchor = (toolActivityCards.isEmpty && !showSlashCommandStrip)
        ? null
        : _resolveToolActivityAnchorGeometry(
            layoutContext: layoutContext,
            constraints: constraints,
            inputBottomPadding: inputBottomPadding,
            keyboardSpacer: keyboardSpacer,
            inputAreaHeight: _inputAreaHeight,
          );
    if ((!showToolActivityStrip || toolActivityCards.isEmpty) &&
        _toolActivityOccupiedHeight > 0) {
      _scheduleToolActivityInsetSync(0);
    }
    if (!showSlashCommandStrip &&
        !_showModelMentionPanel &&
        !_openClawPanelExpanded &&
        _slashCommandPanelOccupiedHeight > 0) {
      _scheduleSlashCommandOccupiedHeightSync(0);
    }
    if ((!toolActivityCanExpand || !showToolActivityStrip) &&
        _isToolActivityExpanded) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _setToolActivityExpanded(false);
      });
    }
    if (!showSlashCommandStrip && _isSlashCommandExpanded) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _setSlashCommandExpanded(false);
      });
    }
    final showAppUpdateIndicator =
        !hideWorkspaceOverlays &&
        AppUpdateService.shouldShowBanner(_appUpdateStatus);
    final appUpdateTooltip = _appUpdateStatus == null
        ? (LegacyTextLocalizer.isEnglish ? 'New version available' : '发现新版本')
        : (LegacyTextLocalizer.isEnglish
              ? 'New version ${_appUpdateStatus!.latestVersionLabel} available'
              : '发现新版本 ${_appUpdateStatus!.latestVersionLabel}');
    final appBarMode = showSurfaceSwitcher
        ? _activeSurfaceMode
        : ChatSurfaceMode.normal;
    final bottomRegionBackgroundColor = !backgroundActive && context.isDarkTheme
        ? context.omniPalette.pageBackground
        : Colors.transparent;
    final composerBottomOffset = inputBottomPadding + keyboardSpacer;
    final composerReservedInset = _resolveNormalSurfaceComposerInset(
      inputBottomPadding: inputBottomPadding,
      keyboardSpacer: keyboardSpacer,
    );
    return Stack(
      clipBehavior: Clip.hardEdge,
      children: [
        Column(
          children: [
            ChatAppBar(
              onMenuTap: onMenuTap,
              onPetTap: () {
                unawaited(_handlePetOverlayTap());
              },
              isPetOpening: _isPetOverlayOpening,
              isPetShowing: _isPetOverlayShowing,
              onOmniAiTap: () {
                unawaited(_handleAgentModeShortcutTap());
              },
              onPureChatToggleTap: () {
                unawaited(_handlePureChatModeShortcutTap());
              },
              onAgentTap: () {
                unawaited(_handleAgentTap());
              },
              onAcpAgentTap: (agentId) {
                unawaited(_handleAcpAgentModeShortcutTap(agentId));
              },
              onPrimaryModeTap: _activeMode == ChatPageMode.agent
                  ? () => GoRouterManager.push('/home/agent_sessions')
                  : null,
              activeMode: appBarMode,
              onModeChanged: (value) {
                unawaited(_switchChatMode(value, syncPage: true));
              },
              displayLayer: _resolveChatPaneDisplayLayer(),
              onDisplayLayerChanged: _handleChatIslandDisplayLayerChanged,
              onTerminalEnvironmentTap: (anchorContext) {
                unawaited(_openTerminalEnvironmentEditor(anchorContext));
              },
              onTerminalTap: _handleTerminalToolTap,
              onBrowserTap: _handleBrowserToolTap,
              hasTerminalEnvironment: _terminalEnvironmentVariables.isNotEmpty,
              isBrowserEnabled: _isBrowserSessionAvailable,
              activeToolType: _lastAgentToolType,
              isAgentReady: _agentRuntimeStatus.ready,
              isAgentConnected: _agentRuntimeStatus.connected,
              isAgentLoading:
                  _isAgentRuntimeStatusLoading || _isAcpAgentSwitching,
              isAgentSelected: _activeMode == ChatPageMode.agent,
              isOmniAiSelected:
                  _activeMode == ChatPageMode.normal && !_isPureChatSelected,
              acpAgentModes: _chatAcpAgentModeOptions,
              activeAcpAgentId: _activeAcpAgentId,
              showAppUpdateIndicator: showAppUpdateIndicator,
              appUpdateTooltip: appUpdateTooltip,
              onAppUpdateTap: showAppUpdateIndicator
                  ? () {
                      unawaited(_handleAppUpdateBannerTap());
                    }
                  : null,
              translucent: backgroundActive,
              visualProfile: visualProfile,
              showMenuButton: showMenuButton,
              showSurfaceSwitcher: showSurfaceSwitcher,
              showPureChatToggle:
                  _activeMode == ChatPageMode.normal ||
                  _activeMode == ChatPageMode.agent,
              isPureChatSelected: _isPureChatSelected,
              isPureChatToggleLocked: _isPureChatToggleLocked,
              showWorkspacePaneButton: showWorkspacePaneButton,
              onWorkspacePaneTap: onWorkspacePaneTap,
              tutorialMenuAnchorKey: _firstUseTourMenuAnchorKey,
              tutorialPetAnchorKey: _firstUseTourPetAnchorKey,
              tutorialIslandAnchorKey: _firstUseTourIslandAnchorKey,
              tutorialModeAnchorKey: _firstUseTourModeAnchorKey,
            ),
            Expanded(child: conversationBody),
          ],
        ),
        if (_isInputAreaVisible)
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: _buildNormalSurfaceTransition(
              viewportWidth: constraints.maxWidth,
              child: ColoredBox(
                color: bottomRegionBackgroundColor,
                child: Padding(
                  padding: EdgeInsets.only(bottom: composerBottomOffset),
                  child: Container(
                    key: _inputAreaKey,
                    child: ChatInputWrapper(
                      inputAreaKey: _chatInputAreaKey,
                      controller: _messageController,
                      focusNode: _inputFocusNode,
                      onRequestFocus: _armComposerLiftIntent,
                      isProcessing:
                          _isAiResponding && _editingUserMessageId == null,
                      onSendMessage: _handleComposerSendMessage,
                      onCancelTask: _onCancelTask,
                      onPopupVisibilityChanged: _onPopupVisibilityChanged,
                      onTerminalTap: _handleTerminalToolTap,
                      useLargeComposerStyle: true,
                      useAttachmentPickerForPlus: true,
                      onPickAttachment: _pickAttachments,
                      onTriggerSlashCommand: _triggerSlashCommandPanel,
                      attachments: _pendingAttachments,
                      hasExternalSendPayload: _editingUserMessageHasAttachments,
                      isEditingUserMessage: _editingUserMessageId != null,
                      onCancelUserMessageEditing: _stopUserMessageEditing,
                      onRemoveAttachment: _removePendingAttachment,
                      selectedModelOverrideId:
                          _activeMode == ChatPageMode.normal &&
                              _showConversationModelMentionChip
                          ? _activeConversationModelOverrideSelection?.modelId
                          : null,
                      contextUsageRatio: _activeMode == ChatPageMode.normal
                          ? _currentConversation?.contextUsageRatio
                          : null,
                      contextUsageTooltipMessage:
                          _activeMode == ChatPageMode.normal
                          ? _buildContextUsageTooltipMessage()
                          : null,
                      onLongPressContextUsageRing:
                          _activeMode == ChatPageMode.normal
                          ? _handleContextUsageRingLongPress
                          : null,
                      modelPickerSettings: _activeMode == ChatPageMode.normal
                          ? ChatModelPickerSettings(
                              modelId: _activeNormalChatModelId ?? '',
                              hasSelectableModels:
                                  _hasSelectableNormalChatModels,
                              anchorKey: _firstUseTourModelAnchorKey,
                              onPointerDown: () {
                                _suppressNextOutsideTapKeyboardHide = true;
                              },
                              onOpen: (anchorContext) =>
                                  _openConversationModelSelector(anchorContext),
                            )
                          : null,
                      agentRunSettings: _activeMode == ChatPageMode.agent
                          ? AgentRunSettings(
                              agentName:
                                  _agentRuntimeStatus.activeAgentName ??
                                  _agentCatalog?.selectedAgent?.name ??
                                  '',
                              modelId: _activeAgentModelId ?? '',
                              reasoningEffort:
                                  _activeAgentReasoningEffort ?? '',
                              modelOptions: _agentModelOptions,
                              reasoningEffortOptions:
                                  _agentReasoningEffortOptions,
                              isLoadingModels: _isAgentModelListLoading,
                              modelListError: _agentModelListError,
                            )
                          : null,
                      onAgentRunSettingsOpened:
                          _activeMode == ChatPageMode.agent
                          ? _loadAgentModelOptionsWhenReady
                          : null,
                      onAgentRunSettingsChanged:
                          _activeMode == ChatPageMode.agent
                          ? ({String? modelId, String? reasoningEffort}) {
                              if (modelId != null) {
                                unawaited(
                                  _selectAgentModel(
                                    modelId,
                                    clearComposer: false,
                                  ),
                                );
                              }
                              if (reasoningEffort != null) {
                                unawaited(
                                  _selectAgentReasoningEffort(reasoningEffort),
                                );
                              }
                            }
                          : null,
                      agentPermissionMode: _activeMode == ChatPageMode.agent
                          ? _agentPermissionMode
                          : null,
                      agentPermissionModes:
                          _activeMode == ChatPageMode.agent &&
                              _agentRuntimeStatus.runtime != 'remote' &&
                              !_agentRuntimeStatus.remoteEnabled
                          ? const <AgentPermissionMode>[
                              AgentPermissionMode.readOnly,
                              AgentPermissionMode.defaultMode,
                              AgentPermissionMode.fullAccess,
                            ]
                          : AgentPermissionMode.values,
                      onAgentPermissionModeChanged:
                          _activeMode == ChatPageMode.agent
                          ? (mode) {
                              setState(() {
                                _agentPermissionMode = mode;
                              });
                            }
                          : null,
                      onInputHeightChanged: _handleInputAreaHeightChanged,
                      onClearSelectedModelOverride:
                          _activeMode == ChatPageMode.normal &&
                              _activeConversationModelOverrideSelection != null
                          ? () {
                              unawaited(_clearConversationModelOverride());
                            }
                          : null,
                      translucent: backgroundActive,
                    ),
                  ),
                ),
              ),
            ),
          ),
        if (showToolActivityStrip)
          Positioned(
            left: overlayAnchor?.rect.left ?? 24,
            width:
                overlayAnchor?.rect.width ??
                math.max(0.0, constraints.maxWidth - 48),
            bottom: overlayAnchor?.bottom ?? 0,
            child: _buildNormalSurfaceTransition(
              viewportWidth: constraints.maxWidth,
              child: KeyedSubtree(
                key: _toolActivityStripKey,
                child: ChatToolActivityStrip(
                  messages: toolActivityMessages,
                  showPreviewThumbnail: toolActivitySnapshot.isActiveRun,
                  openActiveCardOnTap: isPinnedCompletedToolActivity,
                  anchorRect: overlayAnchor?.rect,
                  onOccupiedHeightChanged: _scheduleToolActivityInsetSync,
                  expanded: _isToolActivityExpanded,
                  onExpandedChanged: _setToolActivityExpanded,
                  suppressSurfaceShadow: suppressToolActivitySurfaceShadow,
                  onStopToolCall: _handleToolActivityStopRequested,
                ),
              ),
            ),
          ),
        if (showSlashCommandStrip)
          Positioned(
            left: overlayAnchor?.rect.left ?? 24,
            width:
                overlayAnchor?.rect.width ??
                math.max(0.0, constraints.maxWidth - 48),
            bottom: overlayAnchor?.bottom ?? 0,
            child: _buildNormalSurfaceTransition(
              viewportWidth: constraints.maxWidth,
              child: Container(
                key: _slashCommandStripKey,
                child: KeyedSubtree(
                  key: ValueKey<String>(
                    'slash-command-${_resolveSlashCommandPanelRoute(_messageController.text).name}',
                  ),
                  child: ChatCommandActivityStrip(
                    commands: slashCommandCards,
                    anchorRect: overlayAnchor?.rect,
                    onOccupiedHeightChanged:
                        _scheduleSlashCommandOccupiedHeightSync,
                    suppressSurfaceShadow: suppressToolActivitySurfaceShadow,
                    onSelectCommand: _handleSlashCommandCardSelected,
                  ),
                ),
              ),
            ),
          ),
        if (_showModelMentionPanel || _openClawPanelExpanded)
          Positioned(
            left: 24,
            right: 24,
            bottom: commandPanelBottomOffset,
            child: _buildNormalSurfaceTransition(
              viewportWidth: constraints.maxWidth,
              child: _buildSlashCommandPanel(),
            ),
          ),
        if (_isPopupVisible)
          Positioned(
            right: 24,
            bottom: _popupMenuBottomOffset(),
            child: _buildNormalSurfaceTransition(
              viewportWidth: constraints.maxWidth,
              child:
                  _chatInputAreaKey.currentState?.buildPopupMenu() ??
                  const SizedBox.shrink(),
            ),
          ),
        // Keep auxiliary browser content below the anchor spotlight so an
        // expanded fan dims every non-anchor surface consistently.
        _buildBrowserOverlay(constraints),
        Positioned.fill(
          child: _buildNormalSurfaceTransition(
            viewportWidth: constraints.maxWidth,
            child: _buildMessageAnchorBarOverlay(
              composerReservedInset: composerReservedInset,
              showToolActivityStrip: showToolActivityStrip,
            ),
          ),
        ),
      ],
    );
  }

  /// 消息锚点导航：悬浮在输入框右上角的 gallery-vertical-end 按钮 +
  /// 向左展开的锚点条。始终跟随 composer 顶部（含其上方条带）定位。
  Widget _buildMessageAnchorBarOverlay({
    required double composerReservedInset,
    required bool showToolActivityStrip,
  }) {
    final mode = _primaryChatMessagePageMode;
    final runtime = _runtimeForMode(mode);
    final messages = resolveVisibleChatMessages(
      runtimeMessages: runtime?.messages,
      fallbackMessages: _modeState(mode).messages,
      preserveFallbackDuringHandoff: _modeState(mode).isAiResponding,
    );
    final activeTaskIds = runtime?.activeAgentTaskIds ?? const <String>{};
    // composerReservedInset 已含 composer 顶部上方 12px 的安全间距，
    // 回收 6px 让按钮更贴近输入框右上角。
    final bottomInset = math.max(
      0.0,
      composerReservedInset -
          _kChatMessageBottomSafeSpacing +
          6 +
          _slashCommandPanelOccupiedHeight +
          (showToolActivityStrip ? _toolActivityOccupiedHeight : 0),
    );
    final visible =
        _isInputAreaVisible &&
        !_showModelMentionPanel &&
        !_openClawPanelExpanded &&
        !_isPopupVisible;
    return ChatMessageAnchorBar(
      messages: messages,
      activeAgentTaskIds: activeTaskIds,
      conversationAgentId: mode == ChatPageMode.agent
          ? _activeAcpAgentId
          : null,
      conversationSignature:
          '${mode.name}:${_modeState(mode).currentConversationId ?? ''}',
      bottomInset: bottomInset,
      visible: visible,
      onJumpToEntry: (entryKey) =>
          _modeState(mode).messageListNavigator.animateToEntry(entryKey),
      onExpandedChanged: (expanded) {
        if (!mounted || _messageAnchorExpanded == expanded) {
          return;
        }
        setState(() => _messageAnchorExpanded = expanded);
      },
    );
  }

  Widget _buildHdPadWorkspacePane({
    required bool backgroundActive,
    required AppBackgroundVisualProfile visualProfile,
  }) {
    if (_shouldUseRemoteCodexWorkspace()) {
      return _buildRemoteCodexWorkspaceBrowser(
        key: _hdPadRemoteWorkspaceBrowserKey,
        translucentSurfaces: backgroundActive,
        enableSystemBackHandler: false,
      );
    }
    final workspacePathsFuture = _workspacePathsLoadFuture ??=
        OmnibotResourceService.ensureWorkspacePathsLoaded();
    return FutureBuilder<OmnibotWorkspacePaths>(
      future: workspacePathsFuture,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator.adaptive());
        }
        final paths =
            snapshot.data ??
            const OmnibotWorkspacePaths(
              rootPath: '/data/user/0/cn.com.omnimind.bot/workspace',
              shellRootPath: '/workspace',
              internalRootPath:
                  '/data/user/0/cn.com.omnimind.bot/workspace/.omnibot',
            );
        return OmnibotWorkspaceBrowser(
          key: _hdPadWorkspaceBrowserKey,
          workspacePath: paths.rootPath,
          workspaceShellPath: paths.shellRootPath,
          enableSystemBackHandler: false,
          translucentSurfaces: backgroundActive,
          showBreadcrumbHeader: true,
          showHeaderTitle: false,
          enableInlineDirectoryExpansion: false,
          inlineFilePreview: true,
          onCanGoUpChanged: (canGoUp) {
            if (_workspaceBrowserCanGoUp == canGoUp || !mounted) return;
            setState(() {
              _workspaceBrowserCanGoUp = canGoUp;
            });
          },
        );
      },
    );
  }

  Widget _buildHdPadLandscapeShell({
    required AppBackgroundConfig backgroundConfig,
    required AppBackgroundVisualProfile visualProfile,
    required bool backgroundActive,
    required double inputBottomPadding,
    required double keyboardSpacer,
    required double commandPanelBottomOffset,
  }) {
    const shellPadding = EdgeInsets.fromLTRB(8, 10, 8, 10);
    return LayoutBuilder(
      builder: (context, constraints) {
        final availableWidth = math
            .max(0, constraints.maxWidth - shellPadding.horizontal)
            .toDouble();
        final expandedLayout = _hdPadPaneLayoutResolver.resolve(
          availableWidth,
          preferredLeftWidth: _hdPadLeftPaneWidth,
          preferredRightWidth: _hdPadRightPaneWidth,
        );
        final layout = _hdPadPaneLayoutResolver.resolve(
          availableWidth,
          preferredLeftWidth: _hdPadLeftPaneWidth,
          preferredRightWidth: _hdPadRightPaneWidth,
          collapseLeftPane: _hdPadLeftPaneCollapsed,
          collapseRightPane: _hdPadRightPaneCollapsed,
        );
        final leftCollapseThreshold = _resolveHdPadPaneCollapseThreshold(
          availableWidth: availableWidth,
          minPaneWidth: HdPadPaneLayoutResolver.minLeftWidth,
        );
        final rightCollapseThreshold = _resolveHdPadPaneCollapseThreshold(
          availableWidth: availableWidth,
          minPaneWidth: HdPadPaneLayoutResolver.minRightWidth,
        );
        final paneDuration = _isHdPadPaneDragging
            ? Duration.zero
            : const Duration(milliseconds: 280);
        const paneCurve = Curves.easeInOutCubic;
        return Padding(
          padding: shellPadding,
          child: Row(
            children: [
              AnimatedContainer(
                duration: paneDuration,
                curve: paneCurve,
                width: layout.leftWidth,
                child: ClipRect(
                  child: OverflowBox(
                    alignment: Alignment.centerLeft,
                    minWidth: expandedLayout.leftWidth,
                    maxWidth: expandedLayout.leftWidth,
                    child: SizedBox(
                      width: expandedLayout.leftWidth,
                      child: IgnorePointer(
                        ignoring: _hdPadLeftPaneCollapsed,
                        child: AnimatedSlide(
                          duration: const Duration(milliseconds: 280),
                          curve: Curves.easeInOutCubic,
                          offset: _hdPadLeftPaneCollapsed
                              ? const Offset(-0.08, 0)
                              : Offset.zero,
                          child: _buildPaneSurface(
                            translucent: backgroundActive,
                            visualProfile: visualProfile,
                            child: HomeDrawer(
                              key: _drawerKey,
                              embedded: true,
                              closeOnNavigate: false,
                              onSearchFocusChanged:
                                  _handleHomeDrawerSearchFocusChanged,
                              searchFieldKey: _drawerSearchFieldKey,
                              newConversationMode: _conversationModeForPageMode(
                                _activeMode,
                              ),
                              onThreadTargetSelected:
                                  _handleEmbeddedDrawerThreadTargetSelected,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              AnimatedContainer(
                duration: paneDuration,
                curve: paneCurve,
                width: _hdPadLeftPaneCollapsed
                    ? 0
                    : HdPadPaneLayoutResolver.dividerHitWidth,
                child: _hdPadLeftPaneCollapsed
                    ? const SizedBox.shrink()
                    : _PaneResizeHandle(
                        onDragStart: () {
                          setState(() {
                            _isHdPadPaneDragging = true;
                            _hdPadPaneDragStartWidth = layout.leftWidth;
                            _hdPadPaneDragDelta = 0;
                          });
                        },
                        onDragUpdate: (delta) {
                          _hdPadPaneDragDelta += delta;
                          final nextWidth =
                              (_hdPadPaneDragStartWidth ?? layout.leftWidth) +
                              _hdPadPaneDragDelta;
                          final shouldCollapse =
                              nextWidth <= leftCollapseThreshold;
                          setState(() {
                            if (shouldCollapse) {
                              _hdPadLeftPaneCollapsed = true;
                              _resetHdPadPaneDragState();
                            } else {
                              _hdPadLeftPaneCollapsed = false;
                              _hdPadLeftPaneWidth = nextWidth;
                            }
                          });
                          if (shouldCollapse) {
                            _persistHdPadPanePreferences();
                          }
                        },
                        onDragEnd: () {
                          setState(_resetHdPadPaneDragState);
                          _persistHdPadPanePreferences();
                        },
                      ),
              ),
              AnimatedContainer(
                duration: paneDuration,
                curve: paneCurve,
                width: layout.centerWidth,
                child: _buildPaneSurface(
                  translucent: backgroundActive,
                  visualProfile: visualProfile,
                  child: Listener(
                    behavior: HitTestBehavior.translucent,
                    onPointerDown: _handlePagePointerDown,
                    onPointerMove: _handlePagePointerMove,
                    onPointerUp: _handlePagePointerUp,
                    onPointerCancel: _handlePagePointerCancel,
                    child: LayoutBuilder(
                      builder: (context, paneConstraints) {
                        return _buildChatPaneShell(
                          layoutContext: context,
                          constraints: paneConstraints,
                          backgroundConfig: backgroundConfig,
                          visualProfile: visualProfile,
                          backgroundActive: backgroundActive,
                          inputBottomPadding: inputBottomPadding,
                          keyboardSpacer: keyboardSpacer,
                          commandPanelBottomOffset: commandPanelBottomOffset,
                          conversationBody: _buildModeMessagePage(
                            _primaryChatMessagePageMode,
                            backgroundConfig,
                            visualProfile,
                            bottomOverlayInset:
                                _resolveNormalSurfaceComposerInset(
                                  inputBottomPadding: inputBottomPadding,
                                  keyboardSpacer: keyboardSpacer,
                                ),
                          ),
                          hideWorkspaceOverlays: false,
                          showMenuButton: true,
                          showSurfaceSwitcher: false,
                          onMenuTap: _toggleHdPadLeftPaneCollapsed,
                          showWorkspacePaneButton: _hdPadRightPaneCollapsed,
                          onWorkspacePaneTap: _toggleHdPadRightPaneCollapsed,
                        );
                      },
                    ),
                  ),
                ),
              ),
              AnimatedContainer(
                duration: paneDuration,
                curve: paneCurve,
                width: _hdPadRightPaneCollapsed
                    ? 0
                    : HdPadPaneLayoutResolver.dividerHitWidth,
                child: _hdPadRightPaneCollapsed
                    ? const SizedBox.shrink()
                    : _PaneResizeHandle(
                        onDragStart: () {
                          setState(() {
                            _isHdPadPaneDragging = true;
                            _hdPadPaneDragStartWidth = layout.rightWidth;
                            _hdPadPaneDragDelta = 0;
                          });
                        },
                        onDragUpdate: (delta) {
                          _hdPadPaneDragDelta += delta;
                          final nextWidth =
                              (_hdPadPaneDragStartWidth ?? layout.rightWidth) -
                              _hdPadPaneDragDelta;
                          final shouldCollapse =
                              nextWidth <= rightCollapseThreshold;
                          setState(() {
                            if (shouldCollapse) {
                              _hdPadRightPaneCollapsed = true;
                              _resetHdPadPaneDragState();
                            } else {
                              _hdPadRightPaneCollapsed = false;
                              _hdPadRightPaneWidth = nextWidth;
                            }
                          });
                          if (shouldCollapse) {
                            _persistHdPadPanePreferences();
                          }
                        },
                        onDragEnd: () {
                          setState(_resetHdPadPaneDragState);
                          _persistHdPadPanePreferences();
                        },
                      ),
              ),
              AnimatedContainer(
                duration: paneDuration,
                curve: paneCurve,
                width: layout.rightWidth,
                child: ClipRect(
                  child: OverflowBox(
                    alignment: Alignment.centerRight,
                    minWidth: expandedLayout.rightWidth,
                    maxWidth: expandedLayout.rightWidth,
                    child: SizedBox(
                      width: expandedLayout.rightWidth,
                      child: IgnorePointer(
                        ignoring: _hdPadRightPaneCollapsed,
                        child: AnimatedSlide(
                          duration: const Duration(milliseconds: 280),
                          curve: Curves.easeInOutCubic,
                          offset: _hdPadRightPaneCollapsed
                              ? const Offset(0.08, 0)
                              : Offset.zero,
                          child: _buildPaneSurface(
                            translucent: backgroundActive,
                            visualProfile: visualProfile,
                            showBorder: false,
                            showShadow: false,
                            child: _buildHdPadWorkspacePane(
                              backgroundActive: backgroundActive,
                              visualProfile: visualProfile,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final isHdPadLandscape = _isHdPadLandscapeForMediaQuery(mediaQuery);
    final bottomInset = mediaQuery.viewInsets.bottom;
    final viewPaddingBottom = mediaQuery.viewPadding.bottom;
    final activeMessageListInputFocused = _isMessageListInputFocusedForMode(
      _activeMode,
    );
    final shouldLiftComposerForKeyboard = _isHomeDrawerSearchFocused
        ? false
        : _composerLiftIntentTracker.update(
            hasInputIntent:
                _inputFocusNode.hasFocus ||
                _editingUserMessageId != null ||
                activeMessageListInputFocused,
            bottomInset: bottomInset,
          );
    final composerKeyboardMetrics = _composerKeyboardMetricsTracker.update(
      shouldLiftComposerForKeyboard: shouldLiftComposerForKeyboard,
      bottomInset: bottomInset,
      viewPaddingBottom: viewPaddingBottom,
      safeAreaBottomPadding: mediaQuery.padding.bottom,
    );
    final inputBottomPadding = composerKeyboardMetrics.inputBottomPadding;
    final keyboardSpacer = composerKeyboardMetrics.keyboardSpacer;
    final commandPanelBottomOffset =
        (_popupMenuBottomOffset() + inputBottomPadding + keyboardSpacer + 6)
            .toDouble();

    final chatPage = ValueListenableBuilder<AppBackgroundConfig>(
      valueListenable: AppBackgroundService.notifier,
      builder: (context, backgroundConfig, _) {
        final backgroundActive = backgroundConfig.isActive;
        return ValueListenableBuilder<AppBackgroundVisualProfile>(
          valueListenable: AppBackgroundService.visualProfileNotifier,
          builder: (context, visualProfile, _) {
            final handlesBackLocally =
                _isFirstUseTourActive ||
                _conversationModelSelectorHandle != null ||
                (isHdPadLandscape &&
                    !_hdPadRightPaneCollapsed &&
                    _workspaceBrowserCanGoUp);
            return PopScope(
              // Predictive back requires the framework to declare whether it can
              // pop before the gesture starts. At the root, let Android own the
              // gesture so it can animate the app window back to the launcher.
              canPop: !handlesBackLocally,
              onPopInvokedWithResult: (didPop, _) {
                if (didPop) return;
                if (_isFirstUseTourActive) {
                  _handleFirstUseTourBack();
                  return;
                }
                // 模型选择器是 OverlayEntry，不在 Navigator 栈里，由页面先关闭。
                if (_conversationModelSelectorHandle != null) {
                  unawaited(_conversationModelSelectorHandle?.dismiss());
                  return;
                }
                if (isHdPadLandscape &&
                    !_hdPadRightPaneCollapsed &&
                    _workspaceBrowserCanGoUp) {
                  if (_shouldUseRemoteCodexWorkspace()) {
                    _hdPadRemoteWorkspaceBrowserKey.currentState
                        ?.openParentDirectory();
                  } else {
                    _hdPadWorkspaceBrowserKey.currentState
                        ?.openParentDirectory();
                  }
                  return;
                }
                if (_isWorkspaceSurface && _workspaceBrowserCanGoUp) {
                  return;
                }
              },
              child: Scaffold(
                key: _scaffoldKey,
                backgroundColor: Colors.transparent,
                resizeToAvoidBottomInset: false,
                // 锚点面板展开时关闭抽屉的边缘侧滑，避免轻滑误触发 home_drawer。
                drawerEnableOpenDragGesture: !_messageAnchorExpanded,
                drawer: isHdPadLandscape
                    ? null
                    : HomeDrawer(
                        key: _drawerKey,
                        newConversationMode: _conversationModeForPageMode(
                          _activeMode,
                        ),
                        onSearchFocusChanged:
                            _handleHomeDrawerSearchFocusChanged,
                        searchFieldKey: _drawerSearchFieldKey,
                      ),
                onDrawerChanged: isHdPadLandscape
                    ? null
                    : _handleHomeDrawerChanged,
                body: Stack(
                  fit: StackFit.expand,
                  children: [
                    Positioned.fill(
                      child: backgroundActive
                          ? AppBackgroundLayer(
                              config: backgroundConfig,
                              fallbackColor:
                                  context.omniPalette.previewFallback,
                              layerKey: const ValueKey('chat-page-background'),
                            )
                          : ColoredBox(
                              color: context.omniPalette.pageBackground,
                            ),
                    ),
                    SafeArea(
                      child: ClipRect(
                        child: Listener(
                          behavior: HitTestBehavior.translucent,
                          onPointerDown: (event) {
                            unawaited(_handleOutsideTap(event.position));
                            if (!isHdPadLandscape) {
                              _handlePagePointerDown(event);
                            }
                          },
                          onPointerMove: isHdPadLandscape
                              ? null
                              : _handlePagePointerMove,
                          onPointerUp: isHdPadLandscape
                              ? null
                              : _handlePagePointerUp,
                          onPointerCancel: isHdPadLandscape
                              ? null
                              : _handlePagePointerCancel,
                          child: isHdPadLandscape
                              ? _buildHdPadLandscapeShell(
                                  backgroundConfig: backgroundConfig,
                                  visualProfile: visualProfile,
                                  backgroundActive: backgroundActive,
                                  inputBottomPadding: inputBottomPadding,
                                  keyboardSpacer: keyboardSpacer,
                                  commandPanelBottomOffset:
                                      commandPanelBottomOffset,
                                )
                              : LayoutBuilder(
                                  builder: (context, constraints) {
                                    return _buildChatPaneShell(
                                      layoutContext: context,
                                      constraints: constraints,
                                      backgroundConfig: backgroundConfig,
                                      visualProfile: visualProfile,
                                      backgroundActive: backgroundActive,
                                      inputBottomPadding: inputBottomPadding,
                                      keyboardSpacer: keyboardSpacer,
                                      commandPanelBottomOffset:
                                          commandPanelBottomOffset,
                                      conversationBody: ClipRect(
                                        child: NotificationListener<ScrollNotification>(
                                          onNotification:
                                              _handleModePageScrollNotification,
                                          child: PageView(
                                            controller: _modePageController,
                                            onPageChanged:
                                                _handleModePageChanged,
                                            children: [
                                              _buildModeMessagePage(
                                                _primaryChatMessagePageMode,
                                                backgroundConfig,
                                                visualProfile,
                                                bottomOverlayInset:
                                                    _resolveNormalSurfaceComposerInset(
                                                      inputBottomPadding:
                                                          inputBottomPadding,
                                                      keyboardSpacer:
                                                          keyboardSpacer,
                                                    ),
                                              ),
                                              _buildWorkspaceSurfacePage(),
                                            ],
                                          ),
                                        ),
                                      ),
                                      hideWorkspaceOverlays:
                                          _isWorkspaceSurface,
                                      showMenuButton: true,
                                      showSurfaceSwitcher: true,
                                      onMenuTap: () {
                                        _dismissChatInputFocus();
                                        _scaffoldKey.currentState?.openDrawer();
                                      },
                                    );
                                  },
                                ),
                        ),
                      ),
                    ),
                    ChatMessageAnchorSystemBarsScrim(
                      expanded: _messageAnchorExpanded,
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
    if (!_isFirstUseTourActive) {
      return chatPage;
    }
    return Stack(
      fit: StackFit.expand,
      children: [
        chatPage,
        ChatSpotlightTour(
          step: _firstUseTourStep,
          anchorKey: _firstUseTourAnchorKey,
          onNext: _showNextFirstUseTourStep,
          onFinish: _finishFirstUseTour,
        ),
      ],
    );
  }

  String? _buildContextUsageTooltipMessage() {
    final conversation = _currentConversation;
    if (conversation == null) {
      return null;
    }
    if (conversation.promptTokenThreshold <= 0) {
      return LegacyTextLocalizer.isEnglish
          ? 'No context threshold set for this conversation'
          : '当前对话还没有可用的上下文阈值';
    }
    if (conversation.latestPromptTokensUpdatedAt <= 0 &&
        conversation.latestPromptTokens <= 0) {
      return LegacyTextLocalizer.isEnglish
          ? 'No context token statistics yet\nLong press to adjust threshold'
          : '当前对话还没有上下文 token 统计\n长按可调整阈值';
    }

    final usedTokens = conversation.latestPromptTokens;
    final thresholdTokens = conversation.promptTokenThreshold;
    return '${_formatTokenCount(usedTokens)} / '
        '${_formatTokenCount(thresholdTokens)} tokens'
        '\n${LegacyTextLocalizer.isEnglish ? 'Long press to adjust threshold' : '长按可调整阈值'}';
  }

  Future<void> _handleContextUsageRingLongPress() async {
    final conversation = _currentConversation;
    if (conversation == null || conversation.id <= 0) {
      _showSnackBar(
        LegacyTextLocalizer.isEnglish
            ? 'No adjustable context threshold for this conversation'
            : '当前对话还没有可调整的上下文阈值',
      );
      return;
    }

    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useRootNavigator: false,
      backgroundColor: Colors.transparent,
      builder: (_) => _ContextThresholdSheet(
        initialThreshold: conversation.promptTokenThreshold,
        currentUsageTokens: conversation.latestPromptTokens,
        onThresholdSaved: (nextThreshold) async {
          final trackedConversation = _modeState(
            ChatPageMode.normal,
          ).currentConversation;
          final activeConversation = _currentConversation;
          final ConversationModel latestConversation;
          if (trackedConversation?.id == conversation.id) {
            latestConversation = trackedConversation!;
          } else if (activeConversation?.id == conversation.id) {
            latestConversation = activeConversation!;
          } else {
            latestConversation = conversation;
          }
          if (nextThreshold == latestConversation.promptTokenThreshold) {
            return true;
          }

          final success =
              await ConversationService.updateConversationPromptTokenThreshold(
                conversationId: conversation.id,
                promptTokenThreshold: nextThreshold,
              );
          if (!mounted || !success) {
            return success;
          }

          final modelId =
              _activeConversationModelOverrideSelection?.modelId ??
              _activeDispatchSceneSelection?.modelId;
          if (modelId != null && modelId.isNotEmpty) {
            await StorageService.setManualModelContextThreshold(
              modelId,
              nextThreshold,
            );
          }

          final updatedConversation = latestConversation.copyWith(
            promptTokenThreshold: nextThreshold,
          );
          setState(() {
            if ((_modeState(ChatPageMode.normal).currentConversation?.id ??
                    0) ==
                conversation.id) {
              _modeState(ChatPageMode.normal).currentConversation =
                  updatedConversation;
            }
            if ((_currentConversation?.id ?? 0) == conversation.id) {
              _currentConversation = updatedConversation;
            }
          });
          if ((_modeState(ChatPageMode.normal).currentConversationId ?? 0) ==
              conversation.id) {
            _syncRuntimeSnapshotForMode(
              ChatPageMode.normal,
              conversation: updatedConversation,
            );
          }
          return true;
        },
      ),
    );
  }

  Future<void> _handleUserMessageLongPressStart(
    ChatMessageModel message,
    LongPressStartDetails details, {
    bool allowConversationActions = true,
  }) async {
    final text = (message.text ?? '').trim();
    final hasAttachments = _extractRetryAttachments(message).isNotEmpty;
    if (text.isEmpty && !hasAttachments) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'No actionable text in this user message'
            : '这条用户消息没有可操作的文本',
        type: ToastType.warning,
      );
      return;
    }

    final action = await _showUserMessageQuickMenu(
      details.globalPosition,
      showEditAction: allowConversationActions && _canEditUserMessage(message),
      showRetryAction:
          allowConversationActions && _canRetryUserMessage(message),
    );
    if (!mounted || action == null) return;

    switch (action) {
      case _UserMessageQuickAction.edit:
        _startEditingLatestUserMessage(message);
        return;
      case _UserMessageQuickAction.copy:
        if (text.isEmpty) {
          showToast(
            LegacyTextLocalizer.isEnglish
                ? 'No text to copy in this user message'
                : '这条用户消息没有可复制的文本',
            type: ToastType.warning,
          );
          return;
        }
        await _copyUserMessageText(text);
        return;
      case _UserMessageQuickAction.retry:
        await _retryUserMessage(message);
        return;
    }
  }

  Future<_UserMessageQuickAction?> _showUserMessageQuickMenu(
    Offset globalPosition, {
    required bool showEditAction,
    required bool showRetryAction,
  }) {
    final anchor = glassPopupAnchorFromGlobalPosition(context, globalPosition);
    if (anchor == null) {
      return Future<_UserMessageQuickAction?>.value();
    }
    return showGlassPopup<_UserMessageQuickAction>(
      context: context,
      anchor: anchor,
      verticalGap: 10,
      instant: true,
      horizontalPlacement: GlassPopupHorizontalPlacement.centerOnAnchor,
      child: _UserMessageQuickMenuContent(
        width: 188,
        showEditAction: showEditAction,
        showRetryAction: showRetryAction,
      ),
    );
  }

  bool _canRetryUserMessage(ChatMessageModel message) {
    return _isLatestUserMessage(message);
  }

  bool _canEditUserMessage(ChatMessageModel message) {
    return _isLatestUserMessage(message);
  }

  bool _isLatestUserMessage(ChatMessageModel message) {
    if (message.user != 1) return false;
    for (final item in _messages) {
      if (item.user != 1) continue;
      return item.id == message.id;
    }
    return false;
  }

  ChatMessageModel? _currentEditingUserMessage() {
    final editingMessageId = _editingUserMessageId;
    if (editingMessageId == null) return null;
    for (final message in _messages) {
      if (message.id == editingMessageId && message.user == 1) {
        return message;
      }
    }
    return null;
  }

  bool get _editingUserMessageHasAttachments {
    final message = _currentEditingUserMessage();
    return message != null && _extractRetryAttachments(message).isNotEmpty;
  }

  Future<void> _handleComposerSendMessage({String? text}) async {
    if (_editingUserMessageId != null) {
      final message = _currentEditingUserMessage();
      if (message == null) {
        _stopUserMessageEditing();
        return;
      }
      await _saveAndResendEditedUserMessage(message);
      return;
    }
    await _sendMessage(text: text);
  }

  void _startEditingLatestUserMessage(ChatMessageModel message) {
    if (!_isLatestUserMessage(message)) {
      showToast(
        'Only the latest user message can be edited',
        type: ToastType.warning,
      );
      return;
    }
    final originalText = message.text ?? '';
    _suppressNextOutsideTapKeyboardHide = true;
    _armComposerLiftIntent();
    setState(() {
      _editingUserMessageId = message.id;
      _modeState(_activeMode).draftMessage = originalText;
      _pendingAttachments.clear();
    });
    _messageController.value = TextEditingValue(
      text: originalText,
      selection: TextSelection.collapsed(offset: originalText.length),
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _editingUserMessageId != message.id) return;
      _requestComposerFocus(showKeyboard: true);
    });
  }

  void _stopUserMessageEditing() {
    if (_editingUserMessageId == null) return;
    final mode = _activeMode;
    setState(() {
      _editingUserMessageId = null;
    });
    _modeState(mode).draftMessage = '';
    _messageController.clear();
  }

  Future<void> _saveAndResendEditedUserMessage(ChatMessageModel message) async {
    if (_editingUserMessageId != message.id) return;
    if (!_isLatestUserMessage(message)) {
      _stopUserMessageEditing();
      showToast(
        'Only the latest user message can be edited',
        type: ToastType.warning,
      );
      return;
    }

    final editedText = _messageController.text.trim();
    final attachments = _extractRetryAttachments(message);
    if (editedText.isEmpty && attachments.isEmpty) {
      showToast('No content to send after editing', type: ToastType.warning);
      return;
    }
    if (!await _ensureNormalChatModelConfigurationForSend()) {
      return;
    }

    await _clearRetriedMessageRound(message);
    if (!mounted) return;

    await _retryUserMessageText(editedText, attachments: attachments);
  }

  int _retryMessageRoundLength(
    ChatMessageModel message, {
    bool preserveUserMessage = false,
  }) {
    if (!_canRetryUserMessage(message)) return 0;
    return retriedMessageRoundRemovalCount(
      _messages,
      userMessageId: message.id,
      preserveUserMessage: preserveUserMessage,
    );
  }

  Future<void> _clearRetriedMessageRound(
    ChatMessageModel message, {
    bool preserveUserMessage = false,
  }) async {
    if (_isAiResponding) {
      _onCancelTask();
      if (!mounted) return;
    }

    final removeCount = _retryMessageRoundLength(
      message,
      preserveUserMessage: preserveUserMessage,
    );
    if (removeCount <= 0) return;

    final shouldClearEditState = _editingUserMessageId == message.id;
    setState(() {
      if (shouldClearEditState) {
        _editingUserMessageId = null;
      }
      _messages.removeRange(0, removeCount);
    });
    if (shouldClearEditState) {
      _modeState(_activeMode).draftMessage = '';
      _messageController.clear();
    }

    final conversationId = _currentConversationId;
    if (conversationId == null) return;
    if (isEphemeralConversation(conversationId, activeConversationModeValue)) {
      return;
    }

    await ConversationHistoryService.saveConversationMessages(
      conversationId,
      List<ChatMessageModel>.from(_messages),
      mode: activeConversationModeValue,
    );
  }

  Future<void> _copyUserMessageText(String text) async {
    final success = await AssistsMessageService.copyToClipboard(text);
    if (!mounted) return;
    showToast(
      success
          ? (LegacyTextLocalizer.isEnglish ? 'Message copied' : '已复制消息内容')
          : (LegacyTextLocalizer.isEnglish ? 'Copy failed' : '复制失败'),
      type: success ? ToastType.success : ToastType.error,
    );
  }

  Future<void> _retryUserMessage(ChatMessageModel message) async {
    final text = (message.text ?? '').trim();
    final attachments = _extractRetryAttachments(message);
    if (text.isEmpty && attachments.isEmpty) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'No content to retry in this user message'
            : '这条用户消息没有可重试的内容',
        type: ToastType.warning,
      );
      return;
    }
    if (!_canRetryUserMessage(message)) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Only the latest user message can be retried'
            : '只有最新一条用户消息支持重试',
        type: ToastType.warning,
      );
      return;
    }
    if (!await _ensureNormalChatModelConfigurationForSend()) {
      return;
    }

    if (text.isNotEmpty) {
      await AssistsMessageService.copyToClipboard(text);
      if (!mounted) return;
    }

    if (_editingUserMessageId == message.id) {
      _stopUserMessageEditing();
      if (!mounted) return;
    }

    await _clearRetriedMessageRound(message, preserveUserMessage: true);
    if (!mounted) return;

    await _retryUserMessageText(
      text,
      attachments: attachments,
      retainedUserMessageId: message.id,
    );
    if (!mounted) return;
  }

  Future<void> _retryFailedAgentTurn(ChatMessageModel message) async {
    final taskId = _resolveRetryableAgentTaskId(message);
    if (taskId == null) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'This reply can no longer be retried'
            : '这条回复当前无法继续重试',
        type: ToastType.warning,
      );
      return;
    }
    if (_pendingManualAgentRetryTaskIds.contains(taskId) ||
        message.content?['agentRetrying'] == true) {
      return;
    }
    if (_isAiResponding) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Wait for the current response to finish first'
            : '请先等待当前回复结束',
        type: ToastType.warning,
      );
      return;
    }

    final messageIndex = _messages.indexWhere((item) => item.id == message.id);
    final previousMessage = messageIndex == -1 ? null : _messages[messageIndex];
    _pendingManualAgentRetryTaskIds.add(taskId);
    if (previousMessage != null && mounted) {
      setState(() {
        _messages[messageIndex] = _buildPendingManualRetryMessage(
          previousMessage,
          taskId: taskId,
        );
      });
    }

    final success = await AssistsMessageService.retryAgentTask(taskId: taskId);
    _pendingManualAgentRetryTaskIds.remove(taskId);
    if (!mounted) {
      return;
    }
    if (!success) {
      if (previousMessage != null) {
        final restoreIndex = _messages.indexWhere(
          (item) => item.id == previousMessage.id,
        );
        if (restoreIndex != -1) {
          setState(() {
            _messages[restoreIndex] = previousMessage;
          });
        }
      }
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Retry failed. Please try sending the message again.'
            : '重试失败，请重新发送消息',
        type: ToastType.error,
      );
      return;
    }
  }

  Future<void> _continueFailedAgentTurn(ChatMessageModel message) async {
    final taskId = _resolveContinueableAgentTaskId(message);
    if (taskId == null) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'This reply can no longer continue from the current turn'
            : '这条回复当前无法从本轮继续',
        type: ToastType.warning,
      );
      return;
    }
    if (_pendingManualAgentContinueTaskIds.contains(taskId) ||
        message.content?['agentContinuing'] == true) {
      return;
    }
    if (_isAiResponding) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Wait for the current response to finish first'
            : '请先等待当前回复结束',
        type: ToastType.warning,
      );
      return;
    }

    final messageIndex = _messages.indexWhere((item) => item.id == message.id);
    final previousMessage = messageIndex == -1 ? null : _messages[messageIndex];
    final removedBlankThinkingCards = <ChatMessageModel>[];
    _pendingManualAgentContinueTaskIds.add(taskId);
    if (previousMessage != null && mounted) {
      setState(() {
        _messages[messageIndex] = _buildPendingManualContinueMessage(
          previousMessage,
          taskId: taskId,
        );
        // 失败 run 如果是卡在 thinking 阶段(还没出 tool 调用 / assistant 文本),
        // 会留一张空内容的 "Thought for xx s" 卡。续跑后这张卡没有任何信息价值,
        // 而且新 run 的 thinking 用了 -c$gen 后缀 id,不会原地覆盖它,
        // 所以这里在续跑前先把它从消息流里移除。
        //
        // 注意:thinking 卡的 type / thinkingContent 都在 content.cardData 嵌套层里,
        // 不是顶层 content,所以走 ChatMessageModel.cardData getter 读。
        _messages.removeWhere((item) {
          if (item.id == previousMessage.id) return false;
          if (item.type != 2) return false;
          if (agentRunParentTaskId(item) != taskId) return false;
          final cardData = item.cardData;
          if (cardData == null) return false;
          final cardType = (cardData['type'] ?? '').toString().trim();
          if (cardType != 'deep_thinking') return false;
          final thinkingContent = (cardData['thinkingContent'] ?? '')
              .toString()
              .trim();
          final shouldRemove = thinkingContent.isEmpty;
          if (shouldRemove) removedBlankThinkingCards.add(item);
          return shouldRemove;
        });
      });
    }

    final success = await AssistsMessageService.continueAgentTask(
      taskId: taskId,
    );
    _pendingManualAgentContinueTaskIds.remove(taskId);
    if (!mounted) {
      return;
    }
    if (!success) {
      if (previousMessage != null) {
        final restoreIndex = _messages.indexWhere(
          (item) => item.id == previousMessage.id,
        );
        if (restoreIndex != -1) {
          setState(() {
            _messages[restoreIndex] = previousMessage;
            // 一并恢复因乐观更新被移除的空白 thinking 卡,
            // 避免续跑请求本身失败时静默吞掉历史状态。
            for (final card in removedBlankThinkingCards) {
              if (_messages.indexWhere((item) => item.id == card.id) == -1) {
                _messages.add(card);
              }
            }
          });
        }
      }
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Continue failed. Please try again.'
            : '继续失败，请稍后再试',
        type: ToastType.error,
      );
      return;
    }
  }

  List<Map<String, dynamic>> _extractRetryAttachments(
    ChatMessageModel message,
  ) {
    final raw = message.content?['attachments'];
    if (raw is! List) return const [];
    return raw
        .whereType<Map>()
        .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
        .toList();
  }

  String? _resolveRetryableAgentTaskId(ChatMessageModel message) {
    if (message.content?['agentRetryable'] != true) {
      return null;
    }
    return _resolveAgentTaskId(message);
  }

  String? _resolveContinueableAgentTaskId(ChatMessageModel message) {
    if (message.content?['agentContinueable'] != true) {
      return null;
    }
    return _resolveAgentTaskId(message);
  }

  String? _resolveAgentTaskId(ChatMessageModel message) {
    final contentTaskId = (message.content?['agentTaskId'] ?? '')
        .toString()
        .trim();
    if (contentTaskId.isNotEmpty) {
      return contentTaskId;
    }
    final streamTaskId = (message.streamMeta?['parentTaskId'] ?? '')
        .toString()
        .trim();
    if (streamTaskId.isNotEmpty) {
      return streamTaskId;
    }
    return null;
  }

  ChatMessageModel _buildPendingManualRetryMessage(
    ChatMessageModel message, {
    required String taskId,
  }) {
    final content = Map<String, dynamic>.from(message.content ?? const {});
    content['agentTaskId'] = taskId;
    content['agentRetrying'] = true;
    content['agentContinuing'] = false;
    content['agentRetryStatusText'] = LegacyTextLocalizer.isEnglish
        ? 'Retrying connection...'
        : '连接中断，正在重试…';
    content['agentRetryCount'] = 0;
    content['agentMaxRetries'] =
        (content['agentMaxRetries'] as num?)?.toInt() ?? 3;
    content['agentRetryDelayMs'] = 0;
    content.remove('agentRetryReason');
    content.remove('agentRetryable');
    content.remove('agentContinueable');
    content.remove('agentContinueResumeMode');
    content.remove('agentContinueStatusText');
    content.remove('agentErrorText');
    return message.copyWith(content: content, isError: false);
  }

  ChatMessageModel _buildPendingManualContinueMessage(
    ChatMessageModel message, {
    required String taskId,
  }) {
    final content = Map<String, dynamic>.from(message.content ?? const {});
    // 失败时如果整条 bubble 的正文就是错误文案(无半截输出场景,
    // resolveAgentFinalErrorResolution 设了 persistAsError=true → isError=true),
    // 续跑前清掉它,避免在新流到达前残留 "Failed to connect..." 一类文字。
    // 若 isError=false,说明 text 是真实的半截输出,保留待新流首帧整体替换。
    final errorTextSnapshot = (content['agentErrorText'] ?? '')
        .toString()
        .trim();
    final bubbleText = (content['text'] ?? '').toString().trim();
    final textIsErrorOnly =
        message.isError == true ||
        (errorTextSnapshot.isNotEmpty && errorTextSnapshot == bubbleText);
    if (textIsErrorOnly) {
      content['text'] = '';
      // 解析机制是按文本里出现的 URL 同步进 content.linkPreviews 的(详见
      // chat_conversation_runtime_coordinator 的 syncLinkPreviewsForAssistantText)。
      // 文本被清空后,linkPreviews 不会自动清,会一直渲染 "xxx.com" 这张卡片。
      // 续跑前直接抹掉,新流的首帧文本会触发重新解析。
      content.remove('linkPreviews');
    }
    content['agentTaskId'] = taskId;
    content['agentRetrying'] = false;
    content['agentContinuing'] = true;
    content['agentContinueStatusText'] = LegacyTextLocalizer.isEnglish
        ? 'Continuing from current turn...'
        : '正在从当前轮继续…';
    content.remove('agentRetryStatusText');
    content.remove('agentRetryCount');
    content.remove('agentMaxRetries');
    content.remove('agentRetryDelayMs');
    content.remove('agentRetryReason');
    content.remove('agentRetryable');
    content.remove('agentContinueable');
    content.remove('agentContinueResumeMode');
    content.remove('agentErrorText');
    return message.copyWith(content: content, isError: false);
  }

  String _formatTokenCount(int value) {
    return value.toString().replaceAllMapped(
      RegExp(r'\B(?=(\d{3})+(?!\d))'),
      (_) => ',',
    );
  }
}
