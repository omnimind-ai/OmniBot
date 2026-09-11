# 上下文圈默认零值

用户要求：输入框保持一个圈，初始/暂无用量时默认 0，不显示问号，并重装模拟器验证。

修改仅作用于展示：移除 `_ContextUsageRingButton` 的问号覆盖层；缺少有效 ratio 时仍以 0 绘制圈，默认 tooltip 为 0%。已设置上下文阈值的聊天沿用 `0 / 阈值 tokens` 展示，已有有效用量仍显示真实数值。没有写入虚构 usage，没有改变模型容量、阈值或自动压缩判断；未设置阈值的说明继续保留。

## 可执行回归

既有 `chat_input_area_test.dart` 的大小输入框两项用例改为断言无问号、默认 0% 且长按调整仍有效；已知 0 和非零进度用例保留。与 `agent_request_card_test.dart` 一起在隔离兼容基线上实际运行 45 项通过。

```sh
cd ui
flutter test --no-pub test/features/home/pages/command_overlay/widgets/chat_input_area_test.dart test/features/home/pages/command_overlay/widgets/agent_request_card_test.dart
```

## 构建边界

最新主线 Flutter 3.47.2 / Dart ^3.13.0 工具链仍未就绪。本次使用隔离的 `f07a09841` 合并前基线，加入与整合分支相同的历史确认卡修复和本次零值圈修改，生成兼容验证包；不回退整合分支、不声称该包包含主线依赖升级。该基线已包含此前长上下文和抽屉按需构建修复。兼容基线离线解析使用本机旧 SDK 的依赖，不改变当前主线 lockfile。

兼容基线 `assembleDevelopStandardDebug` 构建成功（4m57s）。APK SHA-256：`eda987e1ff23d095d92e698eb3b75fedead45140b4693dc4bec81db521766c4a`，0.6.2.2（14），`adb -s emulator-5560 install -r` 成功，已有历史保留。重装后截图检查空环无问号；UIAutomator 确认 `0 / 128,000 tokens`，长按打开 Adjust Context Threshold，未修改阈值；关闭弹层、force-stop/重新启动后仍为相同零值显示。设置页真实 usage 仍为 No data yet，证明没有为了界面零值写入虚构观测。

结构化证据见 [verification.json](artifacts/context-ring-zero-20260909/verification.json)。**待真机验证**，兼容验证不能替代新主线构建与验收；该包也未执行历史确认卡的设备专项回归。
