# 对话驱动的长期回归测试索引

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
