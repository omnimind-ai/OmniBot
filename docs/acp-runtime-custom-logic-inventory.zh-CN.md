# ACP Runtime 自定义逻辑与配置归并审计

> **历史归档（2026-09-05）**：下文是修改前的审计快照，其中问题与建议不代表当前代码仍然如此。保留原文用于追溯；当前实现与逐次回归记录见 [最新审阅记录](acp-runtime-custom-logic-inventory.zh-CN_副本.md)，本分支交付范围见 [0.6.1 PR 汇总](pr-0.6.1-acp-ux.zh-CN.md)。

> 审计日期：2026-09-04
>
> 审计范围：`app/`、`ui/`、`baselib/` 中 Agent/ACP/Harness/Provider/工具/会话配置相关实现
>
> 审计性质：只读审计文档；本轮不删除、不重写、不移动业务代码。
>
> 当前工作树：`codex/release-0.6.1`，工作树已有未提交改动；本文档不把这些改动视为已经完成的清理。

## 1. 审计结论

当前版本已经完成一部分重要收敛：模型轮数、完成 token 上限、上下文恢复轮数、工具结果截断配置、文件工具默认限制等，不再作为 `AgentRuntimeSettings` 统一注入 Agent 主循环；Harness 也已经有了资产目录和每个 Harness 的配置转换器。

但“自定义逻辑”并没有清干净。剩余内容主要不是一处配置，而是五类不同性质的实现混在一起：

1. ACP 必须的宿主生命周期与身份归属逻辑。
2. Provider/模型协议兼容逻辑。
3. Harness 安装、探活、配置文件转换逻辑。
4. 我们自己的产品策略、资源上限、展示压缩、重试和安全策略。
5. 已经独立存在的远程 Codex、Web 入口、旧事件和 UI 特判链。

因此不能把所有配置简单合成一个 JSON，也不能把所有 Kotlin 代码都删除。正确方向是：

> 先按生命周期归属拆分，再把同一归属下的配置统一；官方 ACP 负责 Agent 生命周期，Provider 负责模型请求契约，Harness 负责自身安装与配置转换，产品工具负责自己的领域状态。

当前最值得优先处理的不是再挪动一个常量，而是：

- 删除或隔离远程 Codex 的第二套生命周期。
- 删除或隔离私有 `acp/presentation` 展示协议和 UI 重试推断。
- 消除 Harness 安装/探活/路径在 `EnvironmentSetupLogic`、`AgentRuntimeManager`、Harness 常量和 JSON 之间的重复。
- 把会话历史展示压缩、工具结果截断、浏览器风控、子 Agent 白名单等产品策略从 ACP 主链中剥离。
- 形成一个有版本、有类型、有校验、有审计的 Agent 配置模型；但不把 Provider、任务调度、浏览器、记忆、媒体等不同领域硬塞进去。

## 2. 顶至下的判断模型

长期规则规定，所有 Agent 工作都必须从下面的单一生命周期开始：

```text
Conversation
  └── ACP Session
        └── Turn
              └── Item
                    ├── assistant message
                    ├── tool call / tool result
                    ├── approval / input request
                    └── official update / terminal result
```

每段代码先回答四个问题：

| 问题 | 判断标准 |
|---|---|
| 它属于哪个身份？ | `conversationId`、`sessionId`、`turnId`、`messageId`、`toolCallId` 必须明确。 |
| 它属于哪个生命周期？ | 只能扩展已有 owner，不能另建 `turn/*`、回调总线、Reducer 或重试状态机。 |
| 它是协议必需、传输必需还是产品策略？ | 协议/传输可保留；产品策略不能伪装成 ACP 状态。 |
| 它的配置由谁拥有？ | Catalog、Provider、Harness、Conversation、Tool domain、Task domain 各自负责，不建立万能配置桶。 |

### 2.1 允许保留的最薄自定义边界

- 官方 ACP payload 到内部统一模型的兼容转换。
- 每个 Harness 的官方配置文件转换器。
- Provider wire API 的请求/响应适配。
- Android 进程、权限、文件系统、Room 等宿主实现细节。
- 旧数据一次性迁移，并立即进入同一 canonical reducer。

### 2.2 默认应被删除或下沉的逻辑

- 本地决定 Agent 什么时候完成、失败、重试或继续。
- 本地模型轮数、工具调用次数、上下文恢复次数。
- 用时间沉默推断 Agent turn 已完成。
- 用 UI snapshot、文本变化、延迟或坐标猜测 turn 归属。
- 为了“展示好看”而修改 ACP durable item 的内容。
- 在多个层级重复安装、探活、路径、Provider fallback。
- 把远程 Codex 或某个厂商的特殊事件当成通用 ACP 生命周期。

## 3. 全量清单：会话与 ACP 生命周期

### 3.1 必须保留的 canonical owner

| 位置 | 现有逻辑 | 性质 | 结论 |
|---|---|---|---|
| `app/src/main/java/cn/com/omnimind/bot/agent/runtime/LocalAcpRuntime.kt` | 维护 `sessionId`、主机 prompt reservation、turn 归属、session/update 关联、取消/关闭和进程生命周期 | ACP 宿主必要逻辑 | 保留。它不是第二个 Agent 生命周期，而是避免并行请求和无 wire `turnId` 时的归属竞态。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeManager.kt` | 选择 conversation/session/selected agent，登记活动 turn，处理 session/prompt、session/load/resume/list/close/delete 等入口 | canonical runtime owner | 保留并继续瘦身。不得让 UI、远程分支或 Harness 自己完成同样的状态转移。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentSessionBindingRepository.kt` | `conversationId ↔ thread/session` 持久绑定，防止 session 被错误转移到另一个 conversation | 身份与持久化一致性 | 保留。它属于 Conversation/Session 关系，不是业务策略。 |
| `ui/lib/services/agent_event_reducer.dart` | 唯一事件排序、合并、去重、stale event 隔离、历史投影 | canonical projection | 保留。任何新事件都只能进入这里。 |
| `ui/lib/services/chat_conversation_runtime_coordinator.dart` | conversation 切换、活动 session/turn 选择、运行快照同步 | UI runtime coordinator | 保留，但不得继续加入 provider 或 vendor 特判。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/conversation/AgentConversationHistoryRepository.kt` | Room 历史写入、分页和兼容 mode 查询 | durable history owner | 保留；分页实现细节不能被误删为“Agent 限制”。 |

这些组件共同实现的是 `Conversation → ACP Session → Turn → Item`，不是模型的“轮数限制”。删除它们会导致重复消息、late event 写入错误 conversation、取消错位和历史丢失。

### 3.2 必须保留但只能停留在兼容边界的逻辑

| 位置 | 现有逻辑 | 问题 | 处理建议 |
|---|---|---|---|
| `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpLegacyCompatibilityAdapter.kt` | `thread/start`、`thread/resume`、`thread/read`、`thread/list`、`thread/archive`、`turn/start` 等旧请求映射到 canonical ACP | 旧客户端仍可能调用 | 保留为唯一 legacy boundary；禁止新业务直接调用旧接口。 |
| `ui/lib/services/agent_event_reducer.dart` 中 `AcpLegacyEventAdapter` | 旧 `AgentStreamEvent`、dotted event name 和旧字段映射到 ACP update | 是兼容层，不是新协议 | 保留迁移期；记录来源；不允许新增 legacy event 类型。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpPromptInputCompatibilityAdapter.kt` | 旧 prompt/input 形状归一到官方 `session/prompt` | 输入兼容 | 保留，最终输出必须立即进入同一 prompt owner。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpSessionCompatibility.kt` | 旧 session 形态、Xiaowan alias、历史 mode 归一 | 数据迁移 | 保留一次性迁移，不应继续承载新行为。 |
| `baselib/.../AgentConversationHistoryRepository.kt` 中 `agent/codex/normal/acp/coding` mode 候选 | 历史数据兼容查询 | 旧数据读取 | 保留到迁移完成；新的 conversation 只写 canonical mode。 |

### 3.3 应删除或替换的第二生命周期

| 位置 | 现有逻辑 | 为什么是问题 | 建议 |
|---|---|---|---|
| `ui/lib/features/home/pages/chat/chat_page_remote_codex.dart` | 周期性 `Timer` 调用 `thread/read`，根据内容变化、grace period、active hint 推断远程 turn 是否仍在运行 | 通过 snapshot 和时间猜 lifecycle；形成 ACP 之外的活动判定 | P0。远程 Codex 要么实现为真正的 ACP transport/session，要么整个功能删除；不能继续由 UI 猜测。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/runtime/RemoteCodexAppServerSession.kt` | 远程 Codex app-server 的独立 workspace、初始化、prompt、取消和请求超时 | 与 Local ACP 形成第二套 runtime | P0。若产品不再要求远程 Codex，整组删除；若保留，隔离为明确的 `RemoteCodexTransportAdapter`，不得进入通用 Manager。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/runtime/RemoteCodexBridgeConnection.kt` | bridge websocket/HTTP 通信、启动和取消 | 独立传输链 | 同上。不能让 UI 直接感知 bridge 快照。 |
| `ui/lib/features/home/pages/chat/adapters/remote_codex_*` | 远程 thread、snapshot、content、history、turn identity 解析 | 远程私有数据模型和 reducer | 同远程功能成组处理，不单独修补。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeManager.kt` | `DEFAULT_CODEX_THREAD_SOURCE_KINDS`、Codex envelope、thread item、remote status 的通用解析 | 厂商协议泄漏到通用 runtime | 移到远程 Codex adapter；远程功能删除时一并删除。 |
| `ui/lib/features/home/pages/chat/chat_page_user_message_actions.dart`、`chat_page_conversation_flow.dart` | UI 手动 retry、continue、`agentRetrying` 元数据和重发路径 | 可能重复 logical turn 或重复副作用工具 | P0/P1。重试只能由一个 transport owner 在可见输出前执行；用户手动重试必须新建明确 turn，不得伪装成同一 turn 的内部恢复。 |

### 3.4 私有展示协议与 reducer 扩展

| 位置 | 现有逻辑 | 性质 | 建议 |
|---|---|---|---|
| `ui/lib/services/acp_extension_registry.dart` | `cncomomnimindagent`、`omnimindagent`、`acppresentation` namespace，以及 usage/reasoning/tool/artifact/compaction/retry/media 等 alias | 私有 presentation extension | P0/P1。官方 ACP `_meta` 或 MCP/plugin capability 能表达的，改为官方字段；不能表达的，放到明确的 provider adapter，禁止成为通用 lifecycle。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/XiaowanAcpConnection.kt` | `acpPresentationMeta`、retry/reasoning/card 等自定义展示事件 | 厂商/内置 Agent 特化 | 保留官方 ACP item/update 投影；删除“为了 UI 方便而发第二事件”的路径。 |
| `ui/lib/services/agent_event_reducer.dart` | `agentRetryCount`、`agentMaxRetries`、`agentRetryDelayMs`、`agentRetryReason` 等私有 presentation 字段 | 把产品策略暴露成 ACP 状态 | 删除或只在 adapter 内部转换；UI 只显示官方 update/error/cancel 状态。 |

## 4. 全量清单：模型请求与 Provider 逻辑

### 4.1 可以保留，但必须归 Provider 所有

| 位置 | 现有逻辑 | 结论 |
|---|---|---|
| `baselib/src/main/java/cn/com/omnimind/baselib/llm/ModelProviderConfigStore.kt` | Provider profile、base URL、wire API、headers、revision、官方 profile seed、旧配置迁移 | 保留一个 Provider metadata owner；不能与 ACP Agent profile 混成一个对象。 |
| `baselib/.../ModelProviderSecretStore.kt` | API key/custom header 的 Keystore 加密存储 | 保留；所有 token 必须在加密 secret store，不得回退到普通 SharedPreferences/MMKV。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentDispatchConfiguration.kt` | `scene.dispatch.model` 的 Provider/profile/model 解析 | 保留为 Provider resolution owner；禁止 Agent Manager 再复制一份 model fallback。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/AgentProviderRequestPolicy.kt` | wire API、tool choice、reasoning、图片输入等请求契约兼容 | 保留，但命名和边界应明确为 Provider request adapter，不得解释为 ACP session policy。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/llm/AgentLlmClient.kt` | OpenAI/Anthropic/Responses 等 wire 请求、流解析、401 platform token refresh、未输出前的传输重试 | 保留必要的 Provider transport 逻辑；重试 owner 只能是这里。 |

### 4.2 仍然是我们的策略，不能继续藏在 Provider 通用层

| 位置 | 现有逻辑 | 结论 |
|---|---|---|
| `AgentLlmClient.kt` | Provider 拒绝 thinking 后切换参数、拒绝 image 后去掉图片、模型不支持时切换候选模型、reasoning leak 后换 variant | 这些是兼容策略，不是 ACP；每个 fallback 都应有 Provider capability 依据。若官方 SDK/Provider 已负责，应删除本地 fallback。 |
| `AgentLlmClient.kt`、`AgentTurnTimingPolicy.kt` | `PROVIDER_STREAM_IDLE_TIMEOUT_MS = 90_000` 及 idle watchdog | 不是 Agent turn 完成判定，但仍是宿主 watchdog。应下沉给 Provider transport 或设为明确的 transport capability；不能叫 ACP 超时。 |
| `AgentLlmStreamAccumulator.kt` | `<think>` 标签识别、reasoning leak 检测、首段 buffer 900 字符/6 chunks、日志 preview 500 | 供应商输出兼容和展示优化；不属于通用 ACP。应按 Provider adapter 拆分，删除与官方 stream 已重复的判断。 |
| `AgentConversationContextCompactor.kt` | 用户显式 `/compact` 的历史整理提示 | 不再有默认 token threshold、reserve 或自动 compact；该入口不参与正常 Agent prompt loop。 |
| `AgentOrchestrator.kt` | Provider 溢出直接结束当前 prompt；截断 tool call 回传错误 | 不再识别 overflow 后替换历史或重试，也不再有无进展 fingerprint。保留的 tool-call 配对必须不重放副作用。 |

### 4.3 当前明确存在的风险

1. Provider fallback 会改变请求内容，但 UI 可能只看到同一 logical turn；这必须保持 transport detail，不得再次触发用户可见的 turn/retry。
2. `AgentOrchestrator` 仍识别 `length` 并自动追加上下文/继续消息；这不是“16 轮上限”，但仍是宿主决定模型行为的逻辑，需要单独审查。
3. `AgentRuntimeErrorSupport.kt` 将一些错误统一归为 retryable；必须确认不会被 UI 重新发起同一副作用 turn。

## 5. 全量清单：Harness、安装、探活和转换器

### 5.1 正确方向：Catalog + 每个 Harness 一个转换器

| 位置 | 现有逻辑 | 结论 |
|---|---|---|
| `app/src/main/assets/acp/agents.json` | Agent id/name/command/arguments、发现命令、npm 包、安装命令、健康命令、配置 adapter id、能力声明、准备 revision | 保留为 declarative catalog；这是删除 Kotlin vendor 分支的基础。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpAgentCatalog.kt` | 读取、版本校验、重复 id 校验、解析 runtime descriptor | 保留为 catalog interpreter；不要在这里加入 lifecycle 分支。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpHarnessConfigAdapters.kt` | DeepSeek/Codex/Claude/Kimi/OpenCode 的配置读写转换 | 保留。用户已确认“每个 Harness 一套配置转换器”；转换器本身不能删除。 |
| `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpHarnessAdapters.kt` | 每个 Harness 的 capability seam、`forConfigAdapterId` | 保留；最终所有调用方应按 catalog descriptor 解析，不再按硬编码 agent id 查找。 |

### 5.2 重复实现，优先归并

| 位置 | 重复内容 | 处理 |
|---|---|---|
| `com/ai/assistance/operit/terminal/setup/EnvironmentSetupLogic.kt` | packageDefinitions、NPM agent package ids、DeepSeek check/version/health command、安装依赖分类 | P0。只保留通用 terminal package 管理；Harness 内容全部由 `agents.json` 的 runtime descriptor 生成。 |
| `AgentRuntimeManager.kt` | Codex/Claude/OpenCode id、配置文件路径、markers、managed probe/install timeout、config file size | P0。路径/命令/准备信息来自 catalog；Manager 只调用统一 preparation service。 |
| `AcpAgentProfileStore.kt` | Xiaowan/Codex/Kimi/DeepSeek id、DeepSeek Cordis path、默认 Agent id | P1。默认 id、alias、路径从 catalog/migration map 读取；只保留旧 alias 迁移。 |
| `KimiCodeRuntime.kt` | Kimi 包、home、命令、健康检查和环境变量 | P1。安装信息放 catalog；Provider 环境映射留在 Kimi converter。 |
| `AcpHarnessAdapters.kt` | DeepSeek config home、settings path、ACP fs compat 脚本、reasoning/permission mode 集合 | P1。路径和脚本可由 catalog asset 提供；reasoning/permission 只能来自官方 capability negotiation。 |
| `plugin/official/agentweb/AgentWebRuntimeModels.kt` | Kimi/DeepSeek Web command、session id、readiness timeout、DeepSeek private patch path | P1。若 Web surface 已被长期规则移除，整组删除；若仍要支持，单独作为 plugin capability，不能进入 ACP Agent lifecycle。 |

### 5.3 Harness 目录仍有的硬编码

- DeepSeek：`/root/.dsh/omnibot-acp`、`dsh-acp-android`、Cordis plugin bundle、node-pty/build prerequisites、filesystem compatibility script。
- Kimi：Node >= 22.19、`KIMI_CODE_NO_AUTO_UPDATE`、`KIMI_DISABLE_TELEMETRY`、Kimi provider host/reasoning values。
- Claude：`claude-agent-acp`、Anthropic adapter package 和 settings path。
- Codex：`codex-acp`、Codex config/auth/model catalog 文件以及固定 catalog metadata。
- OpenCode：`opencode acp`、Linux ARM64 musl fallback、symlink 安装流程。

这些内容不必全部“变成用户配置”。正确分类是：

- 安装/发现/路径/官方包：catalog。
- 官方配置文件格式：该 Harness converter。
- Provider endpoint/model/key：Provider store + provider adapter。
- 会话/turn：canonical ACP runtime。

## 6. 全量清单：Agent 主循环、工具和资源策略

### 6.1 已经删掉的策略，确认不应恢复

以下策略来自 `2f197281f`（2026-08-30），随后在当前工作树移除：

- `maxModelRounds = 16` 及 `AgentOrchestrator` 的 16 轮硬停。
- `maxCompletionTokens = 16384` 的 Agent 默认注入。
- `maxContextOverflowRecoveryRounds`。
- `maxLengthContinuationRounds`。
- `maxMissingToolCallRecoveryRounds`。
- `maxToolResultChars` 与本地 tool-result compaction 参数。
- `maxVlmSteps`、`maxVlmRejectedRetries`。
- `fileReadMaxChars`、`fileListMaxDepth`、`browserMaxTabs` 等 Agent runtime settings。
- 省略 terminal timeout 时强行注入 30 秒。
- `AgentToolVisibilitySelector`、skill frontmatter `tool-routing` 及其恒等的工具目录选择路径。
- 直接 Agent 的 `file_read -> read`、`terminal_execute -> bash` 等模型工具别名和描述重写。
- 子 Agent profile 的工具白名单、递归/shell/privileged/file-delete 黑名单及专用 Catalog view。
- `subagent_dispatch` schema 中“主动分派”的模型指令；分派现在只在用户明确要求时发生。

这些确实是我们自己的运行策略，不是 ACP 限制。官方 ACP 定义 prompt turn、tool call、update 和 cancellation，但没有要求宿主必须设置 16 轮、工具字符数或模型完成 token 上限。参见官方 [Prompt Turn](https://agentclientprotocol.com/protocol/v1/prompt-turn)、[Tool Calls](https://agentclientprotocol.com/protocol/v1/tool-calls)、[Request Cancellation](https://agentclientprotocol.com/rfds/request-cancellation)。

### 6.2 仍存在的主循环策略

| 位置 | 逻辑 | 性质 | 建议 |
|---|---|---|---|
| `AgentOrchestrator.kt` | Provider context overflow | 官方/Provider 终态错误 | 已不再自动 compact 或重试；保留原历史并结束当前 prompt。 |
| `AgentOrchestrator.kt` | `length` 后自动追加 continuation message | 本地行为策略 | 需要删除或改为 Provider/ACP 明确的 continuation；至少不能有隐藏轮数计数。 |
| `AgentOrchestrator.kt` | 截断 tool call 不执行，写 tool error 再交给模型重发 | 协议一致性 + 本地恢复 | 若官方 ACP/Provider 已给出完整/不完整状态，优先使用官方结果；保留时必须证明不会重放副作用。 |
| `AgentOrchestrator.kt` | skill completion pending 时再次要求完成工具 | 产品级 skill 策略 | 不属于 ACP；建议移入 skill runtime，并让 skill 明确声明 completion contract。 |
| `AgentToolConcurrencyPolicy.kt` | 文件/查询/记忆/skills 只读工具并行；终端/权限/写操作串行；browser 只有 get_text/screenshot 可并行 | 我们自己的并发策略 | 不应由静态 whitelist 决定所有工具；改为 tool capability 的 `concurrency`/side-effect 声明，或由官方 MCP/tool server 管理。 |
| `SubagentToolHandler.kt`、`SubagentDispatcher.kt` | 显式 concurrency 或本次 task 数；Semaphore | 执行请求参数 | 已移除默认 2 和 1–6 上限。省略时并行度等于本次任务数；只拒绝非正参数。 |
| `SubagentProfile.kt` | general/explorer/memory-curator/planner 的可选任务提示 | 产品提示 | 不再拥有工具权限；子 Agent 直接继承父 harness 已协商的目录。 |
| `AgentConversationModePolicy.kt` | `normal`、`agent`、`subagent`、`chat_only`；仅 chat_only 为空工具目录 | 用户选择的产品模式 | Agent-capable mode 不再以模式名过滤工具；能力完全由 active harness catalog 表达。 |

### 6.3 工具输入 schema 里仍然存在的默认值/上限

`app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolDefinitions.kt` 仍需单独清理以下模型可见策略：

- `context_apps_query` 的 `limit` 为可选提示；省略时运行时返回完整查询结果，不设宿主静态上限。
- browser 工具描述中的最多 3 个 tab。
- file list 的 `maxDepth` 与 `limit` 都是可选提示；省略时不设宿主静态深度或条目上限。
- skills list 默认 50、范围 1–200。
- subagent concurrency 是可选请求参数；省略时使用本次 task 数，不设宿主上限。
- image generation 默认 `gpt-image-2`、png/webp/jpeg 枚举和默认质量/尺寸。

即使 Kotlin runtime 不再读取旧 `AgentRuntimeSettings`，schema 默认值仍然会影响模型决策。若目标是“不由我们规定 Agent 策略”，这些值必须改成可选，由执行环境或官方工具 server 决定；真正的 IPC、heap、文件系统和网络安全边界则不能简单删除。

## 7. 全量清单：历史、展示、存储和压缩

### 7.1 会话历史压缩是当前最大的隐藏策略包

`app/src/main/java/cn/com/omnimind/bot/agent/conversation/AgentConversationHistorySupport.kt` 当前集中定义：

- tool summary 240 字符、preview 800、terminal 1200。
- display inline 600、tool JSON 2 KiB、terminal/thinking 8 KiB、card JSON 64 KiB。
- display list 8 项。
- storage entry payload 32 KiB、summary 2 KiB、tool JSON 4 KiB、tool terminal 8 KiB、message text 24 KiB。
- 对 JSON、reasoning、tool event、card、attachment 进行 trim、head/tail compact 和 `_omittedItems` 注入。

这不是 ACP 官方限制，而是我们自己的“历史可展示/可存储”策略。它会改变 durable history 的内容，可能造成：

- Agent 后续恢复拿不到完整 tool result。
- 用户看到的历史不等于 ACP item。
- 同一 payload 在 runtime、history、UI 经过多次截断。
- 大型工具输出被当成摘要后无法重新获取。

建议的最终模型：

```text
ACP Item / artifact 原文
        ├── durable storage：保存完整引用或完整内容
        ├── UI projection：按屏幕需要分页/折叠
        └── Agent prompt：按 Provider context capability 选择内容
```

因此 `AgentConversationHistorySupport` 不应继续承担 display、storage、prompt 三套策略。应拆成 history codec、artifact reference 和 UI projection；如果底层 Room/IPC 真的需要限制，应由底层 transport/storage 明确负责，并且不能静默丢失原文。

### 7.2 其他历史/结果截断

| 位置 | 逻辑 | 结论 |
|---|---|---|
| `AgentEventAdapter.kt` | `compactToolResultContent(maxChars)` head/tail 压缩 | 与 history support 重复；删掉通用截断，改为 artifact/reference。 |
| `agent/tool/handlers/SharedHelper.kt` | 通用文本和 terminal tail 截断，错误 preview 120 字符 | 重复策略；合并到唯一 projection 层或删除。 |
| `BrowserUseEngine.kt` | JS 返回 text 4,000、array 10/20 项、content 4,000、find elements 60 | 浏览器工具自己的输出投影；不应混进 Agent history。由 browser server/artifact storage 管。 |
| `WorkspaceMemoryService.kt` | memory summary 120/140、embedding input 8,000、错误 320、结果 items 220 等 | 记忆领域策略，不能假装是 ACP。改由 memory service 自己的 schema/Provider capability 管理。 |
| `SelfImprovingSkillFailureHook.kt` | error field 2,000、guidance 1,200、error entries 80 等 | skill 自诊断日志策略；移出 ACP runtime，可保留在 skill domain。 |

## 8. 全量清单：文件、附件、图片和浏览器

### 8.1 文件和附件

| 位置 | 逻辑 | 结论 |
|---|---|---|
| `AgentWorkspaceAttachmentSupport.kt` | 单文件 20 MiB、单轮 8 个、批量 64 MiB，读取前校验 size | 这是宿主 heap/IPC/network 资源边界，不是 ACP 语义。不能为了“无上限”直接删除；应下沉到上传/附件 transport，采用流式文件引用。 |
| `AgentImageAttachmentSupport.kt` | model scale 0.75/quality 92，preview scale 0.35/quality 80 | 图片传输/预览策略；交给 Provider/media capability，不能在 Agent lifecycle 中决定。 |
| `FileToolHandler.kt` | 现在大部分 maxChars/maxDepth/limit 由调用参数显式提供，省略时不再注入旧 runtime 默认 | 方向正确；仍需检查 tool schema 默认值和底层输出是否重复截断。 |
| `AgentWorkspaceManager.kt` | `/workspace`、`omnibot://`、attachments/offloads/browser/skills/memory 等目录 | 这是宿主 workspace contract，不应删除；路径属于 workspace provider，而不是 Agent 用户配置。 |

### 8.2 浏览器自定义实现

| 位置 | 逻辑 | 结论 |
|---|---|---|
| `BrowserUseEngine.kt` | Android WebView tab/session、导航、点击、输入、截图、下载、userscript、桌面 UA、viewport、settle delay、输出 preview | 大型自研 browser runtime。优先迁移到官方 browser/MCP tool；迁移前不要拆散删除。 |
| `BrowserRiskControl.kt` | Google/Bing/DDG/Yahoo/Baidu/Yandex/Ecosia/Brave/Sogou host 列表；550/180/260/140/320ms throttle；429/403/Captcha/Cloudflare 检测；停止自动重试 | 明确是我们自己的 anti-abuse/risk-control，不是 ACP。若产品不要求，P1 删除；若要求，独立成安全模块，不作为 Agent 配置。 |
| `BrowserHostStore.kt` | bookmarks/history/downloads/userscripts/desktop mode，历史最多 500 条 | 浏览器领域状态；移到 Browser store。500 是存储保留策略，不是 Agent 限制。 |
| `AgentWebRuntimeModels.kt`/`AgentWebRuntimeManager.kt` | Kimi/DeepSeek Web server launch、snapshot、open browser | 长期规则已经移除 standalone external App/WebView launcher/desktop shortcut/window bridge；需确认这组 plugin 是否仍是必要能力。若不是，整组删除；不能继续扩展。 |

## 9. 全量清单：记忆、技能、调度和领域工具

这些不是 ACP runtime 配置，不能和 Agent profile 合并；但其中仍有自定义策略。

| 领域 | 位置 | 现有自定义逻辑 | 处理建议 |
|---|---|---|---|
| 记忆 | `WorkspaceMemoryService.kt`、`MemoryToolHandler.kt`、`MemoryIndex.kt`、`PlatformEmbeddingGateway.kt` | embedding/rollup 开关；rollup 每次最多 8 个候选；memory 查询的 `limit` 可选、省略时不设宿主静态上限；embedding input 8,000、vector 8,192、response 2 MiB；自动夜间 rollup | 保留 memory 功能但独立；配置归到 typed `MemoryConfig`，embedding/rollup 的 Provider 解析归 Provider。不要放进 ACP Agent profile。 |
| 技能 | `AgentSkillRuntime.kt` | builtin/official/user 三来源；GitHub/CNB 固定仓库；registry 文件；退休 `hatch-pet`；skill id regex；安装/删除状态 | 技能目录应是外部/资产 catalog；固定仓库 URL 不应散落 Kotlin。技能生命周期不等于 ACP turn。 |
| 技能自诊断 | `SelfImprovingSkillFailureHook.kt` | 自动写 `ERRORS.md`、失败摘要、引导和历史条目限制 | 若长期规则允许自改进，保留为 skill domain；从 Agent 主循环移出，不让它改变 ACP 完成状态。 |
| 子 Agent | `SubagentProfile.kt`、`SubagentDispatcher.kt`、`SubagentToolHandler.kt` | 固定 persona/profile；并发由请求参数或本次 task 数决定 | profile 仅提供任务提示；子 Agent 直接继承父 harness 的能力目录，不维护第二份工具白名单或 Catalog adapter。 |
| 计划任务 | `WorkspaceScheduledTaskScheduler.kt` | Native SharedPreferences 与 FlutterSharedPreferences 双写/迁移；scheduled task mode；时间解析和 fallback | 合并成一个 Task scheduler store；不要并入 ACP config。任务触发后再创建 canonical conversation/turn。 |
| 任务生命周期 | `TaskRuntimeSettings.kt`、`TaskRuntime.kt` | foreground service lease、wake lock、防休眠、完成通知、active task count | Android 生命周期必要逻辑；不属于 Agent turn；可统一 Task domain 存储，但不能删除为“Agent 限制”。 |
| 闹钟 | `AgentAlarmToolService.kt` | MMKV 音效/精确闹钟记录；预提醒 5 分钟；snooze 默认 5 分钟 | 领域行为；迁入 Alarm store，不能和 ACP profile 混合。 |
| 日历 | `AgentCalendarToolService.kt` | 列表默认 50、最大 200 | API pagination 策略；由 Calendar service/backend 管。 |
| 音乐 | `AgentMusicToolService.kt`、`AgentMusicPlaybackService.kt` | 播放状态、backend、MediaSession、通知 channel/id | Android media lifecycle；保留并独立。 |
| 图像生成 | `ImageGenerationToolHandler.kt`、`app/build.gradle.kts` | bundled provider、固定 gateway、`gpt-image-2`、20/32 MiB、prompt 64 KiB、endpoint suffix | Provider/tool 配置重复。移到 Provider catalog + encrypted secret + image tool capability；固定 gateway 不应同时存在 BuildConfig 和 handler。 |

## 10. 配置到底能不能挪到一起

### 10.1 应该合并的部分：ACP Agent 配置

推荐形成一个明确的 `AcpAgentConfiguration` 领域，但不是一个万能 JSON：

```text
AcpAgentCatalog（只读、应用/插件提供）
  ├── identity: id/name/aliases/retired
  ├── launch: command/args/discovery
  ├── preparation: packages/install/health/revision
  ├── configAdapterId
  ├── capabilities
  └── embedded/sharedProvider

AcpAgentProfileOverride（用户可改）
  ├── enabled/displayName
  ├── launch overrides（仅允许 catalog 声明的字段）
  ├── providerProfileId/model binding
  ├── reasoning/permission selection（必须经过 capability validation）
  └── schemaVersion/revision/updatedAt

AcpAgentRuntimeState（运行态）
  ├── sessionId
  ├── conversation bindings
  ├── health/probe result
  └── preparation status

AcpAgentConfigAudit（审计）
  ├── revision
  ├── actor/source
  ├── before/after snapshot
  └── timestamp
```

这四部分可以由一个 `AcpAgentConfigurationRepository` 统一读写，但内部仍需区分 catalog、用户 override、runtime state 和 audit。当前 `AcpAgentProfileStore` 把 profile、selected id、session binding、conversation binding、health 放在一个普通 `SharedPreferences` JSON bucket，配置审计又在第二个加密 prefs 文件；这正是需要统一 owner 和 typed schema 的地方。

### 10.2 不应合并的部分

| 配置/状态 | 不能并入 ACP Agent 配置的原因 |
|---|---|
| Provider profile/secret | Provider 决定模型请求契约；secret 必须独立加密。 |
| Conversation history | durable user-visible source of truth，不是 profile setting。 |
| session/turn runtime state | 短生命周期、并发变化，不能当用户配置持久化。 |
| Browser bookmarks/history/downloads | 浏览器领域状态。 |
| Memory embedding/rollup | 记忆领域和 Provider capability。 |
| Task foreground/wake-lock/notification | Android 任务生命周期。 |
| Alarm/calendar/music | 各自领域的系统 API 状态。 |
| Build profile | 构建时包装/插件选择，不应通过 Agent runtime 读取。 |
| Plugin catalog | MCP/plugin 能力目录，不等于 Harness profile。 |

### 10.3 存储层建议

目标不是“全部改成一种数据库”，而是消除同一领域多处真相：

- ACP Agent config：一个 typed versioned repository；metadata/override 可用 DataStore/Room，secret 用 Android Keystore-backed encrypted store。
- Provider：一个 metadata repository + 一个 encrypted secret repository；逐步清理 MMKV plaintext legacy key。
- Conversation/ACP item：Room + artifact reference；不要把完整 item 再复制到 MMKV/SharedPreferences。
- Task/Browser/Memory/Alarm/Calendar/Media：各自一个 domain owner。
- Flutter：只调用 native/domain service，不直接读写 `FlutterSharedPreferences` 作为另一份业务真相。

## 11. 构建 profile 与 BuildConfig 的归并建议

`app/build.gradle.kts` 当前同时维护：

- `OMNIBOT_PROFILE=main/investor`。
- `develop/production` flavor。
- `IMAGE_BASE_URL/IMAGE_MODEL/IMAGE_API_KEY`。
- LLMTHU base/model/key。
- plugin catalog filtering。
- packaged OmniFlow fallback。

判断如下：

1. `develop/production` 是 Android 发布环境，保留在 Gradle。
2. `main/investor` 当前主要决定插件资产过滤，可以保留为 build packaging profile。
3. Image provider 的 URL/model/API key 不应继续同时存在 Gradle BuildConfig 和 `ImageGenerationToolHandler`；应统一进入 Provider/tool catalog。API key 不应进入 BuildConfig。
4. Build profile 不应进入 `AgentRuntimeManager` 或 `AgentOrchestrator`，也不应改变 ACP lifecycle。
5. 插件是否可用应由生成后的 plugin catalog/capability 决定，而不是 Agent 代码写 `if (profile == investor)`。

## 12. 建议的删除顺序，但本轮不执行

### P0：先处理第二生命周期和明显重复

1. 冻结远程 Codex 新功能；决定删除整组还是隔离为 transport adapter。
2. 删除 UI 根据 snapshot/时间推断活动 turn 的逻辑。
3. 将 `EnvironmentSetupLogic`、`AgentRuntimeManager` 的 Harness 安装/探活/路径读取统一到 catalog preparation service。
4. 把远程 Codex envelope/thread parser 移出通用 Manager。
5. 审查私有 presentation/retry 字段，停止新增。

### P1：去掉隐藏的 Agent 策略

1. 拆分/删除 `AgentConversationHistorySupport` 的 storage/display/tool 多重截断。
2. 删除 `AgentEventAdapter`、`SharedHelper` 的重复 tool result truncation。
3. 已移除 `AgentOrchestrator` 的 context overflow 自动恢复；继续审查剩余 tool-call 配对行为是否会重放副作用。
4. 将静态 `AgentToolConcurrencyPolicy` 改成 capability-driven。
5. 将 subagent concurrency、固定 profile 和工具禁用改成明确 capability/permission；在用户确认安全边界前不直接删除。
6. 将 AgentToolDefinitions 中的模型可见默认 limit 改成 optional/environment-owned。

### P2：清理配置和领域重复

1. 建立 typed/versioned `AcpAgentConfigurationRepository`。
2. 合并 Agent profile 的普通 prefs 和加密 audit/snapshot 的 owner，保留 secret 独立加密。
3. 删除 `CodexRemoteBridgeConfigStore` 普通 SharedPreferences 中的明文 auth token，或随远程功能删除。
4. 统一 image provider 的 BuildConfig/handler/provider store。
5. 合并 Flutter/native scheduled task 双写。
6. 将 Prompt/SOUL 配置与 Agent profile 建立明确关系；去掉默认 SOUL 中不属于 ACP 的产品政策，或把它标记为用户可改的 system prompt 配置。

## 13. 删除前必须通过的验收条件

每个候选删除项都必须从以下链路验证，而不是只看编译：

1. request admission：请求是否进入唯一 runtime owner。
2. session selection：是否选中正确 `conversationId/sessionId`。
3. prompt reservation：一个用户发送是否只有一个 logical `turnId`。
4. update projection：官方 update 是否只经过一个 reducer。
5. tool/approval：tool call、permission/input request 是否仍能闭环。
6. completion/error/cancel：只由官方 ACP response/error/cancellation 结束。
7. history commit：用户消息和 ACP item 是否完整、幂等地写入 Room。
8. reconnect/late event：旧 session 的迟到事件不能污染新 conversation。
9. conversation switch：切换后不能把旧 turn 的 snapshot 写到新目标。
10. provider fallback：未输出前可以在 transport owner 内重试；输出开始后不能静默换路或重放。

任何删除导致上述任一项需要 UI 猜测、文本匹配、时间窗口、第二 reducer 或第二 retry owner 才能恢复，说明删除位置不对，应继续向下沉到正确 owner，而不是恢复私有生命周期。

## 14. 本文档对应的来源与历史

本次审计通过代码、Git 历史和当前工作树交叉核对。关键来源：

| 提交 | 日期 | 引入内容 |
|---|---:|---|
| `2f197281f` | 2026-08-30 | 引入 `maxModelRounds=16`、完成 token 上限、轮数硬停、附件和 Provider 请求策略扩展。 |
| `5517380aa` | 2026-08-13 | 引入上下文压缩、overflow recovery、自改进 skill 失败处理。 |
| `051eb5442` | 2026-09-02 | 引入 `AgentRuntimeSettings`、MMKV JSON 编辑器和更多运行时策略；当前工作树已开始移除。 |
| `f0844df43` | 2026-05-09 | 引入 BrowserRiskControl、挑战检测和浏览器节流。 |
| `e5962e7c2` | 2026-05-22 | 引入 Omnibot Codex Bridge、本地/远程 Codex 独立链。 |
| `e5ef76d63` | 2026-07-16 | 引入 Codex 文件附件上传链。 |
| `6f15cd552` | 2026-09-03 | 引入 Kimi/DeepSeek Web plugin 运行时。 |
| `602e46065` | 2026-08-17 | 将多个 Agent 统一到官方 ACP runtime，但仍保留若干 provider/vendor 分支。 |
| `245b131c8` | 2026-09-04 | 将 legacy request compatibility 独立成适配层。 |

## 15. 当前状态

- 本轮只新增本审计文档，未删除或修改业务代码。
- 文档中的“建议删除”不是已执行结果。
- 需要用户审阅并确认优先级后，下一轮才能按 P0/P1/P2 逐项出删除方案和影响面。
- 适配器不会因为“自定义”三个字被删除；只有违反 canonical ACP lifecycle、重复实现官方能力或承载无必要产品策略的部分，才进入删除/下沉候选。
