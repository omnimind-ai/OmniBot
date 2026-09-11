# Codex Android 终端执行：验收失败

emulator-45562，Codex CLI 0.153.4 / Codex ACP 1.10.0，App APK `6731411b77c88286ee7d45985ae8f307802d67aaac2661497bdda08e33f20daa`，会话权限 workspace-write。

通过真实 App 和现有本地 API 请求执行一次无副作用的 shell 命令：分别输出唯一 STDOUT/STDERR 标记，然后 exit 7。请求明确禁止重试或无沙箱重跑。runId `1788953461021`。

真实工具记录只有一个 `agent.terminal` / `commandExecution`，status failed、success false，rawOutput.exit_code 为 **182**，formatted_output 为空。它没有实现预期的退出码 7 与标记输出，**终端工具验收失败，根因尚未确定**。不得据此推断成 DSH 的 Landlock 错误，也不能用更高权限运行来冒充修复。

原 journey 的 13 步仅验证权限菜单返回、模型回复、正式 end_turn、后续普通对话及重启历史；这些通过不足以证明命令执行。保留原报告为 `conversation-only-result.json`，没有改写成工具成功。

现已扩展既有 `assert-agent-turn-outcome.py`：对 OOB_LIVE_CODEX_EXIT 标记必须检查恰好一个 commandExecution、退出码 7、两个实际输出标记，以及非零退出显示失败。对同一已完成任务只读重验，确实失败；未重发请求。观察器单元测试覆盖 182、输出缺失、误标成功、正常预期非零结果和重复工具，现共 30 项通过。

复现入口 `codex-real-terminal-exit.en.json` 和 `codex-terminal-exit.json` 已持久化；修正后的入口会在真实执行不符时失败。结果在 `artifacts/codex-terminal-exit-20260909/`。后续应定位官方 Codex 进程到命令执行的环境边界，保留原 ACP 生命周期及权限选择。

**待真机验证；本问题未修复。**

## 后续：脱离模型与 ACP 的直接复现

当前安装的 Codex CLI `sandbox --help` 要求命名权限配置。最初旧 sandbox_mode 参数探测返回 2（参数不全），使用未定义的 workspace-write 名称返回 1（缺 permissions 表）；两者均未执行命令，不能当成原 182 的复现。

核对 [官方权限文档](https://learn.chatgpt.com/docs/permissions) 后，使用内置 `:workspace`，无需修改用户配置：

```sh
python3 scripts/verify-codex-sandbox.py emulator-45562 docs/testing/artifacts/codex-terminal-exit-20260909/maintained-sandbox-probe.json
```

探针实际运行失败、退出 1，内部明确区分 adb 状态与 guest 状态：App UID 10174 的普通 bash 对照有两个标记，退出 7；同一 bash 经 `codex sandbox -P :workspace` 后退出 182、无命令输出。未发模型请求、未修改 App 权限，普通 bash 只是对照，不是沙箱回退。

这证明原问题可以在没有模型及 ACP 的情况下复现，定位方向为官方 Codex 沙箱与 Android/PRoot 执行边界。PRoot 的 /sdcard 与 /proc/self/fd 警告在成功对照中也存在，单凭这些警告不能认定根因。具体失败系统调用仍未确定，不能宣称已修复或已证明等同于 DSH 的后端失败。

维护探针与两份早期直接对照结果保存在同一 artifacts 目录。该探针验证启动与命令结果，未来即使通过，也不代表隔离性和完整 UI 验收通过。

## 后续：原生进程退出状态

核对安装包 codex-package.json，目标为 aarch64-unknown-linux-musl、版本 0.153.4、入口 bin/codex。对照官方 `rust-v0.153.4` 的 [Linux sandbox 入口](https://github.com/openai/codex/blob/rust-v0.153.4/codex-rs/linux-sandbox/src/linux_run_main.rs) 与 process-hardening 源码；不能把 182 当作已定义的通用加固错误码。

增加同一维护探针的 `--native` 诊断：直接运行安装包中的原生入口，由 Node spawnSync 记录 status/signal，而非通过 npm 的 codex 包装命令。实际运行仍失败，原生进程为 **status=182、signal=null、无输出**；普通 bash 对照仍输出两个标记并返回 7。

```sh
python3 scripts/verify-codex-sandbox.py emulator-45562 docs/testing/artifacts/codex-terminal-exit-20260909/maintained-native-probe.json --native
```

这排除了 npm 包装命令在这一层把信号转换为 182；没有证明内部子进程不存在信号或等待状态问题。具体内部失败点尚待定位。没有修改二进制、App 配置或内核策略。
# 后续定位：PRoot 的 close-on-exec 描述符执行

继续验证：`--fd-exec` 现增加第五项 `bundledBwrapByPath`，直接启动当前 Codex 自带 bwrap，并要求只读根挂载、独立用户/PID namespace、proc/dev 挂载，再执行合成命令。此路径依然要求沙箱成功，不做无沙箱回退。实际在同一 APK/App UID 下返回 **1**，stderr 为 `bwrap: Can't read /proc/sys/kernel/overflowuid: Permission denied`。其余四项结果重复一致。证据 `fd-and-namespace-probe.json`。

这证明加载问题之后还有独立的沙箱初始化限制，因此修正描述符加载本身不能作为 Codex sandbox 可用的验收。当前检查过的 PRoot 上游源码为 `7266fb3e8516535682f5a9c8f3a7e70f6506eddb`；项目实际打包的是校验固定的 `proot_5.1.107.77_aarch64.deb`，未将两者默认为同一个构建，也未凭源码检查替换二进制。未修改产品运行时或安装配置。

2026-09-09，在同一已安装 APK（6731411b…）、emulator-45562、App UID 10174 上执行：

```sh
python3 scripts/verify-codex-sandbox.py emulator-45562 /tmp/codex-fd-probe.json --fd-exec
```

实际四个结果：普通 bash 返回 7 且双流输出正常；Codex `:workspace` 返回 182 且无命令输出；Python 通过不可继承的 `/proc/self/fd/N` 执行同一 bash 返回 182；仅对探针自己的描述符设置可继承后，返回 7 且双流输出正常。整体脚本正确返回失败，没有改变 Codex 安装、权限或配置。

此前 PRoot `-v 3` 日志显示，内置 bwrap 经 `/proc/self/fd/3` exec 后，加载器再次打开同一路径并立即以 182 退出。脱敏摘录及本次实际四项报告在 `artifacts/codex-terminal-exit-20260909/`。

对应上游源码：[Codex rust-v0.153.4 bundled_bwrap.rs](https://github.com/openai/codex/blob/rust-v0.153.4/codex-rs/linux-sandbox/src/bundled_bwrap.rs) 使用 `File::open` 后通过描述符路径 exec；[PRoot loader.c](https://github.com/termux/proot/blob/master/src/loader/loader.c) 的加载失败宏返回 182。结合最小复现，将当前 182 定位到描述符执行与 PRoot 加载器兼容性；不能据此认定解决该层后 bubblewrap 的内核隔离能力就可用。

状态：复现测试已加入，产品修复未完成；待修复 PRoot 兼容路径并再次验证实际 Codex 命令及权限隔离。**待真机验证**。
