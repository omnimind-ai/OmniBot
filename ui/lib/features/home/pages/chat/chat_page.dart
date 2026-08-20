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
import '../command_overlay/services/manual_recording_flow_controller.dart';
import '../command_overlay/services/manual_recording_result_card.dart';
import '../command_overlay/services/tool_card_detail_gesture_gate.dart';
import '../common/openclaw_connection_checker.dart';
import '../omnibot_workspace/widgets/omnibot_workspace_browser.dart';
import 'services/chat_conversation_lifecycle_guard.dart';
import 'services/chat_conversation_runtime_coordinator.dart';
import 'state/chat_page_mode_state.dart';
import 'package:ui/constants/openclaw/openclaw_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/services/app_state_service.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/services/agent_browser_session_service.dart';
import 'package:ui/services/chat_terminal_environment_service.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/omnilink_plugin_service.dart';
import 'package:ui/services/omnilink_event_formatter.dart';
import 'package:ui/services/agent_diff_parser.dart';
import 'package:ui/services/agent_tool_call_parser.dart';
import 'package:ui/services/agent_message_kinds.dart';
import 'package:ui/services/conversation_model_override_service.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/home_greeting_settings_service.dart';
import 'package:ui/services/link_preview_service.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/overlay_service.dart';
import 'package:ui/services/permission_registry.dart';
import 'package:ui/services/permission_service.dart';
import 'package:ui/services/scene_model_config_service.dart';
import 'package:ui/services/shared_open_draft_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_startup_behavior.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/utils/agent_runtime_attachment_payload.dart';
import 'package:ui/features/home/pages/chat/utils/agent_thinking_card_locator.dart';
import 'package:ui/features/home/pages/chat/utils/agent_slash_commands.dart';
import 'package:ui/features/home/pages/chat/utils/deep_thinking_persistence.dart';
import 'package:ui/features/home/pages/chat/utils/composer_lift_intent_tracker.dart';
import 'package:ui/features/home/pages/chat/utils/composer_keyboard_metrics_tracker.dart';
import 'package:ui/features/home/pages/chat/utils/keyboard_inset_motion_tracker.dart';
import 'package:ui/features/home/pages/agent/codex_remote_directory_picker.dart';
import 'package:ui/features/home/pages/agent/codex_remote_workspace_browser.dart';
import 'package:ui/widgets/chat_drawer_gesture_guard.dart';

// 导入 Mixins
import 'mixins/chat_dispatch_support.dart';
import 'mixins/conversation_manager.dart';

// 导入 Widgets
import 'chat_page_models.dart';
import 'tool_activity_utils.dart';
import 'widgets/chat_widgets.dart';
import 'widgets/chat_browser_overlay.dart';
import 'widgets/chat_message_anchor_bar.dart';
import 'widgets/pet_overlay_permission_sheet.dart';
import 'widgets/chat_tool_activity_strip.dart';
import 'widgets/chat_spotlight_tour.dart';
import 'package:ui/widgets/app_update_dialog.dart';
import 'package:ui/widgets/app_background_widgets.dart';
import 'package:ui/widgets/conversation_model_selector.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/omni_glass.dart';

part 'chat_page_browser.dart';
part 'chat_page_lifecycle.dart';
part 'chat_page_model_context.dart';
part 'chat_page_openclaw.dart';
part 'chat_page_terminal_env.dart';
part 'chat_page_agent.dart';
part 'chat_page_conversation_flow.dart';
part 'chat_page_ui.dart';
part 'adapters/agent_runtime_config_parser.dart';
part 'adapters/remote_codex_content_parser.dart';
part 'adapters/remote_codex_history_items.dart';
part 'adapters/remote_codex_snapshot_mapper.dart';
part 'adapters/remote_codex_thread_identity.dart';
part 'widgets/chat_page_overlays.dart';

enum ChatPageMode { normal, openclaw, agent }

enum _SlashCommandPanelRoute { root, effort, agentModel }

const String _kRemoteCodexModeAgentId = 'codex-remote';

class ChatPage extends StatefulWidget {
  final ConversationThreadTarget? threadTarget;
  final bool showFirstUseTour;

  const ChatPage({super.key, this.threadTarget, this.showFirstUseTour = false});

  @override
  State<ChatPage> createState() => _ChatPageState();
}

abstract class _ChatPageStateBase extends State<ChatPage>
    with WidgetsBindingObserver, ChatDispatchSupport, ConversationManager
    implements RouteAware {
  void removeLatestLoadingIfExists() {
    if (_messages.isNotEmpty && _messages.first.isLoading) {
      setState(() => _messages.removeAt(0));
    }
  }

  // ===================== Controllers =====================
  final TextEditingController _messageController = TextEditingController();
  final ScrollController _normalMessageScrollController = ScrollController();
  final ScrollController _openClawMessageScrollController = ScrollController();
  final ScrollController _agentMessageScrollController = ScrollController();
  final List<ChatPageModeState> _modeStates = List<ChatPageModeState>.generate(
    ChatPageMode.values.length,
    (_) => ChatPageModeState(),
    growable: false,
  );
  final PageController _modePageController = PageController(initialPage: 0);
  final FocusNode _inputFocusNode = FocusNode();

  // ===================== Keys =====================
  final GlobalKey<ChatInputAreaState> _chatInputAreaKey =
      GlobalKey<ChatInputAreaState>();
  final GlobalKey<ScaffoldState> _scaffoldKey = GlobalKey<ScaffoldState>();
  // These drawers are mutually exclusive subtrees. They must not share a
  // GlobalKey, otherwise Flutter reparents one keyed subtree between two
  // trees during layout changes and can invalidate inherited dependents.
  final GlobalKey<HomeDrawerState> _embeddedDrawerKey =
      GlobalKey<HomeDrawerState>();
  final GlobalKey<HomeDrawerState> _drawerKey = GlobalKey<HomeDrawerState>();
  final GlobalKey _embeddedDrawerSearchFieldKey = GlobalKey();
  final GlobalKey _drawerSearchFieldKey = GlobalKey();
  final GlobalKey _browserOverlayKey = GlobalKey();
  final GlobalKey _slashCommandStripKey = GlobalKey();
  final GlobalKey _toolActivityStripKey = GlobalKey();
  final GlobalKey _firstUseTourMenuAnchorKey = GlobalKey();
  final GlobalKey _firstUseTourPetAnchorKey = GlobalKey();
  final GlobalKey _firstUseTourIslandAnchorKey = GlobalKey();
  final GlobalKey _firstUseTourModeAnchorKey = GlobalKey();
  final GlobalKey _firstUseTourModelAnchorKey = GlobalKey();

  /// 模型选择器走 OverlayEntry，不走 Navigator.push。
  /// 理由：[Navigator.push] → [ModalRoute.didPush] 会调 `setFirstFocus`
  /// (条件是 **Navigator** 的 `requestFocus`,Route 的 `requestFocus` 不起作用)，
  /// 把焦点从输入框抢走 → 软键盘塌陷 → 输入栏下沉 → popup 锚点错位。
  /// 用 Overlay 直接挂面板可以彻底跳过这条路径。
  OverlayGlassPopupHandle<ConversationModelSelection>?
  _conversationModelSelectorHandle;

  // ===================== State =====================
  bool _isPopupVisible = false;
  int _firstUseTourStep = 0;
  bool _firstUseTourClosing = false;
  bool _isCheckingSendModelConfiguration = false;
  final ChatConversationRuntimeCoordinator _runtimeCoordinator =
      ChatConversationRuntimeCoordinator.instance;
  final ChatConversationLifecycleGuard _conversationLifecycleGuard =
      ChatConversationLifecycleGuard();
  ConversationThreadTarget? _resolvedThreadTarget;
  SharedOpenDraftPayload? _stagedSharedOpenDraft;
  int? _stagedSharedOpenDraftExpiresAt;
  int _conversationTargetRequestId = 0;
  // Conversation bootstrap restores the last target asynchronously. Keep the
  // future so a send started from the first rendered frame cannot race that
  // restore and get cleared by _resetLocalConversationState().
  Future<void>? _conversationBootstrapFuture;
  // A send can be triggered by both the keyboard submit callback and the
  // composer button before the asynchronous conversation bootstrap returns.
  // Keep this synchronous entry guard separate from isAiResponding: the
  // latter is set only after the optimistic user message is inserted.
  bool _sendMessageInFlight = false;
  final Set<String> _consumedInitialMessageRequests = <String>{};

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

  /// 消息锚点面板是否展开。展开时临时关闭抽屉边缘侧滑，避免误触发 home_drawer。
  bool _messageAnchorExpanded = false;
  _ActiveModelMentionToken? _activeModelMentionToken;
  List<ModelProviderProfileSummary> _modelProviderProfiles = const [];
  Map<String, List<ProviderModelOption>> _modelOptionsByProfileId = const {};
  List<SceneCatalogItem> _sceneCatalog = const [];
  int _chatModelCatalogRefreshSerial = 0;
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
  final KeyboardInsetMotionTracker _emptyGreetingKeyboardLiftTracker =
      KeyboardInsetMotionTracker();
  final ComposerLiftIntentTracker _composerLiftIntentTracker =
      ComposerLiftIntentTracker();
  final ComposerKeyboardMetricsTracker _composerKeyboardMetricsTracker =
      ComposerKeyboardMetricsTracker();
  ChatPageModeState _modeState(ChatPageMode mode) => _modeStates[mode.index];
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
  bool _isPetOverlayOpening = false;
  bool _isPetOverlayShowing = false;
  AppUpdateStatus? _appUpdateStatus;
  ModalRoute<dynamic>? _subscribedRoute;
  StreamSubscription<Map<String, dynamic>>?
  _conversationListChangedSubscription;
  StreamSubscription<Map<String, dynamic>>?
  _conversationMessagesChangedSubscription;
  StreamSubscription<Map<String, dynamic>>?
  _browserSessionSnapshotChangedSubscription;
  StreamSubscription<Map<String, dynamic>>? _agentEventSubscription;
  StreamSubscription<Map<String, dynamic>>? _omniLinkEventSubscription;
  Timer? _remoteCodexSessionSyncTimer;
  bool _remoteCodexSessionSyncInFlight = false;
  String? _remoteCodexSessionSyncThreadId;
  String _remoteCodexSessionSyncSignature = '';
  String? _remoteCodexActivityThreadId;
  String _remoteCodexActivityContentSignature = '';
  int? _remoteCodexLastContentChangeAtMs;
  AgentRuntimeStatus _agentRuntimeStatus = AgentRuntimeStatus.disconnected;
  AcpAgentCatalog? _agentCatalog;
  bool _isAgentCatalogLoading = false;
  bool _isAgentRuntimeStatusLoading = false;
  bool _isAcpAgentSwitching = false;
  String? _optimisticAcpAgentId;
  final Map<int, String> _agentIdByConversationId = <int, String>{};
  int? _activeRemoteCodexRuntimeId;
  String? _activeAgentThreadId;
  String? _activeAgentTurnId;
  String? _normalAcpSessionId;
  int? _normalAcpSessionConversationId;
  String? _normalAcpTurnId;
  String? _activeAgentModelId;
  String? _activeAgentReasoningEffort;
  String? _activeAgentCollaborationMode;
  final Set<String> _agentPlanTurnIds = <String>{};
  bool _isAgentModelListLoading = false;
  bool _isAgentCollaborationModeListLoading = false;
  String? _agentModelListError;
  String? _agentCollaborationModeListError;
  String? _loadedAgentModelSourceKey;
  String? _loadingAgentModelSourceKey;
  int _agentModelListRequestId = 0;
  bool _agentModelConfigSupported = false;
  List<String> _agentModelOptions = const <String>[];
  List<String> _agentReasoningEffortOptions = const <String>[];
  List<String> _agentCollaborationModes = const <String>[];
  AgentPermissionMode _agentPermissionMode = AgentPermissionMode.fullAccess;
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
  bool? _wasHdPadLandscape;
  bool _isHdPadPaneDragging = false;
  double? _hdPadPaneDragStartWidth;
  double _hdPadPaneDragDelta = 0;
  final GlobalKey<OmnibotWorkspaceBrowserState> _hdPadWorkspaceBrowserKey =
      GlobalKey<OmnibotWorkspaceBrowserState>();
  final GlobalKey<CodexRemoteWorkspaceBrowserState>
  _hdPadRemoteWorkspaceBrowserKey =
      GlobalKey<CodexRemoteWorkspaceBrowserState>();

  ChatPageMode get _activeMode => _activeConversationMode;

  bool get _isFirstUseTourActive =>
      widget.showFirstUseTour && !_firstUseTourClosing;

  GlobalKey get _firstUseTourAnchorKey => switch (_firstUseTourStep) {
    0 => _firstUseTourMenuAnchorKey,
    1 => _firstUseTourModeAnchorKey,
    2 => _firstUseTourPetAnchorKey,
    3 => _firstUseTourIslandAnchorKey,
    4 => _firstUseTourModelAnchorKey,
    _ => _inputAreaKey,
  };

  void _showNextFirstUseTourStep() {
    if (!mounted ||
        !_isFirstUseTourActive ||
        _firstUseTourStep >= ChatSpotlightTour.stepCount - 1) {
      return;
    }
    setState(() {
      _firstUseTourStep += 1;
    });
  }

  void _handleFirstUseTourBack() {
    if (!mounted || !_isFirstUseTourActive) return;
    if (_firstUseTourStep > 0) {
      setState(() {
        _firstUseTourStep -= 1;
      });
      return;
    }
    Navigator.of(context).pop();
  }

  void _finishFirstUseTour() {
    if (!mounted || _firstUseTourClosing) return;
    setState(() {
      _firstUseTourClosing = true;
    });
    Navigator.of(context).pop(true);
  }

  String? get _conversationBoundAcpAgentId {
    if (_activeMode != ChatPageMode.agent) {
      return null;
    }
    final target = _resolvedThreadTarget;
    if (target?.mode == ConversationMode.agent) {
      final targetAgentId = target?.agentId?.trim() ?? '';
      if (targetAgentId.isNotEmpty) {
        return targetAgentId;
      }
    }
    final conversation = _modeState(ChatPageMode.agent).currentConversation;
    final conversationAgentId = conversation?.agentId?.trim() ?? '';
    if (conversationAgentId.isNotEmpty) {
      return conversationAgentId;
    }
    final conversationId = _modeState(ChatPageMode.agent).currentConversationId;
    final rememberedAgentId = conversationId == null
        ? ''
        : (_agentIdByConversationId[conversationId]?.trim() ?? '');
    if (rememberedAgentId.isNotEmpty) {
      return rememberedAgentId;
    }
    final runtimeMessages = resolveVisibleChatMessages(
      runtimeMessages: _runtimeForMode(ChatPageMode.agent)?.messages,
      fallbackMessages: _modeState(ChatPageMode.agent).messages,
      preserveFallbackDuringHandoff: _modeState(
        ChatPageMode.agent,
      ).isAiResponding,
    );
    for (final message in runtimeMessages.reversed) {
      final messageAgentId = message.agentId?.trim() ?? '';
      if (messageAgentId.isNotEmpty) {
        return messageAgentId;
      }
    }
    return null;
  }

  String? get _activeAcpAgentId {
    final optimisticAgentId = _optimisticAcpAgentId?.trim() ?? '';
    if (optimisticAgentId.isNotEmpty) {
      return optimisticAgentId;
    }
    final targetAgentId = _resolvedThreadTarget?.agentId?.trim() ?? '';
    if (targetAgentId.isNotEmpty) {
      return targetAgentId;
    }
    final boundAgentId = _conversationBoundAcpAgentId;
    if (boundAgentId != null) {
      return boundAgentId;
    }
    if (_agentRuntimeStatus.runtime == 'remote' ||
        _agentRuntimeStatus.remoteEnabled) {
      return _kRemoteCodexModeAgentId;
    }
    final activeId =
        _agentRuntimeStatus.activeAgentId ?? _agentCatalog?.selectedAgentId;
    return activeId?.trim().isNotEmpty == true ? activeId!.trim() : null;
  }

  String get _activeAcpAgentDisplayName {
    final activeAgentId = _activeAcpAgentId?.trim() ?? '';
    if (activeAgentId == _kRemoteCodexModeAgentId) {
      return 'Agent Remote';
    }
    for (final profile in _agentCatalog?.agents ?? const <AcpAgentProfile>[]) {
      if (profile.id == activeAgentId) {
        return profile.name.trim().isEmpty ? 'Agent' : profile.name.trim();
      }
    }
    if (activeAgentId == (_agentRuntimeStatus.activeAgentId?.trim() ?? '')) {
      final statusName = _agentRuntimeStatus.activeAgentName?.trim() ?? '';
      if (statusName.isNotEmpty) {
        return statusName;
      }
    }
    return switch (activeAgentId) {
      'codex-acp' => 'Codex',
      'claude-code-acp' => 'Claude Code',
      'opencode-acp' => 'OpenCode',
      'deepseek-harness-acp' => 'DeepSeek Harness',
      _ => 'Agent',
    };
  }

  List<ChatAcpAgentModeOption> get _chatAcpAgentModeOptions {
    final profiles = _agentCatalog?.agents ?? const <AcpAgentProfile>[];
    final hasXiaowan = profiles.any((profile) => profile.id == 'xiaowan-acp');
    final orderedProfiles = profiles.toList(growable: false)
      ..sort((left, right) {
        final leftIsXiaowan = left.id == 'xiaowan-acp';
        final rightIsXiaowan = right.id == 'xiaowan-acp';
        if (leftIsXiaowan == rightIsXiaowan) {
          return 0;
        }
        return leftIsXiaowan ? -1 : 1;
      });
    final options = <ChatAcpAgentModeOption>[
      // Xiaowan is an in-process built-in ACP Agent. Keep its public entry
      // available even while the asynchronous native catalog is refreshing;
      // otherwise the UI falls back to the legacy OmniAi row and that row
      // cannot carry the ACP profile action.
      if (!hasXiaowan)
        const ChatAcpAgentModeOption(
          id: 'xiaowan-acp',
          name: '小万',
          enabled: true,
          installed: true,
          status: 'online',
        ),
      for (final profile in orderedProfiles)
        ChatAcpAgentModeOption(
          id: profile.id,
          name: profile.name,
          enabled: profile.enabled,
          installed: profile.installed == true,
          status: profile.status,
        ),
      if (_agentRuntimeStatus.remoteConfigured)
        ChatAcpAgentModeOption(
          id: _kRemoteCodexModeAgentId,
          name: 'Agent Remote',
          enabled: _agentRuntimeStatus.remoteEnabled,
          status: _agentRuntimeStatus.ready ? 'online' : 'offline',
        ),
    ];
    return options;
  }

  ConversationMode _conversationModeForPageMode(ChatPageMode mode) {
    if (mode == ChatPageMode.agent) {
      return ConversationMode.agent;
    }
    if (mode == ChatPageMode.openclaw) {
      return ConversationMode.openclaw;
    }
    final runtimeConversation =
        _runtimeForMode(mode)?.conversation ??
        _modeState(mode).currentConversation;
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
      : mode == ConversationMode.agent
      ? ChatPageMode.agent
      : ChatPageMode.normal;
  ChatSurfaceMode _surfaceForConversationMode(ConversationMode mode) =>
      mode == ConversationMode.openclaw
      ? ChatSurfaceMode.openclaw
      : ChatSurfaceMode.normal;
  String _modeKey(ChatPageMode mode) => switch (mode) {
    ChatPageMode.normal => kChatRuntimeModeNormal,
    ChatPageMode.openclaw => kChatRuntimeModeOpenClaw,
    ChatPageMode.agent => kChatRuntimeModeAgent,
  };
  ChatConversationRuntimeState? _runtimeForMode(ChatPageMode mode) {
    final conversationId = _modeState(mode).currentConversationId;
    if (conversationId == null) return null;
    return _runtimeCoordinator.runtimeFor(
      conversationId: conversationId,
      mode: _modeKey(mode),
    );
  }

  bool _isRemoteCodexRuntimeActiveForMode(ChatPageMode mode) {
    if (mode != ChatPageMode.agent) {
      return false;
    }
    final conversationId = _modeState(mode).currentConversationId;
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
    return _runtimeForMode(mode)?.chatIslandDisplayLayer ??
        _modeState(mode).chatIslandDisplayLayer;
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
      runtime.currentDispatchTurnId ?? '',
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
    _modeState(mode).runtimeChromeSignature = _runtimeChromeSignature(runtime);
    _modeState(mode).runtimeMessageMutationRevision =
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
    final conversationId = _modeState(_activeMode).currentConversationId;
    if (_activeMode == ChatPageMode.agent &&
        _isRemoteCodexRuntimeActiveForMode(ChatPageMode.agent)) {
      final threadId = _activeAgentThreadId?.trim() ?? '';
      if (threadId.isNotEmpty) {
        return ConversationThreadTarget.agentSession(
          sessionId: threadId,
          runtime: 'remote',
          agentId: _kRemoteCodexModeAgentId,
        );
      }
      return ConversationThreadTarget.newConversation(
        mode: ConversationMode.agent,
        agentRuntime: 'remote',
        agentId: _kRemoteCodexModeAgentId,
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
    final localAgentThreadId = _activeMode == ChatPageMode.agent
        ? _activeAgentThreadId?.trim()
        : null;
    return ConversationThreadTarget.existing(
      conversationId: conversationId,
      mode: conversationMode,
      agentId: _activeMode == ChatPageMode.agent ? _activeAcpAgentId : null,
      agentSessionId: localAgentThreadId == null || localAgentThreadId.isEmpty
          ? null
          : localAgentThreadId,
      agentRuntime: localAgentThreadId == null || localAgentThreadId.isEmpty
          ? null
          : 'local',
    );
  }

  ConversationThreadTarget? get _visibleThreadTarget =>
      _isWorkspaceSurface ? null : _threadTargetForMode;

  bool get _isPureChatSelected =>
      _conversationModeForPageMode(ChatPageMode.normal) ==
      ConversationMode.chatOnly;

  bool get _isPureChatToggleLocked => false;

  Future<void> _handleAgentModeShortcutTap() async {
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
    if (_activeMode == ChatPageMode.agent) {
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
    final nextTarget = _newThreadTargetForConversationMode(nextMode);
    await _applyConversationThreadTarget(nextTarget);
  }

  String get _expectedBrowserWorkspaceId => chatConversationWorkspaceId(
    _modeState(ChatPageMode.normal).currentConversationId,
  );

  List<ChatMessageModel> get _messages => resolveVisibleChatMessages(
    runtimeMessages: _activeRuntime?.messages,
    fallbackMessages: _modeState(_activeMode).messages,
    preserveFallbackDuringHandoff: _modeState(_activeMode).isAiResponding,
  );
  double get _toolActivityOccupiedHeight =>
      _modeState(_activeMode).toolActivityOccupiedHeight;
  double get _slashCommandPanelOccupiedHeight =>
      _modeState(_activeMode).slashCommandPanelOccupiedHeight;
  bool get _isSlashCommandExpanded =>
      _modeState(_activeMode).slashCommandExpanded;
  bool get _isToolActivityExpanded =>
      _modeState(_activeMode).toolActivityExpanded;
  Set<String> _expandedAgentRunTaskIdsForMode(ChatPageMode mode) =>
      _modeState(mode).expandedAgentRunTaskIds;
  String? _latestExpandedAgentRunTaskIdForMode(ChatPageMode mode) {
    final orderedTaskIds = _modeState(mode).expandedAgentRunTaskOrder;
    if (orderedTaskIds.isEmpty) {
      return null;
    }
    return orderedTaskIds.last;
  }

  double get _inputAreaHeight => _modeState(_activeMode).inputAreaHeight;
  bool get _isAiResponding =>
      _activeRuntime?.isAiResponding ??
      (_modeState(_activeMode).isAiResponding);
  set _isAiResponding(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isAiResponding = value;
      return;
    }
    _modeState(_activeMode).isAiResponding = value;
  }

  bool get _isContextCompressing =>
      _activeRuntime?.isContextCompressing ??
      (_modeState(_activeMode).isContextCompressing);
  set _isContextCompressing(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isContextCompressing = value;
      return;
    }
    _modeState(_activeMode).isContextCompressing = value;
  }

  bool get _isCheckingExecutableTask =>
      _activeRuntime?.isCheckingExecutableTask ??
      (_modeState(_activeMode).isCheckingExecutableTask);
  set _isCheckingExecutableTask(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isCheckingExecutableTask = value;
      return;
    }
    _modeState(_activeMode).isCheckingExecutableTask = value;
  }

  Map<String, String> get _currentAiMessages =>
      _activeRuntime?.currentAiMessages ??
      _modeState(_activeMode).currentAiMessages;
  String get _deepThinkingContent =>
      _activeRuntime?.deepThinkingContent ??
      (_modeState(_activeMode).deepThinkingContent);
  set _deepThinkingContent(String value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.deepThinkingContent = value;
      return;
    }
    _modeState(_activeMode).deepThinkingContent = value;
  }

  bool get _isDeepThinking =>
      _activeRuntime?.isDeepThinking ??
      (_modeState(_activeMode).isDeepThinking);
  set _isDeepThinking(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isDeepThinking = value;
      return;
    }
    _modeState(_activeMode).isDeepThinking = value;
  }

  String? get _currentDispatchTurnId =>
      _activeRuntime?.currentDispatchTurnId ??
      _modeState(_activeMode).currentDispatchTurnId;
  set _currentDispatchTurnId(String? value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.currentDispatchTurnId = value;
      return;
    }
    _modeState(_activeMode).currentDispatchTurnId = value;
  }

  int get _currentThinkingStage =>
      _activeRuntime?.currentThinkingStage ??
      (_modeState(_activeMode).currentThinkingStage);
  set _currentThinkingStage(int value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.currentThinkingStage = value;
      return;
    }
    _modeState(_activeMode).currentThinkingStage = value;
  }

  bool get _isInputAreaVisible =>
      _activeRuntime?.isInputAreaVisible ??
      (_modeState(_activeMode).isInputAreaVisible);
  set _isInputAreaVisible(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isInputAreaVisible = value;
      return;
    }
    _modeState(_activeMode).isInputAreaVisible = value;
  }

  bool get _isExecutingTask =>
      _activeRuntime?.isExecutingTask ??
      (_modeState(_activeMode).isExecutingTask);
  set _isExecutingTask(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isExecutingTask = value;
      return;
    }
    _modeState(_activeMode).isExecutingTask = value;
  }

  int? get _currentConversationId =>
      _modeState(_activeMode).currentConversationId;
  set _currentConversationId(int? value) =>
      _modeState(_activeMode).currentConversationId = value;
  ConversationModel? get _currentConversation =>
      _activeRuntime?.conversation ??
      _modeState(_activeMode).currentConversation;
  set _currentConversation(ConversationModel? value) {
    _modeState(_activeMode).currentConversation = value;
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
    _modeState(_activeMode).chatIslandDisplayLayer = value;
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
      _modeState(_activeMode).lastAgentToolType;
  set _lastAgentToolType(String? value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.lastAgentToolType = value;
      return;
    }
    _modeState(_activeMode).lastAgentToolType = value;
  }

  ChatBrowserSessionSnapshot? get _browserSessionSnapshot =>
      _activeRuntime?.browserSessionSnapshot ??
      _modeState(_activeMode).browserSessionSnapshot;
  set _browserSessionSnapshot(ChatBrowserSessionSnapshot? value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.browserSessionSnapshot = value;
      return;
    }
    _modeState(_activeMode).browserSessionSnapshot = value;
  }

  bool get _supportsReasoningEffortCommand =>
      _activeMode == ChatPageMode.normal && !_isOpenClawSurface;

  _SlashCommandPanelRoute _resolveSlashCommandPanelRoute(String text) {
    final trimmed = text.trimLeft();
    if (!trimmed.startsWith('/')) {
      return _SlashCommandPanelRoute.root;
    }
    final normalized = trimmed.toLowerCase();
    if (_activeMode == ChatPageMode.agent &&
        (normalized == '/model' || normalized.startsWith('/model '))) {
      return _SlashCommandPanelRoute.agentModel;
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
      _SlashCommandPanelRoute.agentModel =>
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
      _modeState(_activeMode).slashCommandExpanded = expanded;
      return;
    }
    setState(() {
      _modeState(_activeMode).slashCommandExpanded = expanded;
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
      _modeState(_activeMode).pendingAttachments;
  String? get _editingUserMessageId =>
      _modeState(_activeMode).editingUserMessageId;
  set _editingUserMessageId(String? value) =>
      _modeState(_activeMode).editingUserMessageId = value;
  void _updateExpandedAgentRunTaskIds(ChatPageMode mode, Set<String> taskIds) {
    final normalizedTaskIds = taskIds
        .map((item) => item.trim())
        .where((item) => item.isNotEmpty)
        .toSet();
    final currentTaskIds = _expandedAgentRunTaskIdsForMode(mode);
    final currentOrder = _modeState(mode).expandedAgentRunTaskOrder;
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
      _modeState(mode).expandedAgentRunTaskIds
        ..clear()
        ..addAll(normalizedTaskIds);
      _modeState(mode).expandedAgentRunTaskOrder
        ..clear()
        ..addAll(nextOrderedTaskIds);
    });
  }

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
    if (dispatchScene?.effectiveProviderProfileId.trim().isNotEmpty == true &&
        effectiveModel.isNotEmpty) {
      return effectiveModel;
    }
    return null;
  }

  bool get _hasSelectableNormalChatModels {
    return _hasSelectableProviderModels;
  }

  bool get _hasSelectableProviderModels {
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

  // Chat dispatch state
  @override
  List<ChatMessageModel> get messages => _messages;
  @override
  bool get isAiResponding => _isAiResponding;
  @override
  set isAiResponding(bool value) => _isAiResponding = value;
  @override
  Map<String, String> get currentAiMessages => _currentAiMessages;
  // Agent stream state
  @override
  String get deepThinkingContent => _deepThinkingContent;
  @override
  set deepThinkingContent(String value) => _deepThinkingContent = value;
  @override
  bool get isDeepThinking => _isDeepThinking;
  @override
  set isDeepThinking(bool value) => _isDeepThinking = value;
  @override
  String? get currentDispatchTurnId => _currentDispatchTurnId;
  @override
  set currentDispatchTurnId(String? value) => _currentDispatchTurnId = value;
  @override
  int get currentThinkingStage => _currentThinkingStage;
  @override
  set currentThinkingStage(int value) => _currentThinkingStage = value;

  // ChatDispatchSupport
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
  bool get hasMoreMessages => _modeState(_activeMode).hasMoreMessages;
  @override
  set hasMoreMessages(bool value) =>
      _modeState(_activeMode).hasMoreMessages = value;
  @override
  bool get isLoadingMore => _modeState(_activeMode).isLoadingMore;
  @override
  set isLoadingMore(bool value) =>
      _modeState(_activeMode).isLoadingMore = value;
  @override
  int get messageOffset => _modeState(_activeMode).messageOffset;
  @override
  set messageOffset(int value) => _modeState(_activeMode).messageOffset = value;
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
      preserveLiveStreamingState: runtime?.hasInFlightTask == true,
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
    _modeState(pageMode).currentConversationId = conversationId;
    _modeState(pageMode).currentConversation = conversation;
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
    _embeddedDrawerKey.currentState?.reloadConversations();
    _drawerKey.currentState?.reloadConversations();
  }

  @override
  void createThinkingCard(String taskID) => _createThinkingCard(taskID);

  @override
  void updateThinkingCard(String taskID) => _updateThinkingCard(taskID);

  @override
  void handleValidationError(String taskID, String debugMessage) {
    handleAgentError(debugMessage);
  }

  @override
  void resetDispatchState() {
    _currentDispatchTurnId = null;
    _deepThinkingContent = '';
    _isDeepThinking = false;
    clearAgentStreamSessionState();
  }

  void clearAgentStreamSessionState() {
    final conversationId = _currentConversationId;
    if (conversationId == null) return;
    _runtimeCoordinator.clearConversationRuntimeSession(
      conversationId: conversationId,
      mode: _modeKey(_activeMode),
    );
  }

  void handleAgentError(String error) {
    final runtime = _runtimeForMode(_activeMode);
    final taskId = runtime?.currentDispatchTurnId;
    if (runtime == null || taskId == null || taskId.trim().isEmpty) {
      return;
    }
    final messageId = '$taskId-error';
    final message = ChatMessageModel(
      id: messageId,
      type: 1,
      user: 2,
      content: <String, dynamic>{'text': error, 'id': messageId},
      isError: true,
    );
    final index = runtime.messages.indexWhere((item) => item.id == messageId);
    if (index == -1) {
      runtime.messages.insert(0, message);
    } else {
      runtime.messages[index] = message;
    }
    runtime.isAiResponding = false;
    runtime.isCheckingExecutableTask = false;
    runtime.isExecutingTask = false;
    clearAgentStreamSessionState();
    if (mounted) {
      setState(() {});
    }
    unawaited(saveConversation());
  }

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
    final activeMode = _activeConversationMode;
    final isNormalAcp =
        activeMode == ChatPageMode.normal &&
        activeConversationModeValue != ConversationMode.chatOnly;
    final response = await AgentRuntimeService.cancelPrompt(
      conversationId: _currentConversationId,
      sessionId: isNormalAcp ? _normalAcpSessionId : _activeAgentThreadId,
      promptId: isNormalAcp ? _normalAcpTurnId : _activeAgentTurnId,
    );
    return response['cancelled'] == true || response['status'] == 'cancelled';
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
    final previousChromeSignature = _modeState(mode).runtimeChromeSignature;
    final nextMutationRevision = runtime.messages.lastMutationRevision;
    final previousMutationRevision = _modeState(
      mode,
    ).runtimeMessageMutationRevision;
    final hasChromeChange = nextChromeSignature != previousChromeSignature;
    final hasMessageMutation = nextMutationRevision != previousMutationRevision;

    _modeState(mode).runtimeChromeSignature = nextChromeSignature;
    _modeState(mode).runtimeMessageMutationRevision = nextMutationRevision;

    if (hasChromeChange ||
        (hasMessageMutation &&
            runtime.messages.lastMutationAffectsPageChrome)) {
      setState(() {});
    }
  }

  void _resetLocalConversationState(ChatPageMode mode) {
    _modeState(mode).resetConversation();
    if (mode == ChatPageMode.agent) {
      _stopRemoteCodexSessionSync();
      _activeRemoteCodexRuntimeId = null;
      _activeAgentThreadId = null;
      _activeAgentTurnId = null;
    }
    if (mode == ChatPageMode.normal) {
      // ACP sessions are bound to a conversation. Never carry the previous
      // conversation's session id into the next prompt, otherwise the local
      // runtime correctly reuses the explicit old session and the user sees
      // repeated or cross-conversation context.
      _normalAcpSessionId = null;
      _normalAcpSessionConversationId = null;
      _normalAcpTurnId = null;
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

  Future<void> _handlePetOverlayTap();

  Future<void> _syncPetOverlayState();

  void _armComposerLiftIntent();

  void _requestComposerFocus({bool showKeyboard});

  void _onFocusChange();

  void _handleAppUpdateStatusChanged();

  double _popupMenuBottomOffset();

  Future<void> _handleAppUpdateBannerTap();

  Future<void> _refreshAgentRuntimeStatus();

  Future<void> _loadAgentCatalog({bool force = false});

  Future<void> _refreshAgentCommandPreferences();

  Future<void> _loadAgentModelOptionsWhenReady({bool force = false});

  Future<void> _loadAgentModelOptions({bool force = false});

  Future<void> _loadAgentCollaborationModes({bool force = false});

  Future<void> _selectAgentModel(String modelId, {bool clearComposer = true});

  Future<bool> _selectAgent(String agentId);

  Future<void> _handleAcpAgentModeShortcutTap(String agentId);

  Future<void> _selectAgentReasoningEffort(String effort);

  Future<void> _selectAgentPermissionMode(AgentPermissionMode mode);

  Future<void> _activateAgentPlanMode({
    bool persistOnly = false,
    bool dismissPanel = true,
  });

  Future<void> _deactivateAgentPlanMode({bool dismissPanel = true});

  Future<void> _handleAgentSlashCommandCardSelected(
    Map<String, dynamic> cardData,
  );

  Future<bool> _tryHandleAgentSlashCommand(
    String messageText, {
    List<Map<String, dynamic>> attachments = const [],
  });

  Future<void> _executeAgentInitCommand();

  Future<void> _startAgentReviewCommand();

  Future<void> _handleAgentTap();

  String? _remoteCodexWorkspaceNameForGreeting();

  Future<void> _openRemoteCodexWorkspacePicker();

  Future<void> _prepareRemoteCodexSessionTarget(
    ConversationThreadTarget target,
  );

  void _handleAgentRuntimeEvent(Map<String, dynamic> event);

  void _stopRemoteCodexSessionSync();

  Future<void> _sendAgentMessage(
    String aiMessageId,
    String messageText, {
    List<Map<String, dynamic>> attachments = const [],
    String? modelOverride,
    String? collaborationModeOverride,
  });

  Future<void> _interruptAgentTurn();

  Future<AgentRuntimeStatus> _refreshConnectedAgentRuntimeStatus();

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

  Future<bool> _tryHandleSlashCommand(
    String messageText, {
    List<Map<String, dynamic>> attachments = const [],
  });

  Future<void> _executeManualContextCompactionCommand();

  Future<void> _checkOpenClawConnection();

  void _syncRuntimeSnapshotForMode(
    ChatPageMode mode, {
    ConversationModel? conversation,
    List<ChatMessageModel>? messages,
    bool preserveLiveStreamingState = false,
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

  Future<void> _startManualRecordingCommand(String messageText);

  Future<void> _sendMessage({String? text});

  Future<void> _retryUserMessageText(
    String text, {
    List<Map<String, dynamic>> attachments,
    String? retainedUserMessageId,
  });

  Future<void> _sendChatMessage(String aiMessageId);

  Future<void> _sendPureChatMessage(String aiMessageId);

  Future<bool> _handleExecutableTaskFlow(
    String aiMessageId,
    String userMessageId,
  );

  Future<bool> _tryAgentFlow(
    String aiMessageId,
    String userMessageId, {
    String? promptText,
    List<Map<String, dynamic>>? attachmentsOverride,
  });

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
        _ChatPageAgentMixin,
        _ChatPageLifecycleMixin,
        _ChatPageModelContextMixin,
        _ChatPageOpenClawMixin,
        _ChatPageTerminalEnvMixin,
        _ChatPageConversationFlowMixin,
        _ChatPageUiMixin {}
