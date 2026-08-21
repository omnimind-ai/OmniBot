import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/features/home/pages/authorize/accessibility_permission_prompt.dart';
import 'package:ui/features/task/pages/execution_history/widgets/function_detail_sheet.dart';
import 'package:ui/features/task/run_log/run_log_metrics.dart';
import 'package:ui/features/task/run_log/omniflow_tool_client.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/models/omni_plugin_item.dart';
import 'package:ui/services/omni_plugin_service.dart';

class OmniFlowExecutionCenterPage extends StatefulWidget {
  const OmniFlowExecutionCenterPage({
    super.key,
    this.initialTab,
    this.initialFunctionId,
  });

  final String? initialTab;
  final String? initialFunctionId;

  @override
  State<OmniFlowExecutionCenterPage> createState() =>
      _OmniFlowExecutionCenterPageState();
}

String _omniFlowErrorText(Object error) {
  if (error is StateError) return error.message.toString();
  return error.toString();
}

@visibleForTesting
String buildFunctionEnhancementPrompt(Map<String, dynamic> function) {
  final functionId = _string(function['function_id']);
  final sourceRunId = _string(function['source_run_id']);
  // source_run_id is UI provenance used to select the local RunLog; it is
  // intentionally not part of omniflow.function.v2 and must not be sent in
  // the `functions` artifact passed to the official writer.
  final functionArtifact = <String, dynamic>{...function}
    ..remove('source_run_id');
  // The enhancement prompt belongs to the official OmniFlow runtime. The
  // chat layer only forwards the local source identity and the current draft;
  // duplicating the staged authoring prompt here made the Agent search for a
  // RunLog and created a second, conflicting enhancement mechanism.
  return '请调用本机 OmniFlow 官方 save_function 完成 Function enhance。'
      '参数必须是 run_id=$sourceRunId、functions=[下面的完整 Function]、enhance=true；'
      '不要执行 Function，不要调用 list_run_logs/get_run_log/get_function，'
      '不要把 Function 或 RunLog 写到远端，也不要自行改写增强规则。'
      '由 OmniFlow 内置的官方增强流程生成并写回本地 Store；若 source_run_id 为空，'
      '直接返回明确错误。\n\n'
      'function_id: $functionId\n'
      'source_run_id: $sourceRunId\n'
      'function: ${jsonEncode(functionArtifact)}';
}

class _OmniFlowExecutionCenterPageState
    extends State<OmniFlowExecutionCenterPage>
    with SingleTickerProviderStateMixin {
  static const _pluginId = 'com.omnimind.omni-vlm-lite';
  static const _pageSize = 20;

  late final TabController _tabController;
  OmniPluginItem? _plugin;
  List<Map<String, dynamic>> _functions = const [];
  List<Map<String, dynamic>> _runLogs = const [];
  bool _pluginLoading = true;
  String? _pluginError;
  bool _functionsLoaded = false;
  bool _functionsLoading = false;
  bool _functionsHasMore = false;
  int _functionsNextOffset = 0;
  String? _functionsError;
  bool _runLogsLoaded = false;
  bool _runLogsLoading = false;
  bool _runLogsHasMore = false;
  int _runLogsNextOffset = 0;
  String? _runLogsError;
  bool _initialFunctionOpened = false;
  final Set<String> _registeringRunIds = <String>{};

  bool get _ready => _plugin?.installed == true && _plugin?.enabled == true;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(
      length: 2,
      initialIndex: _initialTabIndex(widget.initialTab),
      vsync: this,
    )..addListener(_handleTabChanged);
    unawaited(_loadPluginAndActive());
  }

  @override
  void dispose() {
    _tabController
      ..removeListener(_handleTabChanged)
      ..dispose();
    super.dispose();
  }

  void _handleTabChanged() {
    if (_tabController.indexIsChanging) return;
    unawaited(_ensureActiveLoaded());
  }

  Future<void> _loadPluginAndActive() async {
    if (mounted) {
      setState(() {
        _pluginLoading = true;
        _pluginError = null;
      });
    }
    try {
      final plugin = await OmniPluginService.getPlugin(_pluginId);
      if (plugin?.installed != true || plugin?.enabled != true) {
        if (!mounted) return;
        setState(() {
          _plugin = plugin;
          _functions = const [];
          _runLogs = const [];
          _functionsLoaded = false;
          _runLogsLoaded = false;
          _pluginLoading = false;
        });
        return;
      }
      if (!mounted) return;
      setState(() {
        _plugin = plugin;
        _pluginLoading = false;
      });
      await _loadActive(reset: true);
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _pluginLoading = false;
        _pluginError = error.toString();
      });
    }
  }

  Future<void> _ensureActiveLoaded() async {
    if (!_ready) return;
    if (_tabController.index == 0) {
      if (!_functionsLoaded) await _loadFunctions(reset: true);
    } else {
      await Future.wait([
        if (!_runLogsLoaded) _loadRunLogs(reset: true),
        if (!_functionsLoaded) _loadFunctions(reset: true),
      ]);
    }
  }

  Future<void> _loadActive({required bool reset}) {
    if (_tabController.index == 0) return _loadFunctions(reset: reset);
    return Future.wait([
      _loadRunLogs(reset: reset),
      _loadFunctions(reset: reset),
    ]).then((_) {});
  }

  Future<void> _loadFunctions({required bool reset}) async {
    if (_functionsLoading) return;
    final offset = reset ? 0 : _functionsNextOffset;
    setState(() {
      _functionsLoading = true;
      if (reset) _functionsError = null;
    });
    try {
      final result = await OmniFlowToolClient.listFunctions(
        limit: _pageSize,
        offset: offset,
      );
      if (!mounted) return;
      final items = _mapList(result['functions']);
      final merged = reset
          ? items
          : _mergeById(_functions, items, idKey: 'function_id');
      setState(() {
        _functions = merged;
        _functionsLoaded = true;
        _functionsLoading = false;
        _functionsHasMore = _hasMore(result, items.length, merged.length);
        _functionsNextOffset = _nextOffset(
          result,
          fallback: offset + items.length,
        );
      });
      _openInitialFunctionIfAvailable();
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _functionsLoaded = true;
        _functionsLoading = false;
        _functionsError = error.toString();
      });
    }
  }

  void _openInitialFunctionIfAvailable() {
    if (_initialFunctionOpened || !mounted) return;
    final functionId = widget.initialFunctionId?.trim() ?? '';
    if (functionId.isEmpty) return;
    final function = _functions.cast<Map<String, dynamic>?>().firstWhere(
      (item) => _string(item?['function_id']) == functionId,
      orElse: () => null,
    );
    if (function == null) return;
    _initialFunctionOpened = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(_showFunctionDetails(function));
    });
  }

  Future<void> _loadRunLogs({required bool reset}) async {
    if (_runLogsLoading) return;
    final offset = reset ? 0 : _runLogsNextOffset;
    setState(() {
      _runLogsLoading = true;
      if (reset) _runLogsError = null;
    });
    try {
      final result = await OmniFlowToolClient.listRunLogs(
        limit: _pageSize,
        offset: offset,
      );
      if (!mounted) return;
      final items = _mapList(result['runs']);
      final merged = reset
          ? items
          : _mergeById(_runLogs, items, idKey: 'run_id');
      setState(() {
        _runLogs = merged;
        _runLogsLoaded = true;
        _runLogsLoading = false;
        _runLogsHasMore = _hasMore(result, items.length, merged.length);
        _runLogsNextOffset = _nextOffset(
          result,
          fallback: offset + items.length,
        );
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _runLogsLoaded = true;
        _runLogsLoading = false;
        _runLogsError = error.toString();
      });
    }
  }

  Future<void> _refreshActive() => _loadActive(reset: true);

  Future<void> _enablePlugin() async {
    setState(() => _pluginLoading = true);
    try {
      await OmniPluginService.setEnabled(_pluginId, true);
      await _loadPluginAndActive();
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _pluginLoading = false;
        _pluginError = error.toString();
      });
    }
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
        loadFunction: OmniFlowToolClient.getFunction,
        onReplay: _replay,
        onEnhance: _enhanceFunction,
        onDelete: _deleteFunction,
        refreshOnOpen: refreshOnOpen,
      ),
    );
  }

  Future<void> _enhanceFunction(Map<String, dynamic> function) async {
    final functionId = _string(function['function_id']);
    final sourceRunId = _string(function['source_run_id']);
    if (functionId.isEmpty || sourceRunId.isEmpty) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              _text(
                context,
                '该 Function 缺少本地 source_run_id，无法增强',
                'This Function has no local source_run_id and cannot be enhanced',
              ),
            ),
          ),
        );
      }
      return;
    }
    // Enhancement is an Agent operation. Keep the official save_function
    // contract in the prompt and let the normal ACP session execute it, so
    // the user sees the same reasoning/tool activity as any other request.
    await WidgetsBinding.instance.endOfFrame;
    if (!mounted) return;
    final requestKey = DateTime.now().microsecondsSinceEpoch.toString();
    context.push(
      '/home/chat',
      extra: ConversationThreadTarget.newConversation(
        mode: ConversationMode.agent,
        requestKey: requestKey,
        initialMessage: buildFunctionEnhancementPrompt(function),
      ),
    );
  }

  Future<void> _replay(Map<String, dynamic> function) async {
    final functionId = _string(function['function_id']);
    if (functionId.isEmpty) return;
    final accessibilityReady = await showAccessibilityPermissionPrompt(context);
    if (!accessibilityReady || !mounted) return;
    final arguments = await _collectArguments(function);
    if (arguments == null || !mounted) return;
    await _runAction(
      () => OmniFlowToolClient.replayFunction(
        functionId,
        arguments,
        goal: _replayGoal(function, arguments),
      ),
      success: _text(context, '执行已完成', 'Run completed'),
    );
  }

  String _replayGoal(
    Map<String, dynamic> function,
    Map<String, dynamic> arguments,
  ) {
    final name = _string(function['name']);
    final description = _string(function['description']);
    final summary = <String>[
      if (name.isNotEmpty) name,
      if (description.isNotEmpty && description != name) description,
    ];
    final argumentText = arguments.isEmpty
        ? ''
        : '参数: ${jsonEncode(arguments)}';
    final goal = [
      ...summary,
      if (argumentText.isNotEmpty) argumentText,
    ].join('\n').trim();
    return goal.isNotEmpty ? goal : _string(function['function_id']);
  }

  Future<Map<String, dynamic>?> _collectArguments(
    Map<String, dynamic> function,
  ) async {
    final inputSchema = _map(function['input_schema']);
    final properties = _map(inputSchema['properties']);
    if (properties.isEmpty) return <String, dynamic>{};
    final requiredValue = inputSchema['required'];
    final required = requiredValue is List
        ? requiredValue.map((value) => value.toString()).toSet()
        : <String>{};
    final values = <String, String>{};
    final result = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(_text(context, '填写执行参数', 'Run arguments')),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: properties.entries
                .map((entry) {
                  final schema = _map(entry.value);
                  return Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: TextFormField(
                      onChanged: (value) => values[entry.key] = value,
                      decoration: InputDecoration(
                        labelText: required.contains(entry.key)
                            ? '${entry.key} *'
                            : entry.key,
                        helperText: _string(schema['description']).nullIfEmpty,
                      ),
                    ),
                  );
                })
                .toList(growable: false),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: Text(_text(context, '取消', 'Cancel')),
          ),
          FilledButton(
            onPressed: () {
              final parsedValues = <String, dynamic>{};
              for (final entry in properties.entries) {
                final value = (values[entry.key] ?? '').trim();
                if (required.contains(entry.key) && value.isEmpty) {
                  return;
                }
                if (value.isNotEmpty) {
                  parsedValues[entry.key] = _parseArgument(
                    value,
                    _string(_map(entry.value)['type']),
                  );
                }
              }
              Navigator.pop(dialogContext, parsedValues);
            },
            child: Text(_text(context, '开始执行', 'Run')),
          ),
        ],
      ),
    );
    return result;
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
      () => OmniFlowToolClient.deleteFunction(functionId),
      success: _text(context, '复用指令已删除', 'Function deleted'),
      reload: true,
    );
  }

  Future<void> _saveFunctionFromRunLog(Map<String, dynamic> runLog) async {
    final runId = _string(runLog['run_id']);
    if (runId.isEmpty || _registeringRunIds.contains(runId)) return;
    setState(() => _registeringRunIds.add(runId));
    try {
      final registration = await OmniFlowToolClient.registerFunctionFromRunLog(
        runId,
      );
      if (!mounted) return;
      if (!registration.success) {
        throw StateError(
          registration.errorMessage ??
              _text(context, '注册失败', 'Registration failed'),
        );
      }
      final function = <String, dynamic>{
        'name':
            _string(runLog['goal']).nullIfEmpty ??
            _text(context, '未命名复用指令', 'Unnamed Function'),
        'description': _string(runLog['goal']),
        ...registration.function!,
      };
      setState(() {
        _functions = _mergeById(
          <Map<String, dynamic>>[function],
          _functions,
          idKey: 'function_id',
        );
        _functionsLoaded = true;
      });
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
    Future<Map<String, dynamic>> Function() action, {
    required String success,
    bool reload = false,
  }) async {
    try {
      final result = await action();
      if (!mounted) return;
      if (result['success'] == false) {
        throw StateError(
          _string(result['error_message']).nullIfEmpty ??
              _string(result['error_code']).nullIfEmpty ??
              _text(context, 'OmniFlow 操作失败', 'OmniFlow operation failed'),
        );
      }
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(success)));
      if (reload) await _refreshActive();
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(_omniFlowErrorText(error))));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_text(context, '执行中心', 'Execution Center')),
        actions: [
          IconButton(
            tooltip: _text(context, '刷新', 'Refresh'),
            onPressed: _pluginLoading
                ? null
                : (_ready ? _refreshActive : _loadPluginAndActive),
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
    );
  }

  int _initialTabIndex(String? value) {
    return switch (value?.trim().toLowerCase()) {
      'run_log' || 'run_logs' || 'runlog' || 'runlogs' || 'logs' || '1' => 1,
      _ => 0,
    };
  }

  Widget _buildBody() {
    if (_pluginLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_pluginError != null) {
      return _MessageState(
        icon: Icons.error_outline_rounded,
        title: _text(context, '加载失败', 'Failed to load'),
        message: _pluginError!,
        actionLabel: _text(context, '重试', 'Retry'),
        onAction: _loadPluginAndActive,
      );
    }
    if (!_ready) {
      final installed = _plugin?.installed == true;
      return _MessageState(
        icon: Icons.extension_outlined,
        title: installed
            ? _text(context, 'OmniFlow 未启用', 'OmniFlow is disabled')
            : _text(context, '先安装 OmniFlow', 'Install OmniFlow first'),
        message: installed
            ? _text(
                context,
                '启用后即可查看运行记录、注册和执行复用指令。',
                'Enable it to inspect Run Logs and register reusable Functions.',
              )
            : _text(
                context,
                '小万原生 GUI 无需安装；复用指令与 OmniTransfer 运行时会在首次使用时按需准备。',
                'XiaoWan GUI needs no installation. Functions and the OmniTransfer runtime are prepared lazily on first use.',
              ),
        actionLabel: installed
            ? _text(context, '启用插件', 'Enable plugin')
            : _text(context, '前往插件市场', 'Open Plugin Market'),
        onAction: installed
            ? _enablePlugin
            : () => context.push('/home/plugin_market/$_pluginId'),
      );
    }
    return TabBarView(
      controller: _tabController,
      children: [
        _FunctionsTab(
          functions: _functions,
          loading: _functionsLoading,
          error: _functionsError,
          hasMore: _functionsHasMore,
          onRefresh: () => _loadFunctions(reset: true),
          onLoadMore: () => _loadFunctions(reset: false),
          onOpenDetails: _showFunctionDetails,
          onReplay: _replay,
        ),
        _RunLogsTab(
          runLogs: _runLogs,
          functions: _functions,
          loading: _runLogsLoading,
          error: _runLogsError,
          hasMore: _runLogsHasMore,
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
  });

  final List<Map<String, dynamic>> functions;
  final bool loading;
  final String? error;
  final bool hasMore;
  final AsyncCallback onRefresh;
  final AsyncCallback onLoadMore;
  final ValueChanged<Map<String, dynamic>> onOpenDetails;
  final ValueChanged<Map<String, dynamic>> onReplay;

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
        separatorBuilder: (_, __) =>
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
            onReplay: () => onReplay(function),
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
  });

  final Map<String, dynamic> function;
  final VoidCallback onOpenDetails;
  final VoidCallback onReplay;

  @override
  Widget build(BuildContext context) {
    final functionId = _string(function['function_id']);
    final name = _string(function['name']).nullIfEmpty ?? functionId;
    final description = _string(function['description']);
    final steps = _mapList(function['steps']).length;
    final parameters = _map(
      _map(function['input_schema'])['properties'],
    ).length;
    final meta = _text(
      context,
      '$steps 个步骤 · $parameters 个参数',
      '$steps steps · $parameters parameters',
    );
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onOpenDetails,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 13, 8, 13),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    if (description.isNotEmpty && description != name) ...[
                      const SizedBox(height: 3),
                      Text(
                        description,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                    const SizedBox(height: 4),
                    Text(
                      meta,
                      style: Theme.of(context).textTheme.labelSmall?.copyWith(
                        color: Theme.of(context).colorScheme.outline,
                      ),
                    ),
                  ],
                ),
              ),
              TextButton.icon(
                onPressed: onReplay,
                icon: const Icon(Icons.play_arrow_rounded, size: 18),
                label: Text(_text(context, '执行', 'Run')),
              ),
              Icon(
                Icons.chevron_right_rounded,
                color: Theme.of(context).colorScheme.outline,
                size: 20,
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
        separatorBuilder: (_, __) =>
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
          final linkedFunction = _linkedFunction(runLog, functions);
          final linkedFunctionWithSource = linkedFunction == null
              ? null
              : <String, dynamic>{
                  ...linkedFunction,
                  if (_string(linkedFunction['source_run_id']).isEmpty)
                    'source_run_id': _string(runLog['run_id']),
                };
          return _RunLogListItem(
            runLog: runLog,
            onOpen: () =>
                context.push('/task/run_log/${_string(runLog['run_id'])}'),
            onConvert: () => onConvert(runLog),
            linkedFunction: linkedFunctionWithSource,
            registering: registeringRunIds.contains(_string(runLog['run_id'])),
            onOpenFunction: linkedFunctionWithSource == null
                ? null
                : () => onOpenFunction(linkedFunctionWithSource),
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
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
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

Map<String, dynamic>? _linkedFunction(
  Map<String, dynamic> runLog,
  List<Map<String, dynamic>> functions,
) {
  final runId = _string(runLog['run_id']);
  final executedFunctionId = _string(runLog['function_id']);
  for (final function in functions) {
    final functionId = _string(function['function_id']);
    if ((executedFunctionId.isNotEmpty && functionId == executedFunctionId) ||
        (runId.isNotEmpty && _string(function['source_run_id']) == runId)) {
      return function;
    }
  }
  return null;
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

List<Map<String, dynamic>> _mergeById(
  List<Map<String, dynamic>> current,
  List<Map<String, dynamic>> next, {
  required String idKey,
}) {
  final merged = <Map<String, dynamic>>[];
  final seen = <String>{};
  for (final item in [...current, ...next]) {
    final id = _string(item[idKey]);
    if (id.isNotEmpty && !seen.add(id)) continue;
    merged.add(item);
  }
  return List.unmodifiable(merged);
}

bool _hasMore(Map<String, dynamic> result, int fetchedCount, int loadedCount) {
  final explicit = result['has_more'];
  if (explicit is bool) return explicit;
  final total = _intValue(result['total_count']);
  if (total != null) return loadedCount < total;
  return fetchedCount >= 20;
}

int _nextOffset(Map<String, dynamic> result, {required int fallback}) {
  final next = _intValue(result['next_offset']);
  return next != null && next >= 0 ? next : fallback;
}

int? _intValue(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

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

dynamic _parseArgument(String value, String type) => switch (type) {
  'integer' => int.tryParse(value) ?? value,
  'number' => num.tryParse(value) ?? value,
  'boolean' => value.toLowerCase() == 'true',
  'object' || 'array' => jsonDecode(value),
  _ => value,
};

String _text(BuildContext context, String zh, String en) =>
    Localizations.localeOf(context).languageCode == 'en' ? en : zh;

extension on String {
  String? get nullIfEmpty => isEmpty ? null : this;
}
