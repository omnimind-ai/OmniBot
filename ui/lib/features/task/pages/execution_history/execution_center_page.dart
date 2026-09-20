import 'dart:async';

import 'package:flutter/foundation.dart' show AsyncCallback;

import 'package:ui/features/task/execution/execution_backend.dart';
import 'package:ui/features/task/execution/execution_center_controller.dart';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/features/task/pages/execution_history/widgets/function_arguments_dialog.dart';
import 'package:ui/features/task/pages/execution_history/widgets/function_detail_sheet.dart';
import 'package:ui/features/task/run_log/run_log_metrics.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';

class ExecutionCenterPage extends StatefulWidget {
  const ExecutionCenterPage({
    super.key,
    required this.backend,
    this.initialTab,
    this.initialFunctionId,
  });

  final ExecutionBackend backend;
  final String? initialTab;
  final String? initialFunctionId;

  @override
  State<ExecutionCenterPage> createState() => _ExecutionCenterPageState();
}

class _ExecutionCenterPageState extends State<ExecutionCenterPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;
  late final ExecutionCenterController _library;
  bool _initialFunctionOpened = false;
  final Set<String> _registeringRunIds = <String>{};
  final FocusNode _recordingFocus = FocusNode();
  String? _runningFunctionId;
  bool _executing = false;
  bool _recording = false;
  bool get _busy => _recording || _runningFunctionId != null;

  bool get _ready => _library.ready;

  @override
  void initState() {
    super.initState();
    _library = ExecutionCenterController(widget.backend)
      ..addListener(_libraryChanged);
    _tabController = TabController(
      length: 2,
      initialIndex: _initialTabIndex(widget.initialTab),
      vsync: this,
    )..addListener(_handleTabChanged);
    unawaited(_initialize());
  }

  @override
  void dispose() {
    _recordingFocus.dispose();
    _library.removeListener(_libraryChanged);
    _library.dispose();
    _tabController
      ..removeListener(_handleTabChanged)
      ..dispose();
    super.dispose();
  }

  void _libraryChanged() {
    if (!mounted) return;
    setState(() {});
    _openInitialFunctionIfAvailable();
  }

  void _handleTabChanged() {
    if (!_tabController.indexIsChanging) {
      unawaited(_library.loadActive(logs: _tabController.index == 1));
    }
  }

  Future<void> _initialize() =>
      _library.initialize(logs: _tabController.index == 1);
  Future<void> _loadActive({required bool reset}) =>
      _library.loadActive(logs: _tabController.index == 1, reset: reset);
  Future<void> _loadFunctions({required bool reset}) =>
      _library.load(_library.functions, reset: reset);
  Future<void> _loadRunLogs({required bool reset}) =>
      _library.load(_library.runLogs, reset: reset);
  Future<void> _refreshActive() => _loadActive(reset: true);
  Future<void> _enableProvider() =>
      _library.enable(logs: _tabController.index == 1);

  void _openInitialFunctionIfAvailable() {
    if (_initialFunctionOpened || !mounted) return;
    final functionId = widget.initialFunctionId?.trim() ?? '';
    if (functionId.isEmpty) return;
    final function = _library.functions.items
        .cast<Map<String, dynamic>?>()
        .firstWhere(
          (item) => _string(item?['function_id']) == functionId,
          orElse: () => null,
        );
    if (function == null) return;
    _initialFunctionOpened = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(_showFunctionDetails(function));
    });
  }

  Future<void> _showFunctionDetails(
    Map<String, dynamic> function, {
    bool refreshOnOpen = true,
  }) {
    return showModalBottomSheet<void>(
      context: context,
      useRootNavigator: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.28),
      builder: (_) => FunctionDetailSheet(
        initialFunction: function,
        loadFunction: widget.backend.getFunction,
        onSaveMetadata: _library.updateFunctionMetadata,
        onReplay: _replay,
        onEnhance: _enhanceFunction,
        onDelete: _deleteFunction,
        refreshOnOpen: refreshOnOpen,
      ),
    );
  }

  Future<void> _enhanceFunction(Map<String, dynamic> function) async {
    try {
      final request = widget.backend.enhancementRequest(function);
      await WidgetsBinding.instance.endOfFrame;
      if (!mounted) return;
      await context.push(
        '/home/chat',
        extra: ConversationThreadTarget.newConversation(
          mode: ConversationMode.agent,
          requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
          initialMessage: request,
        ),
      );
      if (mounted) await _loadFunctions(reset: true);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.toString())));
      }
    }
  }

  Future<void> _replay(Map<String, dynamic> function) async {
    if (_busy) return;
    final functionId = _string(function['function_id']);
    if (functionId.isEmpty) return;
    setState(() => _runningFunctionId = functionId);
    try {
      final accessibilityReady = await widget.backend.authorize(context);
      if (!accessibilityReady || !mounted) return;
      final arguments = await _collectArguments(function);
      if (arguments == null || !mounted) return;
      setState(() => _executing = true);
      await _runAction(
        () => widget.backend.run(function, arguments),
        success: _text(
          context,
          '执行已完成，可在运行记录中查看',
          'Run completed. See Run Logs for details.',
        ),
      );
      _library.runLogs.loaded = false;
    } finally {
      if (mounted) {
        setState(() {
          _runningFunctionId = null;
          _executing = false;
        });
      }
    }
  }

  Future<void> _startRecording() async {
    if (_busy || !_ready) return;
    setState(() => _recording = true);
    try {
      await widget.backend.record(
        context: context,
        focus: _recordingFocus,
        isMounted: () => mounted,
      );
      _library.invalidate();
      if (mounted) await _loadActive(reset: true);
    } finally {
      if (mounted) setState(() => _recording = false);
    }
  }

  Future<Map<String, dynamic>?> _collectArguments(
    Map<String, dynamic> function,
  ) async {
    final inputSchema = _map(function['input_schema']);
    final properties = _map(inputSchema['properties']);
    if (properties.isEmpty) return <String, dynamic>{};
    return showDialog<Map<String, dynamic>>(
      context: context,
      builder: (_) => FunctionArgumentsDialog(schema: inputSchema),
    );
  }

  Future<void> _deleteFunction(Map<String, dynamic> function) async {
    final functionId = _string(function['function_id']);
    if (functionId.isEmpty) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(_text(context, '删除复用指令', 'Delete Function')),
        content: Text(_text(context, '删除后无法继续执行。', 'This cannot be undone.')),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: Text(_text(context, '取消', 'Cancel')),
          ),
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: Text(_text(context, '删除', 'Delete')),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    await _runAction(
      () => widget.backend.delete(functionId),
      success: _text(context, '复用指令已删除', 'Function deleted'),
      reload: true,
    );
  }

  Future<void> _saveFunctionFromRunLog(Map<String, dynamic> runLog) async {
    final runId = _string(runLog['run_id']);
    if (runId.isEmpty || _registeringRunIds.contains(runId)) return;
    setState(() => _registeringRunIds.add(runId));
    try {
      final function = await _library.register(runLog);
      if (!mounted) return;
      _tabController.animateTo(0);
      await WidgetsBinding.instance.endOfFrame;
      if (!mounted) return;
      await _showFunctionDetails(function, refreshOnOpen: false);
      if (mounted) unawaited(_loadFunctions(reset: true));
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            error is StateError ? error.message.toString() : error.toString(),
          ),
        ),
      );
    } finally {
      if (mounted) setState(() => _registeringRunIds.remove(runId));
    }
  }

  Future<void> _runAction(
    Future<void> Function() action, {
    required String success,
    bool reload = false,
  }) async {
    try {
      await action();
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(success)));
      if (reload) await _refreshActive();
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(error.toString())));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        centerTitle: true,
        titleTextStyle: Theme.of(context).textTheme.titleMedium
            ?.copyWith(fontWeight: FontWeight.w600),
        title: Text(_text(context, '执行中心', 'Execution Center')),
        actions: [
          IconButton(
            tooltip: _text(context, '刷新', 'Refresh'),
            onPressed: _library.loading
                ? null
                : (_ready ? _refreshActive : _initialize),
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
        bottom: TabBar(
          controller: _tabController,
          tabs: [
            Tab(text: _text(context, '复用指令', 'Functions')),
            Tab(text: _text(context, '运行记录', 'Run Logs')),
          ],
        ),
      ),
      body: _buildBody(),
      bottomNavigationBar: _ready
          ? SafeArea(
              top: false,
              minimum: const EdgeInsets.fromLTRB(20, 12, 20, 16),
              child: SizedBox(
                height: 48,
                child: FilledButton.icon(
                  key: const ValueKey('execution-center-record'),
                  onPressed: _busy ? null : _startRecording,
                  icon: _recording
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(
                          Icons.fiber_manual_record_outlined,
                          size: 20,
                        ),
                  label: Text(_text(context, '手动录制', 'Manual recording')),
                ),
              ),
            )
          : null,
    );
  }

  int _initialTabIndex(String? value) {
    return switch (value?.trim().toLowerCase()) {
      'run_log' || 'run_logs' || 'runlog' || 'runlogs' || 'logs' || '1' => 1,
      _ => 0,
    };
  }

  Widget _buildBody() {
    if (_library.loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_library.error != null) {
      return _MessageState(
        icon: Icons.error_outline_rounded,
        title: _text(context, '加载失败', 'Failed to load'),
        message: _library.error!,
        actionLabel: _text(context, '重试', 'Retry'),
        onAction: _initialize,
      );
    }
    if (!_ready) {
      final installed = _library.availability?.installed == true;
      return _MessageState(
        icon: Icons.extension_outlined,
        title: installed
            ? _text(context, '执行组件未启用', 'Execution provider is disabled')
            : _text(context, '先安装执行组件', 'Install an execution provider'),
        message: installed
            ? _text(
                context,
                '启用后即可查看运行记录、注册和执行复用指令。',
                'Enable it to inspect Run Logs and register reusable Functions.',
              )
            : _text(
                context,
                '安装执行组件后，即可录制操作并重复使用。',
                'Install an execution provider to record and reuse actions.',
              ),
        actionLabel: installed
            ? _text(context, '启用插件', 'Enable plugin')
            : _text(context, '前往插件市场', 'Open Plugin Market'),
        onAction: installed
            ? _enableProvider
            : () => context.push(_library.availability!.settingsRoute),
      );
    }
    return TabBarView(
      controller: _tabController,
      children: [
        _FunctionsTab(
          functions: _library.functions.items,
          loading: _library.functions.loading,
          error: _library.functions.error,
          hasMore: _library.functions.hasMore,
          onRefresh: () => _loadFunctions(reset: true),
          onLoadMore: () => _loadFunctions(reset: false),
          onOpenDetails: _showFunctionDetails,
          onReplay: _replay,
          busy: _busy,
          runningFunctionId: _executing ? _runningFunctionId : null,
        ),
        _RunLogsTab(
          runLogs: _library.runLogs.items,
          functions: _library.functions.items,
          loading: _library.runLogs.loading,
          error: _library.runLogs.error,
          hasMore: _library.runLogs.hasMore,
          onRefresh: () => _loadRunLogs(reset: true),
          onLoadMore: () => _loadRunLogs(reset: false),
          onConvert: _saveFunctionFromRunLog,
          onOpenFunction: _showFunctionDetails,
          registeringRunIds: _registeringRunIds,
        ),
      ],
    );
  }
}

class _FunctionsTab extends StatelessWidget {
  const _FunctionsTab({
    required this.functions,
    required this.loading,
    required this.error,
    required this.hasMore,
    required this.onRefresh,
    required this.onLoadMore,
    required this.onOpenDetails,
    required this.onReplay,
    required this.busy,
    required this.runningFunctionId,
  });

  final List<Map<String, dynamic>> functions;
  final bool loading;
  final String? error;
  final bool hasMore;
  final AsyncCallback onRefresh;
  final AsyncCallback onLoadMore;
  final ValueChanged<Map<String, dynamic>> onOpenDetails;
  final ValueChanged<Map<String, dynamic>> onReplay;
  final bool busy;
  final String? runningFunctionId;

  @override
  Widget build(BuildContext context) {
    if (loading && functions.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (error != null && functions.isEmpty) {
      return _MessageState(
        icon: Icons.error_outline_rounded,
        title: _text(context, '加载失败', 'Failed to load'),
        message: error!,
        actionLabel: _text(context, '重试', 'Retry'),
        onAction: onRefresh,
      );
    }
    if (functions.isEmpty) {
      return _EmptyTab(
        icon: Icons.replay_rounded,
        title: _text(context, '暂无复用指令', 'No Functions yet'),
        message: _text(
          context,
          '在运行记录中选择成功记录并注册。',
          'Register a successful execution from Run Logs.',
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: onRefresh,
      child: ListView.separated(
        padding: const EdgeInsets.symmetric(vertical: 6),
        itemCount: functions.length + (hasMore ? 1 : 0),
        separatorBuilder: (_, _) =>
            const Divider(height: 1, indent: 16, endIndent: 16),
        itemBuilder: (context, index) {
          if (index >= functions.length) {
            return _LoadMoreRow(
              key: const ValueKey('functions-load-more'),
              loading: loading,
              onPressed: onLoadMore,
            );
          }
          final function = functions[index];
          return _FunctionListItem(
            function: function,
            onOpenDetails: () => onOpenDetails(function),
            onReplay: busy ? null : () => onReplay(function),
            running: runningFunctionId == _string(function['function_id']),
          );
        },
      ),
    );
  }
}

class _FunctionListItem extends StatelessWidget {
  const _FunctionListItem({
    required this.function,
    required this.onOpenDetails,
    required this.onReplay,
    required this.running,
  });

  final Map<String, dynamic> function;
  final VoidCallback onOpenDetails;
  final VoidCallback? onReplay;
  final bool running;

  @override
  Widget build(BuildContext context) {
    final functionId = _string(function['function_id']);
    final name = _string(function['name']).nullIfEmpty ?? functionId;
    final description = _string(function['description']);
    final steps = _mapList(function['steps']).length;
    final parameters = _map(_map(function['input_schema'])['properties'])
        .length;
    final meta = _text(
      context,
      '$steps 个步骤 · $parameters 个参数',
      '$steps steps · $parameters parameters',
    );
    final theme = Theme.of(context);
    final secondary = theme.colorScheme.onSurfaceVariant;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onOpenDetails,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Icon(Icons.chevron_right_rounded, size: 20, color: secondary),
                ],
              ),
              if (description.isNotEmpty && description != name) ...[
                const SizedBox(height: 6),
                Text(
                  description,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: secondary,
                    height: 1.5,
                  ),
                ),
              ],
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      meta,
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: secondary,
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  FilledButton.tonalIcon(
                    key: ValueKey('function-run-$functionId'),
                    onPressed: onReplay,
                    style: FilledButton.styleFrom(
                      minimumSize: const Size(84, 44),
                      padding: const EdgeInsets.symmetric(horizontal: 14),
                    ),
                    icon: running
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.play_arrow_rounded, size: 18),
                    label: Text(_text(context, '执行', 'Run')),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _RunLogsTab extends StatelessWidget {
  const _RunLogsTab({
    required this.runLogs,
    required this.functions,
    required this.loading,
    required this.error,
    required this.hasMore,
    required this.onRefresh,
    required this.onLoadMore,
    required this.onConvert,
    required this.onOpenFunction,
    required this.registeringRunIds,
  });

  final List<Map<String, dynamic>> runLogs;
  final List<Map<String, dynamic>> functions;
  final bool loading;
  final String? error;
  final bool hasMore;
  final AsyncCallback onRefresh;
  final AsyncCallback onLoadMore;
  final ValueChanged<Map<String, dynamic>> onConvert;
  final ValueChanged<Map<String, dynamic>> onOpenFunction;
  final Set<String> registeringRunIds;

  @override
  Widget build(BuildContext context) {
    if (loading && runLogs.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (error != null && runLogs.isEmpty) {
      return _MessageState(
        icon: Icons.error_outline_rounded,
        title: _text(context, '加载失败', 'Failed to load'),
        message: error!,
        actionLabel: _text(context, '重试', 'Retry'),
        onAction: onRefresh,
      );
    }
    if (runLogs.isEmpty) {
      return _EmptyTab(
        icon: Icons.receipt_long_outlined,
        title: _text(context, '暂无运行记录', 'No Run Logs yet'),
        message: _text(
          context,
          '在线 VLM 执行完成后会保存规范运行记录。',
          'Online VLM executions save canonical run logs.',
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: onRefresh,
      child: ListView.separated(
        padding: const EdgeInsets.symmetric(vertical: 6),
        itemCount: runLogs.length + (hasMore ? 1 : 0),
        separatorBuilder: (_, _) =>
            const Divider(height: 1, indent: 16, endIndent: 16),
        itemBuilder: (context, index) {
          if (index >= runLogs.length) {
            return _LoadMoreRow(
              key: const ValueKey('run-logs-load-more'),
              loading: loading,
              onPressed: onLoadMore,
            );
          }
          final runLog = runLogs[index];
          final linkedFunction = linkedExecutionFunction(runLog, functions);
          return _RunLogListItem(
            runLog: runLog,
            onOpen: () =>
                context.push('/task/run_log/${_string(runLog['run_id'])}'),
            onConvert: () => onConvert(runLog),
            linkedFunction: linkedFunction,
            registering: registeringRunIds.contains(_string(runLog['run_id'])),
            onOpenFunction: linkedFunction == null
                ? null
                : () => onOpenFunction(linkedFunction),
          );
        },
      ),
    );
  }
}

class _RunLogListItem extends StatelessWidget {
  const _RunLogListItem({
    required this.runLog,
    required this.onOpen,
    required this.onConvert,
    required this.linkedFunction,
    required this.onOpenFunction,
    required this.registering,
  });

  final Map<String, dynamic> runLog;
  final VoidCallback onOpen;
  final VoidCallback onConvert;
  final Map<String, dynamic>? linkedFunction;
  final VoidCallback? onOpenFunction;
  final bool registering;

  @override
  Widget build(BuildContext context) {
    final metrics = RunLogMetrics.fromPayload(runLog);
    final runId = _string(runLog['run_id']);
    final status = _string(runLog['status']).nullIfEmpty ?? 'unknown';
    final meta = <String>[
      if (metrics.startedAt != null) formatRunLogTimestamp(metrics.startedAt!),
      if (metrics.durationMs != null) formatRunLogDuration(metrics.durationMs!),
      if (metrics.tokenUsage.totalTokens != null)
        _text(
          context,
          '模型用量 ${formatRunLogTokens(metrics.tokenUsage.totalTokens!)}',
          '${formatRunLogTokens(metrics.tokenUsage.totalTokens!)} tokens',
        )
      else
        _text(context, '模型用量未提供', 'Token usage unavailable'),
      if (metrics.model != null) metrics.model!,
      if (metrics.callCount != null)
        _text(
          context,
          '${metrics.callCount} 次 VLM 调用',
          '${metrics.callCount} VLM calls',
        ),
    ];
    return Material(
      key: ValueKey('run-log-open-$runId'),
      color: Colors.transparent,
      child: InkWell(
        onTap: onOpen,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 13, 8, 10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      _string(runLog['goal']).nullIfEmpty ?? runId,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleSmall
                          ?.copyWith(fontWeight: FontWeight.w600),
                    ),
                  ),
                  Text(
                    _runStatusLabel(context, status),
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: _runStatusColor(context, status),
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(width: 4),
                ],
              ),
              const SizedBox(height: 5),
              Wrap(
                spacing: 6,
                runSpacing: 3,
                children: [
                  for (var index = 0; index < meta.length; index++) ...[
                    if (index > 0)
                      Text('·', style: Theme.of(context).textTheme.labelSmall),
                    Text(
                      meta[index],
                      style: Theme.of(context).textTheme.labelSmall,
                    ),
                  ],
                ],
              ),
              const SizedBox(height: 3),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      runId,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.labelSmall?.copyWith(
                        color: Theme.of(context).colorScheme.outline,
                        fontFamily: 'monospace',
                      ),
                    ),
                  ),
                  TextButton(
                    key: ValueKey(
                      linkedFunction == null
                          ? 'run-log-register-$runId'
                          : 'run-log-function-$runId',
                    ),
                    onPressed: registering
                        ? null
                        : (onOpenFunction ?? onConvert),
                    child: registering
                        ? Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              const SizedBox.square(
                                dimension: 14,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              ),
                              const SizedBox(width: 7),
                              Text(_text(context, '注册中', 'Registering')),
                            ],
                          )
                        : Text(
                            linkedFunction == null
                                ? _text(context, '注册为复用指令', 'Register Function')
                                : _text(context, '查看复用指令', 'View Function'),
                          ),
                  ),
                  Icon(
                    Icons.chevron_right_rounded,
                    color: Theme.of(context).colorScheme.outline,
                    size: 20,
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _LoadMoreRow extends StatelessWidget {
  const _LoadMoreRow({
    super.key,
    required this.loading,
    required this.onPressed,
  });

  final bool loading;
  final AsyncCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 52,
      child: Center(
        child: loading
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : TextButton(
                onPressed: onPressed,
                child: Text(_text(context, '加载更多', 'Load more')),
              ),
      ),
    );
  }
}

class _EmptyTab extends StatelessWidget {
  const _EmptyTab({
    required this.icon,
    required this.title,
    required this.message,
  });

  final IconData icon;
  final String title;
  final String message;

  @override
  Widget build(BuildContext context) =>
      _MessageState(icon: icon, title: title, message: message);
}

class _MessageState extends StatelessWidget {
  const _MessageState({
    required this.icon,
    required this.title,
    required this.message,
    this.actionLabel,
    this.onAction,
  });

  final IconData icon;
  final String title;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 48, color: Theme.of(context).colorScheme.primary),
            const SizedBox(height: 16),
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(message, textAlign: TextAlign.center),
            if (onAction != null && actionLabel != null) ...[
              const SizedBox(height: 20),
              FilledButton(onPressed: onAction, child: Text(actionLabel!)),
            ],
          ],
        ),
      ),
    );
  }
}

List<Map<String, dynamic>> _mapList(dynamic value) => value is List
    ? value.whereType<Map>().map(_map).toList(growable: false)
    : const [];

Map<String, dynamic> _map(dynamic value) => value is Map
    ? value.map((key, nested) => MapEntry(key.toString(), nested))
    : <String, dynamic>{};

String _string(dynamic value) => value?.toString().trim() ?? '';

String _runStatusLabel(BuildContext context, String status) => switch (status
    .toLowerCase()) {
  'success' || 'succeeded' || 'completed' => _text(context, '成功', 'Succeeded'),
  'running' || 'pending' => _text(context, '执行中', 'Running'),
  'failed' || 'error' => _text(context, '失败', 'Failed'),
  'cancelled' || 'canceled' => _text(context, '已取消', 'Cancelled'),
  _ => _text(context, '未知', 'Unknown'),
};

Color _runStatusColor(BuildContext context, String status) =>
    switch (status.toLowerCase()) {
      'success' || 'succeeded' || 'completed' => Colors.green.shade700,
      'running' || 'pending' => Theme.of(context).colorScheme.primary,
      'failed' || 'error' => Theme.of(context).colorScheme.error,
      _ => Theme.of(context).colorScheme.outline,
    };

String _text(BuildContext context, String zh, String en) =>
    Localizations.localeOf(context).languageCode == 'en' ? en : zh;

extension on String {
  String? get nullIfEmpty => isEmpty ? null : this;
}
