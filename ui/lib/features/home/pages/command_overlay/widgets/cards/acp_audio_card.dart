import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';
import 'package:ui/theme/theme_context.dart';

/// Renders the official ACP Audio content block in the shared card route.
///
/// The source can be a URL or an inline base64 data URL. It deliberately does
/// not depend on a Harness name or on the workspace resource service, so the
/// same card works for every ACP adapter.
class AcpAudioCard extends StatefulWidget {
  const AcpAudioCard({super.key, required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  State<AcpAudioCard> createState() => _AcpAudioCardState();
}

class _AcpAudioCardState extends State<AcpAudioCard> {
  final AudioPlayer _player = AudioPlayer();
  bool _ready = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    try {
      final source = _resolveSource(widget.cardData);
      if (source == null) {
        throw StateError('ACP audio has no playable source');
      }
      if (source is Uri) {
        await _player.setUrl(source.toString());
      } else {
        await _player.setAudioSource(source as AudioSource);
      }
      if (mounted) setState(() => _ready = true);
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    }
  }

  Object? _resolveSource(Map<String, dynamic> data) {
    final rawUrl = (data['audioUrl'] ?? data['url'] ?? data['uri'])
        ?.toString()
        .trim();
    if (rawUrl != null &&
        (rawUrl.startsWith('http://') || rawUrl.startsWith('https://'))) {
      return Uri.parse(rawUrl);
    }
    final rawData = (data['audioDataUrl'] ?? data['data'])?.toString().trim();
    if (rawData == null || rawData.isEmpty) return null;
    final comma = rawData.indexOf(',');
    final mimeType = (data['mimeType'] ?? 'audio/mpeg').toString();
    final encoded = rawData.startsWith('data:') && comma >= 0
        ? rawData.substring(comma + 1)
        : rawData;
    final bytes = base64Decode(encoded);
    return _AcpMemoryAudioSource(bytes, mimeType);
  }

  @override
  void dispose() {
    _player.dispose();
    super.dispose();
  }

  Future<void> _toggle() async {
    if (!_ready) return;
    if (_player.playing) {
      await _player.pause();
    } else {
      if (_player.processingState == ProcessingState.completed) {
        await _player.seek(Duration.zero);
      }
      await _player.play();
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final title = (widget.cardData['title'] ?? '音频').toString();
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : const Color(0xFFF8F9FB),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: context.isDarkTheme
              ? palette.borderSubtle
              : const Color(0xFFE0E0E0),
        ),
      ),
      child: Row(
        children: [
          IconButton(
            tooltip: '播放音频',
            onPressed: _ready ? _toggle : null,
            icon: StreamBuilder<PlayerState>(
              stream: _player.playerStateStream,
              builder: (context, snapshot) => Icon(
                snapshot.data?.playing == true
                    ? Icons.pause_circle_outline
                    : Icons.play_circle_outline,
              ),
            ),
          ),
          Expanded(
            child: Text(
              _error == null ? title : '$title（无法播放）',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(color: palette.textPrimary),
            ),
          ),
          if (_error == null && !_ready)
            const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
        ],
      ),
    );
  }
}

class _AcpMemoryAudioSource extends StreamAudioSource {
  _AcpMemoryAudioSource(this._bytes, this._contentType);

  final Uint8List _bytes;
  final String _contentType;

  @override
  Future<StreamAudioResponse> request([int? start, int? end]) async {
    final from = start ?? 0;
    final to = end ?? _bytes.length;
    return StreamAudioResponse(
      sourceLength: _bytes.length,
      contentLength: to - from,
      offset: from,
      stream: Stream.value(_bytes.sublist(from, to)),
      contentType: _contentType,
    );
  }
}
