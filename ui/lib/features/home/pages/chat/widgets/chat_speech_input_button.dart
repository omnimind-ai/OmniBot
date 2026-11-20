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

class _SpeechOverlaySnapshot {
  const _SpeechOverlaySnapshot({
    required this.phase,
    required this.hold,
    required this.draggingOut,
    required this.beyondLimit,
    required this.elapsedSeconds,
  });

  final _SpeechInputPhase phase;
  final bool hold;
  final bool draggingOut;
  final bool beyondLimit;
  final int elapsedSeconds;
}

class ChatSpeechInputButtonState extends State<ChatSpeechInputButton>
    with TickerProviderStateMixin {
  static const _channel = MethodChannel('cn.com.omnimind.bot/SpeechInput');
  static int _nextId = 0;
  static const _cancelMargin = 32.0;

  final LayerLink _layerLink = LayerLink();
  final OverlayPortalController _statusOverlayController =
      OverlayPortalController();

  late final AnimationController _pressController = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 90),
    reverseDuration: const Duration(milliseconds: 180),
  );
  late final AnimationController _pulseController = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1100),
  );
  late final AnimationController _pillController = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 200),
    reverseDuration: const Duration(milliseconds: 150),
  );

  late final Animation<double> _pressScale = Tween<double>(begin: 1.0, end: 0.90)
      .animate(
        CurvedAnimation(
          parent: _pressController,
          curve: Curves.easeOutCubic,
          reverseCurve: Curves.easeOutBack,
        ),
      );
  late final Animation<double> _pulseCurve = CurvedAnimation(
    parent: _pulseController,
    curve: Curves.easeInOutSine,
  );
  late final Animation<double> _pillFade = CurvedAnimation(
    parent: _pillController,
    curve: Curves.easeOutCubic,
    reverseCurve: Curves.easeInCubic,
  );
  late final Animation<Offset> _pillSlide =
      Tween<Offset>(begin: const Offset(0, 0.22), end: Offset.zero).animate(
        CurvedAnimation(
          parent: _pillController,
          curve: Curves.easeOutCubic,
          reverseCurve: Curves.easeInCubic,
        ),
      );
  late final Animation<double> _pillScale = Tween<double>(begin: 0.94, end: 1.0)
      .animate(
        CurvedAnimation(
          parent: _pillController,
          curve: Curves.easeOutCubic,
          reverseCurve: Curves.easeInCubic,
        ),
      );

  _Recording? _recording;
  _SpeechOverlaySnapshot? _overlaySnapshot;
  bool _pendingOverlayHide = false;

  bool get _english => Localizations.localeOf(context).languageCode == 'en';
  bool get _reduceMotion => MediaQuery.maybeDisableAnimationsOf(context) == true;

  @override
  void initState() {
    super.initState();
    _pillController.addStatusListener(_handlePillAnimationStatus);
  }

  @override
  void didUpdateWidget(covariant ChatSpeechInputButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.target != oldWidget.target) _cancel(rebuild: false);
  }

  @override
  void dispose() {
    _cancel(rebuild: false, animateOverlay: false);
    _pillController.removeStatusListener(_handlePillAnimationStatus);
    _pressController.dispose();
    _pulseController.dispose();
    _pillController.dispose();
    super.dispose();
  }

  void cancel() => _cancel(rebuild: true);

  void _handlePillAnimationStatus(AnimationStatus status) {
    if (status == AnimationStatus.dismissed &&
        _pendingOverlayHide &&
        mounted &&
        _recording == null) {
      _pendingOverlayHide = false;
      _overlaySnapshot = null;
      if (_statusOverlayController.isShowing) {
        _statusOverlayController.hide();
      }
    }
  }

  void _syncVisualState({bool animateOverlay = true}) {
    if (!mounted) return;
    final recording = _recording;
    final phase = recording?.phase;
    final isRecording = phase == _SpeechInputPhase.recording;
    final isTranscribing = phase == _SpeechInputPhase.transcribing;
    final isCancel = isRecording && recording!.draggingOut;

    if (isRecording && !isCancel && !_reduceMotion) {
      if (!_pulseController.isAnimating) {
        _pulseController.repeat(reverse: true);
      }
    } else if (_pulseController.isAnimating || _pulseController.value != 0) {
      _pulseController.stop();
      _pulseController.value = 0;
    }

    final shouldShowPill =
        (isRecording || isTranscribing) && Overlay.maybeOf(context) != null;

    if (shouldShowPill) {
      _pendingOverlayHide = false;
      _overlaySnapshot = _SpeechOverlaySnapshot(
        phase: phase!,
        hold: recording!.hold,
        draggingOut: recording.draggingOut,
        beyondLimit: recording.beyondLimit,
        elapsedSeconds: recording.elapsedSeconds,
      );
      if (!_statusOverlayController.isShowing) {
        _statusOverlayController.show();
      }
      if (_reduceMotion || !animateOverlay) {
        _pillController.value = 1.0;
      } else {
        _pillController.forward();
      }
    } else if (_statusOverlayController.isShowing) {
      if (_reduceMotion || !animateOverlay) {
        _pendingOverlayHide = false;
        _overlaySnapshot = null;
        _pillController.value = 0.0;
        _statusOverlayController.hide();
      } else if (!_pendingOverlayHide) {
        _pendingOverlayHide = true;
        _pillController.reverse();
      }
    }
  }

  void _cancel({required bool rebuild, bool animateOverlay = true}) {
    final recording = _recording;
    if (recording == null) return;
    _clear(recording, rebuild: rebuild, animateOverlay: animateOverlay);
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

  void _clear(
    _Recording recording, {
    bool rebuild = true,
    bool animateOverlay = true,
  }) {
    recording.limit?.cancel();
    recording.elapsedTicker?.cancel();
    VoicePlaybackCoordinator.instance.endSpeechInput(recording);
    if (rebuild && mounted) {
      setState(() => _recording = null);
      _syncVisualState(animateOverlay: animateOverlay);
    } else {
      _recording = null;
      _pulseController.stop();
      if (_statusOverlayController.isShowing) {
        if (!animateOverlay) {
          _pendingOverlayHide = false;
          _overlaySnapshot = null;
          _pillController.value = 0.0;
          _statusOverlayController.hide();
        } else {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted && _recording == null) {
              _syncVisualState(animateOverlay: true);
            }
          });
        }
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
      _syncVisualState();
      unawaited(HapticFeedback.selectionClick());
      recording.elapsedTicker = Timer.periodic(const Duration(seconds: 1), (_) {
        if (_isCurrent(recording) &&
            recording.phase == _SpeechInputPhase.recording) {
          setState(() => recording.elapsedSeconds += 1);
          _syncVisualState();
        }
      });
      recording.limit = Timer(const Duration(seconds: 60), () {
        if (_isCurrent(recording)) {
          setState(() => recording.beyondLimit = true);
          _syncVisualState();
        }
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
    _syncVisualState();
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
      unawaited(HapticFeedback.selectionClick());
      setState(() => recording.draggingOut = outside);
      _syncVisualState();
    }
  }

  void _holdEnd(LongPressEndDetails details) {
    _releasePressVisual();
    final recording = _recording;
    if (recording == null || !recording.hold) return;
    if (_outside(details.localPosition, context.size) ||
        recording.phase == _SpeechInputPhase.permission) {
      _cancel(rebuild: true);
    } else {
      _finish(recording);
    }
  }

  void _applyPressVisual() {
    if (_reduceMotion) return;
    _pressController.forward();
  }

  void _releasePressVisual() {
    if (_reduceMotion) return;
    _pressController.reverse();
  }

  String _formatElapsed(int totalSeconds) {
    final minutes = totalSeconds ~/ 60;
    final seconds = (totalSeconds % 60).toString().padLeft(2, '0');
    return '$minutes:$seconds';
  }

  Widget _buildStatusPill(BuildContext context) {
    final snapshot = _overlaySnapshot;
    if (snapshot == null) return const SizedBox.shrink();

    final transcribing = snapshot.phase == _SpeechInputPhase.transcribing;
    final cancel =
        snapshot.phase == _SpeechInputPhase.recording && snapshot.draggingOut;
    final palette = context.omniPalette;
    final colors = Theme.of(context).colorScheme;
    final isDark = context.isDarkTheme;
    final animDuration = _reduceMotion
        ? Duration.zero
        : const Duration(milliseconds: 180);

    final Color bgColor;
    final Color borderColor;
    final Color fgColor;
    final Color secondaryFgColor;
    if (cancel) {
      bgColor = colors.error;
      borderColor = colors.error;
      fgColor = colors.onError;
      secondaryFgColor = colors.onError;
    } else {
      bgColor = palette.surfacePrimary;
      borderColor = palette.borderSubtle.withValues(alpha: isDark ? 0.85 : 0.95);
      fgColor = palette.textPrimary;
      secondaryFgColor = palette.textSecondary;
    }

    final statusText = transcribing
        ? (_english ? 'Transcribing...' : '正在转写...')
        : cancel
        ? (_english ? 'Release to cancel' : '松开取消')
        : snapshot.beyondLimit
        ? (_english ? 'Limit reached' : '已达时长上限')
        : snapshot.hold
        ? (_english ? 'Release to send · Slide out to cancel' : '松开发送 · 移开取消')
        : (_english ? 'Tap to finish' : '点击结束');

    // Positioned with explicit left/top gives the follower loose constraints so
    // the pill wraps to its intrinsic size instead of expanding to the overlay.
    return Positioned(
      left: 0,
      top: 0,
      child: IgnorePointer(
        child: CompositedTransformFollower(
          link: _layerLink,
          showWhenUnlinked: false,
          targetAnchor: Alignment.topRight,
          followerAnchor: Alignment.bottomRight,
          offset: const Offset(0, -12),
          child: FadeTransition(
            opacity: _pillFade,
            child: SlideTransition(
              position: _pillSlide,
              child: ScaleTransition(
                scale: _pillScale,
                alignment: Alignment.bottomRight,
                child: Material(
                  color: Colors.transparent,
                  child: AnimatedContainer(
                    key: const ValueKey('chat-speech-status-pill'),
                    duration: animDuration,
                    curve: Curves.easeOutCubic,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 7,
                    ),
                    decoration: BoxDecoration(
                      color: bgColor,
                      borderRadius: BorderRadius.circular(999),
                      border: Border.all(color: borderColor, width: 0.8),
                      boxShadow: [
                        BoxShadow(
                          color: palette.shadowColor.withValues(
                            alpha: isDark ? 0.35 : 0.12,
                          ),
                          blurRadius: 12,
                          offset: const Offset(0, 4),
                        ),
                      ],
                    ),
                    child: AnimatedSize(
                      duration: animDuration,
                      curve: Curves.easeOutCubic,
                      alignment: Alignment.centerRight,
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
                            AnimatedBuilder(
                              animation: _pulseCurve,
                              builder: (context, child) => Opacity(
                                opacity: 1.0 - (_pulseCurve.value * 0.55),
                                child: child,
                              ),
                              child: Container(
                                width: 7,
                                height: 7,
                                decoration: BoxDecoration(
                                  color: colors.error,
                                  shape: BoxShape.circle,
                                ),
                              ),
                            ),
                            const SizedBox(width: 6),
                            Text(
                              _formatElapsed(snapshot.elapsedSeconds),
                              style: TextStyle(
                                color: fgColor,
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                                fontFeatures: const [
                                  FontFeature.tabularFigures(),
                                ],
                                height: 1.2,
                              ),
                            ),
                            Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 6,
                              ),
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
                              color: secondaryFgColor,
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
              ),
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
    final animationDuration = _reduceMotion
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

    final Widget iconChild;
    if (transcribing) {
      iconChild = SizedBox(
        key: const ValueKey('speech-icon-transcribing'),
        width: 16,
        height: 16,
        child: CircularProgressIndicator(
          strokeWidth: 2,
          valueColor: AlwaysStoppedAnimation<Color>(iconColor),
        ),
      );
    } else {
      iconChild = Icon(
        icon,
        key: ValueKey('speech-icon-${icon.codePoint}-${cancel ? 1 : 0}'),
        size: listening && recording?.hold != true
            ? 13
            : listening
            ? 17.5
            : 19,
        color: iconColor,
      );
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
            onPointerDown: transcribing ? null : (_) => _applyPressVisual(),
            onPointerUp: (_) => _releasePressVisual(),
            onPointerCancel: (_) {
              _releasePressVisual();
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
                child: ScaleTransition(
                  scale: _pressScale,
                  child: SizedBox(
                    width: 36,
                    height: 36,
                    child: Stack(
                      alignment: Alignment.center,
                      clipBehavior: Clip.none,
                      children: [
                        if (listening && !cancel)
                          AnimatedBuilder(
                            animation: _pulseCurve,
                            builder: (context, _) {
                              final scale = 1.0 + _pulseCurve.value * 0.34;
                              final alpha = 0.24 * (1.0 - _pulseCurve.value);
                              return Transform.scale(
                                scale: scale,
                                child: DecoratedBox(
                                  decoration: BoxDecoration(
                                    shape: BoxShape.circle,
                                    color: palette.brandPrimary.withValues(
                                      alpha: alpha,
                                    ),
                                  ),
                                  child: const SizedBox.square(dimension: 36),
                                ),
                              );
                            },
                          ),
                        AnimatedContainer(
                          duration: animationDuration,
                          curve: Curves.easeOutCubic,
                          width: 36,
                          height: 36,
                          decoration: BoxDecoration(
                            color: surfaceColor,
                            shape: BoxShape.circle,
                          ),
                          child: Center(
                            child: AnimatedSwitcher(
                              duration: animationDuration,
                              switchInCurve: Curves.easeOutCubic,
                              switchOutCurve: Curves.easeInCubic,
                              transitionBuilder: (child, animation) =>
                                  FadeTransition(
                                    opacity: animation,
                                    child: ScaleTransition(
                                      scale: animation,
                                      child: child,
                                    ),
                                  ),
                              child: iconChild,
                            ),
                          ),
                        ),
                      ],
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
