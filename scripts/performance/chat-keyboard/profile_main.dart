// Identical, opt-in probe for both frozen source versions. No alternate UI.
import 'dart:async';
import 'dart:convert';
import 'dart:developer' as developer;
import 'dart:io';
import 'dart:ui';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/chat_input_area.dart';
import 'package:ui/main_standard.dart' as app;
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/storage_service.dart';

const _package = 'cn.com.omnimind.bot.keyboardperf';
const _extension = 'ext.omnibot.keyboardPerf';

void _checkProcess() {
  if (!Platform.isAndroid ||
      File('/proc/self/cmdline').readAsStringSync().split('\u0000').first !=
          _package) {
    throw StateError('Probe is restricted to the independent keyboardperf app');
  }
}

Future<void> main(List<String> args) async {
  _checkProcess();
  WidgetsFlutterBinding.ensureInitialized();
  await StorageService.init();
  await StorageService.setBool(StorageKeys.welcomeCompleted, true);
  await app.main([...args, '--route=/home/home']);
  final probe = _KeyboardProbe();
  WidgetsBinding.instance.addObserver(probe);
  GestureBinding.instance.pointerRouter.addGlobalRoute(probe.onPointer);
  SchedulerBinding.instance.addTimingsCallback(probe.onFrames);
  developer.registerExtension(_extension, (method, parameters) async {
    try {
      _checkProcess();
      final result = switch (parameters['op']) {
        'prepareScenario' => await probe.prepare(parameters['scenario']!),
        'startCapture' => probe.start(),
        'result' => probe.result(),
        'hide' => await probe.hide(),
        'home' => await probe.home(),
        _ => throw ArgumentError('Unknown probe operation'),
      };
      return developer.ServiceExtensionResponse.result(jsonEncode(result));
    } catch (error) {
      return developer.ServiceExtensionResponse.error(
        developer.ServiceExtensionResponse.extensionError,
        'Keyboard probe failed: ${error.runtimeType}: $error',
      );
    }
  });
}

class _KeyboardProbe with WidgetsBindingObserver {
  final _frames = <Map<String, int>>[];
  final _metrics = <Map<String, num>>[];
  final _focusEvents = <Map<String, Object>>[];
  FocusNode? _focus;
  Rect? _fieldRect;
  Timer? _stableTimer;
  bool _capturing = false;
  int? _startUs, _downUs, _upUs, _focusUs, _firstInsetUs, _lastInsetUs;
  int? _stableConfirmedUs, _pointerEventDownUs;
  String _scenario = '';
  int _loadedMessages = 0;

  FlutterView get _view =>
      WidgetsBinding.instance.platformDispatcher.views.first;
  double get _inset => _view.viewInsets.bottom / _view.devicePixelRatio;
  int get _now => developer.Timeline.now;

  List<Element> _elements() {
    final result = <Element>[];
    void visit(Element element) {
      if (element.widget case Offstage(offstage: true)) return;
      result.add(element);
      element.visitChildren(visit);
    }

    final root = WidgetsBinding.instance.rootElement;
    if (root != null) visit(root);
    return result;
  }

  bool _visible(Element element) {
    final box = element.findRenderObject();
    if (box is! RenderBox ||
        !box.attached ||
        !box.hasSize ||
        box.size.isEmpty) {
      return false;
    }
    final center = box.localToGlobal(box.size.center(Offset.zero));
    final viewRect =
        Offset.zero & (_view.physicalSize / _view.devicePixelRatio);
    return viewRect.contains(center);
  }

  ({FocusNode focus, Rect rect}) _composer() {
    for (final element in _elements()) {
      final widget = element.widget;
      if (widget is! ChatInputArea || !_visible(element)) continue;
      Element? field;
      void search(Element child) {
        if (child.widget case TextField(:final focusNode)) {
          if (identical(focusNode, widget.focusNode) && _visible(child)) {
            field = child;
          }
        }
        child.visitChildren(search);
      }

      element.visitChildren(search);
      if (field == null) continue;
      if (widget.controller.text.isNotEmpty) {
        throw StateError('Measurement requires an empty composer');
      }
      final box = field!.findRenderObject()! as RenderBox;
      return (
        focus: widget.focusNode,
        rect: box.localToGlobal(Offset.zero) & box.size,
      );
    }
    throw StateError('No visible real ChatInputArea TextField');
  }

  ChatMessageList? _messageList() {
    final lists = _elements()
        .where((e) => e.widget is ChatMessageList && _visible(e))
        .map((e) => e.widget as ChatMessageList)
        .toList();
    lists.sort((a, b) => b.messages.length.compareTo(a.messages.length));
    return lists.firstOrNull;
  }

  Future<Map<String, Object?>> prepare(String scenario) async {
    if (scenario != 'empty' && scenario != 'history') {
      throw ArgumentError('Use empty or history');
    }
    _capturing = false;
    _stableTimer?.cancel();
    _focus?.removeListener(_onFocus);
    _focus = null;
    for (
      var attempt = 0;
      GoRouterManager.rootNavigatorKey.currentContext == null && attempt < 100;
      attempt++
    ) {
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    GoRouterManager.go(GoRouterManager.homeRoute);
    await Future<void>.delayed(const Duration(milliseconds: 600));
    ConversationThreadTarget target;
    if (scenario == 'history') {
      const key = 'keyboard_perf_history_v1';
      var id = StorageService.getInt(key);
      if (id == null) {
        id = await ConversationService.createConversation(
          title: 'Synthetic keyboard benchmark: 200 messages',
          mode: ConversationMode.chatOnly,
          rethrowOnError: true,
        );
        if (id == null)
          throw StateError('Synthetic conversation creation failed');
        final messages = List.generate(200, (index) {
          final assistant = index.isOdd;
          return ChatMessageModel(
            id: 'keyboard-perf-message-$index',
            type: 1,
            user: assistant ? 2 : 1,
            createAt: DateTime.utc(2026, 1, 1).add(Duration(seconds: index)),
            content: {
              'text': assistant
                  ? '## 合成回复 $index\n\n这是仅用于键盘性能测量的合成内容。'
                        '包含 **加粗**、列表和代码，所有数据都在独立应用中生成。\n\n'
                        '- 固定条目一\n- 固定条目二\n\n```dart\nfinal value = $index;\n```\n'
                  : '合成问题 $index：请解释列表渲染和输入法动画的关系。',
            },
          );
        });
        await ConversationHistoryService.saveConversationMessages(
          id,
          messages,
          mode: ConversationMode.chatOnly,
        );
        final saved = await ConversationHistoryService.getConversationMessages(
          id,
          mode: ConversationMode.chatOnly,
        );
        if (saved.length != 200)
          throw StateError('Synthetic history was not saved');
        await StorageService.setInt(key, id);
      }
      target = ConversationThreadTarget.existing(
        conversationId: id,
        mode: ConversationMode.chatOnly,
      );
    } else {
      target = const ConversationThreadTarget.newConversation(
        mode: ConversationMode.agent,
        agentId: 'xiaowan-acp',
      );
    }
    GoRouterManager.go(GoRouterManager.chatRoute, extra: target);
    await Future<void>.delayed(const Duration(milliseconds: 1800));
    if (scenario == 'history') {
      for (var attempt = 0; attempt < 30; attempt++) {
        final list = _messageList();
        if (list != null && list.messages.length >= 200) break;
        if (list != null && list.hasMore && list.onLoadMore != null) {
          await list.onLoadMore!();
        }
        await Future<void>.delayed(const Duration(milliseconds: 150));
      }
    }
    _scenario = scenario;
    _loadedMessages = _messageList()?.messages.length ?? 0;
    if (scenario == 'history' && _loadedMessages != 200) {
      throw StateError(
        'Expected 200 messages through real paging, got $_loadedMessages',
      );
    }
    await hide();
    final composer = _composer();
    _focus = composer.focus;
    _fieldRect = composer.rect;
    _focus!.addListener(_onFocus);
    return _state();
  }

  Map<String, Object?> _state() => {
    'package': _package,
    'scenario': _scenario,
    'loadedMessages': _loadedMessages,
    'devicePixelRatio': _view.devicePixelRatio,
    'refreshRateHz': _view.display.refreshRate,
    'viewInsetsBottom': _inset,
    'focused': _focus?.hasFocus ?? false,
    if (_fieldRect != null)
      'tap': {
        'x': (_fieldRect!.center.dx * _view.devicePixelRatio).round(),
        'y': (_fieldRect!.center.dy * _view.devicePixelRatio).round(),
      },
  };

  Map<String, Object?> start() {
    final composer = _composer();
    if (!identical(composer.focus, _focus)) {
      throw StateError('Composer changed; prepare the scenario again');
    }
    if (_focus!.hasFocus || _inset > 0) {
      throw StateError('Hide the keyboard and clear focus before capture');
    }
    _fieldRect = composer.rect;
    _frames.clear();
    _metrics.clear();
    _focusEvents.clear();
    _downUs = _upUs = _focusUs = _firstInsetUs = _lastInsetUs = null;
    _stableConfirmedUs = _pointerEventDownUs = null;
    _stableTimer?.cancel();
    _startUs = _now;
    _capturing = true;
    return _state();
  }

  void onPointer(PointerEvent event) {
    if (!_capturing) return;
    if (event is PointerDownEvent && _fieldRect!.contains(event.position)) {
      _downUs ??= _now;
      _pointerEventDownUs ??= event.timeStamp.inMicroseconds;
    } else if (event is PointerUpEvent && _downUs != null) {
      _upUs ??= _now;
    }
  }

  void _onFocus() {
    if (!_capturing) return;
    final now = _now;
    _focusEvents.add({'atUs': now, 'focused': _focus!.hasFocus});
    if (_focus!.hasFocus) _focusUs ??= now;
  }

  @override
  void didChangeMetrics() {
    if (!_capturing || _downUs == null) return;
    final now = _now;
    final inset = _inset;
    _metrics.add({'atUs': now, 'bottom': inset});
    _stableTimer?.cancel();
    if (inset > 0) {
      _firstInsetUs ??= now;
      _lastInsetUs = now;
      _stableConfirmedUs = null;
      _stableTimer = Timer(const Duration(milliseconds: 120), () {
        if (_capturing && _inset > 0) _stableConfirmedUs = _now;
      });
    }
  }

  void onFrames(List<FrameTiming> frames) {
    if (!_capturing) return;
    for (final frame in frames) {
      final start = frame.timestampInMicroseconds(FramePhase.buildStart);
      if (start < _startUs!) continue;
      _frames.add({
        'startUs': start,
        'buildUs': frame.buildDuration.inMicroseconds,
        'rasterUs': frame.rasterDuration.inMicroseconds,
        'totalUs': frame.totalSpan.inMicroseconds,
      });
    }
  }

  Map<String, Object?> result() {
    _capturing = false;
    _stableTimer?.cancel();
    double? sinceDown(int? time) =>
        time == null || _downUs == null ? null : (time - _downUs!) / 1000;
    return {
      ..._state(),
      'valid':
          _downUs != null && _focusUs != null && _stableConfirmedUs != null,
      'captureStartUs': _startUs,
      'pointerDownReceivedUs': _downUs,
      'pointerDownEventTimestampUs': _pointerEventDownUs,
      'pointerUpReceivedUs': _upUs,
      'pointerUpMs': sinceDown(_upUs),
      'focusMs': sinceDown(_focusUs),
      'firstInsetMs': sinceDown(_firstInsetUs),
      'lastInsetMs': sinceDown(_lastInsetUs),
      'stableConfirmedMs': sinceDown(_stableConfirmedUs),
      'focusEvents': _focusEvents,
      'insets': _metrics,
      'frames': _frames,
    };
  }

  Future<Map<String, Object?>> hide() async {
    _capturing = false;
    _stableTimer?.cancel();
    FocusManager.instance.primaryFocus?.unfocus();
    await SystemChannels.textInput.invokeMethod<void>('TextInput.hide');
    await Future<void>.delayed(const Duration(milliseconds: 650));
    return _state();
  }

  Future<Map<String, Object?>> home() async {
    await hide();
    GoRouterManager.go(GoRouterManager.homeRoute);
    return {'package': _package, 'home': true};
  }
}
