import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_speech_input_button.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const inputChannel = MethodChannel('cn.com.omnimind.bot/SpeechInput');
  const playbackChannel = MethodChannel('cn.com.omnimind.bot/VoicePlayback');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<MethodCall> calls;
  late Completer<String?>? transcript;
  late List<({String text, bool send, int target})> results;
  late String permission;

  setUp(() {
    calls = [];
    results = [];
    transcript = null;
    permission = 'granted';
    messenger.setMockMethodCallHandler(playbackChannel, (_) async => true);
    messenger.setMockMethodCallHandler(inputChannel, (call) async {
      calls.add(call);
      return switch (call.method) {
        'permissionStatus' => permission,
        'requestPermission' => 'request',
        'start' => (transcript = Completer<String?>()).future,
        'finish' => true,
        'cancel' => null,
        _ => throw MissingPluginException(),
      };
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(inputChannel, null);
    messenger.setMockMethodCallHandler(playbackChannel, null);
  });

  Future<void> showInput(WidgetTester tester, {int target = 1}) {
    return tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: ChatSpeechInputButton(
              target: target,
              onTranscript: (text, {required send, required target}) {
                results.add((text: text, send: send, target: target));
              },
            ),
          ),
        ),
      ),
    );
  }

  final button = find.byType(ChatSpeechInputButton);

  testWidgets('tap records then returns text for the draft', (tester) async {
    await showInput(tester);
    await tester.tap(button);
    await tester.pump();
    expect(calls.map((c) => c.method), ['permissionStatus', 'start']);

    await tester.tap(button);
    await tester.pump();
    expect(calls.last.method, 'finish');
    expect(calls.last.arguments, calls[1].arguments);
    transcript!.complete('你好');
    await tester.pump();
    expect(results, [(text: '你好', send: false, target: 1)]);
  });

  testWidgets('hold sends once only after release and transcription', (
    tester,
  ) async {
    await showInput(tester);
    final gesture = await tester.startGesture(tester.getCenter(button));
    await tester.pump(const Duration(milliseconds: 600));
    expect(calls.last.method, 'start');
    expect(results, isEmpty);

    await gesture.up();
    await tester.pump();
    expect(calls.last.method, 'finish');
    transcript!.complete('发送这一句');
    await tester.pump();
    expect(results, [(text: '发送这一句', send: true, target: 1)]);
  });

  testWidgets('holding outside the button cancels and discards late text', (
    tester,
  ) async {
    await showInput(tester);
    final gesture = await tester.startGesture(tester.getCenter(button));
    await tester.pump(const Duration(milliseconds: 600));
    await gesture.moveBy(const Offset(0, -90));
    await tester.pump();
    await gesture.up();
    await tester.pump();
    expect(calls.map((c) => c.method), ['permissionStatus', 'start', 'cancel']);

    transcript!.complete('已取消');
    await tester.pump();
    expect(results, isEmpty);
  });

  testWidgets('changing the chat target cancels pending transcription', (
    tester,
  ) async {
    await showInput(tester);
    await tester.tap(button);
    await tester.pump();
    await tester.tap(button);
    await tester.pump();
    await showInput(tester, target: 2);
    expect(calls.last.method, 'cancel');

    transcript!.complete('旧会话');
    await tester.pump();
    expect(results, isEmpty);
  });

  testWidgets('a hold with missing permission only asks for permission', (
    tester,
  ) async {
    permission = 'request';
    await showInput(tester);
    final gesture = await tester.startGesture(tester.getCenter(button));
    await tester.pump(const Duration(milliseconds: 600));
    await gesture.up();
    await tester.pump();
    expect(calls.map((c) => c.method), [
      'permissionStatus',
      'requestPermission',
    ]);
    expect(results, isEmpty);
  });
}
