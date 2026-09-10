# 权限菜单系统返回退出 App

## 设备复现

2026-09-09，在 emulator-45562 / Android 13 ARM64 的 Codex 会话打开 Agent permissions，未选择任何权限，按系统返回。前台实际变为 Nexus Launcher。随后终端测试在第一步 Send 就失败，尚未提交请求，因此不属于模型接口失败或工具执行失败。

旧 APK `8a3e5e93fe4501245b676a13b728542080f5901a4a9e5cb0bb473181c84bca3b`，会话 7 与全局权限均为 workspace-write。

## 原因与修复

`chat_input_agent_controls.dart` 中权限菜单与旧运行设置菜单调用通用 `showOverlayGlassPopup` 时显式设置 `dismissOnBackButton: false`；聊天根页面的 PopScope 没有接管它们，所以 Android 将返回交给了根 Activity。

删除这两处禁用，复用通用弹层已有的 PopEntry 和 BackButtonListener。通用弹层只在 Router 存在时挂 BackButtonListener；Navigator-only 宿主继续用现有 PopEntry。这既保留 App 的顶层弹层优先级，也避免普通 MaterialApp 中 Router.of 异常。未改变权限默认值、UI 样式或 ACP 状态。

## 测试

`chat_input_area_test.dart` 新增两条真实组件测试：弹层打开时必须声明 doNotPop，系统返回关闭弹层并保留输入框，配置回调不触发，关闭后恢复原路由返回行为。旧实现两条都以实际 bubble 与期望 doNotPop 不符失败。

初版测试使用 pumpAndSettle，被输入框持续动画卡住，不能当作产品失败；中间修复在没有 Router 的旧测试宿主中暴露异常。两份中间失败均保留，最终修正等待方式与通用组件宿主兼容性。

```sh
cd ui
flutter test test/features/home/pages/command_overlay/widgets/chat_input_area_test.dart test/widgets/glass_popup_back_test.dart test/widgets/glass_popup_keyboard_test.dart
```

相关 Flutter **38 项通过**，Node 协议/脚本 **48 项通过**。证据 `artifacts/permission-popup-back-20260909/`。

新 APK `6731411b77c88286ee7d45985ae8f307802d67aaac2661497bdda08e33f20daa` 构建并保留数据安装。真实模拟器打开权限菜单→系统返回→保留对话已通过；对应动作加入既有 journey runner 的 `dismiss-permissions`，以及 `codex-real-terminal-exit.en.json`。原测试未发送消息，修复后才开始新的运行。

旧运行设置入口的精确返回目前只有 Flutter 测试证据；物理手机仍不可用。**待真机验证**。

新包另行运行 `permission-popup-back.en.json`，两次连续打开/返回，再重启后第三次打开/返回，完整 **12/12 步通过**。重启后只读核对 Conversation 7 仍为 workspace-write。首轮在重启后因 UIAutomator null root 未能点击，保留失败记录；增加只读就绪等待后重新完整执行，未发送任何模型请求。
