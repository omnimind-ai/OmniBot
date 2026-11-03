import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/flclash/popup.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/omni_menu.dart';

enum _Owner { route, overlay }

class _Harness {
  final label = ValueNotifier('Original');
  late BuildContext sourceContext;
  late BuildContext panelContext;
  OverlayGlassPopupHandle<int>? handle;
  Future<void>? selection;

  Widget app({
    required bool glass,
    required LiquidGlassQuality quality,
    required bool dark,
  }) => MaterialApp(
    theme: dark ? ThemeData.dark() : ThemeData.light(),
    builder: (context, child) => LiquidGlassScope(
      enabled: glass,
      quality: quality,
      child: LiquidGlassHost(enabled: glass, child: child!),
    ),
    home: Scaffold(
      body: Center(
        child: FlClashPopupSource(
          builder: (context) {
            sourceContext = context;
            return ValueListenableBuilder<String>(
              valueListenable: label,
              builder: (_, value, _) => SizedBox(
                width: 140,
                height: 44,
                child: TextButton(onPressed: () {}, child: Text(value)),
              ),
            );
          },
        ),
      ),
    ),
  );

  void open(_Owner owner) {
    final anchor = glassPopupAnchorFromContext(sourceContext)!;
    if (owner == _Owner.overlay) {
      final popup = handle = showOverlayGlassPopup<int>(
        context: sourceContext,
        sourceContext: sourceContext,
        anchor: anchor,
        builder: (handle) => OmniMenuSurface(
          width: 220,
          child: TextButton(
            key: const ValueKey('selection'),
            onPressed: () => unawaited(handle.dismiss(7)),
            child: const Text('Choose'),
          ),
        ),
      );
      selection = () async {
        final value = await popup.future;
        await popup.closed;
        if (value != null) label.value = 'Selected $value';
      }();
    } else {
      selection =
          showOmniMenu<int>(
            context: sourceContext,
            sourceContext: sourceContext,
            anchor: anchor,
            items: [
              OmniPopupMenuItem<int>(
                value: 7,
                child: Builder(
                  builder: (context) {
                    panelContext = context;
                    return const Text('Choose', key: ValueKey('selection'));
                  },
                ),
              ),
            ],
          ).then((value) {
            if (value != null) label.value = 'Selected $value';
          });
    }
  }

  void dismiss(_Owner owner) {
    if (owner == _Owner.overlay) {
      unawaited(handle!.dismiss());
    } else {
      Navigator.of(panelContext).pop();
    }
  }
}

double _sourceOpacity(WidgetTester tester) => tester
    .widget<Opacity>(
      find
          .descendant(
            of: find.byType(FlClashPopupSource),
            matching: find.byType(Opacity),
          )
          .first,
    )
    .opacity;

void main() {
  for (final owner in _Owner.values) {
    for (final interrupted in [false, true]) {
      testWidgets(
        '$owner restores source atomically; interrupted=$interrupted',
        (tester) async {
          final harness = _Harness();
          addTearDown(harness.label.dispose);
          await tester.pumpWidget(
            harness.app(
              glass: true,
              quality: LiquidGlassQuality.standard,
              dark: false,
            ),
          );
          harness.open(owner);
          if (interrupted) {
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 70));
          } else {
            await tester.pumpAndSettle();
          }
          harness.dismiss(owner);
          await tester.pump();
          var removed = false;
          for (var frame = 0; frame < 36; frame++) {
            await tester.pump(const Duration(milliseconds: 16));
            final transitions = tester.widgetList<FlClashPopupTransition>(
              find.byType(FlClashPopupTransition),
            );
            final retired =
                transitions.isEmpty || transitions.single.animation.isDismissed;
            expect(
              _sourceOpacity(tester),
              retired ? 1 : 0,
              reason:
                  'The source and animation exchange visibility in one frame',
            );
            if (retired) {
              expect(
                LiquidGlassHost.debugHeldCaptures,
                0,
                reason: 'The retired animation cannot retain a second glass surface',
              );
            }
            if (transitions.isEmpty) {
              removed = true;
              expect(
                _sourceOpacity(tester),
                1,
                reason:
                    'Removing the overlay must not paint an empty source frame',
              );
              break;
            }
          }
          expect(removed, isTrue);
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull);
        },
      );
    }
  }

  testWidgets('overlay dismissed before its first frame never mounts later', (
    tester,
  ) async {
    final harness = _Harness();
    addTearDown(harness.label.dispose);
    await tester.pumpWidget(
      harness.app(
        glass: true,
        quality: LiquidGlassQuality.standard,
        dark: false,
      ),
    );
    harness.open(_Owner.overlay);
    await harness.handle!.dismiss();
    await tester.pumpAndSettle();
    expect(find.byType(GlassPopupOverlayContent), findsNothing);
    expect(_sourceOpacity(tester), 1);
    expect(tester.takeException(), isNull);
    harness.open(_Owner.overlay);
    await tester.pumpAndSettle();
    expect(find.byType(GlassPopupOverlayContent), findsOneWidget);
    harness.dismiss(_Owner.overlay);
    await tester.pumpAndSettle();
    expect(_sourceOpacity(tester), 1);
    expect(tester.takeException(), isNull);
  });

  for (final owner in _Owner.values) {
    for (final dark in [false, true]) {
      for (final quality in <LiquidGlassQuality?>[
        null,
        ...LiquidGlassQuality.values,
      ]) {
        testWidgets(
          '$owner $quality dark=$dark returns before changing value',
          (tester) async {
            final harness = _Harness();
            addTearDown(harness.label.dispose);
            await tester.pumpWidget(
              harness.app(
                glass: quality != null,
                quality: quality ?? LiquidGlassQuality.standard,
                dark: dark,
              ),
            );
            harness.open(owner);
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 70));
            expect(_sourceOpacity(tester), 0);
            harness.dismiss(owner);
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 200));
            expect(_sourceOpacity(tester), 0);
            expect(harness.label.value, 'Original');
            await tester.pumpAndSettle();
            expect(_sourceOpacity(tester), 1);
            expect(harness.label.value, 'Original');
            harness.open(owner);
            await tester.pumpAndSettle();
            await tester.tap(find.byKey(const ValueKey('selection')));
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 300));
            expect(_sourceOpacity(tester), 0);
            expect(harness.label.value, 'Original');
            await tester.pumpAndSettle();
            await harness.selection;
            expect(_sourceOpacity(tester), 1);
            expect(harness.label.value, 'Selected 7');
            expect(LiquidGlassHost.debugHeldCaptures, 0);
            expect(tester.takeException(), isNull);
          },
        );
      }
    }
  }
}
