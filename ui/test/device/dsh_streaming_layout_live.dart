// Run the production reducer/chat widgets on a physical Flutter module host:
// flutter run -d DEVICE -t test/device/dsh_streaming_layout_live.dart
// Synthetic DSH ACP replay, not acceptance of a live provider connection.
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';

import '../features/home/pages/chat/widgets/dsh_streaming_layout_test.dart'
    as suite;

Future<void> main() async {
  final binding = LiveTestWidgetsFlutterBinding();
  // Android attaches the surface after invoking the Dart entrypoint. Starting
  // layout assertions before viewport metrics arrive creates a zero-size root.
  final deadline = DateTime.now().add(const Duration(seconds: 10));
  while (binding.platformDispatcher.implicitView!.physicalSize.isEmpty) {
    if (DateTime.now().isAfter(deadline)) {
      throw StateError('Physical test surface did not become ready');
    }
    await Future<void>.delayed(const Duration(milliseconds: 50));
  }
  // This module host has no OmniBot native voice configuration. The replay
  // exercises text layout only; explicitly disable this peripheral capability.
  binding.defaultBinaryMessenger.setMockMethodCallHandler(
    const MethodChannel('cn.com.omnimind.bot/AssistCoreEvent'),
    (call) async => switch (call.method) {
      'getSceneModelBindings' => <dynamic>[],
      'getSceneVoiceConfig' => <String, dynamic>{},
      _ => throw MissingPluginException(
        'Unexpected fixture method: ${call.method}',
      ),
    },
  );
  binding.defaultBinaryMessenger.setMockMethodCallHandler(
    const MethodChannel('cn.com.omnimind.bot/VoicePlaybackEvents'),
    (call) async {
      if (call.method == 'listen' || call.method == 'cancel') return null;
      throw MissingPluginException(
        'Unexpected voice subscription: ${call.method}',
      );
    },
  );
  suite.main();
}
