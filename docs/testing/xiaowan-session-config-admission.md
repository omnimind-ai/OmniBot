# 小万配置与参数弹层生命周期回归

## 已复现的边界

- 小万配置原来只检查 `activePromptJob.isActive`。请求已预留但 worker 未启动，以及取消后仍在 finally 清理时，都可能越过该检查；关闭后的 session 也缺少配置入口检查。
- prompt 在等待既有执行锁之后没有复查 closed，可能在关闭后进入附件/工具准备。
- 参数弹层使用 root OverlayEntry。单独的 BackButtonListener 不能让 Android 根页面提前知道返回可被消费；真实模拟器按返回直接退到桌面。LocalHistoryEntry 的中间修正也未解决 Android 的 popDisposition，保留失败证据。

## 修复边界

Kotlin 沿用现有 promptMutex/closeMutex：配置与关闭串行，配置必须即时取得 prompt 的空闲预留，失败不进入设置写入；prompt 拿到锁后重新检查 closed。没有增加 ACP 状态、代理重试或第二条流。

Flutter 沿用 PopScope 使用的公开 `ModalRoute.registerPopEntry` 接口。弹层挂在 root Overlay，但向其所属页面登记阻止退出的 PopEntry；关闭时注销并释放 notifier。返回回调结束后再清理，避免在 Flutter 遍历 PopEntry 时修改集合。保留原来的界面和焦点，不 push 新路由。保留原有 BackButtonListener 的顶层弹层优先级；PopEntry 负责向系统声明返回处理能力，并处理直接 Navigator 返回。

## 可执行测试

```sh
./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest \
  --tests '*XiaowanSessionAdmissionTest' --tests '*XiaowanSessionCloseCleanupTest' \
  --tests '*XiaowanSessionConfigTest' --tests '*XiaowanAcpConnectionTest'
cd ui
flutter test test/widgets/glass_popup_back_test.dart \
  test/widgets/glass_popup_keyboard_test.dart \
  test/features/home/pages/chat/acp_config_button_test.dart
```

在仓库根目录，使用已配置的真实模型 API：

```sh
node scripts/verify-agent-user-journey.mjs emulator-45562 \
  scripts/fixtures/agent-user-journeys/xiaowan-session-config-cancel.en.json \
  /tmp/oob-config-acceptance
```

设备流程包含选择 Off、系统返回关闭弹层、真实终端启动、该 ACP session 的持久化 reasoning_effort=none、停止子进程与正式取消、恢复 Model default、下一轮真实命令及正式完成、重启后的配置和历史复查。`assert-agent-session-config.py` 通过本轮 marker → conversation → session 找到准确配置，不用其他会话的值冒充本轮成功。

原生 4 个新用例修复前全部失败，修复后连同相关测试共 18 项通过。Flutter 相关 21 项通过；协议/回归脚本检查 48 项通过。所有失败与成功证据保留在 `artifacts/session-config-admission-20260909/`。最终候选 APK SHA-256 为 `d20b5d3f3984717f0645dac8275982651c0df1469baae2e6e5da2505a8fc08ef`（v0.6.2.2 develop debug）。最终模拟器连续运行 `1788949439994` **27/27 步通过**：三次系统返回均保留对话，Off/default 配置按同一 ACP session 持久化，真实子进程停止和后续命令完成，重启后的配置、取消/完成历史均通过。最终已恢复默认推理设置。

**待真机验证**：只有 Android 13 ARM64 模拟器。精确并发空档目前由 JVM 测试覆盖，常规 UI 操作通过也不能冒充真机竞态验收。

## 2026-09-09：生命周期设置收尾核验

沿用既有 `XiaowanSessionAdmissionTest` 新增两个可执行边界：配置等待关闭锁期间会话关闭，必须重新核对 closed 并拒绝写入；取消等待中的配置请求，必须保留原持有者的关闭锁、不占用 prompt 预留，下一次配置仍可到达现有空闲检查。当前实现通过，无需增加产品状态或另一套锁。

本次实际重跑 Worker、Admission、CloseCleanup、DeleteCleanup、Config 五类共 **30 项，全部通过，零跳过**。脱敏 XML 与汇总在 `artifacts/lifecycle-settings-final-20260909/`。本次仅补充测试，未重新构建、安装 APK 或重跑设备流程；上文及 worker 登记报告的模拟器结果保留其各自包版本，不混称本次设备验收。**待真机验证**。Codex/DSH 沙箱失败是独立未解决项，不包含在本项通过结论中。
