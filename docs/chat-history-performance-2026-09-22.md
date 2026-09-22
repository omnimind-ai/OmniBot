# 聊天数据职责迁移与长记录性能

相对基线 `92af786b7`，生产 Dart 代码净减少 **3,223 行**，生产 Kotlin 代码净增加 **1,173 行**。统计包含本次新增文件，不包含测试、文档、生成文件；Dart 减量包括职责迁移与删除已失效的重复实现。

现有气泡、Markdown、头像、工具卡片、折叠、动画及滚动控件继续由 Flutter 渲染。Kotlin 负责历史导入与兼容解码、Room 查询、会话管理、持久化选择、导出和链接数据服务。Flutter 保留显示模型、现有 ACP 视图投影、交互状态、行级通知，以及把变化消息提交给原生的通道适配。没有添加第二套 ACP reducer、事件流、重试或终态协议。

## 已迁移的职责

| 职责 | Kotlin 实现 | Flutter 变化 |
| --- | --- | --- |
| 旧历史恢复、JSON 帧与字段兼容、占位过滤 | `LegacyConversationHistory`、`AgentConversationHistoryRepository` | 删除历史服务和消息模型中的旧解析代码 |
| 远程桥接历史解包、去重合并、命令输出解码、图片附件解析 | `RemoteHistoryCompatibility`、`RemoteHistoryUserContent` | 删除 `remote_codex_history_items.dart` 和图片内容解析器；接收规范化的 items |
| 会话列表排序、筛选、分页、最新会话读取 | `ConversationDao.getDisplayPage`、`ConversationDomainService` | 不再全量读取后在 Dart 排序或切片 |
| 会话重命名、归档、恢复、删除/隐藏的协调 | `ConversationUiActions` | 界面提交动作，由现有原生 ACP owner 和历史服务处理 |
| 当前/最近线程选择、旧隐藏列表兼容、跨引擎读取 | `ChatConversationPreferences` | 删除 Dart SharedPreferences 数据管理 |
| 复制与 JSON 导出 | `ConversationTranscript` | 原生生成文本，Flutter 调用现有复制/分享交互；不再为导出构建整段显示模型 |
| 链接识别、HTML 元数据、HTTP、缓存及并发请求合并 | `ChatLinkPreviewRepository` | 只接收加载中/成功/失败数据并更新原有卡片 |

远程历史兼容处理仅在已有 `session/load` / `session/resume` 返回边界运行，不消费或重写实时 `session/update`，不以历史字段判定活跃回合。删除了无人调用的旧工具 reducer 辅助文件及对应重复处理，实时 UI 仍使用原有 `AgentEventReducer`。

## 长记录处理的实际变化

- Room 18 → 19 增加 `(conversationId, createdAt, id)` 索引，原有记录保留。逻辑消息按身份在 SQL 中去重后分页，避免先搬运全部内容再切片。
- 旧 SharedPreferences 快照在原生后台导入；已有 Room 记录优先。只有事务成功后才删除对应旧快照，导入与显式清理串行，损坏或写入失败的数据保持可恢复。
- 分页游标 `nextOffset` 按消耗的存储行推进，不受空消息过滤影响。
- 显示模型的嵌套数据不可变，已提交对象只保留弱引用。流式更新仅序列化和传输变化行，删除全历史 JSON 编码及 SHA-256 摘要计算。失败提交不确认，后续可重试。
- 时间线按 run ID 一次建索引；消息监听和列表 key 查找复用索引。远程历史不再为了 `messageCount` 再次完整解析一遍。
- 陈旧 UI 快照更新元数据时，置顶、归档、自定义标题等在 DAO 事务内保留最新值，避免读写间隙覆盖用户操作。
- 链接异步回填核对当前线程/模式和消息对象；新对话从临时状态取得原生 ID 时仍能完成预览，导航或流式更新不会被旧结果覆盖。

## 本地测量

第一轮时间线测量使用相同消息对象，对基线和修改后的真实 `buildAgentRunTimelineEntries` 各执行 5 次，取中位数，并检查条目顺序。环境为 macOS、Flutter 3.47.2 所带 Dart，JIT 模式。

| 样本 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 1,000 轮文本时间线分组 | 24.929 ms | 3.190 ms |
| 5,000 轮文本时间线分组 | 559.048 ms | 4.288 ms |
| 10,000 轮文本时间线分组 | 1,954.606 ms | 7.477 ms |
| SQLite 首次读取 51 条（本轮复测） | 56.04 ms | 0.21 ms |

新增写入工作量测试：10,000 条消息，每条 2 KiB，首次提交后连续更新 20 次；实际只再次调用 **20 次** `toJson` 并发送 **20 条**变化消息。定向测试耗时约 30 ms，最后一次全套测试并行运行时 41 ms；耗时不作为易受机器状态影响的断言。原实现每次保存会对全部 10,000 行重新序列化并计算摘要。

SQLite 测试执行生产 DAO 的 SQL 和实际迁移索引语句，每个会话含 10,000 个逻辑 ID、三份兼容模式记录，另有其他会话和隐藏事件。验证全部 200 页无遗漏、无重复，查询计划不再使用临时排序树；另外验证会话列表筛选先于分页、模式别名和排序顺序。

以上是本地逻辑、数据处理与 widget 验证。尚未测 Android 真机首帧、帧率、内存峰值或逐像素截图一致性；没有构建或安装 APK。

## 验证

- Kotlin JVM：185 项测试通过，覆盖新增服务、历史仓库、兼容处理、顺序、ACP 请求边界和会话创建。
- Flutter：全量 1,245 项测试通过，包含页面、侧栏、消息卡片、ACP 生命周期、原生服务边界及新对话链接预览的异步回填。
- 远程历史：9 组迁移前 Dart 输出固化为共享 fixture，由 Kotlin 验证导入结果，由 Flutter 继续验证对应卡片与文本展示。
- SQLite：3 项测试通过。
- 现有 Node 历史分页终态展示合约测试通过。
- Flutter analyze 无 error；项目仍有 warning/info，未扩大修改范围处理无关诊断。
- `git diff --check` 通过。

复测命令：

```bash
cd ui
flutter test --no-pub
flutter analyze --no-pub --no-fatal-warnings --no-fatal-infos
cd ..
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest \
  --tests 'cn.com.omnimind.bot.agent.ChatLinkPreviewRepositoryTest' \
  --tests 'cn.com.omnimind.bot.agent.ChatConversationPreferencesTest' \
  --tests 'cn.com.omnimind.bot.agent.ConversationUiActionsTest' \
  --tests 'cn.com.omnimind.bot.agent.runtime.RemoteHistoryCompatibilityTest' \
  --tests 'cn.com.omnimind.bot.agent.LegacyConversationHistoryTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentConversationHistoryRepositoryTest' \
  --tests 'cn.com.omnimind.bot.agent.AgentConversationHistorySupportTest' \
  --tests 'cn.com.omnimind.bot.agent.ConversationSnapshotOrderingTest' \
  --tests 'cn.com.omnimind.bot.agent.runtime.AcpLegacyCompatibilityAdapterTest' \
  --tests 'cn.com.omnimind.bot.agent.runtime.AgentRuntimeProtocolPayloadTest' \
  --tests 'cn.com.omnimind.bot.webchat.WebConversationCreationTest'
python3 scripts/test-chat-history-query.py
node --test --test-name-pattern='every persisted history page' scripts/agent_runtime_capability_contract.test.mjs
```
