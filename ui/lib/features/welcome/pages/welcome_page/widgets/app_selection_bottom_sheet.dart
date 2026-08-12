import 'package:flutter/material.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/widgets/bottom_sheet_bg.dart';

/// 目标应用配置
class TargetApp {
  final String name;
  final String packageName;
  final String? iconPath; // 本地图标资源路径

  const TargetApp({
    required this.name,
    required this.packageName,
    this.iconPath,
  });
}

/// 首次体验应用选择底部弹窗
class AppSelectionBottomSheet extends StatefulWidget {
  /// 可选的已安装应用列表
  final List<TargetApp> availableApps;
  /// 选择应用后的回调
  final void Function(TargetApp selectedApp)? onAppSelected;

  const AppSelectionBottomSheet({
    super.key,
    required this.availableApps,
    this.onAppSelected,
  });

  @override
  State<AppSelectionBottomSheet> createState() => _AppSelectionBottomSheetState();
}

class _AppSelectionBottomSheetState extends State<AppSelectionBottomSheet> {
  int? _selectedIndex;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
  }

  void _onStartExperience() async {
    if (_selectedIndex == null || _isLoading) return;
    
    setState(() {
      _isLoading = true;
    });

    final selectedApp = widget.availableApps[_selectedIndex!];
    
    // 先关闭底部弹窗
    Navigator.of(context).pop();
    
    // 回调通知父组件
    widget.onAppSelected?.call(selectedApp);
  }

  @override
  Widget build(BuildContext context) {
    return BottomSheetBg(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          const SizedBox(height: 16),
          Stack(
            children: [
              Center(
                child: Padding(
                  padding: const EdgeInsets.only(left: 57),
                  child: Container(
                    width: 299,
                    height: 137,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(16),
                      gradient: const LinearGradient(
                        colors: [Color(0xFFEAF3FF), Color(0xFFF5F9FF)],
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                    ),
                    child: const Center(
                      child: Icon(
                        Icons.auto_awesome_rounded,
                        size: 56,
                        color: AppColors.primaryBlue,
                      ),
                    ),
                  ),
                )
              ),
              // 关闭按钮居右
              Align(
                alignment: Alignment.topRight,
                child: Padding(
                  padding: const EdgeInsets.only(right: 16),
                  child: GestureDetector(
                    onTap: () => Navigator.of(context).pop(),
                    child: const Icon(
                      Icons.close,
                      size: 16,
                      color: Colors.black,
                    ),
                  ),
                ),
              ),
            ]
          ),
          const SizedBox(height: 13),
          // 标题
          Text(
            Localizations.localeOf(context).languageCode == 'en'
                ? 'Which app would you like to try Omnibot in?'
                : '想要在哪个应用中体验小万？',
            style: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w500,
              color: AppColors.text,
              height: 1.5,
            ),
          ),
          const SizedBox(height: 16),
          // 应用列表
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: List.generate(
              widget.availableApps.length,
              (index) => Padding(
                padding: EdgeInsets.only(
                  right: index < widget.availableApps.length - 1 ? 16 : 0,
                ),
                child: _buildAppItem(index),
              )
            ),
          ),
          const SizedBox(height: 35.2),
          // 开始体验按钮
          GestureDetector(
            onTap: () => _selectedIndex != null && !_isLoading ? _onStartExperience() : null,
            child: Container(
              width: 295,
              height: 40,
              padding: const EdgeInsets.symmetric(vertical: 8),
              decoration: BoxDecoration(
                color: AppColors.buttonPrimary,
                borderRadius: BorderRadius.circular(8),
              ),
              alignment: Alignment.center,
              child: Text(
                Localizations.localeOf(context).languageCode == 'en'
                    ? 'Start'
                    : '开始体验',
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: Colors.white,
                  letterSpacing: 0.44
                ),
              ),
            ),
          ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  Widget _buildAppItem(int index) {
    final app = widget.availableApps[index];
    final isSelected = _selectedIndex == index;

    return GestureDetector(
      onTap: () {
        setState(() {
          _selectedIndex = index;
        });
      },
      child: Container(
        width: 82,
        height: 82,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(4),
          border: Border.all(
            color: isSelected ? Color(0xFF3B74FF) : Color(0xFFEEEEEE),
            width: 1,
          ),
          color: isSelected ? Color(0xFF3B74FF).withOpacity(0.1) : Colors.transparent,
        ),
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // 应用图标容器
              Container(
                width: 28,
                height: 28,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(2.84),
                ),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(2.84),
                  child: app.iconPath != null
                      ? Image.asset(
                          app.iconPath!,
                          width: 68,
                          height: 68,
                          fit: BoxFit.cover,
                        )
                      : Container(
                          width: 68,
                          height: 68,
                          color: Colors.grey[200],
                          child: const Icon(
                            Icons.apps,
                            size: 32,
                            color: Colors.grey,
                          ),
                        ),
                ),
              ),
              const SizedBox(height: 4),
              // 应用名称
              Text(
                app.name,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                  color: Colors.black,
                  height: 1.5,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 显示应用选择底部弹窗的便捷方法
Future<TargetApp?> showAppSelectionBottomSheet({
  required BuildContext context,
  required List<TargetApp> availableApps,
}) async {
  TargetApp? selectedApp;
  
  await showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(8)),
    ),
    builder: (context) {
      return AppSelectionBottomSheet(
        availableApps: availableApps,
        onAppSelected: (app) {
          selectedApp = app;
        },
      );
    },
  );
  
  return selectedApp;
}
