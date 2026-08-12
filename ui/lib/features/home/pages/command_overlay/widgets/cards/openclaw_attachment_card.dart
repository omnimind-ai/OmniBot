import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:dio/dio.dart';
import 'package:path_provider/path_provider.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/image/cached_image.dart';

class OpenClawAttachmentCard extends StatefulWidget {
  final Map<String, dynamic> attachment;

  const OpenClawAttachmentCard({super.key, required this.attachment});

  @override
  State<OpenClawAttachmentCard> createState() => _OpenClawAttachmentCardState();
}

class _OpenClawAttachmentCardState extends State<OpenClawAttachmentCard> {
  static const MethodChannel _fileSaveChannel = MethodChannel(
    'cn.com.omnimind.bot/file_save',
  );
  bool _downloading = false;
  bool _saved = false;
  String _savedPath = '';
  String _downloadError = '';

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final attachment = widget.attachment;
    final mimeType = attachment['mimeType']?.toString() ?? '';
    final fileName = attachment['fileName']?.toString() ?? '';
    final type = attachment['type']?.toString() ?? '';
    final url = attachment['url']?.toString() ?? '';
    final path = attachment['path']?.toString() ?? '';
    final isImage = mimeType.startsWith('image/') || type == 'image';
    final sizeBytes = _parseSizeBytes(attachment['size']);

    final name = fileName.isNotEmpty ? fileName : (isImage ? '图片附件' : '文件附件');
    final size = sizeBytes != null ? _formatBytes(sizeBytes) : '';
    final hasUrl = url.isNotEmpty;
    final metaText = _buildMetaText(mimeType, size, path);
    final canDownload = hasUrl && !_downloading && !_saved;

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: isDark ? palette.borderSubtle : const Color(0xFFE0E0E0),
        ),
        color: isDark ? palette.surfaceSecondary : const Color(0xFFF8F9FB),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (isImage) _buildImagePreview(url: url),
          if (isImage) const SizedBox(height: 8),
          Row(
            children: [
              Icon(
                isImage
                    ? Icons.image_outlined
                    : Icons.insert_drive_file_outlined,
                size: 20,
                color: isDark ? palette.textSecondary : const Color(0xFF6B7280),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  name,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 14,
                    color: palette.textPrimary,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          if (metaText.isNotEmpty)
            Text(
              metaText,
              style: TextStyle(fontSize: 12, color: palette.textTertiary),
            ),
          if (_downloading) const SizedBox(height: 8),
          if (_downloading)
            Text(
              '正在保存到本地…',
              style: TextStyle(fontSize: 12, color: palette.textSecondary),
            ),
          if (_saved) const SizedBox(height: 8),
          if (_saved)
            const Text(
              '已保存到本地',
              style: TextStyle(fontSize: 12, color: Color(0xFF2F7A4A)),
            ),
          if (_saved && _savedPath.isNotEmpty) const SizedBox(height: 4),
          if (_saved && _savedPath.isNotEmpty)
            SelectableText(
              _savedPath,
              style: TextStyle(fontSize: 11, color: palette.textTertiary),
            ),
          if (_downloadError.isNotEmpty) const SizedBox(height: 8),
          if (_downloadError.isNotEmpty)
            Text(
              _downloadError,
              style: const TextStyle(fontSize: 12, color: Color(0xFFC62828)),
            ),
          if (hasUrl) const SizedBox(height: 8),
          if (hasUrl)
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '链接：',
                  style: TextStyle(fontSize: 12, color: palette.textTertiary),
                ),
                SelectableText(
                  _normalizeUrl(url),
                  style: const TextStyle(
                    fontSize: 12,
                    color: Color(0xFF4F83FF),
                  ),
                ),
              ],
            ),
          if (hasUrl) const SizedBox(height: 8),
          if (hasUrl)
            Align(
              alignment: Alignment.centerLeft,
              child: Wrap(
                spacing: 8,
                children: [
                  TextButton.icon(
                    onPressed: canDownload ? () => _downloadToFile(url) : null,
                    icon: Icon(
                      _saved
                          ? Icons.check_circle_outline
                          : Icons.download_outlined,
                      size: 16,
                    ),
                    label: Text(
                      _saved ? '已保存' : (_downloading ? '保存中' : '下载保存'),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildImagePreview({String? url}) {
    if (url != null && url.isNotEmpty) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: CachedImage(
          imageUrl: url,
          fit: BoxFit.cover,
          width: double.infinity,
        ),
      );
    }
    return Container(
      height: 120,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(8),
        color: const Color(0xFFF1F3F5),
      ),
      child: const Icon(Icons.broken_image_outlined, color: Color(0xFFB0B7C3)),
    );
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '${bytes}B';
    final kb = bytes / 1024;
    if (kb < 1024) return '${kb.toStringAsFixed(1)}KB';
    final mb = kb / 1024;
    return '${mb.toStringAsFixed(1)}MB';
  }

  String _buildMetaText(String mimeType, String size, String path) {
    final parts = <String>[];
    if (mimeType.isNotEmpty) parts.add(mimeType);
    if (size.isNotEmpty) parts.add(size);
    if (path.isNotEmpty) parts.add(path);
    return parts.join(' · ');
  }

  int? _parseSizeBytes(dynamic raw) {
    if (raw == null) return null;
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    return int.tryParse(raw.toString());
  }

  Future<void> _downloadToFile(String url) async {
    if (_downloading || _saved) return;
    String? tempPath;
    setState(() {
      _downloading = true;
      _downloadError = '';
    });
    try {
      final normalized = _normalizeUrl(url);
      if (normalized.isEmpty) {
        setState(() {
          _downloadError = '链接为空';
        });
        return;
      }
      final fileName = _resolveFileName(fallbackUrl: normalized);
      final dio = Dio();
      final headers = <String, dynamic>{};
      final token = _resolveAuthToken();
      if (token.isNotEmpty) {
        headers['Authorization'] = 'Bearer $token';
        headers['X-OpenClaw-Token'] = token;
      }
      final downloadRes = await dio.get<List<int>>(
        normalized,
        options: Options(headers: headers, responseType: ResponseType.bytes),
      );
      final bytes = downloadRes.data;
      if (bytes == null || bytes.isEmpty) {
        setState(() {
          _downloadError = '下载失败：文件为空';
        });
        return;
      }

      final tempDir = await getTemporaryDirectory();
      tempPath = '${tempDir.path}/omniapp_$fileName';
      await File(tempPath).writeAsBytes(bytes, flush: true);

      final mimeType = widget.attachment['mimeType']?.toString();
      final resultPath = await _fileSaveChannel.invokeMethod<String>(
        'saveFileWithSystemDialog',
        <String, dynamic>{
          'sourcePath': tempPath,
          'fileName': fileName,
          'mimeType': mimeType,
        },
      );
      if (resultPath == null || resultPath.isEmpty) {
        setState(() {
          _downloadError = '已取消保存';
        });
        return;
      }
      if (!mounted) return;
      setState(() {
        _saved = true;
        _savedPath = resultPath;
        _downloadError = '';
      });
    } on MissingPluginException catch (e) {
      if (!mounted) return;
      setState(() {
        _downloadError = '系统保存面板不可用：${e.message ?? e.toString()}';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _downloadError = '保存失败：$e';
      });
    } finally {
      if (tempPath != null) {
        try {
          await File(tempPath).delete();
        } on FileSystemException {
          // The temporary file may already have been removed by the host picker.
        }
      }
      if (mounted) {
        setState(() => _downloading = false);
      }
    }
  }

  String _resolveFileName({String? fallbackUrl}) {
    final attachment = widget.attachment;
    final fromMeta = attachment['fileName']?.toString() ?? '';
    if (fromMeta.isNotEmpty) return fromMeta;
    final url = fallbackUrl ?? attachment['url']?.toString() ?? '';
    if (url.isNotEmpty) {
      try {
        final uri = Uri.parse(url);
        final last = uri.pathSegments.isNotEmpty ? uri.pathSegments.last : '';
        if (last.isNotEmpty) return last;
      } catch (_) {}
    }
    final now = DateTime.now().millisecondsSinceEpoch;
    return 'attachment_$now';
  }

  String _resolveAuthToken() {
    final attachment = widget.attachment;
    final token = attachment['authToken']?.toString() ?? '';
    if (token.isNotEmpty) return token;
    return attachment['token']?.toString() ?? '';
  }

  String _normalizeUrl(String raw) {
    final trimmed = raw.trim();
    if (trimmed.isEmpty) return '';
    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
      return trimmed;
    }
    return 'http://$trimmed';
  }

}
