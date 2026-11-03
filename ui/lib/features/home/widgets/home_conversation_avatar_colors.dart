import 'package:flutter/material.dart';

/// A coordinated set of opaque avatar fills for the home conversation list.
/// Colors are presentation only; no conversation or Agent settings are changed.
class HomeConversationAvatarColors {
  HomeConversationAvatarColors._();

  // Mist blue, sage, clay, rose, lavender, sand, sea glass and periwinkle.
  // Keep the hue order aligned so a theme switch preserves visual identity.
  static const _light = <Color>[
    Color(0xFFB6CDE0),
    Color(0xFFBECEB5),
    Color(0xFFE0BDA8),
    Color(0xFFDBB9C7),
    Color(0xFFCAC0DF),
    Color(0xFFE5D2A6),
    Color(0xFFB3D1C9),
    Color(0xFFBDC8E5),
  ];

  static const _dark = <Color>[
    Color(0xFF87A4BD),
    Color(0xFFA2B69B),
    Color(0xFFC99E8A),
    Color(0xFFC89BAB),
    Color(0xFFAFA3CC),
    Color(0xFFCDB780),
    Color(0xFF8FB8AF),
    Color(0xFF9EAED4),
  ];

  /// Assign in display order before building lazy rows, including group edges.
  /// The same sequence always receives the same colors on refresh or restart.
  static Map<String, int> assign(Iterable<String> orderedThreadKeys) {
    final result = <String, int>{};
    int? previous;
    for (final key in orderedThreadKeys) {
      if (result.containsKey(key)) continue;
      final hash = _stableHash(key);
      var index = hash % _light.length;
      if (index == previous) {
        // Pick one of the other hues without introducing mutable randomness.
        final offset = 1 + (hash ~/ _light.length) % (_light.length - 1);
        index = (index + offset) % _light.length;
      }
      result[key] = index;
      previous = index;
    }
    return result;
  }

  /// Stable fallback for a standalone row that has no neighboring rows.
  static int indexFor(String key) => _stableHash(key) % _light.length;

  static Color colorAt(int index, Brightness brightness) {
    final colors = brightness == Brightness.dark ? _dark : _light;
    return colors[index % colors.length];
  }

  static int _stableHash(String key) {
    // Explicit 31-bit arithmetic avoids the runtime-dependent String.hashCode.
    // Intermediate multiplication stays within JavaScript's exact integer range.
    var hash = 0x45d9f3b;
    for (final unit in key.codeUnits) {
      hash = ((hash * 131) ^ unit) & 0x7fffffff;
      hash ^= hash >> 13;
    }
    return hash ^ (hash >> 16);
  }
}
