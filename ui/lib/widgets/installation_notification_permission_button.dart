import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/embedded_terminal_preparation_service.dart';
import 'package:ui/services/special_permission.dart';

/// Offers notification access only while the native installer is running.
/// Installation and foreground interaction never wait for this permission.
class InstallationNotificationPermissionButton extends StatefulWidget {
  const InstallationNotificationPermissionButton({
    super.key,
    this.preparation,
    this.textColor,
    this.checkPermission,
    this.requestPermission,
    this.openSettings,
  });

  final EmbeddedTerminalPreparationService? preparation;
  final Color? textColor;
  final Future<bool> Function()? checkPermission;
  final Future<bool> Function()? requestPermission;
  final Future<void> Function()? openSettings;

  @override
  State<InstallationNotificationPermissionButton> createState() =>
      _InstallationNotificationPermissionButtonState();
}

class _InstallationNotificationPermissionButtonState
    extends State<InstallationNotificationPermissionButton>
    with WidgetsBindingObserver {
  late final EmbeddedTerminalPreparationService _preparation;
  bool? _granted;
  bool _requesting = false;
  bool _denied = false;
  int _permissionRead = 0;

  @override
  void initState() {
    super.initState();
    _preparation =
        widget.preparation ?? EmbeddedTerminalPreparationService.instance;
    WidgetsBinding.instance.addObserver(this);
    _preparation.addListener(_handlePreparationChanged);
    _preparation.startObserving();
    unawaited(_readPermission());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) unawaited(_readPermission());
  }

  void _handlePreparationChanged() {
    if (mounted) setState(() {});
  }

  Future<void> _readPermission() async {
    final read = ++_permissionRead;
    try {
      final granted =
          await (widget.checkPermission ?? isNotificationPermissionGranted)();
      if (!mounted || read != _permissionRead) return;
      setState(() => _granted = granted);
    } catch (_) {
      // An unavailable channel is not a reason to interrupt first launch.
    }
  }

  Future<void> _requestPermission() async {
    if (_requesting) return;
    setState(() => _requesting = true);
    try {
      if (_denied) {
        await (widget.openSettings ?? openNotificationSettings)();
        return;
      }
      final granted =
          await (widget.requestPermission ?? requestNotificationPermission)();
      if (mounted) setState(() => _denied = !granted);
      await _readPermission();
    } catch (_) {
      // Notification access is optional; preparation continues in its owner.
    } finally {
      if (mounted) setState(() => _requesting = false);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _preparation.removeListener(_handlePreparationChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_granted != false || _preparation.snapshot?.running != true) {
      return const SizedBox.shrink();
    }
    final english = Localizations.localeOf(context).languageCode == 'en';
    return TextButton.icon(
      key: const ValueKey('installation-notification-permission'),
      style: TextButton.styleFrom(foregroundColor: widget.textColor),
      onPressed: _requesting ? null : () => unawaited(_requestPermission()),
      icon: const Icon(LucideIcons.bell, size: 16),
      label: Text(
        _denied
            ? (english ? 'Notification settings' : '通知设置')
            : (english ? 'Show installation notification' : '显示安装通知'),
      ),
    );
  }
}
