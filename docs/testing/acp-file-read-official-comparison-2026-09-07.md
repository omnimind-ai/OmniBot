# ACP 文件读取官方对照与当前覆盖范围

后续范围更新：用户明确要求优先处理小万，其他 Harness 内部 loop 和文件策略由各自负责。小万公共模型结果与 ACP 展示结果的去重已另行实现，见 [小万结果去重](xiaowan-tool-result-dedup-2026-09-07.md)。以下为本次审计时的记录，外部 ACP 文件回调等风险不作为这轮小万修复的扩展任务。

## 结论

已修复并在隔离模拟器验证图片 `file_read` 的已复现 OOM/闪退路径，以及通用文本 `file_read` 的全文返回 OOM。文本修复按内容类型生效，覆盖 HTML、TXT、Markdown、JSON、CSV、日志、源码等走同一处理器的文件，不是按 `.html` 后缀单独修补。

不能宣称全部文件读取入口已修好。外部 Agent 的 ACP 文件系统回调、编辑器、搜索/编辑和公共工具结果重复包装尚有独立覆盖缺口。小文件通常正常，风险随内容大小、并行读取和已有历史增长；不是所有文件都会失败。

## 证实的本质

大内容先完整驻留内存，再以多个字符串/JSON 字段重复进入模型结果、ACP UI 投影和持久化。文件大小不等于峰值内存。已复现约 16 MiB HTML 在 JSON 编码中申请约 128 MiB 失败；图片另有 Flutter MethodChannel 主线程解码 OOM 导致进程退出。

共同责任边界是文件内容获取、模型输入、UI 预览和历史存储之间的数据分配。此次 ACP 中断是 OOM 的后果，没有证据需要新建会话/turn 生命周期来修复。

具体残留：

- `agent/runtime/AgentEventAdapter.kt::toolResultContent`：模型工具结果仍可同时包含 previewJson、rawResultJson 两份相同正文。
- `agent/XiaowanAcpConnection.kt::toolResultAcpPayload`：ContextResult/McpResult 等仍可包含 previewJson、rawResultJson、result、rawResult，序列化时同一内容可出现四份。上次图片修复避免本地原图 Base64 进入这些 UI 字段；文本分页控制每次读取量，但未消除公共字段重复。
- `agent/runtime/LocalAcpRuntime.kt::fsReadTextFile`：无 line/limit 时 `file.readText()`；有范围时 useLines 仍可分配完整超长单行。该入口没有采用小万 `file_read` 的分页辅助函数。这是源码确认的内存风险，尚未在该 ACP 入口单独复现 OOM。
- `FileToolHandler.file_edit` 仍全文读写，file_search 使用整行读取，file_list/search 默认结果数量可不受限。它们不是上次 file_read 分页测试的覆盖对象。
- Flutter 文本编辑器全文加载、PDF 极端长宽比页面渲染风险见 [文件读取报告](file-read-memory-2026-09-07.md)。

## ACP 规范

核对日期 2026-09-07，v1 文档：

1. [File System](https://agentclientprotocol.com/protocol/v1/file-system)：`fs/read_text_file` 是文本接口，支持可选的 1-based line 和最大行数 limit；响应为 content。协议没有规定统一 64 KiB 上限或 nextOffset 分页字段。小万工具的字符分页属于工具契约，不应直接套入标准 ACP 响应、悄悄返回缺失正文。
2. [Content](https://agentclientprotocol.com/protocol/v1/content)：区分 text、image、audio、embedded resource、resource_link。图像块的 data 是必需 Base64，可选 uri 不是 data 的替代字段；resource_link 适用于接收方可访问的资源。跨设备不能仅传本机绝对路径就假设 Agent 能读取。
3. [Tool Calls](https://agentclientprotocol.com/protocol/v1/tool-calls)：content、locations、rawInput/rawOutput 用于工具报告。规范不要求我们自定义的 previewJson/rawResultJson/result/rawResult 四套重复正文；更新只需发送变化字段。

## 具体实现对照

### Google 官方 Gemini CLI

固定源码版本 `85aca163f6c73ac6ce380b5447359146b8adcae4`：

- [constants.ts](https://github.com/google-gemini/gemini-cli/blob/85aca163f6c73ac6ce380b5447359146b8adcae4/packages/core/src/utils/constants.ts#L10)：默认文本 2000 行、单行 2000 字符、文件大小 20 MiB 上限。这些是 Gemini 的工具策略，不是 ACP 标准。
- [fileUtils.ts](https://github.com/google-gemini/gemini-cli/blob/85aca163f6c73ac6ce380b5447359146b8adcae4/packages/core/src/utils/fileUtils.ts#L492)：先检查文件大小、检测类型；文本有范围和截断标志，二进制跳过；支持的媒体以 inlineData 给模型，returnDisplay 使用文件说明。模型内容与界面文字分别返回。
- 同一文件的文本实现仍会先整份读取再 split，因此不能说官方实现全都是流式读取，或原样照搬就适合 Android 小堆。
- [acpFileSystemService.ts](https://github.com/google-gemini/gemini-cli/blob/85aca163f6c73ac6ce380b5447359146b8adcae4/packages/cli/src/acp/acpFileSystemService.ts#L51)：ACP 回调调用也可能不传 line/limit。不能假设外部 Agent 总会主动分页。

### ACP 组织维护的 Claude 适配器

这是 `agentclientprotocol/claude-agent-acp`，不是把 Anthropic 闭源 Read 引擎说成 ACP 官方实现。固定源码版本 `3e23c5b960b66a6d2c892e7524c952e731c076a7`：

- [Read 显示转换](https://github.com/agentclientprotocol/claude-agent-acp/blob/3e23c5b960b66a6d2c892e7524c952e731c076a7/src/tools.ts#L657)：采用结构化文本结果构造用户显示内容，识别 truncatedByTokenCap 并保留截断提示。
- [媒体转换](https://github.com/agentclientprotocol/claude-agent-acp/blob/3e23c5b960b66a6d2c892e7524c952e731c076a7/src/tools.ts#L1035)：Base64 图片转为标准 ACP image；PDF document 结果给 UI 简短类型/大小说明，避免把几 MB 文档 Base64 当作显示文本。

## 后续修复应守住的边界

- 在既有工具结果和 ACP 投影边界消除重复正文，复用既有共享 reducer；不要增加新事件协议或页面专用生命周期。
- 文本工具按范围/分页返回并明确剩余内容；保留原文件。官方 ACP 回调必须保持请求范围的语义，不能把小万 nextOffset 契约强加给未知 Agent。
- 对标准 ACP 回调的大响应，需在真实 SDK 序列化链上复现并修复内存分配；无法完成请求时应通过既有 RPC 错误路径明确失败，而非截断后标记成功。具体资源策略尚未在本轮实现，不能计为通过。
- 模型媒体能力和 UI 展示分别处理，客户端可以加载本地文件预览；模型实际需要图像时仍按其能力提供图像数据。

本轮是源码/规范审计，未追加 APK 修改或设备验收；上一轮 APK、55 项测试和设备证据见 [文件读取报告](file-read-memory-2026-09-07.md)。
