import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/permission_guide/permission_guide_data.dart';
import 'package:ui/features/home/pages/permission_guide/permission_guide_routes.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';

class PermissionGuidePage extends StatefulWidget {
  final String? initialBrand;

  const PermissionGuidePage({super.key, this.initialBrand});

  @override
  State<PermissionGuidePage> createState() => _PermissionGuidePageState();
}

class _PermissionGuidePageState extends State<PermissionGuidePage> {
  late String _selectedBrand;
  bool _loadingBrand = false;

  @override
  void initState() {
    super.initState();
    _selectedBrand = PermissionGuideRepository.normalizeBrandId(
      widget.initialBrand,
    );
    if (widget.initialBrand == null || _selectedBrand == 'other') {
      _loadDetectedBrand();
    }
  }

  Future<void> _loadDetectedBrand() async {
    setState(() {
      _loadingBrand = true;
    });
    final brand = await PermissionGuideRepository.detectCurrentBrand();
    if (!mounted) {
      return;
    }
    setState(() {
      _selectedBrand = brand;
      _loadingBrand = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final brandInfo = PermissionGuideRepository.brandInfo(_selectedBrand);
    final topics = PermissionGuideRepository.topicsForBrand(_selectedBrand);

    return Scaffold(
      backgroundColor: context.omniPalette.pageBackground,
      appBar: const CommonAppBar(title: '权限开通指引', primary: true),
      body: SafeArea(
        top: false,
        bottom: false,
        child: OmniContentWidth(
          child: ListView(
            padding: edgeToEdgeScrollPadding(
              context,
              const EdgeInsets.fromLTRB(16, 8, 16, 24),
            ),
            children: [
              _buildHeroCard(brandInfo),
              const SizedBox(height: 16),
              _buildBrandSelector(),
              const SizedBox(height: 20),
              Text(
                '根据当前品牌推荐的权限指南',
                style: TextStyle(
                  color: AppColors.text70,
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                ),
              ),
              const SizedBox(height: 8),
              for (final topic in topics) ...[
                _buildTopicCard(topic, brandInfo.id),
                const SizedBox(height: 10),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildHeroCard(PermissionGuideBrandInfo brandInfo) {
    return Container(
      padding: const EdgeInsets.fromLTRB(18, 18, 18, 16),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFFF5FAFF), Color(0xFFE8F1FF)],
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.04),
            blurRadius: 18,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 5,
                  ),
                  decoration: BoxDecoration(
                    color: Colors.white.withOpacity(0.9),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    _loadingBrand ? '识别机型中...' : '当前机型：${brandInfo.name}',
                    style: const TextStyle(
                      color: AppColors.buttonPrimary,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                const Text(
                  '必要权限开启指南',
                  style: TextStyle(
                    color: AppColors.text,
                    fontSize: 24,
                    fontWeight: FontWeight.w700,
                    height: 1.2,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  '根据不同品牌展示更接近真实系统路径的操作步骤，减少你在系统设置里盲找。',
                  style: TextStyle(
                    color: AppColors.text70,
                    fontSize: 13,
                    height: 1.5,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  '示例系统：${brandInfo.osLabel}',
                  style: const TextStyle(color: AppColors.text70, fontSize: 12),
                ),
              ],
            ),
          ),
          const SizedBox(width: 16),
          Image.asset(
            'assets/welcome/permission_icon.png',
            width: 72,
            height: 72,
          ),
        ],
      ),
    );
  }

  Widget _buildBrandSelector() {
    final brands = PermissionGuideRepository.selectableBrands();
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        for (final brand in brands)
          ChoiceChip(
            label: Text(brand.name),
            selected: brand.id == _selectedBrand,
            selectedColor: const Color(0xFFE8F2FF),
            backgroundColor: Colors.white,
            side: BorderSide(
              color: brand.id == _selectedBrand
                  ? AppColors.buttonPrimary
                  : Colors.transparent,
            ),
            labelStyle: TextStyle(
              color: brand.id == _selectedBrand
                  ? AppColors.buttonPrimary
                  : AppColors.text,
              fontSize: 12,
              fontWeight: FontWeight.w600,
            ),
            onSelected: (_) {
              setState(() {
                _selectedBrand = brand.id;
              });
            },
          ),
      ],
    );
  }

  Widget _buildTopicCard(PermissionGuideTopicInfo topic, String brandId) {
    return Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(18),
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: () {
          GoRouterManager.push(
            PermissionGuideRoutes.detail(brand: brandId, type: topic.id),
          );
        },
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: const Color(0xFFF6FAFF),
                  borderRadius: BorderRadius.circular(12),
                ),
                alignment: Alignment.center,
                child: SvgPicture.asset(topic.iconPath, width: 24, height: 24),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      topic.title,
                      style: const TextStyle(
                        color: AppColors.text,
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      topic.subtitle,
                      style: TextStyle(
                        color: AppColors.text70,
                        fontSize: 12,
                        height: 1.5,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              const Icon(
                LucideIcons.chevronRight,
                size: 16,
                color: AppColors.text70,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
