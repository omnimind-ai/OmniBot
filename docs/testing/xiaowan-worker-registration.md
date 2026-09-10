# 小万执行 worker 的登记顺序

## 已复现问题

原 `runXiaowanPromptWorker` 先 `launch` 再调用 Session 登记回调。调度器可能先运行子协程，导致工具执行早于 Session 取得 worker 引用；登记被拒绝时也可能已经执行。另外，prompt 取得执行锁后检查 closed，与实际登记之间没有和 close 互斥，关闭流程可能先清理 MCP，随后 prompt 仍进入附件准备。

新增三个实际失败用例：立即调度时停止登记仍执行工具、登记失败仍执行工具、关闭期间 prompt 进入附件准备。用立即执行的 CoroutineDispatcher 强制呈现合法调度顺序，不依赖压力循环碰概率。初版 Unconfined 测试受 runBlocking 事件循环排队影响，在旧代码上通过，因此未将其作为复现证据；改为立即 dispatcher 后得到两条真实失败。Session 用例在现有关闭锁上控制交错。

## 最小修复

沿用现有 Session、promptMutex、closeMutex 和 worker：worker 使用 `CoroutineStart.LAZY`，Session 在 closeMutex 内再次核对 closed 并完成登记，之后才 start。关闭先取得锁则拒绝新 worker；登记先取得锁则 close 能取消并等待已登记的 worker。登记抛错时取消尚未启动的 lazy child，避免结构化协程等待未启动的子任务。执行失败、取消、更新排空和官方 ACP PromptResponse 仍由原流程处理。

没有添加新 Agent 状态、重试、重新开会话或 UI 流程。

## 回归入口与结果

```sh
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests '*XiaowanPromptWorkerTest' --tests '*XiaowanSessionAdmissionTest' --tests '*XiaowanSessionCloseCleanupTest' --tests '*XiaowanSessionDeleteCleanupTest' -Ptarget=lib/main_standard.dart
```

2026-09-09：21 项原生测试通过，包含已有慢消费者、不同 buffer 容量、重复取消和会话隔离循环测试；APK 构建成功。红绿 XML 在 `artifacts/worker-registration-20260909/`。这两个测试类已在 `scripts/test-agent-runtime.sh` 的执行范围内。

新包 v0.6.2.2 develop debug，SHA-256 `8a3e5e93fe4501245b676a13b728542080f5901a4a9e5cb0bb473181c84bca3b`，保留数据安装到 emulator-45562 / Android 13 ARM64。精确调度交错目前只有 JVM 证据，**待真机验证**。

该新包使用配置的真实 GLM-5.1 API 运行 `xiaowan-session-config-cancel.en.json`，runId `1788950971603`，连续 **27/27 步通过**。覆盖设置切换、弹层返回、真实终端子进程的启动与停止、原任务正式 cancelled、下一轮命令实际输出与正式完成、重启后的配置和两轮历史。最终恢复默认推理设置并关闭弹层。执行用例、设备结果及原生红绿结果均已归档；普通设备流程不替代精确并发时序的真机验收。

## 后续：登记等待期间的两条取消路径

同一测试类新增两条受控时序用例，各重复 20 次：

- collector 在 onStarted 挂起期间被取消：等待其结束后，lazy worker 必须完成且取消，不能执行工具，也不能向已断开的 collector 发布终态；随后独立请求仍可正常完成。
- worker 在 onStarted 挂起期间被停止：登记等待结束后不得执行工具，存活的 collector 只收到一次取消结果。

这区分了传输收集方退出与执行 worker 停止，没有将两种取消混为一个状态。2026-09-09：Worker 12 项及 SessionAdmission 5 项，合计 **17 项通过**。证据 `artifacts/worker-registration-wait-20260909/`；仍由原测试入口执行。本次只增加测试，未修改产品代码、未重新安装 APK。精确交错为 JVM 证据，**待真机验证**。
