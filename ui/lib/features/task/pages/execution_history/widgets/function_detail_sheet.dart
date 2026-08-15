import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:ui/theme/theme_context.dart';

class FunctionDetailSheet extends StatefulWidget {
  const FunctionDetailSheet({
    super.key,
    required this.initialFunction,
    required this.loadFunction,
    required this.onReplay,
    required this.onEnhance,
    required this.onDelete,
    this.refreshOnOpen = true,
  });

  final Map<String, dynamic> initialFunction;
  final Future<Map<String, dynamic>> Function(String functionId) loadFunction;
  final ValueChanged<Map<String, dynamic>> onReplay;
  final ValueChanged<Map<String, dynamic>> onEnhance;
  final ValueChanged<Map<String, dynamic>> onDelete;
  final bool refreshOnOpen;

  @override
  State<FunctionDetailSheet> createState() => _FunctionDetailSheetState();
}

class _FunctionDetailSheetState extends State<FunctionDetailSheet> {
  late Map<String, dynamic> _function;
  bool _loading = true;
  bool _loadFailed = false;

  @override
  void initState() {
    super.initState();
    _function = Map<String, dynamic>.from(widget.initialFunction);
    if (widget.refreshOnOpen) {
      unawaited(_load());
    } else {
      _loading = false;
    }
  }

  Future<void> _load() async {
    final functionId = _string(_function['function_id']);
    if (functionId.isEmpty) {
      setState(() => _loading = false);
      return;
    }
    setState(() {
      _loading = true;
      _loadFailed = false;
    });
    try {
      final payload = await widget.loadFunction(functionId);
      final detail = _functionFromPayload(payload);
      if (!mounted) return;
      setState(() {
        _function = <String, dynamic>{..._function, ...detail};
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _loadFailed = true;
      });
    }
  }

  void _closeAndRun(ValueChanged<Map<String, dynamic>> action) {
    Navigator.of(context).pop();
    action(_function);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final inputSchema = _map(_function['input_schema']);
    final properties = _map(inputSchema['properties']);
    final requiredNames = _stringSet(inputSchema['required']);
    final steps = _mapList(_function['steps']);
    final functionId = _string(_function['function_id']);
    final name =
        _string(_function['name']).nullIfEmpty ??
        (functionId.isEmpty
            ? _text(context, '未命名复用指令', 'Unnamed Function')
            : functionId);
    final description = _string(_function['description']);
    final agentAccess = _function['agent_visible'] == false
        ? _text(context, '智能体不可见', 'Agent access off')
        : _text(context, '智能体可见', 'Agent access on');
    return SafeArea(
      top: false,
      child: DraggableScrollableSheet(
        key: const ValueKey('function-detail-sheet'),
        expand: false,
        initialChildSize: 0.82,
        minChildSize: 0.48,
        maxChildSize: 0.96,
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
                            _text(context, '复用指令详情', 'Function Details'),
                            style: TextStyle(
                              color: palette.textSecondary,
                              fontSize: 11,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 3),
                          Text(
                            name,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: palette.textPrimary,
                              fontSize: 17,
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
              if (_loading)
                LinearProgressIndicator(
                  minHeight: 2,
                  color: palette.accentPrimary,
                  backgroundColor: palette.borderSubtle,
                )
              else
                Divider(height: 1, color: palette.borderSubtle),
              Expanded(
                child: ListView(
                  controller: controller,
                  padding: const EdgeInsets.fromLTRB(16, 14, 16, 24),
                  children: [
                    if (description.isNotEmpty) ...[
                      Text(
                        description,
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 13,
                          height: 1.45,
                        ),
                      ),
                      const SizedBox(height: 7),
                    ],
                    SelectableText(
                      functionId,
                      style: TextStyle(
                        color: palette.textTertiary,
                        fontSize: 11,
                        fontFamily: 'monospace',
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      _text(
                        context,
                        '${steps.length} 个步骤 · ${properties.length} 个参数 · $agentAccess',
                        '${steps.length} steps · ${properties.length} parameters · $agentAccess',
                      ),
                      style: TextStyle(
                        color: palette.textSecondary,
                        fontSize: 11,
                      ),
                    ),
                    if (_loadFailed) ...[
                      const SizedBox(height: 10),
                      Row(
                        children: [
                          Icon(
                            Icons.info_outline_rounded,
                            size: 16,
                            color: Theme.of(context).colorScheme.error,
                          ),
                          const SizedBox(width: 7),
                          Expanded(
                            child: Text(
                              _text(
                                context,
                                '详情同步失败，当前显示缓存内容。',
                                'Details could not sync. Showing cached content.',
                              ),
                              style: TextStyle(
                                color: palette.textSecondary,
                                fontSize: 11,
                              ),
                            ),
                          ),
                          TextButton(
                            onPressed: _load,
                            child: Text(_text(context, '重试', 'Retry')),
                          ),
                        ],
                      ),
                    ],
                    const SizedBox(height: 20),
                    _sectionTitle(context, _text(context, '参数', 'Parameters')),
                    if (properties.isEmpty)
                      _emptyText(
                        context,
                        _text(context, '无需参数', 'No parameters'),
                      )
                    else
                      for (final entry in properties.entries)
                        _parameterRow(
                          context,
                          entry.key,
                          _map(entry.value),
                          requiredNames.contains(entry.key),
                        ),
                    const SizedBox(height: 16),
                    _sectionTitle(context, _text(context, '步骤', 'Steps')),
                    if (steps.isEmpty)
                      _emptyText(context, _text(context, '暂无步骤', 'No steps'))
                    else
                      for (var index = 0; index < steps.length; index++)
                        _stepRow(context, steps[index], index),
                  ],
                ),
              ),
              Divider(height: 1, color: palette.borderSubtle),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
                child: Row(
                  children: [
                    IconButton.outlined(
                      key: const ValueKey('function-detail-delete'),
                      onPressed: functionId.isEmpty
                          ? null
                          : () => _closeAndRun(widget.onDelete),
                      tooltip: _text(context, '删除', 'Delete'),
                      icon: const Icon(Icons.delete_outline_rounded, size: 18),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: OutlinedButton.icon(
                        key: const ValueKey('function-detail-enhance'),
                        onPressed: functionId.isEmpty
                            ? null
                            : () => _closeAndRun(widget.onEnhance),
                        icon: const Icon(Icons.auto_fix_high_rounded, size: 18),
                        label: Text(_text(context, '增强', 'Enhance')),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: FilledButton.icon(
                        key: const ValueKey('function-detail-run'),
                        onPressed: functionId.isEmpty
                            ? null
                            : () => _closeAndRun(widget.onReplay),
                        icon: const Icon(Icons.play_arrow_rounded, size: 19),
                        label: Text(_text(context, '执行', 'Run')),
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

Widget _sectionTitle(BuildContext context, String title) => Padding(
  padding: const EdgeInsets.only(bottom: 6),
  child: Text(
    title,
    style: TextStyle(
      color: context.omniPalette.textPrimary,
      fontSize: 14,
      fontWeight: FontWeight.w700,
    ),
  ),
);

Widget _emptyText(BuildContext context, String text) => Padding(
  padding: const EdgeInsets.symmetric(vertical: 6),
  child: Text(
    text,
    style: TextStyle(color: context.omniPalette.textTertiary, fontSize: 12),
  ),
);

Widget _parameterRow(
  BuildContext context,
  String name,
  Map<String, dynamic> schema,
  bool required,
) {
  final palette = context.omniPalette;
  final type = _string(schema['type']);
  final description = _string(schema['description']);
  return ListTile(
    dense: true,
    contentPadding: EdgeInsets.zero,
    title: Text(name, style: const TextStyle(fontFamily: 'monospace')),
    subtitle: description.isEmpty ? null : Text(description),
    trailing: Text(
      [
        if (type.isNotEmpty) type,
        if (required) _text(context, '必填', 'Required'),
      ].join(' · '),
      style: TextStyle(color: palette.textTertiary, fontSize: 10),
    ),
  );
}

Widget _stepRow(BuildContext context, Map<String, dynamic> step, int index) {
  final palette = context.omniPalette;
  final action = _map(step['action']);
  final args = _map(action['args']);
  final tool =
      _string(action['tool']).nullIfEmpty ??
      _string(action['type']).nullIfEmpty ??
      _text(context, '操作', 'Action');
  return ListTile(
    dense: true,
    contentPadding: EdgeInsets.zero,
    leading: CircleAvatar(
      radius: 12,
      backgroundColor: palette.accentPrimary.withValues(alpha: 0.12),
      child: Text(
        '${index + 1}',
        style: TextStyle(
          color: palette.accentPrimary,
          fontSize: 10,
          fontWeight: FontWeight.w700,
        ),
      ),
    ),
    title: Text(_toolTitle(context, tool)),
    subtitle: Text(
      args.isEmpty ? tool : '$tool  ${jsonEncode(args)}',
      maxLines: 2,
      overflow: TextOverflow.ellipsis,
      style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
    ),
  );
}

Map<String, dynamic> _functionFromPayload(Map<String, dynamic> payload) {
  for (final key in const ['function', 'spec']) {
    final nested = _map(payload[key]);
    if (nested.isNotEmpty) return nested;
  }
  if (payload.containsKey('function_id') ||
      payload.containsKey('input_schema') ||
      payload.containsKey('steps')) {
    return payload;
  }
  return const {};
}

String _toolTitle(BuildContext context, String tool) => switch (tool) {
  'open_app' => _text(context, '打开应用', 'Open app'),
  'click' => _text(context, '点击', 'Tap'),
  'long_press' => _text(context, '长按', 'Long press'),
  'input_text' || 'type' => _text(context, '输入文本', 'Enter text'),
  'swipe' => _text(context, '滑动', 'Swipe'),
  'press_key' => _text(context, '系统按键', 'Press key'),
  'wait' => _text(context, '等待', 'Wait'),
  _ => tool,
};

List<Map<String, dynamic>> _mapList(dynamic value) => value is List
    ? value.whereType<Map>().map(_map).toList(growable: false)
    : const [];

Map<String, dynamic> _map(dynamic value) => value is Map
    ? value.map((key, nested) => MapEntry(key.toString(), nested))
    : <String, dynamic>{};

Set<String> _stringSet(dynamic value) =>
    value is List ? value.map((item) => item.toString()).toSet() : const {};

String _string(dynamic value) => value?.toString().trim() ?? '';

String _text(BuildContext context, String zh, String en) =>
    Localizations.localeOf(context).languageCode == 'en' ? en : zh;

extension on String {
  String? get nullIfEmpty => isEmpty ? null : this;
}
