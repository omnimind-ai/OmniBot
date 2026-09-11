# 当前安装版本：运行环境与命令能力

设备：emulator-45562，Android 13 ARM64；App UID 10174。APK v0.6.2.2 develop debug，SHA-256 `8a3e5e93fe4501245b676a13b728542080f5901a4a9e5cb0bb473181c84bca3b`。

## DSH 后端就绪：失败

```sh
python3 scripts/verify-dsh-sandbox.py emulator-45562 docs/testing/artifacts/dsh-sandbox-20260909-current/probe.json
python3 scripts/verify-terminal-stdio.py emulator-45562 docs/testing/artifacts/dsh-sandbox-20260909-current/terminal-stdio.json
```

两个探针均以 App UID 运行，没有更改权限或使用完全访问回退。DSH 0.1.2-rc.1 的官方 read-only 与 workspace-write 均返回 `SANDBOX_UNAVAILABLE`，因此第一个脚本返回 1 是实际验收失败，不能改为成功：

- bash 启动成功。
- bubblewrap 0.12.0 已安装，但官方启动参数在读取 `/proc/sys/kernel/overflowuid` 时遭到 Permission denied。
- Landlock 启动器在 PRoot 内外均返回 125，报告内核 ABI 不支持或未启用。当前内核为 `5.15.119-android13-8-00034-gd34029c8258b-ab10871489`。
- 第二个探针通过：安装的终端运行时保留标准输入与非零退出码 7。这不等于 DSH 沙箱可用。

需要支持官方隔离后端的运行环境才能继续受限命令的隔离验收。不得将应用权限内的普通 shell 成功当成工作区隔离成功；本次没有执行 DSH 模型任务，也没有确认其 UI 错误展示。

## 小万能力边界

当前 `xiaowanAgentCapabilities` 声明 ACP session/resume、close、delete 等能力。session/resume 恢复持久化上下文，不表示暂停后恢复执行栈。执行 pause/resume 不能从 ACP Session 恢复能力推导出来。

既有回归入口 `xiaowan-unsupported-lifecycle.en.json` 检查菜单包含 init/compact、不包含 plan/review/pause/resume；手动输入 /pause 与 /resume 时验证没有提交用户消息、没有启动任务；重启后重复。这项测试不发模型请求，不能替代 init/compact 的真实 API 执行验收。

该当前安装版本已完整执行上述 **23/23 步并通过**；结果与执行用例归档到 `artifacts/dsh-sandbox-20260909-current/`。在重启前后各输入一次 /pause、/resume，四次均未产生用户消息入库或运行中任务。菜单检查仅证明能力显示符合现有声明，不表示新增了计划或执行暂停功能。

**待真机验证**。环境探针只验证其明确范围，不代表五类整体目标已完成。
