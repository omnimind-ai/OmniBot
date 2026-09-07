# 本地 Agent/ACP 测试集

统一入口是：

```bash
scripts/test-agent-runtime.sh
```

它会自动运行：

- Node 协议与 Provider 请求构造测试；
- Android/JVM 的 ACP 状态、Provider fallback、Harness 准备测试；
- Flutter Agent 页面与运行时服务测试。

如果需要真实联网调用 Provider，只在当前 shell 注入测试 Token，然后追加 `--live`：

```bash
export OMNIBOT_TEST_API_KEY='测试 token'
export OMNIBOT_TEST_BASE_URL='https://your-provider.example/v1'
export OMNIBOT_TEST_MODEL='glm-5.1'
scripts/test-agent-runtime.sh --live
```

Token 不会写入仓库或打印。真实 smoke 执行一次 `/models` 和一次短的非流式 `/chat/completions`，最大输出 1024 tokens；正文为空或仅有思考不算通过。

没有 Token 时，默认只运行本地测试；显式使用 `--offline` 可以强制跳过真实 Provider 请求：

```bash
scripts/test-agent-runtime.sh --offline
```

当前测试集覆盖的关键行为是：检测已安装 Harness 不会触发 npm/node-gyp 下载；检测会把可运行 Harness 标记为 `online`；Provider `/models` 暂时不可用时仍保留已绑定模型；Agent 页面检测和显式 Harness 初始化不会混用。

不要把 Token 写入 `.env`、脚本或提交记录。建议通过 shell profile、密码管理器或 CI secret 注入 `OMNIBOT_TEST_API_KEY`。

## 长期验证规则

### 会话输出是必过项（2026-09-07）

真实 API 全链路入口（使用现有 LLMTHU 环境变量或 `OMNIBOT_TEST_*` 覆盖）：

```bash
export OMNIBOT_TEST_MODEL='GLM-5.1'
export OMNIBOT_TEST_SECOND_MODEL='GLM-5.2'
scripts/test-agent-runtime.sh --live-harnesses /path/to/disposable-harness-installation
```

`verify-live-harness-conversations.mjs` 默认逐一测试 Codex、Claude Code、Kimi、
OpenCode、DeepSeek Harness。它复用原生生成的配置及透明本地转发器，模型回答
来自所配置的真实 API。每个 Harness 使用独立临时目录和会话，结束后清理本次
临时凭据与测试文件；不会更改手机配置或用户工作区。

每个 Harness 的测试包括：非空且符合测试要求的正文；同会话记住随机口令；
实际工具写文件并产生可读取 stdout；切换第二个 Provider 模型并检查实际请求；
官方声明的思考选项及请求值；进程重启后按官方能力恢复同一会话；官方取消和
取消后的下一轮对话。官方未声明思考设置或 session/load 时明确记录不支持，
不得伪造选项或另建恢复机制。这种记录不意味着相应能力已通过实测。

API 错误、缺少正文、错误模型、工具未执行、输出不可读均导致非零退出。
前置失败导致未执行的项目逐项标记 `notRun`，不会记作通过；各 Harness 互相
独立，某个失败仍会继续测试其他 Harness。结果 JSON 只保留断言结果、模型、
参数、状态与耗时，不输出密钥或完整对话。服务商不兼容会保留为失败结果，不
会自动改端点、重放用户请求或把本地模拟响应当成真实输出。

这验证官方 CLI 至真实 API 的链路；手机 UI、Android 工具环境及设备重启仍
需要下述设备验收。不能将主机进程重启测试冒充手机重启。

### 可重复的真实设备操作

`scripts/verify-agent-user-journey.mjs` 从版本化 JSON 场景执行实际界面操作，
复用现有点击和输入脚本。它不直接调用 ACP、不修改数据库，也不伪造回复。
手机使用已配置的真实 API；凭据不进入场景文件。当前场景目录为
`scripts/fixtures/agent-user-journeys/`，前置页面和所需模型写在每份场景中。

```bash
OOB_ALLOW_PHYSICAL_DEVICE=1 node scripts/verify-agent-user-journey.mjs \
  DEVICE_SERIAL scripts/fixtures/agent-user-journeys/xiaowan-model-switch.zh.json \
  /tmp/oob-device-model-evidence
```

真机需要显式启用 `OOB_ALLOW_PHYSICAL_DEVICE=1`；模拟器传明确 serial 即可。
执行前确认没有用户正在输入或生成的回合。同一设备的构建安装和界面操作必须
由一个任务独占，另一任务先交还设备，防止安装中断请求造成错误结论。

场景包括真实搜索、点击模型、输入发送、正文验证以及重启后的继续发送。
每次执行给测试口令加唯一后缀，旧历史不能误算本次成功；用户气泡和输入草稿
不能算助手正文。每步保存截图、耗时及结果，失败停止，不自动重发。
UIAutomator 暂时无法读取窗口时，只在当前步骤期限内重复读取，不使用旧坐标。
截图仅存调用者指定目录，可能包含已有历史，不自动加入 Git。

这些场景目前在逐项真机验收中，**尚未覆盖或通过所有 Harness 的完整 demo**。
`kimi-current-conversation.zh.json` 的首轮真机记录存在连接失败与取消；
`xiaowan-model-switch.zh.json` 已走到实际发送，但回复阶段遇到设备读取失败，
不能算整个场景通过。环境安装、新功能/MCP、全部 Harness 切换和恢复的端到端
场景仍须补齐，不能以 CLI 测试或本地单元测试替代。

修改 Harness、ACP、模型配置或聊天展示后，只有下面的输出回归和相关设备验收
通过，才可标记对应功能验收通过。HTTP 200、初始化成功、工具卡片显示成功或
单独收到 `end_turn` 都不能代替实际输出。

复用本文件的统一入口，传入隔离的官方 CLI 测试安装目录：

```bash
scripts/test-agent-runtime.sh --offline --harnesses /path/to/disposable-harness-installation
```

该目录的 `node_modules` 应安装与 `app/src/main/assets/acp/agents.json` 一致的
Codex、Codex ACP、Claude Code ACP 及其依赖。测试不自动下载依赖、不使用真实
凭据。Codex 用例会把应用随附的版本校验补丁应用到该隔离目录，不得指向日常
使用的 CLI 安装。配置夹具由本次 `AgentAdapterCatalogTest` 生成。

| 必过行为 | 持久测试与断言 |
| --- | --- |
| Claude Code 输出会话正文 | `verify-installed-harness-adapters.mjs` 的 `conversation` 场景：第一轮精确得到 `OK`，同一 ACP session 的第二轮精确得到 `FOLLOWUP_OK`；两轮都必须 `end_turn` |
| Claude Code 接续上一轮 | 第二轮真实发出的 Anthropic 请求必须包含第一轮 assistant 历史；其他 session 的正文导致失败 |
| Claude Code 工具结果可读取 | `verify-claude-terminal-output.mjs` 使用生成的能力配置调用官方转换器，成功/失败终端输出均保留为标准 ACP text；不得声明未消费的私有输出扩展 |
| Codex 不丢正文、不重复 | `verify-codex-completed-messages.mjs`：完整终态正文、部分增量、正常增量都必须精确得到一次 `OK`；错误不得混入正常答案 |
| 聊天页实际渲染、历史保留 | `chat_message_list_test.dart` 的 Claude Code 用例：共享 reducer 接收两轮 ACP 正文，完成后、重复通知后及序列化重新挂载后，两个回答都各显示一次 |

三种 Claude Code Provider 配置均测试相同官方 Messages wire；Codex 两种 Provider
wire 配置均测试官方 Responses wire。这是**真实官方 CLI + 确定性本地 HTTP
夹具**的自动化测试，不等于真实模型推理或手机端到端验收。

未传 `--harnesses` 时，脚本明确输出 `Harness output acceptance INCOMPLETE`，
不得据此声称 Harness 验收通过。缺少依赖、配置夹具、正文、历史或必要终态，
测试应失败，不准通过跳过/放宽断言消除失败。使用 `--skip-*` 的结果只涵盖实际
执行的部分，不能替代完整门禁。

涉及设备行为的发布验收还需记录：APK hash/设备、真实正文、同会话追问、工具
stdout/错误、离开返回与重启后的历史。每项标明通过/失败/未执行；必要项未执行
就是验收未完成。CLI 回归与历史序列化测试不能冒充手机重启测试。

后续修复必须先证明不变量，再修改实现；单个现象不得触发全局重构。验证按
“Provider/协议 -> Harness 适配器 -> ACP runtime -> UI/模拟器 -> 真实在线端点”
分层进行，每一层都要记录通过、失败、未覆盖和失败归属。

### 不可更改的不变量

- 一个用户发送对应一个逻辑 ACP `turnId`；网络重试不能创建第二个用户可见回合。
- `conversationId`、`sessionId`、`turnId`、`messageId`、`toolCallId` 是归属键；旧事件不得借用当前回合。
- Conversation history 是用户可见事实源；ACP echo、replay、reconnect 只能幂等合并。
- Agent 生命周期只走官方 ACP session/turn/update/cancel/close 语义；不得恢复私有 stream 协议、第二 reducer 或第二状态机。
- Provider 能力必须由配置/目录或官方协议证明；适配器不得猜测、静默换路或吞掉终态错误。

### 已与官方对齐的部分

- ACP 的 `session/prompt -> session/update -> PromptResponse` 生命周期及 `session/cancel`。
- Codex 的 Responses 配置、`env_http_headers` 和独立认证文件入口。
- Claude Code 的 Anthropic Messages endpoint；DeepSeek 官方 Anthropic 路径只在对应 Provider endpoint 上使用。
- OpenCode 的官方 provider 配置结构和环境变量引用。
- Provider 自定义 Header 由共享 Provider 编辑并映射到各官方 Harness surface，不复制成第二事实源。

### 可以继续调整的部分

- Provider 的在线超时预算、重试次数和测试用例规模，但必须保持“输出后不重放、不换路”。
- Harness 配置生成器的字段映射，只能根据对应版本的官方配置契约和真实失败修改。
- 测试脚本的在线覆盖范围；在线测试不得成为默认 CI 的不稳定依赖，也不得输出凭证。
- UI 的状态呈现与日志细节，只能投影已被 runtime 证明的状态。

每次提交的测试报告必须单独标记：已验证不变量、尚未验证的不变量、属于上游环境
还是本地代码、可修改项、不可修改项，以及哪些行为已经对齐官方且以后不得重新引入旁路。
