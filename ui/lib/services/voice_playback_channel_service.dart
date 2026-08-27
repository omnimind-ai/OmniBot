import 'dart:async';

import 'package:flutter/services.dart';

const MethodChannel _voicePlaybackChannel = MethodChannel(
  'cn.com.omnimind.bot/VoicePlayback',
);
const EventChannel _voicePlaybackEvents = EventChannel(
  'cn.com.omnimind.bot/VoicePlaybackEvents',
);

enum VoicePlaybackStatus {
  idle,
  synthesizing,
  playing,
  paused,
  completed,
  error;

  static VoicePlaybackStatus fromWire(String? value) {
    final normalized = value?.trim().toLowerCase() ?? '';
    return VoicePlaybackStatus.values.firstWhere(
      (status) => status.name == normalized,
      orElse: () => VoicePlaybackStatus.idle,
    );
  }
}

class VoicePlaybackEvent {
  final String messageId;
  final VoicePlaybackStatus status;
  final String error;
  final bool canReplay;

  const VoicePlaybackEvent({
    required this.messageId,
    required this.status,
    this.error = '',
    this.canReplay = false,
  });

  factory VoicePlaybackEvent.fromMap(Map<dynamic, dynamic>? map) {
    return VoicePlaybackEvent(
      messageId: (map?['messageId'] ?? '').toString(),
      status: VoicePlaybackStatus.fromWire(map?['status']?.toString()),
      error: (map?['error'] ?? '').toString(),
      canReplay: map?['canReplay'] == true,
    );
  }
}

class VoicePlaybackChannelService {
  static Stream<VoicePlaybackEvent>? _events;

  static Stream<VoicePlaybackEvent> get events {
    return _events ??= _voicePlaybackEvents
        .receiveBroadcastStream()
        .map(
          (event) => VoicePlaybackEvent.fromMap(
            event is Map ? event : const <String, dynamic>{},
          ),
        )
        .asBroadcastStream();
  }

  static Future<bool> speakText({
    required String messageId,
    required String text,
    bool enqueue = false,
    bool preferStreaming = true,
  }) async {
    try {
      final result = await _voicePlaybackChannel
          .invokeMethod<bool>('speakText', {
            'messageId': messageId,
            'text': text,
            'enqueue': enqueue,
            'preferStreaming': preferStreaming,
          });
      return result == true;
    } on PlatformException {
      return false;
    }
  }

  static Future<bool> replayText({
    required String messageId,
    required String text,
  }) async {
    try {
      final result = await _voicePlaybackChannel.invokeMethod<bool>(
        'replayText',
        {'messageId': messageId, 'text': text},
      );
      return result == true;
    } on PlatformException {
      return false;
    }
  }

  static Future<bool> pausePlayback(String messageId) async {
    try {
      final result = await _voicePlaybackChannel.invokeMethod<bool>(
        'pausePlayback',
        {'messageId': messageId},
      );
      return result == true;
    } on PlatformException {
      return false;
    }
  }

  static Future<bool> resumePlayback(String messageId) async {
    try {
      final result = await _voicePlaybackChannel.invokeMethod<bool>(
        'resumePlayback',
        {'messageId': messageId},
      );
      return result == true;
    } on PlatformException {
      return false;
    }
  }

  static Future<bool> stopPlayback(String messageId) async {
    try {
      final result = await _voicePlaybackChannel.invokeMethod<bool>(
        'stopPlayback',
        {'messageId': messageId},
      );
      return result == true;
    } on PlatformException {
      return false;
    }
  }
}
