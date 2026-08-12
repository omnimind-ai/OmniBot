import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 轻量事件总线：OSS 版本仅保留基础广播能力。
class EventBus {
  static final EventBus _instance = EventBus._internal();
  factory EventBus() => _instance;
  EventBus._internal();

  final StreamController<Map<String, dynamic>> _eventController =
      StreamController<Map<String, dynamic>>.broadcast();

  Stream<Map<String, dynamic>> get events => _eventController.stream;

  void emit(Map<String, dynamic> event) {
    _eventController.add(event);
  }

  void dispose() {
    _eventController.close();
  }
}

final eventBusProvider = Provider<EventBus>((ref) {
  return EventBus();
});

/// 兼容入口：订阅并消费事件，避免上层初始化链路变更。
final eventListenerProvider = Provider<void>((ref) {
  final eventBus = ref.read(eventBusProvider);
  eventBus.events.listen((event) {
    // OSS 版本暂不处理扩展事件，保留占位监听避免未消费广播。
  });
});
