import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_segmented_slider.dart';
import 'package:ui/services/usage_statistics_service.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/widgets/flclash/page_activity.dart';

// ---------------------------------------------------------------------------
//  Month label helper (heatmap)
// ---------------------------------------------------------------------------

class _MonthLabel {
  final int weekIndex;
  final String label;
  const _MonthLabel({required this.weekIndex, required this.label});
}

// ---------------------------------------------------------------------------
//  Activity Dashboard Card
// ---------------------------------------------------------------------------

class ActivityDashboardCard extends StatefulWidget {
  final int weeksToShow;
  const ActivityDashboardCard({
    super.key,
    this.weeksToShow = 16,
    this.active = true,
  });

  final bool active;

  @override
  State<ActivityDashboardCard> createState() => _ActivityDashboardCardState();
}

class _ActivityDashboardCardState extends State<ActivityDashboardCard>
    with
        SingleTickerProviderStateMixin,
        WidgetsBindingObserver,
        FlClashPageLoadMixin {
  // -- tab --
  static const int _convTab = 0;
  static const int _tokenTab = 1;
  int _currentTab = 0; // 0=对话  1=Token

  // -- conversation data --
  Map<String, int> _activityMap = {};
  bool _isConvLoading = true;
  bool _hasConversationData = false;
  bool _hasConvError = false;
  int _convLoadGeneration = 0;
  int _totalConversations = 0;
  int _currentStreak = 0;

  // -- token data --
  List<WeeklyTokenData> _weeklyData = [];
  bool _isTokenLoading = true;
  bool _hasTokenData = false;
  bool _hasTokenError = false;
  int _tokenLoadGeneration = 0;
  int _totalCached = 0;
  Map<String, int> _modelTotals = {};
  List<String> _modelOrder = [];

  // -- animation --
  late AnimationController _fadeController;
  late Animation<double> _fadeAnimation;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _fadeController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 600),
    );
    _fadeAnimation = CurvedAnimation(
      parent: _fadeController,
      curve: Curves.easeOut,
    );
    _loadConversationData();
    _loadTokenData();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _fadeController.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant ActivityDashboardCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.active && !oldWidget.active) {
      _loadConversationData();
      _loadTokenData();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && widget.active) {
      _loadConversationData();
      _loadTokenData();
    }
  }

  // -----------------------------------------------------------------------
  //  Data loading — conversation
  // -----------------------------------------------------------------------

  Future<void> _loadConversationData() => runPageLoad(
    #conversationActivity,
    _readConversationData,
    revalidate: true,
  );

  Future<void> _readConversationData() async {
    final generation = ++_convLoadGeneration;
    setState(() {
      // Retain the cached chart while refreshing an already visited tab.
      _isConvLoading = !_hasConversationData;
      _hasConvError = false;
    });
    try {
      final now = DateTime.now();
      final today = DateTime(now.year, now.month, now.day);
      final startDate = today.subtract(
        Duration(days: widget.weeksToShow * 7 - 1),
      );

      final summary = await UsageStatisticsService.getConversationActivity(
        startDate: startDate,
        today: today,
      );

      if (await waitForPageReady() &&
          generation == _convLoadGeneration &&
          pageLoadIsCurrent(#conversationActivity)) {
        setState(() {
          _activityMap = summary.activityMap;
          _totalConversations = summary.totalConversations;
          _currentStreak = summary.currentStreak;
          _hasConversationData = true;
          _isConvLoading = false;
        });
        _tryStartFade();
      }
    } catch (e) {
      debugPrint('[ActivityDashboard] conv load failed: $e');
      if (await waitForPageReady() &&
          generation == _convLoadGeneration &&
          pageLoadIsCurrent(#conversationActivity)) {
        setState(() {
          _isConvLoading = false;
          _hasConvError = true;
        });
        _tryStartFade();
      }
    }
  }

  // -----------------------------------------------------------------------
  //  Data loading — token
  // -----------------------------------------------------------------------

  Future<void> _loadTokenData() =>
      runPageLoad(#tokenUsage, _readTokenData, revalidate: true);

  Future<void> _readTokenData() async {
    final generation = ++_tokenLoadGeneration;
    setState(() {
      _isTokenLoading = !_hasTokenData;
      _hasTokenError = false;
    });
    try {
      final now = DateTime.now();
      final today = DateTime(now.year, now.month, now.day);
      var alignedStart = today.subtract(
        Duration(days: widget.weeksToShow * 7 - 1),
      );
      while (alignedStart.weekday != DateTime.monday) {
        alignedStart = alignedStart.subtract(const Duration(days: 1));
      }

      final totalDays = today.difference(alignedStart).inDays + 1;
      final totalWeeks = (totalDays / 7).ceil();
      final weekStarts = List.generate(
        totalWeeks,
        (i) => alignedStart.add(Duration(days: i * 7)),
      );
      final summary = await UsageStatisticsService.getTokenUsage(
        alignedStart: alignedStart,
        weekStarts: weekStarts,
      );

      if (await waitForPageReady() &&
          generation == _tokenLoadGeneration &&
          pageLoadIsCurrent(#tokenUsage)) {
        setState(() {
          _weeklyData = summary.weeklyData;
          _totalCached = summary.totalCached;
          _modelTotals = summary.modelTotals;
          _modelOrder = summary.modelOrder;
          _hasTokenData = true;
          _isTokenLoading = false;
        });
        _tryStartFade();
      }
    } catch (e) {
      debugPrint('[ActivityDashboard] token load failed: $e');
      if (await waitForPageReady() &&
          generation == _tokenLoadGeneration &&
          pageLoadIsCurrent(#tokenUsage)) {
        setState(() {
          _isTokenLoading = false;
          _hasTokenError = true;
        });
        _tryStartFade();
      }
    }
  }

  void _tryStartFade() {
    if (!_isConvLoading && !_isTokenLoading && !_fadeController.isAnimating) {
      _fadeController.forward();
    }
  }

  // -----------------------------------------------------------------------
  //  Helpers
  // -----------------------------------------------------------------------

  int get _totalTokens =>
      _modelTotals.values.fold(0, (sum, tokens) => sum + tokens);

  String _dateKey(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  String _formatTokenCount(int count) {
    if (count >= 1000000) return '${(count / 1000000).toStringAsFixed(1)}M';
    if (count >= 1000) return '${(count / 1000).toStringAsFixed(1)}K';
    return '$count';
  }

  int _percentOf(int part, int total) =>
      total == 0 ? 0 : (part * 100 / total).round();

  // -- heatmap colors --
  int _intensityLevel(int count) {
    if (count == 0) return 0;
    if (count == 1) return 1;
    if (count <= 3) return 2;
    if (count <= 6) return 3;
    return 4;
  }

  Color _cellColor(int level, bool isDark) {
    const light = [0xFFEBEBEB, 0xFFCCCCCC, 0xFFAAAAAA, 0xFF777777, 0xFF333333];
    const dark = [0xFF292929, 0xFF444444, 0xFF777777, 0xFFAAAAAA, 0xFFDDDDDD];
    final c = isDark ? dark : light;
    return Color(c[level.clamp(0, 4)]);
  }

  // -- token colors --
  static const List<Color> _modelLightColors = [
    Color(0xFF2C7FEB),
    Color(0xFF16A085),
    Color(0xFF8B5CF6),
    Color(0xFFF97316),
    Color(0xFFE11D48),
    Color(0xFF0891B2),
    Color(0xFF65A30D),
    Color(0xFFDB2777),
    Color(0xFF475569),
    Color(0xFFB45309),
  ];

  static const List<Color> _modelDarkColors = [
    Color(0xFF7BBCE6),
    Color(0xFF5DD6B3),
    Color(0xFFC4B5FD),
    Color(0xFFFBBF24),
    Color(0xFFFB7185),
    Color(0xFF67E8F9),
    Color(0xFFA3E635),
    Color(0xFFF0ABFC),
    Color(0xFFCBD5E1),
    Color(0xFFFDBA74),
  ];

  Color _modelColor(String modelId, bool isDark) {
    final colors = isDark ? _modelDarkColors : _modelLightColors;
    final hash = modelId.codeUnits.fold<int>(
      0,
      (value, codeUnit) => (value * 31 + codeUnit) & 0x7fffffff,
    );
    return colors[hash % colors.length];
  }

  Color _cachedColor(bool d) =>
      d ? const Color(0xFFB8860B) : const Color(0xFFD4A017);

  // -----------------------------------------------------------------------
  //  Build
  // -----------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final isLoading = _isConvLoading && _isTokenLoading;

    return Padding(
      key: const ValueKey('usage-statistics-content'),
      padding: const EdgeInsets.symmetric(horizontal: 22),
      child: isLoading
          ? _buildSkeleton(palette, isDark)
          : FadeTransition(
              opacity: _fadeAnimation,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _buildStatsRow(palette, isDark),
                  const SizedBox(height: 12),
                  _buildSegmentedControl(),
                  const SizedBox(height: 12),
                  AnimatedCrossFade(
                    firstChild: _buildConversationView(isDark, palette),
                    secondChild: _buildTokenView(isDark, palette),
                    crossFadeState: _currentTab == _convTab
                        ? CrossFadeState.showFirst
                        : CrossFadeState.showSecond,
                    duration: const Duration(milliseconds: 300),
                    firstCurve: Curves.easeOut,
                    secondCurve: Curves.easeOut,
                    sizeCurve: Curves.easeOut,
                  ),
                ],
              ),
            ),
    );
  }

  // -----------------------------------------------------------------------
  //  Skeleton
  // -----------------------------------------------------------------------

  Widget _buildSkeleton(OmniThemePalette palette, bool isDark) {
    final c = palette.surfaceSecondary;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 200,
          height: 16,
          decoration: BoxDecoration(
            color: c,
            borderRadius: BorderRadius.circular(4),
          ),
        ),
        const SizedBox(height: 12),
        Container(
          width: double.infinity,
          height: 28,
          decoration: BoxDecoration(
            color: c,
            borderRadius: BorderRadius.circular(14),
          ),
        ),
        const SizedBox(height: 12),
        Container(
          width: double.infinity,
          height: 90,
          decoration: BoxDecoration(
            color: c,
            borderRadius: BorderRadius.circular(6),
          ),
        ),
        const SizedBox(height: 10),
        Container(
          width: 140,
          height: 12,
          decoration: BoxDecoration(
            color: c,
            borderRadius: BorderRadius.circular(4),
          ),
        ),
      ],
    );
  }

  // -----------------------------------------------------------------------
  //  Stats row — unified header
  // -----------------------------------------------------------------------

  Widget _buildStatsRow(OmniThemePalette palette, bool isDark) {
    final accentBlue = palette.textPrimary;
    final streakColor = _currentStreak >= 3
        ? const Color(0xFFF59E0B)
        : accentBlue;

    return LayoutBuilder(
      builder: (context, constraints) {
        final spacious = constraints.maxWidth >= 600;
        Widget statistic(
          IconData icon,
          String value,
          String label,
          Color color,
        ) => ConstrainedBox(
          constraints: BoxConstraints(maxWidth: constraints.maxWidth),
          child: _buildStatPill(
            icon,
            value,
            label,
            color,
            palette,
            spacious: spacious,
          ),
        );
        return Wrap(
          spacing: spacious ? 24 : 16,
          runSpacing: 12,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            statistic(
              Icons.chat_bubble_outline_rounded,
              _isConvLoading || _hasConvError ? '—' : '$_totalConversations',
              LegacyTextLocalizer.localize('次对话'),
              accentBlue,
            ),
            statistic(
              Icons.local_fire_department_rounded,
              _isConvLoading || _hasConvError ? '—' : '$_currentStreak',
              LegacyTextLocalizer.localize('天连续'),
              streakColor,
            ),
            statistic(
              Icons.bolt_rounded,
              _isTokenLoading || _hasTokenError
                  ? '—'
                  : _formatTokenCount(_totalTokens),
              'tokens',
              accentBlue,
            ),
            if (!_isTokenLoading && !_hasTokenError && _modelTotals.isNotEmpty)
              statistic(
                Icons.hub_outlined,
                '${_modelTotals.length}',
                LegacyTextLocalizer.localize('个模型'),
                _modelColor(_modelOrder.first, isDark),
              ),
            if (!_isTokenLoading && !_hasTokenError && _totalCached > 0)
              statistic(
                Icons.cached_rounded,
                _formatTokenCount(_totalCached),
                LegacyTextLocalizer.localize('缓存'),
                _cachedColor(isDark),
              ),
          ],
        );
      },
    );
  }

  Widget _buildStatPill(
    IconData icon,
    String value,
    String label,
    Color iconColor,
    OmniThemePalette palette, {
    required bool spacious,
  }) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 16, color: iconColor),
        const SizedBox(width: 5),
        Text(
          value,
          style: TextStyle(
            fontSize: spacious ? 18 : 15,
            fontWeight: FontWeight.w600,
            color: palette.textPrimary,
          ),
        ),
        const SizedBox(width: 4),
        Flexible(
          child: Text(
            label,
            style: TextStyle(fontSize: 12, color: palette.textSecondary),
          ),
        ),
      ],
    );
  }

  // -----------------------------------------------------------------------
  //  Tab switcher (matches Scheduled page style, smaller size)
  // -----------------------------------------------------------------------

  void _switchTab(int tabIndex) {
    if (_currentTab == tabIndex) return;
    setState(() => _currentTab = tabIndex);
  }

  Widget _buildSegmentedControl() => OmniSegmentedSlider<int>(
    value: _currentTab,
    keyPrefix: 'activity-view',
    height: 38 + (MediaQuery.textScalerOf(context).scale(12.5) - 12.5) * 1.4,
    options: [
      OmniSegmentedOption(
        value: _convTab,
        label: LegacyTextLocalizer.isEnglish ? 'Chat' : '对话',
      ),
      const OmniSegmentedOption(value: _tokenTab, label: 'Token'),
    ],
    onChanged: _switchTab,
  );

  // -----------------------------------------------------------------------
  //  Conversation tab — heatmap
  // -----------------------------------------------------------------------

  Widget _buildConversationView(bool isDark, OmniThemePalette palette) {
    if (_isConvLoading) {
      return const SizedBox(
        height: 100,
        child: Center(
          child: SizedBox(
            width: 16,
            height: 16,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
        ),
      );
    }
    if (_hasConvError) {
      return _buildLoadError(
        LegacyTextLocalizer.isEnglish
            ? 'Could not load conversations. Tap to retry.'
            : '对话统计加载失败，点击重试',
        _loadConversationData,
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [_buildHeatmapGrid(isDark)],
    );
  }

  Widget _buildHeatmapGrid(bool isDark) {
    final palette = context.omniPalette;
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final daysBack = widget.weeksToShow * 7 - 1;
    var startDate = today.subtract(Duration(days: daysBack));
    while (startDate.weekday != DateTime.monday) {
      startDate = startDate.subtract(const Duration(days: 1));
    }

    final totalDays = today.difference(startDate).inDays + 1;
    final totalWeeks = (totalDays / 7).ceil();

    final monthLabels = <_MonthLabel>[];
    String? lastMonth;
    for (int week = 0; week < totalWeeks; week++) {
      final weekStart = startDate.add(Duration(days: week * 7));
      final m = _monthName(weekStart.month);
      if (m != lastMonth) {
        monthLabels.add(_MonthLabel(weekIndex: week, label: m));
        lastMonth = m;
      }
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final geometry = _chartGeometry(constraints.maxWidth, totalWeeks);
        final labelStyle = TextStyle(
          fontSize: 11,
          height: 1.2,
          fontWeight: FontWeight.w500,
          color: palette.textSecondary,
        );
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: EdgeInsets.only(left: geometry.labelWidth, bottom: 8),
              child: SizedBox(
                height: geometry.labelHeight,
                child: Stack(
                  children: [
                    for (var index = 0; index < monthLabels.length; index++)
                      Positioned(
                        left: monthLabels[index].weekIndex * geometry.pitch,
                        width: index + 1 < monthLabels.length
                            ? (monthLabels[index + 1].weekIndex -
                                          monthLabels[index].weekIndex) *
                                      geometry.pitch -
                                  geometry.gap
                            : geometry.plotWidth -
                                  monthLabels[index].weekIndex * geometry.pitch,
                        child: Tooltip(
                          message: monthLabels[index].label,
                          child: Text(
                            monthLabels[index].label,
                            key: ValueKey(
                              'activity-month-${monthLabels[index].weekIndex}',
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: labelStyle,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(
                  width: geometry.labelWidth,
                  child: Column(
                    children: [
                      for (var day = 0; day < 7; day++)
                        SizedBox(
                          height:
                              geometry.cellHeight +
                              (day < 6 ? geometry.gap : 0),
                          child: Align(
                            alignment: Alignment.centerLeft,
                            child: day.isEven
                                ? Text(_dayLabel(day), style: labelStyle)
                                : null,
                          ),
                        ),
                    ],
                  ),
                ),
                SizedBox(
                  key: const ValueKey('activity-heatmap-plot'),
                  width: geometry.plotWidth,
                  height: geometry.plotHeight,
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      for (var week = 0; week < totalWeeks; week++) ...[
                        if (week > 0) SizedBox(width: geometry.gap),
                        Column(
                          children: [
                            for (var day = 0; day < 7; day++) ...[
                              if (day > 0) SizedBox(height: geometry.gap),
                              _buildActivityCell(
                                date: startDate.add(
                                  Duration(days: week * 7 + day),
                                ),
                                today: today,
                                week: week,
                                day: day,
                                geometry: geometry,
                                palette: palette,
                                isDark: isDark,
                              ),
                            ],
                          ],
                        ),
                      ],
                    ],
                  ),
                ),
              ],
            ),
          ],
        );
      },
    );
  }

  _ActivityChartGeometry _chartGeometry(double width, int columns) {
    final scaler = MediaQuery.textScalerOf(context);
    final label = TextPainter(
      text: TextSpan(
        text: LegacyTextLocalizer.isEnglish ? 'Wed' : _dayLabel(0),
        style: const TextStyle(
          fontSize: 11,
          height: 1.2,
          fontWeight: FontWeight.w500,
        ),
      ),
      textDirection: Directionality.of(context),
      textScaler: scaler,
    )..layout();
    final labelWidth = label.width + 10;
    final labelHeight = label.height;
    label.dispose();
    return _ActivityChartGeometry(
      width: width,
      columns: columns,
      labelWidth: labelWidth,
      labelHeight: labelHeight,
    );
  }

  Widget _buildActivityCell({
    required DateTime date,
    required DateTime today,
    required int week,
    required int day,
    required _ActivityChartGeometry geometry,
    required OmniThemePalette palette,
    required bool isDark,
  }) {
    final count = _activityMap[_dateKey(date)] ?? 0;
    final cell = SizedBox(
      key: ValueKey('activity-cell-$week-$day'),
      width: geometry.cellWidth,
      height: geometry.cellHeight,
      child: date.isAfter(today)
          ? null
          : DecoratedBox(
              decoration: BoxDecoration(
                color: _cellColor(_intensityLevel(count), isDark),
                borderRadius: BorderRadius.circular(4),
              ),
            ),
    );
    if (date.isAfter(today)) return cell;
    return Tooltip(
      triggerMode: TooltipTriggerMode.tap,
      enableFeedback: false,
      message: count > 0
          ? LegacyTextLocalizer.localize(
              '$count 次对话 · ${date.month}/${date.day}',
            )
          : LegacyTextLocalizer.localize('无对话 · ${date.month}/${date.day}'),
      preferBelow: false,
      verticalOffset: 12,
      decoration: BoxDecoration(
        color: palette.textPrimary,
        borderRadius: BorderRadius.circular(8),
      ),
      textStyle: TextStyle(fontSize: 12, color: palette.pageBackground),
      child: cell,
    );
  }

  String _monthName(int m) {
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
      return names[m];
    }
    const names = [
      '',
      '1月',
      '2月',
      '3月',
      '4月',
      '5月',
      '6月',
      '7月',
      '8月',
      '9月',
      '10月',
      '11月',
      '12月',
    ];
    return names[m];
  }

  String _dayLabel(int i) {
    if (LegacyTextLocalizer.isEnglish) {
      const labels = ['Mon', '', 'Wed', '', 'Fri', '', 'Sun'];
      return labels[i];
    }
    const labels = ['一', '', '三', '', '五', '', '日'];
    return labels[i];
  }

  // -----------------------------------------------------------------------
  //  Token tab — stacked bars
  // -----------------------------------------------------------------------

  Widget _buildTokenView(bool isDark, OmniThemePalette palette) {
    if (_isTokenLoading) {
      return const SizedBox(
        height: 100,
        child: Center(
          child: SizedBox(
            width: 16,
            height: 16,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
        ),
      );
    }
    if (_hasTokenError) {
      return _buildLoadError(
        LegacyTextLocalizer.isEnglish
            ? 'Could not load token usage. Tap to retry.'
            : 'Token 统计加载失败，点击重试',
        _loadTokenData,
      );
    }
    if (_totalTokens == 0) {
      return SizedBox(
        height: 70,
        child: Center(
          child: Text(
            LegacyTextLocalizer.localize('暂无 Token 消耗数据'),
            style: TextStyle(fontSize: 13, color: palette.textSecondary),
          ),
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildModelLegendStrip(isDark, palette),
        const SizedBox(height: 10),
        _buildStackedBars(isDark, palette),
      ],
    );
  }

  Widget _buildLoadError(String message, VoidCallback onRetry) {
    return SizedBox(
      height: 100,
      child: Center(
        child: TextButton.icon(
          onPressed: onRetry,
          icon: const Icon(Icons.refresh_rounded, size: 16),
          label: Text(message),
          style: TextButton.styleFrom(
            foregroundColor: context.omniPalette.textSecondary,
            textStyle: const TextStyle(fontSize: 13),
          ),
        ),
      ),
    );
  }

  Widget _buildModelLegendStrip(bool isDark, OmniThemePalette palette) =>
      LayoutBuilder(
        builder: (context, constraints) => Wrap(
          key: const ValueKey('activity-model-legend'),
          spacing: 18,
          runSpacing: 8,
          children: [
            for (final modelId in _modelOrder)
              ConstrainedBox(
                constraints: BoxConstraints(maxWidth: constraints.maxWidth),
                child: _buildModelLegendItem(
                  modelId: modelId,
                  tokens: _modelTotals[modelId] ?? 0,
                  isDark: isDark,
                  palette: palette,
                ),
              ),
          ],
        ),
      );

  Widget _buildModelLegendItem({
    required String modelId,
    required int tokens,
    required bool isDark,
    required OmniThemePalette palette,
  }) {
    final color = _modelColor(modelId, isDark);
    return Tooltip(
      message: modelId,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 9,
              height: 9,
              decoration: BoxDecoration(
                color: color,
                borderRadius: BorderRadius.circular(3),
              ),
            ),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                modelId,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                  color: palette.textSecondary,
                ),
              ),
            ),
            const SizedBox(width: 6),
            Text(
              '${_percentOf(tokens, _totalTokens)}%',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: isDark
                    ? color
                    : Color.lerp(color, palette.textPrimary, 0.18),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStackedBars(bool isDark, OmniThemePalette palette) {
    final maxWeekTotal = _weeklyData.fold<int>(
      0,
      (p, w) => w.totalTokens > p ? w.totalTokens : p,
    );
    return LayoutBuilder(
      builder: (context, constraints) {
        final geometry = _chartGeometry(
          constraints.maxWidth,
          _weeklyData.length,
        );
        final barAreaHeight = geometry.plotHeight;
        final barWidth = geometry.cellWidth;
        return Padding(
          padding: EdgeInsets.only(left: geometry.labelWidth),
          child: SizedBox(
            key: const ValueKey('activity-token-plot'),
            height: barAreaHeight,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: List.generate(_weeklyData.length, (index) {
                final week = _weeklyData[index];
                final totalH = maxWeekTotal > 0
                    ? (week.totalTokens / maxWeekTotal) * barAreaHeight
                    : 0.0;
                final modelIds = _orderedModelsForWeek(week);
                return Padding(
                  padding: EdgeInsets.only(
                    right: index < _weeklyData.length - 1 ? geometry.gap : 0,
                  ),
                  child: Tooltip(
                    triggerMode: TooltipTriggerMode.tap,
                    enableFeedback: false,
                    message: week.totalTokens > 0
                        ? _buildWeekTooltip(week, modelIds)
                        : LegacyTextLocalizer.localize('无消耗'),
                    preferBelow: false,
                    verticalOffset: 12,
                    decoration: BoxDecoration(
                      color: palette.textPrimary,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    textStyle: TextStyle(
                      fontSize: 12,
                      color: palette.pageBackground,
                    ),
                    child: SizedBox(
                      key: ValueKey('activity-token-week-$index'),
                      width: barWidth,
                      height: barAreaHeight,
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: week.totalTokens == 0
                            ? [
                                Container(
                                  width: barWidth,
                                  height: 2,
                                  decoration: BoxDecoration(
                                    color: palette.surfaceElevated,
                                    borderRadius: BorderRadius.circular(1),
                                  ),
                                ),
                              ]
                            : List.generate(modelIds.length, (segmentIndex) {
                                final modelId = modelIds[segmentIndex];
                                final tokens = week.modelTokens[modelId] ?? 0;
                                // Preserve the true proportions. Giving each
                                // tiny model a minimum height can make the
                                // largest week's stacked column overflow.
                                // The tooltip keeps small values inspectable.
                                final segmentHeight =
                                    totalH * tokens / week.totalTokens;
                                final isTop = segmentIndex == 0;
                                final isBottom =
                                    segmentIndex == modelIds.length - 1;
                                return Container(
                                  key: ValueKey(
                                    'activity-token-segment-$index-$segmentIndex',
                                  ),
                                  width: barWidth,
                                  height: segmentHeight,
                                  decoration: BoxDecoration(
                                    color: _modelColor(modelId, isDark),
                                    borderRadius: BorderRadius.only(
                                      topLeft: isTop
                                          ? const Radius.circular(2.5)
                                          : Radius.zero,
                                      topRight: isTop
                                          ? const Radius.circular(2.5)
                                          : Radius.zero,
                                      bottomLeft: isBottom
                                          ? const Radius.circular(1.5)
                                          : Radius.zero,
                                      bottomRight: isBottom
                                          ? const Radius.circular(1.5)
                                          : Radius.zero,
                                    ),
                                  ),
                                );
                              }),
                      ),
                    ),
                  ),
                );
              }),
            ),
          ),
        );
      },
    );
  }

  List<String> _orderedModelsForWeek(WeeklyTokenData week) {
    final ordered = _modelOrder
        .where((modelId) => (week.modelTokens[modelId] ?? 0) > 0)
        .toList();
    for (final modelId in week.modelTokens.keys) {
      if (!ordered.contains(modelId)) {
        ordered.add(modelId);
      }
    }
    return ordered;
  }

  String _buildWeekTooltip(WeeklyTokenData week, List<String> modelIds) {
    final range = _formatWeekRange(week.weekStart);
    final lines = <String>[
      '$range · ${_formatTokenCount(week.totalTokens)} tokens',
      for (final modelId in modelIds.take(6))
        '$modelId ${_formatTokenCount(week.modelTokens[modelId] ?? 0)}',
    ];
    if (modelIds.length > 6) {
      lines.add('+${modelIds.length - 6}');
    }
    if (week.cachedTokens > 0) {
      lines.add(
        '${LegacyTextLocalizer.localize('缓存')} ${_formatTokenCount(week.cachedTokens)}',
      );
    }
    return lines.join('\n');
  }

  String _formatWeekRange(DateTime weekStart) {
    final weekEnd = weekStart.add(const Duration(days: 6));
    return '${weekStart.month}/${weekStart.day}-${weekEnd.month}/${weekEnd.day}';
  }
}

/// One column geometry drives month labels, activity cells and token bars.
/// Columns use the available width; row height stays comfortable on a Pad.
class _ActivityChartGeometry {
  _ActivityChartGeometry({
    required double width,
    required int columns,
    required this.labelWidth,
    required this.labelHeight,
  }) : plotWidth = (width - labelWidth).clamp(0.0, double.infinity),
       gap = width >= 600 ? 5 : 3,
       columns = columns > 0 ? columns : 1;

  final int columns;
  final double labelWidth;
  final double labelHeight;
  final double plotWidth;
  final double gap;
  double get pitch => (plotWidth + gap) / columns;
  double get cellWidth => (pitch - gap).clamp(0.0, double.infinity);
  double get cellHeight =>
      cellWidth.clamp(labelHeight, labelHeight > 28 ? labelHeight : 28);
  double get plotHeight => 7 * cellHeight + 6 * gap;
}
