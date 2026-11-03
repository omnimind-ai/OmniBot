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

  /// Brand color used by logos and labels.
  final Color brandPrimary;
  final Color brandSurface;
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
    required this.brandPrimary,
    required this.brandSurface,
    required this.segmentTrack,
    required this.segmentThumb,
    required this.overlayScrim,
    required this.previewFallback,
    required this.shadowColor,
  });

  static const OmniThemePalette light = OmniThemePalette(
    pageBackground: Color(0xFFF6F6F6),
    surfacePrimary: Color(0xFFFFFFFF),
    surfaceSecondary: Color(0xFFF0F0F0),
    surfaceElevated: Color(0xFFE7E7E7),
    borderSubtle: Color(0xFFE4E4E4),
    borderStrong: Color(0xFFCECECE),
    textPrimary: Color(0xFF161616),
    textSecondary: Color(0xFF777777),
    textTertiary: Color(0xFF929292),
    accentPrimary: Color(0xFF161616),
    brandPrimary: Color(0xFF2C7FEB),
    brandSurface: Color(0xFFE6EDFF),
    segmentTrack: Color(0xFFF0F0F0),
    segmentThumb: Color(0xFFFFFFFF),
    overlayScrim: Color(0x4D000000),
    previewFallback: Color(0xFFF6F6F6),
    shadowColor: Color(0x0D000000),
  );

  static const OmniThemePalette dark = OmniThemePalette(
    pageBackground: Color(0xFF151515),
    surfacePrimary: Color(0xFF202020),
    surfaceSecondary: Color(0xFF292929),
    surfaceElevated: Color(0xFF333333),
    borderSubtle: Color(0xFF383838),
    borderStrong: Color(0xFF4A4A4A),
    textPrimary: Color(0xFFF3F3F3),
    textSecondary: Color(0xFFB0B0B0),
    textTertiary: Color(0xFF8E8E8E),
    accentPrimary: Color(0xFFF3F3F3),
    brandPrimary: Color(0xFF98AD90),
    brandSurface: Color(0xFF2E382D),
    segmentTrack: Color(0xFF292929),
    segmentThumb: Color(0xFF3A3A3A),
    overlayScrim: Color(0xA6000000),
    previewFallback: Color(0xFF1B1B1B),
    shadowColor: Color(0x26000000),
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
    Color? brandPrimary,
    Color? brandSurface,
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
      brandPrimary: brandPrimary ?? this.brandPrimary,
      brandSurface: brandSurface ?? this.brandSurface,
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
      brandPrimary: Color.lerp(brandPrimary, other.brandPrimary, t)!,
      brandSurface: Color.lerp(brandSurface, other.brandSurface, t)!,
      segmentTrack: Color.lerp(segmentTrack, other.segmentTrack, t)!,
      segmentThumb: Color.lerp(segmentThumb, other.segmentThumb, t)!,
      overlayScrim: Color.lerp(overlayScrim, other.overlayScrim, t)!,
      previewFallback: Color.lerp(previewFallback, other.previewFallback, t)!,
      shadowColor: Color.lerp(shadowColor, other.shadowColor, t)!,
    );
  }
}
