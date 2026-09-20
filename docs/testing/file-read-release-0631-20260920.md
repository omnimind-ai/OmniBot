# 0.6.3.1 Release 文件读取实测（2026-09-20）

设备：OobRegression20260909 / emulator-5582，Android 13 ARM64。版本 0.6.3.1 / code 17，ProductionStandardRelease，R8 压缩混淆、不可调试，本地 Android Debug 测试证书签名（不是 Debug 构建，也不是正式发布签名）。已核对设备 base.apk SHA256 为 `0cd3ff0adb01fe96424f0adf6bca273405c68309e8fae96b055e7a534c15b33f`。

通过真实 Android 文件选择器附加合成文件、真实模型回答；没有用模拟 Provider。TXT/PDF/Office 使用 GLM-5.1；PNG 使用 GLM-4.6V。Release 历史检查仅通过测试模拟器 root 的 SQLite 只读备份；没有修改应用可调试标志或会话数据。

| 样本 | 结果 | 实际验证 |
| --- | --- | --- |
| TXT | 8/8 通过 | 读取正文、隐藏校验值、重启保留及原附件回读；两次真实 file_read 各 115 字符 |
| 文本 PDF | 8/8 通过 | PDFBox 2.0.27.0 实际读取 61 字符；重启后再次读取及核对第二字段 |
| DOCX | 8/8 通过 | ooxml-text/1 实际读取 63 字符；正文校验与重启回读 |
| XLSX | 8/8 通过 | ooxml-text/1 实际读取 241 字符；工作表/单元格与重启回读 |
| 损坏 PDF | 8/8 通过（预期失败反馈） | document_parse_failed、无正文；下一条 17+25 回答 42，未重放工具；重启保留 |
| 仅图片 PDF | 8/8 通过（预期失败反馈） | document_ocr_required、无正文；后续对话及重启正常；不代表支持扫描件 OCR |
| 加密 PDF | 8/8 通过（预期失败反馈） | document_password_required、无正文；后续对话及重启正常 |
| PNG | 未通过 | 真实上传与发送成功；未获得颜色答案。当前回合最终为官方 error，Provider HTTPS 证书校验失败，不能认定图片解析成功或失败 |

加密 PDF 后普通请求的等待约 69 秒，其他同类请求通常更短；保留延迟现象，不推断为解析器性能问题。PNG 失败后检查自动时间为 1，设备与主机时间一致，未关闭 TLS 校验，也未修改证书信任。之后模型列表也因安全连接校验失败而无法加载，无法通过 UI 恢复 GLM-5.1；测试结束仍选中 GLM-4.6V。PNG 的 Journey 在 120 秒等待窗口未获得答案，随后只读历史确认回合最终为证书错误，不把超时等同于正文解析失败。

## 可执行入口

复用 `scripts/prepare-chat-image.py`、`scripts/verify-agent-user-journey.mjs` 和 `scripts/assert-agent-turn-outcome.py`。先确认空闲聊天、模型已配置、adb 在 PATH 内，逐个运行，禁止同时控制同一设备：

```sh
python3 scripts/prepare-chat-image.py emulator-5582 chat-upload-invoice.pdf
OOB_TEST_RELEASE_READ=1 node scripts/verify-agent-user-journey.mjs emulator-5582 scripts/fixtures/agent-user-journeys/chat-upload-pdf.en.json artifacts/file-read-0631-20260920/pdf
```

对应其余样本：`chat-upload-record.txt` → `chat-upload-record.en.json`；`chat-upload-office.docx` → `chat-upload-docx.en.json`；`chat-upload-office.xlsx` → `chat-upload-xlsx.en.json`；`chat-upload-broken.pdf` → `chat-document-failure-recovery.en.json`；`chat-upload-scan.pdf` → `chat-scan-pdf.en.json`；`chat-upload-password.pdf` → `chat-password-pdf.en.json`；`chat-upload-colors.png` → `chat-upload-colors.en.json`（先选视觉模型）。

本轮新增扫描和加密 PDF 固定合成样本及对应 Journey，扩展既有断言以核对具体错误码、无正文、下一轮及重启。加密样本仅测试密码拒绝，密码是合成测试值 `fixture-only-password`，不是用户凭据。测试驱动 2 项单测通过，修改的 Python 脚本编译检查通过。

证据：`artifacts/file-read-0631-20260920/{pdf,docx,xlsx,txt,broken,scan,password,png}/` 中的 result.json、真实回合断言和截图；`installed.json` 核对安装包，`summary.json` 汇总。证据截图可能包含同一测试设备既有会话视图，不纳入公开提交。

## 验收边界

本轮仅是模拟器实测，**待真机验证**。未验证旧版 DOC/XLS、PPT、复杂版式、图表、公式计算、大文件性能及所有语言。Office 仍只提取文字/原始单元格值；扫描件不支持 OCR。未更改生产解析代码，也未替换 Docling。正式 GitHub Beta 发布包采用不同签名，不把本记录表述成正式签名包已通过真机验收。
