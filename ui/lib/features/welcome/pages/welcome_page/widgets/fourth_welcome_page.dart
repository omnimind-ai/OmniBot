import 'package:flutter/material.dart';
import 'package:simple_gradient_text/simple_gradient_text.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_section.dart';
import 'package:ui/features/welcome/pages/welcome_page/widgets/termux_guide_bottom_sheet.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/services/permission_service.dart';
import 'package:ui/services/device_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

class FourthWelcomePage extends StatefulWidget {
  final double screenWidth;
  final double screenHeight;
  // 向父级通知权限状态变化
  final void Function(bool isAuthorized)? onAuthorizationChanged;

  const FourthWelcomePage({
    super.key,
    required this.screenWidth,
    required this.screenHeight,
    this.onAuthorizationChanged,
  });

  @override
  State<FourthWelcomePage> createState() => _FourthWelcomePageState();
}

class _FourthWelcomePageState extends State<FourthWelcomePage>
    with WidgetsBindingObserver, AutomaticKeepAliveClientMixin {
  final ValueNotifier<bool> allAuthorized = ValueNotifier(false);
  List<PermissionData> permissions = []; // 初始为空，避免闪烁
  bool _isLoading = true; // 加载状态

  @override
  bool get wantKeepAlive => true; // 保持页面状态

  // 页面是否启用紧缩模式
  bool _isCompactMode = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    // 异步加载设备品牌并初始化权限
    _loadDeviceBrandAndPermissions();

    // 监听权限状态变化
    allAuthorized.addListener(_notifyAuthorizationChanged);
  }

  /// 加载设备品牌并初始化权限列表
  Future<void> _loadDeviceBrandAndPermissions() async {
    try {
      await refreshAppEditionCapabilitySnapshot();
      final deviceInfo = await DeviceService.getDeviceInfo();
      final brand = (deviceInfo?['brand'] as String?)?.toLowerCase() ?? 'other';

      if (mounted) {
        // 根据设备品牌加载权限列表
        final specs = PermissionService.loadSpecs(brand: brand);
        final loadedPermissions = PermissionService.specsToPermissionData(
          specs,
          context: context,
        );
        loadedPermissions.add(_buildTermuxPermissionData());

        await PermissionService.checkPermissions(loadedPermissions);
        allAuthorized.value = PermissionService.checkAllAuthorized(
          loadedPermissions,
        );

        setState(() {
          permissions = loadedPermissions;
          _isLoading = false;
          _isCompactMode = loadedPermissions.length >= 5;
        });
      }
    } catch (e) {
      if (mounted) {
        await refreshAppEditionCapabilitySnapshot();
        if (!mounted) return;
        // 失败时使用默认配置
        final specs = PermissionService.loadSpecs(brand: 'other');
        final fallbackPermissions = PermissionService.specsToPermissionData(
          specs,
          context: context,
        );
        fallbackPermissions.add(_buildTermuxPermissionData());

        await PermissionService.checkPermissions(fallbackPermissions);
        allAuthorized.value = PermissionService.checkAllAuthorized(
          fallbackPermissions,
        );

        setState(() {
          permissions = fallbackPermissions;
          _isLoading = false;
          _isCompactMode = fallbackPermissions.length >= 5;
        });
      }
    }
  }

  @override
  void dispose() {
    allAuthorized.removeListener(_notifyAuthorizationChanged);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// 通知父组件权限状态变化
  void _notifyAuthorizationChanged() {
    widget.onAuthorizationChanged?.call(allAuthorized.value);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkPermissions();
    }
  }

  void _checkAllAuthorized() {
    allAuthorized.value = PermissionService.checkAllAuthorized(permissions);
    // 权限状态已通过 listener 自动通知给父组件
  }

  Future<void> _checkPermissions() async {
    await PermissionService.checkPermissions(permissions);
    _checkAllAuthorized();
  }

  PermissionData _buildTermuxPermissionData() {
    return PermissionData(
      iconPath: 'assets/welcome/permission_installed_apps.svg',
      iconWidth: 32,
      iconHeight: 32,
      name: LegacyTextLocalizer.localize('Termux 终端能力'),
      description: LegacyTextLocalizer.localize('可选，允许 Agent 通过 Termux 执行终端命令'),
      onAuthorize: () async {
        await _showTermuxGuide();
      },
      checkAuthorization: _isTermuxGuideCompleted,
      iconInfo: LegacyTextLocalizer.localize('可选'),
      iconClick: () {
        _showTermuxGuide();
      },
    );
  }

  Future<void> _showTermuxGuide() async {
    if (!mounted) {
      return;
    }
    await TermuxGuideBottomSheet.show(
      context,
      checkTermuxInstalled: _isTermuxInstalled,
      openTermuxApp: _openTermuxApp,
      onCompleted: () async {
        await StorageService.setBool(StorageKeys.termuxPermissionGranted, true);
      },
    );
  }

  Future<bool> _isTermuxGuideCompleted() async {
    final installed = await _isTermuxInstalled();
    final completed =
        StorageService.getBool(
          StorageKeys.termuxPermissionGranted,
          defaultValue: false,
        ) ??
        false;
    return installed && completed;
  }

  Future<bool> _isTermuxInstalled() async {
    try {
      final installed =
          await spePermission.invokeMethod<bool>('isTermuxInstalled') ?? false;
      return installed;
    } catch (e) {
      return false;
    }
  }

  Future<bool> _openTermuxApp() async {
    try {
      return await spePermission.invokeMethod<bool>('openTermuxApp') ?? false;
    } catch (e) {
      return false;
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context); // 必须调用以支持 AutomaticKeepAliveClientMixin
    return Stack(
      children: [
        // 主内容
        SingleChildScrollView(
          child: Column(
            children: [
              const SizedBox(height: 85),
              // 顶部标题
              GradientText(
                LegacyTextLocalizer.localize('让小万带你执行一次任务吧！'),
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: const Color(0xFF91DCFF),
                  fontSize: 20,
                  fontFamily: 'PingFang SC',
                  fontWeight: FontWeight.w500,
                  height: 1.50,
                ),
                colors: [
                  Color(0xD991DCFF),
                  Color(0xFF00AEFF),
                  Color(0xB291DCFF),
                ],
              ),
              const SizedBox(height: 34),
              // 权限说明
              Align(
                alignment: Alignment.centerLeft,
                child: Padding(
                  padding: const EdgeInsets.only(left: 24),
                  child: _buildGuideTitle(),
                ),
              ),
              const SizedBox(height: 33),
              // 权限列表
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 24.0),
                child: _isLoading
                    ? Center(
                        child: Padding(
                          padding: const EdgeInsets.all(40.0),
                          child: CircularProgressIndicator(),
                        ),
                      )
                    : PermissionSection(
                        spacing: _isCompactMode ? 26 : 40,
                        permissions: permissions,
                        onPermissionChanged: _checkPermissions,
                      ),
              ),
              const SizedBox(height: 26),
            ],
          ),
        ),

        Positioned(
          top: 29,
          left: -19,
          child: Image.asset(
            'assets/welcome/permission_icon.png',
            width: 95.37,
            height: 89.67,
          ),
        ),
      ],
    );
  }

  Widget _buildGuideTitle() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          LegacyTextLocalizer.localize('设置权限'),
          style: TextStyle(
            color: AppColors.text,
            fontSize: 20,
            fontFamily: 'PingFang SC',
            fontWeight: FontWeight.w600,
            height: 1.5,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          LegacyTextLocalizer.localize('请放心，这些权限你随时可以收回'),
          style: TextStyle(
            color: Color(0xFF666666),
            fontSize: 14,
            fontFamily: 'PingFang SC',
            fontWeight: FontWeight.w400,
            height: 1.57,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          LegacyTextLocalizer.localize('其中 Termux 终端能力为可选项，未开启也不影响基础功能'),
          style: TextStyle(
            color: Color(0xFF999999),
            fontSize: 12,
            fontFamily: 'PingFang SC',
            fontWeight: FontWeight.w400,
            height: 1.50,
          ),
        ),
      ],
    );
  }
}
