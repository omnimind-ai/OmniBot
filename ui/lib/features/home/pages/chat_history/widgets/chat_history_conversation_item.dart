import 'package:flutter/material.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/features/home/widgets/conversation_slidable.dart';
import 'package:ui/features/home/widgets/conversation_mode_badge.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';

class ChatHistoryConversationItem extends StatelessWidget {
  const ChatHistoryConversationItem({
    super.key,
    required this.conversation,
    required this.onTap,
    required this.actions,
    required this.onDelete,
    this.isBusy = false,
    this.showLeadingIcon = true,
    this.compact = false,
  });

  final ConversationModel conversation;
  final VoidCallback onTap;
  final List<ConversationSlideAction> actions;
  final VoidCallback onDelete;
  final bool isBusy;
  final bool showLeadingIcon;
  final bool compact;

  static const String slidableGroupTag = 'chat-history';

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final borderRadius = BorderRadius.circular(compact ? 7 : 8);
    final contentPadding = EdgeInsets.symmetric(
      horizontal: compact ? 14 : 16,
      vertical: compact ? 12 : 16,
    );
    final metaText = conversation.messageCount > 0
        ? LegacyTextLocalizer.localize(
            '${conversation.timeDisplay} · ${conversation.messageCount} 条消息',
          )
        : conversation.timeDisplay;
    return ConversationSlidable(
      itemKey: conversation.threadKey,
      groupTag: slidableGroupTag,
      isBusy: isBusy,
      actions: actions,
      onDismissed: onDelete,
      margin: EdgeInsets.only(bottom: compact ? 8 : 12),
      child: Material(
        color: Colors.transparent,
        borderRadius: borderRadius,
        child: InkWell(
          onTap: onTap,
          borderRadius: borderRadius,
          child: Container(
            padding: contentPadding,
            decoration: BoxDecoration(
              color: context.isDarkTheme
                  ? palette.surfacePrimary
                  : Colors.white,
              borderRadius: borderRadius,
              border: context.isDarkTheme
                  ? Border.all(color: palette.borderSubtle)
                  : null,
              boxShadow: context.isDarkTheme
                  ? [
                      BoxShadow(
                        color: palette.shadowColor.withValues(alpha: 0.18),
                        blurRadius: 14,
                        offset: const Offset(0, 6),
                      ),
                    ]
                  : [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: 0.05),
                        blurRadius: compact ? 8 : 10,
                        offset: Offset(0, compact ? 1.5 : 2),
                      ),
                    ],
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                if (showLeadingIcon) ...[
                  Container(
                    width: compact ? 36 : 40,
                    height: compact ? 36 : 40,
                    decoration: BoxDecoration(
                      color: JackeryCesConfig.enabled
                          ? JackeryCesConfig.accent
                          : null,
                      gradient: JackeryCesConfig.enabled
                          ? null
                          : LinearGradient(
                              colors: context.isDarkTheme
                                  ? <Color>[
                                      Color.lerp(
                                        palette.surfaceElevated,
                                        palette.accentPrimary,
                                        0.18,
                                      )!,
                                      Color.lerp(
                                        palette.surfaceSecondary,
                                        palette.accentPrimary,
                                        0.3,
                                      )!,
                                    ]
                                  : const <Color>[
                                      Color(0xFF1930D9),
                                      Color(0xFF2CA5F0),
                                    ],
                            ),
                      borderRadius: BorderRadius.all(
                        Radius.circular(compact ? 7 : 8),
                      ),
                      border: context.isDarkTheme
                          ? Border.all(color: palette.borderSubtle)
                          : null,
                    ),
                    child: Icon(
                      Icons.chat_bubble_outline,
                      color: context.isDarkTheme
                          ? palette.textPrimary
                          : Colors.white,
                      size: compact ? 18 : 20,
                    ),
                  ),
                  SizedBox(width: compact ? 10 : 12),
                ],
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          Expanded(
                            child: Text(
                              conversation.title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize: compact ? 15 : 16,
                                fontWeight: FontWeight.w500,
                                color: context.isDarkTheme
                                    ? palette.textPrimary
                                    : AppColors.text,
                                height: compact ? 1.2 : 1.25,
                              ),
                            ),
                          ),
                          if (!JackeryCesConfig.enabled) ...[
                            SizedBox(width: compact ? 6 : 8),
                            ConversationModeBadge(
                              mode: conversation.mode,
                              compact: true,
                            ),
                          ],
                        ],
                      ),
                      if ((conversation.summary ?? '').isNotEmpty) ...[
                        SizedBox(height: compact ? 2 : 4),
                        Text(
                          conversation.summary!,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: compact ? 13 : 14,
                            color: context.isDarkTheme
                                ? palette.textSecondary
                                : AppColors.text.withValues(alpha: 0.6),
                            height: compact ? 1.2 : 1.3,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                SizedBox(width: compact ? 10 : 12),
                Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(
                      compact ? metaText : conversation.timeDisplay,
                      textAlign: TextAlign.right,
                      style: TextStyle(
                        fontSize: compact ? 11 : 12,
                        color: context.isDarkTheme
                            ? palette.textTertiary
                            : AppColors.text.withValues(alpha: 0.4),
                        height: compact ? 1.15 : 1.2,
                      ),
                    ),
                    if (!compact && conversation.messageCount > 0) ...[
                      const SizedBox(height: 4),
                      Text(
                        LegacyTextLocalizer.localize(
                          '${conversation.messageCount} 条消息',
                        ),
                        style: TextStyle(
                          fontSize: 12,
                          color: context.isDarkTheme
                              ? palette.textTertiary
                              : AppColors.text.withValues(alpha: 0.4),
                        ),
                      ),
                    ],
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
