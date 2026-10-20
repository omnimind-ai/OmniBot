import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/jackery_ces_service.dart';

void main() {
  test(
    'disconnected device never shows cached battery, power or AI readiness',
    () {
      final status = JackeryDeviceStatus.fromMap({
        'connected': false,
        'batteryPercent': 91,
        'power': 'charging',
        'aiModelStatus': 'ready',
      });
      expect(status.connectionLabel, 'Not connected');
      expect(status.batteryLabel, 'Battery unavailable');
      expect(status.powerLabel, 'Power unavailable');
      expect(status.modelLabel, 'AI unavailable');
      expect(status.connectionDetailLabel, 'Not connected');
    },
  );
  test('missing and invalid device values stay unavailable', () {
    for (final value in [null, -1, 101, 20.5, double.nan, '80']) {
      final status = JackeryDeviceStatus.fromMap({
        'connected': true,
        'batteryPercent': value,
      });
      expect(status.batteryLabel, 'Battery unavailable');
      expect(status.modelLabel, 'AI unavailable');
      expect(status.connectionDetailLabel, 'Connection unavailable');
    }
    final status = JackeryDeviceStatus.fromMap({
      'connected': true,
      'batteryPercent': 0,
      'connection': 'usb',
      'power': 'external',
      'aiModelStatus': 'loading',
    });
    expect(status.batteryLabel, '0%');
    expect(status.connectionLabel, 'USB');
    expect(status.powerLabel, 'External power');
    expect(status.modelLabel, 'AI loading');
  });
}
