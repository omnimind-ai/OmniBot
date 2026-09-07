import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/special_permission.dart';

void main() {
  test(
    'inventory distinguishes missing from unknown without coercing values',
    () {
      for (final value in [null, 'false', 0]) {
        expect(
          EmbeddedTerminalSetupInventoryItem.fromMap({'ready': value}).ready,
          isNull,
        );
      }
      expect(
        EmbeddedTerminalSetupInventoryItem.fromMap({'ready': false}).ready,
        isFalse,
      );
      expect(
        EmbeddedTerminalSetupInventoryItem.fromMap({
          'ready': true,
          'version': '',
        }).ready,
        isTrue,
      );
      expect(EmbeddedTerminalSetupInventoryItem.fromMap({}).ready, isNull);
    },
  );
  test('parses Ubuntu runtime download progress', () {
    final progress = EmbeddedTerminalInitProgress.fromMap(<String, dynamic>{
      'kind': 'status',
      'message': 'Downloading Ubuntu',
      'timestamp': 1234,
      'phase': 'downloading',
      'distribution': 'ubuntu',
      'downloadedBytes': 25,
      'totalBytes': 100,
      'progress': 0.25,
    });

    expect(progress.phase, 'downloading');
    expect(progress.distribution, EmbeddedTerminalDistribution.ubuntu);
    expect(progress.downloadedBytes, 25);
    expect(progress.totalBytes, 100);
    expect(progress.progress, 0.25);
  });

  test('clamps progress and preserves structured errors', () {
    final progress = EmbeddedTerminalInitProgress.fromMap(<String, dynamic>{
      'kind': 'error',
      'message': 'Download failed',
      'timestamp': 1234,
      'phase': 'error',
      'distribution': 'ubuntu',
      'progress': 2,
      'error': 'network unavailable',
    });

    expect(progress.progress, 1);
    expect(progress.error, 'network unavailable');
  });
}
