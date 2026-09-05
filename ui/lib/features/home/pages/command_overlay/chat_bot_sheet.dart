import 'dart:async';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_link_preview.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'widgets/message_bubble.dart';
import 'widgets/chat_input_area.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_flow_controller.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_result_card.dart';
import 'package:ui/features/task/run_log/omniflow_tool_client.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/utils/deep_thinking_persistence.dart';
import 'package:ui/features/home/pages/chat/utils/keyboard_inset_motion_tracker.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_group_message.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_empty_greeting.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/screen_dialog_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/scene_model_config_service.dart';
import 'package:ui/services/home_greeting_settings_service.dart';
import 'package:ui/services/link_preview_service.dart';
import 'package:ui/widgets/ai_generated_badge.dart';
import 'package:ui/constants/openclaw/openclaw_keys.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/theme/theme_context.dart';

/// 聊天上下文存储的key
const String kChatContextStorageKey = 'chat_context_for_summary';
const String kChatResumeAfterAuthKey = 'chat_resume_after_auth';

/// 启动场景类型
enum ChatBotLaunchScene {
  /// 普通场景
  normal,

  /// 总结场景
  summary,

  /// 授权后恢复场景
  resumeAfterAuth,
}

class ChatBotSheet extends StatefulWidget {
  final String? initialMessage;
  final List<Map<String, dynamic>> initialAttachments;

  /// 启动场景，用于控制是否加载之前保存的上下文
  final ChatBotLaunchScene launchScene;
  final bool? openClawEnabled;

  const ChatBotSheet({
    super.key,
    this.initialMessage,
    this.initialAttachments = const [],
    this.launchScene = ChatBotLaunchScene.normal,
    this.openClawEnabled,
  });

  @override
  State<ChatBotSheet> createState() => _ChatBotSheetState();
}

class _ChatBotSheetState extends State<ChatBotSheet>
    with WidgetsBindingObserver {
  final TextEditingController _messageController = TextEditingController();
  final ScrollController _messageScrollController = ScrollController();
  final DraggableScrollableController _sheetController =
      DraggableScrollableController();
  final List<ChatMessageModel> _messages = [];
  final FocusNode _inputFocusNode = FocusNode();
  final GlobalKey<ChatInputAreaState> _chatInputAreaKey =
      GlobalKey<ChatInputAreaState>();
  final KeyboardInsetMotionTracker _emptyGreetingKeyboardLiftTracker =
      KeyboardInsetMotionTracker();
  final List<ChatInputAttachment> _pendingAttachments = <ChatInputAttachment>[];

  bool _isAiResponding = false;
  bool _isCheckingExecutableTask = false; // 是否正在判断可执行任务
  bool _isPopupVisible = false;

  final Map<String, String> _currentAiMessages = {};
  final Set<String> _expandedAgentRunTaskIds = <String>{};
  bool _autoStickMessageListToLatest = true;
  bool _messageStickToLatestScheduled = false;
  bool _messageListScrollWasUserDriven = false;
  static const double _messageLatestEdgeTolerance = 48.0;

  String? _currentDispatchTurnId;
  String? _acpSessionId;
  String? _acpPromptId;
  String? _activeAcpAgentId;
  bool _closeRequested = false;
  bool _cancelRequested = false;
  bool _acpCloseStarted = false;
  StreamSubscription<Map<String, dynamic>>? _acpRuntimeSubscription;
  final ChatConversationRuntimeCoordinator _runtimeCoordinator =
      ChatConversationRuntimeCoordinator.instance;
  static const String _runtimeMode = 'command_overlay';

  // OpenClaw 配置与开关
  bool _openClawEnabled = false;
  String _openClawBaseUrl = '';
  String _openClawToken = '';
  String _openClawUserId = '';
  bool _showSlashCommandPanel = false;
  bool _openClawPanelExpanded = false;
  final TextEditingController _openClawBaseUrlController =
      TextEditingController();
  final TextEditingController _openClawTokenController =
      TextEditingController();
  final TextEditingController _openClawUserIdController =
      TextEditingController();
  final GlobalKey _openClawPanelKey = GlobalKey();
  final GlobalKey _inputAreaKey = GlobalKey();
  double _inputAreaHeight = 0;

  // 控制输入框显示/隐藏
  bool _isInputAreaVisible = true;
  bool _isExecutingTask = false; // 是否正在执行任务

  // 对话持久化相关
  int? _currentConversationId;
  ConversationModel? _currentConversation;

  @override
  void initState() {
    super.initState();

    WidgetsBinding.instance.addObserver(this);
    HomeGreetingSettingsService.notifier.addListener(
      _handleHomeGreetingSettingsChanged,
    );
    unawaited(HomeGreetingSettingsService.load());
    _inputFocusNode.addListener(_onFocusChange);
    _messageController.addListener(_handleSlashCommandInput);
    _openClawEnabled = widget.openClawEnabled ?? _openClawEnabled;
    _loadOpenClawConfig();

    // 根据启动场景处理上下文
    if (widget.launchScene == ChatBotLaunchScene.summary) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _loadSavedContextAndNotifyNative();
      });
    } else if (widget.launchScene == ChatBotLaunchScene.resumeAfterAuth) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _loadResumeDataAndResend();
      });
    } else {
      // 普通场景：清空保存的上下文和恢复数据
      _clearSavedContext();
      StorageService.remove(kChatResumeAfterAuthKey);

      // 如果有初始文本或附件，立即发送
      final hasInitialPayload =
          (widget.initialMessage?.trim().isNotEmpty ?? false) ||
          widget.initialAttachments.isNotEmpty;
      if (hasInitialPayload) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          _pendingAttachments
            ..clear()
            ..addAll(_chatInputAttachmentsFromMaps(widget.initialAttachments));
          _sendMessage(text: widget.initialMessage ?? '');
        });
      }
    }

    // 页面关闭回调
    ScreenDialogService.setOnBeforeCloseChatBotDialog(_onDialogClose);

    _acpRuntimeSubscription = AgentRuntimeService.events.listen(
      _handleIncomingAcpRuntimeEvent,
    );
    _runtimeCoordinator.ensureInitialized();
    unawaited(_loadActiveAcpAgentIdentity());
  }

  Future<void> _loadActiveAcpAgentIdentity() async {
    try {
      final status = await AgentRuntimeService.status();
      final agentId = status.activeAgentId?.trim() ?? '';
      if (!mounted || agentId.isEmpty) return;
      setState(() => _activeAcpAgentId = agentId);
    } catch (error) {
      debugPrint('加载 ACP Agent 身份失败: $error');
    }
  }

  Future<void> _loadOpenClawConfig() async {
    try {
      final enabled =
          widget.openClawEnabled ??
          (StorageService.getBool(kOpenClawEnabledKey, defaultValue: false) ??
              false);
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

  Future<void> _setOpenClawEnabled(bool enabled) async {
    if (enabled && _openClawBaseUrl.trim().isEmpty) {
      AppToast.show(
        LegacyTextLocalizer.isEnglish
            ? 'Please configure OpenClaw first using /openclaw'
            : '请先使用 /openclaw 配置 OpenClaw',
      );
      _showOpenClawCommandPanel(expand: true);
      return;
    }
    if (!mounted) return;
    setState(() => _openClawEnabled = enabled);
    await StorageService.setBool(kOpenClawEnabledKey, enabled);
  }

  void _showSnackBar(String message) {
    if (!mounted) return;
    final messenger = ScaffoldMessenger.maybeOf(context);
    messenger?.hideCurrentSnackBar();
    messenger?.showSnackBar(
      SnackBar(
        content: Text(message),
        duration: const Duration(milliseconds: 1200),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  void _handleSlashCommandInput() {
    final text = _messageController.text.trimLeft();
    final shouldShow = text.startsWith('/');
    if (!mounted) return;
    if (shouldShow != _showSlashCommandPanel) {
      setState(() {
        _showSlashCommandPanel = shouldShow;
        if (!shouldShow) {
          _openClawPanelExpanded = false;
        }
      });
    }
  }

  void _showOpenClawCommandPanel({bool expand = false}) {
    if (!mounted) return;
    setState(() {
      _showSlashCommandPanel = true;
      _openClawPanelExpanded = expand;
      if (expand) {
        _openClawBaseUrlController.text = _openClawBaseUrl;
        _openClawTokenController.text = _openClawToken;
        _openClawUserIdController.text = _openClawUserId;
      }
    });
  }

  void _hideSlashCommandPanel() {
    if (!mounted) return;
    setState(() {
      _showSlashCommandPanel = false;
      _openClawPanelExpanded = false;
    });
  }

  bool _isPointerInside(GlobalKey key, Offset position) {
    final context = key.currentContext;
    if (context == null) return false;
    final renderBox = context.findRenderObject() as RenderBox?;
    if (renderBox == null || !renderBox.hasSize) return false;
    final offset = renderBox.localToGlobal(Offset.zero);
    final rect = offset & renderBox.size;
    return rect.contains(position);
  }

  Future<void> _handleOutsideTap(Offset position) async {
    if (!_showSlashCommandPanel && !_openClawPanelExpanded) return;
    if (_isPointerInside(_openClawPanelKey, position) ||
        _isPointerInside(_inputAreaKey, position)) {
      return;
    }
    if (_openClawPanelExpanded) {
      await _applyOpenClawConfig(
        baseUrl: _openClawBaseUrlController.text.trim(),
        token: _openClawTokenController.text.trim(),
        userId: _openClawUserIdController.text.trim(),
        enable: _openClawEnabled,
      );
    }
    _hideSlashCommandPanel();
  }

  Future<void> _startManualRecordingFromCommandPanel() async {
    _messageController.clear();
    _hideSlashCommandPanel();
    await _startManualRecordingCommand('/record');
  }

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
      _openClawEnabled = enable && baseUrl.trim().isNotEmpty;
    });
    await StorageService.setBool(kOpenClawEnabledKey, _openClawEnabled);
    await _ensureOpenClawUserId();
  }

  Future<bool> _tryHandleSlashCommand(String messageText) async {
    final trimmed = messageText.trim();
    if (!trimmed.startsWith('/')) return false;

    if (trimmed == '/record') {
      await _startManualRecordingFromCommandPanel();
      return true;
    }

    if (!trimmed.startsWith('/openclaw')) {
      _showSnackBar(
        LegacyTextLocalizer.isEnglish
            ? 'Unknown command, please use /openclaw'
            : '未知指令，请使用 /openclaw',
      );
      return true;
    }

    final parts = trimmed.split(RegExp(r'\\s+'));
    if (parts.length < 2) {
      _showSnackBar(
        LegacyTextLocalizer.isEnglish
            ? 'Format: /openclaw <baseurl> --token <token> <userid>'
            : '格式: /openclaw <baseurl> --token <token> <userid>',
      );
      return true;
    }

    final baseUrl = parts[1];
    final tokenIndex = parts.indexOf('--token');
    if (tokenIndex == -1) {
      _showSnackBar(
        LegacyTextLocalizer.isEnglish
            ? 'Please include --token explicitly in the command'
            : '请在命令中显式包含 --token',
      );
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
      _showSnackBar(
        LegacyTextLocalizer.isEnglish
            ? 'OpenClaw baseurl cannot be empty'
            : 'OpenClaw baseurl 不能为空',
      );
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
    _showSnackBar(
      LegacyTextLocalizer.isEnglish
          ? 'OpenClaw configured and enabled'
          : 'OpenClaw 已配置并启用',
    );
    return true;
  }

  /// 保存当前聊天上下文到本地存储
  Future<void> _saveChatContext() async {
    try {
      final List<Map<String, dynamic>> contextList = _messages
          .where((msg) => !msg.isLoading)
          .map((msg) => msg.toJson())
          .toList();
      await StorageService.setJson(kChatContextStorageKey, contextList);
    } catch (e) {
      debugPrint('保存聊天上下文失败: $e');
    }
  }

  Future<void> _handleBeforeTaskExecute() async {
    await _saveChatContext();
    await _saveConversationToDb();
  }

  String _buildConversationHistoryText() {
    final buffer = StringBuffer();
    for (final message in _messages) {
      if (message.user == 1) {
        final text = message.content?['text'] as String? ?? '';
        if (text.isNotEmpty) {
          buffer.write(
            LegacyTextLocalizer.isEnglish ? 'User: $text\n' : '用户: $text\n',
          );
        }
      }
      // else if (message.user == 2) {
      //   final text = message.content?['text'] as String? ?? '';
      //   if (text.isNotEmpty) {
      //     buffer.write('助手: $text\n');
      //   }
      // }
    }
    return buffer.toString().trim();
  }

  /// 保存对话到数据库，用于持久化对话历史
  Future<void> _saveConversationToDb({
    bool generateSummary = false,
    bool markComplete = false,
  }) async {
    if (_messages.isEmpty) return;

    try {
      final now = DateTime.now().millisecondsSinceEpoch;
      final lastMessage = _messages.isNotEmpty ? (_messages[0].text ?? '') : '';
      final messageCount = _messages.length;

      final firstUserMessage = _messages.firstWhere(
        (m) => m.user == 1,
        orElse: () => ChatMessageModel.userMessage(
          LegacyTextLocalizer.isEnglish ? "New conversation" : "新对话",
        ),
      );
      final userText =
          firstUserMessage.text ??
          (LegacyTextLocalizer.isEnglish ? 'New conversation' : '新对话');
      final title = userText.length > 20
          ? '${userText.substring(0, 20)}...'
          : userText;

      String? summary;
      if (generateSummary) {
        debugPrint("chat bot sheet 生成对话摘要...");
        final conversationHistory = _buildConversationHistoryText();
        summary = conversationHistory.isEmpty
            ? null
            : await ConversationService.generateConversationSummary(
                conversationHistory: conversationHistory,
              );
      }

      if (_currentConversationId == null) {
        final newId = await ConversationService.createConversation(
          title: title,
          summary: summary,
        );
        if (newId != null) {
          _currentConversationId = newId;
          _currentConversation = ConversationModel(
            id: newId,
            title: title,
            summary: summary,
            status: 0,
            lastMessage: lastMessage,
            messageCount: messageCount,
            createdAt: now,
            updatedAt: now,
          );
          // 同步对话ID到Kotlin层，用于任务完成后导航
          await ConversationService.setCurrentConversationId(newId);
          await ConversationHistoryService.saveCurrentConversationId(newId);
          debugPrint('[ChatBotSheet] 创建对话成功，ID: $newId');
        }
      }

      if (_currentConversationId != null) {
        await ConversationService.setCurrentConversationId(
          _currentConversationId,
        );
        await ConversationHistoryService.saveCurrentConversationId(
          _currentConversationId,
        );
        final baseConversation =
            _currentConversation ??
            ConversationModel(
              id: _currentConversationId!,
              title: title,
              summary: summary,
              status: 0,
              lastMessage: lastMessage,
              messageCount: messageCount,
              createdAt: now,
              updatedAt: now,
            );

        final updatedConversation = baseConversation.copyWith(
          summary: summary ?? baseConversation.summary,
          lastMessage: lastMessage,
          messageCount: messageCount,
          updatedAt: now,
        );

        await ConversationService.updateConversation(
          updatedConversation,
          preserveLatestMetadata: true,
        );
        _currentConversation = updatedConversation;
        for (final message in _messages.where((item) {
          final cardData = item.cardData;
          return item.type == 2 && cardData?['type'] == 'deep_thinking';
        })) {
          final cardData = message.cardData;
          if (cardData == null) continue;
          await ConversationHistoryService.upsertConversationUiCard(
            _currentConversationId!,
            entryId: message.id,
            cardData: buildPersistentDeepThinkingCardData(
              Map<String, dynamic>.from(cardData),
            ),
            createdAtMillis: message.createAt.millisecondsSinceEpoch,
          );
        }

        if (markComplete) {
          await ConversationService.completeConversation(
            _currentConversationId!,
          );
        }
      }
    } catch (e) {
      debugPrint('[ChatBotSheet] 保存对话到数据库失败: $e');
    }
  }

  /// 加载保存的聊天上下文并通知原生层
  Future<void> _loadSavedContextAndNotifyNative() async {
    try {
      // final savedContext = StorageService.getJson<List<dynamic>>(kChatContextStorageKey);
      // if (savedContext != null && savedContext.isNotEmpty) {
      //   final List<ChatMessageModel> loadedMessages = savedContext
      //       .map((json) => ChatMessageModel.fromJson(Map<String, dynamic>.from(json)))
      //       .toList();
      //   setState(() {
      //     _messages.clear();
      //     _messages.addAll(loadedMessages);
      //   });
      // }

      // 添加loading消息
      _addLoadingMessage();
    } catch (e) {
      debugPrint('加载聊天上下文失败: $e');
    }
  }

  /// 清空保存的聊天上下文
  Future<void> _clearSavedContext() async {
    try {
      await StorageService.remove(kChatContextStorageKey);
    } catch (e) {
      debugPrint('清空聊天上下文失败: $e');
    }
  }

  Future<void> _loadResumeDataAndResend() async {
    try {
      final resumeData = StorageService.getJson<Map<String, dynamic>>(
        kChatResumeAfterAuthKey,
      );
      if (resumeData == null) return;

      final timestamp = resumeData['timestamp'] as int? ?? 0;
      final now = DateTime.now().millisecondsSinceEpoch;
      if (timestamp > 0 && now - timestamp > 30 * 60 * 1000) {
        await StorageService.remove(kChatResumeAfterAuthKey);
        return;
      }

      final rawMessages = (resumeData['messages'] as List?) ?? const [];
      final prompt = (resumeData['prompt'] as String?) ?? '';

      if (rawMessages.isNotEmpty) {
        final loaded = rawMessages
            .map(
              (e) => ChatMessageModel.fromJson(
                Map<String, dynamic>.from(e as Map),
              ),
            )
            .toList();
        setState(() {
          _messages
            ..clear()
            ..addAll(loaded);
        });
      }

      if (prompt.isNotEmpty) {
        await _sendMessage(text: prompt, appendUserBubble: false);
      }
    } catch (e) {
      debugPrint('加载授权恢复数据失败: $e');
    } finally {
      await StorageService.remove(kChatResumeAfterAuthKey);
    }
  }

  void _onDialogClose() {
    _closeRequested = true;
    final hasLiveTurn = _hasLiveAcpTurn;
    unawaited(_closeAcpLifecycle());
    // Closing the presentation is not proof that an ACP turn completed. A
    // cancelled/unfinished turn remains an active conversation; only an ACP
    // terminal event (or an explicit user cancellation) may complete it.
    unawaited(
      _saveConversationToDb(generateSummary: true, markComplete: !hasLiveTurn),
    );
  }

  @override
  void didChangeMetrics() {
    super.didChangeMetrics();
    _syncEmptyGreetingKeyboardLiftFromView();
  }

  void _syncEmptyGreetingKeyboardLiftFromView() {
    if (!mounted) return;
    final view = View.of(context);
    final bottomInset = view.viewInsets.bottom / view.devicePixelRatio;
    if (_emptyGreetingKeyboardLiftTracker.update(bottomInset)) {
      setState(() {});
    }
  }

  void _handleHomeGreetingSettingsChanged() {
    if (!mounted) return;
    setState(() {});
  }

  void _applyHomeQuickPrompt(HomeQuickPrompt prompt) {
    final text = prompt.resolvePrompt(context).trim();
    if (text.isEmpty) {
      return;
    }
    _messageController.value = TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: text.length),
    );
    _handleSlashCommandInput();
    _inputFocusNode.requestFocus();
  }

  @override
  void dispose() {
    _closeRequested = true;
    unawaited(_closeAcpLifecycle());
    WidgetsBinding.instance.removeObserver(this);
    HomeGreetingSettingsService.notifier.removeListener(
      _handleHomeGreetingSettingsChanged,
    );
    _messageController.removeListener(_handleSlashCommandInput);
    _messageController.dispose();
    _messageScrollController.dispose();
    _sheetController.dispose();
    _inputFocusNode.dispose();
    _openClawBaseUrlController.dispose();
    _openClawTokenController.dispose();
    _openClawUserIdController.dispose();
    _acpRuntimeSubscription?.cancel();
    _acpRuntimeSubscription = null;
    ScreenDialogService.setOnBeforeCloseChatBotDialog(null);
    final conversationId = _currentConversationId;
    if (conversationId != null) {
      _runtimeCoordinator.discardConversationRuntime(
        conversationId: conversationId,
        mode: _runtimeMode,
      );
    }
    super.dispose();
  }

  bool get _hasLiveAcpTurn =>
      _currentDispatchTurnId != null ||
      _isAiResponding ||
      _isCheckingExecutableTask ||
      _isExecutingTask;

  /// Close the ACP resources owned by this short-lived presentation. The
  /// session id is obtained from `session/new` before `session/prompt`, so a
  /// close can cancel the official ACP turn instead of only clearing Flutter
  /// state. If close races session creation, `_tryAgentFlow` repeats this
  /// check after `session/new` and closes the session there.
  Future<void> _closeAcpLifecycle() async {
    final sessionId = _acpSessionId?.trim();
    final conversationId = _currentConversationId;
    if (conversationId == null) {
      return;
    }
    if (_acpCloseStarted) return;
    _acpCloseStarted = true;
    if (_hasLiveAcpTurn) {
      try {
        await AgentRuntimeService.cancelPrompt(
          sessionId: sessionId,
          conversationId: conversationId,
          promptId: _acpPromptId,
          runId: _currentDispatchTurnId,
        );
      } catch (error) {
        debugPrint('ACP 取消请求失败: $error');
      }
    }
    if (sessionId != null && sessionId.isNotEmpty) {
      try {
        await AgentRuntimeService.closeSession(
          sessionId: sessionId,
          conversationId: conversationId,
        );
      } catch (error) {
        debugPrint('关闭 ACP 会话失败: $error');
      }
    }
  }

  /// Waits for the ACP cancellation request to settle, then mirrors the
  /// coordinator projection. The original prompt response owns the terminal
  /// state; the cancellation acknowledgement cannot end the turn.
  Future<void> _finishAcpCancellationPresentation() async {
    await _closeAcpLifecycle();
    if (!mounted) return;
    final conversationId = _currentConversationId;
    final runtime = conversationId == null
        ? null
        : _runtimeCoordinator.runtimeFor(
            conversationId: conversationId,
            mode: _runtimeMode,
          );
    if (runtime?.isAiResponding == true) {
      // The Agent may still send final updates before PromptResponse.
      debugPrint('ACP cancellation returned before prompt response');
      return;
    }
    if (runtime != null) {
      setState(() {
        _messages
          ..clear()
          ..addAll(runtime.messages);
        _currentAiMessages
          ..clear()
          ..addAll(runtime.currentAiMessages);
        _isAiResponding = runtime.isAiResponding;
        _currentDispatchTurnId = runtime.currentDispatchTurnId;
      });
    }
    _resetDispatchState();
    if (mounted) {
      setState(() {
        _isAiResponding = false;
        _isCheckingExecutableTask = false;
        _isExecutingTask = false;
        _isInputAreaVisible = true;
      });
    }
  }

  void _onFocusChange() {
    if (!mounted) return;
    setState(() {});
  }

  void _updateInputAreaMetrics() {
    final context = _inputAreaKey.currentContext;
    if (context == null) return;
    final renderBox = context.findRenderObject() as RenderBox?;
    if (renderBox == null || !renderBox.hasSize) return;
    final height = renderBox.size.height;
    if (height != _inputAreaHeight && mounted) {
      setState(() {
        _inputAreaHeight = height;
      });
    }
  }

  void _onInputHeightChanged(double height) {
    if (height == _inputAreaHeight || !mounted) return;
    setState(() {
      _inputAreaHeight = height;
    });
  }

  /// 添加loading消息
  void _addLoadingMessage() {
    final loadingId = '${DateTime.now().millisecondsSinceEpoch}-loading';
    setState(() {
      _messages.insert(
        0,
        ChatMessageModel(
          id: loadingId,
          type: 1,
          user: 2, // AI消息
          content: {'text': '', 'id': loadingId},
          isLoading: true,
        ),
      );
    });
  }

  double _messageLatestOffset(ScrollMetrics metrics) {
    return switch (metrics.axisDirection) {
      AxisDirection.down || AxisDirection.right => metrics.maxScrollExtent,
      AxisDirection.up || AxisDirection.left => metrics.minScrollExtent,
    };
  }

  double _messageDistanceToLatest(ScrollMetrics metrics) {
    return (metrics.pixels - _messageLatestOffset(metrics)).abs();
  }

  bool _isMessageListNearLatest([ScrollMetrics? metrics]) {
    final resolvedMetrics = metrics;
    if (resolvedMetrics != null) {
      return _messageDistanceToLatest(resolvedMetrics) <=
          _messageLatestEdgeTolerance;
    }
    if (!_messageScrollController.hasClients) {
      return true;
    }
    return _messageDistanceToLatest(_messageScrollController.position) <=
        _messageLatestEdgeTolerance;
  }

  void _scheduleMessageStickToLatest() {
    if (!_autoStickMessageListToLatest || _messageStickToLatestScheduled) {
      return;
    }
    _messageStickToLatestScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _messageStickToLatestScheduled = false;
      if (!mounted || !_messageScrollController.hasClients) {
        return;
      }
      if (!_autoStickMessageListToLatest) {
        return;
      }
      final position = _messageScrollController.position;
      final target = _messageLatestOffset(position);
      if ((target - position.pixels).abs() < 0.5) {
        return;
      }
      _messageScrollController.jumpTo(target);
    });
  }

  void _handleStreamingTextLayoutChanged() {
    if (_autoStickMessageListToLatest || _isMessageListNearLatest()) {
      _autoStickMessageListToLatest = true;
      _scheduleMessageStickToLatest();
    }
  }

  void _toggleAgentRunGroup(String taskId) {
    final normalizedTaskId = taskId.trim();
    if (normalizedTaskId.isEmpty) {
      return;
    }
    setState(() {
      if (_expandedAgentRunTaskIds.contains(normalizedTaskId)) {
        _expandedAgentRunTaskIds.remove(normalizedTaskId);
      } else {
        _expandedAgentRunTaskIds.add(normalizedTaskId);
      }
    });
    if (_autoStickMessageListToLatest) {
      _scheduleMessageStickToLatest();
    }
  }

  void _handleParentScrollHandoff() {
    _autoStickMessageListToLatest = false;
    _messageListScrollWasUserDriven = false;
  }

  void _handleMessageScrollNotification(ScrollNotification notification) {
    if (notification.depth != 0 || notification.metrics.axis != Axis.vertical) {
      return;
    }
    final isUserDrivenUpdate =
        (notification is ScrollUpdateNotification &&
            notification.dragDetails != null) ||
        (notification is OverscrollNotification &&
            notification.dragDetails != null);
    if (isUserDrivenUpdate) {
      _messageListScrollWasUserDriven = true;
      _autoStickMessageListToLatest = _isMessageListNearLatest(
        notification.metrics,
      );
      return;
    }
    if (notification is ScrollEndNotification) {
      if (_messageListScrollWasUserDriven &&
          _isMessageListNearLatest(notification.metrics)) {
        _autoStickMessageListToLatest = true;
      }
      _messageListScrollWasUserDriven = false;
    }
  }

  // 悬浮聊天页也复用同一套 linkPreviews 字段，保证两种聊天入口表现一致。
  void _syncMessageLinkPreviews(String taskId) {
    final index = _messages.indexWhere((msg) => msg.id == taskId);
    if (index == -1) {
      return;
    }

    final message = _messages[index];
    if (message.type != 1 ||
        (message.user != 1 && message.user != 2) ||
        message.isLoading ||
        message.isError ||
        message.isSummarizing) {
      return;
    }

    final content = Map<String, dynamic>.from(message.content ?? const {});
    final nextPreviews = LinkPreviewService.instance.reconcilePreviewMaps(
      text: message.text ?? '',
      existing: content['linkPreviews'],
    );
    final currentPreviews = content['linkPreviews'];
    var didUpdate = false;
    if (!_previewMapListsEqual(currentPreviews, nextPreviews)) {
      if (nextPreviews.isEmpty) {
        content.remove('linkPreviews');
      } else {
        content['linkPreviews'] = nextPreviews;
      }
      _messages[index] = message.copyWith(content: content);
      didUpdate = true;
    }
    if (didUpdate &&
        nextPreviews.any(
          (item) =>
              ChatLinkPreview.fromJson(item).status !=
              ChatLinkPreview.statusLoading,
        )) {
      final conversationId = _currentConversationId;
      if (conversationId != null) {
        unawaited(
          _runtimeCoordinator.persistConversationMessageSnapshot(
            conversationId: conversationId,
            mode: _runtimeMode,
            messages: List<ChatMessageModel>.from(_messages),
            conversation: _currentConversation,
          ),
        );
      }
    }

    // 先渲染占位卡片，网络请求成功后再替换为 ready/failed 数据。
    for (final previewMap in nextPreviews) {
      final preview = ChatLinkPreview.fromJson(previewMap);
      if (preview.status != ChatLinkPreview.statusLoading ||
          preview.url.isEmpty) {
        continue;
      }
      unawaited(_resolveMessageLinkPreview(taskId, preview.url));
    }
  }

  Future<void> _resolveMessageLinkPreview(String taskId, String url) async {
    final resolved = await LinkPreviewService.instance.loadPreview(url);
    if (!mounted) {
      return;
    }

    // 只更新当前消息里的同一个 loading URL，避免异步结果串到别的消息。
    var didUpdate = false;
    setState(() {
      final index = _messages.indexWhere((msg) => msg.id == taskId);
      if (index == -1) {
        return;
      }

      final message = _messages[index];
      final content = Map<String, dynamic>.from(message.content ?? const {});
      final rawPreviews = content['linkPreviews'];
      if (rawPreviews is! List) {
        return;
      }

      final updatedPreviews = rawPreviews
          .whereType<Map>()
          .map(
            (item) => Map<String, dynamic>.from(item.cast<String, dynamic>()),
          )
          .map((previewMap) {
            final preview = ChatLinkPreview.fromJson(previewMap);
            if (preview.url != url ||
                preview.status != ChatLinkPreview.statusLoading) {
              return previewMap;
            }
            didUpdate = true;
            return resolved.toJson();
          })
          .toList();
      if (!didUpdate) {
        return;
      }

      content['linkPreviews'] = updatedPreviews;
      _messages[index] = message.copyWith(content: content);
    });

    if (!didUpdate) {
      return;
    }

    final conversationId = _currentConversationId;
    if (conversationId != null) {
      await _runtimeCoordinator.persistConversationMessageSnapshot(
        conversationId: conversationId,
        mode: _runtimeMode,
        messages: List<ChatMessageModel>.from(_messages),
        conversation: _currentConversation,
      );
    }
  }

  bool _previewMapListsEqual(dynamic left, List<Map<String, dynamic>> right) {
    if (left is! List) {
      return right.isEmpty;
    }
    final normalizedLeft = left
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .toList();
    if (normalizedLeft.length != right.length) {
      return false;
    }
    for (var index = 0; index < normalizedLeft.length; index += 1) {
      if (!_previewMapEquals(normalizedLeft[index], right[index])) {
        return false;
      }
    }
    return true;
  }

  bool _previewMapEquals(
    Map<String, dynamic> left,
    Map<String, dynamic> right,
  ) {
    return left['url'] == right['url'] &&
        left['domain'] == right['domain'] &&
        left['siteName'] == right['siteName'] &&
        left['title'] == right['title'] &&
        left['description'] == right['description'] &&
        left['imageUrl'] == right['imageUrl'] &&
        left['status'] == right['status'];
  }

  /// 重置dispatch状态
  void _resetDispatchState() {
    final conversationId = _currentConversationId;
    if (conversationId != null) {
      _runtimeCoordinator.clearConversationRuntimeSession(
        conversationId: conversationId,
        mode: _runtimeMode,
      );
    }
    _currentDispatchTurnId = null;
    _acpPromptId = null;
    _currentAiMessages.clear();
  }

  /// 发送消息（支持输入框发送、初始消息和恢复场景重发）
  Future<void> _sendMessage({
    String? text,
    bool appendUserBubble = true,
  }) async {
    final messageText = text ?? _messageController.text.trim();
    final hasAttachments = _pendingAttachments.isNotEmpty;
    if ((messageText.isEmpty && !hasAttachments) || _isAiResponding) return;

    final handledSlash = await _tryHandleSlashCommand(messageText);
    if (handledSlash) return;

    if (hasAttachments == false &&
        ManualRecordingFlowController.isCommand(messageText)) {
      await _startManualRecordingCommand(messageText);
      return;
    }

    _inputFocusNode.unfocus();
    final attachments = _pendingAttachments
        .map((item) => item.toMap())
        .toList();
    if (attachments.isNotEmpty && mounted) {
      setState(() => _pendingAttachments.clear());
    }
    late final ({String userMessageId, String aiMessageId}) messageIds;
    if (appendUserBubble) {
      messageIds = _addUserMessage(messageText, attachments: attachments);
      await _saveConversationToDb();
    } else {
      final timestamp = DateTime.now().millisecondsSinceEpoch.toString();
      messageIds = (
        userMessageId: '$timestamp-user',
        aiMessageId: '$timestamp-ai',
      );
      setState(() {
        _isAiResponding = true;
      });
    }

    if (_openClawEnabled) {
      _sendChatMessage(messageIds.aiMessageId);
      return;
    }

    final handled = await _handleExecutableTask(
      messageIds.aiMessageId,
      messageIds.userMessageId,
    );
    if (!handled &&
        mounted &&
        !_closeRequested &&
        _currentDispatchTurnId == messageIds.aiMessageId) {
      _showAcpStartError(
        messageIds.aiMessageId,
        LegacyTextLocalizer.isEnglish
            ? 'Failed to start unified Agent. Please check model provider and scene model config.'
            : '统一 Agent 启动失败，请检查模型提供商与场景模型配置。',
      );
    }
  }

  Future<void> _startManualRecordingCommand(String messageText) async {
    await ManualRecordingFlowController.start(
      context: context,
      inputFocusNode: _inputFocusNode,
      userMessageText: messageText,
      recordDebugScreenshots: true,
      isMounted: () => mounted,
      addUserMessage: (text) {
        final ids = _addUserMessage(text);
        setState(() {
          _messages.insert(
            0,
            ChatMessageModel.assistantMessage(
              '',
              id: ids.aiMessageId,
              isLoading: true,
            ),
          );
        });
        return ManualRecordingFlowMessageIds(
          userMessageId: ids.userMessageId,
          aiMessageId: ids.aiMessageId,
        );
      },
      afterUserMessageAdded: (_) => _saveConversationToDb(),
      insertResultMessage: (messageId, result) {
        if (!mounted) return;
        final index = _messages.indexWhere(
          (message) => message.id == messageId,
        );
        final text = _manualRecordingResultText(result);
        final card = buildManualRecordingResultCard(
          messageId: messageId,
          result: result,
          summary: text,
        );
        setState(() {
          if (index == -1) {
            _messages.insert(0, card);
          } else {
            _messages[index] = card;
          }
        });
        unawaited(_saveConversationToDb(markComplete: true));
      },
      onFinally: () {
        if (!mounted) return;
        setState(() => _isAiResponding = false);
      },
    );
  }

  String _manualRecordingResultText(Map<String, dynamic> result) {
    if (result['success'] == true) {
      return hasOmniFlowRegisteredFunction(result)
          ? '手动录制完成，复用指令已保存'
          : '手动录制完成，RunLog 已保存；复用指令生成失败';
    }
    final error = result['error_message']?.toString().trim() ?? '';
    return error.isEmpty ? '手动录制失败' : '手动录制失败：$error';
  }

  ({String userMessageId, String aiMessageId}) _addUserMessage(
    String text, {
    List<Map<String, dynamic>> attachments = const [],
  }) {
    final timestamp = DateTime.now().millisecondsSinceEpoch.toString();
    final userMessageId = '$timestamp-user';
    final aiMessageId = '$timestamp-ai';
    final content = <String, dynamic>{'text': text, 'id': userMessageId};
    if (attachments.isNotEmpty) {
      content['attachments'] = attachments;
    }

    setState(() {
      _messages.insert(
        0,
        ChatMessageModel(id: userMessageId, type: 1, user: 1, content: content),
      );
      _messageController.clear();
      _isAiResponding = true;
    });
    _syncMessageLinkPreviews(userMessageId);

    return (userMessageId: userMessageId, aiMessageId: aiMessageId);
  }

  Future<bool> _handleExecutableTask(
    String aiMessageId,
    String userMessageId,
  ) async {
    _isCheckingExecutableTask = true;
    try {
      return await _tryAgentFlow(aiMessageId, userMessageId);
    } finally {
      _isCheckingExecutableTask = false;
    }
  }

  // 新增：Agent 流程
  Future<bool> _tryAgentFlow(String aiMessageId, String userMessageId) async {
    final conversationId = _currentConversationId;
    try {
      setState(() {
        _currentDispatchTurnId = aiMessageId;
      });
      _cancelRequested = false;

      final userMessage = _latestUserUtterance();
      final attachments = _latestUserAgentAttachments();
      if (conversationId == null) {
        throw StateError('conversationId is not ready');
      }
      _runtimeCoordinator.ensureEphemeralRuntime(
        conversationId: conversationId,
        mode: _runtimeMode,
        initialMessages: _messages,
        conversation: _currentConversation,
      );
      _runtimeCoordinator.replaceConversationSnapshot(
        conversationId: conversationId,
        mode: _runtimeMode,
        messages: _messages,
        conversation: _currentConversation,
        isAiResponding: true,
        currentDispatchTurnId: aiMessageId,
      );
      // The command overlay is another ACP entry point, not a separate
      // lifecycle. Admit the logical turn through the shared coordinator
      // before any status/session await so early events and failure cleanup
      // have one task binding.
      _runtimeCoordinator.beginAcpTurn(
        taskId: aiMessageId,
        conversationId: conversationId,
        mode: _runtimeMode,
      );
      var status = await AgentRuntimeService.status();
      if (!_canContinueAcpTurn(aiMessageId)) return false;
      if (!status.connected) {
        status = await AgentRuntimeService.connect();
      }
      if (!_canContinueAcpTurn(aiMessageId)) return false;
      final activeAgentId = status.activeAgentId?.trim() ?? '';
      if (activeAgentId.isNotEmpty) {
        _activeAcpAgentId = activeAgentId;
      }
      final catalog = await SceneModelConfigService.getSceneCatalog();
      final dispatchScene = catalog
          .where((item) => item.sceneId == 'scene.dispatch.model')
          .firstOrNull;
      if (!_canContinueAcpTurn(aiMessageId)) return false;
      // ACP separates session ownership from prompt execution. Reserve the
      // session first so cancellation and late-event attribution have a
      // stable official identity before the potentially long prompt call.
      final sessionResponse = await AgentRuntimeService.newSession(
        conversationId: conversationId,
        model: dispatchScene?.effectiveModel.trim(),
        conversationMode: ConversationMode.agent.storageValue,
      );
      _acpSessionId =
          (sessionResponse['sessionId'] ?? sessionResponse['threadId'])
              ?.toString()
              .trim();
      if ((_acpSessionId ?? '').isEmpty) {
        throw StateError('ACP did not return a session id');
      }
      // A stop may have arrived while session/new was pending. Its earlier
      // cleanup did not own this newly returned session.
      _acpCloseStarted = false;
      if (!_canContinueAcpTurn(aiMessageId)) {
        await _closeAcpLifecycle();
        return false;
      }

      final response = await AgentRuntimeService.promptSession(
        sessionId: _acpSessionId,
        conversationId: conversationId,
        requestId: aiMessageId,
        agentId: status.activeAgentId,
        text: userMessage,
        attachments: attachments,
        model: dispatchScene?.effectiveModel.trim(),
        conversationMode: ConversationMode.agent.storageValue,
      );
      final responseSessionId =
          (response['sessionId'] ?? response['threadId'] ?? _acpSessionId)
              ?.toString()
              .trim();
      final responsePromptId = (response['promptId'] ?? response['turnId'])
          ?.toString()
          .trim();
      if (conversationId != null) {
        final result = _runtimeCoordinator.applyAcpPromptResponse(
          taskId: aiMessageId,
          conversationId: conversationId,
          mode: _runtimeMode,
          sessionId: responseSessionId,
          turnId: responsePromptId,
          stopReason:
              response['stopReason']?.toString() ??
              response['status']?.toString(),
          error: response['error']?.toString(),
          conversation: _currentConversation,
        );
        if (result.handled) {
          _acpSessionId = responseSessionId;
          _acpPromptId = responsePromptId;
          _syncAcpRuntimePresentation(conversationId);
        }
      }
      return true;
    } catch (e) {
      final conversationId = _currentConversationId;
      final runtime = conversationId == null
          ? null
          : _runtimeCoordinator.runtimeFor(
              conversationId: conversationId,
              mode: _runtimeMode,
            );
      if (conversationId != null && runtime?.isAiResponding == true) {
        final result = _runtimeCoordinator.applyAcpPromptResponse(
          taskId: aiMessageId,
          conversationId: conversationId,
          mode: _runtimeMode,
          sessionId: runtime?.activeAcpSessionId ?? _acpSessionId,
          turnId: runtime?.activeAcpTurnId ?? _acpPromptId,
          stopReason: 'error',
          error: e.toString(),
          conversation: _currentConversation,
        );
        if (result.handled) {
          _syncAcpRuntimePresentation(conversationId);
        }
      }
      debugPrint('Agent flow error: $e');
      return false;
    } finally {
      if (conversationId != null) {
        // If preparation stopped before session/prompt, there is no ACP
        // PromptResponse to await. Release only this host reservation. For
        // submitted prompts, the response/error above has already reduced it.
        _runtimeCoordinator.unregisterTask(
          aiMessageId,
          conversationId: conversationId,
          mode: _runtimeMode,
        );
        if (_currentDispatchTurnId == aiMessageId) {
          _syncAcpRuntimePresentation(conversationId);
        }
      }
    }
  }

  bool _canContinueAcpTurn(String taskId) =>
      mounted &&
      !_closeRequested &&
      !_cancelRequested &&
      _currentDispatchTurnId == taskId;

  void _handleIncomingAcpRuntimeEvent(Map<String, dynamic> event) {
    final conversationId = _asInt(event['conversationId']);
    if (!mounted ||
        _currentDispatchTurnId == null ||
        conversationId == null ||
        conversationId != _currentConversationId) {
      return;
    }
    final result = _runtimeCoordinator.applyAgentEvent(
      conversationId: conversationId,
      mode: _runtimeMode,
      event: event,
      conversation: _currentConversation,
    );
    if (!result.handled) {
      return;
    }
    final eventTurnId = result.turnId ?? event['turnId']?.toString().trim();
    if (eventTurnId != null && eventTurnId.isNotEmpty) {
      _acpPromptId = eventTurnId;
    }
    _syncAcpRuntimePresentation(conversationId);
  }

  void _syncAcpRuntimePresentation(int conversationId) {
    if (!mounted || conversationId != _currentConversationId) return;
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: conversationId,
      mode: _runtimeMode,
    );
    if (runtime == null) return;
    final nextMessages = List<ChatMessageModel>.from(runtime.messages);
    setState(() {
      _messages
        ..clear()
        ..addAll(nextMessages);
      _currentAiMessages
        ..clear()
        ..addAll(runtime.currentAiMessages);
      _isAiResponding = runtime.isAiResponding;
      _currentDispatchTurnId = runtime.currentDispatchTurnId;
    });
    for (final message in nextMessages) {
      if (message.user == 2 && message.text?.trim().isNotEmpty == true) {
        _syncMessageLinkPreviews(message.id);
      }
    }
    if (!runtime.isAiResponding) {
      unawaited(_saveConversationToDb());
    }
  }

  static int? _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

  String _latestUserUtterance() {
    for (final message in _messages) {
      if (message.user == 1) {
        final text = _buildMessageTextForModel(message);
        if (text.isNotEmpty) {
          return text;
        }
      }
    }
    return '';
  }

  List<Map<String, dynamic>> _latestUserAgentAttachments() {
    for (final message in _messages) {
      if (message.user != 1) continue;
      final raw = message.content?['attachments'];
      if (raw is! List) return const [];
      return raw
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .where(_attachmentShouldSendToModel)
          .toList();
    }
    return const [];
  }

  bool _attachmentShouldSendToModel(Map<String, dynamic> attachment) {
    final raw = attachment['sendToModel'];
    if (raw is bool) return raw;
    if (raw is String) return raw.toLowerCase() != 'false';
    return true;
  }

  String _buildMessageTextForModel(ChatMessageModel message) {
    final text = message.content?['text'] as String? ?? '';
    final attachments = _extractAttachmentList(message);
    if (attachments.isEmpty) return text;

    final pathHint = _buildAttachmentPathHint(attachments);
    if (pathHint.isNotEmpty) {
      if (text.trim().isEmpty) return pathHint;
      return '$text\n$pathHint';
    }

    final names = attachments
        .where((attachment) => !_isImageAttachmentMap(attachment))
        .map(_resolveAttachmentName)
        .where((name) => name.trim().isNotEmpty)
        .map((name) => name.trim())
        .toList();
    if (names.isEmpty) return text;
    final attachmentHint = '已附加附件：${names.join('、')}';
    if (text.trim().isEmpty) return attachmentHint;
    return '$text\n$attachmentHint';
  }

  List<Map<String, dynamic>> _extractAttachmentList(ChatMessageModel message) {
    final raw = message.content?['attachments'];
    if (raw is! List) return const [];
    return raw
        .whereType<Map>()
        .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
        .toList();
  }

  String _buildAttachmentPathHint(List<Map<String, dynamic>> attachments) {
    final lines = attachments
        .map((attachment) {
          final promptPath = (attachment['promptPath'] as String? ?? '').trim();
          if (promptPath.isEmpty) return '';
          final name = _resolveAttachmentName(attachment);
          return name.isEmpty ? '- $promptPath' : '- $name: $promptPath';
        })
        .where((line) => line.isNotEmpty)
        .toList();
    if (lines.isEmpty) return '';
    return '已添加到 workspace，可通过以下路径读取：\n${lines.join('\n')}';
  }

  String _resolveAttachmentName(Map<String, dynamic> attachment) {
    final name = (attachment['name'] as String? ?? '').trim();
    if (name.isNotEmpty) return name;
    final path = (attachment['path'] as String? ?? '').trim();
    return path.isEmpty ? '' : _fileNameFromPath(path);
  }

  bool _isImageAttachmentMap(Map<String, dynamic> attachment) {
    final explicit = attachment['isImage'];
    if (explicit is bool && explicit) return true;
    final mimeType = (attachment['mimeType'] as String? ?? '').toLowerCase();
    if (mimeType.startsWith('image/')) return true;
    final path = (attachment['path'] as String? ?? '').toLowerCase();
    return _isImageFilePath(path, mimeType: mimeType);
  }

  Future<void> _pickAttachments() async {
    var hiddenForPicker = false;
    try {
      hiddenForPicker = await ScreenDialogService.hideForExternalActivity();
      if (hiddenForPicker) {
        await Future<void>.delayed(const Duration(milliseconds: 80));
      }
      final result = await FilePicker.platform.pickFiles(
        allowMultiple: true,
        type: FileType.any,
      );
      if (result == null || result.files.isEmpty || !mounted) return;

      setState(() {
        for (final file in result.files) {
          // Android file_picker can return a readable content URI in
          // `identifier` while `path` is null (cloud/document providers).
          // Keep that identifier so the ACP boundary can materialize it.
          final path = file.path ?? file.identifier;
          if (path == null || path.isEmpty) continue;
          final exists = _pendingAttachments.any((item) => item.path == path);
          if (exists) continue;
          final displayName = file.name.trim().isNotEmpty
              ? file.name.trim()
              : _fileNameFromPath(path);
          final extension = (file.extension ?? '').toLowerCase();
          final mimeType = _mimeTypeFromExtension(path, extension: extension);
          _pendingAttachments.add(
            ChatInputAttachment(
              id: '${path}_${DateTime.now().microsecondsSinceEpoch}',
              name: displayName,
              path: path,
              size: file.size > 0 ? file.size : null,
              mimeType: mimeType,
              isImage: _isImageFilePath(path, mimeType: mimeType),
            ),
          );
        }
      });
    } catch (e) {
      _showSnackBar('添加附件失败：$e');
    } finally {
      if (hiddenForPicker) {
        await Future<void>.delayed(const Duration(milliseconds: 120));
        await ScreenDialogService.restoreAfterExternalActivity();
      }
    }
  }

  void _removePendingAttachment(String id) {
    if (!mounted) return;
    setState(() {
      _pendingAttachments.removeWhere((item) => item.id == id);
    });
  }

  List<ChatInputAttachment> _chatInputAttachmentsFromMaps(
    List<Map<String, dynamic>> rawAttachments,
  ) {
    return rawAttachments
        .map((item) {
          final path = (item['path'] as String? ?? '').trim();
          final name = (item['name'] as String? ?? '').trim();
          final mimeType = item['mimeType'] as String?;
          final size = item['size'];
          return ChatInputAttachment(
            id: (item['id'] as String? ?? '').trim().isNotEmpty
                ? (item['id'] as String).trim()
                : '${path}_${DateTime.now().microsecondsSinceEpoch}',
            name: name.isNotEmpty ? name : _fileNameFromPath(path),
            path: path,
            size: size is int ? size : int.tryParse(size?.toString() ?? ''),
            mimeType: mimeType,
            isImage: item['isImage'] is bool
                ? item['isImage'] as bool
                : _isImageFilePath(path, mimeType: mimeType),
            promptPath: item['promptPath'] as String?,
            sendToModel: item['sendToModel'] is bool
                ? item['sendToModel'] as bool
                : true,
          );
        })
        .where((item) => item.path.isNotEmpty)
        .toList();
  }

  String _fileNameFromPath(String path) {
    final normalized = path.replaceAll('\\', '/');
    final segments = normalized.split('/');
    if (segments.isEmpty) return path;
    return segments.last.isEmpty ? path : segments.last;
  }

  bool _isImageFilePath(String path, {String? mimeType}) {
    final normalizedMime = mimeType?.trim().toLowerCase();
    if (normalizedMime != null && normalizedMime.startsWith('image/')) {
      return true;
    }
    final lowerPath = path.toLowerCase();
    return lowerPath.endsWith('.png') ||
        lowerPath.endsWith('.jpg') ||
        lowerPath.endsWith('.jpeg') ||
        lowerPath.endsWith('.webp') ||
        lowerPath.endsWith('.gif') ||
        lowerPath.endsWith('.bmp') ||
        lowerPath.endsWith('.heic') ||
        lowerPath.endsWith('.heif');
  }

  String? _mimeTypeFromExtension(String path, {String extension = ''}) {
    final ext = extension.isNotEmpty
        ? extension
        : _fileNameFromPath(path).split('.').last.toLowerCase();
    switch (ext) {
      case 'png':
        return 'image/png';
      case 'jpg':
      case 'jpeg':
        return 'image/jpeg';
      case 'gif':
        return 'image/gif';
      case 'webp':
        return 'image/webp';
      case 'bmp':
        return 'image/bmp';
      case 'heic':
        return 'image/heic';
      case 'heif':
        return 'image/heif';
      case 'pdf':
        return 'application/pdf';
      case 'txt':
        return 'text/plain';
      case 'md':
        return 'text/markdown';
      default:
        return null;
    }
  }

  void _sendChatMessage(String aiMessageId) {
    unawaited(
      _tryAgentFlow(aiMessageId, '').then((success) {
        if (!success &&
            mounted &&
            !_closeRequested &&
            _currentDispatchTurnId == aiMessageId) {
          _showAcpStartError(
            aiMessageId,
            LegacyTextLocalizer.isEnglish
                ? 'Failed to start the ACP session.'
                : 'ACP 会话启动失败，请稍后重试。',
          );
        }
      }),
    );
  }

  void _showAcpStartError(String taskId, String message) {
    if (!mounted) return;
    final messageId = '$taskId-error';
    setState(() {
      _messages.removeWhere((item) => item.isLoading);
      _messages.removeWhere((item) => item.id == messageId);
      _messages.insert(
        0,
        ChatMessageModel(
          id: messageId,
          type: 1,
          user: 2,
          content: <String, dynamic>{'text': message, 'id': messageId},
          isError: true,
        ),
      );
      _isAiResponding = false;
    });
    _resetDispatchState();
    unawaited(_saveConversationToDb());
  }

  void _onCancelTask() {
    try {
      _cancelRequested = true;
      // 检查是否有任何正在进行的活动
      if (_currentDispatchTurnId != null ||
          _currentAiMessages.isNotEmpty ||
          _isCheckingExecutableTask ||
          _isExecutingTask) {
        final conversationId = _currentConversationId;
        if (conversationId != null) {
          _runtimeCoordinator.interruptActiveToolCard(
            conversationId: conversationId,
            mode: _runtimeMode,
          );
        }
        // ACP owns cancellation through the original prompt response. Keep
        // the task reservation and loading projection until that response is
        // reduced; otherwise the event is dropped by the current-turn guard
        // and the native session can continue after this sheet looks idle.
        unawaited(_finishAcpCancellationPresentation());
      } else {
        unawaited(_finishAcpCancellationPresentation());
      }
      debugPrint('ACP cancellation requested');
    } catch (e) {
      debugPrint('onCancelTask error: $e');
    }
  }

  void _onCancelTaskFromCard(String taskId) {
    try {
      _cancelRequested = true;
      final conversationId = _currentConversationId;
      if (conversationId == null ||
          !_runtimeCoordinator.isTaskActive(
            taskId: taskId,
            conversationId: conversationId,
            mode: _runtimeMode,
          )) {
        // A stale card cannot own the current session. Refuse the request
        // instead of cancelling a newer prompt through shared session state.
        return;
      }
      _runtimeCoordinator.interruptActiveToolCard(
        conversationId: conversationId,
        mode: _runtimeMode,
      );
      // Keep the official ACP turn alive until its prompt response is
      // projected by the shared reducer. `taskId` remains the card identity;
      // the cancellation request itself uses the reserved session/turn.
      unawaited(_finishAcpCancellationPresentation());
    } catch (e) {
      debugPrint('onCancelTaskFromCard error: $e');
    }
  }

  void _onPopupVisibilityChanged(bool visible) {
    setState(() {
      _isPopupVisible = visible;
    });
  }

  @override
  Widget build(BuildContext context) {
    final screenHeight = MediaQuery.of(context).size.height;
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;
    final inputAreaHeight = _inputAreaHeight > 0 ? _inputAreaHeight : 72.0;
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final liftEmptyGreeting = _emptyGreetingKeyboardLiftTracker.resolveForBuild(
      bottomInset,
    );
    final homeGreetingSettings = HomeGreetingSettingsService.notifier.value;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _updateInputAreaMetrics();
    });
    return DraggableScrollableSheet(
      controller: _sheetController,
      initialChildSize: 0.75,
      minChildSize: 0.3,
      maxChildSize: 0.85,
      expand: false,
      snap: true, // 启用吸附效果，防止中间状态
      snapSizes: const [0.4, 0.75, 0.8], // 吸附点
      shouldCloseOnMinExtent: false, // 防止拖到最低时关闭 sheet
      builder: (context, scrollController) {
        return Stack(
          children: [
            // 隐藏的 SingleChildScrollView 用于附加 scrollController
            // 使用 NeverScrollableScrollPhysics 防止它响应滚动手势
            Positioned.fill(
              child: SingleChildScrollView(
                controller: scrollController,
                physics: const NeverScrollableScrollPhysics(),
                child: const SizedBox(height: 1),
              ),
            ),
            // 实际内容
            Listener(
              behavior: HitTestBehavior.translucent,
              onPointerDown: (event) => _handleOutsideTap(event.position),
              child: Container(
                decoration: BoxDecoration(
                  color: isDark
                      ? palette.pageBackground
                      : const Color(0xFFF9FCFF),
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(20),
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: isDark
                          ? Colors.black.withValues(alpha: 0.28)
                          : const Color(0x1A000000),
                      blurRadius: 20,
                      offset: const Offset(0, -4),
                    ),
                  ],
                ),
                child: Stack(
                  children: [
                    Column(
                      children: [
                        // 拖动指示条 - 仅用于拖动整个 sheet 高度
                        GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onVerticalDragUpdate: (details) {
                            final delta = details.primaryDelta ?? 0;
                            final currentSize = _sheetController.size;
                            // 向上拖动(delta<0)增大size，向下拖动(delta>0)减小size
                            final newSize =
                                currentSize - (delta / screenHeight);
                            _sheetController.jumpTo(newSize.clamp(0.4, 0.95));
                          },
                          child: Container(
                            width: double.infinity,
                            padding: const EdgeInsets.fromLTRB(0, 8, 0, 0),
                            child: Center(
                              child: Container(
                                width: 100,
                                height: 4,
                                decoration: BoxDecoration(
                                  color: isDark
                                      ? palette.borderStrong
                                      : const Color(0xFFCCCCCC),
                                  borderRadius: BorderRadius.circular(4),
                                ),
                              ),
                            ),
                          ),
                        ),
                        // AI 生成标识
                        const Padding(
                          padding: EdgeInsets.only(top: 4),
                          child: Align(
                            alignment: Alignment.center,
                            child: AiGeneratedBadge(),
                          ),
                        ),
                        // 消息列表 - 使用 NotificationListener 阻止滚动事件影响 sheet
                        Expanded(
                          child: NotificationListener<ScrollNotification>(
                            onNotification: (notification) {
                              _handleMessageScrollNotification(notification);
                              return true; // 阻止滚动事件冒泡到 sheet
                            },
                            child: _buildMessageList(),
                          ),
                        ),
                        // 输入框 - 根据 _isInputAreaVisible 控制显示
                        if (_isInputAreaVisible) _buildInputArea(),
                        SizedBox(height: bottomInset),
                      ],
                    ),
                    if (_messages.isEmpty &&
                        homeGreetingSettings.greetingEnabled)
                      AnimatedPositioned(
                        duration: const Duration(milliseconds: 280),
                        curve: Curves.easeInOutCubic,
                        left: 0,
                        right: 0,
                        top: liftEmptyGreeting ? 56 : 116,
                        child: IgnorePointer(
                          child: AnimatedAlign(
                            duration: const Duration(milliseconds: 280),
                            curve: Curves.easeInOutCubic,
                            alignment: liftEmptyGreeting
                                ? Alignment.centerLeft
                                : Alignment.center,
                            child: ChatEmptyGreeting(
                              compact: true,
                              primaryTextColor: palette.textPrimary,
                              secondaryTextColor: palette.textSecondary,
                              accentColor: palette.accentPrimary,
                              quickPrompts: homeGreetingSettings.quickPrompts,
                              pinnedQuickPromptIds:
                                  homeGreetingSettings.pinnedQuickPromptIds,
                              onQuickPromptSelected: _applyHomeQuickPrompt,
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
            Positioned(
              left: 0,
              right: 0,
              bottom: bottomInset + inputAreaHeight,
              child: _buildSlashCommandPanel(),
            ),
            // Popup menu 放在 Stack 层级，确保可以响应点击
            if (_isPopupVisible)
              Positioned(
                right: 24,
                bottom: bottomInset + 72,
                child:
                    _chatInputAreaKey.currentState?.buildPopupMenu() ??
                    const SizedBox.shrink(),
              ),
          ],
        );
      },
    );
  }

  Widget _buildMessageList() {
    if (_messages.isEmpty) {
      // 使用 GestureDetector 阻止手势穿透到原生层
      return GestureDetector(
        onVerticalDragUpdate: (_) {},
        behavior: HitTestBehavior.opaque,
        child: const SizedBox.expand(),
      );
    }

    if (_autoStickMessageListToLatest) {
      _scheduleMessageStickToLatest();
    }
    final timelineEntries = buildAgentRunTimelineEntries(
      _messages,
      conversationAgentId: _activeAcpAgentId,
      activeTaskIds: {
        ..._currentAiMessages.keys,
        ...(_runtimeCoordinator
                .runtimeFor(
                  conversationId: _currentConversationId ?? -1,
                  mode: _runtimeMode,
                )
                ?.activeAgentTurnIds ??
            const <String>{}),
      },
    );
    return Align(
      alignment: Alignment.topCenter,
      child: ListView.builder(
        controller: _messageScrollController,
        reverse: true,
        shrinkWrap: true,
        // 使用 ClampingScrollPhysics 阻止边界弹性效果，防止手势穿透到原生层
        // 这在悬浮窗模式下尤为重要，可以防止向下拖动时整个页面跟着移动
        physics: const ClampingScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 0),
        itemCount: timelineEntries.length,
        itemBuilder: (context, index) {
          final entry = timelineEntries[index];
          final isLastMessage = index == 0; // 最后一条消息（最新的）
          final isOldestMessage =
              index == timelineEntries.length - 1; // 最旧的一条消息
          // 只有当消息数量大于1时，最后一条消息才添加底部padding
          final needBottomPadding = isLastMessage && timelineEntries.length > 1;
          // 如果最旧的一条消息不是用户发送的，给顶部添加24的padding
          final needTopPadding = isOldestMessage && !entry.isUserMessage;
          final padding = EdgeInsets.only(
            top: needTopPadding ? 24.0 : 0.0,
            bottom: needBottomPadding ? 40.0 : 0.0,
          );
          if (entry.message != null) {
            final message = entry.message!;
            return Padding(
              padding: padding,
              child: MessageBubble(
                message: message,
                key: ValueKey(message.dbId ?? message.contentId ?? message.id),
                onBeforeTaskExecute: _handleBeforeTaskExecute,
                onCancelTask: _onCancelTaskFromCard,
                parentScrollController: _messageScrollController,
                onParentScrollHandoff: _handleParentScrollHandoff,
                onStreamingTextLayoutChanged: _handleStreamingTextLayoutChanged,
              ),
            );
          }

          final group = entry.group!;
          return Padding(
            padding: padding,
            child: AgentRunGroupMessage(
              key: ValueKey('overlay-agent-run-${group.taskId}'),
              group: group,
              useAcpPresentation: true,
              expanded: _expandedAgentRunTaskIds.contains(group.taskId),
              onToggleExpanded: () => _toggleAgentRunGroup(group.taskId),
              onBeforeTaskExecute: _handleBeforeTaskExecute,
              onCancelTask: _onCancelTaskFromCard,
              parentScrollController: _messageScrollController,
              onParentScrollHandoff: _handleParentScrollHandoff,
              onStreamingTextLayoutChanged: _handleStreamingTextLayoutChanged,
            ),
          );
        },
      ),
    );
  }

  Widget _buildSlashCommandPanel() {
    final visible = _showSlashCommandPanel || _openClawPanelExpanded;
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final panelTextColor = isDark
        ? palette.textPrimary
        : const Color(0xFF1F2937);
    final panelSecondaryTextColor = isDark
        ? palette.textSecondary
        : const Color(0xFF6B7280);
    final panelAccentColor = isDark
        ? palette.accentPrimary
        : const Color(0xFF2563EB);
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
          : Container(
              key: _openClawPanelKey,
              margin: const EdgeInsets.fromLTRB(24, 0, 24, 6),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: isDark ? palette.surfacePrimary : Colors.white,
                borderRadius: BorderRadius.circular(12),
                border: isDark ? Border.all(color: palette.borderSubtle) : null,
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: isDark ? 0.24 : 0.08),
                    blurRadius: 10,
                    offset: const Offset(0, 4),
                  ),
                ],
              ),
              child: _openClawPanelExpanded
                  ? Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'OpenClaw 配置',
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                            color: panelTextColor,
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
                                ? 'Leave empty if no token required'
                                : '为空表示无需 token',
                            isDense: true,
                          ),
                        ),
                        const SizedBox(height: 6),
                        TextField(
                          controller: _openClawUserIdController,
                          decoration: const InputDecoration(
                            labelText: 'User ID（可选）',
                            isDense: true,
                          ),
                        ),
                      ],
                    )
                  : Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        InkWell(
                          onTap: () => unawaited(
                            _startManualRecordingFromCommandPanel(),
                          ),
                          borderRadius: BorderRadius.circular(10),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 4),
                            child: Row(
                              children: [
                                Icon(
                                  Icons.route_rounded,
                                  size: 16,
                                  color: panelAccentColor,
                                ),
                                const SizedBox(width: 8),
                                Expanded(
                                  child: Text(
                                    '/record',
                                    style: TextStyle(
                                      fontSize: 13,
                                      fontWeight: FontWeight.w600,
                                      color: panelTextColor,
                                    ),
                                  ),
                                ),
                                Text(
                                  LegacyTextLocalizer.isEnglish
                                      ? 'Manual recording'
                                      : '手动录制',
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: panelSecondaryTextColor,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                        Divider(height: 12, color: palette.borderSubtle),
                        InkWell(
                          onTap: () {
                            _showOpenClawCommandPanel(expand: true);
                          },
                          borderRadius: BorderRadius.circular(10),
                          child: Row(
                            children: [
                              Icon(
                                Icons.link,
                                size: 16,
                                color: panelAccentColor,
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  'OpenClaw',
                                  style: TextStyle(
                                    fontSize: 13,
                                    fontWeight: FontWeight.w600,
                                    color: panelTextColor,
                                  ),
                                ),
                              ),
                              Text(
                                LegacyTextLocalizer.isEnglish ? 'Config' : '配置',
                                style: TextStyle(
                                  fontSize: 12,
                                  color: panelSecondaryTextColor,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
            ),
    );
  }

  Widget _buildInputArea() {
    return Container(
      key: _inputAreaKey,
      padding: const EdgeInsets.fromLTRB(24, 8, 24, 20),
      child: ChatInputArea(
        key: _chatInputAreaKey,
        controller: _messageController,
        focusNode: _inputFocusNode,
        isProcessing: _isAiResponding,
        onSendMessage: _sendMessage,
        onCancelTask: _onCancelTask,
        onPopupVisibilityChanged: _onPopupVisibilityChanged,
        onInputHeightChanged: _onInputHeightChanged,
        openClawEnabled: _openClawEnabled,
        onToggleOpenClaw: _setOpenClawEnabled,
        useLargeComposerStyle: true,
        useAttachmentPickerForPlus: true,
        onPickAttachment: _pickAttachments,
        attachments: _pendingAttachments,
        onRemoveAttachment: _removePendingAttachment,
      ),
    );
  }
}
