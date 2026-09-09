# 小万真实 API 自动压缩回归

使用现有 `verify-agent-user-journey.mjs` 和 App UI；不注入模型回复，不调用手动 compact，不改历史数据库。

准备条件：

- 指定隔离模拟器或已授权真机，小万聊天已打开且空闲，使用用户配置的实际 Provider。本次为 emulator-45562、GLM-5.1。
- 记录会话原阈值，通过上下文圆环长按设为 32000。结束后通过同一 UI 恢复原值。
- `/workspace/oob-file-repro/large.html` 为既有脱敏 ASCII 压力样本，至少覆盖 61 个 65536 字符页面；原文件不得被本测试修改。勿替换成私人文件。
- 英文 UI，主机与设备时间一致。每次使用新的证据目录。保持原有 Provider 配置与授权，不清数据。

执行：

```sh
node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/xiaowan-live-auto-compact-60.en.json /tmp/oob-auto-60-unique-run
node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/xiaowan-live-auto-resume.en.json /tmp/oob-auto-resume-unique-run
```

先完成第一组再启动第二组。若观察超时，检查现有轮次，不能重发任务冒充重试。输入工具按字符键入，输入耗时不是 App 首 token 延迟。

第一组 8 步要求：单次发送、60 次且仅 60 次原文件读取、偏移连续、正式完成、摘要 cutoff 晚于本次用户记录且指向完成的工具组、App 重启前后 checkpoint 一致。原有 20 页用例的更强摘要断言与失败记录保留；20 页在工具正文外存后可能不足以产生同任务摘要，不据此声称自动压缩失效。

第二组 4 步要求：在重启后的同一聊天中发送续读请求，实际只调用一次 file_read，原文件路径不变，offset 为 3932160，正式完成。新消息不直接提供路径或偏移量，但提到此前 60 页任务；这证明该续读行为，不等于验证所有摘要语义或正文理解质量。

2026-09-09 实际结果：第一组 8/8、第二组 4/4 均完整运行通过，证据见 `artifacts/auto-compaction-60-20260909/`。没有将两组拼成一次 12 步运行的报告。本次无产品代码修改，物理设备验收仍为 **待真机验证**。
