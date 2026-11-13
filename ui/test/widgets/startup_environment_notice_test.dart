import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/embedded_terminal_preparation_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/startup_environment_notice.dart';

void main() {
  testWidgets('failure notice permits foreground use, dismissal, and retry', (
    tester,
  ) async {
    var snapshot = EmbeddedTerminalInitSnapshot.fromMap({
      'running': true,
      'completed': false,
    });
    var attempts = 0;
    var foregroundTaps = 0;
    final progress = StreamController<EmbeddedTerminalInitProgress>.broadcast();
    final service = EmbeddedTerminalPreparationService(
      loadSnapshot: () async => snapshot,
      progressStream: progress.stream,
      prepare: () async {
        attempts++;
        snapshot = EmbeddedTerminalInitSnapshot.fromMap({
          'completed': true,
          'success': true,
        });
        return {'success': true};
      },
    );
    addTearDown(service.dispose);
    addTearDown(progress.close);
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        builder: (context, child) =>
            StartupEnvironmentNotice(preparation: service, child: child!),
        home: Scaffold(
          body: Center(
            child: TextButton(
              onPressed: () => foregroundTaps++,
              child: const Text('前台操作'),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('系统安装失败'), findsNothing);
    expect(attempts, 0);

    snapshot = EmbeddedTerminalInitSnapshot.fromMap({
      'completed': true,
      'success': false,
      'error': 'network failed',
    });
    await service.refresh();
    await tester.pumpAndSettle();
    expect(find.text('系统安装失败'), findsOneWidget);
    expect(find.text('network failed'), findsOneWidget);
    await tester.tap(find.text('前台操作'));
    expect(foregroundTaps, 1);
    await tester.tap(find.byKey(const ValueKey('startup-environment-dismiss')));
    await tester.pumpAndSettle();
    expect(find.text('系统安装失败'), findsNothing);
    await service.refresh();
    await tester.pump();
    expect(find.text('系统安装失败'), findsNothing);

    // A subsequent attempt has its own notice if it fails.
    snapshot = EmbeddedTerminalInitSnapshot.fromMap({'running': true});
    await service.refresh();
    snapshot = EmbeddedTerminalInitSnapshot.fromMap({
      'completed': true,
      'success': false,
    });
    await service.refresh();
    await tester.pumpAndSettle();
    expect(find.text('系统安装失败'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('startup-environment-retry')));
    await tester.pumpAndSettle();
    expect(attempts, 1);
    expect(service.ready, isTrue);
    expect(find.text('系统安装失败'), findsNothing);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
