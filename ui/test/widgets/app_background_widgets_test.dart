import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/widgets/app_background_widgets.dart';

void main() {
  setUp(() => LegacyTextLocalizer.setResolvedLocale(const Locale('zh')));
  tearDown(LegacyTextLocalizer.clearResolvedLocale);
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('preview renders dedicated layers for chat and workspace', (
    tester,
  ) async {
    const config = AppBackgroundConfig(
      enabled: true,
      sourceType: AppBackgroundSourceType.local,
      localImagePath: '/tmp/missing-background.png',
      remoteImageUrl: '',
      blurSigma: 8,
      frostOpacity: 0.2,
      brightness: 1,
      focalX: 0,
      focalY: 0,
    );

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 280,
              height: 420,
              child: AppBackgroundPreview(
                config: config,
                kind: BackgroundPreviewKind.chat,
              ),
            ),
          ),
        ),
      ),
    );

    expect(
      find.byKey(const ValueKey('app-background-preview-chat')),
      findsOneWidget,
    );
    expect(find.textContaining('聊天 ·'), findsOneWidget);

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 280,
              height: 420,
              child: AppBackgroundPreview(
                config: config,
                kind: BackgroundPreviewKind.workspace,
              ),
            ),
          ),
        ),
      ),
    );

    expect(
      find.byKey(const ValueKey('app-background-preview-workspace')),
      findsOneWidget,
    );
    expect(find.textContaining('聊天 ·'), findsNothing);
  });

  testWidgets('preview drag reports normalized focal point updates', (
    tester,
  ) async {
    Offset? lastOffset;
    double? lastScale;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 280,
            height: 420,
            child: AppBackgroundPreview(
              config: const AppBackgroundConfig(
                enabled: true,
                sourceType: AppBackgroundSourceType.local,
                localImagePath: '/tmp/missing-background.png',
                remoteImageUrl: '',
                blurSigma: 8,
                frostOpacity: 0.2,
                brightness: 1,
                focalX: 0,
                focalY: 0,
              ),
              kind: BackgroundPreviewKind.chat,
              onViewportChanged: (offset, imageScale) {
                lastOffset = offset;
                lastScale = imageScale;
              },
            ),
          ),
        ),
      ),
    );

    await tester.drag(
      find.byKey(const ValueKey('app-background-preview-drag-chat')),
      const Offset(140, 210),
    );
    await tester.pump();

    expect(lastOffset, isNotNull);
    expect(lastOffset!.dx, lessThan(-0.8));
    expect(lastOffset!.dy, lessThan(-0.8));
    expect(lastScale, 1);
  });

  testWidgets('preview pinch reports image scale updates', (tester) async {
    double? lastScale;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 280,
            height: 420,
            child: AppBackgroundPreview(
              config: const AppBackgroundConfig(
                enabled: true,
                sourceType: AppBackgroundSourceType.remote,
                localImagePath: '',
                remoteImageUrl: 'https://example.com/background.png',
                blurSigma: 8,
                frostOpacity: 0.2,
                brightness: 1,
                focalX: 0,
                focalY: 0,
              ),
              kind: BackgroundPreviewKind.chat,
              onViewportChanged: (_, imageScale) {
                lastScale = imageScale;
              },
            ),
          ),
        ),
      ),
    );

    final preview = find.byKey(
      const ValueKey('app-background-preview-drag-chat'),
    );
    final center = tester.getCenter(preview);
    final gesture1 = await tester.startGesture(
      center.translate(-24, 0),
      pointer: 1,
    );
    final gesture2 = await tester.startGesture(
      center.translate(24, 0),
      pointer: 2,
    );
    await tester.pump();
    await gesture1.moveTo(center.translate(-56, 0));
    await gesture2.moveTo(center.translate(56, 0));
    await tester.pump();
    await gesture1.up();
    await gesture2.up();

    expect(lastScale, isNotNull);
    expect(lastScale!, greaterThan(1.4));
  });

  testWidgets('preview reflects custom text color and scaled chat text', (
    tester,
  ) async {
    const config = AppBackgroundConfig(
      enabled: true,
      sourceType: AppBackgroundSourceType.remote,
      localImagePath: '',
      remoteImageUrl: 'https://example.com/background.png',
      blurSigma: 8,
      frostOpacity: 0.2,
      brightness: 1,
      focalX: 0,
      focalY: 0,
      chatTextSize: 18,
      chatTextColorMode: AppBackgroundTextColorMode.custom,
      chatTextHexColor: '#1D3E7B',
    );

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 280,
              height: 420,
              child: AppBackgroundPreview(
                config: config,
                kind: BackgroundPreviewKind.chat,
              ),
            ),
          ),
        ),
      ),
    );

    final titleWidget = tester.widget<Text>(find.text('聊天'));
    expect(titleWidget.style?.color, const Color(0xFF1D3E7B));
    expect(
      titleWidget.style?.fontSize,
      closeTo(11 * resolvedChatTextScale(config), 0.01),
    );
    expect(find.textContaining('聊天 · 自定义颜色'), findsOneWidget);
  });

  testWidgets('preview uses dark fallback surface in dark mode', (
    tester,
  ) async {
    const config = AppBackgroundConfig(
      enabled: false,
      sourceType: AppBackgroundSourceType.none,
      localImagePath: '',
      remoteImageUrl: '',
      blurSigma: 8,
      frostOpacity: 0.2,
      brightness: 1,
      focalX: 0,
      focalY: 0,
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        themeMode: ThemeMode.dark,
        home: const Scaffold(
          body: Center(
            child: SizedBox(
              width: 280,
              height: 420,
              child: AppBackgroundPreview(
                config: config,
                kind: BackgroundPreviewKind.chat,
              ),
            ),
          ),
        ),
      ),
    );

    final fallbackSurface = tester.widget<DecoratedBox>(
      find
          .descendant(
            of: find.byKey(const ValueKey('app-background-preview-chat')),
            matching: find.byType(DecoratedBox),
          )
          .first,
    );
    final decoration = fallbackSurface.decoration as BoxDecoration;

    expect(decoration.color, OmniThemePalette.dark.previewFallback);
  });
}
