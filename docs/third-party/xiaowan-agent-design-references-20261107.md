# 小万 Agent 执行设计参考（2026-11-07）

本次是对现有 Kotlin / Flutter ACP 实现的改进，未复制或引入外部 Agent 的业务生命周期、运行时或依赖。

## 已核对的一手来源

- [DeepSeek Harness Agent API](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/agent/README.md)，读取 blob `c84ac3d2374754d8dfd4f85546cb43cd8a43d5c7`。区分下一回合 follow-up 与下一模型步骤 steering；以唯一消息身份管理待输入内容，取消语义明确。
- [DeepSeek Harness Agent Loop](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/agent-loop/README.md)，读取 blob `03fc5b2e162d2617f8d7fc684f9743fc87582afe`。完整工具批次交给一个执行器；受限并发、独占操作屏障、按模型声明顺序形成结果；turn 边界领取下一条普通输入，step 边界领取补充输入。
- [DeepSeek Harness Tools](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/tools.md)，读取 blob `e94206a685a49565293bb695a496adef3877dd08`。并发安全是工具注册元数据，未知操作默认独占，不能仅根据工具名称或模型要求判定安全。
- [Codex tool execution](https://github.com/openai/codex/blob/main/codex-rs/core/src/tools/parallel.rs)，读取 blob `f11e64547bf41325309b38e19f2e91deaecfeecd`。并发许可来自 router/tool capability；共享执行门控制并发和独占调用，取消归属保留。
- 本机 Codex 源码 `81a92e23b435485a5542f1d13d54ead409901ab3` 的 `codex-rs/core/src/session/turn_input.rs`、`input_queue.rs` 和 `inject.rs`：输入由现有 Session/Turn owner 判定启动、接入或拒绝；输入身份与活动回合绑定，不能通过取消重启模拟 steering。本机包含项目自有修改，不能将该提交整体声称为上游版本。

## 对本项目的约束

Conversation、ACP Session、Turn、Item 仍沿现有 owner。排队内容属于尚未提交的用户草稿；正式开始时使用 `session/prompt`。不中断补充只在协商支持的 ACP steering 扩展下接入活动 prompt，并在模型/工具步骤边界消费。正式完成依旧由 PromptResponse、错误或取消决定。

工具调度仅并发已审查的独立只读能力；写操作、共享设备/浏览器操作及未知插件工具保持独占。结果可以先后完成，但模型上下文中的 tool call/result 对应关系和声明顺序不变。

记忆改进针对上下文选择、任务延续、结果引用及最新用户约束保留。长期记忆的存储布局、公开 API、用户明确要求才写入的语义保持兼容。
