import 'dart:convert';
import 'dart:io';
import 'dart:async';
import 'dart:typed_data';
import 'package:dotted_border/dotted_border.dart';
import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/omnibot_workspace/omnibot_artifact_preview_page.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_link_preview.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/widgets/image_preview_overlay.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import '../../../../../models/chat_message_model.dart';
import '../../../../../services/app_background_service.dart';
import '../../../../../theme/theme_context.dart';
import '../../../../../widgets/streaming_text.dart';
import 'thinking_dots_indicator.dart';
import 'cards/card_widget_factory.dart';
import 'package:flutter_svg/flutter_svg.dart';

export 'package:ui/widgets/streaming_text.dart'
    show kThinkingText, kSummarizingText, kSummaryCompleteText;

/// 消息气泡组件
///
/// 根据消息类型渲染不同样式的气泡
/// - 用户消息：右侧深色气泡
/// - AI消息：左侧浅色气泡，使用StreamingText组件渲染流式内容
/// - 卡片消息（预留）：自定义卡片组件
class MessageBubble extends StatelessWidget {
  final ChatMessageModel message;

  /// 任务执行前的回调，用于保存聊天上下文
  final OnBeforeTaskExecute? onBeforeTaskExecute;

  /// 取消任务回调，参数为 taskId
  final void Function(String taskId)? onCancelTask;

  /// 是否允许深度思考卡片折叠
  final bool enableThinkingCollapse;

  /// 思考卡片完成后是否自动折叠
  final bool thinkingAutoCollapseOnComplete;

  /// 是否允许使用 ACP Agent 的行内工具调用样式。
  final bool useAgentToolPresentation;

  /// 强制覆盖深度思考卡片的头像显示
  final bool? showThinkingAvatarOverride;

  /// Treat persisted text in a completed run as fully rendered even when an
  /// old intermediate snapshot still carries `isFinal:false`.
  final bool forceTextFinal;

  /// 外层消息列表滚动控制器，用于卡片内嵌滚动与父列表联动
  final ScrollController? parentScrollController;
  final VoidCallback? onParentScrollHandoff;
  final OnRequestAuthorize? onRequestAuthorize;
  final void Function(ChatMessageModel message, LongPressStartDetails details)?
  onUserMessageLongPressStart;
  final VoidCallback? onRetryAgentMessage;
  final VoidCallback? onContinueAgentMessage;
  final VoidCallback? onUserMessageEditTap;
  final VoidCallback? onStreamingTextLayoutChanged;
  final AppBackgroundVisualProfile visualProfile;
  final AppBackgroundConfig appearanceConfig;

  const MessageBubble({
    super.key,
    required this.message,
    this.onBeforeTaskExecute,
    this.onCancelTask,
    this.enableThinkingCollapse = false,
    this.thinkingAutoCollapseOnComplete = true,
    this.useAgentToolPresentation = true,
    this.showThinkingAvatarOverride,
    this.forceTextFinal = false,
    this.parentScrollController,
    this.onParentScrollHandoff,
    this.onRequestAuthorize,
    this.onUserMessageLongPressStart,
    this.onRetryAgentMessage,
    this.onContinueAgentMessage,
    this.onUserMessageEditTap,
    this.onStreamingTextLayoutChanged,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
    this.appearanceConfig = AppBackgroundConfig.defaults,
  });

  double get _chatTextSize => appearanceConfig.chatTextSize;
  double get _chatTextScale => resolvedChatTextScale(appearanceConfig);

  bool _usesThemeDrivenText() {
    return !appearanceConfig.isActive &&
        appearanceConfig.chatTextColorMode != AppBackgroundTextColorMode.custom;
  }

  Color _resolvedAiPrimaryTextColor(BuildContext context) {
    return _usesThemeDrivenText()
        ? context.omniPalette.textPrimary
        : visualProfile.primaryTextColor;
  }

  Color _resolvedAiSecondaryTextColor(BuildContext context) {
    return _usesThemeDrivenText()
        ? context.omniPalette.textSecondary
        : visualProfile.secondaryTextColor;
  }

  Color _resolvedUserPrimaryTextColor(BuildContext context) {
    return _usesThemeDrivenText()
        ? context.omniPalette.textPrimary
        : visualProfile.primaryTextColor;
  }

  Color _resolvedUserBubbleColor(BuildContext context) {
    if (!_usesThemeDrivenText()) {
      return visualProfile.userBubbleColor;
    }
    return context.isDarkTheme
        ? context.omniPalette.surfaceSecondary
        : visualProfile.userBubbleColor;
  }

  Color _resolvedAttachmentSurfaceColor(BuildContext context) {
    if (!_usesThemeDrivenText()) {
      return visualProfile.attachmentSurfaceColor;
    }
    return context.isDarkTheme
        ? context.omniPalette.surfaceElevated
        : visualProfile.attachmentSurfaceColor;
  }

  Color _resolvedAttachmentBorderColor(BuildContext context) {
    if (!_usesThemeDrivenText()) {
      return visualProfile.attachmentBorderColor;
    }
    return context.isDarkTheme
        ? context.omniPalette.borderSubtle
        : visualProfile.attachmentBorderColor;
  }

  Color _resolvedAttachmentIconColor(BuildContext context) {
    if (!_usesThemeDrivenText()) {
      return visualProfile.attachmentIconColor;
    }
    return context.isDarkTheme
        ? context.omniPalette.textSecondary
        : visualProfile.attachmentIconColor;
  }

  Color _resolvedAttachmentTextColor(BuildContext context) {
    if (!_usesThemeDrivenText()) {
      return visualProfile.attachmentTextColor;
    }
    return context.isDarkTheme
        ? context.omniPalette.textPrimary
        : visualProfile.attachmentTextColor;
  }

  @override
  Widget build(BuildContext context) {
    // user: 1=用户, 2=AI, 3=系统
    final isUserMessage = message.user == 1;
    final isCardMessage = message.type == 2; // 卡片消息

    // 卡片消息特殊布局：撑满整个聊天框宽度
    if (isCardMessage) {
      return Container(
        margin: const EdgeInsets.only(top: 8, bottom: 0),
        child: _buildMessageContent(context, isUserMessage),
      );
    }

    return Container(
      margin: EdgeInsets.only(
        top: isUserMessage ? 24 : 8,
        bottom: isUserMessage ? 16 : 0,
        right: isUserMessage ? 0 : 18.34,
      ),
      child: Row(
        mainAxisAlignment: isUserMessage
            ? MainAxisAlignment.end
            : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          isUserMessage
              ? Flexible(child: _buildMessageContent(context, isUserMessage))
              : Expanded(child: _buildMessageContent(context, isUserMessage)),
        ],
      ),
    );
  }

  /// 构建消息内容
  Widget _buildMessageContent(BuildContext context, bool isUserMessage) {
    // 根据消息类型渲染不同内容
    // type: 1=普通消息, 2=卡片消息
    switch (message.type) {
      case 1: // 普通消息
        return _buildTextMessage(context, isUserMessage);

      case 2: // 卡片消息
        return _buildCardMessage(context);

      default:
        return _buildTextMessage(context, isUserMessage);
    }
  }

  /// 构建文本消息
  Widget _buildTextMessage(BuildContext context, bool isUserMessage) {
    final text = message.text ?? '';
    final attachments = _extractAttachments();
    final linkPreviews = message.linkPreviews;

    if (isUserMessage) {
      // 用户消息：整块气泡长按触发快捷操作。
      return LayoutBuilder(
        builder: (context, constraints) {
          final fallbackMaxWidth = MediaQuery.of(context).size.width * 0.75;
          final availableWidth = constraints.maxWidth.isFinite
              ? constraints.maxWidth
              : fallbackMaxWidth;
          final maxBubbleWidth = availableWidth * 0.78;
          return GestureDetector(
            behavior: HitTestBehavior.opaque,
            onLongPressStart: onUserMessageLongPressStart == null
                ? null
                : (details) => onUserMessageLongPressStart!(message, details),
            child: Container(
              key: ValueKey('user-message-bubble-${message.id}'),
              constraints: BoxConstraints(maxWidth: maxBubbleWidth),
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              decoration: ShapeDecoration(
                color: _resolvedUserBubbleColor(context),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(4),
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (text.isNotEmpty)
                    _buildUserText(
                      context,
                      text,
                      onEditTap: onUserMessageEditTap,
                    ),
                  if (attachments.isNotEmpty) ...[
                    if (text.isNotEmpty) const SizedBox(height: 8),
                    _buildUserAttachmentList(context, attachments),
                  ],
                  if (linkPreviews.isNotEmpty) ...[
                    if (text.isNotEmpty || attachments.isNotEmpty)
                      const SizedBox(height: 8),
                    _buildLinkPreviewList(
                      context,
                      linkPreviews,
                      compactStyle: true,
                      isUserMessage: true,
                    ),
                  ],
                ],
              ),
            ),
          );
        },
      );
    }

    if (attachments.isEmpty && linkPreviews.isEmpty) {
      // AI消息：简单文本样式，无背景
      return _buildAiTextWithSpeed(context, text, includeTurnUsageFooter: true);
    }

    // AI 消息按“正文 -> 附件 -> 链接预览”顺序分块展示。
    final turnUsageFooter = _buildTurnUsageFooter(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (text.isNotEmpty)
          _buildAiTextWithSpeed(context, text, includeTurnUsageFooter: false),
        if (attachments.isNotEmpty) ...[
          if (text.isNotEmpty) const SizedBox(height: 8),
          _buildUserAttachmentList(context, attachments),
        ],
        if (linkPreviews.isNotEmpty) ...[
          if (text.isNotEmpty || attachments.isNotEmpty)
            const SizedBox(height: 8),
          _buildLinkPreviewList(
            context,
            linkPreviews,
            compactStyle: true,
            isUserMessage: false,
          ),
        ],
        if (turnUsageFooter != null) ...[
          if (text.isNotEmpty ||
              attachments.isNotEmpty ||
              linkPreviews.isNotEmpty)
            const SizedBox(height: 8),
          turnUsageFooter,
        ],
      ],
    );
  }

  List<Map<String, dynamic>> _extractAttachments() {
    final raw = message.content?['attachments'];
    if (raw is! List) return const [];
    return raw
        .whereType<Map>()
        .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
        .toList();
  }

  Widget _buildUserAttachmentList(
    BuildContext context,
    List<Map<String, dynamic>> attachments,
  ) {
    // Collect all image sources for multi-image preview
    final imageAttachments = attachments.where(_isImageAttachment).toList();
    final imageSources = imageAttachments
        .map(_resolveImageSource)
        .whereType<ImagePreviewSource>()
        .toList();
    final heroTags = List.generate(
      imageSources.length,
      (i) => 'img_preview_${message.id}_$i',
    );

    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: attachments.map((item) {
        if (_isImageAttachment(item)) {
          final imageIndex = imageAttachments.indexOf(item);
          return _buildImageAttachmentTile(
            context,
            item,
            imageSources,
            imageIndex,
            heroTags,
          );
        }
        return _buildFileAttachmentChip(context, item);
      }).toList(),
    );
  }

  Widget _buildImageAttachmentTile(
    BuildContext context,
    Map<String, dynamic> item,
    List<ImagePreviewSource> allSources,
    int tappedIndex,
    List<String> heroTags,
  ) {
    final heroTag = heroTags[tappedIndex];
    return GestureDetector(
      onTap: () {
        if (allSources.isNotEmpty) {
          ImagePreviewOverlay.showAll(
            context,
            sources: allSources,
            initialIndex: tappedIndex.clamp(0, allSources.length - 1),
            heroTags: heroTags,
          );
        }
      },
      child: Container(
        width: 84,
        height: 84,
        clipBehavior: Clip.antiAlias,
        decoration: BoxDecoration(
          color: _resolvedAttachmentSurfaceColor(context),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: _resolvedAttachmentBorderColor(context),
            width: 1,
          ),
        ),
        child: Hero(
          tag: heroTag,
          child: _buildAttachmentImageWidget(context, item),
        ),
      ),
    );
  }

  ImagePreviewSource? _resolveImageSource(Map<String, dynamic> item) {
    final dataUrl = (item['dataUrl'] as String? ?? '').trim();
    if (dataUrl.startsWith('data:')) {
      final bytes = _decodeDataUrlBytes(dataUrl);
      if (bytes != null) return MemoryImageSource(bytes);
    }
    final url = (item['url'] as String? ?? '').trim();
    if (url.startsWith('http://') || url.startsWith('https://')) {
      return NetworkImageSource(url);
    }
    if (url.startsWith('data:')) {
      final bytes = _decodeDataUrlBytes(url);
      if (bytes != null) return MemoryImageSource(bytes);
    }
    final path = (item['path'] as String? ?? '').trim();
    if (path.isNotEmpty && !path.startsWith('http')) {
      return FileImageSource(path);
    }
    return null;
  }

  Widget _buildAttachmentImageWidget(
    BuildContext context,
    Map<String, dynamic> item,
  ) {
    final dataUrl = (item['dataUrl'] as String? ?? '').trim();
    if (dataUrl.startsWith('data:')) {
      final bytes = _decodeDataUrlBytes(dataUrl);
      if (bytes != null) {
        return Image.memory(
          bytes,
          fit: BoxFit.cover,
          gaplessPlayback: true,
          errorBuilder: (_, __, ___) => _buildImageFallback(context),
        );
      }
    }

    final url = (item['url'] as String? ?? '').trim();
    if (url.startsWith('http://') || url.startsWith('https://')) {
      return Image.network(
        url,
        fit: BoxFit.cover,
        gaplessPlayback: true,
        errorBuilder: (_, __, ___) => _buildImageFallback(context),
      );
    }
    if (url.startsWith('data:')) {
      final bytes = _decodeDataUrlBytes(url);
      if (bytes != null) {
        return Image.memory(
          bytes,
          fit: BoxFit.cover,
          gaplessPlayback: true,
          errorBuilder: (_, __, ___) => _buildImageFallback(context),
        );
      }
    }

    final path = (item['path'] as String? ?? '').trim();
    if (path.isNotEmpty && !path.startsWith('http')) {
      return Image.file(
        File(path),
        fit: BoxFit.cover,
        gaplessPlayback: true,
        errorBuilder: (_, __, ___) => _buildImageFallback(context),
      );
    }
    return _buildImageFallback(context);
  }

  Widget _buildImageFallback(BuildContext context) {
    return Center(
      child: Icon(
        Icons.image_not_supported_outlined,
        size: 20,
        color: _resolvedAttachmentIconColor(context),
      ),
    );
  }

  Widget _buildFileAttachmentChip(
    BuildContext context,
    Map<String, dynamic> item,
  ) {
    final displayName = _resolveAttachmentDisplayName(item);
    final sizeText = _formatAttachmentSize(item['size']);

    return Container(
      constraints: const BoxConstraints(maxWidth: 220, minHeight: 40),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: _resolvedAttachmentSurfaceColor(context),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(
          color: _resolvedAttachmentBorderColor(context),
          width: 1,
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.insert_drive_file_outlined,
            size: 15,
            color: _resolvedAttachmentIconColor(context),
          ),
          const SizedBox(width: 6),
          Flexible(
            child: Text(
              sizeText.isEmpty ? displayName : '$displayName\n$sizeText',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: _resolvedAttachmentTextColor(context),
                fontSize: 12,
                fontWeight: FontWeight.w500,
                height: 1.25,
              ),
            ),
          ),
        ],
      ),
    );
  }

  // 链接预览独立成块，便于保持引用式层级和轻量点击反馈。
  Widget _buildLinkPreviewList(
    BuildContext context,
    List<ChatLinkPreview> previews, {
    required bool compactStyle,
    required bool isUserMessage,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: previews.asMap().entries.map((entry) {
        return Padding(
          padding: EdgeInsets.only(
            bottom: entry.key == previews.length - 1 ? 0 : 8,
          ),
          child: _buildLinkPreviewCard(
            context,
            entry.value,
            entry.key,
            compactStyle: compactStyle,
            isUserMessage: isUserMessage,
          ),
        );
      }).toList(),
    );
  }

  Widget _buildLinkPreviewCard(
    BuildContext context,
    ChatLinkPreview preview,
    int index, {
    required bool compactStyle,
    required bool isUserMessage,
  }) {
    if (compactStyle) {
      return _buildCompactLinkPreview(
        context,
        preview,
        index,
        isUserMessage: isUserMessage,
      );
    }
    final isEnglish =
        Localizations.maybeLocaleOf(context)?.languageCode == 'en';
    final title = preview.title.trim();
    final description = preview.description.trim();
    final siteName = preview.displaySiteName.trim();
    final hasImage =
        preview.imageUrl.startsWith('http://') ||
        preview.imageUrl.startsWith('https://');
    final primaryColor = isUserMessage
        ? _resolvedUserPrimaryTextColor(context)
        : _resolvedAiPrimaryTextColor(context);
    final secondaryColor = isUserMessage
        ? _resolvedUserPrimaryTextColor(context).withValues(alpha: 0.82)
        : _resolvedAiSecondaryTextColor(context);
    final statusLabel = switch (preview.status) {
      ChatLinkPreview.statusLoading => isEnglish ? 'Loading preview' : '加载预览中',
      ChatLinkPreview.statusFailed =>
        isEnglish ? 'Preview unavailable' : '预览暂不可用',
      _ => '',
    };

    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: ValueKey('link-preview-card-$index'),
        onTap: () {
          if (preview.url.isEmpty) {
            return;
          }
          _handleChatLinkTap(context, preview.url);
        },
        borderRadius: BorderRadius.circular(14),
        child: Container(
          constraints: const BoxConstraints(maxWidth: 360),
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: _resolvedAttachmentSurfaceColor(context),
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
              color: _resolvedAttachmentBorderColor(context),
              width: 1,
            ),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (siteName.isNotEmpty)
                      Text(
                        siteName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: secondaryColor,
                          fontSize: 11,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    if (title.isNotEmpty) ...[
                      if (siteName.isNotEmpty) const SizedBox(height: 4),
                      Text(
                        title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: primaryColor,
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          height: 1.25,
                        ),
                      ),
                    ],
                    if (description.isNotEmpty) ...[
                      if (title.isNotEmpty || siteName.isNotEmpty)
                        const SizedBox(height: 4),
                      Text(
                        description,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: secondaryColor,
                          fontSize: 12,
                          height: 1.35,
                        ),
                      ),
                    ],
                    if (statusLabel.isNotEmpty &&
                        preview.status != ChatLinkPreview.statusReady) ...[
                      if (title.isNotEmpty ||
                          description.isNotEmpty ||
                          siteName.isNotEmpty)
                        const SizedBox(height: 4),
                      Text(
                        statusLabel,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(color: secondaryColor, fontSize: 12),
                      ),
                    ],
                  ],
                ),
              ),
              if (hasImage) ...[
                const SizedBox(width: 12),
                ClipRRect(
                  borderRadius: BorderRadius.circular(10),
                  child: Image.network(
                    preview.imageUrl,
                    width: 72,
                    height: 72,
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) =>
                        _buildLinkPreviewImageFallback(context),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCompactLinkPreview(
    BuildContext context,
    ChatLinkPreview preview,
    int index, {
    required bool isUserMessage,
  }) {
    final isEnglish =
        Localizations.maybeLocaleOf(context)?.languageCode == 'en';
    final title = preview.title.trim();
    final description = preview.description.trim();
    final siteName = preview.displaySiteName.trim();
    final hasImage =
        preview.imageUrl.startsWith('http://') ||
        preview.imageUrl.startsWith('https://');
    final primaryColor = isUserMessage
        ? _resolvedUserPrimaryTextColor(context)
        : _resolvedAiPrimaryTextColor(context);
    final secondaryBaseColor = isUserMessage
        ? _resolvedUserPrimaryTextColor(context)
        : _resolvedAiSecondaryTextColor(context);
    final lineColor = secondaryBaseColor.withValues(alpha: 0.42);
    final metaColor = secondaryBaseColor.withValues(alpha: 0.82);
    final secondaryColor = secondaryBaseColor.withValues(alpha: 0.94);
    final statusLabel = switch (preview.status) {
      ChatLinkPreview.statusLoading => isEnglish ? 'Loading preview' : '加载预览中',
      ChatLinkPreview.statusFailed =>
        isEnglish ? 'Preview unavailable' : '预览暂不可用',
      _ => '',
    };

    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: ValueKey('link-preview-card-$index'),
        onTap: () {
          if (preview.url.isEmpty) {
            return;
          }
          _handleChatLinkTap(context, preview.url);
        },
        borderRadius: BorderRadius.circular(8),
        child: Container(
          key: ValueKey('link-preview-quote-$index'),
          constraints: BoxConstraints(maxWidth: isUserMessage ? 320 : 360),
          padding: const EdgeInsets.only(left: 10, top: 2, right: 2, bottom: 2),
          decoration: BoxDecoration(
            border: Border(left: BorderSide(color: lineColor, width: 3)),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.only(left: 8),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (siteName.isNotEmpty)
                        Text(
                          siteName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: metaColor,
                            fontSize: 10,
                            fontWeight: FontWeight.w500,
                            height: 1.2,
                          ),
                        ),
                      if (title.isNotEmpty) ...[
                        if (siteName.isNotEmpty) const SizedBox(height: 2),
                        Text(
                          title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: primaryColor,
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                            height: 1.22,
                          ),
                        ),
                      ],
                      if (description.isNotEmpty) ...[
                        if (title.isNotEmpty || siteName.isNotEmpty)
                          const SizedBox(height: 2),
                        Text(
                          description,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: secondaryColor,
                            fontSize: 10.5,
                            height: 1.25,
                          ),
                        ),
                      ],
                      if (statusLabel.isNotEmpty &&
                          preview.status != ChatLinkPreview.statusReady) ...[
                        if (title.isNotEmpty ||
                            description.isNotEmpty ||
                            siteName.isNotEmpty)
                          const SizedBox(height: 2),
                        Text(
                          statusLabel,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: metaColor,
                            fontSize: 10,
                            height: 1.2,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
              if (hasImage) ...[
                const SizedBox(width: 8),
                ClipRRect(
                  borderRadius: BorderRadius.circular(6),
                  child: Image.network(
                    preview.imageUrl,
                    width: 40,
                    height: 40,
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) =>
                        _buildCompactLinkPreviewImageFallback(context),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCompactLinkPreviewImageFallback(BuildContext context) {
    final color = _resolvedAttachmentIconColor(context);
    return Container(
      width: 40,
      height: 40,
      alignment: Alignment.center,
      color: color.withValues(alpha: 0.08),
      child: Icon(
        Icons.link_outlined,
        size: 14,
        color: color.withValues(alpha: 0.72),
      ),
    );
  }

  Future<void> _handleChatLinkTap(BuildContext context, String url) async {
    if (url.startsWith('omnibot://')) {
      await OmnibotResourceService.ensureWorkspacePathsLoaded();
      if (!context.mounted) return;
      final metadata = OmnibotResourceService.resolveUri(url);
      if (metadata != null) {
        await showOmnibotArtifactPreviewSheet(context, metadata);
        return;
      }
    }
    await OmnibotResourceService.handleLinkTap(url);
  }

  Widget _buildLinkPreviewImageFallback(BuildContext context) {
    return Container(
      width: 72,
      height: 72,
      color: _resolvedAttachmentSurfaceColor(context),
      alignment: Alignment.center,
      child: Icon(
        Icons.link_outlined,
        size: 18,
        color: _resolvedAttachmentIconColor(context),
      ),
    );
  }

  bool _isImageAttachment(Map<String, dynamic> item) {
    final explicit = item['isImage'];
    if (explicit is bool && explicit) return true;
    final mimeType = (item['mimeType'] as String? ?? '').trim().toLowerCase();
    if (mimeType.startsWith('image/')) return true;
    final path = (item['path'] as String? ?? '').toLowerCase();
    final url = (item['url'] as String? ?? '').toLowerCase();
    final dataUrl = (item['dataUrl'] as String? ?? '').toLowerCase();
    return _looksLikeImage(path) ||
        _looksLikeImage(url) ||
        dataUrl.startsWith('data:image/');
  }

  bool _looksLikeImage(String value) {
    if (value.isEmpty) return false;
    final pure = value.split('?').first;
    return pure.endsWith('.png') ||
        pure.endsWith('.jpg') ||
        pure.endsWith('.jpeg') ||
        pure.endsWith('.gif') ||
        pure.endsWith('.webp') ||
        pure.endsWith('.bmp') ||
        pure.endsWith('.heic') ||
        pure.endsWith('.heif');
  }

  String _resolveAttachmentDisplayName(Map<String, dynamic> item) {
    final name = (item['name'] as String? ?? '').trim();
    if (name.isNotEmpty) return name;
    final fileName = (item['fileName'] as String? ?? '').trim();
    if (fileName.isNotEmpty) return fileName;
    final path = (item['path'] as String? ?? '').trim();
    if (path.isNotEmpty) {
      final normalizedPath = path.replaceAll('\\', '/');
      return normalizedPath.split('/').last;
    }
    final url = (item['url'] as String? ?? '').trim();
    if (url.isNotEmpty) {
      final normalizedUrl = url.split('?').first;
      final segments = normalizedUrl.split('/');
      if (segments.isNotEmpty && segments.last.isNotEmpty) {
        return segments.last;
      }
    }
    return '附件';
  }

  /// Cache decoded data-URL bytes so that repeated [build] calls reuse
  /// the same [Uint8List] instance.  This prevents [Image.memory] from
  /// treating each rebuild as a brand-new image (cache-miss → flicker).
  ///
  /// 上限保持小一些：单条图片可能是几百 KB ~ 数 MB，过大的 LRU 会显著
  /// 抬升内存基线（多次实测后调整为 40 条，覆盖一次对话内的典型用量）。
  static final Map<int, Uint8List> _dataUrlBytesCache = {};
  static const int _maxCacheEntries = 40;

  static Uint8List? _decodeDataUrlBytes(String value) {
    final key = value.hashCode;
    final cached = _dataUrlBytesCache[key];
    if (cached != null) return cached;

    final comma = value.indexOf(',');
    if (comma < 0 || comma + 1 >= value.length) return null;
    final base64Part = value.substring(comma + 1);
    try {
      final bytes = base64Decode(base64Part);
      if (_dataUrlBytesCache.length >= _maxCacheEntries) {
        _dataUrlBytesCache.remove(_dataUrlBytesCache.keys.first);
      }
      _dataUrlBytesCache[key] = bytes;
      return bytes;
    } catch (_) {
      return null;
    }
  }

  String _formatAttachmentSize(dynamic rawSize) {
    final int? size = rawSize is int
        ? rawSize
        : (rawSize is String ? int.tryParse(rawSize) : null);
    if (size == null || size <= 0) return '';
    if (size < 1024) return '${size}B';
    if (size < 1024 * 1024) return '${(size / 1024).toStringAsFixed(1)}KB';
    return '${(size / (1024 * 1024)).toStringAsFixed(1)}MB';
  }

  /// 构建用户文本（不使用流式效果）
  Widget _buildUserText(
    BuildContext context,
    String text, {
    VoidCallback? onEditTap,
  }) {
    final textWidget = Text(
      text,
      style: TextStyle(
        color: _resolvedUserPrimaryTextColor(context),
        fontSize: _chatTextSize,
        fontFamily: 'PingFang SC',
        fontWeight: FontWeight.w400,
        height: 1.43,
        letterSpacing: 0.33,
      ),
      textAlign: TextAlign.left,
    );
    if (onEditTap == null) {
      return textWidget;
    }
    final underlineColor = _resolvedUserPrimaryTextColor(
      context,
    ).withValues(alpha: 0.52);
    return Semantics(
      button: true,
      label: context.trLegacy('编辑消息'),
      child: GestureDetector(
        key: ValueKey('user-message-edit-trigger-${message.id}'),
        behavior: HitTestBehavior.opaque,
        onTap: onEditTap,
        child: DottedBorder(
          options: CustomPathDottedBorderOptions(
            padding: const EdgeInsets.only(bottom: 2),
            color: underlineColor,
            strokeWidth: 1,
            dashPattern: const <double>[3, 3],
            strokeCap: StrokeCap.round,
            customPath: (size) => Path()
              ..moveTo(0, size.height)
              ..lineTo(size.width, size.height),
          ),
          child: textWidget,
        ),
      ),
    );
  }

  /// 构建AI文本（使用StreamingText组件）
  Widget _buildAiText(BuildContext context, String text, {Widget? trailing}) {
    final aiPrimaryTextColor = _resolvedAiPrimaryTextColor(context);
    final aiSecondaryTextColor = _resolvedAiSecondaryTextColor(context);
    final enableMarkdown = _shouldRenderMarkdown();
    // 如果是 loading 状态，显示浮动三个点动画（左对齐，与回复文本位置一致）
    if (message.isLoading) {
      return Align(
        alignment: Alignment.centerLeft,
        child: ThinkingDotsIndicator(dotColor: aiSecondaryTextColor),
      );
    }

    // 如果是总结中状态，显示"总结中"提示
    if (message.isSummarizing) {
      return _buildSummarizingIndicator();
    }

    // 如果有内容且之前是总结状态（通过taskId判断），显示"总结如下"前缀
    final bool isSummaryContent = message.id.startsWith('task-summary-');

    if (isSummaryContent && text.isNotEmpty) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildSummaryCompleteHeader(),
          const SizedBox(height: 8),
          StreamingText(
            enableMarkdown: enableMarkdown,
            markdownRenderedLength: _markdownRenderedLength(),
            isFinal: !_isStreamingMarkdown(),
            fullText: text,
            selectable: true,
            onDisplayedTextChanged: onStreamingTextLayoutChanged,
            onResourceOpen: showOmnibotArtifactPreviewSheet,
            trailing: trailing,
            style: TextStyle(
              fontSize: _chatTextSize,
              color: aiPrimaryTextColor,
              height: 1.57,
            ),
          ),
        ],
      );
    }

    return StreamingText(
      enableMarkdown: enableMarkdown,
      markdownRenderedLength: _markdownRenderedLength(),
      isFinal: !_isStreamingMarkdown(),
      fullText: text,
      selectable: true,
      onDisplayedTextChanged: onStreamingTextLayoutChanged,
      onResourceOpen: showOmnibotArtifactPreviewSheet,
      trailing: trailing,
      style: TextStyle(
        fontSize: _chatTextSize,
        color: aiPrimaryTextColor,
        height: 1.57,
      ),
    );
  }

  bool _shouldRenderMarkdown() {
    final raw = message.content?['renderMarkdown'];
    return raw is bool ? raw : true;
  }

  int? _markdownRenderedLength() {
    final raw = message.content?['markdownRenderedLength'];
    return raw is int ? raw : null;
  }

  bool _isStreamingMarkdown() {
    if (forceTextFinal) {
      return false;
    }
    if (message.content?['isStreamingMarkdown'] == true) {
      return true;
    }
    return message.streamMeta?['isFinal'] == false;
  }

  /// AI text with optional inference speed label
  Widget _buildAiTextWithSpeed(
    BuildContext context,
    String text, {
    required bool includeTurnUsageFooter,
  }) {
    final speed = _decodeTokensPerSecond;
    final aiText = _buildAiText(context, text);
    final continueStatus = _buildAgentContinueStatus(context);
    final retryingStatus = _buildAgentRetryingStatus(context);
    final errorFooter = _buildAgentErrorFooter(context, text);
    final turnUsageFooter = includeTurnUsageFooter
        ? _buildTurnUsageFooter(context)
        : null;
    final showPrimaryText =
        text.isNotEmpty || message.isLoading || message.isSummarizing;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (showPrimaryText) aiText,
        if (speed != null) ...[
          const SizedBox(height: 4),
          Text(
            '${speed.toStringAsFixed(1)} tok/s',
            style: TextStyle(
              fontSize: 11,
              color: _resolvedAiSecondaryTextColor(
                context,
              ).withValues(alpha: 0.6),
              fontWeight: FontWeight.w400,
            ),
          ),
        ],
        if (continueStatus != null) ...[
          if (showPrimaryText || speed != null) const SizedBox(height: 8),
          continueStatus,
        ],
        if (retryingStatus != null) ...[
          if (showPrimaryText || speed != null || continueStatus != null)
            const SizedBox(height: 8),
          retryingStatus,
        ],
        if (errorFooter != null) ...[
          if (showPrimaryText ||
              speed != null ||
              continueStatus != null ||
              retryingStatus != null)
            const SizedBox(height: 8),
          errorFooter,
        ],
        if (turnUsageFooter != null) ...[
          if (showPrimaryText ||
              speed != null ||
              continueStatus != null ||
              retryingStatus != null ||
              errorFooter != null)
            const SizedBox(height: 8),
          turnUsageFooter,
        ],
      ],
    );
  }

  Widget? _buildAgentRetryingStatus(BuildContext context) {
    if (message.content?['agentRetrying'] != true) {
      return null;
    }
    final statusText = (message.content?['agentRetryStatusText'] ?? '')
        .toString()
        .trim();
    if (statusText.isEmpty) {
      return null;
    }
    final secondaryColor = _resolvedAiSecondaryTextColor(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          width: 14,
          height: 14,
          child: CircularProgressIndicator(
            strokeWidth: 1.8,
            valueColor: AlwaysStoppedAnimation<Color>(secondaryColor),
          ),
        ),
        const SizedBox(width: 8),
        Flexible(
          child: Text(
            statusText,
            style: TextStyle(
              fontSize: 12 * _chatTextScale,
              color: secondaryColor,
              height: 1.4,
            ),
          ),
        ),
      ],
    );
  }

  Widget? _buildAgentContinueStatus(BuildContext context) {
    // 续跑期间不再单独显示"正在从当前轮继续…"+转圈,
    // 让旧 bubble 看起来就像一条普通的待更新消息,等首帧新内容到达整体替换。
    return null;
  }

  Widget? _buildAgentErrorFooter(BuildContext context, String text) {
    // 续跑期间隐藏整个错误页脚(报错文字 + Continue 提示框 + Retry 按钮),
    // 不让用户在新内容到达前看到任何残留的失败状态。
    if (message.content?['agentContinuing'] == true) {
      return null;
    }
    final errorText = (message.content?['agentErrorText'] ?? '')
        .toString()
        .trim();
    final retryable = message.content?['agentRetryable'] == true;
    final continueable = message.content?['agentContinueable'] == true;
    final showRetryButton = retryable && onRetryAgentMessage != null;
    final showContinueButton = continueable && onContinueAgentMessage != null;
    final showErrorText = errorText.isNotEmpty && errorText != text.trim();
    if (!showErrorText && !showRetryButton && !showContinueButton) {
      return null;
    }
    final warningColor = Theme.of(context).colorScheme.error;
    final continueColor = const Color(0xFFFF9F1A);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (showErrorText)
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            decoration: BoxDecoration(
              color: warningColor.withValues(alpha: 0.08),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Text(
              errorText,
              style: TextStyle(
                fontSize: 12 * _chatTextScale,
                color: warningColor,
                height: 1.4,
              ),
            ),
          ),
        if (showContinueButton) ...[
          if (showErrorText) const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: continueColor.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    LegacyTextLocalizer.isEnglish
                        ? 'Interrupted. Continue from this turn.'
                        : '已中断，点击「继续」从当前轮恢复',
                    style: TextStyle(
                      fontSize: 12 * _chatTextScale,
                      color: continueColor.withValues(alpha: 0.96),
                      height: 1.4,
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                FilledButton.tonalIcon(
                  onPressed: onContinueAgentMessage,
                  icon: const Icon(Icons.play_arrow_rounded, size: 16),
                  label: Text(
                    LegacyTextLocalizer.isEnglish ? 'Continue' : '继续',
                  ),
                  style: FilledButton.styleFrom(
                    foregroundColor: Colors.white,
                    backgroundColor: continueColor,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 8,
                    ),
                    visualDensity: VisualDensity.compact,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                ),
              ],
            ),
          ),
        ],
        if (showRetryButton)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: onRetryAgentMessage,
              icon: const Icon(Icons.refresh_rounded, size: 16),
              label: Text(LegacyTextLocalizer.isEnglish ? 'Retry' : '重试本轮'),
              style: TextButton.styleFrom(
                padding: EdgeInsets.zero,
                visualDensity: VisualDensity.compact,
                foregroundColor: warningColor,
              ),
            ),
          ),
      ],
    );
  }

  Widget? _buildTurnUsageFooter(BuildContext context) {
    final usage = message.turnUsage;
    if (usage == null || usage.isEmpty) {
      return null;
    }
    final ctx = _readIntValue(usage['ctx']);
    final input = _readIntValue(usage['in']);
    final output = _readIntValue(usage['out']);
    final cache = _readIntValue(usage['cache']);
    if (ctx == null && input == null && output == null && cache == null) {
      return null;
    }
    final textColor = _resolvedAiSecondaryTextColor(
      context,
    ).withValues(alpha: 0.72);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: Theme.of(
          context,
        ).colorScheme.surfaceContainerHighest.withValues(alpha: 0.32),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.av_timer_rounded, size: 12, color: textColor),
          const SizedBox(width: 6),
          Text(
            'ctx:${_formatUsageValue(ctx)}',
            style: TextStyle(
              fontSize: 11 * _chatTextScale,
              color: textColor,
              height: 1.1,
            ),
          ),
          const SizedBox(width: 9),
          _buildTurnUsageMetric(
            icon: Icons.arrow_downward_rounded,
            value: input,
            color: textColor,
          ),
          const SizedBox(width: 8),
          _buildTurnUsageMetric(
            icon: Icons.arrow_upward_rounded,
            value: output,
            color: textColor,
          ),
          const SizedBox(width: 8),
          _buildTurnUsageMetric(
            icon: LucideIcons.databaseZap,
            value: cache,
            color: textColor,
          ),
        ],
      ),
    );
  }

  Widget _buildTurnUsageMetric({
    required IconData icon,
    required int? value,
    required Color color,
  }) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 12, color: color),
        const SizedBox(width: 3),
        Text(
          _formatUsageValue(value),
          style: TextStyle(
            fontSize: 11 * _chatTextScale,
            color: color,
            height: 1.1,
          ),
        ),
      ],
    );
  }

  int? _readIntValue(dynamic raw) {
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    if (raw is String) return int.tryParse(raw.trim());
    return null;
  }

  String _formatUsageValue(int? value) {
    if (value == null || value <= 0) {
      return '0';
    }
    if (value >= 1000000) {
      final formatted = (value / 1000000).toStringAsFixed(
        value % 1000000 == 0 ? 0 : 1,
      );
      return '${formatted.replaceAll(RegExp(r'\.0$'), '')}m';
    }
    if (value >= 1000) {
      final formatted = (value / 1000).toStringAsFixed(
        value % 1000 == 0 ? 0 : 1,
      );
      return '${formatted.replaceAll(RegExp(r'\.0$'), '')}k';
    }
    return value.toString();
  }

  double? get _decodeTokensPerSecond {
    final raw = message.content?['decodeTokensPerSecond'];
    if (raw is double) return raw;
    if (raw is num) return raw.toDouble();
    if (raw is String) return double.tryParse(raw);
    return null;
  }

  /// 构建"总结中"指示器
  Widget _buildSummarizingIndicator() {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        SvgPicture.asset(
          'assets/common/summary_loading.svg',
          width: 16,
          height: 16,
          colorFilter: ColorFilter.mode(
            visualProfile.accentBlue,
            BlendMode.srcIn,
          ),
        ),
        const SizedBox(width: 6),
        Text(
          kSummarizingText,
          style: TextStyle(
            fontSize: 14 * _chatTextScale,
            color: visualProfile.accentBlue,
            fontWeight: FontWeight.w500,
          ),
        ),
        const SizedBox(width: 8),
        ThinkingDotsIndicator(
          dotColor: visualProfile.accentBlue,
          dotSize: 6.0,
          spacing: 3.0,
        ),
      ],
    );
  }

  /// 构建"总结如下"标题
  Widget _buildSummaryCompleteHeader() {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        SvgPicture.asset(
          'assets/common/summary_complete.svg',
          width: 16,
          height: 16,
          colorFilter: ColorFilter.mode(
            visualProfile.accentGreen,
            BlendMode.srcIn,
          ),
        ),
        const SizedBox(width: 6),
        Text(
          kSummaryCompleteText,
          style: TextStyle(
            fontSize: 14 * _chatTextScale,
            color: visualProfile.accentGreen,
            fontWeight: FontWeight.w500,
          ),
        ),
      ],
    );
  }

  /// 构建卡片消息
  Widget _buildCardMessage(BuildContext context) {
    final cardData = Map<String, dynamic>.from(message.cardData ?? {});
    cardData.putIfAbsent('cardId', () => message.contentId ?? message.id);

    // 卡片消息撑满聊天框宽度，减去头像和间距的宽度
    return SizedBox(
      width: double.infinity,
      child: CardWidgetFactory.createCard(
        cardData,
        onBeforeTaskExecute: onBeforeTaskExecute,
        onRequestAuthorize: onRequestAuthorize,
        onCancelTask: onCancelTask,
        enableThinkingCollapse: enableThinkingCollapse,
        thinkingAutoCollapseOnComplete: thinkingAutoCollapseOnComplete,
        useAgentToolPresentation: useAgentToolPresentation,
        showThinkingAvatarOverride: showThinkingAvatarOverride,
        parentScrollController: parentScrollController,
        onParentScrollHandoff: onParentScrollHandoff,
        onStreamingTextLayoutChanged: onStreamingTextLayoutChanged,
        appearanceConfig: appearanceConfig,
        visualProfile: visualProfile,
      ),
    );
  }
}
