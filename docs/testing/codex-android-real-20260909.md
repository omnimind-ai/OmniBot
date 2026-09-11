# Codex Android 安装与真实对话

设备 emulator-45562 / Android 13 ARM64，App v0.6.2.2 develop debug，APK `8a3e5e93fe4501245b676a13b728542080f5901a4a9e5cb0bb473181c84bca3b`。

2026-09-09 从 App 设置进入 Agent mode，Codex 初始为 Unchecked。使用现有 `start-harness-device-install.mjs` 点击该行 Install，仅派发一次。观察到 Installing in background，随后 Assistant installed 提示及 Available 状态。读取安装的 package.json，版本为 Codex CLI 0.153.4、Codex ACP 1.10.0。没有通过手工 CLI 安装替代 App 流程，没有清数据或改变权限。

回到聊天模式菜单选择 Codex，原生 selected_profile_id 为 codex-acp，欢迎页显示 Codex。管理页使用现有 LLMTHU GLM-5.1 (Debug) / GLM-5.1 绑定。运行新增的真实 API 对话用例（复用已有不调用工具的回复 scenario）：

```sh
node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/codex-real-api-basic.en.json /tmp/oob-codex-real-unique
```

入口要求初始为空白 Codex 欢迎页。runId `1788952661556`，**11/11 步通过**：两次唯一标记请求、对应回复与正式 end_turn、重启、两轮正式终态及第二轮回复再次核验。没有重发。

另一次只读合成消息查询确认两轮均属 Conversation 7、Session `01a085e4-0292-7c10-8c6e-8516c0fa4a94`，turnId 各不相同；此跨轮身份检查是本轮补充探针，尚未纳入 journey 的单独 action，不能说由 11 步本身断言。

结果、执行用例、身份与版本记录位于 `artifacts/codex-android-real-20260909/`。当前 App 保留 Codex 会话与已有小万数据。本轮没有产品代码修改。

这是模拟器上的安装/两轮对话/重启验收，不包含工具、权限、Plan 或物理设备。不能用 Mac CLI 测试补算这些 Android 操作。**待真机验证，整体目标仍未完成。**
