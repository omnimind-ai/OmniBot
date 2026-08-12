import 'dart:io';
import 'dart:ui';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/theme/theme_context.dart';

enum BackgroundPreviewKind { chat, workspace }

class AppBackgroundLayer extends StatelessWidget {
  final AppBackgroundConfig config;
  final Color fallbackColor;
  final Key? layerKey;
  final bool showLoadFailureOverlay;

  const AppBackgroundLayer({
    super.key,
    required this.config,
    this.fallbackColor = const Color(0xFFF9FCFF),
    this.layerKey,
    this.showLoadFailureOverlay = false,
  });

  @override
  Widget build(BuildContext context) {
    final alignment = Alignment(config.focalX, config.focalY);
    final image = _buildImage(alignment);
    final imageScale = config.imageScale.clamp(1.0, 3.0).toDouble();
    final gradientTail =
        ThemeData.estimateBrightnessForColor(fallbackColor) == Brightness.dark
        ? Color.lerp(fallbackColor, const Color(0xFF1B2840), 0.5)!
        : Color.lerp(fallbackColor, Colors.white, 0.35)!;

    return Stack(
      key: layerKey,
      fit: StackFit.expand,
      children: [
        DecoratedBox(
          decoration: BoxDecoration(
            color: fallbackColor,
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [fallbackColor, gradientTail],
            ),
          ),
        ),
        if (image != null)
          Positioned.fill(
            child: ClipRect(
              child: Transform.scale(
                scale: imageScale,
                alignment: alignment,
                child: image,
              ),
            ),
          ),
        if (config.isActive) _buildMaskLayer(),
      ],
    );
  }

  Widget _buildMaskLayer() {
    final whiteMaskOpacity = resolvedWhiteMaskOpacity(config);
    final darkMaskOpacity = resolvedDarkMaskOpacity(config);
    final blurSigma = config.blurSigma.clamp(0, 24).toDouble();
    final whiteMask = DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            Colors.white.withValues(
              alpha: (whiteMaskOpacity + 0.04).clamp(0.0, 0.85),
            ),
            const Color(0xFFF6FAFF).withValues(alpha: whiteMaskOpacity),
            const Color(
              0xFFEDF4FF,
            ).withValues(alpha: (whiteMaskOpacity * 0.9).clamp(0.0, 0.8)),
          ],
        ),
      ),
    );

    return ClipRect(
      child: Stack(
        fit: StackFit.expand,
        children: [
          if (blurSigma > 0)
            BackdropFilter(
              filter: ImageFilter.blur(sigmaX: blurSigma, sigmaY: blurSigma),
              child: whiteMask,
            )
          else
            whiteMask,
          if (darkMaskOpacity > 0)
            DecoratedBox(
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: darkMaskOpacity),
              ),
            ),
        ],
      ),
    );
  }

  Widget? _buildImage(Alignment alignment) {
    if (!config.isActive) {
      return null;
    }
    switch (config.sourceType) {
      case AppBackgroundSourceType.local:
        final file = File(config.localImagePath);
        if (!file.existsSync()) {
          return null;
        }
        return Image.file(
          file,
          fit: BoxFit.cover,
          alignment: alignment,
          errorBuilder: (_, __, ___) => const SizedBox.shrink(),
        );
      case AppBackgroundSourceType.remote:
        final imageUrl = config.remoteImageUrl.trim();
        if (imageUrl.isEmpty) {
          return null;
        }
        return CachedNetworkImage(
          imageUrl: imageUrl,
          fit: BoxFit.cover,
          alignment: alignment,
          placeholder: (_, __) => const SizedBox.expand(),
          errorWidget: (_, __, ___) => showLoadFailureOverlay
              ? const _BackgroundLoadFailurePlaceholder()
              : const SizedBox.shrink(),
        );
      case AppBackgroundSourceType.none:
        return null;
    }
  }
}

class AppBackgroundPreview extends StatefulWidget {
  final AppBackgroundConfig config;
  final BackgroundPreviewKind kind;
  final void Function(Offset focalPoint, double imageScale)? onViewportChanged;
  final bool showDragHint;
  final AppBackgroundVisualProfile? visualProfile;

  const AppBackgroundPreview({
    super.key,
    required this.config,
    required this.kind,
    this.onViewportChanged,
    this.showDragHint = false,
    this.visualProfile,
  });

  @override
  State<AppBackgroundPreview> createState() => _AppBackgroundPreviewState();
}

class _AppBackgroundPreviewState extends State<AppBackgroundPreview> {
  Offset _gestureStartOffset = Offset.zero;
  Offset _gestureStartFocalPoint = Offset.zero;
  double _gestureStartScale = 1;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final resolvedVisualProfile =
        widget.visualProfile ??
        AppBackgroundVisualProfile.derive(config: widget.config);
    return ClipRRect(
      borderRadius: BorderRadius.circular(24),
      child: AspectRatio(
        aspectRatio: 0.68,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final previewContent = Stack(
              fit: StackFit.expand,
              children: [
                AppBackgroundLayer(
                  config: widget.config,
                  fallbackColor: palette.previewFallback,
                  layerKey: ValueKey(
                    'app-background-preview-${widget.kind.name}',
                  ),
                  showLoadFailureOverlay: true,
                ),
                Padding(
                  padding: const EdgeInsets.all(14),
                  child: widget.kind == BackgroundPreviewKind.chat
                      ? _ChatPreviewChrome(
                          visualProfile: resolvedVisualProfile,
                          config: widget.config,
                        )
                      : _WorkspacePreviewChrome(
                          visualProfile: resolvedVisualProfile,
                        ),
                ),
                if (widget.showDragHint && widget.config.hasResolvedImage)
                  Positioned(
                    left: 12,
                    bottom: 12,
                    child: IgnorePointer(
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 10,
                          vertical: 6,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.black.withValues(alpha: 0.54),
                          borderRadius: BorderRadius.circular(999),
                        ),
                        child: Text(
                          LegacyTextLocalizer.isEnglish ? 'Drag or pinch to zoom' : '拖动与双指缩放图片',
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 11,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
            );

            if (widget.onViewportChanged == null ||
                !widget.config.hasResolvedImage) {
              return previewContent;
            }

            return GestureDetector(
              key: ValueKey('app-background-preview-drag-${widget.kind.name}'),
              behavior: HitTestBehavior.opaque,
              onScaleStart: (details) {
                _gestureStartOffset = Offset(
                  widget.config.focalX,
                  widget.config.focalY,
                );
                _gestureStartFocalPoint = details.localFocalPoint;
                _gestureStartScale = widget.config.imageScale;
              },
              onScaleUpdate: (details) {
                final size = constraints.biggest;
                final scale = (_gestureStartScale * details.scale)
                    .clamp(1.0, 3.0)
                    .toDouble();
                final delta = details.localFocalPoint - _gestureStartFocalPoint;
                final nextOffset = _normalizedOffsetForDelta(
                  baseOffset: _gestureStartOffset,
                  delta: delta,
                  imageScale: scale,
                  size: size,
                );
                widget.onViewportChanged!(nextOffset, scale);
              },
              child: previewContent,
            );
          },
        ),
      ),
    );
  }

  Offset _normalizedOffsetForDelta({
    required Offset baseOffset,
    required Offset delta,
    required double imageScale,
    required Size size,
  }) {
    if (size.width <= 0 || size.height <= 0) {
      return const Offset(0, 0);
    }
    final normalizedX = (baseOffset.dx - delta.dx / size.width * 2 / imageScale)
        .clamp(-1.0, 1.0)
        .toDouble();
    final normalizedY =
        (baseOffset.dy - delta.dy / size.height * 2 / imageScale)
            .clamp(-1.0, 1.0)
            .toDouble();
    return Offset(normalizedX, normalizedY);
  }
}

Color backgroundSurfaceColor({
  required bool translucent,
  Color baseColor = Colors.white,
  double opacity = 0.78,
}) {
  return translucent ? baseColor.withValues(alpha: opacity) : baseColor;
}

class _ChatPreviewChrome extends StatelessWidget {
  final AppBackgroundVisualProfile visualProfile;
  final AppBackgroundConfig config;

  const _ChatPreviewChrome({required this.visualProfile, required this.config});

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final textScale = resolvedChatTextScale(config);
    return Column(
      children: [
        Container(
          height: 54,
          decoration: BoxDecoration(
            color: backgroundSurfaceColor(
              translucent: true,
              baseColor: palette.surfacePrimary,
              opacity: 0.72,
            ),
            borderRadius: BorderRadius.circular(999),
            boxShadow: [
              BoxShadow(
                color: palette.shadowColor.withValues(alpha: 0.16),
                blurRadius: 18,
                offset: const Offset(0, 6),
              ),
            ],
          ),
        ),
        const SizedBox(height: 18),
        Align(
          alignment: Alignment.centerLeft,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            decoration: BoxDecoration(
              color: backgroundSurfaceColor(
                translucent: true,
                baseColor: palette.surfacePrimary,
                opacity: 0.7,
              ),
              borderRadius: BorderRadius.circular(999),
            ),
            child: Text(
              '${context.l10n.appearancePreviewChat} · ${visualProfile.previewToneLabel}',
              key: const ValueKey('app-background-preview-chat-tone'),
              style: TextStyle(
                color: visualProfile.secondaryTextColor,
                fontSize: 11 * textScale,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ),
        const SizedBox(height: 12),
        Expanded(
          child: Column(
            children: [
              _PreviewMessageCard(
                alignment: Alignment.centerLeft,
                widthFactor: 0.66,
                title: context.l10n.appearancePreviewChat,
                subtitle: context.l10n.appearanceTextColorSubtitle,
                visualProfile: visualProfile,
                userStyle: false,
                textScale: textScale,
              ),
              const SizedBox(height: 14),
              _PreviewMessageCard(
                alignment: Alignment.centerRight,
                widthFactor: 0.52,
                title: context.l10n.appearanceTextColorTitle,
                subtitle: visualProfile.previewToneLabel,
                visualProfile: visualProfile,
                userStyle: true,
                textScale: textScale,
              ),
              const Spacer(),
              Container(
                height: 64,
                decoration: BoxDecoration(
                  color: backgroundSurfaceColor(
                    translucent: true,
                    baseColor: palette.surfacePrimary,
                    opacity: 0.76,
                  ),
                  borderRadius: BorderRadius.circular(22),
                  border: Border.all(color: const Color(0x55D7E2F4)),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _WorkspacePreviewChrome extends StatelessWidget {
  final AppBackgroundVisualProfile visualProfile;

  const _WorkspacePreviewChrome({required this.visualProfile});

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Column(
      children: [
        Container(
          height: 48,
          decoration: BoxDecoration(
            color: backgroundSurfaceColor(
              translucent: true,
              baseColor: palette.surfacePrimary,
              opacity: 0.74,
            ),
            borderRadius: BorderRadius.circular(16),
          ),
        ),
        const SizedBox(height: 18),
        Expanded(
          child: Column(
            children: List.generate(5, (index) {
              return Padding(
                padding: EdgeInsets.only(bottom: index == 4 ? 0 : 10),
                child: Container(
                  height: 46,
                  decoration: BoxDecoration(
                    color: backgroundSurfaceColor(
                      translucent: true,
                      baseColor: palette.surfacePrimary,
                      opacity: 0.7 + index * 0.02,
                    ),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  padding: const EdgeInsets.symmetric(horizontal: 14),
                  alignment: Alignment.centerLeft,
                  child: Row(
                    children: [
                      Container(
                        width: 18,
                        height: 18,
                        decoration: BoxDecoration(
                          color: visualProfile.secondaryTextColor.withValues(
                            alpha: 0.18,
                          ),
                          borderRadius: BorderRadius.circular(6),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Container(
                          height: 10,
                          decoration: BoxDecoration(
                            color: visualProfile.secondaryTextColor.withValues(
                              alpha: 0.32,
                            ),
                            borderRadius: BorderRadius.circular(999),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              );
            }),
          ),
        ),
      ],
    );
  }
}

class _PreviewMessageCard extends StatelessWidget {
  final Alignment alignment;
  final double widthFactor;
  final String title;
  final String subtitle;
  final AppBackgroundVisualProfile visualProfile;
  final bool userStyle;
  final double textScale;

  const _PreviewMessageCard({
    required this.alignment,
    required this.widthFactor,
    required this.title,
    required this.subtitle,
    required this.visualProfile,
    required this.userStyle,
    this.textScale = 1,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Align(
      alignment: alignment,
      child: FractionallySizedBox(
        widthFactor: widthFactor,
        child: Container(
          height: 70,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: userStyle
                ? visualProfile.userBubbleColor
                : backgroundSurfaceColor(
                    translucent: true,
                    baseColor: palette.surfacePrimary,
                    opacity: 0.54,
                  ),
            borderRadius: BorderRadius.circular(18),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                key: ValueKey(
                  'app-background-preview-message-title-${userStyle ? 'user' : 'assistant'}',
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: visualProfile.primaryTextColor,
                  fontSize: 11 * textScale,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                subtitle,
                key: ValueKey(
                  'app-background-preview-message-subtitle-${userStyle ? 'user' : 'assistant'}',
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: visualProfile.secondaryTextColor,
                  fontSize: 10 * textScale,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _BackgroundLoadFailurePlaceholder extends StatelessWidget {
  const _BackgroundLoadFailurePlaceholder();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: Colors.black.withValues(alpha: 0.58),
          borderRadius: BorderRadius.circular(999),
        ),
        child: Text(
          LegacyTextLocalizer.isEnglish ? 'Image load failed' : '图片加载失败',
          style: const TextStyle(
            color: Colors.white,
            fontSize: 12,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }
}
