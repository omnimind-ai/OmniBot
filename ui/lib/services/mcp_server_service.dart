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
  static const MethodChannel _channel = MethodChannel('cn.com.omnimind.bot/McpServer');

  static Future<McpServerInfo?> getState() async {
    try {
      final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>('state');
      return McpServerInfo.fromMap(raw);
    } on PlatformException catch (_) {
      return null;
    }
  }

  static Future<McpServerInfo?> setEnabled(bool enable, {int? port}) async {
    try {
      final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'setEnabled',
        {
          'enable': enable,
          if (port != null) 'port': port,
        },
      );
      return McpServerInfo.fromMap(raw);
    } on PlatformException catch (_) {
      rethrow;
    }
  }

  static Future<McpServerInfo?> refreshToken() async {
    try {
      final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>('refreshToken');
      return McpServerInfo.fromMap(raw);
    } on PlatformException catch (_) {
      rethrow;
    }
  }
}
