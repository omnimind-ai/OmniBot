import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/special_permission.dart';

/// Device delivery preferences for server-owned Na notifications. This widget
/// never creates a chat, starts a model turn, or receives connector credentials.
class NaNotificationSettings extends StatefulWidget {
  const NaNotificationSettings({super.key, this.disabled = false});
  final bool disabled;

  @override
  State<NaNotificationSettings> createState() => _NaNotificationSettingsState();
}

class _NaNotificationSettingsState extends State<NaNotificationSettings>
    with WidgetsBindingObserver {
  Map<String, dynamic> _data = {};
  String? _owner, _error;
  bool _loading = true, _busy = false;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_load());
  }

  @override
  void dispose() {
    ++_generation;
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !_busy) unawaited(_load());
  }

  @override
  void didUpdateWidget(covariant NaNotificationSettings oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.disabled && !widget.disabled && !_busy) unawaited(_load());
  }

  Future<void> _load() async {
    final generation = ++_generation;
    setState(() => _loading = true);
    try {
      final response = await AgentRuntimeService.requestNaNotifications();
      if (!mounted || generation != _generation) return;
      setState(() {
        _owner = response.owner;
        _data = response.data;
        _error = null;
      });
    } catch (_) {
      if (mounted && generation == _generation) {
        setState(() => _error = '暂时无法读取通知设置，请重试。');
      }
    } finally {
      if (mounted && generation == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _setEnabled(bool enabled) async {
    if (_busy || widget.disabled || _owner == null) return;
    final owner = _owner!;
    final generation = ++_generation;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      if (enabled && !await ensureNotificationPermission()) {
        if (mounted && generation == _generation) {
          setState(() => _error = '尚未允许发送通知，可在系统设置中开启。');
        }
        return;
      }
      final response = await AgentRuntimeService.requestNaNotifications(
        owner: owner,
        enabled: enabled,
      );
      if (!mounted || generation != _generation) return;
      setState(() => _data = response.data);
    } catch (_) {
      if (mounted && generation == _generation) {
        setState(() => _error = '未能更改提醒设置，请刷新后重试。');
      }
    } finally {
      if (mounted && generation == _generation) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final enabled = _data['enabled'] == true;
    final disabled = widget.disabled || _loading || _busy || _owner == null;
    final warning = _error ?? _data['error']?.toString();
    return Column(
      key: const Key('na-notification-settings'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Na 主动消息', style: Theme.of(context).textTheme.titleMedium),
        SwitchListTile(
          key: const Key('na-notifications-enabled'),
          contentPadding: EdgeInsets.zero,
          title: const Text('接收主动消息'),
          subtitle: Text(
            enabled
                ? _data['connectionActive'] == true
                      ? '后台连接已开启，点击通知可返回原对话。'
                      : '已开启，将保持后台连接并补取未收到的提醒。'
                : '开启后，Na 的提醒和关注事项会发送到系统通知。',
          ),
          value: enabled,
          onChanged: disabled ? null : _setEnabled,
        ),
        if (_data['developmentMode'] == true)
          const Padding(
            padding: EdgeInsets.only(bottom: 8),
            child: Text('令牌加密保存在本设备，关闭后删除。'),
          ),
        if (enabled)
          const Padding(
            padding: EdgeInsets.only(bottom: 8),
            child: Text('省电限制可能延迟提醒。'),
          ),
        if (_data['permissionGranted'] == false && enabled)
          const Text('系统通知权限已关闭，开启后才能接收提醒。'),
        if (warning != null && warning.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              warning,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
        TextButton(
          key: const Key('na-notifications-refresh'),
          onPressed: _busy || _loading ? null : _load,
          child: Text(_loading ? '正在读取通知设置…' : '刷新通知设置'),
        ),
      ],
    );
  }
}
