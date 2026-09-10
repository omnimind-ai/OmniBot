# 无上下文统计时阈值入口缺失

## 原因与修改

`ConversationModel.contextUsageRatio` 在阈值缺失或尚无统计时返回 null，这是正确的未知状态。两个输入框布局却以 ratio 非 null 为唯一显示条件，连长按调整入口也一并隐藏。

- 显示条件改为“有用量或提供了阈值调整回调”，不改变 Conversation 的统计语义，也不为未知值填充假 token 数量。
- 无统计时展示带问号的圆环；点击提示暂无数据，长按使用原有调整弹窗。收到统计后恢复用量环，真实 0 用量不显示问号。
- 未设置阈值的提示也说明长按可调整。
- 调整弹窗将未知统计显示为“暂无数据”，避免显示假 0 / 0%。保存继续使用原 ConversationService / StorageService 流程。
- 不支持此入口的表面（无统计且无回调，例如原 OpenClaw 路径）继续不显示。消息编辑期间的专用操作栏保持原有行为。

## 验证

1. 先添加大/小输入框无数据入口回归测试，修复前两项失败；修复后整个 `chat_input_area_test.dart` 的 30 项测试通过。补充未知 → 真实 0 用量切换后，两项聚焦测试再次通过。
2. 输入框和测试文件 Dart analyze 无问题；聊天 page / extension 文件仍有原有 `unnecessary_this`、`invalid_use_of_protected_member` 提示，本次改动没有新增错误。
3. 最终 APK 构建成功（52 秒），覆盖安装到 `emulator-5560` 成功。
4. 既有 DSH 测试对话没有上下文统计；真实界面显示 Context usage 入口，点击显示 `No context usage data yet / Long press to adjust threshold`。
5. 长按打开 `Adjust Context Threshold`；当前上下文和占用比例均显示 `No data yet`。选择 64k 后目标和输入框更新为 64,000 / 64000，状态显示 Auto-saved。
6. force-stop 并重新启动应用，切换模拟器到深色主题，入口仍清晰可见；再次长按仍显示 64,000 和 Auto-saved。测试没有改动真实手机。

APK SHA-256：`63331f143bfde9cc046d1163bc1a2f433124ddcdab260ea34dd1191b7da94421`。

复现/回归命令：

```sh
cd ui
flutter test test/features/home/pages/command_overlay/widgets/chat_input_area_test.dart
```

截图：[深色主题入口](artifacts/context-threshold-2026-09-07/oob-context-ring-dark.png)、[阈值保存](artifacts/context-threshold-2026-09-07/oob-context-threshold-saved.png)。这是 Agent 操作的模拟器验收，不是人工用户或真实手机验收。
