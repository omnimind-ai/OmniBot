import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/app_icon_controller.dart';
import 'package:ui/theme/app_icon_variant.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/settings_ui.dart';

class AppIconSetting extends ConsumerWidget {
  const AppIconSetting({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selected = ref.watch(appIconVariantProvider);
    final l10n = context.l10n;
    return Column(
      children: [
        OmniSettingsTile(
          icon: LucideIcons.layoutGrid,
          title: l10n.appearanceAppIconTitle,
          subtitle: l10n.appearanceAppIconSubtitle,
          translateText: false,
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(18, 0, 18, 16),
          child: LayoutBuilder(
            builder: (context, constraints) {
              const spacing = 10.0;
              final textScale = MediaQuery.textScalerOf(context).scale(13) / 13;
              final columns =
                  ((constraints.maxWidth + spacing) /
                          (78 * textScale + spacing))
                      .floor()
                      .clamp(1, 3);
              final width =
                  (constraints.maxWidth - (columns - 1) * spacing) / columns;
              return Wrap(
                spacing: spacing,
                runSpacing: spacing,
                children: [
                  for (final variant in AppIconVariant.values)
                    SizedBox(
                      width: width,
                      child: _AppIconChoice(
                        variant: variant,
                        selected: variant == selected,
                        onTap: () async {
                          final applied = await ref
                              .read(appIconVariantProvider.notifier)
                              .setVariant(variant);
                          if (!applied && context.mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(
                                content: Text(
                                  context.l10n.appearanceAppIconUpdateFailed,
                                ),
                              ),
                            );
                          }
                        },
                      ),
                    ),
                ],
              );
            },
          ),
        ),
      ],
    );
  }
}

class _AppIconChoice extends StatelessWidget {
  const _AppIconChoice({
    required this.variant,
    required this.selected,
    required this.onTap,
  });

  final AppIconVariant variant;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final palette = context.omniPalette;
    final label = switch (variant) {
      AppIconVariant.sparkle => l10n.appearanceAppIconSparkle,
      AppIconVariant.dot => l10n.appearanceAppIconDot,
      AppIconVariant.cursor => l10n.appearanceAppIconCursor,
      AppIconVariant.wave => l10n.appearanceAppIconWave,
      AppIconVariant.open => l10n.appearanceAppIconOpen,
      AppIconVariant.classic => l10n.appearanceAppIconClassic,
    };
    return Semantics(
      key: ValueKey('appearance-app-icon-${variant.storageValue}'),
      button: true,
      selected: selected,
      inMutuallyExclusiveGroup: true,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 6),
          child: Column(
            children: [
              SizedBox.square(
                dimension: 62,
                child: Material(
                  color: const Color(0xFFE9F0F9),
                  shape: RoundedSuperellipseBorder(
                    borderRadius: BorderRadius.circular(18),
                    side: BorderSide(
                      color: selected
                          ? palette.brandPrimary
                          : palette.borderSubtle,
                      width: selected ? 2 : 1,
                    ),
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      Transform.scale(
                        scale: 1.4,
                        child: Image.asset(
                          variant.assetPath,
                          fit: BoxFit.contain,
                          excludeFromSemantics: true,
                        ),
                      ),
                      if (selected)
                        PositionedDirectional(
                          end: 4,
                          top: 4,
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              color: palette.accentPrimary,
                              shape: BoxShape.circle,
                            ),
                            child: Padding(
                              padding: const EdgeInsets.all(2),
                              child: Icon(
                                Icons.check_rounded,
                                size: 11,
                                color: palette.surfacePrimary,
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 6),
              Text(
                label,
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: selected ? palette.textPrimary : palette.textSecondary,
                  fontSize: 13,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                ),
              ),
              if (variant.isDefault) ...[
                const SizedBox(height: 4),
                DecoratedBox(
                  decoration: BoxDecoration(
                    color: palette.surfaceSecondary,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 6,
                      vertical: 2,
                    ),
                    child: Text(
                      l10n.appearanceAppIconDefault,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: palette.textSecondary,
                        fontSize: 10,
                      ),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
