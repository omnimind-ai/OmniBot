import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:ui/features/memory/models/mem0_memory_item.dart';
import 'package:ui/features/memory/pages/memory_center/widgets/tag_chip.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/theme/theme_context.dart';

class Mem0MemorySection extends StatefulWidget {
  final bool isLoading;
  final bool isMutating;
  final Mem0MemorySnapshot snapshot;
  final Future<void> Function()? onRefresh;
  final VoidCallback? onAddTap;
  final ValueChanged<Mem0MemoryItem>? onMemoryTap;

  const Mem0MemorySection({
    super.key,
    required this.isLoading,
    this.isMutating = false,
    required this.snapshot,
    this.onRefresh,
    this.onAddTap,
    this.onMemoryTap,
  });

  @override
  State<Mem0MemorySection> createState() => _Mem0MemorySectionState();
}

class _Mem0MemorySectionState extends State<Mem0MemorySection> {
  static const String _allCategory = 'all';

  String _selectedCategory = _allCategory;

  @override
  void didUpdateWidget(covariant Mem0MemorySection oldWidget) {
    super.didUpdateWidget(oldWidget);
    final categories = _buildCategories();
    final hasSelectedCategory = categories.any(
      (category) => category.$1 == _selectedCategory,
    );
    if (!hasSelectedCategory) {
      _selectedCategory = _allCategory;
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    if (!widget.snapshot.shouldShowSection && !widget.isLoading) {
      return const SizedBox.shrink();
    }

    final categories = _buildCategories();
    final filteredItems = _buildFilteredItems();

    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 0, 18, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildHeader(filteredCount: filteredItems.length),
          if (categories.length > 1) ...[
            const SizedBox(height: 12),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: categories.map((category) {
                final countText = category.$2 > 0 ? ' ${category.$2}' : '';
                return GestureDetector(
                  onTap: () {
                    setState(() {
                      _selectedCategory = category.$1;
                    });
                  },
                  child: TagChip(
                    title: '${category.$3}$countText',
                    selected: _selectedCategory == category.$1,
                    showIcon: false,
                    backgroundColor: palette.surfacePrimary.withValues(
                      alpha: 0.9,
                    ),
                  ),
                );
              }).toList(),
            ),
          ],
          const SizedBox(height: 14),
          if (widget.isLoading && !widget.snapshot.hasData)
            _buildLoadingState()
          else if (!widget.snapshot.configured)
            _buildPlaceholder(
              icon: Icons.cloud_off_outlined,
              title: LegacyTextLocalizer.localize('长期记忆未就绪'),
              subtitle: LegacyTextLocalizer.localize(
                '完成记忆初始化后，这里会展示跨会话沉淀的偏好与事实。',
              ),
            )
          else if (widget.snapshot.errorMessage != null &&
              !widget.snapshot.hasData)
            _buildPlaceholder(
              icon: Icons.error_outline_rounded,
              title: LegacyTextLocalizer.localize('长期记忆暂时不可用'),
              subtitle: widget.snapshot.errorMessage!,
            )
          else if (filteredItems.isEmpty)
            _buildPlaceholder(
              icon: Icons.auto_awesome_outlined,
              title: LegacyTextLocalizer.localize('长期记忆还是空的'),
              subtitle: LegacyTextLocalizer.localize(
                '当 Agent 主动写入长期偏好后，这里会逐渐丰富起来。',
              ),
            )
          else
            Column(
              children: [
                ...filteredItems.map(
                  (item) => Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: _buildMemoryCard(item),
                  ),
                ),
              ],
            ),
        ],
      ),
    );
  }

  Widget _buildHeader({required int filteredCount}) {
    final palette = context.omniPalette;
    final count = widget.snapshot.items.length;
    return Row(
      children: [
        Text(
          LegacyTextLocalizer.localize('长期记忆'),
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: AppTextStyles.fontSizeMain,
            fontWeight: AppTextStyles.fontWeightSemiBold,
            height: AppTextStyles.lineHeightH2,
          ),
        ),
        if (count > 0) ...[
          const SizedBox(width: 8),
          Text(
            '$filteredCount/$count',
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: AppTextStyles.fontSizeSmall,
              height: AppTextStyles.lineHeightH2,
            ),
          ),
        ],
        const Spacer(),
        if (widget.snapshot.configured || widget.snapshot.hasData)
          IconButton(
            tooltip: \'Action\', onPressed: widget.isMutating ? null : widget.onAddTap,
            icon: const Icon(
              Icons.add_circle_outline_rounded,
              color: AppColors.buttonPrimary,
              size: 20,
            ),
            splashRadius: 18,
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(minWidth: 28, minHeight: 28),
            tooltip: LegacyTextLocalizer.localize('新增长期记忆'),
          ),
        if (widget.snapshot.configured || widget.snapshot.hasData)
          IconButton(
            tooltip: \'Action\', onPressed: widget.isMutating || widget.onRefresh == null
                ? null
                : () async {
                    await widget.onRefresh!.call();
                  },
            icon: const Icon(
              Icons.refresh_rounded,
              color: AppColors.buttonPrimary,
              size: 20,
            ),
            splashRadius: 18,
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(minWidth: 28, minHeight: 28),
            tooltip: LegacyTextLocalizer.localize('刷新长期记忆'),
          ),
      ],
    );
  }

  Widget _buildLoadingState() {
    final palette = context.omniPalette;
    return Column(
      children: List.generate(3, (index) {
        return Container(
          width: double.infinity,
          margin: EdgeInsets.only(bottom: index == 2 ? 0 : 10),
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: palette.surfacePrimary.withValues(alpha: 0.84),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: palette.borderSubtle),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: double.infinity,
                height: 16,
                decoration: BoxDecoration(
                  color: const Color(0x142C7FEB),
                  borderRadius: BorderRadius.circular(999),
                ),
              ),
              const SizedBox(height: 8),
              Container(
                width: 180,
                height: 12,
                decoration: BoxDecoration(
                  color: const Color(0x102C7FEB),
                  borderRadius: BorderRadius.circular(999),
                ),
              ),
            ],
          ),
        );
      }),
    );
  }

  Widget _buildPlaceholder({
    required IconData icon,
    required String title,
    required String subtitle,
  }) {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: context.omniPalette.surfacePrimary.withValues(alpha: 0.84),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: context.omniPalette.borderSubtle),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: const Color(0x102C7FEB),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(icon, color: AppColors.buttonPrimary, size: 20),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: AppTextStyles.fontSizeMain,
                    fontWeight: AppTextStyles.fontWeightMedium,
                    height: AppTextStyles.lineHeightH2,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  subtitle,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: AppTextStyles.fontSizeSmall,
                    height: AppTextStyles.lineHeightH2,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildMemoryCard(Mem0MemoryItem item) {
    final palette = context.omniPalette;
    return InkWell(
      onTap: widget.onMemoryTap == null
          ? null
          : () => widget.onMemoryTap!(item),
      borderRadius: BorderRadius.circular(16),
      child: Ink(
        width: double.infinity,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: palette.surfacePrimary.withValues(alpha: 0.88),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: palette.borderSubtle),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 36,
              height: 36,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(12),
                gradient: const LinearGradient(
                  colors: [Color(0x1F2DA5F0), Color(0x262C7FEB)],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
              ),
              child: const Icon(
                Icons.auto_awesome_outlined,
                color: AppColors.buttonPrimary,
                size: 18,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.memory,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: AppTextStyles.fontSizeMain,
                      fontWeight: AppTextStyles.fontWeightMedium,
                      height: AppTextStyles.lineHeightH2,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: [
                      _buildMetaPill(_formatTime(item.displayTime)),
                      ...item.categories.take(2).map(_buildMetaPill),
                      if (item.score != null)
                        _buildMetaPill(
                          LegacyTextLocalizer.localize(
                            '匹配 ${(item.score! * 100).round()}%',
                          ),
                        ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Icon(
              Icons.chevron_right_rounded,
              color: palette.textSecondary,
              size: 18,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMetaPill(String label) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: palette.segmentThumb.withValues(
          alpha: context.isDarkTheme ? 0.72 : 0.9,
        ),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: palette.textSecondary,
          fontSize: AppTextStyles.fontSizeTip,
          fontWeight: AppTextStyles.fontWeightRegular,
        ),
      ),
    );
  }

  List<(String, int, String)> _buildCategories() {
    final countMap = <String, int>{};
    for (final item in widget.snapshot.items) {
      for (final category in item.categories) {
        countMap[category] = (countMap[category] ?? 0) + 1;
      }
    }

    final categories = countMap.entries.toList()
      ..sort((a, b) => b.value.compareTo(a.value));

    return <(String, int, String)>[
      (_allCategory, 0, LegacyTextLocalizer.localize('全部')),
      ...categories.take(6).map((entry) => (entry.key, entry.value, entry.key)),
    ];
  }

  List<Mem0MemoryItem> _buildFilteredItems() {
    if (_selectedCategory == _allCategory) {
      return widget.snapshot.items;
    }
    return widget.snapshot.items
        .where((item) => item.categories.contains(_selectedCategory))
        .toList();
  }

  String _formatTime(DateTime? dateTime) {
    if (dateTime == null) {
      return LegacyTextLocalizer.localize('长期记忆');
    }
    final now = DateTime.now();
    final diff = now.difference(dateTime);
    if (diff.inMinutes < 1) {
      return LegacyTextLocalizer.localize('刚刚');
    }
    if (diff.inHours < 1) {
      return LegacyTextLocalizer.localize('${diff.inMinutes} 分钟前');
    }
    if (diff.inDays < 1) {
      return LegacyTextLocalizer.localize('${diff.inHours} 小时前');
    }
    if (diff.inDays < 7) {
      return LegacyTextLocalizer.localize('${diff.inDays} 天前');
    }
    return DateFormat('MM/dd').format(dateTime);
  }
}
