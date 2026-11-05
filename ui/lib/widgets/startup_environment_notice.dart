import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/services/embedded_terminal_preparation_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/omni_glass.dart';

/// Observes the native installation without owning its work or blocking routes.
class StartupEnvironmentNotice extends StatefulWidget {
  const StartupEnvironmentNotice({
    super.key,
    required this.child,
    this.preparation,
  });

  final Widget child;
  final EmbeddedTerminalPreparationService? preparation;

  @override
  State<StartupEnvironmentNotice> createState() =>
      _StartupEnvironmentNoticeState();
}

class _StartupEnvironmentNoticeState extends State<StartupEnvironmentNotice> {
  late final EmbeddedTerminalPreparationService _preparation;
  bool _dismissed = false;

  @override
  void initState() {
    super.initState();
    _preparation =
        widget.preparation ?? EmbeddedTerminalPreparationService.instance;
    if (!GoRouterManager.isSubEngine) {
      _preparation.addListener(_handleChange);
      _preparation.startObserving();
    }
  }

  void _handleChange() {
    if (!mounted) return;
    setState(() {
      if (!_preparation.failed || _preparation.retrying) _dismissed = false;
    });
  }

  @override
  void dispose() {
    _preparation.removeListener(_handleChange);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final palette = context.omniPalette;
    return Stack(
      fit: StackFit.expand,
      children: [
        widget.child,
        if (!GoRouterManager.isSubEngine &&
            _preparation.failed &&
            !_preparation.retrying &&
            !_dismissed)
          Positioned(
            top: 8,
            left: 16,
            right: 16,
            child: SafeArea(
              bottom: false,
              child: Align(
                alignment: Alignment.topCenter,
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 560),
                  child: Semantics(
                    liveRegion: true,
                    child: OmniGlassCard(
                      key: const ValueKey('startup-environment-failure'),
                      padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              english
                                  ? 'System installation failed'
                                  : '系统安装失败',
                              style: TextStyle(
                                color: palette.textPrimary,
                                fontSize: 15,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          TextButton(
                            key: const ValueKey('startup-environment-retry'),
                            onPressed: () => unawaited(_preparation.retry()),
                            child: Text(english ? 'Retry' : '重试安装'),
                          ),
                          IconButton(
                            key: const ValueKey('startup-environment-dismiss'),
                            onPressed: () => setState(() => _dismissed = true),
                            icon: Icon(
                              LucideIcons.x,
                              size: 18,
                              semanticLabel: english ? 'Dismiss' : '关闭',
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}
