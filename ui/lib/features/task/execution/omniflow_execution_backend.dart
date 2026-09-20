import 'dart:convert';

import 'package:ui/features/home/pages/authorize/accessibility_permission_prompt.dart';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_flow_controller.dart';
import 'package:ui/features/task/run_log/omniflow_tool_client.dart';
import 'package:ui/services/omni_plugin_service.dart';

import 'execution_backend.dart';

/// The sole execution-center adapter for OmniFlow. No engine or lifecycle here.
class OmniFlowExecutionBackend implements ExecutionBackend {
  const OmniFlowExecutionBackend();
  static const _pluginId = 'com.omnimind.omni-vlm-lite';

  Future<T> _attempt<T>(Future<T> Function() action) async {
    try {
      return await action();
    } on PlatformException catch (e) {
      throw ExecutionFailure(e.message ?? e.code);
    } on StateError catch (e) {
      throw ExecutionFailure(e.message.toString());
    }
  }

  Future<Map<String, dynamic>> _checked(Future<Map<String, dynamic>> result) =>
      _attempt(() async {
        final payload = await result;
        if (payload['success'] == false) {
          final error = payload['error'] is Map
              ? payload['error'] as Map
              : const {};
          throw ExecutionFailure(
            _string(payload['error_message']).nullIfEmpty ??
                _string(error['message']).nullIfEmpty ??
                _string(payload['error_code']).nullIfEmpty ??
                _string(error['code']).nullIfEmpty ??
                'Execution failed',
          );
        }
        return payload;
      });

  @override
  Future<ExecutionAvailability> availability() => _attempt(() async {
    final plugin = await OmniPluginService.getPlugin(_pluginId);
    return ExecutionAvailability(
      installed: plugin?.installed == true,
      enabled: plugin?.enabled == true,
      settingsRoute: '/home/plugin_market/$_pluginId',
    );
  });

  @override
  Future<bool> authorize(BuildContext context) =>
      showAccessibilityPermissionPrompt(context);

  @override
  Future<void> enable() => _attempt(() async {
    await OmniPluginService.setEnabled(_pluginId, true);
  });

  @override
  Future<ExecutionPage> list(
    ExecutionCollectionKind kind, {
    required int limit,
    required int offset,
  }) async {
    final functions = kind == ExecutionCollectionKind.functions;
    final result = await _checked(
      functions
          ? OmniFlowToolClient.listFunctions(limit: limit, offset: offset)
          : OmniFlowToolClient.listRunLogs(limit: limit, offset: offset),
    );
    final raw = result[functions ? 'functions' : 'runs'];
    final items = raw is List
        ? raw.whereType<Map>().map((m) => Map<String, dynamic>.from(m)).toList()
        : <Map<String, dynamic>>[];
    final total = int.tryParse('${result['total_count']}');
    final next = int.tryParse('${result['next_offset']}');
    return ExecutionPage(
      items,
      hasMore: result['has_more'] is bool
          ? result['has_more'] as bool
          : total != null
          ? offset + items.length < total
          : items.length >= limit,
      nextOffset: next != null && next >= 0 ? next : offset + items.length,
    );
  }

  @override
  Future<Map<String, dynamic>> getFunction(String id) =>
      _checked(OmniFlowToolClient.getFunction(id));

  @override
  Future<Map<String, dynamic>> updateFunctionMetadata(
    String id,
    String name,
    String description,
  ) async {
    // Read the current artifact instead of writing the page's cached steps.
    final current = await getFunction(id);
    final function = Map<String, dynamic>.from(
      current['function'] is Map ? current['function'] as Map : current,
    )..remove('source_run_id');
    function['name'] = name.trim();
    function['description'] = description.trim();
    await _checked(OmniFlowToolClient.saveFunction(function));
    return getFunction(id);
  }

  @override
  Future<Map<String, dynamic>> getRunLog(String id) =>
      _checked(OmniFlowToolClient.getRunLog(id));
  @override
  Future<Map<String, dynamic>> getRunLogState(String id) =>
      _checked(OmniFlowToolClient.getRunLogState(id));

  @override
  Future<void> run(
    Map<String, dynamic> function,
    Map<String, dynamic> arguments,
  ) async {
    final name = _string(function['name']);
    final description = _string(function['description']);
    final id = _string(function['function_id']);
    final goal = [
      if (name.isNotEmpty) name,
      if (description.isNotEmpty && description != name) description,
      if (arguments.isNotEmpty) '参数: ${jsonEncode(arguments)}',
    ].join('\n');
    await _checked(
      OmniFlowToolClient.replayFunction(
        id,
        arguments,
        goal: goal.isEmpty ? id : goal,
      ),
    );
  }

  @override
  Future<void> delete(String id) async {
    await _checked(OmniFlowToolClient.deleteFunction(id));
  }

  @override
  Future<Map<String, dynamic>> register(Map<String, dynamic> runLog) =>
      _attempt(() async {
        final result = await OmniFlowToolClient.registerFunctionFromRunLog(
          _string(runLog['run_id']),
        );
        if (!result.success) {
          throw ExecutionFailure(result.errorMessage ?? 'Registration failed');
        }
        return {
          'name': _string(runLog['goal']),
          'description': _string(runLog['goal']),
          ...result.function!,
        };
      });

  @override
  String enhancementRequest(Map<String, dynamic> function) {
    if (_string(function['source_run_id']).isEmpty ||
        _string(function['function_id']).isEmpty) {
      throw const ExecutionFailure(
        'Missing local source_run_id; cannot enhance this Function',
      );
    }
    return buildFunctionEnhancementPrompt(function);
  }

  @override
  Future<void> record({
    required BuildContext context,
    required FocusNode focus,
    required bool Function() isMounted,
  }) async {
    await ManualRecordingFlowController.startStandalone(
      context: context,
      inputFocusNode: focus,
      userMessageText: '',
      recordDebugScreenshots: false,
      isMounted: isMounted,
    );
  }
}

String _string(dynamic value) => value?.toString().trim() ?? '';

extension on String {
  String? get nullIfEmpty => isEmpty ? null : this;
}

String buildFunctionEnhancementPrompt(Map<String, dynamic> function) {
  final functionId = _string(function['function_id']);
  final sourceRunId = _string(function['source_run_id']);
  // Passing function/functions selects metadata enhancement and bypasses the
  // canonical RunLog authoring workflow. Only forward the source identity.
  final arguments = jsonEncode({
    'run_id': sourceRunId,
    'enhance': true,
    'instruction':
        '用户要把这次录制转换为可重复使用的操作，而不只是重放相同常量。'
        '请根据实际动作和页面证据识别用户在执行前可指定的业务输入，'
        '例如搜索词、名称或填写内容，将这些输入显式分类为 task_parameter，'
        '并声明对应 input_schema 与 action/render bindings。'
        '固定界面控件、协议常量仍是 stable；依赖实时页面观察或计算的值仍是 online_observation。'
        '不要编造录制中不存在的输入、猜测绑定目标或将在线决策强行参数化。',
  });
  return '请调用本机 OmniFlow 官方 save_function，对原始成功轨迹执行完整 authoring。'
      '工具参数严格使用：$arguments。'
      '不要传 function、functions 或 arguments；不要执行 Function，'
      '不要调用 list_run_logs/get_run_log/get_function，也不要自行编写 Function 或增强规则。'
      '由 OmniFlow 内置的官方 authoring 流程完成语义分析、Function 提炼、参数绑定、编译和本地注册。'
      '原有 Function 保留；本次可能生成多个 Function。'
      '请依据工具真实返回的 functions 逐项报告名称、function_id、参数和绑定；'
      '失败或没有生成参数时如实说明，不要把描述润色当作参数化成功。'
      '\n\n原 Function（仅作来源说明，不作为工具参数）：$functionId'
      '\nsource_run_id: $sourceRunId';
}
