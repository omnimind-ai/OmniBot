import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/app_bootstrap.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme_mode.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final originalDebugPrint = debugPrint;
  late List<String> logs;

  setUp(() {
    logs = [];
    debugPrint = (message, {wrapWidth}) {
      if (message != null) logs.add(message);
    };
  });

  tearDown(() => debugPrint = originalDebugPrint);

  for (final systemUiFirst in [true, false]) {
    test(
      'startup starts both prerequisites and waits for '
      '${systemUiFirst ? 'storage' : 'system UI'} before reading the theme',
      () async {
        SharedPreferences.setMockInitialValues({
          StorageService.kThemeOptionKey: 'light',
        });
        await StorageService.init();
        SharedPreferences.setMockInitialValues({
          StorageService.kThemeOptionKey: 'dark',
        });
        final systemUi = Completer<void>();
        final storage = Completer<void>();
        final started = <String>[];
        AppThemeMode? startupTheme;
        final continuation = initializeMainStartupPrerequisites(
          configureSystemUi: () {
            started.add('system UI');
            return systemUi.future;
          },
          initializeStorage: () {
            started.add('storage');
            return storage.future.then((_) => StorageService.init());
          },
        ).then((_) => startupTheme = StorageService.getThemeMode());

        expect(started, ['system UI', 'storage']);
        expect(StorageService.getThemeMode(), AppThemeMode.light);
        expect(startupTheme, isNull);
        (systemUiFirst ? systemUi : storage).complete();
        await Future<void>.delayed(Duration.zero);
        expect(
          startupTheme,
          isNull,
          reason: 'The other prerequisite is pending',
        );
        (systemUiFirst ? storage : systemUi).complete();
        await continuation;

        expect(startupTheme, AppThemeMode.dark);
        expect(logs, isEmpty);
      },
    );
  }

  for (final failsSystemUi in [true, false]) {
    for (final throwsSynchronously in [true, false]) {
      final label = failsSystemUi
          ? 'system UI mode setup'
          : 'StorageService.init';
      test(
        '$label fails open on a ${throwsSynchronously ? 'synchronous' : 'future'} '
        'error while still waiting for the other prerequisite',
        () async {
          final failure = StateError('unavailable');
          final failedStep = Completer<void>();
          final otherStep = Completer<void>();
          var failedStarted = false;
          var otherStarted = false;
          var continued = false;
          Future<void> fail() {
            failedStarted = true;
            if (throwsSynchronously) throw failure;
            return failedStep.future;
          }

          Future<void> other() {
            otherStarted = true;
            return otherStep.future;
          }

          final continuation = initializeMainStartupPrerequisites(
            configureSystemUi: failsSystemUi ? fail : other,
            initializeStorage: failsSystemUi ? other : fail,
          ).then((_) => continued = true);
          expect(failedStarted, isTrue);
          expect(otherStarted, isTrue);
          if (!throwsSynchronously) failedStep.completeError(failure);
          await Future<void>.delayed(Duration.zero);

          expect(continued, isFalse);
          expect(logs, contains('[FlutterStartup] $label failed: $failure'));
          otherStep.complete();
          await continuation;
          expect(continued, isTrue);
        },
      );
    }
  }

  test('both failures settle before startup continues', () async {
    final systemUi = Completer<void>();
    final storage = Completer<void>();
    var continued = false;
    final continuation = initializeMainStartupPrerequisites(
      configureSystemUi: () => systemUi.future,
      initializeStorage: () => storage.future,
    ).then((_) => continued = true);

    systemUi.completeError(StateError('system UI unavailable'));
    await Future<void>.delayed(Duration.zero);
    expect(continued, isFalse);
    storage.completeError(StateError('storage unavailable'));
    await continuation;

    expect(continued, isTrue);
    expect(logs.where((line) => line.startsWith('[FlutterStartup]')), [
      '[FlutterStartup] system UI mode setup failed: '
          'Bad state: system UI unavailable',
      '[FlutterStartup] StorageService.init failed: '
          'Bad state: storage unavailable',
    ]);
  });
}
