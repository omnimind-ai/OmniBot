import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/codex_bridge_qr_scanner_page.dart';

void main() {
  test(
    'selected session is carried separately from transport and credentials',
    () {
      final result = CodexBridgeQrScanResult.tryParse(
        jsonEncode({
          'type': 'omnibot.codex_bridge',
          'bridgeUrl': 'wss://bridge.example.com/codex',
          'token': 'fixture',
          'cwd': '/project',
          'sessionId': 'selected-session',
        }),
      );
      expect(result?.sessionId, 'selected-session');
      final plain = CodexBridgeQrScanResult.tryParse(
        'wss://bridge.example.com/codex?sessionId=selected-session&token=fixture&cwd=%2Fproject',
      );
      expect(plain?.sessionId, 'selected-session');
      expect(plain?.bridgeUrl, 'wss://bridge.example.com/codex');
    },
  );
  test('parses omnibot codex bridge QR payload', () {
    final payload = Uri(
      scheme: 'omnibot',
      host: 'codex-bridge',
      queryParameters: <String, String>{
        'bridgeUrl': 'ws://192.168.1.20:17321/codex',
        'cwd': '/Users/me/code/project',
        'token': 'secret',
      },
    ).toString();

    final result = CodexBridgeQrScanResult.tryParse(payload);

    expect(result?.bridgeUrl, 'ws://192.168.1.20:17321/codex');
    expect(result?.cwd, '/Users/me/code/project');
    expect(result?.token, 'secret');
  });

  test('parses json payload and rejects unrelated QR codes', () {
    final result = CodexBridgeQrScanResult.tryParse(
      jsonEncode(<String, String>{
        'type': 'omnibot.codex_bridge',
        'bridgeUrl': 'ws://10.0.0.5:17321/codex',
        'remoteCwd': '/repo',
      }),
    );

    expect(result?.bridgeUrl, 'ws://10.0.0.5:17321/codex');
    expect(result?.cwd, '/repo');
    expect(CodexBridgeQrScanResult.tryParse('https://example.com'), isNull);
    expect(CodexBridgeQrScanResult.tryParse('not a qr payload'), isNull);
  });

  test('extracts token and cwd from plain bridge url query', () {
    final result = CodexBridgeQrScanResult.tryParse(
      'ws://192.168.1.21:17321/codex?token=t&cwd=%2Fworkspace&keep=1',
    );

    expect(result?.bridgeUrl, 'ws://192.168.1.21:17321/codex?keep=1');
    expect(result?.token, 't');
    expect(result?.cwd, '/workspace');
  });
}
