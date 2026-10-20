import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';

@immutable
class JackeryDeviceStatus {
  const JackeryDeviceStatus({
    this.connected = false,
    this.batteryPercent,
    this.power,
    this.connection,
    this.aiModelStatus,
    this.observedAtEpochMs,
  });

  factory JackeryDeviceStatus.fromMap(Map<dynamic, dynamic> data) {
    if (data['connected'] != true) return const JackeryDeviceStatus();
    final battery = data['batteryPercent'];
    return JackeryDeviceStatus(
      connected: true,
      batteryPercent:
          battery is num &&
              battery.isFinite &&
              battery >= 0 &&
              battery <= 100 &&
              battery == battery.toInt()
          ? battery.toInt()
          : null,
      power: data['power'] as String?,
      connection: data['connection'] as String?,
      aiModelStatus: data['aiModelStatus'] as String?,
      observedAtEpochMs: (data['observedAtEpochMs'] as num?)?.toInt(),
    );
  }

  final bool connected;
  final int? batteryPercent;
  final String? power;
  final String? connection;
  final String? aiModelStatus;
  final int? observedAtEpochMs;

  String get batteryLabel =>
      batteryPercent == null ? 'Battery unavailable' : '$batteryPercent%';
  String get connectionLabel => !connected
      ? 'Not connected'
      : switch (connection) {
          'wifi' => 'Wi-Fi',
          'usb' => 'USB',
          'ethernet' => 'Ethernet',
          'bluetooth' => 'Bluetooth',
          _ => 'Connected',
        };
  String get connectionDetailLabel => !connected
      ? 'Not connected'
      : connection == null
      ? 'Connection unavailable'
      : connectionLabel;
  String get powerLabel => switch (power) {
    'battery' => 'Battery power',
    'external' => 'External power',
    'charging' => 'Charging',
    'off' => 'Powered off',
    _ => 'Power unavailable',
  };
  String get modelLabel => switch (aiModelStatus) {
    'ready' => 'On-device AI ready',
    'loading' => 'AI loading',
    _ => 'AI unavailable',
  };
}

/// Vendor telemetry has one polling owner shared by all three UI surfaces.
/// Polling stops while the app is hidden or no device surface is mounted.
class JackeryCesService extends ChangeNotifier with WidgetsBindingObserver {
  JackeryCesService._();
  static final instance = JackeryCesService._();
  static const channel = MethodChannel('cn.com.omnimind.bot/jackery_ces');
  static final ValueNotifier<int> demoReset = ValueNotifier(0);
  static bool isResetting = false;

  JackeryDeviceStatus status = const JackeryDeviceStatus();
  String? deviceImagePath;
  Timer? _timer;
  int _subscribers = 0;
  int _generation = 0;
  bool _inFlight = false;
  bool _resumed = true;

  void attach() {
    _subscribers++;
    if (_subscribers != 1) return;
    WidgetsBinding.instance.addObserver(this);
    _resumed =
        WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    unawaited(_loadArtwork());
    _startPolling();
  }

  void detach() {
    _subscribers = (_subscribers - 1).clamp(0, 10000);
    if (_subscribers != 0) return;
    WidgetsBinding.instance.removeObserver(this);
    _stopPolling();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _resumed = state == AppLifecycleState.resumed;
    if (_resumed) {
      _startPolling();
    } else {
      _stopPolling();
      status = const JackeryDeviceStatus();
      notifyListeners();
    }
  }

  Future<void> _loadArtwork() async {
    try {
      final config = await channel.invokeMapMethod<dynamic, dynamic>(
        'getConfig',
      );
      deviceImagePath = config?['deviceImagePath'] as String?;
      notifyListeners();
    } on PlatformException {
      deviceImagePath = null;
    } on MissingPluginException {
      deviceImagePath = null;
    }
  }

  void _startPolling() {
    if (!_resumed || _subscribers == 0 || _timer != null) return;
    unawaited(refresh());
    var ticks = 0;
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      final observed = status.observedAtEpochMs;
      if (status.connected &&
          (observed == null ||
              DateTime.now().millisecondsSinceEpoch - observed > 15000)) {
        status = const JackeryDeviceStatus();
        notifyListeners();
      }
      if (++ticks % 5 == 0) unawaited(refresh());
    });
  }

  void _stopPolling() {
    _timer?.cancel();
    _timer = null;
    _generation++;
  }

  Future<void> refresh() async {
    if (_inFlight || !_resumed || _subscribers == 0) return;
    _inFlight = true;
    final generation = _generation;
    JackeryDeviceStatus next;
    try {
      final response = await channel.invokeMapMethod<dynamic, dynamic>(
        'getDeviceStatus',
      );
      next = JackeryDeviceStatus.fromMap(response ?? const {});
    } on PlatformException {
      next = const JackeryDeviceStatus();
    } on MissingPluginException {
      next = const JackeryDeviceStatus();
    } catch (_) {
      next = const JackeryDeviceStatus();
    } finally {
      _inFlight = false;
    }
    if (generation != _generation || !_resumed || _subscribers == 0) return;
    status = next;
    notifyListeners();
  }

  static Future<void> resetDemo() async {
    if (isResetting) throw StateError('Demo reset is already in progress.');
    isResetting = true;
    final coordinator = ChatConversationRuntimeCoordinator.instance;
    channel.setMethodCallHandler((call) async {
      if (call.method != 'flushDemoPersistence') throw MissingPluginException();
      await coordinator.prepareForDemoReset();
      return true;
    });
    var contentChanged = false;
    var projectionsDiscarded = false;
    Future<void> discardDeletedContent() async {
      projectionsDiscarded = true;
      await coordinator.clearAfterDemoReset();
      try {
        await (await SharedPreferences.getInstance()).reload();
      } finally {
        demoReset.value++;
      }
    }

    try {
      final result = await channel.invokeMapMethod<dynamic, dynamic>(
        'resetDemo',
      );
      if (result?['success'] != true) {
        throw StateError(
          'The demo reset could not be confirmed. Please try again.',
        );
      }
      contentChanged = true;
      await discardDeletedContent();
    } catch (error) {
      contentChanged =
          contentChanged ||
          (error is PlatformException &&
              error.details is Map &&
              (error.details as Map)['contentChanged'] == true);
      if (contentChanged) {
        // Partial deletion still invalidates every old projection. The caller
        // continues to receive the failure and cannot announce success.
        if (!projectionsDiscarded) await discardDeletedContent();
      } else {
        await coordinator.abortDemoReset();
      }
      rethrow;
    } finally {
      isResetting = false;
      channel.setMethodCallHandler(null);
    }
  }
}
