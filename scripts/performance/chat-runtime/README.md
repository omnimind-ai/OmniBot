# 聊天与 ACP reducer 真机性能复现

固定独立包 `cn.com.omnimind.bot.runtimeperf`，不覆盖正式包，不读取正式数据库，不发送模型请求。
Profile 为 Flutter ARM64 AOT + native debug，不能将数值当作完整 release 包性能。
所有控制都通过 opt-in VM Service 扩展，入口不会注册到正式应用。

`prepare.py` 从指定 Git commit 导出 baseline `ui/lib`，从当前工作区复制 after，两者加入完全相同探针。
只导出 Flutter 源码；native、依赖、assets 共用当前 checkout，不能把它用于比较 native 修改。
manifest 保存每个 lib 文件及探针 SHA-256。已生成 APK/结果不覆盖，需要重冻结时使用新输出目录。

```sh
BENCH=scripts/performance/chat-runtime
OUT=build/performance/chat-runtime/run-20261117
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
DART="$(dirname "$(command -v flutter)")/cache/dart-sdk/bin/dart"
python3 "$BENCH/prepare.py" --output "$OUT" --baseline HEAD
python3 "$BENCH/build.py" --output "$OUT" --variant baseline
python3 "$BENCH/build.py" --output "$OUT" --variant after
```

构建不访问设备；下列操作只在已授权设备上执行。安装器核验 APK application ID、native debuggable、
AOT `libapp.so` 并拒绝 JIT `kernel_blob.bin`。可以显式用 `--mode debug` 构建/安装 JIT，必须单独报告。

```sh
python3 "$BENCH/install.py" --device "$DEVICE" --output "$OUT" \
  --apk "$OUT/baseline/runtimeperf.apk" --apkanalyzer "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/apkanalyzer"
adb -s "$DEVICE" shell am force-stop cn.com.omnimind.bot.runtimeperf
adb -s "$DEVICE" shell am start -W -n cn.com.omnimind.bot.runtimeperf/cn.com.omnimind.bot.activity.MainActivity --ez enable-dart-profiling true
python3 "$BENCH/connect.py" --device "$DEVICE" --output "$OUT"
"$DART" --packages=ui/.dart_tool/package_config.json "$BENCH/driver.dart" \
  --device "$DEVICE" --output "$OUT" --label baseline --samples 5 --warmups 1
```

替换为 after APK 后重启独立包、重新 connect，再用 `--label after` 测量。建议最后再装 baseline 用
`--label baseline_repeat` 交叉复核。记录温度/thermal status/刷新率，保持相同设备条件，测量时不要同时操作手机。

默认 suite 对生产 `AgentEventReducer` 和生产 `ChatConversationRuntimeState` 执行 1000 个合成官方
`session/update`，四个 child、一个 parent tool。最终 tool completed 以及官方 PromptResponse 分别投影到同一 reducer。
没有私有 Agent 协议或第二 reducer。每批计时仅包括同步 reducer 调用；fixture 分批生成与夹在各批之间的 event-loop yield 计入 wall 时间。
不包含事件生成、平台通道、持久化、真实页面布局或网络，因此不能把 isolated reducer 提速称为整体 App 提速。

- `short`：短内容累计快照；`long`：每 child 不断增长的累计文本；`tools`：需要保留的工具进度事件持续增长。
- `--events`（1..10000）、`--batch`（1..1000）、`--workers`（1..8）、`--history-rows`（0..10000）可控制负载。
- 每批 `workUs` / `reductionWorkUs` 是同步调用的墙钟耗时，**不是采样 CPU 时间**。
- `semanticSha256` 比较最终 status/tool identity/subagentEvents，排除了运行时间戳等非确定信息；前后必须一致。
- `timerGapsUs` 是主 isolate 10ms Timer 的实际间隔，**不是帧耗时或输入延迟**。
- `FrameTiming` 为 Flutter build/raster/total span，不是 SurfaceFlinger 呈现 FPS。不要根据静止页面帧数判断卡顿。

长对话使用生产 ConversationService/HistoryService 生成固定 1000 / 10000 条合成消息。创建的 ID 仅记录在本独立包，
以 `runtime_perf_history_v1_` 为键；种子写入可重复恢复，不改变真实数据。open 走真实 GoRouter/ChatPage，默认只加载首屏，
`loadMore` 调用当前真实列表的分页回调。不要为了测1万条存储历史，误将全部1万条预加载视为普通打开场景。

```sh
# --params 的所有值为字符串；--variant 必须与当前已安装的 APK 一致。
"$DART" --packages=ui/.dart_tool/package_config.json "$BENCH/driver.dart" \
  --device "$DEVICE" --output "$OUT" --variant baseline --params '{"op":"seed","count":"10000"}'
# 将 params 依次替换为：
# {"op":"open","count":"10000"}
# {"op":"startCapture"}
# 在已观察到的列表区域执行真实 ADB swipe，或单独调用 {"op":"loadMore"}
# {"op":"result"}   （--label scroll_baseline --save true 可保留 JSON）
# {"op":"state"}
# {"op":"home"}
# {"op":"cleanup"}  （只删除该探针记录的两个合成会话）
```

result 会等待 1100ms 接收 Profile 批量 FrameTiming，因此包含尾部静止窗口，原始时间戳保留供按实际操作窗口裁剪。
长对话加载结果可结合 Perfetto 的 scheduling/frame timeline 和 Dart CPU samples 解释；P95 不能掩盖翻页最大帧。
VM token 仅保存在 mode 0600 的 `vm-connection-private.json`，不应提交或展示。完成后：

```sh
python3 "$BENCH/connect.py" --device "$DEVICE" --output "$OUT" --disconnect
```

可以卸载本次创建的独立包来清理合成数据；不要卸载正式包。

## 真实 ChatPage 流式负载

`streamStart` 在独立包中为 `text` / `tools` 分别新建并记住专用合成 Agent 会话，不复用或改写上面的长历史种子。
通过现有 coordinator 的 `beginAcpTurn` / `bindAcpSession` 预留身份，然后输入官方 `session/update`。
文本场景传增量 `agent_message_chunk`；工具场景传一个 parent tool、四个 child 的 started/progress/completed 事件。
每轮使用新的 session/turn/item ID，避免恢复进程或切换 APK 后碰撞已有历史。末尾统一调用
`applyAcpPromptResponse(end_turn)`，通过现有保存 owner 等待提交。最终 SHA 只包含确定的文本/卡片语义。
此路径包含生产 coordinator、持久化、真实 ChatPage 与布局绘制；不包含 native EventChannel、真实 Agent 进程和网络。

本地 fixture 没有 provider 的 turn admission，host 更新必须使用 `beginAcpTurn` 预留的同一个 turn 身份；
另造未被正式 admission 接受的 provider turn 会被 reducer 正确隔离，`handled=true` 本身不能证明发生了消息投影。
探针从生产 Agent catalog 校验内置助手，并在打开/启动前通过 ChatPage 公开的 ConversationManager getters 和
可见 ChatMessageList 核对 conversation/mode/agent、以及它们是否引用同一 runtime.messages。
首个更新后的真实 frame 必须在可见列表找到对应文本或 toolCallId，否则抛错；最终还需找到结果消息并通过 PromptResponse。
`streamStatus.visibleProjectionVerified` 为 true 后才适合切回 home 测后台。结果 `valid` 必须为 true，驱动拒绝无效 stream 结果。
`state.visibleOwner` 只输出 route path、身份、数量和源对象匹配标志，不输出消息正文。

```sh
"$DART" --packages=ui/.dart_tool/package_config.json "$BENCH/driver.dart" \
  --device "$DEVICE" --output "$OUT" --variant baseline --label baseline_stream_text \
  --params '{"op":"streamStart","kind":"text","events":"500","intervalMs":"20","payloadChars":"128"}' \
  --wait-stream true --save true
```

改为 `kind=tools` 测工具进度；支持 `workers=1..8`，两个场景默认 500 events，20ms 间隔、128 字符 payload。
间隔从一次投影完成后开始等待，因此这是受主 isolate 处理能力影响的实际事件节奏，不能声称固定频率的后台生产者。
结果保留每次开始时刻及同步 apply 调用耗时；`emittedEvents` 仅计请求的文本/进度次数，工具生命周期事件另计。
参数上限为 events 10000、payloadChars 32768，且 events×payloadChars 不超过8Mi字符，避免无界 fixture 分配。
`--wait-stream true` 会等待生成完成，再获取 FrameTiming/Timer capture；结果中 `startUs..projectionEndUs`
界定流式窗口，`throughPersistenceUs` 还包括官方完成与历史保存，不应混作纯渲染时间。

不带 `--wait-stream` 时，start 立即返回。之后可以调用：

- `{"op":"streamStatus"}`：读取 fixture 发送数量和 owning runtime 的 responding 状态。
- `{"op":"home"}`：离开聊天页；fixture 继续输入，验证后台投影与持久化。
- `{"op":"streamOpen"}`：重新打开当前 fixture 会话；可指定 kind。
- `{"op":"streamStop"}`：打断 fixture 等待、停止合成输入，通过 `PromptResponse(cancelled)` 官方投影收尾。
- `{"op":"result"}`：等待 FrameTiming 批量交付并返回当前 capture。

这是无真实 Agent 进程的 fixture，VM stop 验证的是取消结果投影；真实 UI Stop → native `session/cancel`
仍应在正式 Agent 场景验证，不能声称这里已测通 native 取消传输。cleanup 会先停止 fixture，再删除本探针记录的专用会话。

## CPU 栈与 VM timeline

驱动支持 `--cpu-profile true` / `--timeline true`，可与上面的 `--wait-stream`、普通 reducer suite 或
`--observe-ms 20000` 组合。observe 会记录20秒 FrameTiming/Timer，同时允许另一个终端执行已观察坐标的真实ADB操作。
计时窗口使用 `getVMTimelineMicros` 的时钟；需要导出已知历史窗口时可显式传 `--time-origin-us` 和 `--time-extent-us`。
每个 label 文件只写一次：

- `LABEL-cpu-samples.json`：完整 VM `getCpuSamples` 返回，含函数表和原始采样栈。
- `LABEL-cpuprofile.json`：同一批样本转换的 Chrome CPU profile（栈方向已转成 root→leaf，行列未知时为-1）。
- `LABEL-timeline.json`：选定窗口的 VM timeline。
- `LABEL-diagnostics.json`：采样状态、样本数、period 与窗口；无凭据。

timeline 默认仅开启 Dart/Embedder/GC，可用 `--timeline-streams Dart,Embedder,GC` 指定，并在结束后恢复此前流设置。
不要将启用时间线与未启用时间线的数据混为同一A/B条件。CPU profiler 若 disabled/unavailable，只记录诊断状态，
不自动改 VM flags、不写出假 CPU 栈或声称采样成功。`getFlagList` 仅保存 profiler/profile_period 两项。
若需要重新启动独立包启用采样，可使用当前 Flutter Android 启动参数（先结束旧独立进程）：

```sh
adb -s "$DEVICE" shell am force-stop cn.com.omnimind.bot.runtimeperf
adb -s "$DEVICE" shell am start -W \
  -n cn.com.omnimind.bot.runtimeperf/cn.com.omnimind.bot.activity.MainActivity \
  --ez enable-dart-profiling true
```

之后重新 connect；最终仍以 RPC 是否实际返回样本判断可用性。该参数来自当前 Flutter SDK 的 Android 启动实现，
不是把 Debug 测量替代成 Profile，也不会修改正式包。采样包含 main isolate；native/kernel CPU 仍需 Perfetto 独立证据。

若应用预建 FlutterEngine 而忽略 Activity profiling extras，可显式传
`--cpu-profile true --enable-profiler true`。驱动先读取并保留原 `profiler` 值，仅在能可靠读取时调用官方
`vm.setFlag('profiler', 'true')`；在 finally 中恢复原值。开启和恢复的 RPC 结果均保存到 diagnostics。
没有这个显式选项时绝不改 profiler flag；不会修改 `profile_period`。此选项仅修改独立进程当前测量期间的 VM 设置，
无需重建 APK。若服务拒绝开启，仍只报告 unavailable/disabled，不宣称已取得 CPU 栈。

## 汇总有效 A/B 测量

```sh
python3 "$BENCH/summarize.py" --output "$OUT"
# 仅汇总已完整采集的 baseline，不要求 after 文件：
python3 "$BENCH/summarize.py" --output "$OUT" --before-only --summary-file baseline-only-summary.json
python3 -m unittest discover -s scripts/performance/chat-runtime -p 'test_summarize.py' -v
```

默认读取 `baseline_clean-results.json` / `after_clean-results.json`，要求 isolated suite 的 complete=true，
丢弃 warmup，再按 short/long/tools 分组。各组参数、最终语义 SHA 和保留事件数必须前后一致。
`--before-label` / `--after-label` 可指定其他 label。
UI 默认读取 `baseline_stream_{tools,text}_{1,2,3}-results.json` 与对应 after 文件；可用
`--before-stream-prefix` / `--after-stream-prefix` 和 `--stream-samples` 调整前缀与轮数。
所有需要的输入必须完整且有效，缺失/无效时退出，不会发布部分 A/B 数字。

UI 必须通过 visibleProjectionVerified、valid、最终消息、官方 PromptResponse、页面/runtime/list 同源与完成状态检查；
工具保留事件数须等于进度事件数+每 worker 的两条生命周期事件。updateWorkUs 与 updateTimesUs 必须覆盖每次 emitted event。
按 `[stream.startUs, stream.projectionEndUs)` 过滤 FrameTiming 的 buildStart，排除开流前与保存后静止帧。
逐轮先计算工作合计、wall、帧 P95/P99/max，再对各轮取中位数；JSON 同时保留按帧合并的分布与每轮指标，
两种统计权重不同。百分位使用排序后 `floor((n-1)*p)`，无插值。
Timer 间隔只能按完整 capture 汇总（包含尾部），不是输入延迟或帧耗时。
summary JSON 默认新建 `summary.json`，已有文件不会覆盖；stdout 输出简短 Markdown 表。
汇总器只读取指定结果和 workspace manifest，不读取 VM 私有连接文件，也不修改原始结果。

## 本轮固定 A/B 输入

2026-11-17 的正式对照关闭 CPU profiler/timeline，各 isolated 场景 1 次预热 + 5 次采样；真实页面每组 3 次，轮间先调用 `cleanup` 清理本探针的合成会话。

- tools：`streamStart kind=tools events=1000 intervalMs=5 payloadChars=256 workers=4`，最终必须保留 1008 条事件。
- text：`streamStart kind=text events=200 intervalMs=10 payloadChars=256 workers=4`，共 51,400 UTF-16 字符（含换行）。
- 每轮 `--wait-stream true --save true`，前后必须使用同一 probe 与 fixture 参数，再由 `summarize.py` 校验完整数据和可见 owner。
- `wallUs` 到官方 PromptResponse 和本轮持久化结束，包含生产者间隔；它不等同于文字动画全部结束。文字透出完整性和停止 ticker 由独立 widget 回归及真机尾部检查验证。
- CPU samples/timeline 另开诊断轮，不混入上述无 profiler 的耗时表。新一轮修改必须重新冻结 after；保留 baseline APK 时应核对 baseline/probe/shared-native 的 SHA，不能覆盖旧结果冒充同版。
