import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme_mode.dart';

final appThemeModeProvider =
    StateNotifierProvider<AppThemeController, AppThemeMode>(
      (ref) => AppThemeController(),
    );

class AppThemeController extends StateNotifier<AppThemeMode> {
  AppThemeController() : super(StorageService.getThemeMode());

  Future<void> setThemeMode(AppThemeMode mode) async {
    if (state == mode) {
      return;
    }
    // 只更新 Flutter 端 state(由 MaterialApp.themeAnimationDuration 平滑过渡)+
    // 持久化存储(供下次冷启动时 App.onCreate -> applyStoredApplicationNightMode
    // 与 StartupThemeResolver.resolveSplashTheme 读到新值)。
    // 运行期不调用原生 applyThemeMode:UiModeManager.setApplicationNightMode /
    // AppCompatDelegate.setDefaultNightMode 会触发 Activity 重建,FlutterView
    // 重建期间 windowBackground 抢屏,这正是切换瞬间整屏闪黑的根因。
    state = mode;
    await StorageService.setThemeMode(mode);
  }
}
