# 小万自动压缩局部恢复

用户明确要求回退自动压缩删除。源提交为 49b7b5205ae962fb56270a0b4d7db9a454f2f81e（2026-09-05），其中把移除宿主限制/自行续跑扩大到了小万自身上下文管理；实现、依赖注入和调用点一起删除。此次只参考该提交父版本恢复必要片段，未整笔撤销其 195 个文件修改。

恢复范围：

- AgentConversationContextCompactor：原有容量判断、预留余量、compactIfNeeded、运行窗口摘要与历史 checkpoint 写入。
- OmniAgentExecutor：在小万执行器中注入同一压缩器。
- AgentOrchestrator：每轮获得 usage 后检查上下文；压缩后更新现有 memory，并继续同一 prompt。当前用户消息与其工具调用保留。
- 取消异常向外传播；正常压缩失败保留原消息，不写入替代摘要。

保留现状：外部 Harness 的会话/压缩机制，官方 ACP 终态、取消、历史投影，现有手动 /compact。未恢复 overflow 后重新请求、私有压缩 presentation 事件或新的用户 turn。

原有阈值策略：会话配置与已知模型容量取小值；缺省容量 128000。预留容量的 1/8，并限制在 2048–16384 token 范围内，小容量不超过一半。128000 容量下，报告用量超过 112000 触发。用量计入输入和输出，并处理整数溢出。当前回退仍依赖 Provider 报告 usage；没有可信 usage 时不凭空声称已经触发。

验证内容：阈值/模型容量、输入输出用量、实际摘要方法调用与 checkpoint 写入、失败不提交、取消传播、下一次模型请求使用摘要、工具调用只执行一次、当前用户消息保持不变。复用现有 Compactor / Orchestrator / HistorySupport 测试。

另一个独立问题：CursorWindow 的单行大小限制不由上下文压缩解决。当前每页 16 条仍可能读到一条过大 payload，数据库分块读取尚未在此次局部回退中实现。不能声称此次回退已修复 Row too big。

验证结果：上述三组原生测试共 101 项通过（/tmp/oob-restore-compaction-verified.log，BUILD SUCCESSFUL）；另有 3 项 ACP 压缩边界源码契约测试通过。Android debug APK 已构建成功（/tmp/oob-restore-compaction-final.log 中 assembleDevelopStandardDebug 完成；同次运行两项测试夹具失败在后续测试中修正）。未安装到用户手机，未声称线上长对话或 CursorWindow 问题已完成真机验收。

后续同日继续按用户要求审查其余误删项：已实现大记录无损分块读取和工具长度终止保护，见 removal-audit-49b7b5205-2026-09-06.md。上文“未实现 CursorWindow 修复”描述的是自动压缩局部回退时的阶段状态。
