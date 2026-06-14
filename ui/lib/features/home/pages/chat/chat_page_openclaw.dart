part of 'chat_page.dart';

mixin _ChatPageOpenClawMixin on _ChatPageStateBase {
  @override
  bool get _supportsManualContextCompaction => !_isOpenClawSurface;

  @override
  void _triggerSlashCommandPanel() {
    final currentText = _messageController.text;
    final slashPrefixed = currentText.trimLeft().startsWith('/');
    if (!slashPrefixed) {
      _messageController.value = const TextEditingValue(
        text: '/',
        selection: TextSelection.collapsed(offset: 1),
      );
    } else {
      _messageController.selection = TextSelection.collapsed(
        offset: currentText.length,
      );
    }
    _inputFocusNode.requestFocus();
    _handleSlashCommandInput();
  }

  @override
  Future<void> _loadOpenClawConfig() async {
    try {
      final enabled =
          StorageService.getBool(kOpenClawEnabledKey, defaultValue: false) ??
          false;
      final baseUrl =
          StorageService.getString(kOpenClawBaseUrlKey, defaultValue: '') ?? '';
      final token =
          StorageService.getString(kOpenClawTokenKey, defaultValue: '') ?? '';
      final userId =
          StorageService.getString(kOpenClawUserIdKey, defaultValue: '') ?? '';
      final effectiveEnabled = enabled && baseUrl.trim().isNotEmpty;
      if (enabled && !effectiveEnabled) {
        await StorageService.setBool(kOpenClawEnabledKey, false);
      }
      if (!mounted) return;
      setState(() {
        _openClawEnabled = effectiveEnabled;
        _openClawBaseUrl = baseUrl;
        _openClawToken = token;
        _openClawUserId = userId;
      });
      await _ensureOpenClawUserId();
    } catch (e) {
      debugPrint('加载OpenClaw配置失败: $e');
    }
  }

  @override
  Future<void> _ensureOpenClawUserId() async {
    if (_openClawUserId.isNotEmpty) return;
    final existing =
        StorageService.getString(kOpenClawUserIdKey, defaultValue: '') ?? '';
    if (existing.isNotEmpty) {
      if (!mounted) return;
      setState(() => _openClawUserId = existing);
      return;
    }
    final generated = DateTime.now().microsecondsSinceEpoch.toString();
    await StorageService.setString(kOpenClawUserIdKey, generated);
    if (!mounted) return;
    setState(() => _openClawUserId = generated);
  }

  @override
  void _handleSlashCommandInput() {
    final value = _messageController.value;
    final shouldShowSlash = value.text.trimLeft().startsWith('/');
    final slashRoute = _resolveSlashCommandPanelRoute(value.text);
    final nextMentionToken = shouldShowSlash
        ? null
        : _parseActiveModelMentionToken(value);
    final shouldShowModelMention = nextMentionToken != null;
    final shouldCollapsePanels = !_isOpenClawSurface;
    final nextOpenClawPanelExpanded = shouldCollapsePanels
        ? false
        : _openClawPanelExpanded;
    final nextSlashPanelVisible =
        shouldShowSlash || shouldShowModelMention || nextOpenClawPanelExpanded;

    if (!mounted) return;

    final shouldUpdate =
        nextSlashPanelVisible != _showSlashCommandPanel ||
        shouldShowModelMention != _showModelMentionPanel ||
        nextMentionToken != _activeModelMentionToken ||
        nextOpenClawPanelExpanded != _openClawPanelExpanded ||
        _isSlashCommandExpanded !=
            (shouldShowSlash && slashRoute == _SlashCommandPanelRoute.effort);
    if (!shouldUpdate) {
      return;
    }

    setState(() {
      _showSlashCommandPanel = nextSlashPanelVisible;
      _showModelMentionPanel = shouldShowModelMention;
      _activeModelMentionToken = nextMentionToken;
      _openClawPanelExpanded = nextOpenClawPanelExpanded;
      _slashCommandExpandedByMode[_activeMode] =
          shouldShowSlash && slashRoute == _SlashCommandPanelRoute.effort;
    });
  }

  @override
  void _showOpenClawCommandPanel({bool expand = false}) {
    if (!_isOpenClawSurface) {
      _showSnackBar('OpenClaw 页面当前已隐藏');
      return;
    }
    if (!mounted) return;
    setState(() {
      _showSlashCommandPanel = true;
      _showModelMentionPanel = false;
      _activeModelMentionToken = null;
      _openClawPanelExpanded = expand;
      if (expand) {
        _openClawBaseUrlController.text = _openClawBaseUrl;
        _openClawTokenController.text = _openClawToken;
        _openClawUserIdController.text = _openClawUserId;
      }
    });
  }

  @override
  void _hideSlashCommandPanel() {
    if (!mounted) return;
    setState(() {
      _showSlashCommandPanel = false;
      _showModelMentionPanel = false;
      _openClawPanelExpanded = false;
      _slashCommandExpandedByMode[_activeMode] = false;
    });
  }

  @override
  bool _isPointerInside(GlobalKey key, Offset position) {
    final context = key.currentContext;
    if (context == null) return false;
    final renderBox = context.findRenderObject() as RenderBox?;
    if (renderBox == null || !renderBox.hasSize) return false;
    final offset = renderBox.localToGlobal(Offset.zero);
    final rect = offset & renderBox.size;
    return rect.contains(position);
  }

  @override
  Future<void> _handleOutsideTap(Offset position) async {
    final insideInputArea = _isPointerInside(_inputAreaKey, position);
    final insideInputAuxiliarySurface =
        _isPointerInside(_openClawPanelKey, position) ||
        _isPointerInside(_slashCommandStripKey, position);
    if (!insideInputArea &&
        !insideInputAuxiliarySurface &&
        _inputFocusNode.hasFocus) {
      await SchedulerBinding.instance.endOfFrame;
      if (!_suppressNextOutsideTapKeyboardHide) {
        await SystemChannels.textInput.invokeMethod<void>('TextInput.hide');
      }
      _suppressNextOutsideTapKeyboardHide = false;
    }
    if (!_showSlashCommandPanel &&
        !_showModelMentionPanel &&
        !_openClawPanelExpanded) {
      return;
    }
    if (insideInputArea || insideInputAuxiliarySurface) {
      _suppressNextOutsideTapKeyboardHide = false;
      return;
    }
    if (_openClawPanelExpanded) {
      await _applyOpenClawConfig(
        baseUrl: _openClawBaseUrlController.text.trim(),
        token: _openClawTokenController.text.trim(),
        userId: _openClawUserIdController.text.trim(),
        enable: _isOpenClawSurface,
      );
      _checkOpenClawConnection();
    }
    _hideSlashCommandPanel();
  }

  @override
  Future<void> _applyOpenClawConfig({
    required String baseUrl,
    required String token,
    String? userId,
    bool enable = true,
  }) async {
    await StorageService.setString(kOpenClawBaseUrlKey, baseUrl);
    await StorageService.setString(kOpenClawTokenKey, token);
    if (userId != null && userId.isNotEmpty) {
      await StorageService.setString(kOpenClawUserIdKey, userId);
    }
    if (!mounted) return;
    setState(() {
      _openClawBaseUrl = baseUrl;
      _openClawToken = token;
      if (userId != null && userId.isNotEmpty) {
        _openClawUserId = userId;
      }
      _openClawEnabled =
          _isOpenClawSurface && enable && baseUrl.trim().isNotEmpty;
    });
    await StorageService.setBool(kOpenClawEnabledKey, _openClawEnabled);
    await _ensureOpenClawUserId();
  }

  @override
  Future<bool> _tryHandleSlashCommand(String messageText) async {
    final trimmed = messageText.trim();
    if (!trimmed.startsWith('/')) return false;
    if (_activeMode == ChatPageMode.codex) {
      return _tryHandleCodexSlashCommand(trimmed);
    }

    if (trimmed == '/compact' || trimmed.startsWith('/compact ')) {
      await _executeManualContextCompactionCommand();
      return true;
    }

    if (trimmed == '/effort') {
      _triggerSlashCommandPanel();
      return true;
    }
    if (trimmed.startsWith('/effort ')) {
      if (!_supportsReasoningEffortCommand) {
        _messageController.clear();
        _hideSlashCommandPanel();
        _showSnackBar('当前模式暂不支持 /effort');
        return true;
      }
      final effort = _normalizeReasoningEffort(
        trimmed.substring('/effort'.length).trimLeft(),
      );
      if (effort == null) {
        _showSnackBar('可用思考强度：no、low、high、max（兼容 xhigh）');
        return true;
      }
      await _applyConversationReasoningEffort(effort);
      _messageController.clear();
      _hideSlashCommandPanel();
      return true;
    }

    if (!trimmed.startsWith('/openclaw')) {
      return false;
    }

    if (!_isOpenClawSurface) {
      _showSnackBar('OpenClaw 页面当前已隐藏，/openclaw 暂不可用');
      return true;
    }

    final parts = trimmed.split(RegExp(r'\s+'));
    if (parts.length < 2) {
      _showSnackBar('格式: /openclaw <baseurl> --token <token> <userid>');
      return true;
    }

    final baseUrl = parts[1];
    final tokenIndex = parts.indexOf('--token');
    if (tokenIndex == -1) {
      _showSnackBar('请在命令中显式包含 --token');
      return true;
    }
    String token = '';
    String? userId;
    if (tokenIndex + 1 < parts.length) {
      token = parts[tokenIndex + 1];
    }
    if (token == '-' || token == 'null') {
      token = '';
    }
    if (tokenIndex + 2 < parts.length) {
      userId = parts[tokenIndex + 2];
    }

    if (baseUrl.trim().isEmpty) {
      _showSnackBar('OpenClaw baseurl 不能为空');
      return true;
    }

    await _applyOpenClawConfig(
      baseUrl: baseUrl.trim(),
      token: token.trim(),
      userId: userId?.trim(),
      enable: true,
    );
    _messageController.clear();
    _inputFocusNode.unfocus();
    _hideSlashCommandPanel();
    _showSnackBar('OpenClaw 已配置并启用');
    return true;
  }

  @override
  Future<void> _executeManualContextCompactionCommand() async {
    if (!_supportsManualContextCompaction) {
      _messageController.clear();
      _hideSlashCommandPanel();
      showToast('当前模式暂不支持 /compact', type: ToastType.warning);
      return;
    }

    final runtime = _runtimeForMode(_activeMode);
    if ((runtime?.hasInFlightTask ?? false) ||
        _isAiResponding ||
        _isExecutingTask ||
        _isCheckingExecutableTask) {
      _messageController.clear();
      _hideSlashCommandPanel();
      showToast('请等待当前任务结束后再压缩', type: ToastType.warning);
      return;
    }

    try {
      await _ensureActiveConversationReadyForStreaming();
    } catch (_) {
      _messageController.clear();
      _hideSlashCommandPanel();
      showToast('当前对话尚未准备好', type: ToastType.warning);
      return;
    }

    final conversationId = _currentConversationId;
    if (conversationId == null) {
      _messageController.clear();
      _hideSlashCommandPanel();
      showToast('当前暂无可压缩的上下文', type: ToastType.warning);
      return;
    }
    final modeKey = _modeKey(_activeMode);
    final conversationMode = activeConversationModeValue.storageValue;
    final latestPromptTokens = _currentConversation?.latestPromptTokens;
    final promptTokenThreshold = _currentConversation?.promptTokenThreshold;
    final modelOverride =
        activeConversationModeValue == ConversationMode.chatOnly
        ? _buildChatModelOverridePayload()
        : _buildAgentModelOverridePayload();
    final reasoningEffort = _activeConversationReasoningEffort;

    _messageController.clear();
    _inputFocusNode.unfocus();
    _hideSlashCommandPanel();

    _runtimeCoordinator.beginContextCompaction(
      conversationId: conversationId,
      mode: modeKey,
      trigger: 'manual',
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
    );

    try {
      final result = await AssistsMessageService.compactConversationContext(
        conversationId: conversationId,
        conversationMode: conversationMode,
        modelOverride: modelOverride,
        reasoningEffort: reasoningEffort,
      );
      final conversationPayload = result['conversation'];
      if (conversationPayload is Map) {
        final updatedConversation = ConversationModel.fromJson(
          Map<String, dynamic>.from(conversationPayload),
        );
        _currentConversation = updatedConversation;
        _syncRuntimeSnapshotForMode(
          _activeMode,
          conversation: updatedConversation,
        );
      }
      final compacted = result['compacted'] == true;
      final reason = (result['reason'] ?? '').toString().trim();
      final status = compacted
          ? 'completed'
          : reason == 'no_candidate' || reason == 'no_prompt_messages'
          ? 'noop'
          : 'failed';
      _runtimeCoordinator.finishContextCompaction(
        conversationId: conversationId,
        mode: modeKey,
        status: status,
        latestPromptTokens: latestPromptTokens,
        promptTokenThreshold: promptTokenThreshold,
      );
      if (!mounted) return;
      if (compacted) {
        showToast('上下文已压缩', type: ToastType.success);
      } else if (status == 'noop') {
        showToast('当前暂无可压缩的上下文', type: ToastType.warning);
      } else {
        showToast('上下文压缩失败', type: ToastType.error);
      }
    } catch (_) {
      _runtimeCoordinator.finishContextCompaction(
        conversationId: conversationId,
        mode: modeKey,
        status: 'failed',
        latestPromptTokens: latestPromptTokens,
        promptTokenThreshold: promptTokenThreshold,
      );
      if (mounted) {
        showToast('上下文压缩失败', type: ToastType.error);
      }
    }
  }

  @override
  Future<void> _checkOpenClawConnection() async {
    await OpenClawConnectionChecker.checkAndToast(_openClawBaseUrl);
  }
}
