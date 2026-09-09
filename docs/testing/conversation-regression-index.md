# 对话驱动的长期回归测试索引

## 2026-09-09：新请求错误详情验收与测试环境恢复

最终：等待共用设备任务正式结束后进入新的 Codex 会话，以本地 GLM-5.1 实际运行 `1788960584626`，14/14 步通过：唯一命令的实际 182 失败及说明持久化、下一轮普通回复、重启复核。错误处理通过不等于沙箱通过；待真机验证。下方为此前环境恢复过程。

新增实际工具说明与退出码一致的 DETAIL journey，原沙箱 EXIT 验收不放宽；31 项 verifier 测试通过。原临时 AVD 数据目录丢失，发送前中止；已新建持久 AVD、安装候选包、确认本地 Provider，并通过 App 启动 Alpine/Codex 安装。新请求尚未验收，待真机验证。见 [记录](codex-live-error-detail-20260909.md)。

## 2026-09-09：ACP 命令失败详情

最终追加：解析收敛至共享工具解析器，旧事件与旧卡片空详情复用；217 项 Flutter、48 项 Node 通过。模拟器两次重启展开既有失败记录的 10 步全部通过；原始退出码显示恢复，无命令重放。最终 APK d1dc4920…，待真机验证；新发命令的实际显示仍待验证。详见下方记录的最终实现章节。

共享投影规范化 `exit_code` / `formatted_output`，已终止且缺少说明的终端工具显示实际退出码，官方状态不变。新增六项，reducer 共 206 项通过；APK 构建并保留数据安装成功，实际卡片和历史恢复待验证、待真机验证。见 [记录](acp-command-exit-detail-20260909.md)。沙箱本身未修复。

## 2026-09-09：Codex 182 最小描述符执行复现

追加实际第五项：直接按路径启动内置 bwrap 仍需经过 sandbox，返回 1 / overflowuid 读取被拒绝。它阻止“绕过描述符加载就算沙箱可用”的错误验收。五项对照结果已保存，产品修复未完成。

`verify-codex-sandbox.py --fd-exec` 在 App UID 上实际比较四种执行路径：普通 shell 与可继承描述符成功，不可继承描述符和 Codex 沙箱均返回 182。已定位 PRoot 加载兼容性，修复尚未完成，不修改权限绕过。见 [证据](codex-terminal-exit-20260909.md)。待真机验证。

## 2026-09-09：生命周期设置收尾

配置等待关闭锁后复查会话关闭、取消等待设置后释放等待且保持原锁所有权：新增 `XiaowanSessionAdmissionTest` 两项，连同 worker、关闭、删除和配置共 30 项实际通过，零跳过。本次仅增加测试，没有产品改动或新设备运行，待真机验证。见 [设置生命周期记录](xiaowan-session-config-admission.md) 及 `artifacts/lifecycle-settings-final-20260909/`。

## 2026-09-09：上下文圈默认零值

UI-CONTEXT-004：暂无用量时画 0 进度空环，不叠加问号；大小输入框保留长按阈值操作，有效用量按原数据更新。复用 `chat_input_area_test.dart`，兼容基线与历史确认卡合计 45 项通过；当前主线新工具链尚未就绪。安装与设备结果见 [验证记录](context-ring-zero-2026-09-09.md)，待真机验证。

## 2026-09-09：历史确认卡误报标识缺失

CODEX-HISTORY-REQUEST-003：历史确认卡展示已有结果，不将 requestId 缺失作为错误提示；失效输入不提示继续回复，缺少身份不能猜测已批准。`agent_request_card_test.dart` 新增三项；隔离合并前基线先失败、修复后 15 项通过。当前主线工具链/依赖阻塞，APK/模拟器重开待验证，待真机验证。详见 [记录](historical-request-notice-2026-09-09.md)。

## 2026-09-08：对话列表打开掉帧

UI-DRAWER-001：300 条合成历史首屏只构建可见范围，末行滚动可达；复用 `home_drawer_test.dart`，修复前 300 行挂载导致断言失败，修复后少于 30 行且滚动可达。20 项抽屉测试、1187 项 Flutter 全量测试及少量历史模拟器打开/重开/重启检查通过。大历史设备帧时间未测，待真机验证。详见 [记录](drawer-lazy-2026-09-08.md)。

## 2026-09-08：40 步统一模拟器测试集

CTX-SUITE-040：原图读取、连续长任务、实际自动摘要和重启恢复。统一入口 `scripts/verify-xiaowan-context-suite.mjs` 顺序复用既有 16 + 17 + 7 步 UI journey；数据生成器与准备、执行、恢复配置命令见 [测试说明](xiaowan-context-40.md)。缺步、失败、错误设备、缺少本次摘要请求或原图校验均不能通过，旧日志不能替代新运行证据。

状态：3 项入口判定测试通过；合成数据重复生成一致、PNG CRC 和解码长度验证通过；离线核对既有 40 步真实报告及 104 条 Provider 记录兼容。既有三组模拟器操作已通过，新增统一入口的完整设备重跑待执行，待真机验证。

## 2026-09-08：六项长上下文反馈复核

以 `context-six-audit-2026-09-08.md` 为本轮证据，纠正此前“最终回复丢失”的未证实判断。
新增可执行回归：`AgentContextOverflowTest`、`AgentConversationContextCompactorTest` 的低阈值/摘要模型容量、不可压缩 schema、超大摘要输入、跨工具统一落盘；`AgentOrchestratorTest` 使用生产压缩器验证真实报错文字、最多一次恢复及已开始输出不重放。
进一步复测发现历史已完成而页面仍旧的独立竞态：`conversation_manager_lifecycle_test.dart` 固定 metadata/history 等待期间当前 runtime 增加消息的场景。加载路径必须在 I/O 后读取当前 owner，不能安装 I/O 前的副本。保留本轮一次失败的 UI 记录，不把持久化成功冒充实时显示通过。
`xiaowan-context-overflow.en.json` 增加 `turn-outcome`，在可见回复、重启恢复、继续任务之间检查唯一用户输入、session/turn 身份及唯一 `end_turn`。
连续复测捕获会话 15 在压缩前恢复工具历史时 OOM：新增 `AgentHistoryToolOutputProjectionTest` 逐页处理 120 个大结果、完整 offload、调用/检查点身份、共享预算、落盘失败回归。`XiaowanAcpConnectionTest` 注入实际 server scope 致命错误，验证既有 connection exit 结束等待。上下文 journey 增加第三长任务和其后普通消息；fixture 用 ADB 读取实际 offload 原文校验，不能跳过内容检查。原始 OOM 和中间失败证据都保留。
`xiaowan-context-summary.en.json` 专门增长同一任务的助手内容，确认实际发出摘要请求。第一次 20 次读取完成但检查点断言失败：原查找在 ACP 异步投影落盘前返回 null，丢弃摘要的持久化机会。增加 Room 完成记录屏障及 `checkpoint awaits committed tool identity instead of dropping a summary during projection lag`，并要求检查点跨重启保持一致；最终状态见本轮报告，不用仅落盘工具结果的测试代替摘要验收。
状态：相关 JVM 测试与模拟器回归通过；原服务商长度单位仍未知，待真机验证。下方旧审计记录保留时间背景，不代表这些风险均已证实。

每次工作对话将用户的实际需求、故障与验收条件加入此索引，并链接既有可执行测试及证据。清单不等于已实现测试；模拟器通过不等于真机验收。原始聊天记录不作为可公开测试数据，样本应脱敏。

## 2026-09-07：小万读取文件与长上下文

更新：用户已授权先用 Kotlin 移植。CTX-001 至 CTX-005 的实现与新增可执行回归见 `context-compaction-fix-2026-09-07.md`；144 项本地测试与连续读取/重启模拟器回归通过，仍待真机验证。下表中“待实现/未修复”为最初审计状态，以该修复报告逐项描述的覆盖与限制为准。CTX-006 配置同步问题不在本次修改范围。

| ID | 用户场景及预期 | 已有入口或记录 | 当前状态 |
| --- | --- | --- | --- |
| FILE-001 | 大图读出后显示，模型收到原图，App 不退出 | `scripts/fixtures/agent-user-journeys/xiaowan-file-read-regression.en.json`；`image-read-crash-2026-09-07.md` | 模拟器验证通过；待真机验证 |
| FILE-002 | 大 HTML/文本分段读取，边界和末页准确，不全量撑爆内存 | 同一 UI journey；`file-read-memory-2026-09-07.md` | 模拟器验证通过；待真机验证 |
| FILE-003 | 工具结果重复字段不反复进入模型请求；重启后原始内容仍可查看 | `XiaowanToolResultPayloadTest`、`AgentEventAdapterTest`；`xiaowan-tool-result-dedup-2026-09-07.md` | 本地与模拟器验证通过；待真机验证 |
| LIFE-001 | 读取后取消，继续发送；重启恢复完成和取消状态；并行会话不串结果 | `xiaowan-emulator-acceptance-2026-09-07.md` 和其 artifacts 中各 journey/result | 模拟器验证含人工补测；不是全自动通过；待真机验证 |
| CTX-001 | 用户报告输入 1,119,534 超过服务上限 1,048,566；首次恢复即超限也应在发送前处理 | `AgentOrchestratorTest.providerPromptLengthRejectionTriggersOneCanonicalPreOutputCompactionRecovery`；`xiaowan-long-context-audit-2026-09-07.md` | JVM 请求拒绝 fixture 配合生产压缩器验证单次恢复；不是原服务商网络复现；原服务长度单位和真机仍待验证 |
| CTX-002 | 同一条用户任务中工具不断累积，能压缩已完成片段，保留任务及调用配对，不重放工具 | 同上；现有 `AgentOrchestratorTest` 尚不覆盖此边界 | 待实现回归用例；未修复 |
| CTX-003 | 服务不返回 usage，或一次/并行工具结果突然增大，仍能在下一请求前维护预算 | 同上 | 待实现回归用例；未修复 |
| CTX-004 | 摘要请求本身不超限；摘要失败、过长或取消，不错误提交检查点、不继续发送已知超限原文 | `AgentConversationContextCompactorTest` 有失败不提交及取消覆盖；其余见审计 | 部分已有测试；新增边界待实现；未修复 |
| CTX-005 | 小万子任务独立维护上下文，不能污染父历史 | 同上；现有子任务测试尚不覆盖压缩 | 待实现回归用例；未修复 |
| CTX-006 | 用户压缩阈值与模型容量区分；切换小容量模型、刷新配置、重启后保持正确 | 同上 | 待实现回归用例与真机复现 |

可执行文件读取 journey 由 `scripts/verify-agent-user-journey.mjs` 运行，测试 Provider 为 `scripts/fixtures/file-read-provider.mjs`，详细样本准备和命令见模拟器报告。它没有模拟 CTX-001 的服务端硬长度上限，不能替代长上下文测试。

本次对话更早的启动延迟、DSH 安装/沙箱/权限和阈值 UI 等反馈也应归档为长期用例；需核对其已有测试入口和实际验收记录，不能从聊天中的“通过”描述推定当前版本已验收。

## 压缩实现约束

下一步先评估可直接复用的成熟压缩组件，核实许可证、上游版本、Android/Kotlin 运行方式、多模型支持、同一任务压缩和持久化恢复契约。将上面的场景作为选型与集成的验收条件。

上游与历史核对结果见 `context-compaction-upstream-decision-2026-09-07.md`：Pi 为首选评估对象，尚未集成。旧版错误识别器的 21 条规则也不能匹配本次错误文字；该文字应作为 CTX-001 的固定回归输入，不能仅用另一种 `context_length_exceeded` 消息代替。

此前审计中的“分批摘要、预算检查、当前任务检查点”等是行为需求，不是授权自行设计另一套算法。没有完成复用评估前，不按该清单从零编写压缩引擎；如无法直接复用，记录具体障碍和方案后再讨论。

## 2026-09-07 上下文数据边界整体验证

配置/usage/模型能力分离，重复压缩、摘要异常结束、数据库检查点与去重分页、部分和空快照不删除历史。执行入口 `scripts/test-context-boundaries.sh`；详细结果及未完成项见 [context-boundaries-2026-09-07.md](context-boundaries-2026-09-07.md)。待真机验证。

- 2026-09-08 后续上下文审计：`context-audit-2026-09-08.md`。新增工具结果文件化后检查点身份、明确清除后迟到快照两项可执行测试，均已运行并失败；尚未修复，待真机验证。


## 2026-09-08 检查点与记忆恢复

上述审计失败是修复前状态。修复及执行证据见 [memory-recovery-2026-09-08.md](memory-recovery-2026-09-08.md)。

| ID | 用户场景与预期 | 可执行入口 | 结果 |
| --- | --- | --- | --- |
| CTX-007 | 文件化结果仍定位同一工具组；展示、回写和重启不丢身份 | AgentConversationHistoryRepositoryTest、AgentConversationHistorySupportTest；xiaowan-checkpoint-restart.en.json | 定向测试及模拟器 11 步通过；待真机验证 |
| CTX-008 | 清除或新摘要后，旧快照和迟到摘要不能覆盖检查点 | ConversationCheckpointTest | SQLite instrumentation 通过；待真机验证 |
| HIST-001 | 长历史只加载有限展示正文，预览回写不能覆盖完整记录 | AgentConversationHistorySupportTest、AgentConversationHistoryRepositoryTest；两组 UI journey | 定向测试及原累积会话模拟器恢复通过；待真机验证 |
| MEM-001 | 长期和每日记忆可写入、检索，重启后仍能读取 | AgentSystemPromptTest、*Memory*Test；xiaowan-memory-restart.en.json | 定向测试及模拟器 7 步通过；真实模型召回质量未验证；待真机验证 |

统一测试入口 scripts/test-context-boundaries.sh；UI 入口 scripts/verify-agent-user-journey.mjs。使用合成独立标记及本机 Provider，证据保留失败与最终通过记录，不复制私人记忆正文。

## 2026-09-08 DSH 沙箱验收

DSH-SANDBOX-001：默认 read-only / workspace-write 应能执行受限命令，不能用普通 shell 或完全访问成功替代。新增可执行入口 scripts/verify-dsh-sandbox.py，直接加载设备已安装官方沙箱实现；两次均实际运行并失败（SANDBOX_UNAVAILABLE）。此入口仅覆盖后端就绪必要条件，完整隔离/UI/重启测试尚未完成。见 [验收报告](dsh-sandbox-acceptance-2026-09-08.md)。待真机验证。

## 2026-09-08 Codex Plan 无输出

CODEX-PLAN-001：声明 Plan 能力后，官方计划快照必须通过 SDK 解码并进入共享展示；计划删除使用相同身份。先复现 MissingFieldException，再通过 AcpSessionUpdateMapperTest 的计划形状、幂等和无关字段回归。真实官方 CLI 的 plan 场景已实现并在两种 Provider 配置运行通过；手机完整 UI/重启验收待执行，待真机验证。见 [报告](codex-plan-output-2026-09-08.md)。

Codex Plan 补验：用户指定模拟器验收，实际 UI 27 步（计划、拒绝实施、重启恢复、Default 回复与再次恢复）通过。新增 CODEX-PLAN-002：已拒绝确认重启后不得出现可操作按钮；入口 codex-plan-resolved-approval.en.json，已运行并失败，未修复。两类结果分开记录，见 Codex Plan 报告。

### 2026-09-09 文件搜索上限与取消传播

- 用户反馈：读取附件后搜索工作区中断，后续任务显示失败。此次只确认并修正搜索遍历上限与取消传播，不把截图中断原因推断为内存溢出。
- 可执行测试：`app/src/test/java/cn/com/omnimind/bot/agent/tool/handlers/FileSearchTraversalTest.kt`，已纳入 `scripts/test-agent-runtime.sh` 的 Android 测试入口。
- 覆盖：达到上限后不枚举下一个文件、非匹配项不消耗上限、中文/空格路径、空结果、最后一个结果产生时取消、读取取消异常传播、致命错误不被当作无匹配、取消后独立操作、40 次重复操作无残留。
- 执行结果：使用本机 Kotlin 编译器编译生产 traversal 与上述 JUnit 测试，Android Studio JBR 运行，6 项通过。该结果不包含完整 Android handler 编译或设备操作验收。
- 尚未完成：成熟搜索程序接入、文件名/正文能力拆分、长行/二进制扫描、大目录模拟器端到端回归；待真机验证。现有未指定 maxResults 的完整结果语义暂未修改。
- 补充执行：`bash scripts/test-agent-runtime.sh --offline --skip-gradle --skip-flutter --skip-webchat`，80 项 Node + 7 项 Python 通过；真实 Provider、Harness、Android/Flutter 和真机验收均未包含在该命令内。

### 2026-09-09 小万命令菜单与构建版本对齐

- 当前 `XiaowanAcpConnection.postInitialize` 只通过官方 AvailableCommandsUpdate 发布 compact。但继续追到前端确认 init 是明确标注的“生成或更新 AGENTS.md”提示词快捷操作，通过普通 prompt 发送，并非原生命令。菜单用例仍应检查 init；曾改为 absent 的误判已撤回。plan 按声明模式显示，review 按声明命令显示。
- emulator-45562 的已安装兼容包（versionName 0.6.2.2 / code 14 / target 35）运行旧 6 步用例通过，显示 init。该包与当前源码不一致，不能用这项成功宣称当前源码验收通过。菜单显示 init 本身并不证明版本差异；该包是兼容包的结论来自其构建基线。旧用例证据保留于本机 /tmp/oob-command-capabilities-20260909，未将私有截图入库。
- 同一菜单用例仍需在当前源码对应 APK 上运行；真机验收未完成。

### 2026-09-09 ACP 取消结束结果在背压下丢失

- 复现：原代码在已取消 worker 内 send CANCELLED 并 runCatching；慢消费者/无缓冲 channel 下只收到 running，send 抛 JobCancellationException，被吞掉。缓冲充足时收到 cancelled，因此具有时序相关性。该协程复现尚不能证明所有用户失败都由此引起。
- 修复：保持 Conversation -> ACP Session -> Turn -> Item 及现有 promptMutex / activePromptJob；工作协程只执行和记录取消，存活的 prompt collector 在 join 后发送原来的官方 PromptResponse(CANCELLED)。没有 NonCancellable 无限发送、私有终态或第二次执行。
- 可执行入口：XiaowanPromptWorkerTest，已加入 scripts/test-agent-runtime.sh。4 项本机 JVM 测试通过，包括 40 轮 x 两种缓冲设置的取消/后续任务、普通完成、工具取消异常、接收端断开。
- 限制：完整 Android 构建、模拟器安装回归与真机验收仍待执行；小万取消当前 turn 不等于暂停并恢复同一个执行栈。
- 回归敏感性核验：将相同测试临时运行于旧 worker 内发送/吞异常逻辑，4 项中 1 项失败（缺少 cancelled）；恢复生产修复后 4 项全通过。临时旧逻辑未写入仓库。
- 后续边界：已新增“执行 worker 尚未启动即停止”的用例。修复前未返回任何结束结果，修复后通过；最终 5 项 JVM 测试通过。
- 真实 Provider 补充：使用本地已有 LLMTHU 配置执行 `node scripts/agent_provider_smoke.mjs`，GLM-5.1 模型目录和真实 completion 通过；这是单次真实连接证据，不覆盖所有请求、拒绝、额度和超时场景，也不替代 App 端到端测试。
- 构建环境补齐：从 Flutter 官方 3.47.2 tag d3b14c876900e553bc736ca19295fc09e3853e8e 安装独立 SDK，已安装 Android platforms;android-37.0 与 build-tools;37.0.0；当前完整构建仍未完成。
- 当前源码 Flutter 验证：Flutter 3.47.2 下运行 agent_event_reducer_test、chat_conversation_runtime_coordinator_test、agent_slash_commands_test，共 296 项通过。未使用旧兼容 SDK 或 APK 替代本次源码验证。
- 补充会话边界：XiaowanPromptWorkerTest 现为 7 项通过，新增跨会话重复停止隔离、执行错误不被改写为取消/成功。当前 Flutter 权限卡片/输入状态/错误格式/终端输出四组测试 21 项通过。

### 2026-09-09 重复停止与恢复的设备回归入口

- `scripts/fixtures/agent-user-journeys/xiaowan-repeat-cancel-recovery.en.json`：43 步，五轮流式输出等待/停止/官方取消终态/下一条成功，随后重启并检查两种终态的历史。执行入口为既有 verify-agent-user-journey.mjs，配套既有 file-read-provider.mjs 的 WAIT/OK 故障分支。
- 服务端不会在 WAIT 中伪造完成；流关闭时释放定时器并记录 completed=false。重复用例保留完整轮次和运行标记，避免旧结果冒充当前请求。
- 已执行：新增真实本地 HTTP fixture 测试 2 项通过；持久化终态 verifier 测试 11 项通过，拒绝缺少官方取消原因、仍 loading、错误被当作取消。
- 43 步设备用例尚未执行，不计为模拟器或真机通过。Android JVM 构建已完成 Gradle 官方 SHA-256 校验后重新启动，仍在进行。
- 取消出口统一：执行器返回 AgentResult.Error(CancellationException) 的兼容路径也交给 prompt collector 收尾，移除了 worker 内直接发送取消结果的第二处代码。完整 Android 构建已进入模块编译阶段，最终结果待确认。

- 最终本轮验证：当前源码完整 Android 73 项测试通过，APK 构建及 emulator-45562 覆盖安装成功；43 步重复停止/继续/重启回归全部通过。Provider 日志确认 10 个唯一请求、无重复发送、5 个取消流关闭。已恢复原有 GLM-5.1 场景绑定并关闭本轮故障服务。证据见 artifacts/lifecycle-cancel-20260909/。该结果仅覆盖本轮取消链路，文件搜索完整改造、其他失败类别的设备回归与真机验收仍未完成。

### 2026-09-09 工具失败恢复与接口错误回归

- 当前 APK 的工具失败 18 步模拟器回归通过：未知工具、坏 JSON 参数、不存在文件、真实 exit 7、真实命令超时，以及重启后完成状态。证据位于 artifacts/tool-failures-20260909/。增强持久化校验，不能用权限错误冒充文件不存在或参数错误；15 项 verifier 单元测试通过，五个设备 turn 重新校验通过。
- 接口错误回归首轮在第 19 步失败：文本选择浮层消费了发送点击，草稿保留；SQLite 查询证实该合成 marker 未被接收，服务端无请求。保留 first-run-failed.json，未计为完整通过。
- 测试驱动改为发送前检测并关闭实际可见的 composer 选择浮层，仍只点击一次 Send，不重放已提交消息。2 项 XML/浮层识别测试通过。仅清除已确认未提交的本轮合成草稿，使用新的运行标记重跑全部接口用例；前端产品代码未因此修改。

- 后续整套接口回归仍在第 19 步失败，保留 second/third/fourth-run-failed.json。发送前布局稳定检查、保留 IME 均未解决，因此此前将问题完全归因于测试驱动的判断不充分。额外 UI 观察中，Android Back 确实会在无 IME 时退出 Activity，驱动已移除无条件 Back，并记录发送前可访问性位置。
- 限流后的合成草稿经额外人工定向 UI 点击后实际入库、完成文件读取并得到 end_turn；没有重新提交已接收消息。这仅证明会话未永久锁死，不能冒充一次点击发送/整套回归通过。该输入区问题继续待定位，证据 manual-rate-recovery.json。
- 离线 Node 83 项、journal verifier 15 项通过；补充 ACP connection/presentation 46 项 Android 测试通过。其他 Harness/真机验收仍未完成。
- 将既有 provider-failures 用例第 22–45 步原样提取单独运行：服务端 503、输出前断流、部分输出后断流，每类错误后执行真实 file_read，再重启验证，共 24 步通过。对应三个错误请求均未重放。证据 remaining-journey.json/remaining-provider-events.json；这不等于原 45 步整套通过。
- 当前实际 APK 的命令菜单 6 步验证通过：显示 init/compact、不显示未支持的 plan/review，清理本轮 `/` 草稿。菜单证据 command-capabilities.json。最后核对恢复原 GLM-5.1 dispatch/compactor 场景绑定，终止本轮测试 Provider；物理设备验收仍待进行。

### 2026-09-09 输入框隐藏选择手柄吞掉发送点击

- 修正此前推断：限流后发送失败的直接原因不是 ACP 取消/重试。独立 7 步用例、发送前截图和全局命中日志证明：相同坐标在成功时命中按钮，失败时命中文本选择手柄 Overlay（RenderExcludeSemantics/RenderAnimatedOpacity）；失败点击未进入按钮或聊天页指针回调。隐藏手柄仍接收触摸，特定草稿长度使它覆盖发送按钮。
- 上游核对：Flutter 3.47.2 `widgets/text_selection.dart` 的 `_SelectionHandleOverlay` 使用 FadeTransition；官方文档明确透明度为零不会禁用命中：https://api.flutter.dev/flutter/widgets/FadeTransition-class.html 。不改本机 SDK，不新增 Agent 生命周期或自动重发。
- 最小应用修复：共享 composer TextField 的 onChanged 通过公开 TextSelectionGestureDetectorBuilderDelegate / EditableTextState.hideToolbar 移除旧选择覆盖层。下一次长按仍恢复正常选择/复制菜单。临时诊断代码已全部移除。
- 可执行回归：`ui/test/features/home/pages/command_overlay/widgets/chat_input_area_test.dart` 新增隐藏手柄测试（旧实现红、修复后绿），包含连续三次输入→发送→再次长按选择/复制菜单；全部输入区 31 项通过，重复用例通过，定向分析无问题。
- 设备入口：`node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/xiaowan-selection-overlay-recovery.en.json /tmp/oob-selection-overlay-run`。原 provider-failures 45 步正在新修复 APK 上运行，尚未计为通过；证据目录 artifacts/selection-overlay-20260909/。待真机验证。
- 最终设备结果：修复 APK（SHA-256 a51940aef8e71f92d04dbf1b13157048c7d8e8d1df2039966e871c44e6d844f6）在 emulator-45562 完整 45 步通过，包括原先连续失败的第 19 步一次发送、6 类接口失败后的真实文件读取及重启历史。服务端验证 6 个失败请求均仅一次、6 个恢复工具结果均有效。证据 after-fix-journey.json/after-fix-provider-events.json；仍待真机验证，未发布。

### 2026-09-09 真实 API 多工具长任务的环境与终态检查

- 首次真实任务未执行任何工具，正式 stopReason=error：旧 ACP 会话仍使用测试服务地址 10.0.2.2:18879。全局 scene binding 恢复不等于覆盖会话内模型选择，保留失败证据 initial-route-failure.json，未重放该轮。
- 模拟器系统时间停在 2023-10-16、auto_time=0，真实服务商模型目录出现 TLS 校验失败。仅校正测试模拟器时间并开启自动校时后，真实服务商目录成功加载 95 个模型；未关闭或降低证书校验。通过 App 模型选择入口选中 GLM-5.1，并再次打开设置验证。
- 加强现有 xiaowan-local-api-long-task 用例：每次完整回复及重启后都要求 canonical end_turn，再检查实际工具结果；真实网络用例运行前要求设备时钟与宿主相差不足五分钟。验证器支持带唯一结束指令的真实多段任务，并保留唯一用户消息、同一会话/轮次、无 loading/error 和正式终态要求；18 项验证器测试通过。
- 16 MiB 合成 HTML 已在测试工作区按哈希校验，后续要求连续 20 页读取。新的真实任务正在执行，尚未计为通过；证据目录 artifacts/real-api-lifecycle-20260909/。待真机验证。

- 实际 GLM-5.1 任务结果：文件操作阶段 11 次真实工具调用通过；16 MiB HTML 连续 20 页的偏移和结果通过，共 21 次工具调用，正式完成和重启恢复均通过。这不证明自动压缩已触发。原整套流程在重启后的登录弹窗处停止，保留 initial-journey.json，不计为整套通过。
- 关闭测试机登录提示后，独立恢复用例实际完成 5 次工具调用：报告读取、不存在文件、exit 7 及后续 exit 0 均通过。但最终回答缺少结束标记，持久化末条文本仅 244 字符，isFinal=false、无 stopReason，界面已显示 Send。完成断言失败，后续重启未执行，不重放已提交任务。原因仍待定位；证据 recovery-journey-failed.json / recovery-observation.json。

### 2026-09-09 MCP 请求超时与用户取消分离

- 发现 Stdio MCP 的 request 使用 withTimeout，自己的 40 秒期限抛出 TimeoutCancellationException，沿工具调用的 CancellationException 分支传播，可被误当作用户取消。红测试复现异常类型错误。
- 在原请求边界使用 kotlinx.coroutines.withTimeoutOrNull，仅本请求到期转换为 java.util.concurrent.TimeoutException；父协程取消和显式 CancellationException 原样传播。不增加 Agent loop、重试或终态。
- 新增 McpRequestTimeoutTest：自身超时后可继续请求、外层超时仍取消、显式取消不转换。与 XiaowanPromptWorkerTest 合计 10 项通过，完整 APK 构建通过。MCP 真实 stdio 超时路径尚未设备验证，待真机验证。
- APK /tmp/OpenOmniBot-0.6.2.2-mcp-timeout-20260909.apk，SHA-256 0b56bf670b9831fd082740af869cccc0ca8bfb5149a91c549aeddd5203850d71；尚未安装，不用构建成功代替设备验收。

- 独立第二轮真实 API 恢复回归 7 步通过（marker OOB_LIVE_RECOVERY_1788896917584）：工具实际失败和恢复、正式完成、重启后完整回答与 end_turn 均通过。日志确认最终模型文本 1056 字符、finish=stop，继而收到官方 PromptResponse(end_turn)。首轮半段回答的原因未确定，不能用重复通过宣布其已修复。证据 repeat-result.json / repeat-completion.log。

- MCP 修复 APK 已覆盖安装至 emulator-45562，启动及 6 步命令菜单回归通过（init/compact 显示，plan/review 不显示）；升级后的真实恢复任务历史 end_turn 校验通过。证据 artifacts/mcp-timeout-20260909/。以上仅是安装/菜单/历史验收，MCP stdio 请求超时的设备验收仍未完成，待真机验证。

### 2026-09-09 ACP 授权详情丢失与确认文案

- LocalAcpRuntime 将传入的 ToolCallUpdate 重建成仅包含标题、in_progress 和选项名称的正文，导致真实 rawInput/content/locations/kind/status 丢失。改为直接使用现有 ACP SDK 0.30.1 serializer，选项仍保留在官方 options 字段，无新协议或前端改造。
- 高权限工具等待同一请求的授权选项时，旧文案却提示另发“确认/取消”。已改为选择本次授权请求的允许/拒绝选项。
- AgentRuntimeProtocolPayloadTest 71 项通过，包含操作详情保留、缺失字段不伪造；现有 Flutter 权限详情展示测试通过。构建中；emulator-45562 未安装 Shizuku，实际高权限授权交互尚未验证，待真机验证。证据 artifacts/permission-details-20260909/。

- 授权详情修复 APK 构建完成并覆盖安装成功，SHA-256 b23c94b9d31b82facc10e2a1dfe9274317967335b620c696109b87ad09dee3ac。实际授权卡片的允许/拒绝/等待中停止仍未设备验收；不能以安装通过替代该验收。

- 已在隔离 emulator-45562 安装并按官方命令启动 Shizuku 13.6.0，实际系统授权弹窗允许 Omnibot 后，App 显示 Granted(root)。此前未安装条件已解除；仍无物理真机。
- 本地真实 GLM API 的授权拒绝用例 5 步通过：请求一次 android_privileged_action(shell.exec, id, confirmed=false)，卡片完整显示命令及新确认提示，实际点击拒绝，模型正常回复并保存 end_turn。额外 journal 断言确认恰好一次工具调用、明确用户拒绝、无重试或替代执行。首次断言因测试误写 shell_exec 失败，依据实际官方 action shell.exec 修正后通过。
- 用例已纳入现有 journey 框架并补充第 6 步工具结果断言；本次运行的是前 5 步，额外断言单独实际执行。允许、等待授权时停止及重复/重启仍待验收。证据 permission-details-20260909/deny-journey.json、deny-card.json、shizuku-setup.json。

- 真实 API 的等待授权时停止→下一轮允许→重启回归 12 步通过。停止轮授权卡为 interrupted，正式 cancelled；允许轮仅一次 id 操作，原始 rawOutput.result 确认 command=id、exitCode=0、stdout 含 uid=0，正式 end_turn；重启后两轮终态仍成立。附加执行结果断言单独通过。证据 stop-allow-journey.json / stop-allow-cards.json。
- 测试入口新增当前轮 permission-pending 校验，防止同名历史卡片提前满足等待条件；取消终态要求不再保留可点击 pending 请求。22 项 verifier 单测通过，当前停止轮追加严格断言通过。设备本轮运行的是原 12 步版本；新增等待条件尚未完整重跑。
- 同时发现允许后的展示 args 被进度字段替换，modelAssistantMessageJson 为空，不能据此断言原始模型参数保存完整；当前实际执行由工具原始结果确认。该历史保真问题继续检查，未宣布已修复。仍待真机验证。

### 2026-09-09 工具进度不得覆盖输入

- 实际允许 id 命令后，journal args 从 action/arguments 变为 backend/command/availableActions。源头为 XiaowanAcpEventBridge.emitToolProgress 将 extras 写入 rawInput。ACP 官方 tool-calls 定义 rawInput 为输入、rawOutput 为输出，稀疏更新应只包含变化字段：https://agentclientprotocol.com/protocol/v1/tool-calls 。
- 修正为 progress extras 使用 rawOutput，进度更新不再填写 rawInput；沿用现有 reducer 对 structuredOutput 的处理，不修改前端业务逻辑。
- 新增 Kotlin 回归在旧实现下失败（42 项中 1 项失败），修复后 42 项全通过。新增 Flutter 测试验证连续三个进度、完成及 JSON 恢复后 args 不变、终端输出更新；已通过。APK 构建并覆盖安装成功，新的严格真实 API/重启回归正在运行，不能提前计为设备通过。证据 artifacts/progress-input-20260909/。
- 另对历史仓库“空字符串旧字段覆盖后来的 canonical 消息”增加复现测试，尚待运行结果；不将其等同于此次参数覆盖的已证实根因。待真机验证。

- Git 当前可见历史定位：进度写 rawInput 出现在 8203f5a42（2026-08-28，统一 ACP runtime 提交）；preserveFullToolPayload 出现在 8c16e38d9（2026-09-08，本地修复快照）。这说明对应代码进入当前路径的提交，不推断更早路径或已发布 APK 的全部行为。
- 仓库空字段复现：AgentConversationHistoryRepositoryTest 7 项中新增用例失败；修正为只有旧非空值才覆盖 incoming，从而保留完整旧消息，同时允许空占位补全。绿测运行中，尚未安装该额外修复。

- 严格真实 API 设备回归在第 12 步失败：本轮等待授权和停止、下一轮当前权限确认、允许点击及正式 end_turn 均通过，args 保留原始 action/arguments/confirmed=false；但 Shizuku 工具实际返回 service_bind_failed（Failed to bind the Shizuku user service），success=false，真实命令成功断言因此失败，未执行后续重启。不将模型正常完成或参数保留冒充工具执行成功，继续排查升级后后端绑定。
- 历史空占位修复 7 项绿测通过，已生成包含两处修复的新 APK（SHA 982d6711b27440203975c28748b5ee0531d5d088497a1ac1a5c952c02809dfe2），尚未安装，避免改动新失败的现场。

### Shizuku 冷启动被提前判定失败（2026-09-09）
- 真实 API 授权后工具失败：服务启动 20:28:13.883，App 收到连接 20:28:18.017，耗时 4134ms；本地只等 3000ms，官方服务端日志允许 30000ms。原始证据 artifacts/shizuku-binding-20260909/before-bind.log。
- 保留现有 ShizukuCapabilityManager 所有权，连接等待对齐上游 30 秒，协程取消仍可立即结束等待，不重放工具操作。
- 执行入口：`:baselib:testDebugUnitTest --tests '*ShizukuServiceBindingTest'`（已加入 scripts/test-agent-runtime.sh）。覆盖 4.2 秒冷启动、取消后连接可用、有界超时及晚到回调。旧 3 秒实现下新增冷启动用例确实失败；绿测及升级后真实 API 流程待运行。全部待真机验证。
- 测试前置修复：AccountApiClientTest 的 StubCall 对齐当前 OkHttp 5.5 的新增元数据接口，执行仍由测试响应替身控制；该类一并回归。
- 绿测 14 项全部通过，APK 覆盖安装成功（SHA 2b11eef4d2ad2e7924f72e860e3f8dd82c74968e409afa34b1d0a734dcf35c3d）。第一轮真实 API 的 16 步全部通过，含当前授权等待停止、下一轮允许真实 id 命令、原始参数保留、正式 end_turn 和重启恢复；冷启动 1597ms，超 3 秒边界由 4.2 秒单测覆盖。
- 第二轮不能计为通过：命令实际成功且只有一次调用，官方 ACP 于 20:49:02.502 返回 end_turn，但历史授权卡缺 streamMeta，助手回复仅保留 64 字符、isFinal=false；回复门禁失败后未重启。这再次复现此前的部分回复/终态丢失症状。保留 repeat-journey-failed.json、repeat-official-completion.log、repeat-history-observation.json；继续追查历史快照保存和事件投影，不能将其误归类为 Shizuku 或模型未完成。所有修复待真机验证。

### 授权卡元数据与历史加载所有权（2026-09-09）
- 针对重复回归 1788900300617：授权卡确认通过 upsertUiCard 只提交卡片字段，旧实现把未提交的 streamMeta 一并抹掉。新增 permission response patch 测试先失败（40 项中 1 项），改为沿现有仓库卡片合并保留缺省元数据后 40 项通过；显式新元数据仍生效。
- 发现并复现另一个入口错误：ChatPage.messages 指向 runtime.messages，ConversationManager.loadConversation 在 onConversationLoaded 之前 clear/addAll，绕过共享协调器。新增延迟 forced-refresh 用例先失败，删除这两行后历史/协调器/保存服务 135 项通过；恢复与分页仍由原入口完成，没有新增 reducer 或生命周期。
- 证据 artifacts/history-owner-20260909/。此时仅源码与可执行回归通过，APK/设备复测尚未完成，最终文本丢失不能提前宣称解决，全部待真机验证。
- 新 APK 构建/覆盖安装成功（SHA edc60c5e04a2c57a6a3909c89e72fb3176f957915c754e5ccd0f0dbcc1010ead）。首次 UI 尚未就绪，发送前空 root 失败已保留；界面就绪后的独立轮次 1788901464879 全部 16 步通过，重启后回复 238 字符、isFinal=true、end_turn，授权卡元数据保留。
- 第二轮 1788901753079 仍未通过：实际 id 成功且单次调用，授权卡元数据正确保留；官方 21:13:12.558 返回 end_turn，但助手落库仅 89 字符、isFinal=false、无 stopReason，未进入重启步骤。说明两处入口修正并未涵盖最终文本丢失全部原因。保留 repeat-history.json 与失败 journey，继续追踪具体写入/投影顺序，不能宣称全生命周期已验收。静态分析有既有 unnecessary_null_comparison 警告（conversation_manager.dart:792）。

### 完成结果与异步保存时间窗口（2026-09-09）
- 诊断版证据：21:23:44.507 运行时为 238 字符、isFinal=true、end_turn；44.530 较早启动的保存仍发送 199 字符非最终快照，44.541 写入数据库；后续 46.139 补全。两轮带诊断日志的真实 API 流程均通过，不能当成旧回退问题消失，也尚未捕获具体失败的后续重装顺序。证据 artifacts/completion-write-20260909/probe-order.log。
- 可执行用例：chat_conversation_runtime_coordinator_test 中 persistence uses the completed projection after awaiting metadata I/O。人为延迟 metadata 写入，期间流式补全并接收正式 PromptResponse，旧实现随后仍发送 partial，断言失败；新实现同一保存队列在 I/O 返回后、runtime/代际一致时读取最新消息，100 项协调器测试通过。没有新增 reducer、状态机或保存队列。
- 临时诊断代码已从源码移除，正在构建/完整回归；修正版设备验证尚未完成，全部待真机验证。
- 仅取快照时机修正的 APK（SHA 971cccdae90133f66959d8c829f729cc21fcf76a0348e4a9ebfd8d3797f3684f）真实 API 第一次仍在最终回复门禁失败，命令成功，第二轮没有启动。该修正不能单独视为问题闭环。
- 补充直接复现：官方 PromptResponse 完成后，replaceConversationSnapshot 对同 ID 的旧部分消息原样替换，导致已完成消息降回未完成。新增 an old history snapshot cannot downgrade an officially completed item 测试先失败；沿同一协调器按已保存 stopReason 保留提交终态，无终态/缺元数据旧副本不能降级，完整终态历史仍可补充 usage。137 项测试通过，正在构建/设备复测；没有改变普通历史排序或增加生命周期推断。

- 最终无诊断日志 APK（SHA 9600433c7e2c42b02db231c53e857a3b044cdaca687fdbeb114140b9542cfa8f）覆盖安装成功；emulator-45562 使用 LLMTHU GLM-5.1 (Debug)，连续两轮 1788903827637 / 1788904098770 各 16 步全通过：当前授权等待停止、新轮授权、真实 id 单次成功、原参数保留、官方完成、重启后取消/完成状态保留。结果归档 terminal-history-first-journey.json 与 terminal-history-repeat-journey.json。137 项本地测试通过；静态检查无 error，但有 7 warning / 1 info，不记为全量 lint 通过。仅证明此回归范围，未覆盖所有长任务和 Harness；无物理设备，待真机验证。

### MCP 静默进程关闭顺序（2026-09-09）
- 发现 stdio close 在 destroy 子进程前调用 BufferedReader.close；readLine 正等待 stdout 时，两者竞争同一锁，关闭可能一直等待。真实 `sh -c exec sleep 30` 子进程回归先复现 2 秒关闭门禁超时，终止进程提前后通过；同一回归还检查重复 close。
- 现有 XiaowanStdioMcpConnection.close 调用经过测试的清理函数；清理在 IO 上执行，进入资源释放后不被调用者取消打断，不增加业务生命周期。
- MCP 清理、请求超时分类、Prompt worker 取消共 11 项测试通过，入口已加入 scripts/test-agent-runtime.sh。证据 artifacts/mcp-cleanup-20260909/。尚未验证 Android App 的真实 stdio 服务，待模拟器及真机验证；不能将主机 JVM 子进程测试算作设备验收。

### HTTP/SSE MCP 停止与权限拒绝（2026-09-09）
- HTTP 阻塞 execute/正文读取没有连接协程取消；真实 TCP MockWebServer 延迟响应头的旧实现超过 1.5 秒取消门禁。修正同一请求范围中取消 OkHttp Call，保持正文和 SSE 消费期间的取消绑定。
- 连续测试又暴露 tools/call 把 401/403 当成会话失效并重新发现/调用；现在只允许已有 session ID 的 404 走既有恢复，其他拒绝直接报告。依据 MCP 2025-11-25 transports session-management；没有更改鉴权配置或新增重试层。
- 22 项通过：HTTP 响应头/正文卡住取消后继续、SSE endpoint 等待取消重复两次、401/403/429 后新请求成功且无隐藏重放，以及既有协商/404 恢复/取消回归。长期入口 scripts/test-agent-runtime.sh 已包含 RemoteMcpClientInteropTest。证据 artifacts/http-mcp-cancel-20260909/。
- 这里是主机 TCP 集成测试，非 Android App 验收；待模拟器及真机验证。本地请求停止不等于远端工具已停止，协议取消通知与真实服务行为仍待核验。

### 按协议版本发送取消（2026-09-09）
- MCP 2025-11-25 使用 notifications/cancelled；2026-07-28 HTTP 以关闭请求流作为取消信号，不能盲目给新版发送旧通知。参考官方 cancellation 规范及 TypeScript SDK 2026-07-28 migration。
- 修正旧版 HTTP：在已执行的请求因调用者取消退出时，沿原 endpoint/session 发送一次不带 id 的通知，引用原 requestId，不重新初始化；最多等待 1 秒。initialize 不发取消通知。另修正现有 notifications/initialized 错带 id，新断言先复现失败。
- 24 项通过，含两次取消/继续、取消通知端点不响应、初始化取消边界及既有 HTTP/SSE/权限拒绝回归。执行入口沿用 scripts/test-agent-runtime.sh，证据 artifacts/legacy-mcp-cancel-20260909/。
- 本次只补齐旧版 HTTP；旧版 SSE 的远端取消通知仍待实现，不能称所有远端任务已可停止。主机 TCP 测试仅证明通知到达，不替代实际远端工具终止、模拟器和真机验收；待真机验证。

### 旧版 SSE 原请求取消与连续操作（2026-09-09）
- 新 TCP SSE fixture 完成 endpoint/initialize/tools 握手后，旧实现本地取消结束但远端未收到通知，red.xml 保存失败。使用项目已有 MockWebServer 依赖；最初 JDK HttpServer 测试编译失败不计为 bug 复现。
- 沿现有连接记录原消息端点和工具 requestId；POST 与 SSE 等待由外层统一发送一次取消通知，不重新 GET/初始化。第一次修正重复回归仍漏第二次取消，随后将网络取消捕获扩大到 coroutineScope 退出边界，覆盖响应消费结束到清理完成之间的取消。
- 25 项测试与 APK 构建通过。SSE fixture 连续覆盖读取等待、POST 响应延迟和接收后立即取消，每次随后调用成功，6 次调用对应 6 条连接，无取消重连。scripts/test-agent-runtime.sh 新增 RemoteMcpSseCancellationTest；证据 artifacts/sse-mcp-cancel-20260909/。
- APK 已留存，尚未安装本次版本或进行 App MCP 界面验收；主机 TCP 测试不代表真实生产服务一定终止工作。待真机验证。

### MCP App 配置、实际取消、恢复和重启验收（2026-09-09）
- 在 emulator-45562 覆盖安装 SHA a585a11d89edb3e3fb5b4841687fe57469e7e96aa9029c9980372b45ec693ff4，保持既有本地模型 API。通过 App 设置 → MCP Tools 添加隔离测试服务，实际刷新 Connected / Tools 3。
- scripts/fixtures/mcp-lifecycle-server.mjs 提供无文件/命令权限的 legacy HTTP fixture；操作入口及清理见同目录 mcp-lifecycle-server.md。设备入口：scripts/verify-agent-user-journey.mjs + xiaowan-mcp-stop-recovery.en.json。新增只读 fixture 观察断言，不绕过 ACP 或修改历史。5 项测试辅助器/时钟前置回归通过。
- 两轮 1788907215245、1788907375120，共 26 步全通过：真实模型调用等待工具，点击 Stop 后 canonical cancelled，服务收到同 requestId 唯一取消且解除等待，下一次真实 echo 成功并正式完成，两类历史重启后均保留。证据 artifacts/mcp-device-20260909/。
- 完成后通过 UI 只删除测试服务，验证服务列表恢复原先空状态，移除测试 reverse，服务进程正常退出。未清除用户数据。当前只验收模拟器 legacy HTTP；SSE/权限拒绝的 App 实际流程与物理设备仍未覆盖，待真机验证。

### SSE 与 401 拒绝的 App 连续验收（2026-09-09）
- 保留既有本地模型 API，UI 添加 /sse 测试端点后 Connected / Tools 3。初轮 1788907965688 停止/真实远端取消/回显/重启均通过，但第 15 步输入前 UiTestAutomationBridge 返回空 root，整轮保持失败，未发送该消息。
- sender 的既有就绪循环现在只在动作前容许空观察并等待，仍保护已有草稿、绝不重发。新增 Node 测试旧代码先失败、修正后通过；7 项观察/就绪/时钟测试、23 项 Python 历史断言通过。
- 独立拒绝恢复 1788908221211 的 12 步通过；完整复测 1788908409224 的 25 步通过。两次 SSE 工具取消都匹配原请求和原 stream endpoint；两次 401 都仅实际调用一次，失败工具与 HTTP 401 历史保留，助手正常收尾，后续 echo 及重启恢复正确。证据 artifacts/sse-device-20260909/，新入口 xiaowan-mcp-stop-denial-recovery.en.json。
- 测试服务仅本机回环；结束后仅删除测试配置、移除 reverse 并终止服务，原空服务列表恢复。未修改模型凭据或清除对话。物理设备仍缺失，待真机验证。
- 另观察到导航进入 Local Agent Sessions 时显示 runtime unavailable；源码显示列表查询全局 Agent 状态，聊天可按对话绑定运行，暂作排查线索，尚未确认根因/修复，不计入本项验收。

### 2026-09-09：冷启动会话列表误报运行时不可用

- 复现：小万历史聊天重启后，点击顶部已选中的 Agent 分段；列表显示 `Agent runtime is unavailable`，未尝试连接。
- 原因：`AgentRuntimeManager.status()` 的可用性探测得到 `ready=true`，但合并 `LocalAcpRuntime.statusPayload()` 时被 `ready=isConnected=false` 覆盖；分发环境的 `connected` 判定也可能被覆盖。保留 host 对这两个字段的所有权，其他 ACP 元数据正常合并。未新增生命周期或重试。
- 可执行回归：`AgentRuntimeProtocolPayloadTest.disconnectedTransportDoesNotHideAnAvailableAgent`、`transportCannotOverrideHostDistributionOrAvailabilityChecks`；旧逻辑 2/2 失败，修正后该测试类 73/73 通过。执行 `./gradlew :app:testDevelopStandardDebugUnitTest --tests '*AgentRuntimeProtocolPayloadTest'`；Flutter 会话列表测试 2/2 通过。
- 模拟器操作：保留数据重装、冷进入列表、打开历史、重新进入、强停重启后再进入，列表均恢复。设备/版本/原始 XML/红绿日志见 `artifacts/session-readiness-20260909/verification.json`。无物理设备，**待真机验证**。
- 此结果只验收“可用性被未连接状态覆盖”。列表的更新时间和 Loaded 统计尚需另行核查，不据此宣称所有会话管理操作通过。

### 2026-09-09：会话列表读取意外刷新所有会话时间

- 实际故障：不发送消息，仅重新进入 Local Agent Sessions，4 条历史会话 `updatedAt` 全部变化。读取经 `listThreads -> ensureBinding -> buildUpdatedConversation` 无条件写入当前时间并触发列表通知。
- 修正：已有绑定的元数据同步无变化时保持 Conversation 原值；cwd 无变化时不写 binding；实际标题/归档/模式变化仍更新时间和发布通知。没有改变会话身份或 ACP 生命周期。
- 可执行单元回归 `AgentSessionBindingMetadataTest` 覆盖重复同步、真实变化后重复同步、缺省元数据与历史保留；旧代码 3 项失败，修正后与协议测试共 76 项通过。入口：`./gradlew :app:testDevelopStandardDebugUnitTest --tests '*AgentSessionBindingMetadataTest' --tests '*AgentRuntimeProtocolPayloadTest'`。
- 可执行设备断言：`python3 scripts/assert-agent-session-metadata.py SERIAL CHECKPOINT.json --capture`，通过 UI 进入列表/返回/重复进入/强停重启，再运行同命令去掉 `--capture`。要求已有绑定历史、全程不发送消息或编辑元数据。只读 SQLite backup，对比 Conversation 与 Binding 时间；物理设备需 `OOB_ALLOW_PHYSICAL_DEVICE=1`。
- Android 13 ARM64 模拟器 emulator-45562 已保留数据重装，以上 4 阶段实际操作后全部断言通过。证据 `artifacts/session-metadata-20260909/verification.json`；**待真机验证**。此验证不等同于已解决列表 Loaded/Running 统计缺失。

### 2026-09-09：本地会话列表遗漏活动/载入状态与标准 sessionId

- `session/list` 的本地投影此前没有 loaded/active，页面默认全部为 false。现在直接读取现有 `sessions` 与 `AcpTurnOwnershipRegistry`，无第二套状态机；列表解析优先接受标准 sessionId，兼容已有 threadId。
- 可执行测试：`LocalAcpSessionListStateTest` 经真实 runtime.handleMethod(session/list) 验证载入、任务保留、取消后仍载入；旧实现失败。Flutter `agent_sessions_page_test.dart` 新增纯 sessionId 三种状态测试，旧解析失败。修正后相关 Kotlin 77 项、Flutter 3 项通过，构建成功。
- 实际本地模型操作：发送 `xiaowan-live-permission.json`，权限等待 -> 顶部区域上划切回模式入口 -> 会话列表 1 Running -> 返回原对话 Stop -> 再进入 0 Running/1 Loaded -> 强停重启，日志确认 session/load 恢复原会话，仍为 0 Running/1 Loaded；取消历史断言通过。
- 设备可执行断言：`python3 scripts/assert-agent-session-list.py SERIAL --running 1 --loaded 0`；停止后 `--running 0 --loaded 1`；配合 `assert-agent-turn-outcome.py SERIAL MARKER permission-pending/cancelled`。该页面的 Loaded 统计排除 Running，避免重复计数。重启后若聊天自动 load，不能假定 Loaded 必须为 0。
- 证据：`artifacts/session-list-state-20260909/verification.json`。立即停止后的第一次持久化观察尚未收敛，后续观察通过；未重发任务。**待真机验证**。本次只验证进入页面时的快照；页面停留期间的自动刷新、关闭会话失败路径仍需继续检查。

### 2026-09-09：关闭/归档失败后提前丢失会话引用

- 对照官方 Kotlin SDK 0.30.1 `ClientSessionImpl.close`：await SessionClose 响应后才 removeSessionHolder。本地 close/archive 原先先 sessions.remove 再 close，失败即丢失仍存在的会话。
- 修正现有运行时关闭顺序：成功后 compare-and-remove 同一个 ClientSession，再移除相关会话元数据；失败透传，保留引用。无自动重试或新生命周期。
- `LocalAcpSessionCloseTest` 两项覆盖关闭/归档被拒绝、引用保留、下一次显式成功关闭后移除；旧代码两项断言失败，修正后相关 Kotlin 共 79 项通过。前两次测试准备阶段出现 JUnit 非 void 签名错误，不计为 bug 的红测；有效红测日志为 `oob-session-close-red3.log`。
- 模拟器通过 UI Archive 验证：已载入变为 0，Conversation 4 的 37 条历史全部保留。但 DB isArchived=1 时列表仍显示 Archived 0，不能从该页取消归档，**整条流程未通过**。待补齐本地归档元数据投影后通过 UI 恢复测试会话。
- 关闭拒绝分支尚未做设备故障注入；**待真机验证**。证据 `artifacts/session-close-20260909/verification.json`。删除请求之后又 close，以及活动任务关闭失败时的终止归属另行核查，不据本次测试宣称通过。
- 上游来源：https://repo.maven.apache.org/maven2/com/agentclientprotocol/acp-jvm/0.30.1/acp-jvm-0.30.1-sources.jar ，SHA-256 `9a24ce36f8a0d2f4a42642e09471d747c2ee60dbed361e69e5f2a26da04acb92`。

### 2026-09-09：归档状态来自 Conversation，修复列表与取消归档入口

- 真实复现：Conversation 4 isArchived=1，但 session/list 未投影该字段，页面显示 Archived 0 并只提供 Archive。只读 `AgentSessionBindingRepository.getConversationByThreadId` 从已有绑定定位 Conversation，列表投影其 isArchived，不写库、不推断协议生命周期。
- `LocalAcpSessionListStateTest` 增加持久化归档 true/false 翻转与 list 不调用 setArchived 的断言；旧代码失败，修正后相关 79 项 Kotlin 测试通过并完成构建。
- 模拟器实际操作：重装显示 Archived 1 -> Unarchive -> Archive -> Unarchive -> 强停重启恢复。每一步运行 `assert-agent-session-list.py` 对照 loaded/running/archived；最终 DB 归档=false、37 条历史保留，原取消结果经 `assert-agent-turn-outcome.py` 验证。上一轮归档的测试会话已通过 UI 恢复，未用数据库写入修正状态。
- 入口：`./gradlew :app:testDevelopStandardDebugUnitTest --tests '*LocalAcpSessionListStateTest'`；设备 `python3 scripts/assert-agent-session-list.py SERIAL --running 0 --loaded 0 --archived 1`，取消归档后 archived=0，loaded 按是否实际加载检查。证据 `artifacts/session-archive-state-20260909/verification.json`。
- **待真机验证**。本次覆盖归档/取消归档 UI 正常路径与持久化；不代替关闭失败设备注入、删除协议顺序、活动会话关闭失败或页面停留自动刷新验收。

### 2026-09-09：session/delete 使用正式 SDK，避免删除成功后再次 close

- 本地依赖 `com.agentclientprotocol:acp-jvm:0.30.1` 已提供 `Client.deleteSession`。旧 host 跳过小万协议请求，外部 Agent 则 raw delete 后再 close，可能将已成功删除误报为会话不存在。改为统一正式 SDK 调用、声明能力检查、确认成功后清理 host 绑定；拒绝/传输失败保留原状态，不添加重试。
- 官方语义与关闭不同：delete 主要从 session/list 移除，存储及 load 行为由 Agent 定义；参考 https://agentclientprotocol.com/rfds/session-delete 。小万已有的删除回调保留 Conversation 历史；host 提前保存绑定的 conversationId，以兼容回调先解除绑定。
- 可执行 `LocalAcpSessionDeleteTest`：旧逻辑断言失败；覆盖活动任务拒绝、Agent 拒绝后引用保留、成功只 delete 不 close、能力未声明不发请求，以及回调解除绑定后的 conversationId。执行 `./gradlew :app:testDevelopStandardDebugUnitTest --tests '*LocalAcpSessionDeleteTest'`。
- 当前 Flutter 只有 deleteSession 服务定义、没有页面调用；不把普通删除聊天当成该 ACP 操作验收。本轮仅保留数据重装并验证原 37 条历史和取消结果，**删除协议设备测试未运行，待真机验证**。没有删除任何已有设备会话。
- 证据目录 `artifacts/session-delete-20260909/`，测试最终结果以其中 XML/verification.json 为准。

### 2026-09-09：取消请求失败不能伪装成功或释放原任务

- `interruptTurn` 原先吞掉 ClientSession.cancel 的传输异常和 2 秒期限，仍返回 ok；活动会话 close 又把前置取消失败当作本地 cancelled。改为保留既有期限、正常传播请求异常；close 不合成终态。原 prompt/所有权继续存在，直到正式响应或其自身错误结束。
- `LocalAcpCancellationFailureTest` 经真实 runtime.handleMethod 验证 cancel/close 两条入口：IOException 传输失败可见、原会话/turnId/执行 Job 保留；再次显式 cancel 可发出，但通知成功也不等同 prompt 完成。有效旧逻辑红测 2 项断言失败，修正后相关 79 项测试通过。
- 测试准备曾遇 Mockito checked-exception 配置限制、协程堆栈恢复复制异常对象；修正为 thenAnswer 抛错、核对异常类型/消息。前两次测试失败不计为产品回归证据。有效日志见 `artifacts/cancel-request-failure-20260909/`。
- 模拟器重复验收入口：`node scripts/verify-agent-user-journey.mjs SERIAL scripts/fixtures/agent-user-journeys/xiaowan-live-permission-stop-allow.en.json OUTPUT`。最终状态以证据目录 verification.json 为准；关闭/取消发送失败的设备注入尚未完成，**待真机验证**。
- 后续：前端取消入口仅 debugPrint 请求失败，仍需补充面向用户的提示；不能让提示修改任务终态。页面停留自动刷新也尚未验收。

- 本轮最终结果补充：16 步真实模型权限等待/停止/批准命令/重启回归全部通过，runId `1788911973583`，见上述证据目录 `device/`；取消传输失败设备注入仍未运行，不能扩大为该失败场景已验收。

### 2026-09-09：ACP 停止请求不能提前中断卡片，失败需提示

- 源码确认 `_onCancelTask`、`_cancelDispatchTask` 和按 taskId 停止路径在发送取消请求前先 interruptActiveToolCard，导致请求失败时 UI 仍显示中断。ACP 分支移除提前投影；非 ACP 分支维持原逻辑。两处普通聊天/Agent 取消 catch 使用既有错误格式化与 toast 提示，不合成任何终态或重试。
- `agent_runtime_service_test.dart` 新增失败到达调用方、无自动重试、再次显式取消保持原身份的 MethodChannel 回归。与 `chat_conversation_runtime_coordinator_test.dart` 共 161 项通过；构建通过。
- 本轮模拟器采用既有 16 步 `xiaowan-live-permission-stop-allow.en.json`，最终运行结果见 `artifacts/stop-ui-20260909/verification.json`。正常停止流程不能证明取消发送失败提示的设备行为；该故障注入未完成，**待真机验证**。
- 仍需检查 command_overlay/chat_bot_sheet.dart 的关闭失败标志及再次操作；没有将本次提示改动扩大为浮层流程已验收。

### 2026-09-09：正式 PromptResponse 必须关闭未回答请求

- 模拟器运行 `1788912529987` 在第 5 步实际失败：正式 `cancelled` 已持久化，但同一轮批准卡片仍是 `pending`，再次读取仍失败。证据保留在 `artifacts/stop-ui-20260909/failed-device-before-terminal-fix/`，不是用延长等待掩盖失败。
- 在既有 `AgentEventReducer._completeTurn` 中处理该轮未回答的 `agent_request`；已提交、批准、拒绝等结果保持原样，其他轮次不变。取消、失败、正常结束均不允许残留可交互请求。已取消/中断/失败的请求状态不会被重放覆盖为 pending。未改工具完成语义、未添加生命周期。
- 三项终态回归先红后绿；加入取消/中断/失败请求重放回归，相关 reducer、service、coordinator 共 361 项通过。构建成功，设备流程结果见同目录 verification.json。
- 旧版本已保存的异常历史是否需要规范化，以及取消传输失败 toast 的设备故障注入，尚未验收；**待真机验证**。

### 2026-09-09：快捷浮层区分停止任务与关闭会话

- `ChatBotSheet` 停止仅调用既有 `session/cancel`，不提前中断工具卡片，也不顺带 `session/close`。失败解除本次取消标记、提示错误；原 prompt 继续拥有终态；再次显式停止可重试，成功应答后的重复停止不重复发送。过期卡片在设置取消标记前验证任务身份。
- 关闭浮层直接交给原生 `session/close` 所有者处理取消和关闭，移除前端重复取消。关闭失败解除关闭标记，使下一次显式关闭可发出；不合成任务完成。
- 旧实现反跑新增断言失败。两个 Flutter 测试文件共 6 项通过，覆盖取消失败、显式重试、重复停止、结果到达、后续任务、启动各阶段停止，以及关闭失败。新增关闭测试独立文件以隔离静态 EventChannel 测试监听生命周期；该隔离不是产品修改。
- 构建和 emulator-45562 覆盖安装成功。实际入口检查显示宠物点击只是挥手，不打开旧浮层；源码剩余直接调用为授权恢复场景。**未完成浮层真实用户流程验收，待真机验证**。证据 `artifacts/overlay-stop-20260909/verification.json`。未把主聊天页通过等同为浮层通过。

### 2026-09-09：活动会话内的旧历史请求不可重新交互

- 现有历史恢复在 `isAiResponding` 或 `preserveLiveStreamingState` 为真时直接跳过所有请求规范化，导致已保存正式 stopReason 的旧 pending 请求也被保留。空闲恢复已有过期处理，不能将它误报为完全缺少历史防护。
- 在既有快照规范化中优先读取每条消息自己的 PromptResponse stopReason，关闭该旧请求；当前未结束请求和已批准结果不变。保留实时消息的合并路径同时覆盖已缓存的旧请求，保留其他 content 字段。未修改数据库或伪造 ACP 响应。
- 两项新增回归先失败后通过；协调器与 reducer 共 303 项通过，覆盖新恢复与保留实时缓存分支。设备上旧失败标记 `1788912529987` 在安装本轮修复前已通过 cancelled 历史断言，说明此前空闲恢复已处理该样本，不能充作本轮活动恢复问题的设备复现。
- 本轮构建/设备结果见 `artifacts/history-request-20260909/verification.json`。**待真机验证**。

### 2026-09-09：递归列目录不能让根目录消耗 limit

- 引入定位：`051eb5442`（2026-09-02）将原 `.drop(1).take(limit)` 改为先 take 后 drop，改动用于可选限额/深度；提交差异保存在同目录 `introduction.patch`。这是操作顺序回归，不能归因于模型找不到文件。
- 原 `file_list` 递归分支先 take(limit) 再 drop(1)，实际 limit=1 返回 0 项，limit=2 返回 1 项。抽取原逻辑后，限额与取消回归两项先失败。修复先去掉遍历根节点，再复用既有可取消遍历；不改变省略 limit/maxDepth 的完整结果契约。
- `FileListTraversalTest` 覆盖限额、根节点排除、深度、非递归、40 次重复和取消后新操作；与搜索遍历、无限额契约共 16 项通过，APK 构建成功。
- 新增真实模型用例 `scripts/fixtures/agent-user-journeys/xiaowan-file-list-limits.en.json`，两个独立文件，三次不同 limit，按 canonical tool journal 核对实际 count/items，并重启复查。
- 旧包模拟器运行 `1788914584465` 实际返回 0/1/2；持久化工具断言复现失败。该轮在输出部分最终回复后超过 180 秒仍未完成，因此主用例在回复等待处失败；随后正常点击 Stop，官方 cancelled 断言通过。未将未完成回复误算为成功，也未重装打断活动任务。模型流等待原因尚未定位，保持为后续调查项。
- 修复包运行 `1788915006738`：7 步通过，三次实际工具结果 1/2/2，正常结束并重启复查通过。设备结果见 `artifacts/file-list-limits-20260909/verification.json`。**待真机验证**。

### 2026-09-09：流内 Provider 错误必须终止部分回复

- `AgentLlmStreamAccumulator` 原逻辑只在正文和工具调用都为空时抛出已收到的 Provider 错误；已有部分输出时可能成功返回，错误后保持连接则继续等待。新增两个回归在旧实现失败。现将明确 error 对象作为本次流的终止条件，buildTurn 优先保留错误，不执行失败回复中的工具输入；沿用既有 client completion 和 ACP 生命周期。
- 累积器、HTTP client、Responses/Anthropic 回归共 88 项通过；本机故障发生器 3 项、终态验证器 25 项通过，构建通过。
- 模拟器受控故障接口：partial text/error/no EOF、partial file_write/error/no EOF、下一次正常请求、重启恢复。首次断言把 pending 输入展示记录误当执行结果，保留失败证据；核对记录及目标文件不存在后，修正验证器允许 pending/false 输入但拒绝执行结果，增加实际文件不存在的设备检查。最终 `1788915874025` 17 步通过，部分正文保留、文件未创建、每轮仅一次 HTTP 请求，错误连接由客户端释放。
- 已恢复 GLM-5.1 可见选择；Provider 安全元数据及 scene bindings 与执行前完全一致；测试服务 18879 已停止。未改动真实密钥。
- 这不能证明前次真实模型中途停顿也是该原因。流内错误的用户提示分类仍偏通用，详细错误保留，待后续检查。证据 `artifacts/inband-provider-error-20260909/verification.json`，**待真机验证**。


### 2026-09-09：Provider 错误分类必须跨过正式 ACP 错误边界

- 小万流内 error 原先只抛普通异常，不能可靠区分额度不足、请求限流和原因不明的 429。保留 Provider status/code，复用现有错误格式化；未知 429 不再默认称为频率限制。异常仍是原请求的失败，不增加重试或第二套生命周期。
- 设备运行 `1788916583700` 复现“任务已失败但用户提示仍通用”。接收端分类尝试 `1788917086654` 仍失败：官方 SDK 已将异常序列化成 JsonRpcException，原类型不在接收端。撤回无效接收端改动，在 XiaowanAcpConnection 发送正式错误前保留 executor 已生成的用户提示，原异常作为 cause；取消分支仍优先处理。
- 可执行入口：`node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/xiaowan-provider-limit-categories.en.json OUT`。三类注入错误分别验证唯一终态及精确提示，随后发送正常请求，并重启复查四轮历史。使用本地故障服务，不能冒充真实 Provider 额度事故。
- 分类/取消 worker 单元测试 23 项、Flutter 错误格式化 61 项、故障服务 3 项通过；APK 构建通过。设备最终结果见 `artifacts/provider-limit-categories-20260909/verification.json`；两次失败证据均保留。**待真机验证**。


### 2026-09-09：单行大文件搜索的内存与取消边界

- `file_search` 原先 `bufferedReader().useLines` 先分配整行，再检查取消；匹配靠前时仍读取整行。提取原逻辑后，受控 Reader 分别证明“已有足够摘要仍继续读”和“取消后继续读”，新增回归旧逻辑 2 项失败。
- 仅在原 FileToolHandler 搜索路径改为分块扫描，保留跨块匹配所需尾部和前 40/后 120 字符摘要。每块读取前后检查同一个 coroutine context；匹配足够即返回。不限制文件大小，不增加工具、生命周期或隐藏结果上限。caseSensitive 使用标准字符串大小写匹配。
- `FileContentSearchTest` 覆盖超长无换行、取消后后续操作、40 次跨块/大小写/Unicode 查询、CR/LF/CRLF、空文件、跨行不匹配、超过块长的 query 和短读取；与目录遍历测试共 13 项通过，APK 构建通过。
- 可执行设备入口：先 `python3 scripts/prepare-content-search-fixture.py emulator-45562`，再 `node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/xiaowan-content-search.en.json OUT`。准备仅写独立合成 HTML，不修改对话；重复准备校验哈希且不覆盖不同内容。实际工具结果断言要求头部、尾部、无匹配计数 1/1/0 和精确摘要，随后重启复查。
- 测试数据初次 stdin/tee 传输缓慢，终止该测试进程并移除其未完成合成文件，改用 adb push + run-as cp；这是测试准备修正，不计产品故障。设备最终重复运行结果见 `artifacts/content-search-20260909/verification.json`。
- **待真机验证**。确定性的读取期间取消覆盖来自单元测试；模拟器验证真实模型搜索与重启，不冒充在设备上精确命中了块间取消时刻。未测量发布版内存峰值。


### 2026-09-09：正式 ACP 错误数据与失败前更新交付

- 进一步发现之前只保留原生中文错误提示仍会丢失语义：Flutter 再次分类时无法还原身份验证/模型错误。原生分类与共享服务新增回归各自先失败。复用安装的 ACP Kotlin SDK 0.30.1 JsonRpcException.data 携带现有 failureKind，LocalAcpRuntime 在原请求结果中保留该诊断字段，AgentRuntimeService 统一格式化；无新增页面状态、重试或终态事件。Provider 401、403、503、model_not_found 同时支持 HTTP 与流内错误。
- 设备首轮 `1788918678490` 分类正确，但严格断言发现失败前 partial 丢失；未放宽断言。根因：XiaowanPromptWorker 子协程抛错取消 channelFlow 生产者，缓冲的 session/update 被丢弃。新增 40 次慢消费者回归在旧逻辑失败；worker 将异常交回生产者，现有 channelFlow 使用 close(error) 先交付已有元素再报错，取消仍通过原官方 PromptResponse(CANCELLED)。
- 上游依据及失败证据见 `artifacts/provider-error-data-20260909/`。failureKind 是应用诊断元数据，不宣称为官方 ACP 状态。JSON-RPC error.data 与 Kotlin Channel.close(cause) 语义均已核对；未引入新 SDK 或第二条流。
- 可执行设备入口 `scripts/fixtures/agent-user-journeys/xiaowan-provider-error-data.en.json`；同时复跑既有 provider-limit-categories 用例，断言精确分类、partial 保留、唯一请求终态、下一请求完成与重启恢复。最终结果见同目录 verification.json。
- **待真机验证**。故障服务为受控本地注入，不能代表真实服务商发生鉴权或额度事故；先前真实模型中途停流仍未证明与此同因。


### 2026-09-09：持久终端进程退出必须结束命令等待

- 模拟器旧包运行 `1788920146314`：真实模型创建终端并执行 `exit 7`，终端已显示进程退出码 7，但小万仍等待工具完成；60 秒回复观察超时后，用户 Stop 路径正式取消该轮。原因是原 `sendSessionCommandAndAwait` 只等 sourced shell wrapper 的完成标记，进程退出、会话消失或替换均没有结束条件。
- 在原终端轮询内检查同一个 TerminalSession 的存在性与运行状态；正式命令完成标记优先，否则已退出进程返回工具失败，由现有 Agent loop 决定后续操作。没有新增 ACP 状态、重试或进程自动重开路径。
- `PersistentSessionCommandTest` 旧轮询先红后绿，覆盖丢失/退出会话、部分输出、完成标记优先、40 次重复及取消后下一次独立命令；相关原生测试共 16 项通过，APK 构建及覆盖安装成功。
- 可执行入口：`node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/xiaowan-session-exit.en.json OUT`。要求真实工具 journal 证明两次创建、第一次 exec 以 exit=7 失败、第二次不同 sessionId 的 exec 实际 stdout 正确、成功停止新终端，最后正式结束并重启复查。结果见 `artifacts/terminal-exit-20260909/verification.json`。
- 取消验证器旧断言错误要求一定有 assistant_message；纯工具轮已有正式 cancelled 元数据却被误判。修正为同一轮任意记录的正式 stopReason，并保留唯一终态和无待回答请求校验；文本“cancelled”不能代替元数据。新增回归先失败后通过，验证器 27 项通过，旧包失败轮实际取消断言通过。
- 独立未解决：权限回归先成功 Stop，随后真实模型返回空正文且无 tool_calls，正式报错；原因尚未定位，未计为全流程通过。另一次权限用例因模型设置弹层未关闭而未发送，不算产品生命周期复现。失败证据均保留。
- 小万 Stop 是取消当前轮，session/resume 恢复持久上下文，不提供命令执行栈暂停/原地续跑。本次修复不增加“暂停”能力。**待真机验证**。

- 本轮设备结果：`1788920467528` 全部 7 步通过；重复运行 `1788920830576` 在 60 秒回复观察期限失败，保留原失败结果，随后同一轮正式完成。另行核对实际工具结果及重启后的终态、工具记录均通过；没有重发请求，也不将迟到完成改记为完整 7 步通过。


### 2026-09-09：空模型响应与取消后下一请求的复查

- 复跑此前失败的真实模型权限流程：等待权限、Stop、正式 cancelled、下一请求、实际批准执行 id、正式完成、重启复查。`1788921133610` 全部 16 步通过；此前 `1788919769944` 的空正文/无 tool_calls 没有再次出现，不能据此宣称间歇故障已修复，也不能归因于终端退出修复。
- `HttpAgentLlmClientTest` 新增同一客户端连续 20 组“finish_reason=stop + 空正文 + DONE → 正常响应”，每个逻辑调用只发一次请求，空响应必须报错，下一调用实际返回 recovered。该回归验证现有实现，没有为得到绿灯增加重试或改变 ACP 语义；相关测试共 32 项通过。它不是旧故障的根因复现。
- 可执行入口：`./gradlew :app:testDevelopStandardDebugUnitTest --tests '*HttpAgentLlmClientTest'`；设备入口沿用 `scripts/fixtures/agent-user-journeys/xiaowan-live-permission-stop-allow.en.json`。证据 `artifacts/empty-response-recovery-20260909/verification.json`。
- 本轮仅新增测试、证据，没有产品代码改动。真实接口空响应根因仍待原始响应或稳定复现证据；未加入无依据的兜底重试。**待真机验证**。


### 2026-09-09：未提供暂停与执行栈恢复时的真实命令入口

- 小万现有 AvailableCommandsUpdate 发布 compact；init 是 UI 明示的生成/更新 AGENTS.md 提示词快捷入口，进入普通 prompt。session/resume 恢复持久会话上下文，不等于用户输入 /resume 后恢复某条已取消命令的执行栈。
- 新增 `scripts/fixtures/agent-user-journeys/xiaowan-unsupported-lifecycle.en.json`：检查 init/compact 显示，plan/review/pause/resume 不可点击；实际输入 /pause、/resume，断言没有活动 Stop 控件且数据库 user_message 数量不增加；重启重复整套操作。复用原 UI 输入与数据库只读快照，不直接调用产品接口。
- 解析回归覆盖 20 轮 /pause、/resume、大小写与带参数输入；相关 Flutter 5 项通过。当前实现即通过，未增加产品生命周期、重试或前端状态。
- 首次设备用例重启前均通过，重启后 UIAutomator 返回 null root，未执行点击；失败记录保留。设备上随后新快照确认界面已恢复，为用例补上命令按钮就绪观察，再执行原流程。最终证据见 `artifacts/unsupported-lifecycle-20260909/verification.json`。
- 此用例验证未支持命令不会误启动任务，不覆盖 init 生成文件内容，也不代表所有 Harness 的能力已验收。**待真机验证**。


### 2026-09-09：停留在会话列表时更新本地任务状态

- 页面原定时刷新只接受 remote，并监听已过时的 thread/turn/item 事件；本地小万被排除，正式任务结束后列表可能保留 Running。页面级回归实际复现：服务的 session/list 已返回完成状态，页面仍找不到 Finished now。
- 统一使用既有每 3 秒 session/list 查询，不从文本、工具输出或旧事件名推断状态；本地与远端同一路径，移除旧事件订阅。增加正在刷新保护，避免慢查询重叠；列表被其他页面覆盖时停止轮询，返回后恢复，dispose 后停止。
- 另移除远端“查询 loaded 再查询所有 session”的重复请求，因为现有 listLoadedSessions 已映射到同一 session/list；标准快照已有 loaded/active 信息，不需要另一套 loaded 查询。
- 可执行页面测试 `agent_sessions_refresh_test.dart` 覆盖 20 次运行/完成切换、页面覆盖/返回/销毁、慢查询；与原列表解析测试共 5 项通过。定向分析与最终 APK 构建通过。
- 设备入口 `scripts/fixtures/agent-user-journeys/xiaowan-session-list-refresh.en.json`：真实模型执行 sleep 60，进入列表后不点击刷新、不离开页面，观察 Running 1/Loaded 0 变为 Running 0/Loaded 1；核对唯一工具执行、正式完成并重启复查。前两次因测试未处理工具层及切层期间两个辅助功能容器而在进入列表前失败，原请求继续运行并正式完成；证据保留，不计完整验收通过。最终结果见 `artifacts/session-refresh-20260909/verification.json`。
- **待真机验证**。3 秒是既有列表观察频率，不改变 ACP 活动状态、完成时刻或 Agent loop。


### 2026-09-09：整合后的 Agent 回归入口

- `scripts/test-agent-runtime.sh` 补入本次已有的目录遍历、取消/关闭/删除失败、会话元数据与列表状态、列表刷新、命令入口和浮层关闭测试；核对最终 JUnit XML 中实际存在对应类，不能仅从 filter 参数宣称覆盖。
- 整合首轮 Orchestrator 5 项失败：旧断言要求直接显示 HTTP 原文，与已验证的分类提示冲突。更新精确提示断言，同时增加原始异常 HTTP 状态与 message 保留断言；不降低唯一请求、不重放、无虚假成功等要求。中间一次把旧界面简写误当异常 message 的测试错误也保留，最终按异常类原格式修正。产品代码未改。
- 最终分两组执行同一入口：`--offline --skip-flutter --skip-webchat` 与 `--offline --skip-gradle`。Node 86、终态验证器 27、Flutter 703、WebChat 12 项通过；WebChat typecheck/build 通过。原生精确计数及类清单见 `artifacts/integrated-regression-20260909/native-results.json`，总结果 `verification.json`。
- 这是离线整合检查；未运行 live Provider、实际 Harness CLI 和独立上下文压缩专项，不能视作所有 Agent/所有操作验收通过。保留此前本地 API/模拟器证据的独立适用范围。**待真机验证**。


### 2026-09-09：整合后的上下文专项与超限识别入口

- 运行独立 `scripts/test-context-boundaries.sh`；发现 AgentContextOverflowTest 未被专项或 Agent 总入口选中，现补入两个入口。该测试保留用户原始 `Prompt exceeds max length` 与数字 Input length 超限样本，并拒绝限流、服务错误、输出参数和文件名长度等误判。
- 最终专项 Kotlin 182、Flutter 349 项通过，JUnit 明确包含两项 overflow 测试。结果及实际类清单见 `artifacts/context-integrated-20260909/`。本轮没有修改压缩算法、检查点语义或产品代码。
- 当前为 Pi 与 Gemini 的 Kotlin 源码移植，具体固定版本及 MIT/Apache-2.0 声明见 `docs/third-party/compaction.md`；不宣称为直接 TypeScript SDK 集成。原上游 TypeScript 测试未在本轮执行，本地回归也不能证明所有服务商硬长度单位完全一致。
- 本轮没有运行设备长任务、SQLite instrumentation 或真实 Provider 超限；保留既有设备证据的独立范围，**待真机验证**。


### 2026-09-09：实际官方 Harness CLI 的重复协议验证

- 使用既有独立 npm 测试目录 `/tmp/oob-all-harness-acceptance` 和当前 Kotlin AgentAdapterCatalogTest 生成的配置，启动真实 Codex、Claude、DSH、Kimi、OpenCode CLI，连接本机合成响应服务；不读取真实 API 密钥、不修改用户 CLI 配置。
- `verify-installed-harness-adapters.mjs CLI_DIR app/build/reports/harness-adapters` 完整 14 组合连续两轮通过；Claude conversation 三组合核对两轮输出、续聊历史与会话归属；官方 tools.js 转换成功/失败工具结果两项通过。
- `verify-codex-completed-messages.mjs` 将原安装补丁应用两次，验证幂等；完整文本、部分文本、正常回复、失败和 Plan 五类×两配置，共 10 项通过。已把 Plan 纳入该长期入口；原 `test-agent-runtime.sh --harnesses DIR` 调用该入口时将一并覆盖。
- Codex 的失败通过协商的 Air sessionFailure 元数据报告；现有 AcpHarnessAdapters.codex.promptFailure 与 LocalAcpRuntime 在同一 PromptResponse 处理它，不能只读 end_turn。这里没有新增 Agent 生命周期或补丁。
- 版本与脱敏结果见 `artifacts/harness-cli-20260909/verification.json`。这是实际 CLI 加受控 HTTP，不是真实模型推理或 Android 沙箱验收；记录的 reasoning 字段不等于所有服务商都已实际执行相同推理设置。**待真机验证**。


### 2026-09-09：当前 APK 的 Android DSH 安装与沙箱复核

- 在 emulator-45562 的当前 0.6.2.2 debug 中通过 Agent mode → DeepSeek Harness 实际安装，界面显示 Assistant installed。安装前 Node 缺失，不能计作沙箱测试；安装后 DSH 0.1.2-rc.1、bash 正常，重启保留。
- 执行 `python3 scripts/verify-dsh-sandbox.py emulator-45562 OUTPUT.json` 并在重启后重复：官方 read-only/workspace-write 均为 SANDBOX_UNAVAILABLE。Landlock 官方静态启动器在 proot 内外均返回 125，报告内核不支持或未启用。
- 继续在测试 Alpine 以 App UID 安装 bubblewrap 0.12.0，重复官方启动参数，实际返回 `/proc/sys/kernel/overflowuid: Permission denied`；不是仅凭未安装推断不可用。测试入口增加该启动探测的 stderr，保留原官方 provider 判定。重启重复仍失败。
- 证据见 `artifacts/dsh-current-20260909/verification.json` 及四份探测结果。没有修改产品生命周期、系统权限或启用无沙箱回退；测试依赖保留。当前环境沙箱未通过，不宣称 DSH 已完成验收。没有执行 DSH 模型任务或隔离性验收，**待真机验证**。


### 2026-09-09：init 入口复用发送准入与附件引用

- 源码发现 init 卡片直接调用任务创建，绕过 `_sendMessage` 的 bootstrap、Harness 切换等待及发送锁；手动发送 /init 则没有将已经提取的附件传入任务。卡片现复用 `_sendMessage(text: '/init')`，手动命令把附件继续传入原 `_startAgentTurnCommand`，不增加第二套生命周期。
- 既有 chat_architecture_test 增加入口/附件传递约束，与 HarnessSwitchSendBarrier、slash parser 共 30 项通过；已在原 Agent 总测试入口内。新增约束属于源码检查，不能证明设备行为。证据 `artifacts/init-admission-20260909/`。
- 本轮尚未构建重装或运行 init 真实生成文件、重复点击、附件历史和重启恢复；不宣称该问题完成验收。**待真机验证**。


### 2026-09-09：init 实际创建、更新与重启重复

- 构建并覆盖安装当前 APK，安装与本地产物 SHA-256 均为 `da1be92cd71df42a6e15fc6a6d6705daf13a25b048fb1bf73c50fde609515ed8`，保留 App 数据。使用原本地 GLM 配置从 UI 点击 init，三次请求各自正式完成，工具调用分别 14/21/17 次；同一 Conversation/Session、三个独立 turn，无重复用户提交。首次创建 4,854 字节 AGENTS.md，后续实际读取和更新已有文件。
- 新增 `assert-agent-init.py`，按操作前 entry ID 校验唯一 init 提交、归属、正式成功与工具投影唯一性；6 项可执行验证器测试拒绝旧成功、重复提交、错误/取消/未完成、混合身份、重复工具记录，加入 Agent 总测试入口。长期设备入口 `xiaowan-init.en.json` 复用 UI journey，校验非空文件及哈希。
- 首次完整 journey 在重启后的菜单展开未就绪时失败，没有第三次用户提交；保存失败。为入口增加只读可点击等待，再从已确认未提交的步骤继续，第三次通过。第二/三次 init 步骤耗时约 298/391 秒，是完整工作区分析，不是消息启动计时。最后再重启，最新正式完成记录和文件哈希不变。证据位于 `artifacts/init-admission-20260909/`，不归档生成文档正文及私人记忆。
- 这是分段完成的实际重复序列，不宣称修正后的完整 journey 已一次跑绿。附件实际引用、快速重复点击、切换 Harness 时的准入及物理手机仍待验收。**待真机验证**。


### 2026-09-09：未提交消息的命令保留草稿附件

- 原 `_sendMessage` 在命令路由前清空附件；实际模拟器旧 APK 选入 33 字节测试文件后发送 /pause，确认没有新增 user_message，但附件消失。失败断言有连续可用快照，不是观察中断。
- 附件清理移动到普通消息及 Agent 快捷任务各自既有 addUserMessage 之后；现有 ChatPageModeState 按附件 ID 消费已放入消息的引用，保留等待期间新选附件、同路径但不同 ID 的替换和其他模式草稿。没有新增 ACP 生命周期。相关状态/架构/切换/命令测试 35 项通过，状态测试覆盖 20 轮及重复消费；加入 Agent 总测试入口。
- 准备：将 `scripts/fixtures/attachments/oob-attachment-admission.txt` push 到设备 `/sdcard/Download/`，通过 Add attachment 打开系统文件选择器，必要时使用 `scripts/tap-test-document-control.py SERIAL 'Show roots'`、`Downloads`，最后选择 `oob-attachment-admission.txt`。该 helper 仅允许这三个测试标签。
- 执行 `node scripts/verify-agent-user-journey.mjs SERIAL scripts/fixtures/agent-user-journeys/xiaowan-command-attachment-retention.en.json OUTPUT_DIR`。覆盖 /pause、/resume、/pause 连续三次无新增用户提交、附件仍可见；重启后重新选入同一文件，重复同一入口。修复版两组均完整 7 步通过，构建与安装哈希一致。证据见 `artifacts/attachment-admission-20260909/verification.json`，保留旧版失败。
- 这里不测试附件草稿跨进程持久化，也不代表 init 实际模型附件引用或快速重复点击已经验收。**待真机验证**。


### 2026-09-09：init 附件实际读取、取消与重启

- 当前 cbbd80f APK 中从系统选择器选择既有 33 字节测试附件，点击 init；历史校验唯一附件及 session/turn 输出后点击 Stop，再以正式 cancelled 验证，随后重启。第一组完整 3 步通过；模型实际调用 file_read 读取 /workspace/.omnibot/attachments 下副本，工具结果包含测试内容。
- 新增长期入口 `xiaowan-init-attachment-cancel.en.json`；扩展 init 验证器明确区分 active/end_turn/cancelled，附件缺失/重复时失败。验证器 8 项通过，相关 Kotlin 11、Flutter 2、journey harness 2 项通过。本轮未改产品代码。
- 重启后重新选入附件再执行，第二组在已启动后、点击 Stop 前遇到 UIAutomator 无法取得 idle state。保留失败；未重发 init，重新读取当前界面后仅点击 Stop，正式 cancelled，重启后复核。第二组按分段验收，不计完整 journey 跑绿。
- 可重复检查读取往返：`python3 scripts/assert-agent-attachment-roundtrip.py SERIAL USER_ENTRY_ID`，限定 init 的单条历史边界，核对唯一成功工具读取、返回哨兵内容及原缓存引用/工作区副本字节。当前实跑 USER_ENTRY_ID=782；第二次取消恢复验证基线 787。证据 `artifacts/init-attachment-20260909/`。
- 仍待快速重复点击、切换时准入及物理设备；这是文本附件实际路径，不宣称全部文件格式或第二次模型读取均覆盖。**待真机验证**。


### 2026-09-09：init 快速双击、取消与重启重复

- 复用维护的 init 验证器，新增 `xiaowan-init-double-tap.en.json`。Android 输入工具在刚观察到的 init 控件位置执行一次双击手势，保存两个点击时间；不在第一次关闭菜单后寻找别的控件。间隔实际为 98/78 毫秒。
- 模拟器完整 5 步一次通过：双击启动→唯一用户消息与同一 turn 输出→Stop→正式取消→重启→重复。两组同一 session、两个独立 turn，最后重启后取消记录仍一致。相关验证器 8、journey harness 2 项通过，本轮无产品变更。证据见 `artifacts/init-double-tap-20260909/verification.json`。
- 这是实际触摸的防重复提交结果，不证明两次触摸都进入 Flutter 回调，也不覆盖 Harness 切换等待期间排队发送。**待真机验证**。


### 2026-09-09：切换完成与发送恢复之间的新切换

- 确定性异步测试复现：先完成已有 Harness 切换，紧接着开始下一次切换，再让等待发送的 continuation 恢复。旧等待器的两个 waiter 都返回 true，虽然新的切换仍未完成。保留实际失败日志。
- 在现有 HarnessSwitchSendBarrier.waitUntilIdle 内，每次成功等待后重新检查当前 pending；若原等待失败，仍返回 false，不因后来切换成功而自动重发。没有新增业务生命周期或传输重试。
- 20 轮每轮两个 waiter 的切换衔接及失败不复活测试，与架构/附件状态共 32 项通过；该测试文件原先未进 Agent 总入口，现已补入。构建、空闲检查后覆盖安装及 APK 哈希核对通过。证据 `artifacts/switch-resumption-20260909/verification.json`。
- 当前是确定性单元时序证据，尚未在模拟器操作中验收本次并发切换；安装成功不替代验收。**待真机验证**。


### 2026-09-09：Harness 切换草稿与实际发送归属

- 新增长期 `xiaowan-harness-switch-draft.en.json`：prepare-draft 仅通过 UI 输入脱敏草稿，不发送；选择 DSH 后检查完整 EditText 文本及原生 selected_profile_id，切回小万再次检查，再点击一次 Send，验证本地 GLM 实际回复与正式完成，重启复核。输入工具按字符键入的约 51/56 秒不是 App 消息启动耗时。
- 首次把回复整行断言误用于多行草稿，测试失败；截图及原生选中项确认已切 DSH、草稿仍在。保留失败，修正为输入框完整文本检查，从原未发送草稿继续完成。之后完整 11 步重新运行一次通过，journey harness 2 项通过。本轮未改产品代码。
- `assert-agent-prompt-owner.py SERIAL OOB_LIVE_SWITCH_DRAFT_RUNID xiaowan-acp` 读取唯一用户提交、Conversation→Session 绑定、ProfileStore 的 Session→Agent 映射以及输出 turn，实际验证两轮属于小万且各自独立 session。证据 `artifacts/harness-switch-draft-20260909/verification.json`。
- 这证明顺序切换、草稿保留及实际发送归属；没有刻意触发微任务间隙的新切换，不替代上一轮竞态的设备验收。未发送 DSH 模型任务、不涉及沙箱通过。**待真机验证**。


### 2026-09-09：近期 init、附件和切换修复后的离线总回归

- 运行更新后的 test-agent-runtime.sh --offline。Node 86、历史验证器 27、init 验证器 8、App JVM 658、baselib JVM 3 项通过；原生报告无失败、错误或跳过，并核对新增超限、目录/搜索和 session 生命周期类。测试阶段短暂无输出时检查同一个 JVM 线程，实际在运行安装包全部组合的 shell 语法检查，随后正常完成，没有重启测试。
- 首次 Flutter 阶段因调用者用了另一脚本的 OOB_FLUTTER_BIN 变量名，落到系统 Dart 3.9.2，依赖解析失败。保留失败；改用本入口要求的 FLUTTER_BIN=/tmp/oob-flutter-3.47.2/bin/flutter，以 --offline --skip-gradle 继续余下阶段。Flutter 716、WebChat 12 项以及 typecheck/build 通过，不重复计算再次运行的 Node/Python 数量。本轮没有产品修改。
- 证据 `artifacts/integrated-latest-20260909/verification.json` 与实际原生类清单、两阶段日志。Flutter 并行人类可读日志会复用当前测试标题，不能仅凭某个名称未出现断言未执行；保留选定文件、最终数量及此前定向回归的独立证据。
- 本次明确跳过真实 Provider/Harness CLI，不含设备旅程；此前实际本地 API、模拟器结果维持各自适用范围。并发切换设备验收和 DSH 沙箱仍未解决全部验收条件。**待真机验证**。


### 2026-09-09：五种官方 Harness 的真实 API 生命周期对比

- 使用既有 verify-live-harness-conversations.mjs，临时 CLI HOME、透明观察代理和实际服务商 API，GLM-5.1/GLM-5.2 均先经真实模型目录确认。未使用合成回复，未修改用户 CLI 配置。新增逐案例脱敏进度输出。
- 五种 Harness 的可见回复、同 session 续聊、真实文件工具输出、实际模型切换、取消后下一轮均通过。Codex、Claude Code、Kimi Code、OpenCode 的进程重启 session/load 通过；DSH 未声明 loadSession，记录不支持，不计恢复通过。
- Kimi 的思考设置实际请求变化通过；Claude/OpenCode/Codex 本次 session 未声明该选项，不能计思考能力通过。DSH 该断言失败，尚不能确定是 Harness 参数映射还是观察字段不全；待进一步定位。Codex 模型目录检查失败：实际出现 gpt-5.6-luna 请求，不能宣称所有请求都遵守配置。总结果失败，保留原始脱敏 JSON，不降级断言。
- 执行入口：OMNIBOT_TEST_MODEL=GLM-5.1 OMNIBOT_TEST_SECOND_MODEL=GLM-5.2 node scripts/verify-live-harness-conversations.mjs CLI_DIRECTORY app/build/reports/harness-adapters；凭据仅从环境读取。证据 artifacts/harness-real-api-20260909/。
- 本次在 Mac 运行官方 CLI，不代表 Android DSH 沙箱通过，也不替代 App 点击流程与物理设备验收。**待真机验证**。


### 2026-09-09：Codex 标题模型覆盖与 DSH 思考设置误判

- 官方 codex-acp 1.10.0 的 TitleGenerator.generateAndPersist 在临时 thread 上覆盖 model 为 gpt-5.6-luna。此前真实目录外请求均为该模型并收到 403。沿用版本及源码形状校验的安装补丁，去掉标题专用 model 覆盖，继承 thread 已配置的模型；不新增标题 Agent 或主机重试。旧完成消息补丁标记不再提前退出，支持已打旧补丁的安装升级，两个补丁分别幂等。
- 真实 GLM-5.1/5.2 对比重跑，Codex 目录外请求归零，续聊、实际文件工具、模型切换、进程重启恢复、取消后下一轮通过。完成文本/部分文本/正常/失败/Plan 共 5 场景 × 2 wire fixture 通过，重复安装后源文件完全一致。
- DSH 的默认与 off 都可能省略参数；原测试把不同选项等同于不同 wire 是误判。改测明确 high 再 off，真实请求从无参数→reasoning_effort=high→无参数，API 两次均成功，未改 DSH 产品代码。其 session/load 不支持仍单独记录，不能计恢复通过。
- 新 APK 构建成功、尚未安装。本模拟器未安装 Codex ACP，后续需验证安装器及实际 App 操作；现有 Codex 用户仅升级 APK 不代表已重新应用安装补丁，需覆盖安装/更新路径验收。证据 artifacts/codex-title-reasoning-20260909/。本次 Mac CLI 通过不证明 Android DSH 沙箱可用，**待真机验证**。


### 2026-09-09：Codex 安装补丁更新后的准备版本失效

- 发现标题补丁变化未更新 preparationRevision，旧设备的 online/installed 状态可能继续复用旧准备记录。在真实 catalog 读取的测试中先复现旧 revision 错误复用，再将现有准备标记更新为 codex-acp-1.10.0-message-completion-1-title-model-1。未新增安装生命周期或每条消息安装逻辑。
- 覆盖旧完成消息补丁版本不能复用、命令及包健康仍要求准备、新版本准备后可复用。Native 83、Node catalog 46 项通过；构建及 emulator-45562 保留数据覆盖安装、启动成功，hash 见 artifacts/codex-preparation-revision-20260909/verification.json。
- 用户新增自动压缩检查优先级，尚未点击手机内 Codex 安装，当前没有后台安装任务；实际新装/更新与重启验收仍待进行。**待真机验证**。


### 2026-09-09：本地真实 API 连续自动摘要与重启恢复

- emulator-45562 最新安装 APK，小万既有本地 GLM-5.1 配置；在测试会话 6 通过 UI 将阈值从 128k 调为 32k，开始前无摘要。一次用户发送要求连续读取 20 页，不调用手动 compact。正式 end_turn，观察到同任务三次不同摘要 revision/cutoff 推进；重启前后最终摘要 hash/cutoff/revision 完全一致。结束后通过 UI 恢复 128k，数据库确认成功。
- 实际 3 步任务 + 4 步续接验证均通过；不是重新跑完合并入口。新 xiaowan-live-auto-compact.en.json 合并上述流程及严格分页断言，共 8 步。verify_live_checkpoint 仅接受本次用户之后、下一用户之前的成功工具检查点，排除旧/后续任务摘要冒充成功；5 项断言测试、2 项 journey 测试通过，纳入 Agent 总入口。
- 严格分页断言实际失败：除覆盖全部 20 页，还存在初始页重复、读取 offload 文件绕路以及一次非法 offset 后恢复。原结果含 nextOffset，但 boundToolOutputs 把整个工具文本替换成引用，当前分页控制信息不再直接可见；后续需要修复这一上下文投影边界，不能因最终完成就计为全通过。证据 artifacts/auto-compaction-real-api-20260909/；无凭据或完整摘要内容入库。
- 这次证明真实 API 的自动摘要持续运行且检查点跨 App 重启保持，并未证明无绕路或所有长任务都可靠。**待真机验证**。


### 2026-09-09：外存结果保留当前分页元数据

- 实测首次 20 页任务用了 43 次工具调用，模型绕路读取外存结果。新红测试经导入修正后明确失败 Paging cursor lost for file_read。沿现有 boundToolOutputs 投影，在完整结果落盘后，为最新结果保留有上限的精确小字段，包括 nextOffset、hasMore、任意 cursor；没有工具名分支，正文不截成摘要，不引入 Agent loop。小字段仍受共享工具预算约束，旧结果不追加新预览。宿主适配边界及限制记录在 third-party/compaction.md。
- 独立 92 项测试通过，包含 60 轮新结构化结果预算回归、不同工具名、完整外存正文、异常输入、深度和长度限制。最新 APK 已构建覆盖安装，hash 与实际 API 所用 APK 分别记录在 artifacts/tool-output-metadata-20260909/verification.json。真实 API 后仅补了外层 JSON 两字符预算计数及边界测试，未为该微小变更重复跑 API。
- 本地 API 同一 32k 阈值实测恰好 20 次 file_read，0..1245184 以 65536 连续推进，无重复/工具错误；任务阶段 195176ms，前轮 555354ms，单次时长对比不宣称通用加速比例。正式完成及重启后的完成记录、已有摘要 checkpoint 均保持。阈值通过 UI 恢复 128k 并核对落库。
- 合并 8 步入口仍失败：本轮压缩后的 cutoff 在当前用户消息之前，不能满足“本任务内部工具组被摘要”的严格条件。没有降低断言或标成全绿；分页严格断言单独实跑通过，重启 3 步独立通过。更长同任务压缩、正文理解质量与真机仍待验证；**待真机验证**。


### 2026-09-09：最新修复后 60 页同任务摘要及重启续读

- 新增独立 xiaowan-live-auto-compact-60.en.json，沿用 32k 阈值与既有本地 GLM-5.1，在最新 APK 00d1cb6e095a9743d5ffe8e47e8201ce87db8240e7971dfd64c1ea9bde264ad9、emulator-45562 完整 8/8 通过：恰好 60 次 file_read，0..3866624 按 65536 连续推进，无重复或工具失败，同一规范 session/turn。约第 28 页检查点进入本任务工具组，之后继续推进；正式完成及重启前后 checkpoint 一致。
- 另一个独立 4/4 续读旅程通过：新消息要求继续此前任务，不提供文件路径或 offset；实际只调用一次原文件 file_read，offset=3932160，正式完成。该消息提及 60 页，因此不声称无任何提示的记忆测试或全文理解验收。两组实际报告分别保留，不拼成单次 12 步报告。
- 旧 20 页严格摘要失败保留；新增长压力用例满足原先缺少的同任务摘要条件，没有降低其断言。验证器保留旧 20 页模式，新增严格 60 页与单次续读模式；旧 20 页实际分页验证再次通过。具体准备/执行见 xiaowan-real-api-compaction.md。
- 本轮未修改产品代码。测试工具输入约 186/196 秒为按字符输入耗时；数据快照也有明显耗时，不用它们推断 App 首 token 延迟。阈值已通过 UI 恢复 128k，落库核对成功。恢复时一次控件未匹配未点击，重新取得页面后完成，没有重发模型任务。证据 artifacts/auto-compaction-60-20260909/；**待真机验证**。


### 2026-09-09：小块正文读取及压缩后可读性

- `file_read.maxChars` 贯通既有读取器，范围 2–65536、默认不变；不新增 Agent loop 或压缩算法。6 项文件读取测试验证单行 HTML、Unicode、原文件完整性和中英文 schema。
- 模拟器现有本地 API 先完整 4/4，再重启重复 5/5：两次 2048 字符读取及第二页正文校验值、正式结束均通过。证据见 `artifacts/small-file-pages-20260909/` 与 `artifacts/small-body-compaction-20260909/`。
- 新增 32K/10K 工具预算下大结果外存后小块正文保留测试；压缩器/Orchestrator/预算/超限恢复共 91 项通过。保留两次测试前提不充分的失败记录，不将其计为产品 bug。
- UI 自动化 focus 后空节点只等待观察恢复，不重放输入框点击；2 项脚本回归通过。首次重启用例因该观察问题失败，原记录保留；新执行完整通过。
- 专项说明 `xiaowan-small-file-pages.md`；本轮没有证明所有摘要语义或所有文件格式正确，**待真机验证**。


### 2026-09-09：正文分页修复后的整合回归

- 总入口补齐压缩器、预算、文件读取和发送观察脚本测试。Node 88、日志验证器 27、init 8、checkpoint 5 通过。
- App 本轮实际运行 693 项，1 条旧 schema 断言误将可续读 maxChars 当作丢弃原文的截断；保留失败并修正为仅 file_read 可声明可选续读页大小，其他工具继续禁止。定向复测 schema 7 + 文件读取 6，全 13 通过。baselib 3 项是 Gradle UP-TO-DATE 复用旧通过报告，不计本轮重新执行。75 个指定类的报告全部匹配，没有缺失。
- 同类 Node 源码断言也已修正并通过。没有以重命名参数规避断言，没有修改产品逻辑。原文完整性和分页连续性仍由实际文件读取测试验证。
- Flutter 716、WebChat 12、typecheck/build 通过；配置的本地 GLM-5.1 目录与真实 completion 通过。证据 artifacts/integrated-compaction-20260909/verification.json。结果来自多个明确记录的阶段，不是一轮连续全绿。本次未重跑官方 Harness CLI，不替代模拟器用户旅程或物理设备验收，**待真机验证**。


### 2026-09-09：小万关闭调用被取消后 MCP 清理遗漏

- 实际私有 Session 方法的时序测试复现：close 置 closed=true 后等待 prompt 停止，调用者取消导致 MCP close 及 onClosed 未执行；再次关闭直接返回。
- 原 close 方法用 NonCancellable 完成已有资源清理，closeMutex 让重复关闭等待同次清理结束，不新增生命周期或普通 Stop 重试。第一次修复中的协程 cancel 名称解析问题被测试拦截，明确限定为 Session.cancel 后通过；失败版未安装。
- JVM 关闭/删除/连接 10 项、ACP 源码契约 46 项通过；已接入总入口。构建、保留数据安装成功，APK SHA256 7702d3535eead7f26999857da5cba2af2ee5d9a96db1a1f450b93e4f0312c9a4。
- 新增模拟器本地 API 用户旅程完整 11/11：启动后 Stop、正式取消、同会话下一轮正常完成、重启后两个终态保留。该旅程不等于精确 close 取消竞态的设备验收；会话列表没有独立 close/delete 按钮。详见 xiaowan-session-close-cleanup.md 和 artifacts/session-close-cleanup-20260909/。
- **精确关闭竞态待设备验证，待真机验证**。不得将普通停止通过扩大为所有关闭路径已验收。


### 2026-09-09：MCP 关闭失败不得伪装成功或阻断显式再次清理

- 两条真实旧实现失败：MCP 关闭吞异常；Session 在清理失败后再次 close 未重新清理（应两次、实际一次）。初版异常身份断言调整为对外错误消息和调用次数后，保留行为红测。
- 原 MCP owner 尝试关闭其余连接后报告普通异常；原 Session owner 在 closeMutex 内单独记录 cleanupComplete，禁止新 prompt 的 closed 标志不再等同于清理成功。失败保留登记，下次显式 close 可继续；没有自动重试、重开会话或新 ACP 状态。
- 28 项 JVM、46 项契约通过。APK b06078c33e18e9cbc627643243743c6d4a28ca568cacb4f48b30d40e13042f39 构建和保留数据安装成功；emulator-45562 使用现有本地 API 的 Stop→下一轮→重启历史 11/11 通过。
- 证据 artifacts/session-close-failure-20260909/，用例继续在 XiaowanSessionCloseCleanupTest 及既有用户旅程入口。普通 Stop 不证明 MCP 关闭异常在设备上已验收，**待设备故障注入、待真机验证**。

### 2026-09-09：终端停止必须清理实际子进程，并保留 ACP 标准输入

- 用户要求：完成生命周期问题，停止后仍能继续任务；不能只看 UI 已取消或模型文字。
- 范围：沿用 Conversation → ACP Session → Turn → Item；终端启动脚本把 host TERM 交给 PRoot 的 QUIT 清理，不新增 reducer、状态机或业务重试。工具停止复用已有进程清理。会话 close 的失败/取消清理见独立 close 回归。
- 可执行入口与脱敏样本：[xiaowan-terminal-stop-lifecycle.md](xiaowan-terminal-stop-lifecycle.md)，`scripts/test-terminal-host-stop.py`、`scripts/test-terminal-child-state.py`、`scripts/verify-terminal-stdio.py` 与 `xiaowan-terminal-child-stop.en.json`。
- 边界：真实子进程身份/年龄、防止自然到期和 App 重启伪通过、正式 cancelled、下一轮终端真实 stdout 与 end_turn、无助手文字的合法结束、重启历史、Android mksh 后台 stdin、非零退出码。
- 结果及失败证据：`artifacts/terminal-stop-lifecycle-20260909/`。保留观察器失败、中间版本 stdin 丢失、设备时间预检失败以及过度要求助手回复导致的断言失败；没有把失败整轮改写为通过。**待真机验证**。

- 最终 APK `5a0e6e644340c9070fd0a9835e6bab595e7fd3d94d0cd26835117cd1464f1961`：真实 API 界面回归 12/12 通过，已安装运行时的 stdin/exit=7 探针通过；尚待物理手机验收。


### 2026-09-09：设置准入、关闭后排队请求与参数弹层返回

- 对应用户目标：运行中/取消后的配置生命周期，以及参数操作后不能继续任务的问题。
- Kotlin 在已有 promptMutex/closeMutex 上保护设置入口；新增 4 个可复现的失败用例，覆盖 worker 尚未启动、取消清理未结束、会话关闭和排队后关闭。
- 参数弹层通过 Flutter PopEntry 声明根页面可处理返回，并保留既有顶层弹层返回顺序。覆盖系统返回、直接 Navigator 返回、主动关闭、多个弹层及焦点保留。
- 入口：[xiaowan-session-config-admission.md](xiaowan-session-config-admission.md)，`XiaowanSessionAdmissionTest`、`glass_popup_back_test.dart`、`xiaowan-session-config-cancel.en.json`。已接入既有测试脚本。
- 最终 APK `d20b5d3f3984717f0645dac8275982651c0df1469baae2e6e5da2505a8fc08ef`：原生 18 项、Flutter 21 项、Node 48 项通过；emulator-45562 / 配置的真实 GLM-5.1 API 连续 27/27 步通过，包含恢复默认设置和重启。证据在 `artifacts/session-config-admission-20260909/`，中间真实失败未删除。精确并发空档目前仅 JVM 覆盖，**待真机验证**。

### 2026-09-09：ACP 删除失败保留清理所有权

- 对应生命周期问题：删除先移除 Session 登记，关闭资源失败后无法再次清理。
- 入口：[xiaowan-session-delete-cleanup.md](xiaowan-session-delete-cleanup.md)、`XiaowanSessionDeleteCleanupTest`，已加入 `scripts/test-agent-runtime.sh`。
- 旧实现两项中一项真实失败；修复后相关原生测试 13 项通过，APK 构建成功。覆盖关闭失败后显式重试、绑定解除失败后不重复释放资源。
- 失败分支目前由 JVM 测试覆盖，UI 归档不能替代 ACP 删除验收。**待设备故障注入与真机验证**。
- 最新包 `6bf2ee96974596b68922f4b144aada216674467eb9676c87b0f52a2ffc5142d3` 已保留数据安装；运行 `1788950333752` 使用真实 GLM-5.1 API 完整设置/停止/下一轮/重启回归 **27/27 步通过**，最终恢复默认设置。

### 2026-09-09：执行前登记与关闭互斥

- 入口：[xiaowan-worker-registration.md](xiaowan-worker-registration.md)、既有 `XiaowanPromptWorkerTest` / `XiaowanSessionAdmissionTest`。
- 新增三条旧实现真实失败：登记前执行、拒绝登记后执行、关闭时进入附件准备。使用既有 worker 的 lazy start 和 Session closeMutex 修复，无新协议或重试路径。
- 相关原生 21 项通过，APK 构建及保留数据安装成功。精确交错为 JVM 证据，**待真机验证**。
- APK `8a3e5e93fe4501245b676a13b728542080f5901a4a9e5cb0bb473181c84bca3b` 在 emulator-45562 使用真实 GLM-5.1 API 连续 27/27 步通过（`1788950971603`），包含设置、真实子进程停止、下一轮完成和重启恢复。证据 `artifacts/worker-registration-20260909/`。

### 2026-09-09：最新安装版本环境与命令能力复验

- [当前版本报告](current-runtime-capabilities-20260909.md)：同一 `8a3e5e93…` APK，App UID 下标准输入与退出码探针通过；DSH 官方 read-only/workspace-write 仍失败，Landlock 内核未启用、bubblewrap 的 /proc 访问被拒绝。没有修改权限或启用无隔离回退。
- 小万菜单及手动 /pause、/resume 回归 **23/23 步通过**，重启前后均没有误提交用户消息或启动任务。复用已有可执行测试，不发模型请求。
- 结果在 `artifacts/dsh-sandbox-20260909-current/`。DSH 隔离和 UI 错误展示尚未完成验收，**待真机验证**。

### 2026-09-09：登记等待中的断开与停止

- `XiaowanPromptWorkerTest` 新增 collector 断开和 worker 停止两种挂起交错，各重复 20 次，验证子任务释放、零工具执行、终态发送边界以及断开后的独立请求。
- 与 SessionAdmission 合计 17 项通过；本次仅扩展测试，没有产品改动。详情 [登记生命周期回归](xiaowan-worker-registration.md)，证据 `artifacts/worker-registration-wait-20260909/`。
- 精确调度仍是 JVM 验证，不能替代真机验收。**待真机验证**。

### 2026-09-09：生命周期修改后的原生整组验证

- 执行 `scripts/test-agent-runtime.sh --skip-flutter --skip-webchat --live`，连续退出 0。App 708 项实际运行通过；Node 88、Python 53 项通过；GLM-5.1 模型列表及对话请求通过。
- baselib 3 项是 UP-TO-DATE 的既有结果，本轮未重跑。Flutter、WebChat 和其他 Harness 未在本轮执行。
- [完整范围与证据说明](integrated-native-current-20260909.md)，XML 结果及原始哈希清单在 `artifacts/integrated-native-current-20260909/`。**待真机验证**，不代表全部目标完成。

### 2026-09-09：Flutter 与 WebChat 整组回归

- 维护入口 `scripts/test-agent-runtime.sh --skip-gradle --offline` 连续退出 0：Flutter 720 项、WebChat 12 项及 typecheck/build 全部通过。
- Node 88、Python 53 是该入口重复执行的既有用例，不累计为新增覆盖；本轮未运行 Gradle、真实 API 或其他 Harness。
- [执行范围与证据](integrated-ui-current-20260909.md)，结果 `artifacts/integrated-ui-current-20260909/`。没有浏览器验收或产品改动，**待真机验证**。

### 2026-09-09：Codex Android 安装与真实对话

- App 管理页实际安装 Codex CLI 0.153.4 / Codex ACP 1.10.0，观察安装中、安装成功、Available。未清数据或修改权限。
- 新增可执行 `codex-real-api-basic.en.json`，现有本地 Provider 下两轮及重启 **11/11 步通过**。补充查询证实同一 Conversation/Session、不同 turnId。
- [完整范围](codex-android-real-20260909.md)，证据 `artifacts/codex-android-real-20260909/`。工具、权限与 Plan 的 Android 验收仍待进行，**待真机验证**。

### 2026-09-09：权限弹层返回与 Codex 终端实测

- [权限菜单返回修复](permission-popup-back-20260909.md)：真实打开权限菜单按系统返回退出 App。复用通用弹层返回处理修复，Flutter 38 项与 Node 48 项通过。
- [Codex 终端失败](codex-terminal-exit-20260909.md)：workspace-write 下预期输出标记并 exit 7，实际一次 commandExecution 返回 182、输出为空，**未修复**。
- 原 13 步仅证明对话恢复等流程通过；已增加真实工具退出码/输出断言，并对同一任务只读重验得到失败。观察器 30 项通过，未重发请求或提升权限。**待真机验证**。
- 权限返回新包另有完整 **12/12 步**模拟器回归通过，覆盖重复与重启；权限仍为 workspace-write。重启时 null root 的观察失败保留，最终用已有 expect 就绪等待恢复。

### 2026-09-09：Codex 沙箱直接对照

- 新增实际运行的 `scripts/verify-codex-sandbox.py`：同一 App UID/PRoot/命令，普通 bash 返回 7 并输出标记，官方 `codex sandbox -P :workspace` 返回 182 且无命令输出。探针如实返回失败。
- 无模型请求或 ACP，未修改权限或持久化配置，排查范围缩小到 Codex 沙箱执行边界。具体内部原因仍未查明，**未修复、待真机验证**。
- 证据 `artifacts/codex-terminal-exit-20260909/maintained-sandbox-probe.json`；完整说明见 [Codex 终端验收](codex-terminal-exit-20260909.md)。
- 同一入口新增 `--native` 诊断并实际运行：直接原生二进制仍 status=182、signal=null，普通 bash 对照通过；记录 `maintained-native-probe.json`。排除当前 npm 包装层的信号转换，内部原因仍未确定。没有修改产品配置或权限。
