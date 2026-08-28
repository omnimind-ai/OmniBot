import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/glass_popup.dart';

class ChatSpotlightTour extends StatefulWidget {
  const ChatSpotlightTour({
    super.key,
    required this.step,
    this.anchorKey,
    required this.onNext,
    required this.onFinish,
  });

  static const int stepCount = 6;

  final int step;
  final GlobalKey? anchorKey;
  final VoidCallback onNext;
  final VoidCallback onFinish;

  @override
  State<ChatSpotlightTour> createState() => _ChatSpotlightTourState();
}

class _ChatSpotlightTourState extends State<ChatSpotlightTour> {
  final GlobalKey _overlayKey = GlobalKey();
  Rect? _measuredSpotlight;

  @override
  void didUpdateWidget(covariant ChatSpotlightTour oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.step != widget.step ||
        oldWidget.anchorKey != widget.anchorKey) {
      _measuredSpotlight = null;
    }
  }

  void _scheduleAnchorMeasurement() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final anchorBox =
          findActiveRenderObject(widget.anchorKey?.currentContext)
              as RenderBox?;
      final overlayBox =
          findActiveRenderObject(_overlayKey.currentContext) as RenderBox?;
      if (anchorBox == null ||
          overlayBox == null ||
          !anchorBox.hasSize ||
          !overlayBox.hasSize) {
        return;
      }

      final globalTopLeft = anchorBox.localToGlobal(Offset.zero);
      final localTopLeft = overlayBox.globalToLocal(globalTopLeft);
      final anchorRect = localTopLeft & anchorBox.size;
      final measured = switch (widget.step) {
        4 => anchorRect.inflate(4),
        5 => anchorRect.inflate(4),
        _ => anchorRect.inflate(6),
      };
      final previous = _measuredSpotlight;
      if (previous != null &&
          (previous.left - measured.left).abs() < 0.5 &&
          (previous.top - measured.top).abs() < 0.5 &&
          (previous.width - measured.width).abs() < 0.5 &&
          (previous.height - measured.height).abs() < 0.5) {
        return;
      }
      setState(() {
        _measuredSpotlight = measured;
      });
    });
  }

  void _advance() {
    if (widget.step >= ChatSpotlightTour.stepCount - 1) {
      widget.onFinish();
      return;
    }
    widget.onNext();
  }

  @override
  Widget build(BuildContext context) {
    _scheduleAnchorMeasurement();
    final mediaQuery = MediaQuery.of(context);
    final palette = context.omniPalette;
    final isEnglish =
        Localizations.localeOf(context).languageCode.toLowerCase() == 'en';
    final step = widget.step.clamp(0, ChatSpotlightTour.stepCount - 1);
    final item = _items[step];
    final reduceMotion = mediaQuery.disableAnimations;

    return KeyedSubtree(
      key: const ValueKey('chat-spotlight-tour'),
      child: Material(
        key: _overlayKey,
        type: MaterialType.transparency,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final size = constraints.biggest;
            final spotlight =
                _measuredSpotlight ??
                _fallbackSpotlightRect(
                  step: step,
                  size: size,
                  topPadding: mediaQuery.padding.top,
                  bottomPadding: mediaQuery.padding.bottom,
                );
            final minCardTop = mediaQuery.padding.top + 76;
            final requestedCardTop = step < 4
                ? spotlight.bottom + 18
                : spotlight.top - 168;
            final rawMaxCardTop = size.height - mediaQuery.padding.bottom - 288;
            final maxCardTop = rawMaxCardTop < minCardTop
                ? minCardTop
                : rawMaxCardTop;
            final cardTop = requestedCardTop.clamp(minCardTop, maxCardTop);

            return GestureDetector(
              key: const ValueKey('chat-spotlight-gesture-layer'),
              behavior: HitTestBehavior.opaque,
              onTap: _advance,
              child: Semantics(
                scopesRoute: true,
                explicitChildNodes: true,
                label: isEnglish ? item.titleEn : item.titleZh,
                child: Stack(
                  children: [
                    Positioned.fill(
                      child: CustomPaint(
                        key: ValueKey<String>('chat-spotlight-hole-$step'),
                        painter: _SpotlightBarrierPainter(
                          spotlight: spotlight,
                          accent: palette.accentPrimary,
                        ),
                      ),
                    ),
                    Positioned(
                      left: 18,
                      right: 18,
                      top: cardTop.toDouble(),
                      child: Center(
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(maxWidth: 430),
                          child: AnimatedSwitcher(
                            duration: reduceMotion
                                ? Duration.zero
                                : const Duration(milliseconds: 220),
                            switchInCurve: Curves.easeOutCubic,
                            switchOutCurve: Curves.easeInCubic,
                            child: GestureDetector(
                              behavior: HitTestBehavior.opaque,
                              onTap: () {},
                              child: Container(
                                key: ValueKey<String>(
                                  'chat-spotlight-card-$step',
                                ),
                                padding: const EdgeInsets.fromLTRB(
                                  18,
                                  17,
                                  18,
                                  18,
                                ),
                                decoration: BoxDecoration(
                                  color: palette.surfacePrimary,
                                  borderRadius: BorderRadius.circular(20),
                                  border: Border.all(
                                    color: palette.borderStrong,
                                  ),
                                  boxShadow: [
                                    BoxShadow(
                                      color: Colors.black.withValues(
                                        alpha: 0.24,
                                      ),
                                      blurRadius: 28,
                                      offset: const Offset(0, 12),
                                    ),
                                  ],
                                ),
                                child: Row(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Container(
                                      width: 40,
                                      height: 40,
                                      decoration: BoxDecoration(
                                        color: palette.accentPrimary.withValues(
                                          alpha: 0.12,
                                        ),
                                        borderRadius: BorderRadius.circular(13),
                                      ),
                                      alignment: Alignment.center,
                                      child: Icon(
                                        item.icon,
                                        size: 20,
                                        color: palette.accentPrimary,
                                      ),
                                    ),
                                    const SizedBox(width: 13),
                                    Expanded(
                                      child: Column(
                                        crossAxisAlignment:
                                            CrossAxisAlignment.start,
                                        children: [
                                          Text(
                                            isEnglish
                                                ? item.titleEn
                                                : item.titleZh,
                                            style: Theme.of(context)
                                                .textTheme
                                                .titleMedium
                                                ?.copyWith(
                                                  color: palette.textPrimary,
                                                  fontWeight: FontWeight.w800,
                                                ),
                                          ),
                                          const SizedBox(height: 7),
                                          Text(
                                            isEnglish
                                                ? item.descriptionEn
                                                : item.descriptionZh,
                                            style: Theme.of(context)
                                                .textTheme
                                                .bodySmall
                                                ?.copyWith(
                                                  color: palette.textSecondary,
                                                  height: 1.55,
                                                ),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Rect _fallbackSpotlightRect({
    required int step,
    required Size size,
    required double topPadding,
    required double bottomPadding,
  }) {
    final top = topPadding + 7;
    final width = size.width;
    return switch (step) {
      0 => Rect.fromLTWH(8, top, 54, 52),
      1 => Rect.fromLTWH(70, top, (width - 140).clamp(150, 320), 52),
      2 => Rect.fromLTWH(width - 112, top, 104, 52),
      3 => Rect.fromCenter(
        center: Offset(width / 2, top + 82),
        width: (width * 0.54).clamp(176, 300),
        height: 48,
      ),
      4 => Rect.fromLTWH(
        size.width - 118,
        size.height - bottomPadding - 187,
        40,
        40,
      ),
      _ => Rect.fromLTWH(12, size.height - bottomPadding - 132, width - 24, 64),
    };
  }
}

class _SpotlightItem {
  const _SpotlightItem({
    required this.icon,
    required this.titleZh,
    required this.titleEn,
    required this.descriptionZh,
    required this.descriptionEn,
  });

  final IconData icon;
  final String titleZh;
  final String titleEn;
  final String descriptionZh;
  final String descriptionEn;
}

const List<_SpotlightItem> _items = <_SpotlightItem>[
  _SpotlightItem(
    icon: LucideIcons.panelLeft,
    titleZh: '菜单与会话',
    titleEn: 'Menu and conversations',
    descriptionZh: '从左上角打开侧栏，可新建对话、切换历史会话，并进入各项设置。',
    descriptionEn:
        'Open the sidebar to start chats, switch conversation history, and reach settings.',
  ),
  _SpotlightItem(
    icon: LucideIcons.workflow,
    titleZh: '选择工作模式',
    titleEn: 'Choose a work mode',
    descriptionZh: '顶部模式岛可在小万、编程 Agent 与纯聊天之间切换，当前选择会直接影响执行方式。',
    descriptionEn:
        'Use the top mode island to switch between OmniAi, coding agents, and pure chat.',
  ),
  _SpotlightItem(
    icon: LucideIcons.pawPrint,
    titleZh: '宠物与工作区',
    titleEn: 'Pet and workspace',
    descriptionZh: '右上角可以显示桌面宠物；在平板或宽屏设备上，还能展开工作区文件面板。',
    descriptionEn:
        'Show the desktop pet, or open the workspace pane on tablets and wider screens.',
  ),
  _SpotlightItem(
    icon: LucideIcons.squareTerminal,
    titleZh: '环境、终端与浏览器',
    titleEn: 'Environment, terminal, and browser',
    descriptionZh: '工具岛用于管理环境变量、打开本地终端，以及查看 Agent 正在使用的浏览器会话。',
    descriptionEn:
        'The tool island manages environment variables, the local terminal, and the agent browser session.',
  ),
  _SpotlightItem(
    icon: LucideIcons.circleGauge,
    titleZh: '模型与上下文',
    titleEn: 'Model and context',
    descriptionZh: '从输入区切换当前模型；上下文环显示已使用容量，长按可调整对话阈值。',
    descriptionEn:
        'Choose the current model and use the context ring to monitor or adjust chat capacity.',
  ),
  _SpotlightItem(
    icon: LucideIcons.paperclip,
    titleZh: '附件、命令与发送',
    titleEn: 'Attachments, commands, and send',
    descriptionZh: '“+”可添加图片或文件，输入“/”打开命令面板；执行期间发送按钮会变为停止按钮。',
    descriptionEn:
        'Use plus for files, slash for commands, and the send button to submit or stop a run.',
  ),
];

class _SpotlightBarrierPainter extends CustomPainter {
  const _SpotlightBarrierPainter({
    required this.spotlight,
    required this.accent,
  });

  final Rect spotlight;
  final Color accent;

  @override
  void paint(Canvas canvas, Size size) {
    final bounds = Offset.zero & size;
    final hole = RRect.fromRectAndRadius(spotlight, const Radius.circular(18));

    canvas.saveLayer(bounds, Paint());
    canvas.drawRect(
      bounds,
      Paint()..color = Colors.black.withValues(alpha: 0.68),
    );
    canvas.drawRRect(
      hole,
      Paint()
        ..blendMode = BlendMode.clear
        ..color = Colors.transparent,
    );
    canvas.restore();

    canvas.drawRRect(
      hole.inflate(2),
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 3
        ..color = accent,
    );
  }

  @override
  bool shouldRepaint(covariant _SpotlightBarrierPainter oldDelegate) {
    return oldDelegate.spotlight != spotlight || oldDelegate.accent != accent;
  }
}
