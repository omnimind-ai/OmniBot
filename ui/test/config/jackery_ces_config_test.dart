import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

void main() {
  test(
    'audience route guard rejects direct links to every configuration surface',
    () {
      for (final route in [
        '/welcome/choice',
        '/home/termux_setting',
        '/my/account',
        '/my/about',
        '/home/storage_usage',
        '/home/mcp',
        '/my/theme',
        '/home/scene_model_setting',
        '/home/agent_config',
      ]) {
        expect(JackeryCesConfig.allowsRoute(route), isFalse, reason: route);
      }
      for (final route in [
        '/home/chat',
        '/home/settings',
        '/home/model_provider_setting',
        '/home/chat_history',
      ]) {
        expect(JackeryCesConfig.allowsRoute(route), isTrue, reason: route);
      }
    },
  );

  test(
    'CES forces English and brand even when a caller supplies Chinese locale',
    () {
      if (!JackeryCesConfig.enabled) return;
      expect(
        LegacyTextLocalizer.localize('小万正在思考...', locale: const Locale('zh')),
        'Jackery Power AI is thinking...',
      );
      expect(LegacyTextLocalizer.localize('设置'), 'Settings');
    },
  );
}
