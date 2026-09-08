# 小万上下文、历史与展示的数据边界

状态：上一轮定向测试及模拟器回归通过；后续审计发现未覆盖的问题，见 `context-audit-2026-09-08.md`，新增测试当前失败，仍待真机验证。实体手机规则不以模拟器、单测或构建替代。

## 本质与范围

本轮不是替换 ACP 或再引入一个 Agent loop。现有 Conversation → ACP Session → Turn → Item 保持不变。
问题是把用户配置当 usage、把模型请求上下文当完整历史、把 UI 部分投影当可覆盖完整历史的快照。每个写入口必须有明确的所有者。

- 模型目录只提供能力，不写用户阈值；用户阈值按 conversation 保存，不再改写模型 contextLimit。
- usage 只写观测数据；数据库列级写入保护阈值，并拒绝比已记录时间更旧的 usage。通用历史快照不能覆盖用户阈值。
- 每次小万模型请求前执行已有 Pi/Gemini 移植模块的上下文维护；调用方预算不能扩大模型能力，普通请求与摘要请求都有显式输出额度。
- 摘要必须有正常完成证据，空、截断、提前断开的摘要不能成为检查点。
- 模型恢复在 SQL 中排除已压缩部分，再读取载荷。旧模式重复身份不能因筛选而复活。
- UI 分页在 SQL 中先按 canonical/legacy 身份去重，再分页，仅还原本页载荷；完整历史仍可访问。
- 常规 UI 状态保存按消息身份更新，不删除未出现在页面上的历史，不接受空状态覆盖。明确的用户历史编辑通过 allowHistoryRemoval 传达移除意图，不由“快照为空”推断清空。
- 复用既有文件流式读取、文本分页、图片附件、工具结果文件化及请求编码。上游版本和许可证见 ../third-party/compaction.md。

## 长期可执行入口

`scripts/test-context-boundaries.sh`：原生预算/压缩/loop/历史/文件/图片/请求编码测试，以及 Flutter 共享 reducer、协调器和架构约束测试。

Android 实际 SQLite/CursorWindow 测试：构建 `:baselib:assembleDebugAndroidTest`，安装到隔离设备后运行：

```bash
adb -s SERIAL shell am instrument -w \
  -e class cn.com.omnimind.baselib.database.ConversationCheckpointTest,cn.com.omnimind.baselib.database.LargeConversationEntryTest \
  cn.com.omnimind.baselib.test/androidx.test.runner.AndroidJUnitRunner
```

UI 入口复用 `scripts/verify-agent-user-journey.mjs`：
- `scripts/fixtures/agent-user-journeys/xiaowan-context-overflow.en.json`：连续读取、重启、再次读取。
- 同一 `file-read-provider.mjs` 已支持真实摘要 HTTP 请求的受控响应，并在后续请求中验证摘要保存的已读页数；不另建测试后端。这不代表实际模型摘要质量验收。

## 本轮明确失败的证据

- 阈值回归先红：保存 64000，接收容量 32000 的压缩状态，旧逻辑把配置变为 32000。
- 常规持久化空状态回归先红：实际发出 `replaceConversationMessages(messages: [])`。
- `artifacts/context-boundaries-2026-09-07/long-context/`：输入浮层吞掉一次发送点击；草稿没有被提交，人工关闭浮层后只发送一次并完成 20 页。
- `long-context-rerun/`：发送与 20 页读取通过，但重启后历史为空，验收失败。这个失败促使修复普通状态保存的整表覆盖语义；不能把失败报告当通过。

测试辅助脚本现于发送前关闭输入浮层，重新检查草稿和按钮边界，仅点击一次发送，没有自动重发。

- `history-owner-fixed/`：读取通过，重启后人工核对新结果仍在；runner 保存 PNG 超过 Node 默认 1 MiB 输出限制报 ENOBUFS。现只提高测试进程的截图缓冲区至 32 MiB，重跑保留独立证据。

- `history-owner-final/`：重启与恢复断言通过，第二轮 20 页最终完成，但超过 180 秒测试上限，整轮仍为失败。随后把常规快照改为只提交变化消息：保存成功后记录 SHA-256 写入摘要，失败不确认，明确清空会失效这些摘要。它只减少重复写入，不参与读取或重建历史。使用依赖图中已有的 crypto 3.0.7，并显式声明直接依赖。

新增持久化回归验证：大旧消息不随新回复重复提交、相同快照不重写、失败后同一内容仍会提交、明确清空后可以重新写入、普通快照无删除权限。测试中旧分页样本改用明确不同的消息 ID，避免同一毫秒创建的测试消息错误地共享身份。

- `incremental-final/`：历史恢复通过，后续真实摘要请求被旧测试后端拒绝（Explicit fixture marker required / HTTP 500）。已为既有文件测试后端补充摘要请求和进度检查后重跑；没有为适应夹具修改生产模型请求。

## 已执行结果（2026-09-08 收尾）

- 原生定向测试：139 项，0 失败（后续恢复预览回归新增 1 项，见最终结果）；Flutter 定向测试 336 项通过，包含历史写入服务、共享 reducer、协调器和架构约束。
- Android 13 ARM64 隔离模拟器 `emulator-5560` / `OobCleanInstall20260907`：6 项真实 SQLite/CursorWindow instrumentation 测试通过，包括数据库重开、延迟 usage、用户预算保护、去重分页、大记录分块读取和检查点旧模式身份。
- `complete-flow/result.json` 六步全通过：20 页读取、重启、恢复历史、再次 20 页读取。测试 Provider 共收到 42 次普通模型请求和 2 次实际摘要 HTTP 请求，0 错误；最大序列化 messages 长度 386300 字符。单位是夹具的序列化字符，不是原服务商报错单位的推断。
- `threshold/result.json` 17 步全通过：实际长按入口，32k → 64k，重新选择 gpt-4o 后仍 64k，重启后仍 64k。最后恢复测试配置 128k。
- 实时保存失败现在抛出错误并保留当前状态，不转写另一份旧存储。旧存储只用于读取/导入兼容。明确清空和保存复用同一写入队列，避免较早的保存晚于清空完成。

## 验收边界

所有模型响应来自受控本机 Provider。实际摘要的 HTTP、预算、进度延续和历史恢复经过 App 路径，但没有真实外部模型的摘要质量验收。未连接实体手机，全部修复仍**待真机验证**。启发式 token 估计不能等同于任意服务商精确 tokenizer 或未知字节/字符上限。

## 最终包复测发现的额外边界

`final-apk-flow/result.json` 保留失败结果：恢复已经累计 120 余条工具结果的历史后，摘要请求预算检查拒绝执行。原始历史仍在，没有发送超限请求。根因是已经文件化的旧结果仍逐条附带 4,000 字符预览，预览总量没有共享上限。新增 `restoredLongHistoryDoesNotAccumulateOffloadedExcerpts` 回归，先在旧实现复现相同 IllegalStateException，再移除旧结果的重复尾部预览；完整内容保存在原文件中，最近输出继续使用既有共享预算。上游移植差异同步记录于 `docs/third-party/compaction.md`。

`final-apk-threshold/result.json` 的 17 步通过；这一包随后只修改上述原生工具结果引用逻辑。最终包读取复测另存，不覆盖失败证据。

最终统一入口 `scripts/test-context-boundaries.sh` 执行通过：140 项原生测试、336 项 Flutter 测试，0 失败。新增恢复预览测试先失败后通过。最后构建成功，APK SHA-256：`b696b3c2536e23104a5000d0696c7ad56d95badb284a8a565059506b1ebd8407`。包内版本 `0.6.1` / code `11`（本轮调试产物，不能用版本号区分不同构建）。

`final-history-recovery/` 第一轮 20 页、重启和历史恢复通过，第二轮第 16 页后 Android 192 MiB Java heap 内存不足，整轮失败。保留 `restored-history-oom.log`；栈最后落在 Provider 配置读取是分配失败位置，不能据此认定加密存储是根因。检查到 loop 的 Input 持有原始大历史，压缩替换 memory 后该旧历史仍由 Input 保留；新增 WeakReference 回归验证真正释放旧消息对象。

内存引用回归 `compactedInitialHistoryIsReleasedWhileInputOwnerRemainsAlive` 在旧实现失败，提示旧历史仍由 Input 保留；改为 Input 只持有既有 AgentChatMemory、由其替换上下文，并把初始历史构建放到交接处，避免执行器局部变量继续持有旧列表。更新后统一入口 141 项原生、336 项 Flutter 测试全部通过。没有新建 loop、重试或历史来源。

## 最终交付状态

最后安装包 SHA-256：`4313967fd0041317238193aaf07d3d5409ef6b2612ba573aca787d05e15a0110`，与模拟器安装的 base.apk 校验一致。前文其他哈希对应中间失败版本，不能代替此包。

- 最终 `final-memory-recovery/result.json` 六步全部通过，runId `1788800634327`：在原失败历史上继续 20 页，重启并恢复，再读 20 页，无清空历史或更换新会话。此轮 42 次受控模型请求，最大序列化 messages 355472 字符，读取内容和分页位置均由夹具校验。此轮旧工具结果文件化后足以落入预算，未发生摘要 HTTP 请求；摘要 HTTP 路径的模拟器证据仍是前文 `complete-flow` 的 2 次，最终摘要单测继续通过。
- 最终测试 141 项原生、336 项 Flutter 全通过；6 项 Android SQLite instrumentation 通过。阈值 17 步界面测试通过，之后的原生修改不涉及阈值界面或保存逻辑。
- 可执行入口、原生测试 XML、测试日志、SQLite 输出、UI 截图和 Provider 请求尺寸记录均保存在本报告相邻 artifacts 中。摘要输出来自受控夹具，不代表真实模型摘要质量通过。
- 所有修改仍待实体手机执行实际操作、重复读取及重启恢复验收。不得标记为彻底修复或真机验收完成。
