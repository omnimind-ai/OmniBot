import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/remote_mcp_server.dart';

void main() {
  test('preserves transport and headers through an edit round trip', () {
    final server = RemoteMcpServer.fromMap({
      'id': 'server-1',
      'name': 'MCP',
      'endpointUrl': 'https://example.com/mcp',
      'bearerToken': '',
      'headers': {'X-Tenant': 'demo'},
      'transport': 'http',
      'enabled': true,
      'lastHealth': 'healthy',
      'toolCount': 2,
    });

    final edited = server.copyWith(name: 'Edited').toMap();

    expect(edited['headers'], {'X-Tenant': 'demo'});
    expect(edited['transport'], 'http');
  });
}
