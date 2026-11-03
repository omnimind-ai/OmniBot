import 'dart:math' as math;

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

  static const int stepCount = 5;
  static const int modelStep = 3;

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
          !overlayBox.hasSize ||
          anchorBox.size.isEmpty ||
          overlayBox.size.isEmpty) {
        return;
      }

      final globalTopLeft = anchorBox.localToGlobal(Offset.zero);
      final localTopLeft = overlayBox.globalToLocal(globalTopLeft);
      final anchorRect = localTopLeft & anchorBox.size;
      final measured = switch (widget.step) {
        ChatSpotlightTour.modelStep => anchorRect.inflate(4),
        4 => anchorRect.inflate(4),
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
            // Measure the real control. A hidden or not-yet-laid-out control
            // must not spotlight a guessed location from an older chat layout.
            final spotlight = _measuredSpotlight;
            final safeBounds = Rect.fromLTRB(
              18,
              mediaQuery.padding.top + 18,
              size.width - 18,
              size.height -
                  math.max(
                    mediaQuery.padding.bottom,
                    mediaQuery.viewInsets.bottom,
                  ) -
                  18,
            );

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
                    Positioned.fill(
                      child: CustomSingleChildLayout(
                        delegate: _TourCardLayout(
                          spotlight: spotlight,
                          safeBounds: safeBounds,
                          preferAbove: step >= ChatSpotlightTour.modelStep,
                        ),
                        child: AnimatedSwitcher(
                          duration: reduceMotion
                              ? Duration.zero
                              : const Duration(milliseconds: 220),
                          switchInCurve: Curves.easeOutCubic,
                          switchOutCurve: Curves.easeInCubic,
                          child: GestureDetector(
                            key: ValueKey<int>(step),
                            behavior: HitTestBehavior.opaque,
                            onTap: () {},
                            child: SingleChildScrollView(
                              key: ValueKey<String>(
                                'chat-spotlight-viewport-$step',
                              ),
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
                                          const SizedBox(height: 10),
                                          Text(
                                            '${step + 1} / ${ChatSpotlightTour.stepCount}  ·  '
                                            '${isEnglish ? (step == ChatSpotlightTour.stepCount - 1 ? 'Tap outside to finish' : 'Tap outside to continue') : (step == ChatSpotlightTour.stepCount - 1 ? '点击空白处完成' : '点击空白处继续')}',
                                            style: Theme.of(context)
                                                .textTheme
                                                .labelSmall
                                                ?.copyWith(
                                                  color: palette.textTertiary,
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
}

/// Positions the measured card outside its target and above the keyboard.
/// The child can scroll when large text leaves less room on a small screen.
class _TourCardLayout extends SingleChildLayoutDelegate {
  const _TourCardLayout({
    required this.spotlight,
    required this.safeBounds,
    required this.preferAbove,
  });

  final Rect? spotlight;
  final Rect safeBounds;
  final bool preferAbove;
  static const double _gap = 18;

  double get _above => spotlight == null
      ? safeBounds.height
      : (spotlight!.top - _gap - safeBounds.top).clamp(0, safeBounds.height);
  double get _below => spotlight == null
      ? safeBounds.height
      : (safeBounds.bottom - spotlight!.bottom - _gap).clamp(
          0,
          safeBounds.height,
        );

  @override
  BoxConstraints getConstraintsForChild(BoxConstraints constraints) {
    final width = math.min(430.0, math.max(0.0, safeBounds.width));
    return BoxConstraints(
      minWidth: width,
      maxWidth: width,
      maxHeight: math.max(0.0, math.max(_above, _below)),
    );
  }

  @override
  Offset getPositionForChild(Size size, Size childSize) {
    final target = spotlight;
    final double top;
    if (target == null) {
      top = safeBounds.center.dy - childSize.height / 2;
    } else {
      final placeAbove = preferAbove
          ? _above >= childSize.height || _below < childSize.height
          : _below < childSize.height && _above >= childSize.height;
      top = placeAbove
          ? target.top - _gap - childSize.height
          : target.bottom + _gap;
    }
    return Offset(
      (size.width - childSize.width) / 2,
      top.clamp(
        safeBounds.top,
        math.max(safeBounds.top, safeBounds.bottom - childSize.height),
      ),
    );
  }

  @override
  bool shouldRelayout(covariant _TourCardLayout oldDelegate) =>
      spotlight != oldDelegate.spotlight ||
      safeBounds != oldDelegate.safeBounds ||
      preferAbove != oldDelegate.preferAbove;
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
    icon: LucideIcons.chevronLeft,
    titleZh: '返回会话列表',
    titleEn: 'Back to conversations',
    descriptionZh: '左上角的返回箭头带你回到首页。在那里可以查看历史会话、新建对话，并从菜单进入设置。',
    descriptionEn:
        'The top-left arrow returns home, where you can browse previous conversations, start a chat, and open settings from the menu.',
  ),
  _SpotlightItem(
    icon: LucideIcons.messageCircle,
    titleZh: '对话标题与模式',
    titleEn: 'Conversation title and mode',
    descriptionZh: '顶部显示当前对话标题，标题左侧的图标表示纯聊天或当前 Agent。新建对话时可以在输入框上方选择模式。',
    descriptionEn:
        'The header shows the conversation title. Its icon identifies pure chat or the current Agent. Choose a mode above the composer when starting a new conversation.',
  ),
  _SpotlightItem(
    icon: LucideIcons.ellipsis,
    titleZh: '对话选项',
    titleEn: 'Conversation options',
    descriptionZh:
        '右上角“…”集中提供工作区文件、上下文用量与阈值、宠物等入口。终端、环境变量和浏览器会按当前模式与可用状态显示；向左滑动聊天页也能查看工作区。',
    descriptionEn:
        'The top-right menu contains workspace files, context usage and limits, and the pet. Terminal, environment, and browser entries depend on the current mode and availability. Swipe left to view the workspace.',
  ),
  _SpotlightItem(
    icon: LucideIcons.slidersHorizontal,
    titleZh: '模型与参数',
    titleEn: 'Model and settings',
    descriptionZh: '点按输入框后，输入区会展开。底部右侧的模型名称可打开模型与参数菜单，查看或调整当前模式支持的选项。',
    descriptionEn:
        'Tap the composer to expand it. The model name near its bottom-right opens model and parameter options supported by the current mode.',
  ),
  _SpotlightItem(
    icon: LucideIcons.paperclip,
    titleZh: '输入、附件与发送',
    titleEn: 'Messages, attachments, and send',
    descriptionZh:
        '左下角“+”可添加图片、文件，并在支持时选择权限模式。输入“/”打开命令面板；右下角箭头发送消息，执行中会变为停止按钮。',
    descriptionEn:
        'The bottom-left plus adds photos and files, and offers permissions when supported. Type slash for commands. The bottom-right arrow sends your message and becomes a stop button during a run.',
  ),
];

class _SpotlightBarrierPainter extends CustomPainter {
  const _SpotlightBarrierPainter({
    required this.spotlight,
    required this.accent,
  });

  final Rect? spotlight;
  final Color accent;

  @override
  void paint(Canvas canvas, Size size) {
    final bounds = Offset.zero & size;
    final target = spotlight;
    if (target == null) {
      canvas.drawRect(
        bounds,
        Paint()..color = Colors.black.withValues(alpha: 0.68),
      );
      return;
    }
    final hole = RRect.fromRectAndRadius(target, const Radius.circular(18));

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
