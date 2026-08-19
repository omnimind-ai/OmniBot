import 'dart:async';
import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/conversation_service.dart';

typedef VibeAppEventSink = FutureOr<void> Function(Map<String, dynamic> event);

abstract interface class VibeAppAgentGateway {
  Stream<Map<String, dynamic>> get runtimeEvents;

  Future<Map<String, dynamic>> ensureAcpSession({
    required int conversationId,
  });

  Future<Map<String, dynamic>> promptAcpSession({
    required int conversationId,
    required String sessionId,
    required String text,
    required String reasoningEffort,
  });

  Future<Map<String, dynamic>> cancelAcpPrompt({
    required int conversationId,
    required String sessionId,
    String? promptId,
  });

  Future<List<ConversationModel>> getConversations();

  Future<int?> createConversation({required String title, String? summary});

}

class XiaowanVibeAppAgentGateway implements VibeAppAgentGateway {
  const XiaowanVibeAppAgentGateway();

  @override
  Stream<Map<String, dynamic>> get runtimeEvents => AgentRuntimeService.events;

  @override
  Future<Map<String, dynamic>> ensureAcpSession({
    required int conversationId,
  }) async {
    await AgentRuntimeService.selectAgent('xiaowan-acp');
    return AgentRuntimeService.newSession(
      conversationId: conversationId,
      conversationMode: ConversationMode.normal.storageValue,
    );
  }

  @override
  Future<Map<String, dynamic>> promptAcpSession({
    required int conversationId,
    required String sessionId,
    required String text,
    required String reasoningEffort,
  }) {
    return AgentRuntimeService.promptSession(
      conversationId: conversationId,
      sessionId: sessionId,
      text: text,
      effort: reasoningEffort,
      conversationMode: ConversationMode.normal.storageValue,
    );
  }

  @override
  Future<Map<String, dynamic>> cancelAcpPrompt({
    required int conversationId,
    required String sessionId,
    String? promptId,
  }) {
    return AgentRuntimeService.cancelPrompt(
      conversationId: conversationId,
      sessionId: sessionId,
      promptId: promptId,
    );
  }

  @override
  Future<List<ConversationModel>> getConversations() {
    return ConversationService.getAllConversations(includeArchived: true);
  }

  @override
  Future<int?> createConversation({required String title, String? summary}) {
    return ConversationService.createConversation(
      title: title,
      summary: summary,
      mode: ConversationMode.normal,
    );
  }

}

class VibeAppAgentBridge {
  VibeAppAgentBridge({
    required this.pluginId,
    required this.appTitle,
    required this.onEvent,
    VibeAppAgentGateway gateway = const XiaowanVibeAppAgentGateway(),
    String Function()? taskIdFactory,
  }) : _gateway = gateway,
       _taskIdFactory = taskIdFactory ?? _defaultTaskId;

  static const String _conversationKeyPrefix = 'vibe_app_conversation_id.';

  final String pluginId;
  final String appTitle;
  final VibeAppEventSink onEvent;
  final VibeAppAgentGateway _gateway;
  final String Function() _taskIdFactory;
  final Set<String> _activeRunIds = <String>{};
  final Set<String> _workingRunIds = <String>{};
  StreamSubscription<Map<String, dynamic>>? _runtimeSubscription;
  String? _acpSessionId;
  String? _acpPromptId;
  String _acpTextSnapshot = '';
  int _acpEventSequence = 0;

  int? _conversationId;
  bool _initialized = false;
  bool _disposed = false;

  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;
    _runtimeSubscription = _gateway.runtimeEvents.listen(
      _handleAcpRuntimeEvent,
    );
    await _restoreConversation();
  }

  Future<Map<String, dynamic>> send(Map<String, dynamic> params) async {
    _ensureAvailable();
    final text = (params['text'] ?? '').toString().trim();
    if (text.isEmpty) {
      throw const FormatException('app.send requires non-empty text');
    }
    final conversationId = await _ensureConversation();
    final reasoningEffort = _reasoningEffort(params['reasoningEffort']);
    final runId = _taskIdFactory();
    _activeRunIds.add(runId);
    await _emit(<String, dynamic>{
      'type': 'started',
      'runId': runId,
      'conversationId': conversationId,
      'reasoningEffort': reasoningEffort,
    });
    final session = await _gateway.ensureAcpSession(
      conversationId: conversationId,
    );
    _acpSessionId = session['sessionId']?.toString().trim().isNotEmpty == true
        ? session['sessionId'].toString().trim()
        : session['threadId']?.toString().trim();
    if ((_acpSessionId ?? '').isEmpty) {
      throw StateError('ACP did not return a session id');
    }
    _acpTextSnapshot = '';
    _acpEventSequence = 0;
    final prompt = await _gateway.promptAcpSession(
      conversationId: conversationId,
      sessionId: _acpSessionId!,
      text: _buildUserMessage(text, params['context']),
      reasoningEffort: reasoningEffort,
    );
    _acpPromptId = prompt['promptId']?.toString().trim().isNotEmpty == true
        ? prompt['promptId'].toString().trim()
        : prompt['turnId']?.toString().trim();
    return <String, dynamic>{
      'accepted': true,
      'runId': runId,
      'conversationId': conversationId,
      'sessionId': _acpSessionId,
      if (_acpPromptId != null) 'promptId': _acpPromptId,
    };
  }

  Future<Map<String, dynamic>> cancel(Map<String, dynamic> params) async {
    _ensureAvailable();
    final runId = (params['runId'] ?? '').toString().trim();
    if (runId.isEmpty) {
      throw const FormatException('app.cancel requires runId');
    }
    if (!_activeRunIds.contains(runId)) {
      return <String, dynamic>{'cancelled': false, 'runId': runId};
    }
    final response = await _gateway.cancelAcpPrompt(
      conversationId: _conversationId!,
      sessionId: _acpSessionId ?? '',
      promptId: _acpPromptId,
    );
    final cancelled = response['status'] == 'cancelled' || response['ok'] == true;
    if (cancelled) {
      _activeRunIds.remove(runId);
      _workingRunIds.remove(runId);
      await _emit(<String, dynamic>{
        'type': 'cancelled',
        'runId': runId,
        if (_conversationId != null) 'conversationId': _conversationId,
      });
    }
    return <String, dynamic>{'cancelled': cancelled, 'runId': runId};
  }

  Future<Map<String, dynamic>> getState() async {
    _ensureAvailable();
    return <String, dynamic>{
      'pluginId': pluginId,
      'conversationId': _conversationId,
      'activeRunIds': _activeRunIds.toList(growable: false),
      'running': _activeRunIds.isNotEmpty,
    };
  }

  void dispose() {
    if (_disposed) return;
    _disposed = true;
    if (_initialized) {
      _runtimeSubscription?.cancel();
      _runtimeSubscription = null;
    }
  }

  Future<void> _restoreConversation() async {
    final preferences = await SharedPreferences.getInstance();
    final savedId = preferences.getInt('$_conversationKeyPrefix$pluginId');
    if (savedId == null || savedId <= 0) return;
    final conversations = await _gateway.getConversations();
    final exists = conversations.any(
      (conversation) =>
          conversation.id == savedId &&
          conversation.mode == ConversationMode.normal &&
          !conversation.isArchived,
    );
    if (exists) {
      _conversationId = savedId;
    } else {
      await preferences.remove('$_conversationKeyPrefix$pluginId');
    }
  }

  Future<int> _ensureConversation() async {
    final existing = _conversationId;
    if (existing != null && existing > 0) return existing;
    final created = await _gateway.createConversation(
      title: appTitle,
      summary: 'Vibe App conversation for $pluginId',
    );
    if (created == null || created <= 0) {
      throw StateError('Unable to create a Xiaowan conversation');
    }
    _conversationId = created;
    final preferences = await SharedPreferences.getInstance();
    await preferences.setInt('$_conversationKeyPrefix$pluginId', created);
    return created;
  }

  String _buildUserMessage(String text, Object? context) {
    final encodedContext = context == null ? '{}' : _encodeContext(context);
    return '''
[Vibe App request]
App: $appTitle
Plugin ID: $pluginId
Page context: $encodedContext

Act as this app's Xiaowan backend. Use the installed plugin Skill and its registered Tools when they apply. Use tools for factual reads and business writes; do not fabricate stored or external data. Return concise user-facing content suitable for rendering inside the app.

User request: $text
'''
        .trim();
  }

  String _encodeContext(Object context) {
    try {
      return jsonEncode(context);
    } catch (_) {
      return jsonEncode(context.toString());
    }
  }

  void _handleAcpRuntimeEvent(Map<String, dynamic> event) {
    if (_disposed || _activeRunIds.isEmpty) return;
    final conversationId = _asInt(event['conversationId']);
    if (conversationId == null || conversationId != _conversationId) return;
    final presentation = _asMap(event['presentation']);
    final presentationKind = presentation?['kind']?.toString().trim();
    if (presentationKind == 'turn_completed' ||
        presentationKind == 'turn_failed') {
      final runId = _activeRunIds.first;
      final isFailure = presentationKind == 'turn_failed';
      unawaited(
        _emit(<String, dynamic>{
          'type': isFailure ? 'error' : 'completed',
          'runId': runId,
          'conversationId': conversationId,
          if (isFailure) 'error': presentation?['error'] ?? 'Agent failed',
        }),
      );
      _activeRunIds.remove(runId);
      _workingRunIds.remove(runId);
      return;
    }
    final params = _asMap(event['params']);
    final update = _asMap(params?['update']);
    final kind = update?['sessionUpdate']?.toString().trim();
    if (kind == null || kind.isEmpty) return;
    final runId = _activeRunIds.first;
    _acpEventSequence += 1;
    final createdAt = DateTime.now().millisecondsSinceEpoch;
    if (kind == 'agent_thought_chunk') {
      if (_workingRunIds.add(runId)) {
        unawaited(
          _emit(<String, dynamic>{
            'type': 'working',
            'runId': runId,
            'conversationId': conversationId,
            'createdAt': createdAt,
            'stage': 'analyzing',
            'label': '小万正在分析…',
          }),
        );
      }
      return;
    }
    if (kind == 'agent_message_chunk') {
      final text = _extractText(update?['content']);
      if (text.isEmpty) return;
      _acpTextSnapshot += text;
      unawaited(
        _emit(<String, dynamic>{
          'type': 'text_snapshot',
          'runId': runId,
          'conversationId': conversationId,
          'seq': _acpEventSequence,
          'createdAt': createdAt,
          'text': _acpTextSnapshot,
          'isFinal': false,
        }),
      );
      return;
    }
    if (kind == 'tool_call' || kind == 'tool_call_update') {
      final status = update?['status']?.toString().trim().toLowerCase();
      final type = kind == 'tool_call'
          ? 'tool_started'
          : (status == 'completed' ||
                status == 'failed' ||
                status == 'cancelled')
          ? 'tool_completed'
          : 'tool_progress';
      unawaited(
        _emit(<String, dynamic>{
          ...?update,
          'type': type,
          'runId': runId,
          'conversationId': conversationId,
          'seq': _acpEventSequence,
          'createdAt': createdAt,
        }),
      );
    }
  }

  static Map<String, dynamic>? _asMap(dynamic value) {
    if (value is! Map) return null;
    return value.map((key, item) => MapEntry(key.toString(), item));
  }

  static int? _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

  static String _extractText(dynamic value) {
    if (value is String) return value;
    final map = _asMap(value);
    if (map != null) {
      return (map['text'] ?? map['content'] ?? '').toString();
    }
    return '';
  }

  Future<void> _emit(Map<String, dynamic> event) async {
    if (_disposed) return;
    await onEvent(event);
  }

  void _ensureAvailable() {
    if (_disposed) {
      throw StateError('Vibe App bridge is disposed');
    }
    if (!_initialized) {
      throw StateError('Vibe App bridge is not initialized');
    }
  }

  String _reasoningEffort(Object? raw) {
    final value = raw?.toString().trim().toLowerCase() ?? 'none';
    if (value == 'none' || value == 'low' || value == 'medium') return value;
    throw const FormatException(
      'app.send reasoningEffort must be none, low, or medium',
    );
  }

  static String _defaultTaskId() {
    return 'vibe-${DateTime.now().microsecondsSinceEpoch}';
  }
}
