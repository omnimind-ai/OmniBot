# 长上下文后续检查 — 2026-09-08

状态：发现尚未修复的问题。此次只增加回归测试和审计证据，未修改生产逻辑。之前通过的测试不能覆盖以下新案例；当前新增测试为失败状态。没有实体手机，待真机验证；未调用真实外部模型。

## 已复现

1. `AgentConversationHistoryRepositoryTest.offloadingResultMustNotLoseItsDurableCheckpointIdentity`：调用真实压缩器把大结果保存成文件引用后，原来可定位的检查点从 7 变为 null。定位依赖 `modelAssistantMessageJson` 和 `modelToolResultMessageJson` 的内容相等，而内容是可变请求视图，不是稳定身份。小万同一任务内后续压缩可能无法落盘检查点。
2. `ConversationCheckpointTest.lateSnapshotCannotResurrectExplicitlyClearedCheckpoint`：真实 Android SQLite 上，明确 clear 后迟到的普通快照复活 old summary。clear 把 contextSummaryUpdatedAt 归零，而普通 update 仍可写入整个检查点。检查点写入权限与失效顺序没有闭合。3 项该类 instrumentation 测试运行，旧 2 项通过、新 1 项失败。

附加读取证据：隔离模拟器 emulator-5560，历史回归会话 7 最近 20 条 tool_event 中，上述两个 canonical 字段均为 0 条。源码检索只找到这些字段的读取/保留逻辑，当前 ACP 写入链路未找到对应生产写入。测试中手造 canonical 行不能代替完整 App 持久化验证。不能把“重启后页面还在”当作“摘要及截断位置已可靠恢复”。

## 代码层面资源风险（未独立复现 OOM）

- AgentOrchestrator.executedTools 保留每个 ToolExecutionResult 到任务结束并交给 AgentResponse；Context/MCP/Terminal 等结果的原始大正文不会因为 request memory 压缩而自动释放。
- logPromptCacheFingerprints 每次请求仍把历史 JSON joinToString，再转 UTF-8 计算哈希，产生额外内容副本。
- toolResultAcpPayload 对不同的 preview/raw 同时传输，终端还会携带 terminalOutput。它们可能包含不同有效信息，不能直接按字段名删除；需要统一完整结果引用与有界展示的责任。

## 可执行入口

```bash
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests '*AgentConversationHistoryRepositoryTest.offloadingResultMustNotLoseItsDurableCheckpointIdentity'
./gradlew --no-daemon --no-parallel :baselib:assembleDebugAndroidTest
adb -s emulator-5560 install -r baselib/build/outputs/apk/androidTest/debug/baselib-debug-androidTest.apk
adb -s emulator-5560 shell am instrument -w -e class cn.com.omnimind.baselib.database.ConversationCheckpointTest cn.com.omnimind.baselib.test/androidx.test.runner.AndroidJUnitRunner
```

执行结果见 artifacts/context-audit-2026-09-08/。测试仅使用合成消息、隔离测试数据库；没有清除 App 数据或覆盖用户会话。

## 修复顺序

先把检查点提交/清除集中到既有 Conversation 存储所有者，普通快照无权改写检查点；异步摘要提交必须检查有效身份及旧检查点前提。随后把模型上下文中的项映射回既有 session/turn/tool 身份及历史行，消除对结果正文及遗留字段的定位依赖，不增加第二套历史或 ACP 生命周期。最后处理结果对象保留及诊断日志复制，并做长任务内存验证。完整验收必须覆盖真正产生摘要、持久化非空检查点、重启仅加载摘要和未压缩后缀，以及迟到保存/清除/失败的交错。

后续修复状态：以上失败用例已转为通过，另补充迟到摘要 CAS、展示回写身份和历史内存边界回归。最终结果见 [检查点修复](checkpoint-ownership-fix-2026-09-08.md) 和 [记忆恢复验证](memory-recovery-2026-09-08.md)。保留本文作为修复前审计记录。待真机验证。
