import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:ui/services/special_permission.dart';

/// Observes the native installation owner. It never starts a second installer
/// or derives Agent/turn states from environment preparation.
class EmbeddedTerminalPreparationService extends ChangeNotifier {
  EmbeddedTerminalPreparationService({
    Future<EmbeddedTerminalInitSnapshot> Function()? loadSnapshot,
    Stream<EmbeddedTerminalInitProgress>? progressStream,
    Future<Map<String, dynamic>> Function()? prepare,
  }) : _loadSnapshot = loadSnapshot ?? getEmbeddedTerminalInitSnapshot,
       _progressStream = progressStream,
       _prepare = prepare ?? retryEmbeddedTerminalInit;

  static final instance = EmbeddedTerminalPreparationService();

  final Future<EmbeddedTerminalInitSnapshot> Function() _loadSnapshot;
  final Stream<EmbeddedTerminalInitProgress>? _progressStream;
  final Future<Map<String, dynamic>> Function() _prepare;
  StreamSubscription<EmbeddedTerminalInitProgress>? _subscription;
  Timer? _pollTimer;
  Future<void>? _refresh;
  bool _refreshAgain = false;
  Future<void>? _retry;
  bool _observing = false;
  bool _disposed = false;
  int _generation = 0;

  EmbeddedTerminalInitSnapshot? _snapshot;
  Object? _observationError;

  EmbeddedTerminalInitSnapshot? get snapshot => _snapshot;
  bool get ready => _snapshot?.completed == true && _snapshot?.success == true;
  bool get failed =>
      _snapshot?.completed == true && _snapshot?.success == false;
  Object? get observationError => _observationError;
  bool get retrying => _retry != null;

  void startObserving() {
    if (_observing || _disposed) return;
    _observing = true;
    _subscription = (_progressStream ?? embeddedTerminalInitProgressStream)
        .listen(
          (_) => unawaited(refresh()),
          onError: (Object error) {
            if (_disposed || !_observing) return;
            _observationError = error;
            notifyListeners();
          },
        );
    unawaited(refresh());
  }

  Future<void> refresh() {
    if (_disposed) return Future<void>.value();
    if (_refresh != null) {
      _refreshAgain = true;
      return _refresh!;
    }
    return _refresh ??= _readSnapshots().whenComplete(() => _refresh = null);
  }

  Future<void> _readSnapshots() async {
    do {
      _refreshAgain = false;
      await _readSnapshot();
    } while (_refreshAgain && !_disposed);
  }

  Future<void> _readSnapshot() async {
    final generation = _generation;
    try {
      final next = await _loadSnapshot();
      if (_disposed || generation != _generation) return;
      _snapshot = next;
      _observationError = null;
    } catch (error) {
      if (_disposed || generation != _generation) return;
      // A channel failure is an observation error, not proof the native
      // installation failed. Keep the last authoritative snapshot.
      _observationError = error;
    }
    if (_disposed || generation != _generation) return;
    _schedulePoll();
    notifyListeners();
  }

  void _schedulePoll() {
    _pollTimer?.cancel();
    // Progress events are advisory; periodic reads also recover a missed event
    // or an initially idle snapshot before the first native frame starts setup.
    if (!_observing || (_snapshot?.completed == true && !retrying)) return;
    _pollTimer = Timer(const Duration(seconds: 1), () {
      _pollTimer = null;
      unawaited(refresh());
    });
  }

  Future<void> retry() {
    if (_disposed) return Future<void>.value();
    if (_retry != null) return _retry!;
    final operation = _retryPreparation();
    _retry = operation;
    notifyListeners();
    unawaited(refresh());
    return operation;
  }

  Future<void> _retryPreparation() async {
    Object? commandError;
    try {
      await _prepare();
    } catch (error) {
      commandError = error;
    } finally {
      // Ensure the field is assigned before clearing it if the channel throws
      // synchronously, and show only the native completion outcome.
      await Future<void>.value();
      _retry = null;
      if (!_disposed) {
        await refresh();
        if (!_disposed && commandError != null && !ready && !failed) {
          _observationError = commandError;
          notifyListeners();
        }
      }
    }
  }

  void stopObserving() {
    _observing = false;
    _subscription?.cancel();
    _subscription = null;
    _pollTimer?.cancel();
    _pollTimer = null;
  }

  @override
  void removeListener(VoidCallback listener) {
    super.removeListener(listener);
    // The launch notice normally observes for the app's lifetime. Release
    // polling whenever the last installation UI observer leaves.
    if (!hasListeners) stopObserving();
  }

  @visibleForTesting
  void resetForTest() {
    stopObserving();
    _generation++;
    _snapshot = null;
    _observationError = null;
    _refresh = null;
    _refreshAgain = false;
    _retry = null;
  }

  @override
  void dispose() {
    _disposed = true;
    stopObserving();
    super.dispose();
  }
}
