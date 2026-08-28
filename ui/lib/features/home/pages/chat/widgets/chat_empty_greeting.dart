import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:ui/features/home/widgets/home_quick_prompt_icon.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/home_greeting_settings_service.dart';
import 'package:ui/theme/theme_context.dart';

const List<String> _kChatGreetingWordsZh = <String>[
  '聊天',
  '执行',
  '构建',
  '探索',
  '规划',
  '总结',
  '检索',
  '记忆',
];

const List<String> _kChatGreetingWordsEn = <String>[
  'chat',
  'execute',
  'build',
  'explore',
  'plan',
  'summarize',
  'search',
  'remember',
];

class ChatEmptyGreeting extends StatelessWidget {
  final Color? primaryTextColor;
  final Color? secondaryTextColor;
  final Color? accentColor;
  final bool compact;
  final List<HomeQuickPrompt> quickPrompts;
  final List<String> pinnedQuickPromptIds;
  final ValueChanged<HomeQuickPrompt>? onQuickPromptSelected;
  final String? agentName;
  final String? agentWorkspaceName;
  final VoidCallback? onAgentWorkspaceTap;

  const ChatEmptyGreeting({
    super.key,
    this.primaryTextColor,
    this.secondaryTextColor,
    this.accentColor,
    this.compact = false,
    this.quickPrompts = const <HomeQuickPrompt>[],
    this.pinnedQuickPromptIds = const <String>[],
    this.onQuickPromptSelected,
    this.agentName,
    this.agentWorkspaceName,
    this.onAgentWorkspaceTap,
  });

  @override
  Widget build(BuildContext context) {
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    final palette = context.omniPalette;
    final primaryColor = primaryTextColor ?? palette.textPrimary;
    final secondaryColor = secondaryTextColor ?? palette.textSecondary;
    final keywordColor = accentColor ?? palette.accentPrimary;
    final disableAnimations =
        MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    final resolvedAgentName = agentName?.trim() ?? '';
    final displayAgentName = resolvedAgentName.isEmpty
        ? (isEnglish ? 'Omnibot' : '小万')
        : resolvedAgentName;
    final headline = isEnglish
        ? "Hi 👋, I'm $displayAgentName"
        : '你好👋，我是$displayAgentName';
    final prefix = isEnglish ? 'I can help you' : '我可以帮助你';
    final workspaceName = agentWorkspaceName?.trim() ?? '';
    final useAgentWorkspaceGreeting = workspaceName.isNotEmpty;
    final words = isEnglish ? _kChatGreetingWordsEn : _kChatGreetingWordsZh;
    final fontSize = compact ? 17.0 : 19.0;
    final headlineStyle = TextStyle(
      color: primaryColor,
      fontSize: fontSize,
      fontWeight: FontWeight.w400,
      height: 1.3,
      letterSpacing: 0,
    );
    final helperStyle = TextStyle(
      color: secondaryColor,
      fontSize: fontSize,
      fontWeight: FontWeight.w400,
      height: 1.3,
      letterSpacing: 0,
    );
    final keywordStyle = TextStyle(
      color: keywordColor,
      fontSize: fontSize,
      fontWeight: FontWeight.w400,
      height: 1.3,
      letterSpacing: 0,
      fontFamily: isEnglish ? 'Georgia' : 'Noto Serif CJK SC',
      fontFamilyFallback: const <String>[
        'Noto Serif CJK SC',
        'Source Han Serif SC',
        'Songti SC',
        'STSong',
        'Georgia',
        'Times New Roman',
        'serif',
      ],
    );

    final greetingSemanticsLabel = useAgentWorkspaceGreeting
        ? (isEnglish
              ? '$headline\nWhat can we do in $workspaceName?'
              : '$headline\n可以在$workspaceName中做点什么')
        : (isEnglish
              ? "$headline\nI can help you chat, execute, build, and explore."
              : '$headline\n我可以帮助你聊天、执行、构建和探索。');

    return TweenAnimationBuilder<double>(
      tween: Tween<double>(begin: 0, end: 1),
      duration: disableAnimations
          ? Duration.zero
          : const Duration(milliseconds: 420),
      curve: Curves.easeOutCubic,
      builder: (context, value, child) {
        if (disableAnimations) {
          return child ?? const SizedBox.shrink();
        }
        final eased = Curves.easeOutCubic.transform(value);
        return Opacity(
          opacity: eased,
          child: Transform.translate(
            offset: Offset(0, 10 * (1 - eased)),
            child: child,
          ),
        );
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Semantics(
              label: greetingSemanticsLabel,
              child: ExcludeSemantics(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      headline,
                      textAlign: TextAlign.left,
                      style: headlineStyle,
                    ),
                    const SizedBox(height: 6),
                    if (useAgentWorkspaceGreeting)
                      Wrap(
                        alignment: WrapAlignment.start,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        spacing: 5,
                        runSpacing: 2,
                        children: [
                          Text(
                            isEnglish ? 'What can we do in' : '可以在',
                            style: helperStyle,
                          ),
                          _AgentWorkspaceNameButton(
                            label: workspaceName,
                            style: keywordStyle,
                            onTap: onAgentWorkspaceTap,
                          ),
                          Text(isEnglish ? '?' : '中做点什么', style: helperStyle),
                        ],
                      )
                    else
                      Wrap(
                        alignment: WrapAlignment.start,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        spacing: 5,
                        runSpacing: 2,
                        children: [
                          Text(prefix, style: helperStyle),
                          _SlotWordRotator(words: words, style: keywordStyle),
                        ],
                      ),
                    if (quickPrompts.isNotEmpty) ...[
                      const SizedBox(height: 14),
                      _RandomQuickPromptPills(
                        quickPrompts: quickPrompts,
                        pinnedQuickPromptIds: pinnedQuickPromptIds,
                        textColor: primaryColor,
                        accentColor: keywordColor,
                        compact: compact,
                        onQuickPromptSelected: onQuickPromptSelected,
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _AgentWorkspaceNameButton extends StatelessWidget {
  const _AgentWorkspaceNameButton({
    required this.label,
    required this.style,
    this.onTap,
  });

  final String label;
  final TextStyle style;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final effectiveStyle = style.copyWith(
      decoration: onTap == null
          ? TextDecoration.none
          : TextDecoration.underline,
      decorationColor: style.color,
      decorationThickness: 1.2,
    );
    final text = Text(
      label,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: effectiveStyle,
    );
    if (onTap == null) {
      return text;
    }
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(6),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 1, vertical: 1),
        child: text,
      ),
    );
  }
}

class _RandomQuickPromptPills extends StatefulWidget {
  final List<HomeQuickPrompt> quickPrompts;
  final List<String> pinnedQuickPromptIds;
  final Color textColor;
  final Color accentColor;
  final bool compact;
  final ValueChanged<HomeQuickPrompt>? onQuickPromptSelected;

  const _RandomQuickPromptPills({
    required this.quickPrompts,
    required this.pinnedQuickPromptIds,
    required this.textColor,
    required this.accentColor,
    required this.compact,
    this.onQuickPromptSelected,
  });

  @override
  State<_RandomQuickPromptPills> createState() =>
      _RandomQuickPromptPillsState();
}

class _RandomQuickPromptPillsState extends State<_RandomQuickPromptPills> {
  final math.Random _random = math.Random();
  String _promptSignature = '';
  List<HomeQuickPrompt> _selectedPrompts = const <HomeQuickPrompt>[];

  @override
  void initState() {
    super.initState();
    _refreshSelection();
  }

  @override
  void didUpdateWidget(covariant _RandomQuickPromptPills oldWidget) {
    super.didUpdateWidget(oldWidget);
    final nextSignature = _buildPromptSignature(widget.quickPrompts);
    if (nextSignature != _promptSignature) {
      _refreshSelection(signature: nextSignature);
    }
  }

  void _refreshSelection({String? signature}) {
    _promptSignature = signature ?? _buildPromptSignature(widget.quickPrompts);
    final candidates = widget.quickPrompts
        .where((prompt) => prompt.title.trim().isNotEmpty)
        .toList(growable: false);
    final pinnedIds = widget.pinnedQuickPromptIds
        .map((id) => id.trim())
        .where((id) => id.isNotEmpty)
        .take(2)
        .toList(growable: false);
    if (pinnedIds.isNotEmpty) {
      final promptsById = {for (final prompt in candidates) prompt.id: prompt};
      _selectedPrompts = pinnedIds
          .map((id) => promptsById[id])
          .whereType<HomeQuickPrompt>()
          .take(2)
          .toList(growable: false);
      return;
    }
    if (candidates.length <= 2) {
      _selectedPrompts = candidates;
      return;
    }
    final shuffled = List<HomeQuickPrompt>.of(candidates)..shuffle(_random);
    _selectedPrompts = shuffled.take(2).toList(growable: false);
  }

  String _buildPromptSignature(List<HomeQuickPrompt> prompts) {
    final promptSignature = prompts
        .map(
          (prompt) => [
            prompt.id,
            prompt.title,
            prompt.titleEn ?? '',
            prompt.prompt,
            prompt.promptEn ?? '',
            prompt.iconKey,
          ].join('\u0001'),
        )
        .join('\u0002');
    final pinnedSignature = widget.pinnedQuickPromptIds.join('\u0003');
    return '$promptSignature\u0004$pinnedSignature';
  }

  @override
  Widget build(BuildContext context) {
    if (_selectedPrompts.isEmpty) {
      return const SizedBox.shrink();
    }
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: _selectedPrompts
          .map(
            (prompt) => _QuickPromptPill(
              prompt: prompt,
              textColor: widget.textColor,
              accentColor: widget.accentColor,
              compact: widget.compact,
              onTap: widget.onQuickPromptSelected == null
                  ? null
                  : () => widget.onQuickPromptSelected!(prompt),
            ),
          )
          .toList(growable: false),
    );
  }
}

class _QuickPromptPill extends StatelessWidget {
  final HomeQuickPrompt prompt;
  final Color textColor;
  final Color accentColor;
  final bool compact;
  final VoidCallback? onTap;

  const _QuickPromptPill({
    required this.prompt,
    required this.textColor,
    required this.accentColor,
    required this.compact,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final label = prompt.resolveTitle(context);
    final backgroundColor = accentColor.withValues(
      alpha: context.isDarkTheme ? 0.13 : 0.09,
    );
    final foregroundColor = context.isDarkTheme ? accentColor : textColor;
    return Semantics(
      button: true,
      label: label,
      child: Material(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(999),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(999),
          splashColor: accentColor.withValues(alpha: 0.12),
          highlightColor: accentColor.withValues(alpha: 0.06),
          child: Container(
            height: compact ? 32 : 34,
            padding: EdgeInsets.symmetric(
              horizontal: compact ? 11 : 13,
              vertical: 6,
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  homeQuickPromptIcon(prompt.iconKey),
                  size: compact ? 14 : 15,
                  color: accentColor,
                ),
                const SizedBox(width: 6),
                Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: foregroundColor,
                    fontSize: compact ? 12 : 13,
                    fontWeight: FontWeight.w500,
                    height: 1,
                    letterSpacing: 0,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SlotWordRotator extends StatefulWidget {
  final List<String> words;
  final TextStyle style;

  const _SlotWordRotator({required this.words, required this.style});

  @override
  State<_SlotWordRotator> createState() => _SlotWordRotatorState();
}

class _SlotWordRotatorState extends State<_SlotWordRotator>
    with SingleTickerProviderStateMixin {
  static const Duration _rotationInterval = Duration(milliseconds: 1800);
  static const Duration _spinDuration = Duration(milliseconds: 460);

  late final AnimationController _controller;
  final math.Random _random = math.Random();
  Timer? _timer;
  int _currentIndex = 0;
  int? _previousIndex;

  @override
  void initState() {
    super.initState();
    _currentIndex = _randomInitialIndex();
    _controller = AnimationController(vsync: this, duration: _spinDuration);
    _startTimer();
  }

  @override
  void didUpdateWidget(covariant _SlotWordRotator oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!listEquals(oldWidget.words, widget.words)) {
      _controller.stop();
      _currentIndex = _randomInitialIndex();
      _previousIndex = null;
      _controller.value = 0;
      _startTimer();
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    _controller.dispose();
    super.dispose();
  }

  int _randomInitialIndex() {
    if (widget.words.isEmpty) {
      return 0;
    }
    return _random.nextInt(widget.words.length);
  }

  void _startTimer() {
    _timer?.cancel();
    if (widget.words.length <= 1) {
      return;
    }
    _timer = Timer.periodic(_rotationInterval, (_) => _advance());
  }

  void _advance() {
    if (!mounted || widget.words.length <= 1 || _controller.isAnimating) {
      return;
    }
    var nextIndex = _random.nextInt(widget.words.length);
    if (nextIndex == _currentIndex) {
      nextIndex = (nextIndex + 1) % widget.words.length;
    }
    setState(() {
      _previousIndex = _currentIndex;
      _currentIndex = nextIndex;
    });
    _controller.forward(from: 0).whenCompleteOrCancel(() {
      if (!mounted) {
        return;
      }
      setState(() {
        _previousIndex = null;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    if (widget.words.isEmpty) {
      return const SizedBox.shrink();
    }
    final disableAnimations =
        MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    final textScaler = MediaQuery.textScalerOf(context);
    final fontSize = widget.style.fontSize ?? 20;
    final lineHeight = widget.style.height ?? 1.2;
    final slotHeight = math.max(24.0, textScaler.scale(fontSize) * lineHeight);
    final maxWordWidth = _measureMaxWordWidth(context);

    if (disableAnimations || _previousIndex == null) {
      return SizedBox(
        width: maxWordWidth,
        height: slotHeight,
        child: Align(
          alignment: Alignment.centerLeft,
          child: Text(
            widget.words[_currentIndex],
            maxLines: 1,
            overflow: TextOverflow.visible,
            style: widget.style,
          ),
        ),
      );
    }

    return SizedBox(
      width: maxWordWidth,
      height: slotHeight,
      child: ClipRect(
        child: AnimatedBuilder(
          animation: _controller,
          builder: (context, _) {
            final slideValue = Curves.easeOutCubic.transform(_controller.value);
            final fadeIn = Curves.easeOut.transform(_controller.value);
            final fadeOut = Curves.easeIn.transform(1 - _controller.value);
            return Stack(
              clipBehavior: Clip.hardEdge,
              children: [
                _buildWord(
                  widget.words[_previousIndex!],
                  dy: -slotHeight * slideValue,
                  opacity: fadeOut,
                ),
                _buildWord(
                  widget.words[_currentIndex],
                  dy: slotHeight * (1 - slideValue),
                  opacity: fadeIn,
                ),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _buildWord(
    String text, {
    required double dy,
    required double opacity,
  }) {
    return Positioned.fill(
      child: Opacity(
        opacity: opacity.clamp(0.0, 1.0).toDouble(),
        child: Transform.translate(
          offset: Offset(0, dy),
          child: Align(
            alignment: Alignment.centerLeft,
            child: Text(
              text,
              maxLines: 1,
              overflow: TextOverflow.visible,
              style: widget.style,
            ),
          ),
        ),
      ),
    );
  }

  double _measureMaxWordWidth(BuildContext context) {
    final textDirection = Directionality.of(context);
    final textScaler = MediaQuery.textScalerOf(context);
    var maxWidth = 0.0;
    for (final word in widget.words) {
      final painter = TextPainter(
        text: TextSpan(text: word, style: widget.style),
        maxLines: 1,
        textDirection: textDirection,
        textScaler: textScaler,
      )..layout();
      maxWidth = math.max(maxWidth, painter.width);
    }
    return maxWidth + 2;
  }
}
