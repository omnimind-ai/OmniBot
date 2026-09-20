# 远程 Codex Harness 选择

基线 main `11a380d72`，0.6.2.3 / versionCode 15，当前未提交改动。

用户最终要求：前端不变，在现有 Harness 选择器里直接选择“远程 Codex”，
让任务在远端设备运行。撤回本轮此前独立“电脑 Codex”设置入口、连接页改版、
手机 Codex 重命名和电脑图标；既有聊天布局、资源卡、审批及历史入口保持原样。

## 实现与所有权

- 选择器补上已存在的 `codex-remote` 身份，不依赖手机是否安装 Codex。
- 选择仍经过 `_handleAcpAgentModeShortcutTap`、既有 HarnessSwitchSendBarrier、
  `_selectRemoteCodexRuntime` 和 ConversationThreadTarget 应用路径。
- 配置已就绪时不跳转页面；缺少配置时进入原有 remote_codex_setting 页面。
- `AgentRuntimeService.activateRemoteCodex` 仅封装原配置写入与 connect 调用。
  首次从本地切换时启用已保存的地址/Token/cwd；已经启用时不重复写配置，
  避免 native writeRemoteBridgeConfig 终止活动远程 transport。
- native connect 继续拥有连接复用；connected=true 且 runtime=remote 才接受为成功。
  远程启用偏好或本地 connected 状态都不能作为远程连接成功。
- 切换失败时撤销本次启用配置，错误回到原有切换失败路径，保留原会话/草稿；
  不自动重试、不转本地执行，不释放该次切换期间排队的发送为成功。
- Conversation -> ACP Session -> Turn -> Item 和单一 reducer 不变。

真实连接仍为：手机 WebSocket → PC codex-bridge → stdio codex-acp → Codex。
本次没有修改 Bridge 进程管理；它仍每连接启动进程，断连后终止。连接到官方
桌面正在运行的线程、账号云同步和断线继续执行没有完成，不能由此次 UI 接通推导。

## 可执行回归

在 ui/（Flutter 3.47.2 / Dart 3.13.2）：

```bash
flutter test --no-pub \
  test/features/home/pages/agent/remote_codex_harness_selection_test.dart \
  test/services/agent_runtime_service_test.dart \
  test/features/home/pages/chat/widgets/chat_app_bar_test.dart \
  test/features/home/pages/chat/harness_switch_send_barrier_test.dart \
  test/features/home/pages/chat/chat_architecture_test.dart \
  test/features/home/pages/scene_model_setting/scene_model_setting_page_test.dart
```

新增用例覆盖配置写入后连接、已启用不重写、断开不能冒充成功、本地不能冒充
远程、缺配置/写入失败、网络失败不重试与明确再选、既有屏障阻塞发送直到连接
完成、失败拒绝发送、重新读取已保存配置。聊天菜单用例验证无本地 Codex 时可选
远程身份，使用原有 AgentBrandIcon、原回调，不新增页面布局。

测试替换 MethodChannel，不代表真实网络、电脑执行或账号模型成功；保存恢复为
测试配置模拟，不冒充手机重启验收。新增用例已加入 scripts/test-agent-runtime.sh。
首次执行因新增测试的屏障 import 路径错误未编译，通过查找现有定义修正；
未跳过失败或替换实际屏障实现。

## 真机待验收

2026-09-11 adb devices -l 无设备。未安装、未发送真实任务或更改账号配置。
**待真机验证**：现有连接配置 → Harness 选远程 Codex → 原聊天页发送一条任务 →
检查实际运行设备/工作目录 → 切本地再切回 → 取消/继续 → App 重启/断网重连。
每次记录设备型号、Android、APK SHA-256、PC OS 与 Bridge/ACP 版本、操作和结果。

## 本轮执行结果

- 上述六个测试文件：146 项通过，0 失败；`/tmp/oob-remote-harness-tests-final.log`。
- service 与新增测试定向 analyze：无 error/warning，保留 51 条 info；没有当作零告警。
- `git diff --check` 与回归脚本 `bash -n` 通过。
- JDK 21 / Flutter 3.47.2：`assembleDevelopStandardDebug` 构建成功，24s，
  `/tmp/oob-remote-harness-build.log`。使用 `-Pflutter.sdk=/tmp/oob-flutter-3.47.2`，
  未修改本机 local.properties、项目签名或版本号。
- APK：`app/build/outputs/remote-codex-harness-20260911/OmniBot-remote-codex-harness-debug.apk`，
  0.6.2.3 / versionCode 15。未发布、未安装；待真机验证。
