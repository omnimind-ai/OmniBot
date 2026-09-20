# 生命周期快照审计与修复

新增两项可执行回归位于 `ui/test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart`，名称前缀 `lifecycle audit:`。本轮只审计与新增失败回归，没有更改产品代码。不得把前一轮 312 项通过当作本轮新增边界已通过。

1. running=false：已接收回复并通过官方 PromptResponse 完成后，安装只有原用户消息的旧历史。预期保留完成回复；实际仅余用户消息。`replaceConversationSnapshot` 的 committedItems 仅替换 incoming 中已有 id，没有补回 incoming 缺失的已提交 item。
2. running=true：同一完成任务安装带旧 isAiResponding/currentDispatchTurnId 的快照。预期仍空闲；实际 hasInFlightTask=true。无绑定任务时快照重新设置活动标志与 activeRunId，但 activeAcpTurnId=null，出现显示在跑而无官方活动 turn 的状态。

证据范围：两个问题在生产 coordinator 接口上确定性复现；未证明小万手机端的实际触发频率。普通 ConversationManager 已在 await 后优先重新取运行时消息，页面同步也优先取 runtime 标志，这些调用保护降低可达性，但底层接口仍允许违反生命周期。不能宣称每次切换或恢复都触发。

执行：
```sh
cd ui
/tmp/oob-flutter-3.47.2/bin/flutter test --no-pub test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart --plain-name 'lifecycle audit:'
/tmp/oob-flutter-3.47.2/bin/flutter test --no-pub test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart test/services/agent_event_reducer_test.dart test/features/home/pages/chat/conversation_manager_lifecycle_test.dart
```

实际结果：针对新增两项，0 通过 / 2 失败；上述相关全集 321 通过 / 2 失败，失败就是这两项，未跳过。日志 `/tmp/oob-lifecycle-audit-red.log`、`/tmp/oob-lifecycle-audit-all.log`。失败测试保留以约束后续修复，不以断言现状或 skip 掩盖问题。

建议修复顺序：先明确普通历史合并与显式用户删除的权限边界，再在既有 coordinator 收口；快照不得重建已结束的任务状态。不能新增 reducer、协议或按文字长度猜新旧。还须保留分页加载和用户主动编辑的语义。

真机：本轮未执行安装或生命周期操作，待真机验证。修复未实施，无新的候选 APK。本次为快照相关定向审计，不是整个 Kotlin/ACP/工具审批/进程恢复生命周期的完整认证。

## 用户同意后的修复结果

以上是修复前诊断记录。现已在原 coordinator 修改：先检查 host task binding，再规范化历史卡片；无活动 reservation 时，历史中的运行标志、思考/压缩状态和 dispatch id 不得创建活动任务。存在 reservation 时保持原 reducer 状态。部分历史省略的当前消息按 id 保留，普通快照没有删除权限；显式 allowHistoryRemoval 仍可清空历史。

新增 `history restore cannot create work and partial pages preserve user messages` 覆盖无绑定恢复、重复部分历史合并、顺序与去重、主动清空。三个已有“活动审批卡”测试改为先通过 beginAcpTurn 登记真实任务，不再用 isAiResponding 模拟任务存在，原审批断言保留。

本轮实际结果：324 项相关 Flutter 测试全部通过（包括原先两项失败）。运行入口同上三文件组合。静态分析无 error，既有警告/建议 10 项。DevelopStandardDebug 构建成功，版本仍为 0.6.2.3，本地候选包包含工作区此前修改，未发布新 release。
日志：`/tmp/oob-lifecycle-fix.log`、`/tmp/oob-lifecycle-fix-analyze.log`、`/tmp/oob-lifecycle-fix-build.log`。
APK：`app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk`。
SHA256：`b7c408b84f1ec3407d9eb6bcd7841c89d6390560ec7193f8ee595faffb58e15a`。

**待真机验证**：本轮 adb devices -l 无设备，未安装；切换会话、后台返回、重启恢复、审批与主动删除的真实操作尚未验收。上述测试包括历史接口恢复，不冒充 App 进程重启验收。
