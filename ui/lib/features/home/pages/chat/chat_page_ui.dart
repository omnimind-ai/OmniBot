part of 'chat_page.dart';

const int _kDefaultContextTokenThreshold = 128000;
const int _kMinContextTokenThreshold = 10000;
const int _kMaxContextTokenThreshold = 1000000;
const double _kChatMessageBottomSafeSpacing = 12.0;
const double _kSlashCommandDrawerRadius = 18.0;
const double _kSlashCommandDrawerHandleWidth = 36.0;
const double _kSlashCommandDrawerHandleHeight = 4.0;

enum _UserMessageQuickAction { copy, edit, retry }

mixin _ChatPageUiMixin on _ChatPageStateBase {
  ChatPaneOverlayAnchorGeometry? _lastStableToolActivityAnchorGeometry;
  static const double _kChatInputWrapperTopPadding = 8.0;
  static const double _kChatInputFallbackHeight = 80.0;
  static const double _kHdPadPaneCollapseWidthRatio = 0.12;
  static const double _kHdPadPaneCollapseMinWidthFactor = 0.72;
  final Set<String> _pendingManualAgentRetryTaskIds = <String>{};

  ChatPageMode get _primaryChatMessagePageMode =>
      _activeMode == ChatPageMode.codex
      ? ChatPageMode.codex
      : ChatPageMode.normal;

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
    _draftMessageByMode[_activeConversationMode] = text;
    _handleSlashCommandInput();
    if (!_inputFocusNode.hasFocus) {
      _inputFocusNode.requestFocus();
      SystemChannels.textInput.invokeMethod('TextInput.show');
    }
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
      final currentHeight = _slashCommandPanelOccupiedHeightByMode[mode] ?? 0;
      if ((currentHeight - nextHeight).abs() < 0.5) {
        return;
      }
      setState(() {
        _slashCommandPanelOccupiedHeightByMode[mode] = nextHeight;
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
        _slashCommandPanelOccupiedHeightByMode[_activeMode] = normalized;
      });
    });
  }

  List<Map<String, dynamic>> _buildSlashCommandCards() {
    final route = _resolveSlashCommandPanelRoute(_messageController.text);
    if (_activeMode == ChatPageMode.codex) {
      return _buildCodexSlashCommandCards(route);
    }
    if (route == _SlashCommandPanelRoute.effort &&
        _supportsReasoningEffortCommand) {
      final activeEffort = _activeConversationReasoningEffort;
      final displayActiveEffort =
          activeEffort == 'xhigh' ? 'max' : activeEffort;
      final query = _slashCommandRouteQuery(route).toLowerCase();
      final efforts = <String>['no', 'low', 'high', 'max']
          .where((effort) {
            return query.isEmpty ||
                effort.contains(query) ||
                (effort == 'max' && 'xhigh'.contains(query));
          })
          .toList(growable: false);
      return efforts
          .map((effort) {
            final isSelected = effort == displayActiveEffort;
            return <String, dynamic>{
              'cardId': 'slash-command-effort-$effort',
              'toolName': effort,
              'toolTitle': effort,
              'displayName': effort,
              'toolType': 'command',
              'toolTypeLabel': LegacyTextLocalizer.isEnglish
                  ? 'Thinking'
                  : '思考',
              'status': isSelected ? 'success' : 'running',
              'statusLabel': isSelected
                  ? (LegacyTextLocalizer.isEnglish ? 'Selected' : '已选')
                  : (LegacyTextLocalizer.isEnglish ? 'Available' : '可选'),
              'summary': effort == 'no'
                  ? (isSelected
                        ? (LegacyTextLocalizer.isEnglish
                              ? 'Thinking disabled'
                              : '已关闭思考')
                        : (LegacyTextLocalizer.isEnglish
                              ? 'Disable thinking'
                              : '关闭思考'))
                  : (isSelected
                        ? (LegacyTextLocalizer.isEnglish
                              ? 'Current effort: $effort'
                              : '当前思考强度：$effort')
                        : (LegacyTextLocalizer.isEnglish
                              ? 'Switch reasoning effort to $effort'
                              : '将思考强度切换为 $effort')),
              'progress': effort == 'no'
                  ? (LegacyTextLocalizer.isEnglish
                        ? 'enable_thinking=false for subsequent requests'
                        : '后续请求将设置 enable_thinking=false')
                  : (LegacyTextLocalizer.isEnglish
                        ? 'reasoning_effort parameter for subsequent requests'
                        : '用于后续请求的 reasoning_effort 参数'),
            };
          })
          .toList(growable: false);
    }

    final commands = <Map<String, dynamic>>[];
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
      final activeEffort = _activeConversationReasoningEffort;
      final displayActiveEffort =
          activeEffort == 'xhigh' ? 'max' : activeEffort;
      commands.add(<String, dynamic>{
        'cardId': 'slash-command-effort',
        'toolName': '/effort',
        'toolTitle': '/effort',
        'displayName': '/effort',
        'toolType': 'command',
        'toolTypeLabel': LegacyTextLocalizer.isEnglish ? 'Thinking' : '思考',
        'status': displayActiveEffort == null ? 'running' : 'success',
        'statusLabel':
            displayActiveEffort ??
            (LegacyTextLocalizer.isEnglish ? 'Command' : '命令'),
        'summary': displayActiveEffort == null
            ? (LegacyTextLocalizer.isEnglish
                  ? 'Set reasoning effort for this session'
                  : '设置当前会话的思考强度')
            : (LegacyTextLocalizer.isEnglish
                  ? 'Current effort: $displayActiveEffort'
                  : '当前思考强度：$displayActiveEffort'),
        'progress': LegacyTextLocalizer.isEnglish
            ? 'Choose no, low, high or max'
            : '点击后选择 no、low、high 或 max',
      });
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

  List<Map<String, dynamic>> _buildCodexSlashCommandCards(
    _SlashCommandPanelRoute route,
  ) {
    if (route == _SlashCommandPanelRoute.codexModel) {
      return _buildCodexModelCards();
    }
    return _buildCodexRootCommandCards();
  }

  List<Map<String, dynamic>> _buildCodexRootCommandCards() {
    final query = _messageController.text.trimLeft().toLowerCase();
    final commands = <Map<String, dynamic>>[
      _buildCodexCommandCard(
        cardId: 'slash-command-codex-model',
        toolTitle: '/model',
        displayName: '/model',
        toolTypeLabel: LegacyTextLocalizer.isEnglish ? 'Model' : '模型',
        status: _activeCodexModelId == null ? 'running' : 'success',
        statusLabel: _activeCodexModelId == null
            ? (LegacyTextLocalizer.isEnglish ? 'Select' : '选择')
            : (_activeCodexModelId!),
        summary: _activeCodexModelId == null
            ? (LegacyTextLocalizer.isEnglish
                  ? 'Choose a Codex model'
                  : '选择 Codex 模型')
            : (LegacyTextLocalizer.isEnglish
                  ? 'Current model: $_activeCodexModelId'
                  : '当前模型：$_activeCodexModelId'),
        progress: _codexModelListError != null
            ? _codexModelListError!
            : _isCodexModelListLoading
            ? (LegacyTextLocalizer.isEnglish ? 'Loading models' : '加载模型中')
            : (_codexModelOptions.isEmpty
                  ? (LegacyTextLocalizer.isEnglish
                        ? 'Tap to load models'
                        : '点击加载模型')
                  : (_codexModelOptions.length == 1
                        ? '1 model'
                        : '${_codexModelOptions.length} models')),
      ),
      _buildCodexCommandCard(
        cardId: 'slash-command-codex-review',
        toolTitle: '/review',
        displayName: '/review',
        toolTypeLabel: LegacyTextLocalizer.isEnglish ? 'Review' : '审查',
        status: 'running',
        statusLabel: LegacyTextLocalizer.isEnglish ? 'Command' : '命令',
        summary: LegacyTextLocalizer.isEnglish
            ? 'Review changes in the current workspace'
            : '审查当前工作区改动',
        progress: LegacyTextLocalizer.isEnglish
            ? 'Runs Codex review on the active thread'
            : '在当前线程中启动 Codex review',
      ),
      _buildCodexCommandCard(
        cardId: 'slash-command-codex-init',
        toolTitle: '/init',
        displayName: '/init',
        toolTypeLabel: LegacyTextLocalizer.isEnglish ? 'Init' : '初始化',
        status: 'running',
        statusLabel: LegacyTextLocalizer.isEnglish ? 'Command' : '命令',
        summary: LegacyTextLocalizer.isEnglish
            ? 'Generate or update AGENTS.md'
            : '生成或更新 AGENTS.md',
        progress: LegacyTextLocalizer.isEnglish
            ? 'Creates Codex initialization guidance'
            : '生成 Codex 初始化指引',
      ),
      _buildCodexCommandCard(
        cardId: 'slash-command-codex-plan',
        toolTitle: '/plan',
        displayName: '/plan',
        toolTypeLabel: LegacyTextLocalizer.isEnglish ? 'Plan' : '计划',
        status: _isCodexPlanMode(_activeCodexCollaborationMode)
            ? 'success'
            : 'running',
        statusLabel: _isCodexPlanMode(_activeCodexCollaborationMode)
            ? (LegacyTextLocalizer.isEnglish ? 'Selected' : '已选')
            : (LegacyTextLocalizer.isEnglish ? 'Command' : '命令'),
        summary: _isCodexPlanMode(_activeCodexCollaborationMode)
            ? (LegacyTextLocalizer.isEnglish
                  ? 'Plan mode is active'
                  : '当前已启用 Plan 模式')
            : (LegacyTextLocalizer.isEnglish
                  ? 'Switch Codex to plan mode'
                  : '切换 Codex 到 Plan 模式'),
        progress: _codexCollaborationModeListError != null
            ? _codexCollaborationModeListError!
            : _isCodexCollaborationModeListLoading
            ? (LegacyTextLocalizer.isEnglish ? 'Loading modes' : '加载模式中')
            : (_codexCollaborationModes.isEmpty
                  ? (LegacyTextLocalizer.isEnglish
                        ? 'Tap to load modes'
                        : '点击加载模式')
                  : (_codexCollaborationModes.length == 1
                        ? '1 mode'
                        : '${_codexCollaborationModes.length} modes')),
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

  List<Map<String, dynamic>> _buildCodexModelCards() {
    if (_codexModelOptions.isEmpty &&
        !_isCodexModelListLoading &&
        _codexModelListError == null) {
      unawaited(_loadCodexModelOptions());
    }
    final query = _slashCommandRouteQuery(
      _SlashCommandPanelRoute.codexModel,
    ).toLowerCase();
    final availableModels = _codexModelOptions.isEmpty
        ? <String>[]
        : _codexModelOptions;
    final filteredModels = availableModels
        .where(
          (modelId) => query.isEmpty || modelId.toLowerCase().contains(query),
        )
        .toList(growable: false);
    final selectedModel = _activeCodexModelId;
    final orderedModels = <String>[
      if (selectedModel != null && filteredModels.contains(selectedModel))
        selectedModel,
      ...filteredModels.where((modelId) => modelId != selectedModel),
    ];
    if (orderedModels.isEmpty) {
      final statusLabel = _codexModelListError != null
          ? (LegacyTextLocalizer.isEnglish ? 'Error' : '错误')
          : _isCodexModelListLoading
          ? (LegacyTextLocalizer.isEnglish ? 'Loading' : '加载中')
          : (LegacyTextLocalizer.isEnglish ? 'No models' : '暂无模型');
      return <Map<String, dynamic>>[
        _buildCodexCommandCard(
          cardId: 'slash-command-codex-model-placeholder',
          toolTitle: '/model',
          displayName: '/model',
          toolTypeLabel: LegacyTextLocalizer.isEnglish ? 'Model' : '模型',
          status: _codexModelListError != null ? 'failed' : 'running',
          statusLabel: statusLabel,
          summary:
              _codexModelListError ??
              (_isCodexModelListLoading
                  ? (LegacyTextLocalizer.isEnglish
                        ? 'Loading available models'
                        : '正在加载可用模型')
                  : (LegacyTextLocalizer.isEnglish
                        ? 'Open /model to load Codex models'
                        : '输入 /model 加载 Codex 模型')),
          progress: query.isEmpty ? '/model' : query,
        ),
      ];
    }
    return orderedModels
        .map(
          (modelId) => _buildCodexCommandCard(
            cardId: 'slash-command-codex-model-$modelId',
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

  Map<String, dynamic> _buildCodexCommandCard({
    required String cardId,
    required String toolTitle,
    required String displayName,
    required String toolTypeLabel,
    required String status,
    required String statusLabel,
    required String summary,
    required String progress,
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
    };
  }

  void _handleSlashCommandCardSelected(Map<String, dynamic> cardData) {
    if (_activeMode == ChatPageMode.codex) {
      unawaited(_handleCodexSlashCommandCardSelected(cardData));
      return;
    }
    final command = (cardData['toolTitle'] ?? cardData['displayName'] ?? '')
        .toString()
        .trim();
    switch (command) {
      case '/compact':
        unawaited(_executeManualContextCompactionCommand());
        break;
      case '/effort':
        _messageController.value = const TextEditingValue(
          text: '/effort ',
          selection: TextSelection.collapsed(offset: 8),
        );
        _inputFocusNode.requestFocus();
        _handleSlashCommandInput();
        break;
      case 'no':
      case 'low':
      case 'high':
      case 'xhigh':
      case 'max':
        unawaited(_applyConversationReasoningEffort(command));
        _messageController.clear();
        _hideSlashCommandPanel();
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
        _toolActivityOccupiedHeightByMode[_activeMode] = normalized;
      });
    });
  }

  void _setToolActivityExpanded(bool expanded) {
    if (_isToolActivityExpanded == expanded) {
      return;
    }
    if (!mounted) {
      _toolActivityExpandedByMode[_activeMode] = expanded;
      return;
    }
    setState(() {
      _toolActivityExpandedByMode[_activeMode] = expanded;
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
      _inputAreaHeightByMode[_activeMode] = normalized;
      return;
    }
    setState(() {
      _inputAreaHeightByMode[_activeMode] = normalized;
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
              child: child,
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
    final resolvedMessages = runtime?.messages ?? _messagesByMode[mode]!;
    final activeAgentTaskIds = runtime?.activeAgentTaskIds ?? const <String>{};
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
    final liftEmptyGreeting =
        mode == _activeMode &&
        _emptyGreetingKeyboardLiftTracker.resolveForBuild(bottomInset);
    final homeGreetingSettings = HomeGreetingSettingsService.notifier.value;
    return ChatMessageList(
      messages: resolvedMessages,
      activeAgentTaskIds: activeAgentTaskIds,
      onRetryAgentMessage: _retryFailedAgentTurn,
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
      emptyGreetingCodexWorkspaceName: mode == ChatPageMode.codex
          ? _codexRemoteWorkspaceNameForGreeting()
          : null,
      onEmptyGreetingCodexWorkspaceTap: mode == ChatPageMode.codex
          ? () => unawaited(_openCodexRemoteWorkspacePicker())
          : null,
      scrollController: _scrollControllerForMode(mode),
      bottomOverlayInset:
          bottomOverlayInset +
          (mode == _activeMode ? _slashCommandPanelOccupiedHeight : 0) +
          (showToolActivityStrip ? _toolActivityOccupiedHeight : 0),
      onBeforeTaskExecute: handleBeforeTaskExecute,
      onCancelTask: _onCancelTaskFromCard,
      onRequestAuthorize: mode == ChatPageMode.normal
          ? _requestAuthorizeForExecution
          : null,
      onUserMessageLongPressStart: mode == ChatPageMode.normal
          ? _handleUserMessageLongPressStart
          : null,
      editingUserMessageId: mode == ChatPageMode.normal
          ? _editingUserMessageIdByMode[mode]
          : null,
      userMessageEditController: mode == ChatPageMode.normal
          ? _userMessageEditControllerForMode(mode)
          : null,
      onUserMessageEditCancelled: mode == ChatPageMode.normal
          ? _cancelUserMessageEditing
          : null,
      onUserMessageEditSaved: mode == ChatPageMode.normal
          ? _saveAndResendEditedUserMessage
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
          onSendFileToCurrentConversation:
              _sendWorkspaceFileToCurrentConversation,
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
    if (_activeMode != ChatPageMode.codex) {
      return false;
    }
    final runtime = _codexStatus.runtime?.trim();
    return runtime == 'remote' || _codexStatus.remoteEnabled;
  }

  Widget _buildRemoteCodexWorkspaceBrowser({
    Key? key,
    required bool translucentSurfaces,
    required bool enableSystemBackHandler,
  }) {
    final workspacePath = (_codexStatus.remoteCwd ?? _codexStatus.cwd ?? '')
        .trim();
    final bridgeUrl = (_codexStatus.remoteBridgeUrl ?? '').trim();
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
      onSendFileToCurrentConversation:
          _sendRemoteWorkspaceFileToCurrentConversation,
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
                  ? 'Remote Codex workspace is not configured'
                  : '远程 Codex 工作目录尚未配置',
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
                  ? 'Open Codex settings and set a remote cwd, or scan the PC Bridge QR code.'
                  : '请在 Codex 配置中设置远程工作目录，或扫描 PC Bridge 二维码。',
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 12,
                height: 1.4,
              ),
            ),
            const SizedBox(height: 14),
            TextButton.icon(
              onPressed: () => GoRouterManager.push('/home/codex_setting'),
              icon: const Icon(Icons.settings_outlined, size: 18),
              label: Text(LegacyTextLocalizer.localize('Codex 配置')),
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
    final suppressToolActivitySurfaceShadow =
        _inputFocusNode.hasFocus &&
        (MediaQuery.maybeOf(context)?.viewInsets.bottom ?? 0.0) > 0;
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
              onAgentTap: () {
                unawaited(_handleAgentModeShortcutTap());
              },
              onPureChatToggleTap: () {
                unawaited(_handlePureChatModeShortcutTap());
              },
              onCodexTap: () {
                unawaited(_handleCodexTap());
              },
              onPrimaryModeTap: _activeMode == ChatPageMode.codex
                  ? () => GoRouterManager.push('/home/codex_sessions')
                  : null,
              onCompanionTap: () {
                unawaited(_toggleCompanionMode());
              },
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
              isCompanionModeEnabled: _isCompanionModeEnabled,
              isCompanionToggleLoading: _isCompanionToggleLoading,
              isCodexReady: _codexStatus.ready,
              isCodexConnected: _codexStatus.connected,
              isCodexLoading: _isCodexStatusLoading,
              isCodexSelected: _activeMode == ChatPageMode.codex,
              isAgentSelected:
                  _activeMode == ChatPageMode.normal && !_isPureChatSelected,
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
                  _activeMode == ChatPageMode.codex,
              isPureChatSelected: _isPureChatSelected,
              isPureChatToggleLocked: _isPureChatToggleLocked,
              showWorkspacePaneButton: showWorkspacePaneButton,
              onWorkspacePaneTap: onWorkspacePaneTap,
            ),
            Expanded(child: conversationBody),
          ],
        ),
        if (_vlmInfoQuestion != null)
          Positioned(
            left: 0,
            right: 0,
            bottom: composerReservedInset,
            child: _buildNormalSurfaceTransition(
              viewportWidth: constraints.maxWidth,
              child: VlmInfoPrompt(
                question: _vlmInfoQuestion!,
                controller: _vlmAnswerController,
                isSubmitting: _isSubmittingVlmReply,
                onSubmit: onSubmitVlmInfo,
                onDismiss: dismissVlmInfo,
              ),
            ),
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
                      isProcessing: _isAiResponding,
                      onSendMessage: _sendMessage,
                      onCancelTask: _onCancelTask,
                      onPopupVisibilityChanged: _onPopupVisibilityChanged,
                      onTerminalTap: _handleTerminalToolTap,
                      useLargeComposerStyle: true,
                      useAttachmentPickerForPlus: true,
                      onPickAttachment: _pickAttachments,
                      onTriggerSlashCommand: _triggerSlashCommandPanel,
                      attachments: _pendingAttachments,
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
                              onPointerDown: () {
                                _suppressNextOutsideTapKeyboardHide = true;
                              },
                              onOpen: (anchorContext) =>
                                  _openConversationModelSelector(anchorContext),
                            )
                          : null,
                      codexRunSettings: _activeMode == ChatPageMode.codex
                          ? CodexRunSettings(
                              modelId: _activeCodexModelId ?? '',
                              reasoningEffort:
                                  _activeCodexReasoningEffort ?? 'xhigh',
                              modelOptions: _codexModelOptions,
                              reasoningEffortOptions:
                                  _codexReasoningEffortOptions,
                              isLoadingModels: _isCodexModelListLoading,
                              modelListError: _codexModelListError,
                            )
                          : null,
                      onCodexRunSettingsOpened:
                          _activeMode == ChatPageMode.codex
                          ? () => _loadCodexModelOptions(force: true)
                          : null,
                      onCodexRunSettingsChanged:
                          _activeMode == ChatPageMode.codex
                          ? ({String? modelId, String? reasoningEffort}) {
                              if (modelId != null) {
                                unawaited(
                                  _selectCodexModel(
                                    modelId,
                                    clearComposer: false,
                                  ),
                                );
                              }
                              if (reasoningEffort != null) {
                                unawaited(
                                  _selectCodexReasoningEffort(reasoningEffort),
                                );
                              }
                            }
                          : null,
                      codexPermissionMode: _activeMode == ChatPageMode.codex
                          ? _codexPermissionMode
                          : null,
                      onCodexPermissionModeChanged:
                          _activeMode == ChatPageMode.codex
                          ? (mode) {
                              setState(() {
                                _codexPermissionMode = mode;
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
        if (!hideWorkspaceOverlays &&
            toolActivityCanExpand &&
            _isToolActivityExpanded)
          Positioned.fill(
            child: GestureDetector(
              behavior: HitTestBehavior.translucent,
              onTap: () => _setToolActivityExpanded(false),
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
        _buildBrowserOverlay(constraints),
      ],
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
          onSendFileToCurrentConversation:
              _sendWorkspaceFileToCurrentConversation,
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
    final shouldLiftComposerForKeyboard =
        _inputFocusNode.hasFocus || _editingUserMessageId != null;
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

    return ValueListenableBuilder<AppBackgroundConfig>(
      valueListenable: AppBackgroundService.notifier,
      builder: (context, backgroundConfig, _) {
        final backgroundActive = backgroundConfig.isActive;
        return ValueListenableBuilder<AppBackgroundVisualProfile>(
          valueListenable: AppBackgroundService.visualProfileNotifier,
          builder: (context, visualProfile, _) {
            return PopScope(
              canPop: false,
              onPopInvokedWithResult: (didPop, _) {
                if (didPop) return;
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
                unawaited(saveConversationWithSummary());
                if (GoRouterManager.canPop()) {
                  GoRouterManager.pop();
                  return;
                }
                unawaited(AppStateService.exitApp());
              },
              child: Scaffold(
                key: _scaffoldKey,
                backgroundColor: Colors.transparent,
                resizeToAvoidBottomInset: false,
                drawer: isHdPadLandscape
                    ? null
                    : HomeDrawer(
                        key: _drawerKey,
                        newConversationMode: _conversationModeForPageMode(
                          _activeMode,
                        ),
                      ),
                onDrawerChanged: (isOpen) {
                  if (isHdPadLandscape) {
                    return;
                  }
                  if (isOpen) {
                    _dismissChatInputFocus();
                    _drawerKey.currentState?.reloadConversations();
                  } else {
                    checkAndHandleDeletedConversation();
                  }
                },
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
                  ],
                ),
              ),
            );
          },
        );
      },
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
          final trackedConversation =
              _currentConversationByMode[ChatPageMode.normal];
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
            if ((_currentConversationByMode[ChatPageMode.normal]?.id ?? 0) ==
                conversation.id) {
              _currentConversationByMode[ChatPageMode.normal] =
                  updatedConversation;
            }
            if ((_currentConversation?.id ?? 0) == conversation.id) {
              _currentConversation = updatedConversation;
            }
          });
          if ((_currentConversationIdByMode[ChatPageMode.normal] ?? 0) ==
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
    LongPressStartDetails details,
  ) async {
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
      showEditAction: _canEditUserMessage(message),
      showRetryAction: _canRetryUserMessage(message),
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

  void _startEditingLatestUserMessage(ChatMessageModel message) {
    if (!_isLatestUserMessage(message)) {
      showToast(
        'Only the latest user message can be edited',
        type: ToastType.warning,
      );
      return;
    }
    final originalText = message.text ?? '';
    setState(() {
      _editingUserMessageId = message.id;
      _editingUserMessageController.value = TextEditingValue(
        text: originalText,
        selection: TextSelection.collapsed(offset: originalText.length),
      );
    });
  }

  void _cancelUserMessageEditing() {
    if (_editingUserMessageId == null &&
        _editingUserMessageController.text.isEmpty) {
      return;
    }
    setState(() {
      _editingUserMessageId = null;
      _editingUserMessageController.clear();
    });
  }

  Future<void> _saveAndResendEditedUserMessage(ChatMessageModel message) async {
    if (_editingUserMessageId != message.id) return;
    if (!_isLatestUserMessage(message)) {
      _cancelUserMessageEditing();
      showToast(
        'Only the latest user message can be edited',
        type: ToastType.warning,
      );
      return;
    }

    final editedText = _editingUserMessageController.text.trim();
    final attachments = _extractRetryAttachments(message);
    if (editedText.isEmpty && attachments.isEmpty) {
      showToast('No content to send after editing', type: ToastType.warning);
      return;
    }
    if (!await _ensureNormalChatModelConfigurationForSend()) {
      return;
    }

    _cancelUserMessageEditing();
    if (!mounted) return;

    await _clearRetriedMessageRound(message);
    if (!mounted) return;

    await _retryUserMessageText(editedText, attachments: attachments);
  }

  int _retryMessageRoundLength(ChatMessageModel message) {
    if (!_canRetryUserMessage(message)) return 0;
    final targetIndex = _messages.indexWhere((item) => item.id == message.id);
    if (targetIndex == -1) return 0;
    return targetIndex + 1;
  }

  Future<void> _clearRetriedMessageRound(ChatMessageModel message) async {
    if (_isAiResponding) {
      _onCancelTask();
      if (!mounted) return;
    }

    final removeCount = _retryMessageRoundLength(message);
    if (removeCount <= 0) return;

    final shouldClearEditState = _editingUserMessageId == message.id;
    setState(() {
      if (shouldClearEditState) {
        _editingUserMessageId = null;
        _editingUserMessageController.clear();
      }
      _messages.removeRange(0, removeCount);
    });

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
      _cancelUserMessageEditing();
      if (!mounted) return;
    }

    await _clearRetriedMessageRound(message);
    if (!mounted) return;

    await _retryUserMessageText(text, attachments: attachments);
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
    content['agentRetryStatusText'] = LegacyTextLocalizer.isEnglish
        ? 'Retrying connection...'
        : '连接中断，正在重试…';
    content['agentRetryCount'] = 0;
    content['agentMaxRetries'] =
        (content['agentMaxRetries'] as num?)?.toInt() ?? 3;
    content['agentRetryDelayMs'] = 0;
    content.remove('agentRetryReason');
    content.remove('agentRetryable');
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

class _UserMessageQuickMenuContent extends StatelessWidget {
  const _UserMessageQuickMenuContent({
    required this.width,
    required this.showEditAction,
    required this.showRetryAction,
  });

  final double width;
  final bool showEditAction;
  final bool showRetryAction;

  void _select(BuildContext context, _UserMessageQuickAction action) {
    Navigator.of(context).pop(action);
  }

  Widget _buildAction(
    BuildContext context, {
    required IconData icon,
    required String label,
    required VoidCallback onTap,
  }) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final foregroundColor = isDark
        ? palette.textPrimary
        : const Color(0xFF172033);
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(16),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Row(
          children: [
            Icon(icon, size: 18, color: foregroundColor),
            const SizedBox(width: 10),
            Text(
              label,
              style: TextStyle(
                fontSize: 14,
                color: foregroundColor,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final dividerColor = context.isDarkTheme
        ? palette.borderSubtle.withValues(alpha: 0.58)
        : Colors.white.withValues(alpha: 0.62);
    return SizedBox(
      width: width,
      child: OmniGlassPanel(
        borderRadius: BorderRadius.circular(18),
        child: Material(
          color: Colors.transparent,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _buildAction(
                context,
                icon: Icons.content_copy_rounded,
                label: LegacyTextLocalizer.isEnglish ? 'Copy' : '复制',
                onTap: () => _select(context, _UserMessageQuickAction.copy),
              ),
              if (showEditAction) ...[
                Divider(height: 1, thickness: 1, color: dividerColor),
                _buildAction(
                  context,
                  icon: Icons.edit_outlined,
                  label: LegacyTextLocalizer.isEnglish ? 'Edit' : '编辑',
                  onTap: () => _select(context, _UserMessageQuickAction.edit),
                ),
              ],
              if (showRetryAction) ...[
                Divider(height: 1, thickness: 1, color: dividerColor),
                _buildAction(
                  context,
                  icon: Icons.refresh_rounded,
                  label: LegacyTextLocalizer.isEnglish ? 'Retry' : '重试这条消息',
                  onTap: () => _select(context, _UserMessageQuickAction.retry),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _PaneResizeHandle extends StatelessWidget {
  const _PaneResizeHandle({
    required this.onDragUpdate,
    this.onDragStart,
    this.onDragEnd,
  });

  final ValueChanged<double> onDragUpdate;
  final VoidCallback? onDragStart;
  final VoidCallback? onDragEnd;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onHorizontalDragStart: (_) => onDragStart?.call(),
      onHorizontalDragUpdate: (details) => onDragUpdate(details.delta.dx),
      onHorizontalDragEnd: (_) => onDragEnd?.call(),
      onHorizontalDragCancel: () => onDragEnd?.call(),
      child: const SizedBox(
        width: HdPadPaneLayoutResolver.dividerHitWidth,
        child: Center(
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: Color(0xFFD7E5FB),
              borderRadius: BorderRadius.all(Radius.circular(999)),
            ),
            child: SizedBox(width: 3, height: 52),
          ),
        ),
      ),
    );
  }
}

class _ContextThresholdSheet extends StatefulWidget {
  const _ContextThresholdSheet({
    required this.initialThreshold,
    required this.currentUsageTokens,
    required this.onThresholdSaved,
  });

  final int initialThreshold;
  final int currentUsageTokens;
  final Future<bool> Function(int threshold) onThresholdSaved;

  @override
  State<_ContextThresholdSheet> createState() => _ContextThresholdSheetState();
}

class _ContextThresholdSheetState extends State<_ContextThresholdSheet> {
  late final TextEditingController _controller;
  final FocusNode _focusNode = FocusNode();
  Timer? _autoSaveTimer;
  String? _errorText;
  String? _saveErrorText;
  late double _draftThreshold;
  late int _lastSavedThreshold;
  bool _isSaving = false;
  int? _queuedThreshold;

  static const List<int> _presets = <int>[
    32000,
    64000,
    128000,
    256000,
    512000,
    1000000,
  ];

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(
      text: widget.initialThreshold.toString(),
    );
    _draftThreshold = widget.initialThreshold.toDouble();
    _lastSavedThreshold = widget.initialThreshold;
    _focusNode.addListener(_handleFocusChange);
  }

  @override
  void dispose() {
    _autoSaveTimer?.cancel();
    _focusNode.removeListener(_handleFocusChange);
    _focusNode.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _handleFocusChange() {
    if (_focusNode.hasFocus) {
      return;
    }
    final parsed = _parseInput(showEmptyError: false);
    if (parsed != null) {
      unawaited(_commitThreshold(parsed));
    }
  }

  Future<bool> _handleWillPop() async {
    await _flushPendingAutoSave();
    return true;
  }

  void _updateDraftThreshold(
    double value, {
    bool updateText = true,
    bool clearError = true,
  }) {
    final normalized = value.round().clamp(
      _kMinContextTokenThreshold,
      _kMaxContextTokenThreshold,
    );
    setState(() {
      _draftThreshold = normalized.toDouble();
      if (updateText) {
        final text = normalized.toString();
        _controller.value = TextEditingValue(
          text: text,
          selection: TextSelection.collapsed(offset: text.length),
        );
      }
      if (clearError) {
        _errorText = null;
      }
      _saveErrorText = null;
    });
  }

  int? _parseInput({bool showEmptyError = true}) {
    final raw = _controller.text.trim();
    if (raw.isEmpty) {
      if (showEmptyError) {
        setState(() {
          _errorText = LegacyTextLocalizer.isEnglish
              ? 'Please enter a threshold'
              : '请输入阈值';
        });
      }
      return null;
    }
    final parsed = int.tryParse(raw);
    if (parsed == null) {
      setState(() {
        _errorText = LegacyTextLocalizer.isEnglish
            ? 'Threshold must be an integer'
            : '阈值必须是整数';
      });
      return null;
    }
    if (parsed < _kMinContextTokenThreshold ||
        parsed > _kMaxContextTokenThreshold) {
      setState(() {
        _errorText = LegacyTextLocalizer.isEnglish
            ? 'Threshold range: $_kMinContextTokenThreshold to $_kMaxContextTokenThreshold'
            : '阈值范围为 $_kMinContextTokenThreshold 到 $_kMaxContextTokenThreshold';
      });
      return null;
    }
    setState(() {
      _errorText = null;
      _draftThreshold = parsed.toDouble();
    });
    return parsed;
  }

  void _scheduleAutoSave() {
    _autoSaveTimer?.cancel();
    final parsed = _parseInput(showEmptyError: false);
    if (parsed == null || parsed == _lastSavedThreshold) {
      return;
    }
    _autoSaveTimer = Timer(const Duration(milliseconds: 320), () {
      unawaited(_commitThreshold(parsed));
    });
  }

  Future<void> _commitThreshold([int? value]) async {
    final parsed = value ?? _parseInput();
    if (parsed == null || parsed == _lastSavedThreshold) {
      return;
    }
    _autoSaveTimer?.cancel();
    _queuedThreshold = parsed;
    if (_isSaving) {
      return;
    }

    while (_queuedThreshold != null) {
      final target = _queuedThreshold!;
      _queuedThreshold = null;
      if (!mounted) {
        return;
      }
      setState(() {
        _isSaving = true;
        _saveErrorText = null;
      });
      final success = await widget.onThresholdSaved(target);
      if (!mounted) {
        return;
      }
      if (success) {
        setState(() {
          _lastSavedThreshold = target;
          _isSaving = false;
          _saveErrorText = null;
        });
        continue;
      }
      setState(() {
        _isSaving = false;
        _saveErrorText = LegacyTextLocalizer.isEnglish
            ? 'Auto-save failed, please try again later'
            : '自动保存失败，请稍后重试';
      });
      break;
    }
  }

  Future<void> _flushPendingAutoSave() async {
    _autoSaveTimer?.cancel();
    final parsed = _parseInput(showEmptyError: false);
    if (parsed != null && parsed != _lastSavedThreshold) {
      await _commitThreshold(parsed);
    }
  }

  String _formatThresholdLabel(int threshold) {
    if (threshold >= 1000000) {
      final millions = threshold / 1000000;
      return millions % 1 == 0
          ? '${millions.toStringAsFixed(0)}M'
          : '${millions.toStringAsFixed(1)}M';
    }
    if (threshold >= 1000) {
      final kilo = threshold / 1000;
      return kilo % 1 == 0
          ? '${kilo.toStringAsFixed(0)}k'
          : '${kilo.toStringAsFixed(1)}k';
    }
    return threshold.toString();
  }

  String _formatTokenCount(int value) {
    return value.toString().replaceAllMapped(
      RegExp(r'\B(?=(\d{3})+(?!\d))'),
      (_) => ',',
    );
  }

  String _formatUsagePercent(double ratio) {
    if (!ratio.isFinite) {
      return '0%';
    }
    final percent = ratio * 100;
    final rounded = percent >= 100 || percent % 1 == 0
        ? percent.toStringAsFixed(0)
        : percent.toStringAsFixed(1);
    return '$rounded%';
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;
    final draftThreshold = _draftThreshold.round();
    final usageRatio = widget.currentUsageTokens <= 0
        ? 0.0
        : widget.currentUsageTokens / draftThreshold;
    final dividerColor = isDark
        ? palette.borderSubtle
        : palette.borderSubtle.withValues(alpha: 0.9);
    final accentColor = palette.accentPrimary;
    final warningColor = isDark
        ? const Color(0xFFE0A06A)
        : const Color(0xFFD65A3A);
    final successColor = isDark
        ? const Color(0xFF8DBB95)
        : const Color(0xFF2F8F6B);
    final pendingAutoSave = _autoSaveTimer?.isActive ?? false;
    final statusText = switch ((_saveErrorText, _isSaving, pendingAutoSave)) {
      (final String message?, _, _) => message,
      (_, true, _) => LegacyTextLocalizer.isEnglish ? 'Saving…' : '正在自动保存…',
      (_, false, true) =>
        LegacyTextLocalizer.isEnglish ? 'Pending auto-save' : '即将自动保存',
      _ =>
        draftThreshold == _lastSavedThreshold
            ? (LegacyTextLocalizer.isEnglish ? 'Auto-saved' : '已自动保存')
            : (LegacyTextLocalizer.isEnglish
                  ? 'Auto-save on change'
                  : '修改后自动保存'),
    };
    final statusColor = _saveErrorText != null
        ? warningColor
        : _isSaving || pendingAutoSave
        ? accentColor
        : palette.textSecondary;
    final navigator = Navigator.of(context);

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        unawaited(
          _handleWillPop().then((shouldPop) {
            if (shouldPop && mounted) {
              navigator.pop();
            }
          }),
        );
      },
      child: SafeArea(
        top: false,
        child: Padding(
          padding: EdgeInsets.only(top: 12, bottom: bottomInset),
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: palette.surfacePrimary,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(28),
              ),
              border: Border(top: BorderSide(color: dividerColor)),
              boxShadow: isDark
                  ? const []
                  : [
                      BoxShadow(
                        color: palette.shadowColor.withValues(alpha: 0.18),
                        blurRadius: 24,
                        offset: const Offset(0, -8),
                      ),
                    ],
            ),
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Center(
                    child: Container(
                      width: 38,
                      height: 4,
                      decoration: BoxDecoration(
                        color: palette.borderStrong,
                        borderRadius: BorderRadius.circular(999),
                      ),
                    ),
                  ),
                  const SizedBox(height: 18),
                  Text(
                    LegacyTextLocalizer.isEnglish
                        ? 'Adjust Context Threshold'
                        : '调整上下文阈值',
                    style: TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                      color: palette.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    LegacyTextLocalizer.isEnglish
                        ? 'Changes are auto-saved. The new threshold takes effect immediately.'
                        : '修改后自动保存，新的阈值会立刻用于当前对话。',
                    style: TextStyle(
                      fontSize: 13,
                      height: 1.4,
                      color: palette.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 18),
                  Container(
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    decoration: BoxDecoration(
                      border: Border(
                        top: BorderSide(color: dividerColor),
                        bottom: BorderSide(color: dividerColor),
                      ),
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: _ThresholdMetric(
                            label: LegacyTextLocalizer.isEnglish
                                ? 'Current context'
                                : '当前上下文',
                            value: _formatTokenCount(widget.currentUsageTokens),
                            accent: palette.textPrimary,
                          ),
                        ),
                        Container(width: 1, height: 38, color: dividerColor),
                        Expanded(
                          child: _ThresholdMetric(
                            label: LegacyTextLocalizer.isEnglish
                                ? 'Target threshold'
                                : '目标阈值',
                            value: _formatTokenCount(draftThreshold),
                            accent: accentColor,
                          ),
                        ),
                        Container(width: 1, height: 38, color: dividerColor),
                        Expanded(
                          child: _ThresholdMetric(
                            label: LegacyTextLocalizer.isEnglish
                                ? 'Usage'
                                : '占用比例',
                            value: _formatUsagePercent(usageRatio),
                            accent: usageRatio >= 1
                                ? warningColor
                                : successColor,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 18),
                  SliderTheme(
                    data: SliderTheme.of(context).copyWith(
                      activeTrackColor: accentColor,
                      inactiveTrackColor: palette.segmentTrack,
                      thumbColor: palette.surfacePrimary,
                      overlayColor: accentColor.withValues(alpha: 0.12),
                      thumbShape: const RoundSliderThumbShape(
                        enabledThumbRadius: 10,
                      ),
                      trackHeight: 4,
                    ),
                    child: Slider(
                      min: _kMinContextTokenThreshold.toDouble(),
                      max: _kMaxContextTokenThreshold.toDouble(),
                      divisions:
                          (_kMaxContextTokenThreshold -
                              _kMinContextTokenThreshold) ~/
                          1000,
                      value: _draftThreshold.clamp(
                        _kMinContextTokenThreshold.toDouble(),
                        _kMaxContextTokenThreshold.toDouble(),
                      ),
                      onChanged: (value) => _updateDraftThreshold(value),
                      onChangeEnd: (value) {
                        _updateDraftThreshold(value);
                        unawaited(_commitThreshold(value.round()));
                      },
                    ),
                  ),
                  const SizedBox(height: 4),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: _presets.map((preset) {
                      final selected = draftThreshold == preset;
                      final chipBackground = selected
                          ? accentColor
                          : palette.surfaceSecondary;
                      final chipBorder = selected ? accentColor : dividerColor;
                      final chipTextColor = selected
                          ? (isDark
                                ? Theme.of(context).colorScheme.onPrimary
                                : Colors.white)
                          : palette.textSecondary;
                      return InkWell(
                        borderRadius: BorderRadius.circular(999),
                        onTap: () {
                          _updateDraftThreshold(preset.toDouble());
                          unawaited(_commitThreshold(preset));
                        },
                        child: AnimatedContainer(
                          duration: const Duration(milliseconds: 180),
                          padding: const EdgeInsets.symmetric(
                            horizontal: 12,
                            vertical: 8,
                          ),
                          decoration: BoxDecoration(
                            color: chipBackground,
                            borderRadius: BorderRadius.circular(999),
                            border: Border.all(color: chipBorder),
                          ),
                          child: Text(
                            _formatThresholdLabel(preset),
                            style: TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                              color: chipTextColor,
                            ),
                          ),
                        ),
                      );
                    }).toList(),
                  ),
                  const SizedBox(height: 16),
                  TextField(
                    controller: _controller,
                    focusNode: _focusNode,
                    keyboardType: TextInputType.number,
                    inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                    decoration: InputDecoration(
                      labelText: '精确阈值',
                      hintText: _kDefaultContextTokenThreshold.toString(),
                      helperText:
                          '默认 $_kDefaultContextTokenThreshold，范围 $_kMinContextTokenThreshold - $_kMaxContextTokenThreshold',
                      errorText: _errorText,
                      filled: true,
                      fillColor: palette.surfaceSecondary,
                      labelStyle: TextStyle(color: palette.textSecondary),
                      hintStyle: TextStyle(color: palette.textTertiary),
                      helperStyle: TextStyle(
                        color: palette.textTertiary,
                        fontSize: 12,
                      ),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(16),
                        borderSide: BorderSide(color: dividerColor),
                      ),
                      enabledBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(16),
                        borderSide: BorderSide(color: dividerColor),
                      ),
                      focusedBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(16),
                        borderSide: BorderSide(color: accentColor, width: 1.4),
                      ),
                    ),
                    style: TextStyle(color: palette.textPrimary),
                    onChanged: (value) {
                      if (value.trim().isEmpty) {
                        _autoSaveTimer?.cancel();
                        setState(() {
                          _errorText = null;
                          _saveErrorText = null;
                        });
                        return;
                      }
                      final parsed = int.tryParse(value.trim());
                      if (parsed == null) {
                        return;
                      }
                      if (parsed < _kMinContextTokenThreshold ||
                          parsed > _kMaxContextTokenThreshold) {
                        _autoSaveTimer?.cancel();
                        setState(() {
                          _errorText =
                              '阈值范围为 $_kMinContextTokenThreshold 到 $_kMaxContextTokenThreshold';
                        });
                        return;
                      }
                      _updateDraftThreshold(
                        parsed.toDouble(),
                        updateText: false,
                      );
                      _scheduleAutoSave();
                    },
                    onSubmitted: (_) {
                      final parsed = _parseInput();
                      if (parsed != null) {
                        unawaited(_commitThreshold(parsed));
                      }
                    },
                  ),
                  const SizedBox(height: 14),
                  Row(
                    children: [
                      Icon(
                        _saveErrorText != null
                            ? Icons.error_outline_rounded
                            : _isSaving || pendingAutoSave
                            ? Icons.sync_rounded
                            : Icons.check_circle_outline_rounded,
                        size: 16,
                        color: statusColor,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          statusText,
                          style: TextStyle(
                            fontSize: 12,
                            color: statusColor,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ThresholdMetric extends StatelessWidget {
  const _ThresholdMetric({
    required this.label,
    required this.value,
    required this.accent,
  });

  final String label;
  final String value;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: palette.textSecondary,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            value,
            style: TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w700,
              color: accent,
            ),
          ),
        ],
      ),
    );
  }
}
