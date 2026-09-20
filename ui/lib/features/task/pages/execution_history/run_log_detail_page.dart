import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/features/task/pages/execution_history/widgets/run_log_timeline_components.dart';
import 'package:ui/features/task/execution/execution_backend.dart';
import 'package:ui/features/task/run_log/run_log_metrics.dart';

class RunLogDetailPage extends StatefulWidget {
  const RunLogDetailPage({
    super.key,
    required this.runId,
    required this.backend,
  });

  final String runId;
  final ExecutionBackend backend;

  @override
  State<RunLogDetailPage> createState() => _RunLogDetailPageState();
}

class _RunLogDetailPageState extends State<RunLogDetailPage> {
  Map<String, dynamic>? _runLog;
  bool _loading = true;
  bool _converting = false;
  String? _error;
  String? _functionId;
  String? _functionName;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final results = await Future.wait([
        widget.backend.getRunLog(widget.runId),
        widget.backend.list(
          ExecutionCollectionKind.functions,
          limit: 100,
          offset: 0,
        ),
      ]);
      final runLog = results[0] as Map<String, dynamic>;
      final functions = (results[1] as ExecutionPage).items;
      final linkedFunction = linkedExecutionFunction(runLog, functions);
      if (!mounted) return;
      setState(() {
        _runLog = runLog;
        _functionId = linkedFunction?['function_id']?.toString();
        _functionName = linkedFunction?['name']?.toString();
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = error.toString();
      });
    }
  }

  Future<void> _convert() async {
    if (_converting) return;
    setState(() => _converting = true);
    try {
      final function = await widget.backend.register({
        'run_id': widget.runId,
        ...?_runLog,
      });
      if (!mounted) return;
      final functionId = function['function_id'].toString();
      setState(() {
        _functionId = functionId;
        _functionName = function['name']?.toString();
      });
      _openFunction(functionId);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              error is StateError ? error.message.toString() : error.toString(),
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _converting = false);
    }
  }

  void _openFunction([String? functionId]) {
    final resolved = (functionId ?? _functionId)?.trim() ?? '';
    if (resolved.isEmpty) return;
    context.push(
      Uri(
        path: '/task/omniflow',
        queryParameters: {'functionId': resolved},
      ).toString(),
    );
  }

  Future<void> _showState(String stateId, Map<String, dynamic> action) async {
    if (stateId.isEmpty) return;
    try {
      final state = await widget.backend.getRunLogState(stateId);
      if (!mounted) return;
      await showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        builder: (context) => _StateSheet(state: state, action: action),
      );
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.toString())));
      }
    }
  }

  Future<void> _showStepDetails(
    Map<String, dynamic> step,
    int fallbackIndex,
    RunLogTokenUsage? tokenUsage,
  ) {
    return showModalBottomSheet<void>(
      context: context,
      useRootNavigator: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.28),
      builder: (_) => RunLogStepDetailSheet(
        step: step,
        fallbackIndex: fallbackIndex,
        tokenUsage: tokenUsage,
        onShowState: _showState,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_text(context, '运行记录', 'Run Log')),
        actions: [
          IconButton(
            tooltip: _text(context, '刷新', 'Refresh'),
            onPressed: _loading ? null : _load,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      floatingActionButton: _runLog == null
          ? null
          : FloatingActionButton.extended(
              key: ValueKey(
                _functionId?.isNotEmpty == true
                    ? 'run-log-view-function'
                    : 'run-log-register-function',
              ),
              onPressed: _converting
                  ? null
                  : (_functionId?.isNotEmpty == true
                        ? _openFunction
                        : _convert),
              icon: _converting
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : Icon(
                      _functionId?.isNotEmpty == true
                          ? Icons.account_tree_outlined
                          : Icons.add_task_rounded,
                    ),
              label: Text(
                _functionId?.isNotEmpty == true
                    ? _text(context, '查看复用指令', 'View Function')
                    : _text(context, '注册为复用指令', 'Register Function'),
              ),
            ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return Center(
        child: TextButton.icon(
          onPressed: _load,
          icon: const Icon(Icons.refresh_rounded),
          label: Text(_error!),
        ),
      );
    }
    final runLog = _runLog;
    if (runLog == null) return const SizedBox.shrink();
    final steps = _mapList(runLog['steps']);
    final metrics = RunLogMetrics.fromPayload(runLog);
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 100),
      children: [
        RunLogOverviewPanel(
          payload: runLog,
          metrics: metrics,
          stepCount: steps.length,
        ),
        if (_functionId?.isNotEmpty == true) ...[
          const SizedBox(height: 10),
          ListTile(
            key: const ValueKey('run-log-linked-function'),
            contentPadding: const EdgeInsets.symmetric(horizontal: 4),
            leading: const Icon(Icons.account_tree_outlined),
            title: Text(_text(context, '对应复用指令', 'Linked Function')),
            subtitle: Text(
              (_functionName?.trim().isNotEmpty == true
                      ? _functionName
                      : _functionId) ??
                  '',
            ),
            trailing: const Icon(Icons.chevron_right_rounded),
            onTap: _openFunction,
          ),
        ],
        const SizedBox(height: 14),
        if (steps.isEmpty)
          Text(_text(context, '没有可显示的步骤', 'No steps to display'))
        else
          for (var index = 0; index < steps.length; index++)
            RunLogTimelineStepCard(
              step: steps[index],
              fallbackIndex: index,
              isLast: index == steps.length - 1,
              tokenUsage: runLogStepTokenUsage(runLog, steps[index]),
              onTap: () => _showStepDetails(
                steps[index],
                index,
                runLogStepTokenUsage(runLog, steps[index]),
              ),
            ),
      ],
    );
  }
}

class _StateSheet extends StatelessWidget {
  const _StateSheet({required this.state, required this.action});

  final Map<String, dynamic> state;
  final Map<String, dynamic> action;

  @override
  Widget build(BuildContext context) {
    final screenshotPath = state['screenshot_path']?.toString() ?? '';
    final screenshot = File(screenshotPath);
    return SafeArea(
      child: DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.85,
        minChildSize: 0.4,
        maxChildSize: 0.95,
        builder: (context, controller) => ListView(
          controller: controller,
          padding: const EdgeInsets.all(16),
          children: [
            Text(
              state['state_id']?.toString() ?? 'State',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 12),
            if (screenshotPath.isNotEmpty && screenshot.existsSync())
              ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: Stack(
                  children: [
                    Image.file(
                      screenshot,
                      width: double.infinity,
                      fit: BoxFit.fitWidth,
                      semanticLabel: _text(context, '记录的屏幕', 'Recorded screen'),
                    ),
                    if (_actionPoint(action) case final point?)
                      Positioned.fill(
                        child: LayoutBuilder(
                          builder: (context, constraints) => Stack(
                            children: [
                              // The decoded point is a fraction of this screen,
                              // independent of screenshot/display resolution.
                              Positioned(
                                left: constraints.maxWidth * point.$1 - 11,
                                top: constraints.maxHeight * point.$2 - 11,
                                child: Icon(
                                  Icons.my_location_rounded,
                                  color: Colors.redAccent,
                                  size: 22,
                                  semanticLabel: _text(
                                    context,
                                    '操作位置',
                                    'Action location',
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                  ],
                ),
              )
            else
              Text(_text(context, '状态截图不可用', 'State screenshot unavailable')),
            const SizedBox(height: 16),
            Text(
              '${state['package_name'] ?? ''} ${state['activity_name'] ?? ''}',
            ),
            const SizedBox(height: 12),
            SelectableText(state['xml']?.toString() ?? ''),
          ],
        ),
      ),
    );
  }

  (double, double)? _actionPoint(Map<String, dynamic> action) {
    final official = action.containsKey('action_type');
    final args = official ? action : _map(action['args']);
    final x = double.tryParse('${args['x'] ?? ''}');
    final y = double.tryParse('${args['y'] ?? ''}');
    // InternalRunLogStore.externalActionPayload projects the official flat
    // payload to display pixels. Legacy tool/args retains canonical 0..1000.
    final display = _map(state['display']);
    final width = official
        ? double.tryParse('${display['width'] ?? ''}')
        : 1000.0;
    final height = official
        ? double.tryParse('${display['height'] ?? ''}')
        : 1000.0;
    if (x == null ||
        y == null ||
        width == null ||
        height == null ||
        !width.isFinite ||
        !height.isFinite ||
        width <= 0 ||
        height <= 0 ||
        !x.isFinite ||
        !y.isFinite ||
        x < 0 ||
        x > width ||
        y < 0 ||
        y > height) {
      return null;
    }
    return (x / width, y / height);
  }
}

List<Map<String, dynamic>> _mapList(dynamic value) => value is List
    ? value.whereType<Map>().map(_map).toList(growable: false)
    : const [];

Map<String, dynamic> _map(dynamic value) => value is Map
    ? value.map((key, nested) => MapEntry(key.toString(), nested))
    : <String, dynamic>{};

String _text(BuildContext context, String zh, String en) =>
    Localizations.localeOf(context).languageCode == 'en' ? en : zh;
