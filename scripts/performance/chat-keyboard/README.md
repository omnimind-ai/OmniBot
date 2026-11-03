# 聊天键盘性能前后复现

本目录测量真实 `main_standard` → `ChatPage` → `ChatInputArea`，不替换聊天 UI、输入框、
消息列表或键盘动画。固定独立应用 ID 为 `cn.com.omnimind.bot.keyboardperf`。
探针启动前检查 Android 进程名，安装器检查 application ID、native debuggable、AOT
`libapp.so` 且拒绝 JIT `kernel_blob.bin`。不读取或导出正式应用的数据库，不发送消息。

默认是 **Flutter Profile AOT + native debug** 测量，不是完整 production release。
显式 `--mode debug` 可构建和验证 **Flutter JIT Debug**，用于匹配实际安装的 Debug 应用；
两种模式的结果必须分别报告，不能合并成一组性能数字。
init script 禁用开发 Provider bootstrap，并清空相关开发凭据字段。
普通应用初始化仍会运行；独立包仅跳过自己的欢迎页。

## 冻结与构建

使用项目实际 Flutter SDK 和 JDK 21。以下步骤不访问设备：

```sh
BENCH=scripts/performance/chat-keyboard
OUT=build/performance/chat-keyboard/run-20261029
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
DART=/Users/ocean/.local/share/flutter/3.47.2/bin/cache/dart-sdk/bin/dart
python3 "$BENCH/prepare.py" --output "$OUT" --baseline HEAD
python3 "$BENCH/build.py" --output "$OUT" --variant baseline
python3 "$BENCH/build.py" --output "$OUT" --variant after
```

`prepare.py` 要求新的 Git-ignored 输出目录。baseline 导出指定 Git 提交的 `ui/lib`，
after 复制当前 `ui/lib`；两者注入同一份探针。`keyboard-perf.json` 保存提交号、
每个 Dart 文件与探针的 SHA-256。没有覆盖工作区入口、临时切分支或隐藏生产改动。
依赖、pubspec、assets/shaders 和 native checkout 共用当前环境，因而这种比较只适合
本次只有 Flutter lib 行为改变的任务，不能用于声称对比不同 native/资源/依赖版本。
`package_config.json` 的 `ui` 指向各自快照，Gradle `:flutter` source 同样指向对应快照。
保留的 `baseline/keyboardperf.apk` 与 `after/keyboardperf.apk` 不会被后一次构建覆盖。

补充同模式 JIT 对照时，复用冻结源码和探针：

```sh
python3 "$BENCH/build.py" --output "$OUT" --variant baseline --mode debug
python3 "$BENCH/build.py" --output "$OUT" --variant after --mode debug
```

产物分别是 `baseline/keyboardperf-debug.apk` 和 `after/keyboardperf-debug.apk`，
SHA-256 位于对应目录的 `apk-debug-sha256.txt`，不会覆盖 Profile AOT 产物。
安装时必须同时传 `--mode debug --apk .../keyboardperf-debug.apk`，驱动也要传
`--mode debug --variant baseline|after` 并使用独立结果标签，如 `baseline_debug`。
安装器验证 Debug 包含 `kernel_blob.bin` 且不含 AOT `libapp.so`；驱动核对模式和哈希。
两种模式均在独立包禁用开发 bootstrap、清空相同开发凭据字段。

## 安装和采样

只在已获授权的设备上执行。每次切换 APK 后重新启动独立包并连接 VM。
安装保留独立包合成数据，前后使用同一数据集；不清除任何应用数据。

```sh
python3 "$BENCH/install.py" --device "$DEVICE" --output "$OUT" \
  --apk "$OUT/baseline/keyboardperf.apk" \
  --apkanalyzer "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/apkanalyzer"
adb -s "$DEVICE" shell am force-stop cn.com.omnimind.bot.keyboardperf
adb -s "$DEVICE" shell am start -W \
  -n cn.com.omnimind.bot.keyboardperf/cn.com.omnimind.bot.activity.MainActivity
python3 "$BENCH/connect.py" --device "$DEVICE" --output "$OUT"
"$DART" --packages=ui/.dart_tool/package_config.json "$BENCH/driver.dart" \
  --device "$DEVICE" --output "$OUT" --label baseline --samples 10 --warmups 2
```

将 APK 改为 `after/keyboardperf.apk`、label 改为 `after`，重复安装/启动/连接/采样。
时间允许时再装 baseline，使用 `--label baseline_repeat`，检查设备热状态或负载漂移。
每组采样前后记录设备电池温度、热状态、输入法及显示刷新配置；不要同时操作手机。
driver 每次触摸前检查独立包仍在前台，切到其他应用即退出。结束后回到独立包首页。

两个场景均由真实路由打开：

- `empty`：小万 Agent 新会话，无消息。
- `history`：纯聊天会话，首次在独立包生成固定 200 条合成消息，通过现有
  `ConversationService` / `ConversationHistoryService` 保存，再重开。
  使用真实 `ChatMessageList.onLoadMore` 逐页加载，确认 `messages.length == 200` 才采样。

准备新会话时原 UI 会自动聚焦，随后探针收起键盘，因此所有数据都属于 **warm keyboard**，
不能称为首次冷启动输入法。默认每场景先做两次预热，不计入汇总。
每次正式测量先收起键盘/清除焦点，之后使用真实 `adb shell input tap` 点击探针从
`TextField` RenderBox 得到的中心坐标。不会用 `requestFocus()` 代替被测触摸。

单步诊断可用 `--params` 调用 `prepareScenario`、`startCapture`、`result`、`hide`、`home`：

```sh
"$DART" --packages=ui/.dart_tool/package_config.json "$BENCH/driver.dart" \
  --device "$DEVICE" --output "$OUT" \
  --params '{"op":"prepareScenario","scenario":"history"}'
```

## 数据口径

`driver.dart` 保存每次原始 focus/IME inset 时间序列、Flutter `FrameTiming` 和场景状态。
触摸后保留 1600 ms 观察窗口，让动画和 Profile `FrameTiming` 批量回调交付；汇总只采用
收到 PointerDown 后固定 **800 ms** 内开始的帧。等待 VM 和 ADB 的时间不计入该窗口。
无触摸、无聚焦、无正数 inset、未完成稳定确认或结束时键盘隐藏均视为无效，不计成零耗时。

```sh
python3 "$BENCH/summarize.py" "$OUT/baseline-results.json" \
  "$OUT/after-results.json" > "$OUT/summary.json"
python3 "$BENCH/connect.py" --device "$DEVICE" --output "$OUT" --disconnect
```

- `focusMs`：Flutter 收到 PointerDown 到 FocusNode 变为聚焦。
- `firstInsetMs`：到第一次观测到非零键盘 inset。
- `firstFinalInsetMs`：最终 inset 高度第一次出现在原始序列的时间。
- `lastInsetMs`：最后一次带正数 inset 的 metrics 回调；可能包含高度不变的回调。
- `stableConfirmedMs`：最后 metrics 后额外等待 120 ms 的稳定确认，**不是动画时长**。
- `firstFrameBuildStartMs`：触摸后首个被记录帧的 build 开始，**不是屏幕呈现时间**。
- `buildUs` / `rasterUs` / `totalUs`：Flutter UI、raster 和帧总跨度，独立统计。
  汇总同时保存每轮 P95 的分布及全部帧合并分布，避免只合并帧导致帧多的轮次权重更高。
  `perSampleFrameSumMs` 另报同一 800 ms 窗口内每轮 build/raster/total 时长的累计值分布，
  `perSampleFrameCount` 和 `frameSamples` 保留每轮帧数与累计值。它们是观测到的
  Flutter 帧阶段时长之和，**不是 CPU 时间**，不同阶段也可能重叠，不能相加为墙钟耗时。
  前后帧数或廉价尾帧占比变化会影响 P95；评估时同时查看固定窗口累计 build 时长、
  每轮帧数及 `firstFinalInsetMs`，不能只凭 P95 下降声称输入法动画改善。
  8.333/16.667 ms 阈值占比仅用于对照，不据此声称实际 FPS。
  如任一 `firstFinalInsetMs` / `lastInsetMs` 达到 800 ms，汇总的
  `framesWindowPotentiallyTruncated` 会标记该组；此时不能称窗口覆盖了完整动画。

PointerDown 的基准是 Flutter 收到事件的回调时刻；原始 Android/engine pointer timestamp
也保留，但未核对时钟基准，不将两者之差解释成系统输入延迟。
`display.refreshRate` 是显示报告值，不保证每帧实际 vsync 间隔。
系统 `ImeTracker` 的 show→shown 可作为独立旁证，不能与 Dart tap→inset 混成同一指标。
VM 地址和访问 token 仅存于 mode 0600 的 `vm-connection-private.json`，不打印到结果。
每份结果嵌入安装 APK SHA-256、probe SHA-256 和 baseline 提交号。
driver 会核对安装记录与保留的 variant APK 哈希；`--label baseline_repeat` 自动使用
baseline 校验，其他自定义标签可通过 `--variant baseline|after` 显式选择。
