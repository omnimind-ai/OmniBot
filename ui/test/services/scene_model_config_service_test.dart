import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/scene_model_config_service.dart';

void main() {
  test('official GUI model is disabled by default', () {
    expect(const SceneOperationConfig().useOfficialService, isFalse);
    expect(SceneOperationConfig.fromMap(null).useOfficialService, isFalse);
    expect(SceneOperationConfig.fromMap(const {}).useOfficialService, isFalse);
  });

  test('official GUI model remains available as explicit opt in', () {
    final config = SceneOperationConfig.fromMap(const {
      'useOfficialService': true,
    });

    expect(config.useOfficialService, isTrue);
  });
}
