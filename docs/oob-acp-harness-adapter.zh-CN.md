# OOB ACP Harness 适配边界

本文记录 OpenOmniBot（OOB）中本地 Harness 的统一 ACP 接入方式。目标是：每个 Harness 只声明自己的外部运行时差异，主聊天、会话、Provider、流式事件和插件能力全部走同一条主路。内置目录以 `app/src/main/assets/acp/agents.json` 为准。

## 总体原则

OOB 的共享边界是 ACP session/runtime：

```text
Flutter Chat
    ↓ ACP event reducer / runtime coordinator
AgentRuntimeManager
    ↓ shared session/new/load/list/prompt/update/cancel
LocalAcpRuntime
    ↓ profile + AcpHarnessAdapter
official ACP Harness process
```

Harness 的具体名称只能用于注册官方 profile；运行时不应在主路写成 `if (codex)` 等厂商分支来修补行为。主路通过 profile 取得 capability：

```kotlin
val adapter = AcpHarnessAdapters.forProfile(profile)
```

当前能力入口位于：

- `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpHarnessAdapters.kt`
- `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpAgentProfileStore.kt`
- `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeManager.kt`
- `app/src/main/java/cn/com/omnimind/bot/agent/runtime/LocalAcpRuntime.kt`
- `app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeMcp.kt`

## 请求必须携带自身归属

运行时不再保存“当前选中的全局 Agent”。`AgentConversationModePolicy` 统一校验请求的 `agentId`、持久 Conversation owner 和 ACP Session owner；已存在的归属不得被页面选择覆盖，彼此冲突时报 `agent_owner_conflict`。没有已知 owner 的新 Agent 请求必须明确传 `agentId`，否则报 `agent_identity_required`。

`normal`、`chat_only` 是明确的小万产品模式，只有 owner 缺失时可据此确定 `xiaowan-acp`；不能用它们改写另一 Agent 的既有会话。新建内置 SubAgent 定时任务由产品入口显式写入 `xiaowan-acp`。没有可验证归属的历史仍可查看及管理，不自动补写默认 Agent。

| 宿主操作 | 身份要求 |
| --- | --- |
| `agent/list` | 只读目录；返回 `agents`、`retiredAgentIds`，不返回 `selectedAgentId` 或条目 `selected` |
| `status`、`disconnect` | 指定 `agentId`，或提供能解析持久 owner 的 `conversationId` / `sessionId` |
| `initialize` | 连接级握手，明确指定 `agentId`，不靠 Conversation 或 Session hint 切换连接 |
| `session/new`、`session/prompt` 及其他 session 操作 | 新会话明确指定 Agent；已有会话沿持久 owner 执行，连接由同一 ACP runtime 按需建立 |
| `agent/select`、`connect` | 已移除，不作为发送前置操作 |

本机 Codex 的身份是 `codex-acp`，PC Bridge 的身份是 `codex-remote`。`remoteEnabled` 只控制远端连接是否可用，不能据此把本地会话改发到远端。配置、模型列表和文件请求也必须寻址对应 owner。

持久化变更仅删除 `acp_agent_profiles` 中旧的 `selected_profile_id`；不读取其值决定运行时，也不创建替代全局选择键。现有 Conversation/Session bindings 与 Room schema 保持原有格式。页面草稿继续使用既有 Conversation target 保存自己的 `agentId`，它不是运行时默认值。Provider 解析、凭据与长期记忆 API/数据不随这次归属调整改变。

## 哪些必须由 Harness adapter 处理

这些内容属于外部 Harness 的部署或协议差异，应该放在 `AcpHarnessAdapter` 或对应的官方 runtime metadata 中：

1. **MCP 注入方式**：Harness 支持 ACP `session/new` 的 MCP 声明时，使用统一的 session-level `McpServer.Http`；不支持时，adapter 将同一个本地 MCP endpoint 变成启动环境变量。不要为此复制一套插件机制。
2. **启动配置面**：如果 Harness 需要自己的配置文件、环境变量或模型字段，adapter 负责读取、同步、校验和写回。共享 Provider 仍是唯一凭据来源。
3. **模型与凭据映射**：Provider 的 `baseUrl`、API Key、模型 ID 可能要变成不同官方变量或配置格式，但模型选择和 Provider 绑定仍由统一 Agent 配置管理。
4. **ACP 输出归一化**：某个官方实现返回的 ACP JSON 缺少展示字段时，只在 adapter 做无损归一化；不能在 Flutter 再建一套私有事件协议。
5. **安装与健康检查**：npm 包、原生依赖、健康检查命令和安装脚本路径属于 runtime metadata/adapter，不属于前台切换流程的 Harness ID 分支。
6. **终端包标识**：终端包管理只读取 official runtime metadata 的 `terminalPackageId`，不在 Manager 中猜测 Harness 名称。

## 哪些不需要每个 Harness 重写

以下是统一兼容层，除非真实协议验证证明某 Harness 有明确差异，否则不应复制：

- ACP `initialize`、`session/new`、`session/load`/`session/resume`、`session/list`、`session/prompt`、`session/update`、`session/cancel`、`session/close` 和 `session/delete` 生命周期（按协商能力启用）。
- `conversationId`、`sessionId`、`turnId`、`messageId`、`toolCallId` 的绑定和恢复。
- Provider/模型绑定、`/models` authoritative 解析、缺少绑定时的错误语义。
- ACP 流式事件进入 `AgentEventReducer` 和聊天 UI 的渲染路径。
- MCP server 的启动、端口、token 和停止生命周期。
- OmniFlow/MCP 插件注册、安装、启停和 Function Store；Harness 只消费能力，不拥有第二套插件系统。
- 取消、超时、断线重连、旧会话兼容和统一错误映射。
- `session/update` 的官方 v1 语义是 session-scoped；它不保证携带 `turnId`。Host 只能用已预留的活动 prompt 关联本轮，不能要求 Harness 伪造协议字段，也不能按文本快照猜测归属。
- ACP 的 `user_message_chunk` 必须先完整保留到共享事件投影，再由 Conversation 历史按 `messageId` 幂等合并；不能在 Harness adapter 中直接丢弃。
- Android 文件系统的 ACP hard-link 兼容 preload。它是所有本地 ACP runtime 的统一兼容层，不是某个 Harness 的协议分支。

## 当前配置矩阵

| Harness | 必须保留的 adapter 差异 | 统一复用的部分 | 是否需要独立配置重写 |
| --- | --- | --- | --- |
| 小万 | 使用内置 ACP connection；不需要外部 managed npm 包 | Provider/模型、session、事件、MCP/插件边界 | 只维护自身官方 ACP 启动入口 |
| Codex | 官方 `config.toml`、`auth.json`、model catalog；Responses wire API | session、Provider 绑定、事件、取消和统一 MCP | 需要自己的官方配置面，但不需要自己的会话/插件机制 |
| Claude Code | 官方 `settings.json`；`ANTHROPIC_*` 环境变量和模型映射 | ACP 生命周期、Provider、事件、MCP 声明 | 需要自己的配置面；原始 JSON 内容走统一 raw-config 兼容 |
| OpenCode | 官方 `opencode.json` provider/model 配置；`OPENAI_*` 环境变量 | ACP 生命周期、Provider、事件、MCP 声明 | 需要自己的配置面；只同步共享 Provider/model |

这里的“需要重写”只表示把同一份共享 Provider 意图翻译成该 Harness 官方接受的配置，不表示重新实现 Agent、会话、工具或插件系统。

## 已移除的运行时

DeepSeek Harness 不再作为内置 Agent 提供安装、升级、配置适配或 Web 入口。DeepSeek 模型及 API Provider 仍由共享 Provider 层支持，与本地 Harness 的支持范围独立。旧版本的安装与验收文档仅保留历史证据，不作为当前支持声明或操作指南。

升级会清除旧启动配置与安装状态，保留 Conversation/Session 的原 Agent 归属和历史。退休 ID 在统一请求入口被拒绝；Flutter 和 WebChat 根据目录中的 `retiredAgentIds` 显示只读提示，继续使用其他 Agent 需要新建会话。

后台清理复用受管卸载脚本和安装互斥锁，只处理现有 Alpine/Ubuntu 环境中的已知全局包及可确认归属的旧启动器，不安装缺失环境。`.dsh` 配置、会话、用户插件及共享 Node/Python 工具保留；未知或修改过的启动器保留并记录清理未完成。安装清理代码仅用于旧版本迁移，不提供恢复 Harness 的入口。

## 评审检查清单

新增或修改 Harness 时检查：

1. 主路是否仍然只使用 `AcpHarnessAdapters.forProfile(profile)`？
2. 是否把差异写成 capability，而不是在 Manager/Flutter 中判断具体 Harness ID？
3. 是否复用了 ACP session 和 `AgentEventReducer`，没有新增私有 stream event？
4. 是否复用了 OmniFlow/MCP 插件系统，没有创建 Harness 专属插件安装器？
5. Provider 和模型是否仍来自 Agent 的共享绑定，而不是静默恢复旧默认模型？
6. 配置读写、安装、MCP transport 和协议归一化是否有聚焦单测？
7. 切换 Harness 后是否验证首轮、第二轮、取消、重新连接和切回小万？

## 验证命令

```bash
./gradlew --no-daemon --no-parallel \
  :app:testDevelopStandardDebugUnitTest \
  --tests 'cn.com.omnimind.bot.agent.runtime.AgentRuntimeMcpTest' \
  --tests 'cn.com.omnimind.bot.agent.runtime.AgentRuntimeProtocolPayloadTest' \
  --tests 'cn.com.omnimind.bot.agent.runtime.AgentConfigAdaptersTest' \
  --tests 'cn.com.omnimind.bot.agent.ManagedAcpAdapterPreparationTest'

./gradlew --no-daemon --no-parallel \
  :app:assembleDevelopStandardDebug \
  -Ptarget=lib/main_standard.dart
```
