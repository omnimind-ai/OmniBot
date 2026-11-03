import 'package:go_router/go_router.dart';
import 'package:ui/features/my/pages/my/my_page.dart';
import 'package:ui/features/my/pages/theme_color/theme_color_page.dart';
import 'package:ui/features/my/pages/about/about_page.dart';
import 'package:ui/features/my/pages/about/ai_request_logs_page.dart';
import 'package:ui/features/my/pages/about/runtime_logs_page.dart';
import 'package:ui/features/my/pages/account/account_page.dart';
import 'package:ui/features/my/pages/feedback/feedback_page.dart';

/// My模块路由配置
List<GoRoute> myRoutes = [
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
    builder: (context, state) => const AccountPage(),
  ),

  GoRoute(
    path: '/my/feedback',
    name: 'my/feedback',
    builder: (context, state) => const FeedbackPage(),
  ),

  // 关于我们页面
  GoRoute(
    path: '/my/about',
    name: 'my/about',
    builder: (context, state) => const AboutPage(),
  ),

  GoRoute(
    path: '/my/about/request-logs',
    name: 'my/about/request-logs',
    builder: (context, state) => const AiRequestLogsPage(),
  ),

  GoRoute(
    path: '/my/about/runtime-logs',
    name: 'my/about/runtime-logs',
    builder: (context, state) => const RuntimeLogsPage(),
  ),
];
