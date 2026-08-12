import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/remote_mcp_server.dart';

void main() {
  test('native credential is represented only as status', () {
    final server = RemoteMcpServer.fromMap(<String, dynamic>{
      'id': 'server',
      'name': 'Remote',
      'endpointUrl': 'https://mcp.example.com/sse',
      'bearerToken': 'legacy-plaintext',
      'hasBearerToken': true,
      'enabled': true,
    });

    expect(server.bearerToken, isEmpty);
    expect(server.hasBearerToken, isTrue);
  });

  test('clear credential requires an explicit write intent', () {
    const server = RemoteMcpServer(
      id: 'server',
      name: 'Remote',
      endpointUrl: 'https://mcp.example.com/sse',
      bearerToken: '',
      hasBearerToken: true,
      clearBearerToken: true,
      enabled: true,
      lastHealth: 'unknown',
      toolCount: 0,
    );

    expect(server.toMap()['bearerToken'], isEmpty);
    expect(server.toMap()['clearBearerToken'], isTrue);
  });
}
