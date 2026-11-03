part of 'chat_page.dart';

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
  bool get _showLocalTerminalActions =>
      !(_activeMode == ChatPageMode.normal && _isPureChatSelected) &&
      !(_activeMode == ChatPageMode.agent && _activeAcpAgentId == 'na-cloud');

  String? get _composerModelLabel {
    if (_activeMode == ChatPageMode.agent) {
      return _activeAgentModelId ??
          (_usesSharedProviderModel(_activeAcpAgentId)
              ? _activeNormalChatModelId
              : null);
    }
    return (_showConversationModelMentionChip
            ? _activeConversationModelOverrideSelection?.modelId
            : null) ??
        _activeNormalChatModelId;
  }

  Widget _buildAcpConfigButton() {
    final generation = _conversationTargetRequestId;
    final agentId = _activeAcpAgentId;
    int? targetConversationId = _currentConversationId;
    bool isCurrent() => mounted && generation == _conversationTargetRequestId;
    Future<Map<String, dynamic>> read({bool refreshConfig = false}) async {
      if (!isCurrent()) throw StateError('Conversation changed');
      final response = await _readActiveAcpSessionConfig(
        refreshConfig: refreshConfig,
      );
      if (!isCurrent()) throw StateError('Conversation changed');
      targetConversationId = _currentConversationId;
      return response;
    }

    return AcpConfigButton(
      key: _acpConfigButtonKey,
      presentationIdentity: generation,
      isRunning: _isAiResponding,
      modelLabel: _composerModelLabel,
      onVisibilityChanged: (visible) {
        _chatInputAreaKey.currentState?.setAuxiliaryPopupVisible(visible);
        _onPopupVisibilityChanged(visible);
      },
      configureModel: _usesSharedProviderModel(agentId)
          ? _openConversationModelSelector
          : null,
      load: () => read(),
      refresh: () => read(refreshConfig: true),
      write: (sessionId, configId, value) async {
        if (!isCurrent()) throw StateError('Conversation changed');
        if (_isAiResponding) throw StateError('本轮结束后可修改运行参数');
        return AgentRuntimeService.setSessionConfigOption(
          sessionId: sessionId,
          conversationId:
              _unboundConfigurationSessions.values.any(
                (session) =>
                    session.sessionId == sessionId &&
                    session.agentId == agentId,
              )
              ? null
              : targetConversationId,
          agentId: agentId,
          configId: configId,
          value: value,
        );
      },
    );
  }

  void _returnToConversationHome() {
    _dismissChatInputFocus();
    if (GoRouterManager.canPop()) {
      GoRouterManager.pop();
    } else {
      GoRouterManager.go('/home/home');
    }
  }

  List<ChatAppBarAction> _chatHeaderActions() {
    final english = LegacyTextLocalizer.isEnglish;
    return [
      if (_isInputAreaVisible) ...[
        if (_activeMode == ChatPageMode.agent &&
            _activeAcpAgentId == 'na-cloud' &&
            _agentCatalog?.agents.any(
                  (agent) =>
                      agent.id == 'na-cloud' && agent.configurationAvailable,
                ) ==
                true)
          ChatAppBarAction(
            label: english ? 'Cloud connection' : '云端连接',
            icon: LucideIcons.cloud,
            onSelected: (_) =>
                GoRouterManager.push('/home/agent_config/na-cloud'),
          ),
        ChatAppBarAction(
          label: english ? 'Commands' : '命令',
          icon: LucideIcons.command,
          onSelected: (_) => _triggerSlashCommandPanel(),
        ),
        if (_activeMode != ChatPageMode.openclaw)
          ChatAppBarAction(
            label: english ? 'Context usage & limit' : '上下文用量与阈值',
            icon: LucideIcons.chartPie,
            onSelected: (_) => this._handleContextUsageRingLongPress(),
          ),
      ],
    ];
  }

  ChatPaneOverlayAnchorGeometry? _lastStableCommandAnchorGeometry;
  static const double _kChatInputWrapperTopPadding = 8.0;
  static const double _kChatInputFallbackHeight = 80.0;
  static const double _kHdPadPaneCollapseWidthRatio = 0.12;
  static const double _kHdPadPaneCollapseMinWidthFactor = 0.72;
  bool _isHomeDrawerSearchFocused = false;

  void _handleHomeDrawerSearchFocusChanged(bool hasFocus) {
    if (!mounted || _isHomeDrawerSearchFocused == hasFocus) {
      return;
    }
    _isHomeDrawerSearchFocused = hasFocus;
    if (hasFocus && _inputFocusNode.hasFocus) {
      _inputFocusNode.unfocus();
    }
    _composerLiftIntentTracker.reset();
    // Search and the composer can exchange focus while the IME stays at the
    // same height. Update their shared geometry even without a metrics event.
    void notifyLayout() {
      if (mounted) _chatKeyboardLayoutChanges.value++;
    }

    if (SchedulerBinding.instance.schedulerPhase ==
        SchedulerPhase.persistentCallbacks) {
      // A resize may dispose the focused sidebar during tree finalization.
      WidgetsBinding.instance.addPostFrameCallback((_) => notifyLayout());
    } else {
      notifyLayout();
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
    _modeState(mode).messageListInputFocused = hasFocus;
    _chatKeyboardLayoutChanges.value++;
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
        (_showNewConversationModeSelector
            ? NewConversationModeSelector.height + 8
            : 0) +
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
        final renderBox = findActiveRenderObject(context) as RenderBox?;
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
      if (_activeAcpAgentId != 'na-cloud')
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
      if (_availableAcpCommandForText('/review') != null)
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
      if (_activeAcpAgentId != 'na-cloud')
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
              ? 'Prompt shortcut: asks the Agent to write workspace guidance'
              : '提示词快捷操作：请求 Agent 编写工作区指引',
        ),
      if (_resolveAgentPlanMode(_agentCollaborationModes) != null)
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
    commands.addAll(_buildAgentAcpCommandCards());
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

  List<Map<String, dynamic>> _buildAgentAcpCommandCards() {
    final runtime = _runtimeForMode(ChatPageMode.agent);
    final commands =
        runtime?.availableAcpCommands ?? const <Map<String, dynamic>>[];
    final seen = <String>{'/model', '/review', '/init', '/plan'};
    return commands
        .map((command) {
          final name = (command['name'] ?? '').toString().trim();
          if (name.isEmpty) return null;
          final slashName = name.startsWith('/') ? name : '/$name';
          if (!seen.add(slashName.toLowerCase())) return null;
          final description = (command['description'] ?? '').toString().trim();
          final card = _buildAgentCommandCard(
            cardId:
                'slash-command-acp-${name.replaceAll(RegExp(r'[^a-zA-Z0-9_-]'), '_')}',
            toolTitle: slashName,
            displayName: slashName,
            toolTypeLabel: LegacyTextLocalizer.isEnglish
                ? 'ACP command'
                : 'ACP 命令',
            status: 'running',
            statusLabel: LegacyTextLocalizer.isEnglish ? 'Available' : '可用',
            summary: description.isEmpty
                ? (LegacyTextLocalizer.isEnglish
                      ? 'Run $slashName through the active ACP session'
                      : '通过当前 ACP 会话运行 $slashName')
                : description,
            progress: LegacyTextLocalizer.isEnglish
                ? 'Tap to enter the command, then add arguments if needed'
                : '点击填入命令，可继续输入参数',
          );
          card['acpCommand'] = true;
          return card;
        })
        .whereType<Map<String, dynamic>>()
        .toList(growable: false);
  }

  List<Map<String, dynamic>> _buildAgentModelCards() {
    if (_agentModelOptions.isEmpty &&
        !_isAgentModelListLoading &&
        _agentModelListError == null) {
      unawaited(_loadAgentModelOptionsWhenReady());
    }
    final query = _slashCommandRouteQuery(_SlashCommandPanelRoute.agentModel)
        .toLowerCase();
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

  ChatPaneOverlayAnchorGeometry _resolveCommandAnchorGeometry({
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
    if (_isSurfacePageScrolling && _lastStableCommandAnchorGeometry != null) {
      return _lastStableCommandAnchorGeometry!;
    }

    if (_isInputAreaVisible && normalizedInputHeight > 0.5) {
      final geometry = resolveChatPaneOverlayAnchorGeometry(
        viewportSize: constraints.biggest,
        bottomSpacing:
            inputBottomPadding + keyboardSpacer + normalizedInputHeight,
        anchorHeight: normalizedInputHeight,
      );
      _lastStableCommandAnchorGeometry = geometry;
      return geometry;
    }

    final liveGeometry = _resolveCommandAnchorGeometryFromInputArea(
      layoutContext: layoutContext,
      constraints: constraints,
      derivedWidth: derivedWidth,
    );
    if (liveGeometry != null) {
      _lastStableCommandAnchorGeometry = liveGeometry;
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
    final inputBox = findActiveRenderObject(inputContext);
    final stackBox = findActiveRenderObject(layoutContext);
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
    _lastStableCommandAnchorGeometry = geometry;
    return geometry;
  }

  ChatPaneOverlayAnchorGeometry? _resolveCommandAnchorGeometryFromInputArea({
    required BuildContext layoutContext,
    required BoxConstraints constraints,
    required double derivedWidth,
  }) {
    if (!_isInputAreaVisible) {
      return null;
    }
    final inputContext = _chatInputAreaKey.currentContext;
    final inputBox = findActiveRenderObject(inputContext);
    final stackBox = findActiveRenderObject(layoutContext);
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

  void _handleInputAreaHeightChanged(double height) {
    final normalized = height.isFinite ? height : 0.0;
    if ((_inputAreaHeight - normalized).abs() < 0.5) {
      return;
    }
    if (!mounted) {
      _modeState(_activeMode).inputAreaHeight = normalized;
      return;
    }
    _modeState(_activeMode).inputAreaHeight = normalized;
    _chatKeyboardLayoutChanges.value++;
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
    AppBackgroundVisualProfile visualProfile,
  ) {
    final runtime = _runtimeForMode(mode);
    final resolvedMessages = resolveVisibleChatMessages(
      runtimeMessages: runtime?.messages,
      fallbackMessages: _modeState(mode).messages,
      preserveFallbackDuringHandoff: mode == ChatPageMode.agent
          ? false
          : _modeState(mode).isAiResponding,
    );
    final chatOnly =
        _conversationModeForPageMode(mode) == ConversationMode.chatOnly;
    // Chat-only disables tool execution, not reasoning. Keep reasoning cards
    // in the ordinary conversation surface so ACP providers do not appear to
    // have lost all thought output; only tool/request cards are execution UI.
    // `runtime.activeAgentTurnIds` is the single source of truth for which
    // turns are in flight. Only fall back to the page-level dispatch id when
    // there is no runtime yet to own it.
    final activeAgentTurnIds = <String>{...?runtime?.activeAgentTurnIds};
    if (mode == ChatPageMode.agent && activeAgentTurnIds.isEmpty) {
      // The runtime can already exist while a handoff/snapshot briefly has
      // not copied its active-turn ids yet. The page-level dispatch state is
      // still authoritative for this short window; use it to render the
      // single processing header, but never manufacture a thinking card.
      // Agent execution state is owned by the ACP runtime. A missing runtime
      // means the turn has not been admitted yet; rendering a page-local
      // dispatch id here would reintroduce a second lifecycle source.
    }
    final homeGreetingSettings = HomeGreetingSettingsService.notifier.value;
    final activeAcpAgentId = _activeAcpAgentId;
    // Normal chat is ACP-backed at the transport/session layer, but it keeps
    // the Xiaowan conversation layout for compatibility with the previous
    // release: no run header, fold wrapper, or Agent tool capsules. The ACP
    // All chat modes now use the same ACP run presentation. The selected
    // Harness changes the provider identity and capabilities, not the event
    // or card layout. Pure chat is simply an ACP run with no tool cards.
    final useAcpPresentation = true;
    return ChatKeyboardLayoutBuilder(
      builder: (context, metrics, child) {
        final internalInputKeyboardInset =
            mode == _activeMode && _isMessageListInputFocusedForMode(mode)
            ? metrics.bottomInset + _kChatMessageBottomSafeSpacing
            : 0.0;
        final reservedBottomOverlayInset = math.max(
          metrics.composerReservedInset,
          internalInputKeyboardInset,
        );
        final liftEmptyGreeting =
            mode == _activeMode && metrics.liftEmptyGreeting;
        return ChatMessageViewport(
          bottomOverlayInset:
              reservedBottomOverlayInset +
              (mode == _activeMode ? _slashCommandPanelOccupiedHeight : 0),
          liftEmptyGreeting: liftEmptyGreeting,
          child: child!,
        );
      },
      child: ChatHistoryLoadingTransition(
        isLoading: _isConversationHistoryLoading,
        textColor:
            !appearanceConfig.isActive &&
                appearanceConfig.chatTextColorMode !=
                    AppBackgroundTextColorMode.custom
            ? context.omniPalette.textSecondary
            : visualProfile.secondaryTextColor,
        child: ChatMessageList(
          messages: resolvedMessages,
          hideToolCards: chatOnly,
          activeAgentTurnIds: activeAgentTurnIds,
          useAcpPresentation: useAcpPresentation,
          activeAcpAgentId: activeAcpAgentId,
          onRetryAgentMessage: this._retryFailedAgentTurn,
          showEmptyGreeting:
              homeGreetingSettings.greetingEnabled &&
              (_resolvedThreadTarget ?? widget.threadTarget)
                      ?.isNewConversation ==
                  true,
          emptyGreetingQuickPrompts: homeGreetingSettings.quickPrompts,
          emptyGreetingPinnedQuickPromptIds:
              homeGreetingSettings.pinnedQuickPromptIds,
          onQuickPromptSelected: _applyHomeQuickPrompt,
          emptyGreetingAgentName: mode == ChatPageMode.agent
              ? _activeAcpAgentDisplayName
              : null,
          emptyGreetingAgentWorkspaceName: mode == ChatPageMode.agent
              ? _remoteCodexWorkspaceNameForGreeting()
              : null,
          onEmptyGreetingAgentWorkspaceTap: mode == ChatPageMode.agent
              ? () => unawaited(_openRemoteCodexWorkspacePicker())
              : null,
          scrollController: _scrollControllerForMode(mode),
          navigator: _modeState(mode).messageListNavigator,
          onInternalInputFocusChanged: (hasFocus) {
            _handleMessageListInputFocusChanged(mode, hasFocus);
          },
          onBeforeTaskExecute: handleBeforeTaskExecute,
          onCancelTask: _onCancelTaskFromCard,
          onRequestAuthorize: mode == ChatPageMode.openclaw
              ? null
              : _requestAuthorizeForExecution,
          onUserMessageLongPressStart: switch (mode) {
            ChatPageMode.normal => this._handleUserMessageLongPressStart,
            ChatPageMode.agent => this._handleUserMessageLongPressStart,
            ChatPageMode.openclaw => null,
          },
          onLatestUserMessageEditTap: mode == ChatPageMode.openclaw
              ? null
              : this._startEditingLatestUserMessage,
          onLoadMore: loadMoreMessages,
          hasMore: hasMoreMessages,
          visualProfile: visualProfile,
          appearanceConfig: appearanceConfig,
        ),
      ),
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
    return _activeAcpAgentId == 'na-cloud' || _activeAgentTargetIsRemote;
  }

  Widget _buildRemoteCodexWorkspaceBrowser({
    Key? key,
    required bool translucentSurfaces,
    required bool enableSystemBackHandler,
  }) {
    final workspacePath = _activeAcpAgentId == 'na-cloud'
        ? '/workspace'
        : (_agentRuntimeStatus.remoteCwd ?? _agentRuntimeStatus.cwd ?? '')
              .trim();
    final bridgeUrl = (_agentRuntimeStatus.remoteBridgeUrl ?? '').trim();
    if (workspacePath.isEmpty) {
      return _buildRemoteCodexWorkspaceUnavailable();
    }
    return CodexRemoteWorkspaceBrowser(
      agentId: _activeAcpAgentId == 'na-cloud'
          ? 'na-cloud'
          : _kRemoteCodexModeAgentId,
      allowFileMutations: true,
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

  Widget _buildChatPaneShell({
    required BuildContext layoutContext,
    required BoxConstraints constraints,
    required AppBackgroundConfig backgroundConfig,
    required AppBackgroundVisualProfile visualProfile,
    required bool backgroundActive,
    required Widget conversationBody,
    required bool hideWorkspaceOverlays,
    required bool showMenuButton,
    required bool showSurfaceSwitcher,
    VoidCallback? onWorkspacePaneTap,
    bool showWorkspacePaneButton = false,
    VoidCallback? onConversationPaneTap,
    bool conversationPaneVisible = false,
    bool workspacePaneVisible = false,
  }) {
    return OmniContentWidth(
      maxWidth: 840,
      child: LayoutBuilder(
        builder: (context, contentConstraints) => _buildChatPaneContent(
          layoutContext: context,
          constraints: contentConstraints,
          backgroundConfig: backgroundConfig,
          visualProfile: visualProfile,
          backgroundActive: backgroundActive,
          conversationBody: conversationBody,
          hideWorkspaceOverlays: hideWorkspaceOverlays,
          showMenuButton: showMenuButton,
          showSurfaceSwitcher: showSurfaceSwitcher,
          onWorkspacePaneTap: onWorkspacePaneTap,
          showWorkspacePaneButton: showWorkspacePaneButton,
          onConversationPaneTap: onConversationPaneTap,
          conversationPaneVisible: conversationPaneVisible,
          workspacePaneVisible: workspacePaneVisible,
        ),
      ),
    );
  }

  Widget _buildChatPaneContent({
    required BuildContext layoutContext,
    required BoxConstraints constraints,
    required AppBackgroundConfig backgroundConfig,
    required AppBackgroundVisualProfile visualProfile,
    required bool backgroundActive,
    required Widget conversationBody,
    required bool hideWorkspaceOverlays,
    required bool showMenuButton,
    required bool showSurfaceSwitcher,
    VoidCallback? onWorkspacePaneTap,
    bool showWorkspacePaneButton = false,
    VoidCallback? onConversationPaneTap,
    bool conversationPaneVisible = false,
    bool workspacePaneVisible = false,
  }) {
    final slashCommandCards =
        _showSlashCommandPanel &&
            !_showModelMentionPanel &&
            !_openClawPanelExpanded
        ? _buildSlashCommandCards()
        : const <Map<String, dynamic>>[];
    final showSlashCommandStrip =
        _isInputAreaVisible && slashCommandCards.isNotEmpty;
    if (!showSlashCommandStrip &&
        !_showModelMentionPanel &&
        !_openClawPanelExpanded &&
        _slashCommandPanelOccupiedHeight > 0) {
      _scheduleSlashCommandOccupiedHeightSync(0);
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
    return Stack(
      clipBehavior: Clip.hardEdge,
      children: [
        Column(
          children: [
            ChatAppBar(
              usePadLayout:
                  constraints.maxWidth >= OmniLayoutBreakpoints.medium,
              onMenuTap: _returnToConversationHome,
              title: _currentConversation?.title,
              actions: _chatHeaderActions(),
              onPrimaryModeTap:
                  _activeMode == ChatPageMode.agent && _activeAcpAgentId != null
                  ? () => GoRouterManager.push(
                      '/home/agent_sessions',
                      extra: _activeAcpAgentId,
                    )
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
              acpAgentModes: _chatAcpAgentModeOptions,
              activeAcpAgentId: switch (_activeMode) {
                ChatPageMode.agent => _appBarActiveAcpAgentId,
                ChatPageMode.openclaw => 'openclaw',
                ChatPageMode.normal => _kXiaowanAcpAgentId,
              },
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
              isPureChatSelected:
                  _activeMode == ChatPageMode.normal && _isPureChatSelected,
              showTerminalActions: _showLocalTerminalActions,
              showWorkspacePaneButton: showWorkspacePaneButton,
              onWorkspacePaneTap: onWorkspacePaneTap,
              onConversationPaneTap: onConversationPaneTap,
              conversationPaneVisible: conversationPaneVisible,
              workspacePaneVisible: workspacePaneVisible,
              tutorialMenuAnchorKey: _firstUseTourMenuAnchorKey,
              tutorialActionsAnchorKey: _firstUseTourActionsAnchorKey,
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
                child: ChatKeyboardLayoutBuilder(
                  builder: (context, metrics, child) => Padding(
                    padding: EdgeInsets.only(
                      bottom: metrics.composerBottomOffset,
                    ),
                    child: child,
                  ),
                  child: Container(
                    key: _inputAreaKey,
                    child: ChatInputWrapper(
                      unavailableMessage: _activeAgentUnavailableMessage,
                      inputAreaKey: _chatInputAreaKey,
                      controller: _messageController,
                      focusNode: _inputFocusNode,
                      onRequestFocus: _armComposerLiftIntent,
                      isProcessing:
                          _isAiResponding &&
                          _editingUserMessageId == null &&
                          !_hasPendingAgentUserInputRequest,
                      onSendMessage: this._handleComposerSendMessage,
                      onCancelTask: _onCancelTask,
                      onPopupVisibilityChanged: _onPopupVisibilityChanged,
                      topBanner: _showNewConversationModeSelector
                          ? NewConversationModeSelector(
                              selectedId: _newConversationModeId,
                              agentModes: _chatAcpAgentModeOptions,
                              enabled: _canSwitchNewConversationMode,
                              onSelected: (modeId) {
                                unawaited(_selectNewConversationMode(modeId));
                              },
                              onPopupVisibilityChanged: (visible) {
                                if (!mounted) return;
                                _chatInputAreaKey.currentState
                                    ?.setAuxiliaryPopupVisible(visible);
                                _onPopupVisibilityChanged(visible);
                              },
                            )
                          : null,
                      onTerminalTap: _showLocalTerminalActions
                          ? _handleTerminalToolTap
                          : null,
                      useLargeComposerStyle: true,
                      useAttachmentPickerForPlus: true,
                      onPickAttachment: _pickAttachments,
                      onPickImages: () =>
                          _pickAttachments(type: FileType.image),
                      onTriggerSlashCommand: _triggerSlashCommandPanel,
                      attachments: _pendingAttachments,
                      hasExternalSendPayload: _editingUserMessageHasAttachments,
                      isEditingUserMessage: _editingUserMessageId != null,
                      onCancelUserMessageEditing: this._stopUserMessageEditing,
                      onRemoveAttachment: _removePendingAttachment,
                      selectedModelOverrideId:
                          _activeMode == ChatPageMode.normal &&
                              _showConversationModelMentionChip
                          ? _activeConversationModelOverrideSelection?.modelId
                          : null,
                      contextUsageRatio: _activeMode == ChatPageMode.openclaw
                          ? null
                          : _currentConversation?.contextUsageRatio,
                      contextUsageTooltipMessage:
                          _activeMode == ChatPageMode.openclaw
                          ? null
                          : _buildContextUsageTooltipMessage(),
                      onLongPressContextUsageRing:
                          _activeMode == ChatPageMode.openclaw
                          ? null
                          : this._handleContextUsageRingLongPress,
                      modelPickerSettings: null,
                      runtimeConfigButton:
                          _activeMode == ChatPageMode.agent &&
                              _activeAcpAgentId == 'na-cloud'
                          ? null
                          : KeyedSubtree(
                              key: _firstUseTourModelAnchorKey,
                              child: _buildAcpConfigButton(),
                            ),
                      agentRunSettings: null,
                      onAgentRunSettingsOpened: null,
                      onAgentRunSettingsChanged: null,
                      agentPermissionMode:
                          _activeMode == ChatPageMode.agent &&
                              _activeAcpAgentId != 'na-cloud'
                          ? _agentPermissionMode
                          : null,
                      agentPermissionModes:
                          _activeMode == ChatPageMode.agent &&
                              !_activeAgentTargetIsRemote
                          ? const <AgentPermissionMode>[
                              AgentPermissionMode.readOnly,
                              AgentPermissionMode.defaultMode,
                              AgentPermissionMode.fullAccess,
                            ]
                          : AgentPermissionMode.values,
                      onAgentPermissionModeChanged:
                          _activeMode == ChatPageMode.agent &&
                              _activeAcpAgentId != 'na-cloud'
                          ? _selectAgentPermissionMode
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
        if (showSlashCommandStrip)
          ChatKeyboardLayoutBuilder(
            builder: (context, metrics, _) {
              final overlayAnchor = _resolveCommandAnchorGeometry(
                layoutContext: layoutContext,
                constraints: constraints,
                inputBottomPadding: metrics.inputBottomPadding,
                keyboardSpacer: metrics.keyboardSpacer,
                inputAreaHeight: _inputAreaHeight,
              );
              return Positioned(
                left: overlayAnchor.rect.left,
                width: overlayAnchor.rect.width,
                bottom: overlayAnchor.bottom,
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
                        anchorRect: overlayAnchor.rect,
                        onOccupiedHeightChanged:
                            _scheduleSlashCommandOccupiedHeightSync,
                        suppressSurfaceShadow: true,
                        onSelectCommand: _handleSlashCommandCardSelected,
                      ),
                    ),
                  ),
                ),
              );
            },
          ),
        if (_showModelMentionPanel || _openClawPanelExpanded)
          ChatKeyboardLayoutBuilder(
            builder: (context, metrics, child) => Positioned(
              left: 24,
              right: 24,
              bottom: metrics.commandPanelBottomOffset,
              child: child!,
            ),
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
            child: ChatKeyboardLayoutBuilder(
              builder: (context, metrics, _) => _buildMessageAnchorBarOverlay(
                composerReservedInset: metrics.composerReservedInset,
              ),
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
  }) {
    final mode = _primaryChatMessagePageMode;
    final runtime = _runtimeForMode(mode);
    final messages = resolveVisibleChatMessages(
      runtimeMessages: runtime?.messages,
      fallbackMessages: _modeState(mode).messages,
      preserveFallbackDuringHandoff: mode == ChatPageMode.agent
          ? false
          : _modeState(mode).isAiResponding,
    );
    final activeTaskIds = runtime?.activeAgentTurnIds ?? const <String>{};
    // composerReservedInset 已含 composer 顶部上方 12px 的安全间距，
    // 回收 6px 让按钮更贴近输入框右上角。
    final bottomInset = math.max(
      0.0,
      composerReservedInset -
          _kChatMessageBottomSafeSpacing +
          6 +
          _slashCommandPanelOccupiedHeight,
    );
    final visible =
        _isInputAreaVisible &&
        !_showModelMentionPanel &&
        !_openClawPanelExpanded &&
        !_isPopupVisible;
    return ChatMessageAnchorBar(
      messages: messages,
      activeAgentTurnIds: activeTaskIds,
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

  Widget _buildAdaptiveWorkspaceShell({
    required AppBackgroundConfig backgroundConfig,
    required AppBackgroundVisualProfile visualProfile,
    required bool backgroundActive,
  }) {
    const shellPadding = EdgeInsets.symmetric(
      horizontal: _kHdPadShellHorizontalPadding,
    );
    return LayoutBuilder(
      builder: (context, constraints) {
        final availableWidth = math
            .max(0, constraints.maxWidth - shellPadding.horizontal)
            .toDouble();
        final expandedLeft = _hdPadPaneLayoutResolver.resolve(
          availableWidth,
          preferredLeftWidth: _hdPadLeftPaneWidth,
          collapseRightPane: true,
        );
        final expandedRight = _hdPadPaneLayoutResolver.resolve(
          availableWidth,
          preferredRightWidth: _hdPadRightPaneWidth,
          collapseLeftPane: true,
        );
        final layout = _hdPadPaneLayoutResolver.resolve(
          availableWidth,
          preferredLeftWidth: _hdPadLeftPaneWidth,
          preferredRightWidth: _hdPadRightPaneWidth,
          collapseLeftPane: _hdPadLeftPaneCollapsed,
          collapseRightPane: _hdPadRightPaneCollapsed,
        );
        final leftHidden = layout.leftWidth == 0;
        final rightHidden = layout.rightWidth == 0;
        final leftContentWidth = leftHidden
            ? expandedLeft.leftWidth
            : layout.leftWidth;
        final rightContentWidth = rightHidden
            ? expandedRight.rightWidth
            : layout.rightWidth;
        final resized = _hdPadLastLayoutWidth != availableWidth;
        _hdPadLastLayoutWidth = availableWidth;
        if (resized && _isHdPadPaneDragging) {
          // A rotation or split-window resize may remove the drag recognizer
          // before it receives a pointer-up. The pane owner closes that UI
          // transaction here and saves the last accepted widths.
          _resetHdPadPaneDragState();
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted) _persistHdPadPanePreferences();
          });
        }
        final leftCollapseThreshold = _resolveHdPadPaneCollapseThreshold(
          availableWidth: availableWidth,
          minPaneWidth: HdPadPaneLayoutResolver.minLeftWidth,
        );
        final rightCollapseThreshold = _resolveHdPadPaneCollapseThreshold(
          availableWidth: availableWidth,
          minPaneWidth: HdPadPaneLayoutResolver.minRightWidth,
        );
        final paneDuration =
            _isHdPadPaneDragging ||
                resized ||
                MediaQuery.disableAnimationsOf(context)
            ? Duration.zero
            : const Duration(milliseconds: 280);
        const paneCurve = Curves.easeInOutCubic;
        return ColoredBox(
          // The panes share one canvas, including a single tint over wallpaper.
          // Their content stays clipped while resizing, without separate cards.
          color: backgroundActive
              ? backgroundSurfaceColor(
                  translucent: true,
                  baseColor: context.omniPalette.surfacePrimary,
                  opacity: 0.72,
                )
              : context.omniPalette.pageBackground,
          child: Padding(
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
                      minWidth: leftContentWidth,
                      maxWidth: leftContentWidth,
                      child: SizedBox(
                        width: leftContentWidth,
                        child: _ChatPaneVisibility(
                          hidden: leftHidden,
                          child: AnimatedSlide(
                            duration: paneDuration,
                            curve: Curves.easeInOutCubic,
                            offset: leftHidden
                                ? const Offset(-0.08, 0)
                                : Offset.zero,
                            child: ClipRect(
                              child: HomeDrawer(
                                key: _embeddedDrawerKey,
                                homePage: true,
                                conversationSidebar: true,
                                selectedThreadTarget:
                                    _currentConversationId == null
                                    ? null
                                    : ConversationThreadTarget.existing(
                                        conversationId: _currentConversationId,
                                        mode:
                                            _currentConversation?.mode ??
                                            _conversationModeForPageMode(
                                              _activeMode,
                                            ),
                                      ),
                                active: !leftHidden,
                                embedded: true,
                                closeOnNavigate: false,
                                onSearchFocusChanged:
                                    _handleHomeDrawerSearchFocusChanged,
                                searchFieldKey: _embeddedDrawerSearchFieldKey,
                                newConversationMode:
                                    _conversationModeForPageMode(_activeMode),
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
                  width: leftHidden
                      ? 0
                      : HdPadPaneLayoutResolver.dividerHitWidth,
                  child: leftHidden
                      ? const SizedBox.shrink()
                      : ChatPaneResizeHandle(
                          key: const ValueKey(
                            'chat-conversation-resize-handle',
                          ),
                          label: LegacyTextLocalizer.isEnglish
                              ? 'Conversation sidebar width'
                              : '会话侧栏宽度',
                          hint: LegacyTextLocalizer.isEnglish
                              ? 'Drag or use arrow keys to resize. Double-tap or press Enter to collapse.'
                              : '拖动或按左右方向键调整，双击或按回车收起',
                          width: HdPadPaneLayoutResolver.dividerHitWidth,
                          paneWidth: layout.leftWidth,
                          viewportWidth: availableWidth,
                          onCollapse: _toggleHdPadLeftPaneCollapsed,
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
                  child: ClipRect(
                    child: LayoutBuilder(
                      builder: (context, paneConstraints) {
                        return _buildChatPaneShell(
                          layoutContext: context,
                          constraints: paneConstraints,
                          backgroundConfig: backgroundConfig,
                          visualProfile: visualProfile,
                          backgroundActive: backgroundActive,
                          conversationBody: _buildModeMessagePage(
                            _primaryChatMessagePageMode,
                            backgroundConfig,
                            visualProfile,
                          ),
                          hideWorkspaceOverlays: false,
                          showMenuButton: true,
                          showSurfaceSwitcher: false,
                          showWorkspacePaneButton: true,
                          onConversationPaneTap: _toggleHdPadLeftPaneCollapsed,
                          conversationPaneVisible: !leftHidden,
                          workspacePaneVisible: !rightHidden,
                          onWorkspacePaneTap: _toggleHdPadRightPaneCollapsed,
                        );
                      },
                    ),
                  ),
                ),
                AnimatedContainer(
                  duration: paneDuration,
                  curve: paneCurve,
                  width: rightHidden
                      ? 0
                      : HdPadPaneLayoutResolver.dividerHitWidth,
                  child: rightHidden
                      ? const SizedBox.shrink()
                      : ChatPaneResizeHandle(
                          key: const ValueKey('chat-workspace-resize-handle'),
                          label: LegacyTextLocalizer.isEnglish
                              ? 'Workspace width'
                              : '工作区宽度',
                          hint: LegacyTextLocalizer.isEnglish
                              ? 'Drag or use arrow keys to resize. Double-tap or press Enter to collapse.'
                              : '拖动或按左右方向键调整，双击或按回车收起',
                          width: HdPadPaneLayoutResolver.dividerHitWidth,
                          paneWidth: layout.rightWidth,
                          viewportWidth: availableWidth,
                          leadingPane: false,
                          onCollapse: _toggleHdPadRightPaneCollapsed,
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
                                (_hdPadPaneDragStartWidth ??
                                    layout.rightWidth) -
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
                      minWidth: rightContentWidth,
                      maxWidth: rightContentWidth,
                      child: SizedBox(
                        width: rightContentWidth,
                        child: _ChatPaneVisibility(
                          hidden: rightHidden,
                          child: AnimatedSlide(
                            duration: paneDuration,
                            curve: Curves.easeInOutCubic,
                            offset: rightHidden
                                ? const Offset(0.08, 0)
                                : Offset.zero,
                            child: ClipRect(
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
          ),
        );
      },
    );
  }

  ChatKeyboardLayoutMetrics _resolveKeyboardLayout(MediaQueryData mediaQuery) {
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
    // A keyboard owned by sidebar search must not move the chat greeting.
    // Feeding zero also clears its motion state before focus returns to chat.
    _emptyGreetingKeyboardLiftTracker.update(
      shouldLiftComposerForKeyboard ? bottomInset : 0,
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

    return (
      bottomInset: bottomInset,
      composerBottomOffset: inputBottomPadding + keyboardSpacer,
      composerReservedInset: _resolveNormalSurfaceComposerInset(
        inputBottomPadding: inputBottomPadding,
        keyboardSpacer: keyboardSpacer,
      ),
      commandPanelBottomOffset: commandPanelBottomOffset,
      inputBottomPadding: inputBottomPadding,
      keyboardSpacer: keyboardSpacer,
      liftEmptyGreeting: _emptyGreetingKeyboardLiftTracker.shouldLift,
    );
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => _buildAdaptiveChatPage(
        context,
        isChatWorkspace: isChatWorkspaceViewport(
          Size(
            constraints.maxWidth - MediaQuery.viewPaddingOf(context).horizontal,
            constraints.maxHeight,
          ),
        ),
        availableWidth:
            constraints.maxWidth - MediaQuery.viewPaddingOf(context).horizontal,
      ),
    );
  }

  Widget _buildAdaptiveChatPage(
    BuildContext context, {
    required bool isChatWorkspace,
    required double availableWidth,
  }) {
    final workspaceVisible =
        isChatWorkspace &&
        _currentPadLayout(
              availableWidth:
                  availableWidth - _kHdPadShellHorizontalPadding * 2,
            ).rightWidth >
            0;
    final chatPage = ChatKeyboardLayout(
      changes: _chatKeyboardLayoutChanges,
      resolve: _resolveKeyboardLayout,
      child: ValueListenableBuilder<AppBackgroundConfig>(
        valueListenable: AppBackgroundService.notifier,
        builder: (context, backgroundConfig, _) {
          final backgroundActive = backgroundConfig.isActive;
          return ValueListenableBuilder<AppBackgroundVisualProfile>(
            valueListenable: AppBackgroundService.visualProfileNotifier,
            builder: (context, visualProfile, _) {
              final handlesBackLocally =
                  _isFirstUseTourActive ||
                  _conversationModelSelectorHandle != null ||
                  (workspaceVisible && _workspaceBrowserCanGoUp);
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
                  if (_embeddedDrawerKey.currentState?.handleBackIfNeeded() ==
                      true) {
                    return;
                  }
                  if (workspaceVisible && _workspaceBrowserCanGoUp) {
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
                  backgroundColor: Colors.transparent,
                  resizeToAvoidBottomInset: false,
                  // Conversation history lives on the home route. Keep the
                  // system edge free for predictive back; the workspace remains
                  // the second page of the horizontal PageView below.
                  drawerEnableOpenDragGesture: false,
                  body: Stack(
                    fit: StackFit.expand,
                    children: [
                      Positioned.fill(
                        child: backgroundActive
                            ? AppBackgroundLayer(
                                config: backgroundConfig,
                                fallbackColor:
                                    context.omniPalette.previewFallback,
                                layerKey: const ValueKey(
                                  'chat-page-background',
                                ),
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
                            },
                            child: isChatWorkspace
                                ? _buildAdaptiveWorkspaceShell(
                                    backgroundConfig: backgroundConfig,
                                    visualProfile: visualProfile,
                                    backgroundActive: backgroundActive,
                                  )
                                : LayoutBuilder(
                                    builder: (context, constraints) {
                                      return _buildChatPaneShell(
                                        layoutContext: context,
                                        constraints: constraints,
                                        backgroundConfig: backgroundConfig,
                                        visualProfile: visualProfile,
                                        backgroundActive: backgroundActive,
                                        conversationBody: ClipRect(
                                          child:
                                              NotificationListener<
                                                ScrollNotification
                                              >(
                                                onNotification:
                                                    _handleModePageScrollNotification,
                                                child: PageView(
                                                  controller:
                                                      _modePageController,
                                                  onPageChanged:
                                                      _handleModePageChanged,
                                                  children: [
                                                    _buildModeMessagePage(
                                                      _primaryChatMessagePageMode,
                                                      backgroundConfig,
                                                      visualProfile,
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
      ),
    );
    if (!_isFirstUseTourActive) {
      return CallbackShortcuts(
        bindings: {
          const SingleActivator(LogicalKeyboardKey.keyL, control: true): () =>
              _requestComposerFocus(),
          if (isChatWorkspace) ...{
            const SingleActivator(LogicalKeyboardKey.keyB, control: true):
                _toggleHdPadLeftPaneCollapsed,
            const SingleActivator(LogicalKeyboardKey.keyE, control: true):
                _toggleHdPadRightPaneCollapsed,
          },
        },
        child: Focus(
          focusNode: _pageShortcutsFocusNode,
          autofocus: true,
          child: chatPage,
        ),
      );
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
          ? 'No context threshold set\nLong press to adjust threshold'
          : '上下文阈值未设置\n长按可调整阈值';
    }
    final usedTokens = conversation.latestPromptTokens;
    final thresholdTokens = conversation.promptTokenThreshold;
    return '${_formatTokenCount(usedTokens)} / '
        '${_formatTokenCount(thresholdTokens)} tokens'
        '\n${LegacyTextLocalizer.isEnglish ? 'Long press to adjust threshold' : '长按可调整阈值'}';
  }

  String _formatTokenCount(int value) {
    return value.toString().replaceAllMapped(
      RegExp(r'\B(?=(\d{3})+(?!\d))'),
      (_) => ',',
    );
  }
}
