# 官方 Provider 与 Claude Code：验收未通过

## 已确认的现象

2026-09-07 09:21，真机上 Claude Code ACP 0.74.0 成功初始化、创建会话并提交
`session/prompt`，但两次约 0.5 秒及 0.25 秒后均收到官方 JSON-RPC 错误。
错误来自官方网关：`403 当前登录凭证不能访问该模型接口`。
首个请求 ID 为 `20260907012115169476356nJdZ0PcC`，可用于服务端查日志。
`first_text` 在这里是错误展示，不能作为成功回复的证据。

## 当前链路

官方 Provider 地址来自 `OmniAccount.currentAiRequestAccess()`，默认网关为
`https://model-api.omnimind.com.cn`。`AgentDispatchConfiguration` 将当前官方
账号的请求凭据交给共用配置映射；Claude Code 使用 `ANTHROPIC_BASE_URL`、
`ANTHROPIC_AUTH_TOKEN`、`ANTHROPIC_API_KEY` 以及选定的模型。
Claude Code 的推理请求使用 Anthropic Messages；ACP 统一的是应用与 Agent
的会话协议，并不自动将服务端的接口格式或权限变为兼容。

尚未证明 403 是接口路径权限、鉴权头兼容性还是模型权限。当前仓库找不到
这条服务端错误文案，不能用修改本地模型名字或跳过鉴权当作修复。

## 模拟器验收前置条件

用户要求后续在模拟器执行。`emulator-5556` 已通过实际界面切换 Claude Code，
当前仅有自定义 Provider，未登录官方账号；已请求用户完成官方登录。
自定义 LLMTHU 的成功不能代替官方账号路径的验收。

登录后需通过界面选择官方 Provider、选择目录中的模型、发送唯一测试口令，
同时核对网关结果、ACP 正文/终态以及 UI 正文。再执行切换模型和继续对话。
只有这些完整步骤通过，才能把本项改为通过。

原始本地日志：`/tmp/oob-real-device-runtime.log`。不将账号凭据写入报告或测试集。
