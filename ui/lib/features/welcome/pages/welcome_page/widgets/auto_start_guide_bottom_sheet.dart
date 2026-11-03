import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:flutter_svg/svg.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/widgets/gradient_button.dart';

class _GuidePageData {
  final String title;
  final String subtitle;
  final String imagePath;
  final TextSpan? titleSpan;

  const _GuidePageData({
    required this.title,
    required this.subtitle,
    required this.imagePath,
    this.titleSpan,
  });
}

/// 自启动权限引导底部弹窗
/// 包含三页滑动引导：1. 搜索应用启动管理 2. 开启所有开关 3. 完成设置
class AutoStartGuideBottomSheet extends StatefulWidget {
  /// 点击"我已了解 去设置"的回调
  final VoidCallback? onGoToSettings;

  /// 点击"已完成"的回调
  final VoidCallback? onCompleted;

  const AutoStartGuideBottomSheet({
    super.key,
    this.onGoToSettings,
    this.onCompleted,
  });

  /// 显示自启动权限引导弹窗
  static Future<bool?> show(
    BuildContext context, {
    VoidCallback? onGoToSettings,
    VoidCallback? onCompleted,
  }) {
    return showOmniGlassBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      isDismissible: false,
      enableDrag: false,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withOpacity(0.5),
      builder: (context) => AutoStartGuideBottomSheet(
        onGoToSettings: onGoToSettings,
        onCompleted: onCompleted,
      ),
    );
  }

  @override
  State<AutoStartGuideBottomSheet> createState() =>
      _AutoStartGuideBottomSheetState();
}

class _AutoStartGuideBottomSheetState extends State<AutoStartGuideBottomSheet>
    with WidgetsBindingObserver {
  final PageController _pageController = PageController(viewportFraction: 0.8);
  static const List<_GuidePageData> _guidePages = [
    _GuidePageData(
      title: '打开"设置",搜索"应用启动管理"',
      subtitle: '开启该权限后，可保证后台任务和浮窗交互持续运行，不会被系统识别为异常应用，您可通过搜索找到该功能',
      imagePath: 'assets/welcome/auto_start_guide_1.png',
    ),
    _GuidePageData(
      title: '',
      subtitle: '关闭小万右侧的"自动管理开关"(状态如图),此时会出现手动管理弹框。',
      imagePath: 'assets/welcome/auto_start_guide_2.png',
      titleSpan: TextSpan(
        children: [
          TextSpan(
            text: '确保" 小万 " 的启动管理为',
            style: TextStyle(
              color: Colors.black,
              fontSize: 14,
              fontWeight: FontWeight.w600,
              height: 1.57,
            ),
          ),
          TextSpan(
            text: '关闭状态',
            style: TextStyle(
              color: Color(0xFFFF0000),
              fontSize: 14,
              fontWeight: FontWeight.w600,
              height: 1.57,
            ),
          ),
        ],
      ),
    ),
    _GuidePageData(
      title: '开启所有开关',
      subtitle: '需要把所有的启动开关和后台活动开关都开启,小万就能随时陪着你啦!',
      imagePath: 'assets/welcome/auto_start_guide_3.png',
    ),
  ];
  int _currentPage = 0;
  bool _showCompletedButton = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pageController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 当应用从后台恢复时，显示"已完成"按钮
    if (state == AppLifecycleState.resumed) {
      if (mounted) {
        setState(() {
          _showCompletedButton = true;
        });
      }
    }
  }

  void _onCompleted() {
    widget.onCompleted?.call();
    Navigator.of(context).pop(true);
  }

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.of(context).padding.bottom;

    return OmniGlassSheet(
      backgroundColor: AppColors.background,
      borderRadius: const BorderRadius.vertical(top: Radius.circular(22)),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 标题栏
          _buildHeader(),
          const SizedBox(height: 16),
          // 内容区域
          Column(
            children: [
              _buildGuideTextSection(),
              const SizedBox(height: 8),
              SizedBox(
                height: 200,
                child: PageView.builder(
                  controller: _pageController,
                  itemCount: _guidePages.length,
                  onPageChanged: (index) {
                    setState(() {
                      _currentPage = index;
                    });
                  },
                  itemBuilder: (context, index) {
                    return _buildGuideImagePage(_guidePages[index].imagePath);
                  },
                ),
              ),
            ],
          ),
          _buildSlideHint(),
          const SizedBox(height: 24),
          _buildBottomButton(),
          // 安全区域
          SizedBox(height: bottomInset + 12),
        ],
      ),
    );
  }

  /// 构建标题栏
  Widget _buildHeader() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 24, 16, 0),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          const Text(
            '应用启动管理',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w500,
              color: AppColors.text,
              letterSpacing: 0.44,
            ),
          ),
          GestureDetector(
            onTap: () => Navigator.of(context).pop(),
            child: Text(
              '稍后设置',
              style: TextStyle(
                color: Colors.black.withOpacity(0.50),
                fontSize: 12,
                fontWeight: FontWeight.w400,
                height: 1.50,
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// 文案区域根据当前页切换
  Widget _buildGuideTextSection() {
    final page = _guidePages[_currentPage];

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 标题
          page.titleSpan != null
              ? Text.rich(page.titleSpan!)
              : Text(
                  page.title,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: Colors.black,
                    height: 1.57,
                  ),
                ),
          const SizedBox(height: 4),
          // 副标题
          SizedBox(
            height: 36,
            child: Text(
              page.subtitle,
              // maxLines: 2,
              style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w400,
                color: Color(0xFF666666),
                height: 1.5,
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// 图片区域仅保留在 PageView 中
  Widget _buildGuideImagePage(String imagePath) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: Container(
        width: double.infinity,
        decoration: BoxDecoration(
          color: const Color(0xFFEFEFEF),
          borderRadius: BorderRadius.circular(16),
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(16),
          child: Image.asset(
            imagePath,
            fit: BoxFit.contain,
            errorBuilder: (context, error, stackTrace) {
              return const Center(
                child: Icon(
                  Icons.image_not_supported,
                  size: 48,
                  color: Color(0xFF999999),
                ),
              );
            },
          ),
        ),
      ),
    );
  }

  /// 构建滑动提示
  Widget _buildSlideHint() {
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Center(
        child: _currentPage < _guidePages.length - 1
            ? Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text(
                    '滑动查看下一页',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w400,
                      color: Color(0xFF3B74FF),
                      height: 1.5,
                    ),
                  ),
                  const SizedBox(width: 4),
                  SvgPicture.asset(
                    'assets/welcome/next_page.svg',
                    width: 16,
                    height: 11,
                    color: const Color(0xFF3B74FF),
                  ),
                ],
              )
            : const SizedBox(height: 18),
      ),
    );
  }

  /// 构建底部按钮
  Widget _buildBottomButton() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Row(
        children: [
          Expanded(
            child: GestureDetector(
              onTap: widget.onGoToSettings,
              child: Container(
                height: 40,
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: AppColors.buttonPrimary, width: 1),
                ),
                alignment: Alignment.center,
                child: const Text(
                  '去设置',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w400,
                    color: AppColors.buttonPrimary,
                    letterSpacing: 0.39,
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Opacity(
              opacity: _showCompletedButton ? 1 : 0.5,
              child: GradientButton(
                text: '我已开启',
                width: double.infinity,
                height: 40,
                borderRadius: 8,
                onTap: () {
                  if (_showCompletedButton) {
                    _onCompleted();
                  }
                },
                textStyle: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w500,
                  color: Colors.white,
                  letterSpacing: 0.39,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
