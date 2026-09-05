// lib/widgets/chat_input_area.dart
import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/command_overlay/state/chat_composer_state_machine.dart';
import 'package:ui/services/model_vendor_catalog.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/image_preview_overlay.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/text_input_context_menu.dart';

part 'chat_input_area_composer.dart';
part 'chat_input_area_popup.dart';
part 'chat_input_actions.dart';
part 'chat_input_agent_controls.dart';
part 'chat_input_agent_menus.dart';
part 'chat_input_attachments.dart';
part 'chat_input_context_usage.dart';
part 'chat_input_flow_border.dart';

const String _kInputTerminalIconAsset = 'assets/home/input_terminal_icon.svg';
const String _kInputAttachmentIconAsset =
    'assets/home/input_attachment_cross_icon.svg';

const String _kLucideCommandSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round" '
    'class="lucide lucide-command-icon lucide-command">'
    '<path d="M15 6v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3"/>'
    '</svg>';

const String _kAgentPermissionDefaultIconAsset =
    'assets/home/chat/permission_hand.svg';
const String _kAgentPermissionReadOnlyIconAsset =
    'assets/home/chat/permission_lock.svg';
const String _kAgentPermissionAutoReviewIconAsset =
    'assets/home/chat/agent.svg';
const String _kAgentPermissionFullAccessIconAsset =
    'assets/home/chat/permission_shield_alert.svg';

enum AgentPermissionMode { readOnly, defaultMode, autoReview, fullAccess }

typedef AgentRunSettingsChanged =
    FutureOr<void> Function({String? modelId, String? reasoningEffort});

class AgentRunSettings {
  const AgentRunSettings({
    required this.modelId,
    required this.reasoningEffort,
    this.agentName = '',
    this.modelOptions = const <String>[],
    this.reasoningEffortOptions = const <String>[],
    this.isLoadingModels = false,
    this.modelListError,
  });

  final String modelId;
  final String reasoningEffort;
  final String agentName;
  final List<String> modelOptions;
  final List<String> reasoningEffortOptions;
  final bool isLoadingModels;
  final String? modelListError;
}

class ChatModelPickerSettings {
  const ChatModelPickerSettings({
    required this.modelId,
    required this.hasSelectableModels,
    required this.onOpen,
    this.onPointerDown,
    this.anchorKey,
  });

  final String modelId;
  final bool hasSelectableModels;
  final FutureOr<void> Function(BuildContext anchorContext) onOpen;
  final VoidCallback? onPointerDown;

  /// Optional key attached to the model-picker button so callers (e.g. the
  /// first-use spotlight tour) can locate the model icon precisely.
  final GlobalKey? anchorKey;
}

class ChatInputAttachment {
  final String id;
  final String name;
  final String path;
  final int? size;
  final String? mimeType;
  final bool isImage;
  final String? promptPath;
  final bool sendToModel;

  const ChatInputAttachment({
    required this.id,
    required this.name,
    required this.path,
    this.size,
    this.mimeType,
    this.isImage = false,
    this.promptPath,
    this.sendToModel = true,
  });

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'name': name,
      'path': path,
      if (size != null) 'size': size,
      if (mimeType != null) 'mimeType': mimeType,
      'isImage': isImage,
      if ((promptPath ?? '').trim().isNotEmpty)
        'promptPath': promptPath!.trim(),
      if (!sendToModel) 'sendToModel': false,
    };
  }
}

class ChatInputArea extends StatefulWidget {
  final TextEditingController controller;
  final FocusNode focusNode;
  final VoidCallback? onRequestFocus;
  final bool isProcessing;
  final VoidCallback onSendMessage;
  final VoidCallback onCancelTask;
  final ValueChanged<bool>? onPopupVisibilityChanged;
  final ValueChanged<double>? onInputHeightChanged;
  final bool? openClawEnabled;
  final ValueChanged<bool>? onToggleOpenClaw;
  final VoidCallback? onLongPressOpenClaw;
  final FutureOr<void> Function()? onTerminalTap;

  /// 是否使用毛玻璃效果（command_overlay 使用毛玻璃，chatbotsheet 使用白色+阴影）
  final bool useFrostedGlass;
  final bool useLargeComposerStyle;
  final bool useAttachmentPickerForPlus;
  final Future<void> Function()? onPickAttachment;
  final List<ChatInputAttachment> attachments;
  final bool hasExternalSendPayload;
  final bool isEditingUserMessage;
  final VoidCallback? onCancelUserMessageEditing;
  final ValueChanged<String>? onRemoveAttachment;
  final VoidCallback? onTriggerSlashCommand;
  final String? selectedModelOverrideId;
  final VoidCallback? onClearSelectedModelOverride;
  final double? contextUsageRatio;
  final String? contextUsageTooltipMessage;
  final VoidCallback? onLongPressContextUsageRing;
  final ChatModelPickerSettings? modelPickerSettings;
  final Widget? runtimeConfigButton;
  final AgentRunSettings? agentRunSettings;
  final AgentRunSettingsChanged? onAgentRunSettingsChanged;
  final FutureOr<void> Function()? onAgentRunSettingsOpened;
  final AgentPermissionMode? agentPermissionMode;
  final List<AgentPermissionMode> agentPermissionModes;
  final FutureOr<void> Function(AgentPermissionMode)?
  onAgentPermissionModeChanged;
  final bool useIndependentSendButton;

  const ChatInputArea({
    super.key,
    required this.controller,
    required this.focusNode,
    this.onRequestFocus,
    required this.isProcessing,
    required this.onSendMessage,
    required this.onCancelTask,
    this.onPopupVisibilityChanged,
    this.onInputHeightChanged,
    this.openClawEnabled,
    this.onToggleOpenClaw,
    this.onLongPressOpenClaw,
    this.onTerminalTap,
    this.useFrostedGlass = false,
    this.useLargeComposerStyle = false,
    this.useAttachmentPickerForPlus = false,
    this.onPickAttachment,
    this.attachments = const [],
    this.hasExternalSendPayload = false,
    this.isEditingUserMessage = false,
    this.onCancelUserMessageEditing,
    this.onRemoveAttachment,
    this.onTriggerSlashCommand,
    this.selectedModelOverrideId,
    this.onClearSelectedModelOverride,
    this.contextUsageRatio,
    this.contextUsageTooltipMessage,
    this.onLongPressContextUsageRing,
    this.modelPickerSettings,
    this.runtimeConfigButton,
    this.agentRunSettings,
    this.onAgentRunSettingsChanged,
    this.onAgentRunSettingsOpened,
    this.agentPermissionMode,
    this.agentPermissionModes = AgentPermissionMode.values,
    this.onAgentPermissionModeChanged,
    this.useIndependentSendButton = true,
  });

  @override
  State<ChatInputArea> createState() => ChatInputAreaState();
}

class ChatInputAreaState extends _ChatInputAreaStateBase
    with _ChatInputAreaComposerMixin, _ChatInputAreaPopupMixin {}

abstract class _ChatInputAreaStateBase extends State<ChatInputArea>
    with TickerProviderStateMixin, WidgetsBindingObserver {
  late ChatComposerStateMachine _composerStateMachine;
  late bool _lastComposerExpansionState;

  final GlobalKey _agentRunSettingsButtonKey = GlobalKey(
    debugLabel: 'agent-run-settings-button',
  );
  final GlobalKey _modelPickerButtonKey = GlobalKey(
    debugLabel: 'chat-model-picker-button',
  );
  final GlobalKey _agentPermissionButtonKey = GlobalKey(
    debugLabel: 'agent-permission-button',
  );
  OverlayGlassPopupHandle<_AgentRunSettingsMenuAction>?
  _agentRunSettingsMenuHandle;
  OverlayGlassPopupHandle<AgentPermissionMode>? _agentPermissionMenuHandle;

  final ScrollController _textFieldScrollController = ScrollController();

  bool get isPopupVisible =>
      _composerStateMachine.value.isPopupOpen(ChatComposerPopup.legacyActions);

  void _hideLegacyPopup({bool alwaysNotify = false}) {
    final wasVisible = isPopupVisible;
    _composerStateMachine.popupClosed(ChatComposerPopup.legacyActions);
    if (wasVisible || alwaysNotify) {
      widget.onPopupVisibilityChanged?.call(false);
    }
  }

  double _lastReportedInputHeight = 44;
  bool _inputHeightReportScheduled = false;
  late AnimationController _composerFlowController;
  late AnimationController _modelPickerSpinController;

  late Widget _terminalSvg;
  late Widget _sendSvg;
  late Widget _pauseSvg;
  late Widget _addSvg;
  late Widget _commandSvg;

  // 按钮动画相关
  final Duration _buttonAnimationDuration = const Duration(milliseconds: 200);
  final Curve _buttonAnimationCurve = Curves.easeInOut;

  @override
  void initState() {
    super.initState();
    _composerStateMachine = ChatComposerStateMachine(
      hasText: widget.controller.text.trim().isNotEmpty,
      hasFocus: widget.focusNode.hasFocus,
    );
    _lastComposerExpansionState = _composerStateMachine.value.expandsTextField;
    _composerStateMachine.addListener(_onComposerStateChanged);
    widget.controller.addListener(_onTextChanged);
    widget.focusNode.addListener(_onFocusChanged);
    WidgetsBinding.instance.addObserver(this);
    _terminalSvg = const SizedBox.shrink();
    _sendSvg = const SizedBox.shrink();
    _pauseSvg = const SizedBox.shrink();
    _addSvg = const SizedBox.shrink();
    _commandSvg = const SizedBox.shrink();
    _composerFlowController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 8000),
    )..repeat();
    _modelPickerSpinController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 520),
    );
    _reportInputHeightAfterBuild();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncKeyboardPhaseFromView();
    final palette = context.omniPalette;
    _terminalSvg = SvgPicture.asset(
      _kInputTerminalIconAsset,
      colorFilter: ColorFilter.mode(palette.accentPrimary, BlendMode.srcIn),
    );
    _sendSvg = context.isDarkTheme
        ? _buildDarkActionButtonIcon(
            size: 24,
            backgroundColor: Color.lerp(
              palette.surfaceElevated,
              palette.accentPrimary,
              0.34,
            )!,
            foreground: Icon(
              Icons.arrow_upward_rounded,
              size: 15,
              color: palette.pageBackground,
            ),
          )
        : _buildComposerIconAsset(
            'assets/home/send_icon.svg',
            width: 24,
            height: 24,
          );
    _pauseSvg = context.isDarkTheme
        ? _buildDarkActionButtonIcon(
            size: 20,
            backgroundColor: Color.lerp(
              palette.surfaceElevated,
              palette.accentPrimary,
              0.34,
            )!,
            foreground: Container(
              width: 7,
              height: 7,
              decoration: BoxDecoration(
                color: palette.pageBackground,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          )
        : _buildComposerIconAsset(
            'assets/home/input_pause_icon.svg',
            width: 20,
            height: 20,
          );
    _addSvg = context.isDarkTheme
        ? _buildComposerIconAsset(
            _kInputAttachmentIconAsset,
            width: 20,
            height: 20,
            color: palette.accentPrimary,
          )
        : _buildComposerIconAsset(
            _kInputAttachmentIconAsset,
            width: 20,
            height: 20,
            color: palette.accentPrimary,
          );
    _commandSvg = context.isDarkTheme
        ? SvgPicture.string(
            _kLucideCommandSvg,
            width: 20,
            height: 20,
            colorFilter: ColorFilter.mode(
              palette.accentPrimary,
              BlendMode.srcIn,
            ),
          )
        : SvgPicture.string(
            _kLucideCommandSvg,
            width: 20,
            height: 20,
            colorFilter: ColorFilter.mode(
              palette.accentPrimary,
              BlendMode.srcIn,
            ),
          );
  }

  Widget _buildComposerIconAsset(
    String assetPath, {
    required double width,
    required double height,
    Color? color,
  }) {
    return SvgPicture.asset(
      assetPath,
      width: width,
      height: height,
      colorFilter: color == null
          ? null
          : ColorFilter.mode(color, BlendMode.srcIn),
    );
  }

  Widget _buildDarkActionButtonIcon({
    required double size,
    required Widget foreground,
    required Color backgroundColor,
    Color? borderColor,
  }) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: backgroundColor,
        shape: BoxShape.circle,
        border: borderColor == null ? null : Border.all(color: borderColor),
      ),
      alignment: Alignment.center,
      child: foreground,
    );
  }

  Future<void> openTerminalFromInput() async {
    try {
      final handler = widget.onTerminalTap;
      if (handler != null) {
        await handler();
      } else {
        await openNativeTerminal();
      }
    } catch (error) {
      if (!mounted) {
        return;
      }
      final messenger = ScaffoldMessenger.maybeOf(context);
      messenger?.showSnackBar(SnackBar(content: Text('打开终端失败: $error')));
    }
  }

  void _onTextChanged() {
    _composerStateMachine.textChanged(widget.controller.text.trim().isNotEmpty);
  }

  void _onFocusChanged() {
    _composerStateMachine.focusChanged(widget.focusNode.hasFocus);
  }

  void _onComposerStateChanged() {
    final expandsTextField = _composerStateMachine.value.expandsTextField;
    if (expandsTextField == _lastComposerExpansionState) {
      return;
    }
    _lastComposerExpansionState = expandsTextField;
    _reportInputHeightAfterBuild();
  }

  @override
  void didChangeMetrics() {
    super.didChangeMetrics();
    _syncKeyboardPhaseFromView();
  }

  void _syncKeyboardPhaseFromView() {
    if (!mounted) return;
    final view = View.of(context);
    final bottomInset = view.viewInsets.bottom / view.devicePixelRatio;
    _composerStateMachine.keyboardInsetChanged(bottomInset);
  }

  @override
  void didUpdateWidget(covariant ChatInputArea oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.attachments != widget.attachments ||
        oldWidget.useLargeComposerStyle != widget.useLargeComposerStyle ||
        oldWidget.useFrostedGlass != widget.useFrostedGlass ||
        oldWidget.selectedModelOverrideId != widget.selectedModelOverrideId ||
        oldWidget.modelPickerSettings != widget.modelPickerSettings) {
      _reportInputHeightAfterBuild();
    }
  }

  @override
  void dispose() {
    unawaited(_agentRunSettingsMenuHandle?.dismiss());
    _agentRunSettingsMenuHandle = null;
    unawaited(_agentPermissionMenuHandle?.dismiss());
    _agentPermissionMenuHandle = null;
    WidgetsBinding.instance.removeObserver(this);
    _textFieldScrollController.dispose();
    _composerStateMachine
      ..removeListener(_onComposerStateChanged)
      ..dispose();
    _composerFlowController.dispose();
    _modelPickerSpinController.dispose();
    widget.controller.removeListener(_onTextChanged);
    widget.focusNode.removeListener(_onFocusChanged);
    super.dispose();
  }

  void _reportInputHeightAfterBuild() {
    if (_inputHeightReportScheduled) return;
    _inputHeightReportScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _inputHeightReportScheduled = false;
      if (!mounted) return;
      final renderBox = findActiveRenderObject(context) as RenderBox?;
      if (renderBox == null || !renderBox.hasSize) return;
      final height = renderBox.size.height;
      if ((height - _lastReportedInputHeight).abs() < 0.5) return;
      _lastReportedInputHeight = height;
      widget.onInputHeightChanged?.call(height);
    });
  }
}
