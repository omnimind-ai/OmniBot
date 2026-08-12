import 'package:flutter/foundation.dart';

/// Stable, non-sensitive event codes allowed in local debug diagnostics.
///
/// Event names must describe program state only. They must never contain user
/// input, identifiers, paths, URLs, request or response data, or error text.
enum SafeLogEvent {
  operationStarted,
  operationCompleted,
  operationFailed,
  stateChanged,
}

/// A deliberately type-restricted debug logger.
///
/// The public API accepts no strings or arbitrary objects, so callers cannot
/// accidentally forward secrets, personal data, exception messages, IDs,
/// paths, URLs, maps, or stack traces.
abstract final class SafeLog {
  static void event(
    SafeLogEvent event, {
    bool? success,
    int? count,
    int? byteCount,
  }) {
    if (count != null && count < 0) {
      throw ArgumentError.value(count, 'count', 'must not be negative');
    }
    if (byteCount != null && byteCount < 0) {
      throw ArgumentError.value(
        byteCount,
        'byteCount',
        'must not be negative',
      );
    }
    if (!kDebugMode) return;

    final fields = <String>[
      event.name,
      if (success != null) 'success=$success',
      if (count != null) 'count=$count',
      if (byteCount != null) 'byteCount=$byteCount',
    ];
    debugPrint(fields.join(' '));
  }
}
