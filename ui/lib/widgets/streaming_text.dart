import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';
import 'package:ui/widgets/omnibot_resource_widgets.dart';

/// 思考中的加载文案（原始中文值，用于数据比较）
const String kThinkingText = '小万正在思考...';

/// 思考中的加载文案（本地化显示用）
String get kThinkingTextLocalized =>
    LegacyTextLocalizer.localize(kThinkingText);

/// 总结中的加载文案（本地化显示用）
String get kSummarizingText => LegacyTextLocalizer.localize('总结中');

/// 总结完成的提示文案（本地化显示用）
String get kSummaryCompleteText => LegacyTextLocalizer.localize('总结如下');

final RegExp _structuredMarkdownInlineLinkPattern = RegExp(
  r'!?\[[^\]]+\]\([^\)]+\)',
);
final RegExp _structuredMarkdownReferenceLinkPattern = RegExp(
  r'\[[^\]]+\]\[[^\]]*\]',
);
final RegExp _structuredMarkdownAsteriskEmphasisPattern = RegExp(
  r'(^|[\s(\[])\*[^*\n]+\*(?=$|[\s).,!?:;\]])',
);
final RegExp _structuredMarkdownUnderscoreEmphasisPattern = RegExp(
  r'(^|[\s(\[])_[^_\n]+_(?=$|[\s).,!?:;\]])',
);
final RegExp _structuredMarkdownAutolinkOrHtmlPattern = RegExp(
  r'<(?:https?://|[A-Za-z][A-Za-z0-9-]*\b)[^>]*>',
);
final RegExp _structuredMarkdownMathPattern = RegExp(r'\$[^\n$]+\$');
final RegExp _structuredMarkdownBlockPattern = RegExp(
  r'^[ \t]{0,3}(?:#{1,6}[ \t]+|>[ \t]?|(?:[-+*]|\d+[.)])[ \t]+)',
  multiLine: true,
);
final RegExp _structuredMarkdownRulePattern = RegExp(
  r'^[ \t]{0,3}(?:-{3,}|\*{3,}|_{3,}|={3,})[ \t]*$',
  multiLine: true,
);
final RegExp _structuredMarkdownIndentedCodePattern = RegExp(
  r'^(?: {4}|\t)\S',
  multiLine: true,
);

int _clampOmnibotTextToCodePointBoundary(String text, int requestedLength) {
  var safeLength = requestedLength.clamp(0, text.length);
  if (safeLength <= 0 || safeLength >= text.length) {
    return safeLength;
  }
  final currentUnit = text.codeUnitAt(safeLength);
  final previousUnit = text.codeUnitAt(safeLength - 1);
  final isCurrentLowSurrogate = currentUnit >= 0xDC00 && currentUnit <= 0xDFFF;
  final isPreviousHighSurrogate =
      previousUnit >= 0xD800 && previousUnit <= 0xDBFF;
  if (isCurrentLowSurrogate && isPreviousHighSurrogate) {
    safeLength -= 1;
  }
  return safeLength;
}

/// 流式文本显示组件，支持平滑逐字透出效果
///
/// 用于显示流式推送的文本内容
///
/// **性能策略**：
/// - 启用 Markdown 但内容仍是普通正文时，持续使用同一个
///   [OmnibotPacedRevealText]，不随批次边界切换布局。
/// - 只含加粗标记的正文使用同一个 RichText 逐字透出，不嵌套整段
///   WidgetSpan。
/// - 标题、列表、代码等复杂 Markdown 按最新快照整体渲染，不混合两套
///   段落布局；表格仍使用专用预览路径。
/// - 未启用 Markdown 时走 [OmnibotPacedRevealText] 做逐字透出（轻量）。
class StreamingText extends StatefulWidget {
  /// 完整的文本内容（会随着流式推送逐渐增加）
  final String fullText;

  /// 文本样式
  final TextStyle style;

  /// 是否启用Markdown渲染，默认为false
  final bool enableMarkdown;

  /// 是否可被选择
  final bool selectable;

  /// 文本流式显示发生布局变化时回调
  final VoidCallback? onDisplayedTextChanged;

  /// 尾随在文本末尾的内联组件
  final Widget? trailing;

  /// 当前文本是否已经是最终态。
  ///
  /// 仅影响文字动画，不决定 Markdown 是否渲染。
  final bool isFinal;

  /// 自定义聊天内资源打开方式。
  final OmnibotResourceOpenCallback? onResourceOpen;

  /// 兼容旧调用方的快照字段；不再用于拆分或延迟 Markdown 渲染。
  final int? markdownRenderedLength;

  const StreamingText({
    super.key,
    required this.fullText,
    required this.style,
    this.enableMarkdown = false,
    this.selectable = false,
    this.onDisplayedTextChanged,
    this.trailing,
    this.isFinal = true,
    this.onResourceOpen,
    this.markdownRenderedLength,
  });

  @override
  State<StreamingText> createState() => _StreamingTextState();
}

class _StreamingTextState extends State<StreamingText> {
  String _previousFullText = '';
  bool _isFirstBuild = true;
  late bool _requiresStructuredMarkdown;
  String? _lastSelectedContent; // 跟踪最后选中的内容
  int? _lastNotifiedDisplayLength;

  @override
  void initState() {
    super.initState();
    _requiresStructuredMarkdown =
        widget.enableMarkdown &&
        omnibotTextRequiresStructuredMarkdown(widget.fullText);
  }

  @override
  void didUpdateWidget(StreamingText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.enableMarkdown) {
      _requiresStructuredMarkdown = false;
    } else if (!oldWidget.enableMarkdown ||
        !widget.fullText.startsWith(oldWidget.fullText)) {
      _requiresStructuredMarkdown = omnibotTextRequiresStructuredMarkdown(
        widget.fullText,
      );
    } else if (oldWidget.fullText != widget.fullText) {
      // Keep scanning while a plain paragraph grows because a later chunk can
      // introduce a heading/list/code fence. Once structured syntax appears,
      // keep the Markdown path stable for the remainder of this message.
      _requiresStructuredMarkdown =
          _requiresStructuredMarkdown ||
          omnibotTextRequiresStructuredMarkdown(widget.fullText);
    }
    if (oldWidget.fullText != widget.fullText) {
      _previousFullText = _resolveAnimationStartText(
        previousText: oldWidget.fullText,
        nextText: widget.fullText,
      );
      _lastNotifiedDisplayLength = null;
    }
  }

  String _resolveAnimationStartText({
    required String previousText,
    required String nextText,
  }) {
    if (previousText == kThinkingText) {
      return previousText;
    }
    if (nextText.startsWith(previousText)) {
      return previousText;
    }
    return nextText;
  }

  void _notifyDisplayedTextChanged(int displayLength) {
    if (_lastNotifiedDisplayLength == displayLength) {
      return;
    }
    _lastNotifiedDisplayLength = displayLength;
    final callback = widget.onDisplayedTextChanged;
    if (callback == null) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        callback();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final animateInitialStreamingText =
        _isFirstBuild && !widget.isFinal && widget.fullText.isNotEmpty;
    // 第一次build时，初始化_previousFullText
    if (_isFirstBuild) {
      _previousFullText = widget.fullText;
      _isFirstBuild = false;
    }

    // 如果是思考中文案，直接显示，不做动画
    if (widget.fullText == kThinkingText) {
      final localizedText = kThinkingTextLocalized;
      Widget child = widget.enableMarkdown
          ? OmnibotMarkdownBody(
              data: localizedText,
              baseStyle: widget.style,
              inlineResourcePlainStyle: true,
              onResourceOpen: widget.onResourceOpen,
            )
          : Text(localizedText, style: widget.style);

      return _wrapSelectable(child);
    }

    if (widget.enableMarkdown && _requiresStructuredMarkdown) {
      if (omnibotTextCanUseStableBoldStreaming(
        widget.fullText,
        allowUnclosed: !widget.isFinal,
      )) {
        return _buildPlainAnimatedContent(
          animateInitialStreamingText: animateInitialStreamingText,
          renderBoldMarkdown: true,
        );
      }
      return _buildMarkdownContent();
    }

    return _buildPlainAnimatedContent(
      animateInitialStreamingText: animateInitialStreamingText,
    );
  }

  // MarkdownBody reparses when data changes. Rendering does not wait for
  // prompt completion or split a Markdown document at transport boundaries.
  Widget _buildMarkdownContent() {
    _notifyDisplayedTextChanged(widget.fullText.length);
    return _wrapSelectable(
      OmnibotMarkdownBody(
        data: widget.fullText,
        baseStyle: widget.style,
        inlineResourcePlainStyle: true,
        onResourceOpen: widget.onResourceOpen,
        trailingInline: widget.trailing,
      ),
    );
  }

  // ── 纯文本路径 ──
  // 普通正文继续使用现有逐字透出引擎。
  Widget _buildPlainAnimatedContent({
    bool animateInitialStreamingText = false,
    bool renderBoldMarkdown = false,
  }) {
    // 从"思考中..."切换到实际内容时，强制重建 widget 从 0 开始动画。
    final isThinkingTransition = _previousFullText == kThinkingText;
    return _wrapSelectable(
      OmnibotPacedRevealText(
        key: isThinkingTransition
            ? ValueKey('paced-${widget.fullText.hashCode}')
            : const ValueKey('omnibot-plain-reveal'),
        text: widget.fullText,
        style: widget.style,
        trailing: widget.trailing,
        initialVisibleLength:
            isThinkingTransition || animateInitialStreamingText ? 0 : null,
        onRevealedLengthChanged: _notifyDisplayedTextChanged,
        spanBuilder: renderBoldMarkdown ? omnibotBuildStreamingBoldSpan : null,
      ),
    );
  }

  Widget _wrapSelectable(Widget child) {
    if (!widget.selectable) {
      return child;
    }
    return SelectionArea(
      onSelectionChanged: (content) {
        _lastSelectedContent = content?.plainText;
      },
      contextMenuBuilder: (context, selectableRegionState) {
        return _buildSelectionContextMenu(selectableRegionState);
      },
      child: child,
    );
  }

  /// 构建选择文本的上下文菜单（使用 AssistsMessageService 复制到剪贴板）
  Widget _buildSelectionContextMenu(
    SelectableRegionState selectableRegionState,
  ) {
    return _GlassSelectionContextMenu(
      anchors: selectableRegionState.contextMenuAnchors,
      onSelectAll: () {
        selectableRegionState.selectAll(SelectionChangedCause.toolbar);
      },
      onCopy: () {
        final selectedText = _lastSelectedContent;
        selectableRegionState.hideToolbar();
        if (selectedText != null && selectedText.isNotEmpty) {
          AssistsMessageService.copyToClipboard(selectedText);
        }
      },
      onShare: () {
        final selectedText = _lastSelectedContent;
        selectableRegionState.hideToolbar();
        if (selectedText == null || selectedText.isEmpty) {
          return;
        }
        _shareSelectedText(selectedText);
      },
    );
  }

  Future<void> _shareSelectedText(String selectedText) async {
    try {
      final shared = await OmnibotResourceService.shareText(selectedText);
      if (!shared) {
        showToast(
          LegacyTextLocalizer.isEnglish
              ? 'Share failed, please try again later'
              : '发送失败，请稍后重试',
          type: ToastType.error,
        );
      }
    } catch (error) {
      debugPrint('share selected text failed: $error');
      showToast(
        LegacyTextLocalizer.isEnglish ? 'Share failed' : '发送失败',
        type: ToastType.error,
      );
    }
  }
}

/// Whether [source] needs block/inline Markdown layout rather than the stable
/// plain-paragraph streaming path.
///
/// Most assistant prose contains no Markdown structure. Rendering that prose
/// as a Markdown prefix plus a WidgetSpan tail changes its line metrics whenever
/// the parser flush boundary advances, which produces visible 1-2 px baseline
/// jumps. This deliberately conservative detector keeps real Markdown features
/// on the Markdown renderer while ordinary prose stays in one Text layout.
bool omnibotTextRequiresStructuredMarkdown(String source) {
  if (source.isEmpty) return false;
  source = normalizeOmnibotMarkdown(source);
  if (source.contains('omnibot://') ||
      source.contains('```') ||
      source.contains('~~~') ||
      source.contains('**') ||
      source.contains('__') ||
      source.contains('~~') ||
      source.contains('`')) {
    return true;
  }
  if (_structuredMarkdownInlineLinkPattern.hasMatch(source) ||
      _structuredMarkdownReferenceLinkPattern.hasMatch(source) ||
      _structuredMarkdownAsteriskEmphasisPattern.hasMatch(source) ||
      _structuredMarkdownUnderscoreEmphasisPattern.hasMatch(source) ||
      _structuredMarkdownAutolinkOrHtmlPattern.hasMatch(source) ||
      _structuredMarkdownMathPattern.hasMatch(source) ||
      _structuredMarkdownBlockPattern.hasMatch(source) ||
      _structuredMarkdownRulePattern.hasMatch(source) ||
      _structuredMarkdownIndentedCodePattern.hasMatch(source) ||
      omnibotMarkdownContainsTableCandidate(source)) {
    return true;
  }
  return false;
}

/// Whether [source] contains only prose plus strong-emphasis markers.
///
/// This subset can stay in one paced [Text.rich] for its entire lifetime. More
/// complex inline or block Markdown keeps using [OmnibotMarkdownBody].
bool omnibotTextCanUseStableBoldStreaming(
  String source, {
  bool allowUnclosed = true,
}) {
  source = normalizeOmnibotMarkdown(source);
  final hasBold = source.contains('**') || source.contains('__');
  if (!hasBold || source.contains('***') || source.contains('___')) {
    return false;
  }
  if (source.contains('omnibot://') ||
      source.contains('```') ||
      source.contains('~~~') ||
      source.contains('~~') ||
      source.contains('`') ||
      source.contains(r'$')) {
    return false;
  }
  final ranges = _streamingBoldRanges(source);
  if (ranges.isEmpty ||
      (!allowUnclosed && ranges.any((range) => !range.isClosed))) {
    return false;
  }
  return !_structuredMarkdownInlineLinkPattern.hasMatch(source) &&
      !_structuredMarkdownReferenceLinkPattern.hasMatch(source) &&
      !_structuredMarkdownAsteriskEmphasisPattern.hasMatch(
        source.replaceAll('**', ''),
      ) &&
      !_structuredMarkdownUnderscoreEmphasisPattern.hasMatch(
        source.replaceAll('__', ''),
      ) &&
      !_structuredMarkdownAutolinkOrHtmlPattern.hasMatch(source) &&
      !_structuredMarkdownBlockPattern.hasMatch(source) &&
      !_structuredMarkdownRulePattern.hasMatch(source) &&
      !_structuredMarkdownIndentedCodePattern.hasMatch(source) &&
      !omnibotMarkdownContainsTableCandidate(source);
}

/// Builds the visible portion of strong-emphasis prose without exposing its
/// Markdown delimiters. Unclosed markers are treated as an in-progress bold
/// span so streaming does not flash raw `**` before the closing marker arrives.
InlineSpan omnibotBuildStreamingBoldSpan(
  String source,
  int visibleSourceLength,
  TextStyle style,
) {
  final visibleEnd = _clampOmnibotTextToCodePointBoundary(
    source,
    visibleSourceLength.clamp(0, source.length),
  );
  final ranges = _streamingBoldRanges(source);
  final children = <InlineSpan>[];
  var cursor = 0;

  void append(String text, {required bool bold}) {
    if (text.isEmpty) return;
    children.add(
      TextSpan(
        text: text,
        style: bold ? style.copyWith(fontWeight: FontWeight.bold) : style,
      ),
    );
  }

  for (final range in ranges) {
    if (cursor >= visibleEnd) break;
    final plainEnd = range.openStart.clamp(cursor, visibleEnd);
    append(source.substring(cursor, plainEnd), bold: false);
    if (visibleEnd <= range.openEnd) {
      cursor = visibleEnd;
      break;
    }
    final boldEnd = range.closeStart.clamp(range.openEnd, visibleEnd);
    append(source.substring(range.openEnd, boldEnd), bold: true);
    cursor = range.closeEnd.clamp(0, visibleEnd);
  }
  if (cursor < visibleEnd) {
    append(source.substring(cursor, visibleEnd), bold: false);
  }
  return TextSpan(style: style, children: children);
}

List<_StreamingBoldRange> _streamingBoldRanges(String source) {
  final ranges = <_StreamingBoldRange>[];
  var cursor = 0;
  while (cursor + 1 < source.length) {
    final starIndex = source.indexOf('**', cursor);
    final underscoreIndex = source.indexOf('__', cursor);
    final openStart = switch ((starIndex, underscoreIndex)) {
      (-1, -1) => -1,
      (-1, _) => underscoreIndex,
      (_, -1) => starIndex,
      _ => starIndex < underscoreIndex ? starIndex : underscoreIndex,
    };
    if (openStart < 0) break;
    final marker = source.substring(openStart, openStart + 2);
    final contentStart = openStart + 2;
    if (_isEscapedMarkdownMarker(source, openStart) ||
        (marker == '__' &&
            openStart > 0 &&
            _isAsciiMarkdownWord(source.codeUnitAt(openStart - 1))) ||
        contentStart >= source.length ||
        _isMarkdownWhitespace(source.codeUnitAt(contentStart))) {
      cursor = contentStart;
      continue;
    }

    var closeStart = source.indexOf(marker, contentStart);
    while (closeStart >= 0 &&
        (_isEscapedMarkdownMarker(source, closeStart) ||
            closeStart == contentStart ||
            _isMarkdownWhitespace(source.codeUnitAt(closeStart - 1)) ||
            (marker == '__' &&
                closeStart + 2 < source.length &&
                _isAsciiMarkdownWord(source.codeUnitAt(closeStart + 2))))) {
      closeStart = source.indexOf(marker, closeStart + 2);
    }
    final hasClose = closeStart >= 0;
    ranges.add(
      _StreamingBoldRange(
        openStart: openStart,
        openEnd: contentStart,
        closeStart: hasClose ? closeStart : source.length,
        closeEnd: hasClose ? closeStart + 2 : source.length,
      ),
    );
    cursor = hasClose ? closeStart + 2 : source.length;
  }
  return ranges;
}

bool _isEscapedMarkdownMarker(String source, int index) {
  var slashCount = 0;
  var cursor = index - 1;
  while (cursor >= 0 && source.codeUnitAt(cursor) == 92) {
    slashCount += 1;
    cursor -= 1;
  }
  return slashCount.isOdd;
}

bool _isMarkdownWhitespace(int codeUnit) =>
    codeUnit == 9 || codeUnit == 10 || codeUnit == 13 || codeUnit == 32;

bool _isAsciiMarkdownWord(int codeUnit) =>
    (codeUnit >= 48 && codeUnit <= 57) ||
    (codeUnit >= 65 && codeUnit <= 90) ||
    (codeUnit >= 97 && codeUnit <= 122) ||
    codeUnit == 95;

class _StreamingBoldRange {
  const _StreamingBoldRange({
    required this.openStart,
    required this.openEnd,
    required this.closeStart,
    required this.closeEnd,
  });

  final int openStart;
  final int openEnd;
  final int closeStart;
  final int closeEnd;

  bool get isClosed => closeEnd > closeStart;
}

class _GlassSelectionContextMenu extends StatelessWidget {
  const _GlassSelectionContextMenu({
    required this.anchors,
    required this.onSelectAll,
    required this.onCopy,
    required this.onShare,
  });

  final TextSelectionToolbarAnchors anchors;
  final VoidCallback onSelectAll;
  final VoidCallback onCopy;
  final VoidCallback onShare;

  @override
  Widget build(BuildContext context) {
    final anchorBelow = anchors.secondaryAnchor ?? anchors.primaryAnchor;
    final safePadding = MediaQuery.paddingOf(context);
    return CustomSingleChildLayout(
      delegate: _GlassSelectionMenuLayoutDelegate(
        anchorAbove: anchors.primaryAnchor,
        anchorBelow: anchorBelow,
        screenPadding: _kSelectionMenuScreenPadding,
        topSafePadding: safePadding.top,
        bottomSafePadding: safePadding.bottom,
      ),
      child: OmniGlassPanel(
        borderRadius: BorderRadius.circular(14),
        padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 4),
        child: Material(
          type: MaterialType.transparency,
          child: SizedBox(
            height: 28,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                _GlassSelectionMenuButton(
                  label: LegacyTextLocalizer.isEnglish ? 'Select all' : '全选',
                  onPressed: onSelectAll,
                ),
                const _GlassSelectionMenuDivider(),
                _GlassSelectionMenuButton(
                  label: LegacyTextLocalizer.isEnglish ? 'Copy' : '复制',
                  onPressed: onCopy,
                ),
                const _GlassSelectionMenuDivider(),
                _GlassSelectionMenuButton(
                  label: LegacyTextLocalizer.isEnglish ? 'Share' : '发送',
                  onPressed: onShare,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _GlassSelectionMenuButton extends StatelessWidget {
  const _GlassSelectionMenuButton({
    required this.label,
    required this.onPressed,
  });

  final String label;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Tooltip(
      message: label,
      child: InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: onPressed,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minWidth: 50, minHeight: 34),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 8),
            child: Center(
              child: Text(
                label,
                maxLines: 1,
                softWrap: false,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: palette.textPrimary,
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  height: 1.0,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _GlassSelectionMenuDivider extends StatelessWidget {
  const _GlassSelectionMenuDivider();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 1,
      height: 20,
      color: context.omniPalette.borderSubtle.withValues(alpha: 0.55),
    );
  }
}

class _GlassSelectionMenuLayoutDelegate extends SingleChildLayoutDelegate {
  const _GlassSelectionMenuLayoutDelegate({
    required this.anchorAbove,
    required this.anchorBelow,
    required this.screenPadding,
    required this.topSafePadding,
    required this.bottomSafePadding,
  });

  final Offset anchorAbove;
  final Offset anchorBelow;
  final double screenPadding;
  final double topSafePadding;
  final double bottomSafePadding;

  @override
  BoxConstraints getConstraintsForChild(BoxConstraints constraints) {
    return BoxConstraints(
      maxWidth: (constraints.maxWidth - screenPadding * 2).clamp(
        0.0,
        double.infinity,
      ),
      maxHeight:
          (constraints.maxHeight -
                  topSafePadding -
                  bottomSafePadding -
                  screenPadding * 2)
              .clamp(0.0, double.infinity),
    );
  }

  @override
  Offset getPositionForChild(Size size, Size childSize) {
    final topLimit = topSafePadding + screenPadding;
    final fitsAbove =
        anchorAbove.dy - _kSelectionMenuAnchorGap - childSize.height >=
        topLimit;
    final anchor = fitsAbove ? anchorAbove : anchorBelow;
    final minX = screenPadding;
    final maxX = size.width - childSize.width - screenPadding;
    final dx = _clampToMenuBounds(anchor.dx - childSize.width / 2, minX, maxX);
    final dy = fitsAbove
        ? anchor.dy - _kSelectionMenuAnchorGap - childSize.height
        : anchor.dy + _kSelectionMenuAnchorGap;
    final maxY =
        size.height - childSize.height - bottomSafePadding - screenPadding;
    return Offset(dx, _clampToMenuBounds(dy, topLimit, maxY));
  }

  @override
  bool shouldRelayout(_GlassSelectionMenuLayoutDelegate oldDelegate) {
    return anchorAbove != oldDelegate.anchorAbove ||
        anchorBelow != oldDelegate.anchorBelow ||
        screenPadding != oldDelegate.screenPadding ||
        topSafePadding != oldDelegate.topSafePadding ||
        bottomSafePadding != oldDelegate.bottomSafePadding;
  }
}

double _clampToMenuBounds(double value, double min, double max) {
  if (max < min) {
    return min;
  }
  return value.clamp(min, max).toDouble();
}

const double _kSelectionMenuScreenPadding = 8.0;
const double _kSelectionMenuAnchorGap = 10.0;

/// 流式尾部文本（fast-path 内嵌 / 纯文本独立渲染）。
///
/// 基于 [Ticker] 的逐字透出引擎：
/// - 输入文本仅支持前缀增长（[text] 以旧值为前缀），非前缀变化直接跳至末态
/// - 使用 credit 累进方式以约 30 ms/字的稳定速率逐字显示
/// - 当积压较大（>20 字）时自动加速避免显示延迟过大
/// - 整体动画仅触发本地小区域重绘，不拉动外层 markdown 子树
class OmnibotPacedRevealText extends StatefulWidget {
  const OmnibotPacedRevealText({
    super.key,
    required this.text,
    required this.style,
    this.trailing,
    this.initialVisibleLength,
    this.onRevealedLengthChanged,
    this.spanBuilder,
  });

  final String text;
  final TextStyle style;
  final Widget? trailing;

  /// 初始可见字符数。
  /// `null`（默认）→ 从 [text.length] 开始（即全量显示，仅对后续增长做动画）。
  /// 传入具体值（如 `0`）→ 从此长度开始逐字透出到 [text.length]。
  final int? initialVisibleLength;

  /// 每次透出长度变化时回调，用于父级跨 flush 追踪总可见字符数。
  final void Function(int revealedLength)? onRevealedLengthChanged;

  /// Optional formatter for the visible source prefix. It must return inline
  /// content with the same paragraph metrics as [style].
  final InlineSpan Function(
    String source,
    int visibleSourceLength,
    TextStyle style,
  )?
  spanBuilder;

  @override
  State<OmnibotPacedRevealText> createState() => _OmnibotPacedRevealTextState();
}

class _OmnibotPacedRevealTextState extends State<OmnibotPacedRevealText>
    with SingleTickerProviderStateMixin {
  // ── 透出参数 ──
  /// 基础速率：每 30 ms 显示 1 个字符（≈33 字/秒）
  static const int _kBaseIntervalMs = 30;

  /// 最小帧间隔，防止热循环
  static const Duration _kMinFrameInterval = Duration(milliseconds: 8);

  /// 积压超过此值时开始加速
  static const int _kSpeedupBacklog = 20;

  /// 最大加速倍数
  static const double _kMaxSpeedMultiplier = 4.0;

  // ── 状态 ──
  late final Ticker _ticker;
  int _visibleLength = 0;
  Duration _lastTickTime = Duration.zero;
  double _credit = 0.0;

  @override
  void initState() {
    super.initState();
    _visibleLength = (widget.initialVisibleLength ?? widget.text.length).clamp(
      0,
      widget.text.length,
    );
    _ticker = createTicker(_onTick);
    if (_visibleLength < widget.text.length) {
      _ticker.start();
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(OmnibotPacedRevealText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.text == oldWidget.text) return;

    if (widget.text.length > oldWidget.text.length &&
        widget.text.startsWith(oldWidget.text)) {
      // 前缀扩展：保持当前可见长度，让 ticker 追赶新目标
      if (!_ticker.isActive && _visibleLength < widget.text.length) {
        _ticker.start();
      }
    } else {
      // 文本回退或整体替换：
      // 当 initialVisibleLength 显式传入了值时（如 StreamingText fast-path
      // 尾部推进场景），从该值重新开始逐字透出而非瞬时收敛；
      // 未传入时保持旧行为：直接跳到全文可见。
      _visibleLength = (widget.initialVisibleLength ?? widget.text.length)
          .clamp(0, widget.text.length);
      _credit = 0.0;
      if (_visibleLength < widget.text.length) {
        _ticker.start();
      } else {
        _ticker.stop();
      }
      widget.onRevealedLengthChanged?.call(_visibleLength);
    }
  }

  void _onTick(Duration elapsed) {
    final dt = elapsed - _lastTickTime;
    if (dt < _kMinFrameInterval) return;
    _lastTickTime = elapsed;

    final targetLen = widget.text.length;
    if (_visibleLength >= targetLen) {
      _ticker.stop();
      return;
    }

    final backlog = targetLen - _visibleLength;

    // 积压较大时加速（最多 4×）
    final speedMultiplier =
        1.0 +
        ((backlog - _kSpeedupBacklog) / _kSpeedupBacklog).clamp(
          0.0,
          _kMaxSpeedMultiplier - 1.0,
        );
    final effectiveInterval = _kBaseIntervalMs / speedMultiplier;

    _credit += dt.inMilliseconds / effectiveInterval;
    final wholeChars = _credit.floor();
    if (wholeChars <= 0) return;
    _credit -= wholeChars.toDouble();

    final step = wholeChars.clamp(1, backlog);
    setState(() {
      _visibleLength = (_visibleLength + step).clamp(0, targetLen);
    });
    widget.onRevealedLengthChanged?.call(_visibleLength);
  }

  @override
  Widget build(BuildContext context) {
    final hasTrailing = widget.trailing != null;
    final safeVisible = _clampOmnibotTextToCodePointBoundary(
      widget.text,
      _visibleLength.clamp(0, widget.text.length),
    );
    final visibleText = safeVisible > 0
        ? widget.text.substring(0, safeVisible)
        : '';
    if (visibleText.isEmpty && !hasTrailing) {
      return const SizedBox.shrink();
    }
    return RepaintBoundary(
      child: Text.rich(
        TextSpan(
          style: widget.style,
          children: <InlineSpan>[
            if (visibleText.isNotEmpty)
              widget.spanBuilder?.call(
                    widget.text,
                    safeVisible,
                    widget.style,
                  ) ??
                  TextSpan(text: visibleText),
            if (hasTrailing)
              WidgetSpan(
                alignment: PlaceholderAlignment.middle,
                child: Padding(
                  padding: const EdgeInsets.only(left: 4),
                  child: widget.trailing!,
                ),
              ),
          ],
        ),
      ),
    );
  }
}
