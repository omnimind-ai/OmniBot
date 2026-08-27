import 'dart:async';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_link_preview.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/ai_chat_service.dart';
import 'widgets/message_bubble.dart';
import 'widgets/chat_input_area.dart';
import 'package:ui/utils/data_parser.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/agent_stream_meta.dart';
import 'package:ui/features/home/pages/command_overlay/services/chat_service.dart';
import 'package:ui/features/home/pages/command_overlay/constants/messages.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_flow_controller.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_result_card.dart';
import 'package:ui/features/home/pages/command_overlay/utils/deep_thinking_parser.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/utils/stream_text_merge.dart';
import 'package:ui/features/home/pages/chat/utils/agent_thinking_card_locator.dart';
import 'package:ui/features/home/pages/chat/utils/deep_thinking_persistence.dart';
import 'package:ui/features/home/pages/chat/utils/keyboard_inset_motion_tracker.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_group_message.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_empty_greeting.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/screen_dialog_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/home_greeting_settings_service.dart';
import 'package:ui/services/link_preview_service.dart';
import 'package:ui/widgets/ai_generated_badge.dart';
import 'package:ui/constants/openclaw/openclaw_keys.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/features/home/pages/chat/mixins/agent_stream_handler.dart';
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
    with WidgetsBindingObserver, AgentStreamHandler {
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

  late AiChatService _aiService;
  bool _isAiResponding = false;
  bool _isCheckingExecutableTask = false; // 是否正在判断可执行任务
  bool _isPopupVisible = false;

  final Map<String, String> _currentAiMessages = {};
  final Set<String> _expandedAgentRunTaskIds = <String>{};
  bool _autoStickMessageListToLatest = true;
  bool _messageStickToLatestScheduled = false;
  bool _messageListScrollWasUserDriven = false;
  static const double _messageLatestEdgeTolerance = 48.0;

  // 流式思考内容相关状态
  String _deepThinkingContent = '';
  bool _isDeepThinking = false;
  String? _currentDispatchTaskId;

  int _currentThinkingStage = 1; // 当前思考阶段：1-识别需求，2-规划任务，3-帮你规划任务，4-完成思考

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

  // ===================== AgentStreamHandler mixin 接口实现 =====================

  @override
  String? get currentDispatchTaskId => _currentDispatchTaskId;

  @override
  String get deepThinkingContent => _deepThinkingContent;

  @override
  set deepThinkingContent(String value) {
    _deepThinkingContent = value;
  }

  @override
  bool get isDeepThinking => _isDeepThinking;

  @override
  set isDeepThinking(bool value) {
    _isDeepThinking = value;
  }

  @override
  int get currentThinkingStage => _currentThinkingStage;

  @override
  set currentThinkingStage(int value) {
    _currentThinkingStage = value;
  }

  void _persistDeepThinkingCardIfNeeded(ChatMessageModel message) {
    final conversationId = _currentConversationId;
    final cardData = message.cardData;
    if (conversationId == null ||
        message.type != 2 ||
        cardData?['type'] != 'deep_thinking') {
      return;
    }
    unawaited(
      ConversationHistoryService.upsertConversationUiCard(
        conversationId,
        entryId: message.id,
        cardData: buildPersistentDeepThinkingCardData(
          Map<String, dynamic>.from(cardData!),
        ),
        createdAtMillis: message.createAt.millisecondsSinceEpoch,
      ),
    );
  }

  void _persistThinkingCardForTask(String taskID, {String? cardId}) {
    final thinkingCardId = cardId ?? '$taskID-thinking';
    final index = _messages.indexWhere((msg) => msg.id == thinkingCardId);
    if (index != -1) {
      _persistDeepThinkingCardIfNeeded(_messages[index]);
    }
  }

  @override
  List<ChatMessageModel> get messages => _messages;

  @override
  bool get isAiResponding => _isAiResponding;

  @override
  set isAiResponding(bool value) {
    _isAiResponding = value;
  }

  @override
  void createThinkingCard(String taskID) => _createThinkingCard(taskID);

  @override
  void updateThinkingCard(String taskID) => _updateThinkingCard(taskID);

  @override
  void createThinkingCardForAgent(
    String taskID, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
  }) => _createThinkingCard(
    taskID,
    cardId: cardId,
    thinkingContent: thinkingContent,
    isLoading: isLoading,
    stage: stage,
    streamMeta: streamMeta,
  );

  @override
  void updateThinkingCardForAgent(
    String taskID, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
    bool lockCompleted = true,
  }) => _updateThinkingCard(
    taskID,
    cardId: cardId,
    thinkingContent: thinkingContent,
    isLoading: isLoading,
    stage: stage,
    streamMeta: streamMeta,
    lockCompleted: lockCompleted,
  );

  @override
  void resetDispatchState() => _resetDispatchState();

  @override
  void fallbackToChat(String taskID) => _fallbackToChat(taskID);

  @override
  Future<void> persistAgentConversation() => _saveConversationToDb();

  @override
  void clearAgentStreamSessionState() {
    super.clearAgentStreamSessionState();
  }

  @override
  void onAgentTextMessageUpdated(String messageId, {bool isFinal = true}) {
    if (isFinal) {
      _syncMessageLinkPreviews(messageId);
    }
  }

  @override
  void initState() {
    super.initState();

    WidgetsBinding.instance.addObserver(this);
    HomeGreetingSettingsService.notifier.addListener(
      _handleHomeGreetingSettingsChanged,
    );
    unawaited(HomeGreetingSettingsService.load());
    _aiService = AiChatService();

    _aiService.setOnMessageCallback((taskId, content, type) {
      _handleAiMessage(taskId, content, type);
    });

    _aiService.setOnMessageEndCallback((
      taskId, {
      Map<String, dynamic>? turnUsage,
    }) {
      _handleAiMessageEnd(taskId);
    });

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

    // 设置dispatch流式回调
    AssistsMessageService.setOnDispatchStreamDataCallBack((
      taskID,
      data,
      fullContent,
    ) {
      if (!mounted) return;
      _handleDispatchStreamData(taskID, data, fullContent);
    });
    AssistsMessageService.setOnDispatchStreamEndCallBack((taskID, fullContent) {
      if (!mounted) return;
      _handleDispatchStreamEnd(taskID, fullContent);
    });
    AssistsMessageService.setOnDispatchStreamErrorCallBack((
      taskID,
      error,
      fullContent,
      isRateLimited,
    ) {
      if (!mounted) return;
      _handleDispatchStreamError(taskID, error, fullContent, isRateLimited);
    });

    // 页面关闭回调
    ScreenDialogService.setOnBeforeCloseChatBotDialog(_onDialogClose);

    AssistsMessageService.setOnAgentStreamEventCallback(
      _handleIncomingAgentStreamEvent,
    );
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

  Future<void> _startManualRecordingFromCommandPanel() async {
    _messageController.clear();
    _hideSlashCommandPanel();
    await _startManualRecordingCommand('/record');
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
    _saveConversationToDb(generateSummary: true, markComplete: true);
  }

  void _handleIncomingAgentStreamEvent(AgentStreamEvent event) {
    if (!mounted) return;
    handleAgentStreamEvent(event);
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
    WidgetsBinding.instance.removeObserver(this);
    HomeGreetingSettingsService.notifier.removeListener(
      _handleHomeGreetingSettingsChanged,
    );
    _messageController.removeListener(_handleSlashCommandInput);
    _messageController.dispose();
    _messageScrollController.dispose();
    _sheetController.dispose();
    _aiService.dispose();
    _inputFocusNode.dispose();
    _openClawBaseUrlController.dispose();
    _openClawTokenController.dispose();
    _openClawUserIdController.dispose();
    // 清理dispatch流式回调
    AssistsMessageService.setOnDispatchStreamDataCallBack(null);
    AssistsMessageService.setOnDispatchStreamEndCallBack(null);
    AssistsMessageService.setOnDispatchStreamErrorCallBack(null);
    AssistsMessageService.removeOnAgentStreamEventCallback(
      _handleIncomingAgentStreamEvent,
    );
    super.dispose();
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

  /// 移除最新的loading消息（如果存在）
  void _removeLatestLoadingIfExists() {
    if (_messages.isNotEmpty && _messages[0].isLoading) {
      setState(() {
        _messages.removeAt(0);
      });
    }
  }

  void _handleAiMessage(String taskId, String content, String? type) async {
    final isErrorMessage = type == 'error';
    final isRateLimited = type == 'rate_limited';
    final prefillTokensPerSecond = extractChatTaskPrefillTokensPerSecond(
      content,
    );
    final decodeTokensPerSecond = extractChatTaskDecodeTokensPerSecond(content);
    final hasPerformanceMetrics =
        prefillTokensPerSecond != null || decodeTokensPerSecond != null;
    final String messageText;
    final bool isError;
    final bool isSummarizing;
    bool shouldUpdateAiMessage = true;

    // 首次收到消息时移除loading（检查是否是新的taskId）
    final isFirstChunk = !_currentAiMessages.containsKey(taskId);
    if (isFirstChunk) {
      _removeLatestLoadingIfExists();
    }

    if (isRateLimited) {
      // 处理 429 限流错误
      messageText = kRateLimitErrorMessage;
      isError = true;
      isSummarizing = false;
      _currentAiMessages.remove(taskId);
    } else if (isErrorMessage) {
      messageText = kNetworkErrorMessage;
      isError = true;
      isSummarizing = false;
      _currentAiMessages.remove(taskId);
    } else {
      final text = extractChatTaskText(content, fallbackToRawText: false);
      if (text.isNotEmpty) {
        _currentAiMessages[taskId] = mergeLegacyStreamingText(
          _currentAiMessages[taskId] ?? '',
          text,
        );
      }
      messageText = _currentAiMessages[taskId] ?? '';
      isError = false;
      isSummarizing = false;
      shouldUpdateAiMessage =
          messageText.isNotEmpty ||
          (hasPerformanceMetrics &&
              _messages.any((message) => message.id == taskId));
    }

    if (shouldUpdateAiMessage) {
      _updateOrAddAiMessage(
        taskId,
        messageText,
        isError,
        isSummarizing: isSummarizing,
        prefillTokensPerSecond: prefillTokensPerSecond,
        decodeTokensPerSecond: decodeTokensPerSecond,
      );
    }
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

  void _updateOrAddAiMessage(
    String taskId,
    String text,
    bool isError, {
    bool isSummarizing = false,
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
  }) {
    final index = _messages.indexWhere((msg) => msg.id == taskId);

    setState(() {
      if (index == -1) {
        final content = <String, dynamic>{'text': text, 'id': taskId};
        if (prefillTokensPerSecond != null) {
          content['prefillTokensPerSecond'] = prefillTokensPerSecond;
        }
        if (decodeTokensPerSecond != null) {
          content['decodeTokensPerSecond'] = decodeTokensPerSecond;
        }
        _messages.insert(
          0,
          ChatMessageModel(
            id: taskId,
            type: 1,
            user: 2,
            content: content,
            isLoading: false,
            isError: isError,
            isSummarizing: isSummarizing,
          ),
        );
      } else {
        final existing = _messages[index];
        final content = Map<String, dynamic>.from(existing.content ?? {});
        final existingText = content['text'] as String? ?? '';
        content['text'] = text.isNotEmpty ? text : existingText;
        if (prefillTokensPerSecond != null) {
          content['prefillTokensPerSecond'] = prefillTokensPerSecond;
        }
        if (decodeTokensPerSecond != null) {
          content['decodeTokensPerSecond'] = decodeTokensPerSecond;
        }
        _messages[index] = existing.copyWith(
          content: content,
          isLoading: false,
          isError: isError,
          isSummarizing: isSummarizing,
        );
      }
    });
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
          ConversationHistoryService.saveConversationMessages(
            conversationId,
            List<ChatMessageModel>.from(_messages),
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
      await ConversationHistoryService.saveConversationMessages(
        conversationId,
        List<ChatMessageModel>.from(_messages),
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

  void _handleAiMessageEnd(String taskId) async {
    setState(() => _isAiResponding = false);

    final index = _messages.indexWhere((msg) => msg.id == taskId);
    final isErrorMessage = index != -1 && _messages[index].isError;
    final messageText = isErrorMessage
        ? (_messages[index].content?['text'] as String? ?? '')
        : (_currentAiMessages[taskId] ?? '');

    if (messageText.isNotEmpty && index != -1) {
      setState(() {
        final existing = _messages[index];
        _messages[index] = existing.copyWith(content: existing.content);
        _syncMessageLinkPreviews(taskId);
      });
    }
    _currentAiMessages.remove(taskId);
    await _saveConversationToDb();
  }

  /// 处理dispatch流式数据
  void _handleDispatchStreamData(
    String taskID,
    String data,
    String fullContent,
  ) {
    if (_currentDispatchTaskId != taskID) return;

    // 增量解析deep_thinking内容
    final result = DeepThinkingParser.extractDeepThinking(fullContent);

    if (result.hasAnyContent) {
      _deepThinkingContent = result.toDisplayText();

      // 当 deep_thinking 字段完整返回后，结束思考计时
      if (result.isDeepThinkingComplete && _isDeepThinking) {
        _isDeepThinking = false;
      }

      // 更新思考卡片内容
      _updateThinkingCard(taskID);
    }
  }

  /// 创建thinking卡片（首次收到有效内容时调用）
  void _createThinkingCard(
    String taskID, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
  }) {
    // 移除loading消息
    final loadingIndex = _messages.indexWhere((msg) => msg.id == taskID);
    if (loadingIndex != -1) {
      setState(() => _messages.removeAt(loadingIndex));
    }

    final startTime = DateTime.now().millisecondsSinceEpoch;
    final thinkingCardId = cardId ?? '$taskID-thinking';
    final cardData = {
      'type': 'deep_thinking',
      'isLoading': isLoading ?? _isDeepThinking,
      'thinkingContent': thinkingContent ?? '',
      'stage': stage ?? _currentThinkingStage,
      'taskID': taskID, // 添加taskID用于创建稳定的key
      'cardId': thinkingCardId,
      'startTime': startTime, // 添加开始时间
      'endTime': null, // 结束时间初始为null
    };

    setState(() {
      _messages.insert(
        0,
        ChatMessageModel(
          id: thinkingCardId,
          type: 2,
          user: 3,
          content: {'cardData': cardData, 'id': thinkingCardId},
          createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
          streamMeta: ensureAgentStreamMessageMeta(
            streamMeta,
            entryId: thinkingCardId,
          ),
        ),
      );
    });
  }

  /// 更新thinking卡片内容
  void _updateThinkingCard(
    String taskID, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
    bool lockCompleted = true,
  }) {
    final thinkingCardId = cardId ?? '$taskID-thinking';
    final index = _messages.indexWhere((msg) => msg.id == thinkingCardId);
    if (index != -1) {
      setState(() {
        final existing = _messages[index];
        final content = Map<String, dynamic>.from(existing.content ?? {});
        final cardData = Map<String, dynamic>.from(content['cardData'] ?? {});

        // 如果卡片已经完成（stage=4），不允许改回其他状态
        final currentStage = cardData['stage'] as int? ?? 1;
        final targetStage = stage ?? _currentThinkingStage;
        final newStage = (lockCompleted && currentStage == 4) ? 4 : targetStage;

        // 保留开始时间（从现有 cardData 中读取）
        final startTime = cardData['startTime'] as int?;

        // 如果思考完成且还没有结束时间，记录结束时间
        int? endTime = cardData['endTime'] as int?;
        if (newStage == 4 && endTime == null) {
          endTime = DateTime.now().millisecondsSinceEpoch;
        }

        // 更新卡片数据
        cardData['thinkingContent'] = thinkingContent ?? _deepThinkingContent;
        cardData['isLoading'] = isLoading ?? _isDeepThinking;
        cardData['stage'] = newStage;
        cardData['taskID'] = taskID; // 显式保留 taskID，确保 Widget key 不会变化
        cardData['cardId'] = thinkingCardId;
        cardData['startTime'] = startTime; // 保留开始时间
        cardData['endTime'] = endTime; // 更新结束时间

        content['cardData'] = cardData;
        _messages[index] = existing.copyWith(
          content: content,
          streamMeta: ensureAgentStreamMessageMeta(
            streamMeta ?? existing.streamMeta,
            entryId: thinkingCardId,
          ),
        );
      });
    }
  }

  /// 处理dispatch流式结束
  void _handleDispatchStreamEnd(String taskID, String fullContent) async {
    if (_currentDispatchTaskId != taskID) return;

    // 更新思考状态为完成
    _isDeepThinking = false;
    _updateThinkingCard(taskID);

    // 调用post-process接口
    _persistThinkingCardForTask(taskID);
    await _callDispatchPostProcess(taskID, fullContent);
  }

  /// 处理dispatch流式错误
  void _handleDispatchStreamError(
    String taskID,
    String error,
    String fullContent,
    bool isRateLimited,
  ) {
    if (_currentDispatchTaskId != taskID) return;

    // 更新思考状态为完成
    _isDeepThinking = false;
    _updateThinkingCard(taskID);

    // 如果是429限流错误，直接显示限流提示，不再走后续流程
    _persistThinkingCardForTask(taskID);
    if (isRateLimited) {
      _handleRateLimitError(taskID);
      _resetDispatchState();
      return;
    }

    // 如果有部分内容，尝试调用post-process
    if (fullContent.isNotEmpty) {
      _callDispatchPostProcess(taskID, fullContent);
    } else {
      // 没有内容，显示错误
      _handleDispatchError(taskID, error);
    }
  }

  /// 调用dispatch post-process接口
  Future<void> _callDispatchPostProcess(
    String taskID,
    String llmRawMessage,
  ) async {
    _fallbackToChat(taskID);
    _resetDispatchState();
  }

  /// 处理dispatch错误
  void _handleDispatchError(String taskID, String error) {
    _persistThinkingCardForTask(taskID);
    setState(() {
      _isAiResponding = false;
      // 移除 loading 消息（如果还存在）
      _messages.removeWhere((msg) => msg.id == taskID && msg.isLoading);
      _messages.insert(
        0,
        ChatMessageModel(
          id: taskID,
          type: 1,
          user: 2,
          content: {
            'text': LegacyTextLocalizer.isEnglish
                ? 'Omnibot is busy right now. Please try again in a moment.'
                : '小万忙不过来了，等会儿再试试吧',
            'id': taskID,
          },
          isError: true,
        ),
      );
    });
    _resetDispatchState();
  }

  /// 旧分发链路兜底（开源版不再回退普通聊天）
  void _fallbackToChat(String taskID) {
    // 更新思考卡片为完成状态
    _currentThinkingStage = 4;
    _isDeepThinking = false;
    _updateThinkingCard(taskID);
    _persistThinkingCardForTask(taskID);
    setState(() {
      _isAiResponding = false;
      _messages.removeWhere((msg) => msg.id == taskID && msg.isLoading);
      _messages.insert(
        0,
        ChatMessageModel(
          id: '$taskID-disabled',
          type: 1,
          user: 2,
          content: {
            'text': LegacyTextLocalizer.isEnglish
                ? 'Unified Agent is enabled. The legacy chat dispatch has been removed. Please check your model config and try again.'
                : '统一 Agent 已启用，旧聊天分发链路已移除，请检查模型配置后重试。',
            'id': '$taskID-disabled',
          },
          isError: true,
        ),
      );
    });
    _resetDispatchState();
  }

  /// 重置dispatch状态
  void _resetDispatchState() {
    _currentDispatchTaskId = null;
    _deepThinkingContent = '';
    _isDeepThinking = false;
    clearAgentStreamSessionState();
    // 注意：不重置 _currentThinkingStage，避免影响已完成的思考卡片显示
    // _currentThinkingStage = 1;
  }

  List<Map<String, dynamic>> _buildConversationHistory() {
    final List<Map<String, dynamic>> history = [];
    final recentMessages = ChatService.getRecentMessages(
      _messages,
      maxCount: 10,
    );

    for (final message in recentMessages) {
      if (message.user == 1) {
        final text = _buildMessageTextForModel(message);
        if (text.isNotEmpty) {
          history.insert(0, {'role': 'user', 'content': text});
        }
      } else if (message.user == 2) {
        final text = message.content?['text'] as String? ?? '';
        if (text.isNotEmpty) {
          history.insert(0, {'role': 'assistant', 'content': text});
        }
      }
    }
    return history;
  }

  List<Map<String, dynamic>> _buildOpenClawHistory() {
    final latest = _latestUserUtterance();
    if (latest.isEmpty) return _buildConversationHistory();
    return [
      {'role': 'user', 'content': latest},
    ];
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
        _currentDispatchTaskId == messageIds.aiMessageId) {
      handleAgentError(
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
      return result['function'] is Map
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
    try {
      setState(() {
        _currentDispatchTaskId = aiMessageId;
        _deepThinkingContent = '';
        _isDeepThinking = false;
        _currentThinkingStage = 1;
      });

      _createThinkingCard(aiMessageId);

      final userMessage = _latestUserUtterance();
      final attachments = _latestUserAgentAttachments();
      final success = await AssistsMessageService.createAgentTask(
        taskId: aiMessageId,
        userMessage: userMessage,
        attachments: attachments,
        conversationId: _currentConversationId,
        conversationMode: ConversationMode.normal.storageValue,
      );

      return success;
    } catch (e) {
      debugPrint('Agent flow error: $e');
      return false;
    }
  }

  List<Map<String, dynamic>> _historyBeforeLatestUser(
    List<Map<String, dynamic>> history,
  ) {
    if (history.isEmpty) return history;
    final normalized = List<Map<String, dynamic>>.from(history);
    final last = normalized.last;
    if ((last['role'] as String?) == 'user') {
      normalized.removeLast();
    }
    return normalized;
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
          final path = file.path;
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

  /// 处理 429 限流错误
  void _handleRateLimitError(String aiMessageId) {
    setState(() {
      _isAiResponding = false;
      // 移除 loading 消息（如果还存在）
      _messages.removeWhere((msg) => msg.id == aiMessageId && msg.isLoading);
      _messages.insert(
        0,
        ChatMessageModel(
          id: aiMessageId,
          type: 1,
          user: 2,
          content: {'text': kRateLimitErrorMessage, 'id': aiMessageId},
          isError: true,
        ),
      );
    });
  }

  void _sendChatMessage(String aiMessageId) {
    if (!_openClawEnabled) {
      handleAgentError(
        LegacyTextLocalizer.isEnglish
            ? 'Unified Agent is enabled. The legacy chat path has been removed. Please check config and try again.'
            : '统一 Agent 已启用，旧聊天链路已移除，请检查配置后重试。',
      );
      return;
    }
    final history = _buildOpenClawHistory();
    final openClawConfig = {
      'baseUrl': _openClawBaseUrl,
      if (_openClawToken.isNotEmpty) 'token': _openClawToken,
      if (_openClawUserId.isNotEmpty) 'userId': _openClawUserId,
      if (_openClawUserId.isNotEmpty)
        'sessionKey': 'openclaw:${_openClawUserId.trim()}',
    };
    final Future<bool> sendFuture = _aiService.sendMessageWithProvider(
      aiMessageId,
      history,
      provider: 'openclaw',
      openClawConfig: openClawConfig,
    );
    sendFuture.catchError((error) {
      final errorId = DateTime.now().millisecondsSinceEpoch.toString();
      setState(() {
        _isAiResponding = false;
        // 移除 loading 消息
        _removeLatestLoadingIfExists();
        _messages.insert(
          0,
          ChatMessageModel(
            id: errorId,
            type: 1,
            user: 2,
            content: {
              'text': LegacyTextLocalizer.isEnglish
                  ? 'Sorry, failed to send message: $error'
                  : '抱歉，发送消息失败：$error',
              'id': errorId,
            },
          ),
        );
      });
      return false;
    });
  }

  void _onCancelTask() {
    try {
      // 检查是否有任何正在进行的活动
      if (_currentDispatchTaskId != null ||
          _currentAiMessages.isNotEmpty ||
          _isCheckingExecutableTask ||
          _isExecutingTask) {
        interruptActiveToolCard();
        final taskId =
            _currentDispatchTaskId ??
            (_currentAiMessages.isEmpty ? null : _currentAiMessages.keys.first);
        AssistsMessageService.cancelRunningTask(taskId: taskId);
        if (taskId != null) {
          _updateThinkingCardToCancelled(taskId);
          _upsertCancelledAgentRunMessage(taskId);
          _collapseAgentRunTrace(taskId);
        }
        _resetDispatchState();
      } else {
        AssistsMessageService.cancelChatTask(
          taskId: _currentAiMessages.keys.isEmpty
              ? null
              : _currentAiMessages.keys.first,
        );
      }

      setState(() {
        _isAiResponding = false;
        _isCheckingExecutableTask = false; // 清理检查状态
        // 移除 loading 消息
        _messages.removeWhere((msg) => msg.isLoading);
      });

      debugPrint('Task cancelled, all states reset');
    } catch (e) {
      debugPrint('onCancelTask error: $e');
    }
  }

  void _onCancelTaskFromCard(String taskId) {
    try {
      interruptActiveToolCard();
      AssistsMessageService.cancelRunningTask(taskId: taskId);
      _updateThinkingCardToCancelled(taskId);
      _upsertCancelledAgentRunMessage(taskId);
      _collapseAgentRunTrace(taskId);
      _resetDispatchState();
      setState(() {
        _isAiResponding = false;
        _isExecutingTask = false;
        _isInputAreaVisible = true;
        _messages.removeWhere((msg) => msg.isLoading);
      });
    } catch (e) {
      debugPrint('onCancelTaskFromCard error: $e');
    }
  }

  void _updateThinkingCardToCancelled(String taskID) {
    final thinkingCard = resolveAgentThinkingCardForTask(
      _messages,
      taskId: taskID,
    );
    if (thinkingCard == null) return;
    final thinkingCardId = thinkingCard.id;
    final index = _messages.indexWhere((msg) => msg.id == thinkingCardId);
    if (index == -1) return;

    final cardData = Map<String, dynamic>.from(thinkingCard.cardData ?? {});
    cardData['stage'] = 5;
    cardData['isLoading'] = false;
    if (cardData['endTime'] == null) {
      cardData['endTime'] = DateTime.now().millisecondsSinceEpoch;
    }

    setState(() {
      _messages[index] = ChatMessageModel(
        id: thinkingCardId,
        type: 2,
        user: 3,
        content: {'cardData': cardData, 'id': thinkingCardId},
        createAt: thinkingCard.createAt,
      );
    });
    _persistDeepThinkingCardIfNeeded(_messages[index]);
  }

  void _collapseAgentRunTrace(String taskId) {
    final normalizedTaskId = taskId.trim();
    if (normalizedTaskId.isEmpty ||
        !_expandedAgentRunTaskIds.contains(normalizedTaskId)) {
      return;
    }
    setState(() {
      _expandedAgentRunTaskIds.remove(normalizedTaskId);
    });
  }

  void _upsertCancelledAgentRunMessage(String taskId) {
    final normalizedTaskId = taskId.trim();
    if (normalizedTaskId.isEmpty) {
      return;
    }
    final messageId = '$normalizedTaskId-cancelled';
    final content = <String, dynamic>{
      'text': LegacyTextLocalizer.localize('任务已取消'),
      'id': messageId,
      'renderMarkdown': false,
    };
    final streamMeta = ensureAgentStreamMessageMeta(
      null,
      seq: 1000000000,
      roundIndex: 1000000000,
      kind: 'text_snapshot',
      parentTaskId: normalizedTaskId,
      entryId: messageId,
      isFinal: true,
    );
    final existingIndex = _messages.indexWhere(
      (message) => message.id == messageId,
    );
    setState(() {
      if (existingIndex == -1) {
        _messages.insert(
          0,
          ChatMessageModel(
            id: messageId,
            type: 1,
            user: 2,
            content: content,
            streamMeta: streamMeta,
          ),
        );
      } else {
        _messages[existingIndex] = _messages[existingIndex].copyWith(
          content: content,
          isLoading: false,
          isError: false,
          streamMeta: streamMeta,
        );
      }
    });
    unawaited(
      _saveConversationToDb(generateSummary: false, markComplete: true),
    );
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
      activeTaskIds: {
        ..._currentAiMessages.keys,
        ...activeAgentStreamTaskIds(),
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
