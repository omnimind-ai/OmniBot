# 小块文件正文回归

## 缺口与修改

底层 `readPage` 已支持 `maxChars`，但文件入口和模型 schema 未暴露它。单行 HTML 无法通过 `lineCount` 缩小到 64K 字符以下；正文被外存后，模型缺少按小块取回原文的参数。

将现有 `maxChars` 贯通 schema、文件工具和读取器，允许 2–65536 字符，默认值保持 65536。沿用原有 Unicode 分页、nextOffset 和 ACP 工具生命周期，没有新增压缩算法。

## 可执行验证

- `AgentFileReadSupportTest`：小块重开文件后完整重建单行 Unicode 正文，原文件未改动，非法上限拒绝；中英文模型 schema 暴露参数。共 6 项已运行通过。
- `scripts/fixtures/attachments/small-page-body.html`：第二页包含校验值，提示不含答案。
- 将该脱敏样本放在隔离测试设备 `/workspace/oob-small-page-body.html`，打开使用本地 API 的小万会话，运行：

```sh
node scripts/verify-agent-user-journey.mjs emulator-45562 scripts/fixtures/agent-user-journeys/xiaowan-small-page-body.en.json /tmp/oob-small-page-body-unique
```

断言恰好两次成功 file_read，maxChars=2048，偏移 0 和 2048，正式完成，并且助手正文报告文件中的校验值。该用例验证正文读取，不独立证明自动摘要语义完整性。

2026-09-09：APK 构建及保留数据安装成功，SHA256 `24ec680f3912055b8800d985757421a8dbb770cb8eea99a976efa54ee2761a62`。使用现有本地 API 的模拟器用例完整 4/4 通过，模型实际报告了第二页校验值。证据见 `artifacts/small-file-pages-20260909/`。该小块读取用例没有单独强制触发新一轮压缩。没有物理手机，**待真机验证**。

重启重复入口：`scripts/fixtures/agent-user-journeys/xiaowan-small-page-body-restart.en.json`。2026-09-09 完整 5/5 通过，证据见 `artifacts/small-body-compaction-20260909/restart-journey.json`。同目录保留 91 项压缩相关测试与测试前提失败记录。
