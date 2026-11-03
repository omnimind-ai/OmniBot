import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/liquid_glass_controller.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<void> storage(Map<String, Object> values) async {
    SharedPreferences.setMockInitialValues(values);
    await StorageService.init();
  }

  test('new installs enable ultra glass', () async {
    await storage({});
    final controller = LiquidGlassController();
    addTearDown(controller.dispose);
    expect(controller.state.enabled, isTrue);
    expect(controller.state.quality, LiquidGlassQuality.ultra);
  });

  test('quality and disabled state survive reconstruction independently', () async {
    await storage({});
    final controller = LiquidGlassController();
    addTearDown(controller.dispose);
    await controller.setQuality(LiquidGlassQuality.standard);
    await controller.setEnabled(false);
    final restored = LiquidGlassController();
    addTearDown(restored.dispose);
    expect(restored.state.enabled, isFalse);
    expect(restored.state.quality, LiquidGlassQuality.standard);
    await restored.setEnabled(true);
    expect(restored.state.quality, LiquidGlassQuality.standard);
    final enabled = LiquidGlassController();
    addTearDown(enabled.dispose);
    expect(enabled.state.enabled, isTrue);
    expect(enabled.state.quality, LiquidGlassQuality.standard);
  });

  test('unknown stored quality uses ultra without overriding disabled state', () async {
    await storage({
      LiquidGlassController.enabledKey: false,
      LiquidGlassController.qualityKey: 'retired-setting',
    });
    final controller = LiquidGlassController();
    addTearDown(controller.dispose);
    expect(controller.state.enabled, isFalse);
    expect(controller.state.quality, LiquidGlassQuality.ultra);
  });
}
