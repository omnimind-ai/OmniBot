# 第三轮启动优化：约 1 秒冷启动与启动页退场（2026-10-29）

这是第三轮的历史记录；用户随后改为包体积优先，当前 Release 交付与最新对比见[安装包精简报告](apk-size-performance-2026-10-29.md)。

同一台 PJD110（Android API 36），相同 Flutter AOT/Profile 配置下，最终交付候选 `delivered-profile.apk` 的进程冷启动中位数从 **1266.5 ms 降至 1017.5 ms**，缩短 **249 ms / 19.7%**。最终 10 次样本范围为 **995–1077 ms**，只有 **2/10 次严格低于 1000 ms**。这支持“约 1 秒启动”，不支持“稳定低于 1 秒”或所有设备都能秒开。

本轮还取消了首帧准备好之后的系统启动页退场动画。独立 trace 中，基线和只修改 DEX/Launcher 的中间候选均有 43 条径向退场动画绘制记录，首尾跨度约 347 ms；最终候选没有这些记录。该动画消失与 `am start -W TotalTime` 是不同证据，不能把约 350 ms 再从最终启动时间中扣除。

## 最终比较与测量边界

| 相同 AOT/Profile 模式 | 有效次数 | 中位数 | 最小值–最大值 | 严格小于 1000 ms |
|---|---:|---:|---:|---:|
| 基线：`before-profile` 两批合并 | 10 | 1266.5 ms | 1229–1355 ms | 0/10 |
| 最终：`delivered-profile` | 10 | 1017.5 ms | 995–1077 ms | 2/10 |

- 基线源码为 `9a1d76878`；本轮最终仅有三项生产改动：DEX 打包方式、Launcher 结束时机和 MainActivity 的系统启动页退场回调。
- 两侧使用相同包名、Debug 签名和 `cn.com.omnimind.bot/.activity.LauncherActivity` 入口，保留账号、主题、历史与其他应用数据。没有卸载或清空数据。
- 每次先 `force-stop`，等待 1 秒，然后 `am start -W`；只接受 `Status: ok`、`LaunchState: COLD`。启动后再等 3 秒检查进程并提取白名单计时日志。每批第一次启动作为预热单列，后续所有有效样本保留。
- 指标是 Android `TotalTime`，即进程冷启动到 Activity 首次显示。它不等于启动页全部可见动画结束、网络/模型请求完成或所有后台服务就绪。系统磁盘页缓存保留，不是整机重启后的存储全冷测试。
- 本轮各批测量开始和结束时 Thermal Status 均为 `0`，电池温度为 **32.3–33.2 °C**。最终批次为 **33.1 → 33.2 °C**，两批基线分别为 **32.5 → 32.5 °C**、**32.3 → 32.3 °C**。脚本只采集批次前后状态，没有逐帧监测温控或锁定 CPU 频率。
- Profile 使用 Flutter AOT，APK 内含 `libapp.so`、不含 `kernel_blob.bin`；原生仍继承 Debug，`debuggable=true`，未启用 R8 或资源收缩。没有安装和测量完整 Release，不能把本结果称为 Release 实测，也没有把 Debug → AOT 的差异算作本轮收益。
- 正式时间样本与 atrace 诊断分别采集，trace 的单次时间不混入上述中位数。此结果仅覆盖这台设备与本次数据状态。

## 全部时间样本

以下单位均为 ms，按实际测量顺序保留七个批次。文件名中的 `final-profile` 是中间候选，**实际交付候选是 `delivered-profile`**。

| 原始 JSON | 版本内容 | 正式样本 | 中位数 | 安装后本批首开，单列不计入 |
|---|---|---|---:|---:|
| `before-profile.json` | 基线 | `[1252, 1258, 1293, 1275, 1319]` | 1275 | 1317 |
| `after-profile.json` | 仅 DEX 不压缩 | `[1017, 1055, 1018, 1028, 1052]` | 1028 | 1065 |
| `before-profile-recheck.json` | 回装同一基线 APK | `[1292, 1229, 1254, 1355, 1250]` | 1254 | 1290 |
| `final-profile.json` | DEX 不压缩 + Launcher 立即结束 | `[1039, 991, 977, 991, 989, 987, 1007, 974, 958, 1037]` | 990 | 1068 |
| `loader-profile.json` | 上述候选 + Flutter loader 预加载试验 | `[1071, 1047, 1026, 1006, 1063, 1022, 1014, 1012, 996, 1013]` | 1018 | 1040 |
| `final-profile-recheck.json` | 撤回 loader 试验，回装同一中间候选 | `[998, 1027, 1019, 1017, 1019]` | 1019 | 1001 |
| `delivered-profile.json` | 最终三项改动，增加即时移除系统启动页 | `[1002, 1023, 1019, 995, 1016, 997, 1077, 1020, 1037, 1007]` | **1017.5** | **1056** |

两批基线合并为 10 次，重新计算中位数 1266.5 ms，未使用上轮报告的旧基线。中间候选的 990 ms 不是最终交付时间；它回测为 1019 ms，两批 15 次合并中位数为 998 ms。Flutter loader 预加载没有显示稳定收益，最终已撤回。候选之间存在时间漂移，不能从单批最好结果宣称每一项改动都有独立的确定收益。

[原始七批测量、产物信息与 trace 摘要](performance/startup-subsecond-2026-10-29.json)保留全部样本，不剔除较慢试次。

## 改动与瓶颈证据

### 1. 取消 DEX ZIP 压缩

`app/build.gradle.kts` 将 `packaging.dex.useLegacyPackaging` 从 `true` 改为 `false`；`jniLibs.useLegacyPackaging=true` 保持不变。当前 `minSdk=29`，该设置恢复 AGP 在 API 28 及以上默认采用的不压缩 DEX 方式，使 ART 可以直接映射对齐的 DEX，消除本次冷启动中测到的运行时解压开销。

配对诊断仅比较基线与 DEX 单项改动，均为独立 Profile trace：

| 主线程阶段 | 基线 | 仅 DEX 不压缩 |
|---|---:|---:|
| `Extract dex file` | 40 次，合计 297.862 ms | 0 次 |
| `Verify dex file` | 40 次，合计 202.902 ms | 40 次，合计 197.282 ms |
| `OpenDexFilesFromOat` | 502.699 ms | 199.215 ms |

这定位到 `Application.onCreate` 之前的真实成本。解压消失，但 DEX 验证仍存在；没有移除验证步骤，也没有把整个原始 DEX 加载阶段算作可消除成本。最终候选独立 trace 同样没有 `Extract dex file`，其验证合计为 194.072 ms，`OpenDexFilesFromOat` 为 196.013 ms。

基线与 DEX 单项候选的成品 ZIP 解压后各 entry 内容逐字节相同，40 个 DEX 的压缩方法从 DEFLATED（8）变为 STORED（0），不压缩 DEX 的数据偏移全部满足 4 字节对齐；JNI 打包方式未改变。这比仅比较源码快照更直接地证明打包差异。本轮未新增 `android:useEmbeddedDex=true`：它会改变 ART 使用 AOT/JIT 的策略，并非取消 ZIP 压缩的必要条件。

- [AGP DEX packaging 定义与 API 28+ 默认行为](https://developer.android.com/reference/tools/gradle-api/9.3/com/android/build/api/dsl/DexPackaging)
- [ART 对未压缩且对齐 DEX 的直接映射实现](https://android.googlesource.com/platform/art/+/refs/heads/main/libdexfile/dex/dex_file_loader.cc)
- [useEmbeddedDex 与 ART 编译策略](https://developer.android.com/privacy-and-security/security-dex)

### 2. Launcher 转发后立即结束

`LauncherActivity` 仍是原有组件，继续接收并转发启动 Intent，并保留旧 QuickLog 入口分支。普通启动在 `startActivity` 后立即 `finish()`，移除原来等到 `onStop` 才结束的路径，避免无内容的 Launcher 继续走显示/恢复过程。

此处没有替换为 activity-alias，没有修改 Main 的 launch mode，也没有改写旧 PendingIntent 的目标。中间候选和回测结果均保留，最终结论使用最后的实际交付 APK。

### 3. 首帧准备好后立即移除系统启动页

`MainActivity` 仅在 API 31 及以上注册原生 `splashScreen.setOnExitAnimationListener`，在系统交接启动页退场时立即调用 `remove()`。Flutter 原有首帧绘制门控仍在；这项改动针对首页首帧准备好之后的额外退场动画。

在完整系统 trace 中搜索 `SplashScreenExitAnimationUtils$RadialVanishAnimation`：

| 单次独立 trace | 径向动画绘制记录数 | 第一条至最后一条的跨度 |
|---|---:|---:|
| `before-profile.trace` | 43 | 347.149 ms |
| `final-profile.trace`，尚未取消动画 | 43 | 346.996 ms |
| `delivered-profile.trace` | 0 | 不适用 |

这些是系统动画绘制事件，并非 43 个额外应用首帧。它们证明本机这次采集中径向退场动画已消失，不代表完整用户可见时长的独立统计。最终截图确认正常首页和原有历史；录屏未成功，因此不声明已完成整个启动过程的无闪白验收，不能用 `TotalTime` 或事件缺失替代该项检查。

## APK 体积与产物身份

| 产物 | 实际字节数 | 实际大小 |
|---|---:|---:|
| `before-profile.apk` | 86,669,253 | 82.654 MiB |
| `after-profile.apk`，仅 DEX 不压缩 | 134,767,229 | 128.524 MiB |
| `delivered-profile.apk`，最终三项改动 | 134,964,115 | **128.712 MiB** |

最终 APK 比基线增加 **46.058 MiB**。128.524 MiB 属于中间候选，不能用作最终体积。对于没有额外传输压缩的裸 APK 下载和 ADB 传输，这会增加实际传输量；设备安装后的总磁盘占用未作成对测量。此 Gradle DSL 不控制从 AAB 生成 APK 时的 DEX 压缩。

- 基线 SHA-256：`8dd96eb8a1a2dfbca2060cf96f1076572d5e6f16836dc3b0d57ada24a641b938`
- 最终 SHA-256：`f344fb4a599816c76f7538c7cfe3de2c84c78f805a34b124b62113ab1d51fa8a`

测量脚本在每批开始前检查安装的单体 `base.apk` SHA-256 与指定本地产物一致。最终设备再次核对的哈希与上述最终 SHA-256 一致，`apksigner verify`、`zipalign -c 4` 均通过；五个候选 APK 的 40 个 DEX 哈希、压缩方式和对齐信息随原始 JSON 归档。设备最终保留已测量的 `delivered-profile.apk`。

## 复现方式

本轮打包修改发生在 Gradle 配置中。旧 `prepare.py`/`snapshot.init.gradle` 仅隔离 Flutter 与部分原生源文件，不能单独冻结本次打包差异。复现时需分别准备基线 `9a1d76878` 与仅包含最终三项改动的源码，保存实际构建配置、构建命令和 APK；不要在含有未保存修改的工作区直接反复切换版本。

使用临时 init script 创建测量用 Profile，不修改仓库发布签名或读取发布密钥。原始 JSON 保存了实际使用的完整临时 init，其中还临时覆盖过 Release 签名，但本报告没有安装或测量 Release。以下复现片段仅保留 Profile 所需设置，无须改动 Release 签名：

```groovy
// 保存到 /tmp/omnibot-startup-profile.init.gradle
gradle.beforeProject { p ->
    p.plugins.withId('com.android.application') {
        if (p.path == ':app') {
            p.extensions.getByName('androidComponents').finalizeDsl { android ->
                def profile = android.buildTypes.maybeCreate('profile')
                profile.initWith(android.buildTypes.getByName('debug'))
                profile.matchingFallbacks = ['debug']
                profile.debuggable = true
                profile.minifyEnabled = false
                profile.shrinkResources = false
            }
        }
    }
}
```

在每份已固定的源码目录中构建，并立即把产物复制到不同的证据目录，防止下一次构建覆盖：

```sh
./gradlew --no-configuration-cache \
  -I /tmp/omnibot-startup-profile.init.gradle \
  :app:assembleDevelopStandardProfile \
  -Ptarget=lib/main_standard.dart
```

产物路径为 `app/build/outputs/apk/developStandard/profile/app-develop-standard-profile.apk`。对每份成品检查 Flutter 编译模式、DEX 压缩/对齐、签名和哈希，再安装测量：

```sh
adb -s <serial> install -r <artifact>/profile.apk
python3 scripts/performance/startup/measure.py \
  --serial <serial> --apk <artifact>/profile.apk \
  --output <artifact>/profile.json --label <variant> \
  --mode profile --runs 10 --warmups 1
```

前后使用相同的屏幕、电源、网络和应用数据条件。按基线、候选、回装基线的顺序检查时间漂移，最后装回已经测量的最终候选。独立 trace 使用 `am wm view dalvik res binder_driver sched freq` 类别和应用包名，保存应用 PID 后只统计目标进程主线程 DEX 阶段；系统退场动画则在全系统 trace 中检查。不要保存无关完整 logcat 或凭据。

## 验证状态与边界

- 七批测量 JSON 的样本、中位数、预热与温度已交叉核对；最终 10 次均成功冷启动，并在启动后 3 秒检查到单一存活应用进程。
- DEX 单项成品差异和配对 trace 已核对；最终候选的独立 trace 已确认无 DEX 解压及无上述径向退场动画记录。
- 最终成品 DEX 的压缩/对齐、签名和设备留存哈希已核对通过；不能将 DEX 单项的“全部 entry 相同”结论套用于包含 Kotlin 修改的最终 APK。
- 最终截图 `delivered-home.png` 已确认正常首页和原有历史，MainActivity 获得焦点。截图仅保留本地，未将历史内容放入仓库。
- 系统录屏的文件写入被拒绝，stdout 模式停滞且没有可用视频；未完成整个启动过程的录屏验收，不能声明逐帧无黑屏/闪白。API 31/32 未作实机覆盖。
- 录屏验证遗留的 shell `screenrecord` 进程处于等待状态；设备拒绝了针对该进程的 SIGINT 和 SIGKILL 清理。没有修改设备权限或结束系统录屏应用，可在方便时重启手机清理。本轮正式启动测量在录屏尝试之前完成。
- 这是启动链路验证，不等于远程模型、语音、终端、后台任务和全部设备的端到端验收。

原始七批数值、APK 信息、成品校验、生产 diff 与实际临时 init 已归档到配套 JSON。原始 trace 和截图仅在本地证据目录保留，仓库只保存必要的统计摘要。

本报告接续[第二轮启动优化报告](startup-performance-2026-10-29.md)。旧报告中的 1.287 秒 Profile 中位数、Debug 最终安装状态和当时验证记录属于上一轮历史；本轮采用新采集的 AOT/Profile 基线与最终交付候选，不混用样本。
