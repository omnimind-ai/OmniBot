import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/display_geometry_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/DisplayGeometry');

  tearDown(() async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    DisplayGeometryService.resetForTesting();
  });

  test('reads all four logical screen corner radii', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'getScreenCornerRadii');
          return <String, double>{
            'topLeft': 42,
            'topRight': 40,
            'bottomLeft': 38,
            'bottomRight': 36,
          };
        });

    expect(
      await DisplayGeometryService.screenCornerRadii(),
      const ScreenCornerRadii(
        topLeft: 42,
        topRight: 40,
        bottomLeft: 38,
        bottomRight: 36,
      ),
    );
  });

  test('normalizes malformed data and caches the platform result', () async {
    var calls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls += 1;
          return <String, Object?>{
            'topLeft': -10,
            'topRight': double.nan,
            'bottomLeft': 'invalid',
            'bottomRight': 24,
          };
        });

    const expected = ScreenCornerRadii(
      topLeft: 0,
      topRight: 0,
      bottomLeft: 0,
      bottomRight: 24,
    );
    expect(await DisplayGeometryService.screenCornerRadii(), expected);
    expect(await DisplayGeometryService.screenCornerRadii(), expected);
    expect(calls, 1);
  });

  test(
    'falls back to square corners when the channel is unavailable',
    () async {
      expect(
        await DisplayGeometryService.screenCornerRadii(),
        const ScreenCornerRadii.zero(),
      );
    },
  );
}
