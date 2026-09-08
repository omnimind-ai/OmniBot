# 检查点归属与工具结果保留修复

状态：代码、定向测试与模拟器流程通过；待真机验证。继续使用既有 Kotlin Pi/Gemini 压缩移植，没有引入新的压缩算法、Agent loop 或历史来源。

## 修改

- ConversationDao 普通更新无权改写检查点；摘要使用专用原子提交，要求开始压缩时的版本仍有效。clear 增加版本，迟到快照及迟到摘要均不能复活旧检查点。历史明确编辑后的截断位置映射也通过同一存储所有者提交。
- 检查点定位采用已提交工具调用身份及恢复消息明确生成的行身份，不比较可变正文。保留完整工具组和歧义拒绝；历史投影未到达时不伪造截断位置。
- 原生历史转换和展示保留共享 ACP 卡片已有 toolCallId/sessionId/turnId；不依赖旧 modelAssistantMessageJson 全文存在，不发明新的协议或标识。
- AgentResult.Success 不再携带所有原始工具结果，loop 不再维护无生产消费者的 executedTools 列表。调试入口复用已有 tool_complete 事件；用户历史及模型工具结果路径保留。
- 移除每次请求拼接、编码整段历史的 cache_prefix_fingerprint 诊断代码。

## 回归

已有审计中失败的正文文件化定位和清除后迟到快照测试继续保留。新增共享 ACP 卡片到历史/恢复的身份测试、迟到摘要 CAS 测试、结果对象释放测试。释放测试让模拟执行的协程及测试执行器退出后再检查对象回收，避免测试自身局部变量保留对象造成假失败。

统一入口 scripts/test-context-boundaries.sh：173 项原生测试、336 项 Flutter 测试通过。

新 UI 回归入口 scripts/fixtures/agent-user-journeys/xiaowan-checkpoint-restart.en.json：读取 → /compact → 数据库检查点断言 → 重启 → 相同摘要哈希/截断位置/版本断言 → 继续读取。数据库断言只读，使用合成标记定位测试会话；不是用数据库写入替代用户操作。

本机受控 Provider 的工具调用 ID 增加每次任务的唯一 marker，避免 Provider 重启后计数器从 1 开始而重复使用同一 ACP session 内的 toolCallId。未改变生产身份或重试行为。

真实外部模型摘要质量未验收，实体手机未连接。不能把受控 Provider 的摘要内容当作真实模型质量结论。

最终 11 步检查点 UI 回归、8 项数据库 instrumentation 通过。展示回写身份丢失已补齐测试及修复；最终安装版本、哈希、重启前后摘要一致性和验证边界见 [记忆恢复报告](memory-recovery-2026-09-08.md)。
