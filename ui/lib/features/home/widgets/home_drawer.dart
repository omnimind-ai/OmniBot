import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_slidable/flutter_slidable.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/widgets/conversation_slidable.dart';
import 'package:ui/features/home/widgets/home_drawer_search_field.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/models/scheduled_task.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/scheduled_task_storage_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_font_effect_scope.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/cache_util.dart';
import 'package:ui/utils/ui.dart';

part 'home_drawer_actions.dart';
part 'home_drawer_conversation_list.dart';
part 'home_drawer_header_footer.dart';
part 'home_drawer_image_previews.dart';
part 'home_drawer_models.dart';
part 'home_drawer_search.dart';

const String _kDrawerMemoryIconSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round" '
    'class="lucide lucide-brain-icon lucide-brain">'
    '<path d="M12 18V5"/>'
    '<path d="M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4"/>'
    '<path d="M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5"/>'
    '<path d="M17.997 5.125a4 4 0 0 1 2.526 5.77"/>'
    '<path d="M18 18a4 4 0 0 0 2-7.464"/>'
    '<path d="M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517"/>'
    '<path d="M6 18a4 4 0 0 1-2-7.464"/>'
    '<path d="M6.003 5.125a4 4 0 0 0-2.526 5.77"/>'
    '</svg>';

const String _kDrawerSkillStoreIconSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round" '
    'class="lucide lucide-container-icon lucide-container">'
    '<path d="M22 7.7c0-.6-.4-1.2-.8-1.5l-6.3-3.9a1.72 1.72 0 0 0-1.7 0l-10.3 '
    '6c-.5.2-.9.8-.9 1.4v6.6c0 .5.4 1.2.8 1.5l6.3 3.9a1.72 1.72 0 0 0 1.7 0'
    'l10.3-6c.5-.3.9-1 .9-1.5Z"/>'
    '<path d="M10 21.9V14L2.1 9.1"/>'
    '<path d="m10 14 11.9-6.9"/>'
    '<path d="M14 19.8v-8.1"/>'
    '<path d="M18 17.5V9.4"/>'
    '</svg>';

const String _kDrawerTaskHistoryIconSvg =
    '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">'
    '<rect x="0.5" y="18" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="0.5" y="12" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="6.5" y="18" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="6.5" y="12" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="6.5" y="6" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="6.5" y="0" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="12.5" y="18" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="12.5" y="12" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="12.5" y="6" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="18.5" y="18" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="18.5" y="12" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="18.5" y="6" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '<rect x="18.5" y="0" width="5" height="5" rx="1.5" fill="currentColor"/>'
    '</svg>';

/// 首页侧边栏
class HomeDrawer extends ConsumerStatefulWidget {
  const HomeDrawer({
    super.key,
    this.memoryCount,
    this.newConversationMode = ConversationMode.normal,
    this.embedded = false,
    this.closeOnNavigate = true,
    this.activeConversationId,
    this.activeConversationMode,
    this.onThreadTargetSelected,
  });

  final int? memoryCount;
  final ConversationMode newConversationMode;
  final bool embedded;
  final bool closeOnNavigate;
  final int? activeConversationId;
  final ConversationMode? activeConversationMode;
  final ValueChanged<ConversationThreadTarget>? onThreadTargetSelected;

  @override
  ConsumerState<HomeDrawer> createState() => HomeDrawerState();
}

class HomeDrawerState extends ConsumerState<HomeDrawer> {
  static const double _conversationActionIconSize = 18;
  static const Duration _searchDebounceDuration = Duration(milliseconds: 220);
  static const Duration _sectionToggleDuration = Duration(milliseconds: 260);
  static const Duration _imagePreviewStripTransitionDuration = Duration(
    milliseconds: 180,
  );
  static const BorderRadius _drawerTrailingActionRadius = BorderRadius.only(
    topRight: Radius.circular(4),
    bottomRight: Radius.circular(4),
  );
  static bool _hasConversationSnapshotCache = false;
  static List<ConversationModel> _conversationSnapshotCache =
      <ConversationModel>[];
  static Map<String, List<_ConversationImagePreview>>
  _conversationImagePreviewCacheSnapshot =
      <String, List<_ConversationImagePreview>>{};
  static Map<String, String> _conversationImagePreviewSignatureSnapshot =
      <String, String>{};
  static Map<String, Set<String>> _conversationImagePreviewFailureSnapshot =
      <String, Set<String>>{};
  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocusNode = FocusNode();
  final Map<String, _ConversationSearchIndex> _conversationSearchCache =
      <String, _ConversationSearchIndex>{};
  final Map<String, List<_ConversationImagePreview>>
  _conversationImagePreviewCache = <String, List<_ConversationImagePreview>>{};
  final Map<String, Future<List<_ConversationImagePreview>>>
  _conversationImagePreviewFutures =
      <String, Future<List<_ConversationImagePreview>>>{};
  final Map<String, String> _conversationImagePreviewSignatures =
      <String, String>{};
  final Map<String, Set<String>> _conversationImagePreviewFailures =
      <String, Set<String>>{};
  final Map<String, bool> _expandedConversationSections = <String, bool>{};
  final Set<String> _busyConversationKeys = <String>{};
  final TextEditingController _titleEditingController = TextEditingController();
  final FocusNode _titleEditingFocusNode = FocusNode();
  List<ConversationModel> _allConversations = <ConversationModel>[];
  List<_ConversationSearchResult> _searchResults =
      <_ConversationSearchResult>[];
  bool isLoadingConversations = true;
  bool _isSearching = false;
  int _conversationLoadGeneration = 0;
  int _searchGeneration = 0;
  Timer? _searchDebounceTimer;
  String? _editingThreadKey;
  List<ScheduledTask> _scheduledTasks = <ScheduledTask>[];
  StreamSubscription<Map<String, dynamic>>?
  _conversationListChangedSubscription;
  StreamSubscription<List<ScheduledTask>>? _scheduledTasksChangedSubscription;

  @override
  void initState() {
    super.initState();
    _searchController.addListener(_handleSearchQueryChanged);
    _searchFocusNode.addListener(_handleSearchFocusChanged);
    _titleEditingFocusNode.addListener(_handleTitleEditingFocusChanged);
    _restoreExpandedConversationSections();
    _restoreDrawerSnapshotCache();
    _conversationListChangedSubscription = AssistsMessageService
        .conversationListChangedStream
        .listen((_) {
          unawaited(_loadConversations());
        });
    _scheduledTasksChangedSubscription = ScheduledTaskStorageService
        .scheduledTasksChangedStream
        .listen(_handleScheduledTasksChanged);
    _loadConversations();
  }

  @override
  void dispose() {
    _searchDebounceTimer?.cancel();
    _conversationListChangedSubscription?.cancel();
    _scheduledTasksChangedSubscription?.cancel();
    _searchController
      ..removeListener(_handleSearchQueryChanged)
      ..dispose();
    _searchFocusNode
      ..removeListener(_handleSearchFocusChanged)
      ..dispose();
    _titleEditingFocusNode
      ..removeListener(_handleTitleEditingFocusChanged)
      ..dispose();
    _titleEditingController.dispose();
    super.dispose();
  }

  void _handleScheduledTasksChanged(List<ScheduledTask> tasks) {
    if (!mounted) {
      return;
    }
    setState(() {
      _scheduledTasks = List<ScheduledTask>.from(tasks);
    });
    if (_isSearchActive) {
      _scheduleConversationSearch(immediate: true);
    }
  }

  void reloadConversations() {
    _loadConversations();
  }

  @override
  Widget build(BuildContext context) {
    final backgroundColor = _drawerBackgroundColor;
    final content = AppFontEffectScope.nonChat(
      child: ColoredBox(
        color: backgroundColor,
        child: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: 16),
              Expanded(child: _buildConversationSection()),
              const SizedBox(height: 12),
              _buildFooterShortcutBar(),
              const SizedBox(height: 12),
            ],
          ),
        ),
      ),
    );
    if (widget.embedded) {
      return content;
    }
    return Drawer(
      width: MediaQuery.of(context).size.width * 0.8,
      backgroundColor: backgroundColor,
      child: content,
    );
  }
}
