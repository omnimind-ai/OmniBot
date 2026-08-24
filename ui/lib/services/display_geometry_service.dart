import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

@immutable
class ScreenCornerRadii {
  const ScreenCornerRadii({
    required this.topLeft,
    required this.topRight,
    required this.bottomLeft,
    required this.bottomRight,
  });

  const ScreenCornerRadii.zero()
    : topLeft = 0,
      topRight = 0,
      bottomLeft = 0,
      bottomRight = 0;

  factory ScreenCornerRadii.fromPlatform(Object? value) {
    if (value is! Map) return const ScreenCornerRadii.zero();

    double read(String key) {
      final raw = value[key];
      if (raw is! num || !raw.isFinite) return 0;
      return raw.toDouble().clamp(0, double.infinity);
    }

    return ScreenCornerRadii(
      topLeft: read('topLeft'),
      topRight: read('topRight'),
      bottomLeft: read('bottomLeft'),
      bottomRight: read('bottomRight'),
    );
  }

  final double topLeft;
  final double topRight;
  final double bottomLeft;
  final double bottomRight;

  @override
  bool operator ==(Object other) {
    return other is ScreenCornerRadii &&
        other.topLeft == topLeft &&
        other.topRight == topRight &&
        other.bottomLeft == bottomLeft &&
        other.bottomRight == bottomRight;
  }

  @override
  int get hashCode => Object.hash(topLeft, topRight, bottomLeft, bottomRight);
}

class DisplayGeometryService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/DisplayGeometry',
  );
  static const Duration _metricsRefreshWindow = Duration(milliseconds: 250);

  static Future<ScreenCornerRadii>? _cached;
  static DateTime? _lastRefresh;

  static Future<ScreenCornerRadii> screenCornerRadii({bool refresh = false}) {
    final now = DateTime.now();
    final recentlyRefreshed =
        _lastRefresh != null &&
        now.difference(_lastRefresh!) < _metricsRefreshWindow;
    if (_cached == null || (refresh && !recentlyRefreshed)) {
      _lastRefresh = now;
      _cached = _readScreenCornerRadii();
    }
    return _cached!;
  }

  static Future<ScreenCornerRadii> _readScreenCornerRadii() async {
    try {
      final value = await _channel.invokeMethod<Object?>(
        'getScreenCornerRadii',
      );
      return ScreenCornerRadii.fromPlatform(value);
    } on PlatformException {
      return const ScreenCornerRadii.zero();
    } on MissingPluginException {
      return const ScreenCornerRadii.zero();
    }
  }

  @visibleForTesting
  static void resetForTesting() {
    _cached = null;
    _lastRefresh = null;
  }
}
