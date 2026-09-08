# 0.6.2.2 发布候选

Android versionName：0.6.2.2；versionCode：14。准备进入现有四段版本号的预发布渠道，尚未发布。

## 更新内容

- 输入框上下文圈暂无数据时默认画空环，不再显示问号；保留真实 usage 与预算语义。兼容验证包已覆盖安装到 emulator-5560，空环、长按及重启检查通过；该包不包含主线新依赖升级，见 [记录](testing/context-ring-zero-2026-09-09.md)。

- 修正精简历史确认卡的提示：优先展示已有允许/拒绝等结果，失效请求显示不可操作说明；不再显示 requestId 内部错误，也不提示继续回复。兼容基线测试通过，当前主线与设备验收仍待执行，见 [记录](testing/historical-request-notice-2026-09-09.md)。

- 修复首页对话列表整组构建全部历史行的问题，改为按可见范围构建；300 条历史的首屏挂载回归由 300 行降至少于 30 行，完整历史仍可滚动访问。设备帧率改善待真机测量。

- 小万在每次模型请求前维护上下文预算，覆盖当前任务增长；修复摘要输入预算、工具 schema 固定开销及服务商超限错误识别。允许的超限恢复在同一任务内最多执行一次，已开始输出的失败请求不重放。
- 大工具结果与长历史采用有界读取和完整文件引用，减少历史恢复、请求构造及界面持久化中的大数据复制；保留当次原图输入和原始历史。
- 自动摘要等待对应已完成工具记录提交后保存检查点；修复异步历史加载覆盖实时回复、快照清空任务身份和内部服务异常后等待不结束的问题。
- 整合文件读取、上下文阈值、历史与记忆恢复等此前本地修复；小万命令菜单遵循实际声明的能力。
- 修复定时任务创建身份、删除失败结果、更新已删除任务及禁用任务重启持久化；终端会话与已安装技能增加执行和恢复回归。
- 增加统一 40 步长上下文测试入口，覆盖原图读取、连续长任务、实际自动摘要及重启恢复；测试数据可重复生成，旧运行证据不能充当新测试成功。

## 验证与限制

历史整合验证见 [整合记录](testing/integration-0.6.2.1-2026-09-08.md)，最终上下文代码验证见 [上下文记录](testing/context-six-audit-2026-09-08.md)，40 步入口见 [执行说明](testing/xiaowan-context-40.md)。这些记录对应各自 APK，不冒充本次 0.6.2.2 包验收。

- 本次版本：1011 项 Android JVM 测试通过，无失败或跳过；1186 项 Flutter 测试通过；80 项 Node 与 7 项 Python 测试通过；debug APK 构建成功。执行命令见下方，不包含本次 APK 的设备操作验收。
- 待真机验证；与 0.6.0.3 的同任务完整能力对照尚未完成。
- 用户新反馈的 2026-09-08 22:35:04 `UncaughtException` 尚缺完整堆栈，未复现、未修复。`Parent job is Cancelling` 本身不能确定原始崩溃原因。
- DSH 默认沙箱仍未通过验收；本版不宣称解决该问题。
- 定时任务已有 CRUD/重启证据，不能替代定时触发后台 Agent 的完整验收。

## 集成基线

2026-09-09 实际重新获取并合并 `origin/main`（45d606b07），生成合并提交 `cccea05e2`，无文本冲突；以该合并事实为准，下方 09-08 的祖先判断不作为本次验证依据。引入主线 Flutter/AGP 依赖升级及更新服务社区二维码等变更，保留 0.6.2.2（14）和本分支修复。

合并后 80 项 Node、7 项 Python 及更新 Worker 19 项测试通过。`flutter pub get --enforce-lockfile` 实际失败：本机 Dart 3.9.2，合并后要求 ^3.13.0；主线指定 Flutter 3.47.2。当前 Android SDK 也尚无主线所需 API 37 平台。合并后的 Flutter/Android 构建和设备回归尚未重新完成，不能沿用合并前的 APK/hash 与测试结果作为新候选验收；需先准备兼容工具链。

2026-09-08 获取的 `origin/main` 为 `45d606b07`，已是 `codex/integrate-local-fixes` 的祖先，合并检查返回 `Already up to date`。保留全部整合提交，包括 `01fd832f6` 和 `8496e2adb`。未强制覆盖本地与远端指向不同的旧标签。

## 本次执行入口

后续列表修复后的最新候选包 SHA-256 为 `20d43f96dbedf90156afde229a3a295f757acff23ea6e10380580bbe581fccbe`，全量 Flutter 1187 项通过；模拟器列表打开、重开和重启入口检查通过。该结果更新下方早先候选包的状态，不代表新的完整 40 步设备验收，详见 [列表修复记录](testing/drawer-lazy-2026-09-08.md)。

```sh
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
cd ui
flutter test --no-pub
```

脚本检查使用 `bash scripts/test-agent-runtime.sh --offline --skip-gradle --skip-flutter --skip-webchat`，本次先运行既有 77 项 Node 与 7 项 Python，再单独运行 `node --test scripts/verify-xiaowan-context-suite.test.mjs` 的 3 项；该 3 项现已加入日常脚本，下次无需单独运行。

本机 JDK 使用 Android Studio JBR，Flutter 使用现有本地 SDK。Gradle 有既有弃用警告。本次 APK 元数据确认 `0.6.2.2` / `14`，SHA-256：`e8c094fad13c1c99c4672d745292e9a496256688905bf26983a422562afa41ad`。这是 debug 候选包，不是正式签名发布产物。当前仅发现模拟器 `emulator-45562`，未安装本包；既有 40 步设备证据对应上次整合包，新候选包设备回归待执行。
