import 'package:flutter/material.dart';
import 'package:ui/services/openclaw_credential_service.dart';
import 'package:ui/utils/ui.dart';

/// Performs a secret-free native authorization check.
///
/// The former Dart WebSocket probe could race a native reset and was not registered with the
/// native session coordinator. The real connection test is now the normal native-gated send path,
/// which is cancelled by reset/disable and revalidates immediately before each frame is sent.
class OpenClawConnectionChecker {
  const OpenClawConnectionChecker._();

  static Future<void> checkAndToast(
    BuildContext context,
    String baseUrl,
  ) async {
    try {
      final configuration =
          await OpenClawCredentialService.loadConfiguration();
      final authorized = configuration.enabled &&
          configuration.baseUrl == baseUrl.trim() &&
          await OpenClawCredentialService.isAuthorized(configuration);
      if (!context.mounted) return;
      if (authorized) {
        AppToast.success(
          Localizations.localeOf(context).languageCode == 'en'
              ? 'OpenClaw is authorized. Send a message to test the Gateway connection.'
              : 'OpenClaw 已获本机授权，请发送一条消息测试 Gateway 连接。',
        );
      } else {
        AppToast.warning(
          Localizations.localeOf(context).languageCode == 'en'
              ? 'OpenClaw is disabled or stale. Confirm the destination again.'
              : 'OpenClaw 已停用或配置过期，请重新确认接收方。',
        );
      }
    } catch (_) {
      AppToast.error(
        Localizations.localeOf(context).languageCode == 'en'
            ? 'OpenClaw authorization could not be verified.'
            : '无法验证 OpenClaw 本机授权状态。',
      );
    }
  }
}
