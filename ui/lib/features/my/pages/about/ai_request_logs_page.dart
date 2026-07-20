import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/ai_request_log_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

class AiRequestLogsPage extends StatefulWidget {
  const AiRequestLogsPage({super.key});

  @override
  State<AiRequestLogsPage> createState() => _AiRequestLogsPageState();
}

class _AiRequestLogsPageState extends State<AiRequestLogsPage> {
  List<AiRequestLogEntry> _logs = const [];
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
      final logs = await AiRequestLogService.listRecent(limit: 10);
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

  Future<void> _copyJson(String label, String content) async {
    await Clipboard.setData(ClipboardData(text: content));
    if (!mounted) return;
    showToast(
      LegacyTextLocalizer.localize('$label已复制'),
      type: ToastType.success,
    );
  }

  String _formatDateTime(DateTime value) {
    String pad(int number) => number.toString().padLeft(2, '0');
    return '${value.year}-${pad(value.month)}-${pad(value.day)} '
        '${pad(value.hour)}:${pad(value.minute)}:${pad(value.second)}';
  }

  String _logKey(AiRequestLogEntry log, int index) {
    if (log.id.trim().isNotEmpty) {
      return log.id.trim();
    }
    final identity = log.model.isNotEmpty ? log.model : log.label;
    return '${log.createdAt.millisecondsSinceEpoch}-$index-$identity';
  }

  String _buildLogTitle(AiRequestLogEntry log) {
    if (log.model.isNotEmpty) {
      return log.model;
    }
    if (log.label.isNotEmpty) {
      return log.label;
    }
    return LegacyTextLocalizer.localize('AI 请求');
  }

  String _buildSummary(AiRequestLogEntry log) {
    final statusText = log.statusCode == null ? '' : 'HTTP ${log.statusCode}';
    final streamText = LegacyTextLocalizer.localize(log.stream ? '流式' : '非流式');
    final protocolText = switch (log.protocolType) {
      'anthropic' => 'Anthropic',
      'deepseek' => 'DeepSeek',
      _ => 'OpenAI',
    };
    return [
      protocolText,
      streamText,
      statusText,
    ].where((item) => item.isNotEmpty).join(' · ');
  }

  int get _successCount => _logs.where((log) => log.success).length;

  int get _failureCount => _logs.where((log) => !log.success).length;

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
    final palette = context.omniPalette;
    final latestAt = _logs.isEmpty
        ? '-'
        : _formatDateTime(_logs.first.createdAt);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SettingsSectionTitle(
          label: '概览',
          subtitle: '最近 10 条 AI 请求，按时间倒序展示。',
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
                label: LegacyTextLocalizer.localize('成功'),
                value: _successCount.toString(),
                valueColor: const Color(0xFF1E8E5A),
              ),
            ),
            _buildOverviewDivider(context),
            Expanded(
              child: _buildOverviewMetric(
                context,
                label: LegacyTextLocalizer.localize('失败'),
                value: _failureCount.toString(),
                valueColor: const Color(0xFFD93025),
                alignEnd: true,
              ),
            ),
          ],
        ),
        const SizedBox(height: 14),
        Text(
          LegacyTextLocalizer.localize('最近一条'),
          style: TextStyle(
            fontFamily: 'PingFang SC',
            fontSize: 11,
            fontWeight: FontWeight.w600,
            letterSpacing: 0.4,
            color: palette.textTertiary,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          latestAt,
          style: TextStyle(
            fontFamily: AppTextStyles.fontFamily,
            fontSize: 14,
            fontWeight: FontWeight.w500,
            height: 1.45,
            color: palette.textPrimary,
          ),
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

  Widget _buildJsonBlock({
    required BuildContext context,
    required String title,
    required String content,
  }) {
    final palette = context.omniPalette;
    final jsonText = content.trim().isEmpty ? '<empty>' : content;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary.withValues(alpha: 0.62)
            : palette.surfaceSecondary.withValues(alpha: 0.82),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: palette.borderSubtle.withValues(
            alpha: context.isDarkTheme ? 0.9 : 1,
          ),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  title,
                  style: TextStyle(
                    fontFamily: AppTextStyles.fontFamily,
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                  ),
                ),
              ),
              TextButton(
                onPressed: () => _copyJson(title, jsonText),
                style: TextButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  minimumSize: const Size(48, 30),
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  visualDensity: VisualDensity.compact,
                ),
                child: Text(LegacyTextLocalizer.localize('复制')),
              ),
            ],
          ),
          const SizedBox(height: 2),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: _CollapsibleJsonView(content: content),
          ),
        ],
      ),
    );
  }

  Widget _buildEmptyState(BuildContext context) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(top: 24, bottom: 8),
      child: Text(
        LegacyTextLocalizer.localize('最近还没有 AI 请求日志'),
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
            LegacyTextLocalizer.localize('加载请求日志失败'),
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

  Widget _buildLogLeading(BuildContext context, AiRequestLogEntry log) {
    final markerColor = log.success
        ? const Color(0xFF1E8E5A)
        : const Color(0xFFD93025);
    return SizedBox(
      width: 18,
      height: 18,
      child: Center(
        child: Container(
          width: 6,
          height: 6,
          decoration: BoxDecoration(color: markerColor, shape: BoxShape.circle),
        ),
      ),
    );
  }

  Widget _buildLogItem(
    BuildContext context,
    AiRequestLogEntry log,
    int index, {
    required bool isLast,
  }) {
    final palette = context.omniPalette;
    final logKey = _logKey(log, index);
    final isExpanded = _expandedLogKeys.contains(logKey);
    final title = _buildLogTitle(log);
    final secondaryLabel = log.label.trim();
    final showSecondaryLabel =
        secondaryLabel.isNotEmpty && secondaryLabel != title;

    return Column(
      children: [
        Material(
          color: Colors.transparent,
          child: Column(
            children: [
              InkWell(
                onTap: () => _toggleExpanded(logKey),
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
                            Text(
                              title,
                              style: TextStyle(
                                fontFamily: AppTextStyles.fontFamily,
                                fontSize: 14,
                                fontWeight: FontWeight.w500,
                                height: 1.45,
                                color: palette.textPrimary,
                              ),
                            ),
                            if (showSecondaryLabel) ...[
                              const SizedBox(height: 2),
                              Text(
                                secondaryLabel,
                                style: TextStyle(
                                  fontFamily: AppTextStyles.fontFamily,
                                  fontSize: 11,
                                  height: 1.5,
                                  color: palette.textTertiary,
                                ),
                              ),
                            ],
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
                            const SizedBox(height: 4),
                            Text(
                              _buildSummary(log),
                              style: TextStyle(
                                fontFamily: AppTextStyles.fontFamily,
                                fontSize: 11,
                                fontWeight: FontWeight.w500,
                                height: 1.45,
                                color: log.success
                                    ? const Color(0xFF1E8E5A)
                                    : const Color(0xFFD93025),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 12),
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
                child: isExpanded
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
                            const SizedBox(height: 12),
                            const SettingsSectionTitle(
                              label: '基础信息',
                              bottomPadding: 8,
                            ),
                            _buildInfoRow(
                              context,
                              LegacyTextLocalizer.localize('请求地址'),
                              log.url,
                            ),
                            _buildInfoRow(
                              context,
                              LegacyTextLocalizer.localize('请求方法'),
                              log.method,
                            ),
                            if (log.errorMessage.trim().isNotEmpty)
                              _buildInfoRow(
                                context,
                                LegacyTextLocalizer.localize('错误信息'),
                                log.errorMessage,
                              ),
                            const SizedBox(height: 4),
                            const SettingsSectionTitle(
                              label: '载荷',
                              bottomPadding: 8,
                            ),
                            _buildJsonBlock(
                              context: context,
                              title: LegacyTextLocalizer.localize('请求 JSON'),
                              content: log.requestJson,
                            ),
                            const SizedBox(height: 12),
                            _buildJsonBlock(
                              context: context,
                              title: LegacyTextLocalizer.localize('响应 JSON'),
                              content: log.responseJson,
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
            label: '最近记录',
            subtitle: _logs.isEmpty ? null : '点击条目展开查看请求与响应正文。',
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

  Widget _buildInfoRow(BuildContext context, String label, String value) {
    final palette = context.omniPalette;
    final resolvedValue = value.trim().isEmpty ? '-' : value.trim();
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
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
          const SizedBox(height: 4),
          Text(
            resolvedValue,
            style: TextStyle(
              fontFamily: AppTextStyles.fontFamily,
              fontSize: 13,
              height: 1.55,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : AppColors.text70,
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: LegacyTextLocalizer.localize('请求日志'),
        primary: true,
        actions: [
          IconButton(
            tooltip: \'Action\', onPressed: _loadLogs,
            icon: const Icon(Icons.refresh),
            tooltip: LegacyTextLocalizer.localize('刷新'),
          ),
        ],
      ),
      body: _buildContent(context),
    );
  }
}

/// 可折叠的 JSON 查看器
class _CollapsibleJsonView extends StatelessWidget {
  const _CollapsibleJsonView({required this.content});

  final String content;

  @override
  Widget build(BuildContext context) {
    if (content.trim().isEmpty) {
      return Text('<empty>', style: _monoStyle(context));
    }
    try {
      final decoded = jsonDecode(content);
      return _JsonNode(data: decoded, initiallyExpanded: false);
    } catch (_) {
      // JSON 解析失败时回退到纯文本显示
      return SelectableText(content, style: _monoStyle(context));
    }
  }

  TextStyle _monoStyle(BuildContext context) {
    final palette = context.omniPalette;
    return TextStyle(
      fontFamily: 'monospace',
      fontSize: 12,
      height: 1.5,
      color: context.isDarkTheme ? palette.textSecondary : AppColors.text70,
    );
  }
}

/// 递归渲染单个 JSON 节点（对象、数组或叶子值）
class _JsonNode extends StatefulWidget {
  const _JsonNode({
    this.fieldKey,
    required this.data,
    this.initiallyExpanded = false,
    this.isLast = true,
  });

  final String? fieldKey;
  final dynamic data;
  final bool initiallyExpanded;
  final bool isLast;

  @override
  State<_JsonNode> createState() => _JsonNodeState();
}

class _JsonNodeState extends State<_JsonNode> {
  late bool _expanded;

  @override
  void initState() {
    super.initState();
    _expanded = widget.initiallyExpanded;
  }

  bool get _isExpandable =>
      widget.data is Map ||
      (widget.data is List && (widget.data as List).isNotEmpty);

  String _collapsedPreview() {
    if (widget.data is Map) {
      final map = widget.data as Map;
      return '{ ${map.length} 个字段 }';
    }
    if (widget.data is List) {
      final list = widget.data as List;
      return '[ ${list.length} 项 ]';
    }
    return '';
  }

  String _formatLeafValue(dynamic value) {
    if (value == null) return 'null';
    if (value is String) return '"$value"';
    return value.toString();
  }

  Color _valueColor(BuildContext context, dynamic value) {
    if (value == null) return const Color(0xFF9E9E9E);
    if (value is bool) return const Color(0xFF1E88E5);
    if (value is num) return const Color(0xFF00897B);
    if (value is String) return const Color(0xFFC62828);
    return context.isDarkTheme
        ? context.omniPalette.textSecondary
        : AppColors.text70;
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final keyStyle = TextStyle(
      fontFamily: 'monospace',
      fontSize: 12,
      height: 1.5,
      fontWeight: FontWeight.w600,
      color: context.isDarkTheme
          ? const Color(0xFF82AAFF)
          : const Color(0xFF1565C0),
    );
    final punctuationStyle = TextStyle(
      fontFamily: 'monospace',
      fontSize: 12,
      height: 1.5,
      color: context.isDarkTheme ? palette.textSecondary : AppColors.text70,
    );
    final trailing = widget.isLast ? '' : ',';

    // 叶子节点
    if (!_isExpandable) {
      if (widget.data is List && (widget.data as List).isEmpty) {
        return _buildLine(
          context,
          children: [
            if (widget.fieldKey != null) ...[
              Text('"${widget.fieldKey}"', style: keyStyle),
              Text(': ', style: punctuationStyle),
            ],
            Text('[]$trailing', style: punctuationStyle),
          ],
        );
      }
      return _buildLine(
        context,
        children: [
          if (widget.fieldKey != null) ...[
            Text('"${widget.fieldKey}"', style: keyStyle),
            Text(': ', style: punctuationStyle),
          ],
          Text(
            '${_formatLeafValue(widget.data)}$trailing',
            style: TextStyle(
              fontFamily: 'monospace',
              fontSize: 12,
              height: 1.5,
              color: _valueColor(context, widget.data),
            ),
          ),
        ],
      );
    }

    // 可展开节点
    final isMap = widget.data is Map;
    final openBracket = isMap ? '{' : '[';
    final closeBracket = isMap ? '}' : ']';

    if (!_expanded) {
      return _buildToggleLine(
        context,
        children: [
          if (widget.fieldKey != null) ...[
            Text('"${widget.fieldKey}"', style: keyStyle),
            Text(': ', style: punctuationStyle),
          ],
          Text(
            _collapsedPreview() + trailing,
            style: TextStyle(
              fontFamily: 'monospace',
              fontSize: 12,
              height: 1.5,
              color: context.isDarkTheme
                  ? palette.textSecondary.withValues(alpha: 0.7)
                  : AppColors.text70.withValues(alpha: 0.7),
            ),
          ),
        ],
      );
    }

    // 展开状态
    final List<Widget> children = [];

    // 开括号行
    children.add(
      _buildToggleLine(
        context,
        children: [
          if (widget.fieldKey != null) ...[
            Text('"${widget.fieldKey}"', style: keyStyle),
            Text(': ', style: punctuationStyle),
          ],
          Text(openBracket, style: punctuationStyle),
        ],
      ),
    );

    // 子元素
    if (isMap) {
      final map = widget.data as Map;
      final entries = map.entries.toList();
      for (var i = 0; i < entries.length; i++) {
        children.add(
          Padding(
            padding: const EdgeInsets.only(left: 16),
            child: _JsonNode(
              fieldKey: entries[i].key.toString(),
              data: entries[i].value,
              initiallyExpanded: false,
              isLast: i == entries.length - 1,
            ),
          ),
        );
      }
    } else {
      final list = widget.data as List;
      for (var i = 0; i < list.length; i++) {
        children.add(
          Padding(
            padding: const EdgeInsets.only(left: 16),
            child: _JsonNode(
              data: list[i],
              initiallyExpanded: false,
              isLast: i == list.length - 1,
            ),
          ),
        );
      }
    }

    // 闭括号行
    children.add(
      _buildLine(
        context,
        children: [Text('$closeBracket$trailing', style: punctuationStyle)],
      ),
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: children,
    );
  }

  Widget _buildLine(BuildContext context, {required List<Widget> children}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 1),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(width: 18), // 对齐展开箭头的空间
          ...children,
        ],
      ),
    );
  }

  Widget _buildToggleLine(
    BuildContext context, {
    required List<Widget> children,
  }) {
    return GestureDetector(
      onTap: () => setState(() => _expanded = !_expanded),
      behavior: HitTestBehavior.opaque,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 1),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 18,
              height: 18,
              child: Icon(
                _expanded ? Icons.arrow_drop_down : Icons.arrow_right,
                size: 18,
                color: context.isDarkTheme
                    ? context.omniPalette.textSecondary
                    : AppColors.text70,
              ),
            ),
            ...children,
          ],
        ),
      ),
    );
  }
}
