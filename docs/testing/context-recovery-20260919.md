# 长任务上下文超限恢复 — 2026-09-19

## 实际故障证据

安装包为ProductionStandardRelease测试签名，SHA256 2aaade3817c0b5490f87595e96970b4aa424bd01ffaac6ab69434ee4b2e0990a，emulator-5580 Android13。真实GLM-4.6V读取合成大文件，服务商原错误为 `OpenAIException - Prompt exceeds max length Error happened to model=GLM-4.6V`（HTTP400）。第7请求269354字节失败，原实现缩短后第8请求132754字节返回200。证据：`artifacts/tool-index-20260919/context-diagnostic-fixed/provider.jsonl`。

本次任务最后仅完成6次读取，模型输出了文本形式的伪工具调用并正式结束；没有执行这些文本，也没有算作60页通过。最终原文和身份见同目录terminal-incomplete.json。只停止了等待不存在完成标记的验收观察进程，没有重放模型任务。更早一次诊断准备操作与发送入库发生竞争，转发工具重启中断了已提交请求，独立记录在context-diagnostic/diagnosis.json，不计为产品故障证据。

临时服务地址已恢复为原https://llmapi.paratera.com，强停重开设置验证保存成功；没有改密钥。见artifacts/context-recovery-20260919/provider-restored.json。

## 修复

1. 在成功的模型响应后重置超限恢复标记，后续新增工具结果导致的超限可再次恢复；同一连续拒绝仍只有一次恢复，响应已开始时不恢复。
2. 服务商明确超限时，不让临时offload直接跳过摘要；复用原切分函数按被拒绝的原消息大小确定边界，再使用有界内容摘要及原Room检查点提交。
3. 增加上下文超限的稳定错误分类，中英文说明保留历史、减少输入或选择更大上下文模型；不再误导为一般配置错误，不声称发生了未经证实的重试。

解析、压缩、持久化仍归现有ACP执行器/Conversation所有者；没有第二个Agent循环、模型切换、文本工具执行或消息重放。

## 验证入口与当前状态

- AgentOrchestratorTest、AgentConversationContextCompactorTest新增案例旧实现均失败；修复后连同AgentContextOverflowTest、AgentRuntimeErrorSupportTest共115项通过。XML和unit-results.json见artifacts/context-recovery-20260919。
- `node --test scripts/agent-provider-observer.test.mjs`：3项通过，转发原始响应不变；诊断仅显式开启且限定合成任务，认证字段脱敏，不记录输入正文。
- Flutter错误展示66项通过；定向analyze无error，仍有55项风格提示（详见原始日志），不是零提示。完整Release构建成功，已安装候选24216d21…c9ebd885，设备APK哈希一致。
- 修复后模拟器长任务、真实持久检查点及重启恢复尚未验收；**待模拟器验证、待真机验证**。原60页案例不降级为6页通过。


## 新候选设备验收进行中

APK：`app/build/outputs/release-candidate-20260919-context-recovery/OmniBot-0.6.3-release-test-signed.apk`，完整SHA256 `24216d211b9c1cabfb0f57a81d9a2259d0ef8cfb96a8e0ea80dc754cc9ebd885`，ProductionStandardRelease/Android Debug测试签名，保留数据安装emulator-5580。原服务商直连，新独立本地会话，真实60页journey正在运行，结果目录 `artifacts/context-recovery-20260919/live-60`，尚未计通过。

验收驱动新增仅针对合成DONE标记的提前失败：数据库明确同轮官方完成且所有项已结束、UI无运行按钮，但缺少所需结果时停止观察并记录任务未完成，不改变App状态、不重放提示。33项verifier测试通过；对上一次实际6页后正式结束的会话只读回查也正确识别缺少目标结果。

实际进度补充：同一会话41已观察到22次成功且唯一的file_read，offset从0到1376256连续递增65536；Room摘要检查点已产生并继续更新，最新观察cutoff1440/revision1789784160615。证据live-60/progress-after-compaction.json。60页任务仍运行，未计完整通过，也尚未执行重启验证。

原journey的20分钟回复观察期限届满，result.json明确保留passed=false；当时没有官方失败或完成。随后用live-60/continue-observation.json继续观察同一marker，无send步骤，不重发、不安装、不重启运行中任务。仅在看到原任务完成后才执行原有终态、60页连续性和检查点重启断言；延长观察不能覆盖原时间要求失败。续观测输出live-60-continuation。

实际终态：同一任务正式end_turn、DONE可见，强停重启后回复恢复，摘要hash/cutoff/revision前后一致，续观测前6步通过；第7步严格页数断言失败。只读审计确认61次唯一成功file_read，offset为0..60×65536连续；模型最终声称60页，但多读取了一页。摘要中“58页完成”对应的当前offset3866624实际上为59×65536，计数与游标不一致，证据live-60-continuation/count-failure.json。保留failed结果，不降低60页断言，不发送依赖精确60页成功的第61页续读场景。上下文恢复/重启已有实际证据，任务计数准确性仍未通过，需继续定位并回归。

后续源码定位：强制摘要前对整段历史offload，使待摘要前缀最后一个已完成工具结果只剩文件引用，其完成/游标metadata因不再是整段最新工具而被丢弃。新增forcedSummaryRetainsCompletedPrefixCursorBeforeTailOffloading在旧实现实际失败（Summary lost the completed prefix cursor）；调整调用顺序，让摘要服务按既有预算处理原前缀、保留尾部另按既有预算处理。复用现有Pi cut/rebuild与Gemini输出限制，不新增摘要算法/Agent loop。修复后Compactor23、Orchestrator72、Budget4共99项通过，before/after XML见artifacts/summary-prefix-20260919。尚未安装新修复，不能宣称已证明61/60模型计数问题彻底解决，仍需真实同场景复测。

前缀游标修复候选构建成功4m24s，APK app/build/outputs/release-candidate-20260919-summary-prefix/OmniBot-0.6.3-release-test-signed.apk，SHA256 8a6ead35ba73ca8985f7f2b8b64e5c3ce17c26d5b5a6283fefed790a4ae1b8fc；测试签名apksigner通过。尚未安装/设备验收。

8a6ead35候选已保留数据安装emulator-5580，base.apk哈希一致，installed.json为证。真实60页复测仍未执行；正在准备同包Vibe独立新任务。
