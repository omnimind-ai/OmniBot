import 'dart:async';

import 'package:flutter/services.dart';

class SpeechTranscriptionResult {
  const SpeechTranscriptionResult({
    required this.text,
    required this.model,
    required this.route,
    required this.durationMs,
  });

  final String text;
  final String model;
  final String route;
  final int durationMs;

  factory SpeechTranscriptionResult.fromMap(Map<dynamic, dynamic> raw) {
    return SpeechTranscriptionResult(
      text: (raw['text'] ?? '').toString().trim(),
      model: (raw['model'] ?? '').toString(),
      route: (raw['route'] ?? '').toString(),
      durationMs: _asInt(raw['durationMs']),
    );
  }
}

class SpeechTranscriptionEvent {
  const SpeechTranscriptionEvent({
    required this.state,
    this.result,
    this.code,
    this.message,
  });

  final String state;
  final SpeechTranscriptionResult? result;
  final String? code;
  final String? message;

  factory SpeechTranscriptionEvent.fromMap(Map<dynamic, dynamic> raw) {
    final text = (raw['text'] ?? '').toString().trim();
    return SpeechTranscriptionEvent(
      state: (raw['state'] ?? '').toString(),
      result: text.isEmpty ? null : SpeechTranscriptionResult.fromMap(raw),
      code: raw['code']?.toString(),
      message: raw['message']?.toString(),
    );
  }
}

class SpeechTranscriptionService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/SpeechTranscription',
  );
  static final StreamController<SpeechTranscriptionEvent> _events =
      StreamController<SpeechTranscriptionEvent>.broadcast();
  static bool _initialized = false;

  static Stream<SpeechTranscriptionEvent> get events {
    _ensureInitialized();
    return _events.stream;
  }

  static Future<void> startRecording({String? language}) async {
    _ensureInitialized();
    await _channel.invokeMethod<dynamic>('startRecording', <String, dynamic>{
      if ((language ?? '').isNotEmpty) 'language': language,
    });
  }

  static Future<SpeechTranscriptionResult> stopAndTranscribe({
    String? language,
  }) async {
    _ensureInitialized();
    final raw = await _channel.invokeMethod<dynamic>(
      'stopAndTranscribe',
      <String, dynamic>{if ((language ?? '').isNotEmpty) 'language': language},
    );
    return SpeechTranscriptionResult.fromMap(
      Map<dynamic, dynamic>.from(raw as Map),
    );
  }

  static Future<SpeechTranscriptionResult> transcribeFile({
    required String path,
    String? mimeType,
    String? language,
  }) async {
    _ensureInitialized();
    final raw = await _channel
        .invokeMethod<dynamic>('transcribeFile', <String, dynamic>{
          'path': path,
          if ((mimeType ?? '').isNotEmpty) 'mimeType': mimeType,
          if ((language ?? '').isNotEmpty) 'language': language,
        });
    return SpeechTranscriptionResult.fromMap(
      Map<dynamic, dynamic>.from(raw as Map),
    );
  }

  static Future<void> cancel() async {
    _ensureInitialized();
    await _channel.invokeMethod<dynamic>('cancel');
  }

  static void _ensureInitialized() {
    if (_initialized) return;
    _initialized = true;
    _channel.setMethodCallHandler((call) async {
      if (call.method != 'onSpeechTranscriptionEvent') return null;
      final raw = Map<dynamic, dynamic>.from(
        (call.arguments as Map?) ?? const <dynamic, dynamic>{},
      );
      _events.add(SpeechTranscriptionEvent.fromMap(raw));
      return null;
    });
  }
}

int _asInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '') ?? 0;
}
