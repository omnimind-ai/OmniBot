# 删除与迟到快照审计

本轮诊断，产品代码未修改。生产 coordinator 接口已复现：用户显式移除消息成功后，删除前的快照经 replaceConversationSnapshot 或 persistConversationMessageSnapshot 返回，移除项再次出现。后者还进入持久化调用路径。原因是合并逻辑把当前不存在的 id 当作新增，没有区分明确删除与从未加载过。

可执行入口：
```sh
cd ui
/tmp/oob-flutter-3.47.2/bin/flutter test --no-pub test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart --plain-name 'deletion audit:'
```

两个合成输入回归均实际失败：预期仅 retained-request，实际又出现 removed-request。日志 `/tmp/oob-deletion-audit.log`。用例未 skip，未修改断言迎合错误结果。未修复，待真机验证。

可达性限制：这是运行时接口级复现，不是真实页面并发复现。普通历史加载在 await 后重新优先读取 runtime；链接预览完成后重新查找当前消息，缺失就返回，均有部分保护。因此不能声称链接预览一定恢复已删除消息。仍需验证重试删除、并发历史刷新与迟到持久化的真实端到端操作。

建议在既有 Conversation 历史所有者中区分删除和未加载，检查项目现有请求代次/版本机制能否作最小扩展；不能简单拒绝所有不存在的 id，否则会破坏历史分页。重启后仍需以持久化历史为准，不能仅靠页面内临时标记宣称问题彻底解决。

## 用户同意后的实现与验证

以上为修复前审计。本轮已修改产品代码：

- `deleteConversationMessageIds` 接收明确 id 集合；复用 coordinator 的每会话 persistence tail 和 ConversationHistoryService 写入队列。原生仍由 ConversationDomainService/AgentConversationHistoryRepository 负责，在 Room 事务中删除指定 id（包含该会话的历史模式别名），不删除未加载的其他记录；同步失效可能含已删除内容的上下文摘要 checkpoint。
- 删除提交前保留界面消息；失败传播给页面并显示失败，消息保留。提交中不允许新 ACP prompt admission。成功后才移除运行时消息，页面切换后不修改新会话。
- 运行时 historyRevision 在编辑开始/结束更新；旧快照保存、历史安装和旧链接预览回调被拒绝。ConversationManager 在 async 历史读取/分页之后核对版本。版本用于本进程异步失效，不冒充数据库全局 revision 或 ACP 生命周期。
- 链接预览只更新指定消息的 linkPreviews；OmniLink 新消息只提交新增消息，不提交整个页面。普通同步投影使用当前 owner 的版本。
- 原生删除持久化，重启不依赖内存 deleted-id 集合。旧版导入来源在删除后清理；迁移写入进入已有串行队列并检查历史修改版本，避免过期迁移绕过写入顺序。该保护针对本 App 的这些入口，不是跨进程通用 CAS，也没有新增 remote Codex revert 能力。

可执行验证：
```sh
cd ui
/tmp/oob-flutter-3.47.2/bin/flutter test --no-pub test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart test/features/home/pages/chat/conversation_manager_lifecycle_test.dart test/services/agent_event_reducer_test.dart test/services/conversation_history_service_test.dart
```

实际 **357 项全部通过**。包含原删除 audit 两项，以及新增提交前可见/提交失败/并发 admission、删除后新分页与局部预览、强制加载期间删除、明确 id 删除保留未加载历史和清理旧导入来源。原生调用在 Flutter 测试中使用 MethodChannel mock，不能当数据库实测。

数据库实际执行用例加入现有 `ConversationCheckpointTest.explicitMessageDeletionPreservesUnloadedRowsAndSurvivesReopen`：同会话别名删除、其他会话隔离、未加载行保留、重复删除、关闭/重新打开数据库后复核。`:baselib:assembleDebugAndroidTest` 已成功编译；**未运行**。真机入口为 `./gradlew :baselib:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.com.omnimind.baselib.database.ConversationCheckpointTest`。

DevelopStandardDebug 构建成功。Flutter 扩展静态分析无 error，80 条 warnings/info（包含原页面现存问题），不称零告警。日志 `/tmp/oob-delete-fix-test.log`、`/tmp/oob-delete-fix-analyze.log`、`/tmp/oob-delete-fix-build.log`、`/tmp/oob-delete-fix-build-final.log`。
APK：`app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk`，版本 0.6.2.3，本地候选包含工作区此前修改，未发布新 release。
SHA256：`2ea0f1d669fb3649e9c9696fb2bb3f2c8e6c99edce1d1bc1c19842a7095c615d`。

**待真机验证**：adb devices -l 无设备，未安装。需要实际小万删除/重试、删除失败提示、重复分页、切换会话、后台返回、杀进程与重启恢复；还需真实下一轮模型请求确认不引用已删除内容，不能只用数据库接口测试证明 ACP 内存上下文同步。没有宣称整个 Harness 删除/回退生命周期已经完整验收。
