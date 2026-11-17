# 聊天、长文本与 subagent 性能修复：2026-11-17

修复集中在现有 ACP 事件投影、不可变消息和文字显示 owner：降低累计文本展示更新频率；复用已冻结的消息节点；避免重复 JSON 编码和整页重建；修复粗体扫描平方复杂度；让大积压文字追上并停止 ticker，修复 ticker 重启时钟。

## 同机结果和边界

设备为 RMX5200 / Android 17 / ARM64。应用正式包为 0.7.0.5，基线代码 `91742663b`；开发分支 `codex/investigate-subagent-performance`。正式测试使用独立包 `cn.com.omnimind.bot.runtimeperf`，Flutter 3.47.2 Profile AOT、native debug，测量期间系统 thermal status 记录为 0。刷新率随系统策略变化，原始数据保留每轮 display refreshRate；表中不推算呈现 FPS。

| 测量（各轮中位数） | 修改前 | 最终修改后 | 变化 |
|---|---:|---:|---:|
| 累计 1000 条工具进度：reducer 同步工作 | 1265.22 ms | 311.37 ms | -75.4% |
| 1000 条工具进度：投影同步工作 | 4073.44 ms | 1409.89 ms | -65.4% |
| 1000 条工具进度：生命周期/保存结束 | 10.40 s | 7.89 s | -24.2% |
| 1000 条工具进度：UI build P95 | 3.44 ms | 2.39 ms | -30.5% |
| 51400 字符流式回复：投影同步工作 | 45.73 ms | 56.28 ms | +23.1% |
| 51400 字符流式回复：生命周期/保存结束 | 26.50 s | 13.70 s | -48.3% |
| 51400 字符流式回复：UI build P95 | 321.31 ms | 158.76 ms | -50.6% |

工具输入为 1000 个 progress、4 个 child、每次 256 字符、间隔 5ms；结束保留 1008 个工具事件。文本输入为 200 次、每次 256 字符加换行、间隔 10ms，共 51400 UTF-16 字符。真实页面每组 3 轮、轮间清理本探针会话；isolated reducer 为每场景 1 次预热和 5 次采样。CPU profiler/timeline 只用于单独诊断，不混入正式表。

`同步工作` 是计时区间墙钟和，不等于采样 CPU 时间。生命周期耗时包含生产者间隔、官方 PromptResponse 和持久化，不等于全部文字动画可见完成时间。FrameTiming 是 Flutter build/raster 工作时间，不是最终呈现 FPS 或点击延迟。最终长文本仍有整段 paragraph 布局超帧预算的情况，不能据此宣称所有长消息均达到 60/120 FPS。

每轮均校验当前 ChatPage、Conversation、runtime、列表数据使用同一 owner，首个 item 必须真的出现在可见列表中；`handled:true` 不作为成功证据。前后文本 SHA 一致（`24cae999d1020365fd41ac8e233e90dbe01a4c8fff27b3e7923569c58924bc5f`），工具 SHA/1008 条事件也一致。完整分布、P99、最大值和各轮原始数据由 `scripts/performance/chat-runtime/summarize.py` 严格校验并保留。

A/B 两个 APK 使用同一份 native 源码（包含这轮快照合并），所以表只量化 Flutter 改动；不能用表中百分比代表 native 合并或整个 release APK 的收益。设备、缓存、热状态和测试顺序的影响仍在，较小差异不作提速结论。

## 定位证据

- [SQL] 正式包真实四 worker 任务：早期 20.02s trace 主线程 Running 11.468s，约第 2–12 秒每秒 309–327 次 AgentRuntimeEvents；晚期 19.975s trace 主线程 Running 14.843s。排除重复 begin 的 75 个平台调度区间为 2.361–15.099s，均值 6.122s。这些异步等待区间不是 CPU 时间，不能直接相加。
- [Dart CPU] 累计工具事件 isolated 诊断中消息冻结链占 6388 个样本的 45.44% inclusive；与 merge、JSON 等嵌套份额不可相加。最终模型只信任自己拥有的不可变集合，外部 unmodifiable 外壳仍完整复制。
- [Dart CPU] 长文本现场 5s、4471 样本中 `_streamingBoldRanges` inclusive 4064（90.9%）。旧代码每找到一种 marker，都会重新扫描不存在的另一种 marker 的剩余后缀。现在保留每种 marker 的 next index 和耗尽状态，关闭、转义和 Unicode 语义保持一致。
- [Dart CPU] 修复扫描后的中间包中，paragraph layout 成为剩余热点；有效窗口 11179 样本的 layout leaf 为 58.81%。它解释了为什么不能把纯扫描函数的千倍微基准当作 App 千倍提速。
- [GAP] 正式包 Perfetto 的 Flutter SurfaceView FrameTimeline 覆盖不足；perf samples 最初没有 callstack，后续通过独立 Profile 的 Dart CPU samples 补充。没有据静止页面低帧认定卡死，没有据高分配认定泄漏或 OOM。

Android Flutter 的 UI/platform 线程合并使 Dart 计算也可能显示在包名同名主线程上，不能只凭线程名归因 Kotlin。[Flutter 架构说明](https://docs.flutter.dev/resources/architectural-overview)

## 改动与生命周期

- `AgentLlmClient` 在现有请求内按 50ms 合并累计 content 展示快照，保留首快照和 reasoning/tool 边界；正常结束/失败保留最终已接收内容。取消传播到现有 emission worker，丢弃未展示 pending，测试确保 request 结束后不再迟发 content。总工具/reasoning 队列仍非严格有界，不宣称全部队列都已消除积压。
- `SubagentDispatcher` 逆向取最后非空 thinking 行，缓存原有空白正则。
- `ChatMessageModel` 使用私有不可变 Map/List 节点共享；reducer 读取已有 typed 节点、去掉丢弃的重复构造；路由分类不再重复生成详情 JSON，最终 upsert 仍过滤 replay 后完整保存。
- 工具进度走行级 notifier；pending input、终态、会话/turn 控件仍由现有 owner 刷新。异步输入答复写回原 runtime，切会话后的草稿保留。
- 普通短回复保留原打字节奏；大积压使用现有 ticker 追赶，全文显示后停止；重启 ticker 重置时钟并保留小段 credit。没有新增 Agent 状态、第二 reducer、协议、重试或 terminal 事件。

最后尝试的大文本视觉缓存/80ms 合并没有获得稳定真机收益，已撤回，没有把该实验混入最终代码或表格。文字透出保持完整的连续前缀；虚拟时钟测试覆盖五万字、emoji、替换、重启，以及十万字粗体内容不截断。

## 长历史、验证和设备状态

独立合成库生成 1000/10000 条历史，真实页面首屏只加载 50 条；经页面已有分页回调扩展到 100/150/200。中间 Profile 包的三次回调含下一帧为 30.85/28.33/58.91ms，读取/分页代码与最终版一致，不作其提速对照。自动触摸注入没有取得可靠滚动位移，因此没有报告触摸滚动 FPS 或把零采样帧视为通过。相关 Flutter 真页/历史测试继续覆盖分页、保留状态和导航。

- Flutter 全量：2290 项通过（最终保留生产代码）；最后缓存实验的 2299 项不作为最终功能增量。
- app JVM/Robolectric 全量：1649 项，失败 0、错误 0、跳过 0。
- 新增长文本/时钟 focused 测试和消息/历史/输入请求回归通过；Python 汇总验证 8 项通过。
- ARM64 标准 Debug APK 构建通过；静态分析无 error，仓库仍有 warning/info。`git diff --check` 通过。

正式包签名与本机 debug 签名不同，没有覆盖或卸载正式包，没有导出正式数据库或复制 provider 凭据。正式包内的两次合成任务记录保留用于复核；其余数据、fixtures 位于独立测试包。已清理独立包和本轮 VM 转发，并返回正式 App。修复在开发分支交付，本轮未向远端推送。

## 复现与产物

见 [复现说明](../scripts/performance/chat-runtime/README.md)。最终使用的冻结输入和原始结果在本机忽略目录 `build/performance/chat-runtime/run-20261117-final-verified/`；baseline/probe/shared-native 与生产源码 SHA 校验一致。baseline APK 经相同源码/probe/native 哈希复核复用，元数据保存来源。此前没有通过可见性/turn admission 验证的探索轮和后续未保留的实验包，不计入最终表。

原始正式包 trace、CPU samples、微基准及可重跑 SQL 在 `build/performance/2026-11-17-agent-jank/`，主要文件为 `early-native-analysis.md`、`late-and-dart-analysis.md`、`release-subagent-early.perfetto-trace`、`release-subagent-long-late.perfetto-trace`、`live-long-text-cpu.json`。

```sql
-- 对 trace_processor 中已加载的正式包 trace 运行。
SELECT t.name, t.is_main_thread, SUM(s.dur) / 1e6 AS cpu_ms
FROM sched_slice AS s
JOIN thread AS t USING (utid)
JOIN process AS p USING (upid)
WHERE p.name = 'cn.com.omnimind.bot' AND s.dur > 0
GROUP BY t.utid
ORDER BY cpu_ms DESC;
```

此查询得到早期/晚期主线程 Running 时间；队列等待的完整过滤 SQL 和样本窗口计算见上述两个分析文件。
