import 'dart:async';
import 'dart:ui' as ui;
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';

Widget _scene({
  bool enabled = true,
  LiquidGlassQuality quality = LiquidGlassQuality.ultra,
  bool pressed = false,
  bool reducedMotion = false,
  bool twoPanes = false,
  Key? sceneKey,
  VoidCallback? onTap,
}) => MaterialApp(
  debugShowCheckedModeBanner: false,
  home: MediaQuery(
    data: MediaQueryData(disableAnimations: reducedMotion),
    child: LiquidGlassScope(
      enabled: enabled,
      quality: quality,
      child: LiquidGlassHost(
        enabled: enabled,
        child: RepaintBoundary(
          key: sceneKey,
          child: Stack(
            children: [
              const Positioned.fill(
                child: ColoredBox(color: Color(0xFFE9E2D0)),
              ),
              const Positioned(
                left: 30,
                top: 70,
                child: Text('Backdrop details under glass'),
              ),
              Positioned(
                left: 20,
                top: 50,
                child: RepaintBoundary(
                  child: LiquidGlassGroup(
                    child: Row(
                      children: [
                        for (var i = 0; i < (twoPanes ? 2 : 1); i++)
                          LiquidGlass(
                            style: LiquidGlassStyle.pill(
                              ColorScheme.fromSeed(seedColor: Colors.blue),
                            ),
                            pressed: pressed,
                            child: GestureDetector(
                              behavior: HitTestBehavior.opaque,
                              onTap: onTap,
                              child: const SizedBox(width: 120, height: 48),
                            ),
                          ),
                      ],
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
);

void main() {
  tearDown(() {
    LiquidGlassHost.debugLastCapture = null;
    LiquidGlassHost.debugCarryBandsOverride = null;
    LiquidGlass.debugResetShaderLoading();
  });

  testWidgets('bundled fragment program compiles and warmup is reusable', (
    tester,
  ) async {
    LiquidGlass.debugResetShaderLoading(waitForFirstFrame: () async {});
    await tester.runAsync(() async {
      final program = await ui.FragmentProgram.fromAsset(
        'shaders/liquid_glass.frag',
      );
      final shader = program.fragmentShader();
      shader.dispose();
      await Future.wait([LiquidGlass.warmUp(), LiquidGlass.warmUp()]);
    });
    expect(
      LiquidGlass.debugShaderReady,
      ui.ImageFilter.isShaderFilterSupported,
      reason: 'Non-Impeller tests use the upstream frost fallback',
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('first frame is interactive before the shared shader loads', (
    tester,
  ) async {
    final program = (await tester.runAsync(
      () => ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag'),
    ))!;
    final firstFrame = Completer<void>();
    final loaded = Completer<ui.FragmentProgram>();
    var loads = 0;
    var taps = 0;
    LiquidGlass.debugResetShaderLoading(
      waitForFirstFrame: () => firstFrame.future,
      loadProgram: () {
        loads++;
        return loaded.future;
      },
    );

    await tester.pumpWidget(_scene(twoPanes: true, onTap: () => taps++));
    final warmUp = LiquidGlass.warmUp();
    expect(loads, 0, reason: 'Painting must not load before rasterization');
    expect(LiquidGlassHost.debugHeldCaptures, 2);
    await tester.tap(find.byType(LiquidGlass).first);
    expect(taps, 1);
    expect(LiquidGlass.debugShaderReady, isFalse);

    firstFrame.complete();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 1));
    expect(loads, 1, reason: 'Panes and warmup share one deferred load');
    loaded.complete(program);
    await tester.idle();
    await warmUp;
    expect(LiquidGlass.debugShaderReady, isTrue);
    expect(tester.binding.hasScheduledFrame, isTrue);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(LiquidGlass).first);
    expect(taps, 2);
    await tester.pumpWidget(const SizedBox.shrink());
    expect(tester.takeException(), isNull);
  });

  testWidgets('panes can be disposed while the shader is loading', (
    tester,
  ) async {
    final program = (await tester.runAsync(
      () => ui.FragmentProgram.fromAsset('shaders/liquid_glass.frag'),
    ))!;
    final loaded = Completer<ui.FragmentProgram>();
    LiquidGlass.debugResetShaderLoading(
      waitForFirstFrame: () async {},
      loadProgram: () => loaded.future,
    );
    await tester.pumpWidget(_scene());
    await tester.pump();
    await tester.pumpWidget(const SizedBox.shrink());
    loaded.complete(program);
    await tester.pumpAndSettle();
    expect(LiquidGlass.debugShaderReady, isTrue);
    expect(LiquidGlassHost.debugHeldCaptures, 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets('shader failure retains interactive fallback without retries', (
    tester,
  ) async {
    var loads = 0;
    var taps = 0;
    LiquidGlass.debugResetShaderLoading(
      waitForFirstFrame: () async {},
      loadProgram: () async {
        loads++;
        throw StateError('shader unavailable');
      },
    );
    await tester.pumpWidget(_scene(onTap: () => taps++));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isA<StateError>());
    await LiquidGlass.warmUp();
    await tester.pumpWidget(_scene(pressed: true, onTap: () => taps++));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(LiquidGlass));
    expect(taps, 1);
    expect(loads, 1);
    expect(LiquidGlass.debugShaderReady, isFalse);
    await tester.pumpWidget(const SizedBox.shrink());
    expect(tester.takeException(), isNull);
  });

  testWidgets('captured fallback invalidates every drawing input', (
    tester,
  ) async {
    final firstFrame = Completer<void>();
    LiquidGlass.debugResetShaderLoading(
      waitForFirstFrame: () => firstFrame.future,
    );
    final sceneKey = GlobalKey();
    Key paneKey = UniqueKey();
    var tint = const Color(0x30404040);
    var saturation = 1.0;
    var brightness = 1.0;
    var rimStrength = 0.2;
    var hairline = 0.0;
    var haze = 0.0;
    var hazeBlur = 0.0;
    var dispersion = 0.0;
    var corners = BorderRadius.circular(20);
    var size = const Size(160, 85);
    var position = const Offset(35, 38);
    LiquidGlassRim? rim;
    CustomClipper<Path>? clipper;
    var quality = LiquidGlassQuality.ultra;

    Future<Uint8List> pixels() async {
      await tester.pumpWidget(
        Directionality(
          textDirection: TextDirection.ltr,
          child: MediaQuery(
            data: const MediaQueryData(),
            child: Center(
              child: SizedBox(
                width: 240,
                height: 160,
                child: RepaintBoundary(
                  key: sceneKey,
                  child: LiquidGlassScope(
                    enabled: true,
                    quality: quality,
                    child: LiquidGlassHost(
                      enabled: true,
                      child: Stack(
                        children: [
                          const Positioned.fill(
                            child: RepaintBoundary(
                              child: Stack(
                                children: [
                                  Positioned.fill(
                                    child: ColoredBox(color: Color(0xFFE9E2D0)),
                                  ),
                                  Positioned(
                                    left: 40,
                                    top: 0,
                                    width: 45,
                                    height: 160,
                                    child: ColoredBox(color: Color(0xFF2070D0)),
                                  ),
                                  Positioned(
                                    left: 0,
                                    top: 75,
                                    width: 240,
                                    height: 15,
                                    child: ColoredBox(color: Color(0xFFE04020)),
                                  ),
                                ],
                              ),
                            ),
                          ),
                          Positioned(
                            left: position.dx,
                            top: position.dy,
                            child: RepaintBoundary(
                              key: paneKey,
                              child: LiquidGlass(
                                style: LiquidGlassStyle(
                                  tint: tint,
                                  blur: 3,
                                  saturation: saturation,
                                  brightness: brightness,
                                  rim: rimStrength,
                                  hairline: hairline,
                                  haze: haze,
                                  hazeBlur: hazeBlur,
                                  dispersion: dispersion,
                                ),
                                borderRadius: corners,
                                clipper: clipper,
                                rim: rim,
                                glow: LiquidGlassGlow.none,
                                child: SizedBox.fromSize(size: size),
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
          ),
        ),
      );
      final boundary =
          sceneKey.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      final image = boundary.toImageSync();
      final data = (await tester.runAsync(() => image.toByteData()))!;
      image.dispose();
      return data.buffer.asUint8List();
    }

    var previous = await pixels();
    final mutations = <(String, VoidCallback)>[
      ('tint', () => tint = const Color(0x90602010)),
      ('saturation', () => saturation = 0),
      ('brightness', () => brightness = 0.6),
      (
        'rim and hairline',
        () {
          rimStrength = 0.8;
          hairline = 1;
        },
      ),
      (
        'rim shine',
        () => rim = const LiquidGlassRim(
          side: 0.5,
          corner: 0.2,
          flat: 0.1,
          shine: 1,
        ),
      ),
      (
        'corners',
        () => corners = const BorderRadius.vertical(top: Radius.circular(35)),
      ),
      ('size', () => size = const Size(175, 95)),
      ('position and sampling', () => position = const Offset(20, 30)),
      (
        'haze',
        () {
          haze = 0.6;
          hazeBlur = 12;
          dispersion = 4;
        },
      ),
      ('effective standard style', () => quality = LiquidGlassQuality.standard),
      (
        'outline',
        () => clipper = const ShapeBorderClipper(
          shape: BeveledRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(25)),
          ),
        ),
      ),
    ];
    for (final (name, mutate) in mutations) {
      final atlas = LiquidGlassHost.debugLastCapture!.panes.single.atlas;
      mutate();
      final updated = await pixels();
      expect(updated, isNot(orderedEquals(previous)), reason: name);
      if (name == 'tint' || name == 'saturation' || name == 'brightness') {
        expect(
          LiquidGlassHost.debugLastCapture!.panes.single.atlas,
          same(atlas),
          reason: '$name must update the pane while reusing its backdrop',
        );
      }
      // Recreating the pane bypasses its picture cache. Its pixels must match
      // the retained pane after the same input change.
      paneKey = UniqueKey();
      final fresh = await pixels();
      expect(updated, orderedEquals(fresh), reason: name);
      previous = fresh;
    }
    expect(LiquidGlass.debugShaderReady, isFalse);
    await tester.pumpWidget(const SizedBox.shrink());
    expect(LiquidGlassHost.debugHeldCaptures, 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'same-level panes share one capture atlas and reuse static bands',
    (tester) async {
      await tester.pumpWidget(_scene(twoPanes: true));
      await tester.pumpAndSettle();
      final first = LiquidGlassHost.debugLastCapture!;
      expect(first.panes, hasLength(2));
      expect(first.panes.map((pane) => pane.level).toSet(), {0});
      expect(first.panes[0].atlas, same(first.panes[1].atlas));
      expect(LiquidGlassHost.debugHeldCaptures, 2);
      final atlas = first.panes.first.atlas;
      // Press light changes only pane uniforms, not what lies beneath the pane.
      await tester.pumpWidget(_scene(twoPanes: true, pressed: true));
      await tester.pumpAndSettle();
      final glowing = LiquidGlassHost.debugLastCapture!;
      expect(glowing.panes.first.atlas, same(atlas));
      expect(glowing.snapshots, 0);
      await tester.pumpWidget(const SizedBox.shrink());
      expect(LiquidGlassHost.debugHeldCaptures, 0);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'quality switches preserve panes and disabling releases captures',
    (tester) async {
      await tester.pumpWidget(_scene());
      await tester.pumpAndSettle();
      final paneElement = tester.element(find.byType(LiquidGlass));
      expect(LiquidGlassHost.debugHeldCaptures, 1);
      await tester.pumpWidget(_scene(quality: LiquidGlassQuality.standard));
      await tester.pumpAndSettle();
      expect(tester.element(find.byType(LiquidGlass)), same(paneElement));
      expect(LiquidGlassHost.debugHeldCaptures, 1);
      await tester.pumpWidget(_scene(enabled: false));
      await tester.pumpAndSettle();
      expect(LiquidGlassHost.debugHeldCaptures, 0);
      await tester.pumpWidget(_scene());
      await tester.pumpAndSettle();
      expect(LiquidGlassHost.debugHeldCaptures, 1);
      await tester.pumpWidget(const SizedBox.shrink());
      expect(LiquidGlassHost.debugHeldCaptures, 0);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('reduced motion settles press glow without an animation clock', (
    tester,
  ) async {
    await tester.pumpWidget(_scene(reducedMotion: true));
    await tester.pumpWidget(_scene(reducedMotion: true, pressed: true));
    await tester.pump();
    expect(tester.binding.hasScheduledFrame, isFalse);
    expect(LiquidGlassHost.debugHeldCaptures, 1);
    await tester.pumpWidget(const SizedBox.shrink());
    expect(LiquidGlassHost.debugHeldCaptures, 0);
  });

  testWidgets(
    'source snapshots can omit panes without dropping live captures',
    (tester) async {
      final key = GlobalKey();
      await tester.pumpWidget(_scene(sceneKey: key));
      await tester.pumpAndSettle();
      final boundary =
          key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      final image = LiquidGlass.withoutPanes(() => boundary.toImageSync());
      await tester.runAsync(() async {
        expect(await image.toByteData(), isNotNull);
        image.dispose();
      });
      expect(LiquidGlassHost.debugHeldCaptures, 1);
      await tester.pumpWidget(const SizedBox.shrink());
      expect(LiquidGlassHost.debugHeldCaptures, 0);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('frost rim follows square bottom corners of a sheet', (
    tester,
  ) async {
    final key = GlobalKey();
    Future<ByteData> pixels(double shine) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Center(
            child: RepaintBoundary(
              key: key,
              child: LiquidGlass(
                style: const LiquidGlassStyle(
                  tint: Colors.black,
                  blur: 0,
                  rim: 0,
                  rimWidth: 0,
                ),
                borderRadius: const BorderRadius.vertical(
                  top: Radius.circular(40),
                ),
                rim: LiquidGlassRim(side: 0, corner: 0, flat: 0, shine: shine),
                child: const SizedBox(width: 160, height: 100),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final boundary =
          key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      // No host: this exercises the pane's frost fallback, including on an
      // engine where the capture shader happens to be supported.
      final image = boundary.toImageSync();
      final data = await tester.runAsync(() => image.toByteData());
      image.dispose();
      return data!;
    }

    final withoutRim = await pixels(0);
    final withRim = await pixels(1);
    var topHighlightPixels = 0;
    for (var y = 5; y < 95; y++) {
      for (var x = 5; x < 155; x++) {
        final offset = (y * 160 + x) * 4;
        final change = withRim.getUint8(offset) - withoutRim.getUint8(offset);
        if (y < 40 && change > 0) topHighlightPixels++;
        if (y >= 60) {
          expect(change, 0, reason: 'No rounded bottom arc at ($x, $y)');
        }
      }
    }
    expect(topHighlightPixels, greaterThan(0));
    expect(tester.takeException(), isNull);
  });
}
