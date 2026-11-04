import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:ui/services/agent_runtime_service.dart';

/// Owns only the visibility of the native Na history subscription. Messages and
/// Agent execution remain owned by the existing Conversation/ACP coordinator.
class NaHistorySyncService with WidgetsBindingObserver {
  NaHistorySyncService._();

  static final instance = NaHistorySyncService._();
  bool _initialized = false;
  Future<void> _tail = Future<void>.value();

  void initialize() {
    if (_initialized) return;
    _initialized = true;
    WidgetsBinding.instance.addObserver(this);
    final state = WidgetsBinding.instance.lifecycleState;
    _publish(state == null || state == AppLifecycleState.resumed);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _publish(true);
    } else if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached ||
        state == AppLifecycleState.hidden) {
      _publish(false);
    }
  }

  void _publish(bool enabled) {
    // Serialize pause/resume across an outstanding platform invocation so a
    // delayed background request cannot disable a newly resumed subscription.
    _tail = _tail.then((_) async {
      try {
        await AgentRuntimeService.setNaHistorySyncEnabled(enabled);
      } catch (_) {
        // A missing/older native host must not prevent chat initialization.
        // Resume and the next native login/configuration change can retry.
      }
    });
  }
}
