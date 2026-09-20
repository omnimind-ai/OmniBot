# 小万流式内容暂时回退

用户反馈：已输出内容消失一部分，稍后又回来。定位并复现其中一条代码路径，不代表已经复现用户真机的全部触发条件。

根因：`persistConversationMessageSnapshot` 合并同 id 消息时，异步页面快照替换了更新的 ACP 投影。下一次 delta 从 reducer 的完整缓存追加，因此文字恢复。修复在原 coordinator 内保持 ACP item 的正文、思考和生命周期所有权，页面只补充 linkPreviews。新增消息仍合并；空闲时显式 allowHistoryRemoval 仍支持删除。没有新增协议、reducer 或重试。

## 可执行回归

入口：`ui/test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart`。

- `stale page save never briefly rolls back a streaming Xiaowan reply`：保存 First 的旧快照前接收 second，监听每次通知均须为 First second；随后 third 必须完整追加。修复前实际失败，通知值为 First。
- `stale page enrichment preserves agent_message_chunk through completion`。
- `stale page enrichment preserves agent_thought_chunk through completion`。

后两项检查链接预览更新不回退内容、外部消息只添加一次、官方完成后迟到快照不回退生命周期、空闲显式删除有效。使用合成无隐私数据；终止测试显式建立原有 ACP turn reservation。

执行：
```sh
cd ui
/tmp/oob-flutter-3.47.2/bin/flutter test --no-pub test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart test/services/agent_event_reducer_test.dart
```

2026-09-13：312 项全部通过。修改文件静态分析无 error，有既有 notifyListeners 可见性、unused helper 和语法建议共 10 项；不是零告警。DevelopStandardDebug 构建成功。
日志：`/tmp/oob-stream-rollback-red.log`、`/tmp/oob-stream-rollback-final.log`、`/tmp/oob-stream-rollback-analyze.log`、`/tmp/oob-stream-rollback-build.log`。
APK：`app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk`，版本 0.6.2.3，本地候选包，包含工作区此前修改，非新发布 release。
SHA256：`90c8d3b2d3a0f51b8ea62bd4c417e92439588ac55bcc264f3ea14cb4366c2695`。

## 真机验收

**待真机验证**。本轮 adb devices -l 无设备，未安装。需要在实体手机运行小万长回复并触发链接预览/外部消息保存，观察正文与思考不回退；完成后重复保存、切换会话、重启恢复并核对历史。以上实际操作未运行，不以单元测试或构建替代。网络中断恢复不在本次修复范围内。
