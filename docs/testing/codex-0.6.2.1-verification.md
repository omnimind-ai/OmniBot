# Codex 0.6.2.1 回归记录（2026-09-08）

发布基线：远端 v0.6.2 / main `2bd4e07793e3d47af65c9255757e473dc989ea15`。
本次使用独立发布分支，未把开发目录中的其他未提交修改混入版本。

## 问题与修复边界

1. Codex Plan 完成但没有正文：实际 `@agentclientprotocol/codex-acp@1.10.0` 发出 `plan.planId`，`acp-kotlin@0.30.1` 解码要求 `id`；计划能力已协商后，Codex 不再同时输出普通正文，因此字段解码失败表现为空白。只在既有 Codex stdio 边界对该扩展补齐 `id`，继续进入同一 SDK、ACP reducer 和历史来源。测试覆盖 markdown、items、file、删除、已有规范 id、重复归一化与无关事件。
2. 精简确认卡仅更新局部状态：点击拒绝后没有复用完整卡的持久化；过期历史请求也可能仍显示按钮。复用既有确认结果写入方法，在 ACP 确认响应后保存结果；不再次提交已消费请求。新增两项 widget 回归在修复前均失败，修复后通过。

## 执行结果

| 层次 | 结果 | 范围 |
| --- | --- | --- |
| Kotlin | 18 通过 | `AcpSessionUpdateMapperTest`，包括真实 Codex Plan 消息解码 |
| Flutter | 298 通过 | 确认卡、共享 reducer、Conversation runtime coordinator |
| 静态检查 | 无 error / warning | 修改卡片文件；1 条既有 DropdownButtonFormField.value 弃用 info |
| Android 构建 | 通过 | developStandardDebug，versionName 0.6.2.1 / versionCode 13 |
| 实际 Codex CLI | 10 通过 | 两种配置各运行完成文本、部分文本、普通完成、结构化失败、Plan |
| 模拟器 App 操作 | 42 步通过 | Plan 27、历史确认 4、取消与后续对话 11；结果见下方 JSON |

设备：`emulator-5560`，AVD `OobCleanInstall20260907`，Android 13 ARM64，Alpine。
Codex 0.153.4 / codex-acp 1.10.0。使用保留数据安装 `adb install -r`，包含多次 force-stop/relaunch 验证。
安装测试 APK SHA-256：`cb43ff727d0a92e096ff8db150242c89e103a0ea9bbd263646cc67799d403f64`。

用户本次明确接受模拟器验证。以上是实际 App / Codex / ACP 操作，模型返回由受控本地 HTTP fixture 提供，不能作为真实模型输出质量或其他手机兼容性验收。**待真机验证**；生产签名产物由既有 Release APK 工作流生成，不与本地 debug APK 混称。

## 可执行入口

Flutter：

```sh
cd ui
flutter test test/features/home/pages/command_overlay/widgets/agent_request_card_test.dart test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart test/services/agent_event_reducer_test.dart
```

Kotlin 与构建（仓库根目录，JDK 17）：

```sh
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests '*AcpSessionUpdateMapperTest' :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
```

CLI：使用可丢弃目录安装 `@openai/codex@0.153.4` 和 `@agentclientprotocol/codex-acp@1.10.0`，将该目录作为首参数。固定配置是既有配置导出器的脱敏输出，只包含 fixture-key 和 fixture.invalid；测试不访问真实账户。

```sh
node scripts/verify-codex-completed-messages.mjs /absolute/disposable-cli-directory scripts/fixtures/codex-configs
node scripts/verify-installed-harness-adapters.mjs /absolute/disposable-cli-directory scripts/fixtures/codex-configs codex plan
```

App：先启动 `node scripts/fixtures/codex-plan-provider.mjs`；用既有配置脚本把测试 Provider 指向 `http://10.0.2.2:18769/v1`、模型 gpt-4o、密钥 fixture-key。在英文 UI 中进入 Codex 新对话；按以下顺序执行。用例自身设置宿主 Read only 和 Codex Plan / Default。测试只发送合成标记，不修改用户文件。

```sh
OMNIBOT_TEST_API_KEY=fixture-key OMNIBOT_TEST_BASE_URL=http://10.0.2.2:18769/v1 OMNIBOT_TEST_MODEL=gpt-4o node scripts/configure-agent-test-provider.mjs emulator-5560
node scripts/verify-agent-user-journey.mjs emulator-5560 scripts/fixtures/agent-user-journeys/codex-plan-output.en.json /tmp/codex-plan
node scripts/verify-agent-user-journey.mjs emulator-5560 scripts/fixtures/agent-user-journeys/codex-plan-resolved-approval.en.json /tmp/codex-approval
node scripts/verify-agent-user-journey.mjs emulator-5560 scripts/fixtures/agent-user-journeys/codex-cancel-recovery.en.json /tmp/codex-cancel
```

长期用例和结果：

- [Plan / Default 与重启](../verification/codex-0.6.2.1/plan.json)
- [确认结果重启恢复](../verification/codex-0.6.2.1/approval-fresh.json)
- [取消、连续对话与重启](../verification/codex-0.6.2.1/cancel.json)
- [官方 CLI 10 场景](../verification/codex-0.6.2.1/cli.jsonl)
- [受控 Provider 请求摘要](../verification/codex-0.6.2.1/provider.jsonl)

未纳入本次验收：DSH 沙箱、任意 shell/file 工具执行、真实模型服务质量和其他物理手机。不得据此宣称这些问题已经修复。
