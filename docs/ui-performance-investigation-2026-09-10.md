# 主界面抽屉与设置返回掉帧排查

## 环境与范围

- 起点：本地 `main`，`9cf2e0bea`；工作分支 `codex/ui-performance-investigation`。
- Pixel 10 Pro ARM64 模拟器，Android 37.1，1280 × 2856，480 dpi。
- Flutter 3.47.2，实际 Android 宿主应用，Flutter Profile/AOT，Impeller OpenGLES。
- 模拟器最初使用 SwiftShader 软件渲染，Raster 单帧可达 100–300 ms。该组数据不用于评价应用性能。后续以 `-gpu host -no-snapshot-load -no-snapshot-save` 启动，日志确认使用 Apple M5。
- 操作包括菜单/内容侧滑打开抽屉、按键返回、从左边缘滑动返回，以及模型提供商、场景模型、外观返回设置、设置返回聊天。
- 模拟器没有实际会话历史；大量会话另用 300 条合成数据做 widget 诊断，不写入用户数据库。

## 已确认并修复：输入框边框动画扩大重绘范围

`chat_input_area.dart` 的 `_composerFlowController` 持续循环；`_ComposerFlowBorderPainter` 通过 `repaint` 每帧请求绘制。原来的边框没有自己的 `RepaintBoundary`，因此会把绘制失效传播到聊天页面的祖先边界。输入框内容已有的边界只包住输入内容，不能隔离它旁边的边框。

这会增加聊天主界面、抽屉覆盖期间以及聊天页面参与转场时的绘制工作。

本次变更：

1. 在边框 `CustomPaint` 外增加独立 `RepaintBoundary`，保持边框动画及外观。
2. 非毛玻璃模式设置 `BackdropFilter.enabled = false`，避免保留没有模糊效果的背景滤镜层。毛玻璃模式继续使用原有滤镜。

回归测试在输入框外放置实际绘制计数器，等初始布局稳定后推进 10 个动画帧：

| 实现 | 稳定后的外层累计绘制次数 | 再推进 10 帧后 | 新增外层重绘 |
| --- | ---: | ---: | ---: |
| 原 `main` | 3 | 13 | 10 |
| 本次修改 | 稳定后的基准值 | 与基准值相同 | 0 |

同一个测试在原代码上失败、修改后通过。这证明边框动画造成的外层重绘已经消除；并不代表抽屉全部掉帧或设置二级页的问题已经解决。

## 已确认、尚未修改：会话列表没有逐条虚拟化

`HomeDrawer` 使用 `ListView` 承载少数几个分组，而每个分组的日期、会话条目仍在嵌套 `Column` 中一次性生成。分组展开/折叠的 `Align(heightFactor)` 和 `Opacity` 也保留了子树。

在 360 × 720 的容器内加载同一天的 300 条会话，实际挂载了 **300 个会话标题 Widget**，包括屏幕外条目。诊断脚本与输出保存在下方本地产物目录。

此外，Flutter 的 `DrawerController` 在完全关闭后移除抽屉子树，重新打开会触发 `HomeDrawer.initState()` 的数据加载；`onDrawerChanged(true)` 也请求刷新。已有内存快照可以避免空白加载状态，但不能避免重新挂载整组条目。多会话场景应优先将分组展开状态投影成扁平行，用同一个惰性 sliver/list 构建可见条目，并合并重复加载请求。

该改动涉及置顶、定时任务父子分组、折叠动画、搜索、重命名焦点和滑动操作，未在本次初步排查中整体重写。

## 设置二级页返回：首轮 60 Hz 模拟器排查

设置、模型提供商、场景模型、外观等页面最后都进入 `PredictiveBackPage` / `_PredictivePageRoute`，共用 `PredictiveBackRouteMotion` 和 `PredictiveBackPageTransition`。设置返回聊天也使用同一个路由控制器与转场。当前代码没有为这两类返回单独使用不同的动画时长。

共用转场包括全屏平移、底层页面四分之一宽度视差、底层整体透明度、黑色遮罩和运动页面的 squircle 裁剪。内容复杂度及合成路径是后续重点，但仅凭这些组件的存在不能认定根因。

Profile 采样中按键返回通常很轻；手势返回有 Raster 尖峰。原代码的模型提供商手势返回样本，UI 峰值 1.36 ms、Raster 峰值 53.73 ms；对应的 `SurfaceFrame::Submit` 为 53.53 ms，说明该帧大部分时间位于渲染提交阶段，不能归因于 Dart 页面重建。

修复边框后的一轮相同左边缘手势采样如下。每个窗口为发出 adb 操作起 850 ms，含手势起始、拖动、松手及可能的静止帧；这些数值不是纯转场帧的 FPS 基准。

| 返回路径 | UI 峰值（ms） | Raster 峰值（ms） |
| --- | ---: | ---: |
| 场景模型 → 设置 | 1.01 | 27.75 |
| 模型提供商 → 设置 | 1.34 | 28.65 |
| 外观 → 设置 | 1.42 | 33.00 |
| 设置 → 聊天 | 1.51 | 32.56 |

这台模拟器上没有稳定复现“多数二级页返回明显更卡、设置返回聊天始终流畅”的差异。OpenGLES 提交/模拟器显示节奏的波动也会进入 Raster 计时，因此不以两轮峰值变化宣称本次修改提高了多少 FPS。

首轮 60 Hz 环境不能验证用户设备上 60/120 帧的差异。后续真机录屏和 120 Hz 对照发现了下述帧调度问题。

## 已复现并修复：静止页面的返回手势隔帧绘制

用户提供的[性能报告](https://omarea.com/pvp.html#/fps?id=1789050972641930)记录设备 RMX5200、Android 16、应用 0.6.2.1，整体平均 75.7 FPS、最高 120 FPS。录屏前半段反复拖动/取消“Agent 模式 → 设置”，浮窗约 60 FPS；后半段拖动“设置 → 聊天”，浮窗达到 120 FPS。

录屏解码得到 1,203 帧。在页面顶部、避开帧率浮窗的横向条带中，前 0–4.2 秒有 259/505 帧与上一帧近似相同，5–9 秒仅 12/480 帧近似相同（灰度平均绝对差 < 0.5/255）。这个粗略检查包含停顿及手势换向，但支持实际画面运动节奏有差异；视频标称 `r_frame_rate=240` 不能当作应用实际帧率。

把同一模拟器的 `hw.lcd.vsync` 和运行时刷新率改为 120 Hz 后，在 Profile/AOT 包中临时记录系统返回进度到达 Dart 的时间，并通过仅用于本地诊断的开关控制“拖动期间提前预约下一帧”。同一包、同一页面、相同 2.5 秒右边缘拖动，取 adb 命令开始后 0.3–2.2 秒的固定窗口：

| 路径 / 实验 | 进度回调频率 | Flutter 出帧频率 | 帧间隔中位数 | UI 工作中位数 |
| --- | ---: | ---: | ---: | ---: |
| Agent 模式 → 设置，原调度 | 113.7 Hz | 57.4 FPS | 16.72 ms | 0.22 ms |
| Agent 模式 → 设置，提前预约 | 117.4 Hz | 103.2 FPS | 8.36 ms | 0.31 ms |
| 设置 → 聊天，原调度 | 112.6 Hz | 92.6 FPS | 8.86 ms | 0.66 ms |
| 设置 → 聊天，提前预约 | 116.8 Hz | 93.7 FPS | 8.53 ms | 0.43 ms |

原调度下，二级页已收到接近 120 Hz 的进度，UI 工作也远低于 8.33 ms，但只每两次 vsync 绘制一帧。手势通过平台通道更新路由控制器；手指拖动时控制器自身不运行动画，静止设置页面依赖收到进度后才请求绘制，存在隔帧调度。聊天页的持续边框动画已经在连续预约帧，因此没有相同的 60 FPS 台阶。单独提前预约下一帧就消除了这一台阶，支持调度是这项差异的原因。

正式实现位于 `PredictiveBackRouteMotion`：开始拖动时使用 `SchedulerBinding.scheduleFrameCallback` 持续预约下一帧；手势提交、取消、路由销毁时取消预约，路由失去当前页身份时停止续约。原有路由控制器继续独占页面位置，松手后的动画由原控制器接管。临时采样点及诊断开关已移除。

新增测试验证静止页面在两次进度消息之间仍预约帧，且页面位置不会自行变化；取消、提交和销毁后没有遗留帧回调。测试在原实现上失败，修复后通过。与现有重抓手势、连续返回、拦截返回和抽屉交互共 26 项测试通过，修改文件静态分析通过，正式修复的 Profile APK 构建成功。

移除诊断代码后，已将正式 Profile 包安装回模拟器；相同窗口下 Agent 模式、模型提供商返回分别为 80.0 / 87.4 FPS，帧间隔中位数 9.02 / 8.55 ms，仍有模拟器渲染波动，但不再固定隔帧。最后一次返回完成后的 1 秒静止设置窗口记录到 0 帧，确认没有持续空转。模拟器分辨率、保存的 AVD 配置和刷新率偏好均在诊断后恢复。

限制：这是 Flutter 出帧计数，不能直接等同于 SurfaceFlinger 最终呈现帧数。1280 × 2856 的模拟器在高刷下仍有约 9–10 ms 的 Raster 中位开销，尚未稳定达到 120 FPS；降低运行时分辨率的额外样本与构建重叠，不用于结论。修复后仍需在用户的 RMX5200 上用相同手势和工具复测，不能宣称真机已经恢复满 120 帧。

本轮视频条带分析、原始时间线、临时诊断源码及回归失败记录位于 `build/performance/2026-09-10-device/`，不纳入版本控制。

## 验证与复现

- 原始 Debug Android APK 构建通过。
- 原始与修改后的 Profile Android APK 构建通过；修改后的 Profile 包已安装到模拟器。
- 输入框、聊天侧滑抽屉、预测性返回的 51 项测试通过。
- 修改涉及的 Dart 文件及测试静态分析通过。
- `git diff --check` 通过。

本地原始时间线、VM Service 采集脚本、绘制计数测试输出、300 条会话诊断脚本和临时 Gradle Profile 配置位于 `build/performance/2026-09-10/`（不纳入版本控制）。

Profile 构建使用临时 init script，复用 debug 签名和原生 debug 依赖；Flutter 根据名为 `profile` 的 build type 使用 AOT/Profile。无需更改发布签名配置：

```sh
./gradlew -I build/performance/2026-09-10/omnibot-profile.init.gradle \
  :app:assembleDevelopStandardProfile \
  -Ptarget=lib/main_standard.dart -Ptarget-platform=android-arm64

cd ui
flutter test --no-pub \
  test/features/home/pages/command_overlay/widgets/chat_input_area_test.dart \
  test/features/home/pages/chat/chat_content_swipe_drawer_test.dart \
  test/widgets/predictive_back_gesture_wrapper_test.dart
```

`omnibot-vm.dart` 通过 VM Service 收集 `Flutter.Frame` 与 Dart/Embedder 时间线。VM Service 地址每次启动都会变化，应从模拟器 `flutter` 日志读取并设置 adb forward；参数中的手势坐标也必须先通过布局或截图确认。`detail` 会开启额外的 Widget/RenderObject 跟踪，不能将它与未开启详细跟踪的运行直接作性能百分比对比。VM 时间线是环形缓冲，较长的详细采样可能只保留末尾事件；帧事件另行持续收集。
