# 文件读取内存回归：HTML、文本及二进制

## 引入时间与位置

以下日期为 Git 提交时间（北京时间），不等于用户下载安装时间。提交来自本地完整 Git 历史，remote 为 `https://github.com/omnimind-ai/OmniBot.git`。

| 时间 | 提交 | 变化 |
| --- | --- | --- |
| v0.6.0.3 基线 | `8dc72e554` | `FileToolHandler` 默认返回 8000 字符，可选参数限制在 128–64000；但底层已经是 `file.readText()`，所以旧版本也有全文分配隐患。 |
| 2026-09-02 02:19:10 | [`051eb544`](https://github.com/omnimind-ai/OmniBot/commit/051eb54429c5d72d06e8c4001dcfd0b1d032fa95) | `FileToolHandler.executeFileRead` 将默认 8000 字符改为 `runtimeSettings.fileReadMaxChars`，该设置默认 null，导致默认返回全文。这是文本返回量回归的明确引入点。 |
| 2026-09-05 15:54:59 | [`49b7b520`](https://github.com/omnimind-ai/OmniBot/commit/49b7b5205ae962fb56270a0b4d7db9a454f2f81e) | `AgentImageAttachmentSupport` 删除模型图片及历史预览压缩，改为原图 data URL；文件读取又去掉运行时上限来源，只保留显式 maxChars。 |
| 2026-09-05 23:56:12 | [`85939b735`](https://github.com/omnimind-ai/OmniBot/commit/85939b73512be22b492b95c8f3d6f901a3f98131) | `FileToolHandler` 删除剩余 maxChars 参数及截断分支，始终返回 sliced 全文。 |
| 2026-09-06 00:57:24 | [`9a02db13a`](https://github.com/omnimind-ai/OmniBot/commit/9a02db13ae88eaa589d0903805f809b11c770634) | 这些变化进入 v0.6.1 / PR #522（版本代码 11）。 |

根因是取消压缩/返回上限后，未同时消除原有全文读取和多重 JSON 包装。`AgentEventAdapter.toolResultContent` 将 previewJson、rawResultJson 同时放入工具结果，历史与通道还会复制字符串。上述改动放大了已有内存隐患；不能将所有相关机制都说成 9 月新引入，也不能据此断言原反馈手机的每次退出都是这一原因（该手机尚无原始 logcat）。

图片实际崩溃和前一轮修复见 [图片验证报告](image-read-crash-2026-09-07.md)。

## HTML 复现

- 隔离 Android 13 ARM64 AVD `emulator-5560`，2 GB RAM，应用 heap growth limit 192 MiB。
- 使用本机受控 Provider，不调用外部模型；在图片修复已生效的 APK 上读取一个约 16 MiB、单行很长的 HTML。
- 2026-09-07 14:34:32，PID 12182 在 `AgentEventAdapter.toolResultContent` 的 JSON 编码处申请 134221976 bytes（约 128 MiB）失败，`Loopback ACP server failed`。
- 此次 HTML 测试复现的是 OOM 导致 Agent 中断，进程仍在；不是图片测试中的主线程进程退出。
- [原始失败堆栈](artifacts/file-read-2026-09-07/html-before-oom.txt)。

## 修复

沿用既有 Conversation → ACP Session → Turn → Item，以及 `file_read` 工具所有者。没有增加生命周期、重试、重放或第二个 reducer。

- `AgentFileReadSupport` 按 Reader 流式读取，每页最多 65536 个 UTF-16 字符单位，返回 offset、returnedChars、hasMore、nextOffset。继续读取使用 nextOffset，不同时传 lineStart。保留原文件，无文件总大小配额。
- 按行读取不再先构造整份字符串和行列表；保留 CR/LF/CRLF，继续位置与原文一致，分页不拆开有效 Unicode 代理对；支持 UTF-16 BOM。
- PDF、音视频、压缩包及检测到二进制控制字节的文件返回元数据和原文件附件，不再错误地按 UTF-8 解码。需要提取内容时由相应解析工具处理；这不是 PDF/OCR 内容提取功能。
- `FileToolHandler` 对同一返回 payload 只编码一次。既有模型结果和 UI 投影边界保持不变。
- 工具说明明确分页继续方式，避免把单页当成完整文件。

## 验证

- 55 项 Kotlin 测试通过：新增文件读取 4 项，图片附件 8 项，工具图片 ACP payload 2 项，既有 ACP presentation 41 项。
- 新测试覆盖超长单行有限读取、Unicode/换行跨页无损重组、行区间与 EOF、二进制识别及 UTF-16 文本。
- APK 构建成功，覆盖安装到同一隔离模拟器。PID 12922：HTML 同一逻辑 turn 依次读取首页、下一页和末尾，受控 Provider 校验返回值分别与原文切片完全相等，长度为 65536、65536、128；收到 `OOB_FILE_HTML_DONE`。
- HTML 最大续答请求 349642 bytes。工具完成后进入下一回合时仍包含历史，不能将此值当成任意长对话的请求总上限。
- PDF `file_read` 返回 kind=binary、contentAvailable=false、无文本 content，完成 `OOB_FILE_PDF_DONE`。测试 PDF 是二进制识别样本，不是可渲染 PDF，不能作为 PDF 页面预览验收。
- 文本指定第 2 行、1 行，准确返回 `second line\n`（12 字符），完成 `OOB_FILE_TEXT_DONE`。重启后历史保留，PID 13733 手动发送的新文本回合再次通过同一断言；重启自身没有发出模型请求或重放工具。
- 两个修复后进程的采集日志未出现 OutOfMemoryError、FATAL EXCEPTION 或 Loopback ACP server failed。[验证请求摘要](artifacts/file-read-2026-09-07/provider.jsonl)、[完成截图](artifacts/file-read-2026-09-07/completed.png)、[重启后历史](artifacts/file-read-2026-09-07/after-restart.png)。截图中 Interrupted 为保留的修复前复现记录。
- 原 HTML SHA-256 在读取前后不变：`a6313e3bf4d2edfabbe3d0b5519cd74e9793b1e5c212a989690a7e2b8296df95`。

APK：`app/build/outputs/file-read-fix-20260907/OpenOmniBot-file-read-fix-debug.apk`。

SHA-256：`bf2707ed790304dfd2f42513da2886302069c166f7e77f4cb8f8fb5d40616a67`。

## 重跑

复用图片报告的隔离 AVD、测试 Provider 配置和工作区准备步骤。生成本机 fixtures：

```python
from pathlib import Path
p = Path('/tmp/oob-file-repro')
p.mkdir(exist_ok=True)
(p/'large.html').write_text('<!doctype html><html><title>OOB regression</title><body><!--' + '0123456789abcdef' * 1024 * 1024 + '-->HTML_END</body></html>')
(p/'notes.txt').write_text('first line\nsecond line\n第三行 😀\nTEXT_END\n')
(p/'sample.pdf').write_bytes(b'%PDF-1.4\n%\x00\xff\x10binary fixture\n%%EOF\n')
```

将三个文件复制到模拟器 App 的 `workspace/oob-file-repro/`，以 `/workspace/oob-file-repro/` 路径供工具访问。启动：

```bash
OOB_FILE_TEST_DIR=/tmp/oob-file-repro node scripts/fixtures/file-read-provider.mjs
node scripts/send-agent-test-message.mjs emulator-5560 OOB_FILE_HTML
node scripts/send-agent-test-message.mjs emulator-5560 OOB_FILE_PDF
node scripts/send-agent-test-message.mjs emulator-5560 OOB_FILE_TEXT
```

每个发送都应等待对应 DONE，再发送下一个；fixture 会校验内容，失败返回 HTTP 500 并记录原因。保留失败 turn，不自动重放。完成后重启 App 检查历史，再手动发送新文本回合。

## 其他入口审计边界

- HTML 预览通过 `WebViewController.loadFile` 加载路径；音视频播放器使用文件路径，PDF 逐页渲染。它们不走这次已复现的 `file_read` 全文 JSON 路径。此结论来自源码检查，未进行浏览器验收。
- 文本/代码编辑器 `omnibot_artifact_preview_page.dart` 仍有 `File.readAsString` 全文加载；`PdfPreviewChannel` 仅限制渲染宽度，极端长宽比页面的位图高度仍可很大。这些是独立待复现风险，未在此次修改中解决。
- 终端输出和文件列表/搜索也值得单独压力测试；没有把这些入口计入本次通过范围。
- 未连接用户反馈的另一台手机，仍需该手机实测；模拟器修复验证不等于原机验收。
