class VoiceSegmentExtractionResult {
  final List<String> segments;
  final int nextIndex;

  const VoiceSegmentExtractionResult({
    required this.segments,
    required this.nextIndex,
  });
}

class SceneVoiceTextProcessing {
  static const Set<String> _segmentTerminators = <String>{
    '。',
    '！',
    '？',
    '!',
    '?',
    '；',
    ';',
    '：',
    ':',
    '\n',
  };

  static VoiceSegmentExtractionResult extractSealedSegments({
    required String fullText,
    required int fromIndex,
    required bool isFinal,
  }) {
    if (fullText.isEmpty) {
      return const VoiceSegmentExtractionResult(
        segments: <String>[],
        nextIndex: 0,
      );
    }
    final safeFromIndex = fromIndex.clamp(0, fullText.length);
    final segments = <String>[];
    var insideFence = _isInsideCodeFence(fullText, safeFromIndex);
    var segmentStart = safeFromIndex;
    var cursor = safeFromIndex;

    while (cursor < fullText.length) {
      if (_startsFence(fullText, cursor)) {
        insideFence = !insideFence;
        cursor += 3;
        continue;
      }
      final character = fullText[cursor];
      if (!insideFence && _segmentTerminators.contains(character)) {
        final sanitized = sanitizeForSpeech(
          fullText.substring(segmentStart, cursor + 1),
        );
        if (sanitized.isNotEmpty) {
          segments.add(sanitized);
        }
        segmentStart = cursor + 1;
      }
      cursor += 1;
    }

    if (isFinal && segmentStart < fullText.length) {
      final sanitized = sanitizeForSpeech(fullText.substring(segmentStart));
      if (sanitized.isNotEmpty) {
        segments.add(sanitized);
      }
      segmentStart = fullText.length;
    }

    return VoiceSegmentExtractionResult(
      segments: segments,
      nextIndex: segmentStart,
    );
  }

  static String sanitizeForSpeech(String rawText) {
    var value = rawText;
    value = value.replaceAll(RegExp(r'```[\s\S]*?```'), ' ');
    value = value.replaceAllMapped(
      RegExp(r'!\[([^\]]*)\]\(([^)]+)\)'),
      (match) => (match.group(1) ?? '').trim(),
    );
    value = value.replaceAllMapped(
      RegExp(r'\[([^\]]+)\]\(([^)]+)\)'),
      (match) => (match.group(1) ?? '').trim(),
    );
    value = value.replaceAllMapped(
      RegExp(r'`([^`]+)`'),
      (match) => (match.group(1) ?? '').trim(),
    );
    value = value.replaceAll(RegExp(r'https?://\S+'), ' ');
    value = value.replaceAll(RegExp(r'<[^>]+>'), ' ');
    value = value.replaceAll(RegExp(r'(^|\n)\s{0,3}[#>*-]+\s*'), ' ');
    value = value.replaceAll(RegExp(r'(^|\n)\s{0,3}\d+\.\s*'), ' ');
    value = value.replaceAll('**', '');
    value = value.replaceAll('__', '');
    value = value.replaceAll('~~', '');
    value = value.replaceAll(RegExp(r'\s+'), ' ');
    return value.trim();
  }

  static bool _startsFence(String value, int index) {
    return index + 2 < value.length &&
        value.substring(index, index + 3) == '```';
  }

  static bool _isInsideCodeFence(String value, int index) {
    var cursor = 0;
    var insideFence = false;
    while (cursor < index) {
      if (_startsFence(value, cursor)) {
        insideFence = !insideFence;
        cursor += 3;
        continue;
      }
      cursor += 1;
    }
    return insideFence;
  }
}
