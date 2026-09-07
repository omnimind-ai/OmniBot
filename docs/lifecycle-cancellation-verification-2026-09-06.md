# 原有 ACP 生命周期：取消结果持久化

## 责任边界

Conversation → ACP Session → prompt reservation / Turn → Item。

取消结果由原 session/prompt 返回值进入 ChatConversationRuntimeCoordinator 和 AgentEventReducer；不是根据文本、停止按钮点击或网络静默推断。既有请求归属检查拒绝迟到的重复 PromptResponse，不增加状态机、定时器、重试、轮数或 Token 上限。

## 修改

- 原 reducer 在完成既有消息时，保存官方 stopReason 到原消息的 streamMeta；同一消息保留正文、工具记录及身份。
- 原时间线据此展示 Cancelled/已取消，支持已经输出正文与只有工具记录两种情况。普通工具失败不等于整轮失败。
- 既有失败卡片优先保持失败展示；没有以取消覆盖错误卡片或修改 ACP wire。

## 自动化

137 项 Coordinator、Timeline、Header 测试通过（/tmp/oob-lifecycle-tests-final.log）。新增覆盖：输出一半后取消、序列化恢复、重复成功响应不能改写取消、工具-only 取消不被隐藏、取消和失败标题。

Dart 分析无错误；AgentEventReducer 的两处既有 nullable 提示仍在（/tmp/oob-lifecycle-analyze.log），本轮没有借机重构。

## 模拟器实际 App 操作

emulator-5556，专用测试对话。scripts/role-protection-provider-fixture.mjs 使用本地测试 Provider，输出 OOB_PARTIAL_BEFORE_CANCEL 后保持连接；通过真实输入框发送 Reply OOB_CANCEL_BASELINE，再点击真实 Stop 按钮。

- UI 显示 Cancelled，部分正文及耗时保留。
- 服务端观察连接关闭，只有 1 个请求，没有自动重放。
- 强制停止/重开 App，重新进入专用对话并滚动到该轮，仍显示 Cancelled 和原部分正文。
- 截图在 docs/verification/lifecycle-cancel-e2e-20260906/。

这是代理操作真实 App + 可控 Provider 的故障注入，不是人类用户试用，不验证真实模型质量。

最终整合 APK 构建成功（/tmp/oob-lifecycle-apk.log，3m11s），adb install -r 返回 Success。安装后重新打开仍显示已取消与部分正文，当前模型已恢复 GLM-5.1；final-installed.png 记录该状态。没有清除应用数据。
