import 'dart:async';
import 'dart:convert';
import 'dart:io' as io;

import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/utils/ui.dart';

class OpenClawConnectionChecker {
  const OpenClawConnectionChecker._();

  /// 检查 OpenClaw Gateway 连接与协议兼容性，并通过全局 Toast 给出结果
  static Future<void> checkAndToast(String baseUrl) async {
    final normalizedBaseUrl = baseUrl.trim();
    if (normalizedBaseUrl.isEmpty) {
      AppToast.warning(LegacyTextLocalizer.localize('OpenClaw Base URL 为空，请先配置'));
      return;
    }

    try {
      final wsUrl = buildWsUrl(normalizedBaseUrl);
      final ws = await io.WebSocket.connect(
        wsUrl,
      ).timeout(const Duration(seconds: 8));
      final challengeReceived = await _waitForConnectChallenge(ws);
      await ws.close(io.WebSocketStatus.normalClosure, 'config check');
      if (challengeReceived) {
        AppToast.success(LegacyTextLocalizer.localize('OpenClaw 连接成功，Gateway 协议正常'));
      } else {
        AppToast.warning(LegacyTextLocalizer.localize('OpenClaw 已连接但未收到 challenge，请检查 Gateway 版本'));
      }
    } on TimeoutException {
      AppToast.error(
        LegacyTextLocalizer.localize('OpenClaw 连接超时，请检查服务地址和网络'),
        duration: const Duration(seconds: 3),
      );
    } catch (e) {
      String errMsg = e.toString();
      if (errMsg.length > 100) errMsg = '${errMsg.substring(0, 100)}...';
      AppToast.error(
        LegacyTextLocalizer.localize('OpenClaw 连接失败：$errMsg'),
        duration: const Duration(seconds: 3),
      );
    }
  }

  /// 将 baseUrl 转换为 WebSocket URL（与 Kotlin 端保持一致）
  static String buildWsUrl(String baseUrl) {
    var url = baseUrl.trim();
    if (url.endsWith('/v1/chat/completions')) {
      url = url.substring(0, url.length - '/v1/chat/completions'.length);
    }
    if (url.endsWith('/v1')) {
      url = url.substring(0, url.length - '/v1'.length);
    }
    url = url.replaceAll(RegExp(r'/+$'), '');
    if (url.startsWith('ws://') || url.startsWith('wss://')) return url;
    if (url.startsWith('https://')) {
      return 'wss://${url.substring('https://'.length)}'.replaceAll(
        RegExp(r'/+$'),
        '',
      );
    }
    if (url.startsWith('http://')) {
      return 'ws://${url.substring('http://'.length)}'.replaceAll(
        RegExp(r'/+$'),
        '',
      );
    }
    return 'ws://$url'.replaceAll(RegExp(r'/+$'), '');
  }

  static Future<bool> _waitForConnectChallenge(io.WebSocket ws) async {
    final completer = Completer<bool>();
    late final StreamSubscription<dynamic> subscription;

    subscription = ws.listen(
      (data) {
        try {
          if (data is! String) return;
          final frame = jsonDecode(data);
          if (frame is Map &&
              frame['type'] == 'event' &&
              frame['event'] == 'connect.challenge' &&
              !completer.isCompleted) {
            completer.complete(true);
          }
        } catch (_) {
          // ignore decode errors, only challenge event matters here
        }
      },
      onError: (_) {
        if (!completer.isCompleted) {
          completer.complete(false);
        }
      },
      onDone: () {
        if (!completer.isCompleted) {
          completer.complete(false);
        }
      },
      cancelOnError: false,
    );

    try {
      return await completer.future.timeout(
        const Duration(seconds: 5),
        onTimeout: () => false,
      );
    } finally {
      await subscription.cancel();
    }
  }
}
