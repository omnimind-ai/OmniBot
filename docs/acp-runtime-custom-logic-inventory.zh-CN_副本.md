# ACP Runtime 最小重构结论

> 审阅日期：2026-09-04
>
> 当前分支：`codex/release-0.6.1`
>
> 范围：Agent/ACP/Harness/Provider/会话历史相关实现
>
> 原则：不再增加框架、协议、状态机、万能配置或应用层资源上限；只删除重复逻辑、数据截断和用户可见故障源。

## 1. 最终判断

### 长期产品要求（2026-09-05 补充）

减少用户使用成本，让 Agent 尽量承担完成任务所需的操作，而不是让用户研究并手工拼装每项能力。应用提供可用的基础运行环境、官方 ACP 会话接口和通用工具接入；具体能力优先来自 Harness 原生工具、MCP、插件及可安装命令，不要求每个能力都内置到 APK，也不为每个 Harness/tool 新增私有处理分支。默认路径尽量可用，高级配置可选，不能把“可插拔”变成用户必须承担的适配工作。

Agent 可在用户任务授权范围内使用现有执行与工具接口补齐能力；这不等于 ACP Client 在连接或发送消息时偷偷安装软件、切换 Provider、重放任务或增加另一套生命周期。遇到必须由用户提供的凭证、系统权限或环境不支持时，指出具体缺项，不用应用自设名单限制替代真实能力判断。本段是验收方向，不代表这些体验均已实现。

原思路中“统一到官方 ACP、减少私有逻辑、归档历史兼容”的方向正确，但不需要为了实现它再建设一套 PreparationService、动态 Harness 注册系统、插件 descriptor 平台、typed configuration 大仓库或新的 artifact 框架。

本次重构只解决用户真正能感知的问题：

- 消息重复、消失或写入错误会话；
- Agent 一直显示思考中，或已经结束却仍显示运行；
- 取消、重试后重复执行工具；
- 远程和本地 Agent 的行为不一致；
- 切换 Harness 时卡住、自动安装或探活阻塞聊天；
- 配置在多个位置互相覆盖，导致“明明配置了却不能用”；
- 为兼容某个 Harness 不断增加 UI 特判。

Harness 能否被自动安装、自动修复、自动识别不是 ACP 主链目标。一个 Harness 只需要满足：能启动官方 ACP 进程就连接；不能启动就快速、明确地报告失败。不要为了提高“启动成功率”在聊天生命周期里增加私有 fallback。

最终边界只有：

```text
Conversation -> ACP Session -> Prompt Turn -> Session Update / PromptResponse
```

官方 ACP 允许一个 prompt turn 内发生多次模型和工具交互，并由 `PromptResponse.stopReason` 结束；Client 不需要再发明一个模型轮数、重试轮数或完成状态。[Prompt Turn](https://agentclientprotocol.com/protocol/v1/prompt-turn)

## 2. 不做什么

本轮明确不新增：

- 新的 ACP facade、Agent state machine 或 reducer；
- 新的 Harness lifecycle、动态插件注册平台或自动安装框架；
- 新的统一 JSON 配置中心或配置数据库迁移；
- 新的 retry/recovery/presentation 私有协议；
- 为每个厂商新增 UI 页面、事件名或 snapshot mapper；
- 为了让旧远程 Codex 可用而继续增加时间窗口、内容 signature 或 active 推断；
- 为了“无限完成任务”而在 ACP Client/UI 层增加隐藏的 continuation/recovery 循环。

需要严格区分两类边界：

- 删除应用自己添加的“最大数量/最大字节/最大字符/最大深度/最大轮数/重试次数/活动推断等待窗口”等产品限制。工具输出、附件、历史、memory、浏览器结果和终端结果不应因为这些常量被截断、拒绝或静默丢弃；也不应新增一个更大的替代上限。
- 只保留协议和系统无法绕开的事实：JSON/ACP 类型与必填字段校验、路径权限和 workspace 隔离、Android/IPC/进程/堆内存实际失败、Provider 或 ACP Agent 自己返回的上下文/令牌限制，以及用户主动取消。它们不是 OpenOmniBot 自己定义的 Agent 资源配额。

因此，“工具资源硬上限”不属于应保留的安全边界，而是本轮明确删除的自定义逻辑。

## 3. 当前代码的真实状态

### 3.1 已经正确，保留

| 位置 | 结论 |
| --- | --- |
| `LocalAcpRuntime.kt` | 已使用官方 ACP SDK、`ClientSession`、`session/prompt`、`session/update`、`session/cancel` 和 `PromptResponse`。保留为本地 ACP owner。 |
| `AgentRuntimeManager.kt` | 已开始将本地和远程请求收敛到 `session/*`。保留路由职责，继续删除 vendor 业务逻辑。 |
| `AcpLegacyCompatibilityAdapter.kt` | 旧 `thread/*`/`turn/*` 映射到官方 `session/*`；`turn/steer` 已拒绝。保留为唯一迁移入口。 |
| `agent_event_reducer.dart` | 官方 update 和旧事件导入共用一个 reducer。保留，但禁止新的 vendor 分支进入。 |
| Provider stores/secret store | Provider、模型、密钥应继续独立，不与 ACP session 配置合并。 |
| per-Harness config converter | 只负责已有 Harness 配置文件格式。保留在边界，不再扩展生命周期能力。 |

### 3.2 当前最影响用户体验的问题

#### A. 远程 Codex 仍由 UI 猜生命周期

`ui/lib/features/home/pages/chat/chat_page_remote_codex.dart` 仍然：

- 每 2 秒读取一次 remote thread；
- 根据内容变化、grace period、`assumeActive` 和历史状态推断是否运行；
- 用 snapshot 主动结束 reducer 中的任务；
- 为 remote turn 生成另一套本地 task id。

这正是“偶尔一直思考、偶尔提前结束、切会话后状态错乱、消息闪动”的主要风险。它不是历史刷新，而是第二套生命周期。

#### B. 私有 presentation 字段仍可能影响状态

`XiaowanAcpConnection.acpPresentationMeta` 与 `AcpExtensionRegistry` 仍投影：

- retry/recovery；
- compaction；
- completion；
- usage/reasoning；
- 多组历史 alias。

`_meta` 可以保留为可忽略的展示提示，但不能决定 active、completed、failed、cancelled、retrying。生命周期必须只看官方 update、tool status 和 `PromptResponse.stopReason`。

#### C. 删除上限后要区分 Agent 内部循环和 Client 生命周期

工作树删除了 `AgentRuntimeSettings`、固定模型轮数，以及 context overflow 的自动摘要重试。当前 `AgentOrchestrator.kt` 不再为这些情况制造额外 prompt：

- `finish_reason=length` 后继续生成；
- context overflow 后结束当前 prompt，保留原始历史；
- skill 未调用完成工具时不断追加恢复提示；
- 截断 tool call 后让模型再次生成。

这些路径属于小万 ACP Agent 内部的模型/工具执行实现，本身不能因为“ACP Client 不需要轮数”而整体删除；否则 Agent 会失去完成工具任务的能力。真正需要删除的是宿主侧的次数配置、UI retry 状态和第二套生命周期。Agent 内部可以在返回 `PromptResponse` 前进行多次模型/工具交换，但必须在官方 stop reason、取消、真实错误或确定的无进展条件下结束。

#### D. Harness 安装和探活侵入正常聊天路径

`agents.json` 已经描述了启动命令和安装信息，但 `EnvironmentSetupLogic.kt`、`AgentRuntimeManager.kt`、`AcpHarnessAdapters.kt` 仍分别维护包、路径、health 和安装分支。

用户真正需要的是：选择后快速启动，失败时得到明确错误。正常切换不应触发 npm、node-gyp、长探活或自动修复；这些功能即使保留，也只能由用户在设置页显式触发，不能阻塞 `session/new` 或 `session/prompt`。

#### E. 资源限制需要按数据语义分类，不能一刀切

之所以审阅这些限制，是因为它们目前有些位于 ACP item、tool result 或会话历史的事实路径中：一旦在这里截断或拒绝，Agent 看到的结果、用户看到的历史和实际执行结果就不一致，容易触发错误重试、误判完成或消息丢失。这是用户体验和适配 bug 的来源，但不是 ACP 要求“无限数据”。

本轮只删除会破坏事实的自定义限制：

- `AgentWorkspaceAttachmentSupport.kt` 的单附件 20 MiB、附件数量 8、批量 64 MiB，以及对应的拒绝分支和 Base64 长度判断；附件交给 ACP/Provider/底层传输处理，真实失败原样返回。
- `AgentConversationHistorySupport.kt` 中写入 canonical history 或传回 Agent 的 tool result、terminal、message payload 的截断、只取尾部和超过上限即丢弃；不能让 `trimText`、`compactJsonText`、`take(n)`、`_omittedItems` 改写事实。
- `FileToolHandler.kt`、`TerminalToolHandler.kt`、`SkillsToolHandler.kt`、`PrivilegedToolHandler.kt`、`BrowserUseEngine.kt` 中宿主偷偷补充的默认 `maxChars`、`maxDepth`、结果数量和拒绝分支。工具调用者明确传入的可选参数仍可保留并原样透传。
- Agent Client 自己添加的轮数、retry quota、并发 quota 和“为了防止资源过多”而结束 prompt 的规则。

以下内容不因本节自动删除：

- 仅用于 UI 排版或轻量预览的 `maxLines`、`maxWidth`、标题缩略；它们不能写回 canonical history，也不能传回 Agent。
- `AgentConversationHistorySupport` 如果只是构造独立的展示摘要，可以保留摘要，但必须明确标为 preview，不能替代原始 ACP item。
- Provider/ACP Agent 返回的上下文或令牌限制、协议分页、传输取消 deadline、Android/IPC/堆内存实际失败，以及解压防护、路径权限等安全检查。
- memory/sandbox/日志等非 ACP 事实数据的索引摘要或分页；只有当它们被当作完整 Agent 输入时，才需要移除宿主截断并改为真实失败或明确的分页语义。

因此实现不是机械删除所有 `MAX_*` 或 `take(...)`，而是沿着“canonical ACP item / Agent 输入 / 用户历史”逐处检查：事实路径不得静默截断；展示路径可以有预览；下游真实失败必须原样返回。不需要新增 `ResourcePolicy`、QuotaService 或更大的替代上限。

这里的“删除上限”只针对数据和 Agent 业务策略，不是删除所有 UI 布局约束。`maxLines`、`maxWidth` 等纯排版属性可以保留；协议明确要求的分页参数也可以保留，但不得把一页当成完整数据。网络/IPC 的取消和传输 deadline 也可以保留用于避免界面永久卡住，不过超时必须作为真实 transport error 结束当前请求，不能被包装成 Agent 完成、资源超限或自动重试。

本节范围是 Agent/ACP 工具链和会话数据路径。应用包导入、解压防炸弹、权限提升等独立安全模块的限制不因本节自动删除；它们需要另做安全审阅，不能为了“无上限”而破坏宿主安全。

## 4. 最小代码修改

### P0：先消除卡死、重复和错会话

#### 4.1 删除 UI 的 remote activity inference

修改 `chat_page_remote_codex.dart`：

1. 删除 `_remoteCodexSessionSyncTimer` 的 2 秒生命周期 polling；
2. 删除 `_inferRemoteCodexSnapshotActive`、content signature、grace period 和 `assumeActive`；
3. `session/load` 只恢复历史，不改变当前 turn 的 active/terminal 状态；
4. 正在执行的 turn 只由 `session/update` 和原始 `session/prompt` 的返回值驱动；
5. 如果旧 bridge 无法重新附着正在运行的 turn，显示“无法恢复实时状态”，不要猜测；
6. 如果远程实现不能提供官方 ACP update/PromptResponse，暂时归档远程入口。

结果：UI 只展示 runtime 的事实，不再成为第二 owner。

本阶段已先完成一项低风险功能修复：远程会话打开时只执行一次 `session/load`/历史 hydration，停止 2 秒 `thread/read` 轮询；snapshot 不再主动结束当前 turn，也不再通过内容变化、grace period 或远程 task id 猜测生命周期。实时 active/completed/cancelled 状态继续由 ACP 事件和 PromptResponse 驱动。后续再根据真实远程 ACP 事件覆盖情况决定是否归档旧 snapshot mapper。

#### 4.2 私有 `_meta` 只允许展示

修改 `XiaowanAcpConnection.kt`、`acp_extension_registry.dart` 和 `agent_event_reducer.dart`：

- usage 使用官方 `usage_update` 或 `PromptResponse.usage`；
- reasoning 使用官方 `agent_thought_chunk`；
- plan 使用官方 `plan` update；
- tool 使用官方 `tool_call`/`tool_call_update`；
- completion/cancel/failure 只使用官方 prompt response、error 和 cancellation；
- 删除 reducer 中由 `retry`、`recovery`、`completion` presentation 修改 runtime 状态的分支；
- 未识别 `_meta` 原样保留给诊断或直接忽略，不转换成新的通用字段。

不必立即删除所有 `_meta`，只要删除它的生命周期权力。

#### 4.3 删除宿主侧的隐藏 continuation/recovery 状态

修改 `AgentOrchestrator.kt`：

- 删除宿主侧的 `maxModelRounds`、`maxLengthContinuationRounds`、`maxMissingToolCallRecoveryRounds` 等配置和 UI 状态；不要在 Client 再计一份轮数；
- 保留 Agent 内部为完成一次 ACP prompt 而进行的模型/工具交换，但所有交换都必须归属于同一个 `session/prompt` 和同一个 turn；
- Agent 不会因 `length` 或 context overflow 自动制造续传/摘要 prompt；收到 Provider 终态就结束当前官方 prompt。工具调用后的多次模型/工具交换仍由同一官方 prompt 承载；
- 不完整 tool call 不执行，重复相同请求/相同错误且没有进展时结束当前官方 prompt，不能无限生成；
- context compaction 仅保留为用户显式 `/compact` 的历史管理动作，不参与正在执行的 prompt；
- 用户主动继续时发送新的 `session/prompt`，形成新的 turn。

这样可以删除 Client 自己添加的生命周期状态，同时保留 Agent 完成任务所需的内部循环。ACP 已定义 `max_tokens`、`max_turn_requests`、`cancelled` 等 stop reason，应直接展示 Agent 返回的结果。

#### 4.4 保持一个重试 owner

- `AgentLlmClient` 只允许在首个可见输出前重试网络连接；
- 一旦出现文字、reasoning 或 tool call，就禁止切换 Provider、模型或 transport；
- UI 的“重试”是新的用户操作和新的 turn；
- 不得复用原 `turnId`，也不得重放已经执行的工具。

#### 4.5 删除会改写 ACP 事实的应用层硬上限

这一项是删除事实路径上的限制，不是要求所有 UI 和系统资源“无限”：

1. 移除附件、tool result、终端、文件、技能、浏览器以及 canonical history 上会导致事实丢失的应用层大小/数量/深度/字符/资源并发上限；不要把传输取消 deadline 当成资源 quota。
2. 移除 schema 中非 ACP 要求且会收窄实际执行的 `default`、`maximum`、`maxLength`、`maxItems`，以及 handler 中对应的拒绝、截断和只取尾部逻辑。
3. 工具参数有值时原样交给工具或下游 ACP Agent；没有值时不擅自补一个“安全默认上限”。
4. 下游返回超时、上下文不足、IPC 失败、内存不足或 Provider 限制时，显示该真实错误；不能把它改写成 OpenOmniBot 的资源错误，也不能静默丢数据。
5. 展示摘要可以独立存在，但必须标为 preview，不能覆盖 canonical item；保留防止越权和协议无法解析所必需的检查：路径/权限、类型/必填字段、取消、传输 deadline、进程生命周期和实际系统错误。

不要用更大的常量、用户可配置 quota 或 fallback 分支替代这些删除项；那仍然是同一类私有逻辑。

#### 4.5.1 第一阶段实施状态：先打通资源事实路径

本阶段已完成以下改动：

- 附件读取不再按文件大小、附件数量或批次总大小拒绝；实际分配失败、文件不可读和下游传输失败仍由对应 owner 返回。
- 内置终端、Termux 和 Shizuku 会话不再在运行时保存固定长度的滚动窗口；`session_read` 只有调用方明确传入 `maxChars` 时才截取。
- canonical conversation history 写入和读取不再把 payload/summary 改写成 compact/omitted 版本；Room 或下游实际失败直接暴露。
- ACP tool replay、终端结果和 tool result 保留完整字段，避免历史恢复时再次丢失事实。
- Flutter/Native runtime 不再对终端结果做第二次字符/行数截断。

仍然保留的不是 OpenOmniBot 资源配额：用户或工具明确传入的 `maxChars` 等参数、UI 纯展示预览、路径/权限/类型校验，以及系统、IPC、进程、堆内存、Provider/ACP Agent 的真实失败。旧的 repair/materialize 代码只作为历史数据兼容路径保留，不再参与新写入和正常读取。

### P1：减少切换 Harness 的等待和配置冲突

#### 4.6 Harness 启动改为直接、失败即报错

不新增安装服务。只修改现有路径：

- `session/new`/连接时只执行 command discovery 和 ACP initialize；
- 不在正常切换中运行 npm install、native build 或长时间 health command；
- 安装/修复按钮若保留，只能从设置页显式执行；
- 启动失败返回：Harness 名称、命令、exit code 和 stderr 摘要；
- 不自动切换到另一个 Harness，不自动换 Provider，也不伪造 online 状态。

`agents.json` 继续作为内置列表即可，不需要升级成动态平台。自定义 Harness 只要求用户提供 command/arguments/environment，并且该进程实现官方 ACP。

#### 4.7 用户配置保持简单可替换

“配置可插拔”不等于建立万能配置中心。保留现有 owner：

- Provider 页面负责 endpoint、model、key；
- Agent profile 负责 command、arguments、environment；
- 官方 ACP initialize 后的 mode/config options 使用 `session/set_mode`、`session/set_config_option`；
- 特殊 Harness converter 只处理它自己的配置文件；
- 不在 `AgentRuntimeManager` 再保存一份 Provider fallback。

配置冲突时明确报错，不能悄悄选第一个模型、回退旧 key 或覆盖用户选择。

#### 4.8 只做必要的现有代码清理

- 删除 `AcpAgentProfileStore.repairConversationBinding` 的重复 prefs 写入；
- 新调用不再使用 `AcpHarnessAdapters.forAgentId`，只从已经解析的 profile 获取 adapter；
- `EnvironmentSetupLogic` 中与正常 ACP 连接无关的自动安装分支移出聊天调用链；
- legacy method 只允许从 `AcpLegacyCompatibilityAdapter` 进入；
- 不迁移数据库，不新增 repository 层。

## 5. 用户体验验收条件

重构是否成功只看以下结果：

1. 用户发送一次，只出现一条用户消息和一个 Agent turn；
2. 切换会话后，旧 session 的迟到事件不会写入当前会话；
3. Agent 返回后思考状态立即结束，不依赖 2 秒 polling；
4. 取消后不会继续执行新工具，已执行工具不会因重试再次执行；
5. `max_tokens`、拒绝、错误和取消都有明确可见状态；Agent 内部继续时仍保持同一 turn，不生成隐藏 UI turn；
6. Harness 不存在或启动失败时快速返回明确错误，聊天页不会长时间卡住；
7. 设置页打开和切换 Harness 不触发安装或长探活；
8. Provider/模型/密钥只有一个配置来源，不发生静默 fallback；
9. 本地、远程、自定义 Harness 的 UI 都消费相同 ACP reducer；
10. 工具、附件、历史和 Agent 输出不会被 OpenOmniBot 自己的大小/数量/深度/字符/超时/并发上限截断或拒绝；
11. 下游真实失败可见，且不会被包装成“达到应用资源上限”；
12. 删除私有逻辑后，不需要增加新的计时器、状态字段、回调总线、quota 配置或 retry counter 才能通过测试。

建议覆盖的回归测试：

- 同一 `requestId` 不重复执行；
- visible output 后 transport failure 不重放；
- cancel 与 PromptResponse 竞态；
- conversation switch + late update；
- session/update 无 wire turnId 时只使用活动 prompt reservation；
- remote load 只恢复历史，不改变 active turn；
- Harness command missing/initialize failure 快速报错；
- 官方 stop reason 可见；Agent 内部 continuation 不产生第二个 UI turn；
- 私有 `_meta` 缺失时 UI 生命周期仍完全正确。

## 6. 历史逻辑归档

以下内容冻结，不再新增调用：

- `thread/*`、`turn/start`、`turn/interrupt`；
- `AgentStreamEvent` 和 dotted event name；
- remote Codex thread snapshot/activity mapper；
- `agentRetrying`、`agentRetryCount`、`agentRetryDelayMs` 等 presentation 状态；
- `AcpHarnessAdapters.forAgentId` 和旧 Agent/mode alias；
- UI 的 remote task id 合成与 grace period；
- 应用层工具资源 quota、历史/结果截断器和“超过上限即失败”的错误文案。

迁移期只允许“旧输入 -> 官方 ACP”的单向转换。新代码不得写入旧格式。确认没有旧版本客户端命中后，再整组删除 adapter 和对应测试样例。

## 7. 当前验证

当前工作树已经通过：

```text
./gradlew --no-daemon --no-parallel :app:compileDevelopStandardDebugKotlin
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest \
  --tests 'cn.com.omnimind.bot.agent.AgentOrchestratorTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentRuntimeContextQueryTest' \
  --tests 'cn.com.omnimind.bot.agent.Subagent*Test' \
  --tests 'cn.com.omnimind.bot.agent.runtime.*Test'
git diff --check
```

这只证明当前代码可编译、相关 ACP/Agent 单元测试通过，不表示上述 P0/P1 已全部实现。

### 7.1 已落地的功能测试与约束

本轮新增并通过的测试把“不同用户对话方式收敛到同一官方生命周期”固定成可执行约束：

- `AgentOrchestratorTest`：带历史追问仍复用原会话上下文；取消当前 prompt 后以终态错误结束，不继续发起模型请求。现有用例继续覆盖直接问答、工具调用、工具失败后继续、`length` continuation、Provider 错误和同一逻辑 turn 不重放。
- `webchat/tests/messageReconciliation.test.ts`：两轮用户对话、流式片段和最终快照按 turn 收敛，旧快照不会删除用户消息，也不会把两轮回复串在一起。
- `AcpSessionUpdateMapperTest`、`LocalAcpRuntimeTest`：覆盖 PromptResponse 终态、取消竞态、会话隔离和迟到事件边界。

验证命令：

```text
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest \
  --tests 'cn.com.omnimind.bot.agent.AgentOrchestratorTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentRuntimeContextQueryTest' \
  --tests 'cn.com.omnimind.bot.agent.Subagent*Test' \
  --tests 'cn.com.omnimind.bot.agent.runtime.*Test' \
  --console=plain --quiet
```

这些是行为约束，不是新的业务生命周期：测试不允许通过增加计时器、隐藏 turn、私有终态或应用级 quota 来“修复”失败；资源事实必须保持完整，预览必须与 canonical 数据分离，终态必须由 ACP/Provider 所有者证明。

官方协议参考： [Prompt Turn](https://agentclientprotocol.com/protocol/v1/prompt-turn)、[Tool Calls](https://agentclientprotocol.com/protocol/v1/tool-calls)、[Request Cancellation](https://agentclientprotocol.com/rfds/request-cancellation)。

### 7.2 2026-09-05：自定义 adapter 配置生效与回归补齐

本次只修用户已能编辑但启动时被清除的配置，不新增 Harness adapter、配置框架或对话生命周期。

- **问题证据**：`AgentConfigAdaptersTest.customAgentKeepsItsOwnEnvironmentWithoutASharedProvider` 在修复前失败。Profile 经 JSON 保存格式往返后，`OPENAI_API_KEY`、`OPENAI_BASE_URL`、`ANTHROPIC_MODEL` 被 `mergeAcpLaunchEnvironment` 无条件过滤，即使自定义 Agent 没有统一 Provider 映射。
- **实现**：启动合并直接使用当前 `AcpAgentProfile`，沿用已有 `usesSharedProvider` 标记。自定义 Agent 保留自己的环境变量；使用统一 Provider 的 Profile 仍移除旧凭据并以当前 Provider 映射为准。`LocalAcpRuntime` 的真实进程启动调用点已接入。没有改变 Session、Turn、工具或 memory 所有权。
- **界面**：设置列表和自定义配置页不再声称“所有 Agent 都由统一 Provider 提供凭据和模型”；自定义 Agent 的启动环境与配置由它自身管理。
- **维护**：`scripts/test-agent-runtime.sh --offline` 纳入原先未包含的 `AgentConfigAdaptersTest` 和 `agent_config_page_test.dart`。完整回归还发现旧目录测试锁着升级前的 Codex bridge 版本，现改为检查包版本与 `preparationRevision` 一致；Node 目录验证读取结构化 JSON，不再按文本位置切片或重复写具体升级版本。这只是目录一致性检查，不证明安装升级已成功。

持久化测试覆盖及证据边界：

| 场景 | 测试与实际覆盖 |
| --- | --- |
| 未配置统一 Provider，使用自定义启动凭据 | JVM 测试执行 Profile JSON 往返、现有配置映射与启动环境合并，比较完整环境；不是 Android 加密存储真机测试。 |
| 编辑、清空配置，以及统一 Provider 改变 | JVM 测试复用实际映射/合并函数，验证当前自定义配置不继承统一 Provider 或上次合并结果；不等同于进程缓存端到端验证。 |
| 用户编辑命令、参数和环境，保存后重新打开 | Flutter widget 测试实际操作编辑器，覆盖带空格路径、中文、等号、引号、空值和删除旧字段；原生通道使用测试替身，不冒充真实 Harness。 |
| 统一 Provider 模式 | 原有行为测试继续保证旧配置不能覆盖当前映射，Provider 缺失时不会复活旧凭据。 |

本次完整离线回归通过：JVM **472** 项、Flutter **341** 项、Node **52** 项、WebChat **12** 项，WebChat typecheck/build 与 `git diff --check` 通过。测试入口：

```bash
scripts/test-agent-runtime.sh --offline
```

尚未验证或完成：手机当前未连接，未进行 Android/PRoot 下真实 adapter 启动、真实 Provider 对话或新版 bridge 安装验收；未新增内置模板复制入口，也未证明编辑正在使用的配置不会打断当前 prompt。当前 `LocalAcpRuntime.saveAgent` 保存活动 Profile 时仍会调用 `disconnect()`，后续应沿现有 ACP owner 验证其取消与历史保留行为，不能靠新增重放/恢复生命周期修补。离线回归通过不代表整个升级或全部长期目标完成。

### 7.3 2026-09-05：断开竞态与官方请求结果的跨轮隔离

本轮沿上一节的配置断开路径验证，并先用确定性测试复现了两个问题，再修复现有所有者；没有新增生命周期或 Harness 特判。

1. **原生断开后，迟到的 prompt Job 仍可能被接纳。** `AcpPromptExecution.cancelForTransport` 原先只取消当时存在的 Job，没有设置已有的 `cancellationRequested`。测试按“断开 -> 挂接迟到 Job -> 尝试发起 prompt”执行，修复前 `latePrompt.isCancelled` 为 false。现在断开在同一个已有锁内设置取消标记，现有 `attachPromptJob` / `tryStartPrompt` 就能阻止迟到执行，无需新队列、重试或状态机。
2. **第一轮迟到的 PromptResponse 会结束尚未输出的第二轮。** 旧跨轮测试主要使用兼容 `turn/*` 事件，未覆盖这个官方返回值入口。新增测试通过现有宿主预留归属发送 `session/update`，再直接调用 `applyAcpPromptResponse`；修复前第二轮的 `isAiResponding` 错误地变为 false。现在该入口显式接收发起请求时已经存在的本地 `taskId`，复用协调器的 `isTaskActive` 检查；普通聊天、Agent 页和悬浮入口传回各自请求捕获的 ID。该 ID 没有加入官方 ACP wire payload，没有生成新身份，也不从当前 UI 状态猜旧请求归属。完成后直接移除已确认的任务绑定，减少一次身份推断。

新增 **20** 项持久化行为测试：

- JVM 3 项：断开发生在 Job 挂接前、没有 preparation Job、已开始执行后的重复断开与另一执行资源隔离。
- Flutter 12 项矩阵：同一 ACP session 继续／换 session 继续 × 迟到取消／错误／成功 × 有／无返回 turnId。每项同时验证第二轮首个输出前和输出后的迟到结果，并核对两轮用户消息、部分答复、最终答复进入历史保存参数。
- Flutter 3 项：没有任何流式输出且无 wire turnId 时，自己的成功／取消／错误仍能正常结束，不留下“思考中”。
- Flutter 2 项：迟到结果不能重建已丢弃的 Conversation runtime；另一对话开始后，后台对话自己的结果仍然可以正常结束自己的请求。

入口仍是 `scripts/test-agent-runtime.sh --offline`，新增测试位于已有 `LocalAcpRuntimeTest.kt` 与 `chat_conversation_runtime_coordinator_test.dart`，不需要另一套测试运行器。完整离线结果：JVM **475**、Flutter **358**、Node **52**、WebChat **12** 项通过，WebChat typecheck/build 与 `git diff --check` 通过；另行运行的协调器、reducer、架构定向集 **260** 项通过（之后补充的后台对话用例包含在完整回归中）。APK 构建通过：

```bash
./gradlew --no-daemon --no-parallel :app:assembleDevelopStandardDebug \
  -Ptarget=lib/main_standard.dart --console=plain --quiet
```

证据边界：JVM 测试覆盖断开路径实际使用的执行资源类；Flutter 测试覆盖真实协调器和 reducer，原生历史通道仍为测试替身。没有把它们称为完整真机配置保存／Android 数据库重启恢复测试；手机仍未连接。保存活动 Profile 会断开连接这一行为没有在本轮改成延迟生效。本节留下的悬浮入口“将 `session/cancel` 应答用作取消终态”问题，由下一节继续验证并移除；不能据此宣称所有终态路径已经完全对齐官方 ACP。

### 7.4 2026-09-05：悬浮聊天取消后保留结果、恢复下一次发送

官方语义已核对：发送 `session/cancel` 后，Client 仍应接收剩余工具更新；取消是否完成由原始 `session/prompt` 返回证明，不能把宿主 MethodChannel 的取消应答当成 `PromptResponse`。[官方 Prompt Turn / Cancellation](https://agentclientprotocol.com/protocol/v1/prompt-turn#cancellation)

新增 `ui/test/features/home/pages/command_overlay/chat_bot_sheet_acp_test.dart`，实际挂载 `ChatBotSheet`，点击停止按钮，通过 EventChannel 送入标准 `session/update`，用可控 Future 分别完成取消、关闭和原始 prompt；不是只调用 reducer 或检查源码字符串。测试先后复现两个故障：

- **提前结束**：取消应答已返回、原始 prompt 仍未返回时，协调器已经不再处于响应状态。删除 `_closeAcpLifecycle` 中从取消应答制造 `stopReason: cancelled` 的代码；仍沿现有取消和关闭 API 清理资源。
- **输入框停留在处理中**：关闭应答先返回、prompt 后返回时，协调器正确结束，但 `ChatInputArea.isProcessing` 仍为 true。官方 prompt 结果／真实异常处理后，现在与流式更新共用原有界面投影；没有再发一个终态通知，没有新 reducer 或轮询。只有协调器接受了该请求结果，页面才更新本地 session/prompt 引用。

一个持久化组件测试包含 **6 个双轮交互场景**：正常结束、真实传输错误、prompt 先于关闭返回的取消、关闭先于 prompt 返回的取消、取消与正常完成竞态、取消后原始 prompt 返回真实错误。每个场景都检查：

1. 收到取消应答时，原始 prompt 未结束则不能提前清理活动状态；
2. 剩余文字和工具结果被保留；同一个 `toolCallId` 只有一张工具卡，已完成工具的成功状态不会被 prompt 取消／失败改写；
3. 原始 prompt 返回后，真实输入组件退出处理中；
4. 用户实际输入并发送第二条消息，生成不同请求 ID、使用新建 session，第一轮工具卡不重复；没有自动重放；
5. 真实传输错误保留其错误内容，不被改写成“已取消”。

该测试已加入 `scripts/test-agent-runtime.sh --offline`。完整离线回归通过：JVM **475**、Flutter **359**、Node **52**、WebChat **12** 项；WebChat typecheck/build 通过。最终定向组件／架构集 **14** 项通过，`git diff --check` 通过，并按上一节同一命令重新构建 APK 成功。组件集在一个应用／EventChannel 生命周期内依次打开、关闭各场景，避免以新增生产测试钩子处理测试框架对平台消息处理器的重置。

仍需区分：这些是实际 Flutter 交互和协议投影验证，但原生进程、工具执行与数据库通道为测试替身，不是真机 Harness 或真实工具端到端验收。手机尚未连接；活动配置保存的断开行为、真机安装升级与真实 Provider 对话仍未据此宣告完成。

### 7.5 2026-09-05：发送后立即停止，不遗留会话、不误报启动失败

继续扩展同一个 `chat_bot_sheet_acp_test.dart`，不新增测试框架。新增 **4 项双轮组件测试**：让状态检查返回在线／离线、连接、`session/new` 分别停在可控 Future 上，实际点击停止，再返回准备结果，最后输入并发送下一条消息。

先红后绿的证据与最小修复：

- 创建会话期间停止，迟到的 `session/new` 返回后，预期的一次 `session/close` 实际为零。原有 `_acpCloseStarted` 在会话尚不存在时已被设置；现在在取得新 session 身份后重置该标记，让现有关闭路径清理这次真正创建的会话。
- 启动前取消仍被发送入口显示为启动错误。最初只查 runtime 和普通文本的断言漏掉了 Markdown 错误气泡；补查实际 `MessageBubble.message.isError` 后，两种准备场景均失败。现在 `_tryAgentFlow` 离开时通过现有协调器 `unregisterTask` 释放该请求的宿主绑定并同步投影；尚未发送 prompt 的准备过程没有 `PromptResponse`，不为它伪造一个取消结果。已发送的 prompt 仍先等待原始结果／错误再清理。
- 状态检查结束后先检查已有取消条件，避免用户已停止、状态返回离线时仍调用 connect。另一个发送回调也按原始任务身份决定是否显示启动错误，不影响后续请求。

每个新增场景均验证：取消后不继续创建 prompt；只关闭实际迟到的 session；没有错误气泡；保留第一条用户消息；输入组件退出处理中；第二条消息只发出一次 prompt 并能正常结束。原来的 6 个取消／完成／错误与剩余工具输出场景继续运行。

可重复命令：

```bash
cd ui
flutter test test/features/home/pages/command_overlay/chat_bot_sheet_acp_test.dart \
  test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart \
  test/features/home/pages/chat/chat_architecture_test.dart --reporter expanded
```

上述定向集 **78** 项通过；`scripts/test-agent-runtime.sh --offline` 完整通过，JVM **475**（41 个 suite，零失败／错误／跳过）、Flutter **363**、Node **52**、WebChat **12** 项，WebChat typecheck/build 与 `git diff --check` 通过。新增测试沿用原有运行器收集入口。按 7.3 节命令重新构建 develop standard debug APK 成功；未安装到设备。

证据边界：真实 Flutter 页面和交互、协调器与 reducer；原生进程／数据库仍是通道替身。测试中的准备 Future 最终会返回，未据此证明永久挂起的启动调用也能及时释放界面。`adb devices` 仍无设备，未做真机安装或真实 Harness 对话。活动 profile 保存断连等未完成项仍保留，不因本节通过而标记整个升级完成。

### 7.6 2026-09-05：保存启动配置不打断当前对话

本节更新 7.2—7.5 节留下的“保存活动 profile 立即断连”问题；此前记载保留为当时的验证状态。

新增原生入口测试 `LocalAcpRuntimeConfigTest`，直接调用真实 `LocalAcpRuntime.handleMethod("agent/save", ...)`。修复前测试失败：`Saving settings cancelled the pending prompt`，实际 `AcpPromptExecution` 持有的 Job 已被保存路径取消。修复仅移除 `saveAgent` 中的 `disconnect()`；不增加待生效配置队列、重启调度器或新 adapter。现有 `activeProfile` / `activeLaunchEnvironment` 继续表示正在运行的进程，profile store 表示下一次启动使用的配置；Manager 已有的启动环境缓存失效路径保持不变。

用户可见语义：自定义 Agent 配置页保存命令、参数、环境变量，不打断当前对话；更改在 **Agent 进程下次启动** 时生效，不是“下一条消息必定生效”。页面中英文说明已同步。这里不承诺各 Harness 原生配置文件的热加载行为，后者仍归 Harness 自己管理。

3 项原生持久化回归覆盖正常编辑、清空参数／环境变量、保存失败。每项都检查：

1. 保存后当前 prompt Job 未被取消，连接和当前启动快照不变；
2. 保存请求携带新的完整 profile，失败时原始保存错误返回给调用者；
3. 后续普通 `connect()` 复用已有连接，不因为已保存命令变化而重启；
4. 用户显式 `disconnect()` 仍取消旧执行；之后真实 `connect()` 的启动准备回调收到新的已保存 profile，保存失败则仍收到原 profile。

测试使用 [Mockito](https://github.com/mockito/mockito) 作为 `testImplementation`，不增加生产依赖。真实方法和执行资源运行在 JVM；Android 存储、工作区和连接为替身，通过测试内反射放入运行状态，没有在生产代码增加测试 hook。下一次连接在准备回调处主动结束，不冒充 Android/PRoot、模型、工具或数据库端到端验证。Flutter 配置页 6 项测试通过，已有保存／重新打开测试同时验证新的生效时机说明。

定向复现／回归命令：

```bash
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest \
  --tests 'cn.com.omnimind.bot.agent.runtime.LocalAcpRuntimeConfigTest' \
  --console=plain --quiet
```

新测试已纳入 `scripts/test-agent-runtime.sh --offline`。本轮完整回归通过：JVM **478**（42 个 suite，零失败／错误／跳过）、Flutter **363**、Node **52**、WebChat **12** 项；WebChat typecheck/build、`git diff --check` 通过。最终再次运行这 3 项原生测试并构建 develop standard debug APK 成功；Gradle `dependencyInsight` 确认 `developStandardDebugRuntimeClasspath` 中不存在 Mockito 依赖。

真机活动对话期间编辑配置、进程重新启动及真实 Harness 的完整验证仍需补齐；当前没有连接的 Android 设备，未提交、推送或创建 PR。

### 7.7 2026-09-05：自定义 Adapter 不再被名称／命令误判为旧内置 Agent

将 7.6 节入口测试的 profile store 替身换成真实 `AcpAgentProfileStore` 和 APK 实际使用的 `acp/agents.json`，补充保存后从磁盘重新构造 Context/store 的验证。由此复现：自定义名称含“小万”、命令含 `xiaowan` 或显式复用内置命令时，原生旧身份迁移会删除刚保存的配置，导致保存抛出 `NoSuchElementException`；Flutter 目录去重也可能隐藏该配置。此前只模拟存储接口，无法发现这个用户路径故障。

修复仅调整已有身份判断：原生迁移和 Flutter 去重只识别确定的历史 ID `legacy-xiaowan-bot`、`legacy-xiaowan-command`，不再用用户可修改的名称／命令猜身份。内置 `xiaowan-acp` 仍保持原身份；明确历史 ID 的选择项、session 和 conversation 绑定迁移仍保留。没有增加 profile 类型、注册平台、重试或 Agent 生命周期。

- 新增 `AcpAgentProfileStoreTest` 5 项，覆盖名称、命令、复用命令、已有配置与绑定的磁盘重开、确切旧 ID 迁移。
- `LocalAcpRuntimeConfigTest` 原有 3 项现在经过真实存储、序列化和校验，非法空命令走实际拒绝路径；保存后从另一 Context/store 读取验证，不以同一个内存对象冒充持久化。
- Flutter service 新增 5 项名称／命令组合，确保内置与自定义配置同时存在，用户选择保持不变；旧 ID 去重测试继续保留。
- 原生与 Flutter 的名称／命令误判测试均先复现失败；同步纠正一项仍要求按自定义名称迁移的历史断言，未删除确切旧 ID 的兼容测试。
- 现有 `RemoteCodexBridgeConnectionTest` 也加入统一运行器，验证回调挂起时桥接事件仍按实际到达顺序投递。

测试支持代码 `AcpProfileStoreTestContext` 只模拟 Android 文件入口、AssetManager 与 SharedPreferences/Editor，实际读写隔离的临时 JSON 文件；这证明产品存储逻辑经过磁盘重开，但不是 Android SharedPreferences 异步落盘、Keystore、Room 或进程崩溃耐久性验收。真实 Harness 启动仍未执行。

### 7.8 2026-09-05：按用户要求收尾提交

不继续扩展功能、不重做前端布局。此次提交汇集本分支已有 ACP 兼容边界整理、内容完整性、配置与对话功能修复及持久化回归集；历史审计加归档声明，避免旧建议与现状混读。最终验证结果、可重复命令和未覆盖项统一记录在 [0.6.1 PR 汇总](pr-0.6.1-acp-ux.zh-CN.md)。创建 PR 不代表真机升级和全部外部 Harness 已完成验收。

用户随后连接手机并要求安装；本次 debug APK 已覆盖安装到 vivo V2502A，包管理器确认从 0.6.0.3（10）升级至 0.6.1（11），未卸载或清空数据。此前各节“未连接手机／未安装”的记载保留为历史时点，不再代表本次收尾安装状态；真实对话、升级数据恢复和 Harness 兼容性仍待单独验收。

### 7.9 2026-09-05：模型提供商缓存的用户路径修复

用户安装后报告“模型提供商缓存有问题”。没有清空用户缓存掩盖故障，也没有增加缓存框架；先在现有 Flutter service／真实设置页面复现以下行为，再修复原入口：

1. 成功刷新仍把旧缓存拼进返回列表，服务端已删除的模型继续显示；空列表也无法清空旧远端目录。现在成功结果替换远端目录，失败才保留同一 Provider revision 的缓存；用户手动添加的模型不因此删除。
2. 临时 API Key／请求头的模型查询，被误存成正式 Provider revision 的目录。现在这类显式覆盖参数仅用于预览结果，不覆盖已保存配置的缓存。
3. 页面修改 API Key／Base URL 后直接刷新，先拉模型、后由离开页面的自动保存使缓存失效。组件测试先得到 `['fetch']` 而非 `['save', 'fetch']`。现在点击刷新先完成原有草稿保存，再通过保存后的 profile 获取模型；保存失败不请求模型，返回后用原有 profile ID／revision 和草稿变化判断是否仍可投影结果。
4. 滑动删除远端模型时漏传 `profileRevision`，缓存存储拒绝无版本写入，但 UI 已报告删除成功；重开页面模型复现。现在写入使用当前已保存 profile 的 base URL 和 revision，实际删除与重开结果一致。

新增 9 项持久化回归：service 4 项（刷新替换／空列表／临时 Key／临时 headers），页面 5 项（Key 与地址草稿分别保存刷新重开／保存失败可重试且不拉模型／草稿变化后的迟到结果／删除后重开）。同时修正旧 Provider 测试夹具：初始化存储、明确 `refresh: true`、同版本失败回退使用同一 revision；不放宽正式缓存身份检查。

```bash
cd ui
flutter test test/services/model_provider_config_service_test.dart \
  test/services/model_provider_cache_lifecycle_test.dart \
  test/features/home/pages/model_provider_setting/model_provider_setting_page_test.dart \
  --reporter expanded
```

上述定向集 46 项通过，并全部纳入 `scripts/test-agent-runtime.sh --offline`。生产修改仅在现有 Provider service 和设置页事件处理，没有前端布局变化，没有 Agent 生命周期变化。组件测试使用真实 UI、service 和 SharedPreferences 测试存储，原生配置／网络响应为 MethodChannel 替身；不能据此宣称每个真实模型供应商和 Android 重启持久化均已验证。最终 APK 与安装记录见 PR 汇总。

### 7.10 2026-09-05：工具工作卡反映实际输入和结果

用户报告小万制作 HTML 时看不到工作卡，以及报错后的显示与实际不一致。本次只修复已有 Provider → ACP → shared reducer → 工具卡的投影，不新增 Agent、状态机、协议、自动重放或页面退出后的执行策略。

- 小万原先只在模型完整返回后、开始执行工具时上报工作卡。现在沿现有回调链转交 Provider 已有 ID／名称的工具输入，使用官方 `tool_call(status=pending)` 和同 ID 的 `tool_call_update`。输入不完整时不执行；真实执行才上报 `in_progress`，真实工具结果决定 `completed`／`failed`。展示复用已有 300ms 更新节奏，不截断输入或限制模型生成。
- 前端不再将所有 `pending` 解释成等待批准。普通 pending 显示“准备中”，实际批准请求仍由原有 ACP permission request 卡负责。移除文件卡隐藏状态标签的特殊分支；没有 HTML 专用分支。
- 流式 `rawInput` 尚非完整 JSON 时，原先显示整个事件封装、后续可能保留旧参数；现在原样显示输入字符串，同卡更新，完整 JSON 和工具结果继续走现有解析器。
- 移除 shared reducer 在 prompt 结束时替未完成工具补“成功”的推断。已收到的成功／失败结果保持不变；未收到工具结果的卡片使用已有“中断”展示，不声称文件已写入，不产生新的工具终止协议事件。官方 prompt 响应／错误／取消仍是唯一所属执行边界。

持久化回归已在原有测试文件中维护，统一入口仍为 `scripts/test-agent-runtime.sh --offline`：

1. `AgentOrchestratorTest`：真实 Http client、累积器、orchestrator、ACP bridge 串联，SSE 尚未结束就断言 pending 卡出现；部分输入不执行；正常完成仅执行一次；断流／取消不执行、不重放。
2. `AgentLlmStreamAccumulatorTest`／`XiaowanAcpPresentationBridgeTest`：并行输入保持原始 ID、不捏造无 ID 卡片、同卡稀疏更新、工具失败、完成后重放不复活；现有 Responses 名称恢复测试也检查流式名称。
3. `agent_event_reducer_test.dart`／`agent_tool_summary_card_test.dart`：输入逐次更新与消息序列化恢复；官方结束／取消／报错不把未完成工具改成成功；文件卡准备状态可见且不误称正在写入或等待确认。

上述关键场景均先复现红测再修复。最终本地集合：JVM 491、Flutter 461、Node 52、WebChat 12 项通过，WebChat typecheck/build 通过。SSE 和工具执行端是确定性替身；消息序列化恢复不等同于真机数据库／进程恢复。手机未连接，尚未验证实际 ANR、真实 Provider 报错频率及离开页面后继续执行，不将自动测试通过表述为这些问题均已解决。

### 7.11 2026-09-05：内容完整性与高额度配置（替代机械删除一切上限）

最新用户决定：必须有上限时尽量配置较高额度，优先使用官方能力；已经消除的内容丢失不恢复。不是把模型容量写成无限，也不是删除用户主动选择的读取范围、操作系统权限、有效数据校验或官方 ACP 请求参数。

本次实际修改：

- Office 预览原先只保留 Word 24 段／每段 240 字符、Excel 3 表／20 行／8 列／每格 48 字符、PPT 8 页／每页 8 行／每行 160 字符。删除这些解析器内的内容丢弃逻辑；真实 ZIP 文档功能测试验证全部段落、单元格和末页内容，不改变页面布局。
- `file_read`、`skills_read`、普通与 Shizuku 终端读取不再宣传或应用自定义 `maxChars` 截断，旧调用即使携带该字段也不会截短正文。文件显式行范围／偏移仍是查询语义。删除无人使用的 SharedHelper 截断函数。
- 删除 `AgentEventAdapter.compactToolResultContent` 及头尾省略／offload 封装，orchestrator 直接使用完整工具结果。此前默认分支虽已不截断，但仍留有能重新启用的旧实现。
- 删除 Agent 用户配置文件约 100 万字符的本地拒绝条件；不改变配置归属或用户编辑入口。
- 平台 embedding 不再额外限制响应 2 MiB、向量 8,192 维。保留非空、有限数值检查；不修改 memory API 或已有记忆数据。真实响应解析测试覆盖超过旧额度的响应和 8,193 维向量。
- Codex 自定义模型目录仍按官方必填 `truncation_policy` 生成，不删除必填字段。由固定 `bytes / 10000` 改为 `tokens / contextWindow`；额度跟随 ProviderModelOption.contextLimit，缺失时沿用已有 272,000-token 回退值。128,000-token 模型对应同等工具输出额度。该回退不代表未知 Provider 真的支持 272,000 token，应按实际模型配置容量。用户可使用官方 `tool_output_token_limit` 显式覆盖，适配层不新建限制协议。参见 [官方配置参考](https://learn.chatgpt.com/docs/config-file/config-reference) 与 [官方模型目录类型](https://github.com/openai/codex/blob/main/codex-rs/protocol/src/openai_models.rs)。提高额度可能更快占满上下文，不保证工具结果和整个历史都能同时进入单次模型请求。
- 删除 orchestrator 的 `TerminalTurnRequestFailure` 重复错误包装和第二个终止分支。原始异常由既有外层错误路径返回，ACP 所属请求仍负责结束；取消和输出后禁止重放的规则不变。

持久化检测入口：

```bash
node scripts/audit-agent-capability-limits.mjs
node scripts/audit-agent-capability-limits.mjs --json
bash scripts/test-agent-runtime.sh --offline
```

扫描器直接遍历 app、assists、baselib、ReTerminal、ui/lib、webchat/src，不受 Git 对 runtime/ 的忽略规则影响；扫描结果是待审阅候选，不能将正则匹配数量当作已确认限制或已完成人工验证。本轮扫描得到 1,515 处候选，包括协议判断、超时、UI 排版、哈希截取和废弃实现。

尚未处理完的审阅项，不能宣称“所有能力限制已取消”：语音输入／音频响应大小策略；旧 SandboxPluginPool 中的包大小／文件数／查询配额及其是否仍有可达入口；历史工具压缩的私有死代码；WebChat 显式 maxChars 参数；错误展示的 400 字符省略；Harness 自身的技能目录额度和模型固有容量。按最新决定，应先确认所有者与可达性，再优先调高官方或用户配置，而非批量删除 require、timeout、权限检查或 UI maxLines。

本次验证结果：统一脚本的 Node 56 项与 JVM 494 项（45 个测试类）通过；Office Flutter 6 项通过；`git diff --check` 通过。Codex 目录测试先因旧 bytes 策略失败，修改后通过；embedding 越界响应测试先失败后通过。未运行真实 Provider、真机安装或本轮完整 Flutter/WebChat 回归，未声称手机上的已安装版本已经变化。

### 7.12 2026-09-05：删除审阅发现的六处额外策略

本节修正 7.10 中“将未完成工具统一显示中断”的决定；不再把请求结束推断成工具终止结果。

1. 小万配置不再写死推理档位，也不默认关闭思考。当前 Provider 模型目录只有 reasoning 布尔能力，没有具体档位，故不伪造 thought_level 选项；模型请求不填 reasoning override，由 Provider 使用自身默认值。外部 ACP Harness 实际声明的 configOptions 仍完整展示。删除无运行时调用、会把 max/xhigh 降成 high 的旧 normalizeXiaowanReasoningEffort。
2. 模型选择通过已有 AcpAgentProfileStore 按 sessionId 持久化，附带 Provider identity。load/resume 初始化时读取同 session 配置，不把另一个 Provider 的选择带过去；session/delete 的既有解绑路径同时清理配置。先确认配置合法并持久化成功，再更新内存值；没有配置重放、自动重开或新增生命周期。
3. 创建、恢复和 initialize 不再调用 /models。ModelProviderConfigStore 只读 Provider 编辑器已有缓存，核对 profileId、规范化地址和 revision；不匹配则不采用旧目录。已绑定模型仍可启动，目录刷新由原有 Provider 设置入口负责。没有增加另一份模型缓存。
4. shared reducer 删除请求结束时批量改写工具卡状态的函数。保留最近一次真实工具状态及结果；请求结束仍由原有 prompt response 路径投影，不替工具补 success/interrupted。显式用户取消路径与真实工具终止更新不在此批量改写范围内。
5. 不完整工具调用错误不再固定声称“已自动重试一次”。实际重试仍仅由既有 HTTP 层在允许的条件下负责，错误文案不替它编造经过。
6. HttpAgentLlmClient 删除单元素模型候选循环、单元素请求变体循环、仅重抛异常的包装与 StreamRequestVariant。直接解析一次配置路由、编码一次请求并调用既有传输；保留 Responses 官方函数名兼容及真实传输重试。原变体函数测试替换为实际 HttpAgentLlmClient 请求捕获测试，逐字段比较真实发送内容且断言只发送一次。

维护入口仍为 `bash scripts/test-agent-runtime.sh --offline`。新增／调整用例覆盖：默认不伪造推理能力、配置存储重开和会话隔离／删除、Provider 目录版本失效、真实请求字段与次数、未完成工具在正常结束／错误／取消后的结果不被伪造、无重试时的错误文案。配置持久化测试使用可重开的 SharedPreferences 测试实现；不等同于真机杀进程验收。

本次执行 `bash scripts/test-agent-runtime.sh --offline --skip-webchat`：JVM 504 项（47 个测试类）、Flutter 468 项、Node 58 项通过，`git diff --check` 通过。未运行真实 Provider 请求、WebChat 验收、APK 安装或真机进程恢复；未将单元测试替身表述为这些端到端场景已验证。

### 7.13 2026-09-05：移除旧状态裁决与重复错误消息

按现有 Conversation → ACP Session → 请求 → Item 所有权向下收敛，本次只处理两项，不新增生命周期、重试或错误去重层：

- 删除 reducer 的 `state_change` / `state_update` 投影、附带 usage 兼容读取，以及 `thread/started` / `thread/status/changed` 对请求启动、结束、失败的裁决；删除 coordinator 对 thread status 的任务解绑判断及不再使用的状态解析函数。未投影的 update 不再被当作需要请求归属的内容；既有未知更新处理不启动或结束请求。标准 usage/config/title 更新仍走原入口。
- 普通聊天 catch 只调用已有 `applyAcpPromptResponse`，删除页面的 `isAiResponding` 裁决、额外 unregister 分支和“抱歉，发送消息失败”消息插入。请求所有者已有的任务身份校验继续忽略取消／结束后的迟到异常；后台请求的失败仍归原会话。没有新增错误消息去重机制。
- 此次删除范围不等同于所有旧事件兼容全部移除；其他旧 item/turn 输入仍待独立审阅，不以本次修改扩大范围。

持久化回归：`chat_conversation_runtime_coordinator_test.dart` 新增 9 个组合（3 种旧状态入口 × 正常结束／取消／失败），验证旧 running/idle/failed/cancelled 不影响当前请求与任务绑定，真实结果仍结束请求且重复结果不新增消息；`chat_architecture_test.dart` 增加页面不再插入第二份错误的源代码约束。两类检查修改前共 10 项失败，修改后通过。旧 reducer 测试改为验证状态不能启动请求、idle 不结束正在输出的思考内容。

可重复执行（在 ui 目录，Flutter 不在 PATH 时使用本机绝对路径）：

```bash
flutter test test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart test/features/home/pages/chat/chat_architecture_test.dart test/services/agent_event_reducer_test.dart test/services/agent_runtime_service_test.dart test/features/home/pages/chat/conversation_manager_lifecycle_test.dart
```

上述 331 项通过，且这些测试文件已被 `scripts/test-agent-runtime.sh` 收录。额外 Node 契约 41/42 通过：Provider 缓存测试仍以全文件禁止 `refreshAndGetModels`，与当前配置刷新实现冲突，本次未修改该配置路径或放宽该测试。`flutter analyze` 因本机缺少 analysis-server snapshot 启动失败；直接 Dart 分析器可运行。未运行真机、真实 Provider 请求或 APK 安装；本次不宣称完整验收通过。

### 7.14 2026-09-05：终态决定权归现有 ACP 请求所有者

本节继续 7.13，不新建协议、reducer、生命周期类或错误去重层。当前请求终态从其 `session/prompt` 返回值／所属请求异常，以及既有显式 ACP 取消路径进入 coordinator 的 `applyAcpPromptResponse`，再由 `reducePromptResponse` 投影。reducer 的 `_completeTurn` 仅剩这一处调用。

已删除：

- Flutter 对 `turn/completed`、`turn/failed`、`thread/closed`、通用 `error/willRetry` 的完成／失败裁决；旧 `kind:completed/error` 不再转换成这些私有终态。旧终态也不再触发“缺少 turnId”的兼容警告。保留旧内容读取，不赋予它结束当前请求的权限。
- coordinator 按通知解绑任务的分支，以及根据缺失 id 猜测可以结束本地请求的兼容函数。
- Agent 发送 catch、另一条普通聊天 Agent flow catch、`handleAgentError` 中页面自行插入错误消息、解绑和按 `isAiResponding` 决定如何结束的路径。没有活动请求的准备错误只显示 toast，不改一组生命周期标志。已拥有请求的错误交给现有 coordinator 校验所属任务。
- 远端 Kotlin 通知中按 thread status、turn complete/failed、error 或 thread closed 清理请求的分支；读取 thread 快照时同步／清理活动请求的函数；prompt finally 的重复终态清理。真实返回／异常路径继续释放资源，连接关闭仍走已有请求取消与断连清理。
- 页面监听私有完成事件收尾 Plan 模式的分支及对应 id 集合；既有收尾行为改在 awaited prompt response 返回后执行。

验证：扩展已有 coordinator 矩阵至 9 种旧输入 × 3 种真实请求结果，共 27 组，覆盖旧 running/idle/failed/cancelled、willRetry、旧 kind:error/completed 不抢占当前请求，真实失败单条展示，重复结果无副作用。初次扩展的 12 个旧终态用例在修改前失败，修改后通过。其他内容、历史、会话隔离测试的完成步骤改用真正的 `reducePromptResponse` / `applyAcpPromptResponse`，而非在测试内复活旧完成通知。新增源码约束保证 reducer 只有一个完成调用点，页面不插第二份错误，远端通知与快照不清理当前请求。

持久化用例均已包含在 `scripts/test-agent-runtime.sh` 现有测试入口。本次定向执行 8 个 Flutter 测试文件共 385 项通过（7.13 的 5 个文件，加 `chat_bot_sheet_acp_test.dart`、`agent_tool_summary_card_test.dart`、`agent_tool_transcript_test.dart`）；Android `LocalAcpRuntimeTest` 30 项、`RemoteCodexAppServerSessionTest` 3 项通过，Gradle 构建成功；Dart 直接分析无 error，仍有 warning/info；`git diff --check` 通过。Node 契约仍是 41/42，原 Provider 缓存断言冲突未处理，不宣称全仓检查通过。未安装手机、未执行真实 Provider 对话，不能声称手机上的失败频率已经下降。

范围说明：此次统一的是当前请求的终态决定权，并非宣布所有旧 item 字段、远端管理接口、历史导入实现均已移除；这些剩余兼容不能重新获得当前 ACP 请求的终态裁决权。

### 7.15 2026-09-05：实时 Markdown 不再等待请求结束

根因在展示侧，不是 ACP 缺少生命周期：`StreamingText` 在 `isFinal:false` 时把表格替换为等宽 `Text` 预览，并按 `markdownRenderedLength` 拆分 Markdown 前缀和尾部、隐藏部分表格候选内容。表格预览策略可追溯到 2026-07-03 的 `eb1a3967e3f30572e91699d8d6b36f00eb09ebc6`，不是本次终态统一新引入的逻辑；旧测试甚至要求输出尚未结束时不存在 `Table`。

本次删除上述预览、拆分和尾部隐藏分支，当前完整文本直接交给已有 `OmnibotMarkdownBody`。复用所安装 `flutter_markdown 0.7.7+1` 的 `didUpdateWidget` 数据变化重新解析机制，不增加解析器、依赖、流协议或完成判断。合法表头与分隔行到达后即可形成表格，后续行继续更新；尚不构成表格的内容保留普通文本展示，不以“疑似重复”或“尚未完成”为由隐藏。旧 `markdownRenderedLength` 参数暂保留以兼容现有调用，但不再控制 Markdown 拆分或等待。

边界依据：[ACP Prompt Turn](https://agentclientprotocol.com/protocol/v1/prompt-turn) 的内容更新与请求完成分工、[GFM 表格语法](https://github.github.com/gfm/#tables-extension-)以及当前安装的 [flutter_markdown](https://pub.dev/packages/flutter_markdown) 更新实现。ACP 负责执行状态，Markdown 组件负责当前文本的布局；请求结束不是表格渲染条件。保留已有公式、资源链接、选择复制和 Markdown 扩展，不宣称已删除所有自定义 Markdown 语法。该包已停止维护，但依赖迁移不混入此次修复。

持久化验证：先把旧预览测试改成“请求未结束即存在真实 Table”，修改生产代码前失败，修改后通过。新增逐步输入表头／分隔行／两行数据／后续正文、列表和代码块的场景，覆盖旧偏移 null、0、8；验证最终态前后及文本替换、200 行表格末行不丢失。两条要求隐藏不完整原文的旧测试改为要求保留内容。新增用例及既有公式、选择复制测试共 37 项通过；会话 coordinator、reducer、overlay、工具摘要卡和工具记录共 313 项通过。相关渲染测试已接入 `scripts/test-agent-runtime.sh`，直接 Dart 分析两个修改文件无问题，脚本语法与 `git diff --check` 通过。

可重复执行渲染回归：在 `ui` 目录执行 `flutter test test/widgets/streaming_text_test.dart test/widgets/omnibot_markdown_body_math_test.dart`。此次没有执行全仓验收、真实 Provider 对话、手机性能测试或安装；200 行用例仅验证组件树保留内容，不代表任意长度下的帧率保证。仍采用现有组件全快照重新解析，不添加私有节流或截断规则。
