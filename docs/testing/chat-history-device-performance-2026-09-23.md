# 长聊天真机性能测试：2026-09-22 至 23 日

> 本文保留优化前基线。随后修复和同机复测结果见[长聊天性能优化与真机复测](chat-history-optimization-2026-09-23.md)。

**结论：首屏分页和普通滚动表现尚可，但长聊天性能问题尚未解决。实际快速滑动触发翻页时出现 282.8 ms 的 Flutter UI 帧；10,000 条历史中仅保存一条变化消息，保存接口返回中位耗时仍达 22.79 秒。**

本轮测量当前实现，没有修改业务代码，也没有进行迁移前后同条件 A/B 测试。因此以下结果不能解释成相对旧版的提升百分比。

## 环境和数据

- 代码：`afcdc1962`；应用 `cn.com.omnimind.bot`，0.6.3 / versionCode 16。
- 实机：PJD110，SM8650，Android 16；系统显示模式 120 Hz。
- 实际渲染分辨率 1080 × 2376、480 dpi；物理面板 1440 × 3168。保留用户已有分辨率与刷新率设置。
- Flutter 3.47.2，Profile / ARM64 AOT，APK 含 `libapp.so`，不含 `kernel_blob.bin`。原生依赖沿用 debug variant，未开启发布版混淆；不是完整 Release 包。
- 用户明确允许临时覆盖安装 Profile 包，结束恢复原 Debug 包。初始包与本地 Debug APK 的 SHA-256 完全一致，并确认含迁移后的类与数据库 schema 19。
- 初始只有 1 个真实会话、3 条存储记录。另建两个独立合成会话，分别含 1,000 / 10,000 条消息。一半为提问，一半为含标题、16 段正文、加粗、行内代码和列表的 Markdown 回答；通过现有 Flutter 服务与原生持久化接口写入，没有发送模型提示。
- USB 充电期间测试，电池温度从约 33.4°C 升至末尾 36.8°C；系统 thermal status 从 0 变为末尾 1。样本顺序未随机化，不能用小幅数值差异判断规模更大反而更快。

Profile 入口只在本地 `build/performance/2026-09-22-device/profile_main.dart` 注册 VM Service 诊断扩展，再启动原应用；页面、布局、主题、消息组件均沿用生产代码。扩展用于调用现有数据服务、记录 FrameTiming，以及受限地操作本轮合成会话。

Flutter 官方建议在真机 Profile 模式下测量性能，Debug 数据不能代替发布性能。本报告的主要数值均来自 Profile 包。[Flutter 性能分析说明](https://docs.flutter.dev/perf/ui-performance)

## 测量结果

下表除特别说明外为中位数。

| 项目 | 1,000 条历史 | 10,000 条历史 |
| --- | ---: | ---: |
| 读取最新 50 条：服务调用至显示模型解码完成 | 58.56 ms | 51.76 ms |
| 读取最新 50 条：P95 | 69.54 ms | 63.09 ms |
| 最深一页 50 条：offset 950 / 9,950 | 58.42 ms | 82.96 ms |
| 进程内重读首屏至首个更新后 Raster 完成 | 69.92 ms | 62.31 ms |
| 追加历史 50 条：数据读取完成 | 48.63 ms | 42.93 ms |
| 追加历史 50 条：至更新后 Raster 完成 | 313.64 ms | 311.49 ms |
| 单条变化消息保存接口返回 | **933.60 ms** | **22,787.08 ms** |
| 进程冷启动：Android Activity TotalTime | 1,484 ms | 1,427 ms |
| 冷启动后 3 秒采样：进程 PSS | 356.8 MiB | 338.0 MiB |
| 冷启动恢复后实际加载的消息数 | 50 | 50 |

方法与边界：

- 分页读取每组预热 3 次后采样 25 次，包含平台通道、Kotlin/Room、消息转换和 Dart 显示模型解码，**不是纯 SQL 查询时间**。P95 取排序后 `floor((n - 1) × 0.95)` 的样本。
- 首屏重读每组 5 次，调用现有 `loadConversation(preferInMemory: false)`，使用已有进程和已热数据缓存；不包含抽屉选择动画。1,000 条组有一次 326.10 ms 的异常高值，没有删去。
- 追加历史每组 5 次，调用现有 `loadMoreMessages()`；使用 FrameTiming 的时间戳，未把回调批量上报的等待时间算入 Raster 完成耗时。
- 单条保存每组 5 次；固定持有 50 条显示对象，每次替换其中一条，其余对象不变。先完成一次未计时的提交，后续增量走现有 `saveConversationMessages`。**计时包含原生写后同步广播等工作，不等于 Room 事务耗时。**测试时前台为 10,000 条会话，两个写入组的前台匹配条件不同，不能仅凭两组比值量化算法复杂度。
- 冷启动每组 3 次，先保存目标，再 force-stop 并启动 MainActivity；没有清空数据。`am start -W` 是 Activity 启动指标，不等同于用户点击至聊天全部可读。每次启动后核对当前会话及 50 条首屏数据。
- PSS 是固定时点采样，不是内存峰值，也不是长期泄漏测试。前面扫描全部历史后的内存不能与冷启动样本混用。
- 完整扫描分别读取 20 / 200 页，得到恰好 1,000 / 10,000 个唯一消息 ID，无重复；总耗时分别 1.15 / 13.42 秒。

## 滚动与翻页卡顿

普通拖动每组 8 次，每次 1.8 秒，交替方向；取每个命令窗口中间部分，排除前后各 200 ms。采集 Flutter FrameTiming，同组都启用 Dart/Embedder/GC 时间线。

| 项目 | 1,000 条历史 | 10,000 条历史 |
| --- | ---: | ---: |
| 有效采样帧数 | 1,454 | 1,455 |
| Flutter 出帧节奏 | 约 120 Hz | 约 120 Hz |
| UI 工作 P95 / 最大值 | 0.84 / 2.29 ms | 0.82 / 1.64 ms |
| Raster 工作 P95 / 最大值 | 6.79 / 8.67 ms | 6.82 / 8.44 ms |

这组普通拖动未触及分页边界，说明已加载内容的常规滚动开销较小。出帧节奏由 Flutter 的 buildStart 时间戳计算，**不是 SurfaceFlinger 最终呈现 FPS**。本机 Perfetto 探测未取得可用于 Flutter SurfaceView 的应用 FrameTimeline 样本，所以没有把宿主 View 的 gfxinfo 当作 Flutter 掉帧率。[Perfetto FrameTimeline 的 SurfaceView 限制](https://perfetto.dev/docs/data-sources/frametimeline)

随后在 10,000 条会话中执行 24 次真实 ADB 快速下滑，每次 100 ms、间隔 600 ms。第 20 次检查时，已加载消息从 50 增至 100，确认真正触发了界面的历史分页：

- 采集 2,445 帧；UI 工作 P95 1.11 ms，但**最大值 282.84 ms**。
- 对应最慢帧总耗时 287.09 ms；Raster 最大值只有 7.43 ms。
- 另一次受控翻页复测出现约 240–261 ms 的 UI 帧；时间线中 `LAYOUT (root)` 达 243.74 ms，`FINALIZE TREE` 达 39.76 ms。此前一次诊断还记录到约 295 ms 的 layout。

因此普通滚动的低 P95 会掩盖分页边界的可见停顿。这里已经定位到 Flutter 布局/树更新阶段，尚未通过具体 Widget 的 CPU 采样或修复 A/B 证明是哪一个控件造成。CPU 采样接口在该包中关闭，此处依据 FrameTiming 和 Dart/Embedder 时间线，而非函数采样结果。

## 已确认的写入路径问题

`ConversationHistoryService` 确实仅把变化消息传给原生，但原生保存返回前还有以下同步等待链：

```text
AssistsCoreManager.replaceConversationMessages
  -> ConversationDomainService.replaceConversationMessages
  -> historyRepository.replaceThreadMessagesFromUiSnapshot
  -> publishMessagesReplaced
  -> listConversationMessages（读取整段历史）
  -> RealtimeHub.publish("messages_replaced", 完整消息列表)
  -> FlutterChatSyncBridge 通知
  -> MethodChannel 返回
```

`ConversationDomainService.kt:623` 在没有条件判断的情况下读取全量消息。`AgentConversationHistoryRepository.kt:518` 开始以每页 16 条循环获取整个会话。即使这次只更新一条，也会经过该路径。本轮没有连接 WebChat 客户端。

10,000 条的 5 次保存耗时为 21.36、23.88、21.70、22.85、22.79 秒；1,000 条为 0.93、0.91、0.94、0.92、0.95 秒。上述代码路径与实测共同说明：减少 Dart 序列化没有消除端到端的全量历史工作。尚未单独插桩拆分每个原生步骤，因此不声称全部 22.79 秒都由某一条 SQL 消耗。

后续优化应优先消除保存 ACK 路径中的全量历史回读，保留现有 ACP 生命周期与 WebChat 同步语义；随后修复历史前插时的布局重算，并用同一快速滑动场景复测。

## 恢复与产物

- 已覆盖恢复原 Debug APK，SHA-256：`acf720dbb8ade4acf6b444e8835f5ba82172b3dc316ec22c2851d321bc6cfe5d`，与测试前一致。
- 合成会话先经 UI 删除逻辑归档/隐藏，再通过现有原生持久删除接口彻底清理；相应测试隐藏引用也已移除。
- 数据库复核：恢复为 1 个原会话、3 条原记录，合成记录为 0。原消息已有字段均未修改或丢失；应用历史恢复正常补充了 Agent 标识和历史卡片展示元数据，故不声称整个数据库逐字节相同。
- 未改动用户分辨率、密度、刷新率、模型设置或真实消息正文。已回到原会话。

本地采样、临时入口和驱动均在 `build/performance/2026-09-22-device/`，不纳入版本控制：

- `profile-measurements.json`：分页、打开、普通拖动 FrameTiming、增量保存。
- `cold-measurements.json`：6 次冷启动与 PSS。
- `pagination-confirm.json`、`pagination-timeline.json`：受控翻页帧与时间线。
- `fling-measurements.json`：真实快速滑动及加载数量。
- `restore-verification.json`：恢复包校验和数据完整性复核。
- `profile_main.dart`、`profile.init.gradle`、`measure.dart`、`pagination.dart`、`fling.dart`、`cold.py`：本轮测量代码；其中会话 ID 和清理范围绑定本轮合成数据，不能直接用于别的数据库。

含真实聊天内容的临时数据库副本、初始截图和 VM 连接凭据在复核后删除；报告只保留聚合数据与合成样本的诊断产物。
