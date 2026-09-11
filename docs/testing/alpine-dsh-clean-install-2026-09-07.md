# Alpine 全新安装 DSH 验收（2026-09-07）

## 环境与边界

- Agent 操作的 Android 模拟器验收，不是人工用户反馈。
- 新建空白 AVD `OobCleanInstall20260907` / `emulator-5560`，Android 13 / ARM64，2 GB RAM；未复用原手机或 Ubuntu 环境。
- 首次安装 APK 为 developStandardDebug 0.6.1，基于工作树 HEAD `64ad2b15c` 加当时已有未提交改动。
- 初始 APK SHA-256：`9a6ec999724668c59f8733389b9957d05889907984805b708f39258216ce52df`。
- 从首次引导选择 Alpine、默认聊天开发工具，完成基础环境安装，再到设置 → Agent Mode → DeepSeek Harness → Install。没有用 ADB 手工补 Linux 依赖。
- Alpine `3.21.0`；应用 Debug 配置自带 GLM-5.1 Provider。本次未复制手机凭据、历史或终端目录。

## 修复前的实际结果

1. Alpine 引导到达 100% / development setup ready。
2. DSH npm 安装正常退出，版本 `@deepseek-ai/dsh@0.1.2-rc.1`。
3. 官方 ACP profile 自动生成，bundles 为 `@deepseek-ai/dsh-base` 和 `@deepseek-ai/dsh-acp-app`；原有 native node-pty 检查通过。
4. 安装健康检查完成 ACP initialize，应用显示 `Assistant installed`。该次初始化 35,022 ms。
5. 新建 DSH 会话 `82609c2b-8e72-404f-96a4-1a71568f7e7e`，发送 `Reply ALPINE_DSH_FRESH_OK`，界面收到原样回复，ACP `stopReason=end_turn`。会话进程冷初始化 65,382 ms；session/new 1,723 ms；prompt 发送 48 ms；首次文本约 20,981 ms。
6. 同一会话发送 `Run bash command uname -s and report its actual output then reply ALPINE_DSH_SHELL_OK`。复用同一进程、session，prompt 发送 11 ms，没有再次 initialize。工具失败，模型如实报告失败。

两项独立错误由工具详情确认：

- 默认 `workspace-write`：`no sandbox backend is usable on this host`。
- 后续命令尝试：`Error: spawn bash ENOENT`。

文件系统确认 `local/alpine/bin/bash` 不存在，只有 BusyBox `/bin/sh`。因此“npm 安装 + ACP 初始化成功”不足以证明 DSH shell 可用。冷初始化慢与缺少 bash 是不同问题，以上模拟器数字不能当作真实手机性能结论。

## 本次修改

- 原有 DSH 安装脚本在 npm/profile 检查前补齐 bash，通过 Alpine apk 或 Ubuntu apt 安装；已有 bash 时不访问包管理器。
- 安装阶段和原有健康命令实际启动 `bash --noprofile --norc -c ':'`，缺失、包管理器失败或不可执行时不再判为健康。
- 更新原有 preparation revision，使旧的成功安装记录重新走既有检查流程。
- 没有改变权限默认值、关闭沙箱、修改 ACP 会话生命周期或重放用户消息。

## 回归方法

```sh
node --test scripts/deepseek-shell-install.test.mjs scripts/deepseek-browser-navigation.test.mjs
./gradlew --no-daemon --no-parallel :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
```

6 项本地测试通过，覆盖 Alpine/Ubuntu 缺失 bash、重复准备不重新安装、包管理器失败、安装后仍缺失/不可执行、健康检查拒绝坏环境，以及原有浏览器登录补丁回归。

## 修复后的设备复测

- APK 构建成功（1m36s），覆盖安装成功；SHA-256：`10769a529b0e9b86df4be0f8c3fa364cbc4b0cffe70a495e90416d30e664a37d`。
- 应用重新打开后，官方 `session/load` 恢复同一会话 `568f7e7e`，原来的成功/失败消息均保留。
- 在 Agent Mode 对 DSH 点击 Reinstall，应用自身安装 `/bin/bash`（866,000 bytes），再次显示 `Assistant installed`。没有通过 ADB 直接安装 bash。
- 同一会话发送相同 `uname -s` 命令，标记改为 `ALPINE_DSH_BASH_FIXED`。prompt 发送 43 ms，未重建当前 ACP 进程/session。
- 默认 `workspace-write` 仍返回 `SANDBOX_UNAVAILABLE`。随后 DSH 通过自己的正式权限请求对该命令申请 `danger-full-access`，记录为 `approval/decided: allowed-once`，bash 工具实际返回 `Linux\n` / `isError=false`。此次没有修改全局权限设置。
- 界面显示实际输出 `Linux`、标记 `ALPINE_DSH_BASH_FIXED`，10:43:11 ACP 返回 `stopReason=end_turn`。这是 bash 缺失修复的通过证据，不是默认沙箱已经可用的证据。
- 完成后 force-stop 并重新启动测试应用，`/bin/bash` 仍存在，原会话的失败记录和修复后的 `Linux` 回复均完整保留。

上游依赖依据为实际安装的官方 `@deepseek-ai/dsh-tool-bash/lib/index.js`，其工具说明明确执行 `bash -c`。修复只补齐该运行依赖；Android/proot 沙箱后端兼容问题仍存在，不计入本次已修复范围。

截图：[Alpine 完成](artifacts/alpine-dsh-2026-09-07/oob-clean-alpine-ready.png)、[DSH 安装完成](artifacts/alpine-dsh-2026-09-07/oob-clean-dsh-installed.png)、[修复后的真实 shell 回复](artifacts/alpine-dsh-2026-09-07/oob-clean-dsh-bash-fixed.png)。

测试辅助脚本 `verify-reply-timing.mjs` 仍限定旧的 Android `ImageView` 语义节点，当前回复节点为 `View`，该脚本断言未通过；以上回复/时间验证来自实际 UI hierarchy、截图、DSH 工具结果与官方 ACP 结束日志，没有将该旧辅助脚本计作通过。

## 默认沙箱后端的进一步定位

后续请求修复默认沙箱时，重新启动上述保留数据的 AVD，直接调用已安装官方 `@deepseek-ai/node-addon-landlock-run` 的 `launcherPath()` / `probe()`，并用 `spawnSync(launcher, ['--probe'])` 保留原始错误：

```text
verdict: unusable
status: 125
stderr: landlock-run: landlock is not enforced by this kernel (ABI unsupported or disabled)
bwrap: ENOENT
```

绕过 proot，通过 `adb shell run-as cn.com.omnimind.bot <绝对路径>/landlock-run --probe` 直接执行同一官方静态程序，仍返回同一错误。由此排除 proot 是此 Landlock 探测失败的必要原因。

只读检查 `/proc/config.gz`，模拟器和真实手机 `b49f281b` 均返回：

```text
# CONFIG_USER_NS is not set
# CONFIG_SECURITY_LANDLOCK is not set
```

模拟器内核为 `5.15.119-android13-8`，真实手机为 `5.15.180-android13-8-o`。手机未安装软件、未改变权限或终端配置。

官方 `@deepseek-ai/dsh-sandbox-local` 的 Linux 后端顺序是 bubblewrap → Landlock；bubblewrap 需要可用的 namespace 创建权限，Landlock 需要内核启用该 LSM。当前非特权 Android 应用没有可用的这两个后端。仅安装 bubblewrap 或修改 ACP 生命周期不能补齐内核能力，因此没有添加这些无效改动。

应用现有 DSH 配置已提供 `danger-full-access`，通过 `DSH_PERMISSION_MODE` 交给官方实现。该模式可使 shell 在应用已有权限范围内工作，但取消工作区级文件写入限制；它是权限选择，不是沙箱修复。继续保持原权限，未自动切换该模式，也未伪造 sandbox probe 成功。若用户明确选择完全访问，可沿用现有配置入口实施和验证；若必须保留工作区沙箱，则需要具备相应内核能力的运行环境。
