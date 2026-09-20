# Release 候选构建 — 2026-09-19

实际执行 assembleProductionStandardRelease，失败于 validateSigningProductionStandardRelease：Keystore file not set for signing config release。未修改发布签名配置、凭据或keystore。

用户要求分发测试，因此后续候选使用本机既有 ~/.android/debug.keystore 通过一次性Gradle参数签名，仍为ProductionStandardRelease构建；仅供测试，不冒充正式发布签名。当前构建运行中，不能宣称APK已产出或验收通过。最终整体验收仍缺Vibe、远程恢复与同一候选包主要流程。

后续R8实际失败：PDFBox-Android 2.0.27.0引用可选com.gemalto.jp2.JP2Decoder。核对上游README及该版本JPXFilter源码，解码前Class.forName检查，缺失返回MissingImageReaderException；本项目PDFTextStripper仅提取文字层。app/proguard-rules.pro仅对该缺失类添加精确dontwarn，不关闭混淆/压缩，不添加整包忽略。

再次ProductionStandardRelease构建成功（3m21s），apksigner verify通过，签名DN Android Debug，证书SHA256 566ba40244f884cee8330f86c68b8ce6c9b0d433c27b08fe79d09aa00113f662。候选文件app/build/outputs/release-candidate-20260919/OmniBot-0.6.3-release-test-signed.apk，文件SHA256 cc3eafc7c7f51e594505e26db3636ff98321b9302f0ccbbedb7080ded1abe31e。

仅为测试签名Release候选，尚未安装该候选进行完整回归，不是正式发布签名包。参考：https://github.com/TomRoush/PdfBox-Android#reading-jpx-images ；https://raw.githubusercontent.com/TomRoush/PdfBox-Android/v2.0.27.0/library/src/main/java/com/tom_roush/pdfbox/filter/JPXFilter.java

最新事件归属/文件无变化反馈修复已在Debug模拟器实测通过，开始重新构建ProductionStandardRelease（仍使用本机既有debug测试签名的一次性参数）。旧候选不包含这些后续修复；新候选尚在构建，待同包回归。

最终候选构建成功（4m16s），文件 `app/build/outputs/release-candidate-20260919-final/OmniBot-0.6.3-release-test-signed.apk`，SHA256 `bd53088f5217be3eb16815a0f61b4e88717e05b0bd00248ac267fd3ecac04f0a`。测试签名校验通过；已覆盖安装 emulator-5580 Android13，设备 base.apk 哈希与候选一致，证据 `artifacts/release-final-20260919/installed.json`。不是正式发布签名，整体回归仍在进行。

首次文件编辑恢复验收进入了 Remote 会话，模型明确报告工具不可用，本地 user admission 断言失败；原始截图/result 保存于 file-edit。原因是 adb shell 路由查询参数未进行设备端 shell 转义。修正为 shlex.join 后进入本地 Agent，以新独立任务执行 file-edit-local；不重放原 Remote turn，首次失败不计通过。

最新 Release 文件编辑恢复实际五步通过：本地 conversation34，file_write 成功、file_edit alpha→alpha 明确 error、alpha→beta success、file_read 正文核对、官方终态与强停重启回复保留。`artifacts/release-final-20260919/file-edit-local/result.json`；真实模型执行，没有注入工具返回。此结果仅覆盖该恢复链路，不代替全部 Release 验收。

同一最终候选 PDF 实际上传/重启回读 8/8 通过：系统文件选择器选择 chat-upload-invoice.pdf，真实 file_read PDF 正文、正确提取、官方完成；强停重启后仍显示结果，再次真实读取原附件和核对 recovery code。证据 `artifacts/release-final-20260919/pdf/result.json`。验证了 R8 Release 中此次文本 PDF 解析，未覆盖扫描 PDF/OCR/JPX 图像解码。

同一最终候选 Life XP 页面和原数据验收通过：实际从插件详情 Open App 打开，历史显示75/60/40/10累计、2026-09-17总75XP、等级2；只读 SQLite 一致快照核对原4习惯/4打卡全部字段未变。证据 `artifacts/release-final-20260919/vibe-history/result.json`、history.png、records.json。入口 `OOB_TEST_RELEASE_READ=1 python3 scripts/verify-life-xp-history-device.py emulator-5580 OUTPUT docs/testing/artifacts/vibe-self-validation-20260919/attempt1/baseline-records.json`。复用一致快照帮助函数，允许严格限定的local.project数据库路径，不修改Release权限或业务数据。这里只验证已人工修复项目的真实页面和升级后数据，不宣称模型自主造App成功或Release跨午夜已复测。

同一最终候选 Remote：真实手机输入任务A，电脑创建并读回 release-20260919.txt；强停重启应用停在 Remote 首页，自动显示原回复观察180秒失败（restart-history.json保留）。通过真实侧栏 Computer→Create and read release file 重开原会话，原回复恢复（reopen-history.json通过），继续任务B读A并创建/读回B通过。独立宿主文件核对见 remote/files.json，两次真实UI任务见 real-task.json、continued-task.json。结论是手动重开历史和继续执行通过；不声称自动恢复原会话通过，不覆盖本次Release断网/公网/TLS/打洞。

候选后续实际OmniFlow验收发现并修复Release日志字段混淆，以及官方action_type未被UI识别。最新测试签名候选改为 `app/build/outputs/release-candidate-20260919-runlog-ui-fixed/OmniBot-0.6.3-release-test-signed.apk`，SHA256 `953fd15cb394562612c0ffb8a0cc2aa8520cb60438bc7c88dafa33975aa05563`。已安装；新旧日志重启读取及动作显示通过，详见release-runlog-serialization-20260919.md。原final目录候选有已知日志序列化bug，不能作为最终建议分发包。最新候选其他大功能回归继续进行。

最新953fd15c候选：完整无障碍恢复+参数化wifi回放通过；新旧日志强停重开、具体动作名称、选定运行时间和原文件哈希通过。真实红/蓝及绿/黄两张图片各8步上传识图/官方完成/重启/模型选择保留通过，另只读独立核对两份持久附件字节与原图一致、会话目录和promptPath一致，见release-runlog-ui-fixed-20260919/image、image-second、image-persistence.json。

自动压缩60页准备首次发现既有large.html只有1900026字节，不足60×65536；发送驱动在输入阶段被停止，DB核对旧marker用户入库数0，失败auto-context/result.json保留。新增独立样本准备入口prepare-auto-context-fixture.py，复用既有确定性生成器，16,777,301字节，SHA256 a6313e3b…296df95；不覆盖原文件、不写Agent历史。新journey xiaowan-release-auto-compact-60.en.json采用独立路径，正式结果待验证。

后续实际结果：60页任务失败，8次 file_read 成功后服务商 HTTP 400，未产生持久化摘要检查点。证据 `artifacts/release-runlog-ui-fixed-20260919/auto-context-ready/result.json` 和 `terminal-error.json`。原始服务商拒绝原因尚未确定，不能仅凭400断定上下文超限，也不能把本次计为自动压缩通过。

失败后在原会话36发送独立17+25任务，真实模型回答42、官方完成、没有重放前一任务工具，强停重启回复保留，5/5通过：`artifacts/release-runlog-ui-fixed-20260919/context-failure-recovery/result.json`。入口 `OOB_TEST_RELEASE_READ=1 node scripts/verify-agent-user-journey.mjs emulator-5580 scripts/fixtures/agent-user-journeys/chat-context-failure-recovery.en.json OUTPUT`。这是模拟器真实用户操作与真实模型验收，物理设备仍待验证。

源码另外修复工具流跨index猜测：后到名称的工具参数曾被拼进前一个工具。回归修复前复现失败，修复后解析器25项、HTTP客户端35项及错误处理18项通过。证据 `artifacts/tool-index-20260919/`。此修复尚未打入上述953fd15c候选、尚未设备验收；不得据此声称已发布或完全修复。

## 大功能收口顺序

| 功能 | 当前证据 | 尚缺验收 |
| --- | --- | --- |
| 聊天图片 | 953fd15c真实两图上传、识图、重启和附件字节保留通过 | 最终包含工具流修复的同包复测 |
| PDF、Word、Excel | PDF较早Release实测；DOCX/XLSX早期Debug实测 | 最终Release三种正文读取、坏文件反馈及重启回读；扫描PDF未覆盖 |
| Vibe应用生成 | 人工修复的Life XP真实页面和历史数据通过 | 从空项目由模型生成并自主测试/返工通过；不能用现成项目替代 |
| OmniFlow | 953fd15c参数化wifi回放、无障碍恢复、新旧日志重启通过 | 更广任务、取消/悬浮层恢复及最终同包回归 |
| Codex Remote | 较早Release真实远端写入、手动重开历史和继续任务通过 | 自动恢复会话、公网/TLS/NAT及电脑休眠恢复 |
| 长任务与失败恢复 | 真实HTTP400后新任务和重启5步通过 | 长任务400原因、实际自动压缩和检查点恢复 |
| 本地模型 | 用户明确暂停本部分工作 | 保持现状，不扩展本轮范围 |

优先完成工具流设备回归和长任务故障定位，再统一候选包跑附件、Vibe、OmniFlow和Remote。当前不是全功能正式发布验收通过。


## 工具流归属修复候选（后续）

最新候选为 `app/build/outputs/release-candidate-20260919-tool-index/OmniBot-0.6.3-release-test-signed.apk`，SHA256 `2aaade3817c0b5490f87595e96970b4aa424bd01ffaac6ab69434ee4b2e0990a`。ProductionStandardRelease，仍为Android Debug测试签名。已保留数据安装emulator-5580 Android13，设备base.apk哈希核对一致，见 `artifacts/tool-index-20260919/device-files.json`。旧953fd15c候选不包含工具流归属修复。

- 工具流受控服务商验收6/6：第二工具参数先到、名称后到，实际两次独立file_write，正式完成和重启历史通过；独立核对两份文件内容与哈希。`device/result.json`、`persisted-files.json`。这是协议注入验收，不能冒充真实GLM生成。
- 切回真实GLM-4.6V，文件编辑失败→修正→正文读回→强停重启5/5通过：`live-file-edit/result.json`。
- 同包真实DOCX系统选择器上传、正文值核对、正式完成、重启原回复与再次读取原附件8/8通过：`docx/result.json`。正文断言要求ooxml-text/1及contentAvailable=true，非仅凭模型回复。
- Kotlin解析器25、HTTP客户端35、错误处理18项通过；服务商fixture4项和终态verifier32项通过。模拟器验收不是物理设备验收，待真机验证。

尚缺同包其余功能验收；真实60页长任务HTTP400尚未修复，不宣称全功能通过。

- 同一2aaade38候选真实GLM-4.6V的XLSX系统选择器上传、表格正文值核对、正式完成、重启保留及再次读取原附件8/8通过，证据 `artifacts/tool-index-20260919/xlsx/result.json`。本轮受控工具流6步、真实文件编辑5步、DOCX8步、XLSX8步共27步通过，范围见该目录summary.json；不是整包全部功能通过。

- 真实GLM长任务HTTP400已捕获明确原因Prompt exceeds max length。现已修复成功响应后的恢复标记重置、强制摘要被offload提前返回和被替代消息大小误导的摘要边界，并增加明确上下文错误反馈。115项Kotlin、66项Flutter、3项观察工具回归通过；修复后Release模拟器验收仍待执行。原GLM地址已恢复并强停重开确认。详见[长任务恢复记录](context-recovery-20260919.md)，不把本次6页后文本伪工具调用的失败计为60页通过。

- 后续Remote自动启动目标保存及RunLog截图官方坐标/缩放修复候选d989f74a…78d343已构建、apksigner验证，目录release-candidate-20260919-remote-startup-scaled；仍是Android Debug测试签名。54项Remote相关Flutter检查、4项RunLog页面检查通过，包内Vibe0.2.2逐字节校验通过。尚未安装，设备仍在24216d21候选运行原60页任务，不能标记最终同包验收通过。

- d989f74a测试签名Release / emulator-5580：Remote原B强停普通启动自动恢复通过；恢复后手机真实发送C、同一后端会话唯一执行、宿主文件正确且旧B保留通过；第二次强停普通启动自动恢复最新C也通过。证据artifacts/remote-startup-20260919/{automatic-restart,continued-real-task,continued-backend-files,second-automatic-restart}.json。自动恢复问题在本模拟器实际场景验收通过，待真机验证；不代表公网/TLS/NAT/休眠通过。

- 后续最新候选为 release-candidate-20260919-vibe-config / SHA256 46a10f277a37fe45848336dab19f837f74a181819f6c85f63ff05f421e2db86e，ProductionStandardRelease 测试签名，已安装 emulator-5580 并核对一致。新增源发布检查拒绝 SQLite 忽略配置，保留已有插件加载与数据库；定向回归通过。新检查真实模型验收及其余同包回归仍待完成。Vibe旧候选真实水打卡10XP通过，但连续运动按钮被禁用、独立历史/跨日测试失败，不能计从零自主App验收通过。

- 当前最新候选 release-candidate-20260919-runlog-units，SHA2561742ecd745e0d3b3ec73639ccc37d2c0fa6a9d3a80a36b0d81331ebc28092b1c，测试签名Release，安装与哈希确认。实际RunLog强停重开、3步/动作保留、截图点像素误差<1px且原日志不变通过。Vibe仍在独立监督返工，60页与其他同包回归待执行，不计整包完成。
