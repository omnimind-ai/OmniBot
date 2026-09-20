# Word / Excel 附件正文读取 — 2026-09-19

整体目标仍进行中。本轮接入 DOCX/XLSX，沿用 FileToolHandler / file_read 和现有分页，不添加 Agent、下载解析器、联网转换或历史旁路。

## 实现范围

- 使用系统 ZIP/SAX 读取 OOXML。DOCX 正文段落/表格文字、换行与制表符；不把删除修订和 moveFrom 内容算作正文。
- XLSX 按 workbook relationship 定位实际工作表，保留工作表名称和单元格位置；支持共享字符串、inline string、布尔值与原始值。
- 公式原文与文件缓存值同时返回，明确未计算；不执行宏、公式、外部关系或 XML 实体。
- 日期、百分比等格式目前返回原始存储值及 style 索引，明确说明未格式化；图片、图表、页眉页脚、嵌入对象不在提取范围。旧 DOC/XLS 未接入，不宣称支持。
- 空文档/空工作簿无正文时失败，不把工作表名称当作已读取内容。结构损坏、不支持的关系、加密文件/非 ZIP、外部实体明确失败；不返回已读部分假装全部成功。
- 64 MiB 输入/总 XML 解压预算，16 MiB 单 XML 与16M字符输出预算，最多10000 ZIP成员。读取期间检查取消。分页复用 AgentFileReadSupport，源文件不修改。

## 回归

- AgentOfficeReadSupportTest：真实 python-docx 1.2.0 / openpyxl 3.1.5 生成的合成附件、DOCX分页/再次读取/源文件不变、XLSX多工作表/公式/布尔值、共享字符串自定义关系及错误索引、空工作簿不误报正文、XML实体和外部工作表拒绝、损坏文件。
- 测试输入仅 CEDAR-7391 / 37 / MAPLE-8264 等合成值，不包含私人文件。样本位于 scripts/fixtures/chat-upload-office.docx、chat-upload-office.xlsx。
- 既有 prepare-chat-image.py 扩展合成 Office 样本；新增 chat-upload-docx.en.json / chat-upload-xlsx.en.json Journey，要求真实模型读取未在提示泄露的值、正式终结、重启历史和回读第二字段。

```
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests '*AgentOfficeReadSupportTest' --tests '*AgentPdfReadSupportTest' --tests '*AgentFileReadSupportTest' :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
python3 scripts/prepare-chat-image.py SERIAL chat-upload-office.docx
node scripts/verify-agent-user-journey.mjs SERIAL scripts/fixtures/agent-user-journeys/chat-upload-docx.en.json OUTPUT
# 在新的空闲对话同样执行 xlsx 样本与对应 Journey。
```

当前没有可用 ADB 真机。**待真机验证**：Android SAX实现、系统选择器上传、真实模型读取、Office错误后继续、重启回读；不能以 JVM 测试替代。

规范参考：
- https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/working-with-sheets
- https://learn.microsoft.com/en-us/office/open-xml/word/how-to-open-and-add-text-to-a-word-processing-document

最终本地结果：Office 6 项、PDF 4 项、原文本/提示 8 项全部通过；Debug APK 构建成功，SHA256 `5885664bf12417d511a385f973678de268714ffc92d00e77c9da41fb68251067`。上传驱动语法和 Journey 引用检查通过。仍待真机验收。
