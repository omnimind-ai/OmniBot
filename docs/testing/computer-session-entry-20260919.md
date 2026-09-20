# 电脑会话入口与协作方向（2026-09-19）

目标保持为 Codex 模式交互和电脑会话管理，不增加另一套 Agent 调度。已存在的共享 daemon / ACP 会话支持手机发起、电脑执行、双方接续；2026-09-18 真机证据见 codex-remote-demo-20260918.md，其中桌面 GUI 和重启恢复仍未验收。

本次检查发现 remoteOnly 未配对时只提供错误和重试。现在读取既有 Bridge 配置后显示“连接你的电脑”与“连接电脑”，导航到已有设置，返回后复用 _loadSessions 获取当前状态。没有新增连接协议、Agent loop 或重放。已配对但离线继续走既有错误和重试。

执行：ui 下 flutter test --no-pub test/features/home/pages/agent/agent_sessions_refresh_test.dart test/features/home/pages/agent/agent_sessions_page_test.dart，8 项通过。新增用例验证未配对入口，以及 6 秒内不激活运行时、不列举、不发送任务。导航返回后的真实配对尚未覆盖；不能用该测试宣称整条连接旅程通过。

本次 adb devices -l 无设备，待真机验证：未配对→连接→返回会话列表；离线重连；已有对话打开/新建/切换；重启后的会话恢复。整个目标仍未完成。

下一步优先审查：电脑身份与目录是否清晰；运行中状态是否来自 canonical ACP；新建/搜索/归档在侧边栏是否可发现；断线保持历史且不会重发。多设备调度暂不引入；先以同一会话的跨设备接续作为协作价值。

## 用户追加：远程模式关闭时隐藏电脑入口

入口现在由既有 `config/remote/read` 和 `config/remote/write` 的持久配置返回值驱动。AgentRuntimeService 仅向界面公开 remoteEnabled 的 ValueNotifier，未增加 Agent 协议、连接或轮询。HomeDrawer 订阅配置变化，关闭时隐藏整个本机/电脑切换栏、移除电脑列表并回到本机会话；保存过配对不等于开启远程模式。开启后显示入口，但点击电脑前不加载远程列表。

同时补充：电脑列表搜索标题或工作目录；搜索栏旁提供小型新建按钮，沿用 session/new，并经既有 onSessionSelected 交给聊天页。测试发现旧返回值解析只认 threadId/id，遗漏官方 sessionId，现优先读取 sessionId。新建等待期间按钮禁用，不重复创建、不自动发送 prompt。

三个测试文件共 30 项通过：agent_sessions_refresh_test.dart、agent_sessions_page_test.dart、home_drawer_test.dart。用例覆盖远程关闭/开启/再次关闭、配置读取不连接、目录搜索、单次新建并返回相同会话身份。新增已打开电脑列表后关闭模式的定向回归另行执行。所有均为 widget/MethodChannel 模拟，不能替代真机。仍无 ADB 设备，待真机验证。

## 配置读取乱序回归与构建

新增长期回归 `late remote read cannot undo a saved disabled sidebar mode`：先挂起读取 enabled=true，成功保存 enabled=false，再完成旧读取。修复前实际失败（入口状态恢复 true），修复后通过。现有配置服务仅给界面投影增加成功写入版本号，阻止旧读取覆盖较新的保存结果；未增加协议、连接或重试。

四个相关 Flutter 文件共 95 项通过（增加 agent_runtime_service_test.dart），日志 `/tmp/computer-mode-regression-20260919.log`。2026-09-19 ADB 仅列出 emulator-5580，mDNS 无真机；未操作他人模拟器。仍待物理设备验证开关、侧边栏返回和冷启动。

本轮 assembleDevelopStandardDebug 失败（1m50s）：compileDevelopStandardDebugKotlin 无法解析 baselib 多个类型，日志 `/tmp/computer-mode-build-20260919.log`。磁盘上的 baselib classes.jar 包含 DatabaseHelper 和 BaseApplication，不能直接认定源码依赖缺失。检查发现同一工作区另一 Gradle 进程 PID 95528 正在执行 TaskRuntimeTest、RemoteCodexAppServerSessionTest 和 assemble；未中断、清理或覆盖它的产物。并发构建是否导致失败尚未证实；应在该进程结束后独立复验。此轮未交付新 APK。

独立复验构建通过：BUILD SUCCESSFUL in 1m35s，日志 `/tmp/computer-mode-build-isolated-20260919.log`。安装包 `app/build/outputs/computer-mode-20260919/OmniBot-computer-mode-debug.apk`，SHA256 `c96d4e2375b44cdef4258ab1ebfa03732b48c44fd0fa4d3a7821eebac69bde55`。没有设备安装或真机验收；上次类型解析错误未重现，原因未定。home_drawer_test.dart 已加入 scripts/test-agent-runtime.sh，bash -n 通过。
