import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
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
    final locale = Localizations.localeOf(context);
    final palette = context.omniPalette;
    final rawError = _preparation.snapshot?.error?.trim();
    final localizedError = rawError != null && rawError.isNotEmpty
        ? LegacyTextLocalizer.localize(rawError, locale: locale)
        : null;
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
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  LegacyTextLocalizer.localize(
                                    '系统安装失败',
                                    locale: locale,
                                  ),
                                  style: TextStyle(
                                    color: palette.textPrimary,
                                    fontSize: 15,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                if (localizedError != null) ...[
                                  const SizedBox(height: 2),
                                  Text(
                                    localizedError,
                                    maxLines: 2,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      color: palette.textSecondary,
                                      fontSize: 12,
                                      height: 1.35,
                                    ),
                                  ),
                                ],
                              ],
                            ),
                          ),
                          const SizedBox(width: 8),
                          TextButton(
                            key: const ValueKey('startup-environment-retry'),
                            onPressed: () => unawaited(_preparation.retry()),
                            child: Text(
                              LegacyTextLocalizer.localize(
                                '重试安装',
                                locale: locale,
                              ),
                            ),
                          ),
                          IconButton(
                            key: const ValueKey('startup-environment-dismiss'),
                            onPressed: () => setState(() => _dismissed = true),
                            icon: Icon(
                              LucideIcons.x,
                              size: 18,
                              semanticLabel: LegacyTextLocalizer.localize(
                                '关闭',
                                locale: locale,
                              ),
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
