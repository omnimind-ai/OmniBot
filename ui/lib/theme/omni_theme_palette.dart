import 'package:flutter/material.dart';

@immutable
class OmniThemePalette extends ThemeExtension<OmniThemePalette> {
  final Color pageBackground;
  final Color surfacePrimary;
  final Color surfaceSecondary;
  final Color surfaceElevated;
  final Color borderSubtle;
  final Color borderStrong;
  final Color textPrimary;
  final Color textSecondary;
  final Color textTertiary;
  final Color accentPrimary;
  final Color segmentTrack;
  final Color segmentThumb;
  final Color overlayScrim;
  final Color previewFallback;
  final Color shadowColor;

  const OmniThemePalette({
    required this.pageBackground,
    required this.surfacePrimary,
    required this.surfaceSecondary,
    required this.surfaceElevated,
    required this.borderSubtle,
    required this.borderStrong,
    required this.textPrimary,
    required this.textSecondary,
    required this.textTertiary,
    required this.accentPrimary,
    required this.segmentTrack,
    required this.segmentThumb,
    required this.overlayScrim,
    required this.previewFallback,
    required this.shadowColor,
  });

  static const OmniThemePalette light = OmniThemePalette(
    pageBackground: Color(0xFFF4F7FB),
    surfacePrimary: Color(0xFFFFFFFF),
    surfaceSecondary: Color(0xFFF0F5FC),
    surfaceElevated: Color(0xFFE9F0F9),
    borderSubtle: Color(0xFFE2EAF4),
    borderStrong: Color(0xFFD3DEEC),
    textPrimary: Color(0xFF353E53),
    textSecondary: Color(0xFF71809B),
    textTertiary: Color(0xFF98A5BB),
    accentPrimary: Color(0xFF2C7FEB),
    segmentTrack: Color(0xFFE8EFF8),
    segmentThumb: Color(0xFFFFFFFF),
    overlayScrim: Color(0x4D0B1220),
    previewFallback: Color(0xFFF6FAFF),
    shadowColor: Color(0x141A2433),
  );

  static const OmniThemePalette dark = OmniThemePalette(
    pageBackground: Color(0xFF151617),
    surfacePrimary: Color(0xFF1C1E1F),
    surfaceSecondary: Color(0xFF242728),
    surfaceElevated: Color(0xFF2D3032),
    borderSubtle: Color(0xFF373A3C),
    borderStrong: Color(0xFF484C4F),
    textPrimary: Color(0xFFF2EFE8),
    textSecondary: Color(0xFFC9C3B8),
    textTertiary: Color(0xFF9A9488),
    accentPrimary: Color(0xFF98AD90),
    segmentTrack: Color(0xFF222425),
    segmentThumb: Color(0xFF303335),
    overlayScrim: Color(0xA6121314),
    previewFallback: Color(0xFF1A1B1C),
    shadowColor: Color(0x26000000),
  );

  /// Exhibition palette. Surfaces stay neutral; orange marks actions and state.
  static const OmniThemePalette jackeryCes = OmniThemePalette(
    pageBackground: Color(0xFFF8F7F5),
    surfacePrimary: Color(0xFFFFFFFF),
    surfaceSecondary: Color(0xFFFFFFFF),
    surfaceElevated: Color(0xFFFFFFFF),
    borderSubtle: Color(0xFFE8E5E1),
    borderStrong: Color(0xFFE8E5E1),
    textPrimary: Color(0xFF303030),
    textSecondary: Color(0xFF303030),
    textTertiary: Color(0x99303030),
    accentPrimary: Color(0xFFFF6727),
    segmentTrack: Color(0xFFF8F7F5),
    segmentThumb: Color(0xFFFFFFFF),
    overlayScrim: Color(0x4D303030),
    previewFallback: Color(0xFFF8F7F5),
    shadowColor: Color(0x14303030),
  );

  @override
  OmniThemePalette copyWith({
    Color? pageBackground,
    Color? surfacePrimary,
    Color? surfaceSecondary,
    Color? surfaceElevated,
    Color? borderSubtle,
    Color? borderStrong,
    Color? textPrimary,
    Color? textSecondary,
    Color? textTertiary,
    Color? accentPrimary,
    Color? segmentTrack,
    Color? segmentThumb,
    Color? overlayScrim,
    Color? previewFallback,
    Color? shadowColor,
  }) {
    return OmniThemePalette(
      pageBackground: pageBackground ?? this.pageBackground,
      surfacePrimary: surfacePrimary ?? this.surfacePrimary,
      surfaceSecondary: surfaceSecondary ?? this.surfaceSecondary,
      surfaceElevated: surfaceElevated ?? this.surfaceElevated,
      borderSubtle: borderSubtle ?? this.borderSubtle,
      borderStrong: borderStrong ?? this.borderStrong,
      textPrimary: textPrimary ?? this.textPrimary,
      textSecondary: textSecondary ?? this.textSecondary,
      textTertiary: textTertiary ?? this.textTertiary,
      accentPrimary: accentPrimary ?? this.accentPrimary,
      segmentTrack: segmentTrack ?? this.segmentTrack,
      segmentThumb: segmentThumb ?? this.segmentThumb,
      overlayScrim: overlayScrim ?? this.overlayScrim,
      previewFallback: previewFallback ?? this.previewFallback,
      shadowColor: shadowColor ?? this.shadowColor,
    );
  }

  @override
  OmniThemePalette lerp(ThemeExtension<OmniThemePalette>? other, double t) {
    if (other is! OmniThemePalette) {
      return this;
    }
    return OmniThemePalette(
      pageBackground: Color.lerp(pageBackground, other.pageBackground, t)!,
      surfacePrimary: Color.lerp(surfacePrimary, other.surfacePrimary, t)!,
      surfaceSecondary: Color.lerp(
        surfaceSecondary,
        other.surfaceSecondary,
        t,
      )!,
      surfaceElevated: Color.lerp(surfaceElevated, other.surfaceElevated, t)!,
      borderSubtle: Color.lerp(borderSubtle, other.borderSubtle, t)!,
      borderStrong: Color.lerp(borderStrong, other.borderStrong, t)!,
      textPrimary: Color.lerp(textPrimary, other.textPrimary, t)!,
      textSecondary: Color.lerp(textSecondary, other.textSecondary, t)!,
      textTertiary: Color.lerp(textTertiary, other.textTertiary, t)!,
      accentPrimary: Color.lerp(accentPrimary, other.accentPrimary, t)!,
      segmentTrack: Color.lerp(segmentTrack, other.segmentTrack, t)!,
      segmentThumb: Color.lerp(segmentThumb, other.segmentThumb, t)!,
      overlayScrim: Color.lerp(overlayScrim, other.overlayScrim, t)!,
      previewFallback: Color.lerp(previewFallback, other.previewFallback, t)!,
      shadowColor: Color.lerp(shadowColor, other.shadowColor, t)!,
    );
  }
}
