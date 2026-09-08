import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/chat_input_area.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';

/// Owns all presentation state that belongs to exactly one chat page mode.
///
/// Keeping these values together prevents the page from maintaining dozens of
/// parallel maps whose keys and reset semantics can silently drift apart.
/// Agent execution facts are owned by ChatConversationRuntimeCoordinator; the
/// legacy fields below remain only for non-Agent compatibility paths and are
/// never used to admit or terminate an ACP turn.
class ChatPageModeState {
  final ChatMessageListNavigator messageListNavigator =
      ChatMessageListNavigator();
  final List<ChatInputAttachment> pendingAttachments = <ChatInputAttachment>[];
  final List<ChatMessageModel> messages = <ChatMessageModel>[];
  final Set<String> expandedAgentRunTaskIds = <String>{};
  final List<String> expandedAgentRunTaskOrder = <String>[];
  final Map<String, String> currentAiMessages = <String, String>{};

  String draftMessage = '';
  ChatIslandDisplayLayer chatIslandDisplayLayer = ChatIslandDisplayLayer.mode;
  String? lastAgentToolType;
  String runtimeChromeSignature = '';
  int runtimeMessageMutationRevision = 0;
  ChatBrowserSessionSnapshot? browserSessionSnapshot;
  bool isInputAreaVisible = true;
  bool isExecutingTask = false;
  String? editingUserMessageId;
  double toolActivityOccupiedHeight = 0;
  double slashCommandPanelOccupiedHeight = 0;
  bool slashCommandExpanded = false;
  bool toolActivityExpanded = false;
  double inputAreaHeight = 0;
  bool isAiResponding = false;
  bool isContextCompressing = false;
  bool isCheckingExecutableTask = false;
  String deepThinkingContent = '';
  bool isDeepThinking = false;
  String? currentDispatchTurnId;
  int currentThinkingStage = 1;
  int? currentConversationId;
  ConversationModel? currentConversation;
  bool hasMoreMessages = false;
  int messageOffset = 0;
  bool isLoadingMore = false;
  bool messageListInputFocused = false;

  /// Clears the same conversation-scoped fields that the page historically
  /// reset while retaining view preferences such as expansion state.
  void resetConversation() {
    messages.clear();
    inputAreaHeight = 0;
    isAiResponding = false;
    isContextCompressing = false;
    isCheckingExecutableTask = false;
    currentAiMessages.clear();
    deepThinkingContent = '';
    isDeepThinking = false;
    currentDispatchTurnId = null;
    currentThinkingStage = 1;
    currentConversationId = null;
    currentConversation = null;
    isInputAreaVisible = true;
    isExecutingTask = false;
    chatIslandDisplayLayer = ChatIslandDisplayLayer.mode;
    lastAgentToolType = null;
    runtimeChromeSignature = '';
    runtimeMessageMutationRevision = 0;
    browserSessionSnapshot = null;
    pendingAttachments.clear();
    editingUserMessageId = null;
    draftMessage = '';
  }
}
