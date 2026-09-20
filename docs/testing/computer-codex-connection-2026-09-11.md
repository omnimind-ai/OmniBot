> 历史方案，已撤回：用户随后明确要求前端不变，仅在 Harness 选择器增加
> “远程 Codex”。独立连接页改版及其专属扫码旅程测试已撤回；本文保留历史验证
> 记录，不适用于当前工作树。当前实现和执行入口见
> [远程 Harness 选择](remote-codex-harness-selection-2026-09-11.md)。

# 电脑 Codex 连接简化

基线：main `11a380d72`，0.6.2.3 / versionCode 15。以下为当前未提交修改，
不代表已发布的 0.6.2.3 APK 已包含此交互。

## 需求与行为

用户反馈远程 Bridge 的设置、模式选择、会话入口太复杂，要求简单化。

- 设置的“服务与环境”和聊天助手菜单直接提供“电脑 Codex”。
- 手机 Codex 和电脑 Codex 分开；电脑菜单使用电脑图标，不要求安装手机 Codex。
- 连接页以扫码为主，地址、目录和密钥默认收在“手动连接”。
- 扫码返回后保存配对，调用现有 AgentRuntimeService.connect；只有返回
  connected 且 runtime=remote 才进入既有 AgentSessionsPage。
- 已保存配对可通过“打开电脑会话”重新连接；不会自动创建或发送任务。
- 失败留在连接页，允许用户明确重试。取消扫码不更改配对。
- 电脑启动说明使用 --token auto --no-interactive，省去地址和 token 选择。
  auto token 每次启动会变化，电脑重启连接程序后可能需要重新扫码。

Conversation -> ACP Session -> Turn -> Item 所有权保持不变：页面只负责
连接表单及导航，继续使用既有 connect、session/list 与 session/load/prompt。
没有引入第二个 reducer、Agent loop、重放或自动重试。打开连接页本身不切换
当前 Conversation；已有本地助手选择仍走原有选择路径。

## 可执行回归

新增 `ui/test/features/home/pages/agent/remote_codex_connection_journey_test.dart`：

- QR 解析结果返回后只保存/连接一次，直接显示既有会话，无第二次确认；
- 取消扫码保留原配对；
- 已保存电脑一键连接，保存先于连接、连接先于会话列举；
- 连接失败不导航，用户明确重试后成功；保存失败不发起连接；
- 重复点击不并发连接；页面退出后的迟到结果不导航；
- 重建页面复用配对，空配置只显示扫码主入口；密钥默认不可见。

聊天菜单新增 widget 用例：未安装手机 Codex 时电脑入口仍可见，使用独立图标，
点击派发 codex-remote。既有自动保存回归保留仅写远程配置字段的断言。
新连接旅程已纳入 `scripts/test-agent-runtime.sh` 的 Flutter 回归入口。

执行入口（Flutter 3.47.2 / Dart 3.13.2，在 ui/）：

```bash
flutter test --no-pub \
  test/features/home/pages/agent/remote_codex_connection_journey_test.dart \
  test/features/home/pages/agent/codex_bridge_qr_scanner_page_test.dart \
  test/features/home/pages/agent/agent_sessions_page_test.dart \
  test/features/home/pages/agent/agent_sessions_refresh_test.dart \
  test/features/home/pages/agent/agent_mode_setting_page_test.dart \
  test/features/home/pages/scene_model_setting/scene_model_setting_page_test.dart \
  test/features/home/pages/chat/widgets/chat_app_bar_test.dart \
  test/features/home/pages/chat/harness_switch_send_barrier_test.dart \
  test/features/home/pages/chat/chat_architecture_test.dart
```

## 验证边界

Widget 测试使用生产页面、导航和服务入口，替换原生 MethodChannel 与扫码页面
的返回结果。它们不代表摄像头、真实电脑 Bridge、真实模型、审批或手机端验收。
页面重建不等于 Android 进程重启。

2026-09-11 `adb devices -l` 无设备；未向用户设备安装、发送任务或改账号配置。
**待真机验证**：物理手机扫码电脑，列举会话、新建并发送任务、打开已有会话、
审批/取消、断网后明确重连、App 重启后配对恢复、手机/电脑模式切换和失败恢复。
记录手机型号、Android 版本、APK SHA-256、电脑 OS、Bridge/ACP 版本及每步结果。

不包含：官方 Codex 桌面活动线程接管、账号云同步、断线后电脑任务继续运行。
既有 Bridge 仍每连接启动一个 codex-acp，关闭连接终止该进程。

## 本轮结果

- 上述九个 Flutter 测试文件：101 项通过，0 失败；日志
  `/tmp/oob-computer-ux-regression-final.log`。最后仅清理新测试的空值语法提示后，
  连接旅程 7 项再次通过，计数不重复累加。
- 首轮新增扫码测试因未初始化测试 StorageService 失败，补齐真实路由依赖后通过；
  扩展回归中一项旧文案断言失败，更新为“电脑 Codex”后全组通过。未跳过失败。
- 连接页及新增旅程定向 analyze：No issues found。包含聊天代码的更大范围分析
  无 error，但仍有 5 warnings / 11 infos，不能称为全项目零告警。
- `git diff --check`、回归脚本 `bash -n` 通过。
- JDK 21、Flutter 3.47.2：Debug APK 构建成功。最终改动后再次增量构建，
  BUILD SUCCESSFUL in 23s；日志 `/tmp/oob-computer-ux-build-final.log`。
- 产物：`app/build/outputs/computer-codex-20260911/OmniBot-computer-codex-debug.apk`。
  versionName 0.6.2.3 / versionCode 15，未提升版本号、未发布。
- SHA-256：`1438d4101d5a03edd697be06bb07090265f8112a99c80da4b0af55cbbdda8219`。
- 真机、真实电脑端 ACP 和真实模型：未运行，待真机验证。构建和 widget 成功
  不代表这些端到端场景已通过。
