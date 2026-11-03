part of 'chat_widgets.dart';

/// 聊天输入区域包装器
class ChatInputWrapper extends StatelessWidget {
  final GlobalKey<ChatInputAreaState> inputAreaKey;
  final TextEditingController controller;
  final FocusNode focusNode;
  final VoidCallback? onRequestFocus;
  final bool isProcessing;
  final String? unavailableMessage;
  final Future<void> Function({String? text}) onSendMessage;
  final VoidCallback onCancelTask;
  final void Function(bool) onPopupVisibilityChanged;
  final FutureOr<void> Function()? onTerminalTap;
  final bool? openClawEnabled;
  final ValueChanged<bool>? onToggleOpenClaw;
  final VoidCallback? onLongPressOpenClaw;
  final bool useLargeComposerStyle;
  final bool useAttachmentPickerForPlus;
  final Future<void> Function()? onPickAttachment;
  final Future<void> Function()? onPickImages;
  final List<ChatInputAttachment> attachments;
  final bool hasExternalSendPayload;
  final bool isEditingUserMessage;
  final VoidCallback? onCancelUserMessageEditing;
  final ValueChanged<String>? onRemoveAttachment;
  final VoidCallback? onTriggerSlashCommand;
  final Widget? topBanner;
  final String? selectedModelOverrideId;
  final VoidCallback? onClearSelectedModelOverride;
  final double? contextUsageRatio;
  final String? contextUsageTooltipMessage;
  final VoidCallback? onLongPressContextUsageRing;
  final ValueChanged<double>? onInputHeightChanged;
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
  final bool translucent;

  const ChatInputWrapper({
    super.key,
    required this.inputAreaKey,
    required this.controller,
    required this.focusNode,
    this.onRequestFocus,
    required this.isProcessing,
    this.unavailableMessage,
    required this.onSendMessage,
    required this.onCancelTask,
    required this.onPopupVisibilityChanged,
    this.onTerminalTap,
    this.openClawEnabled,
    this.onToggleOpenClaw,
    this.onLongPressOpenClaw,
    this.useLargeComposerStyle = false,
    this.useAttachmentPickerForPlus = false,
    this.onPickAttachment,
    this.onPickImages,
    this.attachments = const [],
    this.hasExternalSendPayload = false,
    this.isEditingUserMessage = false,
    this.onCancelUserMessageEditing,
    this.onRemoveAttachment,
    this.onTriggerSlashCommand,
    this.topBanner,
    this.selectedModelOverrideId,
    this.onClearSelectedModelOverride,
    this.contextUsageRatio,
    this.contextUsageTooltipMessage,
    this.onLongPressContextUsageRing,
    this.onInputHeightChanged,
    this.modelPickerSettings,
    this.runtimeConfigButton,
    this.agentRunSettings,
    this.onAgentRunSettingsChanged,
    this.onAgentRunSettingsOpened,
    this.agentPermissionMode,
    this.agentPermissionModes = AgentPermissionMode.values,
    this.onAgentPermissionModeChanged,
    this.useIndependentSendButton = true,
    this.translucent = false,
  });

  @override
  Widget build(BuildContext context) {
    if (unavailableMessage != null) {
      return Padding(
        key: const Key('chat-agent-unavailable'),
        padding: const EdgeInsets.all(18),
        child: Text(
          unavailableMessage!,
          style: Theme.of(context).textTheme.bodyMedium,
        ),
      );
    }
    return Container(
      padding: const EdgeInsets.fromLTRB(24, 8, 24, 8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (topBanner != null) ...[topBanner!, const SizedBox(height: 8)],
          ChatInputArea(
            key: inputAreaKey,
            controller: controller,
            focusNode: focusNode,
            onRequestFocus: onRequestFocus,
            isProcessing: isProcessing,
            onSendMessage: onSendMessage,
            onCancelTask: onCancelTask,
            onPopupVisibilityChanged: onPopupVisibilityChanged,
            onTerminalTap: onTerminalTap,
            openClawEnabled: openClawEnabled,
            onToggleOpenClaw: onToggleOpenClaw,
            onLongPressOpenClaw: onLongPressOpenClaw,
            useFrostedGlass: translucent,
            useLargeComposerStyle: useLargeComposerStyle,
            minimalComposer: true,
            useAttachmentPickerForPlus: useAttachmentPickerForPlus,
            onPickAttachment: onPickAttachment,
            onPickImages: onPickImages,
            attachments: attachments,
            hasExternalSendPayload: hasExternalSendPayload,
            isEditingUserMessage: isEditingUserMessage,
            onCancelUserMessageEditing: onCancelUserMessageEditing,
            onRemoveAttachment: onRemoveAttachment,
            onTriggerSlashCommand: onTriggerSlashCommand,
            selectedModelOverrideId: selectedModelOverrideId,
            onClearSelectedModelOverride: onClearSelectedModelOverride,
            contextUsageRatio: contextUsageRatio,
            contextUsageTooltipMessage: contextUsageTooltipMessage,
            onLongPressContextUsageRing: onLongPressContextUsageRing,
            modelPickerSettings: modelPickerSettings,
            runtimeConfigButton: runtimeConfigButton,
            agentRunSettings: agentRunSettings,
            onAgentRunSettingsChanged: onAgentRunSettingsChanged,
            onAgentRunSettingsOpened: onAgentRunSettingsOpened,
            agentPermissionMode: agentPermissionMode,
            agentPermissionModes: agentPermissionModes,
            onAgentPermissionModeChanged: onAgentPermissionModeChanged,
            onInputHeightChanged: onInputHeightChanged,
            useIndependentSendButton: useIndependentSendButton,
          ),
        ],
      ),
    );
  }
}
