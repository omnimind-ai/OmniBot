import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:ui/features/task/run_log/run_log_metrics.dart';
import 'package:ui/theme/theme_context.dart';

class RunLogOverviewPanel extends StatelessWidget {
  const RunLogOverviewPanel({
    super.key,
    required this.payload,
    required this.metrics,
    required this.stepCount,
  });

  final Map<String, dynamic> payload;
  final RunLogMetrics metrics;
  final int stepCount;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final status = _RunLogStatus.fromPayload(context, payload);
    final goal = _string(payload['goal']);
    final usage = metrics.tokenUsage;
    return Container(
      key: const ValueKey('run-log-overview-panel'),
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Color.alphaBlend(
          status.color.withValues(alpha: context.isDarkTheme ? 0.12 : 0.05),
          palette.surfacePrimary,
        ),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: status.color.withValues(alpha: 0.22)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 30,
                height: 30,
                decoration: BoxDecoration(
                  color: status.color.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(9),
                ),
                child: Icon(status.icon, size: 17, color: status.color),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      status.title,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 14,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    if (goal.isNotEmpty) ...[
                      const SizedBox(height: 3),
                      Text(
                        goal,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 12,
                          height: 1.35,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 7,
            runSpacing: 7,
            children: [
              _MetricPill(
                label: _text(context, '步骤', 'Steps'),
                value: '$stepCount',
              ),
              if (metrics.startedAt != null)
                _MetricPill(
                  label: _text(context, '开始', 'Started'),
                  value: formatRunLogTimestamp(metrics.startedAt!),
                ),
              if (metrics.durationMs != null)
                _MetricPill(
                  label: _text(context, '耗时', 'Duration'),
                  value: formatRunLogDuration(metrics.durationMs!),
                ),
              if (metrics.model != null)
                _MetricPill(
                  label: _text(context, '模型', 'Model'),
                  value: metrics.model!,
                ),
              if (metrics.callCount != null)
                _MetricPill(
                  label: _text(context, '调用', 'Calls'),
                  value: '${metrics.callCount}',
                ),
              if (usage.totalTokens != null)
                _MetricPill(
                  label: _text(context, '模型用量', 'Tokens'),
                  value: formatRunLogTokens(usage.totalTokens!),
                  emphasis: true,
                )
              else
                _MetricPill(
                  label: _text(context, '模型用量', 'Tokens'),
                  value: _text(context, '未提供', 'Unavailable'),
                ),
              if (usage.promptTokens != null)
                _MetricPill(
                  label: _text(context, '输入', 'Prompt'),
                  value: formatRunLogTokens(usage.promptTokens!),
                ),
              if (usage.completionTokens != null)
                _MetricPill(
                  label: _text(context, '输出', 'Completion'),
                  value: formatRunLogTokens(usage.completionTokens!),
                ),
              if (usage.cachedTokens != null)
                _MetricPill(
                  label: _text(context, '缓存', 'Cached'),
                  value: formatRunLogTokens(usage.cachedTokens!),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class RunLogTimelineStepCard extends StatelessWidget {
  const RunLogTimelineStepCard({
    super.key,
    required this.step,
    required this.fallbackIndex,
    required this.isLast,
    required this.tokenUsage,
    required this.onTap,
  });

  final Map<String, dynamic> step;
  final int fallbackIndex;
  final bool isLast;
  final RunLogTokenUsage? tokenUsage;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final action = _map(step['action']);
    final result = _map(step['result']);
    final metadata = _map(step['metadata']);
    final tool = _string(action['tool']).isEmpty
        ? _string(action['type'])
        : _string(action['tool']);
    final args = _map(action['args']);
    final success = result['success'] != false;
    final stepNumber = fallbackIndex + 1;
    final title = _actionTitle(context, tool, args);
    final source = _sourceLabel(context, metadata);
    final summary = _firstText([metadata['summary'], result['message']]);
    final durationMs = _integer(
      metadata['duration_ms'] ?? result['duration_ms'],
    );
    final statusColor = success ? _successColor(context) : _errorColor(context);
    final lineColor = palette.borderSubtle;
    return IntrinsicHeight(
      key: ValueKey('run-log-step-$fallbackIndex'),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            width: 22,
            child: Column(
              children: [
                Container(
                  width: 9,
                  height: 9,
                  margin: const EdgeInsets.only(top: 16),
                  decoration: BoxDecoration(
                    color: statusColor,
                    shape: BoxShape.circle,
                    boxShadow: [
                      BoxShadow(
                        color: statusColor.withValues(alpha: 0.28),
                        blurRadius: 5,
                      ),
                    ],
                  ),
                ),
                if (!isLast)
                  Expanded(
                    child: Center(
                      child: Container(width: 1.5, color: lineColor),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Container(
              margin: EdgeInsets.only(bottom: isLast ? 0 : 10),
              decoration: BoxDecoration(
                color: palette.surfacePrimary,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: palette.borderSubtle),
              ),
              child: Material(
                color: Colors.transparent,
                borderRadius: BorderRadius.circular(12),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  onTap: onTap,
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Text(
                              _text(
                                context,
                                '第 $stepNumber 步',
                                'Step $stepNumber',
                              ),
                              style: TextStyle(
                                color: palette.textSecondary,
                                fontSize: 11,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            if (source.isNotEmpty) ...[
                              const SizedBox(width: 6),
                              _SourceBadge(label: source),
                            ],
                            const Spacer(),
                            if (durationMs != null)
                              _TinyMetric(
                                value: formatRunLogDuration(durationMs),
                              ),
                            if (tokenUsage?.totalTokens != null) ...[
                              const SizedBox(width: 7),
                              _TinyMetric(
                                value: _text(
                                  context,
                                  '用量 ${formatRunLogTokens(tokenUsage!.totalTokens!)}',
                                  '${formatRunLogTokens(tokenUsage!.totalTokens!)} tokens',
                                ),
                              ),
                            ],
                            const SizedBox(width: 7),
                            Icon(
                              success
                                  ? Icons.check_circle_outline_rounded
                                  : Icons.error_outline_rounded,
                              size: 15,
                              color: statusColor,
                            ),
                            const SizedBox(width: 2),
                            Icon(
                              Icons.chevron_right_rounded,
                              size: 17,
                              color: palette.textTertiary,
                            ),
                          ],
                        ),
                        const SizedBox(height: 7),
                        Text(
                          title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: palette.textPrimary,
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            height: 1.25,
                          ),
                        ),
                        if (summary.isNotEmpty && summary != title) ...[
                          const SizedBox(height: 5),
                          Text(
                            summary,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: palette.textSecondary,
                              fontSize: 12,
                              height: 1.3,
                            ),
                          ),
                        ],
                        if (tool.isNotEmpty) ...[
                          const SizedBox(height: 6),
                          Text(
                            tool,
                            style: TextStyle(
                              color: palette.textTertiary,
                              fontSize: 11,
                              fontFamily: 'monospace',
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class RunLogStepDetailSheet extends StatelessWidget {
  const RunLogStepDetailSheet({
    super.key,
    required this.step,
    required this.fallbackIndex,
    required this.tokenUsage,
    required this.onShowState,
  });

  final Map<String, dynamic> step;
  final int fallbackIndex;
  final RunLogTokenUsage? tokenUsage;
  final Future<void> Function(String stateId, Map<String, dynamic> action)
  onShowState;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final action = _map(step['action']);
    final args = _map(action['args']);
    final tool = _string(action['tool']).isEmpty
        ? _string(action['type'])
        : _string(action['tool']);
    // Official OmniFlow RunLog steps carry state ids in observation.auxiliaries;
    // keep accepting the old explicit fields for legacy stored payloads.
    final beforeStateId = _firstText([
      step['before_state_id'],
      _map(_map(step['observation'])['auxiliaries'])['state_id'],
    ]);
    final afterStateId = _firstText([
      step['after_state_id'],
      _map(_map(step['next_observation'])['auxiliaries'])['state_id'],
    ]);
    final metadata = _map(step['metadata']);
    final summary = _string(metadata['summary']);
    final thinking = _string(metadata['thinking']);
    final actionJson = const JsonEncoder.withIndent('  ').convert(action);
    final usage = tokenUsage;
    return SafeArea(
      top: false,
      child: DraggableScrollableSheet(
        key: const ValueKey('run-log-step-detail'),
        expand: false,
        initialChildSize: 0.68,
        minChildSize: 0.38,
        maxChildSize: 0.94,
        builder: (context, controller) => Material(
          color: palette.pageBackground,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(22)),
          clipBehavior: Clip.antiAlias,
          child: Column(
            children: [
              const SizedBox(height: 9),
              Container(
                width: 38,
                height: 4,
                decoration: BoxDecoration(
                  color: palette.borderStrong,
                  borderRadius: BorderRadius.circular(99),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 10, 8, 8),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            _text(
                              context,
                              '第 ${fallbackIndex + 1} 步',
                              'Step ${fallbackIndex + 1}',
                            ),
                            style: TextStyle(
                              color: palette.textSecondary,
                              fontSize: 11,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            _actionTitle(context, tool, args),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: palette.textPrimary,
                              fontSize: 16,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      tooltip: _text(context, '关闭', 'Close'),
                      onPressed: () => Navigator.of(context).pop(),
                      icon: const Icon(Icons.close_rounded),
                    ),
                  ],
                ),
              ),
              Divider(height: 1, color: palette.borderSubtle),
              Expanded(
                child: ListView(
                  controller: controller,
                  padding: const EdgeInsets.fromLTRB(16, 14, 16, 24),
                  children: [
                    if (beforeStateId.isNotEmpty ||
                        afterStateId.isNotEmpty) ...[
                      Text(
                        _text(context, '动作证据', 'Action evidence'),
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: [
                          OutlinedButton.icon(
                            onPressed: () => onShowState(
                              beforeStateId.isNotEmpty
                                  ? beforeStateId
                                  : afterStateId,
                              action,
                            ),
                            icon: const Icon(Icons.image_outlined, size: 17),
                            label: Text(
                              _text(context, '动作截图', 'Action screenshot'),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),
                    ],
                    if (summary.isNotEmpty) ...[
                      Text(
                        _text(context, '决策', 'Decision'),
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: palette.surfacePrimary,
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(color: palette.borderSubtle),
                        ),
                        child: Text(
                          summary,
                          style: TextStyle(
                            color: palette.textPrimary,
                            fontSize: 13,
                            height: 1.4,
                          ),
                        ),
                      ),
                      const SizedBox(height: 16),
                    ],
                    if (thinking.isNotEmpty) ...[
                      Material(
                        color: palette.surfacePrimary,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(10),
                          side: BorderSide(color: palette.borderSubtle),
                        ),
                        clipBehavior: Clip.antiAlias,
                        child: ExpansionTile(
                          key: const ValueKey('run-log-step-thinking'),
                          tilePadding: const EdgeInsets.symmetric(
                            horizontal: 12,
                          ),
                          childrenPadding: const EdgeInsets.fromLTRB(
                            12,
                            0,
                            12,
                            12,
                          ),
                          title: Text(
                            _text(
                              context,
                              '模型思考（可选）',
                              'Raw reasoning (optional)',
                            ),
                            style: TextStyle(
                              color: palette.textSecondary,
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          children: [
                            Align(
                              alignment: Alignment.centerLeft,
                              child: SelectableText(
                                thinking,
                                style: TextStyle(
                                  color: palette.textSecondary,
                                  fontSize: 12,
                                  height: 1.4,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 16),
                    ],
                    if (usage?.hasUsage == true) ...[
                      Text(
                        _text(context, '模型用量', 'Model usage'),
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 7,
                        runSpacing: 7,
                        children: [
                          if (usage?.totalTokens != null)
                            _MetricPill(
                              label: _text(context, '总计', 'Total'),
                              value: formatRunLogTokens(usage!.totalTokens!),
                              emphasis: true,
                            ),
                          if (usage?.promptTokens != null)
                            _MetricPill(
                              label: _text(context, '输入', 'Prompt'),
                              value: formatRunLogTokens(usage!.promptTokens!),
                            ),
                          if (usage?.completionTokens != null)
                            _MetricPill(
                              label: _text(context, '输出', 'Completion'),
                              value: formatRunLogTokens(
                                usage!.completionTokens!,
                              ),
                            ),
                        ],
                      ),
                      const SizedBox(height: 16),
                    ],
                    Text(
                      _text(context, '动作详情', 'Action details'),
                      style: TextStyle(
                        color: palette.textSecondary,
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: palette.surfacePrimary,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: palette.borderSubtle),
                      ),
                      child: SelectableText(
                        actionJson,
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 12,
                          height: 1.4,
                          fontFamily: 'monospace',
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _MetricPill extends StatelessWidget {
  const _MetricPill({
    required this.label,
    required this.value,
    this.emphasis = false,
  });

  final String label;
  final String value;
  final bool emphasis;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      constraints: BoxConstraints(
        maxWidth: MediaQuery.sizeOf(context).width - 64,
      ),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(
        color: emphasis
            ? palette.accentPrimary.withValues(alpha: 0.10)
            : palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text.rich(
        TextSpan(
          children: [
            TextSpan(
              text: '$label ',
              style: TextStyle(color: palette.textTertiary),
            ),
            TextSpan(
              text: value,
              style: TextStyle(
                color: emphasis ? palette.accentPrimary : palette.textPrimary,
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
        ),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 11, height: 1.2),
      ),
    );
  }
}

class _SourceBadge extends StatelessWidget {
  const _SourceBadge({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: palette.accentPrimary.withValues(alpha: 0.09),
        borderRadius: BorderRadius.circular(99),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: palette.accentPrimary,
          fontSize: 10,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _TinyMetric extends StatelessWidget {
  const _TinyMetric({required this.value});

  final String value;

  @override
  Widget build(BuildContext context) => Text(
    value,
    style: TextStyle(color: context.omniPalette.textSecondary, fontSize: 11),
  );
}

class _RunLogStatus {
  const _RunLogStatus({
    required this.title,
    required this.icon,
    required this.color,
  });

  final String title;
  final IconData icon;
  final Color color;

  factory _RunLogStatus.fromPayload(
    BuildContext context,
    Map<String, dynamic> payload,
  ) {
    final status = _string(payload['status']).toLowerCase();
    if (status == 'running' || status == 'pending') {
      return _RunLogStatus(
        title: _text(context, '执行中', 'Execution in progress'),
        icon: Icons.timelapse_rounded,
        color: _runningColor(context),
      );
    }
    if (payload['success'] == false ||
        status == 'failed' ||
        status == 'error' ||
        status == 'cancelled') {
      return _RunLogStatus(
        title: status == 'cancelled'
            ? _text(context, '执行已取消', 'Execution cancelled')
            : _text(context, '执行失败', 'Execution failed'),
        icon: Icons.error_outline_rounded,
        color: _errorColor(context),
      );
    }
    return _RunLogStatus(
      title: _text(context, '执行已完成', 'Execution completed'),
      icon: Icons.check_circle_outline_rounded,
      color: _successColor(context),
    );
  }
}

String _actionTitle(
  BuildContext context,
  String tool,
  Map<String, dynamic> args,
) {
  final action = switch (tool) {
    'open_app' => _text(context, '打开应用', 'Open app'),
    'click' => _text(context, '点击', 'Tap'),
    'long_press' => _text(context, '长按', 'Long press'),
    'input_text' || 'type' => _text(context, '输入文本', 'Enter text'),
    'swipe' => _text(context, '滑动', 'Swipe'),
    'press_key' => _text(context, '系统按键', 'Press key'),
    'wait' => _text(context, '等待', 'Wait'),
    _ => tool.isEmpty ? _text(context, '操作', 'Action') : tool,
  };
  final target = switch (tool) {
    'open_app' => _string(args['package_name']),
    'click' || 'long_press' => _coordinates(args),
    'input_text' || 'type' => _string(args['text']),
    'swipe' => _firstText([args['direction'], _coordinates(args)]),
    'press_key' => _string(args['key']),
    'wait' => args['duration_ms'] == null ? '' : '${args['duration_ms']} ms',
    _ => '',
  };
  return target.isEmpty ? action : '$action · $target';
}

String _sourceLabel(BuildContext context, Map<String, dynamic> metadata) {
  return switch (_string(metadata['source']).toLowerCase()) {
    'vlm' => _text(context, '自动执行', 'Automatic'),
    'human_trajectory' || 'manual_recording' => _text(context, '人类', 'Human'),
    'omniflow_replay' || 'function' => _text(context, '复用指令', 'Function'),
    _ => '',
  };
}

String _coordinates(Map<String, dynamic> args) {
  final x = _string(args['x']);
  final y = _string(args['y']);
  if (x.isNotEmpty && y.isNotEmpty) return '$x, $y';
  final x1 = _string(args['x1']);
  final y1 = _string(args['y1']);
  final x2 = _string(args['x2']);
  final y2 = _string(args['y2']);
  if ([x1, y1, x2, y2].every((value) => value.isNotEmpty)) {
    return '$x1, $y1 → $x2, $y2';
  }
  return '';
}

Color _successColor(BuildContext context) =>
    context.isDarkTheme ? const Color(0xFF63D98A) : const Color(0xFF2F8F4E);

Color _errorColor(BuildContext context) =>
    context.isDarkTheme ? const Color(0xFFFF7A7A) : const Color(0xFFDC2626);

Color _runningColor(BuildContext context) =>
    context.isDarkTheme ? const Color(0xFFFFD166) : const Color(0xFFE6A700);

Map<String, dynamic> _map(dynamic value) => value is Map
    ? value.map((key, nested) => MapEntry(key.toString(), nested))
    : <String, dynamic>{};

String _string(dynamic value) => value?.toString().trim() ?? '';

int? _integer(dynamic value) {
  if (value is int) return value;
  if (value is num && value.isFinite) return value.toInt();
  return int.tryParse(_string(value));
}

String _firstText(Iterable<dynamic> values) {
  for (final value in values) {
    final text = _string(value);
    if (text.isNotEmpty) return text;
  }
  return '';
}

String _text(BuildContext context, String zh, String en) =>
    Localizations.localeOf(context).languageCode == 'en' ? en : zh;
