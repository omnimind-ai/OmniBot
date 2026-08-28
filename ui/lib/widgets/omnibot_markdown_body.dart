import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_math_fork/flutter_math.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:markdown/markdown.dart' as md;
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/chat_drawer_gesture_guard.dart';
import 'package:ui/widgets/omnibot_resource_widgets.dart';

// ── Module-level reusable singletons ──
// flutter_markdown 通过 `!=` 检测 styleSheet 变化触发 _parseMarkdown()，
// 因此 styleSheet 必须保持引用稳定，否则每个 build 都会触发整段 AST 重解析。
// 同理，inline/block 语法和 codeTapHandler 也可全局共享，避免分配开销。

const OmnibotCodeTapHandler _kOmnibotCodeTapHandler = OmnibotCodeTapHandler();

final OmnibotInlineMathSyntax _kOmnibotInlineMathSyntax =
    OmnibotInlineMathSyntax();
final OmnibotInlineLinkSyntax _kOmnibotInlineLinkSyntax =
    OmnibotInlineLinkSyntax();
final OmnibotTrailingInlineSyntax _kOmnibotTrailingInlineSyntax =
    OmnibotTrailingInlineSyntax();
final RegExp _kMarkdownTablePipeCellSeparatorPattern = RegExp(r'\s\|\s');

final List<md.InlineSyntax> _kInlineSyntaxesWithoutTrailing =
    List<md.InlineSyntax>.unmodifiable(<md.InlineSyntax>[
      _kOmnibotInlineMathSyntax,
      _kOmnibotInlineLinkSyntax,
    ]);

final List<md.InlineSyntax> _kInlineSyntaxesWithTrailing =
    List<md.InlineSyntax>.unmodifiable(<md.InlineSyntax>[
      _kOmnibotInlineMathSyntax,
      _kOmnibotInlineLinkSyntax,
      _kOmnibotTrailingInlineSyntax,
    ]);

const List<md.BlockSyntax> _kBlockSyntaxes = <md.BlockSyntax>[
  OmnibotTableSyntax(),
  OmnibotMathBlockSyntax(),
];

bool _omnibotMarkdownNeedsSafetyPreflight(String source) {
  final lines = source.split('\n');
  for (var index = 0; index < lines.length; index += 1) {
    if (OmnibotTableSyntax._tableDividerPattern.hasMatch(lines[index]) ||
        lines[index].trimLeft().startsWith(r'$$')) {
      return true;
    }
  }
  return false;
}

bool _omnibotMarkdownIsParseable(
  String source, {
  required bool hasTrailingInline,
}) {
  if (!_omnibotMarkdownNeedsSafetyPreflight(source)) {
    return true;
  }
  try {
    final document = md.Document(
      blockSyntaxes: _kBlockSyntaxes,
      inlineSyntaxes: hasTrailingInline
          ? _kInlineSyntaxesWithTrailing
          : _kInlineSyntaxesWithoutTrailing,
      extensionSet: md.ExtensionSet.gitHubFlavored,
      encodeHtml: false,
    );
    document.parseLines(const LineSplitter().convert(source));
    return true;
  } catch (error, stack) {
    // Keep the content visible even if a future Markdown package or custom
    // syntax regression rejects one streamed snapshot.
    debugPrint('[Omni][Markdown] preflight failed: $error');
    debugPrintStack(stackTrace: stack);
    return false;
  }
}

// ── StyleSheet LRU cache ──
// `MarkdownStyleSheet.fromTheme(...).copyWith(...)` 是重型操作；同等条件下
// 复用同一引用可以让 flutter_markdown 在 didUpdateWidget 中跳过 _parseMarkdown，
// 从而避免每次父级 setState 都重新解析整段 markdown。
const int _kStyleSheetCacheCapacity = 8;
final LinkedHashMap<int, MarkdownStyleSheet> _styleSheetCache =
    LinkedHashMap<int, MarkdownStyleSheet>();

class OmnibotStreamingTableBlock {
  const OmnibotStreamingTableBlock({
    required this.leadingMarkdown,
    required this.tableMarkdown,
    required this.isCompleteTable,
  });

  final String leadingMarkdown;
  final String tableMarkdown;
  final bool isCompleteTable;
}

/// Repairs the small set of Markdown formatting mistakes that the configured
/// providers commonly produce while streaming.  This is deliberately a
/// renderer-boundary compatibility layer: the stored assistant content stays
/// untouched, and all Harnesses get the same treatment.
///
/// In particular, some provider responses omit the space after a heading
/// marker (`###标题`) and concatenate table rows (`| a | b || c | d |`).
/// Neither form is valid enough for flutter_markdown to recognize reliably,
/// which used to expose the Markdown source as plain text.  Code fences are
/// excluded so examples and tool payloads retain their exact contents.
String normalizeOmnibotMarkdown(String source) {
  if (source.isEmpty) {
    return source;
  }

  final lines = source.replaceAll('\r\n', '\n').split('\n');
  final normalized = <String>[];
  String? fenceCharacter;
  var fenceLength = 0;

  for (var lineIndex = 0; lineIndex < lines.length; lineIndex += 1) {
    final rawLine = lines[lineIndex];
    final isInsideFence = fenceCharacter != null;
    if (isInsideFence) {
      normalized.add(rawLine);
    } else {
      var line = rawLine;
      // A provider can glue the table header to the preceding prose while
      // still emitting a valid divider on the next line. Keep the prose as a
      // paragraph and move the first table pipe to its own line; otherwise the
      // custom table syntax sees a malformed header, retreats, and markdown's
      // block parser can report "parseLines is not advancing".
      if (lineIndex + 1 < lines.length &&
          OmnibotTableSyntax._tableDividerPattern.hasMatch(
            lines[lineIndex + 1],
          )) {
        final firstPipe = line.indexOf('|');
        if (firstPipe > 0 && line.substring(0, firstPipe).trim().isNotEmpty) {
          line =
              '${line.substring(0, firstPipe).trimRight()}\n${line.substring(firstPipe)}';
        }
      }
      // A missing newline between two table rows is emitted as `||`.  Restrict
      // this repair to table-shaped lines: `||` is also valid prose (for
      // example, a shell/boolean expression) and must not be turned into a
      // paragraph break.  The divider check covers tables whose first cell is
      // not preceded by a leading pipe.
      if (line.contains('||') &&
          (line.trimLeft().startsWith('|') ||
              line.contains(RegExp(r'\|\s*-{3,}')))) {
        line = line.replaceAll('||', '|\n|');
      }
      // Providers occasionally append the next heading directly after the
      // last table cell (`| value|### 下一节`).
      line = line.replaceAllMapped(
        RegExp(r'\|(?=#{1,6}(?!#)\S)'),
        (_) => '|\n\n',
      );
      // A table row is sometimes immediately followed by a bold section
      // label (`|value|**生活指数：**`).  Split only labels that end in a
      // colon and are truly glued to the preceding cell.  The look-behind
      // excludes the opening pipe and normal cells such as `| **湿度：** |`.
      line = line.replaceAllMapped(
        RegExp(r'(?<=\S)\|(?=\*\*[^*\n|]+(?:：|:)\*\*)'),
        (_) => '|\n\n',
      );
      // The same section labels can be glued to prose without a newline
      // (`72%**未来几天预报：**`).
      line = line.replaceAllMapped(
        RegExp(r'([^\s])(\*\*[^*\n]+(?:：|:)\*\*)'),
        (match) => '${match.group(1)}\n\n${match.group(2)}',
      );
      // Keep a horizontal rule separate from a following heading
      // (`少发---###⚠️`).
      line = line.replaceAllMapped(
        RegExp(r'---(?=#{1,6}(?!#)\S)'),
        (_) => '\n\n',
      );
      // A resource link card can be followed by a new section without the
      // provider emitting a newline (`[report](omnibot://...)### 概览`).
      // Split only a heading glued directly to a closing parenthesis; code
      // fences are excluded by the surrounding normalization boundary.
      line = line.replaceAllMapped(
        RegExp(r'(?<=\))(?=#{1,6}(?!#)(?:[ \t]+\S|\S))'),
        (_) => '\n\n',
      );
      // Provider list markers often omit the required space (`-☀️`), which
      // makes the whole list render as one paragraph.  Keep `*italic*` and
      // negative numbers intact: both are valid non-list prose at line start.
      line = line.replaceAllMapped(
        RegExp(r'^([ \t]{0,3}[-+])(?=[^\s\d\-+])'),
        (match) => '${match.group(1)} ',
      );
      // Ordered-list and blockquote markers are emitted without the optional
      // space by the same providers (`1.明天` and `>说明`).  Repair only a
      // marker at the beginning of a line so decimals and comparison prose
      // are not changed.
      line = line.replaceAllMapped(
        RegExp(r'^([ \t]{0,3}\d{1,9}[.)])(?=[^\s\d])'),
        (match) => '${match.group(1)} ',
      );
      line = line.replaceAllMapped(
        RegExp(r'^([ \t]{0,3}>)(?=\S)'),
        (match) => '${match.group(1)} ',
      );
      // Some generated list items are concatenated on one line and use an
      // emoji as the next marker (`-👕...-🚗...`).  Split those markers while
      // leaving ordinary hyphens in prose and numeric ranges unchanged.
      line = line.replaceAllMapped(
        RegExp(r'(?<=\S)-(?=[☀️👕🚗🤧🌤️⚠️])'),
        (_) => '\n- ',
      );
      line = line.split('\n').map(_normalizeOmnibotHeadingLine).join('\n');
      normalized.add(line);
    }

    final fence = _markdownFenceStartPattern.firstMatch(rawLine);
    if (fence == null) {
      continue;
    }
    final marker = fence.group(1)!;
    final character = marker.substring(0, 1);
    if (fenceCharacter == null) {
      fenceCharacter = character;
      fenceLength = marker.length;
    } else if (character == fenceCharacter && marker.length >= fenceLength) {
      fenceCharacter = null;
      fenceLength = 0;
    }
  }
  return normalized.join('\n');
}

String _normalizeOmnibotHeadingLine(String line) {
  return line.replaceFirstMapped(
    RegExp(r'^([ \t]{0,3}#{1,6})(?!#)(?=\S)'),
    (match) => '${match.group(1)} ',
  );
}

bool omnibotMarkdownContainsTableCandidate(String source) {
  source = normalizeOmnibotMarkdown(source);
  final lines = source.split('\n');
  for (var index = 0; index < lines.length; index++) {
    if (_isMarkdownLineInsideFencedCode(lines, index)) {
      continue;
    }
    if (_tryParseMarkdownTable(lines, index) != null) {
      return true;
    }
    if (omnibotMarkdownLineLooksLikeTableCandidate(lines[index])) {
      return true;
    }
  }
  return false;
}

OmnibotStreamingTableBlock? omnibotMarkdownTrailingTableBlock(String source) {
  source = normalizeOmnibotMarkdown(source);
  final lines = source.split('\n');
  final lastContentIndex = lines.lastIndexWhere(
    (line) => line.trim().isNotEmpty,
  );
  if (lastContentIndex == -1) {
    return null;
  }

  var index = 0;
  while (index <= lastContentIndex) {
    if (_isMarkdownLineInsideFencedCode(lines, index)) {
      index += 1;
      continue;
    }
    final table = _tryParseMarkdownTable(lines, index);
    if (table == null) {
      index += 1;
      continue;
    }
    final tableEndIndex = table.nextLineIndex - 1;
    if (tableEndIndex >= lastContentIndex) {
      return _buildTrailingTableBlock(
        lines,
        startIndex: index,
        endIndex: lastContentIndex,
        isCompleteTable: true,
      );
    }
    index = table.nextLineIndex;
  }

  int? candidateStartIndex;
  index = 0;
  while (index <= lastContentIndex) {
    if (_isMarkdownLineInsideFencedCode(lines, index)) {
      index += 1;
      continue;
    }
    final table = _tryParseMarkdownTable(lines, index);
    if (table != null) {
      candidateStartIndex = null;
      index = table.nextLineIndex;
      continue;
    }

    final line = lines[index];
    if (line.trim().isEmpty) {
      candidateStartIndex = null;
    } else if (omnibotMarkdownLineLooksLikeTableCandidate(line)) {
      candidateStartIndex ??= index;
    } else {
      candidateStartIndex = null;
    }
    index += 1;
  }

  final start = candidateStartIndex;
  if (start == null) {
    return null;
  }
  return _buildTrailingTableBlock(
    lines,
    startIndex: start,
    endIndex: lastContentIndex,
    isCompleteTable: false,
  );
}

OmnibotStreamingTableBlock _buildTrailingTableBlock(
  List<String> lines, {
  required int startIndex,
  required int endIndex,
  required bool isCompleteTable,
}) {
  final leading = startIndex <= 0
      ? ''
      : lines
            .sublist(0, startIndex)
            .join('\n')
            .replaceFirst(RegExp(r'\n+$'), '');
  return OmnibotStreamingTableBlock(
    leadingMarkdown: leading,
    tableMarkdown: lines.sublist(startIndex, endIndex + 1).join('\n'),
    isCompleteTable: isCompleteTable,
  );
}

String omnibotMarkdownWithoutTrailingTableCandidate(String source) {
  source = normalizeOmnibotMarkdown(source);
  final lines = source.split('\n');
  final lastContentIndex = lines.lastIndexWhere(
    (line) => line.trim().isNotEmpty,
  );
  if (lastContentIndex == -1) {
    return source;
  }

  int? candidateStartIndex;
  var index = 0;
  while (index <= lastContentIndex) {
    if (_isMarkdownLineInsideFencedCode(lines, index)) {
      index += 1;
      continue;
    }
    final table = _tryParseMarkdownTable(lines, index);
    if (table != null) {
      candidateStartIndex = null;
      index = table.nextLineIndex;
      continue;
    }

    final line = lines[index];
    if (line.trim().isEmpty) {
      candidateStartIndex = null;
    } else if (omnibotMarkdownLineLooksLikeTableCandidate(line)) {
      candidateStartIndex ??= index;
    } else {
      candidateStartIndex = null;
    }
    index += 1;
  }

  final start = candidateStartIndex;
  if (start == null) {
    return source;
  }
  final removalStart = _trailingTableCandidateRemovalStart(lines, start);
  return lines
      .sublist(0, removalStart)
      .join('\n')
      .replaceFirst(RegExp(r'\n+$'), '');
}

bool omnibotMarkdownLineLooksLikeTableCandidate(String line) {
  final trimmed = line.trimLeft();
  if (trimmed.startsWith('|') && trimmed.indexOf('|', 1) != -1) {
    return true;
  }
  if (trimmed.startsWith('|') &&
      (trimmed.contains('-') || trimmed.contains(':'))) {
    return true;
  }
  if (_kMarkdownTablePipeCellSeparatorPattern.hasMatch(trimmed)) {
    return true;
  }
  return OmnibotTableSyntax._tableDividerPattern.hasMatch(line);
}

int _trailingTableCandidateRemovalStart(
  List<String> lines,
  int candidateStartIndex,
) {
  if (candidateStartIndex == 0 ||
      !_hasParsedMarkdownTableBefore(lines, candidateStartIndex)) {
    return candidateStartIndex;
  }

  var preambleStartIndex = candidateStartIndex;
  var index = candidateStartIndex - 1;
  while (index >= 0) {
    final line = lines[index];
    if (line.trim().isEmpty ||
        omnibotMarkdownLineLooksLikeTableCandidate(line)) {
      break;
    }
    preambleStartIndex = index;
    index -= 1;
  }

  if (preambleStartIndex == candidateStartIndex ||
      !_paragraphAppearsEarlier(
        lines,
        preambleStartIndex,
        candidateStartIndex,
      )) {
    return candidateStartIndex;
  }
  return preambleStartIndex;
}

bool _hasParsedMarkdownTableBefore(List<String> lines, int endIndex) {
  var index = 0;
  while (index < endIndex) {
    if (_isMarkdownLineInsideFencedCode(lines, index)) {
      index += 1;
      continue;
    }
    final table = _tryParseMarkdownTable(lines, index);
    if (table != null) {
      return true;
    }
    index += 1;
  }
  return false;
}

bool _paragraphAppearsEarlier(
  List<String> lines,
  int startIndex,
  int endIndex,
) {
  final paragraph = lines
      .sublist(startIndex, endIndex)
      .map((line) => line.trim())
      .where((line) => line.isNotEmpty)
      .join('\n');
  if (paragraph.isEmpty) {
    return false;
  }

  final earlierText = lines.sublist(0, startIndex).join('\n');
  return earlierText.contains(paragraph);
}

final RegExp _markdownFenceStartPattern = RegExp(r'^[ ]{0,3}(`{3,}|~{3,})');

/// Returns whether [targetIndex] is part of a fenced code block.
///
/// The custom table splitter runs before flutter_markdown. Without this
/// guard, a code sample containing a table-shaped payload is incorrectly
/// promoted to a real Flutter [Table], which changes both its layout and the
/// copied text. Fence lines themselves are included so they are never treated
/// as table rows either.
bool _isMarkdownLineInsideFencedCode(List<String> lines, int targetIndex) {
  String? fenceCharacter;
  var fenceLength = 0;
  for (var index = 0; index <= targetIndex; index += 1) {
    final match = _markdownFenceStartPattern.firstMatch(lines[index]);
    if (match != null) {
      final marker = match.group(1)!;
      final character = marker.substring(0, 1);
      if (fenceCharacter == null) {
        fenceCharacter = character;
        fenceLength = marker.length;
        if (index == targetIndex) return true;
        continue;
      }
      if (character == fenceCharacter && marker.length >= fenceLength) {
        if (index == targetIndex) return true;
        fenceCharacter = null;
        fenceLength = 0;
        continue;
      }
    }
    if (index == targetIndex) {
      return fenceCharacter != null;
    }
  }
  return fenceCharacter != null;
}

MarkdownStyleSheet _resolveMarkdownStyleSheet(
  BuildContext context,
  TextStyle baseStyle,
) {
  final theme = Theme.of(context);
  final scheme = theme.colorScheme;
  final key = Object.hashAll(<Object?>[
    theme.brightness,
    scheme.primary,
    scheme.onSurface,
    scheme.surface,
    scheme.surfaceContainerHighest,
    scheme.onSurfaceVariant,
    scheme.outlineVariant,
    baseStyle.color,
    baseStyle.fontSize,
    baseStyle.height,
    baseStyle.fontWeight,
    baseStyle.fontFamily,
  ]);
  final cached = _styleSheetCache[key];
  if (cached != null) {
    return cached;
  }
  if (_styleSheetCache.length >= _kStyleSheetCacheCapacity) {
    _styleSheetCache.remove(_styleSheetCache.keys.first);
  }
  final styleSheet = buildOmnibotMarkdownStyleSheet(context, baseStyle);
  _styleSheetCache[key] = styleSheet;
  return styleSheet;
}

class OmnibotMarkdownBody extends StatelessWidget {
  static const String _trailingInlineToken = '[[omnibot-trailing-inline]]';

  final String data;
  final TextStyle baseStyle;
  final bool selectable;
  final bool inlineResourcePlainStyle;
  final Widget? trailingInline;
  final OmnibotResourceOpenCallback? onResourceOpen;

  const OmnibotMarkdownBody({
    super.key,
    required this.data,
    required this.baseStyle,
    this.selectable = false,
    this.inlineResourcePlainStyle = false,
    this.trailingInline,
    this.onResourceOpen,
  });

  @override
  Widget build(BuildContext context) {
    final normalizedData = normalizeOmnibotMarkdown(data);
    final segments = _splitMarkdownTableSegments(normalizedData);
    if (segments.length > 1 ||
        (segments.length == 1 && segments.first.tableRows != null)) {
      final styleSheet = _resolveMarkdownStyleSheet(context, baseStyle);
      final children = <Widget>[];
      for (final segment in segments) {
        final tableRows = segment.tableRows;
        if (tableRows != null) {
          children.add(
            OmnibotTableBuilder(
              baseStyle: baseStyle,
              selectable: selectable,
              inlineResourcePlainStyle: inlineResourcePlainStyle,
              onResourceOpen: onResourceOpen,
            )._buildTableFromRows(context, styleSheet, tableRows),
          );
          continue;
        }
        if (segment.text.trim().isEmpty) {
          continue;
        }
        // The whole mixed prose/table column owns one SelectionArea below;
        // nested MarkdownBody selection regions would stop drag selection at
        // each segment boundary.
        children.add(
          _buildMarkdownBody(context, segment.text, selectableOverride: false),
        );
      }
      if (children.isEmpty) {
        return const SizedBox.shrink();
      }
      final child = KeyedSubtree(
        key: const ValueKey('omnibot-markdown-table-root'),
        child: RepaintBoundary(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: children,
          ),
        ),
      );
      return selectable ? SelectionArea(child: child) : child;
    }
    return KeyedSubtree(
      key: const ValueKey('omnibot-markdown-plain-root'),
      child: _buildMarkdownBody(context, normalizedData),
    );
  }

  Widget _buildMarkdownBody(
    BuildContext context,
    String source, {
    bool? selectableOverride,
  }) {
    final styleSheet = _resolveMarkdownStyleSheet(context, baseStyle);
    final resolvedSelectable = selectableOverride ?? selectable;
    final renderedSource = _linkifyBareOmnibotUris(
      _withTrailingInlineToken(source),
    );
    if (!_omnibotMarkdownIsParseable(
      renderedSource,
      hasTrailingInline: trailingInline != null,
    )) {
      final plainText = resolvedSelectable
          ? SelectableText(source, style: baseStyle)
          : Text(source, style: baseStyle);
      return RepaintBoundary(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [plainText, if (trailingInline != null) trailingInline!],
        ),
      );
    }
    return RepaintBoundary(
      child: MarkdownBody(
        data: renderedSource,
        selectable: resolvedSelectable,
        onTapLink: (text, href, title) {
          if (href == null) return;
          _handleMarkdownLinkTap(context, href, onResourceOpen);
        },
        blockSyntaxes: _kBlockSyntaxes,
        inlineSyntaxes: trailingInline != null
            ? _kInlineSyntaxesWithTrailing
            : _kInlineSyntaxesWithoutTrailing,
        builders: buildOmnibotMarkdownBuilders(
          baseStyle: baseStyle,
          selectable: resolvedSelectable,
          inlineResourcePlainStyle: inlineResourcePlainStyle,
          codeTapHandler: _kOmnibotCodeTapHandler,
          trailingInline: trailingInline,
          onResourceOpen: onResourceOpen,
        ),
        sizedImageBuilder: (config) {
          final uri = config.uri;
          if (uri.scheme == 'omnibot') {
            final metadata = OmnibotResourceService.resolveUri(uri.toString());
            if (metadata != null) {
              return OmnibotInlineResourceEmbed(
                metadata: metadata,
                plainStyle: inlineResourcePlainStyle,
                onOpen: onResourceOpen,
              );
            }
          }
          if (uri.scheme == 'file') {
            return Image.file(File.fromUri(uri));
          }
          return Image.network(uri.toString());
        },
        styleSheet: styleSheet,
      ),
    );
  }

  String _withTrailingInlineToken(String source) {
    if (trailingInline == null) {
      return source;
    }
    // 只去除尾部换行（避免 token 被推入新段落），保留原始空格，
    // 不人为添加额外空格，使 markdown 前缀与纯文本尾部无缝衔接。
    final trimmed = source.replaceFirst(RegExp(r'[\r\n]+$'), '');
    if (trimmed.isEmpty) {
      return _trailingInlineToken;
    }
    return '$trimmed$_trailingInlineToken';
  }
}

MarkdownStyleSheet buildOmnibotMarkdownStyleSheet(
  BuildContext context,
  TextStyle baseStyle,
) {
  final baseColor = baseStyle.color;
  TextStyle headingStyle(double fontSize) => baseStyle.copyWith(
    fontSize: fontSize,
    fontWeight: FontWeight.bold,
    color: baseColor,
  );

  return MarkdownStyleSheet.fromTheme(Theme.of(context)).copyWith(
    p: baseStyle.copyWith(height: 1.5),
    h1: headingStyle(24),
    h2: headingStyle(20),
    h3: headingStyle(18),
    h4: headingStyle(16),
    h5: headingStyle(15),
    h6: headingStyle(baseStyle.fontSize ?? 14),
    code: baseStyle.copyWith(
      fontFamily: 'monospace',
      fontSize: (baseStyle.fontSize ?? 14) * 0.92,
      backgroundColor: Colors.transparent,
      color: Theme.of(context).colorScheme.onSurfaceVariant,
    ),
    codeblockDecoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(14),
    ),
    blockquoteDecoration: BoxDecoration(
      color: Colors.grey.withValues(alpha: 0.1),
      border: Border(
        left: BorderSide(
          color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.5),
          width: 4,
        ),
      ),
    ),
    tableColumnWidth: const IntrinsicColumnWidth(),
    tableCellsPadding: const EdgeInsets.all(6),
    tableHead: baseStyle.copyWith(
      color: baseColor,
      fontWeight: FontWeight.w600,
    ),
    tableBody: baseStyle.copyWith(color: baseColor),
  );
}

class _OmnibotMarkdownSegment {
  const _OmnibotMarkdownSegment.text(this.text) : tableRows = null;

  const _OmnibotMarkdownSegment.table(this.tableRows) : text = '';

  final String text;
  final List<_OmnibotTableRowSpec>? tableRows;
}

List<_OmnibotMarkdownSegment> _splitMarkdownTableSegments(String source) {
  final lines = source.split('\n');
  final segments = <_OmnibotMarkdownSegment>[];
  final textBuffer = StringBuffer();
  var index = 0;

  void flushText() {
    if (textBuffer.isEmpty) {
      return;
    }
    segments.add(_OmnibotMarkdownSegment.text(textBuffer.toString()));
    textBuffer.clear();
  }

  while (index < lines.length) {
    final table = _isMarkdownLineInsideFencedCode(lines, index)
        ? null
        : _tryParseMarkdownTable(lines, index);
    if (table == null) {
      if (textBuffer.isNotEmpty) {
        textBuffer.writeln();
      }
      textBuffer.write(lines[index]);
      index += 1;
      continue;
    }
    flushText();
    segments.add(_OmnibotMarkdownSegment.table(table.rows));
    index = table.nextLineIndex;
  }

  flushText();
  if (segments.isEmpty) {
    return const <_OmnibotMarkdownSegment>[_OmnibotMarkdownSegment.text('')];
  }
  return segments;
}

_ParsedMarkdownTable? _tryParseMarkdownTable(List<String> lines, int index) {
  if (index + 1 >= lines.length) {
    return null;
  }
  final divider = lines[index + 1];
  if (!OmnibotTableSyntax._tableDividerPattern.hasMatch(divider)) {
    return null;
  }

  final alignments = _parseMarkdownTableAlignments(divider);
  if (alignments.isEmpty) {
    return null;
  }
  final headerCells = _parseMarkdownTableCells(lines[index], alignments);
  if (headerCells.length != alignments.length) {
    return null;
  }

  final rows = <_OmnibotTableRowSpec>[
    _OmnibotTableRowSpec(isHeader: true, cells: headerCells),
  ];
  var nextIndex = index + 2;
  while (nextIndex < lines.length) {
    final line = lines[nextIndex];
    if (!_looksLikeMarkdownTableRow(line)) {
      break;
    }
    final cells = _parseMarkdownTableCells(line, alignments);
    while (cells.length < alignments.length) {
      cells.add(const _OmnibotTableCellSpec(source: ''));
    }
    while (cells.length > alignments.length) {
      cells.removeLast();
    }
    rows.add(_OmnibotTableRowSpec(isHeader: false, cells: cells));
    nextIndex += 1;
  }

  return _ParsedMarkdownTable(rows: rows, nextLineIndex: nextIndex);
}

bool _looksLikeMarkdownTableRow(String line) {
  final trimmed = line.trim();
  return trimmed.isNotEmpty && trimmed.contains('|');
}

List<String?> _parseMarkdownTableAlignments(String line) {
  final columns = <String?>[];
  var started = false;
  var hitDash = false;
  String? alignment;

  for (var index = 0; index < line.length; index++) {
    final char = line.codeUnitAt(index);
    if (char == 32 || char == 9 || (!started && char == 124)) {
      continue;
    }
    started = true;

    if (char == 58) {
      if (hitDash) {
        alignment = alignment == 'left' ? 'center' : 'right';
      } else {
        alignment = 'left';
      }
    }

    if (char == 124) {
      columns.add(alignment);
      hitDash = false;
      alignment = null;
    } else {
      hitDash = true;
    }
  }

  if (hitDash) {
    columns.add(alignment);
  }

  return columns;
}

List<_OmnibotTableCellSpec> _parseMarkdownTableCells(
  String line,
  List<String?> alignments,
) {
  final cells = <String>[];
  var index = _walkPastMarkdownTableOpeningPipe(line);
  final cellBuffer = StringBuffer();

  while (true) {
    if (index >= line.length) {
      cells.add(cellBuffer.toString().trimRight());
      break;
    }
    final char = line.codeUnitAt(index);
    if (char == 92) {
      if (index == line.length - 1) {
        cellBuffer.writeCharCode(char);
        cells.add(cellBuffer.toString().trimRight());
        break;
      }
      final escaped = line.codeUnitAt(index + 1);
      if (escaped == 124) {
        cellBuffer.writeCharCode(escaped);
      } else {
        cellBuffer.writeCharCode(char);
        cellBuffer.writeCharCode(escaped);
      }
      index += 2;
    } else if (char == 124) {
      cells.add(cellBuffer.toString().trimRight());
      cellBuffer.clear();
      index += 1;
      index = _walkPastMarkdownTableWhitespace(line, index);
      if (index >= line.length) {
        break;
      }
    } else {
      cellBuffer.writeCharCode(char);
      index += 1;
    }
  }

  final rowChildren = <_OmnibotTableCellSpec>[];
  for (var cellIndex = 0; cellIndex < cells.length; cellIndex++) {
    rowChildren.add(
      _OmnibotTableCellSpec(
        source: cells[cellIndex],
        align: cellIndex < alignments.length ? alignments[cellIndex] : null,
      ),
    );
  }
  return rowChildren;
}

int _walkPastMarkdownTableWhitespace(String line, int index) {
  while (index < line.length) {
    final char = line.codeUnitAt(index);
    if (char != 32 && char != 9) {
      break;
    }
    index += 1;
  }
  return index;
}

int _walkPastMarkdownTableOpeningPipe(String line) {
  var index = 0;
  while (index < line.length) {
    final char = line.codeUnitAt(index);
    if (char == 124) {
      index += 1;
      index = _walkPastMarkdownTableWhitespace(line, index);
    }
    if (char != 32 && char != 9) {
      break;
    }
    index += 1;
  }
  return index;
}

class _ParsedMarkdownTable {
  const _ParsedMarkdownTable({required this.rows, required this.nextLineIndex});

  final List<_OmnibotTableRowSpec> rows;
  final int nextLineIndex;
}

Map<String, MarkdownElementBuilder> buildOmnibotMarkdownBuilders({
  required TextStyle baseStyle,
  required bool selectable,
  required bool inlineResourcePlainStyle,
  required OmnibotCodeTapHandler codeTapHandler,
  Widget? trailingInline,
  OmnibotResourceOpenCallback? onResourceOpen,
}) {
  return <String, MarkdownElementBuilder>{
    'code': OmnibotInlineCodeBuilder(onCopy: codeTapHandler.copy),
    'pre': OmnibotCodeBlockBuilder(onCopy: codeTapHandler.copy),
    'math-inline': OmnibotInlineMathBuilder(baseStyle: baseStyle),
    'math-block': OmnibotBlockMathBuilder(baseStyle: baseStyle),
    'omnibot-table': OmnibotTableBuilder(
      baseStyle: baseStyle,
      selectable: selectable,
      inlineResourcePlainStyle: inlineResourcePlainStyle,
      onResourceOpen: onResourceOpen,
    ),
    'omnibot-link': OmnibotInlineLinkBuilder(
      inlineResourcePlainStyle: inlineResourcePlainStyle,
      onResourceOpen: onResourceOpen,
    ),
    if (trailingInline != null)
      'omnibot-trailing-inline': OmnibotTrailingInlineBuilder(
        child: trailingInline,
      ),
  };
}

class OmnibotTableSyntax extends md.BlockSyntax {
  static const String tag = 'omnibot-table';
  static const String payloadAttribute = 'data-omnibot-table-payload';
  static final RegExp _tableDividerPattern = RegExp(
    r'^[ ]{0,3}\|?([ \t]*:?\-+:?[ \t]*\|[ \t]*)+([ \t]|[ \t]*:?\-+:?[ \t]*)?$',
  );

  const OmnibotTableSyntax();

  @override
  bool canEndBlock(md.BlockParser parser) => true;

  @override
  RegExp get pattern => RegExp('');

  @override
  bool canParse(md.BlockParser parser) {
    if (!parser.matchesNext(_tableDividerPattern)) {
      return false;
    }

    // `parse` consumes the current header row and the following divider.  A
    // provider can stream the divider before the header has become a valid
    // table, however.  Returning true for that partial shape and then
    // returning null from `parse` leaves BlockParser at the same line and
    // triggers its "parseLines is not advancing" assertion.
    final divider = parser.next;
    if (divider == null) {
      return false;
    }
    final alignments = _parseAlignments(divider.content);
    if (alignments.isEmpty) {
      return false;
    }
    final headerCells = _parseMarkdownTableCells(
      parser.current.content,
      alignments,
    );
    return headerCells.length == alignments.length;
  }

  @override
  md.Node? parse(md.BlockParser parser) {
    final alignments = _parseAlignments(parser.next!.content);
    final columnCount = alignments.length;
    final headRow = _parseRowCells(parser, alignments);
    if (headRow.length != columnCount) {
      // `canParse` performs the same shape check, so this is defensive only.
      // Consume the current line and render it as ordinary paragraph text if
      // a future markdown package changes parser state between the two calls.
      final header = headRow.map((cell) => cell.source).join(' | ');
      return md.Element.text('p', header);
    }

    parser.advance();

    final rows = <_OmnibotTableRowSpec>[
      _OmnibotTableRowSpec(isHeader: true, cells: headRow),
    ];
    while (!parser.isDone && !md.BlockSyntax.isAtBlockEnd(parser)) {
      final row = _parseRowCells(parser, alignments);
      while (row.length < columnCount) {
        row.add(const _OmnibotTableCellSpec(source: ''));
      }
      while (row.length > columnCount) {
        row.removeLast();
      }
      rows.add(_OmnibotTableRowSpec(isHeader: false, cells: row));
    }

    return md.Element.empty(tag)
      ..attributes[payloadAttribute] = jsonEncode(
        rows.map((row) => row.toJson()).toList(growable: false),
      );
  }

  List<String?> _parseAlignments(String line) {
    final columns = <String?>[];
    var started = false;
    var hitDash = false;
    String? alignment;

    for (var index = 0; index < line.length; index++) {
      final char = line.codeUnitAt(index);
      if (char == 32 || char == 9 || (!started && char == 124)) {
        continue;
      }
      started = true;

      if (char == 58) {
        if (hitDash) {
          alignment = alignment == 'left' ? 'center' : 'right';
        } else {
          alignment = 'left';
        }
      }

      if (char == 124) {
        columns.add(alignment);
        hitDash = false;
        alignment = null;
      } else {
        hitDash = true;
      }
    }

    if (hitDash) {
      columns.add(alignment);
    }

    return columns;
  }

  List<_OmnibotTableCellSpec> _parseRowCells(
    md.BlockParser parser,
    List<String?> alignments,
  ) {
    final line = parser.current;
    final cells = <String>[];
    var index = _walkPastOpeningPipe(line.content);
    final cellBuffer = StringBuffer();

    while (true) {
      if (index >= line.content.length) {
        cells.add(cellBuffer.toString().trimRight());
        cellBuffer.clear();
        break;
      }
      final char = line.content.codeUnitAt(index);
      if (char == 92) {
        if (index == line.content.length - 1) {
          cellBuffer.writeCharCode(char);
          cells.add(cellBuffer.toString().trimRight());
          cellBuffer.clear();
          break;
        }
        final escaped = line.content.codeUnitAt(index + 1);
        if (escaped == 124) {
          cellBuffer.writeCharCode(escaped);
        } else {
          cellBuffer.writeCharCode(char);
          cellBuffer.writeCharCode(escaped);
        }
        index += 2;
      } else if (char == 124) {
        cells.add(cellBuffer.toString().trimRight());
        cellBuffer.clear();
        index++;
        index = _walkPastWhitespace(line.content, index);
        if (index >= line.content.length) {
          break;
        }
      } else {
        cellBuffer.writeCharCode(char);
        index++;
      }
    }
    parser.advance();

    final rowChildren = <_OmnibotTableCellSpec>[];
    for (var index = 0; index < cells.length; index++) {
      rowChildren.add(
        _OmnibotTableCellSpec(
          source: cells[index],
          align: index < alignments.length ? alignments[index] : null,
        ),
      );
    }

    return rowChildren;
  }

  int _walkPastWhitespace(String line, int index) {
    while (index < line.length) {
      final char = line.codeUnitAt(index);
      if (char != 32 && char != 9) {
        break;
      }
      index++;
    }
    return index;
  }

  int _walkPastOpeningPipe(String line) {
    var index = 0;
    while (index < line.length) {
      final char = line.codeUnitAt(index);
      if (char == 124) {
        index++;
        index = _walkPastWhitespace(line, index);
      }
      if (char != 32 && char != 9) {
        break;
      }
      index++;
    }
    return index;
  }
}

class OmnibotInlineLinkSyntax extends md.InlineSyntax {
  OmnibotInlineLinkSyntax() : super(_pattern);

  static const String _pattern = r'(?<!!)\[([^\]]*?)\]\((omnibot://[^)\s]+)\)';

  @override
  bool onMatch(md.InlineParser parser, Match match) {
    final label = match[1] ?? '';
    final href = match[2] ?? '';
    final element = md.Element.text(
      'omnibot-link',
      label.isEmpty ? href : label,
    )..attributes['href'] = href;
    parser.addNode(element);
    return true;
  }
}

class OmnibotMathBlockSyntax extends md.BlockSyntax {
  static const String expressionAttribute = 'data-expression';

  const OmnibotMathBlockSyntax();

  @override
  RegExp get pattern => RegExp(r'^\s*\$\$');

  @override
  bool canParse(md.BlockParser parser) {
    return pattern.hasMatch(parser.current.content);
  }

  @override
  md.Node parse(md.BlockParser parser) {
    final firstLineTrimmed = parser.current.content.trim();
    if (firstLineTrimmed.startsWith(r'$$') &&
        firstLineTrimmed.endsWith(r'$$') &&
        firstLineTrimmed.length > 4) {
      final inlineExpression = firstLineTrimmed
          .substring(2, firstLineTrimmed.length - 2)
          .trim();
      parser.advance();
      return _buildMathElement(inlineExpression);
    }

    final expressionBuffer = StringBuffer();
    final firstRemainder = firstLineTrimmed.substring(2).trimRight();
    if (firstRemainder.isNotEmpty) {
      expressionBuffer.write(firstRemainder);
    }
    parser.advance();

    while (!parser.isDone) {
      final line = parser.current.content;
      final lineTrimmedRight = line.trimRight();
      final normalized = lineTrimmedRight.trim();

      if (normalized == r'$$') {
        parser.advance();
        break;
      }

      if (normalized.endsWith(r'$$')) {
        final closeIndex = lineTrimmedRight.lastIndexOf(r'$$');
        final contentBeforeClose = lineTrimmedRight.substring(0, closeIndex);
        if (expressionBuffer.isNotEmpty) {
          expressionBuffer.writeln();
        }
        expressionBuffer.write(contentBeforeClose.trimRight());
        parser.advance();
        break;
      }

      if (expressionBuffer.isNotEmpty) {
        expressionBuffer.writeln();
      }
      expressionBuffer.write(lineTrimmedRight);
      parser.advance();
    }

    return _buildMathElement(expressionBuffer.toString().trim());
  }

  md.Element _buildMathElement(String expression) {
    final element = md.Element.empty('math-block');
    element.attributes[expressionAttribute] = expression;
    return element;
  }
}

class OmnibotTableBuilder extends MarkdownElementBuilder {
  OmnibotTableBuilder({
    required this.baseStyle,
    required this.selectable,
    required this.inlineResourcePlainStyle,
    this.onResourceOpen,
  });

  final TextStyle baseStyle;
  final bool selectable;
  final bool inlineResourcePlainStyle;
  final OmnibotResourceOpenCallback? onResourceOpen;

  @override
  bool isBlockElement() => true;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    final styleSheet = _resolveMarkdownStyleSheet(context, baseStyle);
    final rowSpecs = _buildTableRowSpecs(element);
    return _buildTableFromRows(context, styleSheet, rowSpecs);
  }

  Widget _buildTableFromRows(
    BuildContext context,
    MarkdownStyleSheet styleSheet,
    List<_OmnibotTableRowSpec> rowSpecs,
  ) {
    final tableRows = _buildFlutterTableRows(styleSheet, rowSpecs);
    if (tableRows.isEmpty) {
      return const SizedBox.shrink();
    }
    return Padding(
      padding: styleSheet.tablePadding ?? EdgeInsets.zero,
      child: _OmnibotTableScrollable(
        thumbVisibility: styleSheet.tableScrollbarThumbVisibility ?? false,
        child: Table(
          border: styleSheet.tableBorder,
          defaultColumnWidth:
              styleSheet.tableColumnWidth ?? const IntrinsicColumnWidth(),
          defaultVerticalAlignment: styleSheet.tableVerticalAlignment,
          children: tableRows,
        ),
      ),
    );
  }

  List<_OmnibotTableRowSpec> _buildTableRowSpecs(md.Element tableElement) {
    final payload =
        tableElement.attributes[OmnibotTableSyntax.payloadAttribute];
    if (payload == null || payload.isEmpty) {
      return const <_OmnibotTableRowSpec>[];
    }
    final decoded = jsonDecode(payload);
    if (decoded is! List) {
      return const <_OmnibotTableRowSpec>[];
    }
    return decoded
        .whereType<Map<String, dynamic>>()
        .map(_OmnibotTableRowSpec.fromJson)
        .toList(growable: false);
  }

  List<TableRow> _buildFlutterTableRows(
    MarkdownStyleSheet styleSheet,
    List<_OmnibotTableRowSpec> rowSpecs,
  ) {
    final rows = <TableRow>[];
    for (final rowSpec in rowSpecs) {
      rows.add(TableRow(children: _buildRowCells(styleSheet, rowSpec)));
    }
    return rows;
  }

  List<Widget> _buildRowCells(
    MarkdownStyleSheet styleSheet,
    _OmnibotTableRowSpec rowSpec,
  ) {
    if (rowSpec.cells.isEmpty) {
      return const <Widget>[SizedBox.shrink()];
    }
    return rowSpec.cells
        .map((cell) {
          final isHeader = rowSpec.isHeader;
          final cellStyle =
              (isHeader ? styleSheet.tableHead : styleSheet.tableBody) ??
              baseStyle;
          final textAlign = _resolveCellTextAlign(
            cell.align,
            fallback: isHeader
                ? (styleSheet.tableHeadAlign ?? TextAlign.center)
                : TextAlign.left,
          );
          final alignment = _alignmentForTextAlign(textAlign);
          return DecoratedBox(
            decoration:
                styleSheet.tableCellsDecoration ?? const BoxDecoration(),
            child: Padding(
              padding: styleSheet.tableCellsPadding ?? EdgeInsets.zero,
              child: Align(
                alignment: alignment,
                child: _OmnibotMarkdownTableCell(
                  data: cell.source,
                  baseStyle: cellStyle,
                  textAlign: textAlign,
                ),
              ),
            ),
          );
        })
        .toList(growable: false);
  }

  TextAlign _resolveCellTextAlign(
    String? align, {
    required TextAlign fallback,
  }) {
    return switch (align) {
      'left' => TextAlign.left,
      'center' => TextAlign.center,
      'right' => TextAlign.right,
      _ => fallback,
    };
  }

  Alignment _alignmentForTextAlign(TextAlign textAlign) {
    return switch (textAlign) {
      TextAlign.center => Alignment.center,
      TextAlign.right || TextAlign.end => Alignment.centerRight,
      _ => Alignment.centerLeft,
    };
  }
}

class _OmnibotTableRowSpec {
  const _OmnibotTableRowSpec({required this.isHeader, required this.cells});

  factory _OmnibotTableRowSpec.fromJson(Map<String, dynamic> json) {
    final rawCells = json['cells'];
    final cells = rawCells is List
        ? rawCells
              .whereType<Map<String, dynamic>>()
              .map(_OmnibotTableCellSpec.fromJson)
              .toList(growable: false)
        : const <_OmnibotTableCellSpec>[];
    return _OmnibotTableRowSpec(
      isHeader: json['isHeader'] == true,
      cells: cells,
    );
  }

  final bool isHeader;
  final List<_OmnibotTableCellSpec> cells;

  Map<String, Object?> toJson() {
    return <String, Object?>{
      'isHeader': isHeader,
      'cells': cells.map((cell) => cell.toJson()).toList(growable: false),
    };
  }
}

class _OmnibotTableCellSpec {
  const _OmnibotTableCellSpec({required this.source, this.align});

  factory _OmnibotTableCellSpec.fromJson(Map<String, dynamic> json) {
    return _OmnibotTableCellSpec(
      source: json['source'] as String? ?? '',
      align: json['align'] as String?,
    );
  }

  final String source;
  final String? align;

  Map<String, Object?> toJson() {
    return <String, Object?>{'source': source, 'align': align};
  }
}

class _OmnibotTableScrollable extends StatefulWidget {
  const _OmnibotTableScrollable({
    required this.thumbVisibility,
    required this.child,
  });

  final bool thumbVisibility;
  final Widget child;

  @override
  State<_OmnibotTableScrollable> createState() =>
      _OmnibotTableScrollableState();
}

class _OmnibotTableScrollableState extends State<_OmnibotTableScrollable> {
  late final ScrollController _scrollController;

  @override
  void initState() {
    super.initState();
    _scrollController = ScrollController();
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ChatDrawerGestureGuard(
      child: Scrollbar(
        controller: _scrollController,
        thumbVisibility: widget.thumbVisibility,
        child: SingleChildScrollView(
          controller: _scrollController,
          scrollDirection: Axis.horizontal,
          child: widget.child,
        ),
      ),
    );
  }
}

class _OmnibotMarkdownTableCell extends StatelessWidget {
  const _OmnibotMarkdownTableCell({
    required this.data,
    required this.baseStyle,
    required this.textAlign,
  });

  final String data;
  final TextStyle baseStyle;
  final TextAlign textAlign;

  @override
  Widget build(BuildContext context) {
    if (data.isEmpty) {
      return Text(data, style: baseStyle, textAlign: textAlign);
    }
    final spans = _omnibotMarkdownInlineSpans(context, data, baseStyle);
    return Text.rich(
      TextSpan(style: baseStyle, children: spans),
      textAlign: textAlign,
    );
  }
}

List<InlineSpan> _omnibotMarkdownInlineSpans(
  BuildContext context,
  String source,
  TextStyle baseStyle,
) {
  final document = md.Document(
    encodeHtml: false,
    inlineSyntaxes: _kInlineSyntaxesWithoutTrailing,
  );
  final nodes = document.parseInline(source);
  return _omnibotMarkdownNodesToSpans(context, nodes, baseStyle);
}

List<InlineSpan> _omnibotMarkdownNodesToSpans(
  BuildContext context,
  List<md.Node> nodes,
  TextStyle style,
) {
  return nodes
      .map((node) => _omnibotMarkdownNodeToSpan(context, node, style))
      .toList(growable: false);
}

InlineSpan _omnibotMarkdownNodeToSpan(
  BuildContext context,
  md.Node node,
  TextStyle style,
) {
  if (node is md.Text) {
    return TextSpan(text: node.textContent, style: style);
  }
  if (node is md.Element) {
    if (node.tag == 'math-inline') {
      return _buildInlineMathSpan(context, node.textContent.trim(), style);
    }
    var childStyle = style;
    switch (node.tag) {
      case 'strong':
        childStyle = style.copyWith(fontWeight: FontWeight.bold);
        break;
      case 'em':
        childStyle = style.copyWith(fontStyle: FontStyle.italic);
        break;
      case 'del':
        childStyle = style.copyWith(decoration: TextDecoration.lineThrough);
        break;
      case 'code':
        childStyle = style.copyWith(
          fontFamily: 'monospace',
          fontFamilyFallback: const <String>['Courier'],
        );
        break;
    }
    final children = node.children;
    if (children == null || children.isEmpty) {
      return TextSpan(text: node.textContent, style: childStyle);
    }
    return TextSpan(
      style: childStyle,
      children: _omnibotMarkdownNodesToSpans(context, children, childStyle),
    );
  }
  return const TextSpan();
}

WidgetSpan _buildInlineMathSpan(
  BuildContext context,
  String expression,
  TextStyle style,
) {
  if (expression.isEmpty) {
    return const WidgetSpan(child: SizedBox.shrink());
  }
  final mathStyle = style.copyWith(
    color: style.color ?? Theme.of(context).colorScheme.onSurface,
    height: 1.35,
  );
  final width = _estimateInlineMathWidth(context, expression, mathStyle);
  return WidgetSpan(
    alignment: PlaceholderAlignment.middle,
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 1),
      child: SizedBox(
        width: width,
        child: FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.centerLeft,
          child: Math.tex(
            expression,
            mathStyle: MathStyle.text,
            textStyle: mathStyle,
            onErrorFallback: (error) =>
                Text('\$$expression\$', style: mathStyle),
          ),
        ),
      ),
    ),
  );
}

double _estimateInlineMathWidth(
  BuildContext context,
  String expression,
  TextStyle style,
) {
  final fontSize = style.fontSize ?? 14;
  final screenWidth = MediaQuery.maybeOf(context)?.size.width ?? 360;
  final maxWidth = screenWidth * 0.52;
  final estimated = expression.runes.length * fontSize * 0.78 + fontSize;
  return estimated.clamp(fontSize * 2.5, maxWidth).toDouble();
}

class OmnibotInlineMathSyntax extends md.InlineSyntax {
  OmnibotInlineMathSyntax() : super(_pattern);

  static const String _pattern = r'(?<!\\)(?<!\$)\$([^\$\n]+?)\$(?!\$)';

  @override
  bool onMatch(md.InlineParser parser, Match match) {
    final expression = match[1] ?? '';
    parser.addNode(md.Element.text('math-inline', expression));
    return true;
  }
}

typedef OmnibotCodeCopyCallback = Future<void> Function(String code);

class OmnibotCodeTapHandler {
  const OmnibotCodeTapHandler();

  Future<void> copy(String code) async {
    if (code.trim().isEmpty) return;
    try {
      await Clipboard.setData(ClipboardData(text: code));
      showToast(
        LegacyTextLocalizer.isEnglish ? 'Code copied' : '代码已复制',
        type: ToastType.success,
      );
    } catch (_) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Copy failed, please try again'
            : '复制失败，请重试',
        type: ToastType.error,
      );
    }
  }
}

class OmnibotInlineCodeBuilder extends MarkdownElementBuilder {
  OmnibotInlineCodeBuilder({required this.onCopy});

  final OmnibotCodeCopyCallback onCopy;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    final code = element.textContent;
    if (code.isEmpty) {
      return const SizedBox.shrink();
    }
    final theme = Theme.of(context);
    final borderRadius = BorderRadius.circular(8);
    final codeStyle = (preferredStyle ?? parentStyle ?? const TextStyle())
        .copyWith(
          fontFamily: 'monospace',
          backgroundColor: Colors.transparent,
          color: theme.colorScheme.onSurfaceVariant,
          fontSize:
              ((preferredStyle?.fontSize ?? parentStyle?.fontSize ?? 14) * 0.92)
                  .toDouble(),
          height: 1.2,
        );

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 1, vertical: 1),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: borderRadius,
          onTap: () => onCopy(code),
          child: Ink(
            padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
            decoration: BoxDecoration(
              color: theme.colorScheme.surfaceContainerHighest.withValues(
                alpha: 0.72,
              ),
              borderRadius: borderRadius,
              border: Border.all(
                color: theme.colorScheme.outlineVariant.withValues(alpha: 0.45),
                width: 0.8,
              ),
            ),
            child: Text(code, style: codeStyle),
          ),
        ),
      ),
    );
  }
}

class OmnibotCodeBlockBuilder extends MarkdownElementBuilder {
  OmnibotCodeBlockBuilder({required this.onCopy});

  final OmnibotCodeCopyCallback onCopy;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    final code = _normalizedCodeText(element.textContent);
    final canCopy = code.trim().isNotEmpty;
    final theme = Theme.of(context);
    final borderRadius = BorderRadius.circular(14);
    final codeStyle = (preferredStyle ?? parentStyle ?? const TextStyle())
        .copyWith(
          fontFamily: 'monospace',
          backgroundColor: Colors.transparent,
          color: theme.colorScheme.onSurfaceVariant,
          fontSize:
              ((preferredStyle?.fontSize ?? parentStyle?.fontSize ?? 14) * 0.92)
                  .toDouble(),
          height: 1.45,
        );

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: borderRadius,
          onTap: canCopy ? () => onCopy(code) : null,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
            child: ChatDrawerGestureGuard(
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Text(code, style: codeStyle, softWrap: false),
              ),
            ),
          ),
        ),
      ),
    );
  }

  String _normalizedCodeText(String value) {
    if (value.endsWith('\n') && value.length > 1) {
      return value.substring(0, value.length - 1);
    }
    return value;
  }
}

class OmnibotInlineMathBuilder extends MarkdownElementBuilder {
  OmnibotInlineMathBuilder({required this.baseStyle});

  final TextStyle baseStyle;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    final expression = element.textContent.trim();
    if (expression.isEmpty) {
      return const SizedBox.shrink();
    }
    final style = (preferredStyle ?? parentStyle ?? baseStyle).copyWith(
      color: Theme.of(context).colorScheme.onSurface,
      height: 1.4,
    );
    return Text.rich(
      WidgetSpan(
        alignment: PlaceholderAlignment.middle,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 1),
          child: LayoutBuilder(
            builder: (context, constraints) {
              final maxWidth = _resolveMathMaxWidth(
                context,
                constraints,
                fallbackScreenRatio: 0.72,
              );
              return ConstrainedBox(
                constraints: BoxConstraints(maxWidth: maxWidth),
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerLeft,
                  child: Math.tex(
                    expression,
                    mathStyle: MathStyle.text,
                    textStyle: style,
                    onErrorFallback: (error) =>
                        Text('\$$expression\$', style: style),
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}

class OmnibotBlockMathBuilder extends MarkdownElementBuilder {
  OmnibotBlockMathBuilder({required this.baseStyle});

  final TextStyle baseStyle;

  @override
  bool isBlockElement() => true;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    final expression =
        (element.attributes[OmnibotMathBlockSyntax.expressionAttribute] ??
                element.textContent)
            .trim();
    if (expression.isEmpty) {
      return const SizedBox.shrink();
    }
    final style = (preferredStyle ?? parentStyle ?? baseStyle).copyWith(
      color: Theme.of(context).colorScheme.onSurface,
      height: 1.4,
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: ChatDrawerGestureGuard(
        child: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Math.tex(
            expression,
            mathStyle: MathStyle.display,
            textStyle: style,
            onErrorFallback: (error) =>
                Text('\$\$$expression\$\$', style: style),
          ),
        ),
      ),
    );
  }
}

class OmnibotInlineLinkBuilder extends MarkdownElementBuilder {
  OmnibotInlineLinkBuilder({
    this.inlineResourcePlainStyle = false,
    this.onResourceOpen,
  });

  final bool inlineResourcePlainStyle;
  final OmnibotResourceOpenCallback? onResourceOpen;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    final href = element.attributes['href'];
    final metadata = href == null
        ? null
        : OmnibotResourceService.resolveUri(href);
    if (metadata == null) {
      return Text.rich(
        WidgetSpan(
          alignment: PlaceholderAlignment.middle,
          child: InkWell(
            onTap: href == null
                ? null
                : () => _handleMarkdownLinkTap(context, href, onResourceOpen),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Text(
                element.textContent,
                style: preferredStyle?.copyWith(
                  color: Theme.of(context).colorScheme.primary,
                ),
              ),
            ),
          ),
        ),
      );
    }
    return Text.rich(
      WidgetSpan(
        alignment: PlaceholderAlignment.middle,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: OmnibotInlineResourceEmbed(
            metadata: metadata,
            plainStyle: inlineResourcePlainStyle,
            onOpen: onResourceOpen,
          ),
        ),
      ),
    );
  }
}

Future<void> _handleMarkdownLinkTap(
  BuildContext context,
  String href,
  OmnibotResourceOpenCallback? onResourceOpen,
) async {
  if (href.startsWith('omnibot://') && onResourceOpen != null) {
    await OmnibotResourceService.ensureWorkspacePathsLoaded();
    if (!context.mounted) return;
    final metadata = OmnibotResourceService.resolveUri(href);
    if (metadata != null) {
      await onResourceOpen(context, metadata);
      return;
    }
  }
  await OmnibotResourceService.handleLinkTap(href);
}

class OmnibotTrailingInlineSyntax extends md.InlineSyntax {
  OmnibotTrailingInlineSyntax() : super(_pattern);

  static const String _pattern = r'\[\[omnibot-trailing-inline\]\]';

  @override
  bool onMatch(md.InlineParser parser, Match match) {
    parser.addNode(md.Element.empty('omnibot-trailing-inline'));
    return true;
  }
}

class OmnibotTrailingInlineBuilder extends MarkdownElementBuilder {
  OmnibotTrailingInlineBuilder({required this.child});

  final Widget child;

  @override
  Widget visitElementAfterWithContext(
    BuildContext context,
    md.Element element,
    TextStyle? preferredStyle,
    TextStyle? parentStyle,
  ) {
    return Text.rich(
      WidgetSpan(alignment: PlaceholderAlignment.middle, child: child),
    );
  }
}

double _resolveMathMaxWidth(
  BuildContext context,
  BoxConstraints constraints, {
  double fallbackScreenRatio = 1.0,
}) {
  if (constraints.maxWidth.isFinite && constraints.maxWidth > 0) {
    return constraints.maxWidth;
  }
  final screenWidth = MediaQuery.maybeOf(context)?.size.width ?? 360;
  return screenWidth * fallbackScreenRatio;
}

String _linkifyBareOmnibotUris(String input) {
  final buffer = StringBuffer();
  final lines = input.split('\n');
  for (var i = 0; i < lines.length; i++) {
    final line = lines[i];
    final trimmed = line.trim();
    if (trimmed.startsWith('omnibot://') &&
        !trimmed.contains(' ') &&
        !trimmed.contains('[') &&
        !trimmed.contains(']')) {
      final parsed = Uri.tryParse(trimmed);
      final label = parsed?.pathSegments.isNotEmpty == true
          ? parsed!.pathSegments.last
          : (LegacyTextLocalizer.isEnglish ? 'Resource' : '资源');
      buffer.write('[$label]($trimmed)');
    } else {
      buffer.write(line);
    }
    if (i != lines.length - 1) {
      buffer.write('\n');
    }
  }
  return buffer.toString();
}
