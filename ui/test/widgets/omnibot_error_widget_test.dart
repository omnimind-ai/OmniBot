import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/omnibot_error_widget.dart';

void main() {
  testWidgets('global fallback replaces Flutter error widget', (tester) async {
    installOmnibotErrorWidget();
    final fallback = ErrorWidget.builder(
      FlutterErrorDetails(exception: StateError('test rendering failure')),
    );

    await tester.pumpWidget(MaterialApp(home: fallback));

    expect(find.byType(OmnibotSafeErrorWidget), findsOneWidget);
    expect(find.text('内容暂时无法显示'), findsOneWidget);
  });

  testWidgets('safe error widget does not expose Flutter red error UI', (
    tester,
  ) async {
    await tester.pumpWidget(const MaterialApp(home: OmnibotSafeErrorWidget()));

    expect(find.text('内容暂时无法显示'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
