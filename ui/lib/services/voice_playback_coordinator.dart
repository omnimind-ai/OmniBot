import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:ui/services/scene_model_config_service.dart';
import 'package:ui/services/scene_voice_text_processing.dart';
import 'package:ui/services/voice_playback_channel_service.dart';

class VoiceMessagePlaybackState {
  final VoicePlaybackStatus status;
  final String error;
  final bool canReplay;

  const VoiceMessagePlaybackState({
    this.status = VoicePlaybackStatus.idle,
    this.error = '',
    this.canReplay = false,
  });

  VoiceMessagePlaybackState copyWith({
    VoicePlaybackStatus? status,
    String? error,
    bool? canReplay,
  }) {
    return VoiceMessagePlaybackState(
      status: status ?? this.status,
      error: error ?? this.error,
      canReplay: canReplay ?? this.canReplay,
    );
  }
}

class _VoiceStreamingTracker {
  String lastText = '';
  int nextIndex = 0;
  bool hasQueuedAny = false;
  bool interrupted = false;
  Future<void> pending = Future<void>.value();

  void reset() {
    lastText = '';
    nextIndex = 0;
    hasQueuedAny = false;
  }
}

class VoicePlaybackCoordinator extends ChangeNotifier {
  VoicePlaybackCoordinator._();

  static final VoicePlaybackCoordinator instance = VoicePlaybackCoordinator._();

  static const String sceneVoiceId = 'scene.voice';

  bool _initialized = false;
  bool _isVoiceSceneBound = false;
  SceneVoiceConfig _voiceConfig = const SceneVoiceConfig();
  Object? _speechInputOwner;
  final Set<String> _finishedProviderMessages = <String>{};
  final Map<String, VoiceMessagePlaybackState> _messageStates =
      <String, VoiceMessagePlaybackState>{};
  final Map<String, _VoiceStreamingTracker> _trackers =
      <String, _VoiceStreamingTracker>{};
  StreamSubscription<VoicePlaybackEvent>? _playbackSubscription;

  Future<void> ensureInitialized() async {
    if (_initialized) {
      return;
    }
    _initialized = true;
    await _reloadConfig();
    _playbackSubscription = VoicePlaybackChannelService.events.listen(
      _handlePlaybackEvent,
    );
  }

  Future<void> refreshConfiguration() async {
    if (!_initialized) {
      await ensureInitialized();
      return;
    }
    await _reloadConfig();
  }

  bool get isVoiceSceneBound {
    unawaited(ensureInitialized());
    return _isVoiceSceneBound;
  }

  SceneVoiceConfig get voiceConfig => _voiceConfig;

  VoiceMessagePlaybackState stateFor(String messageId) {
    unawaited(ensureInitialized());
    return _messageStates[messageId] ?? const VoiceMessagePlaybackState();
  }

  bool shouldShowVoiceButton({
    required int user,
    required int type,
    required String text,
    bool allowSpeechProvider = false,
  }) {
    unawaited(ensureInitialized());
    return _isVoiceSceneBound &&
        (!_voiceConfig.usesSpeechProvider || allowSpeechProvider) &&
        user == 2 &&
        type == 1 &&
        text.trim().isNotEmpty;
  }

  Future<void> beginSpeechInput(Object owner) async {
    _speechInputOwner = owner;
    for (final tracker in _trackers.values) {
      tracker.interrupted = true;
    }
    await VoicePlaybackChannelService.stopPlayback('');
  }

  void endSpeechInput(Object owner) {
    if (identical(owner, _speechInputOwner)) _speechInputOwner = null;
  }

  Future<void> onAssistantMessageUpdated({
    required String messageId,
    required String text,
    required bool isFinal,
    bool Function()? providerSpeechVisible,
    bool providerSpeechFailed = false,
  }) async {
    await ensureInitialized();
    if (!_isVoiceSceneBound || !_voiceConfig.autoPlay) {
      if (isFinal) _trackers.remove(messageId);
      return;
    }
    final usesProvider = _voiceConfig.usesSpeechProvider;
    if (usesProvider && _finishedProviderMessages.contains(messageId)) return;
    final normalizedText = text.trimRight();
    if (normalizedText.isEmpty) return;

    final tracker = _trackers.putIfAbsent(
      messageId,
      _VoiceStreamingTracker.new,
    );
    if (_speechInputOwner != null || (usesProvider && providerSpeechFailed)) {
      tracker.interrupted = true;
    }
    tracker.pending = tracker.pending.then((_) async {
      try {
        if (!identical(_trackers[messageId], tracker) || tracker.interrupted) {
          return;
        }
        if (tracker.lastText.isNotEmpty &&
            normalizedText.length < tracker.lastText.length &&
            tracker.lastText.startsWith(normalizedText)) {
          return;
        }
        if (tracker.lastText.isNotEmpty &&
            !normalizedText.startsWith(tracker.lastText)) {
          if (usesProvider && tracker.hasQueuedAny) {
            tracker.interrupted = true;
            return;
          }
          tracker.reset();
        }
        final extraction = SceneVoiceTextProcessing.extractSealedSegments(
          fullText: normalizedText,
          fromIndex: tracker.nextIndex,
          isFinal: isFinal,
        );
        tracker.lastText = normalizedText;
        tracker.nextIndex = extraction.nextIndex;
        for (final segment in extraction.segments) {
          if (!identical(_trackers[messageId], tracker) ||
              tracker.interrupted) {
            break;
          }
          if (usesProvider && providerSpeechVisible?.call() != true) continue;
          final accepted = await VoicePlaybackChannelService.speakText(
            messageId: messageId,
            text: segment,
            enqueue: tracker.hasQueuedAny,
            preferStreaming: true,
          );
          if (accepted) {
            tracker.hasQueuedAny = true;
          } else if (usesProvider) {
            tracker.interrupted = true;
          }
        }
      } finally {
        if (isFinal && identical(_trackers[messageId], tracker)) {
          _trackers.remove(messageId);
          if (usesProvider) {
            _finishedProviderMessages.add(messageId);
            if (_finishedProviderMessages.length > 128) {
              _finishedProviderMessages.remove(_finishedProviderMessages.first);
            }
          }
        }
      }
    });
    await tracker.pending;
  }

  Future<void> onAssistantMessageCompleted({
    required String messageId,
    required String text,
    bool Function()? providerSpeechVisible,
    bool providerSpeechFailed = false,
  }) => onAssistantMessageUpdated(
    messageId: messageId,
    text: text,
    isFinal: true,
    providerSpeechVisible: providerSpeechVisible,
    providerSpeechFailed: providerSpeechFailed,
  );

  Future<void> togglePlayback({
    required String messageId,
    required String text,
  }) async {
    await ensureInitialized();
    if (!_isVoiceSceneBound || _speechInputOwner != null) {
      return;
    }
    final currentState = stateFor(messageId);
    switch (currentState.status) {
      case VoicePlaybackStatus.playing:
        await VoicePlaybackChannelService.pausePlayback(messageId);
      case VoicePlaybackStatus.paused:
        await VoicePlaybackChannelService.resumePlayback(messageId);
      case VoicePlaybackStatus.idle:
      case VoicePlaybackStatus.synthesizing:
      case VoicePlaybackStatus.completed:
      case VoicePlaybackStatus.error:
        final sanitized = SceneVoiceTextProcessing.sanitizeForSpeech(text);
        if (sanitized.isEmpty) {
          return;
        }
        await VoicePlaybackChannelService.replayText(
          messageId: messageId,
          text: sanitized,
        );
    }
  }

  Future<void> stopPlayback(String messageId) async {
    await ensureInitialized();
    await VoicePlaybackChannelService.stopPlayback(messageId);
  }

  Future<void> _reloadConfig() async {
    final results = await Future.wait<dynamic>(<Future<dynamic>>[
      SceneModelConfigService.getSceneModelBindings(),
      SceneModelConfigService.getSceneVoiceConfig(),
    ]);
    final bindings = results[0] as List<SceneModelBindingEntry>;
    final voiceConfig = results[1] as SceneVoiceConfig;
    final nextBound = bindings.any(
      (binding) =>
          binding.sceneId == sceneVoiceId &&
          binding.providerProfileId.trim().isNotEmpty &&
          binding.modelId.trim().isNotEmpty,
    );
    // 自定义 curl 模式无需绑定 Provider：只要命令非空即视为可用。
    final customCurlReady =
        voiceConfig.isCustomCurl && voiceConfig.hasCustomCurlCommand;
    final nextAvailable = voiceConfig.usesSpeechProvider
        ? voiceConfig.synthesisProvider?.ready == true
        : nextBound || customCurlReady;
    var shouldNotify = false;
    if (_isVoiceSceneBound != nextAvailable) {
      _isVoiceSceneBound = nextAvailable;
      shouldNotify = true;
    }
    if (_voiceConfig != voiceConfig) {
      _voiceConfig = voiceConfig;
      shouldNotify = true;
    }
    if (!nextAvailable) {
      _trackers.clear();
    }
    if (shouldNotify) {
      notifyListeners();
    }
  }

  void _handlePlaybackEvent(VoicePlaybackEvent event) {
    if (event.messageId.trim().isEmpty) {
      return;
    }
    if (_voiceConfig.usesSpeechProvider &&
        event.status == VoicePlaybackStatus.error) {
      _trackers[event.messageId]?.interrupted = true;
    }
    _messageStates[event.messageId] = VoiceMessagePlaybackState(
      status: event.status,
      error: event.error,
      canReplay: event.canReplay,
    );
    notifyListeners();
  }

  @visibleForTesting
  Future<void> debugResetForTest() async {
    await _playbackSubscription?.cancel();
    _playbackSubscription = null;
    _initialized = false;
    _isVoiceSceneBound = false;
    _voiceConfig = const SceneVoiceConfig();
    _speechInputOwner = null;
    _finishedProviderMessages.clear();
    _messageStates.clear();
    _trackers.clear();
    notifyListeners();
  }

  @visibleForTesting
  void debugSetAvailabilityForTest({
    required bool isBound,
    SceneVoiceConfig config = const SceneVoiceConfig(),
  }) {
    _initialized = true;
    _isVoiceSceneBound = isBound;
    _voiceConfig = config;
    notifyListeners();
  }

  @visibleForTesting
  void debugSetMessageStateForTest(
    String messageId,
    VoiceMessagePlaybackState state,
  ) {
    _messageStates[messageId] = state;
    notifyListeners();
  }
}
