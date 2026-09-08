# 对话驱动的长期回归测试索引

每次工作对话将用户的实际需求、故障与验收条件加入此索引，并链接既有可执行测试及证据。清单不等于已实现测试；模拟器通过不等于真机验收。原始聊天记录不作为可公开测试数据，样本应脱敏。

## 2026-09-07：小万读取文件与长上下文

更新：用户已授权先用 Kotlin 移植。CTX-001 至 CTX-005 的实现与新增可执行回归见 `context-compaction-fix-2026-09-07.md`；144 项本地测试与连续读取/重启模拟器回归通过，仍待真机验证。下表中“待实现/未修复”为最初审计状态，以该修复报告逐项描述的覆盖与限制为准。CTX-006 配置同步问题不在本次修改范围。

| ID | 用户场景及预期 | 已有入口或记录 | 当前状态 |
| --- | --- | --- | --- |
| FILE-001 | 大图读出后显示，模型收到原图，App 不退出 | `scripts/fixtures/agent-user-journeys/xiaowan-file-read-regression.en.json`；`image-read-crash-2026-09-07.md` | 模拟器验证通过；待真机验证 |
| FILE-002 | 大 HTML/文本分段读取，边界和末页准确，不全量撑爆内存 | 同一 UI journey；`file-read-memory-2026-09-07.md` | 模拟器验证通过；待真机验证 |
| FILE-003 | 工具结果重复字段不反复进入模型请求；重启后原始内容仍可查看 | `XiaowanToolResultPayloadTest`、`AgentEventAdapterTest`；`xiaowan-tool-result-dedup-2026-09-07.md` | 本地与模拟器验证通过；待真机验证 |
| LIFE-001 | 读取后取消，继续发送；重启恢复完成和取消状态；并行会话不串结果 | `xiaowan-emulator-acceptance-2026-09-07.md` 和其 artifacts 中各 journey/result | 模拟器验证含人工补测；不是全自动通过；待真机验证 |
| CTX-001 | 用户报告输入 1,119,534 超过服务上限 1,048,566；首次恢复即超限也应在发送前处理 | `xiaowan-long-context-audit-2026-09-07.md` | 尚无该服务的可执行复现；长度单位待确认；未修复 |
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
