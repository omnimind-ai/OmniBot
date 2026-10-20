part of 'chat_input_area.dart';

extension _ChatInputAttachmentSupport on _ChatInputAreaStateBase {
  Widget _buildAttachmentPreview() {
    // Collect all image sources for multi-image preview
    final imageItems = widget.attachments.where((a) => a.isImage).toList();
    final imageSources = imageItems
        .map((a) => FileImageSource(a.path) as ImagePreviewSource)
        .toList();
    final heroTags = List.generate(
      imageItems.length,
      (i) => 'img_preview_input_${imageItems[i].id}',
    );

    return SizedBox(
      height: 72,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: widget.attachments.length,
        separatorBuilder: (_, _) => const SizedBox(width: 8),
        itemBuilder: (context, index) {
          final item = widget.attachments[index];
          if (item.isImage) {
            final imageIndex = imageItems.indexOf(item);
            return _buildImageAttachmentTile(
              item,
              imageSources,
              imageIndex,
              heroTags,
            );
          }
          return _buildFileAttachmentTile(item);
        },
      ),
    );
  }

  Widget _buildImageAttachmentTile(
    ChatInputAttachment item,
    List<ImagePreviewSource> allSources,
    int tappedIndex,
    List<String> heroTags,
  ) {
    final heroTag = heroTags[tappedIndex];
    final palette = context.omniPalette;
    return GestureDetector(
      onTap: () => ImagePreviewOverlay.showAll(
        context,
        sources: allSources,
        initialIndex: tappedIndex.clamp(0, allSources.length - 1),
        heroTags: heroTags,
      ),
      child: Stack(
        children: [
          Container(
            width: 72,
            height: 72,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: JackeryCesConfig.enabled
                    ? JackeryCesConfig.border
                    : context.isDarkTheme
                    ? palette.borderSubtle
                    : const Color(0xFFD3E3FB),
                width: 1,
              ),
              color: JackeryCesConfig.enabled
                  ? Colors.white
                  : context.isDarkTheme
                  ? palette.surfaceSecondary
                  : const Color(0xFFF1F6FF),
            ),
            clipBehavior: Clip.antiAlias,
            child: Hero(
              tag: heroTag,
              child: Image.file(
                File(item.path),
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => Center(
                  child: Icon(
                    Icons.image_not_supported_outlined,
                    size: 20,
                    color: JackeryCesConfig.enabled
                        ? JackeryCesConfig.text
                        : const Color(0xFF6A83AA),
                  ),
                ),
              ),
            ),
          ),
          _buildAttachmentRemoveButton(item.id),
        ],
      ),
    );
  }

  Widget _buildFileAttachmentTile(ChatInputAttachment item) {
    final sizeText = _formatAttachmentSize(item.size);
    final palette = context.omniPalette;
    final tileColor = JackeryCesConfig.enabled
        ? Colors.white
        : context.isDarkTheme
        ? palette.surfaceSecondary
        : const Color(0xFFF1F6FF);
    final tileBorderColor = JackeryCesConfig.enabled
        ? JackeryCesConfig.border
        : context.isDarkTheme
        ? palette.borderSubtle
        : const Color(0xFFD3E3FB);
    final textColor = JackeryCesConfig.enabled
        ? JackeryCesConfig.text
        : context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF35517A);
    final iconColor = JackeryCesConfig.enabled
        ? JackeryCesConfig.accent
        : context.isDarkTheme
        ? palette.accentPrimary
        : const Color(0xFF3B6FD6);
    return Stack(
      children: [
        Container(
          width: 160,
          height: 72,
          padding: const EdgeInsets.fromLTRB(10, 8, 28, 8),
          decoration: BoxDecoration(
            color: tileColor,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: tileBorderColor, width: 1),
          ),
          child: Row(
            children: [
              Icon(
                Icons.insert_drive_file_outlined,
                size: 18,
                color: iconColor,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  sizeText.isEmpty ? item.name : '${item.name}\n$sizeText',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    color: textColor,
                    fontWeight: FontWeight.w500,
                    height: 1.3,
                  ),
                ),
              ),
            ],
          ),
        ),
        _buildAttachmentRemoveButton(item.id),
      ],
    );
  }

  Widget _buildAttachmentRemoveButton(String attachmentId) {
    if (widget.onRemoveAttachment == null) {
      return const SizedBox.shrink();
    }
    return Positioned(
      right: 4,
      top: 4,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => widget.onRemoveAttachment?.call(attachmentId),
        child: Container(
          width: 18,
          height: 18,
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.62),
            shape: BoxShape.circle,
          ),
          child: const Icon(Icons.close_rounded, size: 12, color: Colors.white),
        ),
      ),
    );
  }

  String _formatAttachmentSize(int? size) {
    if (size == null || size <= 0) return '';
    if (size < 1024) return '${size}B';
    if (size < 1024 * 1024) return '${(size / 1024).toStringAsFixed(1)}KB';
    return '${(size / (1024 * 1024)).toStringAsFixed(1)}MB';
  }

  /// 构建带动画的按钮行
}
