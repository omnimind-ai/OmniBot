import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_environment_preparation.dart';
import 'package:ui/services/embedded_terminal_preparation_service.dart';
import 'package:ui/services/special_permission.dart';

void main() {
  testWidgets(
    'waiting status uses the shared continuously animated OmniBot label',
    (tester) async {
      final service = EmbeddedTerminalPreparationService(
        loadSnapshot: () async =>
            EmbeddedTerminalInitSnapshot.fromMap({'running': true}),
      );
      addTearDown(service.dispose);
      await service.refresh();
      await tester.pumpWidget(_app(service));
      expect(find.text('OmniBot'), findsOneWidget);
      expect(find.byType(ShaderMask), findsOneWidget);
      expect(
        find.text('Installing the system and basic tools…'),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('chat-environment-retry')),
        findsNothing,
      );
      await tester.pump(const Duration(seconds: 4));
      expect(find.byType(ShaderMask), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('failed setup has a retry action and respects reduced motion', (
    tester,
  ) async {
    var retries = 0;
    final service = EmbeddedTerminalPreparationService(
      loadSnapshot: () async => EmbeddedTerminalInitSnapshot.fromMap({
        'completed': true,
        'success': false,
      }),
      prepare: () async {
        retries++;
        return {'success': false};
      },
    );
    addTearDown(service.dispose);
    await service.refresh();
    await tester.pumpWidget(_app(service, reduceMotion: true));
    await tester.pumpAndSettle();
    expect(find.byType(ShaderMask), findsNothing);
    expect(
      find.text('System installation failed. Tap Retry to continue.'),
      findsOneWidget,
    );
    await tester.tap(find.byKey(const ValueKey('chat-environment-retry')));
    await tester.pumpAndSettle();
    expect(retries, 1);
    expect(tester.takeException(), isNull);
  });
}

Widget _app(
  EmbeddedTerminalPreparationService service, {
  bool reduceMotion = false,
}) => MaterialApp(
  home: MediaQuery(
    data: MediaQueryData(disableAnimations: reduceMotion),
    child: Scaffold(
      body: AnimatedBuilder(
        animation: service,
        builder: (_, _) => ChatEnvironmentPreparation(preparation: service),
      ),
    ),
  ),
);
