import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/utils/ui.dart';

/// Represents the source of an image to preview.
sealed class ImagePreviewSource {}

class FileImageSource extends ImagePreviewSource {
  final String path;
  FileImageSource(this.path);
}

class NetworkImageSource extends ImagePreviewSource {
  final String url;
  NetworkImageSource(this.url);
}

class MemoryImageSource extends ImagePreviewSource {
  final Uint8List bytes;
  MemoryImageSource(this.bytes);
}

const double _kDefaultPreviewViewportFraction = 0.8;

/// Lightweight full-screen image preview overlay with pinch-to-zoom and swipe.
///
/// Supports Hero-based zoom transition when [heroTags] is provided.
/// Wrap each source thumbnail in a [Hero] widget with the matching tag.
class ImagePreviewOverlay {
  ImagePreviewOverlay._();

  /// Show preview for a single image.
  static Future<void> show(
    BuildContext context, {
    required ImagePreviewSource source,
    String? heroTag,
  }) {
    return showAll(
      context,
      sources: [source],
      initialIndex: 0,
      heroTags: heroTag != null ? [heroTag] : null,
    );
  }

  /// Show preview for multiple images with swipe navigation.
  ///
  /// [heroTags] should contain one tag per source image, matching the
  /// [Hero] tags on the corresponding thumbnails.
  static Future<void> showAll(
    BuildContext context, {
    required List<ImagePreviewSource> sources,
    int initialIndex = 0,
    List<String>? heroTags,
  }) {
    assert(sources.isNotEmpty);
    assert(heroTags == null || heroTags.length == sources.length);
    return Navigator.of(context).push(
      _ImagePreviewRoute(
        sources: sources,
        initialIndex: initialIndex,
        heroTags: heroTags,
      ),
    );
  }
}

/// Custom route that supports Hero transitions with a transparent background.
class _ImagePreviewRoute extends PageRoute<void> {
  final List<ImagePreviewSource> sources;
  final int initialIndex;
  final List<String>? heroTags;

  _ImagePreviewRoute({
    required this.sources,
    required this.initialIndex,
    this.heroTags,
  });

  @override
  bool get opaque => false;

  @override
  bool get barrierDismissible => false;

  @override
  Color? get barrierColor => null;

  @override
  String? get barrierLabel => null;

  @override
  bool get maintainState => true;

  @override
  Duration get transitionDuration => const Duration(milliseconds: 300);

  @override
  Duration get reverseTransitionDuration => const Duration(milliseconds: 250);

  @override
  Widget buildPage(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
  ) {
    return _ImagePreviewPage(
      sources: sources,
      initialIndex: initialIndex,
      heroTags: heroTags,
      animation: animation,
    );
  }
}

class _ImagePreviewPage extends StatefulWidget {
  final List<ImagePreviewSource> sources;
  final int initialIndex;
  final List<String>? heroTags;
  final Animation<double> animation;

  const _ImagePreviewPage({
    required this.sources,
    required this.initialIndex,
    this.heroTags,
    required this.animation,
  });

  @override
  State<_ImagePreviewPage> createState() => _ImagePreviewPageState();
}

class _ImagePreviewPageState extends State<_ImagePreviewPage>
    with SingleTickerProviderStateMixin {
  late final PageController _pageController;
  late int _currentIndex;
  bool _isZoomed = false;

  // Pull-down-to-dismiss state
  Offset _dismissOffset = Offset.zero;
  Offset _pointerStartPos = Offset.zero;
  int? _activePointerId;
  bool _verticalDragActive = false;
  bool _dragDirectionDecided = false;
  late final AnimationController _snapBackController;
  late Animation<Offset> _snapBackAnimation;

  bool get _hasMultipleImages => widget.sources.length > 1;

  /// Dismiss progress 0.0 (idle) → 1.0 (fully dragged away).
  double get _dismissProgress =>
      (_dismissOffset.dy.abs() / 300).clamp(0.0, 1.0);

  /// Resolve the hero tag for the given page index.
  String? _heroTagAt(int index) {
    if (widget.heroTags == null || index >= widget.heroTags!.length) {
      return null;
    }
    return widget.heroTags![index];
  }

  @override
  void initState() {
    super.initState();
    _currentIndex = widget.initialIndex;
    _pageController = PageController(initialPage: widget.initialIndex);
    _snapBackAnimation = const AlwaysStoppedAnimation(Offset.zero);
    _snapBackController =
        AnimationController(
          vsync: this,
          duration: const Duration(milliseconds: 200),
        )..addListener(() {
          setState(() => _dismissOffset = _snapBackAnimation.value);
        });
  }

  @override
  void dispose() {
    _pageController.dispose();
    _snapBackController.dispose();
    super.dispose();
  }

  void _dismiss() => Navigator.of(context).pop();

  // --------------- Pointer tracking (Listener) ---------------

  void _onPointerDown(PointerDownEvent event) {
    if (_isZoomed) return;

    if (_activePointerId != null) {
      // Second finger appeared → cancel any in-progress dismiss drag.
      _cancelDrag();
      return;
    }

    _snapBackController.stop();
    _activePointerId = event.pointer;
    _pointerStartPos = event.position;
    _dragDirectionDecided = false;
    _verticalDragActive = false;
  }

  void _onPointerMove(PointerMoveEvent event) {
    if (event.pointer != _activePointerId || _isZoomed) return;

    // Decide drag direction once the pointer moves far enough.
    if (!_dragDirectionDecided) {
      final delta = event.position - _pointerStartPos;
      if (delta.distance < 10) return;
      _dragDirectionDecided = true;
      _verticalDragActive = delta.dy.abs() > delta.dx.abs();
      if (!_verticalDragActive) return;
    }

    if (!_verticalDragActive) return;

    setState(() {
      _dismissOffset += Offset(0, event.delta.dy);
    });
  }

  void _onPointerUp(PointerUpEvent event) {
    if (event.pointer != _activePointerId) return;
    _activePointerId = null;

    if (!_verticalDragActive) return;
    _verticalDragActive = false;

    if (_dismissProgress > 0.3) {
      // Hero stays active so it flies from the dragged position back to
      // the thumbnail during the route's reverse animation.
      _dismiss();
    } else {
      _animateSnapBack();
    }
  }

  void _onPointerCancel(PointerCancelEvent event) {
    if (event.pointer != _activePointerId) return;
    _cancelDrag();
  }

  void _cancelDrag() {
    _activePointerId = null;
    if (_verticalDragActive && _dismissOffset != Offset.zero) {
      _verticalDragActive = false;
      _animateSnapBack();
    } else {
      _verticalDragActive = false;
    }
  }

  void _animateSnapBack() {
    _snapBackAnimation = Tween<Offset>(begin: _dismissOffset, end: Offset.zero)
        .animate(
          CurvedAnimation(parent: _snapBackController, curve: Curves.easeOut),
        );
    _snapBackController.forward(from: 0);
  }

  // --------------- Build ---------------

  @override
  Widget build(BuildContext context) {
    final bottomPadding = MediaQuery.of(context).padding.bottom;
    final scale = 1.0 - _dismissProgress * 0.15;

    return Listener(
      onPointerDown: _onPointerDown,
      onPointerMove: _onPointerMove,
      onPointerUp: _onPointerUp,
      onPointerCancel: _onPointerCancel,
      child: AnimatedBuilder(
        animation: widget.animation,
        builder: (context, child) {
          final bgOpacity =
              0.87 * widget.animation.value * (1.0 - _dismissProgress);
          return ColoredBox(
            color: Color.fromRGBO(0, 0, 0, bgOpacity),
            child: child,
          );
        },
        child: Transform(
          transform: Matrix4.identity()
            ..translate(_dismissOffset.dx, _dismissOffset.dy)
            ..scale(scale, scale),
          alignment: Alignment.center,
          child: Stack(
            children: [
              // Image page view (swipeable)
              PageView.builder(
                controller: _pageController,
                itemCount: widget.sources.length,
                physics: _isZoomed
                    ? const NeverScrollableScrollPhysics()
                    : const BouncingScrollPhysics(),
                onPageChanged: (index) => setState(() => _currentIndex = index),
                itemBuilder: (context, index) {
                  // Only the currently visible page gets a Hero tag to
                  // avoid duplicate-tag conflicts from PageView caching.
                  final tag = index == _currentIndex ? _heroTagAt(index) : null;
                  return OmnibotInteractiveImageView(
                    source: widget.sources[index],
                    onTap: _dismiss,
                    onScaleChanged: (zoomed) {
                      if (_isZoomed != zoomed) {
                        setState(() => _isZoomed = zoomed);
                      }
                    },
                    heroTag: tag,
                    enableFileShareOnLongPress:
                        widget.sources[index] is FileImageSource,
                  );
                },
              ),

              // Page indicator
              if (_hasMultipleImages)
                Positioned(
                  bottom: bottomPadding + 20,
                  left: 0,
                  right: 0,
                  child: FadeTransition(
                    opacity: widget.animation,
                    child: _buildPageIndicator(),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPageIndicator() {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: List.generate(widget.sources.length, (index) {
        final isActive = index == _currentIndex;
        return AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          margin: const EdgeInsets.symmetric(horizontal: 3),
          width: isActive ? 18 : 6,
          height: 6,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(3),
            color: isActive ? Colors.white : Colors.white38,
          ),
        );
      }),
    );
  }
}

class OmnibotInteractiveImageView extends StatefulWidget {
  final ImagePreviewSource source;
  final VoidCallback? onTap;
  final ValueChanged<bool>? onScaleChanged;
  final String? heroTag;
  final bool enableFileShareOnLongPress;
  final double viewportFraction;
  final Key? previewBoundsKey;

  const OmnibotInteractiveImageView({
    super.key,
    required this.source,
    this.onTap,
    this.onScaleChanged,
    this.heroTag,
    this.enableFileShareOnLongPress = false,
    this.viewportFraction = _kDefaultPreviewViewportFraction,
    this.previewBoundsKey,
  });

  @override
  State<OmnibotInteractiveImageView> createState() =>
      _OmnibotInteractiveImageViewState();
}

class _OmnibotInteractiveImageViewState
    extends State<OmnibotInteractiveImageView> {
  final TransformationController _transformController =
      TransformationController();
  ImageStream? _imageStream;
  ImageStreamListener? _imageStreamListener;
  Size? _intrinsicImageSize;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _resolveIntrinsicImageSize();
  }

  @override
  void didUpdateWidget(covariant OmnibotInteractiveImageView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_isSameSource(oldWidget.source, widget.source)) {
      return;
    }
    _removeImageStreamListener();
    _intrinsicImageSize = null;
    _transformController.value = Matrix4.identity();
    widget.onScaleChanged?.call(false);
    _resolveIntrinsicImageSize();
  }

  @override
  void dispose() {
    _removeImageStreamListener();
    _transformController.dispose();
    super.dispose();
  }

  Future<void> _handleLongPress() async {
    if (!widget.enableFileShareOnLongPress) {
      return;
    }
    final source = widget.source;
    if (source is! FileImageSource) {
      return;
    }
    final metadata = OmnibotResourceService.describePath(source.path);
    try {
      final shared = await OmnibotResourceService.shareFile(
        sourcePath: source.path,
        fileName: metadata.title,
        mimeType: metadata.mimeType,
      );
      if (!shared) {
        showToast(LegacyTextLocalizer.isEnglish ? 'Share failed, please try again later' : '分享失败，请稍后重试', type: ToastType.error);
      }
    } catch (error) {
      showToast(LegacyTextLocalizer.isEnglish ? 'Share failed: $error' : '分享失败：$error', type: ToastType.error);
    }
  }

  @override
  Widget build(BuildContext context) {
    Widget image = _buildImage(widget.source);

    if (widget.heroTag != null) {
      image = Hero(
        tag: widget.heroTag!,
        // Animate border radius from rounded thumbnail to full-screen
        flightShuttleBuilder: (_, animation, __, ___, ____) {
          return AnimatedBuilder(
            animation: animation,
            builder: (context, child) {
              return ClipRRect(
                borderRadius: BorderRadius.lerp(
                  BorderRadius.circular(12),
                  BorderRadius.zero,
                  animation.value,
                )!,
                child: child,
              );
            },
            child: _buildImage(widget.source),
          );
        },
        child: image,
      );
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final boundsSize = _resolvePreviewBounds(constraints.biggest);
        final imageBounds = SizedBox(
          key: widget.previewBoundsKey,
          width: boundsSize.width,
          height: boundsSize.height,
          child: FittedBox(fit: BoxFit.scaleDown, child: image),
        );
        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: widget.onTap,
          onLongPress: widget.enableFileShareOnLongPress
              ? _handleLongPress
              : null,
          onDoubleTapDown: (details) => _handleDoubleTap(details),
          child: InteractiveViewer(
            transformationController: _transformController,
            minScale: 1.0,
            maxScale: 5.0,
            onInteractionEnd: (_) {
              final scale = _transformController.value.getMaxScaleOnAxis();
              widget.onScaleChanged?.call(scale > 1.05);
            },
            child: Center(child: imageBounds),
          ),
        );
      },
    );
  }

  void _resolveIntrinsicImageSize() {
    final provider = _imageProvider(widget.source);
    final stream = provider.resolve(createLocalImageConfiguration(context));
    if (_imageStream?.key == stream.key) {
      return;
    }
    _removeImageStreamListener();
    _imageStream = stream;
    _imageStreamListener = ImageStreamListener((imageInfo, _) {
      if (!mounted) {
        return;
      }
      final scale = imageInfo.scale <= 0 ? 1.0 : imageInfo.scale;
      final nextSize = Size(
        imageInfo.image.width / scale,
        imageInfo.image.height / scale,
      );
      if (_intrinsicImageSize == nextSize) {
        return;
      }
      setState(() => _intrinsicImageSize = nextSize);
    });
    stream.addListener(_imageStreamListener!);
  }

  void _removeImageStreamListener() {
    final stream = _imageStream;
    final listener = _imageStreamListener;
    if (stream != null && listener != null) {
      stream.removeListener(listener);
    }
    _imageStream = null;
    _imageStreamListener = null;
  }

  Size _resolvePreviewBounds(Size availableSize) {
    final maxWidth = availableSize.width.isFinite ? availableSize.width : 0.0;
    final maxHeight = availableSize.height.isFinite
        ? availableSize.height
        : 0.0;
    if (maxWidth <= 0 || maxHeight <= 0) {
      return Size.zero;
    }
    final intrinsicSize = _intrinsicImageSize;
    if (intrinsicSize == null ||
        intrinsicSize.width <= 0 ||
        intrinsicSize.height <= 0) {
      return Size(maxWidth, maxHeight);
    }

    final fittedSize = applyBoxFit(
      BoxFit.scaleDown,
      intrinsicSize,
      Size(maxWidth, maxHeight),
    ).destination;
    final fillsViewportHeight = fittedSize.height >= maxHeight - 0.5;
    if (!fillsViewportHeight) {
      return fittedSize;
    }
    return Size(
      fittedSize.width * widget.viewportFraction,
      fittedSize.height * widget.viewportFraction,
    );
  }

  void _handleDoubleTap(TapDownDetails details) {
    final currentScale = _transformController.value.getMaxScaleOnAxis();
    if (currentScale > 1.05) {
      // Reset to original
      _transformController.value = Matrix4.identity();
      widget.onScaleChanged?.call(false);
    } else {
      // Zoom to 2.5x at tap position
      final position = details.localPosition;
      const targetScale = 2.5;
      final zoomed = Matrix4.identity()
        ..translate(
          -position.dx * (targetScale - 1),
          -position.dy * (targetScale - 1),
        )
        ..scale(targetScale);
      _transformController.value = zoomed;
      widget.onScaleChanged?.call(true);
    }
  }

  static Widget _buildImage(ImagePreviewSource source) {
    return switch (source) {
      FileImageSource(path: final p) => Image.file(
        File(p),
        fit: BoxFit.contain,
        errorBuilder: (_, __, ___) => _buildError(),
      ),
      NetworkImageSource(url: final u) => Image.network(
        u,
        fit: BoxFit.contain,
        errorBuilder: (_, __, ___) => _buildError(),
      ),
      MemoryImageSource(bytes: final b) => Image.memory(
        b,
        fit: BoxFit.contain,
        errorBuilder: (_, __, ___) => _buildError(),
      ),
    };
  }

  static ImageProvider<Object> _imageProvider(ImagePreviewSource source) {
    return switch (source) {
      FileImageSource(path: final p) => FileImage(File(p)),
      NetworkImageSource(url: final u) => NetworkImage(u),
      MemoryImageSource(bytes: final b) => MemoryImage(b),
    };
  }

  static bool _isSameSource(ImagePreviewSource a, ImagePreviewSource b) {
    return switch ((a, b)) {
      (FileImageSource(path: final ap), FileImageSource(path: final bp)) =>
        ap == bp,
      (NetworkImageSource(url: final au), NetworkImageSource(url: final bu)) =>
        au == bu,
      (
        MemoryImageSource(bytes: final ab),
        MemoryImageSource(bytes: final bb),
      ) =>
        identical(ab, bb),
      _ => false,
    };
  }

  static Widget _buildError() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Icon(Icons.broken_image_outlined, size: 48, color: Colors.white54),
        const SizedBox(height: 8),
        Text(LegacyTextLocalizer.isEnglish ? 'Unable to load image' : '无法加载图片', style: const TextStyle(color: Colors.white54, fontSize: 14)),
      ],
    );
  }
}
