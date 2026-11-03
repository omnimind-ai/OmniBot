import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/core/router/conversation_route_target.dart';
import 'package:ui/features/home/pages/main_navigation_page.dart';
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
import 'pages/tools/tools_page.dart';
import 'pages/mcp/remote_mcp_servers_page.dart';
import 'pages/skill_store/skill_store_page.dart';
import 'pages/plugin_market/plugin_market_page.dart';
import 'pages/plugin_market/plugin_detail_page.dart';

import 'package:ui/models/omni_plugin_item.dart';

import 'pages/termux_setting/termux_setting_page.dart';
import 'pages/scene_model_setting/scene_model_setting_page.dart';
import 'pages/model_provider_setting/model_provider_setting_page.dart';

import 'package:ui/features/welcome/pages/onboarding/onboarding_choice_page.dart';

List<GoRoute> homeRoutes = [
  // The home is the conversation list. Legacy thread targets still open chat.
  GoRoute(
    path: '/home/home',
    name: 'home/home',
    redirect: (context, state) {
      final destination = GoRouterManager.resolveHomeRoute(
        state.uri.toString(),
        extra: state.extra,
      );
      return destination == state.uri.toString() ? null : destination;
    },
    builder: (context, state) => MainNavigationPage(
      initialTab: MainNavigationTab.fromName(state.uri.queryParameters['tab']),
    ),
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
      return ChatPage(
        threadTarget: parseConversationRouteTarget(state.uri, state.extra),
      );
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
    builder: (context, state) => AgentSessionsPage(
      agentId: state.extra is String ? state.extra as String : 'codex-remote',
    ),
  ),
  GoRoute(
    path: '/home/agent_mode_setting',
    name: 'home/agent_mode_setting',
    builder: (context, state) => const AgentModeSettingPage(),
  ),
  GoRoute(
    path: '/home/agent_config/:agentId',
    name: 'home/agent_config',
    builder: (context, state) =>
        AgentConfigPage(agentId: state.pathParameters['agentId']?.trim() ?? ''),
  ),
  GoRoute(
    path: '/home/remote_codex_setting',
    name: 'home/remote_codex_setting',
    builder: (context, state) => const RemoteCodexSettingPage(),
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
    builder: (context, state) => const SettingsPage(),
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
    builder: (context, state) => const AlarmSettingPage(),
  ),

  GoRoute(
    path: '/home/tools',
    name: 'home/tools',
    builder: (context, state) => const ToolsPage(),
  ),

  GoRoute(
    path: '/home/mcp_tools',
    name: 'home/mcp_tools',
    builder: (context, state) => const RemoteMcpServersPage(),
  ),

  GoRoute(
    path: '/home/skill_store',
    name: 'home/skill_store',
    builder: (context, state) => const SkillStorePage(),
  ),

  GoRoute(
    path: '/home/plugin_market',
    name: 'home/plugin_market',
    builder: (context, state) => const PluginMarketPage(),
  ),

  GoRoute(
    path: '/home/plugin_market/:pluginId',
    name: 'home/plugin_detail',
    builder: (context, state) => PluginDetailPage(
      pluginId: state.pathParameters['pluginId']?.trim() ?? '',
      initialPlugin: state.extra is OmniPluginItem
          ? state.extra! as OmniPluginItem
          : null,
    ),
  ),

  GoRoute(
    path: '/home/workspace_memory_setting',
    name: 'home/workspace_memory_setting',
    builder: (context, state) => const WorkspaceMemorySettingPage(),
  ),

  GoRoute(
    path: '/home/background_setting',
    name: 'home/background_setting',
    builder: (context, state) => const BackgroundSettingPage(),
  ),

  GoRoute(
    path: '/home/experience_misc_setting',
    name: 'home/experience_misc_setting',
    builder: (context, state) => const ExperienceMiscSettingPage(),
  ),

  GoRoute(
    path: '/home/first_use_tutorial/setup',
    name: 'home/first_use_tutorial/setup',
    builder: (context, state) => const OnboardingChoicePage(allowExit: true),
  ),

  GoRoute(
    path: '/home/home_setting',
    name: 'home/home_setting',
    builder: (context, state) => const HomeSettingPage(),
  ),

  GoRoute(
    path: '/home/open_with_omnibot_setting',
    name: 'home/open_with_omnibot_setting',
    builder: (context, state) => const OpenWithOmnibotSettingPage(),
  ),

  GoRoute(
    path: '/home/storage_usage',
    name: 'home/storage_usage',
    builder: (context, state) => const StorageUsagePage(),
  ),

  // 模型提供商配置页
  GoRoute(
    path: '/home/model_provider_setting',
    name: 'home/model_provider_setting',
    builder: (context, state) => const ModelProviderSettingPage(),
  ),
  GoRoute(
    path: '/home/scene_model_setting',
    name: 'home/scene_model_setting',
    builder: (context, state) => const SceneModelSettingPage(),
  ),

  GoRoute(
    path: '/home/termux_setting',
    name: 'home/termux_setting',
    builder: (context, state) =>
        TermuxSettingPage(focusPackageId: state.uri.queryParameters['focus']),
  ),

  // 应用权限授权页
  GoRoute(
    path: '/home/authorize_setting',
    name: 'home/authorize_setting',
    builder: (context, state) => const AuthorizeSettingPage(),
  ),
];
