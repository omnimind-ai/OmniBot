# 新请求错误详情及恢复验收

## 最终真实 API 流程结果

用户要求等待共用设备当前任务完成后继续。只读检查确认无停止按钮且最近完成记录为官方 end_turn 后，才切换到新的 Codex 欢迎页。未取消、修改或向原用户会话追加测试内容。

`1788960584626` 在持久 AVD OobRegression20260909 / emulator-45562、APK d1dc4920…、本地 Emulator regression / GLM-5.1 上实际 **14/14 步通过**。唯一命令工具返回 182、status=error、success=false，持久化 summary 为 `Command exited with code 182`；下一条普通消息完成，重启后两轮的官方终态及错误详情继续通过。输入工具逐字输入第一条消息耗时 168 秒，不能计为模型启动耗时。

fixture 新增 Codex 空欢迎页前置检查，避免误用用户已有对话。此前缺设备运行不算发送，本次只有一轮正式发送。最终结果、实际 fixture 和合成对话截图已归档 `artifacts/codex-live-error-detail-20260909/`。

**错误处理与恢复的模拟器验收通过；沙箱仍失败，待真机验证。** 以下保留环境恢复过程记录。

入口：`node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/codex-real-error-detail.en.json /tmp/codex-live-detail`。

复用原命令场景和 runner，用独立的 DETAIL 标记验证实际一次命令调用、非零退出、失败状态及与实际退出码完全一致的持久化说明，再验证普通回复和重启后的两轮历史。此用例检验错误处理，**不宣称沙箱成功**；原 EXIT 用例仍要求退出 7 且 stdout/stderr 标记都存在。

`test_agent_turn_outcome.py` 新增对空说明、错误退出码说明、正确说明的判定；31 项通过。

首次运行 `1788959575546` 在第 0 步因 ADB 设备不存在停止，没有发送消息。检查确认原 AVD 的 `/tmp/oob-clean-install-20260907.avd` 目录已不存在，只有索引。未清理或覆盖其他模拟器。

已新建 `OobRegression20260909`，数据位于持久的 `~/.android/avd/OobRegression20260909.avd`，Android 13 / ARM64，serial 仍为 emulator-45562（**设备数据身份已更换**）。安装 d1dc4920… APK 成功，既有 debug 配置入口确认本地 GLM-5.1 Provider。通过实际 App 引导选择 Alpine、Chat Agent Assistant、Codex CLI 并启动安装。当前观察为 79% Installing development tools，apk 子进程存活；不能报告安装完成。

后续实际观察：App 已显示 **100% / System and development environment are ready**，环境安装完成。进入后续权限引导，未授予额外权限。一度 UiAutomator 返回 null root，下一步在点击前退出；只读重查确认原页面仍在，App MainActivity/PID 6121 及 emulator PID 5114 存活，没有据此重发模型请求。

后续：已通过实际 UI 完成六步聊天引导、进入首页并关闭登录提示，默认模型仍为本地 Emulator regression / GLM-5.1。初始聊天模式菜单只有 OmniAi / Pure chat；引导安装 Codex CLI 不等于官方 ACP 适配器已安装。通过 Settings → Agent Mode → Codex 的既有 Install 入口安装后，只读核对包元数据确认 CLI **0.153.4**、ACP **1.10.0** 均存在。

安装后观察到设备进入一条非合成对话且有活跃执行，立即停止 UI 自动化；不保存或复制该用户对话内容，不取消、不重发。本轮真实测试请求仍未发送。已询问等待现有任务结束还是使用另一专用设备；新设备安装状态证据已更新。

当时状态为新请求尚未运行、共用设备有非测试任务；现已按用户安排完成上文真实流程。旧模拟器的历史验收证据仍有效，但不能将原 conversation 7/session 身份用于新设备。
