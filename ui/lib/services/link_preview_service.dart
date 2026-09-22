import 'package:flutter/services.dart';
import 'package:ui/models/chat_link_preview.dart';

/// Transport and display DTOs only. Kotlin owns URL parsing, caching and HTTP.
class LinkPreviewService {
  static final instance = LinkPreviewService();
  static const maxPreviewsPerMessage = 3;
  static const _channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');

  Future<List<Map<String, dynamic>>?> reconcilePreviewMaps({
    required String text,
    dynamic existing,
    int maxCount = maxPreviewsPerMessage,
  }) async {
    try {
      final rows = await _channel.invokeListMethod<dynamic>(
        'reconcileChatLinkPreviews',
        {'text': text, 'existing': existing, 'maxCount': maxCount},
      );
      return rows
          ?.whereType<Map>()
          .map((row) => Map<String, dynamic>.from(row))
          .toList();
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  Future<ChatLinkPreview> loadPreview(String url) async {
    try {
      final row = await _channel.invokeMapMethod<String, dynamic>(
        'loadChatLinkPreview',
        {'url': url},
      );
      if (row != null) return ChatLinkPreview.fromJson(row);
    } on PlatformException {
      // A failed preview is a normal display state; the conversation stays usable.
    } on MissingPluginException {
      // Engines without the host service cannot fetch previews.
    }
    return ChatLinkPreview.failed(url);
  }
}
