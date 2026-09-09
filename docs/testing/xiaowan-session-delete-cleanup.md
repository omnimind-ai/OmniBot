# 小万 ACP 删除与清理所有权

## 问题与修复

`XiaowanAgentSupport.deleteSession` 原先先从 `activeSessions` 移除 Session，再调用其 `close`。MCP 关闭失败时，原 Session 已经失去登记，下一次显式删除找不到它，跳过资源清理后继续解除持久化绑定。

现在先查询原 Session 并调用其现有 `close`。只有清理成功后，原 Session 的 `onClosed` 才移除登记；随后执行原绑定解除回调。清理失败会返回原错误并保留清理对象，下一次显式删除可以继续清理。绑定解除失败时，已经释放的资源不会重新打开。

这是原 ACP Session 所有权顺序的修复，没有添加自动重试、第二个生命周期或历史删除路径。`deleteSessionCallback` 解除 profile/thread 绑定，不等于删除 Conversation 的用户历史。

## 可执行回归

`XiaowanSessionDeleteCleanupTest` 调用实际 AgentSupport 删除方法，隔离 Session 资源依赖：

- 关闭失败后仍保留同一个 Session，绑定未解除；下一次显式删除再次调用清理并成功解除绑定。
- 清理成功、绑定解除失败后，再次删除只重试解除绑定，不重复打开或关闭已释放资源。

旧实现运行 2 项，第一项真实失败，第二项通过；修复后相关删除、关闭和设置准入测试合计 **13 项通过**。红绿 XML 保存在 `artifacts/session-delete-cleanup-20260909/`。测试已加入 `scripts/test-agent-runtime.sh`。

```sh
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests '*XiaowanSessionDeleteCleanupTest' --tests '*XiaowanSessionCloseCleanupTest' --tests '*LocalAcpSessionDeleteTest' --tests '*LocalAcpSessionCloseTest' --tests '*XiaowanSessionAdmissionTest' -Ptarget=lib/main_standard.dart
```

## 验收边界

APK 构建成功并保留数据安装到 Android 13 ARM64 模拟器 emulator-45562；v0.6.2.2 develop debug，SHA-256 `6bf2ee96974596b68922f4b144aada216674467eb9676c87b0f52a2ffc5142d3`。

精确删除失败注入目前为 JVM 证据。UI 对话删除在 Agent 模式下走归档，不能当成 ACP `session/delete` 验收。普通设置、Stop 和重启回归也不能替代这条失败分支的设备验收。**待设备故障注入与真机验证。**

新包使用配置的真实 GLM-5.1 API 完整执行 `xiaowan-session-config-cancel.en.json`，运行 `1788950333752` **27/27 步通过**：设置 Off、返回保留对话、真实终端子进程启动和停止、正式取消、恢复默认设置、下一条终端命令正常完成、重启后配置与取消/完成历史保留。已恢复默认设置并关闭弹层，未清除 App 数据。证据与执行用例保存在同一 artifacts 目录。
