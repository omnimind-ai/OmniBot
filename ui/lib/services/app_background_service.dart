import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/app_state_service.dart';
import 'package:ui/services/storage_service.dart';

enum AppBackgroundSourceType { none, local, remote }

enum AppBackgroundTextTone { dark, light }

enum AppBackgroundTextColorMode { auto, custom }

const bool _isFlutterTest = bool.fromEnvironment('FLUTTER_TEST');

Color? parseAppBackgroundHexColor(String value) {
  final normalized = value.trim().replaceAll('#', '').toUpperCase();
  if (normalized.length == 6) {
    final hexValue = int.tryParse('FF$normalized', radix: 16);
    return hexValue == null ? null : Color(hexValue);
  }
  if (normalized.length == 8) {
    final hexValue = int.tryParse(normalized, radix: 16);
    return hexValue == null ? null : Color(hexValue);
  }
  return null;
}

String? normalizeAppBackgroundHexColor(String value) {
  final color = parseAppBackgroundHexColor(value);
  if (color == null) {
    return null;
  }
  final argb = color.toARGB32();
  final alpha = (argb >> 24) & 0xFF;
  final red = (argb >> 16) & 0xFF;
  final green = (argb >> 8) & 0xFF;
  final blue = argb & 0xFF;
  if (value.trim().replaceAll('#', '').length == 8) {
    return '#'
            '${alpha.toRadixString(16).padLeft(2, '0')}'
            '${red.toRadixString(16).padLeft(2, '0')}'
            '${green.toRadixString(16).padLeft(2, '0')}'
            '${blue.toRadixString(16).padLeft(2, '0')}'
        .toUpperCase();
  }
  return '#'
          '${red.toRadixString(16).padLeft(2, '0')}'
          '${green.toRadixString(16).padLeft(2, '0')}'
          '${blue.toRadixString(16).padLeft(2, '0')}'
      .toUpperCase();
}

double resolvedChatTextScale(AppBackgroundConfig config) {
  return (config.chatTextSize / 14).clamp(0.86, 1.58).toDouble();
}

double resolvedThinkingTextSize(AppBackgroundConfig config) {
  return (12 * resolvedChatTextScale(config)).clamp(10.5, 19).toDouble();
}

@immutable
class AppBackgroundVisualProfile {
  final double sampledImageLuminance;
  final double effectiveLuminance;
  final AppBackgroundTextTone textTone;
  final Color? customPrimaryTextColor;

  const AppBackgroundVisualProfile({
    required this.sampledImageLuminance,
    required this.effectiveLuminance,
    required this.textTone,
    this.customPrimaryTextColor,
  });

  static const AppBackgroundVisualProfile defaultProfile =
      AppBackgroundVisualProfile(
        sampledImageLuminance: 0.82,
        effectiveLuminance: 0.9,
        textTone: AppBackgroundTextTone.dark,
      );

  bool get usesLightText => textTone == AppBackgroundTextTone.light;

  bool get usesCustomTextColor => customPrimaryTextColor != null;

  Color get primaryTextColor =>
      customPrimaryTextColor ??
      (usesLightText ? const Color(0xFFF4F8FE) : const Color(0xFF353E53));

  Color get secondaryTextColor => customPrimaryTextColor != null
      ? customPrimaryTextColor!.withValues(alpha: 0.82)
      : usesLightText
      ? const Color(0xFFD5E3F2)
      : const Color(0xFF617390);

  Color get subtleTextColor => customPrimaryTextColor != null
      ? customPrimaryTextColor!.withValues(alpha: 0.64)
      : usesLightText
      ? const Color(0xFFB4C4D8)
      : const Color(0xFF9DA9BB);

  Color get appBarIconColor =>
      usesLightText ? const Color(0xFFF4F8FE) : const Color(0xFF4A5872);

  Color get islandBorderColor => usesLightText
      ? Colors.white.withValues(alpha: 0.18)
      : const Color(0xFFD9E6FB);

  Color get userBubbleColor =>
      usesLightText ? const Color(0x3322344B) : const Color(0xE6F1F8FF);

  Color get attachmentSurfaceColor =>
      usesLightText ? const Color(0x33283D58) : const Color(0xFFE4EEFF);

  Color get attachmentBorderColor => usesLightText
      ? Colors.white.withValues(alpha: 0.14)
      : const Color(0xFFD0DEFA);

  Color get attachmentIconColor =>
      usesLightText ? const Color(0xFFD8E7F8) : const Color(0xFF375EAF);

  Color get attachmentTextColor =>
      usesLightText ? const Color(0xFFE9F2FC) : const Color(0xFF35517A);

  Color get accentBlue =>
      usesLightText ? const Color(0xFFBFD8FF) : const Color(0xFF4F83FF);

  Color get accentGreen =>
      usesLightText ? const Color(0xFFA9F0B6) : const Color(0xFF52C41A);

  String get previewToneLabel => usesCustomTextColor
      ? (LegacyTextLocalizer.isEnglish ? 'Custom Color' : '自定义颜色')
      : usesLightText
      ? (LegacyTextLocalizer.isEnglish ? 'Light Text' : '浅色文本')
      : (LegacyTextLocalizer.isEnglish ? 'Dark Text' : '深色文本');

  static AppBackgroundVisualProfile derive({
    required AppBackgroundConfig config,
    double? sampledImageLuminance,
  }) {
    if (!config.isActive) {
      return defaultProfile;
    }
    final imageLuminance = (sampledImageLuminance ?? 0.72).clamp(0.0, 1.0);
    final whiteMaskOpacity = resolvedWhiteMaskOpacity(config);
    final darkMaskOpacity = resolvedDarkMaskOpacity(config);
    final afterWhiteMask = ui.lerpDouble(
      imageLuminance,
      0.97,
      whiteMaskOpacity,
    )!;
    final effectiveLuminance = ui
        .lerpDouble(afterWhiteMask, 0.0, darkMaskOpacity)!
        .clamp(0.0, 1.0);
    final textTone = effectiveLuminance < 0.56
        ? AppBackgroundTextTone.light
        : AppBackgroundTextTone.dark;
    final customPrimaryTextColor =
        config.chatTextColorMode == AppBackgroundTextColorMode.custom
        ? parseAppBackgroundHexColor(config.chatTextHexColor)
        : null;
    return AppBackgroundVisualProfile(
      sampledImageLuminance: imageLuminance,
      effectiveLuminance: effectiveLuminance,
      textTone: textTone,
      customPrimaryTextColor: customPrimaryTextColor,
    );
  }
}

class AppBackgroundConfig {
  final bool enabled;
  final AppBackgroundSourceType sourceType;
  final String localImagePath;
  final String remoteImageUrl;
  final double blurSigma;
  final double frostOpacity;
  final double brightness;
  final double focalX;
  final double focalY;
  final double imageScale;
  final double chatTextSize;
  final AppBackgroundTextColorMode chatTextColorMode;
  final String chatTextHexColor;

  const AppBackgroundConfig({
    required this.enabled,
    required this.sourceType,
    required this.localImagePath,
    required this.remoteImageUrl,
    required this.blurSigma,
    required this.frostOpacity,
    required this.brightness,
    required this.focalX,
    required this.focalY,
    this.imageScale = 1,
    this.chatTextSize = 14,
    this.chatTextColorMode = AppBackgroundTextColorMode.auto,
    this.chatTextHexColor = '',
  });

  static const AppBackgroundConfig defaults = AppBackgroundConfig(
    enabled: false,
    sourceType: AppBackgroundSourceType.none,
    localImagePath: '',
    remoteImageUrl: '',
    blurSigma: 8,
    frostOpacity: 0.18,
    brightness: 1,
    focalX: 0,
    focalY: 0,
    imageScale: 1,
    chatTextSize: 14,
    chatTextColorMode: AppBackgroundTextColorMode.auto,
    chatTextHexColor: '',
  );

  bool get hasResolvedImage {
    return switch (sourceType) {
      AppBackgroundSourceType.local => localImagePath.trim().isNotEmpty,
      AppBackgroundSourceType.remote => remoteImageUrl.trim().isNotEmpty,
      AppBackgroundSourceType.none => false,
    };
  }

  bool get isActive => enabled && hasResolvedImage;

  Color? get customChatTextColor =>
      chatTextColorMode == AppBackgroundTextColorMode.custom
      ? parseAppBackgroundHexColor(chatTextHexColor)
      : null;

  AppBackgroundConfig copyWith({
    bool? enabled,
    AppBackgroundSourceType? sourceType,
    String? localImagePath,
    String? remoteImageUrl,
    double? blurSigma,
    double? frostOpacity,
    double? brightness,
    double? focalX,
    double? focalY,
    double? imageScale,
    double? chatTextSize,
    AppBackgroundTextColorMode? chatTextColorMode,
    String? chatTextHexColor,
  }) {
    return AppBackgroundConfig(
      enabled: enabled ?? this.enabled,
      sourceType: sourceType ?? this.sourceType,
      localImagePath: localImagePath ?? this.localImagePath,
      remoteImageUrl: remoteImageUrl ?? this.remoteImageUrl,
      blurSigma: blurSigma ?? this.blurSigma,
      frostOpacity: frostOpacity ?? this.frostOpacity,
      brightness: brightness ?? this.brightness,
      focalX: focalX ?? this.focalX,
      focalY: focalY ?? this.focalY,
      imageScale: imageScale ?? this.imageScale,
      chatTextSize: chatTextSize ?? this.chatTextSize,
      chatTextColorMode: chatTextColorMode ?? this.chatTextColorMode,
      chatTextHexColor: chatTextHexColor ?? this.chatTextHexColor,
    );
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'enabled': enabled,
      'sourceType': sourceType.name,
      'localImagePath': localImagePath,
      'remoteImageUrl': remoteImageUrl,
      'blurSigma': blurSigma,
      'frostOpacity': frostOpacity,
      'brightness': brightness,
      'focalX': focalX,
      'focalY': focalY,
      'imageScale': imageScale,
      'chatTextSize': chatTextSize,
      'chatTextColorMode': chatTextColorMode.name,
      'chatTextHexColor': chatTextHexColor,
    };
  }

  factory AppBackgroundConfig.fromJson(Map<String, dynamic> json) {
    final sourceName = (json['sourceType'] as String? ?? 'none').trim();
    final sourceType = AppBackgroundSourceType.values.firstWhere(
      (value) => value.name == sourceName,
      orElse: () => AppBackgroundSourceType.none,
    );
    final textColorModeName = (json['chatTextColorMode'] as String? ?? 'auto')
        .trim();
    final textColorMode = AppBackgroundTextColorMode.values.firstWhere(
      (value) => value.name == textColorModeName,
      orElse: () => AppBackgroundTextColorMode.auto,
    );
    final normalizedChatTextHexColor = normalizeAppBackgroundHexColor(
      (json['chatTextHexColor'] as String? ?? '').trim(),
    );
    return AppBackgroundConfig(
      enabled: json['enabled'] == true,
      sourceType: sourceType,
      localImagePath: (json['localImagePath'] as String? ?? '').trim(),
      remoteImageUrl: (json['remoteImageUrl'] as String? ?? '').trim(),
      blurSigma: ((json['blurSigma'] as num?)?.toDouble() ?? defaults.blurSigma)
          .clamp(0, 24)
          .toDouble(),
      frostOpacity:
          ((json['frostOpacity'] as num?)?.toDouble() ?? defaults.frostOpacity)
              .clamp(0, 0.55)
              .toDouble(),
      brightness:
          ((json['brightness'] as num?)?.toDouble() ?? defaults.brightness)
              .clamp(0.5, 1.5)
              .toDouble(),
      focalX: ((json['focalX'] as num?)?.toDouble() ?? defaults.focalX)
          .clamp(-1, 1)
          .toDouble(),
      focalY: ((json['focalY'] as num?)?.toDouble() ?? defaults.focalY)
          .clamp(-1, 1)
          .toDouble(),
      imageScale:
          ((json['imageScale'] as num?)?.toDouble() ?? defaults.imageScale)
              .clamp(1, 3)
              .toDouble(),
      chatTextSize:
          ((json['chatTextSize'] as num?)?.toDouble() ?? defaults.chatTextSize)
              .clamp(12, 22)
              .toDouble(),
      chatTextColorMode:
          textColorMode == AppBackgroundTextColorMode.custom &&
              normalizedChatTextHexColor == null
          ? AppBackgroundTextColorMode.auto
          : textColorMode,
      chatTextHexColor: normalizedChatTextHexColor ?? '',
    );
  }
}

double resolvedWhiteMaskOpacity(AppBackgroundConfig config) {
  final lightenBoost = ((config.brightness - 1).clamp(0.0, 0.5) / 0.5) * 0.18;
  return (0.14 + config.frostOpacity + lightenBoost).clamp(0.12, 0.78);
}

double resolvedDarkMaskOpacity(AppBackgroundConfig config) {
  return (((1 - config.brightness).clamp(0.0, 0.5)) / 0.5 * 0.24).clamp(
    0.0,
    0.24,
  );
}

class AppBackgroundService {
  AppBackgroundService._();

  static const String _storageKey = 'app_background_config_v1';
  static final ValueNotifier<AppBackgroundConfig> notifier =
      ValueNotifier<AppBackgroundConfig>(AppBackgroundConfig.defaults);
  static final ValueNotifier<AppBackgroundVisualProfile> visualProfileNotifier =
      ValueNotifier<AppBackgroundVisualProfile>(
        AppBackgroundVisualProfile.defaultProfile,
      );
  static int _visualProfileGeneration = 0;
  static int _configGeneration = 0;
  static Future<void> _writeTail = Future<void>.value();

  static AppBackgroundConfig get current => notifier.value;
  static AppBackgroundVisualProfile get currentVisualProfile =>
      visualProfileNotifier.value;

  static Future<void> load() async {
    await _writeTail;
    final generation = ++_configGeneration;
    try {
      await StorageService.reload();
    } catch (error) {
      debugPrint('Background preference cache refresh failed: $error');
    }
    try {
      final config = _fromChannelMap(
        await AppStateService.getBackgroundConfig(),
      );
      if (generation == _configGeneration) _publish(config);
      return;
    } catch (_) {
      // Flutter can start before its Android channel is attached. The shared
      // preferences value is a read-only first-frame fallback in that case.
    }
    String? raw;
    try {
      raw = StorageService.getString(_storageKey);
    } catch (_) {
      // Storage may also be unavailable during the first Flutter frame.
    }
    AppBackgroundConfig config = AppBackgroundConfig.defaults;
    if (raw != null && raw.trim().isNotEmpty) {
      try {
        final decoded = jsonDecode(raw);
        if (decoded is Map) {
          config = AppBackgroundConfig.fromJson(
            decoded.map((key, value) => MapEntry(key.toString(), value)),
          );
        }
      } catch (_) {
        // Malformed legacy data resolves to the same defaults as native.
      }
    }
    if (generation == _configGeneration) _publish(config);
  }

  static Future<void> save(AppBackgroundConfig config) async {
    await _orderedWrite(() async {
      final generation = ++_configGeneration;
      final normalized = _fromChannelMap(
        await AppStateService.saveBackgroundConfig(config.toJson()),
      );
      if (generation == _configGeneration) _publish(normalized);
      try {
        await StorageService.reload();
      } catch (error) {
        debugPrint('Background preference cache refresh failed: $error');
      }
    });
  }

  static Future<void> reset() async {
    await _orderedWrite(() async {
      final generation = ++_configGeneration;
      final config = _fromChannelMap(
        await AppStateService.resetBackgroundConfig(),
      );
      if (generation == _configGeneration) _publish(config);
      try {
        await StorageService.reload();
      } catch (error) {
        debugPrint('Background preference cache refresh failed: $error');
      }
    });
  }

  static Future<AppBackgroundVisualProfile> analyzeVisualProfile(
    AppBackgroundConfig config,
  ) async {
    final sampledLuminance = await _sampleImageLuminance(config);
    return AppBackgroundVisualProfile.derive(
      config: config,
      sampledImageLuminance: sampledLuminance,
    );
  }

  static Future<String> importLocalImage(String sourcePath) async {
    return AppStateService.importBackgroundImage(sourcePath);
  }

  static Future<void> deleteManagedLocalImage(String path) async {
    if (path.trim().isEmpty) return;
    await AppStateService.deleteManagedBackgroundImage(path);
  }

  static AppBackgroundConfig _fromChannelMap(Map<dynamic, dynamic> value) {
    return AppBackgroundConfig.fromJson(
      value.map((key, entry) => MapEntry(key.toString(), entry)),
    );
  }

  static Future<T> _orderedWrite<T>(Future<T> Function() action) {
    final result = _writeTail.then((_) => action());
    _writeTail = result.then<void>(
      (_) {},
      onError: (Object _, StackTrace __) {},
    );
    return result;
  }

  static void _publish(AppBackgroundConfig config) {
    notifier.value = config;
    _seedVisualProfile(config);
    unawaited(_refineVisualProfile(config));
  }

  static void _seedVisualProfile(AppBackgroundConfig config) {
    visualProfileNotifier.value = AppBackgroundVisualProfile.derive(
      config: config,
    );
  }

  static Future<void> _refineVisualProfile(AppBackgroundConfig config) async {
    final generation = ++_visualProfileGeneration;
    final profile = await analyzeVisualProfile(config);
    if (generation != _visualProfileGeneration) {
      return;
    }
    if (notifier.value.toJson().toString() != config.toJson().toString()) {
      return;
    }
    visualProfileNotifier.value = profile;
  }

  static Future<double?> _sampleImageLuminance(
    AppBackgroundConfig config,
  ) async {
    if (!config.isActive) {
      return null;
    }
    final bytes = await _loadImageBytes(config);
    if (bytes == null || bytes.isEmpty) {
      return null;
    }

    try {
      final codec = await ui.instantiateImageCodec(
        bytes,
        targetWidth: 24,
        targetHeight: 24,
      );
      try {
        final frame = await codec.getNextFrame();
        try {
          final byteData = await frame.image.toByteData(
            format: ui.ImageByteFormat.rawRgba,
          );
          if (byteData == null) {
            return null;
          }
          return _averageLuminanceFromRgba(byteData.buffer.asUint8List());
        } finally {
          frame.image.dispose();
        }
      } finally {
        codec.dispose();
      }
    } catch (_) {
      return null;
    }
  }

  static Future<Uint8List?> _loadImageBytes(AppBackgroundConfig config) async {
    switch (config.sourceType) {
      case AppBackgroundSourceType.local:
        final file = File(config.localImagePath.trim());
        if (!await file.exists()) {
          return null;
        }
        return file.readAsBytes();
      case AppBackgroundSourceType.remote:
        if (_isFlutterTest) {
          return null;
        }
        final imageUrl = config.remoteImageUrl.trim();
        if (imageUrl.isEmpty) {
          return null;
        }
        final uri = Uri.tryParse(imageUrl);
        if (uri == null || !(uri.scheme == 'http' || uri.scheme == 'https')) {
          return null;
        }
        final client = HttpClient()
          ..connectionTimeout = const Duration(seconds: 3);
        try {
          final request = await client
              .getUrl(uri)
              .timeout(const Duration(seconds: 3));
          final response = await request.close().timeout(
            const Duration(seconds: 4),
          );
          if (response.statusCode < 200 || response.statusCode >= 300) {
            return null;
          }
          return await consolidateHttpClientResponseBytes(response)
              .timeout(const Duration(seconds: 4));
        } catch (_) {
          return null;
        } finally {
          client.close(force: true);
        }
      case AppBackgroundSourceType.none:
        return null;
    }
  }

  static double _averageLuminanceFromRgba(Uint8List rgbaBytes) {
    if (rgbaBytes.isEmpty) {
      return 0.72;
    }
    double luminanceSum = 0;
    double alphaSum = 0;
    for (var index = 0; index + 3 < rgbaBytes.length; index += 4) {
      final red = rgbaBytes[index] / 255.0;
      final green = rgbaBytes[index + 1] / 255.0;
      final blue = rgbaBytes[index + 2] / 255.0;
      final alpha = rgbaBytes[index + 3] / 255.0;
      final luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
      luminanceSum += luminance * alpha;
      alphaSum += alpha;
    }
    if (alphaSum <= 0) {
      return 0.72;
    }
    return (luminanceSum / alphaSum).clamp(0.0, 1.0);
  }
}
