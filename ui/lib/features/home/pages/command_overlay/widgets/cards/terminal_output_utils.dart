import 'dart:convert';

import 'package:flutter/material.dart';

const Color kTerminalSurfaceBlack = Color(0xFF06080C);
const Color kTerminalSurfaceBlackElevated = Color(0xFF0C1016);
const Color kTerminalSurfaceShadow = Color(0x52000000);

class TerminalOutputUtils {
  static String trim(String value) {
    // This value feeds the detail sheet and its copy action.  Preview widgets
    // decide their own presentation length, but this shared source must not
    // discard a user's terminal result before either can render it.
    return value;
  }

  static String buildDisplayOutput({
    required String terminalOutput,
    required String rawResultJson,
    required String resultPreviewJson,
  }) {
    if (terminalOutput.trim().isNotEmpty) {
      return trim(terminalOutput);
    }

    final rawMap = _decodeJsonMap(rawResultJson);
    final previewMap = _decodeJsonMap(resultPreviewJson);
    final source = rawMap.isNotEmpty ? rawMap : previewMap;
    if (source.isEmpty) {
      return '';
    }

    final directTerminalOutput = (source['terminalOutput'] ?? '').toString();
    if (directTerminalOutput.trim().isNotEmpty) {
      return trim(directTerminalOutput);
    }

    final segments = <String>[];
    final liveFallbackReason = (source['liveFallbackReason'] ?? '').toString();
    if (liveFallbackReason.trim().isNotEmpty) {
      segments.add('[实时输出已回退]\n$liveFallbackReason');
    }

    final stdout = (source['stdout'] ?? '').toString().trimRight();
    if (stdout.isNotEmpty) {
      segments.add(stdout);
    }

    final stderr = (source['stderr'] ?? '').toString().trimRight();
    if (stderr.isNotEmpty) {
      segments.add(segments.isEmpty ? stderr : '[stderr]\n$stderr');
    }

    final errorMessage = (source['errorMessage'] ?? '').toString().trim();
    if (errorMessage.isNotEmpty &&
        !segments.any((segment) => segment.contains(errorMessage))) {
      segments.add(errorMessage);
    }

    return trim(segments.join('\n\n'));
  }

  static Map<String, dynamic> decodeJsonMap(String value) {
    return _decodeJsonMap(value);
  }

  static Map<String, dynamic> _decodeJsonMap(String value) {
    final text = value.trim();
    if (text.isEmpty) return const {};
    try {
      final decoded = jsonDecode(text);
      if (decoded is Map) {
        return decoded.map((key, value) => MapEntry(key.toString(), value));
      }
    } catch (_) {
      return const {};
    }
    return const {};
  }
}

class AnsiTextSpanBuilder {
  static const Map<int, Color> _standardColors = {
    30: Color(0xFF1F2937),
    31: Color(0xFFE06C75),
    32: Color(0xFF98C379),
    33: Color(0xFFE5C07B),
    34: Color(0xFF61AFEF),
    35: Color(0xFFC678DD),
    36: Color(0xFF56B6C2),
    37: Color(0xFFE5E7EB),
    90: Color(0xFF6B7280),
    91: Color(0xFFF7768E),
    92: Color(0xFF9ECE6A),
    93: Color(0xFFE0AF68),
    94: Color(0xFF7AA2F7),
    95: Color(0xFFBB9AF7),
    96: Color(0xFF7DCFFF),
    97: Color(0xFFF9FAFB),
  };

  static final RegExp _sgrPattern = RegExp(r'\x1B\[([0-9;]*)m');
  static final RegExp _unsupportedAnsiPattern = RegExp(
    r'\x1B\[[0-9;?]*[A-Za-z]',
  );

  static TextSpan build(String text, TextStyle baseStyle) {
    if (text.isEmpty) {
      return TextSpan(text: text, style: baseStyle);
    }

    final spans = <InlineSpan>[];
    var isBold = false;
    Color? foregroundColor;
    var cursor = 0;

    for (final match in _sgrPattern.allMatches(text)) {
      if (match.start > cursor) {
        spans.add(
          TextSpan(
            text: _stripUnsupportedAnsi(text.substring(cursor, match.start)),
            style: _resolveStyle(
              baseStyle,
              isBold: isBold,
              foregroundColor: foregroundColor,
            ),
          ),
        );
      }

      final codesText = match.group(1) ?? '';
      final codes = codesText.isEmpty
          ? const <int>[0]
          : codesText
                .split(';')
                .map((code) => int.tryParse(code) ?? 0)
                .toList();

      for (final code in codes) {
        switch (code) {
          case 0:
            isBold = false;
            foregroundColor = null;
            break;
          case 1:
            isBold = true;
            break;
          case 22:
            isBold = false;
            break;
          case 39:
            foregroundColor = null;
            break;
          default:
            if (_standardColors.containsKey(code)) {
              foregroundColor = _standardColors[code];
            }
            break;
        }
      }

      cursor = match.end;
    }

    if (cursor < text.length) {
      spans.add(
        TextSpan(
          text: _stripUnsupportedAnsi(text.substring(cursor)),
          style: _resolveStyle(
            baseStyle,
            isBold: isBold,
            foregroundColor: foregroundColor,
          ),
        ),
      );
    }

    if (spans.isEmpty) {
      return TextSpan(text: _stripUnsupportedAnsi(text), style: baseStyle);
    }

    return TextSpan(style: baseStyle, children: spans);
  }

  static String _stripUnsupportedAnsi(String value) {
    return value.replaceAll(_unsupportedAnsiPattern, '');
  }

  static TextStyle _resolveStyle(
    TextStyle baseStyle, {
    required bool isBold,
    required Color? foregroundColor,
  }) {
    return baseStyle.copyWith(
      color: foregroundColor ?? baseStyle.color,
      fontWeight: isBold ? FontWeight.w700 : baseStyle.fontWeight,
    );
  }
}
