# Agent 执行与功能回归审计 — 2026-09-17

用户要求：检查全部功能，特别是 Agent 执行问题。沿用用户指定的模拟器范围。
设备 `emulator-5580`，Android 13 ARM64；App `0.6.3 / code 16`。
证据目录：[agent-full-audit-20260917](artifacts/agent-full-audit-20260917/)。
这份记录不代表全部功能稳定验收，物理设备仍**待真机验证**。

## 本轮发现与改动

1. **Provider 自检的失败没有覆盖磁盘上旧的成功结果。** 无效配置只打印 `check_failed`，Agent 以后读文件仍可能看到旧成功。修复 `check-provider.mjs`，配置失败复用同一原子写入路径；存储失败单独报告。既有 `scripts/provider-recovery.test.mjs` 复现后 5 项通过。新增 `scripts/verify-provider-check-runtime.py` 在已安装 APK 的 Ubuntu / Node 22 中执行相同脚本（隔离数据目录），先成功再无效配置，实际只有一次 HTTP 请求，第二次磁盘记录为失败。首次新验证脚本漏传 workspace 挂载环境，定位为测试准备错误，补齐后通过，没有修改生产挂载逻辑。
2. **删除最后一条自定义请求头没有持久化。** 删除按钮未更新 dirty/revision；即使 dirty=true，保存判断仍用已脱敏为空的 profile summary 比较空 map，从而跳过保存。删除复用已有 `_onCustomHeadersChanged()`，保存依据已有 dirty 与有效草稿；移除无效比较函数。`model_provider_setting_page_test.dart` 验证空认证拒绝、修正成功、删除最后一项后确实写入空 headers。页面 24 项及全量 Flutter 1297 项通过。`verify-provider-header-ui.mjs` 在模拟器真实设置界面新增、移出焦点保存、删除并重启：既有原生 Provider owner 发出的三类探针请求均验证新增时携带、删除后及重启后不携带测试头，9 次请求通过。只对合成 Provider 操作。前几次测试准备问题（UIAutomator 无字段标签、同步子进程阻塞同进程测试服务器、收起键盘仍保留焦点）保留原始失败；修正测试准备，没有伪造结果或改写数据库。
3. **真实模型的操作与完成报告仍有问题，未宣称修复。** `xiaowan-local-api-long-task.en.json` 的文件阶段，模型把目标目录写成普通文件，触发后续 ENOTDIR，多次调用后自行纠正。工具准确返回失败，但保存的报告声称没有错误。既有断言保留失败，不把最终得到文件等同于全程正确。证据 `live-tools/tool-results.json` 及该目录的失败报告。未添加路径特例、自动重试或第二套 Agent 循环。
4. **重开设置后，保存过的请求头被错误显示为“未配置”，且不能直接清除。** 原生已经提供 `hasCustomHeaders`，页面却只检查脱敏后空 map。复用该状态显示“已保存（内容隐藏）”，清除按钮写入现有 Provider 的空请求头草稿，不新增协议，不把密钥传回界面。新 widget 回归先失败后通过，页面测试增至 25 项，全量 Flutter 增至 1298 项。最终候选 APK 覆盖安装后，`header-ui-reopen/result.json` 验证新增、删除、重新新增、离开重开、点击清除、重启的 15 次实际请求头状态均通过。

## 执行验证

| 范围 | 实际执行入口 | 本轮结果 |
| --- | --- | --- |
| 真实模型运行终端、停止子进程、下一轮、重启 | `xiaowan-terminal-child-stop.en.json` | 12 步通过；检查 App UID、PID 生命周期，子进程在自然到期前退出 |
| SSE 内部报错且不关闭连接；半截工具调用 | `xiaowan-inband-provider-error.en.json` | 17 步通过；半截工具未执行，下一轮及重启正确 |
| 未知工具、非法参数、缺失文件、exit、timeout | `xiaowan-tool-failures.en.json` | 18 步通过；失败交回同一轮，工具结果身份唯一，下一步恢复 |
| 连续五次取消与继续 | `xiaowan-repeat-cancel-recovery.en.json` | 43 步通过；每轮取消/完成与重启一致 |
| Provider 401、429、503、输出前/中途断流 | `xiaowan-provider-failures.en.json` | 45 步通过；每次失败后实际读取文件继续，重启恢复 |
| 持久终端会话与技能发现 | `xiaowan-session-tools.en.json` | 9 步通过；cwd/env 保留、失败后继续、停止、重复停止；技能列举/读取与缺失错误 |
| 定时任务创建/更新/删除与重启 | `xiaowan-schedule-lifecycle.en.json` | 7 步通过；任务保持禁用，未发通知或运行后台工作 |
| 命令菜单能力及权限设置弹窗 | `xiaowan-command-capabilities.en.json`、`permission-popup-back.en.json` | 6 + 12 步通过；只显示实际支持命令；反复打开/返回及重启 |
| 最后一条请求头删除持久化 | `verify-provider-header-ui.mjs` | 模拟器 UI + 新增/删除/重启后三类实际请求验证通过 |
| 12 个设置入口进入/返回 | `verify-settings-entry-ui.mjs` | 全部通过，仅入口 smoke：账号、Provider、场景、记忆、Agent、终端、MCP、外观、杂项、权限、存储、关于 |
| 真实模型多工具文件操作 | `xiaowan-local-api-long-task.en.json` | 文件阶段失败；见上文，不降低断言 |
| 二十页真实模型读取、重启、预期错误恢复 | `xiaowan-live-long-recovery.en.json` | 11 步通过；20 个连续偏移均实际成功，重启后验证文件缺失、真实 exit 7、随后 echo 成功。完整回复等待 537909ms，约 9 分钟，不含自动化逐字输入；性能仍需定位 |
| 技能代码更新、学习数据跨两次启动保留 | `verify-provider-learning-persistence.py` | 已安装候选 APK 上通过 |
| 自检先成功再配置错误 | `verify-provider-check-runtime.py` | 已安装 Ubuntu 运行时通过 |
| Shizuku 特权确认 | `xiaowan-live-permission-deny.en.json` | 前置能力不可用；模型如实报告工具不存在，不算授权弹窗验收 |
| Alpine stdin/退出码 | `verify-terminal-stdio.py` | 前置未安装 Alpine rootfs，本次未执行到探针；不能算 stdin 产品故障或通过 |

确定性 Provider 仅安排脱敏测试工具调用和注入网络错误；App 的 UI、ACP、工具执行和历史是真实路径。此类用例证明执行链路，不证明真实模型的规划质量。真实 Provider 用例单独列出。测试不通过写数据库或伪造回复建立成功结果。

## 构建与本地检查

- App 原生单元测试：1102 项、0 失败、0 跳过。前两次与其他任务共享 Gradle 结果目录发生 `in-progress-results-generic.bin` 丢失，失败日志保留；无其他构建并发后重跑通过。
- Flutter：最终全量 1298 项通过。初跑 1 项失败即请求头删除问题，另补保存状态回归先失败后通过，保留失败与修复日志。
- `scripts/test-agent-runtime.sh` 的离线协议/Node/Python/WebChat typecheck/build 检查通过。真实服务商 smoke 与所有外部 Harness 验收未运行，脚本明确报告 INCOMPLETE。
- 两次候选 APK 均构建并保留数据覆盖安装成功。首包 SHA-256：`2120af9651689e55d149bf5152644dee9e73f68b5c9934d6477101a147f15187`。安装前的前三组 UI 执行使用之前候选包；连续取消、设置入口和长任务使用首包。仅增加保存状态显示/清除入口后的最终包 SHA-256：`d21df7cd309abd9d07d0de6ecc50466348eb5c0461547c0db07ce21a49ec4c3b`，安装包 hash 一致，重开清除验证在最终包执行。
- 成功的执行 journey 共 180 个步骤，另有 12 个设置页入口/返回检查及 Provider 请求头、运行时探针。失败记录单独保留，`journey-summary.json` 不把失败步骤计入通过。
- 已通过 App UI 删除本轮合成 Provider，恢复原编辑 Provider；安全查询确认原 profiles、scene bindings、editing profile 均与测试前相同。临时两个端口转发及本地测试服务已移除，见 `cleanup.json`。

## 覆盖边界

不能把普通聊天、网络失败和单元测试通过等同于所有页面、全部外部 Harness 或生产网络验收。所有页面入口尚未逐一覆盖；Shizuku、其他 Harness、官网跨设备、MCP 外部服务、真实模型误报完成仍需按各自前置条件和验收目标检查。既有 OmniFlow 六项实际模拟器生命周期回归见 [上一轮记录](omniflow-package-refactor-20260916.md)，不冒充本轮重跑。

长任务约 9 分钟是实测性能问题线索，不能仅凭这个时间归因于模型、网络或本地实现；`long-tool-timeline.json` 是提交时间，不是独立工具耗时。该项功能正确不等于速度已达标。

新增运行入口：
```sh
python3 scripts/verify-provider-check-runtime.py emulator-5580 /tmp/provider-runtime.json
# 按脚本头部准备合成 Provider 并停留在该设置页；原配置必须保存并在结束后恢复。
node scripts/verify-provider-header-ui.mjs emulator-5580 /tmp/provider-header-ui
# 起始页为英文 Settings；不修改页内选项。
node scripts/verify-settings-entry-ui.mjs emulator-5580 /tmp/settings-entry-ui
node scripts/verify-agent-user-journey.mjs emulator-5580 scripts/fixtures/agent-user-journeys/xiaowan-live-long-recovery.en.json /tmp/live-long-recovery
```
长任务前置为至少 20 × 65536 字符的合成 `workspace/oob-file-repro/large.html` 与 `workspace/oob-live-integration`；可复用 `scripts/fixtures/generate-context-files.py`。正常 UI 发送真实 Provider 请求，不能用可控服务端替代该项。受控错误的准备沿用 `scripts/fixtures/file-read-provider.mjs` 与既有 journey，第二行文本固定为 `second line\n`。
