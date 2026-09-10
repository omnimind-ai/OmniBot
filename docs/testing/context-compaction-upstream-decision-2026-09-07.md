# 压缩上游对照与历史归因

后续状态：用户明确授权“先用 Kotlin”。已按这里的上游行为作 Kotlin 移植与现有运行时集成；许可证、差异见 `../third-party/compaction.md`，验证结果见 `context-compaction-fix-2026-09-07.md`。下文的“尚未集成”描述保留为选型阶段记录。

## 历史结论

直接检查 `v0.6.0.3`（tag 指向 `8dc72e554`，2026-08-31）而非根据印象推断：

- 该版本的 `AgentConversationHistorySupport.buildRuntimeCompactionWindow` 已只选择最后一条用户消息之前的内容；重建会保留最后用户消息及之后的全部消息。同一长任务无法缩减的限制是已有缺口。
- 该版本的 `compactIfNeeded` 同样依赖 promptTokens，调用发生在成功响应之后、工具结果加入之前。
- 旧版本有 `compactForOverflow` 与最多一次强制压缩续请求。`49b7b5205`（2026-09-05 15:54:59 +08:00）删除了这条恢复路径；这是保护能力变化，不是同一任务压缩缺口的首次引入。
- 将用户这次错误文字 `Input length 1119534 exceeds the maximum length 1048566` 与旧版 21 条 CONTEXT_OVERFLOW_PATTERNS 对照，匹配数为 0。旧版可能还读取响应体中的其他字段，但仅凭本次错误文字无法触发其识别。因此不能承诺回滚到 0.6.0.3 就能解决这次错误。

## 上游源码核对

### 首选评估：Pi 的压缩模块

固定提交：`badlogic/pi-mono@7d8ab31a477ecc07b36f56ffcae58c79307a68be`。源码许可证 MIT，集成必须保留声明。

[compaction.ts](https://github.com/badlogic/pi-mono/blob/7d8ab31a477ecc07b36f56ffcae58c79307a68be/packages/coding-agent/src/core/compaction/compaction.ts) 中已存在：

- `estimateContextTokens`：最后一次有效 usage 加后续新增消息估算；没有 usage 时估算当前消息。
- `findCutPoint`：按最近内容预算确定保留边界；可以在同一任务中切分，不在工具结果处切断调用配对。
- `prepareCompaction`：分离旧历史与当前任务的较早片段，保存 `firstKeptEntryId`、`isSplitTurn`、`turnPrefixMessages`。
- `compact` / `generateTurnPrefixSummary`：单独生成长任务前段摘要，保留最近工作以继续任务。

应复用的是这些已存在的代码和测试，而不只是摘要提示词。其 token 估算依然是启发式，不能对任意服务的未知硬限制承诺精确。

[上游测试](https://github.com/badlogic/pi-mono/blob/7d8ab31a477ecc07b36f56ffcae58c79307a68be/packages/coding-agent/test/compaction.test.ts) 以及 `agent-session-compaction.test.ts`、`compaction-serialization.test.ts` 可作为行为对照。尚未在本项目运行或集成这些测试。

### 对照：Gemini CLI 对过大工具结果的处理

固定提交：`google-gemini/gemini-cli@85aca163f6c73ac6ce380b5447359146b8adcae4`。

[chatCompressionService.ts](https://github.com/google-gemini/gemini-cli/blob/85aca163f6c73ac6ce380b5447359146b8adcae4/packages/core/src/context/chatCompressionService.ts) 实际包含：旧工具结果预算、将大结果保存到临时文件并以可读取引用替代、为摘要选择能容纳的历史表示，以及压缩后重新计数并拒绝比原历史更大的摘要结果。这里是兼容能力的对照，不决定再拼装第二套压缩引擎。

## 对小万的集成方向与明确障碍

以 Pi 作为首选复用对象，先用原模块验证“只有一条用户任务、很多工具结果”的场景和原上游用例，再确定 Android 集成方式。业务所有权保持现有 `AgentOrchestrator`、ACP prompt、Conversation 原始历史与检查点。

具体接入位置是每次模型请求前的上下文维护入口，以及现有摘要检查点的保存/恢复入口。用上游保留边界替换“只在最后用户消息前截断”的限制；当前任务的已完成片段可摘要，最近片段与工具配对保留。恢复后不能重新执行已完成工具。

直接依赖尚有真实障碍：Pi 代码是 TypeScript，并依赖 Pi 消息类型、SessionEntry/session-manager 和 pi-ai；小万当前 loop 是 Android/Kotlin。尚未证明可以不增加运行时负担地直接调用，不能说成只换一个 SDK 就完成。

下一实现步骤应先验证原模块的独立运行与所需边界。如果只能做 Kotlin 移植，必须明确标为上游移植，保留版本、许可证、原测试输入及差分验证方案，并说明无法直接复用的原因；不能借“参考 Pi”重新设计算法。不会为了调用压缩模块启动 Pi 的另一套 Agent loop，也不会照搬与项目 ACP 终态/重试规则冲突的恢复流程。

状态：上游代码与历史核对完成；生产集成未完成，超限仍未修复，待真机验证。
