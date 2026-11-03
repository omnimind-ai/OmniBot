import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_spotlight_tour.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/theme/app_theme.dart';

class _TourHarness extends StatefulWidget {
  const _TourHarness();

  @override
  State<_TourHarness> createState() => _TourHarnessState();
}

class _TourHarnessState extends State<_TourHarness> {
  int step = 0;
  int finishCount = 0;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      locale: const Locale('zh'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Stack(
          fit: StackFit.expand,
          children: [
            const ColoredBox(
              key: ValueKey('actual-chat-page-under-tour'),
              color: Colors.white,
            ),
            ChatSpotlightTour(
              step: step,
              onNext: () => setState(() => step += 1),
              onFinish: () => setState(() => finishCount += 1),
            ),
          ],
        ),
      ),
    );
  }
}

void main() {
  testWidgets('model explanation stays above its target and keyboard', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 700);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final anchor = GlobalKey();
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(
            viewInsets: const EdgeInsets.only(bottom: 280),
            textScaler: const TextScaler.linear(1.6),
          ),
          child: child!,
        ),
        home: Stack(
          fit: StackFit.expand,
          children: [
            Positioned(
              left: 220,
              top: 350,
              child: SizedBox(key: anchor, width: 112, height: 36),
            ),
            ChatSpotlightTour(
              step: ChatSpotlightTour.modelStep,
              anchorKey: anchor,
              onNext: () {},
              onFinish: () {},
            ),
          ],
        ),
      ),
    );
    await tester.pumpAndSettle();
    final card = tester.getRect(
      find.byKey(const ValueKey('chat-spotlight-viewport-3')),
    );
    expect(card.top, greaterThanOrEqualTo(18));
    expect(card.bottom, lessThanOrEqualTo(350 - 4 - 18));
    expect(card.left, greaterThanOrEqualTo(18));
    expect(card.right, lessThanOrEqualTo(390 - 18));
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'model tour ignores a hidden anchor and measures it when visible',
    (tester) async {
      final anchor = GlobalKey();
      final size = ValueNotifier<Size>(Size.zero);
      addTearDown(size.dispose);
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ValueListenableBuilder<Size>(
            valueListenable: size,
            builder: (context, value, _) => Stack(
              fit: StackFit.expand,
              children: [
                Positioned(
                  left: 100,
                  top: 300,
                  child: SizedBox(
                    key: anchor,
                    width: value.width,
                    height: value.height,
                  ),
                ),
                ChatSpotlightTour(
                  step: ChatSpotlightTour.modelStep,
                  anchorKey: anchor,
                  onNext: () {},
                  onFinish: () {},
                ),
              ],
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      Rect? spotlight() {
        final painter =
            tester
                    .widget<CustomPaint>(
                      find.byKey(const ValueKey('chat-spotlight-hole-3')),
                    )
                    .painter
                as dynamic;
        return painter.spotlight as Rect?;
      }

      expect(spotlight(), isNull);
      size.value = const Size(112, 36);
      await tester.pumpAndSettle();
      expect(spotlight(), const Rect.fromLTWH(96, 296, 120, 44));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('spotlight tour advances from blank areas without bottom bar', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 700);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(const _TourHarness());

    expect(
      find.byKey(const ValueKey('actual-chat-page-under-tour')),
      findsOneWidget,
    );
    expect(find.byKey(const ValueKey('chat-spotlight-tour')), findsOneWidget);
    expect(find.byKey(const ValueKey('chat-spotlight-card-0')), findsOneWidget);
    expect(find.text('返回会话列表'), findsOneWidget);

    expect(
      find.byKey(const ValueKey('chat-spotlight-navigation')),
      findsNothing,
    );
    expect(find.byKey(const ValueKey('chat-spotlight-back')), findsNothing);
    expect(find.byKey(const ValueKey('chat-spotlight-next')), findsNothing);

    await tester.tap(find.byKey(const ValueKey('chat-spotlight-card-0')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('chat-spotlight-card-0')), findsOneWidget);

    await tester.tapAt(const Offset(16, 350));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('chat-spotlight-card-1')), findsOneWidget);

    for (var step = 2; step < ChatSpotlightTour.stepCount; step += 1) {
      await tester.tapAt(const Offset(16, 350));
      await tester.pumpAndSettle();
      expect(
        find.byKey(ValueKey<String>('chat-spotlight-card-$step')),
        findsOneWidget,
      );
    }

    await tester.tapAt(const Offset(16, 350));
    await tester.pumpAndSettle();
    final harness = tester.state<_TourHarnessState>(find.byType(_TourHarness));
    expect(harness.finishCount, 1);
    expect(tester.takeException(), isNull);
  });
}
