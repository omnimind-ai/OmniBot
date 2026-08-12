import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/storage_usage_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

class StorageUsagePage extends StatefulWidget {
  const StorageUsagePage({super.key});

  @override
  State<StorageUsagePage> createState() => _StorageUsagePageState();
}

class _StorageUsagePageState extends State<StorageUsagePage> {
  bool _loading = true;
  String? _error;
  String? _clearingCategoryId;
  String? _applyingStrategyId;
  bool _deletingAllLocalData = false;
  StorageUsageSummary? _summary;

  static const List<Color> _segmentPaletteLight = [
    Color(0xFF2C7FEB),
    Color(0xFF00A870),
    Color(0xFFF59E0B),
    Color(0xFFEF4444),
    Color(0xFF8B5CF6),
    Color(0xFF14B8A6),
    Color(0xFFEC4899),
    Color(0xFF6366F1),
    Color(0xFF84CC16),
    Color(0xFF64748B),
  ];

  static const List<Color> _segmentPaletteDark = [
    Color(0xFF6FA9FF),
    Color(0xFF66D4A4),
    Color(0xFFFFC766),
    Color(0xFFFF8E8E),
    Color(0xFFB9A1FF),
    Color(0xFF58D6CB),
    Color(0xFFFF96CD),
    Color(0xFF8F9BFF),
    Color(0xFFB6DD6F),
    Color(0xFF9AA4B2),
  ];

  @override
  void initState() {
    super.initState();
    _loadSummary();
  }

  String _t(BuildContext context, String zh, String en) {
    if (LegacyTextLocalizer.isEnglish) {
      return en;
    }
    return context.trLegacy(zh);
  }

  Future<void> _loadSummary({bool silent = false}) async {
    if (!silent) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }
    try {
      final summary = await StorageUsageService.getStorageUsageSummary();
      if (!mounted) return;
      setState(() {
        _summary = summary;
        _loading = false;
        _error = null;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = _t(
          context,
          '存储分析失败，请重试',
          'Storage analysis failed, please try again',
        );
      });
    }
  }

  Future<void> _onClearCategory(StorageUsageCategory category) async {
    final olderThanDays = await _showClearOptionsDialog(category);
    if (!mounted) return;
    if (olderThanDays == null) return;

    setState(() {
      _clearingCategoryId = category.id;
    });
    try {
      final result = await StorageUsageService.clearCategory(
        category.id,
        olderThanDays: olderThanDays > 0 ? olderThanDays : null,
      );
      if (!mounted) return;
      if (result.summary != null) {
        setState(() {
          _summary = result.summary;
        });
      } else {
        await _loadSummary(silent: true);
        if (!mounted) return;
      }

      if (result.success) {
        showToast(
          _t(
            context,
            '已清理 ${category.name}，释放 ${_formatBytes(result.releasedBytes)}',
            'Cleaned ${category.name}, freed ${_formatBytes(result.releasedBytes)}',
          ),
          type: ToastType.success,
        );
      } else {
        final rawHint = (result.manualActionHint ?? '').trim();
        final hint = _translateHint(rawHint);
        showToast(
          hint.isNotEmpty
              ? _t(context, '部分清理失败：$hint', 'Some cleanup failed: $hint')
              : _t(
                  context,
                  '部分文件清理失败，请稍后重试',
                  'Some files failed to clean up, please try again later',
                ),
          type: ToastType.error,
        );
      }
    } catch (_) {
      if (!mounted) return;
      showToast(
        _t(context, '清理失败，请稍后重试', 'Cleanup failed, please try again later'),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _clearingCategoryId = null;
        });
      }
    }
  }

  Future<int?> _showClearOptionsDialog(StorageUsageCategory category) async {
    int selected = 0;
    final canRetention = category.riskLevel != 'dangerous';
    return showDialog<int>(
      context: context,
      builder: (dialogContext) {
        final palette = dialogContext.omniPalette;
        return StatefulBuilder(
          builder: (dialogContext, setDialogState) {
            return AlertDialog(
              backgroundColor: palette.surfacePrimary,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
              ),
              title: Text(
                _t(
                  dialogContext,
                  '清理 ${category.name}',
                  'Clean ${category.name}',
                ),
              ),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    category.cleanupHint ??
                        _t(
                          dialogContext,
                          '确认清理该分类数据吗？',
                          'Confirm cleanup for this category?',
                        ),
                    style: TextStyle(color: palette.textSecondary),
                  ),
                  if (canRetention) ...[
                    const SizedBox(height: 12),
                    Text(
                      _t(dialogContext, '清理范围', 'Cleanup scope'),
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Wrap(
                      spacing: 8,
                      children: [
                        ChoiceChip(
                          label: Text(_t(dialogContext, '全部', 'All')),
                          selected: selected == 0,
                          onSelected: (_) => setDialogState(() => selected = 0),
                        ),
                        ChoiceChip(
                          label: Text(_t(dialogContext, '7天前', '7 days ago')),
                          selected: selected == 7,
                          onSelected: (_) => setDialogState(() => selected = 7),
                        ),
                        ChoiceChip(
                          label: Text(_t(dialogContext, '30天前', '30 days ago')),
                          selected: selected == 30,
                          onSelected: (_) =>
                              setDialogState(() => selected = 30),
                        ),
                      ],
                    ),
                  ],
                ],
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(dialogContext).pop(null),
                  child: Text(_t(dialogContext, '取消', 'Cancel')),
                ),
                FilledButton(
                  onPressed: () => Navigator.of(dialogContext).pop(selected),
                  child: Text(_t(dialogContext, '确认清理', 'Confirm cleanup')),
                ),
              ],
            );
          },
        );
      },
    );
  }

  Future<void> _applyStrategy(StorageCleanupStrategyPreset preset) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) {
        final palette = dialogContext.omniPalette;
        return AlertDialog(
          backgroundColor: palette.surfacePrimary,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          title: Text(
            _t(
              dialogContext,
              '执行策略：${preset.name}',
              'Run strategy: ${preset.name}',
            ),
          ),
          content: Text(
            preset.description,
            style: TextStyle(color: palette.textSecondary),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: Text(_t(dialogContext, '取消', 'Cancel')),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: Text(_t(dialogContext, '开始执行', 'Run')),
            ),
          ],
        );
      },
    );
    if (!mounted) return;
    if (confirmed != true) return;

    setState(() {
      _applyingStrategyId = preset.id;
    });
    try {
      final result = await StorageUsageService.applyCleanupStrategy(preset.id);
      if (!mounted) return;
      if (result.summary != null) {
        setState(() {
          _summary = result.summary;
        });
      } else {
        await _loadSummary(silent: true);
        if (!mounted) return;
      }

      final failedCount = result.actionResults
          .where((item) => !item.success)
          .length;
      if (failedCount == 0) {
        showToast(
          _t(
            context,
            '策略执行完成，释放 ${_formatBytes(result.releasedBytes)}',
            'Cleanup strategy completed, freed ${_formatBytes(result.releasedBytes)}',
          ),
          type: ToastType.success,
        );
      } else {
        showToast(
          _t(
            context,
            '策略执行完成，释放 ${_formatBytes(result.releasedBytes)}，$failedCount 项未完全成功',
            'Cleanup strategy finished, freed ${_formatBytes(result.releasedBytes)}, $failedCount actions were incomplete',
          ),
          type: ToastType.error,
        );
      }
    } catch (_) {
      if (!mounted) return;
      showToast(
        _t(
          context,
          '策略执行失败，请稍后重试',
          'Cleanup strategy failed, please try again later',
        ),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _applyingStrategyId = null;
        });
      }
    }
  }

  Future<void> _deleteAllLocalData() async {
    final reviewed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) {
        final palette = dialogContext.omniPalette;
        return AlertDialog(
          backgroundColor: palette.surfacePrimary,
          title: Text(_t(dialogContext, '删除全部本机数据', 'Delete all local data')),
          content: Text(
            _t(
              dialogContext,
              '这会永久删除本机聊天、工作区、账号登录状态、AI Provider、凭据、配置、日志和缓存，并关闭正在运行的本机服务。云端账号、平台额度、服务端记录和公共存储中的原文件不会被删除。App 随后会关闭，需要重新打开。',
              'This permanently deletes local chats, workspaces, account sign-in, AI providers, credentials, settings, logs, and caches, and stops local services. Your cloud account, platform quota, server records, and original files in shared storage are kept. The app will close and must be opened again.',
            ),
            style: TextStyle(color: palette.textSecondary),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: Text(_t(dialogContext, '取消', 'Cancel')),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: Text(_t(dialogContext, '我已了解', 'I understand')),
            ),
          ],
        );
      },
    );
    if (!mounted || reviewed != true) return;

    final controller = TextEditingController();
    final phrase = _t(context, '删除本机数据', 'DELETE LOCAL DATA');
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) {
        final palette = dialogContext.omniPalette;
        return StatefulBuilder(
          builder: (dialogContext, setDialogState) => AlertDialog(
            backgroundColor: palette.surfacePrimary,
            title: Text(_t(dialogContext, '最终确认', 'Final confirmation')),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _t(
                    dialogContext,
                    '请输入“$phrase”继续。此操作无法撤销。',
                    'Type “$phrase” to continue. This cannot be undone.',
                  ),
                  style: TextStyle(color: palette.textSecondary),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: controller,
                  autofocus: true,
                  onChanged: (_) => setDialogState(() {}),
                  decoration: InputDecoration(hintText: phrase),
                ),
              ],
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(false),
                child: Text(_t(dialogContext, '取消', 'Cancel')),
              ),
              FilledButton(
                onPressed: controller.text == phrase
                    ? () => Navigator.of(dialogContext).pop(true)
                    : null,
                child: Text(_t(dialogContext, '永久删除', 'Delete permanently')),
              ),
            ],
          ),
        );
      },
    );
    // Let the dialog route finish its exit animation before releasing the controller.
    await Future<void>.delayed(kThemeAnimationDuration);
    controller.dispose();
    if (!mounted || confirmed != true) return;

    setState(() => _deletingAllLocalData = true);
    try {
      final result = await StorageUsageService.deleteAllLocalData(phrase);
      if (!mounted) return;
      if (!result.started) {
        final activeTasks = result.reason == 'ACTIVE_TASKS';
        showToast(
          activeTasks
              ? _t(
                  context,
                  '仍有 AI 任务正在运行，已安全中止删除。请先停止任务后重试。',
                  'Deletion was safely aborted because AI tasks are still running. Stop them and try again.',
                )
              : _t(
                  context,
                  '系统未开始清除，本机数据保持不变。',
                  'The system did not start clearing; local data is unchanged.',
                ),
          type: ToastType.error,
        );
      }
    } catch (_) {
      if (!mounted) return;
      showToast(
        _t(
          context,
          '系统未开始清除，本机数据保持不变。',
          'The system did not start clearing; local data is unchanged.',
        ),
        type: ToastType.error,
      );
    } finally {
      if (mounted) setState(() => _deletingAllLocalData = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final summary = _summary;
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: _t(context, '存储占用', 'Storage Usage'),
        primary: true,
      ),
      body: _loading
          ? Center(
              child: CircularProgressIndicator(
                valueColor: AlwaysStoppedAnimation<Color>(
                  palette.accentPrimary,
                ),
              ),
            )
          : summary == null
          ? _buildErrorView()
          : RefreshIndicator(
              color: palette.accentPrimary,
              onRefresh: _loadSummary,
              child: ListView(
                padding: edgeToEdgeScrollPadding(
                  context,
                  const EdgeInsets.fromLTRB(18, 12, 18, 24),
                ),
                children: [
                  SettingsSectionTitle(
                    label: _t(context, '存储概览', 'Storage overview'),
                    subtitle: _t(
                      context,
                      '查看当前占用总量、可清理空间和最近变化',
                      'Review total usage, cleanable space, and recent changes',
                    ),
                  ),
                  _buildOverviewSection(summary),
                  if (summary.strategyPresets.isNotEmpty) ...[
                    const SizedBox(height: 18),
                    SettingsSectionTitle(
                      label: _t(context, '清理建议', 'Cleanup suggestions'),
                      subtitle: _t(
                        context,
                        '根据当前占用情况执行推荐的清理策略',
                        'Run recommended cleanup actions based on current usage',
                      ),
                    ),
                    _buildStrategySection(summary),
                  ],
                  const SizedBox(height: 18),
                  SettingsSectionTitle(
                    label: _t(context, '分类占用', 'Usage by category'),
                    subtitle: _t(
                      context,
                      '查看各类数据占用，并对可清理项进行处理',
                      'Review occupied categories and clean removable data',
                    ),
                  ),
                  _buildCategorySection(summary),
                  const SizedBox(height: 24),
                  SettingsSectionTitle(
                    label: _t(context, '本机数据重置', 'Local data reset'),
                    subtitle: _t(
                      context,
                      '仅清除这台设备上的 OmniBot 私有数据，不删除云端账号或公共文件',
                      'Clear only OmniBot private data on this device; cloud accounts and shared files are kept',
                    ),
                  ),
                  Card(
                    color: palette.surfacePrimary,
                    child: ListTile(
                      leading: Icon(
                        Icons.delete_forever_outlined,
                        color: Theme.of(context).colorScheme.error,
                      ),
                      title: Text(
                        _t(context, '删除全部本机数据', 'Delete all local data'),
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                      subtitle: Text(
                        _t(
                          context,
                          '永久删除本机聊天、工作区、凭据、配置和缓存',
                          'Permanently delete local chats, workspaces, credentials, settings, and caches',
                        ),
                      ),
                      trailing: _deletingAllLocalData
                          ? const SizedBox.square(
                              dimension: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.chevron_right_rounded),
                      onTap: _deletingAllLocalData ? null : _deleteAllLocalData,
                    ),
                  ),
                ],
              ),
            ),
    );
  }

  Widget _buildErrorView() {
    final palette = context.omniPalette;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            _error ?? _t(context, '加载失败', 'Load failed'),
            style: TextStyle(color: palette.textSecondary),
          ),
          const SizedBox(height: 12),
          FilledButton(
            onPressed: _loadSummary,
            child: Text(_t(context, '重新分析', 'Analyze again')),
          ),
        ],
      ),
    );
  }

  Widget _buildOverviewSection(StorageUsageSummary summary) {
    final palette = context.omniPalette;
    final sourceText = _metricsSourceText(summary.metricsSource);
    final trend = summary.trend;
    final hasBothTotals =
        summary.systemTotalBytes > 0 && summary.scanTotalBytes > 0;
    final diffBytes = summary.systemTotalBytes - summary.scanTotalBytes;
    final totalDeltaText = _signedBytes(trend.deltaTotalBytes);
    final cleanableDeltaText = _signedBytes(trend.deltaCleanableBytes);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _t(context, '总占用', 'Total usage'),
                    style: TextStyle(
                      fontSize: 12,
                      color: palette.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    _formatBytes(summary.totalBytes),
                    style: TextStyle(
                      fontSize: 28,
                      fontWeight: FontWeight.w700,
                      color: palette.textPrimary,
                    ),
                  ),
                ],
              ),
            ),
            TextButton.icon(
              onPressed: _loadSummary,
              icon: const Icon(LucideIcons.refreshCw, size: 16),
              label: Text(_t(context, '重新分析', 'Analyze again')),
            ),
          ],
        ),
        const SizedBox(height: 12),
        _buildOverviewMetricRow(
          _t(context, '应用大小', 'App size'),
          _formatBytes(summary.appBinaryBytes),
        ),
        _buildSectionDivider(),
        _buildOverviewMetricRow(
          _t(context, '用户数据', 'User data'),
          _formatBytes(summary.userDataBytes),
        ),
        _buildSectionDivider(),
        _buildOverviewMetricRow(
          _t(context, '可清理', 'Cleanable'),
          _formatBytes(summary.cleanableBytes),
          valueColor: summary.cleanableBytes > 0 ? palette.accentPrimary : null,
        ),
        const SizedBox(height: 12),
        Text(
          _t(
            context,
            '最后分析：${_formatDateTime(summary.generatedAt)}',
            'Analyzed at: ${_formatDateTime(summary.generatedAt)}',
          ),
          style: TextStyle(fontSize: 12, color: palette.textSecondary),
        ),
        const SizedBox(height: 4),
        Text(
          _t(context, '统计口径：$sourceText', 'Metrics source: $sourceText'),
          style: TextStyle(fontSize: 12, color: palette.textSecondary),
        ),
        if (summary.packageName.isNotEmpty) ...[
          const SizedBox(height: 4),
          Text(
            _t(
              context,
              '当前包名：${summary.packageName}',
              'Package: ${summary.packageName}',
            ),
            style: TextStyle(fontSize: 12, color: palette.textSecondary),
          ),
        ],
        if (hasBothTotals && diffBytes != 0) ...[
          const SizedBox(height: 4),
          Text(
            _t(
              context,
              '系统口径与扫描口径差异：${_signedBytes(diffBytes)}',
              'Delta between system and scan totals: ${_signedBytes(diffBytes)}',
            ),
            style: TextStyle(fontSize: 12, color: palette.textSecondary),
          ),
        ],
        const SizedBox(height: 12),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              LucideIcons.trendingUp,
              size: 18,
              color: palette.accentPrimary,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                trend.hasPrevious
                    ? _t(
                        context,
                        '较上次分析：总占用 $totalDeltaText，可清理 $cleanableDeltaText',
                        'Since last analysis: total $totalDeltaText, cleanable $cleanableDeltaText',
                      )
                    : _t(
                        context,
                        '这是首次分析，后续将展示占用变化趋势',
                        'This is the first analysis. Trend data will appear next time.',
                      ),
                style: TextStyle(
                  fontSize: 12,
                  height: 1.5,
                  color: palette.textSecondary,
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildOverviewMetricRow(
    String title,
    String value, {
    Color? valueColor,
  }) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Expanded(
            child: Text(
              title,
              style: TextStyle(fontSize: 13, color: palette.textSecondary),
            ),
          ),
          const SizedBox(width: 12),
          Text(
            value,
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: valueColor ?? palette.textPrimary,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSectionDivider() {
    final palette = context.omniPalette;
    return Divider(
      height: 1,
      thickness: 1,
      color: palette.borderSubtle.withValues(
        alpha: context.isDarkTheme ? 0.56 : 0.8,
      ),
    );
  }

  Widget _buildStrategySection(StorageUsageSummary summary) {
    final palette = context.omniPalette;
    final colorScheme = Theme.of(context).colorScheme;
    final presets = summary.strategyPresets;
    if (presets.isEmpty) {
      return const SizedBox.shrink();
    }

    return Column(
      children: List.generate(presets.length, (index) {
        final preset = presets[index];
        final applying = _applyingStrategyId == preset.id;
        return Column(
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        preset.name,
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          color: palette.textPrimary,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        preset.description,
                        style: TextStyle(
                          fontSize: 12,
                          height: 1.5,
                          color: palette.textSecondary,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 12),
                FilledButton(
                  style: FilledButton.styleFrom(
                    minimumSize: const Size(72, 34),
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    backgroundColor: palette.accentPrimary,
                    disabledBackgroundColor: palette.borderStrong,
                    foregroundColor: colorScheme.onPrimary,
                  ),
                  onPressed: applying ? null : () => _applyStrategy(preset),
                  child: applying
                      ? SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            valueColor: AlwaysStoppedAnimation<Color>(
                              colorScheme.onPrimary,
                            ),
                          ),
                        )
                      : Text(_t(context, '执行', 'Run')),
                ),
              ],
            ),
            if (index != presets.length - 1) ...[
              const SizedBox(height: 14),
              _buildSectionDivider(),
              const SizedBox(height: 14),
            ],
          ],
        );
      }),
    );
  }

  Widget _buildCategorySection(StorageUsageSummary summary) {
    final palette = context.omniPalette;
    final colorScheme = Theme.of(context).colorScheme;
    final categories = summary.categories.toList();
    final colorMap = _buildCategoryColorMap(categories);
    if (categories.isEmpty) {
      return Text(
        _t(context, '暂无可展示数据', 'No storage data available'),
        style: TextStyle(fontSize: 12, color: palette.textSecondary),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildCategoryVisualization(summary, categories, colorMap),
        const SizedBox(height: 12),
        Text(
          _t(
            context,
            '最后分析：${_formatDateTime(summary.generatedAt)}',
            'Analyzed at: ${_formatDateTime(summary.generatedAt)}',
          ),
          style: TextStyle(fontSize: 12, color: palette.textSecondary),
        ),
        const SizedBox(height: 14),
        ...categories.asMap().entries.map((entry) {
          final index = entry.key;
          final category = entry.value;
          final percent = summary.totalBytes > 0
              ? category.bytes / summary.totalBytes * 100
              : 0.0;
          final isClearing = _clearingCategoryId == category.id;
          return Column(
            children: [
              _buildCategoryRow(
                category: category,
                percent: percent,
                color: colorMap[category.id] ?? palette.textTertiary,
                colorScheme: colorScheme,
                isClearing: isClearing,
              ),
              if (index != categories.length - 1) ...[
                const SizedBox(height: 14),
                _buildSectionDivider(),
                const SizedBox(height: 14),
              ],
            ],
          );
        }),
      ],
    );
  }

  Widget _buildCategoryVisualization(
    StorageUsageSummary summary,
    List<StorageUsageCategory> categories,
    Map<String, Color> colorMap,
  ) {
    final palette = context.omniPalette;
    final visibleCategories = categories
        .where((item) => item.bytes > 0)
        .toList();
    if (visibleCategories.isEmpty || summary.totalBytes <= 0) {
      return const SizedBox.shrink();
    }

    final segments = _buildChartSegments(visibleCategories, colorMap);
    final legendEntries = segments.take(5).toList();

    return LayoutBuilder(
      builder: (context, constraints) {
        final compact = constraints.maxWidth < 380;
        final pie = Center(
          child: _StorageUsagePieChart(
            totalBytes: summary.totalBytes,
            segments: segments,
            trackColor: palette.segmentTrack,
            centerTextColor: palette.textPrimary,
            size: compact ? 156 : 172,
            strokeWidth: compact ? 18 : 20,
          ),
        );

        final legend = Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              _t(context, '占用分布', 'Usage distribution'),
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: palette.textPrimary,
              ),
            ),
            const SizedBox(height: 8),
            ...legendEntries.map((segment) {
              final percent = summary.totalBytes > 0
                  ? segment.bytes / summary.totalBytes * 100
                  : 0.0;
              return Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  children: [
                    Container(
                      width: 8,
                      height: 8,
                      decoration: BoxDecoration(
                        color: segment.color,
                        shape: BoxShape.circle,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        segment.label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 12,
                          color: palette.textPrimary,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(
                      '${percent.toStringAsFixed(1)}%',
                      style: TextStyle(
                        fontSize: 11,
                        color: palette.textSecondary,
                      ),
                    ),
                  ],
                ),
              );
            }),
            Text(
              _t(
                context,
                '饼图仅作为分类占用的快速预览，详细信息见下方列表',
                'The pie chart is a quick preview; see the list below for details',
              ),
              style: TextStyle(fontSize: 11, color: palette.textSecondary),
            ),
          ],
        );

        if (compact) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [pie, const SizedBox(height: 12), legend],
          );
        }

        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(flex: 4, child: pie),
            const SizedBox(width: 20),
            Expanded(flex: 5, child: legend),
          ],
        );
      },
    );
  }

  Widget _buildCategoryRow({
    required StorageUsageCategory category,
    required double percent,
    required Color color,
    required ColorScheme colorScheme,
    required bool isClearing,
  }) {
    final palette = context.omniPalette;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 8,
          height: 8,
          margin: const EdgeInsets.only(top: 6),
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Text(
                      category.name,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: palette.textPrimary,
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Text(
                    _formatBytes(category.bytes),
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: palette.textPrimary,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                category.description,
                style: TextStyle(
                  fontSize: 12,
                  height: 1.5,
                  color: palette.textSecondary,
                ),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 6,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text(
                    _t(
                      context,
                      '占比 ${percent.toStringAsFixed(1)}%',
                      'Share ${percent.toStringAsFixed(1)}%',
                    ),
                    style: TextStyle(
                      fontSize: 11,
                      color: palette.textSecondary,
                    ),
                  ),
                  _buildRiskTag(category.riskLevel),
                  if (!category.cleanable)
                    Text(
                      _t(context, '当前不可清理', 'Not cleanable'),
                      style: TextStyle(
                        fontSize: 11,
                        color: palette.textSecondary,
                      ),
                    ),
                ],
              ),
              if (category.breakdown.isNotEmpty) ...[
                const SizedBox(height: 8),
                _buildCategoryBreakdownFlat(category.breakdown),
              ],
            ],
          ),
        ),
        if (category.cleanable) ...[
          const SizedBox(width: 12),
          FilledButton(
            style: FilledButton.styleFrom(
              minimumSize: const Size(64, 32),
              padding: const EdgeInsets.symmetric(horizontal: 12),
              backgroundColor: palette.accentPrimary,
              disabledBackgroundColor: palette.borderStrong,
              foregroundColor: colorScheme.onPrimary,
            ),
            onPressed: isClearing ? null : () => _onClearCategory(category),
            child: isClearing
                ? SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      valueColor: AlwaysStoppedAnimation<Color>(
                        colorScheme.onPrimary,
                      ),
                    ),
                  )
                : Text(_t(context, '清理', 'Clean')),
          ),
        ],
      ],
    );
  }

  Widget _buildCategoryBreakdownFlat(List<StorageUsageBreakdownEntry> entries) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          _t(context, '细项', 'Breakdown'),
          style: TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w600,
            color: palette.textSecondary,
          ),
        ),
        const SizedBox(height: 4),
        ...entries.map((entry) {
          return Padding(
            padding: const EdgeInsets.only(bottom: 2),
            child: Text(
              '${entry.label} · ${_formatBytes(entry.bytes)}',
              style: TextStyle(
                fontSize: 11,
                height: 1.4,
                color: palette.textSecondary,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          );
        }),
      ],
    );
  }

  String _translateHint(String raw) {
    final l = context.l10n;
    if (raw.contains('历史未释放') || raw.contains('conversation_history')) {
      return l.storageHintConversation;
    }
    if (raw.contains('终端运行时被清理') || raw.contains('terminal')) {
      return l.storageHintTerminal;
    }
    if (raw.contains('当前不可清理')) {
      return l.storageHintNotCleanable;
    }
    if (raw.contains('已跳过') || raw.contains('skipped')) {
      return l.storageHintSkipped;
    }
    return l.storageHintGeneral;
  }

  Widget _buildRiskTag(String riskLevel) {
    final isDark = context.isDarkTheme;
    late final String text;
    late final Color bgColor;
    late final Color fgColor;
    switch (riskLevel) {
      case 'safe':
        text = _t(context, '低风险', 'Low risk');
        bgColor = isDark ? const Color(0xFF1D4C38) : const Color(0xFFE6F8F0);
        fgColor = isDark ? const Color(0xFF7CE2B4) : const Color(0xFF0E9F6E);
        break;
      case 'caution':
        text = _t(context, '谨慎', 'Caution');
        bgColor = isDark ? const Color(0xFF4E3C1E) : const Color(0xFFFFF4E5);
        fgColor = isDark ? const Color(0xFFFFD37A) : const Color(0xFFB76E00);
        break;
      case 'dangerous':
        text = _t(context, '高风险', 'High risk');
        bgColor = isDark ? const Color(0xFF4C2225) : const Color(0xFFFFECEC);
        fgColor = isDark ? const Color(0xFFFF9A9A) : const Color(0xFFCC3C3C);
        break;
      default:
        text = _t(context, '只读', 'Read-only');
        bgColor = isDark ? const Color(0xFF2B3138) : const Color(0xFFF1F5F9);
        fgColor = isDark ? const Color(0xFFB0BCCB) : const Color(0xFF475569);
        break;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: bgColor,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: fgColor.withValues(alpha: 0.24)),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 10,
          color: fgColor,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }

  Map<String, Color> _buildCategoryColorMap(
    List<StorageUsageCategory> categories,
  ) {
    final palette = context.isDarkTheme
        ? _segmentPaletteDark
        : _segmentPaletteLight;
    final colorMap = <String, Color>{};
    for (int index = 0; index < categories.length; index++) {
      colorMap[categories[index].id] = palette[index % palette.length];
    }
    return colorMap;
  }

  List<_PieChartSegment> _buildChartSegments(
    List<StorageUsageCategory> categories,
    Map<String, Color> colorMap,
  ) {
    final fallbackColor = context.isDarkTheme
        ? const Color(0xFF9AA4B2)
        : const Color(0xFF94A3B8);
    final sorted = [...categories]..sort((a, b) => b.bytes.compareTo(a.bytes));
    if (sorted.length <= 7) {
      return sorted.map((item) {
        return _PieChartSegment(
          item.name,
          item.bytes,
          colorMap[item.id] ?? fallbackColor,
        );
      }).toList();
    }
    final head = sorted.take(6).toList();
    final tailBytes = sorted
        .skip(6)
        .fold<int>(0, (sum, item) => sum + item.bytes);
    return [
      ...head.map(
        (item) => _PieChartSegment(
          item.name,
          item.bytes,
          colorMap[item.id] ?? fallbackColor,
        ),
      ),
      _PieChartSegment(_t(context, '其他', 'Others'), tailBytes, fallbackColor),
    ];
  }

  String _signedBytes(int bytes) {
    if (bytes == 0) return '0 B';
    final sign = bytes > 0 ? '+' : '-';
    return '$sign${_formatBytes(bytes.abs())}';
  }

  String _metricsSourceText(String source) {
    switch (source) {
      case 'system_storage_stats':
        return _t(
          context,
          '系统统计（与系统设置更接近）',
          'System stats (closer to Settings)',
        );
      case 'filesystem_estimate':
      default:
        return _t(context, '目录扫描估算', 'Directory scan estimate');
    }
  }

  String _formatBytes(int bytes) {
    if (bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    double size = bytes.toDouble();
    int unitIndex = 0;
    while (size >= 1024 && unitIndex < units.length - 1) {
      size /= 1024;
      unitIndex++;
    }
    final fixed = size >= 100
        ? size.toStringAsFixed(0)
        : size.toStringAsFixed(1);
    return '$fixed ${units[unitIndex]}';
  }

  String _formatDateTime(int timestampMs) {
    if (timestampMs <= 0) return '-';
    final dt = DateTime.fromMillisecondsSinceEpoch(timestampMs);
    String two(int value) => value.toString().padLeft(2, '0');
    return '${dt.year}-${two(dt.month)}-${two(dt.day)} ${two(dt.hour)}:${two(dt.minute)}';
  }
}

class _PieChartSegment {
  const _PieChartSegment(this.label, this.bytes, this.color);
  final String label;
  final int bytes;
  final Color color;
}

class _StorageUsagePieChart extends StatelessWidget {
  const _StorageUsagePieChart({
    required this.totalBytes,
    required this.segments,
    required this.trackColor,
    required this.centerTextColor,
    this.size = 210,
    this.strokeWidth = 20,
  });

  final int totalBytes;
  final List<_PieChartSegment> segments;
  final Color trackColor;
  final Color centerTextColor;
  final double size;
  final double strokeWidth;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: Stack(
        alignment: Alignment.center,
        children: [
          CustomPaint(
            size: Size.square(size),
            painter: _StorageUsagePiePainter(
              segments: segments,
              trackColor: trackColor,
              strokeWidth: strokeWidth,
            ),
          ),
          Text(
            _formatBytes(totalBytes),
            style: TextStyle(
              fontSize: size >= 190 ? 15 : 13,
              fontWeight: FontWeight.w700,
              color: centerTextColor,
            ),
          ),
        ],
      ),
    );
  }

  String _formatBytes(int bytes) {
    if (bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    double size = bytes.toDouble();
    int unitIndex = 0;
    while (size >= 1024 && unitIndex < units.length - 1) {
      size /= 1024;
      unitIndex++;
    }
    final fixed = size >= 100
        ? size.toStringAsFixed(0)
        : size.toStringAsFixed(1);
    return '$fixed ${units[unitIndex]}';
  }
}

class _StorageUsagePiePainter extends CustomPainter {
  _StorageUsagePiePainter({
    required this.segments,
    required this.trackColor,
    required this.strokeWidth,
  });

  final List<_PieChartSegment> segments;
  final Color trackColor;
  final double strokeWidth;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = math.min(size.width, size.height) / 2 - strokeWidth / 2 - 2;
    final rect = Rect.fromCircle(center: center, radius: radius);
    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..strokeCap = StrokeCap.butt;

    paint.color = trackColor;
    canvas.drawArc(rect, -math.pi / 2, math.pi * 2, false, paint);

    final total = segments.fold<int>(0, (sum, item) => sum + item.bytes);
    if (total <= 0) {
      return;
    }

    double start = -math.pi / 2;
    for (final segment in segments) {
      if (segment.bytes <= 0) continue;
      final sweep = segment.bytes / total * math.pi * 2;
      paint.color = segment.color;
      canvas.drawArc(rect, start, sweep, false, paint);
      start += sweep;
    }
  }

  @override
  bool shouldRepaint(covariant _StorageUsagePiePainter oldDelegate) {
    if (trackColor != oldDelegate.trackColor) return true;
    if (strokeWidth != oldDelegate.strokeWidth) return true;
    if (segments.length != oldDelegate.segments.length) return true;
    for (int i = 0; i < segments.length; i++) {
      if (segments[i].bytes != oldDelegate.segments[i].bytes ||
          segments[i].color != oldDelegate.segments[i].color) {
        return true;
      }
    }
    return false;
  }
}
