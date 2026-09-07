# 安装脚本审查与回归记录

范围：终端环境清单、Alpine/Ubuntu 安装命令与仓库配置、后台/终端会话/交互安装入口、托管 ACP 安装脚本、rootfs 初始化、技能安装和开发 APK 安装脚本。未改变 ACP 生命周期，未重置手机环境或技能。

## 已复现与修复

1. DeepSeek 健康检查生成非法 PATH 展开，dash 退出码 2；纠正 Kotlin 转义。生成命令实际执行回归先红后绿。进一步删除重复 PATH 设置产生的分号断链，防止适配器文件缺失仍显示 INSTALLED；缺失文件用例同样先红后绿。
2. 安装脚本在 if/&& 上下文中失去 errexit，中间失败被后续成功命令掩盖。三个环境安装入口复用 `buildInstallExecutionCommand`，在一个独立 `sh -e` 进程内保留各步骤环境变量，正确返回失败。多行失败、仓库子 shell 失败、显式 fallback 成功/失败及引号/环境变量均有执行测试。
3. OpenCode 在 Ubuntu 中也强制使用 musl 包。按选定 rootfs 分配官方 arm64 或 arm64-musl 包；检查实际执行能力，失败才安装。官方 npm registry 的两个包均已核对。
4. 技能安装路径空格被 shell 分词，SSH URL 中 @ 被误解析。同名技能可合并到同一目标。改为按行读取清单，保留 SSH URL，在复制前拒绝重复目标；保留已有技能禁止覆盖规则。
5. install-dev 默认包名仍含已经取消的 .debug 后缀；同步为实际 applicationId。启动失败不再吞掉退出码。

## 验证

- 独立工作树：EnvironmentSetupLogicTest 18 项、EnvironmentInstallExecutionTest 7 项通过。
- Node shell 工作流测试 5 项通过：临时目录、模拟 Git/ADB，不涉及真实下载、账户或设备写入。
- 7 个安装/初始化 shell 文件通过各自解释器语法检查；语法检查不等于运行成功。
- 两个后台安装调用点使用共享执行构造方法，Android debug APK 最终补丁的测试与 Android debug APK 构建成功（同一 Gradle 调用 1m06s）。
- 新测试接入现有 scripts/test-agent-runtime.sh。

## Android 虚拟机用户操作 E2E（2026-09-06）

实际使用 Android 33 arm64 模拟器，通过 Android UI 点击、滚动、键盘输入操作产品。安装由产品入口发起，未用 adb shell 安装 Linux 工具来替代用户流程。ADB 截图保存于 [验证目录](verification/ubuntu-e2e-20260906/)。

- 旧环境 emulator-5554：设置 → 终端环境 → Ubuntu，修复前复现 `0/14` 和 `/bin/sh: 59: Bad substitution`。覆盖安装当前 debug APK 后检测恢复为 8/14。勾选 OpenCode、sshpass → 开始配置，真实终端联网安装完成；返回清单可见 OpenCode 1.18.29、sshpass 1.09。强制停止并重启应用后再次检测，10/14 保留。
- 全新独立 AVD OobUbuntuE2E / emulator-5556：无既有应用数据或 rootfs；安装同一 APK。首次引导选择 Ubuntu → Chat Agent Assistant → 不选可选 harness → Start setup。产品自行下载、解压并安装基础工具，界面达到 100%，显示 System and development environment are ready。
- 完成引导后从聊天页打开终端。实际通过终端键盘输入并看到 `node --version` 为 v22.23.2、`python3 --version` 为 Python 3.12.3。通过终端创建 `/root/oob-ubuntu-e2e-persisted`，强制停止并重启应用，再打开终端执行 ls，文件仍存在。截图分别为 terminal-versions.png、fresh-restart-persisted.png。
- 输入验证曾因页面未就绪及焦点不在终端而误操作；等待真实提示符并点击终端输入区后命令及输出正常。该过程不作为 Ubuntu 安装失败证据，也未据此新增终端生命周期修补。
- 直接安装的另一个根因已在 Android 应用 UID 下复现：普通 tar 无法创建 Ubuntu 归档硬链接，报 Permission denied。当前工作区已有的 init-host 修复提前部署 bundled PRoot，以 `--link2symlink` 解压；本次全新 UI 安装验证覆盖了该修复。

安装包：当前共享工作区的 developStandard debug、versionName 0.6.1，构建日志 `/tmp/oob-ubuntu-current-build.log`。这是本地修复构建，未发布；本轮只安装到两个模拟器，未安装到用户手机。

APK SHA-256：`267413c527fe411167835c18b52bc262d7c6ffe75179d3606f444001c88b8bb9`。

复查注意：每次点击前读取当前 UI 层级/截图，等待安装结果或终端提示符，勿用固定短延时假定启动完成。可复用 `scripts/tap-agent-device-control.mjs`；不要清除用户数据来重跑首次安装，应使用独立 AVD。

## 尚未覆盖

尚未在真机上执行 Alpine/Ubuntu 所有工具的联网安装、故障重试及重启验收。网络镜像、上游包发布内容、磁盘/权限/PRoot 差异仍需设备证据。构建和模拟执行通过不能声称所有安装问题已经消失。

工作区另有进行中的 DeepSeek 官方适配器与 rootfs 修改，本轮保留它们；本记录只归因本轮已复现的安装问题。

## ACP Harness 真机验收补充（2026-09-06）

### DeepSeek Harness

根因不是 npm 包缺失：官方 `@deepseek-ai/dsh@0.1.2-rc.1` 已安装，但旧安装脚本没有调用官方 launcher 初始化 `$DSH_HOME/profiles/acp`，因此应用的健康检查会看到命令存在而 ACP profile 缺失。修复后的脚本调用官方 `dsh-acp-android --profile acp --help` 触发 profile 初始化，并校验官方 `package.json`、`cordis.patch.yml`、bundle `@deepseek-ai/dsh-acp-app` 和 `node-pty`；不再安装私有 ACP 插件，也不重置已有 profile/plugin。

在 `emulator-5556` 的应用安装入口完成 DSH 安装后，官方 ACP profile 文件和 bundle 均存在；官方 `initialize`、应用内 `session/new`、`session/prompt` 和 `stopReason=end_turn` 均通过。真实对话产生了可见回复，过程中出现的 `bash Failed` 是模型生成命令的工具结果，不是 ACP 安装或生命周期失败；同一 turn 正常结束，没有重放。

### Kimi Code

Kimi Code 0.41.0 通过官方 `kimi acp` 完成 `initialize`、`session/new`、`session/prompt` 和 `stopReason=end_turn`，真机 UI 显示处理完成。Responses 配置另通过本地 fixture 验证实际请求路径为 `/v1/responses`，故意返回 401 后官方 prompt 正常以错误结束；该 fixture 不代表线上模型回复成功，但证明配置转换和 wire API 选择生效。

最终 developStandard debug APK 已重新构建并覆盖安装到 `emulator-5556`，`versionName=0.6.1`、`versionCode=11`。本补充只记录当前本地 APK 和模拟器证据，不表示已经发布 release，也不覆盖用户手机。
