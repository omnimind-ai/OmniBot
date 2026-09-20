# 聊天上传图片修复与验证（2026-09-16）

用户澄清本次问题是聊天上传图片，不是 GUI 截图。

## 定位与处理

- 当前 Provider 的 GLM-5.1 文本/文本工具请求正常，但有效 PNG 请求返回 HTTP 400，并包含上游 fallback timeout。详情见前一轮 [Provider 检查](provider-recovery-20260916.md)。这只能说明当前服务路由失败，不能据此断言所有部署的 GLM-5.1 都不支持图片。
- 图片选择/编码链路没有丢掉本次输入：通过 App 的真实文件选择器上传左右双色 PNG，在聊天“Model & settings”选择实际目录中的 GLM-4.6V 后，真实服务回复正确的颜色。
- 通过原聊天模型选择器保存配置：当前设备 `scene.dispatch.model = GLM-4.6V`；重启后模型面板仍显示该值。没有新增模型路由、重放逻辑或独立配置存储。此变更仅发生在当前模拟器，不代表用户的未连接手机已改好。
- 原 HTTP 错误携带是否实际包含图片的诊断信息。图片请求遇到 HTTP 400/422 时，通过官方 ACP 的 failureKind 投影为 `provider_image_request_rejected`，提示从聊天模型设置选择支持图片的模型；也提示检查图片格式/大小/服务商状态，不把所有 400 断言为模型不支持图片。401、额度等更具体错误仍优先，未删除图片或重放原 turn。
- 前面按 GUI 问题排查时已将模拟器独立 GUI 绑定设为 GLM-4.6V，GUI 原操作也执行成功；这不是聊天图片修复的验收依据。聊天修复使用的是聊天自己的模型选择入口。

## 执行结果

设备 emulator-5580，App 0.6.3 / versionCode 16。

1. 手动真实 UI：选择 GLM-4.6V，上传 `chat-upload-colors.png`，询问左右颜色，回复 `red, blue`，正式 end_turn，发送按钮恢复。
2. 第一次自动化发送检查受到无障碍弹窗干扰，发送脚本报 composer 未清空，保留 [first/result.json](artifacts/chat-image-20260916/first/result.json)，**不计为通过**。稍后可见对应真实回复。测试 marker 最初也不符合既有 turn verifier 对真实场景的 `OOB_LIVE_` 约定，已修正用例；没有放宽 verifier。
3. 新包覆盖安装后，准备阶段曾遇到界面读取失败和弹窗，未计为通过；修复准备脚本，使用唯一 dump 路径并要求新鲜 UI 数据，禁止读取旧快照。
4. 最终新包：上传另一张绿色/黄色 PNG，保持相同真实 Provider 和 GLM-4.6V。唯一 marker 的回答为 `green, yellow`；规范历史断言正式完成；重启后答案仍在、模型面板仍为 GLM-4.6V、关闭设置返回聊天。完整 **8/8 步通过**，见 [verified-second/result.json](artifacts/chat-image-20260916/verified-second/result.json)。第二张图片颜色与前一次不同，避免旧图片答案冒充新输入结果。
5. JVM 60 项通过：HTTP 客户端 34、错误投影 18、图片附件支持 8。Flutter RuntimeService 64 项通过。APK 构建、覆盖安装通过。Dart 单文件分析无 error/warning，51 条 info（未宣称全量 lint 通过）。汇总与 APK SHA 见 [checks.json](artifacts/chat-image-20260916/checks.json)。

## 可复现入口

预置：设备上已有可用 Provider；进入空草稿、空待发送附件的聊天；在聊天模型设置选 GLM-4.6V；系统文件选择器可见 Downloads/Recent 中的测试文件。

```sh
# 用实际文件选择器附加图片；不直接调用 ACP、不写历史、不伪造回复。
python3 scripts/prepare-chat-image.py emulator-5580 chat-upload-colors-second.png
# 上一步成功后单独执行：
node scripts/verify-agent-user-journey.mjs emulator-5580 scripts/fixtures/agent-user-journeys/chat-upload-colors-second.en.json /tmp/oob-chat-image-run

JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest --tests '*HttpAgentLlmClientTest' --tests '*AgentRuntimeErrorSupportTest' --tests '*AgentImageAttachmentSupportTest'
cd ui
/Users/wuzewen/Library/Caches/omnibot/flutter-3.47.2/bin/flutter test test/services/agent_runtime_service_test.dart
```

**待真机验证**：当前仅有模拟器。未覆盖全部模型、外部 Harness、图片格式、缓存清理或系统回收附件后的恢复。新的图片错误提示本轮由 JVM/Flutter 回归覆盖，未把成功模型旅程当作错误提示的真机验收。
