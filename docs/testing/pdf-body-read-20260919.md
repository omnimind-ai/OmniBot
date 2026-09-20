# PDF 附件文字层读取 — 2026-09-19

整体目标中的文档读取缺口，本轮先接入 PDF；DOCX/XLSX 正文解析仍未完成。

## 实现与边界

沿用现有 `file_read -> FileToolHandler -> ContextResult -> ACP/history`，没有新增 Agent、重试或云端上传。引入 Apache-2.0 的 `com.tom-roush:pdfbox-android:2.0.27.0`，由 Android 资源初始化后本地提取文字。原始文件不改写，文字暂存 cache 并在 finally 删除。offset/lineStart/lineCount/maxChars 复用原文本分页实现，每次读取重新提取，以原文件为准。

- 支持普通 PDF 文字层，不声称理解图片、复杂表格或扫描页。
- 无文字：document_ocr_required；密码保护：document_password_required；权限限制：document_access_denied；损坏：document_parse_failed。
- 所有上述结果 contentAvailable=false、工具 success=false，摘要明确原因，保留附件。
- 输入上限 64 MiB、提取文字上限 16M 字符、PDFBox 临时存储上限 128 MiB。超限明确失败，不用静默截断伪装全文。
- 文字输出与分页前检查协程取消。底层同步 PDF 解析期间无法保证即时取消，仍需真机大文件验证。
- PDFBox 只做文字提取；OCR、旧 DOC/XLS 与 Office 正文尚未接入。

## 执行回归

`AgentPdfReadSupportTest` 三项通过：实际创建 PDF/真实 PDFBox 提取/分页重新打开/源文件不变/临时文件清理；空白和损坏失败；实际加密文件失败。测试只将 Android AssetManager 映射到同版本 AAR 原始资源，解析器未 mock。字体测试资源经 Gradle 从同版本依赖解包，不进入主 APK 测试资产。

`AgentFileReadSupportTest` 与上述同时通过；既有分页、Unicode 与双语提示保留。

```
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests '*AgentPdfReadSupportTest' --tests '*AgentFileReadSupportTest'
./gradlew --no-daemon --no-parallel :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
```

既有 `generate-context-files.py` PDF fixture 故意是损坏文件，`file-read-provider.mjs` 预期改为 document_parse_failed，继续要求失败与无正文，未降低断言。

**待真机验证**：目前无设备连接。系统文件选择器上传真实文字 PDF、实际模型引用未提示的正文、扫描/加密失败反馈、取消后继续、重启后再次读取。同一最终 Release 仍需统一验收；JVM 解析通过不等于手机聊天验收。

上游： https://github.com/TomRoush/PdfBox-Android/releases/tag/v2.0.27.0

## 集成构建修正与真机执行入口

首次 APK 构建发现 PDFBox 旧版 BouncyCastle 与项目已有 bcprov-jdk18on 重复。排除 PDFBox 的旧系列，使用 bcpkix-jdk18on 1.85 / bcutil 1.85，与已有 bcprov 1.85.2 共同解析，并合并重复 LICENSE.md/NOTICE.md（不丢弃许可证）。新系列下的加密 PDF 用例必须重跑通过才算集成验证。

新增合成 `scripts/fixtures/chat-upload-invoice.pdf`，包含发票代码、总额和重启回读字段，无私人信息。新增 JVM 用例用实际解析器核对这一份上传样本。

真机待执行：
```
python3 scripts/prepare-chat-image.py SERIAL chat-upload-invoice.pdf
# 通过系统文件选择器附加 PDF；独占空闲对话后：
node scripts/verify-agent-user-journey.mjs SERIAL scripts/fixtures/agent-user-journeys/chat-upload-pdf.en.json OUTPUT
```

提示不包含期望值，Journey 核对实际回复、正式完成、重启历史和原 PDF 回读。当前尚未运行此设备用例。

最终本地结果：4 项 PDF + 8 项文本/提示测试通过，0 失败/跳过；加密 PDF 在统一后的 BC 依赖上通过。Debug APK 构建成功，SHA256 `ae8334a2856d76fd57de84b8b18fa900298b695f9d6b8c3b14885fbfa7107abb`。脚本语法与 diff 检查通过，尚未安装真机。
