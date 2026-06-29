// ignore_for_file: unused_element, unused_element_parameter

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher_string.dart';
import '../../../../models/conversation_model.dart';
import '../../../../models/conversation_thread_target.dart';
import '../../../../models/chat_link_preview.dart';
import '../../../../models/chat_message_model.dart';
import '../../../../services/agent_stream_meta.dart';
import '../../../../services/assists_core_service.dart';
import '../../widgets/home_drawer.dart';
import '../authorize/authorize_page_args.dart';
import '../command_overlay/widgets/chat_input_area.dart';
import '../command_overlay/services/tool_card_detail_gesture_gate.dart';
import '../common/openclaw_connection_checker.dart';
import '../omnibot_workspace/widgets/omnibot_workspace_browser.dart';
import 'services/chat_conversation_lifecycle_guard.dart';
import 'services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/constants/openclaw/openclaw_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/widgets/permission_bottom_sheet.dart';
import 'package:ui/services/app_state_service.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/services/agent_browser_session_service.dart';
import 'package:ui/services/chat_terminal_environment_service.dart';
import 'package:ui/services/codex_app_server_service.dart';
import 'package:ui/services/codex_diff_parser.dart';
import 'package:ui/services/codex_tool_call_parser.dart';
import 'package:ui/services/conversation_model_override_service.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/device_service.dart';
import 'package:ui/services/home_greeting_settings_service.dart';
import 'package:ui/services/link_preview_service.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/model_vendor_catalog.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/permission_registry.dart';
import 'package:ui/services/permission_service.dart';
import 'package:ui/services/scene_model_config_service.dart';
import 'package:ui/services/shared_open_draft_service.dart';
import 'package:ui/features/local_model/local_model_feature.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_startup_behavior.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/utils/agent_runtime_attachment_payload.dart';
import 'package:ui/features/home/pages/chat/utils/agent_thinking_card_locator.dart';
import 'package:ui/features/home/pages/chat/utils/codex_slash_commands.dart';
import 'package:ui/features/home/pages/chat/utils/deep_thinking_persistence.dart';
import 'package:ui/features/home/pages/chat/utils/composer_lift_intent_tracker.dart';
import 'package:ui/features/home/pages/chat/utils/composer_keyboard_metrics_tracker.dart';
import 'package:ui/features/home/pages/chat/utils/keyboard_inset_motion_tracker.dart';
import 'package:ui/features/home/pages/codex/codex_remote_directory_picker.dart';
import 'package:ui/features/home/pages/codex/codex_remote_workspace_browser.dart';
import 'package:ui/widgets/chat_drawer_gesture_guard.dart';

// 导入 Mixins
import 'mixins/chat_message_handler.dart';
import 'mixins/dispatch_stream_handler.dart';
import 'mixins/agent_stream_handler.dart';
import 'mixins/task_execution_handler.dart';
import 'mixins/conversation_manager.dart';

// 导入 Widgets
import 'chat_page_models.dart';
import 'tool_activity_utils.dart';
import 'widgets/chat_widgets.dart';
import 'widgets/chat_browser_overlay.dart';
import 'widgets/chat_tool_activity_strip.dart';
import 'package:ui/widgets/app_update_dialog.dart';
import 'package:ui/widgets/app_background_widgets.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';

part 'chat_page_browser.dart';
part 'chat_page_lifecycle.dart';
part 'chat_page_model_context.dart';
part 'chat_page_openclaw.dart';
part 'chat_page_terminal_env.dart';
part 'chat_page_codex.dart';
part 'chat_page_conversation_flow.dart';
part 'chat_page_ui.dart';

enum ChatPageMode { normal, openclaw, codex }

enum _SlashCommandPanelRoute { root, effort, codexModel }

class ChatPage extends StatefulWidget {
  final ConversationThreadTarget? threadTarget;

  const ChatPage({super.key, this.threadTarget});

  @override
  State<ChatPage> createState() => _ChatPageState();
}

abstract class _ChatPageStateBase extends State<ChatPage>
    with
        WidgetsBindingObserver,
        ChatMessageHandler,
        DispatchStreamHandler,
        AgentStreamHandler,
        TaskExecutionHandler,
        ConversationManager
    implements RouteAware {
  // ===================== Controllers =====================
  final TextEditingController _messageController = TextEditingController();
  final ScrollController _normalMessageScrollController = ScrollController();
  final ScrollController _openClawMessageScrollController = ScrollController();
  final ScrollController _codexMessageScrollController = ScrollController();
  final PageController _modePageController = PageController(initialPage: 0);
  final FocusNode _inputFocusNode = FocusNode();
  final TextEditingController _vlmAnswerController = TextEditingController();
  final TextEditingController _normalUserMessageEditController =
      TextEditingController();
  final TextEditingController _openClawUserMessageEditController =
      TextEditingController();
  final TextEditingController _codexUserMessageEditController =
      TextEditingController();

  // ===================== Keys =====================
  final GlobalKey<ChatInputAreaState> _chatInputAreaKey =
      GlobalKey<ChatInputAreaState>();
  final GlobalKey<ScaffoldState> _scaffoldKey = GlobalKey<ScaffoldState>();
  final GlobalKey<HomeDrawerState> _drawerKey = GlobalKey<HomeDrawerState>();
  final GlobalKey _browserOverlayKey = GlobalKey();
  final GlobalKey _slashCommandStripKey = GlobalKey();
  final GlobalKey _toolActivityStripKey = GlobalKey();

  /// 模型选择器走 OverlayEntry，不走 Navigator.push。
  /// 理由：[Navigator.push] → [ModalRoute.didPush] 会调 `setFirstFocus`
  /// (条件是 **Navigator** 的 `requestFocus`,Route 的 `requestFocus` 不起作用)，
  /// 把焦点从输入框抢走 → 软键盘塌陷 → 输入栏下沉 → popup 锚点错位。
  /// 用 Overlay 直接挂面板可以彻底跳过这条路径。
  OverlayGlassPopupHandle<_ChatModelOverrideSelection>?
  _conversationModelSelectorHandle;

  // ===================== State =====================
  bool _isPopupVisible = false;
  bool _isCheckingSendModelConfiguration = false;
  final ChatConversationRuntimeCoordinator _runtimeCoordinator =
      ChatConversationRuntimeCoordinator.instance;
  final ChatConversationLifecycleGuard _conversationLifecycleGuard =
      ChatConversationLifecycleGuard();
  ConversationThreadTarget? _resolvedThreadTarget;
  SharedOpenDraftPayload? _stagedSharedOpenDraft;
  int? _stagedSharedOpenDraftExpiresAt;
  int _conversationTargetRequestId = 0;

  // OpenClaw 配置与开关
  bool _openClawEnabled = false;
  String _openClawBaseUrl = '';
  String _openClawToken = '';
  String _openClawUserId = '';
  ChatSurfaceMode _activeSurfaceMode = ChatSurfaceMode.normal;
  ChatPageMode _activeConversationMode = ChatPageMode.normal;
  bool _showSlashCommandPanel = false;
  bool _showModelMentionPanel = false;
  bool _openClawPanelExpanded = false;
  _ActiveModelMentionToken? _activeModelMentionToken;
  List<ModelProviderProfileSummary> _modelProviderProfiles = const [];
  Map<String, List<ProviderModelOption>> _modelOptionsByProfileId = const {};
  List<SceneCatalogItem> _sceneCatalog = const [];
  int _dispatchSceneModelSelectionSerial = 0;
  ConversationModelOverride? _conversationModelOverride;
  _ChatModelOverrideSelection? _pendingConversationModelOverride;
  String? _conversationReasoningEffort;
  String? _pendingConversationReasoningEffort;
  bool _showConversationModelMentionChip = false;
  List<ChatTerminalEnvironmentVariable> _terminalEnvironmentVariables =
      const [];
  final TextEditingController _openClawBaseUrlController =
      TextEditingController();
  final TextEditingController _openClawTokenController =
      TextEditingController();
  final TextEditingController _openClawUserIdController =
      TextEditingController();
  final GlobalKey _openClawPanelKey = GlobalKey();
  final GlobalKey _inputAreaKey = GlobalKey();
  final Map<ChatPageMode, List<ChatInputAttachment>> _pendingAttachmentsByMode =
      {
        ChatPageMode.normal: <ChatInputAttachment>[],
        ChatPageMode.openclaw: <ChatInputAttachment>[],
        ChatPageMode.codex: <ChatInputAttachment>[],
      };
  final Map<ChatPageMode, String> _draftMessageByMode = {
    ChatPageMode.normal: '',
    ChatPageMode.openclaw: '',
    ChatPageMode.codex: '',
  };
  final KeyboardInsetMotionTracker _emptyGreetingKeyboardLiftTracker =
      KeyboardInsetMotionTracker();
  final ComposerLiftIntentTracker _composerLiftIntentTracker =
      ComposerLiftIntentTracker();
  final ComposerKeyboardMetricsTracker _composerKeyboardMetricsTracker =
      ComposerKeyboardMetricsTracker();
  final Map<ChatPageMode, ChatIslandDisplayLayer>
  _chatIslandDisplayLayerByMode = {
    ChatPageMode.normal: ChatIslandDisplayLayer.mode,
    ChatPageMode.openclaw: ChatIslandDisplayLayer.mode,
    ChatPageMode.codex: ChatIslandDisplayLayer.mode,
  };
  final Map<ChatPageMode, String?> _lastAgentToolTypeByMode = {
    ChatPageMode.normal: null,
    ChatPageMode.openclaw: null,
    ChatPageMode.codex: null,
  };
  final Map<ChatPageMode, String> _runtimeChromeSignatureByMode = {
    ChatPageMode.normal: '',
    ChatPageMode.openclaw: '',
    ChatPageMode.codex: '',
  };
  final Map<ChatPageMode, int> _runtimeMessageMutationRevisionByMode = {
    ChatPageMode.normal: 0,
    ChatPageMode.openclaw: 0,
    ChatPageMode.codex: 0,
  };
  final Map<ChatPageMode, ChatBrowserSessionSnapshot?>
  _browserSessionSnapshotByMode = {
    ChatPageMode.normal: null,
    ChatPageMode.openclaw: null,
    ChatPageMode.codex: null,
  };

  // 输入框/任务执行状态
  final Map<ChatPageMode, bool> _isInputAreaVisibleByMode = {
    ChatPageMode.normal: true,
    ChatPageMode.openclaw: true,
    ChatPageMode.codex: true,
  };
  final Map<ChatPageMode, bool> _isExecutingTaskByMode = {
    ChatPageMode.normal: false,
    ChatPageMode.openclaw: false,
    ChatPageMode.codex: false,
  };

  final Map<ChatPageMode, List<ChatMessageModel>> _messagesByMode = {
    ChatPageMode.normal: <ChatMessageModel>[],
    ChatPageMode.openclaw: <ChatMessageModel>[],
    ChatPageMode.codex: <ChatMessageModel>[],
  };
  final Map<ChatPageMode, String?> _editingUserMessageIdByMode = {
    ChatPageMode.normal: null,
    ChatPageMode.openclaw: null,
    ChatPageMode.codex: null,
  };
  final Map<ChatPageMode, double> _toolActivityOccupiedHeightByMode = {
    ChatPageMode.normal: 0,
    ChatPageMode.openclaw: 0,
    ChatPageMode.codex: 0,
  };
  final Map<ChatPageMode, double> _slashCommandPanelOccupiedHeightByMode = {
    ChatPageMode.normal: 0,
    ChatPageMode.openclaw: 0,
    ChatPageMode.codex: 0,
  };
  final Map<ChatPageMode, bool> _slashCommandExpandedByMode = {
    ChatPageMode.normal: false,
    ChatPageMode.openclaw: false,
    ChatPageMode.codex: false,
  };
  final Map<ChatPageMode, bool> _toolActivityExpandedByMode = {
    ChatPageMode.normal: false,
    ChatPageMode.openclaw: false,
    ChatPageMode.codex: false,
  };
  final Map<ChatPageMode, Set<String>> _expandedAgentRunTaskIdsByMode = {
    ChatPageMode.normal: <String>{},
    ChatPageMode.openclaw: <String>{},
    ChatPageMode.codex: <String>{},
  };
  final Map<ChatPageMode, List<String>> _expandedAgentRunTaskOrderByMode = {
    ChatPageMode.normal: <String>[],
    ChatPageMode.openclaw: <String>[],
    ChatPageMode.codex: <String>[],
  };
  final Map<ChatPageMode, double> _inputAreaHeightByMode = {
    ChatPageMode.normal: 0,
    ChatPageMode.openclaw: 0,
    ChatPageMode.codex: 0,
  };
  final Map<ChatPageMode, bool> _isAiRespondingByMode = {
    ChatPageMode.normal: false,
    ChatPageMode.openclaw: false,
    ChatPageMode.codex: false,
  };
  final Map<ChatPageMode, bool> _isContextCompressingByMode = {
    ChatPageMode.normal: false,
    ChatPageMode.openclaw: false,
    ChatPageMode.codex: false,
  };
  final Map<ChatPageMode, bool> _isCheckingExecutableTaskByMode = {
    ChatPageMode.normal: false,
    ChatPageMode.openclaw: false,
    ChatPageMode.codex: false,
  };
  final Map<ChatPageMode, bool> _isSubmittingVlmReplyByMode = {
    ChatPageMode.normal: false,
    ChatPageMode.openclaw: false,
    ChatPageMode.codex: false,
  };
  final Map<ChatPageMode, String?> _vlmInfoQuestionByMode = {
    ChatPageMode.normal: null,
    ChatPageMode.openclaw: null,
    ChatPageMode.codex: null,
  };
  final Map<ChatPageMode, Map<String, String>> _currentAiMessagesByMode = {
    ChatPageMode.normal: <String, String>{},
    ChatPageMode.openclaw: <String, String>{},
    ChatPageMode.codex: <String, String>{},
  };
  final Map<ChatPageMode, String> _deepThinkingContentByMode = {
    ChatPageMode.normal: '',
    ChatPageMode.openclaw: '',
    ChatPageMode.codex: '',
  };
  final Map<ChatPageMode, bool> _isDeepThinkingByMode = {
    ChatPageMode.normal: false,
    ChatPageMode.openclaw: false,
    ChatPageMode.codex: false,
  };
  final Map<ChatPageMode, String?> _currentDispatchTaskIdByMode = {
    ChatPageMode.normal: null,
    ChatPageMode.openclaw: null,
    ChatPageMode.codex: null,
  };
  final Map<ChatPageMode, int> _currentThinkingStageByMode = {
    ChatPageMode.normal: 1,
    ChatPageMode.openclaw: 1,
    ChatPageMode.codex: 1,
  };
  final Map<ChatPageMode, int?> _currentConversationIdByMode = {
    ChatPageMode.normal: null,
    ChatPageMode.openclaw: null,
    ChatPageMode.codex: null,
  };
  final Map<ChatPageMode, ConversationModel?> _currentConversationByMode = {
    ChatPageMode.normal: null,
    ChatPageMode.openclaw: null,
    ChatPageMode.codex: null,
  };
  final Map<ChatPageMode, bool> _hasMoreMessagesByMode = {
    ChatPageMode.normal: false,
    ChatPageMode.openclaw: false,
    ChatPageMode.codex: false,
  };
  final Map<ChatPageMode, int> _messageOffsetByMode = {
    ChatPageMode.normal: 0,
    ChatPageMode.openclaw: 0,
    ChatPageMode.codex: 0,
  };
  final Map<ChatPageMode, bool> _isLoadingMoreByMode = {
    ChatPageMode.normal: false,
    ChatPageMode.openclaw: false,
    ChatPageMode.codex: false,
  };
  bool _isAwaitingAuthorizeResult = false;
  bool _isRetryingLatestInstructionAfterAuth = false;
  bool _suppressNextOutsideTapKeyboardHide = false;
  static const String _openClawWaitingHint = '等待龙虾烹饪';
  static const String _openClawWaitingStatusKey = 'openclaw_waiting';
  static const String _openClawSessionKeyPrefix = 'openclaw';
  static const String _hdPadLeftPaneWidthStorageKey =
      'chat_hd_pad_left_pane_width';
  static const String _hdPadRightPaneWidthStorageKey =
      'chat_hd_pad_right_pane_width';
  bool _workspaceBrowserCanGoUp = false;
  Future<OmnibotWorkspacePaths>? _workspacePathsLoadFuture;
  bool _hasInitializedHalfScreen = false;
  bool _isCompanionModeEnabled = false;
  bool _isCompanionToggleLoading = false;
  AppUpdateStatus? _appUpdateStatus;
  ModalRoute<dynamic>? _subscribedRoute;
  StreamSubscription<Map<String, dynamic>>?
  _conversationListChangedSubscription;
  StreamSubscription<Map<String, dynamic>>?
  _conversationMessagesChangedSubscription;
  StreamSubscription<Map<String, dynamic>>?
  _browserSessionSnapshotChangedSubscription;
  StreamSubscription<Map<String, dynamic>>? _codexEventSubscription;
  Timer? _remoteCodexSessionSyncTimer;
  bool _remoteCodexSessionSyncInFlight = false;
  String? _remoteCodexSessionSyncThreadId;
  String _remoteCodexSessionSyncSignature = '';
  String? _remoteCodexActivityThreadId;
  String _remoteCodexActivityContentSignature = '';
  int? _remoteCodexLastContentChangeAtMs;
  CodexStatus _codexStatus = CodexStatus.disconnected;
  bool _isCodexStatusLoading = false;
  int? _activeCodexRemoteRuntimeId;
  String? _activeCodexThreadId;
  String? _activeCodexTurnId;
  String? _activeCodexModelId;
  String? _activeCodexReasoningEffort;
  String? _activeCodexCollaborationMode;
  bool _isCodexModelListLoading = false;
  bool _isCodexCollaborationModeListLoading = false;
  String? _codexModelListError;
  String? _codexCollaborationModeListError;
  List<String> _codexModelOptions = const <String>[];
  List<String> _codexReasoningEffortOptions = const <String>[
    'low',
    'medium',
    'high',
    'xhigh',
  ];
  List<String> _codexCollaborationModes = const <String>[];
  CodexPermissionMode _codexPermissionMode = CodexPermissionMode.fullAccess;
  ChatBrowserSessionSnapshot? _liveBrowserSessionSnapshot;
  bool _isBrowserOverlayVisible = false;
  bool _isBrowserOverlayInitialized = false;
  Offset _browserOverlayOffset = Offset.zero;
  Size _browserOverlaySize = const Size(360, 420);
  int _browserOverlayViewSeed = 0;
  String? _lastObservedBrowserSnapshotSignature;
  int? _pageGesturePointerId;
  double _pageHorizontalDragDelta = 0;
  double _pageVerticalDragDelta = 0;
  int _surfaceSwitchRequestId = 0;
  bool _isSurfacePageScrolling = false;
  final HdPadPaneLayoutResolver _hdPadPaneLayoutResolver =
      const HdPadPaneLayoutResolver();
  double? _hdPadLeftPaneWidth;
  double? _hdPadRightPaneWidth;
  bool _hdPadLeftPaneCollapsed = false;
  bool _hdPadRightPaneCollapsed = false;
  bool _isHdPadPaneDragging = false;
  double? _hdPadPaneDragStartWidth;
  double _hdPadPaneDragDelta = 0;
  final GlobalKey<OmnibotWorkspaceBrowserState> _hdPadWorkspaceBrowserKey =
      GlobalKey<OmnibotWorkspaceBrowserState>();
  final GlobalKey<CodexRemoteWorkspaceBrowserState>
  _hdPadRemoteWorkspaceBrowserKey =
      GlobalKey<CodexRemoteWorkspaceBrowserState>();

  ChatPageMode get _activeMode => _activeConversationMode;
  ConversationMode _conversationModeForPageMode(ChatPageMode mode) {
    if (mode == ChatPageMode.codex) {
      return ConversationMode.codex;
    }
    if (mode == ChatPageMode.openclaw) {
      return ConversationMode.openclaw;
    }
    final runtimeConversation =
        _runtimeForMode(mode)?.conversation ?? _currentConversationByMode[mode];
    final persistedMode = runtimeConversation?.mode;
    if (persistedMode == ConversationMode.subagent ||
        persistedMode == ConversationMode.chatOnly) {
      return persistedMode!;
    }
    if (mode == _activeConversationMode) {
      final targetMode = _resolvedThreadTarget?.mode;
      if (targetMode == ConversationMode.subagent ||
          targetMode == ConversationMode.chatOnly) {
        return targetMode!;
      }
    }
    return ConversationMode.normal;
  }

  ChatPageMode _pageModeForConversationMode(ConversationMode mode) =>
      mode == ConversationMode.openclaw
      ? ChatPageMode.openclaw
      : mode == ConversationMode.codex
      ? ChatPageMode.codex
      : ChatPageMode.normal;
  ChatSurfaceMode _surfaceForConversationMode(ConversationMode mode) =>
      mode == ConversationMode.openclaw
      ? ChatSurfaceMode.openclaw
      : ChatSurfaceMode.normal;
  String _modeKey(ChatPageMode mode) => switch (mode) {
    ChatPageMode.normal => kChatRuntimeModeNormal,
    ChatPageMode.openclaw => kChatRuntimeModeOpenClaw,
    ChatPageMode.codex => kChatRuntimeModeCodex,
  };
  ChatConversationRuntimeState? _runtimeForMode(ChatPageMode mode) {
    final conversationId = _currentConversationIdByMode[mode];
    if (conversationId == null) return null;
    return _runtimeCoordinator.runtimeFor(
      conversationId: conversationId,
      mode: _modeKey(mode),
    );
  }

  bool _isRemoteCodexRuntimeActiveForMode(ChatPageMode mode) {
    if (mode != ChatPageMode.codex) {
      return false;
    }
    final conversationId = _currentConversationIdByMode[mode];
    if (conversationId == null) {
      return false;
    }
    return _runtimeCoordinator.isEphemeralRuntime(
      conversationId: conversationId,
      mode: _modeKey(mode),
    );
  }

  ChatConversationRuntimeState? get _activeRuntime =>
      _runtimeForMode(_activeMode);
  int _beginConversationTargetRequest() => ++_conversationTargetRequestId;
  bool _isConversationTargetRequestCurrent(int requestId) =>
      mounted && requestId == _conversationTargetRequestId;
  @override
  int captureConversationLifecycleToken() =>
      _conversationLifecycleGuard.capture();
  @override
  bool isConversationLifecycleTokenCurrent(int token) =>
      _conversationLifecycleGuard.isCurrent(token);
  @override
  void invalidateConversationLifecycle() {
    _conversationLifecycleGuard.invalidate();
  }

  ChatIslandDisplayLayer _chatIslandDisplayLayerForMode(ChatPageMode mode) {
    final stored =
        _runtimeForMode(mode)?.chatIslandDisplayLayer ??
        _chatIslandDisplayLayerByMode[mode];
    if (stored != null) {
      return stored;
    }
    return ChatIslandDisplayLayer.mode;
  }

  bool get _isOpenClawSurface => _activeSurfaceMode == ChatSurfaceMode.openclaw;
  bool get _isWorkspaceSurface =>
      _activeSurfaceMode == ChatSurfaceMode.workspace;

  String _runtimeChromeSignature(ChatConversationRuntimeState? runtime) {
    if (runtime == null) {
      return '';
    }
    return <String>[
      runtime.isAiResponding ? '1' : '0',
      runtime.isContextCompressing ? '1' : '0',
      runtime.isCheckingExecutableTask ? '1' : '0',
      runtime.isSubmittingVlmReply ? '1' : '0',
      runtime.vlmInfoQuestion ?? '',
      runtime.currentDispatchTaskId ?? '',
      runtime.currentThinkingStage.toString(),
      runtime.isInputAreaVisible ? '1' : '0',
      runtime.isExecutingTask ? '1' : '0',
      runtime.chatIslandDisplayLayer.wireName,
      runtime.lastAgentToolType ?? '',
      _browserSnapshotSignature(runtime.browserSessionSnapshot),
    ].join('|');
  }

  void _rememberRuntimeUiSnapshot(ChatPageMode mode) {
    final runtime = _runtimeForMode(mode);
    _runtimeChromeSignatureByMode[mode] = _runtimeChromeSignature(runtime);
    _runtimeMessageMutationRevisionByMode[mode] =
        runtime?.messages.lastMutationRevision ?? 0;
  }

  double get _surfacePageProgress {
    final fallback = _pageIndexForSurface(_activeSurfaceMode).toDouble();
    if (!_modePageController.hasClients) {
      return fallback;
    }
    final page = _modePageController.page;
    if (page == null || !page.isFinite) {
      return fallback;
    }
    return page.clamp(0.0, 1.0).toDouble();
  }

  double get _normalSurfaceVisibility =>
      (1.0 - _surfacePageProgress).clamp(0.0, 1.0).toDouble();
  bool _isHdPadLandscapeForMediaQuery(MediaQueryData mediaQuery) {
    return isHdPadLandscapeViewport(mediaQuery.size);
  }

  void _loadHdPadPanePreferences() {
    _hdPadLeftPaneWidth = StorageService.getDouble(
      _hdPadLeftPaneWidthStorageKey,
    );
    _hdPadRightPaneWidth = StorageService.getDouble(
      _hdPadRightPaneWidthStorageKey,
    );
  }

  void _persistHdPadPanePreferences() {
    final leftWidth = _hdPadLeftPaneWidth;
    final rightWidth = _hdPadRightPaneWidth;
    if (leftWidth != null) {
      unawaited(
        StorageService.setDouble(_hdPadLeftPaneWidthStorageKey, leftWidth),
      );
    }
    if (rightWidth != null) {
      unawaited(
        StorageService.setDouble(_hdPadRightPaneWidthStorageKey, rightWidth),
      );
    }
  }

  void _resetHdPadPaneDragState() {
    _isHdPadPaneDragging = false;
    _hdPadPaneDragStartWidth = null;
    _hdPadPaneDragDelta = 0;
  }

  void _handleEmbeddedDrawerThreadTargetSelected(
    ConversationThreadTarget target,
  ) {
    _dismissChatInputFocus();
    unawaited(_applyConversationThreadTarget(target));
  }

  void _toggleHdPadLeftPaneCollapsed() {
    _dismissChatInputFocus();
    setState(() {
      _resetHdPadPaneDragState();
      _hdPadLeftPaneCollapsed = !_hdPadLeftPaneCollapsed;
    });
  }

  void _toggleHdPadRightPaneCollapsed() {
    _dismissChatInputFocus();
    setState(() {
      _resetHdPadPaneDragState();
      _hdPadRightPaneCollapsed = !_hdPadRightPaneCollapsed;
    });
  }

  void _dismissChatInputFocus() {
    if (_inputFocusNode.hasFocus) {
      _inputFocusNode.unfocus();
    }
    FocusManager.instance.primaryFocus?.unfocus();
  }

  ConversationThreadTarget get _threadTargetForMode {
    final conversationMode = _conversationModeForPageMode(_activeMode);
    final conversationId = _currentConversationIdByMode[_activeMode];
    if (_activeMode == ChatPageMode.codex &&
        _isRemoteCodexRuntimeActiveForMode(ChatPageMode.codex)) {
      final threadId = _activeCodexThreadId?.trim() ?? '';
      if (threadId.isNotEmpty) {
        return ConversationThreadTarget.codexSession(
          threadId: threadId,
          runtime: 'remote',
        );
      }
      return ConversationThreadTarget.newConversation(
        mode: ConversationMode.codex,
        codexRuntime: 'remote',
      );
    }
    if (conversationId == null) {
      final resolvedTarget = _resolvedThreadTarget;
      if (resolvedTarget != null &&
          resolvedTarget.isNewConversation &&
          _pageModeForConversationMode(resolvedTarget.mode) == _activeMode) {
        return resolvedTarget.copyWith(mode: conversationMode);
      }
      return _newThreadTargetForConversationMode(conversationMode);
    }
    final localCodexThreadId = _activeMode == ChatPageMode.codex
        ? _activeCodexThreadId?.trim()
        : null;
    return ConversationThreadTarget.existing(
      conversationId: conversationId,
      mode: conversationMode,
      codexThreadId: localCodexThreadId == null || localCodexThreadId.isEmpty
          ? null
          : localCodexThreadId,
      codexRuntime: localCodexThreadId == null || localCodexThreadId.isEmpty
          ? null
          : 'local',
    );
  }

  ConversationThreadTarget? get _visibleThreadTarget =>
      _isWorkspaceSurface ? null : _threadTargetForMode;

  bool get _isPureChatSelected =>
      _conversationModeForPageMode(ChatPageMode.normal) ==
      ConversationMode.chatOnly;

  bool get _isPureChatToggleLocked => _isLocalModelPureChatLocked;

  bool get _isOmniInferLocalModelSelected {
    final selection = _activeDispatchSceneSelection;
    return localModelFeature.isBuiltinLocalProvider(
      selection?.providerProfileId,
    );
  }

  bool get _isLocalModelPureChatLocked =>
      _activeMode == ChatPageMode.normal &&
      _isPureChatSelected &&
      _isOmniInferLocalModelSelected;

  void _showLocalModelPureChatLockToast() {
    showToast(
      LegacyTextLocalizer.localize('当前已选择本地模型，请开启新对话后再切换到其他模式'),
      type: ToastType.warning,
    );
  }

  Future<void> _handleAgentModeShortcutTap() async {
    if (_isLocalModelPureChatLocked) {
      _showLocalModelPureChatLockToast();
      return;
    }
    if (_activeMode == ChatPageMode.normal && !_isPureChatSelected) {
      return;
    }
    _storeDraftForActiveConversationMode();
    await _persistVisibleThreadTargetIfNeeded();
    final target = _newThreadTargetForConversationMode(ConversationMode.normal);
    if (!mounted) {
      return;
    }
    await _applyConversationThreadTarget(target);
    if (!mounted) {
      return;
    }
  }

  Future<void> _handlePureChatModeShortcutTap() async {
    if (_activeMode == ChatPageMode.codex) {
      final target = _newThreadTargetForConversationMode(
        ConversationMode.chatOnly,
      );
      await _applyConversationThreadTarget(target);
      if (!mounted) {
        return;
      }
      return;
    }
    await _togglePureChatConversationMode();
  }

  Future<void> _togglePureChatConversationMode() async {
    final nextMode = _isPureChatSelected
        ? ConversationMode.normal
        : ConversationMode.chatOnly;
    if (nextMode != ConversationMode.chatOnly && _isLocalModelPureChatLocked) {
      _showLocalModelPureChatLockToast();
      return;
    }
    final nextTarget = _newThreadTargetForConversationMode(nextMode);
    await _applyConversationThreadTarget(nextTarget);
  }

  String get _expectedBrowserWorkspaceId => chatConversationWorkspaceId(
    _currentConversationIdByMode[ChatPageMode.normal],
  );

  List<ChatMessageModel> get _messages =>
      _activeRuntime?.messages ?? _messagesByMode[_activeMode]!;
  double get _toolActivityOccupiedHeight =>
      _toolActivityOccupiedHeightByMode[_activeMode] ?? 0;
  double get _slashCommandPanelOccupiedHeight =>
      _slashCommandPanelOccupiedHeightByMode[_activeMode] ?? 0;
  bool get _isSlashCommandExpanded =>
      _slashCommandExpandedByMode[_activeMode] ?? false;
  bool get _isToolActivityExpanded =>
      _toolActivityExpandedByMode[_activeMode] ?? false;
  Set<String> _expandedAgentRunTaskIdsForMode(ChatPageMode mode) =>
      _expandedAgentRunTaskIdsByMode[mode] ?? const <String>{};
  String? _latestExpandedAgentRunTaskIdForMode(ChatPageMode mode) {
    final orderedTaskIds = _expandedAgentRunTaskOrderByMode[mode];
    if (orderedTaskIds == null || orderedTaskIds.isEmpty) {
      return null;
    }
    return orderedTaskIds.last;
  }

  double get _inputAreaHeight => _inputAreaHeightByMode[_activeMode] ?? 0;
  bool get _isAiResponding =>
      _activeRuntime?.isAiResponding ??
      (_isAiRespondingByMode[_activeMode] ?? false);
  set _isAiResponding(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isAiResponding = value;
      return;
    }
    _isAiRespondingByMode[_activeMode] = value;
  }

  bool get _isContextCompressing =>
      _activeRuntime?.isContextCompressing ??
      (_isContextCompressingByMode[_activeMode] ?? false);
  set _isContextCompressing(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isContextCompressing = value;
      return;
    }
    _isContextCompressingByMode[_activeMode] = value;
  }

  bool get _isCheckingExecutableTask =>
      _activeRuntime?.isCheckingExecutableTask ??
      (_isCheckingExecutableTaskByMode[_activeMode] ?? false);
  set _isCheckingExecutableTask(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isCheckingExecutableTask = value;
      return;
    }
    _isCheckingExecutableTaskByMode[_activeMode] = value;
  }

  bool get _isSubmittingVlmReply =>
      _activeRuntime?.isSubmittingVlmReply ??
      (_isSubmittingVlmReplyByMode[_activeMode] ?? false);
  set _isSubmittingVlmReply(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isSubmittingVlmReply = value;
      return;
    }
    _isSubmittingVlmReplyByMode[_activeMode] = value;
  }

  String? get _vlmInfoQuestion =>
      _activeRuntime?.vlmInfoQuestion ?? _vlmInfoQuestionByMode[_activeMode];
  set _vlmInfoQuestion(String? value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.vlmInfoQuestion = value;
      return;
    }
    _vlmInfoQuestionByMode[_activeMode] = value;
  }

  Map<String, String> get _currentAiMessages =>
      _activeRuntime?.currentAiMessages ??
      _currentAiMessagesByMode[_activeMode]!;
  String get _deepThinkingContent =>
      _activeRuntime?.deepThinkingContent ??
      (_deepThinkingContentByMode[_activeMode] ?? '');
  set _deepThinkingContent(String value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.deepThinkingContent = value;
      return;
    }
    _deepThinkingContentByMode[_activeMode] = value;
  }

  bool get _isDeepThinking =>
      _activeRuntime?.isDeepThinking ??
      (_isDeepThinkingByMode[_activeMode] ?? false);
  set _isDeepThinking(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isDeepThinking = value;
      return;
    }
    _isDeepThinkingByMode[_activeMode] = value;
  }

  String? get _currentDispatchTaskId =>
      _activeRuntime?.currentDispatchTaskId ??
      _currentDispatchTaskIdByMode[_activeMode];
  set _currentDispatchTaskId(String? value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.currentDispatchTaskId = value;
      return;
    }
    _currentDispatchTaskIdByMode[_activeMode] = value;
  }

  int get _currentThinkingStage =>
      _activeRuntime?.currentThinkingStage ??
      (_currentThinkingStageByMode[_activeMode] ?? 1);
  set _currentThinkingStage(int value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.currentThinkingStage = value;
      return;
    }
    _currentThinkingStageByMode[_activeMode] = value;
  }

  bool get _isInputAreaVisible =>
      _activeRuntime?.isInputAreaVisible ??
      (_isInputAreaVisibleByMode[_activeMode] ?? true);
  set _isInputAreaVisible(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isInputAreaVisible = value;
      return;
    }
    _isInputAreaVisibleByMode[_activeMode] = value;
  }

  bool get _isExecutingTask =>
      _activeRuntime?.isExecutingTask ??
      (_isExecutingTaskByMode[_activeMode] ?? false);
  set _isExecutingTask(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isExecutingTask = value;
      return;
    }
    _isExecutingTaskByMode[_activeMode] = value;
  }

  int? get _currentConversationId => _currentConversationIdByMode[_activeMode];
  set _currentConversationId(int? value) =>
      _currentConversationIdByMode[_activeMode] = value;
  ConversationModel? get _currentConversation =>
      _activeRuntime?.conversation ?? _currentConversationByMode[_activeMode];
  set _currentConversation(ConversationModel? value) {
    _currentConversationByMode[_activeMode] = value;
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.conversation = value;
    }
  }

  String? get _activeConversationReasoningEffort =>
      _activeMode != ChatPageMode.normal
      ? null
      : _pendingConversationReasoningEffort ?? _conversationReasoningEffort;

  ChatIslandDisplayLayer get _chatIslandDisplayLayer =>
      _chatIslandDisplayLayerForMode(_activeMode);
  set _chatIslandDisplayLayer(ChatIslandDisplayLayer value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.chatIslandDisplayLayer = value;
      return;
    }
    _chatIslandDisplayLayerByMode[_activeMode] = value;
  }

  void _handleSurfaceScrollStart() {
    if (!mounted) {
      _isSurfacePageScrolling = true;
      return;
    }
    if (_isSurfacePageScrolling) {
      return;
    }
    setState(() {
      _isSurfacePageScrolling = true;
    });
  }

  void _handleSurfaceScrollSettled() {
    if (!mounted) {
      _isSurfacePageScrolling = false;
      return;
    }
    if (_isSurfacePageScrolling) {
      setState(() {
        _isSurfacePageScrolling = false;
      });
    } else {
      _isSurfacePageScrolling = false;
    }
  }

  bool _handleModePageScrollNotification(ScrollNotification notification) {
    if (notification.depth != 0 ||
        notification.metrics.axis != Axis.horizontal) {
      return false;
    }
    if (notification is ScrollStartNotification) {
      _handleSurfaceScrollStart();
      return false;
    }
    if (notification is UserScrollNotification) {
      final direction = notification.direction;
      if (direction == ScrollDirection.forward ||
          direction == ScrollDirection.reverse) {
        _handleSurfaceScrollStart();
      }
      return false;
    }
    if (notification is ScrollEndNotification) {
      _handleSurfaceScrollSettled();
    }
    return false;
  }

  String? get _lastAgentToolType =>
      _activeRuntime?.lastAgentToolType ??
      _lastAgentToolTypeByMode[_activeMode];
  set _lastAgentToolType(String? value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.lastAgentToolType = value;
      return;
    }
    _lastAgentToolTypeByMode[_activeMode] = value;
  }

  ChatBrowserSessionSnapshot? get _browserSessionSnapshot =>
      _activeRuntime?.browserSessionSnapshot ??
      _browserSessionSnapshotByMode[_activeMode];
  set _browserSessionSnapshot(ChatBrowserSessionSnapshot? value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.browserSessionSnapshot = value;
      return;
    }
    _browserSessionSnapshotByMode[_activeMode] = value;
  }

  bool get _supportsReasoningEffortCommand =>
      _activeMode == ChatPageMode.normal && !_isOpenClawSurface;

  _SlashCommandPanelRoute _resolveSlashCommandPanelRoute(String text) {
    final trimmed = text.trimLeft();
    if (!trimmed.startsWith('/')) {
      return _SlashCommandPanelRoute.root;
    }
    final normalized = trimmed.toLowerCase();
    if (_activeMode == ChatPageMode.codex &&
        (normalized == '/model' || normalized.startsWith('/model '))) {
      return _SlashCommandPanelRoute.codexModel;
    }
    if (normalized == '/effort' || normalized.startsWith('/effort ')) {
      return _SlashCommandPanelRoute.effort;
    }
    return _SlashCommandPanelRoute.root;
  }

  String _slashCommandRouteQuery(
    _SlashCommandPanelRoute route, {
    String? text,
  }) {
    final source = (text ?? _messageController.text).trimLeft();
    return switch (route) {
      _SlashCommandPanelRoute.codexModel =>
        source.length <= 6 ? '' : source.substring(6).trimLeft(),
      _SlashCommandPanelRoute.effort =>
        source.length <= 7 ? '' : source.substring(7).trimLeft(),
      _SlashCommandPanelRoute.root => '',
    };
  }

  String? _normalizeReasoningEffort(String? raw) {
    return ConversationReasoningEffortService.normalizeEffort(raw);
  }

  void _setSlashCommandExpanded(bool expanded) {
    if (_isSlashCommandExpanded == expanded) {
      return;
    }
    if (!mounted) {
      _slashCommandExpandedByMode[_activeMode] = expanded;
      return;
    }
    setState(() {
      _slashCommandExpandedByMode[_activeMode] = expanded;
    });
  }

  ChatBrowserSessionSnapshot? get _resolvedBrowserSessionSnapshot {
    final live = _liveBrowserSessionSnapshot;
    if (live != null && live.matchesWorkspace(_expectedBrowserWorkspaceId)) {
      return live;
    }
    final runtime = _browserSessionSnapshot;
    if (runtime != null &&
        runtime.matchesWorkspace(_expectedBrowserWorkspaceId)) {
      return runtime;
    }
    return null;
  }

  bool get _isBrowserSessionAvailable =>
      _resolvedBrowserSessionSnapshot?.available == true;

  List<ChatInputAttachment> get _pendingAttachments =>
      _pendingAttachmentsByMode[_activeMode]!;
  String? get _editingUserMessageId => _editingUserMessageIdByMode[_activeMode];
  set _editingUserMessageId(String? value) =>
      _editingUserMessageIdByMode[_activeMode] = value;
  void _updateExpandedAgentRunTaskIds(ChatPageMode mode, Set<String> taskIds) {
    final normalizedTaskIds = taskIds
        .map((item) => item.trim())
        .where((item) => item.isNotEmpty)
        .toSet();
    final currentTaskIds = _expandedAgentRunTaskIdsForMode(mode);
    final currentOrder =
        _expandedAgentRunTaskOrderByMode[mode] ?? const <String>[];
    final hasChanged =
        currentTaskIds.length != normalizedTaskIds.length ||
        !currentTaskIds.containsAll(normalizedTaskIds);
    if (!hasChanged || !mounted) {
      return;
    }
    final nextOrderedTaskIds = currentOrder
        .where(normalizedTaskIds.contains)
        .toList(growable: true);
    for (final taskId in normalizedTaskIds) {
      if (!currentTaskIds.contains(taskId)) {
        nextOrderedTaskIds.remove(taskId);
        nextOrderedTaskIds.add(taskId);
      }
    }
    setState(() {
      _expandedAgentRunTaskIdsByMode[mode] = normalizedTaskIds;
      _expandedAgentRunTaskOrderByMode[mode] = nextOrderedTaskIds;
    });
  }

  TextEditingController _userMessageEditControllerForMode(ChatPageMode mode) =>
      mode == ChatPageMode.openclaw
      ? _openClawUserMessageEditController
      : mode == ChatPageMode.codex
      ? _codexUserMessageEditController
      : _normalUserMessageEditController;
  TextEditingController get _editingUserMessageController =>
      _userMessageEditControllerForMode(_activeMode);
  _ChatModelOverrideSelection? get _activeConversationModelOverrideSelection {
    final pending = _pendingConversationModelOverride;
    if (pending != null) {
      return pending;
    }
    final persisted = _conversationModelOverride;
    if (persisted == null) {
      return null;
    }
    return _ChatModelOverrideSelection(
      providerProfileId: persisted.providerProfileId,
      modelId: persisted.modelId,
    );
  }

  SceneCatalogItem? get _dispatchSceneCatalogItem {
    for (final item in _sceneCatalog) {
      if (item.sceneId == 'scene.dispatch.model') {
        return item;
      }
    }
    return null;
  }

  String? get _activeNormalChatModelId {
    final dispatchScene = _dispatchSceneCatalogItem;
    final effectiveModel = dispatchScene?.effectiveModel.trim() ?? '';
    if (effectiveModel.isNotEmpty) {
      return effectiveModel;
    }
    final defaultModel = dispatchScene?.defaultModel.trim() ?? '';
    if (defaultModel.isNotEmpty) {
      return defaultModel;
    }
    return null;
  }

  bool get _hasSelectableNormalChatModels {
    return _modelProviderProfiles.any((profile) {
      if (!profile.configured) {
        return false;
      }
      final models =
          _modelOptionsByProfileId[profile.id] ?? const <ProviderModelOption>[];
      return models.isNotEmpty;
    });
  }

  _ChatModelOverrideSelection? get _activeDispatchSceneSelection {
    final dispatchScene = _dispatchSceneCatalogItem;
    if (dispatchScene == null) {
      return null;
    }
    final providerProfileId = dispatchScene.effectiveProviderProfileId.trim();
    final modelId = dispatchScene.effectiveModel.trim();
    if (providerProfileId.isEmpty || modelId.isEmpty) {
      return null;
    }
    return _ChatModelOverrideSelection(
      providerProfileId: providerProfileId,
      modelId: modelId,
    );
  }

  // ===================== Mixin 接口实现 =====================

  // ChatMessageHandler
  @override
  List<ChatMessageModel> get messages => _messages;
  @override
  bool get isAiResponding => _isAiResponding;
  @override
  set isAiResponding(bool value) => _isAiResponding = value;
  @override
  Map<String, String> get currentAiMessages => _currentAiMessages;
  @override
  TextEditingController get vlmAnswerController => _vlmAnswerController;
  @override
  String? get vlmInfoQuestion => _vlmInfoQuestion;
  @override
  set vlmInfoQuestion(String? value) => _vlmInfoQuestion = value;
  @override
  bool get isSubmittingVlmReply => _isSubmittingVlmReply;
  @override
  set isSubmittingVlmReply(bool value) => _isSubmittingVlmReply = value;

  // DispatchStreamHandler
  @override
  String get deepThinkingContent => _deepThinkingContent;
  @override
  set deepThinkingContent(String value) => _deepThinkingContent = value;
  @override
  bool get isDeepThinking => _isDeepThinking;
  @override
  set isDeepThinking(bool value) => _isDeepThinking = value;
  @override
  String? get currentDispatchTaskId => _currentDispatchTaskId;
  @override
  set currentDispatchTaskId(String? value) => _currentDispatchTaskId = value;
  @override
  int get currentThinkingStage => _currentThinkingStage;
  @override
  set currentThinkingStage(int value) => _currentThinkingStage = value;

  // TaskExecutionHandler
  @override
  TextEditingController get messageController => _messageController;
  @override
  FocusNode get inputFocusNode => _inputFocusNode;
  @override
  bool get isInputAreaVisible => _isInputAreaVisible;
  @override
  set isInputAreaVisible(bool value) => _isInputAreaVisible = value;
  @override
  bool get isExecutingTask => _isExecutingTask;
  @override
  set isExecutingTask(bool value) => _isExecutingTask = value;
  @override
  bool get isCheckingExecutableTask => _isCheckingExecutableTask;
  @override
  set isCheckingExecutableTask(bool value) => _isCheckingExecutableTask = value;

  @override
  Future<void> handleExecutableTaskExecute(
    String aiMessageId,
    Map<String, dynamic> data,
  ) async {
    try {
      await _ensureActiveConversationReadyForStreaming();
    } catch (_) {
      if (mounted) {
        handleAgentError('Conversation setup failed. Please retry.');
      }
      return;
    }
    _syncRuntimeSnapshotForMode(_activeMode);
    _registerActiveTaskBinding(aiMessageId);
    await super.handleExecutableTaskExecute(aiMessageId, data);
  }

  // ConversationManager
  @override
  int? get currentConversationId => _currentConversationId;
  @override
  set currentConversationId(int? value) => _currentConversationId = value;
  @override
  ConversationModel? get currentConversation => _currentConversation;
  @override
  set currentConversation(ConversationModel? value) =>
      _currentConversation = value;
  @override
  ConversationThreadTarget? get routeThreadTarget => _resolvedThreadTarget;
  @override
  ConversationMode get activeConversationModeValue =>
      _conversationModeForPageMode(_activeMode);
  @override
  bool get hasMoreMessages => _hasMoreMessagesByMode[_activeMode] ?? false;
  @override
  set hasMoreMessages(bool value) =>
      _hasMoreMessagesByMode[_activeMode] = value;
  @override
  bool get isLoadingMore => _isLoadingMoreByMode[_activeMode] ?? false;
  @override
  set isLoadingMore(bool value) => _isLoadingMoreByMode[_activeMode] = value;
  @override
  int get messageOffset => _messageOffsetByMode[_activeMode] ?? 0;
  @override
  set messageOffset(int value) => _messageOffsetByMode[_activeMode] = value;
  @override
  List<ChatMessageModel>? getInMemoryMessagesForConversation(
    int conversationId,
    ConversationMode mode,
  ) {
    final pageMode = _pageModeForConversationMode(mode);
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: conversationId,
      mode: _modeKey(pageMode),
    );
    if (runtime == null || runtime.messages.isEmpty) {
      return null;
    }
    return List<ChatMessageModel>.from(runtime.messages);
  }

  @override
  ConversationModel? getInMemoryConversationForConversation(
    int conversationId,
    ConversationMode mode,
  ) {
    final pageMode = _pageModeForConversationMode(mode);
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: conversationId,
      mode: _modeKey(pageMode),
    );
    return runtime?.conversation;
  }

  @override
  bool isEphemeralConversation(int conversationId, ConversationMode mode) {
    final pageMode = _pageModeForConversationMode(mode);
    return _runtimeCoordinator.isEphemeralRuntime(
      conversationId: conversationId,
      mode: _modeKey(pageMode),
    );
  }

  @override
  Future<void> persistAgentConversation() => saveConversation();

  @override
  void onConversationReset(ConversationMode mode) {
    _resetLocalConversationState(_pageModeForConversationMode(mode));
  }

  @override
  void onConversationMissing(ConversationMode mode, int conversationId) {
    final pageMode = _pageModeForConversationMode(mode);
    _runtimeCoordinator.discardConversationRuntime(
      conversationId: conversationId,
      mode: _modeKey(pageMode),
    );
  }

  @override
  void onConversationLoaded(
    ConversationMode mode,
    int conversationId,
    ConversationModel? conversation,
    List<ChatMessageModel> messages,
  ) {
    final pageMode = _pageModeForConversationMode(mode);
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: conversationId,
      mode: _modeKey(pageMode),
    );
    if (runtime == null) {
      _runtimeCoordinator.ensureRuntime(
        conversationId: conversationId,
        mode: _modeKey(pageMode),
        initialMessages: messages,
        conversation: conversation,
        initialChatIslandDisplayLayer: _chatIslandDisplayLayerForMode(pageMode),
      );
    } else if (conversation != null) {
      runtime.conversation = conversation;
    }
    _syncRuntimeSnapshotForMode(
      pageMode,
      conversation: conversation,
      messages: messages,
    );
    if (pageMode == ChatPageMode.normal) {
      unawaited(
        _loadConversationModelOverrideForNormalConversation(conversationId),
      );
      unawaited(_loadNormalChatModelContext());
      unawaited(_refreshLiveBrowserSessionSnapshot(syncRuntime: true));
    }
    if (mounted) {
      setState(() {});
    }
  }

  @override
  void onConversationPersisted(
    ConversationMode mode,
    int conversationId,
    ConversationModel conversation,
    List<ChatMessageModel> messages,
  ) {
    final pageMode = _pageModeForConversationMode(mode);
    _currentConversationIdByMode[pageMode] = conversationId;
    _currentConversationByMode[pageMode] = conversation;
    _syncRuntimeSnapshotForMode(
      pageMode,
      conversation: conversation,
      messages: messages,
    );
    if (pageMode == ChatPageMode.normal) {
      unawaited(
        _persistPendingConversationModelOverrideIfNeeded(conversationId),
      );
      unawaited(_refreshLiveBrowserSessionSnapshot(syncRuntime: true));
    }
    if (!_isWorkspaceSurface && pageMode == _activeConversationMode) {
      unawaited(_persistVisibleThreadTargetIfNeeded());
      unawaited(_syncVisibleChatConversation());
    }
    // Reload the embedded drawer's conversation list so newly persisted
    // conversations appear immediately, matching phone-mode behaviour where
    // the drawer reloads every time it is opened.
    _drawerKey.currentState?.reloadConversations();
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
  void clearAgentStreamSessionState() {
    super.clearAgentStreamSessionState();
    final conversationId = _currentConversationId;
    if (conversationId == null) return;
    _runtimeCoordinator.clearConversationRuntimeSession(
      conversationId: conversationId,
      mode: _modeKey(_activeMode),
    );
  }

  @override
  void interruptActiveToolCard({String? summary}) {
    final conversationId = _currentConversationId;
    if (conversationId == null) return;
    _runtimeCoordinator.interruptActiveToolCard(
      conversationId: conversationId,
      mode: _modeKey(_activeMode),
      summary: summary,
    );
  }

  Future<bool> _handleToolActivityStopRequested(
    String taskId,
    String cardId,
  ) async {
    return AssistsMessageService.stopAgentToolCall(
      taskId: taskId,
      cardId: cardId,
    );
  }

  String _buildOpenClawSessionKey(int conversationId) {
    final normalizedUserId = _openClawUserId.trim();
    if (normalizedUserId.isNotEmpty) {
      return '$_openClawSessionKeyPrefix:$normalizedUserId:conversation:$conversationId';
    }
    return '$_openClawSessionKeyPrefix:conversation:$conversationId';
  }

  String _openClawWaitingCardId(String taskId) => '$taskId-openclaw-waiting';

  bool _isOpenClawWaitingCardMessage(ChatMessageModel message) {
    final cardData = message.cardData;
    return message.type == 2 &&
        cardData?['type'] == 'stage_hint' &&
        cardData?['statusKey'] == _openClawWaitingStatusKey;
  }

  void _showOpenClawWaitingCard(String taskId) {
    final waitingCardId = _openClawWaitingCardId(taskId);
    final cardData = {
      'type': 'stage_hint',
      'hint': LegacyTextLocalizer.localize(_openClawWaitingHint),
      'statusKey': _openClawWaitingStatusKey,
      'taskID': taskId,
      'startTime': DateTime.now().millisecondsSinceEpoch,
    };

    setState(() {
      _messages.removeWhere((msg) => msg.id == waitingCardId);
      _messages.insert(
        0,
        ChatMessageModel(
          id: waitingCardId,
          type: 2,
          user: 3,
          content: {'cardData': cardData, 'id': waitingCardId},
        ),
      );
    });
  }

  void _removeOpenClawWaitingCard(String taskId) {
    final waitingCardId = _openClawWaitingCardId(taskId);
    final hasWaitingCard = _messages.any((msg) => msg.id == waitingCardId);
    if (!hasWaitingCard) return;

    setState(() {
      _messages.removeWhere((msg) => msg.id == waitingCardId);
    });
  }

  void _handleRuntimeCoordinatorChanged() {
    if (!mounted || _activeRuntime == null) return;
    _scheduleBrowserSessionRefreshIfNeeded();
    final mode = _activeMode;
    final runtime = _activeRuntime!;
    final nextChromeSignature = _runtimeChromeSignature(runtime);
    final previousChromeSignature = _runtimeChromeSignatureByMode[mode] ?? '';
    final nextMutationRevision = runtime.messages.lastMutationRevision;
    final previousMutationRevision =
        _runtimeMessageMutationRevisionByMode[mode] ?? 0;
    final hasChromeChange = nextChromeSignature != previousChromeSignature;
    final hasMessageMutation = nextMutationRevision != previousMutationRevision;

    _runtimeChromeSignatureByMode[mode] = nextChromeSignature;
    _runtimeMessageMutationRevisionByMode[mode] = nextMutationRevision;

    if (hasChromeChange ||
        (hasMessageMutation &&
            runtime.messages.lastMutationAffectsPageChrome)) {
      setState(() {});
    }
  }

  void _resetLocalConversationState(ChatPageMode mode) {
    _messagesByMode[mode]!.clear();
    _inputAreaHeightByMode[mode] = 0;
    _isAiRespondingByMode[mode] = false;
    _isContextCompressingByMode[mode] = false;
    _isCheckingExecutableTaskByMode[mode] = false;
    _isSubmittingVlmReplyByMode[mode] = false;
    _vlmInfoQuestionByMode[mode] = null;
    _currentAiMessagesByMode[mode]!.clear();
    _deepThinkingContentByMode[mode] = '';
    _isDeepThinkingByMode[mode] = false;
    _currentDispatchTaskIdByMode[mode] = null;
    _currentThinkingStageByMode[mode] = 1;
    _currentConversationIdByMode[mode] = null;
    _currentConversationByMode[mode] = null;
    _isInputAreaVisibleByMode[mode] = true;
    _isExecutingTaskByMode[mode] = false;
    _chatIslandDisplayLayerByMode[mode] = ChatIslandDisplayLayer.mode;
    _lastAgentToolTypeByMode[mode] = null;
    _runtimeChromeSignatureByMode[mode] = '';
    _runtimeMessageMutationRevisionByMode[mode] = 0;
    _browserSessionSnapshotByMode[mode] = null;
    _pendingAttachmentsByMode[mode]!.clear();
    _editingUserMessageIdByMode[mode] = null;
    _userMessageEditControllerForMode(mode).clear();
    _draftMessageByMode[mode] = '';
    if (mode == ChatPageMode.codex) {
      _stopRemoteCodexSessionSync();
      _activeCodexRemoteRuntimeId = null;
      _activeCodexThreadId = null;
      _activeCodexTurnId = null;
    }
    if (mode == ChatPageMode.normal) {
      _conversationModelOverride = null;
      _pendingConversationModelOverride = null;
      _showConversationModelMentionChip = false;
      _showModelMentionPanel = false;
      _activeModelMentionToken = null;
      _liveBrowserSessionSnapshot = null;
      _isBrowserOverlayVisible = false;
      _isBrowserOverlayInitialized = false;
      _lastObservedBrowserSnapshotSignature = null;
    }
  }

  Map<String, List<ProviderModelOption>> _mergeChatModelOptions({
    required List<ModelProviderProfileSummary> profiles,
    required Map<String, List<ProviderModelOption>> source,
    required List<SceneCatalogItem> sceneCatalog,
    _ChatModelOverrideSelection? overrideSelection,
  }) {
    final result = <String, List<ProviderModelOption>>{
      for (final entry in source.entries)
        entry.key: List<ProviderModelOption>.from(entry.value),
    };
    final knownProfileIds = profiles.map((item) => item.id).toSet();

    void ensureOption(String profileId, String modelId, String ownedBy) {
      final normalizedProfileId = profileId.trim();
      final normalizedModelId = modelId.trim();
      if (normalizedProfileId.isEmpty || normalizedModelId.isEmpty) {
        return;
      }
      if (!knownProfileIds.contains(normalizedProfileId)) {
        return;
      }
      final bucket = result.putIfAbsent(
        normalizedProfileId,
        () => <ProviderModelOption>[],
      );
      final exists = bucket.any((item) => item.id == normalizedModelId);
      if (!exists) {
        bucket.insert(
          0,
          ProviderModelOption(
            id: normalizedModelId,
            displayName: normalizedModelId,
            ownedBy: ownedBy,
          ),
        );
      }
    }

    if (overrideSelection != null) {
      ensureOption(
        overrideSelection.providerProfileId,
        overrideSelection.modelId,
        'override',
      );
    }

    final dispatchScene = sceneCatalog.where(
      (item) => item.sceneId == 'scene.dispatch.model',
    );
    if (dispatchScene.isNotEmpty) {
      final scene = dispatchScene.first;
      ensureOption(
        scene.effectiveProviderProfileId,
        scene.effectiveModel,
        'scene',
      );
      ensureOption(scene.boundProviderProfileId, scene.overrideModel, 'scene');
    }

    return result;
  }

  // ===================== Part 方法声明 =====================

  bool _threadTargetChanged(
    ConversationThreadTarget? oldTarget,
    ConversationThreadTarget? newTarget,
  );

  Future<ConversationThreadTarget> _resolveConversationThreadTarget(
    ConversationThreadTarget? incomingTarget, {
    ConversationMode? preferredMode,
  });

  Future<void> _bootstrapConversationThread();

  Future<void> _reloadConversationForCurrentTarget();

  Future<void> _applyConversationThreadTarget(
    ConversationThreadTarget target, {
    bool syncPage = true,
    int? requestId,
  });

  Future<void> _ensureConversationModeReady(ChatPageMode mode);

  Future<void> _prepareConversationModeState(
    ChatPageMode mode,
    ConversationThreadTarget target,
  );

  Future<void> _persistVisibleThreadTargetIfNeeded();

  Future<void> _syncVisibleChatConversation();

  Future<void> _clearVisibleChatConversation();

  void _notifySummarySheetReadyIfNeeded();

  Future<void> _initializeHalfScreenEngineIfNeeded();

  Future<void> _checkCompanionTaskState();

  Future<void> _toggleCompanionMode();

  Future<void> _startCompanionMode();

  Future<void> _executeCompanionStart();

  Future<void> _cancelCompanionMode();

  void _armComposerLiftIntent();

  void _requestComposerFocus({bool showKeyboard});

  void _onFocusChange();

  void _handleAppUpdateStatusChanged();

  double _popupMenuBottomOffset();

  Future<void> _handleAppUpdateBannerTap();

  Future<void> _refreshCodexStatus();

  Future<void> _refreshCodexCommandPreferences();

  Future<void> _loadCodexModelOptions({bool force = false});

  Future<void> _loadCodexCollaborationModes({bool force = false});

  Future<void> _selectCodexModel(String modelId, {bool clearComposer = true});

  Future<void> _selectCodexReasoningEffort(String effort);

  Future<void> _activateCodexPlanMode({bool persistOnly = false});

  Future<void> _handleCodexSlashCommandCardSelected(
    Map<String, dynamic> cardData,
  );

  Future<bool> _tryHandleCodexSlashCommand(String messageText);

  Future<void> _executeCodexInitCommand();

  Future<void> _startCodexReviewCommand();

  Future<void> _handleCodexTap();

  String? _codexRemoteWorkspaceNameForGreeting();

  Future<void> _openCodexRemoteWorkspacePicker();

  Future<void> _prepareRemoteCodexSessionTarget(
    ConversationThreadTarget target,
  );

  void _handleCodexAppServerEvent(Map<String, dynamic> event);

  void _stopRemoteCodexSessionSync();

  Future<void> _sendCodexMessage(
    String aiMessageId,
    String messageText, {
    String? modelOverride,
    String? collaborationModeOverride,
  });

  Future<void> _interruptCodexTurn();

  Future<void> _loadOpenClawConfig();

  Future<void> _ensureOpenClawUserId();

  int _pageIndexForSurface(ChatSurfaceMode mode);

  ChatSurfaceMode _surfaceForPageIndex(int pageIndex);

  ScrollController _scrollControllerForMode(ChatPageMode mode);

  void _jumpToCurrentModePage({bool animate = true});

  Future<void> _switchChatMode(
    ChatSurfaceMode targetMode, {
    bool syncPage = true,
  });

  void _handleModePageChanged(int pageIndex);

  void _storeDraftForActiveConversationMode();

  void _applyDraftForConversationMode(ChatPageMode mode);

  Future<void> _loadNormalChatModelContext();

  Future<void> _syncInvalidNormalConversationOverrideIfNeeded();

  Future<void> _loadConversationModelOverrideForNormalConversation(
    int? conversationId,
  );

  Future<void> _persistPendingConversationModelOverrideIfNeeded(
    int conversationId,
  );

  void _removeActiveModelMentionTokenFromInput();

  Future<void> _applyConversationModelOverride({
    required String providerProfileId,
    required String modelId,
    bool displayAsMentionChip = false,
  });

  Future<void> _applyConversationReasoningEffort(String reasoningEffort);

  Future<void> _clearConversationModelOverride();

  Map<String, dynamic>? _buildAgentModelOverridePayload();

  Map<String, dynamic>? _buildChatModelOverridePayload();

  _ActiveModelMentionToken? _parseActiveModelMentionToken(
    TextEditingValue value,
  );

  Future<void> _openConversationModelSelector(BuildContext anchorContext);

  Future<void> _applyDispatchSceneModelSelection({
    required String providerProfileId,
    required String modelId,
  });

  Widget _buildModelMentionPanel();

  Future<void> _loadTerminalEnvironmentVariables();

  Future<void> _updateTerminalEnvironmentVariables(
    List<ChatTerminalEnvironmentVariable> variables,
  );

  Future<void> _openTerminalEnvironmentEditor(BuildContext anchorContext);

  Map<String, String>? _buildAgentTerminalEnvironmentPayload();

  String _browserSnapshotSignature(ChatBrowserSessionSnapshot? snapshot);

  void _scheduleBrowserSessionRefreshIfNeeded();

  void _handlePagePointerDown(PointerDownEvent event);

  void _handlePagePointerMove(PointerMoveEvent event);

  void _handlePagePointerUp(PointerUpEvent event);

  void _handlePagePointerCancel(PointerCancelEvent event);

  Future<void> _refreshLiveBrowserSessionSnapshot({bool syncRuntime = false});

  void _handleBrowserSessionSnapshotChanged(Map<String, dynamic> raw);

  void _setChatIslandDisplayLayerForMode(
    ChatPageMode mode,
    ChatIslandDisplayLayer layer,
  );

  void _handleChatIslandDisplayLayerChanged(ChatIslandDisplayLayer layer);

  Future<void> _handleTerminalToolTap();

  Future<void> _handleBrowserToolTap();

  void _hideBrowserOverlay();

  void _ensureBrowserOverlayGeometry(BoxConstraints constraints);

  void _moveBrowserOverlay(Offset delta, BoxConstraints constraints);

  void _resizeBrowserOverlayFromLeft(Offset delta, BoxConstraints constraints);

  void _resizeBrowserOverlayFromRight(Offset delta, BoxConstraints constraints);

  Rect _browserOverlayBounds(BoxConstraints constraints);

  Widget _buildBrowserOverlay(BoxConstraints constraints);

  void _handleSlashCommandInput();

  bool get _supportsManualContextCompaction;

  void _triggerSlashCommandPanel();

  void _showOpenClawCommandPanel({bool expand = false});

  void _hideSlashCommandPanel();

  bool _isPointerInside(GlobalKey key, Offset position);

  Future<void> _handleOutsideTap(Offset position);

  Future<void> _applyOpenClawConfig({
    required String baseUrl,
    required String token,
    String? userId,
    bool enable = true,
  });

  Future<bool> _tryHandleSlashCommand(String messageText);

  Future<void> _executeManualContextCompactionCommand();

  Future<void> _checkOpenClawConnection();

  void _syncRuntimeSnapshotForMode(
    ChatPageMode mode, {
    ConversationModel? conversation,
    List<ChatMessageModel>? messages,
  });

  Future<void> _ensureActiveConversationReadyForStreaming();

  void _registerActiveTaskBinding(String taskId);

  void _createThinkingCard(
    String taskID, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
  });

  void _updateThinkingCard(
    String taskID, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
    bool lockCompleted = true,
  });

  Future<void> _pickAttachments();

  void _removePendingAttachment(String id);

  String _fileNameFromPath(String path);

  bool _isImageFilePath(String path, {String? mimeType});

  String? _mimeTypeFromExtension(String path, {String extension = ''});

  void _showSnackBar(String message);

  Future<bool> _ensureNormalChatModelConfigurationForSend();

  Future<void> _sendMessage({String? text});

  Future<void> _retryUserMessageText(
    String text, {
    List<Map<String, dynamic>> attachments,
  });

  Future<void> _sendChatMessage(String aiMessageId);

  Future<void> _sendPureChatMessage(String aiMessageId);

  Future<bool> _handleExecutableTaskFlow(
    String aiMessageId,
    String userMessageId,
  );

  Future<bool> _tryAgentFlow(String aiMessageId, String userMessageId);

  Future<List<Map<String, dynamic>>> _latestUserAttachments();

  bool _isImageAttachmentMap(Map<String, dynamic> item);

  Future<String> _resolveImageDataUrl(Map<String, dynamic> item);

  void _onCancelTask();

  void _cancelDispatchTask();

  void _onCancelTaskFromCard(String taskId);

  void _updateThinkingCardToCancelled(String taskId);

  void _collapseAgentRunTrace(String taskId);

  void _onPopupVisibilityChanged(bool visible);

  Future<void> _requestAuthorizeForExecution(
    List<String> requiredPermissionIds,
  );

  Future<void> _retryLatestInstructionAfterAuth();

  void _removeFailedAttemptMessages();

  Widget _buildSlashCommandPanel();

  Widget _buildModeMessagePage(
    ChatPageMode mode,
    AppBackgroundConfig appearanceConfig,
    AppBackgroundVisualProfile visualProfile, {
    double bottomOverlayInset = 0,
  });

  Widget _buildWorkspaceSurfacePage();
}

class _ChatPageState extends _ChatPageStateBase
    with
        _ChatPageBrowserMixin,
        _ChatPageCodexMixin,
        _ChatPageLifecycleMixin,
        _ChatPageModelContextMixin,
        _ChatPageOpenClawMixin,
        _ChatPageTerminalEnvMixin,
        _ChatPageConversationFlowMixin,
        _ChatPageUiMixin {}
