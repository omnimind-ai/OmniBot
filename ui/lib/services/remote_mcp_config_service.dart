import 'package:flutter/services.dart';
import 'package:ui/models/remote_mcp_server.dart';

class RemoteMcpConfigService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/RemoteMcpConfig',
  );

  static Future<List<RemoteMcpServer>> listServers() async {
    final result = await _channel.invokeMethod<List<dynamic>>('listServers');
    return (result ?? const [])
        .map(
          (item) => RemoteMcpServer.fromMap(Map<dynamic, dynamic>.from(item)),
        )
        .toList();
  }

  static Future<RemoteMcpServer?> upsertServer(RemoteMcpServer server) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'upsertServer',
      {...server.toMap(), 'destinationConfirmed': true},
    );
    if (result == null) return null;
    return RemoteMcpServer.fromMap(result);
  }

  static Future<void> deleteServer(String id) async {
    await _channel.invokeMethod('deleteServer', {'id': id});
  }

  static Future<RemoteMcpServer?> setServerEnabled(
    RemoteMcpServer server,
    bool enabled,
  ) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'setServerEnabled',
      {
        'id': server.id,
        'enabled': enabled,
        'expectedEndpointUrl': server.endpointUrl,
        'expectedGeneration': server.generation,
        'destinationConfirmed': enabled,
      },
    );
    if (result == null) return null;
    return RemoteMcpServer.fromMap(result);
  }

  static Future<RemoteMcpServer?> refreshServerTools(
    RemoteMcpServer server,
  ) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'refreshServerTools',
      {
        'id': server.id,
        'expectedEndpointUrl': server.endpointUrl,
        'expectedGeneration': server.generation,
        'destinationConfirmed': true,
      },
    );
    final serverMap = result?['server'] as Map<dynamic, dynamic>?;
    if (serverMap == null) return null;
    return RemoteMcpServer.fromMap(serverMap);
  }
}
