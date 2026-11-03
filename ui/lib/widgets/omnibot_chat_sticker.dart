import 'dart:io';

import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/widgets/image_preview_overlay.dart';

/// A compact presentation of an ordinary Markdown image marked `"sticker"`.
/// The resource stays in the assistant text and uses the existing workspace
/// resolver, so replay and history do not require another message type.
class OmnibotChatSticker extends StatefulWidget {
  const OmnibotChatSticker({
    super.key,
    required this.uri,
    required this.alternativeText,
  });

  final Uri uri;
  final String alternativeText;

  @override
  State<OmnibotChatSticker> createState() => _OmnibotChatStickerState();
}

class _OmnibotChatStickerState extends State<OmnibotChatSticker> {
  late final Future<OmnibotWorkspacePaths> _workspacePaths =
      OmnibotResourceService.ensureWorkspacePathsLoaded();

  String get _label => widget.alternativeText.trim().isNotEmpty
      ? widget.alternativeText.trim()
      : (LegacyTextLocalizer.isEnglish ? 'Sticker' : '表情包');

  @override
  Widget build(BuildContext context) {
    // A stable transparent frame keeps streamed text from jumping as the
    // image decodes. Contain preserves lettering and animated GIF frames.
    return SizedBox(
      width: 168,
      height: 168,
      child: FutureBuilder<OmnibotWorkspacePaths>(
        future: _workspacePaths,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return _placeholder(context, unavailable: false);
          }
          final metadata = widget.uri.scheme == 'omnibot'
              ? OmnibotResourceService.resolveUri(widget.uri.toString())
              : null;
          // A failed local reference must never become a network request.
          if (metadata == null ||
              !metadata.exists ||
              metadata.isDirectory ||
              !metadata.mimeType.startsWith('image/')) {
            return _placeholder(context, unavailable: true);
          }
          return Image.file(
            File(metadata.path),
            fit: BoxFit.contain,
            excludeFromSemantics: true,
            frameBuilder: (context, child, frame, wasSynchronouslyLoaded) {
              if (frame == null) {
                return _placeholder(context, unavailable: false);
              }
              void openPreview() => ImagePreviewOverlay.show(
                context,
                source: FileImageSource(metadata.path),
              );
              return Semantics(
                image: true,
                button: true,
                label: _label,
                onTap: openPreview,
                child: GestureDetector(
                  excludeFromSemantics: true,
                  onTap: openPreview,
                  child: child,
                ),
              );
            },
            errorBuilder: (context, error, stackTrace) =>
                _placeholder(context, unavailable: true),
          );
        },
      ),
    );
  }

  Widget _placeholder(BuildContext context, {required bool unavailable}) {
    final text = unavailable
        ? (LegacyTextLocalizer.isEnglish ? 'Sticker unavailable' : '表情包暂时无法显示')
        : (LegacyTextLocalizer.isEnglish ? 'Loading sticker…' : '表情包加载中…');
    final color = Theme.of(context).colorScheme.onSurfaceVariant;
    return Semantics(
      label: '$_label，$text',
      child: ExcludeSemantics(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  unavailable
                      ? Icons.image_not_supported_outlined
                      : Icons.image_outlined,
                  size: 24,
                  color: color,
                ),
                const SizedBox(height: 8),
                Text(
                  text,
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 12, color: color),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
