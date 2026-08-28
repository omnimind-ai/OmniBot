import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import 'package:ui/services/office_preview_service.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/pdf_preview_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/chat_drawer_gesture_guard.dart';
import 'package:ui/widgets/image_preview_overlay.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:video_player/video_player.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

typedef OmnibotResourceOpenCallback =
    Future<void> Function(
      BuildContext context,
      OmnibotResourceMetadata metadata,
    );

class OmnibotInlineResourceEmbed extends StatelessWidget {
  final OmnibotResourceMetadata metadata;
  final bool plainStyle;
  final double? maxWidth;
  final double? preferredHeight;
  final OmnibotResourceOpenCallback? onOpen;

  const OmnibotInlineResourceEmbed({
    super.key,
    required this.metadata,
    this.plainStyle = false,
    this.maxWidth,
    this.preferredHeight,
    this.onOpen,
  });

  @override
  Widget build(BuildContext context) {
    final resolvedMaxWidth =
        maxWidth ?? math.min(MediaQuery.sizeOf(context).width - 72, 360.0);
    return ConstrainedBox(
      constraints: BoxConstraints(maxWidth: resolvedMaxWidth),
      child: switch (metadata.embedKind) {
        'image' => _OmnibotInlineImageCard(
          metadata: metadata,
          plainStyle: plainStyle,
          onOpen: onOpen,
        ),
        'audio' => _OmnibotInlineAudioPlayer(
          metadata: metadata,
          plainStyle: plainStyle,
          onOpen: onOpen,
        ),
        'video' => _OmnibotInlineVideoPlayer(
          metadata: metadata,
          plainStyle: plainStyle,
          onOpen: onOpen,
        ),
        'pdf' => _OmnibotInlinePdfCard(
          metadata: metadata,
          plainStyle: plainStyle,
          preferredHeight: preferredHeight,
          onOpen: onOpen,
        ),
        'html' => _OmnibotInlineHtmlCard(
          metadata: metadata,
          plainStyle: plainStyle,
          preferredHeight: preferredHeight,
          onOpen: onOpen,
        ),
        'office' => _OmnibotInlineOfficePreviewCard(
          metadata: metadata,
          plainStyle: plainStyle,
          onOpen: onOpen,
        ),
        _ => OmnibotResourceLinkCard(
          metadata: metadata,
          plainStyle: plainStyle,
          onOpen: onOpen,
        ),
      },
    );
  }
}

class OmnibotResourceLinkCard extends StatelessWidget {
  final OmnibotResourceMetadata metadata;
  final bool plainStyle;
  final OmnibotResourceOpenCallback? onOpen;

  const OmnibotResourceLinkCard({
    super.key,
    required this.metadata,
    this.plainStyle = false,
    this.onOpen,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final surfaceColor = plainStyle
        ? Colors.transparent
        : (isDark ? palette.surfaceSecondary : Colors.white);
    final borderColor = isDark ? palette.borderSubtle : const Color(0xFFD8E4F8);
    final iconBackgroundColor = isDark
        ? palette.surfaceElevated
        : const Color(0xFFEAF3FF);
    final iconColor = isDark ? palette.accentPrimary : const Color(0xFF1F4ED8);
    final titleColor = isDark ? palette.textPrimary : const Color(0xFF0F172A);
    final metaColor = isDark ? palette.textSecondary : const Color(0xFF64748B);
    final icon = switch (metadata.previewKind) {
      'pdf' => Icons.picture_as_pdf_outlined,
      'html' => Icons.language_outlined,
      'text' => Icons.description_outlined,
      'code' => Icons.code_outlined,
      'office_word' => Icons.description_outlined,
      'office_sheet' => Icons.table_chart_outlined,
      'office_slide' => Icons.slideshow_outlined,
      'directory' => Icons.folder_outlined,
      _ => Icons.insert_drive_file_outlined,
    };
    return InkWell(
      onTap: () => _openMetadata(context, metadata, onOpen: onOpen),
      borderRadius: BorderRadius.circular(14),
      child: Ink(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: surfaceColor,
          borderRadius: BorderRadius.circular(14),
          border: plainStyle ? null : Border.all(color: borderColor),
          boxShadow: plainStyle
              ? null
              : const [
                  BoxShadow(
                    color: Color(0x12243258),
                    blurRadius: 10,
                    offset: Offset(0, 4),
                  ),
                ],
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 38,
              height: 38,
              decoration: BoxDecoration(
                color: iconBackgroundColor,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(icon, size: 20, color: iconColor),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    metadata.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: titleColor,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    metadata.shellPath,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 11,
                      color: metaColor,
                      fontFamily: 'monospace',
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Icon(Icons.open_in_new_rounded, size: 18, color: metaColor),
          ],
        ),
      ),
    );
  }
}

class _OmnibotInlineImageCard extends StatelessWidget {
  final OmnibotResourceMetadata metadata;
  final bool plainStyle;
  final OmnibotResourceOpenCallback? onOpen;

  const _OmnibotInlineImageCard({
    required this.metadata,
    this.plainStyle = false,
    this.onOpen,
  });

  @override
  Widget build(BuildContext context) {
    final heroTag = 'img_preview_${metadata.path}';
    return InkWell(
      onTap: () {
        if (metadata.exists) {
          ImagePreviewOverlay.show(
            context,
            source: FileImageSource(metadata.path),
            heroTag: heroTag,
          );
        } else {
          _openMetadata(context, metadata, onOpen: onOpen);
        }
      },
      borderRadius: BorderRadius.circular(16),
      child: Ink(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          border: plainStyle
              ? null
              : Border.all(color: const Color(0xFFD8E4F8)),
          color: plainStyle ? Colors.transparent : Colors.white,
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(16),
          child: metadata.exists
              ? Hero(
                  tag: heroTag,
                  child: Image.file(
                    File(metadata.path),
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) => _MissingResourceCard(
                      metadata: metadata,
                      icon: Icons.broken_image_outlined,
                      subtitle: LegacyTextLocalizer.isEnglish
                          ? 'Failed to load image'
                          : '图片加载失败',
                      plainStyle: plainStyle,
                      onOpen: onOpen,
                    ),
                  ),
                )
              : _MissingResourceCard(
                  metadata: metadata,
                  icon: Icons.image_not_supported_outlined,
                  subtitle: LegacyTextLocalizer.isEnglish
                      ? 'Image does not exist or is not readable'
                      : '图片不存在或暂不可读',
                  plainStyle: plainStyle,
                  onOpen: onOpen,
                ),
        ),
      ),
    );
  }
}

class _OmnibotInlineAudioPlayer extends StatefulWidget {
  final OmnibotResourceMetadata metadata;
  final bool plainStyle;
  final OmnibotResourceOpenCallback? onOpen;

  const _OmnibotInlineAudioPlayer({
    required this.metadata,
    this.plainStyle = false,
    this.onOpen,
  });

  @override
  State<_OmnibotInlineAudioPlayer> createState() =>
      _OmnibotInlineAudioPlayerState();
}

class _OmnibotInlineAudioPlayerState extends State<_OmnibotInlineAudioPlayer> {
  late final AudioPlayer _player;
  Duration? _duration;
  Object? _error;
  bool _isReady = false;

  @override
  void initState() {
    super.initState();
    _player = AudioPlayer();
    _player.durationStream.listen((value) {
      if (!mounted) return;
      setState(() => _duration = value);
    });
    _initialize();
  }

  Future<void> _initialize() async {
    if (!widget.metadata.exists) return;
    try {
      await _player.setFilePath(widget.metadata.path);
      if (!mounted) return;
      setState(() => _isReady = true);
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error);
    }
  }

  @override
  void dispose() {
    _player.dispose();
    super.dispose();
  }

  Future<void> _togglePlayback() async {
    if (!_isReady) return;
    if (_player.playing) {
      await _player.pause();
      return;
    }
    if (_player.processingState == ProcessingState.completed) {
      await _player.seek(Duration.zero);
    }
    await _player.play();
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.metadata.exists || _error != null) {
      return _MissingResourceCard(
        metadata: widget.metadata,
        icon: Icons.audio_file_outlined,
        subtitle: _error == null
            ? (LegacyTextLocalizer.isEnglish
                  ? 'Audio does not exist or is not readable'
                  : '音频不存在或暂不可读')
            : (LegacyTextLocalizer.isEnglish
                  ? 'Failed to load audio'
                  : '音频加载失败'),
        plainStyle: widget.plainStyle,
        onOpen: widget.onOpen,
      );
    }
    return StreamBuilder<PlayerState>(
      stream: _player.playerStateStream,
      builder: (context, snapshot) {
        final playerState = snapshot.data;
        final isPlaying = playerState?.playing ?? false;
        return Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: widget.plainStyle ? Colors.transparent : Colors.white,
            borderRadius: BorderRadius.circular(16),
            border: widget.plainStyle
                ? null
                : Border.all(color: const Color(0xFFD8E4F8)),
            boxShadow: widget.plainStyle
                ? null
                : const [
                    BoxShadow(
                      color: Color(0x12243258),
                      blurRadius: 10,
                      offset: Offset(0, 4),
                    ),
                  ],
          ),
          child: Row(
            children: [
              IconButton.filledTonal(
                onPressed: _isReady ? _togglePlayback : null,
                icon: Icon(
                  isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      widget.metadata.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      _duration == null
                          ? (LegacyTextLocalizer.isEnglish ? 'Audio' : '音频资源')
                          : _formatDuration(_duration!),
                      style: const TextStyle(
                        fontSize: 11,
                        color: Color(0xFF64748B),
                      ),
                    ),
                  ],
                ),
              ),
              IconButton(
                tooltip: LegacyTextLocalizer.isEnglish
                    ? 'Open preview'
                    : '打开预览',
                onPressed: () => _openMetadata(
                  context,
                  widget.metadata,
                  onOpen: widget.onOpen,
                ),
                icon: const Icon(Icons.open_in_new_rounded),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _OmnibotInlineVideoPlayer extends StatefulWidget {
  final OmnibotResourceMetadata metadata;
  final bool plainStyle;
  final OmnibotResourceOpenCallback? onOpen;

  const _OmnibotInlineVideoPlayer({
    required this.metadata,
    this.plainStyle = false,
    this.onOpen,
  });

  @override
  State<_OmnibotInlineVideoPlayer> createState() =>
      _OmnibotInlineVideoPlayerState();
}

class _OmnibotInlineVideoPlayerState extends State<_OmnibotInlineVideoPlayer> {
  VideoPlayerController? _controller;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  @override
  void didUpdateWidget(covariant _OmnibotInlineVideoPlayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.metadata.path != widget.metadata.path) {
      final previousController = _controller;
      _controller = null;
      _error = null;
      previousController?.dispose();
      _initialize();
    }
  }

  Future<void> _initialize() async {
    if (!widget.metadata.exists) return;
    final controller = VideoPlayerController.file(File(widget.metadata.path));
    try {
      await controller.initialize();
      controller.setLooping(false);
      controller.addListener(() {
        if (mounted) {
          setState(() {});
        }
      });
      if (!mounted) {
        await controller.dispose();
        return;
      }
      setState(() => _controller = controller);
    } catch (error) {
      await controller.dispose();
      if (!mounted) return;
      setState(() => _error = error);
    }
  }

  Future<void> _openFullscreen() async {
    final controller = _controller;
    if (controller == null || !controller.value.isInitialized) {
      return;
    }
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => _OmnibotFullscreenVideoPage(
          controller: controller,
          title: widget.metadata.title,
        ),
      ),
    );
    if (mounted) {
      setState(() {});
    }
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.metadata.exists || _error != null) {
      return _MissingResourceCard(
        metadata: widget.metadata,
        icon: Icons.video_file_outlined,
        subtitle: _error == null
            ? (LegacyTextLocalizer.isEnglish
                  ? 'Video does not exist or is not readable'
                  : '视频不存在或暂不可读')
            : (LegacyTextLocalizer.isEnglish
                  ? 'Failed to load video'
                  : '视频加载失败'),
        plainStyle: widget.plainStyle,
        onOpen: widget.onOpen,
      );
    }
    final controller = _controller;
    if (controller == null || !controller.value.isInitialized) {
      return Container(
        height: 180,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: widget.plainStyle ? Colors.transparent : Colors.white,
          borderRadius: BorderRadius.circular(16),
          border: widget.plainStyle
              ? null
              : Border.all(color: const Color(0xFFD8E4F8)),
        ),
        child: const CircularProgressIndicator(),
      );
    }
    return Container(
      decoration: BoxDecoration(
        color: widget.plainStyle ? Colors.transparent : Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: widget.plainStyle
            ? null
            : Border.all(color: const Color(0xFFD8E4F8)),
        boxShadow: widget.plainStyle
            ? null
            : const [
                BoxShadow(
                  color: Color(0x12243258),
                  blurRadius: 10,
                  offset: Offset(0, 4),
                ),
              ],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: AspectRatio(
          aspectRatio: controller.value.aspectRatio == 0
              ? 16 / 9
              : controller.value.aspectRatio,
          child: _OmnibotVideoSurface(
            controller: controller,
            borderRadius: BorderRadius.circular(16),
            fullscreenButtonIcon: Icons.fullscreen_rounded,
            onFullscreenPressed: _openFullscreen,
          ),
        ),
      ),
    );
  }
}

class _OmnibotVideoSurface extends StatefulWidget {
  final VideoPlayerController controller;
  final BorderRadius borderRadius;
  final IconData fullscreenButtonIcon;
  final VoidCallback? onFullscreenPressed;
  final bool showDismissButton;
  final VoidCallback? onDismissPressed;

  const _OmnibotVideoSurface({
    required this.controller,
    required this.borderRadius,
    required this.fullscreenButtonIcon,
    this.onFullscreenPressed,
    this.showDismissButton = false,
    this.onDismissPressed,
  });

  @override
  State<_OmnibotVideoSurface> createState() => _OmnibotVideoSurfaceState();
}

class _OmnibotVideoSurfaceState extends State<_OmnibotVideoSurface> {
  static const Duration _controlsAutoHideDelay = Duration(seconds: 2);
  static const Duration _controlsFadeDuration = Duration(milliseconds: 180);

  Timer? _controlsHideTimer;
  bool _controlsVisible = true;
  bool _wasPlaying = false;
  double? _scrubPositionMs;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_handleControllerChanged);
    _wasPlaying = widget.controller.value.isPlaying;
    if (_wasPlaying) {
      _restartControlsHideTimer();
    }
  }

  @override
  void didUpdateWidget(covariant _OmnibotVideoSurface oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_handleControllerChanged);
      widget.controller.addListener(_handleControllerChanged);
      _cancelControlsHideTimer();
      _controlsVisible = true;
      _scrubPositionMs = null;
      _wasPlaying = widget.controller.value.isPlaying;
      if (_wasPlaying) {
        _restartControlsHideTimer();
      }
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_handleControllerChanged);
    _cancelControlsHideTimer();
    super.dispose();
  }

  void _handleControllerChanged() {
    final value = widget.controller.value;
    final isPlaying = value.isPlaying;
    final hasCompleted = _hasCompleted(value);
    if (hasCompleted) {
      _cancelControlsHideTimer();
      if (!_controlsVisible && mounted) {
        setState(() => _controlsVisible = true);
      }
    } else if (!_wasPlaying && isPlaying) {
      _restartControlsHideTimer();
    } else if (_wasPlaying && !isPlaying) {
      _cancelControlsHideTimer();
      if (!_controlsVisible && mounted) {
        setState(() => _controlsVisible = true);
      }
    }
    _wasPlaying = isPlaying;
  }

  void _restartControlsHideTimer() {
    _cancelControlsHideTimer();
    _controlsHideTimer = Timer(_controlsAutoHideDelay, () {
      if (!mounted || !widget.controller.value.isPlaying) {
        return;
      }
      setState(() => _controlsVisible = false);
    });
  }

  void _cancelControlsHideTimer() {
    _controlsHideTimer?.cancel();
    _controlsHideTimer = null;
  }

  bool _hasCompleted(VideoPlayerValue value) {
    final duration = value.duration;
    if (!value.isInitialized || duration <= Duration.zero) {
      return false;
    }
    return value.position >= duration;
  }

  Future<void> _togglePlayback() async {
    final controller = widget.controller;
    final value = controller.value;
    if (value.isPlaying) {
      await controller.pause();
      _cancelControlsHideTimer();
    } else {
      if (_hasCompleted(value)) {
        await controller.seekTo(Duration.zero);
      }
      await controller.play();
      _restartControlsHideTimer();
    }
    if (!mounted) return;
    setState(() => _controlsVisible = true);
  }

  void _handleSurfaceTap() {
    final isPlaying = widget.controller.value.isPlaying;
    if (!isPlaying) {
      if (!_controlsVisible) {
        setState(() => _controlsVisible = true);
      }
      return;
    }
    setState(() => _controlsVisible = !_controlsVisible);
    if (_controlsVisible) {
      _restartControlsHideTimer();
    } else {
      _cancelControlsHideTimer();
    }
  }

  void _handleScrubStart(double value) {
    _cancelControlsHideTimer();
    setState(() {
      _controlsVisible = true;
      _scrubPositionMs = value;
    });
  }

  void _handleScrubUpdate(double value) {
    setState(() {
      _controlsVisible = true;
      _scrubPositionMs = value;
    });
  }

  Future<void> _handleScrubEnd(double value) async {
    await widget.controller.seekTo(Duration(milliseconds: value.round()));
    if (!mounted) return;
    setState(() => _scrubPositionMs = null);
    if (widget.controller.value.isPlaying) {
      _restartControlsHideTimer();
    }
  }

  @override
  Widget build(BuildContext context) {
    final value = widget.controller.value;
    final showControls = _controlsVisible || !value.isPlaying;
    final durationMs = math.max(value.duration.inMilliseconds.toDouble(), 1.0);
    final effectivePositionMs =
        _scrubPositionMs ??
        value.position.inMilliseconds.toDouble().clamp(0.0, durationMs);
    final currentPosition = Duration(milliseconds: effectivePositionMs.round());
    final centerIcon = _hasCompleted(value)
        ? Icons.replay_rounded
        : (value.isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded);

    return ClipRRect(
      borderRadius: widget.borderRadius,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: _handleSurfaceTap,
        child: Stack(
          fit: StackFit.expand,
          children: [
            ColoredBox(
              color: Colors.black,
              child: FittedBox(
                fit: BoxFit.contain,
                child: SizedBox(
                  width: value.size.width <= 0 ? 1 : value.size.width,
                  height: value.size.height <= 0 ? 1 : value.size.height,
                  child: VideoPlayer(widget.controller),
                ),
              ),
            ),
            IgnorePointer(
              ignoring: !showControls,
              child: AnimatedOpacity(
                opacity: showControls ? 1 : 0,
                duration: _controlsFadeDuration,
                child: DecoratedBox(
                  decoration: const BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: [
                        Color(0x5A000000),
                        Color(0x12000000),
                        Color(0x7A000000),
                      ],
                      stops: [0.0, 0.45, 1.0],
                    ),
                  ),
                  child: Stack(
                    children: [
                      if (widget.showDismissButton &&
                          widget.onDismissPressed != null)
                        Positioned(
                          top: 12,
                          left: 12,
                          child: SafeArea(
                            bottom: false,
                            child: _VideoControlCircleButton(
                              icon: Icons.arrow_back_rounded,
                              onPressed: widget.onDismissPressed!,
                            ),
                          ),
                        ),
                      Center(
                        child: _VideoControlCircleButton(
                          icon: centerIcon,
                          size: 56,
                          iconSize: 30,
                          onPressed: _togglePlayback,
                        ),
                      ),
                      Positioned(
                        left: 0,
                        right: 0,
                        bottom: 0,
                        child: SafeArea(
                          top: false,
                          minimum: const EdgeInsets.fromLTRB(8, 0, 8, 8),
                          child: Row(
                            children: [
                              Text(
                                _formatDuration(currentPosition),
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 11,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                              Expanded(
                                child: SliderTheme(
                                  data: SliderTheme.of(context).copyWith(
                                    trackHeight: 2.5,
                                    thumbShape: const RoundSliderThumbShape(
                                      enabledThumbRadius: 5,
                                    ),
                                    overlayShape: const RoundSliderOverlayShape(
                                      overlayRadius: 10,
                                    ),
                                    activeTrackColor: Colors.white,
                                    inactiveTrackColor: const Color(0x55FFFFFF),
                                    thumbColor: Colors.white,
                                    overlayColor: const Color(0x33FFFFFF),
                                  ),
                                  child: Slider(
                                    min: 0,
                                    max: durationMs,
                                    value: effectivePositionMs.clamp(
                                      0.0,
                                      durationMs,
                                    ),
                                    onChangeStart: _handleScrubStart,
                                    onChanged: _handleScrubUpdate,
                                    onChangeEnd: _handleScrubEnd,
                                  ),
                                ),
                              ),
                              Text(
                                _formatDuration(value.duration),
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 11,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                              const SizedBox(width: 2),
                              IconButton(
                                onPressed: widget.onFullscreenPressed,
                                icon: Icon(
                                  widget.fullscreenButtonIcon,
                                  color: Colors.white,
                                ),
                                iconSize: 22,
                                splashRadius: 18,
                                tooltip: LegacyTextLocalizer.isEnglish
                                    ? 'Fullscreen'
                                    : '全屏',
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _VideoControlCircleButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback onPressed;
  final double size;
  final double iconSize;

  const _VideoControlCircleButton({
    required this.icon,
    required this.onPressed,
    this.size = 48,
    this.iconSize = 26,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: const Color(0x66000000),
      shape: const CircleBorder(),
      child: InkWell(
        customBorder: const CircleBorder(),
        onTap: onPressed,
        child: SizedBox(
          width: size,
          height: size,
          child: Icon(icon, color: Colors.white, size: iconSize),
        ),
      ),
    );
  }
}

class _OmnibotFullscreenVideoPage extends StatelessWidget {
  final VideoPlayerController controller;
  final String? title;

  const _OmnibotFullscreenVideoPage({required this.controller, this.title});

  @override
  Widget build(BuildContext context) {
    final aspectRatio = controller.value.aspectRatio == 0
        ? 16 / 9
        : controller.value.aspectRatio;
    return Scaffold(
      backgroundColor: Colors.black,
      body: Center(
        child: AspectRatio(
          aspectRatio: aspectRatio,
          child: _OmnibotVideoSurface(
            controller: controller,
            borderRadius: BorderRadius.zero,
            fullscreenButtonIcon: Icons.fullscreen_exit_rounded,
            onFullscreenPressed: () => Navigator.of(context).pop(),
            showDismissButton: true,
            onDismissPressed: () => Navigator.of(context).pop(),
          ),
        ),
      ),
    );
  }
}

class _OmnibotInlinePdfCard extends StatelessWidget {
  final OmnibotResourceMetadata metadata;
  final bool plainStyle;
  final double? preferredHeight;
  final OmnibotResourceOpenCallback? onOpen;

  const _OmnibotInlinePdfCard({
    required this.metadata,
    this.plainStyle = false,
    this.preferredHeight,
    this.onOpen,
  });

  @override
  Widget build(BuildContext context) {
    if (!metadata.exists) {
      return _MissingResourceCard(
        metadata: metadata,
        icon: Icons.picture_as_pdf_outlined,
        subtitle: LegacyTextLocalizer.isEnglish
            ? 'PDF does not exist or is not readable'
            : 'PDF 不存在或暂不可读',
        plainStyle: plainStyle,
        onOpen: onOpen,
      );
    }
    return _OmnibotPdfScrollablePreview(
      metadata: metadata,
      plainStyle: plainStyle,
      preferredHeight: preferredHeight,
      onOpen: onOpen,
    );
  }
}

class _OmnibotPdfScrollablePreview extends StatefulWidget {
  final OmnibotResourceMetadata metadata;
  final bool plainStyle;
  final double? preferredHeight;
  final OmnibotResourceOpenCallback? onOpen;

  const _OmnibotPdfScrollablePreview({
    required this.metadata,
    this.plainStyle = false,
    this.preferredHeight,
    this.onOpen,
  });

  @override
  State<_OmnibotPdfScrollablePreview> createState() =>
      _OmnibotPdfScrollablePreviewState();
}

class _OmnibotPdfScrollablePreviewState
    extends State<_OmnibotPdfScrollablePreview> {
  late Future<OmnibotPdfDocumentInfo> _documentFuture;
  final ScrollController _scrollController = ScrollController();
  final Map<String, Future<Uint8List>> _pageFutureCache =
      <String, Future<Uint8List>>{};

  @override
  void initState() {
    super.initState();
    _documentFuture = OmnibotPdfPreviewService.getDocumentInfo(
      widget.metadata.path,
    );
  }

  @override
  void didUpdateWidget(covariant _OmnibotPdfScrollablePreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.metadata.path != widget.metadata.path) {
      _pageFutureCache.clear();
      _documentFuture = OmnibotPdfPreviewService.getDocumentInfo(
        widget.metadata.path,
      );
    }
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final targetHeight =
        widget.preferredHeight ??
        math.min(MediaQuery.sizeOf(context).height * 0.52, 420.0);
    return Container(
      decoration: BoxDecoration(
        color: widget.plainStyle ? Colors.transparent : Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: widget.plainStyle
            ? null
            : Border.all(color: const Color(0xFFD8E4F8)),
        boxShadow: widget.plainStyle
            ? null
            : const [
                BoxShadow(
                  color: Color(0x12243258),
                  blurRadius: 10,
                  offset: Offset(0, 4),
                ),
              ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
            child: Row(
              children: [
                const Icon(
                  Icons.picture_as_pdf_outlined,
                  size: 18,
                  color: Color(0xFFDC2626),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    widget.metadata.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: Color(0xFF0F172A),
                    ),
                  ),
                ),
                Text(
                  _fileSizeLabel(widget.metadata.path),
                  style: const TextStyle(
                    fontSize: 11,
                    color: Color(0xFF64748B),
                  ),
                ),
              ],
            ),
          ),
          SizedBox(
            height: targetHeight,
            child: FutureBuilder<OmnibotPdfDocumentInfo>(
              future: _documentFuture,
              builder: (context, snapshot) {
                if (snapshot.connectionState != ConnectionState.done) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (snapshot.hasError || !snapshot.hasData) {
                  return _MissingResourceCard(
                    metadata: widget.metadata,
                    icon: Icons.picture_as_pdf_outlined,
                    subtitle: LegacyTextLocalizer.isEnglish
                        ? 'PDF preview failed'
                        : 'PDF 预览失败',
                    plainStyle: widget.plainStyle,
                    onOpen: widget.onOpen,
                  );
                }
                final info = snapshot.data!;
                return Scrollbar(
                  controller: _scrollController,
                  thumbVisibility: true,
                  child: ListView.separated(
                    controller: _scrollController,
                    primary: false,
                    physics: const ClampingScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
                    itemCount: info.pageCount,
                    separatorBuilder: (_, __) => const SizedBox(height: 12),
                    itemBuilder: (context, index) {
                      final page = index < info.pages.length
                          ? info.pages[index]
                          : const OmnibotPdfPageInfo(width: 1, height: 1);
                      return _PdfPageTile(
                        documentPath: widget.metadata.path,
                        pageIndex: index,
                        pageInfo: page,
                        futureCache: _pageFutureCache,
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _PdfPageTile extends StatelessWidget {
  final String documentPath;
  final int pageIndex;
  final OmnibotPdfPageInfo pageInfo;
  final Map<String, Future<Uint8List>> futureCache;

  const _PdfPageTile({
    required this.documentPath,
    required this.pageIndex,
    required this.pageInfo,
    required this.futureCache,
  });

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final targetWidthPx = _resolvePdfTargetWidthPx(context, constraints);
        final cacheKey = '$documentPath#$pageIndex@$targetWidthPx';
        final pageFuture = futureCache.putIfAbsent(
          cacheKey,
          () => OmnibotPdfPreviewService.renderPage(
            path: documentPath,
            pageIndex: pageIndex,
            targetWidthPx: targetWidthPx,
          ),
        );
        return DecoratedBox(
          decoration: BoxDecoration(
            color: const Color(0xFFF8FAFC),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: const Color(0xFFE2E8F0)),
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: AspectRatio(
              aspectRatio: pageInfo.aspectRatio,
              child: FutureBuilder<Uint8List>(
                future: pageFuture,
                builder: (context, snapshot) {
                  if (snapshot.connectionState != ConnectionState.done) {
                    return _PdfPagePlaceholder(pageIndex: pageIndex);
                  }
                  if (snapshot.hasError || !snapshot.hasData) {
                    return _PdfPageError(pageIndex: pageIndex);
                  }
                  return Image.memory(
                    snapshot.data!,
                    fit: BoxFit.contain,
                    gaplessPlayback: true,
                    filterQuality: FilterQuality.medium,
                  );
                },
              ),
            ),
          ),
        );
      },
    );
  }
}

class _PdfPagePlaceholder extends StatelessWidget {
  final int pageIndex;

  const _PdfPagePlaceholder({required this.pageIndex});

  @override
  Widget build(BuildContext context) {
    return Container(
      color: const Color(0xFFF8FAFC),
      alignment: Alignment.center,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const CircularProgressIndicator(),
          const SizedBox(height: 10),
          Text(
            LegacyTextLocalizer.isEnglish
                ? 'Page ${pageIndex + 1} loading'
                : '第 ${pageIndex + 1} 页加载中',
            style: const TextStyle(fontSize: 12, color: Color(0xFF64748B)),
          ),
        ],
      ),
    );
  }
}

class _PdfPageError extends StatelessWidget {
  final int pageIndex;

  const _PdfPageError({required this.pageIndex});

  @override
  Widget build(BuildContext context) {
    return Container(
      color: const Color(0xFFFFFBEB),
      alignment: Alignment.center,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.error_outline_rounded, color: Color(0xFFB45309)),
          const SizedBox(height: 8),
          Text(
            LegacyTextLocalizer.isEnglish
                ? 'Page ${pageIndex + 1} render failed'
                : '第 ${pageIndex + 1} 页渲染失败',
            style: const TextStyle(fontSize: 12, color: Color(0xFF92400E)),
          ),
        ],
      ),
    );
  }
}

class _OmnibotInlineHtmlCard extends StatefulWidget {
  final OmnibotResourceMetadata metadata;
  final bool plainStyle;
  final double? preferredHeight;
  final OmnibotResourceOpenCallback? onOpen;

  const _OmnibotInlineHtmlCard({
    required this.metadata,
    this.plainStyle = false,
    this.preferredHeight,
    this.onOpen,
  });

  @override
  State<_OmnibotInlineHtmlCard> createState() => _OmnibotInlineHtmlCardState();
}

class _OmnibotInlineHtmlCardState extends State<_OmnibotInlineHtmlCard> {
  static final Set<Factory<OneSequenceGestureRecognizer>>
  _webViewGestureRecognizers = <Factory<OneSequenceGestureRecognizer>>{
    Factory<EagerGestureRecognizer>(() => EagerGestureRecognizer()),
  };

  late final WebViewController _controller;
  bool _isLoading = true;
  String? _errorMessage;
  double? _measuredHeight;

  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.white)
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageStarted: (_) {
            if (!mounted) return;
            setState(() {
              _isLoading = true;
              _errorMessage = null;
            });
          },
          onPageFinished: (_) async {
            await _updateMeasuredHeight();
            if (!mounted) return;
            setState(() {
              _isLoading = false;
              _errorMessage = null;
            });
          },
          onWebResourceError: (error) {
            if (error.isForMainFrame == false) {
              return;
            }
            if (!mounted) return;
            setState(() {
              _isLoading = false;
              _errorMessage = error.description;
            });
          },
        ),
      )
      ..enableZoom(true);
    _loadHtmlFile();
  }

  @override
  void didUpdateWidget(covariant _OmnibotInlineHtmlCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.metadata.path != widget.metadata.path) {
      _measuredHeight = null;
      _loadHtmlFile();
    }
  }

  Future<void> _loadHtmlFile() async {
    if (!widget.metadata.exists) return;
    try {
      if (mounted) {
        setState(() {
          _isLoading = true;
          _errorMessage = null;
        });
      }
      final platformController = _controller.platform;
      if (platformController is AndroidWebViewController) {
        await Future.wait(<Future<void>>[
          platformController.setAllowFileAccess(true),
          platformController.setAllowContentAccess(true),
        ]);
      }
      await _controller.loadFile(widget.metadata.path);
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _errorMessage = '$error';
      });
    }
  }

  Future<void> _openExpanded() async {
    if (!widget.metadata.exists) return;
    await _openMetadata(context, widget.metadata, onOpen: widget.onOpen);
  }

  Future<void> _openInBrowser() async {
    if (!widget.metadata.exists) return;
    final isEnglish = LegacyTextLocalizer.isEnglish;
    try {
      final opened = await OmnibotResourceService.openInBrowser(
        sourcePath: widget.metadata.path,
        mimeType: widget.metadata.mimeType,
      );
      if (!mounted || opened) return;
      showToast(
        isEnglish ? 'Browser opening failed' : '浏览器打开失败',
        type: ToastType.error,
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        isEnglish ? 'Browser opening failed: $error' : '浏览器打开失败：$error',
        type: ToastType.error,
      );
    }
  }

  Future<void> _saveToDevice() async {
    if (!widget.metadata.exists) return;
    final isEnglish = LegacyTextLocalizer.isEnglish;
    try {
      final savedUri = await OmnibotResourceService.saveToLocal(
        sourcePath: widget.metadata.path,
        fileName: widget.metadata.title,
        mimeType: widget.metadata.mimeType,
      );
      if (!mounted) return;
      showToast(
        savedUri == null
            ? (isEnglish ? 'Save failed' : '保存失败')
            : (isEnglish ? 'Saved to device' : '已保存到设备'),
        type: savedUri == null ? ToastType.error : ToastType.success,
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        isEnglish ? 'Save failed: $error' : '保存失败：$error',
        type: ToastType.error,
      );
    }
  }

  Future<void> _updateMeasuredHeight() async {
    if (widget.preferredHeight != null) return;
    try {
      final result = await _controller.runJavaScriptReturningResult('''
        Math.max(
          document.documentElement ? document.documentElement.scrollHeight : 0,
          document.body ? document.body.scrollHeight : 0
        );
        ''');
      final height = _parseJavaScriptNumber(result);
      if (!mounted || height == null) return;
      setState(() {
        _measuredHeight = height;
      });
    } catch (_) {}
  }

  double _resolvedHeight() {
    final preferredHeight = widget.preferredHeight;
    if (preferredHeight != null) {
      return preferredHeight.clamp(240.0, 1200.0);
    }
    final measuredHeight = _measuredHeight;
    if (measuredHeight != null) {
      return measuredHeight.clamp(220.0, 420.0);
    }
    return 320.0;
  }

  Widget _buildErrorState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.error_outline_rounded,
              color: Color(0xFF94A3B8),
              size: 28,
            ),
            const SizedBox(height: 10),
            Text(
              LegacyTextLocalizer.isEnglish
                  ? 'HTML preview failed'
                  : 'HTML 预览失败',
              style: const TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: Color(0xFF334155),
              ),
            ),
            if (_errorMessage != null && _errorMessage!.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                _errorMessage!,
                textAlign: TextAlign.center,
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 11,
                  height: 1.4,
                  color: Color(0xFF64748B),
                ),
              ),
            ],
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: _loadHtmlFile,
              icon: const Icon(Icons.refresh_rounded),
              label: Text(LegacyTextLocalizer.isEnglish ? 'Reload' : '重新加载'),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.metadata.exists) {
      return _MissingResourceCard(
        metadata: widget.metadata,
        icon: Icons.language_outlined,
        subtitle: LegacyTextLocalizer.isEnglish
            ? 'HTML file does not exist or is not readable'
            : 'HTML 文件不存在或暂不可读',
        plainStyle: widget.plainStyle,
        onOpen: widget.onOpen,
      );
    }

    return Container(
      decoration: BoxDecoration(
        color: widget.plainStyle ? Colors.transparent : Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: widget.plainStyle
            ? null
            : Border.all(color: const Color(0xFFD8E4F8)),
        boxShadow: widget.plainStyle
            ? null
            : const [
                BoxShadow(
                  color: Color(0x12243258),
                  blurRadius: 10,
                  offset: Offset(0, 4),
                ),
              ],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: SizedBox(
          height: _resolvedHeight(),
          child: Stack(
            fit: StackFit.expand,
            children: [
              if (_errorMessage == null)
                ColoredBox(
                  color: Colors.white,
                  child: ChatDrawerGestureGuard(
                    child: WebViewWidget(
                      key: ValueKey(widget.metadata.path),
                      controller: _controller,
                      gestureRecognizers: _webViewGestureRecognizers,
                    ),
                  ),
                )
              else
                DecoratedBox(
                  decoration: const BoxDecoration(color: Colors.white),
                  child: _buildErrorState(),
                ),
              if (_isLoading)
                const Positioned.fill(
                  child: DecoratedBox(
                    decoration: BoxDecoration(color: Color(0x12FFFFFF)),
                    child: Center(child: CircularProgressIndicator()),
                  ),
                ),
              Positioned(
                top: 10,
                right: 10,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.92),
                    borderRadius: BorderRadius.circular(12),
                    boxShadow: const [
                      BoxShadow(
                        color: Color(0x220F172A),
                        blurRadius: 8,
                        offset: Offset(0, 2),
                      ),
                    ],
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        key: const ValueKey('html-preview-expand'),
                        tooltip: LegacyTextLocalizer.isEnglish
                            ? 'Open larger preview'
                            : '放大预览',
                        onPressed: _openExpanded,
                        visualDensity: VisualDensity.compact,
                        icon: const Icon(Icons.fullscreen_rounded),
                      ),
                      IconButton(
                        key: const ValueKey('html-preview-browser'),
                        tooltip: LegacyTextLocalizer.isEnglish
                            ? 'Open in browser'
                            : '在浏览器打开',
                        onPressed: _openInBrowser,
                        visualDensity: VisualDensity.compact,
                        icon: const Icon(Icons.open_in_browser_rounded),
                      ),
                      IconButton(
                        key: const ValueKey('html-preview-save'),
                        tooltip: LegacyTextLocalizer.isEnglish
                            ? 'Save to device'
                            : '保存到设备',
                        onPressed: _saveToDevice,
                        visualDensity: VisualDensity.compact,
                        icon: const Icon(Icons.download_rounded),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _OmnibotInlineOfficePreviewCard extends StatefulWidget {
  final OmnibotResourceMetadata metadata;
  final bool plainStyle;
  final OmnibotResourceOpenCallback? onOpen;

  const _OmnibotInlineOfficePreviewCard({
    required this.metadata,
    this.plainStyle = false,
    this.onOpen,
  });

  @override
  State<_OmnibotInlineOfficePreviewCard> createState() =>
      _OmnibotInlineOfficePreviewCardState();
}

class _OmnibotInlineOfficePreviewCardState
    extends State<_OmnibotInlineOfficePreviewCard> {
  late Future<OmnibotOfficePreviewData> _previewFuture;

  @override
  void initState() {
    super.initState();
    _previewFuture = _loadPreview();
  }

  @override
  void didUpdateWidget(covariant _OmnibotInlineOfficePreviewCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.metadata.path != widget.metadata.path ||
        oldWidget.metadata.previewKind != widget.metadata.previewKind) {
      _previewFuture = _loadPreview();
    }
  }

  Future<OmnibotOfficePreviewData> _loadPreview() {
    return OmnibotOfficePreviewService.loadPreview(
      path: widget.metadata.path,
      previewKind: widget.metadata.previewKind,
    );
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.metadata.exists) {
      return _MissingResourceCard(
        metadata: widget.metadata,
        icon: _officeIconForKind(widget.metadata.previewKind),
        subtitle: LegacyTextLocalizer.isEnglish
            ? 'File does not exist or is not readable'
            : '文件不存在或暂不可读',
        plainStyle: widget.plainStyle,
        onOpen: widget.onOpen,
      );
    }

    return Container(
      decoration: BoxDecoration(
        color: widget.plainStyle ? Colors.transparent : Colors.white,
        borderRadius: BorderRadius.circular(16),
        border: widget.plainStyle
            ? null
            : Border.all(color: const Color(0xFFD8E4F8)),
        boxShadow: widget.plainStyle
            ? null
            : const [
                BoxShadow(
                  color: Color(0x12243258),
                  blurRadius: 10,
                  offset: Offset(0, 4),
                ),
              ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 12, 10, 8),
            child: Row(
              children: [
                Container(
                  width: 38,
                  height: 38,
                  decoration: BoxDecoration(
                    color: const Color(0xFFEAF3FF),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    _officeIconForKind(widget.metadata.previewKind),
                    size: 20,
                    color: const Color(0xFF1F4ED8),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        widget.metadata.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                          color: Color(0xFF0F172A),
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        _officeKindLabel(widget.metadata.previewKind),
                        style: const TextStyle(
                          fontSize: 11,
                          color: Color(0xFF64748B),
                        ),
                      ),
                    ],
                  ),
                ),
                IconButton(
                  tooltip: LegacyTextLocalizer.isEnglish
                      ? 'Open preview'
                      : '打开预览',
                  onPressed: () => _openMetadata(
                    context,
                    widget.metadata,
                    onOpen: widget.onOpen,
                  ),
                  icon: const Icon(Icons.open_in_new_rounded),
                ),
              ],
            ),
          ),
          Container(
            width: double.infinity,
            margin: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            decoration: BoxDecoration(
              color: const Color(0xFFF8FBFF),
              borderRadius: BorderRadius.circular(14),
            ),
            child: SizedBox(
              height: 220,
              child: FutureBuilder<OmnibotOfficePreviewData>(
                future: _previewFuture,
                builder: (context, snapshot) {
                  if (snapshot.connectionState != ConnectionState.done) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snapshot.hasError || !snapshot.hasData) {
                    return _OfficePreviewErrorView(
                      message:
                          snapshot.error?.toString() ??
                          (LegacyTextLocalizer.isEnglish
                              ? 'Office preview failed'
                              : 'Office 预览失败'),
                      onOpen: () => _openMetadata(
                        context,
                        widget.metadata,
                        onOpen: widget.onOpen,
                      ),
                    );
                  }
                  return _OfficePreviewBody(data: snapshot.data!);
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _OfficePreviewBody extends StatelessWidget {
  final OmnibotOfficePreviewData data;

  const _OfficePreviewBody({required this.data});

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      primary: false,
      padding: const EdgeInsets.fromLTRB(14, 14, 14, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            data.summary,
            style: const TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: Color(0xFF35517A),
            ),
          ),
          const SizedBox(height: 10),
          for (var index = 0; index < data.sections.length; index++) ...[
            if (index > 0) const SizedBox(height: 14),
            _OfficePreviewSectionView(section: data.sections[index]),
          ],
          if (data.truncated) ...[
            const SizedBox(height: 12),
            Text(
              LegacyTextLocalizer.isEnglish
                  ? 'Content is too long. Only showing the first part.'
                  : '内容较多，当前仅展示前面一部分。',
              style: TextStyle(
                fontSize: 11,
                color: Colors.blueGrey.withValues(alpha: 0.78),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _OfficePreviewSectionView extends StatelessWidget {
  final OmnibotOfficePreviewSection section;

  const _OfficePreviewSectionView({required this.section});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0xFFE1EAF8)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            section.title,
            style: const TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: Color(0xFF0F172A),
            ),
          ),
          if (section.subtitle != null && section.subtitle!.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(
              section.subtitle!,
              style: const TextStyle(fontSize: 11, color: Color(0xFF64748B)),
            ),
          ],
          if (section.lines.isNotEmpty) ...[
            const SizedBox(height: 10),
            for (final line in section.lines) ...[
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Padding(
                    padding: EdgeInsets.only(top: 4),
                    child: Icon(
                      Icons.circle,
                      size: 5,
                      color: Color(0xFF4F6FAE),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      line,
                      style: const TextStyle(
                        fontSize: 12,
                        height: 1.45,
                        color: Color(0xFF334155),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
            ],
          ],
          if (section.hasTable) ...[
            const SizedBox(height: 10),
            ChatDrawerGestureGuard(
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: _OfficePreviewTable(rows: section.tableRows),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _OfficePreviewTable extends StatelessWidget {
  final List<List<String>> rows;

  const _OfficePreviewTable({required this.rows});

  @override
  Widget build(BuildContext context) {
    if (rows.isEmpty) {
      return const SizedBox.shrink();
    }

    return Table(
      defaultColumnWidth: const IntrinsicColumnWidth(),
      border: TableBorder.all(color: const Color(0xFFD8E4F8), width: 1),
      children: [
        for (var rowIndex = 0; rowIndex < rows.length; rowIndex++)
          TableRow(
            decoration: BoxDecoration(
              color: rowIndex == 0
                  ? const Color(0xFFF1F6FF)
                  : const Color(0xFFFFFFFF),
            ),
            children: [
              for (final cell in rows[rowIndex])
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 8,
                  ),
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(minWidth: 70),
                    child: Text(
                      cell.isEmpty ? ' ' : cell,
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: rowIndex == 0
                            ? FontWeight.w600
                            : FontWeight.w400,
                        color: const Color(0xFF334155),
                      ),
                    ),
                  ),
                ),
            ],
          ),
      ],
    );
  }
}

class _OfficePreviewErrorView extends StatelessWidget {
  final String message;
  final VoidCallback onOpen;

  const _OfficePreviewErrorView({required this.message, required this.onOpen});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(
              Icons.insert_drive_file_outlined,
              size: 28,
              color: Color(0xFF64748B),
            ),
            const SizedBox(height: 10),
            Text(
              message,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontSize: 12,
                height: 1.5,
                color: Color(0xFF475569),
              ),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: onOpen,
              icon: const Icon(Icons.open_in_new_rounded),
              label: Text(LegacyTextLocalizer.isEnglish ? 'Open file' : '打开文件'),
            ),
          ],
        ),
      ),
    );
  }
}

class _MissingResourceCard extends StatelessWidget {
  final OmnibotResourceMetadata metadata;
  final IconData icon;
  final String subtitle;
  final bool plainStyle;
  final OmnibotResourceOpenCallback? onOpen;

  const _MissingResourceCard({
    required this.metadata,
    required this.icon,
    required this.subtitle,
    this.plainStyle = false,
    this.onOpen,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: () => _openMetadata(context, metadata, onOpen: onOpen),
      borderRadius: BorderRadius.circular(14),
      child: Ink(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: plainStyle ? Colors.transparent : const Color(0xFFFFFBEB),
          borderRadius: BorderRadius.circular(14),
          border: plainStyle
              ? null
              : Border.all(color: const Color(0xFFF2D4A5)),
        ),
        child: Row(
          children: [
            Icon(icon, size: 20, color: const Color(0xFFB45309)),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    metadata.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    subtitle,
                    style: const TextStyle(
                      fontSize: 11,
                      color: Color(0xFF92400E),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            const Icon(
              Icons.open_in_new_rounded,
              size: 18,
              color: Color(0xFF92400E),
            ),
          ],
        ),
      ),
    );
  }
}

Future<void> _openMetadata(
  BuildContext context,
  OmnibotResourceMetadata metadata, {
  OmnibotResourceOpenCallback? onOpen,
}) async {
  if (onOpen != null) {
    await onOpen(context, metadata);
    return;
  }
  if (metadata.isDirectory) {
    await OmnibotResourceService.openWorkspace(
      absolutePath: metadata.path,
      shellPath: metadata.shellPath,
      uri: metadata.uri,
    );
    return;
  }
  await OmnibotResourceService.openFilePath(
    metadata.path,
    uri: metadata.uri,
    title: metadata.title,
    previewKind: metadata.previewKind,
    mimeType: metadata.mimeType,
    shellPath: metadata.shellPath,
  );
}

String _formatDuration(Duration duration) {
  final totalSeconds = duration.inSeconds;
  final minutes = (totalSeconds ~/ 60).toString().padLeft(2, '0');
  final seconds = (totalSeconds % 60).toString().padLeft(2, '0');
  return '$minutes:$seconds';
}

String _fileSizeLabel(String path) {
  try {
    final bytes = File(path).lengthSync();
    if (bytes <= 0) return '';
    if (bytes < 1024) return '${bytes}B';
    if (bytes < 1024 * 1024) {
      return '${(bytes / 1024).toStringAsFixed(1)}KB';
    }
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)}MB';
  } catch (_) {
    return '';
  }
}

double? _parseJavaScriptNumber(Object? value) {
  if (value == null) return null;
  if (value is num) return value.toDouble();
  final raw = value.toString().trim();
  if (raw.isEmpty) return null;
  final normalized = raw.startsWith('"') && raw.endsWith('"')
      ? raw.substring(1, raw.length - 1)
      : raw;
  return double.tryParse(normalized);
}

int _resolvePdfTargetWidthPx(BuildContext context, BoxConstraints constraints) {
  final logicalWidth = constraints.maxWidth.isFinite
      ? constraints.maxWidth
      : MediaQuery.sizeOf(context).width;
  final devicePixelRatio = MediaQuery.devicePixelRatioOf(
    context,
  ).clamp(1.0, 3.0);
  return (logicalWidth * devicePixelRatio).round().clamp(240, 1800);
}

IconData _officeIconForKind(String previewKind) {
  return switch (previewKind) {
    'office_word' => Icons.description_outlined,
    'office_sheet' => Icons.table_chart_outlined,
    'office_slide' => Icons.slideshow_outlined,
    _ => Icons.insert_drive_file_outlined,
  };
}

String _officeKindLabel(String previewKind) {
  return switch (previewKind) {
    'office_word' =>
      LegacyTextLocalizer.isEnglish ? 'Word Document' : 'Word 文档',
    'office_sheet' =>
      LegacyTextLocalizer.isEnglish ? 'Excel Spreadsheet' : 'Excel 表格',
    'office_slide' =>
      LegacyTextLocalizer.isEnglish
          ? 'PowerPoint Presentation'
          : 'PowerPoint 演示文稿',
    _ => LegacyTextLocalizer.isEnglish ? 'Office File' : 'Office 文件',
  };
}
