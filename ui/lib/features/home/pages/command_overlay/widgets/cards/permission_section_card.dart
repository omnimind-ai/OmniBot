import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_section.dart';
import 'package:ui/services/permission_service.dart';
import 'package:ui/theme/theme_context.dart';

class PermissionSectionCard extends StatefulWidget {
  final Map<String, dynamic> cardData;
  final void Function(List<String> requiredPermissionIds)? onRequestAuthorize;

  const PermissionSectionCard({
    super.key,
    required this.cardData,
    this.onRequestAuthorize,
  });

  @override
  State<PermissionSectionCard> createState() => _PermissionSectionCardState();
}

class _PermissionSectionCardState extends State<PermissionSectionCard> {
  late final List<PermissionData> _backgroundPermissions;

  @override
  void initState() {
    super.initState();
    final requiredPermissionIds = normalizeRequiredPermissionIds(
      widget.cardData['requiredPermissionIds'] as List?,
    );
    _backgroundPermissions = [
      ...PermissionService.buildDisplayPermissionsForIds(
        requiredPermissionIds.isEmpty
            ? kTaskExecutionRequiredPermissionIds
            : requiredPermissionIds,
      ),
    ];
    if (widget.cardData['autoOpenAuthorization'] == true &&
        widget.onRequestAuthorize != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _goAuthorizePage();
      });
    }
  }

  void _goAuthorizePage() {
    final requiredPermissionIds = normalizeRequiredPermissionIds(
      widget.cardData['requiredPermissionIds'] as List?,
    );
    widget.onRequestAuthorize?.call(
      requiredPermissionIds.isEmpty
          ? kTaskExecutionRequiredPermissionIds
          : requiredPermissionIds,
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;

    return Column(
      children: [
        const SizedBox(height: 4),
        ClipRRect(
          borderRadius: BorderRadius.circular(16),
          child: Container(
            width: double.infinity,
            decoration: BoxDecoration(
              color: isDark ? palette.surfacePrimary : null,
              borderRadius: BorderRadius.circular(16),
              border: isDark ? Border.all(color: palette.borderSubtle) : null,
              boxShadow: isDark
                  ? null
                  : const [
                      BoxShadow(
                        color: Color(0x0A000000),
                        blurRadius: 30,
                        offset: Offset(0, 4),
                      ),
                    ],
            ),
            child: Stack(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(18, 16, 18, 36),
                  child: IgnorePointer(
                    child: Opacity(
                      opacity: 1,
                      child: PermissionSection(
                        permissions: _backgroundPermissions,
                        spacing: 14,
                      ),
                    ),
                  ),
                ),
                Positioned.fill(
                  child: Container(
                    padding: const EdgeInsets.fromLTRB(18, 16, 18, 36),
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: const Alignment(0.50, 0),
                        end: const Alignment(0.50, 0.50),
                        colors: isDark
                            ? [
                                palette.surfacePrimary.withValues(alpha: 0.18),
                                Color.lerp(
                                  palette.surfacePrimary,
                                  palette.surfaceSecondary,
                                  0.82,
                                )!,
                              ]
                            : [
                                Colors.white.withValues(alpha: 0.3),
                                const Color(0xFFF9FDFF),
                              ],
                      ),
                    ),
                  ),
                ),
                Align(
                  alignment: Alignment.bottomLeft,
                  child: Opacity(
                    opacity: isDark ? 0.2 : 1,
                    child: Image.asset(
                      'assets/chatbot/permission_section_bg1.png',
                      fit: BoxFit.fill,
                    ),
                  ),
                ),
                Align(
                  alignment: Alignment.bottomRight,
                  child: Opacity(
                    opacity: isDark ? 0.16 : 1,
                    child: Image.asset(
                      'assets/chatbot/permission_section_bg2.png',
                      fit: BoxFit.fill,
                    ),
                  ),
                ),
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: 16,
                  child: Center(
                    child: GestureDetector(
                      onTap: _goAuthorizePage,
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 30,
                          vertical: 8,
                        ),
                        decoration: BoxDecoration(
                          gradient: isDark
                              ? LinearGradient(
                                  begin: const Alignment(-0.17, -0.47),
                                  end: const Alignment(1.48, 1.69),
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
                                  begin: Alignment(-0.17, -0.47),
                                  end: Alignment(1.48, 1.69),
                                  colors: [
                                    Color(0xFF0056FA),
                                    Color(0xB2609CF7),
                                  ],
                                ),
                          borderRadius: BorderRadius.circular(8),
                          border: isDark
                              ? Border.all(color: palette.borderSubtle)
                              : null,
                        ),
                        child: Text(
                          '前往开启',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: isDark ? palette.textPrimary : Colors.white,
                            fontSize: 17,
                            fontFamily: 'PingFang SC',
                            fontWeight: FontWeight.w600,
                            height: 1.3,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
