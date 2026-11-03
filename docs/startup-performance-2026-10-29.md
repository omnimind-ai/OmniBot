# 第二轮启动优化与真机对比（2026-10-29）

这是第二轮的历史记录；当前交付的 AOT 版本与第三轮结果见[约一秒启动优化报告](startup-subsecond-2026-10-29.md)。

本轮同一台 PJD110（Android API 36）实测：Debug 冷启动中位数从 **2.636 秒降至 2.495 秒**，缩短 **5.4%**；Profile 从 **1.363 秒降至 1.287 秒**，缩短 **5.6%**。两个构建模式分别比较，未把 JIT 换成 AOT 的差异计入优化收益。

| 模式 | 优化前中位数 | 优化后中位数 | 缩短 |
|---|---:|---:|---:|
| Debug（Flutter JIT） | 2.636 秒 | 2.495 秒 | 141.5 ms / **5.4%** |
| Profile（Flutter AOT） | 1.363 秒 | 1.287 秒 | 76 ms / **5.6%** |

## 测量边界

- 两边每种模式均为 10 次有效冷启动。基线分两轮各 5 次，最终版一次 10 次；每次安装后的第一次启动单列为预热，原始值仍保留。没有删除异常慢样本。
- 使用相同签名、包名、LauncherActivity 入口、主题、账号和历史数据。每次 force-stop，等待 1 秒，am start -W；未卸载或清空数据。记录的 TotalTime 是 Activity 首次显示时间，不等于网络、模型、终端或全部后台服务完成时间。
- 冻结两组各 828 个 Flutter/app/baselib 源文件，只有本轮 12 个生产文件不同。其他工作区修改在两组中完全相同。第二次优化继续使用共同快照，并校验全部基线源码哈希一致后复用基线 APK。安装后再次验证设备 base.apk SHA-256 与测量 APK 相同。
- Debug APK 含 kernel_blob.bin，Profile 含 libapp.so；Profile 沿用 Debug 原生配置并使用 Flutter AOT，不代表完整 Release/R8 构建。
- 两边测试期间系统 Thermal Status 均为 0。Debug 回装基线出现约 100 ms 漂移，因此合并两轮全部样本报告；这是单设备结果，不外推其他机型。

## 原始样本与阶段耗时

### Debug

- 优化前（ms）：`[2551, 2553, 2572, 2611, 2560, 2665, 2793, 2661, 2665, 2698]`
- 优化后（ms）：`[2499, 2554, 2430, 2494, 2571, 2507, 2490, 2495, 2476, 2477]`

| 阶段中位数 | 优化前 | 优化后 |
|---|---:|---:|
| Credentials and providers cost | 284.5 ms | 201.5 ms |
| App onCreate total cost | 340.5 ms | 262 ms |
| Bootstrap to first rasterized frame | 1039 ms | 986 ms |

- before 安装后首开（不混入常规冷启动中位数）：[3372, 3450] ms。
- after 安装后首开（不混入常规冷启动中位数）：[3299] ms。

### Profile

- 优化前（ms）：`[1384, 1347, 1368, 1334, 1367, 1358, 1406, 1337, 1403, 1330]`
- 优化后（ms）：`[1250, 1279, 1301, 1294, 1271, 1256, 1306, 1247, 1308, 1296]`

| 阶段中位数 | 优化前 | 优化后 |
|---|---:|---:|
| Credentials and providers cost | 284.5 ms | 206 ms |
| App onCreate total cost | 340.5 ms | 268 ms |
| Bootstrap to first rasterized frame | 133 ms | 131 ms |

- before 安装后首开（不混入常规冷启动中位数）：[1330, 1351] ms。
- after 安装后首开（不混入常规冷启动中位数）：[1307] ms。

阶段计时可能重叠，不能相加得到 TotalTime。未使用显示空壳页面后再加载首页的办法；正常首页与历史列表仍在首屏。

## 改动

1. 应用启动只登记自定义 TTS 凭据存储的 Context，第一次实际使用才打开并完整校验加密文件。账号身份与 Provider 安全迁移仍同步完成；打开失败仍保持进程内不可用。语音配置读取转到 IO，回调留在主线程，并复用配置 owner 的锁保护旧 curl 迁移。
2. Provider 凭据原始存储值完全一致时跳过加密重写与同步落盘；损坏 JSON、空值残留和真实修改仍执行原清理、commit 和读回验证。
3. 取消每次启动都预热终端；现有终端页面、命令、自启动任务和后台会话入口仍由原 owner 保证环境就绪。首次需要终端时仍须准备环境。OAuth、账号、平台模型和运行时目录的 HTTP client 按需创建，保留超时、重定向与安全设置。
4. Flutter 系统 UI 设置与存储读取并行，两者均结束后才读取主题并 runApp；各自保留失败处理。主题色生成直接传入覆盖值；玻璃 fallback 跳过不会使用的 shader 参数，仍保留捕获、模糊、边缘和完整缓存失效。

## OpenMinis 源码参考

检查官方 OpenMinis main 的固定 revision `1a71df0f381cefde80bf912b723de6f9831bc177`。其 Application 注册 sandbox 单例时不解压 rootfs；安装函数单独在 IO 执行。这一职责划分用于本轮终端与可选凭据的按需初始化。没有复制其业务实现，也没有对 OpenMinis APK 做速度排名。

- [MinisApp.kt：sandbox 注册与后台模型索引](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L640-L680)
- [RootfsManager.kt：独立的 installIfNeeded](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt#L40-L85)
- [AndroidManifest.xml：alias 直接指向 MainActivity](https://github.com/OpenMinis/OpenMinis/blob/1a71df0f381cefde80bf912b723de6f9831bc177/src/android/app/src/main/AndroidManifest.xml#L219-L270)

也测试了直接启动 Main 的入口：早期 Profile 探针中位数 1.233 秒，同期 Launcher 基线 1.321 秒。但改成同名 alias 会改变升级前旧小组件 PendingIntent 的 singleTask 返回栈行为，因此未采用；原入口保持兼容。探针不计入最终优化数据。

## 验证与复现

- Flutter 聚焦测试 23 项通过：主题完整颜色等价、11 类玻璃绘制输入变化与缓存、启动准备并发/完成顺序/失败处理。相关文件 analyze 通过。
- 原生去重 85 项通过：账号/API、Provider、OAuth、加密写盘、懒初始化并发/失败、语音配置与终端。终端环境有一项需要 Java 21；JDK 17 的 class-version 错误在基线上复现，换用本机 JDK 21 后通过。
- Debug 与 Profile APK 均构建成功；真机反复启动无进程退出，截图核对正常首页。此验证不代表远程模型、真实语音或所有终端命令的端到端验收。
- [可复测脚本与命令](../scripts/performance/startup/README.md)
- [全部试次、阶段计时、温度、APK 与源码哈希](performance/startup-2026-10-29.json)

最终设备保留本轮已测量的优化后 Debug APK。未提交或推送代码。
