import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/voice_playback_coordinator.dart';
import 'package:ui/utils/ui.dart';

class ChatSpeechInputButton extends StatefulWidget {
  const ChatSpeechInputButton({
    super.key,
    required this.target,
    required this.onTranscript,
  });

  final int target;
  final void Function(String text, {required bool send, required int target})
  onTranscript;

  @override
  State<ChatSpeechInputButton> createState() => ChatSpeechInputButtonState();
}

enum _SpeechInputPhase { permission, recording, transcribing }

class _Recording {
  _Recording(this.id, this.target, {required this.hold});

  final String id;
  final int target;
  final bool hold;
  _SpeechInputPhase phase = _SpeechInputPhase.permission;
  bool beyondLimit = false;
  bool draggingOut = false;
  Timer? limit;
}

class ChatSpeechInputButtonState extends State<ChatSpeechInputButton> {
  static const _channel = MethodChannel('cn.com.omnimind.bot/SpeechInput');
  static int _nextId = 0;
  static const _cancelMargin = 32.0;
  _Recording? _recording;

  bool get _english => Localizations.localeOf(context).languageCode == 'en';

  @override
  void didUpdateWidget(covariant ChatSpeechInputButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.target != oldWidget.target) _cancel(rebuild: false);
  }

  @override
  void dispose() {
    _cancel(rebuild: false);
    super.dispose();
  }

  void cancel() => _cancel(rebuild: true);

  void _cancel({required bool rebuild}) {
    final recording = _recording;
    if (recording == null) return;
    _clear(recording, rebuild: rebuild);
    if (recording.phase != _SpeechInputPhase.permission) {
      unawaited(_invokeForSession('cancel', recording));
    }
  }

  Future<void> _invokeForSession(String method, _Recording recording) async {
    try {
      await _channel.invokeMethod<Object?>(method, {'id': recording.id});
    } on PlatformException catch (error) {
      if (_isCurrent(recording)) {
        _clear(recording);
        _showFailure(error);
      }
    }
  }

  bool _isCurrent(_Recording recording) =>
      mounted &&
      identical(_recording, recording) &&
      widget.target == recording.target;

  void _clear(_Recording recording, {bool rebuild = true}) {
    recording.limit?.cancel();
    VoicePlaybackCoordinator.instance.endSpeechInput(recording);
    if (rebuild && mounted) {
      setState(() => _recording = null);
    } else {
      _recording = null;
    }
  }

  Future<void> _start({required bool hold}) async {
    if (_recording != null) return;
    final recording = _Recording(
      'speech-${DateTime.now().microsecondsSinceEpoch}-${_nextId++}',
      widget.target,
      hold: hold,
    );
    setState(() => _recording = recording);
    try {
      final status = await _channel.invokeMethod<String>('permissionStatus');
      if (!_isCurrent(recording)) return;
      if (status != 'granted') {
        if (status == 'settings') {
          await _channel.invokeMethod<void>('openPermissionSettings');
        } else {
          final permission = await _channel.invokeMethod<String>(
            'requestPermission',
          );
          if (permission == 'granted' && mounted) {
            showToast(
              _english
                  ? 'Use the microphone again to record'
                  : '已授权，请再次操作麦克风开始录音',
            );
          }
        }
        return;
      }
      await VoicePlaybackCoordinator.instance.beginSpeechInput(recording);
      if (!_isCurrent(recording)) return;

      setState(() => recording.phase = _SpeechInputPhase.recording);
      unawaited(HapticFeedback.selectionClick());
      recording.limit = Timer(const Duration(seconds: 60), () {
        if (_isCurrent(recording)) setState(() => recording.beyondLimit = true);
      });
      final text = await _channel.invokeMethod<String>('start', {
        'id': recording.id,
      });
      if (!_isCurrent(recording) || text == null || text.trim().isEmpty) return;
      widget.onTranscript(text, send: recording.hold, target: recording.target);
    } on PlatformException catch (error) {
      if (_isCurrent(recording)) _showFailure(error);
    } finally {
      if (_isCurrent(recording)) _clear(recording);
    }
  }

  void _showFailure(PlatformException error) {
    final messages = {
      'permission': 'Microphone permission is required',
      'microphone': 'Microphone unavailable',
      'no_speech': 'No speech detected',
      'configuration': 'Set up your speech provider',
      'authentication': 'Check your DeepSeek API key and permissions',
      'rate_limit': 'DeepSeek is rate limited. Try again shortly.',
      'timeout': 'Speech recognition timed out',
      'network': 'Speech connection interrupted',
    };
    showToast(
      _english
          ? messages[error.code] ?? 'Speech recognition failed. Try again.'
          : error.message ?? '语音转写失败，请重试',
      type: ToastType.error,
    );
  }

  void _finish(_Recording recording) {
    if (recording.phase != _SpeechInputPhase.recording) return;
    recording.limit?.cancel();
    setState(() => recording.phase = _SpeechInputPhase.transcribing);
    unawaited(_invokeForSession('finish', recording));
  }

  void _tap() {
    final recording = _recording;
    if (recording == null) {
      unawaited(_start(hold: false));
    } else if (!recording.hold) {
      _finish(recording);
    }
  }

  bool _outside(Offset position, Size? size) {
    if (size == null) return true;
    return !(Offset.zero & size).inflate(_cancelMargin).contains(position);
  }

  void _holdMove(LongPressMoveUpdateDetails details) {
    final recording = _recording;
    if (recording == null || !recording.hold) return;
    final outside = _outside(details.localPosition, context.size);
    if (outside != recording.draggingOut) {
      setState(() => recording.draggingOut = outside);
    }
  }

  void _holdEnd(LongPressEndDetails details) {
    final recording = _recording;
    if (recording == null || !recording.hold) return;
    if (_outside(details.localPosition, context.size) ||
        recording.phase == _SpeechInputPhase.permission) {
      _cancel(rebuild: true);
    } else {
      _finish(recording);
    }
  }

  @override
  Widget build(BuildContext context) {
    final recording = _recording;
    final transcribing = recording?.phase == _SpeechInputPhase.transcribing;
    final listening = recording?.phase == _SpeechInputPhase.recording;
    final cancel = listening && recording!.draggingOut;
    final label = transcribing
        ? (_english ? 'Transcribing' : '正在转写')
        : cancel
        ? (_english ? 'Release to cancel' : '松开取消')
        : listening
        ? recording!.hold
              ? (_english ? 'Release to send' : '松开发送')
              : (_english ? 'Tap to finish' : '点击结束录音')
        : (_english ? 'Dictate. Hold to talk.' : '点击听写，长按说话');
    final icon = cancel
        ? LucideIcons.x
        : listening && recording?.hold != true
        ? LucideIcons.square
        : LucideIcons.mic;
    final colors = Theme.of(context).colorScheme;
    final color = cancel
        ? colors.error
        : listening
        ? colors.primary
        : colors.onSurfaceVariant;

    return Semantics(
      button: true,
      enabled: !transcribing,
      liveRegion: listening || transcribing,
      label: recording?.beyondLimit == true
          ? (_english ? 'Recording limit reached. $label' : '已达录音上限，$label')
          : label,
      onTap: transcribing ? null : _tap,
      child: Listener(
        onPointerCancel: (_) {
          if (_recording?.hold == true) _cancel(rebuild: true);
        },
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          excludeFromSemantics: true,
          onTap: transcribing ? null : _tap,
          onLongPressStart: recording == null
              ? (_) => unawaited(_start(hold: true))
              : null,
          onLongPressMoveUpdate: _holdMove,
          onLongPressEnd: _holdEnd,
          child: Tooltip(
            message: label,
            triggerMode: TooltipTriggerMode.manual,
            child: SizedBox.square(
              dimension: 44,
              child: Center(
                child: transcribing
                    ? const SizedBox.square(
                        dimension: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Icon(icon, size: 19, color: color),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
