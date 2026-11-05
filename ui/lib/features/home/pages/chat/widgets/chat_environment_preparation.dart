import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/services/embedded_terminal_preparation_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/installation_notification_permission_button.dart';

import '../../command_overlay/widgets/cards/bot_status.dart';
import 'chat_message_viewport.dart';

/// Environment readiness belongs to the installer, before any ACP prompt.
class ChatEnvironmentPreparation extends StatelessWidget {
  const ChatEnvironmentPreparation({
    super.key,
    required this.preparation,
    this.textColor,
  });

  final EmbeddedTerminalPreparationService preparation;
  final Color? textColor;

  @override
  Widget build(BuildContext context) {
    final color = textColor ?? context.omniPalette.textSecondary;
    final english = Localizations.localeOf(context).languageCode == 'en';
    final failed = preparation.failed && !preparation.retrying;
    final cannotRead = preparation.observationError != null && !failed;
    final message = failed
        ? (english
              ? 'System installation failed. Tap Retry to continue.'
              : '系统安装失败，请重试安装')
        : cannotRead
        ? (english
              ? 'Unable to check system setup. Tap Retry.'
              : '暂时无法读取安装状态，点重试继续')
        : (english
              ? 'Installing the system and basic tools…'
              : '正在后台安装系统与基础工具…');
    final label = Text(
      'OmniBot',
      style: TextStyle(
        color: color,
        fontSize: 28,
        fontWeight: FontWeight.w500,
        letterSpacing: -0.5,
      ),
    );
    final reduceMotion = MediaQuery.disableAnimationsOf(context);
    return Padding(
      key: const ValueKey('chat-environment-preparation'),
      padding: EdgeInsets.only(
        bottom: ChatMessageViewport.maybeOf(context)?.bottomOverlayInset ?? 0,
        left: 24,
        right: 24,
      ),
      child: Align(
        alignment: const Alignment(0, -0.18),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            RepaintBoundary(
              child: reduceMotion
                  ? label
                  : ShimmeringStatusText(baseColor: color, child: label),
            ),
            const SizedBox(height: 12),
            Semantics(
              liveRegion: true,
              child: Text(
                message,
                textAlign: TextAlign.center,
                style: TextStyle(color: color, fontSize: 13, height: 1.5),
              ),
            ),
            InstallationNotificationPermissionButton(
              preparation: preparation,
              textColor: color,
            ),
            if (failed || cannotRead) ...[
              const SizedBox(height: 12),
              TextButton(
                key: const ValueKey('chat-environment-retry'),
                onPressed: preparation.retrying
                    ? null
                    : () => unawaited(preparation.retry()),
                child: Text(english ? 'Retry' : '重试安装'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
