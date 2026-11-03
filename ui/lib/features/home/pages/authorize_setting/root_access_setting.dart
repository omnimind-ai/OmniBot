import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/root_access_service.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/settings_ui.dart';

class RootAccessSetting extends StatefulWidget {
  const RootAccessSetting({super.key});

  @override
  State<RootAccessSetting> createState() => _RootAccessSettingState();
}

class _RootAccessSettingState extends State<RootAccessSetting>
    with WidgetsBindingObserver {
  RootAccessSnapshot _snapshot = const RootAccessSnapshot();
  bool _loaded = false;
  bool _busy = false;
  bool _failed = false;
  int _revision = 0;
  Timer? _refreshTimer;

  bool get _english => Localizations.localeOf(context).languageCode == 'en';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !_busy) _refresh();
  }

  Future<void> _refresh() async {
    if (_busy) return;
    _refreshTimer?.cancel();
    final revision = _revision;
    try {
      final snapshot = await RootAccessService.status();
      if (!mounted || revision != _revision) return;
      setState(() {
        _snapshot = snapshot;
        _loaded = true;
        _failed = false;
      });
      if (snapshot.checking) {
        _refreshTimer = Timer(const Duration(milliseconds: 500), _refresh);
      }
    } catch (_) {
      if (mounted && revision == _revision) setState(() => _failed = true);
    }
  }

  Future<void> _toggle(bool enabled) async {
    if (enabled) {
      if (_busy) return;
      final confirmed = await AppDialog.confirm(
        context,
        title: _english ? 'Enable Root access' : '启用 Root 高级能力',
        content: _english
            ? 'Allow the agent to use Root shell and system controls. Privileged commands and changes still require your confirmation. You can disable access at any time.'
            : '允许 Agent 使用 Root shell 和系统控制能力。高权限命令与状态修改仍需逐次确认，你可以随时关闭。',
        cancelText: _english ? 'Cancel' : '取消',
        confirmText: _english ? 'Enable and authorize' : '启用并授权',
      );
      if (confirmed != true || !mounted) return;
    }
    await _perform(
      () => RootAccessService.setEnabled(enabled),
      pending: RootAccessSnapshot(
        enabled: enabled,
        status: enabled ? 'CHECKING' : 'DISABLED',
      ),
    );
  }

  Future<void> _perform(
    Future<RootAccessSnapshot> Function() operation, {
    required RootAccessSnapshot pending,
  }) async {
    final revision = ++_revision;
    setState(() {
      _snapshot = pending;
      _busy = true;
      _failed = false;
    });
    try {
      final result = await operation();
      if (mounted && revision == _revision) {
        setState(() {
          _snapshot = result;
          _loaded = true;
        });
      }
    } catch (_) {
      if (mounted && revision == _revision) {
        setState(() => _busy = false);
        await _refresh();
        if (mounted && revision == _revision) setState(() => _failed = true);
      }
    } finally {
      if (mounted && revision == _revision) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        OmniSettingsTile(
          icon: LucideIcons.shield,
          title: _english ? 'Root access' : 'Root 高级能力',
          subtitle: _failed
              ? (_english
                    ? 'Could not read access status. Retry below.'
                    : '无法读取授权状态，请重试。')
              : '${_snapshot.label(_english)}\n${_english ? 'Optional system controls and Root shell. Requests access only after you enable it.' : '可选的系统控制与 Root shell；主动开启后才申请授权。'}',
          translateText: false,
          trailing: OmniSettingsSwitch(
            key: const ValueKey('root-access-switch'),
            value: _snapshot.enabled,
            enabled: _snapshot.enabled || (_loaded && !_busy && !_failed),
            onToggle: _toggle,
          ),
        ),
        if (_failed ||
            (_snapshot.enabled && !_snapshot.granted && !_snapshot.checking))
          OmniSettingsTile(
            key: const ValueKey('root-access-retry'),
            icon: LucideIcons.refreshCw,
            title: _failed
                ? (_english ? 'Refresh access status' : '刷新授权状态')
                : (_english ? 'Retry Root authorization' : '重新申请 Root 授权'),
            translateText: false,
            onTap: _busy
                ? null
                : () {
                    if (_failed) {
                      _refresh();
                    } else {
                      _perform(
                        RootAccessService.requestAccess,
                        pending: const RootAccessSnapshot(
                          enabled: true,
                          status: 'CHECKING',
                        ),
                      );
                    }
                  },
          ),
      ],
    );
  }
}
