import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/features/home/pages/alarm_setting/alarm_setting_page.dart';
import 'package:ui/features/home/pages/authorize_setting/authorize_setting_page.dart';
import 'package:ui/features/home/pages/agent/remote_codex_setting_page.dart';
import 'package:ui/features/home/pages/agent/agent_sessions_page.dart';
import 'package:ui/features/home/pages/agent/agent_mode_setting_page.dart';
import 'package:ui/features/home/pages/agent/agent_config_page.dart';
import 'package:ui/features/home/pages/chat_history/chat_history_page.dart';
import 'package:ui/features/home/pages/permission_guide/permission_guide_detail_page.dart';
import 'package:ui/features/home/pages/permission_guide/permission_guide_page.dart';
import 'pages/authorize/authorize_page.dart';
import 'pages/authorize/authorize_page_args.dart';
import 'pages/chat/chat_page.dart';
import 'pages/command_overlay/command_overlay.dart';
import 'pages/edit_profile/edit_profile_page.dart';
import 'pages/settings/workspace_memory_setting_page.dart';
import 'pages/settings/background_setting_page.dart';
import 'pages/settings/experience_misc_setting_page.dart';
import 'pages/settings/home_setting_page.dart';
import 'pages/settings/open_with_omnibot_setting_page.dart';
import 'pages/settings/storage_usage_page.dart';
import 'pages/omnibot_workspace/omnibot_artifact_preview_page.dart';
import 'pages/omnibot_workspace/omnibot_workspace_page.dart';
import 'pages/webview/webview_page.dart';
import 'pages/settings/settings_page.dart';
import 'pages/mcp/remote_mcp_servers_page.dart';
import 'pages/skill_store/skill_store_page.dart';
import 'pages/plugin_market/plugin_market_page.dart';
import 'pages/plugin_market/plugin_detail_page.dart';
import 'package:ui/models/omni_plugin_item.dart';
import 'pages/termux_setting/termux_setting_page.dart';
import 'pages/scene_model_setting/scene_model_setting_page.dart';
import 'pages/model_provider_setting/model_provider_setting_page.dart';
import 'package:ui/features/welcome/pages/onboarding/onboarding_choice_page.dart';

/// Home模块路由配置
const String kNativeRouteFlag = '__from_native__';

ConversationMode _parseConversationMode(String? rawValue) {
  return ConversationMode.fromStorageValue(rawValue);
}

ConversationThreadTarget? _parseChatThreadTarget(GoRouterState state) {
  final queryConversationId =
      state.uri.queryParameters['conversationId']?.trim() ?? '';
  final queryMode = _parseConversationMode(state.uri.queryParameters['mode']);
  final queryRequestKey = state.uri.queryParameters['requestKey']?.trim();
  final queryAgentId = state.uri.queryParameters['agentId']?.trim();
  if (queryConversationId.isNotEmpty) {
    if (queryConversationId == 'new' || queryConversationId == '__new__') {
      return ConversationThreadTarget.newConversation(
        mode: queryMode,
        agentId: queryAgentId?.isEmpty == true ? null : queryAgentId,
        fromNativeRoute: true,
        requestKey: queryRequestKey?.isEmpty == true ? null : queryRequestKey,
      );
    }
    final conversationId = int.tryParse(queryConversationId);
    if (conversationId != null) {
      return ConversationThreadTarget.existing(
        conversationId: conversationId,
        mode: queryMode,
        agentId: queryAgentId?.isEmpty == true ? null : queryAgentId,
        fromNativeRoute: true,
        requestKey: queryRequestKey?.isEmpty == true ? null : queryRequestKey,
      );
    }
  }

  final extra = state.extra;
  if (extra is ConversationThreadTarget) {
    return extra;
  }

  final argsFromExtra = extra as List<String>? ?? const <String>[];
  if (argsFromExtra.isEmpty) {
    return null;
  }

  final first = argsFromExtra.first.trim();
  final requestKey = argsFromExtra
      .skip(1)
      .map((item) => item.trim())
      .firstWhere(
        (item) =>
            item.isNotEmpty &&
            item != kNativeRouteFlag &&
            !item.startsWith('mode=') &&
            ConversationMode.values.every(
              (mode) => mode.storageValue != item.toLowerCase(),
            ),
        orElse: () => '',
      );
  final modeRaw = argsFromExtra
      .skip(1)
      .map((item) => item.trim())
      .firstWhere(
        (item) =>
            item.startsWith('mode=') ||
            ConversationMode.values.any(
              (mode) => mode.storageValue == item.toLowerCase(),
            ),
        orElse: () => '',
      );
  final mode = modeRaw.startsWith('mode=')
      ? _parseConversationMode(modeRaw.substring(5))
      : _parseConversationMode(modeRaw);
  final fromNativeRoute = argsFromExtra.contains(kNativeRouteFlag);

  if (first == 'new' || first == '__new__') {
    return ConversationThreadTarget.newConversation(
      mode: mode,
      fromNativeRoute: fromNativeRoute,
      requestKey: requestKey.isEmpty ? null : requestKey,
    );
  }

  final conversationId = int.tryParse(first);
  if (conversationId == null) {
    return null;
  }
  return ConversationThreadTarget.existing(
    conversationId: conversationId,
    mode: mode,
    fromNativeRoute: fromNativeRoute,
    requestKey: requestKey.isEmpty ? null : requestKey,
  );
}

List<GoRoute> homeRoutes = [
  // 兼容旧首页路由，统一落到聊天页
  GoRoute(
    path: '/home/home',
    name: 'home/home',
    builder: (context, state) {
      return ChatPage(threadTarget: _parseChatThreadTarget(state));
    },
  ),
  GoRoute(
    path: '/home/blank_page',
    name: 'home/blank_page',
    builder: (context, state) => const SizedBox.shrink(),
  ),

  // 聊天页
  GoRoute(
    path: '/home/chat',
    name: 'home/chat',
    builder: (context, state) {
      return ChatPage(threadTarget: _parseChatThreadTarget(state));
    },
  ),

  // 聊天归档页（保留旧路径兼容）
  GoRoute(
    path: '/home/chat_history',
    name: 'home/chat_history',
    builder: (context, state) => const ChatHistoryPage(archivedOnly: true),
  ),

  GoRoute(
    path: '/home/agent_sessions',
    name: 'home/agent_sessions',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/agent_sessions',
      child: const AgentSessionsPage(),
    ),
  ),
  GoRoute(
    path: '/home/agent_mode_setting',
    name: 'home/agent_mode_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/agent_mode_setting',
      child: const AgentModeSettingPage(),
    ),
  ),
  GoRoute(
    path: '/home/agent_config/:agentId',
    name: 'home/agent_config',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/agent_config',
      child: AgentConfigPage(
        agentId: state.pathParameters['agentId']?.trim() ?? '',
      ),
    ),
  ),
  GoRoute(
    path: '/home/remote_codex_setting',
    name: 'home/remote_codex_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/remote_codex_setting',
      child: const RemoteCodexSettingPage(),
    ),
  ),
  GoRoute(
    path: '/home/archived_conversations',
    name: 'home/archived_conversations',
    builder: (context, state) => const ChatHistoryPage(archivedOnly: true),
  ),

  // 输入框悬浮窗
  GoRoute(
    path: '/home/command_overlay',
    name: 'home/command_overlay',
    builder: (context, state) {
      final scene = state.uri.queryParameters['scene'];
      return CommandOverlay(scene: scene);
    },
  ),

  GoRoute(
    path: '/home/omnibot_artifact_preview',
    name: 'home/omnibot_artifact_preview',
    builder: (context, state) {
      final extra = state.extra as Map<String, dynamic>? ?? const {};
      return OmnibotArtifactPreviewPage(
        path: (extra['path'] ?? '').toString(),
        uri: extra['uri']?.toString(),
        title: (extra['title'] ?? '文件预览').toString(),
        previewKind: (extra['previewKind'] ?? 'file').toString(),
        mimeType: (extra['mimeType'] ?? 'application/octet-stream').toString(),
        shellPath: extra['shellPath']?.toString(),
        exists: extra['exists'] != false,
        startInEditMode: extra['startInEditMode'] == true,
        showPathBar: false,
      );
    },
  ),

  GoRoute(
    path: '/home/omnibot_workspace',
    name: 'home/omnibot_workspace',
    builder: (context, state) {
      final extra = state.extra as Map<String, dynamic>? ?? const {};
      return OmnibotWorkspacePage(
        workspacePath: (extra['workspacePath'] ?? '').toString(),
        workspaceId: extra['workspaceId']?.toString(),
        workspaceShellPath: extra['workspaceShellPath']?.toString(),
      );
    },
  ),

  // 授权页
  GoRoute(
    path: '/home/authorize',
    name: 'home/authorize',
    builder: (context, state) =>
        AuthorizePage(args: state.extra as AuthorizePageArgs?),
  ),

  // 编辑资料页
  GoRoute(
    path: '/home/edit_profile',
    name: 'home/edit_profile',
    builder: (context, state) {
      final extra = state.extra as Map<String, dynamic>?;
      return EditProfilePage(
        initialAvatarIndex: extra?['initialAvatarIndex'] as int?,
        initialNickname: extra?['initialNickname'] as String?,
      );
    },
  ),

  // Webview通用页面
  GoRoute(
    path: '/webview/webview_page',
    name: 'webview/webview_page',
    builder: (context, state) {
      final params = state.extra as Map<String, dynamic>?;
      return WebViewPage(
        url: params?['url'] ?? '',
        title: params?['title'] ?? '',
        showAppBar: params?['showAppBar'] ?? true,
        enableJavaScript: params?['enableJavaScript'] ?? true,
        enableZoom: params?['enableZoom'] ?? true,
        showRefreshButton: params?['showRefreshButton'] ?? false,
        appBarBackClosesPage: params?['appBarBackClosesPage'] ?? false,
      );
    },
  ),

  // 设置页
  GoRoute(
    path: '/home/settings',
    name: 'home/settings',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/settings',
      child: const SettingsPage(),
    ),
  ),

  GoRoute(
    path: '/home/permission_guide',
    name: 'home/permission_guide',
    builder: (context, state) =>
        PermissionGuidePage(initialBrand: state.uri.queryParameters['brand']),
  ),

  GoRoute(
    path: '/home/permission_guide/detail',
    name: 'home/permission_guide/detail',
    builder: (context, state) => PermissionGuideDetailPage(
      type: state.uri.queryParameters['type'] ?? '',
      initialBrand: state.uri.queryParameters['brand'],
    ),
  ),

  // 闹钟设置页
  GoRoute(
    path: '/home/alarm_setting',
    name: 'home/alarm_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/alarm_setting',
      child: const AlarmSettingPage(),
    ),
  ),

  GoRoute(
    path: '/home/mcp_tools',
    name: 'home/mcp_tools',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/mcp_tools',
      child: const RemoteMcpServersPage(),
    ),
  ),

  GoRoute(
    path: '/home/skill_store',
    name: 'home/skill_store',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/skill_store',
      child: const SkillStorePage(),
    ),
  ),

  GoRoute(
    path: '/home/plugin_market',
    name: 'home/plugin_market',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/plugin_market',
      child: const PluginMarketPage(),
    ),
  ),

  GoRoute(
    path: '/home/plugin_market/:pluginId',
    name: 'home/plugin_detail',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/plugin_detail',
      child: PluginDetailPage(
        pluginId: state.pathParameters['pluginId']?.trim() ?? '',
        initialPlugin: state.extra is OmniPluginItem
            ? state.extra! as OmniPluginItem
            : null,
      ),
    ),
  ),

  GoRoute(
    path: '/home/workspace_memory_setting',
    name: 'home/workspace_memory_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/workspace_memory_setting',
      child: const WorkspaceMemorySettingPage(),
    ),
  ),

  GoRoute(
    path: '/home/background_setting',
    name: 'home/background_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/background_setting',
      child: const BackgroundSettingPage(),
    ),
  ),

  GoRoute(
    path: '/home/experience_misc_setting',
    name: 'home/experience_misc_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/experience_misc_setting',
      child: const ExperienceMiscSettingPage(),
    ),
  ),

  GoRoute(
    path: '/home/first_use_tutorial/setup',
    name: 'home/first_use_tutorial/setup',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/first_use_tutorial/setup',
      child: const OnboardingChoicePage(allowExit: true),
    ),
  ),

  GoRoute(
    path: '/home/home_setting',
    name: 'home/home_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/home_setting',
      child: const HomeSettingPage(),
    ),
  ),

  GoRoute(
    path: '/home/open_with_omnibot_setting',
    name: 'home/open_with_omnibot_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/open_with_omnibot_setting',
      child: const OpenWithOmnibotSettingPage(),
    ),
  ),

  GoRoute(
    path: '/home/storage_usage',
    name: 'home/storage_usage',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/storage_usage',
      child: const StorageUsagePage(),
    ),
  ),

  // 模型提供商配置页
  GoRoute(
    path: '/home/model_provider_setting',
    name: 'home/model_provider_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/model_provider_setting',
      child: const ModelProviderSettingPage(),
    ),
  ),
  GoRoute(
    path: '/home/scene_model_setting',
    name: 'home/scene_model_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/scene_model_setting',
      child: const SceneModelSettingPage(),
    ),
  ),

  GoRoute(
    path: '/home/termux_setting',
    name: 'home/termux_setting',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'home/termux_setting',
      child: TermuxSettingPage(
        focusPackageId: state.uri.queryParameters['focus'],
      ),
    ),
  ),

  // 应用权限授权页
  GoRoute(
    path: '/home/authorize_setting',
    name: 'home/authorize_setting',
    builder: (context, state) => const AuthorizeSettingPage(),
  ),
];
