import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_prompt_sheet.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_section.dart';
import 'package:ui/services/special_permission.dart';

class ManualRecordingPermissionCheck {
  const ManualRecordingPermissionCheck({
    required this.accessibilityReady,
    required this.overlayGranted,
  });

  final bool accessibilityReady;
  final bool overlayGranted;

  bool get isAuthorized => accessibilityReady && overlayGranted;
}

class ManualRecordingPermissionGuard {
  const ManualRecordingPermissionGuard._();

  static Future<ManualRecordingPermissionCheck> check() async {
    final accessibilityReady = await _checkPermission(
      'isAndroidGuiAccessibilityReady',
    );
    final overlayGranted = await _checkPermission('isOverlayPermission');
    return ManualRecordingPermissionCheck(
      accessibilityReady: accessibilityReady,
      overlayGranted: overlayGranted,
    );
  }

  static Future<bool> ensureAuthorized(BuildContext context) async {
    final permissionCheck = await check();
    if (permissionCheck.isAuthorized) return true;
    if (!context.mounted) return false;

    final permissions = _buildPermissions(context);
    final accessibilityPermission = permissions.firstWhere(
      (permission) => permission.id == kAccessibilityPermissionId,
    );
    final overlayPermission = permissions.firstWhere(
      (permission) => permission.id == kOverlayPermissionId,
    );
    accessibilityPermission.notifier.value = permissionCheck.accessibilityReady;
    overlayPermission.notifier.value = permissionCheck.overlayGranted;

    final authorized = await PermissionPromptSheet.show(
      context,
      permissions: permissions,
      title: _text(context, '请检查下列权限', 'Please check the permissions below'),
      actionLabel: _text(context, '继续录制', 'Continue recording'),
      actionKey: const ValueKey('manual-recording-permission-continue-button'),
    );
    if (!authorized || !context.mounted) return false;
    return (await check()).isAuthorized;
  }

  static List<PermissionData> _buildPermissions(BuildContext context) {
    return <PermissionData>[
      PermissionData(
        id: kAccessibilityPermissionId,
        iconPath: 'assets/home/chat/permission_hand.svg',
        iconWidth: 32,
        iconHeight: 32,
        name: _text(context, '无障碍权限', 'Accessibility Permission'),
        description: _text(
          context,
          '读取页面并执行点击、滑动和输入等 GUI 操作',
          'Observe the screen and perform taps, swipes, and text input',
        ),
        onAuthorize: () =>
            _openPermission('openAndroidGuiAccessibilitySettings'),
        checkAuthorization: () =>
            _checkPermission('isAndroidGuiAccessibilityReady'),
      ),
      PermissionData(
        id: kOverlayPermissionId,
        iconPath: 'assets/welcome/permission_overlay.svg',
        iconWidth: 32,
        iconHeight: 32,
        name: _text(context, '悬浮窗权限', 'Overlay Permission'),
        description: _text(
          context,
          '在其他应用上方显示录制控制',
          'Show recording controls over other apps',
        ),
        onAuthorize: () => _openPermission('openOverlaySettings'),
        checkAuthorization: () => _checkPermission('isOverlayPermission'),
      ),
    ];
  }

  static Future<void> _openPermission(String method) async {
    try {
      await spePermission.invokeMethod<void>(method);
    } on PlatformException {
      // Leave the permission disabled so the shared card remains actionable.
    }
  }

  static Future<bool> _checkPermission(String method) async {
    try {
      return await spePermission.invokeMethod<bool>(method) ?? false;
    } on PlatformException {
      return false;
    }
  }
}

String _text(BuildContext context, String zh, String en) =>
    Localizations.localeOf(context).languageCode == 'en' ? en : zh;
