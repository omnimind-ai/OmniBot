import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_section.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/permission_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/omni_glass.dart';

/// Shared glass permission card used by contextual actions in chat.
class PermissionPromptSheet extends StatefulWidget {
  const PermissionPromptSheet({
    super.key,
    required this.permissions,
    required this.actionLabel,
    required this.actionKey,
    this.title,
  }) : assert(permissions.length > 0);

  final List<PermissionData> permissions;
  final String actionLabel;
  final Key actionKey;
  final String? title;

  static Future<bool> show(
    BuildContext context, {
    required List<PermissionData> permissions,
    required String actionLabel,
    required Key actionKey,
    String? title,
  }) async {
    return await showModalBottomSheet<bool>(
          context: context,
          isScrollControlled: true,
          backgroundColor: Colors.transparent,
          barrierColor: Colors.black.withValues(alpha: 0.18),
          builder: (context) => PermissionPromptSheet(
            permissions: permissions,
            actionLabel: actionLabel,
            actionKey: actionKey,
            title: title,
          ),
        ) ??
        false;
  }

  @override
  State<PermissionPromptSheet> createState() => _PermissionPromptSheetState();
}

class _PermissionPromptSheetState extends State<PermissionPromptSheet>
    with WidgetsBindingObserver {
  late final Listenable _permissionChanges;

  @override
  void initState() {
    super.initState();
    _permissionChanges = Listenable.merge(
      widget.permissions.map((permission) => permission.notifier).toList(),
    );
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_checkPermissions());
    }
  }

  Future<void> _checkPermissions() async {
    await PermissionService.checkPermissions(widget.permissions);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;

    return Padding(
      padding: EdgeInsets.fromLTRB(
        12,
        0,
        12,
        12 + MediaQuery.viewInsetsOf(context).bottom,
      ),
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * 0.75,
        ),
        child: OmniGlassPanel(
          borderRadius: BorderRadius.circular(22),
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: 32),
              Text(
                widget.title ??
                    (LegacyTextLocalizer.isEnglish
                        ? 'Please check the permission below'
                        : '请检查下列权限'),
                style: TextStyle(
                  color: isDark ? palette.textPrimary : AppColors.text,
                  fontSize: 20,
                  fontWeight: FontWeight.w600,
                  height: 1.5,
                ),
              ),
              const SizedBox(height: 26),
              PermissionSection(
                permissions: widget.permissions,
                spacing: 36,
                onPermissionChanged: () {
                  unawaited(_checkPermissions());
                },
              ),
              const SizedBox(height: 32),
              Center(
                child: AnimatedBuilder(
                  animation: _permissionChanges,
                  builder: (context, child) {
                    final authorized = widget.permissions.every(
                      (permission) => permission.notifier.value,
                    );
                    return Semantics(
                      button: true,
                      enabled: authorized,
                      label: widget.actionLabel,
                      child: GestureDetector(
                        key: widget.actionKey,
                        onTap: authorized
                            ? () => Navigator.of(context).pop(true)
                            : null,
                        child: Opacity(
                          opacity: authorized ? 1 : 0.5,
                          child: Container(
                            width: double.infinity,
                            constraints: const BoxConstraints(maxWidth: 288),
                            height: 48,
                            decoration: BoxDecoration(
                              gradient: isDark
                                  ? LinearGradient(
                                      begin: const Alignment(0.14, -1.09),
                                      end: const Alignment(1.10, 1.26),
                                      colors: [
                                        Color.lerp(
                                          palette.surfaceElevated,
                                          palette.accentPrimary,
                                          0.18,
                                        )!,
                                        Color.lerp(
                                          palette.surfaceSecondary,
                                          palette.accentPrimary,
                                          0.34,
                                        )!,
                                      ],
                                    )
                                  : const LinearGradient(
                                      begin: Alignment(0.14, -1.09),
                                      end: Alignment(1.10, 1.26),
                                      colors: [
                                        Color(0xFF1930D9),
                                        Color(0xFF2CA5F0),
                                      ],
                                    ),
                              borderRadius: BorderRadius.circular(12),
                              border: isDark
                                  ? Border.all(color: palette.borderSubtle)
                                  : null,
                            ),
                            child: Center(
                              child: Text(
                                widget.actionLabel,
                                style: TextStyle(
                                  color: isDark
                                      ? palette.textPrimary
                                      : Colors.white,
                                  fontSize: 16,
                                  fontFamily: 'PingFang SC',
                                  fontWeight: FontWeight.w600,
                                  height: 1.5,
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
              const SizedBox(height: 28),
            ],
          ),
        ),
      ),
    );
  }
}
