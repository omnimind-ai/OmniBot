# 小万记忆读取与恢复验证

状态：代码、定向测试和模拟器实际 UI 流程通过；待真机验证。

## 当前加载语义

- Conversation 历史及已提交摘要属于模型请求的短期上下文，启动下一次小万请求时通过既有历史仓库加载。
- Workspace 的 MEMORY.md 长期记忆和 daily 日志不会整本注入缓存 system prompt；由既有 memory_search 检索两类内容，memory_load 按 slug 加载长期正文。模型从工具结果获得这些事实。
- 原提示词“只有用户明确要求持久化时才使用记忆能力”误把读取也包含在内，与按需检索规则冲突。改为写入/修改需要明确持久化意图；回答历史工作、决定、偏好、待办前先检索，必要时加载正文。未命中或不可用时不能虚构记忆。
- 继续使用既有 WorkspaceMemoryService、LongTermMemoryIndex、共享 ACP 和 Agent loop；没有新建记忆库、迁移文件或另加自动保存任务。

## 成熟实现参考

通过 GitHub CLI 核对 OpenClaw v2026.2.26（MIT，commit bc507080577c620243617e8fadd294bec3efa252）的 Memory Recall section：回答历史工作等问题前检索，再按需读取内容。参考地址：
https://github.com/openclaw/openclaw/blob/bc507080577c620243617e8fadd294bec3efa252/src/agents/system-prompt.ts#L49

这里只借鉴读取与写入授权的区分和召回时机，未引入 OpenClaw 的运行时、session 或文件布局。长期/短期正文仍走已有按需读取边界，避免重新引入无限 system prompt。

## 可执行验证

`scripts/test-context-boundaries.sh` 已包括 AgentSystemPromptTest 和 *Memory*Test，覆盖记忆索引、短期历史索引、删除、embedding 兼容及原上下文测试。

`scripts/fixtures/agent-user-journeys/xiaowan-memory-restart.en.json`：准备好发送入口 → 合成长/短期记忆写入 → 搜索并读取正文 → 重启 → 再次搜索和加载。既有 file-read-provider.mjs 增加 memory 分支，检查实际请求中的工具定义和召回规则，再对 memory_search、memory_load 的实际返回值做内容断言。Provider 仅在收到两类记忆、长期完整正文均正确时发出 DONE。

仅使用每次测试独立的 OOB_MEMORY 标记；不记录无关记忆原文。该测试能证明原生工具、存储、索引、历史和模型请求的数据通路，不能证明真实外部模型自主选择工具的质量。

首次 UI 运行 recall/ 在发送入口阶段退出，没有模型请求或新消息。保留失败证据；测试加入现有 expect 动作等待 Send 入口准备好，再在 recall-ready/ 独立执行，没有自动重发用户消息。

后续 recall-ready/ 在恢复原有长会话时耗尽 Android 192 MiB Java 堆，App 退出，模型未收到记忆请求。保留 restore-oom.log。这不能视为记忆检索通过。展示层原来逐条完整复制 raw/preview/terminal 正文；现改用既有有限预览方法，并为较大工具历史附加管理工作区中的完整 JSON 记录文件。原始数据库内容仍保留，历史预览再次保存不能改写正文；实际新工具结果仍可正常更新。新增 preview 回写与 live 更新区分测试。

## 最终验证（2026-09-08）

安装版本 0.6.1 (11) developStandardDebug，设备 emulator-5560（OobCleanInstall20260907，Android 13 ARM64，192 MiB Java 堆）。继续使用累积历史的 Conversation 7，没有清除历史或重装清数据。

- 原生定向测试 173 项、Flutter 336 项通过；数据库 instrumentation 8 项通过（在最终展示层修改之前执行，数据库代码随后未变）。
- recall-bounded：7 步通过。实际调用 memory_upsert_longterm、memory_write_daily、memory_search、memory_load；重启后再次搜索和加载，Provider 检查长期偏好、每日任务及长期正文内容均正确。
- checkpoint-bounded：11 步通过。读取 20 页 → 手动压缩 → 检查数据库 → 重启 → 核对摘要哈希、cutoff 和 revision 不变 → 再读 20 页。两段工具历史均通过 toolCallId/sessionId 保存断言。最终两组流程未重现此前恢复历史时的退出。
- 安装包与设备 base.apk SHA-256 一致：22ac0030d9e308be998cad4496c2b6f2f55b54b99e12a19b4f7f3b0d334df4c1。

证据见 [verification.json](artifacts/memory-2026-09-08/verification.json)、[记忆 UI 结果](artifacts/memory-2026-09-08/recall-bounded/result.json)、[检查点 UI 结果](artifacts/memory-2026-09-08/checkpoint-bounded/result.json) 和同目录 provider.jsonl。失败的 recall/、recall-ready/ 与 OOM 日志保留，不能算通过。此前检查点旧流程虽完成，额外审计发现展示回写丢失工具身份；最终流程已增加身份断言并通过。

受控 Provider 验证了工具、存储、恢复和请求通路，未验证真实模型自主召回行为及摘要质量。没有连接实体手机，待真机执行相同读取、重启与重复操作后验收。
