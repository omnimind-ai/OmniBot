// Opt-in synthetic probe; never used by a production entrypoint.
import 'dart:async';
import 'dart:convert';
import 'dart:developer' as developer;
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui';

import 'package:crypto/crypto.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/features/home/pages/chat/chat_page.dart';
import 'package:ui/features/home/pages/chat/mixins/conversation_manager.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/main_standard.dart' as app;
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/agent_event_reducer.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/storage_service.dart';

const _package = 'cn.com.omnimind.bot.runtimeperf';
const _extension = 'ext.omnibot.runtimePerf';
const _seedPrefix = 'runtime_perf_history_v1_';

void _checkProcess() {
  if (!Platform.isAndroid ||
      File('/proc/self/cmdline').readAsStringSync().split('\u0000').first !=
          _package) {
    throw StateError('Probe requires the independent runtimeperf process');
  }
}

Future<void> main(List<String> args) async {
  _checkProcess();
  WidgetsFlutterBinding.ensureInitialized();
  await StorageService.init();
  await StorageService.setBool(StorageKeys.welcomeCompleted, true);
  await app.main([...args, '--route=/home/home']);
  final probe = _RuntimeProbe();
  SchedulerBinding.instance.addTimingsCallback(probe.onFrames);
  var running = false;
  developer.registerExtension(_extension, (method, parameters) async {
    try {
      _checkProcess();
      if (running) throw StateError('A probe operation is already running');
      running = true;
      final result = switch (parameters['op']) {
        'seed' => await probe.seed(_count(parameters, 'count', 1000)),
        'open' => await probe.open(_count(parameters, 'count', 1000)),
        'loadMore' => await probe.loadMore(),
        'state' => probe.state(),
        'startCapture' => probe.startCapture(),
        'result' => await probe.result(),
        'stress' => await probe.stress(parameters),
        'streamStart' => await probe.streamStart(parameters),
        'streamStatus' => probe.streamStatus(),
        'streamStop' => await probe.streamStop(),
        'streamOpen' => await probe.streamOpen(parameters['kind']),
        'home' => await probe.home(),
        'cleanup' => await probe.cleanup(),
        _ => throw ArgumentError('Unknown probe operation'),
      };
      return developer.ServiceExtensionResponse.result(jsonEncode(result));
    } catch (error) {
      return developer.ServiceExtensionResponse.error(
        developer.ServiceExtensionResponse.extensionError,
        'Runtime probe failed: ${error.runtimeType}: $error',
      );
    } finally {
      running = false;
    }
  });
}

int _count(Map<String, String> parameters, String key, int fallback) =>
    int.parse(parameters[key] ?? '$fallback');

dynamic _canonical(dynamic value) {
  if (value is Map) {
    final keys = value.keys.map((key) => key.toString()).toList()..sort();
    return {for (final key in keys) key: _canonical(value[key])};
  }
  if (value is List) return value.map(_canonical).toList();
  return value;
}

String _digest(dynamic value) =>
    sha256.convert(utf8.encode(jsonEncode(_canonical(value)))).toString();

ChatMessageModel _historyMessage(int count, int index) {
  final assistant = index.isOdd;
  return ChatMessageModel(
    id: 'runtime-perf-$count-message-$index',
    type: 1,
    user: assistant ? 2 : 1,
    createAt: DateTime.utc(2026, 1, 1).add(Duration(seconds: index)),
    content: {
      'text': assistant
          ? '## 合成回复 $index\n\n'
                '${List.generate(8, (p) => '第 $p 段固定文本。用于观察长对话分页、Markdown 和布局性能。包含 **加粗** 与 `inlineCode`。').join('\n\n')}\n\n'
                '- 固定条目一\n- 固定条目二\n\n```dart\nfinal value = $index;\n```\n'
          : '合成问题 $index：请解释列表、流式更新与布局的关系。',
    },
  );
}

class _RuntimeProbe {
  final _frames = <Map<String, int>>[];
  final _timerGapsUs = <int>[];
  Timer? _timer;
  int _startUs = 0;
  int _lastTickUs = 0;
  int? _openedCount;
  bool _capturing = false;
  Future<void>? _streamTask;
  Completer<void>? _streamCancel;
  Map<String, Object?>? _streamResult;
  String? _streamKind;
  int? _streamConversation;
  int _streamSequence = 0;
  int _streamEmitted = 0;
  String? _streamError;
  String? _streamAgentId;
  bool _streamVisibleVerified = false;

  int get _now => developer.Timeline.now;

  List<Element> _elements() {
    final elements = <Element>[];
    void visit(Element element) {
      if (element.widget case Offstage(offstage: true)) return;
      elements.add(element);
      element.visitChildren(visit);
    }

    WidgetsBinding.instance.rootElement?.visitChildren(visit);
    return elements;
  }

  ({StatefulElement element, ConversationManager page})? _visibleChatOwner() {
    for (final element in _elements()) {
      if (element is StatefulElement &&
          element.widget is ChatPage &&
          ModalRoute.of(element)?.isCurrent == true &&
          element.state is ConversationManager) {
        return (element: element, page: element.state as ConversationManager);
      }
    }
    return null;
  }

  ChatMessageList? _messageList() {
    final owner = _visibleChatOwner();
    if (owner == null) return null;
    final view = WidgetsBinding.instance.platformDispatcher.views.first;
    final viewport = Offset.zero & (view.physicalSize / view.devicePixelRatio);
    ChatMessageList? result;
    void visit(Element element) {
      if (element.widget case Offstage(offstage: true)) return;
      if (element.widget case final ChatMessageList list) {
        final box = element.findRenderObject();
        if (box is RenderBox &&
            box.attached &&
            box.hasSize &&
            (box.localToGlobal(Offset.zero) & box.size).overlaps(viewport)) {
          if (result != null)
            throw StateError('Multiple visible ChatMessageLists');
          result = list;
        }
      }
      element.visitChildren(visit);
    }

    owner.element.visitChildren(visit);
    return result;
  }

  Map<String, Object?> _visibleOwnerState() {
    final owner = _visibleChatOwner();
    if (owner == null) return {'chatPageVisible': false};
    final page = owner.page;
    final id = page.currentConversationId;
    final mode = switch (page.activeConversationModeValue) {
      ConversationMode.agent => kChatRuntimeModeAgent,
      ConversationMode.openclaw => kChatRuntimeModeOpenClaw,
      _ => kChatRuntimeModeNormal,
    };
    final runtime = id == null
        ? null
        : ChatConversationRuntimeCoordinator.instance.runtimeFor(
            conversationId: id,
            mode: mode,
          );
    final list = _messageList();
    return {
      'chatPageVisible': true,
      'routePath': GoRouter.of(owner.element)
          .routerDelegate
          .currentConfiguration
          .uri
          .path,
      'conversationId': id,
      'conversationMode': page.activeConversationModeValue.storageValue,
      'runtimeMode': mode,
      'agentId': page.agentIdForNewConversation,
      'runtimeConversationId': runtime?.conversationId,
      'runtimeMessageCount': runtime?.messages.length,
      'visibleProjectionVerified': _streamVisibleVerified,
      'pageMessageCount': page.messages.length,
      'listMessageCount': list?.messages.length,
      'pageUsesOwningRuntime':
          runtime != null && identical(page.messages, runtime.messages),
      'listUsesOwningRuntime':
          runtime != null &&
          list != null &&
          identical(list.messages, runtime.messages),
      'listAgentId': list?.activeAcpAgentId,
      'listHidesToolCards': list?.hideToolCards,
    };
  }

  Map<String, Object?> _assertVisibleStreamOwner(
    int conversationId, {
    String? callId,
    String? text,
  }) {
    final owner = _visibleOwnerState();
    final list = _messageList();
    if (owner['routePath'] != GoRouterManager.chatRoute ||
        owner['conversationId'] != conversationId ||
        owner['runtimeMode'] != kChatRuntimeModeAgent ||
        owner['agentId'] != _streamAgentId ||
        owner['pageUsesOwningRuntime'] != true ||
        owner['listUsesOwningRuntime'] != true ||
        owner['listAgentId'] != _streamAgentId ||
        (_streamKind == 'tools' && owner['listHidesToolCards'] == true)) {
      throw StateError(
        'Visible ChatPage does not use the fixture owner: ${jsonEncode(owner)}',
      );
    }
    if (callId != null &&
        !list!.messages.any((m) => m.cardData?['toolCallId'] == callId)) {
      throw StateError('Tool update was not projected into the visible list');
    }
    if (text != null &&
        !list!.messages.any((m) => m.user == 2 && m.text == text)) {
      throw StateError('Text update was not projected into the visible list');
    }
    return owner;
  }

  Map<String, Object?> state() {
    final list = _messageList();
    final view = WidgetsBinding.instance.platformDispatcher.views.first;
    return {
      'package': _package,
      'seedCount': _openedCount,
      'loadedMessages': list?.messages.length,
      'hasMore': list?.hasMore,
      'refreshRateHz': view.display.refreshRate,
      'capturing': _capturing,
      'frameCount': _frames.length,
      'visibleOwner': _visibleOwnerState(),
      if (_streamKind != null) 'stream': streamStatus(),
    };
  }

  Future<Map<String, Object?>> seed(int count) async {
    if (count != 1000 && count != 10000) {
      throw ArgumentError('Seed count must be 1000 or 10000');
    }
    final key = '$_seedPrefix$count';
    var id = StorageService.getInt(key);
    final created = id == null;
    final stopwatch = Stopwatch()..start();
    if (id == null) {
      id = await ConversationService.createConversation(
        title: 'Synthetic runtime benchmark: $count messages',
        mode: ConversationMode.chatOnly,
        rethrowOnError: true,
      );
      if (id == null)
        throw StateError('Synthetic conversation creation failed');
      // Journal the id before seeding, so cleanup can recover a partial write.
      await StorageService.setInt(key, id);
    }
    if (StorageService.getBool('$key.complete') != true) {
      for (var offset = 0; offset < count; offset += 250) {
        final batch = List.generate(
          math.min(250, count - offset),
          (index) => _historyMessage(count, offset + index),
        );
        await ConversationHistoryService.saveConversationMessages(
          id,
          batch,
          mode: ConversationMode.chatOnly,
        );
      }
      final now = DateTime.now().millisecondsSinceEpoch;
      if (!await ConversationService.updateConversation(
        ConversationModel(
          id: id,
          mode: ConversationMode.chatOnly,
          title: 'Synthetic runtime benchmark: $count messages',
          messageCount: count,
          status: 1,
          createdAt: now,
          updatedAt: now,
        ),
      )) {
        throw StateError('Synthetic metadata update failed');
      }
      await StorageService.setBool('$key.complete', true);
    }
    final lastPage =
        await ConversationHistoryService.getConversationMessagesPaged(
          id,
          mode: ConversationMode.chatOnly,
          limit: 1,
          offset: count - 1,
        );
    final beyond =
        await ConversationHistoryService.getConversationMessagesPaged(
          id,
          mode: ConversationMode.chatOnly,
          limit: 1,
          offset: count,
        );
    if (lastPage.messages.length != 1 || beyond.messages.isNotEmpty) {
      throw StateError(
        'Synthetic history does not have exactly $count messages',
      );
    }
    return {
      'package': _package,
      'count': count,
      'created': created,
      'seedMs': stopwatch.elapsedMicroseconds / 1000,
      'fixtureVersion': 1,
    };
  }

  Future<Map<String, Object?>> open(int count) async {
    if (count != 1000 && count != 10000) throw ArgumentError('Unknown seed');
    final id = StorageService.getInt('$_seedPrefix$count');
    if (id == null) throw StateError('Run seed first');
    for (var attempt = 0; attempt < 100; attempt++) {
      if (GoRouterManager.rootNavigatorKey.currentContext != null) break;
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    GoRouterManager.go(GoRouterManager.homeRoute);
    await Future<void>.delayed(const Duration(milliseconds: 600));
    GoRouterManager.go(
      GoRouterManager.chatRoute,
      extra: ConversationThreadTarget.existing(
        conversationId: id,
        mode: ConversationMode.chatOnly,
      ),
    );
    _openedCount = count;
    await Future<void>.delayed(const Duration(milliseconds: 1500));
    FocusManager.instance.primaryFocus?.unfocus();
    await SystemChannels.textInput.invokeMethod<void>('TextInput.hide');
    await Future<void>.delayed(const Duration(milliseconds: 500));
    if (_messageList() == null)
      throw StateError('Real ChatMessageList not found');
    return state();
  }

  Future<Map<String, Object?>> loadMore() async {
    final list = _messageList();
    if (list == null || !list.hasMore || list.onLoadMore == null) {
      throw StateError('No visible history page is available');
    }
    final watch = Stopwatch()..start();
    await list.onLoadMore!();
    final loadUs = watch.elapsedMicroseconds;
    await SchedulerBinding.instance.endOfFrame;
    return {
      ...state(),
      'loadUs': loadUs,
      'throughFrameUs': watch.elapsedMicroseconds,
    };
  }

  Map<String, Object?> startCapture() {
    _timer?.cancel();
    _frames.clear();
    _timerGapsUs.clear();
    _startUs = _lastTickUs = _now;
    _capturing = true;
    _timer = Timer.periodic(const Duration(milliseconds: 10), (_) {
      final now = _now;
      if (_capturing) _timerGapsUs.add(now - _lastTickUs);
      _lastTickUs = now;
    });
    return {...state(), 'captureStartUs': _startUs};
  }

  void onFrames(List<FrameTiming> frames) {
    if (!_capturing) return;
    for (final frame in frames) {
      final start = frame.timestampInMicroseconds(FramePhase.buildStart);
      if (start < _startUs) continue;
      _frames.add({
        'startUs': start,
        'buildUs': frame.buildDuration.inMicroseconds,
        'rasterUs': frame.rasterDuration.inMicroseconds,
        'totalUs': frame.totalSpan.inMicroseconds,
      });
    }
  }

  Future<Map<String, Object?>> result() async {
    // Profile FrameTiming callbacks arrive in batches. Keep the capture open
    // for delivery and retain timestamps so the host can trim its window.
    await Future<void>.delayed(const Duration(milliseconds: 1100));
    _capturing = false;
    _timer?.cancel();
    return {
      ...state(),
      'captureStartUs': _startUs,
      'captureEndUs': _now,
      'frames': List.of(_frames),
      'timerGapsUs': List.of(_timerGapsUs),
    };
  }

  Future<Map<String, Object?>> stress(Map<String, String> parameters) async {
    final scenario = parameters['scenario'] ?? 'short';
    if (!const {'short', 'long', 'tools'}.contains(scenario)) {
      throw ArgumentError('Use short, long or tools');
    }
    final count = _count(parameters, 'events', 1000);
    final batchSize = _count(parameters, 'batch', 50);
    final workers = _count(parameters, 'workers', 4);
    final historyRows = _count(parameters, 'historyRows', 0);
    if (count < 1 ||
        count > 10000 ||
        batchSize < 1 ||
        batchSize > 1000 ||
        workers < 1 ||
        workers > 8 ||
        historyRows < 0 ||
        historyRows > 10000) {
      throw ArgumentError(
        'Stress parameters exceed the bounded fixture limits',
      );
    }
    final runtime = ChatConversationRuntimeState(
      conversationId: -170011,
      mode: kChatRuntimeModeAgent,
    );
    const session = 'runtime-perf-session';
    const turn = 'runtime-perf-turn';
    const call = 'runtime-perf-dispatch';
    runtime.activeRunId = 'runtime-perf-run';
    runtime.activeAcpSessionId = session;
    runtime.activeAcpTurnId = turn;
    runtime.isAiResponding = true;
    runtime.messages.addAll(
      List.generate(historyRows, (i) => _historyMessage(historyRows, i)),
    );
    const reducer = AgentEventReducer();
    Map<String, dynamic> event(Map<String, dynamic> update, String id) => {
      'method': 'session/update',
      'eventId': id,
      'turnId': turn,
      'params': {'sessionId': session, 'update': update},
    };
    reducer.reduce(
      runtime: runtime,
      event: event({
        'sessionUpdate': 'tool_call',
        'toolCallId': call,
        'kind': 'other',
        'title': 'subagent_dispatch',
        'status': 'in_progress',
        'rawInput': {
          'tasks': List.generate(
            workers,
            (i) => {'instruction': 'Synthetic worker $i'},
          ),
        },
      }, 'start'),
    );
    // Fixture generation is intentionally outside reduction timing.
    Map<String, dynamic> updateAt(int i) {
      final child = i % workers;
      final text = scenario == 'long'
          ? 'Synthetic cumulative text. ' * ((i ~/ workers) + 1)
          : 'Synthetic progress $i';
      return event({
        'sessionUpdate': 'tool_call_update',
        'toolCallId': call,
        'status': 'in_progress',
        'rawInput': {
          'subagentStatusText': text,
          'subagentEvents': [
            {
              'id': 'runtime-perf-event-$i',
              'seq': i + 1,
              'createdAt': 1700000000000 + i,
              'taskIndex': child,
              'subagentId': 'runtime-perf-child-$child',
              'kind': scenario == 'tools' ? 'tool_progress' : 'message',
              'status': 'running',
              'summary': text,
            },
          ],
        },
      }, 'event-$i');
    }

    final batches = <Map<String, int>>[];
    final wall = Stopwatch()..start();
    var cpuWorkUs = 0;
    final captureWasRunning = _capturing;
    if (!captureWasRunning) startCapture();
    try {
      for (var offset = 0; offset < count; offset += batchSize) {
        final end = math.min(offset + batchSize, count);
        // Hold one batch, rather than O(events squared) cumulative fixtures.
        final updates = List.generate(
          end - offset,
          (i) => updateAt(offset + i),
        );
        final watch = Stopwatch()..start();
        developer.Timeline.startSync(
          'runtimePerf.reduceBatch',
          arguments: {'scenario': scenario, 'first': offset, 'last': end},
        );
        try {
          for (var index = offset; index < end; index++) {
            reducer.reduce(runtime: runtime, event: updates[index - offset]);
          }
        } finally {
          developer.Timeline.finishSync();
        }
        final workUs = watch.elapsedMicroseconds;
        cpuWorkUs += workUs;
        batches.add({'first': offset, 'last': end, 'workUs': workUs});
        await Future<void>.delayed(Duration.zero);
      }
      final wallUs = wall.elapsedMicroseconds;
      reducer.reduce(
        runtime: runtime,
        event: event({
          'sessionUpdate': 'tool_call_update',
          'toolCallId': call,
          'status': 'completed',
          'rawOutput': {
            'toolType': 'context',
            'toolName': 'subagent_dispatch',
            'success': true,
          },
        }, 'completed'),
      );
      reducer.reducePromptResponse(
        runtime: runtime,
        sessionId: session,
        turnId: turn,
        stopReason: 'end_turn',
      );
      final card = runtime.messages
          .singleWhere((message) => message.cardData?['toolCallId'] == call)
          .cardData!;
      final semantic = {
        for (final key in [
          'status',
          'toolType',
          'toolName',
          'subagentStatusText',
          'subagentEvents',
        ])
          key: card[key],
      };
      return {
        'package': _package, 'measurement': 'isolated-production-reducer',
        'scenario': scenario, 'events': count, 'batchSize': batchSize,
        'workers': workers, 'historyRows': historyRows,
        // Sum of elapsed synchronous reducer calls, not sampled CPU time.
        'reductionWorkUs': cpuWorkUs, 'wallUs': wallUs, 'batches': batches,
        'retainedEvents': (card['subagentEvents'] as List).length,
        'semanticSha256': _digest(semantic),
        'terminalStatus': card['status'],
        'stillResponding': runtime.isAiResponding,
        if (!captureWasRunning) 'capture': await result(),
      };
    } finally {
      runtime.dispose();
      if (!captureWasRunning) {
        _capturing = false;
        _timer?.cancel();
      }
    }
  }

  Future<Map<String, Object?>> home() async {
    FocusManager.instance.primaryFocus?.unfocus();
    await SystemChannels.textInput.invokeMethod<void>('TextInput.hide');
    GoRouterManager.go(GoRouterManager.homeRoute);
    _openedCount = null;
    return {'package': _package, 'home': true};
  }

  Future<int> _ensureStreamConversation(String kind) async {
    final catalog = await AgentRuntimeService.listAgents();
    final agent = catalog.agents
        .where((agent) => agent.id == 'xiaowan-acp' && agent.enabled)
        .firstOrNull;
    if (agent == null)
      throw StateError('Built-in Xiaowan is not registered/enabled');
    _streamAgentId = agent.id;
    final key = 'runtime_perf_stream_v1_$kind';
    var id = StorageService.getInt(key);
    if (id == null) {
      id = await ConversationService.createConversation(
        title: 'Synthetic runtime stream: $kind',
        mode: ConversationMode.agent,
        agentId: _streamAgentId,
        rethrowOnError: true,
      );
      if (id == null) throw StateError('Synthetic stream creation failed');
      await StorageService.setInt(key, id);
    }
    return id;
  }

  Future<Map<String, Object?>> streamOpen(String? kind) async {
    kind ??= _streamKind;
    if (kind != 'text' && kind != 'tools') {
      throw ArgumentError('Stream kind must be text or tools');
    }
    final id = await _ensureStreamConversation(kind!);
    for (
      var attempt = 0;
      attempt < 100 && GoRouterManager.rootNavigatorKey.currentContext == null;
      attempt++
    ) {
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    GoRouterManager.go(GoRouterManager.homeRoute);
    await Future<void>.delayed(const Duration(milliseconds: 500));
    GoRouterManager.go(
      GoRouterManager.chatRoute,
      extra: ConversationThreadTarget.existing(
        conversationId: id,
        mode: ConversationMode.agent,
        agentId: _streamAgentId,
      ),
    );
    await Future<void>.delayed(const Duration(milliseconds: 1400));
    FocusManager.instance.primaryFocus?.unfocus();
    await SystemChannels.textInput.invokeMethod<void>('TextInput.hide');
    _openedCount = null;
    // Bootstrap/native session metadata can finish after the route animation.
    // Wait for the actual Page and visible message list to share its owner.
    for (var attempt = 0; attempt < 100; attempt++) {
      final owner = _visibleOwnerState();
      if (owner['conversationId'] == id &&
          owner['listUsesOwningRuntime'] == true)
        break;
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    _assertVisibleStreamOwner(id);
    return state();
  }

  Map<String, Object?> streamStatus() {
    final runtime = _streamConversation == null
        ? null
        : ChatConversationRuntimeCoordinator.instance.runtimeFor(
            conversationId: _streamConversation!,
            mode: kChatRuntimeModeAgent,
          );
    return {
      'kind': _streamKind,
      // Fixture producer status; user-visible Agent state is read from its owner.
      'fixtureRunning': _streamTask != null,
      'emittedEvents': _streamEmitted,
      'runtimeResponding': runtime?.isAiResponding,
      'runtimeMessageCount': runtime?.messages.length,
      'visibleProjectionVerified': _streamVisibleVerified,
      if (_streamError != null) 'fixtureError': _streamError,
      if (_streamResult != null) 'result': _streamResult,
    };
  }

  Future<Map<String, Object?>> streamStart(
    Map<String, String> parameters,
  ) async {
    if (_streamTask != null)
      throw StateError('Stop or await the current stream');
    final kind = parameters['kind'] ?? 'text';
    final events = _count(parameters, 'events', 500);
    final intervalMs = _count(parameters, 'intervalMs', 20);
    final payloadChars = _count(parameters, 'payloadChars', 128);
    final workers = _count(parameters, 'workers', 4);
    if (!const {'text', 'tools'}.contains(kind) ||
        events < 1 ||
        events > 10000 ||
        intervalMs < 1 ||
        intervalMs > 1000 ||
        payloadChars < 1 ||
        payloadChars > 32768 ||
        workers < 1 ||
        workers > 8 ||
        events * payloadChars > 8 * 1024 * 1024) {
      throw ArgumentError('Stream fixture exceeds bounded input limits');
    }
    _streamKind = kind;
    _streamConversation = await _ensureStreamConversation(kind);
    _streamSequence += 1;
    _streamEmitted = 0;
    _streamResult = null;
    _streamError = null;
    _streamVisibleVerified = false;
    _streamCancel = Completer<void>();
    await streamOpen(kind);
    startCapture();
    final id = _streamConversation!;
    final nonce = '${DateTime.now().microsecondsSinceEpoch}-$_streamSequence';
    final run = 'runtime-perf-stream-$nonce';
    final session = 'runtime-perf-stream-session-$nonce';
    // This local ACP v1 fixture has a host prompt reservation, not a provider
    // turn-admission event. Attribute updates to that same reserved turn.
    final turn = run;
    final coordinator = ChatConversationRuntimeCoordinator.instance;
    coordinator.beginAcpTurn(
      taskId: run,
      conversationId: id,
      mode: kChatRuntimeModeAgent,
    );
    if (!coordinator.bindAcpSession(
      taskId: run,
      conversationId: id,
      mode: kChatRuntimeModeAgent,
      sessionId: session,
    )) {
      coordinator.applyAcpPromptResponse(
        taskId: run,
        conversationId: id,
        sessionId: session,
        mode: kChatRuntimeModeAgent,
        stopReason: 'error',
        error: 'Synthetic session reservation failed',
      );
      throw StateError('Synthetic session reservation failed');
    }
    _assertVisibleStreamOwner(id);
    // The fixture owns only its producer. Runtime admission, projection,
    // completion, history and page switching remain production behavior.
    _streamTask =
        _driveStream(
              kind: kind,
              events: events,
              intervalMs: intervalMs,
              payloadChars: payloadChars,
              workers: workers,
              conversationId: id,
              run: run,
              session: session,
              turn: turn,
              cancel: _streamCancel!,
            )
            .catchError((Object error) {
              _streamError = error is StateError
                  ? error.message.toString()
                  : error.runtimeType.toString();
            })
            .whenComplete(() {
              _streamTask = null;
            });
    return streamStatus();
  }

  Future<void> _driveStream({
    required String kind,
    required int events,
    required int intervalMs,
    required int payloadChars,
    required int workers,
    required int conversationId,
    required String run,
    required String session,
    required String turn,
    required Completer<void> cancel,
  }) async {
    final coordinator = ChatConversationRuntimeCoordinator.instance;
    final answer = '$run-answer';
    final call = '$run-dispatch';
    final chunks = StringBuffer();
    final updateTimesUs = <int>[];
    final updateWorkUs = <int>[];
    final startUs = _now;
    final watch = Stopwatch()..start();
    Map<String, Object?>? firstVisibleOwner;
    Object? failure;
    bool cancelled = false;
    void send(Map<String, dynamic> update, String eventId) {
      final result = coordinator.applyAgentEvent(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
        event: {
          'method': 'session/update',
          'agentId': _streamAgentId,
          'eventId': '$run-$eventId',
          'turnId': turn,
          'params': {'sessionId': session, 'update': update},
        },
      );
      if (!result.handled || !result.affectsActiveTurn)
        throw StateError('Synthetic ACP event was not admitted');
    }

    try {
      if (kind == 'tools') {
        send({
          'sessionUpdate': 'tool_call',
          'toolCallId': call,
          'kind': 'other',
          'title': 'subagent_dispatch',
          'status': 'in_progress',
          'rawInput': {
            'tasks': List.generate(
              workers,
              (i) => {'instruction': 'Synthetic worker $i'},
            ),
          },
        }, 'start');
        for (var child = 0; child < workers; child++) {
          send({
            'sessionUpdate': 'tool_call_update',
            'toolCallId': call,
            'status': 'in_progress',
            'rawInput': {
              'subagentEvents': [
                {
                  'id': 'stream-child-start-$child',
                  'seq': child + 1,
                  'createdAt': 1700000000000 + child,
                  'taskIndex': child,
                  'subagentId': 'stream-child-$child',
                  'kind': 'subagent_started',
                  'summary': 'Synthetic worker $child started',
                  'status': 'running',
                },
              ],
            },
          }, 'child-start-$child');
        }
      }
      for (var index = 0; index < events; index++) {
        if (cancel.isCompleted) {
          cancelled = true;
          break;
        }
        final payload =
            ('Synthetic **stream** $index. ' * ((payloadChars ~/ 20) + 2))
                .substring(0, payloadChars);
        updateTimesUs.add(_now);
        final update = kind == 'text'
            ? <String, dynamic>{
                'sessionUpdate': 'agent_message_chunk',
                'messageId': answer,
                'content': {'type': 'text', 'text': '$payload\n'},
              }
            : <String, dynamic>{
                'sessionUpdate': 'tool_call_update',
                'toolCallId': call,
                'status': 'in_progress',
                'rawInput': {
                  'subagentStatusText': payload,
                  'subagentEvents': [
                    {
                      'id': 'stream-event-$index',
                      'seq': workers + index + 1,
                      'createdAt': 1700000000000 + workers + index,
                      'taskIndex': index % workers,
                      'subagentId': 'stream-child-${index % workers}',
                      'kind': 'tool_progress',
                      'summary': payload,
                      'status': 'running',
                    },
                  ],
                },
              };
        final updateWatch = Stopwatch()..start();
        developer.Timeline.startSync(
          'runtimePerf.streamUpdate',
          arguments: {'kind': kind, 'index': index},
        );
        try {
          send(update, 'event-$index');
        } finally {
          developer.Timeline.finishSync();
        }
        updateWorkUs.add(updateWatch.elapsedMicroseconds);
        if (kind == 'text') chunks.write('$payload\n');
        _streamEmitted = index + 1;
        if (index == 0) {
          await SchedulerBinding.instance.endOfFrame;
          firstVisibleOwner = _assertVisibleStreamOwner(
            conversationId,
            callId: kind == 'tools' ? call : null,
            text: kind == 'text' ? chunks.toString() : null,
          );
          _streamVisibleVerified = true;
        }
        if (index + 1 < events) {
          await Future.any<void>([
            Future<void>.delayed(Duration(milliseconds: intervalMs)),
            cancel.future,
          ]);
        }
      }
      if (cancel.isCompleted) cancelled = true;
      if (kind == 'tools' && !cancelled) {
        for (var child = 0; child < workers; child++) {
          send({
            'sessionUpdate': 'tool_call_update',
            'toolCallId': call,
            'status': 'in_progress',
            'rawInput': {
              'subagentEvents': [
                {
                  'id': 'stream-child-complete-$child',
                  'seq': workers + events + child + 1,
                  'createdAt': 1700000000000 + workers + events + child,
                  'taskIndex': child,
                  'subagentId': 'stream-child-$child',
                  'kind': 'subagent_completed',
                  'summary': 'Synthetic worker $child completed',
                  'status': 'completed',
                },
              ],
            },
          }, 'child-complete-$child');
        }
        send({
          'sessionUpdate': 'tool_call_update',
          'toolCallId': call,
          'status': 'completed',
        }, 'completed');
      }
    } catch (error) {
      failure = error;
      rethrow;
    } finally {
      final projectionEndUs = _now;
      final projected = coordinator.applyAcpPromptResponse(
        taskId: run,
        conversationId: conversationId,
        sessionId: session,
        turnId: turn,
        mode: kChatRuntimeModeAgent,
        stopReason: failure != null
            ? 'error'
            : cancelled
            ? 'cancelled'
            : 'end_turn',
        error: failure == null
            ? null
            : 'Synthetic fixture failed: ${failure.runtimeType}',
      );
      await coordinator.flushPendingPersistence(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      )!;
      final expectedText = chunks.toString();
      final message = kind == 'text'
          ? runtime.messages
                .where((m) => m.user == 2 && m.text == expectedText)
                .firstOrNull
          : runtime.messages
                .where((m) => m.cardData?['toolCallId'] == call)
                .firstOrNull;
      final card = message?.cardData;
      final semantic = kind == 'text'
          ? message?.text
          : {
              for (final key in [
                'status',
                'toolType',
                'toolName',
                'subagentStatusText',
                'subagentEvents',
              ])
                key: card?[key],
            };
      final visible = _visibleChatOwner();
      final isFixturePageVisible =
          visible?.page.currentConversationId == conversationId;
      final finalVisibleOwner = isFixturePageVisible
          ? _assertVisibleStreamOwner(
              conversationId,
              callId: kind == 'tools' ? call : null,
              text: kind == 'text' ? expectedText : null,
            )
          : null;
      final valid =
          failure == null &&
          _streamVisibleVerified &&
          message != null &&
          projected.handled;
      _streamResult = {
        'valid': valid,
        'measurement': 'production-coordinator-and-real-chat-page',
        'kind': kind,
        'requestedEvents': events,
        'emittedEvents': _streamEmitted,
        'intervalMs': intervalMs,
        'payloadChars': payloadChars,
        'workers': workers,
        'startUs': startUs,
        'projectionEndUs': projectionEndUs,
        'throughPersistenceUs': _now,
        'wallUs': watch.elapsedMicroseconds,
        'updateTimesUs': updateTimesUs,
        'updateWorkUs': updateWorkUs,
        'cancelled': cancelled,
        'promptResponseHandled': projected.handled,
        'runtimeResponding': runtime.isAiResponding,
        'finalMessageFound': message != null,
        'semanticSha256': _digest(semantic),
        'firstVisibleOwner': firstVisibleOwner,
        'fixturePageVisibleAtEnd': isFixturePageVisible,
        if (finalVisibleOwner != null) 'finalVisibleOwner': finalVisibleOwner,
        if (kind == 'text') 'textMatchesFixture': message?.text == expectedText,
        if (kind == 'tools')
          'retainedEvents': (card?['subagentEvents'] as List?)?.length,
      };
      if (!valid)
        throw StateError(
          'Stream fixture did not project its message into the owning visible page',
        );
    }
  }

  Future<Map<String, Object?>> streamStop() async {
    final task = _streamTask;
    final cancel = _streamCancel;
    if (task != null && cancel != null && !cancel.isCompleted)
      cancel.complete();
    await task;
    return streamStatus();
  }

  Future<Map<String, Object?>> cleanup() async {
    await streamStop();
    await home();
    final removed = <int>[];
    for (final count in [1000, 10000]) {
      final key = '$_seedPrefix$count';
      final id = StorageService.getInt(key);
      if (id == null) continue;
      if (!await ConversationService.deleteConversation(
        id,
        mode: ConversationMode.chatOnly,
      )) {
        throw StateError('Synthetic cleanup failed');
      }
      await StorageService.remove(key);
      await StorageService.remove('$key.complete');
      removed.add(count);
    }
    for (final kind in ['text', 'tools']) {
      final key = 'runtime_perf_stream_v1_$kind';
      final id = StorageService.getInt(key);
      if (id == null) continue;
      if (!await ConversationService.deleteConversation(
        id,
        mode: ConversationMode.agent,
      )) {
        throw StateError('Synthetic stream cleanup failed');
      }
      await StorageService.remove(key);
    }
    return {'package': _package, 'removedSeedCounts': removed};
  }
}
