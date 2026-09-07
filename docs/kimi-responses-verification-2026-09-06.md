# Kimi Dispatch Responses 配置修复

报错 `KIMI_MODEL_* channel does not support the OpenAI Responses wire API` 来自 OOB 的 buildKimiCodeEnvironment 前置断言。它只表明环境变量入口不支持该枚举，不表示 Kimi Harness 不支持 Responses，更不是网络断联。

官方依据：MoonshotAI/kimi-code 的 `packages/agent-core/src/config/env-model.ts` 限制环境变量 provider type 为 kimi、anthropic、openai；`docs/en/configuration/providers.md`、`session/provider-manager.ts` 支持配置文件中的 openai_responses。官方 CLI 已移除 --config/--config-file，配置读取自 KIMI_CODE_HOME/config.toml。

修复复用已有 Kimi 配置适配器及 ACP launchConfigWrites / Web managedFiles：Responses 使用官方 config.toml，Chat Completions 保留原环境变量入口。两入口共享生成逻辑，使用现有 OOB 专用 /root/.kimi-code/omnibot，不改变用户 ~/.kimi-code 主目录和 ACP 生命周期。配置按已有文件写入机制限制为 0600；含 Provider 凭据，不应输出内容到诊断日志。

验证：

- AgentConfigAdaptersTest 39 项、AgentWebRuntimeTest 21 项通过，覆盖 Responses 配置、端点归一化、引号、headers、Web 思考强度以及切回 Chat Completions。
- 真实 Android emulator-5556 内已安装的 Kimi：官方 initialize 成功，session/new 成功，session/prompt 确实请求本地模拟端点 /v1/responses，携带指定模型和虚构 API key；模拟服务刻意返回 401，官方 prompt 返回错误并结束。证明协议/模型映射可执行，不是线上模型回复成功的证明。
- 可重复命令：`node scripts/verify-kimi-responses-wire.mjs emulator-5556`。工具使用独立临时 Kimi home，不覆盖用户模型配置、不调用线上模型。Android 上需先具备 Ubuntu、Kimi 和应用 ACP filesystem preload。
- APK 构建成功，日志 /tmp/oob-kimi-responses-build.log。
- 修复 APK 已覆盖安装 emulator-5556，ADB 返回 Success；持久脚本随后在同一设备通过，输出 `initialized=true`、`sessionCreated=true`、`wireReached=true`、`exitCode=0`。该验证仍是本地 401 fixture，不是线上 Provider 回复验收。

尚未验收：用户手机的更新安装与真实 Provider 回复；当前 ADB 仅连接两个模拟器。Ubuntu tar 硬链接错误是另一独立问题，参见 install-script-audit-2026-09-06.md；本地 APK 已包含对应 PRoot 解压修复，不能据此声称用户手机已经更新。
