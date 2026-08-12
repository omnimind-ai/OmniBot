import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/core/safe_log.dart';

void main() {
  group('SafeLog', () {
    test('accepts only the declared safe scalar fields', () {
      expect(
        () => SafeLog.event(
          SafeLogEvent.operationCompleted,
          success: true,
          count: 2,
          byteCount: 16,
        ),
        returnsNormally,
      );
    });

    test('rejects negative counters', () {
      expect(
        () => SafeLog.event(SafeLogEvent.operationFailed, count: -1),
        throwsArgumentError,
      );
      expect(
        () => SafeLog.event(SafeLogEvent.operationFailed, byteCount: -1),
        throwsArgumentError,
      );
    });
  });

  test('production sources cannot bypass SafeLog', () {
    final sourceRoot = Directory('lib');
    expect(sourceRoot.existsSync(), isTrue);

    final forbidden = RegExp(
      r"\b(?:print|debugPrint)\s*\(|//\s*ignore:\s*avoid_print|dart:developer",
    );
    final violations = <String>[];
    for (final entity in sourceRoot.listSync(recursive: true)) {
      if (entity is! File || !entity.path.endsWith('.dart')) continue;
      if (entity.path.replaceAll('\\', '/').endsWith('/core/safe_log.dart')) {
        continue;
      }
      final lines = entity.readAsLinesSync();
      for (var index = 0; index < lines.length; index += 1) {
        if (forbidden.hasMatch(lines[index])) {
          violations.add('${entity.path}:${index + 1}');
        }
      }
    }

    expect(violations, isEmpty, reason: violations.join('\n'));
  });
}
