import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/gestures.dart';
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
const int _kMaxPreviewFileBytes = 25 * 1024 * 1024;
const int _kMaxDecodedImageDimension = 2048;
const String _kPreviewDirectoryPrefix = 'omnibot_image_preview_';
const Duration _kPreviewDirectoryTtl = Duration(hours: 24);
const Duration _kNetworkConnectTimeout = Duration(seconds: 15);
const Duration _kNetworkResponseTimeout = Duration(seconds: 30);
const Duration _kNetworkBodyDeadline = Duration(seconds: 45);

enum _PreviewLoadState { idle, loading, ready, failed }

class _PreviewPreparation {
  _PreviewPreparation(this.generation);

  final int generation;
  bool cancelled = false;
  HttpClient? networkClient;
  HttpClientRequest? networkRequest;

  void cancel() {
    cancelled = true;
    networkRequest?.abort();
    networkRequest = null;
    networkClient?.close(force: true);
    networkClient = null;
  }
}

class _PreparedPreview {
  const _PreparedPreview({
    required this.intrinsicSize,
    this.snapshot,
    this.memoryBytes,
  });

  final Size intrinsicSize;
  final File? snapshot;
  final Uint8List? memoryBytes;
}

class _PreviewPreparationCancelled implements Exception {
  const _PreviewPreparationCancelled();
}

class _PreviewPayloadRejected implements Exception {
  const _PreviewPayloadRejected();
}

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

  @visibleForTesting
  static Future<void> debugCleanupExpiredPreviewDirectories(DateTime cutoff) {
    return _OmnibotInteractiveImageViewState._cleanupExpiredPreviewDirectories(
      cutoffOverride: cutoff,
    );
  }

  @override
  State<OmnibotInteractiveImageView> createState() =>
      _OmnibotInteractiveImageViewState();
}

class _OmnibotInteractiveImageViewState
    extends State<OmnibotInteractiveImageView> {
  static final Set<String> _activeSnapshotDirectories = <String>{};

  final TransformationController _transformController =
      TransformationController();
  final Map<String, int> _snapshotShareLeases = <String, int>{};
  final Set<String> _snapshotsPendingDeletion = <String>{};

  Size? _intrinsicImageSize;
  File? _previewSnapshot;
  Uint8List? _memoryPreviewBytes;
  _PreviewLoadState _loadState = _PreviewLoadState.idle;
  _PreviewPreparation? _activePreparation;
  int _sourceGeneration = 0;
  bool _staleDirectoryCleanupStarted = false;
  Timer? _longPressTimer;
  int? _longPressPointer;
  Offset? _longPressOrigin;
  Duration? _longPressStartedAt;
  bool _longPressTriggered = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _prepareCurrentSourceOnce();
  }

  @override
  void didUpdateWidget(covariant OmnibotInteractiveImageView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_isSameSource(oldWidget.source, widget.source)) {
      return;
    }
    final oldSnapshot = _previewSnapshot;
    _sourceGeneration += 1;
    _activePreparation?.cancel();
    _activePreparation = null;
    _cancelLongPressTimer();
    _longPressTriggered = false;
    _intrinsicImageSize = null;
    _previewSnapshot = null;
    _memoryPreviewBytes = null;
    _loadState = _PreviewLoadState.idle;
    _transformController.value = Matrix4.identity();
    widget.onScaleChanged?.call(false);
    // Wait until this rebuild has detached the old Image listener before
    // evicting and deleting its snapshot. This matters on platforms that keep
    // decoded file handles open while an Image widget is still mounted.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(_deletePreviewSnapshot(oldSnapshot));
    });
    _prepareCurrentSourceOnce();
  }

  @override
  void dispose() {
    _cancelLongPressTimer();
    _sourceGeneration += 1;
    _activePreparation?.cancel();
    _activePreparation = null;
    unawaited(_deletePreviewSnapshot(_previewSnapshot));
    _transformController.dispose();
    super.dispose();
  }

  Future<void> _handleLongPress() async {
    final source = widget.source;
    final snapshot = _previewSnapshot;
    final generation = _sourceGeneration;
    if (!_canShareFileSnapshot ||
        source is! FileImageSource ||
        snapshot == null) {
      return;
    }
    final metadata = OmnibotResourceService.describePath(source.path);
    final snapshotPath = snapshot.path;
    _acquireSnapshotShareLease(snapshotPath);
    try {
      if (!mounted ||
          generation != _sourceGeneration ||
          !_isSameSource(source, widget.source) ||
          _loadState != _PreviewLoadState.ready ||
          _previewSnapshot?.path != snapshotPath) {
        return;
      }
      final shared = await OmnibotResourceService.shareFile(
        sourcePath: snapshotPath,
        fileName: _safeShareFileName(source.path),
        mimeType: metadata.mimeType,
      );
      if (!shared) {
        showToast(
          LegacyTextLocalizer.isEnglish
              ? 'Share failed, please try again later'
              : '分享失败，请稍后重试',
          type: ToastType.error,
        );
      }
    } on Exception {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Share failed, please try again later'
            : '分享失败，请稍后重试',
        type: ToastType.error,
      );
    } finally {
      _releaseSnapshotShareLease(snapshotPath);
    }
  }

  bool get _canShareFileSnapshot =>
      widget.enableFileShareOnLongPress &&
      widget.source is FileImageSource &&
      _loadState == _PreviewLoadState.ready &&
      _previewSnapshot != null;

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
        final imageBounds = Listener(
          key: widget.previewBoundsKey,
          behavior: HitTestBehavior.opaque,
          onPointerDown: _startLongPressTimer,
          onPointerMove: _handleLongPressMove,
          onPointerUp: _finishLongPressPointer,
          onPointerCancel: _finishLongPressPointer,
          child: SizedBox(
            width: boundsSize.width,
            height: boundsSize.height,
            child: FittedBox(fit: BoxFit.scaleDown, child: image),
          ),
        );
        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: _handleTap,
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

  void _prepareCurrentSourceOnce() {
    if (_loadState != _PreviewLoadState.idle) {
      return;
    }
    if (!_staleDirectoryCleanupStarted) {
      _staleDirectoryCleanupStarted = true;
      unawaited(_cleanupExpiredPreviewDirectories());
    }

    final preparation = _PreviewPreparation(_sourceGeneration);
    _activePreparation = preparation;
    _loadState = _PreviewLoadState.loading;
    unawaited(_prepareCurrentSource(preparation, widget.source));
  }

  Future<void> _prepareCurrentSource(
    _PreviewPreparation preparation,
    ImagePreviewSource source,
  ) async {
    _PreparedPreview? prepared;
    try {
      prepared = switch (source) {
        FileImageSource(path: final path) => await _prepareFileSource(
          path,
          preparation,
        ),
        NetworkImageSource(url: final url) => await _prepareNetworkSource(
          url,
          preparation,
        ),
        MemoryImageSource(bytes: final bytes) => await _prepareMemorySource(
          bytes,
          preparation,
        ),
      };
    } on _PreviewPreparationCancelled {
      return;
    } on Exception {
      if (_isCurrentPreparation(preparation, source)) {
        setState(() {
          _activePreparation = null;
          _loadState = _PreviewLoadState.failed;
          _intrinsicImageSize = null;
          _memoryPreviewBytes = null;
        });
      }
      return;
    } finally {
      preparation.networkClient?.close(force: true);
      preparation.networkClient = null;
    }

    final completed = prepared;
    if (!_isCurrentPreparation(preparation, source)) {
      await _deletePreviewSnapshot(completed.snapshot);
      return;
    }
    setState(() {
      _activePreparation = null;
      _previewSnapshot = completed.snapshot;
      _memoryPreviewBytes = completed.memoryBytes;
      _intrinsicImageSize = completed.intrinsicSize;
      _loadState = _PreviewLoadState.ready;
    });
  }

  bool _isCurrentPreparation(
    _PreviewPreparation preparation,
    ImagePreviewSource source,
  ) {
    return mounted &&
        !preparation.cancelled &&
        identical(_activePreparation, preparation) &&
        preparation.generation == _sourceGeneration &&
        _isSameSource(source, widget.source);
  }

  void _startLongPressTimer(PointerDownEvent event) {
    _cancelLongPressTimer();
    _longPressTriggered = false;
    if (!_canShareFileSnapshot) {
      return;
    }
    _longPressPointer = event.pointer;
    _longPressOrigin = event.position;
    _longPressStartedAt = event.timeStamp;
    _longPressTimer = Timer(kLongPressTimeout, _triggerLongPress);
  }

  void _handleLongPressMove(PointerMoveEvent event) {
    final origin = _longPressOrigin;
    if (event.pointer == _longPressPointer &&
        origin != null &&
        (event.position - origin).distance > kTouchSlop) {
      _cancelLongPressTimer();
    }
  }

  void _finishLongPressPointer(PointerEvent event) {
    if (event.pointer != _longPressPointer) {
      return;
    }
    final startedAt = _longPressStartedAt;
    final heldLongEnough =
        event is PointerUpEvent &&
        startedAt != null &&
        event.timeStamp - startedAt >= kLongPressTimeout;
    if (!_longPressTriggered && heldLongEnough) {
      _triggerLongPress();
      return;
    }
    _cancelLongPressTimer();
  }

  void _triggerLongPress() {
    if (_longPressTriggered ||
        _longPressPointer == null ||
        !_canShareFileSnapshot) {
      return;
    }
    _longPressTimer?.cancel();
    _longPressTimer = null;
    _longPressTriggered = true;
    unawaited(_handleLongPress());
  }

  void _cancelLongPressTimer() {
    _longPressTimer?.cancel();
    _longPressTimer = null;
    _longPressPointer = null;
    _longPressOrigin = null;
    _longPressStartedAt = null;
  }

  void _handleTap() {
    if (_longPressTriggered) {
      _longPressTriggered = false;
      _cancelLongPressTimer();
      return;
    }
    widget.onTap?.call();
  }

  Future<_PreparedPreview> _prepareMemorySource(
    Uint8List bytes,
    _PreviewPreparation preparation,
  ) async {
    // Enforce the encoded-byte ceiling before ImmutableBuffer allocates native
    // memory. Copying also prevents callers from mutating the displayed bytes.
    if (bytes.isEmpty || bytes.lengthInBytes > _kMaxPreviewFileBytes) {
      throw const _PreviewPayloadRejected();
    }
    _throwIfPreparationCancelled(preparation);
    final immutableBytes = Uint8List.fromList(bytes);
    final size = await _validateEncodedMemoryImage(immutableBytes);
    _throwIfPreparationCancelled(preparation);
    return _PreparedPreview(intrinsicSize: size, memoryBytes: immutableBytes);
  }

  Future<_PreparedPreview> _prepareFileSource(
    String path,
    _PreviewPreparation preparation,
  ) async {
    final source = File(path);
    final declaredLength = await source.length();
    if (declaredLength <= 0 || declaredLength > _kMaxPreviewFileBytes) {
      throw const _PreviewPayloadRejected();
    }
    _throwIfPreparationCancelled(preparation);

    final snapshot = await _copyToManagedSnapshot(
      source.openRead(0, _kMaxPreviewFileBytes + 1),
      preparation,
    );
    try {
      final size = await _validateEncodedFileImage(snapshot);
      _throwIfPreparationCancelled(preparation);
      return _PreparedPreview(intrinsicSize: size, snapshot: snapshot);
    } on Exception {
      await _deletePreviewSnapshot(snapshot);
      rethrow;
    }
  }

  Future<_PreparedPreview> _prepareNetworkSource(
    String url,
    _PreviewPreparation preparation,
  ) async {
    final uri = Uri.tryParse(url);
    if (uri == null ||
        (uri.scheme != 'http' && uri.scheme != 'https') ||
        uri.host.isEmpty) {
      throw const _PreviewPayloadRejected();
    }

    final client = HttpClient()..connectionTimeout = _kNetworkConnectTimeout;
    preparation.networkClient = client;
    try {
      final request = await client.getUrl(uri);
      preparation.networkRequest = request;
      _throwIfPreparationCancelled(preparation);
      final response = await request.close().timeout(_kNetworkResponseTimeout);
      preparation.networkRequest = null;
      if (response.statusCode < HttpStatus.ok ||
          response.statusCode >= HttpStatus.multipleChoices) {
        throw HttpException(
          'Image download failed with HTTP ${response.statusCode}',
          uri: uri,
        );
      }
      final declaredLength = response.contentLength;
      if (declaredLength == 0 || declaredLength > _kMaxPreviewFileBytes) {
        throw const _PreviewPayloadRejected();
      }
      _throwIfPreparationCancelled(preparation);

      var bodyDeadlineExceeded = false;
      final bodyDeadline = Timer(_kNetworkBodyDeadline, () {
        bodyDeadlineExceeded = true;
        client.close(force: true);
      });
      late final File snapshot;
      try {
        snapshot = await _copyToManagedSnapshot(
          response.timeout(_kNetworkResponseTimeout),
          preparation,
        );
        if (bodyDeadlineExceeded) {
          await _deletePreviewSnapshot(snapshot);
          throw TimeoutException('Image response body deadline exceeded');
        }
      } finally {
        bodyDeadline.cancel();
      }
      try {
        final size = await _validateEncodedFileImage(snapshot);
        _throwIfPreparationCancelled(preparation);
        return _PreparedPreview(intrinsicSize: size, snapshot: snapshot);
      } on Exception {
        await _deletePreviewSnapshot(snapshot);
        rethrow;
      }
    } finally {
      preparation.networkRequest = null;
      if (identical(preparation.networkClient, client)) {
        preparation.networkClient = null;
      }
      client.close(force: true);
    }
  }

  Future<File> _copyToManagedSnapshot(
    Stream<List<int>> source,
    _PreviewPreparation preparation,
  ) async {
    final directory = await _createManagedPreviewDirectory();
    final snapshot = File(
      '${directory.path}${Platform.pathSeparator}image.bin',
    );
    RandomAccessFile? output;
    try {
      output = await snapshot.open(mode: FileMode.writeOnly);
      var copiedBytes = 0;
      await for (final chunk in source) {
        _throwIfPreparationCancelled(preparation);
        copiedBytes += chunk.length;
        if (copiedBytes > _kMaxPreviewFileBytes) {
          throw const _PreviewPayloadRejected();
        }
        // Await every write so a fast or hostile source cannot queue the full
        // response in memory before the encoded-byte ceiling is enforced.
        await output.writeFrom(chunk);
      }
      _throwIfPreparationCancelled(preparation);
      if (copiedBytes <= 0) {
        throw const _PreviewPayloadRejected();
      }
      await output.flush();
      await output.close();
      output = null;
      return snapshot;
    } on Exception {
      if (output != null) {
        try {
          await output.close();
        } on Exception {
          // Continue with best-effort managed snapshot cleanup.
        }
      }
      await _deleteManagedPreviewDirectory(directory);
      rethrow;
    }
  }

  static void _throwIfPreparationCancelled(_PreviewPreparation preparation) {
    if (preparation.cancelled) {
      throw const _PreviewPreparationCancelled();
    }
  }

  Future<Size> _validateEncodedMemoryImage(Uint8List bytes) async {
    final buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
    return _validateEncodedBuffer(buffer);
  }

  Future<Size> _validateEncodedFileImage(File file) async {
    final buffer = await ui.ImmutableBuffer.fromFilePath(file.path);
    return _validateEncodedBuffer(buffer);
  }

  Future<Size> _validateEncodedBuffer(ui.ImmutableBuffer buffer) async {
    try {
      final descriptor = await ui.ImageDescriptor.encoded(buffer);
      try {
        if (descriptor.width <= 0 || descriptor.height <= 0) {
          throw const _PreviewPayloadRejected();
        }
        final scale = math.min(
          1.0,
          math.min(
            _kMaxDecodedImageDimension / descriptor.width,
            _kMaxDecodedImageDimension / descriptor.height,
          ),
        );
        final targetWidth = math.max(1, (descriptor.width * scale).round());
        final targetHeight = math.max(1, (descriptor.height * scale).round());
        final codec = await descriptor.instantiateCodec(
          targetWidth: targetWidth,
          targetHeight: targetHeight,
        );
        try {
          final frame = await codec.getNextFrame();
          frame.image.dispose();
        } finally {
          codec.dispose();
        }
        return Size(descriptor.width.toDouble(), descriptor.height.toDouble());
      } finally {
        descriptor.dispose();
      }
    } finally {
      buffer.dispose();
    }
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

  Widget _buildImage(ImagePreviewSource source) {
    if (_loadState == _PreviewLoadState.failed) {
      return _buildError();
    }
    if (_loadState != _PreviewLoadState.ready) {
      return const Center(
        child: Icon(Icons.image_outlined, size: 48, color: Colors.white38),
      );
    }

    final generation = _sourceGeneration;
    return Image(
      image: _imageProvider(source),
      fit: BoxFit.contain,
      errorBuilder: (_, __, ___) {
        _markImageProviderFailed(source, generation);
        return _buildError();
      },
    );
  }

  ImageProvider<Object> _imageProvider(ImagePreviewSource source) {
    if (source is FileImageSource || source is NetworkImageSource) {
      final snapshot = _previewSnapshot;
      if (snapshot == null) {
        throw StateError('Snapshot preview is not ready');
      }
      return _boundedImageProvider(FileImage(snapshot));
    }
    final bytes = _memoryPreviewBytes;
    if (bytes == null) {
      throw StateError('Memory preview is not ready');
    }
    return _boundedImageProvider(MemoryImage(bytes));
  }

  static ImageProvider<Object> _boundedImageProvider(
    ImageProvider<Object> provider,
  ) {
    return ResizeImage(
      provider,
      width: _kMaxDecodedImageDimension,
      height: _kMaxDecodedImageDimension,
      policy: ResizeImagePolicy.fit,
    );
  }

  Future<void> _deletePreviewSnapshot(File? snapshot) async {
    if (snapshot == null) {
      return;
    }
    final snapshotPath = snapshot.path;
    if ((_snapshotShareLeases[snapshotPath] ?? 0) > 0) {
      _snapshotsPendingDeletion.add(snapshotPath);
      return;
    }
    try {
      await _boundedImageProvider(FileImage(snapshot)).evict();
    } on Exception {
      // Continue with best-effort temporary-file cleanup.
    }
    await _deleteManagedPreviewDirectory(snapshot.parent);
  }

  void _acquireSnapshotShareLease(String snapshotPath) {
    _snapshotShareLeases.update(
      snapshotPath,
      (count) => count + 1,
      ifAbsent: () => 1,
    );
  }

  void _releaseSnapshotShareLease(String snapshotPath) {
    final count = _snapshotShareLeases[snapshotPath] ?? 0;
    if (count > 1) {
      _snapshotShareLeases[snapshotPath] = count - 1;
      return;
    }
    _snapshotShareLeases.remove(snapshotPath);
    if (_snapshotsPendingDeletion.remove(snapshotPath)) {
      unawaited(_deletePreviewSnapshot(File(snapshotPath)));
    }
  }

  void _markImageProviderFailed(ImagePreviewSource source, int generation) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted ||
          generation != _sourceGeneration ||
          !_isSameSource(source, widget.source) ||
          _loadState != _PreviewLoadState.ready) {
        return;
      }
      final failedSnapshot = _previewSnapshot;
      setState(() {
        _previewSnapshot = null;
        _memoryPreviewBytes = null;
        _intrinsicImageSize = null;
        _loadState = _PreviewLoadState.failed;
      });
      unawaited(_deletePreviewSnapshot(failedSnapshot));
    });
  }

  static Future<Directory> _createManagedPreviewDirectory() async {
    final directory = await Directory.systemTemp.createTemp(
      _kPreviewDirectoryPrefix,
    );
    if (!_isManagedPreviewDirectory(directory)) {
      throw const FileSystemException(
        'Refusing an image preview directory outside the system temp root',
      );
    }
    _activeSnapshotDirectories.add(_normalizedDirectoryPath(directory));
    return directory;
  }

  static Future<void> _cleanupExpiredPreviewDirectories({
    DateTime? cutoffOverride,
  }) async {
    final tempRoot = Directory.systemTemp;
    final cutoff =
        cutoffOverride ?? DateTime.now().subtract(_kPreviewDirectoryTtl);
    try {
      await for (final entity in tempRoot.list(followLinks: false)) {
        try {
          final type = await FileSystemEntity.type(
            entity.path,
            followLinks: false,
          );
          if (type != FileSystemEntityType.directory) {
            continue;
          }
          final directory = Directory(entity.path);
          if (!_isManagedPreviewDirectory(directory)) {
            continue;
          }
          final normalizedPath = _normalizedDirectoryPath(directory);
          if (_activeSnapshotDirectories.contains(normalizedPath)) {
            continue;
          }
          final stat = await directory.stat();
          if (!stat.modified.isBefore(cutoff)) {
            continue;
          }
          await _deleteManagedPreviewDirectory(directory);
        } on FileSystemException {
          // A concurrent cleanup or replacement is harmless.
        }
      }
    } on FileSystemException {
      // Preview remains usable when best-effort stale cleanup is unavailable.
    }
  }

  static Future<void> _deleteManagedPreviewDirectory(
    Directory directory,
  ) async {
    if (!_isManagedPreviewDirectory(directory)) {
      return;
    }
    _activeSnapshotDirectories.remove(_normalizedDirectoryPath(directory));
    for (var attempt = 0; attempt < 10; attempt += 1) {
      try {
        await _deleteTreeWithoutFollowingLinks(directory.path);
        return;
      } on FileSystemException {
        if (attempt == 9) {
          return;
        }
        await Future<void>.delayed(const Duration(milliseconds: 50));
      }
    }
  }

  static Future<void> _deleteTreeWithoutFollowingLinks(String path) async {
    final type = await FileSystemEntity.type(path, followLinks: false);
    if (type == FileSystemEntityType.notFound) {
      return;
    }
    if (type != FileSystemEntityType.directory) {
      if (type == FileSystemEntityType.link) {
        await Link(path).delete();
      } else {
        await File(path).delete();
      }
      return;
    }
    final directory = Directory(path);
    await for (final child in directory.list(followLinks: false)) {
      await _deleteTreeWithoutFollowingLinks(child.path);
    }
    await directory.delete();
  }

  static bool _isManagedPreviewDirectory(Directory directory) {
    final absoluteDirectory = directory.absolute;
    final absoluteTempRoot = Directory.systemTemp.absolute;
    if (_normalizedDirectoryPath(absoluteDirectory.parent) !=
        _normalizedDirectoryPath(absoluteTempRoot)) {
      return false;
    }
    final normalized = absoluteDirectory.path.replaceAll('\\', '/');
    final name = normalized.split('/').last;
    return name.startsWith(_kPreviewDirectoryPrefix) &&
        name.length > _kPreviewDirectoryPrefix.length;
  }

  static String _normalizedDirectoryPath(Directory directory) {
    var path = directory.absolute.path.replaceAll('\\', '/');
    while (path.length > 1 && path.endsWith('/')) {
      path = path.substring(0, path.length - 1);
    }
    return Platform.isWindows ? path.toLowerCase() : path;
  }

  static String _safeShareFileName(String originalPath) {
    final normalized = originalPath.replaceAll('\\', '/');
    var name = normalized.split('/').last.trim();
    name = name.replaceAll(RegExp(r'[\x00-\x1F\x7F<>:"/\\|?*]'), '_');
    if (name.isEmpty || name == '.' || name == '..') {
      return 'image';
    }
    final runes = name.runes.take(180).toList(growable: false);
    return String.fromCharCodes(runes);
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
        const Icon(
          Icons.broken_image_outlined,
          size: 48,
          color: Colors.white54,
        ),
        const SizedBox(height: 8),
        Text(
          LegacyTextLocalizer.isEnglish ? 'Unable to load image' : '无法加载图片',
          style: const TextStyle(color: Colors.white54, fontSize: 14),
        ),
      ],
    );
  }
}
