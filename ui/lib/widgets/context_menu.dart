import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/utils/popup_menu_anchor_position.dart';

enum RecordMenuAction { edit, delete }

Future<RecordMenuAction?> showRecordContextMenu({
  required BuildContext context,
  required Offset position,
  double maxWidth = 144,
  String? editLabel,
  String? deleteLabel,
  String editIconAsset = 'assets/common/edit.svg',
  String deleteIconAsset = 'assets/common/close.svg',
  Color deleteColor = AppColors.alertRed,
  bool showEdit = true,
  bool showDelete = true,
}) {
  final isEnglish = Localizations.localeOf(context).languageCode == 'en';
  final resolvedEditLabel = editLabel ?? (isEnglish ? 'Edit' : '编辑');
  final resolvedDeleteLabel = deleteLabel ?? (isEnglish ? 'Delete record' : '删除记录');
  final List<PopupMenuEntry<RecordMenuAction>> items = [];

  if (showEdit) {
    items.add(
      PopupMenuItem<RecordMenuAction>(
        value: RecordMenuAction.edit,
        padding: EdgeInsets.zero,
        child: Padding(
          padding: EdgeInsets.fromLTRB(18, items.isEmpty && !showDelete ? 16 : 16, 0, items.isEmpty && !showDelete ? 16 : 10),
          child: Row(
            children: [
              SvgPicture.asset(
                editIconAsset,
                width: 22.5,
                height: 18.28,
                colorFilter: ColorFilter.mode(Colors.black.withOpacity(0.70), BlendMode.srcIn),
                errorBuilder: (ctx, err, stack) {
                  return const Icon(Icons.edit_outlined, size: 18, color: Colors.black);
                },
              ),
              const SizedBox(width: 20),
              Text(
                resolvedEditLabel,
                style: const TextStyle(
                  color: Colors.black,
                  fontSize: AppTextStyles.fontSizeMain,
                  fontWeight: AppTextStyles.fontWeightRegular,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  if (showEdit && showDelete) {
    items.add(const PopupMenuDivider(height: 1, color: AppColors.text10));
  }

  if (showDelete) {
    items.add(
      PopupMenuItem<RecordMenuAction>(
        value: RecordMenuAction.delete,
        padding: EdgeInsets.zero,
        child: Padding(
          padding: EdgeInsets.fromLTRB(18, showEdit ? 16 : 16, 0, !showEdit ? 16 : 10),
          child: Row(
            children: [
              SvgPicture.asset(
                deleteIconAsset,
                width: 18.28,
                height: 18.28,
                colorFilter: const ColorFilter.mode(AppColors.alertRed, BlendMode.srcIn),
                errorBuilder: (ctx, err, stack) {
                  return const Icon(Icons.close, size: 18, color: AppColors.alertRed);
                },
              ),
              const SizedBox(width: 20),
              Text(
                resolvedDeleteLabel,
                style: const TextStyle(
                  color: AppColors.alertRed,
                  fontSize: AppTextStyles.fontSizeMain,
                  fontWeight: AppTextStyles.fontWeightRegular,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  return showMenu<RecordMenuAction>(
    context: context,
    position: PopupMenuAnchorPosition.fromGlobalOffset(
      context: context,
      globalOffset: position,
      estimatedMenuHeight: showEdit && showDelete ? 120 : 90,
    ),
    color: const Color(0xFFF9F9F9).withOpacity(0.9),
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    elevation: 5,
    shadowColor: Colors.black.withOpacity(0.05),
    menuPadding: EdgeInsets.zero,
    constraints: BoxConstraints(maxWidth: maxWidth),
    items: items,
  );
}
