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
import '../../../../services/agent_identity.dart';
import '../../../../services/assists_core_service.dart';
import '../../widgets/home_drawer.dart';
import '../authorize/authorize_page_args.dart';
import '../command_overlay/widgets/chat_input_area.dart';
import '../command_overlay/services/manual_recording_flow_controller.dart';
import '../command_overlay/services/manual_recording_result_card.dart';
import 'package:ui/features/task/run_log/omniflow_tool_client.dart';
import '../command_overlay/services/tool_card_detail_gesture_gate.dart';
import '../common/openclaw_connection_checker.dart';
import '../omnibot_workspace/widgets/omnibot_workspace_browser.dart';
import 'services/chat_conversation_lifecycle_guard.dart';
import 'services/chat_conversation_runtime_coordinator.dart';
import 'state/chat_page_mode_state.dart';
import 'package:ui/constants/openclaw/openclaw_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
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
import 'widgets/acp_config_button.dart';
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
part 'chat_page_remote_codex.dart';
part 'chat_page_conversation_flow.dart';
part 'chat_page_ui.dart';
part 'chat_page_user_message_actions.dart';
part 'adapters/agent_runtime_config_parser.dart';
part 'adapters/remote_codex_content_parser.dart';
part 'adapters/remote_codex_history_items.dart';
part 'adapters/remote_codex_snapshot_mapper.dart';
part 'adapters/remote_codex_thread_identity.dart';
part 'widgets/chat_page_overlays.dart';

enum ChatPageMode { normal, openclaw, agent }

enum _SlashCommandPanelRoute { root, effort, agentModel }

const String _kRemoteCodexModeAgentId = 'codex-remote';
const String _kXiaowanAcpAgentId = 'xiaowan-acp';

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
  final ConversationModelSelectorOpeningGuard
  _conversationModelSelectorOpeningGuard =
      ConversationModelSelectorOpeningGuard();

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
  // Guard each visible conversation target independently: a prompt remains
  // in flight until its ACP turn completes, but that must not block sending
  // from another conversation that the user opens in the meantime.
  final Set<int> _sendMessageInFlightTargetIds = <int>{};
  final HarnessSwitchSendBarrier _harnessSwitchSendBarrier =
      HarnessSwitchSendBarrier();
  final Set<String> _consumedInitialMessageRequests = <String>{};

  // OpenClaw 配置与开关
  bool _openClawEnabled = false;
  String _openClawBaseUrl = '';
  String _openClawToken = '';
  String _openClawUserId = '';
  ChatSurfaceMode _activeSurfaceMode = ChatSurfaceMode.normal;
  // The default Xiaowan surface is the built-in ACP Agent. `normal` remains
  // a compatibility page state for old routes, not a second runtime mode.
  ChatPageMode _activeConversationMode = ChatPageMode.agent;
  bool _showSlashCommandPanel = false;
  bool _showModelMentionPanel = false;
  bool _openClawPanelExpanded = false;

  /// 消息锚点面板是否展开。展开时临时关闭抽屉边缘侧滑，避免误触发 home_drawer。
  bool _messageAnchorExpanded = false;
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
  final KeyboardInsetMotionTracker _emptyGreetingKeyboardLiftTracker =
      KeyboardInsetMotionTracker();
  final ComposerLiftIntentTracker _composerLiftIntentTracker =
      ComposerLiftIntentTracker();
  final ComposerKeyboardMetricsTracker _composerKeyboardMetricsTracker =
      ComposerKeyboardMetricsTracker();
  ChatPageModeState _modeState(ChatPageMode mode) => _modeStates[mode.index];
  bool _isAwaitingAuthorizeResult = false;
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
  final Set<String> _pendingManualAgentRetryTaskIds = <String>{};
  bool _pendingAgentInputResponseInFlight = false;
  bool _remoteCodexSessionSyncInFlight = false;
  String? _remoteCodexSessionSyncThreadId;
  AgentRuntimeStatus _agentRuntimeStatus = AgentRuntimeStatus.disconnected;
  AcpAgentCatalog? _agentCatalog;
  bool _isAgentCatalogLoading = false;
  bool _isAgentRuntimeStatusLoading = false;
  bool _isAcpAgentSwitching = false;
  // Monotonic epoch for status snapshots. A status request started before a
  // Harness switch may complete afterwards; without an epoch it can paint
  // the previous runtime over the newly selected one in the AppBar.
  int _agentRuntimeStatusEpoch = 0;
  int _agentCatalogEpoch = 0;
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
  String? _agentReasoningEffortConfigId;
  String? _activeAgentCollaborationMode;
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
    // Pure chat is still an ACP session; it only disables tool capabilities.
    // Keep the selected Agent identity available so the chat chrome and run
    // headers render the same brand avatar after switching Harnesses.
    final optimisticAgentId = _optimisticAcpAgentId?.trim() ?? '';
    if (optimisticAgentId.isNotEmpty) {
      return optimisticAgentId;
    }
    if (_activeMode == ChatPageMode.normal &&
        activeConversationModeValue == ConversationMode.normal) {
      return _kXiaowanAcpAgentId;
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
    return activeAgentId.isEmpty ? 'Agent' : activeAgentId;
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
          // The top-right switcher is an installed-Agent surface, not the
          // profile/configuration catalog. The native ACP catalog reports
          // `installed` from its health probe; null and false must remain
          // unavailable until that fact is established.
          installed: profile.installed == true,
          status: profile.status,
        ),
    ];
    return options;
  }

  /// The app-bar identity is presentation-only and has one source of truth:
  /// the Harness that is being switched to, or the Harness currently
  /// connected by the ACP runtime. A conversation binding describes history;
  /// it must not make the top-right control oscillate between an old session
  /// owner and the live process during asynchronous restore/switch work.
  String? get _appBarActiveAcpAgentId {
    final optimisticId = _optimisticAcpAgentId?.trim() ?? '';
    if (_isAcpAgentSwitching && optimisticId.isNotEmpty) {
      return optimisticId;
    }

    final runtimeId =
        _activeMode == ChatPageMode.agent && _agentRuntimeStatus.connected
        ? (_agentRuntimeStatus.runtime == 'remote' ||
                  _agentRuntimeStatus.remoteEnabled
              ? _kRemoteCodexModeAgentId
              : (_agentRuntimeStatus.activeAgentId?.trim() ?? ''))
        : '';
    final activeId = runtimeId.isNotEmpty
        ? runtimeId
        : (_activeAcpAgentId?.trim() ?? '');
    if (activeId.isEmpty) return null;

    // A connected runtime is authoritative even while the catalog request is
    // still in flight. The brand icon can render from the stable agent id,
    // and the next catalog refresh will fill in the menu metadata.
    if (runtimeId.isNotEmpty) return runtimeId;

    final isVisible = _chatAcpAgentModeOptions.any(
      (agent) => agent.id == activeId && agent.isAvailable,
    );
    return isVisible ? activeId : null;
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
    // Xiaowan used to be exposed as `normal`. Keep the page state compatible,
    // but route its durable conversation and ACP history through Agent.
    return ConversationMode.agent;
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

  bool get _hasSingleModePagePosition =>
      _modePageController.hasClients &&
      _modePageController.positions.length == 1;

  double get _surfacePageProgress {
    final fallback = _pageIndexForSurface(_activeSurfaceMode).toDouble();
    // A surface switch/orientation change can briefly leave the controller
    // attached to both the old and the new PageView.  PageController.page is
    // only defined for exactly one attached position; reading it during that
    // transition throws and replaces the visible chat subtree with the
    // app-wide ErrorWidget.
    if (!_hasSingleModePagePosition) {
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
    final target = _newThreadTargetForConversationMode(ConversationMode.agent);
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
        ? ConversationMode.agent
        : ConversationMode.chatOnly;
    final nextTarget = _newThreadTargetForConversationMode(nextMode);
    await _applyConversationThreadTarget(nextTarget);
  }

  String get _expectedBrowserWorkspaceId => chatConversationWorkspaceId(
    _modeState(_activeConversationMode).currentConversationId,
  );

  List<ChatMessageModel> get _messages => resolveVisibleChatMessages(
    runtimeMessages: _activeRuntime?.messages,
    fallbackMessages: _modeState(_activeMode).messages,
    preserveFallbackDuringHandoff: _activeMode == ChatPageMode.agent
        ? false
        : _modeState(_activeMode).isAiResponding,
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
  // ACP Agent lifecycle state has one owner: the conversation runtime. The
  // mode object is presentation state and may not resurrect a failed turn.
  bool get _isAiResponding =>
      _activeRuntime?.isAiResponding ??
      (_activeMode == ChatPageMode.agent
          ? false
          : _modeState(_activeMode).isAiResponding);
  set _isAiResponding(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isAiResponding = value;
      return;
    }
    if (_activeMode != ChatPageMode.agent) {
      _modeState(_activeMode).isAiResponding = value;
    }
  }

  /// ACP user-input requests use the normal chat composer.  The request card
  /// can still expose structured options, but it must not create a second
  /// text field inside the conversation timeline.
  Map<String, dynamic>? get _pendingAgentUserInputCard {
    if (_activeMode != ChatPageMode.agent) {
      return null;
    }
    for (final message in _messages.reversed) {
      final card = message.cardData;
      if (card == null || !isAgentRequestCardType(card['type'])) {
        continue;
      }
      if (card['requestKind']?.toString() != 'user_input' ||
          card['status']?.toString() != 'pending' ||
          card['requestId'] == null) {
        continue;
      }
      return card;
    }
    return null;
  }

  bool get _hasPendingAgentUserInputRequest =>
      _pendingAgentUserInputCard != null;

  bool get _isContextCompressing =>
      _activeRuntime?.isContextCompressing ??
      (_activeMode == ChatPageMode.agent
          ? false
          : _modeState(_activeMode).isContextCompressing);
  set _isContextCompressing(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isContextCompressing = value;
      return;
    }
    if (_activeMode != ChatPageMode.agent) {
      _modeState(_activeMode).isContextCompressing = value;
    }
  }

  bool get _isCheckingExecutableTask =>
      _activeRuntime?.isCheckingExecutableTask ??
      (_activeMode == ChatPageMode.agent
          ? false
          : _modeState(_activeMode).isCheckingExecutableTask);
  set _isCheckingExecutableTask(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isCheckingExecutableTask = value;
      return;
    }
    if (_activeMode != ChatPageMode.agent) {
      _modeState(_activeMode).isCheckingExecutableTask = value;
    }
  }

  Map<String, String> get _currentAiMessages =>
      _activeRuntime?.currentAiMessages ??
      (_activeMode == ChatPageMode.agent
          ? <String, String>{}
          : _modeState(_activeMode).currentAiMessages);
  String get _deepThinkingContent =>
      _activeRuntime?.deepThinkingContent ??
      (_activeMode == ChatPageMode.agent
          ? ''
          : _modeState(_activeMode).deepThinkingContent);
  set _deepThinkingContent(String value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.deepThinkingContent = value;
      return;
    }
    if (_activeMode != ChatPageMode.agent) {
      _modeState(_activeMode).deepThinkingContent = value;
    }
  }

  bool get _isDeepThinking =>
      _activeRuntime?.isDeepThinking ??
      (_activeMode == ChatPageMode.agent
          ? false
          : _modeState(_activeMode).isDeepThinking);
  set _isDeepThinking(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isDeepThinking = value;
      return;
    }
    if (_activeMode != ChatPageMode.agent) {
      _modeState(_activeMode).isDeepThinking = value;
    }
  }

  String? get _currentDispatchTurnId =>
      _activeRuntime?.currentDispatchTurnId ??
      (_activeMode == ChatPageMode.agent
          ? null
          : _modeState(_activeMode).currentDispatchTurnId);
  set _currentDispatchTurnId(String? value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.currentDispatchTurnId = value;
      return;
    }
    if (_activeMode != ChatPageMode.agent) {
      _modeState(_activeMode).currentDispatchTurnId = value;
    }
  }

  int get _currentThinkingStage =>
      _activeRuntime?.currentThinkingStage ??
      (_activeMode == ChatPageMode.agent
          ? ThinkingStage.thinking.value
          : _modeState(_activeMode).currentThinkingStage);
  set _currentThinkingStage(int value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.currentThinkingStage = value;
      return;
    }
    if (_activeMode != ChatPageMode.agent) {
      _modeState(_activeMode).currentThinkingStage = value;
    }
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
      (_activeMode == ChatPageMode.agent
          ? false
          : _modeState(_activeMode).isExecutingTask);
  set _isExecutingTask(bool value) {
    final runtime = _activeRuntime;
    if (runtime != null) {
      runtime.isExecutingTask = value;
      return;
    }
    if (_activeMode != ChatPageMode.agent) {
      _modeState(_activeMode).isExecutingTask = value;
    }
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
  String? get agentIdForNewConversation =>
      activeConversationModeValue == ConversationMode.agent
      ? _activeAcpAgentId
      : null;
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
    // Agent turn cleanup belongs to the shared ACP runtime. A page-level
    // reset has no turn identity and can therefore erase a newer turn after a
    // late preflight/transport callback. Legacy/non-Agent flows still retain
    // their historical reset path below.
    if (_activeMode == ChatPageMode.agent) return;
    _currentDispatchTurnId = null;
    _deepThinkingContent = '';
    _isDeepThinking = false;
    clearAgentStreamSessionState();
  }

  void clearAgentStreamSessionState() {
    if (_activeMode == ChatPageMode.agent) return;
    final conversationId = _currentConversationId;
    if (conversationId == null) return;
    _runtimeCoordinator.clearConversationRuntimeSession(
      conversationId: conversationId,
      mode: _modeKey(_activeMode),
    );
  }

  /// Routes the official ACP `session/cancel` result into the same shared
  /// reducer used by `session/update` and `session/prompt`. Native code must
  /// not emit a private terminal event just to make a cancelled spinner stop.
  Future<void> cancelAcpPromptForMode({
    required ChatPageMode mode,
    String? sessionId,
    String? turnId,
  }) async {
    final conversationId = _modeState(mode).currentConversationId;
    if (conversationId == null) return;
    try {
      await AgentRuntimeService.cancelPrompt(
        conversationId: conversationId,
        sessionId: sessionId,
        promptId: turnId,
      );
    } catch (error) {
      debugPrint('ACP cancellation failed: $error');
    }
  }

  void handleAgentError(String error, {String? taskIdOverride}) {
    final runtime = _runtimeForMode(_activeMode);
    // The caller may have already detached the runtime task before the error
    // reaches this method.  Prefer the explicit logical turn id in that case;
    // reading only currentDispatchTurnId loses the identity boundary and can
    // leave the fallback composer spinning forever.
    final taskId = taskIdOverride?.trim().isNotEmpty == true
        ? taskIdOverride!.trim()
        : runtime?.currentDispatchTurnId;
    final displayError = formatAgentRuntimeErrorForUser(error);
    if (runtime == null || taskId == null || taskId.trim().isEmpty) {
      if (mounted) showToast(displayError, type: ToastType.error);
      return;
    }
    _runtimeCoordinator.clearTaskThinkingPresentation(
      taskId: taskId,
      conversationId: runtime.conversationId,
      mode: runtime.mode,
    );
    _runtimeCoordinator.applyAcpPromptResponse(
      taskId: taskId,
      conversationId: runtime.conversationId,
      mode: runtime.mode,
      sessionId: runtime.activeAcpSessionId,
      turnId: runtime.activeAcpTurnId,
      stopReason: 'error',
      error: displayError,
    );
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
    String? runId;
    for (final message
        in _activeRuntime?.messages ?? const <ChatMessageModel>[]) {
      final cardData = message.cardData;
      final messageCardId = (cardData?['cardId'] ?? '').toString().trim();
      if (message.id != cardId && messageCardId != cardId) {
        continue;
      }
      final candidate = (cardData?['runId'] ?? cardData?['run_id'])
          ?.toString()
          .trim();
      if (candidate != null && candidate.isNotEmpty) {
        runId = candidate;
      }
      break;
    }
    final response = await AgentRuntimeService.cancelPrompt(
      conversationId: _currentConversationId,
      sessionId: isNormalAcp ? _normalAcpSessionId : _activeAgentThreadId,
      promptId: isNormalAcp ? _normalAcpTurnId : _activeAgentTurnId,
      runId: runId,
    );
    return isAgentCancellationSuccessful(response);
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

  Future<void> _sendAgentMessage(
    String aiMessageId,
    String messageText, {
    required String userMessageId,
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

  Future<void> _sendMessage({String? text, bool waitForBootstrap = true});

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
    String? requestIdOverride,
  });

  Future<List<Map<String, dynamic>>> _latestUserAttachments();

  void _onCancelTask();

  void _cancelDispatchTask();

  void _onCancelTaskFromCard(String taskId);

  void _updateThinkingCardToCancelled(String taskId);

  void _collapseAgentRunTrace(String taskId);

  void _onPopupVisibilityChanged(bool visible);

  Future<void> _requestAuthorizeForExecution(
    List<String> requiredPermissionIds,
  );

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
