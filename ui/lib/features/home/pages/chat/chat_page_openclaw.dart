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
    _requestComposerFocus();
    _handleSlashCommandInput();
  }

  @override
  Future<void> _loadOpenClawConfig() async {
    try {
      final configuration = await OpenClawCredentialService.initializeAndLoad();
      if (!mounted) return;
      setState(() {
        _openClawConfiguration = configuration;
        _openClawBaseUrl = configuration.baseUrl;
        _openClawToken = '';
        _openClawUserId = configuration.userId;
      });
      await _ensureOpenClawUserId();
    } catch (e) {}
  }

  @override
  Future<void> _ensureOpenClawUserId() async {
    if (_openClawUserId.isNotEmpty) return;
    final generated = DateTime.now().microsecondsSinceEpoch.toString();
    if (!mounted) return;
    setState(() => _openClawUserId = generated);
  }

  @override
  void _handleSlashCommandInput() {
    if (_editingUserMessageId != null) {
      if (!mounted ||
          (!_showSlashCommandPanel &&
              !_showModelMentionPanel &&
              !_openClawPanelExpanded &&
              !_isSlashCommandExpanded)) {
        return;
      }
      setState(() {
        _showSlashCommandPanel = false;
        _showModelMentionPanel = false;
        _activeModelMentionToken = null;
        _openClawPanelExpanded = false;
        _slashCommandExpandedByMode[_activeMode] = false;
      });
      return;
    }
    final value = _messageController.value;
    final shouldShowSlash = value.text.trimLeft().startsWith('/');
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
        _isSlashCommandExpanded;
    if (!shouldUpdate) {
      return;
    }

    setState(() {
      _showSlashCommandPanel = nextSlashPanelVisible;
      _showModelMentionPanel = shouldShowModelMention;
      _activeModelMentionToken = nextMentionToken;
      _openClawPanelExpanded = nextOpenClawPanelExpanded;
      _slashCommandExpandedByMode[_activeMode] = false;
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
    final insideToolActivityStrip = _isPointerInside(
      _toolActivityStripKey,
      position,
    );
    final insideInputAuxiliarySurface =
        _isPointerInside(_openClawPanelKey, position) ||
        _isPointerInside(_slashCommandStripKey, position) ||
        insideToolActivityStrip;
    final insideHomeDrawerSearch = _isPointerInside(
      _drawerSearchFieldKey,
      position,
    );
    if (!insideInputArea &&
        !insideInputAuxiliarySurface &&
        !insideHomeDrawerSearch &&
        _inputFocusNode.hasFocus) {
      await SchedulerBinding.instance.endOfFrame;
      if (!mounted) {
        return;
      }
      // The pointer target may have transferred focus to another TextField
      // during this frame (notably HomeDrawer search). Do not race that
      // field's TextInput.show with a stale global TextInput.hide.
      if (_inputFocusNode.hasFocus && !_suppressNextOutsideTapKeyboardHide) {
        await SystemChannels.textInput.invokeMethod<void>('TextInput.hide');
      }
      _suppressNextOutsideTapKeyboardHide = false;
    }
    // 工具活动列表展开后,点击 strip 之外的任何位置都应该收起它——包括输入框
    // (输入框 tap 同时拿到焦点 + 让 strip 收起,不再走"先关 strip 再点一次"
    // 的两步流程)。strip 自己的命中区域不算 outside,避免 toggle 自己把自己关掉。
    if (_isToolActivityExpanded && !insideToolActivityStrip && mounted) {
      setState(() {
        _toolActivityExpandedByMode[_activeMode] = false;
      });
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
      final saved = await _applyOpenClawConfig(
        baseUrl: _openClawBaseUrlController.text.trim(),
        token: _openClawTokenController.text.trim(),
        userId: _openClawUserIdController.text.trim(),
        enable: _isOpenClawSurface,
      );
      if (saved) _checkOpenClawConnection();
    }
    _hideSlashCommandPanel();
  }

  @override
  Future<bool> _applyOpenClawConfig({
    required String baseUrl,
    required String token,
    String? userId,
    bool enable = true,
  }) async {
    final plan = await OpenClawCredentialService.prepareDestination(baseUrl);
    if (!mounted) return false;
    final effectiveUserId = userId?.trim().isNotEmpty == true
        ? userId!.trim()
        : _openClawUserId.trim();
    final outcome =
        await confirmDataDestinationAndRun<OpenClawConfigurationMutationResult>(
          context: context,
          rawEndpoint: plan.baseUrl,
          capability: 'OpenClaw Gateway',
          operation: LegacyTextLocalizer.isEnglish
              ? 'Save or enable configuration'
              : '保存或启用配置',
          dataTypes: [
            LegacyTextLocalizer.isEnglish
                ? 'Gateway credential, when configured'
                : 'Gateway 凭据（如已配置）',
            LegacyTextLocalizer.isEnglish
                ? 'Future prompts, conversation history, attachments, and device pairing metadata'
                : '启用后发送的提示词、对话历史、附件和设备配对元数据',
          ],
          action: () async {
            return OpenClawCredentialService.saveConfirmed(
              plan: plan,
              baseUrl: plan.baseUrl,
              userId: effectiveUserId,
              enable: _isOpenClawSurface && enable,
              replacementToken: token,
            );
          },
        );
    final mutation = outcome.value;
    if (!outcome.confirmed || mutation?.success != true) return false;
    final configuration = mutation!.configuration;
    if (configuration == null) return false;
    if (!mounted) return false;
    setState(() {
      _openClawConfiguration = configuration;
      _openClawBaseUrl = configuration.baseUrl;
      _openClawToken = '';
      _openClawTokenController.clear();
      _openClawUserId = configuration.userId;
    });
    return true;
  }

  @override
  Future<bool> _tryHandleSlashCommand(
    String messageText, {
    List<Map<String, dynamic>> attachments = const [],
  }) async {
    final trimmed = messageText.trim();
    if (!trimmed.startsWith('/')) return false;
    if (_activeMode == ChatPageMode.agent) {
      return _tryHandleAgentSlashCommand(trimmed, attachments: attachments);
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
        _showSnackBar('可用思考强度：no、low、high、xhigh、max');
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

    final saved = await _applyOpenClawConfig(
      baseUrl: baseUrl.trim(),
      token: token.trim(),
      userId: userId?.trim(),
      enable: true,
    );
    if (!saved) {
      _showSnackBar('未确认接收方，OpenClaw 未启用');
      return true;
    }
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
    await OpenClawConnectionChecker.checkAndToast(context, _openClawBaseUrl);
  }

  @override
  Future<void> _resetOpenClawDeviceIdentity() async {
    final result = await showOpenClawIdentityResetFlow(
      context: context,
      onLocalDisabled: () {
        // Native state is authoritative; the next snapshot refresh reflects
        // the disabled state without maintaining a duplicate local flag.
      },
    );
    if (!mounted || result == null) return;
    _showSnackBar(
      result.success
          ? (LegacyTextLocalizer.isEnglish
                ? 'Device identity reset. Restart or reconnect OpenClaw.'
                : '设备身份已重置，请重启或重新连接 OpenClaw。')
          : (LegacyTextLocalizer.isEnglish
                ? 'Reset was not verified (${result.status}); OpenClaw remains disabled.'
                : '无法验证重置结果（${result.status}）；OpenClaw 保持停用。'),
    );
  }
}
