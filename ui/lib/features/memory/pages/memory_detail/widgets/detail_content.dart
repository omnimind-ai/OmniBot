import 'package:flutter/material.dart';
import 'package:ui/utils/ui.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:intl/intl.dart';
import 'package:ui/features/memory/pages/memory_center/widgets/tag_chip.dart';
import 'package:ui/features/memory/pages/memory_center/widgets/tag_section.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/widgets/ai_generated_badge.dart';

/// 详情内容区域
class DetailContent extends StatelessWidget {
  final String title;
  final int timestamp;
  final String content;
  final String? appName;
  final ImageProvider? appIconProvider;
  final String? appSvgPath;
  final List<AppTag>? tags;

  const DetailContent({
    Key? key,
    required this.title,
    required this.timestamp,
    required this.content,
    this.appName,
    this.appIconProvider,
    this.appSvgPath,
    this.tags,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 25),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 标题
          Text(
            title,
            style: const TextStyle(
              color: AppColors.text,
              fontSize: AppTextStyles.fontSizeH2,
              fontWeight: AppTextStyles.fontWeightMedium,
              height: AppTextStyles.lineHeightH1,
              letterSpacing: AppTextStyles.letterSpacingWide,
            ),
          ),

          const SizedBox(height: 10),

          // 应用标签和时间
          Row(
            children: [
              // 左侧：渲染tags或app信息 + 时间
              Expanded(
                child: Row(
                  children: [
                    // 渲染tags中的数据
                    if (tags != null && tags!.isNotEmpty) ...[
                      Wrap(
                        spacing: 6,
                        runSpacing: 6,
                        children: tags!.map((tag) {
                          return TagChip(
                            title: tag.label,
                            iconPath: tag.icon ?? Icons.label,
                            svgPath: tag.svgPath,
                            appIconProvider: tag.appIconProvider,
                          );
                        }).toList(),
                      ),
                    ] else if (appName != null && appName!.isNotEmpty) ...[
                      if (appSvgPath != null) ...[
                        SvgPicture.asset(appSvgPath!, width: 14, height: 14),
                        const SizedBox(width: 4),
                      ] else if (appIconProvider != null) ...[
                        Image(image: appIconProvider!, width: 14, height: 14),
                        const SizedBox(width: 4),
                      ],
                      Text(
                        appName!,
                        style: TextStyle(
                          fontSize: 10,
                          fontWeight: AppTextStyles.fontWeightRegular,
                          letterSpacing: 0.39,
                          height: 1.6,
                          color: AppColors.text,
                        ),
                      ),
                    ] else ...[
                      Icon(Icons.apps, size: 14, color: Color(0xFF666666)),
                      const SizedBox(width: 2),
                      Text(
                        '未知应用',
                        style: TextStyle(
                          fontSize: 10,
                          fontWeight: AppTextStyles.fontWeightRegular,
                          letterSpacing: 0.39,
                          height: 1.6,
                          color: AppColors.text,
                        ),
                      ),
                    ],
                    // 时间文案
                    const SizedBox(width: 16),
                    Text(
                      timestamp > 0
                          ? DateFormat('M月 dd日 HH:mm').format(
                              DateTime.fromMillisecondsSinceEpoch(
                                timestamp,
                              ).toLocal(),
                            )
                          : '未知时间',
                      style: TextStyle(
                        fontSize: AppTextStyles.fontSizeSmall,
                        fontWeight: AppTextStyles.fontWeightRegular,
                        letterSpacing: AppTextStyles.letterSpacingNormal,
                        height: AppTextStyles.lineHeightH2,
                        color: AppColors.text50,
                      ),
                    ),
                  ],
                ),
              ),
              // 右侧：AI生成标识
              const AiGeneratedBadge(),
            ],
          ),

          const SizedBox(height: 16),

          // 正文内容
          Expanded(
            child: SingleChildScrollView(
              padding: edgeToEdgeScrollPadding(
                context,
                const EdgeInsets.only(bottom: 25),
              ),
              child: Padding(
                padding: EdgeInsets.zero,
                child: MarkdownBody(
                  data: content,
                  styleSheet: MarkdownStyleSheet(
                    p: TextStyle(
                      fontSize: AppTextStyles.fontSizeSmall,
                      fontWeight: AppTextStyles.fontWeightRegular,
                      height: AppTextStyles.lineHeightH2,
                      letterSpacing: AppTextStyles.letterSpacingWide,
                      color: AppColors.text70,
                    ),
                    h1: TextStyle(
                      fontSize: AppTextStyles.fontSizeH1,
                      fontWeight: AppTextStyles.fontWeightMedium,
                      height: AppTextStyles.lineHeightH1,
                      letterSpacing: AppTextStyles.letterSpacingWide,
                      color: AppColors.text,
                    ),
                    h2: TextStyle(
                      fontSize: AppTextStyles.fontSizeH2,
                      fontWeight: AppTextStyles.fontWeightMedium,
                      height: AppTextStyles.lineHeightH1,
                      letterSpacing: AppTextStyles.letterSpacingWide,
                      color: AppColors.text,
                    ),
                    h3: TextStyle(
                      fontSize: AppTextStyles.fontSizeH3,
                      fontWeight: AppTextStyles.fontWeightMedium,
                      height: AppTextStyles.lineHeightH2,
                      letterSpacing: AppTextStyles.letterSpacingWide,
                      color: AppColors.text,
                    ),
                    strong: TextStyle(
                      fontSize: AppTextStyles.fontSizeSmall,
                      fontWeight: AppTextStyles.fontWeightMedium,
                      height: AppTextStyles.lineHeightH2,
                      letterSpacing: AppTextStyles.letterSpacingWide,
                      color: AppColors.text70,
                    ),
                    em: TextStyle(
                      fontSize: AppTextStyles.fontSizeSmall,
                      fontWeight: AppTextStyles.fontWeightRegular,
                      height: AppTextStyles.lineHeightH2,
                      letterSpacing: AppTextStyles.letterSpacingWide,
                      color: AppColors.text70,
                      fontStyle: FontStyle.italic,
                    ),
                    code: TextStyle(
                      fontSize: AppTextStyles.fontSizeSmall,
                      fontWeight: AppTextStyles.fontWeightRegular,
                      height: AppTextStyles.lineHeightH2,
                      letterSpacing: AppTextStyles.letterSpacingNormal,
                      color: AppColors.text70,
                      fontFamily: 'monospace',
                      backgroundColor: AppColors.text10,
                    ),
                    codeblockDecoration: BoxDecoration(
                      color: AppColors.text10,
                      borderRadius: BorderRadius.circular(4),
                    ),
                    blockquote: TextStyle(
                      fontSize: AppTextStyles.fontSizeSmall,
                      fontWeight: AppTextStyles.fontWeightRegular,
                      height: AppTextStyles.lineHeightH2,
                      letterSpacing: AppTextStyles.letterSpacingWide,
                      color: AppColors.text70,
                      fontStyle: FontStyle.italic,
                    ),
                    blockquoteDecoration: BoxDecoration(
                      color: AppColors.text10,
                      border: Border(
                        left: BorderSide(color: AppColors.text20, width: 4),
                      ),
                    ),
                    listBullet: TextStyle(
                      fontSize: AppTextStyles.fontSizeSmall,
                      fontWeight: AppTextStyles.fontWeightRegular,
                      height: AppTextStyles.lineHeightH2,
                      letterSpacing: AppTextStyles.letterSpacingWide,
                      color: AppColors.text70,
                    ),
                    listBulletPadding: const EdgeInsets.only(right: 8),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
