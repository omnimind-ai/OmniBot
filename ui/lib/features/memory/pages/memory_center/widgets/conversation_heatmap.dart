import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/theme/theme_context.dart';

/// GitHub-style conversation activity heatmap.
///
/// Displays the last [weeksToShow] weeks of conversation activity
/// using a white-to-blue color gradient to indicate frequency.
class ConversationHeatmap extends StatefulWidget {
  /// Number of weeks to display in the heatmap.
  final int weeksToShow;

  const ConversationHeatmap({super.key, this.weeksToShow = 16});

  @override
  State<ConversationHeatmap> createState() => _ConversationHeatmapState();
}

class _ConversationHeatmapState extends State<ConversationHeatmap>
    with SingleTickerProviderStateMixin {
  /// Date string (yyyy-MM-dd) → conversation count for that day.
  Map<String, int> _activityMap = {};
  bool _isLoading = true;
  int _totalConversations = 0;
  int _currentStreak = 0;

  late AnimationController _fadeController;
  late Animation<double> _fadeAnimation;

  @override
  void initState() {
    super.initState();
    _fadeController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 600),
    );
    _fadeAnimation = CurvedAnimation(
      parent: _fadeController,
      curve: Curves.easeOut,
    );
    _loadConversationData();
  }

  @override
  void dispose() {
    _fadeController.dispose();
    super.dispose();
  }

  Future<void> _loadConversationData() async {
    try {
      final conversations = await ConversationService.getAllConversations(
        includeArchived: true,
      );

      final now = DateTime.now();
      final today = DateTime(now.year, now.month, now.day);
      final startDate = today.subtract(
        Duration(days: widget.weeksToShow * 7 - 1),
      );

      final Map<String, int> activityMap = {};
      int totalInRange = 0;

      for (final conv in conversations) {
        final date = DateTime.fromMillisecondsSinceEpoch(conv.createdAt);
        final dateOnly = DateTime(date.year, date.month, date.day);
        if (dateOnly.isBefore(startDate)) continue;
        if (dateOnly.isAfter(today)) continue;

        final key = _dateKey(dateOnly);
        activityMap[key] = (activityMap[key] ?? 0) + 1;
        totalInRange++;
      }

      // Calculate current streak
      int streak = 0;
      var checkDate = today;
      while (true) {
        final key = _dateKey(checkDate);
        if ((activityMap[key] ?? 0) > 0) {
          streak++;
          checkDate = checkDate.subtract(const Duration(days: 1));
        } else {
          break;
        }
      }

      if (mounted) {
        setState(() {
          _activityMap = activityMap;
          _totalConversations = totalInRange;
          _currentStreak = streak;
          _isLoading = false;
        });
        _fadeController.forward();
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isLoading = false);
        _fadeController.forward();
      }
    }
  }

  String _dateKey(DateTime date) {
    return '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';
  }

  /// Maps a conversation count to a color intensity level (0–4).
  int _intensityLevel(int count) {
    if (count == 0) return 0;
    if (count == 1) return 1;
    if (count <= 3) return 2;
    if (count <= 6) return 3;
    return 4;
  }

  /// Returns the heatmap cell color for a given intensity level.
  Color _cellColor(int level, bool isDark) {
    if (isDark) {
      switch (level) {
        case 0:
          return const Color(0xFF242728);
        case 1:
          return const Color(0xFF1A3A5C);
        case 2:
          return const Color(0xFF1B5E94);
        case 3:
          return const Color(0xFF2178BD);
        case 4:
          return const Color(0xFF3B9FE8);
        default:
          return const Color(0xFF242728);
      }
    } else {
      switch (level) {
        case 0:
          return const Color(0xFFEBF0F5);
        case 1:
          return const Color(0xFFBFDBF7);
        case 2:
          return const Color(0xFF7BBCE6);
        case 3:
          return const Color(0xFF3B8FD4);
        case 4:
          return const Color(0xFF1A56A8);
        default:
          return const Color(0xFFEBF0F5);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.fromLTRB(14, 14, 14, 12),
        decoration: BoxDecoration(
          color: palette.surfacePrimary,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: palette.borderSubtle),
          boxShadow: [
            BoxShadow(
              color: palette.shadowColor.withValues(
                alpha: isDark ? 0.30 : 0.08,
              ),
              blurRadius: 16,
              offset: const Offset(0, 8),
            ),
          ],
        ),
        child: _isLoading
            ? _buildSkeleton(palette, isDark)
            : FadeTransition(
                opacity: _fadeAnimation,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildHeader(palette, isDark),
                    const SizedBox(height: 12),
                    _buildHeatmapGrid(isDark),
                    const SizedBox(height: 10),
                    _buildLegend(isDark, palette),
                  ],
                ),
              ),
      ),
    );
  }

  Widget _buildSkeleton(OmniThemePalette palette, bool isDark) {
    final baseColor = isDark
        ? palette.surfaceSecondary
        : const Color(0xFFE8EFF8);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 140,
          height: 16,
          decoration: BoxDecoration(
            color: baseColor,
            borderRadius: BorderRadius.circular(4),
          ),
        ),
        const SizedBox(height: 14),
        Container(
          width: double.infinity,
          height: 90,
          decoration: BoxDecoration(
            color: baseColor,
            borderRadius: BorderRadius.circular(6),
          ),
        ),
        const SizedBox(height: 10),
        Container(
          width: 180,
          height: 12,
          decoration: BoxDecoration(
            color: baseColor,
            borderRadius: BorderRadius.circular(4),
          ),
        ),
      ],
    );
  }

  Widget _buildHeader(OmniThemePalette palette, bool isDark) {
    return Row(
      children: [
        // Stats pills
        Flexible(
          child: _buildStatPill(
            icon: Icons.chat_bubble_outline_rounded,
            value: '$_totalConversations',
            label: LegacyTextLocalizer.localize('次对话'),
            palette: palette,
            isDark: isDark,
          ),
        ),
        const SizedBox(width: 8),
        Flexible(
          child: _buildStatPill(
            icon: Icons.local_fire_department_rounded,
            value: '$_currentStreak',
            label: LegacyTextLocalizer.localize('天连续'),
            palette: palette,
            isDark: isDark,
            highlight: _currentStreak >= 3,
          ),
        ),
      ],
    );
  }

  Widget _buildStatPill({
    required IconData icon,
    required String value,
    required String label,
    required OmniThemePalette palette,
    required bool isDark,
    bool highlight = false,
  }) {
    final accentColor = highlight
        ? const Color(0xFFF59E0B)
        : (isDark ? const Color(0xFF7BBCE6) : const Color(0xFF2C7FEB));

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14, color: accentColor),
        const SizedBox(width: 3),
        Text(
          value,
          style: TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.w700,
            color: palette.textPrimary,
          ),
        ),
        const SizedBox(width: 2),
        Text(
          label,
          style: TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w400,
            color: palette.textTertiary,
          ),
        ),
      ],
    );
  }

  Widget _buildHeatmapGrid(bool isDark) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);

    // Calculate the start date: go back weeksToShow weeks, aligned to Monday
    final endDate = today;
    final daysBack = widget.weeksToShow * 7 - 1;
    var startDate = endDate.subtract(Duration(days: daysBack));
    // Align to Monday
    while (startDate.weekday != DateTime.monday) {
      startDate = startDate.subtract(const Duration(days: 1));
    }

    final totalDays = endDate.difference(startDate).inDays + 1;
    final totalWeeks = (totalDays / 7).ceil();

    // Build month labels
    final monthLabels = <_MonthLabel>[];
    String? lastMonth;
    for (int week = 0; week < totalWeeks; week++) {
      final weekStartDate = startDate.add(Duration(days: week * 7));
      final monthStr = _monthName(weekStartDate.month);
      if (monthStr != lastMonth) {
        monthLabels.add(_MonthLabel(weekIndex: week, label: monthStr));
        lastMonth = monthStr;
      }
    }

    const double cellSize = 11.0;
    const double cellGap = 3.0;
    const double dayLabelWidth = 20.0;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Month labels row
        Padding(
          padding: const EdgeInsets.only(left: dayLabelWidth, bottom: 4),
          child: SizedBox(
            height: 14,
            child: LayoutBuilder(
              builder: (context, constraints) {
                final availableWidth = constraints.maxWidth;
                final weekCellWidth = totalWeeks > 0
                    ? availableWidth / totalWeeks
                    : cellSize + cellGap;

                return Stack(
                  children: monthLabels.map((ml) {
                    return Positioned(
                      left: ml.weekIndex * weekCellWidth,
                      child: Text(
                        ml.label,
                        style: TextStyle(
                          fontSize: 9,
                          fontWeight: FontWeight.w500,
                          color: isDark
                              ? const Color(0xFF9A9488)
                              : const Color(0xFF98A5BB),
                        ),
                      ),
                    );
                  }).toList(),
                );
              },
            ),
          ),
        ),
        // Heatmap grid: day labels + cells
        LayoutBuilder(
          builder: (context, constraints) {
            final gridAvailableWidth =
                constraints.maxWidth - dayLabelWidth;
            final actualCellSize = totalWeeks > 0
                ? (gridAvailableWidth - (totalWeeks - 1) * cellGap) /
                    totalWeeks
                : cellSize;
            final clampedCellSize = actualCellSize.clamp(4.0, 14.0);
            final gridHeight = 7 * clampedCellSize + 6 * cellGap;
            final rowHeight = clampedCellSize + cellGap;

            return Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Day of week labels
                SizedBox(
                  width: dayLabelWidth,
                  child: Column(
                    children: [
                      for (int day = 0; day < 7; day++)
                        SizedBox(
                          height: day < 6 ? rowHeight : clampedCellSize,
                          child: Align(
                            alignment: Alignment.centerLeft,
                            child: day % 2 == 0
                                ? Text(
                                    _dayLabel(day),
                                    style: TextStyle(
                                      fontSize: 9,
                                      fontWeight: FontWeight.w500,
                                      color: isDark
                                          ? const Color(0xFF9A9488)
                                          : const Color(0xFF98A5BB),
                                    ),
                                  )
                                : const SizedBox.shrink(),
                          ),
                        ),
                    ],
                  ),
                ),
                // Grid cells
                Expanded(
                  child: SizedBox(
                    height: gridHeight,
                    child: Wrap(
                      direction: Axis.vertical,
                      spacing: cellGap,
                      runSpacing: cellGap,
                      children: List.generate(totalWeeks * 7, (index) {
                        final week = index ~/ 7;
                        final dayOfWeek = index % 7;
                        final cellDate = startDate.add(
                          Duration(days: week * 7 + dayOfWeek),
                        );

                        // Skip future dates
                        if (cellDate.isAfter(today)) {
                          return SizedBox(
                            width: clampedCellSize,
                            height: clampedCellSize,
                          );
                        }

                        final key = _dateKey(cellDate);
                        final count = _activityMap[key] ?? 0;
                        final level = _intensityLevel(count);

                        return Tooltip(
                          message: count > 0
                              ? LegacyTextLocalizer.localize(
                                  '$count 次对话 · ${cellDate.month}/${cellDate.day}',
                                )
                              : LegacyTextLocalizer.localize(
                                  '无对话 · ${cellDate.month}/${cellDate.day}',
                                ),
                          preferBelow: false,
                          verticalOffset: 12,
                          decoration: BoxDecoration(
                            color: isDark
                                ? const Color(0xFF2D3032)
                                : const Color(0xFF353E53),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          textStyle: const TextStyle(
                            fontSize: 11,
                            color: Colors.white,
                          ),
                          child: Container(
                            width: clampedCellSize,
                            height: clampedCellSize,
                            decoration: BoxDecoration(
                              color: _cellColor(level, isDark),
                              borderRadius: BorderRadius.circular(2.5),
                            ),
                          ),
                        );
                      }),
                    ),
                  ),
                ),
              ],
            );
          },
        ),
      ],
    );
  }

  Widget _buildLegend(bool isDark, OmniThemePalette palette) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.end,
      children: [
        Text(
          LegacyTextLocalizer.isEnglish ? 'Less' : '少',
          style: TextStyle(
            fontSize: 9,
            color: palette.textTertiary,
          ),
        ),
        const SizedBox(width: 4),
        for (int level = 0; level <= 4; level++) ...[
          Container(
            width: 10,
            height: 10,
            decoration: BoxDecoration(
              color: _cellColor(level, isDark),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          if (level < 4) const SizedBox(width: 3),
        ],
        const SizedBox(width: 4),
        Text(
          LegacyTextLocalizer.isEnglish ? 'More' : '多',
          style: TextStyle(
            fontSize: 9,
            color: palette.textTertiary,
          ),
        ),
      ],
    );
  }

  String _monthName(int month) {
    if (LegacyTextLocalizer.isEnglish) {
      const names = [
        '',
        'Jan',
        'Feb',
        'Mar',
        'Apr',
        'May',
        'Jun',
        'Jul',
        'Aug',
        'Sep',
        'Oct',
        'Nov',
        'Dec',
      ];
      return names[month];
    }
    const names = [
      '', '1月', '2月', '3月', '4月', '5月', '6月',
      '7月', '8月', '9月', '10月', '11月', '12月',
    ];
    return names[month];
  }

  String _dayLabel(int dayIndex) {
    // dayIndex 0=Mon, 2=Wed, 4=Fri, 6=Sun
    if (LegacyTextLocalizer.isEnglish) {
      const labels = ['Mon', '', 'Wed', '', 'Fri', '', 'Sun'];
      return labels[dayIndex];
    }
    const labels = ['一', '', '三', '', '五', '', '日'];
    return labels[dayIndex];
  }
}

class _MonthLabel {
  final int weekIndex;
  final String label;
  const _MonthLabel({required this.weekIndex, required this.label});
}
