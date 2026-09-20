# OmniFlow Release 启用失败回归（2026-09-18）

需求：手机 OmniFlow 无法启用，修复至可以使用；不能仅验证 Debug。

## 根因及修复

真机 PJE110（b49f281b，Android 16），原安装版本 0.6.3 / 16，OmniFlow 2.2.0。
原 Release 点击启用立即回到关闭，仅提示“插件启停失败”。同数据 Debug 可启用；重新安装原 Release 后，恢复插件时报 `Abstract classes can't be instantiated ... Class name: va7`。
构建 mapping 将 va7 对应到 OmniFlowRuntimeManifest。Gson 反射创建的 host.json 数据类未受混淆规则保护，被 R8 优化。

- 在 omniflow-android/consumer-rules.pro 精确保留 OmniFlowRuntimeManifest、RuntimeTool，随模块传递规则。
- 插件详情保留并显示 MethodChannel 返回的失败原因；成功重试清除旧错误。
- 手机小万无障碍服务原先关闭，经系统设置开启。此配置与 R8 启动失败是不同问题。

## 可执行入口

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon --no-parallel :omniflow-android:testDebugUnitTest
cd ui
flutter test test/features/home/pages/plugin_market/plugin_market_page_test.dart
# 回到仓库根目录，安装构建完成的 minified Release APK 后执行：
python3 scripts/verify-omniflow-release-enable.py --serial b49f281b --out docs/testing/artifacts/omniflow-release-enable-20260918/phone
```

真机脚本拒绝 Debug 包，实际点击关闭/启用、打开复用指令列表、强停重启、验证启用状态与再次加载。不会删除数据，也不会执行用户已保存的指令。失败保留结果；不得将加载错误算作成功。
Widget 回归注入 runtime_entrypoint_missing，验证错误可见、开关保持关闭、重试成功后错误清除。

## 结果

- 64 项 OmniFlow 单元测试通过（本次 Gradle 复用未变化测试的有效缓存）。
- 12 项插件市场 Widget 测试实际运行通过，含新错误恢复案例。
- Release 构建成功；混淆结果确认两类及构造器保留。
- 真机 agent 操作验收通过：关闭再启用；Python warmup_ready（约3秒）；列表显示实际已有指令；强停重启后仍启用且列表再次加载。
- 版本 0.6.3 / 16；minified productionStandardRelease，Android debug 证书签名的测试发行包，已覆盖安装，未清除数据。
- 本次验收覆盖启动、列表调用、生命周期恢复；没有据此声称所有模型驱动任务、跨设备迁移都通过。
- Dart 分析无错误；报告一项原有 _openApp 的花括号风格提示。

APK SHA-256、设备结果、修复前错误与修复后启动日志见 artifacts/omniflow-release-enable-20260918/verification.json 及相邻证据。
