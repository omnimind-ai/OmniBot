import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/voice_playback_coordinator.dart';
import 'package:ui/theme/theme_context.dart';
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
  int elapsedSeconds = 0;
  Timer? limit;
  Timer? elapsedTicker;
}

class ChatSpeechInputButtonState extends State<ChatSpeechInputButton> {
  static const _channel = MethodChannel('cn.com.omnimind.bot/SpeechInput');
  static int _nextId = 0;
  static const _cancelMargin = 32.0;

  final LayerLink _layerLink = LayerLink();
  final OverlayPortalController _statusOverlayController =
      OverlayPortalController();
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

  void _syncStatusOverlay() {
    if (!mounted) return;
    final phase = _recording?.phase;
    final shouldShow =
        (phase == _SpeechInputPhase.recording ||
            phase == _SpeechInputPhase.transcribing) &&
        Overlay.maybeOf(context) != null;
    if (shouldShow && !_statusOverlayController.isShowing) {
      _statusOverlayController.show();
    } else if (!shouldShow && _statusOverlayController.isShowing) {
      _statusOverlayController.hide();
    }
  }

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
    recording.elapsedTicker?.cancel();
    VoicePlaybackCoordinator.instance.endSpeechInput(recording);
    if (rebuild && mounted) {
      setState(() => _recording = null);
      _syncStatusOverlay();
    } else {
      _recording = null;
      if (_statusOverlayController.isShowing) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted &&
              _recording == null &&
              _statusOverlayController.isShowing) {
            _statusOverlayController.hide();
          }
        });
      }
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
      _syncStatusOverlay();
      unawaited(HapticFeedback.selectionClick());
      recording.elapsedTicker = Timer.periodic(const Duration(seconds: 1), (_) {
        if (_isCurrent(recording) &&
            recording.phase == _SpeechInputPhase.recording) {
          setState(() => recording.elapsedSeconds += 1);
        }
      });
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
    recording.elapsedTicker?.cancel();
    setState(() => recording.phase = _SpeechInputPhase.transcribing);
    _syncStatusOverlay();
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

  String _formatElapsed(int totalSeconds) {
    final minutes = totalSeconds ~/ 60;
    final seconds = (totalSeconds % 60).toString().padLeft(2, '0');
    return '$minutes:$seconds';
  }

  Widget _buildStatusPill(BuildContext context) {
    final recording = _recording;
    if (recording == null) return const SizedBox.shrink();
    final transcribing = recording.phase == _SpeechInputPhase.transcribing;
    final listening = recording.phase == _SpeechInputPhase.recording;
    if (!listening && !transcribing) return const SizedBox.shrink();

    final cancel = listening && recording.draggingOut;
    final palette = context.omniPalette;
    final colors = Theme.of(context).colorScheme;
    final isDark = context.isDarkTheme;

    final Color bgColor;
    final Color borderColor;
    final Color fgColor;
    if (cancel) {
      bgColor = colors.error;
      borderColor = colors.error;
      fgColor = colors.onError;
    } else {
      bgColor = palette.surfacePrimary;
      borderColor = palette.borderSubtle.withValues(alpha: isDark ? 0.8 : 0.9);
      fgColor = palette.textPrimary;
    }

    final statusText = transcribing
        ? (_english ? 'Transcribing...' : '正在转写...')
        : cancel
        ? (_english ? 'Release to cancel' : '松开取消')
        : recording.beyondLimit
        ? (_english ? 'Limit reached' : '已达时长上限')
        : recording.hold
        ? (_english ? 'Release to send · Slide out to cancel' : '松开发送 · 移开取消')
        : (_english ? 'Tap to finish' : '点击结束');

    return CompositedTransformFollower(
      link: _layerLink,
      showWhenUnlinked: false,
      targetAnchor: Alignment.topRight,
      followerAnchor: Alignment.bottomRight,
      offset: const Offset(0, -14),
      child: IgnorePointer(
        child: Material(
          color: Colors.transparent,
          child: Container(
            key: const ValueKey('chat-speech-status-pill'),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
            decoration: BoxDecoration(
              color: bgColor,
              borderRadius: BorderRadius.circular(999),
              border: Border.all(color: borderColor, width: 0.8),
              boxShadow: [
                BoxShadow(
                  color: palette.shadowColor.withValues(
                    alpha: isDark ? 0.35 : 0.14,
                  ),
                  blurRadius: 12,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (transcribing) ...[
                  SizedBox(
                    width: 12,
                    height: 12,
                    child: CircularProgressIndicator(
                      strokeWidth: 1.8,
                      valueColor: AlwaysStoppedAnimation<Color>(
                        palette.brandPrimary,
                      ),
                    ),
                  ),
                  const SizedBox(width: 7),
                ] else if (cancel) ...[
                  Icon(LucideIcons.x, size: 13.5, color: fgColor),
                  const SizedBox(width: 5),
                ] else ...[
                  Container(
                    width: 7,
                    height: 7,
                    decoration: BoxDecoration(
                      color: colors.error,
                      shape: BoxShape.circle,
                    ),
                  ),
                  const SizedBox(width: 6),
                  Text(
                    _formatElapsed(recording.elapsedSeconds),
                    style: TextStyle(
                      color: fgColor,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      fontFeatures: const [FontFeature.tabularFigures()],
                      height: 1.2,
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 6),
                    child: Text(
                      '·',
                      style: TextStyle(
                        color: palette.textTertiary,
                        fontSize: 12,
                        height: 1.2,
                      ),
                    ),
                  ),
                ],
                Text(
                  statusText,
                  style: TextStyle(
                    color: cancel ? fgColor : palette.textSecondary,
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                    height: 1.2,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
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
    final palette = context.omniPalette;
    final colors = Theme.of(context).colorScheme;
    final animationDuration = MediaQuery.disableAnimationsOf(context)
        ? Duration.zero
        : const Duration(milliseconds: 180);

    final Color surfaceColor;
    final Color iconColor;
    if (cancel) {
      surfaceColor = colors.error;
      iconColor = colors.onError;
    } else if (listening) {
      surfaceColor = palette.brandPrimary;
      iconColor = Colors.white;
    } else if (transcribing) {
      surfaceColor = palette.surfaceSecondary;
      iconColor = palette.textPrimary;
    } else {
      surfaceColor = Colors.transparent;
      iconColor = palette.textSecondary;
    }

    return OverlayPortal(
      controller: _statusOverlayController,
      overlayChildBuilder: _buildStatusPill,
      child: CompositedTransformTarget(
        link: _layerLink,
        child: Semantics(
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
                child: AnimatedContainer(
                  duration: animationDuration,
                  curve: Curves.easeOutCubic,
                  width: 36,
                  height: 36,
                  decoration: BoxDecoration(
                    color: surfaceColor,
                    shape: BoxShape.circle,
                    boxShadow: listening
                        ? [
                            BoxShadow(
                              color:
                                  (cancel ? colors.error : palette.brandPrimary)
                                      .withValues(alpha: 0.28),
                              blurRadius: 10,
                              spreadRadius: 1,
                            ),
                          ]
                        : null,
                  ),
                  child: Center(
                    child: transcribing
                        ? SizedBox.square(
                            dimension: 16,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              valueColor: AlwaysStoppedAnimation<Color>(
                                iconColor,
                              ),
                            ),
                          )
                        : Icon(
                            icon,
                            size: listening && recording?.hold != true
                                ? 13
                                : listening
                                ? 17.5
                                : 19,
                            color: iconColor,
                          ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
