# 远程连接初始化与重连边界 — 2026-09-11

用户要求先修复网络断线后重连，再测试跨网隧道。本轮按当前远程 Codex 上下文定位；未证明涵盖小万本地 Provider 断流问题。

## 已复现

RemoteCodexAppServerSessionTest 新增两项先失败：Socket 打开、initialize 未完成时 isRunning 错误为 true；Bridge start 抛错后没有 close。2026-09-11 聚焦 Gradle 首轮 5 项中 2 项失败，日志 /tmp/oob-reconnect-red.log。前者使 AgentRuntimeManager 的 isActiveSessionFor / ensureConnectedSession 可提前接受未就绪连接；后者遗漏启动阶段清理。

## 修改

沿用 Conversation -> ACP Session -> Turn -> Item。仅现有 RemoteCodexAppServerSession 管理连接就绪与清理：初始化和 initialized 通知成功后才就绪；握手、初始化失败或取消统一释放连接；退出清空协商结果；旧 transport 的 stdout 回调按连接身份隔离。没有新增轮询、自动重发、Agent 生命周期或断线续跑机制。电脑 Bridge 断线杀进程的行为没有在本轮改动。

## 可执行回归

已有 scripts/test-agent-runtime.sh 追加 RemoteCodexAppServerSessionTest。新增用例覆盖初始化中的未就绪、失败后显式再次连接、运行中请求断线失败、重连不重发旧 prompt、迟到消息和退出不影响新连接。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests '*RemoteCodexAppServerSessionTest' --tests '*RemoteCodexBridgeConnectionTest' :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart -Pflutter.sdk=/tmp/oob-flutter-3.47.2
```

最终 7 项通过，0 失败/错误；debug 构建通过，日志 /tmp/oob-reconnect-final.log。测试使用连接替身，不是真实网络或模型验收。

APK: app/build/outputs/remote-reconnect-20260911/OmniBot-0.6.2.3-remote-reconnect-debug.apk
SHA256: 3cba081af7574ca62d55a205ac14d9278b6ed05d2eb032d4d57abe4228756d36
版本仍为 0.6.2.3 / 15，属于未发布测试构建。

## 真机状态

本轮 adb devices -l 无设备；无线发现只有身份未确认的设备，未用于替代用户手机。未安装本轮修复 APK，待真机验证。
需在用户 V2502A 上验证已配对远程会话：连接、断网、恢复后重新连接、重新加载原会话、再次发送、重复操作及 App 重启；核对无重复任务/消息和历史不串。跨网 WSS、实际审批、断线续跑均未验收。
