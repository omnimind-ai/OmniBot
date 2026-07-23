import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/chat_drawer_gesture_guard.dart';
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

/// 流式文本显示组件，支持平滑逐字透出效果
///
/// 用于显示流式推送的文本内容
///
/// **性能策略**：
/// - 启用 Markdown 时，绝不在动画帧里重新解析 markdown。
///   - 已有 [markdownRenderedLength]（>0 且 <fullText）时：走 fast-path，
///     固化 markdown 前缀 + [OmnibotPacedRevealText] 纯文本尾部逐字透出。
///   - 否则：一次性渲染最新 markdown，不做逐字动画。
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
  /// 未完成的 Markdown 表格会先走轻量、稳定的流式预览；最终态再渲染
  /// 完整 Markdown 表格，避免表格列宽/高度在流式过程中反复重算。
  final bool isFinal;

  /// 自定义聊天内资源打开方式。
  final OmnibotResourceOpenCallback? onResourceOpen;

  /// 已完成 Markdown 渲染的文本长度（字符数）。
  ///
  /// 当流式输出时，每 N 个 chunk 才执行一次 Markdown 渲染。该值表示上次
  /// flush 时已渲染为 Markdown 的文本长度。超出该长度的新文本以纯文本追加，
  /// 避免整段文本在 Markdown 与纯文本之间来回跳动。
  ///
  /// - `null`：整段文本按 Markdown 渲染（flush 完成 / 流结束）
  /// - `>= 0 && < fullText.length`：前缀按 Markdown 渲染，尾部按 [OmnibotPacedRevealText] 逐字透出
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
  String? _lastSelectedContent; // 跟踪最后选中的内容
  int? _lastNotifiedDisplayLength;

  /// 已逐字透出的总字符数（跨 markdown flush 边界保持连续）。
  /// 当 tail 逐字推进时由 [_onTailRevealedChars] 更新；
  /// 在 [_buildMarkdownFastPath] 中用于计算新 tail 的起始可见长度。
  int _totalRevealedChars = 0;

  @override
  void didUpdateWidget(StreamingText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.fullText != widget.fullText) {
      _previousFullText = _resolveAnimationStartText(
        previousText: oldWidget.fullText,
        nextText: widget.fullText,
      );
      _lastNotifiedDisplayLength = null;
      // 文本被替换（非前缀增长）时重置跨 flush 透出计数
      if (!widget.fullText.startsWith(oldWidget.fullText)) {
        _totalRevealedChars = 0;
      }
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

    Widget content;
    if (widget.enableMarkdown) {
      content = _buildMarkdownContent();
    } else {
      content = _buildPlainAnimatedContent();
    }

    return AnimatedSize(
      duration: const Duration(milliseconds: 200),
      curve: Curves.easeOutCubic,
      alignment: Alignment.topCenter,
      child: content,
    );
  }

  // ── Markdown 路径 ──
  // 优先走 fast-path：固化 markdown 前缀 + 尾部逐字透出。
  // markdownRenderedLength 为 null 时（flush 完成/流结束）走全量 markdown 渲染，不做动画。
  // markdownRenderedLength 为 0 时（首批 chunk 未 flush）全文走尾部透出，确保首字起流式。
  Widget _buildMarkdownContent() {
    final mdLen = widget.markdownRenderedLength;
    final containsTable = omnibotMarkdownContainsTableCandidate(
      widget.fullText,
    );
    final streamingTableBlock = !widget.isFinal && containsTable
        ? omnibotMarkdownTrailingTableBlock(widget.fullText)
        : null;
    if (streamingTableBlock != null) {
      return _buildMarkdownWithStreamingTableBlock(streamingTableBlock);
    }
    if (mdLen != null && mdLen >= 0 && mdLen < widget.fullText.length) {
      return _buildMarkdownFastPath(mdLen, containsTable: containsTable);
    }
    _notifyDisplayedTextChanged(widget.fullText.length);
    final visibleText = containsTable
        ? omnibotMarkdownWithoutTrailingTableCandidate(widget.fullText)
        : widget.fullText;
    return _wrapSelectable(
      OmnibotMarkdownBody(
        data: visibleText,
        baseStyle: widget.style,
        inlineResourcePlainStyle: true,
        onResourceOpen: widget.onResourceOpen,
        trailingInline: widget.trailing,
      ),
      enabled: !containsTable,
    );
  }

  Widget _buildMarkdownWithStreamingTableBlock(
    OmnibotStreamingTableBlock block,
  ) {
    _notifyDisplayedTextChanged(widget.fullText.length);
    final children = <Widget>[];
    final leadingMarkdown = block.leadingMarkdown.trimRight();
    if (leadingMarkdown.isNotEmpty) {
      children.add(
        OmnibotMarkdownBody(
          data: leadingMarkdown,
          baseStyle: widget.style,
          inlineResourcePlainStyle: true,
          onResourceOpen: widget.onResourceOpen,
        ),
      );
    }
    if (block.tableMarkdown.trim().isNotEmpty) {
      if (children.isNotEmpty) {
        children.add(const SizedBox(height: 6));
      }
      children.add(
        _StreamingMarkdownTablePreview(
          markdown: block.tableMarkdown,
          style: widget.style,
          trailing: widget.trailing,
        ),
      );
    } else if (widget.trailing != null) {
      children.add(widget.trailing!);
    }
    if (children.isEmpty) {
      return const SizedBox.shrink();
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: children,
    );
  }

  Widget _buildMarkdownFastPath(int mdLen, {required bool containsTable}) {
    final safeMdLen = _clampToCodePointBoundary(widget.fullText, mdLen);
    final mdText = widget.fullText.substring(0, safeMdLen);
    final plainTail = widget.fullText.substring(safeMdLen);

    _notifyDisplayedTextChanged(widget.fullText.length);

    // 跨 flush 边界保持连续的可见字符数：
    //  - 已透出的总数 _totalRevealedChars 减去已固化为 markdown 前缀的 safeMdLen，
    //    等于新 tail 中需要一开始就可见的字符数。
    //  - 当 safeMdLen 变大（flush 发生）时，tail 的起始可见长度自然缩小，
    //    已透出的字符"移入"markdown 前缀中。
    final tailInitialVisible = (_totalRevealedChars - safeMdLen).clamp(
      0,
      plainTail.length,
    );

    void onTailRevealed(int revealed) {
      _totalRevealedChars = safeMdLen + revealed;
    }

    if (containsTable) {
      return _buildMarkdownFastPathWithBlockTail(
        mdText: mdText,
        plainTail: plainTail,
      );
    }

    // 尾部走逐字透出 stateful widget（轻量重绘，不触碰 markdown 子树）。
    final inlineTrailing = (plainTail.isEmpty && widget.trailing == null)
        ? null
        : OmnibotPacedRevealText(
            key: const ValueKey('omnibot-streaming-tail'),
            text: plainTail,
            style: widget.style,
            trailing: widget.trailing,
            initialVisibleLength: tailInitialVisible,
            onRevealedLengthChanged: onTailRevealed,
          );

    return _wrapSelectable(
      OmnibotMarkdownBody(
        data: mdText,
        baseStyle: widget.style,
        inlineResourcePlainStyle: true,
        onResourceOpen: widget.onResourceOpen,
        trailingInline: inlineTrailing,
      ),
    );
  }

  Widget _buildMarkdownFastPathWithBlockTail({
    required String mdText,
    required String plainTail,
  }) {
    final visibleMarkdown = omnibotMarkdownWithoutTrailingTableCandidate(
      mdText,
    );
    final visibleTail = _visibleMarkdownTableStreamingTail(
      plainTail: plainTail,
    );
    final hasTail = visibleTail.isNotEmpty || widget.trailing != null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        OmnibotMarkdownBody(
          data: visibleMarkdown,
          baseStyle: widget.style,
          inlineResourcePlainStyle: true,
          onResourceOpen: widget.onResourceOpen,
        ),
        if (hasTail)
          OmnibotPacedRevealText(
            key: const ValueKey('omnibot-streaming-table-tail'),
            text: visibleTail,
            style: widget.style,
            trailing: widget.trailing,
          ),
      ],
    );
  }

  String _visibleMarkdownTableStreamingTail({required String plainTail}) {
    if (plainTail.isEmpty) {
      return '';
    }
    final tailLines = plainTail.split('\n');
    final tableStartIndex = tailLines.indexWhere(
      omnibotMarkdownLineLooksLikeTableCandidate,
    );
    if (tableStartIndex == -1) {
      return plainTail;
    }

    var index = tableStartIndex;
    while (index < tailLines.length) {
      final line = tailLines[index];
      if (line.trim().isEmpty) {
        return _joinTailAfterTableBlock(tailLines, index + 1);
      }
      if (!omnibotMarkdownLineLooksLikeTableCandidate(line)) {
        return tailLines.sublist(index).join('\n');
      }
      index += 1;
    }
    return '';
  }

  String _joinTailAfterTableBlock(List<String> lines, int startIndex) {
    var index = startIndex;
    while (index < lines.length && lines[index].trim().isEmpty) {
      index += 1;
    }
    if (index >= lines.length) {
      return '';
    }
    return lines.sublist(index).join('\n');
  }

  // ── 纯文本路径 ──
  // 使用与 markdown fast-path 尾部相同的逐字透出引擎。
  Widget _buildPlainAnimatedContent() {
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
        initialVisibleLength: isThinkingTransition ? 0 : null,
      ),
    );
  }

  Widget _wrapSelectable(Widget child, {bool enabled = true}) {
    if (!widget.selectable || !enabled) {
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

  int _clampToCodePointBoundary(String text, int requestedLength) {
    var safeLength = requestedLength.clamp(0, text.length);
    if (safeLength <= 0 || safeLength >= text.length) {
      return safeLength;
    }
    final currentUnit = text.codeUnitAt(safeLength);
    final previousUnit = text.codeUnitAt(safeLength - 1);
    final isCurrentLowSurrogate =
        currentUnit >= 0xDC00 && currentUnit <= 0xDFFF;
    final isPreviousHighSurrogate =
        previousUnit >= 0xD800 && previousUnit <= 0xDBFF;
    if (isCurrentLowSurrogate && isPreviousHighSurrogate) {
      safeLength -= 1;
    }
    return safeLength;
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

class _StreamingMarkdownTablePreview extends StatelessWidget {
  const _StreamingMarkdownTablePreview({
    required this.markdown,
    required this.style,
    this.trailing,
  });

  final String markdown;
  final TextStyle style;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final previewStyle = style.copyWith(
      fontFamily: 'monospace',
      fontFamilyFallback: const <String>['Courier'],
      fontSize: (style.fontSize ?? 14) * 0.92,
      height: 1.45,
      color: style.color ?? theme.colorScheme.onSurface,
    );
    final child = ChatDrawerGestureGuard(
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Text(markdown, style: previewStyle, softWrap: false),
      ),
    );
    return SelectionContainer.disabled(
      child: RepaintBoundary(
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: theme.colorScheme.surfaceContainerHighest.withValues(
              alpha: 0.48,
            ),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(
              color: theme.colorScheme.outlineVariant.withValues(alpha: 0.45),
              width: 0.8,
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            child: trailing == null
                ? child
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [child, const SizedBox(height: 6), trailing!],
                  ),
          ),
        ),
      ),
    );
  }
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
    final safeVisible = _visibleLength.clamp(0, widget.text.length);
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
            if (visibleText.isNotEmpty) TextSpan(text: visibleText),
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
