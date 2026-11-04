import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';

typedef NaGrantRequest = Future<Map<String, dynamic>> Function(
  String operation, {
  String? id,
  Map<String, dynamic> payload,
});

Map<String, dynamic> _map(dynamic value) => value is Map
    ? value.map((key, value) => MapEntry(key.toString(), value))
    : {};

bool canSaveNaRepeatGrant(Map<String, dynamic> action) {
  final connector = _map(action['connector']);
  final account = _map(action['account']);
  if (action['id'] is! String ||
      (action['id'] as String).isEmpty ||
      action['routineId'] is! String ||
      (action['routineId'] as String).isEmpty ||
      !['pending', 'completed'].contains(action['status']) ||
      action['args'] is! Map ||
      connector['provider'] != 'composio' ||
      connector['noAuth'] is! bool ||
      !RegExp(r'^[A-Z0-9_][A-Z0-9_]{1,159}$')
          .hasMatch(connector['tool']?.toString() ?? '') ||
      (connector['tool']?.toString() ?? '').startsWith('COMPOSIO_') ||
      action['operation'] != 'composio.${connector['tool']}' ||
      !connector.containsKey('authConfigId') ||
      !RegExp(r'^[a-z0-9_][a-z0-9_-]{0,63}$')
          .hasMatch(connector['toolkit']?.toString() ?? '') ||
      !RegExp(r'^\d{8}_\d{2}$')
          .hasMatch(connector['version']?.toString() ?? '') ||
      connector['version'] == '00000000_00') {
    return false;
  }
  for (final key in ['schemaHash', 'policyHash', 'reviewHash']) {
    if (!RegExp(r'^[0-9a-f]{64}$').hasMatch(connector[key]?.toString() ?? '')) {
      return false;
    }
  }
  if (connector['noAuth'] == true) {
    return connector['accountId'] == null &&
        connector['authConfigId'] == null &&
        account['id'] == 'no-auth:${connector['toolkit']}';
  }
  return connector['accountId'] is String &&
      connector['authConfigId'] is String &&
      (connector['authConfigId'] as String).isNotEmpty &&
      account['id'] == connector['accountId'];
}

String _actionIdentity(Map<String, dynamic> action) =>
    '${action['id']}\u0000${action['routineId']}\u0000${action['status']}\u0000${_map(action['connector'])['reviewHash']}';

Future<bool> _confirmGrant(
  BuildContext context,
  Widget content,
  String title,
  String confirm,
) async {
  var accepted = false;
  return await showDialog<bool>(
        context: context,
        builder: (dialog) => AlertDialog(
          title: Text(title),
          content: SingleChildScrollView(child: content),
          actions: [
            TextButton(
              onPressed: () {
                if (!accepted) Navigator.of(dialog).pop(false);
              },
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () {
                if (accepted) return;
                accepted = true;
                Navigator.of(dialog).pop(true);
              },
              child: Text(confirm),
            ),
          ],
        ),
      ) ??
      false;
}

class NaRepeatGrantButton extends StatefulWidget {
  const NaRepeatGrantButton({
    super.key,
    required this.action,
    required this.request,
    this.onChanged,
    this.disabled = false,
    this.ownerKey,
  });
  final Map<String, dynamic> action;
  final NaGrantRequest request;
  final FutureOr<void> Function()? onChanged;
  final bool disabled;
  final Object? ownerKey;
  @override
  State<NaRepeatGrantButton> createState() => _NaRepeatGrantButtonState();
}

class _NaRepeatGrantButtonState extends State<NaRepeatGrantButton> {
  bool _busy = false, _saved = false;
  int _generation = 0;
  String? _error;
  BuildContext? _dialog;

  @override
  void didUpdateWidget(covariant NaRepeatGrantButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.ownerKey != widget.ownerKey ||
        oldWidget.request != widget.request ||
        _actionIdentity(oldWidget.action) != _actionIdentity(widget.action) ||
        (!oldWidget.disabled && widget.disabled)) {
      ++_generation;
      _busy = false;
      _saved = false;
      _error = null;
      final dialog = _dialog;
      if (dialog != null) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (dialog.mounted && ModalRoute.of(dialog)?.isCurrent == true) {
            Navigator.of(dialog).pop(false);
          }
        });
      }
    }
  }

  @override
  void dispose() {
    ++_generation;
    super.dispose();
  }

  Future<void> _review() async {
    if (_busy ||
        _saved ||
        widget.disabled ||
        !canSaveNaRepeatGrant(widget.action)) {
      return;
    }
    final generation = ++_generation;
    final action =
        jsonDecode(jsonEncode(widget.action)) as Map<String, dynamic>;
    final connector = _map(action['connector']),
        account = _map(action['account']);
    final request = widget.request;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final accepted = await _confirmGrant(
        context,
        Builder(
          builder: (dialog) {
            _dialog = dialog;
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('职责：${action['routineTitle'] ?? action['routineId']}'),
                Text('工具：${connector['tool']} · ${connector['version']}'),
                Text('账号：${account['label'] ?? account['id']}'),
                if (connector['noAuth'] != true)
                  SelectableText('账号标识：${account['id']}'),
                const SizedBox(height: 12),
                const Text('准确参数'),
                SelectableText(
                  const JsonEncoder.withIndent('  ').convert(action['args']),
                ),
                const SizedBox(height: 12),
                const Text(
                  '默认有效 30 天。仅允许当前职责在工具、账号、版本与参数完全相同时重复执行。职责或连接配置变化会使授权失效，你也可随时撤销。',
                ),
                if (action['status'] == 'pending') ...[
                  const SizedBox(height: 8),
                  const Text('保存不会执行本次操作；当前待审核操作仍需单独审核执行。'),
                ],
              ],
            );
          },
        ),
        '保存这项重复授权？',
        '确认保存重复授权',
      );
      _dialog = null;
      if (!accepted ||
          !mounted ||
          generation != _generation ||
          widget.disabled) {
        return;
      }
      await request(
        'connectors.grants.create',
        payload: {'actionId': action['id']},
      );
      if (!mounted || generation != _generation) return;
      setState(() => _saved = true);
      await widget.onChanged?.call();
    } catch (_) {
      if (mounted && generation == _generation) {
        setState(() => _error = '授权未能确认，请刷新后重新审核。');
      }
    } finally {
      _dialog = null;
      if (mounted && generation == _generation) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!canSaveNaRepeatGrant(widget.action)) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        TextButton(
          key: ValueKey('na-save-repeat-${widget.action['id']}'),
          onPressed: widget.disabled || _busy || _saved ? null : _review,
          child: Text(
            _saved
                ? '重复授权已保存'
                : _busy
                ? '正在确认重复授权…'
                : '保存重复授权',
          ),
        ),
        if (_error != null)
          Text(
            _error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
      ],
    );
  }
}

String _grantStatus(dynamic value) =>
    const {
      'active': '有效',
      'revoked': '已撤销',
      'expired': '已到期',
      'invalidated': '已失效',
      'suspended': '已暂停，需重新审核',
    }[value] ??
    '状态待确认';
String _grantReason(dynamic value) =>
    const {
      'expired': '授权期限已到期。',
      'user_revoked': '你已撤销这项授权。',
      'routine_deleted': '原职责已删除。',
      'routine_changed': '职责已修改，需要重新审核。',
      'connection_policy_changed': '连接配置已改变，需要重新审核。',
      'account_or_schema_changed': '账号或工具定义已改变。',
      'account_or_schema_unavailable': '无法验证原账号或工具定义。',
      'unconfirmed_result': '上次操作结果不明确，需要先核实。',
      'execution_interrupted': '上次执行中断，需要先核实结果。',
    }[value] ??
    '这项授权暂时不可用，请重新审核。';

class NaConnectorGrantList extends StatefulWidget {
  const NaConnectorGrantList({
    super.key,
    required this.request,
    this.disabled = false,
    this.ownerKey,
  });
  final NaGrantRequest request;
  final bool disabled;
  final Object? ownerKey;
  @override
  State<NaConnectorGrantList> createState() => _NaConnectorGrantListState();
}

class _NaConnectorGrantListState extends State<NaConnectorGrantList> {
  List<Map<String, dynamic>> _grants = [];
  bool _loading = true;
  String? _busy, _error;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    if (!widget.disabled) {
      unawaited(_load());
    } else {
      _loading = false;
    }
  }

  @override
  void dispose() {
    ++_generation;
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant NaConnectorGrantList oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.ownerKey != widget.ownerKey ||
        oldWidget.request != widget.request ||
        oldWidget.disabled != widget.disabled) {
      ++_generation;
      _grants = [];
      _busy = null;
      _error = null;
      if (!widget.disabled) {
        unawaited(_load());
      } else {
        _loading = false;
      }
    }
  }

  Future<void> _load() async {
    if (widget.disabled) return;
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await widget.request(
        'connectors.grants.list',
        payload: {},
      );
      if (!mounted || generation != _generation) return;
      setState(
        () => _grants = (result['grants'] as List? ?? [])
            .whereType<Map>()
            .map(_map)
            .toList(),
      );
    } catch (_) {
      if (mounted && generation == _generation) {
        setState(() => _error = '暂时无法读取重复授权，请重试。');
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _revoke(Map<String, dynamic> grant) async {
    if (_busy != null || widget.disabled) return;
    final generation = ++_generation, request = widget.request;
    setState(() => _busy = grant['id']?.toString());
    try {
      final accepted = await _confirmGrant(
        context,
        const Text('撤销后，该职责需要重新审核才能再次执行这项操作。已经发出的远端操作不能撤回。'),
        '撤销这项重复授权？',
        '确认撤销',
      );
      if (!accepted ||
          !mounted ||
          generation != _generation ||
          widget.disabled) {
        return;
      }
      final result = await request(
        'connectors.grants.revoke',
        id: grant['id']?.toString(),
        payload: {},
      );
      if (!mounted || generation != _generation) return;
      final changed = _map(result['grant']);
      if (changed['id'] != grant['id']) throw StateError('授权归属不符');
      setState(
        () => _grants = _grants
            .map((row) => row['id'] == grant['id'] ? changed : row)
            .toList(),
      );
    } catch (_) {
      if (mounted && generation == _generation) {
        setState(() => _error = '撤销结果未能确认，请刷新后核对。');
      }
    } finally {
      if (mounted && generation == _generation) setState(() => _busy = null);
    }
  }

  @override
  Widget build(BuildContext context) => Column(
    key: const Key('na-connector-grants'),
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text('重复操作授权', style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 6),
      const Text('仅在你明确保存后，指定职责才可重复执行完全相同的操作。'),
      TextButton(
        onPressed: widget.disabled || _loading || _busy != null ? null : _load,
        child: Text(_loading ? '正在读取授权…' : '刷新重复授权'),
      ),
      if (_error != null)
        Text(
          _error!,
          style: TextStyle(color: Theme.of(context).colorScheme.error),
        ),
      if (!_loading && _error == null && _grants.isEmpty)
        const Text('还没有重复操作授权。'),
      for (final grant in _grants)
        Padding(
          padding: const EdgeInsets.only(top: 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${grant['routineTitle'] ?? grant['routineId']} · ${_grantStatus(grant['status'])}',
              ),
              SelectableText('${grant['tool']} · ${grant['version']}'),
              Text(
                grant['noAuth'] == true
                    ? '无需账号授权的公开工具'
                    : '账号：${grant['accountId']}',
              ),
              Text(
                '到期：${DateTime.tryParse(grant['expiresAt']?.toString() ?? '')?.toLocal().toString().split('.').first ?? '待确认'}',
              ),
              SelectableText('来源操作：${grant['sourceActionId']}'),
              if (grant['status'] != 'active')
                Text(_grantReason(grant['reason'])),
              if (['active', 'suspended'].contains(grant['status']))
                TextButton(
                  key: ValueKey('na-revoke-grant-${grant['id']}'),
                  onPressed: widget.disabled || _busy != null
                      ? null
                      : () => _revoke(grant),
                  child: Text(_busy == grant['id'] ? '正在确认…' : '撤销授权'),
                ),
            ],
          ),
        ),
    ],
  );
}
