import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/na_history_sync_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('foreground subscription serializes pending pause and resume', () async {
    const channel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
    final calls = <bool>[];
    final gate = Completer<void>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'config/na/historySync') {
            calls.add((call.arguments as Map)['enabled'] as bool);
            if (calls.length == 1) await gate.future;
          }
          return <String, dynamic>{};
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    final service = NaHistorySyncService.instance;
    service.didChangeAppLifecycleState(AppLifecycleState.paused);
    service.didChangeAppLifecycleState(AppLifecycleState.resumed);
    await Future<void>.delayed(Duration.zero);
    expect(calls, [false]);
    gate.complete();
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    expect(calls, [false, true]);
    service.didChangeAppLifecycleState(AppLifecycleState.inactive);
    await Future<void>.delayed(Duration.zero);
    expect(calls, [false, true]);
  });
}
