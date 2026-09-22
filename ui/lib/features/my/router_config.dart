import 'package:ui/features/my/pages/about/user_guide_page.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/my/pages/my/my_page.dart';
import 'package:ui/features/my/pages/theme_color/theme_color_page.dart';
import 'package:ui/features/my/pages/about/about_page.dart';
import 'package:ui/features/my/pages/about/ai_request_logs_page.dart';
import 'package:ui/features/my/pages/about/runtime_logs_page.dart';
import 'package:ui/features/my/pages/account/account_page.dart';

/// My模块路由配置
List<GoRoute> myRoutes = [
  // Native and Flutter About share the same documentation page.
  GoRoute(
    path: '/my/about/user-guide',
    name: 'my/about/user-guide',
    builder: (context, state) => const UserGuidePage(),
  ),
  // My模块首页
  GoRoute(
    path: '/my/my_page',
    name: 'my/my_page',
    builder: (context, state) => const MyPage(),
  ),

  // 主题颜色页面
  GoRoute(
    path: '/my/theme',
    name: 'my/theme',
    builder: (context, state) => const ThemeColorPage(),
  ),

  GoRoute(
    path: '/my/account',
    name: 'my/account',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'my/account',
      child: const AccountPage(),
    ),
  ),

  // 关于我们页面
  GoRoute(
    path: '/my/about',
    name: 'my/about',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'my/about',
      child: const AboutPage(),
    ),
  ),

  GoRoute(
    path: '/my/about/request-logs',
    name: 'my/about/request-logs',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'my/about/request-logs',
      child: const AiRequestLogsPage(),
    ),
  ),

  GoRoute(
    path: '/my/about/runtime-logs',
    name: 'my/about/runtime-logs',
    pageBuilder: (context, state) => GoRouterManager.buildActivitySlidePage(
      key: state.pageKey,
      name: 'my/about/runtime-logs',
      child: const RuntimeLogsPage(),
    ),
  ),
];
