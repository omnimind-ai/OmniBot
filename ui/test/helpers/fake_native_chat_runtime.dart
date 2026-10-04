import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';

/// Test double of the native chat runtime owner's command surface
/// (`cn.com.omnimind.bot/ChatRuntime`).
///
/// It keeps just enough state for widget tests to observe what the UI asked
/// the owner to do: task bindings and the in-flight flag, plus message
/// storage. It deliberately does NOT reduce ACP events; a test injects the
/// projection the native reducer would have published with [project].
class FakeNativeChatRuntime {
  FakeNativeChatRuntime._();

  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/ChatRuntime',
  );

  final List<MethodCall> calls = <MethodCall>[];
  final Map<String, _FakeRuntime> _runtimes = <String, _FakeRuntime>{};
  int _revision = 0;

  static FakeNativeChatRuntime install() {
    final fake = FakeNativeChatRuntime._();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, fake._handle);
    return fake;
  }

  void uninstall() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
  }

  List<MethodCall> callsTo(String method) =>
      calls.where((call) => call.method == method).toList();

  /// Publishes what the native reducer would have projected for one event:
  /// upserted messages (newest first) and an outcome for attached surfaces.
  void project({
    required int conversationId,
    required String mode,
    List<ChatMessageModel> upsert = const <ChatMessageModel>[],
    Map<String, dynamic> event = const <String, dynamic>{},
  }) {
    final runtime = _ensure(conversationId, mode);
    for (final message in upsert) {
      final index = runtime.messages.indexWhere((m) => m['id'] == message.id);
      final json = chatMessageToChannel(message);
      if (index < 0) {
        runtime.messages.insert(0, json);
      } else {
        runtime.messages[index] = json;
      }
    }
    final coordinator = ChatConversationRuntimeCoordinator.instance;
    coordinator.debugHandleNativeEvent(_batch(runtime));
    coordinator.debugHandleNativeEvent(<String, dynamic>{
      'type': 'outcome',
      'event': event,
      'conversationId': conversationId,
      'mode': mode,
      'result': <String, dynamic>{'handled': true},
    });
  }

  _FakeRuntime _ensure(int conversationId, String mode) =>
      _runtimes.putIfAbsent(
        '$mode:$conversationId',
        () => _FakeRuntime(conversationId, mode),
      );

  Future<dynamic> _handle(MethodCall call) async {
    calls.add(call);
    final args = Map<String, dynamic>.from(
      (call.arguments as Map?) ?? const <String, dynamic>{},
    );
    final conversationId = args['conversationId'] as int?;
    final mode = args['mode']?.toString();
    final runtime = conversationId == null || mode == null
        ? null
        : _ensure(conversationId, mode);
    final taskId = args['taskId']?.toString();
    Object? result;
    switch (call.method) {
      case 'flushAllPendingPersistence':
      case 'flushPendingPersistence':
        return null;
      case 'ensureRuntime':
      case 'ensureEphemeralRuntime':
        if (runtime!.messages.isEmpty && args['initialMessages'] is List) {
          runtime.messages.addAll(_maps(args['initialMessages']));
        }
      case 'registerTask':
        runtime!.bind(taskId!);
      case 'beginAcpTurn':
        runtime!
          ..bind(taskId!)
          ..isAiResponding = true
          ..lastAgentTurnId = taskId;
      case 'bindAcpSession':
        result = runtime!.boundTaskIds.contains(taskId);
      case 'unregisterTask':
        for (final candidate in _runtimes.values) {
          if (candidate.boundTaskIds.contains(taskId)) candidate.release(taskId!);
        }
      case 'applyAcpPromptResponse':
        final owned = runtime!.boundTaskIds.contains(taskId);
        if (owned) runtime.release(taskId!);
        result = <String, dynamic>{'handled': owned};
      case 'clearConversationRuntimeSession':
        runtime!
          ..boundTaskIds.clear()
          ..activeRunId = null
          ..lastAgentTurnId = null
          ..isAiResponding = false;
      case 'discardConversationRuntime':
        _runtimes.remove('$mode:$conversationId');
        return <String, dynamic>{
          'result': null,
          'sync': <dynamic>[
            <String, dynamic>{
              'type': 'sync',
              'snapshots': <dynamic>[],
              'removed': <String, int>{'$mode:$conversationId': ++_revision},
            },
          ],
        };
      case 'insertRuntimeMessage':
        _upsert(runtime!, _maps(<dynamic>[args['message']]), atStart: true);
      case 'appendRuntimeMessages':
        _upsert(runtime!, _maps(args['messages']), atStart: false);
      case 'replaceRuntimeMessage':
        final index = runtime!.messages.indexWhere(
          (m) => m['id'] == args['messageId'],
        );
        if (index >= 0) runtime.messages[index] = _maps(<dynamic>[args['message']]).single;
        result = index >= 0;
      case 'removeRuntimeMessages':
        final ids = (args['messageIds'] as List).toSet();
        runtime!.messages.removeWhere((m) => ids.contains(m['id']));
      case 'replaceRuntimeMessages':
        runtime!.messages
          ..clear()
          ..addAll(_maps(args['messages']));
      case 'replaceConversationSnapshot':
      case 'persistConversationMessageSnapshot':
        // Merge by id like the native owner does while a turn is live.
        final incoming = _maps(args['messages']);
        final byId = <Object?, Map<String, dynamic>>{
          for (final message in incoming) message['id']: message,
        };
        final merged = runtime!.messages
            .map((m) => byId.remove(m['id']) ?? m)
            .toList();
        merged.addAll(byId.values);
        runtime.messages
          ..clear()
          ..addAll(merged);
      case 'updateRuntimePresentation':
        if (args['isAiResponding'] is bool) {
          runtime!.isAiResponding = args['isAiResponding'] as bool;
        }
      default:
        break;
    }
    return <String, dynamic>{
      'result': result,
      'sync': <dynamic>[if (runtime != null) _batch(runtime)],
    };
  }

  void _upsert(
    _FakeRuntime runtime,
    List<Map<String, dynamic>> messages, {
    required bool atStart,
  }) {
    for (final message in messages) {
      final index = runtime.messages.indexWhere((m) => m['id'] == message['id']);
      if (index >= 0) {
        runtime.messages[index] = message;
      } else if (atStart) {
        runtime.messages.insert(0, message);
      } else {
        runtime.messages.add(message);
      }
    }
  }

  Map<String, dynamic> _batch(_FakeRuntime runtime) => <String, dynamic>{
    'type': 'sync',
    'snapshots': <dynamic>[runtime.toSnapshot(++_revision)],
    'removed': <String, int>{},
  };

  static List<Map<String, dynamic>> _maps(dynamic value) => value is List
      ? value
            .whereType<Map>()
            .map((item) => Map<String, dynamic>.from(item))
            .toList()
      : <Map<String, dynamic>>[];
}

class _FakeRuntime {
  _FakeRuntime(this.conversationId, this.mode);

  final int conversationId;
  final String mode;
  final List<Map<String, dynamic>> messages = <Map<String, dynamic>>[];
  final Set<String> boundTaskIds = <String>{};
  bool isAiResponding = false;
  String? activeRunId;
  String? lastAgentTurnId;

  void bind(String taskId) {
    boundTaskIds.add(taskId);
    activeRunId = taskId;
  }

  void release(String taskId) {
    boundTaskIds.remove(taskId);
    if (activeRunId == taskId) activeRunId = null;
    if (lastAgentTurnId == taskId) lastAgentTurnId = null;
    if (activeRunId == null && lastAgentTurnId == null) isAiResponding = false;
  }

  Map<String, dynamic> toSnapshot(int revision) => <String, dynamic>{
    'conversationId': conversationId,
    'mode': mode,
    'revision': revision,
    'messageIds': messages.map((m) => m['id'].toString()).toList(),
    'changedMessages': messages,
    'isAiResponding': isAiResponding,
    'activeRunId': activeRunId,
    'lastAgentTurnId': lastAgentTurnId,
    'boundTaskIds': boundTaskIds.toList(),
    'isInputAreaVisible': true,
    'currentThinkingStage': 1,
    'chatIslandDisplayLayer': 'mode',
  };
}
