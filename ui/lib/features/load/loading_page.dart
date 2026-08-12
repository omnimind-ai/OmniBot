import 'package:flutter/material.dart';
import 'package:simple_gradient_text/simple_gradient_text.dart';
import 'package:flutter/services.dart';

/// Flutter 加载页
/// 作为应用首屏，执行实际的初始化工作
class LoadingPage extends StatefulWidget {
  /// 加载完成后的回调
  final VoidCallback? onLoadingComplete;
  
  /// 初始化函数（异步执行）
  final Future<void> Function()? onInitialize;

  const LoadingPage({
    Key? key,
    this.onLoadingComplete,
    this.onInitialize,
  }) : super(key: key);

  @override
  State<LoadingPage> createState() => _LoadingPageState();
}

class _LoadingPageState extends State<LoadingPage>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _iconPositionAnimation;
  late Animation<double> _iconSizeAnimation;
  late Animation<double> _fadeAnimation;
  late Animation<double> _bubbleScaleAnimation;
  late Animation<double> _bubbleOffsetAnimation;
  
  bool _initializationComplete = false;
  bool _animationComplete = false;

  @override
  void initState() {
    super.initState();

    // 设置系统 UI 样式（状态栏和导航栏）
    SystemChrome.setSystemUIOverlayStyle(
      const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light, // 白色图标
        systemNavigationBarColor: Colors.transparent, // 导航栏颜色
        systemNavigationBarIconBrightness: Brightness.light, // 白色导航图标
      ),
    );

    // 初始化动画控制器
    _controller = AnimationController(
      duration: const Duration(milliseconds: 800),
      vsync: this,
    );

    // 图标位置动画：从屏幕中心(0)上移到目标位置(-166)
    _iconPositionAnimation = Tween<double>(
      begin: 0.0,
      end: -166.0,
    ).animate(CurvedAnimation(
      parent: _controller,
      curve: Interval(0.0, 0.6, curve: Curves.easeOut),
    ));

    // 图标大小动画：从 288 缩小到 110
    _iconSizeAnimation = Tween<double>(
      begin: 320.0,
      end: 190.0,
    ).animate(CurvedAnimation(
      parent: _controller,
      curve: Interval(0.0, 0.6, curve: Curves.easeOut),
    ));

    // 文字和气泡淡入动画
    _fadeAnimation = Tween<double>(
      begin: 0.0,
      end: 1.0,
    ).animate(CurvedAnimation(
      parent: _controller,
      curve: Interval(0.5, 1.0, curve: Curves.easeIn),
    ));

    // 气泡缩放动画：从 0 弹出到 1（icon 到位后开始）
    _bubbleScaleAnimation = Tween<double>(
      begin: 0.0,
      end: 1.0,
    ).animate(CurvedAnimation(
      parent: _controller,
      curve: Interval(0.6, 0.85, curve: Curves.elasticOut),
    ));

    // 气泡偏移动画：从 icon 中心弹出的效果
    _bubbleOffsetAnimation = Tween<double>(
      begin: 1.0,
      end: 0.0,
    ).animate(CurvedAnimation(
      parent: _controller,
      curve: Interval(0.6, 0.85, curve: Curves.easeOut),
    ));

    // 监听动画完成
    _controller.addStatusListener((status) {
      if (status == AnimationStatus.completed) {
        _animationComplete = true;
        _checkAndComplete();
      }
    });

    // 开始动画
    _controller.forward();

    // 执行实际的初始化工作
    _performInitialization();
  }

  Future<void> _performInitialization() async {
    try {
      // 如果提供了初始化函数，执行它
      if (widget.onInitialize != null) {
        await widget.onInitialize!();
      }
      
      // 标记初始化完成
      if (mounted) {
        setState(() {
          _initializationComplete = true;
        });
        _checkAndComplete();
      }
    } catch (e) {
      // 即使出错也要完成加载，避免卡死
      if (mounted) {
        setState(() {
          _initializationComplete = true;
        });
        _checkAndComplete();
      }
    }
  }

  // 检查是否两个任务都完成，都完成则回调
  void _checkAndComplete() {
    if (_initializationComplete && _animationComplete && mounted) {
      widget.onLoadingComplete?.call();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      body: Stack(
        children: [
          // 渐变背景
          Positioned.fill(
            child: Image.asset(
              'assets/loading/bg.png',
              fit: BoxFit.cover,
            ),
          ),
          
          // 使用 AnimatedBuilder 监听动画变化
          AnimatedBuilder(
            animation: _controller,
            builder: (context, child) {
              return Container(
                alignment: Alignment.center,
                // 使用动画值控制整体位置
                padding: EdgeInsets.only(bottom: _iconPositionAnimation.value.abs()),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    // 顶部文字：淡入动画
                    Opacity(
                      opacity: _fadeAnimation.value,
                      child: GradientText(
                        '知你心意 予你陪伴',
                        style: TextStyle(
                          fontSize: 14,
                          fontFamily: 'Alibaba PuHuiTi 3.0',
                          fontWeight: FontWeight.w400,
                          height: 1.50,
                          letterSpacing: 8.40,
                        ),
                        colors: [
                          Color(0xE38BEAFF),
                          Color(0xFF00AEFF),
                          Color(0xB28BEAFF),
                        ],
                      ),
                    ),
                    SizedBox(height: 0),
                    
                    // Icon 和气泡
                    Stack(
                      clipBehavior: Clip.none,
                      children: [
                        Image.asset(
                          'assets/loading/loading_icon3x.png',
                          width: _iconSizeAnimation.value,
                          height: _iconSizeAnimation.value,
                          fit: BoxFit.cover,
                        ),
                        // Hi 气泡：从 icon 中心弹出的动画效果
                        Positioned(
                          left: 20 + 20 + (20 * _bubbleOffsetAnimation.value),
                          top: 35 + 20 + (20 * _bubbleOffsetAnimation.value),
                          child: Transform.scale(
                            scale: _bubbleScaleAnimation.value,
                            child: Image.asset(
                              'assets/loading/hi_bubble.png',
                              width: 29.5,
                              height: 29.5,
                            ),
                          ),
                        ),
                      ],
                    ),
                    SizedBox(height: 0),
                    
                    // 底部文字：淡入动画
                    Opacity(
                      opacity: _fadeAnimation.value,
                      child: GradientText(
                        '我是小万\n你的屏幕伙伴',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          fontSize: 20,
                          fontFamily: 'Alibaba PuHuiTi 3.0',
                          fontWeight: FontWeight.w400,
                          height: 1.50,
                          letterSpacing: 4,
                        ),
                        colors: [
                          Color(0xE38BEAFF),
                          Color(0xFF00AEFF),
                          Color(0xB28BEAFF),
                        ],
                      ),
                    ),
                  ],
                ),
              );
            },
          ),
        ],
      ),
    );
  }
}
