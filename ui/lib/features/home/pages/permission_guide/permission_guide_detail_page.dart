import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/features/home/pages/permission_guide/permission_guide_data.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class PermissionGuideDetailPage extends StatefulWidget {
  final String type;
  final String? initialBrand;

  const PermissionGuideDetailPage({
    super.key,
    required this.type,
    this.initialBrand,
  });

  @override
  State<PermissionGuideDetailPage> createState() =>
      _PermissionGuideDetailPageState();
}

class _PermissionGuideDetailPageState extends State<PermissionGuideDetailPage> {
  late final PermissionGuideTopicInfo? _topic;
  late String _selectedBrand;

  @override
  void initState() {
    super.initState();
    _topic = PermissionGuideRepository.topicById(widget.type);
    _selectedBrand = PermissionGuideRepository.normalizeBrandId(
      widget.initialBrand,
    );
    if (_topic != null && !_topic.supportsBrand(_selectedBrand)) {
      _selectedBrand = _firstSupportedBrand(_topic);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_topic == null) {
      return Scaffold(
        backgroundColor: context.omniPalette.pageBackground,
        appBar: const CommonAppBar(title: '权限指南', primary: true),
        body: const SafeArea(
          top: false,
          child: Center(
            child: Text(
              '未找到对应的权限指南',
              style: TextStyle(
                color: AppColors.text,
                fontSize: 16,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ),
      );
    }

    final topic = _topic;
    final brandInfo = PermissionGuideRepository.brandInfo(_selectedBrand);
    final steps = topic.stepsFor(_selectedBrand);
    final brands = PermissionGuideRepository.selectableBrands()
        .where((brand) => topic.supportsBrand(brand.id))
        .toList(growable: false);

    return Scaffold(
      backgroundColor: context.omniPalette.pageBackground,
      appBar: CommonAppBar(title: topic.title, primary: true),
      body: SafeArea(
        top: false,
        bottom: false,
        child: ListView(
          padding: edgeToEdgeScrollPadding(
            context,
            const EdgeInsets.fromLTRB(16, 8, 16, 24),
          ),
          children: [
            _buildSummaryCard(topic, brandInfo),
            const SizedBox(height: 16),
            Wrap(
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
            ),
            const SizedBox(height: 16),
            for (var i = 0; i < steps.length; i++) ...[
              _buildStepCard(i + 1, steps[i]),
              const SizedBox(height: 12),
            ],
          ],
        ),
      ),
      bottomNavigationBar: SafeArea(
        minimum: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: FilledButton(
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.buttonPrimary,
            minimumSize: const Size.fromHeight(48),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
          ),
          onPressed: _openSystemSettings,
          child: const Text(
            '我已了解，去设置',
            style: TextStyle(
              color: Colors.white,
              fontSize: 15,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSummaryCard(
    PermissionGuideTopicInfo topic,
    PermissionGuideBrandInfo brandInfo,
  ) {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 46,
            height: 46,
            decoration: BoxDecoration(
              color: const Color(0xFFF6FAFF),
              borderRadius: BorderRadius.circular(14),
            ),
            alignment: Alignment.center,
            child: SvgPicture.asset(topic.iconPath, width: 26, height: 26),
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
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  topic.subtitle,
                  style: TextStyle(
                    color: AppColors.text70,
                    fontSize: 13,
                    height: 1.5,
                  ),
                ),
                const SizedBox(height: 10),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    color: const Color(0xFFF4F7FC),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    '机型参考：${brandInfo.name} · ${brandInfo.osLabel}',
                    style: const TextStyle(
                      color: AppColors.text,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStepCard(int index, PermissionGuideStep step) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 28,
                height: 28,
                decoration: BoxDecoration(
                  color: AppColors.buttonPrimary,
                  borderRadius: BorderRadius.circular(999),
                ),
                alignment: Alignment.center,
                child: Text(
                  index.toString().padLeft(2, '0'),
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  step.title,
                  style: const TextStyle(
                    color: AppColors.text,
                    fontSize: 17,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            step.description,
            style: TextStyle(
              color: AppColors.text70,
              fontSize: 13,
              height: 1.6,
            ),
          ),
          if (step.imageAssetPath != null) ...[
            const SizedBox(height: 14),
            ClipRRect(
              borderRadius: BorderRadius.circular(14),
              child: Image.asset(
                step.imageAssetPath!,
                fit: BoxFit.cover,
                errorBuilder: (context, error, stackTrace) {
                  return Container(
                    height: 160,
                    color: const Color(0xFFF4F7FC),
                    alignment: Alignment.center,
                    child: const Text(
                      '示意图暂不可用',
                      style: TextStyle(color: AppColors.text70, fontSize: 12),
                    ),
                  );
                },
              ),
            ),
          ],
        ],
      ),
    );
  }

  String _firstSupportedBrand(PermissionGuideTopicInfo topic) {
    final supported = topic.supportedBrands;
    if (supported == null || supported.isEmpty) {
      return 'other';
    }
    for (final brand in PermissionGuideRepository.selectableBrands()) {
      if (supported.contains(brand.id)) {
        return brand.id;
      }
    }
    return 'other';
  }

  Future<void> _openSystemSettings() async {
    final topic = _topic;
    if (topic == null) {
      return;
    }
    try {
      await spePermission.invokeMethod(topic.openMethod);
    } catch (_) {
      if (!mounted) {
        return;
      }
      showToast('打开系统设置失败', type: ToastType.error);
    }
  }
}
