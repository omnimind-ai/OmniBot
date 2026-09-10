# 六项长上下文反馈复核

本轮沿 `Conversation -> ACP Session -> Turn -> Item` 的现有 owner 修复。
预算/压缩仍由 `AgentConversationContextCompactor` 负责，单次上下文恢复由现有
`AgentOrchestrator` 负责；未导入第二套 loop、历史来源或 ACP reducer。

| 反馈 | 核对结果与处理 | 可执行验证 |
| --- | --- | --- |
| 1. 摘要自己超限 | 确认用户触发阈值曾同时限制摘要输入。已改用已知摘要模型容量；容量未知时保守沿用有效预算。真正无法容纳的摘要输入仍在联网前报错，不丢历史、不提交半截摘要。未实现新的分块摘要算法。 | `lowUserTriggerDoesNotBecomeSummaryModelCapacity`、`irreducibleSchemaAndOversizedSummaryInputFailBeforeSummaryNetworkRequest` |
| 2. 工具 schema 过大 | 摘要无法安全缩短 schema。固定开销检查已移至用量快速返回前，防止过小用量提示绕过检查；明确要求减少启用工具。未静默删改工具参数。 | 同上 schema 测试，传入低用量提示和超大固定开销 |
| 3. 图片/浏览器/VLM 大结果 | 连续复测确认另一条入口：长历史恢复先累计全部旧工具 payload，再由 Gson 构造 replay JSON，在 192 MB Java 堆触发 OOM，尚未到自动压缩。现将已有 Gemini 50k 共享工具预算前移到数据库逐页读取，完整记录原样原子落盘，以引用构造请求投影；不更新原始历史，不改用户消息。大结果字段流式跳过，避免解析后再丢弃。历史图片完整记录可通过引用取回，当次原图读取保持不变。ACP 内部服务 fatal failure 同时通知既有 exit owner，避免永远等待。 | `AgentHistoryToolOutputProjectionTest`（120 个大结果、共享预算、调用配对、检查点和 I/O 失败）；`XiaowanAcpConnectionTest` 实际 server scope 故障；原图/文件 UI journey。任意多图、VLM 真机尚未验收 |
| 4. 应用查询完整结果 | 现有测试明确要求不加宿主结果数量上限。保留该契约，大结果由统一工具预算落盘处理；不为本次故障私自加分页/改名。 | `aggregateToolBudgetDoesNotDependOnToolNamesOrFileExtensions` 覆盖 `context_apps_query`、`browser_use`、`vlm_task`、文件和自定义工具 |
| 5. 超限错误识别 | 宽泛的 `input length`/`maximum length` 匹配存在误判，并漏掉部分服务商。复用固定 Pi 版本错误/排除模式，加两条用户报错样本；区分限流、鉴权、服务端错误。用显式 `force` 替代 `Int.MAX_VALUE` 假用量。 | `AgentContextOverflowTest`；生产压缩器的超限恢复、再次拒绝和已开始输出测试 |
| 6. 最终回复丢失 | 历史丢失的诊断不成立，但后续复测确认实时界面可能停在旧工具卡。两次旧测试与新失败轮的最终回复都在数据库、状态 `end_turn`。确认两处错误：页面在 metadata/history I/O 前复制 runtime 后再安装旧副本；协调器接收带运行标记的快照时清空已绑定 ACP turn 身份。已改为 I/O 后读取当前 owner，已绑定任务的快照只补缺失项、保留 reducer 内容和身份。未另建投影路径。 | 两个异步加载回归；`a snapshot with running flags cannot clear the admitted ACP identity`；UI `reply` 与 `turn-outcome` |

上游为 Pi `7d8ab31a477ecc07b36f56ffcae58c79307a68be`（MIT）与 Gemini CLI
`85aca163f6c73ac6ce380b5447359146b8adcae4`（Apache-2.0）；接口、移植范围和许可证见
`../third-party/compaction.md`。采用现有 Kotlin 移植与最小宿主修正，不声称直接运行 TS 包。

## 执行结果

- 91 项 JVM 测试通过；APK 构建通过。执行：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon \
  :app:testDevelopStandardDebugUnitTest --tests '*AgentContext*Test' \
  --tests '*AgentConversationContextCompactorTest' --tests '*AgentOrchestratorTest' \
  --tests '*ToolImageAcpPayloadTest' --tests '*AgentEventAdapterTest' \
  :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
```

- 设备：`emulator-5560` / `OobCleanInstall20260907`，v0.6.2.1（13），覆盖安装，未清除数据。
- 初次 APK SHA-256 `6c6341a69a0a9d2fa06bcd2a151171469579a2dcd560677182b044e7ebe40b28`。
  长上下文 9 步、文件 16 步均通过：两轮各 20 次读取，中间重启；HTML 重复读取、17 MB PNG、二进制 PDF、取消后继续文本、再次重启。
  长上下文测试 42 次模型请求的最大请求体为 434,368 字节；原图请求体为 24,275,172 字节，模型侧校验原图 SHA-256 一致。
- 中间保守回退修订 APK SHA-256 `6184085676dba2aa418cf0864e84df04d2699a9d0e25d1ab649c6784bbf5e401`。
  相同 91 项测试和构建通过，但重新执行 UI journey 的第 2 步失败；该轮数据库断言通过，41 个记录、唯一完成回复。失败证据保存在 `artifacts/context-six-20260908/display-failure/result.json`，不以数据库成功代替界面验收。
- 第一轮界面竞态修复：metadata 延迟测试先失败，修复后 126 项 Flutter 测试通过，但 APK `a34e0ccaa088e1da3d4d865e1269077dbae0835b30a3041051e22d932564d8c2` 的实时回复仍失败，记录保留在 `artifacts/context-six-20260908/partial-ui-fix/result.json`。
- 扩展到已绑定 ACP 身份保护后，320 项 Flutter 回归通过（原 3 组加完整 Agent reducer），构建通过。中间 APK SHA-256 `d1ac00c62eebf6c6de54402c4a827a21c1844df9d4a2a0c81ec332bbe440e1e5`，界面结果见 `artifacts/context-six-20260908/final/result.json`。Dart 分文件静态分析无错误；既有代码有 7 条 warning（含 extension 中的 notifyListeners）和 3 条 info。`flutter analyze` 多文件入口本轮崩溃，未冒充成功。

```sh
cd ui
flutter test test/features/home/pages/chat/conversation_manager_lifecycle_test.dart \
  test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart \
  test/features/home/pages/chat/chat_page_models_test.dart \
  test/services/agent_event_reducer_test.dart --reporter expanded
```
- 控制服务端为既有 `scripts/fixtures/file-read-provider.mjs`，UI 入口为
  `node scripts/verify-agent-user-journey.mjs emulator-5560 scripts/fixtures/agent-user-journeys/xiaowan-context-overflow.en.json <evidence-dir>`。
  文件入口换为 `xiaowan-file-read-regression.en.json`。fixture 样本准备见 `file-read-memory-2026-09-07.md`。
- 后续复测说明：上文 `d1ac...` 是中间 APK，9 步通过后继续文件 journey 暴露历史恢复 OOM，不能用这次通过掩盖失败。原始证据 `artifacts/context-six-20260908/history-oom/native-error.log`，堆上限 201,326,592 字节；栈为 `buildCompactToolReplayContent -> buildPromptSeedFromEntries -> buildPromptSeed`，未发出 HTTP 请求。保留会话 15 的全部历史继续复测，未清数据。
- 历史入口和异常收尾修复后，168 项 JVM 测试通过，APK 构建通过；SHA-256 `72486e22a3d670fd93b9b2ef6977e5618e871e51f178f3e385bedc4a80ee95e0`。补充运行 `*AgentHistoryToolOutputProjectionTest`、`*AgentConversationHistorySupportTest`、`*XiaowanAcpConnectionTest`、`*LocalAcpRuntimeTest`。
- fixture 新增 `OOB_FILE_TEST_SERIAL=emulator-5560`：通过 ADB 读取实际 offload 文件并验证完整分页内容，不把引用视为自动成功。此前 fixture 仅跳过 CONTEXT 引用、HTML 引用报 500，已移除该测试盲区。长上下文 journey 扩展为 17 步，增加不重启的第三个长任务、随后普通文本和重启恢复。
- APK `72486...` 的 16 步文件和 17 步长任务测试均通过，分别见 `restored-files` 和 `restored-context`。同一任务真正增长助手内容的 `xiaowan-context-summary.en.json` 第一次却在检查点断言失败：第 11 次读取后实际完成摘要、继续到第 20 次且唯一 `end_turn`，但当时 ACP 工具投影未落盘，旧查找返回 null 后没有再提交摘要。见 `checkpoint-lag-failure/result.json`，不能把前两组通过当成完整压缩验收。
- 修复检查点提交屏障：订阅同一 Conversation 的 Room 工具记录变化，匹配已完成的完整工具组后再 CAS 提交；只查轻量 header 与匹配记录。运行中占位记录不能充当已完成结果。30 秒仍不具备条件则失败，不假称持久化成功。取消沿原协程生命周期传播，没有重放模型或工具请求。新增 JVM 延迟提交回归；最终 169 项 JVM 测试通过，APK `bd166a82894f974df65f0bda0e6b95044894232bc05a3f79e5f83c4d64b47486` 构建并覆盖安装。
- `bd166...` 仍在连续大历史测试暴露显示/保存入口 OOM，证据 `display-history-oom/native-error.log`。只修请求恢复不够：`listConversationMessages` 曾积累全部原始记录后再转换，UI 快照合并也同时保留原文与解码后的映射。现完整列表按逻辑页生成显示数据，普通快照逐条读取合并；已有完整工具 payload 直接复用原始 JSON，不反复解析/序列化。显式删除历史分支未作本轮行为重构。
- 完整修订 APK SHA-256 `580f93ba9bdfe4fd2d73f6dd441e18e23b5f029c45941252ffe2485f24973d5e`，169 项 JVM 测试和构建再次通过。真实自动摘要 7 步通过：第 8 次读取后摘要请求 379,394 字符，继续完成第 20 次；唯一结束记录与重启后的摘要 hash、cutoff、revision 一致。证据 `complete-summary/`。同一 Conversation 15 保留此前累积历史；完成后一次 meminfo 采样 Java Heap 45,784 KB，此为采样值而非峰值保证。
- 该最终 APK 上再次执行文件 16 步、连续长上下文 17 步，全部通过，见 `complete-files/`、`complete-context/` 与 `complete.png`。最终三组共 102 次经过校验的普通模型请求、1 次实际摘要请求；连续长任务最大请求体 412,394 字节，原图最大请求体 24,114,924 字节，原图 hash 一致。`complete-provider.jsonl` 保存实际服务端证据；20:32 起日志未匹配到 fatal/OOM，结果见 `complete-log-check.json`。完成后恢复模拟器原有 `debug-llmthu-glm` / `GLM-5.1` 绑定并关闭测试服务端。
- 本轮没有连接实体手机，**待真机验证**。原报错服务商的实际长度单位、任意多图的请求字节限制、摘要语义保真度不能由受控服务端成功推断。没有发布新版本。
