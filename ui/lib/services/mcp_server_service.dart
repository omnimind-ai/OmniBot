import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class McpServerInfo {
  final bool enabled;
  final bool running;
  final String? host;
  final int port;
  final String token;

  const McpServerInfo({
    required this.enabled,
    required this.running,
    required this.host,
    required this.port,
    required this.token,
  });

  String get endpoint => host == null ? '' : 'http://$host:$port';

  bool get isLoopbackOnly =>
      running && (host == '127.0.0.1' || host == 'localhost' || host == '::1');

  static McpServerInfo? fromMap(Map<dynamic, dynamic>? raw) {
    if (raw == null) return null;
    return McpServerInfo(
      enabled: raw['enabled'] == true,
      running: raw['running'] == true,
      host: raw['host'] as String?,
      port: (raw['port'] as int?) ?? 0,
      token: (raw['token'] as String?) ?? '',
    );
  }
}

class McpServerService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/McpServer',
  );

  static Future<McpServerInfo?> getState() async {
    try {
      final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>('state');
      return McpServerInfo.fromMap(raw);
    } on PlatformException catch (e) {
      debugPrint('getState failed: ${e.message}');
      return null;
    }
  }

  static Future<McpServerInfo?> setEnabled(bool enable, {int? port}) async {
    try {
      final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'setEnabled',
        {'enable': enable, if (port != null) 'port': port},
      );
      return McpServerInfo.fromMap(raw);
    } on PlatformException catch (e) {
      debugPrint('setEnabled failed: ${e.message}');
      rethrow;
    }
  }

  static Future<McpServerInfo?> refreshToken() async {
    try {
      final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'refreshToken',
      );
      return McpServerInfo.fromMap(raw);
    } on PlatformException catch (e) {
      debugPrint('refreshToken failed: ${e.message}');
      rethrow;
    }
  }
}
