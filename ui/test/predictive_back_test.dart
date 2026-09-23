import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/state/predictive_back_controller.dart';
import 'package:ui/services/storage_service.dart';
import 'support/ui_preferences_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(clearUiPreferencesChannelFixture);

  setUp(() async {
    installUiPreferencesChannelFixture();
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
  });

  test('predictive back defaults to enabled', () {
    expect(StorageService.isPredictiveBackEnabled(), isTrue);
  });

  test('setPredictiveBackEnabled persists and getter reflects it', () async {
    final saved = await StorageService.setPredictiveBackEnabled(false);

    expect(saved, isTrue);
    expect(StorageService.isPredictiveBackEnabled(), isFalse);

    // 重新初始化后仍能读到持久化的值
    await StorageService.init();
    expect(StorageService.isPredictiveBackEnabled(), isFalse);
  });

  test('predictive back controller initializes from storage and toggles', () async {
    final container = ProviderContainer();
    addTearDown(container.dispose);

    expect(container.read(predictiveBackEnabledProvider), isTrue);

    await container
        .read(predictiveBackEnabledProvider.notifier)
        .setEnabled(false);

    expect(container.read(predictiveBackEnabledProvider), isFalse);
    expect(StorageService.isPredictiveBackEnabled(), isFalse);

    // 新容器模拟冷启动,从存储恢复关闭状态
    final freshContainer = ProviderContainer();
    addTearDown(freshContainer.dispose);

    expect(freshContainer.read(predictiveBackEnabledProvider), isFalse);

    await freshContainer
        .read(predictiveBackEnabledProvider.notifier)
        .setEnabled(true);

    expect(freshContainer.read(predictiveBackEnabledProvider), isTrue);
    expect(StorageService.isPredictiveBackEnabled(), isTrue);
  });
}
