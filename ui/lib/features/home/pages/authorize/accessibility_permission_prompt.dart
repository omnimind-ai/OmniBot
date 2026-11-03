import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:flutter/services.dart';
import 'package:ui/services/special_permission.dart';

Future<bool> showAccessibilityPermissionPrompt(BuildContext context) async {
  if (await _isAccessibilityReady()) return true;
  if (!context.mounted) return false;
  return await showOmniGlassCardSheet<bool>(
        context: context,
        barrierDismissible: false,
        builder: (_) => const _AccessibilityPermissionDialog(),
      ) ??
      false;
}

class _AccessibilityPermissionDialog extends StatefulWidget {
  const _AccessibilityPermissionDialog();

  @override
  State<_AccessibilityPermissionDialog> createState() =>
      _AccessibilityPermissionDialogState();
}

class _AccessibilityPermissionDialogState
    extends State<_AccessibilityPermissionDialog>
    with WidgetsBindingObserver {
  bool _settingsOpened = false;
  bool _checking = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _settingsOpened) {
      unawaited(_finishWhenReady());
    }
  }

  Future<void> _openSettings() async {
    if (_checking) return;
    setState(() {
      _settingsOpened = true;
      _error = null;
    });
    try {
      await spePermission.invokeMethod<void>(
        'openAndroidGuiAccessibilitySettings',
      );
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() {
        _settingsOpened = false;
        _error = error.message ?? error.code;
      });
    }
  }

  Future<void> _finishWhenReady() async {
    if (_checking) return;
    setState(() {
      _checking = true;
      _error = null;
    });
    try {
      for (var attempt = 0; attempt < 20; attempt += 1) {
        if (await _isAccessibilityReady()) {
          if (mounted) Navigator.of(context).pop(true);
          return;
        }
        await Future<void>.delayed(const Duration(milliseconds: 200));
      }
      if (!mounted) return;
      setState(() {
        _error = _text(
          context,
          '尚未开启无障碍，请打开系统开关后返回。',
          'Accessibility is still disabled. Turn on the switch and return.',
        );
      });
    } finally {
      if (mounted) setState(() => _checking = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return OmniGlassAlertDialog(
      title: Text(_text(context, '开启无障碍权限', 'Enable accessibility permission')),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _text(
              context,
              '小万需要读取页面并执行点击、滑动和输入。',
              'Omnibot needs to observe the screen and perform taps, swipes, and text input.',
            ),
          ),
          const SizedBox(height: 14),
          Text(
            _text(context, '开启位置', 'Where to enable it'),
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 6),
          Text(
            _text(
              context,
              '系统设置 → 无障碍 → 已下载的应用（或已安装的服务）→ 小万 → 开启',
              'System Settings → Accessibility → Downloaded apps (or Installed services) → Omnibot → On',
            ),
          ),
          if (_checking) ...[
            const SizedBox(height: 16),
            const LinearProgressIndicator(),
          ],
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ],
        ],
      ),
      actions: [
        TextButton(
          onPressed: _checking ? null : () => Navigator.of(context).pop(false),
          child: Text(_text(context, '取消', 'Cancel')),
        ),
        FilledButton(
          onPressed: _checking ? null : _openSettings,
          child: Text(
            _text(context, '打开系统无障碍设置', 'Open Accessibility settings'),
          ),
        ),
      ],
    );
  }
}

Future<bool> _isAccessibilityReady() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'isAndroidGuiAccessibilityReady',
        ) ??
        false;
  } on PlatformException {
    return false;
  }
}

String _text(BuildContext context, String zh, String en) =>
    Localizations.localeOf(context).languageCode == 'en' ? en : zh;
