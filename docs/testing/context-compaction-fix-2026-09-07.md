# 小万 Kotlin 上下文超限修复

状态：修复代码、本地测试、APK 构建及模拟器回归完成；**待真机验证**。尚未取得原报错服务的模型信息及长度单位，因此不宣称原手机和原服务已验收。

## 改动与复用来源

采用 Kotlin 源码移植，不引入 TS 运行时或第二套 Agent loop。上游版本、许可证和移植差异见 `../third-party/compaction.md`。

- `AgentContextBudget` 移植 Pi 的 usage + trailing messages 估算和按最近内容预算选择切点的行为；按工具调用配对选择已完成边界，同一用户任务可以压缩较早片段。保留原始用户请求及系统消息。
- 使用 Gemini CLI 的多语言估算与旧工具结果预算策略：完整结果写入既有 workspace offload 文件，模型得到可通过 file_read 取回的路径和有界末尾摘录。原始 Conversation 内容不删、不覆盖。
- `AgentOrchestrator` 在每次请求前维护上下文，包括首次历史、无 usage 和新增工具结果；工具 schema 开销单独计入预算。没有自动重放工具或 ACP turn。
- `AgentConversationContextCompactor` 复核摘要输入和重建后的大小；摘要输出设置明确预算。失败、空摘要、截断或重建后仍过大时停止当前请求，不继续发送已知超限原文。
- `AgentOrchestrator` 识别服务端明确的 `Prompt exceeds max length` / `Input length ... exceeds ...` 拒绝；若该请求尚未开始输出，则只在同一 ACP turn 内强制调用既有压缩器并重建一次请求。已经开始输出时不重放、不创建新 turn。
- 既有 Conversation 检查点支持已完成工具组边界。只接受完整、唯一的 canonical tool/assistant 匹配；日志投影尚未提交或身份有歧义时不推进持久化 cutoff，原始历史保留，下一次加载由发送前检查重新维护上下文。
- 小万子任务使用同一压缩器工厂，但 conversationId 为 null，不写父会话摘要；手动压缩也接入既有 offload 能力。

此次没有修改模型容量与用户阈值的配置同步逻辑，也没有修改外部 ACP Harness 内部循环。估算不是精确 tokenizer；不可分割且过大的用户输入会明确报错，不能保证任意单条输入都能自动处理。

## 本地长期回归

144 项定向 JVM 测试通过，0 失败：

| 测试类 | 数量 |
| --- | ---: |
| AgentContextBudgetTest | 4 |
| AgentConversationContextCompactorTest | 11 |
| AgentOrchestratorTest | 60 |
| AgentConversationHistorySupportTest | 39 |
| AgentConversationHistoryRepositoryTest | 3 |
| HttpControllerCustomHeadersTest | 3 |
| HttpControllerResponsesTest | 24 |

新增用例覆盖：

- 超过 1,048,566 的受控输入限制：不启用预算维护时复现 400；启用后继续成功，工具只执行一次。这个测试明确按文本长度建立限制，并不假定原服务也使用该单位。
- 当本地 token 估算低于阈值但服务端按另一长度单位拒绝时，复现 `Prompt exceeds max length`；同一 turn 强制压缩后恢复，工具只执行一次；已开始输出的拒绝仍保持失败且不重放。
- 无 usage、Unicode、图片不按 Base64 文本计数、整数溢出。
- 单条长任务多次工具读取，保留最近调用/结果配对；未完成并行工具组不能被丢弃。
- 完整大结果保存与可读取引用、不可缩减输入、摘要过长、失败与取消不提交错误检查点。
- 完整唯一的工具组才能形成持久化 cutoff，恢复仅重放保留的上下文片段，歧义和未落盘不猜测。

相关测试仍属于本地受控验证；没有在本项目运行 Pi 的原 TS 测试套件，也没有在真实外部模型上验证摘要质量。

## 模拟器实际操作

设备：Android 13 ARM64，隔离 AVD `OobCleanInstall20260907` / `emulator-5560`。保留旧数据，安装修复 APK 后新建小万对话。测试 Provider 为本机受控接口，没有调用外部收费模型。

执行入口：

```sh
OOB_FILE_TEST_DIR=/tmp/oob-file-repro OOB_FILE_TEST_IMAGE=/tmp/oob-large.png \
  node scripts/fixtures/file-read-provider.mjs
node scripts/verify-agent-user-journey.mjs emulator-5560 \
  scripts/fixtures/agent-user-journeys/xiaowan-context-overflow.en.json \
  docs/testing/artifacts/context-compaction-fix-2026-09-07
```

用例：一条任务连续 file_read 20 次，每次 65,536 字符；完成后重启 App，确认结果恢复，再执行另一条连续读取 20 次的任务。

- 6 个 UI 步骤全通过，本次不需要人工补发。
- 42 次模型请求全部通过，两个任务各 20 次读取；没有重复执行或 HTTP 400。
- 接口硬上限是 `JSON.stringify(messages).length <= 1,048,566`。实际最大为 **384,306 字符**；包括工具定义等字段的最大请求体为 421,059 字节。
- 当前任务最多 17 条旧工具结果以可读取文件引用进入模型上下文。
- 从模拟器读取本次生成的 32 个完整 offload 文件，解码后按 offset 对比原 HTML，正文全部一致。
- App 进程 4080 → 重启后的 4867；对应日志未发现 OOM、FATAL EXCEPTION 或 ANR。

这里的设备场景实际触发的是工具结果 offload，维护后已低于预算，无须调用摘要模型。**同一任务摘要切分与持久化边界由本地测试验证，不将其冒称为真实模型/真机摘要验收。**

证据：`artifacts/context-compaction-fix-2026-09-07/`，包含 UI result.json、截图、Provider 请求计数、offload 完整性与生命周期记录。

## APK

构建：`:app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart` 成功，安装使用 `adb install -r`。

路径：`app/build/outputs/context-compaction-fix-20260907/OpenOmniBot-context-compaction-debug.apk`

SHA-256：`c00f0f3c3bba75c18d1178c384ebdc1d1dc2f6ac5fd1d85704ca2d20120798c8`，已与模拟器安装后的 base.apk 校验一致。

下一验收：在原问题手机和模型上重复原任务，覆盖自动摘要的实际调用、取消、重启与继续执行；记录服务端容量单位和实际 usage。未连接实体设备前不得标为真机通过。
