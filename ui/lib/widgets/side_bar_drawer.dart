import 'dart:async';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/utils/popup_menu_anchor_position.dart';
import '../features/home/pages/edit_profile/edit_profile_page.dart';
import '../features/memory/pages/memory_center/memory_center_page.dart';
import '../features/home/widgets/conversation_mode_badge.dart';
import '../models/conversation_model.dart';
import '../models/conversation_thread_target.dart';
import '../services/assists_core_service.dart';
import '../services/conversation_service.dart';
import '../services/conversation_history_service.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

class SidebarDrawer extends StatefulWidget {
  const SidebarDrawer({super.key});

  @override
  State<SidebarDrawer> createState() => _SidebarDrawerState();
}

class _SidebarDrawerState extends State<SidebarDrawer> {
  int avatarIndex = 0;
  String nickname = LegacyTextLocalizer.isEnglish
      ? "Usernamexxxxx"
      : "用户名xxxxx";
  final List<String> presetAvatars = [
    'assets/avatar/default_avatar1.png',
    'assets/avatar/default_avatar2.png',
    'assets/avatar/default_avatar3.png',
    'assets/avatar/default_avatar4.png',
    'assets/avatar/default_avatar5.png',
    'assets/avatar/default_avatar6.png',
  ];

  List<ConversationModel> conversations = [];
  bool isLoadingConversations = true;
  StreamSubscription<Map<String, dynamic>>?
  _conversationListChangedSubscription;

  @override
  void initState() {
    super.initState();
    _conversationListChangedSubscription = AssistsMessageService
        .conversationListChangedStream
        .listen((_) {
          unawaited(_loadConversations());
        });
    _loadUserData();
    _loadConversations();
  }

  @override
  void dispose() {
    _conversationListChangedSubscription?.cancel();
    super.dispose();
  }

  Future<void> _loadUserData() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      avatarIndex = prefs.getInt('avatarIndex') ?? 0;
      nickname =
          prefs.getString('nickname') ??
          (LegacyTextLocalizer.isEnglish ? "Usernamexxxxx" : "用户名xxxxx");
    });
  }

  Future<void> _loadConversations() async {
    setState(() {
      isLoadingConversations = true;
    });

    final loadedConversations = await ConversationService.getAllConversations();

    setState(() {
      conversations = loadedConversations;
      isLoadingConversations = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    return Drawer(
      child: Container(
        color: Colors.grey[100], // 浅灰色背景
        child: Column(
          children: [
            // 用户信息区域
            _buildUserHeader(),

            // 新建任务按钮
            _buildNewTaskButton(isEnglish: isEnglish),

            // 功能菜单
            _buildMenuItems(isEnglish: isEnglish),

            // 历史记录区域
            Expanded(child: _buildHistorySection(isEnglish: isEnglish)),

            // 底部设置按钮
            _buildBottomSettings(),
          ],
        ),
      ),
    );
  }

  // 用户头像和用户名区域
  Widget _buildUserHeader() {
    return Container(
      padding: EdgeInsets.fromLTRB(20, 60, 20, 20),
      child: GestureDetector(
        onTap: () {
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (context) => EditProfilePage(
                initialAvatarIndex: avatarIndex,
                initialNickname: nickname,
              ),
            ),
          ).then((result) {
            if (result != null && result['avatarIndex'] != null) {
              setState(() {
                avatarIndex = result['avatarIndex'];
                nickname = result['nickname'] ?? nickname;
              });
            }
          });
        },
        child: Row(
          children: [
            CircleAvatar(
              radius: 25,
              backgroundColor: Colors.grey[400],
              backgroundImage: AssetImage(presetAvatars[avatarIndex]),
              onBackgroundImageError: (_, __) {},
              child: null,
            ),
            SizedBox(width: 15),
            Text(
              nickname,
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.w500,
                color: Colors.black87,
              ),
            ),
          ],
        ),
      ),
    );
  }

  // 新建任务按钮
  Widget _buildNewTaskButton({required bool isEnglish}) {
    return Container(
      margin: EdgeInsets.symmetric(horizontal: 20, vertical: 10),
      child: Container(
        width: double.infinity,
        padding: EdgeInsets.symmetric(vertical: 15),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(12),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withOpacity(0.05),
              blurRadius: 10,
              offset: Offset(0, 2),
            ),
          ],
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.edit_outlined, color: Colors.grey[600], size: 20),
            SizedBox(width: 8),
            Text(
              isEnglish ? "New task" : "新建任务",
              style: TextStyle(
                fontSize: 16,
                color: Colors.grey[700],
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }

  // 功能菜单项
  Widget _buildMenuItems({required bool isEnglish}) {
    return Container(
      margin: EdgeInsets.symmetric(horizontal: 20, vertical: 5),
      child: Container(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(12),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withOpacity(0.05),
              blurRadius: 10,
              offset: Offset(0, 2),
            ),
          ],
        ),
        child: Column(
          children: [
            _buildMenuItem(
              icon: Icons.memory_outlined,
              title: isEnglish ? "Memory center" : "记忆中心",
              onTap: () {
                Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (context) => const MemoryCenterPage(),
                  ),
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  // 单个菜单项
  Widget _buildMenuItem({
    required IconData icon,
    required String title,
    required VoidCallback onTap,
  }) {
    return ListTile(
      contentPadding: EdgeInsets.symmetric(horizontal: 20, vertical: 5),
      leading: Icon(icon, color: Colors.grey[600], size: 24),
      title: Text(
        title,
        style: TextStyle(
          fontSize: 16,
          color: Colors.black87,
          fontWeight: FontWeight.w500,
        ),
      ),
      trailing: Icon(Icons.chevron_right, color: Colors.grey[400]),
      onTap: onTap,
    );
  }

  // 历史记录区域
  Widget _buildHistorySection({required bool isEnglish}) {
    return Container(
      margin: EdgeInsets.only(top: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: EdgeInsets.symmetric(horizontal: 20),
            child: Text(
              isEnglish ? "History" : "历史记录",
              style: TextStyle(
                fontSize: 14,
                color: Colors.grey[600],
                fontWeight: FontWeight.w500,
              ),
            ),
          ),
          SizedBox(height: 15),
          Expanded(
            child: isLoadingConversations
                ? Center(
                    child: CircularProgressIndicator(
                      valueColor: AlwaysStoppedAnimation<Color>(
                        Colors.grey[400]!,
                      ),
                    ),
                  )
                : conversations.isEmpty
                ? Center(
                    child: Text(
                      isEnglish ? "No conversations yet" : "暂无历史对话",
                      style: TextStyle(fontSize: 14, color: Colors.grey[500]),
                    ),
                  )
                : ListView.builder(
                    padding: EdgeInsets.symmetric(horizontal: 20),
                    itemCount: conversations.length,
                    itemBuilder: (context, index) {
                      final conversation = conversations[index];
                      return _buildHistoryItem(
                        conversation,
                        isEnglish: isEnglish,
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }

  // 历史记录单项
  Widget _buildHistoryItem(
    ConversationModel conversation, {
    required bool isEnglish,
  }) {
    return GestureDetector(
      onTap: () {
        // 点击对话，跳转到全屏聊天页面
        Navigator.pop(context); // 关闭侧栏
        GoRouterManager.push(
          '/home/chat',
          extra: ConversationThreadTarget.existing(
            conversationId: conversation.id,
            mode: conversation.mode,
          ),
        );
      },
      onLongPressStart: (LongPressStartDetails details) {
        showMenu(
          context: context,
          position: PopupMenuAnchorPosition.fromGlobalOffset(
            context: context,
            globalOffset: details.globalPosition,
            estimatedMenuHeight: 120,
          ),
          color: Colors.white,
          items: [
            PopupMenuItem(
              value: 'rename',
              child: Row(
                children: [
                  Icon(Icons.edit, size: 18, color: Colors.grey[600]),
                  SizedBox(width: 8),
                  Text(isEnglish ? 'Rename' : '重命名'),
                ],
              ),
            ),
            PopupMenuItem(
              value: 'copy',
              child: Row(
                children: [
                  Icon(Icons.content_copy, size: 18, color: Colors.grey[600]),
                  SizedBox(width: 8),
                  Text(isEnglish ? 'Copy conversation' : '复制对话'),
                ],
              ),
            ),
            PopupMenuItem(
              value: 'export',
              child: Row(
                children: [
                  Icon(Icons.ios_share, size: 18, color: Colors.grey[600]),
                  SizedBox(width: 8),
                  Text(isEnglish ? 'Export' : '导出'),
                ],
              ),
            ),
            PopupMenuItem(
              value: 'delete',
              child: Row(
                children: [
                  Icon(Icons.delete, size: 18, color: Colors.grey[600]),
                  SizedBox(width: 8),
                  Text(isEnglish ? 'Delete' : '删除'),
                ],
              ),
            ),
          ],
        ).then((value) {
          if (value == 'rename') {
            _renameConversation(conversation);
          } else if (value == 'copy') {
            _copyConversation(conversation);
          } else if (value == 'export') {
            _exportConversation(conversation);
          } else if (value == 'delete') {
            _deleteConversation(conversation);
          }
        });
      },
      child: Container(
        margin: EdgeInsets.only(bottom: 20),
        child: Row(
          children: [
            Expanded(
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      conversation.title,
                      style: TextStyle(
                        color: Colors.black87,
                        fontWeight: FontWeight.w400,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  SizedBox(width: 8),
                  ConversationModeBadge(mode: conversation.mode, compact: true),
                ],
              ),
            ),
            SizedBox(width: 30),
            Text(
              conversation.timeDisplay,
              style: TextStyle(fontSize: 14, color: Colors.grey[500]),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _renameConversation(ConversationModel conversation) async {
    final newTitle = await showDialog<String>(
      context: context,
      useRootNavigator: false,
      builder: (_) =>
          _RenameConversationDialog(initialTitle: conversation.title),
    );

    if (!mounted) return;
    final normalizedTitle = newTitle?.trim();
    if (normalizedTitle == null || normalizedTitle.isEmpty) {
      return;
    }

    final success = await ConversationService.updateConversationTitle(
      conversationId: conversation.id,
      newTitle: normalizedTitle,
      mode: conversation.mode,
    );
    if (!mounted || !success) return;

    setState(() {
      final index = conversations.indexWhere(
        (c) => c.threadKey == conversation.threadKey,
      );
      if (index != -1) {
        conversations[index] = conversation.copyWith(title: normalizedTitle);
      }
    });
  }

  Future<void> _exportConversation(ConversationModel conversation) async {
    final exported = await ConversationHistoryService.exportConversation(
      conversation.id,
      mode: conversation.mode,
    );
    if (!mounted) return;
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          exported
              ? (isEnglish ? 'Conversation exported' : '对话已导出')
              : (isEnglish ? 'Export failed' : '导出失败'),
        ),
      ),
    );
  }

  Future<void> _copyConversation(ConversationModel conversation) async {
    final copied = await ConversationHistoryService.copyConversation(
      conversation.id,
      mode: conversation.mode,
    );
    if (!mounted) return;
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          copied
              ? (isEnglish ? 'Conversation copied' : '已复制对话')
              : (isEnglish ? 'Copy failed' : '复制失败'),
        ),
      ),
    );
  }

  void _deleteConversation(ConversationModel conversation) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) {
        final isEnglish = Localizations.localeOf(context).languageCode == 'en';
        return AlertDialog(
          title: Text(isEnglish ? "Delete conversation" : "删除对话"),
          content: Text(
            isEnglish
                ? "Are you sure you want to delete this conversation?"
                : "确定要删除这个对话吗？",
          ),
          actions: [
            TextButton(
              onPressed: () {
                Navigator.pop(context, false);
              },
              child: Text(isEnglish ? "Cancel" : "取消"),
            ),
            TextButton(
              onPressed: () {
                Navigator.pop(context, true);
              },
              child: Text(isEnglish ? "Delete" : "确定"),
            ),
          ],
        );
      },
    );

    if (confirmed == true) {
      final success = await ConversationService.deleteConversation(
        conversation.id,
        mode: conversation.mode,
      );
      if (success) {
        setState(() {
          conversations.removeWhere(
            (c) => c.threadKey == conversation.threadKey,
          );
        });
      }
    }
  }

  // 底部设置按钮
  Widget _buildBottomSettings() {
    return Container(
      padding: EdgeInsets.all(20),
      child: Align(
        alignment: Alignment.centerRight,
        child: IconButton(
          icon: Icon(
            Icons.settings_outlined,
            color: Colors.grey[600],
            size: 24,
          ),
          onPressed: () {
            // 处理设置点击
          },
        ),
      ),
    );
  }
}

class _RenameConversationDialog extends StatefulWidget {
  const _RenameConversationDialog({required this.initialTitle});

  final String initialTitle;

  @override
  State<_RenameConversationDialog> createState() =>
      _RenameConversationDialogState();
}

class _RenameConversationDialogState extends State<_RenameConversationDialog> {
  late final TextEditingController _controller;
  final FocusNode _focusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.initialTitle);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _focusNode.requestFocus();
      }
    });
  }

  @override
  void dispose() {
    _focusNode.dispose();
    _controller.dispose();
    super.dispose();
  }

  void _close([String? value]) {
    _focusNode.unfocus();
    Navigator.of(context).pop(value);
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        _close();
      },
      child: AlertDialog(
        title: Text(
          LegacyTextLocalizer.isEnglish ? "Rename conversation" : "重命名对话",
        ),
        content: TextField(
          controller: _controller,
          focusNode: _focusNode,
          decoration: InputDecoration(
            hintText: LegacyTextLocalizer.isEnglish
                ? "Enter new name"
                : "输入新的名称",
          ),
          onSubmitted: (_) => _close(_controller.text.trim()),
        ),
        actions: [
          TextButton(
            onPressed: () => _close(),
            child: Text(LegacyTextLocalizer.isEnglish ? "Cancel" : "取消"),
          ),
          TextButton(
            onPressed: () => _close(_controller.text.trim()),
            child: Text(LegacyTextLocalizer.isEnglish ? "OK" : "确定"),
          ),
        ],
      ),
    );
  }
}
