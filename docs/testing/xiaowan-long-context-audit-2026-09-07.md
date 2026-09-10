# 小万长上下文链路检查

## 范围与证据

检查时间：2026-09-07。源码基线 HEAD `64ad2b15c` 加当前工作区改动；工作区包含其他未提交工作。本轮仅增加本报告，没有修改生产实现。

用户报告最新版错误：`Input length 1119534 exceeds the maximum length 1048566`，HTTP 400；session `b5ff96a0-10f6-4787-a89f-6157fed1abdf`，turn `ac27672a-26cd-4f3c-9e9f-92da89a2f14a`。这证明服务端拒绝了超限输入，但没有请求正文、usage 与具体模型信息，不能确定该服务的长度单位，也不能断定下面哪个缺口触发了这一条请求。

范围是我们拥有的小万模型循环、历史与压缩、工具结果、子任务，以及客户端容量配置和显示。外部 ACP Agent 自己的模型循环仍由它们负责。

所有权：Conversation 的原始历史继续作为用户可见事实；`XiaowanAcpConnection` 负责 ACP prompt；`OmniAgentExecutor` 创建小万执行器；`AgentOrchestrator` 持有本轮模型消息；`AgentConversationContextCompactor` 与 `AgentConversationHistorySupport/Repository` 负责摘要与持久化检查点。修复应扩展这些入口，不新增 ACP 生命周期、自动重放或私有适配器。

## 已确认缺口

| 优先级 | 位置 | 已确认行为与后果 |
| --- | --- | --- |
| P1 | `agent/runtime/AgentOrchestrator.kt:156`、`:253`、`:692` | 首次请求直接使用 memory.snapshot；压缩只在成功响应之后调用，工具结果随后才加入消息。首次历史已超限、或工具结果使下一请求越界时，没有发送前预算检查。 |
| P1 | `agent/conversation/AgentConversationContextCompactor.kt:273` | promptTokens 为空直接返回原文。即使 contextTokens 有值也无法进入检查；不提供 usage 的服务没有本地兜底预算。 |
| P1 | `agent/conversation/AgentConversationHistorySupport.kt:483`、`:524`、`:460` | 自动运行窗口与持久化候选均以最后一条用户消息为分界，只压缩其之前的历史，重建时保留最后用户消息及其后的全部内容。单条长任务的已完成工具片段持续累积；只有一条用户消息时可能根本没有候选。 |
| P1 | `agent/conversation/AgentConversationContextCompactor.kt:391`、`:421` | 手动与自动压缩都将全部待压缩内容一次性发送给摘要模型。没有在此入口分批控制摘要请求大小，超限历史可能使压缩请求自身失败。摘要结果也没有明确的目标长度或重建后的预算校验。 |
| P1 | `agent/conversation/AgentConversationContextCompactor.kt:308`、`:327` | 自动摘要失败/空摘要返回 null，上层继续使用原始 messages；没有证明后续请求仍可容纳。取消异常正确继续抛出，没有错误提交摘要。 |
| P1 | `agent/runtime/SubagentDispatcher.kt:185` | 小万子任务复用 AgentOrchestrator，但 Input 未传 contextCompactor 且 conversationId 为 null。长子任务没有上下文压缩；不能直接借用父会话的持久化检查点修复，否则会污染父历史。 |
| P2 | `agent/runtime/AgentEventAdapter.kt:113`、`:177`；`agent/tool/handlers/TerminalToolHandler.kt:355`、`:403` | 现有去重只消除完全相同的 previewJson/rawResultJson。终端结果仍可同时带 rawResultJson 和 terminalOutput；会话读取可返回完整 transcript。模型结果入口没有统一总预算。文件分页只约束单次 file_read，不能约束多个文件、终端、MCP 与子任务结果的累计体积。 |
| P2 | `agent/conversation/AgentConversationHistoryRepository.kt:495`、`:826` | DB 分页结果会全部加入 List，之后再按摘要 cutoff 构造提示。查询分页不等于总内存有界；很长的原始历史在恢复/选择压缩候选时仍有内存成本。 |
| P2 | `ui/lib/features/home/pages/chat/chat_page_model_context.dart:764`、`:39` | 普通聊天模型信息刷新/选择路径会把 promptTokenThreshold 写为模型 contextLimit，未区分用户自定义触发值与模型硬容量。自定义较低值可能被该同步路径覆盖；此为源码确认的配置写入行为，尚未真机复现。 |

## 容量与显示检查

- 压缩器对已知模型容量和存储值取较小值，这是已有正确保护；模型容量未知时回落到 128,000，不能视为服务端实际硬上限。
- 自动触发使用容量减 reserve；reserve 最大只有 16,384。固定余量不能证明下次新增工具结果、图片及输出预算可容纳，需要结合实际待发送请求检查。
- 普通模型请求 `maxCompletionTokens = null`；压缩器调用也没有在本入口显式传入摘要输出预算。不能把当前 reserve 等同于实际模型最大输出设置。
- 图片通过结构化 image_url 进入模型消息。JSON/Base64 的字节数与视觉 token 不是同一个量，不能用统一字符串长度冒充准确 token 数。
- 已有 prompt + completion / total 计数取较大值的逻辑，且有溢出保护；这仍然只反映已经完成的请求。输入栏显示 usage 不代表下一次请求已通过预算检查。
- 没有发现需要新增生命周期才能解决这些问题的证据。HTTP 400 本身是输入被拒绝，不是图片渲染崩溃的证明。

## 建议修复顺序与验收标准

用户后续明确要求：优先复用成熟压缩实现，不自行开发。下列条目只定义应满足的行为与验收标准，不作为从零开发压缩算法的方案。选型、兼容性核对和最小集成应先于生产修改；长期回归入口见 `conversation-regression-index.md`。

1. 在现有循环的每次模型请求之前检查实际待发上下文，包括首次历史和刚加入的工具结果；明确真实 usage、估算值、模型硬容量与用户触发值的区别。工具 schema、系统提示、附件和输出余量都要计入各自预算，不把全部 JSON 字节当文本 token。
2. 扩展现有压缩窗口，使同一 turn 中已经完成、成对的工具调用及结果能形成检查点。保留当前用户请求、未完成调用和必要继续执行状态；持久化恢复也应使用对应检查点，原始聊天记录不删除，不重放工具。
3. 摘要请求自身按预算分批处理，限制摘要目标大小并复核重建上下文。压缩失败时不能把已确认超限的原文继续发送，也不能静默丢内容；使用当前 ACP 请求的错误/取消路径。
4. 把相同模型预算能力用于小万子任务的运行内存，保持父子上下文隔离；终端/MCP 等大结果通过已有工具/文件能力保留可取回正文。不要按工具名称堆特例。
5. 分离模型容量与用户阈值的配置语义，并优化摘要 cutoff 后的历史查询；不改变完整聊天历史的保留规则。

应补的测试：无 usage、恢复即超限、单个工具结果突然放大、同一用户任务多次工具累计、并行工具累计、摘要请求也超限、摘要过长/失败/取消、模型切换到更小容量、子任务独立预算、压缩后重启与继续工作。每项需检查完整工具配对、当前请求保留、历史完整、无重复执行；真实服务的最终验收还需确认长度单位和有效容量。

## 本轮验证

执行：

```sh
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest \
  --tests 'cn.com.omnimind.bot.agent.AgentConversationContextCompactorTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentConversationHistorySupportTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentOrchestratorTest'
```

结果：106 项通过（8 + 39 + 59），0 失败。它们证明已有行为的回归检查通过，不证明上述超限缺口已修复。

此前图片/文件模拟器用例验证了读取、原图哈希、显示和生命周期；受控 Provider 没有模拟这次服务的硬输入上限，因此不构成长上下文防溢出验收。

本轮 `adb devices -l` 没有连接设备。状态：**源码检查完成，缺口尚未修复，待真机验证**。
