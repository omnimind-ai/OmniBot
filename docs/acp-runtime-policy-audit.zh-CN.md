# ACP 运行时策略审计记录

> **历史归档（2026-09-05）**：本文件保存 2026-09-04 阶段性判断，不作为当前实现或验收标准。文中“保留长度续传／缺失工具恢复”“保留终端、附件、配置文件和历史硬上限”等结论已被后续修改取代；不得据此恢复这些策略。当前实现、验证边界和建议以 [最新审阅记录](acp-runtime-custom-logic-inventory.zh-CN_副本.md) 与 [0.6.1 PR 汇总](pr-0.6.1-acp-ux.zh-CN.md) 为准。

日期：2026-09-04

分支：`codex/release-0.6.1`

## 结论

删除的是我们在 ACP 之上自行增加的 Agent 业务策略，不是 ACP 的必要协议能力。ACP 本身允许一次 `session/prompt` 在同一 Turn 内进行多次模型与工具交换，并由 Agent 返回官方 `PromptResponse`；它没有要求客户端用固定模型轮数、固定工具调用次数或固定上下文恢复次数提前终止。

“不设 Agent 策略上限”不等于设备无限分配内存或无限创建进程。进程取消、权限、workspace 隔离、协议校验，以及底层资源硬上限仍然属于设备运行时职责，不能删除。

## 本次删除记录

| 完成项 | 原实现 | 来源/原因 | 处理结果 |
| --- | --- | --- | --- |
| 模型调用轮数上限 | `maxModelRounds`、默认 16、达到后生成本地错误 | commit `2f197281f986dcccdef4962cfd95d5823f2aa83a`，2026-08-30；当时用于防止模型重复发工具调用 | 从 `AgentOrchestrator.Input`、设置存储、执行循环和测试删除；循环由 ACP/Provider 的终止响应、取消或真实错误结束 |
| 本地 completion token 上限 | `maxCompletionTokens` 默认 16384，并由本地 Agent 注入请求 | 同一 commit；这是本地请求策略，不是 ACP lifecycle 要求 | Agent 不再注入本地 token 上限；协议模型仍保留可由 Provider/Agent 显式提供的字段 |
| 上下文恢复轮数配置 | `maxContextOverflowRecoveryRounds` | commit `051eb54429c5d72d06e8c4001dcfd0b1d032fa95`，2026-09-02 将策略放进 Runtime Settings；最初的上下文压缩/恢复能力来自 commit `5517380aa3e3d08637f5aa1e230715a725a0cbe`，2026-08-13 | 删除配置、自动摘要替换、溢出后重试和无进展 fingerprint。Provider 溢出直接结束当前 prompt；只有用户显式 `/compact` 才可请求整理历史。 |
| 长度续传次数配置 | `maxLengthContinuationRounds` | 2026-09-02 随 Runtime Settings 引入 | 删除本地次数上限；收到 `length` 且已有内容时继续请求，直到 Provider 给出终止结果或真实错误 |
| 缺失工具恢复次数配置 | `maxMissingToolCallRecoveryRounds` | 2026-09-02 随 Runtime Settings 引入 | 删除本地次数上限；保留 ACP tool-call 配对和技能完成状态所需的恢复流程 |
| 工具结果字符策略 | `maxToolResultChars` 和基于它的 JSON offload/截断分支 | 2026-09-02 随 Runtime Settings 引入 | 删除 Runtime Settings 注入和 AgentOrchestrator 的策略截断；工具显式 `maxChars` 仍可作为单次请求参数 |
| VLM 步数/拒绝重试策略 | `maxVlmSteps`、`maxVlmRejectedRetries` | 2026-09-02 随 Runtime Settings 引入 | 删除 Agent Runtime Settings 读取和传递；VLM 工具自身只处理一次明确的工具请求 |
| 文件、浏览器、终端 Runtime Settings | `fileReadMaxChars`、`fileListMaxDepth`、`browserMaxTabs`、`terminalTimeoutSeconds` 等字段 | 2026-09-02 引入的 JSON 编辑器与 MMKV 配置 | 删除 `AgentRuntimeSettings`、MMKV 存储、JSON 编辑器、Native/Flutter 注入链路和相关测试 |
| 终端默认超时 | 直接终端会话的本地 30 秒默认值 | 本地 Handler 行为，不属于 ACP | 省略超时时不再由 Handler 设置默认值；若调用方显式提供才等待指定时间 |
| 浏览器参数默认值 | scroll amount、scroll count、backbone depth、wait selector timeout 的本地默认值 | BrowserUseEngine 的本地便利策略 | 删除解析器默认填充；需要这些参数的动作必须显式提供，省略等待时间由浏览器执行环境决定 |
| Harness 参数默认值 | DeepSeek Harness 的默认模型、推理强度和权限模式 | 本地适配器为方便启动而补的值 | 删除；缺失值交给 Harness/Provider 的官方配置流程，非法枚举值现在直接报错 |
| 私有 `turn/steer` 路径 | 将不存在于 ACP 的 `turn/steer` 当成 Agent 生命周期 | 私有兼容实现 | 兼容层保留，但明确返回不支持；旧输入只能映射到官方 `session/prompt` / `session/cancel`，不再创建第二套生命周期 |
| Dart 内置 Agent 兜底列表 | `agent_mode_setting_page.dart` 在原生目录失败时伪造六个 Agent | UI 私有数据源 | 删除；UI 只显示原生 ACP catalog，暂时失败时仅保留已经加载的目录 |
| 管理器内厂商配置分支 | `AgentRuntimeManager` 按 Agent id 读取/写入 Codex、Claude、OpenCode | 业务层直接耦合 Harness | 删除；统一通过 `AgentConfigAdapterRegistry` 和 `AgentConfigFileAccess` 调用 Harness 配置转换器 |
| Agent 配置 revision/审计/回滚 | 配置读写没有并发版本，也没有可追溯历史 | 原来缺少统一配置治理 | 在现有 `AcpAgentProfileStore` 边界补齐；快照使用 Android 官方加密 SharedPreferences，读写支持 `expectedRevision`，新增 `agent/config/rollback` |
| Agent/Harness 目录与安装描述 | `AcpAgentProfileStore`、终端安装入口和 Kotlin 常量里维护 Agent/包/脚本 | 私有目录与安装逻辑散落在业务代码 | 迁移到 `app/src/main/assets/acp/agents.json` 与 `acp/install/deepseek-harness.sh`；运行时只解析声明式目录，安装入口从目录取值 |
| Harness 配置转换器文件边界 | 公共 `AgentConfigAdapters.kt` 同时包含所有厂商实现 | 公共契约与厂商格式逻辑耦合 | 公共文件只保留契约/注册表/Provider 公共映射；各 Harness 转换器移到 `AcpHarnessConfigAdapters.kt` |

## 保留记录：这些不是要删除的 Agent 策略

- `Conversation -> ACP Session -> Turn -> Item`、官方 `session/prompt`、`session/update`、`session/cancel`、`session/close`、JSON-RPC `$/cancel_request` 的职责边界。
- 一个用户请求对应一个逻辑 Turn；请求重试不能复制已经可见的文本、推理或工具意图。
- 权限确认、workspace/path 隔离、Provider URL/API key 校验、密钥加密存储、进程取消与退出回收。
- 底层资源硬上限：终端输出缓冲区的 64 KiB/600 行保护、ACP 终端缓冲区保护、附件大小与批量大小限制、浏览器进程/标签页生命周期、插件沙箱边界、配置文件 1 MiB 上限、历史记录和数据库写入上限。
- 文件工具本身：`file_read`、`file_write`、`file_edit`、`file_list`、`file_search`、`file_stat`、`file_move`。删除的是隐藏的 Runtime Settings 和默认策略，不是文件能力。
- Transport idle/retry 字段仍可由明确的 Transport owner 或单次请求提供；它们不能重新变成第二个 Agent lifecycle，也不能在可见输出后静默重放 Turn。

以上保留项的共同点是：它们保护设备、协议一致性或数据安全，或者属于官方/底层执行环境的约束，不是“模型最多思考几轮”的产品决策。

## 本次没有引入的东西

本次没有新增 Reducer、回调总线或 Agent 私有协议，也没有把删除的轮数/超时策略搬到另一个 Kotlin 文件。保留的 Harness 配置转换器只处理官方配置格式映射；Agent 继续使用已有 ACP 边界和显式单次请求字段。配置 revision/审计/回滚复用了现有 profile store 边界，不是第二套 Agent 生命周期。

新增 Harness 的最小接入面现在是目录条目：填写 ACP 启动命令、包/健康检查、安装资源和可选配置转换器标识即可；未知转换器默认走标准 ACP，不能修改会话/Turn 生命周期。只有需要非标准配置格式时，才增加独立 Harness 转换器。

## 验证记录

- `./gradlew --no-daemon --no-parallel :app:compileDevelopStandardDebugKotlin`：通过。
- `./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.agent.AgentOrchestratorTest`：通过。
- `./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest`：通过，`BUILD SUCCESSFUL`。
- 全局搜索确认没有残留 `runtimeSettings`、`AgentRuntimeSettings`、`maxModelRounds`、`maxVlmSteps`、`maxToolResultChars`、`fileReadMaxChars`、`fileListMaxDepth`、`browserMaxTabs` 或 `terminalTimeoutSeconds` 的旧 Runtime Settings 引用。
- 配置读写新增 `revision`、`audit`、`expectedRevision` 和 `agent/config/rollback`；快照不写入普通 SharedPreferences。
- `jq empty app/src/main/assets/acp/agents.json`：通过；目录安装脚本由测试读取同一份资源，未再复制 Kotlin 安装脚本。
