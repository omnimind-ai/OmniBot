# Codex Plan 输出兼容修复

状态：确定协议解析缺陷已修复；定向测试、官方 CLI 和模拟器实际 Plan UI 流程通过。本轮用户明确采用模拟器验收。另发现已处理确认框的恢复缺陷，未修复，不能宣称整条确认生命周期全部通过。

## 证据与原因

项目 Kotlin ACP SDK 0.30.1 的 PlanVariant 与 PlanRemoved 要求 id；已安装官方 codex-acp 1.10.0 的计划扩展使用 planId。应用 initialize 声明 plan 能力，Codex 因而发送 plan_update 而不是普通 agent_message_chunk。原生类型解析抛出 MissingFieldException，事件尚未进入展示 reducer。

先加入 codexPlanWireReachesTypedSessionUpdate 并运行，修复前确实失败。真实 Codex CLI + 官方 ACP 配合本机受控 Responses 服务，通过会话返回的 collaboration_mode 配置选项选择 Plan：两种 Provider 配置均返回带 planId 的完整计划，普通助手正文为空，官方 end_turn 正常。此行为独立于用户手机，尚无本次用户设备日志，不能断言其设备只有这一个原因。

## 修复范围

复用 AcpHarnessAdapters.codex.normalizeStdioLine，在 SDK 解码前，仅对 session/update 的 plan_update.plan 和 plan_removed 补齐相同身份的 id。保留原字段和全部正文；已有 id 不覆盖，不改其他方法或工具输入。覆盖 markdown、items、file、删除和幂等。没有增加用户设置、Agent loop、事件流、重试或历史来源。

这是两端不稳定扩展版本差异的薄兼容处理；两端版本对齐后应移除。版本和安装入口已有 Agent 目录及 managedInstallCommandAsset 管理，本次没有另建配置引擎。也未实现或验证 Agent 自更新管理功能。

## 验证与执行入口

- ./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests '*AcpSessionUpdateMapperTest' --tests '*AcpHarness*Test'：通过，其中 mapper 18 项。
- cd ui && flutter test test/services/agent_event_reducer_test.dart：193 项通过。
- node scripts/verify-installed-harness-adapters.mjs /tmp/oob-all-harness-acceptance app/build/reports/harness-adapters codex plan：官方 CLI 两个 case 通过。CLI_DIRECTORY 必须为隔离测试安装；目录应含真实 Codex 和 codex-acp 1.10.0。
- node scripts/verify-codex-completed-messages.mjs /tmp/oob-all-harness-acceptance app/build/reports/harness-adapters：原有 8 个文本/失败 case 通过。
- developStandardDebug APK 构建通过，独立保存于 app/build/outputs/codex-plan-20260908/OpenOmniBot-codex-plan.apk，SHA-256 e575c8d94bdad35ea435acf1107fbfeb4dafe869985251d6c893a00a41ccce7c。

证据在 artifacts/codex-plan-2026-09-08/。模拟器 emulator-5560 保留数据安装本包；该 AVD 未安装 Codex/ACP，不能把安装成功算作 Plan 界面验收。当前没有实体手机连接；仍需实际切换 Plan、显示计划、重复更新、结束、重启恢复和切回 Default 验收。未调用真实收费模型。


## 模拟器实际 UI 验证补充

用户明确要求本轮使用模拟器验收。通过 App 设置 → Agent mode → Codex → Install 安装真实 Codex 0.153.4 / ACP 1.10.0，安装包哈希与上述文件一致。使用已有调试配置入口绑定本机受控 Provider；没有写入合成历史替代真实 UI 操作。

- 可执行入口：先启动 `node scripts/fixtures/codex-plan-provider.mjs`，将隔离 AVD 测试 Provider 设为 `http://10.0.2.2:18769/v1` / gpt-4o / 合成凭据，在新 Codex 对话运行 `node scripts/verify-agent-user-journey.mjs emulator-5560 scripts/fixtures/agent-user-journeys/codex-plan-output.en.json <证据目录>`。
- 27 步均通过：在 App 选择只读权限和 Plan；实际发送 → 计划正文和 Implement this plan? 确认框显示 → 拒绝 → 回合结束 → 展开计划正文 → 重启后展开查看 → 切回 Default → 正常回复 → 再重启恢复回复。结果见 artifacts/codex-plan-2026-09-08/ui-persistence/result.json。
- 第一轮 ui-first 失败保留：App 完全访问策略自动批准了计划实施；测试服务错误地从历史助手消息提取标记，导致 DONE_DONE。修正测试服务只从用户消息取标记，并区分官方实施请求。未为此修改生产代码。
- Codex 的 Mode 与 App 全局权限是不同入口。本轮通过 App 只读权限实际获得确认框，没有把只改 Codex Mode 当成需要确认的证明。

### 新发现：已处理确认框恢复错误（未修复）

拒绝实施后，回合结束。重启后的历史仍出现可点击的允许/拒绝按钮。计划正文和 Default 回复本身恢复成功，但确认状态不正确。

新增失败回归 `codex-plan-resolved-approval.en.json`，在上面的已完成测试对话中运行，要求重启后旧确认框没有可点击按钮。实际在第 3 步 assertAbsent(允许) 失败；证据在 approval-recovery/result.json。此用例确实执行并失败，不计入 27 步通过结果。尚未点击过期的允许按钮，不对其后续行为作推测。

本机受控服务仅替代模型生成，原生 App、官方 CLI、ACP 解码、展示、确认和持久化均实际运行。没有验证真实模型的计划质量。
