import 'package:flutter/material.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/welcome/pages/welcome_page/widgets/first_welcome_page.dart';
import 'package:ui/features/welcome/pages/welcome_page/widgets/fourth_welcome_page.dart';
import 'package:ui/features/welcome/pages/welcome_page/widgets/second_welcome_page.dart';
import 'package:ui/features/welcome/pages/welcome_page/widgets/third_welcome_page.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/widgets/gradient_button.dart';

class WelcomePage extends StatefulWidget {
  const WelcomePage({super.key});

  @override
  State<WelcomePage> createState() => _WelcomePageState();
}

class _WelcomePageState extends State<WelcomePage> {
  int currentIndex = 0;
  late PageController _pageController;
  int _rebuildCounter = 0;

  @override
  void initState() {
    super.initState();
    _pageController = PageController(
      initialPage: currentIndex,
      keepPage: false,
    );
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  /// 请求读取应用列表权限（未授权时拉起设置页）
  Future<void> _requestInstalledAppsPermission() async {
    try {
      await isInstalledAppsPermissionGranted();
    } catch (_) {
      // Starting the app must remain possible when the native channel is absent.
    }
  }

  /// 处理“开始体验”按钮点击
  Future<void> _handleStartExperience() async {
    await _requestInstalledAppsPermission();
    await StorageService.setBool(StorageKeys.welcomeCompleted, true);
    GoRouterManager.clearAndNavigateTo('/home/chat');
  }

  static const int _totalPages = 4;

  int get totalPages => _totalPages;
  bool get _isLastPage => currentIndex == _totalPages - 1;

  void nextPage() {
    if (!_isLastPage) {
      _pageController.animateToPage(
        currentIndex + 1,
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeInOut,
      );
      setState(() {
        currentIndex++;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final screenHeight = MediaQuery.of(context).size.height;
    final screenWidth = MediaQuery.of(context).size.width;

    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: Stack(
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 0),
              child: Column(
                children: [
                  // 主要内容区域
                  Expanded(
                    child: PageView.builder(
                      controller: _pageController,
                      itemCount: totalPages,
                      onPageChanged: (index) {
                        setState(() {
                          currentIndex = index;
                          _rebuildCounter++;
                        });
                      },
                      itemBuilder: (context, index) {
                        switch (index) {
                          case 0:
                            return FirstWelcomePage(
                              key: ValueKey('page_0_$_rebuildCounter'),
                              screenWidth: screenWidth,
                              screenHeight: screenHeight,
                            );
                          case 1:
                            return SecondWelcomePage(
                              key: ValueKey('page_1_$_rebuildCounter'),
                              screenWidth: screenWidth,
                              screenHeight: screenHeight,
                            );
                          case 2:
                            return ThirdWelcomePage(
                              key: ValueKey('page_2_$_rebuildCounter'),
                              screenWidth: screenWidth,
                              screenHeight: screenHeight,
                            );
                          case 3:
                            return FourthWelcomePage(
                              key: const ValueKey('fourth_welcome'),
                              screenWidth: screenWidth,
                              screenHeight: screenHeight,
                            );
                          default:
                            return Container(); // 默认返回空容器
                        }
                      },
                    ),
                  ),

                  Column(
                    children: [
                      if (totalPages > 1) ...[
                        const SizedBox(height: 12),
                        _buildPageIndicator(),
                        const SizedBox(height: 12),
                      ],
                      GradientButton(
                        width: 166,
                        height: 44,
                        text: _isLastPage
                            ? (Localizations.localeOf(context).languageCode ==
                                    'en'
                                ? 'Start'
                                : '开始体验')
                            : (Localizations.localeOf(context).languageCode ==
                                    'en'
                                ? 'Next'
                                : '下一步'),
                        onTap: () {
                          if (_isLastPage) {
                            _handleStartExperience();
                          } else {
                            nextPage();
                          }
                        },
                      ),
                      const SizedBox(height: 80),
                    ],
                  ),
                ],
              ),
            ),
            // 跳过按钮 - 仅在最后一页显示
            // if (_isLastPage)
            //   Positioned(
            //     top: 20,
            //     right: 16,
            //     child: GestureDetector(
            //       onTap: _handleSkip,
            //       child: Text(
            //         '跳过',
            //         style: TextStyle(
            //           color: Color(0xFF727887),
            //           fontSize: 14,
            //           fontWeight: FontWeight.w400,
            //         ),
            //       ),
            //     ),
            //   ),
          ],
        ),
      ),
    );
  }

  // 构建页面指示器
  Widget _buildPageIndicator() {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: List.generate(
        totalPages,
        (index) => Container(
          margin: const EdgeInsets.symmetric(horizontal: 4),
          width: 5,
          height: 5,
          decoration: ShapeDecoration(
            color: index == currentIndex
                ? AppColors.buttonPrimary
                : Color(0xFFD7D7D7),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(4),
            ),
            shadows: [
              BoxShadow(
                color: index == currentIndex
                    ? Color(0x0C000000)
                    : Color(0x0C000000),
                blurRadius: 4,
                offset: Offset(0, 0),
                spreadRadius: 0,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
