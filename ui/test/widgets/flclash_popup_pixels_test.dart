import 'dart:async';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/flclash/popup.dart';

class _PopupPixels extends StatefulWidget {
  const _PopupPixels({
    super.key,
    required this.glass,
    required this.circle,
    required this.dark,
    this.transparentGlyph = false,
    this.quality = LiquidGlassQuality.standard,
  });

  final bool glass;
  final bool circle;
  final bool dark;
  final bool transparentGlyph;
  final LiquidGlassQuality quality;

  @override
  State<_PopupPixels> createState() => _PopupPixelsState();
}

class _PopupPixelsState extends State<_PopupPixels>
    with SingleTickerProviderStateMixin {
  final snapshot = GlobalKey();
  late BuildContext sourceContext;
  late final AnimationController controller =
      AnimationController(
        vsync: this,
        duration: FlClashPopupMotion.openingDuration,
        reverseDuration: FlClashPopupMotion.closingDuration,
      )..addStatusListener((status) {
        if (status == AnimationStatus.dismissed) lease?.reveal();
      });
  FlClashPopupSourceLease? lease;

  Color get fill =>
      widget.transparentGlyph ? Colors.transparent : const Color(0xAA4055E6);
  Size get bodySize => widget.circle ? const Size(40, 40) : const Size(168, 44);
  ShapeBorder get shape => widget.circle
      ? const CircleBorder()
      : const RoundedSuperellipseBorder(
          borderRadius: BorderRadius.all(Radius.circular(999)),
        );

  void open() {
    setState(() => lease = FlClashPopupSource.captureOf(sourceContext));
    unawaited(controller.forward());
  }

  void close() => unawaited(FlClashPopupMotion.close(controller));

  @override
  void dispose() {
    controller.dispose();
    lease?.release();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    theme: widget.dark ? ThemeData.dark() : ThemeData.light(),
    home: LiquidGlassScope(
      enabled: widget.glass,
      quality: widget.quality,
      child: RepaintBoundary(
        key: snapshot,
        child: LiquidGlassHost(
          enabled: widget.glass,
          child: Builder(
            builder: (context) => ColoredBox(
              color: widget.dark
                  ? const Color(0xFF171A24)
                  : const Color(0xFFE5EAF1),
              child: Stack(
                children: [
                  Positioned(
                    top: 64,
                    left: 64,
                    child: FlClashPopupSource(
                      color: widget.glass ? Colors.transparent : fill,
                      shape: shape,
                      glass: widget.glass && !widget.transparentGlyph,
                      captureInsets: widget.circle
                          ? const EdgeInsets.all(4)
                          : EdgeInsets.zero,
                      builder: (context) {
                        sourceContext = context;
                        Widget body = SizedBox.fromSize(
                          size: bodySize,
                          child: const Center(
                            child: SizedBox(
                              width: 14,
                              height: 14,
                              child: ColoredBox(color: Colors.black),
                            ),
                          ),
                        );
                        body = widget.transparentGlyph
                            ? body
                            : widget.glass
                            ? LiquidGlass(
                                style: LiquidGlassStyle.regular(
                                  Theme.of(context).colorScheme,
                                ),
                                child: body,
                              )
                            : DecoratedBox(
                                decoration: ShapeDecoration(
                                  color: fill,
                                  shape: shape,
                                ),
                                child: body,
                              );
                        return widget.circle
                            ? Padding(
                                padding: const EdgeInsets.all(4),
                                child: body,
                              )
                            : body;
                      },
                    ),
                  ),
                  if (lease case final source?)
                    Positioned.fill(
                      child: CustomSingleChildLayout(
                        delegate: _PanelLayout(),
                        child: FlClashPopupTransition(
                          anchor: source.rectIn(null)!,
                          animation: controller,
                          source: source,
                          child: const SizedBox(
                            width: 220,
                            height: 160,
                            child: ColoredBox(color: Colors.green),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

class _PanelLayout extends SingleChildLayoutDelegate {
  @override
  BoxConstraints getConstraintsForChild(BoxConstraints constraints) =>
      const BoxConstraints.tightFor(width: 220, height: 160);

  @override
  Offset getPositionForChild(Size size, Size childSize) => const Offset(64, 64);

  @override
  bool shouldRelayout(_PanelLayout oldDelegate) => false;
}

Future<Uint8List> _pixels(WidgetTester tester, GlobalKey key) async =>
    (await tester.runAsync(() async {
      final boundary =
          key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      final image = await boundary.toImage(pixelRatio: 1);
      try {
        return (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!
            .buffer
            .asUint8List();
      } finally {
        image.dispose();
      }
    }))!;

int _different(Uint8List before, Uint8List after, {int threshold = 12}) {
  var different = 0;
  for (var pixel = 0; pixel < before.length; pixel += 4) {
    if (List.generate(
      4,
      (channel) => (before[pixel + channel] - after[pixel + channel]).abs(),
    ).any((delta) => delta > threshold)) {
      different++;
    }
  }
  return different;
}

// Snapshot resampling may change antialiasing inside a one-pixel edge band.
// A real contour jump, opaque underlay or stray polygon changes flat interiors
// or moves an edge beyond that band, and fails this bidirectional comparison.
int _outsideEdgeBand(Uint8List original, Uint8List returned, int width) {
  final height = original.length ~/ (width * 4);
  var count = 0;
  for (var y = 1; y < height - 1; y++) {
    for (var x = 1; x < width - 1; x++) {
      final pixel = (y * width + x) * 4;
      var outside = false;
      for (var channel = 0; channel < 4; channel++) {
        var low = 255;
        var high = 0;
        for (var dy = -1; dy <= 1; dy++) {
          for (var dx = -1; dx <= 1; dx++) {
            final value = original[((y + dy) * width + x + dx) * 4 + channel];
            if (value < low) low = value;
            if (value > high) high = value;
          }
        }
        final value = returned[pixel + channel];
        outside |= value < low - 2 || value > high + 2;
      }
      if (outside) count++;
    }
  }
  return count;
}

void main() {
  for (final dark in [false, true]) {
    for (final circle in [false, true]) {
      for (final quality in <LiquidGlassQuality?>[
        null,
        ...LiquidGlassQuality.values,
      ]) {
        testWidgets(
          'source pixels return continuously: circle=$circle dark=$dark $quality',
          (tester) async {
            await tester.binding.setSurfaceSize(const Size(400, 300));
            addTearDown(() => tester.binding.setSurfaceSize(null));
            final key = GlobalKey<_PopupPixelsState>();
            await tester.pumpWidget(
              _PopupPixels(
                key: key,
                glass: quality != null,
                circle: circle,
                dark: dark,
                quality: quality ?? LiquidGlassQuality.standard,
              ),
            );
            await tester.pumpAndSettle();
            final state = key.currentState!;
            final before = await _pixels(tester, state.snapshot);
            state.open();
            await tester.pumpAndSettle();
            expect(
              state.lease!.image.value!.width,
              state.bodySize.width *
                  MediaQuery.devicePixelRatioOf(state.sourceContext),
            );
            state.close();
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 499));
            final arriving = await _pixels(tester, state.snapshot);
            expect(
              _outsideEdgeBand(before, arriving, 400),
              0,
              reason: 'The return cannot add a polygon, change the fill or enlarge the source',
            );
            expect(
              _outsideEdgeBand(arriving, before, 400),
              0,
              reason: 'The return cannot shrink or erase part of the source',
            );
            await tester.pump(const Duration(milliseconds: 2));
            final dismissed = await _pixels(tester, state.snapshot);
            expect(state.controller.isDismissed, isTrue);
            expect(
              _different(before, dismissed, threshold: 0),
              0,
              reason: 'The dismissed morph must not repaint opening t=0 over the original',
            );
            await tester.pumpWidget(const SizedBox.shrink());
            expect(LiquidGlassHost.debugHeldCaptures, 0);
            expect(tester.takeException(), isNull);
          },
        );
      }
    }
  }

  testWidgets(
    'an early reveal cannot be hidden again by pending snapshot capture',
    (tester) async {
      final key = GlobalKey<_PopupPixelsState>();
      await tester.pumpWidget(
        _PopupPixels(key: key, glass: false, circle: true, dark: false),
      );
      final state = key.currentState!;
      final lease = FlClashPopupSource.captureOf(state.sourceContext)!;
      lease.reveal();
      await tester.pump();
      await tester.pump();
      expect(lease.image.value, isNull);
      final opacity = tester.widget<Opacity>(
        find
            .descendant(
              of: find.byType(FlClashPopupSource),
              matching: find.byType(Opacity),
            )
            .first,
      );
      expect(opacity.opacity, 1);
      lease.release();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );

  for (final glass in [false, true]) {
    testWidgets(
      'transparent glyph returns without a filled source pane: glass=$glass',
      (tester) async {
        await tester.binding.setSurfaceSize(const Size(400, 300));
        addTearDown(() => tester.binding.setSurfaceSize(null));
        final key = GlobalKey<_PopupPixelsState>();
        await tester.pumpWidget(
          _PopupPixels(
            key: key,
            glass: glass,
            circle: true,
            dark: false,
            transparentGlyph: true,
          ),
        );
        await tester.pumpAndSettle();
        final state = key.currentState!;
        final before = await _pixels(tester, state.snapshot);
        state.open();
        await tester.pumpAndSettle();
        state.close();
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 499));
        final arriving = await _pixels(tester, state.snapshot);
        expect(_outsideEdgeBand(before, arriving, 400), 0);
        expect(_outsideEdgeBand(arriving, before, 400), 0);
        await tester.pumpAndSettle();
        expect(
          _different(
            before,
            await _pixels(tester, state.snapshot),
            threshold: 0,
          ),
          0,
        );
        await tester.pumpWidget(const SizedBox.shrink());
        expect(LiquidGlassHost.debugHeldCaptures, 0);
        expect(tester.takeException(), isNull);
      },
    );
  }
}
