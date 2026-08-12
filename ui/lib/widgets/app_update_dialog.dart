import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher_string.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';

Future<void> showAppUpdateDialog(
  BuildContext context,
  AppUpdateStatus status,
) async {
  if (!AppUpdateService.isSelfUpdateAvailable) return;
  final hasDirectInstall = status.canInstall;
  final isEnglish = Localizations.localeOf(context).languageCode == 'en';
  final palette = context.omniPalette;
  final confirmed = await AppDialog.confirm(
    context,
    title: isEnglish ? 'New version available' : '发现新版本',
    cancelText: isEnglish ? 'Later' : '稍后',
    confirmText: hasDirectInstall
        ? (isEnglish ? 'Update now' : '立即更新')
        : (isEnglish ? 'Go to Release' : '前往 Release'),
    confirmButtonColor: context.isDarkTheme
        ? Color.lerp(palette.accentPrimary, palette.pageBackground, 0.42)!
        : palette.accentPrimary,
    content: _AppUpdateDialogContent(status: status),
    barrierDismissible: true,
    glassStyle: true,
  );

  if (confirmed != true || !context.mounted) {
    return;
  }

  if (!hasDirectInstall) {
    if (status.releaseUrl.isEmpty) {
      showToast(
        isEnglish ? 'No available Release URL' : '缺少可用的 Release 地址',
        type: ToastType.error,
      );
      return;
    }
    final launched = await launchUrlString(
      status.releaseUrl,
      mode: LaunchMode.externalApplication,
    );
    if (!launched) {
      showToast(
        isEnglish ? 'Failed to open Release page' : '打开 Release 页面失败',
        type: ToastType.error,
      );
    }
    return;
  }

  try {
    final notificationGranted = await ensureNotificationPermission();
    if (!notificationGranted) {
      showToast(
        isEnglish
            ? 'Notification permission is not granted. Download will continue, but system download progress will not be shown.'
            : '未授予通知权限，下载仍会继续，但不会显示系统下载进度',
        type: ToastType.warning,
      );
    }
    final result = await AppUpdateService.installLatestApk();
    final toastType = result.success ? ToastType.success : ToastType.warning;
    final message = result.message.isEmpty
        ? (isEnglish ? 'Update installation failed' : '更新安装失败')
        : result.message;
    showToast(message, type: toastType);
  } catch (_) {
    showToast(
      isEnglish ? 'Failed to start update' : '拉起更新失败',
      type: ToastType.error,
    );
  }
}

class _AppUpdateDialogContent extends StatelessWidget {
  final AppUpdateStatus status;

  const _AppUpdateDialogContent({required this.status});

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    final notesSurfaceColor = isDark
        ? palette.surfaceSecondary.withValues(alpha: 0.82)
        : const Color(0xFFF6F8FA);
    final notesBorderColor = isDark
        ? palette.borderSubtle.withValues(alpha: 0.72)
        : const Color(0xFFE6EDF5);
    final publishedAt = status.publishedAt > 0
        ? DateTime.fromMillisecondsSinceEpoch(status.publishedAt)
        : null;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _InfoRow(
          label: isEnglish ? 'Current version' : '当前版本',
          value: status.currentVersionLabel,
        ),
        const SizedBox(height: 8),
        _InfoRow(
          label: isEnglish ? 'Latest version' : '最新版本',
          value: status.latestVersionLabel,
        ),
        if (publishedAt != null) ...[
          const SizedBox(height: 8),
          _InfoRow(
            label: isEnglish ? 'Published at' : '发布时间',
            value:
                '${publishedAt.year.toString().padLeft(4, '0')}-${publishedAt.month.toString().padLeft(2, '0')}-${publishedAt.day.toString().padLeft(2, '0')}',
          ),
        ],
        if (status.releaseNotes.isNotEmpty) ...[
          const SizedBox(height: 12),
          Text(
            isEnglish ? 'Release notes' : '更新说明',
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: palette.textPrimary,
            ),
          ),
          const SizedBox(height: 6),
          Container(
            constraints: const BoxConstraints(maxHeight: 140),
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: notesSurfaceColor,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: notesBorderColor),
            ),
            child: SingleChildScrollView(
              child: Text(
                status.releaseNotes,
                style: TextStyle(
                  fontSize: 12,
                  height: 1.6,
                  color: palette.textSecondary,
                ),
              ),
            ),
          ),
        ],
      ],
    );
  }
}

class _InfoRow extends StatelessWidget {
  final String label;
  final String value;

  const _InfoRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final labelColor = context.isDarkTheme
        ? palette.textTertiary
        : const Color(0xFF5F6F89);
    return Row(
      children: [
        SizedBox(
          width: 68,
          child: Text(
            label,
            style: TextStyle(
              fontSize: 12,
              color: labelColor,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
        Expanded(
          child: Text(
            value,
            style: TextStyle(
              fontSize: 13,
              color: palette.textPrimary,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ],
    );
  }
}
