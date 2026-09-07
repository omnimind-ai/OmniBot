# CC 终端输出修复与验收

## 已确认问题

在 emulator-5556 使用已有 Provider / GLM-5.1，CC 实际执行了独立 CSV CLI 开发任务。mkdir 和两个文件写入成功，Python 测试卡片显示 Success，但打开详情只有 `$ command`，没有 stdout。

根因是宿主对 CC 声明 `_meta.terminal_output=true`，官方 claude-agent-acp 0.74.0 因此将 Bash 输出放入 `tool_call_update._meta.terminal_output`；当前宿主没有对应输出投影。官方适配器在未声明该扩展时，已经提供标准 ACP text content 回退。

## 最小修复

在既有 `AcpHarnessAdapters.claudeCode.clientCapabilityMeta` 中去除 `terminal_output`，保留其他 metadata 和标准 ACP terminal 能力。没有修改官方包、添加私有输出协议或改变会话生命周期。

## 验证

- 54 项 AgentConfigAdaptersTest 通过，包含 CC 不声明未渲染扩展、保留其他 metadata、重复处理幂等测试。
- `scripts/verify-claude-terminal-output.mjs` 直接调用本地安装的官方适配器：成功/失败两种结果都能通过标准 content 返回原始输出。没有模型请求或密钥。
- 修复 APK 构建成功，SHA256 `cbde0e59f83f378f98781eec9d3043221d6d6c810ae9f53073bf093aa1d34a20`，已 install -r 安装模拟器。
- 修复前长任务约 5 分 24 秒，官方 end_turn，session 后缀 36a4a80e / turn 后缀 cb345cf8。真实文件位于 `/workspace/oob_cc_acceptance_20260906`：csvstats.py、test_csvstats.py、sample_data.csv。Agent 报告 11 项测试通过；因旧包工具详情无输出，需要新包重跑直接核验。
- 安装重启后原会话恢复；2026-09-07 00:02:08 在同 session 36a4a80e 发起 followup，turn 6f40e31e。实际执行 `python3 -m unittest discover -s /workspace/oob_cc_acceptance_20260906 -v`，修复后终端卡片和 UI accessibility 均显示 `Ran 11 tests in 0.050s / OK`。00:02:52 官方 `end_turn`，约 43 秒，未重放旧任务。
- 重启后 UI 一度不暴露 accessibility 子节点，输入自动化的前两次尝试在输入前终止，未发送消息。通过实际截图定位输入区完成后续验证，不把该自动化障碍归因于 CC。
- 协同任务已将包含该修复的最终整合包安装到手机，SHA256 `82906b398ae2990d46326db8854a7e37622f933041a0bdc83830f8174fa8712c`。CC 长任务和重启续聊/终端输出验收是在模拟器完成，不声称手机 CC 同样完成该长任务。
