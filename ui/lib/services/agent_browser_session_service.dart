import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';

class AgentBrowserPreviewFrame {
  const AgentBrowserPreviewFrame({
    required this.workspaceId,
    required this.currentUrl,
    required this.title,
    required this.imageDataUrl,
    this.activeTabId,
    this.isLoading = false,
    this.imageWidth,
    this.imageHeight,
  });

  final String workspaceId;
  final String currentUrl;
  final String title;
  final String imageDataUrl;
  final int? activeTabId;
  final bool isLoading;
  final int? imageWidth;
  final int? imageHeight;

  factory AgentBrowserPreviewFrame.fromMap(Map<String, dynamic> map) {
    return AgentBrowserPreviewFrame(
      workspaceId: (map['workspaceId'] ?? '').toString(),
      currentUrl: (map['currentUrl'] ?? '').toString(),
      title: (map['title'] ?? '').toString(),
      imageDataUrl: (map['imageDataUrl'] ?? '').toString(),
      activeTabId: _asInt(map['activeTabId']),
      isLoading: map['isLoading'] == true,
      imageWidth: _asInt(map['imageWidth']),
      imageHeight: _asInt(map['imageHeight']),
    );
  }

  static int? _asInt(dynamic raw) {
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    if (raw is String) return int.tryParse(raw.trim());
    return null;
  }
}

class AgentBrowserSessionService {
  AgentBrowserSessionService._();

  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/AgentBrowserSession',
  );

  static const String platformViewType =
      'cn.com.omnimind.bot/agent_browser_view';

  static Future<ChatBrowserSessionSnapshot?> getLiveSessionSnapshot() {
    return getSnapshot();
  }

  static Future<ChatBrowserSessionSnapshot?> getSnapshot() {
    return _invokeSnapshot('getSnapshot');
  }

  static Future<AgentBrowserPreviewFrame?> capturePreviewFrame({
    String? workspaceId,
    int maxWidth = 420,
  }) async {
    try {
      final requestedWorkspaceId = workspaceId?.trim() ?? '';
      final rawResult = await _channel
          .invokeMethod<dynamic>('capturePreviewFrame', <String, dynamic>{
            'maxWidth': maxWidth,
            if (requestedWorkspaceId.isNotEmpty)
              'workspaceId': requestedWorkspaceId,
          });
      final normalizedResult = _normalizeMap(rawResult);
      if (normalizedResult == null ||
          normalizedResult['available'] != true ||
          (normalizedResult['imageDataUrl'] ?? '').toString().isEmpty) {
        return null;
      }
      final frame = AgentBrowserPreviewFrame.fromMap(normalizedResult);
      if (requestedWorkspaceId.isNotEmpty &&
          frame.workspaceId.trim() != requestedWorkspaceId) {
        return null;
      }
      return frame;
    } on PlatformException {
      rethrow;
    } on MissingPluginException {
      return null;
    }
  }

  static Future<ChatBrowserSessionSnapshot?> navigate(
    String url, {
    int? tabId,
  }) {
    return _invokeSnapshot('navigate', <String, dynamic>{
      'url': _normalizeUrl(url),
      if (tabId != null) 'tabId': tabId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> reload({int? tabId}) {
    return _invokeSnapshot('reload', <String, dynamic>{
      if (tabId != null) 'tabId': tabId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> stopLoading({int? tabId}) {
    return _invokeSnapshot('stopLoading', <String, dynamic>{
      if (tabId != null) 'tabId': tabId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> goBack({int? tabId}) {
    return _invokeSnapshot('goBack', <String, dynamic>{
      if (tabId != null) 'tabId': tabId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> goForward({int? tabId}) {
    return _invokeSnapshot('goForward', <String, dynamic>{
      if (tabId != null) 'tabId': tabId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> newTab({String? url}) {
    return _invokeSnapshot('newTab', <String, dynamic>{
      if (url != null && url.trim().isNotEmpty) 'url': _normalizeUrl(url),
    });
  }

  static Future<ChatBrowserSessionSnapshot?> selectTab(int tabId) {
    return _invokeSnapshot('selectTab', <String, dynamic>{'tabId': tabId});
  }

  static Future<ChatBrowserSessionSnapshot?> closeTab(int tabId) {
    return _invokeSnapshot('closeTab', <String, dynamic>{'tabId': tabId});
  }

  static Future<ChatBrowserSessionSnapshot?> closeAllTabs(
    List<int> tabIds,
  ) async {
    ChatBrowserSessionSnapshot? latest;
    for (final tabId in tabIds) {
      latest = await closeTab(tabId);
    }
    return latest;
  }

  static Future<ChatBrowserSessionSnapshot?> toggleDesktopMode({int? tabId}) {
    return _invokeSnapshot('toggleDesktopMode', <String, dynamic>{
      if (tabId != null) 'tabId': tabId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> toggleBookmark() {
    return _invokeSnapshot('toggleBookmark');
  }

  static Future<ChatBrowserSessionSnapshot?> removeBookmark(String url) {
    return _invokeSnapshot('removeBookmark', <String, dynamic>{'url': url});
  }

  static Future<ChatBrowserSessionSnapshot?> openHistoryEntry(
    String url, {
    int? tabId,
  }) {
    return _invokeSnapshot('openHistoryEntry', <String, dynamic>{
      'url': url,
      if (tabId != null) 'tabId': tabId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> clearHistory() {
    return _invokeSnapshot('clearHistory');
  }

  static Future<ChatBrowserSessionSnapshot?> clearCurrentSiteSession({
    int? tabId,
  }) {
    return _invokeSnapshot('clearCurrentSiteSession', <String, dynamic>{
      if (tabId != null) 'tabId': tabId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> pauseDownload(String taskId) {
    return _invokeSnapshot('pauseDownload', <String, dynamic>{
      'taskId': taskId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> resumeDownload(String taskId) {
    return _invokeSnapshot('resumeDownload', <String, dynamic>{
      'taskId': taskId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> cancelDownload(String taskId) {
    return _invokeSnapshot('cancelDownload', <String, dynamic>{
      'taskId': taskId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> retryDownload(String taskId) {
    return _invokeSnapshot('retryDownload', <String, dynamic>{
      'taskId': taskId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> deleteDownload(
    String taskId, {
    bool deleteFile = false,
  }) {
    return _invokeSnapshot('deleteDownload', <String, dynamic>{
      'taskId': taskId,
      'deleteFile': deleteFile,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> openDownloadedFile(String taskId) {
    return _invokeSnapshot('openDownloadedFile', <String, dynamic>{
      'taskId': taskId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> openDownloadLocation(
    String taskId,
  ) {
    return _invokeSnapshot('openDownloadLocation', <String, dynamic>{
      'taskId': taskId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> installUserscriptFromUrl(
    String url,
  ) {
    return _invokeSnapshot('installUserscriptFromUrl', <String, dynamic>{
      'url': url.trim(),
    });
  }

  static Future<ChatBrowserSessionSnapshot?> importUserscriptSource({
    required String source,
    required String sourceName,
    String? sourceUrl,
  }) {
    return _invokeSnapshot('importUserscriptSource', <String, dynamic>{
      'source': source,
      'sourceName': sourceName,
      if (sourceUrl != null) 'sourceUrl': sourceUrl,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> importUserscriptFile() async {
    final file = await FilePicker.pickFile(
      type: FileType.custom,
      allowedExtensions: const <String>['js'],
    );
    if (file == null) {
      return null;
    }
    final name = file.name.isEmpty ? 'userscript.user.js' : file.name;
    if (!name.endsWith('.js')) {
      throw const FormatException('Selected file is not a JavaScript file');
    }
    String source;
    try {
      source = String.fromCharCodes(await file.readAsBytes());
    } catch (_) {
      if (file.path == null) {
        throw const FileSystemException('Unable to read userscript file');
      }
      source = await File(file.path!).readAsString();
    }
    return importUserscriptSource(source: source, sourceName: name);
  }

  static Future<ChatBrowserSessionSnapshot?> confirmUserscriptInstall() {
    return _invokeSnapshot('confirmUserscriptInstall');
  }

  static Future<ChatBrowserSessionSnapshot?> cancelUserscriptInstall() {
    return _invokeSnapshot('cancelUserscriptInstall');
  }

  static Future<ChatBrowserSessionSnapshot?> setUserscriptEnabled(
    int scriptId,
    bool enabled,
  ) {
    return _invokeSnapshot('setUserscriptEnabled', <String, dynamic>{
      'scriptId': scriptId,
      'enabled': enabled,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> deleteUserscript(int scriptId) {
    return _invokeSnapshot('deleteUserscript', <String, dynamic>{
      'scriptId': scriptId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> checkUserscriptUpdate(
    int scriptId,
  ) {
    return _invokeSnapshot('checkUserscriptUpdate', <String, dynamic>{
      'scriptId': scriptId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> invokeUserscriptMenuCommand(
    String commandId,
  ) {
    return _invokeSnapshot('invokeUserscriptMenuCommand', <String, dynamic>{
      'commandId': commandId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> confirmExternalOpen(
    String requestId,
  ) {
    return _invokeSnapshot('confirmExternalOpen', <String, dynamic>{
      'requestId': requestId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> cancelExternalOpen(
    String requestId,
  ) {
    return _invokeSnapshot('cancelExternalOpen', <String, dynamic>{
      'requestId': requestId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> resolveDialog({
    required String requestId,
    required bool accept,
    String? promptValue,
  }) {
    return _invokeSnapshot('resolveDialog', <String, dynamic>{
      'requestId': requestId,
      'accept': accept,
      if (promptValue != null) 'promptValue': promptValue,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> grantPermission(String requestId) {
    return _invokeSnapshot('grantPermission', <String, dynamic>{
      'requestId': requestId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> denyPermission(String requestId) {
    return _invokeSnapshot('denyPermission', <String, dynamic>{
      'requestId': requestId,
    });
  }

  static Future<ChatBrowserSessionSnapshot?> _invokeSnapshot(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    try {
      final rawResult = await _channel.invokeMethod<dynamic>(method, arguments);
      final normalizedResult = _normalizeMap(rawResult);
      if (normalizedResult == null || normalizedResult['available'] != true) {
        return null;
      }
      return ChatBrowserSessionSnapshot.fromMap(normalizedResult);
    } on PlatformException {
      rethrow;
    } on MissingPluginException {
      return null;
    }
  }

  static Map<String, dynamic>? _normalizeMap(dynamic raw) {
    if (raw is! Map) return null;
    return raw.map((key, value) => MapEntry(key.toString(), value));
  }

  static String _normalizeUrl(String value) {
    final trimmed = value.trim();
    if (trimmed.isEmpty) {
      return trimmed;
    }
    if (trimmed.startsWith('http://') ||
        trimmed.startsWith('https://') ||
        trimmed.startsWith('omnibot://')) {
      return trimmed;
    }
    return 'https://$trimmed';
  }
}
