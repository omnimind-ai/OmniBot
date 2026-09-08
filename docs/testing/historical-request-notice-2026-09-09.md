# 历史确认卡误报 requestId 缺失

用户确认截图来自历史会话：Processed 下的 Implement this plan? 显示“请求缺少 requestId，已跳过交互”。此前修复禁止历史确认再次操作，但精简卡仍优先显示缺少传输标识的提示，且没有展示持久化的处理结果；不可用的 user_input 还可能提示在下方回复。

修复仅在现有 AgentRequestNotice 展示边界：使用已有 status，已保存结果优先于交互不可用提示；accepted/declined/submitted 等分别显示已有结果。明确 session_ended 显示“历史请求，无法继续操作”；仅缺少标识不推断历史或已批准，显示“该请求当前无法操作”。不可用的 user_input 不再提示回复，有效 pending approval 的原 ACP 回复与持久化路径不变。不生成 requestId、不重放请求、不修改历史或新增生命周期。

## 回归入口与结果

```sh
cd ui
flutter test --no-pub test/features/home/pages/command_overlay/widgets/agent_request_card_test.dart
```

新增三项回归：无 requestId 的已处理结果展示、session_ended 输入不提示继续回复、未知来源的缺失标识不猜测历史/批准。既有确认提交、持久化后销毁重建等测试保留。

当前合并主线执行被工具链/依赖阻塞：本机 Flutter 3.35.7 / Dart 3.9.2，主线要求 Flutter 3.47.2 / Dart ^3.13.0；`--no-pub` 也因缺少 android_file_picker、flutter_markdown_plus、Riverpod legacy 依赖而编译失败，不能记为通过。

为验证相同故障路径，在隔离的合并前基线 `f07a09841` 上使用现有依赖配置运行同一测试文件：修复前两项新增测试实际失败，找到错误文本；复制完全相同的卡片修复和三项新增测试后，15 项测试全部通过。这是兼容基线验证，不是合并后主线或新 APK 的验收。

尚未构建包含本修复的新 APK，模拟器原历史卡重开验收待执行，**待真机验证**。恢复新工具链后必须在当前主线重跑，并实际验证原历史卡不再显示内部错误、新请求仍可提交且重启不重复操作。
