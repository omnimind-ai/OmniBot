import 'package:flutter/material.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/edit_profile/edit_profile_page.dart';
import 'package:ui/features/my/pages/my/widgets/profile_section.dart';
import 'package:ui/features/my/pages/my/widgets/setting_section.dart';
import 'package:ui/features/my/pages/my/widgets/setting_tile.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/utils/cache_util.dart';
import 'package:ui/utils/ui.dart';

class MyPage extends StatefulWidget {
  const MyPage({super.key});

  @override
  State<MyPage> createState() => MyPageState();
}

class MyPageState extends State<MyPage> {
  bool vibrationEnabled = true;
  int avatarIndex = 0;
  String nickname = '';
  final List<String> presetAvatars = [
    'assets/avatar/default_avatar1.png',
    'assets/avatar/default_avatar2.png',
    'assets/avatar/default_avatar3.png',
    'assets/avatar/default_avatar4.png',
    'assets/avatar/default_avatar5.png',
    'assets/avatar/default_avatar6.png',
  ];

  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _loadVibrationState();
    _loadUserData();
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _loadVibrationState() async {
    try {
      final enabled = await CacheUtil.getBool("app_vibrate");
      setState(() {
        vibrationEnabled = enabled;
      });

    } catch (e) {
    }
  }

  Future<void> _loadUserData() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final savedNickname = prefs.getString('nickname') ?? '';
      if (!mounted) return;
      setState(() {
        avatarIndex = prefs.getInt('avatarIndex') ?? 0;
        nickname = savedNickname;
      });
    } catch (e) {
    }
  }

  @override
  Widget build(BuildContext context) {
    final bg = Colors.white;
    final displayName = nickname.trim();

    return Scaffold(
      backgroundColor: bg,
      body: SafeArea(
        top: false,
        bottom: false,
        child: Stack(
          children: [
            Positioned.fill(
              top: 0,
              left: 0,
              right: 0,
              child: Container(
                decoration: BoxDecoration(
                  image: DecorationImage(
                    image: AssetImage('assets/my/bg.png'),
                    fit: BoxFit.fitWidth,
                    alignment: Alignment.topCenter,
                  ),
                ),
              ),
            ),
            Container(
              margin: const EdgeInsets.only(top: 151),
              padding: const EdgeInsets.only(top: 87),
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  colors: [Color(0x99FFFFFF), Color(0xFFFFFFFF)],
                ),
                borderRadius: BorderRadius.only(
                  topLeft: Radius.circular(8),
                  topRight: Radius.circular(8),
                ),
              ),
              child: Column(
                children: [
                  Expanded(
                    child: ListView(
                      controller: _scrollController,
                      padding: edgeToEdgeScrollPadding(
                        context,
                        const EdgeInsets.fromLTRB(24, 0, 24, 40),
                      ),
                      children: [
                        // 第一组： 震动
                        SettingSection(
                          children: [
                            SettingTile(
                              title: '账号与 AI 服务',
                              onTap: () => GoRouterManager.push('/my/account'),
                            ),
                            SettingTile(
                              title: '震动反馈',
                              trailing: FlutterSwitch(
                                width: 44.8,
                                height: 25.0,
                                toggleSize: 15.3,
                                padding: 4.8,
                                activeColor: AppColors.primaryBlue,
                                inactiveColor: AppColors.fillStandardSecondary,
                                value: vibrationEnabled,
                                borderRadius: 28.75,
                                onToggle: (val) async {
                                  await CacheUtil.cacheBool("app_vibrate", val);
                                  setState(() {
                                    vibrationEnabled = val;
                                  });
                                },
                              ),
                              showChevron: false,
                              onTap: () {},
                            ),
                            SettingTile(
                              title: 'MCP 工具',
                              onTap: () =>
                                  GoRouterManager.push('/home/mcp_tools'),
                            ),
                          ],
                        ),
                        const SizedBox(height: 8),

                        // 第二组：反馈 / 关于
                        SettingSection(
                          children: [
                            SettingTile(
                              title: '意见反馈',
                              onTap: () {
                                GoRouterManager.push('/my/feedback');
                              },
                            ),
                            SettingTile(
                              title: '关于小万',
                              onTap: () {
                                GoRouterManager.push('/my/about');
                              },
                            ),
                          ],
                        ),
                        const SizedBox(height: 32),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            // 头像组件 - 位于背景和内容区域交界处
            Positioned(
              top: 70,
              left: 0,
              right: 0,
              child: Center(
                child: ProfileSection(
                  username: displayName.isEmpty ? '用户名' : displayName,
                  avatarUrl: presetAvatars[avatarIndex],
                  onTap: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (context) => EditProfilePage(
                          initialAvatarIndex: avatarIndex,
                          initialNickname: displayName,
                        ),
                      ),
                    ).then((result) {
                      if (result != null) {
                        setState(() {
                          avatarIndex = result['avatarIndex'] ?? avatarIndex;
                          nickname = (result['nickname'] ?? nickname)
                              .toString();
                        });
                      }
                    });
                  },
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
