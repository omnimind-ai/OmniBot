# 附件实际上传与前台服务 — 2026-09-19

按用户更新要求，在 emulator-5580（Android 13 ARM64）执行真实文件选择与聊天任务；真实 GLM Provider，未注入回复。

## 已复现

1. PDF 从系统选择器选入，点击发送后 App 崩溃：`ForegroundServiceDidNotStartInTimeException`。短任务结束时调用 stopService，服务尚未完成 foreground 启动握手。原始失败见 `artifacts/document-live-20260919/pdf/foreground-crash.txt`。
2. 修复服务后再次实际发送，App 保持运行、输入框清空并显示失败。正式 ACP 错误为附件不存在。历史里的持久附件实际存在，picker 缓存文件也仍存在：ACP ResourceLink 使用 `/workspace/...`，小万材料化阶段误把 shell 路径当 Android 磁盘路径。

## 修改与验证

- TaskRuntime 最后一项任务结束时向同一 Service 发送 reconciliation，由 Service 完成 foreground 握手后自行停止。重复 finish 无副作用；不改变 ACP 生命周期。TaskRuntimeTest 2 项和 RemoteCodexAppServerSessionTest 14 项通过，Debug 构建成功。
- 安装上述包 SHA256 `c96d4e2375b44cdef4258ab1ebfa03732b48c44fd0fa4d3a7821eebac69bde55`，真实发送复现同类快速失败（46ms）；foreground 服务创建/销毁正常，未新增崩溃。完整 PDF Journey 仍失败，不能标为正文验收通过。证据见 `artifacts/document-live-20260919/pdf-service-fix/`。
- 附件准备入口复用 AgentWorkspaceManager.androidPathForShell 解析 ACP 工作区引用，普通 Android 路径照常读取。新增持久资源引用回归，后续构建与真实模型读取仍在验证。

入口沿用 `prepare-chat-image.py` 与 `verify-agent-user-journey.mjs`、`chat-upload-pdf.en.json`，包含真实读取、正式完成、重启历史、回读第二个字段。保留失败轮次，不用旧回复匹配新任务。

## 路径修复后的实际结果

- 47 项 JVM 回归通过（附件 3、ACP presentation 42、TaskRuntime 2）；Debug 构建与覆盖安装成功。APK SHA256 `137b633b5599cb34ddf7722753141a71003e7d1c997a390172bf90cd36f35e2f`。
- PDF 实际 file_read 返回 `contentAvailable=true`、`pdfbox-android/2.0.27.0` 和三行完整正文，模型正确回答 CEDAR-7391 / 37 / MAPLE-8264，正式 end_turn。原 Journey 首行格式断言失败，因为模型采用分段回答；失败未改写。正文原始证据见 `pdf-path-fix/body-evidence.json` 与截图。
- 后续只继续该已完成会话，不重发原问题。重启、显示历史、再次真实发送读取请求、file_read 正文和正式完成均通过（`pdf-restart/result.json`，6 步）。
- 文档 Journey 更新为 UI 完成标记加同轮正文断言：必须有对应 PDF/DOCX/XLSX 解析器、contentAvailable、实际 file_read 正文和模型回答字段；重启回读也必须有新的工具读取，不能仅从模型记忆回答。测试输入不泄露预期值，不以固定首行排版取代内容验收。
- Word、Excel 各 8 步实际用户流程全部通过（`docx/result.json`、`xlsx/result.json`）：系统选文件、真实模型正文回答、同轮工具正文/正式完成、重启历史、再次发送回读、重新执行解析工具并完成。
- 模拟器 App 为 0.6.3 (16) developStandardDebug，Android 13 ARM64；上述 APK 未变更。GLM-4.6V 使用设备既有 Provider。没有用模拟 Provider 代替模型，没有新增崩溃记录。
- 对真实 DOCX 结果的本地内存数据库副本删除 `file_read.result.content`，保留成功标志，再执行同一断言，按预期拒绝；设备数据库未更改。此项只是断言反向验证，不算新的设备任务。
- 整体目标、复杂文档、同一会话错误后继续场景、最新版图片回归与最终 Release 验收尚未完成。
