# Android 冷启动成对测量

`measure.py` 测量 `am start -W` 的 `TotalTime`：进程已结束，到 Activity 首次显示。
这不是整机重启后的存储冷缓存，也不是首次安装/网络/模型请求完成时间。
默认先执行 1 次安装后预热，再测 5 次，每次 `force-stop` 后等待 1 秒。
不卸载、不清空应用数据；会停止当前进程及其正在运行的任务。

## 冻结源码

在仓库根目录执行 `prepare.py --output /tmp/<新目录> --change <文件> ...`。
`--baseline` 默认 `HEAD`；只列出本次优化改动的生产文件，且这些文件在优化前必须与该 revision 相同。
工具复制当前 Flutter lib、app/baselib 的 Java/Kotlin 源码到 before/after，
然后只将 before 中指定文件替换成基线内容；两边保留相同的其他工作区修改。
`sources.json` 保存逐文件 SHA-256，并验证两份源码仅在指定文件上不同。
追加一轮优化时可用 `--common-snapshot <已有variant>` 固定其他源码，只从工作区更新 `--change` 文件；
复用旧基线 APK 前必须确认新旧 `sources.json` 的 before 全部哈希相同。

其余模块、资源、Gradle 配置、依赖须在构建两份 APK 期间保持不变。
工具不修改当前源码、不 checkout，也不写 git 状态。

## 构建

分别将 `<variant>` 换成 `before` 和 `after`，`<snapshot>` 换成绝对快照目录：

```sh
./gradlew -I scripts/performance/startup/snapshot.init.gradle \
  :app:assembleDevelopStandardDebug :app:assembleDevelopStandardProfile \
  -Pkotlin.incremental=false \
  -PstartupSnapshot=<snapshot>/<variant> \
  -Ptarget=<snapshot>/<variant>/ui/lib/main_standard.dart
```

每次构建后将两个 APK 复制到各自快照目录，避免下一次构建覆盖：

```sh
cp app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk <snapshot>/<variant>/debug.apk
cp app/build/outputs/apk/developStandard/profile/app-develop-standard-profile.apk <snapshot>/<variant>/profile.apk
```

只为这次源码路径切换禁用 Kotlin 增量编译，避免旧 source root 的 class 缓存残留。
不改变仓库 Gradle 默认值。Profile 沿用 debug 的包名、签名和原生配置，Flutter 使用 AOT；
仅将 Profile 与 Profile 对比，不能把 Debug → Profile 算作代码优化收益。

## 安装与测量

```sh
adb -s <serial> install -r <snapshot>/<variant>/debug.apk
python3 scripts/performance/startup/measure.py \
  --serial <serial> --apk <snapshot>/<variant>/debug.apk \
  --output <snapshot>/<variant>/debug.json --label <variant> --mode debug
```

Profile 使用相同命令，将文件名与 `--mode` 改为 `profile`。
程序检查设备安装的 base APK 哈希与 `--apk` 相同，只接受 `LaunchState: COLD`。
输出保留全部试次（预热明确标记）、中位数、范围、温度、热状态和白名单启动计时；
不保留完整 logcat 或凭据。不要改变主题、账号、历史、网络、设备电源/屏幕状态。

建议先 before，再 after，再回装 before 验证时间漂移，最后装回 after。
所有有效样本均应报告，不要只选择最快的一轮。安装后首开单列，不能混入常规冷启动中位数。
