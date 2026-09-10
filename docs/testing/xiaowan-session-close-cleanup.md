# 小万 ACP 关闭过程的资源清理

## 原因与修复

`XiaowanAgentSession.close` 先将 closed 置为 true，再挂起等待 activePromptJob 停止。如果调用 close 的协程此时被取消，cancelAndJoin 抛出取消，MCP 清理和 onClosed 均被跳过。再次 close 因 closed 已置位而直接返回。

最小修改仍由原 session.close 持有资源释放：使用 withContext(NonCancellable) 完成关闭清理，closeMutex 让并发关闭等待同一次清理结束。普通 session.cancel 仍保持原方法和官方 prompt 终态，无新增生命周期或后台重试。

参考 Kotlin 官方 NonCancellable API：https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-non-cancellable/

## 可执行测试

`XiaowanSessionCloseCleanupTest` 调用实际私有 Session 的 close/cancel 方法，隔离未涉及的 Android 构造依赖。控制正在停止的 prompt，让 close 调用者被取消，断言 MCP 和 session 登记各释放一次；同时覆盖并发关闭等待及完成后重复关闭。

旧实现真实失败：MCP close 调用次数为零。首次修复被测试拦截：协程作用域中的 cancel 名称指向协程而非 Session，修正为明确的 this@XiaowanAgentSession.cancel。相关失败 XML 均保存在 artifacts/session-close-cleanup-20260909/。

2026-09-09：关闭清理 2、Local close 2、Local delete 1、Xiaowan connection 5，共 10 项通过。

此为 JVM 生命周期时序验证，不能代替设备复现。App 会话列表未暴露独立关闭/删除按钮；close 的现有入口是未归属会话清理与临时浮层退出，不能把普通 Stop 的模拟器成功当成精确 close 竞态验收。

追加的 `xiaowan-close-cleanup-smoke.en.json` 用本地 API 测试 Stop 后下一轮及重启历史，已在 emulator-45562 使用现有本地 API 完整执行 11/11 步通过。精确关闭取消时序及物理设备验收均待验证。


模拟器补充回归入口（预先安装记录版本、选择现有本地 API 小万空闲会话）：

```sh
node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/xiaowan-close-cleanup-smoke.en.json /tmp/oob-close-cleanup-unique
```

本轮 APK 已构建、保留数据安装至 emulator-45562，SHA256 `7702d3535eead7f26999857da5cba2af2ee5d9a96db1a1f450b93e4f0312c9a4`。ACP 源码契约 46 项通过。


## 后续：关闭异常与再次清理

发现 MCP Session.close 吞掉所有连接关闭异常；上层 closed 标志又使失败后的再次 close 直接成功返回。新增两个行为测试，旧实现分别以“未报告失败”和“预期清理两次、实际一次”失败。

MCP 层现在尝试所有连接的关闭，聚合普通异常后交还原 ACP Session；Session 的 closed 继续用于拒绝新 prompt，cleanupComplete 仅在 closeMutex 内记录成功清理。失败不会移除 session 登记，也不会自动重试；下一次显式 close 可继续清理，成功后仍幂等。没有新增 ACP 状态、UI 入口或重开会话路径。

2026-09-09：28 项相关 JVM 测试和 46 项源码契约通过，证据 artifacts/session-close-failure-20260909/。初版测试对异常对象身份的断言不适合作为行为契约，改为检查对外错误消息及实际调用次数后，保留旧实现两条实际失败证据。设备关闭异常注入仍未验收，待真机验证。

后续关闭异常修复版本 APK SHA256 `b06078c33e18e9cbc627643243743c6d4a28ca568cacb4f48b30d40e13042f39` 已构建、保留数据安装；模拟器本地 API 再次完整 11/11 通过。关闭失败注入仍仅为 JVM 证据，待设备及真机验收。
