# 小万终端停止与会话清理回归

## 所有权与故障

生命周期仍为 Conversation → ACP Session → Turn → Item。`session/cancel` 取消既有 prompt worker；`TerminalManager` 负责该工具的进程和输出管道，启动脚本负责向 PRoot 传递终止请求。没有增加 Agent 状态、重放请求或第二条取消协议。

原启动脚本最终 `exec` 为 PRoot，`Process.destroy()` 发送 TERM。PRoot 忽略 TERM；它的 QUIT handler 才会清理 tracees。终端读取线程可能因此继续等待子进程持有的管道，取消无法及时结束。工具停止回调另有直接强杀分支，也没有沿用底层清理。

核对的上游：[termux/proot event.c，7266fb3e8516535682f5a9c8f3a7e70f6506eddb](https://github.com/termux/proot/blob/7266fb3e8516535682f5a9c8f3a7e70f6506eddb/src/tracee/event.c)。这里记录的是核对源码版本，不将它冒充为 APK 内二进制的构建版本。

修复沿用现有 `run_child`/trap：host shell 将 TERM 转为发给其持有的 PRoot 子进程的 QUIT，由 PRoot 清理 tracees。函数入口先用 `3<&0` 保存标准输入，异步子进程通过 `<&3 3<&-` 恢复 stdin 并关闭额外描述符，避免后台启动将 ACP stdin 换成 `/dev/null`。正常退出仍返回原退出码。工具停止回调复用 `terminateHiddenExecProcess`。

## 可执行回归

```sh
python3 scripts/test-terminal-host-stop.py
python3 scripts/test-terminal-child-state.py
python3 -m unittest discover -s scripts -p test_agent_turn_outcome.py
python3 scripts/verify-terminal-stdio.py emulator-45562 /tmp/oob-terminal-stdio.json
node --test scripts/verify-agent-user-journey.test.mjs
node scripts/verify-agent-user-journey.mjs emulator-45562 \
  scripts/fixtures/agent-user-journeys/xiaowan-terminal-child-stop.en.json \
  /tmp/oob-terminal-stop-acceptance
```

- Host 测试执行真实启动脚本，以子进程模拟 PRoot 官方信号语义；修复前 TERM 无法结束，修复后退出并清理子进程。另测 stdin 与 exit 7。这是宿主机契约测试，不替代设备测试。
- 模拟器使用已配置真实 API，通过 App 输入命令 `sleep 173 & echo $! > /workspace/<本轮标记>.pid; wait`。读取实际 PID、UID、启动时间和命令，要求子进程启动不足 120 秒，避免自然到期伪装成停止成功。
- 点击 Stop 后十秒内检查同一子进程退出；App PID 必须保持不变。检查正式 cancelled 终态、下一轮真实终端成功、重启后的两个历史终态。
- 观察器只有读取权限，不通过 kill 帮助产品通过。ADB exec-out 可能把远端 cat 失败报告为本地退出码 0，已覆盖缺文件等待与拒绝将 Permission denied 当作进程退出。
- 原始失败和环境预检失败保存在 `artifacts/terminal-stop-lifecycle-20260909/`。首次读 PID 失败属于观察器错误；之后继续同一轮，没有重复发送。真实停止断言失败后，系统又因 package change 杀死 App，该外部结束不能算取消成功。另一次设备时间被改到 2023 年，预检在发送前拒绝运行。

- 实际 Android stdin 探针额外抓到中间实现的错误：直接 `<&0 &` 在 Mac shell 可用，在 Android mksh 仍读到 EOF。最小设备对照为 `<&0` 得到空字符串，函数入口保存 fd 3 后恢复得到 PING。最终实现必须同时通过已安装 PRoot 的 stdin/exit=7 探针与 UI 停止流程，不能只凭取消成功发布。

## 验证记录

最终 APK v0.6.2.2 develop debug，SHA-256 `5a0e6e644340c9070fd0a9835e6bab595e7fd3d94d0cd26835117cd1464f1961`。覆盖安装成功，设备上启动脚本与构建资产逐字节相同。

原生相关测试 12 项通过，ACP 契约检查 46 项通过，宿主脚本 2 项、观察器 9 项、历史终态断言 29 项、UI 流程测试 2 项通过。最终已安装 PRoot 的 stdin/exit=7 探针通过，运行 UID 10174；这是实际设备上的运行时检查，不冒充 UI 操作验收。

中间 APK `8e9b05ba…` 首轮 UI 12/12 步通过，第二轮停止也通过（子进程检查分别 1.437 / 1.563 秒）。第二轮在恢复断言处失败：实际 terminal_execute 已输出正确结果并正式 end_turn，但模型没有额外助手文字。已修正测试要求为真实 stdout、单次成功工具和正式终态，并覆盖“只输出工具结果”和“缺正式结束/错误输出”正反例；同一失败轮的只读重验通过。原失败报告仍保留，不回填为整轮通过。

最终版本运行 `1788947103944` 的完整界面流程 **12/12 通过**：实际子进程退出、App PID 不变、正式 cancelled、下一轮真实终端 stdout 与 end_turn、重启后两轮终态保持。测试代码及失败/成功结果已纳入长期回归集，详见 `artifacts/terminal-stop-lifecycle-20260909/verification.json`。

**待真机验证**：未连接物理手机，模拟器和宿主机检查不等于真机验收。会话 close 的取消清理与清理失败重试见 `xiaowan-session-close-cleanup.md`；其精确异常路径仍为 JVM 覆盖，不能由本终端流程代替。
