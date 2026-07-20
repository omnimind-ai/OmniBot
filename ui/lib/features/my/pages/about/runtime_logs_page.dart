import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/runtime_log_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

const int _runtimeLogLimit = 200;

class RuntimeLogsPage extends StatefulWidget {
  const RuntimeLogsPage({super.key});

  @override
  State<RuntimeLogsPage> createState() => _RuntimeLogsPageState();
}

class _RuntimeLogsPageState extends State<RuntimeLogsPage> {
  List<RuntimeLogEntry> _logs = const [];
  final Set<String> _expandedLogKeys = <String>{};
  bool _isLoading = true;
  String _errorMessage = '';

  @override
  void initState() {
    super.initState();
    _loadLogs();
  }

  Future<void> _loadLogs() async {
    setState(() {
      _isLoading = true;
      _errorMessage = '';
    });
    try {
      final logs = await RuntimeLogService.listRecent(limit: _runtimeLogLimit);
      if (!mounted) return;
      final validKeys = <String>{};
      for (var index = 0; index < logs.length; index++) {
        validKeys.add(_logKey(logs[index], index));
      }
      setState(() {
        _logs = logs;
        _expandedLogKeys.removeWhere((key) => !validKeys.contains(key));
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _errorMessage = e.toString();
      });
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _exportLogs() async {
    try {
      final logs = await RuntimeLogService.listRecent(limit: _runtimeLogLimit);
      if (!mounted) return;
      if (logs.isEmpty) {
        showToast(
          LegacyTextLocalizer.localize('暂无运行日志'),
          type: ToastType.warning,
        );
        return;
      }
      await Clipboard.setData(ClipboardData(text: _buildExportText(logs)));
      if (!mounted) return;
      showToast(
        LegacyTextLocalizer.localize('已复制全部运行日志'),
        type: ToastType.success,
      );
    } catch (_) {
      if (!mounted) return;
      showToast(
        LegacyTextLocalizer.localize('导出运行日志失败'),
        type: ToastType.error,
      );
    }
  }

  Future<void> _clearLogs() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(LegacyTextLocalizer.localize('确定删除吗？')),
        content: Text(
          LegacyTextLocalizer.localize('删除后该内容将不可找回'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(LegacyTextLocalizer.localize('取消')),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(LegacyTextLocalizer.localize('确认')),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await RuntimeLogService.clear();
      if (!mounted) return;
      showToast(
        LegacyTextLocalizer.localize('已删除'),
        type: ToastType.success,
      );
      _loadLogs();
    } catch (e) {
      if (!mounted) return;
      showToast(
        LegacyTextLocalizer.localize('删除失败'),
        type: ToastType.error,
      );
    }
  }

  Future<void> _copyText(String text) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    showToast(
      LegacyTextLocalizer.localize('已复制'),
      type: ToastType.success,
    );
  }

  String _buildExportText(List<RuntimeLogEntry> logs) {
    return logs.map(_formatLogForExport).join('\n\n---\n\n');
  }

  String _formatLogForExport(RuntimeLogEntry log) {
    final buffer = StringBuffer()
      ..writeln('Time: ${_formatDateTime(log.createdAt)}')
      ..writeln('Level: ${log.level}${log.isCrash ? ' (CRASH)' : ''}')
      ..writeln('Tag: ${log.tag.trim().isEmpty ? '-' : log.tag.trim()}')
      ..writeln(
        'Message: ${log.message.trim().isEmpty ? '-' : log.message.trim()}',
      );
    final stackTrace = log.stackTrace?.trim();
    if (stackTrace != null && stackTrace.isNotEmpty) {
      buffer
        ..writeln('StackTrace:')
        ..write(stackTrace);
    }
    return buffer.toString().trimRight();
  }

  String _formatDateTime(DateTime value) {
    String pad(int number) => number.toString().padLeft(2, '0');
    return '${value.year}-${pad(value.month)}-${pad(value.day)} '
        '${pad(value.hour)}:${pad(value.minute)}:${pad(value.second)}';
  }

  String _logKey(RuntimeLogEntry log, int index) {
    return '${log.id}-$index';
  }

  Color _levelColor(String level) {
    return switch (level) {
      'ERROR' || 'ASSERT' => const Color(0xFFD93025),
      'WARN' => const Color(0xFFF9AB00),
      'INFO' => const Color(0xFF1E8E5A),
      _ => const Color(0xFF5F6368),
    };
  }

  int get _crashCount => _logs.where((log) => log.isCrash).length;

  bool get _hasExpandableLogs =>
      _logs.any((log) => log.stackTrace?.trim().isNotEmpty == true);

  void _toggleExpanded(String key) {
    setState(() {
      if (_expandedLogKeys.contains(key)) {
        _expandedLogKeys.remove(key);
      } else {
        _expandedLogKeys.add(key);
      }
    });
  }

  Widget _buildOverviewSection(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SettingsSectionTitle(
          label: LegacyTextLocalizer.localize('概览'),
          subtitle: LegacyTextLocalizer.localize('最近 200 条错误和崩溃日志，按时间倒序展示。'),
        ),
        Row(
          children: [
            Expanded(
              child: _buildOverviewMetric(
                context,
                label: LegacyTextLocalizer.localize('总数'),
                value: _logs.length.toString(),
              ),
            ),
            _buildOverviewDivider(context),
            Expanded(
              child: _buildOverviewMetric(
                context,
                label: LegacyTextLocalizer.localize('崩溃'),
                value: _crashCount.toString(),
                valueColor: const Color(0xFFD93025),
              ),
            ),
            _buildOverviewDivider(context),
            Expanded(
              child: _buildOverviewMetric(
                context,
                label: LegacyTextLocalizer.localize('最近一条'),
                value: _logs.isEmpty ? '-' : _formatDateTime(_logs.first.createdAt).substring(5, 10),
                alignEnd: true,
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildOverviewMetric(
    BuildContext context, {
    required String label,
    required String value,
    Color? valueColor,
    bool alignEnd = false,
  }) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: alignEnd
          ? CrossAxisAlignment.end
          : CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: TextStyle(
            fontFamily: 'PingFang SC',
            fontSize: 11,
            fontWeight: FontWeight.w600,
            letterSpacing: 0.4,
            color: palette.textTertiary,
          ),
        ),
        const SizedBox(height: 6),
        Text(
          value,
          style: TextStyle(
            fontFamily: AppTextStyles.fontFamily,
            fontSize: 22,
            fontWeight: FontWeight.w600,
            height: 1.1,
            color: valueColor ?? palette.textPrimary,
          ),
        ),
      ],
    );
  }

  Widget _buildOverviewDivider(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      width: 1,
      height: 34,
      margin: const EdgeInsets.symmetric(horizontal: 14),
      color: palette.borderSubtle.withValues(
        alpha: context.isDarkTheme ? 0.56 : 0.84,
      ),
    );
  }

  Widget _buildEmptyState(BuildContext context) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(top: 24, bottom: 8),
      child: Text(
        LegacyTextLocalizer.localize('暂无运行日志'),
        style: TextStyle(
          fontFamily: AppTextStyles.fontFamily,
          fontSize: 14,
          height: 1.5,
          color: context.isDarkTheme ? palette.textSecondary : AppColors.text70,
        ),
      ),
    );
  }

  Widget _buildErrorState(BuildContext context) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(top: 20, bottom: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            LegacyTextLocalizer.localize('加载运行日志失败'),
            style: TextStyle(
              fontFamily: AppTextStyles.fontFamily,
              fontSize: 16,
              fontWeight: FontWeight.w600,
              color: palette.textPrimary,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            _errorMessage,
            style: TextStyle(
              fontFamily: AppTextStyles.fontFamily,
              fontSize: 13,
              height: 1.55,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : AppColors.text70,
            ),
          ),
          const SizedBox(height: 16),
          OutlinedButton(
            onPressed: _loadLogs,
            child: Text(LegacyTextLocalizer.localize('重试')),
          ),
        ],
      ),
    );
  }

  Widget _buildLogLeading(BuildContext context, RuntimeLogEntry log) {
    final markerColor = log.isCrash
        ? const Color(0xFFD93025)
        : _levelColor(log.level);
    return SizedBox(
      width: 18,
      height: 18,
      child: Center(
        child: Container(
          width: 6,
          height: 6,
          decoration: BoxDecoration(
            color: markerColor,
            shape: BoxShape.circle,
          ),
        ),
      ),
    );
  }

  Widget _buildLogItem(
    BuildContext context,
    RuntimeLogEntry log,
    int index, {
    required bool isLast,
  }) {
    final palette = context.omniPalette;
    final logKey = _logKey(log, index);
    final isExpanded = _expandedLogKeys.contains(logKey);
    final hasStackTrace = log.stackTrace?.trim().isNotEmpty == true;

    return Column(
      children: [
        Material(
          color: Colors.transparent,
          child: Column(
            children: [
              InkWell(
                onTap: hasStackTrace ? () => _toggleExpanded(logKey) : null,
                borderRadius: BorderRadius.circular(14),
                splashColor: palette.accentPrimary.withValues(alpha: 0.08),
                highlightColor: Colors.transparent,
                child: Padding(
                  padding: EdgeInsets.fromLTRB(4, 14, 2, isExpanded ? 12 : 14),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _buildLogLeading(context, log),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Container(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 6,
                                    vertical: 2,
                                  ),
                                  decoration: BoxDecoration(
                                    color: _levelColor(log.level)
                                        .withValues(alpha: 0.12),
                                    borderRadius: BorderRadius.circular(4),
                                  ),
                                  child: Text(
                                    log.level,
                                    style: TextStyle(
                                      fontFamily: AppTextStyles.fontFamily,
                                      fontSize: 10,
                                      fontWeight: FontWeight.w600,
                                      height: 1.3,
                                      color: _levelColor(log.level),
                                    ),
                                  ),
                                ),
                                if (log.isCrash) ...[
                                  const SizedBox(width: 6),
                                  Container(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 6,
                                      vertical: 2,
                                    ),
                                    decoration: BoxDecoration(
                                      color: const Color(0xFFD93025)
                                          .withValues(alpha: 0.12),
                                      borderRadius: BorderRadius.circular(4),
                                    ),
                                    child: Text(
                                      'CRASH',
                                      style: TextStyle(
                                        fontFamily: AppTextStyles.fontFamily,
                                        fontSize: 10,
                                        fontWeight: FontWeight.w600,
                                        height: 1.3,
                                        color: const Color(0xFFD93025),
                                      ),
                                    ),
                                  ),
                                ],
                              ],
                            ),
                            const SizedBox(height: 6),
                            Text(
                              log.displayTitle,
                              style: TextStyle(
                                fontFamily: AppTextStyles.fontFamily,
                                fontSize: 13.5,
                                fontWeight: FontWeight.w500,
                                height: 1.5,
                                color: palette.textPrimary,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              _formatDateTime(log.createdAt),
                              style: TextStyle(
                                fontFamily: AppTextStyles.fontFamily,
                                fontSize: 11,
                                height: 1.45,
                                color: context.isDarkTheme
                                    ? palette.textSecondary
                                    : AppColors.text70,
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 12),
                      if (hasStackTrace)
                        AnimatedRotation(
                          turns: isExpanded ? 0.5 : 0,
                          duration: const Duration(milliseconds: 180),
                          curve: Curves.easeOutCubic,
                          child: Icon(
                            Icons.expand_more_rounded,
                            size: 18,
                            color: palette.textTertiary,
                          ),
                        ),
                    ],
                  ),
                ),
              ),
              AnimatedSize(
                duration: const Duration(milliseconds: 180),
                curve: Curves.easeOutCubic,
                child: isExpanded && hasStackTrace
                    ? Padding(
                        padding: const EdgeInsets.only(left: 28, bottom: 12),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Divider(
                              height: 1,
                              thickness: 1,
                              color: palette.borderSubtle.withValues(
                                alpha: context.isDarkTheme ? 0.5 : 0.78,
                              ),
                            ),
                            const SizedBox(height: 10),
                            Row(
                              children: [
                                Expanded(
                                  child: Text(
                                    'StackTrace',
                                    style: TextStyle(
                                      fontFamily: AppTextStyles.fontFamily,
                                      fontSize: 12,
                                      fontWeight: FontWeight.w600,
                                      color: palette.textPrimary,
                                    ),
                                  ),
                                ),
                                TextButton(
                                  onPressed: () =>
                                      _copyText(log.stackTrace!),
                                  style: TextButton.styleFrom(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 8,
                                    ),
                                    minimumSize: const Size(48, 30),
                                    tapTargetSize:
                                        MaterialTapTargetSize.shrinkWrap,
                                    visualDensity: VisualDensity.compact,
                                  ),
                                  child: Text(
                                    LegacyTextLocalizer.localize('复制'),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 4),
                            Container(
                              width: double.infinity,
                              padding: const EdgeInsets.all(10),
                              decoration: BoxDecoration(
                                color: context.isDarkTheme
                                    ? palette.surfaceSecondary
                                        .withValues(alpha: 0.62)
                                    : palette.surfaceSecondary
                                        .withValues(alpha: 0.82),
                                borderRadius: BorderRadius.circular(10),
                              ),
                              child: SelectableText(
                                log.stackTrace!,
                                style: TextStyle(
                                  fontFamily: 'monospace',
                                  fontSize: 11,
                                  height: 1.5,
                                  color: context.isDarkTheme
                                      ? palette.textSecondary
                                      : AppColors.text70,
                                ),
                              ),
                            ),
                          ],
                        ),
                      )
                    : const SizedBox.shrink(),
              ),
            ],
          ),
        ),
        if (!isLast)
          Padding(
            padding: const EdgeInsets.only(left: 30),
            child: Divider(
              height: 1,
              thickness: 1,
              color: palette.borderSubtle.withValues(
                alpha: context.isDarkTheme ? 0.5 : 0.78,
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildLogsList(BuildContext context) {
    return Column(
      children: List.generate(_logs.length, (index) {
        return _buildLogItem(
          context,
          _logs[index],
          index,
          isLast: index == _logs.length - 1,
        );
      }),
    );
  }

  Widget _buildContent(BuildContext context) {
    if (_isLoading && _logs.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }

    return RefreshIndicator(
      onRefresh: _loadLogs,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(18, 10, 18, 28),
        children: [
          if (_logs.isNotEmpty) ...[
            _buildOverviewSection(context),
            const SizedBox(height: 24),
          ],
          SettingsSectionTitle(
            label: LegacyTextLocalizer.localize('最近记录'),
            subtitle: _hasExpandableLogs
                ? LegacyTextLocalizer.localize('含堆栈的条目可展开查看。')
                : null,
          ),
          if (_errorMessage.isNotEmpty && _logs.isEmpty)
            _buildErrorState(context)
          else if (_logs.isEmpty)
            _buildEmptyState(context)
          else
            _buildLogsList(context),
        ],
      ),
    );
  }

  Widget _buildAppBarLeading(BuildContext context) {
    final palette = context.omniPalette;
    return Row(
      children: [
        SizedBox(
          width: 56,
          child: Center(
            child: GestureDetector(
              onTap: () => GoRouterManager.pop(),
              child: SizedBox(
                width: 24,
                height: 24,
                child: SvgPicture.asset(
                  'assets/common/chevron_left.svg',
                  width: 24,
                  height: 24,
                  colorFilter: ColorFilter.mode(
                    palette.textPrimary,
                    BlendMode.srcIn,
                  ),
                ),
              ),
            ),
          ),
        ),
        IconButton(
          tooltip: \'Action\', onPressed: _exportLogs,
          icon: const Icon(Icons.content_copy_rounded),
          tooltip: LegacyTextLocalizer.localize('导出运行日志'),
          color: palette.textPrimary,
          iconSize: 18,
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints.tightFor(width: 40, height: 40),
          visualDensity: VisualDensity.compact,
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: LegacyTextLocalizer.localize('运行日志'),
        leading: _buildAppBarLeading(context),
        leadingWidth: 96,
        primary: true,
        actions: [
          IconButton(
            tooltip: \'Action\', onPressed: _loadLogs,
            icon: const Icon(Icons.refresh),
            tooltip: LegacyTextLocalizer.localize('刷新'),
          ),
          if (_logs.isNotEmpty)
            IconButton(
              tooltip: \'Action\', onPressed: _clearLogs,
              icon: const Icon(Icons.delete_outline),
              tooltip: LegacyTextLocalizer.localize('清除'),
            ),
        ],
      ),
      body: _buildContent(context),
    );
  }
}
