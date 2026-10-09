import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:ui/services/storage_service.dart';

/// Development cloud connection. The bearer token stays in this app process;
/// the future cloud account service will supply a short-lived tenant token.
class NaCloudConfig {
  const NaCloudConfig({required this.baseUrl, this.token = ''});

  final String baseUrl;
  final String token;
  static const enabled = kDebugMode || bool.fromEnvironment('NA_ENABLED');
  static const _urlKey = 'na_cloud_development_url';
  static NaCloudConfig? _session;

  static NaCloudConfig load() =>
      _session ??
      NaCloudConfig(
        baseUrl:
            StorageService.getString(_urlKey) ??
            const String.fromEnvironment(
              'NA_BASE_URL',
              defaultValue: '',
            ).ifEmpty(
              defaultTargetPlatform == TargetPlatform.android
                  ? 'http://10.0.2.2:8787'
                  : 'http://127.0.0.1:8787',
            ),
      );

  Future<void> save() async {
    validate();
    await StorageService.setString(_urlKey, baseUrl.trim());
    _session = this;
  }

  Uri validate() {
    final uri = Uri.tryParse(baseUrl.trim());
    if (uri == null ||
        !['http', 'https'].contains(uri.scheme) ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment) {
      throw const NaCloudException(
        'Enter a valid HTTP / HTTPS service address.',
        code: 'invalid_address',
      );
    }
    return uri;
  }
}

extension on String {
  String ifEmpty(String fallback) => isEmpty ? fallback : this;
}

class NaCloudException implements Exception {
  const NaCloudException(this.message, {this.statusCode, this.code});
  final String message;
  final int? statusCode;
  final String? code;
  @override
  String toString() => message;
}

String naCloudErrorMessage(Object error, {required bool english}) {
  if (error is NaCloudException) {
    if (error.statusCode == 401) {
      return english
          ? 'Invalid Na token. Update the cloud connection settings.'
          : 'Na token 无效，请更新云端连接配置。';
    }
    if (error.code == 'invalid_address') {
      return english
          ? 'Enter a valid HTTP / HTTPS service address.'
          : '请输入有效的 HTTP / HTTPS 服务地址。';
    }
    if (error.code == 'file_too_large') {
      return english ? 'Files must be smaller than 20 MiB.' : '文件不能超过 20 MiB。';
    }
  }
  if (error is http.ClientException || error is TimeoutException) {
    return english
        ? 'Unable to reach Na. Check the service address and your connection.'
        : '无法连接 Na，请检查服务地址与网络连接。';
  }
  return error.toString();
}

class NaCloudFile {
  const NaCloudFile({
    required this.path,
    required this.size,
    this.modifiedAt = '',
  });
  factory NaCloudFile.fromJson(Map<String, dynamic> value) => NaCloudFile(
    path: value['path'] as String,
    size: (value['size'] as num).toInt(),
    modifiedAt: '${value['modifiedAt'] ?? ''}',
  );
  final String path;
  final int size;
  final String modifiedAt;
  String get name => path.split('/').last;
}

class NaCloudMessage {
  const NaCloudMessage({
    required this.id,
    required this.role,
    required this.text,
    required this.createdAt,
  });
  factory NaCloudMessage.fromJson(Map<String, dynamic> value) => NaCloudMessage(
    id: '${value['id']}',
    role: '${value['role'] ?? 'assistant'}',
    text: '${value['text'] ?? ''}',
    createdAt: '${value['createdAt'] ?? ''}',
  );
  final String id;
  final String role;
  final String text;
  final String createdAt;
}

class NaCloudRun {
  const NaCloudRun({
    required this.id,
    required this.status,
    this.error,
    this.items = const [],
  });
  factory NaCloudRun.fromJson(Map<String, dynamic> value) => NaCloudRun(
    id: '${value['id']}',
    status: '${value['status'] ?? 'queued'}',
    error: value['error']?.toString(),
    items: (value['items'] as List? ?? const [])
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item))
        .toList(),
  );
  final String id;
  final String status;
  final String? error;
  final List<Map<String, dynamic>> items;
  bool get active => ['queued', 'running', 'cancelling'].contains(status);
}

class NaCloudConversation {
  const NaCloudConversation({
    required this.id,
    required this.title,
    this.messages = const [],
    this.activeRun,
    this.latestRun,
  });
  factory NaCloudConversation.fromJson(Map<String, dynamic> value) =>
      NaCloudConversation(
        id: '${value['id']}',
        title: '${value['title'] ?? 'New conversation'}',
        messages: (value['messages'] as List? ?? const [])
            .whereType<Map>()
            .map(
              (item) =>
                  NaCloudMessage.fromJson(Map<String, dynamic>.from(item)),
            )
            .toList(),
        latestRun: value['latestRun'] is Map
            ? NaCloudRun.fromJson(Map<String, dynamic>.from(value['latestRun']))
            : null,
        activeRun: value['activeRun'] is Map
            ? NaCloudRun.fromJson(Map<String, dynamic>.from(value['activeRun']))
            : null,
      );
  final String id;
  final String title;
  final List<NaCloudMessage> messages;
  final NaCloudRun? activeRun;
  final NaCloudRun? latestRun;
}

class NaCloudEvent {
  const NaCloudEvent({
    required this.id,
    required this.type,
    this.conversationId,
    this.runId,
    this.data = const {},
  });
  factory NaCloudEvent.fromJson(Map<String, dynamic> value) => NaCloudEvent(
    id: int.parse('${value['id']}'),
    type: '${value['type']}',
    conversationId: value['conversationId']?.toString(),
    runId: value['runId']?.toString(),
    data: value['data'] is Map
        ? Map<String, dynamic>.from(value['data'])
        : const {},
  );
  final int id;
  final String type;
  final String? conversationId;
  final String? runId;
  final Map<String, dynamic> data;
}

/// REST writes are never automatically replayed. A clientMessageId identifies
/// the same send across an explicit retry; SSE resumes only server events.
class NaCloudService {
  NaCloudService(
    this.config, {
    http.Client? client,
    this.reconnectDelay = const Duration(seconds: 2),
  }) : _client = client ?? http.Client();
  final NaCloudConfig config;
  final http.Client _client;
  final Duration reconnectDelay;
  final connected = ValueNotifier<bool>(false);
  http.Client? _eventClient;
  Timer? _reconnectTimer;
  Completer<void>? _reconnectWait;
  bool _closed = false;
  int _cursor = 0;

  Uri _uri(String path, [Map<String, String>? query]) {
    final base = config.validate();
    final prefix = base.path.replaceFirst(RegExp(r'/$'), '');
    return base.replace(path: '$prefix$path', queryParameters: query);
  }

  Map<String, String> get _headers => {
    'Accept': 'application/json',
    'Content-Type': 'application/json',
    if (config.token.trim().isNotEmpty)
      'Authorization': 'Bearer ${config.token.trim()}',
  };

  Future<dynamic> _request(
    String method,
    String path, [
    Map<String, dynamic>? body,
  ]) async {
    final request = http.Request(method, _uri(path))..headers.addAll(_headers);
    if (body != null) request.body = jsonEncode(body);
    final response = await _client
        .send(request)
        .then(http.Response.fromStream)
        .timeout(const Duration(seconds: 20));
    dynamic value;
    try {
      value = jsonDecode(response.body);
    } catch (_) {}
    if (response.statusCode < 200 || response.statusCode >= 300) {
      final serverError = value is Map ? value['error'] : null;
      throw NaCloudException(
        response.statusCode == 401
            ? 'Invalid Na token. Update the cloud connection settings.'
            : serverError?.toString() ??
                  'Na request failed (${response.statusCode})',
        statusCode: response.statusCode,
      );
    }
    return value;
  }

  Future<Map<String, dynamic>> status() async =>
      Map<String, dynamic>.from(await _request('GET', '/api/status') as Map);
  Future<List<NaCloudConversation>> conversations() async {
    final value = await _request('GET', '/api/conversations');
    final items = value is List ? value : value['conversations'] as List;
    return items
        .whereType<Map>()
        .map(
          (item) =>
              NaCloudConversation.fromJson(Map<String, dynamic>.from(item)),
        )
        .toList();
  }

  Future<List<NaCloudFile>> files() async {
    final value = await _request('GET', '/api/files');
    return (value['files'] as List)
        .whereType<Map>()
        .map((item) => NaCloudFile.fromJson(Map<String, dynamic>.from(item)))
        .toList();
  }

  Future<Uint8List> fileContent(String path) async {
    final request = http.Request(
      'GET',
      _uri('/api/files/content', {'path': path}),
    )..headers.addAll(_headers);
    final response = await _client
        .send(request)
        .timeout(const Duration(seconds: 20));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw NaCloudException(
        'Na file download failed (${response.statusCode})',
        statusCode: response.statusCode,
      );
    }
    const limit = 20 * 1024 * 1024;
    final bytes = BytesBuilder(copy: false);
    await for (final chunk in response.stream.timeout(
      const Duration(seconds: 20),
    )) {
      if (bytes.length + chunk.length > limit) {
        throw const NaCloudException(
          'File exceeds 20 MiB',
          code: 'file_too_large',
        );
      }
      bytes.add(chunk);
    }
    return bytes.takeBytes();
  }

  Future<NaCloudConversation> createConversation() async {
    final value = await _request('POST', '/api/conversations', {});
    return NaCloudConversation.fromJson(
      Map<String, dynamic>.from(
        value['conversation'] is Map ? value['conversation'] : value,
      ),
    );
  }

  Future<NaCloudConversation> conversation(String id) async {
    final value = await _request(
      'GET',
      '/api/conversations/${Uri.encodeComponent(id)}',
    );
    final snapshot = Map<String, dynamic>.from(
      value['conversation'] is Map ? value['conversation'] : value,
    );
    final runs = (value['runs'] as List? ?? const []).whereType<Map>().toList();
    if (runs.isNotEmpty) snapshot['latestRun'] = runs.last;
    return NaCloudConversation.fromJson(snapshot);
  }

  Future<String> send(
    String conversationId,
    String text, {
    required String clientMessageId,
  }) async {
    final value = await _request(
      'POST',
      '/api/conversations/${Uri.encodeComponent(conversationId)}/messages',
      {'text': text, 'clientMessageId': clientMessageId},
    );
    return '${value['runId']}';
  }

  Future<NaCloudRun> run(String id) async {
    final value = await _request('GET', '/api/runs/${Uri.encodeComponent(id)}');
    return NaCloudRun.fromJson(
      Map<String, dynamic>.from(value['run'] is Map ? value['run'] : value),
    );
  }

  Future<void> cancel(String id) => _request(
    'POST',
    '/api/runs/${Uri.encodeComponent(id)}/cancel',
    {},
  ).then((_) {});

  static String newMessageId() {
    final random = Random.secure();
    return 'omni-${DateTime.now().microsecondsSinceEpoch}-'
        '${List.generate(12, (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0')).join()}';
  }

  Stream<NaCloudEvent> events() async* {
    var failures = 0;
    while (!_closed) {
      _eventClient = http.Client();
      try {
        final request = http.Request(
          'GET',
          _uri('/api/events', {'after': '$_cursor'}),
        )..headers.addAll({..._headers, 'Accept': 'text/event-stream'});
        final response = await _eventClient!
            .send(request)
            .timeout(const Duration(seconds: 20));
        if (response.statusCode != 200) {
          throw NaCloudException(
            'Na event stream failed',
            statusCode: response.statusCode,
          );
        }
        if (_closed) return;
        connected.value = true;
        failures = 0;
        await for (final event in decodeEvents(
          response.stream.timeout(const Duration(seconds: 45)),
        )) {
          if (_closed) return;
          if (event.id <= _cursor) continue;
          _cursor = event.id;
          yield event;
        }
      } catch (_) {
        // Reconnect only the read stream. The server owns runs and messages.
        failures++;
      } finally {
        _eventClient?.close();
        if (!_closed) connected.value = false;
      }
      if (_closed) return;
      _reconnectWait = Completer<void>();
      _reconnectTimer = Timer(reconnectDelay * (1 << failures.clamp(0, 4)), () {
        if (!(_reconnectWait?.isCompleted ?? true)) _reconnectWait!.complete();
      });
      await _reconnectWait!.future;
    }
  }

  static Stream<NaCloudEvent> decodeEvents(Stream<List<int>> bytes) async* {
    final data = <String>[];
    await for (final line
        in bytes.transform(utf8.decoder).transform(const LineSplitter())) {
      if (line.isEmpty) {
        if (data.isNotEmpty) {
          final value = jsonDecode(data.join('\n'));
          data.clear();
          if (value is Map) {
            yield NaCloudEvent.fromJson(Map<String, dynamic>.from(value));
          }
        }
      } else if (line.startsWith('data:')) {
        data.add(line.substring(5).replaceFirst(RegExp(r'^ '), ''));
      }
    }
  }

  void close() {
    if (_closed) return;
    _closed = true;
    _eventClient?.close();
    _client.close();
    _reconnectTimer?.cancel();
    if (!(_reconnectWait?.isCompleted ?? true)) _reconnectWait!.complete();
    connected.dispose();
  }
}
