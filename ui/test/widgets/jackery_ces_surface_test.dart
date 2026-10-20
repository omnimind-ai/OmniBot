import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/omnibot_error_widget.dart';

void main() {
  testWidgets('CES glass panels stay warm even with dark or blue overrides', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        home: const Scaffold(
          body: OmniGlassPanel(
            forceDark: true,
            surfaceColor: Colors.blue,
            child: Text('Panel content'),
          ),
        ),
      ),
    );
    final decorations = tester
        .widgetList<DecoratedBox>(
          find.descendant(
            of: find.byType(OmniGlassPanel),
            matching: find.byType(DecoratedBox),
          ),
        )
        .map((widget) => widget.decoration)
        .whereType<BoxDecoration>()
        .toList();
    if (JackeryCesConfig.enabled) {
      expect(
        decorations.any(
          (decoration) => decoration.color == JackeryCesConfig.background,
        ),
        true,
      );
      expect(
        decorations.every((decoration) => decoration.gradient == null),
        true,
      );
      expect(find.byType(BackdropFilter), findsNothing);
    } else {
      expect(
        decorations.any((decoration) => decoration.color == Colors.blue),
        true,
      );
    }
  });

  testWidgets('rendering fallback supports a missing Material theme', (
    tester,
  ) async {
    await tester.pumpWidget(
      const Directionality(
        textDirection: TextDirection.ltr,
        child: OmnibotSafeErrorWidget(),
      ),
    );
    final container = tester.widget<Container>(find.byType(Container).first);
    if (JackeryCesConfig.enabled) {
      expect(container.color, JackeryCesConfig.background);
      expect(find.text('Content is temporarily unavailable'), findsOneWidget);
    } else {
      expect(container.color, const Color(0xFFF6F8FC));
      expect(find.text('内容暂时无法显示'), findsOneWidget);
    }
    expect(tester.takeException(), isNull);
  });
}
