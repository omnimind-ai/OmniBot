import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

void main() {
  tearDown(LegacyTextLocalizer.clearResolvedLocale);

  test('uses active locale override for legacy translations', () {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    expect(LegacyTextLocalizer.localize('设置'), '设置');

    LegacyTextLocalizer.setResolvedLocale(const Locale('en'));
    expect(LegacyTextLocalizer.localize('设置'), 'Settings');
    expect(
      LegacyTextLocalizer.localize('另一个终端环境准备任务正在运行，请稍后再试。'),
      'Another terminal environment setup task is already running. Please try again later.',
    );
    expect(
      LegacyTextLocalizer.localize(
        '环境检测失败，无法确认组件状态：Failed to extract alpine rootfs.',
      ),
      'Environment check failed; unable to confirm component status: Failed to extract alpine rootfs.',
    );
    expect(
      LegacyTextLocalizer.localize('正在下载 Ubuntu 运行资源 12.0 MB / 28.5 MB'),
      'Downloading Ubuntu runtime 12.0 MB / 28.5 MB',
    );
  });
}
