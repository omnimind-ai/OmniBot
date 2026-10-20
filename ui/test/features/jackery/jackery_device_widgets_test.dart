import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/jackery/jackery_device_widgets.dart';
import 'package:ui/services/jackery_ces_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() {
    JackeryCesService.instance.status = const JackeryDeviceStatus();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          JackeryCesService.channel,
          (call) async =>
              call.method == 'getConfig' ? {} : {'connected': false},
        );
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(JackeryCesService.channel, null);
  });

  testWidgets('invalid staff configuration leaves an explicit empty state', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(JackeryCesService.channel, (call) async {
          if (call.method == 'getConfig') {
            throw PlatformException(
              code: 'CES_CONFIG_INVALID',
              message: 'Device configuration is unavailable.',
            );
          }
          return {'connected': false};
        });
    await tester.pumpWidget(
      const MaterialApp(home: Scaffold(body: JackeryDeviceCard())),
    );
    await tester.pump();
    expect(find.text('Device image unavailable'), findsOneWidget);
    expect(find.text('Not connected'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'home card guides a disconnected user and opens real device details',
    (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: Padding(
              padding: EdgeInsets.all(24),
              child: JackeryDeviceCard(),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(find.text('Not connected'), findsOneWidget);
      expect(find.text('Battery unavailable'), findsOneWidget);
      expect(find.text('Device image unavailable'), findsOneWidget);
      expect(find.byType(LinearProgressIndicator), findsNothing);
      await tester.tap(find.text('View device'));
      await tester.pumpAndSettle();
      for (final label in ['Back', 'Battery', 'Power', 'Connection']) {
        expect(find.text(label), findsOneWidget);
      }
      await tester.scrollUntilVisible(find.text('AI model'), 120);
      expect(find.text('AI model'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('Back'));
      await tester.pumpAndSettle();
      expect(find.text('View device'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'chat artwork remains decorative and has no device information or action',
    (tester) async {
      var taps = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Stack(
              children: [
                Positioned.fill(
                  child: GestureDetector(
                    onTap: () => taps++,
                    child: const ColoredBox(color: Colors.white),
                  ),
                ),
                const Positioned.fill(child: JackeryChatBackdrop()),
              ],
            ),
          ),
        ),
      );
      await tester.pump();
      expect(find.text('Local AI. Powered anywhere.'), findsOneWidget);
      expect(find.text('View device'), findsNothing);
      expect(find.text('Battery unavailable'), findsNothing);
      await tester.tapAt(const Offset(100, 100));
      expect(taps, 1);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}
