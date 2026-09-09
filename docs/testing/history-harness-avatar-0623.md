# 历史会话 Harness 图标回归

用户反馈：切回原有 DSH 历史，右上角仍显示另一个 Harness。
根因：chat_page.dart 的 _appBarActiveAcpAgentId 优先采用全局 connected runtime，覆盖当前 Conversation 的绑定身份。此优先级来自 8203f5a428。
修改：复用 _activeAcpAgentId，沿用既有切换临时态和已提交 Conversation 身份；不新增生命周期或发送路由。

可执行入口（ui/）：
- flutter test test/features/home/pages/chat/chat_architecture_test.dart：22 通过，含身份来源防回归。
- flutter test test/features/home/pages/chat/widgets/chat_app_bar_test.dart：31 通过，含 DSH/Codex/DSH 更新及卸载重建头像。组件模拟，不代表完整历史恢复通过。
- assembleDevelopStandardDebug 构建通过。

人工验收：打开 DSH 历史 → 小万历史 → DSH 历史，检查右上角及菜单选择，再重开应用重复；在独立测试会话发送短句并核对对应 Agent 工具/运行记录，不能只凭模型自称验证路由。
模拟器完整用户操作待验收，待真机验证。

安装：emulator-5554，adb install -r Success，已打开应用，保留原数据。Dart analyze 无 error，存在未改动的 agent_run_timeline.dart unused_import 警告（退出码 2）。
