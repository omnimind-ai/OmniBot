# 小万工具结果去重

## 范围

按用户明确收敛后的范围，仅处理小万自己负责的工具读取、模型结果和 ACP 展示结果。不同 Harness 负责各自内部 loop 和工具策略；本轮未修改 LocalAcpRuntime 文件回调、外部 Harness 适配器、工具名称/schema 映射或 ACP 生命周期。

前一轮的图片读取修复、通用文本分页继续保留。这里的分页是小万工具每次返回一部分内容、由模型按 nextOffset 继续读取的策略，不是强加给其他 Agent 的 ACP 协议扩展。

## 失败证据及修复

新增 `XiaowanToolResultPayloadTest` 先在原实现运行，2 项测试均失败，其中正文唯一性断言为 `expected:<1> but was:<4>`。

原因：小万 `toolResultAcpPayload` 把同一份正文同时写入 previewJson、rawResultJson、result、rawResult，后续每次序列化都会复制。模型 `AgentEventAdapter` 则重复携带相同 previewJson 与 rawResultJson。

修复沿用已有两处序列化边界：

- 模型结果保留完整 rawResultJson；previewJson 与其完全相同时省略 previewJson，不同则两者都保留。
- UI 的 ACP rawOutput 保留一个结构化 result。原始结果与预览不同时，另外保留完整 rawResultJson；不再产生 previewJson 和 rawResult 别名副本。
- 适用于小万的 ContextResult、McpResult、MemoryResult、TerminalResult、Interrupted 等结果类型，不靠工具名判断；独立摘要、终端状态、图片引用、附件和动作保持原有语义。
- 复用现有共享 Flutter reducer 对 result 的支持，无新增事件、状态机、工具名转换或运行时组件。旧历史的兼容读取保留。

这消除了相同预览/原始结果的别名复制，并不声称任意大的插件结果或无限历史都具有固定内存占用。原始结果、不同的预览以及摘要确有不同内容时仍需要保留。

## 回归覆盖

- 文本包含 Unicode、非 JSON 原文和完整长正文；预览与原文相同/不同时分别验证。
- 多种小万结果类别和自定义工具名都走同一去重规则。
- 193 项 Flutter reducer 测试通过；新增案例验证精简结果的完整正文、nextOffset、重复事件合并以及 JSON 历史恢复。
- 既有图片原图输入和本地预览测试继续覆盖图片内容保留，避免通过删除模型所需图像来实现减小 payload。

58 项相关 Kotlin 测试通过，debug APK 构建成功并覆盖安装到隔离 Android 13 ARM64 模拟器 `emulator-5560`。没有连接或安装到用户反馈的另一台手机。

实际小万 `file_read` 验证：

- 同一约 16 MiB HTML 的首页、下一页、末尾分别返回 65536、65536、128 字符，受控 Provider 与原文逐字核对通过，界面出现 `OOB_FILE_HTML_DONE`。
- 每页 65536 字符带来的续答请求增量从旧记录的约 135 KB 降至约 68 KB；这是工具结果增量，不是整份对话的大小或速度承诺。现有历史继续保留。
- PDF 二进制识别样本返回 metadata，完成 `OOB_FILE_PDF_DONE`；本测试不代表 PDF 页面渲染或文本提取验收。
- 测试 Provider 新增断言：模型收到的 previewJson 不得与 rawResultJson 完全相同，确保设备实际请求也不再重复传该正文。
- 18,027,082 字节 PNG 读取完成，模型续答携带完整原图，SHA-256 校验为 `04c39e50a1b369a3580e9b0723f213bc980642599269530a4e48ebdadd1b5956`；界面显示 `OOB_IMAGE_LARGE_DONE`。
- 进程 14512 完成上述测试，重启后进程 15306 保留 HTML/PDF/图片完成记录。重启没有向 Provider 发新请求或重放工具；两个进程的采集日志未出现 OOM、FATAL 或 Loopback ACP failure。
- [完成截图](artifacts/xiaowan-result-2026-09-07/completed.png)、[重启历史](artifacts/xiaowan-result-2026-09-07/after-restart.png)、[文件请求摘要](artifacts/xiaowan-result-2026-09-07/files-provider.jsonl)、[图片请求摘要与哈希](artifacts/xiaowan-result-2026-09-07/image-provider.log)。

APK：`app/build/outputs/xiaowan-read-fix-20260907/OpenOmniBot-xiaowan-read-fix-debug.apk`。

SHA-256：`8698d4fbd47fb80a69f90ededc529d62484ac27a9a64180a85dd7fb063338833`。
