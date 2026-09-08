# Provider 模型目录刷新：已验证结果和未完成项

## 统一职责

Provider 是可访问模型目录的来源；AgentConfigAdapterRegistry 将同一目录映射到各程序官方配置；LocalAcpRuntime 通过协商的 ACP 会话能力读取配置、设置模型和处理完成。界面沿用一个模型与参数卡片。普通启动和发送不新增网络目录请求，点击模型才实时刷新；成功目录沿用已有存储用于下一次启动，不用存储结果代替显式刷新。

## Kimi 已验证

- 原因：ACP 启动配置只注入单个 KIMI_MODEL_NAME，Responses 配置也只有一个条目。模型卡片读取官方 configOptions，自然只能看到一个。显式刷新只有小万分支处理，Kimi 返回已有快照。
- 修复：ACP 统一使用 Kimi 官方 config.toml 多模型目录，保留原始模型 ID、名称和可用元数据；显式刷新获取 Provider 目录并更新配置；通过同一个 session/load 读取更新后的选项，抑制历史回放进入实时流。
- 目录写入复用已有 Provider 存储契约，修复原来读取端仍在、成功发现结果却没有写入端的问题。凭据不进入目录；Provider 修改后的旧结果通过版本/端点检查隔离。
- 官方 CLI 测试：Anthropic、Chat Completions、Responses 三种接口均完成首次请求、切换第二个模型、刷新新增第三个模型、进程重启并恢复同一会话。每种检查四次真实 HTTP 请求的模型 ID；接口回复为本地确定性夹具，不是线上推理。
- 真机 b49f281b：Provider 返回并保存 95 个目录项。选择 DeepSeek-V3-250324，实际回复 `KIMI_MODEL_SWITCH_OK`（5.7 秒）；同一对话切换 DeepSeek-V3.1，追问前条回复的 token，实际答对（5.2 秒）。显式 force-stop 后重新打开 App，模型仍为 DeepSeek-V3.1，消息保留。
- 已安装的 Kimi 修复 APK：`/tmp/oob-kimi-model-catalog-final-20260906.apk`。这不包括下述后续公共层改动。
- 证据：`verification/kimi-model-catalog-20260906/` 截图；`scripts/verify-kimi-model-catalog.mjs`。

## 公共层改动（未完成全量设备验收）

- 显式刷新按 shared Provider + configAdapterId 能力分派，不再按 Kimi ID 特判；小万复用同一个 Provider 获取函数，避免重复网络请求。
- LocalAcpRuntime 按 loadSession 协商能力处理配置刷新，复用现有回放抑制和会话注册，不增加重开会话或重试协议。
- Claude Code 的 availableModels 接入 Provider 目录并保留 settings.json 其他字段；DSH 的官方 Provider patch 支持多模型；Codex/OpenCode 沿用已有目录写入适配器。
- 被动 model/list 投影同一份成功发现的目录，不再固定只返回 Dispatch 当前模型。
- Android 单元检查：AgentConfigAdaptersTest、AgentAdapterCatalogTest、AgentRuntimeManagerConfigTest、AgentWebRuntimeTest、ModelProviderConfigStoreTest 通过。

## 实际失败，不能当作通过

`scripts/verify-harness-model-catalog.mjs` 消费 Android 测试生成的初始/刷新两份配置，使用真实安装的程序和本地 HTTP 夹具。

- Claude Code、Codex、OpenCode：启动时接入两个 Provider 模型并切换请求成功；写入第三个模型后，同一 session/load 仍返回旧选项。额外等待 2 秒后 Codex/OpenCode 结果相同。不能用修改界面选项来掩盖此失败。
- Claude Code 0.74.0 的 getOrCreateSession 在会话参数指纹相同时直接返回既存 configOptions。Codex 1.10.0 和当前 OpenCode 也保留运行时目录。
- DSH：多模型请求路径可执行，但官方 ACP 不实现 session/load；必须依据 initialize 协商结果处理，不得伪造该能力。
- 官方 Codex app-server 生成的 ModelListParams schema 只有 cursor/includeHidden/limit，没有强制刷新参数；不能虚构 refresh 参数。

尚未通过运行中新增模型、所有助手重启恢复及多助手真机切换的完整验收。公共层改动尚未安装到手机，不应据此声明所有助手已修复。

仓库禁止新增 renew/reopen 路径。已向用户询问是否允许复用现有重连流程，在空闲时重新加载助手并恢复原对话，或保持运行中不重载、新模型下次启动生效。未收到选择前不引入自动重载。
