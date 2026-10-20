import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/theme/theme_context.dart';

class OmniGlassPanel extends StatelessWidget {
  const OmniGlassPanel({
    super.key,
    required this.child,
    this.borderRadius = const BorderRadius.all(Radius.circular(24)),
    this.padding = EdgeInsets.zero,
    this.width,
    this.height,
    this.forceDark = false,
    this.omitTopBorder = false,
    this.showTopHighlight = true,
    this.surfaceColor,
  });

  final Widget child;
  final BorderRadius borderRadius;
  final EdgeInsetsGeometry padding;
  final double? width;
  final double? height;
  final bool forceDark;

  /// 不透明磨砂底色。传入后面板不再做背后模糊 + 渐变 tint,而是铺一层
  /// 这个实心色——用于"胶囊"类拼接菜单:触发按钮在 app bar 上、面板在
  /// 内容上,两者背后不同,半透明玻璃会把这种差异透出来读作"上下不一致";
  /// 实心底让上下两截合成结果完全相同,接缝也因此不再有渐变断层。
  /// 默认 null 即保持原来的半透明玻璃(不影响其它调用点)。
  final Color? surfaceColor;

  /// 是否省略**顶边**的 1px 边线（默认 false 即画完整四边）。
  /// 当 popup 紧贴在另一块玻璃下方（如下拉模式列表贴在触发按钮下边）需要拼成
  /// 一个完整胶囊时设为 true,避免顶边那条 1px 线在拼接处形成"双线"。
  final bool omitTopBorder;

  /// 是否绘制顶部 1px 的高光渐变（默认 true）。拼接到上方玻璃时也应关掉,
  /// 否则在接缝处会出现一截多余的亮线。
  final bool showTopHighlight;

  @override
  Widget build(BuildContext context) {
    final palette = JackeryCesConfig.enabled
        ? OmniThemePalette.jackeryCes
        : forceDark
        ? OmniThemePalette.dark
        : context.omniPalette;
    final isDark =
        !JackeryCesConfig.enabled && (forceDark || context.isDarkTheme);
    final topTint = isDark
        ? palette.surfacePrimary.withValues(alpha: 0.26)
        : Colors.white.withValues(alpha: 0.40);
    final bottomTint = isDark
        ? palette.surfaceSecondary.withValues(alpha: 0.12)
        : Colors.white.withValues(alpha: 0.18);
    // 深色模式下故意把整圈边线压得极弱(0.08)——之前 0.22 在暗底上一圈白线
    // 看起来就是"PPT 描边",完全没有玻璃感。真玻璃在暗环境里是"边线几乎消
    // 失、顶部高光独自承担定义边界",所以这里把均匀边线退到肉眼几乎觉察不到,
    // 让 [highlightColor] 的顶部 1px 渐变去做"光打在玻璃顶上"的活儿。
    final borderColor = JackeryCesConfig.enabled
        ? palette.borderSubtle
        : isDark
        ? Colors.white.withValues(alpha: 0.08)
        : Colors.white.withValues(alpha: 0.82);
    final highlightColor = isDark
        ? Colors.white.withValues(alpha: 0.45)
        : Colors.white.withValues(alpha: 0.86);
    final accentGlow = palette.accentPrimary.withValues(
      alpha: isDark ? 0.10 : 0.08,
    );

    final borderSide = BorderSide(color: borderColor);
    final BoxBorder border = omitTopBorder
        ? Border(left: borderSide, right: borderSide, bottom: borderSide)
        : Border.all(color: borderColor);

    // 磨砂模式:实心底 + 去掉渐变 tint(渐变在拼接胶囊里会让接缝两截深浅
    // 断层),背后模糊也省掉——底既然不透明,模糊看不见还白费 GPU。
    final bool frosted = JackeryCesConfig.enabled || surfaceColor != null;

    final Widget decorated = DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: borderRadius,
        border: border,
        color: JackeryCesConfig.enabled
            ? palette.pageBackground
            : frosted
            ? surfaceColor
            : null,
        gradient: frosted
            ? null
            : LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [topTint, bottomTint],
              ),
      ),
      child: Stack(
        children: [
          if (showTopHighlight && !JackeryCesConfig.enabled)
            // 顶部高光横向覆盖更广(8 vs 之前 18),让"光面"延伸出去,
            // 不再只是中段一小截亮线——配合深色模式下基本消失的边框,
            // 视觉上更接近真实玻璃顶边的反光。
            Positioned(
              left: 8,
              right: 8,
              top: 0,
              child: Container(
                height: 1,
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: [
                      Colors.transparent,
                      highlightColor,
                      Colors.transparent,
                    ],
                  ),
                ),
              ),
            ),
          Padding(padding: padding, child: child),
        ],
      ),
    );

    return Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
        borderRadius: borderRadius,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: isDark ? 0.34 : 0.12),
            blurRadius: isDark ? 42 : 30,
            offset: const Offset(0, 18),
          ),
          if (!JackeryCesConfig.enabled)
            BoxShadow(
              color: accentGlow,
              blurRadius: 28,
              offset: const Offset(0, -8),
            ),
        ],
      ),
      child: ClipRRect(
        borderRadius: borderRadius,
        child: frosted
            ? decorated
            : BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 10, sigmaY: 10),
                child: decorated,
              ),
      ),
    );
  }
}
