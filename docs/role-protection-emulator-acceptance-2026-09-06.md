# 安全保护：模拟器 App 验收

设备 emulator-5556（Android 13 arm64）。保留原 App 数据，新建专用对话；Provider 使用 scripts/role-protection-provider-fixture.mjs，监听主机回环 18766，模拟器连接 10.0.2.2。凭据为无效测试字符串，不访问真实模型服务。

这是代理操作真实 Android App 的端到端故障注入：通过模型选择器选择测试模型、输入消息、点击发送，实际走 ACP、Dispatcher、Orchestrator、工具校验、UI 与持久化。模型输出由本地 fixture 控制，不是模型质量测试，也不是人类用户验收。

## 已观测

- `Reply OOB_ROLE_GUARDS`：父级 58 工具，planner 0，explorer 11。fixture 故意向两个子任务返回 file_write。两者回传 role permissions 错误，父级聚合返回 OOB_ROLE_GUARDS_PASS。共 6 个请求（父 2、两个子任务各 2），不是重放。
- `Reply OOB_TRUNCATED_GUARD`：fixture 返回合法 file_write JSON，但 finish_reason=length。实际应用拒绝执行，回传长度上限错误，第二次模型调用输出 OOB_TRUNCATED_GUARD_PASS。
- 工作区中未找到 OOB_FORBIDDEN_ROLE.txt、OOB_FORBIDDEN_TRUNCATED.txt。
- `Reply OOB_DISCONNECT`：fixture 返回 OOB_VISIBLE_BEFORE_DISCONNECT 后关闭 SSE，不发 finish_reason/[DONE]。实际只有 1 个请求，LocalAcpRuntime 记录官方请求错误 `chat completion stream closed before completion signal`，正文保留。
- 第一轮断流 UI 验收失败：错误卡片藏在已折叠过程内，标题显示 Processed。已在现有 timeline 展示投影中将运行时终态失败卡片保留在折叠外，并显示 Failed/执行失败；普通工具错误不等同于整轮失败。
- `Reply OOB_RECOVERY`：下一条用户发送正常返回 OOB_RECOVERY_PASS，只有 1 个新请求。

## 持久回归

- 新增真实 ChatMessageList widget 用例：保留部分正文并显示失败卡片，序列化/恢复后仍有效，1 项通过。
- Timeline + AgentRunHeader 两文件 40 项通过；验证工具错误不会误判为整轮失败。
- 三个改动的 Dart 文件分析无问题。
- 同步修正中英文 subagent schema 中“全部角色继承所有能力”的过时说明。

## 验收边界

模拟器未安装 Shizuku，当前父级目录未暴露高权限工具；不能把角色拒绝测试当作真实高权限审批弹窗 allow/deny 验收。该审批继承路径此前已有原生 Dispatcher/Orchestrator allow/deny 自动化测试。

本次 SSE 测的是连接实际关闭，不代表没有任何错误信号的半开连接。没有增加静默重试或固定运行时长上限。

截图位于 docs/verification/role-protections-e2e-20260906/。本地请求日志 /tmp/oob-role-fixture.log，只记录请求序号/工具数量/断言结果。


## 最终修正版复测

- 整合构建及 AgentToolDefinitionsSubagentTest 成功，日志 /tmp/oob-failure-acceptance-final-build.log（BUILD SUCCESSFUL）。首次构建遇到共享工作区并行构建中间产物冲突，保留失败日志 /tmp/oob-failure-acceptance-apk.log；未安装失败构建产物。
- adb install -r 返回 Success；强制停止并重新启动 App 后，原 OOB_DISCONNECT 历史展示 Failed、失败卡片与原部分正文。
- 新发送 OOB_DISCONNECT_R2：仅请求 #11，显示 Failed/本轮执行失败，部分正文保留。
- 再发送 OOB_ROLE_GUARDS_R2：请求 #12–17，两子任务越权 file_write 仍被拒绝，父级返回 OOB_ROLE_GUARDS_PASS。
- 再次强制停止/重开：R2 失败与成功结果都保留，fixture 请求序号仍停在 #17，没有自动重放。目标工作区没有测试用的禁止写入文件。
- 原 scene.dispatch.model 已恢复为验收前的 debug-llmthu-glm / GLM-5.1；没有删除原 Provider 或原会话。

收尾：回到验收前会话，通过真实模型选择器选回 GLM-5.1，语义标签确认；关闭本地 fixture 服务。测试对话和截图保留，未清除应用数据。
