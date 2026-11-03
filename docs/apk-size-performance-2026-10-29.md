# APK 瘦身与启动速度对比（2026-10-29）

> 后续成品审计发现该版本存在 Gson 反射误裁剪，已补精确保留规则并安装修正版（36.738 MiB），同一套成品回归测试 25 项全部通过。本页保留原始体积和启动测量，不能作为功能验收结论；当前状态见[Release 裁剪兼容性审计](release-shrinking-audit-2026-10-29.md)。

按本轮“包体积优先”的交付选择，改用 **Flutter Release AOT + 原生 Release/R8 + 资源收缩 + DEX 压缩**，保留之前的 Launcher 立即结束和首帧准备好后移除系统启动页。最终 APK 从 **128.712 MiB 降到 36.728 MiB**，减少 **91.984 MiB / 71.5%**；同机本轮冷启动中位数从 **1024 ms 降到 474 ms**，缩短 **53.7%**。

这是从测量用 Profile 切换到完整 Release 的交付方案变化，不能把全部体积或启动收益归因于 DEX 压缩。Release 内部的单项对照表明：压缩 DEX 再节省 **8.201 MiB / 18.3%**，两批启动中位数相差 **3 ms**，不足以证明稳定的速度差别，因此最终采用更小的压缩包。

## 同机测量

| 构建方案 | APK 实际字节数 | APK 大小 | 有效次数 | 冷启动中位数 | 范围 |
|---|---:|---:|---:|---:|---:|
| 原交付 Profile，DEX 不压缩 | 134,964,115 | 128.712 MiB | 5 | 1024 ms | 1000–1047 ms |
| Release，DEX 不压缩，对照 | 47,111,929 | 44.929 MiB | 5 | 471 ms | 454–499 ms |
| **Release，DEX 压缩，最终选择** | **38,512,421** | **36.728 MiB** | **10** | **474 ms** | **453–502 ms** |

正式样本及每批第一次启动均保留，单位为 ms：

- Profile：`[1047, 1000, 1024, 1028, 1013]`；单列首开 `1057`。
- Release 不压缩：`[454, 499, 471, 468, 483]`；单列首开 `522`。
- Release 压缩：`[453, 502, 481, 485, 493, 476, 472, 466, 468, 470]`；单列首开 `472`。

设备为 PJD110、Android API 36，入口均为 `cn.com.omnimind.bot/.activity.LauncherActivity`。每次 `force-stop` 后等待 1 秒，使用 `am start -W TotalTime`，仅接受成功的 `COLD` 启动；每批首开作为预热单列。启动后 3 秒检查进程仍存活。未卸载或清空账号、历史和其他应用数据，系统磁盘页缓存保留。

这个指标是进程冷启动到 Activity 首次显示，不是首装时间、整机重启后的缓存全冷、网络/模型就绪或所有后台服务完成时间。三批开始和结束时 Thermal Status 均为 `0`；电池温度分别为 **31.9→31.9 °C、31.6→31.6 °C、31.6→31.7 °C**，未逐帧监测温控或固定 CPU 频率。样本量不同且未作随机交错测试，不能把 3 ms 的差异解释为确定的压缩性能代价。

此前报告中 1017.5 ms 是同一 Profile APK 的上一轮 10 次结果；本轮重新采集 5 次得到 1024 ms，没有把旧样本混入新比较。全部样本、温度、产物与验证信息见[原始 JSON](performance/apk-size-2026-10-29.json)。

## 体积从哪里减少

以下为 ZIP entries 在 APK 内的实际占用，MiB；不包括 ZIP 目录、签名与布局间隙：

| 类别 | 原 Profile | Release 不压缩 DEX | Release 压缩 DEX |
|---|---:|---:|---:|
| DEX | 91.363 | 14.516 | 6.315 |
| 原生库与 Flutter AOT（`lib/`） | 18.518 | 13.903 | 13.903 |
| assets | 14.179 | 13.710 | 13.710 |
| Android resources | 2.837 | 1.267 | 1.267 |
| 其他 entries | 1.336 | 1.337 | 1.337 |

主要变化是现有 Release R8/资源收缩真正参与交付：DEX 从 **40 个、原始 95,801,088 B** 降为 **2 个、原始 15,221,120 B**。Flutter Release 还缩小了 AOT/引擎库并去除 Profile 的 VM service 库。资源收缩和 Release 打包压缩进一步减少占用。由于 DEBUG 常量及 Flutter 构建模式也不同，不能将这几项合并成一个严格隔离的 R8 实验。

两份 Release APK 的全部 ZIP entries 解压后字节完全相同；单项对照只改变 DEX 压缩及 ZIP 布局。JNI 的打包设置保持原样，没有手工删除功能、模型、终端运行时或图片/字体资源，也没有通过移除现有持久化字段保护规则来缩包。

上轮未裁剪的约 91 MiB DEX 在启动前解压成本很高，因此选择不压缩；本轮 R8 后仅剩约 14.5 MiB 原始 DEX，重新测量后采用体积更小的压缩方式。这个选择基于本轮 Release 成品结果，不将上轮 Profile 的解压成本直接套用到 Release。

## 构建开关与复现

本轮唯一新增生产配置在 `app/build.gradle.kts`：`OMNIBOT_COMPRESS_DEX` 默认 `true`；可显式传 `false` 构建不压缩对照。Release 原本已有的 R8 和资源收缩保持启用，之前两项 Activity 启动优化保留。

日常轻量交付采用 Release。使用已有发布签名配置时，在仓库根目录构建默认压缩版本：

```sh
./gradlew --no-configuration-cache \
  :app:assembleDevelopStandardRelease \
  -Ptarget=lib/main_standard.dart
```

不压缩 DEX 的对照命令：

```sh
./gradlew --no-configuration-cache \
  :app:assembleDevelopStandardRelease \
  -Ptarget=lib/main_standard.dart \
  -POMNIBOT_COMPRESS_DEX=false
```

两次均输出 `app/build/outputs/apk/developStandard/release/app-develop-standard-release.apk`，构建后立即复制为不同文件以免覆盖。测量使用已有 `scripts/performance/startup/measure.py`，Release 传 `--mode release`，并在每批前检查安装 APK 哈希与本地产物一致。

本机验证使用临时 init script，仅在该构建进程中让 Release 使用已有 Debug 证书，以便覆盖安装并保留原有数据；两侧证书一致。仓库发布签名配置未改变，未读取或修改发布密钥。原始 JSON 保存实际构建设置；正式分发仍使用原有发布签名。

`scripts/install-dev.sh` 默认构建并安装 Debug，用于开发调试，不代表这里的日常轻量 Release 交付；本轮没有修改该脚本。

## 验证边界

- 最终选择为 `release-compressed.apk`，SHA-256：`e48f8130e054cae47a1f4317c55f73d3bdceb59d34520d9c4a31592e1adf21fe`。设备已安装 APK 的哈希完全一致，签名验证和 ZIP 检查通过，最终保留该压缩 Release。
- **37 项聚焦兼容性测试通过**：持久化 JSON 6 项、Provider 存储 10 项、ChatGPT 注册编解码 7 项、语音配置 6 项、Provider bootstrap 8 项。当前构建不提供所请求的 Release 单测任务，最初尝试未进入测试断言；随后在受支持的 Debug 变体运行。不能把这 37 项称为经过 R8 的测试。
- R8 成品另行核对：`AcpAgentProfile`、`StoredModelProviderProfile`、`ModelProviderProfile`、`SceneModelBindingEntry`、`SceneVoiceConfig` 五个关键持久化类的实例字段名集合与原 Profile 一致。实际 Release 的 10 次冷启动成功，检查到单一存活进程；致命异常、插件缺失、类/方法加载失败及插件注册失败的白名单错误计数均为 0。
- 最终截图确认正常首页和原有历史，未声称完成全部 UI 交互验收。上述 JVM 测试、成品字段检查与真机启动证据分别记录，不能相互替代。
- 设备原有 MMKV 以只读方式校验 CRC 并解析有效记录，确认目标旧 TTS 配置不存在，本次不触发读取时迁移；没有修改原数据。Release 编译后的官方服务地址为 HTTPS，既有端点安全策略保持不变。
- 本轮没有重试录屏，不声明已完成录屏或逐帧无闪白验收；启动数据仅代表上述设备和数据状态，不等于模型、语音、终端、后台任务或全部设备的功能全链路验收。

本报告接续[约 1 秒启动报告](startup-subsecond-2026-10-29.md)。本轮按用户最新包体积优先的要求，最终保留压缩 Release，而不再保留上一轮 Profile 作为日常安装版本。
