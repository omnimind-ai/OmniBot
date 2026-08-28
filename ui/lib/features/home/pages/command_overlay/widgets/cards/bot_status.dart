import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/agent_avatar.dart';

class BotStatus extends StatelessWidget {
  final BotStatusType status;
  final String? hintText;
  final String? costTime;
  final TextStyle? textStyle;
  final bool showAvatar;
  final bool shimmerText;

  const BotStatus({
    super.key,
    required this.status,
    this.hintText,
    this.costTime,
    this.textStyle,
    this.showAvatar = true,
    this.shimmerText = false,
  });

  @override
  Widget build(BuildContext context) {
    final currentCostTime = costTime;
    final normalizedCostTime = currentCostTime == null
        ? ''
        : LegacyTextLocalizer.isEnglish
        ? currentCostTime
        : currentCostTime.replaceAll(' ', '');
    if (status == BotStatusType.completed && LegacyTextLocalizer.isEnglish) {
      final completedEnglishText = normalizedCostTime.isEmpty
          ? 'Thought complete'
          : 'Thought for $normalizedCostTime';
      return _buildStatusRow(
        context,
        customIcon: showAvatar
            ? const AgentAvatarButton(size: 30, showBorder: false)
            : null,
        text: completedEnglishText,
        shimmerText: false,
      );
    }

    switch (status) {
      case BotStatusType.completed:
        return _buildStatusRow(
          context,
          customIcon: showAvatar
              ? const AgentAvatarButton(size: 30, showBorder: false)
              : null,
          text: LegacyTextLocalizer.localize('思考完成'),
          timeDesc: costTime == null
              ? null
              : LegacyTextLocalizer.localize('用时'),
          costTime: costTime,
          shimmerText: false,
        );
      case BotStatusType.hint:
        return _buildStatusRow(
          context,
          customIcon: showAvatar
              ? const AgentAvatarButton(size: 30, showBorder: false)
              : null,
          text: LegacyTextLocalizer.localize(hintText ?? '正在思考'),
          timeDesc: costTime != null
              ? LegacyTextLocalizer.localize('用时')
              : null,
          costTime: costTime,
          shimmerText: shimmerText,
        );
    }
  }

  Widget _buildStatusRow(
    BuildContext context, {
    IconData? icon,
    String? svgPath,
    Widget? customIcon,
    required String text,
    String? timeDesc,
    String? costTime,
    String? timeDescSuffix = '',
    bool shimmerText = false,
  }) {
    final palette = context.omniPalette;
    final defaultTextColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0x80353E53);
    final resolvedTextStyle =
        textStyle ??
        TextStyle(
          color: defaultTextColor,
          fontSize: 12,
          fontFamily: 'PingFang SC',
          fontWeight: FontWeight.w400,
          height: 1.50,
          letterSpacing: 0.33,
        );
    Widget? iconWidget;
    if (customIcon != null) {
      iconWidget = customIcon;
    } else if (svgPath != null) {
      iconWidget = SvgPicture.asset(
        svgPath,
        width: 16,
        height: 16,
        colorFilter: context.isDarkTheme
            ? ColorFilter.mode(defaultTextColor, BlendMode.srcIn)
            : null,
      );
    } else if (icon != null) {
      iconWidget = Icon(
        icon,
        size: 16,
        color: resolvedTextStyle.color ?? defaultTextColor,
      );
    } else {
      iconWidget = null;
    }

    final normalizedCostTime = costTime == null
        ? ''
        : LegacyTextLocalizer.isEnglish
        ? costTime
        : costTime.replaceAll(' ', '');
    final timeJoiner =
        timeDesc != null &&
            normalizedCostTime.isNotEmpty &&
            LegacyTextLocalizer.isEnglish
        ? ' '
        : '';
    final timeText = timeDesc != null
        ? '($timeDesc$timeJoiner$normalizedCostTime)$timeDescSuffix'
        : '';
    final textGroup = Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(text, style: resolvedTextStyle),
        if (timeText.isNotEmpty) ...[
          const SizedBox(width: 4.0),
          Text(timeText, style: resolvedTextStyle),
        ],
      ],
    );
    final statusText = shimmerText
        ? ShimmeringStatusText(
            baseColor: resolvedTextStyle.color ?? defaultTextColor,
            child: textGroup,
          )
        : textGroup;

    return Container(
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(8.0)),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (iconWidget != null) ...[iconWidget, const SizedBox(width: 8.0)],
          statusText,
        ],
      ),
    );
  }
}

enum BotStatusType { completed, hint }

/// Shared restrained shimmer used by transient chat status labels.
///
/// The animation automatically falls back to the unmodified child when the
/// platform requests reduced motion.
class ShimmeringStatusText extends StatefulWidget {
  const ShimmeringStatusText({
    super.key,
    required this.child,
    required this.baseColor,
  });

  final Widget child;
  final Color baseColor;

  @override
  State<ShimmeringStatusText> createState() => _ShimmeringStatusTextState();
}

class _ShimmeringStatusTextState extends State<ShimmeringStatusText>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1800),
  )..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (MediaQuery.disableAnimationsOf(context)) {
      return widget.child;
    }
    final highlightColor = context.isDarkTheme
        ? Colors.white.withValues(alpha: 0.92)
        : Colors.white.withValues(alpha: 0.96);
    return AnimatedBuilder(
      animation: _controller,
      child: widget.child,
      builder: (context, child) {
        return ShaderMask(
          blendMode: BlendMode.srcIn,
          shaderCallback: (bounds) {
            final textWidth = bounds.width <= 0 ? 1.0 : bounds.width;
            final shimmerWidth = (textWidth * 0.72).clamp(52.0, 180.0);
            final travelDistance = textWidth + shimmerWidth;
            final shimmerLeft =
                bounds.left - shimmerWidth + travelDistance * _controller.value;
            return LinearGradient(
              begin: Alignment.centerLeft,
              end: Alignment.centerRight,
              colors: [widget.baseColor, highlightColor, widget.baseColor],
              stops: const [0.08, 0.5, 0.92],
            ).createShader(
              Rect.fromLTWH(
                shimmerLeft,
                bounds.top,
                shimmerWidth,
                bounds.height,
              ),
            );
          },
          child: child,
        );
      },
    );
  }
}
